package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Base64
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * https://cloud.tencent.com/document/product/1093/35646
 */
class TencentFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null,
    private val baseUrl: String = "https://asr.tencentcloudapi.com"
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration),
    PcmBatchRecognizer {

    override val maxRecordDurationMillis: Int = 60_000
    override val uploadAudioEncodingSpec: UploadAudioEncodingSpec? = null

    private val http: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(ApiLogInterceptor())
        .build()

    override suspend fun recognize(pcm: ByteArray) {
        val appId = prefs.tencentAppId
        val secretId = prefs.tencentSecretId
        val secretKey = prefs.tencentSecretKey
        if (appId.isBlank() || secretId.isBlank() || secretKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_tencent_key))
            return
        }

        val engineType = prefs.tencentEngineType.ifBlank { "16k_zh" }
        val pcmWithHeader = wavHeader(pcm.size) + pcm
        val base64Audio = Base64.encodeToString(pcmWithHeader, Base64.NO_WRAP)

        val timestamp = System.currentTimeMillis() / 1000
        val date = formatDate(timestamp)

        val payload = buildJsonPayload(engineType, base64Audio, pcmWithHeader.size)
        val host = java.net.URI(baseUrl).host ?: "asr.tencentcloudapi.com"
        val action = "SentenceRecognition"
        val signedHeaders = "content-type;host;x-tc-action"
        val canonicalUri = "/"
        val canonicalQueryString = ""
        val contentType = "application/json; charset=utf-8"

        val canonicalHeaders = "content-type:$contentType\nhost:$host\nx-tc-action:${action.lowercase()}\n"
        val hashedPayload = sha256Hex(payload)
        val canonicalRequest = "POST\n$canonicalUri\n$canonicalQueryString\n$canonicalHeaders\n$signedHeaders\n$hashedPayload"

        val credentialScope = "$date/asr/tc3_request"
        val hashedCanonicalRequest = sha256Hex(canonicalRequest)
        val algorithm = "TC3-HMAC-SHA256"
        val stringToSign = "$algorithm\n$timestamp\n$credentialScope\n$hashedCanonicalRequest"

        val secretDate = hmac256("TC3$secretKey", date)
        val secretService = hmac256(secretDate, "asr")
        val secretSigning = hmac256(secretService, "tc3_request")
        val signature = hmac256Hex(secretSigning, stringToSign)

        val authorization = "$algorithm Credential=$secretId/$credentialScope, SignedHeaders=$signedHeaders, Signature=$signature"

        val request = Request.Builder()
            .url(baseUrl)
            .tag(
                ApiLogMeta::class.java,
                ApiLogRecorder.meta(
                    category = "ASR",
                    vendor = "tencent",
                    model = engineType,
                    requestStructure = "json keys=EngSerViceType, SourceType, VoiceFormat, Data(base64), DataLen"
                )
            )
            .addHeader("Authorization", authorization)
            .addHeader("Content-Type", contentType)
            .addHeader("Host", host)
            .addHeader("X-TC-Action", action)
            .addHeader("X-TC-Version", "2019-06-14")
            .addHeader("X-TC-Timestamp", timestamp.toString())
            .addHeader("X-TC-Region", "ap-shanghai")
            .post(payload.toRequestBody(contentType.toMediaType()))
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
                // TC3 协议：业务错误同样以 HTTP 200 返回，错误位于 Response.Error（Code 为字符串）
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
        } catch (t: Throwable) {
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    /** TC3 错误协议：HTTP 200 也可能携带业务错误，位于 Response.Error（Code/Message 均为字符串） */
    private fun extractApiError(body: String): String? {
        if (body.isBlank()) return null
        return try {
            val err = JSONObject(body).optJSONObject("Response")?.optJSONObject("Error")
                ?: return null
            val code = err.optString("Code").trim()
            val msg = err.optString("Message").trim()
            listOf(code, msg).filter { it.isNotEmpty() }.joinToString(": ").ifBlank { null }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseResult(body: String): String? = try {
        JSONObject(body).optJSONObject("Response")?.optString("Result")?.trim()?.ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    override suspend fun recognizeEncoded(audio: UploadAudioData): Unit = recognize(audio.bytes)

    override suspend fun recognizeFromPcm(pcm: ByteArray): Unit = recognize(pcm)

    private fun buildJsonPayload(
        engineType: String,
        base64Audio: String,
        dataLen: Int
    ): String = """{"EngSerViceType":"$engineType","SourceType":1,"VoiceFormat":"wav","Data":"$base64Audio","DataLen":$dataLen}"""

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
}

internal fun sha256Hex(s: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

internal fun hmac256(key: ByteArray, msg: String): ByteArray {
    val mac = javax.crypto.Mac.getInstance("HmacSHA256")
    mac.init(javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(msg.toByteArray())
}

internal fun hmac256(key: String, msg: String): ByteArray = hmac256(key.toByteArray(), msg)

internal fun hmac256Hex(key: ByteArray, msg: String): String = hmac256(key, msg).joinToString("") { "%02x".format(it) }

internal fun hmac256Hex(key: String, msg: String): String = hmac256Hex(key.toByteArray(), msg)

internal fun formatDate(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return sdf.format(java.util.Date(timestamp * 1000))
}
