/**
 * Cohere Transcribe 文件识别引擎。
 *
 * 归属模块：asr
 */
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

internal class CohereFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null,
    httpClient: OkHttpClient? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration),
    PcmBatchRecognizer {

    companion object {
        private const val TAG = "CohereFileAsrEngine"
        internal const val ENDPOINT = "https://api.cohere.com/v2/audio/transcriptions"
    }

    // Cohere documents OGG uploads; use the existing OGG Opus encoder on Android 10+.
    override val uploadAudioEncodingSpec: UploadAudioEncodingSpec?
        get() = cohereUploadAudioEncodingSpecIfSupported()

    private val http: OkHttpClient = httpClient ?: AsrHttpClientProvider.newBuilder()
        .addInterceptor(ApiLogInterceptor())
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        if (prefs.cohereApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_cohere_key))
            return false
        }
        if (prefs.cohereAsrModel.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_cohere_model))
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        val spec = if (prefs.uploadAudioCompressionEnabled) uploadAudioEncodingSpec else null
        val audio = if (spec != null) {
            encodePcmForUpload(context, pcm, sampleRate, spec)
        } else {
            pcmToWavUploadAudio(pcm)
        }
        recognizeEncoded(audio)
    }

    override suspend fun recognizeEncoded(audio: UploadAudioData) {
        val tmp = File.createTempFile(
            "asr_cohere_",
            ".${audio.container.extension}",
            context.cacheDir
        )
        try {
            FileOutputStream(tmp).use { it.write(audio.bytes) }
            val model = prefs.cohereAsrModel.trim()
            val language = normalizeCohereLanguageForModel(model, prefs.cohereAsrLanguage)
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("language", language)
                .addFormDataPart(
                    "file",
                    audio.fileName,
                    tmp.asRequestBody(audio.mimeType.toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url(ENDPOINT)
                .tag(
                    ApiLogMeta::class.java,
                    ApiLogRecorder.meta(
                        category = "ASR",
                        vendor = "cohere",
                        model = model,
                        requestStructure = "multipart fields=model, language, file"
                    )
                )
                .addHeader("Authorization", "Bearer ${prefs.cohereApiKey.trim()}")
                .post(multipart)
                .build()

            val startedAt = System.nanoTime()
            http.newCall(request).execute().use { response ->
                val body = response.body.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = formatHttpDetail(response.message, extractCohereError(body))
                    listener.onError(
                        context.getString(R.string.error_request_failed_http, response.code, detail)
                    )
                    return
                }
                val text = parseCohereTranscription(body)
                if (text.isBlank()) {
                    listener.onError(context.getString(R.string.error_asr_empty_result))
                    return
                }
                val duration = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
                try {
                    onRequestDuration?.invoke(duration)
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to report Cohere request duration", t)
                }
                listener.onFinal(text)
            }
        } catch (t: Throwable) {
            listener.onError(
                context.getString(R.string.error_recognize_failed_with_reason, t.message.orEmpty())
            )
        } finally {
            try {
                if (tmp.exists() && !tmp.delete()) {
                    Log.w(TAG, "Failed to delete Cohere temporary upload audio")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to delete Cohere temporary upload audio", t)
            }
        }
    }

    override suspend fun recognizeFromPcm(pcm: ByteArray) = recognize(pcm)
}

internal fun parseCohereTranscription(body: String): String = runCatching {
    JSONObject(body).optString("text")
}.getOrDefault("")

internal fun cohereUploadAudioEncodingSpecIfSupported(): UploadAudioEncodingSpec? = oggOpusUploadAudioEncodingSpecIfSupported()

internal fun extractCohereError(body: String): String {
    if (body.isBlank()) return ""
    return runCatching {
        val json = JSONObject(body)
        when (val error = json.opt("error")) {
            is JSONObject -> error.optString("message")
            is String -> error
            else -> json.optString("message")
        }.trim().ifBlank { body.take(200).trim() }
    }.getOrElse { body.take(200).trim() }
}
