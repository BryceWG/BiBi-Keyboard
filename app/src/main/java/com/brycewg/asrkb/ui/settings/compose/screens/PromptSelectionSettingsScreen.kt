/**
 * Compose “润色模式（自动选择提示词）”设置页。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.ai.PromptSelectionSettingsViewModel
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheet
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.rememberSettingsChoiceSheetNavigator
import com.brycewg.asrkb.ui.settings.compose.components.settingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry

@Composable
fun PromptSelectionSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    onOpenPreview: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    val viewModel: PromptSelectionSettingsViewModel = viewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    val modelPickerSheets = rememberSettingsChoiceSheetNavigator()
    var messageDialog by remember { mutableStateOf<SettingsMessageDialogState?>(null) }
    var featureDialog by remember { mutableStateOf<SettingsFeatureExplainerDialogState?>(null) }

    LaunchedEffect(prefs) {
        viewModel.load(prefs)
    }

    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.title_prompt_selection,
        onBack = onBack
    ) { innerPadding, scrollModifier ->
        SettingsChoiceSheet(
            state = modelPickerSheets.current,
            uiMode = uiMode,
            onDismiss = modelPickerSheets::onDismiss
        )
        SettingsMessageDialog(
            state = messageDialog,
            uiMode = uiMode,
            onDismiss = { messageDialog = null }
        )
        SettingsFeatureExplainerDialog(
            state = featureDialog,
            uiMode = uiMode,
            onDismiss = { featureDialog = null }
        )

        SettingsLazyColumn(
            uiMode = uiMode,
            modifier = Modifier.fillMaxSize(),
            miuixScrollModifier = scrollModifier,
            contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
        ) {
            item("prompt_selection_switch") {
                AiSection(uiMode = uiMode, titleRes = R.string.section_prompt_selection_switch) {
                    AiSwitchPreference(
                        id = "prompt_auto_select_enabled",
                        titleRes = R.string.label_prompt_auto_select_enabled,
                        checked = state.enabled,
                        index = 0,
                        count = 1,
                        onCheckedChange = { checked ->
                            if (checked && !state.canEnable) {
                                messageDialog = SettingsMessageDialogState(
                                    title = context.getString(R.string.title_prompt_selection),
                                    message = context.getString(R.string.prompt_selection_enable_blocked),
                                    confirmText = context.getString(android.R.string.ok)
                                )
                            } else {
                                featureDialog = settingsFeatureExplainerDialogState(
                                    context = context,
                                    titleRes = R.string.title_prompt_selection,
                                    offDescRes = R.string.feature_prompt_auto_select_off_desc,
                                    onDescRes = R.string.helper_prompt_auto_select_enabled,
                                    currentState = state.enabled,
                                    preferenceKey = "prompt_auto_select_explained",
                                    onConfirm = { viewModel.setEnabled(prefs, checked) }
                                )
                            }
                        }
                    )
                    if (state.showInvalidWarning) {
                        AiBodyText(
                            uiMode = uiMode,
                            textRes = R.string.prompt_selection_warning_invalid
                        )
                    }
                }
            }

            item("prompt_selection_model") {
                AiSection(uiMode = uiMode, titleRes = R.string.section_prompt_selection_model) {
                    AiValuePreference(
                        titleRes = R.string.label_prompt_selection_model,
                        value = llmFeatureModelSummary(context, prefs, prefs.promptSelectorModelRef),
                        uiMode = uiMode,
                        index = 0,
                        count = 2,
                        onClick = {
                            modelPickerSheets.show(
                                llmFeatureModelPicker(
                                    context = context,
                                    prefs = prefs,
                                    current = prefs.promptSelectorModelRef,
                                    titleRes = R.string.prompt_selection_model_choose_vendor_title,
                                    navigator = modelPickerSheets,
                                    onNoModels = {
                                        messageDialog = SettingsMessageDialogState(
                                            title = context.getString(R.string.title_prompt_selection),
                                            message = context.getString(R.string.prompt_selection_no_models),
                                            confirmText = context.getString(android.R.string.ok)
                                        )
                                    },
                                    onSelected = { ref -> viewModel.setModelRef(prefs, ref) }
                                )
                            )
                        }
                    )
                    state.modelSummary?.let { summary ->
                        if (!summary.available) {
                            AiBodyText(
                                uiMode = uiMode,
                                textRes = R.string.prompt_selection_model_unavailable
                            )
                        }
                    }
                    SettingsPreference(
                        entry = SettingsEntry.Action(
                            id = "prompt_selection_preview_entry",
                            titleRes = R.string.title_prompt_selection_preview,
                            summaryRes = R.string.summary_prompt_selection_preview,
                            onClick = onOpenPreview
                        ),
                        index = 1,
                        count = 2
                    )
                }
            }

            item("prompt_selection_candidates") {
                AiSection(uiMode = uiMode, titleRes = R.string.section_prompt_selection_candidates) {
                    state.candidates.forEachIndexed { index, row ->
                        SettingsPreference(
                            entry = SettingsEntry.Switch(
                                id = "prompt_candidate_${row.candidate.id}",
                                titleRes = android.R.string.untitled,
                                title = row.candidate.displayTitle.ifBlank { context.getString(R.string.untitled_preset) },
                                summary = row.candidate.skill.takeIf { it.isNotBlank() },
                                checked = row.checked,
                                onCheckedChange = { checked ->
                                    val selectedIds = state.candidates
                                        .filter { it.checked }
                                        .map { it.candidate.id }
                                        .toMutableSet()
                                    if (checked) selectedIds += row.candidate.id else selectedIds -= row.candidate.id
                                    viewModel.setCandidates(prefs, selectedIds)
                                }
                            ),
                            index = index,
                            count = state.candidates.size
                        )
                    }
                    candidateSummaryLines(
                        state = state,
                        minCount = viewModel.minimumCandidateCount()
                    ).forEach { line ->
                        AiBodyText(uiMode = uiMode, text = line)
                    }
                }
            }
        }
    }
}

/** 紧凑摘要：缺 skill、已删除、数量不足、已就绪。 */
@Composable
private fun candidateSummaryLines(
    state: PromptSelectionSettingsViewModel.UiState,
    minCount: Int
): List<String> {
    val lines = mutableListOf<String>()
    if (state.missingSkillTitles.isNotEmpty()) {
        lines += stringResource(
            R.string.prompt_selection_summary_missing_skill,
            state.missingSkillTitles.joinToString("、")
        )
    }
    if (state.deletedCandidateIds.isNotEmpty()) {
        lines += stringResource(
            R.string.prompt_selection_summary_deleted,
            state.deletedCandidateIds.joinToString("、")
        )
    }
    if (state.resolvedCount < minCount) {
        lines += stringResource(R.string.prompt_selection_summary_min_count, minCount)
    }
    return lines
}
