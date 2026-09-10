/**
 * Compose 设置页滑块组件，统一 Material 与 Miuix 的控制项布局。
 * 拖动过程只更新本地显示，松手后才把最终值交给调用方，避免中间步进反复保存。
 * Miuix 无障碍 setProgress 没有 finished 回调，无指针按下时视为一次完整提交。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsSliderPreference(
    uiMode: BibiUiMode,
    title: String,
    valueLabel: (Float) -> String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    showKeyPoints: Boolean = steps in 1..10,
    startLabel: String? = null,
    endLabel: String? = null,
    highlightId: String? = null,
    index: Int = 0,
    count: Int = 1,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit = { _ -> }
) {
    val hapticTap = LocalSettingsHapticTap.current
    var sliderValue by remember { mutableFloatStateOf(value) }
    var isEditing by remember { mutableStateOf(false) }
    val miuixPointerPressed = remember { mutableStateOf(false) }
    val latestValue by rememberUpdatedState(value)
    val latestOnValueChange by rememberUpdatedState(onValueChange)
    val latestOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)

    LaunchedEffect(value) {
        if (!isEditing) {
            sliderValue = value
        }
    }

    val finishWithHaptic = {
        val committed = sliderValue
        isEditing = false
        if (committed != latestValue) {
            latestOnValueChange(committed)
        }
        latestOnValueChangeFinished(committed)
        hapticTap()
    }
    val sliderBottomPadding = if (index == count - 1) {
        SettingsLayoutMetrics.SliderLastItemBottomPadding
    } else {
        SettingsLayoutMetrics.SliderBottomPadding
    }
    val displayLabel = valueLabel(sliderValue)
    val sliderA11yModifier = Modifier.semantics { stateDescription = displayLabel }
    val content: @Composable () -> Unit = {
        when (uiMode) {
            BibiUiMode.Material -> SettingsMaterialItemSurface(index = index, count = count) {
                SettingsControlLabel(
                    uiMode = uiMode,
                    title = title,
                    value = displayLabel
                )
                Slider(
                    value = sliderValue,
                    onValueChange = { next ->
                        isEditing = true
                        sliderValue = next
                    },
                    onValueChangeFinished = finishWithHaptic,
                    valueRange = valueRange,
                    steps = steps,
                    colors = if (showKeyPoints) {
                        SliderDefaults.colors()
                    } else {
                        SliderDefaults.colors(
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SettingsLayoutMetrics.SliderHorizontalPadding)
                        .padding(bottom = sliderBottomPadding)
                        .then(sliderA11yModifier)
                )
                SettingsSliderScaleLabels(uiMode, startLabel, endLabel)
            }

            BibiUiMode.Miuix -> {
                SettingsControlLabel(
                    uiMode = uiMode,
                    title = title,
                    value = displayLabel
                )
                MiuixSlider(
                    value = sliderValue,
                    onValueChange = { next ->
                        // Miuix 0.9.1 无障碍 setProgress 只回调 onValueChange，不回调
                        // onValueChangeFinished。手指拖动时指针已按下，仍只预览；
                        // 无指针则视为无障碍调整，立即提交并清掉编辑状态。
                        if (miuixPointerPressed.value) {
                            isEditing = true
                            sliderValue = next
                        } else {
                            sliderValue = next
                            finishWithHaptic()
                        }
                    },
                    onValueChangeFinished = finishWithHaptic,
                    valueRange = valueRange,
                    steps = steps,
                    showKeyPoints = showKeyPoints,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SettingsLayoutMetrics.SliderHorizontalPadding)
                        .padding(bottom = sliderBottomPadding)
                        .pointerInput(Unit) {
                            try {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        miuixPointerPressed.value = event.changes.any { it.pressed }
                                    }
                                }
                            } finally {
                                miuixPointerPressed.value = false
                            }
                        }
                        .then(sliderA11yModifier)
                )
                SettingsSliderScaleLabels(uiMode, startLabel, endLabel)
            }
        }
    }
    if (highlightId == null) {
        content()
    } else {
        SettingsHighlightContainer(entryId = highlightId, uiMode = uiMode, content = content)
    }
}

@Composable
private fun SettingsSliderScaleLabels(
    uiMode: BibiUiMode,
    startLabel: String?,
    endLabel: String?
) {
    if (startLabel == null && endLabel == null) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsLayoutMetrics.SliderHorizontalPadding,
                vertical = SettingsLayoutMetrics.ControlLabelVerticalPadding
            ),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        when (uiMode) {
            BibiUiMode.Material -> {
                Text(
                    text = startLabel.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = endLabel.orEmpty(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            BibiUiMode.Miuix -> {
                MiuixText(
                    text = startLabel.orEmpty(),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2
                )
                MiuixText(
                    text = endLabel.orEmpty(),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2
                )
            }
        }
    }
}

@Composable
internal fun SettingsControlLabel(
    uiMode: BibiUiMode,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = SettingsLayoutMetrics.ControlLabelHorizontalPadding,
                vertical = SettingsLayoutMetrics.ControlLabelVerticalPadding
            ),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        when (uiMode) {
            BibiUiMode.Material -> {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.size(SettingsLayoutMetrics.ControlLabelSpacing))
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clearAndSetSemantics {
                        // 数值由 Slider 的 stateDescription 朗读，避免重复播报
                    }
                )
            }

            BibiUiMode.Miuix -> {
                MiuixText(
                    text = title,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    style = MiuixTheme.textStyles.headline1
                )
                Spacer(Modifier.size(SettingsLayoutMetrics.ControlLabelSpacing))
                MiuixText(
                    text = value,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                    modifier = Modifier.clearAndSetSemantics {
                        // 数值由 Slider 的 stateDescription 朗读，避免重复播报
                    }
                )
            }
        }
    }
}
