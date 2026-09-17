/**
 * Qwen3-ASR 本地离线识别引擎与预加载入口。
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

internal class Qwen3AsrFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration, progressiveChunkingEnabled = true),
    PcmBatchRecognizer {

    override val progressiveVendor: AsrVendor = AsrVendor.Qwen3Asr

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
        val manager = Qwen3AsrOnnxManager.getInstance()
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
                vendor = AsrVendor.Qwen3Asr,
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
            val manager = Qwen3AsrOnnxManager.getInstance()
            if (!manager.isOnnxAvailable()) {
                reportDuration()
                val msg = context.getString(R.string.error_local_asr_not_ready)
                localLog?.failure(msg)
                listener.onError(msg)
                return
            }

            val resolvedModelCheck = checkQwen3AsrModel(context, prefs)
            val resolvedModel = (resolvedModelCheck as? LocalModelCheck.Ready)?.value
            if (resolvedModel == null) {
                reportDuration()
                val msg = localModelErrorMessage(
                    context,
                    resolvedModelCheck,
                    R.string.error_qwen3_asr_model_missing
                )
                localLog?.failure(msg)
                listener.onError(msg)
                return
            }

            val chunks = splitLocalOfflinePcm16WithVad(context, prefs, pcm, sampleRate)
            if (chunks.isEmpty()) {
                reportDuration()
                val msg = context.getString(R.string.error_audio_empty)
                localLog?.failure(msg)
                listener.onError(msg)
                return
            }

            val keepMinutes = try {
                prefs.qwKeepAliveMinutes
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to get keep alive minutes", t)
                -1
            }
            val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
            val alwaysKeep = keepMinutes < 0
            val numThreads = try {
                prefs.qwNumThreads
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to get num threads", t)
                3
            }

            val texts = ArrayList<String>(chunks.size)
            for (chunk in chunks) {
                val text = manager.decodeOffline(
                    assetManager = null,
                    convFrontend = resolvedModel.convFrontendPath,
                    encoder = resolvedModel.encoderPath,
                    decoder = resolvedModel.decoderPath,
                    tokenizerDir = resolvedModel.tokenizerDirPath,
                    provider = "cpu",
                    numThreads = numThreads,
                    samples = qwen3AsrPcmToFloatArray(chunk),
                    sampleRate = sampleRate,
                    keepAliveMs = keepMs,
                    alwaysKeep = alwaysKeep,
                    onLoadStart = {
                        loadLog = LocalAsrCallLogger.startLoad(
                            prefs = prefs,
                            vendor = AsrVendor.Qwen3Asr,
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
                val sanitized = sanitizeQwen3AsrResult(text)
                if (sanitized.isNotEmpty()) texts += sanitized
            }
            val text = joinNonStreamingChunkTexts(texts)

            if (text.isBlank()) {
                reportDuration()
                val msg = context.getString(R.string.error_asr_empty_result)
                localLog?.failure(msg)
                listener.onError(msg)
            } else {
                val finalText = if (isProgressiveChunkDecode) {
                    text.trim()
                } else {
                    finalizeCombinedProgressiveText(text)
                }
                reportDuration()
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

    override suspend fun finalizeCombinedProgressiveText(text: String): String {
        val trimmed = text.trim()
        val useItn = try {
            prefs.qwUseItn
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to get qwUseItn", t)
            false
        }
        return if (useItn) ChineseItn.normalize(trimmed) else trimmed
    }

    private companion object {
        private const val TAG = "Qwen3AsrFileAsrEngine"
    }
}

internal fun sanitizeQwen3AsrResult(text: String?): String {
    if (text.isNullOrBlank()) return ""
    val marker = "<asr_text>"
    var out = text.trim().trimStart('\u200B', '\uFEFF')
    val markerIndex = out.indexOf(marker)
    if (markerIndex >= 0) {
        val prefix = out.substring(0, markerIndex).trim()
        if (prefix.isEmpty() || prefix.startsWith("language ", ignoreCase = true)) {
            out = out.substring(markerIndex + marker.length)
        }
        out = out.replace(marker, "")
    }
    return out
        .replace("<|im_end|>", "")
        .replace("<|im_start|>", "")
        .trim()
}

internal fun unloadQwen3AsrRecognizer() {
    LocalModelLoadCoordinator.cancel()
    Qwen3AsrOnnxManager.getInstance().unload()
}

internal fun isQwen3AsrPrepared(): Boolean {
    val manager = Qwen3AsrOnnxManager.getInstance()
    return manager.isPrepared() || manager.isPreparing()
}

internal fun normalizeQwen3AsrVariant(variant: String?): String = when (variant?.trim()?.lowercase()) {
    "qwen3-0.6b-int8", "qwen3-0.6b", "0.6b-int8", "0.6b" -> "qwen3-0.6b-int8"
    "qwen3-1.7b-int8", "qwen3-1.7b", "1.7b-int8", "1.7b" -> "qwen3-1.7b-int8"
    else -> "qwen3-0.6b-int8"
}

internal fun findQwen3AsrTokenizerDir(modelDir: File): File? {
    val tokenizer = File(modelDir, "tokenizer")
    if (isQwen3AsrTokenizerDir(tokenizer)) return tokenizer
    if (isQwen3AsrTokenizerDir(modelDir)) return modelDir
    return findQwen3AsrTokenizerDirRecursive(modelDir, maxDepth = 3)
}

internal fun findQwen3AsrModelDir(root: File?): File? {
    if (root == null || !root.exists()) return null
    return findQwen3AsrModelDirRecursive(root, maxDepth = 6)
}

private fun findQwen3AsrTokenizerDirRecursive(root: File, maxDepth: Int): File? {
    if (maxDepth < 0 || !root.isDirectory) return null
    if (isQwen3AsrTokenizerDir(root)) return root
    val subs = root.listFiles() ?: return null
    for (f in subs) {
        if (f.isDirectory) {
            findQwen3AsrTokenizerDirRecursive(f, maxDepth - 1)?.let { return it }
        }
    }
    return null
}

private fun isQwen3AsrModelDir(dir: File): Boolean = File(dir, "conv_frontend.onnx").exists() &&
    File(dir, "encoder.int8.onnx").exists() &&
    File(dir, "decoder.int8.onnx").exists() &&
    findQwen3AsrTokenizerDir(dir) != null

internal fun isQwen3AsrTokenizerDir(dir: File): Boolean {
    if (!dir.isDirectory) return false
    return File(dir, "vocab.json").exists() &&
        File(dir, "merges.txt").exists() &&
        File(dir, "tokenizer_config.json").exists()
}

private fun findQwen3AsrModelDirRecursive(root: File, maxDepth: Int): File? {
    if (maxDepth < 0 || !root.isDirectory) return null
    if (isQwen3AsrModelDir(root)) return root
    val subs = root.listFiles() ?: return null
    for (f in subs) {
        if (f.isDirectory && f.name != "__MACOSX") {
            findQwen3AsrModelDirRecursive(f, maxDepth - 1)?.let { return it }
        }
    }
    return null
}

internal class Qwen3AsrOnnxManager private constructor() : BaseSherpaOfflineRecognizerManager() {

    companion object {
        private const val TAG = "Qwen3AsrOnnxManager"

        @Volatile
        private var instance: Qwen3AsrOnnxManager? = null

        fun getInstance(): Qwen3AsrOnnxManager = instance ?: synchronized(this) {
            instance ?: Qwen3AsrOnnxManager().also { instance = it }
        }
    }

    protected override val tag: String = TAG

    @Volatile
    private var clsOfflineRecognizer: Class<*>? = null

    @Volatile
    private var clsOfflineRecognizerConfig: Class<*>? = null

    @Volatile
    private var clsOfflineModelConfig: Class<*>? = null

    @Volatile
    private var clsFeatureConfig: Class<*>? = null

    @Volatile
    private var clsOfflineQwen3AsrModelConfig: Class<*>? = null

    fun isOnnxAvailable(): Boolean = sherpaIsClassAvailable(
        TAG,
        "com.k2fsa.sherpa.onnx.OfflineRecognizer",
        "com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig"
    )

    private data class RecognizerConfig(
        val convFrontend: String,
        val encoder: String,
        val decoder: String,
        val tokenizerDir: String,
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
            clsOfflineQwen3AsrModelConfig =
                Class.forName("com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig")
            Log.d(TAG, "Initialized sherpa-onnx reflection classes for Qwen3-ASR")
        }
    }

    private fun trySetField(target: Any, name: String, value: Any?): Boolean = sherpaTrySetField(TAG, target, name, value)

    private fun buildFeatureConfig(sampleRate: Int, featureDim: Int): Any {
        val feat = clsFeatureConfig!!.getDeclaredConstructor().newInstance()
        trySetField(feat, "sampleRate", sampleRate)
        trySetField(feat, "featureDim", featureDim)
        return feat
    }

    private fun buildQwen3AsrModelConfig(
        convFrontend: String,
        encoder: String,
        decoder: String,
        tokenizerDir: String
    ): Any {
        val inst = clsOfflineQwen3AsrModelConfig!!.getDeclaredConstructor().newInstance()
        trySetField(inst, "convFrontend", convFrontend)
        trySetField(inst, "encoder", encoder)
        trySetField(inst, "decoder", decoder)
        trySetField(inst, "tokenizer", tokenizerDir)
        trySetField(inst, "maxTotalLen", 512)
        trySetField(inst, "maxNewTokens", 512)
        return inst
    }

    private fun buildModelConfig(
        convFrontend: String,
        encoder: String,
        decoder: String,
        tokenizerDir: String,
        numThreads: Int,
        provider: String
    ): Any {
        val modelConfig = clsOfflineModelConfig!!.getDeclaredConstructor().newInstance()
        trySetField(modelConfig, "tokens", "")
        trySetField(modelConfig, "numThreads", numThreads)
        trySetField(modelConfig, "provider", provider)
        trySetField(modelConfig, "debug", false)

        val qwen3Asr = buildQwen3AsrModelConfig(
            convFrontend = convFrontend,
            encoder = encoder,
            decoder = decoder,
            tokenizerDir = tokenizerDir
        )
        if (!trySetField(modelConfig, "qwen3Asr", qwen3Asr)) {
            trySetField(modelConfig, "qwen3_asr", qwen3Asr)
        }
        return modelConfig
    }

    protected override fun buildRecognizerConfig(config: Any): Any {
        config as RecognizerConfig
        val modelConfig = buildModelConfig(
            convFrontend = config.convFrontend,
            encoder = config.encoder,
            decoder = config.decoder,
            tokenizerDir = config.tokenizerDir,
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
        convFrontend: String,
        encoder: String,
        decoder: String,
        tokenizerDir: String,
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
                convFrontend = convFrontend,
                encoder = encoder,
                decoder = decoder,
                tokenizerDir = tokenizerDir,
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
            Log.e(TAG, "Failed to decode offline Qwen3-ASR: ${t.message}", t)
            return@withLock null
        }
    }

    suspend fun prepare(
        assetManager: android.content.res.AssetManager?,
        convFrontend: String,
        encoder: String,
        decoder: String,
        tokenizerDir: String,
        provider: String,
        numThreads: Int,
        keepAliveMs: Long,
        alwaysKeep: Boolean,
        onLoadStart: (() -> Unit)? = null,
        onLoadDone: (() -> Unit)? = null
    ): Boolean = mutex.withLock {
        try {
            val cfg = RecognizerConfig(
                convFrontend = convFrontend,
                encoder = encoder,
                decoder = decoder,
                tokenizerDir = tokenizerDir,
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
            Log.e(TAG, "Failed to prepare Qwen3-ASR recognizer: ${t.message}", t)
            false
        }
    }
}

internal fun preloadQwen3AsrIfConfigured(
    context: Context,
    prefs: Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null,
    suppressToastOnStart: Boolean = false,
    forImmediateUse: Boolean = false
) {
    try {
        val manager = Qwen3AsrOnnxManager.getInstance()
        if (!manager.isOnnxAvailable()) {
            LocalAsrCallLogger.recordLoadFailure(
                prefs,
                AsrVendor.Qwen3Asr,
                "preload",
                context.getString(R.string.error_local_asr_not_ready)
            )
            return
        }

        val resolvedModelCheck = checkQwen3AsrModel(context, prefs)
        val resolvedModel = (resolvedModelCheck as? LocalModelCheck.Ready)?.value
        if (resolvedModel == null) {
            val msg = localModelErrorMessage(
                context,
                resolvedModelCheck,
                R.string.error_qwen3_asr_model_missing
            )
            LocalAsrCallLogger.recordLoadFailure(
                prefs,
                AsrVendor.Qwen3Asr,
                "preload",
                msg
            )
            return
        }
        val keepMinutes = prefs.qwKeepAliveMinutes
        val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
        val alwaysKeep = keepMinutes < 0
        val numThreads = prefs.qwNumThreads
        val key = "qwen3_asr|" +
            "conv=${resolvedModel.convFrontendPath}|" +
            "encoder=${resolvedModel.encoderPath}|" +
            "decoder=${resolvedModel.decoderPath}|" +
            "tokenizer=${resolvedModel.tokenizerDirPath}|" +
            "provider=cpu|" +
            "threads=$numThreads"

        val mainHandler = Handler(Looper.getMainLooper())
        LocalModelLoadCoordinator.request(key) {
            var loadLog: LocalAsrCallLogger.Session? = null
            val t0 = android.os.SystemClock.uptimeMillis()
            val ok = manager.prepare(
                assetManager = null,
                convFrontend = resolvedModel.convFrontendPath,
                encoder = resolvedModel.encoderPath,
                decoder = resolvedModel.decoderPath,
                tokenizerDir = resolvedModel.tokenizerDirPath,
                provider = "cpu",
                numThreads = numThreads,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = {
                    loadLog = LocalAsrCallLogger.startLoad(
                        prefs = prefs,
                        vendor = AsrVendor.Qwen3Asr,
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
        Log.e("Qwen3AsrFileAsrEngine", "Failed to preload Qwen3-ASR model", t)
    }
}

internal data class Qwen3AsrResolvedModel(
    val modelDir: File,
    val convFrontendPath: String,
    val encoderPath: String,
    val decoderPath: String,
    val tokenizerDirPath: String
)

private object Qwen3AsrModelResolverCache {
    @Volatile private var cachedKey: String? = null

    @Volatile private var cachedValue: Qwen3AsrResolvedModel? = null

    fun resolve(context: Context, variant: String): LocalModelCheck<Qwen3AsrResolvedModel> {
        val base = try {
            context.getExternalFilesDir(null)
        } catch (t: Throwable) {
            Log.w("Qwen3AsrResolver", "Failed to get external files dir", t)
            null
        } ?: context.filesDir
        val probeRoot = File(base, "qwen3_asr")
        val cacheKey = probeRoot.absolutePath + "|" + variant
        val cached = if (cachedKey == cacheKey) cachedValue else null
        if (cached != null && isUsable(context, cached, variant)) return LocalModelCheck.Ready(cached)

        val variantDir = File(probeRoot, variant)
        val modelDir = findQwen3AsrModelDir(variantDir) ?: return LocalModelCheck.Missing
        val convFrontend = File(modelDir, "conv_frontend.onnx")
        val encoder = File(modelDir, "encoder.int8.onnx")
        val decoder = File(modelDir, "decoder.int8.onnx")
        val tokenizerDir = findQwen3AsrTokenizerDir(modelDir) ?: return LocalModelCheck.Missing
        if (!isQwen3AsrTokenizerDir(tokenizerDir)) return LocalModelCheck.Missing
        val specs = LocalModelSpecs.Qwen3Asr.forVariant(variant)
        when (
            val check = requireModelFilesCached(
                context,
                convFrontend to specs.convFrontend,
                encoder to specs.encoder,
                decoder to specs.decoder,
                File(tokenizerDir, "merges.txt") to specs.merges,
                File(tokenizerDir, "tokenizer_config.json") to specs.tokenizerConfig,
                File(tokenizerDir, "vocab.json") to specs.vocab
            )
        ) {
            is LocalModelCheck.IntegrityError -> return LocalModelCheck.IntegrityError(check.fileName)
            LocalModelCheck.Missing -> return LocalModelCheck.Missing
            is LocalModelCheck.Ready -> Unit
        }

        return LocalModelCheck.Ready(
            Qwen3AsrResolvedModel(
                modelDir = modelDir,
                convFrontendPath = convFrontend.absolutePath,
                encoderPath = encoder.absolutePath,
                decoderPath = decoder.absolutePath,
                tokenizerDirPath = tokenizerDir.absolutePath
            ).also {
                cachedKey = cacheKey
                cachedValue = it
            }
        )
    }

    private fun isUsable(context: Context, model: Qwen3AsrResolvedModel, variant: String): Boolean {
        val specs = LocalModelSpecs.Qwen3Asr.forVariant(variant)
        return requireModelFilesCached(
            context,
            File(model.convFrontendPath) to specs.convFrontend,
            File(model.encoderPath) to specs.encoder,
            File(model.decoderPath) to specs.decoder,
            File(model.tokenizerDirPath, "merges.txt") to specs.merges,
            File(model.tokenizerDirPath, "tokenizer_config.json") to specs.tokenizerConfig,
            File(model.tokenizerDirPath, "vocab.json") to specs.vocab
        ) is LocalModelCheck.Ready
    }
}

internal fun checkQwen3AsrModel(context: Context, prefs: Prefs): LocalModelCheck<Qwen3AsrResolvedModel> = Qwen3AsrModelResolverCache.resolve(context, normalizeQwen3AsrVariant(prefs.qwModelVariant))

internal fun resolveQwen3AsrModel(context: Context, prefs: Prefs): Qwen3AsrResolvedModel? = (checkQwen3AsrModel(context, prefs) as? LocalModelCheck.Ready)?.value

private fun qwen3AsrPcmToFloatArray(pcm: ByteArray): FloatArray {
    if (pcm.isEmpty()) return FloatArray(0)
    val n = pcm.size / 2
    val out = FloatArray(n)
    var i = 0
    var offset = 0
    while (i < n) {
        val s = (pcm[offset + 1].toInt() shl 8) or (pcm[offset].toInt() and 0xFF)
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
