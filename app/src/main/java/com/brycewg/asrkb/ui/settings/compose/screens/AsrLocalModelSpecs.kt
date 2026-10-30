/**
 * Compose 本地 ASR 模型配置规格与文件管理辅助。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import androidx.annotation.StringRes
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrLocalModelCatalog
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.LocalModelCheck
import com.brycewg.asrkb.asr.SherpaPunctuationManager
import com.brycewg.asrkb.asr.normalizeFunAsrNanoVariant
import com.brycewg.asrkb.asr.normalizeGigaAmVariant
import com.brycewg.asrkb.asr.normalizeParakeetVariant
import com.brycewg.asrkb.asr.normalizeQwen3AsrVariant
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsUiState
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsViewModel
import java.io.File

internal data class AsrLocalChoice(
    val value: String,
    @param:StringRes val labelRes: Int
)

internal data class AsrLocalModelSpec(
    val key: String,
    val vendor: AsrVendor?,
    val modelType: String,
    val importModelType: String?,
    @param:StringRes val variantLabelRes: Int,
    @param:StringRes val downloadButtonRes: Int,
    @param:StringRes val importButtonRes: Int,
    @param:StringRes val clearButtonRes: Int,
    @param:StringRes val doneStatusRes: Int,
    @param:StringRes val failedStatusRes: Int,
    @param:StringRes val downloadStartedRes: Int,
    @param:StringRes val importStartedRes: Int,
    @param:StringRes val clearTitleRes: Int,
    @param:StringRes val clearMessageRes: Int,
    @param:StringRes val clearDoneRes: Int,
    @param:StringRes val clearFailedRes: Int,
    val variants: List<AsrLocalChoice>,
    val currentVariant: (AsrSettingsUiState) -> String,
    val downloadUrl: (String) -> String,
    val isReady: (AsrSettingsViewModel, Context) -> Boolean,
    val checkStatus: (AsrSettingsViewModel, Context) -> LocalModelCheck<*>,
    val clearInstalled: suspend (Context, Prefs) -> Boolean
)

internal val SenseVoiceModelSpec = AsrLocalModelSpec(
    key = "sensevoice",
    vendor = AsrVendor.SenseVoice,
    modelType = "sensevoice",
    importModelType = null,
    variantLabelRes = R.string.label_sv_model_variant,
    downloadButtonRes = R.string.btn_sv_download_model,
    importButtonRes = R.string.btn_sv_import_model,
    clearButtonRes = R.string.btn_sv_clear_model,
    doneStatusRes = R.string.sv_download_status_done,
    failedStatusRes = R.string.sv_download_status_failed,
    downloadStartedRes = R.string.sv_download_started_in_bg,
    importStartedRes = R.string.sv_import_started_in_bg,
    clearTitleRes = R.string.sv_clear_confirm_title,
    clearMessageRes = R.string.sv_clear_confirm_message,
    clearDoneRes = R.string.sv_clear_done,
    clearFailedRes = R.string.sv_clear_failed,
    variants = listOf(
        AsrLocalChoice("small-int8", R.string.sv_model_small_int8),
        AsrLocalChoice("small-full", R.string.sv_model_small_full)
    ),
    currentVariant = { it.svModelVariant },
    downloadUrl = { variant ->
        if (variant == "small-full") {
            "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.zip"
        } else {
            "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.zip"
        }
    },
    isReady = linkedAsrModelReady(AsrVendor.SenseVoice),
    checkStatus = linkedAsrModelStatus(AsrVendor.SenseVoice),
    clearInstalled = { context, prefs ->
        clearInstalledAsrLocalModel(AsrVendor.SenseVoice, deleteInstalled = {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val root = File(base, "sensevoice")
            val target = if (prefs.svModelVariant == "small-full") {
                File(root, "small-full")
            } else {
                File(root, "small-int8")
            }
            target.deleteIfExists()
        })
    }
)

internal val FunAsrNanoModelSpec = AsrLocalModelSpec(
    key = "funasr_nano",
    vendor = AsrVendor.FunAsrNano,
    modelType = "funasr_nano",
    importModelType = null,
    variantLabelRes = R.string.label_fn_model_variant,
    downloadButtonRes = R.string.btn_fn_download_model,
    importButtonRes = R.string.btn_fn_import_model,
    clearButtonRes = R.string.btn_fn_clear_model,
    doneStatusRes = R.string.fn_download_status_done,
    failedStatusRes = R.string.fn_download_status_failed,
    downloadStartedRes = R.string.fn_download_started_in_bg,
    importStartedRes = R.string.fn_import_started_in_bg,
    clearTitleRes = R.string.fn_clear_confirm_title,
    clearMessageRes = R.string.fn_clear_confirm_message,
    clearDoneRes = R.string.fn_clear_done,
    clearFailedRes = R.string.fn_clear_failed,
    variants = listOf(
        AsrLocalChoice("nano-int8", R.string.fn_model_nano_int8),
        AsrLocalChoice("mlt-int8", R.string.fn_model_mlt_nano_int8)
    ),
    currentVariant = { normalizeFunAsrNanoVariant(it.fnModelVariant) },
    downloadUrl = { variant ->
        if (normalizeFunAsrNanoVariant(variant) == "mlt-int8") {
            "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-funasr-mlt-nano-int8-2026-03-21.zip"
        } else {
            "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-funasr-nano-int8-2025-12-30.zip"
        }
    },
    isReady = linkedAsrModelReady(AsrVendor.FunAsrNano),
    checkStatus = linkedAsrModelStatus(AsrVendor.FunAsrNano),
    clearInstalled = { context, _ ->
        clearInstalledAsrLocalModel(AsrVendor.FunAsrNano, deleteInstalled = {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            val legacySenseVoice = File(base, "sensevoice")
            listOf(
                File(base, "funasr_nano"),
                File(legacySenseVoice, "nano-int8"),
                File(legacySenseVoice, "nano-full")
            ).forEach { it.deleteIfExists() }
        })
    }
)

internal val Qwen3AsrModelSpec = AsrLocalModelSpec(
    key = "qwen3_asr",
    vendor = AsrVendor.Qwen3Asr,
    modelType = "qwen3_asr",
    importModelType = "qwen3_asr",
    variantLabelRes = R.string.label_qw_model_variant,
    downloadButtonRes = R.string.btn_qw_download_model,
    importButtonRes = R.string.btn_qw_import_model,
    clearButtonRes = R.string.btn_qw_clear_model,
    doneStatusRes = R.string.qw_download_status_done,
    failedStatusRes = R.string.qw_download_status_failed,
    downloadStartedRes = R.string.qw_download_started_in_bg,
    importStartedRes = R.string.qw_import_started_in_bg,
    clearTitleRes = R.string.qw_clear_confirm_title,
    clearMessageRes = R.string.qw_clear_confirm_message,
    clearDoneRes = R.string.qw_clear_done,
    clearFailedRes = R.string.qw_clear_failed,
    variants = listOf(
        AsrLocalChoice("qwen3-0.6b-int8", R.string.qw_model_qwen3_06b_int8),
        AsrLocalChoice("qwen3-1.7b-int8", R.string.qw_model_qwen3_17b_int8)
    ),
    currentVariant = { normalizeQwen3AsrVariant(it.qwModelVariant) },
    downloadUrl = { variant ->
        if (normalizeQwen3AsrVariant(variant) == "qwen3-1.7b-int8") {
            "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-qwen3-asr-1.7B-int8-2026-08-04.zip"
        } else {
            "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.zip"
        }
    },
    isReady = linkedAsrModelReady(AsrVendor.Qwen3Asr),
    checkStatus = linkedAsrModelStatus(AsrVendor.Qwen3Asr),
    clearInstalled = { context, prefs ->
        clearInstalledAsrLocalModel(AsrVendor.Qwen3Asr, deleteInstalled = {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            File(File(base, "qwen3_asr"), normalizeQwen3AsrVariant(prefs.qwModelVariant)).deleteIfExists()
        })
    }
)

internal val ParakeetModelSpec = AsrLocalModelSpec(
    key = "parakeet",
    vendor = AsrVendor.Parakeet,
    modelType = "parakeet",
    importModelType = null,
    variantLabelRes = R.string.label_pk_model_variant,
    downloadButtonRes = R.string.btn_pk_download_model,
    importButtonRes = R.string.btn_pk_import_model,
    clearButtonRes = R.string.btn_pk_clear_model,
    doneStatusRes = R.string.pk_download_status_done,
    failedStatusRes = R.string.pk_download_status_failed,
    downloadStartedRes = R.string.pk_download_started_in_bg,
    importStartedRes = R.string.pk_import_started_in_bg,
    clearTitleRes = R.string.pk_clear_confirm_title,
    clearMessageRes = R.string.pk_clear_confirm_message,
    clearDoneRes = R.string.pk_clear_done,
    clearFailedRes = R.string.pk_clear_failed,
    variants = listOf(
        AsrLocalChoice("0.6b-v3-int8", R.string.pk_model_06b_v3_int8),
        AsrLocalChoice("0.6b-v2-int8", R.string.pk_model_06b_v2_int8)
    ),
    currentVariant = { normalizeParakeetVariant(it.pkModelVariant) },
    downloadUrl = { variant ->
        if (normalizeParakeetVariant(variant) == "0.6b-v2-int8") {
            "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.zip"
        } else {
            "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.zip"
        }
    },
    isReady = linkedAsrModelReady(AsrVendor.Parakeet),
    checkStatus = linkedAsrModelStatus(AsrVendor.Parakeet),
    clearInstalled = { context, prefs ->
        clearInstalledAsrLocalModel(AsrVendor.Parakeet, deleteInstalled = {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            File(File(base, "parakeet"), normalizeParakeetVariant(prefs.pkModelVariant)).deleteIfExists()
        })
    }
)

internal val GigaAmModelSpec = AsrLocalModelSpec(
    key = "giga_am",
    vendor = AsrVendor.GigaAm,
    modelType = "giga_am",
    importModelType = "giga_am",
    variantLabelRes = R.string.label_giga_am_model_variant,
    downloadButtonRes = R.string.btn_giga_am_download_model,
    importButtonRes = R.string.btn_giga_am_import_model,
    clearButtonRes = R.string.btn_giga_am_clear_model,
    doneStatusRes = R.string.giga_am_download_status_done,
    failedStatusRes = R.string.giga_am_download_status_failed,
    downloadStartedRes = R.string.giga_am_download_started_in_bg,
    importStartedRes = R.string.giga_am_import_started_in_bg,
    clearTitleRes = R.string.giga_am_clear_confirm_title,
    clearMessageRes = R.string.giga_am_clear_confirm_message,
    clearDoneRes = R.string.giga_am_clear_done,
    clearFailedRes = R.string.giga_am_clear_failed,
    variants = listOf(AsrLocalChoice("giga-v3", R.string.giga_am_model_v3)),
    currentVariant = { normalizeGigaAmVariant(it.gigaAmModelVariant) },
    downloadUrl = {
        "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16.zip"
    },
    isReady = linkedAsrModelReady(AsrVendor.GigaAm),
    checkStatus = linkedAsrModelStatus(AsrVendor.GigaAm),
    clearInstalled = { context, _ ->
        clearInstalledAsrLocalModel(AsrVendor.GigaAm, deleteInstalled = {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            File(base, "giga_am").deleteIfExists()
        })
    }
)

internal val FireRedAsrModelSpec = AsrLocalModelSpec(
    key = "firered_asr",
    vendor = AsrVendor.FireRedAsr,
    modelType = "firered_asr",
    importModelType = "firered_asr",
    variantLabelRes = R.string.label_fr_model_variant,
    downloadButtonRes = R.string.btn_fr_download_model,
    importButtonRes = R.string.btn_fr_import_model,
    clearButtonRes = R.string.btn_fr_clear_model,
    doneStatusRes = R.string.fr_download_status_done,
    failedStatusRes = R.string.fr_download_status_failed,
    downloadStartedRes = R.string.fr_download_started_in_bg,
    importStartedRes = R.string.fr_import_started_in_bg,
    clearTitleRes = R.string.fr_clear_confirm_title,
    clearMessageRes = R.string.fr_clear_confirm_message,
    clearDoneRes = R.string.fr_clear_done,
    clearFailedRes = R.string.fr_clear_failed,
    variants = listOf(
        AsrLocalChoice("ctc-int8", R.string.fr_model_ctc_int8)
    ),
    currentVariant = { it.frModelVariant },
    downloadUrl = {
        "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25.zip"
    },
    isReady = linkedAsrModelReady(AsrVendor.FireRedAsr),
    checkStatus = linkedAsrModelStatus(AsrVendor.FireRedAsr),
    clearInstalled = { context, prefs ->
        clearInstalledAsrLocalModel(AsrVendor.FireRedAsr, deleteInstalled = {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            File(File(base, "firered_asr"), prefs.frModelVariant).deleteIfExists()
        })
    }
)

internal val XAsrModelSpec = AsrLocalModelSpec(
    key = "x_asr",
    vendor = AsrVendor.XAsr,
    modelType = "x_asr",
    importModelType = "x_asr",
    variantLabelRes = R.string.label_x_asr_model_variant,
    downloadButtonRes = R.string.btn_x_asr_download_model,
    importButtonRes = R.string.btn_x_asr_import_model,
    clearButtonRes = R.string.btn_x_asr_clear_model,
    doneStatusRes = R.string.x_asr_download_status_done,
    failedStatusRes = R.string.x_asr_download_status_failed,
    downloadStartedRes = R.string.x_asr_download_started_in_bg,
    importStartedRes = R.string.x_asr_import_started_in_bg,
    clearTitleRes = R.string.x_asr_clear_confirm_title,
    clearMessageRes = R.string.x_asr_clear_confirm_message,
    clearDoneRes = R.string.x_asr_clear_done,
    clearFailedRes = R.string.x_asr_clear_failed,
    variants = listOf(
        AsrLocalChoice("x-asr-480ms", R.string.x_asr_variant_480ms)
    ),
    currentVariant = { "x-asr-480ms" },
    downloadUrl = {
        "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-streaming-x-asr-480ms-zh-en.zip"
    },
    isReady = linkedAsrModelReady(AsrVendor.XAsr),
    checkStatus = linkedAsrModelStatus(AsrVendor.XAsr),
    clearInstalled = { context, _ ->
        clearInstalledAsrLocalModel(AsrVendor.XAsr, deleteInstalled = {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            File(base, "x_asr").deleteIfExists()
        })
    }
)

internal val PunctuationModelSpec = AsrLocalModelSpec(
    key = "punctuation",
    vendor = null,
    modelType = "punctuation",
    importModelType = "punctuation",
    variantLabelRes = R.string.label_punct_model_shared,
    downloadButtonRes = R.string.btn_punct_download_model,
    importButtonRes = R.string.btn_punct_import_model,
    clearButtonRes = R.string.btn_punct_clear_model,
    doneStatusRes = R.string.punct_download_status_done,
    failedStatusRes = R.string.punct_download_status_failed,
    downloadStartedRes = R.string.punct_download_started_in_bg,
    importStartedRes = R.string.punct_import_started_in_bg,
    clearTitleRes = R.string.punct_clear_confirm_title,
    clearMessageRes = R.string.punct_clear_confirm_message,
    clearDoneRes = R.string.punct_clear_done,
    clearFailedRes = R.string.punct_clear_failed,
    variants = listOf(
        AsrLocalChoice("ct-zh-en-int8", R.string.label_punct_model_shared)
    ),
    currentVariant = { "ct-zh-en-int8" },
    downloadUrl = {
        "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8.zip"
    },
    isReady = { viewModel, context -> viewModel.isPunctuationModelInstalled(context) },
    checkStatus = { viewModel, context -> viewModel.checkPunctuationModelStatus(context) },
    clearInstalled = { context, _ -> SherpaPunctuationManager.clearInstalledModel(context) }
)

internal val AllAsrLocalModelSpecs = listOf(
    SenseVoiceModelSpec,
    FunAsrNanoModelSpec,
    Qwen3AsrModelSpec,
    ParakeetModelSpec,
    GigaAmModelSpec,
    FireRedAsrModelSpec,
    XAsrModelSpec,
    PunctuationModelSpec
)

internal fun clearInstalledAsrLocalModel(
    vendor: AsrVendor,
    deleteInstalled: () -> Unit,
    unload: (AsrVendor) -> Boolean = AsrLocalModelCatalog::unload
): Boolean {
    deleteInstalled()
    return unload(vendor)
}

private fun linkedAsrModelReady(
    vendor: AsrVendor
): (AsrSettingsViewModel, Context) -> Boolean = { viewModel, context ->
    AsrLocalModelCatalog.entryFor(vendor) != null &&
        viewModel.checkLocalVendorModelDownloaded(context, vendor)
}

private fun linkedAsrModelStatus(
    vendor: AsrVendor
): (AsrSettingsViewModel, Context) -> LocalModelCheck<*> = { viewModel, context ->
    if (AsrLocalModelCatalog.entryFor(vendor) != null) {
        viewModel.checkLocalVendorModelStatus(context, vendor)
    } else {
        LocalModelCheck.Missing
    }
}

private fun File.deleteIfExists() {
    if (exists()) deleteRecursively()
}
