// Builds Push PCM-compatible ASR engines from the shared mode resolver.
package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope

internal data class AsrPushPcmEngineRequest(
    val context: Context,
    val scope: CoroutineScope,
    val prefs: Prefs,
    val listener: StreamingAsrEngine.Listener,
    val vendor: AsrVendor,
    val invocationMode: AsrEngineInvocationMode,
    val preferences: AsrEngineModePreferences,
    val source: AsrEngineConstructionSource = AsrEngineConstructionSource.ExternalIntegration,
    val onRequestDuration: ((Long) -> Unit)? = null,
    val applyAudioPreprocess: Boolean = true,
    val modelOverride: AsrRequestModelOverride = AsrRequestModelOverride()
)

internal data class AsrPushPcmEnginePlan(
    val resolution: AsrEngineModeResolution,
    val constructorKey: AsrPushPcmEngineConstructorKey,
    val wrappedFileRecognizerKey: AsrFileRecognizerKey? = null
) {
    val vendor: AsrVendor
        get() = resolution.vendor

    val engineClassName: String
        get() = constructorKey.engineClassName

    val wrappedRecognizerClassName: String?
        get() = wrappedFileRecognizerKey?.engineClassName

    val family: AsrPushPcmEngineFamily
        get() = constructorKey.family

    val externalPcmMode: Boolean
        get() = family == AsrPushPcmEngineFamily.NativeStream ||
            family == AsrPushPcmEngineFamily.LocalStream

    val progressiveChunkingEnabled: Boolean
        get() = wrappedFileRecognizerKey?.progressiveChunkingEnabled == true
}

internal enum class AsrPushPcmEngineFamily {
    FileAdapter,
    NativeStream,
    LocalStream,
    PseudoStream
}

internal enum class AsrPushPcmEngineConstructorKey(
    val engineClassName: String,
    val family: AsrPushPcmEngineFamily
) {
    GenericFileAdapter("GenericPushFileAsrAdapter", AsrPushPcmEngineFamily.FileAdapter),
    VolcStream("VolcStreamAsrEngine", AsrPushPcmEngineFamily.NativeStream),
    ElevenLabsStream("ElevenLabsStreamAsrEngine", AsrPushPcmEngineFamily.NativeStream),
    OpenAiRealtime("OpenAiRealtimeAsrEngine", AsrPushPcmEngineFamily.NativeStream),
    DashscopeStream("DashscopeStreamAsrEngine", AsrPushPcmEngineFamily.NativeStream),
    SonioxStream("SonioxStreamAsrEngine", AsrPushPcmEngineFamily.NativeStream),
    SenseVoicePushPcmPseudoStream("SenseVoicePushPcmPseudoStreamAsrEngine", AsrPushPcmEngineFamily.PseudoStream),
    FireRedAsrPushPcmPseudoStream("FireRedAsrPushPcmPseudoStreamAsrEngine", AsrPushPcmEngineFamily.PseudoStream),
    XAsrStream("XAsrStreamAsrEngine", AsrPushPcmEngineFamily.LocalStream),
    TencentStream("TencentStreamAsrEngine", AsrPushPcmEngineFamily.NativeStream)
}

