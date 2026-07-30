// Builds direct-microphone ASR engines from the shared mode resolver.
package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope

internal data class AsrDirectMicrophoneEngineRequest(
    val context: Context,
    val scope: CoroutineScope,
    val prefs: Prefs,
    val listener: StreamingAsrEngine.Listener,
    val vendor: AsrVendor,
    val preferences: AsrEngineModePreferences,
    val source: AsrEngineConstructionSource = AsrEngineConstructionSource.App,
    val onRequestDuration: ((Long) -> Unit)? = null,
    val modelOverride: AsrRequestModelOverride = AsrRequestModelOverride()
)

internal data class AsrDirectMicrophoneEnginePlan(
    val resolution: AsrEngineModeResolution,
    val constructorKey: AsrDirectMicrophoneEngineConstructorKey,
    val fileRecognizerKey: AsrFileRecognizerKey? = null
) {
    val vendor: AsrVendor
        get() = resolution.vendor

    val engineClassName: String
        get() = fileRecognizerKey?.engineClassName ?: constructorKey.engineClassName

    val identity: AsrDirectMicrophoneEngineIdentity
        get() = AsrDirectMicrophoneEngineIdentity(
            vendor = resolution.vendor,
            source = resolution.source,
            mode = resolution.mode,
            fileEngineVariant = resolution.fileEngineVariant,
            constructorKey = constructorKey,
            fileRecognizerKey = fileRecognizerKey
        )

    val family: AsrDirectMicrophoneEngineFamily
        get() = fileRecognizerKey?.family?.toDirectMicrophoneFamily() ?: constructorKey.family

    val progressiveChunkingEnabled: Boolean
        get() = fileRecognizerKey?.progressiveChunkingEnabled == true
}

internal data class AsrDirectMicrophoneEngineIdentity(
    val vendor: AsrVendor,
    val source: AsrEngineConstructionSource,
    val mode: AsrResolvedEngineMode,
    val fileEngineVariant: AsrFileEngineVariant,
    val constructorKey: AsrDirectMicrophoneEngineConstructorKey,
    val fileRecognizerKey: AsrFileRecognizerKey?
)

internal enum class AsrDirectMicrophoneEngineFamily {
    File,
    LocalFile,
    LocalPseudoStream,
    Stream
}

internal enum class AsrDirectMicrophoneEngineConstructorKey(
    val engineClassName: String,
    val family: AsrDirectMicrophoneEngineFamily
) {
    FileRecognizer("SharedFileRecognizer", AsrDirectMicrophoneEngineFamily.File),
    VolcStream("VolcStreamAsrEngine", AsrDirectMicrophoneEngineFamily.Stream),
    ElevenLabsStream("ElevenLabsStreamAsrEngine", AsrDirectMicrophoneEngineFamily.Stream),
    OpenAiRealtime("OpenAiRealtimeAsrEngine", AsrDirectMicrophoneEngineFamily.Stream),
    DashscopeStream("DashscopeStreamAsrEngine", AsrDirectMicrophoneEngineFamily.Stream),
    SonioxStream("SonioxStreamAsrEngine", AsrDirectMicrophoneEngineFamily.Stream),
    TencentStream("TencentStreamAsrEngine", AsrDirectMicrophoneEngineFamily.Stream),
    SenseVoicePseudoStream("SenseVoicePseudoStreamAsrEngine", AsrDirectMicrophoneEngineFamily.LocalPseudoStream),
    FireRedAsrPseudoStream("FireRedAsrPseudoStreamAsrEngine", AsrDirectMicrophoneEngineFamily.LocalPseudoStream),
    XAsrStream("XAsrStreamAsrEngine", AsrDirectMicrophoneEngineFamily.Stream)
}

