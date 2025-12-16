package com.brycewg.asrkb.api

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.asr.*
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Android 标准语音识别服务实现
 */
class AsrRecognitionService : RecognitionService() {

    companion object {
        private const val TAG = "AsrRecognitionSvc"
    }

    private val prefs by lazy { Prefs(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // 当前活动会话
    private var currentSession: RecognitionSession? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            currentSession?.cancel()
            currentSession = null
            serviceScope.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "onDestroy cleanup failed", t)
        }
    }

    override fun onStartListening(intent: Intent, callback: Callback) {
        Log.d(TAG, "onStartListening called")

        // 检查录音权限
        val hasPermission = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "Recording permission not granted")
            callback.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }

        // 检查是否有活动会话
        // 根据 SpeechRecognizer 文档，在上一次会话触发 onResults/onError 之前
        // 再次调用 startListening 应当直接返回 ERROR_RECOGNIZER_BUSY
        if (currentSession != null) {
            Log.w(TAG, "Recognizer busy - session already running")
            callback.error(SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            return
        }

        // 解析 RecognizerIntent 参数
        val config = parseRecognizerIntent(intent)
        Log.d(
            TAG,
            "Parsed external config: language=${config.language}, partialResults=${config.partialResults}"
        )
        // language 等参数仅用于日志/回调控制（如是否返回 partialResults），
        // 不会影响内部供应商选择、本地/云端模型或具体识别配置，相关行为完全由 Prefs 决定。

        // 创建会话（先创建以便作为 listener 传递给引擎）
        val session = RecognitionSession(
            callback = callback,
            config = config
        )

        // 构建引擎
        val engine = buildEngine(session)
        if (engine == null) {
            Log.e(TAG, "Failed to build ASR engine")
            callback.error(SpeechRecognizer.ERROR_CLIENT)
            return
        }

        // 设置引擎并激活会话
        session.setEngine(engine)
        currentSession = session

        // 通知就绪
        try {
            callback.readyForSpeech(Bundle())
        } catch (t: Throwable) {
            Log.w(TAG, "readyForSpeech callback failed", t)
        }

        // 启动引擎
        session.start()
    }

    override fun onStopListening(callback: Callback) {
        Log.d(TAG, "onStopListening called")
        currentSession?.stop()
    }

    override fun onCancel(callback: Callback) {
        Log.d(TAG, "onCancel called")
        currentSession?.cancel()
        currentSession = null
    }

    /**
     * 解析 RecognizerIntent 参数
     */
    private fun parseRecognizerIntent(intent: Intent): RecognitionConfig {
        // 语言选择优先级：EXTRA_LANGUAGE > EXTRA_LANGUAGE_PREFERENCE > 默认（null 表示走应用内配置/自动检测）
        val language = intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE)
            ?: intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE)
        val partialResults = intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        val maxResults = intent.getIntExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1).coerceAtLeast(1)
        return RecognitionConfig(
            language = language,
            partialResults = partialResults,
            maxResults = maxResults
        )
    }

    /**
     * 构建 ASR 引擎（复用 ExternalSpeechService 的逻辑）
     */
    private fun buildEngine(listener: StreamingAsrEngine.Listener): StreamingAsrEngine? {
        val vendor = prefs.asrVendor
        val streamingPref = resolveStreamingBySettings(vendor)
        val scope = serviceScope

        return when (vendor) {
            AsrVendor.Volc -> if (streamingPref) {
                VolcStreamAsrEngine(this, scope, prefs, listener)
            } else {
                VolcFileAsrEngine(this, scope, prefs, listener)
            }
            AsrVendor.SiliconFlow -> SiliconFlowFileAsrEngine(this, scope, prefs, listener)
            AsrVendor.ElevenLabs -> if (streamingPref) {
                ElevenLabsStreamAsrEngine(this, scope, prefs, listener)
            } else {
                ElevenLabsFileAsrEngine(this, scope, prefs, listener)
            }
            AsrVendor.OpenAI -> OpenAiFileAsrEngine(this, scope, prefs, listener)
            AsrVendor.DashScope -> if (streamingPref) {
                DashscopeStreamAsrEngine(this, scope, prefs, listener)
            } else {
                DashscopeFileAsrEngine(this, scope, prefs, listener)
            }
            AsrVendor.Gemini -> GeminiFileAsrEngine(this, scope, prefs, listener)
            AsrVendor.Soniox -> if (streamingPref) {
                SonioxStreamAsrEngine(this, scope, prefs, listener)
            } else {
                SonioxFileAsrEngine(this, scope, prefs, listener)
            }
            AsrVendor.Zhipu -> ZhipuFileAsrEngine(this, scope, prefs, listener)
            AsrVendor.SenseVoice -> {
                if (prefs.svPseudoStreamEnabled) {
                    SenseVoicePseudoStreamAsrEngine(this, scope, prefs, listener)
                } else {
                    SenseVoiceFileAsrEngine(this, scope, prefs, listener)
                }
            }
            AsrVendor.Telespeech -> {
                if (prefs.tsPseudoStreamEnabled) {
                    TelespeechPseudoStreamAsrEngine(this, scope, prefs, listener)
                } else {
                    TelespeechFileAsrEngine(this, scope, prefs, listener)
                }
            }
            AsrVendor.Paraformer -> ParaformerStreamAsrEngine(this, scope, prefs, listener)
            AsrVendor.Zipformer -> ZipformerStreamAsrEngine(this, scope, prefs, listener)
        }
    }

    /**
     * 根据设置决定是否使用流式模式
     */
    private fun resolveStreamingBySettings(vendor: AsrVendor): Boolean {
        return when (vendor) {
            AsrVendor.Volc -> prefs.volcStreamingEnabled
            AsrVendor.DashScope -> prefs.isDashStreamingModelSelected()
            AsrVendor.Soniox -> prefs.sonioxStreamingEnabled
            AsrVendor.ElevenLabs -> prefs.elevenStreamingEnabled
            AsrVendor.Paraformer, AsrVendor.Zipformer -> true
            AsrVendor.SenseVoice, AsrVendor.Telespeech -> false
            AsrVendor.OpenAI, AsrVendor.Gemini, AsrVendor.SiliconFlow, AsrVendor.Zhipu -> false
        }
    }

    /**
     * 将内部错误消息映射到 SpeechRecognizer 标准错误码
     */
    private fun mapToSpeechRecognizerError(message: String): Int {
        return when {
            message.contains("permission", ignoreCase = true) ->
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
            message.contains("network", ignoreCase = true) ||
            message.contains("timeout", ignoreCase = true) ||
            message.contains("connect", ignoreCase = true) ->
                SpeechRecognizer.ERROR_NETWORK
            message.contains("audio", ignoreCase = true) ||
            message.contains("microphone", ignoreCase = true) ||
            message.contains("record", ignoreCase = true) ->
                SpeechRecognizer.ERROR_AUDIO
            message.contains("busy", ignoreCase = true) ->
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY
            message.contains("empty", ignoreCase = true) ||
            message.contains("no speech", ignoreCase = true) ||
            message.contains("no match", ignoreCase = true) ->
                SpeechRecognizer.ERROR_NO_MATCH
            message.contains("server", ignoreCase = true) ||
            message.contains("api", ignoreCase = true) ->
                SpeechRecognizer.ERROR_SERVER
            else -> SpeechRecognizer.ERROR_CLIENT
        }
    }

    /**
     * RecognizerIntent 配置
     */
    private data class RecognitionConfig(
        val language: String?,
        val partialResults: Boolean,
        val maxResults: Int
    )

    /**
     * 识别会话 - 桥接 StreamingAsrEngine.Listener 到 RecognitionService.Callback
     */
    private inner class RecognitionSession(
        private val callback: Callback,
        private val config: RecognitionConfig
    ) : StreamingAsrEngine.Listener {

        private var engine: StreamingAsrEngine? = null

        @Volatile
        var isActive: Boolean = false
            private set

        @Volatile
        private var canceled: Boolean = false

        @Volatile
        private var finished: Boolean = false

        private var speechDetected = false
        private var endOfSpeechDelivered = false

        private var sessionStartUptimeMs: Long = 0L
        private var lastAudioMsForTimeout: Long = 0L
        private var processingTimeoutJob: Job? = null

        fun setEngine(engine: StreamingAsrEngine) {
            this.engine = engine
        }

        fun start() {
            isActive = true
            canceled = false
            finished = false
            speechDetected = false
            endOfSpeechDelivered = false
            sessionStartUptimeMs = SystemClock.uptimeMillis()
            lastAudioMsForTimeout = 0L
            cancelProcessingTimeout()
            engine?.start()
        }

        fun stop() {
            // 停止录音阶段时标记会话为非活动，避免异常情况下卡在 BUSY 状态
            isActive = false
            snapshotAudioMsForTimeoutIfNeeded()
            deliverEndOfSpeechIfNeeded()
            scheduleProcessingTimeoutIfNeeded()
            engine?.stop()
        }

        fun cancel() {
            isActive = false
            canceled = true
            finished = true
            cancelProcessingTimeout()
            try {
                engine?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "Engine stop failed on cancel", t)
            }
        }

        override fun onFinal(text: String) {
            if (canceled || finished) return
            Log.d(TAG, "onFinal: $text")
            isActive = false
            finished = true
            cancelProcessingTimeout()

            // 应用后处理
            val processedText = try {
                com.brycewg.asrkb.util.AsrFinalFilters.applySimple(
                    this@AsrRecognitionService,
                    prefs,
                    text
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Post-processing failed", t)
                text
            }

            // 构建结果 Bundle
            val results = Bundle().apply {
                putStringArrayList(
                    SpeechRecognizer.RESULTS_RECOGNITION,
                    arrayListOf(processedText)
                )
                // 可选：添加置信度分数
                putFloatArray(
                    SpeechRecognizer.CONFIDENCE_SCORES,
                    floatArrayOf(1.0f) // 单结果，置信度设为 1.0
                )
            }

            try {
                callback.results(results)
            } catch (t: Throwable) {
                Log.w(TAG, "results callback failed", t)
            }

            // 清理会话
            if (currentSession === this) {
                currentSession = null
            }
        }

        override fun onPartial(text: String) {
            if (canceled || finished) return
            if (text.isEmpty()) return
            Log.d(TAG, "onPartial: $text")

            // 首次检测到语音时通知
            if (!speechDetected) {
                speechDetected = true
                try {
                    callback.beginningOfSpeech()
                } catch (t: Throwable) {
                    Log.w(TAG, "beginningOfSpeech callback failed", t)
                }
            }

            // 仅当请求了部分结果时才回调
            if (config.partialResults) {
                val partialBundle = Bundle().apply {
                    putStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION,
                        arrayListOf(text)
                    )
                }
                try {
                    callback.partialResults(partialBundle)
                } catch (t: Throwable) {
                    Log.w(TAG, "partialResults callback failed", t)
                }
            }
        }

        override fun onError(message: String) {
            if (canceled || finished) return
            Log.e(TAG, "onError: $message")
            isActive = false
            finished = true
            cancelProcessingTimeout()

            val errorCode = mapToSpeechRecognizerError(message)
            try {
                callback.error(errorCode)
            } catch (t: Throwable) {
                Log.w(TAG, "error callback failed", t)
            }

            // 清理会话
            if (currentSession === this) {
                currentSession = null
            }
        }

        override fun onStopped() {
            if (canceled || finished) return
            Log.d(TAG, "onStopped")
            // 保底将会话标记为非活动，避免仅收到 onStopped 时长期占用 BUSY 状态
            isActive = false
            snapshotAudioMsForTimeoutIfNeeded()
            deliverEndOfSpeechIfNeeded()
            scheduleProcessingTimeoutIfNeeded()
        }

        override fun onAmplitude(amplitude: Float) {
            if (canceled || finished) return
            // 将 0.0-1.0 映射到 Android 惯例的 RMS 范围 (-2.0 to 10.0)
            val rms = -2f + amplitude * 12f
            try {
                callback.rmsChanged(rms)
            } catch (t: Throwable) {
                // RMS 回调失败通常不需要记录
            }
        }

        private fun snapshotAudioMsForTimeoutIfNeeded() {
            if (lastAudioMsForTimeout != 0L) return
            val t0 = sessionStartUptimeMs
            if (t0 <= 0L) return
            lastAudioMsForTimeout = (SystemClock.uptimeMillis() - t0).coerceAtLeast(0L)
            sessionStartUptimeMs = 0L
        }

        private fun deliverEndOfSpeechIfNeeded() {
            if (endOfSpeechDelivered) return
            endOfSpeechDelivered = true
            try {
                callback.endOfSpeech()
            } catch (t: Throwable) {
                Log.w(TAG, "endOfSpeech callback failed", t)
            }
        }

        private fun cancelProcessingTimeout() {
            try {
                processingTimeoutJob?.cancel()
            } catch (t: Throwable) {
                Log.w(TAG, "Cancel processing timeout failed", t)
            } finally {
                processingTimeoutJob = null
            }
        }

        private fun scheduleProcessingTimeoutIfNeeded() {
            // stop->processing 后必须最终回调 results/error；否则会卡住 currentSession，导致后续 startListening 永久 BUSY。
            cancelProcessingTimeout()
            val audioMs = lastAudioMsForTimeout
            val timeoutMs = com.brycewg.asrkb.asr.AsrTimeoutCalculator.calculateTimeoutMs(audioMs)
            processingTimeoutJob = serviceScope.launch {
                delay(timeoutMs)
                if (canceled || finished) return@launch
                if (currentSession !== this@RecognitionSession) return@launch

                Log.w(TAG, "Processing timeout fired (audioMs=$audioMs, timeoutMs=$timeoutMs), forcing session end")
                finished = true
                try {
                    engine?.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "Engine stop failed on processing timeout", t)
                }
                try {
                    // 兜底：以标准错误结束会话，避免客户端永久等待。
                    callback.error(SpeechRecognizer.ERROR_NETWORK_TIMEOUT)
                } catch (t: Throwable) {
                    Log.w(TAG, "error callback failed on processing timeout", t)
                } finally {
                    if (currentSession === this@RecognitionSession) {
                        currentSession = null
                    }
                }
            }
        }
    }
}
