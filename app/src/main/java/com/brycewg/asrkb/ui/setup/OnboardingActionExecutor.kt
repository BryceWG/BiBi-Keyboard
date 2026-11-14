/**
 * 新手引导中的可复用动作执行器。
 *
 * 归属模块：ui/setup
 */
package com.brycewg.asrkb.ui.setup

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.DownloadSourceConfig
import com.brycewg.asrkb.ui.DownloadSourceOption
import com.brycewg.asrkb.ui.settings.asr.ModelDownloadService

/**
 * 负责执行引导页中的“服务选择”动作，避免在多个页面重复业务逻辑。
 */
internal class OnboardingActionExecutor(private val activity: AppCompatActivity) {

    companion object {
        private const val TAG = "OnboardingActionExecutor"
        private const val SENSEVOICE_VARIANT = "small-full"
        private const val SENSEVOICE_ZIP_URL = "https://github.com/BryceWG/BiBi-Keyboard/releases/download/models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.zip"
    }

    private val prefs: Prefs = Prefs(activity)

    fun applySiliconFlowFree(): Int {
        prefs.asrVendor = AsrVendor.SiliconFlow
        prefs.sfFreeAsrEnabled = true
        prefs.sfFreeLlmEnabled = true
        return R.string.model_guide_sf_free_ready
    }

    fun applyLocalModel(): List<DownloadSourceOption> {
        prefs.asrVendor = AsrVendor.SenseVoice
        prefs.svModelVariant = "small-int8"
        prefs.sfFreeAsrEnabled = false
        prefs.sfFreeLlmEnabled = false
        return DownloadSourceConfig.buildOptions(activity, SENSEVOICE_ZIP_URL)
    }

    fun applyCustomService() {
        prefs.sfFreeAsrEnabled = false
        prefs.sfFreeLlmEnabled = false
    }

    fun startLocalModelDownload(option: DownloadSourceOption): Int {
        try {
            ModelDownloadService.startDownload(
                activity,
                option.url,
                SENSEVOICE_VARIANT
            )
            return R.string.model_guide_downloading
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start model download", e)
            return R.string.sv_download_status_failed
        }
    }
}
