/**
 * View 系 UI 的设置页主题桥接。
 *
 * 归属模块：ui
 */
package com.brycewg.asrkb.ui

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import androidx.annotation.ColorInt
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import com.brycewg.asrkb.store.Prefs

internal data class BibiViewTheme(
    val isMiuix: Boolean,
    val isDark: Boolean,
    val keyboardBackground: Int,
    val keyBackground: Int,
    val keyContent: Int,
    val panelBackground: Int,
    val panelContent: Int,
    val panelSummary: Int,
    val primary: Int,
    val onPrimary: Int,
    val micContainer: Int,
    val micContent: Int,
    val floatingIcon: Int,
    val ripple: Int,
    val error: Int,
    val success: Int,
    val iconKeyRadiusDp: Float,
    val rectKeyRadiusDp: Float,
    val panelRadiusDp: Float,
    val keyInsetDp: Int,
    val menuItemBackground: Int
)

internal object BibiViewThemes {

    fun resolve(context: Context, prefs: Prefs): BibiViewTheme {
        val isMiuix = prefs.settingsUiMode != Prefs.SETTINGS_UI_MODE_MATERIAL
        val isDark = resolveDarkMode(context, prefs)
        if (isMiuix) return miuixTheme(isDark)

        val scheme = materialColorScheme(context, isDark)

        val surface = scheme.surface.toArgb()
        val onSurface = scheme.onSurface.toArgb()
        val surfaceVariant = scheme.surfaceVariant.toArgb()
        val onSurfaceVariant = scheme.onSurfaceVariant.toArgb()
        val primary = scheme.primary.toArgb()
        val onPrimary = scheme.onPrimary.toArgb()
        val primaryContainer = scheme.primaryContainer.toArgb()
        val secondaryContainer = scheme.secondaryContainer.toArgb()
        val onSecondaryContainer = scheme.onSecondaryContainer.toArgb()

        return BibiViewTheme(
            isMiuix = false,
            isDark = isDark,
            keyboardBackground = ColorUtils.blendARGB(surface, surfaceVariant, if (isDark) 0.42f else 0.54f),
            keyBackground = surface,
            keyContent = onSurfaceVariant,
            panelBackground = surface,
            panelContent = onSurface,
            panelSummary = onSurfaceVariant,
            primary = primary,
            onPrimary = onPrimary,
            micContainer = secondaryContainer,
            micContent = onSecondaryContainer,
            floatingIcon = scheme.secondary.toArgb(),
            ripple = withAlpha(primaryContainer, 0.28f),
            error = scheme.error.toArgb(),
            success = if (isDark) 0xFF2ECC71.toInt() else 0xFF1B9E4B.toInt(),
            iconKeyRadiusDp = 8f,
            rectKeyRadiusDp = 10f,
            panelRadiusDp = 12f,
            keyInsetDp = 2,
            menuItemBackground = surface
        )
    }

    fun materialColorScheme(context: Context, isDark: Boolean): ColorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (isDark) darkColorScheme() else lightColorScheme()
    }

    fun roundedRipple(
        context: Context,
        @ColorInt color: Int,
        @ColorInt rippleColor: Int,
        radiusDp: Float,
        insetDp: Int = 2
    ): Drawable {
        val radius = dp(context, radiusDp)
        val content = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radius
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.WHITE)
            cornerRadius = radius
        }
        return InsetDrawable(
            RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask),
            dp(context, insetDp.toFloat()).toInt()
        )
    }

    fun roundedRect(context: Context, @ColorInt color: Int, radiusDp: Float): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(context, radiusDp)
    }

    fun dot(@ColorInt color: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun resolveDarkMode(context: Context, prefs: Prefs): Boolean = when (prefs.settingsThemeMode) {
        Prefs.SETTINGS_THEME_MODE_LIGHT -> false
        Prefs.SETTINGS_THEME_MODE_DARK -> true
        else -> {
            val mode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            mode == Configuration.UI_MODE_NIGHT_YES
        }
    }

    @ColorInt
    private fun withAlpha(@ColorInt color: Int, alpha: Float): Int = ColorUtils.setAlphaComponent(color, (alpha * 255).toInt().coerceIn(0, 255))

    private fun miuixTheme(isDark: Boolean): BibiViewTheme {
        // 对齐 Miuix 非 Monet 默认色表，避免 View 系 UI 继续沿用 Material dynamic scheme。
        val primary = if (isDark) 0xFF277AF7.toInt() else 0xFF3482FF.toInt()
        val onPrimary = Color.WHITE
        val background = if (isDark) 0xFF242424.toInt() else Color.WHITE
        val onSurface = if (isDark) 0xFFF2F2F2.toInt() else Color.BLACK
        val secondaryVariant = if (isDark) 0xFF434343.toInt() else 0xFFF0F0F0.toInt()
        val onSecondaryVariant = if (isDark) 0xFFD9D9D9.toInt() else 0xFF303030.toInt()
        val surfaceContainer = if (isDark) 0xFF242424.toInt() else Color.WHITE
        val summary = if (isDark) 0x80FFFFFF.toInt() else 0x99000000.toInt()
        return BibiViewTheme(
            isMiuix = true,
            isDark = isDark,
            keyboardBackground = background,
            keyBackground = secondaryVariant,
            keyContent = onSecondaryVariant,
            panelBackground = surfaceContainer,
            panelContent = onSurface,
            panelSummary = summary,
            primary = primary,
            onPrimary = onPrimary,
            micContainer = primary,
            micContent = onPrimary,
            floatingIcon = primary,
            ripple = withAlpha(primary, 0.12f),
            error = if (isDark) 0xFFF12522.toInt() else 0xFFE94634.toInt(),
            success = if (isDark) 0xFF2ECC71.toInt() else 0xFF1B9E4B.toInt(),
            iconKeyRadiusDp = 14f,
            rectKeyRadiusDp = 18f,
            panelRadiusDp = 22f,
            keyInsetDp = 0,
            menuItemBackground = secondaryVariant
        )
    }

    private fun dp(context: Context, value: Float): Float = value * context.resources.displayMetrics.density
}
