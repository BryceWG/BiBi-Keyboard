package com.brycewg.asrkb.ui.settings.ai

import android.util.Log
import androidx.lifecycle.ViewModel
import com.brycewg.asrkb.store.LlmCustomProviderOption
import com.brycewg.asrkb.store.LlmModelConfigResolver
import com.brycewg.asrkb.store.LlmVendorOption
import com.brycewg.asrkb.store.PROMPT_SELECTION_SKIP_POLISH_ID
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.PromptSelectionCandidate
import com.brycewg.asrkb.store.PromptSelectionStore
import com.brycewg.asrkb.store.LlmFeatureModelRef
import com.brycewg.asrkb.store.PromptSelectorModelSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * “润色模式（自动选择提示词）”设置页状态。
 *
 * 与 [AiPostSettingsViewModel] 分开：该页只读写自动选择相关偏好，不参与预设/供应商编辑，
 * 避免两处状态互相覆盖。所有写入都即时落盘（开关与选择都是低频操作）。
 */
class PromptSelectionSettingsViewModel : ViewModel() {
    companion object {
        private const val TAG = "PromptSelectionVM"
    }

    /** 候选项行：是否勾选、是否缺 skill（仅普通预设可能有意义）。 */
    data class CandidateRow(
        val candidate: PromptSelectionCandidate,
        val checked: Boolean,
        val missingSkill: Boolean
    )

    data class UiState(
        val enabled: Boolean = false,
        /** 全部可选项：全部预设 + “跳过润色”特殊项（供多选列表展示）。 */
        val candidates: List<CandidateRow> = emptyList(),
        /** 已删除的候选 ID，按存储顺序（永不含特殊 ID）。 */
        val deletedCandidateIds: List<String> = emptyList(),
        /** 已选候选中 skill 为空的预设标题。 */
        val missingSkillTitles: List<String> = emptyList(),
        /** 已选候选数量（含特殊项），即分类时 p1..pN 的个数。 */
        val resolvedCount: Int = 0,
        val candidatesValid: Boolean = false,
        val modelSummary: PromptSelectorModelSummary? = null,
        val vendorOptions: List<LlmVendorOption> = emptyList(),
        val customOptions: List<LlmCustomProviderOption> = emptyList()
    ) {
        /** 配置是否满足启用条件：候选 >= 2 且 skill 齐全，且选择模型可解析。 */
        val canEnable: Boolean
            get() = candidatesValid && modelSummary?.available == true

        /** 开关已打开但配置已失效：保留开关并提示。 */
        val showInvalidWarning: Boolean
            get() = enabled && !canEnable
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun load(prefs: Prefs) = refresh(prefs)

    private fun refresh(prefs: Prefs) {
        try {
            val presets = prefs.getPromptPresets()
            val state = prefs.getPromptSelectionCandidateState()
            val selectedIds = state.resolvedIds.toSet()
            val rows = buildList {
                presets.forEach { preset ->
                    add(
                        CandidateRow(
                            candidate = PromptSelectionCandidate.Preset(preset),
                            checked = preset.id in selectedIds,
                            missingSkill = preset.skill.isBlank()
                        )
                    )
                }
                val skipCandidate = PromptSelectionStore.skipPolishCandidate(prefs)
                add(
                    CandidateRow(
                        candidate = skipCandidate,
                        checked = skipCandidate.id in selectedIds,
                        missingSkill = false
                    )
                )
            }
            _uiState.value = UiState(
                enabled = prefs.promptAutoSelectEnabled,
                candidates = rows,
                deletedCandidateIds = state.deletedIds,
                missingSkillTitles = state.resolved
                    .filterIsInstance<PromptSelectionCandidate.Preset>()
                    .filter { it.skill.isBlank() }
                    .map { it.displayTitle },
                resolvedCount = state.resolved.size,
                candidatesValid = state.candidatesValid,
                modelSummary = LlmModelConfigResolver.summarize(prefs, prefs.promptSelectorModelRef),
                vendorOptions = LlmModelConfigResolver.builtinVendorOptions(prefs),
                customOptions = LlmModelConfigResolver.customProviderOptions(prefs)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load prompt selection state", e)
        }
    }

    /**
     * 切换开关。
     *
     * @return 是否写入成功。配置无效时拒绝启用（关闭始终允许）。
     */
    fun setEnabled(prefs: Prefs, enabled: Boolean): Boolean {
        val state = _uiState.value
        if (enabled && !state.canEnable) return false
        return try {
            prefs.promptAutoSelectEnabled = enabled
            refresh(prefs)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle prompt auto selection", e)
            false
        }
    }

    /**
     * 提交候选选择。候选顺序始终按预设列表顺序重排（即 p1..pN 的映射顺序），
     * “跳过润色”特殊项固定在最后（平局时优先普通预设）；
     * 用户确认选择时以当前可见候选为准；已删除预设无法再被选择，因此同时清理其残留 ID。
     */
    fun setCandidates(prefs: Prefs, selectedIds: Set<String>) {
        try {
            val presets = prefs.getPromptPresets()
            val ordered = presets.map { it.id }.filter { it in selectedIds } +
                if (PROMPT_SELECTION_SKIP_POLISH_ID in selectedIds) {
                    listOf(PROMPT_SELECTION_SKIP_POLISH_ID)
                } else {
                    emptyList()
                }
            prefs.setPromptSelectionCandidateIds(ordered)
            refresh(prefs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update prompt selection candidates", e)
        }
    }

    fun setModelRef(prefs: Prefs, ref: LlmFeatureModelRef) {
        try {
            prefs.promptSelectorModelRef = ref
            refresh(prefs)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update prompt selector model", e)
        }
    }

    /** 候选数量的下限（与运行时校验共用同一常量）。 */
    fun minimumCandidateCount(): Int = PromptSelectionStore.MIN_CANDIDATE_COUNT
}
