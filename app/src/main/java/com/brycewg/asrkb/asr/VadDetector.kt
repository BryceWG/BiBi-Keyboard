/** 录音判停策略；模型推理由会话级 VadProbabilityStream 承担。 */
package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.util.concurrent.atomic.AtomicInteger

class VadDetector(
    context: Context,
    sampleRate: Int,
    private val windowMs: Int,
    sensitivityLevel: Int = Prefs.DEFAULT_SILENCE_SENSITIVITY,
    private val tuning: VadTuning = VadTuning.tuningForLevel(sensitivityLevel),
    private val probabilityStream: VadProbabilityStream = VadProbabilityStream(context, sampleRate)
) {
    private val endpoint = VadEndpoint(tuning, windowMs)
    private var reportedStop = false

    fun shouldStop(buf: ByteArray, len: Int): Boolean = accept(probabilityStream.append(buf, len = len))

    internal fun accept(probabilities: VadProbabilities): Boolean {
        // 先完成当前采集块的全部推理，再允许判停；其他消费者仍需当前块的概率。
        for (frame in probabilities.frames) endpoint.accept(frame.probability, allowStop = true)
        if (endpoint.ended && !reportedStop) {
            reportedStop = true
            DebugLogManager.logBase(
                category = "asr",
                event = "vad_endpoint",
                data = mapOf(
                    "reason" to endpoint.stopReason,
                    "sensitivity" to tuning.name,
                    "silenceMs" to windowMs,
                    "firstSpeechMs" to endpoint.firstSpeechMs,
                    "lastSpeechMs" to endpoint.lastSpeechMs
                )
            )
        }
        return endpoint.ended
    }

    fun release() = probabilityStream.release()

    companion object {
        fun preload(context: Context, sampleRate: Int, sensitivityLevel: Int = Prefs.DEFAULT_SILENCE_SENSITIVITY) = VadProbabilityStream.preload(context, sampleRate, sensitivityLevel)

        fun rebuildGlobal(context: Context, sampleRate: Int, sensitivityLevel: Int) = VadProbabilityStream.rebuildGlobal(context, sampleRate, sensitivityLevel)
    }
}

/**
 * 长按说话时的自动停录抑制器：用于绕过静音判停干预。
 */
object VadAutoStopGuard {
    private val suppressCount = AtomicInteger(0)

    fun acquire(): AutoCloseable {
        suppressCount.incrementAndGet()
        return AutoCloseable { release() }
    }

    fun release() {
        val remaining = suppressCount.decrementAndGet()
        if (remaining < 0) {
            suppressCount.set(0)
        }
    }

    fun isSuppressed(): Boolean = suppressCount.get() > 0
}

fun isVadAutoStopEnabled(context: Context, prefs: Prefs): Boolean {
    return try {
        if (VadAutoStopGuard.isSuppressed()) return false
        prefs.recordingAutoStopMode == Prefs.RecordingAutoStopMode.SILENCE
    } catch (t: Throwable) {
        Log.w("VadDetector", "Failed to read prefs for VAD auto-stop", t)
        false
    }
}
