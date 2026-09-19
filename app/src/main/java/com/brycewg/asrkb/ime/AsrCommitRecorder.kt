package com.brycewg.asrkb.ime

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.analytics.AnalyticsManager
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.AsrHistoryAudioStore
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.AsrHistoryTimingDiagnostics
import com.brycewg.asrkb.store.AsrHistoryTimingStage
import com.brycewg.asrkb.store.AsrHistoryTimingTrace
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptSelectionStatus
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.util.TextSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 冻结一次 IME 提交的统计快照，并在用户可见固化完成后持久化。
 *
 * 归属模块：IME 提交记录。
 */
internal class AsrCommitRecorder(
    private val context: Context,
    private val prefs: Prefs,
    private val asrManager: AsrSessionManager,
    private val logTag: String
) {
    internal data class PreparedCommit(
        val recordId: String,
        val timestamp: Long,
        val text: String,
        val rawText: String,
        val aiProcessed: Boolean,
        val aiPostMs: Long,
        val aiPostStatus: AsrHistoryStore.AiPostStatus,
        val llmVendorId: String?,
        val promptSelection: PromptSelectionStatus?,
        val chars: Int,
        val audioMs: Long,
        val totalElapsedMs: Long,
        val procMs: Long,
        val vendor: AsrVendor,
        val timingTrace: AsrHistoryTimingTrace?
    )

    private val recordLock = Any()

    /** 在主链路中仅冻结易变会话数据，不执行历史序列化或磁盘写入。 */
    fun prepare(
        text: String,
        rawText: String = text,
        aiProcessed: Boolean,
        aiPostMs: Long = 0L,
        aiPostStatus: AsrHistoryStore.AiPostStatus = AsrHistoryStore.AiPostStatus.NONE,
        llmVendorId: String? = null,
        promptSelection: PromptSelectionStatus? = null,
        historyTiming: AsrSessionManager.HistoryCommitContext? = null
    ): PreparedCommit {
        val chars = try {
            TextSanitizer.countEffectiveChars(text)
        } catch (t: Throwable) {
            Log.w(logTag, "Failed to count committed characters", t)
            text.length
        }
        val recordId = historyTiming?.recordId ?: asrManager.consumeHistoryCommitContext(null)
        val audioMs = asrManager.popLastAudioMsForStats()
        val totalElapsedMs = asrManager.popLastTotalElapsedMsForStats()
        val vendor = try {
            asrManager.peekLastFinalVendorForStats()
        } catch (t: Throwable) {
            Log.w(logTag, "Failed to get final vendor for stats", t)
            prefs.asrVendor
        }
        val timingTrace = historyTiming?.let { timingContext ->
            try {
                timingContext.timing.end(AsrHistoryTimingStage.TEXT_DELIVERY)
                timingContext.timing.complete()
            } catch (t: Throwable) {
                Log.w(logTag, "Failed to complete history timing", t)
                null
            } finally {
                asrManager.consumeHistoryCommitContext(timingContext)
            }
        }
        val procMs = timingTrace
            ?.stageDurationMs(AsrHistoryTimingStage.RECOGNITION)
            ?.takeIf { it > 0L }
            ?: asrManager.getLastRequestDuration()
            ?: 0L
        val prepared = PreparedCommit(
            recordId = recordId,
            timestamp = System.currentTimeMillis(),
            text = text,
            rawText = rawText,
            aiProcessed = aiProcessed,
            aiPostMs = aiPostMs,
            aiPostStatus = aiPostStatus,
            llmVendorId = llmVendorId,
            promptSelection = promptSelection,
            chars = chars,
            audioMs = audioMs,
            totalElapsedMs = timingTrace?.totalElapsedMs ?: totalElapsedMs,
            procMs = procMs,
            vendor = vendor,
            timingTrace = timingTrace
        )
        logTiming(
            "snapshot_prepared",
            mapOf(
                "aiProcessed" to aiProcessed,
                "len" to text.length,
                "recordId" to recordId.take(8)
            )
        )
        return prepared
    }

    /**
     * 在不随调用方生命周期取消的 IO scope 上异步落库。
     *
     * IME 被 setInputMethod 切走后 serviceScope.cancel() 会取消尚未派发的落库协程，
     * 导致已出队的历史记录静默丢失，因此落库必须脱离 IME 生命周期执行。
     */
    fun recordAsync(prepared: PreparedCommit) {
        ioScope.launch { record(prepared) }
    }

    /** 使用调用方生命周期约束的协程异步持久化已冻结快照。 */
    suspend fun record(prepared: PreparedCommit) = withContext(Dispatchers.IO) {
        synchronized(recordLock) {
            persist(prepared)
        }
    }

    /** 保留中断后处理等同步调用路径；新终态链路应使用 [prepare] 后异步调用 [record]。 */
    fun record(
        text: String,
        rawText: String = text,
        aiProcessed: Boolean,
        aiPostMs: Long = 0L,
        aiPostStatus: AsrHistoryStore.AiPostStatus = AsrHistoryStore.AiPostStatus.NONE,
        llmVendorId: String? = null,
        promptSelection: PromptSelectionStatus? = null,
        historyTiming: AsrSessionManager.HistoryCommitContext? = null
    ) {
        val prepared = prepare(
            text = text,
            rawText = rawText,
            aiProcessed = aiProcessed,
            aiPostMs = aiPostMs,
            aiPostStatus = aiPostStatus,
            llmVendorId = llmVendorId,
            promptSelection = promptSelection,
            historyTiming = historyTiming
        )
        synchronized(recordLock) {
            persist(prepared)
        }
    }

    private fun persist(prepared: PreparedCommit) {
        val recordStartedAt = elapsedRealtimeMs()
        logTiming(
            "background_started",
            mapOf(
                "aiProcessed" to prepared.aiProcessed,
                "len" to prepared.text.length,
                "recordId" to prepared.recordId.take(8)
            )
        )
        var historyWritten = false
        try {
            if (!prefs.disableUsageStats) {
                prefs.addAsrChars(prepared.chars)
            }
            AnalyticsManager.recordAsrEvent(
                context = context,
                vendorId = prepared.vendor.id,
                audioMs = prepared.audioMs,
                procMs = prepared.procMs,
                source = "ime",
                aiProcessed = prepared.aiProcessed,
                charCount = prepared.chars
            )
            if (!prefs.disableUsageStats) {
                prefs.recordUsageCommit(
                    "ime",
                    prepared.vendor,
                    prepared.audioMs,
                    prepared.chars,
                    prepared.procMs
                )
            }
            if (!prefs.disableAsrHistory) {
                val store = AsrHistoryStore(context)
                store.add(
                    AsrHistoryStore.AsrHistoryRecord(
                        id = prepared.recordId,
                        timestamp = prepared.timestamp,
                        text = prepared.text,
                        rawText = prepared.rawText,
                        vendorId = prepared.vendor.id,
                        audioMs = prepared.audioMs,
                        totalElapsedMs = prepared.totalElapsedMs,
                        procMs = prepared.procMs,
                        source = "ime",
                        aiProcessed = prepared.aiProcessed,
                        aiPostMs = prepared.aiPostMs,
                        aiPostStatus = prepared.aiPostStatus,
                        llmVendorId = prepared.llmVendorId,
                        promptSelection = prepared.promptSelection,
                        charCount = prepared.chars,
                        timingTrace = prepared.timingTrace
                    )
                )
                historyWritten = true
                AsrHistoryAudioStore.pruneAsync(
                    context,
                    prefs.audioHistoryRetentionCount
                )
                prepared.timingTrace?.let { trace ->
                    AsrHistoryTimingDiagnostics.logSaved("ime", trace)
                }
            } else {
                AsrHistoryAudioStore(context).delete(prepared.recordId)
            }
        } catch (t: Throwable) {
            Log.e(logTag, "Failed to persist ASR commit", t)
            DebugLogManager.logError(
                context = context,
                category = "ime",
                event = "commit_background_failed",
                throwable = t,
                data = mapOf("recordId" to prepared.recordId.take(8))
            )
        } finally {
            logTiming(
                "background_finished",
                mapOf(
                    "aiProcessed" to prepared.aiProcessed,
                    "durationMs" to (elapsedRealtimeMs() - recordStartedAt).coerceAtLeast(0L),
                    "historyWritten" to historyWritten,
                    "recordId" to prepared.recordId.take(8)
                )
            )
        }
    }

    private fun logTiming(event: String, data: Map<String, Any?>) {
        try {
            DebugLogManager.log(category = "ime", event = "commit_timing_$event", data = data)
        } catch (_: Throwable) { }
    }

    private fun elapsedRealtimeMs(): Long = try {
        android.os.SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        0L
    }

    companion object {
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
