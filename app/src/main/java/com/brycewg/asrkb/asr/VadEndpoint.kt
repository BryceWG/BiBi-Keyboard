/** 按 32 ms 概率帧推进的停说判停状态机；归属模块：asr。 */
package com.brycewg.asrkb.asr

internal class VadEndpoint(private val tuning: VadTuning, private val silenceMs: Int) {
    private enum class State { Waiting, Speaking, Ended }

    private var state = State.Waiting
    private var elapsedMs = 0
    private var strongMs = 0
    private var gapMs = 0
    private var candidateStartMs = 0
    private var silenceStartMs: Int? = null
    private var recoveryMs = 0
    private var recoveryRequiredMs = 0
    var firstSpeechMs: Int? = null
        private set
    var lastSpeechMs: Int? = null
        private set
    var stopReason: String? = null
        private set
    val ended: Boolean get() = state == State.Ended

    fun accept(probability: Float, allowStop: Boolean, frameEndMs: Int? = null): Boolean {
        if (ended) return false
        elapsedMs = frameEndMs ?: (elapsedMs + FRAME_MS)
        val strong = probability >= tuning.threshold
        val quiet = probability < tuning.endThreshold
        var speech = false
        when (state) {
            State.Waiting -> {
                when {
                    strong -> {
                        if (strongMs == 0) candidateStartMs = elapsedMs - FRAME_MS
                        strongMs += FRAME_MS
                        gapMs = 0
                    }
                    else -> {
                        // 中间概率帧也计入间隔，起说候选不能跨长段噪声累加。
                        gapMs += FRAME_MS
                        if (gapMs > tuning.maxSpeechGapMs) strongMs = 0
                    }
                }
                if (strongMs >= tuning.minSpeechMs) {
                    state = State.Speaking
                    firstSpeechMs = candidateStartMs
                    lastSpeechMs = elapsedMs
                    speech = true
                }
            }
            State.Speaking -> {
                if (silenceStartMs == null) {
                    if (quiet) {
                        silenceStartMs = elapsedMs - FRAME_MS
                    } else {
                        lastSpeechMs = elapsedMs
                        speech = true
                    }
                }
                val silenceStart = silenceStartMs
                if (silenceStart != null) {
                    // 截止帧优先判停，恢复确认只允许发生在截止帧之前。
                    if (allowStop && elapsedMs - silenceStart >= silenceMs) {
                        end("silence")
                        return false
                    }
                    if (strong) {
                        // 恢复长度在强帧串开始时确定，避免跨过 600 ms 时改变门槛。
                        if (recoveryMs == 0) {
                            recoveryRequiredMs = if (elapsedMs - FRAME_MS - silenceStart < tuning.recoveryTransitionMs) {
                                tuning.shortRecoveryMs
                            } else {
                                tuning.longRecoveryMs
                            }
                        }
                        recoveryMs += FRAME_MS
                        if (recoveryMs >= recoveryRequiredMs) {
                            silenceStartMs = null
                            recoveryMs = 0
                            lastSpeechMs = elapsedMs
                            speech = true
                        }
                    } else {
                        recoveryMs = 0
                    }
                }
            }
            State.Ended -> Unit
        }
        return speech
    }

    private fun end(reason: String) {
        state = State.Ended
        stopReason = reason
    }

    companion object {
        const val FRAME_MS = 32
    }
}

/** 过滤/分段持有各自的状态，不创建模型，也不参与录音判停。 */
internal class VadSpeechClassifier(tuning: VadTuning = VadTuning.SilenceFilter) {
    private val endpoint = VadEndpoint(tuning, silenceMs = 0)
    private var lastSpeech = false
    private var samples = 0L

    fun accept(probabilities: VadProbabilities): Boolean {
        var anySpeech = false
        for (frame in probabilities.frames) {
            lastSpeech = endpoint.accept(
                frame.probability,
                allowStop = false,
                frameEndMs = ((samples + frame.endSample) * 1000 / probabilities.sampleRate).toInt()
            )
            anySpeech = anySpeech || lastSpeech
        }
        samples += probabilities.sampleCount
        return anySpeech || lastSpeech
    }

    fun speechBounds(byteCount: Int, sampleRate: Int): IntRange {
        val first = endpoint.firstSpeechMs ?: return 0 until byteCount
        val last = checkNotNull(endpoint.lastSpeechMs)
        val start = ((first - 400).coerceAtLeast(0).toLong() * sampleRate / 1000 * 2).toInt()
        val end = minOf(byteCount.toLong(), (last + 150).toLong() * sampleRate / 1000 * 2).toInt()
        return start until end
    }
}
