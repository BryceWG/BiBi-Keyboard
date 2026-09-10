/**
 * Compose 悬浮球设置页状态与系统能力 helper。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.imebridge.ImeBridgeClient
import com.brycewg.asrkb.imebridge.ImeBridgeContract
import com.brycewg.asrkb.imebridge.ImeBridgeResult
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrAccessibilityService
import com.brycewg.asrkb.ui.floating.FloatingServiceManager

private const val FLOATING_SETTINGS_STATE_TAG = "FloatingSettingsState"

internal data class FloatingSettingsUiState(
    val asrEnabled: Boolean,
    val onlyWhenImeVisible: Boolean,
    val holdToRecordEnabled: Boolean,
    val directDragEnabled: Boolean,
    val alphaPercent: Float,
    val sizeDp: Int,
    val volumeKeyRecordingEnabled: Boolean,
    val volumeKeyRecordingMode: String,
    val volumeKeyStatusToastEnabled: Boolean,
    val volumeKeyStopOnImeHidden: Boolean,
    val writeCompatEnabled: Boolean,
    val writePasteEnabled: Boolean,
    val a11yAndroid13ApiEnabled: Boolean,
    val imeBridgeEnabled: Boolean
) {
    companion object {
        val placeholder: FloatingSettingsUiState = FloatingSettingsUiState(
            asrEnabled = false,
            onlyWhenImeVisible = false,
            holdToRecordEnabled = false,
            directDragEnabled = false,
            alphaPercent = 100f,
            sizeDp = 56,
            volumeKeyRecordingEnabled = false,
            volumeKeyRecordingMode = Prefs.VOLUME_KEY_MODE_UP_TOGGLE,
            volumeKeyStatusToastEnabled = false,
            volumeKeyStopOnImeHidden = true,
            writeCompatEnabled = false,
            writePasteEnabled = false,
            a11yAndroid13ApiEnabled = false,
            imeBridgeEnabled = false
        )

        fun fromPrefs(prefs: Prefs): FloatingSettingsUiState = FloatingSettingsUiState(
            asrEnabled = prefs.floatingAsrEnabled,
            onlyWhenImeVisible = prefs.floatingSwitcherOnlyWhenImeVisible,
            holdToRecordEnabled = prefs.floatingBallHoldToRecordEnabled,
            directDragEnabled = prefs.floatingBallDirectDragEnabled,
            alphaPercent = (prefs.floatingSwitcherAlpha * 100f).coerceIn(30f, 100f),
            sizeDp = prefs.floatingBallSizeDp,
            volumeKeyRecordingEnabled = prefs.volumeKeyRecordingEnabled,
            volumeKeyRecordingMode = prefs.volumeKeyRecordingMode,
            volumeKeyStatusToastEnabled = prefs.volumeKeyStatusToastEnabled,
            volumeKeyStopOnImeHidden = prefs.volumeKeyStopOnImeHidden,
            writeCompatEnabled = prefs.floatingWriteTextCompatEnabled,
            writePasteEnabled = prefs.floatingWriteTextPasteEnabled,
            a11yAndroid13ApiEnabled = prefs.floatingA11yAndroid13ApiEnabled,
            imeBridgeEnabled = prefs.floatingImeBridgeEnabled
        )
    }
}

internal enum class ImeBridgeFailureKind {
    MicrophonePermission,
    InjectionUnsupported,
    Other
}

internal fun shouldQueryImeBridgeStatus(bridgeEnabled: Boolean): Boolean = bridgeEnabled

internal fun classifyImeBridgeRecordingFailure(lastError: String?): ImeBridgeFailureKind {
    val normalized = lastError?.lowercase().orEmpty()
    return when {
        normalized.contains("permission") ||
            normalized.contains("record_audio") ||
            normalized.contains("audio record failed") ->
            ImeBridgeFailureKind.MicrophonePermission

        normalized.contains("unsupported ime window root") ||
            normalized.contains("ime window not ready") ||
            normalized.contains("no input method service") ||
            normalized.contains("not attached") ||
            normalized.contains("attach failed") ->
            ImeBridgeFailureKind.InjectionUnsupported

        else -> ImeBridgeFailureKind.Other
    }
}

internal fun formatImeBridgeStatus(
    context: Context,
    result: ImeBridgeResult?,
    bridgeEnabled: Boolean
): String {
    if (!shouldQueryImeBridgeStatus(bridgeEnabled)) {
        return context.getString(R.string.status_floating_ime_bridge_disabled)
    }
    if (result == null) return context.getString(R.string.status_floating_ime_bridge_unknown)

    val target = result.targetPackage
        ?: ImeBridgeClient.resolveCurrentImePackage(context)
        ?: context.getString(R.string.status_floating_ime_bridge_unknown_target)
    val headline = when {
        result.code == ImeBridgeContract.RESULT_NO_CURRENT_IME ||
            result.code == ImeBridgeContract.RESULT_NO_ACTIVE_IME ->
            context.getString(R.string.status_floating_ime_bridge_no_active_ime)

        result.code == ImeBridgeContract.RESULT_NO_INPUT_CONNECTION ->
            context.getString(R.string.status_floating_ime_bridge_no_input_connection, target)

        result.code == ImeBridgeContract.RESULT_SENSITIVE_FIELD || result.isSensitiveField ->
            context.getString(R.string.status_floating_ime_bridge_sensitive, target)

        !result.isBridgePresent ->
            context.getString(R.string.status_floating_ime_bridge_not_found, target)

        !result.isImeWindowVisible ->
            context.getString(R.string.status_floating_ime_bridge_hidden, target)

        !result.hasInputConnection ->
            context.getString(R.string.status_floating_ime_bridge_no_input_connection, target)

        result.isSuccess ->
            context.getString(R.string.status_floating_ime_bridge_ready, target)

        else ->
            context.getString(R.string.status_floating_ime_bridge_error, target, result.message)
    }
    val moduleText = context.getString(
        R.string.status_floating_ime_bridge_module_summary,
        result.moduleVersion ?: context.getString(R.string.status_floating_ime_bridge_module_unknown),
        context.getString(
            if (result.supportsPcmRecording) {
                R.string.status_floating_ime_bridge_pcm_supported
            } else {
                R.string.status_floating_ime_bridge_pcm_not_supported
            }
        ),
        context.getString(
            if (result.supportsClipboard) {
                R.string.status_floating_ime_bridge_clipboard_supported
            } else {
                R.string.status_floating_ime_bridge_clipboard_not_supported
            }
        )
    )
    val inputText = context.getString(
        R.string.status_floating_ime_bridge_input_summary,
        context.yesNo(result.isImeWindowVisible),
        context.yesNo(result.hasInputConnection),
        context.yesNo(result.isSensitiveField)
    )
    val lastFailure = formatImeBridgeLastFailure(context, result.lastError)
    return listOfNotNull(headline, moduleText, inputText, lastFailure).joinToString("\n")
}

private fun formatImeBridgeLastFailure(context: Context, lastError: String?): String? {
    val trimmed = lastError?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    val summaryRes = when (classifyImeBridgeRecordingFailure(trimmed)) {
        ImeBridgeFailureKind.MicrophonePermission ->
            R.string.status_floating_ime_bridge_last_failure_mic_permission

        ImeBridgeFailureKind.InjectionUnsupported ->
            R.string.status_floating_ime_bridge_last_failure_injection_unsupported

        ImeBridgeFailureKind.Other ->
            R.string.status_floating_ime_bridge_last_failure
    }
    return context.getString(summaryRes, trimmed)
}

private fun Context.yesNo(value: Boolean): String = getString(
    if (value) R.string.status_floating_ime_bridge_yes else R.string.status_floating_ime_bridge_no
)

internal data class FloatingSettingsPrefsSnapshot(
    val uiState: FloatingSettingsUiState,
    val compatPackages: String,
    val pastePackages: String
) {
    companion object {
        fun fromPrefs(prefs: Prefs): FloatingSettingsPrefsSnapshot = FloatingSettingsPrefsSnapshot(
            uiState = FloatingSettingsUiState.fromPrefs(prefs),
            compatPackages = prefs.floatingWriteCompatPackages,
            pastePackages = prefs.floatingWritePastePackages
        )
    }
}

internal enum class FloatingPermissionRequest {
    Overlay,
    Accessibility
}

internal fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val component = ComponentName(context, AsrAccessibilityService::class.java)
    val expectedComponentNames = setOf(
        component.flattenToString(),
        component.flattenToShortString()
    )
    val enabledServicesSetting = try {
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
    } catch (e: Throwable) {
        Log.e(FLOATING_SETTINGS_STATE_TAG, "Failed to check accessibility service", e)
        return false
    }
    return enabledServicesSetting
        ?.split(':')
        ?.any { it in expectedComponentNames } == true
}

internal fun resetFloatingPosition(
    context: Context,
    prefs: Prefs,
    serviceManager: FloatingServiceManager
): Boolean {
    var success = true
    try {
        prefs.floatingBallPosX = -1
        prefs.floatingBallPosY = -1
        prefs.floatingBallDockSide = 0
        prefs.floatingBallDockFraction = -1f
        prefs.floatingBallDockHidden = false
    } catch (e: Throwable) {
        Log.e(FLOATING_SETTINGS_STATE_TAG, "Failed to reset floating position in prefs", e)
        success = false
    }
    try {
        serviceManager.resetAsrBallPosition()
    } catch (e: Throwable) {
        Log.e(FLOATING_SETTINGS_STATE_TAG, "Failed to dispatch reset to service", e)
        success = false
    }
    return success
}
