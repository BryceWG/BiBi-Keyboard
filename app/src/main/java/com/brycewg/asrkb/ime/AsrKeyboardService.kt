/**
 * 输入法主服务与键盘面板装配入口。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrLocalModelCatalog
import com.brycewg.asrkb.asr.AsrLocalVendorLifecycles
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.BluetoothRouteManager
import com.brycewg.asrkb.asr.ContinuousCaptureCoordinator
import com.brycewg.asrkb.asr.ContinuousCaptureOwner
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.asr.LocalModelCheck
import com.brycewg.asrkb.asr.isLocalAsrPrepared
import com.brycewg.asrkb.asr.localModelErrorMessage
import com.brycewg.asrkb.asr.partitionAsrVendorsByConfigured
import com.brycewg.asrkb.asr.preloadLocalAsrIfConfigured
import com.brycewg.asrkb.clipboard.ClipboardSyncRuntimeService
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.AsrVendorUi
import com.brycewg.asrkb.ui.SettingsActivity
import com.brycewg.asrkb.util.HapticFeedbackHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal data class LocalAsrPreloadFlags(
    val senseVoice: Boolean,
    val funAsrNano: Boolean,
    val qwen3Asr: Boolean,
    val parakeet: Boolean,
    val fireRedAsr: Boolean,
    val xAsr: Boolean
)

internal fun isLocalAsrPreloadEnabled(
    vendor: AsrVendor,
    flags: LocalAsrPreloadFlags
): Boolean = when (vendor) {
    AsrVendor.SenseVoice -> flags.senseVoice
    AsrVendor.FunAsrNano -> flags.funAsrNano
    AsrVendor.Qwen3Asr -> flags.qwen3Asr
    AsrVendor.Parakeet -> flags.parakeet
    AsrVendor.FireRedAsr -> flags.fireRedAsr
    AsrVendor.XAsr -> flags.xAsr
    else -> false
}

internal fun localAsrMissingModelErrorRes(vendor: AsrVendor): Int? = AsrLocalModelCatalog.missingModelErrorRes(vendor)

internal fun stopRecordingIfActive(isRunning: Boolean, stopRecording: () -> Unit) {
    if (isRunning) stopRecording()
}

/**
 * ASR 键盘服务
 *
 * 职责：
 * - 管理键盘视图的生命周期
 * - 绑定视图事件到 KeyboardActionHandler
 * - 响应 UI 更新通知
 * - 管理系统回调（onStartInputView, onFinishInputView 等）
 * - 协调剪贴板同步等辅助功能
 *
 * 复杂的业务逻辑已拆分到：
 * - KeyboardActionHandler: 键盘动作处理和状态管理
 * - AsrSessionManager: ASR 引擎生命周期管理
 * - InputConnectionHelper: 输入连接操作封装
 * - BackspaceGestureHandler: 退格手势处理
 */
