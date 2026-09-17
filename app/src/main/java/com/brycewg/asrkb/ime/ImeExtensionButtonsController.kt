package com.brycewg.asrkb.ime

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ime.layout.BlockDef
import com.brycewg.asrkb.ime.layout.BlockDefRegistry
import com.brycewg.asrkb.ime.layout.KeyboardLayoutPanel
import com.brycewg.asrkb.ime.layout.KeyboardLayoutViewTags
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.widgets.PunctKeyView

internal class ImeExtensionButtonsController(
    private val prefs: Prefs,
    private val views: ImeViewRefs,
    private val inputHelper: InputConnectionHelper,
    private val actionHandler: KeyboardActionHandler,
    private val inputConnectionProvider: () -> android.view.inputmethod.InputConnection?,
    private val editorInfoProvider: () -> EditorInfo?,
    private val performKeyHaptic: (View?) -> Unit,
    private val checkAsrReady: () -> Boolean,
    private val moveCursorBy: (Int) -> Unit,
    private val toggleSelectionMode: () -> Unit,
    private val isSelectionModeEnabled: () -> Boolean,
    private val updateSelectExtButtonsUi: () -> Unit,
    private val showAiEditPanel: () -> Unit,
    private val hideAiEditPanel: () -> Unit,
    private val showNumpadPanel: () -> Unit,
    private val showNumpadPanelFromAi: () -> Unit,
    private val showClipboardPanel: () -> Unit,
    private val showAsrHistoryPanel: () -> Unit,
    private val hideKeyboardPanel: () -> Unit,
    private val openSettings: () -> Unit,
    private val showPromptPicker: (View) -> Unit,
    private val showPromptPickerForApply: (View) -> Unit,
    private val showVendorPicker: (View) -> Unit,
    private val onImeSwitchButtonClicked: () -> Unit
) {
    fun bindListeners() {
        // 中央扩展按钮（占位，暂无功能）
        views.btnExtCenter1?.setOnClickListener { v ->
            performKeyHaptic(v)
            // TODO: 添加具体功能
        }
        views.btnExtCenter2?.setOnClickListener { v ->
            performKeyHaptic(v)
            if (actionHandler.getCurrentState() !is KeyboardState.Listening) {
                actionHandler.commitText(inputConnectionProvider(), " ")
            }
        }
        bindDynamicLayoutActionButtons(views.rootView)
    }

    fun applyConfig() {
        updateSelectExtButtonsUi()
        updateDynamicSelectButtons()
        updateSilenceAutoStopExtButtonsUi()
        updateKeepScreenOnWhileRecordingExtButtonsUi()
        updateMicTapToggleExtButtonsUi()
        updateFloatingKeyboardExtButtonsUi()
        updateAutoEnterAfterAsrExtButtonsUi()
    }

    private fun bindDynamicLayoutActionButtons(root: View) {
        if (KeyboardLayoutViewTags.isDynamicBlockView(root)) {
            bindDynamicLayoutActionButton(root)
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                bindDynamicLayoutActionButtons(root.getChildAt(i))
            }
        }
    }

    private fun bindDynamicLayoutActionButton(view: View) {
        val panel = KeyboardLayoutViewTags.panelOf(view) ?: return
        val defId = KeyboardLayoutViewTags.defIdOf(view) ?: return
        val def = BlockDefRegistry.default.get(panel, defId) ?: BlockDefRegistry.default.get(defId) ?: return
        def.extensionActionId
            ?.let(ExtensionButtonAction::fromId)
            ?.takeIf { it != ExtensionButtonAction.NONE }
            ?.let { action ->
                bindExtensionActionButton(view, action, panel)
                return
            }

        view.setOnClickListener(null)
        view.setOnTouchListener(null)
        if (view is PunctKeyView) {
            when (def.id) {
                "punct_left" -> view.setTexts(prefs.punct1, prefs.punct2)
                "punct_right" -> view.setTexts(prefs.punct3, prefs.punct4)
            }
        }
        when (def.id) {
            "ai_cursor_left" -> setupCursorButtonRepeat(view, ExtensionButtonAction.CURSOR_LEFT)
            "ai_cursor_right" -> setupCursorButtonRepeat(view, ExtensionButtonAction.CURSOR_RIGHT)
            else -> view.setOnClickListener { v ->
                performKeyHaptic(v)
                handleLayoutBlockAction(def, panel, v)
            }
        }
    }

    private fun bindExtensionActionButton(
        view: View,
        action: ExtensionButtonAction,
        panel: KeyboardLayoutPanel?
    ) {
        if (view is ImageButton) {
            view.setImageResource(action.iconResId)
        }
        view.setOnClickListener(null)
        view.setOnTouchListener(null)
        view.isSelected = false
        when (action) {
            ExtensionButtonAction.CURSOR_LEFT,
            ExtensionButtonAction.CURSOR_RIGHT -> setupCursorButtonRepeat(view, action)
            else -> view.setOnClickListener { v ->
                performKeyHaptic(v)
                handleExtensionButtonAction(action, panel)
            }
        }
    }

    private fun handleLayoutBlockAction(def: BlockDef, panel: KeyboardLayoutPanel, source: View) {
        when (def.id) {
            "mic", "ai_mic" -> {
                if (checkAsrReady()) {
                    actionHandler.handleMicTapToggle()
                }
            }
            "status" -> actionHandler.handleInfoBarClick()
            "ai_info" -> actionHandler.handleInfoBarClick()
            "ai_edit" -> showAiEditPanel()
            "ai_back" -> hideAiEditPanel()
            "ai_apply" -> showPromptPickerForApply(source)
            "postproc" -> actionHandler.handlePostprocessToggle()
            "clipboard" -> showClipboardPanel()
            "backspace", "ai_delete" -> {
                actionHandler.saveUndoSnapshot(inputConnectionProvider())
                inputHelper.sendBackspace(inputConnectionProvider())
            }
            "settings" -> openSettings()
            "prompt_picker" -> showPromptPicker(source)
            "switch_ime" -> onImeSwitchButtonClicked()
            "enter" -> inputHelper.sendEnter(inputConnectionProvider(), editorInfoProvider())
            "numpad", "ai_numpad" -> showNumpadForPanel(panel)
            "punct_left" -> actionHandler.commitText(inputConnectionProvider(), prefs.punct1)
            "punct_right" -> actionHandler.commitText(inputConnectionProvider(), prefs.punct3)
            "space", "ai_space" -> commitSpaceIfAllowed()
            "vendor_picker" -> showVendorPicker(source)
            "ai_select" -> handleExtensionButtonAction(ExtensionButtonAction.SELECT, panel)
            "ai_select_all" -> handleExtensionButtonAction(ExtensionButtonAction.SELECT_ALL, panel)
            "ai_copy" -> handleExtensionButtonAction(ExtensionButtonAction.COPY, panel)
            "ai_paste" -> handleExtensionButtonAction(ExtensionButtonAction.PASTE, panel)
            "ai_move_start" -> handleExtensionButtonAction(ExtensionButtonAction.MOVE_START, panel)
            "ai_move_end" -> handleExtensionButtonAction(ExtensionButtonAction.MOVE_END, panel)
        }
    }

    private fun showNumpadForPanel(panel: KeyboardLayoutPanel?) {
        if (panel == KeyboardLayoutPanel.AiEdit) {
            showNumpadPanelFromAi()
        } else {
            showNumpadPanel()
        }
    }

    private fun commitSpaceIfAllowed() {
        val state = actionHandler.getCurrentState()
        if (state is KeyboardState.Listening || state is KeyboardState.AiEditListening) return
        actionHandler.commitText(inputConnectionProvider(), " ")
    }

    private fun handleExtensionButtonAction(action: ExtensionButtonAction, sourcePanel: KeyboardLayoutPanel?) {
        val result = actionHandler.handleExtensionButtonClick(action, inputConnectionProvider())

        when (result) {
            KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS -> {
                // 成功，不需要额外处理
            }

            KeyboardActionHandler.ExtensionButtonActionResult.FAILED -> {
                // 失败，已在 actionHandler 中处理
            }

            KeyboardActionHandler.ExtensionButtonActionResult.NEED_TOGGLE_SELECTION -> {
                // 在主界面直接切换选择模式（不进入 AI 编辑面板）
                toggleSelectionMode()
                // 同步扩展按钮（若配置为 SELECT）
                updateSelectExtButtonsUi()
                updateDynamicSelectButtons()
            }

            KeyboardActionHandler.ExtensionButtonActionResult.NEED_SHOW_NUMPAD -> {
                // 从主界面进入数字/符号面板
                showNumpadForPanel(sourcePanel)
            }

            KeyboardActionHandler.ExtensionButtonActionResult.NEED_SHOW_CLIPBOARD -> {
                showClipboardPanel()
            }

            KeyboardActionHandler.ExtensionButtonActionResult.NEED_SHOW_ASR_HISTORY -> {
                showAsrHistoryPanel()
            }

            KeyboardActionHandler.ExtensionButtonActionResult.NEED_CURSOR_LEFT,
            KeyboardActionHandler.ExtensionButtonActionResult.NEED_CURSOR_RIGHT -> {
                // 光标移动已在长按处理中完成
            }

            KeyboardActionHandler.ExtensionButtonActionResult.NEED_HIDE_KEYBOARD -> {
                hideKeyboardPanel()
            }

            KeyboardActionHandler.ExtensionButtonActionResult.NEED_TOGGLE_CONTINUOUS_TALK -> {
                applyConfig()
            }
        }

        if (action == ExtensionButtonAction.SILENCE_AUTOSTOP_TOGGLE &&
            result == KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
        ) {
            updateSilenceAutoStopExtButtonsUi()
        }
        if (action == ExtensionButtonAction.KEEP_SCREEN_ON_WHILE_RECORDING_TOGGLE &&
            result == KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
        ) {
            updateKeepScreenOnWhileRecordingExtButtonsUi()
        }
        if (action == ExtensionButtonAction.MIC_TAP_TOGGLE &&
            result == KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
        ) {
            updateMicTapToggleExtButtonsUi()
        }
        if (action == ExtensionButtonAction.FLOATING_KEYBOARD_TOGGLE &&
            result == KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
        ) {
            updateFloatingKeyboardExtButtonsUi()
        }
        if (action == ExtensionButtonAction.AUTO_ENTER_AFTER_ASR_TOGGLE &&
            result == KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
        ) {
            updateAutoEnterAfterAsrExtButtonsUi()
        }
    }

    private fun setupCursorButtonRepeat(btn: View, action: ExtensionButtonAction) {
        val initialDelay = 350L
        val repeatInterval = 50L
        var repeatRunnable: Runnable? = null

        btn.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    performKeyHaptic(v)
                    // 立即移动一次
                    val delta = if (action == ExtensionButtonAction.CURSOR_LEFT) -1 else 1
                    moveCursorBy(delta)

                    // 设置连发
                    repeatRunnable?.let { v.removeCallbacks(it) }
                    val r = Runnable {
                        moveCursorBy(delta)
                        repeatRunnable?.let { v.postDelayed(it, repeatInterval) }
                    }
                    repeatRunnable = r
                    v.postDelayed(r, initialDelay)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    repeatRunnable?.let { v.removeCallbacks(it) }
                    repeatRunnable = null
                    v.performClick()
                    true
                }

                else -> false
            }
        }
    }

    private fun updateSilenceAutoStopExtButtonsUi() {
        updateDynamicToggleButtons(ExtensionButtonAction.SILENCE_AUTOSTOP_TOGGLE) { enabled ->
            if (enabled) R.drawable.hand_palm_fill else R.drawable.hand_palm
        }
    }

    private fun updateKeepScreenOnWhileRecordingExtButtonsUi() {
        updateDynamicToggleButtons(ExtensionButtonAction.KEEP_SCREEN_ON_WHILE_RECORDING_TOGGLE) { enabled ->
            if (enabled) R.drawable.lightbulb_filament_fill else R.drawable.lightbulb_filament
        }
    }

    private fun updateMicTapToggleExtButtonsUi() {
        updateDynamicToggleButtons(ExtensionButtonAction.MIC_TAP_TOGGLE) { enabled ->
            if (enabled) R.drawable.hand_pointing else R.drawable.hand_pointing_fill
        }
    }

    private fun updateFloatingKeyboardExtButtonsUi() {
        updateDynamicToggleButtons(ExtensionButtonAction.FLOATING_KEYBOARD_TOGGLE) { enabled ->
            if (enabled) R.drawable.arrow_square_in_fill else R.drawable.arrow_square_in
        }
    }

    private fun updateAutoEnterAfterAsrExtButtonsUi() {
        updateDynamicToggleButtons(ExtensionButtonAction.AUTO_ENTER_AFTER_ASR_TOGGLE) { enabled ->
            if (enabled) R.drawable.arrow_square_up_fill else R.drawable.arrow_square_up
        }
    }

    private fun updateDynamicSelectButtons() {
        updateDynamicButtons(views.rootView) { view, def ->
            val isSelect = def.extensionActionId == ExtensionButtonAction.SELECT.id || def.id == "ai_select"
            if (!isSelect) return@updateDynamicButtons
            val enabled = isSelectionModeEnabled()
            (view as? ImageButton)?.setImageResource(
                if (enabled) R.drawable.selection_fill else R.drawable.selection_toggle
            )
            view.isSelected = enabled
        }
    }

    private fun updateDynamicToggleButtons(
        action: ExtensionButtonAction,
        iconRes: (Boolean) -> Int
    ) {
        val enabled = when (action) {
            ExtensionButtonAction.SILENCE_AUTOSTOP_TOGGLE -> prefs.autoStopOnSilenceEnabled
            ExtensionButtonAction.KEEP_SCREEN_ON_WHILE_RECORDING_TOGGLE -> prefs.keepScreenOnWhileRecording
            ExtensionButtonAction.MIC_TAP_TOGGLE -> prefs.micTapToggleEnabled
            ExtensionButtonAction.FLOATING_KEYBOARD_TOGGLE -> prefs.imeTabletFloatingKeyboardEnabled
            ExtensionButtonAction.AUTO_ENTER_AFTER_ASR_TOGGLE -> prefs.autoEnterAfterAsrEnabled
            else -> return
        }
        updateDynamicButtons(views.rootView) { view, def ->
            if (def.extensionActionId != action.id) return@updateDynamicButtons
            (view as? ImageButton)?.setImageResource(iconRes(enabled))
            view.isSelected = enabled
        }
    }

    private fun updateDynamicButtons(root: View, block: (View, BlockDef) -> Unit) {
        if (KeyboardLayoutViewTags.isDynamicBlockView(root)) {
            val panel = KeyboardLayoutViewTags.panelOf(root)
            val defId = KeyboardLayoutViewTags.defIdOf(root)
            val def = if (panel != null && defId != null) {
                BlockDefRegistry.default.get(panel, defId) ?: BlockDefRegistry.default.get(defId)
            } else {
                null
            }
            if (def != null) {
                block(root, def)
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                updateDynamicButtons(root.getChildAt(i), block)
            }
        }
    }
}
