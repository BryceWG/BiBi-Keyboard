/**
 * DashScope Recognition WebSocket 连接复用（进程内单槽缓存）。
 *
 * 对齐官方 Java SDK「对象池 borrow/return/invalidate」模式，但不引入 commons-pool2：
 * 移动端多为串行识别，单槽即可跨键盘/悬浮球/AIDL/SpeechRecognizer 引擎实例复用同一
 * Recognition（inference WebSocket）。Qwen3 realtime 不走此缓存。
 *
 * 归属模块：asr
 */
package com.brycewg.asrkb.asr

import android.util.Log
import com.alibaba.dashscope.audio.asr.recognition.Recognition
import com.brycewg.asrkb.store.debug.DebugLogManager

internal data class DashscopeRecognitionReuseKey(
    val wsUrl: String,
    val apiKey: String,
    val model: String,
    val sampleRate: Int
)

internal object DashscopeRecognitionConnectionCache {
    private const val TAG = "DashRecogConnCache"

    /** 与文档一致：任务结束后 60 秒无新任务则连接自动断开。 */
    private const val MAX_IDLE_MS = 60_000L

    private val lock = Any()
    private var idle: IdleEntry? = null

    private data class IdleEntry(
        val key: DashscopeRecognitionReuseKey,
        val recognition: Recognition,
        val returnedAtElapsedMs: Long
    )

    data class BorrowResult(
        val recognition: Recognition,
        val reused: Boolean
    )

    fun borrow(key: DashscopeRecognitionReuseKey): BorrowResult = synchronized(lock) {
        val existing = idle
        idle = null
        if (existing == null) {
            logDiag("dash_ws_reuse_new", mapOf("reason" to "empty", "model" to key.model))
            return BorrowResult(Recognition(), reused = false)
        }
        if (existing.key != key) {
            logDiag(
                "dash_ws_reuse_new",
                mapOf("reason" to "key_mismatch", "model" to key.model)
            )
            closeQuietly(existing.recognition, "key_mismatch")
            return BorrowResult(Recognition(), reused = false)
        }
        val idleMs = android.os.SystemClock.elapsedRealtime() - existing.returnedAtElapsedMs
        if (idleMs > MAX_IDLE_MS) {
            logDiag(
                "dash_ws_reuse_new",
                mapOf("reason" to "idle_expired", "idleMs" to idleMs, "model" to key.model)
            )
            closeQuietly(existing.recognition, "idle_expired")
            return BorrowResult(Recognition(), reused = false)
        }
        logDiag(
            "dash_ws_reuse_hit",
            mapOf("idleMs" to idleMs, "model" to key.model)
        )
        return BorrowResult(existing.recognition, reused = true)
    }

    /**
     * 任务成功结束后归还。不要归还未完成或失败的 Recognition。
     */
    fun returnSuccess(key: DashscopeRecognitionReuseKey, recognition: Recognition) {
        synchronized(lock) {
            val existing = idle
            idle = null
            if (existing != null) {
                closeQuietly(existing.recognition, "superseded")
            }
            idle = IdleEntry(
                key = key,
                recognition = recognition,
                returnedAtElapsedMs = android.os.SystemClock.elapsedRealtime()
            )
            logDiag("dash_ws_reuse_return", mapOf("model" to key.model))
        }
    }

    /**
     * 任务失败或配置失效：关闭底层 WebSocket 并废弃对象。
     */
    fun invalidate(
        key: DashscopeRecognitionReuseKey?,
        recognition: Recognition,
        reason: String
    ) {
        synchronized(lock) {
            val existing = idle
            if (existing != null && existing.recognition === recognition) {
                idle = null
            } else if (existing != null && key != null && existing.key == key) {
                idle = null
                closeQuietly(existing.recognition, "invalidate_stale_$reason")
            }
        }
        closeQuietly(recognition, reason)
        logDiag(
            "dash_ws_reuse_invalidate",
            mapOf("reason" to reason, "model" to (key?.model ?: ""))
        )
    }

    /** 进程或配置切换时清空空闲连接。 */
    fun clear(reason: String = "clear") {
        synchronized(lock) {
            val existing = idle
            idle = null
            if (existing != null) {
                closeQuietly(existing.recognition, reason)
                logDiag(
                    "dash_ws_reuse_invalidate",
                    mapOf("reason" to reason, "model" to existing.key.model)
                )
            }
        }
    }

    private fun closeQuietly(recognition: Recognition, reason: String) {
        try {
            recognition.getDuplexApi()?.close(1000, reason)
        } catch (t: Throwable) {
            Log.w(TAG, "duplex close failed: $reason", t)
        }
    }

    private fun logDiag(event: String, data: Map<String, Any?> = emptyMap()) {
        DebugLogManager.logBase(category = "asr", event = event, data = data)
    }
}
