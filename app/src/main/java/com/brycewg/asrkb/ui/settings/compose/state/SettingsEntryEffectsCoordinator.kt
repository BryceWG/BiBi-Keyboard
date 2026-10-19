/**
 * 设置页入口副作用协调器。
 *
 * 负责首次引导与无障碍服务启用反馈。
 */
package com.brycewg.asrkb.ui.settings.compose.state

import android.content.Intent
import android.util.Log
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BaseActivity
import com.brycewg.asrkb.ui.setup.OnboardingGuideActivity
import com.brycewg.asrkb.ui.setup.isAccessibilityServiceEnabled as isAccessibilityServiceListed

/**
 * 汇总进入设置页时需要触发的非 Compose UI 副作用。
 */
class SettingsEntryEffectsCoordinator(
    private val activity: BaseActivity,
    private val showSystemMessage: (Int, Int) -> Unit
) {
    private var wasAccessibilityEnabled = isAccessibilityServiceEnabled()

    fun onResume() {
        checkAccessibilityServiceJustEnabled()
        maybeAutoShowOnboardingGuideOnFirstOpen()
    }

    private fun openOnboardingGuide() {
        activity.startActivity(Intent(activity, OnboardingGuideActivity::class.java))
    }

    private fun maybeAutoShowOnboardingGuideOnFirstOpen() {
        try {
            val prefs = Prefs(activity)
            if (!prefs.hasShownOnboardingGuideV2Once) {
                // 进入即标记，避免因中途返回导致重复弹出。
                prefs.hasShownOnboardingGuideV2Once = true
                openOnboardingGuide()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to maybe auto show onboarding guide", t)
        }
    }

    private fun checkAccessibilityServiceJustEnabled() {
        val isNowEnabled = isAccessibilityServiceEnabled()

        if (!wasAccessibilityEnabled && isNowEnabled) {
            Log.d(TAG, "Accessibility service just enabled")
            showSystemMessage(R.string.settings_title, R.string.toast_accessibility_enabled)
        }

        wasAccessibilityEnabled = isNowEnabled
    }

    private fun isAccessibilityServiceEnabled(): Boolean = isAccessibilityServiceListed(activity)

    private companion object {
        private const val TAG = "SettingsEntryEffects"
    }
}
