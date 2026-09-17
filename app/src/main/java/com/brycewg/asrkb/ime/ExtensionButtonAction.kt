/**
 * 扩展按钮动作类型定义。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import com.brycewg.asrkb.R

/**
 * 扩展按钮动作类型
 */
enum class ExtensionButtonAction(val id: String, val titleResId: Int, val iconResId: Int) {
    /**
     * 禁用（不显示按钮或显示为灰色）
     */
    NONE(
        id = "none",
        titleResId = R.string.ext_btn_none,
        iconResId = R.drawable.dots_three_outline
    ),

    /**
     * 选择模式切换（进入/退出选择模式）
     */
    SELECT(
        id = "select",
        titleResId = R.string.ext_btn_select,
        iconResId = R.drawable.selection_toggle
    ),

    /**
     * 全选当前输入框文本
     */
    SELECT_ALL(
        id = "select_all",
        titleResId = R.string.ext_btn_select_all,
        iconResId = R.drawable.selection_all_toggle
    ),

    /**
     * 复制选中的文本到剪贴板
     */
    COPY(
        id = "copy",
        titleResId = R.string.ext_btn_copy,
        iconResId = R.drawable.copy_toggle
    ),

    /**
     * 打开识别历史面板（展示最终提交文本）
     */
    ASR_HISTORY(
        id = "asr_history",
        titleResId = R.string.ext_btn_asr_history,
        iconResId = R.drawable.clock_counter_clockwise
    ),

    /**
     * 粘贴剪贴板内容到当前光标位置
     */
    PASTE(
        id = "paste",
        titleResId = R.string.ext_btn_paste,
        iconResId = R.drawable.selection_background_toggle
    ),

    /**
     * 打开剪贴板管理面板
     */
    CLIPBOARD(
        id = "clipboard",
        titleResId = R.string.ext_btn_clipboard,
        iconResId = R.drawable.clipboard_toggle
    ),

    /**
     * 收起键盘
     */
    HIDE_KEYBOARD(
        id = "hide_keyboard",
        titleResId = R.string.ext_btn_hide,
        iconResId = R.drawable.caret_circle_down_toggle
    ),

    /**
     * 录音判停开关（无人说话自动停止录音）
     */
    SILENCE_AUTOSTOP_TOGGLE(
        id = "silence_autostop_toggle",
        titleResId = R.string.ext_btn_silence_autostop,
        iconResId = R.drawable.hand_palm
    ),

    /**
     * 录音时屏幕常亮开关
     */
    KEEP_SCREEN_ON_WHILE_RECORDING_TOGGLE(
        id = "keep_screen_on_while_recording_toggle",
        titleResId = R.string.ext_btn_keep_screen_on_while_recording,
        iconResId = R.drawable.lightbulb_filament
    ),

    /**
     * 麦克风录音模式切换（长按说话 / 点按启停）
     */
    MIC_TAP_TOGGLE(
        id = "mic_tap_toggle",
        titleResId = R.string.ext_btn_mic_tap_toggle,
        iconResId = R.drawable.hand_pointing_fill
    ),

    /**
     * 悬浮键盘开关
     */
    FLOATING_KEYBOARD_TOGGLE(
        id = "floating_keyboard_toggle",
        titleResId = R.string.ext_btn_floating_keyboard,
        iconResId = R.drawable.arrow_square_in
    ),

    /**
     * 输入完成后自动回车开关
     */
    AUTO_ENTER_AFTER_ASR_TOGGLE(
        id = "auto_enter_after_asr_toggle",
        titleResId = R.string.ext_btn_auto_enter_after_asr,
        iconResId = R.drawable.arrow_square_up
    ),

    /**
     * 光标左移一位（长按连发）
     */
    CURSOR_LEFT(
        id = "cursor_left",
        titleResId = R.string.ext_btn_cursor_left,
        iconResId = R.drawable.arrow_left_toggle
    ),

    /**
     * 光标右移一位（长按连发）
     */
    CURSOR_RIGHT(
        id = "cursor_right",
        titleResId = R.string.ext_btn_cursor_right,
        iconResId = R.drawable.arrow_right_toggle
    ),

    /**
     * 移动光标到上一个断句标点之后
     */
    MOVE_PREV_PUNCT(
        id = "prev_punct",
        titleResId = R.string.ext_btn_prev_punct,
        iconResId = R.drawable.caret_line_left
    ),

    /**
     * 移动光标到下一个断句标点之前
     */
    MOVE_NEXT_PUNCT(
        id = "next_punct",
        titleResId = R.string.ext_btn_next_punct,
        iconResId = R.drawable.caret_line_right
    ),

    /**
     * 移动光标到文本开头
     */
    MOVE_START(
        id = "move_start",
        titleResId = R.string.ext_btn_move_start,
        iconResId = R.drawable.arrow_line_left_toggle
    ),

    /**
     * 移动光标到文本结尾
     */
    MOVE_END(
        id = "move_end",
        titleResId = R.string.ext_btn_move_end,
        iconResId = R.drawable.arrow_line_right_toggle
    ),

    /**
     * 打开数字符号键盘面板
     */
    NUMPAD(
        id = "numpad",
        titleResId = R.string.ext_btn_numpad,
        iconResId = R.drawable.numpad_toggle
    ),

    /**
     * 撤销/退格
     */
    UNDO(
        id = "undo",
        titleResId = R.string.ext_btn_undo,
        iconResId = R.drawable.arrow_u_up_left_toggle
    );

    companion object {
        /**
         * 从ID获取动作类型
         */
        fun fromId(id: String?): ExtensionButtonAction = when (id) {
            "copy_last_asr" -> ASR_HISTORY
            else -> values().firstOrNull { it.id == id } ?: NONE
        }

        /**
         * 获取默认的4个按钮配置
         * 默认顺序：撤销、全选、复制、收起键盘
         */
        fun getDefaults(): List<ExtensionButtonAction> = listOf(UNDO, SELECT_ALL, COPY, HIDE_KEYBOARD)
    }
}
