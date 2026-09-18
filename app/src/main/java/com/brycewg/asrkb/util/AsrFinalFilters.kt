package com.brycewg.asrkb.util

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.asr.PromptSelectionOutcome
import com.brycewg.asrkb.asr.PromptSelector
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptSelectionCandidate
import com.brycewg.asrkb.store.PromptSelectionFailReason
import com.brycewg.asrkb.store.PromptSelectionStatus
import kotlinx.coroutines.CancellationException

/**
 * 识别结果末处理：统一封装去尾处理与可选 AI 后处理
 */
object AsrFinalFilters {
    private const val TAG = "AsrFinalFilters"

    /**
     * 自动选择提示词（润色模式）的入口模式。
     *
     * 必须由调用方显式声明：默认 [DISABLED]，只有真正的“自动流程”才传 [AUTO_IF_ENABLED]，
     * 录音测试与手动应用预设等显式入口一律保持 [DISABLED]。
     */
    enum class PromptSelectionMode {
        /** 不进行分类，使用当前激活预设或调用方显式传入的 prompt。 */
        DISABLED,

        /** 若开关与配置有效，则先分类选出本次使用的预设；失败时回退到当前激活预设。 */
        AUTO_IF_ENABLED
    }

    /** Observes the actual LLM request without participating in result processing. */
    interface AiPostprocessTimingObserver {
        fun onAiPostprocessStarted()
        fun onAiPostprocessFinished()

        /** 仅在实际发起分类请求时回调；时间轴上位于 AI_POSTPROCESS 之前。 */
        fun onPromptSelectionStarted() {}

        fun onPromptSelectionFinished() {}
    }

    fun shouldTrimTrailingPunctAndEmoji(prefs: Prefs, text: String): Boolean = shouldTrimTrailingPunctAndEmoji(
        enabled = prefs.trimFinalTrailingPunct,
        effectiveCharCount = TextSanitizer.countEffectiveChars(text),
        threshold = prefs.trimFinalTrailingPunctThreshold
    )

    internal fun shouldTrimTrailingPunctAndEmoji(
        enabled: Boolean,
        effectiveCharCount: Int,
        threshold: Int
    ): Boolean {
        if (!enabled) return false
        val normalizedThreshold = threshold.coerceIn(
            Prefs.TRIM_FINAL_TRAILING_PUNCT_THRESHOLD_MIN,
            Prefs.TRIM_FINAL_TRAILING_PUNCT_THRESHOLD_UNLIMITED
        )
        return normalizedThreshold == Prefs.TRIM_FINAL_TRAILING_PUNCT_THRESHOLD_UNLIMITED ||
            effectiveCharCount <= normalizedThreshold
    }

    /**
     * 执行基础过滤：去除句末标点/emoji，并处理预置替换
     */
    fun applySimple(context: Context, prefs: Prefs, input: String): String {
        var out = input
        try {
            if (shouldTrimTrailingPunctAndEmoji(prefs, input)) {
                out = TextSanitizer.trimTrailingPunctAndEmoji(out)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "trimTrailingPunct failed", t)
        }

        // 预置替换（最高优先级，直接返回替换文案）
        return try {
            val rep = prefs.findSpeechPresetReplacement(out)
            if (!rep.isNullOrEmpty()) rep else out
        } catch (t: Throwable) {
            Log.w(TAG, "speech preset replacement failed", t)
            out
        }
    }

