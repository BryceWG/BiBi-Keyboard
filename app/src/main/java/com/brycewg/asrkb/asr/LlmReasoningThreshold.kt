/**
 * LLM 深度思考字数阈值：哨兵语义、有效字数判断与非线性滑块映射。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object LlmReasoningThreshold {
    const val ALWAYS = 0
    const val NEVER = 300
    const val MIN = ALWAYS
    const val MAX = NEVER

    fun coerce(value: Int): Int = value.coerceIn(MIN, MAX)

    fun fromLegacyEnabled(enabled: Boolean): Int = if (enabled) ALWAYS else NEVER

    fun resolve(stored: Int?, legacyEnabled: Boolean): Int = stored?.let { coerce(it) } ?: fromLegacyEnabled(legacyEnabled)

    fun shouldEnable(threshold: Int, effectiveChars: Int): Boolean {
        val t = coerce(threshold)
        if (t <= ALWAYS) return true
        if (t >= NEVER) return false
        // 与文案一致：中间值仅在字数超过阈值时启用，等于阈值仍关闭
        return effectiveChars > t
    }

    fun fromSlider(pos: Float): Int {
        val t = pos.coerceIn(0f, 1f)
        if (t <= 0f) return ALWAYS
        if (t >= 1f) return NEVER
        // 两端哨兵只在滑轨贴边时命中，避免左侧一小段拖动仍回弹到「启用」
        return (MAX * t * t).roundToInt().coerceIn(ALWAYS + 1, NEVER - 1)
    }

    fun toSlider(value: Int): Float {
        val v = coerce(value)
        if (v <= MIN) return 0f
        if (v >= MAX) return 1f
        return sqrt(v.toFloat() / MAX)
    }
}
