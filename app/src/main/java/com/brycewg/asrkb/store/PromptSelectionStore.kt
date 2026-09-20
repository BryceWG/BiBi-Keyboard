/**
 * 自动选择提示词（润色模式）的候选集合与引用模型读写。
 *
 * 归属模块：store
 */
package com.brycewg.asrkb.store

import android.util.Log
import com.brycewg.asrkb.R
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 存储约定：
 * - 候选集合与引用模型独立于预设列表保存：预设被删除后候选 ID 仍然保留，页面据此提示“已删除”。
 * - 候选顺序即分类时 `p1..pN` 的映射顺序，取预设列表顺序（不是用户点选顺序）。
 */
internal object PromptSelectionStore {
    private const val TAG = "PromptSelectionStore"

    /** 候选至少 2 个才允许自动选择。 */
    const val MIN_CANDIDATE_COUNT = 2

    private val stringListSerializer = ListSerializer(String.serializer())

    /** 候选集合在当前预设列表中的解析结果。 */
    data class CandidateState(
        /** 存储的候选 ID（未过滤顺序，含已删除项）。 */
        val storedIds: List<String>,
        /**
         * 现存候选，按分类 `p1..pN` 的顺序排列。
         *
         * 顺序 = 预设列表顺序，特殊项“跳过润色”固定在最后（平局时优先普通预设）。
         */
        val resolved: List<PromptSelectionCandidate>,
        /** 候选 ID 中在当前预设列表里已不存在的部分；特殊项永不算删除。 */
        val deletedIds: List<String>,
        /** 现存候选中 skill 为空的部分（仅普通预设）。 */
        val missingSkillIds: List<String>
    ) {
        val resolvedIds: List<String> get() = resolved.map { it.id }

        val includesSkipPolish: Boolean get() = resolved.any { it.skipsPolish }

        /** 候选数量与 skill 是否满足自动选择的最低要求；特殊项计入数量但不参与 skill 检查。 */
        val candidatesValid: Boolean
            get() = resolved.size >= MIN_CANDIDATE_COUNT && missingSkillIds.isEmpty()
    }

    fun readStoredIds(prefs: Prefs): List<String>? {
        val raw = prefs.getPrefString(KEY_PROMPT_SELECT_CANDIDATE_IDS, "")
        if (raw.isBlank()) return null
        return decodeIds(prefs, raw)
    }

    fun writeStoredIds(prefs: Prefs, ids: List<String>) {
        val cleaned = ids.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        try {
            prefs.setPrefString(
                KEY_PROMPT_SELECT_CANDIDATE_IDS,
                prefs.json.encodeToString(stringListSerializer, cleaned)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to serialize prompt selection candidate ids", e)
        }
    }

    /**
     * 读取候选 ID；从未写入过时初始化为“内置基础润色预设 + 跳过润色”两项。
     *
     * 初始化会落盘，之后即使预设被删除也不会重新生成，避免覆盖用户的显式选择。
     */
    fun candidateIds(prefs: Prefs, presets: List<PromptPreset>): List<String> {
        val stored = readStoredIds(prefs)
        if (stored != null) return stored
        val initialized = buildList {
            if (presets.any { it.id == DEFAULT_PRESET_POLISH_ID }) {
                add(DEFAULT_PRESET_POLISH_ID)
            }
            add(PROMPT_SELECTION_SKIP_POLISH_ID)
        }
        writeStoredIds(prefs, initialized)
        return initialized
    }

    /** “跳过润色”特殊候选（skill/标题取自当前语言的固定资源）。 */
    fun skipPolishCandidate(prefs: Prefs): PromptSelectionCandidate.SkipPolish = PromptSelectionCandidate.SkipPolish(
        skill = prefs.getLocalizedString(R.string.prompt_selection_skip_polish_skill),
        displayTitle = prefs.getLocalizedString(R.string.prompt_selection_skip_polish_title)
    )

    fun candidateState(prefs: Prefs, presets: List<PromptPreset>): CandidateState {
        val stored = candidateIds(prefs, presets)
        val storedSet = stored.toSet()
        val knownIds = presets.map { it.id }.toSet()
        val presetCandidates = presets
            .filter { it.id in storedSet }
            .map { PromptSelectionCandidate.Preset(it) }
        val resolved = buildList {
            addAll(presetCandidates)
            if (PROMPT_SELECTION_SKIP_POLISH_ID in storedSet) {
                add(skipPolishCandidate(prefs))
            }
        }
        return CandidateState(
            storedIds = stored,
            resolved = resolved,
            deletedIds = stored.filter { !isPromptSelectionSkipPolishId(it) && it !in knownIds },
            missingSkillIds = presetCandidates.filter { it.skill.isBlank() }.map { it.id }
        )
    }

    fun readModelRef(prefs: Prefs): LlmFeatureModelRef = LlmFeatureModelRefStore.read(
        prefs.getPrefString(KEY_PROMPT_SELECTOR_MODEL, ""),
        prefs.json
    )

    fun writeModelRef(prefs: Prefs, ref: LlmFeatureModelRef) {
        val encoded = LlmFeatureModelRefStore.encode(ref, prefs.json)
        if (encoded.isNotBlank()) prefs.setPrefString(KEY_PROMPT_SELECTOR_MODEL, encoded)
    }

    /** 供备份导入使用：未提供键或内容非法时不覆盖本地状态。 */
    fun importModelRefIfPresent(prefs: Prefs, raw: String?) {
        if (raw.isNullOrBlank()) return
        val validated = LlmFeatureModelRefStore.validatedRawOrNull(raw, prefs.json) ?: return
        prefs.setPrefString(KEY_PROMPT_SELECTOR_MODEL, validated)
    }

    fun importCandidateIdsIfPresent(prefs: Prefs, raw: String?) {
        if (raw.isNullOrBlank()) return
        val decoded = decodeIds(prefs, raw) ?: return
        writeStoredIds(prefs, decoded)
    }

    private fun decodeIds(prefs: Prefs, raw: String): List<String>? = try {
        prefs.json.decodeFromString(stringListSerializer, raw)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to parse prompt selection candidate ids", e)
        null
    }
}
