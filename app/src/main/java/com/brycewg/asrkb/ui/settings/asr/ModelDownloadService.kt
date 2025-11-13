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
import androidx.core.content.ContextCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.LocaleHelper
import com.brycewg.asrkb.ui.SettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

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
    private const val EXTRA_MODEL_TYPE = "modelType" // sensevoice | paraformer

    fun startDownload(context: Context, url: String, variant: String) {
      val key = DownloadKey(variant, url)
      val i = Intent(context, ModelDownloadService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_URL, url)
        putExtra(EXTRA_VARIANT, variant)
        putExtra(EXTRA_KEY, key.toSerializedKey())
        putExtra(EXTRA_MODEL_TYPE, "sensevoice")
      }
      ContextCompat.startForegroundService(context, i)
    }

    fun startDownload(context: Context, url: String, variant: String, modelType: String) {
      val key = DownloadKey(variant, url)
      val i = Intent(context, ModelDownloadService::class.java).apply {
        action = ACTION_START
        putExtra(EXTRA_URL, url)
        putExtra(EXTRA_VARIANT, variant)
        putExtra(EXTRA_KEY, key.toSerializedKey())
        putExtra(EXTRA_MODEL_TYPE, modelType)
      }
      ContextCompat.startForegroundService(context, i)
    }

    fun startImport(context: Context, uri: android.net.Uri, variant: String) {
      val key = DownloadKey(variant, "import_${uri.lastPathSegment ?: "unknown"}")
      val i = Intent(context, ModelDownloadService::class.java).apply {
        action = ACTION_IMPORT
        putExtra(EXTRA_URI, uri.toString())
        putExtra(EXTRA_VARIANT, variant)
        putExtra(EXTRA_KEY, key.toSerializedKey())
      }
      ContextCompat.startForegroundService(context, i)
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val tasks = ConcurrentHashMap<DownloadKey, kotlinx.coroutines.Job>()
  private val notificationHandlers = ConcurrentHashMap<DownloadKey, NotificationHandler>()
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
        val serializedKey = intent.getStringExtra(EXTRA_KEY) ?: DownloadKey(variant, url).toSerializedKey()
        val key = DownloadKey.fromSerializedKey(serializedKey)
        val modelType = intent.getStringExtra(EXTRA_MODEL_TYPE) ?: "sensevoice"

        if (!tasks.containsKey(key)) {
          if (tasks.isEmpty()) startAsForegroundSummary()

          val notificationHandler = NotificationHandler(
            context = this,
            notificationManager = nm,
            key = key,
            variant = variant,
            modelType = modelType
          )
          notificationHandlers[key] = notificationHandler

          val job = scope.launch { doDownloadTask(key, url, variant, modelType, notificationHandler) }
          tasks[key] = job
        }
      }
      ACTION_IMPORT -> {
        val uriString = intent.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY
        val uri = android.net.Uri.parse(uriString)
        val variant = intent.getStringExtra(EXTRA_VARIANT) ?: ""
        val serializedKey = intent.getStringExtra(EXTRA_KEY) ?: DownloadKey(variant, "import").toSerializedKey()
        val key = DownloadKey.fromSerializedKey(serializedKey)

        if (!tasks.containsKey(key)) {
          if (tasks.isEmpty()) startAsForegroundSummary()

          val notificationHandler = NotificationHandler(
            context = this,
            notificationManager = nm,
            key = key,
            variant = variant,
            modelType = "auto" // Will be detected from file content
          )
          notificationHandlers[key] = notificationHandler

          val job = scope.launch { doImportTask(key, uri, variant, notificationHandler) }
          tasks[key] = job
        }
      }
      ACTION_CANCEL -> {
        val serializedKey = intent.getStringExtra(EXTRA_KEY) ?: return START_NOT_STICKY
        val key = DownloadKey.fromSerializedKey(serializedKey)

        tasks.remove(key)?.cancel()
        // 由各自 handler 决定文案
        notificationHandlers[key]?.notifyFailed(
          notificationHandlers[key]?.getFailedText() ?: getString(R.string.sv_download_status_failed)
        )
        notificationHandlers.remove(key)
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
    val cacheFile = File(cacheDir, key.toSafeFileName() + ".tar.bz2")

    try {
      // 下载文件
      downloadFile(url, cacheFile, notificationHandler)

      // 解压归档
      val modelDir = extractArchive(cacheFile, key, variant, modelType, notificationHandler)

      // 验证并安装模型
      verifyAndInstallModel(modelDir, variant, modelType)

      val doneText = when (modelType) {
        "paraformer" -> getString(R.string.pf_download_status_done)
        "zipformer" -> getString(R.string.zf_download_status_done)
        else -> getString(R.string.sv_download_status_done)
      }
      notificationHandler.notifySuccess(doneText)
    } catch (t: Throwable) {
      Log.e(TAG, "Download task failed for key=$key, url=$url", t)
      val failText = when (modelType) {
        "paraformer" -> getString(R.string.pf_download_status_failed)
        "zipformer" -> getString(R.string.zf_download_status_failed)
        else -> getString(R.string.sv_download_status_failed)
      }
      notificationHandler.notifyFailed(failText)
    } finally {
      tasks.remove(key)
      notificationHandlers.remove(key)

      // 若无任务，结束前台与自身
      if (tasks.isEmpty()) {
        try {
          stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
          Log.w(TAG, "Error stopping foreground in finally", e)
        }
        stopSelf()
      }
      try {
        cacheFile.delete()
      } catch (e: Throwable) {
        Log.w(TAG, "Error deleting cache file: ${cacheFile.path}", e)
      }
    }
  }

  /**
   * 从本地文件导入模型
   */
  private suspend fun doImportTask(
    key: DownloadKey,
    uri: android.net.Uri,
    variant: String,
    notificationHandler: NotificationHandler
  ) {
    val cacheFile = File(cacheDir, key.toSafeFileName() + ".tar.bz2")

    try {
      // 从 Uri 复制文件到缓存目录
      copyFileFromUri(uri, cacheFile, notificationHandler)

      // 检测模型类型
      val modelType = detectModelType(cacheFile)
      if (modelType == null) {
        throw IllegalStateException(getString(R.string.sv_import_failed, "无法识别模型类型"))
      }

      // 更新通知处理器的模型类型
      notificationHandler.updateModelType(modelType)

      // 解压归档
      val modelDir = extractArchive(cacheFile, key, variant, modelType, notificationHandler)

      // 验证并安装模型
      verifyAndInstallModel(modelDir, variant, modelType)

      // 构造成功消息，包含模型类型和版本信息
      val modelInfo = getModelInfo(modelType, variant)
      val successMessage = getString(R.string.sv_import_success, modelInfo)
      notificationHandler.notifySuccess(successMessage)
    } catch (t: Throwable) {
      Log.e(TAG, "Import task failed for key=$key, uri=$uri", t)
      val errorMessage = t.message ?: "Unknown error"
      val failMessage = getString(R.string.sv_import_failed, errorMessage)
      notificationHandler.notifyFailed(failMessage)
    } finally {
      tasks.remove(key)
      notificationHandlers.remove(key)

      // 若无任务，结束前台与自身
      if (tasks.isEmpty()) {
        try {
          stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
          Log.w(TAG, "Error stopping foreground in finally", e)
        }
        stopSelf()
      }
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
   * 检测模型类型
   * 通过读取 tar.bz2 文件内容来判断是哪种模型
   */
  private suspend fun detectModelType(file: File): String? = withContext(Dispatchers.IO) {
    try {
      var hasTokensTxt = false
      var hasSenseVoiceModel = false
      var hasParaformerModel = false
      var hasZipformerModel = false

      BZip2CompressorInputStream(file.inputStream().buffered(64 * 1024)).use { bz ->
        TarArchiveInputStream(bz).use { tar ->
          var entry = tar.nextEntry
          while (entry != null) {
            val name = entry.name.lowercase()

            when {
              name.endsWith("tokens.txt") -> hasTokensTxt = true
              name.contains("sense-voice") || name.endsWith("model.int8.onnx") -> hasSenseVoiceModel = true
              name.contains("paraformer") -> hasParaformerModel = true
              name.contains("zipformer") || name.contains("encoder-epoch") || name.contains("decoder-epoch") || name.contains("joiner-epoch") -> hasZipformerModel = true
            }

            entry = tar.nextEntry
          }
        }
      }

      // 根据检测到的特征返回模型类型
      return@withContext when {
        hasSenseVoiceModel && hasTokensTxt -> "sensevoice"
        hasParaformerModel && hasTokensTxt -> "paraformer"
        hasZipformerModel && hasTokensTxt -> "zipformer"
        else -> null // 无法识别
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error detecting model type", e)
      null
    }
  }

  /**
   * 获取模型信息字符串
   */
  private fun getModelInfo(modelType: String, variant: String): String {
    return when (modelType) {
      "sensevoice" -> {
        val versionName = if (variant == "small-full") "Small (fp32)" else "Small (int8)"
        "SenseVoice $versionName"
      }
      "paraformer" -> {
        val versionName = when (variant) {
          "bilingual" -> "双语版"
          "trilingual" -> "三语版"
          else -> variant
        }
        "Paraformer $versionName"
      }
      "zipformer" -> {
        val versionName = when {
          variant.contains("bilingual") -> "双语版"
          variant.contains("zh") -> "中文版"
          else -> variant
        }
        "Zipformer $versionName"
      }
      else -> "$modelType $variant"
    }
  }

  /**
   * 下载文件到本地缓存
   */
  private suspend fun downloadFile(
    url: String,
    cacheFile: File,
    notificationHandler: NotificationHandler
  ) = withContext(Dispatchers.IO) {
    Log.d(TAG, "Starting download from: $url")

    val cancelIntent = notificationHandler.createCancelIntent()
    notificationHandler.notifyDownloadProgress(0, cancelIntent)

    val ok = OkHttpClient()
    val req = Request.Builder().url(url).build()

    ok.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) {
        throw IllegalStateException("HTTP ${resp.code}")
      }

      val body = resp.body ?: throw IllegalStateException("empty body")
      val total = body.contentLength()

      cacheFile.outputStream().use { out ->
        var readSum = 0L
        val buf = ByteArray(128 * 1024)

        body.byteStream().use { ins ->
          while (true) {
            val n = ins.read(buf)
            if (n <= 0) break

            out.write(buf, 0, n)
            readSum += n

            if (total > 0L) {
              val progress = ((readSum * 100) / total).toInt().coerceIn(0, 100)
              notificationHandler.notifyDownloadProgress(progress, cancelIntent)
            }
          }
        }
      }
    }

    Log.d(TAG, "Download completed: ${cacheFile.path}")
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
      "paraformer" -> File(base, "paraformer")
      "zipformer" -> File(base, "zipformer")
      else -> File(base, "sensevoice")
    }
    val tmpDir = File(outRoot, ".tmp_extract_${key.toSafeFileName()}_${System.currentTimeMillis()}")

    if (tmpDir.exists()) {
      tmpDir.deleteRecursively()
    }
    tmpDir.mkdirs()

    val cancelIntent = notificationHandler.createCancelIntent()
    // 使用压缩包大小作为总体进度的近似值，避免二次全量扫描带来的长时间转圈
    val compressedTotal = cacheFile.length()
    if (compressedTotal > 0L) {
      // 切换为确定型进度（0%）
      notificationHandler.notifyExtractProgressImmediate(0, cancelIntent)
      extractTarBz2WithCompressedProgress(cacheFile, tmpDir, compressedTotal) { percent ->
        // 百分比已在方法内按压缩读取量计算，这里直接透传
        notificationHandler.notifyExtractProgress(percent, cancelIntent)
      }
    } else {
      // 回退：无法获取压缩大小时，仍切换为 0% 决定型进度，结束后补 100%
      notificationHandler.notifyExtractProgressImmediate(0, cancelIntent)
      extractTarBz2Strict(cacheFile, tmpDir)
      notificationHandler.notifyExtractProgress(100, cancelIntent)
    }

    Log.d(TAG, "Extraction completed to: ${tmpDir.path}")
    return tmpDir
  }

  /**
   * 验证模型文件并安装到最终目录
   */
  private suspend fun verifyAndInstallModel(
    tmpDir: File,
    variant: String,
    modelType: String
  ) = withContext(Dispatchers.IO) {
    Log.d(TAG, "Verifying model files for variant: $variant")

    // 校验并定位模型目录
    val modelDir = findModelDir(tmpDir)
    if (modelDir == null) throw IllegalStateException("model dir not found")
    val tokens = File(modelDir, "tokens.txt")
    if (!tokens.exists()) throw IllegalStateException("tokens.txt missing")

    if (modelType == "paraformer") {
      val encInt8 = File(modelDir, "encoder.int8.onnx")
      val decInt8 = File(modelDir, "decoder.int8.onnx")
      val encF32 = File(modelDir, "encoder.onnx")
      val decF32 = File(modelDir, "decoder.onnx")
      if (!((encInt8.exists() && decInt8.exists()) || (encF32.exists() && decF32.exists()))) {
        throw IllegalStateException("paraformer files missing after extract")
      }
    } else if (modelType == "zipformer") {
      val files = modelDir.listFiles() ?: emptyArray()
      fun exists(regex: Regex): Boolean = files.any { it.isFile && regex.matches(it.name) }
      val hasTokens = File(modelDir, "tokens.txt").exists()
      val hasEncoder = exists(Regex("^encoder(?:-epoch-\\d+-avg-\\d+)?(?:\\.int8)?\\.onnx$"))
      val hasDecoder = exists(Regex("^decoder(?:-epoch-\\d+-avg-\\d+)?(?:\\.int8)?\\.onnx$")) || exists(Regex("^decoder\\.onnx$"))
      val hasJoiner = exists(Regex("^joiner(?:-epoch-\\d+-avg-\\d+)?(?:\\.int8)?\\.onnx$"))
      if (!(hasTokens && hasEncoder && hasDecoder && hasJoiner)) {
        throw IllegalStateException("zipformer files missing after extract (pattern)")
      }
    } else {
      if (!(File(modelDir, "model.int8.onnx").exists() || File(modelDir, "model.onnx").exists())) {
        throw IllegalStateException("sensevoice files missing after extract")
      }
    }

    Log.d(TAG, "Model files verified, installing to final location")

    // 确定最终输出目录
    val base = getExternalFilesDir(null) ?: filesDir
    val outFinal = when (modelType) {
      "paraformer" -> {
        val outRoot = File(base, "paraformer")
        val group = if (variant.startsWith("trilingual")) "trilingual" else "bilingual"
        File(outRoot, group)
      }
      "zipformer" -> {
        val outRoot = File(base, "zipformer")
        val groupDir = when {
          variant.startsWith("zh-xl-") -> "zh-xlarge-2025-06-30"
          variant.startsWith("zh-") -> "zh-2025-06-30"
          variant.startsWith("bi-small-") -> "small-bilingual-zh-en-2023-02-16"
          else -> "bilingual-zh-en-2023-02-20"
        }
        File(outRoot, groupDir)
      }
      else -> {
        val outRoot = File(base, "sensevoice")
        if (variant == "small-full") File(outRoot, "small-full") else File(outRoot, "small-int8")
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

  private suspend fun extractTarBz2WithCompressedProgress(
    file: File,
    outDir: File,
    compressedTotal: Long,
    onProgress: (Int) -> Unit
  ) = withContext(Dispatchers.IO) {
    val counting = CountingInputStream(file.inputStream().buffered(64 * 1024))
    BZip2CompressorInputStream(counting).use { bz ->
      TarArchiveInputStream(bz).use { tar ->
        var entry = tar.nextEntry
        val buf = ByteArray(64 * 1024)
        var lastPercent = -1
        while (entry != null) {
          val outFile = File(outDir, entry.name)
          if (entry.isDirectory) {
            outFile.mkdirs()
          } else {
            outFile.parentFile?.mkdirs()
            var written = 0L
            java.io.BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { bos ->
              while (true) {
                val n = tar.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
                written += n
                // 依据已读取的压缩字节计算进度（单调、无需二次解压）
                val percent = ((counting.bytesRead * 100) / compressedTotal).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                  lastPercent = percent
                  onProgress(percent)
                }
              }
              bos.flush()
            }
            // 仍保持严格校验
            val expected = (entry.size)
            if (expected >= 0 && written != expected) {
              try { outFile.delete() } catch (e: Throwable) { Log.w(TAG, "Error deleting mismatched file: ${outFile.path}", e) }
              throw IllegalStateException("tar entry size mismatch: ${entry.name}")
            }
          }
          entry = tar.nextEntry
        }
        // 结束时确保进度到 100%
        onProgress(100)
      }
    }
  }

  private suspend fun extractTarBz2Strict(file: File, outDir: File) = withContext(Dispatchers.IO) {
    BZip2CompressorInputStream(file.inputStream().buffered(64 * 1024)).use { bz ->
      TarArchiveInputStream(bz).use { tar ->
        var entry = tar.nextEntry
        val buf = ByteArray(64 * 1024)
        while (entry != null) {
          val outFile = File(outDir, entry.name)
          if (entry.isDirectory) {
            outFile.mkdirs()
          } else {
            outFile.parentFile?.mkdirs()
            var written = 0L
            java.io.BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { bos ->
              while (true) {
                val n = tar.read(buf)
                if (n <= 0) break
                bos.write(buf, 0, n)
                written += n
              }
              bos.flush()
            }
            if (written != entry.size) {
              try {
                outFile.delete()
              } catch (e: Throwable) {
                Log.w(TAG, "Error deleting mismatched file: ${outFile.path}", e)
              }
              throw IllegalStateException("tar entry size mismatch: ${entry.name}")
            }
          }
          entry = tar.nextEntry
        }
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
            java.io.BufferedOutputStream(FileOutputStream(target), 64 * 1024).use { bos ->
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
}

/**
 * 下载任务的唯一标识符
 * 使用数据类替代字符串拼接，提供类型安全和更好的可读性
 */
data class DownloadKey(
  val variant: String,
  val url: String
) {
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

  fun toSerializedKey(): String {
    return (variant + SEPARATOR + url).take(MAX_LENGTH)
  }

  fun toSafeFileName(): String {
    return toSerializedKey().replace("[^A-Za-z0-9._-]".toRegex(), "_")
  }

  fun notifIdForKey(): Int {
    return 2000 + (toSerializedKey().hashCode() and 0x7fffffff) % 100000
  }
}

/**
 * 封装通知逻辑的处理器
 * 负责管理单个下载任务的通知状态，包括节流、进度更新和完成状态
 */
class NotificationHandler(
  private val context: Context,
  private val notificationManager: NotificationManager,
  val key: DownloadKey,
  private val variant: String,
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

  /**
   * 创建取消下载的 PendingIntent
   */
  fun createCancelIntent(): PendingIntent {
    return PendingIntent.getService(
      context,
      key.hashCode(),
      Intent(context, ModelDownloadService::class.java).apply {
        action = "com.brycewg.asrkb.action.MODEL_DOWNLOAD_CANCEL"
        putExtra("key", key.toSerializedKey())
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
  }

  /**
   * 通知下载进度（带节流）
   */
  fun notifyDownloadProgress(progress: Int, cancelIntent: PendingIntent) {
    val text = when (modelType) {
      "paraformer" -> context.getString(R.string.pf_download_status_downloading, progress)
      "zipformer" -> context.getString(R.string.zf_download_status_downloading, progress)
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
      "paraformer" -> context.getString(R.string.pf_download_status_extracting_progress, progress)
      "zipformer" -> context.getString(R.string.zf_download_status_extracting_progress, progress)
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
      "paraformer" -> context.getString(R.string.pf_download_status_extracting_progress, progress)
      "zipformer" -> context.getString(R.string.zf_download_status_extracting_progress, progress)
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

  fun getFailedText(): String {
    return when (modelType) {
      "paraformer" -> context.getString(R.string.pf_download_status_failed)
      "zipformer" -> context.getString(R.string.zf_download_status_failed)
      else -> context.getString(R.string.sv_download_status_failed)
    }
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

  private fun getTitleForVariant(): String {
    return when (modelType) {
      "paraformer" -> {
        if (variant.startsWith("trilingual"))
          context.getString(R.string.notif_pf_title_trilingual)
        else
          context.getString(R.string.notif_pf_title_bilingual)
      }
      "zipformer" -> {
        when {
          variant.startsWith("zh-xl-") -> context.getString(R.string.notif_zf_title_zh_xl)
          variant.startsWith("zh-") -> context.getString(R.string.notif_zf_title_zh)
          variant.startsWith("bi-small-") -> context.getString(R.string.notif_zf_title_small_bi)
          else -> context.getString(R.string.notif_zf_title_bi)
        }
      }
      else -> {
        when (variant) {
          "small-full" -> context.getString(R.string.notif_model_title_full)
          else -> context.getString(R.string.notif_model_title_int8)
        }
      }
    }
  }
}