internal class AsrPushPcmEngineFactory(
    private val constructors: AsrPushPcmEngineConstructorTable =
        RealAsrPushPcmEngineConstructorTable
) {
    fun create(request: AsrPushPcmEngineRequest): StreamingAsrEngine = constructors.create(
        resolvePlan(
            vendor = request.vendor,
            invocationMode = request.invocationMode,
            preferences = request.preferences,
            source = request.source
        ),
        request
    )

    fun createOrNull(request: AsrPushPcmEngineRequest): StreamingAsrEngine? {
        if (!isRequestAvailable(request)) return null
        return create(request)
    }

    fun create(
        context: Context,
        scope: CoroutineScope,
        prefs: Prefs,
        listener: StreamingAsrEngine.Listener,
        vendor: AsrVendor = prefs.asrVendor,
        invocationMode: AsrEngineInvocationMode = AsrEngineInvocationMode.PushPcm,
        preferences: AsrEngineModePreferences = prefs.asrEngineModePreferencesSnapshot(),
        source: AsrEngineConstructionSource = AsrEngineConstructionSource.ExternalIntegration,
        onRequestDuration: ((Long) -> Unit)? = null,
        applyAudioPreprocess: Boolean = true,
        modelOverride: AsrRequestModelOverride = AsrRequestModelOverride()
    ): StreamingAsrEngine = create(
        AsrPushPcmEngineRequest(
            context = context,
            scope = scope,
            prefs = prefs,
            listener = listener,
            vendor = vendor,
            invocationMode = invocationMode,
            preferences = preferences,
            source = source,
            onRequestDuration = onRequestDuration,
            applyAudioPreprocess = applyAudioPreprocess,
            modelOverride = modelOverride
        )
    )

    fun createOrNull(
        context: Context,
        scope: CoroutineScope,
        prefs: Prefs,
        listener: StreamingAsrEngine.Listener,
        vendor: AsrVendor = prefs.asrVendor,
        invocationMode: AsrEngineInvocationMode = AsrEngineInvocationMode.PushPcm,
        preferences: AsrEngineModePreferences = prefs.asrEngineModePreferencesSnapshot(),
        source: AsrEngineConstructionSource = AsrEngineConstructionSource.ExternalIntegration,
        onRequestDuration: ((Long) -> Unit)? = null,
        applyAudioPreprocess: Boolean = true,
        modelOverride: AsrRequestModelOverride = AsrRequestModelOverride()
    ): StreamingAsrEngine? = createOrNull(
        AsrPushPcmEngineRequest(
            context = context,
            scope = scope,
            prefs = prefs,
            listener = listener,
            vendor = vendor,
            invocationMode = invocationMode,
            preferences = preferences,
            source = source,
            onRequestDuration = onRequestDuration,
            applyAudioPreprocess = applyAudioPreprocess,
            modelOverride = modelOverride
        )
    )

    fun resolvePlan(
        vendor: AsrVendor,
        invocationMode: AsrEngineInvocationMode,
        preferences: AsrEngineModePreferences,
        source: AsrEngineConstructionSource = AsrEngineConstructionSource.ExternalIntegration
    ): AsrPushPcmEnginePlan {
        require(invocationMode.consumesPushedPcm) {
            "$invocationMode is outside Push PCM factory scope"
        }

        val resolution = AsrEngineModeResolver.resolve(
            vendor = vendor,
            invocationMode = invocationMode,
            preferences = preferences,
            source = source
        )
        return AsrPushPcmEnginePlan(
            resolution = resolution,
            constructorKey = constructorKeyFor(resolution),
            wrappedFileRecognizerKey = wrappedFileRecognizerKeyFor(resolution)
        )
    }

    private fun constructorKeyFor(
        resolution: AsrEngineModeResolution
    ): AsrPushPcmEngineConstructorKey = when (resolution.mode) {
        AsrResolvedEngineMode.PushPcmFileAdapter ->
            AsrPushPcmEngineConstructorKey.GenericFileAdapter
        AsrResolvedEngineMode.PushPcmNativeStream,
        AsrResolvedEngineMode.PushPcmLocalStream ->
            streamConstructorKeyFor(resolution.vendor)
        AsrResolvedEngineMode.PushPcmPseudoStream ->
            pseudoStreamConstructorKeyFor(resolution.vendor)
        AsrResolvedEngineMode.DirectFile,
        AsrResolvedEngineMode.DirectLocalFile,
        AsrResolvedEngineMode.DirectStream,
        AsrResolvedEngineMode.DirectLocalStream,
        AsrResolvedEngineMode.DirectLocalPseudoStream ->
            error("Direct mode ${resolution.mode} is outside Push PCM factory scope")
    }

    private fun wrappedFileRecognizerKeyFor(
        resolution: AsrEngineModeResolution
    ): AsrFileRecognizerKey? = when (resolution.mode) {
        AsrResolvedEngineMode.PushPcmFileAdapter -> fileRecognizerKeyFor(resolution)
        AsrResolvedEngineMode.PushPcmNativeStream,
        AsrResolvedEngineMode.PushPcmLocalStream,
        AsrResolvedEngineMode.PushPcmPseudoStream -> null
        AsrResolvedEngineMode.DirectFile,
        AsrResolvedEngineMode.DirectLocalFile,
        AsrResolvedEngineMode.DirectStream,
        AsrResolvedEngineMode.DirectLocalStream,
        AsrResolvedEngineMode.DirectLocalPseudoStream ->
            error("Direct mode ${resolution.mode} is outside Push PCM factory scope")
    }

    private fun streamConstructorKeyFor(
        vendor: AsrVendor
    ): AsrPushPcmEngineConstructorKey = when (vendor) {
        AsrVendor.Volc -> AsrPushPcmEngineConstructorKey.VolcStream
        AsrVendor.ElevenLabs -> AsrPushPcmEngineConstructorKey.ElevenLabsStream
        AsrVendor.OpenAI -> AsrPushPcmEngineConstructorKey.OpenAiRealtime
        AsrVendor.DashScope -> AsrPushPcmEngineConstructorKey.DashscopeStream
        AsrVendor.Soniox -> AsrPushPcmEngineConstructorKey.SonioxStream
        AsrVendor.XAsr -> AsrPushPcmEngineConstructorKey.XAsrStream
        AsrVendor.Tencent -> AsrPushPcmEngineConstructorKey.TencentStream
        else -> error("$vendor has no Push PCM native stream engine")
    }

    private fun pseudoStreamConstructorKeyFor(
        vendor: AsrVendor
    ): AsrPushPcmEngineConstructorKey = when (vendor) {
        AsrVendor.SenseVoice -> AsrPushPcmEngineConstructorKey.SenseVoicePushPcmPseudoStream
        AsrVendor.FireRedAsr -> AsrPushPcmEngineConstructorKey.FireRedAsrPushPcmPseudoStream
        else -> error("$vendor has no Push PCM pseudo stream engine")
    }

    private fun isRequestAvailable(request: AsrPushPcmEngineRequest): Boolean = isPushPcmFactoryVendorAvailable(
        vendor = request.vendor,
        invocationMode = request.invocationMode,
        checkers = AsrVendorAvailabilityCheckers(
            onlineConfiguration = { checkedVendor ->
                isOnlineAsrVendorConfigured(
                    checkedVendor,
                    AsrOnlineConfigurationChecks(
                        hasSfKeys = { request.prefs.hasSfKeys() },
                        hasVendorKeys = { request.prefs.hasVendorKeys(it) }
                    )
                )
            },
            localModelReadiness = { checkedVendor ->
                AsrLocalVendorLifecycles.isModelReady(
                    request.context,
                    request.prefs,
                    checkedVendor
                )
            }
        )
    )
}

