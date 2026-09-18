/** 非流式识别的实时 PCM 分段与分段结果收口。 */
package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.R
import java.io.ByteArrayOutputStream

internal const val NON_STREAMING_MIN_CHUNK_MS = 15_000
internal const val NON_STREAMING_MAX_CHUNK_MS = 24_000
internal const val ONLINE_NON_STREAMING_MIN_CHUNK_MS = 45_000
internal const val ONLINE_NON_STREAMING_MAX_CHUNK_MS = 60_000
internal const val NON_STREAMING_MIN_SILENCE_MS = 300

/**
 * 句间静音检测的子帧步长，必须小于 [NON_STREAMING_MIN_SILENCE_MS]：
 */
internal const val NON_STREAMING_SENTENCE_VAD_FRAME_MS = 100

internal data class NonStreamingChunkWindow(
    val minChunkMs: Int,
    val maxChunkMs: Int
) {
    init {
        require(minChunkMs > 0 && maxChunkMs >= minChunkMs)
    }

    companion object {
        val Local = NonStreamingChunkWindow(
            minChunkMs = NON_STREAMING_MIN_CHUNK_MS,
            maxChunkMs = NON_STREAMING_MAX_CHUNK_MS
        )
        val Online = NonStreamingChunkWindow(
            minChunkMs = ONLINE_NON_STREAMING_MIN_CHUNK_MS,
            maxChunkMs = ONLINE_NON_STREAMING_MAX_CHUNK_MS
        )

        /**
         * 本地/在线 File 引擎共用这一处窗口选择逻辑
         */
        fun forLocalFile(localFile: Boolean): NonStreamingChunkWindow = if (localFile) Local else Online
    }
}

/**
 * 把一帧采集 PCM 按 [NON_STREAMING_SENTENCE_VAD_FRAME_MS] 子帧切块迭代，
 * 供句间 VAD 与分段器以子帧粒度推进；边界保持 PCM16 对齐。
 */
internal inline fun forEachSentenceVadSubFrame(
    pcm: ByteArray,
    sampleRate: Int,
    block: (offset: Int, end: Int) -> Unit
) {
    if (pcm.isEmpty()) return
    val step = (sampleRate.toLong() * 2L * NON_STREAMING_SENTENCE_VAD_FRAME_MS / 1_000L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
        .let { if (it >= 2) it and -2 else pcm.size }
    if (step >= pcm.size) {
        block(0, pcm.size)
        return
    }
    var offset = 0
    while (offset < pcm.size) {
        val end = minOf(offset + step, pcm.size)
        block(offset, end)
        offset = end
    }
}

internal fun joinNonStreamingChunkTexts(texts: List<String>): String = buildString {
    for (text in texts) {
        val next = text.trim()
        if (next.isEmpty()) continue
        val previous = lastOrNull()
        if (previous != null && needsChunkBoundarySpace(previous, next.first())) append(' ')
        append(next)
    }
}

private fun needsChunkBoundarySpace(previous: Char, next: Char): Boolean = next.isAsciiLetterOrDigit() && (previous.isAsciiLetterOrDigit() || previous in ",.!?;:")

private fun Char.isAsciiLetterOrDigit(): Boolean = code < 128 && isLetterOrDigit()

internal fun createNonStreamingChunkResultCollector(
    context: Context,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)?
): NonStreamingChunkResultCollector = NonStreamingChunkResultCollector(
    delegate = listener,
    emptyResultMessage = context.getString(R.string.error_asr_empty_result),
    ignorableEmptyErrors = setOf(
        context.getString(R.string.error_asr_empty_result),
        context.getString(R.string.error_audio_empty_skipped)
    ),
    requestDurationCallback = onRequestDuration
)

internal fun createNonStreamingSentenceVad(context: Context, sampleRate: Int): VadDetector? = try {
    VadDetector(
        context = context,
        sampleRate = sampleRate,
        windowMs = NON_STREAMING_MIN_SILENCE_MS,
        tuning = VadTuning.ConservativeFilter
    ).also { detector ->
        if (!detector.isAvailable()) detector.release()
    }.takeIf(VadDetector::isAvailable)
} catch (t: Throwable) {
    Log.w("NonStreamingChunking", "Failed to create sentence-boundary VAD", t)
    null
}

