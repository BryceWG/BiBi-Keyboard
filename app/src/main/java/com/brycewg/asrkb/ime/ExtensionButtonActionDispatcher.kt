/**
 * 扩展按钮动作分发与处理。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.inputmethod.InputConnection
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs

internal class ExtensionButtonActionDispatcher(
    private val context: Context,
    private val prefs: Prefs,
    private val inputHelper: InputConnectionHelper,
    private val uiListenerProvider: () -> KeyboardActionHandler.UiListener?,
    private val handleUndo: (InputConnection) -> Boolean,
    private val logTag: String
) {
    fun dispatch(
        action: ExtensionButtonAction,
        ic: InputConnection?
    ): KeyboardActionHandler.ExtensionButtonActionResult = when (action) {
        ExtensionButtonAction.NONE -> KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
        ExtensionButtonAction.SELECT -> KeyboardActionHandler.ExtensionButtonActionResult.NEED_TOGGLE_SELECTION
        ExtensionButtonAction.SELECT_ALL -> selectAll(ic)
        ExtensionButtonAction.COPY -> copy(ic)
        ExtensionButtonAction.ASR_HISTORY -> KeyboardActionHandler.ExtensionButtonActionResult.NEED_SHOW_ASR_HISTORY
        ExtensionButtonAction.PASTE -> paste(ic)
        ExtensionButtonAction.CURSOR_LEFT -> KeyboardActionHandler.ExtensionButtonActionResult.NEED_CURSOR_LEFT
        ExtensionButtonAction.CURSOR_RIGHT -> KeyboardActionHandler.ExtensionButtonActionResult.NEED_CURSOR_RIGHT
        ExtensionButtonAction.MOVE_PREV_PUNCT -> moveToPunctuation(ic, PunctuationJumpDirection.Previous)
        ExtensionButtonAction.MOVE_NEXT_PUNCT -> moveToPunctuation(ic, PunctuationJumpDirection.Next)
        ExtensionButtonAction.MOVE_START -> moveStart(ic)
        ExtensionButtonAction.MOVE_END -> moveEnd(ic)
        ExtensionButtonAction.NUMPAD -> KeyboardActionHandler.ExtensionButtonActionResult.NEED_SHOW_NUMPAD
        ExtensionButtonAction.CLIPBOARD -> KeyboardActionHandler.ExtensionButtonActionResult.NEED_SHOW_CLIPBOARD
        ExtensionButtonAction.SILENCE_AUTOSTOP_TOGGLE -> toggleSilenceAutoStop()
        ExtensionButtonAction.KEEP_SCREEN_ON_WHILE_RECORDING_TOGGLE -> toggleKeepScreenOnWhileRecording()
        ExtensionButtonAction.MIC_TAP_TOGGLE -> toggleMicTapMode()
        ExtensionButtonAction.FLOATING_KEYBOARD_TOGGLE -> toggleFloatingKeyboard()
        ExtensionButtonAction.AUTO_ENTER_AFTER_ASR_TOGGLE -> toggleAutoEnterAfterAsr()
        ExtensionButtonAction.UNDO -> undo(ic)
        ExtensionButtonAction.HIDE_KEYBOARD -> KeyboardActionHandler.ExtensionButtonActionResult.NEED_HIDE_KEYBOARD
    }

    private fun selectAll(ic: InputConnection?): KeyboardActionHandler.ExtensionButtonActionResult {
        if (ic == null) return KeyboardActionHandler.ExtensionButtonActionResult.FAILED
        return try {
            ic.performContextMenuAction(android.R.id.selectAll)
            KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
        } catch (t: Throwable) {
            Log.w(logTag, "SELECT_ALL failed", t)
            KeyboardActionHandler.ExtensionButtonActionResult.FAILED
        }
    }

    private fun copy(ic: InputConnection?): KeyboardActionHandler.ExtensionButtonActionResult {
        if (ic == null) return KeyboardActionHandler.ExtensionButtonActionResult.FAILED
        return try {
            ic.performContextMenuAction(android.R.id.copy)
            uiListenerProvider()?.onStatusMessage(context.getString(R.string.status_copied))
            KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
        } catch (t: Throwable) {
            Log.w(logTag, "COPY failed", t)
            KeyboardActionHandler.ExtensionButtonActionResult.FAILED
        }
    }

    private fun paste(ic: InputConnection?): KeyboardActionHandler.ExtensionButtonActionResult {
        if (ic == null) return KeyboardActionHandler.ExtensionButtonActionResult.FAILED
        return try {
            ic.performContextMenuAction(android.R.id.paste)
            uiListenerProvider()?.onStatusMessage(context.getString(R.string.status_pasted))
            KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
        } catch (t: Throwable) {
            Log.w(logTag, "PASTE failed", t)
            KeyboardActionHandler.ExtensionButtonActionResult.FAILED
        }
    }

    private fun moveStart(ic: InputConnection?): KeyboardActionHandler.ExtensionButtonActionResult {
        if (ic == null) return KeyboardActionHandler.ExtensionButtonActionResult.FAILED
        return try {
            val before = inputHelper.getTextBeforeCursor(ic, 100000)?.length ?: 0
            if (before > 0) {
                ic.setSelection(0, 0)
            }
            KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
        } catch (t: Throwable) {
            Log.w(logTag, "MOVE_START failed", t)
            KeyboardActionHandler.ExtensionButtonActionResult.FAILED
        }
    }

    private fun moveEnd(ic: InputConnection?): KeyboardActionHandler.ExtensionButtonActionResult {
        if (ic == null) return KeyboardActionHandler.ExtensionButtonActionResult.FAILED
        return try {
            val before = inputHelper.getTextBeforeCursor(ic, 100000)?.toString() ?: ""
            val after = inputHelper.getTextAfterCursor(ic, 100000)?.toString() ?: ""
            val total = before.length + after.length
            ic.setSelection(total, total)
            KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
        } catch (t: Throwable) {
            Log.w(logTag, "MOVE_END failed", t)
            KeyboardActionHandler.ExtensionButtonActionResult.FAILED
        }
    }

    private fun moveToPunctuation(
        ic: InputConnection?,
        direction: PunctuationJumpDirection
    ): KeyboardActionHandler.ExtensionButtonActionResult {
        if (ic == null) return KeyboardActionHandler.ExtensionButtonActionResult.FAILED
        return try {
            val before = inputHelper.getTextBeforeCursor(ic, 100000)?.toString() ?: ""
            val after = inputHelper.getTextAfterCursor(ic, 100000)?.toString() ?: ""
            val target = punctuationJumpTarget(before, after, direction)
                ?: return KeyboardActionHandler.ExtensionButtonActionResult.FAILED
            if (inputHelper.setSelection(ic, target, target)) {
                KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
            } else {
                KeyboardActionHandler.ExtensionButtonActionResult.FAILED
            }
        } catch (t: Throwable) {
            Log.w(logTag, "moveToPunctuation failed: direction=$direction", t)
            KeyboardActionHandler.ExtensionButtonActionResult.FAILED
        }
    }

    private fun toggleSilenceAutoStop(): KeyboardActionHandler.ExtensionButtonActionResult {
        val newValue = !prefs.autoStopOnSilenceEnabled
        prefs.autoStopOnSilenceEnabled = newValue
        val msgRes = if (newValue) {
            R.string.toast_silence_autostop_on
        } else {
            R.string.toast_silence_autostop_off
        }
        uiListenerProvider()?.onStatusMessage(context.getString(msgRes))
        return KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
    }

    private fun toggleKeepScreenOnWhileRecording(): KeyboardActionHandler.ExtensionButtonActionResult {
        val newValue = !prefs.keepScreenOnWhileRecording
        prefs.keepScreenOnWhileRecording = newValue
        val msgRes = if (newValue) {
            R.string.toast_keep_screen_on_while_recording_on
        } else {
            R.string.toast_keep_screen_on_while_recording_off
        }
        uiListenerProvider()?.onStatusMessage(context.getString(msgRes))
        return KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
    }

    private fun toggleMicTapMode(): KeyboardActionHandler.ExtensionButtonActionResult {
        val newValue = !prefs.micTapToggleEnabled
        prefs.micTapToggleEnabled = newValue
        val msgRes = if (newValue) {
            R.string.toast_mic_tap_mode_on
        } else {
            R.string.toast_mic_tap_mode_off
        }
        uiListenerProvider()?.onStatusMessage(context.getString(msgRes))
        return KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
    }

    private fun toggleFloatingKeyboard(): KeyboardActionHandler.ExtensionButtonActionResult {
        val newValue = !prefs.imeTabletFloatingKeyboardEnabled
        prefs.imeTabletFloatingKeyboardEnabled = newValue
        val msgRes = if (newValue) {
            R.string.toast_floating_keyboard_on
        } else {
            R.string.toast_floating_keyboard_off
        }
        uiListenerProvider()?.onStatusMessage(context.getString(msgRes))
        context.sendBroadcast(
            Intent(AsrKeyboardService.ACTION_REFRESH_IME_UI).apply {
                setPackage(context.packageName)
            }
        )
        return KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
    }

    private fun toggleAutoEnterAfterAsr(): KeyboardActionHandler.ExtensionButtonActionResult {
        val newValue = !prefs.autoEnterAfterAsrEnabled
        prefs.autoEnterAfterAsrEnabled = newValue
        val msgRes = if (newValue) {
            R.string.toast_auto_enter_after_asr_on
        } else {
            R.string.toast_auto_enter_after_asr_off
        }
        uiListenerProvider()?.onStatusMessage(context.getString(msgRes))
        return KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
    }

    private fun undo(ic: InputConnection?): KeyboardActionHandler.ExtensionButtonActionResult {
        if (ic == null) return KeyboardActionHandler.ExtensionButtonActionResult.FAILED
        val ok = handleUndo(ic)
        return if (ok) {
            KeyboardActionHandler.ExtensionButtonActionResult.SUCCESS
        } else {
            uiListenerProvider()?.onStatusMessage(
                context.getString(R.string.status_nothing_to_undo)
            )
            KeyboardActionHandler.ExtensionButtonActionResult.FAILED
        }
    }
}
