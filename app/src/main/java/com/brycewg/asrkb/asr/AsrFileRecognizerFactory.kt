// Builds shared file-recognition engines for direct capture and Push PCM adapters.
package com.brycewg.asrkb.asr

import android.content.Context
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope

internal data class AsrFileRecognizerRequest(
    val context: Context,
    val scope: CoroutineScope,
    val prefs: Prefs,
    val listener: StreamingAsrEngine.Listener,
    val onRequestDuration: ((Long) -> Unit)? = null,
    val modelOverride: AsrRequestModelOverride = AsrRequestModelOverride()
)

internal enum class AsrFileRecognizerFamily {
    File,
    LocalFile
}

internal fun AsrFileRecognizerFamily.progressiveChunkWindow(): NonStreamingChunkWindow = NonStreamingChunkWindow.forLocalFile(this == AsrFileRecognizerFamily.LocalFile)

internal enum class AsrFileRecognizerKey(
    val engineClassName: String,
    val family: AsrFileRecognizerFamily,
    val progressiveChunkingEnabled: Boolean = true
) {
    VolcFile("VolcFileAsrEngine", AsrFileRecognizerFamily.File),
    VolcStandardFile("VolcStandardFileAsrEngine", AsrFileRecognizerFamily.File),
    SiliconFlowFile("SiliconFlowFileAsrEngine", AsrFileRecognizerFamily.File),
    ElevenLabsFile("ElevenLabsFileAsrEngine", AsrFileRecognizerFamily.File),
    OpenAiFile("OpenAiFileAsrEngine", AsrFileRecognizerFamily.File),
    OpenRouterFile("OpenRouterFileAsrEngine", AsrFileRecognizerFamily.File),
    MiMoFile("MiMoFileAsrEngine", AsrFileRecognizerFamily.File),
    DashscopeFile("DashscopeFileAsrEngine", AsrFileRecognizerFamily.File),
    GeminiFile("GeminiFileAsrEngine", AsrFileRecognizerFamily.File),
    SonioxFile("SonioxFileAsrEngine", AsrFileRecognizerFamily.File),
    StepAudioFile("StepAudioFileAsrEngine", AsrFileRecognizerFamily.File),
    ZhipuFile("ZhipuFileAsrEngine", AsrFileRecognizerFamily.File),
    CohereFile("CohereFileAsrEngine", AsrFileRecognizerFamily.File),
    SenseVoiceFile("SenseVoiceFileAsrEngine", AsrFileRecognizerFamily.LocalFile),
    FunAsrNanoFile("FunAsrNanoFileAsrEngine", AsrFileRecognizerFamily.LocalFile),
    Qwen3AsrFile("Qwen3AsrFileAsrEngine", AsrFileRecognizerFamily.LocalFile),
    ParakeetFile("ParakeetFileAsrEngine", AsrFileRecognizerFamily.LocalFile),
    FireRedAsrFile("FireRedAsrFileAsrEngine", AsrFileRecognizerFamily.LocalFile)
}

internal fun fileRecognizerKeyFor(
    resolution: AsrEngineModeResolution
): AsrFileRecognizerKey = when (resolution.vendor) {
    AsrVendor.Volc -> when (resolution.fileEngineVariant) {
        AsrFileEngineVariant.VolcStandard -> AsrFileRecognizerKey.VolcStandardFile
        AsrFileEngineVariant.VolcLegacy,
        AsrFileEngineVariant.Default -> AsrFileRecognizerKey.VolcFile
    }
    AsrVendor.SiliconFlow -> AsrFileRecognizerKey.SiliconFlowFile
    AsrVendor.ElevenLabs -> AsrFileRecognizerKey.ElevenLabsFile
    AsrVendor.OpenAI -> AsrFileRecognizerKey.OpenAiFile
    AsrVendor.OpenRouter -> AsrFileRecognizerKey.OpenRouterFile
    AsrVendor.MiMo -> AsrFileRecognizerKey.MiMoFile
    AsrVendor.DashScope -> AsrFileRecognizerKey.DashscopeFile
    AsrVendor.Gemini -> AsrFileRecognizerKey.GeminiFile
    AsrVendor.Soniox -> AsrFileRecognizerKey.SonioxFile
    AsrVendor.StepAudio -> AsrFileRecognizerKey.StepAudioFile
    AsrVendor.Zhipu -> AsrFileRecognizerKey.ZhipuFile
    AsrVendor.Cohere -> AsrFileRecognizerKey.CohereFile
    AsrVendor.SenseVoice -> AsrFileRecognizerKey.SenseVoiceFile
    AsrVendor.FunAsrNano -> AsrFileRecognizerKey.FunAsrNanoFile
    AsrVendor.Qwen3Asr -> AsrFileRecognizerKey.Qwen3AsrFile
    AsrVendor.Parakeet -> AsrFileRecognizerKey.ParakeetFile
    AsrVendor.FireRedAsr -> AsrFileRecognizerKey.FireRedAsrFile
    AsrVendor.XAsr -> error("X-ASR has no file recognizer")
}

