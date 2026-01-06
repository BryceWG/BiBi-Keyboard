package com.brycewg.asrkb

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrAccessibilityService
import com.brycewg.asrkb.ui.floating.FloatingAsrService
import com.brycewg.asrkb.ui.floating.FloatingKeepAliveService

/**
 * 开机自启广播接收器：
 * - 按偏好启动悬浮服务（前提：已授予悬浮窗权限）
 * - 若具备 WRITE_SECURE_SETTINGS（ADB/系统应用），尝试自动开启无障碍服务
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val handler = Handler(Looper.getMainLooper())
        // 略微延迟，等系统服务就绪
        handler.postDelayed({
            tryStartOverlayServices(context)
            tryEnableAccessibilityIfPermitted(context)
        }, 2500L)
    }

    private fun tryStartOverlayServices(context: Context) {
        try {
            val prefs = Prefs(context)
            if (prefs.floatingKeepAliveEnabled) {
                FloatingKeepAliveService.start(context)
            }

            val canOverlay = Settings.canDrawOverlays(context)
            if (canOverlay && prefs.floatingAsrEnabled) {
                val i2 = Intent(context, FloatingAsrService::class.java).apply {
                    action = FloatingAsrService.ACTION_SHOW
                }
                context.startService(i2)
            }
        } catch (t: Throwable) {
            Log.w("BootReceiver", "Failed to start overlay services on boot", t)
        }
    }

    private fun tryEnableAccessibilityIfPermitted(context: Context) {
        // 仅当应用实际拥有 WRITE_SECURE_SETTINGS 时才尝试；普通应用默认无此权限
        val granted = context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        try {
            val resolver = context.contentResolver
            val component = ComponentName(context, AsrAccessibilityService::class.java).flattenToString()
            val keyEnabled = Settings.Secure.ACCESSIBILITY_ENABLED
            val keyServices = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES

            val current = Settings.Secure.getString(resolver, keyServices) ?: ""
            val parts = current.split(":").filter { it.isNotBlank() }.toMutableSet()
            if (!parts.contains(component)) parts.add(component)
            val newValue = parts.joinToString(":")

            val ok1 = Settings.Secure.putString(resolver, keyServices, newValue)
            val ok2 = Settings.Secure.putInt(resolver, keyEnabled, 1)
            Log.d("BootReceiver", "tryEnableAccessibilityIfPermitted: servicesUpdated=$ok1, enabledSet=$ok2")
        } catch (t: Throwable) {
            Log.e("BootReceiver", "Failed to enable accessibility via secure settings", t)
        }
    }
}
