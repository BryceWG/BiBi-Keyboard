/** 系统周期 Job 入口，仅承载任务生命周期；保活流程交给协调器，归属 ui/floating。 */
package com.brycewg.asrkb.ui.floating

import android.app.job.JobParameters
import android.app.job.JobService
import com.brycewg.asrkb.store.debug.DebugLogManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

class PrivilegedKeepAliveJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onStartJob(params: JobParameters?): Boolean {
        if (params == null) return false
        scope.launch {
            try {
                KeepAliveCoordinator.runScheduledCheck(this@PrivilegedKeepAliveJobService)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        scope.coroutineContext.cancelChildren()
        DebugLogManager.logBase(this, "keepalive", "job_stop")
        return true
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
