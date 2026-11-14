/** 直接 Root 的 daemon 宿主，复用同一个 Binder 看门狗，归属 ui/floating。 */
package com.brycewg.asrkb.ui.floating

import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.topjohnwu.superuser.ipc.RootService

class RootKeepAliveService : RootService() {
    private val watchdog = PrivilegedKeepAliveUserService.forRoot { stopWhenIdle() }

    private fun stopWhenIdle() {
        Handler(Looper.getMainLooper()).post {
            if (watchdog.isIdle()) stopSelf()
        }
    }

    override fun onBind(intent: Intent): IBinder = watchdog

    override fun onDestroy() {
        watchdog.close()
    }
}
