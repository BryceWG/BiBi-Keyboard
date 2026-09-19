package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsSheetScaffold
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AiModelAdvancedParamsDialog(
    visible: Boolean,
    uiMode: BibiUiMode,
    showTemperature: Boolean,
    temperature: Float,
    temperatureRange: ClosedFloatingPointRange<Float>,
    temperatureSteps: Int,
    onTemperatureChange: (Float) -> Unit,
    onTemperatureChangeFinished: (Float) -> Unit = {},
    showReasoning: Boolean,
    reasoningThreshold: Int,
    showCustomReasoningParams: Boolean,
    customReasoningParamsEnabled: Boolean,
    reasoningOnJson: String,
    reasoningOffJson: String,
    onReasoningChange: (Int) -> Unit,
    onCustomReasoningParamsEnabledChange: (Boolean) -> Unit,
    onReasoningOnJsonChange: (String) -> Unit,
    onReasoningOffJsonChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    @Composable
    fun Content() {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = SettingsLayoutMetrics.SheetContentMaxHeight)
                .verticalScroll(rememberScrollState())
        ) {
            if (showTemperature) {
                AiSliderPreference(
                    titleRes = R.string.label_llm_temperature,
                    valueLabel = { formatTemperature(it) },
                    value = temperature,
                    valueRange = temperatureRange,
                    steps = temperatureSteps,
                    uiMode = uiMode,
                    onValueChange = onTemperatureChange,
                    onValueChangeFinished = onTemperatureChangeFinished
                )
            }
            if (showReasoning) {
                ReasoningSection(
                    uiMode = uiMode,
                    threshold = reasoningThreshold,
                    showParams = showCustomReasoningParams,
                    customParamsEnabled = customReasoningParamsEnabled,
                    onThresholdChange = onReasoningChange,
                    onCustomParamsEnabledChange = onCustomReasoningParamsEnabledChange,
                    onJson = reasoningOnJson,
                    offJson = reasoningOffJson,
                    onOnJsonChange = onReasoningOnJsonChange,
                    onOffJsonChange = onReasoningOffJsonChange,
                    index = 0,
                    count = 1
                )
            }
        }
    }

    when (uiMode) {
        BibiUiMode.Material -> MaterialSettingsSheetScaffold(
            title = stringResource(R.string.label_llm_more_params),
            onDismiss = onDismiss,
            bottomPadding = 0.dp
        ) {
            Content()
        }

        BibiUiMode.Miuix -> {
            var show by remember { mutableStateOf(true) }
            OverlayDialog(
                show = show,
                onDismissRequest = { show = false },
                onDismissFinished = onDismiss,
                insideMargin = DpSize(0.dp, 0.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    MiuixText(
                        text = stringResource(R.string.label_llm_more_params),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                top = SettingsLayoutMetrics.SheetDialogTitleTopPadding,
                                bottom = SettingsLayoutMetrics.SheetTitleBottomPadding
                            ),
                        color = MiuixTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium,
                        style = MiuixTheme.textStyles.title4
                    )
                    Content()
                }
            }
        }
    }
}
