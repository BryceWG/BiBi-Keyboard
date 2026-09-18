package com.brycewg.asrkb.ime

import android.content.Context
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrFailReasonCodes
import com.brycewg.asrkb.asr.BackupAwareAsrEngine
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptSelectionStatus
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.store.debug.StreamingPreviewDiag

internal class DictationUseCase(
    private val context: Context,
    private val prefs: Prefs,
    private val asrManager: AsrSessionManager,
    private val inputHelper: InputConnectionHelper,
    private val processingTimeoutController: ProcessingTimeoutController,
    private val postprocessPipeline: PostprocessPipeline,
    private val commitRecorder: AsrCommitRecorder,
    private val uiListenerProvider: () -> KeyboardActionHandler.UiListener?,
    private val getCurrentEditorInfo: () -> EditorInfo?,
    private val isCancelled: (seq: Long) -> Boolean,
    private val consumeAutoEnterOnce: () -> Boolean,
    private val updateSessionContext: ((KeyboardSessionContext) -> KeyboardSessionContext) -> Unit,
    private val transitionToState: (KeyboardState) -> Unit,
    private val transitionToIdle: (keepMessage: Boolean) -> Unit,
    private val transitionToIdleWithTiming: (showBackupUsedHint: Boolean) -> Unit,
    private val scheduleProcessingTimeout: (audioMsOverride: Long?) -> Unit,
    private val onPostprocessUndoAvailable: () -> Unit
) {
    suspend fun handleFinal(
        ic: InputConnection,
        text: String,
        state: KeyboardState.Listening,
        seq: Long
    ) {
        if (text.isBlank()) {
            transitionToIdle(true)
            uiListenerProvider()?.onStatusMessage(
                context.getString(R.string.asr_error_empty_result)
            )
            uiListenerProvider()?.onVibrate()
            return
        }
        val historyTiming = asrManager.acquireNextHistoryCommitContext()
        historyTiming?.timing?.begin(com.brycewg.asrkb.store.AsrHistoryTimingStage.POSTPROCESS)
        if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
            handleWithPostprocess(ic, text, state, seq, historyTiming)
        } else {
            handleWithoutPostprocess(ic, text, state, seq, historyTiming)
        }
    }

    private suspend fun handleWithPostprocess(
        ic: InputConnection,
        text: String,
        state: KeyboardState.Listening,
        seq: Long,
        historyTiming: AsrSessionManager.HistoryCommitContext?
    ) {
        if (isCancelled(seq)) return

        logCommitPath(
            mode = "postprocess",
            seq = seq,
            partial = state.partialText,
            finalText = text,
            stableLen = state.committedStableLen,
            ai = true
        )
        transitionToState(KeyboardState.AiProcessing(rawText = text))
        inputHelper.replaceStreamingPreview(ic, text)

        val postprocessResult = postprocessPipeline.process(
            ic = ic,
            text = text,
            isCancelled = { isCancelled(seq) },
            onFinalReady = {
                historyTiming?.timing?.end(com.brycewg.asrkb.store.AsrHistoryTimingStage.POSTPROCESS)
                historyTiming?.timing?.begin(com.brycewg.asrkb.store.AsrHistoryTimingStage.TEXT_DELIVERY)
                processingTimeoutController.cancel()
            },
            onPostprocFailed = {
                uiListenerProvider()?.onStatusMessage(
                    context.getString(R.string.status_llm_failed_used_raw)
                )
            },
            aiTimingObserver = historyTiming?.let { context ->
                object : com.brycewg.asrkb.util.AsrFinalFilters.AiPostprocessTimingObserver {
                    override fun onAiPostprocessStarted() {
                        context.timing.end(com.brycewg.asrkb.store.AsrHistoryTimingStage.POSTPROCESS)
                        context.timing.begin(com.brycewg.asrkb.store.AsrHistoryTimingStage.AI_POSTPROCESS)
                    }

                    override fun onAiPostprocessFinished() {
                        context.timing.end(com.brycewg.asrkb.store.AsrHistoryTimingStage.AI_POSTPROCESS)
                        context.timing.begin(com.brycewg.asrkb.store.AsrHistoryTimingStage.POSTPROCESS)
                    }

                    override fun onPromptSelectionStarted() {
                        context.timing.end(com.brycewg.asrkb.store.AsrHistoryTimingStage.POSTPROCESS)
                        context.timing.begin(com.brycewg.asrkb.store.AsrHistoryTimingStage.PROMPT_SELECTION)
                    }

                    override fun onPromptSelectionFinished() {
                        context.timing.end(com.brycewg.asrkb.store.AsrHistoryTimingStage.PROMPT_SELECTION)
                        context.timing.begin(com.brycewg.asrkb.store.AsrHistoryTimingStage.POSTPROCESS)
                    }
                }
            },
            // 首次语音识别属于自动流程，允许按开关自动选择预设。
            promptSelectionMode =
            com.brycewg.asrkb.util.AsrFinalFilters.PromptSelectionMode.AUTO_IF_ENABLED
        ) ?: return

        if (isCancelled(seq)) return

        val finalOut = postprocessResult.finalText
        val rawText = postprocessResult.rawText
        val postprocFailed = postprocessResult.postprocFailed
        val aiUsed = postprocessResult.aiUsed
        val aiPostMs = postprocessResult.aiPostMs
        val aiPostStatus = postprocessResult.aiPostStatus

        if (finalOut.isBlank()) {
            inputHelper.setComposingText(ic, "")
            archiveEmptyFilteredResult(historyTiming)
            return
        }

        val finishStartedAt = elapsedRealtimeMs()
        val finishOk = inputHelper.finishComposingText(ic)
        logSolidifyTiming(
            "finish_composing",
            mapOf(
                "durationMs" to (elapsedRealtimeMs() - finishStartedAt).coerceAtLeast(0L),
                "ok" to finishOk,
                "aiUsed" to aiUsed
            )
        )

        var autoEnterSent = false
        if (finalOut.isNotEmpty() && consumeAutoEnterOnce()) {
            try {
                inputHelper.sendEnter(ic, getCurrentEditorInfo())
                autoEnterSent = true
            } catch (t: Throwable) {
                Log.w(TAG, "sendEnter after postprocess failed", t)
            }
        }

        updateSessionContext { prev ->
            prev.copy(
                lastAsrCommitText = finalOut,
                lastPostprocCommit = if (finalOut.isNotEmpty() && finalOut != rawText) {
                    PostprocCommit(finalOut, rawText)
                } else {
                    null
                }
            )
        }

        uiListenerProvider()?.onVibrate()
        notifyInputSolidifiedIfReady(finalOut)

        if (asrManager.isRunning()) {
            transitionToState(KeyboardState.Listening(lockedBySwipe = state.lockedBySwipe))
        } else if (postprocFailed) {
            // 回到 Idle 后再次设置错误提示，避免被 Idle 文案覆盖
            transitionToIdle(false)
            uiListenerProvider()?.onStatusMessage(
                context.getString(R.string.status_llm_failed_used_raw)
            )
        } else if (!autoEnterSent && finalOut != rawText) {
            transitionToIdle(true)
            onPostprocessUndoAvailable()
        } else {
            val usedBackupResult =
                (asrManager.getEngine() as? BackupAwareAsrEngine)
                    ?.wasLastResultFromBackup() == true
            transitionToState(KeyboardState.Processing)
            scheduleProcessingTimeout(null)
            transitionToIdleWithTiming(usedBackupResult)
        }
        logSolidifyTiming("ui_state_ready", mapOf("aiUsed" to aiUsed, "mode" to "postprocess"))
        recordCommitAsync(
            text = finalOut,
            rawText = text,
            aiProcessed = aiUsed,
            aiPostMs = aiPostMs,
            aiPostStatus = aiPostStatus,
            llmVendorId = postprocessResult.llmVendorId,
            promptSelection = postprocessResult.promptSelection,
            historyTiming = historyTiming
        )
    }

    private fun handleWithoutPostprocess(
        ic: InputConnection,
        text: String,
        state: KeyboardState.Listening,
        seq: Long,
        historyTiming: AsrSessionManager.HistoryCommitContext?
    ) {
        val finalToCommit = com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, text)

        if (finalToCommit.isBlank()) {
            historyTiming?.timing?.end(com.brycewg.asrkb.store.AsrHistoryTimingStage.POSTPROCESS)
            archiveEmptyFilteredResult(historyTiming)
            return
        }

        if (isCancelled(seq)) return

        historyTiming?.timing?.end(com.brycewg.asrkb.store.AsrHistoryTimingStage.POSTPROCESS)
        historyTiming?.timing?.begin(com.brycewg.asrkb.store.AsrHistoryTimingStage.TEXT_DELIVERY)

        val partial = state.partialText
        if (!partial.isNullOrEmpty()) {
            val remainderMode = finalToCommit.startsWith(partial)
            logCommitPath(
                mode = if (remainderMode) "remainder" else "rewrite",
                seq = seq,
                partial = partial,
                finalText = finalToCommit,
                stableLen = state.committedStableLen,
                ai = false
            )
            inputHelper.finishComposingText(ic)
            if (remainderMode) {
                val remainder = finalToCommit.substring(partial.length)
                if (remainder.isNotEmpty()) {
                    inputHelper.commitText(ic, remainder)
                }
            } else {
                inputHelper.deleteSurroundingText(ic, partial.length, 0)
                inputHelper.commitText(ic, finalToCommit)
            }
        } else {
            val committedStableLen = state.committedStableLen
            val remainder = if (finalToCommit.length > committedStableLen) {
                finalToCommit.substring(committedStableLen)
            } else {
                ""
            }
            logCommitPath(
                mode = "stable_remainder",
                seq = seq,
                partial = null,
                finalText = finalToCommit,
                stableLen = committedStableLen,
                ai = false,
                extra = mapOf("remainderLen" to remainder.length)
            )
            inputHelper.finishComposingText(ic)
            if (remainder.isNotEmpty()) {
                inputHelper.commitText(ic, remainder)
            }
        }

        updateSessionContext { prev ->
            prev.copy(
                lastAsrCommitText = finalToCommit,
                lastPostprocCommit = null
            )
        }

        if (finalToCommit.isNotEmpty() && consumeAutoEnterOnce()) {
            try {
                inputHelper.sendEnter(ic, getCurrentEditorInfo())
            } catch (t: Throwable) {
                Log.w(TAG, "sendEnter after final failed", t)
            }
        }

        uiListenerProvider()?.onVibrate()
        notifyInputSolidifiedIfReady(finalToCommit)

        if (asrManager.isRunning()) {
            transitionToState(KeyboardState.Listening(lockedBySwipe = state.lockedBySwipe))
        } else {
            val usedBackupResult =
                (asrManager.getEngine() as? BackupAwareAsrEngine)
                    ?.wasLastResultFromBackup() == true
            transitionToState(KeyboardState.Processing)
            scheduleProcessingTimeout(null)
            transitionToIdleWithTiming(usedBackupResult)
        }
        logSolidifyTiming("ui_state_ready", mapOf("aiUsed" to false, "mode" to "simple"))
        recordCommitAsync(
            text = finalToCommit,
            rawText = text,
            aiProcessed = false,
            historyTiming = historyTiming
        )
    }

    private fun archiveEmptyFilteredResult(historyTiming: AsrSessionManager.HistoryCommitContext? = null) {
        asrManager.archiveQueuedHistoryFailure(
            status = AsrHistoryStore.AsrHistoryStatus.FAILED,
            failStage = AsrHistoryStore.AsrHistoryFailStage.RECOGNITION,
            failReasonCode = AsrFailReasonCodes.EMPTY_RESULT,
            context = historyTiming
        )
        transitionToIdle(true)
        uiListenerProvider()?.onStatusMessage(
            context.getString(R.string.asr_error_empty_result)
        )
        uiListenerProvider()?.onVibrate()
    }

    private fun notifyInputSolidifiedIfReady(committedText: String) {
        if (!AsrInputCompletionPolicy.shouldNotifyInputSolidified(
                committedText = committedText,
                sessionStillRunning = asrManager.isRunning()
            )
        ) {
            return
        }
        uiListenerProvider()?.onDictationInputSolidified()
    }

    private fun recordCommitAsync(
        text: String,
        rawText: String,
        aiProcessed: Boolean,
        aiPostMs: Long = 0L,
        aiPostStatus: AsrHistoryStore.AiPostStatus = AsrHistoryStore.AiPostStatus.NONE,
        llmVendorId: String? = null,
        promptSelection: PromptSelectionStatus? = null,
        historyTiming: AsrSessionManager.HistoryCommitContext?
    ) {
        val prepared = try {
            commitRecorder.prepare(
                text = text,
                rawText = rawText,
                aiProcessed = aiProcessed,
                aiPostMs = aiPostMs,
                aiPostStatus = aiPostStatus,
                llmVendorId = llmVendorId,
                promptSelection = promptSelection,
                historyTiming = historyTiming
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to prepare background ASR commit", t)
            try {
                DebugLogManager.logError(
                    category = "ime",
                    event = "commit_snapshot_failed",
                    throwable = t
                )
            } catch (_: Throwable) { }
            return
        }
        logSolidifyTiming(
            "background_record_queued",
            mapOf("aiUsed" to aiProcessed, "recordId" to prepared.recordId.take(8))
        )
        commitRecorder.recordAsync(prepared)
    }

    private fun logSolidifyTiming(event: String, data: Map<String, Any?>) {
        try {
            DebugLogManager.log(category = "ime", event = "solidify_timing_$event", data = data)
        } catch (_: Throwable) { }
    }

    private fun elapsedRealtimeMs(): Long = try {
        android.os.SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        0L
    }

    private fun logCommitPath(
        mode: String,
        seq: Long,
        partial: String?,
        finalText: String,
        stableLen: Int,
        ai: Boolean,
        extra: Map<String, Any?> = emptyMap()
    ) {
        try {
            DebugLogManager.logBase(
                category = "ime",
                event = "commit_path",
                data = extra + StreamingPreviewDiag.shape(partial, finalText) + mapOf(
                    "mode" to mode,
                    "seq" to seq,
                    "partialLen" to (partial?.length ?: 0),
                    "finalLen" to finalText.length,
                    "stableLen" to stableLen,
                    "ai" to ai
                )
            )
        } catch (_: Throwable) { }
        StreamingPreviewDiag.maybeWarnDup(
            category = "ime",
            at = "commit_path",
            prev = partial,
            next = finalText,
            extra = mapOf("mode" to mode, "seq" to seq)
        )
    }

    companion object {
        private const val TAG = "DictationUseCase"
    }
}
