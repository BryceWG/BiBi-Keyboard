/**
 * Compose AI 设置页本地可编辑字段状态。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptPreset
import com.brycewg.asrkb.ui.settings.ai.AiPostSettingsViewModel

internal class AiSettingsLocalState(
    private val prefs: Prefs,
    selectedVendor: LlmVendor,
    builtinConfig: AiPostSettingsViewModel.BuiltinVendorConfig
) {
    var postProcessEnabled by mutableStateOf(prefs.postProcessEnabled)
    var typewriterEnabled by mutableStateOf(prefs.postprocTypewriterEnabled)
    var aiEditPreferLastAsr by mutableStateOf(prefs.aiEditDefaultToLastAsr)
    var skipUnderChars by mutableStateOf(prefs.postprocSkipUnderChars.coerceIn(0, 100))
    var aiEditCustomSystemPromptEnabled by mutableStateOf(prefs.aiEditCustomSystemPromptEnabled)
    var aiEditSystemPrompt by mutableStateOf(prefs.aiEditSystemPrompt)
    var sfUseFreeService by mutableStateOf(!prefs.sfFreeLlmUsePaidKey)
    var sfApiKey by mutableStateOf(prefs.getLlmVendorApiKey(LlmVendor.SF_FREE))
    var sfModel by mutableStateOf(currentSfModel(prefs))
    var sfPresetModels by mutableStateOf(getSfPresetModels(prefs))
    var sfStaticModels by mutableStateOf(getSfStaticModels(prefs))
    var sfReasoningCharThreshold by mutableStateOf(prefs.getLlmVendorReasoningCharThreshold(LlmVendor.SF_FREE))
    var sfReasoningOnJson by mutableStateOf(prefs.getLlmVendorReasoningParamsOnJson(LlmVendor.SF_FREE))
    var sfReasoningOffJson by mutableStateOf(prefs.getLlmVendorReasoningParamsOffJson(LlmVendor.SF_FREE))
    var sfCustomReasoningParamsEnabled by mutableStateOf(
        prefs.getLlmVendorCustomReasoningParamsEnabled(LlmVendor.SF_FREE)
    )
    var sfTemperature by mutableStateOf(prefs.getLlmVendorTemperature(LlmVendor.SF_FREE).coerceIn(0f, 2f))
    var sfCustomModelInputVisible by mutableStateOf(
        sfModel.isNotBlank() && !sfPresetModels.contains(sfModel)
    )
    var builtinPresetModels by mutableStateOf(prefs.getLlmVendorModels(selectedVendor))
    var builtinCustomModelInputVisible by mutableStateOf(
        isBuiltinCustomModelVisible(selectedVendor, builtinConfig, builtinPresetModels)
    )
    var builtinReasoningOnJson by mutableStateOf(prefs.getLlmVendorReasoningParamsOnJson(selectedVendor))
    var builtinReasoningOffJson by mutableStateOf(prefs.getLlmVendorReasoningParamsOffJson(selectedVendor))
    var customModelInputVisible by mutableStateOf(false)
    var focusPromptTitleAfterAdd by mutableStateOf(false)
    var focusProfileNameAfterAdd by mutableStateOf(false)

    fun refreshSfState() {
        sfUseFreeService = !prefs.sfFreeLlmUsePaidKey
        sfApiKey = prefs.getLlmVendorApiKey(LlmVendor.SF_FREE)
        sfModel = currentSfModel(prefs)
        sfPresetModels = getSfPresetModels(prefs)
        sfStaticModels = getSfStaticModels(prefs)
        sfReasoningCharThreshold = prefs.getLlmVendorReasoningCharThreshold(LlmVendor.SF_FREE)
        sfReasoningOnJson = prefs.getLlmVendorReasoningParamsOnJson(LlmVendor.SF_FREE)
        sfReasoningOffJson = prefs.getLlmVendorReasoningParamsOffJson(LlmVendor.SF_FREE)
        sfCustomReasoningParamsEnabled = prefs.getLlmVendorCustomReasoningParamsEnabled(LlmVendor.SF_FREE)
        sfTemperature = prefs.getLlmVendorTemperature(LlmVendor.SF_FREE).coerceIn(0f, 2f)
        sfCustomModelInputVisible = sfModel.isNotBlank() && !sfPresetModels.contains(sfModel)
    }

    fun syncBuiltinVendor(
        selectedVendor: LlmVendor,
        builtinConfig: AiPostSettingsViewModel.BuiltinVendorConfig
    ) {
        builtinPresetModels = prefs.getLlmVendorModels(selectedVendor)
        builtinCustomModelInputVisible =
            isBuiltinCustomModelVisible(selectedVendor, builtinConfig, builtinPresetModels)
        builtinReasoningOnJson = prefs.getLlmVendorReasoningParamsOnJson(selectedVendor)
        builtinReasoningOffJson = prefs.getLlmVendorReasoningParamsOffJson(selectedVendor)
    }

    fun syncActiveProfile(provider: Prefs.LlmProvider?) {
        val presetModels = customProviderModels(provider)
        customModelInputVisible =
            provider?.model.orEmpty().isNotBlank() &&
            !presetModels.contains(provider?.model.orEmpty())
    }

    fun toRouteState(
        selectedVendor: LlmVendor,
        builtinConfig: AiPostSettingsViewModel.BuiltinVendorConfig,
        activeProfile: Prefs.LlmProvider?,
        activePromptPreset: PromptPreset?,
        llmTestRunning: Boolean
    ): AiSettingsRouteState = AiSettingsRouteState(
        selectedVendor = selectedVendor,
        builtinConfig = builtinConfig,
        activeProfile = activeProfile,
        activePromptPreset = activePromptPreset,
        postProcessEnabled = postProcessEnabled,
        typewriterEnabled = typewriterEnabled,
        aiEditPreferLastAsr = aiEditPreferLastAsr,
        skipUnderChars = skipUnderChars,
        aiEditCustomSystemPromptEnabled = aiEditCustomSystemPromptEnabled,
        aiEditSystemPrompt = aiEditSystemPrompt,
        sfUseFreeService = sfUseFreeService,
        sfApiKey = sfApiKey,
        sfModel = sfModel,
        sfReasoningCharThreshold = sfReasoningCharThreshold,
        sfReasoningOnJson = sfReasoningOnJson,
        sfReasoningOffJson = sfReasoningOffJson,
        sfCustomReasoningParamsEnabled = sfCustomReasoningParamsEnabled,
        sfTemperature = sfTemperature,
        sfPresetModels = sfPresetModels,
        sfStaticModels = sfStaticModels,
        builtinPresetModels = builtinPresetModels,
        sfCustomModelInputVisible = sfCustomModelInputVisible,
        builtinCustomModelInputVisible = builtinCustomModelInputVisible,
        builtinReasoningOnJson = builtinReasoningOnJson,
        builtinReasoningOffJson = builtinReasoningOffJson,
        customModelInputVisible = customModelInputVisible,
        focusProfileNameAfterAdd = focusProfileNameAfterAdd,
        focusPromptTitleAfterAdd = focusPromptTitleAfterAdd,
        llmTestRunning = llmTestRunning
    )
}

@Composable
internal fun rememberAiSettingsLocalState(
    prefs: Prefs,
    selectedVendor: LlmVendor,
    builtinConfig: AiPostSettingsViewModel.BuiltinVendorConfig
): AiSettingsLocalState = remember(prefs) {
    AiSettingsLocalState(prefs, selectedVendor, builtinConfig)
}

private fun isBuiltinCustomModelVisible(
    selectedVendor: LlmVendor,
    builtinConfig: AiPostSettingsViewModel.BuiltinVendorConfig,
    builtinPresetModels: List<String>
): Boolean {
    val displayModel = builtinConfig.model.ifBlank { selectedVendor.defaultModel }
    return displayModel.isNotBlank() && !builtinPresetModels.contains(displayModel)
}
