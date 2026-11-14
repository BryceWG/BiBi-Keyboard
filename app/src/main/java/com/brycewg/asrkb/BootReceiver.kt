/** 开机与特权守护广播入口；校验请求后由保活协调器按各功能开关恢复。 */
package com.brycewg.asrkb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.floating.FloatingKeepAliveService
import com.brycewg.asrkb.ui.floating.KeepAliveCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_RESTORE = "com.brycewg.asrkb.action.PRIVILEGED_KEEP_ALIVE_RESTORE"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val restoring = action == ACTION_RESTORE
        if (restoring) {
            val token = intent.getStringExtra(FloatingKeepAliveService.EXTRA_CALLER_TOKEN)
            if (token != FloatingKeepAliveService.getOrCreateCallerToken(context)) {
                DebugLogManager.logWarning(context, "keepalive", "restore_rejected")
                return
            }
            if (!Prefs(context).floatingKeepAlivePrivilegedEnabled) return
        } else if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }
        val app = context.applicationContext
        DebugLogManager.logBase(app, "keepalive", if (restoring) "restore_received" else "boot_received")
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!restoring) delay(2500L)
                KeepAliveCoordinator.restoreEnabledServices(app, if (restoring) "watchdog" else "boot")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                DebugLogManager.logWarning(app, "keepalive", "restore_failed", data = mapOf("error" to e.javaClass.simpleName))
            } finally {
                pendingResult.finish()
            }
        }
    }
}
