package com.brycewg.asrkb.asr

import com.brycewg.asrkb.store.AsrRuntimeVendorSnapshot
import kotlin.math.roundToLong

internal data class BackupSwitchPlan(
    val switchDeadlineMs: Long,
    val usedStaticFallback: Boolean,
    val baselineMs: Long,
    val audioLengthAdjustmentMs: Long,
    val primaryModeAdjustmentMs: Long,
    val lazyBackupStartAtMs: Long?,
    val lazyEstimatedBackupReadyMs: Long,
    val lazyResidencyFactor: Double,
    val lazyMinPrimaryWindowMs: Long
)

/**
 * 统一的 ASR 超时计算。
 * - 云端/默认：基准 10 秒，每增加 5 秒录音增加 2 秒，上限 40 秒。
 * - 本地模型：按 vendor 放宽最短等待与上限，避免重模型在设备侧推理时过早超时。
 */
object AsrTimeoutCalculator {
    private const val BASE_TIMEOUT_MS = 10000L
    private const val EXTRA_PER_FIVE_SEC_MS = 2000L
    private const val DEFAULT_MIN_TIMEOUT_MS = BASE_TIMEOUT_MS
    private const val DEFAULT_MAX_TIMEOUT_MS = 40000L
    private const val PROCESSING_DYNAMIC_SAFETY_MARGIN_MS = 2_000L
    private const val BACKUP_PROCESSING_MARGIN_MS = 2_000L
    private const val LAZY_LOCAL_BACKUP_ABSOLUTE_MAX_MS = 120_000L
    private const val SWITCH_DEADLINE_BASE_SENSITIVE_MS = 3_000L
    private const val SWITCH_DEADLINE_BASE_BALANCED_MS = 5_000L
    private const val SWITCH_DEADLINE_BASE_RELAXED_MS = 8_000L
    private const val SWITCH_DEADLINE_AUDIO_MEDIUM_MS = 1_000L
    private const val SWITCH_DEADLINE_AUDIO_LONG_MS = 2_000L
    private const val SWITCH_DEADLINE_AUDIO_EXTRA_LONG_MS = 4_000L
    private const val SWITCH_DEADLINE_ONLINE_FILE_ADJUSTMENT_MS = 1_000L
    private const val SWITCH_DEADLINE_LOCAL_STREAM_ADJUSTMENT_MS = 2_000L
    private const val SWITCH_DEADLINE_LOCAL_FILE_ADJUSTMENT_MS = 4_000L
    private const val LAZY_DEADLINE_MIN_MS = 2_500L
    private const val LAZY_MIN_PRIMARY_WINDOW_MS = 1_500L
    private const val LAZY_ESTIMATED_BACKUP_READY_FALLBACK_MS = 1_500L
    private const val LAZY_RESIDENCY_FACTOR_RELAXED = 0.45
    private const val LAZY_RESIDENCY_FACTOR_BALANCED = 0.75
    private const val LAZY_RESIDENCY_FACTOR_SENSITIVE = 1.0

    private data class TimeoutProfile(
        val minTimeoutMs: Long,
        val maxTimeoutMs: Long
    )

    fun calculateTimeoutMs(audioMs: Long): Long = calculateTimeoutMs(audioMs, vendor = null)

    fun calculateTimeoutMs(audioMs: Long, vendor: AsrVendor?): Long {
        val profile = profileFor(vendor)
        val extra = (audioMs.coerceAtLeast(0L) / 5000L) * EXTRA_PER_FIVE_SEC_MS
        val rawTimeoutMs = BASE_TIMEOUT_MS + extra
        return rawTimeoutMs.coerceIn(profile.minTimeoutMs, profile.maxTimeoutMs)
    }

    internal fun calculateProcessingTimeoutMs(
        audioMs: Long,
        vendor: AsrVendor?,
        statsSnapshot: AsrRuntimeVendorSnapshot? = null
    ): Long {
        val staticTimeoutMs = calculateTimeoutMs(audioMs, vendor)
        val dynamicBaselineMs = dynamicSlowBaselineMs(statsSnapshot) ?: return staticTimeoutMs
        val profile = profileFor(vendor)
        return (dynamicBaselineMs + PROCESSING_DYNAMIC_SAFETY_MARGIN_MS)
            .coerceIn(profile.minTimeoutMs, profile.maxTimeoutMs)
    }

