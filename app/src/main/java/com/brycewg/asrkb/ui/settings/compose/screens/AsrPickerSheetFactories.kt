/**
 * Compose ASR 设置页选择弹层的状态构造辅助函数。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.BackupAsrLocalResidency
import com.brycewg.asrkb.asr.TencentAsrModelCatalog
import com.brycewg.asrkb.asr.VolcAsrModelCatalog
import com.brycewg.asrkb.asr.partitionAsrVendorsByConfigured
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceGroup
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceItem
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceTag
import com.brycewg.asrkb.ui.settings.compose.components.settingsChoiceSheetState

internal fun asrSimpleChoiceSheetState(
    context: Context,
    titleResId: Int,
    items: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
): SettingsChoiceSheetState? = settingsChoiceSheetState(
    title = context.getString(titleResId),
    items = items,
    selectedIndex = selectedIndex,
    onSelected = onSelected
)

internal fun asrStringChoiceSheetState(
    context: Context,
    titleResId: Int,
    items: List<String>,
    selectedItem: String,
    fallbackItem: String,
    onSelected: (String) -> Unit
): SettingsChoiceSheetState? = asrSimpleChoiceSheetState(
    context = context,
    titleResId = titleResId,
    items = items,
    selectedIndex = items.indexOf(selectedItem).coerceAtLeast(0)
) { selectedIdx ->
    onSelected(items.getOrElse(selectedIdx) { fallbackItem })
}

internal fun <T> asrValueChoiceSheetState(
    context: Context,
    titleResId: Int,
    options: List<T>,
    selectedValue: String,
    fallbackValue: String,
    valueOf: (T) -> String,
    labelOf: (T) -> String,
    onSelected: (String) -> Unit
): SettingsChoiceSheetState? = asrSimpleChoiceSheetState(
    context = context,
    titleResId = titleResId,
    items = options.map(labelOf),
    selectedIndex = options.indexOfFirst { valueOf(it) == selectedValue }.coerceAtLeast(0)
) { selectedIdx ->
    onSelected(options.getOrNull(selectedIdx)?.let(valueOf) ?: fallbackValue)
}

internal fun backupSensitivityChoiceSheetState(
    context: Context,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
): SettingsChoiceSheetState? = asrSimpleChoiceSheetState(
    context = context,
    titleResId = R.string.label_backup_asr_timeout_sensitivity,
    items = listOf(
        context.getString(R.string.option_backup_asr_timeout_sensitivity_relaxed),
        context.getString(R.string.option_backup_asr_timeout_sensitivity_balanced),
        context.getString(R.string.option_backup_asr_timeout_sensitivity_sensitive)
    ),
    selectedIndex = selectedIndex,
    onSelected = onSelected
)

internal fun backupLocalResidencyChoiceSheetState(
    context: Context,
    selected: BackupAsrLocalResidency,
    onSelected: (BackupAsrLocalResidency) -> Unit
): SettingsChoiceSheetState? {
    val options = listOf(
        BackupAsrLocalResidency.OnDemand,
        BackupAsrLocalResidency.Resident
    )
    return asrSimpleChoiceSheetState(
        context = context,
        titleResId = R.string.label_backup_asr_local_residency,
        items = listOf(
            context.getString(R.string.option_backup_asr_local_residency_on_demand),
            context.getString(R.string.option_backup_asr_local_residency_resident)
        ),
        selectedIndex = options.indexOf(selected).coerceAtLeast(0)
    ) { selectedIdx ->
        onSelected(options.getOrElse(selectedIdx) { BackupAsrLocalResidency.OnDemand })
    }
}

internal fun sfFreeAsrModelChoiceSheetState(
    context: Context,
    selectedModel: String,
    onSelected: (String) -> Unit
): SettingsChoiceSheetState? = asrStringChoiceSheetState(
    context = context,
    titleResId = R.string.label_sf_model_select,
    items = Prefs.SF_FREE_ASR_MODELS,
    selectedItem = selectedModel,
    fallbackItem = Prefs.DEFAULT_SF_FREE_ASR_MODEL,
    onSelected = onSelected
)

internal fun sfPaidAsrModelChoiceSheetState(
    context: Context,
    selectedModel: String,
    onSelected: (String) -> Unit
): SettingsChoiceSheetState? = asrStringChoiceSheetState(
    context = context,
    titleResId = R.string.label_sf_model_select,
    items = sfPaidAsrModels(),
    selectedItem = selectedModel,
    fallbackItem = Prefs.DEFAULT_SF_MODEL,
    onSelected = onSelected
)

internal fun volcLanguageChoiceSheetState(
    context: Context,
    selectedLanguage: String,
    onSelected: (String) -> Unit
): SettingsChoiceSheetState? = asrValueChoiceSheetState(
    context = context,
    titleResId = R.string.label_volc_language,
    options = volcLanguageOptions(context),
    selectedValue = selectedLanguage,
    fallbackValue = "",
    valueOf = { it.value },
    labelOf = { it.label },
    onSelected = onSelected
)

internal fun volcModelChoiceSheetState(
    context: Context,
    selectedModel: String,
    onSelected: (String) -> Unit
): SettingsChoiceSheetState? {
    val options = VolcAsrModelCatalog.all()
    val selected = VolcAsrModelCatalog.fromIdOrDefault(selectedModel)
    return asrValueChoiceSheetState(
        context = context,
        titleResId = R.string.label_volc_asr_model,
        options = options,
        selectedValue = selected.id,
        fallbackValue = VolcAsrModelCatalog.DEFAULT_ID,
        valueOf = { it.id },
        labelOf = { context.getString(it.displayNameRes) },
        onSelected = onSelected
    )
}

internal fun tencentEngineCategoryChoiceSheetState(
    context: Context,
    selectedCategory: String,
    onSelected: (String) -> Unit
): SettingsChoiceSheetState? {
    val selected = TencentAsrModelCatalog.categoryById(selectedCategory)
        ?: TencentAsrModelCatalog.DEFAULT_CATEGORY
    return asrValueChoiceSheetState(
        context = context,
        titleResId = R.string.label_tencent_engine_category,
        options = TencentAsrModelCatalog.categories(),
        selectedValue = selected.id,
        fallbackValue = TencentAsrModelCatalog.DEFAULT_CATEGORY.id,
        valueOf = { it.id },
        labelOf = { context.getString(it.displayNameRes) },
        onSelected = onSelected
    )
}

internal fun tencentModelChoiceSheetState(
    context: Context,
    categoryId: String,
    selectedModel: String,
    onSelected: (String) -> Unit
): SettingsChoiceSheetState? {
    val selection = TencentAsrModelCatalog.resolveSelection(categoryId, selectedModel)
    return asrValueChoiceSheetState(
        context = context,
        titleResId = R.string.label_tencent_engine_language,
        options = TencentAsrModelCatalog.models(selection.category),
        selectedValue = selection.model.id,
        fallbackValue = TencentAsrModelCatalog.defaultModel(selection.category).id,
        valueOf = { it.id },
        labelOf = { model -> context.getString(model.displayNameRes) },
        onSelected = onSelected
    )
}

internal fun dashModelChoiceSheetState(
    context: Context,
    selectedModel: String,
    onSelected: (String) -> Unit
): SettingsChoiceSheetState? = asrValueChoiceSheetState(
    context = context,
    titleResId = R.string.label_dash_model,
    options = dashModelOptions(context),
    selectedValue = normalizeDashModel(selectedModel),
    fallbackValue = Prefs.DEFAULT_DASH_MODEL,
    valueOf = { it.value },
    labelOf = { it.label },
    onSelected = onSelected
)

internal fun dashLanguageChoiceSheetState(
    context: Context,
    selectedLanguage: String,
    onSelected: (String) -> Unit
): SettingsChoiceSheetState? = asrValueChoiceSheetState(
    context = context,
    titleResId = R.string.label_dash_language,
    options = dashLanguageOptions(context),
    selectedValue = selectedLanguage,
    fallbackValue = "",
    valueOf = { it.value },
    labelOf = { it.label },
    onSelected = onSelected
)

internal fun dashRegionChoiceSheetState(
    context: Context,
    selectedRegion: String,
    onSelected: (String) -> Unit
): SettingsChoiceSheetState? = asrValueChoiceSheetState(
    context = context,
    titleResId = R.string.label_dash_region,
    options = dashRegionOptions(context),
    selectedValue = normalizeDashRegion(selectedRegion),
    fallbackValue = "cn",
    valueOf = { it.value },
    labelOf = { it.label },
    onSelected = onSelected
)

internal fun elevenLanguageChoiceSheetState(
    context: Context,
    selectedLanguage: String,
    onSelected: (String) -> Unit
): SettingsChoiceSheetState? = asrValueChoiceSheetState(
    context = context,
    titleResId = R.string.label_eleven_language,
    options = elevenLanguageOptions(context),
    selectedValue = selectedLanguage,
    fallbackValue = "",
    valueOf = { it.value },
    labelOf = { it.label },
    onSelected = onSelected
)

internal fun stepAudioModelChoiceSheetState(
    context: Context,
    selectedModel: String,
    onSelected: (String) -> Unit
): SettingsChoiceSheetState? = asrStringChoiceSheetState(
    context = context,
    titleResId = R.string.label_stepaudio_model,
    items = Prefs.STEPAUDIO_ASR_MODELS,
    selectedItem = selectedModel,
    fallbackItem = Prefs.DEFAULT_STEPAUDIO_ASR_MODEL,
    onSelected = onSelected
)

internal fun stepAudioLanguageChoiceSheetState(
    context: Context,
    selectedLanguage: String,
    onSelected: (String) -> Unit
): SettingsChoiceSheetState? = asrValueChoiceSheetState(
    context = context,
    titleResId = R.string.label_stepaudio_language,
    options = stepAudioLanguageOptions(context),
    selectedValue = selectedLanguage,
    fallbackValue = "zh",
    valueOf = { it.value },
    labelOf = { it.label },
    onSelected = onSelected
)

internal fun asrVendorChoiceSheetState(
    context: Context,
    prefs: Prefs,
    titleResId: Int,
    selectedVendor: AsrVendor,
    onSelected: (AsrVendor) -> Unit
): SettingsChoiceSheetState {
    val vendorOrder = AsrVendorUi.ordered()
    val vendorItems = vendorOrder.mapIndexed { index, vendor ->
        SettingsChoiceItem(
            title = AsrVendorUi.name(context, vendor),
            originalIndex = index,
            tags = AsrVendorUi.tags(vendor).map { tag ->
                SettingsChoiceTag(
                    label = context.getString(tag.labelResId),
                    bgColorResId = tag.bgColorResId,
                    textColorResId = tag.textColorResId
                )
            }
        )
    }
    val indexByVendor = vendorOrder.withIndex().associate { it.value to it.index }
    val partition = partitionAsrVendorsByConfigured(
        context = context,
        prefs = prefs,
        vendors = vendorOrder
    )
    return SettingsChoiceSheetState(
        title = context.getString(titleResId),
        groups = listOf(
            SettingsChoiceGroup(
                label = context.getString(R.string.asr_vendor_group_configured),
                items = partition.configured.mapNotNull { vendor ->
                    indexByVendor[vendor]?.let { idx -> vendorItems[idx] }
                }
            ),
            SettingsChoiceGroup(
                label = context.getString(R.string.asr_vendor_group_unconfigured),
                items = partition.unconfigured.mapNotNull { vendor ->
                    indexByVendor[vendor]?.let { idx -> vendorItems[idx] }
                }
            )
        ),
        selectedIndex = vendorOrder.indexOf(selectedVendor).coerceAtLeast(0),
        onSelected = { selectedIdx ->
            onSelected(vendorOrder.getOrNull(selectedIdx) ?: AsrVendor.Volc)
        }
    )
}
