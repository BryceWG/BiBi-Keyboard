/**
 * OpenRouter ASR 文件识别引擎实现。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Base64
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 使用 OpenRouter /api/v1/audio/transcriptions 的非流式 ASR 引擎。
 */
class OpenRouterFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration),
    PcmBatchRecognizer {

    companion object {
        private const val TAG = "OpenRouterFileAsrEngine"
    }

    private val http: OkHttpClient = httpClient ?: AsrHttpClientProvider.newBuilder()
        .addInterceptor(ApiLogInterceptor())
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        if (prefs.openRouterAsrApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_openrouter_key))
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        recognizeEncoded(pcmToWavUploadAudio(pcm))
    }

    override suspend fun recognizeEncoded(audio: UploadAudioData) {
        try {
            val apiKey = prefs.openRouterAsrApiKey.trim().removeBearerPrefix()
            val endpoint = prefs.openRouterAsrEndpoint.ifBlank {
                Prefs.DEFAULT_OPENROUTER_ASR_ENDPOINT
            }
            val model = prefs.openRouterAsrModel.ifBlank {
                Prefs.DEFAULT_OPENROUTER_ASR_MODEL
            }
            val body = buildRequestBody(audio, model)
            val request = Request.Builder()
                .url(endpoint)
                .tag(
                    ApiLogMeta::class.java,
                    ApiLogRecorder.meta(
                        category = "ASR",
                        vendor = "openrouter",
                        model = model,
                        requestStructure = "json object keys=model, messages, stream"
                    )
                )
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val t0 = System.nanoTime()
            val resp = http.newCall(request).execute()
            resp.use { r ->
                val bodyStr = r.body.string().orEmpty()
                if (!r.isSuccessful) {
                    val detail = formatHttpDetail(r.message, extractErrorHint(bodyStr))
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
        }
    }

    override suspend fun recognizeFromPcm(pcm: ByteArray) {
        recognize(pcm)
    }

    private fun buildRequestBody(audio: UploadAudioData, model: String): String {
        val inputAudio = JSONObject().apply {
            put("data", Base64.encodeToString(audio.bytes, Base64.NO_WRAP))
            put("format", audio.format)
        }
        return JSONObject().apply {
            put("model", model)
            put("input_audio", inputAudio)
        }.toString()
    }

    private fun extractErrorHint(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            when {
                obj.has("error") -> obj.optJSONObject("error")
                    ?.optString("message")
                    ?.trim()
                    .orEmpty()
                    .ifBlank { obj.optString("message").trim() }
                obj.has("message") -> obj.optString("message").trim()
                else -> body.take(200).trim()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse OpenRouter error hint", t)
            body.take(200).trim()
        }
    }

    private fun parseTextFromResponse(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            obj.optString("text", "").trim()
                .ifBlank { obj.optString("output_text", "").trim() }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse OpenRouter text response", t)
            ""
        }
    }

    private fun String.removeBearerPrefix(): String = replace(Regex("^Bearer\\s+", RegexOption.IGNORE_CASE), "").trim()
}
