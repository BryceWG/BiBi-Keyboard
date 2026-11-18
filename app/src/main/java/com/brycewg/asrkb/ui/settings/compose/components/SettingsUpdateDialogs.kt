/**
 * Compose 设置页更新检查弹窗与下载源选择。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:OptIn(ExperimentalMaterial3Api::class)
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.DownloadSourceOption
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.update.UpdateChecker
import java.text.SimpleDateFormat
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal sealed interface SettingsUpdateUiState {
    data object Idle : SettingsUpdateUiState
    data object Checking : SettingsUpdateUiState
    data class UpdateAvailable(val result: UpdateChecker.UpdateCheckResult) : SettingsUpdateUiState
    data class CurrentVersion(val result: UpdateChecker.UpdateCheckResult) : SettingsUpdateUiState
    data class CheckFailed(val message: String) : SettingsUpdateUiState
    data class Message(
        val title: String,
        val message: String
    ) : SettingsUpdateUiState

    data class DownloadSources(
        val version: String,
        val options: List<DownloadSourceOption>
    ) : SettingsUpdateUiState
}

@Composable
internal fun SettingsUpdateHost(
    state: SettingsUpdateUiState,
    uiMode: BibiUiMode,
    onDismiss: () -> Unit,
    onDownload: (UpdateChecker.UpdateCheckResult) -> Unit,
    onOpenReleasePage: (String) -> Unit,
    onOpenChangelog: () -> Unit,
    onManualCheck: () -> Unit,
    onSelectDownloadSource: (DownloadSourceOption, String) -> Unit
) {
    when (state) {
        SettingsUpdateUiState.Idle -> Unit
        SettingsUpdateUiState.Checking -> SettingsProgressDialog(
            state = SettingsProgressDialogState(stringResource(R.string.update_checking)),
            uiMode = uiMode,
            onDismiss = onDismiss
        )

        is SettingsUpdateUiState.UpdateAvailable -> UpdateResultDialog(
            result = state.result,
            hasUpdate = true,
            uiMode = uiMode,
            onDismiss = onDismiss,
            onDownload = { onDownload(state.result) },
            onOpenReleasePage = { onOpenReleasePage(state.result.downloadUrl) },
            onOpenChangelog = onOpenChangelog
        )

        is SettingsUpdateUiState.CurrentVersion -> UpdateResultDialog(
            result = state.result,
            hasUpdate = false,
            uiMode = uiMode,
            onDismiss = onDismiss,
            onDownload = { },
            onOpenReleasePage = { onOpenReleasePage(state.result.downloadUrl) },
            onOpenChangelog = onOpenChangelog
        )

        is SettingsUpdateUiState.CheckFailed -> UpdateFailedDialog(
            message = state.message,
            uiMode = uiMode,
            onDismiss = onDismiss,
            onManualCheck = onManualCheck
        )

        is SettingsUpdateUiState.Message -> SettingsMessageDialog(
            state = SettingsMessageDialogState(
                title = state.title,
                message = state.message,
                confirmText = stringResource(android.R.string.ok)
            ),
            uiMode = uiMode,
            onDismiss = onDismiss
        )

        is SettingsUpdateUiState.DownloadSources -> SettingsDownloadSourceDialog(
            options = state.options,
            uiMode = uiMode,
            onDismiss = onDismiss,
            onSelect = { option -> onSelectDownloadSource(option, state.version) }
        )
    }
}

@Composable
private fun UpdateResultDialog(
    result: UpdateChecker.UpdateCheckResult,
    hasUpdate: Boolean,
    uiMode: BibiUiMode,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onOpenChangelog: () -> Unit
) {
    val exit = rememberSettingsDialogExitController(result)
    fun finishDismiss() {
        exit.finish()
    }
    val title = stringResource(
        if (hasUpdate) R.string.update_dialog_title else R.string.current_version_info_title
    )
    val primaryText = stringResource(if (hasUpdate) R.string.btn_download else android.R.string.ok)
    val primaryAction: () -> Unit = if (hasUpdate) {
        { exit.dismiss(onDownload) }
    } else {
        { exit.dismiss(onDismiss) }
    }
    when (uiMode) {
        BibiUiMode.Material -> {
            val alpha = animateSettingsDialogExitAlpha(
                show = exit.show,
                label = "UpdateResultDialogAlpha"
            )
            MaterialSettingsDialogExitEffect(show = exit.show, onFinished = ::finishDismiss)
            MaterialSettingsAlertDialog(
                onDismissRequest = { exit.dismiss(onDismiss) },
                modifier = Modifier.graphicsLayer(alpha = alpha),
                title = title,
                text = {
                    UpdateMessageContent(
                        result = result,
                        hasUpdate = hasUpdate,
                        uiMode = uiMode
                    )
                },
                buttons = {
                    MaterialSettingsDialogButtonRow(
                        actions = listOf(
                            MaterialSettingsDialogAction(
                                text = stringResource(R.string.btn_view_changelog),
                                onClick = onOpenChangelog
                            ),
                            MaterialSettingsDialogAction(
                                text = stringResource(R.string.btn_view_release_page),
                                onClick = onOpenReleasePage
                            ),
                            MaterialSettingsDialogAction(
                                text = primaryText,
                                onClick = primaryAction
                            )
                        )
                    )
                }
            )
        }

        BibiUiMode.Miuix -> OverlayDialog(
            show = exit.show,
            title = title,
            onDismissRequest = { exit.dismiss(onDismiss) },
            onDismissFinished = ::finishDismiss
        ) {
            UpdateMessageContent(
                result = result,
                hasUpdate = hasUpdate,
                uiMode = uiMode,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            SettingsDialogActionRow(
                uiMode = BibiUiMode.Miuix,
                actions = listOf(
                    SettingsDialogAction(
                        text = stringResource(R.string.btn_view_changelog),
                        onClick = onOpenChangelog
                    ),
                    SettingsDialogAction(
                        text = stringResource(R.string.btn_view_release_page),
                        onClick = onOpenReleasePage
                    ),
                    SettingsDialogAction(
                        text = primaryText,
                        onClick = primaryAction,
                        primary = true
                    )
                )
            )
        }
    }
}

@Composable
private fun UpdateFailedDialog(
    message: String,
    uiMode: BibiUiMode,
    onDismiss: () -> Unit,
    onManualCheck: () -> Unit
) {
    val title = stringResource(R.string.update_check_failed, message.ifBlank { "Unknown error" })
    val exit = rememberSettingsDialogExitController(title)
    fun finishDismiss() {
        exit.finish()
    }
    when (uiMode) {
        BibiUiMode.Material -> {
            val alpha = animateSettingsDialogExitAlpha(
                show = exit.show,
                label = "UpdateFailedDialogAlpha"
            )
            MaterialSettingsDialogExitEffect(show = exit.show, onFinished = ::finishDismiss)
            MaterialSettingsAlertDialog(
                onDismissRequest = { exit.dismiss(onDismiss) },
                modifier = Modifier.graphicsLayer(alpha = alpha),
                title = title,
                buttons = {
                    MaterialSettingsDialogButtonRow(
                        actions = listOf(
                            MaterialSettingsDialogAction(
                                text = stringResource(R.string.btn_cancel),
                                onClick = { exit.dismiss(onDismiss) }
                            ),
                            MaterialSettingsDialogAction(
                                text = stringResource(R.string.btn_manual_check),
                                onClick = onManualCheck
                            )
                        )
                    )
                }
            )
        }

        BibiUiMode.Miuix -> OverlayDialog(
            show = exit.show,
            title = title,
            onDismissRequest = { exit.dismiss(onDismiss) },
            onDismissFinished = ::finishDismiss
        ) {
            SettingsDialogActionRow(
                uiMode = BibiUiMode.Miuix,
                actions = listOf(
                    SettingsDialogAction(
                        text = stringResource(R.string.btn_cancel),
                        onClick = { exit.dismiss(onDismiss) }
                    ),
                    SettingsDialogAction(
                        text = stringResource(R.string.btn_manual_check),
                        onClick = onManualCheck,
                        primary = true
                    )
                )
            )
        }
    }
}

@Composable
private fun UpdateMessageContent(
    result: UpdateChecker.UpdateCheckResult,
    hasUpdate: Boolean,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = SettingsLayoutMetrics.DialogContentMaxHeight)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        UpdateText(
            text = if (hasUpdate) {
                context.getString(
                    R.string.update_dialog_message,
                    result.currentVersion,
                    result.latestVersion
                )
            } else {
                context.getString(R.string.current_version_message, result.currentVersion)
            },
            uiMode = uiMode
        )
        result.importantNotice?.let { notice ->
            UpdateText(
                text = notice,
                uiMode = uiMode,
                colorRole = result.noticeLevel,
                fontWeight = FontWeight.Bold
            )
        }
        result.updateTime?.let { updateTime ->
            UpdateText(
                text = stringResource(R.string.update_timestamp_label, formatUpdateTime(context, updateTime)),
                uiMode = uiMode
            )
        }
        result.releaseNotes?.let { notes ->
            UpdateText(
                text = stringResource(R.string.update_release_notes_label, notes),
                uiMode = uiMode
            )
        }
    }
}

@Composable
private fun UpdateText(
    text: String,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    colorRole: UpdateChecker.NoticeLevel? = null,
    fontWeight: FontWeight? = null
) {
    when (uiMode) {
        BibiUiMode.Material -> MaterialText(
            text = text,
            modifier = modifier,
            color = when (colorRole) {
                UpdateChecker.NoticeLevel.INFO -> MaterialTheme.colorScheme.tertiary
                UpdateChecker.NoticeLevel.WARNING -> MaterialTheme.colorScheme.secondary
                UpdateChecker.NoticeLevel.CRITICAL -> MaterialTheme.colorScheme.error
                null -> MaterialTheme.colorScheme.onSurface
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = fontWeight
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            modifier = modifier,
            color = when (colorRole) {
                UpdateChecker.NoticeLevel.INFO -> MiuixTheme.colorScheme.primary
                UpdateChecker.NoticeLevel.WARNING -> MiuixTheme.colorScheme.secondary
                UpdateChecker.NoticeLevel.CRITICAL -> MiuixTheme.colorScheme.error
                null -> MiuixTheme.colorScheme.onSurface
            },
            style = MiuixTheme.textStyles.body2,
            fontWeight = fontWeight
        )
    }
}

private fun formatUpdateTime(context: android.content.Context, updateTime: String): String = try {
    val locale = LocaleHelper.locale(context)
    val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", locale)
    utcFormat.timeZone = java.util.TimeZone.getTimeZone("UTC")
    val date = utcFormat.parse(updateTime)

    val localFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", locale)
    if (date != null) localFormat.format(date) else updateTime
} catch (_: Exception) {
    updateTime
}
