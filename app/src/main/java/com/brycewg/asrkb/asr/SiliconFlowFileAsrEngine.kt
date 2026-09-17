/**
 * SiliconFlow 文件转写引擎实现。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Base64
import android.util.Log
import com.brycewg.asrkb.BuildConfig
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
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
import org.json.JSONObject

/**
 * 使用 SiliconFlow "audio/transcriptions" API 的非流式 ASR 引擎。
 * 支持两种模式：
 * 1. 自有 API Key 模式：使用用户自己的 API Key
 * 2. 免费服务模式：调用免费模型
 */
class SiliconFlowFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration),
    PcmBatchRecognizer {

    companion object {
        private const val TAG = "SiliconFlowFileAsrEngine"
    }

    override val uploadAudioEncodingSpec: UploadAudioEncodingSpec?
        get() = oggOpusUploadAudioEncodingSpecIfSupported()

    private val http: OkHttpClient = httpClient ?: AsrHttpClientProvider.newBuilder()
        .addInterceptor(ApiLogInterceptor())
        // 普通转写可能较慢：放宽连接/读/写与总超时，避免长音频或排队导致的 SocketTimeout
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private val useFreeService: Boolean get() = prefs.sfFreeAsrEnabled
    private val freeApiKey: String get() = BuildConfig.SF_FREE_API_KEY

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        // 免费服务模式无需 API Key，自有模式需要用户 API Key
        if (useFreeService) {
            if (freeApiKey.isBlank()) {
                listener.onError(context.getString(R.string.error_sf_free_service_unavailable))
                return false
            }
        } else {
            if (prefs.sfApiKey.isBlank()) {
                listener.onError(context.getString(R.string.error_missing_siliconflow_key))
                return false
            }
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        try {
            val audio = encodePcmForUploadIfEnabled(pcm)
            val t0 = System.nanoTime()
            recognizeAudio(audio, t0)
        } catch (t: Throwable) {
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    override suspend fun recognizeEncoded(audio: UploadAudioData) {
        try {
            recognizeAudio(audio, System.nanoTime())
        } catch (t: Throwable) {
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    private fun encodePcmForUploadIfEnabled(pcm: ByteArray): UploadAudioData {
        val spec = if (prefs.uploadAudioCompressionEnabled) uploadAudioEncodingSpec else null
        return if (spec != null) {
            encodePcmForUpload(context, pcm, sampleRate, spec)
        } else {
            pcmToWavUploadAudio(pcm)
        }
    }

    private fun recognizeAudio(audio: UploadAudioData, t0: Long) {
        if (useFreeService) {
            recognizeWithFreeService(audio, t0)
        } else {
            recognizeWithApiKey(audio, t0)
        }
    }

    private fun recognizeWithFreeService(audio: UploadAudioData, t0: Long) {
        val tmp = File.createTempFile("asr_", ".${audio.container.extension}", context.cacheDir)
        FileOutputStream(tmp).use { it.write(audio.bytes) }

        try {
            val model = prefs.sfFreeAsrModel.ifBlank { Prefs.DEFAULT_SF_FREE_ASR_MODEL }

            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart(
                    "file",
                    audio.fileName,
                    tmp.asRequestBody(audio.mimeType.toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(Prefs.SF_ENDPOINT)
                .tag(
                    ApiLogMeta::class.java,
                    ApiLogRecorder.meta(
                        category = "ASR",
                        vendor = "siliconflow",
                        requestStructure = "multipart fields=model, file"
                    )
                )
                .addHeader("Authorization", "Bearer $freeApiKey")
                .post(multipart)
                .build()

            val resp = http.newCall(request).execute()
            resp.use { r ->
                val bodyStr = r.body.string().orEmpty()
                if (!r.isSuccessful) {
                    val detail = formatHttpDetail(r.message, null)
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, r.code, detail)
                    )
                    return
                }
                val text = try {
                    val obj = JSONObject(bodyStr)
                    obj.optString("text", "")
                } catch (_: Throwable) {
                    ""
                }
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
        } finally {
            try {
                if (!tmp.delete()) {
                    tmp.deleteOnExit()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to delete SiliconFlow temp upload audio", t)
            }
        }
    }

    /**
     * 使用自有 API Key 进行识别
     */
    private fun recognizeWithApiKey(audio: UploadAudioData, t0: Long) {
        val apiKey = prefs.sfApiKey
        val selectedModel = prefs.sfModel.ifBlank { Prefs.DEFAULT_SF_MODEL }
        val isOmni = selectedModel.startsWith("Qwen/Qwen3-Omni-30B-A3B-")

        if (isOmni) {
            val b64 = Base64.encodeToString(audio.bytes, Base64.NO_WRAP)
            // Qwen3-Omni 通过 chat/completions，支持提示词
            val model = if (selectedModel.isNotBlank()) selectedModel else Prefs.DEFAULT_SF_OMNI_MODEL
            val basePrompt = prefs.sfOmniPrompt.ifBlank {
                context.getString(R.string.prompt_default_sf_omni)
            }
            val prompt = basePrompt
            val body = buildSfChatCompletionsBody(model, b64, audio.mimeType, prompt)
            val request = Request.Builder()
                .url(Prefs.SF_CHAT_COMPLETIONS_ENDPOINT)
                .tag(
                    ApiLogMeta::class.java,
                    ApiLogRecorder.meta(
                        category = "ASR",
                        vendor = "siliconflow",
                        model = model,
                        requestStructure = "json object keys=model, messages, stream"
                    )
                )
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val resp = http.newCall(request).execute()
            resp.use { r ->
                val str = r.body.string().orEmpty()
                if (!r.isSuccessful) {
                    val detail = formatHttpDetail(r.message, null)
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, r.code, detail)
                    )
                    return
                }
                val text = parseSfChatText(str)
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
        } else {
            val tmp = File.createTempFile("asr_", ".${audio.container.extension}", context.cacheDir)
            FileOutputStream(tmp).use { it.write(audio.bytes) }
            try {
                // 其他模型走 transcriptions
                val model = selectedModel
                val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", model)
                    .addFormDataPart(
                        "file",
                        audio.fileName,
                        tmp.asRequestBody(audio.mimeType.toMediaType())
                    )
                    .build()
                val request = Request.Builder()
                    .url(Prefs.SF_ENDPOINT)
                    .tag(
                        ApiLogMeta::class.java,
                        ApiLogRecorder.meta(
                            category = "ASR",
                            vendor = "siliconflow",
                            model = model,
                            requestStructure = "multipart fields=model, file"
                        )
                    )
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(multipart)
                    .build()
                val resp = http.newCall(request).execute()
                resp.use { r ->
                    if (!r.isSuccessful) {
                        val detail = formatHttpDetail(r.message, null)
                        listener.onError(
                            context.getString(R.string.error_request_failed_http, r.code, detail)
                        )
                        return
                    }
                    val bodyStr = r.body.string().orEmpty()
                    val text = try {
                        val obj = JSONObject(bodyStr)
                        obj.optString("text", "")
                    } catch (_: Throwable) {
                        ""
                    }
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
            } finally {
                try {
                    if (!tmp.delete()) {
                        tmp.deleteOnExit()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to delete SiliconFlow temp upload audio", t)
                }
            }
        }
    }

    override suspend fun recognizeFromPcm(pcm: ByteArray) {
        recognize(pcm)
    }

    /**
     * 构建 SiliconFlow Chat Completions API 请求体
     */
    private fun buildSfChatCompletionsBody(
        model: String,
        base64Audio: String,
        mimeType: String,
        prompt: String
    ): String {
        val audioPart = JSONObject().apply {
            put("type", "audio_url")
            put(
                "audio_url",
                JSONObject().apply {
                    put("url", "data:$mimeType;base64,$base64Audio")
                }
            )
        }
        val system = JSONObject().apply {
            put("role", "system")
            put(
                "content",
                org.json.JSONArray().apply {
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
                org.json.JSONArray().apply {
                    put(audioPart)
                }
            )
        }
        return JSONObject().apply {
            put("model", model)
            put(
                "messages",
                org.json.JSONArray().apply {
                    put(system)
                    put(user)
                }
            )
        }.toString()
    }

    /**
     * 从 SiliconFlow Chat 响应中解析转写文本
     */
    private fun parseSfChatText(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val o = JSONObject(body)
            val arr = o.optJSONArray("choices") ?: return ""
            if (arr.length() == 0) return ""
            val c0 = arr.optJSONObject(0) ?: return ""
            val msg = c0.optJSONObject("message") ?: return ""
            val contentAny = msg.opt("content")
            when (contentAny) {
                is String -> contentAny.trim()
                is org.json.JSONArray -> {
                    val sb = StringBuilder()
                    for (i in 0 until contentAny.length()) {
                        val part = contentAny.optJSONObject(i) ?: continue
                        val type = part.optString("type")
                        if (type == "text" || type == "output_text") {
                            val t = part.optString("text").ifBlank { part.optString("content") }
                            if (t.isNotBlank()) sb.append(t)
                        }
                    }
                    sb.toString().trim()
                }
                else -> ""
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse SiliconFlow chat response", t)
            ""
        }
    }
}
