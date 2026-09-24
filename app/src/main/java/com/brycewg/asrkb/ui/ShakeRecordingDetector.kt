package com.brycewg.asrkb.ui

import android.hardware.SensorManager
import android.os.SystemClock
import com.brycewg.asrkb.store.Prefs
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 摇一摇手势检测：低通估计重力后只看线性加速度，要求短时间窗内出现方向反转。
 * 参数完全来自 [Prefs.ShakeRecordingSensitivity]，切换档位后下一次采样即生效。
 */
internal class ShakeRecordingDetector {
    data class Trigger(
        val sensitivityId: String,
        val peakG: Float,
        val reversals: Int,
        val windowMs: Long
    )

    private var gravityX = 0f
    private var gravityY = 0f
    private var gravityZ = 0f
    private var gravitySeeded = false

    /** 各轴上次越过峰值阈值时的符号与时间：sign 为 -1 / 0 / +1 */
    private val lastPeakSign = IntArray(3)
    private val lastPeakAtMs = LongArray(3)
    private val reversalAtMs = ArrayDeque<Long>()
    private var windowPeakG = 0f
    private var lastTriggerAt = 0L
    private var activeSensitivityId: String? = null

    fun reset() {
        gravityX = 0f
        gravityY = 0f
        gravityZ = 0f
        gravitySeeded = false
        clearPeakHistory()
        reversalAtMs.clear()
        windowPeakG = 0f
        lastTriggerAt = 0L
        activeSensitivityId = null
    }

    /**
     * @return 满足当前灵敏度档位条件时返回触发信息，否则 null。
     */
    fun onAccelerometerSample(
        ax: Float,
        ay: Float,
        az: Float,
        sensitivity: Prefs.ShakeRecordingSensitivity,
        nowElapsedRealtime: Long = SystemClock.elapsedRealtime()
    ): Trigger? {
        if (activeSensitivityId != null && activeSensitivityId != sensitivity.id) {
            // 档位切换：清掉半成品反转，避免旧窗口用新阈值误触发。
            clearGestureState()
        }
        activeSensitivityId = sensitivity.id

        updateGravity(ax, ay, az)
        val lx = (ax - gravityX) / SensorManager.GRAVITY_EARTH
        val ly = (ay - gravityY) / SensorManager.GRAVITY_EARTH
        val lz = (az - gravityZ) / SensorManager.GRAVITY_EARTH
        val magG = sqrt(lx * lx + ly * ly + lz * lz)
        if (magG > windowPeakG) windowPeakG = magG

        if (nowElapsedRealtime - lastTriggerAt < sensitivity.cooldownMs) {
            return null
        }

        val peak = sensitivity.peakThresholdG
        countReversalsForSample(lx, ly, lz, peak, nowElapsedRealtime, sensitivity.windowMs)

        pruneReversals(nowElapsedRealtime, sensitivity.windowMs)
        if (reversalAtMs.size < sensitivity.minReversals) {
            return null
        }

        val trigger = Trigger(
            sensitivityId = sensitivity.id,
            peakG = windowPeakG,
            reversals = reversalAtMs.size,
            windowMs = sensitivity.windowMs
        )
        // 冷却由调用方在真正派发动作后 markTriggered；键盘未显示时不应吞掉冷却。
        clearGestureState()
        return trigger
    }

    fun markTriggered(nowElapsedRealtime: Long = SystemClock.elapsedRealtime()) {
        lastTriggerAt = nowElapsedRealtime
    }

    private fun updateGravity(ax: Float, ay: Float, az: Float) {
        if (!gravitySeeded) {
            gravityX = ax
            gravityY = ay
            gravityZ = az
            gravitySeeded = true
            return
        }
        // α 越大越稳，线性加速度更干净；对来回摇仍足够跟手。
        val alpha = 0.85f
        gravityX = alpha * gravityX + (1f - alpha) * ax
        gravityY = alpha * gravityY + (1f - alpha) * ay
        gravityZ = alpha * gravityZ + (1f - alpha) * az
    }

    /**
     * 同一次采样最多记 1 次反转：多轴同时反转时只计线性加速度绝对值最大的轴；
     * 其余轴仍更新方向记录。某轴距上次越过阈值已超过 [windowMs] 时，当作无历史方向。
     */
    private fun countReversalsForSample(
        lx: Float,
        ly: Float,
        lz: Float,
        peakG: Float,
        now: Long,
        windowMs: Long
    ) {
        var bestReversalAxis = -1
        var bestAbs = -1f
        for (axis in 0 until 3) {
            val valueG = when (axis) {
                0 -> lx
                1 -> ly
                else -> lz
            }
            val sign = when {
                valueG >= peakG -> 1
                valueG <= -peakG -> -1
                else -> 0
            }
            if (sign == 0) continue

            val prev = lastPeakSign[axis]
            val prevFresh = prev != 0 && now - lastPeakAtMs[axis] <= windowMs
            if (prevFresh && prev != sign) {
                val absG = abs(valueG)
                if (absG > bestAbs) {
                    bestAbs = absG
                    bestReversalAxis = axis
                }
            }
            lastPeakSign[axis] = sign
            lastPeakAtMs[axis] = now
        }
        if (bestReversalAxis >= 0) {
            reversalAtMs.addLast(now)
        }
    }

    private fun pruneReversals(now: Long, windowMs: Long) {
        while (reversalAtMs.isNotEmpty() && now - reversalAtMs.first() > windowMs) {
            reversalAtMs.removeFirst()
        }
        if (reversalAtMs.isEmpty()) {
            windowPeakG = 0f
        }
    }

    private fun clearPeakHistory() {
        lastPeakSign[0] = 0
        lastPeakSign[1] = 0
        lastPeakSign[2] = 0
        lastPeakAtMs[0] = 0L
        lastPeakAtMs[1] = 0L
        lastPeakAtMs[2] = 0L
    }

    private fun clearGestureState() {
        clearPeakHistory()
        reversalAtMs.clear()
        windowPeakG = 0f
    }
}
