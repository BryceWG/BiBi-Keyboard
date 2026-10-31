/** 录音布局的单个 Prompt 按钮配置弹窗。归属模块：ui/settings。 */
package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ime.layout.RecordingLayoutButtons
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsAlertDialog
import com.brycewg.asrkb.ui.settings.compose.components.MaterialSettingsDialogExitEffect
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceItem
import com.brycewg.asrkb.ui.settings.compose.components.SettingsChoiceItemRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogAction
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDialogActionRow
import com.brycewg.asrkb.ui.settings.compose.components.animateSettingsDialogExitAlpha
import com.brycewg.asrkb.ui.settings.compose.components.rememberSettingsDialogExitController
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.layout.DialogDefaults
import top.yukonga.miuix.kmp.overlay.OverlayDialog

@Composable
internal fun RecordingPromptButtonDialog(
    uiMode: BibiUiMode,
    initialConfig: Map<String, String>,
    isAdding: Boolean,
    onDismiss: () -> Unit,
    onSave: (Map<String, String>) -> Unit
) {
    val context = LocalContext.current
    val presets = remember { Prefs(context).getPromptPresets() }
    var selectedId by remember { mutableStateOf(initialConfig[RecordingLayoutButtons.PROMPT_ID].orEmpty()) }
    val exit = rememberSettingsDialogExitController()
    val dialogTitle = stringResource(R.string.keyboard_layout_prompt_polish)
    val actions = listOf(
        SettingsDialogAction(
            text = stringResource(android.R.string.cancel),
            onClick = { exit.dismiss(onDismiss) }
        ),
        SettingsDialogAction(
            text = stringResource(if (isAdding) R.string.keyboard_layout_add else R.string.btn_save),
            enabled = presets.any { it.id == selectedId },
            primary = true,
            onClick = {
                exit.dismiss {
                    onSave(
                        mapOf(RecordingLayoutButtons.PROMPT_ID to selectedId)
                    )
                }
            }
        )
    )
    val fields: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = SettingsLayoutMetrics.DialogContentMaxHeight)
                .verticalScroll(rememberScrollState())
                .selectableGroup()
                .padding(vertical = 8.dp)
        ) {
            presets.forEachIndexed { index, preset ->
                SettingsChoiceItemRow(
                    item = SettingsChoiceItem(title = preset.title, originalIndex = index),
                    selected = preset.id == selectedId,
                    uiMode = uiMode,
                    index = index,
                    count = presets.size,
                    miuixInsideMargin = PaddingValues(
                        start = DialogDefaults.insideMargin.width,
                        end = DialogDefaults.insideMargin.width,
                        top = BasicComponentDefaults.InsideMargin.calculateTopPadding(),
                        bottom = BasicComponentDefaults.InsideMargin.calculateBottomPadding()
                    ),
                    onClick = { selectedId = preset.id }
                )
            }
        }
    }
    when (uiMode) {
        BibiUiMode.Material -> {
            val alpha = animateSettingsDialogExitAlpha(show = exit.show, label = "RecordingPromptButtonDialogAlpha")
            MaterialSettingsDialogExitEffect(show = exit.show, onFinished = exit::finish)
            MaterialSettingsAlertDialog(
                title = dialogTitle,
                onDismissRequest = { exit.dismiss(onDismiss) },
                modifier = Modifier.graphicsLayer(alpha = alpha),
                text = fields,
                buttons = { SettingsDialogActionRow(uiMode = uiMode, actions = actions) }
            )
        }
        BibiUiMode.Miuix -> OverlayDialog(
            show = exit.show,
            title = dialogTitle,
            // 列表的点击反馈铺满弹窗；内容留白由行和底部按钮各自提供。
            insideMargin = DpSize(0.dp, DialogDefaults.insideMargin.height),
            onDismissRequest = { exit.dismiss(onDismiss) },
            onDismissFinished = exit::finish
        ) {
            fields()
            SettingsDialogActionRow(
                uiMode = uiMode,
                actions = actions,
                modifier = Modifier.padding(horizontal = DialogDefaults.insideMargin.width)
            )
        }
    }
}
