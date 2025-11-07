package com.brycewg.asrkb.ime

import com.brycewg.asrkb.R

/**
 * 扩展按钮动作类型
 *
 * 定义键盘扩展按钮可以执行的所有动作类型
 */
enum class ExtensionButtonAction(
    val id: String,
    val titleResId: Int,
    val iconResId: Int
) {
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
     * 粘贴剪贴板内容到当前光标位置
     */
    PASTE(
        id = "paste",
        titleResId = R.string.ext_btn_paste,
        iconResId = R.drawable.selection_background_toggle
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
        iconResId = R.drawable.backspace_toggle
    );

    companion object {
        /**
         * 从ID获取动作类型
         */
        fun fromId(id: String?): ExtensionButtonAction {
            return values().firstOrNull { it.id == id } ?: NONE
        }

        /**
         * 获取默认的4个按钮配置
         */
        fun getDefaults(): List<ExtensionButtonAction> {
            return listOf(SELECT, COPY, PASTE, NUMPAD)
        }
    }
}
