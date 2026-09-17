package com.brycewg.asrkb.asr

import android.content.Context
import android.media.AudioFormat
import android.os.SystemClock
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.getAsrRuntimeStatsSnapshotOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 并行主备 ASR 引擎：
 * - 录音只采集一次（AudioCaptureManager）
 * - 同步推送 PCM 给主用与备用两个引擎（均以 externalPcmMode/Push-PCM 方式构建）
 * - 以“主用是否在阈值内产生终止事件（onFinal/onError）”作为切换依据：
 *   - 主用先给出 onFinal：直接采用主用，即使结果为空
 *   - 主用超时或失败（onError）：尝试采用备用结果
 */
class ParallelAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    override val primaryVendor: AsrVendor,
    override val backupVendor: AsrVendor,
    private val onPrimaryRequestDuration: ((Long) -> Unit)? = null,
    private val externalPcmInput: Boolean = false
) : StreamingAsrEngine,
    BackupAwareAsrEngine,
    ExternalPcmConsumer,
    CancelableAsrEngine,
    AudioFrameSinkOwner {

    companion object {
        private const val TAG = "ParallelAsrEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val CHUNK_MS = 200
    }

    private enum class Source { PRIMARY, BACKUP }

    private sealed class Terminal {
        data class Final(val text: String) : Terminal()
        data class Error(val message: String) : Terminal()
    }

    override val isRunning: Boolean
        get() = running.get()

    private val running = AtomicBoolean(false)
    override var audioFrameSink: AudioFrameSink? = null
    internal var modePreferencesOverride: AsrEngineModePreferences? = null
    internal var modelOverride: AsrRequestModelOverride = AsrRequestModelOverride()
    private val modePreferences: AsrEngineModePreferences
        get() = modePreferencesOverride ?: prefs.asrEngineModePreferencesSnapshot()
    private val stopRequested = AtomicBoolean(false)

    private val stateLock = Any()

    private var audioJob: Job? = null
    private var switchDeadlineJob: Job? = null

    @Volatile private var startUptimeMs: Long = 0L
    private val audioBytes = AtomicLong(0L)

    @Volatile private var stoppedNotified: Boolean = false

    private val terminalCoordinator = BackupAsrTerminalCoordinator(
        onFinal = ::deliverFinalFromCoordinator,
        onError = ::deliverErrorFromCoordinator
    )

    override val backupStrategy: AsrParallelEngineDecision =
        AsrParallelEngineDecision.UseParallel

    override val primaryStreamingForSwitchPlan: Boolean
        get() = isPrimaryNativeOrLocalStreamForSwitch()

    override fun wasLastResultFromBackup(): Boolean = terminalCoordinator.wasLastResultFromBackup()

    private val primaryListener = EngineListener(Source.PRIMARY, forwardLocalModelUi = true)
    private val backupListener = EngineListener(Source.BACKUP, forwardLocalModelUi = false)

    private var primaryEngine: StreamingAsrEngine? = null
    private var backupEngine: StreamingAsrEngine? = null
    private var primaryConsumer: ExternalPcmConsumer? = null
    private var backupConsumer: ExternalPcmConsumer? = null
    private val pushPcmEngineFactory = AsrPushPcmEngineFactory()
    private val externalVadInputLeveler = VadInputLevelerBranch(sampleRate = SAMPLE_RATE)

    override fun start() {
        if (!running.compareAndSet(false, true)) return

        stopRequested.set(false)
        stoppedNotified = false
        audioBytes.set(0L)
        externalVadInputLeveler.reset()
        switchDeadlineJob?.cancel()
        switchDeadlineJob = null

        startUptimeMs = try {
            SystemClock.uptimeMillis()
        } catch (_: Throwable) {
            0L
        }

        val primaryNetworkGateEvent = AsrPrimaryNetworkGate.preflightEvent(
            primaryVendor = primaryVendor,
            networkAvailable = AsrPrimaryNetworkGate.isNetworkAvailable(context),
            message = context.getString(R.string.asr_error_network_unavailable)
        )
        primaryEngine = if (primaryNetworkGateEvent == null) {
            buildPushPcmEngine(
                vendor = primaryVendor,
                engineListener = primaryListener,
                invocationMode = AsrEngineInvocationMode.ParallelPrimary,
                onRequestDuration = onPrimaryRequestDuration
            )
        } else {
            null
        }
        backupEngine = buildPushPcmEngine(
            vendor = backupVendor,
            engineListener = backupListener,
            invocationMode = AsrEngineInvocationMode.ParallelBackup,
            onRequestDuration = null
        )
        primaryConsumer = primaryEngine as? ExternalPcmConsumer
        backupConsumer = backupEngine as? ExternalPcmConsumer
        terminalCoordinator.reset(
            hasPrimary = primaryEngine != null || primaryNetworkGateEvent != null,
            hasBackup = backupEngine != null
        )

        if (primaryEngine == null && backupEngine == null && primaryNetworkGateEvent == null) {
            running.set(false)
            try {
                listener.onError(
                    context.getString(
                        R.string.error_recognize_failed_with_reason,
                        "No engine available"
                    )
                )
            } catch (t: Throwable) {
                Log.w(TAG, "notify no engine available failed", t)
            }
            return
        }

        if (primaryNetworkGateEvent != null) {
            val deliveredByGate = synchronized(stateLock) {
                if (!terminalCoordinator.terminalDelivered) {
                    terminalCoordinator.dispatch(primaryNetworkGateEvent)
                }
                terminalCoordinator.terminalDelivered
            }
            if (deliveredByGate) {
                cleanupAfterTerminal()
                return
            }
        } else {
            try {
                primaryEngine?.start()
            } catch (t: Throwable) {
                Log.e(TAG, "primary start failed", t)
                onTerminal(Source.PRIMARY, Terminal.Error(t.message ?: "primary start failed"))
            }
        }
        try {
            backupEngine?.start()
        } catch (t: Throwable) {
            Log.e(TAG, "backup start failed", t)
            onTerminal(Source.BACKUP, Terminal.Error(t.message ?: "backup start failed"))
        }

        if (!externalPcmInput) {
            startAudioCapture()
        }
    }

    override fun stop() {
        if (stopRequested.getAndSet(true)) return

        running.set(false)
        if (!terminalCoordinator.terminalDelivered) {
            notifyStoppedIfNeeded()
        }

        try {
            audioJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel audio job failed", t)
        } finally {
            audioJob = null
        }

        stopEngines()
        scheduleSwitchDeadlineIfNeeded()
    }

    private fun stopEngines() {
        try {
            primaryEngine?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "primary stop failed", t)
        }
        try {
            backupEngine?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "backup stop failed", t)
        }
    }

    override fun cancel() {
        stopRequested.set(true)
        running.set(false)
        terminalCoordinator.markTerminalDelivered()
        try {
            audioJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel audio job failed", t)
        } finally {
            audioJob = null
        }
        try {
            switchDeadlineJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel switch deadline failed", t)
        } finally {
            switchDeadlineJob = null
        }
        cancelOrStopEngine(primaryEngine, "primary")
        cancelOrStopEngine(backupEngine, "backup")
        primaryEngine = null
        backupEngine = null
        primaryConsumer = null
        backupConsumer = null
    }

    private fun cancelOrStopEngine(engine: StreamingAsrEngine?, label: String) {
        try {
            val cancelable = engine as? CancelableAsrEngine
            if (cancelable != null) {
                cancelable.cancel()
            } else {
                engine?.stop()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$label cancel failed", t)
        }
    }

    override suspend fun awaitReady(timeoutMs: Long): Boolean {
        val primaryOk = primaryConsumer?.awaitReady(timeoutMs) ?: true
        val backupOk = backupConsumer?.awaitReady(timeoutMs) ?: true
        return primaryOk && backupOk
    }

    override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
        if (!externalPcmInput) return
        if (!running.get()) return
        if (terminalCoordinator.terminalDelivered) return
        if (sampleRate != SAMPLE_RATE || channels != CHANNELS) return

        audioBytes.addAndGet(pcm.size.toLong())
        val leveled = externalVadInputLeveler.process(pcm)
        try {
            listener.onAmplitude(leveled.stableAmplitude)
        } catch (t: Throwable) {
            Log.w(TAG, "notify amplitude failed (externalPcmInput)", t)
        }
        appendPcmToConsumers(pcm, sourceLabel = "externalPcmInput")
    }

    private fun cleanupAfterTerminal() {
        stopRequested.set(true)
        running.set(false)
        try {
            switchDeadlineJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel switchDeadlineJob failed in cleanupAfterTerminal", t)
        } finally {
            switchDeadlineJob = null
        }
        try {
            audioJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel audio job failed in cleanupAfterTerminal", t)
        } finally {
            audioJob = null
        }
        try {
            primaryEngine?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "primary stop failed in cleanupAfterTerminal", t)
        }
        try {
            backupEngine?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "backup stop failed in cleanupAfterTerminal", t)
        }
    }

    private fun startAudioCapture() {
        audioJob?.cancel()
        audioJob = scope.launch(Dispatchers.IO) {
            val audioManager = AudioCaptureManager(
                context = context,
                sampleRate = SAMPLE_RATE,
                channelConfig = AudioFormat.CHANNEL_IN_MONO,
                audioFormat = AudioFormat.ENCODING_PCM_16BIT,
                chunkMillis = CHUNK_MS,
                audioFrameSinkProvider = { audioFrameSink }
            )

            if (!audioManager.hasPermission()) {
                Log.e(TAG, "Missing RECORD_AUDIO permission")
                fatalCaptureError(context.getString(R.string.error_record_permission_denied))
                return@launch
            }

            val vadDetector = if (isVadAutoStopEnabled(context, prefs)) {
                try {
                    VadDetector(
                        context,
                        SAMPLE_RATE,
                        prefs.autoStopSilenceWindowMs,
                        prefs.autoStopSilenceSensitivity
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to create VAD detector", t)
                    null
                }
            } else {
                null
            }
            val maxDurationLimiter = RecordingDurationLimiter.fromPrefs(
                prefs = prefs,
                sampleRate = SAMPLE_RATE
            )
            val vadInputLeveler = VadInputLevelerBranch(sampleRate = SAMPLE_RATE)

            try {
                audioManager.startCapture().collect { chunk ->
                    if (!isActive || !running.get()) return@collect
                    if (terminalCoordinator.terminalDelivered) return@collect

                    val leveled = vadInputLeveler.process(chunk)

                    try {
                        listener.onAmplitude(leveled.stableAmplitude)
                    } catch (t: Throwable) {
                        Log.w(TAG, "notify amplitude failed", t)
                    }
                    audioBytes.addAndGet(chunk.size.toLong())

                    appendPcmToConsumers(chunk, sourceLabel = "capture")

                    if (maxDurationLimiter.acceptPcm(chunk.size)) {
                        Log.d(TAG, "Max recording duration reached, stopping session")
                        notifyStoppedIfNeeded()
                        stop()
                        return@collect
                    }

                    if (vadDetector?.shouldStop(leveled.leveledPcm, leveled.leveledPcm.size) == true) {
                        Log.d(TAG, "VAD silence detected, stopping session")
                        notifyStoppedIfNeeded()
                        stop()
                        return@collect
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    Log.d(TAG, "Audio capture cancelled: ${t.message}")
                } else {
                    Log.e(TAG, "Audio capture failed", t)
                    fatalCaptureError(
                        context.getString(R.string.error_audio_error, t.message ?: "")
                    )
                }
            } finally {
                try {
                    vadDetector?.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "VAD release failed", t)
                }
            }
        }
    }

    private fun fatalCaptureError(message: String) {
        val shouldStopCapture = synchronized(stateLock) {
            if (terminalCoordinator.terminalDelivered) return
            terminalCoordinator.dispatch(
                AsrBackupArbitrationEvent.PrimaryError(
                    message = message,
                    strategy = AsrPrimaryErrorStrategy.ImmediateFailover
                )
            )
            terminalCoordinator.dispatch(AsrBackupArbitrationEvent.BackupError(message))
            terminalCoordinator.terminalDelivered
        }
        if (shouldStopCapture) {
            cleanupAfterTerminal()
        }
    }

    private fun appendPcmToConsumers(pcm: ByteArray, sourceLabel: String) {
        try {
            primaryConsumer?.appendPcm(pcm, SAMPLE_RATE, CHANNELS)
        } catch (t: Throwable) {
            Log.w(TAG, "primary appendPcm failed ($sourceLabel)", t)
        }
        try {
            backupConsumer?.appendPcm(pcm, SAMPLE_RATE, CHANNELS)
        } catch (t: Throwable) {
            Log.w(TAG, "backup appendPcm failed ($sourceLabel)", t)
        }
    }

    private fun notifyStoppedIfNeeded() {
        if (stoppedNotified) return
        stoppedNotified = true
        try {
            listener.onStopped()
        } catch (t: Throwable) {
            Log.w(TAG, "notify onStopped failed", t)
        }
    }

    private fun scheduleSwitchDeadlineIfNeeded() {
        if (primaryEngine == null || backupEngine == null) return
        if (terminalCoordinator.terminalDelivered) return

        val bytesAudioMs = audioMsFromBytes(audioBytes.get())
        val audioMs = if (bytesAudioMs > 0L) {
            bytesAudioMs
        } else {
            val t0 = startUptimeMs
            val t1 = try {
                SystemClock.uptimeMillis()
            } catch (_: Throwable) {
                0L
            }
            if (t0 > 0L && t1 >= t0) (t1 - t0) else 0L
        }

        val sensitivityTier = try {
            prefs.backupAsrTimeoutSensitivity
        } catch (_: Throwable) {
            1
        }
        val primaryStreaming = isPrimaryNativeOrLocalStreamForSwitch()
        val switchPlan = AsrTimeoutCalculator.calculateBackupSwitchPlan(
            audioMs = audioMs,
            primaryVendor = primaryVendor,
            primaryStreaming = primaryStreaming,
            sensitivityTier = sensitivityTier,
            primaryStatsSnapshot = prefs.getAsrRuntimeStatsSnapshotOrNull(primaryVendor, audioMs),
            backupStrategy = AsrParallelEngineDecision.UseParallel
        )
        val switchDeadlineMs = switchPlan.switchDeadlineMs

        try {
            switchDeadlineJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel switchDeadlineJob failed", t)
        }
        switchDeadlineJob = scope.launch {
            val countdownStartMs = try {
                SystemClock.uptimeMillis()
            } catch (_: Throwable) {
                0L
            }
            val readyWaitBudgetMs = switchDeadlineMs.coerceAtMost(LOCAL_MODEL_READY_WAIT_MAX_MS)
            if (isLocalAsrVendor(primaryVendor)) {
                val ok = awaitLocalAsrReady(prefs, maxWaitMs = readyWaitBudgetMs)
                if (!ok) {
                    Log.w(
                        TAG,
                        "Local model readiness wait timed out; continue countdown within switch deadline"
                    )
                }
                if (terminalCoordinator.terminalDelivered) return@launch
            }
            val elapsedMs = if (countdownStartMs > 0L) {
                val now = try {
                    SystemClock.uptimeMillis()
                } catch (_: Throwable) {
                    countdownStartMs
                }
                if (now >= countdownStartMs) {
                    (now - countdownStartMs).coerceAtLeast(0L)
                } else {
                    0L
                }
            } else {
                0L
            }
            val remainingDelayMs = (switchDeadlineMs - elapsedMs).coerceAtLeast(0L)
            delay(remainingDelayMs)
            synchronized(stateLock) {
                if (terminalCoordinator.terminalDelivered) return@synchronized
                Log.w(
                    TAG,
                    "Switch deadline reached (audioMs=$audioMs, switchDeadlineMs=$switchDeadlineMs, elapsedMs=$elapsedMs)"
                )
                terminalCoordinator.dispatch(AsrBackupArbitrationEvent.SwitchDeadlineReached)
            }
        }
        Log.d(
            TAG,
            "Switch deadline scheduled: audioMs=$audioMs, switchDeadlineMs=$switchDeadlineMs, usedStaticFallback=${switchPlan.usedStaticFallback}, baselineMs=${switchPlan.baselineMs}, audioAdjustmentMs=${switchPlan.audioLengthAdjustmentMs}, modeAdjustmentMs=${switchPlan.primaryModeAdjustmentMs}"
        )
    }

    private fun audioMsFromBytes(bytes: Long): Long {
        if (bytes <= 0L) return 0L
        val denom = SAMPLE_RATE.toLong() * CHANNELS.toLong() * 2L
        if (denom <= 0L) return 0L
        return (bytes * 1000L / denom).coerceAtLeast(0L)
    }

    private fun isPrimaryNativeOrLocalStreamForSwitch(): Boolean = try {
        when (
            pushPcmEngineFactory.resolvePlan(
                vendor = primaryVendor,
                invocationMode = AsrEngineInvocationMode.ParallelPrimary,
                preferences = modePreferences,
                source = AsrEngineConstructionSource.App
            ).family
        ) {
            AsrPushPcmEngineFamily.NativeStream,
            AsrPushPcmEngineFamily.LocalStream -> true
            AsrPushPcmEngineFamily.FileAdapter,
            AsrPushPcmEngineFamily.PseudoStream -> false
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to resolve primary stream mode for switch deadline", t)
        false
    }

    private fun onTerminal(source: Source, t: Terminal) {
        val shouldStopCapture = synchronized(stateLock) {
            if (terminalCoordinator.terminalDelivered) return
            terminalCoordinator.dispatch(t.toArbitrationEvent(source))
            terminalCoordinator.terminalDelivered
        }
        if (shouldStopCapture) {
            cleanupAfterTerminal()
        }
    }

    private fun Terminal.toArbitrationEvent(source: Source): AsrBackupArbitrationEvent = when (source) {
        Source.PRIMARY -> when (this) {
            is Terminal.Final -> AsrBackupArbitrationEvent.PrimaryFinal(text)
            is Terminal.Error ->
                if (AsrErrorMessageMapper.isEmptyResult(context, message)) {
                    AsrBackupArbitrationEvent.PrimaryFinal("")
                } else {
                    AsrBackupArbitrationEvent.PrimaryError(message)
                }
        }
        Source.BACKUP -> when (this) {
            is Terminal.Final -> AsrBackupArbitrationEvent.BackupFinal(text)
            is Terminal.Error -> AsrBackupArbitrationEvent.BackupError(message)
        }
    }

    private fun deliverFinalFromCoordinator(
        text: String,
        source: AsrBackupArbitrationSource
    ) {
        try {
            switchDeadlineJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel switchDeadlineJob failed on deliverFinal", t)
        } finally {
            switchDeadlineJob = null
        }
        try {
            listener.onFinal(text)
        } catch (t: Throwable) {
            Log.e(TAG, "notify final failed", t)
        }
    }

    private fun deliverErrorFromCoordinator(message: String) {
        try {
            switchDeadlineJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel switchDeadlineJob failed on deliverError", t)
        } finally {
            switchDeadlineJob = null
        }
        try {
            listener.onError(message)
        } catch (t: Throwable) {
            Log.e(TAG, "notify error failed", t)
        }
    }

    private inner class EngineListener(
        private val source: Source,
        private val forwardLocalModelUi: Boolean
    ) : StreamingAsrEngine.Listener,
        SenseVoiceFileAsrEngine.LocalModelLoadUi {

        override fun onFinal(text: String) {
            onTerminal(source, Terminal.Final(text))
        }

        override fun onError(message: String) {
            onTerminal(source, Terminal.Error(message))
        }

        override fun onPartial(text: String) {
            if (source != Source.PRIMARY) return
            try {
                listener.onPartial(text)
            } catch (t: Throwable) {
                Log.w(TAG, "notify partial failed", t)
            }
        }

        override fun onLocalModelLoadStart() {
            if (!forwardLocalModelUi) return
            val ui = listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi ?: return
            try {
                ui.onLocalModelLoadStart()
            } catch (
                t: Throwable
            ) {
                Log.w(TAG, "forward loadStart failed", t)
            }
        }

        override fun onLocalModelLoadDone() {
            if (!forwardLocalModelUi) return
            val ui = listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi ?: return
            try {
                ui.onLocalModelLoadDone()
            } catch (
                t: Throwable
            ) {
                Log.w(TAG, "forward loadDone failed", t)
            }
        }
    }

    private fun buildPushPcmEngine(
        vendor: AsrVendor,
        engineListener: StreamingAsrEngine.Listener,
        invocationMode: AsrEngineInvocationMode,
        onRequestDuration: ((Long) -> Unit)?
    ): StreamingAsrEngine? {
        if (!hasRequiredVendorConfiguration(vendor)) return null

        return pushPcmEngineFactory.create(
            context = context,
            scope = scope,
            prefs = prefs,
            listener = engineListener,
            vendor = vendor,
            invocationMode = invocationMode,
            preferences = modePreferences,
            source = AsrEngineConstructionSource.App,
            onRequestDuration = onRequestDuration,
            applyAudioPreprocess = true,
            modelOverride = modelOverride
        )
    }

    private fun hasRequiredVendorConfiguration(vendor: AsrVendor): Boolean = try {
        isAsrVendorConfigured(context, prefs, vendor)
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to check vendor configuration for vendor=$vendor", t)
        false
    }
}
