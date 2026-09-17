package com.brycewg.asrkb.ime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrCallLatencyProbe
import com.brycewg.asrkb.asr.AsrFailReasonCodes
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.BackupAwareAsrEngine
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.asr.VadAutoStopGuard
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.store.debug.StreamingPreviewDiag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 键盘动作处理器：作为控制器/ViewModel 管理键盘的核心状态和业务逻辑
 *
 * 职责：
 * - 管理键盘状态机（使用 KeyboardState）
 * - 处理所有用户操作（麦克风、AI编辑、后处理等）
 * - 协调各个组件（AsrSessionManager, InputConnectionHelper, LlmPostProcessor）
 * - 处理 ASR 回调并触发状态转换
 * - 管理会话上下文（撤销快照、最后提交的文本等）
 * - 触发 UI 更新
 */
class KeyboardActionHandler(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val asrManager: AsrSessionManager,
    private val inputHelper: InputConnectionHelper,
    private val llmPostProcessor: LlmPostProcessor
) : AsrSessionManager.Listener {

    companion object {
        private const val TAG = "KeyboardActionHandler"
    }

    // 回调接口：通知 UI 更新
    interface UiListener {
        fun onStateChanged(state: KeyboardState)
        fun onStatusMessage(message: String)
        fun onVibrate()
        fun onShowClipboardPreview(preview: ClipboardPreview)
        fun onHideClipboardPreview()
        fun onShowRetryChip(label: String)
        fun onHideRetryChip()
        fun onShowPostprocessUndo(label: String)
        fun onHidePostprocessUndo()
        fun onAmplitude(amplitude: Float) { /* 默认空实现 */ }
        fun onDictationInputSolidified() { /* 默认空实现 */ }
    }

    private var uiListener: UiListener? = null

    // 当前键盘状态
    private var currentState: KeyboardState = KeyboardState.Idle

    // 会话上下文
    private var sessionContext = KeyboardSessionContext()

    // 强制停止标记：用于忽略上一会话迟到的 onFinal/onStopped
    private var dropPendingFinal: Boolean = false

    // 操作序列号：用于取消在途处理（强制停止/新会话开始都会递增）
    private var opSeq: Long = 0L

    private val undoManager = UndoManager(inputHelper = inputHelper, logTag = TAG, maxSnapshots = 3)

    private val processingTimeoutController = ProcessingTimeoutController(
        scope = scope,
        prefs = prefs,
        logTag = TAG,
        currentStateProvider = { currentState },
        opSeqProvider = { opSeq },
        audioMsProvider = { asrManager.peekLastAudioMsForStats() },
        backupEngineProvider = {
            asrManager.getEngine() as? BackupAwareAsrEngine
        },
        onTimeout = {
            dropPendingFinal = true
            asrManager.abandonPendingRecognition(
                status = AsrHistoryStore.AsrHistoryStatus.FAILED,
                failStage = AsrHistoryStore.AsrHistoryFailStage.RECOGNITION,
                failReasonCode = AsrFailReasonCodes.TIMEOUT
            )
            transitionToIdle()
        }
    )

    private val commitRecorder = AsrCommitRecorder(
        context = context,
        prefs = prefs,
        asrManager = asrManager,
        logTag = TAG
    )

    private val postprocessPipeline = PostprocessPipeline(
        context = context,
        scope = scope,
        prefs = prefs,
        inputHelper = inputHelper,
        llmPostProcessor = llmPostProcessor,
        logTag = TAG
    )

    private val dictationUseCase = DictationUseCase(
        context = context,
        prefs = prefs,
        asrManager = asrManager,
        inputHelper = inputHelper,
        processingTimeoutController = processingTimeoutController,
        postprocessPipeline = postprocessPipeline,
        commitRecorder = commitRecorder,
        uiListenerProvider = { uiListener },
        getCurrentEditorInfo = { getCurrentEditorInfo() },
        isCancelled = { seq -> seq != opSeq },
        consumeAutoEnterOnce = { consumeAutoEnterOnce() },
        updateSessionContext = { transform -> sessionContext = transform(sessionContext) },
        transitionToState = { transitionToState(it) },
        transitionToIdle = { keepMessage -> transitionToIdle(keepMessage = keepMessage) },
        transitionToIdleWithTiming = { showBackupUsedHint ->
            transitionToIdleWithTiming(showBackupUsedHint)
        },
        scheduleProcessingTimeout = { audioMsOverride ->
            scheduleProcessingTimeout(audioMsOverride)
        },
        onPostprocessUndoAvailable = {
            uiListener?.onShowPostprocessUndo(context.getString(R.string.action_revert_postprocess))
        }
    )

    private val aiEditUseCase = AiEditUseCase(
        context = context,
        prefs = prefs,
        asrManager = asrManager,
        inputHelper = inputHelper,
        llmPostProcessor = llmPostProcessor,
        uiListenerProvider = { uiListener },
        currentStateProvider = { currentState },
        getCurrentInputConnection = { getCurrentInputConnection() },
        isCancelled = { seq -> seq != opSeq },
        getLastAsrCommitText = { sessionContext.lastAsrCommitText },
        updateSessionContext = { transform -> sessionContext = transform(sessionContext) },
        transitionToState = { transitionToState(it) },
        transitionToIdle = { keepMessage -> transitionToIdle(keepMessage = keepMessage) },
        transitionToIdleWithTiming = { showBackupUsedHint ->
            transitionToIdleWithTiming(showBackupUsedHint)
        }
    )

    private val promptApplyUseCase = PromptApplyUseCase(
        context = context,
        scope = scope,
        prefs = prefs,
        inputHelper = inputHelper,
        llmPostProcessor = llmPostProcessor,
        uiListenerProvider = { uiListener },
        saveUndoSnapshot = { ic -> saveUndoSnapshot(ic) },
        getLastAsrCommitText = { sessionContext.lastAsrCommitText },
        updateSessionContext = { transform -> sessionContext = transform(sessionContext) },
        logTag = TAG
    )

    private val extensionButtonDispatcher = ExtensionButtonActionDispatcher(
        context = context,
        prefs = prefs,
        inputHelper = inputHelper,
        uiListenerProvider = { uiListener },
        handleUndo = { ic -> handleUndo(ic) },
        logTag = TAG
    )

    private val retryUseCase = RetryUseCase(
        context = context,
        asrManager = asrManager,
        uiListenerProvider = { uiListener },
        transitionToState = { transitionToState(it) },
        transitionToIdle = { transitionToIdle() },
        scheduleProcessingTimeout = { scheduleProcessingTimeout() },
        logTag = TAG
    )

    // 长按期间的"按住状态"和自动重启计数（用于应对录音被系统提前中断的设备差异）
    private var micHoldActive: Boolean = false
    private var micHoldRestartCount: Int = 0
    private var autoStopSuppression: AutoCloseable? = null

    // 自动启动录音标志：标识当前录音是否由键盘面板自动启动
    private var isAutoStartedRecording: Boolean = false

    private fun scheduleProcessingTimeout(audioMsOverride: Long? = null) {
        processingTimeoutController.schedule(audioMsOverride)
    }

    private fun ensureAutoStopSuppressed() {
        if (autoStopSuppression != null) return
        autoStopSuppression = VadAutoStopGuard.acquire()
    }

    private fun releaseAutoStopSuppression() {
        val token = autoStopSuppression ?: return
        autoStopSuppression = null
        try {
            token.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release auto-stop suppression", t)
        }
    }

    fun setUiListener(listener: UiListener) {
        uiListener = listener
    }

    /**
     * 获取当前状态
     */
    fun getCurrentState(): KeyboardState = currentState

    fun isMicLockedBySwipe(): Boolean {
        val state = currentState as? KeyboardState.Listening
        return state?.lockedBySwipe == true
    }

    /**
     * 启动自动录音（键盘面板自动启动）
     * 此录音的停止方式：点按麦克风按钮或VAD自动停止
     */
    fun startAutoRecording() {
        if (currentState !is KeyboardState.Idle) {
            Log.w(TAG, "startAutoRecording: ignored in non-idle state $currentState")
            return
        }
        isAutoStartedRecording = true
        startNormalListening()
        try {
            DebugLogManager.log("ime", "auto_start_recording", mapOf("opSeq" to opSeq))
        } catch (_: Throwable) { }
    }

    /**
     * 处理麦克风点击（点按切换模式）
     */
    fun handleMicTapToggle() {
        try {
            DebugLogManager.log(
                category = "ime",
                event = "mic_tap_toggle",
                data = mapOf(
                    "state" to currentState.diagnosticName,
                    "opSeq" to opSeq,
                    "dropPendingFinal" to dropPendingFinal,
                    "isAutoStarted" to isAutoStartedRecording
                )
            )
        } catch (_: Throwable) { }
        when (currentState) {
            is KeyboardState.Idle -> {
                // 开始录音
                startNormalListening()
                try {
                    DebugLogManager.log(
                        "ime",
                        "mic_tap_action",
                        mapOf(
                            "action" to "start_listening",
                            "opSeq" to opSeq
                        )
                    )
                } catch (_: Throwable) { }
            }
            is KeyboardState.Listening -> {
                // 停止录音：如果是自动启动的录音，或者正常的点按模式，都执行停止
                // 统一进入 Processing，显示"识别中"直到最终结果（即使未开启后处理）
                isAutoStartedRecording = false // 清除自动启动标志
                asrManager.stopRecording()
                transitionToState(KeyboardState.Processing)
                scheduleProcessingTimeout()
                uiListener?.onStatusMessage(context.getString(R.string.status_recognizing))
                try {
                    DebugLogManager.log(
                        "ime",
                        "mic_tap_action",
                        mapOf(
                            "action" to "stop_and_process",
                            "opSeq" to opSeq
                        )
                    )
                } catch (_: Throwable) { }
            }
            is KeyboardState.Processing -> {
                // 强制停止：立即回到 Idle，并忽略本会话迟到的 onFinal/onStopped
                processingTimeoutController.cancel()
                markDropPendingRecognition()
                transitionToIdle(keepMessage = true)
                uiListener?.onStatusMessage(context.getString(R.string.status_cancelled))
                try {
                    DebugLogManager.log(
                        "ime",
                        "mic_tap_action",
                        mapOf(
                            "action" to "force_stop",
                            "opSeq" to opSeq
                        )
                    )
                } catch (_: Throwable) { }
            }
            is KeyboardState.AiProcessing -> {
                // 用户主动点麦：打断后处理并开始新一轮录音
                abortAiPostProcessing(
                    reason = "mic_tap",
                    allowAutoEnter = false,
                    notifySolidified = false
                )
                startNormalListening()
                try {
                    DebugLogManager.log(
                        "ime",
                        "mic_tap_action",
                        mapOf(
                            "action" to "abort_ai_and_start_listening",
                            "opSeq" to opSeq
                        )
                    )
                } catch (_: Throwable) { }
            }
            else -> {
                // 其他状态忽略
                Log.w(TAG, "handleMicTapToggle: ignored in state $currentState")
                try {
                    DebugLogManager.log(
                        "ime",
                        "mic_tap_action",
                        mapOf(
                            "action" to "ignored",
                            "state" to currentState.diagnosticName
                        )
                    )
                } catch (_: Throwable) { }
            }
        }
    }

    /**
     * 处理麦克风按下（长按模式）
     */
    fun handleMicPressDown() {
        micHoldActive = true
        micHoldRestartCount = 0
        ensureAutoStopSuppressed()
        try {
            DebugLogManager.log(
                category = "ime",
                event = "mic_down_dispatch",
                data = mapOf(
                    "state" to currentState.diagnosticName,
                    "opSeq" to opSeq,
                    "dropPendingFinal" to dropPendingFinal,
                    "isAutoStarted" to isAutoStartedRecording
                )
            )
        } catch (_: Throwable) { }
        when (currentState) {
            is KeyboardState.Idle -> startNormalListening()
            is KeyboardState.Listening -> {
                // 如果正在录音（可能是自动启动的），长按应该停止并重新开始
                isAutoStartedRecording = false // 清除自动启动标志
            }
            is KeyboardState.AiProcessing -> {
                abortAiPostProcessing(
                    reason = "mic_down",
                    allowAutoEnter = false,
                    notifySolidified = false
                )
                startNormalListening()
                try {
                    DebugLogManager.log(
                        "ime",
                        "mic_down_action",
                        mapOf(
                            "action" to "abort_ai_and_start_listening",
                            "opSeq" to opSeq
                        )
                    )
                } catch (_: Throwable) { }
            }
            is KeyboardState.Processing -> {
                // 强制停止：根据模式决定后续动作
                processingTimeoutController.cancel()
                // 标记忽略上一会话的迟到回调
                markDropPendingRecognition()
                if (!prefs.micTapToggleEnabled) {
                    // 长按模式：直接开始新一轮录音
                    startNormalListening()
                    try {
                        DebugLogManager.log(
                            "ime",
                            "mic_down_action",
                            mapOf(
                                "action" to "force_stop_and_restart",
                                "opSeq" to opSeq
                            )
                        )
                    } catch (
                        _: Throwable
                    ) { }
                } else {
                    // 点按切换模式：仅取消并回到空闲
                    transitionToIdle(keepMessage = true)
                    uiListener?.onStatusMessage(context.getString(R.string.status_cancelled))
                    try {
                        DebugLogManager.log(
                            "ime",
                            "mic_down_action",
                            mapOf(
                                "action" to "force_stop_to_idle",
                                "opSeq" to opSeq
                            )
                        )
                    } catch (
                        _: Throwable
                    ) { }
                }
            }
            else -> {
                Log.w(TAG, "handleMicPressDown: ignored in state $currentState")
                try {
                    DebugLogManager.log(
                        "ime",
                        "mic_down_action",
                        mapOf(
                            "action" to "ignored",
                            "state" to currentState.diagnosticName
                        )
                    )
                } catch (_: Throwable) { }
            }
        }
    }

    fun handleMicSwipeLock() {
        val state = currentState as? KeyboardState.Listening ?: return
        if (state.lockedBySwipe) return
        micHoldActive = false
        releaseAutoStopSuppression()
        autoEnterOnce = false
        isAutoStartedRecording = false
        val newState = state.copy(lockedBySwipe = true)
        transitionToState(newState)
    }

    fun handleMicGestureCancel() {
        val state = currentState as? KeyboardState.Listening ?: return
        markDropPendingRecognition()
        micHoldActive = false
        releaseAutoStopSuppression()
        autoEnterOnce = false
        isAutoStartedRecording = false
        asrManager.cancelRecording(discardPending = true)
        // 清除已显示的 composing text（中间结果），避免取消时被固化提交
        val ic = getCurrentInputConnection()
        if (ic != null && !state.partialText.isNullOrEmpty()) {
            inputHelper.setComposingText(ic, "")
            inputHelper.finishComposingText(ic)
        }
        transitionToIdle(keepMessage = true)
        uiListener?.onStatusMessage(context.getString(R.string.status_cancelled))
        uiListener?.onVibrate()
    }

    fun handleMicGestureSend() {
        if (currentState !is KeyboardState.Listening) return
        handleMicPressUp(autoEnterAfterFinal = true)
    }

    fun handleLockedMicTap() {
        if (currentState !is KeyboardState.Listening) return
        handleMicPressUp(autoEnterAfterFinal = false)
    }

    /**
     * 处理麦克风松开（长按模式）
     */
    fun handleMicPressUp() {
        handleMicPressUp(false)
    }

    /**
     * 处理麦克风松开（长按模式，可选：最终结果后自动回车）
     */
    fun handleMicPressUp(autoEnterAfterFinal: Boolean) {
        autoEnterOnce = autoEnterAfterFinal
        micHoldActive = false
        releaseAutoStopSuppression()
        isAutoStartedRecording = false // 清除自动启动标志
        try {
            DebugLogManager.log(
                category = "ime",
                event = "mic_up_dispatch",
                data = mapOf(
                    "autoEnter" to autoEnterAfterFinal,
                    "state" to currentState.diagnosticName,
                    "opSeq" to opSeq
                )
            )
        } catch (_: Throwable) { }
        if (asrManager.isRunning()) {
            asrManager.stopRecording()
            // 进入处理阶段（无论是否开启后处理）
            transitionToState(KeyboardState.Processing)
            scheduleProcessingTimeout()
            uiListener?.onStatusMessage(context.getString(R.string.status_recognizing))
            try {
                DebugLogManager.log(
                    "ime",
                    "mic_up_action",
                    mapOf(
                        "action" to "stop_and_process",
                        "autoEnter" to autoEnterAfterFinal,
                        "opSeq" to opSeq
                    )
                )
            } catch (_: Throwable) { }
        } else {
            // 异常：UI 处于 Listening，但引擎未在运行（例如启动失败/被系统打断）。
            // 为避免卡住“正在聆听”，直接归位到 Idle 并提示“已取消”。
            if (currentState is KeyboardState.Listening ||
                currentState is KeyboardState.AiEditListening
            ) {
                // 确保释放音频焦点/路由（即使引擎未在运行）
                try {
                    asrManager.stopRecording()
                } catch (_: Throwable) { }
                transitionToIdle(keepMessage = true)
                uiListener?.onStatusMessage(context.getString(R.string.status_cancelled))
                try {
                    DebugLogManager.log(
                        "ime",
                        "mic_up_action",
                        mapOf(
                            "action" to "not_running_cancel",
                            "opSeq" to opSeq
                        )
                    )
                } catch (_: Throwable) { }
            }
        }
    }

    /**
     * 处理 AI 编辑按钮点击
     */
    fun handleAiEditClick(ic: InputConnection?) {
        aiEditUseCase.handleClick(ic)
    }

    /**
     * 处理后处理开关切换
     */
    fun handlePostprocessToggle() {
        val enabled = !prefs.postProcessEnabled
        prefs.postProcessEnabled = enabled

        // 切换引擎实现（仅在空闲时）
        if (currentState is KeyboardState.Idle) {
            asrManager.rebuildEngine()
        }

        val state = if (enabled) {
            context.getString(
                R.string.toggle_on
            )
        } else {
            context.getString(R.string.toggle_off)
        }
        uiListener?.onStatusMessage(context.getString(R.string.status_postproc, state))
    }

    fun handleInfoBarClick() {
        abortAiPostProcessing(
            reason = "info_bar",
            allowAutoEnter = true,
            notifySolidified = true
        )
    }

    /**
     * 输入视图被收起：仅打断已经在跑的 AI 后处理（提交原文并回到 Idle）。
     * 听写录音/识别中的收起仍走 stopRecording → 出结果提交，不在这里整段丢弃；
     * 因此再次显示时不得无条件 abort，否则会误杀收起后才开始的后处理。
     */
    fun onInputViewHidden() {
        abortAiPostProcessing(
            reason = "input_view_hidden",
            allowAutoEnter = false,
            notifySolidified = false
        )
    }

    /**
     * 取消进行中的 LLM 后处理并回到 Idle。
     * InputConnection 缺失时仍必须清状态并取消 HTTP，否则麦键会一直被忽略。
     */
    private fun abortAiPostProcessing(
        reason: String,
        allowAutoEnter: Boolean,
        notifySolidified: Boolean
    ): Boolean {
        val state = currentState as? KeyboardState.AiProcessing ?: return false
        val ic = getCurrentInputConnection()
        val rawText = state.rawText

        opSeq++
        try {
            DebugLogManager.log(
                "ime",
                "opseq_inc",
                mapOf("at" to "abort_ai_postprocess", "reason" to reason, "opSeq" to opSeq)
            )
        } catch (_: Throwable) { }
        processingTimeoutController.cancel()
        llmPostProcessor.cancelActiveRequest()
        isAutoStartedRecording = false

        try {
            DebugLogManager.logBase(
                category = "ime",
                event = "abort_ai_postprocess",
                data = mapOf(
                    "reason" to reason,
                    "hasIc" to (ic != null),
                    "seq" to opSeq,
                    "finalLen" to rawText.length
                )
            )
        } catch (_: Throwable) { }

        try {
            DebugLogManager.logBase(
                category = "ime",
                event = "commit_path",
                data = mapOf(
                    "mode" to "cancel_ai_raw",
                    "seq" to opSeq,
                    "finalLen" to rawText.length,
                    "ai" to false,
                    "fp" to StreamingPreviewDiag.fingerprint(rawText)
                )
            )
        } catch (_: Throwable) { }

        if (ic != null) {
            inputHelper.setComposingText(ic, rawText)
            inputHelper.finishComposingText(ic)
            if (allowAutoEnter && rawText.isNotEmpty() && consumeAutoEnterOnce()) {
                try {
                    inputHelper.sendEnter(ic, getCurrentEditorInfo())
                } catch (t: Throwable) {
                    Log.w(TAG, "sendEnter after interrupted postprocess failed", t)
                }
            }
        }
        autoEnterOnce = false

        sessionContext = sessionContext.copy(
            lastAsrCommitText = rawText,
            lastPostprocCommit = null
        )
        val historyTiming = asrManager.takeInFlightHistoryCommitContext()
        historyTiming?.timing?.end(com.brycewg.asrkb.store.AsrHistoryTimingStage.AI_POSTPROCESS)
        historyTiming?.timing?.end(com.brycewg.asrkb.store.AsrHistoryTimingStage.POSTPROCESS)
        historyTiming?.timing?.begin(com.brycewg.asrkb.store.AsrHistoryTimingStage.TEXT_DELIVERY)
        val prepared = try {
            commitRecorder.prepare(
                text = rawText,
                rawText = rawText,
                aiProcessed = false,
                historyTiming = historyTiming
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to prepare interrupted postprocess commit", t)
            null
        }
        if (prepared != null) {
            commitRecorder.recordAsync(prepared)
        }
        transitionToState(KeyboardState.Idle)
        uiListener?.onStatusMessage(context.getString(R.string.status_postprocess_interrupted_raw))
        if (reason != "input_view_hidden") {
            uiListener?.onVibrate()
        }
        if (notifySolidified &&
            ic != null &&
            AsrInputCompletionPolicy.shouldNotifyInputSolidified(
                committedText = rawText,
                sessionStillRunning = asrManager.isRunning()
            )
        ) {
            uiListener?.onDictationInputSolidified()
        }
        return true
    }

    /**
     * 处理全局撤销（优先撤销 AI 后处理，否则从撤销栈恢复快照）
     */
    fun handleUndo(ic: InputConnection?): Boolean {
        if (ic == null) return false

        // 1) 优先撤销最近一次 AI 后处理
        if (revertLastPostprocessCommit(ic)) return true

        // 2) 否则从撤销栈恢复快照
        val remaining = undoManager.popAndRestoreSnapshot(ic)
        if (remaining != null) {
            val message = if (remaining > 0) {
                context.getString(R.string.status_undone) + " ($remaining)"
            } else {
                context.getString(R.string.status_undone)
            }
            uiListener?.onStatusMessage(message)
            return true
        }

        return false
    }

    fun handlePostprocessUndoClick(ic: InputConnection?): Boolean {
        if (ic == null) return false
        val ok = revertLastPostprocessCommit(ic)
        if (!ok) {
            uiListener?.onStatusMessage(context.getString(R.string.status_revert_postprocess_unavailable))
        }
        return ok
    }

    private fun revertLastPostprocessCommit(ic: InputConnection): Boolean {
        val postprocCommit = sessionContext.lastPostprocCommit
        if (postprocCommit != null && postprocCommit.processed.isNotEmpty()) {
            val before = inputHelper.getTextBeforeCursor(ic, 10000)?.toString()
            if (!before.isNullOrEmpty() && before.endsWith(postprocCommit.processed)) {
                if (inputHelper.replaceText(ic, postprocCommit.processed, postprocCommit.raw)) {
                    sessionContext = sessionContext.copy(lastPostprocCommit = null)
                    uiListener?.onHidePostprocessUndo()
                    uiListener?.onStatusMessage(context.getString(R.string.status_reverted_to_raw))
                    return true
                }
            }
        }
        return false
    }

    /**
     * 处理扩展按钮动作（统一入口）
     * @return ExtensionButtonActionResult 包含是否成功和可选的回调需求
     */
    fun handleExtensionButtonClick(
        action: com.brycewg.asrkb.ime.ExtensionButtonAction,
        ic: InputConnection?
    ): ExtensionButtonActionResult {
        val result = extensionButtonDispatcher.dispatch(action, ic)
        if (
            action == com.brycewg.asrkb.ime.ExtensionButtonAction.KEEP_SCREEN_ON_WHILE_RECORDING_TOGGLE &&
            result == ExtensionButtonActionResult.SUCCESS
        ) {
            asrManager.syncRecordingKeepScreenOn()
        }
        return result
    }

    /**
     * 扩展按钮动作结果
     */
    enum class ExtensionButtonActionResult {
        SUCCESS, // 成功完成
        FAILED, // 失败
        NEED_TOGGLE_SELECTION, // 需要 IME 切换选择模式
        NEED_CURSOR_LEFT, // 需要 IME 处理左移（支持长按）
        NEED_CURSOR_RIGHT, // 需要 IME 处理右移（支持长按）
        NEED_SHOW_NUMPAD, // 需要 IME 显示数字键盘
        NEED_SHOW_CLIPBOARD, // 需要 IME 显示剪贴板面板
        NEED_SHOW_ASR_HISTORY, // 需要 IME 显示识别历史面板
        NEED_HIDE_KEYBOARD, // 需要 IME 收起键盘
        NEED_TOGGLE_CONTINUOUS_TALK // 需要 IME 切换畅说模式
    }

    /**
     * 保存撤销快照（在执行变更操作前调用）
     *
     * 优化策略：
     * - 支持多级撤回（最多3个快照）
     * - 如果 force=true，强制保存新快照
     * - 如果当前内容与栈顶快照不同，则保存新快照（智能判断）
     */
    fun saveUndoSnapshot(ic: InputConnection?, force: Boolean = false) {
        if (ic == null) return
        undoManager.saveSnapshot(ic, force)
    }

    /**
     * 提交文本（用于标点按钮等）
     */
    fun commitText(ic: InputConnection?, text: String) {
        if (ic == null) return
        saveUndoSnapshot(ic)
        inputHelper.commitText(ic, text)
    }

    /**
     * 使用当前激活的 Prompt 对文本进行处理：优先处理选区，否则处理整个输入框文本。
     * 成功则用返回结果替换（保留撤销快照）。
     */
    fun applyActivePromptToSelectionOrAll(ic: InputConnection?) {
        promptApplyUseCase.apply(ic, promptOverride = null)
    }

    /**
     * 使用指定的 Prompt 内容对文本进行处理：优先处理选区，否则处理整个输入框文本。
     * 不修改全局激活的 Prompt；成功则用返回结果替换（保留撤销快照）。
     */
    fun applyPromptToSelectionOrAll(ic: InputConnection?, promptContent: String) {
        promptApplyUseCase.apply(ic, promptOverride = promptContent)
    }

    /**
     * 显示剪贴板预览
     */
    fun showClipboardPreview(fullText: String) {
        val displaySnippet = clipboardTextPreview(fullText)
        val preview = ClipboardPreview(fullText, displaySnippet, ClipboardPreviewType.TEXT, null)
        sessionContext = sessionContext.copy(clipboardPreview = preview)
        uiListener?.onShowClipboardPreview(preview)
    }

    /**
     * 显示文件类型的剪贴板预览（仅展示文件名和格式）。
     */
    fun showClipboardFilePreview(entry: com.brycewg.asrkb.clipboard.ClipboardHistoryStore.Entry) {
        val label = entry.getDisplayLabel()
        val preview = ClipboardPreview(
            fullText = label,
            displaySnippet = label,
            type = ClipboardPreviewType.FILE,
            fileEntryId = entry.id
        )
        sessionContext = sessionContext.copy(clipboardPreview = preview)
        uiListener?.onShowClipboardPreview(preview)
    }

    /**
     * 若存在已保存的剪贴板预览，则重新显示（用于被临时提示覆盖后恢复）。
     */
    fun reShowClipboardPreviewIfAny() {
        val preview = sessionContext.clipboardPreview ?: return
        uiListener?.onShowClipboardPreview(preview)
    }

    /**
     * 处理剪贴板预览点击（粘贴）
     */
    fun handleClipboardPreviewClick(ic: InputConnection?) {
        val preview = sessionContext.clipboardPreview ?: return
        // 仅文本类型预览支持点击粘贴
        if (preview.type != ClipboardPreviewType.TEXT) return
        if (ic == null) return
        val text = preview.fullText
        if (!text.isNullOrEmpty()) {
            inputHelper.finishComposingText(ic)
            saveUndoSnapshot(ic)
            inputHelper.commitText(ic, text)
        }
        hideClipboardPreview()
    }

    /**
     * 隐藏剪贴板预览
     */
    fun hideClipboardPreview() {
        sessionContext = sessionContext.copy(clipboardPreview = null)
        uiListener?.onHideClipboardPreview()
    }

    /**
     * 恢复中间结果为 composing（键盘重新显示时）
     */
    fun restorePartialAsComposing(ic: InputConnection?) {
        if (ic == null) return
        val state = currentState
        if (state is KeyboardState.Listening) {
            val partial = state.partialText
            if (!partial.isNullOrEmpty()) {
                try {
                    DebugLogManager.log(
                        "ime",
                        "restore_partial",
                        mapOf(
                            "seq" to opSeq,
                            "fp" to StreamingPreviewDiag.fingerprint(partial)
                        )
                    )
                } catch (_: Throwable) { }
                inputHelper.setStreamingPreview(ic, partial)
            }
        }
    }

    // ========== AsrSessionManager.Listener 实现 ==========

    override fun onAsrFinal(text: String, currentState: KeyboardState) {
        val hopStartedAt = SystemClock.elapsedRealtime()
        scope.launch {
            AsrCallLatencyProbe.log(
                "t_final_hop",
                mapOf("hop_ms" to (SystemClock.elapsedRealtime() - hopStartedAt).coerceAtLeast(0L))
            )
            // 若强制停止，忽略迟到的 onFinal
            if (dropPendingFinal) {
                dropPendingFinal = false
                return@launch
            }
            // 捕获当前操作序列，用于在提交前判定是否已被新的操作序列取消
            val seq = opSeq
            // 若已启动新一轮录音（当前仍为 Listening 且引擎在运行），忽略旧会话迟到的 onFinal
            val stateNow = this@KeyboardActionHandler.currentState
            if (asrManager.isRunning() && stateNow is KeyboardState.Listening) return@launch
            when (currentState) {
                is KeyboardState.AiEditListening -> {
                    aiEditUseCase.handleFinal(text, currentState, seq)
                }
                is KeyboardState.Listening -> {
                    handleNormalDictationFinal(text, currentState, seq)
                }
                is KeyboardState.Processing, is KeyboardState.Idle -> {
                    // 允许在 Idle/Processing 状态接收最终结果（例如提前切回 Idle 的路径）
                    val synthetic = KeyboardState.Listening()
                    handleNormalDictationFinal(text, synthetic, seq)
                }
                else -> {
                    // 兜底按普通听写处理
                    val synthetic = KeyboardState.Listening()
                    handleNormalDictationFinal(text, synthetic, seq)
                }
            }
        }
    }

    override fun onAsrPartial(text: String) {
        scope.launch {
            when (val state = currentState) {
                is KeyboardState.Listening -> {
                    // 更新中间结果
                    val newState = state.copy(partialText = text)
                    transitionToState(newState)
                }
                is KeyboardState.AiEditListening -> {
                    // 更新 AI 编辑指令
                    val newState = state.copy(instruction = text)
                    transitionToState(newState)
                }
                else -> {
                    Log.w(TAG, "onAsrPartial: unexpected state $currentState")
                }
            }
        }
    }

    override fun onAsrError(message: String) {
        scope.launch {
            // 若在新的录音会话中（仍处于 Listening），将上一会话的错误视为迟到并忽略，避免覆盖 UI 状态
            val stateNow = this@KeyboardActionHandler.currentState
            if (asrManager.isRunning() && stateNow is KeyboardState.Listening) {
                Log.w(TAG, "onAsrError ignored: new session is running; message=$message")
                return@launch
            }
            // 先切换到 Idle，再显示错误，避免被 Idle 文案覆盖
            transitionToIdle(keepMessage = true)
            uiListener?.onStatusMessage(message)
            uiListener?.onVibrate()
            try {
                if (retryUseCase.shouldOfferRetry(message)) {
                    uiListener?.onShowRetryChip(context.getString(R.string.btn_retry))
                } else {
                    uiListener?.onHideRetryChip()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to evaluate/show retry chip", t)
            }
        }
    }

    override fun onAsrStopped() {
        scope.launch {
            // 若仍在长按且为非点按模式，并且上一轮录音时长极短，则判定为系统提前中断，自动重启一次录音
            // 这样用户的“持续按住说话”不会因为系统打断而直接被判定为取消
            // 仅用于早停判定：读取但不清空，避免影响后续统计与历史写入
            val earlyMs = try {
                asrManager.peekLastAudioMsForStats()
            } catch (t: Throwable) {
                0L
            }
            if (!prefs.micTapToggleEnabled && micHoldActive && earlyMs in 1..250) {
                if (micHoldRestartCount < 1) {
                    micHoldRestartCount += 1
                    try {
                        DebugLogManager.log(
                            "ime",
                            "auto_restart_after_early_stop",
                            mapOf(
                                "audioMs" to earlyMs,
                                "count" to micHoldRestartCount,
                                "opSeq" to opSeq
                            )
                        )
                    } catch (
                        _: Throwable
                    ) { }
                    startNormalListening()
                    return@launch
                } else {
                    try {
                        DebugLogManager.log(
                            "ime",
                            "auto_restart_skip",
                            mapOf(
                                "audioMs" to earlyMs,
                                "count" to micHoldRestartCount,
                                "opSeq" to opSeq
                            )
                        )
                    } catch (
                        _: Throwable
                    ) { }
                }
            }
            // 若强制停止，忽略迟到的 onStopped
            if (dropPendingFinal) return@launch
            // 若此时已经开始了新的录音（引擎运行中），则将本次 onStopped 视为上一会话的迟到事件并忽略。
            if (asrManager.isRunning()) {
                try {
                    asrManager.popLastAudioMsForStats()
                } catch (_: Throwable) { }
                try {
                    DebugLogManager.log(
                        "ime",
                        "asr_stopped_ignored",
                        mapOf(
                            "reason" to "new_session_running",
                            "opSeq" to opSeq
                        )
                    )
                } catch (_: Throwable) { }
                return@launch
            }
            // 误触极短录音：直接取消，避免进入“识别中…”阻塞后续长按
            val audioMs = earlyMs
            // 若前面 earlyMs==0（例如未知或异常），再尝试一次以兼容既有逻辑
            val audioMsVal = if (audioMs !=
                0L
            ) {
                audioMs
            } else {
                try {
                    asrManager.peekLastAudioMsForStats()
                } catch (t: Throwable) {
                    Log.w(TAG, "popLastAudioMsForStats failed", t)
                    0L
                }
            }
            if (audioMsVal in 1..250) {
                // 将后续迟到回调丢弃并归位
                markDropPendingRecognition()
                transitionToIdle()
                uiListener?.onStatusMessage(context.getString(R.string.status_cancelled))
                try {
                    DebugLogManager.log(
                        "ime",
                        "asr_stopped",
                        mapOf(
                            "audioMs" to audioMsVal,
                            "action" to "cancel_short",
                            "opSeq" to opSeq
                        )
                    )
                } catch (_: Throwable) { }
                return@launch
            }
            // 正常流程：进入 Processing，等待最终结果或兜底
            transitionToState(KeyboardState.Processing)
            scheduleProcessingTimeout(audioMsVal)
            uiListener?.onStatusMessage(context.getString(R.string.status_recognizing))
            try {
                DebugLogManager.log(
                    "ime",
                    "asr_stopped",
                    mapOf(
                        "audioMs" to audioMs,
                        "action" to "enter_processing",
                        "opSeq" to opSeq
                    )
                )
            } catch (_: Throwable) { }
        }
    }

    override fun onLocalModelLoadStart() {
        // 记录开始时间，用于计算加载耗时
        try {
            modelLoadStartUptimeMs = android.os.SystemClock.uptimeMillis()
        } catch (
            _: Throwable
        ) {
            modelLoadStartUptimeMs =
                0L
        }
        val resId = if (currentState is KeyboardState.Listening ||
            currentState is KeyboardState.AiEditListening
        ) {
            R.string.sv_loading_model_while_listening
        } else {
            R.string.sv_loading_model
        }
        uiListener?.onStatusMessage(context.getString(resId))
    }

    override fun onLocalModelLoadDone() {
        val dt = try {
            val now = android.os.SystemClock.uptimeMillis()
            if (modelLoadStartUptimeMs > 0L &&
                now >= modelLoadStartUptimeMs
            ) {
                now - modelLoadStartUptimeMs
            } else {
                -1L
            }
        } catch (_: Throwable) {
            -1L
        }
        if (dt > 0) {
            uiListener?.onStatusMessage(context.getString(R.string.sv_model_ready_with_ms, dt))
        } else {
            uiListener?.onStatusMessage(context.getString(R.string.sv_model_ready))
        }
    }

    override fun onBackupAsrLoading(backupVendor: AsrVendor) {
        uiListener?.onStatusMessage(context.getString(R.string.status_backup_asr_loading))
    }

    override fun onBackupAsrRecognizing(backupVendor: AsrVendor) {
        uiListener?.onStatusMessage(context.getString(R.string.status_backup_asr_recognizing))
    }

    override fun onAmplitude(amplitude: Float) {
        uiListener?.onAmplitude(amplitude)
    }

    // ========== 私有方法：状态转换 ==========

    private fun transitionToState(newState: KeyboardState) {
        // 不在进入 Processing 时主动 finishComposing，保留预览供最终结果做差量合并
        val prev = currentState
        currentState = newState
        try {
            DebugLogManager.log(
                category = "ime",
                event = "state_transition",
                data = mapOf(
                    "from" to prev.diagnosticName,
                    "to" to newState.diagnosticName,
                    "opSeq" to opSeq
                )
            )
        } catch (_: Throwable) { }
        // 仅在携带文本上下文的状态下同步到 AsrSessionManager，
        // 避免切到 Processing 后丢失 partialText 影响最终合并
        when (newState) {
            is KeyboardState.Listening,
            is KeyboardState.AiEditListening -> asrManager.setCurrentState(newState)
            else -> { /* keep previous contextual state in AsrSessionManager */ }
        }
        if (newState !is KeyboardState.Idle) {
            try {
                uiListener?.onHideRetryChip()
            } catch (_: Throwable) {}
            try {
                uiListener?.onHidePostprocessUndo()
            } catch (_: Throwable) {}
        }
        uiListener?.onStateChanged(newState)
    }

    private fun markDropPendingRecognition() {
        dropPendingFinal = true
        val failStage = when (currentState) {
            is KeyboardState.Listening -> AsrHistoryStore.AsrHistoryFailStage.RECORDING
            else -> AsrHistoryStore.AsrHistoryFailStage.RECOGNITION
        }
        asrManager.abandonPendingRecognition(
            status = AsrHistoryStore.AsrHistoryStatus.CANCELLED,
            failStage = failStage,
            failReasonCode = AsrFailReasonCodes.USER_CANCEL
        )
    }

    private fun transitionToIdle(keepMessage: Boolean = false) {
        // 新的显式归位：递增操作序列，取消在途处理
        opSeq++
        try {
            DebugLogManager.log("ime", "opseq_inc", mapOf("at" to "to_idle", "opSeq" to opSeq))
        } catch (
            _: Throwable
        ) { }
        processingTimeoutController.cancel()
        autoEnterOnce = false
        isAutoStartedRecording = false // 清除自动启动标志
        transitionToState(KeyboardState.Idle)
        if (!keepMessage) {
            uiListener?.onStatusMessage(context.getString(R.string.status_idle))
        }
    }

    private fun startNormalListening() {
        // 开启新一轮录音：递增操作序列，取消在途处理
        opSeq++
        inputHelper.diagSession = opSeq
        try {
            DebugLogManager.log(
                "ime",
                "opseq_inc",
                mapOf(
                    "at" to "start_listening",
                    "opSeq" to opSeq
                )
            )
        } catch (_: Throwable) { }
        processingTimeoutController.cancel()
        dropPendingFinal = false
        autoEnterOnce = false
        try {
            uiListener?.onHideRetryChip()
        } catch (_: Throwable) {}
        val state = KeyboardState.Listening()
        transitionToState(state)
        asrManager.startRecording(state)
        uiListener?.onStatusMessage(context.getString(R.string.status_listening))
    }

    // 本地模型加载耗时统计
    private var modelLoadStartUptimeMs: Long = 0L

    /**
     * 处理“重试”点击：隐藏芯片，进入 Processing，并触发重试。
     */
    fun handleRetryClick() {
        retryUseCase.onRetryClick()
    }

    // ========== 私有方法：处理最终识别结果 ==========

    private suspend fun handleNormalDictationFinal(
        text: String,
        state: KeyboardState.Listening,
        seq: Long
    ) {
        val ic = getCurrentInputConnection() ?: return
        dictationUseCase.handleFinal(ic, text, state, seq)
    }

    private fun transitionToIdleWithTiming(showBackupUsedHint: Boolean = false) {
        val ms = asrManager.getLastDisplayedRecognitionDuration()
        if (ms != null) {
            // 立刻切到 Idle，确保此时再次点按可直接开始录音，同时取消任何兜底定时器，避免后续误判为“取消”
            transitionToIdle(keepMessage = true)
            if (showBackupUsedHint) {
                uiListener?.onStatusMessage(context.getString(R.string.status_backup_asr_used))
                scope.launch {
                    delay(700)
                    if (currentState !is KeyboardState.Listening) {
                        uiListener?.onStatusMessage(
                            context.getString(R.string.status_last_request_ms, ms)
                        )
                    }
                    delay(800)
                    if (currentState !is KeyboardState.Listening) {
                        uiListener?.onStatusMessage(context.getString(R.string.status_idle))
                    }
                }
            } else {
                // 切换到 Idle 后再设置耗时文案，避免被 UI 的 Idle 文案覆盖
                uiListener?.onStatusMessage(context.getString(R.string.status_last_request_ms, ms))
                scope.launch {
                    delay(1500)
                    if (currentState !is KeyboardState.Listening) {
                        uiListener?.onStatusMessage(context.getString(R.string.status_idle))
                    }
                }
            }
        } else {
            if (showBackupUsedHint) {
                transitionToIdle(keepMessage = true)
                uiListener?.onStatusMessage(context.getString(R.string.status_backup_asr_used))
                scope.launch {
                    delay(1500)
                    if (currentState !is KeyboardState.Listening) {
                        uiListener?.onStatusMessage(context.getString(R.string.status_idle))
                    }
                }
            } else {
                transitionToIdle()
            }
        }
    }

    /**
     * 获取当前输入连接（需要从外部注入）
     * 这是一个临时方案，实际应该通过参数传递
     */
    private var currentInputConnectionProvider: (() -> InputConnection?)? = null

    fun setInputConnectionProvider(provider: () -> InputConnection?) {
        currentInputConnectionProvider = provider
    }

    private fun getCurrentInputConnection(): InputConnection? = currentInputConnectionProvider?.invoke()

    /**
     * 获取当前编辑器信息（需要从外部注入）
     */
    private var currentEditorInfoProvider: (() -> EditorInfo?)? = null

    fun setEditorInfoProvider(provider: () -> EditorInfo?) {
        currentEditorInfoProvider = provider
    }

    private fun getCurrentEditorInfo(): EditorInfo? = currentEditorInfoProvider?.invoke()

    /**
     * 是否在本次最终结果提交后自动回车。
     * 优先消费手势「右滑发送」等会话一次性标记；否则遵循全局设置 [Prefs.autoEnterAfterAsrEnabled]。
     */
    private fun consumeAutoEnterOnce(): Boolean {
        if (autoEnterOnce) {
            autoEnterOnce = false
            return true
        }
        return prefs.autoEnterAfterAsrEnabled
    }

    // 会话一次性标记：最终结果提交后是否自动发送回车（如右滑发送手势）
    private var autoEnterOnce: Boolean = false
}
