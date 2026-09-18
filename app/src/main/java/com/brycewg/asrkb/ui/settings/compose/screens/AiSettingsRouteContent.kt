/**
 * Compose AI 设置页路由内容编排。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.ai.AiPostSettingsViewModel
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics

@Composable
internal fun AiSettingsRouteContent(
    uiMode: BibiUiMode,
    innerPadding: PaddingValues,
    scrollModifier: Modifier,
    prefs: Prefs,
    viewModel: AiPostSettingsViewModel,
    routeState: AiSettingsRouteState,
    routeActions: AiSettingsRouteActions
) {
    val untitledProfile = stringResource(R.string.untitled_profile)
    val untitledPreset = stringResource(R.string.untitled_preset)

    with(routeState) {
        with(routeActions) {
            SettingsLazyColumn(
                uiMode = uiMode,
                modifier = Modifier.fillMaxSize(),
                miuixScrollModifier = scrollModifier,
                contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
            ) {
                item("prompt_selection_entry") {
                    AiSection(uiMode = uiMode, titleRes = R.string.section_prompt_selection_switch) {
                        AiActionPreference(
                            id = "prompt_selection_entry",
                            titleRes = R.string.title_prompt_selection,
                            index = 0,
                            count = 1,
                            onClick = onOpenPromptSelection
                        )
                        AiBodyText(
                            uiMode = uiMode,
                            textRes = R.string.helper_prompt_auto_select_enabled
                        )
                    }
                }

                item("post_process_scope") {
                    AiPostProcessSection(
                        uiMode = uiMode,
                        postProcessEnabled = postProcessEnabled,
                        typewriterEnabled = typewriterEnabled,
                        skipUnderChars = skipUnderChars,
                        onPostProcessChange = { checked ->
                            onShowExplainedSwitch(
                                postProcessEnabled,
                                checked,
                                R.string.label_ai_post_process_enabled,
                                R.string.feature_ai_post_process_off_desc,
                                R.string.feature_ai_post_process_on_desc,
                                "ai_post_process_enabled_explained",
                                { value ->
                                    prefs.postProcessEnabled = value
                                    onPostProcessEnabledChange(value)
                                },
                                { onSendRefreshBroadcast() }
                            )
                        },
                        onTypewriterChange = { checked ->
                            onShowExplainedSwitch(
                                typewriterEnabled,
                                checked,
                                R.string.label_postproc_typewriter_enabled,
                                R.string.feature_postproc_typewriter_off_desc,
                                R.string.feature_postproc_typewriter_on_desc,
                                "postproc_typewriter_explained",
                                { value ->
                                    prefs.postprocTypewriterEnabled = value
                                    onTypewriterEnabledChange(value)
                                },
                                {}
                            )
                        },
                        onSkipUnderCharsChange = { next ->
                            onSkipUnderCharsChange(next)
                            prefs.postprocSkipUnderChars = next
                        },
                        onSkipUnderCharsFinished = {}
                    )
                }

                item("ai_edit") {
                    AiEditSection(
                        uiMode = uiMode,
                        aiEditPreferLastAsr = aiEditPreferLastAsr,
                        customSystemPromptEnabled = aiEditCustomSystemPromptEnabled,
                        aiEditSystemPrompt = aiEditSystemPrompt,
                        defaultSystemPrompt = prefs.getLocalizedString(
                            R.string.llm_edit_system_prompt
                        ),
                        onAiEditPreferLastAsrChange = { checked ->
                            onShowExplainedSwitch(
                                aiEditPreferLastAsr,
                                checked,
                                R.string.label_ai_edit_default_use_last_asr,
                                R.string.feature_ai_edit_default_use_last_asr_off_desc,
                                R.string.feature_ai_edit_default_use_last_asr_on_desc,
                                "ai_edit_default_use_last_asr_explained",
                                { value ->
                                    prefs.aiEditDefaultToLastAsr = value
                                    onAiEditPreferLastAsrChange(value)
                                },
                                {}
                            )
                        },
                        onCustomSystemPromptEnabledChange = { checked ->
                            onAiEditCustomSystemPromptEnabledChange(checked)
                            prefs.aiEditCustomSystemPromptEnabled = checked
                        },
                        onAiEditSystemPromptChange = { value ->
                            onAiEditSystemPromptChange(value)
                            prefs.aiEditSystemPrompt = value
                        }
                    )
                }

                item("post_process_model") {
                    AiPostProcessModelSection(
                        uiMode = uiMode,
                        selectedVendor = selectedVendor,
                        selectedVendorName = stringResource(selectedVendor.displayNameResId),
                        builtinConfig = builtinConfig,
                        activeProfile = activeProfile,
                        sfUseFreeService = sfUseFreeService,
                        sfApiKey = sfApiKey,
                        sfModel = sfModel,
                        sfReasoningCharThreshold = sfReasoningCharThreshold,
                        sfReasoningOnJson = sfReasoningOnJson,
                        sfReasoningOffJson = sfReasoningOffJson,
                        sfTemperature = sfTemperature,
                        sfPresetModels = sfPresetModels,
                        sfStaticModels = sfStaticModels,
                        builtinPresetModels = builtinPresetModels,
                        sfCustomModelInputVisible = sfCustomModelInputVisible,
                        builtinCustomModelInputVisible = builtinCustomModelInputVisible,
                        builtinReasoningOnJson = builtinReasoningOnJson,
                        builtinReasoningOffJson = builtinReasoningOffJson,
                        sfCustomReasoningParamsEnabled = sfCustomReasoningParamsEnabled,
                        customModelInputVisible = customModelInputVisible,
                        focusProfileNameAfterAdd = focusProfileNameAfterAdd,
                        llmTestRunning = llmTestRunning,
                        sfActions = AiVendorActions(
                            onToggleSfFree = { checked ->
                                prefs.sfFreeLlmUsePaidKey = !checked
                                onRefreshSfState()
                            },
                            onSfApiKeyChange = { value ->
                                onSfApiKeyChange(value)
                                prefs.setLlmVendorApiKey(LlmVendor.SF_FREE, value)
                            },
                            onShowModelDialog = {
                                onShowSfModelDialog()
                            },
                            onCustomModelChange = { value ->
                                onSfCustomModelInputVisibleChange(true)
                                onSfModelChange(value)
                                if (value.isNotBlank()) {
                                    if (prefs.sfFreeLlmUsePaidKey) {
                                        prefs.setLlmVendorModel(LlmVendor.SF_FREE, value)
                                    } else {
                                        prefs.sfFreeLlmModel = value
                                    }
                                }
                            },
                            onFetchModels = {
                                if (prefs.sfFreeLlmUsePaidKey) {
                                    onFetchModels(
                                        LlmVendor.SF_FREE.endpoint,
                                        prefs.getLlmVendorApiKey(LlmVendor.SF_FREE)
                                    ) { models -> onShowBuiltinModelsPicker(LlmVendor.SF_FREE, models) }
                                }
                            },
                            onReasoningChange = { threshold ->
                                onSfReasoningCharThresholdChange(threshold)
                                prefs.setLlmVendorReasoningCharThreshold(LlmVendor.SF_FREE, threshold)
                            },
                            onCustomReasoningParamsEnabledChange = { checked ->
                                onSfCustomReasoningParamsEnabledChange(checked)
                                prefs.setLlmVendorCustomReasoningParamsEnabled(LlmVendor.SF_FREE, checked)
                            },
                            onReasoningOnJsonChange = { value ->
                                onSfReasoningOnJsonChange(value)
                                prefs.setLlmVendorReasoningParamsOnJson(LlmVendor.SF_FREE, value)
                            },
                            onReasoningOffJsonChange = { value ->
                                onSfReasoningOffJsonChange(value)
                                prefs.setLlmVendorReasoningParamsOffJson(LlmVendor.SF_FREE, value)
                            },
                            onTemperatureChange = { value ->
                                val next = value.coerceIn(0f, 2f)
                                onSfTemperatureChange(next)
                                prefs.setLlmVendorTemperature(LlmVendor.SF_FREE, next)
                            },
                            onOpenRegister = {
                                onOpenUrl(LlmVendor.SF_FREE.registerUrl)
                            },
                            onTestCall = {
                                onTestLlmCall()
                            }
                        ),
                        onChooseVendor = {
                            onShowVendorSelectionDialog()
                        },
                        onFocusedProfileName = { onFocusProfileNameAfterAddChange(false) },
                        onChooseProfile = {
                            onShowProfileDialog()
                        },
                        onProfileNameChange = { value ->
                            viewModel.updateActiveLlmProvider(prefs) { it.copy(name = value) }
                        },
                        onCustomEndpointChange = { value ->
                            viewModel.updateActiveLlmProvider(prefs) { it.copy(endpoint = value) }
                        },
                        onCustomApiKeyChange = { value ->
                            viewModel.updateActiveLlmProvider(prefs) { it.copy(apiKey = value) }
                        },
                        onChooseCustomModel = {
                            onShowCustomModelDialog()
                        },
                        onCustomModelChange = { value ->
                            viewModel.updateActiveLlmProvider(prefs) { it.copy(model = value) }
                        },
                        onFetchCustomModels = {
                            val provider = activeProfile
                            onFetchModels(
                                provider?.endpoint?.ifBlank { prefs.llmEndpoint } ?: prefs.llmEndpoint,
                                provider?.apiKey?.ifBlank { prefs.llmApiKey } ?: prefs.llmApiKey
                            ) { models -> onShowCustomModelsPicker(models) }
                        },
                        onCustomReasoningChange = { threshold ->
                            viewModel.updateActiveLlmProvider(prefs) {
                                it.withReasoningCharThreshold(threshold)
                            }
                        },
                        onCustomReasoningParamsEnabledChange = { checked ->
                            viewModel.updateActiveLlmProvider(prefs) {
                                it.copy(useCustomReasoningParams = checked)
                            }
                        },
                        onCustomReasoningOnJsonChange = { value ->
                            viewModel.updateActiveLlmProvider(prefs) {
                                it.copy(reasoningParamsOnJson = value)
                            }
                        },
                        onCustomReasoningOffJsonChange = { value ->
                            viewModel.updateActiveLlmProvider(prefs) {
                                it.copy(reasoningParamsOffJson = value)
                            }
                        },
                        onCustomTemperatureChange = { value ->
                            val next = value.coerceIn(0f, 2f)
                            viewModel.updateActiveLlmProvider(prefs) {
                                it.copy(temperature = next)
                            }
                        },
                        onAddProfile = {
                            if (viewModel.addLlmProvider(prefs, untitledProfile)) {
                                onFocusProfileNameAfterAddChange(true)
                                onMessage(R.string.toast_llm_profile_added)
                            }
                        },
                        onDeleteProfile = {
                            if (viewModel.deleteActiveLlmProvider(prefs)) {
                                onMessage(R.string.toast_llm_profile_deleted)
                            }
                        },
                        onBuiltinApiKeyChange = { value -> viewModel.updateBuiltinApiKey(prefs, value) },
                        onChooseBuiltinModel = {
                            onShowBuiltinModelDialog()
                        },
                        onBuiltinCustomModelChange = { value ->
                            onBuiltinCustomModelInputVisibleChange(true)
                            viewModel.updateBuiltinModel(prefs, value)
                        },
                        onFetchBuiltinModels = {
                            onFetchModels(
                                selectedVendor.endpoint,
                                prefs.getLlmVendorApiKey(selectedVendor)
                            ) { models -> onShowBuiltinModelsPicker(selectedVendor, models) }
                        },
                        onBuiltinReasoningChange = { threshold ->
                            viewModel.updateBuiltinReasoningCharThreshold(prefs, threshold)
                        },
                        onBuiltinCustomReasoningParamsEnabledChange = { checked ->
                            viewModel.updateBuiltinCustomReasoningParamsEnabled(prefs, checked)
                        },
                        onBuiltinReasoningOnJsonChange = { value ->
                            onBuiltinReasoningOnJsonChange(value)
                            prefs.setLlmVendorReasoningParamsOnJson(selectedVendor, value)
                        },
                        onBuiltinReasoningOffJsonChange = { value ->
                            onBuiltinReasoningOffJsonChange(value)
                            prefs.setLlmVendorReasoningParamsOffJson(selectedVendor, value)
                        },
                        onBuiltinTemperatureChange = { value ->
                            val next = value.coerceIn(
                                selectedVendor.temperatureMin,
                                selectedVendor.temperatureMax
                            )
                            viewModel.updateBuiltinTemperature(prefs, next)
                        },
                        onOpenBuiltinRegister = {
                            onOpenUrl(selectedVendor.registerUrl)
                        },
                        onTestCall = {
                            onTestLlmCall()
                        }
                    )
                }

                item("prompt_presets") {
                    AiPromptPresetRouteSection(
                        uiMode = uiMode,
                        preset = activePromptPreset,
                        focusTitleAfterAdd = focusPromptTitleAfterAdd,
                        onFocusedTitle = { onFocusPromptTitleAfterAddChange(false) },
                        onChoosePreset = {
                            onShowPromptPresetDialog()
                        },
                        onTitleChange = { value ->
                            viewModel.updateActivePromptPreset(prefs) { it.copy(title = value) }
                        },
                        onSkillChange = { value ->
                            viewModel.updateActivePromptPreset(prefs) { it.copy(skill = value) }
                        },
                        onContentChange = { value ->
                            viewModel.updateActivePromptPreset(prefs) { it.copy(content = value) }
                        },
                        onAddPreset = {
                            if (viewModel.addPromptPreset(prefs, untitledPreset, "")) {
                                onFocusPromptTitleAfterAddChange(true)
                                onMessage(R.string.toast_preset_added)
                            }
                        },
                        onDeletePreset = {
                            if (viewModel.deleteActivePromptPreset(prefs)) {
                                onMessage(R.string.toast_preset_deleted)
                            }
                        }
                    )
                }
            }
        }
    }
}