internal fun isPushPcmFactoryVendorAvailable(
    vendor: AsrVendor,
    invocationMode: AsrEngineInvocationMode = AsrEngineInvocationMode.PushPcm,
    checkers: AsrVendorAvailabilityCheckers
): Boolean {
    require(invocationMode.consumesPushedPcm) {
        "$invocationMode is outside Push PCM factory scope"
    }
    if (!invocationMode.requiresConfigurationValidation) return true
    return try {
        checkAsrVendorAvailability(vendor, checkers).isUsable
    } catch (_: Throwable) {
        false
    }
}

internal fun interface AsrPushPcmEngineConstructorTable {
    fun create(
        plan: AsrPushPcmEnginePlan,
        request: AsrPushPcmEngineRequest
    ): StreamingAsrEngine
}

internal object RealAsrPushPcmEngineConstructorTable : AsrPushPcmEngineConstructorTable {
    override fun create(
        plan: AsrPushPcmEnginePlan,
        request: AsrPushPcmEngineRequest
    ): StreamingAsrEngine = when (plan.constructorKey) {
        AsrPushPcmEngineConstructorKey.GenericFileAdapter ->
            createFileAdapter(plan, request)
        AsrPushPcmEngineConstructorKey.VolcStream ->
            VolcStreamAsrEngine(request.context, request.scope, request.prefs, request.listener, externalPcmMode = true)
        AsrPushPcmEngineConstructorKey.ElevenLabsStream ->
            ElevenLabsStreamAsrEngine(request.context, request.scope, request.prefs, request.listener, externalPcmMode = true)
        AsrPushPcmEngineConstructorKey.OpenAiRealtime ->
            OpenAiRealtimeAsrEngine(request.context, request.scope, request.prefs, request.listener, externalPcmMode = true)
        AsrPushPcmEngineConstructorKey.DashscopeStream ->
            DashscopeStreamAsrEngine(request.context, request.scope, request.prefs, request.listener, externalPcmMode = true)
        AsrPushPcmEngineConstructorKey.SonioxStream ->
            SonioxStreamAsrEngine(request.context, request.scope, request.prefs, request.listener, externalPcmMode = true)
        AsrPushPcmEngineConstructorKey.SenseVoicePushPcmPseudoStream ->
            SenseVoicePushPcmPseudoStreamAsrEngine(
                request.context,
                request.scope,
                request.prefs,
                request.listener,
                request.onRequestDuration
            )
        AsrPushPcmEngineConstructorKey.FireRedAsrPushPcmPseudoStream ->
            FireRedAsrPushPcmPseudoStreamAsrEngine(
                request.context,
                request.scope,
                request.prefs,
                request.listener,
                request.onRequestDuration
            )
        AsrPushPcmEngineConstructorKey.XAsrStream ->
            XAsrStreamAsrEngine(request.context, request.scope, request.prefs, request.listener, externalPcmMode = true)
        AsrPushPcmEngineConstructorKey.TencentStream ->
            TencentStreamAsrEngine(request.context, request.scope, request.prefs, request.listener, externalPcmMode = true)
    }

