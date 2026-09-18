// Replays archived recognition audio and post-processing without creating usage events.
package com.brycewg.asrkb.ui.history

import android.content.Context
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.asr.AsrEngineConstructionSource
import com.brycewg.asrkb.asr.AsrEngineInvocationMode
import com.brycewg.asrkb.asr.AsrParallelEngineFactory
import com.brycewg.asrkb.asr.AsrPushPcmEngineFactory
import com.brycewg.asrkb.asr.AsrRecordedAudioRouteDecision
import com.brycewg.asrkb.asr.AsrRecordedAudioRouteResolver
import com.brycewg.asrkb.asr.BackupAwareAsrEngine
import com.brycewg.asrkb.asr.CancelableAsrEngine
import com.brycewg.asrkb.asr.ExternalPcmConsumer
import com.brycewg.asrkb.asr.StreamingAsrEngine
import com.brycewg.asrkb.store.AsrHistoryAudioStore
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.AsrHistoryTimingDiagnostics
import com.brycewg.asrkb.store.AsrHistoryTimingOrigin
import com.brycewg.asrkb.store.AsrHistoryTimingRecorder
import com.brycewg.asrkb.store.AsrHistoryTimingStage
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptSelectionStatus
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.util.AsrFinalFilters
import com.brycewg.asrkb.util.TextSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class AsrHistoryRerunCoordinator(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    private val prefs = Prefs(appContext)
    private val store = AsrHistoryStore(appContext)
    private val audioStore = AsrHistoryAudioStore(appContext)

    @Suppress("UNUSED_PARAMETER")
    fun preflight(record: AsrHistoryStore.AsrHistoryRecord): AsrRecordedAudioRouteDecision {
        val localizedContext = LocaleHelper.wrap(appContext)
        return AsrRecordedAudioRouteResolver.resolve(localizedContext, prefs)
    }

    suspend fun reRecognize(record: AsrHistoryStore.AsrHistoryRecord): AsrHistoryStore.AsrHistoryRecord {
        val timingRecorder = AsrHistoryTimingRecorder(AsrHistoryTimingOrigin.RERECOGNITION)
        val localizedContext = LocaleHelper.wrap(appContext)
        val decision = AsrRecordedAudioRouteResolver.resolve(localizedContext, prefs)
        logDiag(
            "history_rerun_route_decided",
            mapOf(
                "vendor" to decision.vendor.id,
                "currentModel" to decision.currentModelKey,
                "kind" to decision.kind.name,
                "fallbackModel" to decision.fallbackModelKey,
                "reason" to decision.reasonCode,
                "backup" to decision.backupPolicy.name
            )
        )
        if (!decision.canContinue) {
            logDiag(
                "history_rerun_blocked",
                mapOf(
                    "vendor" to decision.vendor.id,
                    "reason" to decision.reasonCode
                )
            )
            error(decision.reasonCode)
        }
        timingRecorder.begin(AsrHistoryTimingStage.AUDIO_INPUT)
        val pcm = withContext(Dispatchers.IO) { audioStore.readAudio(record.id) }
            ?: error("audio_unavailable")
        var requestMs = 0L
        val finalText = CompletableDeferred<String>()
        val listener = object : StreamingAsrEngine.Listener {
            override fun onFinal(text: String) {
                finalText.complete(text)
            }
            override fun onError(message: String) {
                finalText.completeExceptionally(IllegalStateException(message))
            }
        }
        val primary = prefs.asrVendor
        var engine: StreamingAsrEngine? = null
        try {
            val runningEngine = AsrParallelEngineFactory().createOrNull(
                context = localizedContext,
                scope = scope,
                prefs = prefs,
                listener = listener,
                primaryVendor = primary,
                backupVendor = prefs.backupAsrVendor,
                externalPcmInput = true,
                modePreferences = decision.modePreferences,
                onPrimaryRequestDuration = { requestMs = it },
                modelOverride = decision.modelOverride
            ) ?: AsrPushPcmEngineFactory().createOrNull(
                context = localizedContext,
                scope = scope,
                prefs = prefs,
                listener = listener,
                vendor = primary,
                invocationMode = AsrEngineInvocationMode.PushPcm,
                preferences = decision.modePreferences,
                source = AsrEngineConstructionSource.App,
                onRequestDuration = { requestMs = it },
                modelOverride = decision.modelOverride
            ) ?: error("engine_unavailable")
            engine = runningEngine
            logDiag(
                "history_rerun_started",
                mapOf(
                    "vendor" to decision.vendor.id,
                    "kind" to decision.kind.name,
                    "fileModel" to (decision.fallbackModelKey ?: decision.currentModelKey),
                    "reason" to decision.reasonCode
                )
            )
            runningEngine.start()
            val consumer = runningEngine as? ExternalPcmConsumer ?: error("engine_pcm_unsupported")
            if (!consumer.awaitReady()) error("engine_not_ready")
            pcm.asSequenceOfPcmChunks().forEach { chunk ->
                consumer.appendPcm(chunk, 16_000, 1)
            }
            runningEngine.stop()
            timingRecorder.end(AsrHistoryTimingStage.AUDIO_INPUT)
            timingRecorder.begin(AsrHistoryTimingStage.RECOGNITION)
            val raw = withTimeout(120_000L) { finalText.await() }
            if (raw.isBlank()) error("empty_result")
            timingRecorder.end(AsrHistoryTimingStage.RECOGNITION)
            timingRecorder.begin(AsrHistoryTimingStage.POSTPROCESS)
            val processed = processNormal(raw, timingRecorder)
            timingRecorder.end(AsrHistoryTimingStage.POSTPROCESS)
            val actualVendor = when (runningEngine) {
                is BackupAwareAsrEngine -> if (runningEngine.wasLastResultFromBackup()) {
                    runningEngine.backupVendor
                } else {
                    runningEngine.primaryVendor
                }
                else -> primary
            }
            timingRecorder.begin(AsrHistoryTimingStage.TEXT_DELIVERY)
            val updated = record.copy(
                rawText = raw,
                text = processed.text,
                vendorId = actualVendor.id,
                procMs = requestMs,
                aiProcessed = processed.aiUsed,
                aiPostMs = processed.aiMs,
                aiPostStatus = processed.status,
                llmVendorId = processed.llmVendorId,
                promptSelection = processed.promptSelection,
                charCount = TextSanitizer.countEffectiveChars(processed.text),
                status = AsrHistoryStore.AsrHistoryStatus.SUCCESS,
                failStage = AsrHistoryStore.AsrHistoryFailStage.NONE,
                failReasonCode = null
            )
            withContext(Dispatchers.IO) {
                store.updateById(record.id) { updated } ?: error("record_missing")
            }
            timingRecorder.end(AsrHistoryTimingStage.TEXT_DELIVERY)
            val timingTrace = timingRecorder.complete()
            val saved = withContext(Dispatchers.IO) {
                store.updateById(record.id) {
                    it.copy(
                        totalElapsedMs = timingTrace.totalElapsedMs,
                        timingTrace = timingTrace
                    )
                } ?: error("record_missing")
            }
            logDiag(
                "history_rerun_finished",
                mapOf(
                    "vendor" to actualVendor.id,
                    "kind" to decision.kind.name,
                    "elapsedMs" to timingTrace.totalElapsedMs,
                    "reason" to decision.reasonCode
                )
            )
            AsrHistoryTimingDiagnostics.logSaved("rerecognition", timingTrace)
            return saved
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            logDiag(
                "history_rerun_failed",
                mapOf(
                    "vendor" to decision.vendor.id,
                    "kind" to decision.kind.name,
                    "elapsedMs" to timingRecorder.snapshot().totalElapsedMs,
                    "reason" to stableFailReason(t)
                )
            )
            throw t
        } finally {
            val running = engine
            if (running != null) {
                (running as? CancelableAsrEngine)?.cancel() ?: runCatching { running.stop() }
            }
            if (!currentCoroutineContext().isActive) finalText.cancel()
        }
    }

    suspend fun reprocess(record: AsrHistoryStore.AsrHistoryRecord): AsrHistoryStore.AsrHistoryRecord {
        val timingRecorder = AsrHistoryTimingRecorder(AsrHistoryTimingOrigin.REPROCESS)
        timingRecorder.begin(AsrHistoryTimingStage.POSTPROCESS)
        val localizedContext = LocaleHelper.wrap(appContext)
        if (!prefs.hasLlmKeys()) error("llm_unavailable")
        val input = record.rawText?.takeIf { it.isNotBlank() } ?: record.text
        val result = AsrFinalFilters.applyWithAi(
            localizedContext,
            prefs,
            input,
            forceAi = true,
            aiTimingObserver = timingRecorder.asAiTimingObserver(),
            // 历史重新润色属于自动流程。
            promptSelectionMode = AsrFinalFilters.PromptSelectionMode.AUTO_IF_ENABLED
        )
        if (!result.ok || result.text.isBlank()) error(result.errorMessage ?: "postprocess_failed")
        timingRecorder.end(AsrHistoryTimingStage.POSTPROCESS)
        timingRecorder.begin(AsrHistoryTimingStage.TEXT_DELIVERY)
        val updated = record.copy(
            text = result.text,
            aiProcessed = result.usedAi,
            aiPostMs = if (result.attempted) result.llmMs else 0L,
            aiPostStatus = when {
                result.attempted && result.usedAi -> AsrHistoryStore.AiPostStatus.SUCCESS
                result.attempted -> AsrHistoryStore.AiPostStatus.FAILED
                else -> AsrHistoryStore.AiPostStatus.NONE
            },
            llmVendorId = result.llmVendorId,
            promptSelection = result.promptSelectionStatus,
            charCount = TextSanitizer.countEffectiveChars(result.text)
        )
        withContext(Dispatchers.IO) {
            store.updateById(record.id) { updated } ?: error("record_missing")
        }
        timingRecorder.end(AsrHistoryTimingStage.TEXT_DELIVERY)
        val timingTrace = timingRecorder.complete()
        val saved = withContext(Dispatchers.IO) {
            store.updateById(record.id) {
                it.copy(
                    totalElapsedMs = timingTrace.totalElapsedMs,
                    timingTrace = timingTrace
                )
            } ?: error("record_missing")
        }
        AsrHistoryTimingDiagnostics.logSaved("reprocess", timingTrace)
        return saved
    }

    private suspend fun processNormal(
        raw: String,
        timingRecorder: AsrHistoryTimingRecorder? = null
    ): ProcessedText {
        val localizedContext = LocaleHelper.wrap(appContext)
        val result = AsrFinalFilters.applyWithAi(
            localizedContext,
            prefs,
            raw,
            aiTimingObserver = timingRecorder?.asAiTimingObserver(),
            // 历史重新识别属于自动流程。
            promptSelectionMode = AsrFinalFilters.PromptSelectionMode.AUTO_IF_ENABLED
        )
        val text = result.text.ifBlank { AsrFinalFilters.applySimple(localizedContext, prefs, raw) }
        val aiUsed = result.ok && result.usedAi
        return ProcessedText(
            text = text,
            aiUsed = aiUsed,
            aiMs = if (result.attempted) result.llmMs else 0L,
            status = when {
                result.attempted && aiUsed -> AsrHistoryStore.AiPostStatus.SUCCESS
                result.attempted -> AsrHistoryStore.AiPostStatus.FAILED
                else -> AsrHistoryStore.AiPostStatus.NONE
            },
            llmVendorId = result.llmVendorId,
            promptSelection = result.promptSelectionStatus
        )
    }

    private data class ProcessedText(
        val text: String,
        val aiUsed: Boolean,
        val aiMs: Long,
        val status: AsrHistoryStore.AiPostStatus,
        val llmVendorId: String?,
        val promptSelection: PromptSelectionStatus?
    )

    private fun AsrHistoryTimingRecorder.asAiTimingObserver(): AsrFinalFilters.AiPostprocessTimingObserver = object : AsrFinalFilters.AiPostprocessTimingObserver {
        override fun onAiPostprocessStarted() {
            end(AsrHistoryTimingStage.POSTPROCESS)
            begin(AsrHistoryTimingStage.AI_POSTPROCESS)
        }

        override fun onAiPostprocessFinished() {
            end(AsrHistoryTimingStage.AI_POSTPROCESS)
            begin(AsrHistoryTimingStage.POSTPROCESS)
        }

        override fun onPromptSelectionStarted() {
            end(AsrHistoryTimingStage.POSTPROCESS)
            begin(AsrHistoryTimingStage.PROMPT_SELECTION)
        }

        override fun onPromptSelectionFinished() {
            end(AsrHistoryTimingStage.PROMPT_SELECTION)
            begin(AsrHistoryTimingStage.POSTPROCESS)
        }
    }

    private fun ByteArray.asSequenceOfPcmChunks(): Sequence<ByteArray> = sequence {
        val chunkBytes = 16_000 * 2 * 200 / 1_000
        var offset = 0
        while (offset < size) {
            val end = (offset + chunkBytes).coerceAtMost(size)
            yield(copyOfRange(offset, end))
            offset = end
        }
    }

    private fun stableFailReason(t: Throwable): String {
        val message = t.message?.trim().orEmpty()
        return if (message in STABLE_FAIL_REASONS) message else "recognize_failed"
    }

    private fun logDiag(event: String, data: Map<String, Any?> = emptyMap()) {
        DebugLogManager.logBase(category = "asr", event = event, data = data)
    }

    companion object {
        private val STABLE_FAIL_REASONS = setOf(
            "audio_unavailable",
            "engine_unavailable",
            "engine_pcm_unsupported",
            "engine_not_ready",
            "empty_result",
            "record_missing",
            AsrRecordedAudioRouteResolver.REASON_DIRECT_FILE,
            AsrRecordedAudioRouteResolver.REASON_MAPPED_FALLBACK,
            AsrRecordedAudioRouteResolver.REASON_REPLAY_STREAM,
            AsrRecordedAudioRouteResolver.REASON_UNSUPPORTED_OPENAI_STREAMING,
            AsrRecordedAudioRouteResolver.REASON_UNSUPPORTED_XASR,
            AsrRecordedAudioRouteResolver.REASON_UNSUPPORTED_UNKNOWN_MODEL,
            AsrRecordedAudioRouteResolver.REASON_UNSUPPORTED_NO_FILE_FALLBACK,
            AsrRecordedAudioRouteResolver.REASON_UNAVAILABLE_CREDENTIALS
        )
    }
}
