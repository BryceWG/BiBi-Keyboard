/**
 * 设置页录音测试状态与调试链路编排。
 *
 * 归属模块：ui/settings/compose/screens
 */
package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrEngineConstructionSource
import com.brycewg.asrkb.asr.AsrEngineInvocationMode
import com.brycewg.asrkb.asr.AsrParallelEngineDecision
import com.brycewg.asrkb.asr.AsrParallelEngineFactory
import com.brycewg.asrkb.asr.AsrPushPcmEngineFactory
import com.brycewg.asrkb.asr.AsrPushPcmEngineFamily
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.AudioCaptureManager
import com.brycewg.asrkb.asr.CancelableAsrEngine
import com.brycewg.asrkb.asr.ExternalPcmConsumer
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.asr.OfflineSpeechDenoiserManager
import com.brycewg.asrkb.asr.RecordedAudioVoiceFilter
import com.brycewg.asrkb.asr.RecordingAudioFocusController
import com.brycewg.asrkb.asr.RecordingAudioFocusLoss
import com.brycewg.asrkb.asr.StreamingAsrEngine
import com.brycewg.asrkb.asr.asrEngineModePreferencesSnapshot
import com.brycewg.asrkb.asr.isAsrVendorConfigured
import com.brycewg.asrkb.asr.preloadLocalAsrForImmediateUse
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptPreset
import com.brycewg.asrkb.util.AsrFinalFilters
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class RecordingTestUiState(
    val isRecording: Boolean = false,
    val isPlaying: Boolean = false,
    val isTranscribing: Boolean = false,
    val isAiProcessing: Boolean = false,
    val currentAsrVendor: AsrVendor = AsrVendor.Volc,
    val backupAsrVendor: AsrVendor? = null,
    val backupAsrStrategy: AsrParallelEngineDecision? = null,
    val asrMode: RecordingTestAsrMode = RecordingTestAsrMode.File,
    val selectedPromptId: String = "",
    val promptPresets: List<PromptPreset> = emptyList(),
    val pressWallTimeMs: Long? = null,
    val firstFrameWallTimeMs: Long? = null,
    val recordLatencyMs: Long? = null,
    val asrLatencyMs: Long? = null,
    val aiLatencyMs: Long? = null,
    val totalLatencyMs: Long? = null,
    val durationMs: Long = 0L,
    val currentDb: Float? = null,
    val peakDb: Float? = null,
    val silentPercent: Int? = null,
    val rawText: String = "",
    val aiText: String = "",
    val statusMessage: String? = null,
    val hasAudio: Boolean = false
) {
    val canPlay: Boolean get() = hasAudio && !isRecording && !isTranscribing && !isAiProcessing
    val canAiProcess: Boolean get() = rawText.isNotBlank() && !isRecording && !isTranscribing && !isAiProcessing
}

internal enum class RecordingTestAsrMode {
    PushPcm,
    File
}

