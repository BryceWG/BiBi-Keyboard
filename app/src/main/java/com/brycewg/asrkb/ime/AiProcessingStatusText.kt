package com.brycewg.asrkb.ime

import android.content.Context
import com.brycewg.asrkb.R

/**
 * 键盘信息条在润色阶段的文案。
 *
 * 已知提示词名称时直接展示名称；自动选择尚未完成时展示选择中；名称为空时回退固定文案。
 */
internal fun Context.aiProcessingStatusText(
    promptTitle: String?,
    selectingPrompt: Boolean = false
): String {
    if (selectingPrompt) return getString(R.string.status_prompt_selecting)
    val title = promptTitle?.trim().orEmpty()
    return if (title.isEmpty()) {
        getString(R.string.status_ai_processing)
    } else {
        getString(R.string.status_ai_processing_named, title)
    }
}
