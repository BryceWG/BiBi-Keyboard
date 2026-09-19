/**
 * AI 设置页提示词预设路由区块。
 *
 * 归属模块：ui/settings/compose/screens
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.compose.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.PromptPreset
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode

@Composable
internal fun AiPromptPresetRouteSection(
    uiMode: BibiUiMode,
    preset: PromptPreset?,
    focusTitleAfterAdd: Boolean,
    onFocusedTitle: () -> Unit,
    onChoosePreset: () -> Unit,
    onTitleChange: (String) -> Unit,
    onSkillChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onAddPreset: () -> Unit,
    onDeletePreset: () -> Unit
) {
    AiSection(uiMode = uiMode, titleRes = R.string.label_llm_prompt_presets) {
        PromptPresetSection(
            uiMode = uiMode,
            preset = preset,
            focusTitleAfterAdd = focusTitleAfterAdd,
            onFocusedTitle = onFocusedTitle,
            onChoosePreset = onChoosePreset,
            onTitleChange = onTitleChange,
            onSkillChange = onSkillChange,
            onContentChange = onContentChange,
            onAddPreset = onAddPreset,
            onDeletePreset = onDeletePreset
        )
    }
}

@Composable
internal fun PromptPresetSection(
    uiMode: BibiUiMode,
    preset: PromptPreset?,
    focusTitleAfterAdd: Boolean,
    onFocusedTitle: () -> Unit,
    onChoosePreset: () -> Unit,
    onTitleChange: (String) -> Unit,
    onSkillChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onAddPreset: () -> Unit,
    onDeletePreset: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusTitleAfterAdd) {
        if (focusTitleAfterAdd) {
            focusRequester.requestFocus()
            onFocusedTitle()
        }
    }
    var itemIndex = 0
    val itemCount = 4
    AiValuePreference(
        titleRes = R.string.label_llm_prompt_presets,
        value = preset?.title.orEmpty().ifBlank { stringResource(R.string.untitled_preset) },
        uiMode = uiMode,
        index = itemIndex++,
        count = itemCount,
        onClick = onChoosePreset
    )
    AiTextField(
        uiMode = uiMode,
        value = preset?.title.orEmpty(),
        onValueChange = onTitleChange,
        label = stringResource(R.string.label_llm_prompt_title),
        modifier = Modifier.focusRequester(focusRequester),
        index = itemIndex++,
        count = itemCount
    )
    AiTextField(
        uiMode = uiMode,
        value = preset?.skill.orEmpty(),
        onValueChange = onSkillChange,
        label = stringResource(R.string.label_llm_prompt_skill),
        index = itemIndex++,
        count = itemCount
    )
    AiTextField(
        uiMode = uiMode,
        value = preset?.content.orEmpty(),
        onValueChange = onContentChange,
        label = stringResource(R.string.label_llm_prompt),
        singleLine = false,
        minLines = 5,
        index = itemIndex,
        count = itemCount
    )
    AiButtonRow(uiMode = uiMode) {
        AiButton(
            uiMode = uiMode,
            textRes = R.string.btn_add_preset,
            onClick = onAddPreset
        )
        AiButton(
            uiMode = uiMode,
            textRes = R.string.btn_delete_preset,
            onClick = onDeletePreset
        )
    }
}
