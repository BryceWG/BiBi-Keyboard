/** Provider-scoped installed translation packs and per-pack deletion actions. */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.translation.FoxletTranslationService
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsSheetScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSheetLazyColumn
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun TranslationPackagesSheet(
    uiMode: BibiUiMode,
    packs: List<FoxletTranslationService.InstalledPack>,
    enabled: Boolean,
    languageName: (String) -> String,
    onDelete: (FoxletTranslationService.InstalledPack) -> Unit,
    onDismiss: () -> Unit
) {
    val title = stringResource(R.string.translation_manage_packages)
    val content: @Composable () -> Unit = {
        SettingsSheetLazyColumn(uiMode = uiMode, contentPadding = PaddingValues(0.dp)) {
            if (packs.isEmpty()) {
                item { AsrBodyText(uiMode = uiMode, text = stringResource(R.string.translation_no_packages)) }
            }
            items(packs.sortedBy { languageName(it.source) + languageName(it.target) }, key = { "${it.id}-${it.version}" }) { pack ->
                val name = "${languageName(pack.source)} → ${languageName(pack.target)}"
                val details = stringResource(R.string.translation_pack_details, pack.bytes / 1_000_000, pack.version)
                val description = LocalContext.current.getString(R.string.translation_delete_pack_action, name)
                val haptic = LocalSettingsHapticTap.current
                val delete = {
                    haptic()
                    onDelete(pack)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(
                        if (uiMode == BibiUiMode.Miuix) BasicComponentDefaults.InsideMargin else PaddingValues(vertical = 12.dp)
                    ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        when (uiMode) {
                            BibiUiMode.Material -> {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            BibiUiMode.Miuix -> {
                                MiuixText(name, style = MiuixTheme.textStyles.body1)
                                MiuixText(details, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                    when (uiMode) {
                        BibiUiMode.Material -> IconButton(onClick = delete, enabled = enabled) {
                            Icon(Icons.Rounded.Delete, contentDescription = description)
                        }
                        BibiUiMode.Miuix -> MiuixIconButton(onClick = delete, enabled = enabled) {
                            MiuixIcon(Icons.Rounded.Delete, contentDescription = description)
                        }
                    }
                }
            }
        }
    }
    when (uiMode) {
        BibiUiMode.Material -> MaterialSettingsSheetScaffold(title = title, onDismiss = onDismiss, bottomPadding = 0.dp) { content() }
        BibiUiMode.Miuix -> {
            var show by remember { mutableStateOf(true) }
            OverlayDialog(
                show = show,
                onDismissRequest = { show = false },
                onDismissFinished = onDismiss,
                insideMargin = DpSize(0.dp, 0.dp)
            ) {
                MiuixText(
                    title,
                    modifier = Modifier.fillMaxWidth().padding(
                        top = SettingsLayoutMetrics.SheetDialogTitleTopPadding,
                        bottom = SettingsLayoutMetrics.SheetTitleBottomPadding
                    ),
                    style = MiuixTheme.textStyles.title4,
                    textAlign = TextAlign.Center
                )
                content()
            }
        }
    }
}
