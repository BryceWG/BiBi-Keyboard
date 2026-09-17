// VAD Input Leveler 接线路径的轻量源码护栏测试。
package com.brycewg.asrkb.asr

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadInputLevelerRoutingSourceTest {
    @Test
    fun directMicrophoneFileEngineKeepsAsrBuffersRawAndRoutesVadThroughLeveler() {
        val source = sourceFile("BaseFileAsrEngine.kt")

        assertTrue(source.contains("currentSeg.write(audioChunk)"))
        assertTrue(source.contains("encoder.writePcm(encodedInput)"))
        assertTrue(source.contains("listener.onAmplitude(leveled.stableAmplitude)"))
        assertTrue(source.contains("vadDetector?.shouldStop(leveled.leveledPcm, leveled.leveledPcm.size)"))
        assertFalse(source.contains("currentSeg.write(leveled"))
        assertFalse(source.contains("writePcm(leveled"))
    }

    @Test
    fun pushedPcmPathsKeepAsrBuffersRawAndUseStableAmplitude() {
        val generic = sourceFile("GenericPushFileAsrAdapter.kt")
        val pseudo = sourceFile("PushPcmPseudoStreamAsrEngine.kt")
        val parallel = sourceFile("ParallelAsrEngine.kt")

        assertTrue(generic.contains("listener.onAmplitude(leveled.stableAmplitude)"))
        assertTrue(generic.contains("bos.write(pcm)"))
        assertTrue(pseudo.contains("listener.onAmplitude(leveled.stableAmplitude)"))
        assertTrue(pseudo.contains("sessionBuffer.write(pcm)"))
        assertTrue(pseudo.contains("segmentBuffer.write(pcm)"))
        assertTrue(parallel.contains("listener.onAmplitude(leveled.stableAmplitude)"))
        assertTrue(parallel.contains("appendPcmToConsumers(chunk, sourceLabel = \"capture\")"))
        assertTrue(parallel.contains("appendPcmToConsumers(pcm, sourceLabel = \"externalPcmInput\")"))
        assertFalse(generic.contains("bos.write(leveled"))
        assertFalse(pseudo.contains("sessionBuffer.write(leveled"))
        assertFalse(pseudo.contains("segmentBuffer.write(leveled"))
        assertFalse(parallel.contains("appendPcmToConsumers(leveled"))
    }

    @Test
    fun offlineVoiceFilterUsesLeveledVadAndEnergyButRawBadSourceGuard() {
        val source = sourceFile("RecordedAudioVoiceFilter.kt")

        assertTrue(source.contains("val rawEnergy = measureEnergy(chunk, chunk.size)"))
        assertTrue(source.contains("val leveled = leveler.process(chunk)"))
        assertTrue(source.contains("detector.analyzeFrame(leveledPcm, leveledPcm.size).isSpeech"))
        assertTrue(source.contains("val energy = measureEnergy(leveledPcm, leveledPcm.size)"))
        assertTrue(source.contains("marks.all { it.isRawBadSourceLevel() }"))
        assertTrue(source.contains("isLikelyBadSource("))
    }

    @Test
    fun offlineReplayLevelsLowAndHighRecordedPcmIntoComparableEnergy() {
        val lowRecording = repeatPcm(sinePcm(rms = 260.0), count = 24)
        val highRecording = repeatPcm(sinePcm(rms = 5_200.0), count = 24)

        val lowLast = VadInputLeveler()
            .replayPcm16Le(lowRecording, chunkBytes = CHUNK_BYTES)
            .last()
        val highLast = VadInputLeveler()
            .replayPcm16Le(highRecording, chunkBytes = CHUNK_BYTES)
            .last()

        assertTrue(lowLast.stats.speechCandidate)
        assertTrue(highLast.stats.speechCandidate)
        assertTrue(abs(lowLast.stats.leveledRms - highLast.stats.leveledRms) < 700.0)
    }

    private fun sourceFile(name: String): String {
        val root = File(System.getProperty("user.dir") ?: error("user.dir is unavailable"))
        return File(root, "src/main/java/com/brycewg/asrkb/asr/$name").readText()
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
            val sample = (sin(2.0 * PI * i / 40.0) * amplitude).roundToInt()
            out[dst] = (sample and 0xFF).toByte()
            out[dst + 1] = ((sample shr 8) and 0xFF).toByte()
            dst += BYTES_PER_SAMPLE
        }
        return out
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_DURATION_MS = 100
        const val BYTES_PER_SAMPLE = 2
        const val CHUNK_SAMPLES = SAMPLE_RATE * CHUNK_DURATION_MS / 1_000
        const val CHUNK_BYTES = CHUNK_SAMPLES * BYTES_PER_SAMPLE
    }
}
