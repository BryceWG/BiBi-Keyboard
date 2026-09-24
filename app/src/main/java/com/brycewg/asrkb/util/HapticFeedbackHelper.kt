package com.brycewg.asrkb.util

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import com.brycewg.asrkb.store.Prefs

object HapticFeedbackHelper {
    private const val TAG = "HapticFeedbackHelper"
    private const val DEFAULT_DURATION_MS = 20L
    private const val PULSE_DURATION_MS = 40L
    private const val PULSE_GAP_MS = 70L

    fun performTap(context: Context, prefs: Prefs, view: View? = null) {
        when (prefs.hapticFeedbackLevel) {
            Prefs.HAPTIC_FEEDBACK_LEVEL_OFF -> return
            Prefs.HAPTIC_FEEDBACK_LEVEL_SYSTEM -> {
                if (view != null) {
                    try {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        return
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed to perform view haptic feedback", e)
                    }
                }
                vibrate(context, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            else -> {
                val amplitude = amplitudeForLevel(prefs.hapticFeedbackLevel) ?: return
                vibrate(context, amplitude)
            }
        }
    }

    /**
     * 无障碍向短脉冲震动，不受按键触觉强度开关影响。
     * 用于摇一摇录音开始/停止反馈。
     */
    fun vibratePulses(
        context: Context,
        count: Int,
        pulseMs: Long = PULSE_DURATION_MS,
        gapMs: Long = PULSE_GAP_MS,
        amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE
    ) {
        if (count <= 0) return
        val vibrator = context.getSystemService(Vibrator::class.java)
        if (vibrator == null || !vibrator.hasVibrator()) return
        val pulseCount = count.coerceAtMost(6)
        val safePulseMs = pulseMs.coerceIn(10L, 200L)
        val safeGapMs = gapMs.coerceIn(20L, 400L)
        // timings: [delay0, on0, delay1, on1, ...]
        val timings = LongArray(pulseCount * 2)
        val amplitudes = IntArray(pulseCount * 2)
        val safeAmplitude = if (amplitude == VibrationEffect.DEFAULT_AMPLITUDE) {
            amplitude
        } else {
            amplitude.coerceIn(1, 255)
        }
        for (i in 0 until pulseCount) {
            timings[i * 2] = if (i == 0) 0L else safeGapMs
            timings[i * 2 + 1] = safePulseMs
            amplitudes[i * 2] = 0
            amplitudes[i * 2 + 1] = if (safeAmplitude == VibrationEffect.DEFAULT_AMPLITUDE) {
                160
            } else {
                safeAmplitude
            }
        }
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to vibrate pulses", e)
        }
    }

    private fun amplitudeForLevel(level: Int): Int? = when (level) {
        Prefs.HAPTIC_FEEDBACK_LEVEL_WEAK -> 30
        Prefs.HAPTIC_FEEDBACK_LEVEL_LIGHT -> 50
        Prefs.HAPTIC_FEEDBACK_LEVEL_MEDIUM -> 70
        Prefs.HAPTIC_FEEDBACK_LEVEL_STRONG -> 100
        Prefs.HAPTIC_FEEDBACK_LEVEL_HEAVY -> 140
        else -> null
    }

    private fun vibrate(context: Context, amplitude: Int) {
        val vibrator = context.getSystemService(Vibrator::class.java)
        if (vibrator == null || !vibrator.hasVibrator()) return
        val safeAmplitude = if (amplitude == VibrationEffect.DEFAULT_AMPLITUDE) {
            amplitude
        } else {
            amplitude.coerceIn(1, 255)
        }
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(DEFAULT_DURATION_MS, safeAmplitude))
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to vibrate", e)
        }
    }
}
