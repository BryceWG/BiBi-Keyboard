/**
 * Compose 设置多选底部弹层。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.core.settingsSegmentedItemShape
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class SettingsMultiChoiceSheetState(
    val title: String,
    val items: List<String>,
    val checkedIndices: Set<Int>,
    val selectedOrder: List<Int> = checkedIndices.toList(),
    val confirmText: String,
    val cancelText: String,
    val requiredSelectionCount: Int? = null,
    val maxSelectionCount: Int? = null,
    val maxSelectionMessage: String? = null,
    val showSelectionOrder: Boolean = false,
    val onSelectionRejected: ((String) -> Unit)? = null,
    val onConfirm: (List<Int>) -> Boolean
)

@Composable
internal fun SettingsMultiChoiceSheet(
    state: SettingsMultiChoiceSheetState?,
    uiMode: BibiUiMode,
    onDismiss: () -> Unit
) {
    val visibleState = state?.takeIf { it.items.isNotEmpty() } ?: return
    when (uiMode) {
        BibiUiMode.Material -> MaterialMultiChoiceSheet(
            state = visibleState,
            onDismiss = onDismiss
        )

        BibiUiMode.Miuix -> MiuixMultiChoiceSheet(
            state = visibleState,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun MaterialMultiChoiceSheet(
    state: SettingsMultiChoiceSheetState,
    onDismiss: () -> Unit
) {
    var selectedOrder by remember(state) { mutableStateOf(state.normalizedSelectedOrder()) }
    val hapticTap = LocalSettingsHapticTap.current
    MaterialSettingsSheetScaffold(
        title = state.title,
        onDismiss = onDismiss,
        bottomPadding = 0.dp
    ) { dismissSheet ->
        MultiChoiceList(
            items = state.items,
            selectedOrder = selectedOrder,
            uiMode = BibiUiMode.Material,
            showSelectionOrder = state.showSelectionOrder,
            onToggle = { index, checked ->
                updateSelectedOrder(
                    state = state,
                    selectedOrder = selectedOrder,
                    index = index,
                    checked = checked
                )?.let { nextOrder ->
                    hapticTap()
                    selectedOrder = nextOrder
                }
            }
        )
        SettingsSheetActionRow(
            uiMode = BibiUiMode.Material,
            cancelText = state.cancelText,
            confirmText = state.confirmText,
            confirmEnabled = state.isConfirmEnabled(selectedOrder),
            onDismiss = { dismissSheet {} },
            onConfirm = {
                if (state.onConfirm(selectedOrder)) {
                    dismissSheet {}
                }
            },
            contentPadding = PaddingValues(vertical = SettingsLayoutMetrics.SheetBottomPadding)
        )
    }
}

@Composable
private fun MiuixMultiChoiceSheet(
    state: SettingsMultiChoiceSheetState,
    onDismiss: () -> Unit
) {
    var selectedOrder by remember(state) { mutableStateOf(state.normalizedSelectedOrder()) }
    var show by remember(state) { mutableStateOf(true) }
    val hapticTap = LocalSettingsHapticTap.current
    OverlayDialog(
        show = show,
        onDismissRequest = { show = false },
        onDismissFinished = onDismiss,
        insideMargin = DpSize(0.dp, 0.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            MiuixText(
                text = state.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        top = SettingsLayoutMetrics.SheetDialogTitleTopPadding,
                        bottom = SettingsLayoutMetrics.SheetTitleBottomPadding
                    ),
                color = MiuixTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
                style = MiuixTheme.textStyles.title4
            )
            MultiChoiceList(
                items = state.items,
                selectedOrder = selectedOrder,
                uiMode = BibiUiMode.Miuix,
                showSelectionOrder = state.showSelectionOrder,
                onToggle = { index, checked ->
                    updateSelectedOrder(
                        state = state,
                        selectedOrder = selectedOrder,
                        index = index,
                        checked = checked
                    )?.let { nextOrder ->
                        hapticTap()
                        selectedOrder = nextOrder
                    }
                }
            )
            SettingsSheetActionRow(
                uiMode = BibiUiMode.Miuix,
                cancelText = state.cancelText,
                confirmText = state.confirmText,
                confirmEnabled = state.isConfirmEnabled(selectedOrder),
                onDismiss = { show = false },
                onConfirm = {
                    if (state.onConfirm(selectedOrder)) {
                        show = false
                    }
                }
            )
        }
    }
}

@Composable
private fun MultiChoiceList(
    items: List<String>,
    selectedOrder: List<Int>,
    uiMode: BibiUiMode,
    showSelectionOrder: Boolean,
    onToggle: (Int, Boolean) -> Unit
) {
    SettingsSheetLazyColumn(
        uiMode = uiMode,
        contentPadding = PaddingValues(bottom = 8.dp)
    ) {
        itemsIndexed(
            items = items,
            key = { index, item -> "$index:$item" }
        ) { index, item ->
            val orderIndex = selectedOrder.indexOf(index)
            val checked = orderIndex >= 0
            val title = if (showSelectionOrder && orderIndex >= 0) {
                "${orderIndex + 1}. $item"
            } else {
                item
            }
            when (uiMode) {
                BibiUiMode.Material -> Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = SettingsLayoutMetrics.MaterialSectionItemSpacing)
                        .toggleable(
                            value = checked,
                            role = Role.Checkbox,
                            onValueChange = { onToggle(index, it) }
                        ),
                    shape = settingsSegmentedItemShape(index, items.size),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    ListItem(
                        modifier = Modifier.heightIn(min = SettingsLayoutMetrics.SettingsPreferenceMinHeight),
                        headlineContent = { MaterialText(title) },
                        trailingContent = {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }

                BibiUiMode.Miuix -> CheckboxPreference(
                    title = title,
                    checked = checked,
                    onCheckedChange = { isChecked -> onToggle(index, isChecked) },
                    checkboxLocation = CheckboxLocation.End
                )
            }
        }
    }
}

private fun SettingsMultiChoiceSheetState.normalizedSelectedOrder(): List<Int> {
    val validOrder = selectedOrder.filter { it in items.indices }.distinct()
    val missing = checkedIndices
        .filter { it in items.indices && !validOrder.contains(it) }
    return validOrder + missing
}

private fun SettingsMultiChoiceSheetState.isConfirmEnabled(selectedOrder: List<Int>): Boolean {
    val requiredCount = requiredSelectionCount ?: return true
    return selectedOrder.size == requiredCount
}

private fun updateSelectedOrder(
    state: SettingsMultiChoiceSheetState,
    selectedOrder: List<Int>,
    index: Int,
    checked: Boolean
): List<Int>? = if (checked) {
    if (selectedOrder.contains(index)) {
        selectedOrder
    } else {
        val maxCount = state.maxSelectionCount
        if (maxCount != null && selectedOrder.size >= maxCount) {
            state.maxSelectionMessage?.let { state.onSelectionRejected?.invoke(it) }
            null
        } else {
            selectedOrder + index
        }
    }
} else {
    selectedOrder - index
}
