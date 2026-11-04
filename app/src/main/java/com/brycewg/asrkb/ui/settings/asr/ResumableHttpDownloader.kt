package com.brycewg.asrkb.ui.settings.asr

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * HTTP 文件下载（支持 Range 断点续传）。
 *
 * - 本地已有部分字节时发送 `Range: bytes={n}-`
 * - 206：按 Content-Range 校验后 append
 * - 200：截断后全量重写（服务器不支持或不接受 Range）
 * - 416：删除部分文件后无 Range 重试一次
 * - partial 元数据绑定 URL 与强 validator；切换下载源时丢弃旧缓存
 *
 * 失败/取消时保留部分文件，由调用方决定清理时机。
 */
internal class IncompleteDownloadException(
    val expectedBytes: Long,
    val actualBytes: Long
) : IOException("Incomplete download: got $actualBytes of $expectedBytes bytes")

internal object ResumableHttpDownloader {
    private const val TAG = "ResumableHttpDownloader"
    private const val RESUME_METADATA_SUFFIX = ".resume"
    private val CONTENT_RANGE_REGEX =
        Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)

    fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class ContentRange(
        val start: Long,
        val end: Long,
        val total: Long?
    )

    fun parseContentRange(header: String): ContentRange? {
        val match = CONTENT_RANGE_REGEX.matchEntire(header.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val totalRaw = match.groupValues[3]
        val total = if (totalRaw == "*") null else totalRaw.toLongOrNull() ?: return null
        if (start < 0L || end < start) return null
        if (total != null && total < 0L) return null
        return ContentRange(start = start, end = end, total = total)
    }

    /**
     * @param onProgress 进度百分比 0..100；总量未知时不会回调中间进度
     * @param onCallCreated 每次发起 HTTP Call 时回调，便于外部立即 cancel
     */
    fun download(
        client: OkHttpClient,
        url: String,
        destFile: File,
        isActive: () -> Boolean = { true },
        onProgress: (percent: Int) -> Unit = {},
        onCallCreated: (Call) -> Unit = {}
    ) {
        downloadInternal(
            client = client,
            url = url,
            destFile = destFile,
            allowResume = true,
            isActive = isActive,
            onProgress = onProgress,
            onCallCreated = onCallCreated
        )
    }

    /** Drops a partial/full cache when it was created for another source URL. */
    fun discardIfSourceChanged(destFile: File, url: String): Boolean {
        if (!destFile.exists()) return false
        if (readResumeValidator(destFile, url) != null) return false
        deletePartial(destFile)
        return true
    }

    private fun downloadInternal(
        client: OkHttpClient,
        url: String,
        destFile: File,
        allowResume: Boolean,
        isActive: () -> Boolean,
        onProgress: (percent: Int) -> Unit,
        onCallCreated: (Call) -> Unit
    ) {
        var existing = destFile.takeIf { it.exists() }?.length() ?: 0L
        val resumeValidator = if (allowResume && existing > 0L) {
            readResumeValidator(destFile, url)
        } else {
            null
        }
        if (existing > 0L && resumeValidator == null) {
            deletePartial(destFile)
            existing = 0L
        }
        val useRange = allowResume && existing > 0L && resumeValidator != null

        val request = Request.Builder()
            .url(url)
            .apply {
                if (useRange) {
                    header("Range", "bytes=$existing-")
                    header("If-Range", resumeValidator)
                }
            }
            .build()

        val call = client.newCall(request)
        onCallCreated(call)
        try {
            ensureActive(isActive, call)
            call.execute().use { resp ->
                when {
                    resp.code == 416 && useRange -> {
                        deletePartial(destFile)
                        downloadInternal(
                            client = client,
                            url = url,
                            destFile = destFile,
                            allowResume = false,
                            isActive = isActive,
                            onProgress = onProgress,
                            onCallCreated = onCallCreated
                        )
                    }

                    resp.code == 206 -> {
                        handlePartialContent(
                            resp = resp,
                            destFile = destFile,
                            existing = existing,
                            client = client,
                            url = url,
                            allowResume = allowResume,
                            resumeValidator = resumeValidator,
                            isActive = isActive,
                            call = call,
                            onProgress = onProgress,
                            onCallCreated = onCallCreated
                        )
                    }

                    resp.isSuccessful -> {
                        writeResumeValidator(destFile, url, responseValidator(resp))
                        writeBodyToFile(
                            resp = resp,
                            destFile = destFile,
                            append = false,
                            bytesAlreadyOnDisk = 0L,
                            totalBytes = resp.body.contentLength().takeIf { it > 0L },
                            isActive = isActive,
                            call = call,
                            onProgress = onProgress
                        )
                        deleteResumeMetadata(destFile)
                    }

                    else -> throw IOException("HTTP ${resp.code}")
                }
            }
        } finally {
            if (!isActive()) {
                call.cancel()
            }
        }
    }

    private fun handlePartialContent(
        resp: Response,
        destFile: File,
        existing: Long,
        client: OkHttpClient,
        url: String,
        allowResume: Boolean,
        resumeValidator: String?,
        isActive: () -> Boolean,
        call: Call,
        onProgress: (percent: Int) -> Unit,
        onCallCreated: (Call) -> Unit
    ) {
        val header = resp.header("Content-Range")
            ?: throw IOException("HTTP 206 missing Content-Range")
        val range = parseContentRange(header)
            ?: throw IOException("Invalid Content-Range: $header")

        val total = range.total
        val receivedValidator = responseValidator(resp)
        if (resumeValidator != null &&
            receivedValidator != null &&
            receivedValidator != resumeValidator
        ) {
            if (!allowResume) {
                throw IOException("Response validator changed during resume")
            }
            deletePartial(destFile)
            downloadInternal(
                client = client,
                url = url,
                destFile = destFile,
                allowResume = false,
                isActive = isActive,
                onProgress = onProgress,
                onCallCreated = onCallCreated
            )
            return
        }
        val declaredLength = if (range.end == Long.MAX_VALUE) {
            throw IOException("Invalid Content-Range: $header")
        } else {
            range.end - range.start + 1L
        }
        if (total != null && (total <= 0L || range.end >= total)) {
            throw IOException("Invalid Content-Range: $header")
        }
        val bodyLength = resp.body.contentLength()
        if (bodyLength >= 0L && bodyLength != declaredLength) {
            throw IOException("Content-Range length does not match response body")
        }
        if (total != null && existing > total) {
            deletePartial(destFile)
            downloadInternal(
                client = client,
                url = url,
                destFile = destFile,
                allowResume = false,
                isActive = isActive,
                onProgress = onProgress,
                onCallCreated = onCallCreated
            )
            return
        }

        if (range.start != existing) {
            if (!allowResume) {
                throw IOException("Content-Range start does not match requested offset")
            }
            deletePartial(destFile)
            downloadInternal(
                client = client,
                url = url,
                destFile = destFile,
                allowResume = false,
                isActive = isActive,
                onProgress = onProgress,
                onCallCreated = onCallCreated
            )
            return
        }

        val resolvedTotal = total ?: existing + declaredLength
        writeResumeValidator(
            destFile,
            url,
            receivedValidator ?: resumeValidator
        )

        writeBodyToFile(
            resp = resp,
            destFile = destFile,
            append = true,
            bytesAlreadyOnDisk = existing,
            totalBytes = resolvedTotal,
            isActive = isActive,
            call = call,
            onProgress = onProgress
        )
        deleteResumeMetadata(destFile)
    }

    private fun writeBodyToFile(
        resp: Response,
        destFile: File,
        append: Boolean,
        bytesAlreadyOnDisk: Long,
        totalBytes: Long?,
        isActive: () -> Boolean,
        call: Call,
        onProgress: (percent: Int) -> Unit
    ) {
        destFile.parentFile?.mkdirs()
        if (!append && destFile.exists()) {
            // Truncate explicitly before streaming so a failed open cannot leave stale bytes.
            FileOutputStream(destFile, false).close()
        }

        if (totalBytes != null && totalBytes > 0L && bytesAlreadyOnDisk > 0L) {
            val initial = ((bytesAlreadyOnDisk * 100) / totalBytes).toInt().coerceIn(0, 100)
            onProgress(initial)
        }

        FileOutputStream(destFile, append).use { out ->
            var readSum = bytesAlreadyOnDisk
            val buf = ByteArray(128 * 1024)
            resp.body.byteStream().use { ins ->
                while (true) {
                    ensureActive(isActive, call)
                    val n = ins.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    readSum += n
                    if (totalBytes != null && totalBytes > 0L) {
                        val progress = ((readSum * 100) / totalBytes).toInt().coerceIn(0, 100)
                        onProgress(progress)
                    }
                }
            }
            if (totalBytes != null && totalBytes > 0L && readSum != totalBytes) {
                throw IncompleteDownloadException(expectedBytes = totalBytes, actualBytes = readSum)
            }
            if (totalBytes != null && totalBytes > 0L && destFile.length() != totalBytes) {
                throw IncompleteDownloadException(
                    expectedBytes = totalBytes,
                    actualBytes = destFile.length()
                )
            }
        }
    }

    private fun ensureActive(isActive: () -> Boolean, call: Call) {
        if (!isActive()) {
            call.cancel()
            throw CancellationException("Download cancelled")
        }
    }

    private fun responseValidator(resp: Response): String? = resp.header("ETag")?.takeUnless { it.startsWith("W/", ignoreCase = true) }
        ?: resp.header("Last-Modified")

    private fun readResumeValidator(destFile: File, url: String): String? {
        val metadataFile = resumeMetadataFile(destFile).takeIf { it.isFile } ?: return null
        return try {
            val lines = metadataFile.readLines()
            lines.getOrNull(1)?.takeIf { lines.firstOrNull() == url && it.isNotBlank() }
        } catch (t: Throwable) {
            metadataFile.delete()
            Log.w(TAG, "Failed to read resume validator", t)
            null
        }
    }

    private fun writeResumeValidator(destFile: File, url: String, validator: String?) {
        val metadataFile = resumeMetadataFile(destFile)
        if (validator == null) {
            metadataFile.delete()
            return
        }
        try {
            metadataFile.writeText("$url\n$validator")
        } catch (t: Throwable) {
            metadataFile.delete()
            Log.w(TAG, "Failed to persist resume validator", t)
        }
    }

    private fun deletePartial(file: File) {
        deleteQuietly(file)
        deleteResumeMetadata(file)
    }

    private fun deleteResumeMetadata(file: File) {
        deleteQuietly(resumeMetadataFile(file))
    }

    private fun resumeMetadataFile(file: File): File = File(file.path + RESUME_METADATA_SUFFIX)

    private fun deleteQuietly(file: File) {
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to delete partial file: ${file.path}", t)
        }
    }
}