internal class RecordingTestViewModel(
    private val appContext: Context,
    private val prefs: Prefs
) : ViewModel() {
    private val localizedContext get() = LocaleHelper.wrap(appContext)
    private val parallelEngineFactory = AsrParallelEngineFactory()
    private val pushPcmEngineFactory = AsrPushPcmEngineFactory()

    private val _uiState = MutableStateFlow(
        RecordingTestUiState(
            currentAsrVendor = prefs.asrVendor,
            backupAsrVendor = configuredBackupVendorOrNull(),
            backupAsrStrategy = configuredBackupStrategyOrNull(),
            asrMode = configuredRecordingTestAsrMode(),
            selectedPromptId = prefs.activePromptId,
            promptPresets = prefs.getPromptPresets()
        )
    )
    val uiState: StateFlow<RecordingTestUiState> = _uiState.asStateFlow()

    private var captureJob: Job? = null
    private val recordingAudioFocusController = RecordingAudioFocusController(appContext) { loss ->
        onRecordingAudioFocusLost(loss)
    }
    private var player: MediaPlayer? = null
    private var aiJob: Job? = null
    private var recognitionTimeoutJob: Job? = null
    private var activeAsrEngine: StreamingAsrEngine? = null
    private var activePcmConsumer: ExternalPcmConsumer? = null
    private var recognitionStopUptimeMs: Long? = null
    private var rawPcm = ByteArray(0)
    private var wavFile: File? = null
    private var firstFrameUptimeMs: Long? = null
    private var recordingStartUptimeMs: Long = 0L
    private var peakAbs: Int = 0
    fun refreshConfiguredAsr() {
        _uiState.update {
            it.copy(
                currentAsrVendor = prefs.asrVendor,
                backupAsrVendor = configuredBackupVendorOrNull(),
                backupAsrStrategy = configuredBackupStrategyOrNull(),
                asrMode = configuredRecordingTestAsrMode(),
                promptPresets = prefs.getPromptPresets(),
                selectedPromptId = prefs.activePromptId.takeIf { id -> id.isNotBlank() } ?: it.selectedPromptId
            )
        }
    }

    fun selectPromptPreset(id: String) {
        _uiState.update { it.copy(selectedPromptId = id) }
    }

    fun toggleRecording() {
        if (_uiState.value.isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    fun replay() {
        if (!_uiState.value.canPlay) return
        val file = wavFile ?: return
        stopPlayback()
        try {
            val mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stopPlayback()
                }
                prepare()
                start()
            }
            player = mediaPlayer
            _uiState.update { it.copy(isPlaying = true, statusMessage = null) }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to play recording test audio", t)
            stopPlayback()
            _uiState.update { it.copy(statusMessage = localizedContext.getString(R.string.recording_test_error_playback, t.message.orEmpty())) }
        }
    }

    fun processAi() {
        val state = _uiState.value
        val input = state.rawText
        if (input.isBlank() || state.isAiProcessing) return
        val prompt = state.promptPresets.firstOrNull { it.id == state.selectedPromptId }?.content
        aiJob = viewModelScope.launch {
            _uiState.update { it.copy(isAiProcessing = true, aiText = "", statusMessage = null) }
            val started = SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    AsrFinalFilters.applyWithAi(
                        context = localizedContext,
                        prefs = prefs,
                        input = input,
                        postProcessor = LlmPostProcessor(),
                        promptOverride = prompt,
                        forceAi = true
                    )
                }
            }
            val elapsed = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
            result.exceptionOrNull()?.let { t ->
                if (t is CancellationException) {
                    _uiState.update { it.copy(isAiProcessing = false) }
                    return@launch
                }
            }
            result.onSuccess { processed ->
                _uiState.update {
                    it.copy(
                        isAiProcessing = false,
                        aiText = processed.text,
                        aiLatencyMs = elapsed,
                        totalLatencyMs = sumLatencies(it.recordLatencyMs, it.asrLatencyMs, elapsed),
                        statusMessage = processed.errorMessage?.takeIf { msg -> !processed.ok && msg.isNotBlank() }
                    )
                }
            }.onFailure { t ->
                _uiState.update {
                    it.copy(
                        isAiProcessing = false,
                        aiLatencyMs = elapsed,
                        totalLatencyMs = sumLatencies(it.recordLatencyMs, it.asrLatencyMs, elapsed),
                        statusMessage = localizedContext.getString(R.string.recording_test_error_ai, t.message.orEmpty())
                    )
                }
            }
        }
    }

    fun releasePageResources() {
        val job = captureJob
        captureJob = null
        job?.cancel()
        aiJob?.cancel()
        aiJob = null
        stopActiveRecognition(cancelTimeout = true)
        stopPlayback()
        releaseRecordingResources()
        deleteWavFile()
        _uiState.update {
            it.copy(
                isRecording = false,
                isPlaying = false,
                isTranscribing = false,
                isAiProcessing = false,
                pressWallTimeMs = null,
                firstFrameWallTimeMs = null,
                recordLatencyMs = null,
                asrLatencyMs = null,
                aiLatencyMs = null,
                totalLatencyMs = null,
                durationMs = 0L,
                currentDb = null,
                peakDb = null,
                silentPercent = null,
                rawText = "",
                aiText = "",
                statusMessage = null,
                hasAudio = false
            )
        }
        viewModelScope.launch {
            try {
                job?.cancelAndJoin()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to release recording test capture on page exit", t)
            }
            rawPcm = ByteArray(0)
            firstFrameUptimeMs = null
            resetAudioStats()
        }
    }

    fun resetRecording() {
        if (_uiState.value.isRecording) return
        stopActiveRecognition(cancelTimeout = true)
        stopPlayback()
        rawPcm = ByteArray(0)
        deleteWavFile()
        firstFrameUptimeMs = null
        _uiState.update {
            it.copy(
                isPlaying = false,
                pressWallTimeMs = null,
                firstFrameWallTimeMs = null,
                recordLatencyMs = null,
                asrLatencyMs = null,
                aiLatencyMs = null,
                totalLatencyMs = null,
                durationMs = 0L,
                currentDb = null,
                peakDb = null,
                silentPercent = null,
                rawText = "",
                aiText = "",
                statusMessage = null,
                hasAudio = false
            )
        }
    }

    private fun startRecording() {
        stopPlayback()
        resetAudioStats()
        stopActiveRecognition(cancelTimeout = true)
        rawPcm = ByteArray(0)
        deleteWavFile()
        val engine = buildConfiguredPushPcmEngine(createConfiguredRecognitionListener())
        val pcmConsumer = engine as? ExternalPcmConsumer
        if (engine == null || pcmConsumer == null) {
            _uiState.update {
                it.copy(
                    statusMessage = localizedContext.getString(R.string.recording_test_error_vendor_unavailable),
                    currentAsrVendor = prefs.asrVendor,
                    backupAsrVendor = configuredBackupVendorOrNull(),
                    backupAsrStrategy = configuredBackupStrategyOrNull(),
                    asrMode = configuredRecordingTestAsrMode()
                )
            }
            return
        }
        activeAsrEngine = engine
        activePcmConsumer = pcmConsumer
        recognitionStopUptimeMs = null
        recordingStartUptimeMs = SystemClock.elapsedRealtime()
        val pressWallTime = System.currentTimeMillis()
        _uiState.update {
            it.copy(
                isRecording = true,
                isPlaying = false,
                isTranscribing = false,
                currentAsrVendor = prefs.asrVendor,
                backupAsrVendor = configuredBackupVendorOrNull(),
                backupAsrStrategy = configuredBackupStrategyOrNull(),
                asrMode = configuredRecordingTestAsrMode(),
                pressWallTimeMs = pressWallTime,
                firstFrameWallTimeMs = null,
                recordLatencyMs = null,
                asrLatencyMs = null,
                aiLatencyMs = null,
                totalLatencyMs = null,
                durationMs = 0L,
                currentDb = null,
                peakDb = null,
                silentPercent = null,
                rawText = "",
                aiText = "",
                statusMessage = null,
                hasAudio = false
            )
        }
        try {
            engine.start()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start recording test ASR engine", t)
            stopActiveRecognition(cancelTimeout = true)
            _uiState.update {
                it.copy(
                    isRecording = false,
                    statusMessage = localizedContext.getString(R.string.recording_test_error_transcribe, t.message.orEmpty())
                )
            }
            return
        }
        if (activeAsrEngine == null || !engine.isRunning) {
            stopActiveRecognition(cancelTimeout = true)
            _uiState.update { it.copy(isRecording = false) }
            return
        }

        if (prefs.duckMediaOnRecordEnabled) {
            recordingAudioFocusController.acquire()
        }
        try {
            preloadLocalAsrForImmediateUse(localizedContext, prefs)
        } catch (t: Throwable) {
            Log.w(TAG, "Recording test preparation failed", t)
        }

        captureJob = viewModelScope.launch(Dispatchers.IO) {
            val audioManager = AudioCaptureManager(appContext)
            val output = ByteArrayOutputStream()
            try {
                audioManager.startCapture().collect { chunk ->
                    val now = SystemClock.elapsedRealtime()
                    val first = firstFrameUptimeMs == null
                    if (first) {
                        firstFrameUptimeMs = now
                    }
                    output.write(chunk)
                    try {
                        activePcmConsumer?.appendPcm(chunk, RECORDING_TEST_SAMPLE_RATE, RECORDING_TEST_CHANNELS)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Failed to push recording test PCM to ASR", t)
                    }
                    val stats = updateAudioStats(chunk)
                    _uiState.update {
                        val latency = if (first) now - recordingStartUptimeMs else it.recordLatencyMs
                        it.copy(
                            firstFrameWallTimeMs = if (first) System.currentTimeMillis() else it.firstFrameWallTimeMs,
                            recordLatencyMs = latency,
                            totalLatencyMs = latency,
                            durationMs = durationMs(output.size()),
                            currentDb = stats.currentDb,
                            peakDb = dbFromAbs(peakAbs)
                        )
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    Log.d(TAG, "Recording test capture stopped")
                } else {
                    Log.w(TAG, "Recording test capture failed", t)
                    captureJob = null
                    stopActiveRecognition(cancelTimeout = true)
                    _uiState.update {
                        it.copy(
                            isRecording = false,
                            isTranscribing = false,
                            statusMessage = localizedContext.getString(R.string.recording_test_error_record, t.message.orEmpty())
                        )
                    }
                }
            } finally {
                rawPcm = output.toByteArray()
                releaseRecordingResources()
            }
        }
    }

    private fun stopRecording() {
        val job = captureJob
        captureJob = null
        viewModelScope.launch {
            try {
                job?.cancelAndJoin()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to stop recording test capture", t)
            }
            if (rawPcm.isEmpty()) {
                stopActiveRecognition(cancelTimeout = true)
            } else {
                stopConfiguredRecognitionForResult()
            }
            finalizeRecording()
        }
    }

    private suspend fun finalizeRecording() {
        val source = rawPcm
        if (source.isEmpty()) {
            _uiState.update {
                it.copy(
                    isRecording = false,
                    hasAudio = false,
                    statusMessage = localizedContext.getString(R.string.recording_test_error_empty_audio)
                )
            }
            return
        }
        val finalized = withContext(Dispatchers.IO) {
            val silenceAnalysis = RecordedAudioVoiceFilter.analyzeSilence(
                context = localizedContext,
                prefs = prefs,
                pcm = source,
                sampleRate = RECORDING_TEST_SAMPLE_RATE,
                chunkMillis = RECORDING_TEST_CHUNK_MS
            )
            val filtered = RecordedAudioVoiceFilter.processIfEnabled(
                context = localizedContext,
                prefs = prefs,
                pcm = source,
                sampleRate = RECORDING_TEST_SAMPLE_RATE,
                chunkMillis = RECORDING_TEST_CHUNK_MS
            )
            OfflineSpeechDenoiserManager.denoiseIfEnabled(
                context = localizedContext,
                prefs = prefs,
                pcm = if (filtered.droppedAsEmptyAudio) source else filtered.pcm,
                sampleRate = RECORDING_TEST_SAMPLE_RATE
            )
                .let { processed ->
                    FinalizedRecording(
                        processedPcm = processed,
                        silentPercent = silenceAnalysis?.silentPercent
                    )
                }
        }
        val file = withContext(Dispatchers.IO) {
            writeWavFile(finalized.processedPcm)
        }
        wavFile = file
        _uiState.update {
            it.copy(
                isRecording = false,
                hasAudio = finalized.processedPcm.isNotEmpty(),
                durationMs = durationMs(finalized.processedPcm.size),
                silentPercent = finalized.silentPercent ?: it.silentPercent,
                statusMessage = null
            )
        }
    }

    private fun createConfiguredRecognitionListener(): StreamingAsrEngine.Listener = object : StreamingAsrEngine.Listener {
        override fun onPartial(text: String) {
            if (activeAsrEngine == null) return
            _uiState.update {
                it.copy(rawText = text, statusMessage = null)
            }
        }

        override fun onFinal(text: String) {
            if (activeAsrEngine == null) return
            recognitionTimeoutJob?.cancel()
            recognitionTimeoutJob = null
            cancelCaptureOnRecognitionTerminal()
            val elapsed = recognitionStopUptimeMs
                ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
                ?: 0L
            stopActiveRecognition(cancelTimeout = false)
            _uiState.update {
                it.copy(
                    isRecording = false,
                    isTranscribing = false,
                    rawText = text,
                    asrLatencyMs = elapsed,
                    totalLatencyMs = sumLatencies(it.recordLatencyMs, elapsed, null),
                    statusMessage = null
                )
            }
        }

        override fun onError(message: String) {
            if (activeAsrEngine == null) return
            recognitionTimeoutJob?.cancel()
            recognitionTimeoutJob = null
            cancelCaptureOnRecognitionTerminal()
            stopActiveRecognition(cancelTimeout = false)
            _uiState.update {
                it.copy(
                    isRecording = false,
                    isTranscribing = false,
                    statusMessage = localizedContext.getString(R.string.recording_test_error_transcribe, message)
                )
            }
        }
    }

    private fun cancelCaptureOnRecognitionTerminal() {
        val job = captureJob
        captureJob = null
        try {
            job?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to cancel recording test capture after ASR terminal", t)
        }
    }

    private fun stopConfiguredRecognitionForResult() {
        val engine = activeAsrEngine ?: return
        recognitionStopUptimeMs = SystemClock.elapsedRealtime()
        _uiState.update {
            it.copy(
                isTranscribing = true,
                asrLatencyMs = null,
                aiLatencyMs = null,
                totalLatencyMs = it.recordLatencyMs
            )
        }
        try {
            engine.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to stop recording test ASR engine", t)
            stopActiveRecognition(cancelTimeout = true)
            _uiState.update {
                it.copy(
                    isTranscribing = false,
                    statusMessage = localizedContext.getString(R.string.recording_test_error_transcribe, t.message.orEmpty())
                )
            }
            return
        }
        recognitionTimeoutJob?.cancel()
        recognitionTimeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(RECORDING_TEST_TRANSCRIBE_TIMEOUT_MS)
            if (!_uiState.value.isTranscribing) return@launch
            recognitionTimeoutJob = null
            stopActiveRecognition(cancelTimeout = false)
            _uiState.update {
                it.copy(
                    isTranscribing = false,
                    statusMessage = localizedContext.getString(R.string.recording_test_error_transcribe_timeout)
                )
            }
        }
    }

    private fun stopActiveRecognition(cancelTimeout: Boolean) {
        if (cancelTimeout) {
            recognitionTimeoutJob?.cancel()
            recognitionTimeoutJob = null
        }
        val engine = activeAsrEngine
        activeAsrEngine = null
        activePcmConsumer = null
        recognitionStopUptimeMs = null
        try {
            val cancelable = engine as? CancelableAsrEngine
            if (cancelable != null) {
                cancelable.cancel()
            } else {
                engine?.stop()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release recording test ASR engine", t)
        }
    }

    private fun buildConfiguredPushPcmEngine(listener: StreamingAsrEngine.Listener): StreamingAsrEngine? {
        val primaryVendor = prefs.asrVendor
        val backupVendor = prefs.backupAsrVendor
        parallelEngineFactory.createOrNull(
            context = localizedContext,
            scope = viewModelScope,
            prefs = prefs,
            listener = listener,
            primaryVendor = primaryVendor,
            backupVendor = backupVendor,
            externalPcmInput = true
        )?.let { return it }

        if (!isAsrVendorConfigured(appContext, prefs, primaryVendor)) return null
        return pushPcmEngineFactory.create(
            context = localizedContext,
            scope = viewModelScope,
            prefs = prefs,
            listener = listener,
            vendor = primaryVendor,
            invocationMode = AsrEngineInvocationMode.RecordingTest,
            preferences = prefs.asrEngineModePreferencesSnapshot(),
            source = AsrEngineConstructionSource.App,
            onRequestDuration = null,
            applyAudioPreprocess = true
        )
    }

    private fun configuredBackupVendorOrNull(): AsrVendor? = configuredBackupPlanOrNull()?.backupVendor

    private fun configuredBackupStrategyOrNull(): AsrParallelEngineDecision? = configuredBackupPlanOrNull()?.decision

    private fun configuredBackupPlanOrNull() = parallelEngineFactory.resolvePlan(
        context = localizedContext,
        prefs = prefs,
        primaryVendor = prefs.asrVendor,
        backupVendor = prefs.backupAsrVendor,
        externalPcmInput = true
    ).takeIf { it.shouldUseBackupWrapper }

    private fun configuredRecordingTestAsrMode(): RecordingTestAsrMode = if (configuredBackupVendorOrNull() != null || resolvesRecordingTestAsPushPcm(prefs.asrVendor)) {
        RecordingTestAsrMode.PushPcm
    } else {
        RecordingTestAsrMode.File
    }

    private fun resolvesRecordingTestAsPushPcm(vendor: AsrVendor): Boolean = try {
        when (
            pushPcmEngineFactory.resolvePlan(
                vendor = vendor,
                invocationMode = AsrEngineInvocationMode.RecordingTest,
                preferences = prefs.asrEngineModePreferencesSnapshot(),
                source = AsrEngineConstructionSource.App
            ).family
        ) {
            AsrPushPcmEngineFamily.FileAdapter -> false
            AsrPushPcmEngineFamily.NativeStream,
            AsrPushPcmEngineFamily.LocalStream,
            AsrPushPcmEngineFamily.PseudoStream -> true
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Failed to resolve recording test ASR mode", t)
        false
    }

    private fun releaseRecordingResources() {
        recordingAudioFocusController.release()
    }

    private fun onRecordingAudioFocusLost(loss: RecordingAudioFocusLoss) {
        Log.w(TAG, "Recording test audio focus lost: $loss")
        viewModelScope.launch {
            if (_uiState.value.isRecording) {
                stopRecording()
            }
        }
    }

    private fun stopPlayback() {
        val mediaPlayer = player
        player = null
        try {
            mediaPlayer?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to stop recording test media player", t)
        }
        try {
            mediaPlayer?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release recording test media player", t)
        }
        _uiState.update { it.copy(isPlaying = false) }
    }

    private fun writeWavFile(pcm: ByteArray): File {
        val file = File(appContext.cacheDir, "recording_test_${System.currentTimeMillis()}.wav")
        FileOutputStream(file).use { it.write(pcmToWav(pcm)) }
        return file
    }

    private fun deleteWavFile() {
        try {
            wavFile?.delete()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to delete recording test wav", t)
        } finally {
            wavFile = null
        }
    }

    private fun resetAudioStats() {
        firstFrameUptimeMs = null
        peakAbs = 0
    }

    private fun updateAudioStats(chunk: ByteArray): AudioStats {
        var maxAbs = 0
        var sumSquares = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < chunk.size) {
            val lo = chunk[i].toInt() and 0xFF
            val hi = chunk[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val abs = kotlin.math.abs(sample)
            if (abs > maxAbs) maxAbs = abs
            sumSquares += sample.toDouble() * sample.toDouble()
            samples++
            i += 2
        }
        peakAbs = maxOf(peakAbs, maxAbs)
        val rms = if (samples > 0) kotlin.math.sqrt(sumSquares / samples) else 0.0
        return AudioStats(currentDb = dbFromRms(rms))
    }

    private fun durationMs(byteCount: Int): Long = byteCount / 2L * 1_000L / RECORDING_TEST_SAMPLE_RATE

    override fun onCleared() {
        captureJob?.cancel()
        captureJob = null
        aiJob?.cancel()
        aiJob = null
        stopActiveRecognition(cancelTimeout = true)
        stopPlayback()
        releaseRecordingResources()
        deleteWavFile()
        super.onCleared()
    }

    private data class AudioStats(val currentDb: Float?)

    private data class FinalizedRecording(
        val processedPcm: ByteArray,
        val silentPercent: Int?
    )

    private companion object {
        private const val TAG = "RecordingTestVM"
        private const val RECORDING_TEST_TRANSCRIBE_TIMEOUT_MS = 120_000L
    }
}

internal const val RECORDING_TEST_SAMPLE_RATE = 16000
internal const val RECORDING_TEST_CHANNELS = 1
internal const val RECORDING_TEST_CHUNK_MS = 200

internal val recordingTestTimeFormatter: DateTimeFormatter = DateTimeFormatter
    .ofPattern("HH:mm:ss.SSS")
    .withZone(ZoneId.systemDefault())

internal fun formatRecordingTestTimestamp(timestampMs: Long?): String = timestampMs?.let { recordingTestTimeFormatter.format(Instant.ofEpochMilli(it)) } ?: "--:--:--.---"

internal fun formatRecordingTestDuration(ms: Long?): String = when {
    ms == null -> "--"
    ms < 1_000L -> "${ms}ms"
    else -> String.format(java.util.Locale.US, "%.2fs", ms / 1_000.0)
}

private fun sumLatencies(recordMs: Long?, asrMs: Long?, aiMs: Long?): Long? {
    val values = listOfNotNull(recordMs, asrMs, aiMs)
    if (values.isEmpty()) return null
    return values.sum()
}

private fun dbFromRms(rms: Double): Float? {
    if (rms <= 0.0) return null
    return (20.0 * kotlin.math.log10(rms / Short.MAX_VALUE)).toFloat()
}

private fun dbFromAbs(abs: Int): Float? {
    if (abs <= 0) return null
    return (20.0 * kotlin.math.log10(abs.toDouble() / Short.MAX_VALUE)).toFloat()
}

private fun pcmToWav(pcm: ByteArray): ByteArray {
    val channels = 1
    val bitsPerSample = 16
    val byteRate = RECORDING_TEST_SAMPLE_RATE * channels * bitsPerSample / 8
    val dataSize = pcm.size
    val out = ByteArrayOutputStream(44 + dataSize)
    out.write("RIFF".toByteArray())
    out.write(intToBytesLE(dataSize + 36))
    out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray())
    out.write(intToBytesLE(16))
    out.write(shortToBytesLE(1))
    out.write(shortToBytesLE(channels))
    out.write(intToBytesLE(RECORDING_TEST_SAMPLE_RATE))
    out.write(intToBytesLE(byteRate))
    out.write(shortToBytesLE(channels * bitsPerSample / 8))
    out.write(shortToBytesLE(bitsPerSample))
    out.write("data".toByteArray())
    out.write(intToBytesLE(dataSize))
    out.write(pcm)
    return out.toByteArray()
}

private fun intToBytesLE(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

private fun shortToBytesLE(value: Int): ByteArray = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()
