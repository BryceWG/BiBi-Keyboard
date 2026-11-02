/**
 * IME 听写文本交付：直接更新 composing；组合区丢失时局部确认上一版预览并恢复范围。
 */
package com.brycewg.asrkb.ime

import android.view.inputmethod.InputConnection
import com.brycewg.asrkb.store.debug.DebugLogManager

/** 主线程串行使用；ASR、AI 与终稿共享绑定，诊断读回不决定普通写入是否成功。 */
internal class DictationTextSession(private val inputHelper: InputConnectionHelper) {
    /** ACCEPTED 表示编辑器接受了操作，不代表同步读回确认了正文。 */
    enum class Delivery { ACCEPTED, FAILED, SKIPPED }

    private data class Binding(val ic: InputConnection, var start: Int)

    private var binding: Binding? = null
    private var generation = 0L
    private var active = false
    private var invalidated = true
    private var sentText = ""
    private var hasWritten = false
    private var aiPhase = false
    private var operation = 0L
    private var segment = 0L
    private var expectedCursor = -1 to -1
    private val expectedSelections = ArrayDeque<Pair<Int, Int>>()

    // null 表示本次写入尚无宿主回报；(-1, -1) 才是回调明确报告没有组合区。
    private var composingRange: Pair<Int, Int>? = null

    fun begin(generation: Long, ic: InputConnection?, selectionStart: Int, selectionEnd: Int) {
        this.generation = generation
        operation = 0
        segment = 0
        val selection = ic?.let { inputHelper.getSelection(it) } ?: (selectionStart to selectionEnd)
        beginSegment(ic, selection)
    }

    private fun beginSegment(ic: InputConnection?, selection: Pair<Int, Int>) {
        active = ic != null
        invalidated = ic == null
        sentText = ""
        hasWritten = false
        aiPhase = false
        composingRange = null
        expectedSelections.clear()
        binding = ic?.let { Binding(it, minOf(selection.first, selection.second)) }
        expectedCursor = selection
        rememberSelection(selection)
        logDiag("dictation_session_begin", mapOf("hasIc" to (ic != null), "start" to binding?.start))
    }

    fun isActive(generation: Long): Boolean = active && !invalidated && this.generation == generation

    fun isSameConnection(ic: InputConnection?): Boolean = ic != null && binding?.ic === ic

    fun enterAi(generation: Long) {
        if (this.generation != generation) return
        aiPhase = true
        logDiag("dictation_phase", mapOf("writable" to isActive(generation)))
    }

    /** 重试沿用原输入框和替换范围，不能重新授权已经取消的绑定。 */
    fun resumeForRetry(generation: Long, ic: InputConnection?): Boolean {
        if (invalidated || !isSameConnection(ic)) return false
        this.generation = generation
        active = true
        logDiag("dictation_retry_resume", emptyMap())
        return true
    }

    /** 连续录音的下一段从上一段提交后的光标开始。 */
    fun continueAfterDelivery(ic: InputConnection?) {
        val own = binding ?: return
        if (invalidated || active || !isSameConnection(ic)) return
        val selection = inputHelper.getSelection(own.ic) ?: expectedCursor
        segment++
        beginSegment(own.ic, selection)
    }

    fun update(generation: Long, ic: InputConnection?, text: String): Delivery = write(generation, ic, text, final = false)

    fun complete(generation: Long, ic: InputConnection?, finalText: String): Delivery = write(generation, ic, finalText, final = true)

    fun cancelRecording() {
        val own = binding ?: return
        if (!active || invalidated) return
        complete(generation, own.ic, "")
        logDiag("dictation_cancel", mapOf("reason" to "recording"))
    }

    fun cancelPolish(ic: InputConnection?, rawText: String): Delivery = complete(generation, ic, rawText)

    fun invalidate(reason: String) {
        if (invalidated) return
        invalidated = true
        active = false
        binding = null
        composingRange = null
        expectedSelections.clear()
        logDiag("dictation_ownership_invalid", mapOf("reason" to reason))
    }

    /** 迟到的自身选区回报只记录；只让当前预期光标的回报更新组合区状态。 */
    fun onSelectionChanged(start: Int, end: Int, composingStart: Int, composingEnd: Int) {
        val own = binding ?: return
        val selection = start to end
        val pendingIndex = expectedSelections.lastIndexOf(selection)
        val expected = pendingIndex >= 0
        logDiag(
            "dictation_selection",
            mapOf(
                "start" to start,
                "end" to end,
                "composingStart" to composingStart,
                "composingEnd" to composingEnd,
                "expected" to expected,
                "current" to (selection == expectedCursor)
            )
        )
        if (selection == expectedCursor) {
            expectedSelections.clear()
            composingRange = composingStart to composingEnd
            return
        }
        if (expected) {
            repeat(pendingIndex + 1) { expectedSelections.removeFirst() }
            return
        }
        // 宿主可能调整 composing 正文长度；在其组合区末尾的折叠光标仍属于本次输入。
        if (active &&
            start == end &&
            composingStart >= 0 &&
            composingEnd == end &&
            (own.start < 0 || composingStart == own.start)
        ) {
            own.start = composingStart
            composingRange = composingStart to composingEnd
            expectedCursor = selection
            expectedSelections.clear()
            rememberSelection(selection)
            return
        }
        invalidate("external_selection")
    }