    internal fun calculateBackupAwareProcessingTimeoutMs(
        audioMs: Long,
        primaryVendor: AsrVendor?,
        primaryStatsSnapshot: AsrRuntimeVendorSnapshot? = null,
        backupStrategy: AsrParallelEngineDecision? = null,
        backupVendor: AsrVendor? = null,
        backupStatsSnapshot: AsrRuntimeVendorSnapshot? = null,
        sensitivityTier: Int = 1,
        primaryStreaming: Boolean = true,
        /**
         * AI 阶段额外的固定预算（例如自动选择提示词的分类窗口）。
         *
         * 该会话级熔断同时覆盖识别与 AI 后处理；不追加这段预算会直接挤占润色时间。
         */
        extraAiBudgetMs: Long = 0L
    ): Long {
        val primaryTimeoutMs = calculateProcessingTimeoutMs(
            audioMs = audioMs,
            vendor = primaryVendor,
            statsSnapshot = primaryStatsSnapshot
        )
        val baseTimeoutMs = when (backupStrategy) {
            AsrParallelEngineDecision.UseParallel -> {
                val backupProcessingTimeoutMs = calculateProcessingTimeoutMs(
                    audioMs = audioMs,
                    vendor = backupVendor,
                    statsSnapshot = backupStatsSnapshot
                )
                maxOf(primaryTimeoutMs, backupProcessingTimeoutMs) + BACKUP_PROCESSING_MARGIN_MS
            }
            AsrParallelEngineDecision.UseLazyLocalBackup -> {
                // Processing timeout is the final session fuse. It must leave room after
                // the switch deadline for lazy backup startup and inference; it does not
                // decide whether backup is allowed to win.
                val primaryVendorForPlan = primaryVendor ?: backupVendor ?: AsrVendor.Volc
                val switchPlan = calculateBackupSwitchPlan(
                    audioMs = audioMs,
                    primaryVendor = primaryVendorForPlan,
                    primaryStreaming = primaryStreaming,
                    primaryStatsSnapshot = primaryStatsSnapshot,
                    backupStrategy = AsrParallelEngineDecision.UseLazyLocalBackup,
                    backupStatsSnapshot = backupStatsSnapshot,
                    sensitivityTier = sensitivityTier
                )
                val backupInferenceMs = calculateProcessingTimeoutMs(
                    audioMs = audioMs,
                    vendor = backupVendor,
                    statsSnapshot = backupStatsSnapshot
                )
                maxOf(
                    primaryTimeoutMs + BACKUP_PROCESSING_MARGIN_MS,
                    switchPlan.switchDeadlineMs +
                        switchPlan.lazyEstimatedBackupReadyMs +
                        backupInferenceMs +
                        BACKUP_PROCESSING_MARGIN_MS
                ).coerceAtMost(LAZY_LOCAL_BACKUP_ABSOLUTE_MAX_MS)
            }
            AsrParallelEngineDecision.UsePrimaryOnly,
            null -> primaryTimeoutMs
        }
        return baseTimeoutMs + extraAiBudgetMs.coerceAtLeast(0L)
    }

    internal fun calculateBackupSwitchPlan(
        audioMs: Long,
        primaryVendor: AsrVendor,
        primaryStreaming: Boolean,
        sensitivityTier: Int,
        primaryStatsSnapshot: AsrRuntimeVendorSnapshot? = null,
        backupStrategy: AsrParallelEngineDecision? = null,
        backupStatsSnapshot: AsrRuntimeVendorSnapshot? = null
    ): BackupSwitchPlan {
        val staticBaselineMs = staticSwitchDeadlineBaseMs(sensitivityTier)
        val audioLengthAdjustmentMs = switchDeadlineAudioLengthAdjustmentMs(audioMs)
        val primaryModeAdjustmentMs = switchDeadlinePrimaryModeAdjustmentMs(
            primaryVendor = primaryVendor,
            primaryStreaming = primaryStreaming
        )
        val staticFallbackMs = staticBaselineMs +
            audioLengthAdjustmentMs +
            primaryModeAdjustmentMs
        val dynamicBaselineMs = dynamicSwitchDeadlineBaselineMs(
            snapshot = primaryStatsSnapshot,
            sensitivityTier = sensitivityTier
        )
        val usedStaticFallback = dynamicBaselineMs == null
        val baselineMs = dynamicBaselineMs ?: staticBaselineMs
        val minDeadlineMs = if (backupStrategy == AsrParallelEngineDecision.UseLazyLocalBackup) {
            maxOf(staticFallbackMs, LAZY_DEADLINE_MIN_MS)
        } else {
            staticFallbackMs
        }
        val profile = profileFor(primaryVendor)
        val switchDeadlineMs = (dynamicBaselineMs ?: staticFallbackMs)
            .coerceAtLeast(minDeadlineMs)
            .coerceAtMost(profile.maxTimeoutMs)
        val lazyEstimatedBackupReadyMs = estimatedLazyBackupReadyMs(backupStatsSnapshot)
        val lazyResidencyFactor = lazyResidencyFactor(sensitivityTier)
        val lazyBackupStartAtMs = if (backupStrategy == AsrParallelEngineDecision.UseLazyLocalBackup) {
            (switchDeadlineMs - (lazyEstimatedBackupReadyMs.toDouble() * lazyResidencyFactor).roundToLong())
                .coerceAtLeast(LAZY_MIN_PRIMARY_WINDOW_MS)
        } else {
            null
        }

        return BackupSwitchPlan(
            switchDeadlineMs = switchDeadlineMs,
            usedStaticFallback = usedStaticFallback,
            baselineMs = baselineMs,
            audioLengthAdjustmentMs = audioLengthAdjustmentMs,
            primaryModeAdjustmentMs = primaryModeAdjustmentMs,
            lazyBackupStartAtMs = lazyBackupStartAtMs,
            lazyEstimatedBackupReadyMs = lazyEstimatedBackupReadyMs,
            lazyResidencyFactor = lazyResidencyFactor,
            lazyMinPrimaryWindowMs = LAZY_MIN_PRIMARY_WINDOW_MS
        )
    }

