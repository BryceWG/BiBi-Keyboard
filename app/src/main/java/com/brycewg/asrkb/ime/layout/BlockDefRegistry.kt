/**
 * 自定义键盘布局可用按钮的集中注册表。
 *
 * 归属模块：ime/layout
 */
package com.brycewg.asrkb.ime.layout

import com.brycewg.asrkb.R
import com.brycewg.asrkb.ime.ExtensionButtonAction

class BlockDefRegistry private constructor(
    private val defsByPanel: Map<KeyboardLayoutPanel, List<BlockDef>>
) {
    fun defsFor(panel: KeyboardLayoutPanel): List<BlockDef> = defsByPanel[panel].orEmpty()

    fun allDefs(): List<BlockDef> = defsByPanel.values.flatten().distinctBy { it.id }

    fun get(panel: KeyboardLayoutPanel, id: String): BlockDef? = defsFor(panel).firstOrNull { it.id == id }

    fun get(id: String): BlockDef? = allDefs().firstOrNull { it.id == id }

    companion object {
        val default: BlockDefRegistry = BlockDefRegistry(
            mapOf(
                KeyboardLayoutPanel.Main to mainDefs(),
                KeyboardLayoutPanel.AiEdit to aiEditDefs(),
                KeyboardLayoutPanel.Recording to recordingDefs()
            )
        )

        private fun mainDefs(): List<BlockDef> = listOf(
            fixed("mic", R.string.cd_mic, R.drawable.microphone, R.id.groupMicStatus, standardSizes(), "2x2", required = true),
            fixed("status", R.string.keyboard_layout_block_status, null, R.id.btnExtCenter1, sizes("2x1", "3x1", "4x1", "5x1"), "3x1", required = true, viewKind = ButtonViewKind.Status),
            fixed("ai_edit", R.string.cd_ai_edit, R.drawable.pencil_simple_line, R.id.btnPromptPicker),
            fixed("postproc", R.string.cd_postproc_toggle, R.drawable.magic_wand, R.id.btnPostproc),
            fixed("clipboard", R.string.ext_btn_clipboard, R.drawable.clipboard_toggle, R.id.btnHide),
            fixed("backspace", R.string.cd_backspace, R.drawable.backspace_toggle, R.id.btnBackspace),
            fixed("settings", R.string.cd_settings, R.drawable.gear_toggle, R.id.btnSettings),
            fixed("prompt_picker", R.string.cd_prompt_picker, R.drawable.article_toggle, R.id.btnImeSwitcher),
            fixed("switch_ime", R.string.cd_switch_ime, R.drawable.keyboard_toggle, R.id.btnAiEdit),
            fixed("enter", R.string.cd_enter, R.drawable.key_return_toggle, R.id.btnEnter),
            fixed("numpad", R.string.cd_numpad, R.drawable.numpad_toggle, R.id.btnPunct1),
            fixed("punct_left", R.string.cd_punct_btn_2, null, R.id.btnPunct2, standardSizes(), "1x1", viewKind = ButtonViewKind.Punctuation),
            fixed("space", R.string.cd_space, null, R.id.btnExtCenter2, sizes("2x1", "3x1", "4x1", "5x1"), "3x1", viewKind = ButtonViewKind.Text),
            fixed("punct_right", R.string.cd_punct_btn_3, null, R.id.btnPunct3, standardSizes(), "1x1", viewKind = ButtonViewKind.Punctuation),
            fixed("vendor_picker", R.string.cd_vendor_picker, R.drawable.circles_four_toggle, R.id.btnPunct4)
        ) + aiPanelActionDefs() + extensionActionDefs()

        private fun aiEditDefs(): List<BlockDef> = listOf(
            fixed("ai_info", R.string.keyboard_layout_block_ai_info, null, R.id.aiEditInfoBar, sizes("2x1", "3x1", "4x1", "5x1"), "5x1", required = true, viewKind = ButtonViewKind.Status),
            fixed("ai_mic", R.string.cd_mic, R.drawable.microphone, R.id.btnAiPanelMic, standardSizes(), "2x2", required = true),
            fixed("ai_back", R.string.cd_return_main, R.drawable.arrow_u_up_left_toggle, R.id.btnAiPanelBack),
            fixed("ai_apply", R.string.cd_apply_preset_prompt, R.drawable.lightning_toggle, R.id.btnAiPanelApplyPreset),
            fixed("ai_select_all", R.string.cd_select_all, R.drawable.selection_all_toggle, R.id.btnAiPanelSelectAll),
            fixed("ai_delete", R.string.cd_backspace, R.drawable.backspace_toggle, R.id.btnAiPanelUndo),
            fixed("ai_cursor_left", R.string.cd_cursor_left, R.drawable.arrow_left_toggle, R.id.btnAiPanelCursorLeft),
            fixed("ai_cursor_right", R.string.cd_cursor_right, R.drawable.arrow_right_toggle, R.id.btnAiPanelCursorRight),
            fixed("ai_copy", R.string.cd_copy, R.drawable.copy_toggle, R.id.btnAiPanelCopy),
            fixed("ai_paste", R.string.cd_paste, R.drawable.selection_background_toggle, R.id.btnAiPanelPaste),
            fixed("ai_numpad", R.string.cd_numpad, R.drawable.numpad_toggle, R.id.btnAiPanelNumpad),
            fixed("ai_select", R.string.cd_select_toggle, R.drawable.selection_toggle, R.id.btnAiPanelSelect),
            fixed("ai_space", R.string.cd_space, null, R.id.btnAiPanelSpace, sizes("2x1", "3x1", "4x1", "5x1"), "3x1", viewKind = ButtonViewKind.Text),
            fixed("ai_move_start", R.string.cd_move_home, R.drawable.arrow_line_left_toggle, R.id.btnAiPanelMoveStart),
            fixed("ai_move_end", R.string.cd_move_end, R.drawable.arrow_line_right_toggle, R.id.btnAiPanelMoveEnd)
        ) + mainPanelActionDefs() + extensionActionDefs()

        private fun recordingDefs(): List<BlockDef> = listOf(
            fixed("gesture_cancel", R.string.label_recording_gesture_cancel, null, R.id.btnGestureCancel, standardSizes(), "2x2", viewKind = ButtonViewKind.Gesture),
            fixed("gesture_send", R.string.label_recording_gesture_send, null, R.id.btnGestureSend, standardSizes(), "2x2", viewKind = ButtonViewKind.Gesture),
            dynamic(RecordingLayoutButtons.TRANSLATE, R.string.keyboard_layout_recording_translate, null, standardSizes(), "2x2", viewKind = ButtonViewKind.Gesture),
            dynamic(RecordingLayoutButtons.SKIP_POLISH, R.string.keyboard_layout_skip_polish, null, standardSizes(), "2x2", viewKind = ButtonViewKind.Gesture),
            dynamic(RecordingLayoutButtons.PROMPT_POLISH, R.string.keyboard_layout_prompt_polish, null, standardSizes(), "2x2", viewKind = ButtonViewKind.Gesture).copy(maxInstances = Int.MAX_VALUE)
        )

        fun extensionDefId(action: ExtensionButtonAction): String = "ext_${action.id}"

        private fun mainPanelActionDefs(): List<BlockDef> = listOf(
            dynamic("mic", R.string.cd_mic, R.drawable.microphone, standardSizes(), "2x2"),
            dynamic("status", R.string.keyboard_layout_block_status, null, sizes("2x1", "3x1", "4x1", "5x1"), "3x1", viewKind = ButtonViewKind.Status),
            dynamic("ai_edit", R.string.cd_ai_edit, R.drawable.pencil_simple_line),
            dynamic("postproc", R.string.cd_postproc_toggle, R.drawable.magic_wand),
            dynamic("clipboard", R.string.ext_btn_clipboard, R.drawable.clipboard_toggle),
            dynamic("backspace", R.string.cd_backspace, R.drawable.backspace_toggle),
            dynamic("settings", R.string.cd_settings, R.drawable.gear_toggle),
            dynamic("prompt_picker", R.string.cd_prompt_picker, R.drawable.article_toggle),
            dynamic("switch_ime", R.string.cd_switch_ime, R.drawable.keyboard_toggle),
            dynamic("enter", R.string.cd_enter, R.drawable.key_return_toggle),
            dynamic("numpad", R.string.cd_numpad, R.drawable.numpad_toggle),
            dynamic("punct_left", R.string.cd_punct_btn_2, null, standardSizes(), "1x1", viewKind = ButtonViewKind.Punctuation),
            dynamic("space", R.string.cd_space, null, sizes("2x1", "3x1", "4x1", "5x1"), "3x1", viewKind = ButtonViewKind.Text),
            dynamic("punct_right", R.string.cd_punct_btn_3, null, standardSizes(), "1x1", viewKind = ButtonViewKind.Punctuation),
            dynamic("vendor_picker", R.string.cd_vendor_picker, R.drawable.circles_four_toggle)
        )

        private fun aiPanelActionDefs(): List<BlockDef> = listOf(
            dynamic("ai_back", R.string.cd_return_main, R.drawable.arrow_u_up_left_toggle),
            dynamic("ai_apply", R.string.cd_apply_preset_prompt, R.drawable.lightning_toggle),
            dynamic("ai_select_all", R.string.cd_select_all, R.drawable.selection_all_toggle),
            dynamic("ai_delete", R.string.cd_backspace, R.drawable.backspace_toggle),
            dynamic("ai_cursor_left", R.string.cd_cursor_left, R.drawable.arrow_left_toggle),
            dynamic("ai_cursor_right", R.string.cd_cursor_right, R.drawable.arrow_right_toggle),
            dynamic("ai_copy", R.string.cd_copy, R.drawable.copy_toggle),
            dynamic("ai_paste", R.string.cd_paste, R.drawable.selection_background_toggle),
            dynamic("ai_numpad", R.string.cd_numpad, R.drawable.numpad_toggle),
            dynamic("ai_select", R.string.cd_select_toggle, R.drawable.selection_toggle),
            dynamic("ai_space", R.string.cd_space, null, sizes("2x1", "3x1", "4x1", "5x1"), "3x1", viewKind = ButtonViewKind.Text),
            dynamic("ai_move_start", R.string.cd_move_home, R.drawable.arrow_line_left_toggle),
            dynamic("ai_move_end", R.string.cd_move_end, R.drawable.arrow_line_right_toggle)
        )

        private fun extensionActionDefs(): List<BlockDef> = ExtensionButtonAction.values()
            .filter { it != ExtensionButtonAction.NONE }
            .map { action ->
                BlockDef(
                    id = extensionDefId(action),
                    labelRes = action.titleResId,
                    iconRes = action.iconResId,
                    viewKind = ButtonViewKind.External,
                    allowedSizes = standardSizes(),
                    defaultSize = size("1x1"),
                    maxInstances = 1,
                    extensionActionId = action.id
                )
            }

        private fun fixed(
            id: String,
            labelRes: Int,
            iconRes: Int?,
            viewId: Int,
            allowedSizes: List<BlockSize> = standardSizes(),
            defaultSize: String = "1x1",
            required: Boolean = false,
            viewKind: ButtonViewKind = ButtonViewKind.Icon
        ): BlockDef = BlockDef(
            id = id,
            labelRes = labelRes,
            iconRes = iconRes,
            viewKind = viewKind,
            allowedSizes = allowedSizes,
            defaultSize = size(defaultSize),
            required = required,
            viewId = viewId
        )

        private fun dynamic(
            id: String,
            labelRes: Int,
            iconRes: Int?,
            allowedSizes: List<BlockSize> = standardSizes(),
            defaultSize: String = "1x1",
            required: Boolean = false,
            viewKind: ButtonViewKind = ButtonViewKind.Icon
        ): BlockDef = BlockDef(
            id = id,
            labelRes = labelRes,
            iconRes = iconRes,
            viewKind = viewKind,
            allowedSizes = allowedSizes,
            defaultSize = size(defaultSize),
            required = required
        )

        private fun sizes(vararg values: String): List<BlockSize> = values.map(::size)

        private fun standardSizes(): List<BlockSize> = sizes(
            "1x1",
            "1x2",
            "2x1",
            "2x2",
            "3x1",
            "3x2",
            "4x1",
            "4x2",
            "4x3",
            "4x4"
        )

        private fun size(value: String): BlockSize {
            val parts = value.split("x")
            return BlockSize(parts[0].toFloat(), parts[1].toFloat())
        }
    }
}
