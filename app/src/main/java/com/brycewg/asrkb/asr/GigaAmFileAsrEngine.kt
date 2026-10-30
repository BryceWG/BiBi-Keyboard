/** GigaAM v3 Russian offline recognizer backed by sherpa-onnx Nemo transducer. */
package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

internal class GigaAmFileAsrEngine(
    context: Context,
    scope: CoroutineScope,
    prefs: Prefs,
    listener: StreamingAsrEngine.Listener,
    onRequestDuration: ((Long) -> Unit)? = null
) : BaseFileAsrEngine(context, scope, prefs, listener, onRequestDuration, progressiveChunkingEnabled = false),
    PcmBatchRecognizer {

    override val progressiveVendor: AsrVendor = AsrVendor.GigaAm

    override fun ensureReady(): Boolean {
        if (!super.ensureReady()) return false
        if (!GigaAmOnnxManager.getInstance().isOnnxAvailable()) {
            listener.onError(context.getString(R.string.error_local_asr_not_ready))
            return false
        }
        return true
    }

    override suspend fun recognize(pcm: ByteArray) {
        val startedAt = System.currentTimeMillis()
        val callLog = LocalAsrCallLogger.startInference(
            prefs = prefs,
            vendor = AsrVendor.GigaAm,
            source = "file",
            audioBytes = pcm.size,
            sampleRate = sampleRate
        )
        try {
            val manager = GigaAmOnnxManager.getInstance()
            if (!manager.isOnnxAvailable()) {
                val message = context.getString(R.string.error_local_asr_not_ready)
                callLog.failure(message)
                listener.onError(message)
                return
            }
            val modelCheck = checkGigaAmModel(context, prefs)
            val model = (modelCheck as? LocalModelCheck.Ready)?.value
            if (model == null) {
                val message = localModelErrorMessage(
                    context,
                    modelCheck,
                    R.string.error_giga_am_model_missing
                )
                callLog.failure(message)
                listener.onError(message)
                return
            }
            val samples = parakeetPcmToFloatArray(pcm)
            if (samples.isEmpty()) {
                val message = context.getString(R.string.error_audio_empty)
                callLog.failure(message)
                listener.onError(message)
                return
            }
            val keepMinutes = prefs.gigaAmKeepAliveMinutes
            val text = manager.decodeOffline(
                assetManager = null,
                tokens = model.tokensPath,
                encoder = model.encoderPath,
                decoder = model.decoderPath,
                joiner = model.joinerPath,
                provider = "cpu",
                numThreads = prefs.gigaAmNumThreads,
                samples = samples,
                sampleRate = sampleRate,
                keepAliveMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L,
                alwaysKeep = keepMinutes < 0
            )
            if (text.isNullOrBlank()) {
                val message = context.getString(R.string.error_asr_empty_result)
                callLog.failure(message)
                listener.onError(message)
            } else {
                val finalText = text.trim()
                callLog.successWithText(finalText)
                listener.onFinal(finalText)
            }
        } catch (t: CancellationException) {
            callLog.failure("cancelled")
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "GigaAM recognition failed", t)
            val message = context.getString(R.string.error_recognize_failed_with_reason, t.message ?: "")
            callLog.failure(message)
            listener.onError(message)
        } finally {
            onRequestDuration?.invoke(System.currentTimeMillis() - startedAt)
        }
    }

    override suspend fun recognizeFromPcm(pcm: ByteArray) = recognize(pcm)

    private companion object {
        const val TAG = "GigaAmFileAsrEngine"
    }
}

internal data class GigaAmResolvedModel(
    val variant: String,
    val modelDir: File,
    val tokensPath: String,
    val encoderPath: String,
    val decoderPath: String,
    val joinerPath: String
)

internal fun normalizeGigaAmVariant(@Suppress("UNUSED_PARAMETER") variant: String?): String = "giga-v3"

