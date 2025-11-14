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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ElevenLabs Realtime Streaming ASR Engine.
 *
 * Protocol highlights:
 * - Connects via WebSocket (Realtime endpoint) with xi-api-key header.
 * - Audio is sent as Base64 encoded PCM chunks wrapped in JSON payloads.
 * - Service streams partial/committed transcripts back as JSON messages.
 */
class ElevenLabsStreamAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    private val externalPcmMode: Boolean = false
) : StreamingAsrEngine, ExternalPcmConsumer {

    companion object {
        private const val TAG = "ElevenLabsStreamAsr"
        private const val WS_HOST = "api.elevenlabs.io"
        private const val MODEL_ID = "scribe_v2_realtime"
    }

    private val httpClient = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val running = AtomicBoolean(false)
    private val wsReady = AtomicBoolean(false)
    private val closingByUser = AtomicBoolean(false)
    private val stopNotified = AtomicBoolean(false)
    private val finalEmitted = AtomicBoolean(false)
    private var ws: WebSocket? = null
    private var audioJob: Job? = null
    private val prebuffer = ArrayDeque<ByteArray>()
    private val prebufferLock = Any()
    private val finalText = StringBuilder()

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        if (!externalPcmMode && !hasRecordPermission()) {
            listener.onError(context.getString(R.string.error_record_permission_denied))
            return
        }
        if (prefs.elevenApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_eleven_key))
            return
        }
        running.set(true)
        wsReady.set(false)
        closingByUser.set(false)
        stopNotified.set(false)
        finalEmitted.set(false)
        finalText.setLength(0)
        synchronized(prebufferLock) { prebuffer.clear() }

        openWebSocket(startAudio = !externalPcmMode)
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        closingByUser.set(true)
        wsReady.set(false)
        notifyStoppedIfNeeded()
        audioJob?.cancel()
        audioJob = null
        scope.launch(Dispatchers.IO) {
            try {
                sendCommitChunk()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to send commit chunk on stop", t)
            }
            delay(150)
            try {
                ws?.close(1000, "stop")
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to close websocket", t)
            } finally {
                ws = null
            }
        }
    }

    private fun openWebSocket(startAudio: Boolean) {
        if (startAudio) startCaptureAndStream()

        val urlBuilder = HttpUrl.Builder()
            .scheme("wss")
            .host(WS_HOST)
            .addPathSegments("v1/speech-to-text/realtime")
            .addQueryParameter("model_id", MODEL_ID)
            .addQueryParameter("audio_format", "pcm_16000")
            .addQueryParameter("sample_rate", sampleRate.toString())
            .addQueryParameter("commit_strategy", "vad")

        val lang = prefs.elevenLanguageCode.trim()
        if (lang.isNotEmpty()) {
            urlBuilder.addQueryParameter("language_code", lang)
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .addHeader("xi-api-key", prefs.elevenApiKey.trim())
            .build()

        ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened: $response")
                wsReady.set(true)
                flushPrebuffer()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: code=$code reason=$reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: code=$code reason=$reason")
                ws = null
                running.set(false)
                audioJob?.cancel()
                audioJob = null
                if (closingByUser.get() || !running.get()) {
                    emitFinalIfNeeded("closed")
                } else if (!finalEmitted.get()) {
                    listener.onError(
                        context.getString(
                            R.string.error_recognize_failed_with_reason,
                            "connection closed"
                        )
                    )
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure: ${t.message}", t)
                ws = null
                audioJob?.cancel()
                audioJob = null
                if (closingByUser.get()) {
                    emitFinalIfNeeded("failure_after_stop")
                    return
                }
                val detail = response?.message ?: t.message.orEmpty()
                listener.onError(
                    context.getString(R.string.error_recognize_failed_with_reason, detail)
                )
                running.set(false)
            }
        })
    }

    private fun startCaptureAndStream() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val chunkMillis = 200
            val audioManager = AudioCaptureManager(
                context = context,
                sampleRate = sampleRate,
                channelConfig = channelConfig,
                audioFormat = audioFormat,
                chunkMillis = chunkMillis
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
            } else null
            val maxFrames = (2000 / chunkMillis).coerceAtLeast(1)

            try {
                audioManager.startCapture().collect { chunk ->
                    if (!isActive || !running.get()) return@collect

                    try {
                        val amplitude = calculateNormalizedAmplitude(chunk)
                        listener.onAmplitude(amplitude)
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
                        sendAudioChunk(chunk, commit = false)
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    Log.d(TAG, "Audio capture cancelled: ${t.message}")
                } else {
                    Log.e(TAG, "Audio capture error: ${t.message}", t)
                    listener.onError(
                        context.getString(
                            R.string.error_audio_error,
                            t.message ?: ""
                        )
                    )
                    stop()
                }
            }
        }
    }

    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!running.get()) return
        if (sampleRate != this.sampleRate || channels != 1) return
        try {
            val amplitude = calculateNormalizedAmplitude(pcm)
            listener.onAmplitude(amplitude)
        } catch (_: Throwable) {
        }
        if (!wsReady.get()) {
            synchronized(prebufferLock) { prebuffer.addLast(pcm.copyOf()) }
        } else {
            flushPrebuffer()
            sendAudioChunk(pcm, commit = false)
        }
    }

    private fun sendAudioChunk(data: ByteArray, commit: Boolean) {
        val socket = ws ?: return
        val payload = JSONObject().apply {
            put("message_type", "input_audio_chunk")
            put("audio_base_64", Base64.encodeToString(data, Base64.NO_WRAP))
            put("commit", commit)
            put("sample_rate", sampleRate)
        }
        try {
            socket.send(payload.toString())
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to send audio chunk", t)
        }
    }

    private fun sendCommitChunk() {
        val socket = ws ?: return
        val payload = JSONObject().apply {
            put("message_type", "input_audio_chunk")
            put("audio_base_64", "")
            put("commit", true)
            put("sample_rate", sampleRate)
        }
        try {
            socket.send(payload.toString())
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send commit message", t)
        }
    }

    private fun handleMessage(text: String) {
        try {
            val obj = JSONObject(text)
            when (obj.optString("message_type")) {
                "session_started" -> {
                    Log.d(TAG, "Session started: id=${obj.optString("session_id")}")
                }
                "partial_transcript" -> {
                    val preview = obj.optString("text")
                    if (preview.isNotEmpty() && running.get()) {
                        val combined = (finalText.toString() + preview)
                        listener.onPartial(combined)
                    }
                }
                "committed_transcript", "committed_transcript_with_timestamps" -> {
                    val committed = obj.optString("text")
                    if (committed.isNotEmpty()) {
                        finalText.append(committed)
                        if (running.get()) {
                            listener.onPartial(finalText.toString())
                        }
                    }
                }
                "error", "auth_error" -> {
                    val err = obj.optString("error").ifBlank { "unknown" }
                    listener.onError(
                        context.getString(R.string.error_recognize_failed_with_reason, err)
                    )
                    stop()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to handle ElevenLabs message: $text", t)
        }
    }

    private fun flushPrebuffer() {
        if (!wsReady.get()) return
        var frames: Array<ByteArray>? = null
        synchronized(prebufferLock) {
            if (prebuffer.isNotEmpty()) {
                frames = prebuffer.toTypedArray()
                prebuffer.clear()
            }
        }
        frames?.forEach { sendAudioChunk(it, commit = false) }
    }

    private fun emitFinalIfNeeded(reason: String) {
        if (finalEmitted.get()) return
        finalEmitted.set(true)
        val text = finalText.toString().trim()
        Log.d(TAG, "Emitting final result ($reason), length=${text.length}")
        try {
            listener.onFinal(text)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to deliver final result", t)
        }
    }

    private fun notifyStoppedIfNeeded() {
        if (stopNotified.get()) return
        stopNotified.set(true)
        try {
            listener.onStopped()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to notify onStopped", t)
        }
    }

    private fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }
}
