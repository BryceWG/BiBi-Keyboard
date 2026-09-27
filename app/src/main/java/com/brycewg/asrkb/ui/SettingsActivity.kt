/**
 * 主设置入口页面。
 *
 * 提供一键设置流程、入口导航与常用工具能力。
 */
package com.brycewg.asrkb.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsTestInputSheet
import com.brycewg.asrkb.ui.settings.compose.components.SettingsUpdateHost
import com.brycewg.asrkb.ui.settings.compose.components.SettingsUpdateUiState
import com.brycewg.asrkb.ui.settings.compose.core.BibiSettingsRoute
import com.brycewg.asrkb.ui.settings.compose.core.BibiSettingsTheme
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsActionController
import com.brycewg.asrkb.ui.settings.compose.screens.SettingsRootScreen
import com.brycewg.asrkb.ui.settings.compose.state.SettingsEntryEffectsCoordinator
import com.brycewg.asrkb.ui.settings.compose.state.SettingsHostViewModel
import com.brycewg.asrkb.ui.settings.compose.state.SettingsImePickerController
import com.brycewg.asrkb.ui.settings.compose.state.SettingsUpdateCoordinator
import com.brycewg.asrkb.ui.setup.SetupState
import com.brycewg.asrkb.ui.setup.SetupStateMachine
import com.brycewg.asrkb.util.HapticFeedbackHelper

/**
 * 主设置页面
 *
 * 提供：
 * - 一键设置流程（基于状态机）
 * - 更新检查（通过 SettingsUpdateCoordinator）
 * - 设置导入/导出
 * - 子设置页导航
 * - 测试输入体验
 */
class SettingsActivity : BaseActivity() {
    companion object {
        private const val TAG = "SettingsActivity"
        const val EXTRA_AUTO_SHOW_IME_PICKER = "extra_auto_show_ime_picker"
        const val EXTRA_SHOW_IME_PICKER = "extra_show_ime_picker"
        const val EXTRA_INITIAL_ROUTE = "extra_initial_settings_route"
    }

    // 一键设置状态机
    private lateinit var setupStateMachine: SetupStateMachine

    private lateinit var prefs: Prefs
    private lateinit var imePickerController: SettingsImePickerController
    private lateinit var entryEffectsCoordinator: SettingsEntryEffectsCoordinator
    private lateinit var updateCoordinator: SettingsUpdateCoordinator

    // Handler 用于延迟任务
    private val handler = Handler(Looper.getMainLooper())

    // 一键设置轮询任务（用于等待用户选择输入法）
    private var setupPollingRunnable: Runnable? = null

    // 一键设置触发的 IME 选择器前台状态
    private var setupImePickerShown = false
    private var setupImePickerLostFocusOnce = false

    // 设置页手动触发的 IME 选择器前台状态
    private var settingsImePickerShown = false
    private var settingsImePickerLostFocusOnce = false