internal class AsrDirectMicrophoneEngineFactory(
    private val constructors: AsrDirectMicrophoneEngineConstructorTable =
        RealAsrDirectMicrophoneEngineConstructorTable
) {
    fun create(request: AsrDirectMicrophoneEngineRequest): StreamingAsrEngine = constructors.create(resolvePlan(request.vendor, request.preferences, request.source), request)

    fun createOrNull(request: AsrDirectMicrophoneEngineRequest): StreamingAsrEngine? {
        if (!isRequestAvailable(request)) return null
        return create(request)
    }

    fun create(
        context: Context,
        scope: CoroutineScope,
        prefs: Prefs,
        listener: StreamingAsrEngine.Listener,
        vendor: AsrVendor = prefs.asrVendor,
        preferences: AsrEngineModePreferences = prefs.asrEngineModePreferencesSnapshot(),
        source: AsrEngineConstructionSource = AsrEngineConstructionSource.App,
        onRequestDuration: ((Long) -> Unit)? = null,
        modelOverride: AsrRequestModelOverride = AsrRequestModelOverride()
    ): StreamingAsrEngine = create(
        AsrDirectMicrophoneEngineRequest(
            context = context,
            scope = scope,
            prefs = prefs,
            listener = listener,
            vendor = vendor,
            preferences = preferences,
            source = source,
            onRequestDuration = onRequestDuration,
            modelOverride = modelOverride
        )
    )

    fun createOrNull(
        context: Context,
        scope: CoroutineScope,
        prefs: Prefs,
        listener: StreamingAsrEngine.Listener,
        vendor: AsrVendor = prefs.asrVendor,
        preferences: AsrEngineModePreferences = prefs.asrEngineModePreferencesSnapshot(),
        source: AsrEngineConstructionSource = AsrEngineConstructionSource.App,
        onRequestDuration: ((Long) -> Unit)? = null,
        modelOverride: AsrRequestModelOverride = AsrRequestModelOverride()
    ): StreamingAsrEngine? = createOrNull(
        AsrDirectMicrophoneEngineRequest(
            context = context,
            scope = scope,
            prefs = prefs,
            listener = listener,
            vendor = vendor,
            preferences = preferences,
            source = source,
            onRequestDuration = onRequestDuration,
            modelOverride = modelOverride
        )
    )

    fun resolvePlan(
        vendor: AsrVendor,
        preferences: AsrEngineModePreferences,
        source: AsrEngineConstructionSource = AsrEngineConstructionSource.App
    ): AsrDirectMicrophoneEnginePlan {
        val resolution = AsrEngineModeResolver.resolve(
            vendor = vendor,
            invocationMode = AsrEngineInvocationMode.DirectMicrophoneCapture,
            preferences = preferences,
            source = source
        )
        return AsrDirectMicrophoneEnginePlan(
            resolution = resolution,
            constructorKey = constructorKeyFor(resolution),
            fileRecognizerKey = fileRecognizerKeyOrNull(resolution)
        )
    }

    private fun constructorKeyFor(
        resolution: AsrEngineModeResolution
    ): AsrDirectMicrophoneEngineConstructorKey = when (resolution.mode) {
        AsrResolvedEngineMode.DirectFile,
        AsrResolvedEngineMode.DirectLocalFile -> fileConstructorKeyFor(resolution)
        AsrResolvedEngineMode.DirectStream,
        AsrResolvedEngineMode.DirectLocalStream -> streamConstructorKeyFor(resolution.vendor)
        AsrResolvedEngineMode.DirectLocalPseudoStream -> pseudoStreamConstructorKeyFor(resolution.vendor)
        AsrResolvedEngineMode.PushPcmFileAdapter,
        AsrResolvedEngineMode.PushPcmNativeStream,
        AsrResolvedEngineMode.PushPcmLocalStream,
        AsrResolvedEngineMode.PushPcmPseudoStream ->
            error("Push PCM mode ${resolution.mode} is outside direct microphone factory scope")
    }

    private fun fileConstructorKeyFor(
        resolution: AsrEngineModeResolution
    ): AsrDirectMicrophoneEngineConstructorKey = when (resolution.vendor) {
        AsrVendor.XAsr -> error("X-ASR has no direct file engine")
        else -> AsrDirectMicrophoneEngineConstructorKey.FileRecognizer
    }

    private fun fileRecognizerKeyOrNull(
        resolution: AsrEngineModeResolution
    ): AsrFileRecognizerKey? = when (resolution.mode) {
        AsrResolvedEngineMode.DirectFile,
        AsrResolvedEngineMode.DirectLocalFile -> fileRecognizerKeyFor(resolution)
        AsrResolvedEngineMode.DirectStream,
        AsrResolvedEngineMode.DirectLocalStream,
        AsrResolvedEngineMode.DirectLocalPseudoStream -> null
        AsrResolvedEngineMode.PushPcmFileAdapter,
        AsrResolvedEngineMode.PushPcmNativeStream,
        AsrResolvedEngineMode.PushPcmLocalStream,
        AsrResolvedEngineMode.PushPcmPseudoStream ->
            error("Push PCM mode ${resolution.mode} is outside direct microphone factory scope")
    }

    private fun streamConstructorKeyFor(
        vendor: AsrVendor
    ): AsrDirectMicrophoneEngineConstructorKey = when (vendor) {
        AsrVendor.Volc -> AsrDirectMicrophoneEngineConstructorKey.VolcStream
        AsrVendor.ElevenLabs -> AsrDirectMicrophoneEngineConstructorKey.ElevenLabsStream
        AsrVendor.OpenAI -> AsrDirectMicrophoneEngineConstructorKey.OpenAiRealtime
        AsrVendor.DashScope -> AsrDirectMicrophoneEngineConstructorKey.DashscopeStream
        AsrVendor.Soniox -> AsrDirectMicrophoneEngineConstructorKey.SonioxStream
        AsrVendor.Tencent -> AsrDirectMicrophoneEngineConstructorKey.TencentStream
        AsrVendor.XAsr -> AsrDirectMicrophoneEngineConstructorKey.XAsrStream
        else -> error("$vendor has no direct stream engine")
    }

    private fun pseudoStreamConstructorKeyFor(
        vendor: AsrVendor
    ): AsrDirectMicrophoneEngineConstructorKey = when (vendor) {
        AsrVendor.SenseVoice -> AsrDirectMicrophoneEngineConstructorKey.SenseVoicePseudoStream
        AsrVendor.FireRedAsr -> AsrDirectMicrophoneEngineConstructorKey.FireRedAsrPseudoStream
        else -> error("$vendor has no direct pseudo stream engine")
    }

    private fun isRequestAvailable(request: AsrDirectMicrophoneEngineRequest): Boolean = isDirectMicrophoneFactoryVendorAvailable(
        vendor = request.vendor,
        invocationMode = AsrEngineInvocationMode.DirectMicrophoneCapture,
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

internal fun isDirectMicrophoneFactoryVendorAvailable(
    vendor: AsrVendor,
    invocationMode: AsrEngineInvocationMode = AsrEngineInvocationMode.DirectMicrophoneCapture,
    checkers: AsrVendorAvailabilityCheckers
): Boolean {
    require(invocationMode.ownsMicrophoneCapture) {
        "$invocationMode is outside direct microphone factory scope"
    }
    if (!invocationMode.requiresConfigurationValidation) return true
    return try {
        checkAsrVendorAvailability(vendor, checkers).isUsable
    } catch (_: Throwable) {
        false
    }
}

internal fun interface AsrDirectMicrophoneEngineConstructorTable {
    fun create(
        plan: AsrDirectMicrophoneEnginePlan,
        request: AsrDirectMicrophoneEngineRequest
    ): StreamingAsrEngine
}

internal object RealAsrDirectMicrophoneEngineConstructorTable : AsrDirectMicrophoneEngineConstructorTable {
    override fun create(
        plan: AsrDirectMicrophoneEnginePlan,
        request: AsrDirectMicrophoneEngineRequest
    ): StreamingAsrEngine = when (plan.constructorKey) {
        AsrDirectMicrophoneEngineConstructorKey.FileRecognizer ->
            RealAsrFileRecognizerConstructorTable.createStreamingEngine(
                plan.fileRecognizerKey ?: error("Direct file construction requires a file recognizer key"),
                request.toFileRecognizerRequest()
            )
        AsrDirectMicrophoneEngineConstructorKey.VolcStream ->
            VolcStreamAsrEngine(request.context, request.scope, request.prefs, request.listener)
        AsrDirectMicrophoneEngineConstructorKey.ElevenLabsStream ->
            ElevenLabsStreamAsrEngine(request.context, request.scope, request.prefs, request.listener)
        AsrDirectMicrophoneEngineConstructorKey.OpenAiRealtime ->
            OpenAiRealtimeAsrEngine(request.context, request.scope, request.prefs, request.listener)
        AsrDirectMicrophoneEngineConstructorKey.DashscopeStream ->
            DashscopeStreamAsrEngine(request.context, request.scope, request.prefs, request.listener)
        AsrDirectMicrophoneEngineConstructorKey.SonioxStream ->
            SonioxStreamAsrEngine(request.context, request.scope, request.prefs, request.listener)
        AsrDirectMicrophoneEngineConstructorKey.TencentStream ->
            TencentStreamAsrEngine(request.context, request.scope, request.prefs, request.listener)
        AsrDirectMicrophoneEngineConstructorKey.SenseVoicePseudoStream ->
            SenseVoicePseudoStreamAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrDirectMicrophoneEngineConstructorKey.FireRedAsrPseudoStream ->
            FireRedAsrPseudoStreamAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrDirectMicrophoneEngineConstructorKey.XAsrStream ->
            XAsrStreamAsrEngine(request.context, request.scope, request.prefs, request.listener)
    }
}

private fun AsrFileRecognizerFamily.toDirectMicrophoneFamily(): AsrDirectMicrophoneEngineFamily = when (this) {
    AsrFileRecognizerFamily.File -> AsrDirectMicrophoneEngineFamily.File
    AsrFileRecognizerFamily.LocalFile -> AsrDirectMicrophoneEngineFamily.LocalFile
}

internal fun Prefs.asrEngineModePreferencesSnapshot(): AsrEngineModePreferences {
    val volcModel = VolcAsrModelCatalog.fromIdOrDefault(volcAsrModel)
    return AsrEngineModePreferences(
        volcStreamingEnabled = volcModel.streaming,
        volcStandardFileEnabled = volcModel.protocol == VolcAsrProtocolKind.StandardFile,
        elevenStreamingEnabled = elevenStreamingEnabled,
        openAiStreamingEnabled = isOpenAiStreamingEffective(),
        dashScopeStreamingEnabled = isDashStreamingModelSelected(),
        sonioxStreamingEnabled = sonioxStreamingEnabled,
        tencentStreamingEnabled = tencentStreamingEnabled,
        senseVoicePseudoStreamEnabled = svPseudoStreamEnabled,
        fireRedPseudoStreamEnabled = frPseudoStreamEnabled
    )
}
