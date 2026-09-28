package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.alibaba.dashscope.audio.asr.recognition.Recognition
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult
import com.alibaba.dashscope.audio.omni.OmniRealtimeCallback
import com.alibaba.dashscope.audio.omni.OmniRealtimeConfig
import com.alibaba.dashscope.audio.omni.OmniRealtimeConversation
import com.alibaba.dashscope.audio.omni.OmniRealtimeModality
import com.alibaba.dashscope.audio.omni.OmniRealtimeParam
import com.alibaba.dashscope.audio.omni.OmniRealtimeTranscriptionParam
import com.alibaba.dashscope.common.ResultCallback
import com.alibaba.dashscope.utils.Constants
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.DashScopePrefsCompat
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.google.gson.JsonObject
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * DashScope 实时流式 ASR 引擎（SDK）。
 *
 * - Fun-ASR / Qwen-Audio 3.x 走 Recognition + inference WebSocket（支持连接复用）。
 * - Qwen3-ASR-Flash-Realtime 走 OmniRealtimeConversation + realtime WebSocket（不支持复用）。
 * - 每 ~100ms 发送一帧 PCM（16kHz/16bit/mono）。
 */
class DashscopeStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    private val externalPcmMode: Boolean = false
) : StreamingAsrEngine,
    ExternalPcmConsumer,
    AudioFrameSinkOwner {

    override var audioFrameSink: AudioFrameSink? = null

    companion object {
        private const val TAG = "DashscopeStreamAsrEngine"
        private const val WS_URL_CN = "wss://maas.qianwenaiapi.com/api-ws/v1/realtime"
        private const val WS_URL_INTL = "wss://maas.qwencloudapi.com/api-ws/v1/realtime"
        private const val WS_URL_INFER_CN = "wss://maas.qianwenaiapi.com/api-ws/v1/inference"
        private const val WS_URL_INFER_INTL = "wss://maas.qwencloudapi.com/api-ws/v1/inference"
        private const val FINAL_RESULT_TIMEOUT_MS = 6000L
    }

    private val running = AtomicBoolean(false)
    private var audioJob: Job? = null
    private var controlJob: Job? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private var conversation: OmniRealtimeConversation? = null
    private var recognizer: Recognition? = null
    private var useRecognitionProtocol: Boolean = false
    private var selectedModel: String = Prefs.DEFAULT_DASH_MODEL
    private var recognitionReuseKey: DashscopeRecognitionReuseKey? = null

    /** 仅在 Recognition onComplete 后为 true，允许把连接归还到复用缓存。 */
    private val recognitionTaskSucceeded = AtomicBoolean(false)

    // 用于识别结果
    // currentTurnText: 当前已确定的文本（来自 text 事件的 text 字段，用于实时预览）
    // currentTurnStash: 当前未确定的中间文本（来自 text 事件的 stash 字段，用于实时预览）
    // finalTranscript: 用户停止后，由 stop() 触发的最终完整识别结果
    private var currentTurnText: String = ""
    private var currentTurnStash: String = ""
    private var finalTranscript: String? = null
    private var finalResultDeferred: CompletableDeferred<String?>? = null
    private val finalDelivered = AtomicBoolean(false)
    private var apiLogSession: ApiCallLogger.Session? = null

    override val isRunning: Boolean
        get() = running.get()

    private val prebuffer = java.util.ArrayDeque<ByteArray>()
    private val prebufferLock = Any()
    private val externalVadInputLeveler = VadInputLevelerBranch(sampleRate = sampleRate)

    @Volatile private var recognizerReady: Boolean = false

    override fun start() {
        if (running.get()) return
        if (!externalPcmMode) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                listener.onError(context.getString(R.string.error_record_permission_denied))
                return
            }
        }
        if (prefs.dashApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_dashscope_key))
            return
        }

        selectedModel = DashScopePrefsCompat.normalizeDashAsrModel(prefs.dashAsrModel)
        useRecognitionProtocol = DashScopePrefsCompat.isRecognitionStreamingModel(selectedModel)

        running.set(true)
        externalVadInputLeveler.reset()
        currentTurnText = ""
        currentTurnStash = ""
        finalTranscript = null
        finalResultDeferred = null
        finalDelivered.set(false)
        recognitionTaskSucceeded.set(false)
        recognitionReuseKey = null
        apiLogSession = null

        // 在 IO 线程启动 SDK 识别并随后启动采集
        controlJob?.cancel()
        controlJob = scope.launch(Dispatchers.IO) {
            try {
                if (useRecognitionProtocol) {
                    startRecognitionStreaming(selectedModel)
                } else {
                    startQwen3RealtimeStreaming(selectedModel)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start DashScope streaming recognition", t)
                recordApiLogOnce(success = false, error = t.message.orEmpty())
                try {
                    listener.onError(
                        context.getString(
                            R.string.error_recognize_failed_with_reason,
                            t.message ?: ""
                        )
                    )
                } catch (notifyError: Throwable) {
                    Log.e(TAG, "notify error failed", notifyError)
                }
                running.set(false)
                safeClose(disposeRecognition = true, reason = "start_failed")
            }
        }
    }

    private fun startQwen3RealtimeStreaming(model: String) {
        val wsUrl = if (prefs.dashRegion.equals("intl", ignoreCase = true)) {
            WS_URL_INTL
        } else {
            WS_URL_CN
        }
        prepareApiLog(
            wsUrl = wsUrl,
            model = model,
            requestStructure = "SDK WebSocket session; config=modalities, turn_detection, transcription; audio=base64 pcm frames"
        )

        val param = OmniRealtimeParam.builder()
            .model(model)
            .apikey(prefs.dashApiKey)
            .url(wsUrl)
            .build()

        val transcriptionParam = OmniRealtimeTranscriptionParam()
        transcriptionParam.setInputSampleRate(sampleRate)
        transcriptionParam.setInputAudioFormat("pcm")
        prefs.getDashLanguages().firstOrNull()?.let(transcriptionParam::setLanguage)
        val corpus = prefs.dashPrompt.trim()
        if (corpus.isNotEmpty()) {
            transcriptionParam.setCorpusText(corpus)
        }

        val config = OmniRealtimeConfig.builder()
            .modalities(listOf(OmniRealtimeModality.TEXT))
            .enableTurnDetection(false)
            .transcriptionConfig(transcriptionParam)
            .build()

        val callback = object : OmniRealtimeCallback() {
            override fun onOpen() {
                Log.d(TAG, "WebSocket opened, updating session config")
                try {
                    conversation?.updateSession(config)
                    recognizerReady = true
                    flushPrebuffer()
                } catch (t: Throwable) {
                    Log.e(TAG, "updateSession failed", t)
                }
            }

            override fun onEvent(message: JsonObject) {
                handleQwen3RealtimeEvent(message)
            }

            override fun onClose(code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $code $reason")
                if (running.get()) {
                    recordApiLogOnce(success = false, code = code, error = reason)
                    running.set(false)
                    try {
                        listener.onError(
                            context.getString(R.string.error_recognize_failed_with_reason, reason)
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "notify error failed", t)
                    }
                }
            }
        }

        val conv = OmniRealtimeConversation(param, callback)
        conversation = conv
        recognizer = null
        recognizerReady = false
        conv.connect()

        if (!externalPcmMode) {
            startCaptureAndSend()
        }
    }

    private fun handleQwen3RealtimeEvent(message: JsonObject) {
        val eventType = message.get("type")?.asString ?: return
        when (eventType) {
            "conversation.item.input_audio_transcription.text" -> {
                val text = message.get("text")?.asString ?: ""
                val stash = message.get("stash")?.asString ?: ""
                if (!running.get()) return
                currentTurnText = text
                currentTurnStash = stash
                val preview = currentTurnText + currentTurnStash
                if (preview.isNotEmpty()) {
                    try {
                        listener.onPartial(preview)
                    } catch (t: Throwable) {
                        Log.e(TAG, "notify partial failed", t)
                    }
                }
            }
            "conversation.item.input_audio_transcription.completed" -> {
                val transcript = message.get("transcript")?.asString ?: ""
                Log.d(TAG, "Transcription completed: $transcript")
                finalTranscript = transcript
                finalResultDeferred?.complete(transcript)
                if (finalDelivered.compareAndSet(false, true)) {
                    recordApiLogOnce(success = true)
                    try {
                        listener.onFinal(transcript)
                    } catch (t: Throwable) {
                        Log.e(TAG, "notify final failed", t)
                    }
                }
            }
            "conversation.item.input_audio_transcription.failed" -> {
                val errorMsg = message.getAsJsonObject("error")?.get("message")?.asString
                    ?: "Transcription failed"
                Log.e(TAG, "Transcription failed: $errorMsg")
                running.set(false)
                if (!finalDelivered.get()) {
                    recordApiLogOnce(success = false, error = errorMsg)
                    try {
                        listener.onError(
                            context.getString(R.string.error_recognize_failed_with_reason, errorMsg)
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "notify error failed", t)
                    }
                }
                finalResultDeferred?.complete(null)
                try {
                    audioJob?.cancel()
                } catch (t: Throwable) {
                    Log.w(TAG, "cancel audio job after failure failed", t)
                }
                audioJob = null
                safeClose(disposeRecognition = true, reason = "realtime_transcript_failed")
            }
            "error" -> {
                val errorMsg = message.getAsJsonObject("error")?.get("message")?.asString
                    ?: "Unknown error"
                Log.e(TAG, "Server error: $errorMsg")
                if (running.get()) {
                    running.set(false)
                    recordApiLogOnce(success = false, error = errorMsg)
                    try {
                        listener.onError(
                            context.getString(R.string.error_recognize_failed_with_reason, errorMsg)
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "notify error failed", t)
                    }
                }
                try {
                    audioJob?.cancel()
                } catch (t: Throwable) {
                    Log.w(TAG, "cancel audio job after server error failed", t)
                }
                audioJob = null
                finalResultDeferred?.complete(null)
                safeClose(disposeRecognition = true, reason = "realtime_server_error")
            }
            else -> Log.d(TAG, "Realtime event: $eventType")
        }
    }

    private fun startRecognitionStreaming(model: String) {
        // Fun-ASR 与 Qwen-Audio 3.x 使用 Recognition SDK 和 inference endpoint。
        val wsUrl = if (prefs.dashRegion.equals(
                "intl",
                ignoreCase = true
            )
        ) {
            WS_URL_INFER_INTL
        } else {
            WS_URL_INFER_CN
        }
        prepareApiLog(
            wsUrl = wsUrl,
            model = model,
            requestStructure = "SDK WebSocket recognition; format=pcm, sample_rate=16000, language_hints?, semantic_punctuation_enabled?, heartbeat?, keep_dialect?, intermediate_result_enabled?, disfluency_removal_enabled?; connection_reuse"
        )
        try {
            Constants.baseWebsocketApiUrl = wsUrl
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to set baseWebsocketApiUrl", t)
        }

        val param = buildDashRecognitionParam(
            model = model,
            apiKey = prefs.dashApiKey,
            sampleRate = sampleRate,
            languages = prefs.getDashLanguages(),
            keepDialect = prefs.dashKeepDialect,
            disfluencyRemoval = prefs.dashAutoPolish
        )
        val reuseKey = DashscopeRecognitionReuseKey(
            wsUrl = wsUrl,
            apiKey = prefs.dashApiKey,
            model = model,
            sampleRate = sampleRate
        )
        recognitionReuseKey = reuseKey
        recognitionTaskSucceeded.set(false)
        recognizer?.let { leftover ->
            DashscopeRecognitionConnectionCache.invalidate(
                null,
                leftover,
                "replaced_before_borrow"
            )
        }
        val borrowed = DashscopeRecognitionConnectionCache.borrow(reuseKey)
        val rec = borrowed.recognition
        recognizer = rec
        conversation = null
        recognizerReady = false
        logDiag(
            if (borrowed.reused) "dash_ws_session_reuse" else "dash_ws_session_new",
            mapOf("model" to model, "reused" to borrowed.reused)
        )
        val callback = object : ResultCallback<RecognitionResult>() {
            override fun onEvent(result: RecognitionResult) {
                handleRecognitionEvent(result)
            }

            override fun onComplete() {
                handleRecognitionComplete()
            }

            override fun onError(e: Exception) {
                handleRecognitionError(e)
            }
        }

        try {
            rec.call(param, callback)
        } catch (t: Throwable) {
            // 借出的脏连接：废弃后新建一次（不递归）
            if (borrowed.reused) {
                Log.w(TAG, "reuse call failed, recreating Recognition", t)
                logDiag(
                    "dash_ws_reuse_fail_reconnect",
                    mapOf("model" to model, "error" to (t.message ?: t.javaClass.simpleName))
                )
                DashscopeRecognitionConnectionCache.invalidate(reuseKey, rec, "reuse_call_failed")
                val fresh = Recognition()
                recognizer = fresh
                recognitionTaskSucceeded.set(false)
                fresh.call(param, callback)
            } else {
                throw t
            }
        }

        recognizerReady = true
        flushPrebuffer()
        if (!externalPcmMode) {
            startCaptureAndSend()
        }
    }

    private fun handleRecognitionEvent(result: RecognitionResult) {
        val sentenceText = result.getSentence()?.getText().orEmpty()
        if (sentenceText.isBlank()) return

        val isEnd = result.isSentenceEnd
        if (isEnd) {
            currentTurnText = appendSentence(currentTurnText, sentenceText)
            currentTurnStash = ""
        } else {
            currentTurnStash = sentenceText
        }

        if (!running.get()) return
        val preview = (currentTurnText + currentTurnStash).trim()
        if (preview.isNotEmpty()) {
            try {
                listener.onPartial(preview)
            } catch (t: Throwable) {
                Log.e(TAG, "notify partial failed", t)
            }
        }
    }

    private fun handleRecognitionComplete() {
        recognitionTaskSucceeded.set(true)
        val finalText = (currentTurnText + currentTurnStash).trim()
        finalTranscript = finalText
        finalResultDeferred?.complete(finalText)

        if (finalDelivered.compareAndSet(false, true)) {
            recordApiLogOnce(success = true)
            try {
                listener.onFinal(finalText)
            } catch (t: Throwable) {
                Log.e(TAG, "notify final failed", t)
            }
        }
    }

    private fun handleRecognitionError(e: Exception) {
        val msg = e.message ?: "Recognition error"
        Log.e(TAG, "DashScope Recognition streaming error: $msg", e)
        recognitionTaskSucceeded.set(false)
        recordApiLogOnce(success = false, error = msg)
        if (running.get()) {
            running.set(false)
            if (!finalDelivered.get()) {
                try {
                    listener.onError(
                        context.getString(R.string.error_recognize_failed_with_reason, msg)
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "notify error failed", t)
                }
            }
        }
        finalResultDeferred?.complete(null)
        try {
            audioJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel audio job after failure failed", t)
        }
        audioJob = null
        safeClose(disposeRecognition = true, reason = "recognition_error")
    }

    private fun appendSentence(existing: String, sentence: String): String {
        val s = sentence.trim()
        if (s.isEmpty()) return existing
        val cur = existing.trim()
        if (cur.isEmpty()) return s
        val last = cur.last()
        val first = s.first()
        val needsSpace = last.isAsciiLetterOrDigit() && first.isAsciiLetterOrDigit()
        return if (needsSpace) "$cur $s" else cur + s
    }

    private fun Char.isAsciiLetterOrDigit(): Boolean = (this in 'a'..'z') || (this in 'A'..'Z') || (this in '0'..'9')

    /**
     * 冲刷预缓冲区
     */
    private fun flushPrebuffer() {
        var flushed: Array<ByteArray>? = null
        synchronized(prebufferLock) {
            if (prebuffer.isNotEmpty()) {
                flushed = prebuffer.toTypedArray()
                prebuffer.clear()
            }
        }
        flushed?.forEach { b ->
            sendAudioFrame(b)
        }
    }

    /** 发送 PCM 音频帧。 */
    private fun sendAudioFrame(audioChunk: ByteArray) {
        if (useRecognitionProtocol) {
            try {
                recognizer?.sendAudioFrame(ByteBuffer.wrap(audioChunk))
            } catch (t: Throwable) {
                Log.e(TAG, "sendAudioFrame failed", t)
            }
            return
        }
        try {
            val base64Audio = Base64.encodeToString(audioChunk, Base64.NO_WRAP)
            conversation?.appendAudio(base64Audio)
        } catch (t: Throwable) {
            Log.e(TAG, "appendAudio failed", t)
        }
    }

    // ========== ExternalPcmConsumer（外部推流） ==========
    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!running.get()) return
        if (sampleRate != 16000 || channels != 1) return
        val leveled = externalVadInputLeveler.process(pcm)
        try {
            listener.onAmplitude(leveled.stableAmplitude)
        } catch (t: Throwable) {
            Log.w(TAG, "notify amplitude failed", t)
        }

        if (!recognizerReady) {
            synchronized(prebufferLock) { prebuffer.addLast(pcm.copyOf()) }
        } else {
            // 先冲刷预缓冲
            flushPrebuffer()
            sendAudioFrame(pcm)
        }
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)

        // 先取消音频采集，然后调用 commit() 触发最终识别
        scope.launch(Dispatchers.IO) {
            val resultDeferred = CompletableDeferred<String?>()
            finalResultDeferred = resultDeferred
            try {
                // 通知 UI：录音阶段结束，可复位麦克风按钮
                try {
                    listener.onStopped()
                } catch (t: Throwable) {
                    Log.e(TAG, "notify stopped failed", t)
                }

                // 取消音频采集协程，触发 AudioRecord 释放
                try {
                    audioJob?.cancel()
                    // 等待音频采集协程完全结束，确保 AudioRecord 被完全释放
                    audioJob?.join()
                } catch (t: Throwable) {
                    Log.w(TAG, "cancel/join audio job failed", t)
                }
                audioJob = null

                if (useRecognitionProtocol) {
                    try {
                        Log.d(TAG, "Calling recognizer.stop() to trigger final recognition")
                        recognizer?.stop()
                    } catch (t: Throwable) {
                        Log.w(TAG, "recognizer.stop() failed", t)
                        deliverStopFallback(resultDeferred, "recognizer.stop failed: ${t.message.orEmpty()}")
                    }
                } else {
                    try {
                        Log.d(TAG, "Calling commit() to trigger final recognition")
                        conversation?.commit()
                    } catch (t: Throwable) {
                        Log.w(TAG, "commit() failed", t)
                        deliverStopFallback(resultDeferred, "commit failed: ${t.message.orEmpty()}")
                    }
                }

                // 等待 completed 事件返回或超时
                val awaited = withTimeoutOrNull(FINAL_RESULT_TIMEOUT_MS) { resultDeferred.await() }
                if (awaited == null && finalDelivered.compareAndSet(false, true)) {
                    // 超时未收到 onComplete：连接状态未知，不可归还复用
                    recognitionTaskSucceeded.set(false)
                    recordApiLogOnce(success = false, error = "final result timeout")
                    // 超时后使用当前文本作为兜底结果
                    val fallbackText = (finalTranscript ?: (currentTurnText + currentTurnStash)).trim()
                    try {
                        listener.onFinal(fallbackText)
                    } catch (notifyError: Throwable) {
                        Log.e(TAG, "notify final timeout fallback failed", notifyError)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "stop cleanup failed", t)
            } finally {
                if (!resultDeferred.isCompleted) {
                    resultDeferred.complete(finalTranscript)
                }
                finalResultDeferred = null
                val canReuse = useRecognitionProtocol && recognitionTaskSucceeded.get()
                safeClose(
                    disposeRecognition = !canReuse,
                    reason = if (canReuse) "task_finished" else "stop_no_complete"
                )
            }
        }
    }

    private fun deliverStopFallback(
        resultDeferred: CompletableDeferred<String?>,
        error: String
    ) {
        recognitionTaskSucceeded.set(false)
        val fallbackText = (currentTurnText + currentTurnStash).trim()
        if (finalDelivered.compareAndSet(false, true)) {
            recordApiLogOnce(success = false, error = error)
            try {
                listener.onFinal(fallbackText)
            } catch (notifyError: Throwable) {
                Log.e(TAG, "notify final fallback failed", notifyError)
            }
        }
        if (!resultDeferred.isCompleted) {
            resultDeferred.complete(fallbackText)
        }
    }

    private fun startCaptureAndSend() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val chunkMillis = 100 // 建议 100ms 左右
            val audioManager = AudioCaptureManager(
                context = context,
                sampleRate = sampleRate,
                channelConfig = channelConfig,
                audioFormat = audioFormat,
                chunkMillis = chunkMillis,
                audioFrameSinkProvider = { audioFrameSink }
            )

            if (!audioManager.hasPermission()) {
                Log.e(TAG, "Missing RECORD_AUDIO permission")
                listener.onError(context.getString(R.string.error_record_permission_denied))
                running.set(false)
                return@launch
            }

            val vadDetector = if (isVadAutoStopEnabled(context, prefs)) {
                VadDetector(
                    context,
                    sampleRate,
                    prefs.autoStopSilenceWindowMs,
                    prefs.autoStopSilenceSensitivity
                )
            } else {
                null
            }
            val maxDurationLimiter = RecordingDurationLimiter.fromPrefs(
                prefs = prefs,
                sampleRate = sampleRate
            )
            val vadInputLeveler = VadInputLevelerBranch(sampleRate = sampleRate)

            try {
                audioManager.startCapture().collect { audioChunk ->
                    if (!running.get()) return@collect

                    val leveled = vadInputLeveler.process(audioChunk)

                    // Calculate and send audio amplitude (for waveform animation)
                    try {
                        listener.onAmplitude(leveled.stableAmplitude)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to calculate amplitude", t)
                    }

                    // 客户端 VAD 自动停止（可选，与服务端 VAD 独立）
                    if (vadDetector?.shouldStop(leveled.leveledPcm, leveled.leveledPcm.size) == true) {
                        Log.d(TAG, "Client VAD: silence detected, stopping recording")
                        try {
                            listener.onStopped()
                        } catch (
                            t: Throwable
                        ) {
                            Log.e(TAG, "notify stopped failed", t)
                        }
                        stop()
                        return@collect
                    }

                    // 发送音频
                    if (!recognizerReady) {
                        synchronized(prebufferLock) { prebuffer.addLast(audioChunk.copyOf()) }
                    } else {
                        flushPrebuffer()
                        sendAudioFrame(audioChunk)
                    }

                    if (maxDurationLimiter.acceptPcm(audioChunk.size)) {
                        Log.d(TAG, "Max recording duration reached, stopping recording")
                        try {
                            listener.onStopped()
                        } catch (t: Throwable) {
                            Log.e(TAG, "notify stopped failed", t)
                        }
                        stop()
                        return@collect
                    }
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "Audio streaming cancelled: ${t.message}")
                } else {
                    Log.e(TAG, "Audio streaming failed: ${t.message}", t)
                    listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
                }
            } finally {
                try {
                    vadDetector?.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "VAD release failed", t)
                }
            }
        }
    }

    /**
     * @param disposeRecognition true 时关闭并废弃 Recognition（错误/超时/取消）；
     *   false 且任务已 onComplete 时归还到进程内缓存以复用 WebSocket。
     */
    private fun safeClose(disposeRecognition: Boolean, reason: String) {
        recognizerReady = false
        try {
            conversation?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "conversation close failed", t)
        } finally {
            conversation = null
        }

        val rec = recognizer
        val key = recognitionReuseKey
        recognizer = null
        recognitionReuseKey = null
        if (rec == null) return

        if (!useRecognitionProtocol) {
            // realtime 路径不应持有 Recognition；防御性关闭
            try {
                rec.getDuplexApi()?.close(1000, reason)
            } catch (t: Throwable) {
                Log.w(TAG, "unexpected recognition close failed", t)
            }
            return
        }

        if (disposeRecognition || !recognitionTaskSucceeded.get()) {
            DashscopeRecognitionConnectionCache.invalidate(key, rec, reason)
        } else if (key != null) {
            DashscopeRecognitionConnectionCache.returnSuccess(key, rec)
        } else {
            DashscopeRecognitionConnectionCache.invalidate(null, rec, "missing_reuse_key")
        }
    }

    private fun logDiag(event: String, data: Map<String, Any?> = emptyMap()) {
        DebugLogManager.logBase(category = "asr", event = event, data = data)
    }

    private fun prepareApiLog(
        wsUrl: String,
        model: String,
        requestStructure: String
    ) {
        val meta = ApiCallLogger.meta(
            category = "ASR",
            vendor = "dashscope",
            model = model,
            requestStructure = requestStructure
        )
        apiLogSession = ApiCallLogger.startSdkWebSocket(wsUrl, meta)
    }

    private fun recordApiLogOnce(
        success: Boolean,
        code: Int = 0,
        error: String = ""
    ) {
        apiLogSession?.complete(success = success, code = code, error = error)
    }
}

