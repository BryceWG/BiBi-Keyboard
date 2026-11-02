// X-ASR local streaming engine and sherpa-onnx reflection adapter.
package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 基于 sherpa-onnx OnlineRecognizer 的本地 X-ASR 流式识别引擎。
 * - 反射调用 sherpa-onnx Kotlin API，避免编译期强耦合。
 * - 录音/外部 PCM 同步进入单写者会话队列；采集 Flow 结束后才 Finish，再按模型 chunk 补 right-context。
 */
class XAsrStreamAsrEngine(
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
        private const val TAG = "XAsrStreamAsrEngine"
        private const val FRAME_MS = 200

        // 仅防止采集协程挂死；正常路径必须等 Flow/Channel 自然结束。
        private const val CAPTURE_DRAIN_HANG_TIMEOUT_MS = 3000L
    }

    private val running = AtomicBoolean(false)

    @Volatile private var useItnForSession: Boolean = false

    private var audioJob: Job? = null
    private val mgr = XAsrOnnxManager.getInstance()
    private val stopController = XAsrStopController()

    @Volatile private var session: XAsrStreamSession? = null

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val externalVadInputLeveler = VadInputLevelerBranch(sampleRate = sampleRate)

    private val loggedAudioBytes = AtomicInteger(0)

    @Volatile private var streamLog: LocalAsrCallLogger.Session? = null

    @Volatile private var loadLog: LocalAsrCallLogger.Session? = null

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        loggedAudioBytes.set(0)
        streamLog = LocalAsrCallLogger.startInference(
            prefs = prefs,
            vendor = AsrVendor.XAsr,
            source = if (externalPcmMode) "external_pcm_stream" else "streaming",
            audioBytes = 0,
            sampleRate = sampleRate
        )
        // 外部推流模式下不检查录音权限
        if (!externalPcmMode) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                val msg = context.getString(R.string.error_record_permission_denied)
                failStreamLog(msg)
                listener.onError(msg)
                return
            }
        }

        if (!mgr.isOnnxAvailable()) {
            val msg = context.getString(R.string.error_local_asr_not_ready)
            failStreamLog(msg)
            listener.onError(msg)
            return
        }

        useItnForSession = try {
            prefs.xAsrUseItn
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read xAsrUseItn", t)
            false
        }
        val newSession = XAsrStreamSession(
            scope = scope,
            sampleRate = sampleRate,
            frameMs = FRAME_MS,
            useItn = useItnForSession,
            nowMs = { SystemClock.uptimeMillis() },
            onPartial = { text ->
                try {
                    listener.onPartial(text)
                } catch (t: Throwable) {
                    Log.e(TAG, "notify partial failed", t)
                }
            },
            onFinal = { text ->
                completeStreamLog(text)
                try {
                    listener.onFinal(text)
                } catch (t: Throwable) {
                    Log.e(TAG, "notify final failed", t)
                }
                running.set(false)
            },
            logDiag = { event, data -> logDiag(event, data) },
            dropOverflowBeforeSink = !externalPcmMode
        )
        val previous = stopController.onNewSession(newSession)
        previous?.cancel()
        session = newSession
        running.set(true)
        externalVadInputLeveler.reset()
        newSession.start()

        // 非外部模式才启动采集；外部模式下由 appendPcm 注入
        if (!externalPcmMode) startCapture()
        scope.launch(Dispatchers.Default) {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val filesCheck = checkXAsrModelFiles(context, java.io.File(base, "x_asr"))
            val files = (filesCheck as? LocalModelCheck.Ready)?.value
            if (files == null) {
                val msg = localModelErrorMessage(
                    context,
                    filesCheck,
                    R.string.error_x_asr_model_missing
                )
                failPrepare(newSession, msg)
                return@launch
            }

            val keepMinutes = prefs.xAsrKeepAliveMinutes
            val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
            val alwaysKeep = keepMinutes < 0

            val ok = mgr.prepare(
                tokens = files.tokens.absolutePath,
                encoder = files.encoder.absolutePath,
                decoder = files.decoder.absolutePath,
                joiner = files.joiner.absolutePath,
                numThreads = prefs.xAsrNumThreads,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = {
                    loadLog = LocalAsrCallLogger.startLoad(
                        prefs = prefs,
                        vendor = AsrVendor.XAsr,
                        source = "streaming_load"
                    )
                    notifyLoadUi(true)
                },
                onLoadDone = {
                    loadLog?.success("loaded=true")
                    loadLog = null
                    notifyLoadUi(false)
                }
            )
            if (!ok) {
                Log.w(TAG, "X-ASR prepare() failed")
                loadLog?.failure("prepare returned false")
                loadLog = null
                failPrepare(newSession, context.getString(R.string.error_local_asr_not_ready))
                return@launch
            }

            val stream = mgr.createStreamOrNull()
            if (stream == null) {
                val msg = context.getString(R.string.error_local_asr_not_ready)
                failPrepare(newSession, msg)
                return@launch
            }
            if (session !== newSession) {
                try {
                    mgr.releaseStream(stream)
                } catch (t: Throwable) {
                    Log.e(TAG, "release stale stream failed", t)
                }
                return@launch
            }
            newSession.attachSink(XAsrOnnxStreamSink(mgr, stream))
        }
    }

    // ========== ExternalPcmConsumer（外部推流） ==========
    override suspend fun awaitReady(timeoutMs: Long): Boolean {
        val active = session ?: return false
        val started = SystemClock.uptimeMillis()
        val ok = active.awaitSinkReady(timeoutMs)
        logDiag(
            "xasr_sink_ready",
            mapOf(
                "waitedMs" to (SystemClock.uptimeMillis() - started).coerceAtLeast(0L),
                "ok" to ok
            )
        )
        return ok
    }

    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        val active = session ?: return
        if (sampleRate != 16000 || channels != 1) return
        val leveled = externalVadInputLeveler.process(pcm)
        try {
            listener.onAmplitude(leveled.stableAmplitude)
        } catch (
            _: Throwable
        ) { }
        if (active.enqueuePcm(pcm)) {
            loggedAudioBytes.addAndGet(pcm.size)
        }
    }

    override fun stop() {
        running.set(false)
        val armed = stopController.tryBeginStop(externalPcmMode)
        if (armed == null) {
            logDiag("xasr_stop", mapOf("reentry" to true))
            return
        }
        audioJob = null
        logDiag(
            "xasr_stop",
            mapOf(
                "reentry" to false,
                "waitForCapture" to armed.waitForCapture
            )
        )
        if (!armed.waitForCapture) {
            armed.session?.finish()
            return
        }
        val captureJob = armed.captureJob
        val drained = armed.drained
        val stoppingSession = armed.session
        // 等采集 Flow 自然结束（Channel 关闭且帧已 enqueue），再 Finish。超时只防挂死。
        scope.launch(Dispatchers.IO) {
            withContext(NonCancellable) {
                val completed = withTimeoutOrNull(CAPTURE_DRAIN_HANG_TIMEOUT_MS) { drained.await() }
                if (completed == null && captureJob?.isActive == true) {
                    captureJob.cancel()
                }
                if (!drained.isCompleted) {
                    drained.await()
                }
                logDiag(
                    "xasr_capture_drain",
                    mapOf("complete" to (completed == true))
                )
                stoppingSession?.finish()
            }
        }
    }

    private fun notifyLoadUi(start: Boolean) {
        val ui = (listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi) ?: return
        if (start) ui.onLocalModelLoadStart() else ui.onLocalModelLoadDone()
    }

    private fun completeStreamLog(finalText: String) {
        val trimmed = finalText.trim()
        val response = "resultChars=${trimmed.length}; empty=${trimmed.isEmpty()}; audioBytes=${loggedAudioBytes.get()}"
        if (trimmed.isEmpty()) {
            streamLog?.failure("Empty ASR result")
        } else {
            streamLog?.success(response)
        }
        streamLog = null
    }

    private fun failStreamLog(message: String) {
        streamLog?.failure(message)
        streamLog = null
    }

    private fun failPrepare(active: XAsrStreamSession, message: String) {
        failStreamLog(message)
        try {
            listener.onError(message)
        } catch (t: Throwable) {
            Log.e(TAG, "notify error failed", t)
        }
        running.set(false)
        if (session === active) {
            active.cancel()
        }
    }

    private fun logDiag(event: String, data: Map<String, Any?> = emptyMap()) {
        DebugLogManager.logBase(category = "asr", event = event, data = data)
    }

    private fun startCapture() {
        audioJob?.cancel()
        val drained = CompletableDeferred<Boolean>()
        stopController.captureDrained = drained
        val job = scope.launch(Dispatchers.IO) {
            try {
                runCaptureLoop()
            } finally {
                drained.complete(true)
            }
        }
        audioJob = job
        stopController.captureJob = job
    }

    private suspend fun runCaptureLoop() {
        val chunkMillis = FRAME_MS
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
            val msg = context.getString(R.string.error_record_permission_denied)
            failStreamLog(msg)
            listener.onError(msg)
            running.set(false)
            session?.cancel()
            return
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

        val continuousFlow = ContinuousCaptureCoordinator.attachActiveSessionFlow(
            sampleRate = sampleRate,
            channelConfig = channelConfig,
            audioFormat = audioFormat
        )
        val fromContinuous = continuousFlow != null
        val captureFlow = (continuousFlow ?: audioManager.startPlatformCapture())
            .onEach { audioFrameSink?.onAudioFrame(it, sampleRate, 1) }

        try {
            Log.d(TAG, "Starting audio capture for X-ASR with chunk=${chunkMillis}ms")
            captureFlow.collect { audioChunk ->
                if (audioChunk.isEmpty()) return@collect
                val leveled = vadInputLeveler.process(audioChunk)

                try {
                    listener.onAmplitude(leveled.stableAmplitude)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to calculate amplitude", t)
                }

                val shouldStopForVad = vadDetector?.shouldStop(
                    audioChunk,
                    audioChunk.size
                ) == true
                val active = session
                if (active != null && active.enqueuePcm(audioChunk)) {
                    loggedAudioBytes.addAndGet(audioChunk.size)
                }

                if (maxDurationLimiter.acceptPcm(audioChunk.size)) {
                    Log.d(TAG, "Max recording duration reached, stopping recording")
                    try {
                        listener.onStopped()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to notify stopped", t)
                    }
                    stop()
                }

                // VAD 自动判停：当前块必须先进入队列，避免尾段在停止前被丢弃。
                if (shouldStopForVad) {
                    Log.d(TAG, "Silence detected, stopping recording")
                    try {
                        listener.onStopped()
                    } catch (
                        t: Throwable
                    ) {
                        Log.e(TAG, "Failed to notify stopped", t)
                    }
                    stop()
                }
                // 平台采集在 stop 后收完当前块即可退出；热采集要等到 Channel 关闭才能排空尾帧。
                if (!running.get() && !fromContinuous) {
                    throw CancellationException("xasr-capture-stop")
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "Audio streaming cancelled: ${t.message}")
            } else {
                Log.e(TAG, "Audio streaming failed: ${t.message}", t)
                val msg = if (isLikelyMicInUseError(t)) {
                    context.getString(R.string.asr_error_mic_in_use)
                } else {
                    context.getString(R.string.error_audio_error, t.message ?: "")
                }
                failStreamLog(msg)
                try {
                    listener.onError(msg)
                } catch (
                    err: Throwable
                ) {
                    Log.e(TAG, "notify error failed", err)
                }

                running.set(false)
                session?.cancel()
            }
        } finally {
            try {
                vadDetector?.release()
            } catch (t: Throwable) {
                Log.w(TAG, "VAD release failed", t)
            }
        }
    }

    private fun isLikelyMicInUseError(t: Throwable): Boolean {
        fun matchOne(msg: String?): Boolean {
            val m = msg?.lowercase() ?: return false
            if (m.contains("audiorecord read error")) {
                val code = m.substringAfter("audiorecord read error:", "").trim().toIntOrNull()
                return code == -3 || code == -6 || code == -2
            }
            if (m.contains("failed to start recording") || m.contains("startrecording")) return true
            if (m.contains("error reading audio data") || m.contains("audiorecord")) return true
            return false
        }
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            if (matchOne(cur.message)) return true
            cur = cur.cause
            depth++
        }
        return false
    }
}

