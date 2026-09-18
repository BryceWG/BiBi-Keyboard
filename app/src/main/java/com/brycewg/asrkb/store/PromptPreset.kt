package com.brycewg.asrkb.store

import kotlinx.serialization.Serializable

/**
 * 一条 Prompt 预设。
 *
 * - [skill]：该预设的适用场景与预期输出的简短描述，供自动选择（PromptSelector）分类使用。
 *   旧 JSON 缺失该字段时按空字符串读取，不做迁移失败处理。
 */
@Serializable
data class PromptPreset(
    val id: String,
    val title: String,
    val content: String,
    val skill: String = ""
)
