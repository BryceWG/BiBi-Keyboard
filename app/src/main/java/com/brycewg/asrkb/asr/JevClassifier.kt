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
    val failureReason: PromptSelectionFailReason? = null,
    /** Probability of the finally chosen key in `answers.selected_prompt.probabilities`, if present. */
    val matchProbability: Double? = null
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
                                model = modelFor(prefs, provider),
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
                        val parsed = if (it.isSuccessful && responseJson != null) {
                            parseChoiceAndProbability(responseJson)
                        } else {
                            if (!it.isSuccessful) {
                                Log.w(TAG, "Jev request failed with HTTP ${it.code}")
                            }
                            null
                        }
                        val choice = parsed?.first
                        val matchProbability = parsed?.second
                        continuation.resumeWith(
                            Result.success(
                                JevSelectionResult(
                                    choice = choice,
                                    vendorId = provider.id,
                                    model = responseJson?.optString("model")
                                        ?.takeIf(String::isNotBlank)
                                        ?: modelFor(prefs, provider),
                                    elapsedMs = elapsedMs(startedAt),
                                    requestSent = true,
                                    failureReason = when {
                                        !it.isSuccessful -> PromptSelectionFailReason.REQUEST_FAILED
                                        choice == null -> PromptSelectionFailReason.INVALID_OUTPUT
                                        else -> null
                                    },
                                    matchProbability = matchProbability
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
            JevClassifierProvider.OPENROUTER,
            JevClassifierProvider.CUSTOM -> JSONObject()
                .put("model", modelFor(prefs, provider))
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
                "https://api.cloudflare.com/client/v4/accounts/${prefs.jevCloudflareAccountId}/ai/run/typesafe/jev" to
                    prefs.jevCloudflareApiKey
            JevClassifierProvider.CUSTOM -> prefs.jevCustomEndpoint.trim() to prefs.jevCustomApiKey
        }
        val payloadModel = modelFor(prefs, provider)
        val requestStructure = when (provider) {
            JevClassifierProvider.CLOUDFLARE ->
                "json object keys=model,input; input keys=state,questions"
            JevClassifierProvider.TYPESAFE,
            JevClassifierProvider.OPENROUTER,
            JevClassifierProvider.CUSTOM ->
                "json object keys=model, state, questions"
        }
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .tag(
                ApiLogMeta::class.java,
                ApiLogRecorder.meta(
                    category = "LLM",
                    vendor = LlmVendor.TYPESAFE.id,
                    model = payloadModel,
                    requestStructure = "$requestStructure; channel=${provider.id}"
                )
            )
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
    }

    private fun modelFor(prefs: Prefs, provider: JevClassifierProvider): String = when (provider) {
        JevClassifierProvider.TYPESAFE -> JEV_MODEL_ID
        JevClassifierProvider.OPENROUTER -> "~typesafe/jev-latest"
        JevClassifierProvider.CLOUDFLARE -> "typesafe/jev"
        JevClassifierProvider.CUSTOM -> prefs.jevCustomModel.trim()
    }

    private fun elapsedMs(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedAt).coerceAtLeast(0L))

    /**
     * Reads `answers.selected_prompt.choice` and, when present, the probability for that same key.
     * Does not alter request payload or selection decision.
     */
    private fun parseChoiceAndProbability(responseJson: JSONObject): Pair<String, Double?>? {
        return runCatching {
            val answer = responseJson
                .getJSONObject("answers")
                .getJSONObject(QUESTION_ID)
            val choice = answer.getString("choice").trim()
            if (choice.isEmpty()) return@runCatching null
            val probability = answer.optJSONObject("probabilities")
                ?.takeIf { it.has(choice) }
                ?.optDouble(choice, Double.NaN)
                ?.takeUnless { it.isNaN() }
            choice to probability
        }.getOrNull()
    }
}