    private fun createFileRecognizer(
        key: AsrFileRecognizerKey?,
        request: AsrPushPcmEngineRequest,
        listener: StreamingAsrEngine.Listener,
        onRequestDuration: ((Long) -> Unit)?
    ): PcmBatchRecognizer = RealAsrFileRecognizerConstructorTable.create(
        key ?: error("Push PCM file adapter requires a wrapped file recognizer"),
        request.toFileRecognizerRequest().copy(
            listener = listener,
            onRequestDuration = onRequestDuration
        )
    )

    private fun createFileAdapter(
        plan: AsrPushPcmEnginePlan,
        request: AsrPushPcmEngineRequest
    ): GenericPushFileAsrAdapter {
        val progressiveResults = plan.wrappedFileRecognizerKey
            ?.takeIf(AsrFileRecognizerKey::progressiveChunkingEnabled)
            ?.let {
                createNonStreamingChunkResultCollector(
                    context = request.context,
                    listener = request.listener,
                    onRequestDuration = request.onRequestDuration
                )
            }
        return GenericPushFileAsrAdapter(
            context = request.context,
            scope = request.scope,
            prefs = request.prefs,
            listener = request.listener,
            recognizer = createFileRecognizer(
                plan.wrappedFileRecognizerKey,
                request,
                progressiveResults ?: request.listener,
                if (progressiveResults == null) request.onRequestDuration else null
            ),
            applyAudioPreprocess = request.applyAudioPreprocess,
            progressiveResults = progressiveResults
        )
    }
}
