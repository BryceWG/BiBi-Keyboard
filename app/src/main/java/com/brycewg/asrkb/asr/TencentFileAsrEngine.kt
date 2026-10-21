/**
 * 腾讯云 ASR 文件识别引擎（录音文件识别极速版，HMAC-SHA1 Authorization 签名）。
 *
 * 归属模块：asr
 *
 * 文档：https://cloud.tencent.com/document/product/1093/52097
 */
package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal class TencentFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null,
    private val baseUrl: String = "https://asr.cloud.tencent.com"
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration),
    PcmBatchRecognizer {

    override val uploadAudioEncodingSpec: UploadAudioEncodingSpec? = null

    private val http: OkHttpClient = httpClient ?: AsrHttpClientProvider.newBuilder()
        .addInterceptor(ApiLogInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    override suspend fun recognize(pcm: ByteArray) {
        val appId = prefs.tencentAppId
        val secretId = prefs.tencentSecretId
        val secretKey = prefs.tencentSecretKey
        if (appId.isBlank() || secretId.isBlank() || secretKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_tencent_key))
            return
        }

        val engineType = prefs.tencentSelection.engineId
        val wavBytes = wavHeader(pcm.size) + pcm
        val timestamp = System.currentTimeMillis() / 1000
        val host = URI(baseUrl).host ?: TENCENT_FLASH_HOST
        val queryString = buildQueryString(secretId, engineType, timestamp)
        // 极速版签名原文：HTTP 方法 + 请求域名与路径 + 字典序查询串，Authorization 不做 urlencode。
        val signaturePlain = "POST$host/asr/flash/v1/$appId?$queryString"
        val authorization = TencentStreamAsrEngine.hmacSha1Base64(signaturePlain, secretKey)

        val request = Request.Builder()
            .url("$baseUrl/asr/flash/v1/$appId?$queryString")
            .tag(
                ApiLogMeta::class.java,
                ApiLogRecorder.meta(
                    category = "ASR",
                    vendor = "tencent",
                    model = engineType,
                    requestStructure = "POST asr/flash/v1 binary wav body, query=engine_type,voice_format,timestamp,secretid"
                )
            )
            .addHeader("Authorization", authorization)
            .addHeader("Content-Type", CONTENT_TYPE_OCTET_STREAM)
            .post(wavBytes.toRequestBody(CONTENT_TYPE_OCTET_STREAM.toMediaType()))
            .build()

        val t0 = System.nanoTime()
        try {
            http.newCall(request).execute().use { r ->
                val bodyStr = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    val detail = formatHttpDetail(r.message, extractApiError(bodyStr) ?: bodyStr.take(200).trim())
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, r.code, detail)
                    )
                    return
                }
                // 极速版协议：业务错误同样以 HTTP 200 返回，错误码位于顶层 code 字段。
                val apiError = extractApiError(bodyStr)
                if (apiError != null) {
                    listener.onError(
                        context.getString(R.string.error_recognize_failed_with_reason, apiError)
                    )
                    return
                }
                val text = parseResult(bodyStr)
                if (!text.isNullOrBlank()) {
                    val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                    try {
                        onRequestDuration?.invoke(dt)
                    } catch (_: Throwable) {}
                    listener.onFinal(text)
                } else {
                    listener.onError(context.getString(R.string.error_asr_empty_result))
                }
            }
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    private fun buildQueryString(secretId: String, engineType: String, timestamp: Long): String {
        val params = TencentStreamAsrEngine.sortedMapOf(
            "engine_type" to engineType,
            "secretid" to secretId,
            "timestamp" to timestamp.toString(),
            "voice_format" to "wav"
        )
        return params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    /** 极速版错误协议：HTTP 200 时业务错误位于顶层 code/message，非 0 即失败。 */
    private fun extractApiError(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val obj = JSONObject(body)
            val code = obj.optInt("code", 0)
            if (code == 0) {
                val err = obj.optJSONObject("Response")?.optJSONObject("Error") ?: return null
                val errCode = err.optString("Code").trim()
                val errMsg = err.optString("Message").trim()
                listOf(errCode, errMsg).filter { it.isNotEmpty() }.joinToString(": ").ifBlank { null }
            } else {
                listOf(code.toString(), obj.optString("message").trim())
                    .filter { it.isNotEmpty() }
                    .joinToString(": ")
                    .ifBlank { null }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseResult(body: String): String? = try {
        JSONObject(body)
            .optJSONArray("flash_result")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.trim()
            ?.ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    override suspend fun recognizeEncoded(audio: UploadAudioData): Unit = recognize(audio.bytes)

    override suspend fun recognizeFromPcm(pcm: ByteArray): Unit = recognize(pcm)

    private fun wavHeader(dataLen: Int): ByteArray {
        val sampleRate = 16000
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val totalDataLen = dataLen + 44 - 8
        val buf = ByteArrayOutputStream()
        buf.write("RIFF".toByteArray())
        buf.write(intToBytes(totalDataLen))
        buf.write("WAVE".toByteArray())
        buf.write("fmt ".toByteArray())
        buf.write(intToBytes(16))
        buf.write(shortToBytes(1))
        buf.write(shortToBytes(channels))
        buf.write(intToBytes(sampleRate))
        buf.write(intToBytes(byteRate))
        buf.write(shortToBytes(blockAlign))
        buf.write(shortToBytes(bitsPerSample))
        buf.write("data".toByteArray())
        buf.write(intToBytes(dataLen))
        return buf.toByteArray()
    }

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte(),
        ((value shr 16) and 0xff).toByte(),
        ((value shr 24) and 0xff).toByte()
    )

    private fun shortToBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value shr 8) and 0xff).toByte()
    )

    private companion object {
        private const val TENCENT_FLASH_HOST = "asr.cloud.tencent.com"
        private const val CONTENT_TYPE_OCTET_STREAM = "application/octet-stream"
    }
}
