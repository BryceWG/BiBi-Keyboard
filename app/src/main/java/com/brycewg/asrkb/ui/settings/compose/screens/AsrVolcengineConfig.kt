/**
 * Compose 火山引擎 ASR 设置组件。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.VolcAsrModelCatalog
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption

@Composable
internal fun VolcengineConfig(
    uiMode: BibiUiMode,
    appKey: String,
    onAppKeyChange: (String) -> Unit,
    accessKey: String,
    onAccessKeyChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    useNewAuth: Boolean,
    onUseNewAuthChange: (Boolean) -> Unit,
    selectedModelId: String,
    modelLabel: String,
    onChooseModel: () -> Unit,
    nonstream: Boolean,
    onNonstreamChange: (Boolean) -> Unit,
    ddc: Boolean,
    onDdcChange: (Boolean) -> Unit,
    vad: Boolean,
    onVadChange: (Boolean) -> Unit,
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    primaryIndexOffset: Int = 0,
    primaryGroupCount: Int? = null
) {
    val streaming = VolcAsrModelCatalog.isStreaming(selectedModelId)
    var itemIndex = primaryIndexOffset
    val itemCount = primaryGroupCount ?: volcenginePrimaryItemCount(
        streaming = streaming,
        useNewAuth = useNewAuth
    )
    AsrSwitchPreference(
        id = "volc_use_new_auth",
        titleRes = R.string.label_volc_use_new_auth,
        checked = useNewAuth,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = onUseNewAuthChange
    )
    AsrTextField(
        uiMode = uiMode,
        value = if (useNewAuth) apiKey else appKey,
        onValueChange = if (useNewAuth) onApiKeyChange else onAppKeyChange,
        label = stringResource(if (useNewAuth) R.string.label_volc_api_key else R.string.label_app_key),
        password = useNewAuth,
        index = itemIndex++,
        count = itemCount
    )
    if (!useNewAuth) {
        AsrTextField(
            uiMode = uiMode,
            value = accessKey,
            onValueChange = onAccessKeyChange,
            label = stringResource(R.string.label_access_key),
            password = true,
            index = itemIndex++,
            count = itemCount
        )
    }
    AsrValuePreference(
        titleRes = R.string.label_volc_asr_model,
        value = modelLabel,
        uiMode = uiMode,
        highlightId = "volc_asr_model",
        index = itemIndex++,
        count = itemCount,
        onClick = onChooseModel
    )
    AsrSwitchPreference(
        id = "volc_ddc",
        titleRes = R.string.label_volc_ddc,
        checked = ddc,
        index = itemIndex++,
        count = itemCount,
        onCheckedChange = onDdcChange
    )
    if (streaming) {
        AsrSwitchPreference(
            id = "volc_nonstream",
            titleRes = R.string.label_volc_nonstream,
            checked = nonstream,
            index = itemIndex++,
            count = itemCount,
            onCheckedChange = onNonstreamChange
        )
        AsrSwitchPreference(
            id = "volc_vad",
            titleRes = R.string.label_volc_vad,
            checked = vad,
            index = itemIndex++,
            count = itemCount,
            onCheckedChange = onVadChange
        )
        AsrDropdownPreference(
            titleRes = R.string.label_volc_language,
            options = volcLanguageOptions(LocalContext.current).map { option ->
                DropdownOption(option.value, option.label)
            },
            selectedOptionId = selectedLanguage,
            index = itemIndex,
            count = itemCount,
            onSelectedOptionChange = onLanguageSelected
        )
    }
}

internal fun volcenginePrimaryItemCount(
    streaming: Boolean,
    useNewAuth: Boolean
): Int = 1 +
    (if (useNewAuth) 1 else 2) +
    1 +
    1 +
    (if (streaming) 3 else 0)

internal fun volcModelLabel(context: Context, modelId: String): String = context.getString(VolcAsrModelCatalog.fromIdOrDefault(modelId).displayNameRes)

internal fun volcLanguageOptions(context: Context): List<VolcChoice> = listOf(
    VolcChoice("", context.getString(R.string.volc_lang_auto)),
    VolcChoice("yue-CN", context.getString(R.string.volc_lang_yue_cn)),
    VolcChoice("en-US", context.getString(R.string.volc_lang_en_us)),
    VolcChoice("ja-JP", context.getString(R.string.volc_lang_ja_jp)),
    VolcChoice("id-ID", context.getString(R.string.volc_lang_id_id)),
    VolcChoice("es-MX", context.getString(R.string.volc_lang_es_mx)),
    VolcChoice("pt-BR", context.getString(R.string.volc_lang_pt_br)),
    VolcChoice("de-DE", context.getString(R.string.volc_lang_de_de)),
    VolcChoice("fr-FR", context.getString(R.string.volc_lang_fr_fr)),
    VolcChoice("ko-KR", context.getString(R.string.volc_lang_ko_kr)),
    VolcChoice("fil-PH", context.getString(R.string.volc_lang_fil_ph)),
    VolcChoice("ms-MY", context.getString(R.string.volc_lang_ms_my)),
    VolcChoice("th-TH", context.getString(R.string.volc_lang_th_th)),
    VolcChoice("ar-SA", context.getString(R.string.volc_lang_ar_sa))
)

internal fun volcLanguageLabel(context: Context, language: String): String {
    val normalized = language.trim()
    return volcLanguageOptions(context).firstOrNull { it.value == normalized }?.label
        ?: context.getString(R.string.volc_lang_auto)
}

internal data class VolcChoice(
    val value: String,
    val label: String
)
