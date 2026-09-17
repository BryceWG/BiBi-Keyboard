package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Base64
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.shouldCompressAudioBeforeOpenAiUpload
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * 使用 OpenAI 兼容接口的非流式 ASR 引擎。
 * 支持 audio/transcriptions 与多模态 chat/completions 两种请求方式。
 */
class OpenAiFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration),
    PcmBatchRecognizer {

    companion object {
        private const val TAG = "OpenAiFileAsrEngine"

        internal fun buildOpenAiChatCompletionsBody(
            model: String,
            base64Audio: String,
            mimeType: String,
            prompt: String
        ): String {
            val system = JSONObject().apply {
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
            val user = JSONObject().apply {
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
                                        put("data", base64Audio)
                                        put("format", mimeType.substringAfter('/'))
                                    }
                                )
                            }
                        )
                    }
                )
            }
            return JSONObject().apply {
                put("model", model)
                put("stream", false)
                put(
                    "messages",
                    JSONArray().apply {
                        put(system)
                        put(user)
                    }
                )
            }.toString()
        }

        internal fun parseOpenAiChatText(body: String): String {
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
                Log.e(TAG, "Failed to parse OpenAI chat response", t)
                ""
            }
        }
    }

    private val http: OkHttpClient = httpClient ?: AsrHttpClientProvider.newBuilder()
        .addInterceptor(ApiLogInterceptor())
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override val uploadAudioEncodingSpec: UploadAudioEncodingSpec?
        get() = if (prefs.oaAsrUseCompletions || !shouldCompressTranscriptionsUpload) {
            null
        } else {
            UploadAudioEncodingSpec.M4A_AAC_LC
        }

    private val shouldCompressTranscriptionsUpload: Boolean
        get() = shouldCompressAudioBeforeOpenAiUpload(
            globalEnabled = prefs.uploadAudioCompressionEnabled,
            endpoint = prefs.oaAsrEndpoint
        )

    override suspend fun recognize(pcm: ByteArray) {
        val audio = if (prefs.oaAsrUseCompletions) {
            pcmToWavUploadAudio(pcm)
        } else if (shouldCompressTranscriptionsUpload) {
            encodePcmForUpload(context, pcm, sampleRate, UploadAudioEncodingSpec.M4A_AAC_LC)
        } else {
            pcmToWavUploadAudio(pcm)
        }
        recognizeEncoded(audio)
    }

    override suspend fun recognizeEncoded(audio: UploadAudioData) {
        if (prefs.oaAsrUseCompletions) {
            recognizeWithCompletions(audio)
            return
        }
        var tmp: File? = null
        try {
            val created = File.createTempFile(
                "asr_oa_",
                ".${audio.container.extension}",
                context.cacheDir
            )
            tmp = created
            FileOutputStream(created).use { it.write(audio.bytes) }

            val apiKey = prefs.oaAsrApiKey
            val endpoint = prefs.oaAsrEndpoint.ifBlank { Prefs.DEFAULT_OA_ASR_ENDPOINT }
            val model = prefs.oaAsrModel.ifBlank { Prefs.DEFAULT_OA_ASR_MODEL }

            val usePrompt = prefs.oaAsrUsePrompt
            val basePrompt = prefs.oaAsrPrompt.trim()
            val prompt = basePrompt
            val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart(
                    "file",
                    audio.fileName,
                    created.asRequestBody(audio.mimeType.toMediaType())
                )
                .addFormDataPart("response_format", "json")
            if (usePrompt && prompt.isNotEmpty()) {
                multipartBuilder.addFormDataPart("prompt", prompt)
            }
            val lang = prefs.oaAsrLanguage.trim()
            if (lang.isNotEmpty()) {
                multipartBuilder.addFormDataPart("language", lang)
            }
            val multipart = multipartBuilder.build()

            val reqBuilder = Request.Builder()
                .url(endpoint)
                .tag(
                    ApiLogMeta::class.java,
                    ApiLogRecorder.meta(
                        category = "ASR",
                        vendor = "openai",
                        model = model,
                        requestStructure = "multipart fields=model, file, response_format, prompt?, language?"
                    )
                )
                .post(multipart)
            if (apiKey.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $apiKey")
            }
            val request = reqBuilder.build()

            val t0 = System.nanoTime()
            val resp = http.newCall(request).execute()
            resp.use { r ->
                val bodyStr = r.body.string().orEmpty()
                if (!r.isSuccessful) {
                    val extra = extractErrorHint(bodyStr)
                    val detail = formatHttpDetail(r.message, extra)
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, r.code, detail)
                    )
                    return
                }
                val text = parseTextFromResponse(bodyStr)
                if (text.isNotBlank()) {
                    val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                    try {
                        onRequestDuration?.invoke(dt)
                    } catch (_: Throwable) {}
                    listener.onFinal(text)
                } else {
                    listener.onError(context.getString(R.string.error_asr_empty_result))
                }
            }
        } catch (t: Throwable) {
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        } finally {
            try {
                val file = tmp
                if (file != null && file.exists() && !file.delete()) {
                    file.deleteOnExit()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to delete OpenAI temp upload audio", t)
            }
        }
    }

    private fun recognizeWithCompletions(audio: UploadAudioData) {
        try {
            val apiKey = prefs.oaAsrApiKey
            val endpoint = prefs.oaAsrEndpoint.ifBlank { Prefs.DEFAULT_OA_ASR_ENDPOINT }
            val model = prefs.oaAsrModel.ifBlank { Prefs.DEFAULT_OA_ASR_MODEL }
            val prompt = if (prefs.oaAsrUsePrompt && prefs.oaAsrPrompt.trim().isNotEmpty()) {
                prefs.oaAsrPrompt.trim()
            } else {
                context.getString(R.string.prompt_default_sf_omni)
            }
            val base64Audio = Base64.encodeToString(audio.bytes, Base64.NO_WRAP)
            val body = buildOpenAiChatCompletionsBody(
                model = model,
                base64Audio = base64Audio,
                mimeType = audio.mimeType,
                prompt = prompt
            )
            val requestBuilder = Request.Builder()
                .url(endpoint)
                .tag(
                    ApiLogMeta::class.java,
                    ApiLogRecorder.meta(
                        category = "ASR",
                        vendor = "openai",
                        model = model,
                        requestStructure = "json object keys=model, messages, stream; input_audio.data omitted"
                    )
                )
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            if (apiKey.isNotBlank()) {
                requestBuilder.addHeader("Authorization", "Bearer $apiKey")
            }

            val t0 = System.nanoTime()
            val resp = http.newCall(requestBuilder.build()).execute()
            resp.use { r ->
                val bodyStr = r.body.string().orEmpty()
                if (!r.isSuccessful) {
                    val detail = formatHttpDetail(r.message, extractErrorHint(bodyStr))
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, r.code, detail)
                    )
                    return
                }
                val text = parseOpenAiChatText(bodyStr)
                if (text.isNotBlank()) {
                    val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                    try {
                        onRequestDuration?.invoke(dt)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to dispatch OpenAI request duration", t)
                    }
                    listener.onFinal(text)
                } else {
                    listener.onError(context.getString(R.string.error_asr_empty_result))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "OpenAI chat completions recognition failed", t)
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    override suspend fun recognizeFromPcm(pcm: ByteArray) {
        recognize(pcm)
    }

    /**
     * 从响应体中提取错误提示信息
     */
    private fun extractErrorHint(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            when {
                obj.has(
                    "error"
                ) -> obj.optJSONObject("error")?.optString("message")?.trim().orEmpty()
                    .ifBlank { obj.optString("message").trim() }
                obj.has("message") -> obj.optString("message").trim()
                else -> body.take(200).trim()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse error hint from response", t)
            body.take(200).trim()
        }
    }

    /**
     * 从响应体中解析转写文本
     */
    private fun parseTextFromResponse(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            obj.optString("text", "").trim()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse text from response", t)
            ""
        }
    }
}
