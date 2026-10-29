/**
 * 流式预览/固化诊断：只描述文本形状与编辑器区间，不记录正文。
 *
 * 归属模块：store/debug
 */
package com.brycewg.asrkb.store.debug

import android.text.Spanned
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

internal object StreamingPreviewDiag {
    private const val OVERLAP_SCAN_MAX = 512
    private const val SNAPSHOT_READ_MAX = 512

    fun fingerprint(text: CharSequence?): String {
        if (text == null) return "0:0"
        val s = text.toString()
        return "${s.length}:${s.hashCode().toUInt().toString(16)}"
    }

    fun relation(prev: String?, next: String): String {
        if (prev.isNullOrEmpty()) return if (next.isEmpty()) "equal" else "rewrite"
        if (prev == next) return "equal"
        val prevLen = prev.length
        val nextLen = next.length
        if (nextLen == prevLen * 2 && next.startsWith(prev) && next.endsWith(prev)) {
            return "exact_dup"
        }
        if (next.startsWith(prev) && nextLen > prevLen) return "prefix_grow"
        if (prev.startsWith(next) && nextLen < prevLen) return "prefix_shrink"
        if (next.endsWith(prev) && nextLen > prevLen) return "appended_prev"
        if (overlapLen(prev, next) > 0) return "overlap"
        return "rewrite"
    }

    fun relation(prev: StringBuilder, next: String): String {
        if (prev.isEmpty()) return if (next.isEmpty()) "equal" else "rewrite"
        if (prev.length <= OVERLAP_SCAN_MAX) return relation(prev.toString(), next)
        val samePrefix = matchesPrefixBounded(prev, next)
        if (prev.length == next.length && samePrefix) return "equal"
        if (next.length > prev.length && samePrefix) return "prefix_grow"
        if (next.length > prev.length && matchesSuffixBounded(prev, next)) return "appended_prev"
        if (matchesSuffixToPrefixBounded(prev, next)) return "overlap"
        return "rewrite"
    }

    fun overlapLen(prev: String?, next: String): Int {
        if (prev.isNullOrEmpty() || next.isEmpty()) return 0
        val max = minOf(prev.length, next.length, OVERLAP_SCAN_MAX)
        for (len in max downTo 1) {
            if (prev.regionMatches(prev.length - len, next, 0, len)) return len
        }
        return 0
    }

    fun shape(prev: String?, next: String): Map<String, Any?> = mapOf(
        "prev" to fingerprint(prev),
        "next" to fingerprint(next),
        "rel" to relation(prev, next),
        "overlap" to overlapLen(prev, next)
    )

    fun logVerbose(
        category: String,
        event: String,
        prev: String?,
        next: String,
        extra: Map<String, Any?> = emptyMap()
    ) {
        try {
            DebugLogManager.log(category, event, extra + shape(prev, next))
        } catch (_: Throwable) { }
        maybeWarnDup(category, event, prev, next, extra)
    }

    fun maybeWarnDup(
        category: String,
        at: String,
        prev: String?,
        next: String,
        extra: Map<String, Any?> = emptyMap()
    ) {
        val rel = relation(prev, next)
        if (rel != "exact_dup" && rel != "appended_prev") return
        try {
            DebugLogManager.logWarning(
                category = category,
                event = "dup_suspect",
                data = extra + shape(prev, next) + mapOf("at" to at, "rel" to rel)
            )
        } catch (_: Throwable) { }
    }

    fun looksCumulativeDelta(builder: String, delta: String): Boolean {
        if (builder.isEmpty() || delta.isEmpty()) return false
        if (delta.length > builder.length && delta.startsWith(builder)) return true
        if (delta == builder && builder.length >= 8) return true
        val builderLen = builder.length
        val deltaLen = delta.length
        val closeLen = kotlin.math.abs(deltaLen - builderLen) * 5 <= builderLen.coerceAtLeast(1)
        return closeLen && builderLen >= 8 && builder.endsWith(delta)
    }

    fun looksCumulativeDelta(builder: StringBuilder, delta: String): Boolean {
        if (builder.isEmpty() || delta.isEmpty()) return false
        val builderLen = builder.length
        val deltaLen = delta.length
        if (deltaLen > builderLen && matchesPrefixBounded(builder, delta)) return true
        if (deltaLen == builderLen && builderLen >= 8 && matchesPrefixBounded(builder, delta)) return true
        val closeLen = kotlin.math.abs(deltaLen - builderLen) * 5 <= builderLen.coerceAtLeast(1)
        return closeLen && builderLen >= 8 && matchesSuffixBounded(builder, delta)
    }

