// Tests ASR engine mode resolution without constructing real engines.
package com.brycewg.asrkb.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrEngineModeResolverTest {
    @Test
    fun resolverCoversEveryVendorAndInvocationMode() {
        AsrVendor.entries.forEach { vendor ->
            AsrEngineInvocationMode.entries.forEach { invocationMode ->
                val resolution = resolve(vendor, invocationMode = invocationMode)

                assertSame("vendor for $vendor/$invocationMode", vendor, resolution.vendor)
                assertSame(invocationMode, resolution.invocationMode)
                assertEquals(invocationMode.consumesPushedPcm, resolution.consumesPushedPcm)
            }

            val externalDirect = resolve(
                vendor,
                source = AsrEngineConstructionSource.ExternalIntegration
            )
            assertSame(vendor, externalDirect.vendor)
        }
    }

    @Test
    fun onlineStreamingTogglesSelectNativeStreamOrFileConstruction() {
        streamingCases.forEach { case ->
            val streamingPreferences = preferencesFor(case.vendor, streaming = true)
            assertSame(
                "direct stream for ${case.vendor}",
                AsrResolvedEngineMode.DirectStream,
                resolve(case.vendor, preferences = streamingPreferences).mode
            )
            assertSame(
                "Push PCM native stream for ${case.vendor}",
                AsrResolvedEngineMode.PushPcmNativeStream,
                resolve(
                    case.vendor,
                    invocationMode = AsrEngineInvocationMode.PushPcm,
                    preferences = streamingPreferences
                ).mode
            )

            val filePreferences = preferencesFor(case.vendor, streaming = false)
            assertSame(
                "direct file for ${case.vendor}",
                AsrResolvedEngineMode.DirectFile,
                resolve(case.vendor, preferences = filePreferences).mode
            )
            assertSame(
                "Push PCM adapter for ${case.vendor}",
                AsrResolvedEngineMode.PushPcmFileAdapter,
                resolve(
                    case.vendor,
                    invocationMode = AsrEngineInvocationMode.PushPcm,
                    preferences = filePreferences
                ).mode
            )
        }
    }

    @Test
    fun fileOnlySuppliersUseFileAndPushPcmFileAdapter() {
        val online = resolve(AsrVendor.SiliconFlow)
        val onlinePush = resolve(AsrVendor.SiliconFlow, invocationMode = AsrEngineInvocationMode.PushPcm)
        val local = resolve(AsrVendor.FunAsrNano)
        val localPush = resolve(AsrVendor.FunAsrNano, invocationMode = AsrEngineInvocationMode.PushPcm)

        assertSame(AsrResolvedEngineMode.DirectFile, online.mode)
        assertSame(AsrResolvedEngineMode.PushPcmFileAdapter, onlinePush.mode)
        assertSame(AsrResolvedEngineMode.DirectLocalFile, local.mode)
        assertSame(AsrResolvedEngineMode.PushPcmFileAdapter, localPush.mode)
        assertFalse(online.usesPushPcmFileAdapter)
        assertTrue(onlinePush.usesPushPcmFileAdapter)
    }

    @Test
    fun volcStandardFileVariantPreservesExternalDirectChannelDifference() {
        val preferences = AsrEngineModePreferences(volcStandardFileEnabled = true)

        val appDirect = resolve(AsrVendor.Volc, preferences = preferences)
        val speechDirect = resolve(
            AsrVendor.Volc,
            preferences = preferences,
            source = AsrEngineConstructionSource.SpeechRecognizer
        )
        val externalDirect = resolve(
            AsrVendor.Volc,
            preferences = preferences,
            source = AsrEngineConstructionSource.ExternalIntegration
        )
        val externalPush = resolve(
            AsrVendor.Volc,
            invocationMode = AsrEngineInvocationMode.PushPcm,
            preferences = preferences,
            source = AsrEngineConstructionSource.ExternalIntegration
        )

        assertSame(AsrFileEngineVariant.VolcStandard, appDirect.fileEngineVariant)
        assertSame(AsrFileEngineVariant.VolcStandard, speechDirect.fileEngineVariant)
        assertSame(AsrFileEngineVariant.VolcLegacy, externalDirect.fileEngineVariant)
        assertSame(AsrFileEngineVariant.VolcStandard, externalPush.fileEngineVariant)
        assertSame(AsrResolvedEngineMode.PushPcmFileAdapter, externalPush.mode)
    }

    @Test
    fun localPseudoStreamTogglesPreserveExternalDirectAndPushedPcmDifferences() {
        pseudoCases.forEach { case ->
            val offPreferences = pseudoPreferencesFor(case.vendor, enabled = false)
            assertSame(AsrResolvedEngineMode.DirectLocalFile, resolve(case.vendor, preferences = offPreferences).mode)
            assertSame(
                AsrResolvedEngineMode.PushPcmFileAdapter,
                resolve(
                    case.vendor,
                    invocationMode = AsrEngineInvocationMode.PushPcm,
                    preferences = offPreferences
                ).mode
            )

            val onPreferences = pseudoPreferencesFor(case.vendor, enabled = true)
            assertSame(AsrResolvedEngineMode.DirectLocalPseudoStream, resolve(case.vendor, preferences = onPreferences).mode)
            assertSame(
                AsrResolvedEngineMode.DirectLocalFile,
                resolve(
                    case.vendor,
                    preferences = onPreferences,
                    source = AsrEngineConstructionSource.ExternalIntegration
                ).mode
            )
            assertSame(
                AsrResolvedEngineMode.PushPcmPseudoStream,
                resolve(
                    case.vendor,
                    invocationMode = AsrEngineInvocationMode.PushPcm,
                    preferences = onPreferences
                ).mode
            )
        }
    }

    @Test
    fun xAsrAlwaysResolvesToLocalStreamingWithoutFileFallback() {
        val descriptor = AsrVendorRegistry.descriptorFor(AsrVendor.XAsr)
        val direct = resolve(AsrVendor.XAsr)
        val push = resolve(AsrVendor.XAsr, invocationMode = AsrEngineInvocationMode.PushPcm)

        assertFalse(AsrVendorCapability.FileRecognition in descriptor.capabilities)
        assertTrue(AsrVendorCapability.StreamingRecognition in descriptor.capabilities)
        assertSame(AsrResolvedEngineMode.DirectLocalStream, direct.mode)
        assertSame(AsrResolvedEngineMode.PushPcmLocalStream, push.mode)
    }

    @Test
    fun recordingTestKeepsPushPcmModeResolutionIndependentOfDurationReporting() {
        val preferences = AsrEngineModePreferences(openAiStreamingEnabled = true)
        val push = resolve(
            AsrVendor.OpenAI,
            invocationMode = AsrEngineInvocationMode.PushPcm,
            preferences = preferences
        )
        val recordingTest = resolve(
            AsrVendor.OpenAI,
            invocationMode = AsrEngineInvocationMode.RecordingTest,
            preferences = preferences
        )
        val backup = resolve(
            AsrVendor.OpenAI,
            invocationMode = AsrEngineInvocationMode.ParallelBackup,
            preferences = preferences
        )

        assertTrue(push.invocationMode.reportsRequestDuration)
        assertFalse(recordingTest.invocationMode.reportsRequestDuration)
        assertFalse(backup.invocationMode.reportsRequestDuration)
        assertSame(push.mode, recordingTest.mode)
        assertSame(push.mode, backup.mode)
        assertSame(push.fileEngineVariant, recordingTest.fileEngineVariant)
        assertSame(push.fileEngineVariant, backup.fileEngineVariant)
    }

    @Test
    fun resolverUsesRegistryCapabilitiesForModeShape() {
        AsrVendorRegistry.descriptors.forEach { descriptor ->
            val direct = resolve(descriptor.vendor)
            val push = resolve(descriptor.vendor, invocationMode = AsrEngineInvocationMode.PushPcm)
            val capabilities = descriptor.capabilities

            if (AsrVendorCapability.LocalRecognition in capabilities) {
                assertTrue("direct local mode for ${descriptor.vendor}", direct.mode.name.contains("Local"))
            }
            if (AsrVendorCapability.PushPcmFileAdapter !in capabilities) {
                assertFalse("no Push PCM adapter for ${descriptor.vendor}", push.usesPushPcmFileAdapter)
            }
        }
    }

    private fun resolve(
        vendor: AsrVendor,
        invocationMode: AsrEngineInvocationMode = AsrEngineInvocationMode.DirectMicrophoneCapture,
        preferences: AsrEngineModePreferences = AsrEngineModePreferences(),
        source: AsrEngineConstructionSource = AsrEngineConstructionSource.App
    ): AsrEngineModeResolution = AsrEngineModeResolver.resolve(
        vendor = vendor,
        invocationMode = invocationMode,
        preferences = preferences,
        source = source
    )

    private fun preferencesFor(vendor: AsrVendor, streaming: Boolean): AsrEngineModePreferences = when (vendor) {
        AsrVendor.Volc -> AsrEngineModePreferences(volcStreamingEnabled = streaming)
        AsrVendor.ElevenLabs -> AsrEngineModePreferences(elevenStreamingEnabled = streaming)
        AsrVendor.OpenAI -> AsrEngineModePreferences(openAiStreamingEnabled = streaming)
        AsrVendor.DashScope -> AsrEngineModePreferences(dashScopeStreamingEnabled = streaming)
        AsrVendor.Soniox -> AsrEngineModePreferences(sonioxStreamingEnabled = streaming)
        AsrVendor.Tencent -> AsrEngineModePreferences(tencentStreamingEnabled = streaming)
        else -> error("$vendor is not controlled by an online streaming toggle")
    }

    private fun pseudoPreferencesFor(vendor: AsrVendor, enabled: Boolean): AsrEngineModePreferences = when (vendor) {
        AsrVendor.SenseVoice -> AsrEngineModePreferences(senseVoicePseudoStreamEnabled = enabled)
        AsrVendor.FireRedAsr -> AsrEngineModePreferences(fireRedPseudoStreamEnabled = enabled)
        else -> error("$vendor is not controlled by a pseudo stream toggle")
    }

    private data class StreamingCase(val vendor: AsrVendor)

    private data class PseudoCase(val vendor: AsrVendor)

    private companion object {
        private val streamingCases = listOf(
            StreamingCase(AsrVendor.Volc),
            StreamingCase(AsrVendor.ElevenLabs),
            StreamingCase(AsrVendor.OpenAI),
            StreamingCase(AsrVendor.DashScope),
            StreamingCase(AsrVendor.Soniox),
            StreamingCase(AsrVendor.Tencent)
        )

        private val pseudoCases = listOf(
            PseudoCase(AsrVendor.SenseVoice),
            PseudoCase(AsrVendor.FireRedAsr)
        )
    }
}
