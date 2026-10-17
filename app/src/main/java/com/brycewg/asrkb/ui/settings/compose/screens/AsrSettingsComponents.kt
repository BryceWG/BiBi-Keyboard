/**
 * Compose 语音识别设置页通用 UI 组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsHighlightContainer
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionContainer
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSliderPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsTextField
import com.brycewg.asrkb.ui.settings.compose.components.SettingsValuePreference
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalBibiSettingsDark
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AsrScaffold(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    content: @Composable (PaddingValues, Modifier) -> Unit
) {
    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.title_asr_settings,
        onBack = onBack,
        content = content
    )
}

@Composable
internal fun AsrSection(
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
internal fun AsrTextField(
    uiMode: BibiUiMode,
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    highlightId: String? = null,
    index: Int = 0,
    count: Int = 1
) {
    val content: @Composable () -> Unit = {
        SettingsTextField(
            uiMode = uiMode,
            value = value,
            onValueChange = onValueChange,
            label = label,
            modifier = modifier,
            password = password,
            singleLine = singleLine,
            minLines = minLines,
            enabled = enabled,
            keyboardType = keyboardType,
            index = index,
            count = count
        )
    }
    if (highlightId == null) {
        content()
    } else {
        SettingsHighlightContainer(entryId = highlightId, uiMode = uiMode, content = content)
    }
}

@Composable
internal fun AsrValuePreference(
    titleRes: Int,
    value: String,
    uiMode: BibiUiMode,
    highlightId: String? = null,
    index: Int = 0,
    count: Int = 1,
    onClick: () -> Unit
) {
    val content: @Composable () -> Unit = {
        SettingsValuePreference(
            titleRes = titleRes,
            value = value,
            uiMode = uiMode,
            index = index,
            count = count,
            onClick = onClick
        )
    }
    if (highlightId == null) {
        content()
    } else {
        SettingsHighlightContainer(entryId = highlightId, uiMode = uiMode, content = content)
    }
}

@Composable
internal fun AsrDropdownPreference(
    id: String? = null,
    titleRes: Int,
    options: List<DropdownOption>,
    selectedOptionId: String,
    index: Int = 0,
    count: Int = 1,
    onSelectedOptionChange: (String) -> Unit
) {
    SettingsPreference(
        entry = SettingsEntry.Dropdown(
            id = id ?: "asr_dropdown_$titleRes",
            titleRes = titleRes,
            options = options,
            selectedOptionId = selectedOptionId,
            onSelectedOptionChange = onSelectedOptionChange
        ),
        index = index,
        count = count
    )
}

@Composable
internal fun AsrSwitchPreference(
    id: String,
    titleRes: Int,
    checked: Boolean,
    index: Int = 0,
    count: Int = 1,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsPreference(
        entry = SettingsEntry.Switch(
            id = id,
            titleRes = titleRes,
            checked = checked,
            onCheckedChange = onCheckedChange
        ),
        index = index,
        count = count
    )
}

@Composable
internal fun AsrActionPreference(
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
internal fun AsrBodyText(uiMode: BibiUiMode, textRes: Int, color: Color? = null) {
    AsrBodyText(uiMode = uiMode, text = stringResource(textRes), color = color)
}

@Composable
internal fun AsrBodyText(uiMode: BibiUiMode, text: String, color: Color? = null) {
    when (uiMode) {
        BibiUiMode.Material -> Text(
            text = text,
            color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            color = color ?: MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )
    }
}

@Composable
internal fun SiliconFlowPoweredByImage() {
    val drawableRes = if (LocalBibiSettingsDark.current) {
        R.drawable.powered_by_siliconflow_dark
    } else {
        R.drawable.powered_by_siliconflow_light
    }
    Image(
        painter = painterResource(drawableRes),
        contentDescription = stringResource(R.string.sf_powered_by_desc),
        contentScale = ContentScale.FillWidth,
        alignment = Alignment.CenterStart,
        modifier = Modifier
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .fillMaxWidth()
    )
}

@Composable
internal fun AsrSliderPreference(
    titleRes: Int,
    valueLabel: (Float) -> String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    uiMode: BibiUiMode,
    showKeyPoints: Boolean = steps in 1..10,
    startLabel: String? = null,
    endLabel: String? = null,
    highlightId: String? = null,
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
        startLabel = startLabel,
        endLabel = endLabel,
        highlightId = highlightId,
        index = index,
        count = count,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished
    )
}
