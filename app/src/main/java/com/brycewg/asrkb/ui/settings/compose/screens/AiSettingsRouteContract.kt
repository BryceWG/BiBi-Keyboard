/**
 * Compose AI 设置页路由状态与动作契约。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptPreset
import com.brycewg.asrkb.ui.settings.ai.AiPostSettingsViewModel

internal typealias AiExplainedSwitchHandler = (
    current: Boolean,
    target: Boolean,
    titleRes: Int,
    offDescRes: Int,
    onDescRes: Int,
    preferenceKey: String,
    write: (Boolean) -> Unit,
    afterWrite: (Boolean) -> Unit
) -> Unit

internal data class AiSettingsRouteState(
    val selectedVendor: LlmVendor,
    val builtinConfig: AiPostSettingsViewModel.BuiltinVendorConfig,
    val activeProfile: Prefs.LlmProvider?,
    val activePromptPreset: PromptPreset?,
    val postProcessEnabled: Boolean,
    val typewriterEnabled: Boolean,
    val aiEditPreferLastAsr: Boolean,
    val skipUnderChars: Int,
    val aiEditCustomSystemPromptEnabled: Boolean,
    val aiEditSystemPrompt: String,
    val sfUseFreeService: Boolean,
    val sfApiKey: String,
    val sfModel: String,
    val sfReasoningCharThreshold: Int,
    val sfReasoningOnJson: String,
    val sfReasoningOffJson: String,
    val sfCustomReasoningParamsEnabled: Boolean,
    val sfTemperature: Float,
    val sfPresetModels: List<String>,
    val sfStaticModels: List<String>,
    val builtinPresetModels: List<String>,
    val sfCustomModelInputVisible: Boolean,
    val builtinCustomModelInputVisible: Boolean,
    val builtinReasoningOnJson: String,
    val builtinReasoningOffJson: String,
    val customModelInputVisible: Boolean,
    val focusProfileNameAfterAdd: Boolean,
    val focusPromptTitleAfterAdd: Boolean,
    val llmTestRunning: Boolean
)

internal data class AiSettingsRouteActions(
    val onPostProcessEnabledChange: (Boolean) -> Unit,
    val onTypewriterEnabledChange: (Boolean) -> Unit,
    val onAiEditPreferLastAsrChange: (Boolean) -> Unit,
    val onSkipUnderCharsChange: (Int) -> Unit,
    val onAiEditCustomSystemPromptEnabledChange: (Boolean) -> Unit,
    val onAiEditSystemPromptChange: (String) -> Unit,
    val onSfApiKeyChange: (String) -> Unit,
    val onSfModelChange: (String) -> Unit,
    val onSfReasoningCharThresholdChange: (Int) -> Unit,
    val onSfReasoningOnJsonChange: (String) -> Unit,
    val onSfReasoningOffJsonChange: (String) -> Unit,
    val onSfCustomReasoningParamsEnabledChange: (Boolean) -> Unit,
    val onSfTemperatureChange: (Float) -> Unit,
    val onSfCustomModelInputVisibleChange: (Boolean) -> Unit,
    val onBuiltinCustomModelInputVisibleChange: (Boolean) -> Unit,
    val onBuiltinReasoningOnJsonChange: (String) -> Unit,
    val onBuiltinReasoningOffJsonChange: (String) -> Unit,
    val onFocusProfileNameAfterAddChange: (Boolean) -> Unit,
    val onFocusPromptTitleAfterAddChange: (Boolean) -> Unit,
    val onMessage: (Int) -> Unit,
    val onRefreshSfState: () -> Unit,
    val onSendRefreshBroadcast: () -> Unit,
    val onShowExplainedSwitch: AiExplainedSwitchHandler,
    val onShowVendorSelectionDialog: () -> Unit,
    val onShowSfModelDialog: () -> Unit,
    val onShowBuiltinModelDialog: () -> Unit,
    val onShowProfileDialog: () -> Unit,
    val onShowCustomModelDialog: () -> Unit,
    val onShowPromptPresetDialog: () -> Unit,
    val onShowBuiltinModelsPicker: (LlmVendor, List<String>) -> Unit,
    val onShowCustomModelsPicker: (List<String>) -> Unit,
    val onFetchModels: (String, String, (List<String>) -> Unit) -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onTestLlmCall: () -> Unit
)
