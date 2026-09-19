/**
 * Miuix 风格设置组件。
 *
 * 归属模块：ui/settings/compose/miuix
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.miuix

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun MiuixSettingsEntry(entry: SettingsEntry) {
    val hapticTap = LocalSettingsHapticTap.current
    when (entry) {
        is SettingsEntry.Action -> ArrowPreference(
            title = entry.title ?: stringResource(entry.titleRes),
            summary = settingsEntrySummary(entry),
            enabled = entry.enabled,
            startAction = entry.icon?.let { icon -> { SettingsEntryIcon(icon) } },
            onClick = {
                hapticTap()
                entry.onClick()
            }
        )

        is SettingsEntry.Switch -> SwitchPreference(
            title = entry.title ?: stringResource(entry.titleRes),
            summary = settingsEntrySummary(entry),
            enabled = entry.enabled,
            checked = entry.checked,
            startAction = entry.icon?.let { icon -> { SettingsEntryIcon(icon) } },
            onCheckedChange = { checked ->
                hapticTap()
                entry.onCheckedChange(checked)
            }
        )

        is SettingsEntry.Dropdown -> {
            val selectedIndex = entry.options.indexOfFirst { it.id == entry.selectedOptionId }
                .takeIf { it >= 0 }
                ?: 0
            OverlayDropdownPreference(
                title = entry.title ?: stringResource(entry.titleRes),
                summary = settingsEntrySummary(entry),
                enabled = entry.enabled,
                items = entry.options.map { it.label },
                selectedIndex = selectedIndex,
                startAction = entry.icon?.let { icon -> { SettingsEntryIcon(icon) } },
                onSelectedIndexChange = { index ->
                    entry.options.getOrNull(index)?.let { option ->
                        hapticTap()
                        entry.onSelectedOptionChange(option.id)
                    }
                }
            )
        }
    }
}

@Composable
private fun SettingsEntryIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp),
        tint = MiuixTheme.colorScheme.onBackground
    )
}

@Composable
private fun settingsEntrySummary(entry: SettingsEntry): String? = entry.summary ?: entry.summaryRes?.let { stringResource(it) }
