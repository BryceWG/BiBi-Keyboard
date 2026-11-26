package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.util.Log
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusEncoder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.WebSocket

/**
 * 16kHz 单声道流式 Opus ASR 引擎共享基类。
 * 统一管理麦克风采集、外部推流（ExternalPcmConsumer）、预缓冲、取消与协程生命周期。
 *
 * 归属模块：asr
 */
internal abstract class BaseOpusStreamAsrEngine(
    protected val context: Context,
    protected val scope: CoroutineScope,
    protected val prefs: Prefs,
    protected val listener: StreamingAsrEngine.Listener,
    private val externalPcmMode: Boolean,
    private val chunkMillis: Int,
    maxPrebufferMs: Int
) : StreamingAsrEngine,
    ExternalPcmConsumer,
    AudioFrameSinkOwner,
    CancelableAsrEngine {

    override var audioFrameSink: AudioFrameSink? = null

    protected abstract val logTag: String

    protected val sampleRate: Int = 16000
    protected val running = AtomicBoolean(false)
    protected val cancelled = AtomicBoolean(false)
    protected val ready = AtomicBoolean(false)

    @Volatile
    protected var activeWs: WebSocket? = null

    private var audioJob: Job? = null
    private var sessionJob: Job? = null
    private var audioChan: Channel<ByteArray>? = null
    private val prebuffer = ArrayDeque<ByteArray>()
    private val prebufferLock = Any()
    private val externalVadInputLeveler = VadInputLevelerBranch(sampleRate = sampleRate)
    private val maxPrebufferFrames = (maxPrebufferMs / chunkMillis).coerceAtLeast(1)

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.getAndSet(true)) return
        cancelled.set(false)
        ready.set(false)
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
        val chan = Channel<ByteArray>(Channel.UNLIMITED)
        audioChan = chan
        if (!externalPcmMode) {
            startAudioStreaming()
        }
        val job = scope.launch(Dispatchers.IO) {
            runSession(chan)
        }
        sessionJob = job
        job.invokeOnCompletion {
            if (sessionJob === job) sessionJob = null
        }
    }

    override fun stop() {
        if (!running.getAndSet(false)) return
        audioJob?.cancel()
        audioJob = null
        try {
            audioChan?.close()
        } catch (_: Throwable) { }
    }

    override fun cancel() {
        cancelled.set(true)
        running.set(false)
        ready.set(false)
        audioJob?.cancel()
        audioJob = null
        try {
            audioChan?.close()
        } catch (_: Throwable) { }
        try {
            activeWs?.cancel()
        } catch (_: Throwable) { }
        activeWs = null
        sessionJob?.cancel()
        sessionJob = null
    }

    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!running.get() || cancelled.get()) return
        if (sampleRate != this.sampleRate || channels != 1) {
            Log.w(logTag, "ignore frame: sr=$sampleRate ch=$channels")
            return
        }
        val leveled = externalVadInputLeveler.process(pcm)
        try {
            listener.onAmplitude(leveled.stableAmplitude)
        } catch (_: Throwable) { }
        enqueuePcm(pcm.copyOf())
    }

    protected fun cancelAudioCapture() {
        audioJob?.cancel()
        audioJob = null
    }

    protected fun drainPrebuffer(): Array<ByteArray>? = synchronized(prebufferLock) {
        if (prebuffer.isEmpty()) {
            null
        } else {
            prebuffer.toTypedArray().also { prebuffer.clear() }
        }
    }

    protected abstract suspend fun runSession(audioChan: Channel<ByteArray>)

    private fun startAudioStreaming() {
        audioJob?.cancel()
        audioJob = launchStreamingMicCapture(
            context = context,
            scope = scope,
            prefs = prefs,
            listener = listener,
            tag = logTag,
            sampleRate = sampleRate,
            chunkMillis = chunkMillis,
            audioFrameSinkProvider = { audioFrameSink },
            isRunning = { running.get() && !cancelled.get() },
            onStopRequested = { stop() },
            onAudioChunk = { enqueuePcm(it) }
        )
    }

    private fun enqueuePcm(chunk: ByteArray) {
        var flushed: Array<ByteArray>? = null
        synchronized(prebufferLock) {
            if (!ready.get()) {
                prebuffer.addLast(chunk)
                while (prebuffer.size > maxPrebufferFrames) prebuffer.removeFirst()
                return
            }
            if (prebuffer.isNotEmpty()) {
                flushed = prebuffer.toTypedArray()
                prebuffer.clear()
            }
        }
        flushed?.forEach { audioChan?.trySend(it) }
        audioChan?.trySend(chunk)
    }
}

