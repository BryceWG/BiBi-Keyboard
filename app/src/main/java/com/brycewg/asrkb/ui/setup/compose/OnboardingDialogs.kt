/**
 * 新手引导的本地模型下载源选择。
 *
 * 归属模块：ui/setup/compose
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.setup.compose

import androidx.compose.runtime.Composable
import com.brycewg.asrkb.ui.DownloadSourceOption
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDownloadSourceSheet
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode

@Composable
internal fun OnboardingDialogHost(
    state: OnboardingDialogState,
    uiMode: BibiUiMode,
    onDismiss: () -> Unit,
    onSelectDownloadSource: (DownloadSourceOption) -> Unit
) {
    when (state) {
        OnboardingDialogState.None -> Unit
        is OnboardingDialogState.DownloadSources -> SettingsDownloadSourceSheet(
            options = state.options,
            uiMode = uiMode,
            onDismiss = onDismiss,
            onSelect = onSelectDownloadSource
        )
    }
}
