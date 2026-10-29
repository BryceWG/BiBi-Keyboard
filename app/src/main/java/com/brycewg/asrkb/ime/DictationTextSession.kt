/**
 * IME 听写会话的文本写入与交付：正常 composing，以及严格归属校验下的一次有界修复。
 */
package com.brycewg.asrkb.ime

import android.os.SystemClock
import android.view.inputmethod.InputConnection
import com.brycewg.asrkb.store.debug.DebugLogManager

private const val ANCHOR_MAX = 128
private const val VERIFY_MAX = 10_000

/** 所有入口由 IME 主线程串行调用；结果处理不依赖本类是否仍有编辑器写入权限。 */
internal class DictationTextSession(private val inputHelper: InputConnectionHelper) {
    enum class Delivery { CONFIRMED_REPLACED, REPAIRED, UNVERIFIED, SKIPPED }

    private enum class Check { REPLACED, APPENDED, UNREADABLE, CONFLICT }

    private data class Ownership(
        val ic: InputConnection,
        val before: String?,
        val after: String?,
        val start: Int,
        val initialSelection: Pair<Int, Int>
    )

    private var ownership: Ownership? = null
    private var generation = 0L
    private var active = false
    private var invalidated = true
    private var sentText = ""
    private var confirmedText: String? = null
    private var hasWritten = false
    private var aiPhase = false
    private val expectedSelections = ArrayDeque<Pair<Int, Int>>()

    /** 只有新的听写开始才采集连接、编辑位置和前后锚点。 */
    fun begin(generation: Long, ic: InputConnection?, selectionStart: Int, selectionEnd: Int) {
        this.generation = generation
        active = ic != null
        invalidated = ic == null
        sentText = ""
        confirmedText = null
        hasWritten = false
        aiPhase = false
        expectedSelections.clear()
        val selection = ic?.let { inputHelper.getSelection(it) } ?: (selectionStart to selectionEnd)
        ownership = ic?.let {
            Ownership(
                it,
                inputHelper.getTextBeforeCursor(it, ANCHOR_MAX)?.toString(),
                inputHelper.getTextAfterCursor(it, ANCHOR_MAX)?.toString(),
                minOf(selection.first, selection.second),
                selection
            )
        }
        rememberSelection(selection.first, selection.second)
        logBase("dictation_session_begin", mapOf("hasIc" to (ic != null), "start" to ownership?.start))
    }

    fun isActive(generation: Long): Boolean = active && !invalidated && this.generation == generation

    fun isSameConnection(ic: InputConnection?): Boolean = ic != null && ownership?.ic === ic

    fun enterAi(generation: Long) {
        if (this.generation != generation) return
        aiPhase = true
        logBase("dictation_phase", mapOf("writable" to isActive(generation)))
    }

    /** 重试只更换结果代次，不重采位置或锚点，也不恢复已撤销的权限。 */
    fun resumeForRetry(generation: Long, ic: InputConnection?): Boolean {
        if (invalidated || !isSameConnection(ic)) return false
        this.generation = generation
        active = true
        logBase("dictation_retry_resume", emptyMap())
        return true
    }

    /** 同一录音中的下一段只能沿用仍有效且已确认完成交付的权限。 */
    fun continueAfterDelivery(ic: InputConnection?) {
        val own = ownership ?: return
        if (invalidated || active || confirmedText != sentText || !isSameConnection(ic)) return
        val end = if (own.start >= 0) own.start + sentText.length else -1
        begin(generation, ic, end, end)
    }

    fun update(generation: Long, ic: InputConnection?, text: String): Delivery {
        val own = authorized(generation, ic) ?: return Delivery.SKIPPED
        // 写入前验证旧稿，避免先破坏未知位置、再发现上下文冲突。
        if (checkCurrent(own) == Check.CONFLICT) return conflict("before_write")
        if (hasWritten && text == sentText) return verifyTarget(own, text)

        val previous = sentText
        val previousConfirmed = confirmedText == previous && hasWritten
        expectCursor(own, text.length)
        if (previousConfirmed) expectCursor(own, previous.length + text.length)
        val startedAt = SystemClock.elapsedRealtime()
        if (!inputHelper.setComposingText(own.ic, text)) return conflict("write_failed")
        if (invalidated) return Delivery.SKIPPED
        sentText = text
        hasWritten = true
        confirmedText = null
        val check = checkText(own, text, if (previousConfirmed && previous.isNotEmpty()) previous + text else null)
        logVerbose("dictation_write", mapOf("len" to text.length, "check" to check.name))
        return when (check) {
            Check.REPLACED -> {
                confirmedText = text
                Delivery.CONFIRMED_REPLACED
            }
            Check.APPENDED -> repair(own, previous + text, text, startedAt)
            Check.UNREADABLE -> Delivery.UNVERIFIED
            Check.CONFLICT -> conflict("after_write")
        }
    }

