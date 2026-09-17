package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
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
import org.json.JSONObject

/**
 * 使用智谱 GLM /v4/audio/transcriptions 的非流式 ASR 引擎。
 * API 文档参考：https://open.bigmodel.cn/dev/api
 */
class ZhipuFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration),
    PcmBatchRecognizer {

    companion object {
        private const val TAG = "ZhipuFileAsrEngine"
        private const val ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions"
    }

    private val http: OkHttpClient = httpClient ?: AsrHttpClientProvider.newBuilder()
        .addInterceptor(ApiLogInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        if (prefs.zhipuApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_zhipu_key))
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        try {
            val wav = pcmToWav(pcm)
            val tmp = File.createTempFile("asr_zhipu_", ".wav", context.cacheDir)
            FileOutputStream(tmp).use { it.write(wav) }

            try {
                val apiKey = prefs.zhipuApiKey
                val temperature = prefs.zhipuTemperature
                val prompt = prefs.zhipuPrompt.trim()
                val model = "glm-asr-2512"

                val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", model)
                    .addFormDataPart("temperature", temperature.toString())
                    .addFormDataPart("stream", "false")
                    .addFormDataPart(
                        "file",
                        "audio.wav",
                        tmp.asRequestBody("audio/wav".toMediaType())
                    )

                // 添加可选的 prompt 参数（用于长文本场景的前文上下文）
                if (prompt.isNotBlank()) {
                    multipartBuilder.addFormDataPart("prompt", prompt)
                }

                val multipart = multipartBuilder.build()

                val request = Request.Builder()
                    .url(ENDPOINT)
                    .tag(
                        ApiLogMeta::class.java,
                        ApiLogRecorder.meta(
                            category = "ASR",
                            vendor = "zhipu",
                            model = model,
                            requestStructure = "multipart fields=model, temperature, stream, file, prompt?"
                        )
                    )
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(multipart)
                    .build()

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
            } finally {
                try {
                    if (!tmp.delete()) {
                        tmp.deleteOnExit()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to delete Zhipu temp wav", t)
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
     * 从 JSON 响应中解析转写文本
     * 响应格式：{ "text": "转写结果" }
     */
    private fun parseTextFromResponse(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            obj.optString("text", "").trim()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse response", t)
            ""
        }
    }
}
