/**
 * Compose 悬浮球设置页。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.brycewg.asrkb.R
import com.brycewg.asrkb.imebridge.ImeBridgeClient
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.floating.FloatingServiceManager
import com.brycewg.asrkb.ui.floating.floatingAsrNeedsAccessibility as policyFloatingAsrNeedsAccessibility
import com.brycewg.asrkb.ui.floating.floatingInputNeedsAccessibility as policyFloatingInputNeedsAccessibility
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheet
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.components.settingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.settingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsActionController
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val FLOATING_TAG = "FloatingSettingsScreen"

private data class FloatingPackageEdits(
    val compat: String,
    val paste: String,
    val compatChanged: Boolean,
    val pasteChanged: Boolean
)

private class FloatingPackagePersistState {
    var compat: String? = null
    var paste: String? = null
}

@Composable
fun FloatingSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    actions: SettingsActionController
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember(appContext) { Prefs(appContext) }
    val serviceManager = remember(appContext) { FloatingServiceManager(appContext) }
    val imeBridgeClient = remember(appContext) { ImeBridgeClient(appContext) }
    val scope = rememberCoroutineScope()
    var uiState by remember(appContext) { mutableStateOf(FloatingSettingsUiState.placeholder) }
    var compatPackages by remember(appContext) { mutableStateOf("") }
    var pastePackages by remember(appContext) { mutableStateOf("") }
    var imeBridgeStatusText by remember(appContext) { mutableStateOf("") }
    val persistedPackages = remember(appContext) { FloatingPackagePersistState() }
    var settingsLoaded by remember(appContext) { mutableStateOf(false) }
    var pendingAsrEnable by remember { mutableStateOf(false) }
    var pendingAsrPermission by remember { mutableStateOf<FloatingPermissionRequest?>(null) }
    var pendingVolumeKeyEnable by remember { mutableStateOf(false) }
    var autoAccessibilityRequested by remember { mutableStateOf(false) }
    var choiceSheet by remember { mutableStateOf<SettingsChoiceSheetState?>(null) }
    var featureExplainerDialog by remember { mutableStateOf<SettingsFeatureExplainerDialogState?>(null) }
    var messageDialog by remember { mutableStateOf<SettingsMessageDialogState?>(null) }
    val latestCompatPackages by rememberUpdatedState(compatPackages)
    val latestPastePackages by rememberUpdatedState(pastePackages)
    val latestSettingsLoaded by rememberUpdatedState(settingsLoaded)

    fun applySnapshot(snapshot: FloatingSettingsPrefsSnapshot) {
        if (uiState != snapshot.uiState) uiState = snapshot.uiState
        if (compatPackages != snapshot.compatPackages) compatPackages = snapshot.compatPackages
        if (pastePackages != snapshot.pastePackages) pastePackages = snapshot.pastePackages
        persistedPackages.compat = snapshot.compatPackages
        persistedPackages.paste = snapshot.pastePackages
        if (!settingsLoaded) settingsLoaded = true
    }

    suspend fun loadSnapshot(): FloatingSettingsPrefsSnapshot = withContext(Dispatchers.IO) {
        FloatingSettingsPrefsSnapshot.fromPrefs(prefs)
    }

    suspend fun loadUiState(): FloatingSettingsUiState = withContext(Dispatchers.IO) {
        FloatingSettingsUiState.fromPrefs(prefs)
    }

    fun applyUiState(next: FloatingSettingsUiState) {
        if (uiState != next) uiState = next
    }

    LaunchedEffect(prefs) {
        applySnapshot(loadSnapshot())
    }

    LaunchedEffect(compatPackages, settingsLoaded) {
        if (!settingsLoaded) return@LaunchedEffect
        if (compatPackages == persistedPackages.compat) return@LaunchedEffect
        delay(300)
        withContext(Dispatchers.IO) {
            prefs.floatingWriteCompatPackages = compatPackages
        }
        persistedPackages.compat = compatPackages
    }

    LaunchedEffect(pastePackages, settingsLoaded) {
        if (!settingsLoaded) return@LaunchedEffect
        if (pastePackages == persistedPackages.paste) return@LaunchedEffect
        delay(300)
        withContext(Dispatchers.IO) {
            prefs.floatingWritePastePackages = pastePackages
        }
        persistedPackages.paste = pastePackages
    }

    fun refreshState() {
        scope.launch {
            applyUiState(loadUiState())
        }
    }

    fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:${context.packageName}".toUri()
            )
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.e(FLOATING_TAG, "Failed to request overlay permission", e)
        }
    }

    fun requestAccessibilityPermission() {
        try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (e: Throwable) {
            Log.e(FLOATING_TAG, "Failed to request accessibility permission", e)
        }
    }

    fun showFloatingMessage(messageRes: Int) {
        messageDialog = SettingsMessageDialogState(
            title = context.getString(R.string.title_floating_settings),
            message = context.getString(messageRes),
            confirmText = context.getString(android.R.string.ok)
        )
    }

    fun showOverlayPermissionMessage() {
        showFloatingMessage(R.string.toast_need_overlay_perm)
    }

    fun showAccessibilityPermissionMessage() {
        showFloatingMessage(R.string.toast_need_accessibility_perm)
    }

    fun floatingAsrNeedsAccessibilityWhenEnabled(): Boolean = !uiState.imeBridgeEnabled

    fun floatingAsrNeedsAccessibility(): Boolean = policyFloatingAsrNeedsAccessibility(
        floatingEnabled = uiState.asrEnabled,
        imeBridgeEnabled = uiState.imeBridgeEnabled
    )

    fun floatingInputNeedsAccessibility(): Boolean = policyFloatingInputNeedsAccessibility(
        floatingEnabled = uiState.asrEnabled,
        volumeKeyEnabled = uiState.volumeKeyRecordingEnabled,
        imeBridgeEnabled = uiState.imeBridgeEnabled
    )

    fun refreshImeBridgeStatus() {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                imeBridgeClient.queryStatus()
            }
            imeBridgeStatusText = formatImeBridgeStatus(
                context = context,
                result = result,
                bridgeEnabled = uiState.imeBridgeEnabled
            )
        }
    }

    LaunchedEffect(
        settingsLoaded,
        uiState.asrEnabled,
        uiState.volumeKeyRecordingEnabled,
        uiState.imeBridgeEnabled,
        uiState.onlyWhenImeVisible
    ) {
        if (!settingsLoaded || autoAccessibilityRequested) return@LaunchedEffect
        if (floatingInputNeedsAccessibility() && !isAccessibilityServiceEnabled(context)) {
            autoAccessibilityRequested = true
            requestAccessibilityPermission()
        }
    }

    LaunchedEffect(settingsLoaded, uiState.imeBridgeEnabled) {
        if (!settingsLoaded) return@LaunchedEffect
        if (shouldQueryImeBridgeStatus(uiState.imeBridgeEnabled)) {
            refreshImeBridgeStatus()
        } else {
            imeBridgeStatusText = context.getString(R.string.status_floating_ime_bridge_disabled)
        }
    }

    fun setAsrEnabled(enabled: Boolean): Boolean {
        if (enabled) {
            if (!Settings.canDrawOverlays(context)) {
                pendingAsrEnable = true
                pendingAsrPermission = FloatingPermissionRequest.Overlay
                showOverlayPermissionMessage()
                requestOverlayPermission()
                return false
            }
            if (floatingAsrNeedsAccessibilityWhenEnabled() && !isAccessibilityServiceEnabled(context)) {
                pendingAsrEnable = true
                pendingAsrPermission = FloatingPermissionRequest.Accessibility
                showAccessibilityPermissionMessage()
                requestAccessibilityPermission()
                return false
            }
        }

        pendingAsrEnable = false
        pendingAsrPermission = null
        prefs.floatingAsrEnabled = enabled
        if (enabled) {
            serviceManager.showAsrService()
        } else {
            serviceManager.hideAsrService()
        }
        refreshState()
        return true
    }

    fun setVolumeKeyRecordingEnabled(enabled: Boolean) {
        if (enabled && !isAccessibilityServiceEnabled(context)) {
            pendingVolumeKeyEnable = true
            showAccessibilityPermissionMessage()
            requestAccessibilityPermission()
            refreshState()
            return
        }
        pendingVolumeKeyEnable = false
        prefs.volumeKeyRecordingEnabled = enabled
        refreshState()
    }

    fun syncAsrToggleAfterPermissions() {
        if (pendingVolumeKeyEnable && isAccessibilityServiceEnabled(context)) {
            setVolumeKeyRecordingEnabled(true)
            return
        }

        if (pendingAsrEnable) {
            val hasOverlay = Settings.canDrawOverlays(context)
            val hasAccessibility = isAccessibilityServiceEnabled(context)
            val needsAccessibility = floatingAsrNeedsAccessibilityWhenEnabled()
            if (hasOverlay && (!needsAccessibility || hasAccessibility)) {
                setAsrEnabled(true)
                return
            }
            if (
                hasOverlay &&
                needsAccessibility &&
                !hasAccessibility &&
                pendingAsrPermission == FloatingPermissionRequest.Overlay
            ) {
                pendingAsrPermission = FloatingPermissionRequest.Accessibility
                showAccessibilityPermissionMessage()
                requestAccessibilityPermission()
            }
            return
        }

        scope.launch {
            applyUiState(loadUiState())
        }
    }

    DisposableEffect(lifecycleOwner) {
        fun pendingPackageEdits(): FloatingPackageEdits? {
            if (!latestSettingsLoaded) return null
            val compat = latestCompatPackages
            val paste = latestPastePackages
            val compatChanged = compat != persistedPackages.compat
            val pasteChanged = paste != persistedPackages.paste
            if (!compatChanged && !pasteChanged) return null
            return FloatingPackageEdits(
                compat = compat,
                paste = paste,
                compatChanged = compatChanged,
                pasteChanged = pasteChanged
            )
        }

        fun flushPackageEditsAsync() {
            val edits = pendingPackageEdits() ?: return
            scope.launch {
                withContext(Dispatchers.IO) {
                    if (edits.compatChanged) prefs.floatingWriteCompatPackages = edits.compat
                    if (edits.pasteChanged) prefs.floatingWritePastePackages = edits.paste
                }
                if (edits.compatChanged) persistedPackages.compat = edits.compat
                if (edits.pasteChanged) persistedPackages.paste = edits.paste
            }
        }

        fun flushPackageEditsNow() {
            val edits = pendingPackageEdits() ?: return
            if (edits.compatChanged) prefs.floatingWriteCompatPackages = edits.compat
            if (edits.pasteChanged) prefs.floatingWritePastePackages = edits.paste
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> syncAsrToggleAfterPermissions()
                Lifecycle.Event.ON_PAUSE -> flushPackageEditsAsync()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            flushPackageEditsNow()
        }
    }

    fun applyExplainedSwitch(
        current: Boolean,
        target: Boolean,
        titleRes: Int,
        offDescRes: Int,
        onDescRes: Int,
        preferenceKey: String,
        onConfirm: (Boolean) -> Unit
    ) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = titleRes,
            offDescRes = offDescRes,
            onDescRes = onDescRes,
            currentState = current,
            preferenceKey = preferenceKey,
            onConfirm = {
                onConfirm(target)
                refreshState()
            }
        )
    }

    fun volumeKeyModeLabel(mode: String): String = context.getString(
        when (mode) {
            Prefs.VOLUME_KEY_MODE_DOWN_TOGGLE -> R.string.option_volume_key_down_toggle
            Prefs.VOLUME_KEY_MODE_UP_START_DOWN_STOP -> R.string.option_volume_key_up_start_down_stop
            Prefs.VOLUME_KEY_MODE_DOWN_START_UP_STOP -> R.string.option_volume_key_down_start_up_stop
            else -> R.string.option_volume_key_up_toggle
        }
    )

    fun showVolumeKeyModeSheet() {
        val modes = listOf(
            Prefs.VOLUME_KEY_MODE_UP_TOGGLE,
            Prefs.VOLUME_KEY_MODE_DOWN_TOGGLE,
            Prefs.VOLUME_KEY_MODE_UP_START_DOWN_STOP,
            Prefs.VOLUME_KEY_MODE_DOWN_START_UP_STOP
        )
        val selectedIndex = modes.indexOf(uiState.volumeKeyRecordingMode).takeIf { it >= 0 } ?: 0
        choiceSheet = settingsChoiceSheetState(
            title = context.getString(R.string.label_volume_key_recording_mode),
            items = modes.map { volumeKeyModeLabel(it) },
            selectedIndex = selectedIndex
        ) { index ->
            prefs.volumeKeyRecordingMode = modes.getOrElse(index) { Prefs.VOLUME_KEY_MODE_UP_TOGGLE }
            refreshState()
        }
    }

    FloatingScaffold(uiMode = uiMode, onBack = onBack) { innerPadding, scrollModifier ->
        SettingsChoiceSheet(
            state = choiceSheet,
            uiMode = uiMode,
            onDismiss = { choiceSheet = null }
        )
        SettingsFeatureExplainerDialog(
            state = featureExplainerDialog,
            uiMode = uiMode,
            onDismiss = { featureExplainerDialog = null }
        )
        SettingsMessageDialog(
            state = messageDialog,
            uiMode = uiMode,
            onDismiss = { messageDialog = null }
        )
        SettingsLazyColumn(
            uiMode = uiMode,
            modifier = Modifier.fillMaxSize(),
            miuixScrollModifier = scrollModifier,
            contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
        ) {
            item("preview") {
                FloatingPreviewCard(
                    uiMode = uiMode,
                    enabled = uiState.asrEnabled,
                    alphaPercent = uiState.alphaPercent,
                    sizeDp = uiState.sizeDp
                )
            }

            item("basic") {
                FloatingSection(uiMode = uiMode, titleRes = R.string.section_floating_basic) {
                    val basicItemCount = if (uiState.asrEnabled) 6 else 1
                    FloatingExplainedSwitch(
                        id = "floating_asr",
                        titleRes = R.string.label_floating_asr,
                        checked = uiState.asrEnabled,
                        onToggle = { target ->
                            applyExplainedSwitch(
                                current = uiState.asrEnabled,
                                target = target,
                                titleRes = R.string.label_floating_asr,
                                offDescRes = R.string.feature_floating_asr_off_desc,
                                onDescRes = R.string.feature_floating_asr_on_desc,
                                preferenceKey = "floating_asr_explained"
                            ) { setAsrEnabled(it) }
                        },
                        index = 0,
                        count = basicItemCount
                    )
                    if (uiState.asrEnabled) {
                        FloatingExplainedSwitch(
                            id = "floating_only_when_ime_visible",
                            titleRes = R.string.label_floating_only_when_ime_visible,
                            checked = uiState.onlyWhenImeVisible,
                            onToggle = { target ->
                                applyExplainedSwitch(
                                    current = uiState.onlyWhenImeVisible,
                                    target = target,
                                    titleRes = R.string.label_floating_only_when_ime_visible,
                                    offDescRes = R.string.feature_floating_only_when_ime_visible_off_desc,
                                    onDescRes = R.string.feature_floating_only_when_ime_visible_on_desc,
                                    preferenceKey = "floating_only_when_ime_visible_explained"
                                ) { enabled ->
                                    if (enabled && !isAccessibilityServiceEnabled(context)) {
                                        showAccessibilityPermissionMessage()
                                        requestAccessibilityPermission()
                                    } else {
                                        prefs.floatingSwitcherOnlyWhenImeVisible = enabled
                                        serviceManager.refreshAsrService(uiState.asrEnabled)
                                    }
                                }
                            },
                            index = 1,
                            count = basicItemCount
                        )
                        FloatingExplainedSwitch(
                            id = "floating_hold_to_record",
                            titleRes = R.string.label_floating_hold_to_record,
                            checked = uiState.holdToRecordEnabled,
                            onToggle = { target ->
                                applyExplainedSwitch(
                                    current = uiState.holdToRecordEnabled,
                                    target = target,
                                    titleRes = R.string.label_floating_hold_to_record,
                                    offDescRes = R.string.feature_floating_hold_to_record_off_desc,
                                    onDescRes = R.string.feature_floating_hold_to_record_on_desc,
                                    preferenceKey = "floating_hold_to_record_explained"
                                ) { prefs.floatingBallHoldToRecordEnabled = it }
                            },
                            index = 2,
                            count = basicItemCount
                        )
                        FloatingExplainedSwitch(
                            id = "floating_direct_drag",
                            titleRes = R.string.label_floating_direct_drag,
                            checked = uiState.directDragEnabled,
                            onToggle = { target ->
                                applyExplainedSwitch(
                                    current = uiState.directDragEnabled,
                                    target = target,
                                    titleRes = R.string.label_floating_direct_drag,
                                    offDescRes = R.string.feature_floating_direct_drag_off_desc,
                                    onDescRes = R.string.feature_floating_direct_drag_on_desc,
                                    preferenceKey = "floating_direct_drag_explained"
                                ) { prefs.floatingBallDirectDragEnabled = it }
                            },
                            index = 3,
                            count = basicItemCount
                        )
                        FloatingSliderPreference(
                            titleRes = R.string.label_floating_alpha,
                            valueLabel = { "${it.roundFloatingToStep(5).toInt()}%" },
                            value = uiState.alphaPercent,
                            valueRange = 30f..100f,
                            step = 5,
                            uiMode = uiMode,
                            index = 4,
                            count = basicItemCount,
                            onValueChange = { value ->
                                uiState = uiState.copy(alphaPercent = value.roundFloatingToStep(5))
                            },
                            onValueChangeFinished = { value ->
                                val rounded = value.roundFloatingToStep(5)
                                prefs.floatingSwitcherAlpha = (rounded / 100f).coerceIn(0.2f, 1.0f)
                                serviceManager.refreshAsrService(uiState.asrEnabled)
                                refreshState()
                            }
                        )
                        FloatingSliderPreference(
                            titleRes = R.string.label_floating_size,
                            valueLabel = { "${it.roundFloatingToStep(4).toInt().coerceIn(28, 96)} dp" },
                            value = uiState.sizeDp.toFloat(),
                            valueRange = 28f..96f,
                            step = 4,
                            uiMode = uiMode,
                            index = 5,
                            count = basicItemCount,
                            onValueChange = { value ->
                                uiState = uiState.copy(sizeDp = value.roundFloatingToStep(4).toInt().coerceIn(28, 96))
                            },
                            onValueChangeFinished = { value ->
                                val next = value.roundFloatingToStep(4).toInt().coerceIn(28, 96)
                                prefs.floatingBallSizeDp = next
                                if (uiState.asrEnabled) {
                                    serviceManager.showAsrService()
                                }
                                refreshState()
                            }
                        )
                        FloatingResetButton(
                            uiMode = uiMode,
                            onClick = {
                                val messageRes = if (resetFloatingPosition(context, prefs, serviceManager)) {
                                    R.string.toast_floating_position_reset
                                } else {
                                    R.string.toast_debug_failed
                                }
                                showFloatingMessage(messageRes)
                            }
                        )
                    }
                }
            }

            item("volume_key") {
                FloatingSection(uiMode = uiMode, titleRes = R.string.section_volume_key_recording) {
                    val volumeItemCount = if (uiState.volumeKeyRecordingEnabled) 4 else 1
                    FloatingExplainedSwitch(
                        id = "volume_key_recording",
                        titleRes = R.string.label_volume_key_recording,
                        checked = uiState.volumeKeyRecordingEnabled,
                        onToggle = { target ->
                            applyExplainedSwitch(
                                current = uiState.volumeKeyRecordingEnabled,
                                target = target,
                                titleRes = R.string.label_volume_key_recording,
                                offDescRes = R.string.feature_volume_key_recording_off_desc,
                                onDescRes = R.string.feature_volume_key_recording_on_desc,
                                preferenceKey = "volume_key_recording_explained"
                            ) { setVolumeKeyRecordingEnabled(it) }
                        },
                        index = 0,
                        count = volumeItemCount
                    )
                    if (uiState.volumeKeyRecordingEnabled) {
                        FloatingValuePreference(
                            titleRes = R.string.label_volume_key_recording_mode,
                            value = volumeKeyModeLabel(uiState.volumeKeyRecordingMode),
                            uiMode = uiMode,
                            index = 1,
                            count = volumeItemCount,
                            onClick = {
                                showVolumeKeyModeSheet()
                            }
                        )
                        FloatingExplainedSwitch(
                            id = "volume_key_status_toast",
                            titleRes = R.string.label_volume_key_status_toast,
                            checked = uiState.volumeKeyStatusToastEnabled,
                            onToggle = { target ->
                                applyExplainedSwitch(
                                    current = uiState.volumeKeyStatusToastEnabled,
                                    target = target,
                                    titleRes = R.string.label_volume_key_status_toast,
                                    offDescRes = R.string.feature_volume_key_status_toast_off_desc,
                                    onDescRes = R.string.feature_volume_key_status_toast_on_desc,
                                    preferenceKey = "volume_key_status_toast_explained"
                                ) { prefs.volumeKeyStatusToastEnabled = it }
                            },
                            index = 2,
                            count = volumeItemCount
                        )
                        FloatingExplainedSwitch(
                            id = "volume_key_stop_on_ime_hidden",
                            titleRes = R.string.label_volume_key_stop_on_ime_hidden,
                            checked = uiState.volumeKeyStopOnImeHidden,
                            onToggle = { target ->
                                applyExplainedSwitch(
                                    current = uiState.volumeKeyStopOnImeHidden,
                                    target = target,
                                    titleRes = R.string.label_volume_key_stop_on_ime_hidden,
                                    offDescRes = R.string.feature_volume_key_stop_on_ime_hidden_off_desc,
                                    onDescRes = R.string.feature_volume_key_stop_on_ime_hidden_on_desc,
                                    preferenceKey = "volume_key_stop_on_ime_hidden_explained"
                                ) { prefs.volumeKeyStopOnImeHidden = it }
                            },
                            index = 3,
                            count = volumeItemCount
                        )
                    }
                }
            }

            item("compat") {
                FloatingSection(uiMode = uiMode, titleRes = R.string.section_floating_compat) {
                    FloatingExplainedSwitch(
                        id = "floating_ime_bridge",
                        titleRes = R.string.label_floating_ime_bridge,
                        checked = uiState.imeBridgeEnabled,
                        onToggle = { target ->
                            applyExplainedSwitch(
                                current = uiState.imeBridgeEnabled,
                                target = target,
                                titleRes = R.string.label_floating_ime_bridge,
                                offDescRes = R.string.feature_floating_ime_bridge_off_desc,
                                onDescRes = R.string.feature_floating_ime_bridge_on_desc,
                                preferenceKey = "floating_ime_bridge_explained"
                            ) {
                                prefs.floatingImeBridgeEnabled = it
                                if (shouldQueryImeBridgeStatus(it)) {
                                    refreshImeBridgeStatus()
                                } else {
                                    imeBridgeStatusText =
                                        context.getString(R.string.status_floating_ime_bridge_disabled)
                                }
                            }
                        },
                        index = 0,
                        count = if (uiState.imeBridgeEnabled) 2 else 1
                    )
                    if (uiState.imeBridgeEnabled) {
                        FloatingValuePreference(
                            titleRes = R.string.label_floating_ime_bridge_status,
                            value = if (imeBridgeStatusText.isEmpty()) {
                                stringResource(R.string.status_floating_ime_bridge_unknown)
                            } else {
                                imeBridgeStatusText
                            },
                            uiMode = uiMode,
                            index = 1,
                            count = 2,
                            onClick = {
                                refreshImeBridgeStatus()
                            }
                        )
                    }
                    FloatingSubsectionGap(compact = !uiState.imeBridgeEnabled)
                    if (Build.VERSION.SDK_INT >= 33) {
                        FloatingExplainedSwitch(
                            id = "floating_a11y_android13_api",
                            titleRes = R.string.label_floating_a11y_android13_api,
                            checked = uiState.a11yAndroid13ApiEnabled,
                            onToggle = { target ->
                                applyExplainedSwitch(
                                    current = uiState.a11yAndroid13ApiEnabled,
                                    target = target,
                                    titleRes = R.string.label_floating_a11y_android13_api,
                                    offDescRes = R.string.feature_floating_a11y_android13_api_off_desc,
                                    onDescRes = R.string.feature_floating_a11y_android13_api_on_desc,
                                    preferenceKey = "floating_a11y_android13_api_explained"
                                ) { prefs.floatingA11yAndroid13ApiEnabled = it }
                            },
                            index = 0,
                            count = 1
                        )
                        FloatingSubsectionGap()
                    }
                    FloatingExplainedSwitch(
                        id = "floating_write_compat",
                        titleRes = R.string.label_floating_write_compat,
                        checked = uiState.writeCompatEnabled,
                        onToggle = { target ->
                            applyExplainedSwitch(
                                current = uiState.writeCompatEnabled,
                                target = target,
                                titleRes = R.string.label_floating_write_compat,
                                offDescRes = R.string.feature_floating_write_compat_off_desc,
                                onDescRes = R.string.feature_floating_write_compat_on_desc,
                                preferenceKey = "floating_write_compat_explained"
                            ) { prefs.floatingWriteTextCompatEnabled = it }
                        },
                        index = 0,
                        count = 2
                    )
                    FloatingPackagesField(
                        value = compatPackages,
                        onValueChange = {
                            compatPackages = it
                        },
                        label = stringResource(R.string.label_floating_write_compat_pkgs),
                        helper = stringResource(R.string.hint_floating_write_compat_pkgs),
                        uiMode = uiMode,
                        index = 1,
                        count = 2
                    )
                    FloatingSubsectionGap()
                    FloatingExplainedSwitch(
                        id = "floating_write_paste",
                        titleRes = R.string.label_floating_write_paste,
                        checked = uiState.writePasteEnabled,
                        onToggle = { target ->
                            applyExplainedSwitch(
                                current = uiState.writePasteEnabled,
                                target = target,
                                titleRes = R.string.label_floating_write_paste,
                                offDescRes = R.string.feature_floating_write_paste_off_desc,
                                onDescRes = R.string.feature_floating_write_paste_on_desc,
                                preferenceKey = "floating_write_paste_explained"
                            ) { prefs.floatingWriteTextPasteEnabled = it }
                        },
                        index = 0,
                        count = 2
                    )
                    FloatingPackagesField(
                        value = pastePackages,
                        onValueChange = {
                            pastePackages = it
                        },
                        label = stringResource(R.string.label_floating_write_paste_pkgs),
                        helper = stringResource(R.string.hint_floating_write_paste_pkgs),
                        uiMode = uiMode,
                        index = 1,
                        count = 2
                    )
                }
            }
        }
    }
}
