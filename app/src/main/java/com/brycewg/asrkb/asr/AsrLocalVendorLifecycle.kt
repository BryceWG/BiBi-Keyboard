// Local ASR supplier lifecycle seam.
package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.store.Prefs
import java.io.File

internal class AsrLocalVendorPreloadRequest private constructor(
    private val contextOrNull: Context?,
    private val prefsOrNull: Prefs?,
    val onLoadStart: (() -> Unit)?,
    val onLoadDone: (() -> Unit)?,
    val suppressToastOnStart: Boolean,
    val forImmediateUse: Boolean
) {
    val context: Context
        get() = requireNotNull(contextOrNull) { "Context is required for real local ASR preload" }

    val prefs: Prefs
        get() = requireNotNull(prefsOrNull) { "Prefs is required for real local ASR preload" }

    companion object {
        fun create(
            context: Context,
            prefs: Prefs,
            onLoadStart: (() -> Unit)? = null,
            onLoadDone: (() -> Unit)? = null,
            suppressToastOnStart: Boolean = false,
            forImmediateUse: Boolean = false
        ): AsrLocalVendorPreloadRequest = AsrLocalVendorPreloadRequest(
            contextOrNull = context,
            prefsOrNull = prefs,
            onLoadStart = onLoadStart,
            onLoadDone = onLoadDone,
            suppressToastOnStart = suppressToastOnStart,
            forImmediateUse = forImmediateUse
        )

        internal fun forTest(
            onLoadStart: (() -> Unit)? = null,
            onLoadDone: (() -> Unit)? = null,
            suppressToastOnStart: Boolean = false,
            forImmediateUse: Boolean = false
        ): AsrLocalVendorPreloadRequest = AsrLocalVendorPreloadRequest(
            contextOrNull = null,
            prefsOrNull = null,
            onLoadStart = onLoadStart,
            onLoadDone = onLoadDone,
            suppressToastOnStart = suppressToastOnStart,
            forImmediateUse = forImmediateUse
        )
    }
}

internal interface AsrLocalVendorLifecycle {
    val vendor: AsrVendor

    fun preload(request: AsrLocalVendorPreloadRequest)

    fun unload()

    fun isPrepared(): Boolean

    fun isReady(): Boolean

    fun modelStatus(context: Context, prefs: Prefs): LocalModelCheck<*>

    fun isModelReady(context: Context, prefs: Prefs): Boolean = modelStatus(context, prefs) is LocalModelCheck.Ready<*>
}

internal class AsrLocalVendorLifecycleRegistry(
    lifecycles: List<AsrLocalVendorLifecycle>
) {
    private val lifecycleByVendor: Map<AsrVendor, AsrLocalVendorLifecycle> =
        lifecycles.associateBy { it.vendor }

    fun all(): List<AsrLocalVendorLifecycle> = lifecycleByVendor.values.toList()

    fun lifecycleFor(vendor: AsrVendor): AsrLocalVendorLifecycle? = lifecycleByVendor[vendor]

    fun isLocalVendor(vendor: AsrVendor): Boolean = lifecycleFor(vendor) != null

    fun preload(vendor: AsrVendor, request: AsrLocalVendorPreloadRequest): Boolean {
        val lifecycle = lifecycleFor(vendor) ?: return false
        lifecycle.preload(request)
        return true
    }

    fun unload(vendor: AsrVendor): Boolean {
        val lifecycle = lifecycleFor(vendor) ?: return false
        lifecycle.unload()
        return true
    }

    fun isPrepared(vendor: AsrVendor): Boolean = lifecycleFor(vendor)?.isPrepared() == true

    fun isReady(vendor: AsrVendor): Boolean = lifecycleFor(vendor)?.isReady() == true

    fun modelStatus(context: Context, prefs: Prefs, vendor: AsrVendor): LocalModelCheck<*>? = lifecycleFor(vendor)?.modelStatus(context, prefs)

    fun isModelReady(context: Context, prefs: Prefs, vendor: AsrVendor): Boolean = lifecycleFor(vendor)?.isModelReady(context, prefs) == true
}

internal object AsrLocalVendorLifecycles {
    private val registry = AsrLocalVendorLifecycleRegistry(
        listOf(
            senseVoiceLifecycle(),
            funAsrNanoLifecycle(),
            qwen3AsrLifecycle(),
            parakeetLifecycle(),
            gigaAmLifecycle(),
            fireRedAsrLifecycle(),
            xAsrLifecycle()
        )
    )

    fun all(): List<AsrLocalVendorLifecycle> = registry.all()

    fun lifecycleFor(vendor: AsrVendor): AsrLocalVendorLifecycle? = registry.lifecycleFor(vendor)

    fun isLocalVendor(vendor: AsrVendor): Boolean = registry.isLocalVendor(vendor)

    fun preload(vendor: AsrVendor, request: AsrLocalVendorPreloadRequest): Boolean = registry.preload(vendor, request)

    fun unload(vendor: AsrVendor): Boolean = registry.unload(vendor)

    fun isPrepared(vendor: AsrVendor): Boolean = registry.isPrepared(vendor)

    fun isReady(vendor: AsrVendor): Boolean = registry.isReady(vendor)

    fun modelStatus(context: Context, prefs: Prefs, vendor: AsrVendor): LocalModelCheck<*>? = registry.modelStatus(context, prefs, vendor)

    fun isModelReady(context: Context, prefs: Prefs, vendor: AsrVendor): Boolean = registry.isModelReady(context, prefs, vendor)
}

