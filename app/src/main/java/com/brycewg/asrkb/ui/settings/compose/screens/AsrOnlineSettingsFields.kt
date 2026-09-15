/**
 * Compose ASR 设置页本地可编辑字段状态。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.brycewg.asrkb.asr.normalizeCohereLanguageForModel
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsViewModel
import java.util.UUID

internal const val GEMINI_TRANSCRIBE_CUSTOM_LANGUAGE_ID = "__custom__"

private val GEMINI_TRANSCRIBE_PRESET_LANGUAGES = setOf("", "cmn-Hans-CN", "yue-Hant-HK", "en-US", "ja-JP", "ko-KR")

internal fun isCustomGeminiTranscribeLanguage(value: String): Boolean = value.isNotBlank() && value !in GEMINI_TRANSCRIBE_PRESET_LANGUAGES

internal class AsrOnlineSettingsFields(
    private val prefs: Prefs
) {
    var volcAppKey by mutableStateOf(prefs.appKey)
    var volcAccessKey by mutableStateOf(prefs.accessKey)
    var volcApiKey by mutableStateOf(prefs.volcApiKey)
    var dashApiKey by mutableStateOf(prefs.dashApiKey)
    var dashModel by mutableStateOf(normalizeDashModel(prefs.dashAsrModel))
    var dashPrompt by mutableStateOf(prefs.dashPrompt)
    var dashLanguage by mutableStateOf(prefs.dashLanguage)
    var dashRegion by mutableStateOf(normalizeDashRegion(prefs.dashRegion))
    var dashSemanticPunct by mutableStateOf(prefs.dashSemanticPunctEnabled)
    var sfFreeAsrEnabled by mutableStateOf(prefs.sfFreeAsrEnabled)
    var sfFreeAsrModel by mutableStateOf(displaySfFreeAsrModel(prefs))
    var sfApiKey by mutableStateOf(prefs.sfApiKey)
    var sfModel by mutableStateOf(displaySfPaidModel(prefs))
    var elevenApiKey by mutableStateOf(prefs.elevenApiKey)
    var elevenStreaming by mutableStateOf(prefs.elevenStreamingEnabled)
    var elevenLanguageCode by mutableStateOf(prefs.elevenLanguageCode)
    var stepAudioApiKey by mutableStateOf(prefs.stepAudioApiKey)
    var stepAudioEndpoint by mutableStateOf(prefs.getEffectiveStepAudioAsrEndpoint())
    var stepAudioEndpointPreset by mutableStateOf(prefs.stepAudioEndpointPreset)
    var stepAudioModel by mutableStateOf(displayStepAudioModel(prefs))
    var stepAudioCustomModelVisible by mutableStateOf(isCustomStepAudioModel(stepAudioModel))
    var stepAudioCustomModelDraft by mutableStateOf(stepAudioModel.takeIf(::isCustomStepAudioModel).orEmpty())
    var stepAudioLanguage by mutableStateOf(prefs.stepAudioLanguage.trim())
    var stepAudioUseItn by mutableStateOf(prefs.stepAudioUseItn)
    var zhipuApiKey by mutableStateOf(prefs.zhipuApiKey)
    var zhipuTemperature by mutableStateOf(prefs.zhipuTemperature.coerceIn(0f, 1f))
    var cohereApiKey by mutableStateOf(prefs.cohereApiKey)
    var cohereModel by mutableStateOf(prefs.cohereAsrModel)
    var cohereCustomModelVisible by mutableStateOf(isCohereCustomModel(cohereModel))
    var cohereCustomModelDraft by mutableStateOf(
        cohereModel.takeIf(::isCohereCustomModel).orEmpty()
    )
    var cohereLanguage by mutableStateOf(readAndPersistNormalizedCohereLanguage())
    var geminiApiKey by mutableStateOf(prefs.gemApiKey)
    var geminiEndpoint by mutableStateOf(prefs.gemEndpoint.ifBlank { Prefs.DEFAULT_GEM_ENDPOINT })
    var geminiModel by mutableStateOf(prefs.gemModel)
    var geminiPrompt by mutableStateOf(prefs.gemPrompt)
    var geminiDisableThinking by mutableStateOf(prefs.geminiDisableThinking)
    var geminiAsrMode by mutableStateOf(prefs.geminiAsrMode)
    var geminiTranscribeApiKey by mutableStateOf(prefs.gemTranscribeApiKey)
    var geminiTranscribeEndpoint by mutableStateOf(prefs.gemTranscribeEndpoint)
    var geminiTranscribeModel by mutableStateOf(prefs.gemTranscribeModel)
    var geminiTranscribeLanguage by mutableStateOf(prefs.gemTranscribeLanguage)
    var geminiTranscribeCustomLanguageVisible by mutableStateOf(
        isCustomGeminiTranscribeLanguage(prefs.gemTranscribeLanguage)
    )
    var geminiTranscribeSmartEnabled by mutableStateOf(prefs.gemTranscribeSmartEnabled)
    var openRouterEndpoint by mutableStateOf(
        prefs.openRouterAsrEndpoint.ifBlank { Prefs.DEFAULT_OPENROUTER_ASR_ENDPOINT }
    )
    var openRouterApiKey by mutableStateOf(prefs.openRouterAsrApiKey)
    var openRouterModel by mutableStateOf(
        prefs.openRouterAsrModel.ifBlank { Prefs.DEFAULT_OPENROUTER_ASR_MODEL }
    )
    var mimoApiKey by mutableStateOf(prefs.mimoAsrApiKey)
    var mimoEndpoint by mutableStateOf(
        prefs.getEffectiveMimoAsrEndpoint()
    )
    var mimoEndpointPreset by mutableStateOf(prefs.mimoAsrEndpointPreset)
    var mimoLanguage by mutableStateOf(normalizeMimoLanguage(prefs.mimoAsrLanguage))
    var mimoPrompt by mutableStateOf(prefs.mimoAsrPrompt)
    var mimoModel by mutableStateOf(prefs.mimoAsrModel)
    var mimoDisableThinking by mutableStateOf(prefs.mimoAsrDisableThinking)
    var openAiProviders by mutableStateOf(prefs.getOpenAiAsrProviders())
    var openAiActiveProviderId by mutableStateOf(prefs.activeOpenAiAsrProviderId)
    var openAiProfileName by mutableStateOf(prefs.getActiveOpenAiAsrProvider()?.name.orEmpty())
    var openAiEndpoint by mutableStateOf(prefs.oaAsrEndpoint)
    var openAiApiKey by mutableStateOf(prefs.oaAsrApiKey)
    var openAiModel by mutableStateOf(prefs.oaAsrModel)
    var openAiStreaming by mutableStateOf(prefs.oaAsrStreamingEnabled)
    var openAiUseCompletions by mutableStateOf(prefs.oaAsrUseCompletions)
    var openAiUsePrompt by mutableStateOf(prefs.oaAsrUsePrompt)
    var openAiPrompt by mutableStateOf(prefs.oaAsrPrompt)
    var openAiLanguage by mutableStateOf(prefs.oaAsrLanguage)
    var sonioxApiKey by mutableStateOf(prefs.sonioxApiKey)
    var sonioxStreaming by mutableStateOf(prefs.sonioxStreamingEnabled)
    var sonioxEndpointSensitivityLevel by mutableStateOf(prefs.sonioxEndpointSensitivityLevel)
    var sonioxLanguages by mutableStateOf(prefs.getSonioxLanguages())
    var sonioxLanguageStrict by mutableStateOf(prefs.sonioxLanguageHintsStrict)

    fun refreshFromPrefs() {
        volcAppKey = prefs.appKey
        volcAccessKey = prefs.accessKey
        volcApiKey = prefs.volcApiKey
        dashApiKey = prefs.dashApiKey
        dashModel = normalizeDashModel(prefs.dashAsrModel)
        dashPrompt = prefs.dashPrompt
        dashLanguage = prefs.dashLanguage
        dashRegion = normalizeDashRegion(prefs.dashRegion)
        dashSemanticPunct = prefs.dashSemanticPunctEnabled
        sfFreeAsrEnabled = prefs.sfFreeAsrEnabled
        sfFreeAsrModel = displaySfFreeAsrModel(prefs)
        sfApiKey = prefs.sfApiKey
        sfModel = displaySfPaidModel(prefs)
        elevenApiKey = prefs.elevenApiKey
        elevenStreaming = prefs.elevenStreamingEnabled
        elevenLanguageCode = prefs.elevenLanguageCode
        stepAudioApiKey = prefs.stepAudioApiKey
        stepAudioEndpoint = prefs.getEffectiveStepAudioAsrEndpoint()
        stepAudioEndpointPreset = prefs.stepAudioEndpointPreset
        stepAudioModel = displayStepAudioModel(prefs)
        stepAudioCustomModelVisible = isCustomStepAudioModel(stepAudioModel)
        stepAudioCustomModelDraft = stepAudioModel.takeIf(::isCustomStepAudioModel).orEmpty()
        stepAudioLanguage = prefs.stepAudioLanguage.trim()
        stepAudioUseItn = prefs.stepAudioUseItn
        zhipuApiKey = prefs.zhipuApiKey
        zhipuTemperature = prefs.zhipuTemperature.coerceIn(0f, 1f)
        cohereApiKey = prefs.cohereApiKey
        cohereModel = prefs.cohereAsrModel
        cohereCustomModelVisible = isCohereCustomModel(cohereModel)
        cohereCustomModelDraft = cohereModel.takeIf(::isCohereCustomModel).orEmpty()
        cohereLanguage = readAndPersistNormalizedCohereLanguage()
        geminiApiKey = prefs.gemApiKey
        geminiEndpoint = prefs.gemEndpoint.ifBlank { Prefs.DEFAULT_GEM_ENDPOINT }
        geminiModel = prefs.gemModel
        geminiPrompt = prefs.gemPrompt
        geminiDisableThinking = prefs.geminiDisableThinking
        geminiAsrMode = prefs.geminiAsrMode
        geminiTranscribeApiKey = prefs.gemTranscribeApiKey
        geminiTranscribeEndpoint = prefs.gemTranscribeEndpoint
        geminiTranscribeModel = prefs.gemTranscribeModel
        geminiTranscribeLanguage = prefs.gemTranscribeLanguage
        geminiTranscribeCustomLanguageVisible = isCustomGeminiTranscribeLanguage(geminiTranscribeLanguage)
        geminiTranscribeSmartEnabled = prefs.gemTranscribeSmartEnabled
        openRouterEndpoint = prefs.openRouterAsrEndpoint.ifBlank {
            Prefs.DEFAULT_OPENROUTER_ASR_ENDPOINT
        }
        openRouterApiKey = prefs.openRouterAsrApiKey
        openRouterModel = prefs.openRouterAsrModel.ifBlank {
            Prefs.DEFAULT_OPENROUTER_ASR_MODEL
        }
        mimoApiKey = prefs.mimoAsrApiKey
        mimoEndpoint = prefs.getEffectiveMimoAsrEndpoint()
        mimoEndpointPreset = prefs.mimoAsrEndpointPreset
        mimoLanguage = normalizeMimoLanguage(prefs.mimoAsrLanguage)
        mimoPrompt = prefs.mimoAsrPrompt
        mimoModel = prefs.mimoAsrModel
        mimoDisableThinking = prefs.mimoAsrDisableThinking
        refreshOpenAiFromPrefs()
        sonioxApiKey = prefs.sonioxApiKey
        sonioxStreaming = prefs.sonioxStreamingEnabled
        sonioxEndpointSensitivityLevel = prefs.sonioxEndpointSensitivityLevel
        sonioxLanguages = prefs.getSonioxLanguages()
        sonioxLanguageStrict = prefs.sonioxLanguageHintsStrict
    }

    private fun readAndPersistNormalizedCohereLanguage(): String {
        val normalized = normalizeCohereLanguageForModel(
            prefs.cohereAsrModel,
            prefs.cohereAsrLanguage
        )
        if (normalized != prefs.cohereAsrLanguage) {
            prefs.cohereAsrLanguage = normalized
        }
        return normalized
    }

    private fun refreshOpenAiFromPrefs() {
        openAiProviders = prefs.getOpenAiAsrProviders()
        openAiActiveProviderId = prefs.activeOpenAiAsrProviderId
        openAiProfileName = prefs.getActiveOpenAiAsrProvider()?.name.orEmpty()
        openAiEndpoint = prefs.oaAsrEndpoint
        openAiApiKey = prefs.oaAsrApiKey
        openAiModel = prefs.oaAsrModel
        openAiStreaming = prefs.oaAsrStreamingEnabled
        openAiUseCompletions = prefs.oaAsrUseCompletions
        openAiUsePrompt = prefs.oaAsrUsePrompt
        openAiPrompt = prefs.oaAsrPrompt
        openAiLanguage = prefs.oaAsrLanguage
    }

    fun showCohereCustomModelInput() {
        cohereCustomModelVisible = true
        cohereCustomModelDraft = cohereModel.takeIf(::isCohereCustomModel).orEmpty()
    }

    fun updateCohereCustomModelDraft(value: String) {
        cohereCustomModelDraft = value
        if (value.isNotBlank()) {
            cohereModel = value
            prefs.cohereAsrModel = value
        }
    }

    fun toRouteState(
        viewModel: AsrSettingsViewModel,
        applyDashSemanticPunctSwitch: (Boolean) -> Unit,
        applyElevenStreamingSwitch: (Boolean) -> Unit,
        applyGeminiThinkingSwitch: (Boolean) -> Unit,
        applyGeminiTranscribeSmartSwitch: (Boolean) -> Unit,
        applyMimoDisableThinkingSwitch: (Boolean) -> Unit,
        applyOpenAiStreamingSwitch: (Boolean) -> Unit,
        applyOpenAiUseCompletionsSwitch: (Boolean) -> Unit,
        applyOpenAiUsePromptSwitch: (Boolean) -> Unit,
        applySonioxStreamingSwitch: (Boolean) -> Unit,
        applySonioxLanguageStrictSwitch: (Boolean) -> Unit,
        applyStepAudioUseItnSwitch: (Boolean) -> Unit,
        openAiDefaultProfileName: (Int) -> String
    ): AsrOnlineSettingsRouteState = AsrOnlineSettingsRouteState(
        volcAppKey = volcAppKey,
        onVolcAppKeyChange = { value ->
            volcAppKey = value
            prefs.appKey = value
        },
        volcAccessKey = volcAccessKey,
        onVolcAccessKeyChange = { value ->
            volcAccessKey = value
            prefs.accessKey = value
        },
        volcApiKey = volcApiKey,
        onVolcApiKeyChange = { value ->
            volcApiKey = value
            prefs.volcApiKey = value
        },
        dashApiKey = dashApiKey,
        onDashApiKeyChange = { value ->
            dashApiKey = value
            prefs.dashApiKey = value
        },
        dashModel = dashModel,
        dashPrompt = dashPrompt,
        onDashPromptChange = { value ->
            dashPrompt = value
            prefs.dashPrompt = value
        },
        dashLanguage = dashLanguage,
        onDashLanguageChange = { value ->
            dashLanguage = value
            prefs.dashLanguage = value
        },
        dashRegion = dashRegion,
        onDashRegionChange = { value ->
            dashRegion = value
            prefs.dashRegion = value
        },
        dashSemanticPunct = dashSemanticPunct,
        onDashSemanticPunctChange = applyDashSemanticPunctSwitch,
        sfFreeAsrEnabled = sfFreeAsrEnabled,
        onSfFreeAsrEnabledChange = { checked ->
            sfFreeAsrEnabled = checked
            prefs.sfFreeAsrEnabled = checked
        },
        sfFreeAsrModel = sfFreeAsrModel,
        sfApiKey = sfApiKey,
        onSfApiKeyChange = { value ->
            sfApiKey = value
            prefs.sfApiKey = value
        },
        sfModel = sfModel,
        elevenApiKey = elevenApiKey,
        onElevenApiKeyChange = { value ->
            elevenApiKey = value
            prefs.elevenApiKey = value
        },
        elevenStreaming = elevenStreaming,
        onElevenStreamingChange = { checked ->
            applyElevenStreamingSwitch(checked)
        },
        elevenLanguageCode = elevenLanguageCode,
        onElevenLanguageChange = { value ->
            elevenLanguageCode = value
            prefs.elevenLanguageCode = value
        },
        stepAudioApiKey = stepAudioApiKey,
        onStepAudioApiKeyChange = { value ->
            stepAudioApiKey = value
            prefs.stepAudioApiKey = value
        },
        stepAudioEndpoint = stepAudioEndpoint,
        onStepAudioEndpointChange = { value ->
            stepAudioEndpoint = value
            prefs.stepAudioEndpoint = value
        },
        stepAudioEndpointPreset = stepAudioEndpointPreset,
        onStepAudioEndpointPresetChange = { value ->
            stepAudioEndpointPreset = value
            prefs.stepAudioEndpointPreset = value
            val presetUrl = Prefs.STEPAUDIO_ENDPOINT_PRESETS[value]
            if (value != Prefs.STEPAUDIO_ENDPOINT_PRESET_CUSTOM && presetUrl != null) {
                stepAudioEndpoint = presetUrl
                prefs.stepAudioEndpoint = presetUrl
            } else {
                stepAudioEndpoint = ""
                prefs.stepAudioEndpoint = ""
            }
            stepAudioApiKey = prefs.stepAudioApiKey
        },
        stepAudioModel = stepAudioModel,
        stepAudioCustomModelVisible = stepAudioCustomModelVisible,
        onStepAudioModelSelected = { value ->
            if (value == STEPAUDIO_CUSTOM_MODEL_OPTION_ID) {
                stepAudioCustomModelVisible = true
                stepAudioCustomModelDraft = prefs.stepAudioModel.takeIf(::isCustomStepAudioModel).orEmpty()
            } else {
                stepAudioCustomModelVisible = false
                stepAudioModel = value
                prefs.stepAudioModel = value
            }
        },
        stepAudioCustomModelDraft = stepAudioCustomModelDraft,
        onStepAudioCustomModelDraftChange = { value ->
            stepAudioCustomModelDraft = value
            if (value.isNotBlank()) {
                stepAudioModel = value
                prefs.stepAudioModel = value.trim()
            }
        },
        stepAudioLanguage = stepAudioLanguage,
        onStepAudioLanguageChange = { value ->
            stepAudioLanguage = value
            prefs.stepAudioLanguage = value
        },
        stepAudioUseItn = stepAudioUseItn,
        onStepAudioUseItnChange = { checked ->
            applyStepAudioUseItnSwitch(checked)
        },
        zhipuApiKey = zhipuApiKey,
        onZhipuApiKeyChange = { value ->
            zhipuApiKey = value
            prefs.zhipuApiKey = value
        },
        zhipuTemperature = zhipuTemperature,
        onZhipuTemperatureChange = { value ->
            val next = value.coerceIn(0f, 1f)
            zhipuTemperature = next
            prefs.zhipuTemperature = next
        },
        cohereApiKey = cohereApiKey,
        onCohereApiKeyChange = { value ->
            val key = value.removeBearerPrefix()
            cohereApiKey = key
            prefs.cohereApiKey = key
        },
        cohereModel = cohereModel,
        onCohereModelChange = { value ->
            cohereModel = value
            prefs.cohereAsrModel = value
            cohereCustomModelVisible = isCohereCustomModel(value)
        },
        cohereCustomModelVisible = cohereCustomModelVisible,
        onCohereCustomModelSelected = ::showCohereCustomModelInput,
        cohereCustomModelDraft = cohereCustomModelDraft,
        onCohereCustomModelDraftChange = ::updateCohereCustomModelDraft,
        cohereLanguage = cohereLanguage,
        onCohereLanguageChange = { value ->
            cohereLanguage = value
            prefs.cohereAsrLanguage = value
        },
        geminiApiKey = geminiApiKey,
        onGeminiApiKeyChange = { value ->
            geminiApiKey = value
            prefs.gemApiKey = value
        },
        geminiEndpoint = geminiEndpoint,
        onGeminiEndpointChange = { value ->
            geminiEndpoint = value
            prefs.gemEndpoint = value
        },
        geminiModel = geminiModel,
        onGeminiModelChange = { value ->
            geminiModel = value
            prefs.gemModel = value
        },
        geminiPrompt = geminiPrompt,
        onGeminiPromptChange = { value ->
            geminiPrompt = value
            prefs.gemPrompt = value
        },
        geminiDisableThinking = geminiDisableThinking,
        onGeminiDisableThinkingChange = { checked ->
            applyGeminiThinkingSwitch(checked)
        },
        geminiAsrMode = geminiAsrMode,
        onGeminiAsrModeChange = { mode ->
            geminiAsrMode = mode
            prefs.geminiAsrMode = mode
        },
        geminiTranscribeApiKey = geminiTranscribeApiKey,
        onGeminiTranscribeApiKeyChange = { value ->
            geminiTranscribeApiKey = value
            prefs.gemTranscribeApiKey = value
        },
        geminiTranscribeEndpoint = geminiTranscribeEndpoint,
        onGeminiTranscribeEndpointChange = { value ->
            geminiTranscribeEndpoint = value
            prefs.gemTranscribeEndpoint = value
        },
        geminiTranscribeModel = geminiTranscribeModel,
        onGeminiTranscribeModelChange = { value ->
            geminiTranscribeModel = value
            prefs.gemTranscribeModel = value
        },
        geminiTranscribeLanguage = geminiTranscribeLanguage,
        onGeminiTranscribeLanguageOptionSelected = { value ->
            if (value == GEMINI_TRANSCRIBE_CUSTOM_LANGUAGE_ID) {
                geminiTranscribeCustomLanguageVisible = true
                if (!isCustomGeminiTranscribeLanguage(geminiTranscribeLanguage)) {
                    geminiTranscribeLanguage = ""
                    prefs.gemTranscribeLanguage = ""
                }
            } else {
                geminiTranscribeCustomLanguageVisible = false
                geminiTranscribeLanguage = value
                prefs.gemTranscribeLanguage = value
            }
        },
        onGeminiTranscribeLanguageChange = { value ->
            geminiTranscribeLanguage = value
            prefs.gemTranscribeLanguage = value
        },
        geminiTranscribeCustomLanguageVisible = geminiTranscribeCustomLanguageVisible,
        geminiTranscribeSmartEnabled = geminiTranscribeSmartEnabled,
        onGeminiTranscribeSmartEnabledChange = applyGeminiTranscribeSmartSwitch,
        openRouterEndpoint = openRouterEndpoint,
        onOpenRouterEndpointChange = { value ->
            openRouterEndpoint = value
            prefs.openRouterAsrEndpoint = value
        },
        openRouterApiKey = openRouterApiKey,
        onOpenRouterApiKeyChange = { value ->
            val key = value.removeBearerPrefix()
            openRouterApiKey = key
            prefs.openRouterAsrApiKey = key
        },
        openRouterModel = openRouterModel,
        onOpenRouterModelChange = { value ->
            openRouterModel = value
            prefs.openRouterAsrModel = value
        },
        mimoApiKey = mimoApiKey,
        onMimoApiKeyChange = { value ->
            mimoApiKey = value
            prefs.mimoAsrApiKey = value
        },
        mimoEndpoint = mimoEndpoint,
        onMimoEndpointChange = { value ->
            mimoEndpoint = value
            prefs.mimoAsrEndpoint = value
        },
        mimoEndpointPreset = mimoEndpointPreset,
        onMimoEndpointPresetChange = { value ->
            mimoEndpointPreset = value
            prefs.mimoAsrEndpointPreset = value
            val presetUrl = Prefs.MIMO_ENDPOINT_PRESETS[value]
            if (value != Prefs.MIMO_ENDPOINT_PRESET_CUSTOM && presetUrl != null) {
                mimoEndpoint = presetUrl
                prefs.mimoAsrEndpoint = presetUrl
            } else {
                mimoEndpoint = ""
                prefs.mimoAsrEndpoint = ""
            }
            mimoApiKey = prefs.mimoAsrApiKey
        },
        mimoLanguage = mimoLanguage,
        onMimoLanguageChange = { value ->
            val next = normalizeMimoLanguage(value)
            mimoLanguage = next
            prefs.mimoAsrLanguage = next
        },
        mimoPrompt = mimoPrompt,
        onMimoPromptChange = { value ->
            mimoPrompt = value
            prefs.mimoAsrPrompt = value
        },
        mimoModel = mimoModel,
        onMimoModelChange = { value ->
            mimoModel = value
            prefs.mimoAsrModel = value
        },
        mimoPromptEnabled = shouldShowMimoPrompt(mimoModel),
        mimoDisableThinking = mimoDisableThinking,
        onMimoDisableThinkingChange = { checked ->
            applyMimoDisableThinkingSwitch(checked)
        },
        openAiProviders = openAiProviders,
        openAiActiveProviderId = openAiActiveProviderId,
        onOpenAiProviderSelected = { providerId ->
            if (prefs.selectOpenAiAsrProvider(providerId)) {
                refreshOpenAiFromPrefs()
                viewModel.refreshOpenAiProfileState()
            }
        },
        onOpenAiProviderAdded = {
            val list = prefs.getOpenAiAsrProviders().toMutableList()
            val nextIndex = list.size + 1
            val profile = Prefs.OpenAiAsrProvider(
                id = UUID.randomUUID().toString(),
                name = openAiDefaultProfileName(nextIndex),
                endpoint = Prefs.DEFAULT_OA_ASR_ENDPOINT,
                apiKey = "",
                model = Prefs.DEFAULT_OA_ASR_MODEL,
                streamingEnabled = true,
                useCompletions = false,
                usePrompt = false,
                prompt = "",
                language = ""
            )
            list.add(profile)
            prefs.setOpenAiAsrProviders(list)
            prefs.selectOpenAiAsrProvider(profile.id)
            refreshOpenAiFromPrefs()
            viewModel.refreshOpenAiProfileState()
        },
        onOpenAiProviderDeleted = {
            val list = prefs.getOpenAiAsrProviders().toMutableList()
            if (list.size <= 1) {
                false
            } else {
                val activeId = prefs.activeOpenAiAsrProviderId
                val idx = list.indexOfFirst { it.id == activeId }
                if (idx < 0) {
                    false
                } else {
                    list.removeAt(idx)
                    prefs.setOpenAiAsrProviders(list)
                    val nextActive = list.getOrNull(idx.coerceAtMost(list.lastIndex))
                        ?: list.firstOrNull()
                    if (nextActive != null) prefs.selectOpenAiAsrProvider(nextActive.id)
                    refreshOpenAiFromPrefs()
                    viewModel.refreshOpenAiProfileState()
                    true
                }
            }
        },
        openAiProfileName = openAiProfileName,
        onOpenAiProfileNameChange = { value ->
            openAiProfileName = value
            prefs.updateActiveOpenAiAsrProvider { it.copy(name = value) }
            openAiProviders = prefs.getOpenAiAsrProviders()
        },
        openAiEndpoint = openAiEndpoint,
        onOpenAiEndpointChange = { value ->
            openAiEndpoint = value
            prefs.oaAsrEndpoint = value
        },
        openAiApiKey = openAiApiKey,
        onOpenAiApiKeyChange = { value ->
            openAiApiKey = value
            prefs.oaAsrApiKey = value
        },
        openAiModel = openAiModel,
        onOpenAiModelChange = { value ->
            openAiModel = value
            prefs.oaAsrModel = value
        },
        openAiStreaming = openAiStreaming,
        onOpenAiStreamingChange = { checked ->
            applyOpenAiStreamingSwitch(checked)
        },
        openAiUseCompletions = openAiUseCompletions,
        onOpenAiUseCompletionsChange = { checked ->
            applyOpenAiUseCompletionsSwitch(checked)
        },
        openAiUsePrompt = openAiUsePrompt,
        onOpenAiUsePromptChange = { checked ->
            applyOpenAiUsePromptSwitch(checked)
        },
        openAiPrompt = openAiPrompt,
        onOpenAiPromptChange = { value ->
            openAiPrompt = value
            prefs.oaAsrPrompt = value
        },
        openAiLanguage = openAiLanguage,
        onOpenAiLanguageChange = { value ->
            openAiLanguage = value
            prefs.oaAsrLanguage = value
        },
        sonioxApiKey = sonioxApiKey,
        onSonioxApiKeyChange = { value ->
            sonioxApiKey = value
            prefs.sonioxApiKey = value
        },
        sonioxStreaming = sonioxStreaming,
        onSonioxStreamingChange = { checked ->
            applySonioxStreamingSwitch(checked)
        },
        sonioxEndpointSensitivityLevel = sonioxEndpointSensitivityLevel,
        onSonioxEndpointSensitivityLevelChange = { level ->
            sonioxEndpointSensitivityLevel = level
            prefs.sonioxEndpointSensitivityLevel = level
        },
        sonioxLanguages = sonioxLanguages,
        sonioxLanguageStrict = sonioxLanguageStrict,
        onSonioxLanguageStrictChange = { checked ->
            applySonioxLanguageStrictSwitch(checked)
        }
    )
}

@Composable
internal fun rememberAsrOnlineSettingsFields(prefs: Prefs): AsrOnlineSettingsFields = remember(prefs) { AsrOnlineSettingsFields(prefs) }

private fun String.removeBearerPrefix(): String = replace(
    Regex("^Bearer\\s+", RegexOption.IGNORE_CASE),
    ""
).trim()

private fun normalizeMimoLanguage(value: String): String = value.trim().ifBlank { Prefs.DEFAULT_MIMO_ASR_LANGUAGE }

private fun shouldShowMimoPrompt(model: String): Boolean = model.isNotBlank() && !model.endsWith("-asr", ignoreCase = true)
