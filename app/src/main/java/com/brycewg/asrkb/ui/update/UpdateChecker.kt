package com.brycewg.asrkb.ui.update

import android.content.Context
import android.util.Log
import com.brycewg.asrkb.LocaleHelper
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * 更新检查器
 *
 * 负责从多个 GitHub 镜像源获取版本信息，比较版本号，返回更新结果
 */
class UpdateChecker(private val context: Context) {
    companion object {
        private const val TAG = "UpdateChecker"

        // 多个镜像源 URL（优先级从高到低）
        private val MIRROR_SOURCES = listOf(
            // 优先使用可用镜像代理 raw 内容（更新更及时）
            "https://hub.gitmirror.com/https://raw.githubusercontent.com/BryceWG/BiBi-Keyboard/main/version.json",
            "https://ghproxy.net/https://raw.githubusercontent.com/BryceWG/BiBi-Keyboard/main/version.json",
            // 直接读取 GitHub 原始内容
            "https://raw.githubusercontent.com/BryceWG/BiBi-Keyboard/main/version.json",
            // jsDelivr 主域 (作为兜底）
            "https://cdn.jsdelivr.net/gh/BryceWG/BiBi-Keyboard@main/version.json"
        )
    }

    /**
     * 更新检查结果
     *
     * @property hasUpdate 是否有更新
     * @property currentVersion 当前版本号
     * @property latestVersion 最新版本号
     * @property downloadUrl 下载地址
     * @property updateTime 更新时间（可选）
     * @property releaseNotes 发布说明（可选）
     * @property importantNotice 重要提示（可选）
     * @property noticeLevel 提示级别（info/warning/critical，默认 info）
     */
    data class UpdateCheckResult(
        val hasUpdate: Boolean,
        val currentVersion: String,
        val latestVersion: String,
        val downloadUrl: String,
        val updateTime: String? = null,
        val releaseNotes: String? = null,
        val importantNotice: String? = null,
        val noticeLevel: NoticeLevel = NoticeLevel.INFO
    )

    /**
     * 提示级别
     */
    enum class NoticeLevel {
        INFO, // 普通信息（使用 tertiary 颜色）
        WARNING, // 警告信息（使用 secondary 颜色）
        CRITICAL // 重要信息（使用 error 颜色）
    }

    /**
     * 检查 GitHub Release 更新
     *
     * 并发请求多个镜像源，选择版本号最高的作为最新版本
     *
     * @return 更新检查结果
     * @throws Exception 如果所有源都失败
     */
    suspend fun checkGitHubRelease(): UpdateCheckResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting update check")

        val client = createHttpClient()
        val currentVersion = getCurrentVersion()

        // 加入时间戳参数，降低 CDN 旧缓存命中概率（按分钟变动，避免每秒都变）
        val timestamp = (System.currentTimeMillis() / 60_000L).toString()
        val urls = MIRROR_SOURCES.map { "$it?t=$timestamp" }

        Log.d(TAG, "Current version: $currentVersion")
        Log.d(TAG, "Checking ${urls.size} mirror sources")

        val remoteVersions = fetchRemoteVersions(client, urls)

        if (remoteVersions.isEmpty()) {
            Log.e(TAG, "All mirror sources failed")
            throw Exception("无法从任何镜像源获取版本信息")
        }

        Log.d(TAG, "Successfully fetched ${remoteVersions.size} remote versions")

        // 选择版本号最高的作为最新版本
        val latestRemote = selectLatestVersion(remoteVersions)

        Log.d(TAG, "Latest remote version: ${latestRemote.version}")

        val hasUpdate = compareVersions(latestRemote.version, currentVersion) > 0

        Log.d(TAG, "Update available: $hasUpdate")