internal class NonStreamingPcmChunker(
    sampleRate: Int,
    minChunkMs: Int = NON_STREAMING_MIN_CHUNK_MS,
    maxChunkMs: Int = NON_STREAMING_MAX_CHUNK_MS,
    minSilenceMs: Int = NON_STREAMING_MIN_SILENCE_MS
) {
    private val minBytes = pcmBytes(sampleRate, minChunkMs)
    private val maxBytes = pcmBytes(sampleRate, maxChunkMs)
    private val minSilenceBytes = pcmBytes(sampleRate, minSilenceMs)
    private val buffer = ByteArrayOutputStream(maxBytes)
    private var silenceStart: Int? = null

    init {
        require(sampleRate > 0)
        require(minChunkMs > 0 && maxChunkMs >= minChunkMs)
        require(minSilenceMs > 0)
    }

    fun append(pcm: ByteArray, isSpeech: Boolean): List<ByteArray> {
        require(pcm.size % PCM16_BYTES_PER_SAMPLE == 0) { "Incomplete PCM16 frame" }
        if (pcm.isEmpty()) return emptyList()

        val previousSize = buffer.size()
        buffer.write(pcm)
        silenceStart = if (isSpeech) null else silenceStart ?: previousSize

        return buildList {
            while (true) {
                val size = buffer.size()
                val quietStart = silenceStart
                val quietCut = if (
                    quietStart != null &&
                    size - quietStart >= minSilenceBytes &&
                    size >= minBytes
                ) {
                    ((quietStart.toLong() + size) / 2L).toInt()
                        .coerceIn(minBytes, maxBytes)
                        .alignPcm16()
                } else {
                    null
                }
                val cut = quietCut ?: if (size >= maxBytes) maxBytes else break
                add(takePrefix(cut))
            }
        }
    }

    fun finish(): ByteArray? {
        if (buffer.size() == 0) return null
        val tail = buffer.toByteArray()
        buffer.reset()
        silenceStart = null
        return tail
    }

    private fun takePrefix(cut: Int): ByteArray {
        val all = buffer.toByteArray()
        val head = all.copyOfRange(0, cut)
        buffer.reset()
        buffer.write(all, cut, all.size - cut)
        silenceStart = silenceStart?.let { start ->
            if (start < cut) 0 else start - cut
        }
        return head
    }

    private fun pcmBytes(sampleRate: Int, durationMs: Int): Int = (sampleRate.toLong() * PCM16_BYTES_PER_SAMPLE * durationMs / 1_000L)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
        .alignPcm16()

    private fun Int.alignPcm16(): Int = this and -PCM16_BYTES_PER_SAMPLE

    private companion object {
        const val PCM16_BYTES_PER_SAMPLE = 2
    }
}

/**
 * 渐进分段识别的段级结果收口。
 *
 * 段感知语义：处理循环在识别每段前调用 [beginSegment]，该段识别期间产生的
 * [onFinal]/[onError] 记入对应段槽；失败段由循环决定重试或经 [failSegment] 升级为
 * 会话级错误（沿用"首个致命错误终止会话"语义）。无段号（落盘降级）或会话级错误走
 * [failSession] 直接置致命错误。文本按段号顺序拼接，重试晚到不乱序。
 */