class AsrKeyboardService :
    InputMethodService(),
    KeyboardActionHandler.UiListener {

    companion object {
        const val ACTION_REFRESH_IME_UI = "com.brycewg.asrkb.action.REFRESH_IME_UI"
        private val INSETS_WARMUP_DELAYS_MS = longArrayOf(32L, 96L, 220L)

        /** 收起后切换输入法时，异步重试拉起软键盘的延迟序列 */
        private val RESHOW_SOFT_INPUT_DELAYS_MS = longArrayOf(32L, 120L, 280L)
    }

    override fun attachBaseContext(newBase: android.content.Context?) {
        val wrapped = newBase?.let { LocaleHelper.wrap(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    // ========== 组件实例 ==========
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: Prefs
    private lateinit var inputHelper: InputConnectionHelper
    private lateinit var asrManager: AsrSessionManager
    private lateinit var actionHandler: KeyboardActionHandler
    private lateinit var backspaceGestureHandler: BackspaceGestureHandler

    // ========== 视图与控制器 ==========
    private var rootView: View? = null
    private var viewRefs: ImeViewRefs? = null
    private lateinit var themeStyler: ImeThemeStyler
    private var layoutController: ImeLayoutController? = null
    private var uiRenderer: ImeUiRenderer? = null
    private var mainKeyboardBinder: ImeMainKeyboardBinder? = null
    private var extensionButtonsController: ImeExtensionButtonsController? = null
    private var clipboardCoordinator: ImeClipboardCoordinator? = null

    private var aiEditPanelController: AiEditPanelController? = null
    private var numpadPanelController: NumpadPanelController? = null
    private var clipboardPanelController: ClipboardPanelController? = null
    private var asrHistoryPanelController: AsrHistoryPanelController? = null
    private var micGestureController: MicGestureController? = null
    private var imeViewVisible: Boolean = false

    private val isAiEditPanelVisible: Boolean
        get() = aiEditPanelController?.isVisible == true
    private val isNumpadPanelVisible: Boolean
        get() = numpadPanelController?.isVisible == true
    private val isClipboardPanelVisible: Boolean
        get() = clipboardPanelController?.isVisible == true
    private val isAsrHistoryPanelVisible: Boolean
        get() = asrHistoryPanelController?.isVisible == true

    // ========== 剪贴板和其他辅助功能 ==========
    private var prefsReceiver: BroadcastReceiver? = null

    // 本地模型首次出现预热仅触发一次
    private var localPreloadTriggered: Boolean = false
    private var suppressReturnPrevImeOnHideOnce: Boolean = false

    // 记录最近一次在 IME 内弹出菜单的时间，用于限制“防误收起”逻辑的作用窗口
    private var lastPopupMenuShownAt: Long = 0L

    // ========== 生命周期 ==========

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        themeStyler = ImeThemeStyler(prefs)
        layoutController = ImeLayoutController(
            prefs = prefs,
            themeStyler = themeStyler,
            windowProvider = { window?.window },
            viewRefsProvider = { viewRefs }
        )

        // 初始化组件
        inputHelper = InputConnectionHelper("AsrKeyboardService")
        asrManager = AsrSessionManager(this, serviceScope, prefs)
        actionHandler = KeyboardActionHandler(
            this,
            serviceScope,
            prefs,
            asrManager,
            inputHelper,
            LlmPostProcessor()
        )
        backspaceGestureHandler = BackspaceGestureHandler(inputHelper)

        // 设置监听器
        asrManager.setListener(actionHandler)
        actionHandler.setUiListener(this)
        actionHandler.setInputConnectionProvider { currentInputConnection }
        actionHandler.setEditorInfoProvider { currentInputEditorInfo }

        // 构建初始 ASR 引擎
        asrManager.rebuildEngine()

        // 监听设置变化以即时刷新键盘 UI
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_REFRESH_IME_UI -> {
                        refreshCurrentInputViewTheme(recreateVisibleInputView = true)
                        clipboardCoordinator?.notifyClipboardSyncConfigChanged()
                    }
                }
            }
        }
        prefsReceiver = r
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                /* context = */
                this,
                /* receiver = */
                r,
                /* filter = */
                IntentFilter().apply {
                    addAction(ACTION_REFRESH_IME_UI)
                },
                /* flags = */
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Throwable) {
            android.util.Log.e("AsrKeyboardService", "Failed to register prefsReceiver", e)
        }
    }

    override fun onDestroy() {
        ClipboardSyncRuntimeService.setUiListener(null)
        super.onDestroy()
        ContinuousCaptureCoordinator.release(ContinuousCaptureOwner.Ime)
        asrManager.cleanup()
        serviceScope.cancel()
        clipboardCoordinator?.stopClipboardPreviewListener()
        clipboardCoordinator?.deactivateClipboardSyncRuntime()
        try {
            prefsReceiver?.let { unregisterReceiver(it) }
        } catch (e: Throwable) {
            android.util.Log.e("AsrKeyboardService", "Failed to unregister prefsReceiver", e)
        }
        prefsReceiver = null
    }

    override fun onCreateInputView(): View = createKeyboardView()

    private fun createKeyboardView(): View {
        val themedContext = ContextThemeWrapper(this, R.style.Theme_ASRKeyboard_Ime)
        val dynamicContext = com.google.android.material.color.DynamicColors.wrapContextIfAvailable(
            themedContext
        )
        val view = ImeKeyboardViewFactory.create(dynamicContext, prefs)
        return setupKeyboardView(view)
    }

    private fun setupKeyboardView(view: View): View {
        rootView = view

        // 根据主题动态调整键盘背景色，使其略浅于当前容器色但仍明显深于普通按键与麦克风按钮
        themeStyler.applyKeyboardBackgroundColor(view)

        // 应用 Window Insets 以适配 Android 15 边缘到边缘显示
        layoutController?.installKeyboardInsetsListener(view)

        // 查找所有视图
        bindViews(view)

        // 设置监听器
        setupListeners()

        // 应用偏好设置
        layoutController?.applyKeyboardHeightScale()
        layoutController?.scheduleKeyboardLayoutApply()
        mainKeyboardBinder?.applyPunctuationLabels()
        extensionButtonsController?.applyConfig()

        // 更新初始 UI 状态
        refreshPermissionUi()
        uiRenderer?.forceStructuralRenderOnNextFrame()
        onStateChanged(actionHandler.getCurrentState())

        // 同步系统导航栏颜色
        view.post { syncSystemBarsToKeyboardBackground(view) }

        return view
    }

    private fun refreshCurrentInputViewTheme(recreateVisibleInputView: Boolean) {
        if (recreateVisibleInputView && imeViewVisible && rootView != null) {
            clipboardCoordinator?.stopClipboardPreviewListener()
            val newView = createKeyboardView()
            setInputView(newView)
            clipboardCoordinator?.activateClipboardSyncRuntime()
            clipboardCoordinator?.startClipboardPreviewListener()
            androidx.core.view.ViewCompat.requestApplyInsets(newView)
            scheduleInsetsWarmup(newView)
            if (asrManager.isRunning()) {
                uiRenderer?.forceStructuralRenderOnNextFrame()
            }
            onStateChanged(actionHandler.getCurrentState())
            newView.post { syncSystemBarsToKeyboardBackground(newView) }
            return
        }

        val v = rootView ?: return
        ImeKeyboardViewFactory.applyTheme(v, prefs)
        val layoutChanged = layoutController?.applyKeyboardHeightScale() == true
        extensionButtonsController?.applyConfig()
        uiRenderer?.updateWaveformSensitivity()
        uiRenderer?.updatePostprocIcon()
        syncSystemBarsToKeyboardBackground(v)
        if (layoutChanged) {
            v.requestLayout()
        }
        layoutController?.scheduleKeyboardLayoutApply()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        imeViewVisible = true
        layoutController?.onInputViewStarted()
        if (layoutController?.applyKeyboardHeightScale() == true) {
            rootView?.requestLayout()
        }
        layoutController?.scheduleKeyboardLayoutApply()
        // 冷启动首帧偶现 system insets 迟到/不稳定：主动触发一次重新分发，降低高度异常概率
        rootView?.let {
            androidx.core.view.ViewCompat.requestApplyInsets(it)
            scheduleInsetsWarmup(it)
        }
        inputHelper.diagHostPkg = info?.packageName.orEmpty()
        DebugLogManager.log(
            category = "ime",
            event = "start_input_view",
            data = mapOf(
                "pkg" to (info?.packageName ?: ""),
                "inputType" to (info?.inputType ?: 0),
                "imeOptions" to (info?.imeOptions ?: 0),
                "icNull" to (currentInputConnection == null),
                "isMultiLine" to
                    (
                        (info?.inputType ?: 0) and android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE !=
                            0
                        ),
                "actionId" to
                    ((info?.imeOptions ?: 0) and android.view.inputmethod.EditorInfo.IME_MASK_ACTION)
            )
        )

        // 键盘面板首次出现时，按需异步预加载本地模型
        tryPreloadLocalModel()

        // 刷新 UI
        mainKeyboardBinder?.applyPunctuationLabels()
        extensionButtonsController?.applyConfig()
        refreshPermissionUi()
        resetPanelsToMainKeyboard()
        // 同步系统栏颜色
        rootView?.post { syncSystemBarsToKeyboardBackground(rootView) }

        // 启动剪贴板同步 Runtime（默认节能：仅输入视图可见期间）
        clipboardCoordinator?.activateClipboardSyncRuntime()

        // 监听系统剪贴板变更，IME 可见期间弹出预览
        clipboardCoordinator?.startClipboardPreviewListener()

        // 预热耳机路由（键盘显示）
        try {
            BluetoothRouteManager.setImeActive(this, true)
        } catch (
            t: Throwable
        ) {
            android.util.Log.w("AsrKeyboardService", "BluetoothRouteManager setImeActive(true)", t)
        }
        ContinuousCaptureCoordinator.acquire(ContinuousCaptureOwner.Ime, this)

        // 识别中收起后可能仍在 Processing/AiProcessing：按真实状态刷新，避免面板停在 Idle
        uiRenderer?.render(actionHandler.getCurrentState())

        // 自动启动录音（如果开启了设置）
        if (prefs.autoStartRecordingOnShow) {
            // 与手动开始保持一致的就绪性校验，避免在缺少 Key/模型时进入 Listening 状态
            if (!checkAsrReady()) {
                // refreshPermissionUi() 已在校验中处理，这里直接返回
            } else {
                // 延迟一小段时间再启动，确保键盘 UI 已完全显示
                rootView?.postDelayed({
                    // 再次确认仍然就绪（期间用户可能改了设置/权限）
                    if (!checkAsrReady()) return@postDelayed
                    if (asrManager.isRunning()) return@postDelayed
                    if (actionHandler.getCurrentState() !is KeyboardState.Idle) return@postDelayed
                    actionHandler.startAutoRecording()
                }, 100)
            }
        }
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )
        aiEditPanelController?.onSelectionChanged(newSelStart, newSelEnd)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        imeViewVisible = false
        layoutController?.onInputViewFinished()
        stopImeRecordingIfRunning()
        actionHandler.onInputViewHidden()
        inputHelper.resetStreamingPreviewState("finish_input_view")
        super.onFinishInputView(finishingInput)
        DebugLogManager.log("ime", "finish_input_view")
        // 停止剪贴板预览监听与默认节能下的自动同步
        clipboardCoordinator?.stopClipboardPreviewListener()
        clipboardCoordinator?.deactivateClipboardSyncRuntime()

        resetPanelsToMainKeyboard()

        // 键盘收起，解除预热（若未在录音）
        try {
            BluetoothRouteManager.setImeActive(this, false)
        } catch (
            t: Throwable
        ) {
            android.util.Log.w("AsrKeyboardService", "BluetoothRouteManager setImeActive(false)", t)
        }
        ContinuousCaptureCoordinator.release(ContinuousCaptureOwner.Ime)

        // 如开启：键盘收起后自动切回上一个输入法。
        // 注意：若已在 requestHideSelf/返回键/输入完成交接路径完成「显示中交接」，此处会被 suppress 跳过。
        // 对系统直接 hide（手势返回等）只能事后切换；输入连接仍在时再尝试拉起目标 IME 面板。
        if (suppressReturnPrevImeOnHideOnce) {
            // 无论是否开启收起后切换，都要清掉一次性抑制，避免下次交接被误跳过
            suppressReturnPrevImeOnHideOnce = false
        } else if (prefs.returnPrevImeOnHide) {
            val shouldTryReshow = !finishingInput
            val switched = switchToConfiguredImeOrPrevious()
            if (switched && shouldTryReshow) {
                scheduleReshowSoftInputAfterImeSwitch()
            }
        }
    }

    override fun requestHideSelf(flags: Int) {
        // 在仍显示时交接给目标 IME，使其接管同一输入会话并保持面板可见。
        // 若先 hide 再 switch，系统已清除 show 请求，目标 IME 只会成为当前输入法而不会自动弹出。
        if (tryHandOffToConfiguredImeWhileShown(reason = "request_hide_self")) {
            return
        }
        super.requestHideSelf(flags)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // 实体/导航返回：在仍显示时交接，避免「先收起再切换」导致目标面板不出现
        if (keyCode == KeyEvent.KEYCODE_BACK && event?.repeatCount == 0) {
            if (isInputViewShown || imeViewVisible) {
                if (tryHandOffToConfiguredImeWhileShown(reason = "key_back")) {
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        inputHelper.diagHostPkg = attribute?.packageName.orEmpty()
        val ic = currentInputConnection
        // 同一输入框被宿主重启时保留预览归属，避免按已含 composing 的光标文本重采锚点。
        if (!inputHelper.hasStreamingPreviewOwnershipFor(ic)) {
            inputHelper.resetStreamingPreviewState("start_input")
        }

        // 若正在录音，同步中间结果为 composing
        if (asrManager.isRunning()) {
            actionHandler.restorePartialAsComposing(ic)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        // 避免全屏候选，保持紧凑的麦克风键盘
        return false
    }

    override fun onComputeInsets(outInsets: InputMethodService.Insets) {
        super.onComputeInsets(outInsets)
        // 冷启动偶现：系统拿到错误的 Insets（contentTopInsets=0），导致宿主被过度 adjustResize，
        // 表现为输入框被顶到接近屏幕顶端、键盘仍在底部、两者之间出现大块空白区域（IME 背景色）。
        // 这里按实际输入视图高度/位置兜底修正，避免首次 insets 失真后卡住直到下一次收起/唤出。
        layoutController?.fixImeInsetsIfNeeded(imeViewVisible, outInsets, window?.window?.decorView)
    }

    // ========== KeyboardActionHandler.UiListener 实现 ==========

    override fun onStateChanged(state: KeyboardState) {
        uiRenderer?.render(state)
    }

    override fun onStatusMessage(message: String, reportError: Boolean) {
        uiRenderer?.showStatusMessage(message, reportError)
    }

    override fun onVibrate() {
        vibrateTick()
    }

    override fun onAmplitude(amplitude: Float) {
        viewRefs?.waveformView?.updateAmplitude(amplitude)
    }

    override fun onShowClipboardPreview(preview: ClipboardPreview) {
        uiRenderer?.showClipboardPreview(preview)
    }

    override fun onHideClipboardPreview() {
        uiRenderer?.hideClipboardPreview()
    }

    override fun onShowRetryChip(label: String) {
        uiRenderer?.showRetryChip(label)
    }

    override fun onHideRetryChip() {
        uiRenderer?.hideRetryChip()
    }

    override fun onShowPostprocessUndo(label: String) {
        uiRenderer?.showPostprocessUndo(label)
    }

    override fun onHidePostprocessUndo() {
        uiRenderer?.hidePostprocessUndo()
    }

    override fun onDictationInputSolidified() {
        tryHandOffAfterAsrInput()
    }

    // ========== 视图绑定和监听器设置 ==========

    private fun bindViews(view: View) {
        val refs = ImeViewRefs.bind(view)
        viewRefs = refs

        // 为波形视图应用随设置 UI 模式解析后的主色。
        ImeKeyboardViewFactory.applyTheme(view, prefs)
        // 应用波形灵敏度设置
        refs.waveformView?.sensitivity = prefs.waveformSensitivity
        // 修复麦克风垂直位置
        layoutController?.bindMicVerticalFix(refs)

        val coordinator = ImeClipboardCoordinator(
            context = this,
            prefs = prefs,
            serviceScope = serviceScope,
            rootViewProvider = { rootView },
            actionHandler = actionHandler,
            isClipboardPanelVisible = { clipboardPanelController?.isVisible == true },
            refreshClipboardPanelList = { clipboardPanelController?.refreshList() ?: Unit },
            clipStoreProvider = { clipboardPanelController?.store },
            showStatusMessage = { msg -> uiRenderer?.showStatusMessage(msg) ?: Unit }
        )
        clipboardCoordinator = coordinator

        aiEditPanelController = AiEditPanelController(
            context = this,
            prefs = prefs,
            views = refs,
            inputHelper = inputHelper,
            actionHandler = actionHandler,
            backspaceGestureHandler = backspaceGestureHandler,
            performKeyHaptic = ::performKeyHaptic,
            showAiEditHint = { message -> uiRenderer?.showAiEditFunctionHint(message) ?: Unit },
            showPopupMenuKeepingIme = ::showPopupMenuKeepingIme,
            inputConnectionProvider = { currentInputConnection },
            onRequestShowNumpad = { returnToAiPanel -> showNumpadPanel(returnToAiPanel) }
        )
        numpadPanelController = NumpadPanelController(
            prefs = prefs,
            views = refs,
            inputHelper = inputHelper,
            actionHandler = actionHandler,
            backspaceGestureHandler = backspaceGestureHandler,
            performKeyHaptic = ::performKeyHaptic,
            inputConnectionProvider = { currentInputConnection },
            editorInfoProvider = { currentInputEditorInfo },
            onRequestShowAiEditPanel = { showAiEditPanel() }
        )
        clipboardPanelController = ClipboardPanelController(
            context = this,
            prefs = prefs,
            serviceScope = serviceScope,
            views = refs,
            themeStyler = themeStyler,
            performKeyHaptic = ::performKeyHaptic,
            inputConnectionProvider = { currentInputConnection },
            showPopupMenuKeepingIme = ::showPopupMenuKeepingIme,
            onOpenFile = coordinator::openFile,
            onDownloadFile = coordinator::downloadClipboardFile
        )
        asrHistoryPanelController = AsrHistoryPanelController(
            context = this,
            serviceScope = serviceScope,
            views = refs,
            themeStyler = themeStyler,
            performKeyHaptic = ::performKeyHaptic,
            inputConnectionProvider = { currentInputConnection }
        )
        micGestureController = MicGestureController(
            prefs = prefs,
            views = refs,
            actionHandler = actionHandler,
            performKeyHaptic = ::performKeyHaptic,
            checkAsrReady = ::checkAsrReady,
            inputConnectionProvider = { currentInputConnection },
            isAiEditPanelVisible = { isAiEditPanelVisible },
            onLockedBySwipeChanged = { onStateChanged(actionHandler.getCurrentState()) }
        )

        uiRenderer = ImeUiRenderer(
            context = this,
            prefs = prefs,
            views = refs,
            inputHelper = inputHelper,
            actionHandler = actionHandler,
            inputConnectionProvider = { currentInputConnection },
            performKeyHaptic = ::performKeyHaptic,
            isAiEditPanelVisible = { isAiEditPanelVisible },
            micGestureController = { micGestureController },
            downloadClipboardFileById = coordinator::downloadClipboardFileById,
            markShownClipboardText = coordinator::markShownText,
            copyTextToSystemClipboard = coordinator::copyPlainTextToSystemClipboard
        )
        mainKeyboardBinder = ImeMainKeyboardBinder(
            context = this,
            prefs = prefs,
            views = refs,
            inputHelper = inputHelper,
            actionHandler = actionHandler,
            backspaceGestureHandler = backspaceGestureHandler,
            performKeyHaptic = ::performKeyHaptic,
            vibrateTick = ::vibrateTick,
            hasRecordAudioPermission = ::hasRecordAudioPermission,
            refreshPermissionUi = ::refreshPermissionUi,
            clearStatusTextStyle = { uiRenderer?.clearStatusTextStyle() ?: Unit },
            showStatusMessage = { message -> uiRenderer?.showStatusMessage(message) ?: Unit },
            renderCurrentState = { uiRenderer?.render(actionHandler.getCurrentState()) ?: Unit },
            showAiEditPanel = ::showAiEditPanel,
            showNumpadPanel = { returnToAiPanel -> showNumpadPanel(returnToAiPanel) },
            showClipboardPanel = ::showClipboardPanel,
            openSettings = ::openSettings,
            showPromptPicker = ::showPromptPicker,
            showVendorPicker = ::showVendorPicker,
            onImeSwitchButtonClicked = ::handleImeSwitchClick,
            inputConnectionProvider = { currentInputConnection },
            editorInfoProvider = { currentInputEditorInfo }
        )
        extensionButtonsController = ImeExtensionButtonsController(
            prefs = prefs,
            views = refs,
            inputHelper = inputHelper,
            actionHandler = actionHandler,
            inputConnectionProvider = { currentInputConnection },
            editorInfoProvider = { currentInputEditorInfo },
            performKeyHaptic = ::performKeyHaptic,
            checkAsrReady = ::checkAsrReady,
            moveCursorBy = { delta -> aiEditPanelController?.moveCursorBy(delta) },
            toggleSelectionMode = { aiEditPanelController?.toggleSelectionMode() },
            isSelectionModeEnabled = { aiEditPanelController?.isSelectionModeEnabled() == true },
            updateSelectExtButtonsUi = { aiEditPanelController?.applySelectExtButtonsUi() },
            showAiEditPanel = { showAiEditPanel() },
            hideAiEditPanel = { aiEditPanelController?.hide() },
            showNumpadPanel = { showNumpadPanel(returnToAiPanel = false) },
            showNumpadPanelFromAi = { showNumpadPanel(returnToAiPanel = true) },
            showClipboardPanel = { showClipboardPanel() },
            showAsrHistoryPanel = { showAsrHistoryPanel() },
            hideKeyboardPanel = { hideKeyboardPanel() },
            openSettings = { openSettings() },
            showPromptPicker = { anchor -> showPromptPicker(anchor) },
            showPromptPickerForApply = { anchor -> aiEditPanelController?.showPromptPickerForApply(anchor) },
            showVendorPicker = { anchor -> showVendorPicker(anchor) },
            onImeSwitchButtonClicked = { handleImeSwitchClick() }
        )
    }

    private fun setupListeners() {
        aiEditPanelController?.bindListeners()
        numpadPanelController?.bindListeners()
        clipboardPanelController?.bindListeners()
        asrHistoryPanelController?.bindListeners()
        micGestureController?.bindMicButton()
        micGestureController?.bindOverlayButtons()
        mainKeyboardBinder?.bind()
        extensionButtonsController?.bindListeners()
    }

    private fun scheduleInsetsWarmup(view: View) {
        INSETS_WARMUP_DELAYS_MS.forEach { delayMs ->
            view.postDelayed({
                if (!imeViewVisible) return@postDelayed
                if (rootView !== view) return@postDelayed
                if (layoutController?.hasResolvedBottomInset() == true) return@postDelayed
                androidx.core.view.ViewCompat.requestApplyInsets(view)
            }, delayMs)
        }
    }

    private fun showAiEditPanel() {
        if (isAiEditPanelVisible) return
        hideClipboardPanel()
        hideAsrHistoryPanel()
        hideNumpadPanel()
        aiEditPanelController?.show()
        if (layoutController?.applyKeyboardHeightScale() == true) {
            rootView?.requestLayout()
        }
        rootView?.post {
            if (isAiEditPanelVisible && layoutController?.applyKeyboardHeightScale() == true) {
                rootView?.requestLayout()
            }
        }
        uiRenderer?.render(actionHandler.getCurrentState())
    }

    private fun showNumpadPanel(returnToAiPanel: Boolean = false) {
        if (isNumpadPanelVisible) return
        hideClipboardPanel()
        hideAsrHistoryPanel()
        aiEditPanelController?.hide()
        numpadPanelController?.show(returnToAiPanel)
        // 面板高度与按键行高依赖主键盘的测量高度，展示时必须重新对齐一次，
        // 否则首帧或从其他输入法切回后会沿用回退尺寸。
        if (layoutController?.applyKeyboardHeightScale() == true) {
            rootView?.requestLayout()
        }
        rootView?.post {
            if (isNumpadPanelVisible && layoutController?.applyKeyboardHeightScale() == true) {
                rootView?.requestLayout()
            }
        }
    }

    private fun hideNumpadPanel() {
        numpadPanelController?.hide()
    }

    private fun showClipboardPanel() {
        if (isClipboardPanelVisible) return
        hideNumpadPanel()
        hideAsrHistoryPanel()
        aiEditPanelController?.hide()
        clipboardPanelController?.show()
    }

    private fun hideClipboardPanel() {
        clipboardPanelController?.hide()
    }

    private fun showAsrHistoryPanel() {
        if (isAsrHistoryPanelVisible) return
        hideNumpadPanel()
        hideClipboardPanel()
        aiEditPanelController?.hide()
        asrHistoryPanelController?.show()
    }

    private fun hideAsrHistoryPanel() {
        asrHistoryPanelController?.hide()
    }

    private fun resetPanelsToMainKeyboard() {
        clipboardPanelController?.hide()
        asrHistoryPanelController?.hide()
        numpadPanelController?.hide()
        aiEditPanelController?.hide()
        aiEditPanelController?.resetSelectionState()

        val refs = viewRefs
        refs?.layoutClipboardPanel?.visibility = View.GONE
        refs?.layoutAsrHistoryPanel?.visibility = View.GONE
        refs?.layoutNumpadPanel?.visibility = View.GONE
        refs?.layoutAiEditPanel?.visibility = View.GONE
        refs?.layoutMainKeyboard?.visibility = View.VISIBLE
        refs?.groupMicStatus?.visibility = View.VISIBLE
    }

    /**
     * 在 IME 窗口内展示 PopupMenu，并在异常情况下尝试保持键盘不被收起。
     *
     * 部分机型上，在输入法窗口里弹出菜单偶现触发系统收起软键盘；
     * 这里在菜单消失时检测输入视图是否已被隐藏，如已隐藏则请求重新显示。
     */
    private fun showPopupMenuKeepingIme(popup: PopupMenu) {
        popup.setOnDismissListener {
            // 仅在弹出后短时间内发生收起时尝试恢复，避免干扰用户主动收起键盘
            val now = System.currentTimeMillis()
            if (now - lastPopupMenuShownAt > 2000L) return@setOnDismissListener
            if (!isInputViewShown && currentInputEditorInfo != null) {
                try {
                    requestShowSelf(0)
                } catch (t: Throwable) {
                    android.util.Log.w(
                        "AsrKeyboardService",
                        "Failed to re-show IME after popup dismiss",
                        t
                    )
                }
            }
        }
        lastPopupMenuShownAt = System.currentTimeMillis()
        popup.show()
    }

    internal fun checkAsrReady(): Boolean {
        if (!hasRecordAudioPermission()) {
            refreshPermissionUi()
            DebugLogManager.log("ime", "asr_not_ready", mapOf("reason" to "perm"))
            return false
        }
        if (!prefs.hasAsrKeys()) {
            refreshPermissionUi()
            DebugLogManager.log("ime", "asr_not_ready", mapOf("reason" to "keys"))
            return false
        }
        val localEntry = AsrLocalModelCatalog.entryFor(prefs.asrVendor)
        if (localEntry != null && !localEntry.lifecycle.isPrepared()) {
            val check = AsrLocalModelCatalog.modelStatus(this, prefs, localEntry.vendor)
                ?: return false
            if (check !is LocalModelCheck.Ready) {
                uiRenderer?.clearStatusTextStyle()
                viewRefs?.txtStatusText?.text = localModelErrorMessage(
                    this,
                    check,
                    localEntry.missingModelErrorRes
                )
                return false
            }
        }
        // 确保引擎匹配当前模式
        asrManager.ensureEngineMatchesMode()
        return true
    }

    private fun refreshPermissionUi() {
        uiRenderer?.clearStatusTextStyle()
        val granted = hasRecordAudioPermission()
        val hasKeys = prefs.hasAsrKeys()
        if (!granted) {
            viewRefs?.btnMic?.isEnabled = false
            viewRefs?.txtStatusText?.text = getString(R.string.hint_need_permission)
        } else if (!hasKeys) {
            viewRefs?.btnMic?.isEnabled = false
            viewRefs?.txtStatusText?.text = getString(R.string.hint_need_keys)
        } else {
            viewRefs?.btnMic?.isEnabled = true
            viewRefs?.txtStatusText?.text = getString(R.string.status_idle)
        }
    }

    private fun hasRecordAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun vibrateTick() {
        HapticFeedbackHelper.performTap(this, prefs, rootView)
    }

    private fun performKeyHaptic(view: View?) {
        HapticFeedbackHelper.performTap(this, prefs, view)
    }

    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun hideKeyboardPanel() {
        stopImeRecordingIfRunning()
        actionHandler.onInputViewHidden()
        uiRenderer?.render(actionHandler.getCurrentState())
        // 开启「收起后切换」时，由 requestHideSelf 在显示中交接目标 IME（并拉起其面板）
        try {
            requestHideSelf(0)
        } catch (e: Exception) {
            android.util.Log.w("AsrKeyboardService", "requestHideSelf failed", e)
        }
    }

    private fun showImePicker() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.showInputMethodPicker()
    }

    private fun handleImeSwitchClick() {
        if (prefs.fcitx5ReturnOnImeSwitch) {
            stopImeRecordingIfRunning()
            // 显示中主动切换：目标 IME 会接管面板，无需事后 re-show
            suppressReturnPrevImeOnHideOnce = true
            val switched = switchToConfiguredImeOrPrevious()
            if (!switched) {
                suppressReturnPrevImeOnHideOnce = false
                showImePicker()
            }
        } else {
            showImePicker()
        }
    }

    /**
     * 在输入视图仍可见时切换到配置的目标/上一输入法，让对方接管当前输入会话并保持软键盘显示。
     *
     * @return true 表示已发起交接（调用方应中止本 IME 的 hide 流程）
     */
    private fun tryHandOffToConfiguredImeWhileShown(reason: String): Boolean {
        if (!prefs.returnPrevImeOnHide) return false
        val switched = configuredImeHandOffFailureReason() == null
        if (switched) {
            DebugLogManager.log(
                category = "ime",
                event = "ime_handoff_while_shown",
                data = mapOf("reason" to reason)
            )
        }
        return switched
    }

    private fun tryHandOffAfterAsrInput() {
        if (!prefs.autoSwitchImeAfterAsrEnabled) return
        val failReason = configuredImeHandOffFailureReason()
        if (failReason == null) {
            DebugLogManager.logBase(
                this,
                "ime",
                "ime_handoff_after_asr",
                mapOf("ok" to true)
            )
        } else {
            DebugLogManager.logBase(
                this,
                "ime",
                "ime_handoff_after_asr",
                mapOf("ok" to false, "reason" to failReason)
            )
        }
    }

    /**
     * @return `null` 表示已发起交接；否则为失败原因码。
     */
    private fun configuredImeHandOffFailureReason(): String? {
        if (suppressReturnPrevImeOnHideOnce) return "suppressed"
        if (!isInputViewShown && !imeViewVisible) return "ime_not_shown"

        stopImeRecordingIfRunning()
        suppressReturnPrevImeOnHideOnce = true
        val switched = switchToConfiguredImeOrPrevious()
        if (!switched) {
            suppressReturnPrevImeOnHideOnce = false
            return "switch_failed"
        }
        return null
    }

    private fun stopImeRecordingIfRunning() {
        stopRecordingIfActive(asrManager.isRunning()) { asrManager.stopRecording() }
    }

    /**
     * 键盘已被系统收起后的兜底：切换 IME 完成后尝试再次请求显示软键盘。
     * 成功率依赖机型与是否仍有焦点输入框；显示中交接路径不依赖此兜底。
     */
    private fun scheduleReshowSoftInputAfterImeSwitch() {
        val decor = window?.window?.decorView ?: return
        // 分几次重试，等待 setInputMethod / switchToPrevious 完成绑定
        for (delayMs in RESHOW_SOFT_INPUT_DELAYS_MS) {
            decor.postDelayed({ tryReshowSoftInputAfterImeSwitch() }, delayMs)
        }
    }

    private fun tryReshowSoftInputAfterImeSwitch() {
        // 若本 IME 仍在显示，说明交接未真正完成或用户又切了回来，勿干扰
        if (isInputViewShown) return
        try {
            // InputMethodService 公开 API：系统会把 show 请求与当前输入会话协调，
            // 无需也无法从 IME 进程取得宿主应用的 served View。
            requestShowSelf(0)
        } catch (t: Throwable) {
            android.util.Log.w(
                "AsrKeyboardService",
                "requestShowSelf after IME switch failed",
                t
            )
        }
    }

    private fun switchToConfiguredImeOrPrevious(): Boolean {
        val targetId = prefs.imeSwitchTargetId
        return if (targetId.isNotBlank()) {
            switchToTargetInputMethod(targetId)
        } else {
            safeSwitchToPreviousInputMethod()
        }
    }

    private fun switchToTargetInputMethod(targetId: String): Boolean {
        if (targetId.isBlank()) return false
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        val enabled = imm.enabledInputMethodList.any { it.id == targetId }
        if (!enabled) return false
        val token = window?.window?.attributes?.token ?: return false
        @Suppress("DEPRECATION")
        imm.setInputMethod(token, targetId)
        return true
    }

    private fun safeSwitchToPreviousInputMethod(): Boolean = try {
        switchToPreviousInputMethod()
    } catch (t: Throwable) {
        android.util.Log.w("AsrKeyboardService", "Failed to switch to previous input method", t)
        false
    }

    private fun showPromptPicker(anchor: View) {
        val presets = prefs.getPromptPresets()
        if (presets.isEmpty()) return
        val popup = PopupMenu(anchor.context, anchor)
        presets.forEachIndexed { idx, p ->
            val item = popup.menu.add(0, idx, idx, p.title)
            item.isCheckable = true
            if (p.id == prefs.activePromptId) item.isChecked = true
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { mi ->
            val position = mi.itemId
            val preset = presets.getOrNull(position) ?: return@setOnMenuItemClickListener false
            prefs.activePromptId = preset.id
            uiRenderer?.clearStatusTextStyle()
            viewRefs?.txtStatusText?.text = getString(R.string.switched_preset, preset.title)
            true
        }
        showPopupMenuKeepingIme(popup)
    }

    private fun showVendorPicker(anchor: View) {
        val vendors = partitionAsrVendorsByConfigured(this, prefs, AsrVendorUi.ordered()).configured
        if (vendors.isEmpty()) return
        val popup = PopupMenu(anchor.context, anchor)
        val cur = prefs.asrVendor
        vendors.forEachIndexed { idx, v ->
            val item = popup.menu.add(0, idx, idx, AsrVendorUi.name(this, v))
            item.isCheckable = true
            if (v == cur) item.isChecked = true
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { mi ->
            val position = mi.itemId
            val vendor = vendors.getOrNull(position)
            if (vendor != null && vendor != prefs.asrVendor) {
                val old = prefs.asrVendor
                prefs.asrVendor = vendor

                // 离开本地引擎时卸载缓存识别器，释放内存
                try {
                    if (AsrLocalVendorLifecycles.isLocalVendor(old)) {
                        AsrLocalVendorLifecycles.unload(old)
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("AsrKeyboardService", "Failed to unload local recognizer", t)
                }

                // 空闲时立即重建引擎
                if (actionHandler.getCurrentState() is KeyboardState.Idle) {
                    asrManager.rebuildEngine()
                }

                // 切换到本地引擎且启用预加载时，尝试预加载
                try {
                    if (AsrLocalVendorLifecycles.isLocalVendor(vendor) &&
                        isLocalAsrPreloadEnabled(vendor, prefs.localAsrPreloadFlags())
                    ) {
                        preloadLocalAsrIfConfigured(this, prefs)
                    }
                } catch (t: Throwable) {
                    android.util.Log.e(
                        "AsrKeyboardService",
                        "Failed to preload local recognizer",
                        t
                    )
                }

                // 状态栏提示
                uiRenderer?.clearStatusTextStyle()
                val name = try {
                    AsrVendorUi.name(this, vendor)
                } catch (t: Throwable) {
                    android.util.Log.w(
                        "AsrKeyboardService",
                        "Failed to resolve AsrVendorUi name: $vendor",
                        t
                    )
                    vendor.name
                }
                viewRefs?.txtStatusText?.text = getString(R.string.switched_preset, name)
            }
            true
        }
        showPopupMenuKeepingIme(popup)
    }

    private fun tryPreloadLocalModel() {
        if (localPreloadTriggered) return
        val p = prefs
        if (!isLocalAsrPreloadEnabled(p.asrVendor, p.localAsrPreloadFlags())) return
        if (isLocalAsrPrepared(p)) {
            localPreloadTriggered = true
            return
        }

        // 信息栏显示"加载中…"，完成后回退状态
        rootView?.post {
            uiRenderer?.clearStatusTextStyle()
            viewRefs?.txtStatusText?.text = getString(R.string.sv_loading_model)
        }
        localPreloadTriggered = true

        serviceScope.launch(Dispatchers.Default) {
            val t0 = android.os.SystemClock.uptimeMillis()
            preloadLocalAsrIfConfigured(
                this@AsrKeyboardService,
                p,
                onLoadStart = null,
                onLoadDone = {
                    val dt = (android.os.SystemClock.uptimeMillis() - t0).coerceAtLeast(0)
                    rootView?.post {
                        uiRenderer?.clearStatusTextStyle()
                        viewRefs?.txtStatusText?.text =
                            getString(R.string.sv_model_ready_with_ms, dt)
                        rootView?.postDelayed({
                            uiRenderer?.clearStatusTextStyle()
                            viewRefs?.txtStatusText?.text =
                                if (asrManager.isRunning()) {
                                    getString(
                                        R.string.status_listening
                                    )
                                } else {
                                    getString(R.string.status_idle)
                                }
                        }, 1200)
                    }
                },
                suppressToastOnStart = true
            )
        }
    }

    private fun syncSystemBarsToKeyboardBackground(anchorView: View? = null) {
        val w = window?.window ?: return
        themeStyler.syncSystemBarsToKeyboardBackground(w, anchorView, anchorView?.context ?: this)
    }

    private fun Prefs.localAsrPreloadFlags(): LocalAsrPreloadFlags = LocalAsrPreloadFlags(
        senseVoice = svPreloadEnabled,
        funAsrNano = fnPreloadEnabled,
        qwen3Asr = qwPreloadEnabled,
        parakeet = pkPreloadEnabled,
        fireRedAsr = frPreloadEnabled,
        xAsr = xAsrPreloadEnabled
    )
}
