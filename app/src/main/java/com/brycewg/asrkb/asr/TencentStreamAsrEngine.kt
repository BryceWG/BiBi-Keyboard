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
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/**
 * 腾讯云 ASR 实时流式识别引擎（WebSocket + HMAC-SHA1 签名）。
 *
 * 归属模块：asr
 *
 * 文档：https://cloud.tencent.com/document/product/1093/48982
 */
internal class TencentStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    httpClient: OkHttpClient? = null,
    private val externalPcmMode: Boolean = false
) : StreamingAsrEngine,
    ExternalPcmConsumer,
    AudioFrameSinkOwner {

    private val running = AtomicBoolean(false)
    private var wssJob: Job? = null
    private var audioJob: Job? = null
    override var audioFrameSink: AudioFrameSink? = null
    private var audioChan: Channel<ByteArray>? = null
    private val wsReady = AtomicBoolean(false)
    private var currentVoiceId: String? = null

    private val client: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val prebuffer = ArrayDeque<ByteArray>()
    private val prebufferLock = Any()
    private val externalVadInputLeveler = VadInputLevelerBranch(sampleRate = sampleRate)
    private val maxPrebufferFrames = (2000 / CHUNK_MILLIS).coerceAtLeast(1)

    override val isRunning: Boolean get() = running.get()

    override fun start() {
        if (running.getAndSet(true)) return
        if (!externalPcmMode) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                listener.onError(context.getString(R.string.error_record_permission_denied))
                running.set(false)
                return
            }
        }
        externalVadInputLeveler.reset()
        synchronized(prebufferLock) { prebuffer.clear() }
        audioChan = Channel(Channel.UNLIMITED)
        if (!externalPcmMode) startAudioStreaming()
        openWebSocket()
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        audioJob?.cancel()
        audioJob = null
        // 只关闭音频通道，让发送协程把剩余帧刷完并自然退出，再由 runSession 收尾发送 end 帧、
        // 等待最终结果并回调 onFinal；直接 cancel(wssJob) 会把 end 帧和最终文本一起吞掉。
        try {
            audioChan?.close()
        } catch (_: Throwable) { }
    }

    private fun openWebSocket() {
        val job = scope.launch(Dispatchers.IO) {
            runSession(listener)
        }
        wssJob = job
        job.invokeOnCompletion {
            if (wssJob === job) wssJob = null
        }
    }

    private fun startAudioStreaming() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val audioManager = AudioCaptureManager(
                context = context,
                sampleRate = sampleRate,
                channelConfig = channelConfig,
                audioFormat = audioFormat,
                chunkMillis = CHUNK_MILLIS,
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
                    if (!isActive || !running.get()) return@collect

                    val leveled = vadInputLeveler.process(audioChunk)

                    try {
                        listener.onAmplitude(leveled.stableAmplitude)
                    } catch (_: Throwable) { }

                    if (vadDetector?.shouldStop(leveled.leveledPcm, leveled.leveledPcm.size) == true) {
                        try {
                            listener.onStopped()
                        } catch (_: Throwable) { }
                        stop()
                        return@collect
                    }

                    enqueuePcm(audioChunk)

                    if (maxDurationLimiter.acceptPcm(audioChunk.size)) {
                        try {
                            listener.onStopped()
                        } catch (_: Throwable) { }
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
                } catch (_: Throwable) { }
            }
        }
    }

    // ========== ExternalPcmConsumer（外部推流） ==========

    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!running.get()) return
        if (sampleRate != this.sampleRate || channels != 1) {
            Log.w(TAG, "ignore frame: sr=$sampleRate ch=$channels")
            return
        }
        val leveled = externalVadInputLeveler.process(pcm)
        try {
            listener.onAmplitude(leveled.stableAmplitude)
        } catch (_: Throwable) { }
        enqueuePcm(pcm.copyOf())
    }

    /**
     * 将一帧 PCM 送入发送队列；WebSocket 未就绪时先进入预缓冲，
     * 就绪后先冲刷预缓冲再发送当前帧（麦克风采集与外部推流共用）。
     */
    private fun enqueuePcm(chunk: ByteArray) {
        var flushed: Array<ByteArray>? = null
        synchronized(prebufferLock) {
            if (!wsReady.get()) {
                prebuffer.addLast(chunk)
                while (prebuffer.size > maxPrebufferFrames) prebuffer.removeFirst()
                return
            }
            if (prebuffer.isNotEmpty()) {
                flushed = prebuffer.toTypedArray()
                prebuffer.clear()
            }
        }
        flushed?.forEach { b -> audioChan?.trySend(b) }
        audioChan?.trySend(chunk)
    }

    fun buildWsUrl(): String? {
        val appId = prefs.tencentAppId
        val secretId = prefs.tencentSecretId
        val secretKey = prefs.tencentSecretKey
        if (appId.isBlank() || secretId.isBlank() || secretKey.isBlank()) return null

        val voiceId = UUID.randomUUID().toString().replace("-", "")
        currentVoiceId = voiceId
        val engineType = prefs.tencentEngineType.ifBlank { "16k_zh" }
        val voiceFormat = 1
        val needvad = if (prefs.tencentVadEnabled) 1 else 0
        val timestamp = System.currentTimeMillis() / 1000
        val expired = timestamp + 86400
        val nonce = (1000000..9999999).random()

        val params = sortedMapOf(
            "engine_model_type" to engineType,
            "expired" to expired.toString(),
            "needvad" to needvad.toString(),
            "nonce" to nonce.toString(),
            "secretid" to secretId,
            "timestamp" to timestamp.toString(),
            "voice_format" to voiceFormat.toString(),
            "voice_id" to voiceId
        )

        val queryString = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val signaturePlain = "asr.cloud.tencent.com/asr/v2/$appId?$queryString"
        val signature = hmacSha1Base64(signaturePlain, secretKey)
        val encodedSignature = URLEncoder.encode(signature, "UTF-8")
        return "wss://asr.cloud.tencent.com/asr/v2/$appId?$queryString&signature=$encodedSignature"
    }

    private suspend fun runSession(listener: StreamingAsrEngine.Listener) {
        val wsUrl = buildWsUrl() ?: run {
            listener.onError(context.getString(R.string.error_missing_tencent_key))
            running.set(false)
            return
        }

        val chan = audioChan ?: run {
            listener.onError("Audio channel not initialized")
            running.set(false)
            return
        }

        val wsClosed = AtomicBoolean(false)
        val accumulatedText = StringBuilder()
        val finalTextDelivered = AtomicBoolean(false)

        val meta = ApiCallLogger.meta(
            category = "ASR",
            vendor = "tencent",
            model = prefs.tencentEngineType.ifBlank { "16k_zh" },
            requestStructure = "WebSocket wss://asr.cloud.tencent.com/asr/v2/<appid>?signed_query, binary PCM frames"
        )
        val request = Request.Builder()
            .url(wsUrl)
            .tag(ApiLogMeta::class.java, meta)
            .build()
        val apiLogSession = ApiCallLogger.startWebSocket(request, meta)

        val wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                wsReady.set(true)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val obj = org.json.JSONObject(text)
                    val code = obj.optInt("code", 0)
                    if (code != 0) {
                        val msg = obj.optString("message", "Unknown error")
                        listener.onError(
                            context.getString(
                                R.string.error_recognize_failed_with_reason,
                                "Tencent stream error $code: $msg"
                            )
                        )
                        wsClosed.set(true)
                        return
                    }

                    val isFinal = obj.optInt("final") == 1
                    val resultObj = obj.optJSONObject("result")

                    if (resultObj != null) {
                        val sliceType = resultObj.optInt("slice_type", -1)
                        val voiceText = resultObj.optString("voice_text_str", "")
                        when (sliceType) {
                            0 -> { }
                            1 -> if (voiceText.isNotEmpty()) listener.onPartial(voiceText)
                            2 -> if (voiceText.isNotEmpty()) {
                                listener.onPartial(voiceText)
                                accumulatedText.append(voiceText)
                            }
                        }
                    }

                    if (isFinal) {
                        if (resultObj != null) {
                            val finalText = resultObj.optString("voice_text_str", "")
                            if (finalText.isNotBlank()) {
                                accumulatedText.setLength(0)
                                accumulatedText.append(finalText)
                            }
                        }
                        if (!finalTextDelivered.getAndSet(true)) {
                            val ft = accumulatedText.toString()
                            if (ft.isNotBlank()) {
                                listener.onFinal(ft)
                            }
                        }
                        wsClosed.set(true)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Parse error: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                wsClosed.set(true)
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                wsClosed.set(true)
                apiLogSession.complete(success = finalTextDelivered.get(), code = code, error = reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                wsClosed.set(true)
                apiLogSession.failure(
                    code = response?.code ?: 0,
                    error = response?.message ?: t.message.orEmpty()
                )
                running.set(false)
                audioJob?.cancel()
                audioJob = null
                if (finalTextDelivered.get()) return
                val ft = accumulatedText.toString()
                if (ft.isNotBlank()) {
                    // 已有部分结果时以最终文本收口，避免再弹错误造成双终态。
                    if (!finalTextDelivered.getAndSet(true)) listener.onFinal(ft)
                    return
                }
                listener.onError(
                    context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
                )
            }
        }

        val ws = client.newWebSocket(request, wsListener)

        val sendJob = scope.launch(Dispatchers.IO) {
            while (isActive && !wsClosed.get()) {
                val pcm = try {
                    chan.receive()
                } catch (_: Exception) {
                    break
                }
                ws.send(pcm.toByteString())
            }
        }

        // 队列里的剩余帧很快刷完；服务端先断开时不能让收尾无限等待。
        withTimeoutOrNull(SEND_DRAIN_TIMEOUT_MS) { sendJob.join() }
        sendJob.cancel()

        // 握手未完成时不会有最终结果，直接收口，避免空等 10s。
        if (wsReady.get() && !wsClosed.get()) {
            wsReady.set(false)
            try {
                ws.send("{\"type\": \"end\"}")
            } catch (_: Exception) { }

            val finalWaitStart = System.currentTimeMillis()
            val finalTimeoutMs = 10_000L
            while (!wsClosed.get() && (System.currentTimeMillis() - finalWaitStart < finalTimeoutMs)) {
                delay(100)
            }
        }

        try {
            ws.close(1000, "client close")
        } catch (_: Exception) { }

        if (!finalTextDelivered.getAndSet(true)) {
            val ft = accumulatedText.toString()
            if (ft.isNotBlank()) {
                listener.onFinal(ft)
            }
        }
        running.set(false)
    }

    companion object {
        private const val TAG = "TencentStreamAsrEngine"
        private const val CHUNK_MILLIS = 200
        private const val SEND_DRAIN_TIMEOUT_MS = 5_000L

        fun hmacSha1Base64(plainText: String, secretKey: String): String {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
            return Base64.encodeToString(mac.doFinal(plainText.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        }

        fun sortedMapOf(vararg pairs: Pair<String, String>): Map<String, String> = linkedMapOf(*pairs).toSortedMap()
    }
}
