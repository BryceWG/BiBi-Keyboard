package com.brycewg.asrkb

import android.app.Application
import android.app.Activity
import android.app.ActivityManager
import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import android.util.Log
import com.google.android.material.color.DynamicColors
import com.brycewg.asrkb.store.Prefs
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.brycewg.asrkb.ui.floating.FloatingAsrService
import com.brycewg.asrkb.ui.floating.FloatingKeepAliveService
import com.brycewg.asrkb.asr.VadDetector
import com.brycewg.asrkb.analytics.AnalyticsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 在可用时为所有Activity启用Material You动态颜色 (Android 12+)
        DynamicColors.applyToActivitiesIfAvailable(this)
        // 应用应用内语言设置（空表示跟随系统）
        try {
            val prefs = Prefs(this)
            val tag = prefs.appLanguageTag
            // 兼容旧版本存储的 zh-CN，统一归一化为 zh
            val normalized = when (tag.lowercase()) {
                "zh", "zh-cn", "zh-hans" -> "zh-CN"
                else -> tag
            }
            if (normalized != tag) prefs.appLanguageTag = normalized
            val locales = if (normalized.isBlank()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(normalized)
            AppCompatDelegate.setApplicationLocales(locales)
        } catch (t: Throwable) {
            Log.w("App", "Failed to apply app locales", t)
        }

        // 初始化匿名统计（不影响正常识别流程）
        try {
            AnalyticsManager.init(this)
        } catch (t: Throwable) {
            Log.w("App", "Analytics init failed", t)
        }

        // 若用户在设置中启用了悬浮球且已授予悬浮窗权限，则启动悬浮球服务
        try {
            val prefs = Prefs(this)
            val canOverlay = Settings.canDrawOverlays(this)

            // 启动语音识别悬浮球
            if (prefs.floatingAsrEnabled && canOverlay) {
                val intent = Intent(this, FloatingAsrService::class.java).apply {
                    action = FloatingAsrService.ACTION_SHOW
                }
                startService(intent)
            }

            if (prefs.floatingKeepAliveEnabled) {
                FloatingKeepAliveService.start(this)
            }
        } catch (t: Throwable) {
            Log.w("App", "Failed to start overlay services", t)
        }

        // 预加载 VAD：仅当已开启“静音自动停止”时，避免首次录音时的模型加载延迟
        try {
            val prefs = Prefs(this)
            if (prefs.autoStopOnSilenceEnabled) {
                VadDetector.preload(this, 16000, prefs.autoStopSilenceSensitivity)
            }
        } catch (t: Throwable) {
            Log.w("App", "Failed to preload VAD", t)
        }

        // 清理已移除的 Zipformer 模型文件（仅执行一次）
        try {
            val prefs = Prefs(this)
            if (!prefs.zipformerCleanupDone) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    val externalBase = getExternalFilesDir(null)
                    val bases = mutableListOf<File>()
                    if (externalBase != null) bases.add(externalBase)
                    if (externalBase == null || externalBase != filesDir) bases.add(filesDir)
                    bases.forEach { base ->
                        val zipformerDir = File(base, "zipformer")
                        if (zipformerDir.exists()) {
                            val deleted = zipformerDir.deleteRecursively()
                            if (!deleted) {
                                Log.w("App", "Failed to delete zipformer dir: ${zipformerDir.path}")
                            }
                        }
                    }
                    prefs.zipformerCleanupDone = true
                }
            }
        } catch (t: Throwable) {
            Log.w("App", "Zipformer cleanup init failed", t)
        }

        // 根据设置将任务从最近任务中排除/恢复
        try {
            registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    applyExcludeFromRecents(activity)
                }
                override fun onActivityResumed(activity: Activity) {
                    applyExcludeFromRecents(activity)
                }
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        } catch (t: Throwable) {
            Log.w("App", "Failed to register lifecycle callbacks", t)
        }
    }

    private fun applyExcludeFromRecents(activity: Activity) {
        try {
            val enabled = Prefs(activity).hideRecentTaskCard
            val am = activity.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.appTasks?.forEach { it.setExcludeFromRecents(enabled) }
        } catch (t: Throwable) {
            Log.w("App", "Failed to apply exclude from recents", t)
        }
    }
}
