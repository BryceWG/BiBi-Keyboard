// 渐进分段识别的段级重试调度：失败段延迟后重新入队，录音结束时冲刷。
// 归属模块：asr
package com.brycewg.asrkb.asr

import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ProgressiveSegmentRetryScheduler(
    private val scope: CoroutineScope,
    private val onRetryDue: (segmentIndex: Int) -> Unit,
    private val retryDelayMs: Long = SEGMENT_RETRY_DELAY_MS
) {
    private val lock = Any()
    private val pending = LinkedHashMap<Int, Job>()
    private var closed = false

    fun scheduleRetry(segmentIndex: Int) {
        synchronized(lock) {
            if (closed || pending.containsKey(segmentIndex)) return
            pending[segmentIndex] = scope.launch(Dispatchers.IO) {
                delay(retryDelayMs)
                // 先从 pending 移除再回调，保证与 flushNow 的直接回调不重复触发。
                if (!takeOwnership(segmentIndex)) return@launch
                onRetryDue(segmentIndex)
            }
        }
    }

    fun pendingRetryCount(): Int = synchronized(lock) { pending.size }

    /** 录音结束时立即冲刷：取消剩余延迟并同步回调，重试段随后按队列顺序处理。 */
    fun flushNow() {
        val due: List<Int>
        synchronized(lock) {
            if (closed) return
            due = pending.keys.toList()
            due.forEach { index -> pending.remove(index)?.cancel() }
        }
        due.forEach(onRetryDue)
    }

    fun cancel() {
        synchronized(lock) {
            closed = true
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
    }

    private fun takeOwnership(segmentIndex: Int): Boolean = synchronized(lock) {
        pending.remove(segmentIndex) != null && !closed
    }
}

/** 段级重试间隔：需大于常见网络抖动时长，且远小于相邻段间隔（45–60s）。 */
internal const val SEGMENT_RETRY_DELAY_MS = 10_000L
