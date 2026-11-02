/**
 * IME 听写诊断：一次记录写入前后的位置、组合区及文本形状；不记录正文，不参与写入决策。
 */
package com.brycewg.asrkb.ime

import android.os.SystemClock
import android.text.Spanned
import android.view.inputmethod.InputConnection
import com.brycewg.asrkb.store.debug.DebugLogManager

internal object DictationTextDiagnostics {
    private const val BEFORE_MAX = 10_000
    private const val AFTER_MAX = 128

    data class Snapshot(
        val selection: Pair<Int, Int>?,
        val before: String?,
        val after: String?,
        val requestedBefore: Int,
        val beforeStyled: Boolean,
        val afterStyled: Boolean,
        val beforeComposing: Pair<Int, Int>?,
        val afterComposing: Pair<Int, Int>?,
        val elapsedMs: Long
    )

    fun capture(helper: InputConnectionHelper, ic: InputConnection, previous: String, target: String): Snapshot? {
        if (!DebugLogManager.isRecording()) return null
        val startedAt = SystemClock.elapsedRealtime()
        val count = minOf(previous.length + target.length, BEFORE_MAX)
        val selection = helper.getSelection(ic)
        val before = helper.getTextBeforeCursor(ic, count, InputConnection.GET_TEXT_WITH_STYLES)
        val after = helper.getTextAfterCursor(ic, AFTER_MAX, InputConnection.GET_TEXT_WITH_STYLES)
        return Snapshot(
            selection, before?.toString(), after?.toString(), count,
            before is Spanned, after is Spanned,
            helper.composingRange(before), helper.composingRange(after),
            SystemClock.elapsedRealtime() - startedAt
        )
    }

    fun describe(before: Snapshot?, after: Snapshot?, previous: String, target: String): Map<String, Any?> {
        if (before == null && after == null) return emptyMap()
        return buildMap {
            if (before != null) putAll(describeSnapshot("pre", before, previous, target))
            if (after != null) putAll(describeSnapshot("post", after, previous, target))
            val oldTail = before?.after
            val newTail = after?.after
            put("afterEqual", if (oldTail != null && newTail != null) oldTail == newTail else null)
            put("afterStartsWithPrior", if (oldTail != null && newTail != null) newTail.startsWith(oldTail) else null)
            put("afterEndsWithPrior", if (oldTail != null && newTail != null) newTail.endsWith(oldTail) else null)
        }
    }

    private fun describeSnapshot(prefix: String, snapshot: Snapshot, previous: String, target: String): Map<String, Any?> {
        val read = snapshot.before
        val tail = snapshot.after
        val appended = previous + target
        return mapOf(
            prefix + "SelectionStart" to snapshot.selection?.first,
            prefix + "SelectionEnd" to snapshot.selection?.second,
            prefix + "RequestedBeforeLen" to snapshot.requestedBefore,
            prefix + "RequestedAfterLen" to AFTER_MAX,
            prefix + "BeforeLen" to read?.length,
            prefix + "AfterLen" to tail?.length,
            prefix + "BeforeStyled" to snapshot.beforeStyled,
            prefix + "AfterStyled" to snapshot.afterStyled,
            // span 坐标相对于对应的读取片段，不冒充编辑器绝对坐标。
            prefix + "BeforeSpanStart" to snapshot.beforeComposing?.first,
            prefix + "BeforeSpanEnd" to snapshot.beforeComposing?.second,
            prefix + "AfterSpanStart" to snapshot.afterComposing?.first,
            prefix + "AfterSpanEnd" to snapshot.afterComposing?.second,
            prefix + "MatchesPrevious" to read?.endsWith(previous),
            prefix + "MatchesTarget" to read?.endsWith(target),
            prefix + "MatchesAppended" to if (previous.isNotEmpty()) read?.endsWith(appended) else null,
            prefix + "AfterWhitespaceCount" to tail?.count { it.isWhitespace() },
            prefix + "AfterLineBreakCount" to tail?.count { it == '\n' || it == '\r' },
            prefix + "AfterFormatCount" to tail?.count { Character.getType(it) == Character.FORMAT.toInt() },
            prefix + "ReadMs" to snapshot.elapsedMs
        )
    }
}
