/**
 * Gemini 文件转写引擎实现。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Base64
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 使用 Google Gemini generateContent 的非流式 ASR 引擎（通过提示词进行转录）。
 */
class GeminiFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration),
    PcmBatchRecognizer {

    companion object {
        private const val TAG = "GeminiFileAsrEngine"
    }

    private val http: OkHttpClient = httpClient ?: AsrHttpClientProvider.newBuilder()
        .addInterceptor(ApiLogInterceptor())
        // Inline base64 可能接近 20MB，转写本身也可能较慢
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    override val uploadAudioEncodingSpec: UploadAudioEncodingSpec =
        UploadAudioEncodingSpec.AAC_ADTS

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        val configured = when (prefs.geminiAsrMode) {
            GeminiAsrMode.Gemini -> prefs.getGeminiApiKeys().isNotEmpty()
            GeminiAsrMode.Transcribe -> prefs.gemTranscribeApiKey.isNotBlank()
        }
        if (!configured) {
            listener.onError(context.getString(R.string.error_missing_gemini_key))
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        val audio = if (prefs.uploadAudioCompressionEnabled) {
            encodePcmForUpload(context, pcm, sampleRate, uploadAudioEncodingSpec)
        } else {
            pcmToWavUploadAudio(pcm)
        }
        recognizeEncoded(audio)
    }

    override suspend fun recognizeEncoded(audio: UploadAudioData) {
        val mode = prefs.geminiAsrMode
        logDiag("gemini_request_start", mapOf("mode" to mode.id))
        if (mode == GeminiAsrMode.Transcribe) {
            recognizeTranscribe(audio)
            return
        }
        try {
            val b64 = Base64.encodeToString(audio.bytes, Base64.NO_WRAP)
            val apiKeys = prefs.getGeminiApiKeys()
            val apiKey = apiKeys.random()
            val endpoint = prefs.gemEndpoint
            val model = prefs.gemModel.ifBlank { Prefs.DEFAULT_GEM_MODEL }
            val basePrompt = prefs.gemPrompt.ifBlank {
                context.getString(R.string.prompt_default_gem)
            }
            val prompt = basePrompt

            val body = buildGeminiRequestBody(b64, audio.mimeType, prompt, model)
            val req = Request.Builder()
                .url(buildGeminiRequestUrl(endpoint, model, apiKey))
                .tag(
                    ApiLogMeta::class.java,
                    ApiLogRecorder.meta(
                        category = "ASR",
                        vendor = "gemini",
                        model = model,
                        requestStructure = "json object keys=contents; inline_data.data=base64 omitted"
                    )
                )
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val t0 = System.nanoTime()
            val resp = http.newCall(req).execute()
            resp.use { r ->
                val str = r.body.string().orEmpty()
                if (!r.isSuccessful) {
                    val hint = extractGeminiError(str)
                    val detail = formatHttpDetail(r.message, hint)
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, r.code, detail)
                    )
                    logDiag("gemini_request_error", mapOf("mode" to mode.id, "code" to r.code))
                    return
                }
                val text = parseGeminiText(str)
                if (text.isNotBlank()) {
                    val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                    try {
                        onRequestDuration?.invoke(dt)
                    } catch (_: Throwable) {}
                    listener.onFinal(text)
                    logDiag("gemini_request_final", mapOf("mode" to mode.id))
                } else {
                    listener.onError(context.getString(R.string.error_asr_empty_result))
                    logDiag("gemini_request_error", mapOf("mode" to mode.id, "reason" to "empty_result"))
                }
            }
        } catch (t: Throwable) {
            logDiag("gemini_request_error", mapOf("mode" to mode.id, "error_type" to t.javaClass.simpleName))
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    private suspend fun recognizeTranscribe(audio: UploadAudioData) {
        val t0 = System.nanoTime()
        try {
            val apiKey = prefs.gemTranscribeApiKey.trim()
            val apiRoot = transcribeApiRoot(prefs.gemTranscribeEndpoint)
            val model = prefs.gemTranscribeModel.trim().ifBlank { Prefs.DEFAULT_GEM_TRANSCRIBE_MODEL }
            val b64 = Base64.encodeToString(audio.bytes, Base64.NO_WRAP)
            val body = buildTranscribeRequest(
                b64,
                audio.mimeType,
                model,
                prefs.gemTranscribeLanguage,
                prefs.gemTranscribeSmartEnabled
            )
            val request = Request.Builder()
                .url(appendPath(apiRoot, "interactions"))
                .tag(
                    ApiLogMeta::class.java,
                    ApiLogRecorder.meta(
                        category = "ASR",
                        vendor = "gemini",
                        model = model,
                        requestStructure = "json keys=model,input,generation_config; audio.data=base64 omitted",
                        redactErrorBody = true
                    )
                )
                .addHeader("x-goog-api-key", apiKey)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(request).execute().use { response ->
                val textBody = response.body.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = formatHttpDetail(response.message, extractGeminiError(textBody))
                    listener.onError(context.getString(R.string.error_request_failed_http, response.code, detail))
                    logDiag("gemini_request_error", mapOf("mode" to "transcribe", "code" to response.code))
                    return
                }
                val text = parseTranscribeText(textBody)
                if (text.isBlank()) {
                    listener.onError(context.getString(R.string.error_asr_empty_result))
                    logDiag("gemini_request_error", mapOf("mode" to "transcribe", "reason" to "empty_result"))
                } else {
                    try {
                        onRequestDuration?.invoke(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0))
                    } catch (_: Throwable) {}
                    listener.onFinal(text)
                    logDiag("gemini_request_final", mapOf("mode" to "transcribe"))
                }
            }
        } catch (t: Throwable) {
            logDiag("gemini_request_error", mapOf("mode" to "transcribe", "error_type" to t.javaClass.simpleName))
            listener.onError(context.getString(R.string.error_recognize_failed_with_reason, t.message ?: ""))
        }
    }

    private fun buildTranscribeRequest(
        base64Audio: String,
        mimeType: String,
        model: String,
        language: String,
        smart: Boolean
    ): JSONObject = JSONObject().apply {
        put("model", "models/${model.removeRepeatedModelPrefix()}")
        put(
            "input",
            org.json.JSONArray().put(
                JSONObject()
                    .put("type", "audio")
                    .put("data", base64Audio)
                    .put("mime_type", mimeType)
            )
        )
        val config = JSONObject().apply {
            if (language.isNotBlank()) {
                put("language_codes", org.json.JSONArray().put(language))
            }
            if (smart) put("mode", JSONObject().put("type", "smart"))
        }
        if (config.length() > 0) put("generation_config", JSONObject().put("transcription_config", config))
    }

    private fun parseTranscribeText(body: String): String {
        return try {
            val steps = JSONObject(body).optJSONArray("steps") ?: return ""
            for (i in steps.length() - 1 downTo 0) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("type") != "model_output") continue
                val content = step.optJSONArray("content") ?: continue
                return (0 until content.length()).joinToString("") { j ->
                    val item = content.optJSONObject(j)
                    if (item?.optString("type") == "text") item.optString("text") else ""
                }.trim()
            }
            ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun transcribeApiRoot(raw: String): String {
        val (base, query) = raw.trim().split("?", limit = 2).let { it[0] to it.getOrNull(1) }
        val cleaned = base.trim().trimEnd('/').ifBlank { Prefs.DEFAULT_GEM_ENDPOINT }
        val versionMatch = Regex("/v\\d+(beta)?", RegexOption.IGNORE_CASE).find(cleaned)
        val apiBase = if (versionMatch == null) {
            "$cleaned/v1beta"
        } else {
            cleaned.substring(0, versionMatch.range.last + 1)
        }
        return attachQuery(apiBase, query)
    }

    private fun appendPath(url: String, path: String): String {
        val (base, query) = url.split("?", limit = 2).let { it[0] to it.getOrNull(1) }
        return attachQuery("${base.trimEnd('/')}/${path.trimStart('/')}", query)
    }

    private fun attachQuery(base: String, query: String?): String = if (query.isNullOrBlank()) base else "$base?$query"

    private fun String.removeRepeatedModelPrefix(): String {
        var value = trim()
        while (value.startsWith("models/", ignoreCase = true)) {
            value = value.substringAfter('/')
        }
        return value
    }

    private fun logDiag(event: String, data: Map<String, Any?>) = DebugLogManager.logBase(category = "asr", event = event, data = data)

    override suspend fun recognizeFromPcm(pcm: ByteArray) {
        recognize(pcm)
    }

    /**
     * 构建 Gemini API 请求体
     */
    private fun buildGeminiRequestBody(
        base64Audio: String,
        mimeType: String,
        prompt: String,
        model: String
    ): String {
        val inlineAudio = JSONObject().apply {
            put(
                "inline_data",
                JSONObject().apply {
                    put("mime_type", mimeType)
                    put("data", base64Audio)
                }
            )
        }
        val systemInstruction = JSONObject().apply {
            put(
                "parts",
                org.json.JSONArray().apply {
                    put(JSONObject().apply { put("text", prompt) })
                }
            )
        }
        val user = JSONObject().apply {
            put("role", "user")
            put(
                "parts",
                org.json.JSONArray().apply {
                    put(inlineAudio)
                }
            )
        }
        return JSONObject().apply {
            put("system_instruction", systemInstruction)
            put("contents", org.json.JSONArray().apply { put(user) })
            put(
                "generationConfig",
                JSONObject().apply {
                    put("temperature", 0)
                    buildGeminiThinkingConfig(model)?.let { put("thinkingConfig", it) }
                }
            )
        }.toString()
    }

    private fun buildGeminiThinkingConfig(model: String): JSONObject? {
        if (!prefs.geminiDisableThinking) return null

        val modelName = model.substringAfterLast('/').lowercase()
        return when {
            modelName.startsWith("gemini-3") -> JSONObject().apply {
                val level = if (modelName.contains("pro")) "low" else "minimal"
                put("thinkingLevel", level)
            }
            modelName.contains("2.5-pro") -> JSONObject().apply {
                put("thinkingBudget", 128)
            }
            modelName.contains("2.5-flash") -> JSONObject().apply {
                put("thinkingBudget", 0)
            }
            else -> null
        }
    }

    private fun buildGeminiRequestUrl(endpoint: String, model: String, apiKey: String): String {
        val trimmed = endpoint.trim()
        val (basePart, queryPart) = trimmed.split("?", limit = 2).let {
            it[0] to it.getOrNull(1)
        }
        val normalizedBase = normalizeGeminiEndpointBase(basePart)
        val baseWithPath = "$normalizedBase/models/$model:generateContent"
        val withQuery = if (!queryPart.isNullOrBlank()) "$baseWithPath?$queryPart" else baseWithPath
        val separator = if (withQuery.contains("?")) "&" else "?"
        return "$withQuery${separator}key=$apiKey"
    }

    private fun normalizeGeminiEndpointBase(raw: String): String {
        val cleaned = raw.trim().trimEnd('/')
        if (cleaned.isBlank()) return Prefs.DEFAULT_GEM_ENDPOINT
        val versionPattern = Regex("/v\\d+(beta)?(/|$)", RegexOption.IGNORE_CASE)
        return if (versionPattern.containsMatchIn(cleaned)) cleaned else "$cleaned/v1beta"
    }

    /**
     * 从响应体中提取错误信息
     */
    private fun extractGeminiError(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val o = JSONObject(body)
            if (o.has("error")) {
                val e = o.optJSONObject("error")
                val msg = e?.optString("message").orEmpty()
                val status = e?.optString("status").orEmpty()
                listOf(status, msg).filter { it.isNotBlank() }.joinToString(": ")
            } else {
                body.take(200).trim()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse Gemini error", t)
            body.take(200).trim()
        }
    }

    /**
     * 从 Gemini 响应中解析转写文本
     */
    private fun parseGeminiText(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val o = JSONObject(body)
            val cands = o.optJSONArray("candidates") ?: return ""
            if (cands.length() == 0) return ""
            val cand0 = cands.optJSONObject(0) ?: return ""
            val content = cand0.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            var txt = ""
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                val t = p.optString("text").trim()
                if (t.isNotEmpty()) {
                    txt = t
                    break
                }
            }
            txt
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse Gemini response", t)
            ""
        }
    }
}