/**
 * 16kHz 单声道原始 Opus 帧编码器（基于纯 Java Concentus）。
 * 供豆包输入法（10ms 帧）与微信输入法（20ms 帧）流式 ASR 共用。
 */
internal class RawOpusFrameEncoder(
    sampleRate: Int = 16000,
    channels: Int = 1,
    application: OpusApplication,
    bitrate: Int,
    useVbr: Boolean,
    complexity: Int? = null,
    private val frameSamples: Int
) {
    private val frameBytes = frameSamples * channels * 2
    private val pcmBuffer = ByteArrayOutputStream()
    private val shortBuf = ShortArray(frameSamples * channels)
    private val outBuf = ByteArray(MAX_OPUS_PACKET_BYTES)
    private val encoder = OpusEncoder(sampleRate, channels, application).apply {
        setBitrate(bitrate)
        setUseVBR(useVbr)
        if (complexity != null) {
            setComplexity(complexity)
        }
    }

    fun feed(pcm: ByteArray, onPacket: (ByteArray) -> Unit) {
        if (pcm.isEmpty()) return
        pcmBuffer.write(pcm)
        emitFullFrames(onPacket)
    }

    fun flush(onPacket: (ByteArray) -> Unit) {
        val remaining = pcmBuffer.toByteArray()
        if (remaining.isEmpty()) return
        pcmBuffer.reset()
        val padded = if (remaining.size < frameBytes) {
            remaining.copyOf(frameBytes)
        } else {
            remaining
        }
        var offset = 0
        while (offset + frameBytes <= padded.size) {
            onPacket(encodeFrame(padded, offset))
            offset += frameBytes
        }
    }

    private fun emitFullFrames(onPacket: (ByteArray) -> Unit) {
        val data = pcmBuffer.toByteArray()
        if (data.size < frameBytes) return
        var offset = 0
        while (offset + frameBytes <= data.size) {
            onPacket(encodeFrame(data, offset))
            offset += frameBytes
        }
        pcmBuffer.reset()
        if (offset < data.size) {
            pcmBuffer.write(data, offset, data.size - offset)
        }
    }

    private fun encodeFrame(pcm: ByteArray, offset: Int): ByteArray {
        ByteBuffer.wrap(pcm, offset, frameBytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .get(shortBuf)
        val encodedBytes = encoder.encode(shortBuf, 0, frameSamples, outBuf, 0, outBuf.size)
        if (encodedBytes < 0) {
            error("Opus encode failed with code $encodedBytes")
        }
        return outBuf.copyOf(encodedBytes)
    }

    companion object {
        private const val MAX_OPUS_PACKET_BYTES = 4000
    }
}

/**
 * 轻量 Protobuf 编解码工具（无需引入完整 protobuf-javalite 运行时）。
 */
internal object LiteProtobuf {
    fun encodeVarint(value: Long): ByteArray {
        var v = value
        val out = ByteArrayOutputStream()
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.write(v.toInt())
                return out.toByteArray()
            }
            out.write((v.toInt() and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    fun decodeVarint(data: ByteArray, startIndex: Int): Pair<Long, Int> {
        var index = startIndex
        var shift = 0
        var value = 0L
        while (true) {
            require(index < data.size) { "Unexpected end of protobuf varint" }
            val b = data[index].toInt() and 0xFF
            index++
            value = value or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) {
                return value to index
            }
            shift += 7
            require(shift < 70) { "Protobuf varint too long" }
        }
    }

    fun encodeBytes(fieldNumber: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeVarint(((fieldNumber.toLong()) shl 3) or 2L))
        out.write(encodeVarint(value.size.toLong()))
        out.write(value)
        return out.toByteArray()
    }

    fun encodeString(fieldNumber: Int, value: String): ByteArray = encodeBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))

    fun encodeInt(fieldNumber: Int, value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(encodeVarint((fieldNumber.toLong()) shl 3))
        out.write(encodeVarint(value))
        return out.toByteArray()
    }

    fun parseFields(data: ByteArray): List<Pair<Int, Any>> {
        val out = ArrayList<Pair<Int, Any>>()
        var index = 0
        while (index < data.size) {
            val (tag, nextAfterTag) = decodeVarint(data, index)
            index = nextAfterTag
            val fieldNumber = (tag ushr 3).toInt()
            val wireType = (tag and 0x07L).toInt()
            val value: Any = when (wireType) {
                0 -> {
                    val (v, next) = decodeVarint(data, index)
                    index = next
                    v
                }
                1 -> {
                    require(index + 8 <= data.size) { "Truncated fixed64 field" }
                    val slice = data.copyOfRange(index, index + 8)
                    index += 8
                    slice
                }
                2 -> {
                    val (lenLong, next) = decodeVarint(data, index)
                    val len = lenLong.toInt()
                    require(len >= 0 && next + len <= data.size) { "Truncated length-delimited field" }
                    val slice = data.copyOfRange(next, next + len)
                    index = next + len
                    slice
                }
                5 -> {
                    require(index + 4 <= data.size) { "Truncated fixed32 field" }
                    val slice = data.copyOfRange(index, index + 4)
                    index += 4
                    slice
                }
                else -> error("Unsupported protobuf wire type: $wireType")
            }
            out.add(fieldNumber to value)
        }
        return out
    }

    fun lastString(fields: List<Pair<Int, Any>>, fieldNumber: Int): String {
        val raw = fields.lastOrNull { it.first == fieldNumber }?.second ?: return ""
        return when (raw) {
            is ByteArray -> String(raw, Charsets.UTF_8)
            is Long -> raw.toString()
            else -> ""
        }
    }

    fun lastInt(fields: List<Pair<Int, Any>>, fieldNumber: Int): Long {
        val raw = fields.lastOrNull { it.first == fieldNumber }?.second ?: return 0L
        return raw as? Long ?: 0L
    }

    fun lastBytes(fields: List<Pair<Int, Any>>, fieldNumber: Int): ByteArray? = fields.lastOrNull { it.first == fieldNumber }?.second as? ByteArray
}