internal fun buildDashRecognitionParam(
    model: String,
    apiKey: String,
    sampleRate: Int,
    languages: List<String>,
    keepDialect: Boolean = false,
    disfluencyRemoval: Boolean = true
): RecognitionParam {
    val builder = RecognitionParam.builder()
        .model(model)
        .apiKey(apiKey)
        .format("pcm")
        .sampleRate(sampleRate)
    val normalized = DashScopePrefsCompat.normalizeDashAsrModel(model)
    // message 不支持 language_hints；其余 Recognition 流式按模型能力传。
    if (!DashScopePrefsCompat.isQwenAudio31MessageModel(normalized)) {
        val normalizedLanguages = DashScopePrefsCompat.parseDashLanguages(languages.joinToString(","))
        val languageHints = if (DashScopePrefsCompat.isQwenAudioModel(normalized)) {
            normalizedLanguages
        } else {
            normalizedLanguages.take(1)
        }
        if (languageHints.isNotEmpty()) {
            builder.parameter("language_hints", languageHints.toTypedArray())
        }
    }
    // Fun-ASR / Qwen-Audio Recognition 流式（含 message）均支持；固定开启语义断句与保活
    if (DashScopePrefsCompat.isSemanticPunctuationSupported(normalized)) {
        builder.parameter("semantic_punctuation_enabled", true)
        builder.parameter("heartbeat", true)
    }
    // Qwen-Audio 3.1 streaming / message：方言开关。
    // 语义断句开启时服务端忽略 VAD 参数，故不传 vad_model。
    val isQwenAudio31Recognition =
        normalized.equals(Prefs.DASH_MODEL_QWEN_AUDIO_31_REALTIME, ignoreCase = true) ||
            DashScopePrefsCompat.isQwenAudio31MessageModel(normalized)
    if (isQwenAudio31Recognition) {
        builder.parameter("keep_dialect", keepDialect)
    }
    // message：中间结果默认关，必须显式开启；自动润色跟用户开关
    if (DashScopePrefsCompat.isQwenAudio31MessageModel(normalized)) {
        builder.parameter("intermediate_result_enabled", true)
        builder.parameter("disfluency_removal_enabled", disfluencyRemoval)
    }
    return builder.build()
}
