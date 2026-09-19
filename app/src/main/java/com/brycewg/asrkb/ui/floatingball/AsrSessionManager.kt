/**
 * 悬浮球录音会话管理与结果提交协调器。
 *
 * 归属模块：ui/floatingball
 */
package com.brycewg.asrkb.ui.floatingball

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.brycewg.asrkb.asr.*
import com.brycewg.asrkb.asr.AsrConnectionWarmer
import com.brycewg.asrkb.asr.AsrTimeoutCalculator
import com.brycewg.asrkb.asr.BluetoothRouteManager
import com.brycewg.asrkb.imebridge.ImeBridgeClient
import com.brycewg.asrkb.imebridge.ImeBridgeContract
import com.brycewg.asrkb.imebridge.ImeBridgeResult
import com.brycewg.asrkb.imebridge.ImeBridgeWarningToast
import com.brycewg.asrkb.imebridge.imeBridgeWarningMessageRes
import com.brycewg.asrkb.store.AsrHistoryAudioCapture
import com.brycewg.asrkb.store.AsrHistoryFailureRecorder
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.AsrHistoryTimingOrigin
import com.brycewg.asrkb.store.AsrHistoryTimingRecorder
import com.brycewg.asrkb.store.AsrHistoryTimingStage
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptSelectionStatus
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.store.debug.StreamingPreviewDiag
import com.brycewg.asrkb.store.getAsrRuntimeStatsSnapshotOrNull
import com.brycewg.asrkb.store.recordPrimaryAsrRuntimeRequestIfSuccessful
import com.brycewg.asrkb.ui.AsrAccessibilityService.FocusContext
import com.brycewg.asrkb.ui.AsrAccessibilityService.InsertPath
import com.brycewg.asrkb.util.TextSanitizer
import com.brycewg.asrkb.util.TypewriterTextAnimator
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun floatingBallLocalAsrMissingModelErrorRes(vendor: AsrVendor): Int? = AsrLocalModelCatalog.missingModelErrorRes(vendor)

/**
 * ASR 会话管理器
 * 负责 ASR 引擎的生命周期管理、结果处理和超时兜底
 */
