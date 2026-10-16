package com.brycewg.asrkb.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.annotation.StringRes
import androidx.appcompat.widget.PopupMenu
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs

internal class AiEditPanelController(
    private val context: Context,
    private val prefs: Prefs,
    private val views: ImeViewRefs,
    private val inputHelper: InputConnectionHelper,
    private val actionHandler: KeyboardActionHandler,
    private val backspaceGestureHandler: BackspaceGestureHandler,
    private val performKeyHaptic: (View?) -> Unit,
    private val showAiEditHint: (String) -> Unit,
    private val showPopupMenuKeepingIme: (PopupMenu) -> Unit,
    private val inputConnectionProvider: () -> android.view.inputmethod.InputConnection?,
    private val onRequestShowNumpad: (returnToAiPanel: Boolean) -> Unit
) {
    var isVisible: Boolean = views.layoutAiEditPanel?.visibility == View.VISIBLE
        private set

    private var selectMode: Boolean = false
    private var selectAnchor: Int? = null
    private var lastSelStart: Int = -1
    private var lastSelEnd: Int = -1

    private var repeatLeftRunnable: Runnable? = null
    private var repeatRightRunnable: Runnable? = null
    private var undoHintRunnable: Runnable? = null
    private var leftHintRunnable: Runnable? = null
    private var rightHintRunnable: Runnable? = null

    fun bindListeners() {
        // AI 编辑面板返回按钮
        views.btnAiEditPanelBack?.setOnClickListener { v ->
            performKeyHaptic(v)
            hide()
        }

        // AI 编辑面板：应用预设 Prompt 并处理文本
        views.btnAiPanelApplyPreset?.setOnClickListener { v ->
            performKeyHaptic(v)
            showPromptPickerForApply(v)
        }

        // AI 编辑面板：空格键
        views.btnAiPanelSpace?.setOnClickListener { v ->
            performKeyHaptic(v)
            val state = actionHandler.getCurrentState()
            if (state is KeyboardState.Listening ||
                state is KeyboardState.AiEditListening
            ) {
                return@setOnClickListener
            }
            actionHandler.commitText(inputConnectionProvider(), " ")
        }

        // AI 编辑面板：光标/选择移动
        views.btnAiPanelCursorLeft?.setOnClickListener { v ->
            performKeyHaptic(v)
            moveCursorBy(-1)
        }
        views.btnAiPanelCursorRight?.setOnClickListener { v ->
            performKeyHaptic(v)
            moveCursorBy(1)
        }
        views.btnAiPanelMoveStart?.setOnClickListener { v ->
            performKeyHaptic(v)
            moveCursorToEdge(toStart = true)
        }
        views.btnAiPanelMoveEnd?.setOnClickListener { v ->
            performKeyHaptic(v)
            moveCursorToEdge(toStart = false)
        }

        // AI 编辑面板：选择开关/全选
        views.btnAiPanelSelect?.setOnClickListener { v ->
            performKeyHaptic(v)
            toggleSelectionMode()
        }
        views.btnAiPanelSelectAll?.setOnClickListener { v ->
            performKeyHaptic(v)
            selectAllText()
        }

        // AI 编辑面板：复制/粘贴/退格（带主键盘同款手势）
        views.btnAiPanelCopy?.setOnClickListener { v ->
            performKeyHaptic(v)
            handleCopyAction()
        }
        views.btnAiPanelPaste?.setOnClickListener { v ->
            performKeyHaptic(v)
            handlePasteAction()
        }
        // 点按退格（注：手势由 onTouch 托管，onClick 多为兜底）
        views.btnAiPanelUndo?.setOnClickListener { v ->
            performKeyHaptic(v)
            inputHelper.sendBackspace(inputConnectionProvider())
        }

        // AI 编辑面板：数字小键盘
        views.btnAiPanelNumpad?.setOnClickListener { v ->
            performKeyHaptic(v)
            onRequestShowNumpad(true)
        }

        bindLongPressHints()
        setupCursorRepeatHandlers()
        applySelectionUi()
    }

    fun show() {
        if (isVisible) {
            views.groupMicStatus?.visibility = View.GONE
            return
        }
        // 进入面板时重置选择模式
        resetSelectionMode()
        val mainHeight = views.layoutMainKeyboard?.height
        views.layoutMainKeyboard?.visibility = View.GONE
        views.groupMicStatus?.visibility = View.GONE
        val panel = views.layoutAiEditPanel
        if (panel != null) {
            if (mainHeight != null && mainHeight > 0) {
                val lp = panel.layoutParams
                lp.height = mainHeight
                panel.layoutParams = lp
            }
            panel.visibility = View.VISIBLE
        }
        isVisible = true
    }

    fun hide() {
        val panel = views.layoutAiEditPanel
        if (panel != null) {
            panel.visibility = View.GONE
            val lp = panel.layoutParams
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
            panel.layoutParams = lp
        }
        views.layoutMainKeyboard?.visibility = View.VISIBLE
        views.groupMicStatus?.visibility = View.VISIBLE
        isVisible = false
        resetSelectionMode()
        releaseCursorRepeatCallbacks()
    }

    fun resetSelectionState() {
        resetSelectionMode()
        lastSelStart = -1
        lastSelEnd = -1
        releaseCursorRepeatCallbacks()
    }

    fun onSelectionChanged(newSelStart: Int, newSelEnd: Int) {
        lastSelStart = newSelStart
        lastSelEnd = newSelEnd
    }

    fun isSelectionModeEnabled(): Boolean = selectMode

    fun applySelectExtButtonsUi() {
        // 旧四槽扩展按钮已不再创建；保留入口给过渡期控制器调用。
    }

    fun toggleSelectionMode() {
        selectMode = !selectMode
        if (selectMode) {
            selectAnchor = null
            ensureAnchorForSelection()
        } else {
            selectAnchor = null
        }
        applySelectionUi()
    }

    fun moveCursorBy(delta: Int) {
        val ic = inputConnectionProvider() ?: return
        if (delta == 0) return
        val maxLen = totalTextLength() ?: Int.MAX_VALUE

        if (!selectMode) {
            val pos = currentCursorPosition() ?: return
            val newPos = (pos + delta).coerceIn(0, maxLen)
            inputHelper.setSelection(ic, newPos, newPos)
            return
        }

        ensureAnchorForSelection()
        val anchor = selectAnchor ?: 0
        val selStart = lastSelStart
        val selEnd = lastSelEnd

        val activeNow: Int = if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
            if (anchor == selStart) selEnd else selStart
        } else {
            currentCursorPosition() ?: anchor
        }

        val step = if (delta < 0) -1 else 1
        val newActive = (activeNow + step).coerceIn(0, maxLen)
        val start = minOf(anchor, newActive)
        val end = maxOf(anchor, newActive)
        inputHelper.setSelection(ic, start, end)
    }

    private fun moveCursorToEdge(toStart: Boolean) {
        val ic = inputConnectionProvider() ?: return
        val newPos = if (toStart) 0 else (totalTextLength() ?: Int.MAX_VALUE)
        if (selectMode) {
            ensureAnchorForSelection()
            val anchor = selectAnchor ?: 0
            val start = minOf(anchor, newPos)
            val end = maxOf(anchor, newPos)
            inputHelper.setSelection(ic, start, end)
        } else {
            inputHelper.setSelection(ic, newPos, newPos)
        }
    }

    private fun selectAllText() {
        val ic = inputConnectionProvider() ?: return
        inputHelper.selectAll(ic)
        resetSelectionMode()
    }

    private fun handleCopyAction() {
        val ic = inputConnectionProvider() ?: return
        val ok = ic.performContextMenuAction(android.R.id.copy)
        if (!ok) {
            val selected = inputHelper.getSelectedText(ic, 0)?.toString()
            if (!selected.isNullOrEmpty()) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("ASR Copy", selected))
            }
        }

        val selected = inputHelper.getSelectedText(ic, 0)?.toString()
        val text = if (!selected.isNullOrEmpty()) {
            selected
        } else {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        }
        if (!text.isNullOrEmpty()) {
            actionHandler.showClipboardPreview(text)
        }
    }

    private fun handlePasteAction() {
        val ic = inputConnectionProvider() ?: return
        actionHandler.saveUndoSnapshot(ic)
        val ok = ic.performContextMenuAction(android.R.id.paste)
        if (!ok) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!text.isNullOrEmpty()) {
                inputHelper.commitText(ic, text)
            }
        }
    }

    fun showPromptPickerForApply(anchor: View) {
        val presets = prefs.getPromptPresets()
        if (presets.isEmpty()) return
        val popup = PopupMenu(anchor.context, anchor)
        presets.forEachIndexed { idx, p ->
            popup.menu.add(0, idx, idx, p.title)
        }
        popup.setOnMenuItemClickListener { mi ->
            val position = mi.itemId
            val preset = presets.getOrNull(position) ?: return@setOnMenuItemClickListener false
            actionHandler.applyPromptToSelectionOrAll(
                inputConnectionProvider(),
                promptContent = preset.content,
                promptTitle = preset.title
            )
            true
        }
        showPopupMenuKeepingIme(popup)
    }

    private fun setupCursorRepeatHandlers() {
        val initialDelay = 350L
        val repeatInterval = 50L
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

        views.btnAiPanelCursorLeft?.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    performKeyHaptic(v)
                    moveCursorBy(-1)
                    leftHintRunnable?.let { v.removeCallbacks(it) }
                    val hint =
                        Runnable { showAiEditHint(context.getString(R.string.cd_cursor_left)) }
                    leftHintRunnable = hint
                    v.postDelayed(hint, longPressTimeout)
                    repeatLeftRunnable?.let { v.removeCallbacks(it) }
                    val r = Runnable {
                        moveCursorBy(-1)
                        repeatLeftRunnable?.let { v.postDelayed(it, repeatInterval) }
                    }
                    repeatLeftRunnable = r
                    v.postDelayed(r, initialDelay)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    leftHintRunnable?.let { v.removeCallbacks(it) }
                    leftHintRunnable = null
                    repeatLeftRunnable?.let { v.removeCallbacks(it) }
                    repeatLeftRunnable = null
                    true
                }
                else -> false
            }
        }

        views.btnAiPanelCursorRight?.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    performKeyHaptic(v)
                    moveCursorBy(1)
                    rightHintRunnable?.let { v.removeCallbacks(it) }
                    val hint =
                        Runnable { showAiEditHint(context.getString(R.string.cd_cursor_right)) }
                    rightHintRunnable = hint
                    v.postDelayed(hint, longPressTimeout)
                    repeatRightRunnable?.let { v.removeCallbacks(it) }
                    val r = Runnable {
                        moveCursorBy(1)
                        repeatRightRunnable?.let { v.postDelayed(it, repeatInterval) }
                    }
                    repeatRightRunnable = r
                    v.postDelayed(r, initialDelay)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    rightHintRunnable?.let { v.removeCallbacks(it) }
                    rightHintRunnable = null
                    repeatRightRunnable?.let { v.removeCallbacks(it) }
                    repeatRightRunnable = null
                    true
                }
                else -> false
            }
        }
    }

    private fun releaseCursorRepeatCallbacks() {
        leftHintRunnable?.let { views.btnAiPanelCursorLeft?.removeCallbacks(it) }
        leftHintRunnable = null
        repeatLeftRunnable?.let { views.btnAiPanelCursorLeft?.removeCallbacks(it) }
        repeatLeftRunnable = null
        rightHintRunnable?.let { views.btnAiPanelCursorRight?.removeCallbacks(it) }
        rightHintRunnable = null
        repeatRightRunnable?.let { views.btnAiPanelCursorRight?.removeCallbacks(it) }
        repeatRightRunnable = null
        undoHintRunnable?.let { views.btnAiPanelUndo?.removeCallbacks(it) }
        undoHintRunnable = null
    }

    private fun currentCursorPosition(): Int? {
        val ic = inputConnectionProvider() ?: return null
        val selStart = lastSelStart
        val selEnd = lastSelEnd
        if (selStart >= 0 && selEnd >= 0) {
            val anchor = selectAnchor
            if (selectMode && anchor != null && selStart != selEnd) {
                return if (anchor == selStart) selEnd else selStart
            }
            return selEnd
        }
        return inputHelper.getTextBeforeCursor(ic, 10000)?.length
    }

    private fun totalTextLength(): Int? {
        val ic = inputConnectionProvider() ?: return null
        val before = inputHelper.getTextBeforeCursor(ic, 10000)?.length ?: 0
        val after = inputHelper.getTextAfterCursor(ic, 10000)?.length ?: 0
        return before + after
    }

    private fun ensureAnchorForSelection() {
        if (!selectMode) return
        if (selectAnchor != null) return
        val ic = inputConnectionProvider() ?: return
        if (lastSelStart >= 0 && lastSelEnd >= 0 && lastSelStart != lastSelEnd) {
            selectAnchor = minOf(lastSelStart, lastSelEnd)
            return
        }
        val beforeLen = inputHelper.getTextBeforeCursor(ic, 10000)?.length ?: 0
        selectAnchor = beforeLen
    }

    private fun resetSelectionMode() {
        selectMode = false
        selectAnchor = null
        applySelectionUi()
    }

    private fun applySelectionUi() {
        views.btnAiPanelSelect?.isSelected = selectMode
        views.btnAiPanelSelect?.setImageResource(
            if (selectMode) R.drawable.selection_fill else R.drawable.selection_toggle
        )
        applySelectExtButtonsUi()
    }

    private fun bindLongPressHints() {
        registerLongPressHint(views.btnAiEditPanelBack, R.string.cd_return_main)
        registerLongPressHint(views.btnAiPanelApplyPreset, R.string.cd_apply_preset_prompt)
        registerLongPressHint(views.btnAiPanelSpace, R.string.cd_space)
        registerLongPressHint(views.btnAiPanelMoveStart, R.string.cd_move_home)
        registerLongPressHint(views.btnAiPanelMoveEnd, R.string.cd_move_end)
        registerLongPressHint(views.btnAiPanelSelect, R.string.cd_select_toggle)
        registerLongPressHint(views.btnAiPanelSelectAll, R.string.cd_select_all)
        registerLongPressHint(views.btnAiPanelCopy, R.string.cd_copy)
        registerLongPressHint(views.btnAiPanelPaste, R.string.cd_paste)
        registerLongPressHint(views.btnAiPanelNumpad, R.string.cd_numpad)
        bindUndoTouchHint()
    }

    private fun registerLongPressHint(view: View?, @StringRes hintRes: Int) {
        view?.setOnLongClickListener { v ->
            performKeyHaptic(v)
            showAiEditHint(context.getString(hintRes))
            true
        }
    }

    private fun bindUndoTouchHint() {
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
        views.btnAiPanelUndo?.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    undoHintRunnable?.let { v.removeCallbacks(it) }
                    val hint = Runnable { showAiEditHint(context.getString(R.string.cd_backspace)) }
                    undoHintRunnable = hint
                    v.postDelayed(hint, longPressTimeout)
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    undoHintRunnable?.let { v.removeCallbacks(it) }
                    undoHintRunnable = null
                }
            }
            backspaceGestureHandler.handleTouchEvent(v, event, inputConnectionProvider())
        }
    }
}
