package com.brycewg.asrkb.clipboard

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * 简单的剪贴板历史存储：
 * - 按时间倒序（最新在前）
 * - 支持固定（置顶）与普通记录分开存储
 * - 仅固定记录参与备份（存储于 KEY_CLIP_PINNED_JSON）
 * - 普通记录存储于 KEY_CLIP_HISTORY_JSON，不参与备份
 */
class ClipboardHistoryStore(private val context: Context, private val prefs: Prefs) {

    @Serializable
    data class Entry(
        val id: String,
        val text: String,
        val ts: Long,
        val pinned: Boolean
    )

    private val sp by lazy { context.getSharedPreferences("asr_prefs", Context.MODE_PRIVATE) }
    private val json by lazy { Json { ignoreUnknownKeys = true; encodeDefaults = true } }

    companion object {
        private const val TAG = "ClipboardHistoryStore"
        private const val MAX_HISTORY = 200
        private const val MAX_PINNED = 200
        private const val KEY_CLIP_HISTORY_JSON = "clip_history"
        private const val KEY_CLIP_PINNED_JSON = "clip_pinned"
    }

    fun getAll(): List<Entry> {
        return getPinned() + getHistory()
    }

    fun getPinned(): List<Entry> {
        return try {
            val s = sp.getString(KEY_CLIP_PINNED_JSON, null) ?: return emptyList()
            json.decodeFromString<List<Entry>>(s).sortedByDescending { it.ts }.filter { it.pinned }
        } catch (t: Throwable) {
            Log.w(TAG, "parse pinned failed", t)
            emptyList()
        }
    }

    fun getHistory(): List<Entry> {
        return try {
            val s = sp.getString(KEY_CLIP_HISTORY_JSON, null) ?: return emptyList()
            json.decodeFromString<List<Entry>>(s).sortedByDescending { it.ts }.filter { !it.pinned }
        } catch (t: Throwable) {
            Log.w(TAG, "parse history failed", t)
            emptyList()
        }
    }

    fun totalCount(): Int = getPinned().size + getHistory().size

    /**
     * 将当前剪贴板文本追加到历史（作为非固定项）。
     * 若与最新的一条非固定记录相同，则跳过。
     */
    fun addFromClipboard(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        try {
            val his = getHistory().toMutableList()
            if (his.firstOrNull()?.text == trimmed) return
            his.add(0, Entry(UUID.randomUUID().toString(), trimmed, System.currentTimeMillis(), pinned = false))
            while (his.size > MAX_HISTORY) if (his.isNotEmpty()) his.removeAt(his.lastIndex) else break
            sp.edit().putString(KEY_CLIP_HISTORY_JSON, json.encodeToString(his)).apply()
        } catch (t: Throwable) {
            Log.e(TAG, "addFromClipboard failed", t)
        }
    }

    /**
     * 切换固定状态（根据 id）。返回新的固定状态。
     * 若该条在历史中，则移入/移出固定集合；保持两个集合各自有序（按时间倒序）。
     */
    fun togglePin(id: String): Boolean {
        val pinned = getPinned().toMutableList()
        val history = getHistory().toMutableList()
        // 先在历史里找
        val idxH = history.indexOfFirst { it.id == id }
        if (idxH >= 0) {
            val e = history.removeAt(idxH)
            val pe = e.copy(pinned = true, ts = System.currentTimeMillis())
            pinned.add(0, pe)
            while (pinned.size > MAX_PINNED) if (pinned.isNotEmpty()) pinned.removeAt(pinned.lastIndex) else break
            persist(pinned, history)
            return true
        }
        // 再在固定里找 -> 取消固定
        val idxP = pinned.indexOfFirst { it.id == id }
        if (idxP >= 0) {
            val e = pinned.removeAt(idxP)
            val he = e.copy(pinned = false, ts = System.currentTimeMillis())
            history.add(0, he)
            while (history.size > MAX_HISTORY) if (history.isNotEmpty()) history.removeAt(history.lastIndex) else break
            persist(pinned, history)
            return false
        }
        return false
    }

    /** 删除指定时间之前的非固定记录（不影响固定）。返回删除数量。 */
    fun deleteHistoryBefore(cutoffEpochMs: Long): Int {
        val history = getHistory().toMutableList()
        val beforeSize = history.size
        val remain = history.filter { it.ts >= cutoffEpochMs }
        sp.edit().putString(KEY_CLIP_HISTORY_JSON, json.encodeToString(remain)).apply()
        return beforeSize - remain.size
    }

    /** 清空所有非固定记录 */
    fun clearAllNonPinned(): Int {
        val sz = getHistory().size
        sp.edit().remove(KEY_CLIP_HISTORY_JSON).apply()
        return sz
    }

    private fun persist(pinned: List<Entry>, history: List<Entry>) {
        try {
            sp.edit()
                .putString(KEY_CLIP_PINNED_JSON, json.encodeToString(pinned.sortedByDescending { it.ts }))
                .putString(KEY_CLIP_HISTORY_JSON, json.encodeToString(history.sortedByDescending { it.ts }))
                .apply()
        } catch (t: Throwable) {
            Log.e(TAG, "persist failed", t)
        }
    }

    /** 从非固定历史中删除指定ID的记录 */
    fun deleteHistoryById(id: String): Boolean {
        return try {
            val history = getHistory().toMutableList()
            val idx = history.indexOfFirst { it.id == id }
            if (idx >= 0) {
                history.removeAt(idx)
                sp.edit().putString(KEY_CLIP_HISTORY_JSON, json.encodeToString(history)).apply()
                true
            } else false
        } catch (t: Throwable) {
            Log.e(TAG, "deleteHistoryById failed", t)
            false
        }
    }

    /**
     * 粘贴到目标输入框
     */
    fun pasteInto(ic: android.view.inputmethod.InputConnection?, text: String) {
        if (ic == null) return
        try {
            ic.commitText(text, 1)
        } catch (e: Throwable) {
            Log.e(TAG, "commitText failed", e)
        }
    }
}
