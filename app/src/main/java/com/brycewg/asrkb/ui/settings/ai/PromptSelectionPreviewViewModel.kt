package com.brycewg.asrkb.ui.settings.ai

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.asr.PromptSelector
import com.brycewg.asrkb.store.AsrHistoryStore
import com.brycewg.asrkb.store.LlmModelConfigResolver
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptSelectionFailReason
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PromptSelectionPreviewViewModel : ViewModel() {
    companion object {
        const val MAX_SELECTION = 20
        private const val TAG = "PromptSelectionPreview"
    }

    enum class Phase { SELECTING, RUNNING, COMPLETED, CANCELLED }

    data class HistoryItem(
        val id: String,
        val timestamp: Long,
        val text: String,
        val summary: String,
        val vendorId: String,
        val source: String
    )

    data class PreviewResult(
        val recordId: String,
        val text: String,
        val matchedTitle: String?,
        val skippedPolish: Boolean,
        val elapsedMs: Long,
        val failureReason: PromptSelectionFailReason?
    ) {
        val succeeded: Boolean get() = failureReason == null
    }

    data class UiState(
        val history: List<HistoryItem> = emptyList(),
        val selectedIds: List<String> = emptyList(),
        val phase: Phase = Phase.SELECTING,
        val results: List<PreviewResult> = emptyList(),
        val configInvalid: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var runJob: Job? = null
    private var processor: LlmPostProcessor? = null
    private var runGeneration = 0L

    fun load(historyStore: AsrHistoryStore) {
        viewModelScope.launch {
            val records = withContext(Dispatchers.IO) { historyStore.listAll() }
            _uiState.value = UiState(
                history = records.mapNotNull { record ->
                    val original = (record.rawText ?: record.text).trim()
                    original.takeIf { it.isNotEmpty() }?.let {
                        HistoryItem(
                            id = record.id,
                            timestamp = record.timestamp,
                            text = original,
                            summary = summarize(original),
                            vendorId = record.vendorId,
                            source = record.source
                        )
                    }
                }
            )
        }
    }

    fun toggleSelection(id: String, selected: Boolean) {
        val state = _uiState.value
        if (state.phase != Phase.SELECTING) return
        val updated = state.selectedIds.toMutableList().apply {
            remove(id)
            if (selected && size < MAX_SELECTION) add(id)
        }
        _uiState.value = state.copy(selectedIds = updated, configInvalid = false)
    }

    fun start(prefs: Prefs) {
        val initial = _uiState.value
        if (initial.phase != Phase.SELECTING || initial.selectedIds.isEmpty()) return

        val candidateState = prefs.getPromptSelectionCandidateState()
        val modelRef = prefs.promptSelectorModelRef
        if (!candidateState.candidatesValid || !LlmModelConfigResolver.summarize(prefs, modelRef).available) {
            _uiState.value = initial.copy(configInvalid = true)
            return
        }

        val selectedIds = initial.selectedIds.toSet()
        val selected = initial.history.filter { it.id in selectedIds }
        if (selected.isEmpty()) return
        val requestProcessor = LlmPostProcessor()
        val generation = ++runGeneration
        processor = requestProcessor
        _uiState.value = initial.copy(
            phase = Phase.RUNNING,
            results = emptyList(),
            configInvalid = false
        )
        runJob = viewModelScope.launch {
            try {
                selected.forEach { item ->
                    val startedAt = System.nanoTime()
                    val result = try {
                        val outcome = PromptSelector.select(
                            prefs = prefs,
                            processor = requestProcessor,
                            asrText = item.text,
                            candidates = candidateState.resolved,
                            modelRef = modelRef,
                            persistRequestMode = false
                        )
                        PreviewResult(
                            recordId = item.id,
                            text = item.text,
                            matchedTitle = outcome.candidate?.displayTitle,
                            skippedPolish = outcome.candidate?.skipsPolish == true,
                            elapsedMs = outcome.status.elapsedMs,
                            failureReason = outcome.status.failReasonEnum
                        )
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.w(TAG, "Preview item failed", t)
                        PreviewResult(
                            recordId = item.id,
                            text = item.text,
                            matchedTitle = null,
                            skippedPolish = false,
                            elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt),
                            failureReason = PromptSelectionFailReason.REQUEST_FAILED
                        )
                    }
                    if (generation != runGeneration) return@launch
                    _uiState.value = _uiState.value.let { state ->
                        state.copy(
                            results = state.results + result
                        )
                    }
                }
                if (generation == runGeneration) {
                    _uiState.value = _uiState.value.copy(phase = Phase.COMPLETED)
                }
            } catch (_: CancellationException) {
                if (generation == runGeneration && _uiState.value.phase == Phase.RUNNING) {
                    _uiState.value = _uiState.value.copy(phase = Phase.CANCELLED)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Preview run failed", t)
                if (generation == runGeneration) {
                    _uiState.value = _uiState.value.copy(phase = Phase.CANCELLED)
                }
            } finally {
                if (generation == runGeneration) {
                    processor = null
                    runJob = null
                }
            }
        }
    }

    fun cancel() {
        if (_uiState.value.phase != Phase.RUNNING) return
        processor?.cancelActiveRequest()
        runJob?.cancel(CancellationException("Prompt selection preview cancelled"))
    }

    fun resetSelection() {
        if (_uiState.value.phase == Phase.RUNNING) return
        _uiState.value = _uiState.value.copy(
            selectedIds = emptyList(),
            phase = Phase.SELECTING,
            results = emptyList(),
            configInvalid = false
        )
    }

    fun close() {
        runGeneration++
        processor?.cancelActiveRequest()
        runJob?.cancel(CancellationException("Prompt selection preview closed"))
        processor = null
        runJob = null
        _uiState.value = UiState(history = _uiState.value.history)
    }

    override fun onCleared() {
        runGeneration++
        processor?.cancelActiveRequest()
        runJob?.cancel(CancellationException("Prompt selection preview cleared"))
        super.onCleared()
    }

    private fun summarize(text: String): String {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        return if (normalized.length <= 120) normalized else normalized.take(120) + "..."
    }
}
