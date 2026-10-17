package com.brycewg.asrkb.store

import android.content.SharedPreferences
import java.net.URI
import java.util.Locale

/**
 * DashScope 偏好项的兼容/推导逻辑（从 [Prefs] / [PrefsBackup] 中拆出）。
 */
internal object DashScopePrefsCompat {
    private const val DASH_LEGACY_QWEN3_REALTIME_VERSIONED_MODEL = "qwen3-asr-flash-realtime-2026-02-10"
    const val MAX_QWEN_AUDIO_LANGUAGE_HINTS = 4

    const val DASH_REGION_CN = "cn"
    const val DASH_REGION_INTL = "intl"
    const val DASH_REGION_CUSTOM = "custom"

    /** 中国大陆主机，不含路径。 */
    const val DASH_HTTP_ORIGIN_CN = "https://maas.qianwenaiapi.com"
    const val DASH_HTTP_ORIGIN_INTL = "https://maas.qwencloudapi.com"

    private const val HTTP_API_V1_PATH = "/api/v1"
    private const val COMPATIBLE_CHAT_PATH = "/compatible-mode/v1/chat/completions"
    private const val MULTIMODAL_GENERATION_SUFFIX = "/services/aigc/multimodal-generation/generation"
    private const val WS_REALTIME_PATH = "/api-ws/v1/realtime"
    private const val WS_INFERENCE_PATH = "/api-ws/v1/inference"

    private val KNOWN_ASR_MODELS = setOf(
        Prefs.DASH_MODEL_FUN_ASR_FLASH,
        Prefs.DASH_MODEL_QWEN_AUDIO_31_FLASH,
        Prefs.DASH_MODEL_QWEN_AUDIO_FLASH,
        Prefs.DASH_MODEL_QWEN3_FLASH,
        Prefs.DASH_MODEL_QWEN35_OMNI_FLASH,
        Prefs.DASH_MODEL_QWEN38_OMNI_FLASH,
        Prefs.DASH_MODEL_QWEN35_OMNI_PLUS,
        Prefs.DASH_MODEL_FUN_ASR_REALTIME,
        Prefs.DASH_MODEL_QWEN_AUDIO_31_MESSAGE,
        Prefs.DASH_MODEL_QWEN_AUDIO_31_REALTIME,
        Prefs.DASH_MODEL_QWEN_AUDIO_REALTIME,
        Prefs.DASH_MODEL_QWEN3_REALTIME
    )

    fun getDashHttpBaseUrl(dashRegion: String, customEndpoint: String = ""): String = resolveDashHttpOrigin(dashRegion, customEndpoint) + HTTP_API_V1_PATH

    fun getDashCompatibleModeChatEndpoint(dashRegion: String, customEndpoint: String = ""): String = resolveDashHttpOrigin(dashRegion, customEndpoint) + COMPATIBLE_CHAT_PATH

    fun getDashMultimodalGenerationEndpoint(dashRegion: String, customEndpoint: String = ""): String = getDashHttpBaseUrl(dashRegion, customEndpoint).trimEnd('/') + MULTIMODAL_GENERATION_SUFFIX

    fun getDashWebsocketRealtimeUrl(dashRegion: String, customEndpoint: String = ""): String = toDashWebsocketOrigin(resolveDashHttpOrigin(dashRegion, customEndpoint)) + WS_REALTIME_PATH

    fun getDashWebsocketInferenceUrl(dashRegion: String, customEndpoint: String = ""): String = toDashWebsocketOrigin(resolveDashHttpOrigin(dashRegion, customEndpoint)) + WS_INFERENCE_PATH

    fun resolveDashHttpOrigin(dashRegion: String, customEndpoint: String = ""): String = when {
        dashRegion.equals(DASH_REGION_INTL, ignoreCase = true) -> DASH_HTTP_ORIGIN_INTL
        dashRegion.equals(DASH_REGION_CUSTOM, ignoreCase = true) ->
            normalizeDashCustomHttpOrigin(customEndpoint) ?: customEndpoint
        else -> DASH_HTTP_ORIGIN_CN
    }

