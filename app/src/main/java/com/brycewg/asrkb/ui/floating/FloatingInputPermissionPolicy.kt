/**
 * 悬浮输入相关权限依赖判断。
 *
 * 归属模块：ui/floating
 */
package com.brycewg.asrkb.ui.floating

internal fun floatingAsrNeedsAccessibility(
    floatingEnabled: Boolean,
    imeBridgeEnabled: Boolean
): Boolean = floatingEnabled && !imeBridgeEnabled

internal fun floatingInputNeedsAccessibility(
    floatingEnabled: Boolean,
    volumeKeyEnabled: Boolean,
    imeBridgeEnabled: Boolean,
    shakeRecordingEnabled: Boolean = false
): Boolean = volumeKeyEnabled ||
    shakeRecordingEnabled ||
    floatingAsrNeedsAccessibility(
        floatingEnabled = floatingEnabled,
        imeBridgeEnabled = imeBridgeEnabled
    )
