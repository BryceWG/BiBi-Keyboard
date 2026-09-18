// Stores re-recognition audio in the app-private, no-backup directory.
package com.brycewg.asrkb.store

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AsrHistoryAudioStore(context: Context) {
    companion object {
        private const val TAG = "AsrHistoryAudioStore"
        private const val DIRECTORY = "asr_history_audio"
        private const val EXTENSION = ".pcm"
        private const val PENDING_FILE_GRACE_MS = 10 * 60 * 1000L
        private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val deletedIds = ConcurrentHashMap.newKeySet<String>()
        private val storageGeneration = AtomicLong(0L)

        fun saveAsync(context: Context, recordId: String, pcm16kMono: ByteArray) {
            val appContext = context.applicationContext
            val scheduledGeneration = storageGeneration.get()
            ioScope.launch {
                val prefs = Prefs(appContext)
                if (scheduledGeneration != storageGeneration.get() ||
                    recordId in deletedIds ||
                    prefs.disableAsrHistory ||
                    prefs.audioHistoryRetentionCount <= 0
                ) {
                    return@launch
                }
                val store = AsrHistoryAudioStore(appContext)
                if (store.save(recordId, pcm16kMono)) {
                    val latestPrefs = Prefs(appContext)
                    if (scheduledGeneration != storageGeneration.get() ||
                        latestPrefs.disableAsrHistory ||
                        latestPrefs.audioHistoryRetentionCount <= 0
                    ) {
                        store.delete(recordId)
                        return@launch
                    }
                    val ids = AsrHistoryStore(appContext).listIdsNewestFirstOrNull()
                        ?: return@launch
                    store.prune(ids, latestPrefs.audioHistoryRetentionCount)
                }
            }
        }

        fun pruneAsync(context: Context, maxCount: Int) {
            val appContext = context.applicationContext
            ioScope.launch {
                val ids = AsrHistoryStore(appContext).listIdsNewestFirstOrNull()
                    ?: return@launch
                AsrHistoryAudioStore(appContext).prune(ids, maxCount)
            }
        }

        /**
         * 把已写好的 PCM 文件同步晋升为录音历史音频（rename 优先，跨分区退回流式拷贝）。
         * 与 [saveAsync] 同一套校验（generation/deletedIds/偏好），供会话段存储晋升调用；
         * 成功返回目标文件，失败返回 null。
         */
        fun saveFromFile(context: Context, recordId: String, sourceFile: File): File? {
            val appContext = context.applicationContext
            val scheduledGeneration = storageGeneration.get()
            val prefs = Prefs(appContext)
            if (scheduledGeneration != storageGeneration.get() ||
                recordId in deletedIds ||
                prefs.disableAsrHistory ||
                prefs.audioHistoryRetentionCount <= 0
            ) {
                return null
            }
            if (!sourceFile.isFile || sourceFile.length() <= 0L) return null
            val store = AsrHistoryAudioStore(appContext)
            val target = store.saveFile(recordId, sourceFile) ?: return null
            val latestPrefs = Prefs(appContext)
            if (scheduledGeneration != storageGeneration.get() ||
                recordId in deletedIds ||
                latestPrefs.disableAsrHistory ||
                latestPrefs.audioHistoryRetentionCount <= 0
            ) {
                store.delete(recordId)
                return null
            }
            val ids = AsrHistoryStore(appContext).listIdsNewestFirstOrNull()
            if (ids != null) {
                store.prune(ids, latestPrefs.audioHistoryRetentionCount)
            }
            return target
        }
    }

    private val directory = File(context.noBackupFilesDir, DIRECTORY)

    fun hasAudio(recordId: String): Boolean = audioFile(recordId).isFile

    fun readAudio(recordId: String): ByteArray? = try {
        audioFile(recordId).takeIf { it.isFile }?.readBytes()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to read archived audio", e)
        null
    }

    fun save(recordId: String, pcm16kMono: ByteArray): Boolean {
        if (pcm16kMono.isEmpty()) return false
        if (recordId in deletedIds) return false
        return try {
            if (!directory.exists() && !directory.mkdirs()) return false
            val target = audioFile(recordId)
            val temp = File(directory, "$recordId.tmp")
            FileOutputStream(temp).use { it.write(pcm16kMono) }
            if (target.exists() && !target.delete()) {
                temp.delete()
                return false
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return false
            }
            if (recordId in deletedIds) {
                target.delete()
                return false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive audio", e)
            runCatching { File(directory, "$recordId.tmp").delete() }
            false
        }
    }

    /** 与 [save] 等价，但数据来自已写好的文件；rename 失败退回 4KB 流式拷贝。成功返回目标文件。 */
    fun saveFile(recordId: String, sourceFile: File): File? {
        if (recordId in deletedIds) return null
        return try {
            if (!directory.exists() && !directory.mkdirs()) return null
            val target = audioFile(recordId)
            if (target.exists() && !target.delete()) return null
            if (sourceFile.renameTo(target)) {
                if (recordId in deletedIds) {
                    target.delete()
                    return null
                }
                return target
            }
            val temp = File(directory, "$recordId.tmp")
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(temp).use { output -> input.copyTo(output) }
            }
            if (recordId in deletedIds || !temp.renameTo(target)) {
                temp.delete()
                return null
            }
            sourceFile.delete()
            target
        } catch (e: Exception) {
            Log.e(TAG, "Failed to archive audio from file", e)
            runCatching { File(directory, "$recordId.tmp").delete() }
            null
        }
    }

    fun delete(recordId: String) {
        deletedIds.add(recordId)
        runCatching { audioFile(recordId).delete() }
            .onFailure { Log.w(TAG, "Failed to delete archived audio", it) }
    }

    fun clearAll() {
        storageGeneration.incrementAndGet()
        directory.listFiles()?.forEach { file ->
            if (file.isFile && !file.delete()) Log.w(TAG, "Failed to delete ${file.name}")
        }
        deletedIds.clear()
    }

    fun prune(idsNewestFirst: List<String>, maxCount: Int) {
        if (maxCount <= 0) {
            clearAll()
            return
        }
        val knownIds = idsNewestFirst.toSet()
        val keepIds = idsNewestFirst.asSequence()
            .filter(::hasAudio)
            .take(maxCount.coerceIn(0, 100))
            .toSet()
        val now = System.currentTimeMillis()
        directory.listFiles()?.forEach { file ->
            val recordId = file.name.removeSuffix(EXTENSION)
            val pending = file.extension == "pcm" &&
                recordId !in knownIds &&
                now - file.lastModified() < PENDING_FILE_GRACE_MS
            val keep = file.extension == "pcm" && (recordId in keepIds || pending)
            if (!keep && !file.delete()) Log.w(TAG, "Failed to prune ${file.name}")
        }
    }

    private fun audioFile(recordId: String): File = File(directory, "$recordId$EXTENSION")
}
