/**
 * 火山引擎标准版文件识别接入。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Base64
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 火山引擎 ASR 2.0 录音文件识别（标准版，submit/query）引擎。
 * - stop() 后先提交任务，再轮询查询结果，最终仅回调 onFinal。
 */
class VolcStandardFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null,
    modelIdOverride: String? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration),
    PcmBatchRecognizer {

    companion object {
        private const val TAG = "VolcStandardFileAsr"
        private const val SUBMIT_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit"
        private const val QUERY_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/query"
        private const val MAX_POLL_ATTEMPTS = 120
        private const val POLL_INTERVAL_MS = 1000L
    }

    override val uploadAudioEncodingSpec: UploadAudioEncodingSpec?
        get() = oggOpusUploadAudioEncodingSpecIfSupported()

    private val http: OkHttpClient = httpClient ?: AsrHttpClientProvider.newBuilder()
        .addInterceptor(ApiLogInterceptor())
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    private val resourceId: String
        get() = VolcAsrModelCatalog.fromIdOrDefault(effectiveModelId).resourceId

    private val effectiveModelId: String =
        modelIdOverride?.trim()?.takeIf { it.isNotEmpty() } ?: prefs.volcAsrModel

    private sealed interface QueryResult {
        data class Success(val text: String) : QueryResult
        data class Failed(val message: String) : QueryResult
        object Empty : QueryResult
        object Timeout : QueryResult
    }

    override suspend fun recognize(pcm: ByteArray) {
        val audio = encodePcmForUploadIfEnabled(pcm)
        recognizeEncoded(audio)
    }

    override suspend fun recognizeEncoded(audio: UploadAudioData) {
        val requestId = UUID.randomUUID().toString()
        val t0 = System.nanoTime()
        try {
            val b64 = Base64.encodeToString(audio.bytes, Base64.NO_WRAP)
            val submitJson = buildSubmitRequestJson(b64, audio)
            val submitReq = Request.Builder()
                .url(SUBMIT_URL)
                .tag(
                    ApiLogMeta::class.java,
                    ApiLogRecorder.meta(
                        category = "ASR",
                        vendor = "volcengine",
                        model = resourceId,
                        requestStructure = "json object keys=audio, request, user; audio.data=base64 omitted"
                    )
                )
                .volcHeaders(prefs, resourceId, requestId)
                .post(submitJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(submitReq).execute().use { resp ->
                val status = parseStatusCode(resp)
                if (!resp.isSuccessful) {
                    val msg = resp.header("X-Api-Message") ?: resp.message
                    val detail = formatHttpDetail(msg, "status=${status ?: "unknown"}")
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, resp.code, detail)
                    )
                    return
                }
                if (status != 20000000L) {
                    val msg = resp.header("X-Api-Message") ?: resp.message
                    val detail = formatHttpDetail(msg, "status=${status ?: "unknown"}")
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, resp.code, detail)
                    )
                    return
                }
            }

            when (val result = pollResult(requestId)) {
                is QueryResult.Success -> {
                    val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                    try {
                        onRequestDuration?.invoke(dt)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to report request duration", t)
                    }
                    listener.onFinal(result.text)
                }
                is QueryResult.Empty -> {
                    listener.onError(context.getString(R.string.error_asr_empty_result))
                }
                is QueryResult.Failed -> {
                    listener.onError(result.message)
                }
                QueryResult.Timeout -> {
                    listener.onError(context.getString(R.string.error_asr_timeout))
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to recognize with Volc standard file API", t)
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    override suspend fun recognizeFromPcm(pcm: ByteArray) {
        recognize(pcm)
    }

    private fun encodePcmForUploadIfEnabled(pcm: ByteArray): UploadAudioData {
        val spec = if (prefs.uploadAudioCompressionEnabled) uploadAudioEncodingSpec else null
        return if (spec != null) {
            encodePcmForUpload(context, pcm, sampleRate, spec)
        } else {
            pcmToWavUploadAudio(pcm)
        }
    }

    private fun buildSubmitRequestJson(base64Audio: String, audioData: UploadAudioData): String {
        val user = JSONObject().apply { put("uid", prefs.appKey) }
        val audio = JSONObject().apply {
            put("data", base64Audio)
            put("format", audioData.format)
            if (audioData.container == UploadAudioContainer.OGG_OPUS) {
                put("codec", "opus")
            }
            put("rate", audioData.sampleRate)
            put("bits", 16)
            put("channel", audioData.channels)
            val lang = prefs.volcLanguage
            if (lang.isNotBlank()) put("language", lang)
        }
        val request = JSONObject().apply {
            put("model_name", "bigmodel")
            put("enable_itn", true)
            put("enable_punc", true)
            put("enable_ddc", prefs.volcDdcEnabled)
        }
        return JSONObject().apply {
            put("user", user)
            put("audio", audio)
            put("request", request)
        }.toString()
    }

    private suspend fun pollResult(requestId: String): QueryResult {
        repeat(MAX_POLL_ATTEMPTS) {
            val queryReq = Request.Builder()
                .url(QUERY_URL)
                .tag(
                    ApiLogMeta::class.java,
                    ApiLogRecorder.meta(
                        category = "ASR",
                        vendor = "volcengine",
                        model = resourceId,
                        requestStructure = "json object keys=empty"
                    )
                )
                .volcHeaders(prefs, resourceId, requestId)
                .post("{}".toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            http.newCall(queryReq).execute().use { resp ->
                val status = parseStatusCode(resp)
                if (!resp.isSuccessful) {
                    val msg = resp.header("X-Api-Message") ?: resp.message
                    val detail = formatHttpDetail(msg, "status=${status ?: "unknown"}")
                    return QueryResult.Failed(
                        context.getString(R.string.error_request_failed_http, resp.code, detail)
                    )
                }
                val message = resp.header("X-Api-Message") ?: resp.message
                val bodyStr = resp.body.string().orEmpty()
                when (status) {
                    20000000L -> {
                        val text = extractText(bodyStr)
                        return if (text.isNotBlank()) {
                            QueryResult.Success(text)
                        } else {
                            QueryResult.Empty
                        }
                    }
                    20000001L, 20000002L -> { /* 正在处理或排队，继续轮询 */ }
                    20000003L -> {
                        return QueryResult.Empty
                    }
                    else -> {
                        val detail = formatHttpDetail(message, "status=${status ?: "unknown"}")
                        return QueryResult.Failed(
                            context.getString(R.string.error_request_failed_http, resp.code, detail)
                        )
                    }
                }
            }
            delay(POLL_INTERVAL_MS)
        }
        Log.w(TAG, "Polling timeout after $MAX_POLL_ATTEMPTS attempts for requestId=$requestId")
        return QueryResult.Timeout
    }

    private fun parseStatusCode(resp: okhttp3.Response): Long? {
        val header = resp.header("X-Api-Status-Code") ?: return null
        return header.toLongOrNull()
    }

    private fun extractText(bodyStr: String): String = try {
        val obj = JSONObject(bodyStr)
        if (obj.has("result")) {
            obj.getJSONObject("result").optString("text", "")
        } else {
            ""
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to parse standard file ASR result", t)
        ""
    }
}
