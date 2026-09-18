/**
 * Compose DashScope ASR 设置组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import android.icu.text.ListFormatter
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.DashScopePrefsCompat
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption

@Composable
internal fun DashScopeConfig(
    uiMode: BibiUiMode,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    modelLabel: String,
    onChooseModel: () -> Unit,
    prompt: String,
    onPromptChange: (String) -> Unit,
    promptVisible: Boolean,
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    languageVisible: Boolean,
    languageMultiSelect: Boolean,
    onChooseLanguages: () -> Unit,
    selectedRegion: String,
    onRegionSelected: (String) -> Unit,
    semanticPunct: Boolean,
    semanticPunctVisible: Boolean,
    onSemanticPunctChange: (Boolean) -> Unit,
    onOpenGuide: () -> Unit,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null
) {
    val context = LocalContext.current
    val itemCount = primaryGroupCount ?: dashScopePrimaryItemCount(
        languageVisible = languageVisible,
        semanticPunctVisible = semanticPunctVisible,
        promptVisible = promptVisible
    )
    var itemIndex = primaryIndexOffset
    AsrTextField(
        uiMode = uiMode,
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = stringResource(R.string.label_dash_api_key),
        password = true,
        index = itemIndex++,
        count = itemCount
    )
    AsrValuePreference(
        titleRes = R.string.label_dash_model,
        value = modelLabel,
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        onClick = onChooseModel
    )
    if (languageVisible) {
        if (languageMultiSelect) {
            AsrValuePreference(
                titleRes = R.string.label_dash_language,
                value = dashLanguageSummary(context, selectedLanguage),
                uiMode = uiMode,
                index = itemIndex++,
                count = itemCount,
                onClick = onChooseLanguages
            )
        } else {
            AsrDropdownPreference(
                titleRes = R.string.label_dash_language,
                options = dashLanguageOptions(context).map { option ->
                    DropdownOption(option.value, option.label)
                },
                selectedOptionId = DashScopePrefsCompat.parseDashLanguages(selectedLanguage)
                    .firstOrNull().orEmpty(),
                index = itemIndex++,
                count = itemCount,
                onSelectedOptionChange = onLanguageSelected
            )
        }
    }
    AsrDropdownPreference(
        titleRes = R.string.label_dash_region,
        options = dashRegionOptions(context).map { option ->
            DropdownOption(option.value, option.label)
        },
        selectedOptionId = normalizeDashRegion(selectedRegion),
        index = itemIndex++,
        count = itemCount,
        onSelectedOptionChange = onRegionSelected
    )
    if (semanticPunctVisible) {
        AsrSwitchPreference(
            id = "dash_semantic_punct",
            titleRes = R.string.label_dash_semantic_punct,
            checked = semanticPunct,
            index = itemIndex++,
            count = itemCount,
            onCheckedChange = onSemanticPunctChange
        )
    }
    if (promptVisible) {
        AsrTextField(
            uiMode = uiMode,
            value = prompt,
            onValueChange = onPromptChange,
            label = stringResource(R.string.label_dash_prompt),
            singleLine = false,
            minLines = 2,
            index = itemIndex++,
            count = itemCount
        )
    }
    AsrActionPreference(
        id = "dash_get_key_guide",
        titleRes = R.string.btn_get_api_key_guide,
        index = itemIndex,
        count = itemCount,
        onClick = onOpenGuide
    )
}

internal fun dashScopePrimaryItemCount(
    languageVisible: Boolean,
    semanticPunctVisible: Boolean,
    promptVisible: Boolean
): Int = 4 +
    (if (languageVisible) 1 else 0) +
    (if (semanticPunctVisible) 1 else 0) +
    (if (promptVisible) 1 else 0)

internal fun dashModelOptions(context: Context): List<DashChoice> = listOf(
    DashChoice(
        Prefs.DASH_MODEL_QWEN_AUDIO_REALTIME,
        context.getString(R.string.dash_model_qwen_audio_realtime)
    ),
    DashChoice(
        Prefs.DASH_MODEL_QWEN_AUDIO_FLASH,
        context.getString(R.string.dash_model_qwen_audio_flash)
    ),
    DashChoice(Prefs.DASH_MODEL_FUN_ASR_REALTIME, context.getString(R.string.dash_model_fun_realtime)),
    DashChoice(Prefs.DASH_MODEL_FUN_ASR_FLASH, context.getString(R.string.dash_model_fun_flash)),
    DashChoice(
        Prefs.DASH_MODEL_QWEN3_REALTIME,
        context.getString(R.string.dash_model_qwen3_realtime)
    ),
    DashChoice(
        Prefs.DASH_MODEL_QWEN3_FLASH,
        context.getString(R.string.dash_model_qwen3_flash)
    ),
    DashChoice(
        Prefs.DASH_MODEL_QWEN38_OMNI_FLASH,
        context.getString(R.string.dash_model_qwen38_omni_flash)
    ),
    DashChoice(
        Prefs.DASH_MODEL_QWEN35_OMNI_FLASH,
        context.getString(R.string.dash_model_qwen35_omni_flash)
    ),
    DashChoice(
        Prefs.DASH_MODEL_QWEN35_OMNI_PLUS,
        context.getString(R.string.dash_model_qwen35_omni_plus)
    )
)

internal fun dashModelLabel(context: Context, model: String): String {
    val normalized = normalizeDashModel(model)
    return dashModelOptions(context).firstOrNull { it.value == normalized }?.label
        ?: context.getString(R.string.dash_model_qwen_audio_flash)
}

internal fun dashLanguageOptions(context: Context): List<DashChoice> = listOf(
    DashChoice("", context.getString(R.string.dash_lang_auto)),
    DashChoice("zh", context.getString(R.string.dash_lang_zh)),
    DashChoice("en", context.getString(R.string.dash_lang_en)),
    DashChoice("ja", context.getString(R.string.dash_lang_ja)),
    DashChoice("de", context.getString(R.string.dash_lang_de)),
    DashChoice("ko", context.getString(R.string.dash_lang_ko)),
    DashChoice("ru", context.getString(R.string.dash_lang_ru)),
    DashChoice("fr", context.getString(R.string.dash_lang_fr)),
    DashChoice("pt", context.getString(R.string.dash_lang_pt)),
    DashChoice("ar", context.getString(R.string.dash_lang_ar)),
    DashChoice("it", context.getString(R.string.dash_lang_it)),
    DashChoice("es", context.getString(R.string.dash_lang_es))
)

internal fun dashLanguageLabel(context: Context, language: String): String {
    val normalized = language.trim()
    return dashLanguageOptions(context).firstOrNull { it.value == normalized }?.label
        ?: context.getString(R.string.dash_lang_auto)
}

internal fun dashLanguageSummary(context: Context, languages: String): String {
    val selected = DashScopePrefsCompat.parseDashLanguages(languages)
    if (selected.isEmpty()) return context.getString(R.string.dash_lang_auto)
    val labels = dashLanguageOptions(context).associate { it.value to it.label }
    val selectedLabels = selected.mapNotNull(labels::get)
    if (selectedLabels.isEmpty()) return context.getString(R.string.dash_lang_auto)
    val locale = context.resources.configuration.locales[0]
    return ListFormatter.getInstance(locale).format(selectedLabels)
}

internal fun dashRegionOptions(context: Context): List<DashChoice> = listOf(
    DashChoice("cn", context.getString(R.string.dash_region_cn)),
    DashChoice("intl", context.getString(R.string.dash_region_intl))
)

internal fun dashRegionLabel(context: Context, region: String): String {
    val normalized = normalizeDashRegion(region)
    return dashRegionOptions(context).firstOrNull { it.value == normalized }?.label
        ?: context.getString(R.string.dash_region_cn)
}

internal fun normalizeDashModel(model: String): String = DashScopePrefsCompat.normalizeDashAsrModel(model)

internal fun normalizeDashRegion(region: String): String = if (region.equals("intl", ignoreCase = true)) {
    "intl"
} else {
    "cn"
}

internal fun isDashSemanticPunctuationSupported(model: String): Boolean = DashScopePrefsCompat.isSemanticPunctuationSupported(model)

internal fun isDashPromptSupported(model: String): Boolean = DashScopePrefsCompat.isPromptSupported(model)

internal fun isDashLanguageSupported(model: String): Boolean = DashScopePrefsCompat.isLanguageSupported(model)

internal const val DASH_SCOPE_ASR_GUIDE_URL: String =
    "https://bibidocs.brycewg.com/getting-started/asr-providers.html#%E9%98%BF%E9%87%8C%E4%BA%91%E7%99%BE%E7%82%BC-dashscope-qwen"

internal data class DashChoice(
    val value: String,
    val label: String
)
