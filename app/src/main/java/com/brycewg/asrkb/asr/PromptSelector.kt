/**
 * 自动选择提示词：用一次分类请求从候选预设中选出最匹配当前场景的一项。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.JevClassifierProvider
import com.brycewg.asrkb.store.LlmModelConfigResolver
import com.brycewg.asrkb.store.LlmModelResolution
import com.brycewg.asrkb.store.LlmModelUnavailableReason
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptSelectionCandidate
import com.brycewg.asrkb.store.PromptSelectionFailReason
import com.brycewg.asrkb.store.PromptSelectionStatus
import com.brycewg.asrkb.store.PromptSelectorModelRef
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/**
 * 分类结果。
 *
 * [candidate] 仅在选择成功时非空；失败时调用方必须继续使用当前激活预设。
 */
internal data class PromptSelectionOutcome(
    val status: PromptSelectionStatus,
    val candidate: PromptSelectionCandidate?
)

/** Extra session timeout needed when automatic selection can run before polishing. */
internal fun promptSelectionSessionSlackMs(prefs: Prefs): Long = try {
    if (prefs.promptAutoSelectEnabled && prefs.postProcessEnabled && prefs.hasLlmKeys()) {
        PromptSelector.TOTAL_TIMEOUT_MS
    } else {
        0L
    }
} catch (_: Throwable) {
    0L
}

/**
 * 分类请求的固定本地化文案。
 */
internal data class PromptSelectionLabels(
    val systemPrompt: String,
    val candidatesLabel: String,
    val transcriptLabel: String
) {
    companion object {
        fun from(prefs: Prefs): PromptSelectionLabels = PromptSelectionLabels(
            systemPrompt = prefs.getLocalizedString(R.string.prompt_selection_system_prompt),
            candidatesLabel = prefs.getLocalizedString(R.string.prompt_selection_candidates_label),
            transcriptLabel = prefs.getLocalizedString(R.string.prompt_selection_transcript_label)
        )
    }
}

internal object PromptSelector {
    private const val TAG = "PromptSelector"

    /** 分类阶段的独立总超时（含首次请求模式探测与重试）。 */
    const val TOTAL_TIMEOUT_MS = 8_000L

    /**
     * 候选 ID 与 `pN` 的映射顺序固定为候选列表顺序。
     *
     * 候选列表由 [com.brycewg.asrkb.store.PromptSelectionStore] 生成：普通预设按预设列表顺序，
     * 特殊项“跳过润色”固定在最后（平局时优先普通预设）。
     */
    fun candidatePromptIds(count: Int): List<String> = (1..count).map { "p$it" }

    /**
     * 构造分类请求：只包含固定路由指令、`pN` + skill，以及 ASR 原文。
     *
     * 刻意不携带预设标题与完整 prompt 内容，避免泄露用户提示词并压缩输入长度。
     * 数据使用 JSON 编码，skill/原文里的换行、引号和伪标签只会作为字符串内容出现。
     */
    fun buildRequest(
        labels: PromptSelectionLabels,
        candidates: List<PromptSelectionCandidate>,
        asrText: String
    ): Pair<String, String> {
        val ids = candidatePromptIds(candidates.size)
        val candidateData = JSONArray().apply {
            candidates.forEachIndexed { index, candidate ->
                put(JSONObject().put("id", ids[index]).put("skill", candidate.skill.trim()))
            }
        }
        val userContent = JSONObject()
            .put("candidatesLabel", labels.candidatesLabel)
            .put("candidates", candidateData)
            .put("transcriptLabel", labels.transcriptLabel)
            .put("transcript", asrText)
            .toString()
        return labels.systemPrompt to userContent
    }

    /**
     * 严格解析分类输出。
     *
     * 只接受 trim 后与某个候选 ID 完全一致的内容；JSON、解释、代码块、未知 ID 均视为失败。
     */
    fun parseSelectionId(raw: String?, candidates: List<PromptSelectionCandidate>): String? = parseSelection(raw, candidates)?.id

