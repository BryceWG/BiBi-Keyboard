/**
 * 腾讯云 ASR 引擎目录：五个识别大类及每个大类下的引擎 ID。
 *
 * 归属模块：asr
 *
 * 腾讯没有可运行时查询的引擎清单，这里按官方文档静态维护：
 * - 实时语音识别 WebSocket（doc 48982）与 V2 通道（doc 131127）共用 asr/v2 地址，实测各引擎均按 result.slice_type 分片返回；
 * - 录音文件识别极速版（doc 52097）走 asr/flash/v1 HTTP 通道。
 * 应用音频固定 16kHz，因此不收录 8k 电话引擎。
 */
package com.brycewg.asrkb.asr

import androidx.annotation.StringRes
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.debug.DebugLogManager

internal enum class TencentAsrProtocol {
    Stream,
    FlashFile
}

internal enum class TencentAsrCategory(
    val id: String,
    @param:StringRes val displayNameRes: Int,
    val protocol: TencentAsrProtocol
) {
    LargeStream20(
        id = "large_stream_2_0",
        displayNameRes = R.string.tencent_category_large_stream_2_0,
        protocol = TencentAsrProtocol.Stream
    ),
    LargeStream10(
        id = "large_stream_1_0",
        displayNameRes = R.string.tencent_category_large_stream_1_0,
        protocol = TencentAsrProtocol.Stream
    ),
    StandardStream(
        id = "standard_stream",
        displayNameRes = R.string.tencent_category_standard_stream,
        protocol = TencentAsrProtocol.Stream
    ),
    LargeFile(
        id = "large_file",
        displayNameRes = R.string.tencent_category_large_file,
        protocol = TencentAsrProtocol.FlashFile
    ),
    StandardFile(
        id = "standard_file",
        displayNameRes = R.string.tencent_category_standard_file,
        protocol = TencentAsrProtocol.FlashFile
    );

    val streaming: Boolean get() = protocol == TencentAsrProtocol.Stream
}

internal data class TencentAsrModel(
    val id: String,
    @param:StringRes val displayNameRes: Int
)

internal data class TencentAsrSelection(
    val category: TencentAsrCategory,
    val model: TencentAsrModel
) {
    val streaming: Boolean get() = category.streaming
    val engineId: String get() = model.id
}

internal object TencentAsrModelCatalog {
    val DEFAULT_CATEGORY: TencentAsrCategory = TencentAsrCategory.StandardStream
    const val DEFAULT_MODEL_ID = "16k_zh"

    private fun model(
        id: String,
        @StringRes displayNameRes: Int
    ): TencentAsrModel = TencentAsrModel(id = id, displayNameRes = displayNameRes)

    private val largeStream20Models = listOf(
        model("16k_zh_en_2.0", R.string.tencent_lang_zh_en),
        model("Hy-ASR-3.0-preview", R.string.tencent_model_hy_preview)
    )

    private val largeStream10Models = listOf(
        model("16k_zh_en", R.string.tencent_lang_zh_en),
        model("16k_multi_lang", R.string.tencent_lang_multi_lang),
        model("16k_en_large", R.string.tencent_lang_en)
    )

    private val standardStreamModels = listOf(
        model("16k_zh", R.string.tencent_lang_zh),
        model("16k_zh-TW", R.string.tencent_lang_zh_tw),
        model("16k_zh_edu", R.string.tencent_lang_zh_edu),
        model("16k_zh_medical", R.string.tencent_lang_zh_medical),
        model("16k_zh_court", R.string.tencent_lang_zh_court),
        model("16k_yue", R.string.tencent_lang_yue),
        model("16k_en", R.string.tencent_lang_en),
        model("16k_en_game", R.string.tencent_lang_en_game),
        model("16k_en_edu", R.string.tencent_lang_en_edu),
        model("16k_ko", R.string.tencent_lang_ko),
        model("16k_ja", R.string.tencent_lang_ja),
        model("16k_th", R.string.tencent_lang_th),
        model("16k_id", R.string.tencent_lang_id),
        model("16k_vi", R.string.tencent_lang_vi),
        model("16k_ms", R.string.tencent_lang_ms),
        model("16k_fil", R.string.tencent_lang_fil),
        model("16k_pt", R.string.tencent_lang_pt),
        model("16k_tr", R.string.tencent_lang_tr),
        model("16k_ar", R.string.tencent_lang_ar),
        model("16k_es", R.string.tencent_lang_es),
        model("16k_hi", R.string.tencent_lang_hi),
        model("16k_fr", R.string.tencent_lang_fr),
        model("16k_de", R.string.tencent_lang_de)
    )