internal fun interface AsrFileRecognizerConstructorTable {
    fun create(
        key: AsrFileRecognizerKey,
        request: AsrFileRecognizerRequest
    ): PcmBatchRecognizer
}

internal object RealAsrFileRecognizerConstructorTable : AsrFileRecognizerConstructorTable {
    override fun create(
        key: AsrFileRecognizerKey,
        request: AsrFileRecognizerRequest
    ): PcmBatchRecognizer = when (key) {
        AsrFileRecognizerKey.VolcFile ->
            VolcFileAsrEngine(
                request.context,
                request.scope,
                request.prefs,
                request.listener,
                request.onRequestDuration,
                modelIdOverride = request.modelOverride.volcAsrModel
            )
        AsrFileRecognizerKey.VolcStandardFile ->
            VolcStandardFileAsrEngine(
                request.context,
                request.scope,
                request.prefs,
                request.listener,
                request.onRequestDuration,
                modelIdOverride = request.modelOverride.volcAsrModel
            )
        AsrFileRecognizerKey.SiliconFlowFile ->
            SiliconFlowFileAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrFileRecognizerKey.ElevenLabsFile ->
            ElevenLabsFileAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrFileRecognizerKey.OpenAiFile ->
            OpenAiFileAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrFileRecognizerKey.OpenRouterFile ->
            OpenRouterFileAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrFileRecognizerKey.MiMoFile ->
            MiMoFileAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrFileRecognizerKey.DashscopeFile ->
            DashscopeFileAsrEngine(
                request.context,
                request.scope,
                request.prefs,
                request.listener,
                request.onRequestDuration,
                modelIdOverride = request.modelOverride.dashAsrModel
            )
        AsrFileRecognizerKey.GeminiFile ->
            GeminiFileAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrFileRecognizerKey.SonioxFile ->
            SonioxFileAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrFileRecognizerKey.StepAudioFile ->
            StepAudioFileAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrFileRecognizerKey.ZhipuFile ->
            ZhipuFileAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrFileRecognizerKey.CohereFile ->
            CohereFileAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrFileRecognizerKey.SenseVoiceFile ->
            SenseVoiceFileAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrFileRecognizerKey.FunAsrNanoFile ->
            FunAsrNanoFileAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrFileRecognizerKey.Qwen3AsrFile ->
            Qwen3AsrFileAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrFileRecognizerKey.ParakeetFile ->
            ParakeetFileAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
        AsrFileRecognizerKey.FireRedAsrFile ->
            FireRedAsrFileAsrEngine(request.context, request.scope, request.prefs, request.listener, request.onRequestDuration)
    }
}

internal fun AsrFileRecognizerConstructorTable.createStreamingEngine(
    key: AsrFileRecognizerKey,
    request: AsrFileRecognizerRequest
): StreamingAsrEngine = create(key, request) as? StreamingAsrEngine
    ?: error("${key.engineClassName} must implement StreamingAsrEngine for direct microphone construction")

internal fun AsrDirectMicrophoneEngineRequest.toFileRecognizerRequest(): AsrFileRecognizerRequest = AsrFileRecognizerRequest(
    context = context,
    scope = scope,
    prefs = prefs,
    listener = listener,
    onRequestDuration = onRequestDuration,
    modelOverride = modelOverride
)

internal fun AsrPushPcmEngineRequest.toFileRecognizerRequest(): AsrFileRecognizerRequest = AsrFileRecognizerRequest(
    context = context,
    scope = scope,
    prefs = prefs,
    listener = listener,
    onRequestDuration = onRequestDuration,
    modelOverride = modelOverride
)
