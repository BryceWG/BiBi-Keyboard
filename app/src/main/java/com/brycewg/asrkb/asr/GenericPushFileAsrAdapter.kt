package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * 通用的“推送 PCM” -> 非流式识别 适配器。
 *
 * 用法：传入具体供应商的 File 引擎实例（其实现 PcmBatchRecognizer），本适配器将：
 * - start(): 标记运行中；
 * - appendPcm(): 校验 16k/单声道并累计；启用渐进分段时同步投递已封闭片段；
 * - stop(): onStopped -> 提交尾段，全部完成后交付一次最终结果；
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
    CancelableAsrEngine {

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
    private var chunkChannel: Channel<ByteArray>? = null
    private var progressiveAudioBytes = 0

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
        progressiveRecognizer?.beginExternalProgressiveSession()
        val window = progressiveRecognizer?.progressiveChunkWindow ?: NonStreamingChunkWindow.Online
        progressiveChunker = NonStreamingPcmChunker(
            sampleRate = 16_000,
            minChunkMs = window.minChunkMs,
            maxChunkMs = window.maxChunkMs
        )
        sentenceVadDetector = createNonStreamingSentenceVad(context, 16_000)
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        chunkChannel = channel
        recognitionJob = scope.launch(Dispatchers.IO) {
            try {
                for (pcm in channel) {
                    recognizePcm(pcm, progressive = true)
                    if (results.hasFatalError) break
                }
            } catch (t: Throwable) {
                if (t !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "progressive recognizeFromPcm failed", t)
                    results.onError(recognitionError(t))
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
                channel.cancel()
                releaseSentenceVad()
                progressiveRecognizer?.finishProgressiveResults(results, progressiveAudioBytes)
                    ?: results.finish()
                chunkChannel = null
                recognitionJob = null
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
            if (progressiveAudioBytes == 0) {
                progressiveResults?.onError(context.getString(R.string.error_audio_empty))
            }
            chunker.finish()?.let { chunkChannel?.trySend(it) }
            progressiveChunker = null
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
            progressiveAudioBytes += end - offset
            chunker.append(subPcm, isSpeech).forEach { chunkChannel?.trySend(it) }
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