private class XAsrOnnxStreamSink(
    private val mgr: XAsrOnnxManager,
    private val stream: Any
) : XAsrStreamSink {
    override fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        mgr.acceptWaveform(stream, samples, sampleRate)
    }

    override fun isReady(): Boolean = mgr.isReady(stream)

    override fun decode() {
        mgr.decode(stream)
    }

    override fun getResultText(): String? = mgr.getResultText(stream)

    override fun inputFinished() {
        mgr.inputFinished(stream)
    }

    override fun release() {
        mgr.releaseStream(stream)
    }
}

/**
 * 查找 X-ASR 480ms 模型目录：tokens.txt、encoder-480ms、decoder-480ms、joiner-480ms 必须同目录。
 */
fun findXAsrModelDir(root: java.io.File?): java.io.File? = findXAsrModelFiles(root)?.dir

internal data class XAsrModelFiles(
    val dir: java.io.File,
    val tokens: java.io.File,
    val encoder: java.io.File,
    val decoder: java.io.File,
    val joiner: java.io.File
)

internal fun findXAsrModelFiles(root: java.io.File?): XAsrModelFiles? = (checkXAsrModelFiles(root) as? LocalModelCheck.Ready)?.value

internal fun checkXAsrModelFiles(root: java.io.File?): LocalModelCheck<XAsrModelFiles> = checkXAsrModelFilesInternal(context = null, root = root)