    /** 严格解析并返回命中的候选（含“跳过润色”特殊项）。 */
    fun parseSelection(
        raw: String?,
        candidates: List<PromptSelectionCandidate>
    ): PromptSelectionCandidate? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val ids = candidatePromptIds(candidates.size)
        val index = ids.indexOf(trimmed)
        if (index < 0) return null
        return candidates[index]
    }

    /**
     * 执行一次分类。
     *
     * 前置校验（候选数量、skill、模型配置）不通过时不发请求，[PromptSelectionStatus.requestSent] 为 false。
     */
    suspend fun select(
        prefs: Prefs,
        processor: LlmPostProcessor,
        asrText: String,
        candidates: List<PromptSelectionCandidate>,
        modelRef: PromptSelectorModelRef,
        onRequestStarted: (() -> Unit)? = null,
        onRequestFinished: (() -> Unit)? = null,
        persistRequestMode: Boolean = true
    ): PromptSelectionOutcome {
        val startedAtNs = System.nanoTime()
        fun elapsedMs(): Long = TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startedAtNs).coerceAtLeast(0L))

        // 特殊项计入 2 项下限，但不会因“缺 skill”被拒（其 skill 来自固定资源）。
        val missingSkill = candidates.count { it.skill.isBlank() }
        if (candidates.size < 2 || missingSkill > 0) {
            return PromptSelectionOutcome(
                status = PromptSelectionStatus.failure(
                    reason = PromptSelectionFailReason.INVALID_CONFIG,
                    requestSent = false,
                    elapsedMs = elapsedMs()
                ),
                candidate = null
            )
        }

        if (modelRef is PromptSelectorModelRef.Builtin && modelRef.vendorId == LlmVendor.TYPESAFE.id) {
            val provider = prefs.jevClassifierProvider
            val credentialsReady = when (provider) {
                JevClassifierProvider.TYPESAFE -> prefs.jevTypesafeApiKey.isNotBlank()
                JevClassifierProvider.OPENROUTER -> prefs.jevOpenRouterApiKey.isNotBlank()
                JevClassifierProvider.CLOUDFLARE ->
                    prefs.jevCloudflareApiKey.isNotBlank() && prefs.jevCloudflareAccountId.isNotBlank()
            }
            if (!credentialsReady || modelRef.model != com.brycewg.asrkb.store.JEV_MODEL_ID) {
                return PromptSelectionOutcome(
                    status = PromptSelectionStatus.failure(
                        reason = PromptSelectionFailReason.INVALID_CONFIG,
                        requestSent = false,
                        elapsedMs = elapsedMs()
                    ),
                    candidate = null
                )
            }
            val classifier = JevClassifier()
            val result = try {
                onRequestStarted?.invoke()
                try {
                    classifier.select(
                        prefs = prefs,
                        provider = provider,
                        candidates = candidates,
                        asrText = asrText
                    )
                } finally {
                    onRequestFinished?.invoke()
                }
            } catch (t: CancellationException) {
                classifier.cancel()
                throw t
            }
            val matchedIndex = result.choice?.let { candidatePromptIds(candidates.size).indexOf(it.trim()) }
            val matched = matchedIndex?.takeIf { it >= 0 }?.let(candidates::get)
            if (matched == null) {
                return PromptSelectionOutcome(
                    status = PromptSelectionStatus.failure(
                        reason = result.failureReason ?: PromptSelectionFailReason.INVALID_OUTPUT,
                        requestSent = result.requestSent,
                        vendorId = result.vendorId,
                        model = result.model,
                        elapsedMs = result.elapsedMs
                    ),
                    candidate = null
                )
            }
            val status = if (matched.skipsPolish) {
                PromptSelectionStatus.skippedPolishSuccess(
                    vendorId = result.vendorId,
                    model = result.model,
                    elapsedMs = result.elapsedMs
                )
            } else {
                PromptSelectionStatus.success(
                    presetId = matched.id,
                    presetTitle = matched.displayTitle,
                    vendorId = result.vendorId,
                    customProviderId = null,
                    model = result.model,
                    elapsedMs = result.elapsedMs
                )
            }
            return PromptSelectionOutcome(status = status, candidate = matched)
        }

        val resolved = when (val r = LlmModelConfigResolver.resolve(prefs, modelRef)) {
            is LlmModelResolution.Resolved -> r.config
            is LlmModelResolution.Unavailable -> {
                val reason = if (r.reason == LlmModelUnavailableReason.MISSING_API_KEY ||
                    r.reason == LlmModelUnavailableReason.PROVIDER_MISSING
                ) {
                    PromptSelectionFailReason.INVALID_CONFIG
                } else {
                    PromptSelectionFailReason.MODEL_UNAVAILABLE
                }
                return PromptSelectionOutcome(
                    status = PromptSelectionStatus.failure(
                        reason = reason,
                        requestSent = false,
                        elapsedMs = elapsedMs()
                    ),
                    candidate = null
                )
            }
        }

        val labels = PromptSelectionLabels.from(prefs)
        val (systemPrompt, userContent) = buildRequest(labels, candidates, asrText)

        val result = try {
            onRequestStarted?.invoke()
            try {
                withTimeout(TOTAL_TIMEOUT_MS) {
                    processor.processWithResolvedConfig(
                        prefs = prefs,
                        resolved = resolved,
                        systemPrompt = systemPrompt,
                        userContent = userContent,
                        totalTimeoutMs = TOTAL_TIMEOUT_MS,
                        persistRequestMode = persistRequestMode
                    )
                }
            } finally {
                onRequestFinished?.invoke()
            }
        } catch (timeout: TimeoutCancellationException) {
            // 超时后必须主动取消底层阻塞请求，否则连接会一直占用到读超时。
            processor.cancelActiveRequest()
            Log.w(TAG, "Prompt selection timed out")
            return PromptSelectionOutcome(
                status = PromptSelectionStatus.failure(
                    reason = PromptSelectionFailReason.TIMEOUT,
                    requestSent = true,
                    vendorId = resolved.vendorId,
                    customProviderId = resolved.customProviderId,
                    model = resolved.model,
                    elapsedMs = elapsedMs()
                ),
                candidate = null
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "Prompt selection request failed", t)
            return PromptSelectionOutcome(
                status = PromptSelectionStatus.failure(
                    reason = PromptSelectionFailReason.REQUEST_FAILED,
                    requestSent = true,
                    vendorId = resolved.vendorId,
                    customProviderId = resolved.customProviderId,
                    model = resolved.model,
                    elapsedMs = elapsedMs()
                ),
                candidate = null
            )
        }

        if (!result.ok) {
            val error = result.errorMessage.orEmpty()
            val reason = when {
                error == CANCELLED_ERROR -> PromptSelectionFailReason.CANCELLED
                error.startsWith(TIMEOUT_ERROR_PREFIX) || elapsedMs() >= TOTAL_TIMEOUT_MS ->
                    PromptSelectionFailReason.TIMEOUT
                else -> PromptSelectionFailReason.REQUEST_FAILED
            }
            return PromptSelectionOutcome(
                status = PromptSelectionStatus.failure(
                    reason = reason,
                    requestSent = true,
                    vendorId = resolved.vendorId,
                    customProviderId = resolved.customProviderId,
                    model = resolved.model,
                    elapsedMs = elapsedMs()
                ),
                candidate = null
            )
        }

        val matched = parseSelection(result.text, candidates)
        if (matched == null) {
            Log.w(TAG, "Prompt selection output did not match any candidate id")
            return PromptSelectionOutcome(
                status = PromptSelectionStatus.failure(
                    reason = PromptSelectionFailReason.INVALID_OUTPUT,
                    requestSent = true,
                    vendorId = resolved.vendorId,
                    customProviderId = resolved.customProviderId,
                    model = resolved.model,
                    elapsedMs = elapsedMs()
                ),
                candidate = null
            )
        }

        // 命中“跳过润色”：分类成功但本次不再发润色请求。
        if (matched.skipsPolish) {
            return PromptSelectionOutcome(
                status = PromptSelectionStatus.skippedPolishSuccess(
                    vendorId = resolved.vendorId,
                    customProviderId = resolved.customProviderId,
                    model = resolved.model,
                    elapsedMs = elapsedMs()
                ),
                candidate = matched
            )
        }

        return PromptSelectionOutcome(
            status = PromptSelectionStatus.success(
                presetId = matched.id,
                presetTitle = matched.displayTitle,
                vendorId = resolved.vendorId,
                customProviderId = resolved.customProviderId,
                model = resolved.model,
                elapsedMs = elapsedMs()
            ),
            candidate = matched
        )
    }

    private const val CANCELLED_ERROR = "Request canceled"
    private const val TIMEOUT_ERROR_PREFIX = "timeout"
}