internal fun findGigaAmModelDir(root: File?): File? {
    if (root == null || !root.isDirectory) return null
    val required = listOf("tokens.txt", "encoder.int8.onnx", "decoder.onnx", "joiner.onnx")
    if (required.all { File(root, it).isFile }) return root
    return root.listFiles()?.firstNotNullOfOrNull { child ->
        if (child.isDirectory && !child.name.startsWith(".tmp_")) findGigaAmModelDir(child) else null
    }
}

internal fun checkGigaAmModel(context: Context, prefs: Prefs): LocalModelCheck<GigaAmResolvedModel> {
    val base = context.getExternalFilesDir(null) ?: context.filesDir
    val root = File(base, "giga_am")
    val modelDir = findGigaAmModelDir(File(root, normalizeGigaAmVariant(prefs.gigaAmModelVariant)))
        ?: findGigaAmModelDir(root)
        ?: return LocalModelCheck.Missing
    val tokens = File(modelDir, "tokens.txt")
    val encoder = File(modelDir, "encoder.int8.onnx")
    val decoder = File(modelDir, "decoder.onnx")
    val joiner = File(modelDir, "joiner.onnx")
    return when (
        val check = requireModelFilesCached(
            context,
            tokens to LocalModelSpecs.GigaAm.tokens,
            encoder to LocalModelSpecs.GigaAm.encoder,
            decoder to LocalModelSpecs.GigaAm.decoder,
            joiner to LocalModelSpecs.GigaAm.joiner
        )
    ) {
        is LocalModelCheck.IntegrityError -> LocalModelCheck.IntegrityError(check.fileName)
        LocalModelCheck.Missing -> LocalModelCheck.Missing
        is LocalModelCheck.Ready -> LocalModelCheck.Ready(
            GigaAmResolvedModel(
                variant = "giga-v3",
                modelDir = modelDir,
                tokensPath = tokens.absolutePath,
                encoderPath = encoder.absolutePath,
                decoderPath = decoder.absolutePath,
                joinerPath = joiner.absolutePath
            )
        )
    }
}

internal fun unloadGigaAmRecognizer() = GigaAmOnnxManager.getInstance().unload()

internal fun isGigaAmPrepared(): Boolean {
    val manager = GigaAmOnnxManager.getInstance()
    return manager.isPrepared() || manager.isPreparing()
}

internal fun preloadGigaAmIfConfigured(
    context: Context,
    prefs: Prefs,
    onLoadStart: (() -> Unit)? = null,
    onLoadDone: (() -> Unit)? = null,
    @Suppress("UNUSED_PARAMETER") suppressToastOnStart: Boolean = false,
    @Suppress("UNUSED_PARAMETER") forImmediateUse: Boolean = false
) {
    val model = (checkGigaAmModel(context, prefs) as? LocalModelCheck.Ready)?.value ?: return
    val keepMinutes = prefs.gigaAmKeepAliveMinutes
    val manager = GigaAmOnnxManager.getInstance()
    val key = listOf(
        "giga_am",
        model.tokensPath,
        model.encoderPath,
        model.decoderPath,
        model.joinerPath,
        prefs.gigaAmNumThreads
    ).joinToString("|")
    LocalModelLoadCoordinator.request(key) {
        manager.prepare(
            assetManager = null,
            tokens = model.tokensPath,
            encoder = model.encoderPath,
            decoder = model.decoderPath,
            joiner = model.joinerPath,
            provider = "cpu",
            numThreads = prefs.gigaAmNumThreads,
            keepAliveMs = if (keepMinutes <= 0) 0L else keepMinutes.toLong() * 60_000L,
            alwaysKeep = keepMinutes < 0,
            onLoadStart = onLoadStart,
            onLoadDone = onLoadDone
        )
    }
}

internal class GigaAmOnnxManager private constructor() : ParakeetOnnxManager("GigaAmOnnxManager") {
    companion object {
        @Volatile
        private var instance: GigaAmOnnxManager? = null

        fun getInstance(): GigaAmOnnxManager = instance ?: synchronized(this) {
            instance ?: GigaAmOnnxManager().also { instance = it }
        }
    }
}
