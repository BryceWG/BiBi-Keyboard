/**
 * Compose AI 后处理设置页。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.brycewg.asrkb.BuildConfig
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.ime.AsrKeyboardService
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.ai.AiPostSettingsViewModel
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMultiChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsProgressDialogState
import com.brycewg.asrkb.ui.settings.compose.components.settingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsActionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

private const val AI_TAG = "AiSettingsScreen"

@Composable
fun AiSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    actions: SettingsActionController
) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    val coroutineScope = rememberCoroutineScope()
    val viewModel: AiPostSettingsViewModel = viewModel()
    val selectedVendor by viewModel.selectedVendor.collectAsStateWithLifecycle()
    val builtinConfig by viewModel.builtinVendorConfig.collectAsStateWithLifecycle()
    val profiles by viewModel.llmProfiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeLlmProvider.collectAsStateWithLifecycle()
    val promptPresets by viewModel.promptPresets.collectAsStateWithLifecycle()
    val activePromptPreset by viewModel.activePromptPreset.collectAsStateWithLifecycle()
    val localState = rememberAiSettingsLocalState(prefs, selectedVendor, builtinConfig)

    var llmTestJob by remember { mutableStateOf<Job?>(null) }
    var llmTestProcessor by remember { mutableStateOf<LlmPostProcessor?>(null) }
    var llmTestSessionId by remember { mutableLongStateOf(0L) }
    var choiceSheet by remember { mutableStateOf<SettingsChoiceSheetState?>(null) }
    var multiChoiceSheet by remember { mutableStateOf<SettingsMultiChoiceSheetState?>(null) }
    var messageDialog by remember { mutableStateOf<SettingsMessageDialogState?>(null) }
    var progressDialog by remember { mutableStateOf<SettingsProgressDialogState?>(null) }
    var featureExplainerDialog by remember { mutableStateOf<SettingsFeatureExplainerDialogState?>(null) }
    var llmTestResultDialog by remember { mutableStateOf<AiLlmTestResultDialogState?>(null) }
    val llmTestRunning = llmTestJob?.isActive == true

    LaunchedEffect(context) {
        viewModel.loadData(prefs)
    }

    LaunchedEffect(selectedVendor, builtinConfig.model) {
        localState.syncBuiltinVendor(selectedVendor, builtinConfig)
    }

    LaunchedEffect(activeProfile?.id) {
        localState.syncActiveProfile(activeProfile)
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.flushPendingWrites(prefs)
            llmTestSessionId++
            llmTestProcessor?.cancelActiveRequest()
            llmTestJob?.cancel(CancellationException("AI settings disposed"))
            progressDialog = null
            messageDialog = null
            llmTestResultDialog = null
        }
    }

    fun sendRefreshBroadcast() {
        context.sendBroadcast(
            Intent(AsrKeyboardService.ACTION_REFRESH_IME_UI).apply {
                setPackage(context.packageName)
            }
        )
    }

    fun showAiMessage(messageRes: Int) {
        messageDialog = SettingsMessageDialogState(
            title = context.getString(R.string.title_ai_settings),
            message = context.getString(messageRes),
            confirmText = context.getString(android.R.string.ok)
        )
    }

    fun applyExplainedSwitch(
        current: Boolean,
        target: Boolean,
        titleRes: Int,
        offDescRes: Int,
        onDescRes: Int,
        preferenceKey: String,
        write: (Boolean) -> Unit,
        afterWrite: (Boolean) -> Unit = {}
    ) {
        featureExplainerDialog = settingsFeatureExplainerDialogState(
            context = context,
            titleRes = titleRes,
            offDescRes = offDescRes,
            onDescRes = onDescRes,
            currentState = current,
            preferenceKey = preferenceKey,
            onConfirm = {
                write(target)
                afterWrite(target)
            }
        )
    }

    fun showMessageDialog(titleRes: Int, message: String) {
        messageDialog = SettingsMessageDialogState(
            title = context.getString(titleRes),
            message = message,
            confirmText = context.getString(android.R.string.ok)
        )
    }

    fun showFetchFailedDialog(message: String) {
        showMessageDialog(
            titleRes = R.string.llm_models_fetch_failed_title,
            message = context.getString(R.string.llm_models_fetch_failed, message)
        )
    }

    val pickerActions = AiSettingsPickerActions(
        context = context,
        prefs = prefs,
        localState = localState,
        viewModel = viewModel,
        selectedVendor = selectedVendor,
        builtinConfig = builtinConfig,
        profiles = profiles,
        activeProfile = activeProfile,
        promptPresets = promptPresets,
        onChoiceSheetChange = { choiceSheet = it },
        onMultiChoiceSheetChange = { multiChoiceSheet = it },
        onMessage = ::showAiMessage,
        onFetchFailed = ::showFetchFailedDialog
    )

    fun fetchModels(endpoint: String, apiKey: String, onSuccess: (List<String>) -> Unit) {
        launchAiModelsFetch(
            context = context,
            coroutineScope = coroutineScope,
            endpoint = endpoint,
            apiKey = apiKey,
            flushPendingWrites = { viewModel.flushPendingWrites(prefs) },
            onMissingParams = { showAiMessage(R.string.llm_test_missing_params) },
            onProgressChange = { progressDialog = it },
            onClearProgress = { progressState ->
                if (progressDialog === progressState) {
                    progressDialog = null
                }
            },
            onFetchFailed = ::showFetchFailedDialog,
            onSuccess = onSuccess
        )
    }

    fun cancelRunningLlmTest(showToast: Boolean, reason: String) {
        llmTestSessionId++
        llmTestProcessor?.cancelActiveRequest()
        llmTestProcessor = null
        llmTestJob?.cancel(CancellationException(reason))
        llmTestJob = null
        progressDialog = null
        if (showToast) showAiMessage(R.string.llm_test_cancelled)
    }

    fun testLlmCall() {
        if (llmTestJob?.isActive == true) return
        val sessionId = llmTestSessionId + 1L
        llmTestSessionId = sessionId
        llmTestJob = launchAiLlmTest(
            context = context,
            coroutineScope = coroutineScope,
            prefs = prefs,
            sessionId = sessionId,
            flushPendingWrites = { viewModel.flushPendingWrites(prefs) },
            onCancel = {
                cancelRunningLlmTest(showToast = true, reason = "User canceled LLM test")
            },
            isActiveSession = { it == llmTestSessionId },
            onProcessorChange = { llmTestProcessor = it },
            onProgressChange = { progressDialog = it },
            onSuccess = { result ->
                llmTestResultDialog = AiLlmTestResultDialogState(
                    responseMode =
                    result.responseMode ?: LlmPostProcessor.LlmResponseMode.NON_SSE,
                    totalMs = result.totalMs,
                    connectionMs = result.connectionMs,
                    responseHeadersMs = result.responseHeadersMs,
                    firstVisibleMs = result.firstVisibleMs,
                    outputMs = result.outputMs,
                    responseBodyMs = result.responseBodyMs,
                    preview = result.contentPreview,
                    connectionReused = result.connectionReused,
                    fallbackUsed = result.fallbackUsed
                )
            },
            onFailed = { message ->
                showMessageDialog(
                    titleRes = R.string.llm_test_failed_title,
                    message = message
                )
            },
            onClear = { finishedSessionId, progressState ->
                if (finishedSessionId == llmTestSessionId) {
                    llmTestProcessor = null
                    llmTestJob = null
                    if (progressDialog === progressState) {
                        progressDialog = null
                    }
                }
            }
        )
    }

    AiScaffold(uiMode = uiMode, onBack = onBack) { innerPadding, scrollModifier ->
        AiSettingsDialogHost(
            uiMode = uiMode,
            choiceSheet = choiceSheet,
            multiChoiceSheet = multiChoiceSheet,
            messageDialog = messageDialog,
            progressDialog = progressDialog,
            featureExplainerDialog = featureExplainerDialog,
            llmTestResultDialog = llmTestResultDialog,
            onDismissChoiceSheet = { choiceSheet = null },
            onDismissMultiChoiceSheet = { multiChoiceSheet = null },
            onDismissMessageDialog = { messageDialog = null },
            onDismissProgressDialog = { progressDialog = null },
            onDismissFeatureExplainerDialog = { featureExplainerDialog = null },
            onDismissLlmTestResultDialog = { llmTestResultDialog = null }
        )
        AiSettingsRouteContent(
            uiMode = uiMode,
            innerPadding = innerPadding,
            scrollModifier = scrollModifier,
            prefs = prefs,
            viewModel = viewModel,
            routeState = localState.toRouteState(
                selectedVendor = selectedVendor,
                builtinConfig = builtinConfig,
                activeProfile = activeProfile,
                activePromptPreset = activePromptPreset,
                llmTestRunning = llmTestRunning
            ),
            routeActions = AiSettingsRouteActions(
                onPostProcessEnabledChange = { localState.postProcessEnabled = it },
                onTypewriterEnabledChange = { localState.typewriterEnabled = it },
                onAiEditPreferLastAsrChange = { localState.aiEditPreferLastAsr = it },
                onSkipUnderCharsChange = { localState.skipUnderChars = it },
                onAiEditCustomSystemPromptEnabledChange = {
                    localState.aiEditCustomSystemPromptEnabled =
                        it
                },
                onAiEditSystemPromptChange = { localState.aiEditSystemPrompt = it },
                onSfApiKeyChange = { localState.sfApiKey = it },
                onSfModelChange = { localState.sfModel = it },
                onSfReasoningCharThresholdChange = { localState.sfReasoningCharThreshold = it },
                onSfReasoningOnJsonChange = { localState.sfReasoningOnJson = it },
                onSfReasoningOffJsonChange = { localState.sfReasoningOffJson = it },
                onSfCustomReasoningParamsEnabledChange = {
                    localState.sfCustomReasoningParamsEnabled = it
                },
                onSfTemperatureChange = { localState.sfTemperature = it },
                onSfCustomModelInputVisibleChange = { localState.sfCustomModelInputVisible = it },
                onBuiltinCustomModelInputVisibleChange = { localState.builtinCustomModelInputVisible = it },
                onBuiltinReasoningOnJsonChange = { localState.builtinReasoningOnJson = it },
                onBuiltinReasoningOffJsonChange = { localState.builtinReasoningOffJson = it },
                onFocusProfileNameAfterAddChange = { localState.focusProfileNameAfterAdd = it },
                onFocusPromptTitleAfterAddChange = { localState.focusPromptTitleAfterAdd = it },
                onMessage = ::showAiMessage,
                onRefreshSfState = localState::refreshSfState,
                onSendRefreshBroadcast = ::sendRefreshBroadcast,
                onShowExplainedSwitch = { current, target, titleRes, offDescRes, onDescRes, preferenceKey, write, afterWrite ->
                    applyExplainedSwitch(
                        current = current,
                        target = target,
                        titleRes = titleRes,
                        offDescRes = offDescRes,
                        onDescRes = onDescRes,
                        preferenceKey = preferenceKey,
                        write = write,
                        afterWrite = afterWrite
                    )
                },
                onShowVendorSelectionDialog = pickerActions::showVendorSelectionDialog,
                onShowSfModelDialog = pickerActions::showSfModelDialog,
                onShowBuiltinModelDialog = pickerActions::showBuiltinModelDialog,
                onShowProfileDialog = pickerActions::showProfileDialog,
                onShowCustomModelDialog = pickerActions::showCustomModelDialog,
                onShowPromptPresetDialog = pickerActions::showPromptPresetDialog,
                onShowBuiltinModelsPicker = pickerActions::showBuiltinModelsPicker,
                onShowCustomModelsPicker = pickerActions::showCustomModelsPicker,
                onFetchModels = ::fetchModels,
                onOpenUrl = { url ->
                    if (!openUrlSafely(context, url)) {
                        showAiMessage(R.string.error_open_browser)
                    }
                },
                onTestLlmCall = ::testLlmCall
            )
        )
    }
}

private fun openUrlSafely(context: Context, url: String): Boolean {
    if (url.isBlank()) return true
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return true
    } catch (t: Throwable) {
        if (BuildConfig.DEBUG) Log.d(AI_TAG, "Failed to open url", t)
        return false
    }
}
