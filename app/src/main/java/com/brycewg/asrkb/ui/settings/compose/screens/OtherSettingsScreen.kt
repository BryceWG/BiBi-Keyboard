/**
 * Compose 其他设置页。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.brycewg.asrkb.R
import com.brycewg.asrkb.clipboard.ClipboardSyncReceiveMode
import com.brycewg.asrkb.clipboard.ClipboardSyncRuntimeService
import com.brycewg.asrkb.clipboard.isSyncClipboardDownloadDirectory
import com.brycewg.asrkb.ime.AsrKeyboardService
import com.brycewg.asrkb.store.AsrHistoryAudioStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.floating.FloatingServiceManager
import com.brycewg.asrkb.ui.floating.KeepAliveNotificationClick
import com.brycewg.asrkb.ui.floating.PrivilegedAccessManager
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheet
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.components.settingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.settingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsActionController
import com.brycewg.asrkb.ui.settings.other.OtherSettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

private const val OTHER_TAG = "OtherSettingsScreen"

@Composable
fun OtherSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    actions: SettingsActionController
) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    val serviceManager = remember(context) { FloatingServiceManager(context) }
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val viewModel: OtherSettingsViewModel = viewModel(
        factory = remember(prefs, context) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = OtherSettingsViewModel(
                    prefs = prefs,
                    onSyncClipboardChanged = {
                        notifySyncClipboardConfigChanged(context, prefs)
                    }
                ) as T
            }
        }
    )
    val speechState by viewModel.speechPresetsState.collectAsStateWithLifecycle()
    val syncState by viewModel.syncClipboardState.collectAsStateWithLifecycle()
    var uiState by remember(context) { mutableStateOf(OtherSettingsUiState.fromPrefs(prefs)) }
    var downloadMirrors by remember(context) { mutableStateOf(prefs.customDownloadMirrors) }
    var editingDownloadMirror by rememberSaveable { mutableStateOf<String?>(null) }
    var punct1 by remember(context) { mutableStateOf(prefs.punct1) }
    var punct2 by remember(context) { mutableStateOf(prefs.punct2) }
    var punct3 by remember(context) { mutableStateOf(prefs.punct3) }
    var punct4 by remember(context) { mutableStateOf(prefs.punct4) }
    var messageDialog by remember { mutableStateOf<SettingsMessageDialogState?>(null) }
    var focusNameAfterAdd by remember { mutableStateOf(false) }
    var pendingPrivilegedEnable by remember { mutableStateOf(false) }
    var choiceSheet by remember { mutableStateOf<SettingsChoiceSheetState?>(null) }
    var featureExplainerDialog by remember { mutableStateOf<SettingsFeatureExplainerDialogState?>(null) }
    val latestPunctuation by rememberUpdatedState(PunctuationFields(punct1, punct2, punct3, punct4))

    LaunchedEffect(punct1, punct2, punct3, punct4) {
        delay(350L)
        persistPunctuationIfChanged(prefs, PunctuationFields(punct1, punct2, punct3, punct4))
    }

    DisposableEffect(Unit) {
        onDispose {
            persistPunctuationIfChanged(prefs, latestPunctuation)
        }
    }

    fun refreshState() {
        uiState = OtherSettingsUiState.fromPrefs(prefs)
    }

    fun showOtherMessage(messageRes: Int) {
        messageDialog = SettingsMessageDialogState(
            title = context.getString(R.string.title_other_settings),
            message = context.getString(messageRes),
            confirmText = context.getString(android.R.string.ok)
        )
    }

    fun showOtherMessageAfterCurrentDialog(messageRes: Int) {
        coroutineScope.launch {
            yield()
            showOtherMessage(messageRes)
        }
    }

    val syncClipboardWatchTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        if (isSyncClipboardDownloadDirectory(uri)) {
            showOtherMessage(R.string.sc_watch_tree_download_directory_not_allowed)
            return@rememberLauncherForActivityResult
        }
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.updateSyncClipboardWatchTreeUri(uri.toString())
        } catch (t: Throwable) {
            Log.e(OTHER_TAG, "Failed to persist clipboard watch directory permission", t)
        }
    }

    fun autoEnablePrivilegedKeepAliveAfterGranted() {
        if (!pendingPrivilegedEnable) return
        pendingPrivilegedEnable = false
        if (prefs.floatingKeepAlivePrivilegedEnabled) return
        if (!PrivilegedAccessManager.isServiceGranted()) return

        prefs.floatingKeepAlivePrivilegedEnabled = true
        refreshState()
    }

    DisposableEffect(context) {
        val listener: (PrivilegedAccessManager.PermissionRequestResult) -> Unit = { result ->
            if (result == PrivilegedAccessManager.PermissionRequestResult.Granted) {
                autoEnablePrivilegedKeepAliveAfterGranted()
            } else {
                pendingPrivilegedEnable = false
                showOtherMessage(
                    if (result == PrivilegedAccessManager.PermissionRequestResult.NotInstalled) {
                        R.string.toast_shizuku_or_root_unavailable
                    } else {
                        R.string.toast_shizuku_permission_request_failed
                    }
                )
            }
        }
        PrivilegedAccessManager.addPermissionResultListener(listener)
        onDispose { PrivilegedAccessManager.removePermissionResultListener(listener) }
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
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = titleRes,
            offDescRes = offDescRes,
            onDescRes = onDescRes,
            currentState = current,
            preferenceKey = preferenceKey,
            onConfirm = {
                if (preCheck != null && !preCheck(target)) {
                    refreshState()
                    return@settingsFeatureExplainerDialogState
                }
                write(target)
                onChanged?.invoke(target)
                refreshState()
            }
        )
    }

    fun showSpeechPresetPicker() {
        if (speechState.presets.isEmpty()) return
        val displayNames = speechState.presets.map {
            it.name.ifBlank { context.getString(R.string.speech_preset_untitled) }
        }
        val selectedIndex = speechState.presets.indexOfFirst { it.id == speechState.activePresetId }
            .let { if (it < 0) 0 else it }
        choiceSheet = settingsChoiceSheetState(
            title = context.getString(R.string.label_speech_preset_section),
            items = displayNames,
            selectedIndex = selectedIndex,
            onSelected = { which ->
                speechState.presets.getOrNull(which)?.let { preset ->
                    viewModel.setActivePreset(preset.id)
                }
            }
        )
    }

    OtherScaffold(uiMode = uiMode, onBack = onBack) { innerPadding, scrollModifier ->
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
        editingDownloadMirror?.let { prefix ->
            OtherDownloadMirrorDialog(
                uiMode = uiMode,
                mirrors = downloadMirrors,
                initialPrefix = prefix,
                onMirrorsChange = { mirrors ->
                    prefs.customDownloadMirrors = mirrors
                    downloadMirrors = mirrors
                },
                onDismiss = { editingDownloadMirror = null }
            )
        }
        OtherSettingsRouteContent(
            uiMode = uiMode,
            innerPadding = innerPadding,
            scrollModifier = scrollModifier,
            uiState = uiState,
            downloadMirrors = downloadMirrors,
            onOpenDownloadMirrorEditor = { editingDownloadMirror = it },
            punctuation = PunctuationFields(punct1, punct2, punct3, punct4),
            speechState = speechState,
            syncState = syncState,
            focusNameAfterAdd = focusNameAfterAdd,
            onFocusNameHandled = { focusNameAfterAdd = false },
            onKeepAliveToggle = { target ->
                applyExplainedSwitch(
                    current = uiState.keepAliveEnabled,
                    target = target,
                    titleRes = R.string.label_floating_keep_alive_foreground,
                    offDescRes = R.string.feature_floating_keep_alive_off_desc,
                    onDescRes = R.string.feature_floating_keep_alive_on_desc,
                    preferenceKey = "floating_keep_alive_explained",
                    onChanged = { enabled ->
                        if (enabled) {
                            serviceManager.startKeepAliveService()
                        } else {
                            serviceManager.stopKeepAliveService()
                        }
                    }
                ) { prefs.floatingKeepAliveEnabled = it }
            },
            onKeepAliveNotificationClickRouteChange = { routeId ->
                prefs.keepAliveNotificationClickRoute = routeId
                uiState = uiState.copy(
                    keepAliveNotificationClickRoute = KeepAliveNotificationClick.routeFromPrefs(prefs).id
                )
                serviceManager.startKeepAliveService()
            },
            onPrivilegedKeepAliveToggle = { target ->
                applyExplainedSwitch(
                    current = uiState.privilegedKeepAliveEnabled,
                    target = target,
                    titleRes = R.string.label_floating_keep_alive_privileged,
                    offDescRes = R.string.feature_floating_keep_alive_privileged_off_desc,
                    onDescRes = R.string.feature_floating_keep_alive_privileged_on_desc,
                    preferenceKey = "floating_keep_alive_privileged_explained",
                    preCheck = { shouldEnable ->
                        checkPrivilegedKeepAlivePreconditions(
                            context = context,
                            shouldEnable = shouldEnable,
                            setPendingEnable = { pendingPrivilegedEnable = it },
                            showMessage = ::showOtherMessage
                        )
                    }
                ) { prefs.floatingKeepAlivePrivilegedEnabled = it }
            },
            onRequestBatteryWhitelist = {
                requestBatteryOptimizationWhitelist(context, ::showOtherMessage)
            },
            onDisableAsrHistoryToggle = { target ->
                applyExplainedSwitch(
                    current = uiState.disableAsrHistory,
                    target = target,
                    titleRes = R.string.label_disable_asr_history,
                    offDescRes = R.string.feature_disable_asr_history_off_desc,
                    onDescRes = R.string.feature_disable_asr_history_on_desc,
                    preferenceKey = "disable_asr_history_explained",
                    onChanged = { enabled ->
                        if (enabled) clearAsrHistory(context, ::showOtherMessage)
                    }
                ) { prefs.disableAsrHistory = it }
            },
            onAudioRetentionChange = { value ->
                val next = value.coerceIn(0, 100)
                uiState = uiState.copy(audioHistoryRetentionCount = next)
                prefs.audioHistoryRetentionCount = next
            },
            onAudioRetentionChangeFinished = {
                coroutineScope.launch(Dispatchers.IO) {
                    AsrHistoryAudioStore.pruneAsync(
                        context,
                        prefs.audioHistoryRetentionCount
                    )
                }
            },
            onDisableUsageStatsToggle = { target ->
                applyExplainedSwitch(
                    current = uiState.disableUsageStats,
                    target = target,
                    titleRes = R.string.label_disable_usage_stats,
                    offDescRes = R.string.feature_disable_usage_stats_off_desc,
                    onDescRes = R.string.feature_disable_usage_stats_on_desc,
                    preferenceKey = "disable_usage_stats_explained",
                    onChanged = { enabled ->
                        if (enabled) clearUsageStats(context, prefs, ::showOtherMessage)
                    }
                ) { prefs.disableUsageStats = it }
            },
            onDataCollectionToggle = { target ->
                applyExplainedSwitch(
                    current = uiState.dataCollectionEnabled,
                    target = target,
                    titleRes = R.string.label_data_collection,
                    offDescRes = R.string.feature_data_collection_off_desc,
                    onDescRes = R.string.feature_data_collection_on_desc,
                    preferenceKey = "data_collection_explained",
                    onChanged = { enabled ->
                        updateAnalyticsConsent(context, enabled)
                    }
                ) { prefs.dataCollectionEnabled = it }
            },
            onClearClipboardHistory = {
                messageDialog = SettingsMessageDialogState(
                    title = context.getString(R.string.dialog_clear_clipboard_history_title),
                    message = context.getString(R.string.dialog_clear_clipboard_history_message),
                    confirmText = context.getString(R.string.btn_confirm),
                    dismissText = context.getString(R.string.btn_cancel),
                    onConfirm = {
                        clearClipboardHistory(context, prefs, coroutineScope) {
                            showOtherMessageAfterCurrentDialog(it)
                        }
                    }
                )
            },
            onPunct1Change = { punct1 = it.take(3) },
            onPunct2Change = { punct2 = it.take(3) },
            onPunct3Change = { punct3 = it.take(3) },
            onPunct4Change = { punct4 = it.take(3) },
            onSpeechPresetPicker = { showSpeechPresetPicker() },
            onUpdateSpeechPresetName = viewModel::updateActivePresetName,
            onUpdateSpeechPresetContent = viewModel::updateActivePresetContent,
            onAddSpeechPreset = {
                val defaultName = context.getString(
                    R.string.speech_preset_default_name,
                    speechState.presets.size + 1
                )
                viewModel.addSpeechPreset(defaultName)
                focusNameAfterAdd = true
                showOtherMessage(R.string.toast_speech_preset_added)
            },
            onDeleteSpeechPreset = {
                speechState.currentPreset?.let { preset ->
                    messageDialog = SettingsMessageDialogState(
                        title = context.getString(R.string.dialog_speech_preset_delete_title),
                        message = context.getString(
                            R.string.dialog_speech_preset_delete_message,
                            preset.name.ifBlank {
                                context.getString(R.string.speech_preset_untitled)
                            }
                        ),
                        confirmText = context.getString(R.string.btn_speech_preset_delete),
                        dismissText = context.getString(R.string.btn_cancel),
                        onConfirm = {
                            viewModel.deleteSpeechPreset(preset.id)
                            showOtherMessageAfterCurrentDialog(R.string.toast_speech_preset_deleted)
                        }
                    )
                }
            },
            onSyncClipboardEnabledChange = { target ->
                applyExplainedSwitch(
                    current = syncState.enabled,
                    target = target,
                    titleRes = R.string.label_enable_sync_clipboard,
                    offDescRes = R.string.feature_sync_clipboard_off_desc,
                    onDescRes = R.string.feature_sync_clipboard_on_desc,
                    preferenceKey = "sync_clipboard_explained"
                ) { viewModel.updateSyncClipboardEnabled(it) }
            },
            onSyncClipboardServerChange = viewModel::updateSyncClipboardServerBase,
            onSyncClipboardUsernameChange = viewModel::updateSyncClipboardUsername,
            onSyncClipboardPasswordChange = viewModel::updateSyncClipboardPassword,
            onSyncClipboardAutoReceiveChange = { target ->
                applyExplainedSwitch(
                    current = syncState.receiveMode != ClipboardSyncReceiveMode.OFF,
                    target = target,
                    titleRes = R.string.label_sc_auto_receive,
                    offDescRes = R.string.feature_sc_auto_receive_off_desc,
                    onDescRes = R.string.feature_sc_auto_receive_on_desc,
                    preferenceKey = "sc_auto_receive_explained"
                ) { viewModel.updateSyncClipboardAutoReceiveEnabled(it) }
            },
            onSyncClipboardKeepBackgroundChange = { target ->
                applyExplainedSwitch(
                    current = syncState.keepBackgroundRealtimeEnabled,
                    target = target,
                    titleRes = R.string.label_sc_keep_background_realtime,
                    offDescRes = R.string.feature_sc_keep_background_realtime_off_desc,
                    onDescRes = R.string.feature_sc_keep_background_realtime_on_desc,
                    preferenceKey = "sc_keep_background_realtime_explained"
                ) { viewModel.updateSyncClipboardKeepBackgroundRealtimeEnabled(it) }
            },
            onSyncClipboardIntervalChange = viewModel::updateSyncClipboardPullIntervalSec,
            onSyncClipboardImagesChange = { target ->
                applyExplainedSwitch(
                    current = syncState.syncImagesEnabled,
                    target = target,
                    titleRes = R.string.label_sc_sync_images,
                    offDescRes = R.string.feature_sc_sync_images_off_desc,
                    onDescRes = R.string.feature_sc_sync_images_on_desc,
                    preferenceKey = "sc_sync_images_explained"
                ) { viewModel.updateSyncClipboardImagesEnabled(it) }
            },
            onSyncClipboardFilesChange = { target ->
                applyExplainedSwitch(
                    current = syncState.syncFilesEnabled,
                    target = target,
                    titleRes = R.string.label_sc_sync_files,
                    offDescRes = R.string.feature_sc_sync_files_off_desc,
                    onDescRes = R.string.feature_sc_sync_files_on_desc,
                    preferenceKey = "sc_sync_files_explained"
                ) { viewModel.updateSyncClipboardFilesEnabled(it) }
            },
            onSyncClipboardAttachmentMaxSizeChange = viewModel::updateSyncClipboardAttachmentMaxSizeMb,
            onChooseSyncClipboardWatchTree = { syncClipboardWatchTreeLauncher.launch(null) },
            onClearSyncClipboardWatchTree = {
                val oldUri = syncState.watchTreeUri
                if (oldUri.isNotBlank()) {
                    try {
                        context.contentResolver.releasePersistableUriPermission(
                            android.net.Uri.parse(oldUri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (t: Throwable) {
                        Log.w(OTHER_TAG, "Failed to release clipboard watch directory permission", t)
                    }
                }
                viewModel.updateSyncClipboardWatchTreeUri("")
            },
            onTestClipboardSync = {
                testClipboardSync(context, prefs, coroutineScope, ::showOtherMessage)
            },
            onOpenSyncClipboardProject = {
                openSyncClipboardProjectHome(context, ::showOtherMessage)
            }
        )
    }
}

private fun notifySyncClipboardConfigChanged(context: Context, prefs: Prefs) {
    val appContext = context.applicationContext
    appContext.sendBroadcast(
        Intent(AsrKeyboardService.ACTION_REFRESH_IME_UI).setPackage(appContext.packageName)
    )
    ClipboardSyncRuntimeService.notifyConfigChanged(appContext)
}
