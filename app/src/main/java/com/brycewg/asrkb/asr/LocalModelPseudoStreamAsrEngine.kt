package com.brycewg.asrkb.asr

import android.content.Context
import android.media.AudioFormat
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 本地模型伪流式基础引擎：
 * - 统一封装麦克风采集、定时分片（预览用）与可选静音判停；
 * - 子类通过 onSegmentBoundary / onSessionFinished 实现片段预览与整段识别。
 */
abstract class LocalModelPseudoStreamAsrEngine(
    protected val context: Context,
    protected val scope: CoroutineScope,
    protected val prefs: Prefs,
    protected val listener: StreamingAsrEngine.Listener,
    protected val onRequestDuration: ((Long) -> Unit)? = null
) : StreamingAsrEngine,
    AudioFrameSinkOwner {

    companion object {
        private const val TAG = "LocalPseudoStreamEngine"
        private const val PREVIEW_SEGMENT_MS = 800
    }

    protected open val sampleRate: Int = 16000
    protected open val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO
    protected open val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
    protected open val chunkMillis: Int = 200

    private val running = AtomicBoolean(false)
    override var audioFrameSink: AudioFrameSink? = null
    private var audioJob: Job? = null
    private var finalRecognitionJob: Job? = null

    private val sessionIdGenerator = AtomicLong(0L)

    @Volatile
    private var activeSessionId: Long = 0L

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
     * 当到达定时分段边界形成一个可识别片段时回调。
     * 子类可在内部启动后台协程进行识别并通过 listener.onPartial 输出预览。
     */
    protected abstract fun onSegmentBoundary(sessionId: Long, pcmSegment: ByteArray)

    /**
     * 会话结束后回调整段 PCM 音频。
     * 子类负责在内部进行识别，并通过 listener.onFinal / listener.onError 输出最终结果。
     */
    protected abstract suspend fun onSessionFinished(sessionId: Long, fullPcm: ByteArray)

    /**
     * 新会话开始时回调，用于清理上一会话的预览缓存等会话态数据。
     * 必须为非阻塞实现。
     */
    protected open fun onSessionStart(sessionId: Long) { /* default no-op */ }

    override fun start() {
        if (running.get()) return
        if (!ensureReady()) return

        val previousFinalJob = finalRecognitionJob
        finalRecognitionJob = null
        if (previousFinalJob != null) {
            try {
                previousFinalJob.cancel()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to cancel previous final recognition job", t)
            }
        }

        val sessionId = sessionIdGenerator.incrementAndGet()
        activeSessionId = sessionId
        try {
            onSessionStart(sessionId)
        } catch (t: Throwable) {
            Log.e(TAG, "onSessionStart failed", t)
        }

        running.set(true)
        stoppedDelivered = false

        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val probabilityBuffer = VadProbabilityStream(context, sampleRate)
            val needsProbabilities = RecordedAudioVoiceFilter.isEnabled(prefs)
            val sessionBuffer = ByteArrayOutputStream()
            val segmentBuffer = ByteArrayOutputStream()
            var hasRecordedAudio = false
            var stopVadDetector: VadDetector? = null
            var segmentElapsedMs = 0L
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
                    chunkMillis = chunkMillis,
                    audioFrameSinkProvider = { audioFrameSink }
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
                val maxDurationLimiter = RecordingDurationLimiter.fromPrefs(
                    prefs = prefs,
                    sampleRate = sampleRate
                )
                val vadInputLeveler = VadInputLevelerBranch(sampleRate = sampleRate)

                audioManager.startCapture().collect { audioChunk ->
                    if (!running.get()) return@collect

                    val probabilities = if (needsProbabilities) probabilityBuffer.append(audioChunk) else null
                    val leveled = vadInputLeveler.process(audioChunk)

                    // 振幅回调（波形）
                    try {
                        listener.onAmplitude(leveled.stableAmplitude)
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
                            try {
                                onSegmentBoundary(sessionId, segBytes)
                            } catch (t: Throwable) {
                                Log.e(TAG, "onSegmentBoundary failed", t)
                            }
                        }
                        segmentBuffer.reset()
                        segmentElapsedMs = 0L
                    }

                    if (maxDurationLimiter.acceptPcm(audioChunk.size)) {
                        Log.d(TAG, "Max recording duration reached, stopping pseudo stream")
                        stop()
                        return@collect
                    }

                    // 停录 VAD：启用静音自动停止时持续喂入，避免分段缓冲导致判停失效
                    val stopVad = stopVadDetector
                    if (autoStopEnabled && stopVad != null && audioChunk.isNotEmpty()) {
                        val shouldStop = try {
                            stopVad.accept(checkNotNull(probabilities))
                        } catch (t: Throwable) {
                            Log.e(TAG, "Stop VAD shouldStop failed", t)
                            false
                        }
                        if (shouldStop) {
                            Log.d(
                                TAG,
                                "Silence after last segment with auto-stop enabled, stopping session"
                            )
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
                        listener.onError(
                            context.getString(
                                R.string.error_audio_error,
                                t.message ?: ""
                            )
                        )
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to notify audio error", e)
                    }
                }
            } finally {
                try {
                    stopVadDetector?.release()
                    probabilityBuffer.release()
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

                if (hasRecordedAudio && sessionId == activeSessionId) {
                    val fullPcm = sessionBuffer.toByteArray()
                    val processed = RecordedAudioVoiceFilter.processIfEnabled(
                        context = context,
                        prefs = prefs,
                        pcm = fullPcm,
                        sampleRate = sampleRate,
                        chunkMillis = chunkMillis,
                        probabilities = if (needsProbabilities) probabilityBuffer.snapshot() else null
                    )
                    if (processed.droppedAsEmptyAudio) {
                        try {
                            listener.onError(context.getString(R.string.error_audio_empty_skipped))
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to notify empty audio", t)
                        }
                        running.set(false)
                        return@launch
                    }
                    val denoised = OfflineSpeechDenoiserManager.denoiseIfEnabled(
                        context = context,
                        prefs = prefs,
                        pcm = processed.pcm,
                        sampleRate = sampleRate
                    )
                    // stop() 会 cancel 录音协程。若直接在 finally 内调用 suspend 的 onSessionFinished，
                    // 其内部若使用可取消的 suspend API（mutex.withLock / ensureActive 等）会被 CancellationException 中断，
                    // 导致最终结果无法覆盖预览结果。
                    finalRecognitionJob = scope.launch(Dispatchers.IO) {
                        try {
                            onSessionFinished(sessionId, denoised)
                        } catch (t: CancellationException) {
                            Log.d(TAG, "onSessionFinished cancelled: ${t.message}")
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
