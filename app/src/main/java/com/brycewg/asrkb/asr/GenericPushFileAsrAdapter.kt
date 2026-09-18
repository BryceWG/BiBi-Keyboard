package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 通用的“推送 PCM”-> 非流式识别 适配器。
 *
 * 用法：传入具体供应商的 File 引擎实例（其实现 PcmBatchRecognizer），本适配器将：
 * - start(): 标记运行中；
 * - appendPcm(): 校验 16k/单声道并累计；启用渐进分段时按段落盘并同步投递已封闭片段；
 * - stop(): onStopped -> 冲刷段重试并提交尾段，全部完成后交付一次最终结果；
 *
 * 目的：将“推送 PCM”的通用部分抽象出来，避免在每个供应商重复粘贴聚合/回调逻辑。
 */
internal class GenericPushFileAsrAdapter(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    private val recognizer: PcmBatchRecognizer,
    private val applyAudioPreprocess: Boolean = true,
    private val progressiveResults: NonStreamingChunkResultCollector? = null
) : StreamingAsrEngine,
    ExternalPcmConsumer,
    CancelableAsrEngine,
    SessionAudioSourceOwner,
    ProgressiveRetryStatusOwner {

    private class ProgressiveChunk(
        val pcm: ByteArray,
        val segmentIndex: Int,
        val attempt: Int
    )

    companion object {
        private const val TAG = "PushFileAdapter"
    }

    private val running = AtomicBoolean(false)
    private val bos = ByteArrayOutputStream()
    private val vadInputLeveler = VadInputLevelerBranch(sampleRate = 16_000)
    private val progressiveRecognizer = recognizer as? BaseFileAsrEngine
    private var recognitionJob: kotlinx.coroutines.Job? = null
    private var progressiveChunker: NonStreamingPcmChunker? = null
    private var sentenceVadDetector: VadDetector? = null
    private var chunkChannel: Channel<ProgressiveChunk>? = null
    private var progressiveAudioBytes = 0

    // push 路径自持段级落盘存储：段级重试的音频来源，历史音频晋升的绑定目标。
    @Volatile private var currentStore: SessionAudioSegmentStore? = null
    private var retryScheduler: ProgressiveSegmentRetryScheduler? = null
    private val pendingRetrySegments = AtomicInteger(0)

    override val sessionAudioStore: SessionAudioSegmentStore?
        get() = currentStore

    override fun peekPendingRetryCount(): Int = pendingRetrySegments.get()

    override val isRunning: Boolean
        get() = running.get()

    override fun start() {
        if (running.get()) return
        if (recognitionJob?.isCompleted == false) {
            Log.w(TAG, "start ignored while previous Push PCM recognition is still draining")
            return
        }
        running.set(true)
        bos.reset()
        vadInputLeveler.reset()
        val results = progressiveResults ?: return
        results.start()
        progressiveAudioBytes = 0
        currentStore = SessionAudioSegmentStore(context)
        pendingRetrySegments.set(0)
        progressiveRecognizer?.beginExternalProgressiveSession()
        val window = progressiveRecognizer?.progressiveChunkWindow ?: NonStreamingChunkWindow.Online
        progressiveChunker = NonStreamingPcmChunker(
            sampleRate = 16_000,
            minChunkMs = window.minChunkMs,
            maxChunkMs = window.maxChunkMs
        )
        sentenceVadDetector = createNonStreamingSentenceVad(context, 16_000)
        val channel = Channel<ProgressiveChunk>(Channel.UNLIMITED)
        chunkChannel = channel
        retryScheduler = ProgressiveSegmentRetryScheduler(
            scope = scope,
            onRetryDue = { segmentIndex -> enqueueSegmentRetry(channel, segmentIndex) }
        )
        recognitionJob = scope.launch(Dispatchers.IO) {
            try {
                for (chunk in channel) {
                    if (chunk.attempt > 0) {
                        pendingRetrySegments.updateAndGet { (it - 1).coerceAtLeast(0) }
                    }
                    results.beginSegment(chunk.segmentIndex)
                    recognizePcm(chunk.pcm, progressive = true)
                    handleSegmentRecognitionOutcome(chunk)
                    if (results.hasFatalError) break
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "progressive recognizeFromPcm failed", t)
                    // 崩溃路径按会话级错误收口，保持既有语义
                    results.failSession(recognitionError(t))
                }
            } finally {
                if (results.hasFatalError && running.getAndSet(false)) {
                    try {
                        results.onStopped()
                    } catch (t: Throwable) {
                        Log.w(TAG, "notify stopped after progressive error failed", t)
                    }
                } else {
                    running.set(false)
                }
                retryScheduler?.cancel()
                retryScheduler = null
                channel.cancel()
                releaseSentenceVad()
                val store = currentStore
                try {
                    progressiveRecognizer?.finishProgressiveResults(results, progressiveAudioBytes)
                        ?: results.finish()
                } finally {
                    // 终态回调期间保持 currentStore 可见，供历史捕获晋升或丢弃。
                    if (store != null && !store.isTransferred) store.closeAndDelete()
                    currentStore = null
                    chunkChannel = null
                    recognitionJob = null
                }
            }
        }
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        vadInputLeveler.finishDebugSession("stop")
        try {
            (progressiveResults ?: listener).onStopped()
        } catch (t: Throwable) {
            Log.w(TAG, "notify stopped failed", t)
        }
        val chunker = progressiveChunker
        if (chunker != null) {
            progressiveRecognizer?.markProgressiveStopped()
            val tail = chunker.finish()
            if (progressiveAudioBytes == 0 && tail == null) {
                progressiveResults?.onError(context.getString(R.string.error_audio_empty))
            }
            tail?.let(::enqueueClosedChunk)
            progressiveChunker = null
            // 停录即录音结束：冲刷尚未到期的段重试，保证处理循环在通道关闭前收到重试段
            val scheduler = retryScheduler
            if (scheduler != null && scheduler.pendingRetryCount() > 0) {
                DebugLogManager.logBase(
                    context,
                    "asr",
                    "segment_retry_flushed",
                    mapOf("count" to scheduler.pendingRetryCount())
                )
            }
            scheduler?.flushNow()
            chunkChannel?.close()
            releaseSentenceVad()
            return
        }
        val data = bos.toByteArray()
        bos.reset()
        if (data.isEmpty()) {
            try {
                listener.onError(context.getString(R.string.error_audio_empty))
            } catch (
                _: Throwable
            ) {}
            return
        }
        recognitionJob = scope.launch(Dispatchers.IO) {
            try {
                recognizePcm(data)
            } catch (t: Throwable) {
                Log.e(TAG, "recognizeFromPcm failed", t)
                try {
                    listener.onError(recognitionError(t))
                } catch (_: Throwable) { }
            }
        }
    }

    override fun cancel() {
        running.set(false)
        vadInputLeveler.finishDebugSession("cancel")
        bos.reset()
        progressiveChunker = null
        progressiveAudioBytes = 0
        retryScheduler?.cancel()
        pendingRetrySegments.set(0)
        currentStore?.closeAndDelete()
        currentStore = null
        chunkChannel?.cancel()
        chunkChannel = null
        progressiveResults?.cancel()
        releaseSentenceVad()
        try {
            recognitionJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel recognition job failed", t)
        }
    }

    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!isRunning) return
        if (sampleRate != 16000 || channels != 1) {
            Log.w(TAG, "ignore frame: sr=$sampleRate ch=$channels")
            return
        }
        val chunker = progressiveChunker
        if (chunker == null) {
            val leveled = vadInputLeveler.process(pcm)
            try {
                listener.onAmplitude(leveled.stableAmplitude)
            } catch (t: Throwable) {
                Log.w(TAG, "amp cb failed", t)
            }
            try {
                bos.write(pcm)
            } catch (t: Throwable) {
                Log.e(TAG, "buffer write failed", t)
            }
            return
        }
        appendProgressiveFrame(pcm, chunker)
    }

    private fun appendProgressiveFrame(pcm: ByteArray, chunker: NonStreamingPcmChunker) {
        val leveled = vadInputLeveler.process(pcm)
        try {
            listener.onAmplitude(leveled.stableAmplitude)
        } catch (t: Throwable) {
            Log.w(TAG, "amp cb failed", t)
        }
        // 句间 VAD 与分段器按 ≤100ms 子帧推进
        val leveledPcm = leveled.leveledPcm
        forEachSentenceVadSubFrame(pcm, 16_000) { offset, end ->
            val leveledEnd = minOf(end, leveledPcm.size)
            val isSpeech = sentenceVadDetector
                ?.analyzeFrame(leveledPcm.copyOfRange(offset, leveledEnd), leveledEnd - offset)
                ?.isSpeech
                ?: true
            val subPcm = if (offset == 0 && end == pcm.size) pcm else pcm.copyOfRange(offset, end)
            chunker.append(subPcm, isSpeech).forEach(::enqueueClosedChunk)
        }
    }

    /** 封闭段落盘并入识别队列；落盘降级时段号为 -1（该段失败不再可重试）。 */
    private fun enqueueClosedChunk(chunk: ByteArray) {
        progressiveAudioBytes += chunk.size
        val segmentIndex = currentStore?.appendSegment(chunk) ?: -1
        chunkChannel?.trySend(ProgressiveChunk(chunk, segmentIndex, attempt = 0))
    }

    /** 段识别返回后的失败处置：首次失败调度 10s 重试，重试耗尽升级为会话级错误。 */
    private fun handleSegmentRecognitionOutcome(chunk: ProgressiveChunk) {
        val results = progressiveResults ?: return
        if (!results.segmentFailed(chunk.segmentIndex)) return
        if (chunk.attempt == 0 && chunk.segmentIndex >= 0) {
            pendingRetrySegments.incrementAndGet()
            retryScheduler?.scheduleRetry(chunk.segmentIndex)
            DebugLogManager.logBase(
                context,
                "asr",
                "segment_retry_scheduled",
                mapOf("segment" to chunk.segmentIndex)
            )
        } else {
            results.failSegment(chunk.segmentIndex)
            DebugLogManager.logBase(
                context,
                "asr",
                "segment_retry_exhausted",
                mapOf("segment" to chunk.segmentIndex)
            )
        }
    }

    private fun enqueueSegmentRetry(channel: Channel<ProgressiveChunk>, segmentIndex: Int) {
        val results = progressiveResults ?: return
        val pcm = currentStore?.readSegment(segmentIndex)
        if (pcm == null || !channel.trySend(ProgressiveChunk(pcm, segmentIndex, attempt = 1)).isSuccess) {
            // 读取失败或通道已收尾：该段重试丢失，按失败收口
            pendingRetrySegments.updateAndGet { (it - 1).coerceAtLeast(0) }
            results.failSegment(segmentIndex)
        }
    }

    private suspend fun recognizePcm(data: ByteArray, progressive: Boolean = false) {
        // applyAudioPreprocess=false 表示上层（主备 wrapper）已对人声过滤与降噪，
        val denoised = if (applyAudioPreprocess) {
            preprocessForRecognition(data) ?: return
        } else {
            data
        }
        if (progressive) {
            progressiveRecognizer?.recognizeProgressiveChunk(denoised)
                ?: recognizer.recognizeFromPcm(denoised)
        } else {
            recognizer.recognizeFromPcm(denoised)
        }
    }

    /** 返回 null 表示已按空音频交付错误，调用方应直接结束本次识别。 */
    private fun preprocessForRecognition(data: ByteArray): ByteArray? {
        val processed = RecordedAudioVoiceFilter.processIfEnabled(
            context = context,
            prefs = prefs,
            pcm = data,
            sampleRate = 16000,
            chunkMillis = 200
        )
        if (processed.droppedAsEmptyAudio) {
            (progressiveResults ?: listener).onError(
                context.getString(R.string.error_audio_empty_skipped)
            )
            return null
        }
        return OfflineSpeechDenoiserManager.denoiseIfEnabled(
            context = context,
            prefs = prefs,
            pcm = processed.pcm,
            sampleRate = 16000
        )
    }

    private fun releaseSentenceVad() {
        try {
            sentenceVadDetector?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release sentence-boundary VAD", t)
        } finally {
            sentenceVadDetector = null
        }
    }

    private fun recognitionError(t: Throwable): String = context.getString(
        R.string.error_recognize_failed_with_reason,
        t.message ?: ""
    )
}
