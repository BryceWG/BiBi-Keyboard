/** Translation settings: global switch, provider/language choices and local model operations. */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.brycewg.asrkb.R
import com.brycewg.asrkb.translation.TranslationDownloadService
import com.brycewg.asrkb.translation.TranslationVendor
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButton
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButtonRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheet
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.settingsChoiceSheetState
import com.brycewg.asrkb.ui.settings.compose.components.settingsFeatureExplainerDialogState
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import com.brycewg.asrkb.ui.settings.translation.TranslationSettingsViewModel

@Composable
internal fun TranslationSettingsScreen(uiMode: BibiUiMode, onBack: () -> Unit) {
    val context = LocalContext.current
    val model: TranslationSettingsViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val download by TranslationDownloadService.state.collectAsStateWithLifecycle()
    val locale = context.resources.configuration.locales[0]
    val offline = state.vendor.offline
    val groupCount = if (offline) 4 else 2
    val route = if (offline) model.catalog.route(state.source, state.target) else emptyList()
    val missing = route.filter { it.id !in state.installed }
    val ready = route.isNotEmpty() && missing.isEmpty()
    val busy = state.testing || (offline && (state.loading || download.running))
    var choice by remember { mutableStateOf<SettingsChoiceSheetState?>(null) }
    var feature by remember { mutableStateOf<SettingsFeatureExplainerDialogState?>(null) }
    var showPackages by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<SettingsMessageDialogState?>(null) }
    fun name(tag: String) = if (tag == "auto") context.getString(R.string.translation_auto_language) else model.catalog.languageName(tag, locale)

    LaunchedEffect(download.running) { if (!download.running) model.refresh() }

    SettingsDetailScaffold(uiMode = uiMode, titleRes = R.string.title_translation_settings, onBack = onBack) { padding, scroll ->
        SettingsChoiceSheet(state = choice, uiMode = uiMode, onDismiss = { choice = null })
        SettingsFeatureExplainerDialog(state = feature, uiMode = uiMode, onDismiss = { feature = null })
        if (showPackages) {
            TranslationPackagesSheet(
                uiMode = uiMode,
                packs = state.packs,
                enabled = !busy,
                languageName = { name(it) },
                onDismiss = { showPackages = false },
                onDelete = { pack ->
                    message = SettingsMessageDialogState(
                        title = context.getString(R.string.translation_delete_packages),
                        message = context.getString(R.string.translation_delete_one_confirm, "${name(pack.source)} → ${name(pack.target)}"),
                        confirmText = context.getString(android.R.string.ok),
                        dismissText = context.getString(android.R.string.cancel),
                        onConfirm = { model.delete(listOf(com.brycewg.asrkb.translation.TranslationModelCatalog.Model(pack.source, pack.target, pack.bytes))) }
                    )
                }
            )
        }
        SettingsMessageDialog(state = message, uiMode = uiMode, onDismiss = { message = null })
        SettingsMessageDialog(
            state = state.testResult?.let {
                SettingsMessageDialogState(
                    title = context.getString(R.string.translation_test),
                    message = it,
                    confirmText = context.getString(android.R.string.ok)
                )
            },
            uiMode = uiMode,
            onDismiss = model::dismissTestResult
        )
        SettingsLazyColumn(
            uiMode = uiMode,
            modifier = Modifier.fillMaxSize(),
            miuixScrollModifier = scroll,
            contentPadding = SettingsLayoutMetrics.pageContentPadding(padding),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
        ) {
            item("translation_switch") {
                AsrSection(uiMode = uiMode, titleRes = R.string.translation_feature) {
                    SettingsPreference(
                        entry = SettingsEntry.Switch(
                            id = "translation_enabled",
                            titleRes = R.string.label_translation_enabled,
                            checked = state.enabled,
                            enabled = !offline || model.supported || state.enabled,
                            onCheckedChange = { checked ->
                                feature = settingsFeatureExplainerDialogState(
                                    context = context,
                                    titleRes = R.string.label_translation_enabled,
                                    offDescRes = R.string.translation_feature_off,
                                    onDescRes = R.string.translation_feature_on,
                                    currentState = state.enabled,
                                    preferenceKey = "translation_explained",
                                    onConfirm = { model.setEnabled(checked) }
                                )
                            }
                        ),
                        index = 0,
                        count = 1
                    )
                }
            }
            item("translation_provider") {
                AsrSection(uiMode = uiMode, titleRes = R.string.translation_service_config) {
                    AsrValuePreference(
                        titleRes = R.string.translation_vendor,
                        value = stringResource(state.vendor.titleRes),
                        uiMode = uiMode,
                        index = 0,
                        count = groupCount,
                        onClick = {
                            choice = settingsChoiceSheetState(
                                context.getString(R.string.translation_vendor),
                                TranslationVendor.entries.map { context.getString(it.titleRes) },
                                TranslationVendor.entries.indexOf(state.vendor)
                            ) { index ->
                                model.setVendor(TranslationVendor.entries[index])
                                choice = null
                            }
                        }
                    )
                    TranslationLanguageRow(
                        uiMode = uiMode,
                        source = name(state.source),
                        target = name(state.target),
                        enabled = !busy,
                        canSwap = state.source != "auto" && state.source in model.targets(state.target),
                        groupCount = groupCount,
                        onSwap = { model.setLanguages(state.target, state.source) },
                        onSourceClick = {
                            if (!busy) {
                                val languages = model.sources().filter { it != "auto" }.sortedBy { name(it) }.let { if (offline) it else listOf("auto") + it }
                                choice = settingsChoiceSheetState(context.getString(R.string.translation_source_language), languages.map { name(it) }, languages.indexOf(state.source)) { index ->
                                    val source = languages[index]
                                    val targets = model.targets(source)
                                    model.setLanguages(source, if (state.target in targets) state.target else targets.first())
                                    choice = null
                                }
                            }
                        },
                        onTargetClick = {
                            if (!busy) {
                                val languages = model.targets(state.source).sortedBy { name(it) }
                                choice = settingsChoiceSheetState(context.getString(R.string.translation_target_language), languages.map { name(it) }, languages.indexOf(state.target)) { index ->
                                    model.setLanguages(state.source, languages[index])
                                    choice = null
                                }
                            }
                        }
                    )
                    if (offline) {
                        SettingsPreference(
                            entry = SettingsEntry.Switch(
                                id = "translation_preload",
                                titleRes = R.string.translation_preload,
                                checked = state.preload,
                                enabled = model.supported,
                                onCheckedChange = { checked ->
                                    feature = settingsFeatureExplainerDialogState(
                                        context = context,
                                        titleRes = R.string.translation_preload,
                                        offDescRes = R.string.translation_preload_off,
                                        onDescRes = R.string.translation_preload_on,
                                        currentState = state.preload,
                                        preferenceKey = "translation_preload_explained",
                                        onConfirm = { model.setPreload(checked) }
                                    )
                                }
                            ),
                            index = 2,
                            count = 4
                        )
                        AsrDropdownPreference(
                            id = "translation_keep_alive",
                            titleRes = R.string.translation_keep_alive,
                            options = listOf(
                                com.brycewg.asrkb.ui.settings.compose.model.DropdownOption("0", stringResource(R.string.translation_keep_alive_immediate)),
                                com.brycewg.asrkb.ui.settings.compose.model.DropdownOption("5", stringResource(R.string.sv_keep_alive_5m)),
                                com.brycewg.asrkb.ui.settings.compose.model.DropdownOption("15", stringResource(R.string.sv_keep_alive_15m)),
                                com.brycewg.asrkb.ui.settings.compose.model.DropdownOption("30", stringResource(R.string.sv_keep_alive_30m)),
                                com.brycewg.asrkb.ui.settings.compose.model.DropdownOption("-1", stringResource(R.string.translation_keep_alive_always))
                            ),
                            selectedOptionId = state.keepAliveMinutes.toString(),
                            index = 3,
                            count = 4,
                            onSelectedOptionChange = { model.setKeepAlive(it.toInt()) }
                        )
                        val status = when {
                            !model.supported -> stringResource(R.string.translation_unsupported_device)
                            state.error != null -> state.error.orEmpty()
                            download.running -> stringResource(R.string.translation_download_progress, download.percent)
                            download.error != null -> download.error.orEmpty()
                            state.loading -> stringResource(R.string.translation_checking)
                            ready -> stringResource(R.string.translation_ready)
                            else -> stringResource(R.string.translation_required_download, missing.size, missing.sumOf { it.bytes } / 1_000_000)
                        }
                        AsrBodyText(uiMode = uiMode, text = status)
                        if (!ready || download.running) {
                            SettingsActionButtonRow(uiMode = uiMode) {
                                SettingsActionButton(
                                    uiMode = uiMode,
                                    text = stringResource(
                                        when {
                                            download.running -> R.string.translation_cancel_download
                                            else -> R.string.translation_download_packages
                                        }
                                    ),
                                    enabled = model.supported && !state.loading,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        when {
                                            download.running -> TranslationDownloadService.cancel(context)
                                            else -> TranslationDownloadService.start(context)
                                        }
                                    }
                                )
                            }
                        }
                        SettingsPreference(
                            entry = SettingsEntry.Action(
                                id = "translation_manage_packages",
                                titleRes = R.string.translation_manage_packages,
                                enabled = !state.loading,
                                onClick = { showPackages = true }
                            ),
                            index = 0,
                            count = 1
                        )
                    } else {
                        AsrBodyText(uiMode = uiMode, text = stringResource(R.string.translation_online_notice))
                    }
                    SettingsActionButtonRow(uiMode = uiMode) {
                        SettingsActionButton(
                            uiMode = uiMode,
                            text = stringResource(if (state.testing) R.string.translation_test_running else R.string.translation_test),
                            enabled = !busy && (!offline || model.supported),
                            modifier = Modifier.weight(1f),
                            onClick = model::testTranslation
                        )
                    }
                }
            }
        }
    }
}