    /**
     * 收成「协议 + 主机」：去掉路径、query、fragment 和末尾斜杠。
     * 空串或无法解析出 http(s) 主机时返回 null，调用方原样使用用户输入。
     */
    fun normalizeDashCustomHttpOrigin(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return null
        val hostPart = if (':' in host) "[$host]" else host
        val portSuffix = if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://$hostPart$portSuffix"
    }

    private fun toDashWebsocketOrigin(httpOrigin: String): String = when {
        httpOrigin.startsWith("https://") -> "wss://" + httpOrigin.removePrefix("https://")
        httpOrigin.startsWith("http://") -> "wss://" + httpOrigin.removePrefix("http://")
        else -> httpOrigin
    }

    fun normalizeDashAsrModel(model: String): String {
        val trimmed = model.trim()
        return when {
            trimmed.isBlank() -> Prefs.DEFAULT_DASH_MODEL
            trimmed.equals(DASH_LEGACY_QWEN3_REALTIME_VERSIONED_MODEL, ignoreCase = true) ->
                Prefs.DASH_MODEL_QWEN3_REALTIME
            else -> trimmed
        }
    }

    fun isGenerationAsrModel(model: String): Boolean = normalizeDashAsrModel(model).let {
        it.equals(Prefs.DASH_MODEL_FUN_ASR_FLASH, ignoreCase = true) ||
            it.equals(Prefs.DASH_MODEL_QWEN_AUDIO_31_FLASH, ignoreCase = true) ||
            it.equals(Prefs.DASH_MODEL_QWEN_AUDIO_FLASH, ignoreCase = true)
    }

    fun isQwen3FlashModel(model: String): Boolean = normalizeDashAsrModel(model)
        .equals(Prefs.DASH_MODEL_QWEN3_FLASH, ignoreCase = true)

    fun isQwen3RealtimeModel(model: String): Boolean = normalizeDashAsrModel(model)
        .equals(Prefs.DASH_MODEL_QWEN3_REALTIME, ignoreCase = true)

    fun isQwenAudio31MessageModel(model: String): Boolean = normalizeDashAsrModel(model)
        .equals(Prefs.DASH_MODEL_QWEN_AUDIO_31_MESSAGE, ignoreCase = true)

    fun isRecognitionStreamingModel(model: String): Boolean = normalizeDashAsrModel(model).let {
        it.equals(Prefs.DASH_MODEL_FUN_ASR_REALTIME, ignoreCase = true) ||
            it.equals(Prefs.DASH_MODEL_QWEN_AUDIO_31_MESSAGE, ignoreCase = true) ||
            it.equals(Prefs.DASH_MODEL_QWEN_AUDIO_31_REALTIME, ignoreCase = true) ||
            it.equals(Prefs.DASH_MODEL_QWEN_AUDIO_REALTIME, ignoreCase = true)
    }

    fun isStreamingModel(model: String): Boolean = isRecognitionStreamingModel(model) || isQwen3RealtimeModel(model)

    fun isKnownAsrModel(model: String): Boolean {
        val normalized = normalizeDashAsrModel(model)
        return KNOWN_ASR_MODELS.any { it.equals(normalized, ignoreCase = true) }
    }

    fun fileFallbackModel(model: String): String? {
        val normalized = normalizeDashAsrModel(model)
        return when {
            normalized.equals(Prefs.DASH_MODEL_FUN_ASR_REALTIME, ignoreCase = true) ->
                Prefs.DASH_MODEL_FUN_ASR_FLASH
            normalized.equals(Prefs.DASH_MODEL_QWEN_AUDIO_31_MESSAGE, ignoreCase = true) ||
                normalized.equals(Prefs.DASH_MODEL_QWEN_AUDIO_31_REALTIME, ignoreCase = true) ->
                Prefs.DASH_MODEL_QWEN_AUDIO_31_FLASH
            normalized.equals(Prefs.DASH_MODEL_QWEN_AUDIO_REALTIME, ignoreCase = true) ->
                Prefs.DASH_MODEL_QWEN_AUDIO_FLASH
            normalized.equals(Prefs.DASH_MODEL_QWEN3_REALTIME, ignoreCase = true) ->
                Prefs.DASH_MODEL_QWEN3_FLASH
            else -> null
        }
    }

