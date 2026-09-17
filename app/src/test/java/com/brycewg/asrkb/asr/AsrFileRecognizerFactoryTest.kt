// Tests the shared file-recognizer construction seam used by direct and Push PCM factories.
package com.brycewg.asrkb.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrFileRecognizerFactoryTest {
    private val directFactory = AsrDirectMicrophoneEngineFactory()
    private val pushPcmFactory = AsrPushPcmEngineFactory()

    @Test
    fun sharedFileRecognizerKeysCoverEveryCurrentFileRecognizer() {
        val expectedEngineClassNames = buildSet {
            baselineCases.forEach { case ->
                CurrentAsrConstructionPath.entries.forEach { path ->
                    val use = CurrentAsrConstructionBaseline.describe(
                        path = path,
                        vendor = case.vendor,
                        settings = case.settings
                    )
                    when (use.family) {
                        CurrentAsrEngineFamily.File,
                        CurrentAsrEngineFamily.LocalFile -> add(use.engineClassName)
                        CurrentAsrEngineFamily.PushPcmAdapter ->
                            add(requireNotNull(use.wrappedRecognizerClassName))
                        CurrentAsrEngineFamily.LocalPseudoStream,
                        CurrentAsrEngineFamily.Parallel,
                        CurrentAsrEngineFamily.PushPcmPseudoStream,
                        CurrentAsrEngineFamily.PushPcmStream,
                        CurrentAsrEngineFamily.Stream -> Unit
                    }
                }
            }
        }
        val sharedEngineClassNames = AsrFileRecognizerKey.entries
            .map { it.engineClassName }
            .toSet()

        assertEquals(expectedEngineClassNames, sharedEngineClassNames)
    }

    @Test
    fun fileRecognizerKeysEnableProgressiveChunkingWithFamilyWindows() {
        AsrFileRecognizerKey.entries.forEach { key ->
            assertTrue(key.progressiveChunkingEnabled)
            val expected = if (key.family == AsrFileRecognizerFamily.LocalFile) {
                NonStreamingChunkWindow.Local
            } else {
                NonStreamingChunkWindow.Online
            }
            assertEquals(expected, key.family.progressiveChunkWindow())
        }
    }

    @Test
    fun directAndPushPcmAppFilePlansUseTheSameSharedRecognizerKeys() {
        baselineCases.forEach { case ->
            val directPlan = directFactory.resolvePlan(
                vendor = case.vendor,
                preferences = case.preferences,
                source = AsrEngineConstructionSource.App
            )
            val pushPlan = pushPcmFactory.resolvePlan(
                vendor = case.vendor,
                invocationMode = AsrEngineInvocationMode.RecordingTest,
                preferences = case.preferences,
                source = AsrEngineConstructionSource.App
            )

            if (directPlan.fileRecognizerKey == null) {
                assertNull(
                    "non-file direct plan should not wrap file recognizer for ${case.label}",
                    pushPlan.wrappedFileRecognizerKey
                )
            } else {
                assertSame(
                    "direct and Push PCM file plans should share recognizer key for ${case.label}",
                    directPlan.fileRecognizerKey,
                    pushPlan.wrappedFileRecognizerKey
                )
                assertEquals(directPlan.engineClassName, pushPlan.wrappedRecognizerClassName)
            }
        }
    }

    @Test
    fun externalDirectVolcStandardStillUsesLegacyFileWhilePushPcmUsesStandardFile() {
        val preferences = AsrEngineModePreferences(volcStandardFileEnabled = true)
        val externalDirect = directFactory.resolvePlan(
            vendor = AsrVendor.Volc,
            preferences = preferences,
            source = AsrEngineConstructionSource.ExternalIntegration
        )
        val externalPush = pushPcmFactory.resolvePlan(
            vendor = AsrVendor.Volc,
            invocationMode = AsrEngineInvocationMode.PushPcm,
            preferences = preferences,
            source = AsrEngineConstructionSource.ExternalIntegration
        )

        assertSame(AsrFileRecognizerKey.VolcFile, externalDirect.fileRecognizerKey)
        assertSame(AsrFileRecognizerKey.VolcStandardFile, externalPush.wrappedFileRecognizerKey)
    }

    @Test
    fun streamAndPseudoStreamPlansStayOutsideFileRecognizerSeam() {
        val cases = listOf(
            AsrVendor.Volc to AsrEngineModePreferences(volcStreamingEnabled = true),
            AsrVendor.ElevenLabs to AsrEngineModePreferences(elevenStreamingEnabled = true),
            AsrVendor.OpenAI to AsrEngineModePreferences(openAiStreamingEnabled = true),
            AsrVendor.DashScope to AsrEngineModePreferences(dashScopeStreamingEnabled = true),
            AsrVendor.Soniox to AsrEngineModePreferences(sonioxStreamingEnabled = true),
            AsrVendor.SenseVoice to AsrEngineModePreferences(senseVoicePseudoStreamEnabled = true),
            AsrVendor.FireRedAsr to AsrEngineModePreferences(fireRedPseudoStreamEnabled = true),
            AsrVendor.XAsr to AsrEngineModePreferences()
        )

        cases.forEach { (vendor, preferences) ->
            val directPlan = directFactory.resolvePlan(vendor, preferences)
            val pushPlan = pushPcmFactory.resolvePlan(
                vendor = vendor,
                invocationMode = AsrEngineInvocationMode.PushPcm,
                preferences = preferences
            )

            assertNull("direct non-file plan should not have file key for $vendor", directPlan.fileRecognizerKey)
            assertNull("Push PCM non-file plan should not have wrapped file key for $vendor", pushPlan.wrappedFileRecognizerKey)
        }
    }

    @Test
    fun directAndPushPcmFactoriesDoNotOwnFileRecognizerConstructorTables() {
        val directSource = projectFile(
            "app/src/main/java/com/brycewg/asrkb/asr/AsrDirectMicrophoneEngineFactory.kt"
        ).readText()
        val pushPcmSource = projectFile(
            "app/src/main/java/com/brycewg/asrkb/asr/AsrPushPcmEngineFactory.kt"
        ).readText()
        val sharedSource = projectFile(
            "app/src/main/java/com/brycewg/asrkb/asr/AsrFileRecognizerFactory.kt"
        ).readText()

        AsrFileRecognizerKey.entries.forEach { key ->
            val constructorCall = "${key.engineClassName}("
            assertFalse(
                "direct factory should delegate $constructorCall to shared file recognizer seam",
                directSource.contains(constructorCall)
            )
            assertFalse(
                "Push PCM factory should delegate $constructorCall to shared file recognizer seam",
                pushPcmSource.contains(constructorCall)
            )
            assertTrue(
                "shared file recognizer seam should construct $constructorCall",
                sharedSource.contains(constructorCall)
            )
        }
        assertTrue(directSource.contains("RealAsrFileRecognizerConstructorTable.createStreamingEngine("))
        assertTrue(pushPcmSource.contains("RealAsrFileRecognizerConstructorTable.create("))
    }

    @Test
    fun everySharedFileRecognizerKeyIsReachedByDirectAndPushPcmPlans() {
        val directKeys = mutableSetOf<AsrFileRecognizerKey>()
        val pushKeys = mutableSetOf<AsrFileRecognizerKey>()

        baselineCases.forEach { case ->
            directFactory.resolvePlan(
                vendor = case.vendor,
                preferences = case.preferences,
                source = AsrEngineConstructionSource.App
            ).fileRecognizerKey?.let { directKeys += it }
            directFactory.resolvePlan(
                vendor = case.vendor,
                preferences = case.preferences,
                source = AsrEngineConstructionSource.ExternalIntegration
            ).fileRecognizerKey?.let { directKeys += it }
            pushPcmFactory.resolvePlan(
                vendor = case.vendor,
                invocationMode = AsrEngineInvocationMode.PushPcm,
                preferences = case.preferences
            ).wrappedFileRecognizerKey?.let { pushKeys += it }
        }

        assertEquals(AsrFileRecognizerKey.entries.toSet(), directKeys)
        assertEquals(AsrFileRecognizerKey.entries.toSet(), pushKeys)
    }

    private data class BaselineCase(
        val vendor: AsrVendor,
        val label: String,
        val preferences: AsrEngineModePreferences,
        val settings: CurrentAsrConstructionSettings
    )

    private companion object {
        private val baselineCases = listOf(
            BaselineCase(
                AsrVendor.Volc,
                "Volc streaming",
                AsrEngineModePreferences(volcStreamingEnabled = true),
                CurrentAsrConstructionSettings(streamingEnabled = true)
            ),
            BaselineCase(
                AsrVendor.Volc,
                "Volc legacy file",
                AsrEngineModePreferences(),
                CurrentAsrConstructionSettings()
            ),
            BaselineCase(
                AsrVendor.Volc,
                "Volc standard file",
                AsrEngineModePreferences(volcStandardFileEnabled = true),
                CurrentAsrConstructionSettings(volcStandardFileEnabled = true)
            ),
            streamingCase(AsrVendor.ElevenLabs, AsrEngineModePreferences(elevenStreamingEnabled = true)),
            fileCase(AsrVendor.ElevenLabs),
            streamingCase(AsrVendor.OpenAI, AsrEngineModePreferences(openAiStreamingEnabled = true)),
            fileCase(AsrVendor.OpenAI),
            streamingCase(AsrVendor.DashScope, AsrEngineModePreferences(dashScopeStreamingEnabled = true)),
            fileCase(AsrVendor.DashScope),
            streamingCase(AsrVendor.Soniox, AsrEngineModePreferences(sonioxStreamingEnabled = true)),
            fileCase(AsrVendor.Soniox),
            fileCase(AsrVendor.SiliconFlow),
            fileCase(AsrVendor.OpenRouter),
            fileCase(AsrVendor.Gemini),
            fileCase(AsrVendor.MiMo),
            fileCase(AsrVendor.StepAudio),
            fileCase(AsrVendor.Zhipu),
            fileCase(AsrVendor.Cohere),
            fileCase(AsrVendor.FunAsrNano),
            fileCase(AsrVendor.Qwen3Asr),
            fileCase(AsrVendor.Parakeet),
            fileCase(AsrVendor.SenseVoice),
            BaselineCase(
                AsrVendor.SenseVoice,
                "SenseVoice pseudo stream",
                AsrEngineModePreferences(senseVoicePseudoStreamEnabled = true),
                CurrentAsrConstructionSettings(pseudoStreamEnabled = true)
            ),
            fileCase(AsrVendor.FireRedAsr),
            BaselineCase(
                AsrVendor.FireRedAsr,
                "FireRedAsr pseudo stream",
                AsrEngineModePreferences(fireRedPseudoStreamEnabled = true),
                CurrentAsrConstructionSettings(pseudoStreamEnabled = true)
            ),
            streamingCase(AsrVendor.XAsr, AsrEngineModePreferences())
        )

        private fun streamingCase(
            vendor: AsrVendor,
            preferences: AsrEngineModePreferences
        ): BaselineCase = BaselineCase(
            vendor = vendor,
            label = "$vendor streaming",
            preferences = preferences,
            settings = CurrentAsrConstructionSettings(streamingEnabled = true)
        )

        private fun fileCase(vendor: AsrVendor): BaselineCase = BaselineCase(
            vendor = vendor,
            label = "$vendor file",
            preferences = AsrEngineModePreferences(),
            settings = CurrentAsrConstructionSettings()
        )

        private fun projectFile(path: String): File {
            val userDir = System.getProperty("user.dir") ?: "."
            var dir = File(userDir).absoluteFile
            while (!File(dir, "settings.gradle.kts").exists()) {
                val parent = dir.parentFile
                    ?: error("Could not find repository root from $userDir")
                dir = parent
            }
            return File(dir, path)
        }
    }
}
