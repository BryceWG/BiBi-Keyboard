/**
 * 新手引导主页面。
 *
 * 归属模块：ui/setup
 */
package com.brycewg.asrkb.ui.setup

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.net.toUri
import com.brycewg.asrkb.R
import com.brycewg.asrkb.analytics.AnalyticsManager
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BaseActivity
import com.brycewg.asrkb.ui.DownloadSourceOption
import com.brycewg.asrkb.ui.SettingsActivity
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.core.BibiSettingsRoute
import com.brycewg.asrkb.ui.settings.compose.core.BibiSettingsTheme
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.setup.compose.OnboardingAsrChoice
import com.brycewg.asrkb.ui.setup.compose.OnboardingDialogHost
import com.brycewg.asrkb.ui.setup.compose.OnboardingDialogState
import com.brycewg.asrkb.ui.setup.compose.OnboardingGuideScreen
import com.brycewg.asrkb.ui.setup.compose.OnboardingPermissionGroup
import com.brycewg.asrkb.ui.setup.compose.OnboardingPermissionItem

/**
 * 重构后的“查看使用指南”入口：整合权限、服务选择、隐私和相关信息。
 */
class OnboardingGuideActivity : BaseActivity() {

    companion object {
        private const val TAG = "OnboardingGuideActivity"
    }

    private lateinit var prefs: Prefs
    private lateinit var actionExecutor: OnboardingActionExecutor
    private var unregisterSetupObserver: (() -> Unit)? = null

