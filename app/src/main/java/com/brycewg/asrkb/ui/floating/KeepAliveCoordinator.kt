/** 保活的应用级协调器：集中处理守护启停、周期调度、前台服务启动及后台设置恢复，归属 ui/floating。 */
package com.brycewg.asrkb.ui.floating

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.brycewg.asrkb.aidl.IKeepAliveWatchdog
import com.brycewg.asrkb.store.KEY_FLOATING_ASR_ENABLED
import com.brycewg.asrkb.store.KEY_FLOATING_IME_BRIDGE_ENABLED
import com.brycewg.asrkb.store.KEY_FLOATING_KEEP_ALIVE_PRIVILEGED_ENABLED
import com.brycewg.asrkb.store.KEY_SHAKE_RECORDING_ENABLED
import com.brycewg.asrkb.store.KEY_VOLUME_KEY_RECORDING_ENABLED
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.topjohnwu.superuser.ipc.RootService
import eu.darken.porter.sdk.PermissionState
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterConnection
import eu.darken.porter.sdk.PorterException
import eu.darken.porter.sdk.UserServiceArgs
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal object KeepAliveCoordinator {
    private const val TAG = "KeepAliveCoordinator"
    private const val JOB_ID = 4103
    private const val PERIOD_MS = 15 * 60 * 1000L
    private const val START_COOLDOWN_MS = 3500L
    private val lastPrivilegedStartAtMs = AtomicLong(0L)
    private val initialized = AtomicBoolean()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var bindingJob: Job? = null
    private var boundConnection: PorterConnection? = null
    private var preferenceListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun init(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        PrivilegedAccessibilityRecovery.init(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == KEY_FLOATING_KEEP_ALIVE_PRIVILEGED_ENABLED) {
                update(context)
            } else if (key == KEY_FLOATING_ASR_ENABLED ||
                key == KEY_FLOATING_IME_BRIDGE_ENABLED ||
                key == KEY_VOLUME_KEY_RECORDING_ENABLED ||
                key == KEY_SHAKE_RECORDING_ENABLED
            ) {
                PrivilegedAccessibilityRecovery.requestCheck(context)
            }
        }
        preferenceListener = listener
        context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE).registerOnSharedPreferenceChangeListener(listener)
        update(context)
        scope.launch { restoreEnabledServices(context, "app") }
        scope.launch {
            Porter.connection.collectLatest { connection ->
                if (connection == null) {
                    refreshRuntime(context)
                } else {
                    connection.permission.collect { refreshRuntime(context) }
                }
            }
        }
    }

    private fun update(context: Context) {
        refreshRuntime(context)
        val prefs = Prefs(context)
        logDiag(context, "scheduler_update", mapOf("enabled" to prefs.floatingKeepAlivePrivilegedEnabled, "keepAlive" to prefs.floatingKeepAliveEnabled, "privileged" to prefs.floatingKeepAlivePrivilegedEnabled))
        if (prefs.floatingKeepAlivePrivilegedEnabled) schedule(context) else cancelSchedule(context)
    }

    suspend fun runScheduledCheck(context: Context) {
        refreshRuntime(context)
        logDiag(context, "job_start")
        try {
            val prefs = Prefs(context)
            if (!prefs.floatingKeepAlivePrivilegedEnabled) {
                logDiag(context, "job_skip", mapOf("reason" to "pref_disabled"))
                return
            }
            // 独立守护始终按增强开关运行；前台服务只按自己的开关启动。
            if (!prefs.floatingKeepAliveEnabled) return
            val result = startForeground(context, "job") ?: return
            logDiag(context, "job_result", mapOf("method" to result.method.name.lowercase(), "ok" to result.ok, "exit" to result.exitCode))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logDiag(context, "job_error", mapOf("error" to e.javaClass.simpleName), warning = true)
        }
    }

    suspend fun restoreEnabledServices(context: Context, source: String) {
        val prefs = Prefs(context)
        if (prefs.floatingAsrEnabled && Settings.canDrawOverlays(context)) {
            try {
                context.startService(Intent(context, FloatingAsrService::class.java).setAction(FloatingAsrService.ACTION_SHOW))
            } catch (e: RuntimeException) {
                logDiag(context, "overlay_restore_failed", mapOf("source" to source, "error" to e.javaClass.simpleName), warning = true)
            }
        }
        if (prefs.floatingKeepAliveEnabled) {
            // 普通前台保活不依赖特权授权；特权恢复由守护就绪和周期检查单独触发。
            val result = startForegroundNormally(context)
            logDiag(context, "start_result", mapOf("source" to source, "method" to result.method.name, "ok" to result.ok))
        }
    }

    private suspend fun startForeground(context: Context, source: String): PrivilegedAccessManager.ExecResult? {
        val prefs = Prefs(context)
        if (!prefs.floatingKeepAliveEnabled) return null
        if (!tryAcquireStartWindow(source)) return null
        val result = if (prefs.floatingKeepAlivePrivilegedEnabled) {
            PrivilegedAccessManager.runShell(context, foregroundServiceCommand(context)) ?: startForegroundNormally(context)
        } else {
            startForegroundNormally(context)
        }
        logDiag(context, "start_result", mapOf("source" to source, "method" to result.method.name, "ok" to result.ok, "exit" to result.exitCode))
        return result
    }

    private fun tryAcquireStartWindow(source: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        while (true) {
            val last = lastPrivilegedStartAtMs.get()
            if (last > 0 && now - last < START_COOLDOWN_MS) {
                DebugLogManager.log("keepalive", "start_throttled", mapOf("source" to source))
                return false
            }
            if (lastPrivilegedStartAtMs.compareAndSet(last, now)) return true
        }
    }

    private fun refreshRuntime(context: Context) {
        val app = context.applicationContext
        PrivilegedAccessibilityRecovery.requestCheck(app)
        scope.launch {
            mutex.withLock {
                try {
                    reconcile(app)
                } catch (e: TimeoutCancellationException) {
                    logDiag(app, "runtime_update_timeout", warning = true)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logDiag(app, "runtime_update_failed", data = mapOf("error" to e.javaClass.simpleName), warning = true)
                }
            }
        }
    }

    private suspend fun reconcile(context: Context) {
        val prefs = Prefs(context)
        val policy = BackgroundSettings(context)
        val journal = context.getSharedPreferences("privileged_keep_alive_runtime", Context.MODE_PRIVATE)
        if (!prefs.floatingKeepAlivePrivilegedEnabled) {
            bindingJob?.cancelAndJoin()
            bindingJob = null
            boundConnection = null
            if (!stopPendingWatchdogs(context)) return
            if (policy.hasPendingRestore) policy.restore()
            return
        }
        val connection = Porter.connection.value?.takeIf { it.permission.value == PermissionState.Granted }
        if (bindingJob?.isActive == true && connection === boundConnection) return
        bindingJob?.cancelAndJoin()
        bindingJob = null
        boundConnection = connection
        // 上次进程可能在保存开关后、撤销守护前退出。切换后端时先收回旧守护。
        if (connection == null && journal.getBoolean("service_started", false)) stopPendingWatchdogs(context, stopRoot = false)
        if (connection != null &&
            journal.getBoolean("root_started", false) &&
            !stopPendingWatchdogs(context, stopService = false)
        ) {
            return
        }
        if (connection == null) {
            val root = PrivilegedAccessManager.runShellByRoot(context, "true")
            if (root?.ok != true) {
                logDiag(context, "watchdog_unavailable")
                return
            }
        }
        policy.apply()
        bindingJob = scope.launch {
            val method = connection?.backend?.name?.lowercase() ?: "root"
            val args = userServiceArgs(context)
            val startedKey = if (connection == null) "root_started" else "service_started"
            journal.edit().putBoolean(startedKey, true).commit()
            val client = Binder()
            var watchdog: IKeepAliveWatchdog? = null
            try {
                val binders = connection?.userService(args) ?: rootService(context)
                coroutineScope {
                    val channel = binders.produceIn(this)
                    try {
                        val binder = withTimeout(15_000L) { channel.receive() }
                        watchdog = attachWatchdog(context, binder, client, method)
                        startForeground(context, "privileged_ready")
                        // 连接的流完成表示守护死亡；下一次调度/授权事件可以重新绑定。
                        for (next in channel) {
                            watchdog = attachWatchdog(context, next, client, method)
                            startForeground(context, "privileged_ready")
                        }
                    } finally {
                        channel.cancel()
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logDiag(context, "watchdog_bind_timeout", data = mapOf("method" to method), warning = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logDiag(context, "watchdog_failed", data = mapOf("method" to method, "error" to e.javaClass.simpleName), warning = true)
            } finally {
                // 应用正常解绑必须撤销死亡监听；进程被杀时 finally 不执行，daemon 继续恢复。
                withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        watchdog?.unwatch(client)
                        if (connection == null && watchdog != null) journal.edit().remove(startedKey).commit()
                    } catch (e: RemoteException) {
                        logDiag(context, "watchdog_detach_failed", data = mapOf("method" to method), warning = true)
                    }
                    try {
                        if (connection != null) {
                            connection.stopUserService(args)
                            journal.edit().remove(startedKey).commit()
                        }
                    } catch (e: PorterException) {
                        logDiag(context, "watchdog_stop_failed", data = mapOf("method" to method, "error" to e.javaClass.simpleName), warning = true)
                    }
                    logDiag(context, "watchdog_detached", mapOf("method" to method))
                }
            }
        }
    }

    private fun userServiceArgs(context: Context): UserServiceArgs = UserServiceArgs(
        componentName = ComponentName(context, PrivilegedKeepAliveUserService::class.java),
        processNameSuffix = "keepalive",
        tag = "bibi_keepalive",
        version = 2,
        daemon = true
    )

    private suspend fun stopPendingWatchdogs(context: Context, stopRoot: Boolean = true, stopService: Boolean = true): Boolean {
        val journal = context.getSharedPreferences("privileged_keep_alive_runtime", Context.MODE_PRIVATE)
        val connection = Porter.connection.value
        if (stopService && journal.getBoolean("service_started", false) && connection?.permission?.value == PermissionState.Granted) {
            connection.stopUserService(userServiceArgs(context))
            journal.edit().remove("service_started").commit()
        }
        if (stopRoot && journal.getBoolean("root_started", false)) {
            // libsu 的绑定入口会读取缓存的授权状态，先让这次清理真正获取一次 Root。
            if (PrivilegedAccessManager.runShellByRoot(context, "true")?.ok != true) return false
            val binder = withTimeout(15_000L) { rootService(context).first() }
            IKeepAliveWatchdog.Stub.asInterface(binder).unwatch(null)
            journal.edit().remove("root_started").commit()
        }
        return true
    }

    private fun attachWatchdog(context: Context, binder: IBinder, client: IBinder, method: String): IKeepAliveWatchdog {
        val remote = IKeepAliveWatchdog.Stub.asInterface(binder)
        val report = remote.watch(client, context.packageName, FloatingKeepAliveService.getOrCreateCallerToken(context))
        logDiag(context, "watchdog_attached", mapOf("method" to method, "previousEvent" to report.getString("event"), "previousExit" to if (report.containsKey("exit")) report.getInt("exit") else null))
        PrivilegedAccessibilityRecovery.requestCheck(context)
        return remote
    }

    private fun rootService(context: Context): Flow<IBinder> = callbackFlow {
        val intent = Intent(context, RootKeepAliveService::class.java).addCategory(RootService.CATEGORY_DAEMON_MODE)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (trySend(service).isFailure) {
                    scope.launch {
                        try {
                            IKeepAliveWatchdog.Stub.asInterface(service).unwatch(Binder())
                        } catch (e: RemoteException) {
                            logDiag(context, "root_late_detach_failed", warning = true)
                        }
                    }
                }
            }
            override fun onServiceDisconnected(name: ComponentName) {
                close()
            }
        }
        withContext(Dispatchers.Main.immediate) { RootService.bind(intent, connection) }
        awaitClose {
            scope.launch(Dispatchers.Main.immediate) { RootService.unbind(connection) }
        }
    }
    private fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val component = ComponentName(context, PrivilegedKeepAliveJobService::class.java)
        val info = JobInfo.Builder(JOB_ID, component)
            .setPersisted(true)
            .setPeriodic(PERIOD_MS)
            // 不额外要求充电、电量充足或设备空闲；执行时机仍由系统决定。
            .setRequiresBatteryNotLow(false)
            .setRequiresDeviceIdle(false)
            .setRequiresCharging(false)
            .build()

        val result = try {
            scheduler.schedule(info)
        } catch (t: Throwable) {
            Log.w(TAG, "schedule privileged keep-alive job failed", t)
            JobScheduler.RESULT_FAILURE
        }
        if (result != JobScheduler.RESULT_SUCCESS) {
            Log.w(TAG, "schedule privileged keep-alive job not success: $result")
        }
        logDiag(context, "scheduler_schedule", mapOf("result" to result))
    }

    private fun cancelSchedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        try {
            scheduler.cancel(JOB_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "cancel privileged keep-alive job failed", t)
        }
        logDiag(context, "scheduler_cancel")
    }

    private fun foregroundServiceCommand(context: Context): String {
        val component = "${context.packageName}/${FloatingKeepAliveService::class.java.name}"
        val token = FloatingKeepAliveService.getOrCreateCallerToken(context)
        return "am start-foreground-service --user ${Process.myUid() / 100000} -n $component" +
            " -a ${FloatingKeepAliveService.ACTION_START}" +
            " --es ${FloatingKeepAliveService.EXTRA_CALLER_TOKEN} $token"
    }

    private fun startForegroundNormally(context: Context): PrivilegedAccessManager.ExecResult = try {
        FloatingKeepAliveService.start(context)
        PrivilegedAccessManager.ExecResult(true, PrivilegedAccessManager.PrivilegedMethod.None)
    } catch (e: RuntimeException) {
        logDiag(context, "normal_start_failed", mapOf("error" to e.javaClass.simpleName), warning = true)
        PrivilegedAccessManager.ExecResult(false, PrivilegedAccessManager.PrivilegedMethod.None)
    }

    private class BackgroundSettings(private val context: Context) {
        // 设备本地操作记录，不属于用户偏好，也不进入备份。
        private val journal = context.getSharedPreferences("privileged_keep_alive_policy", Context.MODE_PRIVATE)
        private val packageName = context.packageName
        private val userId = Process.myUid() / 100000
        private val backgroundOp = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) "RUN_ANY_IN_BACKGROUND" else "RUN_IN_BACKGROUND"

        val hasPendingRestore: Boolean
            get() = journal.all.isNotEmpty()

        suspend fun apply() {
            val oldMode = readBackgroundMode()
            if (oldMode != null && oldMode != "allow") {
                if (!journal.contains("background_mode")) {
                    journal.edit().putString("background_mode", oldMode).commit()
                }
                execute("background_allow", "cmd appops set --user $userId $packageName $backgroundOp allow")
            }
            val power = context.getSystemService(PowerManager::class.java)
            if (!power.isIgnoringBatteryOptimizations(packageName)) {
                journal.edit().putBoolean("whitelist_added", true).commit()
                execute("doze_allow", "dumpsys deviceidle whitelist +$packageName")
            }
            // active 是一次性提示，不是锁定；API 26/27 尚无待机分组命令。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                execute("standby_active", "am set-standby-bucket --user $userId $packageName active")
            }
        }

        suspend fun restore() {
            val mode = journal.getString("background_mode", null)
            if (mode != null) {
                val current = readBackgroundMode()
                if (current != null && (current != "allow" || execute("background_restore", "cmd appops set --user $userId $packageName $backgroundOp $mode"))) {
                    journal.edit().remove("background_mode").commit()
                }
            }
            if (journal.getBoolean("whitelist_added", false) &&
                execute("doze_restore", "dumpsys deviceidle whitelist -$packageName")
            ) {
                journal.edit().remove("whitelist_added").commit()
            }
        }

        private suspend fun readBackgroundMode(): String? {
            val result = PrivilegedAccessManager.runShell(context, "cmd appops get --user $userId $packageName $backgroundOp")
            if (result?.ok != true) return null
            if (result.stdout.contains("No operations.")) return "default"
            return Regex("$backgroundOp: (allow|ignore|deny|default|foreground)").find(result.stdout)?.groupValues?.get(1)
        }

        private suspend fun execute(event: String, command: String): Boolean {
            val result = PrivilegedAccessManager.runShell(context, command)
            logDiag(context, "policy_result", mapOf("operation" to event, "ok" to (result?.ok == true), "exit" to result?.exitCode))
            return result?.ok == true
        }
    }

    private fun logDiag(context: Context, event: String, data: Map<String, Any?> = emptyMap(), warning: Boolean = false) {
        if (warning) {
            DebugLogManager.logWarning(context, "keepalive", event, data = data)
        } else {
            DebugLogManager.logBase(context, "keepalive", event, data)
        }
    }
}
