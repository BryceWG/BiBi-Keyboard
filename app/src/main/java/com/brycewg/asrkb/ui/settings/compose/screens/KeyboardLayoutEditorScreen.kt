/**
 * 自定义键盘布局编辑页面。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ime.layout.BlockDef
import com.brycewg.asrkb.ime.layout.BlockDefRegistry
import com.brycewg.asrkb.ime.layout.BlockInstance
import com.brycewg.asrkb.ime.layout.BlockPlacement
import com.brycewg.asrkb.ime.layout.BlockSize
import com.brycewg.asrkb.ime.layout.GridSize
import com.brycewg.asrkb.ime.layout.KeyboardLayout
import com.brycewg.asrkb.ime.layout.KeyboardLayoutBundle
import com.brycewg.asrkb.ime.layout.KeyboardLayoutPanel
import com.brycewg.asrkb.ime.layout.KeyboardLayoutStore
import com.brycewg.asrkb.ime.layout.validateLayout
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsAlertDialog
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogButtonRow
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogExitEffect
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogActionRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialog
import com.brycewg.asrkb.ui.settings.compose.components.SettingsMessageDialogState
import com.brycewg.asrkb.ui.settings.compose.components.SettingsPreference
import com.brycewg.asrkb.ui.settings.compose.components.SettingsSheetActionRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsTextField
import com.brycewg.asrkb.ui.settings.compose.components.animateSettingsDialogExitAlpha
import com.brycewg.asrkb.ui.settings.compose.components.rememberSettingsDialogExitController
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.LocalSettingsHapticTap
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import com.brycewg.asrkb.ui.settings.compose.model.DropdownOption
import com.brycewg.asrkb.ui.settings.compose.model.SettingsEntry
import kotlin.math.roundToInt
import org.xmlpull.v1.XmlPullParser
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.DropdownDefaults
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon as MiuixIcon
import top.yukonga.miuix.kmp.basic.IconButton as MiuixIconButton
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.basic.TextButton as MiuixTextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.popup.OverlayDropdownPopup
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun KeyboardLayoutEditorScreen(
    uiMode: BibiUiMode,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember(context) { Prefs(context) }
    val registry = remember { BlockDefRegistry.default }
    var bundle by remember { mutableStateOf(KeyboardLayoutStore.load(prefs)) }
    var panel by remember { mutableStateOf(KeyboardLayoutPanel.Main) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var showJson by remember { mutableStateOf(false) }
    var jsonText by remember(bundle) { mutableStateOf(KeyboardLayoutStore.encodeBundle(bundle)) }
    var messageDialog by remember { mutableStateOf<SettingsMessageDialogState?>(null) }

    fun replaceBundle(nextBundle: KeyboardLayoutBundle) {
        bundle = nextBundle
        selectedIndex = selectedIndex?.takeIf { it in nextBundle.layoutFor(panel).blocks.indices }
        jsonText = KeyboardLayoutStore.encodeBundle(nextBundle)
    }

    fun replaceCurrentLayout(layout: KeyboardLayout) {
        val updated = layout.copy(updatedAt = System.currentTimeMillis())
        replaceBundle(bundle.withLayout(updated))
    }

    fun updateLayout(transform: (KeyboardLayout) -> KeyboardLayout) {
        replaceCurrentLayout(transform(bundle.layoutFor(panel)))
    }

    fun syncLayoutGridSize(gridSize: GridSize) {
        replaceBundle(bundle.withSyncedGridSize(panel, gridSize))
    }

    fun showMessage(message: String) {
        messageDialog = SettingsMessageDialogState(
            title = context.getString(R.string.keyboard_layout_editor_title),
            message = message,
            confirmText = context.getString(android.R.string.ok)
        )
    }

    fun copyJsonToClipboard(text: String = KeyboardLayoutStore.encodeBundle(bundle)) {
        copyToSystemClipboard(context, text)
        showMessage(context.getString(R.string.keyboard_layout_export_copied))
    }

    fun saveLayout() {
        val errors = listOf(bundle.main, bundle.aiEdit, bundle.recording)
            .flatMap { validateLayout(it, registry) }
        if (errors.isNotEmpty()) {
            showMessage(context.getString(R.string.keyboard_layout_error_invalid))
        } else {
            KeyboardLayoutStore.save(prefs, bundle)
            context.sendImeRefreshBroadcast()
            showMessage(context.getString(R.string.keyboard_layout_save_success))
        }
    }

    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.keyboard_layout_editor_title,
        onBack = onBack,
        actions = {
            KeyboardLayoutPanelTopSelector(
                uiMode = uiMode,
                selectedPanel = panel,
                onSelected = { selectedPanel ->
                    panel = selectedPanel
                    selectedIndex = null
                }
            )
        },
        bottomBar = {
            KeyboardLayoutEditorBottomBar(
                uiMode = uiMode,
                onReset = {
                    replaceCurrentLayout(KeyboardLayoutStore.defaultLayout(prefs, panel))
                    selectedIndex = null
                },
                onClear = {
                    updateLayout { current ->
                        current.clearedRequiredOnly(
                            defaultLayout = KeyboardLayoutStore.defaultLayout(prefs, panel),
                            registry = registry
                        )
                    }
                    selectedIndex = null
                },
                onToggleJson = {
                    jsonText = KeyboardLayoutStore.encodeBundle(bundle)
                    showJson = true
                },
                onCancel = onBack,
                onSave = ::saveLayout
            )
        }
    ) {
            innerPadding,
            scrollModifier
        ->
        val layout = bundle.layoutFor(panel)
        val selectedBlock = selectedIndex?.let { layout.blocks.getOrNull(it) }
        val selectedDef = selectedBlock?.let { registry.get(panel, it.defId) ?: registry.get(it.defId) }
        Column(
            modifier = Modifier
                .then(scrollModifier)
                .fillMaxSize()
                .padding(SettingsLayoutMetrics.pageContentPadding(innerPadding)),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KeyboardLayoutEditorCard(uiMode = uiMode) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    KeyboardLayoutSizeControls(
                        uiMode = uiMode,
                        layout = layout,
                        onColsChange = { cols ->
                            syncLayoutGridSize(layout.gridSize.copy(cols = cols))
                        },
                        onRowsChange = { rows ->
                            syncLayoutGridSize(layout.gridSize.copy(rows = rows))
                        }
                    )

                    KeyboardLayoutGrid(
                        uiMode = uiMode,
                        layout = layout,
                        registry = registry,
                        selectedIndex = selectedIndex,
                        onSelect = { selectedIndex = it },
                        onMove = { index, placement ->
                            updateLayout { current ->
                                val block = current.blocks.getOrNull(index) ?: return@updateLayout current
                                val nextPlacement = placement.snapped().coerceInto(current.gridSize)
                                if (current.hasCollision(index, nextPlacement)) {
                                    current
                                } else {
                                    current.copy(
                                        blocks = current.blocks.toMutableList().also {
                                            it[index] = block.copy(placement = nextPlacement)
                                        }
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                KeyboardLayoutEditorCard(
                    uiMode = uiMode,
                    modifier = Modifier.fillMaxSize()
                ) {
                    KeyboardLayoutTray(
                        uiMode = uiMode,
                        layout = layout,
                        registry = registry,
                        floatingBarInset = if (selectedBlock != null && selectedDef != null) 72.dp else 0.dp,
                        modifier = Modifier.fillMaxSize(),
                        onAdd = { def ->
                            updateLayout { current ->
                                val placement = findFreePlacement(current, def) ?: return@updateLayout current
                                current.copy(blocks = current.blocks + BlockInstance(def.id, placement))
                            }
                        }
                    )
                }

                val selectedIndexValue = selectedIndex
                if (selectedIndexValue != null && selectedBlock != null && selectedDef != null) {
                    KeyboardLayoutFloatingSelectionBar(
                        uiMode = uiMode,
                        def = selectedDef,
                        block = selectedBlock,
                        sizes = selectedDef.validSizesFor(layout, selectedIndexValue, selectedBlock),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        onSizeSelected = { size ->
                            updateLayout { current ->
                                val block = current.blocks.getOrNull(selectedIndexValue) ?: return@updateLayout current
                                val placement = block.placement.copy(
                                    width = size.width,
                                    height = size.height
                                ).coerceInto(current.gridSize)
                                if (!placement.withinGrid(current.gridSize) || current.hasCollision(selectedIndexValue, placement)) {
                                    current
                                } else {
                                    current.copy(
                                        blocks = current.blocks.toMutableList().also {
                                            it[selectedIndexValue] = block.copy(placement = placement)
                                        }
                                    )
                                }
                            }
                        },
                        onDelete = {
                            if (!selectedDef.required) {
                                updateLayout { current ->
                                    current.copy(blocks = current.blocks.filterIndexed { i, _ -> i != selectedIndexValue })
                                }
                                selectedIndex = null
                            }
                        }
                    )
                }
            }
        }
    }

    if (showJson) {
        KeyboardLayoutJsonDialog(
            uiMode = uiMode,
            jsonText = jsonText,
            onJsonTextChange = { jsonText = it },
            onDismiss = { showJson = false },
            onImport = {
                val parsed = KeyboardLayoutStore.parseBundle(jsonText)
                if (parsed == null) {
                    showMessage(context.getString(R.string.keyboard_layout_import_failed))
                } else {
                    val normalized = KeyboardLayoutStore.normalizeBundle(prefs, parsed)
                    bundle = normalized
                    selectedIndex = null
                    jsonText = KeyboardLayoutStore.encodeBundle(normalized)
                    showMessage(context.getString(R.string.keyboard_layout_import_success))
                }
            },
            onCopy = { copyJsonToClipboard(jsonText) }
        )
    }

    SettingsMessageDialog(
        state = messageDialog,
        uiMode = uiMode,
        onDismiss = { messageDialog = null }
    )
}

@Composable
private fun KeyboardLayoutPanelTopSelector(
    uiMode: BibiUiMode,
    selectedPanel: KeyboardLayoutPanel,
    onSelected: (KeyboardLayoutPanel) -> Unit
) {
    val panels = KeyboardLayoutPanel.values()
    var expanded by remember { mutableStateOf(false) }
    val hapticTap = LocalSettingsHapticTap.current
    val expandWithHaptic = {
        hapticTap()
        expanded = true
    }
    val selectedColor = when (uiMode) {
        BibiUiMode.Material -> MaterialTheme.colorScheme.primary
        BibiUiMode.Miuix -> MiuixTheme.colorScheme.primary
    }
    val normalColor = when (uiMode) {
        BibiUiMode.Material -> MaterialTheme.colorScheme.onSurface
        BibiUiMode.Miuix -> MiuixTheme.colorScheme.onSurface
    }
    Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
        when (uiMode) {
            BibiUiMode.Material -> MaterialTextButton(onClick = expandWithHaptic) {
                Text(
                    text = stringResource(selectedPanel.titleRes()),
                    modifier = Modifier.widthIn(max = 104.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            BibiUiMode.Miuix -> MiuixTextButton(
                text = stringResource(selectedPanel.titleRes()),
                onClick = expandWithHaptic
            )
        }
        when (uiMode) {
            BibiUiMode.Material -> DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                panels.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(option.titleRes()),
                                color = if (option == selectedPanel) selectedColor else normalColor
                            )
                        },
                        onClick = {
                            hapticTap()
                            expanded = false
                            if (option != selectedPanel) onSelected(option)
                        }
                    )
                }
            }

            BibiUiMode.Miuix -> {
                val entry = DropdownEntry(
                    items = panels.map { option ->
                        DropdownItem(
                            text = stringResource(option.titleRes()),
                            selected = option == selectedPanel,
                            onClick = {
                                hapticTap()
                                expanded = false
                                if (option != selectedPanel) onSelected(option)
                            }
                        )
                    }
                )
                OverlayDropdownPopup(
                    entry = entry,
                    show = expanded,
                    onDismiss = { expanded = false },
                    onDismissFinished = {},
                    maxHeight = null,
                    dropdownColors = DropdownDefaults.dropdownColors(),
                    renderInRootScaffold = true,
                    collapseOnSelection = true
                )
            }
        }
    }
}

@Composable
private fun KeyboardLayoutEditorCard(
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    when (uiMode) {
        BibiUiMode.Material -> Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(SettingsLayoutMetrics.MaterialSectionShape),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(SettingsLayoutMetrics.MaterialSectionElevation),
            contentColor = MaterialTheme.colorScheme.onSurface,
            content = content
        )

        BibiUiMode.Miuix -> MiuixCard(modifier = modifier.fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun KeyboardLayoutEditorBottomBar(
    uiMode: BibiUiMode,
    onReset: () -> Unit,
    onClear: () -> Unit,
    onToggleJson: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    KeyboardLayoutBottomBarSurface(uiMode = uiMode) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            KeyboardLayoutNeutralAction(
                uiMode = uiMode,
                text = stringResource(R.string.keyboard_layout_reset_default),
                modifier = Modifier.weight(1f),
                onClick = onReset
            )
            KeyboardLayoutNeutralAction(
                uiMode = uiMode,
                text = stringResource(R.string.keyboard_layout_clear_current),
                modifier = Modifier.weight(1f),
                onClick = onClear
            )
            KeyboardLayoutNeutralAction(
                uiMode = uiMode,
                text = stringResource(R.string.keyboard_layout_import_export),
                modifier = Modifier.weight(1f),
                onClick = onToggleJson
            )
        }
        SettingsSheetActionRow(
            uiMode = uiMode,
            cancelText = stringResource(R.string.btn_cancel),
            confirmText = stringResource(R.string.btn_save),
            confirmEnabled = true,
            onDismiss = onCancel,
            onConfirm = onSave,
            contentPadding = PaddingValues(0.dp)
        )
    }
}

@Composable
private fun KeyboardLayoutBottomBarSurface(
    uiMode: BibiUiMode,
    content: @Composable () -> Unit
) {
    when (uiMode) {
        BibiUiMode.Material -> Surface(
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(SettingsLayoutMetrics.BottomBarElevation),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = SettingsLayoutMetrics.BottomBarElevation,
            shape = RoundedCornerShape(
                topStart = SettingsLayoutMetrics.BottomBarTopCorner,
                topEnd = SettingsLayoutMetrics.BottomBarTopCorner
            ),
            modifier = Modifier.navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = SettingsLayoutMetrics.ActionButtonRowHorizontalPadding,
                        vertical = 12.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = { content() }
            )
        }

        BibiUiMode.Miuix -> MiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = SettingsLayoutMetrics.ActionButtonRowHorizontalPadding,
                        vertical = 12.dp
                    ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = { content() }
            )
        }
    }
}

@Composable
private fun KeyboardLayoutNeutralAction(
    uiMode: BibiUiMode,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(SettingsLayoutMetrics.ActionButtonCorner)
    val hapticTap = LocalSettingsHapticTap.current
    val clickWithHaptic = {
        hapticTap()
        onClick()
    }
    val actionModifier = modifier
        .heightIn(min = SettingsLayoutMetrics.ActionButtonMinHeight)
        .clip(shape)
        .clickable(enabled = enabled, onClick = clickWithHaptic)
        .alpha(if (enabled) 1f else 0.38f)
    when (uiMode) {
        BibiUiMode.Material -> Surface(
            modifier = actionModifier,
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = 8.dp,
                        vertical = SettingsLayoutMetrics.ActionButtonInsideVerticalPadding
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }

        BibiUiMode.Miuix -> Box(
            modifier = actionModifier.background(MiuixTheme.colorScheme.secondaryVariant),
            contentAlignment = Alignment.Center
        ) {
            MiuixText(
                text = text,
                modifier = Modifier.padding(
                    horizontal = 8.dp,
                    vertical = SettingsLayoutMetrics.ActionButtonInsideVerticalPadding
                ),
                style = MiuixTheme.textStyles.button,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun KeyboardLayoutJsonDialog(
    uiMode: BibiUiMode,
    jsonText: String,
    onJsonTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
    onCopy: () -> Unit
) {
    val title = stringResource(R.string.keyboard_layout_import_export)
    val importText = stringResource(R.string.keyboard_layout_import_json)
    val copyText = stringResource(R.string.keyboard_layout_copy_json)
    val exit = rememberSettingsDialogExitController()
    fun finishDismiss() {
        exit.finish()
    }
    when (uiMode) {
        BibiUiMode.Material -> {
            val alpha = animateSettingsDialogExitAlpha(
                show = exit.show,
                label = "KeyboardLayoutJsonDialogAlpha"
            )
            MaterialSettingsDialogExitEffect(show = exit.show, onFinished = ::finishDismiss)
            MaterialSettingsAlertDialog(
                title = title,
                onDismissRequest = { exit.dismiss(onDismiss) },
                modifier = Modifier.graphicsLayer(alpha = alpha),
                text = {
                    KeyboardLayoutJsonDialogContent(
                        uiMode = uiMode,
                        jsonText = jsonText,
                        onJsonTextChange = onJsonTextChange
                    )
                },
                buttons = {
                    MaterialSettingsDialogButtonRow(
                        actions = listOf(
                            MaterialSettingsDialogAction(
                                text = importText,
                                onClick = onImport
                            ),
                            MaterialSettingsDialogAction(
                                text = copyText,
                                onClick = onCopy,
                                primary = true
                            )
                        )
                    )
                }
            )
        }

        BibiUiMode.Miuix -> OverlayDialog(
            show = exit.show,
            title = title,
            onDismissRequest = { exit.dismiss(onDismiss) },
            onDismissFinished = ::finishDismiss
        ) {
            KeyboardLayoutJsonDialogContent(
                uiMode = uiMode,
                jsonText = jsonText,
                onJsonTextChange = onJsonTextChange,
                modifier = Modifier.padding(bottom = SettingsLayoutMetrics.DialogContentBottomPadding)
            )
            SettingsDialogActionRow(
                uiMode = uiMode,
                actions = listOf(
                    SettingsDialogAction(
                        text = importText,
                        onClick = onImport
                    ),
                    SettingsDialogAction(
                        text = copyText,
                        onClick = onCopy,
                        primary = true
                    )
                )
            )
        }
    }
}

@Composable
private fun KeyboardLayoutJsonDialogContent(
    uiMode: BibiUiMode,
    jsonText: String,
    onJsonTextChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    SettingsTextField(
        uiMode = uiMode,
        value = jsonText,
        onValueChange = onJsonTextChange,
        label = stringResource(R.string.keyboard_layout_json_label),
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp),
        singleLine = false,
        minLines = 5,
        maxLines = 8,
        keyboardType = KeyboardType.Text,
        materialContainer = false,
        contentPadding = PaddingValues(0.dp)
    )
}

@Composable
private fun KeyboardLayoutSizeControls(
    uiMode: BibiUiMode,
    layout: KeyboardLayout,
    onColsChange: (Int) -> Unit,
    onRowsChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Stepper(
            uiMode = uiMode,
            label = stringResource(R.string.keyboard_layout_cols),
            value = layout.gridSize.cols,
            modifier = Modifier.weight(1f),
            onMinus = { onColsChange(layout.gridSize.cols - 1) },
            onPlus = { onColsChange(layout.gridSize.cols + 1) }
        )
        Stepper(
            uiMode = uiMode,
            label = stringResource(R.string.keyboard_layout_rows),
            value = layout.gridSize.rows,
            modifier = Modifier.weight(1f),
            onMinus = { onRowsChange(layout.gridSize.rows - 1) },
            onPlus = { onRowsChange(layout.gridSize.rows + 1) }
        )
    }
}

@Composable
private fun Stepper(
    uiMode: BibiUiMode,
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onMinus: () -> Unit,
    onPlus: () -> Unit
) {
    val hapticTap = LocalSettingsHapticTap.current
    val minusWithHaptic = {
        hapticTap()
        onMinus()
    }
    val plusWithHaptic = {
        hapticTap()
        onPlus()
    }
    KeyboardLayoutControlSurface(uiMode = uiMode, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (uiMode) {
                BibiUiMode.Material -> {
                    Text(label, style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = minusWithHaptic, modifier = Modifier.size(34.dp)) {
                        Text("-")
                    }
                    Text(value.toString(), modifier = Modifier.width(24.dp), textAlign = TextAlign.Center)
                    IconButton(onClick = plusWithHaptic, modifier = Modifier.size(34.dp)) {
                        Text("+")
                    }
                }

                BibiUiMode.Miuix -> {
                    MiuixText(text = label, style = MiuixTheme.textStyles.body1)
                    MiuixIconButton(
                        onClick = minusWithHaptic,
                        modifier = Modifier.size(34.dp),
                        minWidth = 34.dp,
                        minHeight = 34.dp
                    ) {
                        MiuixText(text = "-", style = MiuixTheme.textStyles.button)
                    }
                    Box(
                        modifier = Modifier.width(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        MiuixText(
                            text = value.toString(),
                            style = MiuixTheme.textStyles.body1
                        )
                    }
                    MiuixIconButton(
                        onClick = plusWithHaptic,
                        modifier = Modifier.size(34.dp),
                        minWidth = 34.dp,
                        minHeight = 34.dp
                    ) {
                        MiuixText(text = "+", style = MiuixTheme.textStyles.button)
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyboardLayoutControlSurface(
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    when (uiMode) {
        BibiUiMode.Material -> Surface(
            modifier = modifier,
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            content = content
        )

        BibiUiMode.Miuix -> Box(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MiuixTheme.colorScheme.secondaryVariant)
        ) {
            content()
        }
    }
}

@Composable
private fun KeyboardLayoutEditorText(
    uiMode: BibiUiMode,
    text: String,
    modifier: Modifier = Modifier,
    materialStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelMedium,
    miuixStyle: androidx.compose.ui.text.TextStyle = MiuixTheme.textStyles.footnote1,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null
) {
    when (uiMode) {
        BibiUiMode.Material -> Text(
            text = text,
            modifier = modifier,
            color = color,
            style = materialStyle,
            maxLines = maxLines,
            overflow = overflow,
            textAlign = textAlign
        )

        BibiUiMode.Miuix -> MiuixText(
            text = text,
            modifier = modifier,
            color = color,
            style = miuixStyle,
            maxLines = maxLines,
            overflow = overflow
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun KeyboardLayoutMarqueeText(
    uiMode: BibiUiMode,
    text: String,
    modifier: Modifier = Modifier,
    materialStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelMedium,
    miuixStyle: androidx.compose.ui.text.TextStyle = MiuixTheme.textStyles.footnote1,
    color: Color = Color.Unspecified,
    textAlign: TextAlign = TextAlign.Center
) {
    val style = when (uiMode) {
        BibiUiMode.Material -> materialStyle
        BibiUiMode.Miuix -> miuixStyle
    }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            modifier = Modifier.basicMarquee(),
            color = color,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Visible,
            textAlign = textAlign
        )
    }
}

private data class KeyboardLayoutDragPreview(
    val index: Int,
    val rawPlacement: BlockPlacement,
    val dropPlacement: BlockPlacement,
    val valid: Boolean
)

@Composable
private fun KeyboardLayoutGrid(
    uiMode: BibiUiMode,
    layout: KeyboardLayout,
    registry: BlockDefRegistry,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit,
    onMove: (Int, BlockPlacement) -> Unit,
    modifier: Modifier = Modifier
) {
    val hapticTap = LocalSettingsHapticTap.current
    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(layout.gridSize.cols.toFloat() / layout.gridSize.rows.toFloat())
            .clip(RoundedCornerShape(16.dp))
            .background(KeyboardLayoutGridBackground(uiMode))
    ) {
        val cellPx = constraints.maxWidth.toFloat() / layout.gridSize.cols.toFloat()
        val density = LocalDensity.current
        var dragPreview by remember(layout.id, layout.updatedAt) { mutableStateOf<KeyboardLayoutDragPreview?>(null) }
        GridLines(uiMode = uiMode, grid = layout.gridSize)
        layout.blocks.forEachIndexed { index, block ->
            val def = registry.get(layout.panel, block.defId) ?: registry.get(block.defId) ?: return@forEachIndexed
            val placement = block.placement.snapped()
            val blockWidth = with(density) { (placement.width * cellPx).toDp() }
            val blockHeight = with(density) { (placement.height * cellPx).toDp() }
            val selected = index == selectedIndex
            val dragging = dragPreview?.index == index
            KeyboardLayoutBlockChip(
                uiMode = uiMode,
                def = def,
                selected = selected,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (placement.col * cellPx).roundToInt(),
                            (placement.row * cellPx).roundToInt()
                        )
                    }
                    .size(
                        width = blockWidth,
                        height = blockHeight
                    )
                    .padding(3.dp)
                    .alpha(if (dragging) 0.42f else 1f)
                    .pointerInput(block, cellPx, layout.gridSize, layout.blocks) {
                        var dragStart = placement
                        var dragX = 0f
                        var dragY = 0f
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                onSelect(index)
                                dragStart = placement
                                dragX = 0f
                                dragY = 0f
                                val drop = dragStart.coerceInto(layout.gridSize)
                                dragPreview = KeyboardLayoutDragPreview(
                                    index = index,
                                    rawPlacement = dragStart,
                                    dropPlacement = drop,
                                    valid = !layout.hasCollision(index, drop)
                                )
                            },
                            onDragEnd = {
                                dragPreview
                                    ?.takeIf { it.index == index && it.valid }
                                    ?.let { onMove(index, it.dropPlacement) }
                                dragPreview = null
                            },
                            onDragCancel = {
                                dragPreview = null
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                if (cellPx > 0f) {
                                    dragX += amount.x
                                    dragY += amount.y
                                    val raw = dragStart.copy(
                                        col = dragStart.col + dragX / cellPx,
                                        row = dragStart.row + dragY / cellPx
                                    ).coerceIntoGridBounds(layout.gridSize)
                                    val drop = raw.snapped().coerceInto(layout.gridSize)
                                    dragPreview = KeyboardLayoutDragPreview(
                                        index = index,
                                        rawPlacement = raw,
                                        dropPlacement = drop,
                                        valid = drop.withinGrid(layout.gridSize) && !layout.hasCollision(index, drop)
                                    )
                                }
                            }
                        )
                    }
                    .clickable {
                        hapticTap()
                        onSelect(index)
                    }
            )
        }
        dragPreview?.let { preview ->
            val block = layout.blocks.getOrNull(preview.index) ?: return@let
            val def = registry.get(layout.panel, block.defId) ?: registry.get(block.defId) ?: return@let
            val placement = preview.rawPlacement
            val blockWidth = with(density) { (placement.width * cellPx).toDp() }
            val blockHeight = with(density) { (placement.height * cellPx).toDp() }
            KeyboardLayoutBlockChip(
                uiMode = uiMode,
                def = def,
                selected = true,
                invalid = !preview.valid,
                ghost = true,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (placement.col * cellPx).roundToInt(),
                            (placement.row * cellPx).roundToInt()
                        )
                    }
                    .size(width = blockWidth, height = blockHeight)
                    .padding(3.dp)
            )
        }
    }
}

@Composable
private fun KeyboardLayoutGridBackground(uiMode: BibiUiMode): Color = when (uiMode) {
    BibiUiMode.Material -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    BibiUiMode.Miuix -> MiuixTheme.colorScheme.secondaryVariant.copy(alpha = 0.58f)
}

@Composable
private fun GridLines(uiMode: BibiUiMode, grid: GridSize) {
    val lineColor = when (uiMode) {
        BibiUiMode.Material -> MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        BibiUiMode.Miuix -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.34f)
    }
    val halfLineColor = when (uiMode) {
        BibiUiMode.Material -> MaterialTheme.colorScheme.outline.copy(alpha = 0.24f)
        BibiUiMode.Miuix -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.18f)
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cell = size.width / grid.cols
        val dashed = PathEffect.dashPathEffect(floatArrayOf(6f, 8f), 0f)
        for (i in 0..grid.cols * 2) {
            val x = i * cell / 2f
            drawLine(
                color = if (i % 2 == 0) lineColor else halfLineColor,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = if (i % 2 == 0) 1.4f else 1f,
                pathEffect = if (i % 2 == 0) null else dashed
            )
        }
        for (i in 0..grid.rows * 2) {
            val y = i * cell / 2f
            drawLine(
                color = if (i % 2 == 0) lineColor else halfLineColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = if (i % 2 == 0) 1.4f else 1f,
                pathEffect = if (i % 2 == 0) null else dashed
            )
        }
    }
}

@Composable
private fun KeyboardLayoutBlockChip(
    uiMode: BibiUiMode,
    def: BlockDef,
    selected: Boolean,
    invalid: Boolean = false,
    ghost: Boolean = false,
    modifier: Modifier
) {
    val bg = when (uiMode) {
        BibiUiMode.Material -> when {
            invalid -> MaterialTheme.colorScheme.errorContainer
            selected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surface
        }
        BibiUiMode.Miuix -> if (invalid) {
            MaterialTheme.colorScheme.errorContainer
        } else if (selected) {
            MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MiuixTheme.colorScheme.surfaceVariant
        }
    }
    val fg = when (uiMode) {
        BibiUiMode.Material -> when {
            invalid -> MaterialTheme.colorScheme.onErrorContainer
            selected -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurface
        }
        BibiUiMode.Miuix -> when {
            invalid -> MaterialTheme.colorScheme.onErrorContainer
            selected -> MiuixTheme.colorScheme.primary
            else -> MiuixTheme.colorScheme.onSurface
        }
    }
    val border = when (uiMode) {
        BibiUiMode.Material -> when {
            invalid -> MaterialTheme.colorScheme.error
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        }
        BibiUiMode.Miuix -> when {
            invalid -> MaterialTheme.colorScheme.error
            selected -> MiuixTheme.colorScheme.primary
            else -> MiuixTheme.colorScheme.outline
        }
    }
    Column(
        modifier = modifier
            .alpha(if (ghost) 0.78f else 1f)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = border,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        KeyboardLayoutIcon(
            def = def,
            tint = fg,
            modifier = Modifier.size(18.dp)
        ) {
            Spacer(Modifier.height(2.dp))
        }
        KeyboardLayoutMarqueeText(
            uiMode = uiMode,
            text = stringResource(def.labelRes),
            modifier = Modifier.fillMaxWidth(),
            color = fg,
            materialStyle = MaterialTheme.typography.labelSmall,
            miuixStyle = MiuixTheme.textStyles.footnote1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun KeyboardLayoutFloatingSelectionBar(
    uiMode: BibiUiMode,
    def: BlockDef,
    block: BlockInstance,
    sizes: List<BlockSize>,
    modifier: Modifier = Modifier,
    onSizeSelected: (BlockSize) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    val currentSize = BlockSize(block.placement.width, block.placement.height)
    val options = sizes.ifEmpty { listOf(currentSize) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(18.dp),
            color = KeyboardLayoutFloatingBarBackground(uiMode),
            contentColor = KeyboardLayoutFloatingBarContent(uiMode),
            tonalElevation = if (uiMode == BibiUiMode.Material) 3.dp else 0.dp,
            shadowElevation = if (uiMode == BibiUiMode.Material) 4.dp else 0.dp
        ) {
            SettingsPreference(
                entry = SettingsEntry.Dropdown(
                    id = "keyboard_layout_selected_size",
                    titleRes = def.labelRes,
                    options = options.map { size -> DropdownOption(size.toString(), size.toString()) },
                    selectedOptionId = currentSize.toString(),
                    onSelectedOptionChange = { id ->
                        options.firstOrNull { it.toString() == id }?.let(onSizeSelected)
                    }
                )
            )
        }
        KeyboardLayoutDeleteIconButton(
            uiMode = uiMode,
            enabled = !def.required,
            contentDescription = context.getString(R.string.btn_delete),
            onClick = onDelete
        )
    }
}

@Composable
private fun KeyboardLayoutFloatingBarBackground(uiMode: BibiUiMode): Color = when (uiMode) {
    BibiUiMode.Material -> MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
    BibiUiMode.Miuix -> MiuixTheme.colorScheme.surfaceVariant
}

@Composable
private fun KeyboardLayoutFloatingBarContent(uiMode: BibiUiMode): Color = when (uiMode) {
    BibiUiMode.Material -> MaterialTheme.colorScheme.onSurface
    BibiUiMode.Miuix -> MiuixTheme.colorScheme.onSurface
}

@Composable
private fun KeyboardLayoutDeleteIconButton(
    uiMode: BibiUiMode,
    enabled: Boolean,
    contentDescription: String,
    onClick: () -> Unit
) {
    val bg = KeyboardLayoutFloatingBarBackground(uiMode)
    val fg = KeyboardLayoutFloatingBarContent(uiMode).copy(alpha = if (enabled) 1f else 0.38f)
    val hapticTap = LocalSettingsHapticTap.current
    val clickWithHaptic = {
        hapticTap()
        onClick()
    }
    when (uiMode) {
        BibiUiMode.Material -> Surface(
            shape = RoundedCornerShape(18.dp),
            color = bg,
            contentColor = fg,
            tonalElevation = 3.dp,
            shadowElevation = 4.dp
        ) {
            IconButton(
                enabled = enabled,
                onClick = clickWithHaptic,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = contentDescription
                )
            }
        }

        BibiUiMode.Miuix -> Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(bg)
                .alpha(if (enabled) 1f else 0.38f),
            contentAlignment = Alignment.Center
        ) {
            MiuixIconButton(
                onClick = { if (enabled) clickWithHaptic() },
                modifier = Modifier.size(56.dp),
                minWidth = 56.dp,
                minHeight = 56.dp
            ) {
                MiuixIcon(
                    imageVector = Icons.Rounded.Delete,
                    contentDescription = contentDescription,
                    tint = fg
                )
            }
        }
    }
}

@Composable
private fun KeyboardLayoutTray(
    uiMode: BibiUiMode,
    layout: KeyboardLayout,
    registry: BlockDefRegistry,
    floatingBarInset: Dp,
    modifier: Modifier = Modifier,
    onAdd: (BlockDef) -> Unit
) {
    Column(
        modifier = modifier
            .padding(start = 12.dp, top = 12.dp + floatingBarInset, end = 12.dp, bottom = 12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        registry.sharedTrayDefs(layout.panel).chunked(3).forEach { rowDefs ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowDefs.forEach { def ->
                    val addDef = registry.targetTrayDef(layout.panel, def) ?: return@forEach
                    val count = layout.blocks.count { block ->
                        (registry.get(layout.panel, block.defId) ?: registry.get(block.defId))
                            ?.trayKey() == def.trayKey()
                    }
                    val enabled = count < addDef.maxInstances
                    KeyboardLayoutTrayChip(
                        uiMode = uiMode,
                        def = def,
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        onClick = { onAdd(addDef) }
                    )
                }
                repeat(3 - rowDefs.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun KeyboardLayoutTrayChip(
    uiMode: BibiUiMode,
    def: BlockDef,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val hapticTap = LocalSettingsHapticTap.current
    val clickWithHaptic = {
        hapticTap()
        onClick()
    }
    val chipModifier = modifier
        .height(56.dp)
        .clip(RoundedCornerShape(14.dp))
        .clickable(enabled = enabled, onClick = clickWithHaptic)
        .alpha(if (enabled) 1f else 0.38f)

    when (uiMode) {
        BibiUiMode.Material -> Surface(
            modifier = chipModifier,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            KeyboardLayoutTrayChipContent(uiMode = uiMode, def = def)
        }

        BibiUiMode.Miuix -> Box(
            modifier = chipModifier.background(MiuixTheme.colorScheme.secondaryVariant)
        ) {
            KeyboardLayoutTrayChipContent(uiMode = uiMode, def = def)
        }
    }
}

@Composable
private fun KeyboardLayoutTrayChipContent(
    uiMode: BibiUiMode,
    def: BlockDef
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KeyboardLayoutIcon(
            def = def,
            tint = KeyboardLayoutIconTint(uiMode),
            modifier = Modifier.size(16.dp)
        ) {
            Spacer(Modifier.width(4.dp))
        }
        KeyboardLayoutMarqueeText(
            uiMode = uiMode,
            text = stringResource(def.labelRes),
            modifier = Modifier.weight(1f),
            color = KeyboardLayoutIconTint(uiMode),
            materialStyle = MaterialTheme.typography.labelMedium,
            miuixStyle = MiuixTheme.textStyles.footnote1
        )
    }
}

@Composable
private fun KeyboardLayoutIconTint(uiMode: BibiUiMode): Color = when (uiMode) {
    BibiUiMode.Material -> MaterialTheme.colorScheme.onSurface
    BibiUiMode.Miuix -> MiuixTheme.colorScheme.onSurface
}

@Composable
private fun KeyboardLayoutIcon(
    def: BlockDef,
    modifier: Modifier,
    tint: Color = Color.Unspecified,
    trailingContent: @Composable () -> Unit
) {
    val context = LocalContext.current
    val iconRes = remember(def.iconRes) { context.composeSafeDrawableRes(def.iconRes) } ?: return
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        tint = tint,
        modifier = modifier
    )
    trailingContent()
}

private fun Context.composeSafeDrawableRes(@DrawableRes iconRes: Int?): Int? {
    if (iconRes == null) return null
    val name = resources.getResourceEntryName(iconRes)
    if (isComposeDrawableSupported(iconRes)) return iconRes
    if (!name.endsWith("_toggle")) return null
    val baseName = name.removeSuffix("_toggle")
    val baseIcon = resources.getIdentifier(baseName, "drawable", packageName)
    return if (baseIcon != 0 && isComposeDrawableSupported(baseIcon)) baseIcon else null
}

private fun Context.isComposeDrawableSupported(@DrawableRes iconRes: Int): Boolean {
    val typeName = resources.getResourceTypeName(iconRes)
    if (typeName != "drawable") return true
    val parser = try {
        resources.getXml(iconRes)
    } catch (_: Resources.NotFoundException) {
        return true
    }
    try {
        var event = parser.eventType
        while (event != XmlPullParser.START_TAG && event != XmlPullParser.END_DOCUMENT) {
            event = parser.next()
        }
        return parser.name == "vector"
    } finally {
        parser.close()
    }
}

private fun KeyboardLayoutPanel.titleRes(): Int = when (this) {
    KeyboardLayoutPanel.Main -> R.string.keyboard_layout_panel_main
    KeyboardLayoutPanel.AiEdit -> R.string.keyboard_layout_panel_ai_edit
    KeyboardLayoutPanel.Recording -> R.string.keyboard_layout_panel_recording
}

private fun BlockDef.validSizesFor(
    layout: KeyboardLayout,
    selectedIndex: Int,
    block: BlockInstance
): List<BlockSize> {
    val currentSize = BlockSize(block.placement.width, block.placement.height)
    val sizes = allowedSizes.filter { size ->
        val placement = block.placement.copy(
            width = size.width,
            height = size.height
        ).coerceInto(layout.gridSize)
        placement.withinGrid(layout.gridSize) && !layout.hasCollision(selectedIndex, placement)
    }
    return if (sizes.any { it == currentSize }) sizes else listOf(currentSize) + sizes
}

private fun KeyboardLayout.clearedRequiredOnly(
    defaultLayout: KeyboardLayout,
    registry: BlockDefRegistry
): KeyboardLayout {
    val requiredIds = registry.defsFor(panel)
        .filter { it.required }
        .map { it.id }
        .toSet()
    if (requiredIds.isEmpty()) return copy(blocks = emptyList())

    val keptBlocks = blocks
        .filter { it.defId in requiredIds }
        .distinctBy { it.defId }
    val missingIds = requiredIds - keptBlocks.map { it.defId }.toSet()
    val restoredBlocks = defaultLayout.blocks.filter { it.defId in missingIds }
    val nextBlocks = keptBlocks + restoredBlocks
    val nextGrid = if (nextBlocks.all { it.placement.snapped().withinGrid(gridSize) }) {
        gridSize
    } else {
        GridSize(
            cols = maxOf(gridSize.cols, defaultLayout.gridSize.cols),
            rows = maxOf(gridSize.rows, defaultLayout.gridSize.rows)
        ).coerceForPanel(panel)
    }
    return copy(
        gridSize = nextGrid,
        blocks = nextBlocks.filter { it.placement.snapped().withinGrid(nextGrid) }
    )
}

private fun BlockPlacement.coerceInto(grid: GridSize): BlockPlacement {
    val snapped = snapped()
    return snapped.copy(
        col = snapped.col.coerceIn(0f, (grid.cols - snapped.width).coerceAtLeast(0f)),
        row = snapped.row.coerceIn(0f, (grid.rows - snapped.height).coerceAtLeast(0f))
    )
}

private fun BlockPlacement.coerceIntoGridBounds(grid: GridSize): BlockPlacement = copy(
    col = col.coerceIn(0f, (grid.cols - width).coerceAtLeast(0f)),
    row = row.coerceIn(0f, (grid.rows - height).coerceAtLeast(0f))
)

private fun KeyboardLayout.hasCollision(index: Int, placement: BlockPlacement): Boolean = blocks.anyIndexed { otherIndex, other ->
    otherIndex != index && placement.overlaps(other.placement.snapped())
}

private fun findFreePlacement(layout: KeyboardLayout, def: BlockDef): BlockPlacement? {
    val size = def.defaultSize
    var row = 0f
    while (row + size.height <= layout.gridSize.rows) {
        var col = 0f
        while (col + size.width <= layout.gridSize.cols) {
            val placement = BlockPlacement(col, row, size.width, size.height)
            if (!layout.hasCollision(-1, placement)) return placement
            col += 0.5f
        }
        row += 0.5f
    }
    return null
}

private fun BlockDefRegistry.sharedTrayDefs(panel: KeyboardLayoutPanel): List<BlockDef> {
    if (panel == KeyboardLayoutPanel.Recording) {
        return trayKeyOrder.mapNotNull { key ->
            defsFor(KeyboardLayoutPanel.Recording).firstOrNull { it.trayKey() == key }
        }
    }
    val grouped = allDefs()
        .filter { it.isTrayVisibleIn(panel) }
        .groupBy { it.trayKey() }
    return trayKeyOrder.mapNotNull { key ->
        grouped[key]?.preferredTrayDef()
    }
}

private fun List<BlockDef>.preferredTrayDef(): BlockDef? = firstOrNull { it.extensionActionId == null } ?: firstOrNull()

private fun BlockDefRegistry.targetTrayDef(panel: KeyboardLayoutPanel, displayDef: BlockDef): BlockDef? = defsFor(panel)
    .filter { it.trayKey() == displayDef.trayKey() }
    .sortedWith(
        compareBy<BlockDef> { it.viewId == null }
            .thenBy { it.extensionActionId != null }
    )
    .firstOrNull()

private fun BlockDef.isRecordingGesture(): Boolean = id == "gesture_cancel" || id == "gesture_send"

private fun BlockDef.isTrayVisibleIn(panel: KeyboardLayoutPanel): Boolean = when {
    isRecordingGesture() -> false
    panel == KeyboardLayoutPanel.Main && trayKey() == "return_main" -> false
    else -> true
}

private fun BlockDef.trayKey(): String = when (id) {
    "ai_mic" -> "mic"
    "ai_info" -> "status"
    "ai_back" -> "return_main"
    "ai_apply" -> "apply_preset"
    "ai_select_all" -> "select_all"
    "ai_delete" -> "backspace"
    "ai_cursor_left" -> "cursor_left"
    "ai_cursor_right" -> "cursor_right"
    "ai_copy" -> "copy"
    "ai_paste" -> "paste"
    "ai_numpad" -> "numpad"
    "ai_select" -> "select"
    "ai_space" -> "space"
    "ai_move_start" -> "move_start"
    "ai_move_end" -> "move_end"
    else -> extensionActionId ?: id
}

private val trayKeyOrder = listOf(
    "mic",
    "status",
    "ai_edit",
    "postproc",
    "apply_preset",
    "clipboard",
    "backspace",
    "settings",
    "prompt_picker",
    "switch_ime",
    "floating_keyboard_toggle",
    "return_main",
    "enter",
    "numpad",
    "select",
    "select_all",
    "copy",
    "asr_history",
    "paste",
    "cursor_left",
    "cursor_right",
    "prev_punct",
    "next_punct",
    "move_start",
    "move_end",
    "punct_left",
    "space",
    "punct_right",
    "vendor_picker",
    "undo",
    "hide_keyboard",
    "silence_autostop_toggle",
    "keep_screen_on_while_recording_toggle",
    "mic_tap_toggle",
    "auto_enter_after_asr_toggle",
    "gesture_cancel",
    "gesture_send"
)

private inline fun <T> Iterable<T>.anyIndexed(predicate: (Int, T) -> Boolean): Boolean {
    forEachIndexed { index, item ->
        if (predicate(index, item)) return true
    }
    return false
}

private fun copyToSystemClipboard(context: Context, text: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    manager.setPrimaryClip(ClipData.newPlainText("keyboard-layout", text))
}
