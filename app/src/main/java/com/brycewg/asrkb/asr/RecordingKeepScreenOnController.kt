/**
 * 录音期间保持屏幕常亮。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.brycewg.asrkb.store.debug.DebugLogManager

/**
 * 通过调用方提供的窗口/视图应用 [android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]。
 * 窗口操作切回主线程；释放时即使视图已拆除也视为成功。
 */
internal class RecordingKeepScreenOnController(
    private val surface: String,
    private val apply: (Boolean) -> Boolean
) {
    companion object {
        private const val TAG = "RecordingKeepScreenOn"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var desired = false
    private var applyGeneration = 0L

    fun acquire() {
        val generation = synchronized(lock) {
            desired = true
            applyGeneration += 1L
            applyGeneration
        }
        dispatch(generation, logAcquire = true)
    }

    fun release() {
        val generation = synchronized(lock) {
            desired = false
            applyGeneration += 1L
            applyGeneration
        }
        dispatch(generation, logAcquire = false)
    }

    private fun dispatch(generation: Long, logAcquire: Boolean) {
        val task = Runnable {
            val enabled = synchronized(lock) {
                if (generation != applyGeneration) return@Runnable
                desired
            }
            val ok = try {
                apply(enabled)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to apply keepScreenOn=$enabled surface=$surface", t)
                DebugLogManager.log(
                    category = "asr",
                    event = "keep_screen_on_apply_failed",
                    data = mapOf(
                        "enabled" to enabled,
                        "surface" to surface,
                        "error" to t.javaClass.simpleName
                    )
                )
                false
            }
            if (enabled && !ok) {
                DebugLogManager.log(
                    category = "asr",
                    event = "keep_screen_on_unavailable",
                    data = mapOf("surface" to surface)
                )
            } else if (enabled && ok && logAcquire) {
                DebugLogManager.log(
                    category = "asr",
                    event = "keep_screen_on",
                    data = mapOf("surface" to surface)
                )
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
        } else {
            mainHandler.post(task)
        }
    }
}