internal fun checkXAsrModelFiles(
    context: Context,
    root: java.io.File?
): LocalModelCheck<XAsrModelFiles> = checkXAsrModelFilesInternal(context = context.applicationContext, root = root)

private fun checkXAsrModelFilesInternal(
    context: Context?,
    root: java.io.File?
): LocalModelCheck<XAsrModelFiles> {
    if (root == null || !root.exists() || !root.isDirectory) return LocalModelCheck.Missing

    fun filesIn(dir: java.io.File): LocalModelCheck<XAsrModelFiles> {
        val files = XAsrModelFiles(
            dir = dir,
            tokens = java.io.File(dir, "tokens.txt"),
            encoder = java.io.File(dir, "encoder-480ms.onnx"),
            decoder = java.io.File(dir, "decoder-480ms.onnx"),
            joiner = java.io.File(dir, "joiner-480ms.onnx")
        )
        val check = if (context != null) {
            requireModelFilesCached(
                context,
                files.tokens to LocalModelSpecs.XAsr.tokens,
                files.encoder to LocalModelSpecs.XAsr.encoder,
                files.decoder to LocalModelSpecs.XAsr.decoder,
                files.joiner to LocalModelSpecs.XAsr.joiner
            )
        } else {
            requireModelFiles(
                files.tokens to LocalModelSpecs.XAsr.tokens,
                files.encoder to LocalModelSpecs.XAsr.encoder,
                files.decoder to LocalModelSpecs.XAsr.decoder,
                files.joiner to LocalModelSpecs.XAsr.joiner
            )
        }
        return when (check) {
            is LocalModelCheck.IntegrityError -> LocalModelCheck.IntegrityError(check.fileName)
            LocalModelCheck.Missing -> LocalModelCheck.Missing
            is LocalModelCheck.Ready -> LocalModelCheck.Ready(files)
        }
    }

    val direct = filesIn(root)
    if (direct is LocalModelCheck.Ready || direct is LocalModelCheck.IntegrityError) return direct
    val subs = root.listFiles() ?: return LocalModelCheck.Missing
    subs.forEach { child ->
        if (child.isDirectory && child.name != "__MACOSX" && !child.name.startsWith(".")) {
            val nested = checkXAsrModelFilesInternal(context, child)
            if (nested is LocalModelCheck.Ready || nested is LocalModelCheck.IntegrityError) return nested
        }
    }
    return LocalModelCheck.Missing
}

