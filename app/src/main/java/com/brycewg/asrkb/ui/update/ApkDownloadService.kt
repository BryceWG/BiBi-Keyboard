package com.brycewg.asrkb.ui.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.brycewg.asrkb.R
import com.brycewg.asrkb.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import com.brycewg.asrkb.store.Prefs

/**
 * APK 下载与安装服务
 *
 * 功能：
 * - 自动下载 APK 更新包到应用外部存储目录
 * - 显示下载进度通知
 * - 下载完成后自动弹出安装界面
 * - 高版本系统自动检测并引导用户开启安装权限
 * - 每次检测更新时自动清理旧的安装包
 */
class ApkDownloadService : Service() {

    override fun attachBaseContext(newBase: Context?) {
        val wrapped = newBase?.let { LocaleHelper.wrap(it) }
        super.attachBaseContext(wrapped ?: newBase)
    }

    companion object {
        private const val TAG = "ApkDownloadService"
        private const val CHANNEL_ID = "apk_download"
        private const val NOTIFICATION_ID = 3000

        private const val ACTION_START_DOWNLOAD = "com.brycewg.asrkb.action.APK_DOWNLOAD_START"
        private const val ACTION_CANCEL = "com.brycewg.asrkb.action.APK_DOWNLOAD_CANCEL"

        private const val EXTRA_URL = "url"
        private const val EXTRA_VERSION = "version"

        /**
         * 启动下载服务
         */
        fun startDownload(context: Context, url: String, version: String) {
            val intent = Intent(context, ApkDownloadService::class.java).apply {
                action = ACTION_START_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_VERSION, version)
            }
            context.startService(intent)
        }

        /**
         * 清理旧的 APK 安装包
         */
        fun cleanOldApks(context: Context) {
            try {
                val apkDir = getApkDirectory(context)
                if (apkDir.exists()) {
                    apkDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.name.endsWith(".apk")) {
                            val deleted = file.delete()
                            Log.d(TAG, "Cleaning old APK: ${file.name}, deleted=$deleted")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clean old APKs", e)
            }
        }

        /**
         * 获取 APK 存储目录
         */
        private fun getApkDirectory(context: Context): File {
            // 使用 Android/data/{packageName}/files/apk 目录
            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            return File(baseDir, "apk").apply {
                if (!exists()) {
                    mkdirs()
                }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationManager: NotificationManager
    private var isDownloading = false
    private var downloadJob: Job? = null
    @Volatile private var activeDownloadCall: Call? = null
    @Volatile private var downloadingApkFile: File? = null
    private var downloadedApkFile: File? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL)
                val version = intent.getStringExtra(EXTRA_VERSION)

                if (url != null && version != null && !isDownloading) {
                    isDownloading = true
                    startForegroundWithNotification()
                    downloadJob = scope.launch {
                        downloadAndInstall(url, version)
                    }
                } else {
                    stopSelfSafely()
                }
            }
            ACTION_CANCEL -> {
                Log.d(TAG, "Download cancelled by user")
                cancelDownload()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try {
            activeDownloadCall?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Error cancelling OkHttp call in onDestroy", t)
        }
        try {
            scope.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, "Error cancelling scope in onDestroy", e)
        }
        isDownloading = false
    }

