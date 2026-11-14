/** 独立特权进程的 Binder 看门狗；Porter/Shizuku 与 Root 共用，归属 ui/floating。 */
package com.brycewg.asrkb.ui.floating

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import com.brycewg.asrkb.BootReceiver
import com.brycewg.asrkb.aidl.IKeepAliveWatchdog
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

class PrivilegedKeepAliveUserService private constructor(private val onEmpty: () -> Unit) : IKeepAliveWatchdog.Stub() {
    constructor() : this({})

    companion object {
        internal fun forRoot(onEmpty: () -> Unit): PrivilegedKeepAliveUserService = PrivilegedKeepAliveUserService(onEmpty)
    }

    private class Client(val binder: IBinder, val death: IBinder.DeathRecipient)
    private val clients = mutableMapOf<Int, Client>()
    private val reports = mutableMapOf<Int, Bundle>()
    private val lastRecovery = mutableMapOf<Int, Long>()
    private val worker = Executors.newSingleThreadScheduledExecutor()

    @Synchronized
    override fun watch(client: IBinder, packageName: String, token: String): Bundle {
        val uid = Binder.getCallingUid()
        clients.remove(uid)?.let { it.binder.unlinkToDeath(it.death, 0) }
        val death = IBinder.DeathRecipient {
            // 留出 force-stop 状态写入和正常重建的时间；重连会替换客户端，取消旧恢复。
            worker.schedule({ recover(uid, client, packageName, token) }, 1, TimeUnit.SECONDS)
        }
        client.linkToDeath(death, 0)
        clients[uid] = Client(client, death)
        return reports.remove(uid) ?: Bundle()
    }

    @Synchronized
    override fun unwatch(client: IBinder?) {
        val uid = Binder.getCallingUid()
        // 迟到的解绑只能撤销自己这一代绑定；null 用于新进程清理上次留下的守护。
        if (client != null && clients[uid]?.binder != client) {
            if (clients.isEmpty()) onEmpty()
            return
        }
        clients.remove(uid)?.let { it.binder.unlinkToDeath(it.death, 0) }
        reports.remove(uid)
        lastRecovery.remove(uid)
        if (clients.isEmpty()) onEmpty()
    }

    override fun destroy() {
        check(Binder.getCallingUid() == Process.myUid() || Binder.getCallingUid() == 0 || Binder.getCallingUid() == 2000)
        close()
        exitProcess(0)
    }

    @Synchronized
    internal fun isIdle(): Boolean = clients.isEmpty()

    @Synchronized
    internal fun close() {
        clients.values.forEach { it.binder.unlinkToDeath(it.death, 0) }
        clients.clear()
        worker.shutdownNow()
    }

    @Synchronized
    private fun recover(uid: Int, client: IBinder, packageName: String, token: String) {
        if (clients[uid]?.binder !== client) return
        val now = SystemClock.elapsedRealtime()
        // 应用启动即崩溃时，不让独立守护把崩溃变成无限拉起。
        val previous = lastRecovery[uid]
        if (previous != null && now - previous < 30_000L) {
            report(uid, "crash_loop_suppressed")
            return
        }
        val userId = uid / 100000
        try {
            val (queryExit, packageState) = execute("dumpsys", "package", packageName)
            val userLine = packageState.lineSequence().firstOrNull { it.trimStart().startsWith("User $userId:") }
            if (queryExit != 0 || userLine == null) {
                report(uid, "package_state_unavailable", queryExit)
                return
            }
            if (!userLine.contains("stopped=false")) {
                report(uid, "package_stopped")
                return
            }
            lastRecovery[uid] = now
            val (exit, _) = execute(
                "am", "broadcast", "--user", userId.toString(),
                "-n", "$packageName/${BootReceiver::class.java.name}",
                "-a", BootReceiver.ACTION_RESTORE,
                "--es", FloatingKeepAliveService.EXTRA_CALLER_TOKEN, token
            )
            report(uid, "recovery_attempt", exit)
        } catch (e: IOException) {
            report(uid, "command_io_error")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun report(uid: Int, event: String, exit: Int? = null) {
        reports[uid] = Bundle().apply {
            putString("event", event)
            if (exit != null) putInt("exit", exit)
        }
    }

    private fun execute(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        return try {
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor() to output
        } finally {
            process.destroy()
        }
    }
}
