/**
 * 应用图标背景色预设与切换。
 *
 * 归属模块：ui/icon
 * 默认 alias 保留旧 SettingsActivity 组件名以兼容图标包；每个颜色预设对应一个 alias。
 * 切换时只启用选中的 alias，其余禁用（见 [applyAppIconColor]）。
 */
package com.brycewg.asrkb.ui.icon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs

internal enum class AppIconColor(
    val id: String,
    val aliasClass: String,
    @param:StringRes val labelRes: Int
) {
    System(
        Prefs.APP_ICON_COLOR_SYSTEM,
        "com.brycewg.asrkb.ui.SettingsActivity",
        R.string.settings_app_icon_color_system
    ),
    Blue("blue", "com.brycewg.asrkb.ui.icon.IconBlue", R.string.settings_app_icon_color_blue),
    Green("green", "com.brycewg.asrkb.ui.icon.IconGreen", R.string.settings_app_icon_color_green),
    Orange("orange", "com.brycewg.asrkb.ui.icon.IconOrange", R.string.settings_app_icon_color_orange),
    Red("red", "com.brycewg.asrkb.ui.icon.IconRed", R.string.settings_app_icon_color_red),
    Purple("purple", "com.brycewg.asrkb.ui.icon.IconPurple", R.string.settings_app_icon_color_purple),
    Slate("slate", "com.brycewg.asrkb.ui.icon.IconSlate", R.string.settings_app_icon_color_slate),
    White("white", "com.brycewg.asrkb.ui.icon.IconWhite", R.string.settings_app_icon_color_white),
    Black("black", "com.brycewg.asrkb.ui.icon.IconBlack", R.string.settings_app_icon_color_black);

    companion object {
        fun fromId(id: String): AppIconColor = entries.firstOrNull { it.id == id } ?: System
    }
}

/**
 * 只启用 [selectedId] 对应的 alias。
 *
 * 先启用目标再禁用其余，避免中途被杀进程后出现“一个都没启用、桌面图标消失”。
 */
internal fun applyAppIconColor(context: Context, selectedId: String) {
    val selected = AppIconColor.fromId(selectedId)
    val pm = context.packageManager
    fun setEnabled(color: AppIconColor, enabled: Boolean) {
        val component = ComponentName(context.packageName, color.aliasClass)
        val state = pm.getComponentEnabledSetting(component)
        val currentlyEnabled = if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            color == AppIconColor.System
        } else {
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        // 默认状态已符合 manifest 时不写入，避免未换色的用户触发桌面组件刷新。
        if (currentlyEnabled == enabled) return
        pm.setComponentEnabledSetting(
            component,
            if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP
        )
    }
    setEnabled(selected, true)
    AppIconColor.entries.filter { it != selected }.forEach { setEnabled(it, false) }
}
