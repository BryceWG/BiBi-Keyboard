/**
 * DashScope 非流式文件识别引擎。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Base64
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.DashScopePrefsCompat
import com.brycewg.asrkb.store.Prefs
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 使用阿里云百炼（DashScope）的非流式 ASR 引擎。
 * - Fun-ASR-Flash 与 Qwen-Audio 3.0 走 DashScope REST multimodal-generation + Base64 音频。
 * - Qwen3-ASR-Flash 走同一 REST 入口，使用 asr_options / system prompt。
 * - Qwen3.5 / Qwen3.8 Omni 非实时模型走 OpenAI 兼容 chat/completions + Base64 音频输入。
 */
class DashscopeFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null,
    private val modelIdOverride: String? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration),
    PcmBatchRecognizer {

    companion object {
        private const val TAG = "DashscopeFileAsrEngine"
    }

    private val http: OkHttpClient = httpClient ?: AsrHttpClientProvider.newBuilder()
        .addInterceptor(ApiLogInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    override val uploadAudioEncodingSpec: UploadAudioEncodingSpec?
        get() = dashscopeUploadAudioEncodingSpecForModel(effectiveDashModel())

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        if (prefs.dashApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_dashscope_key))
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        val model = effectiveDashModel()
        val audio = encodePcmForUploadIfEnabled(pcm, model)
        when {
            prefs.isDashGenerationAsrModelId(model) || DashScopePrefsCompat.isQwen3FlashModel(model) ->
                recognizeWithGenerationApi(audio, model)
            prefs.isDashOmniModelId(model) -> recognizeWithOmni(audio, model)
            else -> reportUnsupportedModel(model)
        }
    }

    override suspend fun recognizeEncoded(audio: UploadAudioData) {
        val model = effectiveDashModel()
        when {
            prefs.isDashGenerationAsrModelId(model) || DashScopePrefsCompat.isQwen3FlashModel(model) ->
                recognizeWithGenerationApi(audio, model)
            prefs.isDashOmniModelId(model) -> recognizeWithOmni(audio, model)
            else -> reportUnsupportedModel(model)
        }
    }

    override suspend fun recognizeFromPcm(pcm: ByteArray) {
        recognize(pcm)
    }

    private fun effectiveDashModel(): String {
        val override = modelIdOverride?.trim().orEmpty()
        if (override.isNotEmpty()) {
            return DashScopePrefsCompat.normalizeDashAsrModel(override)
        }
        return prefs.dashAsrModel.trim().ifBlank { Prefs.DEFAULT_DASH_MODEL }
    }

    private fun reportUnsupportedModel(model: String) {
        listener.onError(
            context.getString(
                R.string.error_recognize_failed_with_reason,
                context.getString(R.string.error_dashscope_unsupported_model, model)
            )
        )
    }

    private fun encodePcmForUploadIfEnabled(pcm: ByteArray, model: String): UploadAudioData {
        val encodingSpec = dashscopeUploadAudioEncodingSpecForModel(model)
        return if (prefs.uploadAudioCompressionEnabled) {
            encodePcmForUpload(
                context,
                pcm,
                sampleRate,
                encodingSpec
            )
        } else {
            pcmToWavUploadAudio(pcm)
        }
    }

    /**
     * Fun-ASR-Flash / Qwen-Audio 3.0 / Qwen3-ASR-Flash 非流式 REST 路径。
     */
    private fun recognizeWithGenerationApi(audio: UploadAudioData, model: String) {
        try {
            val base64Audio = Base64.encodeToString(audio.bytes, Base64.NO_WRAP)
            val body = if (DashScopePrefsCompat.isQwen3FlashModel(model)) {
                buildDashQwen3FlashRequestBody(
                    model = model,
                    base64Audio = base64Audio,
                    audio = audio,
                    prompt = prefs.dashPrompt,
                    languages = prefs.getDashLanguages()
                )
            } else {
                buildDashGenerationAsrRequestBody(
                    model = model,
                    base64Audio = base64Audio,
                    audio = audio,
                    sampleRate = sampleRate,
                    languages = prefs.getDashLanguages()
                )
            }
            val request = Request.Builder()
                .url(prefs.getDashMultimodalGenerationEndpoint())
                .tag(
                    ApiLogMeta::class.java,
                    ApiLogRecorder.meta(
                        category = "ASR",
                        vendor = "dashscope",
                        model = model,
                        requestStructure = "json object keys=model,input,parameters; messages=input_audio"
                    )
                )
                .addHeader("Authorization", "Bearer ${prefs.dashApiKey}")
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .addHeader("X-DashScope-SSE", "disable")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val t0 = System.nanoTime()
            val response = http.newCall(request).execute()
            response.use { r ->
                val bodyStr = r.body.string().orEmpty()
                if (!r.isSuccessful) {
                    val detail = formatHttpDetail(r.message, extractErrorHint(bodyStr))
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, r.code, detail)
                    )
                    return
                }

                dispatchFinalText(parseDashscopeGenerationText(bodyStr), t0)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "DashScope generation ASR recognition failed", t)
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    /**
     * Qwen Omni 非实时识别路径。
     */
    private fun recognizeWithOmni(audio: UploadAudioData, model: String) {
        try {
            val base64Audio = Base64.encodeToString(audio.bytes, Base64.NO_WRAP)
            val prompt = prefs.dashPrompt.trim().ifBlank {
                context.getString(R.string.prompt_default_sf_omni)
            }
            val body = buildDashOmniRequestBody(model, base64Audio, audio, prompt)
            val request = Request.Builder()
                .url(prefs.getDashCompatibleModeChatEndpoint())
                .tag(
                    ApiLogMeta::class.java,
                    ApiLogRecorder.meta(
                        category = "ASR",
                        vendor = "dashscope",
                        model = model,
                        requestStructure = "json object keys=input, model, parameters"
                    )
                )
                .addHeader("Authorization", "Bearer ${prefs.dashApiKey}")
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val t0 = System.nanoTime()
            val response = http.newCall(request).execute()
            response.use { r ->
                val bodyStr = r.body.string().orEmpty()
                if (!r.isSuccessful) {
                    val detail = formatHttpDetail(r.message, extractErrorHint(bodyStr))
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, r.code, detail)
                    )
                    return
                }

                val contentType = r.header("Content-Type").orEmpty()
                val looksLikeSse = contentType.contains("text/event-stream", ignoreCase = true) ||
                    bodyStr.lineSequence().any { it.startsWith("data:") }
                val text = if (looksLikeSse) {
                    parseDashscopeOmniSseText(bodyStr)
                } else {
                    parseDashscopeOmniChatText(bodyStr)
                }
                dispatchFinalText(text, t0)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "DashScope Omni recognition failed", t)
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    private fun dispatchFinalText(text: String, startedAtNanos: Long) {
        if (text.isBlank()) {
            listener.onError(context.getString(R.string.error_asr_empty_result))
            return
        }
        val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos)
        try {
            onRequestDuration?.invoke(dt)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to dispatch duration", t)
        }
        listener.onFinal(text)
    }

    private fun parseDashscopeGenerationText(body: String): String {
        val sdkText = parseDashscopeSdkText(body)
        if (sdkText.isNotBlank()) return sdkText
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            val output = obj.optJSONObject("output")
            firstNonBlank(
                output?.optString("text"),
                output?.optString("sentence"),
                output?.optString("transcript"),
                output?.optString("transcription"),
                output?.optString("result"),
                obj.optString("text"),
                obj.optString("sentence"),
                obj.optString("transcript"),
                obj.optString("transcription"),
                obj.optString("result")
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse DashScope generation response", t)
            ""
        }
    }

    /**
     * 从 DashScope SDK 响应体中解析转写文本。
     */
    private fun parseDashscopeSdkText(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            val output = obj.optJSONObject("output") ?: return ""
            val choices = output.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            val msg = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
            val content = msg.optJSONArray("content") ?: return ""
            var txt = ""
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                if (item.has("text")) {
                    txt = item.optString("text").trim()
                    if (txt.isNotEmpty()) break
                }
            }
            txt
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse DashScope SDK response", t)
            ""
        }
    }

    /**
     * 从 DashScope Omni SSE 响应中累积文本。
     */
    private fun parseDashscopeOmniSseText(body: String): String {
        if (body.isBlank()) return ""
        val contentBuilder = StringBuilder()
        val eventBuilder = StringBuilder()

        fun flushEvent() {
            val rawData = eventBuilder.toString().trim()
            eventBuilder.clear()
            if (rawData.isEmpty() || rawData == "[DONE]") return

            try {
                val json = JSONObject(rawData)
                val choices = json.optJSONArray("choices") ?: return
                if (choices.length() == 0) return
                val choice = choices.optJSONObject(0) ?: return
                appendChatDeltaContent(choice.optJSONObject("delta"), contentBuilder)
                if (choice.optString("finish_reason") == "stop") return
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to parse DashScope Omni SSE event", t)
            }
        }

        body.lineSequence().forEach { line ->
            if (line.isEmpty()) {
                flushEvent()
            } else if (line.startsWith("data:")) {
                eventBuilder.append(line.removePrefix("data:").trim()).append('\n')
            }
        }
        flushEvent()
        return contentBuilder.toString().trim()
    }

    /**
     * 从 DashScope Omni 非 SSE JSON 响应中解析文本。
     */
    private fun parseDashscopeOmniChatText(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            val choices = obj.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            val message = choices.optJSONObject(0)?.optJSONObject("message") ?: return ""
            when (val content = message.opt("content")) {
                is String -> content.trim()
                is JSONArray -> {
                    val text = StringBuilder()
                    for (i in 0 until content.length()) {
                        when (val item = content.opt(i)) {
                            is String -> if (item.isNotBlank()) text.append(item)
                            is JSONObject -> {
                                val piece = item.optString("text").ifBlank {
                                    item.optString("content")
                                }
                                if (piece.isNotBlank()) text.append(piece)
                            }
                        }
                    }
                    text.toString().trim()
                }
                else -> ""
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse DashScope Omni chat response", t)
            ""
        }
    }

    private fun firstNonBlank(vararg values: String?): String {
        for (value in values) {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isNotEmpty()) return trimmed
        }
        return ""
    }

    private fun appendChatDeltaContent(delta: JSONObject?, builder: StringBuilder) {
        if (delta == null) return
        when (val content = delta.opt("content")) {
            is String -> if (content.isNotEmpty()) builder.append(content)
            is JSONArray -> {
                for (i in 0 until content.length()) {
                    when (val item = content.opt(i)) {
                        is String -> if (item.isNotEmpty()) builder.append(item)
                        is JSONObject -> {
                            val text = item.optString("text").ifBlank {
                                item.optString("content")
                            }
                            if (text.isNotBlank()) builder.append(text)
                        }
                    }
                }
            }
        }
    }

    private fun extractErrorHint(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            when {
                obj.has("error") -> obj.optJSONObject("error")?.optString("message")?.trim().orEmpty()
                    .ifBlank { obj.optString("message").trim() }
                obj.has("message") -> obj.optString("message").trim()
                else -> body.take(200).trim()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse DashScope error response", t)
            body.take(200).trim()
        }
    }
}

