// Lazy local backup ASR wrapper; starts the primary engine first and defers local backup work.
package com.brycewg.asrkb.asr

import android.content.Context
import android.media.AudioFormat
import android.os.SystemClock
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.getAsrRuntimeStatsSnapshotOrNull
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal interface BackupAsrStatusListener {
    fun onBackupAsrLoading(backupVendor: AsrVendor) { /* default no-op */ }

    fun onBackupAsrRecognizing(backupVendor: AsrVendor) { /* default no-op */ }
}

internal data class LazyLocalBackupAsrEngineHooks(
    val createPrimaryEngine: (StreamingAsrEngine.Listener) -> StreamingAsrEngine?,
    val createBackupEngine: (StreamingAsrEngine.Listener) -> StreamingAsrEngine?,
    val onBackupRequestDuration: ((Long) -> Unit)? = null,
    val preloadBackupVendor: (AsrLocalVendorPreloadRequest) -> Boolean,
    val isBackupReady: () -> Boolean,
    val processBufferedPcm: (ByteArray) -> RecordedAudioVoiceFilter.Result,
    val primaryNetworkGateEvent: () -> AsrBackupArbitrationEvent.PrimaryError? = { null },
    val backupSwitchPlan: (
        audioMs: Long,
        primaryStreaming: Boolean,
        pendingRetryCount: Int
    ) -> BackupSwitchPlan = { _, _, _ ->
        BackupSwitchPlan(
            switchDeadlineMs = 0L,
            usedStaticFallback = true,
            baselineMs = 0L,
            audioLengthAdjustmentMs = 0L,
            primaryModeAdjustmentMs = 0L,
            lazyBackupStartAtMs = 0L,
            lazyEstimatedBackupReadyMs = 0L,
            lazyResidencyFactor = 0.0,
            lazyMinPrimaryWindowMs = 0L
        )
    },
    val residencyManager: LocalBackupResidencyController = NoopLocalBackupResidencyController,
    val localBackupResidency: () -> BackupAsrLocalResidency = { BackupAsrLocalResidency.OnDemand }
)

internal class LazyLocalBackupAsrEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val listener: StreamingAsrEngine.Listener,
    override val primaryVendor: AsrVendor,
    override val backupVendor: AsrVendor,
    private val onPrimaryRequestDuration: ((Long) -> Unit)? = null,
    private val onBackupRequestDuration: ((Long) -> Unit)? = null,
    private val backupRequestDurationHolder: AtomicReference<Long?> = AtomicReference(null),
    private val externalPcmInput: Boolean = false,
    private val modePreferences: AsrEngineModePreferences = prefs.asrEngineModePreferencesSnapshot(),
    private val modelOverride: AsrRequestModelOverride = AsrRequestModelOverride(),
    private val maxBufferedPcmBytes: Int = defaultMaxBufferedPcmBytes(),
    private val hooks: LazyLocalBackupAsrEngineHooks = realHooks(
        context = context,
        scope = scope,
        prefs = prefs,
        primaryVendor = primaryVendor,
        backupVendor = backupVendor,
        onPrimaryRequestDuration = onPrimaryRequestDuration,
        onBackupRequestDuration = onBackupRequestDuration,
        backupRequestDurationHolder = backupRequestDurationHolder,
        modePreferences = modePreferences,
        modelOverride = modelOverride
    )
) : BackupAwareAsrEngine,
    ExternalPcmConsumer,
    CancelableAsrEngine,
    AudioFrameSinkOwner,
    SessionAudioSourceOwner,
    ProgressiveRetryStatusOwner {

    private enum class Source { PRIMARY, BACKUP }

    private sealed class Terminal {
        data class Final(val text: String) : Terminal()
        data class Error(val message: String) : Terminal()
    }

    override val backupStrategy: AsrParallelEngineDecision =
        AsrParallelEngineDecision.UseLazyLocalBackup

    override val primaryStreamingForSwitchPlan: Boolean
        get() = isPrimaryStreamingForSwitchPlan()

    override val isRunning: Boolean
        get() = running.get()

    private val running = AtomicBoolean(false)
    override var audioFrameSink: AudioFrameSink? = null
    private val stopRequested = AtomicBoolean(false)
    private val backupTriggered = AtomicBoolean(false)
    private val backupFed = AtomicBoolean(false)
    private val residencySessionFinished = AtomicBoolean(true)
    private val stateLock = Any()
    private val audioBytes = AtomicLong(0L)
    private val pcmLock = Any()
    private val pcmBuffer = ByteArrayOutputStream()
    private val externalVadInputLeveler = VadInputLevelerBranch(sampleRate = SAMPLE_RATE)

    @Volatile private var stoppedNotified: Boolean = false

    @Volatile private var startUptimeMs: Long = 0L

    @Volatile private var lastBackupStatus: String? = null

    private val terminalCoordinator = BackupAsrTerminalCoordinator(
        onFinal = ::deliverFinalFromCoordinator,
        onError = ::deliverErrorFromCoordinator
    )

    private var audioJob: Job? = null
    private var backupStartJob: Job? = null
    private var switchDeadlineJob: Job? = null
    private var primaryEngine: StreamingAsrEngine? = null
    private var backupEngine: StreamingAsrEngine? = null
    private var primaryConsumer: ExternalPcmConsumer? = null
    private var backupConsumer: ExternalPcmConsumer? = null

    override val sessionAudioStore: SessionAudioSegmentStore?
        get() = (primaryEngine as? SessionAudioSourceOwner)?.sessionAudioStore

    override fun peekPendingRetryCount(): Int = maxOf(
        (primaryEngine as? ProgressiveRetryStatusOwner)?.peekPendingRetryCount() ?: 0,
        (backupEngine as? ProgressiveRetryStatusOwner)?.peekPendingRetryCount() ?: 0
    )

    private val primaryListener = EngineListener(Source.PRIMARY)
    private val backupListener = EngineListener(Source.BACKUP)

    override fun wasLastResultFromBackup(): Boolean = terminalCoordinator.wasLastResultFromBackup()

    override fun start() {
        if (!running.compareAndSet(false, true)) return

        stopRequested.set(false)
        backupTriggered.set(false)
        backupFed.set(false)
        residencySessionFinished.set(false)
        hooks.residencyManager.onSessionStarted()
        stoppedNotified = false
        lastBackupStatus = null
        audioBytes.set(0L)
        backupRequestDurationHolder.set(null)
        externalVadInputLeveler.reset()
        synchronized(pcmLock) {
            pcmBuffer.reset()
        }
        cancelBackupPlanJobs()

        startUptimeMs = try {
            SystemClock.uptimeMillis()
        } catch (_: Throwable) {
            0L
        }

        val networkGateEvent = hooks.primaryNetworkGateEvent()
        primaryEngine = if (networkGateEvent == null) {
            hooks.createPrimaryEngine(primaryListener)
        } else {
            null
        }
        primaryConsumer = primaryEngine as? ExternalPcmConsumer
        terminalCoordinator.reset(
            hasPrimary = primaryEngine != null || networkGateEvent != null,
            hasBackup = true
        )

        if (networkGateEvent != null) {
            onPrimarySuspicious(networkGateEvent)
        } else if (primaryEngine == null) {
            triggerBackup("primary_unavailable")
        } else {
            try {
                primaryEngine?.start()
            } catch (t: Throwable) {
                Log.e(TAG, "primary start failed", t)
                onPrimaryImmediateFailure(t.message ?: "primary start failed", "primary_start_failed")
            }
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

        stopPrimaryEngine()
        scheduleBackupSwitchPlan()
        maybeFeedBackupIfReady()
    }

    private fun stopPrimaryEngine() {
        try {
            primaryEngine?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "primary stop failed", t)
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
        cancelBackupPlanJobs()
        synchronized(pcmLock) {
            pcmBuffer.reset()
        }
        cancelOrStopEngine(primaryEngine, "primary")
        cancelOrStopEngine(backupEngine, "backup")
        primaryEngine = null
        backupEngine = null
        primaryConsumer = null
        backupConsumer = null
        finishResidencySession()
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
        if (appendPcmToPrimaryAndBuffer(pcm, sourceLabel = "externalPcmInput")) {
            notifyStoppedIfNeeded()
            stop()
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
                val message = context.getString(R.string.error_record_permission_denied)
                fatalCaptureError(message)
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
                    if (appendPcmToPrimaryAndBuffer(chunk, sourceLabel = "capture")) {
                        notifyStoppedIfNeeded()
                        stop()
                        return@collect
                    }

                    if (maxDurationLimiter.acceptPcm(chunk.size)) {
                        notifyStoppedIfNeeded()
                        stop()
                        return@collect
                    }

                    if (vadDetector?.shouldStop(leveled.leveledPcm, leveled.leveledPcm.size) == true) {
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
                    onTerminal(
                        Source.PRIMARY,
                        Terminal.Error(context.getString(R.string.error_audio_error, t.message ?: ""))
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

    private fun appendPcmToPrimaryAndBuffer(pcm: ByteArray, sourceLabel: String): Boolean {
        try {
            synchronized(pcmLock) {
                val remaining = (maxBufferedPcmBytes - pcmBuffer.size()).coerceAtLeast(0)
                val allowed = minOf(pcm.size, remaining)
                if (allowed > 0) {
                    pcmBuffer.write(pcm, 0, allowed)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "buffer PCM failed ($sourceLabel)", t)
        }
        val capReached = maxBufferedPcmBytes > 0 &&
            synchronized(pcmLock) {
                pcmBuffer.size() >= maxBufferedPcmBytes
            }

        try {
            primaryConsumer?.appendPcm(pcm, SAMPLE_RATE, CHANNELS)
        } catch (t: Throwable) {
            Log.w(TAG, "primary appendPcm failed ($sourceLabel)", t)
        }
        return capReached
    }

    private fun processedBufferedPcmOrError(errorSource: Source): ByteArray? {
        val pcm = synchronized(pcmLock) {
            pcmBuffer.toByteArray()
        }
        if (pcm.isEmpty()) return null
        val processed = hooks.processBufferedPcm(pcm)
        if (processed.droppedAsEmptyAudio) {
            onTerminal(errorSource, Terminal.Error(context.getString(R.string.error_audio_empty_skipped)))
            return null
        }
        return processed.pcm
    }

    private fun triggerBackup(reason: String) {
        val shouldStart = synchronized(stateLock) {
            !terminalCoordinator.terminalDelivered && backupTriggered.compareAndSet(false, true)
        }
        if (!shouldStart) return
        Log.d(TAG, "Trigger lazy local backup: reason=$reason vendor=$backupVendor")
        notifyBackupLoading()
        var backupLoadStartUptimeMs = 0L
        var backupLoadRecorded = false
        val request = AsrLocalVendorPreloadRequest.create(
            context = context,
            prefs = prefs,
            onLoadStart = {
                backupLoadStartUptimeMs = safeUptimeMillis()
                notifyBackupLoading()
            },
            onLoadDone = {
                if (!terminalCoordinator.terminalDelivered) {
                    if (!backupLoadRecorded) {
                        backupLoadRecorded = true
                        recordBackupLoadDuration(backupLoadStartUptimeMs)
                    }
                    notifyBackupRecognizing()
                    maybeFeedBackupIfReady(forceReady = true)
                }
            },
            suppressToastOnStart = true,
            forImmediateUse = true
        )
        val accepted = synchronized(stateLock) {
            if (terminalCoordinator.terminalDelivered) {
                false
            } else {
                try {
                    hooks.preloadBackupVendor(request)
                } catch (t: Throwable) {
                    Log.e(TAG, "backup preload failed", t)
                    false
                }
            }
        }
        if (!accepted) {
            onTerminal(Source.BACKUP, Terminal.Error("backup preload failed"))
            return
        }
        hooks.residencyManager.onBackupUsed(
            primaryVendor = primaryVendor,
            mode = hooks.localBackupResidency()
        )
        if (hooks.isBackupReady()) {
            if (!backupLoadRecorded) {
                backupLoadRecorded = true
                recordBackupLoadDuration(backupLoadStartUptimeMs)
            }
            notifyBackupRecognizing()
            maybeFeedBackupIfReady(forceReady = true)
        }
    }

    private fun recordBackupLoadDuration(startUptimeMs: Long) {
        if (startUptimeMs <= 0L) return
        val doneUptimeMs = safeUptimeMillis()
        if (doneUptimeMs < startUptimeMs) return
        try {
            prefs.recordAsrRuntimeLoad(
                vendor = backupVendor,
                loadMs = (doneUptimeMs - startUptimeMs).coerceAtLeast(1L)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "record backup load duration failed", t)
        }
    }

    private fun safeUptimeMillis(): Long = try {
        SystemClock.uptimeMillis()
    } catch (_: Throwable) {
        0L
    }

    private fun maybeFeedBackupIfReady(forceReady: Boolean = false) {
        if (terminalCoordinator.terminalDelivered) return
        if (!stopRequested.get()) return
        if (!backupTriggered.get()) return
        if (!backupFed.compareAndSet(false, true)) return
        val ready = forceReady || hooks.isBackupReady()
        if (!ready) {
            backupFed.set(false)
            return
        }

        val processed = processedBufferedPcmOrError(Source.BACKUP)
        if (processed == null) {
            backupFed.set(false)
            return
        }
        val engine = try {
            hooks.createBackupEngine(backupListener)
        } catch (t: Throwable) {
            Log.e(TAG, "backup create failed", t)
            null
        }
        if (engine == null) {
            onTerminal(Source.BACKUP, Terminal.Error("backup engine unavailable"))
            return
        }
        backupEngine = engine
        backupConsumer = engine as? ExternalPcmConsumer
        notifyBackupRecognizing()
        try {
            engine.start()
            backupConsumer?.appendPcm(processed, SAMPLE_RATE, CHANNELS)
            engine.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "backup recognize failed", t)
            onTerminal(Source.BACKUP, Terminal.Error(t.message ?: "backup recognize failed"))
        }
    }

    private fun scheduleBackupSwitchPlan() {
        if (terminalCoordinator.terminalDelivered) return
        cancelBackupPlanJobs()
        val plan = hooks.backupSwitchPlan(
            audioMsFromBytes(audioBytes.get()),
            isPrimaryStreamingForSwitchPlan(),
            (primaryEngine as? ProgressiveRetryStatusOwner)?.peekPendingRetryCount() ?: 0
        )
        val backupStartAtMs = plan.lazyBackupStartAtMs ?: plan.switchDeadlineMs
        if (!backupTriggered.get()) {
            backupStartJob = scope.launch {
                delay(backupStartAtMs.coerceAtLeast(0L))
                if (terminalCoordinator.terminalDelivered) return@launch
                triggerBackup("backup_start_plan")
            }
        }
        switchDeadlineJob = scope.launch {
            delay(plan.switchDeadlineMs.coerceAtLeast(0L))
            if (terminalCoordinator.terminalDelivered) return@launch
            synchronized(stateLock) {
                if (!terminalCoordinator.terminalDelivered) {
                    terminalCoordinator.dispatch(AsrBackupArbitrationEvent.SwitchDeadlineReached)
                }
            }
        }
    }

    private fun isPrimaryStreamingForSwitchPlan(): Boolean = primaryConsumer !is GenericPushFileAsrAdapter

    private fun onPrimarySuspicious(event: AsrBackupArbitrationEvent.PrimaryError) {
        synchronized(stateLock) {
            terminalCoordinator.dispatch(event)
        }
        triggerBackup("primary_immediate_error")
    }

    private fun onPrimaryImmediateFailure(message: String, reason: String) {
        val shouldStop = synchronized(stateLock) {
            if (terminalCoordinator.terminalDelivered) return
            terminalCoordinator.dispatch(
                AsrBackupArbitrationEvent.PrimaryError(
                    message = message,
                    strategy = AsrPrimaryErrorStrategy.ImmediateFailover
                )
            )
            terminalCoordinator.terminalDelivered
        }
        triggerBackup(reason)
        if (shouldStop) cleanupAfterTerminal()
    }

    private fun fatalCaptureError(message: String) {
        val shouldStop = synchronized(stateLock) {
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
        if (shouldStop) cleanupAfterTerminal()
    }

    private fun onTerminal(source: Source, terminal: Terminal) {
        val shouldStop = synchronized(stateLock) {
            if (terminalCoordinator.terminalDelivered) return
            val event = terminal.toArbitrationEvent(source)
            terminalCoordinator.dispatch(event)
            if (source == Source.PRIMARY && terminal.shouldTriggerBackup()) {
                triggerBackup("primary_terminal")
            }
            terminalCoordinator.terminalDelivered
        }
        if (shouldStop) cleanupAfterTerminal()
    }

    private fun Terminal.shouldTriggerBackup(): Boolean = when (this) {
        is Terminal.Final -> false
        is Terminal.Error ->
            AsrPrimaryErrorClassifier.classifyMessage(message) ==
                AsrPrimaryErrorStrategy.ImmediateFailover
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
        if (source == AsrBackupArbitrationSource.Backup) {
            backupRequestDurationHolder.get()?.let { onBackupRequestDuration?.invoke(it) }
        }
        cancelBackupPlanJobs()
        try {
            listener.onFinal(text)
        } catch (t: Throwable) {
            Log.e(TAG, "notify final failed", t)
        }
    }

    private fun deliverErrorFromCoordinator(message: String) {
        cancelBackupPlanJobs()
        notifyError(message)
    }

    private fun notifyError(message: String) {
        try {
            listener.onError(message)
        } catch (t: Throwable) {
            Log.e(TAG, "notify error failed", t)
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

    private fun notifyBackupLoading() {
        if (lastBackupStatus == "loading") return
        lastBackupStatus = "loading"
        try {
            (listener as? BackupAsrStatusListener)?.onBackupAsrLoading(backupVendor)
        } catch (t: Throwable) {
            Log.w(TAG, "notify backup loading failed", t)
        }
    }

    private fun notifyBackupRecognizing() {
        if (lastBackupStatus == "recognizing") return
        lastBackupStatus = "recognizing"
        try {
            (listener as? BackupAsrStatusListener)?.onBackupAsrRecognizing(backupVendor)
        } catch (t: Throwable) {
            Log.w(TAG, "notify backup recognizing failed", t)
        }
    }

    private fun cleanupAfterTerminal() {
        stopRequested.set(true)
        running.set(false)
        cancelBackupPlanJobs()
        try {
            audioJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel audio job failed in cleanupAfterTerminal", t)
        } finally {
            audioJob = null
        }
        cancelOrStopEngine(primaryEngine, "primary")
        cancelOrStopEngine(backupEngine, "backup")
        finishResidencySession()
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

    private fun cancelBackupPlanJobs() {
        try {
            backupStartJob?.cancel()
            switchDeadlineJob?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "cancel backup switch plan jobs failed", t)
        } finally {
            backupStartJob = null
            switchDeadlineJob = null
        }
    }

    private fun finishResidencySession() {
        if (!residencySessionFinished.compareAndSet(false, true)) return
        hooks.residencyManager.onSessionFinished(
            primaryVendor = primaryVendor,
            mode = hooks.localBackupResidency()
        )
    }

    private fun audioMsFromBytes(bytes: Long): Long {
        if (bytes <= 0L) return 0L
        val denom = SAMPLE_RATE.toLong() * CHANNELS.toLong() * 2L
        if (denom <= 0L) return 0L
        return (bytes * 1000L / denom).coerceAtLeast(0L)
    }

    private inner class EngineListener(
        private val source: Source
    ) : StreamingAsrEngine.Listener {
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

        // 子引擎可能在分段失败时提前停止；整场录音的 onStopped 只由外层采集生命周期发出。
        override fun onAmplitude(amplitude: Float) {
            if (source != Source.PRIMARY) return
            try {
                listener.onAmplitude(amplitude)
            } catch (t: Throwable) {
                Log.w(TAG, "notify amplitude failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "LazyLocalBackupAsr"
        private const val SAMPLE_RATE = 16000
        private const val CHANNELS = 1
        private const val BYTES_PER_SAMPLE = 2
        private const val CHUNK_MS = 200

        private fun defaultMaxBufferedPcmBytes(): Int {
            val bytes = SAMPLE_RATE.toLong() *
                CHANNELS.toLong() *
                BYTES_PER_SAMPLE.toLong() *
                Prefs.RECORDING_MAX_DURATION_MAX_MS.toLong() /
                1_000L
            return bytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }

        private fun realHooks(
            context: Context,
            scope: CoroutineScope,
            prefs: Prefs,
            primaryVendor: AsrVendor,
            backupVendor: AsrVendor,
            onPrimaryRequestDuration: ((Long) -> Unit)?,
            onBackupRequestDuration: ((Long) -> Unit)?,
            backupRequestDurationHolder: AtomicReference<Long?>,
            modePreferences: AsrEngineModePreferences,
            modelOverride: AsrRequestModelOverride
        ): LazyLocalBackupAsrEngineHooks {
            val pushPcmFactory = AsrPushPcmEngineFactory()
            return LazyLocalBackupAsrEngineHooks(
                onBackupRequestDuration = onBackupRequestDuration,
                createPrimaryEngine = { engineListener ->
                    pushPcmFactory.createOrNull(
                        context = context,
                        scope = scope,
                        prefs = prefs,
                        listener = engineListener,
                        vendor = primaryVendor,
                        invocationMode = AsrEngineInvocationMode.ParallelPrimary,
                        preferences = modePreferences,
                        source = AsrEngineConstructionSource.App,
                        onRequestDuration = onPrimaryRequestDuration,
                        applyAudioPreprocess = true,
                        modelOverride = modelOverride
                    )
                },
                createBackupEngine = { engineListener ->
                    pushPcmFactory.createOrNull(
                        context = context,
                        scope = scope,
                        prefs = prefs,
                        listener = engineListener,
                        vendor = backupVendor,
                        invocationMode = AsrEngineInvocationMode.ParallelBackup,
                        preferences = modePreferences,
                        source = AsrEngineConstructionSource.App,
                        onRequestDuration = { ms -> backupRequestDurationHolder.set(ms) },
                        applyAudioPreprocess = false,
                        modelOverride = modelOverride
                    )
                },
                preloadBackupVendor = { request ->
                    AsrLocalVendorLifecycles.preload(backupVendor, request)
                },
                isBackupReady = {
                    AsrLocalVendorLifecycles.isReady(backupVendor)
                },
                processBufferedPcm = { pcm ->
                    // 备用引擎仍在停录后一次性投喂整段缓冲；人声过滤与降噪在这里完成，
                    // backup adapter 以 applyAudioPreprocess=false 构造，避免重复处理。
                    val filtered = RecordedAudioVoiceFilter.processIfEnabled(
                        context = context,
                        prefs = prefs,
                        pcm = pcm,
                        sampleRate = SAMPLE_RATE,
                        chunkMillis = CHUNK_MS
                    )
                    if (filtered.droppedAsEmptyAudio) {
                        filtered
                    } else {
                        filtered.copy(
                            pcm = OfflineSpeechDenoiserManager.denoiseIfEnabled(
                                context = context,
                                prefs = prefs,
                                pcm = filtered.pcm,
                                sampleRate = SAMPLE_RATE
                            )
                        )
                    }
                },
                primaryNetworkGateEvent = {
                    AsrPrimaryNetworkGate.preflightEvent(
                        primaryVendor = primaryVendor,
                        networkAvailable = AsrPrimaryNetworkGate.isNetworkAvailable(context),
                        message = context.getString(R.string.asr_error_network_unavailable)
                    )
                },
                backupSwitchPlan = { audioMs, primaryStreaming, pendingRetryCount ->
                    val sensitivityTier = try {
                        prefs.backupAsrTimeoutSensitivity
                    } catch (_: Throwable) {
                        1
                    }
                    AsrTimeoutCalculator.calculateBackupSwitchPlan(
                        audioMs = audioMs,
                        primaryVendor = primaryVendor,
                        primaryStreaming = primaryStreaming,
                        backupStrategy = AsrParallelEngineDecision.UseLazyLocalBackup,
                        primaryStatsSnapshot = prefs.getAsrRuntimeStatsSnapshotOrNull(
                            primaryVendor,
                            audioMs
                        ),
                        backupStatsSnapshot = prefs.getAsrRuntimeStatsSnapshotOrNull(
                            backupVendor,
                            audioMs
                        ),
                        sensitivityTier = sensitivityTier,
                        pendingRetryCount = pendingRetryCount
                    )
                },
                residencyManager = LocalBackupResidencyManagers.forVendor(backupVendor),
                localBackupResidency = { prefs.backupAsrLocalResidency }
            )
        }
    }
}

internal object NoopLocalBackupResidencyController : LocalBackupResidencyController {
    override fun onSessionStarted() = Unit

    override fun onBackupUsed(primaryVendor: AsrVendor, mode: BackupAsrLocalResidency) = Unit

    override fun onSessionFinished(primaryVendor: AsrVendor, mode: BackupAsrLocalResidency) = Unit
}
