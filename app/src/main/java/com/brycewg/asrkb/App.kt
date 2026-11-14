/**
 * 应用入口：初始化主题、多语言、统计与部分后台能力。
 *
 * 归属模块：根目录与通用
 */
package com.brycewg.asrkb

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.brycewg.asrkb.analytics.AnalyticsManager
import com.brycewg.asrkb.asr.OfflineSpeechDenoiserManager
import com.brycewg.asrkb.asr.VadDetector
import com.brycewg.asrkb.store.ApiLogStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.floating.KeepAliveCoordinator
import com.google.android.material.color.DynamicColors
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLogManager.initialize(this)
        ApiLogStore.initialize(this)
        DebugLogManager.installUncaughtExceptionHandler(this)
        DebugLogManager.inspectHistoricalProcessExit(this)
        DebugLogManager.updateProcessStateSummary("phase=app_create")
        DebugLogManager.logBase(
            this,
            "app",
            "on_create",
            data = mapOf("buildType" to BuildConfig.BUILD_TYPE)
        )
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
            val locales = if (normalized.isBlank()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(
                    normalized
                )
            }
            AppCompatDelegate.setApplicationLocales(locales)
        } catch (t: Throwable) {
            Log.w("App", "Failed to apply app locales", t)
            DebugLogManager.logWarning(this, "app", "locale_apply_failed", t)
        }

        // 初始化匿名统计（不影响正常识别流程）
        try {
            AnalyticsManager.init(this)
        } catch (t: Throwable) {
            Log.w("App", "Analytics init failed", t)
            DebugLogManager.logWarning(this, "analytics", "init_failed", t)
        }

        KeepAliveCoordinator.init(this)

        // 预加载 VAD：仅当已开启“静音自动停止”时，避免首次录音时的模型加载延迟
        try {
            val prefs = Prefs(this)
            if (prefs.autoStopOnSilenceEnabled) {
                VadDetector.preload(this, 16000, prefs.autoStopSilenceSensitivity)
                DebugLogManager.logBase(this, "asr", "vad_preload_started")
            }
        } catch (t: Throwable) {
            Log.w("App", "Failed to preload VAD", t)
            DebugLogManager.logWarning(this, "asr", "vad_preload_failed", t)
        }

        // 预加载离线降噪模型：首次降噪会同步加载 JNI 与 ONNX 会话，
        // 若落在采集/编码协程内会挤占单帧预算并丢掉句首音频，因此提前在 IO 线程预热。
        try {
            val prefs = Prefs(this)
            if (prefs.offlineDenoiseEnabled) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    val ready = OfflineSpeechDenoiserManager.preload(this@App, prefs)
                    DebugLogManager.log(
                        "asr",
                        "denoiser_preload_done",
                        data = mapOf("ready" to ready)
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w("App", "Failed to preload offline denoiser", t)
            DebugLogManager.logWarning(this, "asr", "denoiser_preload_failed", t)
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
                                DebugLogManager.logWarning(
                                    this@App,
                                    "storage",
                                    "zipformer_cleanup_partial",
                                    data = mapOf("path" to zipformerDir.path.takeLast(48))
                                )
                            }
                        }
                    }
                    prefs.zipformerCleanupDone = true
                    DebugLogManager.logBase(this@App, "storage", "zipformer_cleanup_done")
                }
            }
        } catch (t: Throwable) {
            Log.w("App", "Zipformer cleanup init failed", t)
            DebugLogManager.logWarning(this, "storage", "zipformer_cleanup_init_failed", t)
        }

        // 根据设置将任务从最近任务中排除/恢复
        try {
            registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    applyExcludeFromRecents(activity)
                }
                override fun onActivityResumed(activity: Activity) {
                    applyExcludeFromRecents(activity)
                    DebugLogManager.updateProcessStateSummary(
                        "phase=activity_resumed;activity=${activity.javaClass.simpleName.take(24)}"
                    )
                }
                override fun onActivityStarted(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
        } catch (t: Throwable) {
            Log.w("App", "Failed to register lifecycle callbacks", t)
            DebugLogManager.logWarning(this, "app", "lifecycle_callbacks_register_failed", t)
        }
    }

    private fun applyExcludeFromRecents(activity: Activity) {
        try {
            val enabled = Prefs(activity).hideRecentTaskCard
            val am = activity.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.appTasks?.forEach { it.setExcludeFromRecents(enabled) }
        } catch (t: Throwable) {
            Log.w("App", "Failed to apply exclude from recents", t)
            DebugLogManager.logWarning(activity, "app", "exclude_from_recents_apply_failed", t)
        }
    }
}
