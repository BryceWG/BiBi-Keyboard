/**
 * 常驻保活通知点击后打开的设置页。
 *
 * 归属模块：ui/floating
 */
package com.brycewg.asrkb.ui.floating

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.StringRes
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.SettingsActivity
import com.brycewg.asrkb.ui.settings.compose.core.BibiSettingsRoute

internal object KeepAliveNotificationClick {
    private const val TAG = "KeepAliveNotifClick"

    /**
     * 通知 PendingIntent 的 requestCode。
     */
    const val SETTINGS_PENDING_INTENT_REQUEST_CODE = 1

    data class Destination(
        val route: BibiSettingsRoute,
        @param:StringRes val titleRes: Int
    )

    val destinations: List<Destination> = listOf(
        Destination(BibiSettingsRoute.Input, R.string.title_input_settings),
        Destination(BibiSettingsRoute.UiSettings, R.string.section_ui_settings),
        Destination(BibiSettingsRoute.Floating, R.string.title_floating_settings),
        Destination(BibiSettingsRoute.Asr, R.string.title_asr_settings),
        Destination(BibiSettingsRoute.Ai, R.string.title_ai_settings),
        Destination(BibiSettingsRoute.History, R.string.btn_open_asr_history),
        Destination(BibiSettingsRoute.UsageStats, R.string.about_stats_title)
    )

    val defaultRoute: BibiSettingsRoute = BibiSettingsRoute.History

    fun routeFromPrefs(prefs: Prefs): BibiSettingsRoute {
        val id = prefs.keepAliveNotificationClickRoute
        return destinations.firstOrNull { it.route.id == id }?.route ?: defaultRoute
    }

    fun openSettingsIntent(context: Context): Intent {
        val route = try {
            routeFromPrefs(Prefs(context))
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resolve notification click route", t)
            defaultRoute
        }
        return Intent(context, SettingsActivity::class.java).apply {
            // singleTop 根 Activity 配 CLEAR_TOP：复用已有设置页，而不是再叠一个只有首页的新实例。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(SettingsActivity.EXTRA_INITIAL_ROUTE, route.id)
        }
    }
}
