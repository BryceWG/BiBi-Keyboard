/** DeepSeek 在线文件识别，复用 ASR 上传编码与文件会话。 */
package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal class DeepSeekFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration),
    PcmBatchRecognizer {

    private companion object {
        const val ENDPOINT = "https://api.deepseek.com/v1/tools/asr"
    }

    override val uploadAudioEncodingSpec: UploadAudioEncodingSpec?
        get() = oggOpusUploadAudioEncodingSpecIfSupported()

    private val http: OkHttpClient = AsrHttpClientProvider.newBuilder()
        .addInterceptor(ApiLogInterceptor())
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.MINUTES)
        .build()

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        if (prefs.deepSeekAsrApiKey.isBlank()) {
            listener.onError(context.getString(R.string.error_missing_deepseek_key))
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
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.fileName, audio.bytes.toRequestBody(audio.mimeType.toMediaType()))
            .build()
        val request = Request.Builder()
            .url(ENDPOINT)
            .tag(
                ApiLogMeta::class.java,
                ApiLogRecorder.meta(
                    category = "ASR",
                    vendor = AsrVendor.DeepSeek.id,
                    requestStructure = "multipart fields=file",
                    redactErrorBody = true
                )
            )
            .header("Authorization", "Bearer ${prefs.deepSeekAsrApiKey.trim()}")
            .post(multipart)
            .build()

        val startedAt = System.nanoTime()
        val response = requestTranscription(request)
        currentCoroutineContext().ensureActive()
        if (response.code !in 200..299) {
            listener.onError(
                context.getString(R.string.error_request_failed_http, response.code, formatHttpDetail(response.message))
            )
            return
        }
        val text = JSONObject(response.body).getString("text").trim()
        if (text.isBlank()) {
            listener.onError(context.getString(R.string.error_asr_empty_result))
            return
        }
        onRequestDuration?.invoke(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt))
        listener.onFinal(text)
    }

    override suspend fun recognizeFromPcm(pcm: ByteArray) = recognize(pcm)

    // 在回调内读完并关闭响应，使取消涵盖上传、等待与响应体读取。
    private suspend fun requestTranscription(request: Request): TranscriptionResponse = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val result = try {
                    response.use {
                        Result.success(TranscriptionResponse(it.code, it.message, it.body.string()))
                    }
                } catch (e: IOException) {
                    Result.failure(e)
                }
                if (continuation.isActive) continuation.resumeWith(result)
            }
        })
    }

    private data class TranscriptionResponse(val code: Int, val message: String, val body: String)
}