class AsrSessionManager(
    private val context: Context,
    private val prefs: Prefs,
    private val serviceScope: CoroutineScope,
    private val listener: AsrSessionListener,
    keepScreenOnApply: (Boolean) -> Boolean
) {

    companion object {
        private const val TAG = "AsrSessionManager"
        private const val LOCAL_MODEL_READY_WAIT_CONSUMED = -1L
        private const val WARNING_TOAST_RECENT_WINDOW_MS = 5_000L
    }

    interface AsrSessionListener {
        fun onSessionStateChanged(state: FloatingBallState)
        fun onResultCommitted(text: String, success: Boolean)
        fun onError(message: String)
        fun onAmplitude(amplitude: Float) { /* default no-op */ }
    }

    private var asrEngine: StreamingAsrEngine? = null
    private val postproc = LlmPostProcessor()
    private val imeBridgeClient by lazy { ImeBridgeClient(context.applicationContext) }
    private val directMicrophoneEngineFactory = AsrDirectMicrophoneEngineFactory()
    private val parallelEngineFactory = AsrParallelEngineFactory()

    // 会话上下文
    private var focusContext: FocusContext? = null
    private var lastPartialForPreview: String? = null

    @Volatile
    private var useImeBridgeForSession: Boolean = false

    @Volatile
    private var skipA11ySetTextPreviewForSession: Boolean = false

    @Volatile
    private var useImeBridgeComposingPreviewForSession: Boolean = false

    @Volatile
    private var imeBridgeSessionId: String? = null
    private var markerInserted: Boolean = false
    private var markerChar: String? = null
    private var aiPostProcessingToken: Long = 0L
    private var aiPostProcessingBaseText: String? = null
    private var aiPostProcessingPreviewText: String? = null
    private var aiPostProcessingResolvedText: String? = null

    // 超时控制
    private var processingTimeoutJob: Job? = null
    private var hasCommittedResult: Boolean = false

    // 统计：录音时长
    private var sessionStartUptimeMs: Long = 0L
    private var lastAudioMsForStats: Long = 0L

    // 统计/历史：端到端耗时起点（从开始录音到最终提交完成）
    private var sessionStartTotalUptimeMs: Long = 0L

    // 统计：非流式请求处理耗时（毫秒）
    private var lastRequestDurationMs: Long? = null

    // 本地模型：Processing 阶段等待“模型就绪”的耗时（用于将处理耗时统计从模型就绪开始）
    private val localModelReadyWaitMs = AtomicLong(0L)

    // 标记：最近一次提交是否实际使用了 AI 输出
    private var lastAiUsed: Boolean = false

    // 统计/历史：最近一次 AI 后处理耗时与状态
    private var lastAiPostMs: Long = 0L
    private var lastAiPostStatus: AsrHistoryStore.AiPostStatus = AsrHistoryStore.AiPostStatus.NONE
    private var lastLlmVendorId: String? = null
    private var lastPromptSelection: PromptSelectionStatus? = null

    // 统计/历史：最近一次最终结果的实际供应商（备用引擎场景下不再固定记录 prefs.asrVendor）
    private var sessionPrimaryVendor: AsrVendor = try {
        prefs.asrVendor
    } catch (
        _: Throwable
    ) {
        AsrVendor.Volc
    }
    private var lastFinalVendorForStats: AsrVendor? = null
    private var historyAudioCapture: AsrHistoryAudioCapture? = null
    private var activeHistoryRecordId: String? = null
    private var completedHistoryRecordId: String? = null
    private var completedHistoryRawText: String? = null
    private var activeHistoryTiming: AsrHistoryTimingRecorder? = null
    private var completedHistoryTiming: AsrHistoryTimingRecorder? = null

    private val recordingAudioFocusController = RecordingAudioFocusController(context) { loss ->
        onRecordingAudioFocusLost(loss)
    }
    private val keepScreenOnController = RecordingKeepScreenOnController(
        surface = "float",
        apply = keepScreenOnApply
    )
    private val sessionTokenCounter = AtomicLong(0L)
    private val bridgePreviewSequence = AtomicLong(0L)
    private val bridgeOperationLock = Any()

    @Volatile
    private var activeSessionToken: Long = 0L

    private fun createSessionToken(): Long {
        val token = sessionTokenCounter.incrementAndGet()
        activeSessionToken = token
        return token
    }

    private fun clearActiveSessionToken(expectedToken: Long? = null) {
        if (expectedToken == null || activeSessionToken == expectedToken) {
            if (activeSessionToken != 0L) {
                ContinuousCaptureCoordinator.endSession(activeSessionToken)
            }
            activeSessionToken = 0L
        }
    }

    private fun isSessionActive(sessionToken: Long): Boolean = sessionToken != 0L && activeSessionToken == sessionToken

    private fun createEngineListener(sessionToken: Long): StreamingAsrEngine.Listener = object :
        StreamingAsrEngine.Listener,
        BackupAsrStatusListener {
        override fun onFinal(text: String) {
            this@AsrSessionManager.onFinal(sessionToken, text)
        }

        override fun onError(message: String) {
            this@AsrSessionManager.onError(sessionToken, message)
        }

        override fun onPartial(text: String) {
            this@AsrSessionManager.onPartial(sessionToken, text)
        }

        override fun onStopped() {
            this@AsrSessionManager.onStopped(sessionToken)
        }

        override fun onAmplitude(amplitude: Float) {
            this@AsrSessionManager.onAmplitude(sessionToken, amplitude)
        }

        override fun onBackupAsrLoading(backupVendor: AsrVendor) {
            this@AsrSessionManager.onBackupAsrLoading(sessionToken)
        }

        override fun onBackupAsrRecognizing(backupVendor: AsrVendor) {
            this@AsrSessionManager.onBackupAsrRecognizing(sessionToken)
        }
    }

    private fun clearPreviewSessionContext() {
        focusContext = null
        lastPartialForPreview = null
        useImeBridgeForSession = false
        skipA11ySetTextPreviewForSession = false
        useImeBridgeComposingPreviewForSession = false
        imeBridgeSessionId = null
        markerInserted = false
        markerChar = null
        aiPostProcessingToken = 0L
        aiPostProcessingBaseText = null
        aiPostProcessingPreviewText = null
        aiPostProcessingResolvedText = null
    }

    private fun beginAiPostProcessing(sessionToken: Long, rawFinalText: String) {
        aiPostProcessingToken = sessionToken
        aiPostProcessingBaseText = rawFinalText
        aiPostProcessingPreviewText = null
        aiPostProcessingResolvedText = null
    }

    private fun rememberAiPostProcessingPreview(sessionToken: Long, text: String) {
        if (aiPostProcessingToken != sessionToken || text.isBlank()) return
        aiPostProcessingPreviewText = text
    }

    private fun rememberAiPostProcessingResolvedText(sessionToken: Long, text: String) {
        if (aiPostProcessingToken != sessionToken || text.isBlank()) return
        aiPostProcessingResolvedText = text
    }

    private fun peekInterruptedPostProcessingCommitText(sessionToken: Long): String? {
        if (sessionToken == 0L) return null
        if (aiPostProcessingToken != sessionToken) return null
        return aiPostProcessingBaseText?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun snapshotAudioDurationIfPossible() {
        if (lastAudioMsForStats != 0L) return
        historyAudioCapture?.audioDurationMs()?.takeIf { it > 0L }?.let {
            lastAudioMsForStats = it
            sessionStartUptimeMs = 0L
            return
        }
        (asrEngine as? SessionAudioSourceOwner)?.sessionAudioStore?.totalBytes
            ?.takeIf { it > 0L }
            ?.let {
                lastAudioMsForStats = it * 1_000L / (16_000L * 2L)
                sessionStartUptimeMs = 0L
                return
            }
        if (sessionStartUptimeMs == 0L) return
        try {
            val now = SystemClock.uptimeMillis()
            if (now >= sessionStartUptimeMs) {
                lastAudioMsForStats = now - sessionStartUptimeMs
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to snapshot audio duration on stopRecording", t)
        } finally {
            sessionStartUptimeMs = 0L
        }
    }

    private fun stopActiveEngineIfRunning(reason: String) {
        val engine = asrEngine ?: return
        val running = try {
            engine.isRunning
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read engine running state on $reason", t)
            false
        }
        if (!running) return
        try {
            Log.w(TAG, "Force stopping active engine: reason=$reason")
            engine.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to stop active engine on $reason", t)
        }
    }

    private fun releaseRecordingResources(reason: String) {
        recordingAudioFocusController.release()
        setRecordingKeepScreenOn(active = false)
        try {
            BluetoothRouteManager.onRecordingStopped(context)
        } catch (t: Throwable) {
            Log.w(TAG, "BluetoothRouteManager.onRecordingStopped failed on $reason", t)
        }
    }

    /** 开始录音 */
    fun startRecording() {
        discardInFlightHistoryCapture()
        Log.d(TAG, "startRecording called")
        val sessionToken = createSessionToken()
        stopActiveEngineIfRunning("start_recording")
        releaseRecordingResources("start_recording")
        activeHistoryRecordId = UUID.randomUUID().toString()
        historyAudioCapture = AsrHistoryAudioCapture.create(
            context,
            prefs,
            activeHistoryRecordId.orEmpty()
        )
        activeHistoryTiming = AsrHistoryTimingRecorder(AsrHistoryTimingOrigin.ORIGINAL).also {
            it.begin(AsrHistoryTimingStage.AUDIO_INPUT)
        }
        try {
            sessionPrimaryVendor = prefs.asrVendor
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to snapshot vendor on startRecording", t)
        } finally {
            lastFinalVendorForStats = null
        }
        try {
            sessionStartUptimeMs = SystemClock.uptimeMillis()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read uptime for session start", t)
            sessionStartUptimeMs = 0L
        }
        sessionStartTotalUptimeMs = sessionStartUptimeMs
        lastAudioMsForStats = 0L
        // 新会话开始：重置请求耗时，避免上一轮的值串台
        lastRequestDurationMs = null
        localModelReadyWaitMs.set(0L)
        lastAiUsed = false
        lastAiPostMs = 0L
        lastAiPostStatus = AsrHistoryStore.AiPostStatus.NONE
        lastLlmVendorId = null
        lastPromptSelection = null

        val localModelError = checkLocalModelError()
        if (localModelError != null) {
            clearActiveSessionToken(sessionToken)
            releaseRecordingResources("model_missing")
            listener.onError(localModelError)
            return
        }

        // 清理上次会话
        try {
            processingTimeoutJob?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel previous timeout job", e)
        }
        processingTimeoutJob = null
        hasCommittedResult = false

        val useImeBridge = isImeBridgeEnabled()
        useImeBridgeForSession = useImeBridge
        skipA11ySetTextPreviewForSession = try {
            prefs.shouldUseA11yAndroid13Api()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to snapshot Android 13 a11y API preference", e)
            false
        }
        imeBridgeSessionId = null
        useImeBridgeComposingPreviewForSession = if (useImeBridge) {
            prepareImeBridgeSession()
        } else {
            false
        }
        // 写入兼容模式：为命中包名注入占位符（粘贴方式），屏蔽原文本干扰。
        // Android 13 IME commitText 路径不做 SET_TEXT 预览，兼容标记也不需要。
        if (!useImeBridge && !skipA11ySetTextPreviewForSession) {
            tryFixCompatPlaceholderIfNeeded()
        }

        // 构建引擎
        asrEngine = buildEngineForCurrentMode(sessionToken)
        Log.d(TAG, "ASR engine created: ${asrEngine?.javaClass?.simpleName}")

        // Bridge 模式直接通过 IME 的 InputConnection 提交最终文本，不做无障碍预览/整段重写。
        if (useImeBridge) {
            focusContext = null
        } else {
            // 记录焦点上下文（占位后再取，保持与参考版本一致）
            focusContext = com.brycewg.asrkb.ui.AsrAccessibilityService.getCurrentFocusContext()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    focusContext = com.brycewg.asrkb.ui.AsrAccessibilityService.getCurrentFocusContext()
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to refresh focus context", e)
                }
            }, 120)
        }
        lastPartialForPreview = null

        // 启动引擎
        if (prefs.duckMediaOnRecordEnabled) {
            recordingAudioFocusController.acquire()
        } else {
            Log.d(TAG, "Audio ducking disabled by user; skip audio focus request")
        }
        setRecordingKeepScreenOn(active = true)
        listener.onSessionStateChanged(FloatingBallState.Recording)
        asrEngine?.let { engine ->
            (engine as? AudioFrameSinkOwner)?.audioFrameSink = historyAudioCapture
            historyAudioCapture?.bind(engine as? SessionAudioSourceOwner)
            AsrConnectionWarmer.warmForImmediateUse(context, prefs)
            LlmConnectionWarmer.warmForImmediateUse(prefs)
            preloadLocalAsrForImmediateUse(context, prefs)
            ContinuousCaptureCoordinator.beginSession(sessionToken)
            engine.start()
        }
        try {
            BluetoothRouteManager.onRecordingStarted(context)
        } catch (
            t: Throwable
        ) {
            Log.w(TAG, "BluetoothRouteManager onRecordingStarted", t)
        }
    }

    /** 停止录音 */
    fun stopRecording() {
        Log.d(TAG, "stopRecording called")
        snapshotAudioDurationIfPossible()
        transitionAudioInputToRecognition()
        asrEngine?.stop()
        ContinuousCaptureCoordinator.endSession(activeSessionToken)
        releaseRecordingResources("stop_recording")

        // 进入处理阶段
        listener.onSessionStateChanged(FloatingBallState.Processing)

        // 启动超时兜底
        startProcessingTimeout(activeSessionToken, lastAudioMsForStats)
    }

    /** 录音引擎是否仍在采集；独立于悬浮球当前展示的视觉状态。 */
    internal fun isRecordingActive(): Boolean = try {
        asrEngine?.isRunning == true
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to read active recording state", t)
        false
    }

    /**
     * 取消当前会话并丢弃本轮迟到回调，供悬浮球在 Processing/Recording 态主动中止。
     */
    fun cancelSession() {
        Log.d(TAG, "cancelSession called")
        val sessionToken = activeSessionToken
        ContinuousCaptureCoordinator.endSession(sessionToken)
        val commitText = peekInterruptedPostProcessingCommitText(sessionToken)
        if (commitText.isNullOrEmpty()) {
            archiveUncommittedHistoryRecords(
                status = AsrHistoryStore.AsrHistoryStatus.CANCELLED,
                failStage = currentHistoryFailStage(),
                failReasonCode = AsrFailReasonCodes.USER_CANCEL
            )
        } else {
            historyAudioCapture = null
            activeHistoryRecordId = null
        }
        (asrEngine as? AudioFrameSinkOwner)?.audioFrameSink = null
        postproc.cancelActiveRequest()
        clearActiveSessionToken(sessionToken)
        try {
            processingTimeoutJob?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel timeout job in cancelSession", e)
        }
        processingTimeoutJob = null
        hasCommittedResult = false
        stopActiveEngineIfRunning("cancel_session")
        releaseRecordingResources("cancel_session")
        asrEngine = null
        sessionStartUptimeMs = 0L
        localModelReadyWaitMs.set(0L)
        listener.onSessionStateChanged(FloatingBallState.Idle)
        if (!commitText.isNullOrEmpty()) {
            Log.d(TAG, "Cancel during AI post-processing; commit current result instead")
            lastAiUsed = false
            lastAiPostMs = 0L
            lastAiPostStatus = AsrHistoryStore.AiPostStatus.NONE
            lastLlmVendorId = null
            lastPromptSelection = null
            transitionPostprocessToDelivery()
            val success = insertTextToFocus(commitText)
            hasCommittedResult = true
            listener.onResultCommitted(commitText, success)
        } else {
            if (useImeBridgeForSession) {
                clearImeBridgeComposingPreview("cancel_session")
            } else {
                rollbackPreviewToSnapshotIfNeeded()
            }
            sessionStartTotalUptimeMs = 0L
            lastAudioMsForStats = 0L
            lastRequestDurationMs = null
        }
        clearPreviewSessionContext()
    }

    private fun rollbackPreviewToSnapshotIfNeeded() {
        if (shouldSkipA11ySetTextPreview()) return
        if (lastPartialForPreview.isNullOrEmpty() && !markerInserted) return
        val ctx = focusContext ?: return
        val currentText = com.brycewg.asrkb.ui.AsrAccessibilityService.getCurrentFocusedText()
            ?: return
        val rollbackText = stripMarkersIfAny(ctx.prefix + ctx.suffix)
        val previewText = lastPartialForPreview?.let { ctx.prefix + it + ctx.suffix }
        val acceptableCurrentTexts = linkedSetOf(
            ctx.prefix + ctx.suffix,
            rollbackText
        )
        previewText?.let {
            acceptableCurrentTexts += it
            acceptableCurrentTexts += stripMarkersIfAny(it)
        }
        if (currentText !in acceptableCurrentTexts) {
            Log.d(TAG, "Skip preview rollback because focused text diverged")
            return
        }
        val reverted = com.brycewg.asrkb.ui.AsrAccessibilityService.insertTextSilent(rollbackText)
        if (!reverted) {
            Log.w(TAG, "Failed to roll back preview text on cancelSession")
            return
        }
        val prefixLenForCursor = stripMarkersIfAny(ctx.prefix).length
        com.brycewg.asrkb.ui.AsrAccessibilityService.setSelectionSilent(prefixLenForCursor)
        Log.d(TAG, "Preview text rolled back on cancelSession")
    }

    /**
     * 读取并清空最近一次会话的录音时长（毫秒）。
     */
    fun popLastAudioMsForStats(): Long {
        val v = lastAudioMsForStats
        lastAudioMsForStats = 0L
        return v
    }

    fun popLastHistoryRecordId(): String = completedHistoryRecordId.also { completedHistoryRecordId = null }
        ?: UUID.randomUUID().toString()

    fun popLastHistoryRawText(): String? = completedHistoryRawText.also { completedHistoryRawText = null }

    internal fun transitionPostprocessToDelivery() {
        completedHistoryTiming?.apply {
            end(AsrHistoryTimingStage.POSTPROCESS)
            begin(AsrHistoryTimingStage.TEXT_DELIVERY)
        }
    }

    internal fun completeLastHistoryTiming(): com.brycewg.asrkb.store.AsrHistoryTimingTrace? {
        val timing = completedHistoryTiming ?: return null
        timing.end(AsrHistoryTimingStage.TEXT_DELIVERY)
        completedHistoryTiming = null
        return timing.complete()
    }

    private fun discardInFlightHistoryCapture() {
        val leftoverId = activeHistoryRecordId
        val capture = historyAudioCapture
        historyAudioCapture = null
        activeHistoryRecordId = null
        if (leftoverId.isNullOrEmpty() && capture == null) return
        archiveHistoryFailure(
            status = AsrHistoryStore.AsrHistoryStatus.CANCELLED,
            failStage = AsrHistoryStore.AsrHistoryFailStage.RECORDING,
            failReasonCode = AsrFailReasonCodes.USER_CANCEL,
            capture = capture,
            recordId = leftoverId
        )
    }

    private fun archiveUncommittedHistoryRecords(
        status: AsrHistoryStore.AsrHistoryStatus,
        failStage: AsrHistoryStore.AsrHistoryFailStage,
        failReasonCode: String
    ) {
        snapshotAudioDurationIfPossible()
        val leftoverId = completedHistoryRecordId
        val leftoverRaw = completedHistoryRawText
        val leftoverTiming = completedHistoryTiming
        completedHistoryRecordId = null
        completedHistoryRawText = null
        completedHistoryTiming = null
        val activeId = activeHistoryRecordId
        val capture = historyAudioCapture
        historyAudioCapture = null
        activeHistoryRecordId = null
        if (!leftoverId.isNullOrEmpty()) {
            archiveHistoryFailure(
                status = status,
                failStage = failStage,
                failReasonCode = failReasonCode,
                capture = null,
                recordId = leftoverId,
                timingTrace = leftoverTiming?.complete(completed = false),
                audioAlreadySaved = true,
                rawText = leftoverRaw ?: lastPartialForPreview
            )
        }
        if (!activeId.isNullOrEmpty() || capture != null) {
            archiveHistoryFailure(
                status = status,
                failStage = failStage,
                failReasonCode = failReasonCode,
                capture = capture,
                recordId = activeId,
                rawText = lastPartialForPreview
            )
        }
    }

    private fun archiveHistoryFailure(
        status: AsrHistoryStore.AsrHistoryStatus,
        failStage: AsrHistoryStore.AsrHistoryFailStage,
        failReasonCode: String,
        capture: AsrHistoryAudioCapture? = historyAudioCapture,
        recordId: String? = activeHistoryRecordId,
        audioAlreadySaved: Boolean = false,
        rawText: String? = lastPartialForPreview,
        timingTrace: com.brycewg.asrkb.store.AsrHistoryTimingTrace? = null
    ): Boolean {
        snapshotAudioDurationIfPossible()
        val resolvedTimingTrace = timingTrace ?: activeHistoryTiming?.complete(completed = false)
        if (timingTrace == null) activeHistoryTiming = null
        return AsrHistoryFailureRecorder.archive(
            context = context,
            prefs = prefs,
            capture = capture,
            recordId = recordId,
            source = "floating",
            vendorId = peekLastFinalVendorForStats().id,
            audioMs = lastAudioMsForStats,
            totalElapsedMs = resolvedTimingTrace?.totalElapsedMs ?: peekTotalElapsedMsForStats(),
            procMs = resolvedTimingTrace
                ?.stageDurationMs(AsrHistoryTimingStage.RECOGNITION)
                ?.takeIf { it > 0L }
                ?: lastRequestDurationMs
                ?: 0L,
            rawText = rawText,
            status = status,
            failStage = failStage,
            failReasonCode = failReasonCode,
            timingTrace = resolvedTimingTrace,
            audioAlreadySaved = audioAlreadySaved
        )
    }

    private fun currentHistoryFailStage(): AsrHistoryStore.AsrHistoryFailStage = if (isRecordingActive()) {
        AsrHistoryStore.AsrHistoryFailStage.RECORDING
    } else {
        AsrHistoryStore.AsrHistoryFailStage.RECOGNITION
    }

    private fun peekTotalElapsedMsForStats(): Long {
        val start = sessionStartTotalUptimeMs
        if (start <= 0L) return 0L
        val now = try {
            SystemClock.uptimeMillis()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to peek total elapsed ms", t)
            return 0L
        }
        return if (now >= start) (now - start).coerceAtLeast(0L) else 0L
    }

    /** 最近一次请求耗时（毫秒），仅非流式模式有效 */
    fun getLastRequestDuration(): Long? = lastRequestDurationMs

    /** 最近一次提交是否实际使用了 AI 输出 */
    fun wasLastAiUsed(): Boolean = lastAiUsed

    /** 读取并清空最近一次会话的端到端总耗时（毫秒）。 */
    fun popLastTotalElapsedMsForStats(): Long {
        val start = sessionStartTotalUptimeMs
        if (start <= 0L) return 0L
        val now = try {
            SystemClock.uptimeMillis()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read uptime for total elapsed ms", t)
            sessionStartTotalUptimeMs = 0L
            return 0L
        }
        val elapsed = if (now >= start) (now - start).coerceAtLeast(0L) else 0L
        sessionStartTotalUptimeMs = if (asrEngine?.isRunning == true) now else 0L
        return elapsed
    }

    private fun transitionAudioInputToRecognition() {
        activeHistoryTiming?.apply {
            end(AsrHistoryTimingStage.AUDIO_INPUT)
            begin(AsrHistoryTimingStage.RECOGNITION)
        }
    }

    /** 最近一次 AI 后处理耗时（毫秒）；未尝试时为 0 */
    fun getLastAiPostMs(): Long = lastAiPostMs

    /** 最近一次 AI 后处理状态 */
    fun getLastAiPostStatus(): AsrHistoryStore.AiPostStatus = lastAiPostStatus

    /** 最近一次实际尝试后处理使用的 LLM 渠道。 */
    fun getLastLlmVendorId(): String? = lastLlmVendorId

    /** 最近一次自动选择提示词的结果快照；未启用自动选择时为 null。 */
    fun getLastPromptSelection(): PromptSelectionStatus? = lastPromptSelection

    fun peekLastFinalVendorForStats(): AsrVendor = lastFinalVendorForStats ?: sessionPrimaryVendor

    /** 清理会话 */
    fun cleanup() {
        archiveUncommittedHistoryRecords(
            status = AsrHistoryStore.AsrHistoryStatus.CANCELLED,
            failStage = currentHistoryFailStage(),
            failReasonCode = AsrFailReasonCodes.USER_CANCEL
        )
        (asrEngine as? AudioFrameSinkOwner)?.audioFrameSink = null
        clearActiveSessionToken()
        ContinuousCaptureCoordinator.endAnySession()
        postproc.cancelActiveRequest()
        try {
            asrEngine?.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to stop ASR engine", e)
        }
        releaseRecordingResources("cleanup")
        sessionStartTotalUptimeMs = 0L
        try {
            processingTimeoutJob?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel timeout job", e)
        }
        if (useImeBridgeForSession) {
            clearImeBridgeComposingPreview("cleanup")
        } else {
            rollbackPreviewToSnapshotIfNeeded()
        }
        asrEngine = null
        clearPreviewSessionContext()
    }

    // ==================== StreamingAsrEngine.Listener ====================

    private fun onFinal(sessionToken: Long, text: String) {
        Log.d(TAG, "onFinal called with text: $text")
        if (!isSessionActive(sessionToken)) {
            Log.d(TAG, "Ignoring onFinal from stale session: $sessionToken")
            return
        }
        transitionAudioInputToRecognition()
        activeHistoryTiming?.end(AsrHistoryTimingStage.RECOGNITION)
        snapshotAudioDurationIfPossible()
        if (text.isNotBlank()) {
            completedHistoryRecordId = activeHistoryRecordId
            completedHistoryRawText = text
            completedHistoryTiming = activeHistoryTiming
            historyAudioCapture?.complete()
        } else {
            archiveHistoryFailure(
                status = AsrHistoryStore.AsrHistoryStatus.FAILED,
                failStage = AsrHistoryStore.AsrHistoryFailStage.RECOGNITION,
                failReasonCode = AsrFailReasonCodes.EMPTY_RESULT,
                rawText = lastPartialForPreview
            )
        }
        historyAudioCapture = null
        activeHistoryRecordId = null
        activeHistoryTiming = null
        (asrEngine as? AudioFrameSinkOwner)?.audioFrameSink = null
        val shouldUseAiPostProcessing = prefs.postProcessEnabled && prefs.hasLlmKeys()
        if (shouldUseAiPostProcessing) {
            beginAiPostProcessing(sessionToken, text)
        }
        lastFinalVendorForStats = when (val e = asrEngine) {
            is BackupAwareAsrEngine -> if (e.wasLastResultFromBackup()) e.backupVendor else e.primaryVendor
            else -> sessionPrimaryVendor
        }
        val hopStartedAt = SystemClock.elapsedRealtime()
        serviceScope.launch {
            AsrCallLatencyProbe.log(
                "t_final_hop",
                mapOf("hop_ms" to (SystemClock.elapsedRealtime() - hopStartedAt).coerceAtLeast(0L))
            )
            if (!isSessionActive(sessionToken)) return@launch

            completedHistoryTiming?.begin(AsrHistoryTimingStage.POSTPROCESS)
            try {
                processingTimeoutJob?.cancel()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to cancel timeout job in onFinal", e)
            }
            processingTimeoutJob = null
            if (!isSessionActive(sessionToken)) return@launch

            // 若已由兜底提交，忽略后续 onFinal
            if (hasCommittedResult && asrEngine?.isRunning != true) {
                Log.w(TAG, "Result already committed by fallback; ignoring residual onFinal")
                return@launch
            }

            var finalText = text
            lastAiUsed = false
            lastAiPostMs = 0L
            lastAiPostStatus = AsrHistoryStore.AiPostStatus.NONE
            lastLlmVendorId = null
            lastPromptSelection = null
            val stillRecording = (asrEngine?.isRunning == true)
            // 若未收到 onStopped，则在此近似计算录音时长
            if (lastAudioMsForStats == 0L && sessionStartUptimeMs > 0L) {
                try {
                    val dur = (SystemClock.uptimeMillis() - sessionStartUptimeMs).coerceAtLeast(0)
                    lastAudioMsForStats = dur
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to compute audio duration in onFinal", t)
                } finally {
                    sessionStartUptimeMs = 0L
                }
            }
            prefs.recordPrimaryAsrRuntimeRequestIfSuccessful(
                engine = asrEngine,
                fallbackPrimaryVendor = sessionPrimaryVendor,
                audioMs = lastAudioMsForStats,
                requestMs = lastRequestDurationMs
            )
            if (!isSessionActive(sessionToken)) return@launch

            // 统一使用 AsrFinalFilters：含预修剪/LLM/后修剪/繁体转换
            if (shouldUseAiPostProcessing) {
                Log.d(TAG, "Starting AI post-processing (stillRecording=$stillRecording)")
                if (!stillRecording) {
                    listener.onSessionStateChanged(FloatingBallState.Processing)
                }
                if (lastPartialForPreview.isNullOrEmpty()) {
                    rememberAiPostProcessingPreview(sessionToken, text)
                    updatePreviewText(text)
                }
                val typewriterEnabled = try {
                    prefs.postprocTypewriterEnabled
                } catch (
                    _: Throwable
                ) {
                    true
                }
                var postprocCommitted = false
                val typewriter = if (typewriterEnabled) {
                    TypewriterTextAnimator(
                        scope = serviceScope,
                        onEmit = emit@{ typed ->
                            if (postprocCommitted || !isSessionActive(sessionToken)) return@emit
                            rememberAiPostProcessingPreview(sessionToken, typed)
                            updatePreviewText(typed)
                        },
                        frameDelayMs = 60L,
                        idleStopDelayMs = 1200L,
                        normalTargetFrames = 12,
                        normalMaxStep = 8
                    )
                } else {
                    null
                }
                var lastStreamingText: String? = null
                val onStreamingUpdate: ((String) -> Unit)? = typewriter?.let { animator ->
                    onStreamingUpdate@{ streamed ->
                        if (postprocCommitted || !isSessionActive(sessionToken)) return@onStreamingUpdate
                        if (streamed.isEmpty() ||
                            streamed == lastStreamingText
                        ) {
                            return@onStreamingUpdate
                        }
                        val prevStream = lastStreamingText
                        lastStreamingText = streamed
                        StreamingPreviewDiag.logVerbose(
                            category = "float",
                            event = "ai_stream",
                            prev = prevStream,
                            next = streamed,
                            extra = mapOf(
                                "sessionToken" to sessionToken,
                                "tw" to true
                            )
                        )
                        logFloatTypewriterSubmit(animator, streamed, rush = false)
                        animator.submit(streamed)
                    }
                }
                val res = try {
                    com.brycewg.asrkb.util.AsrFinalFilters.applyWithAi(
                        context,
                        prefs,
                        text,
                        postproc,
                        onStreamingUpdate = onStreamingUpdate,
                        aiTimingObserver = object : com.brycewg.asrkb.util.AsrFinalFilters.AiPostprocessTimingObserver {
                            override fun onAiPostprocessStarted() {
                                completedHistoryTiming?.end(AsrHistoryTimingStage.POSTPROCESS)
                                completedHistoryTiming?.begin(AsrHistoryTimingStage.AI_POSTPROCESS)
                            }

                            override fun onAiPostprocessFinished() {
                                completedHistoryTiming?.end(AsrHistoryTimingStage.AI_POSTPROCESS)
                                completedHistoryTiming?.begin(AsrHistoryTimingStage.POSTPROCESS)
                            }

                            override fun onPromptSelectionStarted() {
                                completedHistoryTiming?.end(AsrHistoryTimingStage.POSTPROCESS)
                                completedHistoryTiming?.begin(AsrHistoryTimingStage.PROMPT_SELECTION)
                            }

                            override fun onPromptSelectionFinished() {
                                completedHistoryTiming?.end(AsrHistoryTimingStage.PROMPT_SELECTION)
                                completedHistoryTiming?.begin(AsrHistoryTimingStage.POSTPROCESS)
                            }
                        },
                        // 悬浮球录音属于自动流程。
                        promptSelectionMode = com.brycewg.asrkb.util.AsrFinalFilters.PromptSelectionMode.AUTO_IF_ENABLED,
                        isCancelled = { !isSessionActive(sessionToken) }
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "applyWithAi failed", t)
                    com.brycewg.asrkb.asr.LlmPostProcessor.LlmProcessResult(
                        ok = false,
                        text = text,
                        errorMessage = t.message,
                        httpCode = null,
                        usedAi = false,
                        attempted = true,
                        llmMs = 0
                    )
                }
                if (!isSessionActive(sessionToken)) {
                    typewriter?.cancel()
                    return@launch
                }
                if (!res.ok) Log.w(TAG, "Post-processing failed; using processed text anyway")
                val aiUsed = (res.usedAi && res.ok)
                lastAiPostMs = if (res.attempted) res.llmMs else 0L
                lastAiPostStatus = when {
                    res.attempted && aiUsed -> AsrHistoryStore.AiPostStatus.SUCCESS
                    res.attempted -> AsrHistoryStore.AiPostStatus.FAILED
                    else -> AsrHistoryStore.AiPostStatus.NONE
                }
                lastLlmVendorId = res.llmVendorId
                lastPromptSelection = res.promptSelectionStatus
                finalText = res.text.ifBlank { text }
                rememberAiPostProcessingResolvedText(sessionToken, finalText)
                if (typewriter != null &&
                    aiUsed &&
                    finalText.isNotEmpty() &&
                    focusContext != null
                ) {
                    // 最终结果到达后：让打字机以最快速度追到最终文本，再进行最终提交
                    logFloatTypewriterSubmit(typewriter, finalText, rush = true)
                    typewriter.submit(finalText, rush = true)
                    val finalLen = finalText.length
                    val t0 = try {
                        SystemClock.uptimeMillis()
                    } catch (_: Throwable) {
                        0L
                    }
                    while (!postprocCommitted &&
                        isSessionActive(sessionToken) &&
                        (t0 <= 0L || (SystemClock.uptimeMillis() - t0) < 2_000L) &&
                        typewriter.currentText().length != finalLen
                    ) {
                        delay(20)
                    }
                }
                if (!isSessionActive(sessionToken)) {
                    typewriter?.cancel()
                    return@launch
                }
                val twLen = typewriter?.currentText()?.length ?: -1
                val timedOut = typewriter != null &&
                    aiUsed &&
                    finalText.isNotEmpty() &&
                    twLen != finalText.length
                try {
                    DebugLogManager.logBase(
                        category = "float",
                        event = "ai_commit",
                        data = mapOf(
                            "sessionToken" to sessionToken,
                            "fp" to StreamingPreviewDiag.fingerprint(finalText),
                            "aiUsed" to aiUsed,
                            "twLen" to twLen,
                            "timedOut" to timedOut,
                            "streamed" to StreamingPreviewDiag.fingerprint(lastStreamingText)
                        )
                    )
                } catch (_: Throwable) { }
                postprocCommitted = true
                typewriter?.cancel()
                lastAiUsed = aiUsed
                Log.d(TAG, "Post-processing completed: $finalText")
            } else {
                finalText =
                    com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, text)
                lastAiUsed = false
                lastAiPostMs = 0L
                lastAiPostStatus = AsrHistoryStore.AiPostStatus.NONE
                lastLlmVendorId = null
                lastPromptSelection = null
            }
            if (!isSessionActive(sessionToken)) return@launch

            // 更新状态
            val engineStillRunning = asrEngine?.isRunning == true
            if (engineStillRunning) {
                listener.onSessionStateChanged(FloatingBallState.Recording)
            } else {
                listener.onSessionStateChanged(FloatingBallState.Idle)
            }
            if (!isSessionActive(sessionToken)) return@launch

            // 插入文本
            if (finalText.isNotEmpty()) {
                val usedBackupEngine =
                    (asrEngine as? BackupAwareAsrEngine)?.wasLastResultFromBackup() == true
                transitionPostprocessToDelivery()
                val success = insertTextToFocus(finalText)
                if (!engineStillRunning) {
                    clearActiveSessionToken(sessionToken)
                }
                listener.onResultCommitted(finalText, success)
                if (usedBackupEngine) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(com.brycewg.asrkb.R.string.toast_backup_asr_used),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                Log.w(TAG, "Final text is empty")
                val queuedId = completedHistoryRecordId
                val queuedRaw = completedHistoryRawText
                val queuedTiming = completedHistoryTiming
                completedHistoryRecordId = null
                completedHistoryRawText = null
                completedHistoryTiming = null
                if (!queuedId.isNullOrEmpty()) {
                    archiveHistoryFailure(
                        status = AsrHistoryStore.AsrHistoryStatus.FAILED,
                        failStage = AsrHistoryStore.AsrHistoryFailStage.RECOGNITION,
                        failReasonCode = AsrFailReasonCodes.EMPTY_RESULT,
                        capture = null,
                        recordId = queuedId,
                        timingTrace = queuedTiming?.complete(completed = false),
                        audioAlreadySaved = true,
                        rawText = queuedRaw ?: lastPartialForPreview
                    )
                }
                if (!engineStillRunning) {
                    clearActiveSessionToken(sessionToken)
                }
                clearImeBridgeComposingPreview("empty_final")
                listener.onError(
                    context.getString(com.brycewg.asrkb.R.string.asr_error_empty_result)
                )
            }

            // 清理会话上下文
            clearPreviewSessionContext()
        }
    }

    private fun onStopped(sessionToken: Long) {
        if (!isSessionActive(sessionToken)) {
            Log.d(TAG, "Ignoring onStopped from stale session: $sessionToken")
            return
        }
        ContinuousCaptureCoordinator.endSession(sessionToken)
        transitionAudioInputToRecognition()
        serviceScope.launch {
            if (!isSessionActive(sessionToken)) return@launch
            listener.onSessionStateChanged(FloatingBallState.Processing)
            snapshotAudioDurationIfPossible()
            if (!isSessionActive(sessionToken)) return@launch
            // 确保归还音频焦点
            recordingAudioFocusController.release()
            setRecordingKeepScreenOn(active = false)
            if (!isSessionActive(sessionToken)) return@launch
            startProcessingTimeout(sessionToken, lastAudioMsForStats)
        }
    }

    private fun onRecordingAudioFocusLost(loss: RecordingAudioFocusLoss) {
        Log.w(TAG, "Recording audio focus lost: $loss")
        if (activeSessionToken == 0L) return
        stopRecording()
    }

    private fun setRecordingKeepScreenOn(active: Boolean) {
        if (active && prefs.keepScreenOnWhileRecording) {
            keepScreenOnController.acquire()
        } else {
            keepScreenOnController.release()
        }
    }

    private fun onPartial(sessionToken: Long, text: String) {
        if (!isSessionActive(sessionToken)) {
            Log.d(TAG, "Ignoring onPartial from stale session: $sessionToken")
            return
        }
        updatePreviewText(text)
    }

    private fun onAmplitude(sessionToken: Long, amplitude: Float) {
        if (!isSessionActive(sessionToken)) return
        listener.onAmplitude(amplitude)
    }

    private fun onError(sessionToken: Long, message: String) {
        Log.e(TAG, "onError called: $message")
        if (!isSessionActive(sessionToken)) {
            Log.d(TAG, "Ignoring onError from stale session: $sessionToken")
            return
        }
        archiveHistoryFailure(
            status = AsrHistoryStore.AsrHistoryStatus.FAILED,
            failStage = currentHistoryFailStage(),
            failReasonCode = AsrErrorMessageMapper.classify(context, message),
            rawText = lastPartialForPreview
        )
        historyAudioCapture = null
        activeHistoryRecordId = null
        (asrEngine as? AudioFrameSinkOwner)?.audioFrameSink = null
        serviceScope.launch {
            if (!isSessionActive(sessionToken)) return@launch
            try {
                processingTimeoutJob?.cancel()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to cancel timeout job in onError", e)
            }
            processingTimeoutJob = null
            if (!isSessionActive(sessionToken)) return@launch
            stopActiveEngineIfRunning("listener_onError")
            releaseRecordingResources("listener_onError")
            clearActiveSessionToken(sessionToken)

            clearImeBridgeComposingPreview("listener_onError")
            listener.onSessionStateChanged(FloatingBallState.Error(message))
            listener.onError(message)
            clearPreviewSessionContext()
        }
    }

    // ==================== 私有辅助方法 ====================

    private fun checkLocalModelError(): String? {
        val vendor = prefs.asrVendor
        val localEntry = AsrLocalModelCatalog.entryFor(vendor) ?: return null
        val lifecycle = localEntry.lifecycle

        val prepared = try {
            lifecycle.isPrepared()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check local model preparation", e)
            false
        }
        if (prepared) return null

        val check = AsrLocalModelCatalog.modelStatus(context, prefs, vendor) ?: return null
        if (check is LocalModelCheck.Ready<*>) return null
        return localModelErrorMessage(
            context,
            check,
            localEntry.missingModelErrorRes
        )
    }

    private fun buildEngineForCurrentMode(sessionToken: Long): StreamingAsrEngine? {
        val engineListener = createEngineListener(sessionToken)
        val requestDurationCallback: (Long) -> Unit = { ms -> onRequestDuration(sessionToken, ms) }
        val primaryVendor = prefs.asrVendor
        val backupVendor = prefs.backupAsrVendor
        val parallelEngine = parallelEngineFactory.createOrNull(
            context = context,
            scope = serviceScope,
            prefs = prefs,
            listener = engineListener,
            primaryVendor = primaryVendor,
            backupVendor = backupVendor,
            externalPcmInput = false,
            onPrimaryRequestDuration = requestDurationCallback,
            onBackupRequestDuration = requestDurationCallback
        )
        if (parallelEngine != null) return parallelEngine
        if (!isPrimaryVendorConstructible(primaryVendor)) return null

        // 悬浮球主路径已先做在线配置预校验；本地供应商保持可构造，
        // 让模型加载等待与缺模型提示继续走现有 UI 流程。
        return directMicrophoneEngineFactory.create(
            context = context,
            scope = serviceScope,
            prefs = prefs,
            listener = engineListener,
            vendor = primaryVendor,
            preferences = prefs.asrEngineModePreferencesSnapshot(),
            source = AsrEngineConstructionSource.App,
            onRequestDuration = requestDurationCallback
        )
    }

    private fun isPrimaryVendorConstructible(vendor: AsrVendor): Boolean = when {
        // 本地模型即使尚未就绪也允许构造，保留加载等待与缺模型提示路径。
        isLocalAsrVendor(vendor) -> true
        vendor == AsrVendor.SiliconFlow -> prefs.hasSfKeys()
        else -> prefs.hasVendorKeys(vendor)
    }

    private fun onRequestDuration(sessionToken: Long, ms: Long) {
        if (!isSessionActive(sessionToken)) {
            Log.d(TAG, "Ignoring request duration from stale session: $sessionToken")
            return
        }
        val waitMs = localModelReadyWaitMs.getAndSet(LOCAL_MODEL_READY_WAIT_CONSUMED)
        val adjusted = if (waitMs > 0L && ms > waitMs) ms - waitMs else ms
        lastRequestDurationMs = adjusted
        // 仅对首次“等待模型就绪”的请求做一次扣减，避免后续分段请求被重复扣除（同时避免晚写覆盖）。
        Log.d(TAG, "Request duration: ${adjusted}ms")
    }

    private fun onBackupAsrLoading(sessionToken: Long) {
        if (!isSessionActive(sessionToken)) return
        listener.onSessionStateChanged(FloatingBallState.Processing)
        showShortToast(context.getString(com.brycewg.asrkb.R.string.status_backup_asr_loading))
    }

    private fun onBackupAsrRecognizing(sessionToken: Long) {
        if (!isSessionActive(sessionToken)) return
        listener.onSessionStateChanged(FloatingBallState.Processing)
        showShortToast(context.getString(com.brycewg.asrkb.R.string.status_backup_asr_recognizing))
    }

    private fun showShortToast(message: String) {
        try {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to show backup ASR status toast", t)
        }
    }

    private fun startProcessingTimeout(sessionToken: Long, audioMsOverride: Long? = null) {
        if (!isSessionActive(sessionToken)) return
        try {
            processingTimeoutJob?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to cancel previous timeout job", e)
        }
        val audioMs = audioMsOverride ?: lastAudioMsForStats
        val backupEngine = asrEngine as? BackupAwareAsrEngine
        val primaryVendor = backupEngine?.primaryVendor ?: sessionPrimaryVendor
        val backupVendor = backupEngine?.backupVendor
        val timeoutMs = AsrTimeoutCalculator.calculateBackupAwareProcessingTimeoutMs(
            audioMs = audioMs,
            primaryVendor = primaryVendor,
            primaryStatsSnapshot = prefs.getAsrRuntimeStatsSnapshotOrNull(primaryVendor, audioMs),
            backupStrategy = backupEngine?.backupStrategy,
            backupVendor = backupVendor,
            backupStatsSnapshot = prefs.getAsrRuntimeStatsSnapshotOrNull(backupVendor, audioMs),
            sensitivityTier = safeBackupSensitivityTier(),
            primaryStreaming = backupEngine?.primaryStreamingForSwitchPlan ?: true,
            pendingRetryCount = (asrEngine as? ProgressiveRetryStatusOwner)
                ?.peekPendingRetryCount() ?: 0,
            extraAiBudgetMs = promptSelectionSessionSlackMs(prefs)
        )
        processingTimeoutJob = serviceScope.launch {
            if (!isSessionActive(sessionToken)) return@launch
            val shouldDeferForLocalModel = try {
                backupEngine == null && isLocalAsrVendor(prefs.asrVendor)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to determine local ASR vendor for timeout gating", t)
                false
            }
            if (shouldDeferForLocalModel) {
                val wasReady = try {
                    isLocalAsrReady(prefs)
                } catch (_: Throwable) {
                    false
                }
                val t0 = try {
                    SystemClock.uptimeMillis()
                } catch (_: Throwable) {
                    0L
                }
                val ok = awaitLocalAsrReady(prefs, maxWaitMs = LOCAL_MODEL_READY_WAIT_MAX_MS)
                if (!ok) {
                    Log.w(
                        TAG,
                        "awaitLocalAsrReady returned false, fallback to immediate timeout countdown"
                    )
                }
                if (ok && !wasReady && t0 > 0L) {
                    val t1 = try {
                        SystemClock.uptimeMillis()
                    } catch (_: Throwable) {
                        0L
                    }
                    if (t1 >= t0) {
                        localModelReadyWaitMs.compareAndSet(0L, (t1 - t0).coerceAtLeast(0L))
                    }
                }
            }
            if (!isSessionActive(sessionToken)) return@launch
            delay(timeoutMs)
            if (!isSessionActive(sessionToken)) return@launch
            if (!hasCommittedResult) {
                Log.d(TAG, "Processing timeout fired: audioMs=$audioMs, timeoutMs=$timeoutMs")
                handleProcessingTimeout(sessionToken)
            }
        }
        Log.d(TAG, "Processing timeout scheduled: audioMs=$audioMs, timeoutMs=$timeoutMs")
    }

    private fun safeBackupSensitivityTier(): Int = try {
        prefs.backupAsrTimeoutSensitivity
    } catch (_: Throwable) {
        1
    }

    private suspend fun handleProcessingTimeout(sessionToken: Long) {
        if (!isSessionActive(sessionToken)) return
        stopActiveEngineIfRunning("processing_timeout")
        releaseRecordingResources("processing_timeout")
        val candidate = lastPartialForPreview?.trim().orEmpty()
        Log.w(TAG, "Finalize timeout; fallback with preview='$candidate'")
        if (candidate.isEmpty()) {
            Log.w(TAG, "Fallback has no candidate text; only clear state")
            archiveHistoryFailure(
                status = AsrHistoryStore.AsrHistoryStatus.FAILED,
                failStage = AsrHistoryStore.AsrHistoryFailStage.RECOGNITION,
                failReasonCode = AsrFailReasonCodes.TIMEOUT,
                rawText = lastPartialForPreview
            )
            historyAudioCapture = null
            activeHistoryRecordId = null
            clearActiveSessionToken(sessionToken)
            clearImeBridgeComposingPreview("processing_timeout_empty")
            listener.onSessionStateChanged(FloatingBallState.Idle)
            clearPreviewSessionContext()
            return
        }

        transitionAudioInputToRecognition()
        activeHistoryTiming?.end(AsrHistoryTimingStage.RECOGNITION)
        activeHistoryTiming?.begin(AsrHistoryTimingStage.POSTPROCESS)

        val textOut = if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
            try {
                val res = com.brycewg.asrkb.util.AsrFinalFilters.applyWithAi(
                    context,
                    prefs,
                    candidate,
                    postproc,
                    aiTimingObserver = object : com.brycewg.asrkb.util.AsrFinalFilters.AiPostprocessTimingObserver {
                        override fun onAiPostprocessStarted() {
                            activeHistoryTiming?.end(AsrHistoryTimingStage.POSTPROCESS)
                            activeHistoryTiming?.begin(AsrHistoryTimingStage.AI_POSTPROCESS)
                        }

                        override fun onAiPostprocessFinished() {
                            activeHistoryTiming?.end(AsrHistoryTimingStage.AI_POSTPROCESS)
                            activeHistoryTiming?.begin(AsrHistoryTimingStage.POSTPROCESS)
                        }

                        override fun onPromptSelectionStarted() {
                            activeHistoryTiming?.end(AsrHistoryTimingStage.POSTPROCESS)
                            activeHistoryTiming?.begin(AsrHistoryTimingStage.PROMPT_SELECTION)
                        }

                        override fun onPromptSelectionFinished() {
                            activeHistoryTiming?.end(AsrHistoryTimingStage.PROMPT_SELECTION)
                            activeHistoryTiming?.begin(AsrHistoryTimingStage.POSTPROCESS)
                        }
                    },
                    // 超时兜底仍属自动流程。
                    promptSelectionMode = com.brycewg.asrkb.util.AsrFinalFilters.PromptSelectionMode.AUTO_IF_ENABLED,
                    isCancelled = { !isSessionActive(sessionToken) }
                )
                if (!isSessionActive(sessionToken)) return
                val aiUsed = (res.usedAi && res.ok)
                lastAiUsed = aiUsed
                lastAiPostMs = if (res.attempted) res.llmMs else 0L
                lastAiPostStatus = when {
                    res.attempted && aiUsed -> AsrHistoryStore.AiPostStatus.SUCCESS
                    res.attempted -> AsrHistoryStore.AiPostStatus.FAILED
                    else -> AsrHistoryStore.AiPostStatus.NONE
                }
                lastLlmVendorId = res.llmVendorId
                lastPromptSelection = res.promptSelectionStatus
                res.text.ifBlank {
                    com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, candidate)
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                if (!isSessionActive(sessionToken)) return
                Log.e(TAG, "applyWithAi failed in timeout fallback", t)
                lastAiUsed = false
                lastAiPostMs = 0L
                lastAiPostStatus = AsrHistoryStore.AiPostStatus.FAILED
                lastLlmVendorId = null
                lastPromptSelection = null
                com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, candidate)
            }
        } else {
            if (!isSessionActive(sessionToken)) return
            lastAiUsed = false
            lastAiPostMs = 0L
            lastAiPostStatus = AsrHistoryStore.AiPostStatus.NONE
            lastLlmVendorId = null
            lastPromptSelection = null
            com.brycewg.asrkb.util.AsrFinalFilters.applySimple(context, prefs, candidate)
        }
        if (!isSessionActive(sessionToken)) return

        if (completedHistoryRecordId == null && textOut.isNotBlank()) {
            completedHistoryRecordId = activeHistoryRecordId
            completedHistoryRawText = candidate
            completedHistoryTiming = activeHistoryTiming
            historyAudioCapture?.complete()
            historyAudioCapture = null
            activeHistoryRecordId = null
            activeHistoryTiming = null
        }
        transitionPostprocessToDelivery()
        val success = insertTextToFocus(textOut)
        Log.d(TAG, "Fallback inserted=$success text='$textOut'")
        clearActiveSessionToken(sessionToken)
        listener.onResultCommitted(textOut, success)
        hasCommittedResult = true

        listener.onSessionStateChanged(FloatingBallState.Idle)
        clearPreviewSessionContext()
    }

    private fun insertTextToFocus(text: String): Boolean {
        val preview = lastPartialForPreview
        val hadPreview = !preview.isNullOrEmpty()
        if (useImeBridgeForSession) {
            val bridgeResult = synchronized(bridgeOperationLock) {
                bridgePreviewSequence.incrementAndGet()
                imeBridgeClient.insertText(text, sessionId = imeBridgeSessionId)
            }
            logInsertFinal(
                path = "bridge",
                text = text,
                preview = preview,
                hadPreview = hadPreview,
                ok = bridgeResult.isSuccess,
                extra = mapOf(
                    "code" to bridgeResult.code,
                    "pkg" to bridgeResult.targetPackage
                )
            )
            if (bridgeResult.isSuccess) {
                imeBridgeSessionId = null
                recordAsrUsage(text)
                return true
            }
            Log.w(
                TAG,
                "IME bridge insert failed: code=${bridgeResult.code}, target=${bridgeResult.targetPackage}, message=${bridgeResult.message}"
            )
            showImeBridgeInsertFailure(bridgeResult)
            clearImeBridgeComposingPreview("final_commit_failed")
            return false
        }

        val ctx =
            focusContext ?: com.brycewg.asrkb.ui.AsrAccessibilityService.getCurrentFocusContext()
        val prefix = stripMarkersIfAny(ctx?.prefix ?: "")
        val suffix = stripMarkersIfAny(ctx?.suffix ?: "")
        val delta = stripMarkersIfAny(text)
        Log.d(TAG, "Inserting text: deltaLen=${delta.length} (previewCtx=${ctx != null})")

        val pkg = com.brycewg.asrkb.ui.AsrAccessibilityService.getActiveWindowPackage()
        val result = com.brycewg.asrkb.ui.AsrAccessibilityService.insertText(
            context,
            delta,
            prefix,
            suffix
        )
        logInsertFinal(
            path = "a11y",
            text = text,
            preview = preview,
            hadPreview = hadPreview,
            ok = result.ok,
            extra = mapOf(
                "pkg" to pkg,
                "prefixLen" to prefix.length,
                "suffixLen" to suffix.length,
                "deltaLen" to delta.length,
                "android13Api" to skipA11ySetTextPreviewForSession,
                "writePath" to result.id
            )
        )

        if (result.ok) {
            recordAsrUsage(text)
            if (result == InsertPath.SET_TEXT) {
                // 光标应定位到“前缀 + 新文本”的末尾；占位符已从前缀中移除。
                // commitText / PASTE 由系统或目标控件自己移动光标，不能按快照前缀重定位。
                val prefixLenForCursor = stripMarkersIfAny(ctx?.prefix ?: "").length
                val desiredCursor = (prefixLenForCursor + text.length).coerceAtLeast(0)
                com.brycewg.asrkb.ui.AsrAccessibilityService.setSelectionSilent(desiredCursor)
            }
        }

        return result.ok
    }

    private fun showImeBridgeInsertFailure(bridgeResult: ImeBridgeResult) {
        // 仅当桥接警告 toast 本次实际展示过才跳过，避免被冷却期抑制后用户完全无反馈
        val warningRes = imeBridgeWarningMessageRes(bridgeResult.code, warnOnFailure = true)
        val warningShown = warningRes != null &&
            ImeBridgeWarningToast.wasShownWithin(
                warningRes,
                SystemClock.elapsedRealtime(),
                WARNING_TOAST_RECENT_WINDOW_MS
            )
        if (warningShown) return
        val isSensitiveField = bridgeResult.isSensitiveField ||
            bridgeResult.code == ImeBridgeContract.RESULT_SENSITIVE_FIELD
        val message = if (isSensitiveField) {
            context.getString(com.brycewg.asrkb.R.string.floating_ime_bridge_sensitive_field)
        } else {
            val detail = bridgeResult.message.ifBlank {
                ImeBridgeClient.messageForCode(bridgeResult.code)
            }
            context.getString(com.brycewg.asrkb.R.string.floating_ime_bridge_insert_failed, detail)
        }
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(
                context,
                message,
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun prepareImeBridgeSession(): Boolean {
        val result = try {
            imeBridgeClient.queryStatus(timeoutMs = 180L)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to query IME bridge session support", t)
            return false
        }
        if (result.isSuccess && result.supportsSessions) {
            val sessionId = UUID.randomUUID().toString()
            val beginResult = try {
                imeBridgeClient.beginSession(sessionId)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to begin IME bridge session", t)
                null
            }
            if (beginResult?.isSuccess == true) {
                imeBridgeSessionId = sessionId
                return result.supportsComposingPreview
            }
            if (beginResult != null && beginResult.isBridgePresent) {
                Log.d(
                    TAG,
                    "IME bridge session disabled: code=${beginResult.code}, " +
                        "target=${beginResult.targetPackage}, message=${beginResult.message}"
                )
            }
            return false
        }

        val enabled = result.isSuccess && result.supportsComposingPreview
        if (!enabled && result.isBridgePresent) {
            Log.d(
                TAG,
                "IME bridge composing preview disabled: code=${result.code}, " +
                    "supportsPreview=${result.supportsComposingPreview}, " +
                    "supportsSessions=${result.supportsSessions}, target=${result.targetPackage}"
            )
        }
        return enabled
    }

    private fun isImeBridgeEnabled(): Boolean = try {
        prefs.floatingImeBridgeEnabled
    } catch (e: Throwable) {
        Log.w(TAG, "Failed to get IME bridge preference", e)
        false
    }

    private fun recordAsrUsage(text: String) {
        try {
            if (!prefs.disableUsageStats) {
                prefs.addAsrChars(TextSanitizer.countEffectiveChars(text))
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to add ASR chars", e)
        }
    }

    private fun shouldSkipA11ySetTextPreview(): Boolean = skipA11ySetTextPreviewForSession

    private fun tryFixCompatPlaceholderIfNeeded() {
        markerInserted = false
        markerChar = null
        if (shouldSkipA11ySetTextPreview()) return
        val pkg = com.brycewg.asrkb.ui.AsrAccessibilityService.getActiveWindowPackage() ?: return
        val compat = try {
            prefs.floatingWriteTextCompatEnabled
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get write compat preference", e)
            true
        }
        if (!compat || !isPackageInCompatTargets(pkg)) return

        val candidates = listOf("\u2060", "\u200B")
        for (m in candidates) {
            val ok = com.brycewg.asrkb.ui.AsrAccessibilityService.pasteTextSilent(m)
            if (ok) {
                markerInserted = true
                markerChar = m
                Log.d(TAG, "Compat fix: injected marker ${Integer.toHexString(m.codePointAt(0))}")
                break
            }
        }
    }

    private fun stripMarkersIfAny(s: String): String {
        var out = s
        markerChar?.let { if (it.isNotEmpty()) out = out.replace(it, "") }
        out = out.replace("\u2060", "")
        out = out.replace("\u200B", "")
        return out
    }

    private fun updatePreviewText(text: String) {
        if (text.isEmpty() || lastPartialForPreview == text) return
        val prev = lastPartialForPreview
        StreamingPreviewDiag.logVerbose(
            category = "float",
            event = "preview_write",
            prev = prev,
            next = text,
            extra = mapOf(
                "bridge" to useImeBridgeForSession,
                "composing" to useImeBridgeComposingPreviewForSession,
                "prefixLen" to (focusContext?.prefix?.length ?: -1),
                "suffixLen" to (focusContext?.suffix?.length ?: -1)
            )
        )
        if (useImeBridgeForSession) {
            lastPartialForPreview = text
            if (useImeBridgeComposingPreviewForSession) {
                updateImeBridgeComposingPreview(text)
            }
            return
        }
        if (shouldSkipA11ySetTextPreview()) {
            lastPartialForPreview = text
            return
        }
        val ctx = focusContext ?: return
        lastPartialForPreview = text
        val toWrite = ctx.prefix + text + ctx.suffix
        Log.d(TAG, "preview update: $text")

        serviceScope.launch {
            com.brycewg.asrkb.ui.AsrAccessibilityService.insertTextSilent(toWrite)
            val prefixLenForCursor = stripMarkersIfAny(ctx.prefix).length
            val desiredCursor = (prefixLenForCursor + text.length).coerceAtLeast(0)
            com.brycewg.asrkb.ui.AsrAccessibilityService.setSelectionSilent(desiredCursor)
        }
    }

    private fun updateImeBridgeComposingPreview(text: String) {
        val previewSequence = bridgePreviewSequence.incrementAndGet()
        serviceScope.launch(Dispatchers.IO) {
            val result = synchronized(bridgeOperationLock) {
                if (previewSequence != bridgePreviewSequence.get() ||
                    !useImeBridgeForSession ||
                    !useImeBridgeComposingPreviewForSession
                ) {
                    null
                } else {
                    imeBridgeClient.setComposingText(text, sessionId = imeBridgeSessionId)
                }
            }
            if (result != null) {
                try {
                    DebugLogManager.log(
                        category = "float",
                        event = "bridge_composing",
                        data = mapOf(
                            "ok" to result.isSuccess,
                            "code" to result.code,
                            "pkg" to result.targetPackage,
                            "fp" to StreamingPreviewDiag.fingerprint(text),
                            "seq" to previewSequence
                        )
                    )
                } catch (_: Throwable) { }
            }
            if (result != null && !result.isSuccess && result.isBridgePresent) {
                Log.d(
                    TAG,
                    "IME bridge composing preview failed: code=${result.code}, target=${result.targetPackage}, message=${result.message}"
                )
            }
        }
    }

    private fun clearImeBridgeComposingPreview(reason: String) {
        if (!useImeBridgeForSession) {
            return
        }
        val sessionIdSnapshot = imeBridgeSessionId
        if (!sessionIdSnapshot.isNullOrEmpty()) {
            imeBridgeSessionId = null
        }
        val hadComposingPreview = useImeBridgeComposingPreviewForSession &&
            !lastPartialForPreview.isNullOrEmpty()
        val clearSequence = bridgePreviewSequence.incrementAndGet()
        serviceScope.launch(Dispatchers.IO) {
            val result = synchronized(bridgeOperationLock) {
                if (clearSequence != bridgePreviewSequence.get()) {
                    null
                } else {
                    if (!sessionIdSnapshot.isNullOrEmpty()) {
                        imeBridgeClient.cancelSession(sessionIdSnapshot)
                    } else if (hadComposingPreview) {
                        val clearResult = imeBridgeClient.setComposingText("")
                        if (clearResult.isSuccess) {
                            imeBridgeClient.finishComposingText()
                        } else {
                            clearResult
                        }
                    } else {
                        null
                    }
                }
            } ?: return@launch
            if (!result.isSuccess && result.isBridgePresent) {
                Log.d(
                    TAG,
                    "IME bridge clear composing preview failed: reason=$reason, " +
                        "code=${result.code}, message=${result.message}"
                )
            }
        }
    }

    private fun isPackageInCompatTargets(pkg: String): Boolean {
        val raw = try {
            prefs.floatingWriteCompatPackages
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to get compat packages", e)
            ""
        }
        val rules = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        // 前缀匹配（包名边界）
        return rules.any { rule -> pkg == rule || pkg.startsWith("$rule.") }
    }

    private fun logFloatTypewriterSubmit(
        typewriter: TypewriterTextAnimator,
        target: String,
        rush: Boolean
    ) {
        val current = typewriter.currentText()
        try {
            DebugLogManager.log(
                category = "float",
                event = "typewriter_submit",
                data = StreamingPreviewDiag.shape(current, target) + mapOf(
                    "rush" to rush,
                    "curLen" to current.length,
                    "tgtLen" to target.length,
                    "rewrite" to !target.startsWith(current)
                )
            )
        } catch (_: Throwable) { }
    }

    private fun logInsertFinal(
        path: String,
        text: String,
        preview: String?,
        hadPreview: Boolean,
        ok: Boolean,
        extra: Map<String, Any?> = emptyMap()
    ) {
        try {
            DebugLogManager.logBase(
                category = "float",
                event = "insert_final",
                data = extra + StreamingPreviewDiag.shape(preview, text) + mapOf(
                    "path" to path,
                    "hadPreview" to hadPreview,
                    "ok" to ok,
                    "textLen" to text.length
                )
            )
        } catch (_: Throwable) { }
        StreamingPreviewDiag.maybeWarnDup(
            category = "float",
            at = "insert_final",
            prev = preview,
            next = text,
            extra = mapOf("path" to path)
        )
    }
}
