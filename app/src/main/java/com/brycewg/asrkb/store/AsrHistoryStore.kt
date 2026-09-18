package com.brycewg.asrkb.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * ASR 历史记录存储
 * - 使用 SQLite 行级读写；旧版 SharedPreferences JSON 在首次打开时迁入
 * - 备份导入/导出仍使用 JSON 数组（Prefs KEY_ASR_HISTORY_JSON）
 */
class AsrHistoryStore(context: Context) {
    companion object {
        private const val TAG = "AsrHistoryStore"
        private const val SP_NAME = "asr_prefs"
        private const val KEY_ASR_HISTORY_JSON = "asr_history"

        // 防止无限增长，保留最近 N 条
        private const val MAX_RECORDS = 2000
        private val HISTORY_LOCK = Any()
        private val migrateFailedThisProcess = AtomicBoolean(false)
        private val dbUnavailableLogged = AtomicBoolean(false)
    }

    private val appContext = context.applicationContext
    private val sp = appContext.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
    private val database = AsrHistoryDatabase.get(appContext)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    @Serializable
    enum class AiPostStatus {
        NONE,
        SUCCESS,
        FAILED
    }

    @Serializable
    enum class AsrHistoryStatus {
        SUCCESS,
        FAILED,
        CANCELLED
    }

    @Serializable
    enum class AsrHistoryFailStage {
        NONE,
        RECORDING,
        RECOGNITION
    }

    @Serializable
    data class AsrHistoryRecord(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long,
        val text: String,
        // ASR 引擎返回、进入任何末处理前的原始文本；旧记录没有该字段。
        val rawText: String? = null,
        val vendorId: String,
        val audioMs: Long,
        // 端到端总耗时（毫秒）：从开始录音到最终提交完成（含识别/后处理/打字机动画等待等）。
        // 旧记录无该字段时视为 0。
        val totalElapsedMs: Long = 0,
        // 供应商处理耗时（非流式文件识别时有效，毫秒）。OSS 旧记录无该字段时视为 0。
        val procMs: Long = 0,
        val source: String, // "ime" | "floating" | "external"
        val aiProcessed: Boolean,
        // AI 后处理耗时（毫秒）。未尝试或旧记录无该字段时视为 0。
        val aiPostMs: Long = 0,
        // AI 后处理状态。旧记录无该字段时视为 NONE。
        val aiPostStatus: AiPostStatus = AiPostStatus.NONE,
        // 当次实际尝试后处理使用的 LLM 渠道。旧记录没有该字段。
        val llmVendorId: String? = null,
        val charCount: Int,
        // 会话结果。旧记录无该字段时视为 SUCCESS。
        val status: AsrHistoryStatus = AsrHistoryStatus.SUCCESS,
        // 失败/取消发生的阶段。旧记录无该字段时视为 NONE。
        val failStage: AsrHistoryFailStage = AsrHistoryFailStage.NONE,
        // 稳定失败原因码，不存本地化文案。成功记录为 null。
        val failReasonCode: String? = null,
        // 阶段耗时轨迹。旧版本创建的记录没有该字段。
        val timingTrace: AsrHistoryTimingTrace? = null,
        // 自动选择提示词的结果快照。旧记录与未启用自动选择的记录为 null。
        val promptSelection: PromptSelectionStatus? = null
    ) {
        val isUnsuccessful: Boolean
            get() = status != AsrHistoryStatus.SUCCESS
    }

    fun add(record: AsrHistoryRecord) {
        withWritableDb {
            it.beginTransaction()
            try {
                database.insertOrReplace(it, record)
                database.pruneToMax(it, MAX_RECORDS)
                it.setTransactionSuccessful()
            } finally {
                it.endTransaction()
            }
        }
    }

    fun updateById(
        id: String,
        transform: (AsrHistoryRecord) -> AsrHistoryRecord
    ): AsrHistoryRecord? {
        return withWritableDb<AsrHistoryRecord?> {
            val current = database.queryById(it, id) ?: return@withWritableDb null
            val updated = transform(current).copy(id = id)
            database.insertOrReplace(it, updated)
            updated
        }
    }

    fun listAll(): List<AsrHistoryRecord> = withReadableDb(emptyList()) { db ->
        database.queryAllNewestFirst(db)
    }

    /**
     * 取出最近 [limit] 条记录（含失败/取消；跳过空白成功记录）。
     */
    fun listRecent(limit: Int): List<AsrHistoryRecord> {
        if (limit <= 0) return emptyList()
        return withReadableDb(emptyList()) { db ->
            database.queryRecent(db, limit)
        }
    }

    /** 全部记录 id，按时间倒序。供音频清理使用，避免整表反序列化。 */
    fun listIdsNewestFirst(): List<String> = withReadableDb(emptyList()) { db ->
        database.queryIdsNewestFirst(db)
    }

