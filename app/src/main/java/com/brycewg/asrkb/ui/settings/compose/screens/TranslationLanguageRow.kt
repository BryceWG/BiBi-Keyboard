/** Translation settings language selectors with a shared direction-swap action. */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CompareArrows
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMaterialItemSurface
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun TranslationLanguageRow(
    uiMode: BibiUiMode,
    source: String,
    target: String,
    enabled: Boolean,
    canSwap: Boolean,
    groupCount: Int,
    onSwap: () -> Unit,
    onSourceClick: () -> Unit,
    onTargetClick: () -> Unit
) {
    val haptic = LocalSettingsHapticTap.current
    val swapDescription = stringResource(R.string.translation_swap)
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsLayoutMetrics.TextFieldHorizontalPadding)
                .padding(vertical = SettingsLayoutMetrics.TextFieldLooseVerticalPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TranslationLanguageField(
                uiMode = uiMode,
                label = stringResource(R.string.translation_source_language),
                value = source,
                enabled = enabled,
                onClick = onSourceClick,
                modifier = Modifier.weight(1f)
            )
            val swap = {
                haptic()
                onSwap()
            }
            when (uiMode) {
                BibiUiMode.Material -> IconButton(onClick = swap, enabled = enabled && canSwap) {
                    Icon(Icons.AutoMirrored.Rounded.CompareArrows, contentDescription = swapDescription)
                }
                BibiUiMode.Miuix -> MiuixIconButton(onClick = swap, enabled = enabled && canSwap) {
                    MiuixIcon(Icons.AutoMirrored.Rounded.CompareArrows, contentDescription = swapDescription)
                }
            }
            TranslationLanguageField(
                uiMode = uiMode,
                label = stringResource(R.string.translation_target_language),
                value = target,
                enabled = enabled,
                onClick = onTargetClick,
                modifier = Modifier.weight(1f)
            )
        }
    }
    when (uiMode) {
        BibiUiMode.Material -> SettingsMaterialItemSurface(index = 1, count = groupCount) { content() }
        BibiUiMode.Miuix -> content()
    }
}

@Composable
private fun TranslationLanguageField(
    uiMode: BibiUiMode,
    label: String,
    value: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val haptic = LocalSettingsHapticTap.current
    val shape = RoundedCornerShape(SettingsLayoutMetrics.TextFieldCorner)
    val background = when (uiMode) {
        BibiUiMode.Material -> MaterialTheme.colorScheme.surfaceColorAtElevation(SettingsLayoutMetrics.MaterialSectionElevation)
        BibiUiMode.Miuix -> MiuixTheme.colorScheme.secondaryContainer
    }
    val outline = when (uiMode) {
        BibiUiMode.Material -> MaterialTheme.colorScheme.outline
        BibiUiMode.Miuix -> background
    }
    Column(
        modifier = modifier
            .heightIn(min = 64.dp)
            .background(background, shape)
            .border(1.dp, outline, shape)
            .clickable(enabled = enabled, role = Role.Button) {
                haptic()
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        when (uiMode) {
            BibiUiMode.Material -> {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge)
            }
            BibiUiMode.Miuix -> {
                MiuixText(label, style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                MiuixText(value, style = MiuixTheme.textStyles.body1)
            }
        }
    }
}
