package com.brycewg.asrkb.asr

/**
 * 统一的 ASR 超时计算：基准 10 秒，每增加 5 秒录音增加 2 秒，上限 40 秒。
 */
object AsrTimeoutCalculator {
    private const val BASE_TIMEOUT_MS = 10000L
    private const val EXTRA_PER_FIVE_SEC_MS = 2000L
    private const val MAX_TIMEOUT_MS = 40000L

    fun calculateTimeoutMs(audioMs: Long): Long {
        val extra = (audioMs / 5000L) * EXTRA_PER_FIVE_SEC_MS
        return (BASE_TIMEOUT_MS + extra).coerceAtMost(MAX_TIMEOUT_MS)
    }
}
