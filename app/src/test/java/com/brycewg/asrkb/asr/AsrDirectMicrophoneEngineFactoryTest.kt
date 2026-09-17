// Tests direct-microphone factory planning against the current construction baseline.
package com.brycewg.asrkb.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrDirectMicrophoneEngineFactoryTest {
    private val factory = AsrDirectMicrophoneEngineFactory()

    @Test
    fun defaultPlansMatchBaselineForEveryVendorAndDirectSource() {
        AsrVendor.entries.forEach { vendor ->
            directSourceCases.forEach { sourceCase ->
                assertPlanMatchesBaseline(
                    vendor = vendor,
                    sourceCase = sourceCase,
                    preferences = AsrEngineModePreferences()
                )
            }
        }
    }

    @Test
    fun streamingPreferencePlansMatchBaseline() {
        streamingVendors.forEach { vendor ->
            directSourceCases.forEach { sourceCase ->
                assertPlanMatchesBaseline(
                    vendor = vendor,
                    sourceCase = sourceCase,
                    preferences = streamingPreferencesFor(vendor)
                )
            }
        }
    }

    @Test
    fun volcStandardFilePreservesExternalDirectLegacyDifference() {
        val preferences = AsrEngineModePreferences(volcStandardFileEnabled = true)

        assertPlanMatchesBaseline(AsrVendor.Volc, appSource, preferences)
        assertPlanMatchesBaseline(AsrVendor.Volc, speechRecognizerSource, preferences)
        assertPlanMatchesBaseline(AsrVendor.Volc, externalSource, preferences)

        assertEquals(
            "VolcStandardFileAsrEngine",
            factory.resolvePlan(AsrVendor.Volc, preferences, appSource.source).engineClassName
        )
        assertEquals(
            "VolcStandardFileAsrEngine",
            factory.resolvePlan(AsrVendor.Volc, preferences, speechRecognizerSource.source).engineClassName
        )
        assertEquals(
            "VolcFileAsrEngine",
            factory.resolvePlan(AsrVendor.Volc, preferences, externalSource.source).engineClassName
        )
    }

    @Test
    fun localPseudoStreamPlansPreserveExternalDirectFileFallback() {
        listOf(
            AsrVendor.SenseVoice to AsrEngineModePreferences(senseVoicePseudoStreamEnabled = true),
            AsrVendor.FireRedAsr to AsrEngineModePreferences(fireRedPseudoStreamEnabled = true)
        ).forEach { (vendor, preferences) ->
            assertPlanMatchesBaseline(vendor, appSource, preferences)
            assertPlanMatchesBaseline(vendor, speechRecognizerSource, preferences)
            assertPlanMatchesBaseline(vendor, externalSource, preferences)

            assertSame(
                AsrDirectMicrophoneEngineFamily.LocalPseudoStream,
                factory.resolvePlan(vendor, preferences, appSource.source).family
            )
            assertSame(
                AsrDirectMicrophoneEngineFamily.LocalFile,
                factory.resolvePlan(vendor, preferences, externalSource.source).family
            )
        }
    }

    @Test
    fun progressiveChunkingFollowsResolvedFileRecognizers() {
        AsrVendor.entries.forEach { vendor ->
            val plan = factory.resolvePlan(
                vendor = vendor,
                preferences = AsrEngineModePreferences(),
                source = AsrEngineConstructionSource.App
            )
            val key = plan.fileRecognizerKey
            assertEquals(key != null, plan.progressiveChunkingEnabled)
            if (key != null) {
                val expectedWindow = if (key.family == AsrFileRecognizerFamily.LocalFile) {
                    NonStreamingChunkWindow.Local
                } else {
                    NonStreamingChunkWindow.Online
                }
                assertEquals(expectedWindow, key.family.progressiveChunkWindow())
            }
        }
        assertFalse(
            factory.resolvePlan(
                AsrVendor.SenseVoice,
                AsrEngineModePreferences(senseVoicePseudoStreamEnabled = true),
                AsrEngineConstructionSource.App
            ).progressiveChunkingEnabled
        )
        assertFalse(
            factory.resolvePlan(
                AsrVendor.FireRedAsr,
                AsrEngineModePreferences(fireRedPseudoStreamEnabled = true),
                AsrEngineConstructionSource.App
            ).progressiveChunkingEnabled
        )
    }

    @Test
    fun identityIsStableForEquivalentDirectConstructionPlan() {
        val first = factory.resolvePlan(
            vendor = AsrVendor.Volc,
            preferences = AsrEngineModePreferences(),
            source = AsrEngineConstructionSource.App
        )
        val second = factory.resolvePlan(
            vendor = AsrVendor.Volc,
            preferences = AsrEngineModePreferences(),
            source = AsrEngineConstructionSource.App
        )

        assertEquals(first.identity, second.identity)
        assertSame(AsrVendor.Volc, first.identity.vendor)
        assertSame(AsrResolvedEngineMode.DirectFile, first.identity.mode)
        assertSame(AsrFileEngineVariant.VolcLegacy, first.identity.fileEngineVariant)
        assertEquals(first.constructorKey, first.identity.constructorKey)
        assertEquals(first.fileRecognizerKey, first.identity.fileRecognizerKey)
    }

    @Test
    fun identityChangesWhenResolvedDirectConstructionPlanChanges() {
        val volcFile = factory.resolvePlan(
            vendor = AsrVendor.Volc,
            preferences = AsrEngineModePreferences(),
            source = AsrEngineConstructionSource.App
        ).identity
        val volcStream = factory.resolvePlan(
            vendor = AsrVendor.Volc,
            preferences = AsrEngineModePreferences(volcStreamingEnabled = true),
            source = AsrEngineConstructionSource.App
        ).identity
        val volcStandardFile = factory.resolvePlan(
            vendor = AsrVendor.Volc,
            preferences = AsrEngineModePreferences(volcStandardFileEnabled = true),
            source = AsrEngineConstructionSource.App
        ).identity
        val senseFile = factory.resolvePlan(
            vendor = AsrVendor.SenseVoice,
            preferences = AsrEngineModePreferences(),
            source = AsrEngineConstructionSource.App
        ).identity
        val sensePseudoStream = factory.resolvePlan(
            vendor = AsrVendor.SenseVoice,
            preferences = AsrEngineModePreferences(senseVoicePseudoStreamEnabled = true),
            source = AsrEngineConstructionSource.App
        ).identity

        assertNotEquals(volcFile, volcStream)
        assertNotEquals(volcFile, volcStandardFile)
        assertNotEquals(senseFile, sensePseudoStream)
    }

    @Test
    fun plansAreBackedByRegistryCapabilitiesThroughResolver() {
        AsrVendorRegistry.descriptors.forEach { descriptor ->
            val plan = factory.resolvePlan(
                vendor = descriptor.vendor,
                preferences = AsrEngineModePreferences()
            )
            val capabilities = descriptor.capabilities

            assertSame(descriptor.vendor, plan.vendor)
            assertSame(AsrEngineInvocationMode.DirectMicrophoneCapture, plan.resolution.invocationMode)
            assertFalse(plan.resolution.consumesPushedPcm)
            if (AsrVendorCapability.LocalRecognition in capabilities) {
                assertTrue(
                    "local family follows registry capability for ${descriptor.vendor}",
                    plan.family == AsrDirectMicrophoneEngineFamily.LocalFile ||
                        plan.family == AsrDirectMicrophoneEngineFamily.LocalPseudoStream ||
                        plan.family == AsrDirectMicrophoneEngineFamily.Stream
                )
            }
            if (descriptor.vendor == AsrVendor.XAsr) {
                assertFalse(AsrVendorCapability.FileRecognition in capabilities)
                assertSame(AsrDirectMicrophoneEngineFamily.Stream, plan.family)
            }
        }
    }

    @Test
    fun everyDirectConstructorKeyIsCoveredByAtLeastOnePlan() {
        val plannedKeys = buildSet {
            val preferenceCases = listOf(
                AsrEngineModePreferences(),
                AsrEngineModePreferences(volcStreamingEnabled = true),
                AsrEngineModePreferences(volcStandardFileEnabled = true),
                AsrEngineModePreferences(elevenStreamingEnabled = true),
                AsrEngineModePreferences(openAiStreamingEnabled = true),
                AsrEngineModePreferences(dashScopeStreamingEnabled = true),
                AsrEngineModePreferences(sonioxStreamingEnabled = true),
                AsrEngineModePreferences(senseVoicePseudoStreamEnabled = true),
                AsrEngineModePreferences(fireRedPseudoStreamEnabled = true)
            )
            AsrVendor.entries.forEach { vendor ->
                directSourceCases.forEach { sourceCase ->
                    preferenceCases.forEach { preferences ->
                        add(factory.resolvePlan(vendor, preferences, sourceCase.source).constructorKey)
                    }
                }
            }
        }

        assertEquals(AsrDirectMicrophoneEngineConstructorKey.entries.toSet(), plannedKeys)
    }

    @Test
    fun validationRequiredDirectModeUsesSharedAvailabilityChecks() {
        val configuredOnline = isDirectMicrophoneFactoryVendorAvailable(
            vendor = AsrVendor.OpenAI,
            checkers = AsrVendorAvailabilityCheckers(
                onlineConfiguration = { vendor -> vendor == AsrVendor.OpenAI },
                localModelReadiness = { error("online supplier should not use local readiness") }
            )
        )
        val missingLocalModel = isDirectMicrophoneFactoryVendorAvailable(
            vendor = AsrVendor.FunAsrNano,
            checkers = AsrVendorAvailabilityCheckers(
                onlineConfiguration = { error("local supplier should not use online configuration") },
                localModelReadiness = { false }
            )
        )

        assertTrue(configuredOnline)
        assertFalse(missingLocalModel)
    }

    @Test
    fun validationHelperRejectsNonDirectInvocationMode() {
        try {
            isDirectMicrophoneFactoryVendorAvailable(
                vendor = AsrVendor.Volc,
                invocationMode = AsrEngineInvocationMode.PushPcm,
                checkers = AsrVendorAvailabilityCheckers(
                    onlineConfiguration = { true },
                    localModelReadiness = { true }
                )
            )
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("outside direct microphone factory scope"))
            return
        }

        throw AssertionError("Push PCM invocation should be rejected")
    }

    private fun assertPlanMatchesBaseline(
        vendor: AsrVendor,
        sourceCase: SourceCase,
        preferences: AsrEngineModePreferences
    ) {
        val plan = factory.resolvePlan(
            vendor = vendor,
            preferences = preferences,
            source = sourceCase.source
        )
        val expected = CurrentAsrConstructionBaseline.describe(
            path = sourceCase.baselinePath,
            vendor = vendor,
            settings = preferences.toBaselineSettings(vendor)
        )

        assertEquals("engine for $vendor/${sourceCase.source}/$preferences", expected.engineClassName, plan.engineClassName)
        assertSame("family for $vendor/${sourceCase.source}/$preferences", expected.family.toDirectFamily(), plan.family)
    }

    private fun AsrEngineModePreferences.toBaselineSettings(vendor: AsrVendor): CurrentAsrConstructionSettings = CurrentAsrConstructionSettings(
        streamingEnabled = when (vendor) {
            AsrVendor.Volc -> volcStreamingEnabled
            AsrVendor.ElevenLabs -> elevenStreamingEnabled
            AsrVendor.OpenAI -> openAiStreamingEnabled
            AsrVendor.DashScope -> dashScopeStreamingEnabled
            AsrVendor.Soniox -> sonioxStreamingEnabled
            AsrVendor.XAsr -> true
            else -> false
        },
        volcStandardFileEnabled = volcStandardFileEnabled,
        pseudoStreamEnabled = when (vendor) {
            AsrVendor.SenseVoice -> senseVoicePseudoStreamEnabled
            AsrVendor.FireRedAsr -> fireRedPseudoStreamEnabled
            else -> false
        }
    )

    private fun CurrentAsrEngineFamily.toDirectFamily(): AsrDirectMicrophoneEngineFamily = when (this) {
        CurrentAsrEngineFamily.File -> AsrDirectMicrophoneEngineFamily.File
        CurrentAsrEngineFamily.LocalFile -> AsrDirectMicrophoneEngineFamily.LocalFile
        CurrentAsrEngineFamily.LocalPseudoStream -> AsrDirectMicrophoneEngineFamily.LocalPseudoStream
        CurrentAsrEngineFamily.Stream -> AsrDirectMicrophoneEngineFamily.Stream
        CurrentAsrEngineFamily.Parallel,
        CurrentAsrEngineFamily.PushPcmAdapter,
        CurrentAsrEngineFamily.PushPcmPseudoStream,
        CurrentAsrEngineFamily.PushPcmStream -> error("$this is not a direct microphone family")
    }

    private fun streamingPreferencesFor(vendor: AsrVendor): AsrEngineModePreferences = when (vendor) {
        AsrVendor.Volc -> AsrEngineModePreferences(volcStreamingEnabled = true)
        AsrVendor.ElevenLabs -> AsrEngineModePreferences(elevenStreamingEnabled = true)
        AsrVendor.OpenAI -> AsrEngineModePreferences(openAiStreamingEnabled = true)
        AsrVendor.DashScope -> AsrEngineModePreferences(dashScopeStreamingEnabled = true)
        AsrVendor.Soniox -> AsrEngineModePreferences(sonioxStreamingEnabled = true)
        else -> error("$vendor is not controlled by a streaming preference")
    }

    private data class SourceCase(
        val source: AsrEngineConstructionSource,
        val baselinePath: CurrentAsrConstructionPath
    )

    private companion object {
        private val appSource = SourceCase(
            source = AsrEngineConstructionSource.App,
            baselinePath = CurrentAsrConstructionPath.AppDirectMicrophone
        )
        private val speechRecognizerSource = SourceCase(
            source = AsrEngineConstructionSource.SpeechRecognizer,
            baselinePath = CurrentAsrConstructionPath.SpeechRecognizerDirectMicrophone
        )
        private val externalSource = SourceCase(
            source = AsrEngineConstructionSource.ExternalIntegration,
            baselinePath = CurrentAsrConstructionPath.ExternalDirectMicrophone
        )
        private val directSourceCases = listOf(appSource, speechRecognizerSource, externalSource)
        private val streamingVendors = listOf(
            AsrVendor.Volc,
            AsrVendor.ElevenLabs,
            AsrVendor.OpenAI,
            AsrVendor.DashScope,
            AsrVendor.Soniox
        )
    }
}
