// Resolves ASR engine construction mode from invocation context and supplier capabilities.
package com.brycewg.asrkb.asr

internal data class AsrEngineModePreferences(
    val volcStreamingEnabled: Boolean = false,
    val volcStandardFileEnabled: Boolean = false,
    val elevenStreamingEnabled: Boolean = false,
    val openAiStreamingEnabled: Boolean = false,
    val dashScopeStreamingEnabled: Boolean = false,
    val sonioxStreamingEnabled: Boolean = false,
    val tencentStreamingEnabled: Boolean = false,
    val senseVoicePseudoStreamEnabled: Boolean = false,
    val fireRedPseudoStreamEnabled: Boolean = false
)

internal enum class AsrEngineConstructionSource {
    App,
    SpeechRecognizer,
    ExternalIntegration
}

internal enum class AsrResolvedEngineMode {
    DirectFile,
    DirectLocalFile,
    DirectStream,
    DirectLocalStream,
    DirectLocalPseudoStream,
    PushPcmFileAdapter,
    PushPcmNativeStream,
    PushPcmLocalStream,
    PushPcmPseudoStream
}

internal enum class AsrFileEngineVariant {
    Default,
    VolcLegacy,
    VolcStandard
}

internal data class AsrEngineModeResolution(
    val vendor: AsrVendor,
    val invocationMode: AsrEngineInvocationMode,
    val source: AsrEngineConstructionSource,
    val mode: AsrResolvedEngineMode,
    val fileEngineVariant: AsrFileEngineVariant = AsrFileEngineVariant.Default
) {
    val consumesPushedPcm: Boolean
        get() = invocationMode.consumesPushedPcm

    val usesPushPcmFileAdapter: Boolean
        get() = mode == AsrResolvedEngineMode.PushPcmFileAdapter
}

internal object AsrEngineModeResolver {
    fun resolve(
        vendor: AsrVendor,
        invocationMode: AsrEngineInvocationMode,
        preferences: AsrEngineModePreferences = AsrEngineModePreferences(),
        source: AsrEngineConstructionSource = AsrEngineConstructionSource.App
    ): AsrEngineModeResolution {
        val descriptor = AsrVendorRegistry.descriptorFor(vendor)
        return if (invocationMode.consumesPushedPcm) {
            resolvePushedPcm(vendor, invocationMode, source, preferences, descriptor.capabilities)
        } else {
            resolveDirect(vendor, invocationMode, source, preferences, descriptor.capabilities)
        }
    }

    private fun resolveDirect(
        vendor: AsrVendor,
        invocationMode: AsrEngineInvocationMode,
        source: AsrEngineConstructionSource,
        preferences: AsrEngineModePreferences,
        capabilities: Set<AsrVendorCapability>
    ): AsrEngineModeResolution {
        if (capabilities.supports(AsrVendorCapability.PseudoStreamingRecognition) &&
            source != AsrEngineConstructionSource.ExternalIntegration &&
            preferences.pseudoStreamEnabledFor(vendor)
        ) {
            return resolution(
                vendor,
                invocationMode,
                source,
                AsrResolvedEngineMode.DirectLocalPseudoStream
            )
        }

        if (capabilities.supports(AsrVendorCapability.StreamingRecognition) &&
            preferences.streamingEnabledFor(vendor)
        ) {
            return resolution(
                vendor,
                invocationMode,
                source,
                if (capabilities.supports(AsrVendorCapability.LocalRecognition)) {
                    AsrResolvedEngineMode.DirectLocalStream
                } else {
                    AsrResolvedEngineMode.DirectStream
                }
            )
        }

        require(capabilities.supports(AsrVendorCapability.FileRecognition)) {
            "No direct file or stream construction mode for $vendor"
        }
        return resolution(
            vendor,
            invocationMode,
            source,
            if (capabilities.supports(AsrVendorCapability.LocalRecognition)) {
                AsrResolvedEngineMode.DirectLocalFile
            } else {
                AsrResolvedEngineMode.DirectFile
            },
            fileEngineVariant = fileVariantFor(vendor, invocationMode, source, preferences)
        )
    }

