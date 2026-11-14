/**
 * 新手引导 Compose 页面模型。
 *
 * 归属模块：ui/setup/compose
 */
package com.brycewg.asrkb.ui.setup.compose

import androidx.annotation.StringRes
import com.brycewg.asrkb.ui.DownloadSourceOption

internal enum class OnboardingAsrChoice {
    SiliconFlowFree,
    LocalModel,
    CustomService
}

internal data class OnboardingPermissionItem(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val granted: Boolean,
    val onRequest: () -> Unit
)

internal data class OnboardingPermissionGroup(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
    val items: List<OnboardingPermissionItem>
)

internal sealed interface OnboardingDialogState {
    data object None : OnboardingDialogState
    data class DownloadSources(val options: List<DownloadSourceOption>) : OnboardingDialogState
}
