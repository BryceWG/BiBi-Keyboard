// VAD Input Leveler 开源端到端验证护栏。
package com.brycewg.asrkb.asr

import com.brycewg.asrkb.api.recognitionServiceRmsFromAmplitude
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourceVadEndToEndVerificationTest {
    @Test
    fun stableAmplitudeStaysInPublicCallbackRangeForRepresentativeInputs() {
        val leveler = VadInputLeveler()
        val inputs = listOf(
            ByteArray(CHUNK_BYTES),
            sinePcm(rms = 24.0),
            sinePcm(rms = 260.0),
            sinePcm(rms = 5_200.0),
            constantPcm(value = 30_000)
        )

        repeat(8) {
            inputs.forEach { pcm ->
                val result = leveler.processPcm16Le(pcm)
                assertTrue(
                    "stableAmplitude ${result.stableAmplitude} should be in 0..1",
                    result.stableAmplitude in 0.0f..1.0f
                )
                assertTrue(result.stats.stableAmplitude in 0.0f..1.0f)
            }
        }
    }

    @Test
    fun lowAndHighRecordedInputsReplayToComparableLeveledEnergy() {
        val low = repeatPcm(sinePcm(rms = 260.0), count = 24)
        val high = repeatPcm(sinePcm(rms = 5_200.0), count = 24)

        val lowLast = VadInputLeveler().replayPcm16Le(low, chunkBytes = CHUNK_BYTES).last()
        val highLast = VadInputLeveler().replayPcm16Le(high, chunkBytes = CHUNK_BYTES).last()

        assertTrue(lowLast.stats.speechCandidate)
        assertTrue(highLast.stats.speechCandidate)
        assertTrue(abs(lowLast.stats.leveledRms - highLast.stats.leveledRms) < 700.0)
        assertTrue(lowLast.stableAmplitude in 0.0f..1.0f)
        assertTrue(highLast.stableAmplitude in 0.0f..1.0f)
    }

    @Test
    fun recognitionServiceRmsMappingSemanticsAreUnchanged() {
        assertEquals(-2.0f, recognitionServiceRmsFromAmplitude(0.0f), 0.0f)
        assertEquals(4.0f, recognitionServiceRmsFromAmplitude(0.5f), 0.0f)
        assertEquals(10.0f, recognitionServiceRmsFromAmplitude(1.0f), 0.0f)
    }

    @Test
    fun asrInputsRemainRawWhileVadAndAmplitudeUseLeveler() {
        val asrSources = listOf(
            "BaseFileAsrEngine.kt",
            "DashscopeStreamAsrEngine.kt",
            "ElevenLabsStreamAsrEngine.kt",
            "GenericPushFileAsrAdapter.kt",
            "LocalModelPseudoStreamAsrEngine.kt",
            "OpenAiRealtimeAsrEngine.kt",
            "ParallelAsrEngine.kt",
            "PushPcmPseudoStreamAsrEngine.kt",
            "SonioxStreamAsrEngine.kt",
            "VolcStreamAsrEngine.kt",
            "XAsrStreamAsrEngine.kt"
        ).associateWith { mainSource("asr/$it") }

        val joined = asrSources.values.joinToString("\n")
        assertFalse(joined.contains("currentSeg.write(leveled"))
        assertFalse(joined.contains("currentPcm.write(leveled"))
        assertFalse(joined.contains("sessionBuffer.write(leveled"))
        assertFalse(joined.contains("segmentBuffer.write(leveled"))
        assertFalse(joined.contains("appendPcmToConsumers(leveled"))
        assertFalse(joined.contains("sendAudioFrame(leveled"))
        assertFalse(joined.contains("sendAudioChunk(leveled"))
        assertFalse(joined.contains("deliverChunk(s, leveled"))
        assertFalse(joined.contains("appendPrebuffer(leveled"))
        assertFalse(joined.contains("recognizeFromPcm(leveled"))

        assertTrue(joined.contains("listener.onAmplitude(leveled.stableAmplitude)"))
        assertTrue(joined.contains("shouldStop(leveled.leveledPcm"))
        assertTrue(asrSources.getValue("BaseFileAsrEngine.kt").contains("currentSeg.write(audioChunk)"))
        assertTrue(asrSources.getValue("PushPcmPseudoStreamAsrEngine.kt").contains("sessionBuffer.write(pcm)"))
        assertTrue(asrSources.getValue("ParallelAsrEngine.kt").contains("appendPcmToConsumers(chunk, sourceLabel = \"capture\")"))
    }

    @Test
    fun warmupBadSourceAndOfflineRawProtectionStayRaw() {
        val audioCaptureManager = mainSource("asr/AudioCaptureManager.kt")
        val voiceFilter = mainSource("asr/RecordedAudioVoiceFilter.kt")

        assertFalse(audioCaptureManager.contains("VadInputLeveler"))
        assertTrue(audioCaptureManager.contains("reads < WARMUP_MAX_READS"))
        assertTrue(audioCaptureManager.contains("if (stats.countAboveThreshold > 0)"))
        assertTrue(audioCaptureManager.contains("probedBytes.takeIf { it.isNotEmpty() }"))
        assertTrue(voiceFilter.contains("val rawEnergy = measureEnergy(chunk, chunk.size)"))
        assertTrue(voiceFilter.contains("marks.all { it.isRawBadSourceLevel() }"))
        assertTrue(voiceFilter.contains("filterLongNonContentRuns(pcm, marks)"))
    }

    @Test
    fun externalCallbacksKeepZeroToOneContractAndExistingMapping() {
        val recognition = mainSource("api/AsrRecognitionService.kt")
        val externalSession = mainSource("api/ExternalSpeechSession.kt")
        val externalService = mainSource("api/ExternalSpeechService.kt")
        val floatingSession = mainSource("ui/floatingball/AsrSessionManager.kt")
        val floatingController = mainSource("ui/floating/FloatingAsrInteractionController.kt")

        assertTrue(recognition.contains("recognitionServiceRmsFromAmplitude(amplitude)"))
        assertTrue(recognition.contains("callback.rmsChanged(rms)"))
        assertTrue(externalSession.contains("callbacks.onAmplitude(id, amplitude)"))
        assertTrue(externalService.contains("data.writeFloat(amp)"))
        assertTrue(floatingSession.contains("listener.onAmplitude(amplitude)"))
        assertTrue(floatingController.contains("viewManager.updateAmplitude(nextAmplitude)"))
    }

    @Test
    fun settingsKeepStopSensitivityWaitingWindowAndBackupCompatibility() {
        val section = mainSource("ui/settings/compose/screens/AsrSilenceSection.kt")
        val viewModel = mainSource("ui/settings/asr/AsrSettingsViewModel.kt")
        val search = mainSource("ui/settings/search/SettingsSearchIndex.kt")
        val prefs = mainSource("store/Prefs.kt")
        val backup = mainSource("store/PrefsBackup.kt")

        assertTrue(section.contains("R.string.label_silence_window_ms"))
        assertTrue(section.contains("R.string.label_silence_sensitivity"))
        assertTrue(viewModel.contains("silenceSensitivity"))
        assertTrue(search.contains("label_silence_sensitivity"))
        assertTrue(prefs.contains("var autoStopSilenceSensitivity: Int"))
        assertTrue(backup.contains("KEY_AUTO_STOP_SILENCE_SENSITIVITY"))
    }

    private fun mainSource(relativePath: String): String {
        val root = File(System.getProperty("user.dir") ?: error("user.dir is unavailable"))
        return File(root, "src/main/java/com/brycewg/asrkb/$relativePath").readText()
    }

    private fun repeatPcm(chunk: ByteArray, count: Int): ByteArray {
        val out = ByteArray(chunk.size * count)
        repeat(count) { index ->
            chunk.copyInto(out, destinationOffset = index * chunk.size)
        }
        return out
    }

    private fun sinePcm(rms: Double): ByteArray {
        val amplitude = (rms * sqrt(2.0)).coerceAtMost(30_000.0)
        val out = ByteArray(CHUNK_BYTES)
        var dst = 0
        for (i in 0 until CHUNK_SAMPLES) {
            writeShortLe(out, dst, (sin(2.0 * PI * i / 40.0) * amplitude).roundToInt())
            dst += BYTES_PER_SAMPLE
        }
        return out
    }

    private fun constantPcm(value: Int): ByteArray {
        val out = ByteArray(CHUNK_BYTES)
        var dst = 0
        repeat(CHUNK_SAMPLES) {
            writeShortLe(out, dst, value)
            dst += BYTES_PER_SAMPLE
        }
        return out
    }

    private fun writeShortLe(out: ByteArray, offset: Int, value: Int) {
        val clamped = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        out[offset] = (clamped and 0xFF).toByte()
        out[offset + 1] = ((clamped shr 8) and 0xFF).toByte()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_DURATION_MS = 100
        const val BYTES_PER_SAMPLE = 2
        const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_DURATION_MS / 1_000
        const val CHUNK_BYTES = CHUNK_SAMPLES * BYTES_PER_SAMPLE
    }
}
