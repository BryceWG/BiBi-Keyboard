package com.brycewg.asrkb.api

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.SystemClock
import android.os.Parcel
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.aidl.SpeechConfig
import com.brycewg.asrkb.asr.*
import com.brycewg.asrkb.analytics.AnalyticsManager
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 对外导出的语音服务（Binder 手写协议，兼容 AIDL 生成的代理）。
 * - 接口描述符需与 AIDL 一致：com.brycewg.asrkb.aidl.IExternalSpeechService。
 * - 方法顺序与 AIDL 保持一致，以匹配事务码。
 */
class ExternalSpeechService : Service() {

    private val prefs by lazy { Prefs(this) }
    private val sessions = ConcurrentHashMap<Int, Session>()
    @Volatile private var nextId: Int = 1

    override fun onBind(intent: Intent?): IBinder? = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code) {
                INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR_SVC)
                    return true
                }
                TRANSACTION_startSession -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val cfg = if (data.readInt() != 0) SpeechConfig.CREATOR.createFromParcel(data) else null
                    val cbBinder = data.readStrongBinder()
                    val cb = CallbackProxy(cbBinder)

                    // 开关与权限检查：仅要求开启外部联动
                    if (!prefs.externalAidlEnabled) {
                        safe { cb.onError(-1, 403, "feature disabled") }
                        reply?.apply { writeNoException(); writeInt(-3) }
                        return true
                    }
                    // 联通测试：当 vendorId == "mock" 时，无需录音权限，直接回调固定内容并结束
                    if (cfg?.vendorId == "mock") {
                        val sid = synchronized(this@ExternalSpeechService) { nextId++ }
                        safe { cb.onState(sid, STATE_RECORDING, "recording") }
                        safe { cb.onPartial(sid, "【联通测试中】……") }
                        safe { cb.onFinal(sid, "说点啥外部AIDL联通成功（mock）") }
                        safe { cb.onState(sid, STATE_IDLE, "final") }
                        reply?.apply { writeNoException(); writeInt(sid) }
                        return true
                    }

                    val permOk = ContextCompat.checkSelfPermission(
                        this@ExternalSpeechService,
                        android.Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!permOk) {
                        safe { cb.onError(-1, 401, "record permission denied") }
                        reply?.apply { writeNoException(); writeInt(-4) }
                        return true
                    }
                    if (sessions.values.any { it.engine?.isRunning == true }) {
                        reply?.apply { writeNoException(); writeInt(-2) }
                        return true
                    }

                    val sid = synchronized(this@ExternalSpeechService) { nextId++ }
                    val s = Session(sid, this@ExternalSpeechService, prefs, cb)
                    if (!s.prepare()) {
                        reply?.apply { writeNoException(); writeInt(-3) }
                        return true
                    }
                    sessions[sid] = s
                    s.start()
                    reply?.apply { writeNoException(); writeInt(sid) }
                    return true
                }
                TRANSACTION_stopSession -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    sessions[sid]?.stop()
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_cancelSession -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    sessions[sid]?.cancel()
                    sessions.remove(sid)
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_isRecording -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    val r = sessions[sid]?.engine?.isRunning == true
                    reply?.apply { writeNoException(); writeInt(if (r) 1 else 0) }
                    return true
                }
                TRANSACTION_isAnyRecording -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val r = sessions.values.any { it.engine?.isRunning == true }
                    reply?.apply { writeNoException(); writeInt(if (r) 1 else 0) }
                    return true
                }
                TRANSACTION_getVersion -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    reply?.apply { writeNoException(); writeString(com.brycewg.asrkb.BuildConfig.VERSION_NAME) }
                    return true
                }
                // ================= 推送 PCM 模式 =================
                TRANSACTION_startPcmSession -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    if (data.readInt() != 0) SpeechConfig.CREATOR.createFromParcel(data) else null
                    val cbBinder = data.readStrongBinder()
                    val cb = CallbackProxy(cbBinder)

                    if (!prefs.externalAidlEnabled) {
                        safe { cb.onError(-1, 403, "feature disabled") }
                        reply?.apply { writeNoException(); writeInt(-3) }
                        return true
                    }
                    if (sessions.values.any { it.engine?.isRunning == true }) {
                        reply?.apply { writeNoException(); writeInt(-2) }
                        return true
                    }

                    val sid = synchronized(this@ExternalSpeechService) { nextId++ }
                    val s = Session(sid, this@ExternalSpeechService, prefs, cb)
                    if (!s.preparePushPcm()) {
                        reply?.apply { writeNoException(); writeInt(-5) }
                        return true
                    }
                    sessions[sid] = s
                    s.start()
                    reply?.apply { writeNoException(); writeInt(sid) }
                    return true
                }
                TRANSACTION_writePcm -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    val bytes = data.createByteArray() ?: ByteArray(0)
                    val sr = data.readInt()
                    val ch = data.readInt()
                    sessions[sid]?.onPcmFrame(bytes, sr, ch)
                    reply?.writeNoException()
                    return true
                }
                TRANSACTION_finishPcm -> {
                    data.enforceInterface(DESCRIPTOR_SVC)
                    val sid = data.readInt()
                    sessions[sid]?.stop()
                    reply?.writeNoException()
                    return true
                }
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private class Session(
        private val id: Int,
        private val context: Context,
        private val prefs: Prefs,
        private val cb: CallbackProxy
    ) : StreamingAsrEngine.Listener {
        var engine: StreamingAsrEngine? = null
        // 统计：录音起止与耗时（用于历史记录展示）
        private var sessionStartUptimeMs: Long = 0L
        private var lastAudioMsForStats: Long = 0L
        private var lastRequestDurationMs: Long? = null
        private var lastPostprocPreview: String? = null
        @Volatile private var canceled: Boolean = false
        @Volatile private var hasAsrPartial: Boolean = false

        fun prepare(): Boolean {
            // 完全跟随应用内当前设置：供应商与是否流式均以 Prefs 为准
            val vendor = prefs.asrVendor
            val streamingPref = resolveStreamingBySettings(vendor)
            engine = buildEngine(vendor, streamingPref)
            return engine != null
        }

        fun preparePushPcm(): Boolean {
            val vendor = prefs.asrVendor
            val streamingPref = resolveStreamingBySettings(vendor)
            engine = buildPushPcmEngine(vendor, streamingPref)
            return engine != null
        }

        fun start() {
            safe { cb.onState(id, STATE_RECORDING, "recording") }
            try {
                sessionStartUptimeMs = SystemClock.uptimeMillis()
                // 新会话开始时重置上次请求耗时，避免串台（流式模式不会更新此值）
                lastRequestDurationMs = null
                lastAudioMsForStats = 0L
                lastPostprocPreview = null
                canceled = false
                hasAsrPartial = false
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to mark session start", t)
            }
            engine?.start()
        }

        fun stop() {
            engine?.stop()
            safe { cb.onState(id, STATE_PROCESSING, "processing") }
        }

        fun cancel() {
            canceled = true
            try {
                engine?.stop()
            } catch (t: Throwable) {
                Log.w(TAG, "Engine stop failed on cancel", t)
            }
            safe { cb.onState(id, STATE_IDLE, "canceled") }
        }

        fun onPcmFrame(pcm: ByteArray, sampleRate: Int, channels: Int) {
            val e = engine
            if (e !is com.brycewg.asrkb.asr.ExternalPcmConsumer) return

            try {
                e.appendPcm(pcm, sampleRate, channels)
            } catch (t: Throwable) {
                Log.w(TAG, "appendPcm failed for sid=$id", t)
            }
        }

        private fun resolveStreamingBySettings(vendor: AsrVendor): Boolean {
            return when (vendor) {
                AsrVendor.Volc -> prefs.volcStreamingEnabled
                AsrVendor.DashScope -> prefs.isDashStreamingModelSelected()
                AsrVendor.Soniox -> prefs.sonioxStreamingEnabled
                // 本地 sherpa-onnx：Paraformer 仅流式；SenseVoice/TeleSpeech 仅非流式
                AsrVendor.Paraformer -> true
                AsrVendor.SenseVoice, AsrVendor.Telespeech -> false
                AsrVendor.ElevenLabs -> prefs.elevenStreamingEnabled
                // 其他云厂商（OpenAI/Gemini/SiliconFlow/Zhipu）仅非流式
                AsrVendor.OpenAI, AsrVendor.Gemini, AsrVendor.SiliconFlow, AsrVendor.Zhipu -> false
            }
        }

        private fun buildEngine(vendor: AsrVendor, streamingPreferred: Boolean): StreamingAsrEngine? {
            val scope = CoroutineScope(Dispatchers.Main)
            return when (vendor) {
                AsrVendor.Volc -> if (streamingPreferred) {
                    VolcStreamAsrEngine(context, scope, prefs, this)
                } else {
                    VolcFileAsrEngine(
                        context,
                        scope,
                        prefs,
                        this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                }
                AsrVendor.SiliconFlow -> SiliconFlowFileAsrEngine(
                    context, scope, prefs, this,
                    onRequestDuration = { ms: Long ->
                        try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                    }
                )
                AsrVendor.ElevenLabs -> if (streamingPreferred) {
                    ElevenLabsStreamAsrEngine(context, scope, prefs, this)
                } else {
                    ElevenLabsFileAsrEngine(
                        context, scope, prefs, this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                }
                AsrVendor.OpenAI -> OpenAiFileAsrEngine(
                    context, scope, prefs, this,
                    onRequestDuration = { ms: Long ->
                        try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                    }
                )
                AsrVendor.DashScope -> if (streamingPreferred) {
                    DashscopeStreamAsrEngine(context, scope, prefs, this)
                } else {
                    DashscopeFileAsrEngine(
                        context, scope, prefs, this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                }
                AsrVendor.Gemini -> GeminiFileAsrEngine(
                    context, scope, prefs, this,
                    onRequestDuration = { ms: Long ->
                        try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                    }
                )
                AsrVendor.Soniox -> if (streamingPreferred) {
                    SonioxStreamAsrEngine(context, scope, prefs, this)
                } else {
                    SonioxFileAsrEngine(
                        context, scope, prefs, this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                }
                AsrVendor.Zhipu -> ZhipuFileAsrEngine(
                    context, scope, prefs, this,
                    onRequestDuration = { ms: Long ->
                        try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                    }
                )
                AsrVendor.SenseVoice -> SenseVoiceFileAsrEngine(
                    context, scope, prefs, this,
                    onRequestDuration = { ms: Long ->
                        try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                    }
                )
                AsrVendor.Telespeech -> TelespeechFileAsrEngine(
                    context, scope, prefs, this,
                    onRequestDuration = { ms: Long ->
                        try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                    }
                )
                AsrVendor.Paraformer -> ParaformerStreamAsrEngine(context, scope, prefs, this)
            }
        }

        private fun buildPushPcmEngine(vendor: AsrVendor, streamingPreferred: Boolean): StreamingAsrEngine? {
            val scope = CoroutineScope(Dispatchers.Main)
            return when (vendor) {
                AsrVendor.Volc -> if (streamingPreferred) {
                    VolcStreamAsrEngine(context, scope, prefs, this, externalPcmMode = true)
                } else {
                    if (prefs.volcFileStandardEnabled) {
                        com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                            context, scope, prefs, this,
                            com.brycewg.asrkb.asr.VolcStandardFileAsrEngine(
                                context, scope, prefs, this,
                                onRequestDuration = { ms: Long ->
                                    try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                                }
                            )
                        )
                    } else {
                        com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                            context, scope, prefs, this,
                            com.brycewg.asrkb.asr.VolcFileAsrEngine(
                                context, scope, prefs, this,
                                onRequestDuration = { ms: Long ->
                                    try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                                }
                            )
                        )
                    }
                }
                // 阿里 DashScope：依据设置走流式或非流式
                AsrVendor.DashScope -> if (streamingPreferred) {
                    com.brycewg.asrkb.asr.DashscopeStreamAsrEngine(context, scope, prefs, this, externalPcmMode = true)
                } else {
                    com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                        context, scope, prefs, this,
                        com.brycewg.asrkb.asr.DashscopeFileAsrEngine(
                            context, scope, prefs, this,
                            onRequestDuration = { ms: Long ->
                                try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                            }
                        )
                    )
                }
                // Soniox：依据设置走流式或非流式
                AsrVendor.Soniox -> if (streamingPreferred) {
                    com.brycewg.asrkb.asr.SonioxStreamAsrEngine(context, scope, prefs, this, externalPcmMode = true)
                } else {
                    com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                        context, scope, prefs, this,
                        com.brycewg.asrkb.asr.SonioxFileAsrEngine(
                            context, scope, prefs, this,
                            onRequestDuration = { ms: Long ->
                                try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                            }
                        )
                    )
                }
                // 其他云厂商：仅非流式（若供应商另行支持流式则走对应分支）
                AsrVendor.ElevenLabs -> if (streamingPreferred) {
                    com.brycewg.asrkb.asr.ElevenLabsStreamAsrEngine(context, scope, prefs, this, externalPcmMode = true)
                } else {
                    com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                        context, scope, prefs, this,
                        com.brycewg.asrkb.asr.ElevenLabsFileAsrEngine(
                            context, scope, prefs, this,
                            onRequestDuration = { ms: Long ->
                                try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                            }
                        )
                    )
                }
                AsrVendor.OpenAI -> com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                    context, scope, prefs, this,
                    com.brycewg.asrkb.asr.OpenAiFileAsrEngine(
                        context, scope, prefs, this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                )
                AsrVendor.Gemini -> com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                    context, scope, prefs, this,
                    com.brycewg.asrkb.asr.GeminiFileAsrEngine(
                        context, scope, prefs, this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                )
                AsrVendor.SiliconFlow -> com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                    context, scope, prefs, this,
                    com.brycewg.asrkb.asr.SiliconFlowFileAsrEngine(
                        context, scope, prefs, this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                )
                AsrVendor.Zhipu -> com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                    context, scope, prefs, this,
                    com.brycewg.asrkb.asr.ZhipuFileAsrEngine(
                        context, scope, prefs, this,
                        onRequestDuration = { ms: Long ->
                            try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                        }
                    )
                )
                // 本地：Paraformer 固定流式
                AsrVendor.Paraformer -> com.brycewg.asrkb.asr.ParaformerStreamAsrEngine(context, scope, prefs, this, externalPcmMode = true)
                // SenseVoice：支持伪流式（VAD 分片预览 + 整段离线识别）
                AsrVendor.SenseVoice -> {
                    if (prefs.svPseudoStreamEnabled) {
                        com.brycewg.asrkb.asr.SenseVoicePushPcmPseudoStreamAsrEngine(
                            context, scope, prefs, this,
                            onRequestDuration = { ms: Long ->
                                try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                            }
                        )
                    } else {
                        com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                            context, scope, prefs, this,
                            com.brycewg.asrkb.asr.SenseVoiceFileAsrEngine(
                                context, scope, prefs, this,
                                onRequestDuration = { ms: Long ->
                                    try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                                }
                            )
                        )
                    }
                }
                // TeleSpeech：支持伪流式（VAD 分片预览 + 整段离线识别）
                AsrVendor.Telespeech -> {
                    if (prefs.tsPseudoStreamEnabled) {
                        com.brycewg.asrkb.asr.TelespeechPushPcmPseudoStreamAsrEngine(
                            context, scope, prefs, this,
                            onRequestDuration = { ms: Long ->
                                try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                            }
                        )
                    } else {
                        com.brycewg.asrkb.asr.GenericPushFileAsrAdapter(
                            context, scope, prefs, this,
                            com.brycewg.asrkb.asr.TelespeechFileAsrEngine(
                                context, scope, prefs, this,
                                onRequestDuration = { ms: Long ->
                                    try { lastRequestDurationMs = ms } catch (t: Throwable) { Log.w(TAG, "set proc ms failed", t) }
                                }
                            )
                        )
                    }
                }
            }
        }

        override fun onFinal(text: String) {
            if (canceled) return
            // 若尚未收到 onStopped，则以当前时间近似计算一次时长
            if (lastAudioMsForStats == 0L && sessionStartUptimeMs > 0L) {
                try {
                    val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
                    lastAudioMsForStats = dur
                    sessionStartUptimeMs = 0L
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to compute audio duration on final", t)
                }
            }
            val doAi = try { prefs.postProcessEnabled && prefs.hasLlmKeys() } catch (_: Throwable) { false }
            if (doAi) {
                if (!hasAsrPartial && text.isNotEmpty()) {
                    hasAsrPartial = true
                    safe { cb.onPartial(id, text) }
                }
                // 执行带 AI 的完整后处理链（IO 在线程内切换）
                CoroutineScope(Dispatchers.Main).launch {
                    if (canceled) return@launch
                    val (out, usedAi) = try {
                        val onStreamingUpdate: (String) -> Unit = onStreamingUpdate@{ streamed ->
                            if (canceled) return@onStreamingUpdate
                            if (streamed.isEmpty() || streamed == lastPostprocPreview) return@onStreamingUpdate
                            lastPostprocPreview = streamed
                            safe { cb.onPartial(id, streamed) }
                        }
                        val res = com.brycewg.asrkb.util.AsrFinalFilters.applyWithAi(
                            context,
                            prefs,
                            text,
                            onStreamingUpdate = onStreamingUpdate
                        )
                        val processed = res.text
                        val finalOut = processed.ifBlank {
                            // AI 返回空：回退到简单后处理（包含正则/繁体）
                            try {
                                com.brycewg.asrkb.util.AsrFinalFilters.applySimple(
                                    context,
                                    prefs,
                                    text
                                )
                            } catch (_: Throwable) {
                                text
                            }
                        }
                        finalOut to (res.usedAi && res.ok)
                    } catch (t: Throwable) {
                        Log.w(TAG, "applyWithAi failed, fallback to simple", t)
                        val fallback = try { com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, text) } catch (_: Throwable) { text }
                        fallback to false
                    }
                    if (canceled) return@launch
                    // 记录使用统计与识别历史（来源标记为 ime；尊重开关）
                    try {
                        val audioMs = lastAudioMsForStats
                        val procMs = lastRequestDurationMs ?: 0L
                        val chars = try { com.brycewg.asrkb.util.TextSanitizer.countEffectiveChars(out) } catch (_: Throwable) { out.length }
                        AnalyticsManager.recordAsrEvent(
                            context = context,
                            vendorId = prefs.asrVendor.id,
                            audioMs = audioMs,
                            procMs = procMs,
                            source = "ime",
                            aiProcessed = usedAi,
                            charCount = chars
                        )
                        if (!prefs.disableUsageStats) {
                            prefs.recordUsageCommit("ime", prefs.asrVendor, audioMs, chars, procMs)
                        }
                        if (!prefs.disableAsrHistory) {
                            val store = com.brycewg.asrkb.store.AsrHistoryStore(context)
                            store.add(
                                com.brycewg.asrkb.store.AsrHistoryStore.AsrHistoryRecord(
                                    timestamp = System.currentTimeMillis(),
                                    text = out,
                                    vendorId = prefs.asrVendor.id,
                                    audioMs = audioMs,
                                    procMs = procMs,
                                    source = "ime",
                                    aiProcessed = usedAi,
                                    charCount = chars
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to add ASR history (external, ai)", e)
                    }
                    if (canceled) return@launch
                    safe { cb.onFinal(id, out) }
                    safe { cb.onState(id, STATE_IDLE, "final") }
                    try { (context as? ExternalSpeechService)?.onSessionDone(id) } catch (t: Throwable) { Log.w(TAG, "remove session on final failed", t) }
                }
            } else {
                if (canceled) return
                // 应用简单末处理：去尾标点和预置替换
                val out = try {
                    com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, text)
                } catch (t: Throwable) {
                    Log.w(TAG, "applySimple failed, fallback to raw text", t)
                    text
                }
                // 记录使用统计与识别历史（来源标记为 ime；尊重开关）
                try {
                    val audioMs = lastAudioMsForStats
                    val procMs = lastRequestDurationMs ?: 0L
                    val chars = try { com.brycewg.asrkb.util.TextSanitizer.countEffectiveChars(out) } catch (_: Throwable) { out.length }
                    AnalyticsManager.recordAsrEvent(
                        context = context,
                        vendorId = prefs.asrVendor.id,
                        audioMs = audioMs,
                        procMs = procMs,
                        source = "ime",
                        aiProcessed = false,
                        charCount = chars
                    )
                    if (!prefs.disableUsageStats) {
                        prefs.recordUsageCommit("ime", prefs.asrVendor, audioMs, chars, procMs)
                    }
                    if (!prefs.disableAsrHistory) {
                        val store = com.brycewg.asrkb.store.AsrHistoryStore(context)
                        store.add(
                            com.brycewg.asrkb.store.AsrHistoryStore.AsrHistoryRecord(
                                timestamp = System.currentTimeMillis(),
                                text = out,
                                vendorId = prefs.asrVendor.id,
                                audioMs = audioMs,
                                procMs = procMs,
                                source = "ime",
                                aiProcessed = false,
                                charCount = chars
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add ASR history (external, simple)", e)
                }
                safe { cb.onFinal(id, out) }
                safe { cb.onState(id, STATE_IDLE, "final") }
                try { (context as? ExternalSpeechService)?.onSessionDone(id) } catch (t: Throwable) { Log.w(TAG, "remove session on final failed", t) }
            }
        }

        override fun onError(message: String) {
            if (canceled) return
            safe {
                cb.onError(id, 500, message)
                cb.onState(id, STATE_ERROR, message)
            }
            try { (context as? ExternalSpeechService)?.onSessionDone(id) } catch (t: Throwable) { Log.w(TAG, "remove session on error failed", t) }
        }

        override fun onPartial(text: String) {
            if (canceled) return
            if (text.isNotEmpty()) {
                hasAsrPartial = true
                safe { cb.onPartial(id, text) }
            }
        }

        override fun onStopped() {
            if (canceled) return
            // 计算一次会话录音时长
            if (sessionStartUptimeMs > 0L) {
                try {
                    val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
                    lastAudioMsForStats = dur
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to compute audio duration on stop", t)
                } finally {
                    sessionStartUptimeMs = 0L
                }
            }
            safe { cb.onState(id, STATE_PROCESSING, "processing") }
        }

        override fun onAmplitude(amplitude: Float) {
            if (canceled) return
            safe { cb.onAmplitude(id, amplitude) }
        }
    }

    private class CallbackProxy(private val remote: IBinder?) {
        fun onState(sessionId: Int, state: Int, msg: String) {
            transact(CB_onState) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeInt(state)
                data.writeString(msg)
            }
        }
        fun onPartial(sessionId: Int, text: String) {
            transact(CB_onPartial) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeString(text)
            }
        }
        fun onFinal(sessionId: Int, text: String) {
            transact(CB_onFinal) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeString(text)
            }
        }
        fun onError(sessionId: Int, code: Int, message: String) {
            transact(CB_onError) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeInt(code)
                data.writeString(message)
            }
        }
        fun onAmplitude(sessionId: Int, amp: Float) {
            transact(CB_onAmplitude) { data ->
                data.writeInterfaceToken(DESCRIPTOR_CB)
                data.writeInt(sessionId)
                data.writeFloat(amp)
            }
        }

        private inline fun transact(code: Int, fill: (Parcel) -> Unit) {
            val b = remote ?: return
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                fill(data)
                b.transact(code, data, reply, 0)
                reply.readException()
            } catch (t: Throwable) {
                Log.w(TAG, "callback transact failed: code=$code", t)
            } finally {
                try {
                    data.recycle()
                } catch (t: Throwable) {
                    Log.w(TAG, "data.recycle failed", t)
                }
                try {
                    reply.recycle()
                } catch (t: Throwable) {
                    Log.w(TAG, "reply.recycle failed", t)
                }
            }
        }
    }

    companion object {
        private const val TAG = "ExternalSpeechSvc"

        // 与 AIDL 生成的 Stub 保持一致的描述符与事务号
        private const val DESCRIPTOR_SVC = "com.brycewg.asrkb.aidl.IExternalSpeechService"
        private const val TRANSACTION_startSession = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val TRANSACTION_stopSession = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val TRANSACTION_cancelSession = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val TRANSACTION_isRecording = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TRANSACTION_isAnyRecording = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TRANSACTION_getVersion = IBinder.FIRST_CALL_TRANSACTION + 5
        private const val TRANSACTION_startPcmSession = IBinder.FIRST_CALL_TRANSACTION + 6
        private const val TRANSACTION_writePcm = IBinder.FIRST_CALL_TRANSACTION + 7
        private const val TRANSACTION_finishPcm = IBinder.FIRST_CALL_TRANSACTION + 8

        private const val DESCRIPTOR_CB = "com.brycewg.asrkb.aidl.ISpeechCallback"
        private const val CB_onState = IBinder.FIRST_CALL_TRANSACTION + 0
        private const val CB_onPartial = IBinder.FIRST_CALL_TRANSACTION + 1
        private const val CB_onFinal = IBinder.FIRST_CALL_TRANSACTION + 2
        private const val CB_onError = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val CB_onAmplitude = IBinder.FIRST_CALL_TRANSACTION + 4

        private const val STATE_IDLE = 0
        private const val STATE_RECORDING = 1
        private const val STATE_PROCESSING = 2
        private const val STATE_ERROR = 3

        private inline fun safe(block: () -> Unit) { try { block() } catch (t: Throwable) { Log.w(TAG, "callback failed", t) } }
    }

    // 统一的会话清理入口：在 onFinal/onError 触发后移除，避免内存泄漏
    private fun onSessionDone(sessionId: Int) {
        try {
            sessions.remove(sessionId)
        } catch (t: Throwable) {
            Log.w(TAG, "sessions.remove failed for id=$sessionId", t)
        }
    }
}
