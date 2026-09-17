/**
 * StepAudio 在线文件识别引擎实现。
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
import org.json.JSONArray
import org.json.JSONObject

/**
 * 使用 StepFun StepAudio ASR SSE API 的非流式录音识别引擎。
 *
 * API 会以 SSE 增量返回文本；本引擎聚合增量后向上层提交一次最终结果。
 */
class StepAudioFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration),
    PcmBatchRecognizer {

    companion object {
        private const val TAG = "StepAudioFileAsrEngine"
    }

    override val uploadAudioEncodingSpec: UploadAudioEncodingSpec?
        get() = oggOpusUploadAudioEncodingSpecIfSupported()

    private val http: OkHttpClient = httpClient ?: AsrHttpClientProvider.newBuilder()
        .addInterceptor(ApiLogInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        if (prefs.stepAudioApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_stepaudio_key))
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        val spec = if (prefs.uploadAudioCompressionEnabled) uploadAudioEncodingSpec else null
        if (spec != null) {
            recognizeEncoded(encodePcmForUpload(context, pcm, sampleRate, spec))
            return
        }
        try {
            val body = buildRequestBody(pcm)
            requestRecognition(body)
        } catch (t: Throwable) {
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    override suspend fun recognizeEncoded(audio: UploadAudioData) {
        try {
            requestRecognition(buildRequestBody(audio))
        } catch (t: Throwable) {
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            )
        }
    }

    override suspend fun recognizeFromPcm(pcm: ByteArray) {
        recognize(pcm)
    }

    private fun requestRecognition(body: String) {
        val request = Request.Builder()
            .url(prefs.getEffectiveStepAudioAsrEndpoint())
            .tag(
                ApiLogMeta::class.java,
                ApiLogRecorder.meta(
                    category = "ASR",
                    vendor = "stepaudio",
                    requestStructure = "json object keys=transcription, format"
                )
            )
            .addHeader("Authorization", "Bearer ${prefs.stepAudioApiKey}")
            .addHeader("Accept", "text/event-stream")
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
            val text = parseSseText(bodyStr)
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
    }

    private fun buildRequestBody(pcm: ByteArray): String {
        val transcription = JSONObject().apply {
            put("model", prefs.stepAudioModel.ifBlank { Prefs.DEFAULT_STEPAUDIO_ASR_MODEL })
            val language = prefs.stepAudioLanguage.trim()
            if (language.isNotEmpty()) {
                put("language", language)
            }
            put("enable_itn", prefs.stepAudioUseItn)
        }
        val format = JSONObject().apply {
            put("type", "pcm")
            put("codec", "pcm_s16le")
            put("rate", sampleRate)
            put("bits", 16)
            put("channel", 1)
        }
        val input = JSONObject().apply {
            put("transcription", transcription)
            put("format", format)
        }
        val audio = JSONObject().apply {
            put("data", Base64.encodeToString(pcm, Base64.NO_WRAP))
            put("input", input)
        }
        return JSONObject().put("audio", audio).toString()
    }

    private fun buildRequestBody(audioData: UploadAudioData): String {
        val transcription = JSONObject().apply {
            put("model", prefs.stepAudioModel.ifBlank { Prefs.DEFAULT_STEPAUDIO_ASR_MODEL })
            val language = prefs.stepAudioLanguage.trim()
            if (language.isNotEmpty()) {
                put("language", language)
            }
            put("enable_itn", prefs.stepAudioUseItn)
        }
        val format = JSONObject().apply {
            put("type", audioData.format)
            if (audioData.container == UploadAudioContainer.OGG_OPUS) {
                put("codec", "opus")
            }
            put("rate", audioData.sampleRate)
            put("channel", audioData.channels)
        }
        val input = JSONObject().apply {
            put("transcription", transcription)
            put("format", format)
        }
        val audio = JSONObject().apply {
            put("data", Base64.encodeToString(audioData.bytes, Base64.NO_WRAP))
            put("input", input)
        }
        return JSONObject().put("audio", audio).toString()
    }

    private fun parseSseText(body: String): String {
        if (body.isBlank()) return ""
        val out = StringBuilder()
        var currentEvent = ""
        val dataLines = mutableListOf<String>()

        fun flush() {
            if (dataLines.isEmpty()) return
            val data = dataLines.joinToString("\n").trim()
            if (data.isNotEmpty() && data != "[DONE]") {
                appendSseDataText(out, currentEvent, data)
            }
            currentEvent = ""
            dataLines.clear()
        }

        body.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.isEmpty() -> flush()
                line.startsWith("event:") -> currentEvent = line.removePrefix("event:").trim()
                line.startsWith("data:") -> dataLines.add(line.removePrefix("data:").trim())
            }
        }
        flush()
        return out.toString().trim()
    }

    private fun appendSseDataText(out: StringBuilder, event: String, data: String) {
        try {
            val obj = JSONObject(data)
            val type = obj.optString("type", event)
            val delta = extractText(obj, "delta")
            val text = extractText(obj, "text")
            when {
                event == "transcript.text.delta" || type == "transcript.text.delta" -> {
                    out.append(delta.ifBlank { text })
                }
                event == "transcript.text.done" || type == "transcript.text.done" -> {
                    if (out.isBlank() && text.isNotBlank()) {
                        out.append(text)
                    }
                }
                delta.isNotBlank() -> out.append(delta)
                out.isBlank() && text.isNotBlank() -> out.append(text)
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Failed to parse SSE data JSON", t)
            if (event == "transcript.text.delta") {
                out.append(data)
            }
        }
    }

    private fun extractText(obj: JSONObject, key: String): String {
        val direct = obj.optString(key, "")
        if (direct.isNotBlank()) return direct
        val nested = obj.optJSONObject(key)
        if (nested != null) {
            val text = nested.optString("text", "")
            if (text.isNotBlank()) return text
            val content = nested.optString("content", "")
            if (content.isNotBlank()) return content
        }
        return ""
    }

    private fun extractErrorHint(body: String): String {
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            when {
                obj.has("error") -> extractText(obj, "error").ifBlank {
                    obj.optJSONObject("error")?.optString("message", "").orEmpty()
                }
                obj.has("message") -> obj.optString("message").trim()
                else -> body.take(200).trim()
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Trying to parse StepAudio error as array", t)
            try {
                val arr = JSONArray(body)
                if (arr.length() > 0) {
                    arr.optJSONObject(0)?.optString("message", "").orEmpty().ifBlank {
                        body.take(200).trim()
                    }
                } else {
                    body.take(200).trim()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to parse StepAudio error hint", e)
                body.take(200).trim()
            }
        }
    }
}
