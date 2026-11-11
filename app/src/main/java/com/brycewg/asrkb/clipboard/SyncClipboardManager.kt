package com.brycewg.asrkb.clipboard

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.brycewg.asrkb.store.Prefs
import java.io.InputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/**
 * 写入 SyncClipboard 的文本数据载荷
 */
@Serializable
private data class UploadClipboardPayload(
    val hasData: Boolean,
    val text: String,
    val type: String,
    val hash: String? = null,
    val dataName: String? = null,
    val size: Long? = null
)

/**
 * 从 SyncClipboard 读取的数据载荷
 */
@Serializable
private data class PullClipboardPayload(
    val text: String? = null,
    val type: String? = null,
    val hash: String? = null,
    val size: Long? = null,
    val hasData: Boolean? = null,
    val dataName: String? = null,
    @SerialName("Clipboard") val legacyClipboard: String? = null,
    @SerialName("Type") val legacyType: String? = null,
    @SerialName("Hash") val legacyHash: String? = null,
    @SerialName("Size") val legacySize: Long? = null,
    @SerialName("File") val legacyFile: String? = null
)

private data class HandledPullPayload(
    val value: String,
    val clipboardApplied: Boolean = true
)

/**
 * SyncClipboard 客户端：
 * - 经 [SystemClipboardPort] 监听剪贴板变动并上传（文本类型）
 * - 按设定周期从服务器拉取文本并经 Port 写入系统剪贴板
 *
 * 注意：服务端认证使用标准 HTTP Basic（`Authorization: Basic <base64(username:password)>`）。
 * 网络与凭证只留在本体；Port 可指向本进程 ClipboardManager 或 IME Bridge。
 */
