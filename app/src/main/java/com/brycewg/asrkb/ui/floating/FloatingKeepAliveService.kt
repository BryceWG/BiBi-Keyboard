package com.brycewg.asrkb.ui.floating

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.floating.FloatingSettingsActivity

class FloatingKeepAliveService : Service() {

  companion object {
    private const val TAG = "FloatingKeepAliveSvc"
    private const val CHANNEL_ID = "floating_keep_alive"
    private const val NOTIFICATION_ID = 4101

    const val ACTION_START = "com.brycewg.asrkb.action.FLOATING_KEEP_ALIVE_START"
    const val ACTION_STOP = "com.brycewg.asrkb.action.FLOATING_KEEP_ALIVE_STOP"

    fun start(context: Context) {
      val intent = Intent(context, FloatingKeepAliveService::class.java).apply {
        action = ACTION_START
      }
      ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, FloatingKeepAliveService::class.java).apply {
        action = ACTION_STOP
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
    when (intent?.action) {
      ACTION_STOP -> {
        stopForegroundSafely()
        stopSelf()
        return START_NOT_STICKY
      }
      else -> {
        startForegroundWithNotification()
        return START_STICKY
      }
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onDestroy() {
    stopForegroundSafely()
    super.onDestroy()
  }

  private fun startForegroundWithNotification() {
    val openIntent = Intent(this, FloatingSettingsActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val pendingIntent = PendingIntent.getActivity(
      this,
      0,
      openIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle(getString(R.string.notif_floating_keep_alive_title))
      .setContentText(getString(R.string.notif_floating_keep_alive_desc))
      .setSmallIcon(R.drawable.microphone)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setContentIntent(pendingIntent)
      .build()

    startForeground(NOTIFICATION_ID, notification)
  }

  private fun ensureChannel() {
    val channel = NotificationChannel(
      CHANNEL_ID,
      getString(R.string.notif_channel_floating_keep_alive),
      NotificationManager.IMPORTANCE_LOW
    )
    channel.description = getString(R.string.notif_channel_floating_keep_alive_desc)
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
}
