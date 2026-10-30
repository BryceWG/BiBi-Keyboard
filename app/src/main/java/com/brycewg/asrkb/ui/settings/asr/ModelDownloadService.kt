package com.brycewg.asrkb.ui.settings.asr

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LocalModelCheck
import com.brycewg.asrkb.asr.LocalModelFileSpec
import com.brycewg.asrkb.asr.LocalModelSpecs
import com.brycewg.asrkb.asr.findGigaAmModelDir
import com.brycewg.asrkb.asr.requireModelFilesCached
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.SettingsActivity
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call

/**
 * 本地模型下载/解压 前台服务：
 * - 采用通知栏通知进度
 * - 支持同时下载不同版本
 * - 解压采用严格校验，临时目录原子替换
 */
class ModelDownloadService : Service() {

    override fun attachBaseContext(newBase: Context?) {
        val wrapped = newBase?.let { LocaleHelper.wrap(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    companion object {
        private const val TAG = "ModelDownloadService"
        private const val CHANNEL_ID = "model_download"
        private const val GROUP_ID = "model_download_group"
        private const val SUMMARY_ID = 1000

        private const val ACTION_START = "com.brycewg.asrkb.action.MODEL_DOWNLOAD_START"
        private const val ACTION_IMPORT = "com.brycewg.asrkb.action.MODEL_IMPORT"
        private const val ACTION_CANCEL = "com.brycewg.asrkb.action.MODEL_DOWNLOAD_CANCEL"

        private const val EXTRA_URL = "url"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_VARIANT = "variant"
        private const val EXTRA_KEY = "key"
        private const val EXTRA_MODEL_TYPE = "modelType" // sensevoice | funasr_nano | qwen3_asr | parakeet | giga_am | x_asr | firered_asr | punctuation

        private fun buildDownloadKey(variant: String, modelType: String): DownloadKey {
            val sourceId = when (modelType) {
                "x_asr" -> "download_x_asr"
                "firered_asr" -> "download_firered_asr"
                "punctuation" -> "download_punctuation"
                "funasr_nano" -> "download_funasr_nano"
                "qwen3_asr" -> "download_qwen3_asr"
                "parakeet" -> "download_parakeet"
                "giga_am" -> "download_giga_am"
                else -> "download_sensevoice"
            }
            return DownloadKey(variant, sourceId)
        }

        fun startDownload(context: Context, url: String, variant: String) {
            val modelType = "sensevoice"
            val key = buildDownloadKey(variant, modelType)
            val i = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_VARIANT, variant)
                putExtra(EXTRA_KEY, key.toSerializedKey())
                putExtra(EXTRA_MODEL_TYPE, modelType)
            }
            context.startService(i)
        }

        fun startDownload(context: Context, url: String, variant: String, modelType: String) {
            val key = buildDownloadKey(variant, modelType)
            val i = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_VARIANT, variant)
                putExtra(EXTRA_KEY, key.toSerializedKey())
                putExtra(EXTRA_MODEL_TYPE, modelType)
            }
            context.startService(i)
        }

