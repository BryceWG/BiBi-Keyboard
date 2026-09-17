// Tests Push PCM factory planning against the current construction baseline.
package com.brycewg.asrkb.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrPushPcmEngineFactoryTest {
    private val factory = AsrPushPcmEngineFactory()

    @Test
    fun defaultPlansMatchBaselineForEveryVendorAndPushedPcmInvocation() {
        AsrVendor.entries.forEach { vendor ->
            pushedPcmInvocationCases.forEach { invocationCase ->
                assertPlanMatchesBaseline(
                    vendor = vendor,
                    invocationCase = invocationCase,
                    preferences = AsrEngineModePreferences()
                )
            }
        }
    }

    @Test
    fun streamingPreferencePlansUseExternalPcmStreamEngines() {
        streamingVendors.forEach { vendor ->
            pushedPcmInvocationCases.forEach { invocationCase ->
                val preferences = streamingPreferencesFor(vendor)
                val plan = assertPlanMatchesBaseline(vendor, invocationCase, preferences)

                assertSame(AsrPushPcmEngineFamily.NativeStream, plan.family)
                assertTrue(plan.externalPcmMode)
                assertNull(plan.wrappedRecognizerClassName)
            }
        }
    }

    @Test
    fun volcStandardFileUsesGenericAdapterWithStandardFileRecognizer() {
        val preferences = AsrEngineModePreferences(volcStandardFileEnabled = true)

        pushedPcmInvocationCases.forEach { invocationCase ->
            val plan = assertPlanMatchesBaseline(AsrVendor.Volc, invocationCase, preferences)

            assertSame(AsrPushPcmEngineFamily.FileAdapter, plan.family)
            assertEquals("GenericPushFileAsrAdapter", plan.engineClassName)
            assertEquals("VolcStandardFileAsrEngine", plan.wrappedRecognizerClassName)
        }
    }

    @Test
    fun localPseudoStreamPlansUsePushPcmPseudoEngines() {
        listOf(
            AsrVendor.SenseVoice to AsrEngineModePreferences(senseVoicePseudoStreamEnabled = true),
            AsrVendor.FireRedAsr to AsrEngineModePreferences(fireRedPseudoStreamEnabled = true)
        ).forEach { (vendor, preferences) ->
            pushedPcmInvocationCases.forEach { invocationCase ->
                val plan = assertPlanMatchesBaseline(vendor, invocationCase, preferences)

                assertSame(AsrPushPcmEngineFamily.PseudoStream, plan.family)
                assertFalse(plan.externalPcmMode)
                assertNull(plan.wrappedRecognizerClassName)
            }
        }
    }

    @Test
    fun progressiveChunkingFollowsResolvedFileAdapters() {
        AsrVendor.entries.forEach { vendor ->
            val plan = factory.resolvePlan(
                vendor = vendor,
                invocationMode = AsrEngineInvocationMode.PushPcm,
                preferences = AsrEngineModePreferences()
            )
            val key = plan.wrappedFileRecognizerKey
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
                AsrEngineInvocationMode.PushPcm,
                AsrEngineModePreferences(senseVoicePseudoStreamEnabled = true)
            ).progressiveChunkingEnabled
        )
        assertFalse(
            factory.resolvePlan(
                AsrVendor.FireRedAsr,
                AsrEngineInvocationMode.PushPcm,
                AsrEngineModePreferences(fireRedPseudoStreamEnabled = true)
            ).progressiveChunkingEnabled
        )
    }

    @Test
    fun xAsrPlansUseLocalExternalPcmStreamWithoutFileAdapter() {
        pushedPcmInvocationCases.forEach { invocationCase ->
            val plan = assertPlanMatchesBaseline(
                vendor = AsrVendor.XAsr,
                invocationCase = invocationCase,
                preferences = AsrEngineModePreferences()
            )

            assertSame(AsrPushPcmEngineFamily.LocalStream, plan.family)
            assertEquals("XAsrStreamAsrEngine", plan.engineClassName)
            assertTrue(plan.externalPcmMode)
            assertNull(plan.wrappedRecognizerClassName)
        }
    }

    @Test
    fun parallelPrimaryAndBackupPlansUseSharedFactoryRulesFromAppSource() {
        val cases = listOf(
            ParallelPlanCase(
                vendor = AsrVendor.Volc,
                preferences = AsrEngineModePreferences(volcStreamingEnabled = true),
                expectedFamily = AsrPushPcmEngineFamily.NativeStream,
                expectedEngineClassName = "VolcStreamAsrEngine"
            ),
            ParallelPlanCase(
                vendor = AsrVendor.SiliconFlow,
                preferences = AsrEngineModePreferences(),
                expectedFamily = AsrPushPcmEngineFamily.FileAdapter,
                expectedEngineClassName = "GenericPushFileAsrAdapter",
                expectedWrappedRecognizerClassName = "SiliconFlowFileAsrEngine"
            ),
            ParallelPlanCase(
                vendor = AsrVendor.SenseVoice,
                preferences = AsrEngineModePreferences(senseVoicePseudoStreamEnabled = true),
                expectedFamily = AsrPushPcmEngineFamily.PseudoStream,
                expectedEngineClassName = "SenseVoicePushPcmPseudoStreamAsrEngine"
            ),
            ParallelPlanCase(
                vendor = AsrVendor.XAsr,
                preferences = AsrEngineModePreferences(),
                expectedFamily = AsrPushPcmEngineFamily.LocalStream,
                expectedEngineClassName = "XAsrStreamAsrEngine"
            )
        )

        cases.forEach { case ->
            listOf(
                AsrEngineInvocationMode.ParallelPrimary,
                AsrEngineInvocationMode.ParallelBackup
            ).forEach { invocationMode ->
                val plan = factory.resolvePlan(
                    vendor = case.vendor,
                    invocationMode = invocationMode,
                    preferences = case.preferences,
                    source = AsrEngineConstructionSource.App
                )

                assertSame(case.vendor, plan.vendor)
                assertSame(invocationMode, plan.resolution.invocationMode)
                assertSame(AsrEngineConstructionSource.App, plan.resolution.source)
                assertSame(case.expectedFamily, plan.family)
                assertEquals(case.expectedEngineClassName, plan.engineClassName)
                assertEquals(
                    case.expectedWrappedRecognizerClassName,
                    plan.wrappedRecognizerClassName
                )
                if (invocationMode == AsrEngineInvocationMode.ParallelPrimary) {
                    assertTrue(plan.resolution.invocationMode.reportsRequestDuration)
                } else {
                    assertFalse(plan.resolution.invocationMode.reportsRequestDuration)
                }
            }
        }
    }

    @Test
    fun plansAreBackedByRegistryCapabilitiesThroughResolver() {
        AsrVendorRegistry.descriptors.forEach { descriptor ->
            val plan = factory.resolvePlan(
                vendor = descriptor.vendor,
                invocationMode = AsrEngineInvocationMode.PushPcm,
                preferences = AsrEngineModePreferences()
            )
            val capabilities = descriptor.capabilities

            assertSame(descriptor.vendor, plan.vendor)
            assertTrue(
                "registry declares Push PCM compatibility for ${descriptor.vendor}",
                AsrVendorCapability.NativePushPcmInput in capabilities ||
                    AsrVendorCapability.PushPcmFileAdapter in capabilities
            )
            if (AsrVendorCapability.PushPcmFileAdapter !in capabilities) {
                assertFalse(plan.resolution.usesPushPcmFileAdapter)
                assertNull(plan.wrappedRecognizerClassName)
            }
            if (plan.resolution.usesPushPcmFileAdapter) {
                assertTrue(AsrVendorCapability.PushPcmFileAdapter in capabilities)
                assertNotNull(plan.wrappedRecognizerClassName)
            }
        }
    }

    @Test
    fun invocationMetadataDoesNotChangeModeChoice() {
        val preferences = AsrEngineModePreferences(openAiStreamingEnabled = true)
        val push = factory.resolvePlan(
            vendor = AsrVendor.OpenAI,
            invocationMode = AsrEngineInvocationMode.PushPcm,
            preferences = preferences
        )
        val recordingTest = factory.resolvePlan(
            vendor = AsrVendor.OpenAI,
            invocationMode = AsrEngineInvocationMode.RecordingTest,
            preferences = preferences
        )
        val backup = factory.resolvePlan(
            vendor = AsrVendor.OpenAI,
            invocationMode = AsrEngineInvocationMode.ParallelBackup,
            preferences = preferences
        )

        assertTrue(push.resolution.invocationMode.reportsRequestDuration)
        assertFalse(recordingTest.resolution.invocationMode.reportsRequestDuration)
        assertFalse(backup.resolution.invocationMode.reportsRequestDuration)
        assertEquals(push.constructorKey, recordingTest.constructorKey)
        assertEquals(push.constructorKey, backup.constructorKey)
        assertEquals(push.wrappedFileRecognizerKey, recordingTest.wrappedFileRecognizerKey)
        assertEquals(push.wrappedFileRecognizerKey, backup.wrappedFileRecognizerKey)
    }

    @Test
    fun directMicrophoneInvocationIsRejected() {
        try {
            factory.resolvePlan(
                vendor = AsrVendor.Volc,
                invocationMode = AsrEngineInvocationMode.DirectMicrophoneCapture,
                preferences = AsrEngineModePreferences()
            )
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("outside Push PCM factory scope"))
            return
        }

        throw AssertionError("Direct microphone invocation should be rejected")
    }

    @Test
    fun everyPushPcmConstructorAndWrappedRecognizerKeyIsCoveredByAtLeastOnePlan() {
        val plannedConstructorKeys = mutableSetOf<AsrPushPcmEngineConstructorKey>()
        val plannedWrappedKeys = mutableSetOf<AsrFileRecognizerKey>()
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
            pushedPcmInvocationCases.forEach { invocationCase ->
                preferenceCases.forEach { preferences ->
                    val plan = factory.resolvePlan(
                        vendor = vendor,
                        invocationMode = invocationCase.invocationMode,
                        preferences = preferences,
                        source = invocationCase.source
                    )
                    plannedConstructorKeys += plan.constructorKey
                    plan.wrappedFileRecognizerKey?.let { plannedWrappedKeys += it }
                }
            }
        }

        assertEquals(AsrPushPcmEngineConstructorKey.entries.toSet(), plannedConstructorKeys)
        assertEquals(AsrFileRecognizerKey.entries.toSet(), plannedWrappedKeys)
    }

    @Test
    fun validationRequiredPushPcmModesUseSharedAvailabilityChecks() {
        pushedPcmInvocationCases.forEach { invocationCase ->
            val configuredOnline = isPushPcmFactoryVendorAvailable(
                vendor = AsrVendor.OpenAI,
                invocationMode = invocationCase.invocationMode,
                checkers = AsrVendorAvailabilityCheckers(
                    onlineConfiguration = { vendor -> vendor == AsrVendor.OpenAI },
                    localModelReadiness = { error("online supplier should not use local readiness") }
                )
            )
            val missingLocalModel = isPushPcmFactoryVendorAvailable(
                vendor = AsrVendor.FunAsrNano,
                invocationMode = invocationCase.invocationMode,
                checkers = AsrVendorAvailabilityCheckers(
                    onlineConfiguration = { error("local supplier should not use online configuration") },
                    localModelReadiness = { false }
                )
            )

            assertTrue("${invocationCase.invocationMode} should allow configured online vendor", configuredOnline)
            assertFalse("${invocationCase.invocationMode} should reject missing local model", missingLocalModel)
        }
    }

    @Test
    fun validationHelperTreatsAvailabilityExceptionsAsUnavailable() {
        val available = isPushPcmFactoryVendorAvailable(
            vendor = AsrVendor.OpenAI,
            checkers = AsrVendorAvailabilityCheckers(
                onlineConfiguration = { error("configuration check failed") },
                localModelReadiness = { error("online supplier should not use local readiness") }
            )
        )

        assertFalse(available)
    }

    @Test
    fun validationHelperRejectsNonPushPcmInvocationMode() {
        try {
            isPushPcmFactoryVendorAvailable(
                vendor = AsrVendor.Volc,
                invocationMode = AsrEngineInvocationMode.DirectMicrophoneCapture,
                checkers = AsrVendorAvailabilityCheckers(
                    onlineConfiguration = { true },
                    localModelReadiness = { true }
                )
            )
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("outside Push PCM factory scope"))
            return
        }

        throw AssertionError("Direct microphone invocation should be rejected")
    }

    private fun assertPlanMatchesBaseline(
        vendor: AsrVendor,
        invocationCase: InvocationCase,
        preferences: AsrEngineModePreferences
    ): AsrPushPcmEnginePlan {
        val plan = factory.resolvePlan(
            vendor = vendor,
            invocationMode = invocationCase.invocationMode,
            preferences = preferences,
            source = invocationCase.source
        )
        val expected = CurrentAsrConstructionBaseline.describe(
            path = invocationCase.baselinePath,
            vendor = vendor,
            settings = preferences.toBaselineSettings(vendor)
        )

        assertSame("vendor for $vendor/${invocationCase.invocationMode}/$preferences", vendor, plan.vendor)
        assertEquals(
            "engine for $vendor/${invocationCase.invocationMode}/$preferences",
            expected.engineClassName,
            plan.engineClassName
        )
        assertEquals(
            "wrapped recognizer for $vendor/${invocationCase.invocationMode}/$preferences",
            expected.wrappedRecognizerClassName,
            plan.wrappedRecognizerClassName
        )
        assertSame(
            "family for $vendor/${invocationCase.invocationMode}/$preferences",
            expected.family.toPushPcmFamily(vendor),
            plan.family
        )
        return plan
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

    private fun CurrentAsrEngineFamily.toPushPcmFamily(vendor: AsrVendor): AsrPushPcmEngineFamily = when (this) {
        CurrentAsrEngineFamily.PushPcmAdapter -> AsrPushPcmEngineFamily.FileAdapter
        CurrentAsrEngineFamily.PushPcmPseudoStream -> AsrPushPcmEngineFamily.PseudoStream
        CurrentAsrEngineFamily.PushPcmStream -> if (vendor == AsrVendor.XAsr) {
            AsrPushPcmEngineFamily.LocalStream
        } else {
            AsrPushPcmEngineFamily.NativeStream
        }
        CurrentAsrEngineFamily.File,
        CurrentAsrEngineFamily.LocalFile,
        CurrentAsrEngineFamily.LocalPseudoStream,
        CurrentAsrEngineFamily.Parallel,
        CurrentAsrEngineFamily.Stream -> error("$this is not a Push PCM family")
    }

    private fun streamingPreferencesFor(vendor: AsrVendor): AsrEngineModePreferences = when (vendor) {
        AsrVendor.Volc -> AsrEngineModePreferences(volcStreamingEnabled = true)
        AsrVendor.ElevenLabs -> AsrEngineModePreferences(elevenStreamingEnabled = true)
        AsrVendor.OpenAI -> AsrEngineModePreferences(openAiStreamingEnabled = true)
        AsrVendor.DashScope -> AsrEngineModePreferences(dashScopeStreamingEnabled = true)
        AsrVendor.Soniox -> AsrEngineModePreferences(sonioxStreamingEnabled = true)
        else -> error("$vendor is not controlled by a streaming preference")
    }

    private data class InvocationCase(
        val invocationMode: AsrEngineInvocationMode,
        val source: AsrEngineConstructionSource,
        val baselinePath: CurrentAsrConstructionPath
    )

    private data class ParallelPlanCase(
        val vendor: AsrVendor,
        val preferences: AsrEngineModePreferences,
        val expectedFamily: AsrPushPcmEngineFamily,
        val expectedEngineClassName: String,
        val expectedWrappedRecognizerClassName: String? = null
    )

    private companion object {
        private val pushedPcmInvocationCases = listOf(
            InvocationCase(
                invocationMode = AsrEngineInvocationMode.PushPcm,
                source = AsrEngineConstructionSource.ExternalIntegration,
                baselinePath = CurrentAsrConstructionPath.ExternalPushPcm
            ),
            InvocationCase(
                invocationMode = AsrEngineInvocationMode.RecordingTest,
                source = AsrEngineConstructionSource.App,
                baselinePath = CurrentAsrConstructionPath.RecordingTestPushPcm
            ),
            InvocationCase(
                invocationMode = AsrEngineInvocationMode.ParallelPrimary,
                source = AsrEngineConstructionSource.App,
                baselinePath = CurrentAsrConstructionPath.ParallelDirectLeg
            ),
            InvocationCase(
                invocationMode = AsrEngineInvocationMode.ParallelBackup,
                source = AsrEngineConstructionSource.App,
                baselinePath = CurrentAsrConstructionPath.ParallelPushPcmLeg
            )
        )

        private val streamingVendors = AsrVendorRegistry.descriptors
            .filter {
                AsrVendorCapability.StreamingRecognition in it.capabilities &&
                    AsrVendorCapability.LocalRecognition !in it.capabilities
            }
            .map { it.vendor }
    }
}