/**
 * 释放 X-ASR 识别器（供设置页或切换供应商时手工卸载）
 */
fun unloadXAsrRecognizer() {
    LocalModelLoadCoordinator.cancel()
    XAsrOnnxManager.getInstance().unload()
}

// 判断是否已有缓存的本地 X-ASR 识别器（已加载或正在加载中）
fun isXAsrPrepared(): Boolean {
    val manager = XAsrOnnxManager.getInstance()
    return manager.isPrepared() || manager.isPreparing()
}

private const val X_ASR_CJK_PUNCT = "，。！？；：、（）《》〈〉【】「」『』“”‘’"
private const val X_ASR_ASCII_PUNCT_NO_LEADING_SPACE = ",.!?;:%)]}"

internal fun formatXAsrText(text: String, useItn: Boolean): String {
    var out = normalizeXAsrCjkSpacing(text.trim())
    if (useItn && out.isNotEmpty()) {
        out = normalizeXAsrCjkSpacing(ChineseItn.normalize(out))
    }
    return out
}

private fun normalizeXAsrCjkSpacing(text: String): String {
    if (text.isEmpty()) return text
    val chars = text.toCharArray()
    val out = StringBuilder(text.length)
    var i = 0
    while (i < chars.size) {
        val ch = chars[i]
        if (ch.isWhitespace()) {
            val prev = out.lastOrNull()
            var j = i + 1
            while (j < chars.size && chars[j].isWhitespace()) {
                j++
            }
            val next = chars.getOrNull(j)
            val dropCjkSpace = prev != null &&
                next != null &&
                isXAsrCjkOrPunct(prev) &&
                isXAsrCjkOrPunct(next)
            val dropBeforeAsciiPunct = next != null &&
                X_ASR_ASCII_PUNCT_NO_LEADING_SPACE.indexOf(next) >= 0
            if (!dropCjkSpace && !dropBeforeAsciiPunct) {
                var k = i
                while (k < j) {
                    out.append(chars[k])
                    k++
                }
            }
            i = j
            continue
        }
        out.append(ch)
        i++
    }
    return out.toString()
}

private fun isXAsrCjkOrPunct(ch: Char): Boolean = isXAsrCjk(ch) || X_ASR_CJK_PUNCT.indexOf(ch) >= 0

