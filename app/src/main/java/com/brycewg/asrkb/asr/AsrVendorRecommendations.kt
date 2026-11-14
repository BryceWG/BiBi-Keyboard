/**
 * ASR 供应商的语言推荐规则；只影响选择器展示，不参与引擎路由。
 */
package com.brycewg.asrkb.asr

internal object AsrVendorRecommendations {
    // 每种语言选 3～5 个供应商，同时包含在线和本地；未单独适配的语言使用 other 名单。
    // key 使用推荐语言标识（例如 zh-Hans、zh-Hant、en、yue），集合不定义排序。
    private val vendorsByLanguage: Map<String, Set<AsrVendor>> = mapOf(
        "zh-Hans" to setOf(AsrVendor.Volc, AsrVendor.DashScope, AsrVendor.DeepSeek, AsrVendor.XAsr, AsrVendor.Qwen3Asr),
        "zh-Hant" to setOf(AsrVendor.Volc, AsrVendor.DashScope, AsrVendor.Soniox, AsrVendor.SenseVoice, AsrVendor.Qwen3Asr),
        "yue" to setOf(AsrVendor.Tencent, AsrVendor.Volc, AsrVendor.DashScope, AsrVendor.SenseVoice, AsrVendor.Qwen3Asr),
        "en" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.XAsr, AsrVendor.Qwen3Asr),
        "ja" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.SenseVoice, AsrVendor.Qwen3Asr),
        "ko" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.SenseVoice, AsrVendor.Qwen3Asr),
        "de" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.Parakeet, AsrVendor.Qwen3Asr),
        "fr" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.Parakeet, AsrVendor.Qwen3Asr),
        "es" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.Parakeet, AsrVendor.Qwen3Asr),
        "pt" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.Parakeet, AsrVendor.Qwen3Asr),
        "it" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.Parakeet, AsrVendor.Qwen3Asr),
        "ru" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.GigaAm, AsrVendor.Qwen3Asr),
        "ar" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.Qwen3Asr),
        "hi" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.Qwen3Asr),
        "th" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.Qwen3Asr),
        "vi" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.Qwen3Asr),
        "id" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.Qwen3Asr),
        "ms" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.Qwen3Asr),
        "tr" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.Qwen3Asr),
        "other" to setOf(AsrVendor.Gemini, AsrVendor.Soniox, AsrVendor.ElevenLabs, AsrVendor.SenseVoice, AsrVendor.Qwen3Asr)
    )

    val adaptedLanguageTags: Set<String>
        get() = vendorsByLanguage.filterValues { it.isNotEmpty() }.keys

    fun forLanguage(languageTag: String): Set<AsrVendor> = vendorsByLanguage[languageTag].orEmpty()

    /** 稳定排序：推荐与非推荐各自保留传入的默认顺序。 */
    fun prioritize(vendors: List<AsrVendor>, recommended: Set<AsrVendor>): List<AsrVendor> = vendors.sortedBy { if (it in recommended) 0 else 1 }
}
