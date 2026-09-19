/**
 * Compose AI 设置页的后处理模型与供应商表单编排组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.ai.AiPostSettingsViewModel
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode

@Composable
internal fun AiPostProcessModelSection(
    uiMode: BibiUiMode,
    selectedVendor: LlmVendor,
    selectedVendorName: String,
    builtinConfig: AiPostSettingsViewModel.BuiltinVendorConfig,
    activeProfile: Prefs.LlmProvider?,
    sfUseFreeService: Boolean,
    sfApiKey: String,
    sfModel: String,
    sfReasoningCharThreshold: Int,
    sfReasoningOnJson: String,
    sfReasoningOffJson: String,
    sfTemperature: Float,
    sfPresetModels: List<String>,
    sfStaticModels: List<String>,
    builtinPresetModels: List<String>,
    sfCustomModelInputVisible: Boolean,
    builtinCustomModelInputVisible: Boolean,
    builtinReasoningOnJson: String,
    builtinReasoningOffJson: String,
    sfCustomReasoningParamsEnabled: Boolean,
    customModelInputVisible: Boolean,
    focusProfileNameAfterAdd: Boolean,
    llmTestRunning: Boolean,
    sfActions: AiVendorActions,
    onChooseVendor: () -> Unit,
    onFocusedProfileName: () -> Unit,
    onChooseProfile: () -> Unit,
    onProfileNameChange: (String) -> Unit,
    onCustomEndpointChange: (String) -> Unit,
    onCustomApiKeyChange: (String) -> Unit,
    onChooseCustomModel: () -> Unit,
    onCustomModelChange: (String) -> Unit,
    onFetchCustomModels: () -> Unit,
    onCustomReasoningChange: (Int) -> Unit,
    onCustomReasoningParamsEnabledChange: (Boolean) -> Unit,
    onCustomReasoningOnJsonChange: (String) -> Unit,
    onCustomReasoningOffJsonChange: (String) -> Unit,
    onCustomTemperatureChange: (Float) -> Unit,
    onAddProfile: () -> Unit,
    onDeleteProfile: () -> Unit,
    onBuiltinApiKeyChange: (String) -> Unit,
    onChooseBuiltinModel: () -> Unit,
    onBuiltinCustomModelChange: (String) -> Unit,
    onFetchBuiltinModels: () -> Unit,
    onBuiltinReasoningChange: (Int) -> Unit,
    onBuiltinCustomReasoningParamsEnabledChange: (Boolean) -> Unit,
    onBuiltinReasoningOnJsonChange: (String) -> Unit,
    onBuiltinReasoningOffJsonChange: (String) -> Unit,
    onBuiltinTemperatureChange: (Float) -> Unit,
    onOpenBuiltinRegister: () -> Unit,
    onTestCall: () -> Unit
) {
    var showAdvancedParams by remember(selectedVendor) { mutableStateOf(false) }

    AiSection(uiMode = uiMode, titleRes = R.string.section_post_process_model) {
        val primaryConfigItemCount = when (selectedVendor) {
            LlmVendor.SF_FREE -> sfFreeLlmPrimaryItemCount(
                presetModels = sfPresetModels,
                staticModels = sfStaticModels,
                sfUseFreeService = sfUseFreeService,
                sfModel = sfModel,
                customModelInputVisible = sfCustomModelInputVisible
            )

            LlmVendor.CUSTOM -> customLlmPrimaryItemCount(customModelInputVisible)

            else -> builtinLlmPrimaryItemCount(
                vendor = selectedVendor,
                config = builtinConfig,
                presetModels = builtinPresetModels,
                customModelInputVisible = builtinCustomModelInputVisible
            )
        }
        val primaryGroupCount = 1 + primaryConfigItemCount
        AiValuePreference(
            titleRes = R.string.label_llm_vendor,
            value = selectedVendorName,
            uiMode = uiMode,
            index = 0,
            count = primaryGroupCount,
            onClick = onChooseVendor
        )
        when (selectedVendor) {
            LlmVendor.SF_FREE -> SfFreeLlmSection(
                uiMode = uiMode,
                presetModels = sfPresetModels,
                staticModels = sfStaticModels,
                sfUseFreeService = sfUseFreeService,
                sfApiKey = sfApiKey,
                sfModel = sfModel,
                customModelInputVisible = sfCustomModelInputVisible,
                testEnabled = !llmTestRunning,
                primaryIndexOffset = 1,
                primaryGroupCount = primaryGroupCount,
                actions = sfActions,
                onShowAdvancedParams = { showAdvancedParams = true }
            )

            LlmVendor.CUSTOM -> CustomLlmSection(
                uiMode = uiMode,
                provider = activeProfile,
                customModelInputVisible = customModelInputVisible,
                focusProfileNameAfterAdd = focusProfileNameAfterAdd,
                testEnabled = !llmTestRunning,
                onFocusedProfileName = onFocusedProfileName,
                onChooseProfile = onChooseProfile,
                onProfileNameChange = onProfileNameChange,
                onEndpointChange = onCustomEndpointChange,
                onApiKeyChange = onCustomApiKeyChange,
                onChooseModel = onChooseCustomModel,
                onModelChange = onCustomModelChange,
                onFetchModels = onFetchCustomModels,
                onAddProfile = onAddProfile,
                onDeleteProfile = onDeleteProfile,
                onTestCall = onTestCall,
                primaryIndexOffset = 1,
                primaryGroupCount = primaryGroupCount,
                onShowAdvancedParams = { showAdvancedParams = true }
            )

            else -> BuiltinLlmSection(
                uiMode = uiMode,
                vendor = selectedVendor,
                config = builtinConfig,
                presetModels = builtinPresetModels,
                customModelInputVisible = builtinCustomModelInputVisible,
                testEnabled = !llmTestRunning,
                onApiKeyChange = onBuiltinApiKeyChange,
                onChooseModel = onChooseBuiltinModel,
                onCustomModelChange = onBuiltinCustomModelChange,
                onFetchModels = onFetchBuiltinModels,
                onOpenRegister = onOpenBuiltinRegister,
                onTestCall = onTestCall,
                primaryIndexOffset = 1,
                primaryGroupCount = primaryGroupCount,
                onShowAdvancedParams = { showAdvancedParams = true }
            )
        }
    }

    when (selectedVendor) {
        LlmVendor.SF_FREE -> {
            val showCustomReasoningParams =
                sfModel.isNotBlank() && !sfStaticModels.contains(sfModel)
            val showReasoning =
                selectedVendor.supportsReasoningControl(sfModel) || showCustomReasoningParams
            AiModelAdvancedParamsDialog(
                visible = showAdvancedParams,
                uiMode = uiMode,
                showTemperature = !sfUseFreeService,
                temperature = sfTemperature,
                temperatureRange = 0f..2f,
                temperatureSteps = 19,
                onTemperatureChange = sfActions.onTemperatureChange,
                onTemperatureChangeFinished = { sfActions.onTestHaptic() },
                showReasoning = showReasoning,
                reasoningThreshold = sfReasoningCharThreshold,
                showCustomReasoningParams = showCustomReasoningParams,
                customReasoningParamsEnabled = sfCustomReasoningParamsEnabled,
                reasoningOnJson = sfReasoningOnJson,
                reasoningOffJson = sfReasoningOffJson,
                onReasoningChange = sfActions.onReasoningChange,
                onCustomReasoningParamsEnabledChange =
                sfActions.onCustomReasoningParamsEnabledChange,
                onReasoningOnJsonChange = sfActions.onReasoningOnJsonChange,
                onReasoningOffJsonChange = sfActions.onReasoningOffJsonChange,
                onDismiss = { showAdvancedParams = false }
            )
        }

        LlmVendor.CUSTOM -> AiModelAdvancedParamsDialog(
            visible = showAdvancedParams,
            uiMode = uiMode,
            showTemperature = true,
            temperature = activeProfile?.temperature ?: Prefs.DEFAULT_LLM_TEMPERATURE,
            temperatureRange = 0f..2f,
            temperatureSteps = 19,
            onTemperatureChange = onCustomTemperatureChange,
            showReasoning = true,
            reasoningThreshold = activeProfile?.resolvedReasoningCharThreshold()
                ?: com.brycewg.asrkb.asr.LlmReasoningThreshold.NEVER,
            showCustomReasoningParams = true,
            customReasoningParamsEnabled = activeProfile?.useCustomReasoningParams ?: false,
            reasoningOnJson = activeProfile?.reasoningParamsOnJson.orEmpty(),
            reasoningOffJson = activeProfile?.reasoningParamsOffJson.orEmpty(),
            onReasoningChange = onCustomReasoningChange,
            onCustomReasoningParamsEnabledChange = onCustomReasoningParamsEnabledChange,
            onReasoningOnJsonChange = onCustomReasoningOnJsonChange,
            onReasoningOffJsonChange = onCustomReasoningOffJsonChange,
            onDismiss = { showAdvancedParams = false }
        )

        else -> {
            val model = builtinConfig.model.ifBlank { selectedVendor.defaultModel }
            val showCustomReasoningParams =
                model.isNotBlank() && !selectedVendor.models.contains(model)
            val showReasoning =
                selectedVendor.supportsReasoningControl(model) || showCustomReasoningParams
            AiModelAdvancedParamsDialog(
                visible = showAdvancedParams,
                uiMode = uiMode,
                showTemperature = true,
                temperature = builtinConfig.temperature,
                temperatureRange = selectedVendor.temperatureMin..selectedVendor.temperatureMax,
                temperatureSteps = temperatureSteps(selectedVendor),
                onTemperatureChange = onBuiltinTemperatureChange,
                showReasoning = showReasoning,
                reasoningThreshold = builtinConfig.reasoningCharThreshold,
                showCustomReasoningParams = showCustomReasoningParams,
                customReasoningParamsEnabled = builtinConfig.customReasoningParamsEnabled,
                reasoningOnJson = builtinReasoningOnJson,
                reasoningOffJson = builtinReasoningOffJson,
                onReasoningChange = onBuiltinReasoningChange,
                onCustomReasoningParamsEnabledChange =
                onBuiltinCustomReasoningParamsEnabledChange,
                onReasoningOnJsonChange = onBuiltinReasoningOnJsonChange,
                onReasoningOffJsonChange = onBuiltinReasoningOffJsonChange,
                onDismiss = { showAdvancedParams = false }
            )
        }
    }
}
