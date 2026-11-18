/**
 * Compose 其他设置页路由内容。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.floating.KeepAliveNotificationClick
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMaterialItemSurface
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSliderPreference
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import com.brycewg.asrkb.ui.settings.other.OtherSettingsViewModel

@Composable
internal fun OtherSettingsRouteContent(
    uiMode: BibiUiMode,
    innerPadding: PaddingValues,
    scrollModifier: Modifier,
    uiState: OtherSettingsUiState,
    downloadMirrors: List<String>,
    onOpenDownloadMirrorEditor: (String) -> Unit,
    punctuation: PunctuationFields,
    speechState: OtherSettingsViewModel.SpeechPresetsState,
    syncState: OtherSettingsViewModel.SyncClipboardState,
    focusNameAfterAdd: Boolean,
    onFocusNameHandled: () -> Unit,
    onKeepAliveToggle: (Boolean) -> Unit,
    onKeepAliveNotificationClickRouteChange: (String) -> Unit,
    onPrivilegedKeepAliveToggle: (Boolean) -> Unit,
    onRequestBatteryWhitelist: () -> Unit,
    onDisableAsrHistoryToggle: (Boolean) -> Unit,
    onAudioRetentionChange: (Int) -> Unit,
    onAudioRetentionChangeFinished: () -> Unit,
    onDisableUsageStatsToggle: (Boolean) -> Unit,
    onDataCollectionToggle: (Boolean) -> Unit,
    onClearClipboardHistory: () -> Unit,
    onPunct1Change: (String) -> Unit,
    onPunct2Change: (String) -> Unit,
    onPunct3Change: (String) -> Unit,
    onPunct4Change: (String) -> Unit,
    onSpeechPresetPicker: () -> Unit,
    onUpdateSpeechPresetName: (String) -> Unit,
    onUpdateSpeechPresetContent: (String) -> Unit,
    onAddSpeechPreset: () -> Unit,
    onDeleteSpeechPreset: () -> Unit,
    onSyncClipboardEnabledChange: (Boolean) -> Unit,
    onSyncClipboardServerChange: (String) -> Unit,
    onSyncClipboardUsernameChange: (String) -> Unit,
    onSyncClipboardPasswordChange: (String) -> Unit,
    onSyncClipboardAutoReceiveChange: (Boolean) -> Unit,
    onSyncClipboardKeepBackgroundChange: (Boolean) -> Unit,
    onSyncClipboardIntervalChange: (Int) -> Unit,
    onSyncClipboardImagesChange: (Boolean) -> Unit,
    onSyncClipboardFilesChange: (Boolean) -> Unit,
    onSyncClipboardAttachmentMaxSizeChange: (Int) -> Unit,
    onChooseSyncClipboardWatchTree: () -> Unit,
    onClearSyncClipboardWatchTree: () -> Unit,
    onTestClipboardSync: () -> Unit,
    onOpenSyncClipboardProject: () -> Unit
) {
    SettingsLazyColumn(
        uiMode = uiMode,
        modifier = Modifier.fillMaxSize(),
        miuixScrollModifier = scrollModifier,
        contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
        verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
    ) {
        item("keep_alive") {
            val keepAliveItemCount = if (uiState.keepAliveEnabled) 3 else 2
            val notificationClickOptions = KeepAliveNotificationClick.destinations.map { dest ->
                DropdownOption(dest.route.id, stringResource(dest.titleRes))
            }
            OtherSection(uiMode = uiMode, titleRes = R.string.section_general) {
                OtherExplainedSwitch(
                    id = "floating_keep_alive",
                    titleRes = R.string.label_floating_keep_alive_foreground,
                    checked = uiState.keepAliveEnabled,
                    onToggle = onKeepAliveToggle,
                    index = 0,
                    count = keepAliveItemCount
                )
                if (uiState.keepAliveEnabled) {
                    SettingsPreference(
                        entry = SettingsEntry.Dropdown(
                            id = "keep_alive_notification_click",
                            titleRes = R.string.label_keep_alive_notification_click,
                            options = notificationClickOptions,
                            selectedOptionId = uiState.keepAliveNotificationClickRoute,
                            onSelectedOptionChange = onKeepAliveNotificationClickRouteChange
                        ),
                        index = 1,
                        count = keepAliveItemCount
                    )
                }
                OtherExplainedSwitch(
                    id = "floating_keep_alive_privileged",
                    titleRes = R.string.label_floating_keep_alive_privileged,
                    checked = uiState.privilegedKeepAliveEnabled,
                    onToggle = onPrivilegedKeepAliveToggle,
                    index = if (uiState.keepAliveEnabled) 2 else 1,
                    count = keepAliveItemCount
                )
                OtherButton(
                    text = stringResource(R.string.label_request_battery_whitelist),
                    uiMode = uiMode,
                    onClick = onRequestBatteryWhitelist
                )
            }
        }

        item("download_mirrors") {
            OtherDownloadMirrorsSection(
                uiMode = uiMode,
                mirrors = downloadMirrors,
                onOpenEditor = onOpenDownloadMirrorEditor
            )
        }

        item("privacy") {
            OtherPrivacySection(
                uiMode = uiMode,
                uiState = uiState,
                onDisableAsrHistoryToggle = onDisableAsrHistoryToggle,
                onAudioRetentionChange = onAudioRetentionChange,
                onAudioRetentionChangeFinished = onAudioRetentionChangeFinished,
                onDisableUsageStatsToggle = onDisableUsageStatsToggle,
                onDataCollectionToggle = onDataCollectionToggle,
                onClearClipboardHistory = onClearClipboardHistory
            )
        }

        item("punctuation") {
            OtherPunctuationSection(
                uiMode = uiMode,
                punctuation = punctuation,
                onPunct1Change = onPunct1Change,
                onPunct2Change = onPunct2Change,
                onPunct3Change = onPunct3Change,
                onPunct4Change = onPunct4Change
            )
        }

        item("speech_presets") {
            SpeechPresetSection(
                uiMode = uiMode,
                state = speechState,
                focusNameAfterAdd = focusNameAfterAdd,
                onFocusNameHandled = onFocusNameHandled,
                onSelectorTap = onSpeechPresetPicker,
                onUpdateName = onUpdateSpeechPresetName,
                onUpdateContent = onUpdateSpeechPresetContent,
                onAddPreset = onAddSpeechPreset,
                onDeletePreset = onDeleteSpeechPreset
            )
        }

        item("sync_clipboard") {
            SyncClipboardSection(
                uiMode = uiMode,
                state = syncState,
                onEnabledChange = onSyncClipboardEnabledChange,
                onServerChange = onSyncClipboardServerChange,
                onUsernameChange = onSyncClipboardUsernameChange,
                onPasswordChange = onSyncClipboardPasswordChange,
                onAutoReceiveChange = onSyncClipboardAutoReceiveChange,
                onKeepBackgroundRealtimeChange = onSyncClipboardKeepBackgroundChange,
                onIntervalChange = onSyncClipboardIntervalChange,
                onImagesChange = onSyncClipboardImagesChange,
                onFilesChange = onSyncClipboardFilesChange,
                onAttachmentMaxSizeChange = onSyncClipboardAttachmentMaxSizeChange,
                onChooseWatchTree = onChooseSyncClipboardWatchTree,
                onClearWatchTree = onClearSyncClipboardWatchTree,
                onTestPull = onTestClipboardSync,
                onOpenProject = onOpenSyncClipboardProject
            )
        }
    }
}

@Composable
private fun OtherPrivacySection(
    uiMode: BibiUiMode,
    uiState: OtherSettingsUiState,
    onDisableAsrHistoryToggle: (Boolean) -> Unit,
    onAudioRetentionChange: (Int) -> Unit,
    onAudioRetentionChangeFinished: () -> Unit,
    onDisableUsageStatsToggle: (Boolean) -> Unit,
    onDataCollectionToggle: (Boolean) -> Unit,
    onClearClipboardHistory: () -> Unit
) {
    OtherSection(uiMode = uiMode, titleRes = R.string.section_data_retention) {
        val context = LocalContext.current
        OtherExplainedSwitch(
            id = "disable_asr_history",
            titleRes = R.string.label_disable_asr_history,
            checked = uiState.disableAsrHistory,
            onToggle = onDisableAsrHistoryToggle,
            index = 0,
            count = 4
        )
        SettingsSliderPreference(
            uiMode = uiMode,
            title = stringResource(R.string.label_audio_history_retention_count),
            valueLabel = { value ->
                context.getString(
                    R.string.audio_history_retention_count_value,
                    value.toInt().coerceIn(0, 100)
                )
            },
            value = uiState.audioHistoryRetentionCount.toFloat(),
            valueRange = 0f..100f,
            steps = 99,
            showKeyPoints = false,
            index = 1,
            count = 4,
            onValueChange = { onAudioRetentionChange(it.toInt()) },
            onValueChangeFinished = { onAudioRetentionChangeFinished() }
        )
        OtherExplainedSwitch(
            id = "disable_usage_stats",
            titleRes = R.string.label_disable_usage_stats,
            checked = uiState.disableUsageStats,
            onToggle = onDisableUsageStatsToggle,
            index = 2,
            count = 4
        )
        OtherExplainedSwitch(
            id = "data_collection",
            titleRes = R.string.label_data_collection,
            checked = uiState.dataCollectionEnabled,
            onToggle = onDataCollectionToggle,
            index = 3,
            count = 4
        )
        OtherButton(
            text = stringResource(R.string.btn_clear_clipboard_history),
            uiMode = uiMode,
            onClick = onClearClipboardHistory
        )
    }
}

@Composable
private fun OtherPunctuationSection(
    uiMode: BibiUiMode,
    punctuation: PunctuationFields,
    onPunct1Change: (String) -> Unit,
    onPunct2Change: (String) -> Unit,
    onPunct3Change: (String) -> Unit,
    onPunct4Change: (String) -> Unit
) {
    val compactFieldPadding = PaddingValues(
        horizontal = 0.dp,
        vertical = SettingsLayoutMetrics.TextFieldLooseVerticalPadding
    )
    OtherSection(uiMode = uiMode, titleRes = R.string.custom_punct_section_title) {
        val fields: @Composable RowScope.() -> Unit = {
            OtherTextField(
                value = punctuation.punct1,
                onValueChange = onPunct1Change,
                label = stringResource(R.string.label_custom_punct_1),
                uiMode = uiMode,
                modifier = Modifier.weight(1f),
                materialContainer = false,
                contentPadding = compactFieldPadding
            )
            OtherTextField(
                value = punctuation.punct2,
                onValueChange = onPunct2Change,
                label = stringResource(R.string.label_custom_punct_2),
                uiMode = uiMode,
                modifier = Modifier.weight(1f),
                materialContainer = false,
                contentPadding = compactFieldPadding
            )
            OtherTextField(
                value = punctuation.punct3,
                onValueChange = onPunct3Change,
                label = stringResource(R.string.label_custom_punct_3),
                uiMode = uiMode,
                modifier = Modifier.weight(1f),
                materialContainer = false,
                contentPadding = compactFieldPadding
            )
            OtherTextField(
                value = punctuation.punct4,
                onValueChange = onPunct4Change,
                label = stringResource(R.string.label_custom_punct_4),
                uiMode = uiMode,
                modifier = Modifier.weight(1f),
                materialContainer = false,
                contentPadding = compactFieldPadding
            )
        }
        val row: @Composable () -> Unit = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SettingsLayoutMetrics.TextFieldHorizontalPadding),
                horizontalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.ActionButtonSpacing),
                content = fields
            )
        }
        when (uiMode) {
            BibiUiMode.Material -> SettingsMaterialItemSurface {
                row()
            }

            BibiUiMode.Miuix -> row()
        }
    }
}
