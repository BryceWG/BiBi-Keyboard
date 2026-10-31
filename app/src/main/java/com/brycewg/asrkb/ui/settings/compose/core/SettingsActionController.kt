/**
 * 设置页系统动作统一入口。
 *
 * 归属模块：ui/settings/compose/core
 */
package com.brycewg.asrkb.ui.settings.compose.core

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.SettingsActivity
import com.brycewg.asrkb.ui.settings.search.SettingsSearchEntry
import com.brycewg.asrkb.ui.setup.OnboardingGuideActivity

class SettingsActionController(
    private val activity: SettingsActivity
) {
    private val prefs = Prefs(activity)

    val updatesEnabled: Boolean
        get() = activity.updatesEnabledFromCompose()

    fun hapticTap() {
        activity.hapticTapFromCompose()
    }

    fun startOneClickSetup() {
        activity.startOneClickSetupFromCompose()
    }

    fun checkForUpdates() {
        activity.checkForUpdatesFromCompose()
    }

    fun showTestInput() {
        activity.showTestInputFromCompose()
    }

    fun showImePicker() {
        activity.showImePickerFromCompose()
    }

    fun openOnboardingGuide() {
        activity.startActivity(Intent(activity, OnboardingGuideActivity::class.java))
    }

    fun applySearchEntry(entry: SettingsSearchEntry) {
        entry.forceAsrVendorId?.let { prefs.asrVendor = AsrVendor.fromId(it) }
        entry.forceLlmVendorId?.let { prefs.llmVendor = LlmVendor.fromId(it) }
    }

    fun openUrl(@StringRes urlRes: Int) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(activity.getString(urlRes))))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Failed to open URL: no browser found", e)
            showSystemMessage(R.string.error_open_browser)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to open URL", e)
            showSystemMessage(R.string.error_open_browser)
        }
    }

    fun openUrl(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Failed to open URL: no browser found", e)
            showSystemMessage(R.string.error_open_browser)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to open URL", e)
            showSystemMessage(R.string.error_open_browser)
        }
    }

    fun setDebugRecording(enabled: Boolean): Boolean {
        try {
            if (enabled) {
                DebugLogManager.start(activity)
                showSystemMessage(R.string.toast_debug_recording_started)
                logSupportEnvironment()
            } else {
                DebugLogManager.stop()
                showSystemMessage(R.string.toast_debug_recording_stopped)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed toggling debug recording", t)
            showSystemMessage(R.string.toast_debug_failed)
        }
        return DebugLogManager.isRecording()
    }

    fun exportDebugLog() {
        try {
            when (val result = DebugLogManager.buildShareIntent(activity)) {
                is DebugLogManager.ShareIntentResult.Success -> {
                    try {
                        activity.startActivity(
                            Intent.createChooser(
                                result.intent,
                                activity.getString(R.string.btn_debug_export)
                            )
                        )
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to start share chooser", e)
                        showSystemMessage(R.string.toast_debug_export_failed)
                    }
                }

                is DebugLogManager.ShareIntentResult.Error -> {
                    val messageRes = when (result.error) {
                        DebugLogManager.ShareError.RecordingActive -> R.string.toast_debug_stop_before_export
                        DebugLogManager.ShareError.NoLog -> R.string.toast_debug_no_log
                        DebugLogManager.ShareError.Failed -> R.string.toast_debug_export_failed
                    }
                    showSystemMessage(messageRes)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to export debug log", t)
            showSystemMessage(R.string.toast_debug_export_failed)
        }
    }

    fun buildLicensesText(): String? = try {
        buildString {
            append(activity.readAssetFile("licenses/sherpa-onnx-LICENSE"))
            append("\n\n")
            append("=".repeat(80))
            append("\n\n")
            append(activity.readAssetFile("licenses/SyncClipboard-LICENSE"))
            append("\n\n")
            append("=".repeat(80))
            append("\n\n")
            append(activity.readAssetFile("licenses/Phosphor-LICENSE"))
            append("\n\n")
            append("=".repeat(80))
            append("\n\n")
            append(activity.readAssetFile("licenses/Miuix-LICENSE"))
            append("\n\n")
            append("=".repeat(80))
            append("\n\n")
            append(activity.readAssetFile("licenses/WaveLineView-LICENSE"))
            append("\n\n")
            append("=".repeat(80))
            append("\n\n")
            append(activity.readAssetFile("licenses/TenVAD-LICENSE"))
            append("\n\n")
            append(activity.readAssetFile("licenses/Foxlet-LICENSE"))
        }
    } catch (e: Throwable) {
        Log.e(TAG, "Failed to build licenses text", e)
        showSystemMessage(R.string.toast_debug_failed)
        null
    }

    private fun showSystemMessage(@StringRes messageRes: Int) {
        activity.showSystemActionDialogFromCompose(
            titleRes = R.string.settings_title,
            messageRes = messageRes
        )
    }

    private fun SettingsActivity.readAssetFile(fileName: String): String = try {
        assets.open(fileName).bufferedReader().use { it.readText() }
    } catch (e: Throwable) {
        Log.e(TAG, "Failed to read asset file: $fileName", e)
        "Error reading file: $fileName"
    }

    private fun logSupportEnvironment() {
        try {
            val overlayOk = try {
                android.provider.Settings.canDrawOverlays(activity)
            } catch (_: Throwable) {
                false
            }
            val powerManager = try {
                activity.getSystemService(android.os.PowerManager::class.java)
            } catch (_: Throwable) {
                null
            }
            val batteryIgnore = try {
                powerManager?.isIgnoringBatteryOptimizations(activity.packageName) ?: false
            } catch (_: Throwable) {
                false
            }
            val notifGranted = try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                } else {
                    true
                }
            } catch (_: Throwable) {
                null
            } ?: true
            val envData = mapOf(
                "overlay" to overlayOk,
                "a11y" to com.brycewg.asrkb.ui.AsrAccessibilityService.isEnabled(),
                "batteryIgnore" to batteryIgnore,
                "notifGranted" to notifGranted
            )
            DebugLogManager.logBase(
                context = activity,
                category = "env",
                event = "support_logging_enabled",
                data = envData
            )
            DebugLogManager.log(
                category = "env",
                event = "permissions",
                data = envData
            )
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to log env status", e)
        }
    }

    private companion object {
        private const val TAG = "SettingsActionController"
    }
}