    /** 相同终稿不重写，但同样验证连接、位置及正文，并检查 finish 与最终正文。 */
    fun complete(generation: Long, ic: InputConnection?, finalText: String): Delivery {
        val own = authorized(generation, ic) ?: return Delivery.SKIPPED
        val write = update(generation, ic, finalText)
        if (invalidated || write == Delivery.SKIPPED) return Delivery.SKIPPED
        expectCursor(own, finalText.length)
        val finishOk = inputHelper.finishComposingText(own.ic)
        if (invalidated) return Delivery.SKIPPED
        if (!finishOk) {
            invalidate("finish_failed")
            logBase("dictation_delivery", mapOf("len" to finalText.length, "finishOk" to false))
            return Delivery.UNVERIFIED
        }
        val verified = if (finishOk) verifyTarget(own, finalText) else Delivery.UNVERIFIED
        active = false
        val delivery = when {
            !finishOk || verified == Delivery.UNVERIFIED -> Delivery.UNVERIFIED
            verified == Delivery.SKIPPED -> Delivery.SKIPPED
            write == Delivery.REPAIRED -> Delivery.REPAIRED
            else -> Delivery.CONFIRMED_REPLACED
        }
        // 保留绑定供失败音频重试核对；结束输入或外部编辑才撤销它。
        logBase(
            "dictation_delivery",
            mapOf("len" to finalText.length, "finishOk" to finishOk, "delivery" to delivery.name)
        )
        return delivery
    }

    fun cancelRecording() {
        val own = ownership ?: return
        if (!active || invalidated) return
        // 与正常更新共享授权和校验，宿主已固化时也不按 partial 长度盲删。
        complete(generation, own.ic, "")
        logBase("dictation_cancel", mapOf("reason" to "recording"))
    }

    fun cancelPolish(ic: InputConnection?, rawText: String): Delivery = complete(generation, ic, rawText)

    fun invalidate(reason: String) {
        if (invalidated) return
        invalidated = true
        active = false
        ownership = null
        expectedSelections.clear()
        logBase("dictation_ownership_invalid", mapOf("reason" to reason))
    }

    /**
     * 自身操作的预期选区在调用前登记；迟到回调不改写当前文本状态。
     * 不凭 composing 消失推断异常，也不把 composing 内任意光标位置当作自身操作。
     */
    fun onSelectionChanged(start: Int, end: Int, composingStart: Int, composingEnd: Int) {
        val own = ownership ?: return
        if (invalidated) return
        logVerbose(
            "dictation_selection",
            mapOf("start" to start, "end" to end, "composingStart" to composingStart, "composingEnd" to composingEnd)
        )
        if (own.start >= 0 && composingStart >= 0 && composingEnd > composingStart && composingStart != own.start) {
            invalidate("composing_range_drift")
            return
        }
        if ((start to end) in expectedSelections) return
        if (own.start < 0 && start == end) {
            // 未知绝对位置时，以实际正文判定；读取不可用不能授权显式删除。
            if (checkCurrent(own) != Check.CONFLICT) return
        }
        invalidate("external_selection")
    }

    private fun authorized(generation: Long, ic: InputConnection?): Ownership? {
        if (!isActive(generation)) return null
        val own = ownership ?: return null
        if (ic == null || own.ic !== ic) {
            invalidate("connection_changed")
            return null
        }
        return own
    }

    private fun checkCurrent(own: Ownership): Check = checkText(own, if (hasWritten) sentText else "")

    private fun verifyTarget(own: Ownership, text: String): Delivery = when (checkText(own, text)) {
        Check.REPLACED -> {
            confirmedText = text
            Delivery.CONFIRMED_REPLACED
        }
        Check.UNREADABLE -> {
            confirmedText = null
            Delivery.UNVERIFIED
        }
        else -> conflict("verify_target")
    }

