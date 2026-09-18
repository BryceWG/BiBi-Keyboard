package com.brycewg.asrkb.ime

import android.util.Log
import com.brycewg.asrkb.asr.AsrTimeoutCalculator
import com.brycewg.asrkb.asr.BackupAwareAsrEngine
import com.brycewg.asrkb.asr.LOCAL_MODEL_READY_WAIT_MAX_MS
import com.brycewg.asrkb.asr.ProgressiveRetryStatusOwner
import com.brycewg.asrkb.asr.awaitLocalAsrReady
import com.brycewg.asrkb.asr.isLocalAsrVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.store.getAsrRuntimeStatsSnapshotOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ProcessingTimeoutController(
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val logTag: String,
    private val currentStateProvider: () -> KeyboardState,
    private val opSeqProvider: () -> Long,
    private val audioMsProvider: () -> Long,
    private val backupEngineProvider: () -> BackupAwareAsrEngine?,
    private val pendingRetryOwnerProvider: () -> ProgressiveRetryStatusOwner?,
    private val onTimeout: () -> Unit
) {
    private var job: Job? = null

    fun cancel() {
        val previous = job ?: return
        try {
            previous.cancel()
        } catch (t: Throwable) {
            Log.w(logTag, "Cancel processing timeout failed", t)
        }
        job = null
    }

    fun schedule(audioMsOverride: Long? = null) {
        cancel()

        val audioMs = audioMsOverride ?: safeAudioMs()
        val backupEngine = safeBackupEngine()
        val primaryVendor = backupEngine?.primaryVendor ?: safePrimaryVendor()
        val backupVendor = backupEngine?.backupVendor
        val primarySnapshot = prefs.getAsrRuntimeStatsSnapshotOrNull(primaryVendor, audioMs)
        val backupSnapshot = prefs.getAsrRuntimeStatsSnapshotOrNull(backupVendor, audioMs)
        val timeoutMs = AsrTimeoutCalculator.calculateBackupAwareProcessingTimeoutMs(
            audioMs = audioMs,
            primaryVendor = primaryVendor,
            primaryStatsSnapshot = primarySnapshot,
            backupStrategy = backupEngine?.backupStrategy,
            backupVendor = backupVendor,
            backupStatsSnapshot = backupSnapshot,
            sensitivityTier = safeBackupSensitivityTier(),
            primaryStreaming = backupEngine?.primaryStreamingForSwitchPlan ?: true,
            pendingRetryCount = safePendingRetryCount()
        )

        val shouldDeferForLocalModel = shouldDeferForLocalModel(backupEngine != null)
        job = scope.launch {
            if (shouldDeferForLocalModel) {
                // 本地模型：将超时计时起点推移到“模型加载完成”之后，避免首次加载期间误触发超时
                val ok = awaitLocalAsrReady(prefs, maxWaitMs = LOCAL_MODEL_READY_WAIT_MAX_MS)
                if (!ok) {
                    // 读取配置失败等异常场景：回退为原有策略（不阻塞、继续计时）
                    Log.w(
                        logTag,
                        "awaitLocalAsrReady returned false, fallback to immediate timeout countdown"
                    )
                }
                // 若等待期间状态已变化，则不再继续计时
                if (currentStateProvider() !is KeyboardState.Processing) return@launch
            }
            delay(timeoutMs)
            // 若仍处于 Processing，则回到 Idle
            if (currentStateProvider() is KeyboardState.Processing) {
                debugLog(
                    "processing_timeout_fired",
                    mapOf(
                        "opSeq" to opSeqProvider(),
                        "audioMs" to audioMs,
                        "timeoutMs" to timeoutMs
                    )
                )
                onTimeout()
            }
        }
        debugLog(
            "processing_timeout_scheduled",
            mapOf("opSeq" to opSeqProvider(), "audioMs" to audioMs, "timeoutMs" to timeoutMs)
        )
    }

    private fun safeAudioMs(): Long = try {
        audioMsProvider()
    } catch (_: Throwable) {
        0L
    }

    private fun safeBackupEngine(): BackupAwareAsrEngine? = try {
        backupEngineProvider()
    } catch (_: Throwable) {
        null
    }

    private fun safePendingRetryCount(): Int = try {
        pendingRetryOwnerProvider()?.peekPendingRetryCount() ?: 0
    } catch (_: Throwable) {
        0
    }

    private fun safePrimaryVendor(): com.brycewg.asrkb.asr.AsrVendor? = try {
        prefs.asrVendor
    } catch (_: Throwable) {
        null
    }

    private fun safeBackupSensitivityTier(): Int = try {
        prefs.backupAsrTimeoutSensitivity
    } catch (_: Throwable) {
        1
    }

    private fun shouldDeferForLocalModel(usingBackupEngine: Boolean): Boolean = try {
        !usingBackupEngine && isLocalAsrVendor(prefs.asrVendor)
    } catch (t: Throwable) {
        Log.w(logTag, "Failed to determine local ASR vendor for timeout gating", t)
        false
    }

    private fun debugLog(event: String, data: Map<String, Any?>) {
        try {
            DebugLogManager.log("ime", event, data)
        } catch (_: Throwable) {
        }
    }
}