    private var refreshKeyState = mutableIntStateOf(0)
    private var asrChoiceState = mutableStateOf(OnboardingAsrChoice.SiliconFlowFree)
    private var dataCollectionEnabledState = mutableStateOf(false)
    private var dialogState = mutableStateOf<OnboardingDialogState>(OnboardingDialogState.None)
    private var messageDialogState = mutableStateOf<SettingsMessageDialogState?>(null)
    private var messageDismissAction: (() -> Unit)? = null
    private var isCompletingOnboarding: Boolean = false

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionPage()
        }
    private val requestMicrophonePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissionPage()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = Prefs(this)
        actionExecutor = OnboardingActionExecutor(this)
        asrChoiceState.value = deriveInitialAsrChoice()
        dataCollectionEnabledState.value = prefs.dataCollectionEnabled

        markOnboardingAsShown()
        unregisterSetupObserver = BasicSetupStatus.observeSystemChanges(this) {
            if (!isFinishing && !isDestroyed) refreshPermissionPage()
        }
        setContent {
            val refreshKey by refreshKeyState
            val asrChoice by asrChoiceState
            val dataCollectionEnabled by dataCollectionEnabledState
            val onboardingDialog by dialogState
            val messageDialog by messageDialogState
            val uiMode = BibiUiMode.fromId(prefs.settingsUiMode)

            BibiSettingsTheme(
                uiMode = uiMode,
                themeMode = prefs.settingsThemeMode
            ) {
                OnboardingGuideScreen(
                    uiMode = uiMode,
                    refreshKey = refreshKey,
                    permissionGroups = buildPermissionGroups(),
                    asrChoice = asrChoice,
                    dataCollectionEnabled = dataCollectionEnabled,
                    onBack = ::finishOnboarding,
                    onSkip = ::finishOnboarding,
                    onNextFromLastPage = ::completeOnboardingWithSelectedAsrChoice,
                    onAsrChoiceChange = { asrChoiceState.value = it },
                    onDataCollectionChange = ::updateDataCollectionEnabled,
                    onOpenProject = { openUrl(getString(R.string.about_project_url)) },
                    onOpenWebsite = { openUrl(getString(R.string.about_website_url)) },
                    onOpenDocs = { openUrl(getString(R.string.about_docs_url)) },
                    onOpenPro = ::showProPromo
                )
                OnboardingDialogHost(
                    state = onboardingDialog,
                    uiMode = uiMode,
                    onDismiss = ::dismissOnboardingDialog,
                    onConfirmOnlineGuide = ::confirmOnlineGuide,
                    onSelectDownloadSource = ::selectLocalModelDownloadSource
                )
                SettingsMessageDialog(
                    state = messageDialog,
                    uiMode = uiMode,
                    onDismiss = ::dismissOnboardingMessage
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionPage()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 输入法选择器、运行时权限框只抢走焦点，不一定让本页走 ON_RESUME。
        if (hasFocus && !isFinishing && !isDestroyed) refreshPermissionPage()
    }

    override fun onDestroy() {
        unregisterSetupObserver?.invoke()
        unregisterSetupObserver = null
        super.onDestroy()
    }

    private fun buildPermissionGroups(): List<OnboardingPermissionGroup> {
        val status = BasicSetupStatus.read(this, prefs)
        val requiredItems = buildList {
            add(
                OnboardingPermissionItem(
                    titleRes = R.string.onboarding_permission_ime_enable_title,
                    descriptionRes = R.string.onboarding_permission_ime_enable_desc,
                    granted = status.imeEnabled,
                    onRequest = ::requestEnableIme
                )
            )
            add(
                OnboardingPermissionItem(
                    titleRes = R.string.onboarding_permission_ime_switch_title,
                    descriptionRes = R.string.onboarding_permission_ime_switch_desc,
                    granted = status.imeCurrent,
                    onRequest = ::requestSwitchIme
                )
            )
            add(
                OnboardingPermissionItem(
                    titleRes = R.string.onboarding_permission_mic_title,
                    descriptionRes = R.string.onboarding_permission_mic_desc,
                    granted = status.microphoneGranted,
                    onRequest = ::requestMicrophonePermission
                )
            )
            if (status.overlayRequired) {
                add(
                    OnboardingPermissionItem(
                        titleRes = R.string.onboarding_permission_overlay_title,
                        descriptionRes = R.string.onboarding_permission_overlay_desc,
                        granted = status.overlayGranted,
                        onRequest = ::requestOverlayPermission
                    )
                )
            }
            if (status.accessibilityRequired) {
                add(
                    OnboardingPermissionItem(
                        titleRes = R.string.onboarding_permission_a11y_title,
                        descriptionRes = R.string.onboarding_permission_a11y_desc,
                        granted = status.accessibilityGranted,
                        onRequest = ::requestAccessibilityPermission
                    )
                )
            }
        }
        val optionalItems = buildList {
            if (!status.overlayRequired) {
                add(
                    OnboardingPermissionItem(
                        titleRes = R.string.onboarding_permission_overlay_title,
                        descriptionRes = R.string.onboarding_permission_overlay_desc,
                        granted = status.overlayGranted,
                        onRequest = ::requestOverlayPermission
                    )
                )
            }
            if (!status.accessibilityRequired) {
                add(
                    OnboardingPermissionItem(
                        titleRes = R.string.onboarding_permission_a11y_title,
                        descriptionRes = R.string.onboarding_permission_a11y_desc,
                        granted = status.accessibilityGranted,
                        onRequest = ::requestAccessibilityPermission
                    )
                )
            }
            add(
                OnboardingPermissionItem(
                    titleRes = R.string.onboarding_permission_notif_title,
                    descriptionRes = R.string.onboarding_permission_notif_desc,
                    granted = status.notificationGranted,
                    onRequest = ::requestNotificationPermission
                )
            )
        }
        return listOf(
            OnboardingPermissionGroup(
                titleRes = R.string.onboarding_permissions_required_title,
                descriptionRes = R.string.onboarding_permissions_required_desc,
                items = requiredItems
            ),
            OnboardingPermissionGroup(
                titleRes = R.string.onboarding_permissions_optional_title,
                descriptionRes = R.string.onboarding_permissions_optional_desc,
                items = optionalItems
            )
        )
    }

    private fun refreshPermissionPage() {
        refreshKeyState.intValue += 1
    }

    private fun updateDataCollectionEnabled(enabled: Boolean) {
        dataCollectionEnabledState.value = enabled
        prefs.dataCollectionConsentShown = true
        prefs.dataCollectionEnabled = enabled

        if (enabled) {
            try {
                AnalyticsManager.init(this)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to init analytics after enabling data collection", t)
            }
        }

        try {
            AnalyticsManager.sendConsentChoice(this, enabled)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to send consent choice from onboarding", t)
        }
    }

    private fun requestMicrophonePermission() {
        try {
            requestMicrophonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request microphone permission", e)
        }
    }

    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()
            )
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open overlay settings", e)
        }
    }

    private fun requestAccessibilityPermission() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open accessibility settings", e)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            showOnboardingMessage(R.string.onboarding_permission_status_granted)
            return
        }
        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun requestEnableIme() {
        try {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open input method settings", e)
        }
    }

    private fun requestSwitchIme() {
        try {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show input method picker", e)
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open url: $url", e)
            showOnboardingMessage(R.string.error_open_browser)
        }
    }

    private fun showOnboardingMessage(
        messageRes: Int,
        onDismiss: (() -> Unit)? = null
    ) {
        messageDismissAction = onDismiss
        messageDialogState.value = SettingsMessageDialogState(
            title = getString(R.string.onboarding_title),
            message = getString(messageRes),
            confirmText = getString(android.R.string.ok)
        )
    }

    private fun dismissOnboardingMessage() {
        val dismissAction = messageDismissAction
        messageDismissAction = null
        messageDialogState.value = null
        dismissAction?.invoke()
    }

    private fun showProPromo() {
        try {
            startActivity(
                Intent(this, SettingsActivity::class.java).putExtra(
                    SettingsActivity.EXTRA_INITIAL_ROUTE,
                    BibiSettingsRoute.Paywall.id
                )
            )
            prefs.proPromoShown = true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to open Pro paywall", t)
        }
    }

    private fun finishOnboarding() {
        markOnboardingAsShown()
        finish()
    }

    private fun completeOnboardingWithSelectedAsrChoice() {
        if (isCompletingOnboarding) return
        isCompletingOnboarding = true
        when (asrChoiceState.value) {
            OnboardingAsrChoice.SiliconFlowFree -> {
                showOnboardingMessage(
                    actionExecutor.applySiliconFlowFree(),
                    onDismiss = ::finishOnboarding
                )
            }

            OnboardingAsrChoice.LocalModel -> {
                dialogState.value = OnboardingDialogState.DownloadSources(actionExecutor.applyLocalModel())
            }

            OnboardingAsrChoice.OnlineCustom -> {
                actionExecutor.applyOnlineCustom()
                dialogState.value = OnboardingDialogState.OnlineGuide
            }
        }
    }

    private fun dismissOnboardingDialog() {
        if (dialogState.value == OnboardingDialogState.None) return
        dialogState.value = OnboardingDialogState.None
        finishOnboarding()
    }

    private fun confirmOnlineGuide() {
        val messageRes = actionExecutor.openOnlineModelConfigGuide()
        dialogState.value = OnboardingDialogState.None
        if (messageRes == null) {
            finishOnboarding()
        } else {
            showOnboardingMessage(messageRes, onDismiss = ::finishOnboarding)
        }
    }

    private fun selectLocalModelDownloadSource(option: DownloadSourceOption) {
        val messageRes = actionExecutor.startLocalModelDownload(option)
        dialogState.value = OnboardingDialogState.None
        showOnboardingMessage(messageRes, onDismiss = ::finishOnboarding)
    }

    private fun markOnboardingAsShown() {
        if (!prefs.hasShownOnboardingGuideV2Once) {
            prefs.hasShownOnboardingGuideV2Once = true
        }
    }

    private fun deriveInitialAsrChoice(): OnboardingAsrChoice = when {
        prefs.sfFreeAsrEnabled -> OnboardingAsrChoice.SiliconFlowFree
        prefs.asrVendor == AsrVendor.SenseVoice -> OnboardingAsrChoice.LocalModel
        else -> OnboardingAsrChoice.OnlineCustom
    }
}
