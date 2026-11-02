package com.brycewg.asrkb.asr

import android.content.Context
import android.media.AudioFormat
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 推 PCM 的伪流式基础引擎：
 * - 外部（如小企鹅）通过 AIDL writePcm 持续推送 PCM；
 * - 引擎内部定时分片，子类可对片段做离线识别并回调 onPartial 预览；
 * - finishPcm/stop() 时对整段音频做一次离线识别，回调 onFinal。
 *
 * 注意：该引擎仅做“分句预览”，不做自动判停（由外部 finishPcm 决定会话结束）。
 */
abstract class PushPcmPseudoStreamAsrEngine(
    protected val context: Context,
    protected val scope: CoroutineScope,
    protected val prefs: Prefs,
    protected val listener: StreamingAsrEngine.Listener,
    protected val onRequestDuration: ((Long) -> Unit)? = null
) : StreamingAsrEngine,
    ExternalPcmConsumer,
    VadPcmConsumer {

    companion object {
        private const val TAG = "PushPcmPseudoStream"
        private const val PREVIEW_SEGMENT_MS = 800
    }

    protected open val sampleRate: Int = 16000
    protected open val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO
    protected open val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT

    private val running = AtomicBoolean(false)
    private val finalized = AtomicBoolean(false)
    private val probabilityBuffer = VadProbabilityStream(context, sampleRate)

    private val sessionBuffer = ByteArrayOutputStream()
    private val segmentBuffer = ByteArrayOutputStream()
    private var vadInputLeveler = VadInputLevelerBranch()
    private var segmentElapsedMs: Long = 0L

    override val needsVadProbabilities: Boolean
        get() = RecordedAudioVoiceFilter.isEnabled(prefs)

    override val isRunning: Boolean
        get() = running.get()

    protected open fun ensureReady(): Boolean = true

    protected abstract fun onSegmentBoundary(pcmSegment: ByteArray)

    protected abstract suspend fun onSessionFinished(fullPcm: ByteArray)

    override fun start() {
        if (running.get()) return
        if (!ensureReady()) return

        running.set(true)
        finalized.set(false)
        probabilityBuffer.reset()
        sessionBuffer.reset()
        segmentBuffer.reset()
        vadInputLeveler = VadInputLevelerBranch(sampleRate = sampleRate)
        segmentElapsedMs = 0L
    }

    override fun stop() {
        if (!running.get()) return
        running.set(false)
        try {
            listener.onStopped()
        } catch (t: Throwable) {
            Log.w(TAG, "notify onStopped failed", t)
        }
        vadInputLeveler.finishDebugSession("stop")
        finalizeOnce()
    }

    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) = appendFrame(pcm, sampleRate, channels, null)

    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int, probabilities: VadProbabilities) = appendFrame(pcm, sampleRate, channels, probabilities)

    private fun appendFrame(pcm: ByteArray, sampleRate: Int, channels: Int, probabilities: VadProbabilities?) {
        if (!running.get()) return
        if (sampleRate != this.sampleRate || channels != 1) {
            Log.w(TAG, "ignore frame: sr=$sampleRate ch=$channels")
            return
        }
        if (pcm.isEmpty()) return

        if (needsVadProbabilities || probabilities != null) probabilityBuffer.append(pcm, probabilities)
        val leveled = vadInputLeveler.process(pcm)
        try {
            listener.onAmplitude(leveled.stableAmplitude)
        } catch (t: Throwable) {
            Log.w(TAG, "amp cb failed", t)
        }

        try {
            sessionBuffer.write(pcm)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to append audio chunk to session buffer", t)
            return
        }

        try {
            segmentBuffer.write(pcm)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to buffer audio chunk", t)
            return
        }

        val frameMs = if (sampleRate > 0) {
            ((pcm.size / 2) * 1000L) / sampleRate
        } else {
            0L
        }
        if (frameMs > 0L) {
            segmentElapsedMs += frameMs
        }
        if (segmentElapsedMs >= PREVIEW_SEGMENT_MS && segmentBuffer.size() > 0) {
            val segBytes = try {
                segmentBuffer.toByteArray()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to toByteArray for segment", t)
                null
            } ?: return
            try {
                onSegmentBoundary(segBytes)
            } catch (t: Throwable) {
                Log.e(TAG, "onSegmentBoundary failed", t)
            }
            segmentBuffer.reset()
            segmentElapsedMs = 0L
        }
    }

    private fun finalizeOnce() {
        if (!finalized.compareAndSet(false, true)) return
        vadInputLeveler.finishDebugSession("finalize")
        if (segmentBuffer.size() > 0) {
            segmentBuffer.reset()
        }
        val fullPcm = try {
            sessionBuffer.toByteArray()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to dump session buffer", t)
            ByteArray(0)
        }
        sessionBuffer.reset()
        segmentBuffer.reset()

        if (fullPcm.isEmpty()) {
            try {
                listener.onError(context.getString(R.string.error_audio_empty))
            } catch (t: Throwable) {
                Log.w(TAG, "notify audio empty failed", t)
            }
            return
        }

        val probabilities = probabilityBuffer.snapshot().takeIf { it.sampleCount > 0 }
        probabilityBuffer.release()
        scope.launch(Dispatchers.IO) {
            try {
                val processed = RecordedAudioVoiceFilter.processIfEnabled(
                    context = context,
                    prefs = prefs,
                    pcm = fullPcm,
                    sampleRate = sampleRate,
                    chunkMillis = 200,
                    probabilities = probabilities
                )
                if (processed.droppedAsEmptyAudio) {
                    try {
                        listener.onError(context.getString(R.string.error_audio_empty_skipped))
                    } catch (t: Throwable) {
                        Log.w(TAG, "notify empty audio failed", t)
                    }
                    return@launch
                }
                val denoised = OfflineSpeechDenoiserManager.denoiseIfEnabled(
                    context = context,
                    prefs = prefs,
                    pcm = processed.pcm,
                    sampleRate = sampleRate
                )
                onSessionFinished(denoised)
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    Log.d(TAG, "final recognition cancelled: ${t.message}")
                } else {
                    Log.e(TAG, "Final recognition failed", t)
                    try {
                        listener.onError(
                            context.getString(
                                R.string.error_recognize_failed_with_reason,
                                t.message ?: ""
                            )
                        )
                    } catch (e: Throwable) {
                        Log.w(TAG, "notify final error failed", e)
                    }
                }
            }
        }
    }
}