        fun startImport(context: Context, uri: android.net.Uri, variant: String) {
            val key = DownloadKey(variant, "import_${uri.lastPathSegment ?: "unknown"}")
            val i = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_IMPORT
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_VARIANT, variant)
                putExtra(EXTRA_KEY, key.toSerializedKey())
            }
            context.startService(i)
        }

        fun startImport(
            context: Context,
            uri: android.net.Uri,
            variant: String,
            modelType: String
        ) {
            val key =
                DownloadKey(variant, "import_${modelType}_${uri.lastPathSegment ?: "unknown"}")
            val i = Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_IMPORT
                putExtra(EXTRA_URI, uri.toString())
                putExtra(EXTRA_VARIANT, variant)
                putExtra(EXTRA_KEY, key.toSerializedKey())
                putExtra(EXTRA_MODEL_TYPE, modelType)
            }
            context.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val tasks = ConcurrentHashMap<DownloadKey, kotlinx.coroutines.Job>()
    private val notificationHandlers = ConcurrentHashMap<DownloadKey, NotificationHandler>()
    private val activeCalls = ConcurrentHashMap<DownloadKey, Call>()

    /** 用户点取消的 key：catch 里无论何种异常都不得再覆盖为「失败」 */
    private val userCancelledKeys = ConcurrentHashMap.newKeySet<DownloadKey>()
    private lateinit var nm: NotificationManager

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NotificationManager::class.java)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val variant = intent.getStringExtra(EXTRA_VARIANT) ?: ""
                val modelType = intent.getStringExtra(EXTRA_MODEL_TYPE) ?: "sensevoice"
                val serializedKey = intent.getStringExtra(EXTRA_KEY)
                    ?: buildDownloadKey(variant, modelType).toSerializedKey()
                val key = DownloadKey.fromSerializedKey(serializedKey)

                if (!tasks.containsKey(key)) {
                    userCancelledKeys.remove(key)
                    if (tasks.isEmpty()) startAsForegroundSummary()

                    val notificationHandler = NotificationHandler(
                        context = this,
                        notificationManager = nm,
                        key = key,
                        variant = variant,
                        modelType = modelType
                    )
                    notificationHandlers[key] = notificationHandler

                    val job = scope.launch {
                        doDownloadTask(key, url, variant, modelType, notificationHandler)
                    }
                    tasks[key] = job
                }
            }
            ACTION_IMPORT -> {
                val uriString = intent.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY
                val uri = android.net.Uri.parse(uriString)
                val variant = intent.getStringExtra(EXTRA_VARIANT) ?: ""
                val modelType = intent.getStringExtra(EXTRA_MODEL_TYPE) ?: "auto"
                val serializedKey =
                    intent.getStringExtra(EXTRA_KEY)
                        ?: DownloadKey(variant, "import").toSerializedKey()
                val key = DownloadKey.fromSerializedKey(serializedKey)

                if (!tasks.containsKey(key)) {
                    if (tasks.isEmpty()) startAsForegroundSummary()

                    val notificationHandler = NotificationHandler(
                        context = this,
                        notificationManager = nm,
                        key = key,
                        variant = variant,
                        modelType = if (modelType == "auto") "auto" else modelType
                    )
                    notificationHandlers[key] = notificationHandler

                    val job = scope.launch {
                        doImportTask(key, uri, variant, modelType, notificationHandler)
                    }
                    tasks[key] = job
                }
            }
            ACTION_CANCEL -> {
                val serializedKey = intent.getStringExtra(EXTRA_KEY) ?: return START_NOT_STICKY
                val key = DownloadKey.fromSerializedKey(serializedKey)
                val cancelledText = getString(R.string.error_model_download_cancelled)

                userCancelledKeys.add(key)
                // 先移除 handler，避免 doDownloadTask catch 再次弹出「失败」通知
                val handler = notificationHandlers.remove(key)
                // 立即掐断 OkHttp，避免卡在 read 导致 finally/stopForeground 迟迟不跑
                try {
                    activeCalls.remove(key)?.cancel()
                } catch (t: Throwable) {
                    Log.w(TAG, "Error cancelling active download call", t)
                }
                tasks.remove(key)?.cancel()
                handler?.notifyFailed(cancelledText)
                // 任务已从 map 移除：立刻收起固定的「模型下载」前台 summary
                stopIfNoTasks("ACTION_CANCEL")
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            scope.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Error cancelling scope in onDestroy", e)
        }
    }

    private fun startAsForegroundSummary() {
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_model_summary_title))
            .setContentText(getString(R.string.notif_model_summary_text))
            .setSmallIcon(R.drawable.cloud_arrow_down)
            .setGroup(GROUP_ID)
            .setOngoing(true)
            .build()
        startForeground(SUMMARY_ID, notif)
    }

    private suspend fun doDownloadTask(
        key: DownloadKey,
        url: String,
        variant: String,
        modelType: String,
        notificationHandler: NotificationHandler
    ) {
        // 仅支持 .zip 下载源；非 .zip 直接报错（提示更新下载链接）
        val cacheFile = File(cacheDir, key.toSafeFileName() + ".zip")
        // 仅在下载未完成（失败/取消）时保留部分 zip，供断点续传
        var keepPartialCache = true
        var downloadCompleted = false
        pruneDownloadCaches("doDownloadTask", cacheFile, url)

        try {
            if (!url.lowercase().substringBefore('#').substringBefore('?').endsWith(".zip")) {
                throw IllegalArgumentException(getString(R.string.error_only_zip_supported))
            }

            // 下载文件
            downloadFile(url, cacheFile, notificationHandler)
            downloadCompleted = true
            // zip 已完整；解压/校验失败时不应再当作可续传部分文件
            keepPartialCache = false

            // 解压归档
            val modelDir = extractArchive(cacheFile, key, variant, modelType, notificationHandler)

            // 验证并安装模型
            verifyAndInstallModel(modelDir, variant, modelType)

            val doneText = when (modelType) {
                "x_asr" -> getString(R.string.x_asr_download_status_done)
                "firered_asr" -> getString(R.string.fr_download_status_done)
                "punctuation" -> getString(R.string.punct_download_status_done)
                "funasr_nano" -> getString(R.string.fn_download_status_done)
                "qwen3_asr" -> getString(R.string.qw_download_status_done)
                "parakeet" -> getString(R.string.pk_download_status_done)
                "giga_am" -> getString(R.string.giga_am_download_status_done)
                else -> getString(R.string.sv_download_status_done)
            }
            notificationHandler.notifySuccess(doneText)
        } catch (t: Throwable) {
            Log.e(TAG, "Download task failed for key=$key, url=$url", t)
            val onlyZipMsg = getString(R.string.error_only_zip_supported)
            val userCancelled = userCancelledKeys.contains(key)
            val failText = when {
                userCancelled || t is CancellationException ->
                    getString(R.string.error_model_download_cancelled)
                t is IncompleteDownloadException ->
                    getString(R.string.error_model_download_incomplete)
                t is ModelIntegrityException ->
                    t.message ?: getString(R.string.error_local_model_integrity_failed, "")
                t.message == onlyZipMsg -> onlyZipMsg
                downloadCompleted ->
                    getString(R.string.error_model_package_verify_failed)
                modelType == "x_asr" -> getString(R.string.x_asr_download_status_failed)
                modelType == "firered_asr" -> getString(R.string.fr_download_status_failed)
                modelType == "punctuation" -> getString(R.string.punct_download_status_failed)
                modelType == "funasr_nano" -> getString(R.string.fn_download_status_failed)
                modelType == "qwen3_asr" -> getString(R.string.qw_download_status_failed)
                modelType == "parakeet" -> getString(R.string.pk_download_status_failed)
                modelType == "giga_am" -> getString(R.string.giga_am_download_status_failed)
                else -> getString(R.string.sv_download_status_failed)
            }
            // 用户取消：ACTION_CANCEL 已展示「已取消下载」，禁止被 OkHttp IOException 等覆盖成失败
            if (!userCancelled) {
                notificationHandler.notifyFailed(failText)
            } else if (keepPartialCache && cacheFile.exists()) {
                ModelDownloadCache.touch(cacheFile)
            }
        } finally {
            activeCalls.remove(key)
            tasks.remove(key)
            notificationHandlers.remove(key)
            userCancelledKeys.remove(key)
            stopIfNoTasks("doDownloadTask.finally")
            if (!keepPartialCache) {
                try {
                    cacheFile.delete()
                } catch (e: Throwable) {
                    Log.w(TAG, "Error deleting cache file: ${cacheFile.path}", e)
                }
            }
        }
    }

    /**
     * 从本地文件导入模型
     * @param specifiedModelType 指定的模型类型，"auto" 表示自动检测
     */
    private suspend fun doImportTask(
        key: DownloadKey,
        uri: android.net.Uri,
        variant: String,
        specifiedModelType: String,
        notificationHandler: NotificationHandler
    ) {
        // 仅支持 .zip 导入：先根据显示名或路径判断并在服务侧再次校验
        val cacheFile = File(cacheDir, key.toSafeFileName() + ".zip")

        try {
            val displayName = getDisplayNameFromUri(uri) ?: uri.lastPathSegment ?: ""
            if (!displayName.lowercase().endsWith(".zip")) {
                throw IllegalArgumentException(getString(R.string.error_only_zip_supported))
            }

            // 从 Uri 复制文件到缓存目录
            copyFileFromUri(uri, cacheFile, notificationHandler)

            // 确定模型类型和变体
            val modelType: String
            val detectedVariant: String

            if (specifiedModelType != "auto") {
                // 使用指定的模型类型和变体
                modelType = specifiedModelType
                detectedVariant = variant
                notificationHandler.updateModelType(modelType)
            } else {
                // 通过压缩包文件名精准识别模型类型与变体
                val typeAndVariant = detectModelTypeAndVariantFromFileName(displayName)
                    ?: throw IllegalStateException(getString(R.string.sv_import_failed, "无法识别模型类型"))
                modelType = typeAndVariant.first
                detectedVariant = typeAndVariant.second

                // 更新通知处理器（类型与变体），以便文案准确：
                notificationHandler.updateModelType(modelType)
                val shouldUpdateVariant = when (modelType) {
                    "sensevoice" -> true
                    "funasr_nano" -> true
                    "qwen3_asr" -> true
                    "parakeet" -> true
                    "giga_am" -> true
                    "x_asr" -> true
                    else -> false
                }
                if (shouldUpdateVariant) notificationHandler.updateVariant(detectedVariant)

                // 同步首选项中的变体，确保后续加载/校验路径一致
                try {
                    val prefs = Prefs(this@ModelDownloadService)
                    when (modelType) {
                        // SenseVoice：二选一，直接同步用户选择
                        "sensevoice" -> prefs.svModelVariant = detectedVariant
                        // FunASR Nano：二选一，直接同步用户选择
                        "funasr_nano" -> prefs.fnModelVariant = detectedVariant
                        // Qwen3-ASR：同步用户选择的模型变体
                        "qwen3_asr" -> prefs.qwModelVariant = detectedVariant
                        // Parakeet：v2/v3 两套模型
                        "parakeet" -> prefs.pkModelVariant = detectedVariant
                        "giga_am" -> prefs.gigaAmModelVariant = detectedVariant
                        // FireRedASR：离线 CTC，int8/full 二选一，直接同步用户选择
                        "firered_asr" -> prefs.frModelVariant = detectedVariant
                        "x_asr" -> prefs.xAsrModelVariant = detectedVariant
                    }
                } catch (e: Throwable) {
                    Log.w(
                        TAG,
                        "Failed to persist detected variant: $detectedVariant for $modelType",
                        e
                    )
                }
            }

            // 解压归档（仅支持 ZIP）
            val installVariant = detectedVariant
            val modelDir =
                extractArchive(cacheFile, key, installVariant, modelType, notificationHandler)

            // 验证并安装模型（使用检测到的变体决定最终安装路径）
            verifyAndInstallModel(modelDir, installVariant, modelType)

            // 构造成功消息
            val modelInfo = getModelInfo(modelType, installVariant)
            val successMessage = when (modelType) {
                "punctuation" -> getString(R.string.punct_import_success, modelInfo)
                "funasr_nano" -> getString(R.string.fn_import_success, modelInfo)
                "qwen3_asr" -> getString(R.string.qw_import_success, modelInfo)
                "parakeet" -> getString(R.string.pk_import_success, modelInfo)
                "giga_am" -> getString(R.string.giga_am_import_success, modelInfo)
                "x_asr" -> getString(R.string.x_asr_import_success, modelInfo)
                else -> getString(R.string.sv_import_success, modelInfo)
            }
            notificationHandler.notifySuccess(successMessage)
        } catch (t: Throwable) {
            Log.e(TAG, "Import task failed for key=$key, uri=$uri", t)
            val errorMessage = t.message ?: "Unknown error"
            val failMessage = when (specifiedModelType) {
                "punctuation" -> getString(R.string.punct_import_failed, errorMessage)
                "funasr_nano" -> getString(R.string.fn_import_failed, errorMessage)
                "qwen3_asr" -> getString(R.string.qw_import_failed, errorMessage)
                "parakeet" -> getString(R.string.pk_import_failed, errorMessage)
                "giga_am" -> getString(R.string.giga_am_import_failed, errorMessage)
                "x_asr" -> getString(R.string.x_asr_import_failed, errorMessage)
                else -> getString(R.string.sv_import_failed, errorMessage)
            }
            notificationHandler.notifyFailed(failMessage)
        } finally {
            tasks.remove(key)
            notificationHandlers.remove(key)
            stopIfNoTasks("doImportTask.finally")
            try {
                cacheFile.delete()
            } catch (e: Throwable) {
                Log.w(TAG, "Error deleting cache file: ${cacheFile.path}", e)
            }
        }
    }

    /**
     * 从 Uri 复制文件到缓存目录
     */
    private suspend fun copyFileFromUri(
        uri: android.net.Uri,
        destFile: File,
        notificationHandler: NotificationHandler
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Copying file from URI: $uri")

        contentResolver.openInputStream(uri)?.use { input ->
            val total = try {
                contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            } catch (e: Exception) {
                0L
            }

            FileOutputStream(destFile).use { output ->
                val buf = ByteArray(128 * 1024)
                var readSum = 0L
                var lastProgress = -1

                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break

                    output.write(buf, 0, n)
                    readSum += n

                    if (total > 0) {
                        val progress = ((readSum * 100) / total).toInt()
                        if (progress != lastProgress) {
                            val cancelIntent = notificationHandler.createCancelIntent()
                            notificationHandler.notifyExtractProgress(progress, cancelIntent)
                            lastProgress = progress
                        }
                    }
                }
            }
        } ?: throw IllegalStateException("无法打开文件")

        Log.d(TAG, "File copied successfully: ${destFile.length()} bytes")
    }

    /**
     * 根据压缩包文件名识别模型类型（不解压内容）
     */
    private fun detectModelTypeFromFileName(name: String): String? {
        val n = name.lowercase()
        return when {
            n.contains("x-asr") || n.contains("x_asr") -> "x_asr"
            n.contains("fire-red-asr2") ||
                n.contains("fire_red_asr2") ||
                n.contains("firered_asr") ||
                n.contains("fireredasr") -> "firered_asr"
            n.contains("funasr-mlt-nano") -> "funasr_nano"
            n.contains("funasr-nano") -> "funasr_nano"
            n.contains("qwen3-asr") -> "qwen3_asr"
            n.contains("parakeet") -> "parakeet"
            n.contains("giga-am") || n.contains("giga_am") || n.contains("gigaam") -> "giga_am"
            n.contains("sense-voice") || n.contains("sensevoice") -> "sensevoice"
            n.contains("punct-ct-transformer") || n.contains("punctuation") -> "punctuation"
            else -> null
        }
    }

    /**
     * 基于压缩包“完整文件名”精准识别模型类型与应用内变体编码
     * 要求：文件名在转换为 .zip 时不改主名，仅改后缀
     */
    private fun detectModelTypeAndVariantFromFileName(name: String): Pair<String, String>? {
        val base = name.substringAfterLast('/')
            .substringBeforeLast('.')
            .lowercase()
        return when (base) {
            // SenseVoice
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17" -> "sensevoice" to "small-full"
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17" ->
                "sensevoice" to
                    "small-int8"

            // FunASR Nano
            "sherpa-onnx-funasr-nano-int8-2025-12-30" -> "funasr_nano" to "nano-int8"
            "sherpa-onnx-funasr-mlt-nano-int8-2026-03-21" -> "funasr_nano" to "mlt-int8"

            // Qwen3-ASR
            "sherpa-onnx-qwen3-asr-0.6b-int8-2026-03-25" -> "qwen3_asr" to "qwen3-0.6b-int8"
            "sherpa-onnx-qwen3-asr-1.7b-int8-2026-08-04" -> "qwen3_asr" to "qwen3-1.7b-int8"

            // Parakeet
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8" -> "parakeet" to "0.6b-v3-int8"
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8" -> "parakeet" to "0.6b-v2-int8"

            // GigaAM v3 Russian Nemo transducer with punctuation
            "sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16" ->
                "giga_am" to "giga-v3"

            "sherpa-onnx-streaming-x-asr-480ms-zh-en",
            "sherpa-onnx-streaming-x_asr-480ms-zh_en" -> "x_asr" to "x-asr-480ms"

            // FireRedASR V2
            "sherpa-onnx-fire-red-asr2-ctc-zh_en-int8-2026-02-25" ->
                "firered_asr" to
                    "ctc-int8"

            // Punctuation（ct-transformer zh+en）
            "sherpa-onnx-punct-ct-transformer-zh-en-vocab272727-2024-04-12-int8" ->
                "punctuation" to
                    "ct-zh-en-int8"

            else -> null
        }
    }

    /**
     * 获取模型信息字符串
     */
    private fun getModelInfo(modelType: String, variant: String): String = when (modelType) {
        "sensevoice" -> {
            val versionName = when (variant) {
                "small-full" -> "Small (fp32)"
                "small-int8" -> "Small (int8)"
                else -> variant
            }
            "SenseVoice $versionName"
        }
        "funasr_nano" -> {
            val versionName = when (variant) {
                "nano-int8" -> "Nano (int8)"
                "mlt-int8" -> "MLT Nano (int8)"
                else -> variant
            }
            "FunASR $versionName"
        }
        "qwen3_asr" -> if (
            com.brycewg.asrkb.asr.normalizeQwen3AsrVariant(variant) == "qwen3-1.7b-int8"
        ) {
            getString(R.string.qw_model_qwen3_17b_int8)
        } else {
            getString(R.string.qw_model_qwen3_06b_int8)
        }
        "parakeet" -> {
            val versionName = when (variant) {
                "0.6b-v2-int8" -> "0.6B V2 Int8"
                else -> "0.6B V3 Int8"
            }
            "Parakeet $versionName"
        }
        "giga_am" -> "GigaAM v3 Russian"
        "firered_asr" -> "FireRedASR CTC (int8)"
        "x_asr" -> "X-ASR 480ms"
        "punctuation" -> {
            // 目前仅一套中英通用标点模型
            "Punctuation zh+en ($variant)"
        }
        else -> "$modelType $variant"
    }

    /**
     * 下载文件到本地缓存（服务器支持 Range 时断点续传）
     */
    private suspend fun downloadFile(
        url: String,
        cacheFile: File,
        notificationHandler: NotificationHandler
    ) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting download from: $url")
        val key = notificationHandler.key

        val cancelIntent = notificationHandler.createCancelIntent()
        notificationHandler.notifyDownloadProgress(0, cancelIntent)

        try {
            ResumableHttpDownloader.download(
                client = ResumableHttpDownloader.defaultClient(),
                url = url,
                destFile = cacheFile,
                isActive = { coroutineContext.isActive },
                onProgress = { progress ->
                    notificationHandler.notifyDownloadProgress(progress, cancelIntent)
                },
                onCallCreated = { call -> activeCalls[key] = call }
            )
        } finally {
            activeCalls.remove(key)
        }

        Log.d(TAG, "Download completed: ${cacheFile.path}")
    }

    /** 清理过期/超量的未完成下载 zip，保留当前任务与活跃任务缓存 */
    private suspend fun pruneDownloadCaches(
        reason: String,
        sourceFile: File,
        sourceUrl: String
    ) = withContext(Dispatchers.IO) {
        try {
            ResumableHttpDownloader.discardIfSourceChanged(sourceFile, sourceUrl)
            val protect = buildSet {
                add(sourceFile.name)
                tasks.keys.forEach { add(it.toSafeFileName() + ".zip") }
                notificationHandlers.keys.forEach { add(it.toSafeFileName() + ".zip") }
            }
            val result = ModelDownloadCache.prune(
                cacheDir = cacheDir,
                protectFileNames = protect
            )
            if (result.deletedCount > 0) {
                Log.i(
                    TAG,
                    "Pruned ${result.deletedCount} stale download cache file(s), freed=${result.freedBytes} ($reason)"
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to prune download caches ($reason)", t)
        }
    }

    /** 无进行中任务时收起固定 summary 前台通知并 stopSelf */
    private fun stopIfNoTasks(reason: String) {
        if (!tasks.isEmpty()) {
            return
        }
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            Log.w(TAG, "Error stopping foreground ($reason)", e)
        }
        try {
            nm.cancel(SUMMARY_ID)
        } catch (e: Throwable) {
            Log.w(TAG, "Error cancelling summary notification ($reason)", e)
        }
        stopSelf()
    }

    /**
     * 解压归档文件到临时目录
     * @return 模型所在的目录
     */
    private suspend fun extractArchive(
        cacheFile: File,
        key: DownloadKey,
        variant: String,
        modelType: String,
        notificationHandler: NotificationHandler
    ): File {
        Log.d(TAG, "Starting extraction for variant: $variant")
        // 解压前准备通知/目录

        // 输出目录
        val base = getExternalFilesDir(null) ?: filesDir
        val outRoot = when (modelType) {
            "x_asr" -> File(base, "x_asr")
            "firered_asr" -> File(base, "firered_asr")
            "punctuation" -> File(base, "punctuation_tmp")
            "funasr_nano" -> File(base, "funasr_nano")
            "qwen3_asr" -> File(base, "qwen3_asr")
            "parakeet" -> File(base, "parakeet")
            "giga_am" -> File(base, "giga_am")
            else -> File(base, "sensevoice")
        }
        val tmpDir =
            File(outRoot, ".tmp_extract_${key.toSafeFileName()}_${System.currentTimeMillis()}")

        if (tmpDir.exists()) {
            tmpDir.deleteRecursively()
        }
        tmpDir.mkdirs()

        val cancelIntent = notificationHandler.createCancelIntent()
        val compressedTotal = cacheFile.length()
        notificationHandler.notifyExtractProgressImmediate(0, cancelIntent)
        if (detectArchiveType(cacheFile) != ArchiveType.ZIP) {
            throw IllegalStateException(getString(R.string.error_only_zip_supported))
        }
        extractZipWithCompressedProgress(cacheFile, tmpDir, compressedTotal) { percent ->
            notificationHandler.notifyExtractProgress(percent, cancelIntent)
        }

        Log.d(TAG, "Extraction completed to: ${tmpDir.path}")
        return tmpDir
    }

    /**
     * 验证模型文件并安装到最终目录
     */
    private suspend fun verifyAndInstallModel(tmpDir: File, variant: String, modelType: String) = withContext(Dispatchers.IO) {
        Log.d(TAG, "Verifying model files for variant: $variant")

        // 标点模型：单独走简化校验/安装逻辑（仅需 model.int8.onnx），不依赖 tokens.txt
        if (modelType == "punctuation") {
            verifyAndInstallPunctuationModel(tmpDir)
            return@withContext
        }

        // 校验并定位模型目录
        val modelDir = when (modelType) {
            // FunASR Nano 不含 tokens.txt；以多个 onnx + tokenizer 目录判定
            "funasr_nano" -> findFunAsrNanoModelDir(tmpDir)
            // Qwen3-ASR 不含 tokens.txt；以 conv/encoder/decoder + tokenizer 目录判定
            "qwen3_asr" -> findQwen3AsrModelDir(tmpDir)
            // Parakeet 使用 Nemo transducer 文件组：encoder/decoder/joiner + tokens
            "parakeet" -> findParakeetModelDir(tmpDir)
            "giga_am" -> findGigaAmModelDir(tmpDir)
            "x_asr" -> findXAsrInstallModelDir(tmpDir)
            else -> findModelDir(tmpDir)
        } ?: throw IllegalStateException("model dir not found")

        verifyModelIntegrity(modelDir, variant, modelType)

        Log.d(TAG, "Model files verified, installing to final location")

        // 确定最终输出目录
        val base = getExternalFilesDir(null) ?: filesDir
        val outFinal = when (modelType) {
            "x_asr" -> {
                val outRoot = File(base, "x_asr")
                File(outRoot, "x-asr-480ms")
            }
            "firered_asr" -> {
                val outRoot = File(base, "firered_asr")
                File(outRoot, variant)
            }
            "funasr_nano" -> {
                val outRoot = File(base, "funasr_nano")
                File(outRoot, com.brycewg.asrkb.asr.normalizeFunAsrNanoVariant(variant))
            }
            "qwen3_asr" -> {
                val outRoot = File(base, "qwen3_asr")
                File(outRoot, com.brycewg.asrkb.asr.normalizeQwen3AsrVariant(variant))
            }
            "parakeet" -> {
                val outRoot = File(base, "parakeet")
                File(outRoot, com.brycewg.asrkb.asr.normalizeParakeetVariant(variant))
            }
            "giga_am" -> {
                val outRoot = File(base, "giga_am")
                File(outRoot, com.brycewg.asrkb.asr.normalizeGigaAmVariant(variant))
            }
            else -> {
                val outRoot = File(base, "sensevoice")
                val dirName = when (variant) {
                    "small-full" -> "small-full"
                    "nano-full" -> "nano-full"
                    "nano-int8" -> "nano-int8"
                    else -> "small-int8"
                }
                File(outRoot, dirName)
            }
        }

        // 原子替换
        if (outFinal.exists()) {
            outFinal.deleteRecursively()
        }

        val renamed = tmpDir.renameTo(outFinal)
        if (!renamed) {
            Log.w(TAG, "Direct rename failed, falling back to recursive copy")
            copyDirRecursively(tmpDir, outFinal)
        }

        try {
            tmpDir.deleteRecursively()
        } catch (e: Throwable) {
            Log.w(TAG, "Error deleting temp directory: ${tmpDir.path}", e)
        }

        Log.d(TAG, "Model installation completed: ${outFinal.path}")
    }

    /**
     * 标点模型安装：
     * - 仅校验存在 model.int8.onnx；
     * - 最终落盘路径为 externalFilesDir/punctuation（或 filesDir/punctuation）。
     */
    private fun verifyAndInstallPunctuationModel(tmpDir: File) {
        val base = getExternalFilesDir(null) ?: filesDir
        val punctRoot = File(base, "punctuation")

        fun findPunctDir(root: File): File? {
            if (!root.exists()) return null
            val direct = File(root, "model.int8.onnx")
            if (direct.exists()) return root
            val subs = root.listFiles() ?: return null
            subs.forEach { f ->
                if (f.isDirectory) {
                    val m = File(f, "model.int8.onnx")
                    if (m.exists()) return f
                }
            }
            return null
        }

        val modelDir = findPunctDir(tmpDir)
            ?: throw IllegalStateException("punctuation files missing after extract")
        requireInstalledFiles(
            File(modelDir, "model.int8.onnx") to LocalModelSpecs.Punctuation.model,
            File(modelDir, "tokens.json") to LocalModelSpecs.Punctuation.tokens
        )

        Log.d(TAG, "Punctuation model dir located at: ${modelDir.path}")

        // 清理旧目录
        if (punctRoot.exists()) {
            try {
                punctRoot.deleteRecursively()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to delete old punctuation dir: ${punctRoot.path}", e)
            }
        }

        // 将模型目录迁移到 punctuation 根目录
        val renamed = try {
            modelDir.renameTo(punctRoot)
        } catch (e: Throwable) {
            Log.w(TAG, "Rename punctuation dir failed, will fallback to copy", e)
            false
        }
        if (!renamed) {
            Log.w(TAG, "Falling back to recursive copy for punctuation model")
            copyDirRecursivelyInternal(modelDir, punctRoot)
        }

        // 清理临时目录（包括父目录）
        val tmpRoot = tmpDir.parentFile ?: tmpDir
        try {
            if (tmpRoot.exists()) {
                tmpRoot.deleteRecursively()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error deleting tmp punctuation dir: ${tmpRoot.path}", e)
        }

        Log.d(TAG, "Punctuation model installation completed: ${punctRoot.path}")
    }

    private fun verifyModelIntegrity(modelDir: File, variant: String, modelType: String) {
        when (modelType) {
            "x_asr" -> requireInstalledFiles(
                File(modelDir, "tokens.txt") to LocalModelSpecs.XAsr.tokens,
                File(modelDir, "encoder-480ms.onnx") to LocalModelSpecs.XAsr.encoder,
                File(modelDir, "decoder-480ms.onnx") to LocalModelSpecs.XAsr.decoder,
                File(modelDir, "joiner-480ms.onnx") to LocalModelSpecs.XAsr.joiner
            )
            "firered_asr" -> requireInstalledFiles(
                File(modelDir, "tokens.txt") to LocalModelSpecs.FireRedAsr.tokens,
                File(modelDir, "model.int8.onnx") to LocalModelSpecs.FireRedAsr.ctcModel
            )
            "funasr_nano" -> {
                val tokenizerDir = findFunAsrNanoTokenizerDir(modelDir)
                    ?: throw IllegalStateException("funasr_nano tokenizer missing after extract")
                val specs = funAsrNanoInstallSpecs(variant)
                requireInstalledFiles(
                    File(modelDir, "encoder_adaptor.int8.onnx") to specs.encoderAdaptor,
                    File(modelDir, "llm.int8.onnx") to specs.llm,
                    File(modelDir, "embedding.int8.onnx") to specs.embedding,
                    File(tokenizerDir, "tokenizer.json") to LocalModelSpecs.FunAsrNano.tokenizer
                )
            }
            "qwen3_asr" -> {
                val tokenizerDir = findQwen3AsrTokenizerDir(modelDir)
                    ?: throw IllegalStateException("qwen3_asr tokenizer missing after extract")
                val specs = LocalModelSpecs.Qwen3Asr.forVariant(variant)
                requireInstalledFiles(
                    File(modelDir, "conv_frontend.onnx") to specs.convFrontend,
                    File(modelDir, "encoder.int8.onnx") to specs.encoder,
                    File(modelDir, "decoder.int8.onnx") to specs.decoder,
                    File(tokenizerDir, "merges.txt") to specs.merges,
                    File(tokenizerDir, "tokenizer_config.json") to specs.tokenizerConfig,
                    File(tokenizerDir, "vocab.json") to specs.vocab
                )
            }
            "parakeet" -> {
                val specs = parakeetInstallSpecs(variant)
                requireInstalledFiles(
                    File(modelDir, "tokens.txt") to specs.tokens,
                    File(modelDir, "encoder.int8.onnx") to specs.encoder,
                    File(modelDir, "decoder.int8.onnx") to specs.decoder,
                    File(modelDir, "joiner.int8.onnx") to specs.joiner
                )
            }
            "giga_am" -> {
                requireInstalledFiles(
                    File(modelDir, "tokens.txt") to LocalModelSpecs.GigaAm.tokens,
                    File(modelDir, "encoder.int8.onnx") to LocalModelSpecs.GigaAm.encoder,
                    File(modelDir, "decoder.onnx") to LocalModelSpecs.GigaAm.decoder,
                    File(modelDir, "joiner.onnx") to LocalModelSpecs.GigaAm.joiner
                )
            }
            else -> {
                val normalizedVariant = if (variant == "small-full") "small-full" else "small-int8"
                val modelSpec = if (normalizedVariant == "small-full") {
                    LocalModelSpecs.SenseVoice.smallFull
                } else {
                    LocalModelSpecs.SenseVoice.smallInt8
                }
                val modelFileName = if (normalizedVariant == "small-full") "model.onnx" else "model.int8.onnx"
                requireInstalledFiles(
                    File(modelDir, "tokens.txt") to LocalModelSpecs.SenseVoice.tokens,
                    File(modelDir, modelFileName) to modelSpec
                )
            }
        }
    }

    private fun requireInstalledFiles(vararg files: Pair<File, LocalModelFileSpec>) {
        when (val check = requireModelFilesCached(this, *files)) {
            is LocalModelCheck.Ready -> Unit
            LocalModelCheck.Missing -> throw IllegalStateException("model files missing after extract")
            is LocalModelCheck.IntegrityError -> throw ModelIntegrityException(
                getString(R.string.error_local_model_integrity_failed, check.fileName)
            )
        }
    }

    private fun funAsrNanoInstallSpecs(variant: String): FunAsrNanoInstallSpecs = if (com.brycewg.asrkb.asr.normalizeFunAsrNanoVariant(variant) == "mlt-int8") {
        FunAsrNanoInstallSpecs(
            encoderAdaptor = LocalModelSpecs.FunAsrNano.mltEncoderAdaptor,
            llm = LocalModelSpecs.FunAsrNano.mltLlm,
            embedding = LocalModelSpecs.FunAsrNano.mltEmbedding
        )
    } else {
        FunAsrNanoInstallSpecs(
            encoderAdaptor = LocalModelSpecs.FunAsrNano.nanoEncoderAdaptor,
            llm = LocalModelSpecs.FunAsrNano.nanoLlm,
            embedding = LocalModelSpecs.FunAsrNano.nanoEmbedding
        )
    }

    private data class FunAsrNanoInstallSpecs(
        val encoderAdaptor: LocalModelFileSpec,
        val llm: LocalModelFileSpec,
        val embedding: LocalModelFileSpec
    )

    private fun parakeetInstallSpecs(variant: String): ParakeetInstallSpecs = if (com.brycewg.asrkb.asr.normalizeParakeetVariant(variant) == "0.6b-v2-int8") {
        ParakeetInstallSpecs(
            tokens = LocalModelSpecs.Parakeet.v2Tokens,
            encoder = LocalModelSpecs.Parakeet.v2Encoder,
            decoder = LocalModelSpecs.Parakeet.v2Decoder,
            joiner = LocalModelSpecs.Parakeet.v2Joiner
        )
    } else {
        ParakeetInstallSpecs(
            tokens = LocalModelSpecs.Parakeet.v3Tokens,
            encoder = LocalModelSpecs.Parakeet.v3Encoder,
            decoder = LocalModelSpecs.Parakeet.v3Decoder,
            joiner = LocalModelSpecs.Parakeet.v3Joiner
        )
    }

    private data class ParakeetInstallSpecs(
        val tokens: LocalModelFileSpec,
        val encoder: LocalModelFileSpec,
        val decoder: LocalModelFileSpec,
        val joiner: LocalModelFileSpec
    )

    private fun ensureChannel() {
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_model_download),
            NotificationManager.IMPORTANCE_LOW
        )
        ch.description = getString(R.string.notif_channel_model_download_desc)
        nm.createNotificationChannel(ch)
    }

    // --- 解压与文件工具 ---

    private class CountingInputStream(private val input: java.io.InputStream) : java.io.InputStream() {
        @Volatile var bytesRead: Long = 0
            private set
        override fun read(): Int {
            val r = input.read()
            if (r >= 0) bytesRead++
            return r
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = input.read(b, off, len)
            if (n > 0) bytesRead += n
            return n
        }
        override fun close() = input.close()
        override fun available(): Int = input.available()
        override fun markSupported(): Boolean = input.markSupported()
        override fun mark(readlimit: Int) = input.mark(readlimit)
        override fun reset() = input.reset()
    }

    private enum class ArchiveType { ZIP, UNKNOWN }

    private fun detectArchiveType(file: File): ArchiveType = try {
        file.inputStream().use { ins ->
            val header = ByteArray(4)
            val n = ins.read(header)
            if (n >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) { // PK
                ArchiveType.ZIP
            } else {
                ArchiveType.UNKNOWN
            }
        }
    } catch (e: Throwable) {
        Log.w(TAG, "detectArchiveType failed", e)
        ArchiveType.UNKNOWN
    }

    private suspend fun extractZipWithCompressedProgress(
        file: File,
        outDir: File,
        compressedTotal: Long,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val ctx = coroutineContext
        val counting = CountingInputStream(file.inputStream().buffered(64 * 1024))
        ZipInputStream(counting).use { zis ->
            val buf = ByteArray(64 * 1024)
            var entry: ZipEntry? = zis.nextEntry
            var lastPercent = -1
            while (entry != null) {
                if (!ctx.isActive) {
                    throw CancellationException("Extraction cancelled")
                }
                val outFile = File(outDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    java.io.BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { bos ->
                        var written = 0L
                        while (true) {
                            if (!ctx.isActive) {
                                throw CancellationException("Extraction cancelled")
                            }
                            val n = zis.read(buf)
                            if (n <= 0) break
                            bos.write(buf, 0, n)
                            written += n
                            if (compressedTotal > 0L) {
                                val percent = ((counting.bytesRead * 100) / compressedTotal).toInt().coerceIn(
                                    0,
                                    100
                                )
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                        bos.flush()
                    }
                }
                // CRC 校验在 closeEntry 时由 ZipInputStream 执行；若失败会抛异常
                zis.closeEntry()
                entry = zis.nextEntry
            }
            // 结束时确保进度到 100%
            if (ctx.isActive) {
                onProgress(100)
            }
        }
    }

    private suspend fun copyDirRecursively(src: File, dst: File) {
        withContext(Dispatchers.IO) { copyDirRecursivelyInternal(src, dst) }
    }

    private fun copyDirRecursivelyInternal(src: File, dst: File) {
        if (!src.exists()) return
        if (src.isDirectory) {
            if (!dst.exists()) dst.mkdirs()
            src.listFiles()?.forEach { child ->
                val target = File(dst, child.name)
                if (child.isDirectory) {
                    copyDirRecursivelyInternal(child, target)
                } else {
                    target.parentFile?.mkdirs()
                    child.inputStream().use { ins ->
                        java.io.BufferedOutputStream(
                            FileOutputStream(target),
                            64 * 1024
                        ).use { bos ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n <= 0) break
                                bos.write(buf, 0, n)
                            }
                            bos.flush()
                        }
                    }
                }
            }
        } else {
            dst.parentFile?.mkdirs()
            src.inputStream().use { ins ->
                java.io.BufferedOutputStream(FileOutputStream(dst), 64 * 1024).use { bos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        bos.write(buf, 0, n)
                    }
                    bos.flush()
                }
            }
        }
    }

    private fun findModelDir(root: File): File? {
        if (!root.exists()) return null
        val direct = File(root, "tokens.txt")
        if (direct.exists()) return root
        val subs = root.listFiles() ?: return null
        subs.forEach { f ->
            if (f.isDirectory) {
                val t = File(f, "tokens.txt")
                if (t.exists()) return f
            }
        }
        return null
    }

    private fun findFunAsrNanoTokenizerDir(modelDir: File): File? {
        val direct = File(modelDir, "tokenizer.json")
        if (direct.exists()) return modelDir
        val qwen = File(modelDir, "Qwen3-0.6B")
        if (File(qwen, "tokenizer.json").exists()) return qwen
        val subs = modelDir.listFiles() ?: return null
        subs.forEach { f ->
            if (f.isDirectory && File(f, "tokenizer.json").exists()) return f
        }
        return null
    }

    private fun findFunAsrNanoModelDir(root: File): File? {
        if (!root.exists()) return null

        fun isValid(dir: File): Boolean {
            val encoderAdaptor = File(dir, "encoder_adaptor.int8.onnx")
            val llm = File(dir, "llm.int8.onnx")
            val embedding = File(dir, "embedding.int8.onnx")
            if (!encoderAdaptor.exists() || !llm.exists() || !embedding.exists()) return false
            return findFunAsrNanoTokenizerDir(dir) != null
        }

        if (isValid(root)) return root
        val subs = root.listFiles() ?: return null
        subs.forEach { f ->
            if (f.isDirectory && isValid(f)) return f
        }
        return null
    }

    private fun findQwen3AsrTokenizerDir(modelDir: File): File? {
        val tokenizer = File(modelDir, "tokenizer")
        if (isQwen3AsrTokenizerDir(tokenizer)) return tokenizer
        if (isQwen3AsrTokenizerDir(modelDir)) return modelDir
        return findQwen3AsrTokenizerDirRecursive(modelDir, maxDepth = 3)
    }

    private fun findQwen3AsrModelDir(root: File): File? {
        if (!root.exists()) return null
        return findQwen3AsrModelDirRecursive(root, maxDepth = 6)
    }

    private fun findQwen3AsrTokenizerDirRecursive(root: File, maxDepth: Int): File? {
        if (maxDepth < 0 || !root.isDirectory) return null
        if (isQwen3AsrTokenizerDir(root)) return root
        val subs = root.listFiles() ?: return null
        subs.forEach { f ->
            if (f.isDirectory) {
                findQwen3AsrTokenizerDirRecursive(f, maxDepth - 1)?.let { return it }
            }
        }
        return null
    }

    private fun isQwen3AsrModelDir(dir: File): Boolean {
        val convFrontend = File(dir, "conv_frontend.onnx")
        val encoder = File(dir, "encoder.int8.onnx")
        val decoder = File(dir, "decoder.int8.onnx")
        if (!convFrontend.exists() || !encoder.exists() || !decoder.exists()) return false
        return findQwen3AsrTokenizerDir(dir) != null
    }

    private fun isQwen3AsrTokenizerDir(dir: File): Boolean {
        if (!dir.isDirectory) return false
        return File(dir, "vocab.json").exists() &&
            File(dir, "merges.txt").exists() &&
            File(dir, "tokenizer_config.json").exists()
    }

    private fun findQwen3AsrModelDirRecursive(root: File, maxDepth: Int): File? {
        if (maxDepth < 0 || !root.isDirectory) return null
        if (isQwen3AsrModelDir(root)) return root
        val subs = root.listFiles() ?: return null
        subs.forEach { f ->
            if (f.isDirectory && f.name != "__MACOSX") {
                findQwen3AsrModelDirRecursive(f, maxDepth - 1)?.let { return it }
            }
        }
        return null
    }

    private fun findParakeetModelDir(root: File): File? {
        if (!root.exists() || !root.isDirectory) return null
        val hasFiles = File(root, "tokens.txt").exists() &&
            File(root, "encoder.int8.onnx").exists() &&
            File(root, "decoder.int8.onnx").exists() &&
            File(root, "joiner.int8.onnx").exists()
        if (hasFiles) return root
        val subs = root.listFiles() ?: return null
        subs.forEach { f ->
            if (f.isDirectory && f.name != "__MACOSX" && !f.name.startsWith(".tmp_")) {
                findParakeetModelDir(f)?.let { return it }
            }
        }
        return null
    }

    private fun findXAsrInstallModelDir(root: File): File? {
        if (!root.exists() || !root.isDirectory) return null
        val hasFiles = File(root, "tokens.txt").exists() &&
            File(root, "encoder-480ms.onnx").exists() &&
            File(root, "decoder-480ms.onnx").exists() &&
            File(root, "joiner-480ms.onnx").exists()
        if (hasFiles) return root
        val subs = root.listFiles() ?: return null
        subs.forEach { f ->
            if (f.isDirectory && f.name != "__MACOSX" && !f.name.startsWith(".")) {
                findXAsrInstallModelDir(f)?.let { return it }
            }
        }
        return null
    }

    private fun getDisplayNameFromUri(uri: android.net.Uri): String? = try {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    } catch (e: Throwable) {
        Log.w(TAG, "Failed to get display name from uri: $uri", e)
        null
    }
}

