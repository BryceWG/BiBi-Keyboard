/**
 * Compose 关于页。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLongTextDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLongTextDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreferenceGroup
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsActionController
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry

@Composable
fun AboutSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    onOpenPro: () -> Unit,
    actions: SettingsActionController
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val appContext = context.applicationContext
    val prefs = remember(appContext) { Prefs(appContext) }
    val aboutInfo = remember(appContext) { buildAboutInfo(context) }
    val latestExitInfo = remember(appContext) { buildLatestExitInfo(context) }
    var autoUpdateCheck by remember { mutableStateOf(prefs.autoUpdateCheckEnabled) }
    var debugRecording by remember { mutableStateOf(DebugLogManager.isRecording()) }
    var licensesDialog by remember { mutableStateOf<SettingsLongTextDialogState?>(null) }

    AboutScaffold(uiMode = uiMode, onBack = onBack) { innerPadding, scrollModifier ->
        SettingsLazyColumn(
            uiMode = uiMode,
            modifier = Modifier.fillMaxSize(),
            miuixScrollModifier = scrollModifier,
            contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
        ) {
            item("app") {
                AboutSection(uiMode = uiMode) {
                    AboutAppIntro(
                        appName = aboutInfo.appName,
                        version = aboutInfo.version,
                        packageName = aboutInfo.packageName,
                        description = stringResource(R.string.about_desc),
                        uiMode = uiMode
                    )
                }
            }

            item("links") {
                AboutSection(
                    uiMode = uiMode,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    SettingsPreferenceGroup(
                        SettingsEntry.Switch(
                            id = "about_auto_update_check",
                            titleRes = R.string.about_auto_update_check,
                            checked = autoUpdateCheck,
                            onCheckedChange = {
                                autoUpdateCheck = it
                                prefs.autoUpdateCheckEnabled = it
                            }
                        ),
                        SettingsEntry.Action(
                            id = "about_project",
                            titleRes = R.string.about_open_github,
                            icon = Icons.Rounded.Code,
                            onClick = { actions.openUrl(R.string.about_project_url) }
                        ),
                        SettingsEntry.Action(
                            id = "about_website",
                            titleRes = R.string.about_open_website,
                            icon = Icons.Rounded.OpenInBrowser,
                            onClick = { actions.openUrl(R.string.about_website_url) }
                        ),
                        SettingsEntry.Action(
                            id = "about_docs",
                            titleRes = R.string.about_open_docs,
                            icon = Icons.AutoMirrored.Rounded.Article,
                            onClick = { actions.openUrl(R.string.about_docs_url) }
                        ),
                        SettingsEntry.Action(
                            id = "about_pro",
                            titleRes = R.string.about_btn_learn_pro,
                            icon = Icons.Rounded.WorkspacePremium,
                            onClick = onOpenPro
                        )
                    )
                }
            }

            item("acknowledgements") {
                AboutSection(uiMode = uiMode, titleRes = R.string.about_acknowledgements_title) {
                    AboutText(stringResource(R.string.about_acknowledgements_desc), uiMode)
                    AboutAcknowledgement(R.string.about_sherpa_onnx_title, R.string.about_sherpa_onnx_desc, uiMode)
                    AboutAcknowledgement(R.string.about_syncclipboard_title, R.string.about_syncclipboard_desc, uiMode)
                    AboutAcknowledgement(R.string.about_phosphor_title, R.string.about_phosphor_desc, uiMode)
                    AboutAcknowledgement(R.string.about_miuix_title, R.string.about_miuix_desc, uiMode)
                    AboutAcknowledgement(R.string.about_wavelineview_title, R.string.about_wavelineview_desc, uiMode)
                    AboutAcknowledgement(R.string.about_silero_vad_title, R.string.about_silero_vad_desc, uiMode)
                    SettingsPreference(
                        SettingsEntry.Action(
                            id = "about_licenses",
                            titleRes = R.string.about_view_full_licenses,
                            onClick = {
                                actions.buildLicensesText()?.let { licensesText ->
                                    licensesDialog = SettingsLongTextDialogState(
                                        title = context.getString(R.string.about_licenses_dialog_title),
                                        text = licensesText,
                                        confirmText = context.getString(android.R.string.ok)
                                    )
                                }
                            }
                        )
                    )
                }
            }

            item("debug") {
                AboutSection(uiMode = uiMode, titleRes = R.string.about_debug_title) {
                    AboutText(stringResource(R.string.about_debug_desc), uiMode)
                    latestExitInfo?.let { latest ->
                        AboutText(latest, uiMode)
                    }
                    SettingsPreferenceGroup(
                        SettingsEntry.Action(
                            id = "about_debug_recording",
                            titleRes = if (debugRecording) {
                                R.string.btn_debug_stop_recording
                            } else {
                                R.string.btn_debug_start_recording
                            },
                            icon = if (debugRecording) Icons.Rounded.StopCircle else Icons.Rounded.BugReport,
                            onClick = {
                                debugRecording = actions.setDebugRecording(!debugRecording)
                            }
                        ),
                        SettingsEntry.Action(
                            id = "about_debug_export",
                            titleRes = R.string.btn_debug_export,
                            icon = Icons.Rounded.Share,
                            onClick = actions::exportDebugLog
                        )
                    )
                }
            }
        }
    }
    SettingsLongTextDialog(
        state = licensesDialog,
        uiMode = uiMode,
        onDismiss = { licensesDialog = null }
    )
}