private fun isXAsrCjk(ch: Char): Boolean = ch in '\u3400'..'\u4DBF' ||
    ch in '\u4E00'..'\u9FFF' ||
    ch in '\uF900'..'\uFAFF'

// ===== 反射式在线识别管理器 =====

private class ReflectiveOnlineStream(val instance: Any) {
    private val cls = instance.javaClass
    private val acceptWaveformMethod: Method? = try {
        cls.getMethod("acceptWaveform", FloatArray::class.java, Int::class.javaPrimitiveType)
    } catch (_: Throwable) {
        null
    }
    private val inputFinishedMethod: Method? = try {
        cls.getMethod("inputFinished")
    } catch (_: Throwable) {
        null
    }
    private val releaseMethod: Method? = try {
        cls.getMethod("release")
    } catch (_: Throwable) {
        null
    }

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        try {
            (acceptWaveformMethod ?: throw NoSuchMethodException("acceptWaveform"))
                .invoke(instance, samples, sampleRate)
        } catch (t: Throwable) {
            Log.e("ROnlineStream", "acceptWaveform reflection failed", t)
        }
    }

    fun inputFinished() {
        try {
            (inputFinishedMethod ?: throw NoSuchMethodException("inputFinished")).invoke(instance)
        } catch (t: Throwable) {
            Log.e("ROnlineStream", "inputFinished failed", t)
        }
    }

    fun release() {
        try {
            (releaseMethod ?: throw NoSuchMethodException("release")).invoke(instance)
        } catch (t: Throwable) {
            Log.e("ROnlineStream", "release failed", t)
        }
    }
}

private class ReflectiveOnlineRecognizer(private val instance: Any, private val cls: Class<*>) {
    private val createStreamMethod: Method = cls.getMethod("createStream", String::class.java)
    private val decodeMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val isReadyMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val getResultMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val resultTextMethodCache = ConcurrentHashMap<Class<*>, Method>()
    private val releaseMethod: Method? = try {
        cls.getMethod("release")
    } catch (_: Throwable) {
        null
    }

    fun createStream(): ReflectiveOnlineStream {
        val s = createStreamMethod.invoke(instance, "") as Any
        return ReflectiveOnlineStream(s)
    }

    fun isReady(stream: ReflectiveOnlineStream): Boolean {
        val streamClass = stream.instance.javaClass
        val method = isReadyMethodCache.getOrPut(streamClass) {
            cls.getMethod("isReady", streamClass)
        }
        return method.invoke(instance, stream.instance) as Boolean
    }

    fun decode(stream: ReflectiveOnlineStream) {
        val streamClass = stream.instance.javaClass
        val method = decodeMethodCache.getOrPut(streamClass) {
            cls.getMethod("decode", streamClass)
        }
        method.invoke(instance, stream.instance)
    }

    fun getResultText(stream: ReflectiveOnlineStream): String? {
        val streamClass = stream.instance.javaClass
        val getResultMethod = getResultMethodCache.getOrPut(streamClass) {
            cls.getMethod("getResult", streamClass)
        }
        val res = getResultMethod.invoke(instance, stream.instance)
        return try {
            val resultClass = res.javaClass
            val textMethod = resultTextMethodCache.getOrPut(resultClass) {
                resultClass.getMethod("getText")
            }
            textMethod.invoke(res) as? String
        } catch (t: Throwable) {
            Log.e("ROnlineRecognizer", "getResultText getter not found", t)
            null
        }
    }

    fun release() {
        try {
            (releaseMethod ?: throw NoSuchMethodException("release")).invoke(instance)
        } catch (t: Throwable) {
            Log.e("ROnlineRecognizer", "release failed", t)
        }
    }
}

class XAsrOnnxManager private constructor() {
    companion object {
        private const val TAG = "XAsrOnnxManager"

        @Volatile private var instance: XAsrOnnxManager? = null
        fun getInstance(): XAsrOnnxManager = instance ?: synchronized(this) {
            instance ?: XAsrOnnxManager().also { instance = it }
        }
    }

    private val scope = CoroutineScope(SupervisorJob())
    private val mutex = Mutex()
    private val runtimeLock = Any()

    @Volatile private var cachedConfig: RecognizerConfig? = null

    @Volatile private var cachedRecognizer: ReflectiveOnlineRecognizer? = null

    @Volatile private var preparing: Boolean = false

    @Volatile private var clsOnlineRecognizer: Class<*>? = null

    @Volatile private var clsOnlineRecognizerConfig: Class<*>? = null

    @Volatile private var clsOnlineModelConfig: Class<*>? = null

    @Volatile private var clsOnlineTransducerModelConfig: Class<*>? = null