    fun isSemanticPunctuationSupported(model: String): Boolean = isRecognitionStreamingModel(model)

    fun isQwenAudio31Model(model: String): Boolean = normalizeDashAsrModel(model).let {
        it.equals(Prefs.DASH_MODEL_QWEN_AUDIO_31_FLASH, ignoreCase = true) ||
            it.equals(Prefs.DASH_MODEL_QWEN_AUDIO_31_MESSAGE, ignoreCase = true) ||
            it.equals(Prefs.DASH_MODEL_QWEN_AUDIO_31_REALTIME, ignoreCase = true)
    }

    /** 支持 language_hints 的 Qwen-Audio 模型（不含 message：文档注明不支持）。 */
    fun isQwenAudioModel(model: String): Boolean = normalizeDashAsrModel(model).let {
        it.equals(Prefs.DASH_MODEL_QWEN_AUDIO_31_FLASH, ignoreCase = true) ||
            it.equals(Prefs.DASH_MODEL_QWEN_AUDIO_FLASH, ignoreCase = true) ||
            it.equals(Prefs.DASH_MODEL_QWEN_AUDIO_31_REALTIME, ignoreCase = true) ||
            it.equals(Prefs.DASH_MODEL_QWEN_AUDIO_REALTIME, ignoreCase = true)
    }

    fun isDisfluencyRemovalSupported(model: String): Boolean = normalizeDashAsrModel(model).let {
        it.equals(Prefs.DASH_MODEL_QWEN_AUDIO_31_FLASH, ignoreCase = true) ||
            it.equals(Prefs.DASH_MODEL_QWEN_AUDIO_31_MESSAGE, ignoreCase = true)
    }

    fun isOmniModel(model: String): Boolean = normalizeDashAsrModel(model).let {
        it.equals(Prefs.DASH_MODEL_QWEN35_OMNI_FLASH, ignoreCase = true) ||
            it.equals(Prefs.DASH_MODEL_QWEN38_OMNI_FLASH, ignoreCase = true) ||
            it.equals(Prefs.DASH_MODEL_QWEN35_OMNI_PLUS, ignoreCase = true)
    }

    fun isQwen38OmniFlash(model: String): Boolean = normalizeDashAsrModel(model)
        .equals(Prefs.DASH_MODEL_QWEN38_OMNI_FLASH, ignoreCase = true)

    fun isPromptSupported(model: String): Boolean {
        val normalized = normalizeDashAsrModel(model)
        return !normalized.startsWith("fun-asr", ignoreCase = true) &&
            !isRecognitionStreamingModel(normalized) &&
            !isGenerationAsrModel(normalized)
    }

    fun isLanguageSupported(model: String): Boolean {
        val normalized = normalizeDashAsrModel(model)
        // message 文档不支持 language_hints；设置页隐藏识别语言项。
        if (isOmniModel(normalized) || isQwenAudio31MessageModel(normalized)) return false
        return !isGenerationAsrModel(normalized) || isQwenAudioModel(normalized)
    }

    fun parseDashLanguages(value: String): List<String> = normalizeDashLanguages(listOf(value))

    fun serializeDashLanguages(values: Iterable<String>): String = normalizeDashLanguages(values).joinToString(",")

    private fun normalizeDashLanguages(values: Iterable<String>): List<String> = values
        .asSequence()
        .flatMap { it.splitToSequence(',') }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(MAX_QWEN_AUDIO_LANGUAGE_HINTS)
        .toList()

    fun deriveDashAsrModelFromLegacyFlags(sp: SharedPreferences): String {
        val streaming = sp.getBoolean(KEY_DASH_STREAMING_ENABLED, false)
        if (!streaming) return Prefs.DEFAULT_DASH_MODEL
        val funAsr = sp.getBoolean(KEY_DASH_FUNASR_ENABLED, false)
        return if (funAsr) Prefs.DASH_MODEL_FUN_ASR_REALTIME else Prefs.DASH_MODEL_QWEN_AUDIO_REALTIME
    }
}