/**
 * 流式引擎通用的麦克风采集循环（含权限检查、振幅回调、VAD 自动判停与最长录音限制）。
 */
internal fun launchStreamingMicCapture(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    tag: String,
    sampleRate: Int = 16000,
    chunkMillis: Int = 100,
    audioFrameSinkProvider: () -> AudioFrameSink?,
    isRunning: () -> Boolean,
    onStopRequested: () -> Unit,
    onAudioChunk: (ByteArray) -> Unit
): Job = scope.launch(Dispatchers.IO) {
    val audioManager = AudioCaptureManager(
        context = context,
        sampleRate = sampleRate,
        channelConfig = AudioFormat.CHANNEL_IN_MONO,
        audioFormat = AudioFormat.ENCODING_PCM_16BIT,
        chunkMillis = chunkMillis,
        audioFrameSinkProvider = audioFrameSinkProvider
    )

    if (!audioManager.hasPermission()) {
        Log.e(tag, "Missing RECORD_AUDIO permission")
        listener.onError(context.getString(R.string.error_record_permission_denied))
        onStopRequested()
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
            if (!isActive || !isRunning()) return@collect

            val leveled = vadInputLeveler.process(audioChunk)
            try {
                listener.onAmplitude(leveled.stableAmplitude)
            } catch (_: Throwable) { }

            if (vadDetector?.shouldStop(audioChunk, audioChunk.size) == true) {
                try {
                    listener.onStopped()
                } catch (_: Throwable) { }
                onStopRequested()
                return@collect
            }

            onAudioChunk(audioChunk)

            if (maxDurationLimiter.acceptPcm(audioChunk.size)) {
                try {
                    listener.onStopped()
                } catch (_: Throwable) { }
                onStopRequested()
                return@collect
            }
        }
    } catch (t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) {
            Log.d(tag, "Audio streaming cancelled: ${t.message}")
        } else {
            Log.e(tag, "Audio streaming failed: ${t.message}", t)
            listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
        }
    } finally {
        try {
            vadDetector?.release()
        } catch (_: Throwable) { }
    }
}
