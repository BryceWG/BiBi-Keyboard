package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/**
 * OpenAI Realtime WebSocket 流式识别引擎（intent=transcription）。
 *
 * - 根据官方 Realtime transcription 文档：session.update + input_audio_buffer.append/commit
 * - 默认使用 server_vad 断句，接收 conversation.item.input_audio_transcription.delta/completed 事件
 * - 采样率策略：官方 endpoint 使用 24kHz；非官方 endpoint 使用 16kHz。
 * - 外部 Push-PCM：官方 endpoint 场景支持自动重采样到 24kHz。
 */
class OpenAiRealtimeAsrEngine(
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
        private const val TAG = "OpenAiRealtimeAsr"
        private const val FINAL_WAIT_TIMEOUT_MS = 10_000L
        private const val PREBUFFER_MS = 2_000
        private const val MIN_COMMIT_AUDIO_MS = 100L
        private const val OFFICIAL_HOST = "api.openai.com"
        private const val SAMPLE_RATE_OFFICIAL = 24_000
        private const val SAMPLE_RATE_NON_OFFICIAL = 16_000
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(ApiLogInterceptor())
        .pingInterval(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private val wsReady = AtomicBoolean(false)
    private val awaitingFinal = AtomicBoolean(false)
    private val closingByUser = AtomicBoolean(false)
    private val stopNotified = AtomicBoolean(false)
    private val terminalNotified = AtomicBoolean(false)
    private val stopCommitSent = AtomicBoolean(false)
    private val appendedAudioBytes = AtomicLong(0L)

    private var ws: WebSocket? = null
    private var audioJob: Job? = null
    private var finalizeJob: Job? = null

    @Volatile private var targetSampleRate: Int = SAMPLE_RATE_OFFICIAL

    @Volatile private var externalVadInputLeveler = VadInputLevelerBranch(
        sampleRate = SAMPLE_RATE_OFFICIAL
    )
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val prebuffer = ArrayDeque<ByteArray>()
    private val prebufferLock = Any()

    // committed turn ordering（由 committed.previous_item_id 维护）
    private val committedOrder = ArrayList<String>()
    private val committedPrev = HashMap<String, String?>()
    private val committedTranscript = HashMap<String, String>()
    private val inProgressDelta = HashMap<String, StringBuilder>()

    @Volatile private var finalCommitItemId: String? = null

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        if (!externalPcmMode && !hasRecordPermission()) {
            listener.onError(context.getString(R.string.error_record_permission_denied))
            return
        }

        val endpoint = prefs.oaAsrEndpoint.ifBlank { Prefs.DEFAULT_OA_ASR_ENDPOINT }.trim()
        val url = endpoint.toHttpUrlOrNull()
        targetSampleRate = resolveTargetSampleRate(endpoint)
        externalVadInputLeveler = VadInputLevelerBranch(sampleRate = targetSampleRate)
        if (url != null &&
            url.host.equals(OFFICIAL_HOST, ignoreCase = true) &&
            prefs.oaAsrApiKey.isBlank()
        ) {
            listener.onError(context.getString(R.string.asr_error_auth_invalid))
            return
        }

        running.set(true)
        wsReady.set(false)
        awaitingFinal.set(false)
        closingByUser.set(false)
        stopNotified.set(false)
        terminalNotified.set(false)
        stopCommitSent.set(false)
        appendedAudioBytes.set(0L)
        finalCommitItemId = null
        committedOrder.clear()
        committedPrev.clear()
        committedTranscript.clear()
        inProgressDelta.clear()
        synchronized(prebufferLock) { prebuffer.clear() }

        openWebSocket(startAudio = !externalPcmMode)
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        closingByUser.set(true)
        awaitingFinal.set(true)
        notifyStoppedIfNeeded()

        audioJob?.cancel()
        audioJob = null

        finalizeJob?.cancel()
        finalizeJob = scope.launch(Dispatchers.IO) {
            val bufferedBeforeWait = bufferedAudioBytes()
            val wsReadyWaitMs = if (bufferedBeforeWait >= minCommitAudioBytes()) 2_000 else 800
            var waited = 0
            while (!wsReady.get() && waited < wsReadyWaitMs) {
                delay(50)
                waited += 50
            }

            val buffered = bufferedAudioBytes()
            val attemptedMs = audioMsFromBytes(appendedAudioBytes.get() + buffered)

            if (!wsReady.get()) {
                if (attemptedMs < MIN_COMMIT_AUDIO_MS) {
                    notifyError(context.getString(R.string.error_audio_empty), "audio_too_short", null)
                    awaitingFinal.set(false)
                    closeWebSocket("audio_too_short")
                    return@launch
                }
                Log.w(TAG, "WebSocket not ready on stop; unable to commit audio buffer")
            } else {
                flushPrebuffer()
                val sentMs = audioMsFromBytes(appendedAudioBytes.get())
                if (sentMs < MIN_COMMIT_AUDIO_MS) {
                    notifyError(context.getString(R.string.error_audio_empty), "audio_too_short", null)
                    awaitingFinal.set(false)
                    closeWebSocket("audio_too_short")
                    return@launch
                }
                stopCommitSent.set(true)
                sendCommitIfSupported("stop")
            }

            var left = FINAL_WAIT_TIMEOUT_MS
            while (awaitingFinal.get() && left > 0 && isActive) {
                delay(50)
                left -= 50
            }
            if (awaitingFinal.get()) {
                notifyError(context.getString(R.string.error_asr_timeout), "timeout", null)
                awaitingFinal.set(false)
                closeWebSocket("timeout")
            }
        }
    }

    private fun openWebSocket(startAudio: Boolean) {
        val endpoint = prefs.oaAsrEndpoint.ifBlank { Prefs.DEFAULT_OA_ASR_ENDPOINT }.trim()
        val primaryWsUrl = buildRealtimeWsUrl(endpoint)
        val effectiveEndpoint = if (primaryWsUrl != null) endpoint else Prefs.DEFAULT_OA_ASR_ENDPOINT
        targetSampleRate = resolveTargetSampleRate(effectiveEndpoint)
        externalVadInputLeveler = VadInputLevelerBranch(sampleRate = targetSampleRate)
        val wsUrl = primaryWsUrl
            ?: buildRealtimeWsUrl(Prefs.DEFAULT_OA_ASR_ENDPOINT)
            ?: run {
                listener.onError(
                    context.getString(R.string.error_recognize_failed_with_reason, "invalid endpoint")
                )
                running.set(false)
                awaitingFinal.set(false)
                closeWebSocket("invalid_endpoint")
                return
            }

        if (startAudio) startCaptureAndSendAudio()

        val apiKey = prefs.oaAsrApiKey.trim()
        val model = prefs.oaAsrModel.ifBlank { Prefs.DEFAULT_OA_ASR_MODEL }.trim()
        val meta = ApiCallLogger.meta(
            category = "ASR",
            vendor = "openai",
            model = model,
            requestStructure = "WebSocket session.update; input_audio_buffer.append/commit; response audio transcription"
        )
        val reqBuilder = Request.Builder().url(wsUrl)
            .tag(ApiLogMeta::class.java, meta)
        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        }
        val req = reqBuilder.build()
        val apiLogSession = ApiCallLogger.startWebSocket(req, meta)

        ws = http.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket opened: $response")
                    try {
                        sendSessionUpdate(webSocket)
                        // 等待服务端确认 session.updated 后再开始发送音频，避免音频被丢弃。
                    } catch (t: Throwable) {
                        notifyError(
                            context.getString(
                                R.string.error_recognize_failed_with_reason,
                                t.message ?: ""
                            ),
                            "session_update",
                            t
                        )
                        running.set(false)
                        awaitingFinal.set(false)
                        closeWebSocket("session_update_error")
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(text)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val detail = response?.message ?: t.message.orEmpty()
                    apiLogSession.failure(
                        code = response?.code ?: 0,
                        error = detail
                    )
                    if (closingByUser.get() || awaitingFinal.get()) {
                        emitFinalIfNeeded("failure_after_stop")
                        running.set(false)
                        awaitingFinal.set(false)
                        closeWebSocket("failure_after_stop")
                        return
                    }
                    notifyError(
                        context.getString(R.string.error_recognize_failed_with_reason, detail),
                        "failure",
                        t
                    )
                    running.set(false)
                    awaitingFinal.set(false)
                    closeWebSocket("failure")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $code $reason")
                    apiLogSession.complete(
                        success = true,
                        code = code,
                        error = reason
                    )
                    ws = null
                    wsReady.set(false)
                    audioJob?.cancel()
                    audioJob = null

                    if (closingByUser.get() || awaitingFinal.get()) {
                        emitFinalIfNeeded("closed")
                    } else if (running.get()) {
                        notifyError(
                            context.getString(
                                R.string.error_recognize_failed_with_reason,
                                "connection closed"
                            ),
                            "closed_unexpected",
                            null
                        )
                    }
                    running.set(false)
                    awaitingFinal.set(false)
                    synchronized(prebufferLock) { prebuffer.clear() }
                }
            }
        )
    }

    private fun startCaptureAndSendAudio() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val chunkMillis = 100
            val audioManager = AudioCaptureManager(
                context = context,
                sampleRate = targetSampleRate,
                channelConfig = channelConfig,
                audioFormat = audioFormat,
                chunkMillis = chunkMillis,
                audioFrameSinkProvider = { audioFrameSink }
            )

            if (!audioManager.hasPermission()) {
                listener.onError(context.getString(R.string.error_record_permission_denied))
                running.set(false)
                return@launch
            }

            val vadDetector = if (isVadAutoStopEnabled(context, prefs)) {
                VadDetector(
                    context,
                    targetSampleRate,
                    prefs.autoStopSilenceWindowMs,
                    prefs.autoStopSilenceSensitivity
                )
            } else {
                null
            }
            val maxDurationLimiter = RecordingDurationLimiter.fromPrefs(
                prefs = prefs,
                sampleRate = targetSampleRate
            )
            val vadInputLeveler = VadInputLevelerBranch(sampleRate = targetSampleRate)
            val maxFrames = (PREBUFFER_MS / chunkMillis).coerceAtLeast(1)

            try {
                audioManager.startCapture().collect { chunk ->
                    if (!isActive || !running.get()) return@collect

                    val leveled = vadInputLeveler.process(chunk)

                    try {
                        listener.onAmplitude(leveled.stableAmplitude)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to compute amplitude", t)
                    }

                    if (vadDetector?.shouldStop(chunk, chunk.size) == true) {
                        Log.d(TAG, "VAD silence detected, stopping stream")
                        notifyStoppedIfNeeded()
                        stop()
                        return@collect
                    }

                    if (!wsReady.get()) {
                        synchronized(prebufferLock) {
                            prebuffer.addLast(chunk.copyOf())
                            while (prebuffer.size > maxFrames) prebuffer.removeFirst()
                        }
                    } else {
                        flushPrebuffer()
                        val socket = ws
                        if (socket != null) sendAppendAudio(socket, chunk)
                    }

                    if (maxDurationLimiter.acceptPcm(chunk.size)) {
                        Log.d(TAG, "Max recording duration reached, stopping stream")
                        notifyStoppedIfNeeded()
                        stop()
                        return@collect
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
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

    // ========== ExternalPcmConsumer（外部推流） ==========
    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!running.get()) return
        if (channels != 1) return

        val targetRate = targetSampleRate
        val pcmForSend = when {
            targetRate == SAMPLE_RATE_OFFICIAL && sampleRate == SAMPLE_RATE_OFFICIAL -> pcm
            targetRate == SAMPLE_RATE_OFFICIAL && sampleRate > 0 ->
                resamplePcm16Mono(pcm, sampleRate, SAMPLE_RATE_OFFICIAL)
            targetRate == SAMPLE_RATE_NON_OFFICIAL && sampleRate == SAMPLE_RATE_NON_OFFICIAL -> pcm
            else -> return
        }

        val leveled = externalVadInputLeveler.process(pcmForSend)
        try {
            listener.onAmplitude(leveled.stableAmplitude)
        } catch (_: Throwable) {}

        if (!wsReady.get()) {
            synchronized(prebufferLock) {
                prebuffer.addLast(pcmForSend)
                while (prebuffer.size > 20) prebuffer.removeFirst()
            }
        } else {
            flushPrebuffer()
            val socket = ws ?: return
            sendAppendAudio(socket, pcmForSend)
        }
    }

    private fun sendSessionUpdate(socket: WebSocket) {
        val model = prefs.oaAsrModel.ifBlank { Prefs.DEFAULT_OA_ASR_MODEL }.trim()
        val lang = prefs.oaAsrLanguage.trim()

        val prompt = if (prefs.oaAsrUsePrompt) prefs.oaAsrPrompt.trim() else ""

        val transcription = JSONObject().apply {
            put("model", model)
            if (prompt.isNotBlank()) put("prompt", prompt)
            if (lang.isNotBlank()) put("language", lang)
        }

        // https://platform.openai.com/docs/guides/realtime-transcription
        val payload = JSONObject().apply {
            put("type", "session.update")
            put(
                "session",
                JSONObject().apply {
                    // Realtime transcription: required by server for intent=transcription
                    put("type", "transcription")
                    put(
                        "audio",
                        JSONObject().apply {
                            put(
                                "input",
                                JSONObject().apply {
                                    put(
                                        "format",
                                        JSONObject().apply {
                                            put("type", "audio/pcm")
                                            put("rate", targetSampleRate)
                                        }
                                    )
                                    put(
                                        "noise_reduction",
                                        JSONObject().apply { put("type", "near_field") }
                                    )
                                    put("transcription", transcription)
                                    put(
                                        "turn_detection",
                                        JSONObject().apply {
                                            put("type", "server_vad")
                                            put("threshold", 0.5)
                                            put("prefix_padding_ms", 300)
                                            put("silence_duration_ms", 500)
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }.toString()
        socket.send(payload)
    }

    private fun sendAppendAudio(socket: WebSocket, pcm: ByteArray) {
        val audioB64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("type", "input_audio_buffer.append")
            put("audio", audioB64)
        }.toString()
        try {
            val ok = socket.send(payload)
            if (ok) {
                appendedAudioBytes.addAndGet(pcm.size.toLong())
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send audio chunk", t)
        }
    }

    private fun sendCommitIfSupported(reason: String) {
        val socket = ws ?: return
        if (!wsReady.get()) return
        val payload = JSONObject().apply { put("type", "input_audio_buffer.commit") }.toString()
        try {
            socket.send(payload)
            Log.d(TAG, "Sent input_audio_buffer.commit ($reason)")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send commit ($reason)", t)
        }
    }

    private fun flushPrebuffer() {
        val socket = ws ?: return
        if (!wsReady.get()) return
        val flushed = drainPrebuffer()
        flushed.forEach { sendAppendAudio(socket, it) }
    }

    private fun drainPrebuffer(): Array<ByteArray> = synchronized(prebufferLock) {
        if (prebuffer.isEmpty()) return@synchronized emptyArray()
        val flushed = prebuffer.toTypedArray()
        prebuffer.clear()
        flushed
    }

    private fun bufferedAudioBytes(): Long = synchronized(prebufferLock) {
        if (prebuffer.isEmpty()) return@synchronized 0L
        var total = 0L
        prebuffer.forEach { total += it.size.toLong() }
        total
    }

    private fun minCommitAudioBytes(): Long {
        val bytesPerSec = targetSampleRate.toLong() * 2L
        return bytesPerSec * MIN_COMMIT_AUDIO_MS / 1000L
    }

    private fun audioMsFromBytes(bytes: Long): Long {
        if (bytes <= 0L) return 0L
        val bytesPerMs = (targetSampleRate.toLong() * 2L) / 1000L
        if (bytesPerMs <= 0L) return 0L
        return (bytes / bytesPerMs).coerceAtLeast(0L)
    }

    private fun handleMessage(json: String) {
        try {
            val o = JSONObject(json)

            if (o.has("error")) {
                val err = o.optJSONObject("error")
                val msg = err?.optString("message")?.trim().orEmpty()
                    .ifBlank { o.optString("message").trim() }
                    .ifBlank { json.take(200) }

                // server_vad 模式下，stop() 时若服务端已自动 commit 过，额外的 commit 可能返回 buffer too small。
                // 该错误表示当前 buffer 无可提交内容：此时直接以已收到的转写结果收尾即可。
                val lower = msg.lowercase(Locale.ROOT)
                val commitBufferTooSmall =
                    lower.contains("error committing input audio buffer") &&
                        lower.contains("buffer too small")
                if (commitBufferTooSmall && (closingByUser.get() || awaitingFinal.get())) {
                    emitFinalIfNeeded("commit_buffer_too_small")
                    awaitingFinal.set(false)
                    closeWebSocket("final")
                    return
                }

                notifyError(
                    context.getString(R.string.error_recognize_failed_with_reason, msg),
                    "server_error",
                    null
                )
                running.set(false)
                awaitingFinal.set(false)
                closeWebSocket("server_error")
                return
            }

            val type = o.optString("type", "")
            when (type) {
                "session.created" -> Unit
                "session.updated" -> {
                    if (!wsReady.get()) {
                        wsReady.set(true)
                        flushPrebuffer()
                    }
                    // stop() 可能发生在 session.updated 之前：此处补发 commit
                    if (!running.get() && awaitingFinal.get()) {
                        val sentMs = audioMsFromBytes(appendedAudioBytes.get())
                        if (sentMs >= MIN_COMMIT_AUDIO_MS) {
                            stopCommitSent.set(true)
                            sendCommitIfSupported("stop_after_session_updated")
                        }
                    }
                }
                "input_audio_buffer.committed" -> {
                    val itemId = o.optString("item_id").trim()
                    val prevId = o.optString("previous_item_id").trim().ifBlank { null }
                    if (itemId.isNotBlank()) {
                        recordCommittedItem(itemId, prevId)
                        if (awaitingFinal.get() && finalCommitItemId == null) {
                            finalCommitItemId = itemId
                        }
                    }
                }
                "conversation.item.input_audio_transcription.delta" -> {
                    val itemId = o.optString("item_id").trim()
                    val delta = o.optString("delta").trim()
                    if (itemId.isNotBlank() && delta.isNotEmpty()) {
                        val buf = inProgressDelta.getOrPut(itemId) { StringBuilder() }
                        buf.append(delta)
                        emitPartialIfNeeded()
                    }
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    val itemId = o.optString("item_id").trim()
                    val transcript = o.optString("transcript").trim()
                    if (itemId.isNotBlank()) {
                        if (transcript.isNotBlank()) committedTranscript[itemId] = transcript
                        inProgressDelta.remove(itemId)
                        emitPartialIfNeeded()
                        val finalId = finalCommitItemId
                        if (awaitingFinal.get() && finalId == null && stopCommitSent.get()) {
                            finalCommitItemId = itemId
                        }
                        val resolvedFinalId = finalCommitItemId
                        if (awaitingFinal.get() && resolvedFinalId != null && itemId == resolvedFinalId) {
                            emitFinalIfNeeded("completed_after_stop")
                            awaitingFinal.set(false)
                            closeWebSocket("final")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to parse message: $json", t)
        }
    }

    private fun recordCommittedItem(itemId: String, previousItemId: String?) {
        if (committedPrev.containsKey(itemId)) return
        committedPrev[itemId] = previousItemId
        if (committedOrder.isEmpty()) {
            committedOrder.add(itemId)
            return
        }
        if (previousItemId.isNullOrBlank()) {
            // 无 prev：尽量追加到末尾（服务端可能无法提供 prev）
            committedOrder.add(itemId)
            return
        }
        val prevIdx = committedOrder.indexOf(previousItemId)
        if (prevIdx >= 0 && prevIdx < committedOrder.size - 1) {
            committedOrder.add(prevIdx + 1, itemId)
        } else if (prevIdx == committedOrder.size - 1) {
            committedOrder.add(itemId)
        } else {
            committedOrder.add(itemId)
        }
    }

    private fun emitPartialIfNeeded() {
        if (!running.get()) return
        val preview = buildPreviewText().trim()
        if (preview.isNotBlank()) {
            try {
                listener.onPartial(preview)
            } catch (t: Throwable) {
                Log.w(TAG, "notify partial failed", t)
            }
        }
    }

    private fun emitFinalIfNeeded(reason: String) {
        val finalText = buildPreviewText().trim()
        if (!terminalNotified.compareAndSet(false, true)) return
        if (finalText.isBlank()) {
            val msg = context.getString(R.string.error_asr_empty_result)
            try {
                listener.onError(AsrErrorMessageMapper.map(context, msg) ?: msg)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to notify empty final ($reason)", t)
            }
            return
        }
        try {
            listener.onFinal(finalText)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to notify final ($reason)", t)
        }
    }

    private fun buildPreviewText(): String {
        val sb = StringBuilder()
        for (id in committedOrder) {
            val stable = committedTranscript[id].orEmpty()
            val delta = inProgressDelta[id]?.toString().orEmpty()
            val combined = when {
                stable.isNotBlank() && delta.isNotBlank() -> mergeWithOverlapDedup(stable, delta)
                stable.isNotBlank() -> stable
                else -> delta
            }.trim()
            if (combined.isBlank()) continue
            if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
            sb.append(combined)
        }
        // 若没有 committedOrder（VAD/commit 未触发），回退拼接所有 delta（尽量不中断 UI）
        if (sb.isEmpty() && inProgressDelta.isNotEmpty()) {
            inProgressDelta.values.forEach { b ->
                val s = b.toString().trim()
                if (s.isBlank()) return@forEach
                if (sb.isNotEmpty() && !sb.last().isWhitespace()) sb.append(' ')
                sb.append(s)
            }
        }
        return stripEndMarkers(sb.toString())
    }

    private fun notifyStoppedIfNeeded() {
        if (!stopNotified.compareAndSet(false, true)) return
        try {
            listener.onStopped()
        } catch (t: Throwable) {
            Log.w(TAG, "notifyStopped failed", t)
        }
    }

    private fun notifyError(message: String, reason: String, error: Throwable?) {
        if (!terminalNotified.compareAndSet(false, true)) return
        if (error != null) {
            Log.e(TAG, "ASR error ($reason): $message", error)
        } else {
            Log.e(TAG, "ASR error ($reason): $message")
        }
        try {
            val mapped = AsrErrorMessageMapper.map(context, message) ?: message
            listener.onError(mapped)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to notify error ($reason)", t)
        }
    }

    private fun closeWebSocket(reason: String) {
        audioJob?.cancel()
        audioJob = null

        val socket = ws
        if (socket != null) {
            try {
                socket.close(1000, reason)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to close WebSocket ($reason)", t)
            }
        }
        ws = null
        wsReady.set(false)
        synchronized(prebufferLock) { prebuffer.clear() }
    }

    /**
     * 将 stable 与 tail 合并，移除边界处的重复前后缀重叠。
     */
    private fun mergeWithOverlapDedup(stable: String, tail: String): String {
        if (stable.isEmpty()) return tail
        if (tail.isEmpty()) return stable
        val max = minOf(stable.length, tail.length)
        var k = max
        while (k > 0) {
            if (stable.regionMatches(stable.length - k, tail, 0, k)) {
                return stable + tail.substring(k)
            }
            k--
        }
        return stable + tail
    }

    private fun stripEndMarkers(s: String): String {
        if (s.isEmpty()) return s
        return s.replace("<end>", "").replace("<fin>", "").trimEnd()
    }

    private fun resolveTargetSampleRate(endpoint: String): Int {
        val host = endpoint.toHttpUrlOrNull()?.host.orEmpty()
        return if (host.equals(OFFICIAL_HOST, ignoreCase = true)) {
            SAMPLE_RATE_OFFICIAL
        } else {
            SAMPLE_RATE_NON_OFFICIAL
        }
    }

    private fun resamplePcm16Mono(pcm: ByteArray, fromRate: Int, toRate: Int): ByteArray {
        if (fromRate <= 0 || toRate <= 0 || pcm.isEmpty()) return pcm
        if (fromRate == toRate) return pcm
        val srcSamples = pcm.size / 2
        if (srcSamples <= 1) return pcm

        val dstSamples = ((srcSamples.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val out = ByteArray(dstSamples * 2)
        var i = 0
        while (i < dstSamples) {
            val srcPos = i.toDouble() * fromRate.toDouble() / toRate.toDouble()
            val idx0 = srcPos.toInt().coerceIn(0, srcSamples - 1)
            val idx1 = (idx0 + 1).coerceAtMost(srcSamples - 1)
            val frac = srcPos - idx0
            val s0 = readPcm16LE(pcm, idx0)
            val s1 = readPcm16LE(pcm, idx1)
            val v = (s0 + (s1 - s0) * frac).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            writePcm16LE(out, i, v.toShort())
            i++
        }
        return out
    }

    private fun readPcm16LE(src: ByteArray, sampleIndex: Int): Int {
        val o = sampleIndex * 2
        val lo = src[o].toInt() and 0xFF
        val hi = src[o + 1].toInt()
        return (hi shl 8) or lo
    }

    private fun writePcm16LE(dst: ByteArray, sampleIndex: Int, value: Short) {
        val o = sampleIndex * 2
        dst[o] = (value.toInt() and 0xFF).toByte()
        dst[o + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
    }

    private fun hasRecordPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun buildRealtimeWsUrl(endpoint: String): String? {
        val httpUrl = endpoint.trim().toHttpUrlOrNull() ?: return null
        val segs = httpUrl.pathSegments.filter { it.isNotBlank() }
        val v1Index = segs.indexOf("v1")
        val prefix = if (v1Index >= 0) segs.take(v1Index) else emptyList()

        val builder = HttpUrl.Builder()
            .scheme(httpUrl.scheme)
            .host(httpUrl.host)
            .port(httpUrl.port)

        prefix.forEach { builder.addPathSegment(it) }
        builder.addPathSegment("v1")
        builder.addPathSegment("realtime")
        builder.addQueryParameter("intent", "transcription")

        val http = builder.build().toString()
        return when (httpUrl.scheme.lowercase()) {
            "https" -> http.replaceFirst("https:", "wss:")
            "http" -> http.replaceFirst("http:", "ws:")
            else -> null
        }
    }
}
