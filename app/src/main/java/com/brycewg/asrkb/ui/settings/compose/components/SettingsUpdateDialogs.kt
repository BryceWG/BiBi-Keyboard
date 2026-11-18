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
import androidx.compose.material3.HorizontalDivider as MaterialHorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.DownloadSourceOption
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.update.UpdateChecker
import java.text.SimpleDateFormat
import top.yukonga.miuix.kmp.basic.HorizontalDivider as MiuixHorizontalDivider
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val releaseNotesHeading = Regex("""^ {0,3}#{1,6}[ \t]+([^\r\n]+)(?:\r?\n[ \t]*$)?""", RegexOption.MULTILINE)
private val releaseNotesBullet = Regex("""^(\h*)[-+*][ \t]+""", RegexOption.MULTILINE)
private val releaseNotesBold = Regex("""(\*\*|__)(.+?)\1""")

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
                    UpdateResultActions(
                        uiMode = uiMode,
                        primaryText = primaryText,
                        onPrimaryAction = primaryAction,
                        onOpenReleasePage = onOpenReleasePage,
                        onOpenChangelog = onOpenChangelog
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
            UpdateResultActions(
                uiMode = uiMode,
                primaryText = primaryText,
                onPrimaryAction = primaryAction,
                onOpenReleasePage = onOpenReleasePage,
                onOpenChangelog = onOpenChangelog
            )
        }
    }
}

@Composable
private fun UpdateResultActions(
    uiMode: BibiUiMode,
    primaryText: String,
    onPrimaryAction: () -> Unit,
    onOpenReleasePage: () -> Unit,
    onOpenChangelog: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.ActionButtonSpacing)) {
        SettingsDialogActionRow(
            uiMode = uiMode,
            actions = listOf(
                SettingsDialogAction(
                    text = stringResource(R.string.btn_view_changelog),
                    onClick = onOpenChangelog
                ),
                SettingsDialogAction(
                    text = stringResource(R.string.btn_view_release_page),
                    onClick = onOpenReleasePage
                )
            )
        )
        SettingsDialogActionRow(
            uiMode = uiMode,
            actions = listOf(
                SettingsDialogAction(text = primaryText, onClick = onPrimaryAction, primary = true)
            )
        )
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
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val versionMessage = if (hasUpdate) {
                stringResource(R.string.update_dialog_message, result.currentVersion, result.latestVersion)
            } else {
                stringResource(R.string.current_version_message, result.currentVersion)
            }
            UpdateText(
                text = AnnotatedString(versionMessage),
                uiMode = uiMode
            )
            result.updateTime?.let { updateTime ->
                val timestamp = stringResource(R.string.update_timestamp_label, formatUpdateTime(context, updateTime))
                UpdateText(
                    text = AnnotatedString(timestamp),
                    uiMode = uiMode
                )
            }
        }
        result.importantNotice?.let { notice ->
            UpdateText(
                text = AnnotatedString(notice),
                uiMode = uiMode,
                colorRole = result.noticeLevel,
                fontWeight = FontWeight.Bold
            )
        }
        result.releaseNotes?.let { notes ->
            when (uiMode) {
                BibiUiMode.Material -> MaterialHorizontalDivider()
                BibiUiMode.Miuix -> MiuixHorizontalDivider()
            }
            UpdateText(
                text = remember(notes) { formatReleaseNotes(notes) },
                uiMode = uiMode
            )
        }
    }
}

@Composable
private fun UpdateText(
    text: AnnotatedString,
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

private fun formatReleaseNotes(notes: String): AnnotatedString = buildAnnotatedString {
    val withBullets = notes.replace(releaseNotesBullet, "$1• ")
    var bodyStart = 0
    for (heading in releaseNotesHeading.findAll(withBullets)) {
        appendReleaseNotesText(withBullets.substring(bodyStart, heading.range.first))
        withStyle(SpanStyle(fontSize = 1.15.em, fontWeight = FontWeight.SemiBold)) {
            appendReleaseNotesText(heading.groupValues[1])
        }
        bodyStart = heading.range.last + 1
    }
    appendReleaseNotesText(withBullets.substring(bodyStart))
}

private fun AnnotatedString.Builder.appendReleaseNotesText(text: String) {
    var plainTextStart = 0
    for (bold in releaseNotesBold.findAll(text)) {
        append(text, plainTextStart, bold.range.first)
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
            append(bold.groupValues[2])
        }
        plainTextStart = bold.range.last + 1
    }
    append(text, plainTextStart, text.length)
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