    /**
     * 可选 AI 后处理：
     * - 先按需要去除句末标点；
     * - 若开启 LLM 且配置完整，按需先做一次自动选择（分类），再调用 LLM 后处理；
     * - 结束后再次按需要去除句末标点
     * 返回值沿用 LlmPostProcessor 的结果结构，text 字段为最终可提交文本。
     */
    suspend fun applyWithAi(
        context: Context,
        prefs: Prefs,
        input: String,
        postProcessor: LlmPostProcessor = LlmPostProcessor(),
        promptOverride: String? = null,
        forceAi: Boolean = false,
        onStreamingUpdate: ((String) -> Unit)? = null,
        aiTimingObserver: AiPostprocessTimingObserver? = null,
        promptSelectionMode: PromptSelectionMode = PromptSelectionMode.DISABLED,
        isCancelled: (() -> Boolean)? = null
    ): LlmPostProcessor.LlmProcessResult {
        if (input.isBlank()) {
            return LlmPostProcessor.LlmProcessResult(
                ok = true,
                text = input,
                errorMessage = null,
                httpCode = null,
                usedAi = false,
                attempted = false,
                llmMs = 0
            )
        }
        val shouldTrimTrailing = try {
            shouldTrimTrailingPunctAndEmoji(prefs, input)
        } catch (t: Throwable) {
            Log.w(TAG, "trim threshold calculation failed", t)
            false
        }

        // 预修剪
        val base = try {
            if (shouldTrimTrailing) {
                TextSanitizer.trimTrailingPunctAndEmoji(
                    input
                )
            } else {
                input
            }
        } catch (t: Throwable) {
            Log.w(TAG, "pre-trim failed", t)
            input
        }

        // 语音预设替换：若命中则跳过 LLM 与全部其他处理（含正则/繁体），直接返回
        try {
            val rep = prefs.findSpeechPresetReplacement(base)
            if (!rep.isNullOrEmpty()) {
                return LlmPostProcessor.LlmProcessResult(
                    ok = true,
                    text = rep,
                    errorMessage = null,
                    httpCode = null,
                    usedAi = false,
                    attempted = false,
                    llmMs = 0
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "speech preset replacement failed (ai branch)", t)
        }

        if (base.isBlank()) {
            return LlmPostProcessor.LlmProcessResult(
                ok = true,
                text = base,
                errorMessage = null,
                httpCode = null,
                usedAi = false,
                attempted = false,
                llmMs = 0
            )
        }

        var processed = base
        var ok = true
        var http: Int? = null
        var err: String? = null
        var aiAttempted = false
        var aiMs: Long = 0
        var llmVendorId: String? = null
        var selectionStatus: PromptSelectionStatus? = null

        // 少于阈值时自动跳过 AI 后处理（forceAi 时不跳过）
        val skipForShort = try {
            if (forceAi || prefs.postprocSkipUnderChars <= 0) {
                false
            } else {
                TextSanitizer.countEffectiveChars(base) < prefs.postprocSkipUnderChars
            }
        } catch (t: Throwable) {
            Log.w(TAG, "skip threshold calculation failed", t)
            false
        }

        val autoSelectionRequested =
            promptSelectionMode == PromptSelectionMode.AUTO_IF_ENABLED && isAutoSelectEnabled(prefs)
        if (!skipForShort && (forceAi || prefs.postProcessEnabled) && prefs.hasLlmKeys()) {
            var effectivePromptOverride = promptOverride
            var skipPolish = false
            if (autoSelectionRequested) {
                // 自动选择失败必须使用当前激活预设；手动模式下的 override 不参与自动流程回退。
                effectivePromptOverride = null
                val selection = runPromptSelection(prefs, postProcessor, base, aiTimingObserver)
                selectionStatus = selection.status
                when (val candidate = selection.candidate) {
                    null -> Unit
                    is PromptSelectionCandidate.Preset ->
                        effectivePromptOverride = candidate.contentForPolish

                    is PromptSelectionCandidate.SkipPolish -> skipPolish = true
                }
            }
            // 分类与润色共享取消语义：即使命中“跳过润色”，分类结束后取消也不能返回成功结果。
            val cancelledAfterSelection = isCancelled?.invoke() == true
            if (cancelledAfterSelection) {
                aiAttempted = !skipPolish
                ok = false
                processed = base
                err = "Request canceled"
            } else if (!skipPolish) {
                aiAttempted = true
                val t0 = System.nanoTime()
                notifyAiTimingStarted(aiTimingObserver)
                try {
                    val res = postProcessor.processWithStatus(
                        base,
                        prefs,
                        effectivePromptOverride,
                        onStreamingUpdate = onStreamingUpdate
                    )
                    ok = res.ok
                    processed = res.text
                    http = res.httpCode
                    err = res.errorMessage
                    llmVendorId = res.llmVendorId
                    aiMs =
                        if (res.llmMs >
                            0
                        ) {
                            res.llmMs
                        } else {
                            ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)
                        }
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    Log.e(TAG, "LLM post-processing threw", t)
                    ok = false
                    processed = base
                    err = t.message
                    aiMs = ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(0L)
                } finally {
                    notifyAiTimingFinished(aiTimingObserver)
                }
            }
        }

        // 后修剪
        processed = try {
            if (shouldTrimTrailing) {
                TextSanitizer.trimTrailingPunctAndEmoji(
                    processed
                )
            } else {
                processed
            }
        } catch (t: Throwable) {
            Log.w(TAG, "post-trim failed", t)
            processed
        }

        // AI 返回空：视为失败（由上层决定是否回退到 applySimple）
        if (aiAttempted && ok && processed.isBlank()) {
            ok = false
            err = err ?: "Empty AI output"
        }

        val usedAi = aiAttempted && ok && processed.isNotBlank()
        return LlmPostProcessor.LlmProcessResult(
            ok = ok,
            text = processed,
            errorMessage = err,
            httpCode = http,
            usedAi = usedAi,
            attempted = aiAttempted,
            llmMs = if (aiAttempted) aiMs.coerceAtLeast(0L) else 0,
            llmVendorId = llmVendorId,
            promptSelectionStatus = selectionStatus
        )
    }

