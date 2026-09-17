package com.brycewg.asrkb.asr

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NonStreamingProgressiveChunkingTest {
    private val sampleRate = 1_000

    @Test
    fun firstPauseAfterMinimumDurationCutsImmediately() {
        val chunker = NonStreamingPcmChunker(sampleRate)

        assertTrue(chunker.append(pcm(15_000), isSpeech = true).isEmpty())
        assertTrue(chunker.append(pcm(200), isSpeech = false).isEmpty())
        val chunks = chunker.append(pcm(200), isSpeech = false)

        assertEquals(listOf(15_200), chunks.map(::durationMs))
        assertEquals(200, durationMs(chunker.finish()!!))
    }

    @Test
    fun silenceCrossingMinimumCannotCreateShortChunk() {
        val chunker = NonStreamingPcmChunker(sampleRate)

        assertTrue(chunker.append(pcm(14_800), isSpeech = true).isEmpty())
        val chunks = chunker.append(pcm(400), isSpeech = false)

        assertEquals(listOf(15_000), chunks.map(::durationMs))
    }

    @Test
    fun continuousSpeechHardCutsAtMaximumDurationWithoutLosingPcm() {
        val chunker = NonStreamingPcmChunker(sampleRate)
        val input = ByteArray(49_000 * 2) { (it % 251).toByte() }
        val outputs = ArrayList<ByteArray>()

        input.asList().chunked(400).forEach { bytes ->
            outputs += chunker.append(bytes.toByteArray(), isSpeech = true)
        }
        chunker.finish()?.let(outputs::add)

        assertEquals(listOf(24_000, 24_000, 1_000), outputs.map(::durationMs))
        assertArrayEquals(input, outputs.fold(ByteArray(0)) { all, next -> all + next })
    }

    @Test
    fun onlineSilenceBeforeFortyFiveSecondsDoesNotCut() {
        val chunker = NonStreamingPcmChunker(
            sampleRate,
            minChunkMs = ONLINE_NON_STREAMING_MIN_CHUNK_MS,
            maxChunkMs = ONLINE_NON_STREAMING_MAX_CHUNK_MS
        )

        assertTrue(chunker.append(pcm(20_000), isSpeech = true).isEmpty())
        assertTrue(chunker.append(pcm(400), isSpeech = false).isEmpty())
        assertEquals(20_400, durationMs(chunker.finish()!!))
    }

    @Test
    fun onlineFirstPauseAfterMinimumDurationCutsImmediately() {
        val chunker = NonStreamingPcmChunker(
            sampleRate,
            minChunkMs = ONLINE_NON_STREAMING_MIN_CHUNK_MS,
            maxChunkMs = ONLINE_NON_STREAMING_MAX_CHUNK_MS
        )

        assertTrue(chunker.append(pcm(45_000), isSpeech = true).isEmpty())
        assertTrue(chunker.append(pcm(200), isSpeech = false).isEmpty())
        val chunks = chunker.append(pcm(200), isSpeech = false)

        assertEquals(listOf(45_200), chunks.map(::durationMs))
        assertEquals(200, durationMs(chunker.finish()!!))
    }

    @Test
    fun onlineContinuousSpeechHardCutsAtMaximumDurationWithoutLosingPcm() {
        val chunker = NonStreamingPcmChunker(
            sampleRate,
            minChunkMs = ONLINE_NON_STREAMING_MIN_CHUNK_MS,
            maxChunkMs = ONLINE_NON_STREAMING_MAX_CHUNK_MS
        )
        val input = ByteArray(121_000 * 2) { (it % 251).toByte() }
        val outputs = ArrayList<ByteArray>()

        input.asList().chunked(400).forEach { bytes ->
            outputs += chunker.append(bytes.toByteArray(), isSpeech = true)
        }
        chunker.finish()?.let(outputs::add)

        assertEquals(listOf(60_000, 60_000, 1_000), outputs.map(::durationMs))
        assertArrayEquals(input, outputs.fold(ByteArray(0)) { all, next -> all + next })
    }

    @Test
    fun collectorCachesChunksAndPublishesOneFinalOnFinish() = runTest {
        val events = ArrayList<String>()
        val durations = ArrayList<Long>()
        var nowNanos = 1_000_000_000L
        val collector = NonStreamingChunkResultCollector(
            delegate = listener(events),
            emptyResultMessage = "empty",
            ignorableEmptyErrors = setOf("blank chunk"),
            requestDurationCallback = durations::add,
            nanoTime = { nowNanos }
        )

        collector.start()
        collector.onFinal("第一段")
        collector.onError("blank chunk")
        nowNanos += 500_000_000L
        collector.onStopped()
        nowNanos += 80_000_000L
        collector.onFinal("第二段")

        collector.finish()

        assertEquals(listOf("final:第一段第二段"), events)
        assertEquals(listOf(80L), durations)
        assertFalse(collector.hasFatalError)
    }

    @Test
    fun collectorPublishesOnlyFatalErrorAndCancelPublishesNothing() = runTest {
        val events = ArrayList<String>()
        val collector = NonStreamingChunkResultCollector(
            delegate = listener(events),
            emptyResultMessage = "empty",
            ignorableEmptyErrors = emptySet()
        )

        collector.start()
        collector.onFinal("partial")
        collector.onError("decode failed")
        assertTrue(collector.hasFatalError)
        collector.finish()
        collector.finish()

        collector.start()
        collector.onFinal("discarded")
        collector.cancel()
        collector.finish()

        assertEquals(listOf("error:decode failed"), events)
    }

    @Test
    fun collectorCanStartASeparateRetryAfterTerminal() = runTest {
        val events = ArrayList<String>()
        val collector = NonStreamingChunkResultCollector(
            delegate = listener(events),
            emptyResultMessage = "empty",
            ignorableEmptyErrors = emptySet()
        )

        collector.start()
        collector.onError("first failure")
        collector.finish()

        collector.start()
        collector.onFinal("retry result")
        collector.finish()

        assertEquals(listOf("error:first failure", "final:retry result"), events)
    }

    @Test
    fun englishChunkWordsKeepTheirBoundary() {
        assertEquals("hello world", joinNonStreamingChunkTexts(listOf("hello", "world")))
        assertEquals("你好世界", joinNonStreamingChunkTexts(listOf("你好", "世界")))
        assertEquals("hello, world", joinNonStreamingChunkTexts(listOf("hello,", "world")))
    }

    @Test
    fun collectorFinalizesCombinedTextOnce() = runTest {
        val events = ArrayList<String>()
        var finalizeCalls = 0
        val collector = NonStreamingChunkResultCollector(
            delegate = listener(events),
            emptyResultMessage = "empty",
            ignorableEmptyErrors = emptySet()
        )

        collector.start()
        collector.onFinal("one")
        collector.onFinal("two")
        collector.finish(
            transformFinal = {
                finalizeCalls++
                "$it!"
            }
        )

        assertEquals(1, finalizeCalls)
        assertEquals(listOf("final:one two!"), events)
    }

    @Test
    fun cancelDuringFinalizationSuppressesFinalResult() = runTest {
        val events = ArrayList<String>()
        val enteredFinalizer = CompletableDeferred<Unit>()
        val releaseFinalizer = CompletableDeferred<Unit>()
        var finalizedCalls = 0
        val collector = NonStreamingChunkResultCollector(
            delegate = listener(events),
            emptyResultMessage = "empty",
            ignorableEmptyErrors = emptySet()
        )

        collector.start()
        collector.onFinal("result")
        val finishJob = launch {
            collector.finish(
                transformFinal = {
                    enteredFinalizer.complete(Unit)
                    releaseFinalizer.await()
                    it
                },
                onFinalized = { finalizedCalls++ }
            )
        }
        enteredFinalizer.await()
        collector.cancel()
        releaseFinalizer.complete(Unit)
        finishJob.join()

        assertTrue(events.isEmpty())
        assertEquals(0, finalizedCalls)
    }

    private fun pcm(durationMs: Int): ByteArray = ByteArray(durationMs * sampleRate / 1_000 * 2)

    private fun durationMs(pcm: ByteArray): Int = pcm.size / 2 * 1_000 / sampleRate

    private fun listener(events: MutableList<String>) = object : StreamingAsrEngine.Listener {
        override fun onFinal(text: String) {
            events += "final:$text"
        }

        override fun onError(message: String) {
            events += "error:$message"
        }
    }
}