        UpdateCheckResult(
            hasUpdate = hasUpdate,
            currentVersion = currentVersion,
            latestVersion = latestRemote.version,
            downloadUrl = latestRemote.downloadUrl,
            updateTime = latestRemote.updateTime,
            releaseNotes = latestRemote.releaseNotes,
            importantNotice = latestRemote.importantNotice,
            noticeLevel = latestRemote.noticeLevel
        )
    }

    /**
     * 创建 HTTP 客户端
     */
    private fun createHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * 获取当前应用版本号
     */
    private fun getCurrentVersion(): String = try {
        val versionName = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName ?: "unknown"
        normalizeVersion(versionName)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get current version", e)
        "unknown"
    }

    /**
     * 并发从多个源获取远程版本信息
     *
     * @return 成功获取的远程版本列表（可能为空）
     */
    private suspend fun fetchRemoteVersions(
        client: OkHttpClient,
        urls: List<String>
    ): List<RemoteVersion> = coroutineScope {
        // 为每个 URL 创建异步任务
        val jobs = urls.map { url ->
            async(Dispatchers.IO) {
                try {
                    fetchVersionFromUrl(client, url)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to fetch from $url: ${e.message}")
                    null
                }
            }
        }

        // 等待所有任务完成，过滤掉失败的结果
        jobs.mapNotNull { it.await() }
    }

    /**
     * 从单个 URL 获取版本信息
     *
     * @throws Exception 如果请求失败或解析失败
     */
    private fun fetchVersionFromUrl(client: OkHttpClient, url: String): RemoteVersion {
        Log.d(TAG, "Fetching version from: $url")

        val request = Request.Builder()
            .url(url)
            .addHeader("User-Agent", "BiBiKeyboard-Android")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}")
            }

            val body = response.body.string()

            val json = JSONObject(body)

            val version = normalizeVersion(
                json.optString("version", "").removePrefix("v")
            )

            if (version.isEmpty()) {
                throw Exception("Invalid version format in JSON")
            }

            val downloadUrl = json.optString(
                "download_url",
                "https://github.com/BryceWG/BiBi-Keyboard/releases"
            )

            val updateTime = json.optString("update_time", "").ifBlank { null }

            // 根据语言选择 release_notes：仅简体中文用户看中文版，其他语言看英文版
            val locale = LocaleHelper.locale(context)
            val isSimplifiedChinese = locale.language == "zh" && locale.country == "CN"
            val releaseNotes = if (isSimplifiedChinese) {
                json.optString("release_notes", "").ifBlank { null }
            } else {
                // 优先使用英文版，如果为空则回退到中文版
                json.optString("release_notes_en", "").ifBlank {
                    json.optString("release_notes", "").ifBlank { null }
                }
            }

            val importantNotice = json.optString("important_notice", "").ifBlank { null }
            val noticeLevelStr = json.optString("notice_level", "info").lowercase()
            val noticeLevel = when (noticeLevelStr) {
                "warning" -> NoticeLevel.WARNING
                "critical" -> NoticeLevel.CRITICAL
                else -> NoticeLevel.INFO
            }

            Log.d(TAG, "Successfully fetched version $version from $url")

            return RemoteVersion(
                version,
                downloadUrl,
                updateTime,
                releaseNotes,
                importantNotice,
                noticeLevel
            )
        }
    }

    /**
     * 从多个远程版本中选择版本号最高的
     */
    private fun selectLatestVersion(versions: List<RemoteVersion>): RemoteVersion = versions.maxWithOrNull { v1, v2 ->
        compareVersions(v1.version, v2.version)
    } ?: versions.first()

    /**
     * 标准化版本号字符串
     *
     * 移除前缀 v/V 和非数字点字符，仅保留主次修订号
     *
     * @param v 原始版本号
     * @return 标准化后的版本号（如 "3.1.7"）
     */
    private fun normalizeVersion(v: String): String {
        if (v.isBlank()) return ""

        return v.trim()
            .removePrefix("v")
            .removePrefix("V")
            .replace(Regex("[^0-9.]"), "")
            .trim('.')
    }

    /**
     * 比较两个版本号
     *
     * @param version1 版本号1
     * @param version2 版本号2
     * @return 大于0表示version1更新，小于0表示version2更新，等于0表示相同
     */
    private fun compareVersions(version1: String, version2: String): Int {
        val v1Parts = version1.split(".").mapNotNull { it.toIntOrNull() }
        val v2Parts = version2.split(".").mapNotNull { it.toIntOrNull() }

        val maxLength = maxOf(v1Parts.size, v2Parts.size)
        for (i in 0 until maxLength) {
            val v1 = v1Parts.getOrNull(i) ?: 0
            val v2 = v2Parts.getOrNull(i) ?: 0
            if (v1 != v2) {
                return v1.compareTo(v2)
            }
        }
        return 0
    }

    /**
     * 远程版本信息（内部数据类）
     */
    private data class RemoteVersion(
        val version: String,
        val downloadUrl: String,
        val updateTime: String?,
        val releaseNotes: String?,
        val importantNotice: String?,
        val noticeLevel: NoticeLevel
    )
}