internal fun dashscopeUploadAudioEncodingSpecForModel(model: String): UploadAudioEncodingSpec = if (DashScopePrefsCompat.isOmniModel(model)) {
    UploadAudioEncodingSpec.AAC_ADTS
} else {
    UploadAudioEncodingSpec.M4A_AAC_LC
}

internal fun buildDashOmniRequestBody(
    model: String,
    base64Audio: String,
    audio: UploadAudioData,
    prompt: String
): String {
    val systemMessage = JSONObject().apply {
        put("role", "system")
        put(
            "content",
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("type", "text")
                        put("text", prompt)
                    }
                )
            }
        )
    }
    val userMessage = JSONObject().apply {
        put("role", "user")
        put(
            "content",
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("type", "input_audio")
                        put(
                            "input_audio",
                            JSONObject().apply {
                                put("data", "data:${audio.mimeType};base64,$base64Audio")
                                put("format", audio.format)
                            }
                        )
                    }
                )
            }
        )
    }
    return JSONObject().apply {
        put("model", model)
        put("stream", true)
        put("modalities", JSONArray().put("text"))
        if (DashScopePrefsCompat.isQwen38OmniFlash(model)) {
            put("reasoning_effort", "none")
        }
        put(
            "messages",
            JSONArray().apply {
                put(systemMessage)
                put(userMessage)
            }
        )
    }.toString()
}

