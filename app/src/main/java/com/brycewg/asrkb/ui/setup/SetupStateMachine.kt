package com.brycewg.asrkb.ui.setup

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.core.net.toUri
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager

/**
 * 一键设置流程的状态机管理器
 *
 * 负责管理设置流程的状态转换、权限检查和用户交互
 */
class SetupStateMachine(
    private val context: Context,
    private val showMessage: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "SetupStateMachine"
        private const val IME_PICKER_TIMEOUT_MS = 8000L
    }

    var currentState: SetupState = SetupState.NotStarted
        private set

    private val prefs = Prefs(context)

    /**
     * 推进状态机到下一个状态
     *
     * 根据当前状态和系统状态（输入法是否启用/选择、权限是否授予）决定下一步操作
     *
     * @return 下一个状态，如果无法继续则返回当前状态
     */
    fun advance(): SetupState {
        val previous = currentState
        Log.d(TAG, "Advancing from state: $currentState")

        // 整份状态要读 IME 列表和悬浮窗权限，都是跨进程调用。
        // 等待输入法选择的轮询每 300ms 走一次这里，只有真正要判定时才读，避免在 UI 线程反复打 binder。
        var cachedStatus: BasicSetupStatus? = null
        fun status(): BasicSetupStatus = cachedStatus ?: BasicSetupStatus.read(context, prefs).also { cachedStatus = it }

        currentState = when (val state = currentState) {
            is SetupState.NotStarted -> nextStep(status())

            is SetupState.EnablingIme -> {
                val snapshot = status()
                if (snapshot.imeEnabled) {
                    Log.d(TAG, "IME enabled; prioritizing permissions before selecting IME")
                    nextStepAfterImeEnabled(snapshot)
                } else {
                    state
                }
            }

            is SetupState.SelectingIme -> {
                when {
                    // 只有切到本输入法之后才需要判定权限，等待期间不读。
                    isOurImeCurrent(context) -> {
                        Log.d(TAG, "IME selected, resolving next setup step")
                        // 通知权限在进入选择器前已经问过；这里只在必需权限被收回时回到权限步骤。
                        if (status().requiredPermissionsGranted) {
                            SetupState.Completed
                        } else {
                            SetupState.RequestingPermissions(askedNotif = true)
                        }
                    }

                    state.waitingSince > 0 &&
                        System.currentTimeMillis() - state.waitingSince > IME_PICKER_TIMEOUT_MS -> {
                        Log.w(TAG, "IME selection timeout")
                        SetupState.Aborted(context.getString(R.string.setup_abort_reason_ime_timeout))
                    }

                    else -> state
                }
            }

            is SetupState.RequestingPermissions -> {
                val snapshot = status()
                when {
                    !snapshot.requiredPermissionsGranted -> state
                    snapshot.optionalPermissionMissing && !state.askedNotif -> state
                    !snapshot.imeCurrent -> SetupState.SelectingIme()
                    else -> {
                        Log.d(TAG, "Required permissions granted and IME selected, setup completed")
                        SetupState.Completed
                    }
                }
            }

            is SetupState.Completed, is SetupState.Aborted -> state
        }

        if (previous::class != currentState::class) {
            logDiag("state", transitionData(previous, currentState, cachedStatus))
        }

        Log.d(TAG, "Advanced to state: $currentState")
        return currentState
    }

    /**
     * 执行当前状态对应的操作（弹出系统设置、请求权限等）
     *
     * @return true 表示执行了操作，false 表示当前状态无需操作
     */
    fun executeCurrentStateAction(): Boolean {
        Log.d(TAG, "Executing action for state: $currentState")

        return when (val state = currentState) {
            is SetupState.EnablingIme -> {
                if (!state.askedOnce) {
                    // 引导用户前往系统设置启用输入法
                    showSetupMessage(R.string.toast_setup_enable_keyboard_first)
                    try {
                        context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                        // 更新状态标记已提示过
                        currentState = SetupState.EnablingIme(askedOnce = true)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open IME settings", e)
                        false
                    }
                } else {
                    // 已提示过，等待用户操作
                    false
                }
            }

            is SetupState.SelectingIme -> {
                if (!state.askedOnce) {
                    // 唤起输入法选择器
                    try {
                        val imm = context.getSystemService(
                            Context.INPUT_METHOD_SERVICE
                        ) as InputMethodManager
                        imm.showInputMethodPicker()
                        // 更新状态：标记已唤起，并记录开始等待的时间
                        currentState = SetupState.SelectingIme(
                            askedOnce = true,
                            waitingSince = System.currentTimeMillis()
                        )
                        Log.d(TAG, "IME picker shown, waiting for user selection")
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to show IME picker", e)
                        false
                    }
                } else {
                    // 已唤起选择器，等待用户选择
                    false
                }
            }

            is SetupState.RequestingPermissions -> {
                // 按顺序请求缺失的权限
                requestNextMissingPermission(state)
            }

            is SetupState.Completed -> {
                // 显示完成提示
                showSetupMessage(R.string.toast_setup_all_done)
                false
            }

            is SetupState.Aborted -> {
                // 显示中止提示
                showMessage(context.getString(R.string.toast_setup_aborted, state.reason))
                false
            }

            is SetupState.NotStarted -> {
                // 初始状态，无需操作
                false
            }
        }
    }

    /**
     * 请求下一个缺失的权限
     *
     * 按照优先级顺序请求：麦克风 > 悬浮窗 > 通知 > 无障碍
     *
     * @return true 表示发起了权限请求，false 表示所有权限已授予或已全部请求过
     */
    private fun requestNextMissingPermission(state: SetupState.RequestingPermissions): Boolean {
        val status = BasicSetupStatus.read(context, prefs)

        // 1) 麦克风权限（必需）
        if (!state.askedMic && !status.microphoneGranted) {
            Log.d(TAG, "Requesting microphone permission")
            currentState = state.copy(askedMic = true)
            return true
        }

        // 2) 悬浮窗权限（当启用悬浮功能时需要）
        if (!state.askedOverlay && status.overlayRequired && !status.overlayGranted) {
            Log.d(TAG, "Requesting overlay permission")
            showSetupMessage(R.string.toast_need_overlay_perm)
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:${context.packageName}".toUri()
                )
                context.startActivity(intent)
                currentState = state.copy(askedOverlay = true)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request overlay permission", e)
                currentState = state.copy(askedOverlay = true)
                return false
            }
        }

        // 3) 通知权限（Android 13+，增强项）
        if (status.notificationApplicable && !state.askedNotif && !status.notificationGranted) {
            Log.d(TAG, "Requesting notification permission")
            // 实际请求由 Activity.requestPermissions() 处理，这里只标记已发起。
            currentState = state.copy(askedNotif = true)
            return true
        }

        // 4) 无障碍权限（音量键始终依赖；悬浮球在输入法桥接关闭时依赖）
        if (!state.askedA11y && status.accessibilityRequired && !status.accessibilityGranted) {
            Log.d(TAG, "Requesting accessibility permission")
            logA11yDiagnostics()
            showSetupMessage(R.string.toast_need_accessibility_perm)
            try {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                currentState = state.copy(askedA11y = true)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to request accessibility permission", e)
                currentState = state.copy(askedA11y = true)
                return false
            }
        }

        Log.d(TAG, "All permissions requested or granted")
        return false
    }

    private fun nextStep(status: BasicSetupStatus): SetupState {
        if (!status.imeEnabled) return SetupState.EnablingIme()
        return nextStepAfterImeEnabled(status)
    }

    /**
     * 输入法已启用之后：先补权限（含通知），再把切换当前输入法放在最后。
     */
    private fun nextStepAfterImeEnabled(status: BasicSetupStatus): SetupState = when {
        !status.requiredPermissionsGranted || status.optionalPermissionMissing ->
            SetupState.RequestingPermissions()
        !status.imeCurrent -> SetupState.SelectingIme()
        else -> SetupState.Completed
    }

    private fun logDiag(event: String, data: Map<String, Any?>) {
        DebugLogManager.logBase(context, "setup", event, data)
    }

    /**
     * 状态迁移日志只记录判定用到的现场；等待输入法期间没读状态，就不写空字段。
     */
    private fun transitionData(
        from: SetupState,
        to: SetupState,
        status: BasicSetupStatus?
    ): Map<String, Any?> = buildMap {
        put("from", from.diagName())
        put("to", to.diagName())
        if (status == null) return@buildMap
        put("imeEnabled", status.imeEnabled)
        put("imeCurrent", status.imeCurrent)
        put("mic", status.microphoneGranted)
        put("overlayRequired", status.overlayRequired)
        put("overlay", status.overlayGranted)
        put("a11yRequired", status.accessibilityRequired)
        put("a11y", status.accessibilityGranted)
        put("notif", status.notificationGranted)
    }

    /**
     * 无障碍“已开启却判定未开启”的现场快照，只在排查这类反馈时才有用。
     * 走 verbose 流，避免每次用户走到这一步都往常驻日志里写一条只有开发者看得懂的记录。
     */
    private fun logA11yDiagnostics() {
        DebugLogManager.log("setup", "a11y_missing", accessibilityMismatchDiagnostics(context))
    }

    /**
     * 重置状态机到初始状态
     */
    fun reset() {
        Log.d(TAG, "Resetting state machine")
        currentState = SetupState.NotStarted
    }

    private fun showSetupMessage(messageRes: Int) {
        showMessage(context.getString(messageRes))
    }

    /**
     * 提供给 UI 层用于判断当前输入法是否已切换为本应用。
     * 仅暴露只读查询，不改变状态机状态。
     */
    fun isOurImeCurrentForUi(): Boolean = isOurImeCurrent(context)

    /**
     * 获取当前状态对应的 RequestingPermissions，如果不是则返回 null
     * 用于从 Activity 的 onRequestPermissionsResult 回调中更新状态
     */
    fun getCurrentPermissionState(): SetupState.RequestingPermissions? = currentState as? SetupState.RequestingPermissions
}

private fun SetupState.diagName(): String = when (this) {
    is SetupState.NotStarted -> "NotStarted"
    is SetupState.EnablingIme -> "EnablingIme"
    is SetupState.SelectingIme -> "SelectingIme"
    is SetupState.RequestingPermissions -> "RequestingPermissions"
    is SetupState.Completed -> "Completed"
    is SetupState.Aborted -> "Aborted"
}
