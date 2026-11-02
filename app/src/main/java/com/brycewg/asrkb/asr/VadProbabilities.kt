/** 与 PCM 样本位置对齐的不可变概率切片，可随音频分段、裁剪和主备分发复用。 */
package com.brycewg.asrkb.asr

internal data class VadProbabilityFrame(val startSample: Long, val endSample: Long, val probability: Float)

class VadProbabilities internal constructor(
    internal val sampleRate: Int,
    internal val sampleCount: Long,
    internal val frames: List<VadProbabilityFrame>
) {
    internal fun slice(startByte: Long, endByte: Long): VadProbabilities {
        val start = startByte / 2
        val end = endByte / 2
        require(start >= 0 && end >= start && end <= sampleCount)
        var low = 0
        var high = frames.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (frames[middle].endSample <= start) low = middle + 1 else high = middle
        }
        val first = low
        // 以帧结束位置归属音频片段，避免跨边界帧被两个消费者重复累计。
        val selected = frames.subList(first, frames.size).asSequence().takeWhile { it.endSample <= end }.map {
            VadProbabilityFrame(
                (it.startSample - start).coerceAtLeast(0),
                (it.endSample.coerceAtMost(end) - start),
                it.probability
            )
        }.toList()
        return VadProbabilities(sampleRate, end - start, selected)
    }

    internal fun select(ranges: List<IntRange>): VadProbabilities {
        var offset = 0L
        val selected = ArrayList<VadProbabilityFrame>()
        for (range in ranges) {
            val part = slice(range.first.toLong(), range.last.toLong() + 1)
            selected.addAll(
                part.frames.map {
                    VadProbabilityFrame(it.startSample + offset, it.endSample + offset, it.probability)
                }
            )
            offset += part.sampleCount
        }
        return VadProbabilities(sampleRate, offset, selected)
    }

    internal companion object {
        fun concatenate(parts: List<VadProbabilities>): VadProbabilities {
            val sampleRate = parts.first().sampleRate
            var offset = 0L
            val frames = ArrayList<VadProbabilityFrame>()
            for (part in parts) {
                require(part.sampleRate == sampleRate)
                frames.addAll(
                    part.frames.map {
                        VadProbabilityFrame(it.startSample + offset, it.endSample + offset, it.probability)
                    }
                )
                offset += part.sampleCount
            }
            return VadProbabilities(sampleRate, offset, frames)
        }

        fun analyze(context: android.content.Context, pcm: ByteArray, sampleRate: Int): VadProbabilities {
            val stream = VadProbabilityStream(context, sampleRate)
            return try {
                stream.append(pcm)
                stream.snapshot()
            } finally {
                stream.release()
            }
        }
    }
}