    private fun write(generation: Long, ic: InputConnection?, text: String, final: Boolean): Delivery {
        val own = authorized(generation, ic) ?: return Delivery.SKIPPED
        operation++
        // 尚未写入预览时取消/空结果，不向原选区提交空串，否则会删除用户选中的正文。
        if (!hasWritten && text.isEmpty()) {
            if (final) active = false
            logDiag("dictation_write", mapOf("action" to "empty", "final" to final))
            return Delivery.ACCEPTED
        }
        if (!final && hasWritten && text == sentText) {
            logDiag("dictation_write", mapOf("action" to "unchanged", "len" to text.length))
            return Delivery.ACCEPTED
        }
        val previous = sentText
        val reported = composingRange
        val before = DictationTextDiagnostics.capture(inputHelper, own.ic, previous, text)
        val prepared = prepareComposing(own)
        val accepted = if (prepared && !invalidated) writeText(own, text, final) else null
        val action = if (final) "commit_text" else "set_composing_text"
        val delivery = when {
            accepted == false -> Delivery.FAILED
            invalidated || accepted == null -> Delivery.SKIPPED
            else -> Delivery.ACCEPTED
        }
        val after = DictationTextDiagnostics.capture(inputHelper, own.ic, previous, text)
        logDiag(
            "dictation_write",
            mapOf(
                "action" to action,
                "previousLen" to previous.length,
                "len" to text.length,
                "start" to own.start,
                "final" to final,
                "accepted" to accepted,
                "reportedComposingStart" to reported?.first,
                "reportedComposingEnd" to reported?.second,
                "expectedSelectionStart" to expectedCursor.first,
                "expectedSelectionEnd" to expectedCursor.second,
                "delivery" to delivery.name
            ) + DictationTextDiagnostics.describe(before, after, previous, text)
        )
        if (final) logDiag("dictation_delivery", mapOf("len" to text.length, "delivery" to delivery.name))
        return delivery
    }

    private fun writeText(own: Binding, text: String, final: Boolean): Boolean {
        if (own.start >= 0) {
            val end = own.start + text.length
            expectedCursor = end to end
            rememberSelection(expectedCursor)
        }
        composingRange = null
        val accepted = if (final) {
            inputHelper.commitText(own.ic, text)
        } else {
            inputHelper.setComposingText(own.ic, text)
        }
        if (!accepted) {
            invalidate("write_failed")
            return false
        }
        sentText = text
        hasWritten = true
        if (final) {
            active = false
            composingRange = -1 to -1
        }
        return true
    }

    /**
     * 正常 composing 不匹配周围正文。仅在组合区未知/丢失时，核对光标前上一版预览以恢复范围。
     * 读不到 span 不等于组合区丢失；没有恢复依据时仍遵循普通 composing 协议。
     */
    private fun prepareComposing(own: Binding): Boolean {
        val reported = composingRange
        if (!hasWritten || sentText.isEmpty() || reported?.let { it.first >= 0 && it.second > it.first } == true) {
            logDiag("dictation_prepare", mapOf("path" to "composing", "composingStart" to reported?.first, "composingEnd" to reported?.second))
            return true
        }
        val local = inputHelper.getTextBeforeCursor(own.ic, sentText.length, InputConnection.GET_TEXT_WITH_STYLES)
        val position = inputHelper.getSelection(own.ic)
        val span = inputHelper.composingRange(local)
        val matches = local?.toString() == sentText
        val atEnd = position != null &&
            position.first == position.second &&
            (own.start < 0 || position.first == own.start + sentText.length)
        val data = mapOf(
            "previousLen" to sentText.length, "readLen" to local?.length,
            "matchesPrevious" to matches, "atExpectedEnd" to atEnd,
            "selectionStart" to position?.first, "selectionEnd" to position?.second,
            "composingStart" to reported?.first, "composingEnd" to reported?.second,
            "localComposingStart" to span?.first, "localComposingEnd" to span?.second
        )
        if (span != null) {
            logDiag("dictation_prepare", data + mapOf("path" to "composing_span"))
            return true
        }
        if (matches && atEnd) {
            val start = position.first - sentText.length
            val ok = inputHelper.setComposingRegion(own.ic, start, position.first)
            logDiag("dictation_prepare", data + mapOf("path" to "restore_region", "start" to start, "end" to position.first, "accepted" to ok))
            if (!ok) invalidate("restore_failed")
            if (ok) own.start = start
            return ok
        }
        if (reported == (-1 to -1)) {
            logDiag("dictation_prepare", data + mapOf("path" to "lost_preview_unmatched"))
            invalidate("lost_preview_unmatched")
            return false
        }
        logDiag("dictation_prepare", data + mapOf("path" to "composing_unknown"))
        return true
    }

    private fun authorized(generation: Long, ic: InputConnection?): Binding? {
        if (!isActive(generation)) {
            logDiag("dictation_write_skipped", mapOf("reason" to "inactive", "requestedGeneration" to generation))
            return null
        }
        val own = binding ?: return null
        if (ic == null || own.ic !== ic) {
            invalidate("connection_changed")
            return null
        }
        return own
    }

    private fun rememberSelection(selection: Pair<Int, Int>) {
        expectedSelections.addLast(selection)
        if (expectedSelections.size > 64) expectedSelections.removeFirst()
    }

    private fun logDiag(event: String, data: Map<String, Any?>) {
        DebugLogManager.log(
            "insert",
            event,
            data + mapOf("generation" to generation, "segment" to segment, "operation" to operation, "ai" to aiPhase, "pkg" to inputHelper.diagHostPkg)
        )
    }
}
