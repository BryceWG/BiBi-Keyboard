// Captures one recognition session as fixed-format PCM without affecting ASR delivery.
// 优先晋升绑定引擎的段文件，同时保留统一帧缓冲作为流式引擎和文件晋升失败时的兜底。
package com.brycewg.asrkb.store

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.asr.AudioFrameSink
import com.brycewg.asrkb.asr.SessionAudioSourceOwner
import java.io.ByteArrayOutputStream

class AsrHistoryAudioCapture private constructor(
    context: Context,
    private val recordId: String,
    enabled: Boolean
) : AudioFrameSink {
    companion object {
        private const val TAG = "AsrHistoryAudioCapture"
        private const val MAX_BYTES = 64 * 1024 * 1024

        fun create(context: Context, prefs: Prefs, recordId: String): AsrHistoryAudioCapture {
            val enabled = !prefs.disableAsrHistory && prefs.audioHistoryRetentionCount > 0
            return AsrHistoryAudioCapture(context.applicationContext, recordId, enabled)
        }
    }

    private val appContext = context.applicationContext
    private val buffer = ByteArrayOutputStream()

    // 优先晋升引擎的段文件；缓冲区作为主引擎失败、段文件被清理时的兜底。
    @Volatile private var boundOwner: SessionAudioSourceOwner? = null

    @Volatile private var valid = enabled

    @Volatile private var closed = false

    private var capturedBytes = 0L

    /** 绑定支持段级落盘的引擎；传 null 回退帧缓冲模式（流式引擎）。 */
    fun bind(owner: SessionAudioSourceOwner?) {
        boundOwner = owner
    }

    @Synchronized
    override fun onAudioFrame(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!valid || closed || pcm.isEmpty()) return
        val normalized = normalizePcm16(pcm, sampleRate, channels)
        if (normalized == null || buffer.size() + normalized.size > MAX_BYTES) {
            valid = false
            buffer.reset()
            return
        }
        try {
            buffer.write(normalized)
            capturedBytes += normalized.size
        } catch (e: Exception) {
            Log.w(TAG, "Audio archive buffer failed", e)
            valid = false
            buffer.reset()
        }
    }

    @Synchronized
    fun complete(): Boolean {
        closeCapture()
        boundOwner?.sessionAudioStore?.let { store ->
            if (store.promoteTo(recordId)) {
                buffer.reset()
                return true
            }
        }
        if (!valid || buffer.size() == 0) return false
        val data = buffer.toByteArray()
        buffer.reset()
        AsrHistoryAudioStore.saveAsync(appContext, recordId, data)
        return true
    }

    /** 返回已捕获的 16 kHz/mono PCM 时长，供录音统计使用。 */
    @Synchronized
    fun audioDurationMs(): Long = capturedBytes * 1_000L / (16_000L * 2L)

    @Synchronized
    fun discard() {
        closeCapture()
        valid = false
        buffer.reset()
        boundOwner?.sessionAudioStore?.closeAndDelete()
        AsrHistoryAudioStore(appContext).delete(recordId)
    }

    private fun closeCapture() {
        if (closed) return
        closed = true
    }

    private fun normalizePcm16(pcm: ByteArray, sampleRate: Int, channels: Int): ByteArray? {
        if (sampleRate !in 8_000..192_000 || channels !in 1..8 || pcm.size < channels * 2) return null
        if (sampleRate == 16_000 && channels == 1) return pcm
        val frameCount = pcm.size / (channels * 2)
        if (frameCount <= 0) return null
        val mono = ShortArray(frameCount)
        for (frame in 0 until frameCount) {
            var sum = 0
            for (channel in 0 until channels) {
                val offset = (frame * channels + channel) * 2
                val sample = (pcm[offset].toInt() and 0xFF) or (pcm[offset + 1].toInt() shl 8)
                sum += sample.toShort().toInt()
            }
            mono[frame] = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        val outputFrames = ((frameCount.toLong() * 16_000L) / sampleRate)
            .coerceAtLeast(1L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val output = ByteArray(outputFrames * 2)
        for (index in 0 until outputFrames) {
            val sourcePosition = if (outputFrames == 1 || frameCount == 1) {
                0.0
            } else {
                index.toDouble() * (frameCount - 1).toDouble() / (outputFrames - 1).toDouble()
            }
            val lower = sourcePosition.toInt().coerceIn(0, frameCount - 1)
            val upper = (lower + 1).coerceAtMost(frameCount - 1)
            val fraction = sourcePosition - lower
            val sample = (mono[lower] + (mono[upper] - mono[lower]) * fraction)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output[index * 2] = (sample and 0xFF).toByte()
            output[index * 2 + 1] = (sample shr 8).toByte()
        }
        return output
    }
}
