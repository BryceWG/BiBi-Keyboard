/**
 * LLM 功能共用的供应商与已保存模型两级选择器。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import androidx.annotation.StringRes
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmVendor
import com.brycewg.asrkb.store.LlmModelConfigResolver
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.LlmFeatureModelRef
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceGroup
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceItem
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheetNavigator
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.settingsChoiceSheetState

internal fun llmFeatureModelPicker(
    context: Context,
    prefs: Prefs,
    current: LlmFeatureModelRef,
    @StringRes titleRes: Int,
    navigator: SettingsChoiceSheetNavigator,
    excludedVendors: Set<LlmVendor> = emptySet(),
    onNoModels: () -> Unit,
    onSelected: (LlmFeatureModelRef) -> Unit
): SettingsChoiceSheetState? {
    val vendorOptions = LlmModelConfigResolver.builtinVendorOptions(prefs)
        .filter { it.vendor != LlmVendor.CUSTOM && it.vendor !in excludedVendors }
    val customOptions = LlmModelConfigResolver.customProviderOptions(prefs)
    val refs = buildList {
        add(LlmFeatureModelRef.FollowDefault)
        vendorOptions.forEach { option ->
            add(
                LlmFeatureModelRef.Builtin(
                    vendorId = option.vendor.id,
                    model = prefs.effectiveLlmConfigForVendor(option.vendor, null)?.model.orEmpty()
                )
            )
        }
        customOptions.forEach { option ->
            val model = prefs.getLlmProviders()
                .firstOrNull { it.id == option.providerId }
                ?.model
                .orEmpty()
            add(LlmFeatureModelRef.Custom(providerId = option.providerId, model = model))
        }
    }
    val unconfiguredLabel = context.getString(R.string.prompt_selection_model_unconfigured)
    val untitledProfile = context.getString(R.string.untitled_profile)

    data class Option(
        val ref: LlmFeatureModelRef,
        val item: SettingsChoiceItem,
        val configured: Boolean
    )

    val options = refs.mapIndexed { index, ref ->
        val configured = when (ref) {
            LlmFeatureModelRef.FollowDefault ->
                LlmModelConfigResolver.summarize(prefs, ref).available
            is LlmFeatureModelRef.Builtin ->
                vendorOptions.firstOrNull { it.vendor.id == ref.vendorId }?.configured == true
            is LlmFeatureModelRef.Custom ->
                customOptions.firstOrNull { it.providerId == ref.providerId }?.configured == true
        }
        val name = when (ref) {
            LlmFeatureModelRef.FollowDefault ->
                context.getString(R.string.prompt_selection_model_follow_default)
            is LlmFeatureModelRef.Builtin ->
                context.getString(LlmVendor.fromId(ref.vendorId).displayNameResId)
            is LlmFeatureModelRef.Custom ->
                customOptions
                    .firstOrNull { it.providerId == ref.providerId }
                    ?.name
                    .orEmpty()
                    .ifBlank { untitledProfile }
        }
        Option(
            ref = ref,
            item = SettingsChoiceItem(
                title = if (configured) name else "$name（$unconfiguredLabel）",
                originalIndex = index
            ),
            configured = configured
        )
    }

    val followDefault = options.firstOrNull { it.ref is LlmFeatureModelRef.FollowDefault }
    val providers = options.filterNot { it.ref is LlmFeatureModelRef.FollowDefault }
    val groups = buildList {
        followDefault?.let { add(SettingsChoiceGroup(label = "", items = listOf(it.item))) }
        providers.filter { it.configured }.map { it.item }.takeIf { it.isNotEmpty() }?.let {
            add(SettingsChoiceGroup(context.getString(R.string.llm_vendor_group_configured), it))
        }
        providers.filterNot { it.configured }.map { it.item }.takeIf { it.isNotEmpty() }?.let {
            add(SettingsChoiceGroup(context.getString(R.string.llm_vendor_group_unconfigured), it))
        }
    }
    if (groups.isEmpty()) return null
    return SettingsChoiceSheetState(
        title = context.getString(titleRes),
        groups = groups,
        selectedIndex = refs.indexOfFirst { sameLlmTarget(it, current) },
        onSelected = { index ->
            val ref = refs.getOrNull(index) ?: return@SettingsChoiceSheetState
            if (ref is LlmFeatureModelRef.FollowDefault) {
                navigator.finishAfterDismiss()
                onSelected(ref)
                return@SettingsChoiceSheetState
            }
            val models = LlmModelConfigResolver.savedModels(prefs, ref)
            if (models.isEmpty()) {
                navigator.finishAfterDismiss()
                onNoModels()
                return@SettingsChoiceSheetState
            }
            if (models.size == 1) {
                navigator.finishAfterDismiss()
                onSelected(withLlmModel(ref, models.single()))
                return@SettingsChoiceSheetState
            }
            navigator.showAfterDismiss(
                settingsChoiceSheetState(
                    title = context.getString(R.string.prompt_selection_model_choose_model_title),
                    items = models,
                    selectedIndex = models.indexOf(modelOf(ref)),
                    onSelected = { modelIndex ->
                        val model = models.getOrNull(modelIndex) ?: return@settingsChoiceSheetState
                        navigator.finishAfterDismiss()
                        onSelected(withLlmModel(ref, model))
                    }
                )
            )
        }
    )
}

internal fun llmFeatureModelSummary(
    context: Context,
    prefs: Prefs,
    ref: LlmFeatureModelRef
): String {
    val summary = LlmModelConfigResolver.summarize(prefs, ref)
    val targetName = when {
        summary.vendorNameResId != null -> context.getString(summary.vendorNameResId)
        summary.customProviderName != null ->
            summary.customProviderName.ifBlank { context.getString(R.string.untitled_profile) }
        else -> context.getString(R.string.prompt_selection_model_follow_default)
    }
    val model = summary.model.ifBlank {
        context.getString(R.string.prompt_selection_model_unconfigured)
    }
    val base = "$targetName · $model"
    return if (summary.available) {
        base
    } else {
        "$base · ${context.getString(R.string.prompt_selection_model_unavailable)}"
    }
}

private fun modelOf(ref: LlmFeatureModelRef): String = when (ref) {
    LlmFeatureModelRef.FollowDefault -> ""
    is LlmFeatureModelRef.Builtin -> ref.model
    is LlmFeatureModelRef.Custom -> ref.model
}

private fun sameLlmTarget(a: LlmFeatureModelRef, b: LlmFeatureModelRef): Boolean = when {
    a is LlmFeatureModelRef.FollowDefault && b is LlmFeatureModelRef.FollowDefault -> true
    a is LlmFeatureModelRef.Builtin && b is LlmFeatureModelRef.Builtin -> a.vendorId == b.vendorId
    a is LlmFeatureModelRef.Custom && b is LlmFeatureModelRef.Custom -> a.providerId == b.providerId
    else -> false
}

private fun withLlmModel(
    ref: LlmFeatureModelRef,
    model: String
): LlmFeatureModelRef = when (ref) {
    LlmFeatureModelRef.FollowDefault -> ref
    is LlmFeatureModelRef.Builtin -> ref.copy(model = model)
    is LlmFeatureModelRef.Custom -> ref.copy(model = model)
}
