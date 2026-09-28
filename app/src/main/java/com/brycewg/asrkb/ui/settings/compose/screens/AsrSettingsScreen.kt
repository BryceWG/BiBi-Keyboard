/**
 * Compose 语音识别设置页。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.asr.LocalModelCheck
import com.brycewg.asrkb.asr.VadDetector
import com.brycewg.asrkb.store.DashScopePrefsCompat
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.DownloadSourceConfig
import com.brycewg.asrkb.ui.DownloadSourceOption
import com.brycewg.asrkb.ui.settings.asr.AsrSettingsViewModel
import com.brycewg.asrkb.ui.settings.asr.ModelDownloadService
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDownloadSourceSheet
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMultiChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.settingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsActionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AsrLocalDownloadRequest(
    val spec: AsrLocalModelSpec,
    val variant: String,
    val options: List<DownloadSourceOption>
)

private data class AsrLocalModelQueryResult(
    val readyByKey: Map<String, Boolean>,
    val checkStatusByKey: Map<String, String>
)

private class LocalModelRefreshHandle {
    var job: Job? = null
}

@Composable
fun AsrSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    actions: SettingsActionController
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) { Prefs(context) }
    val viewModel: AsrSettingsViewModel = viewModel()
    remember(context, viewModel) {
        viewModel.initialize(context)
        true
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val onlineFields = rememberAsrOnlineSettingsFields(prefs)
    var backupAsrEnabled by remember(context) { mutableStateOf(prefs.backupAsrEnabled) }
    var backupAsrVendor by remember(context) { mutableStateOf(prefs.backupAsrVendor) }
    var backupSensitivity by remember(context) {
        mutableIntStateOf(prefs.backupAsrTimeoutSensitivity.coerceIn(0, 2))
    }
    var backupLocalResidency by remember(context) { mutableStateOf(prefs.backupAsrLocalResidency) }
    var choiceSheet by remember { mutableStateOf<SettingsChoiceSheetState?>(null) }
    var multiChoiceSheet by remember { mutableStateOf<SettingsMultiChoiceSheetState?>(null) }
    var downloadSourceRequest by remember { mutableStateOf<AsrLocalDownloadRequest?>(null) }
    var featureExplainerDialog by remember { mutableStateOf<SettingsFeatureExplainerDialogState?>(null) }
    var messageDialog by remember { mutableStateOf<SettingsMessageDialogState?>(null) }
    var localModelReadyByKey by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var localModelOperationStatusByKey by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var localModelCheckStatusByKey by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var localModelPendingKeys by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingImport by remember { mutableStateOf<Pair<AsrLocalModelSpec, String>?>(null) }
    val localModelRefreshHandle = remember { LocalModelRefreshHandle() }

    fun setLocalModelStatus(spec: AsrLocalModelSpec, message: String, pending: Boolean = false) {
        localModelOperationStatusByKey = localModelOperationStatusByKey + (spec.key to message)
        localModelPendingKeys = if (pending) {
            localModelPendingKeys + spec.key
        } else {
            localModelPendingKeys - spec.key
        }
    }

    suspend fun queryLocalModelStatus(): AsrLocalModelQueryResult = withContext(Dispatchers.IO) {
        val readyByKey = mutableMapOf<String, Boolean>()
        val checkStatusByKey = mutableMapOf<String, String>()
        AllAsrLocalModelSpecs.forEach { spec ->
            val check = runCatching { spec.checkStatus(viewModel, context) }
                .getOrDefault(LocalModelCheck.Missing)
            readyByKey[spec.key] = check is LocalModelCheck.Ready
            if (check is LocalModelCheck.IntegrityError) {
                checkStatusByKey[spec.key] = context.getString(
                    R.string.error_local_model_integrity_failed,
                    check.fileName
                )
            }
        }
        AsrLocalModelQueryResult(
            readyByKey = readyByKey,
            checkStatusByKey = checkStatusByKey
        )
    }

    fun applyLocalModelQueryResult(result: AsrLocalModelQueryResult) {
        val readyKeys = result.readyByKey.filterValues { it }.keys
        val terminalKeys = readyKeys + result.checkStatusByKey.keys
        val clearsTrackedState = localModelPendingKeys.any { it in terminalKeys } ||
            localModelOperationStatusByKey.keys.any { it in terminalKeys }
        if (localModelReadyByKey != result.readyByKey) {
            localModelReadyByKey = result.readyByKey
        }
        if (localModelCheckStatusByKey != result.checkStatusByKey) {
            localModelCheckStatusByKey = result.checkStatusByKey
        }
        if (clearsTrackedState) {
            localModelOperationStatusByKey = localModelOperationStatusByKey - terminalKeys
            localModelPendingKeys = localModelPendingKeys - terminalKeys
        }
        if (readyKeys.isNotEmpty() && clearsTrackedState) {
            viewModel.refreshFromPrefs()
        }
    }

    fun refreshLocalModelReady() {
        localModelRefreshHandle.job?.cancel()
        val job = scope.launch {
            applyLocalModelQueryResult(queryLocalModelStatus())
        }
        localModelRefreshHandle.job = job
        job.invokeOnCompletion {
            if (localModelRefreshHandle.job === job) {
                localModelRefreshHandle.job = null
            }
        }
    }

    val localModelImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val request = pendingImport
        pendingImport = null
        if (uri != null && request != null) {
            val (spec, variant) = request
            runCatching {
                val importModelType = spec.importModelType
                if (importModelType == null) {
                    ModelDownloadService.startImport(context, uri, variant)
                } else {
                    ModelDownloadService.startImport(context, uri, variant, importModelType)
                }
                setLocalModelStatus(
                    spec = spec,
                    message = context.getString(spec.importStartedRes),
                    pending = true
                )
            }.onFailure {
                setLocalModelStatus(spec, context.getString(spec.failedStatusRes))
            }
            refreshLocalModelReady()
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshFromPrefs()
                backupAsrEnabled = prefs.backupAsrEnabled
                backupAsrVendor = prefs.backupAsrVendor
                backupSensitivity = prefs.backupAsrTimeoutSensitivity.coerceIn(0, 2)
                backupLocalResidency = prefs.backupAsrLocalResidency
                onlineFields.refreshFromPrefs()
                refreshLocalModelReady()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(
        uiState.selectedVendor,
        uiState.svModelVariant,
        uiState.fnModelVariant,
        uiState.qwModelVariant,
        uiState.pkModelVariant,
        uiState.frModelVariant,
        uiState.xAsrModelVariant
    ) {
        refreshLocalModelReady()
    }

    LaunchedEffect(localModelPendingKeys) {
        var attempts = 0
        while (localModelPendingKeys.isNotEmpty() && attempts < 120) {
            delay(2500)
            applyLocalModelQueryResult(queryLocalModelStatus())
            attempts += 1
        }
    }

    fun showAsrMessageText(message: String) {
        messageDialog = SettingsMessageDialogState(
            title = context.getString(R.string.title_asr_settings),
            message = message,
            confirmText = context.getString(android.R.string.ok)
        )
    }

    fun showAsrMessage(messageRes: Int) {
        showAsrMessageText(context.getString(messageRes))
    }

    fun showVendorPicker(
        titleResId: Int,
        selectedVendor: AsrVendor,
        onSelected: (AsrVendor) -> Unit
    ) {
        choiceSheet = asrVendorChoiceSheetState(
            context = context,
            prefs = prefs,
            titleResId = titleResId,
            selectedVendor = selectedVendor,
            onSelected = onSelected
        )
    }

    fun showBackupSensitivityPicker() {
        choiceSheet = backupSensitivityChoiceSheetState(
            context = context,
            selectedIndex = backupSensitivity
        ) { selectedIdx ->
            backupSensitivity = selectedIdx.coerceIn(0, 2)
            prefs.backupAsrTimeoutSensitivity = backupSensitivity
        }
    }

    fun showBackupLocalResidencyPicker() {
        choiceSheet = backupLocalResidencyChoiceSheetState(
            context = context,
            selected = backupLocalResidency
        ) { selected ->
            backupLocalResidency = selected
            prefs.backupAsrLocalResidency = selected
        }
    }

    fun showSfFreeModelPicker() {
        choiceSheet = sfFreeAsrModelChoiceSheetState(
            context = context,
            selectedModel = onlineFields.sfFreeAsrModel
        ) { model ->
            onlineFields.sfFreeAsrModel = model
            prefs.sfFreeAsrModel = model
        }
    }

    fun showSfPaidModelPicker() {
        choiceSheet = sfPaidAsrModelChoiceSheetState(
            context = context,
            selectedModel = onlineFields.sfModel
        ) { model ->
            onlineFields.sfModel = model
            prefs.sfModel = model
            viewModel.updateSfUseOmni(isSfOmniModel(model))
        }
    }

    fun showDashModelPicker() {
        choiceSheet = dashModelChoiceSheetState(
            context = context,
            selectedModel = onlineFields.dashModel
        ) { model ->
            onlineFields.dashModel = model
            prefs.dashAsrModel = model
        }
    }

    fun showVolcModelPicker() {
        choiceSheet = volcModelChoiceSheetState(
            context = context,
            selectedModel = uiState.volcAsrModel
        ) { modelId ->
            viewModel.updateVolcAsrModel(modelId)
        }
    }

    fun showStepAudioModelPicker() {
        choiceSheet = stepAudioModelChoiceSheetState(
            context = context,
            selectedModel = onlineFields.stepAudioModel
        ) { model ->
            onlineFields.stepAudioModel = model
            prefs.stepAudioModel = model
        }
    }

    fun applyAutoStopSwitch(target: Boolean) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = R.string.label_auto_stop_silence,
            offDescRes = R.string.feature_auto_stop_silence_off_desc,
            onDescRes = R.string.feature_auto_stop_silence_on_desc,
            currentState = uiState.autoStopSilenceEnabled,
            preferenceKey = "auto_stop_silence_explained",
            onConfirm = {
                viewModel.updateAutoStopSilence(target)
                if (target) {
                    try {
                        VadDetector.preload(
                            context.applicationContext,
                            16000,
                            prefs.autoStopSilenceSensitivity
                        )
                    } catch (t: Throwable) {
                        android.util.Log.w("AsrSettingsScreen", "Failed to preload VAD", t)
                    }
                }
            }
        )
    }

    fun applyRecordingAutoStopMode(mode: Prefs.RecordingAutoStopMode) {
        when (mode) {
            Prefs.RecordingAutoStopMode.SILENCE -> {
                if (uiState.recordingAutoStopMode == Prefs.RecordingAutoStopMode.MANUAL) {
                    applyAutoStopSwitch(true)
                } else {
                    viewModel.updateRecordingAutoStopMode(mode)
                    try {
                        VadDetector.preload(
                            context.applicationContext,
                            16000,
                            prefs.autoStopSilenceSensitivity
                        )
                    } catch (t: Throwable) {
                        android.util.Log.w("AsrSettingsScreen", "Failed to preload VAD", t)
                    }
                }
            }
            Prefs.RecordingAutoStopMode.MANUAL,
            Prefs.RecordingAutoStopMode.MAX_DURATION -> viewModel.updateRecordingAutoStopMode(mode)
        }
    }

    fun applyElevenStreamingSwitch(target: Boolean) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = R.string.label_eleven_streaming,
            offDescRes = R.string.feature_eleven_streaming_off_desc,
            onDescRes = R.string.feature_eleven_streaming_on_desc,
            currentState = onlineFields.elevenStreaming,
            preferenceKey = "eleven_streaming_explained",
            onConfirm = {
                onlineFields.elevenStreaming = target
                viewModel.updateElevenStreaming(target)
            }
        )
    }

    fun applyStepAudioUseItnSwitch(target: Boolean) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = R.string.label_stepaudio_use_itn,
            offDescRes = R.string.feature_stepaudio_use_itn_off_desc,
            onDescRes = R.string.feature_stepaudio_use_itn_on_desc,
            currentState = onlineFields.stepAudioUseItn,
            preferenceKey = "stepaudio_use_itn_explained",
            onConfirm = {
                onlineFields.stepAudioUseItn = target
                prefs.stepAudioUseItn = target
            }
        )
    }

    fun applyMimoDisableThinkingSwitch(target: Boolean) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = R.string.label_mimo_disable_thinking,
            offDescRes = R.string.feature_mimo_disable_thinking_off_desc,
            onDescRes = R.string.feature_mimo_disable_thinking_on_desc,
            currentState = onlineFields.mimoDisableThinking,
            preferenceKey = "mimo_disable_thinking_explained",
            onConfirm = {
                onlineFields.mimoDisableThinking = target
                prefs.mimoAsrDisableThinking = target
            }
        )
    }

    fun applyOpenAiStreamingSwitch(target: Boolean) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = R.string.label_openai_streaming,
            offDescRes = R.string.feature_openai_streaming_off_desc,
            onDescRes = R.string.feature_openai_streaming_on_desc,
            currentState = onlineFields.openAiStreaming,
            preferenceKey = "openai_streaming_explained",
            onConfirm = {
                onlineFields.openAiStreaming = target
                viewModel.updateOpenAiStreaming(target)
            }
        )
    }

    fun applyOpenAiUseCompletionsSwitch(target: Boolean) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = R.string.label_openai_use_completions,
            offDescRes = R.string.feature_openai_use_completions_off_desc,
            onDescRes = R.string.feature_openai_use_completions_on_desc,
            currentState = onlineFields.openAiUseCompletions,
            preferenceKey = "openai_use_completions_explained",
            onConfirm = {
                onlineFields.openAiUseCompletions = target
                prefs.oaAsrUseCompletions = target
                viewModel.refreshOpenAiProfileState()
            }
        )
    }

    fun applyOpenAiUsePromptSwitch(target: Boolean) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = R.string.label_openai_use_prompt,
            offDescRes = R.string.feature_openai_use_prompt_off_desc,
            onDescRes = R.string.feature_openai_use_prompt_on_desc,
            currentState = onlineFields.openAiUsePrompt,
            preferenceKey = "openai_use_prompt_explained",
            onConfirm = {
                onlineFields.openAiUsePrompt = target
                viewModel.updateOpenAiUsePrompt(target)
            }
        )
    }

    fun applySonioxStreamingSwitch(target: Boolean) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = R.string.label_soniox_streaming,
            offDescRes = R.string.feature_soniox_streaming_off_desc,
            onDescRes = R.string.feature_soniox_streaming_on_desc,
            currentState = onlineFields.sonioxStreaming,
            preferenceKey = "soniox_streaming_explained",
            onConfirm = {
                onlineFields.sonioxStreaming = target
                viewModel.updateSonioxStreaming(target)
            }
        )
    }

    fun applyDashKeepDialectSwitch(target: Boolean) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = R.string.label_dash_keep_dialect,
            offDescRes = R.string.feature_dash_keep_dialect_off_desc,
            onDescRes = R.string.feature_dash_keep_dialect_on_desc,
            currentState = onlineFields.dashKeepDialect,
            preferenceKey = "dash_keep_dialect_explained",
            onConfirm = {
                onlineFields.dashKeepDialect = target
                prefs.dashKeepDialect = target
            }
        )
    }

    fun applyDashAutoPolishSwitch(target: Boolean) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = R.string.label_dash_auto_polish,
            offDescRes = R.string.feature_dash_auto_polish_off_desc,
            onDescRes = R.string.feature_dash_auto_polish_on_desc,
            currentState = onlineFields.dashAutoPolish,
            preferenceKey = "dash_auto_polish_explained",
            onConfirm = {
                onlineFields.dashAutoPolish = target
                prefs.dashAutoPolish = target
            }
        )
    }

    fun applySonioxLanguageStrictSwitch(target: Boolean) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = R.string.label_soniox_language_strict,
            offDescRes = R.string.feature_soniox_language_strict_off_desc,
            onDescRes = R.string.feature_soniox_language_strict_on_desc,
            currentState = onlineFields.sonioxLanguageStrict,
            preferenceKey = "soniox_language_strict_explained",
            onConfirm = {
                onlineFields.sonioxLanguageStrict = target
                prefs.sonioxLanguageHintsStrict = target
            }
        )
    }

    fun applyGeminiThinkingSwitch(target: Boolean) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = R.string.label_gemini_disable_thinking,
            offDescRes = R.string.feature_gemini_disable_thinking_off_desc,
            onDescRes = R.string.feature_gemini_disable_thinking_on_desc,
            currentState = onlineFields.geminiDisableThinking,
            preferenceKey = "gemini_disable_thinking_explained",
            onConfirm = {
                onlineFields.geminiDisableThinking = target
                prefs.geminiDisableThinking = target
            }
        )
    }

    fun applyGeminiTranscribeSmartSwitch(target: Boolean) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = R.string.label_gemini_transcribe_smart,
            offDescRes = R.string.feature_gemini_transcribe_smart_off_desc,
            onDescRes = R.string.feature_gemini_transcribe_smart_on_desc,
            currentState = onlineFields.geminiTranscribeSmartEnabled,
            preferenceKey = "gemini_transcribe_smart_explained",
            onConfirm = {
                onlineFields.geminiTranscribeSmartEnabled = target
                prefs.gemTranscribeSmartEnabled = target
            }
        )
    }

    fun applyVolcSwitch(
        target: Boolean,
        titleResId: Int,
        offDescResId: Int,
        onDescResId: Int,
        currentState: Boolean,
        preferenceKey: String,
        onConfirm: (Boolean) -> Unit
    ) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = titleResId,
            offDescRes = offDescResId,
            onDescRes = onDescResId,
            currentState = currentState,
            preferenceKey = preferenceKey,
            onConfirm = { onConfirm(target) }
        )
    }

    fun rebuildVadIfNeeded() {
        if (!prefs.autoStopOnSilenceEnabled) return
        try {
            VadDetector.rebuildGlobal(
                context.applicationContext,
                16000,
                prefs.autoStopSilenceSensitivity
            )
            showAsrMessage(R.string.toast_vad_sensitivity_applied)
        } catch (t: Throwable) {
            android.util.Log.w("AsrSettingsScreen", "Failed to rebuild VAD", t)
        }
    }

    fun showSonioxLanguagePicker() {
        val options = sonioxLanguageOptions(context)
        val selected = onlineFields.sonioxLanguages
        val checked = options.mapIndexedNotNull { index, option ->
            val isAuto = option.value.isBlank()
            val isChecked = if (isAuto) selected.isEmpty() else option.value in selected
            if (isChecked) index else null
        }.toSet().ifEmpty { setOf(0) }
        multiChoiceSheet = SettingsMultiChoiceSheetState(
            title = context.getString(R.string.label_soniox_language),
            items = options.map { it.label },
            checkedIndices = checked,
            confirmText = context.getString(R.string.btn_confirm),
            cancelText = context.getString(R.string.btn_cancel),
            onConfirm = { selectedIndices ->
                val selectedCodes = selectedIndices
                    .sorted()
                    .mapNotNull { index -> options.getOrNull(index)?.value }
                    .filter { it.isNotBlank() }
                onlineFields.sonioxLanguages = selectedCodes
                viewModel.updateSonioxLanguages(selectedCodes)
                true
            }
        )
    }

    fun showDashLanguagePicker() {
        val options = dashLanguageOptions(context).filter { it.value.isNotBlank() }
        val selected = DashScopePrefsCompat.parseDashLanguages(onlineFields.dashLanguage)
        multiChoiceSheet = SettingsMultiChoiceSheetState(
            title = context.getString(R.string.label_dash_language),
            items = options.map { it.label },
            checkedIndices = options.mapIndexedNotNull { index, option ->
                index.takeIf { option.value in selected }
            }.toSet(),
            confirmText = context.getString(R.string.btn_confirm),
            cancelText = context.getString(R.string.btn_cancel),
            maxSelectionCount = DashScopePrefsCompat.MAX_QWEN_AUDIO_LANGUAGE_HINTS,
            maxSelectionMessage = context.getString(R.string.dash_language_max_4),
            onSelectionRejected = { showAsrMessageText(it) },
            onConfirm = { selectedIndices ->
                prefs.setDashLanguages(selectedIndices.mapNotNull { options.getOrNull(it)?.value })
                onlineFields.dashLanguage = prefs.dashLanguage
                true
            }
        )
    }

    fun showLocalModelDownloadSource(spec: AsrLocalModelSpec, variant: String) {
        val url = spec.downloadUrl(variant)
        downloadSourceRequest = AsrLocalDownloadRequest(
            spec = spec,
            variant = variant,
            options = DownloadSourceConfig.buildOptions(context, url)
        )
    }

    fun importLocalModel(spec: AsrLocalModelSpec, variant: String) {
        pendingImport = spec to variant
        localModelImportLauncher.launch("application/zip")
    }

    fun confirmClearLocalModel(spec: AsrLocalModelSpec) {
        messageDialog = SettingsMessageDialogState(
            title = context.getString(spec.clearTitleRes),
            message = context.getString(spec.clearMessageRes),
            confirmText = context.getString(android.R.string.ok),
            dismissText = context.getString(R.string.btn_cancel),
            onConfirm = {
                scope.launch {
                    val success = withContext(Dispatchers.IO) {
                        runCatching { spec.clearInstalled(context, prefs) }.getOrDefault(false)
                    }
                    setLocalModelStatus(
                        spec,
                        context.getString(if (success) spec.clearDoneRes else spec.clearFailedRes)
                    )
                    refreshLocalModelReady()
                }
            }
        )
    }

    AsrScaffold(uiMode = uiMode, onBack = onBack) { innerPadding, scrollModifier ->
        downloadSourceRequest?.let { request ->
            SettingsDownloadSourceSheet(
                options = request.options,
                uiMode = uiMode,
                onDismiss = { downloadSourceRequest = null },
                onSelect = { option ->
                    runCatching {
                        ModelDownloadService.startDownload(
                            context,
                            option.url,
                            request.variant,
                            request.spec.modelType
                        )
                        setLocalModelStatus(
                            spec = request.spec,
                            message = context.getString(request.spec.downloadStartedRes),
                            pending = true
                        )
                    }.onFailure {
                        setLocalModelStatus(
                            request.spec,
                            context.getString(request.spec.failedStatusRes)
                        )
                    }
                    downloadSourceRequest = null
                    refreshLocalModelReady()
                }
            )
        }
        AsrSettingsDialogHost(
            uiMode = uiMode,
            choiceSheet = choiceSheet,
            multiChoiceSheet = multiChoiceSheet,
            featureExplainerDialog = featureExplainerDialog,
            messageDialog = messageDialog,
            onDismissChoiceSheet = { choiceSheet = null },
            onDismissMultiChoiceSheet = { multiChoiceSheet = null },
            onDismissFeatureExplainerDialog = { featureExplainerDialog = null },
            onDismissMessageDialog = { messageDialog = null }
        )
        AsrSettingsRouteContent(
            uiMode = uiMode,
            context = context,
            prefs = prefs,
            viewModel = viewModel,
            uiState = uiState,
            innerPadding = innerPadding,
            scrollModifier = scrollModifier,
            onlineState = onlineFields.toRouteState(
                viewModel = viewModel,
                applyElevenStreamingSwitch = ::applyElevenStreamingSwitch,
                applyGeminiThinkingSwitch = ::applyGeminiThinkingSwitch,
                applyGeminiTranscribeSmartSwitch = ::applyGeminiTranscribeSmartSwitch,
                applyMimoDisableThinkingSwitch = ::applyMimoDisableThinkingSwitch,
                applyOpenAiStreamingSwitch = ::applyOpenAiStreamingSwitch,
                applyOpenAiUseCompletionsSwitch = ::applyOpenAiUseCompletionsSwitch,
                applyOpenAiUsePromptSwitch = ::applyOpenAiUsePromptSwitch,
                applySonioxStreamingSwitch = ::applySonioxStreamingSwitch,
                applySonioxLanguageStrictSwitch = ::applySonioxLanguageStrictSwitch,
                applyDashKeepDialectSwitch = ::applyDashKeepDialectSwitch,
                applyDashAutoPolishSwitch = ::applyDashAutoPolishSwitch,
                applyStepAudioUseItnSwitch = ::applyStepAudioUseItnSwitch,
                openAiDefaultProfileName = { index ->
                    context.getString(R.string.openai_profile_default_name, index)
                }
            ),
            localModelState = AsrLocalModelRouteState(
                readyByKey = localModelReadyByKey,
                statusByKey = localModelOperationStatusByKey + localModelCheckStatusByKey,
                errorStatusKeys = localModelCheckStatusByKey.keys,
                onDownload = ::showLocalModelDownloadSource,
                onImport = ::importLocalModel,
                onClear = ::confirmClearLocalModel,
                onRefresh = ::refreshLocalModelReady
            ),
            backupState = AsrBackupSettingsRouteState(
                enabled = backupAsrEnabled,
                onEnabledChange = { backupAsrEnabled = it },
                vendor = backupAsrVendor,
                onVendorChange = { backupAsrVendor = it },
                sensitivity = backupSensitivity,
                localResidency = backupLocalResidency
            ),
            routeActions = AsrSettingsRouteActions(
                showVendorPicker = ::showVendorPicker,
                showBackupSensitivityPicker = ::showBackupSensitivityPicker,
                showBackupLocalResidencyPicker = ::showBackupLocalResidencyPicker,
                showSfFreeModelPicker = ::showSfFreeModelPicker,
                showSfPaidModelPicker = ::showSfPaidModelPicker,
                showDashModelPicker = ::showDashModelPicker,
                showVolcModelPicker = ::showVolcModelPicker,
                showDashLanguagePicker = ::showDashLanguagePicker,
                showStepAudioModelPicker = ::showStepAudioModelPicker,
                showSonioxLanguagePicker = ::showSonioxLanguagePicker,
                onPrimaryVendorSelected = { vendor ->
                    viewModel.updateVendor(vendor)
                    backupAsrVendor = prefs.backupAsrVendor
                },
                applyRecordingAutoStopMode = ::applyRecordingAutoStopMode,
                applyAutoStopSwitch = ::applyAutoStopSwitch,
                applyVolcSwitch = ::applyVolcSwitch,
                rebuildVadIfNeeded = ::rebuildVadIfNeeded,
                onOpenUrl = actions::openUrl
            )
        )
    }
}
