/** 下载镜像编辑弹窗，归属 ui/settings/compose/screens。 */
package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.normalizeDownloadMirrorPrefix
import com.brycewg.asrkb.ui.DownloadSourceConfig
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsSheetScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogActionRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsTextField
import com.brycewg.asrkb.ui.settings.compose.components.rememberSettingsDialogExitController
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun OtherDownloadMirrorDialog(
    uiMode: BibiUiMode,
    mirrors: List<String>,
    initialPrefix: String,
    onMirrorsChange: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val adding = initialPrefix.isEmpty()
    var draft by rememberSaveable(initialPrefix) { mutableStateOf(initialPrefix) }
    val normalizedPrefix = normalizeDownloadMirrorPrefix(draft)
    val errorRes = when {
        draft.isBlank() -> null
        normalizedPrefix == null -> R.string.download_mirror_invalid
        DownloadSourceConfig.isBuiltInMirror(normalizedPrefix) ||
            (normalizedPrefix != initialPrefix && normalizedPrefix in mirrors) -> R.string.download_mirror_duplicate
        else -> null
    }
    val title = stringResource(if (adding) R.string.download_mirror_add else R.string.download_mirror_edit)

    @Composable
    fun Content(dismiss: (() -> Unit) -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = SettingsLayoutMetrics.SheetContentMaxHeight)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsTextField(
                uiMode = uiMode,
                value = draft,
                onValueChange = { draft = it },
                label = stringResource(R.string.download_mirror_address),
                keyboardType = KeyboardType.Uri,
                helper = errorRes?.let { stringResource(it) },
                materialContainer = false,
                applyEdgePadding = false,
                contentPadding = PaddingValues()
            )
            SettingsDialogActionRow(
                uiMode = uiMode,
                spacing = SettingsLayoutMetrics.ActionButtonSpacing,
                actions = listOfNotNull(
                    SettingsDialogAction(
                        text = stringResource(R.string.btn_cancel),
                        onClick = { dismiss {} }
                    ),
                    if (adding) {
                        null
                    } else {
                        SettingsDialogAction(
                            text = stringResource(R.string.btn_delete),
                            onClick = { dismiss { onMirrorsChange(mirrors - initialPrefix) } }
                        )
                    }
                )
            )
            SettingsDialogActionRow(
                uiMode = uiMode,
                actions = listOf(
                    SettingsDialogAction(
                        text = stringResource(R.string.btn_save),
                        enabled = normalizedPrefix != null && errorRes == null,
                        primary = true,
                        onClick = {
                            val prefix = requireNotNull(normalizedPrefix)
                            dismiss {
                                onMirrorsChange(
                                    if (adding) mirrors + prefix else mirrors.map { if (it == initialPrefix) prefix else it }
                                )
                            }
                        }
                    )
                )
            )
        }
    }

    when (uiMode) {
        BibiUiMode.Material -> MaterialSettingsSheetScaffold(title = title, onDismiss = onDismiss) { dismiss ->
            Content(dismiss)
        }

        BibiUiMode.Miuix -> {
            val exit = rememberSettingsDialogExitController()
            OverlayDialog(
                show = exit.show,
                title = title,
                insideMargin = DpSize(
                    SettingsLayoutMetrics.SettingsPreferenceHorizontalPadding,
                    SettingsLayoutMetrics.DialogContentBottomPadding
                ),
                onDismissRequest = { exit.dismiss(onDismiss) },
                onDismissFinished = exit::finish
            ) {
                Content { action ->
                    exit.dismiss {
                        action()
                        onDismiss()
                    }
                }
            }
        }
    }
}
