package com.brycewg.asrkb.asr

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.withLock

/**
 * 本地 SenseVoice（通过 sherpa-onnx）非流式文件识别引擎。
 * 目前为占位实现：当 sherpa-onnx 依赖与模型未接入时，给出友好提示而不发起录音后的无效识别。
 * 后续接入模型与 AAR/so 后，可在 [recognize] 中补充实际推理调用。
 */
class SenseVoiceFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration, progressiveChunkingEnabled = true),
    PcmBatchRecognizer {

    override val progressiveVendor: AsrVendor = AsrVendor.SenseVoice

    interface LocalModelLoadUi {
        fun onLocalModelLoadStart()
        fun onLocalModelLoadDone()
    }

    private fun showToast(resId: Int) {
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
                } catch (
                    t: Throwable
                ) {
                    Log.e("SenseVoiceFileAsrEngine", "Failed to show toast", t)
                }
            }
        } catch (t: Throwable) {
            Log.e("SenseVoiceFileAsrEngine", "Failed to post toast", t)
        }
    }

    private fun notifyLoadStart() {
        val ui = (listener as? LocalModelLoadUi)
        if (ui != null) {
            try {
                ui.onLocalModelLoadStart()
            } catch (t: Throwable) {
                Log.e("SenseVoiceFileAsrEngine", "Failed to notify load start", t)
            }
        } else {
            showToast(R.string.sv_loading_model)
        }
    }

    private fun notifyLoadDone() {
        val ui = (listener as? LocalModelLoadUi)
        if (ui != null) {
            try {
                ui.onLocalModelLoadDone()
            } catch (t: Throwable) {
                Log.e("SenseVoiceFileAsrEngine", "Failed to notify load done", t)
            }
        }
    }

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        // 若未集成 sherpa-onnx Kotlin/so，则直接报错以避免无意义的录音
        val manager = SenseVoiceOnnxManager.getInstance()
        if (!manager.isOnnxAvailable()) {
            try {
                listener.onError(context.getString(R.string.error_local_asr_not_ready))
            } catch (
                t: Throwable
            ) {
                Log.e("SenseVoiceFileAsrEngine", "Failed to send error callback", t)
            }
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        val t0 = System.currentTimeMillis()
        val localLog = if (isProgressiveChunkDecode) {
            null
        } else {
            LocalAsrCallLogger.startInference(
                prefs = prefs,
                vendor = AsrVendor.SenseVoice,
                source = "file",
                audioBytes = pcm.size,
                sampleRate = sampleRate
            )
        }
        var loadLog: LocalAsrCallLogger.Session? = null
        var durationReported = false
        fun reportDuration() {
            if (durationReported) return
            durationReported = true
            val dt = System.currentTimeMillis() - t0
            try {
                onRequestDuration?.invoke(dt)
            } catch (t: Throwable) {
                Log.e("SenseVoiceFileAsrEngine", "Failed to invoke duration callback", t)
            }
        }
        try {
            val manager = SenseVoiceOnnxManager.getInstance()
            if (!manager.isOnnxAvailable()) {
                reportDuration()
                val msg = context.getString(R.string.error_local_asr_not_ready)
                localLog?.failure(msg)
                listener.onError(msg)
                return
            }
            val resolvedModelCheck = checkSenseVoiceModel(context, prefs)
            val resolvedModel = (resolvedModelCheck as? LocalModelCheck.Ready)?.value
            if (resolvedModel == null) {
                reportDuration()
                val msg = localModelErrorMessage(
                    context,
                    resolvedModelCheck,
                    R.string.error_sensevoice_model_missing
                )
                localLog?.failure(msg)
                listener.onError(msg)
                return
            }

            // PCM16LE -> FloatArray(-1..1)
            val samples = pcmToFloatArray(pcm)
            if (samples.isEmpty()) {
                reportDuration()
                val msg = context.getString(R.string.error_audio_empty)
                localLog?.failure(msg)
                listener.onError(msg)
                return
            }

            // 反射调用 sherpa-onnx Kotlin API
            // 注意：当从绝对路径加载模型/词表时，必须将 assetManager 设为 null
            // 参考 sherpa-onnx 提示 https://github.com/k2-fsa/sherpa-onnx/issues/2562
            // 在需要创建新识别器时，向用户提示"加载中/完成"
            val keepMinutes = try {
                prefs.svKeepAliveMinutes
            } catch (t: Throwable) {
                Log.w("SenseVoiceFileAsrEngine", "Failed to get keep alive minutes", t)
                -1
            }
            val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
            val alwaysKeep = keepMinutes < 0

            val text = manager.decodeOffline(
                assetManager = null,
                tokens = resolvedModel.tokensPath,
                model = resolvedModel.modelPath,
                language = try {
                    resolveSvLanguageForVariant(prefs.svLanguage, resolvedModel.variant)
                } catch (t: Throwable) {
                    Log.w("SenseVoiceFileAsrEngine", "Failed to get language", t)
                    "auto"
                },
                useItn = try {
                    prefs.svUseItn
                } catch (t: Throwable) {
                    Log.w("SenseVoiceFileAsrEngine", "Failed to get useItn", t)
                    false
                },
                provider = "cpu",
                numThreads = try {
                    prefs.svNumThreads
                } catch (t: Throwable) {
                    Log.w("SenseVoiceFileAsrEngine", "Failed to get num threads", t)
                    2
                },
                samples = samples,
                sampleRate = sampleRate,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = {
                    loadLog = LocalAsrCallLogger.startLoad(
                        prefs = prefs,
                        vendor = AsrVendor.SenseVoice,
                        source = "inference_load"
                    )
                    notifyLoadStart()
                },
                onLoadDone = {
                    loadLog?.success("loaded=true")
                    loadLog = null
                    notifyLoadDone()
                }
            )

            if (text.isNullOrBlank()) {
                reportDuration()
                val msg = context.getString(R.string.error_asr_empty_result)
                localLog?.failure(msg)
                listener.onError(msg)
            } else {
                val raw = text.trim()
                reportDuration()
                localLog?.successWithText(raw)
                listener.onFinal(raw)
            }
        } catch (t: Throwable) {
            Log.e("SenseVoiceFileAsrEngine", "Recognition failed", t)
            reportDuration()
            val msg = context.getString(
                R.string.error_recognize_failed_with_reason,
                t.message ?: ""
            )
            loadLog?.failure(t.message ?: msg)
            loadLog = null
            localLog?.failure(msg)
            listener.onError(msg)
        } finally {
            loadLog?.failure("Model load did not complete")
            reportDuration()
        }
    }

    override suspend fun recognizeFromPcm(pcm: ByteArray) {
        recognize(pcm)
    }

    private fun pcmToFloatArray(pcm: ByteArray): FloatArray {
        if (pcm.isEmpty()) return FloatArray(0)
        val n = pcm.size / 2
        val out = FloatArray(n)
        var i = 0
        var offset = 0
        while (i < n) {
            val s = (pcm[offset + 1].toInt() shl 8) or (pcm[offset].toInt() and 0xFF)
            // 32768f 防止 -32768 溢出；限制到 [-1, 1]
            var f = s / 32768.0f
            if (f > 1f) {
                f = 1f
            } else if (f < -1f) {
                f = -1f
            }
            out[i] = f
            i++
            offset += 2
        }
        return out
    }

    // 目录探测改为统一使用顶层 findSvModelDir
}

