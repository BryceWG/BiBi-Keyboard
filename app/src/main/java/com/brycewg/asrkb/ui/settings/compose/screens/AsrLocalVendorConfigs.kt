/**
 * Compose 本地 ASR 供应商设置组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsUiState
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsViewModel
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButton
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButtonRow
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun CurrentLocalAsrVendorConfig(
    uiMode: BibiUiMode,
    selectedVendor: AsrVendor,
    uiState: AsrSettingsUiState,
    localModelState: AsrLocalModelRouteState,
    viewModel: AsrSettingsViewModel,
    onOpenGuide: () -> Unit,
    onOpenPunctuationGuide: () -> Unit,
    applySwitch: AsrExplainedSwitchApplier,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null
) {
    when (selectedVendor) {
        AsrVendor.SenseVoice -> SenseVoiceConfig(
            uiMode = uiMode,
            uiState = uiState,
            localModelState = localModelState,
            viewModel = viewModel,
            onOpenGuide = onOpenGuide,
            primaryIndexOffset = primaryIndexOffset,
            primaryGroupCount = primaryGroupCount
        )

        AsrVendor.FunAsrNano -> FunAsrNanoConfig(
            uiMode = uiMode,
            uiState = uiState,
            localModelState = localModelState,
            viewModel = viewModel,
            onOpenGuide = onOpenGuide,
            applySwitch = applySwitch,
            primaryIndexOffset = primaryIndexOffset,
            primaryGroupCount = primaryGroupCount
        )

        AsrVendor.Qwen3Asr -> Qwen3AsrConfig(
            uiMode = uiMode,
            uiState = uiState,
            localModelState = localModelState,
            viewModel = viewModel,
            onOpenGuide = onOpenGuide,
            applySwitch = applySwitch,
            primaryIndexOffset = primaryIndexOffset,
            primaryGroupCount = primaryGroupCount
        )

        AsrVendor.Parakeet -> ParakeetConfig(
            uiMode = uiMode,
            uiState = uiState,
            localModelState = localModelState,
            viewModel = viewModel,
            onOpenGuide = onOpenGuide,
            applySwitch = applySwitch,
            primaryIndexOffset = primaryIndexOffset,
            primaryGroupCount = primaryGroupCount
        )

        AsrVendor.GigaAm -> GigaAmConfig(
            uiMode = uiMode,
            uiState = uiState,
            localModelState = localModelState,
            viewModel = viewModel,
            onOpenGuide = onOpenGuide,
            applySwitch = applySwitch,
            primaryIndexOffset = primaryIndexOffset,
            primaryGroupCount = primaryGroupCount
        )

        AsrVendor.FireRedAsr -> FireRedAsrConfig(
            uiMode = uiMode,
            uiState = uiState,
            localModelState = localModelState,
            viewModel = viewModel,
            onOpenGuide = onOpenGuide,
            onOpenPunctuationGuide = onOpenPunctuationGuide,
            primaryIndexOffset = primaryIndexOffset,
            primaryGroupCount = primaryGroupCount
        )

        AsrVendor.XAsr -> XAsrConfig(
            uiMode = uiMode,
            uiState = uiState,
            localModelState = localModelState,
            viewModel = viewModel,
            onOpenGuide = onOpenGuide,
            primaryIndexOffset = primaryIndexOffset,
            primaryGroupCount = primaryGroupCount
        )

        else -> Unit
    }
}

@Composable
private fun SenseVoiceConfig(
    uiMode: BibiUiMode,
    uiState: AsrSettingsUiState,
    localModelState: AsrLocalModelRouteState,
    viewModel: AsrSettingsViewModel,
    onOpenGuide: () -> Unit,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null
) {
    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: localAsrPrimaryItemCount(AsrVendor.SenseVoice)
    LocalModelVariantPreference(
        uiMode = uiMode,
        spec = SenseVoiceModelSpec,
        uiState = uiState,
        localModelState = localModelState,
        index = itemIndex++,
        count = itemCount,
        onVariantChange = {
            viewModel.updateSvModelVariant(it)
            localModelState.onRefresh()
        }
    )
    AsrDropdownPreference(
        titleRes = R.string.label_sv_language,
        options = senseVoiceLanguageOptions(LocalContext.current),
        selectedOptionId = uiState.svLanguage,
        index = itemIndex++,
        count = itemCount,
        onSelectedOptionChange = {
            viewModel.updateSvLanguage(it)
        }
    )
    AsrSliderPreference(
        titleRes = R.string.label_sv_threads,
        valueLabel = { it.toInt().toString() },
        value = uiState.svNumThreads.toFloat(),
        valueRange = 1f..8f,
        steps = 6,
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        onValueChange = { viewModel.updateSvNumThreads(it.toInt()) }
    )
    AsrSwitchPreference(
        id = "sv_use_itn",
        titleRes = R.string.label_sv_use_itn,
        checked = uiState.svUseItn,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = {
            viewModel.updateSvUseItn(it)
        }
    )
    AsrSwitchPreference(
        id = "sv_preload",
        titleRes = R.string.label_sv_preload,
        checked = uiState.svPreloadEnabled,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = {
            viewModel.updateSvPreload(it)
        }
    )
    AsrSwitchPreference(
        id = "sv_pseudo_stream",
        titleRes = R.string.label_sv_pseudo_stream,
        checked = uiState.svPseudoStreamEnabled,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = {
            viewModel.updateSvPseudoStream(it)
        }
    )
    KeepAlivePreference(
        titleRes = R.string.label_sv_keep_alive,
        selectedMinutes = uiState.svKeepAliveMinutes,
        index = itemIndex,
        count = itemCount,
        onSelected = {
            viewModel.updateSvKeepAlive(it)
        }
    )
    LocalModelOperations(
        uiMode = uiMode,
        spec = SenseVoiceModelSpec,
        uiState = uiState,
        localModelState = localModelState,
        onOpenGuide = onOpenGuide
    )
}

@Composable
private fun FunAsrNanoConfig(
    uiMode: BibiUiMode,
    uiState: AsrSettingsUiState,
    localModelState: AsrLocalModelRouteState,
    viewModel: AsrSettingsViewModel,
    onOpenGuide: () -> Unit,
    applySwitch: AsrExplainedSwitchApplier,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null
) {
    val context = LocalContext.current
    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: localAsrPrimaryItemCount(AsrVendor.FunAsrNano)
    LocalModelVariantPreference(
        uiMode = uiMode,
        spec = FunAsrNanoModelSpec,
        uiState = uiState,
        localModelState = localModelState,
        index = itemIndex++,
        count = itemCount,
        onVariantChange = {
            viewModel.updateFnModelVariant(it)
            val allowed = funAsrLanguageOptions(context, it).map { option -> option.id }
            if (uiState.fnLanguage.isNotBlank() && uiState.fnLanguage !in allowed) {
                viewModel.updateFnLanguage("")
            }
            localModelState.onRefresh()
        }
    )
    AsrDropdownPreference(
        titleRes = R.string.label_fn_language_preset,
        options = funAsrLanguageOptions(context, uiState.fnModelVariant),
        selectedOptionId = uiState.fnLanguage,
        index = itemIndex++,
        count = itemCount,
        onSelectedOptionChange = {
            viewModel.updateFnLanguage(it)
        }
    )
    AsrSliderPreference(
        titleRes = R.string.label_fn_threads,
        valueLabel = { it.toInt().toString() },
        value = uiState.fnNumThreads.toFloat(),
        valueRange = 1f..8f,
        steps = 6,
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        onValueChange = { viewModel.updateFnNumThreads(it.toInt()) }
    )
    AsrSwitchPreference(
        id = "fn_use_itn",
        titleRes = R.string.label_fn_use_itn,
        checked = uiState.fnUseItn,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = { checked ->
            applySwitch(
                checked,
                R.string.label_fn_use_itn,
                R.string.feature_fn_use_itn_off_desc,
                R.string.feature_fn_use_itn_on_desc,
                uiState.fnUseItn,
                "fn_use_itn_explained",
                viewModel::updateFnUseItn
            )
        }
    )
    AsrSwitchPreference(
        id = "fn_preload",
        titleRes = R.string.label_fn_preload,
        checked = uiState.fnPreloadEnabled,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = { checked ->
            applySwitch(
                checked,
                R.string.label_fn_preload,
                R.string.feature_fn_preload_off_desc,
                R.string.feature_fn_preload_on_desc,
                uiState.fnPreloadEnabled,
                "fn_preload_explained",
                viewModel::updateFnPreload
            )
        }
    )
    KeepAlivePreference(
        titleRes = R.string.label_fn_keep_alive,
        selectedMinutes = uiState.fnKeepAliveMinutes,
        index = itemIndex++,
        count = itemCount,
        onSelected = {
            viewModel.updateFnKeepAlive(it)
        }
    )
    AsrTextField(
        uiMode = uiMode,
        value = uiState.fnUserPrompt,
        onValueChange = viewModel::updateFnUserPrompt,
        label = stringResource(R.string.label_fn_user_prompt),
        singleLine = false,
        minLines = 2,
        index = itemIndex,
        count = itemCount
    )
    AsrBodyText(uiMode = uiMode, textRes = R.string.fn_user_prompt_hint)
    LocalModelOperations(
        uiMode = uiMode,
        spec = FunAsrNanoModelSpec,
        uiState = uiState,
        localModelState = localModelState,
        onOpenGuide = onOpenGuide
    )
}

@Composable
private fun Qwen3AsrConfig(
    uiMode: BibiUiMode,
    uiState: AsrSettingsUiState,
    localModelState: AsrLocalModelRouteState,
    viewModel: AsrSettingsViewModel,
    onOpenGuide: () -> Unit,
    applySwitch: AsrExplainedSwitchApplier,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null
) {
    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: localAsrPrimaryItemCount(AsrVendor.Qwen3Asr)
    LocalModelVariantPreference(
        uiMode = uiMode,
        spec = Qwen3AsrModelSpec,
        uiState = uiState,
        localModelState = localModelState,
        index = itemIndex++,
        count = itemCount,
        onVariantChange = {
            viewModel.updateQwModelVariant(it)
            localModelState.onRefresh()
        }
    )
    AsrSliderPreference(
        titleRes = R.string.label_qw_threads,
        valueLabel = { it.toInt().toString() },
        value = uiState.qwNumThreads.toFloat(),
        valueRange = 1f..8f,
        steps = 6,
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        onValueChange = { viewModel.updateQwNumThreads(it.toInt()) }
    )
    AsrSwitchPreference(
        id = "qw_preload",
        titleRes = R.string.label_qw_preload,
        checked = uiState.qwPreloadEnabled,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = { checked ->
            applySwitch(
                checked,
                R.string.label_qw_preload,
                R.string.feature_qw_preload_off_desc,
                R.string.feature_qw_preload_on_desc,
                uiState.qwPreloadEnabled,
                "qw_preload_explained",
                viewModel::updateQwPreload
            )
        }
    )
    AsrSwitchPreference(
        id = "qw_use_itn",
        titleRes = R.string.label_qw_use_itn,
        checked = uiState.qwUseItn,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = {
            viewModel.updateQwUseItn(it)
        }
    )
    KeepAlivePreference(
        titleRes = R.string.label_qw_keep_alive,
        selectedMinutes = uiState.qwKeepAliveMinutes,
        index = itemIndex,
        count = itemCount,
        onSelected = {
            viewModel.updateQwKeepAlive(it)
        }
    )
    LocalModelOperations(
        uiMode = uiMode,
        spec = Qwen3AsrModelSpec,
        uiState = uiState,
        localModelState = localModelState,
        onOpenGuide = onOpenGuide
    )
}

@Composable
private fun ParakeetConfig(
    uiMode: BibiUiMode,
    uiState: AsrSettingsUiState,
    localModelState: AsrLocalModelRouteState,
    viewModel: AsrSettingsViewModel,
    onOpenGuide: () -> Unit,
    applySwitch: AsrExplainedSwitchApplier,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null
) {
    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: localAsrPrimaryItemCount(AsrVendor.Parakeet)
    LocalModelVariantPreference(
        uiMode = uiMode,
        spec = ParakeetModelSpec,
        uiState = uiState,
        localModelState = localModelState,
        index = itemIndex++,
        count = itemCount,
        onVariantChange = {
            viewModel.updatePkModelVariant(it)
            localModelState.onRefresh()
        }
    )
    AsrSliderPreference(
        titleRes = R.string.label_pk_threads,
        valueLabel = { it.toInt().toString() },
        value = uiState.pkNumThreads.toFloat(),
        valueRange = 1f..8f,
        steps = 6,
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        onValueChange = { viewModel.updatePkNumThreads(it.toInt()) }
    )
    AsrSwitchPreference(
        id = "pk_preload",
        titleRes = R.string.label_pk_preload,
        checked = uiState.pkPreloadEnabled,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = { checked ->
            applySwitch(
                checked,
                R.string.label_pk_preload,
                R.string.feature_pk_preload_off_desc,
                R.string.feature_pk_preload_on_desc,
                uiState.pkPreloadEnabled,
                "pk_preload_explained",
                viewModel::updatePkPreload
            )
        }
    )
    KeepAlivePreference(
        titleRes = R.string.label_pk_keep_alive,
        selectedMinutes = uiState.pkKeepAliveMinutes,
        index = itemIndex,
        count = itemCount,
        onSelected = {
            viewModel.updatePkKeepAlive(it)
        }
    )
    LocalModelOperations(
        uiMode = uiMode,
        spec = ParakeetModelSpec,
        uiState = uiState,
        localModelState = localModelState,
        onOpenGuide = onOpenGuide
    )
}

@Composable
private fun GigaAmConfig(
    uiMode: BibiUiMode,
    uiState: AsrSettingsUiState,
    localModelState: AsrLocalModelRouteState,
    viewModel: AsrSettingsViewModel,
    onOpenGuide: () -> Unit,
    applySwitch: AsrExplainedSwitchApplier,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null
) {
    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: localAsrPrimaryItemCount(AsrVendor.GigaAm)
    LocalModelVariantPreference(
        uiMode = uiMode,
        spec = GigaAmModelSpec,
        uiState = uiState,
        localModelState = localModelState,
        index = itemIndex++,
        count = itemCount,
        onVariantChange = {
            viewModel.updateGigaAmModelVariant(it)
            localModelState.onRefresh()
        }
    )
    AsrSliderPreference(
        titleRes = R.string.label_giga_am_threads,
        valueLabel = { it.toInt().toString() },
        value = uiState.gigaAmNumThreads.toFloat(),
        valueRange = 1f..8f,
        steps = 6,
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        onValueChange = { viewModel.updateGigaAmNumThreads(it.toInt()) }
    )
    AsrSwitchPreference(
        id = "giga_am_preload",
        titleRes = R.string.label_giga_am_preload,
        checked = uiState.gigaAmPreloadEnabled,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = { checked ->
            applySwitch(
                checked,
                R.string.label_giga_am_preload,
                R.string.feature_giga_am_preload_off_desc,
                R.string.feature_giga_am_preload_on_desc,
                uiState.gigaAmPreloadEnabled,
                "giga_am_preload_explained",
                viewModel::updateGigaAmPreload
            )
        }
    )
    KeepAlivePreference(
        titleRes = R.string.label_giga_am_keep_alive,
        selectedMinutes = uiState.gigaAmKeepAliveMinutes,
        index = itemIndex,
        count = itemCount,
        onSelected = viewModel::updateGigaAmKeepAlive
    )
    LocalModelOperations(
        uiMode = uiMode,
        spec = GigaAmModelSpec,
        uiState = uiState,
        localModelState = localModelState,
        onOpenGuide = onOpenGuide
    )
}

@Composable
private fun FireRedAsrConfig(
    uiMode: BibiUiMode,
    uiState: AsrSettingsUiState,
    localModelState: AsrLocalModelRouteState,
    viewModel: AsrSettingsViewModel,
    onOpenGuide: () -> Unit,
    onOpenPunctuationGuide: () -> Unit,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null
) {
    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: localAsrPrimaryItemCount(AsrVendor.FireRedAsr)
    LocalModelVariantPreference(
        uiMode = uiMode,
        spec = FireRedAsrModelSpec,
        uiState = uiState,
        localModelState = localModelState,
        index = itemIndex++,
        count = itemCount,
        onVariantChange = {
            viewModel.updateFrModelVariant(it)
            localModelState.onRefresh()
        }
    )
    AsrSliderPreference(
        titleRes = R.string.label_fr_threads,
        valueLabel = { it.toInt().toString() },
        value = uiState.frNumThreads.toFloat(),
        valueRange = 1f..8f,
        steps = 6,
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        onValueChange = { viewModel.updateFrNumThreads(it.toInt()) }
    )
    AsrSwitchPreference(
        id = "fr_preload",
        titleRes = R.string.label_fr_preload,
        checked = uiState.frPreloadEnabled,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = {
            viewModel.updateFrPreload(it)
        }
    )
    AsrSwitchPreference(
        id = "fr_use_itn",
        titleRes = R.string.label_fr_use_itn,
        checked = uiState.frUseItn,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = {
            viewModel.updateFrUseItn(it)
        }
    )
    AsrSwitchPreference(
        id = "fr_pseudo_stream",
        titleRes = R.string.label_fr_pseudo_stream,
        checked = uiState.frPseudoStreamEnabled,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = {
            viewModel.updateFrPseudoStream(it)
        }
    )
    KeepAlivePreference(
        titleRes = R.string.label_fr_keep_alive,
        selectedMinutes = uiState.frKeepAliveMinutes,
        index = itemIndex,
        count = itemCount,
        onSelected = {
            viewModel.updateFrKeepAlive(it)
        }
    )
    LocalModelOperations(
        uiMode = uiMode,
        spec = FireRedAsrModelSpec,
        uiState = uiState,
        localModelState = localModelState,
        onOpenGuide = onOpenGuide
    )
    PunctuationModelManager(uiMode, uiState, localModelState, onOpenPunctuationGuide)
}

@Composable
private fun XAsrConfig(
    uiMode: BibiUiMode,
    uiState: AsrSettingsUiState,
    localModelState: AsrLocalModelRouteState,
    viewModel: AsrSettingsViewModel,
    onOpenGuide: () -> Unit,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null
) {
    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: localAsrPrimaryItemCount(AsrVendor.XAsr)
    LocalModelVariantPreference(
        uiMode = uiMode,
        spec = XAsrModelSpec,
        uiState = uiState,
        localModelState = localModelState,
        index = itemIndex++,
        count = itemCount,
        onVariantChange = {
            viewModel.updateXAsrModelVariant(it)
            localModelState.onRefresh()
        }
    )
    AsrSliderPreference(
        titleRes = R.string.label_x_asr_threads,
        valueLabel = { it.toInt().toString() },
        value = uiState.xAsrNumThreads.toFloat(),
        valueRange = 1f..8f,
        steps = 6,
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        onValueChange = { viewModel.updateXAsrNumThreads(it.toInt()) }
    )
    AsrSwitchPreference(
        id = "x_asr_preload",
        titleRes = R.string.label_x_asr_preload,
        checked = uiState.xAsrPreloadEnabled,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = {
            viewModel.updateXAsrPreload(it)
        }
    )
    AsrSwitchPreference(
        id = "x_asr_use_itn",
        titleRes = R.string.label_x_asr_use_itn,
        checked = uiState.xAsrUseItn,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = {
            viewModel.updateXAsrUseItn(it)
        }
    )
    KeepAlivePreference(
        titleRes = R.string.label_x_asr_keep_alive,
        selectedMinutes = uiState.xAsrKeepAliveMinutes,
        index = itemIndex,
        count = itemCount,
        onSelected = {
            viewModel.updateXAsrKeepAlive(it)
        }
    )
    LocalModelOperations(
        uiMode = uiMode,
        spec = XAsrModelSpec,
        uiState = uiState,
        localModelState = localModelState,
        onOpenGuide = onOpenGuide
    )
}

@Composable
private fun LocalModelVariantPreference(
    uiMode: BibiUiMode,
    spec: AsrLocalModelSpec,
    uiState: AsrSettingsUiState,
    localModelState: AsrLocalModelRouteState,
    index: Int = 0,
    count: Int = 1,
    onVariantChange: (String) -> Unit
) {
    val context = LocalContext.current
    val selectedVariant = spec.currentVariant(uiState)
    AsrDropdownPreference(
        titleRes = spec.variantLabelRes,
        options = spec.variants.map { DropdownOption(it.value, context.getString(it.labelRes)) },
        selectedOptionId = selectedVariant,
        index = index,
        count = count,
        onSelectedOptionChange = onVariantChange
    )
}

@Composable
private fun LocalModelOperations(
    uiMode: BibiUiMode,
    spec: AsrLocalModelSpec,
    uiState: AsrSettingsUiState,
    localModelState: AsrLocalModelRouteState,
    onOpenGuide: () -> Unit
) {
    val context = LocalContext.current
    val selectedVariant = spec.currentVariant(uiState)
    val ready = localModelState.readyByKey[spec.key] == true
    val status = localModelStatus(context, spec, ready, localModelState.statusByKey[spec.key])
    if (status.isNotBlank()) {
        AsrBodyText(
            uiMode = uiMode,
            text = status,
            color = localModelStatusColor(uiMode, spec, localModelState)
        )
    }
    LocalModelActionRow(
        uiMode = uiMode,
        spec = spec,
        selectedVariant = selectedVariant,
        ready = ready,
        localModelState = localModelState
    )
    AsrActionPreference(
        id = "${spec.key}_guide",
        titleRes = R.string.btn_local_model_guide,
        onClick = onOpenGuide
    )
}

@Composable
private fun PunctuationModelManager(
    uiMode: BibiUiMode,
    uiState: AsrSettingsUiState,
    localModelState: AsrLocalModelRouteState,
    onOpenGuide: () -> Unit
) {
    val context = LocalContext.current
    val ready = localModelState.readyByKey[PunctuationModelSpec.key] == true
    val status = localModelStatus(
        context = context,
        spec = PunctuationModelSpec,
        ready = ready,
        explicitStatus = localModelState.statusByKey[PunctuationModelSpec.key]
    )
    AsrLocalDivider(uiMode)
    AsrBodyText(uiMode = uiMode, textRes = R.string.label_punct_model_shared)
    if (status.isNotBlank()) {
        AsrBodyText(
            uiMode = uiMode,
            text = status,
            color = localModelStatusColor(uiMode, PunctuationModelSpec, localModelState)
        )
    }
    LocalModelActionRow(
        uiMode = uiMode,
        spec = PunctuationModelSpec,
        selectedVariant = PunctuationModelSpec.currentVariant(uiState),
        ready = ready,
        localModelState = localModelState
    )
    AsrActionPreference(
        id = "punctuation_guide",
        titleRes = R.string.btn_local_model_guide,
        onClick = onOpenGuide
    )
}

@Composable
private fun AsrLocalDivider(uiMode: BibiUiMode) {
    val color = when (uiMode) {
        BibiUiMode.Material -> MaterialTheme.colorScheme.outlineVariant
        BibiUiMode.Miuix -> MiuixTheme.colorScheme.dividerLine
    }
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .height(1.dp)
            .background(color)
    )
}

@Composable
private fun LocalModelActionRow(
    uiMode: BibiUiMode,
    spec: AsrLocalModelSpec,
    selectedVariant: String,
    ready: Boolean,
    localModelState: AsrLocalModelRouteState
) {
    SettingsActionButtonRow(uiMode = uiMode) {
        if (ready) {
            SettingsActionButton(
                uiMode = uiMode,
                text = stringResource(spec.clearButtonRes),
                onClick = { localModelState.onClear(spec) },
                modifier = Modifier.weight(1f)
            )
        } else {
            SettingsActionButton(
                uiMode = uiMode,
                text = stringResource(spec.downloadButtonRes),
                onClick = { localModelState.onDownload(spec, selectedVariant) },
                modifier = Modifier.weight(1f)
            )
            SettingsActionButton(
                uiMode = uiMode,
                text = stringResource(spec.importButtonRes),
                onClick = { localModelState.onImport(spec, selectedVariant) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun KeepAlivePreference(
    titleRes: Int,
    selectedMinutes: Int,
    index: Int = 0,
    count: Int = 1,
    onSelected: (Int) -> Unit
) {
    val context = LocalContext.current
    AsrDropdownPreference(
        titleRes = titleRes,
        options = keepAliveOptions(context),
        selectedOptionId = selectedMinutes.toString(),
        index = index,
        count = count,
        onSelectedOptionChange = { value -> onSelected(value.toIntOrNull() ?: -1) }
    )
}

private fun localModelStatus(
    context: Context,
    spec: AsrLocalModelSpec,
    ready: Boolean,
    explicitStatus: String?
): String = if (ready) context.getString(spec.doneStatusRes) else explicitStatus.orEmpty()

@Composable
private fun localModelStatusColor(
    uiMode: BibiUiMode,
    spec: AsrLocalModelSpec,
    localModelState: AsrLocalModelRouteState
) = if (spec.key in localModelState.errorStatusKeys) {
    when (uiMode) {
        BibiUiMode.Material -> MaterialTheme.colorScheme.error
        BibiUiMode.Miuix -> MiuixTheme.colorScheme.error
    }
} else {
    null
}

internal fun localAsrPrimaryItemCount(selectedVendor: AsrVendor): Int = when (selectedVendor) {
    AsrVendor.SenseVoice -> 7
    AsrVendor.FunAsrNano -> 7
    AsrVendor.Qwen3Asr -> 5
    AsrVendor.Parakeet -> 4
    AsrVendor.GigaAm -> 4
    AsrVendor.FireRedAsr -> 6
    AsrVendor.XAsr -> 5
    else -> 0
}

private fun senseVoiceLanguageOptions(context: Context): List<DropdownOption> = listOf(
    DropdownOption("auto", context.getString(R.string.sv_lang_auto)),
    DropdownOption("zh", context.getString(R.string.sv_lang_zh)),
    DropdownOption("en", context.getString(R.string.sv_lang_en)),
    DropdownOption("ja", context.getString(R.string.sv_lang_ja)),
    DropdownOption("ko", context.getString(R.string.sv_lang_ko)),
    DropdownOption("yue", context.getString(R.string.sv_lang_yue))
)

private fun funAsrLanguageOptions(context: Context, variant: String): List<DropdownOption> {
    val common = listOf(
        DropdownOption("", context.getString(R.string.fn_lang_auto)),
        DropdownOption("中文", context.getString(R.string.fn_lang_zh)),
        DropdownOption("英文", context.getString(R.string.fn_lang_en)),
        DropdownOption("日文", context.getString(R.string.fn_lang_ja))
    )
    if (!variant.contains("mlt", ignoreCase = true)) return common
    return common + listOf(
        DropdownOption("韩文", context.getString(R.string.fn_lang_ko)),
        DropdownOption("越南语", context.getString(R.string.fn_lang_vi)),
        DropdownOption("印尼语", context.getString(R.string.fn_lang_id)),
        DropdownOption("泰语", context.getString(R.string.fn_lang_th)),
        DropdownOption("马来语", context.getString(R.string.fn_lang_ms)),
        DropdownOption("菲律宾语", context.getString(R.string.fn_lang_fil)),
        DropdownOption("阿拉伯语", context.getString(R.string.fn_lang_ar)),
        DropdownOption("印地语", context.getString(R.string.fn_lang_hi)),
        DropdownOption("保加利亚语", context.getString(R.string.fn_lang_bg)),
        DropdownOption("克罗地亚语", context.getString(R.string.fn_lang_hr)),
        DropdownOption("捷克语", context.getString(R.string.fn_lang_cs)),
        DropdownOption("丹麦语", context.getString(R.string.fn_lang_da)),
        DropdownOption("荷兰语", context.getString(R.string.fn_lang_nl)),
        DropdownOption("爱沙尼亚语", context.getString(R.string.fn_lang_et)),
        DropdownOption("芬兰语", context.getString(R.string.fn_lang_fi)),
        DropdownOption("希腊语", context.getString(R.string.fn_lang_el)),
        DropdownOption("匈牙利语", context.getString(R.string.fn_lang_hu)),
        DropdownOption("爱尔兰语", context.getString(R.string.fn_lang_ga)),
        DropdownOption("拉脱维亚语", context.getString(R.string.fn_lang_lv)),
        DropdownOption("立陶宛语", context.getString(R.string.fn_lang_lt)),
        DropdownOption("马耳他语", context.getString(R.string.fn_lang_mt)),
        DropdownOption("波兰语", context.getString(R.string.fn_lang_pl)),
        DropdownOption("葡萄牙语", context.getString(R.string.fn_lang_pt)),
        DropdownOption("罗马尼亚语", context.getString(R.string.fn_lang_ro)),
        DropdownOption("斯洛伐克语", context.getString(R.string.fn_lang_sk)),
        DropdownOption("斯洛文尼亚语", context.getString(R.string.fn_lang_sl)),
        DropdownOption("瑞典语", context.getString(R.string.fn_lang_sv))
    )
}

private fun keepAliveOptions(context: Context): List<DropdownOption> = listOf(
    DropdownOption("0", context.getString(R.string.sv_keep_alive_immediate)),
    DropdownOption("5", context.getString(R.string.sv_keep_alive_5m)),
    DropdownOption("15", context.getString(R.string.sv_keep_alive_15m)),
    DropdownOption("30", context.getString(R.string.sv_keep_alive_30m)),
    DropdownOption("-1", context.getString(R.string.sv_keep_alive_always))
)