    @Volatile private var clsFeatureConfig: Class<*>? = null

    @Volatile private var unloadJob: Job? = null

    // 最近一次配置与流计数：用于保留/卸载
    @Volatile private var lastKeepAliveMs: Long = 0L

    @Volatile private var lastAlwaysKeep: Boolean = false
    private val activeStreams = AtomicInteger(0)

    @Volatile private var pendingUnload: Boolean = false

    fun isOnnxAvailable(): Boolean = sherpaIsClassAvailable(
        TAG,
        "com.k2fsa.sherpa.onnx.OnlineRecognizer"
    )

    fun unload() {
        pendingUnload = true
        scope.launch {
            tryUnloadIfIdle()
        }
    }

    fun isPrepared(): Boolean = cachedRecognizer != null
	
    fun isPreparing(): Boolean = preparing

    private fun invokeCallbackSafely(name: String, callback: (() -> Unit)?) {
        sherpaInvokeCallbackSafely(TAG, name, callback)
    }
	
    private suspend fun tryUnloadIfIdle() {
        mutex.withLock {
            if (!pendingUnload) return@withLock
            if (activeStreams.get() > 0) return@withLock
            try {
                synchronized(runtimeLock) {
                    cachedRecognizer?.release()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "unload failed", t)
            } finally {
                cachedRecognizer = null
                cachedConfig = null
                pendingUnload = false
            }
        }
    }

    private fun scheduleAutoUnload(keepAliveMs: Long, alwaysKeep: Boolean) {
        unloadJob = sherpaScheduleAutoUnload(TAG, scope, unloadJob, keepAliveMs, alwaysKeep) {
            unload()
        }
    }

    private fun initClasses() {
        if (clsOnlineRecognizer == null) {
            clsOnlineRecognizer = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
            clsOnlineRecognizerConfig =
                Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig")
            clsOnlineModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OnlineModelConfig")
            clsOnlineTransducerModelConfig =
                Class.forName("com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig")
            clsFeatureConfig = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
            Log.d(TAG, "Initialized reflection classes for online recognizer")
        }
    }

    private fun trySetField(target: Any, name: String, value: Any?): Boolean = sherpaTrySetField(TAG, target, name, value)

    private data class RecognizerConfig(
        val tokens: String,
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val numThreads: Int,
        val provider: String = "cpu",
        val modelType: String = "zipformer2",
        val sampleRate: Int = 16000,
        val featureDim: Int = 80,
        val debug: Boolean = false
    ) {
        fun toCacheKey(): String = listOf(
            tokens,
            encoder,
            decoder,
            joiner,
            numThreads,
            provider,
            modelType,
            sampleRate,
            featureDim,
            debug
        ).joinToString("|")
    }

    private fun buildTransducerModelConfig(encoder: String, decoder: String, joiner: String): Any {
        val cls = clsOnlineTransducerModelConfig!!
        // 1.13.3 起 data class 增加 qnnConfig 且无 @JvmOverloads，三 String 构造在 JVM 上消失。
        val inst = try {
            cls.getDeclaredConstructor().newInstance()
        } catch (_: NoSuchMethodException) {
            cls.getDeclaredConstructor(
                String::class.java,
                String::class.java,
                String::class.java
            ).newInstance(encoder, decoder, joiner)
        }
        trySetField(inst, "encoder", encoder)
        trySetField(inst, "decoder", decoder)
        trySetField(inst, "joiner", joiner)
        return inst
    }

    private fun buildModelConfig(
        tokens: String,
        encoder: String,
        decoder: String,
        joiner: String,
        numThreads: Int,
        provider: String,
        modelType: String,
        debug: Boolean
    ): Any {
        val transducer = buildTransducerModelConfig(encoder, decoder, joiner)
        val model = clsOnlineModelConfig!!.getDeclaredConstructor().newInstance()
        // Android sherpa-onnx exposes Python from_transducer(...) through OnlineModelConfig.transducer.
        trySetField(model, "tokens", tokens)
        trySetField(model, "numThreads", numThreads)
        trySetField(model, "provider", provider)
        trySetField(model, "modelType", modelType)
        trySetField(model, "debug", debug)
        trySetField(model, "transducer", transducer)
        return model
    }

    private fun buildFeatureConfig(sampleRate: Int, featureDim: Int): Any {
        val feat = clsFeatureConfig!!.getDeclaredConstructor().newInstance()
        trySetField(feat, "sampleRate", sampleRate)
        trySetField(feat, "featureDim", featureDim)
        return feat
    }

