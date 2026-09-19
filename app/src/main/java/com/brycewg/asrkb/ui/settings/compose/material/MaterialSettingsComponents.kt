/**
 * Material 风格设置组件。
 *
 * 归属模块：ui/settings/compose/material
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.material

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMaterialItemSurface
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.core.settingsSegmentedItemShape
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry

@Composable
fun MaterialSettingsEntry(
    entry: SettingsEntry,
    index: Int = 0,
    count: Int = 1
) {
    val shape = settingsSegmentedItemShape(index, count)
    when (entry) {
        is SettingsEntry.Action -> MaterialActionEntry(entry, shape)
        is SettingsEntry.Switch -> MaterialSwitchEntry(entry, shape)
        is SettingsEntry.Dropdown -> MaterialDropdownEntry(entry, shape)
    }
}

@Composable
private fun MaterialActionEntry(
    entry: SettingsEntry.Action,
    shape: Shape
) {
    val hapticTap = LocalSettingsHapticTap.current
    MaterialSegmentedSurface(
        shape = shape,
        modifier = Modifier.clickable(
            enabled = entry.enabled,
            role = Role.Button,
            onClick = {
                hapticTap()
                entry.onClick()
            }
        )
    ) {
        MaterialEntryListItem(entry)
    }
}

@Composable
private fun MaterialSwitchEntry(
    entry: SettingsEntry.Switch,
    shape: Shape
) {
    val hapticTap = LocalSettingsHapticTap.current
    val interactionSource = remember { MutableInteractionSource() }
    MaterialSegmentedSurface(
        shape = shape,
        modifier = Modifier.toggleable(
            value = entry.checked,
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            enabled = entry.enabled,
            role = Role.Switch,
            onValueChange = { checked ->
                hapticTap()
                entry.onCheckedChange(checked)
            }
        )
    ) {
        MaterialEntryListItem(
            entry = entry,
            trailingContent = {
                MaterialExpressiveSwitch(
                    checked = entry.checked,
                    onCheckedChange = null,
                    enabled = entry.enabled,
                    interactionSource = interactionSource
                )
            }
        )
    }
}

@Composable
private fun MaterialDropdownEntry(
    entry: SettingsEntry.Dropdown,
    shape: Shape
) {
    var expanded by remember { mutableStateOf(false) }
    val hapticTap = LocalSettingsHapticTap.current

    MaterialSegmentedSurface(
        shape = shape,
        modifier = Modifier.clickable(
            enabled = entry.enabled && entry.options.isNotEmpty(),
            role = Role.Button,
            onClick = {
                hapticTap()
                expanded = true
            }
        )
    ) {
        MaterialEntryListItem(
            entry = entry,
            trailingContent = {
                MaterialDropdownTrailingContent(
                    entry = entry,
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                )
            }
        )
    }
}

@Composable
private fun MaterialSegmentedSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    SettingsMaterialItemSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(modifier),
        shape = shape,
        content = content
    )
}

@Composable
private fun MaterialEntryListItem(
    entry: SettingsEntry,
    trailingContent: (@Composable () -> Unit)? = null
) {
    ListItem(
        modifier = Modifier.heightIn(min = SettingsLayoutMetrics.SettingsPreferenceMinHeight),
        headlineContent = { MaterialEntryHeadline(entry) },
        supportingContent = settingsEntrySummary(entry)?.let { summary ->
            { MaterialEntrySupporting(entry, summary) }
        },
        leadingContent = entry.icon?.let { { MaterialEntryIcon(entry) } },
        trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun MaterialEntryHeadline(entry: SettingsEntry) {
    val color = if (entry.enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
    }
    Text(
        text = entry.title ?: stringResource(entry.titleRes),
        color = color,
        style = MaterialTheme.typography.bodyLarge
    )
}

@Composable
private fun MaterialEntrySupporting(entry: SettingsEntry, text: String) {
    val color = if (entry.enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA)
    }
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun settingsEntrySummary(entry: SettingsEntry): String? = entry.summary ?: entry.summaryRes?.let { stringResource(it) }

@Composable
private fun MaterialEntryIcon(entry: SettingsEntry) {
    val icon = entry.icon ?: return
    val color = if (entry.enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA)
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = Modifier.size(SettingsLayoutMetrics.SettingsPreferenceIconSize),
        tint = color
    )
}

@Composable
private fun MaterialDropdownTrailingContent(
    entry: SettingsEntry.Dropdown,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit
) {
    val hapticTap = LocalSettingsHapticTap.current
    val selectedOption = entry.options.firstOrNull { it.id == entry.selectedOptionId }
    Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
        Text(
            text = selectedOption?.label.orEmpty(),
            modifier = Modifier.widthIn(max = SettingsLayoutMetrics.SettingsPreferenceTrailingMaxWidth),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) }
        ) {
            entry.options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.label,
                            color = if (option.id == entry.selectedOptionId) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    },
                    onClick = {
                        hapticTap()
                        onExpandedChange(false)
                        entry.onSelectedOptionChange(option.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun MaterialExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        thumbContent = {
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier.size(SwitchDefaults.IconSize)
                )
            }
        },
        enabled = enabled,
        colors = colors,
        interactionSource = interactionSource
    )
}

private const val DISABLED_CONTENT_ALPHA = 0.38f