    private fun matchesPrefixBounded(left: CharSequence, right: CharSequence): Boolean {
        if (right.length < left.length) return false
        val edgeLength = minOf(left.length, OVERLAP_SCAN_MAX / 2)
        return regionMatches(left, 0, right, 0, edgeLength) &&
            regionMatches(
                left,
                left.length - edgeLength,
                right,
                left.length - edgeLength,
                edgeLength
            )
    }

    private fun matchesSuffixBounded(left: CharSequence, right: CharSequence): Boolean {
        val length = minOf(left.length, right.length, OVERLAP_SCAN_MAX)
        return length > 0 &&
            regionMatches(
                left,
                left.length - length,
                right,
                right.length - length,
                length
            )
    }

    private fun matchesSuffixToPrefixBounded(left: CharSequence, right: CharSequence): Boolean {
        val length = minOf(left.length, right.length, OVERLAP_SCAN_MAX)
        return length > 0 && regionMatches(left, left.length - length, right, 0, length)
    }

    private fun regionMatches(
        left: CharSequence,
        leftStart: Int,
        right: CharSequence,
        rightStart: Int,
        length: Int
    ): Boolean {
        for (index in 0 until length) {
            if (left[leftStart + index] != right[rightStart + index]) return false
        }
        return true
    }

    fun editorSnapshot(ic: InputConnection?): Map<String, Any?> {
        if (ic == null) return mapOf("icNull" to true)
        return try {
            val beforeLen = try {
                ic.getTextBeforeCursor(SNAPSHOT_READ_MAX, 0)?.length ?: -1
            } catch (_: Throwable) {
                -1
            }
            val afterLen = try {
                ic.getTextAfterCursor(SNAPSHOT_READ_MAX, 0)?.length ?: -1
            } catch (_: Throwable) {
                -1
            }
            val selectedLen = try {
                ic.getSelectedText(0)?.length ?: 0
            } catch (_: Throwable) {
                -1
            }
            val extracted = try {
                ic.getExtractedText(
                    ExtractedTextRequest().apply {
                        hintMaxChars = SNAPSHOT_READ_MAX
                        hintMaxLines = 1
                    },
                    0
                )
            } catch (_: Throwable) {
                null
            }
            val extractedText = extracted?.text
            val composing = composingRange(extractedText)
            buildMap {
                put("beforeLen", beforeLen)
                put("afterLen", afterLen)
                put("selectedLen", selectedLen)
                put("extractedLen", extractedText?.length ?: -1)
                put("selStart", extracted?.selectionStart ?: -1)
                put("selEnd", extracted?.selectionEnd ?: -1)
                when {
                    extractedText == null -> {
                        // 未读到编辑器文本：composing 存在性未知，不能当成确认没有 composing。
                        put("composingKnown", false)
                        put("composingLen", -1)
                    }
                    composing != null -> {
                        put("composingKnown", true)
                        put("composingStart", composing.first)
                        put("composingEnd", composing.second)
                        put("composingLen", composing.second - composing.first)
                    }
                    extractedText !is Spanned -> {
                        // 读到的文本不携带 span 信息：无法确认 composing 是否存在，仍按未知记录。
                        put("composingKnown", false)
                        put("composingLen", -1)
                    }
                    else -> {
                        put("composingKnown", true)
                        put("composingLen", 0)
                    }
                }
            }
        } catch (_: Throwable) {
            mapOf("snapshotFailed" to true)
        }
    }

    /**
     * 读取 composing 区间。
     *
     * 返回 null 表示未读到 spans（未知），与“确认没有 composing”区分开。
     */
    private fun composingRange(text: CharSequence?): Pair<Int, Int>? {
        if (text !is Spanned) return null
        val spans = try {
            text.getSpans(0, text.length, Any::class.java)
        } catch (_: Throwable) {
            return null
        }
        for (span in spans) {
            val flags = try {
                text.getSpanFlags(span)
            } catch (_: Throwable) {
                continue
            }
            if (flags and Spanned.SPAN_COMPOSING == 0) continue
            val start = text.getSpanStart(span)
            val end = text.getSpanEnd(span)
            if (start >= 0 && end >= start) return start to end
        }
        return null
    }
}