    private fun buildRecognizerConfig(config: RecognizerConfig): Any {
        val model =
            buildModelConfig(
                config.tokens,
                config.encoder,
                config.decoder,
                config.joiner,
                config.numThreads,
                config.provider,
                config.modelType,
                config.debug
            )
        val feat = buildFeatureConfig(config.sampleRate, config.featureDim)
        val rec = clsOnlineRecognizerConfig!!.getDeclaredConstructor().newInstance()
        // OnlineRecognizerConfig: modelConfig/featConfig/decodingMethod/enableEndpoint/maxActivePaths...
        trySetField(rec, "modelConfig", model)
        trySetField(rec, "featConfig", feat)
        trySetField(rec, "decodingMethod", "greedy_search")
        trySetField(rec, "enableEndpoint", false)
        trySetField(rec, "maxActivePaths", 4)
        return rec
    }

    private fun createRecognizer(recConfig: Any): Any {
        val ctor = clsOnlineRecognizer!!.getDeclaredConstructor(
            android.content.res.AssetManager::class.java,
            clsOnlineRecognizerConfig!!
        )
        return ctor.newInstance(null, recConfig)
    }

    suspend fun prepare(
        tokens: String,
        encoder: String,
        decoder: String,
        joiner: String,
        numThreads: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null
    ): Boolean = mutex.withLock {
        try {
            pendingUnload = false
            unloadJob?.cancel()
            unloadJob = null
            initClasses()
            val config = RecognizerConfig(tokens, encoder, decoder, joiner, numThreads)

            val cached = cachedRecognizer
            val sameConfig = cachedConfig == config
            if (sameConfig && cached != null) {
                lastKeepAliveMs = keepAliveMs
                lastAlwaysKeep = alwaysKeep
                scheduleAutoUnload(keepAliveMs, alwaysKeep)
                return@withLock true
            }

            if (!sameConfig && cached != null && activeStreams.get() > 0) {
                Log.w(TAG, "prepare skipped: ${activeStreams.get()} active streams")
                lastKeepAliveMs = keepAliveMs
                lastAlwaysKeep = alwaysKeep
                scheduleAutoUnload(keepAliveMs, alwaysKeep)
                return@withLock true
            }

            preparing = true
            var newRecognizer: ReflectiveOnlineRecognizer? = null
            try {
                invokeCallbackSafely("onLoadStart", onLoadStart)
                currentCoroutineContext().ensureActive()

                val recConfig = buildRecognizerConfig(config)
                currentCoroutineContext().ensureActive()
                val inst = synchronized(runtimeLock) { createRecognizer(recConfig) }
                newRecognizer = ReflectiveOnlineRecognizer(inst, clsOnlineRecognizer!!)

                currentCoroutineContext().ensureActive()
                val oldRecognizer = cachedRecognizer
                synchronized(runtimeLock) { cachedRecognizer = newRecognizer }
                cachedConfig = config
                newRecognizer = null

                invokeCallbackSafely("onLoadDone", onLoadDone)
                if (oldRecognizer != null) {
                    synchronized(runtimeLock) { oldRecognizer.release() }
                }

                lastKeepAliveMs = keepAliveMs
                lastAlwaysKeep = alwaysKeep
                scheduleAutoUnload(keepAliveMs, alwaysKeep)
                true
            } catch (t: CancellationException) {
                val toRelease = newRecognizer
                if (toRelease != null) {
                    synchronized(runtimeLock) { toRelease.release() }
                }
                throw t
            } catch (t: Throwable) {
                val toRelease = newRecognizer
                if (toRelease != null) {
                    synchronized(runtimeLock) { toRelease.release() }
                }
                throw t
            } finally {
                preparing = false
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "prepare failed", t)
            false
        }
    }

    suspend fun createStreamOrNull(): Any? = mutex.withLock {
        try {
            pendingUnload = false
            unloadJob?.cancel()
            unloadJob = null
            val r = cachedRecognizer ?: return@withLock null
            val s = synchronized(runtimeLock) { r.createStream() }
            activeStreams.incrementAndGet()
            s
        } catch (t: Throwable) {
            Log.e(TAG, "createStream failed", t)
            null
        }
    }

    fun acceptWaveform(stream: Any, samples: FloatArray, sampleRate: Int) {
        try {
            synchronized(runtimeLock) {
                if (stream is ReflectiveOnlineStream) stream.acceptWaveform(samples, sampleRate)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "acceptWaveform failed", t)
        }
    }

    fun inputFinished(stream: Any) {
        try {
            synchronized(runtimeLock) {
                if (stream is ReflectiveOnlineStream) stream.inputFinished()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "inputFinished failed", t)
        }
    }

