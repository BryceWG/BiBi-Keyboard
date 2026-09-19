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
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptSelectorModelRef
import com.brycewg.asrkb.ui.settings.ai.PromptSelectionSettingsViewModel
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceGroup
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceItem
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheet
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.rememberSettingsChoiceSheetNavigator
import com.brycewg.asrkb.ui.settings.compose.components.settingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.settingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry

@Composable
fun PromptSelectionSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit
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
                        value = modelSummaryLabel(context, state),
                        uiMode = uiMode,
                        index = 0,
                        count = 1,
                        onClick = {
                            modelPickerSheets.show(
                                modelRefChoiceSheet(
                                    context = context,
                                    prefs = prefs,
                                    viewModel = viewModel,
                                    onPick = { ref ->
                                        if (ref is PromptSelectorModelRef.FollowDefault) {
                                            modelPickerSheets.finishAfterDismiss()
                                            viewModel.setModelRef(prefs, ref)
                                            return@modelRefChoiceSheet
                                        }
                                        val currentModel = when (ref) {
                                            is PromptSelectorModelRef.Builtin -> ref.model
                                            is PromptSelectorModelRef.Custom -> ref.model
                                            PromptSelectorModelRef.FollowDefault -> ""
                                        }
                                        val models = viewModel.savedModels(prefs, ref)
                                        if (models.isEmpty()) {
                                            messageDialog = SettingsMessageDialogState(
                                                title = context.getString(R.string.title_prompt_selection),
                                                message = context.getString(R.string.prompt_selection_no_models),
                                                confirmText = context.getString(android.R.string.ok)
                                            )
                                            modelPickerSheets.finishAfterDismiss()
                                            return@modelRefChoiceSheet
                                        }
                                        if (models.size == 1) {
                                            modelPickerSheets.finishAfterDismiss()
                                            viewModel.setModelRef(prefs, withModel(ref, models.single()))
                                            return@modelRefChoiceSheet
                                        }
                                        modelPickerSheets.showAfterDismiss(
                                            settingsChoiceSheetState(
                                                title = context.getString(R.string.prompt_selection_model_choose_model_title),
                                                items = models,
                                                selectedIndex = models.indexOf(currentModel),
                                                onSelected = { index ->
                                                    val model = models.getOrNull(index) ?: return@settingsChoiceSheetState
                                                    modelPickerSheets.finishAfterDismiss()
                                                    viewModel.setModelRef(prefs, withModel(ref, model))
                                                }
                                            )
                                        )
                                    }
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

@Composable
private fun modelSummaryLabel(
    context: android.content.Context,
    state: PromptSelectionSettingsViewModel.UiState
): String {
    val summary = state.modelSummary ?: return stringResource(
        R.string.prompt_selection_model_follow_default
    )
    val targetName = when {
        summary.vendorNameResId != null -> context.getString(summary.vendorNameResId)
        summary.customProviderName != null ->
            summary.customProviderName.ifBlank { context.getString(R.string.untitled_profile) }
        else -> context.getString(R.string.prompt_selection_model_follow_default)
    }
    val model = summary.model.ifBlank { context.getString(R.string.prompt_selection_model_unconfigured) }
    return "$targetName · $model"
}

/**
 * 第一层：跟随默认 + 所有内置供应商 + 各自定义配置（未配置状态直接标注）。
 */
private fun modelRefChoiceSheet(
    context: android.content.Context,
    prefs: Prefs,
    viewModel: PromptSelectionSettingsViewModel,
    onPick: (PromptSelectorModelRef) -> Unit
): SettingsChoiceSheetState? {
    val state = viewModel.uiState.value
    val options = viewModel.modelRefOptions(prefs)
    val unconfiguredLabel = context.getString(R.string.prompt_selection_model_unconfigured)
    val untitledProfile = context.getString(R.string.untitled_profile)

    data class Option(val index: Int, val item: SettingsChoiceItem, val configured: Boolean)

    val built = options.mapIndexed { index, ref ->
        val title: String
        val configured: Boolean
        when (ref) {
            PromptSelectorModelRef.FollowDefault -> {
                title = context.getString(R.string.prompt_selection_model_follow_default)
                configured = state.modelSummary?.available == true
            }

            is PromptSelectorModelRef.Builtin -> {
                val vendor = LlmVendor.fromId(ref.vendorId)
                title = context.getString(vendor.displayNameResId)
                configured = state.vendorOptions
                    .firstOrNull { it.vendor.id == ref.vendorId }
                    ?.configured == true
            }

            is PromptSelectorModelRef.Custom -> {
                val option = state.customOptions.firstOrNull { it.providerId == ref.providerId }
                title = option?.name?.ifBlank { untitledProfile } ?: untitledProfile
                configured = option?.configured == true
            }
        }
        Option(
            index = index,
            item = SettingsChoiceItem(
                title = if (configured) title else "$title（$unconfiguredLabel）",
                originalIndex = index
            ),
            configured = configured
        )
    }

    val followDefault = built.firstOrNull { options.getOrNull(it.index) is PromptSelectorModelRef.FollowDefault }
    val targets = built.filter { it !== followDefault }
    val configuredItems = targets.filter { it.configured }.map { it.item }
    val unconfiguredItems = targets.filter { !it.configured }.map { it.item }

    val groups = buildList {
        followDefault?.let { add(SettingsChoiceGroup(label = "", items = listOf(it.item))) }
        if (configuredItems.isNotEmpty()) {
            add(
                SettingsChoiceGroup(
                    label = context.getString(R.string.llm_vendor_group_configured),
                    items = configuredItems
                )
            )
        }
        if (unconfiguredItems.isNotEmpty()) {
            add(
                SettingsChoiceGroup(
                    label = context.getString(R.string.llm_vendor_group_unconfigured),
                    items = unconfiguredItems
                )
            )
        }
    }
    if (groups.isEmpty()) return null
    return SettingsChoiceSheetState(
        title = context.getString(R.string.prompt_selection_model_choose_vendor_title),
        groups = groups,
        selectedIndex = viewModel.currentModelRefIndex(prefs),
        onSelected = { index -> options.getOrNull(index)?.let(onPick) }
    )
}

/** 第二层：已保存模型（含当前模型），不支持自由输入。 */
private fun withModel(
    ref: PromptSelectorModelRef,
    model: String
): PromptSelectorModelRef = when (ref) {
    PromptSelectorModelRef.FollowDefault -> PromptSelectorModelRef.FollowDefault
    is PromptSelectorModelRef.Builtin -> ref.copy(model = model)
    is PromptSelectorModelRef.Custom -> ref.copy(model = model)
}
