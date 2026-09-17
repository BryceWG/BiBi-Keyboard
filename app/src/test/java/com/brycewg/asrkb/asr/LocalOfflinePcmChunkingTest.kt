package com.brycewg.asrkb.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LocalOfflinePcmChunkingTest {
    @Test
    fun audioAtSafeLimitStaysSinglePass() {
        val pcm = ByteArray(HARD_CHUNK_BYTES)

        val chunks = splitLocalOfflinePcm16(pcm, SAMPLE_RATE)

        assertEquals(1, chunks.size)
        assertSame(pcm, chunks.single())
    }

    @Test
    fun audioOverSafeLimitIsSplitWithoutLossOrOverlap() {
        val pcm = ByteArray(HARD_CHUNK_BYTES * 2 + 2) { (it % 251).toByte() }

        val chunks = splitLocalOfflinePcm16(pcm, SAMPLE_RATE)

        assertEquals(listOf(HARD_CHUNK_BYTES, HARD_CHUNK_BYTES, 2), chunks.map { it.size })
        assertArrayEquals(pcm, chunks.fold(ByteArray(0)) { all, chunk -> all + chunk })
    }

    @Test
    fun longestSilenceNearTargetIsPreferredOverHardCut() {
        val pcm = ByteArray(bytesForMs(45_000)) {
            (it % 251).toByte()
        }
        val firstSilence = byteRange(startMs = 18_000, endMs = 19_000)
        val secondSilence = byteRange(startMs = 37_000, endMs = 38_000)

        val chunks = splitLocalOfflinePcm16(
            pcm = pcm,
            sampleRate = SAMPLE_RATE,
            silenceRanges = listOf(firstSilence, secondSilence)
        )

        assertEquals(
            listOf(bytesForMs(18_500), bytesForMs(19_000), bytesForMs(7_500)),
            chunks.map { it.size }
        )
        assertArrayEquals(pcm, chunks.fold(ByteArray(0)) { all, chunk -> all + chunk })
    }

    @Test
    fun silenceAfterTwentySecondsCanBeSelected() {
        val pcm = ByteArray(bytesForMs(45_000))

        val chunks = splitLocalOfflinePcm16(
            pcm = pcm,
            sampleRate = SAMPLE_RATE,
            silenceRanges = listOf(byteRange(startMs = 21_000, endMs = 21_500))
        )

        assertEquals(bytesForMs(21_250), chunks.first().size)
    }

    @Test
    fun longestSilenceInWindowWins() {
        val pcm = ByteArray(bytesForMs(45_000))

        val chunks = splitLocalOfflinePcm16(
            pcm = pcm,
            sampleRate = SAMPLE_RATE,
            silenceRanges = listOf(
                byteRange(startMs = 18_000, endMs = 18_400),
                byteRange(startMs = 20_500, endMs = 21_500)
            )
        )

        assertEquals(bytesForMs(21_000), chunks.first().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompletePcm16FrameIsRejected() {
        splitLocalOfflinePcm16(ByteArray(3), SAMPLE_RATE)
    }

    @Test
    fun onlineWindowPrefersSilenceBetweenFortyFiveAndSixtySeconds() {
        val pcm = ByteArray(bytesForMs(90_000))

        val chunks = splitLocalOfflinePcm16(
            pcm = pcm,
            sampleRate = SAMPLE_RATE,
            maxChunkMs = ONLINE_NON_STREAMING_MAX_CHUNK_MS,
            minChunkMs = ONLINE_NON_STREAMING_MIN_CHUNK_MS,
            silenceRanges = listOf(byteRange(startMs = 50_000, endMs = 51_000))
        )

        assertEquals(bytesForMs(50_500), chunks.first().size)
        assertArrayEquals(pcm, chunks.fold(ByteArray(0)) { all, chunk -> all + chunk })
    }

    @Test
    fun onlineWindowIgnoresSilenceBeforeFortyFiveSeconds() {
        val pcm = ByteArray(bytesForMs(90_000))

        val chunks = splitLocalOfflinePcm16(
            pcm = pcm,
            sampleRate = SAMPLE_RATE,
            maxChunkMs = ONLINE_NON_STREAMING_MAX_CHUNK_MS,
            minChunkMs = ONLINE_NON_STREAMING_MIN_CHUNK_MS,
            silenceRanges = listOf(byteRange(startMs = 20_000, endMs = 21_000))
        )

        assertEquals(bytesForMs(60_000), chunks.first().size)
    }

    @Test
    fun onlineWindowHardCutsAtSixtySecondsWithoutSilence() {
        val pcm = ByteArray(bytesForMs(90_000)) { (it % 251).toByte() }

        val chunks = splitLocalOfflinePcm16(
            pcm = pcm,
            sampleRate = SAMPLE_RATE,
            maxChunkMs = ONLINE_NON_STREAMING_MAX_CHUNK_MS,
            minChunkMs = ONLINE_NON_STREAMING_MIN_CHUNK_MS
        )

        assertEquals(listOf(bytesForMs(60_000), bytesForMs(30_000)), chunks.map { it.size })
        assertArrayEquals(pcm, chunks.fold(ByteArray(0)) { all, chunk -> all + chunk })
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val HARD_CHUNK_BYTES = SAMPLE_RATE * 2 * 24

        fun bytesForMs(durationMs: Int): Int = (SAMPLE_RATE.toLong() * 2 * durationMs / 1_000L).toInt()

        fun byteRange(startMs: Int, endMs: Int): IntRange = bytesForMs(startMs) until bytesForMs(endMs)
    }
}
