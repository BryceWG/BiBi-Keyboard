/** 会话级 Silero 概率推理与实例池；各功能只消费概率，不重复运行模型。 */
package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.ArrayDeque

class VadProbabilityStream internal constructor(private val context: Context, internal val sampleRate: Int) {
    private var model: Vad? = null
    private val input = FloatArray(64 + 512)
    private var pending = 0
    private var resamplePhase = 0
    private var samples = 0L
    private var frameStart = 0L
    private val frames = ArrayList<VadProbabilityFrame>()
    private var computedFrames = 0
    private var forwardedFrames = 0
    private var released = false

    init {
        require(sampleRate >= 16_000)
    }

    @Synchronized
    internal fun append(pcm: ByteArray, probabilities: VadProbabilities? = null, len: Int = pcm.size): VadProbabilities {
        released = false
        // 主备转发只累计样本坐标；循环模型状态仅由原始 PCM 的推理拥有者推进。
        if (probabilities != null) {
            forwardedFrames += probabilities.frames.size
            require(probabilities.sampleRate == sampleRate && probabilities.sampleCount == len / 2L)
            frames.addAll(
                probabilities.frames.map {
                    VadProbabilityFrame(it.startSample + samples, it.endSample + samples, it.probability)
                }
            )
            samples += probabilities.sampleCount
            return probabilities
        }
        val start = samples
        val firstFrame = frames.size
        val vad = model ?: acquire(context).also { model = it }
        var offset = 0
        while (offset + 1 < len) {
            val sample = ((pcm[offset + 1].toInt() shl 8) or (pcm[offset].toInt() and 0xff)) / 32768f
            samples++
            resamplePhase += 16_000
            if (resamplePhase >= sampleRate) {
                resamplePhase -= sampleRate
                input[64 + pending++] = sample
                if (pending == 512) {
                    frames.add(VadProbabilityFrame(frameStart, samples, vad.compute(input)))
                    computedFrames++
                    frameStart = samples
                    input.copyInto(input, 0, 512, 576)
                    pending = 0
                }
            }
            offset += 2
        }
        return VadProbabilities(
            sampleRate,
            samples - start,
            frames.subList(firstFrame, frames.size).map {
                VadProbabilityFrame((it.startSample - start).coerceAtLeast(0), it.endSample - start, it.probability)
            }
        )
    }

    @Synchronized
    internal fun snapshot(startByte: Long = 0, endByte: Long = samples * 2): VadProbabilities = VadProbabilities(sampleRate, samples, frames).slice(startByte, endByte)

    @Synchronized
    internal fun release() {
        if (!released && samples > 0) {
            released = true
            DebugLogManager.log(
                category = "asr",
                event = "vad_probability_stream",
                data = mapOf(
                    "sampleRate" to sampleRate,
                    "audioMs" to samples * 1000 / sampleRate,
                    "computedFrames" to computedFrames,
                    "forwardedFrames" to forwardedFrames
                )
            )
        }
        model?.let(::recycle)
        model = null
    }

    @Synchronized
    internal fun reset() {
        release()
        input.fill(0f)
        pending = 0
        resamplePhase = 0
        samples = 0L
        frameStart = 0L
        frames.clear()
        computedFrames = 0
        forwardedFrames = 0
        released = false
    }

    companion object {
        private val pool = ArrayDeque<Vad>()

        internal fun recycle(model: Vad) {
            model.reset()
            synchronized(pool) {
                if (pool.size < 2) pool.addLast(model) else model.release()
            }
        }

        private fun create(context: Context): Vad = Vad(
            assetManager = context.assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "vad/silero-v6.2.3.onnx",
                    windowSize = 512
                ),
                sampleRate = 16_000,
                numThreads = 1,
                provider = "cpu",
                debug = false
            )
        )

        internal fun acquire(context: Context): Vad {
            val model = synchronized(pool) { if (pool.isEmpty()) null else pool.removeFirst() }
                ?: create(context)
            model.reset()
            return model
        }

        @Suppress("UNUSED_PARAMETER")
        internal fun preload(context: Context, sampleRate: Int, sensitivityLevel: Int = Prefs.DEFAULT_SILENCE_SENSITIVITY) {
            synchronized(pool) {
                if (pool.isEmpty()) pool.addLast(create(context))
            }
        }

        internal fun rebuildGlobal(context: Context, sampleRate: Int, sensitivityLevel: Int) {
            preload(context, sampleRate, sensitivityLevel)
        }
    }
}
