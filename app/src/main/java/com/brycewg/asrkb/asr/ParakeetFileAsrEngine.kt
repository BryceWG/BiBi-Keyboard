/**
 * Parakeet 本地离线识别引擎与预加载入口。
 *
 * 归属模块：asr
 */
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

internal class ParakeetFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration, progressiveChunkingEnabled = true),
    PcmBatchRecognizer {

    override val progressiveVendor: AsrVendor = AsrVendor.Parakeet

    private fun showToast(resId: Int) {
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to show toast", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to post toast", t)
        }
    }

    private fun notifyLoadStart() {
        val ui = listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi
        if (ui != null) {
            try {
                ui.onLocalModelLoadStart()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to notify load start", t)
            }
        } else {
            showToast(R.string.sv_loading_model)
        }
    }

    private fun notifyLoadDone() {
        val ui = listener as? SenseVoiceFileAsrEngine.LocalModelLoadUi
        if (ui != null) {
            try {
                ui.onLocalModelLoadDone()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to notify load done", t)
            }
        }
    }

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        val manager = ParakeetOnnxManager.getInstance()
        if (!manager.isOnnxAvailable()) {
            try {
                listener.onError(context.getString(R.string.error_local_asr_not_ready))
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to send error callback", t)
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
                vendor = AsrVendor.Parakeet,
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
                Log.e(TAG, "Failed to invoke duration callback", t)
            }
        }

        try {
            val manager = ParakeetOnnxManager.getInstance()
            if (!manager.isOnnxAvailable()) {
                reportDuration()
                val msg = context.getString(R.string.error_local_asr_not_ready)
                localLog?.failure(msg)
                listener.onError(msg)
                return
            }

            val modelCheck = checkParakeetModel(context, prefs)
            val model = (modelCheck as? LocalModelCheck.Ready)?.value
            if (model == null) {
                reportDuration()
                val msg = localModelErrorMessage(
                    context,
                    modelCheck,
                    R.string.error_parakeet_model_missing
                )
                localLog?.failure(msg)
                listener.onError(msg)
                return
            }

            val samples = parakeetPcmToFloatArray(pcm)
            if (samples.isEmpty()) {
                reportDuration()
                val msg = context.getString(R.string.error_audio_empty)
                localLog?.failure(msg)
                listener.onError(msg)
                return
            }

            val keepMinutes = try {
                prefs.pkKeepAliveMinutes
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to get keep alive minutes", t)
                -1
            }
            val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
            val alwaysKeep = keepMinutes < 0
            val numThreads = try {
                prefs.pkNumThreads
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to get num threads", t)
                3
            }

            val text = manager.decodeOffline(
                assetManager = null,
                tokens = model.tokensPath,
                encoder = model.encoderPath,
                decoder = model.decoderPath,
                joiner = model.joinerPath,
                provider = "cpu",
                numThreads = numThreads,
                samples = samples,
                sampleRate = sampleRate,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = {
                    loadLog = LocalAsrCallLogger.startLoad(
                        prefs = prefs,
                        vendor = AsrVendor.Parakeet,
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
                reportDuration()
                val finalText = text.trim()
                localLog?.successWithText(finalText)
                listener.onFinal(finalText)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Recognition failed", t)
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

    private companion object {
        private const val TAG = "ParakeetFileAsrEngine"
    }
}

internal data class ParakeetResolvedModel(
    val variant: String,
    val modelDir: File,
    val tokensPath: String,
    val encoderPath: String,
    val decoderPath: String,
    val joinerPath: String
)

internal fun unloadParakeetRecognizer() {
    LocalModelLoadCoordinator.cancel()
    ParakeetOnnxManager.getInstance().unload()
}

internal fun isParakeetPrepared(): Boolean {
    val manager = ParakeetOnnxManager.getInstance()
    return manager.isPrepared() || manager.isPreparing()
}

internal fun normalizeParakeetVariant(variant: String?): String = when (variant?.trim()?.lowercase()) {
    "0.6b-v2-int8", "v2-int8", "v2" -> "0.6b-v2-int8"
    else -> "0.6b-v3-int8"
}

internal fun findParakeetModelDir(root: File?): File? {
    if (root == null || !root.exists() || !root.isDirectory || isParakeetTempModelDir(root)) return null
    val hasFiles = File(root, "tokens.txt").exists() &&
        File(root, "encoder.int8.onnx").exists() &&
        File(root, "decoder.int8.onnx").exists() &&
        File(root, "joiner.int8.onnx").exists()
    if (hasFiles) return root
    return root.listFiles()?.firstNotNullOfOrNull { child ->
        if (child.isDirectory && !isParakeetTempModelDir(child)) findParakeetModelDir(child) else null
    }
}

private fun isParakeetTempModelDir(dir: File): Boolean = dir.name.startsWith(".tmp_")

internal fun checkParakeetModel(context: Context, prefs: Prefs): LocalModelCheck<ParakeetResolvedModel> {
    val base = try {
        context.getExternalFilesDir(null)
    } catch (t: Throwable) {
        Log.w("ParakeetResolver", "Failed to get external files dir", t)
        null
    } ?: context.filesDir

    val root = File(base, "parakeet")
    val variant = normalizeParakeetVariant(prefs.pkModelVariant)
    val cacheKey = root.absolutePath + "|" + variant
    val cached = ParakeetModelResolverCache.get(context, cacheKey)
    if (cached != null) return LocalModelCheck.Ready(cached)

    val variantDir = File(root, variant)
    val modelDir = findParakeetModelDir(variantDir) ?: findParakeetModelDir(root) ?: return LocalModelCheck.Missing
    val tokens = File(modelDir, "tokens.txt")
    val encoder = File(modelDir, "encoder.int8.onnx")
    val decoder = File(modelDir, "decoder.int8.onnx")
    val joiner = File(modelDir, "joiner.int8.onnx")
    val specs = parakeetSpecsForVariant(variant)
    when (
        val check = requireModelFilesCached(
            context,
            tokens to specs.tokens,
            encoder to specs.encoder,
            decoder to specs.decoder,
            joiner to specs.joiner
        )
    ) {
        is LocalModelCheck.IntegrityError -> return LocalModelCheck.IntegrityError(check.fileName)
        LocalModelCheck.Missing -> return LocalModelCheck.Missing
        is LocalModelCheck.Ready -> Unit
    }

    return LocalModelCheck.Ready(
        ParakeetResolvedModel(
            variant = variant,
            modelDir = modelDir,
            tokensPath = tokens.absolutePath,
            encoderPath = encoder.absolutePath,
            decoderPath = decoder.absolutePath,
            joinerPath = joiner.absolutePath
        ).also {
            ParakeetModelResolverCache.put(cacheKey, it)
        }
    )
}

internal fun resolveParakeetModel(context: Context, prefs: Prefs): ParakeetResolvedModel? = (checkParakeetModel(context, prefs) as? LocalModelCheck.Ready)?.value

private object ParakeetModelResolverCache {
    @Volatile private var cachedKey: String? = null

    @Volatile private var cachedValue: ParakeetResolvedModel? = null

    fun get(context: Context, key: String): ParakeetResolvedModel? {
        val cached = if (cachedKey == key) cachedValue else null
        return cached?.takeIf { isUsable(context, it) }
    }

    fun put(key: String, model: ParakeetResolvedModel) {
        cachedKey = key
        cachedValue = model
    }

    private fun isUsable(context: Context, model: ParakeetResolvedModel): Boolean {
        val specs = parakeetSpecsForVariant(model.variant)
        return requireModelFilesCached(
            context,
            File(model.tokensPath) to specs.tokens,
            File(model.encoderPath) to specs.encoder,
            File(model.decoderPath) to specs.decoder,
            File(model.joinerPath) to specs.joiner
        ) is LocalModelCheck.Ready
    }
}

private data class ParakeetVariantSpecs(
    val tokens: LocalModelFileSpec,
    val encoder: LocalModelFileSpec,
    val decoder: LocalModelFileSpec,
    val joiner: LocalModelFileSpec
)

private fun parakeetSpecsForVariant(variant: String): ParakeetVariantSpecs = if (normalizeParakeetVariant(variant) == "0.6b-v2-int8") {
    ParakeetVariantSpecs(
        tokens = LocalModelSpecs.Parakeet.v2Tokens,
        encoder = LocalModelSpecs.Parakeet.v2Encoder,
        decoder = LocalModelSpecs.Parakeet.v2Decoder,
        joiner = LocalModelSpecs.Parakeet.v2Joiner
    )
} else {
    ParakeetVariantSpecs(
        tokens = LocalModelSpecs.Parakeet.v3Tokens,
        encoder = LocalModelSpecs.Parakeet.v3Encoder,
        decoder = LocalModelSpecs.Parakeet.v3Decoder,
        joiner = LocalModelSpecs.Parakeet.v3Joiner
    )
}

internal class ParakeetOnnxManager private constructor() : BaseSherpaOfflineRecognizerManager() {

    companion object {
        private const val TAG = "ParakeetOnnxManager"

        @Volatile
        private var instance: ParakeetOnnxManager? = null

        fun getInstance(): ParakeetOnnxManager = instance ?: synchronized(this) {
            instance ?: ParakeetOnnxManager().also { instance = it }
        }
    }

    protected override val tag: String = TAG

    @Volatile private var clsOfflineRecognizer: Class<*>? = null

    @Volatile private var clsOfflineRecognizerConfig: Class<*>? = null

    @Volatile private var clsOfflineModelConfig: Class<*>? = null

    @Volatile private var clsFeatureConfig: Class<*>? = null

    @Volatile private var clsOfflineTransducerModelConfig: Class<*>? = null

    fun isOnnxAvailable(): Boolean = sherpaIsClassAvailable(
        TAG,
        "com.k2fsa.sherpa.onnx.OfflineRecognizer",
        "com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig"
    )

    private data class RecognizerConfig(
        val tokens: String,
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val provider: String,
        val numThreads: Int,
        val sampleRate: Int,
        val featureDim: Int
    )

    protected override fun initClasses() {
        if (clsOfflineRecognizer == null) {
            clsOfflineRecognizer = Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
            clsOfflineRecognizerConfig =
                Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizerConfig")
            clsOfflineModelConfig = Class.forName("com.k2fsa.sherpa.onnx.OfflineModelConfig")
            clsFeatureConfig = Class.forName("com.k2fsa.sherpa.onnx.FeatureConfig")
            clsOfflineTransducerModelConfig =
                Class.forName("com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig")
            Log.d(TAG, "Initialized sherpa-onnx reflection classes for Parakeet")
        }
    }

    private fun trySetField(target: Any, name: String, value: Any?): Boolean = sherpaTrySetField(TAG, target, name, value)

    private fun buildFeatureConfig(sampleRate: Int, featureDim: Int): Any {
        val feat = clsFeatureConfig!!.getDeclaredConstructor().newInstance()
        trySetField(feat, "sampleRate", sampleRate)
        trySetField(feat, "featureDim", featureDim)
        trySetField(feat, "dither", 0.0f)
        return feat
    }

    private fun buildTransducerModelConfig(encoder: String, decoder: String, joiner: String): Any {
        val inst = clsOfflineTransducerModelConfig!!.getDeclaredConstructor().newInstance()
        trySetField(inst, "encoder", encoder)
        trySetField(inst, "decoder", decoder)
        trySetField(inst, "joiner", joiner)
        return inst
    }

    private fun buildModelConfig(
        tokens: String,
        encoder: String,
        decoder: String,
        joiner: String,
        numThreads: Int,
        provider: String
    ): Any {
        val modelConfig = clsOfflineModelConfig!!.getDeclaredConstructor().newInstance()
        trySetField(modelConfig, "tokens", tokens)
        trySetField(modelConfig, "numThreads", numThreads)
        trySetField(modelConfig, "provider", provider)
        trySetField(modelConfig, "debug", false)
        trySetField(modelConfig, "modelType", "nemo_transducer")
        val transducer = buildTransducerModelConfig(encoder, decoder, joiner)
        if (!trySetField(modelConfig, "transducer", transducer)) {
            trySetField(modelConfig, "transducerModel", transducer)
        }
        return modelConfig
    }

    protected override fun buildRecognizerConfig(config: Any): Any {
        config as RecognizerConfig
        val modelConfig = buildModelConfig(
            tokens = config.tokens,
            encoder = config.encoder,
            decoder = config.decoder,
            joiner = config.joiner,
            numThreads = config.numThreads,
            provider = config.provider
        )
        val featConfig = buildFeatureConfig(config.sampleRate, config.featureDim)
        val recConfig = clsOfflineRecognizerConfig!!.getDeclaredConstructor().newInstance()
        if (!trySetField(recConfig, "modelConfig", modelConfig)) {
            trySetField(recConfig, "model_config", modelConfig)
        }
        if (!trySetField(recConfig, "featConfig", featConfig)) {
            trySetField(recConfig, "feat_config", featConfig)
        }
        trySetField(recConfig, "decodingMethod", "greedy_search")
        trySetField(recConfig, "maxActivePaths", 4)
        return recConfig
    }

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

    protected override fun offlineRecognizerClass(): Class<*> = clsOfflineRecognizer!!

    suspend fun decodeOffline(
        assetManager: android.content.res.AssetManager?,
        tokens: String,
        encoder: String,
        decoder: String,
        joiner: String,
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
            val cfg = RecognizerConfig(
                tokens = tokens,
                encoder = encoder,
                decoder = decoder,
                joiner = joiner,
                provider = provider,
                numThreads = numThreads,
                sampleRate = sampleRate,
                featureDim = 80
            )
            val recognizer = ensurePreparedLocked(assetManager, cfg, onLoadStart, onLoadDone)
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
            Log.e(TAG, "Failed to decode offline Parakeet: ${t.message}", t)
            return@withLock null
        }
    }

    suspend fun prepare(
        assetManager: android.content.res.AssetManager?,
        tokens: String,
        encoder: String,
        decoder: String,
        joiner: String,
        provider: String,
        numThreads: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null
    ): Boolean = mutex.withLock {
        try {
            val cfg = RecognizerConfig(
                tokens = tokens,
                encoder = encoder,
                decoder = decoder,
                joiner = joiner,
                provider = provider,
                numThreads = numThreads,
                sampleRate = 16000,
                featureDim = 80
            )
            val ok = ensurePreparedLocked(assetManager, cfg, onLoadStart, onLoadDone) != null
            if (!ok) return@withLock false
            scheduleAutoUnload(keepAliveMs, alwaysKeep)
            true
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to prepare Parakeet recognizer: ${t.message}", t)
            false
        }
    }
}

internal fun preloadParakeetIfConfigured(
    context: Context,
    prefs: Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null,
    suppressToastOnStart: Boolean = false,
    forImmediateUse: Boolean = false
) {
    try {
        val manager = ParakeetOnnxManager.getInstance()
        if (!manager.isOnnxAvailable()) {
            LocalAsrCallLogger.recordLoadFailure(
                prefs,
                AsrVendor.Parakeet,
                "preload",
                context.getString(R.string.error_local_asr_not_ready)
            )
            return
        }

        val modelCheck = checkParakeetModel(context, prefs)
        val model = (modelCheck as? LocalModelCheck.Ready)?.value
        if (model == null) {
            val msg = localModelErrorMessage(
                context,
                modelCheck,
                R.string.error_parakeet_model_missing
            )
            LocalAsrCallLogger.recordLoadFailure(
                prefs,
                AsrVendor.Parakeet,
                "preload",
                msg
            )
            return
        }
        val keepMinutes = prefs.pkKeepAliveMinutes
        val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
        val alwaysKeep = keepMinutes < 0
        val numThreads = prefs.pkNumThreads
        val key = "parakeet|" +
            "variant=${model.variant}|" +
            "tokens=${model.tokensPath}|" +
            "encoder=${model.encoderPath}|" +
            "decoder=${model.decoderPath}|" +
            "joiner=${model.joinerPath}|" +
            "provider=cpu|" +
            "threads=$numThreads"

        val mainHandler = Handler(Looper.getMainLooper())
        LocalModelLoadCoordinator.request(key) {
            var loadLog: LocalAsrCallLogger.Session? = null
            val t0 = android.os.SystemClock.uptimeMillis()
            val ok = manager.prepare(
                assetManager = null,
                tokens = model.tokensPath,
                encoder = model.encoderPath,
                decoder = model.decoderPath,
                joiner = model.joinerPath,
                provider = "cpu",
                numThreads = numThreads,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = {
                    loadLog = LocalAsrCallLogger.startLoad(
                        prefs = prefs,
                        vendor = AsrVendor.Parakeet,
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
        Log.e("ParakeetFileAsrEngine", "Failed to preload Parakeet model", t)
    }
}

internal fun parakeetPcmToFloatArray(pcm: ByteArray): FloatArray {
    if (pcm.isEmpty()) return FloatArray(0)
    val n = pcm.size / 2
    val out = FloatArray(n)
    var index = 0
    var offset = 0
    while (index < n) {
        val sample = (pcm[offset + 1].toInt() shl 8) or (pcm[offset].toInt() and 0xFF)
        var value = sample / 32768.0f
        if (value > 1f) {
            value = 1f
        } else if (value < -1f) {
            value = -1f
        }
        out[index] = value
        index++
        offset += 2
    }
    return out
}