    /**
     * 自动选择开关。开关关闭时即使流程显式要求 AUTO 也不分类：分类是用户显式开启的能力。
     */
    private fun isAutoSelectEnabled(prefs: Prefs): Boolean = try {
        prefs.promptAutoSelectEnabled
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to read prompt auto select switch", t)
        false
    }

    /**
     * 执行一次自动选择。任何异常都降级为“失败并继续使用当前激活预设”，绝不抛出。
     */
    private suspend fun runPromptSelection(
        prefs: Prefs,
        postProcessor: LlmPostProcessor,
        text: String,
        observer: AiPostprocessTimingObserver?
    ): PromptSelectionOutcome {
        val startedAtNs = System.nanoTime()
        return try {
            val candidateState = prefs.getPromptSelectionCandidateState()
            PromptSelector.select(
                prefs = prefs,
                processor = postProcessor,
                asrText = text,
                candidates = candidateState.resolved,
                modelRef = prefs.promptSelectorModelRef,
                onRequestStarted = { notifyPromptSelectionStarted(observer) },
                onRequestFinished = { notifyPromptSelectionFinished(observer) }
            )
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Prompt selection threw", t)
            PromptSelectionOutcome(
                status = PromptSelectionStatus.failure(
                    reason = PromptSelectionFailReason.INVALID_CONFIG,
                    requestSent = false,
                    elapsedMs = (System.nanoTime() - startedAtNs) / 1_000_000L
                ),
                candidate = null
            )
        }
    }

    private fun notifyAiTimingStarted(observer: AiPostprocessTimingObserver?) {
        try {
            observer?.onAiPostprocessStarted()
        } catch (t: Throwable) {
            Log.w(TAG, "AI timing observer start failed", t)
        }
    }

    private fun notifyAiTimingFinished(observer: AiPostprocessTimingObserver?) {
        try {
            observer?.onAiPostprocessFinished()
        } catch (t: Throwable) {
            Log.w(TAG, "AI timing observer finish failed", t)
        }
    }

    private fun notifyPromptSelectionStarted(observer: AiPostprocessTimingObserver?) {
        try {
            observer?.onPromptSelectionStarted()
        } catch (t: Throwable) {
            Log.w(TAG, "Prompt selection timing observer start failed", t)
        }
    }

    private fun notifyPromptSelectionFinished(observer: AiPostprocessTimingObserver?) {
        try {
            observer?.onPromptSelectionFinished()
        } catch (t: Throwable) {
            Log.w(TAG, "Prompt selection timing observer finish failed", t)
        }
    }
}
