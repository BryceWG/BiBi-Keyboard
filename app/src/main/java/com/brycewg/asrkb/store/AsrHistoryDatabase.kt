package com.brycewg.asrkb.store

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * ASR 历史 SQLite 表。一表一类；由 [AsrHistoryStore] 独占使用。
 *
 * 归属模块：store
 */
internal class AsrHistoryDatabase private constructor(
    context: Context
) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    companion object {
        private const val TAG = "AsrHistoryDatabase"
        private const val DB_NAME = "asr_history.db"
        private const val DB_VERSION = 2

        const val TABLE = "asr_history"
        const val COL_ID = "id"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_TEXT = "text"
        const val COL_RAW_TEXT = "raw_text"
        const val COL_VENDOR_ID = "vendor_id"
        const val COL_AUDIO_MS = "audio_ms"
        const val COL_TOTAL_ELAPSED_MS = "total_elapsed_ms"
        const val COL_PROC_MS = "proc_ms"
        const val COL_SOURCE = "source"
        const val COL_AI_PROCESSED = "ai_processed"
        const val COL_AI_POST_MS = "ai_post_ms"
        const val COL_AI_POST_STATUS = "ai_post_status"
        const val COL_LLM_VENDOR_ID = "llm_vendor_id"
        const val COL_CHAR_COUNT = "char_count"
        const val COL_STATUS = "status"
        const val COL_FAIL_STAGE = "fail_stage"
        const val COL_FAIL_REASON_CODE = "fail_reason_code"
        const val COL_TIMING_TRACE = "timing_trace"
        const val COL_PROMPT_SELECTION = "prompt_selection"

        @Volatile
        private var instance: AsrHistoryDatabase? = null

        fun get(context: Context): AsrHistoryDatabase = instance ?: synchronized(this) {
            instance ?: AsrHistoryDatabase(context.applicationContext).also { instance = it }
        }

        /** 版本升级实现（拆出便于单测直接验证 v1 → v2 的列迁移）。 */
        internal fun applyUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                // v2：自动选择提示词的结果快照，旧记录保持 NULL（视为未使用）。
                addColumnIfMissing(db, COL_PROMPT_SELECTION, "TEXT")
            }
        }

        private fun addColumnIfMissing(db: SQLiteDatabase, column: String, type: String) {
            if (hasColumn(db, column)) return
            try {
                db.execSQL("ALTER TABLE $TABLE ADD COLUMN $column $type")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add column $column to $TABLE", e)
            }
        }

        private fun hasColumn(db: SQLiteDatabase, column: String): Boolean = try {
            db.rawQuery("PRAGMA table_info($TABLE)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                var found = false
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && cursor.getString(nameIndex) == column) {
                        found = true
                        break
                    }
                }
                found
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inspect $TABLE schema", e)
            false
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                $COL_ID TEXT PRIMARY KEY NOT NULL,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_TEXT TEXT NOT NULL,
                $COL_RAW_TEXT TEXT,
                $COL_VENDOR_ID TEXT NOT NULL,
                $COL_AUDIO_MS INTEGER NOT NULL,
                $COL_TOTAL_ELAPSED_MS INTEGER NOT NULL DEFAULT 0,
                $COL_PROC_MS INTEGER NOT NULL DEFAULT 0,
                $COL_SOURCE TEXT NOT NULL,
                $COL_AI_PROCESSED INTEGER NOT NULL,
                $COL_AI_POST_MS INTEGER NOT NULL DEFAULT 0,
                $COL_AI_POST_STATUS TEXT NOT NULL DEFAULT 'NONE',
                $COL_LLM_VENDOR_ID TEXT,
                $COL_CHAR_COUNT INTEGER NOT NULL,
                $COL_STATUS TEXT NOT NULL DEFAULT 'SUCCESS',
                $COL_FAIL_STAGE TEXT NOT NULL DEFAULT 'NONE',
                $COL_FAIL_REASON_CODE TEXT,
                $COL_TIMING_TRACE TEXT,
                $COL_PROMPT_SELECTION TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX idx_asr_history_timestamp ON $TABLE ($COL_TIMESTAMP DESC)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = applyUpgrade(db, oldVersion, newVersion)

    fun writableOrNull(): SQLiteDatabase? = openOrNull(writable = true)

    fun readableOrNull(): SQLiteDatabase? = openOrNull(writable = false)

    fun insertOrReplace(db: SQLiteDatabase, record: AsrHistoryStore.AsrHistoryRecord) {
        val rowId = db.insertWithOnConflict(
            TABLE,
            null,
            toValues(record),
            SQLiteDatabase.CONFLICT_REPLACE
        )
        if (rowId == -1L) {
            throw SQLException("Failed to insert ASR history record")
        }
    }

    fun queryAllNewestFirst(db: SQLiteDatabase): List<AsrHistoryStore.AsrHistoryRecord> = db.query(
        TABLE,
        null,
        null,
        null,
        null,
        null,
        "$COL_TIMESTAMP DESC, $COL_ID DESC"
    ).use { cursor -> readRecords(cursor) }

    fun queryRecent(
        db: SQLiteDatabase,
        limit: Int
    ): List<AsrHistoryStore.AsrHistoryRecord> = db.query(
        TABLE,
        null,
        "TRIM($COL_TEXT) != '' OR $COL_STATUS != ?",
        arrayOf(AsrHistoryStore.AsrHistoryStatus.SUCCESS.name),
        null,
        null,
        "$COL_TIMESTAMP DESC, $COL_ID DESC",
        limit.toString()
    ).use { cursor -> readRecords(cursor) }

    fun queryIdsNewestFirst(db: SQLiteDatabase): List<String> = db.query(
        TABLE,
        arrayOf(COL_ID),
        null,
        null,
        null,
        null,
        "$COL_TIMESTAMP DESC, $COL_ID DESC"
    ).use { cursor ->
        val out = ArrayList<String>(cursor.count)
        val idIdx = cursor.getColumnIndexOrThrow(COL_ID)
        while (cursor.moveToNext()) {
            out.add(cursor.getString(idIdx))
        }
        out
    }

    fun queryById(db: SQLiteDatabase, id: String): AsrHistoryStore.AsrHistoryRecord? {
        return db.query(
            TABLE,
            null,
            "$COL_ID = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            cursorToRecord(cursor)
        }
    }

    fun deleteByIds(db: SQLiteDatabase, ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        var deleted = 0
        ids.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            deleted += db.delete(TABLE, "$COL_ID IN ($placeholders)", chunk.toTypedArray())
        }
        return deleted
    }

    fun deleteAll(db: SQLiteDatabase) {
        db.delete(TABLE, null, null)
    }

    fun pruneToMax(db: SQLiteDatabase, maxRecords: Int) {
        if (maxRecords <= 0) {
            deleteAll(db)
            return
        }
        val extra = DatabaseUtils.queryNumEntries(db, TABLE) - maxRecords
        if (extra <= 0L) return
        db.execSQL(
            """
            DELETE FROM $TABLE WHERE $COL_ID IN (
                SELECT $COL_ID FROM $TABLE
                ORDER BY $COL_TIMESTAMP ASC, $COL_ID ASC
                LIMIT $extra
            )
            """.trimIndent()
        )
    }

    private fun openOrNull(writable: Boolean): SQLiteDatabase? = try {
        if (writable) writableDatabase else readableDatabase
    } catch (e: Exception) {
        Log.e(TAG, "Failed to open ASR history database", e)
        null
    }

    private fun toValues(record: AsrHistoryStore.AsrHistoryRecord): ContentValues = ContentValues().apply {
        put(COL_ID, record.id)
        put(COL_TIMESTAMP, record.timestamp)
        put(COL_TEXT, record.text)
        putNullable(COL_RAW_TEXT, record.rawText)
        put(COL_VENDOR_ID, record.vendorId)
        put(COL_AUDIO_MS, record.audioMs)
        put(COL_TOTAL_ELAPSED_MS, record.totalElapsedMs)
        put(COL_PROC_MS, record.procMs)
        put(COL_SOURCE, record.source)
        put(COL_AI_PROCESSED, if (record.aiProcessed) 1 else 0)
        put(COL_AI_POST_MS, record.aiPostMs)
        put(COL_AI_POST_STATUS, record.aiPostStatus.name)
        putNullable(COL_LLM_VENDOR_ID, record.llmVendorId)
        put(COL_CHAR_COUNT, record.charCount)
        put(COL_STATUS, record.status.name)
        put(COL_FAIL_STAGE, record.failStage.name)
        putNullable(COL_FAIL_REASON_CODE, record.failReasonCode)
        putNullable(COL_TIMING_TRACE, record.timingTrace?.let { json.encodeToString(it) })
        putNullable(
            COL_PROMPT_SELECTION,
            record.promptSelection?.let { json.encodeToString(it) }
        )
    }

    private fun readRecords(cursor: Cursor): List<AsrHistoryStore.AsrHistoryRecord> {
        val out = ArrayList<AsrHistoryStore.AsrHistoryRecord>(cursor.count)
        while (cursor.moveToNext()) {
            out.add(cursorToRecord(cursor))
        }
        return out
    }

    private fun cursorToRecord(cursor: Cursor): AsrHistoryStore.AsrHistoryRecord = AsrHistoryStore.AsrHistoryRecord(
        id = cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)),
        timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
        text = cursor.getString(cursor.getColumnIndexOrThrow(COL_TEXT)).orEmpty(),
        rawText = cursor.optionalString(COL_RAW_TEXT),
        vendorId = cursor.getString(cursor.getColumnIndexOrThrow(COL_VENDOR_ID)).orEmpty(),
        audioMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_AUDIO_MS)),
        totalElapsedMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TOTAL_ELAPSED_MS)),
        procMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_PROC_MS)),
        source = cursor.getString(cursor.getColumnIndexOrThrow(COL_SOURCE)).orEmpty(),
        aiProcessed = cursor.getInt(cursor.getColumnIndexOrThrow(COL_AI_PROCESSED)) != 0,
        aiPostMs = cursor.getLong(cursor.getColumnIndexOrThrow(COL_AI_POST_MS)),
        aiPostStatus = enumValue(
            cursor.getString(cursor.getColumnIndexOrThrow(COL_AI_POST_STATUS)),
            AsrHistoryStore.AiPostStatus.NONE
        ),
        llmVendorId = cursor.optionalString(COL_LLM_VENDOR_ID),
        charCount = cursor.getInt(cursor.getColumnIndexOrThrow(COL_CHAR_COUNT)),
        status = enumValue(
            cursor.getString(cursor.getColumnIndexOrThrow(COL_STATUS)),
            AsrHistoryStore.AsrHistoryStatus.SUCCESS
        ),
        failStage = enumValue(
            cursor.getString(cursor.getColumnIndexOrThrow(COL_FAIL_STAGE)),
            AsrHistoryStore.AsrHistoryFailStage.NONE
        ),
        failReasonCode = cursor.optionalString(COL_FAIL_REASON_CODE),
        timingTrace = cursor.optionalString(COL_TIMING_TRACE)?.let { raw ->
            try {
                json.decodeFromString<AsrHistoryTimingTrace>(raw)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse timing trace", e)
                null
            }
        },
        promptSelection = cursor.optionalString(COL_PROMPT_SELECTION)?.let { raw ->
            try {
                json.decodeFromString<PromptSelectionStatus>(raw)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse prompt selection status", e)
                null
            }
        }
    )

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun Cursor.optionalString(column: String): String? {
        // 迁移未落地的库可能缺列，这里按“不存在即 NULL”处理，避免整表读取失败。
        val idx = getColumnIndex(column)
        if (idx < 0) return null
        return if (isNull(idx)) null else getString(idx)
    }

    private inline fun <reified T : Enum<T>> enumValue(raw: String?, fallback: T): T {
        if (raw.isNullOrBlank()) return fallback
        return try {
            java.lang.Enum.valueOf(T::class.java, raw)
        } catch (_: IllegalArgumentException) {
            fallback
        }
    }
}
