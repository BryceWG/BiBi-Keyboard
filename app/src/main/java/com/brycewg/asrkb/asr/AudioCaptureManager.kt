package com.brycewg.asrkb.asr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 音频采集管理器
 *
 * 封装 AudioRecord 的初始化、权限检查、预热和音频流读取逻辑。
 * 提供简洁的音频流接口供 ASR 引擎使用，消除重复代码。
 *
 * ## 功能特性
 * - 自动处理音频源回退（VOICE_RECOGNITION -> MIC）
 * - 预热策略：两帧探测 + 仅在确认“坏源”时回退到 MIC
 *
 * @param context Android Context
 * @param sampleRate 采样率（Hz），默认 16000
 * @param channelConfig 声道配置，默认单声道
 * @param audioFormat 音频格式，默认 PCM 16-bit
 * @param chunkMillis 每个音频块的时长（ms），默认 200ms
 */
class AudioCaptureManager(
    private val context: Context,
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val chunkMillis: Int = 200,
    private val audioFrameSinkProvider: (() -> AudioFrameSink?)? = null
) {
    private val bytesPerSample = 2 // 16bit mono PCM
    private val prefs by lazy { Prefs(context) }

    companion object {
        private const val TAG = "AudioCaptureManager"

        /**
         * 单次 AudioRecord.read() 的目标时长。
         *
         * read() 是阻塞调用且不响应协程取消，停录后必须等当前这次 read 满额返回才能退出循环，
         * 这段等待均值就是 read 粒度的一半。把 read 粒度与 emit 粒度解耦后，下游仍按
         * chunkMillis 收帧（VAD 判停窗口、降噪块长、编码队列深度、波形回调频率都不变），
         * 只有停录退出被加速。
         */
        private const val READ_SLICE_MILLIS = 40

        /**
         * 音源探测的累积时长上限。
         *
         * 探测只为判断音源是否近乎全零（需要回退到 MIC），一旦读到超过阈值的样本就立刻结束。
         * 底噪是持续的，120ms 足够判定；探测到的音频会保留下来交给下游，不再丢弃。
         */
        private const val WARMUP_PROBE_MILLIS = 120
        private const val WARMUP_MAX_READS = 2

        /**
         * 采集收尾（AudioRecord.stop/release 与音频路由恢复）与音频数据已无关系，
         * 但实测要占 40ms 上下，且串行阻塞在“停录 → 发起识别”的关键路径上，
         * 因此挪到独立作用域执行。
         *
         * 代价：AudioRecord 是独占资源，下一次采集必须先等上一次收尾结束，
         * 所以连续快速录音会退化成原来的同步行为。
         */
        private val captureCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        private var pendingCaptureCleanup: Job? = null

        private suspend fun awaitPendingCaptureCleanup() {
            val pending = pendingCaptureCleanup ?: return
            if (pending.isCompleted) return
            pending.join()
        }
    }

    /**
     * 检查录音权限
     *
     * @return 如果具有 RECORD_AUDIO 权限返回 true，否则返回 false
     */
    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED

    private fun createAudioRecord(audioSource: Int, bufferSize: Int): AudioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Api31.createAudioRecord(
            context = context,
            audioSource = audioSource,
            sampleRate = sampleRate,
            channelConfig = channelConfig,
            audioEncoding = audioFormat,
            bufferSize = bufferSize
        )
    } else {
        AudioRecord(
            audioSource,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )
    }

    private fun createRecorderForSource(source: Int, bufferSize: Int): AudioRecord? = try {
        createAudioRecord(source, bufferSize).let { recorder ->
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                recorder
            } else {
                recorder.release()
                null
            }
        }
    } catch (t: Throwable) {
        Log.e(TAG, "Failed to create AudioRecord with source=$source", t)
        null
    }

    private fun startRecorderForSource(source: Int, bufferSize: Int): AudioRecord? {
        val recorder = createRecorderForSource(source, bufferSize) ?: return null
        return try {
            recorder.startRecording()
            recorder
        } catch (t: Throwable) {
            try {
                recorder.release()
            } catch (_: Throwable) { }
            Log.w(TAG, "Failed to start AudioRecord with source=$source", t)
            null
        }
    }

    private fun deviceIdentifier(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .joinToString("|") { it.trim().lowercase() }

    @RequiresApi(Build.VERSION_CODES.S)
    private object Api31 {
        fun createAudioRecord(
            context: Context,
            audioSource: Int,
            sampleRate: Int,
            channelConfig: Int,
            audioEncoding: Int,
            bufferSize: Int
        ): AudioRecord {
            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .setEncoding(audioEncoding)
                .build()
            return AudioRecord.Builder()
                .setContext(context)
                .setAudioSource(audioSource)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .build()
        }
    }

    /**
     * 启动音频采集，返回音频数据流
     *
     * 该方法会：
     * 1. 检查录音权限
     * 2. 初始化 AudioRecord（优先 VOICE_RECOGNITION，失败时回退到 MIC）
     * 3. 执行预热逻辑（两帧小窗探测 + 仅在两帧近乎全零时回退为 MIC）
     * 4. 循环读取音频数据并通过 Flow emit
     *
     * @return Flow<ByteArray> 音频数据流，每个 ByteArray 是一个音频块（约 chunkMillis 时长）
     * @throws SecurityException 如果缺少录音权限
     * @throws IllegalStateException 如果 AudioRecord 初始化失败
     */
    fun startCapture(): Flow<ByteArray> = ContinuousCaptureCoordinator.attachActiveSessionFlow(
        sampleRate = sampleRate,
        channelConfig = channelConfig,
        audioFormat = audioFormat
    )?.onEach { audioFrameSinkProvider?.invoke()?.onAudioFrame(it, sampleRate, 1) }
        ?: startPlatformCapture().onEach {
            audioFrameSinkProvider?.invoke()?.onAudioFrame(it, sampleRate, 1)
        }

    internal fun startPlatformCapture(): Flow<ByteArray> = flow {
        // 上一次采集的收尾可能仍在后台执行；AudioRecord 独占，必须等它释放完再新建。
        awaitPendingCaptureCleanup()
        // 采集收尾诊断：最后一次 read 的阻塞时长，与 read 返回后到真正退出循环的调度开销。
        var lastReadEndedAtMs = 0L
        var lastReadMs = 0L
        try {
            DebugLogManager.log(
                category = "audio",
                event = "acm_start",
                data = mapOf(
                    "sr" to sampleRate,
                    "chunkMs" to chunkMillis
                )
            )
        } catch (_: Throwable) { }
        // 1. 权限检查
        if (!hasPermission()) {
            val error = SecurityException("Missing RECORD_AUDIO permission")
            Log.e(TAG, "Permission check failed", error)
            DebugLogManager.logError(
                context,
                "audio",
                "record_permission_missing",
                error,
                data = mapOf("stage" to "perm")
            )
            try {
                DebugLogManager.log(
                    "audio",
                    "acm_error",
                    mapOf(
                        "stage" to "perm",
                        "msg" to error.message
                    )
                )
            } catch (_: Throwable) { }
            throw error
        }

        // 2. 计算缓冲区大小与读缓冲。分配放在路由准备之前：路由激活后只剩 AudioRecord 创建
        // （内部已捕获异常），避免中途抛异常/取消时把耳机通信设备或 SCO 留在已终止的录音上。
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val chunkBytes = ((sampleRate * chunkMillis) / 1000) * bytesPerSample
        val bufferSize = maxOf(minBuffer, chunkBytes)
        // read 粒度不超过 emit 粒度；小于时循环内累积到 chunkBytes 再 emit。
        val readSliceBytes = minOf(
            chunkBytes,
            ((sampleRate * READ_SLICE_MILLIS) / 1000) * bytesPerSample
        ).coerceAtLeast(bytesPerSample)
        val buf = ByteArray(chunkBytes)
        // read 粒度小于 emit 粒度时用 pending 累积，保证下游帧长仍是 chunkMillis。
        val pending = if (readSliceBytes < chunkBytes) ByteArray(chunkBytes) else null
        var pendingSize = 0

        // 1.5. 如开启“耳机麦克风优先”，在构建 AudioRecord 之前把通信音频路由到耳机。
        // 只在录音窗口内激活，避免键盘可见时长期占用耳机麦克风、把耳机锁在通话模式。
        val headsetRoute = if (prefs.headsetMicPriorityEnabled) HeadsetAudioRoute(context) else null
        if (headsetRoute != null) {
            try {
                headsetRoute.prepare()
            } catch (t: Throwable) {
                // prepare 只在取消时抛出：终止前先释放，避免路由泄漏。
                headsetRoute.release()
                throw t
            }
            Log.i(
                TAG,
                "Headset route: activated=${headsetRoute.activated} " +
                    "device=${headsetRoute.selectedDevice?.productName} " +
                    "type=${headsetRoute.selectedDevice?.type} " +
                    "commPath=${headsetRoute.viaCommunicationPath} " +
                    "elapsed=${headsetRoute.prepareElapsedMs}ms " +
                    "failure=${headsetRoute.failureReason}"
            )
        }
        val routeActive = headsetRoute?.activated == true
        val routeUsesCommunicationPath = headsetRoute?.viaCommunicationPath == true
        val preferredInputDevice = headsetRoute?.selectedDevice

        // 3. 优先使用已验证的设备级音源；耳机通信路由生效时必须用 VOICE_COMMUNICATION 采集，
        // 只有通信路径的音源才会跟随 setCommunicationDevice/SCO 切换到耳机麦克风。
        val deviceId = deviceIdentifier()
        val primarySource = if (routeActive && routeUsesCommunicationPath) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        val useSourceCache = !routeActive && preferredInputDevice == null
        var cachedSource = if (useSourceCache) prefs.getAudioSourceCache(deviceId) else null
        var selectedSource = cachedSource ?: primarySource
        var recorder: AudioRecord? = createRecorderForSource(selectedSource, bufferSize)
        var sourceIsCached = cachedSource != null
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder?.release()
            if (sourceIsCached) {
                prefs.clearAudioSourceCache(deviceId)
                cachedSource = null
                sourceIsCached = false
                recorder = createRecorderForSource(primarySource, bufferSize)
                selectedSource = primarySource
            }
            if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "Primary audio source unavailable, falling back to MIC")
                recorder?.release()
                recorder = createRecorderForSource(MediaRecorder.AudioSource.MIC, bufferSize)
                selectedSource = MediaRecorder.AudioSource.MIC
            }
        }

        // 5. 最终校验
        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            // 路由已激活但录音无法启动：立即恢复，避免耳机被锁在通话模式。
            headsetRoute?.release()
            val error =
                IllegalStateException(
                    "AudioRecord initialization failed for primary source and MIC"
                )
            Log.e(TAG, "AudioRecord initialization failed", error)
            DebugLogManager.logError(
                context,
                "audio",
                "recorder_init_failed",
                error,
                data = mapOf(
                    "sr" to sampleRate,
                    "chunkMs" to chunkMillis
                )
            )
            throw error
        }

        var activeRecorder: AudioRecord = requireNotNull(recorder)

        // API 31+ 的通信设备路由不需要再指定 preferredDevice；旧版有线/SCO 仍靠它兜底。
        // 抽成函数：录音中途重建 recorder（启动失败重试/读错误恢复）后仍能选中耳机输入。
        fun applyPreferredDevice(target: AudioRecord) {
            if (preferredInputDevice == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && routeUsesCommunicationPath) return
            try {
                target.preferredDevice = preferredInputDevice
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to set preferred input device on AudioRecord", t)
            }
        }
        applyPreferredDevice(activeRecorder)

        try {
            // 6. 启动录音
            try {
                try {
                    activeRecorder.startRecording()
                } catch (t: Throwable) {
                    if (!sourceIsCached) throw t
                    prefs.clearAudioSourceCache(deviceId)
                    cachedSource = null
                    try {
                        activeRecorder.release()
                    } catch (releaseError: Throwable) {
                        Log.w(TAG, "Failed to release cached recorder after start failure", releaseError)
                    }
                    sourceIsCached = false
                    val retryPrimary = startRecorderForSource(primarySource, bufferSize)
                    selectedSource = if (retryPrimary != null) {
                        activeRecorder = retryPrimary
                        applyPreferredDevice(retryPrimary)
                        primarySource
                    } else {
                        activeRecorder = startRecorderForSource(MediaRecorder.AudioSource.MIC, bufferSize)
                            ?: throw t
                        MediaRecorder.AudioSource.MIC
                    }
                }
                Log.d(TAG, "AudioRecord started successfully")
                try {
                    DebugLogManager.log("audio", "recorder_started")
                } catch (_: Throwable) { }
                // 实际路由可能被系统改写（A2DP-only、被通话抢占等），记录一次供事后定位。
                logHeadsetRoute(headsetRoute, activeRecorder, selectedSource)
            } catch (se: SecurityException) {
                Log.e(TAG, "SecurityException during startRecording", se)
                DebugLogManager.logError(
                    context,
                    "audio",
                    "recorder_start_security_error",
                    se
                )
                try {
                    DebugLogManager.log(
                        "audio",
                        "acm_error",
                        mapOf(
                            "stage" to "start",
                            "type" to "security",
                            "msg" to se.message
                        )
                    )
                } catch (_: Throwable) { }
                throw se
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start recording", t)
                DebugLogManager.logError(
                    context,
                    "audio",
                    "recorder_start_failed",
                    t
                )
                try {
                    DebugLogManager.log(
                        "audio",
                        "acm_error",
                        mapOf(
                            "stage" to "start",
                            "type" to "start_fail",
                            "msg" to t.message
                        )
                    )
                } catch (_: Throwable) { }
                throw IllegalStateException("Failed to start recording", t)
            }

            // 7. 探测音源是否近乎全零，必要时换回 MIC；耳机路由已确认时不再回退
            val avoidMicFallback = routeActive
            val probeTargetBytes = ((sampleRate * WARMUP_PROBE_MILLIS) / 1000) * bytesPerSample
            val warmupResult = if (sourceIsCached) {
                WarmupResult(activeRecorder, null, cachedSource!!, true)
            } else {
                warmupRecorder(
                    activeRecorder,
                    buf,
                    readSliceBytes,
                    probeTargetBytes,
                    bufferSize,
                    avoidMicFallback,
                    selectedSource
                )
            }
            activeRecorder = warmupResult.recorder
            var pendingCacheSource: Int? = null
            if (useSourceCache && cachedSource == null && warmupResult.verified) {
                prefs.setAudioSourceCache(deviceId, warmupResult.source)
                sourceIsCached = true
            } else if (useSourceCache && cachedSource == null) {
                pendingCacheSource = warmupResult.source
            }

            // 8. 探测期间读到的音频不丢弃，走同一套聚合逻辑，保证下游帧长仍是 chunkMillis
            val probedBytes = warmupResult.probedBytes
            if (probedBytes != null && probedBytes.isNotEmpty()) {
                pendingSize =
                    emitChunks(probedBytes, probedBytes.size, pending, chunkBytes, pendingSize)
            }

            // 9. 持续读取音频数据
            while (true) {
                val readStartedAtMs = SystemClock.elapsedRealtime()
                val read = try {
                    activeRecorder.read(buf, 0, readSliceBytes)
                } catch (t: Throwable) {
                    if (sourceIsCached) prefs.clearAudioSourceCache(deviceId)
                    Log.e(TAG, "Error reading audio data", t)
                    DebugLogManager.logError(
                        context,
                        "audio",
                        "recorder_read_failed",
                        t
                    )
                    try {
                        DebugLogManager.log(
                            "audio",
                            "acm_error",
                            mapOf(
                                "stage" to "read",
                                "msg" to t.message
                            )
                        )
                    } catch (_: Throwable) { }
                    AudioRecord.ERROR_INVALID_OPERATION
                }
                lastReadEndedAtMs = SystemClock.elapsedRealtime()
                lastReadMs = lastReadEndedAtMs - readStartedAtMs
                // read 阻塞且不响应取消，而 flow 只在 emit 处感知取消、emit 粒度仍是 chunkMillis，
                // 不显式检查就要等累积满一帧才能退出。这里让停录后最多再等一次 read。
                currentCoroutineContext().ensureActive()

                if (read > 0) {
                    pendingCacheSource?.let { source ->
                        prefs.setAudioSourceCache(deviceId, source)
                        sourceIsCached = true
                        pendingCacheSource = null
                    }
                    pendingSize = emitChunks(buf, read, pending, chunkBytes, pendingSize)
                } else if (read < 0) {
                    val shouldRecover = sourceIsCached
                    if (shouldRecover) prefs.clearAudioSourceCache(deviceId)
                    val error = IllegalStateException("AudioRecord read error: $read")
                    Log.e(TAG, "AudioRecord read returned error code", error)
                    DebugLogManager.logError(
                        context,
                        "audio",
                        "recorder_read_error_code",
                        error,
                        data = mapOf("code" to read)
                    )
                    if (!shouldRecover) throw error

                    try {
                        activeRecorder.stop()
                    } catch (_: Throwable) { }
                    try {
                        activeRecorder.release()
                    } catch (releaseError: Throwable) {
                        Log.w(TAG, "Failed to release cached recorder after read failure", releaseError)
                    }
                    sourceIsCached = false
                    cachedSource = null
                    val retryPrimary = startRecorderForSource(primarySource, bufferSize)
                    selectedSource = if (retryPrimary != null) {
                        activeRecorder = retryPrimary
                        applyPreferredDevice(retryPrimary)
                        primarySource
                    } else {
                        activeRecorder = startRecorderForSource(MediaRecorder.AudioSource.MIC, bufferSize)
                            ?: throw error
                        MediaRecorder.AudioSource.MIC
                    }
                    val recovered = warmupRecorder(
                        activeRecorder,
                        buf,
                        readSliceBytes,
                        probeTargetBytes,
                        bufferSize,
                        false,
                        selectedSource
                    )
                    activeRecorder = recovered.recorder
                    recovered.probedBytes?.takeIf { it.isNotEmpty() }?.let {
                        pendingSize = emitChunks(it, it.size, pending, chunkBytes, pendingSize)
                    }
                    if (recovered.verified) {
                        prefs.setAudioSourceCache(deviceId, recovered.source)
                        sourceIsCached = true
                    } else {
                        pendingCacheSource = recovered.source
                    }
                }
            }
        } finally {
            // 10. 清理资源：搬到独立作用域，避免 stop/release 与路由恢复阻塞识别启动。
            AsrCallLatencyProbe.log(
                "t_capture_read_exit",
                buildMap {
                    put("last_read_ms", lastReadMs)
                    put("slice_ms", (readSliceBytes / bytesPerSample) * 1000 / sampleRate)
                    // 累积未满一帧、随协程取消一起丢弃的尾部音频。
                    put("dropped_ms", (pendingSize / bytesPerSample) * 1000 / sampleRate)
                    if (lastReadEndedAtMs > 0L) {
                        put("since_read_ms", SystemClock.elapsedRealtime() - lastReadEndedAtMs)
                    }
                }
            )
            val recorderToRelease = activeRecorder
            pendingCaptureCleanup = captureCleanupScope.launch {
                val stopStartedAtMs = SystemClock.elapsedRealtime()
                try {
                    recorderToRelease.stop()
                    Log.d(TAG, "AudioRecord stopped")
                } catch (t: Throwable) {
                    Log.e(TAG, "Error stopping AudioRecord", t)
                }
                val stoppedAtMs = SystemClock.elapsedRealtime()
                try {
                    recorderToRelease.release()
                    Log.d(TAG, "AudioRecord released")
                } catch (t: Throwable) {
                    Log.e(TAG, "Error releasing AudioRecord", t)
                }
                AsrCallLatencyProbe.log(
                    "t_capture_released",
                    mapOf(
                        "stop_ms" to (stoppedAtMs - stopStartedAtMs),
                        "release_ms" to (SystemClock.elapsedRealtime() - stoppedAtMs)
                    )
                )
                try {
                    DebugLogManager.log(
                        category = "audio",
                        event = "acm_cleanup"
                    )
                } catch (_: Throwable) { }
                // 路由只覆盖本次录音窗口，收尾时恢复通信设备 / SCO / audio mode。
                headsetRoute?.release()
            }
        }
    }

    /** 探测结果：可能被换掉的 recorder，以及探测期间读到的、应当交给下游的音频。 */
    private class WarmupResult(
        val recorder: AudioRecord,
        val probedBytes: ByteArray?,
        val source: Int,
        val verified: Boolean
    )

    /**
     * 探测音源是否是"坏源"（近乎全零），仅在确认时回退为 MIC。
     *
     * - 耳机优先且路由已就绪时直接跳过：探测结果不会改变音源选择。
     * - 否则按 read 粒度累积探测，最多 [probeTargetBytes]；一旦读到超阈值样本立刻结束。
     * - 探测到的音频不丢弃，由调用方喂给下游，录音开头不再缺一截。
     * - 判定为坏源时才停止并释放当前源、重建为 MIC 源；重建后的第一帧由主读循环负责。
     */
    private fun warmupRecorder(
        current: AudioRecord,
        buf: ByteArray,
        readSliceBytes: Int,
        probeTargetBytes: Int,
        bufferSize: Int,
        avoidMicFallback: Boolean,
        initialSource: Int
    ): WarmupResult {
        if (!hasPermission()) {
            val error = SecurityException("RECORD_AUDIO permission was revoked during warmup")
            Log.e(TAG, "Permission check failed during warmup", error)
            throw error
        }

        var recorder = current
        // 耳机优先且路由已就绪时，探测结果不会改变音源选择，整段探测没有意义，直接跳过。
        if (avoidMicFallback) {
            logWarmupResult("skipped", 0, 0L)
            return WarmupResult(recorder, null, initialSource, false)
        }

        val probeStartedAtMs = SystemClock.elapsedRealtime()
        val probed = ByteArrayOutputStream(probeTargetBytes)
        var sawFrame = false
        var hasSignal = false
        var nearZero = true
        var reads = 0
        while (probed.size() < probeTargetBytes && reads < WARMUP_MAX_READS) {
            reads++
            val read = try {
                recorder.read(buf, 0, readSliceBytes)
            } catch (t: Throwable) {
                Log.e(TAG, "Error during warmup probe read", t)
                break
            }
            if (read <= 0) break
            probed.write(buf, 0, read)
            sawFrame = true
            val stats = computeFrameStats16le(buf, read, 30)
            val meanSquare = if (stats.sampleCount > 0) {
                stats.sumSquares.toDouble() / stats.sampleCount
            } else {
                0.0
            }
            if (stats.countAboveThreshold > 0) {
                hasSignal = true
                nearZero = false
                break
            }
            if (stats.maxAbs >= 12 || meanSquare >= 16.0) nearZero = false
        }
        val probeElapsedMs = SystemClock.elapsedRealtime() - probeStartedAtMs
        val probedBytes = probed.toByteArray()

        if (!sawFrame || !nearZero || initialSource == MediaRecorder.AudioSource.MIC) {
            logWarmupResult(
                if (hasSignal) "signal" else "no_signal",
                probedBytes.size,
                probeElapsedMs
            )
            return WarmupResult(
                recorder,
                probedBytes.takeIf { it.isNotEmpty() },
                initialSource,
                sawFrame
            )
        }
        // 近乎全零：探测数据来自坏音源，丢弃并重建为 MIC 源
        Log.i(TAG, "Warmup: near-zero source, rebuilding with MIC")
        try {
            recorder.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "Error stopping recorder during rebuild", t)
        }
        try {
            recorder.release()
        } catch (t: Throwable) {
            Log.e(TAG, "Error releasing recorder during rebuild", t)
        }

        val newRecorder = try {
            createAudioRecord(MediaRecorder.AudioSource.MIC, bufferSize)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create new AudioRecord with MIC during warmup", t)
            null
        }

        if (newRecorder == null || newRecorder.state != AudioRecord.STATE_INITIALIZED) {
            val error =
                IllegalStateException(
                    "Failed to rebuild AudioRecord with MIC source during warmup"
                )
            Log.e(TAG, "AudioRecord rebuild failed", error)
            DebugLogManager.logError(
                context,
                "audio",
                "warmup_rebuild_failed",
                error
            )
            throw error
        }

        recorder = newRecorder
        try {
            recorder.startRecording()
            Log.d(TAG, "Warmup: MIC recorder started")
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException during MIC recorder start", se)
            DebugLogManager.logError(
                context,
                "audio",
                "warmup_mic_start_security_error",
                se
            )
            try {
                recorder.release()
            } catch (t: Throwable) {
                Log.e(TAG, "Error releasing recorder after SecurityException", t)
            }
            throw se
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start MIC recorder", t)
            DebugLogManager.logError(
                context,
                "audio",
                "warmup_mic_start_failed",
                t
            )
            try {
                recorder.release()
            } catch (releaseError: Throwable) {
                Log.e(TAG, "Error releasing recorder after start failure", releaseError)
            }
            throw IllegalStateException("Failed to start MIC recorder", t)
        }

        // 音源被换掉是关键状态迁移，走 base 流：用户不开详细日志也要能定位。
        DebugLogManager.logBase(
            category = "audio",
            event = "acm_warmup_mic_rebuilt",
            data = mapOf("probed_ms" to probeElapsedMs)
        )
        // 重建后的第一帧交给主读循环，这里不再额外读一帧。
        return WarmupResult(recorder, null, MediaRecorder.AudioSource.MIC, false)
    }

    private fun logWarmupResult(result: String, probedBytes: Int, elapsedMs: Long) {
        try {
            DebugLogManager.log(
                category = "audio",
                event = "acm_warmup",
                data = mapOf(
                    "result" to result,
                    "probed_bytes" to probedBytes,
                    "elapsed_ms" to elapsedMs
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to log warmup result", t)
        }
    }

    /**
     * 记录一次耳机路由结果：实际采集设备可能被系统改写（A2DP-only 耳机、通话抢占等），
     * 只有 routedDevice 才能说明录音到底来自耳机还是手机麦克风。
     */
    private fun logHeadsetRoute(route: HeadsetAudioRoute?, recorder: AudioRecord, source: Int) {
        if (route == null) return
        val routed = try {
            recorder.routedDevice
        } catch (t: Throwable) {
            Log.w(TAG, "getRoutedDevice failed", t)
            null
        }
        val requested = route.selectedDevice
        try {
            DebugLogManager.logBase(
                category = "audio",
                event = "headset_route",
                data = mapOf(
                    "activated" to route.activated,
                    "comm_path" to route.viaCommunicationPath,
                    "requested_type" to requested?.type,
                    "routed_type" to routed?.type,
                    "routed_name" to routed?.productName,
                    "source" to audioSourceName(source),
                    "prepare_ms" to route.prepareElapsedMs,
                    "failure" to route.failureReason,
                    "matched" to (requested != null && routed?.type == requested.type)
                )
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to log headset route", t)
        }
    }

    private fun audioSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "voice_communication"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "voice_recognition"
        MediaRecorder.AudioSource.MIC -> "mic"
        else -> source.toString()
    }

    /**
     * 把 [len] 字节喂入 [pending] 聚合缓冲，凑满 [chunkBytes] 就 emit 一帧，返回新的待聚合长度。
     *
     * [pending] 为 null 表示 read 粒度已等于 emit 粒度，直接透传不做聚合。
     */
    private suspend fun FlowCollector<ByteArray>.emitChunks(
        src: ByteArray,
        len: Int,
        pending: ByteArray?,
        chunkBytes: Int,
        pendingSize: Int
    ): Int {
        if (pending == null) {
            emit(src.copyOf(len))
            return 0
        }
        var offset = 0
        var size = pendingSize
        while (offset < len) {
            val take = minOf(len - offset, chunkBytes - size)
            System.arraycopy(src, offset, pending, size, take)
            size += take
            offset += take
            if (size == chunkBytes) {
                emit(pending.copyOf())
                size = 0
            }
        }
        return size
    }
}