class SyncClipboardManager(
    private val context: Context,
    private val prefs: Prefs,
    private val scope: CoroutineScope,
    private val listener: Listener? = null,
    private val clipboardStore: ClipboardHistoryStore? = null,
    private val clipboardPort: SystemClipboardPort = DirectSystemClipboardPort(context),
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    interface Listener {
        fun onPulledNewContent(text: String)
        fun onUploadSuccess()
        fun onUploadFailed(reason: String? = null)
        fun onFilePulled(type: EntryType, fileName: String, serverFileName: String)
    }

    private val json by lazy { Json { ignoreUnknownKeys = true } }
    private val fileManager by lazy { ClipboardFileManager(context) }
    private val attachmentPolicy by lazy { ClipboardAttachmentPolicy(prefs) }
    private val attachmentOrigins by lazy { ClipboardAttachmentOriginStore(context) }
    private val attachmentNotifier by lazy { ClipboardAttachmentNotifier(context) }
    private val attachmentWatcher by lazy {
        ClipboardAttachmentWatcher(context, prefs, attachmentPolicy)
    }

    companion object {
        private const val TAG = "SyncClipboardManager"
        private const val BRIDGE_OBSERVER_RENEW_INTERVAL_MS = 15_000L
        private const val ATTACHMENT_DOWNLOAD_MAX_ATTEMPTS = 3
        private const val ATTACHMENT_DOWNLOAD_RETRY_DELAY_MS = 500L
        private const val ATTACHMENT_RECOVERY_DELAY_MS = 30_000L

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private var pullJob: Job? = null
    private var observerRenewJob: Job? = null
    private var attachmentWatchJob: Job? = null
    private var attachmentRecoveryJob: Job? = null
    private var observing = false

    // 最近一次已应用的远端 Profile.hash。必须与 lastPulledWasText 一起判断，
    // 否则文件 → 同一段文本时会跳过历史里的文件条目清理。
    @Volatile private var lastPulledServerHash: String? = null

    @Volatile private var lastPulledWasText: Boolean = false

    /** 凭证/服务器配置世代；变化时立即作废旧接收路径。 */
    @Volatile private var credentialsEpoch: Long = 0L

    /** 息屏/后台保持：暂停观察与自动写入，保留 realtime 连接语义。 */
    @Volatile private var clipboardEffectsPaused: Boolean = false

    @Volatile private var pollingEnabled: Boolean = false

    val clipboardActor: SystemClipboardActor get() = clipboardPort.actor

    fun start(pollingEnabled: Boolean = prefs.syncClipboardAutoPullEnabled) {
        if (!prefs.syncClipboardEnabled) return
        this.pollingEnabled = pollingEnabled
        clipboardEffectsPaused = false
        ensureObserver()
        ensureObserverRenewLoop()
        ensurePullLoop()
        ensureAttachmentWatch()
    }

    fun stop() {
        credentialsEpoch++
        clipboardEffectsPaused = false
        if (observing) {
            try {
                clipboardPort.stopObserving()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to stop clipboard observer", e)
            }
        }
        observing = false
        stopPullLoop()
        stopAttachmentWatch()
        stopAttachmentRecovery()
        observerRenewJob?.cancel()
        observerRenewJob = null
        lastPulledServerHash = null
        lastPulledWasText = false
        pollingEnabled = false
    }

    /** 暂停剪贴板副作用，但保留亮屏后应恢复的 polling 选择。 */
    fun pauseClipboardSideEffects() {
        clipboardEffectsPaused = true
        if (observing) {
            try {
                clipboardPort.stopObserving()
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to pause clipboard observer", e)
            }
        }
        observing = false
        stopPullLoop()
        stopAttachmentWatch()
        stopAttachmentRecovery()
        observerRenewJob?.cancel()
        observerRenewJob = null
    }

    /** 恢复剪贴板副作用（亮屏后）。 */
    fun resumeClipboardSideEffects() {
        if (!prefs.syncClipboardEnabled) return
        clipboardEffectsPaused = false
        ensureObserver()
        ensureObserverRenewLoop()
        ensurePullLoop()
        ensureAttachmentWatch()
    }

    /** 关闭同步或变更服务器/凭证后调用，立即作废旧响应与接收循环。 */
    internal fun invalidateReceivePath() {
        credentialsEpoch++
        pollingEnabled = false
        stopPullLoop()
        stopAttachmentWatch()
        stopAttachmentRecovery()
    }

    internal fun setPollingEnabled(enabled: Boolean) {
        if (pollingEnabled && !enabled) {
            // OkHttp execute() is blocking; invalidate a response that may outlive Job.cancel().
            credentialsEpoch++
        }
        pollingEnabled = enabled
        ensurePullLoop()
    }

    private fun ensureObserver(force: Boolean = false) {
        if (observing && !force) return
        try {
            clipboardPort.startObserving {
                if (!prefs.syncClipboardEnabled || clipboardEffectsPaused) return@startObserving
                scope.launch(ioDispatcher) {
                    try {
                        uploadCurrentClipboardText()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to upload clipboard text on change", e)
                    }
                }
            }
            observing = true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start clipboard observer", e)
        }
    }

    private fun ensureObserverRenewLoop() {
        if (clipboardPort.actor != SystemClipboardActor.BRIDGE || observerRenewJob?.isActive == true) {
            return
        }
        observerRenewJob = scope.launch(ioDispatcher) {
            while (isActive && prefs.syncClipboardEnabled) {
                delay(BRIDGE_OBSERVER_RENEW_INTERVAL_MS)
                if (!isActive || !prefs.syncClipboardEnabled) break
                ensureObserver(force = true)
            }
        }
    }

    private fun stopPullLoop() {
        pullJob?.cancel()
        pullJob = null
    }

    private fun stopAttachmentWatch() {
        attachmentWatchJob?.cancel()
        attachmentWatchJob = null
    }

    private fun stopAttachmentRecovery() {
        attachmentRecoveryJob?.cancel()
        attachmentRecoveryJob = null
    }

    private fun scheduleAttachmentRecovery(requestEpoch: Long) {
        if (attachmentRecoveryJob?.isActive == true) return
        attachmentRecoveryJob = scope.launch(ioDispatcher) {
            delay(ATTACHMENT_RECOVERY_DELAY_MS)
            pullNow(
                updateClipboard = true,
                requestEpoch = requestEpoch,
                attachmentDownloadAttempts = 1
            )
        }
    }

    private fun ensureAttachmentWatch() {
        if (attachmentWatchJob?.isActive == true ||
            clipboardEffectsPaused ||
            !prefs.syncClipboardEnabled ||
            !attachmentPolicy.hasEnabledType() ||
            prefs.syncClipboardWatchTreeUri.isBlank()
        ) {
            return
        }
        attachmentWatchJob = scope.launch(ioDispatcher) {
            attachmentWatcher.watchAndUpload(::uploadAttachment)
        }
    }

    private fun ensurePullLoop() {
        stopPullLoop()
        if (clipboardEffectsPaused) return
        if (!pollingEnabled || !prefs.syncClipboardEnabled) return
        val intervalSec = prefs.syncClipboardPullIntervalSec.coerceIn(1, 600)
        val epochAtStart = credentialsEpoch
        pullJob = scope.launch(ioDispatcher) {
            while (
                isActive &&
                prefs.syncClipboardEnabled &&
                !clipboardEffectsPaused &&
                pollingEnabled &&
                credentialsEpoch == epochAtStart
            ) {
                try {
                    pullNow(updateClipboard = true)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to pull clipboard in loop", e)
                }
                delay(intervalSec * 1000L)
            }
        }
    }

    private fun buildUrl(): String? {
        val raw = prefs.syncClipboardServerBase.trim()
        if (raw.isBlank()) return null
        val base = raw.trimEnd('/')
        val lower = base.lowercase()
        return if (lower.endsWith(".json")) base else "$base/SyncClipboard.json"
    }

    private fun sha256Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return sha256Hex(md.digest(s.toByteArray(Charsets.UTF_8)))
    }

    private fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return sha256Hex(digest.digest())
    }

    private fun sha256Hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun authHeaderB64(): String? {
        val u = prefs.syncClipboardUsername
        val p = prefs.syncClipboardPassword
        if (u.isBlank() || p.isBlank()) return null
        val token = "$u:$p".toByteArray(Charsets.UTF_8)
        val b64 = Base64.encodeToString(token, Base64.NO_WRAP)
        return "Basic $b64"
    }

    private fun readClipboardText(): String? = clipboardPort.readText()?.text?.takeIf { it.isNotEmpty() }

    private fun writeClipboardText(text: String): Boolean = try {
        clipboardPort.writeText(text)
    } catch (e: Throwable) {
        Log.e(TAG, "Failed to write clipboard text via port", e)
        false
    }

    private fun uploadCurrentClipboardText() {
        val url = buildUrl() ?: return
        val authB64 = authHeaderB64() ?: return
        val read = clipboardPort.readText() ?: return
        if (read.isSensitive) {
            Log.d(TAG, "Skip upload: clipboard marked sensitive, chars=${read.text.length}")
            return
        }
        val text = read.text
        if (text.isEmpty()) return
        // 若与最近一次成功上传（或最近一次拉取写入）相同，则跳过上传，避免重复
        try {
            val newHash = sha256Hex(text)
            val last = try {
                prefs.syncClipboardLastUploadedHash
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to read last uploaded hash", e)
                ""
            }
            if (newHash == last) return
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to compute hash for clipboard text", e)
            // 继续尝试上传
        }
        // 仅使用标准 Basic Base64 认证
        uploadText(url, authB64, text)
    }

    private fun uploadText(url: String, auth: String, text: String): Boolean = try {
        val payload = UploadClipboardPayload(hasData = false, text = text, type = "Text")
        val bodyJson = json.encodeToString(payload)
        val req = Request.Builder()
            .url(url)
            .header("Authorization", auth)
            .put(bodyJson.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) {
                // 记录最近一次成功上传内容的哈希，便于后续对比
                try {
                    prefs.syncClipboardLastUploadedHash = sha256Hex(text)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to save uploaded hash", e)
                }
                try {
                    listener?.onUploadSuccess()
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to notify upload success listener", e)
                }
                true
            } else {
                Log.w(TAG, "Upload failed with status: ${resp.code}")
                try {
                    listener?.onUploadFailed("HTTP ${resp.code}")
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to notify upload failed listener", e)
                }
                false
            }
        }
    } catch (e: Throwable) {
        Log.e(TAG, "Failed to upload clipboard text", e)
        try {
            listener?.onUploadFailed(e.message)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to notify upload failed listener (exception)", t)
        }
        false
    }

    private fun uploadAttachment(attachment: LocalClipboardAttachment): Boolean {
        if (!prefs.syncClipboardEnabled || !attachmentPolicy.allows(attachment.kind, attachment.sizeBytes)) return false
        val url = buildUrl() ?: return false
        val auth = authHeaderB64() ?: return false
        val dataName = newAttachmentDataName(attachment.displayName)
        val body = UriRequestBody(
            context = context,
            uri = attachment.uri,
            mimeType = attachment.mimeType,
            sizeBytes = attachment.sizeBytes,
            onProgress = { copied ->
                attachmentNotifier.showUploadProgress(attachment.displayName, copied, attachment.sizeBytes)
            }
        )
        val fileUrl = buildFileUrl(dataName) ?: return false
        val fileUploaded = try {
            httpClient.newCall(
                Request.Builder()
                    .url(fileUrl)
                    .header("Authorization", auth)
                    .put(body)
                    .build()
            ).execute().use { it.isSuccessful }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to upload clipboard attachment data", t)
            false
        }
        if (!fileUploaded) {
            attachmentNotifier.showUploadFailed(attachment.displayName)
            return false
        }
        if (!prefs.syncClipboardEnabled || !attachmentPolicy.allows(attachment.kind, attachment.sizeBytes)) {
            attachmentNotifier.showUploadFailed(attachment.displayName)
            return false
        }
        val hash = attachmentProfileHash(dataName, attachment, body.contentDigest)
        if (hash == null) {
            attachmentNotifier.showUploadFailed(attachment.displayName)
            return false
        }
        val payload = UploadClipboardPayload(
            hasData = true,
            text = attachment.displayName,
            type = attachment.kind.remoteType,
            hash = hash,
            dataName = dataName,
            size = attachment.sizeBytes
        )
        // SignalR may deliver the Profile before this PUT returns, so record its origin first.
        attachmentOrigins.record(hash)
        val profilePublished = try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", auth)
                .put(json.encodeToString(payload).toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Clipboard attachment profile upload failed: ${response.code}")
                }
                response.isSuccessful
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to upload clipboard attachment profile", t)
            false
        }
        if (profilePublished) {
            attachmentNotifier.showUploaded(attachment.displayName)
        } else {
            attachmentOrigins.clear(hash)
            attachmentNotifier.showUploadFailed(attachment.displayName)
        }
        return profilePublished
    }

    /**
     * Profile 哈希依赖文件内容摘要；优先复用上传流顺带算出的摘要，避免把附件整读第二遍。
     * 摘要缺失时（body 未被写出）回退到独立读取。
     */
    private fun attachmentProfileHash(
        dataName: String,
        attachment: LocalClipboardAttachment,
        uploadedDigest: ByteArray?
    ): String? {
        val contentHash = uploadedDigest?.let(::sha256Hex) ?: try {
            context.contentResolver.openInputStream(attachment.uri)?.use(::sha256Hex)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to hash clipboard attachment", t)
            null
        }
        return contentHash?.let { syncClipboardAttachmentHash(dataName, it) }
    }

    /** 上传由系统分享入口授予的单个附件。 */
    fun uploadSharedFile(uri: Uri): Boolean {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
        return ClipboardAttachmentTransferGate.run {
            if (!prefs.syncClipboardEnabled) return@run false
            resolveSharedAttachment(uri)?.let(::uploadAttachment) ?: false
        }
    }

    private fun resolveSharedAttachment(uri: Uri): LocalClipboardAttachment? {
        var displayName: String? = null
        var sizeBytes: Long? = null
        try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read shared clipboard attachment metadata", t)
        }
        val size = sizeBytes?.takeIf { it >= 0L } ?: resolveSharedAttachmentSize(uri) ?: return null
        val name = displayName.orEmpty()
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .ifBlank { "attachment" }
        val mimeType = try {
            context.contentResolver.getType(uri).orEmpty()
                .ifBlank { "application/octet-stream" }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to resolve shared clipboard attachment type", t)
            return null
        }
        val kind = if (mimeType.startsWith("image/", ignoreCase = true)) {
            ClipboardAttachmentKind.IMAGE
        } else {
            ClipboardAttachmentKind.FILE
        }
        return LocalClipboardAttachment(
            uri = uri,
            displayName = name,
            mimeType = mimeType,
            sizeBytes = size,
            kind = kind,
            signature = uri.toString(),
            lastModifiedMillis = 0L
        )
    }

    private fun resolveSharedAttachmentSize(uri: Uri): Long? {
        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length >= 0L) return descriptor.length
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read shared clipboard attachment descriptor", t)
        }
        val maxBytes = prefs.syncClipboardAttachmentMaxSizeMb * 1024L * 1024L
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use {
                val buffer = ByteArray(8192)
                var total = 0L
                while (total <= maxBytes) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    total += read
                }
                total
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to measure shared clipboard attachment", t)
            null
        }
    }

    private fun newAttachmentDataName(displayName: String): String {
        val extension = displayName.substringAfterLast('.', "")
            .takeWhile { it.isLetterOrDigit() }
            .take(16)
        return UUID.randomUUID().toString() + extension.takeIf { it.isNotBlank() }?.let { ".$it" }.orEmpty()
    }

    /**
     * 一次性上传当前系统粘贴板文本（不进行"与上次一致"跳过判断）。
     * 返回是否成功。
     */
    fun uploadOnce(): Boolean {
        val url = buildUrl() ?: return false
        val authB64 = authHeaderB64() ?: return false
        val read = clipboardPort.readText() ?: return false
        if (read.isSensitive) {
            Log.d(TAG, "uploadOnce skipped: sensitive clipboard, chars=${read.text.length}")
            return false
        }
        val text = read.text
        if (text.isEmpty()) return false
        return try {
            uploadText(url, authB64, text)
        } catch (e: Throwable) {
            Log.e(TAG, "uploadOnce failed", e)
            false
        }
    }

    /**
     * 执行带认证的请求（HTTP Basic）。
     */
    private fun <T> executeRequestWithAuth(
        requestBuilder: (auth: String) -> Request,
        responseHandler: (okhttp3.Response) -> T?
    ): T? {
        val authB64 = authHeaderB64() ?: return null
        return try {
            val req = requestBuilder(authB64)
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    return responseHandler(resp)
                }
                Log.w(TAG, "Auth failed with status: ${resp.code}")
                null
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Auth request failed", e)
            null
        }
    }

    fun pullNow(
        updateClipboard: Boolean,
        requestEpoch: Long = credentialsEpoch,
        attachmentDownloadAttempts: Int = ATTACHMENT_DOWNLOAD_MAX_ATTEMPTS
    ): Pair<Boolean, String?> {
        if (!canApplyPullResponse(updateClipboard, requestEpoch)) return false to null
        val url = buildUrl() ?: return false to null
        if (!canApplyPullResponse(updateClipboard, requestEpoch)) return false to null

        val result = try {
            executeRequestWithAuth(
                requestBuilder = { auth ->
                    Request.Builder()
                        .url(url)
                        .header("Authorization", auth)
                        .get()
                        .build()
                },
                responseHandler = { resp ->
                    val body = resp.body.string().takeIf { it.isNotEmpty() }
                    if (body == null) {
                        Log.w(TAG, "Pull response body is empty")
                        return@executeRequestWithAuth null
                    }

                    val payload = decodeRemoteProfile(body) ?: return@executeRequestWithAuth null
                    applyRemoteProfile(
                        payload,
                        updateClipboard,
                        requestEpoch,
                        attachmentDownloadAttempts
                    )
                }
            )
        } catch (e: Throwable) {
            Log.e(TAG, "pullNow failed", e)
            null
        }

        return if (result != null) {
            result.clipboardApplied to result.value
        } else {
            false to null
        }
    }

    /** Applies a realtime ProfileDto through the same pipeline used by HTTP pulls. */
    internal fun captureRemoteProfileEpoch(): Long = credentialsEpoch

    internal fun applyRemoteProfileJson(
        profileJson: String,
        requestEpoch: Long = credentialsEpoch,
        attachmentDownloadAttempts: Int = ATTACHMENT_DOWNLOAD_MAX_ATTEMPTS
    ): Boolean {
        if (!canApplyPullResponse(updateClipboard = true, requestEpoch)) return false
        val payload = decodeRemoteProfile(profileJson) ?: return false
        return applyRemoteProfile(
            payload,
            updateClipboard = true,
            requestEpoch = requestEpoch,
            attachmentDownloadAttempts = attachmentDownloadAttempts
        )
            ?.clipboardApplied == true
    }

    private fun decodeRemoteProfile(profileJson: String): PullClipboardPayload? = try {
        json.decodeFromString<PullClipboardPayload>(profileJson)
    } catch (e: Throwable) {
        Log.w(TAG, "Failed to parse remote clipboard profile", e)
        null
    }

    private fun applyRemoteProfile(
        payload: PullClipboardPayload,
        updateClipboard: Boolean,
        requestEpoch: Long,
        attachmentDownloadAttempts: Int
    ): HandledPullPayload? {
        if (!canApplyPullResponse(updateClipboard, requestEpoch)) return null
        val payloadType = resolvePayloadType(payload)
        return when (payloadType.lowercase()) {
            "text" -> {
                val remoteHash = resolvePayloadHash(payload)
                if (lastPulledWasText && sameRemoteHash(remoteHash, lastPulledServerHash)) {
                    return HandledPullPayload(resolvePayloadText(payload).orEmpty())
                }
                val textDataName = if (payload.hasData == true) {
                    payload.dataName?.takeIf { it.isNotEmpty() }
                        ?: payload.legacyFile?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                val text = if (!textDataName.isNullOrBlank()) {
                    downloadTextData(textDataName)
                } else {
                    resolvePayloadText(payload)
                }
                val nonBlankText = text?.takeIf { it.isNotBlank() }
                if (nonBlankText == null) {
                    Log.w(TAG, "Clipboard text is blank")
                    return null
                }
                if (!canApplyPullResponse(updateClipboard, requestEpoch)) return null
                handleTextPayload(nonBlankText, updateClipboard, requestEpoch, remoteHash)
            }
            "image", "file" -> {
                val fileName = resolvePayloadFileName(payload)?.takeIf { it.isNotBlank() }
                if (fileName == null) {
                    Log.w(TAG, "File name is blank for type: $payloadType")
                    return null
                }
                val normalizedType = if (payloadType.equals("image", ignoreCase = true)) {
                    "Image"
                } else {
                    "File"
                }
                handleFilePayload(
                    normalizedType,
                    fileName,
                    resolvePayloadHash(payload),
                    payload.size ?: payload.legacySize,
                    updateClipboard,
                    requestEpoch,
                    attachmentDownloadAttempts
                )
            }
            else -> {
                Log.w(TAG, "Unsupported payload type: $payloadType")
                null
            }
        }
    }

    /**
     * 统一解析 payload 类型，优先新字段，兼容旧字段。
     */
    private fun resolvePayloadType(payload: PullClipboardPayload): String {
        val explicitType = payload.type?.trim().takeUnless { it.isNullOrBlank() }
            ?: payload.legacyType?.trim().takeUnless { it.isNullOrBlank() }
        if (explicitType != null) return explicitType
        if (payload.hasData == true &&
            (!payload.dataName.isNullOrBlank() || !payload.legacyFile.isNullOrBlank())
        ) {
            return "File"
        }
        return "Text"
    }

    /**
     * 统一解析 payload 文本内容，优先新字段，兼容旧字段。
     */
    private fun resolvePayloadText(payload: PullClipboardPayload): String? = payload.text?.takeIf { it.isNotEmpty() }
        ?: payload.legacyClipboard?.takeIf { it.isNotEmpty() }

    private fun resolvePayloadHash(payload: PullClipboardPayload): String? = payload.hash?.takeIf { it.isNotBlank() } ?: payload.legacyHash?.takeIf { it.isNotBlank() }

    private fun sameRemoteHash(left: String?, right: String?): Boolean = !left.isNullOrBlank() && !right.isNullOrBlank() && left.equals(right, ignoreCase = true)

    /**
     * 统一解析 payload 文件名，优先新字段，兼容旧字段。
     */
    private fun resolvePayloadFileName(payload: PullClipboardPayload): String? = payload.dataName?.takeIf { it.isNotEmpty() }
        ?: payload.legacyFile?.takeIf { it.isNotEmpty() }
        ?: payload.text?.takeIf { payload.hasData == true && it.isNotEmpty() }
        ?: payload.legacyClipboard?.takeIf { payload.hasData == true && it.isNotEmpty() }

    /**
     * 拉取文本内容：当 `Text + hasData=true` 时，正文存放在 `/file/{dataName}`。
     */
    private fun downloadTextData(dataName: String): String? {
        val fileUrl = buildFileUrl(dataName) ?: run {
            Log.w(TAG, "Failed to build text data url for: $dataName")
            return null
        }
        return executeRequestWithAuth(
            requestBuilder = { auth ->
                Request.Builder()
                    .url(fileUrl)
                    .header("Authorization", auth)
                    .get()
                    .build()
            },
            responseHandler = { resp ->
                val text = resp.body.string()
                if (text.isEmpty()) {
                    Log.w(TAG, "Downloaded text data is empty: $dataName")
                    return@executeRequestWithAuth null
                }
                text
            }
        )
    }

    /**
     * 处理文本类型的 payload
     */
    private fun handleTextPayload(
        text: String,
        updateClipboard: Boolean,
        requestEpoch: Long,
        remoteHash: String?
    ): HandledPullPayload {
        if (!canApplyPullResponse(updateClipboard, requestEpoch)) {
            return HandledPullPayload(text, clipboardApplied = false)
        }

        try {
            clipboardStore?.clearFileEntries()
            if (prefs.syncClipboardLastFileName.isNotEmpty()) {
                prefs.syncClipboardLastFileName = ""
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to clear file entries on text payload", t)
            return HandledPullPayload(text, clipboardApplied = false)
        }

        if (updateClipboard) {
            val cur = readClipboardText()
            if (text.isNotEmpty() && text != cur) {
                if (!canApplyPullResponse(updateClipboard, requestEpoch)) {
                    return HandledPullPayload(text, clipboardApplied = false)
                }
                val written = writeClipboardText(text)
                if (!written) {
                    Log.w(TAG, "Clipboard port refused write, chars=${text.length} actor=${clipboardPort.actor}")
                    return HandledPullPayload(text, clipboardApplied = false)
                }
                // 将此次拉取的内容也记录到"最近一次上传哈希"，避免后续补上传（减少不必要的上传）
                try {
                    prefs.syncClipboardLastUploadedHash = sha256Hex(text)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to save pulled hash", e)
                }
                if (written) {
                    try {
                        listener?.onPulledNewContent(text)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed to notify pulled content listener", e)
                    }
                }
            }
        }
        lastPulledServerHash = remoteHash
        lastPulledWasText = true
        return HandledPullPayload(text)
    }

    private fun canApplyPullResponse(updateClipboard: Boolean, requestEpoch: Long): Boolean = credentialsEpoch == requestEpoch &&
        (!updateClipboard || (prefs.syncClipboardEnabled && !clipboardEffectsPaused))

    /** 处理文件类型的 payload，并在类型和大小允许时自动下载。 */
    private fun handleFilePayload(
        type: String,
        fileName: String,
        serverHash: String?,
        serverSize: Long?,
        updateClipboard: Boolean,
        requestEpoch: Long,
        attachmentDownloadAttempts: Int
    ): HandledPullPayload {
        if (!canApplyPullResponse(updateClipboard, requestEpoch)) {
            return HandledPullPayload(fileName, clipboardApplied = false)
        }
        lastPulledWasText = false
        val attachmentKind = if (type.equals("image", ignoreCase = true)) {
            ClipboardAttachmentKind.IMAGE
        } else {
            ClipboardAttachmentKind.FILE
        }
        if (!attachmentPolicy.allows(attachmentKind, serverSize)) {
            Log.d(TAG, "Clipboard attachment skipped by policy: type=$type size=$serverSize")
            return HandledPullPayload(fileName)
        }
        return ClipboardAttachmentTransferGate.run {
            if (attachmentOrigins.isLocal(serverHash)) {
                Log.d(TAG, "Skip locally published clipboard attachment: $fileName")
                return@run HandledPullPayload(fileName)
            }
            try {
                // 若文件名与最近一次处理的文件相同，则视为内容未更新，避免重复触发预览
                val prevName = try {
                    prefs.syncClipboardLastFileName
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to read last file name", e)
                    ""
                }
                val previousEntry = clipboardStore?.getLatestFileEntry()
                val sameRemoteFile = fileName.isNotEmpty() &&
                    fileName == prevName &&
                    (
                        serverHash.isNullOrBlank() ||
                            previousEntry?.serverHash.equals(serverHash, ignoreCase = true)
                        ) &&
                    previousEntry?.downloadStatus == DownloadStatus.COMPLETED &&
                    fileManager.fileExists(fileName, serverSize)
                if (sameRemoteFile) {
                    Log.d(TAG, "File payload unchanged, skip preview: $fileName")
                    return@run HandledPullPayload(fileName)
                }

                val entryType = when (type.lowercase()) {
                    "image" -> EntryType.IMAGE
                    "file" -> EntryType.FILE
                    else -> EntryType.FILE
                }

                // 检查文件是否已下载；同名但 hash 更新时先删旧文件。
                val existingPath = fileManager.getLocalPath(fileName)
                if (existingPath != null &&
                    !serverHash.isNullOrBlank() &&
                    !previousEntry?.serverHash.equals(serverHash, ignoreCase = true)
                ) {
                    fileManager.deleteFile(fileName)
                }
                val hadLocalFile = fileManager.fileExists(fileName, serverSize)
                val (downloaded, localPath) = if (attachmentDownloadAttempts > 0) {
                    downloadAttachmentWithRetry(
                        fileName,
                        serverSize,
                        serverHash,
                        attachmentKind,
                        updateClipboard,
                        requestEpoch,
                        attachmentDownloadAttempts
                    )
                } else {
                    false to null
                }
                if (!canApplyPullResponse(updateClipboard, requestEpoch) ||
                    !attachmentPolicy.allows(attachmentKind, serverSize)
                ) {
                    if (downloaded && !hadLocalFile) fileManager.deleteFile(fileName)
                    attachmentNotifier.clearDownloadProgress()
                    return@run HandledPullPayload(fileName, clipboardApplied = false)
                }
                val downloadStatus = if (downloaded && localPath != null) {
                    if (!hadLocalFile) attachmentNotifier.showDownloaded(fileName)
                    DownloadStatus.COMPLETED
                } else {
                    if (attachmentDownloadAttempts > 0) attachmentNotifier.showDownloadFailed(fileName)
                    if (attachmentDownloadAttempts > 1 &&
                        prefs.syncClipboardReceiveMode == ClipboardSyncReceiveMode.REALTIME
                    ) {
                        scheduleAttachmentRecovery(requestEpoch)
                    }
                    DownloadStatus.FAILED
                }

                // 添加到历史记录（仅保留最新一条文件记录）
                clipboardStore?.addFileEntry(
                    type = entryType,
                    fileName = fileName,
                    serverFileName = fileName,
                    fileSize = serverSize,
                    serverHash = serverHash,
                    localFilePath = localPath,
                    downloadStatus = downloadStatus
                )

                // 通知监听器有新文件
                try {
                    listener?.onFilePulled(entryType, fileName, fileName)
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to notify file pulled listener", e)
                }

                // 记录最近一次成功处理的文件名
                try {
                    prefs.syncClipboardLastFileName = fileName
                } catch (e: Throwable) {
                    Log.e(TAG, "Failed to save last file name", e)
                }

                Log.d(TAG, "File payload handled: $fileName (type: $type, status: $downloadStatus)")
                // 附件失败已由本管理器安排一次受控恢复，避免 realtime 立即重复拉取同一份 profile。
                HandledPullPayload(fileName, clipboardApplied = attachmentDownloadAttempts > 0)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to handle file payload: $fileName", e)
                HandledPullPayload(fileName, clipboardApplied = false)
            }
        }
    }

    /**
     * 下载文件
     * @param entryId 条目 ID
     * @param progressCallback 进度回调
     * @return 是否下载成功
     */
    fun downloadFile(entryId: String, progressCallback: ((Long, Long) -> Unit)? = null): Boolean = ClipboardAttachmentTransferGate.run { downloadFileLocked(entryId, progressCallback) }

    private fun downloadFileLocked(entryId: String, progressCallback: ((Long, Long) -> Unit)?): Boolean {
        val store = clipboardStore ?: return false
        val entry = store.getEntryById(entryId) ?: return false
        val serverFileName = entry.serverFileName ?: entry.fileName ?: return false

        // 更新状态为下载中
        if (!store.updateFileEntry(entryId, null, DownloadStatus.DOWNLOADING)) return false

        val (ok, localPath) = downloadFileDirectInternal(
            serverFileName = serverFileName,
            expectedSize = entry.fileSize,
            expectedHash = entry.serverHash,
            progressCallback = progressCallback
        )

        if (ok && localPath != null) {
            if (!store.updateFileEntry(entryId, localPath, DownloadStatus.COMPLETED)) return false
            attachmentNotifier.showDownloaded(serverFileName)
            return true
        }

        if (!store.updateFileEntry(entryId, null, DownloadStatus.FAILED)) return false
        attachmentNotifier.showDownloadFailed(serverFileName)
        return false
    }

    /**
     * 直接按文件名下载文件（不依赖剪贴板历史条目）
     * @param fileName 服务器上的文件名
     * @param progressCallback 进度回调
     * @return Pair<是否成功, 本地路径（成功时非 null）>
     */
    fun downloadFileDirect(
        fileName: String,
        progressCallback: ((Long, Long) -> Unit)? = null
    ): Pair<Boolean, String?> = ClipboardAttachmentTransferGate.run {
        downloadFileDirectLocked(fileName, progressCallback)
    }

    private fun downloadFileDirectLocked(
        fileName: String,
        progressCallback: ((Long, Long) -> Unit)?
    ): Pair<Boolean, String?> {
        if (fileName.isBlank()) return false to null

        // 已存在则直接返回
        if (fileManager.fileExists(fileName)) {
            val localPath = fileManager.getLocalPath(fileName)
            Log.d(TAG, "File already downloaded (direct): $fileName -> $localPath")
            return true to localPath
        }

        return downloadFileDirectInternal(
            serverFileName = fileName,
            expectedSize = null,
            expectedHash = null,
            progressCallback = progressCallback
        )
    }

    /**
     * 文件下载核心实现，供历史条目下载和直接按文件名下载复用
     */
    private fun downloadFileDirectInternal(
        serverFileName: String,
        expectedSize: Long?,
        expectedHash: String?,
        progressCallback: ((Long, Long) -> Unit)?
    ): Pair<Boolean, String?> {
        val fileUrl = buildFileUrl(serverFileName) ?: run {
            Log.w(TAG, "Failed to build file url for: $serverFileName")
            return false to null
        }

        val authB64 = authHeaderB64() ?: run {
            Log.w(TAG, "Missing auth header for file download")
            return false to null
        }

        // 若已存在且大小匹配，直接返回
        if (fileManager.fileExists(serverFileName, expectedSize)) {
            val contentHash = fileManager.openInputStream(serverFileName)?.use(::sha256Hex)
            val actualHash = contentHash?.let { syncClipboardAttachmentHash(serverFileName, it) }
            if (expectedHash.isNullOrBlank() || actualHash.equals(expectedHash, ignoreCase = true)) {
                val localPath = fileManager.getLocalPath(serverFileName)
                Log.d(
                    TAG,
                    "File already exists with expected size: $serverFileName -> $localPath"
                )
                return true to localPath
            }
            fileManager.deleteFile(serverFileName)
        }

        return try {
            val req = Request.Builder()
                .url(fileUrl)
                .header("Authorization", authB64)
                .get()
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Download failed: ${resp.code}")
                    return false to null
                }

                val body = resp.body

                val totalBytes = body.contentLength()
                val maxBytes = prefs.syncClipboardAttachmentMaxSizeMb * 1024L * 1024L
                if (totalBytes > maxBytes) {
                    Log.w(TAG, "Downloaded attachment exceeds configured size limit: $serverFileName")
                    return false to null
                }
                attachmentNotifier.showDownloadProgress(serverFileName, 0L, totalBytes)
                val digest = MessageDigest.getInstance("SHA-256")
                val localPath = fileManager.saveFile(
                    serverFileName,
                    DigestInputStream(body.byteStream(), digest),
                    totalBytes,
                    maxBytes,
                    { copied, total ->
                        attachmentNotifier.showDownloadProgress(serverFileName, copied, total)
                        progressCallback?.invoke(copied, total)
                    }
                )

                if (localPath != null) {
                    val actualHash = syncClipboardAttachmentHash(serverFileName, sha256Hex(digest.digest()))
                    if (!expectedHash.isNullOrBlank() && !actualHash.equals(expectedHash, ignoreCase = true)) {
                        fileManager.deleteFile(serverFileName)
                        attachmentNotifier.clearDownloadProgress()
                        Log.w(TAG, "Downloaded attachment hash mismatch: $serverFileName")
                        return false to null
                    }
                    Log.d(TAG, "File downloaded successfully: $serverFileName -> $localPath")
                    attachmentNotifier.clearDownloadProgress()
                    true to localPath
                } else {
                    attachmentNotifier.clearDownloadProgress()
                    Log.w(TAG, "Failed to save downloaded file: $serverFileName")
                    false to null
                }
            }
        } catch (e: Exception) {
            attachmentNotifier.clearDownloadProgress()
            Log.e(TAG, "Download error: $serverFileName", e)
            false to null
        }
    }

    /**
     * 构建文件下载 URL
     */
    private fun buildFileUrl(fileName: String): String? {
        if (!isSafeAttachmentFileName(fileName)) return null
        val raw = prefs.syncClipboardServerBase.trim()
        if (raw.isBlank()) return null
        val base = raw.trimEnd('/')
        // 文件在服务器的 /file/ 目录下
        val encodedFileName = Uri.encode(fileName)
        return "$base/file/$encodedFileName"
    }

    private fun downloadAttachmentWithRetry(
        serverFileName: String,
        expectedSize: Long?,
        expectedHash: String?,
        kind: ClipboardAttachmentKind,
        updateClipboard: Boolean,
        requestEpoch: Long,
        maxAttempts: Int
    ): Pair<Boolean, String?> {
        repeat(maxAttempts) { attempt ->
            if (!canApplyPullResponse(updateClipboard, requestEpoch) ||
                !attachmentPolicy.allows(kind, expectedSize)
            ) {
                return false to null
            }
            val result = downloadFileDirectInternal(serverFileName, expectedSize, expectedHash, null)
            if (result.first) return result
            if (attempt < maxAttempts - 1) {
                try {
                    Thread.sleep(ATTACHMENT_DOWNLOAD_RETRY_DELAY_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return result
                }
            }
        }
        return false to null
    }

    private fun isSafeAttachmentFileName(fileName: String): Boolean = fileName.isNotBlank() &&
        fileName != "." &&
        fileName != ".." &&
        !fileName.contains('/') &&
        !fileName.contains('\\')

    private class UriRequestBody(
        private val context: Context,
        private val uri: Uri,
        private val mimeType: String,
        private val sizeBytes: Long,
        private val onProgress: (Long) -> Unit
    ) : RequestBody() {
        /** 上传流经过的内容摘要；OkHttp 可能重发 body，因此每次 writeTo 重新计算。 */
        @Volatile
        var contentDigest: ByteArray? = null
            private set

        override fun contentType() = mimeType.toMediaType()

        override fun contentLength(): Long = sizeBytes

        override fun writeTo(sink: BufferedSink) {
            contentDigest = null
            val digest = MessageDigest.getInstance("SHA-256")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Unable to open clipboard attachment")
            input.use {
                val buffer = ByteArray(8192)
                var copied = 0L
                while (true) {
                    val read = it.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    copied += read
                    onProgress(copied)
                }
            }
            contentDigest = digest.digest()
        }
    }

    /**
     * 在启动时调用：若系统剪贴板文本与上次成功上传不一致，则主动上传一次。
     */
    fun proactiveUploadIfChanged() {
        val url = buildUrl() ?: return
        val authB64 = authHeaderB64() ?: return
        val read = clipboardPort.readText() ?: return
        if (read.isSensitive) {
            Log.d(TAG, "proactiveUpload skipped: sensitive clipboard, chars=${read.text.length}")
            return
        }
        val text = read.text
        if (text.isEmpty()) return
        val newHash = try {
            sha256Hex(text)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to compute hash for proactive upload", e)
            return
        }
        val last = try {
            prefs.syncClipboardLastUploadedHash
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to read last uploaded hash", e)
            ""
        }
        if (newHash != last) {
            try {
                uploadText(url, authB64, text)
            } catch (e: Throwable) {
                Log.e(TAG, "proactiveUploadIfChanged failed", e)
            }
        }
    }
}
