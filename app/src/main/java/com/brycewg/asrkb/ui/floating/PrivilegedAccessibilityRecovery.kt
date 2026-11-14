/** 按已启用功能的依赖恢复无障碍；使用既有增强保活授权，归属 ui/floating。 */
package com.brycewg.asrkb.ui.floating

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.AsrAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal object PrivilegedAccessibilityRecovery {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingCheck: Job? = null
    private var recovering = false
    private var lastAttemptAt: Long? = null

    fun init(context: Context) {
        // 与应用进程同寿命；只观察设置变化，不通过轮询维持无障碍。
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                requestCheck(context)
            }
        }
        listOf(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, Settings.Secure.ACCESSIBILITY_ENABLED).forEach { key ->
            context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(key), false, observer)
        }
    }

    fun requestCheck(context: Context) {
        val app = context.applicationContext
        scope.launch {
            // 自己的重连会产生设置通知和 onDestroy，不能取消正在进行的移除/恢复事务。
            if (recovering) return@launch
            pendingCheck?.cancel()
            pendingCheck = scope.launch {
                // 系统正常绑定是异步的，先让它完成，避免启动时重复切换服务。
                delay(1500L)
                val prefs = Prefs(app)
                if (!prefs.floatingKeepAlivePrivilegedEnabled ||
                    !floatingInputNeedsAccessibility(
                        prefs.floatingAsrEnabled,
                        prefs.volumeKeyRecordingEnabled,
                        prefs.floatingImeBridgeEnabled,
                        prefs.shakeRecordingEnabled
                    ) ||
                    AsrAccessibilityService.isEnabled()
                ) {
                    return@launch
                }
                if (!PrivilegedAccessManager.isServiceGranted() && !PrivilegedAccessManager.isRootProbablyAvailable()) return@launch
                val now = SystemClock.elapsedRealtime()
                if (lastAttemptAt?.let { now - it < 30_000L } == true) return@launch
                lastAttemptAt = now
                recovering = true
                try {
                    val result = PrivilegedAccessManager.runShell(app, recoveryCommand(app))
                    delay(1500L)
                    DebugLogManager.logBase(
                        app,
                        "keepalive",
                        "accessibility_recovery_result",
                        mapOf("method" to result?.method?.name, "exit" to result?.exitCode, "connected" to AsrAccessibilityService.isEnabled())
                    )
                } finally {
                    recovering = false
                }
            }
        }
    }

    private fun recoveryCommand(context: Context): String {
        val component = ComponentName(context, AsrAccessibilityService::class.java)
        val userId = Process.myUid() / 100000
        // 设置值只在 shell 内读取、引用，不拼接到可执行命令中，也不写入日志。
        // 已列出但失联的服务需先移除，再添加；间隔让系统消费异步设置通知。
        val script = """
            read_services() {
                services=${'$'}(settings --user $userId get secure enabled_accessibility_services) || return ${'$'}?
                [ "${'$'}services" != null ] || services=''
                others=''
                saved_ifs=${'$'}IFS
                IFS=:
                set -f
                for entry in ${'$'}services; do
                    case "${'$'}entry" in
                        '${component.flattenToString()}'|'${component.flattenToShortString()}'|'') ;;
                        *) others="${'$'}{others:+${'$'}others:}${'$'}entry" ;;
                    esac
                done
                IFS=${'$'}saved_ifs
            }
            read_services || exit ${'$'}?
            if [ "${'$'}services" != "${'$'}others" ]; then
                settings --user $userId put secure enabled_accessibility_services "${'$'}others" || exit ${'$'}?
                sleep 0.5
            fi
            read_services || exit ${'$'}?
            settings --user $userId put secure enabled_accessibility_services "${'$'}{others:+${'$'}others:}${component.flattenToString()}" || exit ${'$'}?
            settings --user $userId put secure accessibility_enabled 1
        """.trimIndent()
        // libsu 会复用 shell；局部变量、函数与 exit 只作用于本次子 shell。
        return "(\n$script\n)"
    }
}
