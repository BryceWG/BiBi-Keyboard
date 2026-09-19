/**
 * Compose AI 设置页通用 UI 组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButton
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButtonRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionContainer
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSliderPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsTextField
import com.brycewg.asrkb.ui.settings.compose.components.SettingsValuePreference
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AiScaffold(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.title_ai_settings,
        onBack = onBack,
        content = content
    )
}

@Composable
internal fun AiSection(
    uiMode: BibiUiMode,
    titleRes: Int,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsSectionContainer(
        uiMode = uiMode,
        titleRes = titleRes,
        content = content
    )
}

@Composable
internal fun AiTextField(
    uiMode: BibiUiMode,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    password: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardType: KeyboardType = KeyboardType.Text,
    index: Int = 0,
    count: Int = 1
) {
    SettingsTextField(
        uiMode = uiMode,
        value = value,
        onValueChange = onValueChange,
        label = label,
        modifier = modifier,
        enabled = enabled,
        password = password,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardType = keyboardType,
        index = index,
        count = count
    )
}

@Composable
internal fun AiBodyText(uiMode: BibiUiMode, textRes: Int) {
    AiBodyText(uiMode = uiMode, text = stringResource(textRes))
}

@Composable
internal fun AiBodyText(uiMode: BibiUiMode, text: String) {
    when (uiMode) {
        BibiUiMode.Material -> Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )
    }
}

@Composable
internal fun AiValuePreference(
    titleRes: Int,
    value: String,
    uiMode: BibiUiMode,
    index: Int = 0,
    count: Int = 1,
    trailingActionIcon: ImageVector? = null,
    trailingActionContentDescriptionRes: Int? = null,
    onTrailingActionClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    SettingsValuePreference(
        titleRes = titleRes,
        value = value,
        uiMode = uiMode,
        index = index,
        count = count,
        trailingActionIcon = trailingActionIcon,
        trailingActionContentDescriptionRes = trailingActionContentDescriptionRes,
        onTrailingActionClick = onTrailingActionClick,
        onClick = onClick
    )
}

@Composable
internal fun AiSliderPreference(
    titleRes: Int,
    valueLabel: (Float) -> String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    uiMode: BibiUiMode,
    showKeyPoints: Boolean = steps in 1..10,
    index: Int = 0,
    count: Int = 1,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit = { _ -> }
) {
    SettingsSliderPreference(
        uiMode = uiMode,
        title = stringResource(titleRes),
        valueLabel = valueLabel,
        value = value.coerceIn(valueRange.start, valueRange.endInclusive),
        valueRange = valueRange,
        steps = steps,
        showKeyPoints = showKeyPoints,
        index = index,
        count = count,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished
    )
}

@Composable
internal fun AiSwitchPreference(
    id: String,
    titleRes: Int,
    checked: Boolean,
    summaryRes: Int? = null,
    index: Int = 0,
    count: Int = 1,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsPreference(
        entry = SettingsEntry.Switch(
            id = id,
            titleRes = titleRes,
            summaryRes = summaryRes,
            checked = checked,
            onCheckedChange = onCheckedChange
        ),
        index = index,
        count = count
    )
}

@Composable
internal fun AiActionPreference(
    id: String,
    titleRes: Int,
    index: Int = 0,
    count: Int = 1,
    onClick: () -> Unit
) {
    SettingsPreference(
        entry = SettingsEntry.Action(
            id = id,
            titleRes = titleRes,
            onClick = onClick
        ),
        index = index,
        count = count
    )
}

@Composable
internal fun AiButtonRow(
    uiMode: BibiUiMode,
    content: @Composable RowScope.() -> Unit
) {
    SettingsActionButtonRow(uiMode = uiMode, content = content)
}

@Composable
internal fun RowScope.AiButton(
    uiMode: BibiUiMode,
    textRes: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
    icon: @Composable (() -> Unit)? = null
) {
    SettingsActionButton(
        uiMode = uiMode,
        text = stringResource(textRes),
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(1f),
        leadingContent = icon
    )
}
