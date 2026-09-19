package com.brycewg.asrkb.util

import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.RequiresApi

internal data class WindowSizePx(
    val width: Int,
    val height: Int
)

internal fun calculateUsableWindowSize(
    boundsWidth: Int,
    boundsHeight: Int,
    insetLeft: Int,
    insetTop: Int,
    insetRight: Int,
    insetBottom: Int
): WindowSizePx = WindowSizePx(
    width = (boundsWidth - insetLeft - insetRight).coerceAtLeast(0),
    height = (boundsHeight - insetTop - insetBottom).coerceAtLeast(0)
)

@RequiresApi(Build.VERSION_CODES.R)
internal fun WindowManager.currentUsableWindowSize(): WindowSizePx = currentWindowMetrics.toUsableWindowSize()

@RequiresApi(Build.VERSION_CODES.R)
internal fun WindowManager.maximumUsableWindowSize(): WindowSizePx = maximumWindowMetrics.toUsableWindowSize()

@Suppress("DEPRECATION")
internal fun WindowManager.legacyUsableWindowSize(): WindowSizePx {
    val metrics = DisplayMetrics()
    defaultDisplay.getMetrics(metrics)
    return WindowSizePx(metrics.widthPixels, metrics.heightPixels)
}

@RequiresApi(Build.VERSION_CODES.R)
private fun android.view.WindowMetrics.toUsableWindowSize(): WindowSizePx {
    val insets = windowInsets.getInsetsIgnoringVisibility(
        WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
    )
    return calculateUsableWindowSize(
        boundsWidth = bounds.width(),
        boundsHeight = bounds.height(),
        insetLeft = insets.left,
        insetTop = insets.top,
        insetRight = insets.right,
        insetBottom = insets.bottom
    )
}
