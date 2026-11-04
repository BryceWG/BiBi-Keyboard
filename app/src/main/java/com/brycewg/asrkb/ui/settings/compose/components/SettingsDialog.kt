/**
 * Compose 设置消息与进度弹窗。
 *
 * 归属模块：ui/settings/compose/components
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text as MiuixText
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal data class SettingsMessageDialogState(
    val title: String,
    val message: String,
    val confirmText: String,
    val dismissText: String? = null,
    val onDismissAction: (() -> Unit)? = null,
    val onConfirm: (() -> Unit)? = null
)

internal data class SettingsProgressDialogState(
    val message: String,
    val cancelText: String? = null,
    val onCancel: (() -> Unit)? = null
)

internal data class SettingsLongTextDialogState(
    val title: String,
    val text: String,
    val confirmText: String
)

@Composable
internal fun SettingsMessageDialog(
    state: SettingsMessageDialogState?,
    uiMode: BibiUiMode,
    onDismiss: () -> Unit
) {
    val visibleState = state ?: return
    var show by remember(visibleState) { mutableStateOf(true) }
    var afterDismiss by remember(visibleState) { mutableStateOf<(() -> Unit)?>(null) }
    val scope = rememberCoroutineScope()
    val dismiss: (() -> Unit) -> Unit = { action ->
        if (show) {
            afterDismiss = action
            show = false
        }
    }
    when (uiMode) {
        BibiUiMode.Material -> MaterialMessageDialog(
            state = visibleState,
            show = show,
            onDismiss = {
                dismiss {}
                scope.launch {
                    kotlinx.coroutines.delay(SETTINGS_DIALOG_EXIT_MILLIS.toLong())
                    afterDismiss?.invoke()
                    onDismiss()
                }
            },
            onDismissAfter = { action ->
                dismiss(action)
                scope.launch {
                    kotlinx.coroutines.delay(SETTINGS_DIALOG_EXIT_MILLIS.toLong())
                    afterDismiss?.invoke()
                    onDismiss()
                }
            }
        )

        BibiUiMode.Miuix -> MiuixMessageDialog(
            state = visibleState,
            show = show,
            onDismiss = { dismiss {} },
            onDismissAfter = dismiss,
            onDismissFinished = {
                afterDismiss?.invoke()
                onDismiss()
            }
        )
    }
}

@Composable
internal fun SettingsProgressDialog(
    state: SettingsProgressDialogState?,
    uiMode: BibiUiMode,
    onDismiss: () -> Unit
) {
    val visibleState = state ?: return
    when (uiMode) {
        BibiUiMode.Material -> MaterialProgressDialog(visibleState, onDismiss)
        BibiUiMode.Miuix -> MiuixProgressDialog(visibleState, onDismiss)
    }
}

@Composable
internal fun SettingsLongTextDialog(
    state: SettingsLongTextDialogState?,
    uiMode: BibiUiMode,
    onDismiss: () -> Unit
) {
    val visibleState = state ?: return
    var show by remember(visibleState) { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    fun dismissWithAnimation() {
        if (!show) return
        show = false
        if (uiMode == BibiUiMode.Material) {
            scope.launch {
                kotlinx.coroutines.delay(SETTINGS_DIALOG_EXIT_MILLIS.toLong())
                onDismiss()
            }
        }
    }
    when (uiMode) {
        BibiUiMode.Material -> MaterialLongTextDialog(
            state = visibleState,
            show = show,
            onDismiss = ::dismissWithAnimation
        )

        BibiUiMode.Miuix -> MiuixLongTextDialog(
            state = visibleState,
            show = show,
            onDismiss = ::dismissWithAnimation,
            onDismissFinished = onDismiss
        )
    }
}

@Composable
private fun MaterialMessageDialog(
    state: SettingsMessageDialogState,
    show: Boolean,
    onDismiss: () -> Unit,
    onDismissAfter: (() -> Unit) -> Unit
) {
    val alpha by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = tween(SETTINGS_DIALOG_EXIT_MILLIS),
        label = "MaterialMessageDialogAlpha"
    )
    MaterialSettingsAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.graphicsLayer(alpha = alpha),
        title = state.title,
        text = { MaterialText(state.message) },
        buttons = {
            MaterialSettingsDialogButtonRow(
                actions = listOfNotNull(
                    state.dismissText?.let { dismissText ->
                        MaterialSettingsDialogAction(
                            text = dismissText,
                            onClick = {
                                onDismissAfter { state.onDismissAction?.invoke() }
                            }
                        )
                    },
                    MaterialSettingsDialogAction(
                        text = state.confirmText,
                        onClick = {
                            onDismissAfter { state.onConfirm?.invoke() }
                        }
                    )
                )
            )
        }
    )
}

@Composable
private fun MaterialLongTextDialog(
    state: SettingsLongTextDialogState,
    show: Boolean,
    onDismiss: () -> Unit
) {
    val alpha by animateFloatAsState(
        targetValue = if (show) 1f else 0f,
        animationSpec = tween(SETTINGS_DIALOG_EXIT_MILLIS),
        label = "MaterialLongTextDialogAlpha"
    )
    MaterialSettingsAlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.graphicsLayer(alpha = alpha),
        title = state.title,
        text = {
            LicenseTextContent(
                text = state.text,
                uiMode = BibiUiMode.Material,
                modifier = Modifier.heightIn(max = SettingsLayoutMetrics.DialogContentMaxHeight)
            )
        },
        buttons = {
            MaterialSettingsDialogButtonRow(
                actions = listOf(
                    MaterialSettingsDialogAction(
                        text = state.confirmText,
                        onClick = onDismiss
                    )
                )
            )
        }
    )
}

@Composable
private fun MiuixMessageDialog(
    state: SettingsMessageDialogState,
    show: Boolean,
    onDismiss: () -> Unit,
    onDismissAfter: (() -> Unit) -> Unit,
    onDismissFinished: () -> Unit
) {
    OverlayDialog(
        show = show,
        title = state.title,
        summary = state.message,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished
    ) {
        SettingsDialogActionRow(
            uiMode = BibiUiMode.Miuix,
            actions = listOfNotNull(
                state.dismissText?.let { dismissText ->
                    SettingsDialogAction(
                        text = dismissText,
                        onClick = {
                            onDismissAfter { state.onDismissAction?.invoke() }
                        }
                    )
                },
                SettingsDialogAction(
                    text = state.confirmText,
                    onClick = {
                        onDismissAfter { state.onConfirm?.invoke() }
                    },
                    primary = true
                )
            )
        )
    }
}

@Composable
private fun MiuixLongTextDialog(
    state: SettingsLongTextDialogState,
    show: Boolean,
    onDismiss: () -> Unit,
    onDismissFinished: () -> Unit
) {
    OverlayDialog(
        show = show,
        title = state.title,
        onDismissRequest = onDismiss,
        onDismissFinished = onDismissFinished
    ) {
        LicenseTextContent(
            text = state.text,
            uiMode = BibiUiMode.Miuix,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = SettingsLayoutMetrics.DialogContentMaxHeight)
                .padding(bottom = SettingsLayoutMetrics.DialogContentBottomPadding)
        )
        SettingsDialogActionRow(
            uiMode = BibiUiMode.Miuix,
            actions = listOf(
                SettingsDialogAction(
                    text = state.confirmText,
                    onClick = onDismiss,
                    primary = true
                )
            )
        )
    }
}

@Composable
private fun MaterialProgressDialog(
    state: SettingsProgressDialogState,
    onDismiss: () -> Unit
) {
    MaterialSettingsAlertDialog(
        onDismissRequest = {},
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.width(SettingsLayoutMetrics.DialogProgressMaterialSpacing))
                MaterialText(
                    text = state.message,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        },
        buttons = {
            progressCancelAction(state, onDismiss)?.let { action ->
                MaterialSettingsDialogButtonRow(actions = listOf(action))
            }
        }
    )
}

@Composable
private fun MiuixProgressDialog(
    state: SettingsProgressDialogState,
    onDismiss: () -> Unit
) {
    OverlayDialog(
        show = true,
        onDismissRequest = null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    bottom = if (state.cancelText == null) {
                        0.dp
                    } else {
                        SettingsLayoutMetrics.DialogContentBottomPadding
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InfiniteProgressIndicator(
                color = MiuixTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.width(SettingsLayoutMetrics.DialogProgressMiuixSpacing))
            MiuixText(
                text = state.message,
                fontWeight = FontWeight.Medium
            )
        }
        state.cancelText?.let { cancelText ->
            SettingsDialogActionRow(
                uiMode = BibiUiMode.Miuix,
                actions = listOf(
                    SettingsDialogAction(
                        text = cancelText,
                        onClick = {
                            state.onCancel?.invoke() ?: onDismiss()
                        }
                    )
                )
            )
        }
    }
}

@Composable
private fun LicenseTextContent(
    text: String,
    uiMode: BibiUiMode,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    SelectionContainer {
        when (uiMode) {
            BibiUiMode.Material -> MaterialText(
                text = text,
                modifier = modifier.verticalScroll(scrollState),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )

            BibiUiMode.Miuix -> MiuixText(
                text = text,
                modifier = modifier.verticalScroll(scrollState),
                style = MiuixTheme.textStyles.body2,
                fontFamily = FontFamily.Monospace,
                color = MiuixTheme.colorScheme.onSurface
            )
        }
    }
}

private fun progressCancelAction(
    state: SettingsProgressDialogState,
    onDismiss: () -> Unit
): MaterialSettingsDialogAction? {
    val cancelText = state.cancelText ?: return null
    return MaterialSettingsDialogAction(
        text = cancelText,
        onClick = {
            state.onCancel?.invoke() ?: onDismiss()
        }
    )
}
