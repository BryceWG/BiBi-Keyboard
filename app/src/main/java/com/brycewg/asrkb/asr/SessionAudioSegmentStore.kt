// 非流式渐进分段的会话音频落盘：cacheDir 临时 PCM 文件 + 段 manifest，供段级重试与录音历史音频晋升共用。
// 归属模块：asr
package com.brycewg.asrkb.asr

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.store.AsrHistoryAudioStore
import com.brycewg.asrkb.store.debug.DebugLogManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** 暴露会话级音频段存储，供录音历史音频晋升绑定。 */
interface SessionAudioSourceOwner {
    val sessionAudioStore: SessionAudioSegmentStore?
}

class SessionAudioSegmentStore(context: Context) {

    private class SegmentSpan(val offset: Int, val length: Int, val probabilities: VadProbabilities?)

    private val appContext = context.applicationContext
    private val file = File(File(appContext.cacheDir, DIRECTORY), "${UUID.randomUUID()}.pcm")

    private val segments = ArrayList<SegmentSpan>()
    private var sessionProbabilities: VadProbabilities? = null
    private var output: FileOutputStream? = null
    private var totalWritten = 0L
    private var failed = false
    private var closed = false

    // 晋升成功后指向录音历史音频文件；重试读取走该文件。
    private var transferredFile: File? = null

    val hasAudio: Boolean
        get() = synchronized(this) {
            (segments.isNotEmpty() || transferredFile?.isFile == true) && !failed
        }

    val segmentCount: Int
        get() = synchronized(this) { segments.size }

    val totalBytes: Long
        get() = synchronized(this) { totalWritten }

    /** 音频已晋升为录音历史文件（临时文件所有权已转移）。 */
    val isTransferred: Boolean
        get() = synchronized(this) { transferredFile != null }

    /**
     * 追加一个已封闭的分段并登记 manifest，返回段号。
     * 写盘失败后进入降级态并返回 -1：识别照常继续，段级重试不可用。
     */
    fun appendSegment(pcm: ByteArray, probabilities: VadProbabilities? = null): Int {
        return synchronized(this) {
            if (closed || failed || pcm.isEmpty()) return@synchronized -1
            val offset = totalWritten
            try {
                val out = output ?: createOutput()
                out.write(pcm)
                out.flush()
                totalWritten += pcm.size
                val index = segments.size
                segments.add(SegmentSpan(offset.toInt(), pcm.size, probabilities))
                sessionProbabilities = null
                index
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to append session audio segment", t)
                DebugLogManager.logError(appContext, "asr", "segment_store_write_failed", t)
                degradeAndCleanupLocked()
                -1
            }
        }
    }

    /** 按段号读取段音频；降级或越界返回 null。 */
    fun readSegment(index: Int): ByteArray? {
        val span = synchronized(this) {
            if (failed) null else segments.getOrNull(index)
        } ?: return null
        return readRange(span.offset, span.length)
    }

    /** 段级重试直接复用录音时的概率，不创建新的模型。 */
    internal fun readSegmentProbabilities(index: Int): VadProbabilities? = synchronized(this) {
        segments.getOrNull(index)?.probabilities
    }

    /** 整轮重试按原始 PCM 段顺序拼接概率，晋升音频到历史文件时也保留这份元数据。 */
    internal fun readProbabilities(): VadProbabilities? = synchronized(this) {
        sessionProbabilities?.let { return@synchronized it }
        if (segments.isEmpty()) return@synchronized null
        val parts = segments.map { it.probabilities ?: return@synchronized null }
        VadProbabilities.concatenate(parts).also { sessionProbabilities = it }
    }

    /** 读取全会话音频，供"重试最近一次识别"整段重切使用；已晋升时读历史文件。 */
    fun readAll(): ByteArray? {
        val snapshot = synchronized(this) {
            when {
                failed -> null
                transferredFile != null -> transferredFile
                segments.isEmpty() -> null
                else -> file
            }
        } ?: return null
        return try {
            snapshot.readBytes()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read session audio", t)
            null
        }
    }

    /**
     * 把会话音频晋升为录音历史音频（rename 优先，跨分区退回流式拷贝）。
     * 成功后文件所有权转移给历史存储，后续 [closeAndDelete] 只清临时文件。
     */
    fun promoteTo(recordId: String): Boolean {
        return synchronized(this) {
            if (closed || failed || segments.isEmpty()) return@synchronized false
            closeOutputLocked()
            val target = try {
                AsrHistoryAudioStore.saveFromFile(appContext, recordId, file)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to promote session audio to history", t)
                null
            }
            transferredFile = target
            if (target != null) closed = true
            target != null
        }
    }

    /** 会话结束未被晋升时清理临时文件；已晋升时只清临时文件（历史文件不受影响）。 */
    fun closeAndDelete() {
        synchronized(this) {
            closed = true
            closeOutputLocked()
            segments.clear()
            sessionProbabilities = null
            runCatching { file.delete() }
                .onFailure { Log.w(TAG, "Failed to delete session audio file", it) }
        }
    }

    private fun createOutput(): FileOutputStream {
        file.parentFile?.let { if (!it.exists() && !it.mkdirs()) error("mkdirs failed: $it") }
        return FileOutputStream(file).also { output = it }
    }

    private fun closeOutputLocked() {
        try {
            output?.flush()
            output?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to close session audio file", t)
        }
        output = null
    }

    private fun degradeAndCleanupLocked() {
        failed = true
        closeOutputLocked()
        segments.clear()
        sessionProbabilities = null
        runCatching { file.delete() }
            .onFailure { Log.w(TAG, "Failed to delete session audio file", it) }
    }

    private fun readRange(offset: Int, length: Int): ByteArray? {
        return try {
            if (length <= 0) return null
            FileInputStream(file).use { input ->
                var toSkip = offset.toLong()
                while (toSkip > 0) {
                    val skipped = input.skip(toSkip)
                    if (skipped <= 0) return null
                    toSkip -= skipped
                }
                val bytes = ByteArray(length)
                var read = 0
                while (read < length) {
                    val n = input.read(bytes, read, length - read)
                    if (n < 0) return null
                    read += n
                }
                bytes
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read session audio range", t)
            null
        }
    }

    companion object {
        private const val TAG = "SessionAudioStore"
        private const val DIRECTORY = "asr_session_audio"
    }
}
