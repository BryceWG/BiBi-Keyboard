@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.text.format.DateFormat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptSelectionFailReason
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.settings.ai.PromptSelectionPreviewViewModel
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButton
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButtonRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import java.util.Date
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.CardDefaults as MiuixCardDefaults
import top.yukonga.miuix.kmp.basic.Checkbox as MiuixCheckbox
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun PromptSelectionPreviewScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val prefs = remember(appContext) { Prefs(appContext) }
    val historyStore = remember(appContext) { AsrHistoryStore(appContext) }
    val viewModel: PromptSelectionPreviewViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var fullTextDialog by remember { mutableStateOf<SettingsMessageDialogState?>(null) }

    LaunchedEffect(historyStore) { viewModel.load(historyStore) }
    LaunchedEffect(state.configInvalid) {
        if (state.configInvalid) {
            fullTextDialog = SettingsMessageDialogState(
                title = context.getString(R.string.title_prompt_selection_preview),
                message = context.getString(R.string.prompt_selection_preview_invalid_config),
                confirmText = context.getString(android.R.string.ok)
            )
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.close() }
    }

    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.title_prompt_selection_preview,
        onBack = onBack,
        bottomBar = {
            SettingsActionButtonRow(uiMode = uiMode) {
                when (state.phase) {
                    PromptSelectionPreviewViewModel.Phase.SELECTING -> SettingsActionButton(
                        uiMode = uiMode,
                        text = stringResource(
                            R.string.action_start_prompt_selection_preview,
                            state.selectedIds.size
                        ),
                        enabled = state.selectedIds.isNotEmpty(),
                        onClick = { viewModel.start(prefs) },
                        modifier = Modifier.weight(1f)
                    )

                    PromptSelectionPreviewViewModel.Phase.RUNNING -> SettingsActionButton(
                        uiMode = uiMode,
                        text = stringResource(R.string.action_cancel_prompt_selection_preview),
                        onClick = viewModel::cancel,
                        modifier = Modifier.weight(1f)
                    )

                    PromptSelectionPreviewViewModel.Phase.COMPLETED,
                    PromptSelectionPreviewViewModel.Phase.CANCELLED -> SettingsActionButton(
                        uiMode = uiMode,
                        text = stringResource(R.string.action_reselect_preview_records),
                        onClick = viewModel::resetSelection,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    ) { innerPadding, scrollModifier ->
        SettingsMessageDialog(
            state = fullTextDialog,
            uiMode = uiMode,
            onDismiss = { fullTextDialog = null }
        )
        SettingsLazyColumn(
            uiMode = uiMode,
            modifier = Modifier.fillMaxSize(),
            miuixScrollModifier = scrollModifier,
            contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
        ) {
            item("preview_history") {
                if (state.history.isEmpty()) {
                    AiBodyText(uiMode = uiMode, textRes = R.string.prompt_selection_preview_empty_history)
                }
            }
            val visibleHistory = if (state.phase == PromptSelectionPreviewViewModel.Phase.SELECTING) {
                state.history
            } else {
                state.history.filter { it.id in state.selectedIds }
            }
            items(visibleHistory, key = { it.id }) { item ->
                val selecting = state.phase == PromptSelectionPreviewViewModel.Phase.SELECTING
                val checked = item.id in state.selectedIds
                PreviewHistoryCard(
                    // Only the selected cards remain in the list. Keep their movement animated,
                    // but remove fade animations so unselected cards disappear immediately.
                    modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                    uiMode = uiMode,
                    item = item,
                    result = state.results.firstOrNull { it.recordId == item.id },
                    selecting = selecting,
                    checked = checked,
                    enabled = checked || state.selectedIds.size < PromptSelectionPreviewViewModel.MAX_SELECTION,
                    onToggle = { viewModel.toggleSelection(item.id, it) },
                    onOpen = {
                        if (selecting) {
                            viewModel.toggleSelection(item.id, !checked)
                        } else {
                            fullTextDialog = SettingsMessageDialogState(
                                title = context.getString(R.string.prompt_selection_preview_original_text),
                                message = item.text,
                                confirmText = context.getString(android.R.string.ok)
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PreviewHistoryCard(
    modifier: Modifier,
    uiMode: BibiUiMode,
    item: PromptSelectionPreviewViewModel.HistoryItem,
    result: PromptSelectionPreviewViewModel.PreviewResult?,
    selecting: Boolean,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit
) {
    val cardModifier = modifier.fillMaxWidth()
    when (uiMode) {
        BibiUiMode.Material -> ElevatedCard(
            onClick = onOpen,
            modifier = cardModifier,
            shape = RoundedCornerShape(SettingsLayoutMetrics.MaterialSectionShape),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (checked && selecting) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainer
                }
            )
        ) {
            PreviewHistoryCardContent(uiMode, item, result, selecting, checked, enabled, onToggle)
        }

        BibiUiMode.Miuix -> MiuixCard(
            modifier = cardModifier,
            cornerRadius = MiuixCardDefaults.CornerRadius,
            insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp),
            onClick = onOpen,
            showIndication = true
        ) {
            PreviewHistoryCardContent(uiMode, item, result, selecting, checked, enabled, onToggle)
        }
    }
}

@Composable
private fun PreviewHistoryCardContent(
    uiMode: BibiUiMode,
    item: PromptSelectionPreviewViewModel.HistoryItem,
    result: PromptSelectionPreviewViewModel.PreviewResult?,
    selecting: Boolean,
    checked: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            // Keep the original timestamp/vendor line until this item has a result. Once
            // classification finishes, only that small line is replaced by the result summary.
            val metadata = if (result != null) {
                previewResultSummary(result)
            } else {
                historyMetadata(item.timestamp, item.vendorId, item.source)
            }
            if (uiMode == BibiUiMode.Material) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (result != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                MiuixText(
                    text = metadata,
                    style = MiuixTheme.textStyles.body2,
                    color = if (result != null) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    }
                )
            }
            Spacer(Modifier.size(5.dp))
            if (uiMode == BibiUiMode.Material) {
                Text(text = item.summary, style = MaterialTheme.typography.bodyLarge)
            } else {
                MiuixText(text = item.summary, style = MiuixTheme.textStyles.body1)
            }
        }
        if (selecting) {
            if (uiMode == BibiUiMode.Material) {
                Checkbox(checked = checked, onCheckedChange = onToggle, enabled = enabled)
            } else {
                MiuixCheckbox(
                    state = if (checked) ToggleableState.On else ToggleableState.Off,
                    onClick = { onToggle(!checked) },
                    enabled = enabled
                )
            }
        }
    }
}

@Composable
private fun historyMetadata(timestamp: Long, vendorId: String, source: String): String {
    val context = LocalContext.current
    val vendor = AsrVendorUi.ordered()
        .firstOrNull { it.id == vendorId }
        ?.let { AsrVendorUi.name(context, it) }
        ?: vendorId
    val date = Date(timestamp)
    val dateText = DateFormat.getMediumDateFormat(context).format(date)
    val timeText = DateFormat.getTimeFormat(context).format(date)
    val sourceLabel = when (source) {
        "floating" -> stringResource(R.string.source_floating_full)
        "external" -> stringResource(R.string.source_external_full)
        "ime" -> stringResource(R.string.source_ime_full)
        else -> source
    }
    return "$dateText $timeText · $vendor · $sourceLabel"
}

@Composable
private fun previewResultSummary(result: PromptSelectionPreviewViewModel.PreviewResult): String {
    val match = when {
        !result.succeeded -> stringResource(R.string.prompt_selection_preview_selection_failed)
        result.skippedPolish -> stringResource(
            R.string.prompt_selection_preview_match,
            stringResource(R.string.prompt_selection_preview_skip_polish)
        )
        else -> stringResource(
            R.string.prompt_selection_preview_match,
            result.matchedTitle.orEmpty()
        )
    }
    val elapsed = stringResource(R.string.prompt_selection_preview_elapsed, result.elapsedMs)
    val reason = result.failureReason?.let { " · ${promptSelectionFailureText(it)}" }.orEmpty()
    return "$match · $elapsed$reason"
}

@Composable
private fun promptSelectionFailureText(reason: PromptSelectionFailReason): String = stringResource(
    when (reason) {
        PromptSelectionFailReason.INVALID_CONFIG -> R.string.prompt_selection_reason_invalid_config
        PromptSelectionFailReason.MODEL_UNAVAILABLE -> R.string.prompt_selection_reason_model_unavailable
        PromptSelectionFailReason.TIMEOUT -> R.string.prompt_selection_reason_timeout
        PromptSelectionFailReason.REQUEST_FAILED -> R.string.prompt_selection_reason_request_failed
        PromptSelectionFailReason.INVALID_OUTPUT -> R.string.prompt_selection_reason_invalid_output
        PromptSelectionFailReason.CANCELLED -> R.string.prompt_selection_reason_cancelled
    }
)
