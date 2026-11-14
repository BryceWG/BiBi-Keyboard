/** Porter/Shizuku 与直接 Root 的授权及命令入口，归属 ui/floating。 */
package com.brycewg.asrkb.ui.floating

import android.content.Context
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import eu.darken.porter.sdk.PermissionState
import eu.darken.porter.sdk.Porter
import eu.darken.porter.sdk.PorterAvailability
import eu.darken.porter.sdk.PorterBackend
import eu.darken.porter.sdk.PorterException
import eu.darken.porter.sdk.extras.PorterShellException
import eu.darken.porter.sdk.extras.exec
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal object PrivilegedAccessManager {
    enum class PrivilegedMethod { Porter, Shizuku, Root, None }
    enum class PermissionRequestResult { Granted, NotInstalled, Failed }

    data class ExecResult(
        val ok: Boolean,
        val method: PrivilegedMethod,
        val exitCode: Int? = null,
        val stdout: String = ""
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val permissionListeners = CopyOnWriteArraySet<(PermissionRequestResult) -> Unit>()
    private var requestingPermission = false

    fun isServiceGranted(): Boolean = Porter.connection.value?.permission?.value == PermissionState.Granted

    fun addPermissionResultListener(listener: (PermissionRequestResult) -> Unit) {
        permissionListeners.add(listener)
    }

    fun removePermissionResultListener(listener: (PermissionRequestResult) -> Unit) {
        permissionListeners.remove(listener)
    }

    fun requestPermission(context: Context) {
        if (requestingPermission) return
        requestingPermission = true
        scope.launch {
            val result = try {
                when (Porter.availability(context)) {
                    is PorterAvailability.NotInstalled -> PermissionRequestResult.NotInstalled
                    is PorterAvailability.Incompatible, is PorterAvailability.InstalledUnrecognized -> PermissionRequestResult.Failed
                    else -> {
                        val connection = withTimeout(30_000L) { Porter.connection.filterNotNull().first() }
                        if (connection.requestPermission() == PermissionState.Granted) {
                            PermissionRequestResult.Granted
                        } else {
                            PermissionRequestResult.Failed
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logDiag(context, "permission_connection_timeout")
                PermissionRequestResult.Failed
            } catch (e: PorterException) {
                logDiag(context, "permission_failed", data = mapOf("error" to e.javaClass.simpleName))
                PermissionRequestResult.Failed
            } finally {
                requestingPermission = false
            }
            permissionListeners.forEach { it(result) }
        }
    }

    fun isRootProbablyAvailable(): Boolean {
        if (Shell.isAppGrantedRoot() == true) return true
        val paths = listOf("/system/bin", "/system/xbin", "/sbin", "/vendor/bin", "/su/bin") +
            System.getenv("PATH").orEmpty().split(":")
        return paths.any { it.isNotBlank() && File(it, "su").exists() }
    }

    suspend fun runShell(context: Context, command: String): ExecResult? = runShellByService(context, command) ?: runShellByRoot(context, command)

    private suspend fun runShellByService(context: Context, command: String): ExecResult? {
        val connection = Porter.connection.value ?: return null
        if (connection.permission.value != PermissionState.Granted) return null
        val method = when (connection.backend) {
            PorterBackend.PORTER -> PrivilegedMethod.Porter
            PorterBackend.SHIZUKU -> PrivilegedMethod.Shizuku
        }
        return try {
            val result = connection.exec("sh", "-c", command)
            ExecResult(result.exitCode == 0, method, result.exitCode, result.output.trim())
        } catch (e: PorterShellException) {
            logDiag(context, "shell_failed", data = mapOf("method" to method.name, "error" to e.javaClass.simpleName))
            ExecResult(false, method)
        } catch (e: PorterException) {
            logDiag(context, "shell_failed", data = mapOf("method" to method.name, "error" to e.javaClass.simpleName))
            ExecResult(false, method)
        }
    }

    suspend fun runShellByRoot(context: Context, command: String): ExecResult? = withContext(Dispatchers.IO) {
        if (!isRootProbablyAvailable()) return@withContext null
        try {
            val shell = Shell.getShell()
            if (!shell.isRoot) {
                // libsu 会缓存授权被拒后的普通 shell；释放后，下次重试才能重新申请 Root。
                shell.close()
                logDiag(context, "root_permission_denied")
                return@withContext ExecResult(false, PrivilegedMethod.Root)
            }
            val result = shell.newJob().add(command).to(ArrayList<String>()).exec()
            ExecResult(result.isSuccess, PrivilegedMethod.Root, result.code, result.out.joinToString("\n").trim())
        } catch (e: NoShellException) {
            logDiag(context, "root_shell_failed", data = mapOf("error" to e.javaClass.simpleName))
            ExecResult(false, PrivilegedMethod.Root)
        }
    }
    private fun logDiag(context: Context, event: String, data: Map<String, Any?> = emptyMap()) {
        DebugLogManager.logWarning(context, "keepalive", event, data = data)
    }
}
