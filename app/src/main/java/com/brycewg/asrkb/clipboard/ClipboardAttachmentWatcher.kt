/** 剪贴板附件目录的事件监听、增量扫描与上传进度记录。 */
package com.brycewg.asrkb.clipboard

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

internal fun isSyncClipboardDownloadDirectory(treeUri: Uri): Boolean = try {
    DocumentsContract.getTreeDocumentId(treeUri)
        .replace('\\', '/')
        .trimEnd('/')
        .endsWith(":Download/BiBi", ignoreCase = true)
} catch (_: Throwable) {
    false
}

internal data class LocalClipboardAttachment(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: ClipboardAttachmentKind,
    val signature: String,
    val lastModifiedMillis: Long
)

internal fun selectLatestAttachment(
    attachments: List<LocalClipboardAttachment>,
    isEligible: (LocalClipboardAttachment) -> Boolean
): LocalClipboardAttachment? = attachments.filter(isEligible).maxWithOrNull(
    compareBy<LocalClipboardAttachment> { it.lastModifiedMillis }.thenBy { it.signature }
)

internal class ClipboardAttachmentWatcher(
    context: Context,
    private val prefs: Prefs,
    private val policy: ClipboardAttachmentPolicy
) {
    private val appContext = context.applicationContext
    private val seenPrefs = appContext.getSharedPreferences(SEEN_PREFS, Context.MODE_PRIVATE)

    /** 调用方在 IO dispatcher 运行；取消会同时释放监听，不等待下游阻塞上传结束。 */
    suspend fun watchAndUpload(upload: (LocalClipboardAttachment) -> Boolean) {
        val root = watchRoot() ?: return
        val watchContext = currentCoroutineContext()
        try {
            directoryChanges(root).collect {
                ClipboardAttachmentTransferGate.run {
                    watchContext.ensureActive()
                    scanAndUploadLocked(root, upload) { watchContext.ensureActive() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard attachment directory watch stopped", e)
            logWarning("clip_attachment_watch_failed", mapOf("errorType" to e.javaClass.simpleName))
        }
    }

    private fun directoryChanges(root: Uri) = callbackFlow {
        val resolver = appContext.contentResolver
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        // AOSP 将底层 FileObserver 绑定到 Cursor 生命周期。只保留 ID 列作监听租约，
        // 快照查询独立关闭，整个会话期间不撤销目录监听。
        val cursor = resolver.query(
            childrenUri(root),
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null
        ) ?: error("Directory provider returned no cursor")
        cursor.use {
            try {
                currentCoroutineContext().ensureActive()
                // 跨进程 Cursor 通过 Binder observer 转发变更，不透传 notificationUri。
                // 直接订阅 Cursor，不能用 notificationUri 是否为空判断监听是否可用。
                cursor.registerContentObserver(observer)
                logBase("clip_attachment_watch_started", emptyMap())
                // 订阅完成后才取首个快照，覆盖查询监听 Cursor 到订阅完成之间的变化。
                trySend(Unit)
                awaitClose { }
            } finally {
                cursor.unregisterContentObserver(observer)
                logBase("clip_attachment_watch_stopped", emptyMap())
            }
        }
    }.buffer(Channel.CONFLATED)

    fun scanAndUpload(upload: (LocalClipboardAttachment) -> Boolean) {
        val root = watchRoot() ?: return
        ClipboardAttachmentTransferGate.run { scanAndUploadLocked(root, upload) {} }
    }

    private fun watchRoot(): Uri? {
        if (!policy.hasEnabledType()) return null
        val treeUri = prefs.syncClipboardWatchTreeUri.takeIf { it.isNotBlank() } ?: return null
        val root = try {
            Uri.parse(treeUri)
        } catch (t: Throwable) {
            Log.w(TAG, "Invalid clipboard watch tree URI", t)
            return null
        }
        if (isSyncClipboardDownloadDirectory(root)) {
            Log.w(TAG, "Ignore Download/BiBi as clipboard attachment watch directory")
            prefs.syncClipboardWatchTreeUri = ""
            return null
        }
        return root
    }

    private fun childrenUri(root: Uri): Uri = DocumentsContract.buildChildDocumentsUriUsingTree(
        root,
        DocumentsContract.getTreeDocumentId(root)
    )

    private fun scanAndUploadLocked(
        root: Uri,
        upload: (LocalClipboardAttachment) -> Boolean,
        checkActive: () -> Unit
    ) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val treeKey = root.toString()
        val firstScanForTree = seenPrefs.getString(KEY_TREE_URI, null) != treeKey
        val attachments = mutableListOf<LocalClipboardAttachment>()
        val scanStartedAt = SystemClock.elapsedRealtime()
        try {
            val queried = appContext.contentResolver.query(childrenUri(root), projection, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    checkActive()
                    val mime = cursor.getString(mimeIndex) ?: continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR || cursor.isNull(sizeIndex)) continue
                    val size = cursor.getLong(sizeIndex)
                    val kind = if (mime.startsWith("image/", ignoreCase = true)) {
                        ClipboardAttachmentKind.IMAGE
                    } else {
                        ClipboardAttachmentKind.FILE
                    }
                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(idIndex))
                    val modified = if (cursor.isNull(modifiedIndex)) 0L else cursor.getLong(modifiedIndex)
                    val signature = buildString {
                        append(documentUri)
                        append('|')
                        append(size)
                        append('|')
                        append(modified)
                    }
                    attachments += LocalClipboardAttachment(
                        uri = documentUri,
                        displayName = cursor.getString(nameIndex).orEmpty().ifBlank { "attachment" },
                        mimeType = mime,
                        sizeBytes = size,
                        kind = kind,
                        signature = signature,
                        lastModifiedMillis = modified
                    )
                }
                true
            } ?: false
            checkActive()
            if (!queried) {
                // provider 掉线或 URI 权限失效时不能落基线，否则下一轮会把既存文件当成新增上传。
                Log.w(TAG, "Clipboard watch tree query returned no cursor")
                logWarning("clip_attachment_scan_no_cursor", mapOf("firstScan" to firstScanForTree))
                return
            }
            DebugLogManager.log(
                "app",
                "clip_attachment_scan",
                mapOf("count" to attachments.size, "elapsedMs" to SystemClock.elapsedRealtime() - scanStartedAt)
            )
            if (firstScanForTree) {
                // 新目录整体作为基线，历史文件不参与上传。
                writeBaseline(treeKey, attachments)
                logBase("clip_attachment_baseline_set", mapOf("count" to attachments.size))
                return
            }
            val baseline = readBaseline()
            val newAttachments = attachments.filter(baseline::isNew)
            val attachment = selectLatestAttachment(newAttachments) {
                policy.allows(it.kind, it.sizeBytes)
            }
            if (attachment == null) {
                markProcessed(newAttachments)
                return
            }
            markProcessed(
                newAttachments.filter {
                    it.lastModifiedMillis <= attachment.lastModifiedMillis && it.signature != attachment.signature
                }
            )
            checkActive()
            if (upload(attachment)) {
                // 已完成的上传仍记入进度；锁内提交，恢复后的扫描不会重复上传。
                markProcessed(
                    newAttachments.filter { it.lastModifiedMillis > attachment.lastModifiedMillis } + attachment
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to scan clipboard watch tree", t)
            logWarning("clip_attachment_scan_failed", mapOf("firstScan" to firstScanForTree), t)
        }
    }

    /** 已处理进度：最大 lastModified 与该时间戳上的签名集合，天然限制集合容量。 */
    private class SeenBaseline(val latestModified: Long, val signatures: Set<String>) {
        fun isNew(attachment: LocalClipboardAttachment): Boolean = attachment.lastModifiedMillis > latestModified ||
            (attachment.lastModifiedMillis == latestModified && !signatures.contains(attachment.signature))
    }

    private fun readBaseline(): SeenBaseline = SeenBaseline(
        latestModified = seenPrefs.getLong(KEY_LATEST_MODIFIED, Long.MIN_VALUE),
        signatures = seenPrefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()
    )

    /** 新目录基线：一次提交写入进度与目录标识，避免逐文件落盘。 */
    private fun writeBaseline(treeKey: String, attachments: List<LocalClipboardAttachment>) {
        val editor = seenPrefs.edit()
        val latest = attachments.maxOfOrNull { it.lastModifiedMillis }
        if (latest == null) {
            editor.remove(KEY_LATEST_MODIFIED).remove(KEY_SEEN)
        } else {
            editor.putLong(KEY_LATEST_MODIFIED, latest)
                .putStringSet(KEY_SEEN, attachments.signaturesAt(latest))
        }
        editor.putString(KEY_TREE_URI, treeKey).apply()
    }

    private fun markProcessed(attachments: Collection<LocalClipboardAttachment>) {
        if (attachments.isEmpty()) return
        val storedLatest = seenPrefs.getLong(KEY_LATEST_MODIFIED, Long.MIN_VALUE)
        val latest = maxOf(storedLatest, attachments.maxOf { it.lastModifiedMillis })
        val signatures = attachments.signaturesAt(latest)
        if (latest > storedLatest) {
            // 时间戳前进后旧签名不再需要保留，集合因此不会无界增长。
            seenPrefs.edit()
                .putLong(KEY_LATEST_MODIFIED, latest)
                .putStringSet(KEY_SEEN, signatures)
                .apply()
            return
        }
        val seen = seenPrefs.getStringSet(KEY_SEEN, emptySet()).orEmpty()
        if (seen.containsAll(signatures)) return
        seenPrefs.edit().putStringSet(KEY_SEEN, seen + signatures).apply()
    }

    private fun Collection<LocalClipboardAttachment>.signaturesAt(modifiedMillis: Long): Set<String> = asSequence().filter { it.lastModifiedMillis == modifiedMillis }.map { it.signature }.toSet()

    private fun logBase(event: String, data: Map<String, Any?>) {
        try {
            DebugLogManager.logBase(appContext, "app", event, data)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write clipboard diagnostic event: $event", t)
        }
    }

    private fun logWarning(event: String, data: Map<String, Any?>, throwable: Throwable? = null) {
        try {
            DebugLogManager.logWarning(appContext, "app", event, throwable, data)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to write clipboard diagnostic event: $event", t)
        }
    }

    companion object {
        private const val TAG = "ClipboardAttachmentWatcher"
        private const val SEEN_PREFS = "clipboard_attachment_uploads"
        private const val KEY_SEEN = "seen"
        private const val KEY_LATEST_MODIFIED = "latest_modified"
        private const val KEY_TREE_URI = "tree_uri"
    }
}
