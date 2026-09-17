package com.brycewg.asrkb.asr

import android.content.Context
import android.media.AudioFormat
import android.os.SystemClock
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 基础的文件识别 ASR 引擎，封装了麦克风采集、静音判停等通用逻辑，
 * 子类只需实现具体的识别请求即可。
 */
abstract class BaseFileAsrEngine(
    protected val context: Context,
    private val scope: CoroutineScope,
    protected val prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    private val progressiveChunkingEnabled: Boolean = true
) : StreamingAsrEngine,
    AudioFrameSinkOwner {

    companion object {
        private const val TAG = "BaseFileAsrEngine"

        // 编码队列的帧数上限；满队列时采集侧挂起等待，退化为背压而不是静默丢帧。
        private const val ENCODE_QUEUE_CAPACITY = 16
    }

    private val running = AtomicBoolean(false)
    private val chunkResults = createNonStreamingChunkResultCollector(
        context = context,
        listener = listener,
        onRequestDuration = onRequestDuration
    )
    protected val listener: StreamingAsrEngine.Listener = chunkResults
    protected val onRequestDuration: ((Long) -> Unit)? = if (progressiveChunkingEnabled) {
        { _ -> }
    } else {
        onRequestDuration
    }
    protected open val progressiveVendor: AsrVendor? = null
    internal val progressiveChunkWindow: NonStreamingChunkWindow
        get() = NonStreamingChunkWindow.forLocalFile(progressiveVendor != null)
    protected val isProgressiveChunkDecode: Boolean
        get() = progressiveChunkDecode

    @Volatile private var progressiveChunkDecode = false

    @Volatile private var progressiveStoppedAtMs = 0L
    override var audioFrameSink: AudioFrameSink? = null

    @Volatile private var stopRequested: Boolean = false

    @Volatile private var stoppedDelivered: Boolean = false
    private var audioJob: Job? = null
    private var processingJob: Job? = null
    private var segmentChan: Channel<RecordedSegment>? = null
    private var lastSegmentForRetry: RecordedSegment? = null
    private var progressiveRetryPcm: ByteArrayOutputStream? = null

    @Volatile private var discardOnStop: Boolean = false

    @Volatile private var capturePath: String = "pcm"

    protected open val sampleRate: Int = 16000
    protected open val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO
    protected open val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
    protected open val chunkMillis: Int = 200
    protected open val uploadAudioEncodingSpec: UploadAudioEncodingSpec? = null

    private val bytesPerSample = 2 // 16bit mono

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        if (audioJob?.isCompleted == false || processingJob?.isCompleted == false) {
            Log.w(TAG, "start ignored while previous file recognition is still draining")
            return
        }
        if (!ensureReady()) return
        running.set(true)
        stopRequested = false
        stoppedDelivered = false
        discardOnStop = false
        capturePath = "pcm"
        AsrCallLatencyProbe.reset()
        if (progressiveChunkingEnabled) {
            chunkResults.start()
            progressiveRetryPcm = ByteArrayOutputStream()
            progressiveStoppedAtMs = 0L
        }
        // 使用有界队列并在溢出时丢弃最旧的数据，避免内存溢出
        val chan: Channel<RecordedSegment> = if (progressiveChunkingEnabled) {
            Channel(Channel.UNLIMITED)
        } else {
            Channel(
                capacity = 10,
                onBufferOverflow = BufferOverflow.DROP_OLDEST
            )
        }
        segmentChan = chan
        // 顺序消费识别请求，确保结果按段落顺序提交
        processingJob = scope.launch(Dispatchers.IO) {
            try {
                for (seg in chan) {
                    try {
                        if (discardOnStop) {
                            continue
                        }
                        logCallLatency(
                            "t_segment_dequeued",
                            mapOf("bytes" to segmentBytes(seg))
                        )
                        when (seg) {
                            is RecordedSegment.Pcm -> {
                                val preprocessStartedAt = SystemClock.elapsedRealtime()
                                val processed = processPcmForRecognition(seg.pcm) ?: continue
                                val preprocessMs =
                                    (SystemClock.elapsedRealtime() - preprocessStartedAt).coerceAtLeast(0L)
                                // 记录最近一次真正用于识别的片段，供“重试”功能使用
                                lastSegmentForRetry = RecordedSegment.Pcm(processed)
                                val denoiseStartedAt = SystemClock.elapsedRealtime()
                                val denoised = OfflineSpeechDenoiserManager.denoiseIfEnabled(
                                    context = context,
                                    prefs = prefs,
                                    pcm = processed,
                                    sampleRate = sampleRate
                                )
                                val denoiseMs =
                                    (SystemClock.elapsedRealtime() - denoiseStartedAt).coerceAtLeast(0L)
                                logCallLatency(
                                    "t_recognize_enter",
                                    mapOf(
                                        "bytes" to denoised.size,
                                        "preprocess_ms" to preprocessMs,
                                        "denoise_ms" to denoiseMs
                                    )
                                )
                                recognizeProgressiveChunk(denoised)
                            }
                            is RecordedSegment.Encoded -> {
                                lastSegmentForRetry = seg
                                logCallLatency(
                                    "t_recognize_enter",
                                    mapOf(
                                        "bytes" to seg.audio.bytes.size,
                                        "finish_ms" to seg.audio.finishElapsedMs,
                                        "encode_ms" to seg.audio.encodeElapsedMs
                                    )
                                )
                                recognizeEncoded(seg.audio)
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Recognition failed for segment", t)
                        try {
                            listener.onError(
                                context.getString(
                                    R.string.error_recognize_failed_with_reason,
                                    t.message ?: ""
                                )
                            )
                        } catch (e: Throwable) {
                            Log.e(TAG, "Failed to notify recognition error", e)
                        }
                    }
                    if (progressiveChunkingEnabled && chunkResults.hasFatalError) {
                        running.set(false)
                        if (!stoppedDelivered) {
                            markProgressiveStopped()
                            listener.onStopped()
                            stoppedDelivered = true
                        }
                        chan.cancel()
                        audioJob?.cancelAndJoin()
                        break
                    }
                }
            } finally {
                if (progressiveChunkingEnabled) {
                    val retryPcm = progressiveRetryPcm?.toByteArray()
                    if (retryPcm != null && retryPcm.isNotEmpty()) {
                        lastSegmentForRetry = RecordedSegment.Pcm(retryPcm)
                    }
                    progressiveRetryPcm = null
                    if (discardOnStop) {
                        chunkResults.cancel()
                    } else {
                        val audioBytes = retryPcm?.size ?: 0
                        finishProgressiveResults(chunkResults, audioBytes)
                    }
                }
                processingJob = null
                segmentChan = null
            }
        }
        // 持续录音：渐进切段在采集侧完成，编码上传在每段 recognize() 内进行
        audioJob = scope.launch(Dispatchers.IO) {
            try {
                val needsPcmVoiceProcessing =
                    prefs.autoCancelEmptyAudioInputEnabled ||
                        prefs.autoFilterSilentAudioSegmentsEnabled
                val encodingSpec =
                    if (
                        !progressiveChunkingEnabled &&
                        prefs.uploadAudioCompressionEnabled &&
                        !needsPcmVoiceProcessing
                    ) {
                        uploadAudioEncodingSpec
                    } else {
                        null
                    }
                if (encodingSpec == null) {
                    capturePath = "pcm"
                    recordAndEnqueueSegments(chan)
                } else {
                    capturePath = "encoded"
                    recordEncodeAndEnqueueSegments(chan, encodingSpec)
                }
            } finally {
                running.set(false)
                try {
                    DebugLogManager.log("asr", "engine_run_end", mapOf("reason" to "audio_job_end"))
                } catch (
                    _: Throwable
                ) { }
                // 若录音流意外结束且未显式通知 onStopped，则补发一次，确保上层释放音频焦点与路由。
                if (!stoppedDelivered) {
                    try {
                        DebugLogManager.log("asr", "engine_stop_implied")
                    } catch (_: Throwable) { }
                    try {
                        markProgressiveStopped()
                        listener.onStopped()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to notify implied onStopped", t)
                    } finally {
                        stoppedDelivered = true
                    }
                }
                try {
                    chan.close()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to close channel", t)
                }
                audioJob = null
            }
        }
    }

    override fun stop() {
        val wasRunning = running.getAndSet(false)
        if (wasRunning) {
            AsrCallLatencyProbe.markStop(reason = "user_stop", path = capturePath)
        }
        stopRequested = true
        markProgressiveStopped()
        // 主动停止采集：取消录音协程以触发 finally 冲刷尾段并关闭通道
        try {
            audioJob?.cancel()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to cancel audio job on stop", t)
        }
        // 通知 UI 录音已结束（与静音判停一致），便于及时切换到“识别中”
        if (wasRunning) {
            try {
                listener.onStopped()
                stoppedDelivered = true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to notify onStopped on stop", t)
            }
        }
    }

    /**
     * 识别前的准备校验，可在子类中扩展，如检查 API Key 是否配置。
     *
     * 注意：权限检查已由 AudioCaptureManager 处理，此处保留是为了向后兼容。
     */
    protected open fun ensureReady(): Boolean = true

    /**
     * 连续录音并将片段依次投递到 [chan]。
     *
     * 使用 AudioCaptureManager 封装音频采集逻辑，简化代码并提高可维护性。
     * - 段间不停止/重建 AudioRecord，尽量保证采集连续
     * - 仅在静音判停或用户停止时回调 onStopped()，切段不打断 UI 的"正在聆听"
     * - 开启渐进切段时按句间静音在窗口内切开，边录边识别
     */
    private suspend fun recordAndEnqueueSegments(chan: Channel<RecordedSegment>) {
        val audioManager = AudioCaptureManager(
            context = context,
            sampleRate = sampleRate,
            channelConfig = channelConfig,
            audioFormat = audioFormat,
            chunkMillis = chunkMillis,
            audioFrameSinkProvider = { audioFrameSink }
        )

        // 权限检查
        if (!audioManager.hasPermission()) {
            Log.w(TAG, "Missing RECORD_AUDIO permission")
            try {
                listener.onError(context.getString(R.string.error_record_permission_denied))
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to notify permission error", t)
            }
            return
        }

        // VAD 检测器（如果启用）。长按说话模式下由用户松手决定停止，绕过 VAD 自动判停
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
            sampleRate = sampleRate,
            bytesPerSample = bytesPerSample
        )
        val vadInputLeveler = VadInputLevelerBranch(sampleRate = sampleRate)
        var vadLevelerFinishReason = "capture_end"
        val progressiveChunker = if (progressiveChunkingEnabled) {
            NonStreamingPcmChunker(
                sampleRate,
                minChunkMs = progressiveChunkWindow.minChunkMs,
                maxChunkMs = progressiveChunkWindow.maxChunkMs
            )
        } else {
            null
        }
        val sentenceVadDetector = if (progressiveChunkingEnabled) {
            createNonStreamingSentenceVad(context, sampleRate)
        } else {
            null
        }

        val currentSeg = ByteArrayOutputStream()
        val pendingList = java.util.ArrayDeque<ByteArray>()

        fun enqueuePcm(pcm: ByteArray) {
            if (pcm.isEmpty()) return
            progressiveRetryPcm?.write(pcm)
            logUncompressedUploadSegment(pcm)
            while (!pendingList.isEmpty()) {
                val head = pendingList.peekFirst() ?: break
                if (chan.trySend(RecordedSegment.Pcm(head)).isSuccess) {
                    pendingList.removeFirst()
                } else {
                    break
                }
            }
            if (!chan.trySend(RecordedSegment.Pcm(pcm)).isSuccess) pendingList.addLast(pcm)
        }

        try {
            audioManager.startCapture().collect { audioChunk ->
                if (!running.get()) return@collect

                val leveled = vadInputLeveler.process(audioChunk)

                // 计算并发送音频振幅（用于波形动画）
                try {
                    listener.onAmplitude(leveled.stableAmplitude)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to calculate amplitude", t)
                }

                if (progressiveChunker == null) {
                    currentSeg.write(audioChunk)
                } else {
                    // 句间 VAD 与分段器按 ≤100ms 子帧推进，300ms 静音阈值不被 200ms 采集帧放大
                    val leveledPcm = leveled.leveledPcm
                    forEachSentenceVadSubFrame(audioChunk, sampleRate) { offset, end ->
                        val isSpeech = sentenceVadDetector
                            ?.analyzeFrame(leveledPcm.copyOfRange(offset, end), end - offset)
                            ?.isSpeech
                            ?: true
                        val subPcm = if (offset == 0 && end == audioChunk.size) audioChunk else audioChunk.copyOfRange(offset, end)
                        progressiveChunker.append(subPcm, isSpeech).forEach(::enqueuePcm)
                    }
                }

                val stopReason = when {
                    vadDetector?.shouldStop(leveled.leveledPcm, leveled.leveledPcm.size) == true ->
                        "Silence detected, stopping recording"
                    maxDurationLimiter.acceptPcm(audioChunk.size) ->
                        "Max recording duration reached, stopping recording"
                    else -> null
                }

                // 自动停止：结束录音，推送最后一段
                if (stopReason != null) {
                    AsrCallLatencyProbe.markStop(reason = "auto_stop", path = capturePath)
                    vadLevelerFinishReason = stopReason
                    running.set(false)
                    Log.d(TAG, stopReason)
                    try {
                        markProgressiveStopped()
                        listener.onStopped()
                        stoppedDelivered = true
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to notify stopped", t)
                    }

                    val last = progressiveChunker?.finish() ?: currentSeg.toByteArray()
                    if (last.isNotEmpty()) {
                        enqueuePcm(last)
                        // 已投递/入队最后一段后，重置缓冲，避免 finally 重复推送
                        currentSeg.reset()
                        Log.d(TAG, "Final segment enqueued (${last.size} bytes)")
                    }
                    // 取消录音协程，尽快退出采集循环并在 finally 中完成清理
                    try {
                        audioJob?.cancel()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to cancel audio job after silence stop", t)
                    }
                    return@collect
                }

                // 尝试非阻塞地刷出待发送片段（若存在）
                while (!pendingList.isEmpty()) {
                    val head = pendingList.peekFirst() ?: break
                    val r = chan.trySend(RecordedSegment.Pcm(head))
                    if (r.isSuccess) {
                        pendingList.removeFirst()
                        Log.d(TAG, "Pending segment sent (${head.size} bytes)")
                    } else {
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                vadLevelerFinishReason = "capture_cancelled"
                Log.d(TAG, "Audio capture cancelled: ${t.message}")
            } else {
                vadLevelerFinishReason = "capture_error"
                Log.e(TAG, "Audio capture failed", t)
                try {
                    listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to notify audio error", e)
                }
            }
        } finally {
            vadInputLeveler.finishDebugSession(vadLevelerFinishReason)
            logCallLatency("t_capture_exit")
            // 录音结束后，推送任何遗留的待发送段与缓冲
            Log.d(
                TAG,
                "Cleaning up: ${pendingList.size} pending segments, ${currentSeg.size()} bytes in buffer"
            )
            while (!pendingList.isEmpty()) {
                try {
                    val head = pendingList.removeFirst()
                    chan.trySend(RecordedSegment.Pcm(head))
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to send pending segment during cleanup", t)
                    break
                }
            }
            val tail = progressiveChunker?.finish() ?: currentSeg.toByteArray()
            if (tail.isNotEmpty()) {
                try {
                    enqueuePcm(tail)
                    Log.d(TAG, "Final buffer sent (${tail.size} bytes)")
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to send final buffer during cleanup", t)
                }
            }

            // 释放 VAD 资源
            try {
                vadDetector?.release()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to release VAD detector", t)
            }
            try {
                sentenceVadDetector?.release()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to release sentence-boundary VAD", t)
            }
        }
    }

    /**
     * 录音过程中增量编码上传音频，录音结束或切段时直接投递压缩后的文件数据。
     */
    private suspend fun recordEncodeAndEnqueueSegments(
        chan: Channel<RecordedSegment>,
        encodingSpec: UploadAudioEncodingSpec
    ) {
        val audioManager = AudioCaptureManager(
            context = context,
            sampleRate = sampleRate,
            channelConfig = channelConfig,
            audioFormat = audioFormat,
            chunkMillis = chunkMillis,
            audioFrameSinkProvider = { audioFrameSink }
        )

        if (!audioManager.hasPermission()) {
            Log.w(TAG, "Missing RECORD_AUDIO permission")
            try {
                listener.onError(context.getString(R.string.error_record_permission_denied))
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to notify permission error", t)
            }
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
            sampleRate = sampleRate,
            bytesPerSample = bytesPerSample
        )
        val vadInputLeveler = VadInputLevelerBranch(sampleRate = sampleRate)
        var vadLevelerFinishReason = "capture_end"

        // 首个编码器仍在采集启动前创建，让不支持的容器格式沿用既有的向上抛出路径。
        val initialEncoder = createUploadAudioEncodingSession(
            context = context,
            sampleRate = sampleRate,
            spec = encodingSpec
        )
        // 降噪与 MediaCodec 编码放到独立协程：AudioRecord 缓冲区只有约 chunkMillis 的余量，
        // 在采集协程内同步跑这两步会挤占单帧预算并直接丢帧。
        // 该协程挂在引擎 scope 而非采集 Job 之下，以便 stop() 取消采集后仍能冲刷并收尾最后一段。
        val encodeChan = Channel<EncodeRequest>(capacity = ENCODE_QUEUE_CAPACITY)
        val encodeJob = scope.launch(Dispatchers.Default) {
            runEncodeWorker(
                chan = chan,
                encodeChan = encodeChan,
                initialEncoder = initialEncoder
            )
        }

        try {
            audioManager.startCapture().collect { audioChunk ->
                if (!running.get()) return@collect

                val leveled = vadInputLeveler.process(audioChunk)

                try {
                    listener.onAmplitude(leveled.stableAmplitude)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to calculate amplitude", t)
                }

                encodeChan.send(EncodeRequest(audioChunk))

                val stopReason = when {
                    vadDetector?.shouldStop(leveled.leveledPcm, leveled.leveledPcm.size) == true ->
                        "Silence detected, stopping recording"
                    maxDurationLimiter.acceptPcm(audioChunk.size) ->
                        "Max recording duration reached, stopping recording"
                    else -> null
                }

                if (stopReason != null) {
                    AsrCallLatencyProbe.markStop(reason = "auto_stop", path = capturePath)
                    vadLevelerFinishReason = stopReason
                    running.set(false)
                    Log.d(TAG, stopReason)
                    try {
                        markProgressiveStopped()
                        listener.onStopped()
                        stoppedDelivered = true
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to notify stopped", t)
                    }
                    try {
                        audioJob?.cancel()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to cancel audio job after silence stop", t)
                    }
                    return@collect
                }
            }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) {
                vadLevelerFinishReason = "capture_cancelled"
                Log.d(TAG, "Audio capture cancelled: ${t.message}")
            } else {
                vadLevelerFinishReason = "capture_error"
                Log.e(TAG, "Audio capture failed", t)
                try {
                    listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to notify audio error", e)
                }
            }
        } finally {
            vadInputLeveler.finishDebugSession(vadLevelerFinishReason)
            logCallLatency("t_capture_exit")
            Log.d(TAG, "Cleaning up encoded capture")
            // 关闭后 worker 会把队列里剩余帧编码完并冲刷尾段；join 必须不可取消，否则尾段丢失。
            encodeChan.close()
            val drainStartedAt = System.nanoTime()
            withContext(NonCancellable) { encodeJob.join() }
            val drainMs = (System.nanoTime() - drainStartedAt) / 1_000_000
            // drainMs 反映编码是否跟不上采集：明显大于 chunkMillis 说明队列积压。
            logCallLatency(
                "upload_encode_drained",
                mapOf("drain_ms" to drainMs)
            )
            try {
                vadDetector?.release()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to release VAD detector", t)
            }
        }
    }

    /**
     * 消费采集协程投递的 PCM 帧：降噪 → 上传编码 → 录音结束后投递。
     *
     * 编码器与待投递队列都由本协程独占，避免与采集协程竞争同一 [UploadAudioEncodingSession]。
     */
    private suspend fun runEncodeWorker(
        chan: Channel<RecordedSegment>,
        encodeChan: Channel<EncodeRequest>,
        initialEncoder: UploadAudioEncodingSession
    ) {
        val pendingList = java.util.ArrayDeque<RecordedSegment>()
        val encoder: UploadAudioEncodingSession = initialEncoder
        var encodedBytes = 0

        fun flushPending() {
            while (!pendingList.isEmpty()) {
                val head = pendingList.peekFirst() ?: break
                if (chan.trySend(head).isSuccess) {
                    pendingList.removeFirst()
                } else {
                    break
                }
            }
        }

        fun cutSegment() {
            if (encodedBytes <= 0) return
            val audio = encoder.finish()
            logUploadAudioCompression(
                compressed = true,
                sourceBytes = audio.sourceBytes,
                outputBytes = audio.bytes.size,
                durationMs = audio.durationMs,
                elapsedMs = audio.encodeElapsedMs,
                feedElapsedMs = audio.feedElapsedMs,
                finishElapsedMs = audio.finishElapsedMs,
                format = audio.format
            )
            encoder.close()
            encodedBytes = 0
            val segment = RecordedSegment.Encoded(audio)
            flushPending()
            if (!chan.trySend(segment).isSuccess) {
                pendingList.addLast(segment)
            }
        }

        try {
            for (request in encodeChan) {
                val encodedInput = OfflineSpeechDenoiserManager.denoiseIfEnabled(
                    context = context,
                    prefs = prefs,
                    pcm = request.pcm,
                    sampleRate = sampleRate
                )
                encoder.writePcm(encodedInput)
                encodedBytes += request.pcm.size
                flushPending()
            }
            cutSegment()
            flushPending()
        } catch (t: Throwable) {
            // 编码器已不可用：主动废弃队列，让采集侧的 send 立即失败并结束会话。
            encodeChan.cancel()
            running.set(false)
            Log.e(TAG, "Upload audio encoding failed", t)
            DebugLogManager.logError(context, "asr", "upload_encode_failed", t)
            try {
                listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to notify upload encoding error", e)
            }
            try {
                audioJob?.cancel()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to cancel audio job after encoding failure", e)
            }
        } finally {
            try {
                encoder.close()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to close upload audio encoder", t)
            }
        }
    }

    private class EncodeRequest(val pcm: ByteArray)

    /**
     * 将 PCM 格式音频转换为 WAV 格式
     *
     * @param pcm PCM 音频数据
     * @return WAV 格式音频数据
     */
    protected fun pcmToWav(pcm: ByteArray): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val headerSize = 44
        val dataSize = pcm.size
        val totalDataLen = dataSize + 36
        val out = ByteArrayOutputStream(headerSize + dataSize)
        out.write("RIFF".toByteArray())
        out.write(intToBytesLE(totalDataLen))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intToBytesLE(16))
        out.write(shortToBytesLE(1))
        out.write(shortToBytesLE(channels))
        out.write(intToBytesLE(sampleRate))
        out.write(intToBytesLE(byteRate))
        out.write(shortToBytesLE((channels * bitsPerSample / 8)))
        out.write(shortToBytesLE(bitsPerSample))
        out.write("data".toByteArray())
        out.write(intToBytesLE(dataSize))
        out.write(pcm)
        return out.toByteArray()
    }

    protected fun pcmToWavUploadAudio(pcm: ByteArray): UploadAudioData = UploadAudioData(
        bytes = pcmToWav(pcm),
        container = UploadAudioContainer.WAV,
        sampleRate = sampleRate,
        channels = 1,
        sourceBytes = pcm.size,
        durationMs = pcm.size / bytesPerSample * 1_000L / sampleRate,
        encodeElapsedMs = 0L,
        feedElapsedMs = 0L,
        finishElapsedMs = 0L
    )

    /**
     * 将整数转换为小端序字节数组（4字节）
     */
    private fun intToBytesLE(v: Int): ByteArray {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(v)
        return bb.array()
    }

    /**
     * 将短整数转换为小端序字节数组（2字节）
     */
    private fun shortToBytesLE(v: Int): ByteArray {
        val bb = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(v.toShort())
        return bb.array()
    }

    /**
     * 交由子类实现具体的识别流程，如上传音频并解析结果。
     *
     * @param pcm PCM 格式音频数据
     */
    protected abstract suspend fun recognize(pcm: ByteArray)

    /**
     * 子类可覆盖此方法直接上传编码后的音频。
     */
    protected open suspend fun recognizeEncoded(audio: UploadAudioData): Unit = throw UnsupportedOperationException("Encoded upload audio is not supported by this ASR engine")

    private fun processPcmForRecognition(pcm: ByteArray): ByteArray? {
        val result = RecordedAudioVoiceFilter.processIfEnabled(
            context = context,
            prefs = prefs,
            pcm = pcm,
            sampleRate = sampleRate,
            chunkMillis = chunkMillis
        )
        if (result.droppedAsEmptyAudio) {
            Log.d(
                TAG,
                "Dropped empty audio before recognition (${result.originalDurationMs}ms)"
            )
            try {
                listener.onError(context.getString(R.string.error_audio_empty_skipped))
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to notify empty audio", t)
            }
            return null
        }
        if (result.pcm.size != pcm.size) {
            Log.d(
                TAG,
                "Filtered silent audio: ${result.originalDurationMs}ms -> ${result.outputDurationMs}ms"
            )
        }
        return result.pcm
    }

    private fun logUncompressedUploadSegment(pcm: ByteArray) {
        logUploadAudioCompression(
            compressed = false,
            sourceBytes = pcm.size,
            outputBytes = pcm.size,
            durationMs = pcm.size / bytesPerSample * 1_000L / sampleRate,
            elapsedMs = 0L,
            feedElapsedMs = 0L,
            finishElapsedMs = 0L,
            format = "pcm"
        )
    }

    /**
     * 标记当前会话在停止时丢弃所有待处理片段，避免上传/识别。
     */
    fun markDiscardOnStop() {
        discardOnStop = true
        lastSegmentForRetry = null
        progressiveRetryPcm = null
        if (progressiveChunkingEnabled) chunkResults.cancel()
        try {
            processingJob?.cancel()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to cancel processing job on discard", t)
        }
    }

    /**
     * 是否存在可用于重试的片段
     */
    fun hasRetryableSegment(): Boolean = lastSegmentForRetry != null

    /**
     * 对最近一次片段发起重新识别（不重新录音）。
     * 该操作不会修改 running 状态；仅触发一次识别请求。
     */
    fun retryLastSegment() {
        val data = lastSegmentForRetry
        if (data == null) {
            Log.w(TAG, "retryLastSegment: no segment available")
            return
        }
        scope.launch(Dispatchers.IO) {
            if (progressiveChunkingEnabled) {
                chunkResults.start()
                chunkResults.onStopped()
                progressiveStoppedAtMs = SystemClock.uptimeMillis()
            }
            try {
                when (data) {
                    is RecordedSegment.Pcm -> {
                        val chunks = if (progressiveChunkingEnabled) {
                            splitLocalOfflinePcm16WithVad(
                                context = context,
                                prefs = prefs,
                                pcm = data.pcm,
                                sampleRate = sampleRate,
                                window = progressiveChunkWindow
                            )
                        } else {
                            listOf(data.pcm)
                        }
                        for (chunk in chunks) {
                            val pcm = if (progressiveChunkingEnabled) {
                                processPcmForRecognition(chunk) ?: continue
                            } else {
                                chunk
                            }
                            val denoised = OfflineSpeechDenoiserManager.denoiseIfEnabled(
                                context = context,
                                prefs = prefs,
                                pcm = pcm,
                                sampleRate = sampleRate
                            )
                            recognizeProgressiveChunk(denoised)
                            if (progressiveChunkingEnabled && chunkResults.hasFatalError) break
                        }
                    }
                    is RecordedSegment.Encoded -> recognizeEncoded(data.audio)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "retryLastSegment recognize failed", t)
                try {
                    listener.onError(
                        context.getString(
                            R.string.error_recognize_failed_with_reason,
                            t.message ?: ""
                        )
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to notify recognition error (retry)", e)
                }
            } finally {
                if (progressiveChunkingEnabled) {
                    val audioBytes = (data as? RecordedSegment.Pcm)?.pcm?.size ?: 0
                    finishProgressiveResults(chunkResults, audioBytes)
                }
            }
        }
    }

    internal suspend fun recognizeProgressiveChunk(pcm: ByteArray) {
        progressiveChunkDecode = true
        try {
            recognize(pcm)
        } finally {
            progressiveChunkDecode = false
        }
    }

    internal fun markProgressiveStopped() {
        if (progressiveChunkingEnabled && progressiveStoppedAtMs == 0L) {
            progressiveStoppedAtMs = SystemClock.uptimeMillis()
        }
    }

    internal fun beginExternalProgressiveSession() {
        progressiveStoppedAtMs = 0L
    }

    internal suspend fun finalizeProgressiveResult(text: String): String = try {
        finalizeCombinedProgressiveText(text)
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to finalize combined progressive result", t)
        text
    }

    internal suspend fun finishProgressiveResults(
        results: NonStreamingChunkResultCollector,
        audioBytes: Int
    ) {
        results.finish(
            transformFinal = { finalizeProgressiveResult(it) },
            onFinalized = { logProgressiveSuccess(it, audioBytes) },
            onError = { logProgressiveFailure(it, audioBytes) }
        )
    }

    internal fun logProgressiveSuccess(text: String, audioBytes: Int) {
        val vendor = progressiveVendor ?: return
        progressiveLog(vendor, audioBytes).successWithText(text)
    }

    internal fun logProgressiveFailure(message: String, audioBytes: Int) {
        val vendor = progressiveVendor ?: return
        progressiveLog(vendor, audioBytes).failure(message)
    }

    protected open suspend fun finalizeCombinedProgressiveText(text: String): String = text

    private fun progressiveLog(vendor: AsrVendor, audioBytes: Int): LocalAsrCallLogger.Session = LocalAsrCallLogger.startInference(
        prefs = prefs,
        vendor = vendor,
        source = "file",
        audioBytes = audioBytes,
        sampleRate = sampleRate,
        startedMs = progressiveStoppedAtMs.takeIf { it > 0L } ?: SystemClock.uptimeMillis()
    )

    private fun segmentBytes(segment: RecordedSegment): Int = when (segment) {
        is RecordedSegment.Pcm -> segment.pcm.size
        is RecordedSegment.Encoded -> segment.audio.bytes.size
    }

    private fun logCallLatency(event: String, data: Map<String, Any?> = emptyMap()) {
        AsrCallLatencyProbe.log(event, data)
    }

    private sealed interface RecordedSegment {
        data class Pcm(val pcm: ByteArray) : RecordedSegment
        data class Encoded(val audio: UploadAudioData) : RecordedSegment
    }
}