internal class NonStreamingChunkResultCollector(
    private val delegate: StreamingAsrEngine.Listener,
    private val emptyResultMessage: String,
    private val ignorableEmptyErrors: Set<String>,
    private val requestDurationCallback: ((Long) -> Unit)? = null,
    private val nanoTime: () -> Long = System::nanoTime
) : StreamingAsrEngine.Listener {
    private val lock = Any()
    private val texts = ArrayList<String?>()
    private val unindexedTexts = ArrayList<String>()
    private val segmentErrors = HashMap<Int, String>()
    private var activeSegmentIndex = -1
    private var state = State.Idle
    private var fatalError: String? = null
    private var stoppedAtNanos = 0L
    private var canceled = false

    val hasFatalError: Boolean
        get() = synchronized(lock) { fatalError != null }

    fun start() = synchronized(lock) {
        texts.clear()
        unindexedTexts.clear()
        segmentErrors.clear()
        activeSegmentIndex = -1
        fatalError = null
        stoppedAtNanos = 0L
        canceled = false
        state = State.Active
    }

    /** 处理循环在识别某段前调用；与该段识别回调同属处理协程，单写者。 */
    fun beginSegment(index: Int) = synchronized(lock) {
        if (state != State.Active || index < 0) return
        activeSegmentIndex = index
        while (texts.size <= index) texts.add(null)
    }

    /** 识别返回后查询该段是否失败（空段等可忽略错误不算失败）。 */
    fun segmentFailed(index: Int): Boolean = synchronized(lock) {
        index >= 0 && segmentErrors.containsKey(index)
    }

    /** 段级重试耗尽（或不可重试）时升级为会话级致命错误。 */
    fun failSegment(index: Int) = synchronized(lock) {
        if (state != State.Active || index < 0) return
        val message = segmentErrors[index] ?: return
        if (fatalError == null) fatalError = message
    }

    /** 会话级错误（采集失败、权限等非段级来源）直接置致命错误。 */
    fun failSession(message: String) = onErrorInternal(message, scopeToSegment = false)

    suspend fun finish(
        transformFinal: suspend (String) -> String = { it },
        onFinalized: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val completed = synchronized(lock) {
            if (state != State.Active) return
            state = State.Terminal
            val unresolvedSegmentError = segmentErrors.minByOrNull { it.key }?.value
            val outcome = (fatalError ?: unresolvedSegmentError)?.let(Outcome::Error) ?: run {
                val text = joinNonStreamingChunkTexts(texts.filterNotNull() + unindexedTexts)
                if (text.isBlank()) Outcome.Error(emptyResultMessage) else Outcome.Final(text)
            }
            stoppedAtNanos to outcome
        }
        val outcome = when (val raw = completed.second) {
            is Outcome.Final -> Outcome.Final(transformFinal(raw.text))
            is Outcome.Error -> raw
        }
        if (synchronized(lock) { canceled }) return
        if (outcome is Outcome.Final) onFinalized(outcome.text)
        val waitMs = if (completed.first > 0L) {
            (nanoTime() - completed.first).coerceAtLeast(0L) / NANOS_PER_MILLISECOND
        } else {
            0L
        }
        try {
            requestDurationCallback?.invoke(waitMs)
        } catch (t: Throwable) {
            Log.w("NonStreamingChunking", "Failed to report combined request duration", t)
        }
        when (outcome) {
            is Outcome.Final -> delegate.onFinal(outcome.text)
            is Outcome.Error -> {
                onError(outcome.message)
                delegate.onError(outcome.message)
            }
        }
    }

    fun cancel() = synchronized(lock) {
        texts.clear()
        unindexedTexts.clear()
        segmentErrors.clear()
        activeSegmentIndex = -1
        fatalError = null
        stoppedAtNanos = 0L
        canceled = true
        state = State.Terminal
    }

    override fun onFinal(text: String) {
        val forward = synchronized(lock) {
            when (state) {
                State.Idle -> true
                State.Active -> {
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        val index = activeSegmentIndex
                        if (index >= 0) {
                            texts[index] = trimmed
                            segmentErrors.remove(index)
                        } else {
                            unindexedTexts.add(trimmed)
                        }
                    }
                    false
                }
                State.Terminal -> false
            }
        }
        if (forward) delegate.onFinal(text)
    }

    override fun onError(message: String) = onErrorInternal(message, scopeToSegment = true)

    private fun onErrorInternal(message: String, scopeToSegment: Boolean) {
        val forward = synchronized(lock) {
            when (state) {
                State.Idle -> true
                State.Active -> {
                    if (message !in ignorableEmptyErrors) {
                        if (scopeToSegment && activeSegmentIndex >= 0) {
                            segmentErrors[activeSegmentIndex] = message
                        } else if (fatalError == null) {
                            fatalError = message
                        }
                    }
                    false
                }
                State.Terminal -> false
            }
        }
        if (forward) delegate.onError(message)
    }

    override fun onPartial(text: String) {
        val forward = synchronized(lock) { state == State.Idle }
        if (forward) delegate.onPartial(text)
    }

    override fun onStopped() {
        synchronized(lock) {
            if (state == State.Active && stoppedAtNanos == 0L) stoppedAtNanos = nanoTime()
        }
        delegate.onStopped()
    }

    override fun onAmplitude(amplitude: Float) = delegate.onAmplitude(amplitude)

    private enum class State { Idle, Active, Terminal }

    private sealed interface Outcome {
        data class Final(val text: String) : Outcome
        data class Error(val message: String) : Outcome
    }

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
