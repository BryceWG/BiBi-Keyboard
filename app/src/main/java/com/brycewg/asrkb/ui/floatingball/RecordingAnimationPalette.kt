/**
 * Material/Monet 录音动画配色，仅用主色色组的明暗变化形成渐变层次。
 * 归属模块：ui/floatingball
 */
package com.brycewg.asrkb.ui.floatingball

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils

internal data class RecordingAnimationPalette(
    val ribbons: List<List<Int>>,
    val body: List<Int>,
    val core: List<Int>,
    val ribbonGlow: List<Int>,
    val orbGlow: List<Int>
) {
    companion object {
        fun material(scheme: ColorScheme, isDark: Boolean): RecordingAnimationPalette {
            val primary = scheme.primary.toArgb()
            val primaryContainer = scheme.primaryContainer.toArgb()
            val onPrimaryContainer = scheme.onPrimaryContainer.toArgb()
            val middle = mix(primary, primaryContainer, 0.35f)
            val soft = mix(primary, primaryContainer, 0.65f)
            val contrast = mix(primary, onPrimaryContainer, 0.25f)
            val base = if (isDark) {
                mix(scheme.surfaceContainerHighest.toArgb(), scheme.onSurface.toArgb(), 0.16f)
            } else {
                scheme.surface.toArgb()
            }
            val highlight = if (isDark) {
                mix(base, scheme.onSurface.toArgb(), 0.40f)
            } else {
                scheme.onPrimary.toArgb()
            }
            return RecordingAnimationPalette(
                ribbons = listOf(
                    listOf(soft, primary, contrast, middle),
                    listOf(primaryContainer, soft, primary, contrast),
                    listOf(middle, contrast, soft, primary),
                    listOf(soft, middle, primary, primaryContainer)
                ),
                body = listOf(base, base),
                core = listOf(
                    alpha(highlight, 217),
                    alpha(mix(highlight, base, 0.25f), 153),
                    alpha(base, 38),
                    alpha(base, 0)
                ),
                ribbonGlow = listOf(alpha(primary, 128), alpha(soft, 51), alpha(soft, 0)),
                orbGlow = listOf(
                    alpha(mix(base, primary, 0.25f), 153),
                    alpha(mix(base, primary, 0.20f), 51),
                    alpha(mix(base, primary, 0.20f), 0)
                )
            )
        }

        private fun mix(start: Int, end: Int, amount: Float): Int = ColorUtils.blendARGB(start, end, amount)

        private fun alpha(color: Int, value: Int): Int = ColorUtils.setAlphaComponent(color, value)
    }
}
