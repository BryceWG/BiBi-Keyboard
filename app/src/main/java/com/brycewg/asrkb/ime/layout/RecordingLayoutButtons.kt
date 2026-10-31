/** 录音面板专用按钮及实例配置约定。归属模块：ime/layout。 */
package com.brycewg.asrkb.ime.layout

internal object RecordingLayoutButtons {
    const val TRANSLATE = "recording_translate"
    const val SKIP_POLISH = "recording_skip_polish"
    const val PROMPT_POLISH = "recording_prompt_polish"
    const val PROMPT_ID = "prompt_id"

    val defIds = setOf("gesture_cancel", "gesture_send", SKIP_POLISH, PROMPT_POLISH, TRANSLATE)
}
