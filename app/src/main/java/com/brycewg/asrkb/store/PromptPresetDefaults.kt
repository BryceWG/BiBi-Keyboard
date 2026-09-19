/**
 * 默认 Prompt 预设构建入口。
 *
 * 归属模块：store
 */
package com.brycewg.asrkb.store

import android.content.Context
import com.brycewg.asrkb.R

/**
 * 默认 Prompt 预设（从 [Prefs] 中拆出）。
 *
 * [PromptPreset.skill] 与 title/content 一样来自本地化资源，语言切换时由
 * [PromptPresetMigrations.syncDefaultsForLanguageIfNeeded] 同步到未被用户修改过的内置预设。
 */
internal fun buildDefaultPromptPresets(context: Context): List<PromptPreset> = listOf(
    PromptPreset(
        id = DEFAULT_PRESET_GENERAL_ID,
        title = context.getString(R.string.llm_prompt_preset_default_general_title),
        content = context.getString(R.string.llm_prompt_preset_default_general_content),
        skill = context.getString(R.string.llm_prompt_preset_default_general_skill)
    ),
    PromptPreset(
        id = DEFAULT_PRESET_POLISH_ID,
        title = context.getString(R.string.llm_prompt_preset_default_polish_title),
        content = context.getString(R.string.llm_prompt_preset_default_polish_content),
        skill = context.getString(R.string.llm_prompt_preset_default_polish_skill)
    ),
    PromptPreset(
        id = DEFAULT_PRESET_TRANSLATE_EN_ID,
        title = context.getString(R.string.llm_prompt_preset_default_translate_en_title),
        content = context.getString(R.string.llm_prompt_preset_default_translate_en_content),
        skill = context.getString(R.string.llm_prompt_preset_default_translate_en_skill)
    ),
    PromptPreset(
        id = DEFAULT_PRESET_KEY_POINTS_ID,
        title = context.getString(R.string.llm_prompt_preset_default_key_points_title),
        content = context.getString(R.string.llm_prompt_preset_default_key_points_content),
        skill = context.getString(R.string.llm_prompt_preset_default_key_points_skill)
    ),
    PromptPreset(
        id = DEFAULT_PRESET_TODO_ID,
        title = context.getString(R.string.llm_prompt_preset_default_todo_title),
        content = context.getString(R.string.llm_prompt_preset_default_todo_content),
        skill = context.getString(R.string.llm_prompt_preset_default_todo_skill)
    )
)

/** 内置默认预设的稳定 ID（与本地化标题无关，可安全跨语言识别）。 */
internal val DEFAULT_PROMPT_PRESET_IDS: List<String> = listOf(
    DEFAULT_PRESET_GENERAL_ID,
    DEFAULT_PRESET_POLISH_ID,
    DEFAULT_PRESET_TRANSLATE_EN_ID,
    DEFAULT_PRESET_KEY_POINTS_ID,
    DEFAULT_PRESET_TODO_ID
)

internal fun isBuiltinDefaultPromptPresetId(id: String): Boolean = id in DEFAULT_PROMPT_PRESET_IDS

private const val DEFAULT_PRESET_GENERAL_ID = "preset.default.general"

/**
 * 内置“基础文本润色”预设 ID。
 *
 * 自动选择的首次候选初始化依赖它：按 ID 引用，不按标题匹配，避免文案漂移。
 */
internal const val DEFAULT_PRESET_POLISH_ID = "preset.default.polish"
private const val DEFAULT_PRESET_TRANSLATE_EN_ID = "preset.default.translate_en"
private const val DEFAULT_PRESET_KEY_POINTS_ID = "preset.default.key_points"
private const val DEFAULT_PRESET_TODO_ID = "preset.default.todo"