    private val largeFileModels = listOf(
        model("16k_zh_en", R.string.tencent_lang_zh_en),
        model("16k_multi_lang", R.string.tencent_lang_multi_lang)
    )

    private val standardFileModels = listOf(
        model("16k_zh", R.string.tencent_lang_zh),
        model("16k_zh-PY", R.string.tencent_lang_zh_py),
        model("16k_yue", R.string.tencent_lang_yue),
        model("16k_en", R.string.tencent_lang_en),
        model("16k_ja", R.string.tencent_lang_ja),
        model("16k_ko", R.string.tencent_lang_ko),
        model("16k_vi", R.string.tencent_lang_vi),
        model("16k_ms", R.string.tencent_lang_ms),
        model("16k_id", R.string.tencent_lang_id),
        model("16k_fil", R.string.tencent_lang_fil),
        model("16k_th", R.string.tencent_lang_th),
        model("16k_pt", R.string.tencent_lang_pt),
        model("16k_tr", R.string.tencent_lang_tr),
        model("16k_ar", R.string.tencent_lang_ar),
        model("16k_es", R.string.tencent_lang_es),
        model("16k_hi", R.string.tencent_lang_hi),
        model("16k_fr", R.string.tencent_lang_fr),
        model("16k_de", R.string.tencent_lang_de)
    )

    private val modelsByCategory: Map<TencentAsrCategory, List<TencentAsrModel>> = mapOf(
        TencentAsrCategory.LargeStream20 to largeStream20Models,
        TencentAsrCategory.LargeStream10 to largeStream10Models,
        TencentAsrCategory.StandardStream to standardStreamModels,
        TencentAsrCategory.LargeFile to largeFileModels,
        TencentAsrCategory.StandardFile to standardFileModels
    )

    fun categories(): List<TencentAsrCategory> = TencentAsrCategory.entries.toList()

    fun categoryById(id: String): TencentAsrCategory? = TencentAsrCategory.entries.firstOrNull { it.id == id }

    fun models(category: TencentAsrCategory): List<TencentAsrModel> = modelsByCategory[category].orEmpty()

    fun defaultModel(category: TencentAsrCategory): TencentAsrModel = models(category).firstOrNull { it.id == DEFAULT_MODEL_ID }
        ?: models(category).first()

    /**
     * 把设置里保存的「大类 + 引擎」解析成可用的选择，未知值回退到默认大类并记录日志。
     *
     * 兼容旧版本只保存 engine type 的情况：按「流式优先」反查大类。
     */
    fun resolveSelection(categoryId: String, modelId: String): TencentAsrSelection {
        val wantedModel = modelId.trim()
        val category = categoryById(categoryId)
        if (category != null) {
            val model = models(category).firstOrNull { it.id == wantedModel } ?: defaultModel(category)
            if (wantedModel.isNotEmpty() && model.id != wantedModel) {
                logFallback(categoryId, wantedModel, model.id)
            }
            return TencentAsrSelection(category = category, model = model)
        }
        if (wantedModel.isNotEmpty()) {
            for (candidate in TencentAsrCategory.entries) {
                val model = models(candidate).firstOrNull { it.id == wantedModel } ?: continue
                if (categoryId.isNotEmpty()) logFallback(categoryId, wantedModel, model.id)
                return TencentAsrSelection(category = candidate, model = model)
            }
            logFallback(categoryId, wantedModel, DEFAULT_MODEL_ID)
        }
        return TencentAsrSelection(category = DEFAULT_CATEGORY, model = defaultModel(DEFAULT_CATEGORY))
    }

    private fun logFallback(categoryId: String, rawModel: String, fallbackModel: String) {
        DebugLogManager.logWarning(
            category = "asr",
            event = "tencent_model_unknown",
            data = mapOf(
                "category" to categoryId,
                "model" to rawModel,
                "fallback" to fallbackModel
            )
        )
    }
}
