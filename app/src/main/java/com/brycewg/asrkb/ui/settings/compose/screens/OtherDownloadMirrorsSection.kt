/** 下载镜像管理，归属 ui/settings/compose/screens。 */
package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.components.SettingsHighlightContainer
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMaterialItemSurface
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry

@Composable
internal fun OtherDownloadMirrorsSection(
    uiMode: BibiUiMode,
    mirrors: List<String>,
    onOpenEditor: (String) -> Unit
) {
    val count = mirrors.size + 1
    OtherSection(uiMode = uiMode, titleRes = R.string.download_mirrors_section) {
        mirrors.forEachIndexed { index, prefix ->
            SettingsPreference(
                entry = SettingsEntry.Action(
                    id = "custom_download_mirror_$index",
                    titleRes = R.string.download_source_custom_mirror,
                    title = stringResource(R.string.download_source_custom_mirror, index + 1),
                    summary = prefix,
                    onClick = { onOpenEditor(prefix) }
                ),
                index = index,
                count = count
            )
        }
        val addMirror: @Composable () -> Unit = {
            SettingsHighlightContainer(entryId = "download_mirrors_section", uiMode = uiMode) {
                OtherButton(
                    text = stringResource(R.string.download_mirror_add),
                    uiMode = uiMode,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SettingsLayoutMetrics.ActionButtonRowHorizontalPadding)
                        .padding(top = SettingsLayoutMetrics.ActionButtonRowTopPadding),
                    onClick = { onOpenEditor("") },
                    leadingIcon = Icons.Rounded.Add,
                    padded = false
                )
            }
            OtherBodyText(stringResource(R.string.download_mirrors_summary), uiMode)
        }
        when (uiMode) {
            BibiUiMode.Material -> SettingsMaterialItemSurface(index = mirrors.size, count = count, content = addMirror)
            BibiUiMode.Miuix -> addMirror()
        }
    }
}