    private val requestSetupMicPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            handler.postDelayed({ advanceSetupIfInProgress() }, 200)
        }

    private val testInputSheetVisible = mutableStateOf(false)
    private val systemActionDialogState = mutableStateOf<SettingsMessageDialogState?>(null)
    private val pendingInitialRoute = mutableStateOf<BibiSettingsRoute?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = Prefs(this)

        // 初始化状态机和工具类
        setupStateMachine = SetupStateMachine(this, ::showSetupStateMessage)
        imePickerController = SettingsImePickerController(
            activity = this,
            handler = handler,
            autoShowExtra = EXTRA_AUTO_SHOW_IME_PICKER,
            showExtra = EXTRA_SHOW_IME_PICKER
        )
        entryEffectsCoordinator = SettingsEntryEffectsCoordinator(
            activity = this,
            showSystemMessage = ::showSystemActionDialog
        )
        updateCoordinator = SettingsUpdateCoordinator(this)

        val actionController = SettingsActionController(this)
        val coldStartRoute = if (savedInstanceState == null) {
            consumeInitialRouteExtra(intent)
        } else {
            null
        }
        setContent {
            val viewModel: SettingsHostViewModel = viewModel(
                factory = remember {
                    settingsHostViewModelFactory(application, coldStartRoute)
                }
            )
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val routeToOpen = pendingInitialRoute.value

            LaunchedEffect(routeToOpen) {
                routeToOpen?.let { route ->
                    viewModel.openExternalRoute(route)
                    pendingInitialRoute.value = null
                }
            }

            BibiSettingsTheme(
                uiMode = uiState.uiMode,
                themeMode = uiState.themeMode
            ) {
                val hasUpdateAvailable by remember {
                    derivedStateOf { updateCoordinator.uiState.value is SettingsUpdateUiState.UpdateAvailable }
                }
                CompositionLocalProvider(LocalSettingsHapticTap provides actionController::hapticTap) {
                    SettingsRootScreen(
                        uiState = uiState,
                        hasUpdateAvailable = hasUpdateAvailable,
                        onSelectTab = viewModel::selectHomeTab,
                        onPushRoute = viewModel::push,
                        onOpenRoute = viewModel::openRoute,
                        onPopRoute = viewModel::pop,
                        onSetUiMode = viewModel::setUiMode,
                        onSetThemeMode = viewModel::setThemeMode,
                        actions = actionController
                    )
                    settingsOverlayHosts(uiMode = uiState.uiMode)
                }
            }
        }

        imePickerController.consumeShowImePickerExtraIfPresent(intent)
    }

    @Composable
    private fun settingsOverlayHosts(uiMode: BibiUiMode) {
        SettingsTestInputSheet(
            show = testInputSheetVisible.value,
            uiMode = uiMode,
            onDismiss = { testInputSheetVisible.value = false }
        )
        SettingsUpdateHost(
            state = updateCoordinator.uiState.value,
            uiMode = uiMode,
            onDismiss = updateCoordinator::dismiss,
            onDownload = updateCoordinator::showDownloadSources,
            onOpenReleasePage = updateCoordinator::openReleasePage,
            onOpenChangelog = updateCoordinator::openChangelogHistory,
            onManualCheck = updateCoordinator::openManualReleasePage,
            onSelectDownloadSource = updateCoordinator::startDownload
        )
        SettingsMessageDialog(
            state = systemActionDialogState.value,
            uiMode = uiMode,
            onDismiss = { systemActionDialogState.value = null }
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingInitialRoute.value = consumeInitialRouteExtra(intent)
        imePickerController.consumeShowImePickerExtraIfPresent(intent)
        imePickerController.handleShowImePickerFromTile(hasWindowFocus())
    }

    private fun settingsHostViewModelFactory(
        application: Application,
        initialRoute: BibiSettingsRoute?
    ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsHostViewModel(application, initialRoute) as T
    }

    private fun consumeInitialRouteExtra(intent: Intent?): BibiSettingsRoute? {
        val route = BibiSettingsRoute.fromId(intent?.getStringExtra(EXTRA_INITIAL_ROUTE))
            ?: return null
        intent?.removeExtra(EXTRA_INITIAL_ROUTE)
        return route
    }

    override fun onResume() {
        super.onResume()

        updateCoordinator.onResume()
        entryEffectsCoordinator.onResume()

        // 若处于一键设置流程中，返回后继续推进
        advanceSetupIfInProgress()

        // 匿名数据采集选择已整合进新手引导页，此处不再自动弹窗
    }

    override fun onStop() {
        super.onStop()
        // 退出设置页时停止一键设置轮询，避免后台弹出不合时机的提示
        stopSetupPolling()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)

        imePickerController.onWindowFocusChanged(hasFocus)

        // 处理一键设置流程中的 IME 选择器焦点变化
        handleSetupImePickerFocus(hasFocus)
        handleSettingsImePickerFocus(hasFocus)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        // 权限请求结果返回后，继续推进一键设置流程
        if (setupStateMachine.currentState is SetupState.RequestingPermissions) {
            Log.d(TAG, "Permission result received, advancing setup")
            // 小延迟，等待系统状态稳定
            handler.postDelayed({ advanceSetupIfInProgress() }, 200)
        }
    }

    fun startOneClickSetupFromCompose() {
        startOneClickSetup()
    }

    fun checkForUpdatesFromCompose() {
        updateCoordinator.checkForUpdates()
    }

    fun showTestInputFromCompose() {
        testInputSheetVisible.value = true
    }

    fun showImePickerFromCompose() {
        try {
            val imm = getSystemService(InputMethodManager::class.java)
            if (imm == null) {
                showSystemActionDialog(
                    titleRes = R.string.settings_ime_picker_title,
                    messageRes = R.string.settings_ime_picker_open_failed_message
                )
                return
            }
            settingsImePickerShown = true
            settingsImePickerLostFocusOnce = false
            imm.showInputMethodPicker()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to show IME picker from settings", e)
            settingsImePickerShown = false
            settingsImePickerLostFocusOnce = false
            showSystemActionDialog(
                titleRes = R.string.settings_ime_picker_title,
                messageRes = R.string.settings_ime_picker_open_failed_message
            )
        }
    }

    fun hapticTapFromCompose() {
        hapticTapIfEnabled(null)
    }

    fun updatesEnabledFromCompose(): Boolean = updateCoordinator.updatesEnabled

    fun showSystemActionDialogFromCompose(
        titleRes: Int,
        messageRes: Int
    ) {
        showSystemActionDialog(titleRes = titleRes, messageRes = messageRes)
    }

    // ==================== 一键设置相关 ====================

    /**
     * 启动一键设置流程
     */
    private fun startOneClickSetup() {
        Log.d(TAG, "Starting one-click setup")

        // 重置状态机
        setupStateMachine.reset()
        stopSetupPolling()

        // 推进到第一个状态
        advanceSetupStateMachine()
    }

    /**
     * 推进一键设置状态机
     *
     * 1. 调用状态机的 advance() 方法获取下一个状态
     * 2. 执行该状态对应的操作
     * 3. 如果是 SelectingIme 状态，启动轮询等待用户选择
     */
    private fun advanceSetupStateMachine() {
        val newState = setupStateMachine.advance()
        val didExecute = setupStateMachine.executeCurrentStateAction()

        Log.d(TAG, "Setup state: $newState, executed action: $didExecute")

        when (newState) {
            is SetupState.SelectingIme -> {
                // 第一次进入该状态会唤起 IME 选择器；此时立即开始轮询
                if (didExecute) {
                    setupImePickerShown = true
                    setupImePickerLostFocusOnce = false
                    startSetupPolling()
                } else if (newState.askedOnce) {
                    // 返回后继续等待用户选择输入法
                    startSetupPolling()
                }
            }

            is SetupState.Completed, is SetupState.Aborted -> {
                // 设置完成或中止，停止轮询
                stopSetupPolling()
                setupImePickerShown = false
                setupImePickerLostFocusOnce = false
            }

            is SetupState.RequestingPermissions -> {
                // 权限请求阶段，某些权限需要通过 Activity 的回调处理
                if (didExecute) {
                    val state = setupStateMachine.getCurrentPermissionState()
                    if (state?.askedMic == true && !hasRecordAudioPermission()) {
                        requestSetupMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else if (state?.askedNotif == true && !state.askedA11y) {
                        // Android 13+ 通知权限请求
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            if (ContextCompat.checkSelfPermission(
                                    this,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                requestPermissions(
                                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                                    1001
                                )
                            } else {
                                // 已授予，继续推进
                                handler.postDelayed({ advanceSetupStateMachine() }, 200)
                            }
                        } else {
                            // Android 12 及以下，跳过
                            handler.postDelayed({ advanceSetupStateMachine() }, 200)
                        }
                    }
                }
            }

            else -> {
                // 其他状态，无需特殊处理
            }
        }
    }

    private fun hasRecordAudioPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * 如果正在一键设置流程中，继续推进
     */
    private fun advanceSetupIfInProgress() {
        if (setupStateMachine.currentState !is SetupState.NotStarted &&
            setupStateMachine.currentState !is SetupState.Completed &&
            setupStateMachine.currentState !is SetupState.Aborted
        ) {
            Log.d(TAG, "Resuming setup flow")
            handler.post { advanceSetupStateMachine() }
        }
    }

    /**
     * 启动轮询，等待用户选择输入法
     *
     * 轮询间隔 300ms，最长等待 8 秒
     */
    private fun startSetupPolling() {
        stopSetupPolling()

        Log.d(TAG, "Starting setup polling for IME selection")

        val runnable = object : Runnable {
            override fun run() {
                val state = setupStateMachine.currentState as? SetupState.SelectingIme
                    ?: return

                // 再次推进状态机（检查是否已选择）
                setupStateMachine.advance()

                val newState = setupStateMachine.currentState

                when (newState) {
                    is SetupState.RequestingPermissions -> {
                        // 用户已选择输入法，进入权限阶段
                        Log.d(TAG, "IME selected during polling, advancing to permissions")
                        stopSetupPolling()
                        advanceSetupStateMachine()
                    }

                    is SetupState.Aborted -> {
                        // 超时或其他原因中止
                        Log.d(TAG, "Setup aborted during polling")
                        stopSetupPolling()
                        if (setupImePickerShown && !hasWindowFocus()) {
                            showSystemActionDialog(
                                titleRes = R.string.settings_ime_picker_title,
                                messageRes = R.string.toast_setup_choose_keyboard
                            )
                        } else {
                            Log.d(TAG, "Skip IME choose toast: picker not foreground")
                        }
                    }

                    is SetupState.Completed -> {
                        // 已完成（不太可能在这个阶段发生）
                        stopSetupPolling()
                    }

                    else -> {
                        // 继续轮询
                        handler.postDelayed(this, 300)
                    }
                }
            }
        }

        setupPollingRunnable = runnable
        handler.postDelayed(runnable, 350)
    }

    /**
     * 停止轮询
     */
    private fun stopSetupPolling() {
        setupPollingRunnable?.let { handler.removeCallbacks(it) }
        setupPollingRunnable = null
    }

    /**
     * 一键设置中 IME 选择器焦点变化处理：
     * - 选择器弹出：activity 失去焦点
     * - 选择器关闭：activity 恢复焦点
     *
     * 若关闭时仍未选择本输入法，则静默结束一键设置流程，避免回到设置页后再提示。
     */
    private fun handleSetupImePickerFocus(hasFocus: Boolean) {
        val selecting = setupStateMachine.currentState as? SetupState.SelectingIme
        if (!setupImePickerShown || selecting == null) {
            if (setupImePickerShown && selecting == null) {
                setupImePickerShown = false
                setupImePickerLostFocusOnce = false
            }
            return
        }

        if (!hasFocus) {
            setupImePickerLostFocusOnce = true
            Log.d(TAG, "One-click IME picker shown, activity lost focus")
            return
        }

        if (setupImePickerLostFocusOnce) {
            // 选择器关闭：根据是否已切换输入法决定下一步
            stopSetupPolling()
            if (setupStateMachine.isOurImeCurrentForUi()) {
                Log.d(TAG, "One-click IME picker closed with selection, continue setup")
                handler.post { advanceSetupStateMachine() }
            } else {
                Log.d(TAG, "One-click IME picker closed without selection, abort silently")
                setupStateMachine.reset()
            }
            setupImePickerShown = false
            setupImePickerLostFocusOnce = false
        }
    }

    private fun handleSettingsImePickerFocus(hasFocus: Boolean) {
        if (!settingsImePickerShown) return

        if (!hasFocus) {
            settingsImePickerLostFocusOnce = true
            Log.d(TAG, "Settings IME picker shown, activity lost focus")
            return
        }

        if (settingsImePickerLostFocusOnce) {
            val messageRes = if (setupStateMachine.isOurImeCurrentForUi()) {
                R.string.settings_ime_picker_selected_message
            } else {
                R.string.settings_ime_picker_closed_message
            }
            showSystemActionDialog(
                titleRes = R.string.settings_ime_picker_title,
                messageRes = messageRes
            )
            settingsImePickerShown = false
            settingsImePickerLostFocusOnce = false
        }
    }

    private fun showSystemActionDialog(
        titleRes: Int,
        messageRes: Int
    ) {
        systemActionDialogState.value = SettingsMessageDialogState(
            title = getString(titleRes),
            message = getString(messageRes),
            confirmText = getString(android.R.string.ok)
        )
    }

    private fun showSetupStateMessage(message: String) {
        systemActionDialogState.value = SettingsMessageDialogState(
            title = getString(R.string.btn_one_click_setup),
            message = message,
            confirmText = getString(android.R.string.ok)
        )
    }

    private fun hapticTapIfEnabled(view: View?) {
        HapticFeedbackHelper.performTap(this, prefs, view)
    }
}
