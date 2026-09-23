/**
 * 输入法桥接显隐提示入口：接收 Hook 模块从第三方输入法进程发来的 IME 面板状态。
 *
 * 归属模块：ui/floating
 */
package com.brycewg.asrkb.ui.floating

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.brycewg.asrkb.imebridge.ImeBridgeContract
import com.brycewg.asrkb.store.Prefs

class FloatingImeBridgeHintReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: return
        if (intent?.action != ImeBridgeContract.ACTION_IME_WINDOW_VISIBILITY_CHANGED) return
        if (intent.getIntExtra(ImeBridgeContract.EXTRA_PROTOCOL_VERSION, 0) !=
            ImeBridgeContract.PROTOCOL_VERSION
        ) {
            return
        }

        val prefs = try {
            Prefs(appContext)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read prefs for bridge IME hint", t)
            return
        }

        val shouldForwardFloating = try {
            prefs.floatingImeBridgeEnabled &&
                (
                    prefs.floatingAsrEnabled ||
                        prefs.volumeKeyRecordingEnabled ||
                        prefs.shakeRecordingEnabled
                    )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to check bridge IME hint prefs", t)
            false
        }
        // 剪贴板自动同步由 Binder 激活；此处仅保留悬浮球相关转发，不再依赖悬浮球开关才能收到 hint。
        if (!shouldForwardFloating) return

        val serviceIntent = Intent(appContext, FloatingAsrService::class.java).apply {
            action = ImeBridgeContract.ACTION_IME_WINDOW_VISIBILITY_CHANGED
            putExtras(intent)
        }
        try {
            appContext.startService(serviceIntent)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to forward bridge IME hint to floating service", t)
        }
    }

    private companion object {
        const val TAG = "FloatingImeBridgeHint"
    }
}
