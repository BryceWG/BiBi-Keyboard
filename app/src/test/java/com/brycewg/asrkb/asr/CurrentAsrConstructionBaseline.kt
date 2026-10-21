// Captures the current ASR construction baseline for refactor safety tests.
package com.brycewg.asrkb.asr

internal enum class CurrentAsrConstructionPath {
    AppDirectMicrophone,
    SpeechRecognizerDirectMicrophone,
    ExternalDirectMicrophone,
    ExternalPushPcm,
    RecordingTestPushPcm,
    ParallelDirectLeg,
    ParallelPushPcmLeg
}

internal enum class CurrentAsrEngineFamily {
    File,
    LocalFile,
    LocalPseudoStream,
    Parallel,
    PushPcmAdapter,
    PushPcmPseudoStream,
    PushPcmStream,
    Stream
}

internal enum class CurrentRecordingTestMode {
    File,
    PushPcm
}

internal data class CurrentAsrConstructionSettings(
    val streamingEnabled: Boolean = false,
    val volcStandardFileEnabled: Boolean = false,
    val pseudoStreamEnabled: Boolean = false
)

internal data class CurrentAsrEngineUse(
    val family: CurrentAsrEngineFamily,
    val engineClassName: String,
    val wrappedRecognizerClassName: String? = null,
    val mode: String? = null
) {
    fun render(): String = buildString {
        append(family)
        append(':')
        append(engineClassName)
        wrappedRecognizerClassName?.let {
            append('(')
            append(it)
            append(')')
        }
        mode?.let {
            append('[')
            append(it)
            append(']')
        }
    }
}

internal object CurrentAsrConstructionBaseline {
    fun describe(
        path: CurrentAsrConstructionPath,
        vendor: AsrVendor,
        settings: CurrentAsrConstructionSettings
    ): CurrentAsrEngineUse = when (path) {
        CurrentAsrConstructionPath.AppDirectMicrophone,
        CurrentAsrConstructionPath.SpeechRecognizerDirectMicrophone ->
            describeAppOrSpeechRecognizerDirect(vendor, settings)
        CurrentAsrConstructionPath.ExternalDirectMicrophone ->
            describeExternalDirect(vendor, settings)
        CurrentAsrConstructionPath.ExternalPushPcm,
        CurrentAsrConstructionPath.RecordingTestPushPcm ->
            describePushPcm(vendor, settings)
        CurrentAsrConstructionPath.ParallelDirectLeg ->
            describePushPcm(vendor, settings)
        CurrentAsrConstructionPath.ParallelPushPcmLeg ->
            describePushPcm(vendor, settings)
    }

    fun describeBackupContainer(externalPcmInput: Boolean): CurrentAsrEngineUse = use(
        CurrentAsrEngineFamily.Parallel,
        "ParallelAsrEngine",
        mode = if (externalPcmInput) "externalPcmInput=true" else null
    )

    fun recordingTestMode(
        vendor: AsrVendor,
        settings: CurrentAsrConstructionSettings,
        backupEnabled: Boolean
    ): CurrentRecordingTestMode = if (backupEnabled || isRecordingTestPushPcmMode(vendor, settings)) {
        CurrentRecordingTestMode.PushPcm
    } else {
        CurrentRecordingTestMode.File
    }

