/**
 * 新手引导 Compose 分页内容。
 *
 * 归属模块：ui/setup/compose
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.setup.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Stars
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.AsrRecommendationLanguageControl
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.state.rememberAsrRecommendationLanguageState
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.Switch as MiuixSwitch
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun OnboardingPermissionsPage(
    groups: List<OnboardingPermissionGroup>,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = SettingsLayoutMetrics.PageContentPadding
) {
    OnboardingPage(uiMode = uiMode, modifier = modifier, contentPadding = contentPadding) {
        OnboardingHeader(
            title = stringResource(R.string.onboarding_permissions_title),
            description = stringResource(R.string.onboarding_permissions_desc),
            uiMode = uiMode
        )
        groups.forEach { group ->
            if (group.items.isNotEmpty()) {
                PermissionGroup(group = group, uiMode = uiMode)
            }
        }
    }
}

@Composable
internal fun OnboardingAsrChoicePage(
    selected: OnboardingAsrChoice,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = SettingsLayoutMetrics.PageContentPadding,
    onSelected: (OnboardingAsrChoice) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    val recommendationLanguageState = rememberAsrRecommendationLanguageState(prefs)
    OnboardingPage(uiMode = uiMode, modifier = modifier, contentPadding = contentPadding) {
        OnboardingHeader(
            title = stringResource(R.string.onboarding_asr_title),
            description = stringResource(R.string.onboarding_asr_desc),
            uiMode = uiMode,
            beforeDescription = {
                AsrRecommendationLanguageControl(
                    state = recommendationLanguageState,
                    uiMode = uiMode,
                    prominent = true
                )
            }
        )
        AsrChoiceCard(
            title = stringResource(R.string.model_guide_option_sf_free),
            description = stringResource(R.string.model_guide_option_sf_free_desc),
            action = stringResource(R.string.model_guide_option_sf_free_action),
            icon = Icons.Rounded.Stars,
            selected = selected == OnboardingAsrChoice.SiliconFlowFree,
            uiMode = uiMode,
            onClick = { onSelected(OnboardingAsrChoice.SiliconFlowFree) }
        )
        AsrChoiceCard(
            title = stringResource(R.string.model_guide_option_local),
            description = stringResource(R.string.model_guide_option_local_desc),
            action = stringResource(R.string.model_guide_option_local_action),
            icon = Icons.Rounded.PhoneAndroid,
            selected = selected == OnboardingAsrChoice.LocalModel,
            uiMode = uiMode,
            onClick = { onSelected(OnboardingAsrChoice.LocalModel) }
        )
        AsrChoiceCard(
            title = stringResource(R.string.model_guide_option_custom),
            description = stringResource(R.string.model_guide_option_custom_desc),
            action = stringResource(R.string.model_guide_option_custom_action),
            icon = Icons.Rounded.Tune,
            selected = selected == OnboardingAsrChoice.CustomService,
            uiMode = uiMode,
            onClick = { onSelected(OnboardingAsrChoice.CustomService) }
        )
    }
}

@Composable
internal fun OnboardingPrivacyPage(
    checked: Boolean,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = SettingsLayoutMetrics.PageContentPadding,
    onCheckedChange: (Boolean) -> Unit
) {
    OnboardingPage(uiMode = uiMode, modifier = modifier, contentPadding = contentPadding) {
        OnboardingHeader(
            title = stringResource(R.string.onboarding_privacy_title),
            description = stringResource(R.string.onboarding_privacy_desc),
            uiMode = uiMode
        )
        OnboardingCard(uiMode = uiMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCheckedChange(!checked) }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconByMode(Icons.Rounded.Security, uiMode)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    BodyText(
                        text = stringResource(R.string.label_data_collection),
                        uiMode = uiMode,
                        strong = true
                    )
                    Spacer(Modifier.height(4.dp))
                    SupportingText(
                        text = stringResource(R.string.onboarding_privacy_hint),
                        uiMode = uiMode
                    )
                }
                when (uiMode) {
                    BibiUiMode.Material -> Switch(
                        checked = checked,
                        onCheckedChange = onCheckedChange
                    )

                    BibiUiMode.Miuix -> MiuixSwitch(
                        checked = checked,
                        onCheckedChange = onCheckedChange
                    )
                }
            }
        }
    }
}

@Composable
internal fun OnboardingLinksPage(
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = SettingsLayoutMetrics.PageContentPadding,
    onOpenProject: () -> Unit,
    onOpenWebsite: () -> Unit,
    onOpenDocs: () -> Unit,
    onOpenPro: () -> Unit
) {
    OnboardingPage(uiMode = uiMode, modifier = modifier, contentPadding = contentPadding) {
        OnboardingHeader(
            title = stringResource(R.string.onboarding_links_title),
            description = stringResource(R.string.onboarding_links_desc),
            uiMode = uiMode
        )
        LinkButton(
            text = stringResource(R.string.onboarding_links_project),
            icon = Icons.Rounded.Code,
            uiMode = uiMode,
            onClick = onOpenProject
        )
        LinkButton(
            text = stringResource(R.string.onboarding_links_website),
            icon = Icons.Rounded.OpenInBrowser,
            uiMode = uiMode,
            onClick = onOpenWebsite
        )
        LinkButton(
            text = stringResource(R.string.onboarding_links_docs),
            icon = Icons.Rounded.Description,
            uiMode = uiMode,
            onClick = onOpenDocs
        )
        LinkButton(
            text = stringResource(R.string.onboarding_links_pro),
            icon = Icons.Rounded.Stars,
            uiMode = uiMode,
            onClick = onOpenPro
        )
    }
}

@Composable
private fun OnboardingPage(
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = SettingsLayoutMetrics.PageContentPadding,
    content: @Composable () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .then(modifier),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 1.dp),
                verticalArrangement = Arrangement.spacedBy(if (uiMode == BibiUiMode.Miuix) 12.dp else 10.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun OnboardingHeader(
    title: String,
    description: String,
    uiMode: BibiUiMode,
    beforeDescription: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (uiMode) {
            BibiUiMode.Material -> {
                Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                beforeDescription?.invoke()
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            BibiUiMode.Miuix -> {
                MiuixText(text = title, style = MiuixTheme.textStyles.title2)
                beforeDescription?.invoke()
                MiuixText(
                    text = description,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2
                )
            }
        }
    }
}

@Composable
private fun PermissionGroup(group: OnboardingPermissionGroup, uiMode: BibiUiMode) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(modifier = Modifier.padding(horizontal = 8.dp)) {
            BodyText(text = stringResource(group.titleRes), uiMode = uiMode, strong = true)
            SupportingText(text = stringResource(group.descriptionRes), uiMode = uiMode)
        }
        OnboardingCard(uiMode = uiMode) {
            Column {
                group.items.forEach { item ->
                    PermissionRow(item = item, uiMode = uiMode)
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(item: OnboardingPermissionItem, uiMode: BibiUiMode) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconByMode(
            imageVector = if (item.granted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
            uiMode = uiMode,
            tint = when {
                uiMode == BibiUiMode.Material && item.granted -> MaterialTheme.colorScheme.primary
                uiMode == BibiUiMode.Material -> MaterialTheme.colorScheme.onSurfaceVariant
                item.granted -> MiuixTheme.colorScheme.primary
                else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
            }
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            BodyText(text = stringResource(item.titleRes), uiMode = uiMode, strong = true)
            SupportingText(text = stringResource(item.descriptionRes), uiMode = uiMode)
            SupportingText(
                text = stringResource(
                    if (item.granted) {
                        R.string.onboarding_permission_status_granted
                    } else {
                        R.string.onboarding_permission_status_missing
                    }
                ),
                uiMode = uiMode
            )
        }
        PermissionAction(
            granted = item.granted,
            uiMode = uiMode,
            onClick = item.onRequest
        )
    }
}

@Composable
private fun PermissionAction(granted: Boolean, uiMode: BibiUiMode, onClick: () -> Unit) {
    val text = stringResource(
        if (granted) {
            R.string.onboarding_permission_btn_enabled
        } else {
            R.string.onboarding_permission_btn_go_enable
        }
    )
    when (uiMode) {
        BibiUiMode.Material -> OutlinedButton(
            onClick = onClick,
            enabled = !granted
        ) {
            Text(text)
        }

        BibiUiMode.Miuix -> MiuixTextButton(
            text = text,
            onClick = onClick,
            enabled = !granted,
            colors = MiuixButtonDefaults.textButtonColorsPrimary()
        )
    }
}

@Composable
private fun AsrChoiceCard(
    title: String,
    description: String,
    action: String,
    icon: ImageVector,
    selected: Boolean,
    uiMode: BibiUiMode,
    onClick: () -> Unit
) {
    val borderColor = when {
        uiMode == BibiUiMode.Material && selected -> MaterialTheme.colorScheme.primary
        uiMode == BibiUiMode.Material -> Color.Transparent
        selected -> MiuixTheme.colorScheme.primary
        else -> Color.Transparent
    }
    OnboardingCard(
        uiMode = uiMode,
        border = BorderStroke(1.dp, borderColor),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconByMode(icon, uiMode)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BodyText(title, uiMode, strong = true)
                SupportingText(description, uiMode)
                SupportingText(action, uiMode)
            }
            IconByMode(
                imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                uiMode = uiMode,
                tint = when {
                    uiMode == BibiUiMode.Material && selected -> MaterialTheme.colorScheme.primary
                    uiMode == BibiUiMode.Material -> MaterialTheme.colorScheme.onSurfaceVariant
                    selected -> MiuixTheme.colorScheme.primary
                    else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
                }
            )
        }
    }
}

@Composable
private fun LinkButton(
    text: String,
    icon: ImageVector,
    uiMode: BibiUiMode,
    onClick: () -> Unit
) {
    when (uiMode) {
        BibiUiMode.Material -> Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.filledTonalButtonColors()
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(text)
        }

        BibiUiMode.Miuix -> MiuixButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = MiuixButtonDefaults.buttonColorsPrimary()
        ) {
            MiuixIcon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            MiuixText(text = text, style = MiuixTheme.textStyles.button)
        }
    }
}

@Composable
private fun OnboardingCard(
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    content: @Composable () -> Unit
) {
    when (uiMode) {
        BibiUiMode.Material -> Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = border,
            content = content
        )

        BibiUiMode.Miuix -> MiuixCard(modifier = modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun IconByMode(
    imageVector: ImageVector,
    uiMode: BibiUiMode,
    tint: Color? = null
) {
    when (uiMode) {
        BibiUiMode.Material -> Icon(
            imageVector = imageVector,
            contentDescription = null,
            tint = tint ?: MaterialTheme.colorScheme.primary
        )

        BibiUiMode.Miuix -> MiuixIcon(
            imageVector = imageVector,
            contentDescription = null,
            tint = tint ?: MiuixTheme.colorScheme.primary
        )
    }
}

@Composable
private fun BodyText(text: String, uiMode: BibiUiMode, strong: Boolean = false) {
    when (uiMode) {
        BibiUiMode.Material -> Text(
            text = text,
            fontWeight = if (strong) FontWeight.SemiBold else null,
            style = MaterialTheme.typography.bodyLarge
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            fontWeight = if (strong) FontWeight.Medium else null,
            style = MiuixTheme.textStyles.body1
        )
    }
}

@Composable
private fun SupportingText(text: String, uiMode: BibiUiMode) {
    when (uiMode) {
        BibiUiMode.Material -> Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.footnote1
        )
    }
}
