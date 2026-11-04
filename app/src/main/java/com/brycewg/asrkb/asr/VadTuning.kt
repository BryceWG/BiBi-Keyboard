/** 本地 VAD 各用途的独立判定参数；不参与 Silero 模型构造。 */
package com.brycewg.asrkb.asr

import com.brycewg.asrkb.store.Prefs

data class VadTuning(
    val name: String,
    val threshold: Float,
    val minSpeechMs: Int = 250,
    val maxSpeechGapMs: Int = 64,
    val shortRecoveryMs: Int = 128,
    val longRecoveryMs: Int = 250,
    val recoveryTransitionMs: Int = 600
) {
    val endThreshold: Float get() = threshold - 0.15f

    companion object {
        const val LEVELS = 3
        val Default = tuningForLevel(Prefs.DEFAULT_SILENCE_SENSITIVITY)
        val SilenceFilter = VadTuning("silence_filter", 0.40f)
        val SpeechBounds = VadTuning("speech_bounds", 0.40f)
        val SentenceBoundary = VadTuning("sentence_boundary", 0.40f)

        // 判停灵敏度越高，静音阈值越高，越容易进入静音计时并停录。
        fun tuningForLevel(level: Int): VadTuning = when (level) {
            1 -> VadTuning("high", 0.60f)
            2 -> VadTuning("standard", 0.50f)
            3 -> VadTuning("low", 0.40f)
            else -> error("Unknown VAD sensitivity: $level")
        }
    }
}

/** 全空录音使用独立的累计规则，不沿用判停器的起说/恢复条件。 */
internal data class EmptyAudioTuning(
    val threshold: Float,
    val minSpeechMs: Int
) {
    val weakThreshold: Float get() = threshold - 0.15f
    val maxSilenceMs: Int get() = 64

    companion object {
        fun forStopSensitivity(level: Int): EmptyAudioTuning = when (level) {
            1 -> EmptyAudioTuning(0.45f, 192)
            2 -> EmptyAudioTuning(0.35f, 160)
            3 -> EmptyAudioTuning(0.25f, 128)
            else -> error("Unknown empty audio sensitivity: $level")
        }
    }
}
