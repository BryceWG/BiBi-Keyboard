/**
 * 悬浮球前台保活服务：通过常驻通知维持进程优先级。
 *
 * 归属模块：ui/floating
 */
package com.brycewg.asrkb.ui.floating

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.AsrVendorUi
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FloatingKeepAliveService : Service() {

    @Volatile
    private var keepAliveStarted = false
    private val notificationStateLock = Any()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notificationRefreshJob: Job? = null

    companion object {
        private const val TAG = "FloatingKeepAliveSvc"
        private const val CHANNEL_ID = "floating_keep_alive"
        private const val NOTIFICATION_ID = 4101
        private const val NOTIFICATION_REFRESH_INTERVAL_MS = 60_000L
        private const val TOKEN_PREFS = "floating_keep_alive_auth"
        private const val TOKEN_KEY = "caller_token"
        const val EXTRA_CALLER_TOKEN = "com.brycewg.asrkb.extra.FLOATING_KEEP_ALIVE_TOKEN"

        const val ACTION_START = "com.brycewg.asrkb.action.FLOATING_KEEP_ALIVE_START"
        const val ACTION_STOP = "com.brycewg.asrkb.action.FLOATING_KEEP_ALIVE_STOP"

        @Synchronized
        internal fun getOrCreateCallerToken(context: Context): String {
            val prefs = context.applicationContext.getSharedPreferences(
                TOKEN_PREFS,
                Context.MODE_PRIVATE
            )
            val existing = prefs.getString(TOKEN_KEY, null)
            if (!existing.isNullOrBlank()) return existing
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(TOKEN_KEY, generated).apply()
            return generated
        }

        fun start(context: Context) {
            val intent = Intent(context, FloatingKeepAliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CALLER_TOKEN, getOrCreateCallerToken(context))
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FloatingKeepAliveService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_CALLER_TOKEN, getOrCreateCallerToken(context))
            }
            context.startService(intent)
        }
    }

    private lateinit var notificationManager: NotificationManager

    override fun attachBaseContext(newBase: Context?) {
        val wrapped = newBase?.let { LocaleHelper.wrap(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && !isTrustedCaller(intent)) {
            Log.w(TAG, "Ignore unauthorized keep-alive request")
            DebugLogManager.logPersistent(
                this,
                "keepalive",
                "service_reject",
                mapOf(
                    "reason" to "token_mismatch"
                )
            )
            if (!keepAliveStarted) {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                DebugLogManager.logPersistent(this, "keepalive", "service_stop")
                keepAliveStarted = false
                stopNotificationRefreshLoop()
                clearNotificationSafely()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                // 系统重启后可能以空 intent 触发重建：开关关闭时立即退出，避免误保活造成耗电。
                val prefs = try {
                    Prefs(this)
                } catch (_: Throwable) {
                    null
                }
                if (prefs != null && !prefs.floatingKeepAliveEnabled) {
                    DebugLogManager.logPersistent(
                        this,
                        "keepalive",
                        "service_skip",
                        mapOf(
                            "reason" to "pref_disabled"
                        )
                    )
                    stopForegroundSafely()
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (keepAliveStarted && intent?.action == ACTION_START) {
                    updateNotification()
                    startNotificationRefreshLoop()
                    DebugLogManager.logPersistent(
                        this,
                        "keepalive",
                        "service_skip",
                        mapOf(
                            "reason" to "already_started",
                            "click_route" to notificationClickRouteId(prefs)
                        )
                    )
                    return START_STICKY
                }
                keepAliveStarted = true
                startForegroundWithNotification()
                DebugLogManager.logPersistent(
                    this,
                    "keepalive",
                    "service_start",
                    mapOf(
                        "action" to (intent?.action ?: "null"),
                        "click_route" to notificationClickRouteId(prefs)
                    )
                )
                return START_STICKY
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (keepAliveStarted) updateNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground keep-alive timeout: startId=$startId, fgsType=$fgsType")
        DebugLogManager.logPersistent(
            this,
            "keepalive",
            "service_timeout",
            mapOf(
                "startId" to startId,
                "fgsType" to fgsType
            )
        )
        keepAliveStarted = false
        stopNotificationRefreshLoop()
        clearNotificationSafely()
        stopSelf(startId)
    }

    override fun onDestroy() {
        keepAliveStarted = false
        stopNotificationRefreshLoop()
        serviceScope.cancel()
        clearNotificationSafely()
        super.onDestroy()
    }

    private fun isTrustedCaller(intent: Intent): Boolean {
        val expected = getOrCreateCallerToken(this)
        val provided = intent.getStringExtra(EXTRA_CALLER_TOKEN)
        return !provided.isNullOrBlank() && provided == expected
    }

    private fun notificationClickRouteId(prefs: Prefs?): String = try {
        KeepAliveNotificationClick.routeFromPrefs(prefs ?: Prefs(this)).id
    } catch (_: Throwable) {
        KeepAliveNotificationClick.defaultRoute.id
    }

    private fun startForegroundWithNotification() {
        val notification = buildKeepAliveNotification()
        try {
            synchronized(notificationStateLock) {
                if (!keepAliveStarted) return
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: RuntimeException) {
            if (!isForegroundServiceStartRejected(t)) throw t
            Log.w(TAG, "Foreground keep-alive start rejected by system", t)
            DebugLogManager.logPersistent(
                this,
                "keepalive",
                "service_start_rejected",
                mapOf(
                    "reason" to "foreground_service_restricted",
                    "msg" to t.message.safeLogSnippet()
                )
            )
            keepAliveStarted = false
            stopNotificationRefreshLoop()
            stopSelf()
            return
        }
        startNotificationRefreshLoop()
    }

    private fun localizedContext(): Context = LocaleHelper.wrap(this)

    private fun buildKeepAliveNotification(): android.app.Notification {
        val localized = localizedContext()
        ensureChannel(localized)
        val openIntent = KeepAliveNotificationClick.openSettingsIntent(this)
        val pendingIntent = PendingIntent.getActivity(
            this,
            KeepAliveNotificationClick.SETTINGS_PENDING_INTENT_REQUEST_CODE,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = buildKeepAliveStatusText(localized)
        return NotificationCompat.Builder(localized, CHANNEL_ID)
            .setContentTitle(
                localized.getString(
                    R.string.notif_floating_keep_alive_title,
                    localized.getString(R.string.app_name)
                )
            )
            .setContentText(statusText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(statusText))
            .setSmallIcon(R.drawable.microphone)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun ensureChannel(localized: Context = localizedContext()) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.notif_channel_floating_keep_alive),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = localized.getString(R.string.notif_channel_floating_keep_alive_desc)
        try {
            notificationManager.createNotificationChannel(channel)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to create keep-alive channel", e)
        }
    }

    private fun stopForegroundSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to stop foreground", e)
        }
    }

    private fun startNotificationRefreshLoop() {
        if (notificationRefreshJob?.isActive == true) return
        notificationRefreshJob = serviceScope.launch {
            while (isActive) {
                delay(NOTIFICATION_REFRESH_INTERVAL_MS)
                if (!keepAliveStarted) continue
                updateNotification()
            }
        }
    }

    private fun stopNotificationRefreshLoop() {
        notificationRefreshJob?.cancel()
        notificationRefreshJob = null
    }

    private fun updateNotification() {
        val notification = buildKeepAliveNotification()
        try {
            synchronized(notificationStateLock) {
                if (!keepAliveStarted) return
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to refresh keep-alive notification", t)
        }
    }

    private fun clearNotificationSafely() {
        synchronized(notificationStateLock) {
            stopForegroundSafely()
            try {
                notificationManager.cancel(NOTIFICATION_ID)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to cancel keep-alive notification", t)
            }
        }
    }

    private fun buildKeepAliveStatusText(localized: Context): String {
        val prefs = try {
            Prefs(this)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read prefs for keep-alive notification", t)
            return localized.getString(
                R.string.notif_floating_keep_alive_desc,
                0L,
                localized.getString(R.string.vendor_volc),
                localized.getString(R.string.notif_floating_keep_alive_postproc_disabled)
            )
        }
        val todayChars = getTodayRecognizedChars(prefs)
        val vendorName = try {
            AsrVendorUi.name(localized, prefs.asrVendor)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resolve keep-alive vendor name", t)
            prefs.asrVendor.id
        }
        val postProcessStatus = if (prefs.postProcessEnabled && prefs.hasLlmKeys()) {
            localized.getString(R.string.notif_floating_keep_alive_postproc_enabled)
        } else {
            localized.getString(R.string.notif_floating_keep_alive_postproc_disabled)
        }
        return localized.getString(
            R.string.notif_floating_keep_alive_desc,
            todayChars,
            vendorName,
            postProcessStatus
        )
    }

    private fun getTodayRecognizedChars(prefs: Prefs): Long = try {
        val todayKey = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
        prefs.getUsageStats().daily[todayKey]?.chars?.coerceAtLeast(0L) ?: 0L
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to load today's usage stats", t)
        0L
    }

    private fun isForegroundServiceStartRejected(t: RuntimeException): Boolean {
        var current: Throwable? = t
        while (current != null) {
            val className = current.javaClass.name
            val message = current.message.orEmpty()
            if (className == "android.app.ForegroundServiceStartNotAllowedException" ||
                className == "android.app.ForegroundServiceTypeException" ||
                message.contains("foreground service type", ignoreCase = true) ||
                message.contains("foreground service", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun String?.safeLogSnippet(): String? {
        if (this.isNullOrBlank()) return null
        return take(160)
    }
}
