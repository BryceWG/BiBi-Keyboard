/**
 * 设置页录音测试 Compose 页面。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrParallelEngineDecision
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButton
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButtonRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSectionContainer
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import java.util.Locale
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator as MiuixLinearProgressIndicator
import top.yukonga.miuix.kmp.basic.ProgressIndicatorDefaults
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun RecordingTestScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    onOpenAsrSettings: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val prefs = remember(appContext) { Prefs(appContext) }
    val viewModel: RecordingTestViewModel = viewModel(
        factory = remember(appContext, prefs) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = RecordingTestViewModel(appContext, prefs) as T
            }
        }
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.refreshConfiguredAsr()
    }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.releasePageResources()
        }
    }

    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.title_recording_test,
        onBack = onBack,
        bottomBar = {
            RecordingTestBottomBar(
                uiMode = uiMode,
                isRecording = state.isRecording,
                onClick = viewModel::toggleRecording
            )
        }
    ) { innerPadding, scrollModifier ->
        SettingsLazyColumn(
            uiMode = uiMode,
            modifier = Modifier.fillMaxSize(),
            miuixScrollModifier = scrollModifier,
            contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
        ) {
            item("overview") {
                RecordingOverviewSection(uiMode = uiMode, state = state)
            }
            item("audio") {
                RecordingAudioSection(
                    uiMode = uiMode,
                    state = state,
                    onPlay = viewModel::replay,
                    onReset = viewModel::resetRecording
                )
            }
            item("pipeline") {
                RecognitionPipelineSection(
                    uiMode = uiMode,
                    state = state,
                    onPromptSelected = viewModel::selectPromptPreset,
                    onAiProcess = viewModel::processAi,
                    onOpenAsrSettings = onOpenAsrSettings
                )
            }
            item("results") {
                RecordingResultsSection(uiMode = uiMode, state = state)
            }
        }
    }
}

@Composable
private fun RecordingOverviewSection(
    uiMode: BibiUiMode,
    state: RecordingTestUiState
) {
    RecordingSection(uiMode = uiMode, titleRes = R.string.recording_test_latency_chain) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                RecordingStatusPill(uiMode = uiMode, state = state)
                Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    RecordingText(
                        uiMode = uiMode,
                        text = stringResource(R.string.recording_test_total_label),
                        style = RecordingTextStyle.Label
                    )
                    RecordingText(
                        uiMode = uiMode,
                        text = formatRecordingTestDuration(state.totalLatencyMs),
                        style = RecordingTextStyle.Display
                    )
                }
            }
            LatencySegments(uiMode = uiMode, state = state)
            RecordingMetricList {
                RecordingMetricRow(
                    uiMode = uiMode,
                    label = stringResource(R.string.recording_test_press_time),
                    value = formatRecordingTestTimestamp(state.pressWallTimeMs)
                )
                RecordingMetricRow(
                    uiMode = uiMode,
                    label = stringResource(R.string.recording_test_first_frame_time),
                    value = formatRecordingTestTimestamp(state.firstFrameWallTimeMs)
                )
            }
        }
    }
}

@Composable
private fun RecordingStatusPill(
    uiMode: BibiUiMode,
    state: RecordingTestUiState
) {
    val (text, icon, color) = when {
        state.isRecording -> Triple(
            stringResource(R.string.recording_test_status_recording),
            Icons.Rounded.GraphicEq,
            RecordingAccentColor.Primary
        )
        state.isTranscribing || state.isAiProcessing -> Triple(
            stringResource(R.string.recording_test_status_processing),
            Icons.Rounded.AutoFixHigh,
            RecordingAccentColor.Tertiary
        )
        state.hasAudio -> Triple(
            stringResource(R.string.recording_test_status_ready),
            Icons.Rounded.PlayArrow,
            RecordingAccentColor.Secondary
        )
        else -> Triple(
            stringResource(R.string.recording_test_status_idle),
            Icons.Rounded.Mic,
            RecordingAccentColor.Neutral
        )
    }
    val containerColor = recordingAccentContainerColor(uiMode, color)
    val contentColor = recordingAccentContentColor(uiMode, color)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(containerColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RecordingIcon(uiMode = uiMode, imageVector = icon, tint = contentColor, size = 18.dp)
        RecordingText(
            uiMode = uiMode,
            text = text,
            color = contentColor,
            style = RecordingTextStyle.LabelStrong
        )
    }
}

@Composable
private fun LatencySegments(
    uiMode: BibiUiMode,
    state: RecordingTestUiState
) {
    val segments = listOf(
        RecordingLatencySegment(
            label = stringResource(R.string.recording_test_segment_record),
            value = state.recordLatencyMs,
            color = RecordingLatencyBandColor.Green
        ),
        RecordingLatencySegment(
            label = stringResource(R.string.recording_test_segment_asr),
            value = state.asrLatencyMs,
            color = RecordingLatencyBandColor.Blue
        ),
        RecordingLatencySegment(
            label = stringResource(R.string.recording_test_segment_ai),
            value = state.aiLatencyMs,
            color = RecordingLatencyBandColor.Orange
        )
    )
    val knownTotal = segments.mapNotNull { it.value }.sum().coerceAtLeast(1L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(14.dp))
    ) {
        segments.forEach { segment ->
            val value = segment.value
            val hasLatency = value != null
            val weight = ((value ?: (knownTotal / 3L).coerceAtLeast(1L)).toFloat() / knownTotal)
                .coerceAtLeast(0.24f)
            val segmentColor = recordingLatencySegmentColor(uiMode, segment.color, hasLatency)
            val contentColor = recordingLatencySegmentContentColor(uiMode, segment.color, hasLatency)
            Column(
                modifier = Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .background(segmentColor)
                    .padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                RecordingText(
                    uiMode = uiMode,
                    text = segment.label,
                    color = contentColor,
                    style = RecordingTextStyle.Label,
                    maxLines = 1
                )
                RecordingText(
                    uiMode = uiMode,
                    text = formatRecordingTestDuration(value),
                    color = contentColor,
                    style = RecordingTextStyle.Caption,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun RecordingAudioSection(
    uiMode: BibiUiMode,
    state: RecordingTestUiState,
    onPlay: () -> Unit,
    onReset: () -> Unit
) {
    RecordingSection(uiMode = uiMode, titleRes = R.string.recording_test_audio_title) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AudioLevelMeter(uiMode = uiMode, state = state)
            RecordingMetricList {
                RecordingMetricRow(
                    uiMode = uiMode,
                    label = stringResource(R.string.recording_test_duration_label),
                    value = String.format(Locale.US, "%.1fs", state.durationMs / 1_000.0)
                )
                RecordingMetricRow(
                    uiMode = uiMode,
                    label = stringResource(R.string.recording_test_current_level_label),
                    value = formatDb(state.currentDb)
                )
                RecordingMetricRow(
                    uiMode = uiMode,
                    label = stringResource(R.string.recording_test_peak_level_label),
                    value = formatDb(state.peakDb)
                )
                RecordingMetricRow(
                    uiMode = uiMode,
                    label = stringResource(R.string.recording_test_silence_label),
                    value = state.silentPercent?.let { percent ->
                        stringResource(R.string.recording_test_percent_value, percent)
                    } ?: "--"
                )
            }
            SettingsActionButtonRow(uiMode = uiMode, padded = false) {
                SettingsActionButton(
                    uiMode = uiMode,
                    text = if (state.isPlaying) {
                        stringResource(R.string.recording_test_playing)
                    } else {
                        stringResource(R.string.recording_test_play)
                    },
                    enabled = state.canPlay,
                    leadingIcon = Icons.Rounded.PlayArrow,
                    onClick = onPlay,
                    modifier = Modifier.weight(1f)
                )
                SettingsActionButton(
                    uiMode = uiMode,
                    text = stringResource(R.string.recording_test_rerecord),
                    enabled = state.hasAudio && !state.isRecording,
                    leadingIcon = Icons.Rounded.Refresh,
                    onClick = onReset,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun AudioLevelMeter(
    uiMode: BibiUiMode,
    state: RecordingTestUiState
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RecordingMeterRow(
            uiMode = uiMode,
            label = stringResource(R.string.recording_test_current_level_label),
            value = formatDb(state.currentDb),
            progress = levelProgress(state.currentDb),
            color = RecordingAccentColor.Primary
        )
        RecordingMeterRow(
            uiMode = uiMode,
            label = stringResource(R.string.recording_test_peak_level_label),
            value = formatDb(state.peakDb),
            progress = levelProgress(state.peakDb),
            color = RecordingAccentColor.Secondary
        )
    }
}

@Composable
private fun RecordingMeterRow(
    uiMode: BibiUiMode,
    label: String,
    value: String,
    progress: Float,
    color: RecordingAccentColor
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RecordingText(uiMode = uiMode, text = label, style = RecordingTextStyle.Caption)
            RecordingText(uiMode = uiMode, text = value, style = RecordingTextStyle.CaptionStrong)
        }
        RecordingProgressIndicator(
            uiMode = uiMode,
            progress = progress,
            color = color
        )
    }
}

@Composable
private fun RecognitionPipelineSection(
    uiMode: BibiUiMode,
    state: RecordingTestUiState,
    onPromptSelected: (String) -> Unit,
    onAiProcess: () -> Unit,
    onOpenAsrSettings: () -> Unit
) {
    val context = LocalContext.current
    val promptOptions = remember(state.promptPresets) {
        state.promptPresets.map { preset ->
            DropdownOption(
                id = preset.id,
                label = preset.title.takeIf { it.isNotBlank() } ?: preset.id
            )
        }
    }
    val selectedPromptId = state.selectedPromptId.takeIf { id -> promptOptions.any { it.id == id } }
        ?: promptOptions.firstOrNull()?.id
        ?: ""
    val noPromptLabel = stringResource(R.string.recording_test_no_prompt)

    RecordingSection(uiMode = uiMode, titleRes = R.string.recording_test_recognition_title) {
        SettingsPreference(
            entry = SettingsEntry.Action(
                id = "recording_test_asr",
                titleRes = R.string.recording_test_asr_label,
                summary = configuredAsrSummary(context, state),
                icon = Icons.Rounded.Mic,
                enabled = !state.isRecording && !state.isTranscribing && !state.isAiProcessing,
                onClick = onOpenAsrSettings
            ),
            index = 0,
            count = 2
        )
        SettingsPreference(
            entry = SettingsEntry.Dropdown(
                id = "recording_test_prompt",
                titleRes = R.string.recording_test_ai_label,
                summary = stringResource(R.string.recording_test_prompt_summary),
                icon = Icons.Rounded.AutoFixHigh,
                enabled = promptOptions.isNotEmpty(),
                options = promptOptions.ifEmpty {
                    listOf(DropdownOption("", noPromptLabel))
                },
                selectedOptionId = selectedPromptId,
                onSelectedOptionChange = onPromptSelected
            ),
            index = 1,
            count = 2
        )
        SettingsActionButtonRow(uiMode = uiMode) {
            SettingsActionButton(
                uiMode = uiMode,
                text = stringResource(R.string.recording_test_auto_transcribe),
                enabled = false,
                leadingIcon = if (state.isTranscribing) null else Icons.Rounded.TextFields,
                leadingContent = if (state.isTranscribing) {
                    { RecordingBusyIndicator() }
                } else {
                    null
                },
                onClick = {},
                modifier = Modifier.weight(1f)
            )
            SettingsActionButton(
                uiMode = uiMode,
                text = stringResource(R.string.recording_test_ai_process),
                enabled = state.canAiProcess,
                leadingIcon = if (state.isAiProcessing) null else Icons.Rounded.AutoFixHigh,
                leadingContent = if (state.isAiProcessing) {
                    { RecordingBusyIndicator() }
                } else {
                    null
                },
                onClick = onAiProcess,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun configuredAsrSummary(
    context: android.content.Context,
    state: RecordingTestUiState
): String {
    val primary = AsrVendorUi.name(context, state.currentAsrVendor)
    val mode = when (state.asrMode) {
        RecordingTestAsrMode.PushPcm -> stringResource(R.string.recording_test_asr_mode_push_pcm)
        RecordingTestAsrMode.File -> stringResource(R.string.recording_test_asr_mode_file)
    }
    val backup = state.backupAsrVendor?.let { vendor ->
        val strategy = state.backupAsrStrategy?.let { backupAsrStrategyLabel(it) }.orEmpty()
        stringResource(R.string.recording_test_asr_backup_suffix, AsrVendorUi.name(context, vendor), strategy)
    } ?: ""
    return stringResource(R.string.recording_test_asr_summary, primary, mode, backup)
}

@Composable
private fun backupAsrStrategyLabel(strategy: AsrParallelEngineDecision): String = when (strategy) {
    AsrParallelEngineDecision.UseParallel -> stringResource(R.string.recording_test_backup_strategy_parallel)
    AsrParallelEngineDecision.UseLazyLocalBackup -> stringResource(R.string.recording_test_backup_strategy_lazy)
    AsrParallelEngineDecision.UsePrimaryOnly -> ""
}

@Composable
private fun RecordingResultsSection(
    uiMode: BibiUiMode,
    state: RecordingTestUiState
) {
    RecordingSection(uiMode = uiMode, titleRes = R.string.recording_test_result_compare) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ResultBlock(
                uiMode = uiMode,
                label = stringResource(R.string.recording_test_raw_result),
                value = state.rawText.ifBlank { stringResource(R.string.recording_test_empty_result_placeholder) },
                icon = Icons.Rounded.TextFields,
                accent = RecordingAccentColor.Tertiary,
                empty = state.rawText.isBlank()
            )
            ResultBlock(
                uiMode = uiMode,
                label = stringResource(R.string.recording_test_ai_result),
                value = state.aiText.ifBlank { stringResource(R.string.recording_test_empty_result_placeholder) },
                icon = Icons.Rounded.AutoFixHigh,
                accent = RecordingAccentColor.Secondary,
                empty = state.aiText.isBlank()
            )
            state.statusMessage?.takeIf { it.isNotBlank() }?.let { message ->
                RecordingStatusMessage(uiMode = uiMode, message = message)
            }
        }
    }
}

@Composable
private fun ResultBlock(
    uiMode: BibiUiMode,
    label: String,
    value: String,
    icon: ImageVector,
    accent: RecordingAccentColor,
    empty: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(recordingNeutralContainerColor(uiMode))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RecordingIcon(
                uiMode = uiMode,
                imageVector = icon,
                tint = recordingAccentSolidColor(uiMode, accent),
                size = 18.dp
            )
            RecordingText(
                uiMode = uiMode,
                text = label,
                style = RecordingTextStyle.LabelStrong
            )
        }
        RecordingText(
            uiMode = uiMode,
            text = value,
            color = if (empty) recordingSecondaryTextColor(uiMode) else recordingPrimaryTextColor(uiMode),
            style = RecordingTextStyle.Body,
            maxLines = 5
        )
    }
}

@Composable
private fun RecordingStatusMessage(
    uiMode: BibiUiMode,
    message: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(recordingErrorContainerColor(uiMode))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        RecordingText(
            uiMode = uiMode,
            text = message,
            color = recordingErrorContentColor(uiMode),
            style = RecordingTextStyle.Body,
            maxLines = 3
        )
    }
}

@Composable
private fun RecordingMetricList(
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content
    )
}

@Composable
private fun RecordingMetricRow(
    uiMode: BibiUiMode,
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        RecordingText(
            uiMode = uiMode,
            text = label,
            color = recordingSecondaryTextColor(uiMode),
            style = RecordingTextStyle.Body,
            modifier = Modifier.weight(1f)
        )
        RecordingText(
            uiMode = uiMode,
            text = value,
            style = RecordingTextStyle.BodyStrong,
            maxLines = 1
        )
    }
}

@Composable
private fun RecordingSection(
    uiMode: BibiUiMode,
    titleRes: Int,
    content: @Composable ColumnScope.() -> Unit
) {
    SettingsSectionContainer(
        uiMode = uiMode,
        titleRes = titleRes
    ) {
        content()
    }
}

@Composable
private fun RecordingProgressIndicator(
    uiMode: BibiUiMode,
    progress: Float,
    color: RecordingAccentColor
) {
    val modifier = Modifier
        .fillMaxWidth()
        .height(8.dp)
        .clip(RoundedCornerShape(4.dp))
    when (uiMode) {
        BibiUiMode.Material -> LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier,
            color = recordingAccentSolidColor(uiMode, color),
            trackColor = recordingNeutralContainerColor(uiMode)
        )

        BibiUiMode.Miuix -> MiuixLinearProgressIndicator(
            progress = progress.coerceIn(0f, 1f),
            modifier = modifier,
            colors = ProgressIndicatorDefaults.progressIndicatorColors(
                foregroundColor = recordingAccentSolidColor(uiMode, color)
            )
        )
    }
}

@Composable
private fun RecordingTestBottomBar(
    uiMode: BibiUiMode,
    isRecording: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(
        topStart = SettingsLayoutMetrics.BottomBarTopCorner,
        topEnd = SettingsLayoutMetrics.BottomBarTopCorner
    )
    // 底栏背景继续绘制到系统导航栏下方，只给内容让出安全边距，
    // 否则 Edge-to-Edge 下录音按钮会被虚拟导航栏遮挡。
    val contentInsets = WindowInsets.systemBars
        .union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    when (uiMode) {
        BibiUiMode.Material -> Surface(
            shadowElevation = SettingsLayoutMetrics.BottomBarElevation,
            tonalElevation = SettingsLayoutMetrics.BottomBarElevation,
            shape = shape
        ) {
            RecordingTestBottomBarContent(
                uiMode = uiMode,
                isRecording = isRecording,
                onClick = onClick,
                contentInsets = contentInsets
            )
        }

        BibiUiMode.Miuix -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MiuixTheme.colorScheme.surfaceVariant)
        ) {
            RecordingTestBottomBarContent(
                uiMode = uiMode,
                isRecording = isRecording,
                onClick = onClick,
                contentInsets = contentInsets
            )
        }
    }
}

@Composable
private fun RecordingTestBottomBarContent(
    uiMode: BibiUiMode,
    isRecording: Boolean,
    onClick: () -> Unit,
    contentInsets: WindowInsets
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(contentInsets)
            .heightIn(min = SettingsLayoutMetrics.BottomBarMinHeight)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        RecordingTestRecordButton(
            uiMode = uiMode,
            isRecording = isRecording,
            onClick = onClick
        )
    }
}

@Composable
private fun RecordingTestRecordButton(
    uiMode: BibiUiMode,
    isRecording: Boolean,
    onClick: () -> Unit
) {
    SettingsActionButton(
        uiMode = uiMode,
        text = if (isRecording) {
            stringResource(R.string.recording_test_stop)
        } else {
            stringResource(R.string.recording_test_record)
        },
        leadingIcon = if (isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun RecordingBusyIndicator() {
    CircularProgressIndicator(
        modifier = Modifier.size(SettingsLayoutMetrics.ActionButtonIconSize),
        strokeWidth = 2.dp
    )
}

@Composable
private fun RecordingIcon(
    uiMode: BibiUiMode,
    imageVector: ImageVector,
    tint: Color,
    size: androidx.compose.ui.unit.Dp
) {
    when (uiMode) {
        BibiUiMode.Material -> Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(size),
            tint = tint
        )

        BibiUiMode.Miuix -> top.yukonga.miuix.kmp.basic.Icon(
            imageVector = imageVector,
            contentDescription = null,
            modifier = Modifier.size(size),
            tint = tint
        )
    }
}

@Composable
private fun RecordingText(
    uiMode: BibiUiMode,
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: RecordingTextStyle = RecordingTextStyle.Body,
    maxLines: Int = Int.MAX_VALUE
) {
    val resolvedColor = if (color == Color.Unspecified) {
        when (style) {
            RecordingTextStyle.Caption,
            RecordingTextStyle.Label -> recordingSecondaryTextColor(uiMode)
            else -> recordingPrimaryTextColor(uiMode)
        }
    } else {
        color
    }
    when (uiMode) {
        BibiUiMode.Material -> Text(
            text = text,
            modifier = modifier,
            color = resolvedColor,
            style = when (style) {
                RecordingTextStyle.Display -> MaterialTheme.typography.headlineSmall
                RecordingTextStyle.Body,
                RecordingTextStyle.BodyStrong -> MaterialTheme.typography.bodyMedium
                RecordingTextStyle.Label,
                RecordingTextStyle.LabelStrong -> MaterialTheme.typography.labelLarge
                RecordingTextStyle.Caption,
                RecordingTextStyle.CaptionStrong -> MaterialTheme.typography.labelSmall
            },
            fontWeight = when (style) {
                RecordingTextStyle.Display,
                RecordingTextStyle.BodyStrong,
                RecordingTextStyle.LabelStrong,
                RecordingTextStyle.CaptionStrong -> FontWeight.SemiBold
                else -> FontWeight.Normal
            },
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            modifier = modifier,
            color = resolvedColor,
            style = when (style) {
                RecordingTextStyle.Display -> MiuixTheme.textStyles.title2
                RecordingTextStyle.Body,
                RecordingTextStyle.BodyStrong -> MiuixTheme.textStyles.body2
                RecordingTextStyle.Label,
                RecordingTextStyle.LabelStrong -> MiuixTheme.textStyles.body1
                RecordingTextStyle.Caption,
                RecordingTextStyle.CaptionStrong -> MiuixTheme.textStyles.footnote1
            },
            fontWeight = when (style) {
                RecordingTextStyle.Display,
                RecordingTextStyle.BodyStrong,
                RecordingTextStyle.LabelStrong,
                RecordingTextStyle.CaptionStrong -> FontWeight.SemiBold
                else -> FontWeight.Normal
            },
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private data class RecordingLatencySegment(
    val label: String,
    val value: Long?,
    val color: RecordingLatencyBandColor
)

private enum class RecordingTextStyle {
    Display,
    Body,
    BodyStrong,
    Label,
    LabelStrong,
    Caption,
    CaptionStrong
}

private enum class RecordingAccentColor {
    Primary,
    Secondary,
    Tertiary,
    Neutral
}

@Composable
private fun recordingLatencySegmentColor(
    uiMode: BibiUiMode,
    bandColor: RecordingLatencyBandColor,
    completed: Boolean
): Color {
    val solidColor = when (bandColor) {
        RecordingLatencyBandColor.Green -> colorResource(R.color.recording_latency_green)
        RecordingLatencyBandColor.Blue -> colorResource(R.color.recording_latency_blue)
        RecordingLatencyBandColor.Orange -> colorResource(R.color.recording_latency_orange)
    }
    if (completed) return solidColor
    return when (uiMode) {
        BibiUiMode.Material -> solidColor.copy(alpha = 0.16f)
        BibiUiMode.Miuix -> solidColor.copy(alpha = 0.18f)
    }
}

@Composable
private fun recordingLatencySegmentContentColor(
    uiMode: BibiUiMode,
    bandColor: RecordingLatencyBandColor,
    completed: Boolean
): Color {
    if (!completed) return recordingSecondaryTextColor(uiMode)
    return when (bandColor) {
        RecordingLatencyBandColor.Green -> colorResource(R.color.recording_latency_on_green)
        RecordingLatencyBandColor.Blue -> colorResource(R.color.recording_latency_on_blue)
        RecordingLatencyBandColor.Orange -> colorResource(R.color.recording_latency_on_orange)
    }
}

private enum class RecordingLatencyBandColor {
    Green,
    Blue,
    Orange
}

@Composable
private fun recordingAccentSolidColor(
    uiMode: BibiUiMode,
    accent: RecordingAccentColor
): Color = when (uiMode) {
    BibiUiMode.Material -> when (accent) {
        RecordingAccentColor.Primary -> MaterialTheme.colorScheme.primary
        RecordingAccentColor.Secondary -> MaterialTheme.colorScheme.secondary
        RecordingAccentColor.Tertiary -> MaterialTheme.colorScheme.tertiary
        RecordingAccentColor.Neutral -> MaterialTheme.colorScheme.outline
    }
    BibiUiMode.Miuix -> when (accent) {
        RecordingAccentColor.Primary -> MiuixTheme.colorScheme.primary
        RecordingAccentColor.Secondary -> MiuixTheme.colorScheme.secondary
        RecordingAccentColor.Tertiary -> MiuixTheme.colorScheme.secondaryVariant
        RecordingAccentColor.Neutral -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }
}

@Composable
private fun recordingAccentContainerColor(
    uiMode: BibiUiMode,
    accent: RecordingAccentColor
): Color = when (uiMode) {
    BibiUiMode.Material -> when (accent) {
        RecordingAccentColor.Primary -> MaterialTheme.colorScheme.primaryContainer
        RecordingAccentColor.Secondary -> MaterialTheme.colorScheme.secondaryContainer
        RecordingAccentColor.Tertiary -> MaterialTheme.colorScheme.tertiaryContainer
        RecordingAccentColor.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    BibiUiMode.Miuix -> recordingAccentSolidColor(uiMode, accent).copy(alpha = 0.14f)
}

@Composable
private fun recordingAccentContentColor(
    uiMode: BibiUiMode,
    accent: RecordingAccentColor
): Color = when (uiMode) {
    BibiUiMode.Material -> when (accent) {
        RecordingAccentColor.Primary -> MaterialTheme.colorScheme.onPrimaryContainer
        RecordingAccentColor.Secondary -> MaterialTheme.colorScheme.onSecondaryContainer
        RecordingAccentColor.Tertiary -> MaterialTheme.colorScheme.onTertiaryContainer
        RecordingAccentColor.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    BibiUiMode.Miuix -> recordingAccentSolidColor(uiMode, accent)
}

@Composable
private fun recordingAccentOnSolidColor(
    uiMode: BibiUiMode,
    accent: RecordingAccentColor
): Color = when (uiMode) {
    BibiUiMode.Material -> when (accent) {
        RecordingAccentColor.Primary -> MaterialTheme.colorScheme.onPrimary
        RecordingAccentColor.Secondary -> MaterialTheme.colorScheme.onSecondary
        RecordingAccentColor.Tertiary -> MaterialTheme.colorScheme.onTertiary
        RecordingAccentColor.Neutral -> MaterialTheme.colorScheme.onSurface
    }
    BibiUiMode.Miuix -> MiuixTheme.colorScheme.onSurface
}

@Composable
private fun recordingNeutralContainerColor(uiMode: BibiUiMode): Color = when (uiMode) {
    BibiUiMode.Material -> MaterialTheme.colorScheme.surfaceContainerHighest
    BibiUiMode.Miuix -> MiuixTheme.colorScheme.surfaceVariant
}

@Composable
private fun recordingPrimaryTextColor(uiMode: BibiUiMode): Color = when (uiMode) {
    BibiUiMode.Material -> MaterialTheme.colorScheme.onSurface
    BibiUiMode.Miuix -> MiuixTheme.colorScheme.onSurface
}

@Composable
private fun recordingSecondaryTextColor(uiMode: BibiUiMode): Color = when (uiMode) {
    BibiUiMode.Material -> MaterialTheme.colorScheme.onSurfaceVariant
    BibiUiMode.Miuix -> MiuixTheme.colorScheme.onSurfaceVariantSummary
}

@Composable
private fun recordingErrorContainerColor(uiMode: BibiUiMode): Color = when (uiMode) {
    BibiUiMode.Material -> MaterialTheme.colorScheme.errorContainer
    BibiUiMode.Miuix -> MiuixTheme.colorScheme.error.copy(alpha = 0.14f)
}

@Composable
private fun recordingErrorContentColor(uiMode: BibiUiMode): Color = when (uiMode) {
    BibiUiMode.Material -> MaterialTheme.colorScheme.onErrorContainer
    BibiUiMode.Miuix -> MiuixTheme.colorScheme.error
}

private fun levelProgress(value: Float?): Float {
    if (value == null) return 0f
    return ((value + 60f) / 60f).coerceIn(0f, 1f)
}

private fun formatDb(value: Float?): String = value?.let {
    String.format(Locale.US, "%.0fdB", it)
} ?: "--dB"
