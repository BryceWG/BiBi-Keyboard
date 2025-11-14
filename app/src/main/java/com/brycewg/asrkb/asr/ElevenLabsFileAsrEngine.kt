package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 使用 ElevenLabs Speech-to-Text 多部分 API 的非流式 ASR 引擎。
 */
class ElevenLabsFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration), PcmBatchRecognizer {

    companion object {
        private const val TAG = "ElevenLabsFileAsrEngine"
        private const val MODEL_ID = "scribe_v1"
    }

    // ElevenLabs：未明确限制，本地限制为 20 分钟
    override val maxRecordDurationMillis: Int = 20 * 60 * 1000

    private val http: OkHttpClient = httpClient ?: OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        if (prefs.elevenApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_eleven_key))
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        try {
            val wav = pcmToWav(pcm)
            val tmp = File.createTempFile("asr_el_", ".wav", context.cacheDir)
            FileOutputStream(tmp).use { it.write(wav) }

            val apiKey = prefs.elevenApiKey
            val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "audio.wav",
                    tmp.asRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model_id", MODEL_ID)
                .addFormDataPart("tag_audio_events", "false")
                .addFormDataPart("num_speakers", "1")
            val lang = prefs.elevenLanguageCode.trim()
            if (lang.isNotEmpty()) {
                multipartBuilder.addFormDataPart("language_code", lang)
            }

            val request = Request.Builder()
                .url("https://api.elevenlabs.io/v1/speech-to-text")
                .addHeader("xi-api-key", apiKey)
                .post(multipartBuilder.build())
                .build()

            val t0 = System.nanoTime()
            val resp = http.newCall(request).execute()
            resp.use { r ->
                val bodyStr = r.body?.string().orEmpty()
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
                    try { onRequestDuration?.invoke(dt) } catch (_: Throwable) {}
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

    override suspend fun recognizeFromPcm(pcm: ByteArray) { recognize(pcm) }

    /**
     * 从响应体中提取错误提示信息
     */
    private fun extractErrorHint(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            when {
                obj.has("detail") -> obj.optString("detail").trim()
                obj.has("message") -> obj.optString("message").trim()
                obj.has("error") -> obj.optString("error").trim()
                else -> body.take(200).trim()
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Trying to parse error as array", t)
            try {
                val arr = org.json.JSONArray(body)
                val msgs = mutableListOf<String>()
                for (i in 0 until minOf(arr.length(), 5)) {
                    val e = arr.optJSONObject(i) ?: continue
                    val loc = e.opt("loc")
                    val msg = e.optString("msg").ifBlank { e.optString("message") }
                    if (msg.isNotBlank()) {
                        val locStr = when (loc) {
                            is org.json.JSONArray -> (0 until loc.length()).joinToString(".") { loc.optString(it) }
                            is String -> loc
                            else -> ""
                        }
                        msgs.add(if (locStr.isNotBlank()) "$locStr: $msg" else msg)
                    }
                }
                if (msgs.isNotEmpty()) msgs.joinToString("; ") else body.take(200).trim()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to parse error hint", e)
                body.take(200).trim()
            }
        }
    }

    /**
     * 从响应体中解析转写文本
     */
    private fun parseTextFromResponse(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            when {
                obj.has("text") -> obj.optString("text", "")
                obj.has("transcripts") -> {
                    val arr = obj.optJSONArray("transcripts")
                    if (arr != null && arr.length() > 0) {
                        val list = mutableListOf<String>()
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val t = item.optString("text").trim()
                            if (t.isNotEmpty()) list.add(t)
                        }
                        list.joinToString("\n").trim()
                    } else ""
                }
                else -> ""
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse text from response", t)
            ""
        }
    }
}
