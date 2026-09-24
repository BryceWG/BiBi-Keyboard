/**
 * Compose 备份与同步设置页。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.brycewg.asrkb.R
import com.brycewg.asrkb.clipboard.ClipboardSyncRuntimeService
import com.brycewg.asrkb.ime.AsrKeyboardService
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrAccessibilityService
import com.brycewg.asrkb.ui.settings.backup.WebDavBackupHelper
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsActionController
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "BackupSettingsScreen"

@Composable
fun BackupSettingsScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit,
    actions: SettingsActionController
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    val scope = rememberCoroutineScope()

    var webdavUrl by remember { mutableStateOf(prefs.webdavUrl) }
    var webdavUsername by remember { mutableStateOf(prefs.webdavUsername) }
    var webdavPassword by remember { mutableStateOf(prefs.webdavPassword) }
    var busyAction by remember { mutableStateOf<BackupBusyAction?>(null) }
    var messageDialog by remember { mutableStateOf<SettingsMessageDialogState?>(null) }

    fun showBackupMessage(message: String) {
        messageDialog = SettingsMessageDialogState(
            title = context.getString(R.string.title_backup_settings),
            message = message,
            confirmText = context.getString(android.R.string.ok)
        )
    }

    fun reloadWebdavState() {
        webdavUrl = prefs.webdavUrl
        webdavUsername = prefs.webdavUsername
        webdavPassword = prefs.webdavPassword
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                busyAction = BackupBusyAction.FileExport
                showBackupMessage(exportSettings(context, prefs, uri).message)
                busyAction = null
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                busyAction = BackupBusyAction.FileImport
                val result = importSettings(context, prefs, uri)
                showBackupMessage(result.message)
                if (result.success) {
                    reloadWebdavState()
                }
                busyAction = null
            }
        }
    }

    BackupScaffold(uiMode = uiMode, onBack = onBack) { innerPadding, scrollModifier ->
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
            item("file") {
                BackupSection(uiMode = uiMode, titleRes = R.string.section_file_backup) {
                    BackupActionPreference(
                        id = "backup_export_file",
                        titleRes = R.string.btn_export_to_file,
                        icon = Icons.Rounded.Upload,
                        enabled = busyAction == null,
                        index = 0,
                        count = 2,
                        onClick = {
                            exportLauncher.launch(buildBackupFileName())
                        }
                    )
                    BackupActionPreference(
                        id = "backup_import_file",
                        titleRes = R.string.btn_import_from_file,
                        icon = Icons.Rounded.Download,
                        enabled = busyAction == null,
                        index = 1,
                        count = 2,
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain"))
                        }
                    )
                }
            }

            item("webdav") {
                BackupSection(uiMode = uiMode, titleRes = R.string.section_webdav_sync) {
                    BackupTextField(
                        value = webdavUrl,
                        onValueChange = {
                            webdavUrl = it
                            prefs.webdavUrl = it
                        },
                        label = stringResource(R.string.hint_webdav_url),
                        uiMode = uiMode,
                        keyboardType = KeyboardType.Uri,
                        index = 0,
                        count = 5
                    )
                    BackupTextField(
                        value = webdavUsername,
                        onValueChange = {
                            webdavUsername = it
                            prefs.webdavUsername = it
                        },
                        label = stringResource(R.string.hint_webdav_username),
                        uiMode = uiMode,
                        keyboardType = KeyboardType.Text,
                        index = 1,
                        count = 5
                    )
                    BackupTextField(
                        value = webdavPassword,
                        onValueChange = {
                            webdavPassword = it
                            prefs.webdavPassword = it
                        },
                        label = stringResource(R.string.hint_webdav_password),
                        uiMode = uiMode,
                        keyboardType = KeyboardType.Password,
                        password = true,
                        index = 2,
                        count = 5
                    )
                    BackupActionPreference(
                        id = "backup_webdav_upload",
                        titleRes = R.string.btn_webdav_upload,
                        icon = Icons.Rounded.Upload,
                        enabled = busyAction == null,
                        index = 3,
                        count = 5,
                        onClick = {
                            scope.launch {
                                busyAction = BackupBusyAction.WebdavUpload
                                showBackupMessage(uploadToWebdav(context, prefs).message)
                                busyAction = null
                            }
                        }
                    )
                    BackupActionPreference(
                        id = "backup_webdav_download",
                        titleRes = R.string.btn_webdav_download,
                        icon = Icons.Rounded.Download,
                        enabled = busyAction == null,
                        index = 4,
                        count = 5,
                        onClick = {
                            scope.launch {
                                busyAction = BackupBusyAction.WebdavDownload
                                val result = downloadFromWebdav(context, prefs)
                                showBackupMessage(result.message)
                                if (result.success) {
                                    reloadWebdavState()
                                }
                                busyAction = null
                            }
                        }
                    )
                    BackupBodyText(stringResource(R.string.tip_backup_contains_secrets), uiMode)
                }
            }
        }
    }
}

private enum class BackupBusyAction {
    FileExport,
    FileImport,
    WebdavUpload,
    WebdavDownload
}

private data class BackupImportOutcome(
    val imported: Boolean,
    val errorReason: String?
)

private data class BackupOperationResult(
    val success: Boolean,
    val message: String
)

private fun buildBackupFileName(): String = "asr_keyboard_settings_" +
    SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date()) +
    ".json"

private suspend fun exportSettings(
    context: Context,
    prefs: Prefs,
    uri: Uri
): BackupOperationResult {
    val exported = withContext(Dispatchers.IO) {
        runCatching {
            val jsonString = prefs.exportJsonString()
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(jsonString.toByteArray(Charsets.UTF_8))
                os.flush()
            } ?: error("Output stream is null")
        }
    }
    return if (exported.isSuccess) {
        val name = uri.lastPathSegment ?: "settings.json"
        Log.d(TAG, "Settings exported successfully to $uri")
        BackupOperationResult(
            success = true,
            message = context.getString(R.string.toast_export_success, name)
        )
    } else {
        Log.e(TAG, "Failed to export settings", exported.exceptionOrNull())
        BackupOperationResult(
            success = false,
            message = context.getString(R.string.toast_export_failed)
        )
    }
}

private suspend fun importSettings(
    context: Context,
    prefs: Prefs,
    uri: Uri
): BackupOperationResult {
    val imported = withContext(Dispatchers.IO) {
        runCatching {
            val json = context.contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?: ""
            prefs.importJsonString(json)
        }
    }
    return if (imported.getOrDefault(false)) {
        context.refreshImeUi()
        Log.d(TAG, "Settings imported successfully from $uri")
        BackupOperationResult(
            success = true,
            message = context.getString(R.string.toast_import_success)
        )
    } else {
        imported.exceptionOrNull()?.let { Log.e(TAG, "Failed to import settings", it) }
        BackupOperationResult(
            success = false,
            message = context.getString(R.string.toast_import_failed)
        )
    }
}

private suspend fun uploadToWebdav(
    context: Context,
    prefs: Prefs
): BackupOperationResult {
    val rawUrl = prefs.webdavUrl.trim()
    if (rawUrl.isEmpty()) {
        return BackupOperationResult(
            success = false,
            message = context.getString(R.string.toast_webdav_url_required)
        )
    }

    val result = WebDavBackupHelper.uploadSettingsWithStatus(context, prefs)
    return if (result is WebDavBackupHelper.UploadResult.Success) {
        BackupOperationResult(
            success = true,
            message = context.getString(R.string.toast_webdav_upload_success)
        )
    } else {
        val error = result as? WebDavBackupHelper.UploadResult.Error
        val reason = buildWebdavErrorReason(error?.statusCode, error?.responsePhrase)
        BackupOperationResult(
            success = false,
            message = context.getString(R.string.toast_webdav_upload_failed, reason)
        )
    }
}

private suspend fun downloadFromWebdav(
    context: Context,
    prefs: Prefs
): BackupOperationResult {
    val rawUrl = prefs.webdavUrl.trim()
    if (rawUrl.isEmpty()) {
        return BackupOperationResult(
            success = false,
            message = context.getString(R.string.toast_webdav_url_required)
        )
    }

    val outcome = withContext(Dispatchers.IO) {
        when (val result = WebDavBackupHelper.downloadSettingsWithStatus(prefs)) {
            is WebDavBackupHelper.DownloadResult.Success -> {
                val imported = prefs.importJsonString(result.json)
                BackupImportOutcome(
                    imported = imported,
                    errorReason = if (imported) null else context.getString(R.string.toast_import_failed)
                )
            }

            is WebDavBackupHelper.DownloadResult.NotFound -> BackupImportOutcome(
                imported = false,
                errorReason = context.getString(R.string.toast_webdav_backup_not_found)
            )

            is WebDavBackupHelper.DownloadResult.Error -> BackupImportOutcome(
                imported = false,
                errorReason = buildWebdavErrorReason(result.statusCode, result.responsePhrase)
            )
        }
    }

    return if (outcome.imported) {
        context.refreshImeUi()
        BackupOperationResult(
            success = true,
            message = context.getString(R.string.toast_webdav_download_success)
        )
    } else {
        val reasonText = outcome.errorReason ?: context.getString(R.string.toast_import_failed)
        BackupOperationResult(
            success = false,
            message = context.getString(R.string.toast_webdav_download_failed, reasonText)
        )
    }
}

private fun Context.refreshImeUi() {
    try {
        ClipboardSyncRuntimeService.notifyConfigChanged(this)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to notify clipboard sync runtime", e)
    }
    try {
        sendBroadcast(Intent(AsrKeyboardService.ACTION_REFRESH_IME_UI))
    } catch (e: Exception) {
        Log.e(TAG, "Failed to send refresh broadcast", e)
    }
    // 备份可能改动摇一摇开关；偏好已写入后按当前值同步加速度计注册。
    AsrAccessibilityService.refreshShakeSensor()
}

private fun buildWebdavErrorReason(
    statusCode: Int?,
    responsePhrase: String?
): String = when {
    statusCode != null && !responsePhrase.isNullOrBlank() -> "$statusCode $responsePhrase"
    statusCode != null -> statusCode.toString()
    !responsePhrase.isNullOrBlank() -> responsePhrase
    else -> "HTTP"
}
