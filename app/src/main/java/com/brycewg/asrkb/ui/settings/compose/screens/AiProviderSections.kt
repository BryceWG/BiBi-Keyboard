/**
 * Compose AI 设置页的供应商与提示词表单组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmReasoningThreshold
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.store.JevClassifierProvider
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.ai.AiPostSettingsViewModel
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption

@Composable
internal fun TypeSafeLlmSection(
    uiMode: BibiUiMode,
    prefs: Prefs,
    onChooseModel: () -> Unit,
    primaryIndexOffset: Int,
    primaryGroupCount: Int
) {
    var channel by remember { mutableStateOf(prefs.jevClassifierProvider) }
    var apiKey by remember(channel) { mutableStateOf(jevApiKey(prefs, channel)) }
    var accountId by remember { mutableStateOf(prefs.jevCloudflareAccountId) }
    var index = primaryIndexOffset

    AsrDropdownPreference(
        id = "typesafe_channel",
        titleRes = R.string.label_prompt_selection_jev_channel,
        options = JevClassifierProvider.entries.map { DropdownOption(it.id, it.displayName()) },
        selectedOptionId = channel.id,
        index = index++,
        count = primaryGroupCount,
        onSelectedOptionChange = { id ->
            JevClassifierProvider.fromId(id)?.let {
                channel = it
                prefs.jevClassifierProvider = it
            }
        }
    )
    AiTextField(
        uiMode = uiMode,
        value = apiKey,
        onValueChange = {
            apiKey = it
            setJevApiKey(prefs, channel, it)
        },
        label = stringResource(R.string.label_llm_api_key),
        password = true,
        index = index++,
        count = primaryGroupCount
    )
    if (channel == JevClassifierProvider.CLOUDFLARE) {
        AiTextField(
            uiMode = uiMode,
            value = accountId,
            onValueChange = {
                accountId = it
                prefs.jevCloudflareAccountId = it
            },
            label = stringResource(R.string.label_prompt_selection_jev_account_id),
            index = index++,
            count = primaryGroupCount
        )
    }
    AiValuePreference(
        titleRes = R.string.label_llm_model_select,
        value = LlmVendor.TYPESAFE.defaultModel,
        uiMode = uiMode,
        index = index,
        count = primaryGroupCount,
        onClick = onChooseModel
    )
    AiBodyText(uiMode = uiMode, textRes = R.string.helper_prompt_selection_jev_only)
}

private fun jevApiKey(prefs: Prefs, provider: JevClassifierProvider): String = when (provider) {
    JevClassifierProvider.TYPESAFE -> prefs.jevTypesafeApiKey
    JevClassifierProvider.OPENROUTER -> prefs.jevOpenRouterApiKey
    JevClassifierProvider.CLOUDFLARE -> prefs.jevCloudflareApiKey
}

private fun setJevApiKey(prefs: Prefs, provider: JevClassifierProvider, value: String) {
    when (provider) {
        JevClassifierProvider.TYPESAFE -> prefs.jevTypesafeApiKey = value
        JevClassifierProvider.OPENROUTER -> prefs.jevOpenRouterApiKey = value
        JevClassifierProvider.CLOUDFLARE -> prefs.jevCloudflareApiKey = value
    }
}

@Composable
internal fun SfFreeLlmSection(
    uiMode: BibiUiMode,
    presetModels: List<String>,
    staticModels: List<String>,
    sfUseFreeService: Boolean,
    sfApiKey: String,
    sfModel: String,
    customModelInputVisible: Boolean,
    testEnabled: Boolean,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null,
    actions: AiVendorActions,
    onShowAdvancedParams: () -> Unit
) {
    val showCustomModel =
        customModelInputVisible || (sfModel.isNotBlank() && !presetModels.contains(sfModel))
    val showCustomReasoningParams = sfModel.isNotBlank() && !staticModels.contains(sfModel)
    val showReasoning =
        LlmVendor.SF_FREE.supportsReasoningControl(sfModel) || showCustomReasoningParams

    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: sfFreeLlmPrimaryItemCount(
        presetModels = presetModels,
        staticModels = staticModels,
        sfUseFreeService = sfUseFreeService,
        sfModel = sfModel,
        customModelInputVisible = customModelInputVisible
    )
    AiSwitchPreference(
        id = "sf_free_service",
        titleRes = R.string.label_sf_use_free_service,
        checked = sfUseFreeService,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = actions.onToggleSfFree
    )
    if (!sfUseFreeService) {
        AiTextField(
            uiMode = uiMode,
            value = sfApiKey,
            onValueChange = actions.onSfApiKeyChange,
            label = stringResource(R.string.label_llm_api_key),
            password = true,
            index = itemIndex++,
            count = itemCount
        )
    }
    AiValuePreference(
        titleRes = R.string.label_sf_free_llm_model,
        value = sfModel.ifBlank { LlmVendor.SF_FREE.defaultModel },
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        trailingActionIcon = if (!sfUseFreeService) Icons.Rounded.CloudDownload else null,
        trailingActionContentDescriptionRes = if (!sfUseFreeService) R.string.btn_llm_fetch_models else null,
        onTrailingActionClick = if (!sfUseFreeService) actions.onFetchModels else null,
        onClick = actions.onShowModelDialog
    )
    if (showCustomModel) {
        AiTextField(
            uiMode = uiMode,
            value = sfModel,
            onValueChange = actions.onCustomModelChange,
            label = stringResource(R.string.label_custom_model_id),
            index = itemIndex++,
            count = itemCount
        )
    }
    if (!sfUseFreeService) {
        AiActionPreference(
            id = "more_params",
            titleRes = R.string.label_llm_more_params,
            index = itemIndex,
            count = itemCount,
            onClick = onShowAdvancedParams
        )
    } else if (showReasoning) {
        AiActionPreference(
            id = "more_params",
            titleRes = R.string.label_llm_more_params,
            index = itemIndex,
            count = itemCount,
            onClick = onShowAdvancedParams
        )
    }
    AiBodyText(uiMode = uiMode, textRes = R.string.sf_free_register_hint)
    AiButtonRow(uiMode = uiMode) {
        AiButton(uiMode = uiMode, textRes = R.string.btn_get_api_key, onClick = actions.onOpenRegister)
        AiButton(
            uiMode = uiMode,
            textRes = R.string.btn_llm_test_call,
            enabled = testEnabled,
            onClick = actions.onTestCall
        )
    }
    if (sfUseFreeService) {
        AiBodyText(uiMode = uiMode, textRes = R.string.sf_free_service_desc)
        SiliconFlowPoweredByImage()
    }
}

@Composable
internal fun BuiltinLlmSection(
    uiMode: BibiUiMode,
    vendor: LlmVendor,
    config: AiPostSettingsViewModel.BuiltinVendorConfig,
    presetModels: List<String>,
    customModelInputVisible: Boolean,
    testEnabled: Boolean,
    onApiKeyChange: (String) -> Unit,
    onChooseModel: () -> Unit,
    onCustomModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onOpenRegister: () -> Unit,
    onTestCall: () -> Unit,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null,
    onShowAdvancedParams: () -> Unit
) {
    val displayModel = config.model.ifBlank { vendor.defaultModel }
    val isPresetModel = displayModel.isNotBlank() && presetModels.contains(displayModel)
    val showCustomModel =
        customModelInputVisible || (displayModel.isNotBlank() && !isPresetModel)

    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: builtinLlmPrimaryItemCount(
        vendor = vendor,
        config = config,
        presetModels = presetModels,
        customModelInputVisible = customModelInputVisible
    )
    AiTextField(
        uiMode = uiMode,
        value = config.apiKey,
        onValueChange = onApiKeyChange,
        label = stringResource(R.string.label_llm_api_key),
        password = true,
        index = itemIndex++,
        count = itemCount
    )
    AiValuePreference(
        titleRes = R.string.label_llm_model_select,
        value = displayModel,
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        trailingActionIcon = Icons.Rounded.CloudDownload,
        trailingActionContentDescriptionRes = R.string.btn_llm_fetch_models,
        onTrailingActionClick = onFetchModels,
        onClick = onChooseModel
    )
    if (showCustomModel) {
        AiTextField(
            uiMode = uiMode,
            value = displayModel,
            onValueChange = onCustomModelChange,
            label = stringResource(R.string.label_custom_model_id),
            index = itemIndex++,
            count = itemCount
        )
    }
    AiActionPreference(
        id = "more_params",
        titleRes = R.string.label_llm_more_params,
        index = itemIndex,
        count = itemCount,
        onClick = onShowAdvancedParams
    )
    AiButtonRow(uiMode = uiMode) {
        if (vendor.registerUrl.isNotBlank()) {
            AiButton(uiMode = uiMode, textRes = R.string.btn_llm_register, onClick = onOpenRegister)
        }
        AiButton(
            uiMode = uiMode,
            textRes = R.string.btn_llm_test_call,
            enabled = testEnabled,
            onClick = onTestCall
        )
    }
}

@Composable
internal fun CustomLlmSection(
    uiMode: BibiUiMode,
    provider: Prefs.LlmProvider?,
    customModelInputVisible: Boolean,
    focusProfileNameAfterAdd: Boolean,
    testEnabled: Boolean,
    onFocusedProfileName: () -> Unit,
    onChooseProfile: () -> Unit,
    onProfileNameChange: (String) -> Unit,
    onEndpointChange: (String) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onChooseModel: () -> Unit,
    onModelChange: (String) -> Unit,
    onFetchModels: () -> Unit,
    onAddProfile: () -> Unit,
    onDeleteProfile: () -> Unit,
    onTestCall: () -> Unit,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null,
    onShowAdvancedParams: () -> Unit
) {
    val profileName = provider?.name.orEmpty()
    val displayName = profileName.ifBlank { stringResource(R.string.untitled_profile) }
    val presetModels = provider?.models.orEmpty().map { it.trim() }.filter { it.isNotBlank() }
    val model = provider?.model.orEmpty()
    val hasPresetModels = presetModels.isNotEmpty()
    val showFetchButton = !(customModelInputVisible && hasPresetModels)
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(focusProfileNameAfterAdd) {
        if (focusProfileNameAfterAdd) {
            focusRequester.requestFocus()
            onFocusedProfileName()
        }
    }

    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: customLlmPrimaryItemCount(customModelInputVisible)
    AiValuePreference(
        titleRes = R.string.label_llm_choose_profile,
        value = displayName,
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        onClick = onChooseProfile
    )
    AiTextField(
        uiMode = uiMode,
        value = profileName,
        onValueChange = onProfileNameChange,
        label = stringResource(R.string.label_llm_profile_name),
        modifier = Modifier.focusRequester(focusRequester),
        index = itemIndex++,
        count = itemCount
    )
    AiTextField(
        uiMode = uiMode,
        value = provider?.endpoint.orEmpty(),
        onValueChange = onEndpointChange,
        label = stringResource(R.string.label_llm_endpoint),
        index = itemIndex++,
        count = itemCount
    )
    AiTextField(
        uiMode = uiMode,
        value = provider?.apiKey.orEmpty(),
        onValueChange = onApiKeyChange,
        label = stringResource(R.string.label_llm_api_key),
        password = true,
        index = itemIndex++,
        count = itemCount
    )
    AiValuePreference(
        titleRes = R.string.label_llm_model_select,
        value = model.ifBlank { stringResource(R.string.option_custom_model) },
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        trailingActionIcon = if (showFetchButton) Icons.Rounded.CloudDownload else null,
        trailingActionContentDescriptionRes = if (showFetchButton) R.string.btn_llm_fetch_models else null,
        onTrailingActionClick = if (showFetchButton) onFetchModels else null,
        onClick = onChooseModel
    )
    if (customModelInputVisible) {
        AiTextField(
            uiMode = uiMode,
            value = model,
            onValueChange = onModelChange,
            label = stringResource(R.string.label_custom_model_id),
            index = itemIndex++,
            count = itemCount
        )
    }
    AiActionPreference(
        id = "more_params",
        titleRes = R.string.label_llm_more_params,
        index = itemIndex,
        count = itemCount,
        onClick = onShowAdvancedParams
    )
    AiButtonRow(uiMode = uiMode) {
        AiButton(uiMode = uiMode, textRes = R.string.btn_llm_add_profile, onClick = onAddProfile)
        AiButton(uiMode = uiMode, textRes = R.string.btn_llm_delete_profile, onClick = onDeleteProfile)
        AiButton(
            uiMode = uiMode,
            textRes = R.string.btn_llm_test_call,
            enabled = testEnabled,
            onClick = onTestCall
        )
    }
}

@Composable
internal fun ReasoningSection(
    uiMode: BibiUiMode,
    threshold: Int,
    showParams: Boolean,
    customParamsEnabled: Boolean,
    onThresholdChange: (Int) -> Unit,
    onCustomParamsEnabledChange: (Boolean) -> Unit,
    onJson: String,
    offJson: String,
    onOnJsonChange: (String) -> Unit,
    onOffJsonChange: (String) -> Unit,
    index: Int,
    count: Int
) {
    val alwaysLabel = stringResource(R.string.value_ai_reasoning_always)
    val neverLabel = stringResource(R.string.value_ai_reasoning_never)
    val coerced = LlmReasoningThreshold.coerce(threshold)

    AiSliderPreference(
        titleRes = R.string.title_ai_reasoning_threshold,
        valueLabel = { pos ->
            when (val value = LlmReasoningThreshold.fromSlider(pos)) {
                LlmReasoningThreshold.ALWAYS -> alwaysLabel
                LlmReasoningThreshold.NEVER -> neverLabel
                else -> value.toString()
            }
        },
        value = LlmReasoningThreshold.toSlider(coerced),
        valueRange = 0f..1f,
        steps = 0,
        showKeyPoints = false,
        uiMode = uiMode,
        index = index,
        count = count,
        onValueChange = { pos -> onThresholdChange(LlmReasoningThreshold.fromSlider(pos)) }
    )
    AiBodyText(uiMode = uiMode, textRes = R.string.helper_ai_reasoning_threshold)
    if (showParams) {
        var itemIndex = 0
        val itemCount = 1 + (if (customParamsEnabled) 2 else 0)
        AiSwitchPreference(
            id = "custom_reasoning_params",
            titleRes = R.string.label_custom_reasoning_params,
            checked = customParamsEnabled,
            index = itemIndex++,
            count = itemCount,
            onCheckedChange = onCustomParamsEnabledChange
        )
        if (customParamsEnabled) {
            AiTextField(
                uiMode = uiMode,
                value = onJson,
                onValueChange = onOnJsonChange,
                label = stringResource(R.string.label_reasoning_params_on_json),
                singleLine = false,
                index = itemIndex++,
                count = itemCount
            )
            AiTextField(
                uiMode = uiMode,
                value = offJson,
                onValueChange = onOffJsonChange,
                label = stringResource(R.string.label_reasoning_params_off_json),
                singleLine = false,
                index = itemIndex,
                count = itemCount
            )
            AiBodyText(uiMode = uiMode, textRes = R.string.hint_reasoning_params_json)
        }
    }
}

internal data class AiVendorActions(
    val onToggleSfFree: (Boolean) -> Unit,
    val onSfApiKeyChange: (String) -> Unit,
    val onShowModelDialog: () -> Unit,
    val onCustomModelChange: (String) -> Unit,
    val onFetchModels: () -> Unit,
    val onReasoningChange: (Int) -> Unit,
    val onCustomReasoningParamsEnabledChange: (Boolean) -> Unit,
    val onReasoningOnJsonChange: (String) -> Unit,
    val onReasoningOffJsonChange: (String) -> Unit,
    val onTemperatureChange: (Float) -> Unit,
    val onOpenRegister: () -> Unit,
    val onTestCall: () -> Unit,
    val onTestHaptic: () -> Unit = {}
)

internal fun sfFreeLlmPrimaryItemCount(
    presetModels: List<String>,
    staticModels: List<String>,
    sfUseFreeService: Boolean,
    sfModel: String,
    customModelInputVisible: Boolean
): Int {
    val showCustomModel =
        customModelInputVisible || (sfModel.isNotBlank() && !presetModels.contains(sfModel))
    val showCustomReasoningParams = sfModel.isNotBlank() && !staticModels.contains(sfModel)
    val showReasoning =
        LlmVendor.SF_FREE.supportsReasoningControl(sfModel) || showCustomReasoningParams
    val showAdvancedParams = llmAdvancedParamsVisible(
        hasTemperature = !sfUseFreeService,
        hasReasoning = showReasoning
    )
    return 1 +
        (if (!sfUseFreeService) 1 else 0) +
        1 +
        (if (showCustomModel) 1 else 0) +
        (if (showAdvancedParams) 1 else 0)
}

internal fun builtinLlmPrimaryItemCount(
    vendor: LlmVendor,
    config: AiPostSettingsViewModel.BuiltinVendorConfig,
    presetModels: List<String>,
    customModelInputVisible: Boolean
): Int {
    val displayModel = config.model.ifBlank { vendor.defaultModel }
    val isPresetModel = displayModel.isNotBlank() && presetModels.contains(displayModel)
    val showCustomModel =
        customModelInputVisible || (displayModel.isNotBlank() && !isPresetModel)
    return 3 + (if (showCustomModel) 1 else 0)
}

internal fun customLlmPrimaryItemCount(customModelInputVisible: Boolean): Int = 6 + (if (customModelInputVisible) 1 else 0)

internal fun llmAdvancedParamsVisible(hasTemperature: Boolean, hasReasoning: Boolean): Boolean = hasTemperature || hasReasoning
