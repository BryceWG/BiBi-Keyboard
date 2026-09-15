/**
 * Compose ASR 设置页路由内容。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.DashScopePrefsCompat
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsUiState
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsViewModel
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics

@Composable
internal fun AsrSettingsRouteContent(
    uiMode: BibiUiMode,
    context: Context,
    prefs: Prefs,
    viewModel: AsrSettingsViewModel,
    uiState: AsrSettingsUiState,
    innerPadding: PaddingValues,
    scrollModifier: Modifier,
    onlineState: AsrOnlineSettingsRouteState,
    localModelState: AsrLocalModelRouteState,
    backupState: AsrBackupSettingsRouteState,
    routeActions: AsrSettingsRouteActions
) {
    with(routeActions) {
        SettingsLazyColumn(
            uiMode = uiMode,
            modifier = Modifier.fillMaxSize(),
            miuixScrollModifier = scrollModifier,
            contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
        ) {
            item("silence") {
                AsrSilenceSection(
                    uiMode = uiMode,
                    autoStopMode = uiState.recordingAutoStopMode,
                    silenceWindowMs = uiState.silenceWindowMs,
                    silenceSensitivity = uiState.silenceSensitivity,
                    recordingMaxDurationMs = uiState.recordingMaxDurationMs,
                    onAutoStopModeChange = applyRecordingAutoStopMode,
                    onWindowChange = { value ->
                        viewModel.updateSilenceWindow(value.coerceIn(300, 5000))
                    },
                    onWindowFinished = {},
                    onSensitivityChange = { value ->
                        viewModel.updateSilenceSensitivity(value.coerceIn(1, 10))
                    },
                    onSensitivityFinished = {
                        rebuildVadIfNeeded()
                    },
                    onMaxDurationChange = { value ->
                        viewModel.updateRecordingMaxDuration(value)
                    },
                    onMaxDurationFinished = {
                    }
                )
            }

            item("vendor") {
                AsrSection(uiMode = uiMode, titleRes = R.string.label_asr_vendor) {
                    val vendorPrimaryItemCount = currentAsrVendorPrimaryItemCount(
                        selectedVendor = uiState.selectedVendor,
                        uiState = uiState,
                        onlineState = onlineState
                    )
                    val vendorGroupCount = 1 + vendorPrimaryItemCount
                    AsrValuePreference(
                        titleRes = R.string.label_asr_vendor,
                        value = AsrVendorUi.name(context, uiState.selectedVendor),
                        uiMode = uiMode,
                        highlightId = "asr_vendor",
                        index = 0,
                        count = vendorGroupCount,
                        onClick = {
                            showVendorPicker(
                                R.string.label_asr_vendor,
                                uiState.selectedVendor,
                                onPrimaryVendorSelected
                            )
                        }
                    )
                    if (uiState.selectedVendor == AsrVendor.Volc) {
                        AsrVolcRouteSection(
                            context = context,
                            uiMode = uiMode,
                            uiState = uiState,
                            appKey = onlineState.volcAppKey,
                            accessKey = onlineState.volcAccessKey,
                            apiKey = onlineState.volcApiKey,
                            onAppKeyChange = onlineState.onVolcAppKeyChange,
                            onAccessKeyChange = onlineState.onVolcAccessKeyChange,
                            onApiKeyChange = onlineState.onVolcApiKeyChange,
                            selectedModelId = uiState.volcAsrModel,
                            modelLabel = volcModelLabel(context, uiState.volcAsrModel),
                            onChooseModel = showVolcModelPicker,
                            onUpdateUseNewAuth = viewModel::updateVolcUseNewAuth,
                            onUpdateNonstream = viewModel::updateVolcNonstream,
                            onUpdateDdc = viewModel::updateVolcDdc,
                            onUpdateVad = viewModel::updateVolcVad,
                            applySwitch = applyVolcSwitch,
                            onLanguageSelected = viewModel::updateVolcLanguage,
                            primaryIndexOffset = 1,
                            primaryGroupCount = vendorGroupCount
                        )
                    }
                    if (uiState.selectedVendor == AsrVendor.DashScope) {
                        DashScopeConfig(
                            uiMode = uiMode,
                            apiKey = onlineState.dashApiKey,
                            onApiKeyChange = onlineState.onDashApiKeyChange,
                            modelLabel = dashModelLabel(context, onlineState.dashModel),
                            onChooseModel = {
                                showDashModelPicker()
                            },
                            prompt = onlineState.dashPrompt,
                            onPromptChange = onlineState.onDashPromptChange,
                            promptVisible = isDashPromptSupported(onlineState.dashModel),
                            selectedLanguage = onlineState.dashLanguage,
                            onLanguageSelected = { language ->
                                onlineState.onDashLanguageChange(language)
                            },
                            languageVisible = isDashLanguageSupported(onlineState.dashModel),
                            languageMultiSelect = DashScopePrefsCompat.isQwenAudioModel(onlineState.dashModel),
                            onChooseLanguages = showDashLanguagePicker,
                            selectedRegion = onlineState.dashRegion,
                            onRegionSelected = { region ->
                                onlineState.onDashRegionChange(region)
                            },
                            semanticPunct = onlineState.dashSemanticPunct,
                            semanticPunctVisible = isDashSemanticPunctuationSupported(onlineState.dashModel),
                            onSemanticPunctChange = { checked ->
                                onlineState.onDashSemanticPunctChange(checked)
                            },
                            onOpenGuide = {
                                onOpenUrl(DASH_SCOPE_ASR_GUIDE_URL)
                            },
                            primaryIndexOffset = 1,
                            primaryGroupCount = vendorGroupCount
                        )
                    }
                    CurrentAsrVendorConfig(
                        uiMode = uiMode,
                        selectedVendor = uiState.selectedVendor,
                        sfFreeAsrEnabled = onlineState.sfFreeAsrEnabled,
                        onSfFreeAsrEnabledChange = onlineState.onSfFreeAsrEnabledChange,
                        sfFreeAsrModel = onlineState.sfFreeAsrModel,
                        onChooseSfFreeAsrModel = {
                            showSfFreeModelPicker()
                        },
                        sfApiKey = onlineState.sfApiKey,
                        onSfApiKeyChange = onlineState.onSfApiKeyChange,
                        sfModel = onlineState.sfModel,
                        onChooseSfModel = {
                            showSfPaidModelPicker()
                        },
                        elevenApiKey = onlineState.elevenApiKey,
                        onElevenApiKeyChange = onlineState.onElevenApiKeyChange,
                        elevenStreaming = onlineState.elevenStreaming,
                        onElevenStreamingChange = onlineState.onElevenStreamingChange,
                        elevenLanguageCode = onlineState.elevenLanguageCode,
                        onElevenLanguageSelected = { language ->
                            onlineState.onElevenLanguageChange(language)
                        },
                        stepAudioApiKey = onlineState.stepAudioApiKey,
                        onStepAudioApiKeyChange = onlineState.onStepAudioApiKeyChange,
                        stepAudioEndpoint = onlineState.stepAudioEndpoint,
                        onStepAudioEndpointChange = onlineState.onStepAudioEndpointChange,
                        stepAudioEndpointPreset = onlineState.stepAudioEndpointPreset,
                        onStepAudioEndpointPresetChange = onlineState.onStepAudioEndpointPresetChange,
                        stepAudioModel = onlineState.stepAudioModel,
                        stepAudioCustomModelVisible = onlineState.stepAudioCustomModelVisible,
                        onStepAudioModelSelected = onlineState.onStepAudioModelSelected,
                        stepAudioCustomModelDraft = onlineState.stepAudioCustomModelDraft,
                        onStepAudioCustomModelDraftChange = onlineState.onStepAudioCustomModelDraftChange,
                        stepAudioLanguage = onlineState.stepAudioLanguage,
                        onStepAudioLanguageSelected = { language ->
                            onlineState.onStepAudioLanguageChange(language)
                        },
                        stepAudioUseItn = onlineState.stepAudioUseItn,
                        onStepAudioUseItnChange = onlineState.onStepAudioUseItnChange,
                        zhipuApiKey = onlineState.zhipuApiKey,
                        onZhipuApiKeyChange = onlineState.onZhipuApiKeyChange,
                        zhipuTemperature = onlineState.zhipuTemperature,
                        onZhipuTemperatureChange = onlineState.onZhipuTemperatureChange,
                        onZhipuTemperatureFinished = {},
                        cohereApiKey = onlineState.cohereApiKey,
                        onCohereApiKeyChange = onlineState.onCohereApiKeyChange,
                        cohereModel = onlineState.cohereModel,
                        onCohereModelChange = onlineState.onCohereModelChange,
                        cohereCustomModelVisible = onlineState.cohereCustomModelVisible,
                        onCohereCustomModelSelected = onlineState.onCohereCustomModelSelected,
                        cohereCustomModelDraft = onlineState.cohereCustomModelDraft,
                        onCohereCustomModelDraftChange = onlineState.onCohereCustomModelDraftChange,
                        cohereLanguage = onlineState.cohereLanguage,
                        onCohereLanguageSelected = onlineState.onCohereLanguageChange,
                        geminiApiKey = onlineState.geminiApiKey,
                        onGeminiApiKeyChange = onlineState.onGeminiApiKeyChange,
                        geminiEndpoint = onlineState.geminiEndpoint,
                        onGeminiEndpointChange = onlineState.onGeminiEndpointChange,
                        geminiModel = onlineState.geminiModel,
                        onGeminiModelChange = onlineState.onGeminiModelChange,
                        geminiPrompt = onlineState.geminiPrompt,
                        onGeminiPromptChange = onlineState.onGeminiPromptChange,
                        geminiDisableThinking = onlineState.geminiDisableThinking,
                        onGeminiDisableThinkingChange = onlineState.onGeminiDisableThinkingChange,
                        geminiAsrMode = onlineState.geminiAsrMode,
                        onGeminiAsrModeChange = onlineState.onGeminiAsrModeChange,
                        geminiTranscribeApiKey = onlineState.geminiTranscribeApiKey,
                        onGeminiTranscribeApiKeyChange = onlineState.onGeminiTranscribeApiKeyChange,
                        geminiTranscribeEndpoint = onlineState.geminiTranscribeEndpoint,
                        onGeminiTranscribeEndpointChange = onlineState.onGeminiTranscribeEndpointChange,
                        geminiTranscribeModel = onlineState.geminiTranscribeModel,
                        onGeminiTranscribeModelChange = onlineState.onGeminiTranscribeModelChange,
                        geminiTranscribeLanguage = onlineState.geminiTranscribeLanguage,
                        onGeminiTranscribeLanguageOptionSelected = onlineState.onGeminiTranscribeLanguageOptionSelected,
                        onGeminiTranscribeLanguageChange = onlineState.onGeminiTranscribeLanguageChange,
                        geminiTranscribeCustomLanguageVisible = onlineState.geminiTranscribeCustomLanguageVisible,
                        geminiTranscribeSmartEnabled = onlineState.geminiTranscribeSmartEnabled,
                        onGeminiTranscribeSmartEnabledChange = onlineState.onGeminiTranscribeSmartEnabledChange,
                        openRouterEndpoint = onlineState.openRouterEndpoint,
                        onOpenRouterEndpointChange = onlineState.onOpenRouterEndpointChange,
                        openRouterApiKey = onlineState.openRouterApiKey,
                        onOpenRouterApiKeyChange = onlineState.onOpenRouterApiKeyChange,
                        openRouterModel = onlineState.openRouterModel,
                        onOpenRouterModelChange = onlineState.onOpenRouterModelChange,
                        mimoApiKey = onlineState.mimoApiKey,
                        onMimoApiKeyChange = onlineState.onMimoApiKeyChange,
                        mimoEndpoint = onlineState.mimoEndpoint,
                        onMimoEndpointChange = onlineState.onMimoEndpointChange,
                        mimoEndpointPreset = onlineState.mimoEndpointPreset,
                        onMimoEndpointPresetChange = onlineState.onMimoEndpointPresetChange,
                        mimoLanguage = onlineState.mimoLanguage,
                        onMimoLanguageChange = onlineState.onMimoLanguageChange,
                        mimoPrompt = onlineState.mimoPrompt,
                        onMimoPromptChange = onlineState.onMimoPromptChange,
                        mimoModel = onlineState.mimoModel,
                        onMimoModelChange = onlineState.onMimoModelChange,
                        mimoPromptEnabled = onlineState.mimoPromptEnabled,
                        mimoDisableThinking = onlineState.mimoDisableThinking,
                        onMimoDisableThinkingChange = onlineState.onMimoDisableThinkingChange,
                        openAiProviders = onlineState.openAiProviders,
                        openAiActiveProviderId = onlineState.openAiActiveProviderId,
                        onOpenAiProviderSelected = onlineState.onOpenAiProviderSelected,
                        onOpenAiProviderAdded = onlineState.onOpenAiProviderAdded,
                        onOpenAiProviderDeleted = onlineState.onOpenAiProviderDeleted,
                        openAiProfileName = onlineState.openAiProfileName,
                        onOpenAiProfileNameChange = onlineState.onOpenAiProfileNameChange,
                        openAiEndpoint = onlineState.openAiEndpoint,
                        onOpenAiEndpointChange = onlineState.onOpenAiEndpointChange,
                        openAiApiKey = onlineState.openAiApiKey,
                        onOpenAiApiKeyChange = onlineState.onOpenAiApiKeyChange,
                        openAiModel = onlineState.openAiModel,
                        onOpenAiModelChange = onlineState.onOpenAiModelChange,
                        openAiStreaming = onlineState.openAiStreaming,
                        onOpenAiStreamingChange = onlineState.onOpenAiStreamingChange,
                        openAiUseCompletions = onlineState.openAiUseCompletions,
                        onOpenAiUseCompletionsChange = onlineState.onOpenAiUseCompletionsChange,
                        openAiUsePrompt = onlineState.openAiUsePrompt,
                        onOpenAiUsePromptChange = onlineState.onOpenAiUsePromptChange,
                        openAiPrompt = onlineState.openAiPrompt,
                        onOpenAiPromptChange = onlineState.onOpenAiPromptChange,
                        openAiLanguage = onlineState.openAiLanguage,
                        onOpenAiLanguageChange = onlineState.onOpenAiLanguageChange,
                        sonioxApiKey = onlineState.sonioxApiKey,
                        onSonioxApiKeyChange = onlineState.onSonioxApiKeyChange,
                        sonioxStreaming = onlineState.sonioxStreaming,
                        onSonioxStreamingChange = onlineState.onSonioxStreamingChange,
                        sonioxEndpointSensitivityLevel = onlineState.sonioxEndpointSensitivityLevel,
                        onSonioxEndpointSensitivityLevelChange = onlineState.onSonioxEndpointSensitivityLevelChange,
                        sonioxLanguages = onlineState.sonioxLanguages,
                        onChooseSonioxLanguages = showSonioxLanguagePicker,
                        sonioxLanguageStrict = onlineState.sonioxLanguageStrict,
                        onSonioxLanguageStrictChange = onlineState.onSonioxLanguageStrictChange,
                        onOpenGuide = onOpenUrl,
                        primaryIndexOffset = 1,
                        primaryGroupCount = vendorGroupCount
                    )
                    CurrentLocalAsrVendorConfig(
                        uiMode = uiMode,
                        selectedVendor = uiState.selectedVendor,
                        uiState = uiState,
                        localModelState = localModelState,
                        viewModel = viewModel,
                        onOpenGuide = {
                            onOpenUrl(context.getString(R.string.local_model_guide_config_doc_url))
                        },
                        onOpenPunctuationGuide = {
                            onOpenUrl(PUNCTUATION_MODEL_GUIDE_URL)
                        },
                        applySwitch = applyVolcSwitch,
                        primaryIndexOffset = 1,
                        primaryGroupCount = vendorGroupCount
                    )
                }
            }

            item("backup") {
                AsrBackupRouteSection(
                    uiMode = uiMode,
                    prefs = prefs,
                    enabled = backupState.enabled,
                    vendor = backupState.vendor,
                    vendorName = AsrVendorUi.name(context, backupState.vendor),
                    sensitivity = backupState.sensitivity,
                    localResidency = backupState.localResidency,
                    onEnabledChange = backupState.onEnabledChange,
                    onVendorChange = backupState.onVendorChange,
                    showVendorPicker = showVendorPicker,
                    showSensitivityPicker = showBackupSensitivityPicker,
                    showLocalResidencyPicker = showBackupLocalResidencyPicker
                )
            }
        }
    }
}

private const val PUNCTUATION_MODEL_GUIDE_URL =
    "https://bibidocs.brycewg.com/getting-started/asr-providers.html#%E9%80%9A%E7%94%A8%E6%A0%87%E7%82%B9%E6%A8%A1%E5%9E%8B-%E5%8F%AF%E9%80%89"

private fun currentAsrVendorPrimaryItemCount(
    selectedVendor: AsrVendor,
    uiState: AsrSettingsUiState,
    onlineState: AsrOnlineSettingsRouteState
): Int = when (selectedVendor) {
    AsrVendor.Volc -> volcenginePrimaryItemCount(
        streaming = uiState.isVolcStreaming,
        useNewAuth = uiState.volcUseNewAuth
    )

    AsrVendor.DashScope -> dashScopePrimaryItemCount(
        languageVisible = isDashLanguageSupported(onlineState.dashModel),
        semanticPunctVisible = isDashSemanticPunctuationSupported(onlineState.dashModel),
        promptVisible = isDashPromptSupported(onlineState.dashModel)
    )

    AsrVendor.SiliconFlow,
    AsrVendor.ElevenLabs,
    AsrVendor.StepAudio,
    AsrVendor.Zhipu,
    AsrVendor.Cohere,
    AsrVendor.OpenRouter,
    AsrVendor.MiMo,
    AsrVendor.OpenAI,
    AsrVendor.Soniox -> currentOnlineAsrPrimaryItemCount(
        selectedVendor = selectedVendor,
        openAiProviders = onlineState.openAiProviders,
        openAiUsePrompt = onlineState.openAiUsePrompt,
        openAiUseCompletions = onlineState.openAiUseCompletions,
        mimoCustomEndpointVisible = onlineState.mimoEndpointPreset == Prefs.MIMO_ENDPOINT_PRESET_CUSTOM,
        mimoPromptVisible = onlineState.mimoPromptEnabled,
        stepAudioCustomEndpointVisible =
        onlineState.stepAudioEndpointPreset == Prefs.STEPAUDIO_ENDPOINT_PRESET_CUSTOM,
        cohereCustomModelVisible = onlineState.cohereCustomModelVisible,
        stepAudioCustomModelVisible = onlineState.stepAudioCustomModelVisible
    )

    AsrVendor.Gemini -> if (onlineState.geminiAsrMode == com.brycewg.asrkb.asr.GeminiAsrMode.Gemini) {
        7
    } else {
        7 + if (onlineState.geminiTranscribeCustomLanguageVisible) 1 else 0
    }

    else -> localAsrPrimaryItemCount(selectedVendor)
}
