package com.brycewg.asrkb.ui.floating

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.analytics.AnalyticsManager
import com.brycewg.asrkb.asr.AsrErrorMessageMapper
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.AsrAccessibilityService
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.SettingsActivity
import com.brycewg.asrkb.ui.floatingball.AsrSessionManager
import com.brycewg.asrkb.ui.floatingball.FloatingBallHoldAccessibilityPromptTracker
import com.brycewg.asrkb.ui.floatingball.FloatingBallHoldPressAction
import com.brycewg.asrkb.ui.floatingball.FloatingBallHoldRecordingTracker
import com.brycewg.asrkb.ui.floatingball.FloatingBallRecordingTapAction
import com.brycewg.asrkb.ui.floatingball.FloatingBallState
import com.brycewg.asrkb.ui.floatingball.FloatingBallStateMachine
import com.brycewg.asrkb.ui.floatingball.FloatingBallTouchHandler
import com.brycewg.asrkb.ui.floatingball.FloatingBallViewManager
import com.brycewg.asrkb.ui.floatingball.FloatingMenuHelper
import com.brycewg.asrkb.ui.floatingball.resolveFloatingBallHoldPressAction
import com.brycewg.asrkb.ui.floatingball.resolveFloatingBallRecordingTapAction
import com.brycewg.asrkb.util.HapticFeedbackHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal class FloatingAsrInteractionController(
    private val context: Context,
    private val prefs: Prefs,
    private val viewManager: FloatingBallViewManager,
    private val menuController: FloatingMenuController,
    private val stateMachine: FloatingBallStateMachine,
    private val notifier: UserNotifier,
    private val scope: CoroutineScope,
    private val tag: String,
    private val isImeVisible: () -> Boolean,
    private val startRecordingForeground: () -> Boolean,
    private val stopRecordingForeground: () -> Unit
) : AsrSessionManager.AsrSessionListener,
    FloatingBallTouchHandler.TouchEventListener {
    private enum class RecordingStartFromBallResult {
        Started,
        MissingAccessibility,
        Failed
    }

    companion object {
        private const val EDGE_HANDLE_AUTO_HIDE_DELAY_MS = 2500L
        private const val AMPLITUDE_DISPATCH_INTERVAL_MS = 32L
    }

    lateinit var asrSessionManager: AsrSessionManager
    lateinit var applyVisibility: (String) -> Unit

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var touchActiveGuard: Boolean = false

    private var edgeHandleAutoHideRunnable: Runnable? = null
    private var postCommitPartialHideRunnable: Runnable? = null
    private var postErrorPartialHideRunnable: Runnable? = null
    private var postErrorResetStateRunnable: Runnable? = null
    private var volumeKeySessionActive: Boolean = false
    private val holdRecordingTracker = FloatingBallHoldRecordingTracker()
    private val holdAccessibilityPromptTracker = FloatingBallHoldAccessibilityPromptTracker()
    private var pendingAmplitude: Float? = null
    private var amplitudeDispatchRunnable: Runnable? = null
    private var lastAmplitudeDispatchUptimeMs: Long = 0L

    fun isForceVisibleActive(): Boolean = menuController.isForceVisibleMenuActive() ||
        stateMachine.isMoveMode ||
        touchActiveGuard

    fun cleanup() {
        cancelEdgeHandleAutoHide()
        cancelPostCommitPartialHide()
        cancelPostErrorPartialHide()
        cancelPostErrorResetState()
        cancelAmplitudeDispatch()
        holdRecordingTracker.clear()
        holdAccessibilityPromptTracker.clear()
        stopRecordingForeground()
        try {
            menuController.hideAll()
        } catch (e: Throwable) {
            Log.w(tag, "Failed to hide menus in cleanup", e)
        }
    }

    private fun updateVisibilityByPref(src: String = "update_visibility") {
        if (!this::applyVisibility.isInitialized) return
        applyVisibility(src)
    }

    // ==================== 录音控制 ====================

    fun onVolumeKeyStart() {
        if (stateMachine.isRecording || stateMachine.isProcessing) return
        if (startRecording(fromVolumeKey = true)) showVolumeKeyStatusToast(R.string.toast_volume_key_recording_started)
    }

    fun onVolumeKeyStop() {
        if (!stateMachine.isRecording) return
        stopRecording()
        showVolumeKeyStatusToast(R.string.toast_volume_key_recording_stopped)
    }

    fun onVolumeKeyToggle() {
        if (stateMachine.isRecording) {
            stopRecording()
            showVolumeKeyStatusToast(R.string.toast_volume_key_recording_stopped)
            return
        }
        if (stateMachine.isProcessing) return
        if (startRecording(fromVolumeKey = true)) showVolumeKeyStatusToast(R.string.toast_volume_key_recording_started)
    }

    fun stopVolumeKeyRecordingOnImeHidden() {
        if (!volumeKeySessionActive) return
        if (!prefs.volumeKeyStopOnImeHidden) return
        if (!stateMachine.isRecording) return
        stopRecording()
    }

    private fun startRecording(fromVolumeKey: Boolean = false): Boolean {
        Log.d(tag, "startRecording called")
        cancelEdgeHandleAutoHide()

        if (!canStartRecording()) return false

        if (!startRecordingForeground()) {
            Log.w(tag, "Failed to enter microphone foreground state")
            logRecordingStartPath(
                event = "microphone_fgs_start_failed",
                fromVolumeKey = fromVolumeKey,
                success = false
            )
            showToast(context.getString(R.string.toast_floating_recording_foreground_failed))
            return false
        }

        logRecordingStartPath(
            event = "microphone_fgs_started",
            fromVolumeKey = fromVolumeKey,
            success = true
        )

        return startAsrRecording(fromVolumeKey)
    }

    private fun canStartRecording(): Boolean {
        if (!hasRecordAudioPermission()) {
            Log.w(tag, "No record audio permission")
            showToast(context.getString(R.string.asr_error_mic_permission_denied))
            return false
        }

        if (!prefs.hasAsrKeys()) {
            Log.w(tag, "No ASR keys configured")
            showToast(context.getString(R.string.hint_need_keys))
            return false
        }

        return true
    }

    private fun startAsrRecording(fromVolumeKey: Boolean): Boolean {
        // 开始录音前切换为激活态图标
        try {
            viewManager.getBallView()?.findViewById<android.widget.ImageView>(R.id.ballIcon)
                ?.setImageResource(R.drawable.microphone_floatingball)
        } catch (e: Throwable) {
            Log.w(tag, "Failed to reset icon to mic", e)
        }

        volumeKeySessionActive = fromVolumeKey
        logRecordingStartPath(
            event = "asr_start_requested",
            fromVolumeKey = fromVolumeKey,
            success = true
        )
        try {
            asrSessionManager.startRecording()
        } catch (t: Throwable) {
            Log.e(tag, "Failed to start ASR recording", t)
            volumeKeySessionActive = false
            stopRecordingForeground()
            showToast(
                context.getString(
                    R.string.floating_asr_error,
                    t.message ?: t.javaClass.simpleName
                )
            )
            return false
        }
        updateVisibilityByPref("start_recording")
        return true
    }

    private fun logRecordingStartPath(
        event: String,
        fromVolumeKey: Boolean,
        success: Boolean
    ) {
        val data = mapOf(
            "method" to "service_microphone_fgs",
            "fromVolumeKey" to fromVolumeKey,
            "success" to success
        )
        Log.i(tag, "recording_start_path event=$event method=service_microphone_fgs fromVolumeKey=$fromVolumeKey success=$success")
        DebugLogManager.logPersistent(context, "float", event, data)
    }

    private fun stopRecording() {
        Log.d(tag, "stopRecording called")
        cancelEdgeHandleAutoHide()
        volumeKeySessionActive = false
        asrSessionManager.stopRecording()
        stopRecordingForeground()
        updateVisibilityByPref("stop_recording")
    }

    private fun cancelCurrentSession() {
        Log.d(tag, "cancelCurrentSession called")
        cancelEdgeHandleAutoHide()
        volumeKeySessionActive = false
        asrSessionManager.cancelSession()
        stopRecordingForeground()
        updateVisibilityByPref("cancel_session")
    }

    // ==================== 延迟任务 ====================

    private fun cancelDelayedRunnable(runnable: Runnable?, label: String): Runnable? {
        val r = runnable ?: return null
        try {
            handler.removeCallbacks(r)
        } catch (e: Throwable) {
            Log.w(tag, "Failed to cancel $label runnable", e)
        }
        return null
    }

    private fun cancelEdgeHandleAutoHide() {
        edgeHandleAutoHideRunnable =
            cancelDelayedRunnable(edgeHandleAutoHideRunnable, "edge-handle auto hide")
    }

    private fun cancelPostCommitPartialHide() {
        postCommitPartialHideRunnable =
            cancelDelayedRunnable(postCommitPartialHideRunnable, "post-commit partial hide")
    }

    private fun cancelPostErrorPartialHide() {
        postErrorPartialHideRunnable =
            cancelDelayedRunnable(postErrorPartialHideRunnable, "post-error partial hide")
    }

    private fun cancelPostErrorResetState() {
        postErrorResetStateRunnable =
            cancelDelayedRunnable(postErrorResetStateRunnable, "post-error reset state")
    }

    private fun cancelAmplitudeDispatch() {
        amplitudeDispatchRunnable =
            cancelDelayedRunnable(amplitudeDispatchRunnable, "amplitude dispatch")
        pendingAmplitude = null
    }

    private fun enqueueAmplitude(amplitude: Float) {
        pendingAmplitude = amplitude.coerceIn(0f, 1f)
        if (amplitudeDispatchRunnable != null) return

        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastAmplitudeDispatchUptimeMs
        val delay = (AMPLITUDE_DISPATCH_INTERVAL_MS - elapsed).coerceAtLeast(0L)
        val runnable = Runnable {
            amplitudeDispatchRunnable = null
            lastAmplitudeDispatchUptimeMs = SystemClock.uptimeMillis()
            val nextAmplitude = pendingAmplitude ?: return@Runnable
            pendingAmplitude = null
            if (stateMachine.isRecording) {
                viewManager.updateAmplitude(nextAmplitude)
            }
        }
        amplitudeDispatchRunnable = runnable
        try {
            handler.postDelayed(runnable, delay)
        } catch (e: Throwable) {
            amplitudeDispatchRunnable = null
            Log.w(tag, "Failed to schedule amplitude dispatch", e)
        }
    }

    private fun schedulePostCommitPartialHide() {
        cancelPostCommitPartialHide()
        val runnable = Runnable {
            if (stateMachine.isIdle &&
                !prefs.floatingSwitcherOnlyWhenImeVisible &&
                !isImeVisible()
            ) {
                try {
                    viewManager.animateHideToEdgePartialIfNeeded()
                } catch (e: Throwable) {
                    Log.w(tag, "Failed to partial hide after commit", e)
                }
            }
        }
        postCommitPartialHideRunnable = runnable
        try {
            handler.postDelayed(runnable, 3000L)
        } catch (e: Throwable) {
            Log.w(tag, "Failed to schedule post-commit partial hide", e)
        }
    }

    private fun schedulePostErrorPartialHide() {
        cancelPostErrorPartialHide()
        val runnable = Runnable {
            if (!prefs.floatingSwitcherOnlyWhenImeVisible && !isImeVisible()) {
                try {
                    viewManager.animateHideToEdgePartialIfNeeded()
                } catch (e: Throwable) {
                    Log.w(tag, "Failed to partial hide after error", e)
                }
            }
        }
        postErrorPartialHideRunnable = runnable
        try {
            handler.postDelayed(runnable, 3000L)
        } catch (e: Throwable) {
            Log.w(tag, "Failed to schedule partial hide after error", e)
        }
    }

    private fun schedulePostErrorResetState() {
        cancelPostErrorResetState()
        val runnable = Runnable {
            if (!stateMachine.isError) return@Runnable
            try {
                stateMachine.transitionTo(FloatingBallState.Idle)
                viewManager.updateStateVisual(FloatingBallState.Idle)
            } catch (e: Throwable) {
                Log.w(tag, "Failed to reset state to Idle after error animation", e)
            }
            updateVisibilityByPref("post_error_reset")
        }
        postErrorResetStateRunnable = runnable
        try {
            handler.postDelayed(runnable, 1500L)
        } catch (e: Throwable) {
            Log.w(tag, "Failed to schedule state reset after error", e)
        }
    }

    private fun scheduleEdgeHandleAutoHide() {
        cancelEdgeHandleAutoHide()
        if (try {
                prefs.floatingSwitcherOnlyWhenImeVisible
            } catch (_: Throwable) {
                false
            }
        ) {
            return
        }

        val runnable = Runnable {
            try {
                val completionActive = try {
                    viewManager.isCompletionTickActive()
                } catch (
                    _: Throwable
                ) {
                    false
                }
                val imeVisible = isImeVisible()
                val forceVisible = isForceVisibleActive()
                if (!imeVisible &&
                    stateMachine.isIdle &&
                    !stateMachine.isRecording &&
                    !stateMachine.isProcessing &&
                    !completionActive &&
                    !forceVisible &&
                    !viewManager.isEdgeHandleVisible()
                ) {
                    viewManager.animateHideToEdgePartialIfNeeded()
                }
            } catch (e: Throwable) {
                Log.w(tag, "Failed to auto hide after edge-handle reveal", e)
            }
        }
        edgeHandleAutoHideRunnable = runnable
        try {
            handler.postDelayed(runnable, EDGE_HANDLE_AUTO_HIDE_DELAY_MS)
        } catch (e: Throwable) {
            Log.w(tag, "Failed to schedule edge-handle auto hide", e)
        }
    }

    // ==================== AsrSessionManager.AsrSessionListener ====================

    override fun onSessionStateChanged(state: FloatingBallState) {
        stateMachine.transitionTo(state)
        val recordingActive = isRecordingCaptureActive()
        holdRecordingTracker.onRecordingActivityChanged(recordingActive)
        if (state !is FloatingBallState.Recording && !recordingActive) {
            cancelAmplitudeDispatch()
            stopRecordingForeground()
        }
        handler.post {
            viewManager.updateStateVisual(state)
            // 录音/处理态：保证浮现；Idle 半隐/显隐策略统一交由 visibilityCoordinator 处理
            try {
                when (state) {
                    is FloatingBallState.Recording, is FloatingBallState.Processing ->
                        viewManager.animateRevealFromEdgeIfNeeded()
                    else -> Unit
                }
            } catch (e: Throwable) {
                Log.w(tag, "Failed to apply edge reveal on state change", e)
            }
            updateVisibilityByPref("session_state_changed")
        }
    }

    override fun onResultCommitted(text: String, success: Boolean) {
        volumeKeySessionActive = false
        if (!stateMachine.isRecording) {
            stopRecordingForeground()
        }
        handler.post {
            if (success) {
                viewManager.showCompletionTick()
            }
            if (text.isNotBlank() || success) {
                persistFloatingCommit(text)
            }
            if (success) {
                schedulePostCommitPartialHide()
            }
        }
    }

    private fun persistFloatingCommit(text: String) {
        try {
            val audioMs = asrSessionManager.popLastAudioMsForStats()
            val historyRecordId = asrSessionManager.popLastHistoryRecordId()
            val historyRawText = asrSessionManager.popLastHistoryRawText() ?: text
            val totalElapsedMs = asrSessionManager.popLastTotalElapsedMsForStats()
            val procMs = asrSessionManager.getLastRequestDuration() ?: 0L
            val chars = com.brycewg.asrkb.util.TextSanitizer.countEffectiveChars(text)
            val ai = try {
                asrSessionManager.wasLastAiUsed()
            } catch (_: Throwable) {
                false
            }
            val aiPostMs = try {
                asrSessionManager.getLastAiPostMs()
            } catch (_: Throwable) {
                0L
            }
            val aiPostStatus = try {
                asrSessionManager.getLastAiPostStatus()
            } catch (_: Throwable) {
                if (ai) {
                    com.brycewg.asrkb.store.AsrHistoryStore.AiPostStatus.SUCCESS
                } else {
                    com.brycewg.asrkb.store.AsrHistoryStore.AiPostStatus.NONE
                }
            }
            val llmVendorId = try {
                asrSessionManager.getLastLlmVendorId()
            } catch (_: Throwable) {
                null
            }
            val promptSelection = try {
                asrSessionManager.getLastPromptSelection()
            } catch (_: Throwable) {
                null
            }
            val vendorForRecord = try {
                asrSessionManager.peekLastFinalVendorForStats()
            } catch (t: Throwable) {
                Log.w(tag, "Failed to get final vendor for stats", t)
                prefs.asrVendor
            }
            val timingTrace = asrSessionManager.completeLastHistoryTiming()
            val disableHistory = prefs.disableAsrHistory
            val disableStats = prefs.disableUsageStats
            val retention = prefs.audioHistoryRetentionCount
            scope.launch(Dispatchers.IO) {
                try {
                    AnalyticsManager.recordAsrEvent(
                        context = context,
                        vendorId = vendorForRecord.id,
                        audioMs = audioMs,
                        procMs = procMs,
                        source = "floating",
                        aiProcessed = ai,
                        charCount = chars
                    )
                    if (!disableStats) {
                        prefs.recordUsageCommit(
                            "floating",
                            vendorForRecord,
                            audioMs,
                            chars,
                            procMs
                        )
                    }
                    if (!disableHistory) {
                        val store = com.brycewg.asrkb.store.AsrHistoryStore(context)
                        store.add(
                            com.brycewg.asrkb.store.AsrHistoryStore.AsrHistoryRecord(
                                id = historyRecordId,
                                timestamp = System.currentTimeMillis(),
                                text = text,
                                rawText = historyRawText,
                                vendorId = vendorForRecord.id,
                                audioMs = audioMs,
                                totalElapsedMs = timingTrace?.totalElapsedMs ?: totalElapsedMs,
                                procMs = procMs,
                                source = "floating",
                                aiProcessed = ai,
                                aiPostMs = aiPostMs,
                                aiPostStatus = aiPostStatus,
                                llmVendorId = llmVendorId,
                                charCount = chars,
                                timingTrace = timingTrace,
                                promptSelection = promptSelection
                            )
                        )
                        timingTrace?.let { trace ->
                            com.brycewg.asrkb.store.AsrHistoryTimingDiagnostics.logSaved(
                                "floating",
                                trace
                            )
                        }
                        com.brycewg.asrkb.store.AsrHistoryAudioStore.pruneAsync(
                            context,
                            retention
                        )
                    } else {
                        com.brycewg.asrkb.store.AsrHistoryAudioStore(context).delete(historyRecordId)
                    }
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to record usage stats (floating)", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(tag, "Failed to snapshot floating commit stats", t)
        }
    }

    override fun onError(message: String) {
        volumeKeySessionActive = false
        stopRecordingForeground()
        cancelAmplitudeDispatch()
        handler.post {
            val mapped = AsrErrorMessageMapper.map(context, message)
            if (mapped != null) {
                showToast(mapped)
            } else {
                showToast(context.getString(R.string.floating_asr_error, message))
            }

            schedulePostErrorPartialHide()
            schedulePostErrorResetState()
        }
    }

    override fun onAmplitude(amplitude: Float) {
        try {
            handler.post {
                enqueueAmplitude(amplitude)
            }
        } catch (e: Throwable) {
            Log.w(tag, "Failed to post amplitude update", e)
        }
    }

    // ==================== FloatingBallTouchHandler.TouchEventListener ====================

    override fun onSingleTap() {
        cancelEdgeHandleAutoHide()
        val imeVisible = isImeVisible()

        if (stateMachine.isMoveMode) {
            stateMachine.transitionTo(FloatingBallState.Idle)
            viewManager.getBallView()?.let {
                try {
                    viewManager.animateSnapToEdge(it) {
                        try {
                            if (!prefs.floatingSwitcherOnlyWhenImeVisible && !imeVisible) {
                                viewManager.animateHideToEdgePartialIfNeeded()
                            }
                        } catch (e: Throwable) {
                            Log.w(tag, "Failed to partial hide after exit move mode", e)
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(tag, "Failed to animate snap, falling back", e)
                    viewManager.snapToEdge(it)
                    try {
                        if (!prefs.floatingSwitcherOnlyWhenImeVisible && !imeVisible) {
                            viewManager.animateHideToEdgePartialIfNeeded()
                        }
                    } catch (ex: Throwable) {
                        Log.w(tag, "Failed to partial hide after fallback snap", ex)
                    }
                }
            }
            hideRadialMenu()
            hideVendorMenu()
            return
        }

        if (stateMachine.isRecording) {
            if (resolveFloatingBallRecordingTapAction(
                    isRecording = true,
                    holdToRecordEnabled = isHoldToRecordEnabled()
                ) == FloatingBallRecordingTapAction.StopRecording
            ) {
                stopRecording()
            }
            return
        }

        if (stateMachine.isProcessing) {
            cancelCurrentSession()
            return
        }

        if (revealEdgeHandleIfNeeded()) return
        when (
            resolveFloatingBallRecordingTapAction(
                isRecording = false,
                holdToRecordEnabled = isHoldToRecordEnabled()
            )
        ) {
            FloatingBallRecordingTapAction.StartRecording -> startRecordingFromBall()
            FloatingBallRecordingTapAction.StopRecording,
            FloatingBallRecordingTapAction.None -> Unit
        }
    }

    private fun isHoldToRecordEnabled(): Boolean = try {
        prefs.floatingBallHoldToRecordEnabled
    } catch (e: Throwable) {
        Log.w(tag, "Failed to read floating hold-to-record preference", e)
        false
    }

    private fun isImeBridgeEnabled(): Boolean = try {
        prefs.floatingImeBridgeEnabled
    } catch (e: Throwable) {
        Log.w(tag, "Failed to read IME bridge preference", e)
        false
    }

    override fun onLongPress() {
        cancelEdgeHandleAutoHide()
        holdAccessibilityPromptTracker.clear()
        touchActiveGuard = true
        updateVisibilityByPref("long_press")
        if (!isHoldToRecordEnabled()) return
        if (stateMachine.isMoveMode) return
        when (
            resolveFloatingBallHoldPressAction(
                isRecording = stateMachine.isRecording,
                isProcessing = stateMachine.isProcessing,
                isEdgeHandleVisible = viewManager.isEdgeHandleVisible()
            )
        ) {
            FloatingBallHoldPressAction.StartRecording -> {
                when (startRecordingFromBall(openAccessibilitySettingsOnMissing = false)) {
                    RecordingStartFromBallResult.Started -> {
                        holdRecordingTracker.markStartResult(
                            started = true,
                            isRecordingActive = isRecordingCaptureActive()
                        )
                    }
                    RecordingStartFromBallResult.MissingAccessibility ->
                        holdAccessibilityPromptTracker.markPending()
                    RecordingStartFromBallResult.Failed -> Unit
                }
            }
            FloatingBallHoldPressAction.StopRecording -> stopRecording()
            FloatingBallHoldPressAction.CancelProcessing -> cancelCurrentSession()
            FloatingBallHoldPressAction.RevealEdge -> revealEdgeHandleIfNeeded()
            FloatingBallHoldPressAction.None -> Unit
        }
    }

    override fun onLongPressRelease() {
        val shouldStop = holdRecordingTracker.consumeStopOnRelease(isRecordingCaptureActive())
        if (shouldStop) {
            stopRecording()
        }
        if (holdAccessibilityPromptTracker.consumeOnRelease()) {
            openAccessibilitySettings()
        }
        touchActiveGuard = false
        updateVisibilityByPref("long_press_release")
    }

    override fun onLongPressGestureMovedBeyondSlop() {
        holdAccessibilityPromptTracker.clear()
    }

    override fun onLongPressCancel() {
        cancelHoldRecordingForGesture()
        touchActiveGuard = false
        updateVisibilityByPref("long_press_cancel")
    }

    override fun onLongPressDragStart(initialRawX: Float, initialRawY: Float) {
        cancelHoldRecordingForGesture()
        touchActiveGuard = true
        cancelEdgeHandleAutoHide()
        try {
            viewManager.animateRevealFromEdgeIfNeeded()
        } catch (e: Throwable) {
            Log.w(tag, "Failed to reveal on long-press drag start", e)
        }

        if (menuController.isDragSessionActive()) return

        val center = viewManager.getBallCenterSnapshot()
        val alpha = getMenuAlphaOrDefault()
        val items = buildRadialMenuItems()

        menuController.showRadialMenuForDrag(center, alpha, items) {
            touchActiveGuard = false
            updateVisibilityByPref("radial_drag_dismiss")
        }
        updateVisibilityByPref("radial_drag_show")
        menuController.updateDragHover(initialRawX, initialRawY)
    }

    override fun onLongPressDragMove(rawX: Float, rawY: Float) {
        menuController.updateDragHover(rawX, rawY)
    }

    override fun onLongPressDragRelease(rawX: Float, rawY: Float) {
        menuController.performDragSelectionAt(rawX, rawY)
    }

    override fun onMoveStarted() {
        cancelHoldRecordingForGesture()
        touchActiveGuard = true
        cancelEdgeHandleAutoHide()
        if (!viewManager.isEdgeHandleVisible()) {
            try {
                viewManager.animateRevealFromEdgeIfNeeded()
            } catch (e: Throwable) {
                Log.w(tag, "Failed to reveal on move start", e)
            }
        }
        updateVisibilityByPref("move_started")
    }

    override fun onMoveEnded() {
        touchActiveGuard = false
        val imeVisible = isImeVisible()

        if (stateMachine.isMoveMode) {
            stateMachine.transitionTo(FloatingBallState.Idle)
            try {
                viewManager.updateStateVisual(FloatingBallState.Idle)
            } catch (e: Throwable) {
                Log.w(tag, "Failed to update state visual after move end", e)
            }
        }

        try {
            if (!stateMachine.isMoveMode &&
                !stateMachine.isRecording &&
                !stateMachine.isProcessing &&
                !prefs.floatingSwitcherOnlyWhenImeVisible &&
                !imeVisible
            ) {
                viewManager.animateHideToEdgePartialIfNeeded()
            }
        } catch (e: Throwable) {
            Log.w(tag, "Failed to partial hide after move end", e)
        }
        updateVisibilityByPref("move_ended")
    }

    override fun onDragCancelled() {
        menuController.dismissDragSession()
        touchActiveGuard = false
        updateVisibilityByPref("drag_cancelled")
    }

    private fun startRecordingFromBall(
        openAccessibilitySettingsOnMissing: Boolean = true
    ): RecordingStartFromBallResult {
        if (!AsrAccessibilityService.isEnabled() && !isImeBridgeEnabled()) {
            Log.w(tag, "Accessibility service not enabled")
            showToast(context.getString(R.string.toast_need_accessibility_perm))
            if (openAccessibilitySettingsOnMissing) {
                openAccessibilitySettings()
            }
            return RecordingStartFromBallResult.MissingAccessibility
        }

        try {
            viewManager.animateRevealFromEdgeIfNeeded()
        } catch (e: Throwable) {
            Log.w(tag, "Failed to reveal before recording", e)
        }

        return if (startRecording()) {
            RecordingStartFromBallResult.Started
        } else {
            RecordingStartFromBallResult.Failed
        }
    }

    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(tag, "Failed to open accessibility settings", e)
        }
    }

    private fun revealEdgeHandleIfNeeded(): Boolean {
        if (!viewManager.isEdgeHandleVisible()) return false
        try {
            viewManager.animateRevealFromEdgeIfNeeded()
        } catch (e: Throwable) {
            Log.w(tag, "Failed to reveal from edge handle", e)
        }
        scheduleEdgeHandleAutoHide()
        return true
    }

    private fun cancelHoldRecordingForGesture() {
        holdAccessibilityPromptTracker.clear()
        val shouldCancel = holdRecordingTracker.consumeCancelForGesture(isRecordingCaptureActive())
        if (shouldCancel) {
            cancelCurrentSession()
        }
    }

    private fun isRecordingCaptureActive(): Boolean {
        if (!this::asrSessionManager.isInitialized) return false
        return asrSessionManager.isRecordingActive()
    }

    // ==================== 菜单动作 ====================

    private fun hideRadialMenu() {
        menuController.hideRadialMenu()
        updateVisibilityByPref("hide_radial_menu")
    }

    private fun hideVendorMenu() {
        menuController.hideVendorMenu()
        updateVisibilityByPref("hide_vendor_menu")
    }

    private fun onPickPromptPresetFromMenu() {
        touchActiveGuard = true
        hideVendorMenu()

        val center = viewManager.getBallCenterSnapshot()
        val alpha = getMenuAlphaOrDefault()

        val presets = try {
            prefs.getPromptPresets()
        } catch (e: Throwable) {
            Log.e(tag, "Failed to get prompt presets", e)
            emptyList()
        }
        val active = try {
            prefs.activePromptId
        } catch (e: Throwable) {
            Log.w(tag, "Failed to get active prompt ID", e)
            ""
        }

        val entries = presets.map { p ->
            Triple(p.title, p.id == active) {
                try {
                    prefs.activePromptId = p.id
                    showToast(context.getString(R.string.switched_preset, p.title))
                } catch (e: Throwable) {
                    Log.e(tag, "Failed to switch prompt preset", e)
                }
                Unit
            }
        }

        menuController.showListPanel(
            anchorCenter = center,
            alpha = alpha,
            title = context.getString(R.string.label_llm_prompt_presets),
            entries = entries
        ) {
            touchActiveGuard = false
            updateVisibilityByPref("prompt_panel_dismiss")
        }
    }

    private fun onPickAsrVendor() {
        touchActiveGuard = true
        hideVendorMenu()

        val center = viewManager.getBallCenterSnapshot()
        val alpha = getMenuAlphaOrDefault()

        val vendors = AsrVendorUi.pairs(context)
        val cur = try {
            prefs.asrVendor
        } catch (e: Throwable) {
            Log.w(tag, "Failed to get current vendor", e)
            AsrVendor.Volc
        }

        val entries = vendors.map { (v, name) ->
            Triple(name, v == cur) {
                try {
                    val old = try {
                        prefs.asrVendor
                    } catch (e: Throwable) {
                        Log.w(tag, "Failed to get old vendor", e)
                        AsrVendor.Volc
                    }
                    if (v != old) {
                        prefs.asrVendor = v
                        // 离开本地引擎时卸载缓存识别器，释放内存
                        if (old == AsrVendor.SenseVoice && v != AsrVendor.SenseVoice) {
                            try {
                                com.brycewg.asrkb.asr.unloadSenseVoiceRecognizer()
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to unload SenseVoice", e)
                            }
                        }
                        if (old == AsrVendor.FunAsrNano && v != AsrVendor.FunAsrNano) {
                            try {
                                com.brycewg.asrkb.asr.unloadFunAsrNanoRecognizer()
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to unload FunASR Nano", e)
                            }
                        }
                        if (old == AsrVendor.Qwen3Asr && v != AsrVendor.Qwen3Asr) {
                            try {
                                com.brycewg.asrkb.asr.unloadQwen3AsrRecognizer()
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to unload Qwen3-ASR", e)
                            }
                        }
                        if (old == AsrVendor.Parakeet && v != AsrVendor.Parakeet) {
                            try {
                                com.brycewg.asrkb.asr.unloadParakeetRecognizer()
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to unload Parakeet", e)
                            }
                        }
                        if (old == AsrVendor.FireRedAsr && v != AsrVendor.FireRedAsr) {
                            try {
                                com.brycewg.asrkb.asr.unloadFireRedAsrRecognizer()
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to unload FireRedASR", e)
                            }
                        }
                        if (old == AsrVendor.XAsr && v != AsrVendor.XAsr) {
                            try {
                                com.brycewg.asrkb.asr.unloadXAsrRecognizer()
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to unload X-ASR", e)
                            }
                        }

                        // 切换到本地引擎且启用预加载时，触发预加载以降低首次等待
                        if (v == AsrVendor.SenseVoice && prefs.svPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadSenseVoiceIfConfigured(context, prefs)
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to preload SenseVoice", e)
                            }
                        }
                        if (v == AsrVendor.FunAsrNano && prefs.fnPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadFunAsrNanoIfConfigured(context, prefs)
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to preload FunASR Nano", e)
                            }
                        }
                        if (v == AsrVendor.Qwen3Asr && prefs.qwPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadQwen3AsrIfConfigured(context, prefs)
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to preload Qwen3-ASR", e)
                            }
                        }
                        if (v == AsrVendor.Parakeet && prefs.pkPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadParakeetIfConfigured(context, prefs)
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to preload Parakeet", e)
                            }
                        }
                        if (v == AsrVendor.FireRedAsr && prefs.frPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadFireRedAsrIfConfigured(context, prefs)
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to preload FireRedASR", e)
                            }
                        }
                        if (v == AsrVendor.XAsr && prefs.xAsrPreloadEnabled) {
                            try {
                                com.brycewg.asrkb.asr.preloadXAsrIfConfigured(context, prefs)
                            } catch (e: Throwable) {
                                Log.e(tag, "Failed to preload X-ASR", e)
                            }
                        }
                    }
                    showToast(name)
                } catch (e: Throwable) {
                    Log.e(tag, "Failed to switch ASR vendor", e)
                }
                Unit
            }
        }

        menuController.showListPanel(
            anchorCenter = center,
            alpha = alpha,
            title = context.getString(R.string.label_choose_asr_vendor),
            entries = entries
        ) {
            touchActiveGuard = false
            updateVisibilityByPref("vendor_panel_dismiss")
        }
    }

    private fun invokeImePickerFromMenu() {
        hideVendorMenu()
        invokeImePicker()
    }

    private fun enableMoveModeFromMenu() {
        stateMachine.transitionTo(FloatingBallState.MoveMode)
        hideVendorMenu()
        showToast(context.getString(R.string.toast_move_mode_on))
    }

    private fun togglePostprocFromMenu() {
        try {
            val newVal = !prefs.postProcessEnabled
            prefs.postProcessEnabled = newVal
            val msg = context.getString(
                R.string.status_postproc,
                if (newVal) {
                    context.getString(
                        R.string.toggle_on
                    )
                } else {
                    context.getString(R.string.toggle_off)
                }
            )
            showToast(msg)
        } catch (e: Throwable) {
            Log.e(tag, "Failed to toggle postproc", e)
        }
    }

    private fun showHistoryPanelFromMenu() {
        touchActiveGuard = true
        hideVendorMenu()

        val center = viewManager.getBallCenterSnapshot()
        val alpha = getMenuAlphaOrDefault()
        val emptyText = context.getString(R.string.empty_history)
        val title = context.getString(R.string.btn_open_asr_history)

        scope.launch(Dispatchers.IO) {
            val texts: List<String> = try {
                com.brycewg.asrkb.store.AsrHistoryStore(context)
                    .listRecent(100)
                    .map { it.text }
                    .filter { it.isNotBlank() }
            } catch (e: Throwable) {
                Log.e(tag, "Failed to load ASR history for panel", e)
                emptyList()
            }
            handler.post {
                menuController.showScrollableTextPanel(
                    anchorCenter = center,
                    alpha = alpha,
                    title = title,
                    texts = if (texts.isEmpty()) listOf(emptyText) else texts,
                    onItemClick = { text ->
                        if (text == emptyText) {
                            return@showScrollableTextPanel
                        }
                        try {
                            val clipMgr = context.getSystemService(
                                android.content.ClipboardManager::class.java
                            )
                            val clip = android.content.ClipData.newPlainText("asr_history", text)
                            clipMgr?.setPrimaryClip(clip)
                            showToast(context.getString(R.string.floating_asr_copied))
                        } catch (e: Throwable) {
                            Log.e(tag, "Failed to copy history text", e)
                        }
                    },
                    initialVisibleCount = 20,
                    loadMoreCount = 20
                ) {
                    touchActiveGuard = false
                    updateVisibilityByPref("history_panel_dismiss")
                }
            }
        }
    }

    private fun toggleAutoStopSilenceFromMenu() {
        try {
            val newVal = !prefs.autoStopOnSilenceEnabled
            prefs.autoStopOnSilenceEnabled = newVal
            val msgRes = if (newVal) R.string.toast_silence_autostop_on else R.string.toast_silence_autostop_off
            showToast(context.getString(msgRes))
        } catch (e: Throwable) {
            Log.e(tag, "Failed to toggle silence auto-stop", e)
        }
    }

    private fun openSettingsFromMenu() {
        try {
            val intent = Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(tag, "Failed to open settings", e)
        }
    }

    private fun uploadClipboardOnceFromMenu() {
        try {
            val mgr = com.brycewg.asrkb.clipboard.SyncClipboardManager(
                context,
                prefs,
                scope,
                clipboardPort = com.brycewg.asrkb.clipboard.SystemClipboardPortFactory.create(
                    context,
                    prefs
                )
            )
            scope.launch(Dispatchers.IO) {
                val ok = try {
                    mgr.uploadOnce()
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to upload clipboard", t)
                    false
                }
                handler.post {
                    showToast(
                        context.getString(
                            if (ok) R.string.sc_status_uploaded else R.string.sc_test_failed
                        )
                    )
                }
            }
        } catch (e: Throwable) {
            Log.e(tag, "Failed to init clipboard manager for upload", e)
        }
    }

    private fun pullClipboardOnceFromMenu() {
        try {
            val mgr = com.brycewg.asrkb.clipboard.SyncClipboardManager(
                context,
                prefs,
                scope,
                clipboardPort = com.brycewg.asrkb.clipboard.SystemClipboardPortFactory.create(
                    context,
                    prefs
                )
            )
            scope.launch(Dispatchers.IO) {
                val ok = try {
                    mgr.pullNow(updateClipboard = true).first
                } catch (t: Throwable) {
                    Log.e(tag, "Failed to pull clipboard", t)
                    false
                }

                handler.post {
                    val msgRes = if (ok) R.string.sc_test_success else R.string.sc_test_failed
                    showToast(context.getString(msgRes))
                }
            }
        } catch (e: Throwable) {
            Log.e(tag, "Failed to init clipboard manager for pull", e)
        }
    }

    private fun getMenuAlphaOrDefault(): Float = try {
        prefs.floatingSwitcherAlpha
    } catch (e: Throwable) {
        Log.w(tag, "Failed to get floating menu alpha", e)
        1.0f
    }

    private fun buildRadialMenuItems(): List<FloatingMenuHelper.MenuItem> = buildList {
        add(
            FloatingMenuHelper.MenuItem(
                R.drawable.article,
                context.getString(R.string.label_radial_switch_prompt),
                context.getString(R.string.label_radial_switch_prompt)
            ) { onPickPromptPresetFromMenu() }
        )
        add(
            FloatingMenuHelper.MenuItem(
                R.drawable.waveform,
                context.getString(R.string.label_radial_switch_asr),
                context.getString(R.string.label_radial_switch_asr)
            ) { onPickAsrVendor() }
        )
        add(
            FloatingMenuHelper.MenuItem(
                R.drawable.keyboard,
                context.getString(R.string.label_radial_switch_ime),
                context.getString(R.string.label_radial_switch_ime)
            ) { invokeImePickerFromMenu() }
        )
        add(
            FloatingMenuHelper.MenuItem(
                R.drawable.arrows_out_cardinal,
                context.getString(R.string.label_radial_move),
                context.getString(R.string.label_radial_move)
            ) { enableMoveModeFromMenu() }
        )
        add(
            FloatingMenuHelper.MenuItem(
                if (try {
                        prefs.autoStopOnSilenceEnabled
                    } catch (_: Throwable) {
                        false
                    }
                ) {
                    R.drawable.hand_palm_fill
                } else {
                    R.drawable.hand_palm
                },
                context.getString(R.string.label_radial_toggle_silence_autostop),
                context.getString(R.string.label_radial_toggle_silence_autostop)
            ) { toggleAutoStopSilenceFromMenu() }
        )
        add(
            FloatingMenuHelper.MenuItem(
                if (try {
                        prefs.postProcessEnabled
                    } catch (_: Throwable) {
                        false
                    }
                ) {
                    R.drawable.magic_wand_fill
                } else {
                    R.drawable.magic_wand
                },
                context.getString(R.string.label_radial_postproc),
                context.getString(R.string.label_radial_postproc)
            ) { togglePostprocFromMenu() }
        )
        add(
            FloatingMenuHelper.MenuItem(
                R.drawable.textbox,
                context.getString(R.string.label_radial_open_history),
                context.getString(R.string.label_radial_open_history)
            ) { showHistoryPanelFromMenu() }
        )

        if (try {
                prefs.syncClipboardEnabled
            } catch (_: Throwable) {
                false
            }
        ) {
            add(
                FloatingMenuHelper.MenuItem(
                    R.drawable.cloud_arrow_up,
                    context.getString(R.string.label_radial_clipboard_upload),
                    context.getString(R.string.label_radial_clipboard_upload)
                ) { uploadClipboardOnceFromMenu() }
            )
            add(
                FloatingMenuHelper.MenuItem(
                    R.drawable.cloud_arrow_down,
                    context.getString(R.string.label_radial_clipboard_pull),
                    context.getString(R.string.label_radial_clipboard_pull)
                ) { pullClipboardOnceFromMenu() }
            )
        }

        add(
            FloatingMenuHelper.MenuItem(
                R.drawable.gear,
                context.getString(R.string.label_radial_open_settings),
                context.getString(R.string.label_radial_open_settings)
            ) { openSettingsFromMenu() }
        )
    }

    // ==================== 辅助方法 ====================

    private fun invokeImePicker() {
        try {
            val imm = context.getSystemService(InputMethodManager::class.java)
            if (!isOurImeEnabled(imm)) {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return
            }
            val intent = Intent(context, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(SettingsActivity.EXTRA_AUTO_SHOW_IME_PICKER, true)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(tag, "Failed to invoke IME picker", e)
            try {
                val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Throwable) {
                Log.e(tag, "Failed to open IME settings", e2)
            }
        }
    }

    private fun isOurImeEnabled(imm: InputMethodManager?): Boolean {
        val list = try {
            imm?.enabledInputMethodList
        } catch (e: Throwable) {
            Log.e(tag, "Failed to get enabled IME list", e)
            null
        }
        if (list?.any { it.packageName == context.packageName } == true) return true
        return try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_INPUT_METHODS
            )
            val id = "${context.packageName}/.ime.AsrKeyboardService"
            enabled?.contains(id) == true ||
                (enabled?.split(':')?.any { it.startsWith(context.packageName) } == true)
        } catch (e: Throwable) {
            Log.e(tag, "Failed to check IME enabled via settings", e)
            false
        }
    }

    private fun showToast(message: String) {
        try {
            notifier.showToast(message)
        } catch (e: Throwable) {
            Log.e(tag, "Failed to show toast: $message", e)
        }
    }

    private fun showVolumeKeyStatusToast(messageRes: Int) {
        if (!prefs.volumeKeyStatusToastEnabled) return
        showToast(context.getString(messageRes))
    }

    private fun hasRecordAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun hapticTapIfEnabled(view: View?) {
        HapticFeedbackHelper.performTap(context, prefs, view)
    }
}
