package com.brycewg.asrkb.store

import android.os.SystemClock
import kotlinx.serialization.Serializable

/**
 * Recognition timing data persisted with a history record.
 *
 * Intervals intentionally do not need to cover the full elapsed duration: the gaps represent
 * unclassified scheduling and hand-off time in the history timeline.
 */
@Serializable
enum class AsrHistoryTimingOrigin {
    ORIGINAL,
    RERECOGNITION,
    REPROCESS
}

@Serializable
enum class AsrHistoryTimingStage {
    AUDIO_INPUT,
    RECOGNITION,
    POSTPROCESS,

    /** 自动选择提示词（分类）阶段；仅在真正发起分类请求时出现。 */
    PROMPT_SELECTION,
    AI_POSTPROCESS,
    TEXT_DELIVERY
}

@Serializable
data class AsrHistoryTimingInterval(
    val stage: AsrHistoryTimingStage,
    val startOffsetMs: Long,
    val endOffsetMs: Long
)

@Serializable
data class AsrHistoryTimingTrace(
    val origin: AsrHistoryTimingOrigin,
    val totalElapsedMs: Long,
    val intervals: List<AsrHistoryTimingInterval>,
    val completed: Boolean
) {
    fun stageDurationMs(stage: AsrHistoryTimingStage): Long = intervals.sumOf { interval ->
        if (interval.stage != stage) {
            0L
        } else {
            (interval.endOffsetMs - interval.startOffsetMs).coerceAtLeast(0L)
        }
    }
}

/**
 * Records a single history operation from a monotonic-clock origin without coupling to ASR flow.
 */
class AsrHistoryTimingRecorder(
    private val origin: AsrHistoryTimingOrigin,
    private val startedAtMs: Long = SystemClock.uptimeMillis()
) {
    private val lock = Any()
    private val runningStarts = mutableMapOf<AsrHistoryTimingStage, Long>()
    private val completedIntervals = mutableListOf<AsrHistoryTimingInterval>()
    private var finalTrace: AsrHistoryTimingTrace? = null

    fun begin(stage: AsrHistoryTimingStage) {
        synchronized(lock) {
            if (finalTrace != null) return
            if (runningStarts.containsKey(stage)) return
            val now = elapsedNow()
            closeRunningStagesAt(now)
            runningStarts[stage] = now
        }
    }

    fun end(stage: AsrHistoryTimingStage) {
        synchronized(lock) {
            if (finalTrace != null) return
            val start = runningStarts.remove(stage) ?: return
            completedIntervals += AsrHistoryTimingInterval(
                stage = stage,
                startOffsetMs = start,
                endOffsetMs = elapsedNow().coerceAtLeast(start)
            )
        }
    }

    /** Returns a point-in-time view. Running intervals are closed only in the returned snapshot. */
    fun snapshot(completed: Boolean = false): AsrHistoryTimingTrace = synchronized(lock) {
        finalTrace?.let { return@synchronized it }
        val elapsed = elapsedNow()
        AsrHistoryTimingTrace(
            origin = origin,
            totalElapsedMs = elapsed,
            intervals = buildList {
                addAll(completedIntervals)
                runningStarts.forEach { (stage, start) ->
                    add(
                        AsrHistoryTimingInterval(
                            stage = stage,
                            startOffsetMs = start,
                            endOffsetMs = elapsed.coerceAtLeast(start)
                        )
                    )
                }
            }.sortedWith(
                compareBy<AsrHistoryTimingInterval> { it.startOffsetMs }
                    .thenBy { it.endOffsetMs }
                    .thenBy { it.stage.ordinal }
            ),
            completed = completed
        )
    }

    /** Closes all active intervals and returns the immutable final trace. */
    fun complete(completed: Boolean = true): AsrHistoryTimingTrace = synchronized(lock) {
        finalTrace?.let { return@synchronized it }
        val elapsed = elapsedNow()
        runningStarts.forEach { (stage, start) ->
            completedIntervals += AsrHistoryTimingInterval(
                stage = stage,
                startOffsetMs = start,
                endOffsetMs = elapsed.coerceAtLeast(start)
            )
        }
        runningStarts.clear()
        AsrHistoryTimingTrace(
            origin = origin,
            totalElapsedMs = elapsed,
            intervals = completedIntervals.sortedWith(
                compareBy<AsrHistoryTimingInterval> { it.startOffsetMs }
                    .thenBy { it.endOffsetMs }
                    .thenBy { it.stage.ordinal }
            ),
            completed = completed
        ).also { finalTrace = it }
    }

    /**
     * The timeline is sequential even when callbacks race. Closing an older stage here keeps a
     * late transition from creating overlapping intervals while preserving the real gap before
     * the newly started stage.
     */
    private fun closeRunningStagesAt(endOffsetMs: Long) {
        runningStarts.forEach { (stage, start) ->
            completedIntervals += AsrHistoryTimingInterval(
                stage = stage,
                startOffsetMs = start,
                endOffsetMs = endOffsetMs.coerceAtLeast(start)
            )
        }
        runningStarts.clear()
    }

    private fun elapsedNow(): Long = (SystemClock.uptimeMillis() - startedAtMs).coerceAtLeast(0L)
}
