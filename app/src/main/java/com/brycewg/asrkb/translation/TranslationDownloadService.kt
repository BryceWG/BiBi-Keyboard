/** Translation module: user-started foreground model downloads, independent of settings lifetime. */
package com.brycewg.asrkb.translation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class TranslationDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            job?.cancel()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.translation_packages), NotificationManager.IMPORTANCE_LOW))
        startForeground(NOTIFICATION_ID, notification(getString(R.string.translation_downloading), 0).build())
        job = scope.launch {
            try {
                check(supportsFoxletTranslation())
                val prefs = Prefs(this@TranslationDownloadService)
                val route = TranslationModelCatalog(this@TranslationDownloadService).route(prefs.translationSourceLanguage, prefs.translationTargetLanguage)
                check(route.isNotEmpty())
                val installed = FoxletTranslationService.installed(this@TranslationDownloadService)
                val missing = route.filter { it.id !in installed }
                val total = missing.sumOf { it.bytes }
                var completed = 0L
                DebugLogManager.logBase("app", "translation_download_start", mapOf("packages" to missing.size))
                missing.forEach { model ->
                    FoxletTranslationService.download(this@TranslationDownloadService, model) { progress ->
                        val percent = if (total > 0) ((completed + progress.downloadedBytes) * 100 / total).toInt().coerceIn(0, 100) else 0
                        val old = mutableState.value
                        if (!old.running || old.percent != percent) {
                            mutableState.value = State(true, percent)
                            manager.notify(NOTIFICATION_ID, notification(getString(R.string.translation_downloading), percent).build())
                        }
                    }
                    completed += model.bytes
                }
                mutableState.value = State()
                DebugLogManager.logBase("app", "translation_download_ready")
            } catch (e: CancellationException) {
                mutableState.value = State()
                DebugLogManager.logBase("app", "translation_download_cancel")
                throw e
            } catch (e: Exception) {
                mutableState.value = State(error = getString(R.string.translation_download_failed))
                DebugLogManager.logError("app", "translation_download_error", data = mapOf("errorType" to e.javaClass.simpleName))
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun notification(text: String, percent: Int) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(getString(R.string.translation_packages))
        .setContentText(text)
        .setProgress(100, percent, false)
        .setOngoing(true)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    data class State(val running: Boolean = false, val percent: Int = 0, val error: String? = null)

    companion object {
        private const val CHANNEL = "translation_models"
        private const val NOTIFICATION_ID = 417
        private const val ACTION_CANCEL = "cancel"
        private val mutableState = MutableStateFlow(State())
        val state = mutableState.asStateFlow()

        fun start(context: Context) {
            mutableState.value = State(running = true)
            ContextCompat.startForegroundService(context, Intent(context, TranslationDownloadService::class.java))
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, TranslationDownloadService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
