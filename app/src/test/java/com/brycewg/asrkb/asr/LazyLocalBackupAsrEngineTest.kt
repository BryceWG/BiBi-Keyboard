// Tests lazy local backup ASR behavior through injected engine and lifecycle seams.
package com.brycewg.asrkb.asr

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LazyLocalBackupAsrEngineTest {
    @Before
    fun setUp() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("asr_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun startRunsPrimaryWithoutPreloadingOrStartingBackup() {
        val primary = FakeStreamingPcmEngine()
        val backup = FakeStreamingPcmEngine()
        val harness = Harness(primary = primary, backup = backup)
        val engine = harness.createEngine()

        engine.start()

        assertTrue(primary.started)
        assertFalse(backup.started)
        assertEquals(0, harness.preloadCount)
    }

    @Test
    fun fatalCaptureErrorDeliversErrorWithoutWaitingForLazyBackup() {
        val primary = FakeStreamingPcmEngine()
        val listener = RecordingListener()
        val harness = Harness(
            primary = primary,
            listener = listener,
            backupReady = true,
            completePreloadImmediately = true
        )
        val engine = harness.createEngine()

        engine.start()
        engine.invokePrivate("fatalCaptureError", "record permission denied")

        assertEquals(listOf("record permission denied (backup: record permission denied)"), listener.errors)
        assertTrue(listener.finals.isEmpty())
        assertEquals(0, harness.preloadCount)
        assertFalse(engine.isRunning)
    }

    @Test
    fun immediatePrimaryFailurePreloadsBackupAndFeedsCompleteBufferedPcmAfterStop() {
        val primary = FakeStreamingPcmEngine()
        val backup = FakeStreamingPcmEngine(finalOnStop = "backup text")
        val listener = RecordingListener()
        val residency = FakeResidencyController()
        val harness = Harness(
            primary = primary,
            backup = backup,
            listener = listener,
            residency = residency,
            backupReady = true,
            completePreloadImmediately = true
        )
        val engine = harness.createEngine()
        val first = byteArrayOf(1, 0, 2, 0)
        val second = byteArrayOf(3, 0, 4, 0)

        engine.start()
        engine.appendPcm(first, 16000, 1)
        primary.listener?.onError("HTTP 503 unavailable")
        assertEquals(1, harness.preloadCount)
        assertFalse(backup.started)

        engine.appendPcm(second, 16000, 1)
        engine.stop()

        assertTrue(backup.started)
        assertArrayEquals(first + second, backup.receivedPcm)
        assertArrayEquals(first + second, harness.lastProcessedPcm)
        assertEquals(listOf("backup text"), listener.finals)
        assertEquals(listOf("loading", "recognizing"), listener.backupStatuses)
        assertTrue(engine.wasLastResultFromBackup())
        assertEquals(1, residency.sessionStartedCount)
        assertEquals(listOf(AsrVendor.Volc to BackupAsrLocalResidency.OnDemand), residency.backupUses)
        assertEquals(listOf(AsrVendor.Volc to BackupAsrLocalResidency.OnDemand), residency.sessionFinishes)
    }

    @Test
    fun shortExternalPcmBelowBufferCapFeedsCompleteBufferedPcmAfterStop() {
        val primary = FakeStreamingPcmEngine()
        val backup = FakeStreamingPcmEngine(finalOnStop = "backup text")
        val harness = Harness(
            primary = primary,
            backup = backup,
            backupReady = true,
            completePreloadImmediately = true,
            maxBufferedPcmBytes = 12
        )
        val engine = harness.createEngine()
        val first = byteArrayOf(1, 0, 2, 0)
        val second = byteArrayOf(3, 0, 4, 0)

        engine.start()
        primary.listener?.onError("HTTP 503 unavailable")
        engine.appendPcm(first, 16000, 1)
        engine.appendPcm(second, 16000, 1)
        engine.stop()

        assertArrayEquals(first + second, primary.receivedPcm)
        assertArrayEquals(first + second, backup.receivedPcm)
        assertArrayEquals(first + second, harness.lastProcessedPcm)
    }

    @Test
    fun externalPcmAboveBufferCapCachesPrefixAndTriggersBackupProcessing() {
        val primary = FakeStreamingPcmEngine()
        val backup = FakeStreamingPcmEngine(finalOnStop = "backup text")
        val listener = RecordingListener()
        val harness = Harness(
            primary = primary,
            backup = backup,
            listener = listener,
            backupReady = true,
            completePreloadImmediately = true,
            maxBufferedPcmBytes = 6
        )
        val engine = harness.createEngine()
        val oversized = byteArrayOf(1, 0, 2, 0, 3, 0, 4, 0)

        engine.start()
        primary.listener?.onError("HTTP 503 unavailable")
        engine.appendPcm(oversized, 16000, 1)

        assertEquals(listOf(Unit), listener.stoppedEvents)
        assertArrayEquals(oversized, primary.receivedPcm)
        assertArrayEquals(byteArrayOf(1, 0, 2, 0, 3, 0), backup.receivedPcm)
        assertArrayEquals(byteArrayOf(1, 0, 2, 0, 3, 0), harness.lastProcessedPcm)
        assertEquals(listOf("backup text"), listener.finals)
        assertTrue(engine.wasLastResultFromBackup())
    }

    @Test
    fun missingPrimaryEnginePreloadsBackupAndDeliversBackupFinalAfterStop() {
        val backup = FakeStreamingPcmEngine(finalOnStop = "backup text")
        val listener = RecordingListener()
        val harness = Harness(
            primaryConstructs = false,
            backup = backup,
            listener = listener,
            backupReady = true,
            completePreloadImmediately = true
        )
        val engine = harness.createEngine()
        val first = byteArrayOf(11, 0, 12, 0)
        val second = byteArrayOf(13, 0, 14, 0)

        engine.start()

        assertTrue(engine.isRunning)
        assertEquals(1, harness.preloadCount)
        assertTrue(listener.errors.isEmpty())

        engine.appendPcm(first, 16000, 1)
        engine.appendPcm(second, 16000, 1)
        engine.stop()

        assertTrue(backup.started)
        assertArrayEquals(first + second, backup.receivedPcm)
        assertArrayEquals(first + second, harness.lastProcessedPcm)
        assertEquals(listOf("backup text"), listener.finals)
        assertTrue(engine.wasLastResultFromBackup())
    }

    @Test
    fun primaryStartFailurePreloadsBackupAndDeliversBackupFinalAfterStop() {
        val primary = FakeStreamingPcmEngine(throwOnStart = true)
        val backup = FakeStreamingPcmEngine(finalOnStop = "backup text")
        val listener = RecordingListener()
        val harness = Harness(
            primary = primary,
            backup = backup,
            listener = listener,
            backupReady = true,
            completePreloadImmediately = true
        )
        val engine = harness.createEngine()
        val pcm = byteArrayOf(21, 0, 22, 0)

        engine.start()

        assertTrue(engine.isRunning)
        assertEquals(1, harness.preloadCount)
        assertTrue(listener.errors.isEmpty())

        engine.appendPcm(pcm, 16000, 1)
        engine.stop()

        assertArrayEquals(pcm, backup.receivedPcm)
        assertEquals(listOf("backup text"), listener.finals)
        assertTrue(engine.wasLastResultFromBackup())
    }

    @Test
    fun cancelFinishesResidencySessionAfterBackupWasTriggered() {
        val primary = FakeStreamingPcmEngine()
        val backup = FakeStreamingPcmEngine()
        val residency = FakeResidencyController()
        val harness = Harness(
            primary = primary,
            backup = backup,
            residency = residency,
            backupReady = true,
            completePreloadImmediately = true
        )
        val engine = harness.createEngine()

        engine.start()
        primary.listener?.onError("HTTP 500")
        engine.cancel()

        assertEquals(1, residency.sessionStartedCount)
        assertEquals(listOf(AsrVendor.Volc to BackupAsrLocalResidency.OnDemand), residency.backupUses)
        assertEquals(listOf(AsrVendor.Volc to BackupAsrLocalResidency.OnDemand), residency.sessionFinishes)
    }

    @Test
    fun primaryFinalWinsAfterLazyBackupHasStarted() {
        val primary = FakeStreamingPcmEngine()
        val backup = FakeStreamingPcmEngine()
        val listener = RecordingListener()
        val harness = Harness(
            primary = primary,
            backup = backup,
            listener = listener,
            backupReady = true,
            completePreloadImmediately = true
        )
        val engine = harness.createEngine()

        engine.start()
        engine.appendPcm(byteArrayOf(9, 0), 16000, 1)
        primary.listener?.onError("HTTP 500")
        engine.stop()
        primary.listener?.onFinal("primary text")
        backup.listener?.onFinal("late backup text")

        assertEquals(listOf("primary text"), listener.finals)
        assertFalse(engine.wasLastResultFromBackup())
    }

    @Test
    fun backupStartPlanTriggersBackupWhenPrimaryStaysPending() {
        val primary = FakeStreamingPcmEngine()
        val backup = FakeStreamingPcmEngine(finalOnStop = "backup text")
        val listener = RecordingListener()
        val harness = Harness(
            primary = primary,
            backup = backup,
            listener = listener,
            backupReady = true,
            completePreloadImmediately = true,
            backupStartAtMs = 0L,
            switchDeadlineMs = 0L
        )
        val engine = harness.createEngine()

        engine.start()
        engine.appendPcm(byteArrayOf(7, 0), 16000, 1)
        engine.stop()
        Thread.sleep(80)

        assertEquals(1, harness.preloadCount)
        assertEquals(listOf("backup text"), listener.finals)
        assertTrue(engine.wasLastResultFromBackup())
    }

    @Test
    fun backupPreloadFailureAfterBackupStartPlanStillAllowsPrimaryFinalBeforeDeadline() {
        val primary = FakeStreamingPcmEngine()
        val listener = RecordingListener()
        val harness = Harness(
            primary = primary,
            listener = listener,
            preloadAccepted = false,
            backupStartAtMs = 0L,
            switchDeadlineMs = 1_000L
        )
        val engine = harness.createEngine()

        engine.start()
        engine.appendPcm(byteArrayOf(8, 0), 16000, 1)
        engine.stop()
        Thread.sleep(80)
        primary.listener?.onFinal("primary text")

        assertEquals(1, harness.preloadCount)
        assertEquals(listOf("primary text"), listener.finals)
        assertTrue(listener.errors.isEmpty())
        assertFalse(engine.wasLastResultFromBackup())
    }

    @Test
    fun primaryFinalBeforeBackupStartPlanCancelsLazyBackup() {
        val primary = FakeStreamingPcmEngine()
        val backup = FakeStreamingPcmEngine(finalOnStop = "backup text")
        val listener = RecordingListener()
        val harness = Harness(
            primary = primary,
            backup = backup,
            listener = listener,
            backupReady = true,
            completePreloadImmediately = true,
            backupStartAtMs = 200L,
            switchDeadlineMs = 400L
        )
        val engine = harness.createEngine()

        engine.start()
        engine.appendPcm(byteArrayOf(10, 0), 16000, 1)
        engine.stop()
        primary.listener?.onFinal("primary text")
        Thread.sleep(260)

        assertEquals(0, harness.preloadCount)
        assertEquals(listOf("primary text"), listener.finals)
        assertFalse(engine.wasLastResultFromBackup())
    }

    @Test
    fun blankPrimaryFinalDoesNotPreloadOrStartLazyBackup() {
        val primary = FakeStreamingPcmEngine()
        val backup = FakeStreamingPcmEngine(finalOnStop = "backup text")
        val listener = RecordingListener()
        val harness = Harness(
            primary = primary,
            backup = backup,
            listener = listener,
            backupReady = true,
            completePreloadImmediately = true
        )
        val engine = harness.createEngine()

        engine.start()
        primary.listener?.onFinal("")

        assertEquals(0, harness.preloadCount)
        assertFalse(backup.started)
        assertEquals(listOf(""), listener.finals)
        assertTrue(listener.errors.isEmpty())
        assertFalse(engine.wasLastResultFromBackup())
    }

    @Test
    fun primaryEmptyResultErrorCompletesAsBlankFinalWithoutStartingLazyBackup() {
        val primary = FakeStreamingPcmEngine()
        val backup = FakeStreamingPcmEngine(finalOnStop = "backup text")
        val listener = RecordingListener()
        val harness = Harness(
            primary = primary,
            backup = backup,
            listener = listener,
            backupReady = true,
            completePreloadImmediately = true,
            backupStartAtMs = 0L,
            switchDeadlineMs = 0L
        )
        val engine = harness.createEngine()
        val context = ApplicationProvider.getApplicationContext<Context>()

        engine.start()
        primary.listener?.onError(context.getString(R.string.error_asr_empty_result))
        Thread.sleep(80)

        assertEquals(0, harness.preloadCount)
        assertFalse(backup.started)
        assertEquals(listOf(""), listener.finals)
        assertTrue(listener.errors.isEmpty())
        assertFalse(engine.wasLastResultFromBackup())
    }

    @Test
    fun primaryEmptyAudioSkippedErrorIsNotConvertedToBlankFinal() {
        val primary = FakeStreamingPcmEngine()
        val backup = FakeStreamingPcmEngine(finalOnStop = "backup text")
        val listener = RecordingListener()
        val harness = Harness(
            primary = primary,
            backup = backup,
            listener = listener,
            backupReady = true,
            completePreloadImmediately = true,
            backupStartAtMs = 0L,
            switchDeadlineMs = 0L
        )
        val engine = harness.createEngine()
        val context = ApplicationProvider.getApplicationContext<Context>()

        engine.start()
        primary.listener?.onError(context.getString(R.string.error_audio_empty_skipped))
        Thread.sleep(80)

        assertEquals(0, harness.preloadCount)
        assertFalse(backup.started)
        assertTrue(listener.finals.isEmpty())
        assertTrue(listener.errors.isEmpty())
        assertFalse(engine.wasLastResultFromBackup())
    }

    @Test
    fun backupFinalBeforeDeadlineIsCachedAndPrimaryFinalStillWins() {
        val primary = FakeStreamingPcmEngine()
        val backup = FakeStreamingPcmEngine(finalOnStop = "backup text")
        val listener = RecordingListener()
        val harness = Harness(
            primary = primary,
            backup = backup,
            listener = listener,
            backupReady = true,
            completePreloadImmediately = true,
            backupStartAtMs = 0L,
            switchDeadlineMs = 250L
        )
        val engine = harness.createEngine()

        engine.start()
        engine.appendPcm(byteArrayOf(12, 0), 16000, 1)
        engine.stop()
        Thread.sleep(80)

        assertEquals(1, harness.preloadCount)
        assertTrue(listener.finals.isEmpty())

        primary.listener?.onFinal("primary text")
        Thread.sleep(220)

        assertEquals(listOf("primary text"), listener.finals)
        assertFalse(engine.wasLastResultFromBackup())
    }

    @Test
    fun cachedBackupFinalDeliversWhenSwitchDeadlineArrives() {
        val primary = FakeStreamingPcmEngine()
        val backup = FakeStreamingPcmEngine(finalOnStop = "backup text")
        val listener = RecordingListener()
        val harness = Harness(
            primary = primary,
            backup = backup,
            listener = listener,
            backupReady = true,
            completePreloadImmediately = true,
            backupStartAtMs = 0L,
            switchDeadlineMs = 40L
        )
        val engine = harness.createEngine()

        engine.start()
        engine.appendPcm(byteArrayOf(14, 0), 16000, 1)
        engine.stop()
        Thread.sleep(100)

        assertEquals(listOf("backup text"), listener.finals)
        assertTrue(engine.wasLastResultFromBackup())
    }

    @Test
    fun emptyBufferBackupFeedRollsBackSoLaterReadyRetryCanFeedBackup() {
        val primary = FakeStreamingPcmEngine()
        val backup = FakeStreamingPcmEngine(finalOnStop = "backup text")
        val listener = RecordingListener()
        val harness = Harness(
            primary = primary,
            backup = backup,
            listener = listener,
            backupReady = true,
            completePreloadImmediately = true,
            backupStartAtMs = 0L,
            switchDeadlineMs = 0L
        )
        val engine = harness.createEngine()

        engine.start()
        primary.listener?.onError("HTTP 503 unavailable")
        engine.stop()

        assertFalse(backup.started)
        engine.bufferedPcmForTest().write(byteArrayOf(16, 0))
        engine.invokePrivate("maybeFeedBackupIfReady", true)

        assertArrayEquals(byteArrayOf(16, 0), backup.receivedPcm)
        assertEquals(listOf("backup text"), listener.finals)
        assertTrue(engine.wasLastResultFromBackup())
    }

    @Test
    fun exposesBackupAwareMetadata() {
        val engine = Harness().createEngine()

        assertSame(AsrVendor.Volc, engine.primaryVendor)
        assertSame(AsrVendor.SenseVoice, engine.backupVendor)
        assertSame(AsrParallelEngineDecision.UseLazyLocalBackup, engine.backupStrategy)
    }

    @Test
    fun recordsBackupModelLoadDurationWhenLazyPreloadCompletes() {
        val primary = FakeStreamingPcmEngine()
        val harness = Harness(
            primary = primary,
            backupReady = true,
            completePreloadImmediately = true
        )
        val engine = harness.createEngine()

        engine.start()
        primary.listener?.onError("HTTP 500")

        val prefs = Prefs(ApplicationProvider.getApplicationContext())
        val snapshot = prefs.getAsrRuntimeStatsSnapshot(AsrVendor.SenseVoice, 1_000L)
        assertEquals(1, snapshot.loadSampleCount)
    }

    private class Harness(
        private val primaryConstructs: Boolean = true,
        private val primary: FakeStreamingPcmEngine = FakeStreamingPcmEngine(),
        private val backup: FakeStreamingPcmEngine = FakeStreamingPcmEngine(),
        private val listener: RecordingListener = RecordingListener(),
        private val residency: FakeResidencyController = FakeResidencyController(),
        private var backupReady: Boolean = false,
        private val completePreloadImmediately: Boolean = false,
        private val preloadAccepted: Boolean = true,
        private val backupStartAtMs: Long = 0L,
        private val switchDeadlineMs: Long = 0L,
        private val maxBufferedPcmBytes: Int = Int.MAX_VALUE
    ) {
        var preloadCount: Int = 0
            private set
        var lastProcessedPcm: ByteArray = byteArrayOf()
            private set

        fun createEngine(): LazyLocalBackupAsrEngine {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val prefs = Prefs(context)
            return LazyLocalBackupAsrEngine(
                context = context,
                scope = CoroutineScope(Dispatchers.Unconfined),
                prefs = prefs,
                listener = listener,
                primaryVendor = AsrVendor.Volc,
                backupVendor = AsrVendor.SenseVoice,
                externalPcmInput = true,
                maxBufferedPcmBytes = maxBufferedPcmBytes,
                hooks = LazyLocalBackupAsrEngineHooks(
                    createPrimaryEngine = { engineListener ->
                        if (!primaryConstructs) {
                            null
                        } else {
                            primary.listener = engineListener
                            primary
                        }
                    },
                    createBackupEngine = { engineListener ->
                        backup.listener = engineListener
                        backup
                    },
                    preloadBackupVendor = { request ->
                        preloadCount += 1
                        request.onLoadStart?.invoke()
                        if (completePreloadImmediately) {
                            backupReady = true
                            request.onLoadDone?.invoke()
                        }
                        preloadAccepted
                    },
                    isBackupReady = { backupReady },
                    processBufferedPcm = { pcm ->
                        lastProcessedPcm = pcm
                        RecordedAudioVoiceFilter.Result(
                            pcm = pcm,
                            hasSpeech = pcm.isNotEmpty(),
                            droppedAsEmptyAudio = false,
                            originalDurationMs = 0L,
                            outputDurationMs = 0L
                        )
                    },
                    backupSwitchPlan = { _, _, _ ->
                        BackupSwitchPlan(
                            switchDeadlineMs = switchDeadlineMs,
                            usedStaticFallback = true,
                            baselineMs = switchDeadlineMs,
                            audioLengthAdjustmentMs = 0L,
                            primaryModeAdjustmentMs = 0L,
                            lazyBackupStartAtMs = backupStartAtMs,
                            lazyEstimatedBackupReadyMs = 0L,
                            lazyResidencyFactor = 0.0,
                            lazyMinPrimaryWindowMs = 0L
                        )
                    },
                    residencyManager = residency,
                    localBackupResidency = { BackupAsrLocalResidency.OnDemand }
                )
            )
        }
    }

    private class FakeResidencyController : LocalBackupResidencyController {
        var sessionStartedCount: Int = 0
            private set
        val backupUses = mutableListOf<Pair<AsrVendor, BackupAsrLocalResidency>>()
        val sessionFinishes = mutableListOf<Pair<AsrVendor, BackupAsrLocalResidency>>()

        override fun onSessionStarted() {
            sessionStartedCount += 1
        }

        override fun onBackupUsed(primaryVendor: AsrVendor, mode: BackupAsrLocalResidency) {
            backupUses += primaryVendor to mode
        }

        override fun onSessionFinished(primaryVendor: AsrVendor, mode: BackupAsrLocalResidency) {
            sessionFinishes += primaryVendor to mode
        }
    }

    private class FakeStreamingPcmEngine(
        private val finalOnStop: String? = null,
        private val throwOnStart: Boolean = false
    ) : StreamingAsrEngine,
        ExternalPcmConsumer,
        CancelableAsrEngine {
        var listener: StreamingAsrEngine.Listener? = null
        var started: Boolean = false
        var stopped: Boolean = false
        var receivedPcm: ByteArray = byteArrayOf()

        override val isRunning: Boolean
            get() = started && !stopped

        override fun start() {
            if (throwOnStart) error("primary start failed")
            started = true
            stopped = false
        }

        override fun stop() {
            stopped = true
            finalOnStop?.let { listener?.onFinal(it) }
        }

        override fun cancel() {
            stopped = true
        }

        override fun appendPcm(pcm: ByteArray, sampleRate: Int, channels: Int) {
            receivedPcm += pcm
        }
    }

    private fun Any.invokePrivate(name: String, value: String) {
        val method = javaClass.getDeclaredMethod(name, String::class.java)
        method.isAccessible = true
        method.invoke(this, value)
    }

    private fun Any.invokePrivate(name: String, value: Boolean) {
        val method = javaClass.getDeclaredMethod(name, Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(this, value)
    }

    private fun Any.bufferedPcmForTest(): ByteArrayOutputStream {
        val field = javaClass.getDeclaredField("pcmBuffer")
        field.isAccessible = true
        return field.get(this) as ByteArrayOutputStream
    }

    private class RecordingListener :
        StreamingAsrEngine.Listener,
        BackupAsrStatusListener {
        val finals = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val stoppedEvents = mutableListOf<Unit>()
        val backupStatuses = mutableListOf<String>()

        override fun onFinal(text: String) {
            finals += text
        }

        override fun onError(message: String) {
            errors += message
        }

        override fun onStopped() {
            stoppedEvents += Unit
        }

        override fun onBackupAsrLoading(backupVendor: AsrVendor) {
            backupStatuses += "loading"
        }

        override fun onBackupAsrRecognizing(backupVendor: AsrVendor) {
            backupStatuses += "recognizing"
        }
    }
}