/**
 * 下载任务的唯一标识符
 * 使用数据类替代字符串拼接，提供类型安全和更好的可读性
 */
data class DownloadKey(val variant: String, val url: String) {
    companion object {
        private const val SEPARATOR = "|"
        private const val MAX_LENGTH = 200

        fun fromSerializedKey(serialized: String): DownloadKey {
            val parts = serialized.split(SEPARATOR, limit = 2)
            return if (parts.size == 2) {
                DownloadKey(parts[0], parts[1])
            } else {
                DownloadKey("", serialized)
            }
        }
    }

    fun toSerializedKey(): String = (variant + SEPARATOR + url).take(MAX_LENGTH)

    fun toSafeFileName(): String = toSerializedKey().replace("[^A-Za-z0-9._-]".toRegex(), "_")

    fun notifIdForKey(): Int = 2000 + (toSerializedKey().hashCode() and 0x7fffffff) % 100000
}

private class ModelIntegrityException(message: String) : IllegalStateException(message)

/**
 * 封装通知逻辑的处理器
 * 负责管理单个下载任务的通知状态，包括节流、进度更新和完成状态
 */
class NotificationHandler(
    private val context: Context,
    private val notificationManager: NotificationManager,
    val key: DownloadKey,
    private var variant: String,
    private var modelType: String
) {
    companion object {
        private const val THROTTLE_INTERVAL_MS = 500L
        private const val CHANNEL_ID = "model_download"
        private const val GROUP_ID = "model_download_group"
    }

    private var lastProgress: Int = -1
    private var lastNotifyTime: Long = 0L

    private val notifId: Int = key.notifIdForKey()
    private var title: String = getTitleForVariant()

    /**
     * 更新模型类型（用于导入时自动检测）
     */
    fun updateModelType(newModelType: String) {
        modelType = newModelType
        title = getTitleForVariant()
    }

    /** 更新变体编码（用于导入时根据文件名精准识别） */
    fun updateVariant(newVariant: String) {
        variant = newVariant
        title = getTitleForVariant()
    }

    /**
     * 创建取消下载的 PendingIntent
     */
    fun createCancelIntent(): PendingIntent = PendingIntent.getService(
        context,
        key.hashCode(),
        Intent(context, ModelDownloadService::class.java).apply {
            action = "com.brycewg.asrkb.action.MODEL_DOWNLOAD_CANCEL"
            putExtra("key", key.toSerializedKey())
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * 通知下载进度（带节流）
     */
    fun notifyDownloadProgress(progress: Int, cancelIntent: PendingIntent) {
        val text = when (modelType) {
            "x_asr" -> context.getString(R.string.x_asr_download_status_downloading, progress)
            "firered_asr" -> context.getString(R.string.fr_download_status_downloading, progress)
            "punctuation" -> context.getString(R.string.punct_download_status_downloading, progress)
            "funasr_nano" -> context.getString(R.string.fn_download_status_downloading, progress)
            "qwen3_asr" -> context.getString(R.string.qw_download_status_downloading, progress)
            "parakeet" -> context.getString(R.string.pk_download_status_downloading, progress)
            "giga_am" -> context.getString(R.string.giga_am_download_status_downloading, progress)
            else -> context.getString(R.string.sv_download_status_downloading, progress)
        }
        notifyProgress(
            progress = progress,
            text = text,
            indeterminate = false,
            ongoing = true,
            done = false,
            action = cancelIntent,
            throttle = true
        )
    }

    /**
     * 通知解压进度（带百分比与节流）
     */
    fun notifyExtractProgress(progress: Int, cancelIntent: PendingIntent) {
        val text = when (modelType) {
            "x_asr" -> context.getString(
                R.string.x_asr_download_status_extracting_progress,
                progress
            )
            "firered_asr" -> context.getString(
                R.string.fr_download_status_extracting_progress,
                progress
            )
            "punctuation" -> context.getString(
                R.string.punct_download_status_extracting_progress,
                progress
            )
            "funasr_nano" -> context.getString(
                R.string.fn_download_status_extracting_progress,
                progress
            )
            "qwen3_asr" -> context.getString(
                R.string.qw_download_status_extracting_progress,
                progress
            )
            "parakeet" -> context.getString(
                R.string.pk_download_status_extracting_progress,
                progress
            )
            "giga_am" -> context.getString(
                R.string.giga_am_download_status_extracting_progress,
                progress
            )
            else -> context.getString(R.string.sv_download_status_extracting_progress, progress)
        }
        notifyProgress(
            progress = progress,
            text = text,
            indeterminate = false,
            ongoing = true,
            done = false,
            action = cancelIntent,
            throttle = true
        )
    }

    /**
     * 立即切换为确定型解压进度（不节流），用于首次从转圈切换为0%
     */
    fun notifyExtractProgressImmediate(progress: Int, cancelIntent: PendingIntent) {
        val text = when (modelType) {
            "x_asr" -> context.getString(
                R.string.x_asr_download_status_extracting_progress,
                progress
            )
            "firered_asr" -> context.getString(
                R.string.fr_download_status_extracting_progress,
                progress
            )
            "punctuation" -> context.getString(
                R.string.punct_download_status_extracting_progress,
                progress
            )
            "funasr_nano" -> context.getString(
                R.string.fn_download_status_extracting_progress,
                progress
            )
            "qwen3_asr" -> context.getString(
                R.string.qw_download_status_extracting_progress,
                progress
            )
            "parakeet" -> context.getString(
                R.string.pk_download_status_extracting_progress,
                progress
            )
            "giga_am" -> context.getString(
                R.string.giga_am_download_status_extracting_progress,
                progress
            )
            else -> context.getString(R.string.sv_download_status_extracting_progress, progress)
        }
        notifyProgress(
            progress = progress,
            text = text,
            indeterminate = false,
            ongoing = true,
            done = false,
            action = cancelIntent,
            throttle = false,
            force = true
        )
    }

    /**
     * 通知下载成功
     */
    fun notifySuccess(text: String) {
        notifyProgress(
            progress = 100,
            text = text,
            indeterminate = false,
            ongoing = false,
            done = true,
            throttle = false,
            force = true
        )
    }

    /**
     * 通知下载失败
     */
    fun notifyFailed(text: String) {
        notifyProgress(
            progress = 0,
            text = text,
            indeterminate = false,
            ongoing = false,
            done = true,
            throttle = false,
            force = true
        )
    }

    private fun notifyProgress(
        progress: Int,
        text: String,
        indeterminate: Boolean = false,
        ongoing: Boolean = false,
        done: Boolean = false,
        action: PendingIntent? = null,
        throttle: Boolean = false,
        force: Boolean = false
    ) {
        // 节流：避免高频刷新被系统丢弃
        if (!force && throttle) {
            if (shouldThrottle(progress, indeterminate)) {
                return
            }
        }

        updateThrottleState(progress)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.cloud_arrow_down)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setGroup(GROUP_ID)
            .setOngoing(ongoing && !done)

        if (!done) {
            builder.setProgress(100, if (indeterminate) 0 else progress, indeterminate)
            action?.let {
                builder.addAction(0, context.getString(R.string.btn_cancel), it)
            }
        } else {
            builder.setProgress(0, 0, false)
        }

        // 点击跳转设置页
        val pi = PendingIntent.getActivity(
            context,
            key.hashCode() + 1,
            Intent(context, SettingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        builder.setContentIntent(pi)

        notificationManager.notify(notifId, builder.build())
    }

    private fun shouldThrottle(progress: Int, indeterminate: Boolean): Boolean {
        val now = System.currentTimeMillis()

        // 进度未变化且非不定进度，直接丢弃
        if (progress == lastProgress && !indeterminate) {
            return true
        }

        // 距离上次通知小于阈值，丢弃
        if (now - lastNotifyTime < THROTTLE_INTERVAL_MS) {
            return true
        }

        return false
    }

    private fun updateThrottleState(progress: Int) {
        lastProgress = progress
        lastNotifyTime = System.currentTimeMillis()
    }

    private fun getTitleForVariant(): String = when (modelType) {
        "x_asr" -> context.getString(R.string.notif_x_asr_title_480ms)
        "firered_asr" -> context.getString(R.string.notif_fr_title_ctc_int8)
        "punctuation" -> context.getString(R.string.notif_punct_title)
        "funasr_nano" -> {
            if (com.brycewg.asrkb.asr.normalizeFunAsrNanoVariant(variant) == "mlt-int8") {
                context.getString(R.string.notif_fn_title_mlt_nano_int8)
            } else {
                context.getString(R.string.notif_fn_title_nano_int8)
            }
        }
        "qwen3_asr" -> if (
            com.brycewg.asrkb.asr.normalizeQwen3AsrVariant(variant) == "qwen3-1.7b-int8"
        ) {
            context.getString(R.string.notif_qw_title_qwen3_17b_int8)
        } else {
            context.getString(R.string.notif_qw_title_qwen3_06b_int8)
        }
        "parakeet" -> {
            if (com.brycewg.asrkb.asr.normalizeParakeetVariant(variant) == "0.6b-v2-int8") {
                context.getString(R.string.notif_pk_title_06b_v2_int8)
            } else {
                context.getString(R.string.notif_pk_title_06b_v3_int8)
            }
        }
        "giga_am" -> context.getString(R.string.notif_giga_am_title_v3)
        else -> {
            when (variant) {
                "small-full" -> context.getString(R.string.notif_model_title_small_full)
                else -> context.getString(R.string.notif_model_title_small_int8)
            }
        }
    }
}
