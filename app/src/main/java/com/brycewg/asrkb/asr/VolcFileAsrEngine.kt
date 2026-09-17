/**
 * 火山引擎极速版文件识别接入。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Base64
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 使用火山引擎"recognize/flash" API的非流式ASR引擎。
 * 行为：start()开始录制PCM；stop()完成并上传一个请求；仅调用onFinal。
 */
class VolcFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null,
    modelIdOverride: String? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration),
    PcmBatchRecognizer {

    private val effectiveModelId: String =
        modelIdOverride?.trim()?.takeIf { it.isNotEmpty() } ?: prefs.volcAsrModel

    private val fileResource: String
        get() = VolcAsrModelCatalog.fromIdOrDefault(effectiveModelId).resourceId

    override val uploadAudioEncodingSpec: UploadAudioEncodingSpec?
        get() = oggOpusUploadAudioEncodingSpecIfSupported()

    private val http: OkHttpClient = httpClient ?: AsrHttpClientProvider.newBuilder()
        .addInterceptor(ApiLogInterceptor())
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun recognize(pcm: ByteArray) {
        val audio = encodePcmForUploadIfEnabled(pcm)
        recognizeEncoded(audio)
    }

    override suspend fun recognizeEncoded(audio: UploadAudioData) {
        try {
            val b64 = Base64.encodeToString(audio.bytes, Base64.NO_WRAP)
            val json = buildRequestJson(b64, audio)
            val reqBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(Prefs.DEFAULT_ENDPOINT)
                .tag(
                    ApiLogMeta::class.java,
                    ApiLogRecorder.meta(
                        category = "ASR",
                        vendor = "volcengine",
                        model = fileResource,
                        requestStructure = "json object keys=audio, request, user; audio.data=base64 omitted"
                    )
                )
                .volcHeaders(prefs, fileResource, UUID.randomUUID().toString())
                .post(reqBody)
                .build()
            val t0 = System.nanoTime()
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val msg = resp.header("X-Api-Message") ?: resp.message
                    val detail = formatHttpDetail(msg)
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, resp.code, detail)
                    )
                    return
                }
                val bodyStr = resp.body.string().orEmpty()
                val text = try {
                    val obj = JSONObject(bodyStr)
                    if (obj.has("result")) {
                        obj.getJSONObject("result").optString("text", "")
                    } else {
                        ""
                    }
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
        } catch (t: Throwable) {
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    // 供“推送 PCM 适配器”调用，直接复用现有实现
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

    /**
     * 构建火山引擎 API 请求体
     */
    private fun buildRequestJson(base64Audio: String, audioData: UploadAudioData): String {
        val user = JSONObject().apply {
            put("uid", prefs.appKey)
        }
        val audio = JSONObject().apply {
            put("data", base64Audio)
            putVolcAudioFormat(audioData)
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

    private fun JSONObject.putVolcAudioFormat(audio: UploadAudioData) {
        put("format", audio.format)
        put("rate", audio.sampleRate)
        put("bits", 16)
        put("channel", audio.channels)
        if (audio.container == UploadAudioContainer.OGG_OPUS) {
            put("codec", "opus")
        }
    }
}