internal fun buildDashGenerationAsrRequestBody(
    model: String,
    base64Audio: String,
    audio: UploadAudioData,
    sampleRate: Int,
    languages: List<String>
): String {
    val languageHints = DashScopePrefsCompat.parseDashLanguages(languages.joinToString(","))
    val userMessage = JSONObject().apply {
        put("role", "user")
        put(
            "content",
            JSONArray().put(
                JSONObject().apply {
                    put("type", "input_audio")
                    put(
                        "input_audio",
                        JSONObject().apply {
                            put("data", "data:${audio.mimeType};base64,$base64Audio")
                        }
                    )
                }
            )
        )
    }
    return JSONObject().apply {
        put("model", model)
        put("input", JSONObject().put("messages", JSONArray().put(userMessage)))
        put(
            "parameters",
            JSONObject().apply {
                put("format", audio.format)
                put("sample_rate", sampleRate.toString())
                if (DashScopePrefsCompat.isQwenAudioModel(model) && languageHints.isNotEmpty()) {
                    put("language_hints", JSONArray(languageHints))
                }
            }
        )
    }.toString()
}

internal fun buildDashQwen3FlashRequestBody(
    model: String,
    base64Audio: String,
    audio: UploadAudioData,
    prompt: String,
    languages: List<String>
): String {
    val messages = JSONArray()
    val trimmedPrompt = prompt.trim()
    if (trimmedPrompt.isNotEmpty()) {
        messages.put(
            JSONObject().apply {
                put("role", "system")
                put("content", JSONArray().put(JSONObject().put("text", trimmedPrompt)))
            }
        )
    }
    messages.put(
        JSONObject().apply {
            put("role", "user")
            put(
                "content",
                JSONArray().put(
                    JSONObject().put("audio", "data:${audio.mimeType};base64,$base64Audio")
                )
            )
        }
    )
    val asrOptions = JSONObject().apply {
        put("enable_itn", true)
        languages.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { put("language", it) }
    }
    return JSONObject().apply {
        put("model", model)
        put("input", JSONObject().put("messages", messages))
        put("parameters", JSONObject().put("asr_options", asrOptions))
    }.toString()
}
