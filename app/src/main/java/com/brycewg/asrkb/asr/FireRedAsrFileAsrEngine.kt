/**
 * FireRedASR V2 本地离线识别引擎与预加载入口。
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

internal class FireRedAsrFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration, progressiveChunkingEnabled = true),
    PcmBatchRecognizer {

    override val progressiveVendor: AsrVendor = AsrVendor.FireRedAsr

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
        val manager = FireRedAsrOnnxManager.getInstance()
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
                vendor = AsrVendor.FireRedAsr,
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
            try {
                SherpaPunctuationManager.maybeWarnModelMissing(context)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to warn punctuation model missing", t)
            }

            val manager = FireRedAsrOnnxManager.getInstance()
            if (!manager.isOnnxAvailable()) {
                reportDuration()
                val msg = context.getString(R.string.error_local_asr_not_ready)
                localLog?.failure(msg)
                listener.onError(msg)
                return
            }

            val modelFilesCheck = checkFireRedAsrModelFiles(context, prefs)
            val modelFiles = (modelFilesCheck as? LocalModelCheck.Ready)?.value
            if (modelFiles == null) {
                reportDuration()
                val msg = localModelErrorMessage(
                    context,
                    modelFilesCheck,
                    R.string.error_firered_asr_model_missing
                )
                localLog?.failure(msg)
                listener.onError(msg)
                return
            }

            val samples = fireRedAsrPcmToFloatArray(pcm)
            if (samples.isEmpty()) {
                reportDuration()
                val msg = context.getString(R.string.error_audio_empty)
                localLog?.failure(msg)
                listener.onError(msg)
                return
            }

            val keepMinutes = try {
                prefs.frKeepAliveMinutes
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to get keep alive minutes", t)
                -1
            }
            val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
            val alwaysKeep = keepMinutes < 0
            val numThreads = try {
                prefs.frNumThreads
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to get num threads", t)
                2
            }

            val text = manager.decodeOffline(
                assetManager = null,
                tokens = modelFiles.tokensPath,
                ctcModel = modelFiles.ctcModelPath,
                encoder = modelFiles.encoderPath,
                decoder = modelFiles.decoderPath,
                provider = "cpu",
                numThreads = numThreads,
                samples = samples,
                sampleRate = sampleRate,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = {
                    loadLog = LocalAsrCallLogger.startLoad(
                        prefs = prefs,
                        vendor = AsrVendor.FireRedAsr,
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

            val sanitizedText = sanitizeFireRedAsrResult(text)
            if (sanitizedText.isNullOrEmpty()) {
                reportDuration()
                val msg = context.getString(R.string.error_asr_empty_result)
                localLog?.failure(msg)
                listener.onError(msg)
            } else {
                val finalText = if (isProgressiveChunkDecode) {
                    sanitizedText
                } else {
                    finalizeCombinedProgressiveText(sanitizedText)
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
        val useItn = try {
            prefs.frUseItn
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to get frUseItn", t)
            false
        }
        val normalized = if (useItn) ChineseItn.normalize(text) else text
        val numThreads = try {
            prefs.frNumThreads
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to get num threads", t)
            2
        }
        return try {
            SherpaPunctuationManager.getInstance().addOfflinePunctuation(
                context = context,
                text = normalized,
                numThreads = numThreads
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to apply offline punctuation", t)
            normalized
        }
    }

    private companion object {
        private const val TAG = "FireRedAsrFileAsrEngine"
    }
}

internal data class FireRedAsrResolvedModel(
    val variant: String,
    val modelDir: File,
    val tokensPath: String,
    val ctcModelPath: String? = null,
    val encoderPath: String? = null,
    val decoderPath: String? = null
)

internal fun unloadFireRedAsrRecognizer() {
    LocalModelLoadCoordinator.cancel()
    FireRedAsrOnnxManager.getInstance().unload()
}

internal fun isFireRedAsrPrepared(): Boolean {
    val manager = FireRedAsrOnnxManager.getInstance()
    return manager.isPrepared() || manager.isPreparing()
}

internal fun preloadFireRedAsrIfConfigured(
    context: Context,
    prefs: Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null,
    suppressToastOnStart: Boolean = false,
    forImmediateUse: Boolean = false
) {
    try {
        val manager = FireRedAsrOnnxManager.getInstance()
        if (!manager.isOnnxAvailable()) {
            LocalAsrCallLogger.recordLoadFailure(
                prefs,
                AsrVendor.FireRedAsr,
                "preload",
                context.getString(R.string.error_local_asr_not_ready)
            )
            return
        }

        val modelFilesCheck = checkFireRedAsrModelFiles(context, prefs)
        val modelFiles = (modelFilesCheck as? LocalModelCheck.Ready)?.value
        if (modelFiles == null) {
            val msg = localModelErrorMessage(
                context,
                modelFilesCheck,
                R.string.error_firered_asr_model_missing
            )
            LocalAsrCallLogger.recordLoadFailure(
                prefs,
                AsrVendor.FireRedAsr,
                "preload",
                msg
            )
            return
        }
        val keepMinutes = prefs.frKeepAliveMinutes
        val keepMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L
        val alwaysKeep = keepMinutes < 0
        val numThreads = prefs.frNumThreads
        val key = buildString {
            append("firered_asr|")
            append("variant=")
            append(modelFiles.variant)
            append("|tokens=")
            append(modelFiles.tokensPath)
            append("|ctc=")
            append(modelFiles.ctcModelPath ?: "")
            append("|encoder=")
            append(modelFiles.encoderPath ?: "")
            append("|decoder=")
            append(modelFiles.decoderPath ?: "")
            append("|provider=cpu|threads=")
            append(numThreads)
        }

        val mainHandler = Handler(Looper.getMainLooper())
        LocalModelLoadCoordinator.request(key) {
            var loadLog: LocalAsrCallLogger.Session? = null
            val t0 = android.os.SystemClock.uptimeMillis()
            val ok = manager.prepare(
                assetManager = null,
                tokens = modelFiles.tokensPath,
                ctcModel = modelFiles.ctcModelPath,
                encoder = modelFiles.encoderPath,
                decoder = modelFiles.decoderPath,
                provider = "cpu",
                numThreads = numThreads,
                keepAliveMs = keepMs,
                alwaysKeep = alwaysKeep,
                onLoadStart = {
                    loadLog = LocalAsrCallLogger.startLoad(
                        prefs = prefs,
                        vendor = AsrVendor.FireRedAsr,
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
            if (ok) {
                try {
                    SherpaPunctuationManager.getInstance().ensureOfflineLoaded(
                        context = context,
                        numThreads = numThreads
                    )
                } catch (t: Throwable) {
                    Log.w("FireRedAsrFileAsrEngine", "Failed to preload punctuation model with FireRedASR", t)
                }
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
        Log.e("FireRedAsrFileAsrEngine", "Failed to preload FireRedASR model", t)
    }
}

internal fun findFireRedAsrModelDir(root: File?): File? {
    if (root == null || !root.exists() || !root.isDirectory || isFireRedTempModelDir(root)) return null
    val hasTokens = File(root, "tokens.txt").exists()
    val hasCtcModel = File(root, "model.int8.onnx").exists()
    if (hasTokens && hasCtcModel) {
        return root
    }
    return root.listFiles()?.firstNotNullOfOrNull { child ->
        if (child.isDirectory && !isFireRedTempModelDir(child)) findFireRedAsrModelDir(child) else null
    }
}

private fun isFireRedTempModelDir(dir: File): Boolean = dir.name.startsWith(".tmp_")

internal fun checkFireRedAsrModelFiles(context: Context, prefs: Prefs): LocalModelCheck<FireRedAsrResolvedModel> {
    val base = try {
        context.getExternalFilesDir(null)
    } catch (t: Throwable) {
        Log.w("FireRedAsrResolver", "Failed to get external files dir", t)
        null
    } ?: context.filesDir

    val probeRoot = File(base, "firered_asr")
    val preferredVariant = try {
        prefs.frModelVariant
    } catch (t: Throwable) {
        Log.w("FireRedAsrResolver", "Failed to get frModelVariant", t)
        "ctc-int8"
    }
    val cacheKey = probeRoot.absolutePath + "|" + preferredVariant
    val cached = FireRedAsrModelResolverCache.get(context, cacheKey)
    if (cached != null) return LocalModelCheck.Ready(cached)

    val variantDir = File(probeRoot, preferredVariant)
    val modelDir = findFireRedAsrModelDir(variantDir) ?: findFireRedAsrModelDir(probeRoot) ?: return LocalModelCheck.Missing

    val tokens = File(modelDir, "tokens.txt")
    if (!tokens.exists()) return LocalModelCheck.Missing

    val ctcModel = File(modelDir, "model.int8.onnx")
    if (!ctcModel.exists()) return LocalModelCheck.Missing
    when (
        val check = requireModelFilesCached(
            context,
            tokens to LocalModelSpecs.FireRedAsr.tokens,
            ctcModel to LocalModelSpecs.FireRedAsr.ctcModel
        )
    ) {
        is LocalModelCheck.IntegrityError -> return LocalModelCheck.IntegrityError(check.fileName)
        LocalModelCheck.Missing -> return LocalModelCheck.Missing
        is LocalModelCheck.Ready -> Unit
    }

    return LocalModelCheck.Ready(
        FireRedAsrResolvedModel(
            variant = "ctc-int8",
            modelDir = modelDir,
            tokensPath = tokens.absolutePath,
            ctcModelPath = ctcModel.absolutePath,
            encoderPath = null,
            decoderPath = null
        ).also {
            FireRedAsrModelResolverCache.put(cacheKey, it)
        }
    )
}

internal fun resolveFireRedAsrModelFiles(context: Context, prefs: Prefs): FireRedAsrResolvedModel? = (checkFireRedAsrModelFiles(context, prefs) as? LocalModelCheck.Ready)?.value

private object FireRedAsrModelResolverCache {
    @Volatile private var cachedKey: String? = null

    @Volatile private var cachedValue: FireRedAsrResolvedModel? = null

    fun get(context: Context, key: String): FireRedAsrResolvedModel? {
        val cached = if (cachedKey == key) cachedValue else null
        return cached?.takeIf { isUsable(context, it) }
    }

    fun put(key: String, model: FireRedAsrResolvedModel) {
        cachedKey = key
        cachedValue = model
    }

    private fun isUsable(context: Context, model: FireRedAsrResolvedModel): Boolean {
        val ctcModelPath = model.ctcModelPath
        if (ctcModelPath != null) {
            return requireModelFilesCached(
                context,
                File(model.tokensPath) to LocalModelSpecs.FireRedAsr.tokens,
                File(ctcModelPath) to LocalModelSpecs.FireRedAsr.ctcModel
            ) is LocalModelCheck.Ready
        }
        val encoderPath = model.encoderPath ?: return false
        val decoderPath = model.decoderPath ?: return false
        return File(encoderPath).exists() && File(decoderPath).exists()
    }
}

internal fun sanitizeFireRedAsrResult(text: String?): String {
    if (text.isNullOrBlank()) return ""
    return text
        .replace("<sil>", "")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
}

internal fun fireRedAsrPcmToFloatArray(pcm: ByteArray): FloatArray {
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

internal class FireRedAsrOnnxManager private constructor() : BaseSherpaOfflineRecognizerManager() {

    companion object {
        private const val TAG = "FireRedAsrOnnxManager"

        @Volatile
        private var instance: FireRedAsrOnnxManager? = null

        fun getInstance(): FireRedAsrOnnxManager = instance ?: synchronized(this) {
            instance ?: FireRedAsrOnnxManager().also { instance = it }
        }
    }

    protected override val tag: String = TAG

    @Volatile private var clsOfflineRecognizer: Class<*>? = null

    @Volatile private var clsOfflineRecognizerConfig: Class<*>? = null

    @Volatile private var clsOfflineModelConfig: Class<*>? = null

    @Volatile private var clsFeatureConfig: Class<*>? = null

    @Volatile private var clsOfflineFireRedAsrModelConfig: Class<*>? = null

    @Volatile private var clsOfflineFireRedAsrCtcModelConfig: Class<*>? = null

    fun isOnnxAvailable(): Boolean = sherpaIsClassAvailable(
        TAG,
        "com.k2fsa.sherpa.onnx.OfflineRecognizer"
    )

    private data class RecognizerConfig(
        val tokens: String,
        val ctcModel: String?,
        val encoder: String?,
        val decoder: String?,
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
            clsOfflineFireRedAsrModelConfig = runCatching {
                Class.forName("com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig")
            }.getOrNull()
            clsOfflineFireRedAsrCtcModelConfig = runCatching {
                Class.forName("com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig")
            }.getOrNull()
            Log.d(TAG, "Initialized sherpa-onnx reflection classes for FireRedASR")
        }
    }

    private fun trySetField(target: Any, name: String, value: Any?): Boolean = sherpaTrySetField(TAG, target, name, value)

    private fun ensureAedClass(): Class<*> {
        val cached = clsOfflineFireRedAsrModelConfig
        if (cached != null) return cached
        return Class.forName("com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig").also {
            clsOfflineFireRedAsrModelConfig = it
        }
    }

    private fun ensureCtcClass(): Class<*> {
        val cached = clsOfflineFireRedAsrCtcModelConfig
        if (cached != null) return cached
        return Class.forName("com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig").also {
            clsOfflineFireRedAsrCtcModelConfig = it
        }
    }

    private fun buildFeatureConfig(sampleRate: Int, featureDim: Int): Any {
        val feat = clsFeatureConfig!!.getDeclaredConstructor().newInstance()
        trySetField(feat, "sampleRate", sampleRate)
        trySetField(feat, "featureDim", featureDim)
        trySetField(feat, "dither", 0.0f)
        return feat
    }

    private fun buildFireRedAsrModelConfig(encoder: String, decoder: String): Any {
        val inst = ensureAedClass().getDeclaredConstructor().newInstance()
        trySetField(inst, "encoder", encoder)
        trySetField(inst, "decoder", decoder)
        return inst
    }

    private fun buildFireRedAsrCtcModelConfig(model: String): Any {
        val inst = ensureCtcClass().getDeclaredConstructor().newInstance()
        trySetField(inst, "model", model)
        return inst
    }

    private fun buildModelConfig(
        tokens: String,
        ctcModel: String?,
        encoder: String?,
        decoder: String?,
        numThreads: Int,
        provider: String
    ): Any {
        val modelConfig = clsOfflineModelConfig!!.getDeclaredConstructor().newInstance()
        trySetField(modelConfig, "tokens", tokens)
        trySetField(modelConfig, "numThreads", numThreads)
        trySetField(modelConfig, "provider", provider)
        trySetField(modelConfig, "debug", false)

        if (!ctcModel.isNullOrBlank()) {
            val fireRedAsrCtc = buildFireRedAsrCtcModelConfig(ctcModel)
            if (!trySetField(modelConfig, "fireRedAsrCtc", fireRedAsrCtc)) {
                trySetField(modelConfig, "fire_red_asr_ctc", fireRedAsrCtc)
            }
        } else {
            require(!encoder.isNullOrBlank() && !decoder.isNullOrBlank()) {
                "FireRedASR AED model files are missing"
            }
            val fireRedAsr = buildFireRedAsrModelConfig(encoder, decoder)
            if (!trySetField(modelConfig, "fireRedAsr", fireRedAsr)) {
                trySetField(modelConfig, "fire_red_asr", fireRedAsr)
            }
        }

        return modelConfig
    }

    protected override fun buildRecognizerConfig(config: Any): Any {
        config as RecognizerConfig
        val modelConfig = buildModelConfig(
            tokens = config.tokens,
            ctcModel = config.ctcModel,
            encoder = config.encoder,
            decoder = config.decoder,
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
        ctcModel: String?,
        encoder: String?,
        decoder: String?,
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
                ctcModel = ctcModel,
                encoder = encoder,
                decoder = decoder,
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
            Log.e(TAG, "Failed to decode offline FireRedASR: ${t.message}", t)
            return@withLock null
        }
    }

    suspend fun prepare(
        assetManager: android.content.res.AssetManager?,
        tokens: String,
        ctcModel: String?,
        encoder: String?,
        decoder: String?,
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
                ctcModel = ctcModel,
                encoder = encoder,
                decoder = decoder,
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
            Log.e(TAG, "Failed to prepare FireRedASR recognizer: ${t.message}", t)
            false
        }
    }
}
