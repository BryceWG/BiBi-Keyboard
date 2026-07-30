// ASR supplier construction golden baseline for the vendor architecture refactor.
package com.brycewg.asrkb.asr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrConstructionBaselineTest {
    @Test
    fun baselineCoversEverySupportedVendor() {
        val covered = baselineRows.map { it.vendor }.toSet()

        assertEquals(AsrVendor.entries.toSet(), covered)
    }

    @Test
    fun completeGoldenMatrixMatchesCurrentProductionDescriptor() {
        val expected = """
            Volc[streaming=true] | app=Stream:VolcStreamAsrEngine | speech=Stream:VolcStreamAsrEngine | external=Stream:VolcStreamAsrEngine | externalPush=PushPcmStream:VolcStreamAsrEngine | recordingPush=PushPcmStream:VolcStreamAsrEngine | recordingMode=PushPcm | parallelDirect=PushPcmStream:VolcStreamAsrEngine | parallelPush=PushPcmStream:VolcStreamAsrEngine
            Volc[streaming=false,standardFile=false] | app=File:VolcFileAsrEngine | speech=File:VolcFileAsrEngine | external=File:VolcFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(VolcFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(VolcFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(VolcFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(VolcFileAsrEngine)
            Volc[streaming=false,standardFile=true] | app=File:VolcStandardFileAsrEngine | speech=File:VolcStandardFileAsrEngine | external=File:VolcFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(VolcStandardFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(VolcStandardFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(VolcStandardFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(VolcStandardFileAsrEngine)
            ElevenLabs[streaming=true] | app=Stream:ElevenLabsStreamAsrEngine | speech=Stream:ElevenLabsStreamAsrEngine | external=Stream:ElevenLabsStreamAsrEngine | externalPush=PushPcmStream:ElevenLabsStreamAsrEngine | recordingPush=PushPcmStream:ElevenLabsStreamAsrEngine | recordingMode=PushPcm | parallelDirect=PushPcmStream:ElevenLabsStreamAsrEngine | parallelPush=PushPcmStream:ElevenLabsStreamAsrEngine
            ElevenLabs[streaming=false] | app=File:ElevenLabsFileAsrEngine | speech=File:ElevenLabsFileAsrEngine | external=File:ElevenLabsFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(ElevenLabsFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(ElevenLabsFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(ElevenLabsFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(ElevenLabsFileAsrEngine)
            OpenAI[streaming=true] | app=Stream:OpenAiRealtimeAsrEngine | speech=Stream:OpenAiRealtimeAsrEngine | external=Stream:OpenAiRealtimeAsrEngine | externalPush=PushPcmStream:OpenAiRealtimeAsrEngine | recordingPush=PushPcmStream:OpenAiRealtimeAsrEngine | recordingMode=PushPcm | parallelDirect=PushPcmStream:OpenAiRealtimeAsrEngine | parallelPush=PushPcmStream:OpenAiRealtimeAsrEngine
            OpenAI[streaming=false] | app=File:OpenAiFileAsrEngine | speech=File:OpenAiFileAsrEngine | external=File:OpenAiFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(OpenAiFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(OpenAiFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(OpenAiFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(OpenAiFileAsrEngine)
            DashScope[streaming=true] | app=Stream:DashscopeStreamAsrEngine | speech=Stream:DashscopeStreamAsrEngine | external=Stream:DashscopeStreamAsrEngine | externalPush=PushPcmStream:DashscopeStreamAsrEngine | recordingPush=PushPcmStream:DashscopeStreamAsrEngine | recordingMode=PushPcm | parallelDirect=PushPcmStream:DashscopeStreamAsrEngine | parallelPush=PushPcmStream:DashscopeStreamAsrEngine
            DashScope[streaming=false] | app=File:DashscopeFileAsrEngine | speech=File:DashscopeFileAsrEngine | external=File:DashscopeFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(DashscopeFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(DashscopeFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(DashscopeFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(DashscopeFileAsrEngine)
            Soniox[streaming=true] | app=Stream:SonioxStreamAsrEngine | speech=Stream:SonioxStreamAsrEngine | external=Stream:SonioxStreamAsrEngine | externalPush=PushPcmStream:SonioxStreamAsrEngine | recordingPush=PushPcmStream:SonioxStreamAsrEngine | recordingMode=PushPcm | parallelDirect=PushPcmStream:SonioxStreamAsrEngine | parallelPush=PushPcmStream:SonioxStreamAsrEngine
            Soniox[streaming=false] | app=File:SonioxFileAsrEngine | speech=File:SonioxFileAsrEngine | external=File:SonioxFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(SonioxFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(SonioxFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(SonioxFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(SonioxFileAsrEngine)
            SiliconFlow[fileOnly] | app=File:SiliconFlowFileAsrEngine | speech=File:SiliconFlowFileAsrEngine | external=File:SiliconFlowFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(SiliconFlowFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(SiliconFlowFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(SiliconFlowFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(SiliconFlowFileAsrEngine)
            OpenRouter[fileOnly] | app=File:OpenRouterFileAsrEngine | speech=File:OpenRouterFileAsrEngine | external=File:OpenRouterFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(OpenRouterFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(OpenRouterFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(OpenRouterFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(OpenRouterFileAsrEngine)
            Gemini[fileOnly] | app=File:GeminiFileAsrEngine | speech=File:GeminiFileAsrEngine | external=File:GeminiFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(GeminiFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(GeminiFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(GeminiFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(GeminiFileAsrEngine)
            MiMo[fileOnly] | app=File:MiMoFileAsrEngine | speech=File:MiMoFileAsrEngine | external=File:MiMoFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(MiMoFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(MiMoFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(MiMoFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(MiMoFileAsrEngine)
            StepAudio[fileOnly] | app=File:StepAudioFileAsrEngine | speech=File:StepAudioFileAsrEngine | external=File:StepAudioFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(StepAudioFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(StepAudioFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(StepAudioFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(StepAudioFileAsrEngine)
            Zhipu[fileOnly] | app=File:ZhipuFileAsrEngine | speech=File:ZhipuFileAsrEngine | external=File:ZhipuFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(ZhipuFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(ZhipuFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(ZhipuFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(ZhipuFileAsrEngine)
            Cohere[fileOnly] | app=File:CohereFileAsrEngine | speech=File:CohereFileAsrEngine | external=File:CohereFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(CohereFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(CohereFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(CohereFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(CohereFileAsrEngine)
            FunAsrNano[fileOnly] | app=LocalFile:FunAsrNanoFileAsrEngine | speech=LocalFile:FunAsrNanoFileAsrEngine | external=LocalFile:FunAsrNanoFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(FunAsrNanoFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(FunAsrNanoFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(FunAsrNanoFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(FunAsrNanoFileAsrEngine)
            Qwen3Asr[fileOnly] | app=LocalFile:Qwen3AsrFileAsrEngine | speech=LocalFile:Qwen3AsrFileAsrEngine | external=LocalFile:Qwen3AsrFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(Qwen3AsrFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(Qwen3AsrFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(Qwen3AsrFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(Qwen3AsrFileAsrEngine)
            Parakeet[fileOnly] | app=LocalFile:ParakeetFileAsrEngine | speech=LocalFile:ParakeetFileAsrEngine | external=LocalFile:ParakeetFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(ParakeetFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(ParakeetFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(ParakeetFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(ParakeetFileAsrEngine)
            SenseVoice[pseudoStream=false] | app=LocalFile:SenseVoiceFileAsrEngine | speech=LocalFile:SenseVoiceFileAsrEngine | external=LocalFile:SenseVoiceFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(SenseVoiceFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(SenseVoiceFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(SenseVoiceFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(SenseVoiceFileAsrEngine)
            SenseVoice[pseudoStream=true] | app=LocalPseudoStream:SenseVoicePseudoStreamAsrEngine | speech=LocalPseudoStream:SenseVoicePseudoStreamAsrEngine | external=LocalFile:SenseVoiceFileAsrEngine | externalPush=PushPcmPseudoStream:SenseVoicePushPcmPseudoStreamAsrEngine | recordingPush=PushPcmPseudoStream:SenseVoicePushPcmPseudoStreamAsrEngine | recordingMode=PushPcm | parallelDirect=PushPcmPseudoStream:SenseVoicePushPcmPseudoStreamAsrEngine | parallelPush=PushPcmPseudoStream:SenseVoicePushPcmPseudoStreamAsrEngine
            FireRedAsr[pseudoStream=false] | app=LocalFile:FireRedAsrFileAsrEngine | speech=LocalFile:FireRedAsrFileAsrEngine | external=LocalFile:FireRedAsrFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(FireRedAsrFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(FireRedAsrFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(FireRedAsrFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(FireRedAsrFileAsrEngine)
            FireRedAsr[pseudoStream=true] | app=LocalPseudoStream:FireRedAsrPseudoStreamAsrEngine | speech=LocalPseudoStream:FireRedAsrPseudoStreamAsrEngine | external=LocalFile:FireRedAsrFileAsrEngine | externalPush=PushPcmPseudoStream:FireRedAsrPushPcmPseudoStreamAsrEngine | recordingPush=PushPcmPseudoStream:FireRedAsrPushPcmPseudoStreamAsrEngine | recordingMode=PushPcm | parallelDirect=PushPcmPseudoStream:FireRedAsrPushPcmPseudoStreamAsrEngine | parallelPush=PushPcmPseudoStream:FireRedAsrPushPcmPseudoStreamAsrEngine
            XAsr[alwaysStreaming] | app=Stream:XAsrStreamAsrEngine | speech=Stream:XAsrStreamAsrEngine | external=Stream:XAsrStreamAsrEngine | externalPush=PushPcmStream:XAsrStreamAsrEngine | recordingPush=PushPcmStream:XAsrStreamAsrEngine | recordingMode=PushPcm | parallelDirect=PushPcmStream:XAsrStreamAsrEngine | parallelPush=PushPcmStream:XAsrStreamAsrEngine
            Tencent[streaming=true] | app=Stream:TencentStreamAsrEngine | speech=Stream:TencentStreamAsrEngine | external=Stream:TencentStreamAsrEngine | externalPush=PushPcmStream:TencentStreamAsrEngine | recordingPush=PushPcmStream:TencentStreamAsrEngine | recordingMode=PushPcm | parallelDirect=PushPcmStream:TencentStreamAsrEngine | parallelPush=PushPcmStream:TencentStreamAsrEngine
            Tencent[streaming=false] | app=File:TencentFileAsrEngine | speech=File:TencentFileAsrEngine | external=File:TencentFileAsrEngine | externalPush=PushPcmAdapter:GenericPushFileAsrAdapter(TencentFileAsrEngine) | recordingPush=PushPcmAdapter:GenericPushFileAsrAdapter(TencentFileAsrEngine) | recordingMode=File | parallelDirect=PushPcmAdapter:GenericPushFileAsrAdapter(TencentFileAsrEngine) | parallelPush=PushPcmAdapter:GenericPushFileAsrAdapter(TencentFileAsrEngine)
        """.trimIndent()

        assertEquals(expected, renderGoldenMatrix())
    }

    @Test
    fun productionConstructionSourcesStillContainBaselineEnginesForEachPath() {
        val fileRecognizerSource = sourceSlice(
            "app/src/main/java/com/brycewg/asrkb/asr/AsrFileRecognizerFactory.kt",
            "internal object RealAsrFileRecognizerConstructorTable",
            end = null
        )
        val sourceByPath = mapOf(
            CurrentAsrConstructionPath.AppDirectMicrophone to sourceSlice(
                "app/src/main/java/com/brycewg/asrkb/asr/AsrDirectMicrophoneEngineFactory.kt",
                "internal object RealAsrDirectMicrophoneEngineConstructorTable",
                end = null
            ) + fileRecognizerSource,
            CurrentAsrConstructionPath.SpeechRecognizerDirectMicrophone to sourceSlice(
                "app/src/main/java/com/brycewg/asrkb/asr/AsrDirectMicrophoneEngineFactory.kt",
                "internal object RealAsrDirectMicrophoneEngineConstructorTable",
                end = null
            ) + fileRecognizerSource,
            CurrentAsrConstructionPath.ExternalDirectMicrophone to sourceSlice(
                "app/src/main/java/com/brycewg/asrkb/asr/AsrDirectMicrophoneEngineFactory.kt",
                "internal object RealAsrDirectMicrophoneEngineConstructorTable",
                end = null
            ) + fileRecognizerSource,
            CurrentAsrConstructionPath.ExternalPushPcm to sourceSlice(
                "app/src/main/java/com/brycewg/asrkb/asr/AsrPushPcmEngineFactory.kt",
                "internal object RealAsrPushPcmEngineConstructorTable",
                end = null
            ) + fileRecognizerSource,
            CurrentAsrConstructionPath.RecordingTestPushPcm to sourceSlice(
                "app/src/main/java/com/brycewg/asrkb/asr/AsrPushPcmEngineFactory.kt",
                "internal object RealAsrPushPcmEngineConstructorTable",
                end = null
            ) + fileRecognizerSource,
            CurrentAsrConstructionPath.ParallelDirectLeg to sourceSlice(
                "app/src/main/java/com/brycewg/asrkb/asr/AsrPushPcmEngineFactory.kt",
                "internal object RealAsrPushPcmEngineConstructorTable",
                end = null
            ) + fileRecognizerSource,
            CurrentAsrConstructionPath.ParallelPushPcmLeg to sourceSlice(
                "app/src/main/java/com/brycewg/asrkb/asr/AsrPushPcmEngineFactory.kt",
                "internal object RealAsrPushPcmEngineConstructorTable",
                end = null
            ) + fileRecognizerSource
        )

        CurrentAsrConstructionPath.entries.forEach { path ->
            val source = sourceByPath.getValue(path)
            val expectedClasses = baselineRows
                .map { it.useFor(path) }
                .flatMap { listOfNotNull(it.engineClassName, it.wrappedRecognizerClassName) }
                .toSet()

            expectedClasses.forEach { className ->
                assertTrue(
                    "Expected $className constructor/reference in production source for $path",
                    source.contains(className)
                )
            }
        }
    }

    @Test
    fun parallelEngineInternalsDelegateLegConstructionToSharedPushPcmFactory() {
        val source = projectFile(
            "app/src/main/java/com/brycewg/asrkb/asr/ParallelAsrEngine.kt"
        ).readText()

        assertTrue(source.contains("pushPcmEngineFactory.create("))
        assertTrue(source.contains("AsrEngineInvocationMode.ParallelPrimary"))
        assertTrue(source.contains("AsrEngineInvocationMode.ParallelBackup"))
        assertTrue(source.contains("source = AsrEngineConstructionSource.App"))
        assertTrue(source.contains("pushPcmEngineFactory.resolvePlan("))
        assertTrue(source.contains("AsrPushPcmEngineFamily.NativeStream"))
        assertTrue(source.contains("AsrPushPcmEngineFamily.LocalStream"))
        assertTrue(source.contains("isAsrVendorConfigured(context, prefs, vendor)"))
        assertFalse(source.contains("private fun wrapPushFileEngine("))
        assertFalse(source.contains("private fun isPrimaryStreamingForSwitch("))
        assertFalse(source.contains("prefs.hasVendorKeys(vendor)"))
        assertFalse(source.contains("prefs.hasSfKeys()"))
        assertFalse(source.contains("VolcStreamAsrEngine("))
        assertFalse(source.contains("GenericPushFileAsrAdapter("))
    }

    @Test
    fun recordingTestDelegatesPushPcmConstructionToSharedFactories() {
        val source = projectFile(
            "app/src/main/java/com/brycewg/asrkb/ui/settings/compose/screens/RecordingTestViewModel.kt"
        ).readText()

        assertTrue(source.contains("parallelEngineFactory.createOrNull("))
        assertTrue(source.contains("externalPcmInput = true"))
        assertTrue(source.contains("pushPcmEngineFactory.create("))
        assertTrue(source.contains("isAsrVendorConfigured(appContext, prefs, primaryVendor)"))
        assertTrue(source.contains("AsrEngineInvocationMode.RecordingTest"))
        assertTrue(source.contains("source = AsrEngineConstructionSource.App"))
        assertTrue(source.contains("pushPcmEngineFactory.resolvePlan("))
        assertTrue(source.contains("AsrPushPcmEngineFamily.FileAdapter -> false"))
        assertTrue(source.contains("AsrPushPcmEngineFamily.PseudoStream -> true"))
        assertFalse(source.contains("private fun buildSinglePushPcmEngine("))
        assertFalse(source.contains("private fun wrapPushFileEngine("))
        assertFalse(source.contains("private fun isPushPcmMode("))
        listOf(
            "prefs.volcStreamingEnabled",
            "prefs.isDashStreamingModelSelected()",
            "prefs.sonioxStreamingEnabled",
            "prefs.elevenStreamingEnabled",
            "prefs.isOpenAiStreamingEffective()",
            "prefs.svPseudoStreamEnabled",
            "prefs.frPseudoStreamEnabled"
        ).forEach { forbidden ->
            assertFalse(
                "Recording test mode should use the shared Push PCM plan instead of $forbidden",
                source.contains(forbidden)
            )
        }
        assertFalse(source.contains("VolcStreamAsrEngine("))
        assertFalse(source.contains("GenericPushFileAsrAdapter("))
    }

    @Test
    fun speechRecognizerDelegatesConstructionToSharedFactories() {
        val source = projectFile(
            "app/src/main/java/com/brycewg/asrkb/api/AsrRecognitionService.kt"
        ).readText()

        assertTrue(source.contains("parallelEngineFactory.createOrNull("))
        assertTrue(source.contains("directMicrophoneEngineFactory.createOrNull("))
        assertFalse(source.contains("directMicrophoneEngineFactory.create("))
        assertTrue(source.contains("preferences = prefs.asrEngineModePreferencesSnapshot()"))
        assertTrue(source.contains("source = AsrEngineConstructionSource.SpeechRecognizer"))
        assertTrue(source.contains("localModelReadyWaitMs.getAndSet(LOCAL_MODEL_READY_WAIT_CONSUMED)"))
        assertTrue(source.contains("localModelReadyWaitMs.compareAndSet(0L, (readyAt - startMs).coerceAtLeast(0L))"))
        assertFalse(source.contains("private fun resolveStreamingBySettings("))
        assertFalse(source.contains("shouldUseBackupAsr("))
        assertFalse(source.contains("ParallelAsrEngine("))
        assertFalse(source.contains("VolcStreamAsrEngine("))
        assertFalse(source.contains("GenericPushFileAsrAdapter("))
        assertMarkerOrder(
            source,
            "parallelEngineFactory.createOrNull(",
            "directMicrophoneEngineFactory.createOrNull("
        )
    }

    @Test
    fun externalSpeechSessionDelegatesConstructionToSharedFactories() {
        val source = projectFile(
            "app/src/main/java/com/brycewg/asrkb/api/ExternalSpeechSession.kt"
        ).readText()

        assertTrue(source.contains("parallelEngineFactory.createOrNull("))
        assertTrue(source.contains("directMicrophoneEngineFactory.createOrNull("))
        assertFalse(source.contains("directMicrophoneEngineFactory.create("))
        assertTrue(source.contains("pushPcmEngineFactory.createOrNull("))
        assertFalse(source.contains(") ?: pushPcmEngineFactory.create("))
        assertTrue(source.contains("externalPcmInput = false"))
        assertTrue(source.contains("externalPcmInput = true"))
        assertTrue(source.contains("invocationMode = AsrEngineInvocationMode.PushPcm"))
        assertTrue(source.contains("preferences = prefs.asrEngineModePreferencesSnapshot()"))
        assertTrue(source.contains("source = AsrEngineConstructionSource.ExternalIntegration"))
        assertTrue(source.contains("onPrimaryRequestDuration = ::onRequestDuration"))
        assertTrue(source.contains("onRequestDuration = ::onRequestDuration"))
        assertFalse(source.contains("private fun resolveStreamingBySettings("))
        assertFalse(source.contains("private fun buildEngine("))
        assertFalse(source.contains("private fun buildPushPcmEngine("))
        assertFalse(source.contains("shouldUseBackupAsr("))
        assertFalse(source.contains("ParallelAsrEngine("))
        assertFalse(source.contains("VolcStreamAsrEngine("))
        assertFalse(source.contains("GenericPushFileAsrAdapter("))
        assertMarkerOrder(
            source,
            "parallelEngineFactory.createOrNull(",
            "directMicrophoneEngineFactory.createOrNull("
        )
        assertMarkerOrder(
            source,
            "externalPcmInput = true",
            "pushPcmEngineFactory.createOrNull("
        )
    }

    @Test
    fun keyboardRecordingDelegatesConstructionToSharedFactories() {
        val source = projectFile(
            "app/src/main/java/com/brycewg/asrkb/ime/AsrSessionManager.kt"
        ).readText()

        assertTrue(source.contains("parallelEngineFactory.createOrNull("))
        assertTrue(source.contains("directMicrophoneEngineFactory.create("))
        assertFalse(source.contains("directMicrophoneEngineFactory.createOrNull("))
        assertTrue(source.contains("parallelEngineFactory.resolvePlan("))
        assertTrue(source.contains("directMicrophoneEngineFactory.resolvePlan("))
        assertTrue(source.contains("preferences = prefs.asrEngineModePreferencesSnapshot()"))
        assertTrue(source.contains("source = AsrEngineConstructionSource.App"))
        assertTrue(source.contains("onPrimaryRequestDuration = requestDurationCallback"))
        assertTrue(source.contains("onRequestDuration = requestDurationCallback"))
        assertTrue(source.contains("onRequestDuration(engineListener.currentSessionSeq(), ms)"))
        assertTrue(source.contains("engineListenerBridge?.bindPrewarmedSession(targetSessionSeq)"))
        assertTrue(source.contains("engineSessionSeq = targetSessionSeq"))
        assertTrue(source.contains("directEngineIdentity = built?.directIdentity"))
        assertTrue(source.contains("directIdentity == plan.identity"))
        assertTrue(source.contains("isLocalAsrVendor(vendor) -> true"))
        assertTrue(source.contains("vendor == AsrVendor.SiliconFlow -> prefs.hasSfKeys()"))
        assertTrue(source.contains("本地供应商保持可构造"))
        assertMarkerOrder(
            source,
            "if (!isPrimaryVendorConstructible(primaryVendor)) return null",
            "directMicrophoneEngineFactory.create("
        )
        assertFalse(source.contains("engine.javaClass.simpleName == plan.engineClassName"))
        assertFalse(source.contains("shouldUseBackupAsr("))
        assertFalse(source.contains("ParallelAsrEngine("))
        assertFalse(source.contains("VolcStreamAsrEngine("))
        assertFalse(source.contains("GenericPushFileAsrAdapter("))
    }

    @Test
    fun floatingBallRecordingDelegatesConstructionToSharedFactories() {
        val source = projectFile(
            "app/src/main/java/com/brycewg/asrkb/ui/floatingball/AsrSessionManager.kt"
        ).readText()

        assertTrue(source.contains("parallelEngineFactory.createOrNull("))
        assertTrue(source.contains("directMicrophoneEngineFactory.create("))
        assertFalse(source.contains("directMicrophoneEngineFactory.createOrNull("))
        assertTrue(source.contains("externalPcmInput = false"))
        assertTrue(source.contains("preferences = prefs.asrEngineModePreferencesSnapshot()"))
        assertTrue(source.contains("source = AsrEngineConstructionSource.App"))
        assertTrue(source.contains("onPrimaryRequestDuration = requestDurationCallback"))
        assertTrue(source.contains("onRequestDuration = requestDurationCallback"))
        assertTrue(source.contains("private fun isPrimaryVendorConstructible("))
        assertTrue(source.contains("isLocalAsrVendor(vendor) -> true"))
        assertTrue(source.contains("vendor == AsrVendor.SiliconFlow -> prefs.hasSfKeys()"))
        assertTrue(source.contains("AsrLocalModelCatalog.entryFor(vendor)"))
        assertTrue(source.contains("AsrLocalModelCatalog.modelStatus(context, prefs, vendor)"))
        assertTrue(source.contains("localEntry.missingModelErrorRes"))
        assertTrue(source.contains("本地供应商保持可构造"))
        assertMarkerOrder(
            source,
            "if (!isPrimaryVendorConstructible(primaryVendor)) return null",
            "directMicrophoneEngineFactory.create("
        )
        assertFalse(source.contains("com.brycewg.asrkb.ui.settings.compose.screens"))
        assertFalse(source.contains("AllAsrLocalModelSpecs"))
        assertFalse(source.contains("shouldUseBackupAsr("))
        assertFalse(source.contains("ParallelAsrEngine("))
        assertFalse(source.contains("VolcStreamAsrEngine("))
        assertFalse(source.contains("GenericPushFileAsrAdapter("))
        listOf(
            "checkSenseVoiceModel(",
            "checkFunAsrNanoModel(",
            "checkQwen3AsrModel(",
            "checkParakeetModel(",
            "checkFireRedAsrModelFiles(",
            "checkXAsrModelFiles(",
            "isSenseVoicePrepared(",
            "isFunAsrNanoPrepared(",
            "isQwen3AsrPrepared(",
            "isParakeetPrepared(",
            "isFireRedAsrPrepared(",
            "isXAsrPrepared("
        ).forEach { forbidden ->
            assertFalse(
                "Floating ball session manager should use local lifecycle instead of $forbidden",
                source.contains(forbidden)
            )
        }
    }

    @Test
    fun backupWrappersShareArbitrationAndFeedbackContracts() {
        val parallelSource = projectFile(
            "app/src/main/java/com/brycewg/asrkb/asr/ParallelAsrEngine.kt"
        ).readText()
        val lazySource = projectFile(
            "app/src/main/java/com/brycewg/asrkb/asr/LazyLocalBackupAsrEngine.kt"
        ).readText()
        val coordinatorSource = projectFile(
            "app/src/main/java/com/brycewg/asrkb/asr/BackupAsrTerminalCoordinator.kt"
        ).readText()

        listOf(parallelSource, lazySource).forEach { source ->
            assertTrue(source.contains("BackupAwareAsrEngine"))
            assertTrue(source.contains("BackupAsrTerminalCoordinator("))
            assertTrue(source.contains("terminalCoordinator.dispatch("))
            assertTrue(source.contains("AsrBackupArbitrationEvent.PrimaryFinal"))
            assertTrue(source.contains("AsrBackupArbitrationEvent.PrimaryError"))
            assertTrue(source.contains("AsrBackupArbitrationEvent.BackupFinal"))
            assertTrue(source.contains("AsrBackupArbitrationEvent.BackupError"))
            assertTrue(source.contains("AsrBackupArbitrationEvent.SwitchDeadlineReached"))
            assertTrue(source.contains("override fun wasLastResultFromBackup()"))
        }
        assertTrue(coordinatorSource.contains("AsrBackupArbitrator("))
        assertTrue(coordinatorSource.contains("AsrBackupArbitrationCommand.DeliverFinal"))
        assertTrue(coordinatorSource.contains("AsrBackupArbitrationCommand.DeliverError"))
        assertTrue(coordinatorSource.contains("lastFinalFromBackup = source == AsrBackupArbitrationSource.Backup"))
        assertTrue(lazySource.contains("BackupAsrStatusListener"))
        assertTrue(lazySource.contains("onBackupAsrLoading(backupVendor)"))
        assertTrue(lazySource.contains("onBackupAsrRecognizing(backupVendor)"))
    }

    @Test
    fun backupParallelAndRecordingModeBaselineRecordsCurrentProductionBehavior() {
        baselineRows.forEach { row ->
            assertEquals(
                "Direct backup container for ${row.label}",
                CurrentAsrConstructionBaseline.describeBackupContainer(externalPcmInput = false),
                row.appDirectWithBackup
            )
            assertEquals(
                "Push PCM backup container for ${row.label}",
                CurrentAsrConstructionBaseline.describeBackupContainer(externalPcmInput = true),
                row.recordingTestWithBackup
            )
            assertEquals(
                "Backup-enabled recording test mode for ${row.label}",
                CurrentRecordingTestMode.PushPcm,
                CurrentAsrConstructionBaseline.recordingTestMode(
                    row.vendor,
                    row.settings,
                    backupEnabled = true
                )
            )
            assertEquals(
                "Parallel direct caller internal leg uses Push PCM construction for ${row.label}",
                row.externalPushPcm,
                row.parallelDirectLeg
            )
            assertEquals(
                "Parallel external-PCM caller internal leg uses Push PCM construction for ${row.label}",
                row.externalPushPcm,
                row.parallelPushPcmLeg
            )
        }
    }

    @Test
    fun knownBehaviorDriftIsExplicitAndAnchoredToProductionSources() {
        val volcStandard = baselineRow(AsrVendor.Volc, "streaming=false,standardFile=true")
        assertNotEquals(volcStandard.appDirect, volcStandard.externalDirect)
        assertNotEquals(volcStandard.appDirect, volcStandard.parallelDirectLeg)

        val externalDirect = AsrDirectMicrophoneEngineFactory().resolvePlan(
            vendor = AsrVendor.Volc,
            preferences = AsrEngineModePreferences(volcStandardFileEnabled = true),
            source = AsrEngineConstructionSource.ExternalIntegration
        )
        assertEquals("VolcFileAsrEngine", externalDirect.engineClassName)

        val recordingTestFileOnly = baselineRow(AsrVendor.SiliconFlow, "fileOnly")
        assertEquals(CurrentRecordingTestMode.File, recordingTestFileOnly.recordingTestModeWithoutBackup)
        assertEquals(CurrentAsrEngineFamily.PushPcmAdapter, recordingTestFileOnly.recordingTestPushPcm.family)

        val sensePseudo = baselineRow(AsrVendor.SenseVoice, "pseudoStream=true")
        val fireRedPseudo = baselineRow(AsrVendor.FireRedAsr, "pseudoStream=true")
        assertNotEquals(sensePseudo.appDirect, sensePseudo.parallelDirectLeg)
        assertNotEquals(fireRedPseudo.appDirect, fireRedPseudo.parallelDirectLeg)
        assertNotEquals(sensePseudo.appDirect, sensePseudo.externalDirect)
        assertNotEquals(fireRedPseudo.appDirect, fireRedPseudo.externalDirect)
        assertEquals(CurrentAsrEngineFamily.PushPcmPseudoStream, sensePseudo.parallelDirectLeg.family)
        assertEquals(CurrentAsrEngineFamily.PushPcmPseudoStream, fireRedPseudo.parallelDirectLeg.family)
    }

    private fun renderGoldenMatrix(): String = baselineRows.joinToString("\n") { row ->
        listOf(
            row.label,
            "app=${row.appDirect.render()}",
            "speech=${row.speechRecognizerDirect.render()}",
            "external=${row.externalDirect.render()}",
            "externalPush=${row.externalPushPcm.render()}",
            "recordingPush=${row.recordingTestPushPcm.render()}",
            "recordingMode=${row.recordingTestModeWithoutBackup}",
            "parallelDirect=${row.parallelDirectLeg.render()}",
            "parallelPush=${row.parallelPushPcmLeg.render()}"
        ).joinToString(" | ")
    }

    private fun sourceSlice(path: String, start: String, end: String?): String {
        val text = projectFile(path).readText()
        val startIndex = text.indexOf(start)
        assertTrue("Missing source start marker '$start' in $path", startIndex >= 0)
        val endIndex = end?.let { marker ->
            text.indexOf(marker, startIndex + start.length).let {
                if (it >= 0) it else text.length
            }
        } ?: text.length
        return text.substring(startIndex, endIndex)
    }

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

    private fun assertMarkerOrder(source: String, before: String, after: String) {
        val beforeIndex = source.indexOf(before)
        val afterIndex = source.indexOf(after)
        assertTrue("Missing source marker '$before'", beforeIndex >= 0)
        assertTrue("Missing source marker '$after'", afterIndex >= 0)
        assertTrue("Expected '$before' before '$after'", beforeIndex < afterIndex)
    }

    private fun baselineRow(vendor: AsrVendor, settingsLabel: String): BaselineRow = baselineRows.single { it.vendor == vendor && it.settingsLabel == settingsLabel }

    private data class BaselineRow(
        val vendor: AsrVendor,
        val settingsLabel: String,
        val settings: CurrentAsrConstructionSettings
    ) {
        val label: String = "${vendor.name}[$settingsLabel]"
        val appDirect: CurrentAsrEngineUse =
            describe(CurrentAsrConstructionPath.AppDirectMicrophone)
        val speechRecognizerDirect: CurrentAsrEngineUse =
            describe(CurrentAsrConstructionPath.SpeechRecognizerDirectMicrophone)
        val externalDirect: CurrentAsrEngineUse =
            describe(CurrentAsrConstructionPath.ExternalDirectMicrophone)
        val externalPushPcm: CurrentAsrEngineUse =
            describe(CurrentAsrConstructionPath.ExternalPushPcm)
        val recordingTestPushPcm: CurrentAsrEngineUse =
            describe(CurrentAsrConstructionPath.RecordingTestPushPcm)
        val recordingTestModeWithoutBackup: CurrentRecordingTestMode =
            CurrentAsrConstructionBaseline.recordingTestMode(vendor, settings, backupEnabled = false)
        val parallelDirectLeg: CurrentAsrEngineUse =
            describe(CurrentAsrConstructionPath.ParallelDirectLeg)
        val parallelPushPcmLeg: CurrentAsrEngineUse =
            describe(CurrentAsrConstructionPath.ParallelPushPcmLeg)
        val appDirectWithBackup: CurrentAsrEngineUse =
            CurrentAsrConstructionBaseline.describeBackupContainer(externalPcmInput = false)
        val recordingTestWithBackup: CurrentAsrEngineUse =
            CurrentAsrConstructionBaseline.describeBackupContainer(externalPcmInput = true)

        fun useFor(path: CurrentAsrConstructionPath): CurrentAsrEngineUse = when (path) {
            CurrentAsrConstructionPath.AppDirectMicrophone -> appDirect
            CurrentAsrConstructionPath.SpeechRecognizerDirectMicrophone -> speechRecognizerDirect
            CurrentAsrConstructionPath.ExternalDirectMicrophone -> externalDirect
            CurrentAsrConstructionPath.ExternalPushPcm -> externalPushPcm
            CurrentAsrConstructionPath.RecordingTestPushPcm -> recordingTestPushPcm
            CurrentAsrConstructionPath.ParallelDirectLeg -> parallelDirectLeg
            CurrentAsrConstructionPath.ParallelPushPcmLeg -> parallelPushPcmLeg
        }

        private fun describe(path: CurrentAsrConstructionPath): CurrentAsrEngineUse = CurrentAsrConstructionBaseline.describe(path, vendor, settings)
    }

    private companion object {
        private val baselineRows = listOf(
            listOf(
                BaselineRow(
                    AsrVendor.Volc,
                    "streaming=true",
                    CurrentAsrConstructionSettings(streamingEnabled = true)
                ),
                BaselineRow(
                    AsrVendor.Volc,
                    "streaming=false,standardFile=false",
                    CurrentAsrConstructionSettings()
                ),
                BaselineRow(
                    AsrVendor.Volc,
                    "streaming=false,standardFile=true",
                    CurrentAsrConstructionSettings(volcStandardFileEnabled = true)
                )
            ),
            streamingRows(AsrVendor.ElevenLabs),
            streamingRows(AsrVendor.OpenAI),
            streamingRows(AsrVendor.DashScope),
            streamingRows(AsrVendor.Soniox),
            fileOnlyRow(AsrVendor.SiliconFlow),
            fileOnlyRow(AsrVendor.OpenRouter),
            fileOnlyRow(AsrVendor.Gemini),
            fileOnlyRow(AsrVendor.MiMo),
            fileOnlyRow(AsrVendor.StepAudio),
            fileOnlyRow(AsrVendor.Zhipu),
            fileOnlyRow(AsrVendor.Cohere),
            fileOnlyRow(AsrVendor.FunAsrNano),
            fileOnlyRow(AsrVendor.Qwen3Asr),
            fileOnlyRow(AsrVendor.Parakeet),
            pseudoRows(AsrVendor.SenseVoice),
            pseudoRows(AsrVendor.FireRedAsr),
            listOf(
                BaselineRow(
                    AsrVendor.XAsr,
                    "alwaysStreaming",
                    CurrentAsrConstructionSettings(streamingEnabled = true)
                )
            ),
            streamingRows(AsrVendor.Tencent)
        ).flatten()

        private fun streamingRows(vendor: AsrVendor): List<BaselineRow> = listOf(
            BaselineRow(
                vendor,
                "streaming=true",
                CurrentAsrConstructionSettings(streamingEnabled = true)
            ),
            BaselineRow(
                vendor,
                "streaming=false",
                CurrentAsrConstructionSettings()
            )
        )

        private fun fileOnlyRow(vendor: AsrVendor): List<BaselineRow> = listOf(
            BaselineRow(vendor, "fileOnly", CurrentAsrConstructionSettings())
        )

        private fun pseudoRows(vendor: AsrVendor): List<BaselineRow> = listOf(
            BaselineRow(
                vendor,
                "pseudoStream=false",
                CurrentAsrConstructionSettings()
            ),
            BaselineRow(
                vendor,
                "pseudoStream=true",
                CurrentAsrConstructionSettings(pseudoStreamEnabled = true)
            )
        )
    }
}