// 公开卸载入口：供设置页在清除模型后释放本地识别器内存
fun unloadSenseVoiceRecognizer() {
    LocalModelLoadCoordinator.cancel()
    SenseVoiceOnnxManager.getInstance().unload()
}

// 预加载：根据当前配置尝试构建本地识别器，便于降低首次点击等待
fun preloadSenseVoiceIfConfigured(
    context: Context,
    prefs: Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null,
    suppressToastOnStart: Boolean = false,
    // force 参数仅用于兼容旧调用，不再改变保留策略；始终遵循偏好设置
    force: Boolean = false,
    // 该预加载是否紧跟着会被立即使用（例如开始录音）。
    // 若为 true，则不在此处调度卸载，由实际使用/释放时再调度；否则预加载后按设置调度卸载。
    forImmediateUse: Boolean = false
) {
    try {
        val manager = SenseVoiceOnnxManager.getInstance()
        if (!manager.isOnnxAvailable()) {
            LocalAsrCallLogger.recordLoadFailure(
                prefs,
                AsrVendor.SenseVoice,
                "preload",
                context.getString(R.string.error_local_asr_not_ready)
            )
            return
        }

        val resolvedModelCheck = checkSenseVoiceModel(context, prefs)
        val resolvedModel = (resolvedModelCheck as? LocalModelCheck.Ready)?.value
        if (resolvedModel == null) {
            val msg = localModelErrorMessage(
                context,
                resolvedModelCheck,
                R.string.error_sensevoice_model_missing
            )
            LocalAsrCallLogger.recordLoadFailure(
                prefs,
                AsrVendor.SenseVoice,
                "preload",
                msg
            )
            return
        }

        val keepMinutes = prefs.svKeepAliveMinutes
        val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
        val alwaysKeep = keepMinutes < 0

        val language = resolveSvLanguageForVariant(prefs.svLanguage, resolvedModel.variant)
        val useItn = prefs.svUseItn
        val numThreads = prefs.svNumThreads

        val key = "sensevoice|tokens=${resolvedModel.tokensPath}|model=${resolvedModel.modelPath}|lang=$language|itn=$useItn|provider=cpu|threads=$numThreads"
        val mainHandler = Handler(Looper.getMainLooper())

        LocalModelLoadCoordinator.request(key) {
            var loadLog: LocalAsrCallLogger.Session? = null
            val t0 = android.os.SystemClock.uptimeMillis()
            val ok = manager.prepare(
                assetManager = null,
                tokens = resolvedModel.tokensPath,
                model = resolvedModel.modelPath,
                language = language,
                useItn = useItn,
                provider = "cpu",
                numThreads = numThreads,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = {
                    loadLog = LocalAsrCallLogger.startLoad(
                        prefs = prefs,
                        vendor = AsrVendor.SenseVoice,
                        source = "preload"
                    )
                    if (!suppressToastOnStart) {
                        mainHandler.post {
                            Toast.makeText(
                                context,
                                context.getString(R.string.sv_loading_model),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    onLoadStart?.invoke()
                },
                onLoadDone = {
                    loadLog?.success("loaded=true")
                    loadLog = null
                    onLoadDone?.invoke()
                }
            )
            if (!ok) {
                loadLog?.failure("prepare returned false")
                loadLog = null
            }
            if (ok && !forImmediateUse) {
                val dt = (android.os.SystemClock.uptimeMillis() - t0).coerceAtLeast(0)
                mainHandler.post {
                    Toast.makeText(
                        context,
                        context.getString(R.string.sv_model_ready_with_ms, dt),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    } catch (t: Throwable) {
        Log.e("SenseVoiceFileAsrEngine", "Failed to preload SenseVoice", t)
    }
}

internal data class SenseVoiceResolvedModel(
    val variant: String,
    val modelDir: File,
    val tokensPath: String,
    val modelPath: String
)

private object SenseVoiceModelResolverCache {
    @Volatile private var cachedKey: String? = null

    @Volatile private var cachedValue: SenseVoiceResolvedModel? = null

    fun resolve(context: Context, prefs: Prefs): LocalModelCheck<SenseVoiceResolvedModel> {
        val base = try {
            context.getExternalFilesDir(null)
        } catch (t: Throwable) {
            Log.w("SenseVoiceResolver", "Failed to get external files dir", t)
            null
        } ?: context.filesDir
        val probeRoot = File(base, "sensevoice")
        val rawVariant = try {
            prefs.svModelVariant
        } catch (t: Throwable) {
            Log.w("SenseVoiceResolver", "Failed to get model variant", t)
            "small-int8"
        }
        val variant = if (rawVariant == "small-full") "small-full" else "small-int8"
        val cacheKey = probeRoot.absolutePath + "|" + variant
        val cached = if (cachedKey == cacheKey) cachedValue else null
        if (cached != null && isUsable(context, cached)) return LocalModelCheck.Ready(cached)

        val variantDir = File(probeRoot, variant)
        val modelDir = findSvModelDir(variantDir) ?: return resolveFallback(context, probeRoot, variant)
        val tokensPath = File(modelDir, "tokens.txt").absolutePath
        val modelFile = selectSvModelFile(modelDir, variant) ?: return LocalModelCheck.Missing
        val spec = if (variant == "small-full") {
            LocalModelSpecs.SenseVoice.smallFull
        } else {
            LocalModelSpecs.SenseVoice.smallInt8
        }
        when (
            val check = requireModelFilesCached(
                context,
                File(tokensPath) to LocalModelSpecs.SenseVoice.tokens,
                modelFile to spec
            )
        ) {
            is LocalModelCheck.IntegrityError -> return LocalModelCheck.IntegrityError(check.fileName)
            LocalModelCheck.Missing -> return LocalModelCheck.Missing
            is LocalModelCheck.Ready -> Unit
        }

        return LocalModelCheck.Ready(
            SenseVoiceResolvedModel(
                variant = variant,
                modelDir = modelDir,
                tokensPath = tokensPath,
                modelPath = modelFile.absolutePath
            ).also {
                cachedKey = cacheKey
                cachedValue = it
            }
        )
    }

    private fun resolveFallback(context: Context, probeRoot: File, requestedVariant: String): LocalModelCheck<SenseVoiceResolvedModel> {
        val modelDir = findSvModelDir(probeRoot) ?: return LocalModelCheck.Missing
        val tokensPath = File(modelDir, "tokens.txt").absolutePath
        val modelFile = selectSvModelFile(modelDir, requestedVariant) ?: return LocalModelCheck.Missing
        val spec = when (modelFile.name) {
            "model.onnx" -> LocalModelSpecs.SenseVoice.smallFull
            else -> LocalModelSpecs.SenseVoice.smallInt8
        }
        when (
            val check = requireModelFilesCached(
                context,
                File(tokensPath) to LocalModelSpecs.SenseVoice.tokens,
                modelFile to spec
            )
        ) {
            is LocalModelCheck.IntegrityError -> return LocalModelCheck.IntegrityError(check.fileName)
            LocalModelCheck.Missing -> return LocalModelCheck.Missing
            is LocalModelCheck.Ready -> Unit
        }
        return LocalModelCheck.Ready(
            SenseVoiceResolvedModel(
                variant = requestedVariant,
                modelDir = modelDir,
                tokensPath = tokensPath,
                modelPath = modelFile.absolutePath
            )
        )
    }

    private fun isUsable(context: Context, model: SenseVoiceResolvedModel): Boolean {
        val spec = when (File(model.modelPath).name) {
            "model.onnx" -> LocalModelSpecs.SenseVoice.smallFull
            else -> LocalModelSpecs.SenseVoice.smallInt8
        }
        return requireModelFilesCached(
            context,
            File(model.tokensPath) to LocalModelSpecs.SenseVoice.tokens,
            File(model.modelPath) to spec
        ) is LocalModelCheck.Ready
    }
}

internal fun checkSenseVoiceModel(context: Context, prefs: Prefs): LocalModelCheck<SenseVoiceResolvedModel> = SenseVoiceModelResolverCache.resolve(context, prefs)

internal fun resolveSenseVoiceModel(context: Context, prefs: Prefs): SenseVoiceResolvedModel? = (checkSenseVoiceModel(context, prefs) as? LocalModelCheck.Ready)?.value

// 顶层工具：在指定根目录下寻找包含 tokens.txt 的模型目录（最多一层）
fun findSvModelDir(root: java.io.File?): java.io.File? {
    if (root == null || !root.exists()) return null
    val direct = java.io.File(root, "tokens.txt")
    if (direct.exists()) return root
    val subs = root.listFiles() ?: return null
    for (f in subs) {
        if (f.isDirectory) {
            val t = java.io.File(f, "tokens.txt")
            if (t.exists()) return f
        }
    }
    return null
}

/**
 * 根据变体与文件存在情况选择 SenseVoice 模型文件。
 * - small-full 渠道优先使用 fp32（model.onnx），兼容旧包中同时存在 int8 的情况；
 * - 其他变体保持原有“优先 int8 回退 fp32”的策略。
 */
fun selectSvModelFile(dir: java.io.File, variant: String?): java.io.File? {
    val int8File = java.io.File(dir, "model.int8.onnx")
    val f32File = java.io.File(dir, "model.onnx")
    val hasInt8 = int8File.exists()
    val hasF32 = f32File.exists()
    if (!hasInt8 && !hasF32) return null

    val isFullVariant = variant == "small-full"
    return when {
        isFullVariant && hasF32 -> f32File
        !isFullVariant && hasInt8 -> int8File
        hasF32 -> f32File
        hasInt8 -> int8File
        else -> null
    }
}

/**
 * SenseVoice
 */
fun resolveSvLanguageForVariant(language: String, variant: String?): String = language.trim().ifBlank { "auto" }

/**
 * 识别器配置（作为缓存 key）
 */
private data class RecognizerConfig(
    val tokens: String,
    val model: String,
    val language: String,
    val useItn: Boolean,
    val provider: String,
    val numThreads: Int
) {
    fun toCacheKey(): String = listOf(tokens, model, language, useItn, provider, numThreads).joinToString("|")
}

/**
 * 反射式音频流包装
 */
/**
 * SenseVoice ONNX 识别器管理器
 *
 * 通过反射调用 sherpa-onnx Kotlin API，实现离线语音识别。
 * 使用单例模式管理识别器实例和生命周期。
 */
internal class SenseVoiceOnnxManager private constructor() : BaseSherpaOfflineRecognizerManager() {

    companion object {
        private const val TAG = "SenseVoiceOnnxManager"

        @Volatile
        private var instance: SenseVoiceOnnxManager? = null

        fun getInstance(): SenseVoiceOnnxManager = instance ?: synchronized(this) {
            instance ?: SenseVoiceOnnxManager().also { instance = it }
        }
    }

    protected override val tag: String = TAG

    @Volatile private var clsOfflineRecognizer: Class<*>? = null

    @Volatile private var clsOfflineRecognizerConfig: Class<*>? = null

    @Volatile private var clsOfflineModelConfig: Class<*>? = null

    @Volatile private var clsOfflineSenseVoiceModelConfig: Class<*>? = null

    fun isOnnxAvailable(): Boolean = sherpaIsClassAvailable(
        TAG,
        "com.k2fsa.sherpa.onnx.OfflineRecognizer"
    )

    /**
     * 构建 SenseVoice 模型配置
     * sherpa-onnx 1.12.20+: useItn 已重命名为 useInverseTextNormalization
     */
    private fun buildSenseVoiceConfig(model: String, language: String, useItn: Boolean): Any {
        val inst = clsOfflineSenseVoiceModelConfig!!.getDeclaredConstructor().newInstance()
        trySetField(inst, "model", model)
        trySetField(inst, "language", language)
        trySetField(inst, "useInverseTextNormalization", useItn)
        return inst
    }

    /**
     * 构建模型配置
     */
    private fun buildModelConfig(
        tokens: String,
        numThreads: Int,
        provider: String,
        senseVoice: Any
    ): Any {
        val modelConfig = clsOfflineModelConfig!!.getDeclaredConstructor().newInstance()
        trySetField(modelConfig, "tokens", tokens)
        trySetField(modelConfig, "numThreads", numThreads)
        trySetField(modelConfig, "provider", provider)
        trySetField(modelConfig, "debug", false)

        // Kotlin 属性名可能为 senseVoice 或 sense_voice
        if (!trySetField(modelConfig, "senseVoice", senseVoice)) {
            trySetField(modelConfig, "sense_voice", senseVoice)
        }

        return modelConfig
    }

    /**
     * 构建识别器配置
     */
    protected override fun buildRecognizerConfig(config: Any): Any {
        config as RecognizerConfig
        val senseVoice = buildSenseVoiceConfig(config.model, config.language, config.useItn)
        val modelConfig =
            buildModelConfig(config.tokens, config.numThreads, config.provider, senseVoice)

        val recConfig = clsOfflineRecognizerConfig!!.getDeclaredConstructor().newInstance()
        if (!trySetField(recConfig, "modelConfig", modelConfig)) {
            trySetField(recConfig, "model_config", modelConfig)
        }
        return recConfig
    }

    /**
     * 创建识别器实例
     */
    protected override fun createRecognizer(
        assetManager: android.content.res.AssetManager?,
        recognizerConfig: Any
    ): Any = sherpaCreateOfflineRecognizer(
        tag = TAG,
        recognizerClass = clsOfflineRecognizer!!,
        recognizerConfigClass = clsOfflineRecognizerConfig!!,
        assetManager = assetManager,
        recognizerConfig = recognizerConfig
    )

    /**
     * 初始化反射类引用
     */
    protected override fun initClasses() {
        if (clsOfflineRecognizer == null) {
            clsOfflineRecognizer = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
            clsOfflineRecognizerConfig =
                Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")
            clsOfflineModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineModelConfig")
            clsOfflineSenseVoiceModelConfig =
                Class.forName("com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig")
            Log.d(TAG, "Initialized sherpa-onnx reflection classes")
        }
    }

    /**
     * 尝试设置对象字段
     */
    private fun trySetField(target: Any, name: String, value: Any?): Boolean = sherpaTrySetField(TAG, target, name, value)

    protected override fun offlineRecognizerClass(): Class<*> = clsOfflineRecognizer!!

    /**
     * 通过反射完成一次离线解码。依赖于 sherpa-onnx Kotlin API 在运行时可用。
     */
    suspend fun decodeOffline(
        assetManager: android.content.res.AssetManager?,
        tokens: String,
        model: String,
        language: String,
        useItn: Boolean,
        provider: String,
        numThreads: Int,
        samples: FloatArray,
        sampleRate: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null
    ): String? = mutex.withLock {
        try {
            val config = RecognizerConfig(tokens, model, language, useItn, provider, numThreads)
            val recognizer = ensurePreparedLocked(assetManager, config, onLoadStart, onLoadDone)
                ?: return@withLock null

            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate)
                val text = recognizer.decode(stream)
                scheduleAutoUnload(keepAliveMs, alwaysKeep)
                return@withLock text
            } finally {
                stream.release()
            }
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to decode offline: ${t.message}", t)
            return@withLock null
        }
    }

    /**
     * 仅预加载（不解码），用于打开键盘等场景的预热。
     */
    suspend fun prepare(
        assetManager: android.content.res.AssetManager?,
        tokens: String,
        model: String,
        language: String,
        useItn: Boolean,
        provider: String,
        numThreads: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null
    ): Boolean = mutex.withLock {
        try {
            val config = RecognizerConfig(tokens, model, language, useItn, provider, numThreads)
            val ok = ensurePreparedLocked(assetManager, config, onLoadStart, onLoadDone) != null
            if (!ok) return@withLock false
            scheduleAutoUnload(keepAliveMs, alwaysKeep)
            return@withLock true
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to prepare recognizer: ${t.message}", t)
            return@withLock false
        }
    }
}

/**
 * 向后兼容的桥接 object
 * 委托给新的 SenseVoiceOnnxManager 单例
 */
@Deprecated(
    "Use SenseVoiceOnnxManager.getInstance() instead",
    ReplaceWith("SenseVoiceOnnxManager.getInstance()")
)
object SenseVoiceOnnxBridge {
    private val manager = SenseVoiceOnnxManager.getInstance()

    fun isOnnxAvailable(): Boolean = manager.isOnnxAvailable()

    fun unload() = manager.unload()

    fun isPrepared(): Boolean = manager.isPrepared()

    suspend fun decodeOffline(
        assetManager: android.content.res.AssetManager?,
        tokens: String,
        model: String,
        language: String,
        useItn: Boolean,
        provider: String,
        numThreads: Int,
        samples: FloatArray,
        sampleRate: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null
    ): String? = manager.decodeOffline(
        assetManager, tokens, model, language, useItn, provider, numThreads,
        samples, sampleRate, keepAliveMs, alwaysKeep, onLoadStart, onLoadDone
    )

    suspend fun prepare(
        assetManager: android.content.res.AssetManager?,
        tokens: String,
        model: String,
        language: String,
        useItn: Boolean,
        provider: String,
        numThreads: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null
    ): Boolean = manager.prepare(
        assetManager, tokens, model, language, useItn, provider, numThreads,
        keepAliveMs, alwaysKeep, onLoadStart, onLoadDone
    )
}

// 判断是否已有缓存的本地识别器（已加载或正在加载中）
fun isSenseVoicePrepared(): Boolean {
    val manager = SenseVoiceOnnxManager.getInstance()
    return manager.isPrepared() || manager.isPreparing()
}