private class DefaultAsrLocalVendorLifecycle(
    override val vendor: AsrVendor,
    private val preloadHook: (AsrLocalVendorPreloadRequest) -> Unit,
    private val unloadHook: () -> Unit,
    private val preparedHook: () -> Boolean,
    private val readyHook: () -> Boolean,
    private val modelStatusHook: (Context, Prefs) -> LocalModelCheck<*>
) : AsrLocalVendorLifecycle {
    override fun preload(request: AsrLocalVendorPreloadRequest) = preloadHook(request)

    override fun unload() = unloadHook()

    override fun isPrepared(): Boolean = preparedHook()

    override fun isReady(): Boolean = readyHook()

    override fun modelStatus(context: Context, prefs: Prefs): LocalModelCheck<*> = modelStatusHook(context, prefs)
}

private fun senseVoiceLifecycle(): AsrLocalVendorLifecycle = DefaultAsrLocalVendorLifecycle(
    vendor = AsrVendor.SenseVoice,
    preloadHook = { request ->
        preloadSenseVoiceIfConfigured(
            request.context,
            request.prefs,
            request.onLoadStart,
            request.onLoadDone,
            request.suppressToastOnStart,
            request.forImmediateUse
        )
    },
    unloadHook = ::unloadSenseVoiceRecognizer,
    preparedHook = ::isSenseVoicePrepared,
    readyHook = {
        val manager = SenseVoiceOnnxManager.getInstance()
        manager.isPrepared() && !manager.isPreparing()
    },
    modelStatusHook = ::checkSenseVoiceModel
)

private fun funAsrNanoLifecycle(): AsrLocalVendorLifecycle = DefaultAsrLocalVendorLifecycle(
    vendor = AsrVendor.FunAsrNano,
    preloadHook = { request ->
        preloadFunAsrNanoIfConfigured(
            request.context,
            request.prefs,
            request.onLoadStart,
            request.onLoadDone,
            request.suppressToastOnStart,
            request.forImmediateUse
        )
    },
    unloadHook = ::unloadFunAsrNanoRecognizer,
    preparedHook = ::isFunAsrNanoPrepared,
    readyHook = {
        val manager = FunAsrNanoOnnxManager.getInstance()
        manager.isPrepared() && !manager.isPreparing()
    },
    modelStatusHook = ::checkFunAsrNanoModel
)

private fun qwen3AsrLifecycle(): AsrLocalVendorLifecycle = DefaultAsrLocalVendorLifecycle(
    vendor = AsrVendor.Qwen3Asr,
    preloadHook = { request ->
        preloadQwen3AsrIfConfigured(
            request.context,
            request.prefs,
            request.onLoadStart,
            request.onLoadDone,
            request.suppressToastOnStart,
            request.forImmediateUse
        )
    },
    unloadHook = ::unloadQwen3AsrRecognizer,
    preparedHook = ::isQwen3AsrPrepared,
    readyHook = {
        val manager = Qwen3AsrOnnxManager.getInstance()
        manager.isPrepared() && !manager.isPreparing()
    },
    modelStatusHook = ::checkQwen3AsrModel
)

private fun parakeetLifecycle(): AsrLocalVendorLifecycle = DefaultAsrLocalVendorLifecycle(
    vendor = AsrVendor.Parakeet,
    preloadHook = { request ->
        preloadParakeetIfConfigured(
            request.context,
            request.prefs,
            request.onLoadStart,
            request.onLoadDone,
            request.suppressToastOnStart,
            request.forImmediateUse
        )
    },
    unloadHook = ::unloadParakeetRecognizer,
    preparedHook = ::isParakeetPrepared,
    readyHook = {
        isParakeetPrepared()
    },
    modelStatusHook = ::checkParakeetModel
)

private fun gigaAmLifecycle(): AsrLocalVendorLifecycle = DefaultAsrLocalVendorLifecycle(
    vendor = AsrVendor.GigaAm,
    preloadHook = { request ->
        preloadGigaAmIfConfigured(
            request.context,
            request.prefs,
            request.onLoadStart,
            request.onLoadDone,
            request.suppressToastOnStart,
            request.forImmediateUse
        )
    },
    unloadHook = ::unloadGigaAmRecognizer,
    preparedHook = ::isGigaAmPrepared,
    readyHook = {
        val manager = GigaAmOnnxManager.getInstance()
        manager.isPrepared() && !manager.isPreparing()
    },
    modelStatusHook = ::checkGigaAmModel
)

private fun fireRedAsrLifecycle(): AsrLocalVendorLifecycle = DefaultAsrLocalVendorLifecycle(
    vendor = AsrVendor.FireRedAsr,
    preloadHook = { request ->
        preloadFireRedAsrIfConfigured(
            request.context,
            request.prefs,
            request.onLoadStart,
            request.onLoadDone,
            request.suppressToastOnStart,
            request.forImmediateUse
        )
    },
    unloadHook = ::unloadFireRedAsrRecognizer,
    preparedHook = ::isFireRedAsrPrepared,
    readyHook = {
        val manager = FireRedAsrOnnxManager.getInstance()
        manager.isPrepared() && !manager.isPreparing()
    },
    modelStatusHook = ::checkFireRedAsrModelFiles
)

private fun xAsrLifecycle(): AsrLocalVendorLifecycle = DefaultAsrLocalVendorLifecycle(
    vendor = AsrVendor.XAsr,
    preloadHook = { request ->
        preloadXAsrIfConfigured(
            request.context,
            request.prefs,
            request.onLoadStart,
            request.onLoadDone,
            request.suppressToastOnStart,
            request.forImmediateUse
        )
    },
    unloadHook = ::unloadXAsrRecognizer,
    preparedHook = ::isXAsrPrepared,
    readyHook = {
        val manager = XAsrOnnxManager.getInstance()
        manager.isPrepared() && !manager.isPreparing()
    },
    modelStatusHook = { context, _ ->
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        checkXAsrModelFiles(context, File(base, "x_asr"))
    }
)