    private fun dynamicSlowBaselineMs(snapshot: AsrRuntimeVendorSnapshot?): Long? {
        if (snapshot?.hasEnoughRequestSamples != true) return null
        return snapshot.slowRequestMs?.takeIf { it > 0L }
    }

    private fun dynamicSwitchDeadlineBaselineMs(
        snapshot: AsrRuntimeVendorSnapshot?,
        sensitivityTier: Int
    ): Long? {
        if (snapshot?.hasEnoughRequestSamples != true) return null
        val p50 = snapshot.p50RequestMs?.takeIf { it > 0L } ?: return null
        val p90 = snapshot.p90RequestMs?.takeIf { it > 0L } ?: return null
        val low = minOf(p50, p90)
        val high = maxOf(p50, p90)
        val spread = high - low
        return when (sensitivityTier.coerceIn(0, 2)) {
            0 -> high
            2 -> low + (spread.toDouble() * 0.25).roundToLong()
            else -> low + (spread.toDouble() * 0.625).roundToLong()
        }
    }

    private fun staticSwitchDeadlineBaseMs(sensitivityTier: Int): Long = when (sensitivityTier.coerceIn(0, 2)) {
        0 -> SWITCH_DEADLINE_BASE_RELAXED_MS
        2 -> SWITCH_DEADLINE_BASE_SENSITIVE_MS
        else -> SWITCH_DEADLINE_BASE_BALANCED_MS
    }

    private fun switchDeadlineAudioLengthAdjustmentMs(audioMs: Long): Long = when {
        audioMs <= 5_000L -> 0L
        audioMs <= 20_000L -> SWITCH_DEADLINE_AUDIO_MEDIUM_MS
        audioMs <= 60_000L -> SWITCH_DEADLINE_AUDIO_LONG_MS
        else -> SWITCH_DEADLINE_AUDIO_EXTRA_LONG_MS
    }

    private fun switchDeadlinePrimaryModeAdjustmentMs(
        primaryVendor: AsrVendor,
        primaryStreaming: Boolean
    ): Long {
        val localPrimary = isLocalAsrVendor(primaryVendor)
        return when {
            localPrimary && primaryStreaming -> SWITCH_DEADLINE_LOCAL_STREAM_ADJUSTMENT_MS
            localPrimary -> SWITCH_DEADLINE_LOCAL_FILE_ADJUSTMENT_MS
            primaryStreaming -> 0L
            else -> SWITCH_DEADLINE_ONLINE_FILE_ADJUSTMENT_MS
        }
    }

    private fun estimatedLazyBackupReadyMs(snapshot: AsrRuntimeVendorSnapshot?): Long {
        val measuredLoadMs = snapshot?.p90LoadMs?.takeIf { it > 0L }
            ?: snapshot?.latestLoadMs?.takeIf { it > 0L }
        return (measuredLoadMs ?: LAZY_ESTIMATED_BACKUP_READY_FALLBACK_MS)
            .coerceAtLeast(LAZY_ESTIMATED_BACKUP_READY_FALLBACK_MS)
    }

    private fun lazyResidencyFactor(sensitivityTier: Int): Double = when (sensitivityTier.coerceIn(0, 2)) {
        0 -> LAZY_RESIDENCY_FACTOR_RELAXED
        2 -> LAZY_RESIDENCY_FACTOR_SENSITIVE
        else -> LAZY_RESIDENCY_FACTOR_BALANCED
    }

    private fun profileFor(vendor: AsrVendor?): TimeoutProfile = when (vendor) {
        // X-ASR 为本地流式，其余三个为设备侧整段推理，分别使用独立超时范围。
        AsrVendor.XAsr -> TimeoutProfile(minTimeoutMs = 10_000L, maxTimeoutMs = 40_000L)
        AsrVendor.SenseVoice -> TimeoutProfile(minTimeoutMs = 10_000L, maxTimeoutMs = 40_000L)
        AsrVendor.FireRedAsr -> TimeoutProfile(minTimeoutMs = 15_000L, maxTimeoutMs = 70_000L)
        AsrVendor.FunAsrNano -> TimeoutProfile(minTimeoutMs = 15_000L, maxTimeoutMs = 90_000L)
        AsrVendor.Qwen3Asr -> TimeoutProfile(minTimeoutMs = 15_000L, maxTimeoutMs = 90_000L)
        AsrVendor.Parakeet -> TimeoutProfile(minTimeoutMs = 15_000L, maxTimeoutMs = 90_000L)

        else -> TimeoutProfile(
            minTimeoutMs = DEFAULT_MIN_TIMEOUT_MS,
            maxTimeoutMs = DEFAULT_MAX_TIMEOUT_MS
        )
    }
}
