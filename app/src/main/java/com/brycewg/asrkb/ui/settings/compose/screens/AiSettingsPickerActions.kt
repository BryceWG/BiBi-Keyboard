/**
 * Compose AI 设置页的选择器与模型列表操作。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptPreset
import com.brycewg.asrkb.store.jevModelId
import com.brycewg.asrkb.ui.settings.ai.AiPostSettingsViewModel
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMultiChoiceSheetState

internal class AiSettingsPickerActions(
    private val context: Context,
    private val prefs: Prefs,
    private val localState: AiSettingsLocalState,
    private val viewModel: AiPostSettingsViewModel,
    private val selectedVendor: LlmVendor,
    private val builtinConfig: AiPostSettingsViewModel.BuiltinVendorConfig,
    private val profiles: List<Prefs.LlmProvider>,
    private val activeProfile: Prefs.LlmProvider?,
    private val promptPresets: List<PromptPreset>,
    private val onChoiceSheetChange: (SettingsChoiceSheetState?) -> Unit,
    private val onMultiChoiceSheetChange: (SettingsMultiChoiceSheetState?) -> Unit,
    private val onMessage: (Int) -> Unit,
    private val onFetchFailed: (String) -> Unit
) {
    fun showVendorSelectionDialog() {
        onChoiceSheetChange(
            llmVendorChoiceSheetState(
                context = context,
                prefs = prefs,
                selectedVendor = selectedVendor,
                onSelected = { vendor -> viewModel.selectVendor(prefs, vendor) }
            )
        )
    }

    fun showSfModelDialog() {
        val presetModels = localState.sfPresetModels
        onChoiceSheetChange(
            aiModelChoiceSheetState(
                context = context,
                titleRes = R.string.label_sf_free_llm_model,
                presetModels = presetModels,
                currentModel = localState.sfModel,
                blankAsCustom = false,
                onCustomSelected = { customModel ->
                    localState.sfCustomModelInputVisible = true
                    localState.sfModel = customModel
                },
                onModelSelected = { selected ->
                    localState.sfCustomModelInputVisible = false
                    if (prefs.sfFreeLlmUsePaidKey) {
                        prefs.setLlmVendorModel(LlmVendor.SF_FREE, selected)
                    } else {
                        prefs.sfFreeLlmModel = selected
                    }
                    localState.sfModel = selected
                }
            ) {
                localState.refreshSfState()
            }
        )
    }

    fun showBuiltinModelDialog() {
        if (selectedVendor == LlmVendor.CUSTOM || selectedVendor == LlmVendor.SF_FREE) return
        val isTypesafe = selectedVendor == LlmVendor.TYPESAFE
        val typesafeChannel = prefs.jevClassifierProvider
        val presetModels = if (isTypesafe) {
            listOf(typesafeChannel.defaultModelId())
        } else {
            localState.builtinPresetModels
        }
        val currentModel = if (isTypesafe) {
            jevModelId(prefs, typesafeChannel)
        } else {
            builtinConfig.model.ifBlank { selectedVendor.defaultModel }
        }
        onChoiceSheetChange(
            aiModelChoiceSheetState(
                context = context,
                titleRes = R.string.label_llm_model_select,
                presetModels = presetModels,
                currentModel = currentModel,
                onCustomSelected = { customModel ->
                    localState.builtinCustomModelInputVisible = true
                    if (customModel.isNotBlank()) {
                        viewModel.updateBuiltinModel(prefs, customModel)
                    }
                },
                onModelSelected = { selected ->
                    localState.builtinCustomModelInputVisible = false
                    viewModel.updateBuiltinModel(prefs, selected)
                }
            )
        )
    }

    fun showProfileDialog() {
        onChoiceSheetChange(
            aiProfileChoiceSheetState(
                context = context,
                profiles = profiles,
                selectedIndex = viewModel.getActiveLlmProviderIndex(),
                onSelected = { profile -> viewModel.selectLlmProvider(prefs, profile.id) }
            )
        )
    }

    fun showCustomModelDialog() {
        val provider = activeProfile
        val presetModels = customProviderModels(provider)
        val currentModel = provider?.model.orEmpty()
        onChoiceSheetChange(
            aiModelChoiceSheetState(
                context = context,
                titleRes = R.string.label_llm_model_select,
                presetModels = presetModels,
                currentModel = currentModel,
                onCustomSelected = { customModel ->
                    localState.customModelInputVisible = true
                    viewModel.updateActiveLlmProvider(prefs) { it.copy(model = customModel) }
                },
                onModelSelected = { selected ->
                    localState.customModelInputVisible = false
                    viewModel.updateActiveLlmProvider(prefs) { it.copy(model = selected) }
                }
            )
        )
    }

    fun showPromptPresetDialog() {
        onChoiceSheetChange(
            aiPromptPresetChoiceSheetState(
                context = context,
                promptPresets = promptPresets,
                selectedIndex = viewModel.getActivePromptPresetIndex(),
                onSelected = { preset -> viewModel.selectPromptPreset(prefs, preset.id) }
            )
        )
    }

    fun showBuiltinModelsPicker(vendor: LlmVendor, models: List<String>) {
        val currentModels = prefs.getLlmVendorModels(vendor)
        val sheetState = aiModelsMultiChoiceSheetState(
            context = context,
            models = models,
            currentModels = currentModels
        ) { selected ->
            if (selected.isEmpty()) {
                onMessage(R.string.toast_llm_models_none_selected)
                true
            } else {
                val selectionResult = applyAiBuiltinModelsSelection(
                    prefs = prefs,
                    vendor = vendor,
                    activeVendor = selectedVendor,
                    selectedModels = selected
                )
                selectionResult.sfPresetModels?.let { localState.sfPresetModels = it }
                selectionResult.sfStaticModels?.let { localState.sfStaticModels = it }
                selectionResult.builtinPresetModels?.let { localState.builtinPresetModels = it }
                selectionResult.sfModel?.let { localState.sfModel = it }
                selectionResult.builtinModel?.let { viewModel.updateBuiltinModel(prefs, it) }
                localState.refreshSfState()
                true
            }
        }
        if (sheetState == null) {
            onFetchFailed(context.getString(R.string.llm_test_failed_generic))
            return
        }
        onMultiChoiceSheetChange(sheetState)
    }

    fun showCustomModelsPicker(models: List<String>) {
        val provider = activeProfile
        val currentModels = customProviderModels(provider)
        val sheetState = aiModelsMultiChoiceSheetState(
            context = context,
            models = models,
            currentModels = currentModels
        ) { selected ->
            if (selected.isEmpty()) {
                onMessage(R.string.toast_llm_models_none_selected)
                true
            } else {
                val currentModel = provider?.model.orEmpty()
                val nextModel = nextSelectedModel(currentModel, selected)
                localState.customModelInputVisible = false
                viewModel.updateActiveLlmProvider(prefs) {
                    it.copy(models = selected, model = nextModel)
                }
                true
            }
        }
        if (sheetState == null) {
            onFetchFailed(context.getString(R.string.llm_test_failed_generic))
            return
        }
        onMultiChoiceSheetChange(sheetState)
    }
}
