/**
 * Compose 输入设置页。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceGroup
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceItem
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheet
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsActionController

@Composable
fun InputSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    actions: SettingsActionController
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    var uiState by remember(context) { mutableStateOf(InputSettingsUiState.fromPrefs(context, prefs)) }
    var pendingHeadsetPermission by remember { mutableStateOf(false) }
    var choiceSheet by remember { mutableStateOf<SettingsChoiceSheetState?>(null) }
    var messageDialog by remember { mutableStateOf<SettingsMessageDialogState?>(null) }
    var featureExplainerDialog by remember { mutableStateOf<SettingsFeatureExplainerDialogState?>(null) }

    fun showInputMessage(message: String) {
        messageDialog = SettingsMessageDialogState(
            title = context.getString(R.string.title_input_settings),
            message = message,
            confirmText = context.getString(android.R.string.ok)
        )
    }

    fun showInputMessage(messageRes: Int) {
        showInputMessage(context.getString(messageRes))
    }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (pendingHeadsetPermission) {
            pendingHeadsetPermission = false
            if (granted) {
                prefs.headsetMicPriorityEnabled = true
                uiState = uiState.copy(headsetMicPriority = true)
            } else {
                prefs.headsetMicPriorityEnabled = false
                uiState = uiState.copy(headsetMicPriority = false)
                showInputMessage(R.string.toast_bt_connect_permission_denied)
            }
        }
    }

    fun refreshState() {
        uiState = InputSettingsUiState.fromPrefs(context, prefs)
    }

    fun showExternalAidlReleaseChooser() {
        val items = listOf(
            context.getString(R.string.external_aidl_guide_release_fcitx),
            context.getString(R.string.external_aidl_guide_release_trime)
        )
        val urls = listOf(
            "https://github.com/BryceWG/fcitx5-android-lexi-keyboard/releases",
            "https://github.com/BryceWG/trime-bibi-keyboard/releases"
        )
        choiceSheet = SettingsChoiceSheetState(
            title = context.getString(R.string.external_aidl_guide_choose_release_title),
            groups = listOf(
                SettingsChoiceGroup(
                    label = "",
                    items = items.mapIndexed { index, label ->
                        SettingsChoiceItem(title = label, originalIndex = index)
                    }
                )
            ),
            selectedIndex = -1,
            onSelected = { index ->
                urls.getOrNull(index)?.let { url ->
                    if (!context.openExternalAidlReleaseUrl(url)) {
                        showInputMessage(R.string.error_open_browser)
                    }
                }
            }
        )
    }

    fun showExternalAidlGuideDialog() {
        messageDialog = SettingsMessageDialogState(
            title = context.getString(R.string.external_aidl_guide_title),
            message = context.getString(R.string.external_aidl_guide_message),
            confirmText = context.getString(R.string.external_aidl_guide_btn_close),
            dismissText = context.getString(R.string.external_aidl_guide_btn_open_release),
            onDismissAction = { showExternalAidlReleaseChooser() }
        )
    }

    fun applyExplainedSwitch(
        current: Boolean,
        target: Boolean,
        titleRes: Int,
        offDescRes: Int,
        onDescRes: Int,
        preferenceKey: String,
        preCheck: ((Boolean) -> Boolean)? = null,
        onChanged: ((Boolean) -> Unit)? = null,
        write: (Boolean) -> Unit
    ) {
        featureExplainerDialog = inputExplainedSwitchDialogState(
            context = context,
            current = current,
            target = target,
            titleRes = titleRes,
            offDescRes = offDescRes,
            onDescRes = onDescRes,
            preferenceKey = preferenceKey,
            preCheck = preCheck,
            onChanged = onChanged,
            write = write,
            onRefreshState = ::refreshState
        )
    }

    InputScaffold(uiMode = uiMode, onBack = onBack) { innerPadding, scrollModifier ->
        SettingsChoiceSheet(
            state = choiceSheet,
            uiMode = uiMode,
            onDismiss = { choiceSheet = null }
        )
        SettingsMessageDialog(
            state = messageDialog,
            uiMode = uiMode,
            onDismiss = { messageDialog = null }
        )
        SettingsFeatureExplainerDialog(
            state = featureExplainerDialog,
            uiMode = uiMode,
            onDismiss = { featureExplainerDialog = null }
        )
        InputSettingsRouteContent(
            uiMode = uiMode,
            innerPadding = innerPadding,
            scrollModifier = scrollModifier,
            prefs = prefs,
            uiState = uiState,
            actions = actions,
            onUiStateChange = { uiState = it },
            onPendingHeadsetPermissionChange = { pendingHeadsetPermission = it },
            onRequestBluetoothConnectPermission = {
                bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            },
            onRefreshState = ::refreshState,
            onShowExternalAidlGuideDialog = ::showExternalAidlGuideDialog,
            onApplyExplainedSwitch = { current, target, titleRes, offDescRes, onDescRes, preferenceKey, preCheck, onChanged, write ->
                applyExplainedSwitch(
                    current = current,
                    target = target,
                    titleRes = titleRes,
                    offDescRes = offDescRes,
                    onDescRes = onDescRes,
                    preferenceKey = preferenceKey,
                    preCheck = preCheck,
                    onChanged = onChanged,
                    write = write
                )
            }
        )
    }
}

internal fun InputSettingsUiState.withHapticFeedbackLevel(
    context: Context,
    level: Int
): InputSettingsUiState = copy(
    hapticFeedbackLevel = level,
    hapticFeedbackLabel = context.hapticFeedbackStrengthLabel(level)
)

internal data class InputSettingsUiState(
    val trimTrailingPunct: Boolean,
    val trimTrailingPunctThreshold: Int,
    val micTapToggle: Boolean,
    val autoStartRecordingOnShow: Boolean,
    val autoEnterAfterAsr: Boolean,
    val autoSwitchImeAfterAsr: Boolean,
    val continuousCapture: Boolean,
    val keepScreenOnWhileRecording: Boolean,
    val fcitx5ReturnOnSwitcher: Boolean,
    val returnPrevImeOnHide: Boolean,
    val hideRecentTasks: Boolean,
    val duckMediaOnRecord: Boolean,
    val offlineDenoise: Boolean,
    val autoCancelEmptyAudioInput: Boolean,
    val autoFilterSilentAudioSegments: Boolean,
    val uploadAudioCompression: Boolean,
    val headsetMicPriority: Boolean,
    val externalAidl: Boolean,
    val keyboardHeightTier: Int,
    val imeTabletFloatingKeyboard: Boolean,
    val hapticFeedbackLevel: Int,
    val hapticFeedbackLabel: String,
    val keyboardBottomPaddingDp: Int,
    val waveformSensitivity: Int,
    val languageLabel: String,
    val imeSwitchTargetLabel: String,
    val extensionButtonsLabel: String
) {
    companion object {
        fun fromPrefs(context: Context, prefs: Prefs): InputSettingsUiState = InputSettingsUiState(
            trimTrailingPunct = prefs.trimFinalTrailingPunct,
            trimTrailingPunctThreshold = prefs.trimFinalTrailingPunctThreshold,
            micTapToggle = prefs.micTapToggleEnabled,
            autoStartRecordingOnShow = prefs.autoStartRecordingOnShow,
            autoEnterAfterAsr = prefs.autoEnterAfterAsrEnabled,
            autoSwitchImeAfterAsr = prefs.autoSwitchImeAfterAsrEnabled,
            continuousCapture = prefs.continuousCaptureEnabled,
            keepScreenOnWhileRecording = prefs.keepScreenOnWhileRecording,
            fcitx5ReturnOnSwitcher = prefs.fcitx5ReturnOnImeSwitch,
            returnPrevImeOnHide = prefs.returnPrevImeOnHide,
            hideRecentTasks = prefs.hideRecentTaskCard,
            duckMediaOnRecord = prefs.duckMediaOnRecordEnabled,
            offlineDenoise = prefs.offlineDenoiseEnabled,
            autoCancelEmptyAudioInput = prefs.autoCancelEmptyAudioInputEnabled,
            autoFilterSilentAudioSegments = prefs.autoFilterSilentAudioSegmentsEnabled,
            uploadAudioCompression = prefs.uploadAudioCompressionEnabled,
            headsetMicPriority = prefs.headsetMicPriorityEnabled,
            externalAidl = prefs.externalAidlEnabled,
            keyboardHeightTier = prefs.keyboardHeightTier,
            imeTabletFloatingKeyboard = prefs.imeTabletFloatingKeyboardEnabled,
            hapticFeedbackLevel = prefs.hapticFeedbackLevel,
            hapticFeedbackLabel = context.hapticFeedbackStrengthLabel(prefs.hapticFeedbackLevel),
            keyboardBottomPaddingDp = prefs.keyboardBottomPaddingDp,
            waveformSensitivity = prefs.waveformSensitivity,
            languageLabel = context.languageLabel(prefs.appLanguageTag),
            imeSwitchTargetLabel = context.imeSwitchTargetLabel(prefs),
            extensionButtonsLabel = context.extensionButtonsLabel(prefs)
        )
    }
}