    /** 同一次读取按各候选长度比较尾部，同时核对后文；不足读取和歧义不得授权删除。 */
    private fun checkText(own: Ownership, text: String, appended: String? = null): Check {
        val position = inputHelper.getSelection(own.ic)
        if (position != null && own.start >= 0) {
            val expectedEnd = own.start + text.length
            val appendEnd = appended?.let { own.start + it.length }
            val initial = !hasWritten && position == own.initialSelection
            val expectedPosition = position.first == position.second &&
                (position.first == expectedEnd || position.first == appendEnd)
            if (!initial && !expectedPosition) {
                return Check.CONFLICT
            }
        }
        val before = own.before ?: return Check.UNREADABLE
        val after = own.after ?: return Check.UNREADABLE
        val replaced = before + text
        val duplicate = appended?.let { before + it }
        val readLength = maxOf(replaced.length, duplicate?.length ?: 0)
        if (readLength > VERIFY_MAX) return Check.UNREADABLE
        val read = inputHelper.getTextBeforeCursor(own.ic, readLength)?.toString() ?: return Check.UNREADABLE
        val tail = inputHelper.getTextAfterCursor(own.ic, ANCHOR_MAX)?.toString() ?: return Check.UNREADABLE
        if (tail.length < after.length) return Check.UNREADABLE
        if (if (after.length < ANCHOR_MAX) tail != after else !tail.startsWith(after)) return Check.CONFLICT
        fun matches(candidate: String): Boolean = read.length >= candidate.length && read.endsWith(candidate)
        val matchesReplacement = matches(replaced)
        val matchesAppend = duplicate != null && matches(duplicate)
        return when {
            matchesReplacement && matchesAppend -> Check.CONFLICT
            matchesReplacement -> Check.REPLACED
            matchesAppend -> Check.APPENDED
            read.length < replaced.length -> Check.UNREADABLE
            else -> Check.CONFLICT
        }
    }

    private fun repair(own: Ownership, appended: String, target: String, startedAt: Long): Delivery {
        // 修复必须拥有前后锚点和已知起点；读不到位置则仅继续 composing。
        if (own.start < 0 || own.before == null || own.after == null) return Delivery.UNVERIFIED
        if (!repairMatches(own, appended, target)) return conflict("repair_before_finish")
        expectCursor(own, appended.length)
        if (!inputHelper.finishComposingText(own.ic)) return conflict("repair_finish_failed")
        if (!repairMatches(own, appended, target)) return conflict("repair_after_finish")
        expectCursor(own, 0)
        if (!inputHelper.deleteSurroundingText(own.ic, appended.length, 0)) return conflict("repair_delete_failed")
        expectCursor(own, target.length)
        if (!inputHelper.setComposingText(own.ic, target)) return conflict("repair_write_failed")
        val result = verifyTarget(own, target)
        if (result != Delivery.CONFIRMED_REPLACED) return conflict("repair_verify_failed")
        logBase(
            "dictation_repair",
            mapOf("result" to "repaired", "len" to target.length, "ms" to SystemClock.elapsedRealtime() - startedAt)
        )
        return Delivery.REPAIRED
    }

    private fun repairMatches(own: Ownership, appended: String, target: String): Boolean {
        val end = own.start + appended.length
        return !invalidated &&
            ownership === own &&
            checkText(own, target, appended) == Check.APPENDED &&
            inputHelper.getSelection(own.ic) == (end to end) &&
            inputHelper.getSelectedText(own.ic).isNullOrEmpty()
    }

    private fun expectCursor(own: Ownership, length: Int) {
        if (own.start >= 0) rememberSelection(own.start + length, own.start + length)
    }

    private fun rememberSelection(start: Int, end: Int) {
        expectedSelections.addLast(start to end)
        if (expectedSelections.size > 64) expectedSelections.removeFirst()
    }

    private fun conflict(reason: String): Delivery {
        invalidate(reason)
        return Delivery.SKIPPED
    }

    private fun logBase(event: String, data: Map<String, Any?>) {
        DebugLogManager.logBase("insert", event, data + mapOf("generation" to generation, "ai" to aiPhase))
    }

    private fun logVerbose(event: String, data: Map<String, Any?>) {
        DebugLogManager.log("insert", event, data + mapOf("generation" to generation, "ai" to aiPhase))
    }
}
