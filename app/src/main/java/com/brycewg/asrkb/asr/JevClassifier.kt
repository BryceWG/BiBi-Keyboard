package com.brycewg.asrkb.asr

import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.JEV_MODEL_ID
import com.brycewg.asrkb.store.JevClassifierProvider
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptSelectionFailReason
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal data class JevSelectionResult(
    val choice: String?,
    val vendorId: String,
    val model: String,
    val elapsedMs: Long,
    val requestSent: Boolean,
    val failureReason: PromptSelectionFailReason? = null
)

/** Native System One client for Jev's decision-only choice API. */
internal class JevClassifier(
    private val client: OkHttpClient = LlmPostProcessor.defaultSharedHttpClient().newBuilder()
        .callTimeout(PromptSelector.TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
) {
    companion object {
        private const val TAG = "JevClassifier"
        private const val QUESTION_ID = "selected_prompt"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    @Volatile
    private var activeCall: Call? = null

    suspend fun select(
        prefs: Prefs,
        provider: JevClassifierProvider,
        candidates: List<com.brycewg.asrkb.store.PromptSelectionCandidate>,
        asrText: String
    ): JevSelectionResult {
        val startedAt = System.nanoTime()
        val request = buildRequest(prefs, provider, candidates, asrText)
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            activeCall = call
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    activeCall = null
                    if (!continuation.isActive) return
                    val reason = if (e is InterruptedIOException) {
                        PromptSelectionFailReason.TIMEOUT
                    } else if (call.isCanceled()) {
                        PromptSelectionFailReason.CANCELLED
                    } else {
                        PromptSelectionFailReason.REQUEST_FAILED
                    }
                    continuation.resumeWith(
                        Result.success(
                            JevSelectionResult(
                                choice = null,
                                vendorId = provider.id,
                                model = JEV_MODEL_ID,
                                elapsedMs = elapsedMs(startedAt),
                                requestSent = true,
                                failureReason = reason
                            )
                        )
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    activeCall = null
                    if (!continuation.isActive) {
                        response.close()
                        return
                    }
                    response.use {
                        val body = it.body?.string().orEmpty()
                        val responseJson = if (it.isSuccessful) {
                            runCatching { JSONObject(body) }.getOrNull()
                        } else {
                            null
                        }
                        val choice = if (it.isSuccessful) {
                            runCatching {
                                responseJson!!
                                    .getJSONObject("answers")
                                    .getJSONObject(QUESTION_ID)
                                    .getString("choice")
                            }.getOrNull()
                        } else {
                            Log.w(TAG, "Jev request failed with HTTP ${it.code}")
                            null
                        }
                        continuation.resumeWith(
                            Result.success(
                                JevSelectionResult(
                                    choice = choice,
                                    vendorId = provider.id,
                                    model = responseJson?.optString("model")
                                        ?.takeIf(String::isNotBlank)
                                        ?: JEV_MODEL_ID,
                                    elapsedMs = elapsedMs(startedAt),
                                    requestSent = true,
                                    failureReason = when {
                                        !it.isSuccessful -> PromptSelectionFailReason.REQUEST_FAILED
                                        choice == null -> PromptSelectionFailReason.INVALID_OUTPUT
                                        else -> null
                                    }
                                )
                            )
                        )
                    }
                }
            })
        }
    }

    fun cancel() {
        activeCall?.cancel()
    }

    private fun buildRequest(
        prefs: Prefs,
        provider: JevClassifierProvider,
        candidates: List<com.brycewg.asrkb.store.PromptSelectionCandidate>,
        asrText: String
    ): Request {
        val criteria = JSONObject().apply {
            candidates.forEachIndexed { index, candidate ->
                put("p${index + 1}", candidate.skill.trim())
            }
        }
        val questions = JSONObject().put(
            QUESTION_ID,
            JSONObject()
                .put("type", "choice")
                .put("instructions", prefs.getLocalizedString(R.string.prompt_selection_jev_instructions))
                .put("criteria", criteria)
        )
        val state = asrText
        val payload = when (provider) {
            JevClassifierProvider.TYPESAFE,
            JevClassifierProvider.OPENROUTER -> JSONObject()
                .put("model", if (provider == JevClassifierProvider.OPENROUTER) "~typesafe/jev-latest" else JEV_MODEL_ID)
                .put("state", state)
                .put("questions", questions)

            JevClassifierProvider.CLOUDFLARE -> JSONObject()
                .put("model", "typesafe/jev")
                .put(
                    "input",
                    JSONObject()
                        .put("state", state)
                        .put("questions", questions)
                )
        }
        val (url, apiKey) = when (provider) {
            JevClassifierProvider.TYPESAFE ->
                "https://api.typesafe.ai/v1/systemone" to prefs.jevTypesafeApiKey
            JevClassifierProvider.OPENROUTER ->
                "https://openrouter.ai/api/alpha/decisions" to prefs.jevOpenRouterApiKey
            JevClassifierProvider.CLOUDFLARE ->
                "https://api.cloudflare.com/client/v4/accounts/${prefs.jevCloudflareAccountId}/ai/run" to
                    prefs.jevCloudflareApiKey
        }
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun elapsedMs(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedAt).coerceAtLeast(0L))
}