    private fun resolvePushedPcm(
        vendor: AsrVendor,
        invocationMode: AsrEngineInvocationMode,
        source: AsrEngineConstructionSource,
        preferences: AsrEngineModePreferences,
        capabilities: Set<AsrVendorCapability>
    ): AsrEngineModeResolution {
        if (capabilities.supports(AsrVendorCapability.PseudoStreamingRecognition) &&
            preferences.pseudoStreamEnabledFor(vendor)
        ) {
            return resolution(vendor, invocationMode, source, AsrResolvedEngineMode.PushPcmPseudoStream)
        }

        if (capabilities.supports(AsrVendorCapability.StreamingRecognition) &&
            capabilities.supports(AsrVendorCapability.NativePushPcmInput) &&
            preferences.streamingEnabledFor(vendor)
        ) {
            return resolution(
                vendor,
                invocationMode,
                source,
                if (capabilities.supports(AsrVendorCapability.LocalRecognition)) {
                    AsrResolvedEngineMode.PushPcmLocalStream
                } else {
                    AsrResolvedEngineMode.PushPcmNativeStream
                }
            )
        }

        require(capabilities.supports(AsrVendorCapability.PushPcmFileAdapter)) {
            "No Push PCM construction mode for $vendor"
        }
        return resolution(
            vendor,
            invocationMode,
            source,
            AsrResolvedEngineMode.PushPcmFileAdapter,
            fileEngineVariant = fileVariantFor(vendor, invocationMode, source, preferences)
        )
    }

    private fun resolution(
        vendor: AsrVendor,
        invocationMode: AsrEngineInvocationMode,
        source: AsrEngineConstructionSource,
        mode: AsrResolvedEngineMode,
        fileEngineVariant: AsrFileEngineVariant = AsrFileEngineVariant.Default
    ): AsrEngineModeResolution = AsrEngineModeResolution(
        vendor = vendor,
        invocationMode = invocationMode,
        source = source,
        mode = mode,
        fileEngineVariant = fileEngineVariant
    )

    private fun fileVariantFor(
        vendor: AsrVendor,
        invocationMode: AsrEngineInvocationMode,
        source: AsrEngineConstructionSource,
        preferences: AsrEngineModePreferences
    ): AsrFileEngineVariant = when (vendor) {
        AsrVendor.Volc -> if (preferences.volcStandardFileEnabled) {
            if (
                invocationMode == AsrEngineInvocationMode.DirectMicrophoneCapture &&
                source == AsrEngineConstructionSource.ExternalIntegration
            ) {
                AsrFileEngineVariant.VolcLegacy
            } else {
                AsrFileEngineVariant.VolcStandard
            }
        } else {
            AsrFileEngineVariant.VolcLegacy
        }
        else -> AsrFileEngineVariant.Default
    }

    private fun Set<AsrVendorCapability>.supports(capability: AsrVendorCapability): Boolean = capability in this

    private fun AsrEngineModePreferences.streamingEnabledFor(vendor: AsrVendor): Boolean = when (vendor) {
        AsrVendor.Volc -> volcStreamingEnabled
        AsrVendor.ElevenLabs -> elevenStreamingEnabled
        AsrVendor.OpenAI -> openAiStreamingEnabled
        AsrVendor.DashScope -> dashScopeStreamingEnabled
        AsrVendor.Soniox -> sonioxStreamingEnabled
        AsrVendor.XAsr -> true
        AsrVendor.Tencent -> tencentStreamingEnabled
        else -> false
    }

    private fun AsrEngineModePreferences.pseudoStreamEnabledFor(vendor: AsrVendor): Boolean = when (vendor) {
        AsrVendor.SenseVoice -> senseVoicePseudoStreamEnabled
        AsrVendor.FireRedAsr -> fireRedPseudoStreamEnabled
        else -> false
    }
}
