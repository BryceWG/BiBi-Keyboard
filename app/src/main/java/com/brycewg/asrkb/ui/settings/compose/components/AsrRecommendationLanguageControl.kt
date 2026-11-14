/**
 * 推荐语言提示与选择入口：引导页使用较大字号，与供应商列表共用相同选择。
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.AsrRecommendationLanguages
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.state.AsrRecommendationLanguageState
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AsrRecommendationLanguageControl(
    state: AsrRecommendationLanguageState,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    prominent: Boolean = false
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val languageTag = AsrRecommendationLanguages.resolve(context, state.selectedTag)
    val languageName = AsrRecommendationLanguages.displayName(context, languageTag)
    var showLanguages by rememberSaveable { mutableStateOf(false) }
    val template = stringResource(
        if (prominent) R.string.onboarding_asr_recommendation_language else R.string.asr_recommendation_language_summary
    )
    val primary = when (uiMode) {
        BibiUiMode.Material -> MaterialTheme.colorScheme.primary
        BibiUiMode.Miuix -> MiuixTheme.colorScheme.primary
    }
    val text = buildAnnotatedString {
        append(template.substringBefore("%1\$s"))
        withLink(
            LinkAnnotation.Clickable(
                tag = "asr_recommendation_language",
                styles = TextLinkStyles(
                    style = SpanStyle(color = primary, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.SemiBold)
                ),
                linkInteractionListener = { showLanguages = true }
            )
        ) {
            append(languageName)
        }
        append(template.substringAfter("%1\$s"))
    }
    when (uiMode) {
        BibiUiMode.Material -> MaterialText(
            text = text,
            modifier = modifier.fillMaxWidth(),
            style = if (prominent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            modifier = modifier.fillMaxWidth(),
            style = if (prominent) MiuixTheme.textStyles.title3 else MiuixTheme.textStyles.body1
        )
    }
    if (showLanguages) {
        val tags = remember(configuration, languageTag) {
            listOf("") + AsrRecommendationLanguages.options(context)
        }
        val sheet = remember(configuration, tags, state.selectedTag) {
            settingsChoiceSheetState(
                title = context.getString(R.string.asr_recommendation_language_title),
                items = tags.map { tag ->
                    if (tag.isEmpty()) context.getString(R.string.lang_follow_system) else AsrRecommendationLanguages.displayName(context, tag)
                },
                selectedIndex = tags.indexOf(if (state.selectedTag.isEmpty()) "" else languageTag),
                onSelected = { state.select(tags[it]) }
            )
        }
        SettingsChoiceSheet(state = sheet, uiMode = uiMode, onDismiss = { showLanguages = false })
    }
}