    fun isReady(stream: Any): Boolean = try {
        synchronized(runtimeLock) {
            val r = cachedRecognizer
            if (r != null && stream is ReflectiveOnlineStream) r.isReady(stream) else false
        }
    } catch (t: Throwable) {
        Log.e(TAG, "isReady failed", t)
        false
    }

    fun decode(stream: Any) {
        try {
            synchronized(runtimeLock) {
                val r = cachedRecognizer
                if (r != null && stream is ReflectiveOnlineStream) r.decode(stream)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "decode failed", t)
        }
    }

    fun getResultText(stream: Any): String? = try {
        synchronized(runtimeLock) {
            val r = cachedRecognizer
            if (r != null && stream is ReflectiveOnlineStream) r.getResultText(stream) else null
        }
    } catch (t: Throwable) {
        Log.e(TAG, "getResultText failed", t)
        null
    }

    fun releaseStream(stream: Any?) {
        if (stream == null) return
        try {
            synchronized(runtimeLock) {
                if (stream is ReflectiveOnlineStream) stream.release()
            }
            activeStreams.updateAndGet { if (it > 0) it - 1 else 0 }
            scheduleUnloadIfIdle()
        } catch (t: Throwable) {
            Log.e(TAG, "releaseStream failed", t)
        }
    }

    fun scheduleUnloadIfIdle() {
        if (activeStreams.get() <= 0) {
            if (pendingUnload) {
                scope.launch { tryUnloadIfIdle() }
            } else {
                scheduleAutoUnload(lastKeepAliveMs, lastAlwaysKeep)
            }
        }
    }
}

// 预加载：根据当前配置尝试构建本地 X-ASR 在线识别器，降低首次点击等待
fun preloadXAsrIfConfigured(
    context: android.content.Context,
    prefs: com.brycewg.asrkb.store.Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null,
    suppressToastOnStart: Boolean = false,
    forImmediateUse: Boolean = false
) {
    try {
        val manager = XAsrOnnxManager.getInstance()
        if (!manager.isOnnxAvailable()) {
            LocalAsrCallLogger.recordLoadFailure(
                prefs,
                AsrVendor.XAsr,
                "preload",
                context.getString(com.brycewg.asrkb.R.string.error_local_asr_not_ready)
            )
            return
        }

        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val filesCheck = checkXAsrModelFiles(context, java.io.File(base, "x_asr"))
        val files = (filesCheck as? LocalModelCheck.Ready)?.value
        if (files == null) {
            val msg = localModelErrorMessage(
                context,
                filesCheck,
                com.brycewg.asrkb.R.string.error_x_asr_model_missing
            )
            LocalAsrCallLogger.recordLoadFailure(
                prefs,
                AsrVendor.XAsr,
                "preload",
                msg
            )
            return
        }

        val keepMinutes = prefs.xAsrKeepAliveMinutes
        val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
        val alwaysKeep = keepMinutes < 0

        val numThreads = prefs.xAsrNumThreads
        val key = listOf(
            "x_asr",
            "tokens=${files.tokens.absolutePath}",
            "encoder=${files.encoder.absolutePath}",
            "decoder=${files.decoder.absolutePath}",
            "joiner=${files.joiner.absolutePath}",
            "threads=$numThreads"
        ).joinToString("|")

        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        LocalModelLoadCoordinator.request(key) {
            var loadLog: LocalAsrCallLogger.Session? = null
            val t0 = android.os.SystemClock.uptimeMillis()
            val ok = manager.prepare(
                tokens = files.tokens.absolutePath,
                encoder = files.encoder.absolutePath,
                decoder = files.decoder.absolutePath,
                joiner = files.joiner.absolutePath,
                numThreads = numThreads,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = {
                    loadLog = LocalAsrCallLogger.startLoad(
                        prefs = prefs,
                        vendor = AsrVendor.XAsr,
                        source = "preload"
                    )
                    if (!suppressToastOnStart) {
                        mainHandler.post {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(com.brycewg.asrkb.R.string.x_asr_loading_model),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    onLoadStart?.invoke()
                },
                onLoadDone = {
                    loadLog?.success("loaded=true")
                    loadLog = null
                    onLoadDone?.invoke()
                }
            )
            if (!ok) {
                loadLog?.failure("prepare returned false")
                loadLog = null
            }
            if (ok && !forImmediateUse) {
                val dt = (android.os.SystemClock.uptimeMillis() - t0).coerceAtLeast(0)
                mainHandler.post {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(com.brycewg.asrkb.R.string.sv_model_ready_with_ms, dt),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                manager.scheduleUnloadIfIdle()
            }
        }
    } catch (t: Throwable) {
        Log.e("X-ASRPreload", "preloadXAsrIfConfigured failed", t)
    }
}
