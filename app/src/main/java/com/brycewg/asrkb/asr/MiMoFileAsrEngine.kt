/**
 * MiMo ASR 文件识别引擎实现。
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
 * 使用小米 MiMo /v1/chat/completions 的非流式 ASR 引擎。
 *
 * MiMo ASR 不走 OpenAI /v1/audio/transcriptions 格式，
 * 而是走 Chat Completions 端点，音频以 input_audio content type 内嵌在 messages 中。
 */
class MiMoFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration),
    PcmBatchRecognizer {

    companion object {
        private const val TAG = "MiMoFileAsrEngine"
        private const val MODEL_ASR = "mimo-v2.5-asr"
        private const val MODEL_AUDIO_UNDERSTANDING = "mimo-v2.5"

        /** 默认提示词：用于音频理解模型的系统指令 */
        private const val DEFAULT_SYSTEM_PROMPT = "你是一个语音识别助手。请将用户输入的音频准确转写为文字，直接输出转写结果，不要添加任何额外说明。"
        private const val DEFAULT_AUDIO_UNDERSTANDING_USER_TEXT = "请将这段音频准确转写为文字，直接输出转写结果。"
        private const val AUDIO_UNDERSTANDING_MAX_TOKENS = 4096
    }

    private val http: OkHttpClient = httpClient ?: AsrHttpClientProvider.newBuilder()
        .addInterceptor(ApiLogInterceptor())
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    // MiMo v2.5-asr 与 v2.5 均保持 WAV 上传；v2.5 的压缩 M4A 会被后端拒绝。
    override val uploadAudioEncodingSpec: UploadAudioEncodingSpec? = null

    /** 当前是否使用音频理解模型（mimo-v2.5 而非 mimo-v2.5-asr） */
    private fun isAudioUnderstandingModel(model: String): Boolean = model.equals(MODEL_AUDIO_UNDERSTANDING, ignoreCase = true) ||
        (model.startsWith("mimo-v2.5") && !model.endsWith("-asr"))

    private fun currentModel(): String = prefs.mimoAsrModel.ifBlank { MODEL_ASR }

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        if (prefs.mimoAsrApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_mimo_key))
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        val audio = pcmToWavUploadAudio(pcm)
        recognizeEncoded(audio)
    }

    override suspend fun recognizeEncoded(audio: UploadAudioData) {
        try {
            val apiKey = prefs.mimoAsrApiKey.trim()
            val endpoint = prefs.getEffectiveMimoAsrEndpoint()
            val model = currentModel()
            val language = prefs.mimoAsrLanguage.ifBlank { "auto" }
            val isAuModel = isAudioUnderstandingModel(model)
            val prompt = prefs.mimoAsrPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }
            val body = buildRequestBody(
                audio = audio,
                model = model,
                language = language,
                prompt = prompt,
                isAuModel = isAuModel,
                disableThinking = prefs.mimoAsrDisableThinking
            )
            val reqStructure = if (isAuModel) {
                "json object keys=model, messages(system+user with input_audio+text), max_completion_tokens"
            } else {
                "json object keys=model, messages, asr_options"
            }
            val request = Request.Builder()
                .url(endpoint)
                .tag(
                    ApiLogMeta::class.java,
                    ApiLogRecorder.meta(
                        category = "ASR",
                        vendor = "mimo",
                        model = model,
                        requestStructure = reqStructure
                    )
                )
                .addHeader("api-key", apiKey)
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
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
                val text = parseTextFromResponse(bodyStr, isAuModel)
                if (text.isNotBlank()) {
                    val dt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
                    try {
                        onRequestDuration?.invoke(dt)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to report MiMo request duration", t)
                    }
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

    private fun buildRequestBody(
        audio: UploadAudioData,
        model: String,
        language: String,
        prompt: String = "",
        isAuModel: Boolean = false,
        disableThinking: Boolean = false
    ): String {
        val b64 = Base64.encodeToString(audio.bytes, Base64.NO_WRAP)
        val dataUrl = "data:${audio.mimeType};base64,$b64"

        val inputAudio = JSONObject().apply {
            put("data", dataUrl)
        }
        val audioPart = JSONObject().apply {
            put("type", "input_audio")
            put("input_audio", inputAudio)
        }
        val content = JSONArray().put(audioPart)
        if (isAuModel) {
            content.put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", DEFAULT_AUDIO_UNDERSTANDING_USER_TEXT)
                }
            )
        }
        val userMsg = JSONObject().apply {
            put("role", "user")
            put("content", content)
        }
        val messages = JSONArray().apply {
            if (isAuModel && prompt.isNotBlank()) {
                put(
                    JSONObject().apply {
                        put("role", "system")
                        put("content", prompt)
                    }
                )
            }
            put(userMsg)
        }
        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
        }
        if (isAuModel) {
            body.put("max_completion_tokens", AUDIO_UNDERSTANDING_MAX_TOKENS)
            if (disableThinking) {
                body.put(
                    "thinking",
                    JSONObject().apply {
                        put("type", "disabled")
                    }
                )
            }
        }
        if (!isAuModel) {
            body.put(
                "asr_options",
                JSONObject().apply {
                    put("language", language)
                }
            )
        }
        return body.toString()
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
            Log.e(TAG, "Failed to parse MiMo error hint", t)
            body.take(200).trim()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun parseTextFromResponse(body: String, isAuModel: Boolean = false): String {
        if (body.isBlank()) return ""
        return try {
            val obj = JSONObject(body)
            val choices = obj.optJSONArray("choices")
            if (choices != null && choices.length() > 0) {
                choices.getJSONObject(0)
                    .optJSONObject("message")
                    ?.let { message ->
                        val content = message.optString("content", "").trim()
                        if (content.isNotBlank() || !isAuModel) {
                            content
                        } else {
                            message.optString("reasoning_content", "").trim()
                        }
                    } ?: ""
            } else {
                ""
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to parse MiMo ASR response", t)
            ""
        }
    }
}