    private fun describeAppOrSpeechRecognizerDirect(
        vendor: AsrVendor,
        settings: CurrentAsrConstructionSettings
    ): CurrentAsrEngineUse = when (vendor) {
        AsrVendor.Volc -> if (settings.streamingEnabled) {
            stream("VolcStreamAsrEngine")
        } else if (settings.volcStandardFileEnabled) {
            file("VolcStandardFileAsrEngine")
        } else {
            file("VolcFileAsrEngine")
        }
        AsrVendor.SiliconFlow -> file("SiliconFlowFileAsrEngine")
        AsrVendor.ElevenLabs -> if (settings.streamingEnabled) {
            stream("ElevenLabsStreamAsrEngine")
        } else {
            file("ElevenLabsFileAsrEngine")
        }
        AsrVendor.OpenAI -> if (settings.streamingEnabled) {
            stream("OpenAiRealtimeAsrEngine")
        } else {
            file("OpenAiFileAsrEngine")
        }
        AsrVendor.OpenRouter -> file("OpenRouterFileAsrEngine")
        AsrVendor.MiMo -> file("MiMoFileAsrEngine")
        AsrVendor.DashScope -> if (settings.streamingEnabled) {
            stream("DashscopeStreamAsrEngine")
        } else {
            file("DashscopeFileAsrEngine")
        }
        AsrVendor.Gemini -> file("GeminiFileAsrEngine")
        AsrVendor.Soniox -> if (settings.streamingEnabled) {
            stream("SonioxStreamAsrEngine")
        } else {
            file("SonioxFileAsrEngine")
        }
        AsrVendor.StepAudio -> file("StepAudioFileAsrEngine")
        AsrVendor.Zhipu -> file("ZhipuFileAsrEngine")
        AsrVendor.Cohere -> file("CohereFileAsrEngine")
        AsrVendor.SenseVoice -> if (settings.pseudoStreamEnabled) {
            localPseudo("SenseVoicePseudoStreamAsrEngine")
        } else {
            localFile("SenseVoiceFileAsrEngine")
        }
        AsrVendor.FunAsrNano -> localFile("FunAsrNanoFileAsrEngine")
        AsrVendor.Qwen3Asr -> localFile("Qwen3AsrFileAsrEngine")
        AsrVendor.Parakeet -> localFile("ParakeetFileAsrEngine")
        AsrVendor.FireRedAsr -> if (settings.pseudoStreamEnabled) {
            localPseudo("FireRedAsrPseudoStreamAsrEngine")
        } else {
            localFile("FireRedAsrFileAsrEngine")
        }
        AsrVendor.XAsr -> stream("XAsrStreamAsrEngine")
        AsrVendor.Tencent -> if (settings.streamingEnabled) {
            stream("TencentStreamAsrEngine")
        } else {
            file("TencentFileAsrEngine")
        }
    }

    private fun describeExternalDirect(
        vendor: AsrVendor,
        settings: CurrentAsrConstructionSettings
    ): CurrentAsrEngineUse = when (vendor) {
        AsrVendor.Volc -> if (settings.streamingEnabled) {
            stream("VolcStreamAsrEngine")
        } else {
            file("VolcFileAsrEngine")
        }
        AsrVendor.SenseVoice -> localFile("SenseVoiceFileAsrEngine")
        AsrVendor.FireRedAsr -> localFile("FireRedAsrFileAsrEngine")
        else -> describeAppOrSpeechRecognizerDirect(vendor, settings)
    }

