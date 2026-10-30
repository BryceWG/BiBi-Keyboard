// ASR vendor identifiers and compatibility aliases.
package com.brycewg.asrkb.asr

internal const val LEGACY_X_ASR_VENDOR_ID = "paraformer"

enum class AsrVendor(val id: String) {
    Volc("volc"),
    SiliconFlow("siliconflow"),
    ElevenLabs("elevenlabs"),
    OpenAI("openai"),
    OpenRouter("openrouter"),
    DashScope("dashscope"),
    Gemini("gemini"),
    Soniox("soniox"),
    StepAudio("stepaudio"),
    Zhipu("zhipu"),
    Cohere("cohere"),
    SenseVoice("sensevoice"),
    FunAsrNano("funasr_nano"),
    Qwen3Asr("qwen3_asr"),
    Parakeet("parakeet"),
    GigaAm("giga_am"),
    FireRedAsr("firered_asr"),
    XAsr("x_asr"),
    MiMo("mimo"),
    Tencent("tencent");

    companion object {
        fun fromId(id: String?): AsrVendor = AsrVendorRegistry.vendorFromIdOrNull(id) ?: Volc
    }
}
