package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.floating.FloatingAsrService
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/**
 * 基于 Ten VAD（sherpa-onnx）的语音活动检测与判停器。
 *
 * 相比基于音量阈值的 SilenceDetector，VAD 使用 AI 模型进行语音/静音判断，
 * 能够更准确地区分语音、呼吸声、环境噪音，减少误判。
 *
 * ## 工作原理
 * - 将 PCM 音频块转换为归一化的 FloatArray
 * - 调用 sherpa-onnx Vad 模型进行实时语音活动检测
 * - 累计连续非语音时长，超过窗口阈值时触发判停
 *
 *
 * @param context Android Context，用于访问 AssetManager
 * @param sampleRate 音频采样率（Hz），必须与 PCM 数据一致
 * @param windowMs 连续非语音的时长阈值（毫秒），超过此值判定为静音
 * @param sensitivityLevel 灵敏度档位（1-10），值越大越容易判定为静音
 */
class VadDetector(
    private val context: Context,
    private val sampleRate: Int,
    private val windowMs: Int,
    sensitivityLevel: Int
) {
    /**
     * 单帧分析结果：
     * @param isSpeech 当前帧是否检测到语音
     * @param silenceStop 是否累计静音已达到停录阈值
     */
    data class FrameResult(
        val isSpeech: Boolean,
        val silenceStop: Boolean
    )

    companion object {
        private const val TAG = "VadDetector"

        // 灵敏度档位总数（与设置滑块一致）
        const val LEVELS: Int = 10

        // 灵敏度映射到 VAD 的 minSilenceDuration 参数（单位：秒）
        // 调整为更宽松的分段：低档位给更长的静音要求，减少“提前中断”。
        // 规则：sensitivityLevel 越高，minSilenceDuration 越小（更敏感）。
        private val MIN_SILENCE_DURATION_MAP: FloatArray = floatArrayOf(
            0.55f, // 1  非常不敏感：至少 0.60s 静音才算非语音
            0.50f, // 2
            0.42f, // 3
            0.35f, // 4
            0.30f, // 5
            0.25f, // 6
            0.20f, // 7（默认附近）
            0.16f, // 8
            0.12f, // 9
            0.08f  // 10 更敏感
        )
        // 全局共享 VAD 实例（App 启动时可预加载）
        @Volatile
        private var sharedVad: Vad? = null

        // 预加载全局 VAD（若已存在则跳过）
        fun preload(context: Context, sampleRate: Int, sensitivityLevel: Int) {
            if (sharedVad != null) return
            try {
                val lvl = sensitivityLevel.coerceIn(1, LEVELS)
                val threshold = when (lvl) {
                    in 1..3 -> 0.40f
                    in 4..7 -> 0.50f
                    else -> 0.60f
                }
                val minSilenceDuration = MIN_SILENCE_DURATION_MAP[lvl - 1]

                val tenConfig = TenVadModelConfig(
                    model = "vad/ten-vad.onnx",
                    threshold = threshold,
                    minSilenceDuration = minSilenceDuration,
                    minSpeechDuration = 0.25f,
                    windowSize = 256
                )
                val vadConfig = VadModelConfig(
                    tenVadModelConfig = tenConfig,
                    sampleRate = sampleRate,
                    numThreads = 1,
                    provider = "cpu",
                    debug = false
                )
                sharedVad = Vad(
                    assetManager = context.assets,
                    config = vadConfig
                )
                Log.i(TAG, "Global VAD preloaded (sr=$sampleRate, sensitivity=$lvl)")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to preload global VAD", t)
            }
        }

        // 重建全局 VAD（用于灵敏度调整后“立即生效”）。
        // 不直接释放旧实例，避免影响正在录音的会话；旧实例会在该会话结束时被各自的 release() 回收。
        fun rebuildGlobal(context: Context, sampleRate: Int, sensitivityLevel: Int) {
            try {
                sharedVad = null
                preload(context, sampleRate, sensitivityLevel)
                Log.i(TAG, "Global VAD rebuilt (sr=$sampleRate, sensitivity=${sensitivityLevel.coerceIn(1, LEVELS)})")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to rebuild global VAD", t)
            }
        }
    }

    private var vad: Vad? = null
    private var silentMsAcc: Int = 0
    private val minSilenceDuration: Float
    private val threshold: Float
    private val speechHangoverMs: Int
    private var speechHangoverRemainingMs: Int = 0
    // 录音开始阶段的初期防抖（仅在首次检测到语音之前生效）
    private val initialDebounceMs: Int
    private var initialDebounceRemainingMs: Int = 0
    private var hasDetectedSpeech: Boolean = false

    init {
        val lvl = sensitivityLevel.coerceIn(1, LEVELS)
        minSilenceDuration = MIN_SILENCE_DURATION_MAP[lvl - 1]

        // 将灵敏度映射到 VAD 置信阈值：
        // 10 档：低(1-3)=0.40，中(4-7)=0.50，高(8-10)=0.60
        threshold = when (lvl) {
            in 1..3 -> 0.40f
            in 4..7 -> 0.50f
            else -> 0.60f
        }

        // 短暂静音“挂起”时间：在检测到语音后，容忍这段时间内的瞬时静音，避免过早累计。
        // 档位越低，挂起越长以更保守地保持语音状态。
        speechHangoverMs = when (lvl) {
            in 1..3 -> 300
            in 4..7 -> 250
            else -> 200
        }

        // 初期防抖：刚开始录音时，允许一小段时间不累计静音，避免尚未开口或微弱启动噪声导致的提前停。
        // 比语音挂起略保守一点（低档位更长）。
        initialDebounceMs = when (lvl) {
            in 1..3 -> 3000
            in 4..7 -> 2000
            else -> 1000
        }
        initialDebounceRemainingMs = initialDebounceMs

        try {
            initVad()
            Log.i(
                TAG,
                "VadDetector initialized: windowMs=$windowMs, sensitivity=$lvl, minSilenceDuration=$minSilenceDuration, threshold=$threshold, hangoverMs=$speechHangoverMs, initialDebounceMs=$initialDebounceMs"
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize VAD, will fallback to no detection", t)
        }
    }

    /**
     * 初始化 sherpa-onnx Vad
     */
    private fun initVad() {
        // Reuse global instance if available
        val g = sharedVad
        if (g != null) {
            vad = g
            Log.d(TAG, "Reusing global VAD instance")
            return
        }
        // 1. 构建 TenVadModelConfig
        val tenConfig = TenVadModelConfig(
            model = "vad/ten-vad.onnx",   // 模型路径（相对于 assets）
            threshold = threshold,         // 按灵敏度映射的语音检测阈值
            minSilenceDuration = minSilenceDuration, // 根据灵敏度映射
            minSpeechDuration = 0.25f,     // 最小语音持续时长
            windowSize = 256               // Ten VAD 窗口大小
        )

        // 2. 构建 VadModelConfig
        val vadConfig = VadModelConfig(
            tenVadModelConfig = tenConfig,
            sampleRate = sampleRate,
            numThreads = 1,
            provider = "cpu",
            debug = false
        )

        // 3. 创建 Vad 实例
        vad = Vad(
            assetManager = context.assets,
            config = vadConfig
        )

        Log.d(TAG, "Vad instance created successfully (non-global)")
    }

    /**
     * 处理音频帧，判断是否应停止录音。
     *
     * @param buf PCM 音频数据（16-bit LE）
     * @param len 有效数据长度（字节）
     * @return 如果连续非语音时长超过窗口阈值，返回 true
     */
    fun shouldStop(buf: ByteArray, len: Int): Boolean {
        return analyzeFrame(buf, len).silenceStop
    }

    /**
     * 对单帧音频进行 VAD 分析，返回“是否语音”与“是否触发静音停录”。
     *
     * - isSpeech：基于模型的当前帧语音判定；
     * - silenceStop：在综合初期防抖、挂起和平滑累计后，是否达到静音停录阈值。
     */
    fun analyzeFrame(buf: ByteArray, len: Int): FrameResult {
        val vad = this.vad ?: return FrameResult(isSpeech = false, silenceStop = false)

        try {
            val frameMs = if (sampleRate > 0) ((len / 2) * 1000) / sampleRate else 0

            // 1. 将 PCM ByteArray 转换为 FloatArray（归一化到 -1.0 ~ 1.0）
            val samples = pcmToFloatArray(buf, len)
            if (samples.isEmpty()) return FrameResult(isSpeech = false, silenceStop = false)

            // 2. 调用 Vad.acceptWaveform(FloatArray)
            vad.acceptWaveform(samples)

            // 3. 调用 Vad.isSpeechDetected(): Boolean
            val isSpeech = vad.isSpeechDetected()

            // 4. 调用 Vad.clear() 清除内部状态（准备下一帧）
            vad.clear()

            // 5. 初期防抖 + 语音挂起 平滑处理，再进行累计非语音时长
            if (isSpeech) {
                silentMsAcc = 0
                hasDetectedSpeech = true
                speechHangoverRemainingMs = speechHangoverMs
                return FrameResult(isSpeech = true, silenceStop = false)
            } else {
                // 初期防抖：尚未检测到语音前，不累计静音
                if (!hasDetectedSpeech && initialDebounceRemainingMs > 0) {
                    initialDebounceRemainingMs -= frameMs
                    if (initialDebounceRemainingMs < 0) initialDebounceRemainingMs = 0
                    return FrameResult(isSpeech = false, silenceStop = false)
                }

                // 语音挂起：检测到语音后的一小段时间内，不累计静音
                if (speechHangoverRemainingMs > 0) {
                    speechHangoverRemainingMs -= frameMs
                    if (speechHangoverRemainingMs < 0) speechHangoverRemainingMs = 0
                    // 挂起期内直接返回
                    return FrameResult(isSpeech = false, silenceStop = false)
                }

                silentMsAcc += frameMs
                if (silentMsAcc >= windowMs) {
                    Log.d(TAG, "Silence window reached: ${silentMsAcc}ms >= ${windowMs}ms")
                    return FrameResult(isSpeech = false, silenceStop = true)
                }
            }

            return FrameResult(isSpeech = false, silenceStop = false)
        } catch (t: Throwable) {
            Log.e(TAG, "Error during VAD detection", t)
            return FrameResult(isSpeech = false, silenceStop = false)
        }
    }

    /**
     * 仅基于 VAD 判断当前帧是否包含语音，不参与静音累计与停录判定。
     *
     * 适用于“起说检测”等仅需语音活动信息的场景（例如畅说模式）。
     */
    fun isSpeechFrame(buf: ByteArray, len: Int): Boolean {
        return try {
            analyzeFrame(buf, len).isSpeech
        } catch (t: Throwable) {
            Log.e(TAG, "Error during VAD speech frame detection", t)
            false
        }
    }

    /**
     * 重置内部累计状态（不重新创建底层 VAD 实例）。
     *
     * 适用于同一会话内按“停顿”分片后继续使用当前检测器的场景。
     */
    fun reset() {
        silentMsAcc = 0
        speechHangoverRemainingMs = 0
        initialDebounceRemainingMs = initialDebounceMs
        hasDetectedSpeech = false
    }

    /**
     * 释放 VAD 资源
     */
    fun release() {
        // Skip releasing when using global instance
        if (vad != null && vad === sharedVad) {
            Log.d(TAG, "Skip releasing global VAD instance")
            return
        }
        try {
            vad?.release()
            Log.d(TAG, "VAD released successfully")
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to release VAD", t)
        } finally {
            vad = null
        }
    }

    /**
     * 将 PCM 16-bit LE ByteArray 转换为归一化的 FloatArray
     *
     * @param pcm PCM 音频数据
     * @param len 有效数据长度（字节）
     * @return 归一化的音频样本（-1.0 到 1.0）
     */
    private fun pcmToFloatArray(pcm: ByteArray, len: Int): FloatArray {
        if (len <= 0) return FloatArray(0)

        val numSamples = len / 2 // 16-bit = 2 bytes per sample
        val samples = FloatArray(numSamples)

        var i = 0
        var sampleIdx = 0
        while (i + 1 < len && sampleIdx < numSamples) {
            // Little Endian: 低字节在前
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt() and 0xFF
            val pcmValue = (hi shl 8) or lo  // 0..65535

            // 转为有符号 -32768..32767
            val signed = if (pcmValue < 0x8000) pcmValue else pcmValue - 0x10000

            // 归一化到 -1.0 ~ 1.0
            // 使用 32768.0f 避免 -32768 除法溢出，并限制范围
            var normalized = signed / 32768.0f
            if (normalized > 1.0f) normalized = 1.0f
            else if (normalized < -1.0f) normalized = -1.0f

            samples[sampleIdx] = normalized

            i += 2
            sampleIdx++
        }

        return samples
    }
}

fun isVadAutoStopEnabled(context: Context, prefs: Prefs): Boolean {
    return try {
        // 统一仅由「静音自动停止」开关控制是否启用基于静音的自动停录。
        // - IME 与悬浮球行为一致：只要用户显式开启了静音判停，就启用 VAD 停录。
        // - 点按/长按录音模式（micTapToggleEnabled）不再影响是否启用静音判停，避免和畅说模式等基于 VAD 的功能产生隐性耦合。
        prefs.autoStopOnSilenceEnabled
    } catch (t: Throwable) {
        Log.w("VadDetector", "Failed to read prefs for VAD auto-stop", t)
        false
    }
}