    private fun describePushPcm(
        vendor: AsrVendor,
        settings: CurrentAsrConstructionSettings
    ): CurrentAsrEngineUse = when (vendor) {
        AsrVendor.Volc -> if (settings.streamingEnabled) {
            pushStream("VolcStreamAsrEngine")
        } else if (settings.volcStandardFileEnabled) {
            pushAdapter("VolcStandardFileAsrEngine")
        } else {
            pushAdapter("VolcFileAsrEngine")
        }
        AsrVendor.SiliconFlow -> pushAdapter("SiliconFlowFileAsrEngine")
        AsrVendor.ElevenLabs -> if (settings.streamingEnabled) {
            pushStream("ElevenLabsStreamAsrEngine")
        } else {
            pushAdapter("ElevenLabsFileAsrEngine")
        }
        AsrVendor.OpenAI -> if (settings.streamingEnabled) {
            pushStream("OpenAiRealtimeAsrEngine")
        } else {
            pushAdapter("OpenAiFileAsrEngine")
        }
        AsrVendor.OpenRouter -> pushAdapter("OpenRouterFileAsrEngine")
        AsrVendor.MiMo -> pushAdapter("MiMoFileAsrEngine")
        AsrVendor.DashScope -> if (settings.streamingEnabled) {
            pushStream("DashscopeStreamAsrEngine")
        } else {
            pushAdapter("DashscopeFileAsrEngine")
        }
        AsrVendor.Gemini -> pushAdapter("GeminiFileAsrEngine")
        AsrVendor.Soniox -> if (settings.streamingEnabled) {
            pushStream("SonioxStreamAsrEngine")
        } else {
            pushAdapter("SonioxFileAsrEngine")
        }
        AsrVendor.StepAudio -> pushAdapter("StepAudioFileAsrEngine")
        AsrVendor.Zhipu -> pushAdapter("ZhipuFileAsrEngine")
        AsrVendor.Cohere -> pushAdapter("CohereFileAsrEngine")
        AsrVendor.SenseVoice -> if (settings.pseudoStreamEnabled) {
            use(CurrentAsrEngineFamily.PushPcmPseudoStream, "SenseVoicePushPcmPseudoStreamAsrEngine")
        } else {
            pushAdapter("SenseVoiceFileAsrEngine")
        }
        AsrVendor.FunAsrNano -> pushAdapter("FunAsrNanoFileAsrEngine")
        AsrVendor.Qwen3Asr -> pushAdapter("Qwen3AsrFileAsrEngine")
        AsrVendor.Parakeet -> pushAdapter("ParakeetFileAsrEngine")
        AsrVendor.FireRedAsr -> if (settings.pseudoStreamEnabled) {
            use(CurrentAsrEngineFamily.PushPcmPseudoStream, "FireRedAsrPushPcmPseudoStreamAsrEngine")
        } else {
            pushAdapter("FireRedAsrFileAsrEngine")
        }
        AsrVendor.XAsr -> pushStream("XAsrStreamAsrEngine")
        AsrVendor.Tencent -> if (settings.streamingEnabled) {
            pushStream("TencentStreamAsrEngine")
        } else {
            pushAdapter("TencentFileAsrEngine")
        }
    }

    private fun isRecordingTestPushPcmMode(
        vendor: AsrVendor,
        settings: CurrentAsrConstructionSettings
    ): Boolean = when (vendor) {
        AsrVendor.Volc,
        AsrVendor.DashScope,
        AsrVendor.Soniox,
        AsrVendor.ElevenLabs,
        AsrVendor.OpenAI -> settings.streamingEnabled
        AsrVendor.XAsr -> true
        AsrVendor.Tencent -> settings.streamingEnabled
        AsrVendor.SenseVoice,
        AsrVendor.FireRedAsr -> settings.pseudoStreamEnabled
        AsrVendor.SiliconFlow,
        AsrVendor.OpenRouter,
        AsrVendor.Gemini,
        AsrVendor.MiMo,
        AsrVendor.StepAudio,
        AsrVendor.Zhipu,
        AsrVendor.Cohere,
        AsrVendor.FunAsrNano,
        AsrVendor.Qwen3Asr,
        AsrVendor.Parakeet -> false
    }

    private fun stream(engine: String): CurrentAsrEngineUse = use(CurrentAsrEngineFamily.Stream, engine)

    private fun file(engine: String): CurrentAsrEngineUse = use(CurrentAsrEngineFamily.File, engine)

    private fun localFile(engine: String): CurrentAsrEngineUse = use(CurrentAsrEngineFamily.LocalFile, engine)

    private fun localPseudo(engine: String): CurrentAsrEngineUse = use(CurrentAsrEngineFamily.LocalPseudoStream, engine)

    private fun pushStream(engine: String): CurrentAsrEngineUse = use(CurrentAsrEngineFamily.PushPcmStream, engine)

    private fun pushAdapter(wrappedRecognizer: String): CurrentAsrEngineUse = use(
        family = CurrentAsrEngineFamily.PushPcmAdapter,
        engine = "GenericPushFileAsrAdapter",
        wrappedRecognizer = wrappedRecognizer
    )

    private fun use(
        family: CurrentAsrEngineFamily,
        engine: String,
        wrappedRecognizer: String? = null,
        mode: String? = null
    ): CurrentAsrEngineUse = CurrentAsrEngineUse(
        family = family,
        engineClassName = engine,
        wrappedRecognizerClassName = wrappedRecognizer,
        mode = mode
    )
}