    internal fun listIdsNewestFirstOrNull(): List<String>? = synchronized(HISTORY_LOCK) {
        migrateFromPrefsIfNeeded()
        if (migrateFailedThisProcess.get()) return@synchronized null
        val db = database.readableOrNull() ?: return@synchronized null
        try {
            database.queryIdsNewestFirst(db)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read ASR history ids", e)
            logError("history_ids_read_failed", e)
            null
        }
    }

    fun deleteByIds(ids: Set<String>): Int {
        if (ids.isEmpty()) return 0
        return withWritableDb { database.deleteByIds(it, ids) }
    }

    fun clearAll() {
        withWritableDb { database.deleteAll(it) }
    }

    fun exportJson(): String {
        synchronized(HISTORY_LOCK) {
            migrateFromPrefsIfNeeded()
            check(!sp.contains(KEY_ASR_HISTORY_JSON)) {
                "ASR history migration is incomplete"
            }
            val db = database.readableOrNull()
                ?: throw SQLiteException("Failed to open ASR history database for export")
            return json.encodeToString(database.queryAllNewestFirst(db))
        }
    }

    fun replaceAllFromJson(raw: String) {
        val records = parseLegacyJson(raw)
            .sortedByDescending { it.timestamp }
            .distinctBy { it.id }
        withWritableDb { db ->
            db.beginTransaction()
            try {
                database.deleteAll(db)
                records.forEach { database.insertOrReplace(db, it) }
                database.pruneToMax(db, MAX_RECORDS)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        if (!sp.edit().remove(KEY_ASR_HISTORY_JSON).commit()) {
            throw IllegalStateException("Failed to clear legacy ASR history after restore")
        }
    }

    private fun <T> withReadableDb(fallback: T, block: (SQLiteDatabase) -> T): T {
        synchronized(HISTORY_LOCK) {
            migrateFromPrefsIfNeeded()
            if (migrateFailedThisProcess.get()) return fallback
            val db = database.readableOrNull() ?: return unavailable(fallback)
            return try {
                block(db)
            } catch (e: Exception) {
                Log.e(TAG, "ASR history read failed", e)
                logError("history_read_failed", e)
                fallback
            }
        }
    }

    private fun <T> withWritableDb(block: (SQLiteDatabase) -> T): T {
        synchronized(HISTORY_LOCK) {
            migrateFromPrefsIfNeeded()
            check(!migrateFailedThisProcess.get()) {
                "ASR history migration failed; retry after cold start"
            }
            val db = database.writableOrNull()
                ?: throw SQLiteException("Failed to open ASR history database for writing")
            return try {
                block(db)
            } catch (e: Exception) {
                Log.e(TAG, "ASR history write failed", e)
                logError("history_write_failed", e)
                throw e
            }
        }
    }

    private fun migrateFromPrefsIfNeeded() {
        if (migrateFailedThisProcess.get()) return
        if (!sp.contains(KEY_ASR_HISTORY_JSON)) return
        val raw = sp.getString(KEY_ASR_HISTORY_JSON, "").orEmpty()
        val db = database.writableOrNull()
        if (db == null) {
            migrateFailedThisProcess.set(true)
            unavailable(Unit)
            return
        }
        try {
            val records = parseLegacyJson(raw)
                .sortedByDescending { it.timestamp }
                .distinctBy { it.id }
            db.beginTransaction()
            try {
                records.forEach { database.insertOrReplace(db, it) }
                database.pruneToMax(db, MAX_RECORDS)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            check(sp.edit().remove(KEY_ASR_HISTORY_JSON).commit()) {
                "Failed to clear migrated ASR history JSON"
            }
            logBase(
                "history_migrate_ok",
                mapOf("count" to records.size)
            )
        } catch (e: Exception) {
            migrateFailedThisProcess.set(true)
            Log.e(TAG, "Failed to migrate ASR history from prefs", e)
            logError("history_migrate_failed", e)
        }
    }

    private fun parseLegacyJson(raw: String): List<AsrHistoryRecord> {
        if (raw.isBlank()) return emptyList()
        return json.decodeFromString<List<AsrHistoryRecord>>(raw)
    }

    private fun <T> unavailable(fallback: T): T {
        if (dbUnavailableLogged.compareAndSet(false, true)) {
            logBase("history_db_open_failed")
        }
        return fallback
    }

    private fun logBase(event: String, data: Map<String, Any?> = emptyMap()) {
        try {
            DebugLogManager.logBase(
                context = appContext,
                category = "asr",
                event = event,
                data = data
            )
        } catch (_: Throwable) { }
    }

    private fun logError(event: String, throwable: Throwable) {
        try {
            DebugLogManager.logError(
                context = appContext,
                category = "asr",
                event = event,
                throwable = throwable
            )
        } catch (_: Throwable) { }
    }
}