    private fun cancelDownload() {
        if (!isDownloading && downloadJob?.isActive != true) {
            try {
                notificationManager.cancel(NOTIFICATION_ID)
            } catch (t: Throwable) {
                Log.w(TAG, "Error cancelling notification", t)
            }
            stopSelfSafely()
            return
        }

        Log.d(TAG, "Cancelling APK download...")

        val fileToDelete = downloadingApkFile
        try {
            activeDownloadCall?.cancel()
        } catch (t: Throwable) {
            Log.w(TAG, "Error cancelling OkHttp call", t)
        } finally {
            activeDownloadCall = null
        }

        downloadJob?.cancel(CancellationException("User cancelled APK download"))
        downloadJob = null
        isDownloading = false

        if (fileToDelete != null) {
            try {
                if (fileToDelete.exists()) {
                    val deleted = fileToDelete.delete()
                    Log.d(TAG, "Deleted partial APK: ${fileToDelete.path}, deleted=$deleted")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to delete partial APK: ${fileToDelete.path}", t)
            } finally {
                downloadingApkFile = null
            }
        } else {
            downloadingApkFile = null
        }

        try {
            notificationManager.cancel(NOTIFICATION_ID)
        } catch (t: Throwable) {
            Log.w(TAG, "Error cancelling notification", t)
        }

        stopSelfSafely()
    }

    /**
     * 启动前台服务并显示初始通知
     */
    private fun startForegroundWithNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.apk_download_notification_title))
            .setContentText(getString(R.string.apk_download_preparing))
            .setSmallIcon(R.drawable.cloud_arrow_down)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    /**
     * 下载并安装 APK
     */
    private suspend fun downloadAndInstall(url: String, version: String) {
        try {
            // 1. 下载 APK
            val apkFile = downloadApk(url, version)
            downloadedApkFile = apkFile

            // 将下载完成的 APK 路径保存，便于授权返回后自动重试安装
            try {
                Prefs(this@ApkDownloadService).pendingApkPath = apkFile.absolutePath
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to persist pending APK path", t)
            }

            // 2. 下载完成，显示成功通知（带点击安装功能）
            showDownloadCompleteNotification(apkFile)

            // 3. 检查并安装
            withContext(Dispatchers.Main) {
                installApk(apkFile)
            }
        } catch (e: CancellationException) {
            Log.d(TAG, "APK download cancelled", e)
        } catch (e: Exception) {
            Log.e(TAG, "Download or install failed", e)
            showDownloadFailedNotification(e.message ?: "Unknown error")
        } finally {
            isDownloading = false
            downloadJob = null
            activeDownloadCall = null
            downloadingApkFile = null
            // 延迟停止服务，让用户有时间看到通知
            if (kotlinx.coroutines.currentCoroutineContext().isActive) {
                kotlinx.coroutines.delay(2000)
            }
            stopSelfSafely()
        }
    }

    /**
     * 下载 APK 文件
     */
    private suspend fun downloadApk(url: String, version: String): File = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting APK download from: $url")

        val apkDir = getApkDirectory(this@ApkDownloadService)
        val apkFile = File(apkDir, "bibi-keyboard-$version.apk")
        downloadingApkFile = apkFile

        // 如果文件已存在，先删除
        if (apkFile.exists()) {
            apkFile.delete()
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "BiBiKeyboard-Android")
            .build()

        val call = client.newCall(request)
        activeDownloadCall = call
        var completed = false

        try {
            val response = call.execute()
            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw Exception("HTTP ${resp.code}")
                }

                val body = resp.body ?: throw Exception("Empty response body")
                val totalBytes = body.contentLength()

                apkFile.outputStream().use { outputStream ->
                    var downloadedBytes = 0L
                    val buffer = ByteArray(128 * 1024)

                    body.byteStream().use { inputStream ->
                        while (true) {
                            if (!coroutineContext.isActive) {
                                call.cancel()
                                throw CancellationException("APK download cancelled")
                            }

                            val bytesRead = inputStream.read(buffer)
                            if (bytesRead <= 0) break

                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            // 更新进度
                            if (totalBytes > 0) {
                                val progress = ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                updateDownloadProgress(progress, downloadedBytes, totalBytes)
                            }
                        }
                    }
                }
            }

            completed = true
        } catch (t: Throwable) {
            if (t is CancellationException) {
                throw t
            }
            if (!coroutineContext.isActive || call.isCanceled()) {
                throw CancellationException("APK download cancelled")
            }
            throw t
        } finally {
            activeDownloadCall = null
            downloadingApkFile = null

            if (!completed) {
                try {
                    if (apkFile.exists()) {
                        val deleted = apkFile.delete()
                        Log.d(TAG, "Deleted incomplete APK: ${apkFile.path}, deleted=$deleted")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Failed to delete incomplete APK: ${apkFile.path}", t)
                }
            }
        }

        Log.d(TAG, "APK download completed: ${apkFile.path}")
        return@withContext apkFile
    }

    /**
     * 更新下载进度通知
     */
    private fun updateDownloadProgress(progress: Int, downloaded: Long, total: Long) {
        val downloadedMB = downloaded / (1024 * 1024)
        val totalMB = total / (1024 * 1024)

        val cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ApkDownloadService::class.java).apply {
                action = ACTION_CANCEL
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.apk_download_notification_title))
            .setContentText(getString(R.string.apk_download_progress, progress, downloadedMB, totalMB))
            .setSmallIcon(R.drawable.cloud_arrow_down)
            .setOngoing(true)
            .setProgress(100, progress, false)
            .addAction(0, getString(R.string.btn_cancel), cancelIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 显示下载完成通知（点击可安装）
     */
    private fun showDownloadCompleteNotification(apkFile: File) {
        // 创建安装 Intent
        val installIntent = createInstallIntent(apkFile)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            installIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.apk_download_notification_title))
            .setContentText(getString(R.string.apk_download_complete_click_to_install))
            .setSmallIcon(R.drawable.cloud_arrow_down)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 创建安装 Intent
     */
    private fun createInstallIntent(apkFile: File): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION

            // 使用 FileProvider
            val uri = FileProvider.getUriForFile(
                this@ApkDownloadService,
                "${packageName}.fileprovider",
                apkFile
            )
            setDataAndType(uri, "application/vnd.android.package-archive")
        }
    }

    /**
     * 显示下载失败通知
     */
    private fun showDownloadFailedNotification(errorMessage: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.apk_download_notification_title))
            .setContentText(getString(R.string.apk_download_failed, errorMessage))
            .setSmallIcon(R.drawable.cloud_arrow_down)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 安装 APK
     */
    private fun installApk(apkFile: File) {
        try {
            // 检查安装权限
            if (!packageManager.canRequestPackageInstalls()) {
                Log.d(TAG, "Missing install permission, requesting...")
                requestInstallPermission()
                return
            }

            // 使用创建的安装 Intent
            val intent = createInstallIntent(apkFile)
            startActivity(intent)
            Log.d(TAG, "Install intent sent for: ${apkFile.path}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install APK", e)
            showInstallFailedNotification()
        }
    }

    /**
     * 请求安装权限
     */
    private fun requestInstallPermission() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)

            // 显示提示通知
            showPermissionRequiredNotification()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request install permission", e)
            showInstallFailedNotification()
        }
    }

    /**
     * 显示需要权限的通知
     */
    private fun showPermissionRequiredNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.apk_install_permission_required_title))
            .setContentText(getString(R.string.apk_install_permission_required_message))
            .setSmallIcon(R.drawable.cloud_arrow_down)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 显示安装失败通知
     */
    private fun showInstallFailedNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.apk_download_notification_title))
            .setContentText(getString(R.string.apk_install_failed))
            .setSmallIcon(R.drawable.cloud_arrow_down)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * 创建通知渠道
     */
    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.apk_download_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.apk_download_notification_channel_desc)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * 安全停止服务
     */
    private fun stopSelfSafely() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            Log.w(TAG, "Error stopping foreground", e)
        }
        stopSelf()
    }
}
