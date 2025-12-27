package com.brycewg.asrkb.asr

import android.content.Context
import android.media.AudioFormat
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 本地模型伪流式基础引擎：
 * - 统一封装麦克风采集、定时分片（预览用）+ VAD 静音过滤 + 可选静音判停；
 * - 子类通过 onSegmentBoundary / onSessionFinished 实现片段预览与整段识别。
 */
abstract class LocalModelPseudoStreamAsrEngine(
    protected val context: Context,
    protected val scope: CoroutineScope,
    protected val prefs: Prefs,
    protected val listener: StreamingAsrEngine.Listener,
    protected val onRequestDuration: ((Long) -> Unit)? = null
) : StreamingAsrEngine {

    companion object {
        private const val TAG = "LocalPseudoStreamEngine"
        private const val PREVIEW_SEGMENT_MS = 800
    }

    protected open val sampleRate: Int = 16000
    protected open val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO
    protected open val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
    protected open val chunkMillis: Int = 200

    private val running = AtomicBoolean(false)
    private var audioJob: Job? = null

    @Volatile
    private var stoppedDelivered: Boolean = false

    override val isRunning: Boolean
        get() = running.get()

    /**
     * 子类用于检查本地模型是否就绪等前置条件。
     * 返回 false 时不启动录音。
     */
    protected open fun ensureReady(): Boolean = true

    /**
     * 当检测到“停顿”形成一个可识别片段时回调。
     * 子类可在内部启动后台协程进行识别并通过 listener.onPartial 输出预览。
     */
    protected abstract fun onSegmentBoundary(pcmSegment: ByteArray)

    /**
     * 会话结束后回调整段 PCM 音频。
     * 子类负责在内部进行识别，并通过 listener.onFinal / listener.onError 输出最终结果。
     */
    protected abstract suspend fun onSessionFinished(fullPcm: ByteArray)

    override fun start() {
        if (running.get()) return
        if (!ensureReady()) return

        running.set(true)
        stoppedDelivered = false

        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val sessionBuffer = ByteArrayOutputStream()
            val segmentBuffer = ByteArrayOutputStream()
            var hasRecordedAudio = false
            var segVadDetector: VadDetector? = null
            var stopVadDetector: VadDetector? = null
            var segmentElapsedMs = 0L
            var segmentHasSpeech = false
            val autoStopEnabled = try {
                isVadAutoStopEnabled(context, prefs)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to read auto-stop flag for pseudo stream", t)
                false
            }

            try {
                val audioManager = AudioCaptureManager(
                    context = context,
                    sampleRate = sampleRate,
                    channelConfig = channelConfig,
                    audioFormat = audioFormat,
                    chunkMillis = chunkMillis
                )

                if (!audioManager.hasPermission()) {
                    Log.w(TAG, "Missing RECORD_AUDIO permission")
                    try {
                        listener.onError(context.getString(R.string.error_record_permission_denied))
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to notify permission error", t)
                    }
                    running.set(false)
                    return@launch
                }

                val stopWindowMs = try {
                    prefs.autoStopSilenceWindowMs
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to read silence window for pseudo stream", t)
                    1200
                }.coerceIn(300, 5000)
                val segmentWindowMs = PREVIEW_SEGMENT_MS

                segVadDetector = try {
                    VadDetector(
                        context = context,
                        sampleRate = sampleRate,
                        windowMs = segmentWindowMs,
                        sensitivityLevel = prefs.autoStopSilenceSensitivity
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to create segment VAD for pseudo stream", t)
                    null
                }
                segmentHasSpeech = segVadDetector == null

                stopVadDetector = if (autoStopEnabled) {
                    try {
                        VadDetector(
                            context = context,
                            sampleRate = sampleRate,
                            windowMs = stopWindowMs,
                            sensitivityLevel = prefs.autoStopSilenceSensitivity
                        )
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to create stop VAD for pseudo stream", t)
                        null
                    }
                } else {
                    null
                }

                audioManager.startCapture().collect { audioChunk ->
                    if (!running.get()) return@collect

                    // 振幅回调（波形）
                    try {
                        val amplitude = calculateNormalizedAmplitude(audioChunk)
                        listener.onAmplitude(amplitude)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to calculate amplitude", t)
                    }

                    try {
                        if (audioChunk.isNotEmpty()) {
                            segmentBuffer.write(audioChunk)
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to buffer audio chunk", t)
                    }

                    // 分段语音检测：用于过滤无声片段
                    val segVad = segVadDetector
                    if (segVad != null && audioChunk.isNotEmpty()) {
                        val hasSpeech = try {
                            segVad.isSpeechFrame(audioChunk, audioChunk.size)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Segment VAD speech check failed", t)
                            false
                        }
                        if (hasSpeech) segmentHasSpeech = true
                    } else if (segVad == null) {
                        segmentHasSpeech = true
                    }

                    // 定时分段：固定间隔触发预览
                    val frameMs = if (audioChunk.isNotEmpty() && sampleRate > 0) {
                        ((audioChunk.size / 2) * 1000L) / sampleRate
                    } else {
                        0L
                    }
                    if (frameMs > 0L) {
                        segmentElapsedMs += frameMs
                    }
                    if (segmentElapsedMs >= segmentWindowMs && segmentBuffer.size() > 0) {
                        val segBytes = try {
                            segmentBuffer.toByteArray()
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to toByteArray for segment", t)
                            null
                        }
                        if (segBytes != null) {
                            try {
                                sessionBuffer.write(segBytes)
                                hasRecordedAudio = true
                            } catch (t: Throwable) {
                                Log.e(TAG, "Failed to append segment to session buffer", t)
                            }
                            if (segmentHasSpeech) {
                                try {
                                    onSegmentBoundary(segBytes)
                                } catch (t: Throwable) {
                                    Log.e(TAG, "onSegmentBoundary failed", t)
                                }
                            }
                        }
                        segmentBuffer.reset()
                        segmentElapsedMs = 0L
                        segmentHasSpeech = segVadDetector == null
                        try {
                            segVadDetector?.reset()
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to reset segment VAD detector", t)
                        }
                    }

                    // 停录 VAD：启用静音自动停止时持续喂入，避免分段缓冲导致判停失效
                    val stopVad = stopVadDetector
                    if (autoStopEnabled && stopVad != null && audioChunk.isNotEmpty()) {
                        val shouldStop = try {
                            stopVad.shouldStop(audioChunk, audioChunk.size)
                        } catch (t: Throwable) {
                            Log.e(TAG, "Stop VAD shouldStop failed", t)
                            false
                        }
                        if (shouldStop) {
                            Log.d(TAG, "Silence after last segment with auto-stop enabled, stopping session")
                            stop()
                            return@collect
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    Log.d(TAG, "Audio capture cancelled: ${t.message}")
                } else {
                    Log.e(TAG, "Audio capture failed", t)
                    try {
                        listener.onError(context.getString(R.string.error_audio_error, t.message ?: ""))
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to notify audio error", e)
                    }
                }
            } finally {
                try {
                    segVadDetector?.release()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to release segment VAD detector", t)
                }
                try {
                    stopVadDetector?.release()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to release stop VAD detector", t)
                }

                // 统一发出 onStopped，确保上层 UI 与音频焦点正常回收
                if (!stoppedDelivered) {
                    try {
                        listener.onStopped()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to notify onStopped", t)
                    } finally {
                        stoppedDelivered = true
                    }
                }

                if (segmentBuffer.size() > 0) {
                    val segBytes = try {
                        segmentBuffer.toByteArray()
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to toByteArray for tail segment", t)
                        null
                    }
                    if (segBytes != null) {
                        try {
                            sessionBuffer.write(segBytes)
                            hasRecordedAudio = true
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to append tail segment to session buffer", t)
                        }
                    }
                    segmentBuffer.reset()
                }

                if (hasRecordedAudio) {
                    val fullPcm = sessionBuffer.toByteArray()
                    val denoised = OfflineSpeechDenoiserManager.denoiseIfEnabled(
                        context = context,
                        prefs = prefs,
                        pcm = fullPcm,
                        sampleRate = sampleRate
                    )
                    try {
                        onSessionFinished(denoised)
                    } catch (t: Throwable) {
                        Log.e(TAG, "onSessionFinished failed", t)
                        try {
                            listener.onError(
                                context.getString(
                                    R.string.error_recognize_failed_with_reason,
                                    t.message ?: ""
                                )
                            )
                        } catch (e: Throwable) {
                            Log.e(TAG, "Failed to notify final recognition error", e)
                        }
                    }
                }

                running.set(false)
            }
        }
    }

    override fun stop() {
        val wasRunning = running.getAndSet(false)
        if (!wasRunning) return
        val job = audioJob
        audioJob = null
        try {
            job?.cancel()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to cancel audio job", t)
        }
    }
}
