/**
 * 供应商选择请求与动态推荐展示，复用通用单选弹层。
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.asr.AsrVendor
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.AsrRecommendationLanguages
import com.brycewg.asrkb.ui.settings.compose.components.AsrRecommendationLanguageControl
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceSheet
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.state.AsrRecommendationLanguageState

internal data class AsrVendorChoiceSheetRequest(
    @param:StringRes val titleResId: Int,
    val selectedVendor: AsrVendor,
    val onSelected: (AsrVendor) -> Unit
)

@Composable
internal fun AsrVendorChoiceSheet(
    request: AsrVendorChoiceSheetRequest?,
    prefs: Prefs,
    languageState: AsrRecommendationLanguageState,
    uiMode: BibiUiMode,
    onDismiss: () -> Unit
) {
    if (request == null) return
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val languageTag = AsrRecommendationLanguages.resolve(context, languageState.selectedTag)
    val sheet = remember(request, configuration, languageTag) {
        asrVendorChoiceSheetState(
            context = context,
            prefs = prefs,
            titleResId = request.titleResId,
            selectedVendor = request.selectedVendor,
            recommendationLanguage = languageTag,
            onSelected = request.onSelected
        ).copy(
            header = { mode ->
                AsrRecommendationLanguageControl(
                    state = languageState,
                    uiMode = mode,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        )
    }
    SettingsChoiceSheet(state = sheet, uiMode = uiMode, onDismiss = onDismiss)
}
