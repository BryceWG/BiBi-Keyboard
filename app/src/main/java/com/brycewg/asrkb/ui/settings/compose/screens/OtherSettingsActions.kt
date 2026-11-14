/**
 * Compose 其他设置页动作与副作用工具。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import com.brycewg.asrkb.R
import com.brycewg.asrkb.analytics.AnalyticsManager
import com.brycewg.asrkb.clipboard.ClipboardHistoryStore
import com.brycewg.asrkb.clipboard.SyncClipboardManager
import com.brycewg.asrkb.store.AsrHistoryAudioStore
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.floating.PrivilegedAccessManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val OTHER_ACTION_TAG = "OtherSettingsActions"

internal fun checkPrivilegedKeepAlivePreconditions(
    context: Context,
    shouldEnable: Boolean,
    setPendingEnable: (Boolean) -> Unit,
    showMessage: (Int) -> Unit
): Boolean {
    if (!shouldEnable) return true
    setPendingEnable(false)
    if (PrivilegedAccessManager.isRootProbablyAvailable()) return true
    if (PrivilegedAccessManager.isServiceGranted()) return true

    setPendingEnable(true)
    PrivilegedAccessManager.requestPermission(context)
    showMessage(R.string.toast_shizuku_permission_waiting)
    return false
}

internal fun clearAsrHistory(context: Context, showMessage: (Int) -> Unit) {
    try {
        AsrHistoryStore(context).clearAll()
        AsrHistoryAudioStore(context).clearAll()
        showMessage(R.string.toast_cleared_history)
    } catch (e: Throwable) {
        Log.e(OTHER_ACTION_TAG, "Failed to clear ASR history", e)
    }
}

internal fun clearUsageStats(context: Context, prefs: Prefs, showMessage: (Int) -> Unit) {
    try {
        prefs.resetUsageStats()
        showMessage(R.string.toast_cleared_stats)
    } catch (e: Throwable) {
        Log.e(OTHER_ACTION_TAG, "Failed to reset usage stats", e)
    }
}

internal fun clearClipboardHistory(
    context: Context,
    prefs: Prefs,
    scope: CoroutineScope,
    showMessage: (Int) -> Unit
) {
    scope.launch(Dispatchers.IO) {
        try {
            ClipboardHistoryStore(context, prefs).clearAll()
            withContext(Dispatchers.Main) {
                showMessage(R.string.toast_cleared_clipboard_history)
            }
        } catch (e: Throwable) {
            Log.e(OTHER_ACTION_TAG, "Failed to clear clipboard history", e)
            withContext(Dispatchers.Main) {
                showMessage(R.string.toast_clear_clipboard_history_failed)
            }
        }
    }
}

internal fun updateAnalyticsConsent(context: Context, enabled: Boolean) {
    try {
        AnalyticsManager.sendConsentChoice(context, enabled)
    } catch (t: Throwable) {
        Log.w(OTHER_ACTION_TAG, "Failed to send consent choice from settings", t)
    }
    if (enabled) {
        try {
            AnalyticsManager.init(context)
        } catch (t: Throwable) {
            Log.w(OTHER_ACTION_TAG, "Failed to init analytics after enabling", t)
        }
    }
}

internal fun testClipboardSync(
    context: Context,
    prefs: Prefs,
    scope: CoroutineScope,
    showMessage: (Int) -> Unit
) {
    val manager = SyncClipboardManager(context, prefs, scope)
    scope.launch(Dispatchers.IO) {
        val (ok, _) = try {
            manager.pullNow(updateClipboard = false)
        } catch (e: Throwable) {
            Log.e(OTHER_ACTION_TAG, "Failed to test clipboard sync", e)
            false to null
        }
        withContext(Dispatchers.Main) {
            showMessage(if (ok) R.string.sc_test_success else R.string.sc_test_failed)
        }
    }
}

internal fun openSyncClipboardProjectHome(context: Context, showMessage: (Int) -> Unit) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, "https://github.com/Jeric-X/SyncClipboard".toUri())
        )
    } catch (e: Throwable) {
        Log.e(OTHER_ACTION_TAG, "Failed to open project home page", e)
        showMessage(R.string.sc_open_browser_failed)
    }
}

internal fun requestBatteryOptimizationWhitelist(context: Context, showMessage: (Int) -> Unit) {
    val powerManager = context.getSystemService(PowerManager::class.java)
    val alreadyIgnoring = try {
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    } catch (e: Throwable) {
        Log.w(OTHER_ACTION_TAG, "Failed to query battery optimization state", e)
        false
    }
    if (alreadyIgnoring) {
        showMessage(R.string.toast_battery_whitelist_already)
        return
    }

    try {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:${context.packageName}".toUri()
            )
        )
    } catch (e: Throwable) {
        Log.e(OTHER_ACTION_TAG, "Failed to request battery optimization whitelist", e)
        showMessage(R.string.toast_battery_whitelist_failed)
    }
}
