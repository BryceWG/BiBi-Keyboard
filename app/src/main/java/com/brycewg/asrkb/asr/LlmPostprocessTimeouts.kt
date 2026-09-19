/**
 * LLM 后处理两档超时预算：正文首 token 与输出阶段。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import java.util.concurrent.TimeUnit

internal object LlmPostprocessTimeouts {
    private const val OUTPUT_TPS = 10
    private const val MIN_OUTPUT_MS = 2_000L
    private const val FIRST_TOKEN_MS_NO_REASONING = 8_000L
    private const val FIRST_TOKEN_MS_REASONING = 15_000L
    private const val CONNECTIVITY_FIRST_TOKEN_MS = 60_000L
    private const val CONNECTIVITY_OUTPUT_MS = 10_000L

    /** 总时长预算里输出阶段的最低保留量。 */
    private const val OUTPUT_FLOOR_MS = MIN_OUTPUT_MS

    /** 总时长预算里首 token 阶段的最低保留量（总时长很小时按一半压缩）。 */
    private const val FIRST_TOKEN_FLOOR_MS = 1_000L

    data class Budget(
        val firstTokenMs: Long,
        val outputMs: Long,
        val reasoningEnabled: Boolean,
        val charCount: Int,
        /**
         * 整个逻辑请求（含请求模式探测）的绝对截止时刻（`System.nanoTime()` 基准）。
         *
         * 两档预算只能约束单次 HTTP 调用；需要“总超时”的场景（如自动选择分类）靠它把
         * 探测与正式请求都压在同一个硬截止时间内。
         */
        val absoluteDeadlineNs: Long? = null
    ) {
        val combinedMs: Long get() = firstTokenMs + outputMs

        /** 单次调用允许的截止时刻：不超过绝对截止时间。 */
        fun callDeadlineNs(startedAtNs: Long, phaseMs: Long): Long {
            val phaseDeadline = startedAtNs + TimeUnit.MILLISECONDS.toNanos(phaseMs)
            val absolute = absoluteDeadlineNs ?: return phaseDeadline
            return minOf(phaseDeadline, absolute)
        }
    }

    fun budget(reasoningEnabled: Boolean, inputCharCount: Int): Budget {
        val charCount = inputCharCount.coerceAtLeast(0)
        return Budget(
            firstTokenMs = if (reasoningEnabled) {
                FIRST_TOKEN_MS_REASONING
            } else {
                FIRST_TOKEN_MS_NO_REASONING
            },
            outputMs = outputTimeoutMs(charCount),
            reasoningEnabled = reasoningEnabled,
            charCount = charCount
        )
    }

    /**
     * 总时长硬预算：两档之和恰好等于 [totalMs]（永不超过），并带上绝对截止时刻作为硬上限。
     *
     * 分配规则：先给输出阶段留出至少 [OUTPUT_FLOOR_MS]（自然输出预算更大时按自然值给），
     * 余量全部给首 token；两者都只把自然预算当上限，因此总超时较小时会被等比压缩。
     * 例如 total = 8s、非 reasoning、短输入：firstToken = 6s、output = 2s。
     */
    fun totalBudget(
        totalMs: Long,
        reasoningEnabled: Boolean,
        inputCharCount: Int,
        nowNs: Long
    ): Budget {
        val total = totalMs.coerceAtLeast(1L)
        val firstTokenFloor = minOf(FIRST_TOKEN_FLOOR_MS, total / 2)
        val maxOutput = (total - firstTokenFloor).coerceAtLeast(0L)
        val outputTarget = minOf(outputTimeoutMs(inputCharCount), maxOutput)
        val output = maxOf(outputTarget, minOf(OUTPUT_FLOOR_MS, maxOutput))
        return Budget(
            firstTokenMs = total - output,
            outputMs = output,
            reasoningEnabled = reasoningEnabled,
            charCount = inputCharCount.coerceAtLeast(0),
            absoluteDeadlineNs = nowNs + TimeUnit.MILLISECONDS.toNanos(total)
        )
    }

    fun connectivityBudget(reasoningEnabled: Boolean): Budget = Budget(
        firstTokenMs = CONNECTIVITY_FIRST_TOKEN_MS,
        outputMs = CONNECTIVITY_OUTPUT_MS,
        reasoningEnabled = reasoningEnabled,
        charCount = 0
    )

    fun outputTimeoutMs(inputCharCount: Int): Long {
        val estimatedMs = inputCharCount.coerceAtLeast(0) * 1_000L / OUTPUT_TPS
        return estimatedMs.coerceAtLeast(MIN_OUTPUT_MS)
    }
}
