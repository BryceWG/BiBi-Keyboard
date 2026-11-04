/**
 * 界面设置页「应用图标背景色」下拉项。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.icon.AppIconColor
import com.brycewg.asrkb.ui.icon.applyAppIconColor
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry

@Composable
internal fun InputAppIconColorPreference(
    prefs: Prefs,
    index: Int,
    count: Int
) {
    val context = LocalContext.current
    // 下拉框标签读的是 prefs，prefs 变化不会触发重组，本地留一份用于即时回显
    var iconColorId by remember { mutableStateOf(AppIconColor.fromId(prefs.appIconColor).id) }
    val options = AppIconColor.entries.map {
        DropdownOption(it.id, stringResource(it.labelRes))
    }
    SettingsPreference(
        entry = SettingsEntry.Dropdown(
            id = "settings_app_icon_color",
            titleRes = R.string.settings_app_icon_color,
            summaryRes = R.string.settings_app_icon_color_summary,
            options = options,
            selectedOptionId = iconColorId,
            onSelectedOptionChange = { id ->
                iconColorId = id
                if (id != prefs.appIconColor) {
                    prefs.appIconColor = id
                    applyAppIconColor(context, id)
                }
            }
        ),
        index = index,
        count = count
    )
}
