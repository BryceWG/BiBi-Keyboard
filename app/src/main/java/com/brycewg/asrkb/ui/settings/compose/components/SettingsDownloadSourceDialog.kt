/** 下载源选择弹窗，归属 ui/settings/compose/components。 */
package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.DownloadSourceLatencyResult
import com.brycewg.asrkb.ui.DownloadSourceLatencyStatus
import com.brycewg.asrkb.ui.DownloadSourceOption
import com.brycewg.asrkb.ui.buildDownloadSourceAddressDisplay
import com.brycewg.asrkb.ui.measureDownloadSourceLatency
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SettingsDownloadSourceDialog(
    options: List<DownloadSourceOption>,
    uiMode: BibiUiMode,
    onDismiss: () -> Unit,
    onSelect: (DownloadSourceOption) -> Unit
) {
    if (options.isEmpty()) return

    val exit = rememberSettingsDialogExitController(options)
    val title = stringResource(R.string.download_source_title)
    val cancel = SettingsDialogAction(
        text = stringResource(R.string.btn_cancel),
        onClick = { exit.dismiss(onDismiss) }
    )

    @Composable
    fun Content(modifier: Modifier = Modifier) {
        DownloadSourceList(options, uiMode, modifier) { option ->
            exit.dismiss {
                onDismiss()
                onSelect(option)
            }
        }
    }

    when (uiMode) {
        BibiUiMode.Material -> {
            val alpha = animateSettingsDialogExitAlpha(show = exit.show)
            MaterialSettingsDialogExitEffect(show = exit.show, onFinished = exit::finish)
            MaterialSettingsAlertDialog(
                title = title,
                onDismissRequest = cancel.onClick,
                modifier = Modifier.graphicsLayer(alpha = alpha),
                text = { Content() },
                buttons = { SettingsDialogActionRow(uiMode, listOf(cancel)) }
            )
        }

        BibiUiMode.Miuix -> OverlayDialog(
            show = exit.show,
            title = title,
            onDismissRequest = cancel.onClick,
            onDismissFinished = exit::finish
        ) {
            Column {
                Content(Modifier.weight(1f, fill = false))
                SettingsDialogActionRow(uiMode, listOf(cancel))
            }
        }
    }
}

@Composable
private fun DownloadSourceList(
    options: List<DownloadSourceOption>,
    uiMode: BibiUiMode,
    modifier: Modifier,
    onSelect: (DownloadSourceOption) -> Unit
) {
    val latencyResults = remember(options) { mutableStateMapOf<String, DownloadSourceLatencyResult>() }
    val hapticTap = LocalSettingsHapticTap.current
    LaunchedEffect(options) {
        options.forEach { option ->
            launch {
                latencyResults[option.url] = measureDownloadSourceLatency(option.url)
            }
        }
    }

    SettingsLazyColumn(
        uiMode = uiMode,
        modifier = modifier.fillMaxWidth().heightIn(max = SettingsLayoutMetrics.DialogContentMaxHeight)
    ) {
        items(options, key = { it.url }) { option ->
            val address = buildDownloadSourceAddressDisplay(option.url)
            val latency = latencyResults[option.url]?.asText()
                ?: stringResource(R.string.download_source_latency_pending)
            DownloadSourceRow(option.label, "$address\n$latency", uiMode) {
                hapticTap()
                onSelect(option)
            }
        }
        item("latency-note") {
            val text = stringResource(R.string.download_source_latency_note)
            when (uiMode) {
                BibiUiMode.Material -> MaterialText(
                    text = text,
                    modifier = Modifier.padding(
                        horizontal = SettingsLayoutMetrics.SettingsPreferenceHorizontalPadding,
                        vertical = SettingsLayoutMetrics.SheetSupportingTextVerticalPadding
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )

                BibiUiMode.Miuix -> MiuixText(
                    text = text,
                    modifier = Modifier.padding(bottom = SettingsLayoutMetrics.DialogContentBottomPadding),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.footnote1
                )
            }
        }
    }
}

@Composable
private fun DownloadSourceRow(title: String, summary: String, uiMode: BibiUiMode, onClick: () -> Unit) {
    when (uiMode) {
        BibiUiMode.Material -> ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            headlineContent = { MaterialText(title) },
            supportingContent = { MaterialText(summary) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        BibiUiMode.Miuix -> BasicComponent(
            title = title,
            summary = summary,
            insideMargin = PaddingValues(vertical = SettingsLayoutMetrics.DownloadSourceInsideVerticalPadding),
            onClick = onClick
        )
    }
}

@Composable
private fun DownloadSourceLatencyResult.asText(): String = when (status) {
    DownloadSourceLatencyStatus.Pending -> stringResource(R.string.download_source_latency_pending)
    DownloadSourceLatencyStatus.Timeout -> stringResource(R.string.download_source_latency_timeout)
    DownloadSourceLatencyStatus.Error -> stringResource(R.string.download_source_latency_failed)
    DownloadSourceLatencyStatus.Ok -> stringResource(R.string.download_source_latency_value, latencyMs)
}
