package com.brycewg.asrkb.ime

import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * InputConnection 辅助类：封装所有与 InputConnection 的交互，统一异常处理和日志记录。
 *
 * 由于输入法宿主应用千差万别，InputConnection 的行为并不可靠。
 * 为所有操作添加详细日志，以便在特定应用中功能静默失败时能够快速定位问题。
 */
class InputConnectionHelper(
    private val tag: String = "InputConnectionHelper"
) {
    /**
     * 提交文本到输入框
     * @param ic InputConnection 实例
     * @param text 要提交的文本
     * @param newCursorPosition 新的光标位置
     * @return 操作是否成功
     */
    fun commitText(ic: InputConnection?, text: CharSequence, newCursorPosition: Int = 1): Boolean {
        if (ic == null) {
            Log.w(tag, "commitText: InputConnection is null")
            return false
        }
        return try {
            ic.commitText(text, newCursorPosition)
            true
        } catch (e: Throwable) {
            Log.e(tag, "commitText failed: text='$text', pos=$newCursorPosition", e)
            false
        }
    }

    /**
     * 设置组合文本（预览状态，通常用于实时显示 ASR 中间结果）
     */
    fun setComposingText(ic: InputConnection?, text: CharSequence, newCursorPosition: Int = 1): Boolean {
        if (ic == null) {
            Log.w(tag, "setComposingText: InputConnection is null")
            return false
        }
        return try {
            ic.setComposingText(text, newCursorPosition)
            true
        } catch (e: Throwable) {
            Log.e(tag, "setComposingText failed: text='$text', pos=$newCursorPosition", e)
            false
        }
    }

    /**
     * 完成组合文本（将预览文本固化为最终提交）
     */
    fun finishComposingText(ic: InputConnection?): Boolean {
        if (ic == null) {
            Log.w(tag, "finishComposingText: InputConnection is null")
            return false
        }
        return try {
            ic.finishComposingText()
            true
        } catch (e: Throwable) {
            Log.e(tag, "finishComposingText failed", e)
            false
        }
    }

    /**
     * 删除光标周围的文本
     * @param beforeLength 删除光标前的字符数
     * @param afterLength 删除光标后的字符数
     */
    fun deleteSurroundingText(ic: InputConnection?, beforeLength: Int, afterLength: Int): Boolean {
        if (ic == null) {
            Log.w(tag, "deleteSurroundingText: InputConnection is null")
            return false
        }
        return try {
            ic.deleteSurroundingText(beforeLength, afterLength)
            true
        } catch (e: Throwable) {
            Log.e(tag, "deleteSurroundingText failed: before=$beforeLength, after=$afterLength", e)
            false
        }
    }

    /**
     * 获取光标前的文本
     * @param n 最多获取的字符数
     * @param flags 标志位
     * @return 光标前的文本，失败时返回 null
     */
    fun getTextBeforeCursor(ic: InputConnection?, n: Int, flags: Int = 0): CharSequence? {
        if (ic == null) {
            Log.w(tag, "getTextBeforeCursor: InputConnection is null")
            return null
        }
        return try {
            ic.getTextBeforeCursor(n, flags)
        } catch (e: Throwable) {
            Log.e(tag, "getTextBeforeCursor failed: n=$n, flags=$flags", e)
            null
        }
    }

    /**
     * 获取光标后的文本
     */
    fun getTextAfterCursor(ic: InputConnection?, n: Int, flags: Int = 0): CharSequence? {
        if (ic == null) {
            Log.w(tag, "getTextAfterCursor: InputConnection is null")
            return null
        }
        return try {
            ic.getTextAfterCursor(n, flags)
        } catch (e: Throwable) {
            Log.e(tag, "getTextAfterCursor failed: n=$n, flags=$flags", e)
            null
        }
    }

    /**
     * 获取当前选中的文本
     */
    fun getSelectedText(ic: InputConnection?, flags: Int = 0): CharSequence? {
        if (ic == null) {
            Log.w(tag, "getSelectedText: InputConnection is null")
            return null
        }
        return try {
            ic.getSelectedText(flags)
        } catch (e: Throwable) {
            Log.e(tag, "getSelectedText failed: flags=$flags", e)
            null
        }
    }

    /**
     * 设置选区范围
     */
    fun setSelection(ic: InputConnection?, start: Int, end: Int): Boolean {
        if (ic == null) {
            Log.w(tag, "setSelection: InputConnection is null")
            return false
        }
        return try {
            ic.setSelection(start, end)
            true
        } catch (e: Throwable) {
            Log.e(tag, "setSelection failed: start=$start, end=$end", e)
            false
        }
    }

    /**
     * 选择全部文本（基于上下文长度估算）
     */
    fun selectAll(ic: InputConnection?): Boolean {
        if (ic == null) {
            Log.w(tag, "selectAll: InputConnection is null")
            return false
        }
        return try {
            val beforeLen = getTextBeforeCursor(ic, 10000)?.length ?: 0
            val afterLen = getTextAfterCursor(ic, 10000)?.length ?: 0
            ic.setSelection(0, beforeLen + afterLen)
            true
        } catch (e: Throwable) {
            Log.e(tag, "selectAll failed", e)
            false
        }
    }

    /**
     * 获取撤销快照：捕获当前光标前后的文本
     */
    fun captureUndoSnapshot(ic: InputConnection?): UndoSnapshot? {
        if (ic == null) {
            Log.w(tag, "captureUndoSnapshot: InputConnection is null")
            return null
        }
        return try {
            val before = ic.getTextBeforeCursor(10000, 0)
            val after = ic.getTextAfterCursor(10000, 0)
            if (before != null && after != null) {
                UndoSnapshot(before, after)
            } else {
                Log.w(tag, "captureUndoSnapshot: before or after is null")
                null
            }
        } catch (e: Throwable) {
            Log.e(tag, "captureUndoSnapshot failed", e)
            null
        }
    }

    /**
     * 恢复撤销快照
     */
    fun restoreSnapshot(ic: InputConnection?, snapshot: UndoSnapshot): Boolean {
        if (ic == null) {
            Log.w(tag, "restoreSnapshot: InputConnection is null")
            return false
        }
        return try {
            val before = snapshot.beforeCursor.toString()
            val after = snapshot.afterCursor.toString()

            ic.beginBatchEdit()
            // 清空当前内容
            val currBeforeLen = getTextBeforeCursor(ic, 10000)?.length ?: 0
            val currAfterLen = getTextAfterCursor(ic, 10000)?.length ?: 0
            ic.deleteSurroundingText(currBeforeLen, currAfterLen)

            // 恢复快照内容
            ic.commitText(before + after, 1)
            val sel = before.length
            ic.setSelection(sel, sel)
            ic.finishComposingText()
            ic.endBatchEdit()
            true
        } catch (e: Throwable) {
            Log.e(tag, "restoreSnapshot failed", e)
            false
        }
    }

    /**
     * 发送回车键或执行编辑器动作
     *
     * 根据 EditorInfo 的 imeOptions 判断行为：
     * - 对于 IME_ACTION_SEND/GO/SEARCH/DONE/NEXT/PREVIOUS，执行 performEditorAction
     * - 对于多行输入框或 IME_ACTION_NONE/UNSPECIFIED，发送普通回车键
     *
     * @param ic InputConnection 实例
     * @param editorInfo 当前编辑器信息，用于判断应执行的动作
     */
    fun sendEnter(ic: InputConnection?, editorInfo: EditorInfo? = null): Boolean {
        if (ic == null) {
            Log.w(tag, "sendEnter: InputConnection is null")
            return false
        }
        return try {
            val imeOptions = editorInfo?.imeOptions ?: 0
            val action = imeOptions and EditorInfo.IME_MASK_ACTION
            val isMultiLine = (editorInfo?.inputType ?: 0) and
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            val flagNoEnterAction = (imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0

            // 根据 action 类型和输入框特性决定行为
            val shouldPerformAction = when {
                // 多行且设置了 NO_ENTER_ACTION 标志，发送普通回车
                isMultiLine && flagNoEnterAction -> false
                // 特定的 action 类型需要执行 performEditorAction
                action == EditorInfo.IME_ACTION_SEND ||
                action == EditorInfo.IME_ACTION_GO ||
                action == EditorInfo.IME_ACTION_SEARCH ||
                action == EditorInfo.IME_ACTION_DONE ||
                action == EditorInfo.IME_ACTION_NEXT ||
                action == EditorInfo.IME_ACTION_PREVIOUS -> true
                // 其他情况发送普通回车
                else -> false
            }

            if (shouldPerformAction) {
                Log.d(tag, "sendEnter: performEditorAction($action)")
                ic.performEditorAction(action)
            } else {
                Log.d(tag, "sendEnter: sendKeyEvent(KEYCODE_ENTER)")
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            }
            true
        } catch (e: Throwable) {
            Log.e(tag, "sendEnter failed", e)
            false
        }
    }

    /**
     * 发送退格键（删除光标前的一个字符）
     */
    fun sendBackspace(ic: InputConnection?): Boolean {
        if (ic == null) {
            Log.w(tag, "sendBackspace: InputConnection is null")
            return false
        }

        return try {
            // 先结束任何悬浮的 composing，避免目标应用将退格当作"撤销整段组合文本"
            ic.finishComposingText()

            // 若有选区，按退格语义应删除选区内容
            val selected = ic.getSelectedText(0)
            if (!selected.isNullOrEmpty()) {
                ic.commitText("", 1)
                return true
            }

            // 对部分应用，使用硬件 DEL 事件能更稳定地保持光标位置
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            true
        } catch (e: Throwable) {
            Log.e(tag, "sendBackspace failed, trying fallback", e)
            // 兜底：删除光标前一个字符
            try {
                ic?.deleteSurroundingText(1, 0)
                true
            } catch (e2: Throwable) {
                Log.e(tag, "sendBackspace fallback failed", e2)
                false
            }
        }
    }

    /**
     * 清空所有文本（使用快照精确删除，避免误删）
     */
    fun clearAllText(ic: InputConnection?, snapshot: UndoSnapshot?): Boolean {
        if (ic == null) {
            Log.w(tag, "clearAllText: InputConnection is null")
            return false
        }

        if (snapshot == null) {
            Log.w(tag, "clearAllText: snapshot is null, using max deletion")
            return try {
                ic.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE)
                true
            } catch (e: Throwable) {
                Log.e(tag, "clearAllText max deletion failed", e)
                false
            }
        }

        return try {
            val beforeLen = snapshot.beforeCursor.length
            val afterLen = snapshot.afterCursor.length
            ic.beginBatchEdit()
            ic.deleteSurroundingText(beforeLen, afterLen)
            ic.finishComposingText()
            ic.endBatchEdit()
            true
        } catch (e: Throwable) {
            Log.e(tag, "clearAllText with snapshot failed", e)
            try {
                ic.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE)
                true
            } catch (e2: Throwable) {
                Log.e(tag, "clearAllText fallback failed", e2)
                false
            }
        }
    }

    /**
     * 替换指定文本：在光标前查找 oldText 并替换为 newText
     * @return 是否成功替换
     */
    fun replaceText(ic: InputConnection?, oldText: String, newText: String): Boolean {
        if (ic == null) {
            Log.w(tag, "replaceText: InputConnection is null")
            return false
        }
        if (oldText.isEmpty()) {
            Log.w(tag, "replaceText: oldText is empty")
            return false
        }

        return try {
            val before = getTextBeforeCursor(ic, 10000)?.toString()
            val after = getTextAfterCursor(ic, 10000)?.toString()

            ic.beginBatchEdit()
            var replaced = false

            // 尝试在光标前查找并替换
            if (!before.isNullOrEmpty() && before.endsWith(oldText)) {
                ic.deleteSurroundingText(oldText.length, 0)
                ic.commitText(newText, 1)
                replaced = true
            }
            // 尝试在光标后查找并替换
            else if (!after.isNullOrEmpty() && after.startsWith(oldText)) {
                ic.deleteSurroundingText(0, oldText.length)
                ic.commitText(newText, 1)
                replaced = true
            }
            // 尝试在整个上下文中查找
            else if (before != null && after != null) {
                val combined = before + after
                val pos = combined.lastIndexOf(oldText)
                if (pos >= 0) {
                    val end = pos + oldText.length
                    ic.setSelection(end, end)
                    // 重新获取光标位置后的文本
                    val before2 = getTextBeforeCursor(ic, 10000)?.toString()
                    if (!before2.isNullOrEmpty() && before2.endsWith(oldText)) {
                        ic.deleteSurroundingText(oldText.length, 0)
                        ic.commitText(newText, 1)
                        replaced = true
                    }
                }
            }

            ic.finishComposingText()
            ic.endBatchEdit()

            if (!replaced) {
                Log.w(tag, "replaceText: text not found in context, old='$oldText'")
            }
            replaced
        } catch (e: Throwable) {
            Log.e(tag, "replaceText failed: old='$oldText', new='$newText'", e)
            false
        }
    }
}
