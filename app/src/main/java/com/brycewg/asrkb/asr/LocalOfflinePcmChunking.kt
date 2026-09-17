/** 本地离线模型的 PCM16 安全分段与静音切点选择。 */
package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.store.Prefs

private const val PCM16_BYTES_PER_SAMPLE = 2

internal fun splitLocalOfflinePcm16(
    pcm: ByteArray,
    sampleRate: Int,
    maxChunkMs: Int = NON_STREAMING_MAX_CHUNK_MS,
    minChunkMs: Int = NON_STREAMING_MIN_CHUNK_MS,
    silenceRanges: List<IntRange> = emptyList()
): List<ByteArray> {
    require(sampleRate > 0)
    require(maxChunkMs > 0)
    require(minChunkMs > 0 && maxChunkMs >= minChunkMs)
    require(pcm.size % PCM16_BYTES_PER_SAMPLE == 0) { "Incomplete PCM16 frame" }
    if (pcm.isEmpty()) return emptyList()

    val chunkBytes = (sampleRate.toLong() * PCM16_BYTES_PER_SAMPLE * maxChunkMs / 1_000L)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt() and -PCM16_BYTES_PER_SAMPLE
    val minChunkBytes = (
        sampleRate.toLong() * PCM16_BYTES_PER_SAMPLE * minChunkMs / 1_000L
        ).coerceAtMost(chunkBytes.toLong()).toInt() and -PCM16_BYTES_PER_SAMPLE
    require(chunkBytes > 0)
    if (pcm.size <= chunkBytes) return listOf(pcm)

    return buildList((pcm.size + chunkBytes - 1) / chunkBytes) {
        var offset = 0
        while (offset < pcm.size) {
            val hardEnd = (offset + chunkBytes).coerceAtMost(pcm.size)
            val end = if (hardEnd == pcm.size) {
                hardEnd
            } else {
                preferredSilenceCut(
                    silenceRanges = silenceRanges,
                    searchStart = offset + minChunkBytes,
                    hardEnd = hardEnd
                ) ?: hardEnd
            }
            add(pcm.copyOfRange(offset, end))
            offset = end
        }
    }
}

internal fun localOfflinePcmNeedsChunking(
    pcm: ByteArray,
    sampleRate: Int,
    maxChunkMs: Int = NON_STREAMING_MAX_CHUNK_MS
): Boolean = sampleRate > 0 &&
    pcm.size.toLong() >
    sampleRate.toLong() * PCM16_BYTES_PER_SAMPLE * maxChunkMs / 1_000L

internal fun splitLocalOfflinePcm16WithVad(
    context: Context,
    prefs: Prefs,
    pcm: ByteArray,
    sampleRate: Int,
    window: NonStreamingChunkWindow = NonStreamingChunkWindow.Local
): List<ByteArray> {
    val silenceRanges = if (localOfflinePcmNeedsChunking(pcm, sampleRate, window.maxChunkMs)) {
        RecordedAudioVoiceFilter.findSilenceRangesForLocalOfflineChunking(
            context = context,
            prefs = prefs,
            pcm = pcm,
            sampleRate = sampleRate
        )
    } else {
        emptyList()
    }
    return splitLocalOfflinePcm16(
        pcm = pcm,
        sampleRate = sampleRate,
        maxChunkMs = window.maxChunkMs,
        minChunkMs = window.minChunkMs,
        silenceRanges = silenceRanges
    )
}

private fun preferredSilenceCut(
    silenceRanges: List<IntRange>,
    searchStart: Int,
    hardEnd: Int
): Int? = silenceRanges.asSequence()
    .mapNotNull { range ->
        val start = maxOf(range.first, searchStart)
        val endExclusive = minOf(range.last + 1, hardEnd)
        if (endExclusive <= start) {
            null
        } else {
            val midpoint = ((start.toLong() + endExclusive) / 2L).toInt() and -PCM16_BYTES_PER_SAMPLE
            midpoint to (endExclusive - start)
        }
    }
    .maxWithOrNull(compareBy<Pair<Int, Int>> { it.second }.thenBy { it.first })
    ?.first
