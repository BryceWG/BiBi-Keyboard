/**
 * 润色 Prompt 全屏编辑页 Compose 宿主。
 *
 * 归属模块：ui/settings/asr
 */
@file:Suppress("FunctionName")

package com.brycewg.asrkb.ui.settings.asr

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BaseActivity
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButton
import com.brycewg.asrkb.ui.settings.compose.components.SettingsActionButtonRow
import com.brycewg.asrkb.ui.settings.compose.components.SettingsDetailScaffold
import com.brycewg.asrkb.ui.settings.compose.components.SettingsLazyColumn
import com.brycewg.asrkb.ui.settings.compose.components.SettingsTextField
import com.brycewg.asrkb.ui.settings.compose.core.BibiSettingsTheme
import com.brycewg.asrkb.ui.settings.compose.core.BibiUiMode
import com.brycewg.asrkb.ui.settings.compose.core.SettingsLayoutMetrics

class LargePromptEditorActivity : BaseActivity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val initialText = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        setContent {
            val uiMode = BibiUiMode.fromId(prefs.settingsUiMode)
            BibiSettingsTheme(
                uiMode = uiMode,
                themeMode = prefs.settingsThemeMode
            ) {
                LargePromptEditorScreen(
                    uiMode = uiMode,
                    initialText = initialText,
                    onDone = ::finishWithResult
                )
            }
        }
    }

    private fun finishWithResult(text: String) {
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_RESULT_TEXT, text)
        )
        finish()
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_RESULT_TEXT = "result_text"
    }
}

@Composable
private fun LargePromptEditorScreen(
    uiMode: BibiUiMode,
    initialText: String,
    onDone: (String) -> Unit
) {
    var text by rememberSaveable(initialText) { mutableStateOf(initialText) }
    BackHandler { onDone(text) }
    SettingsDetailScaffold(
        uiMode = uiMode,
        titleRes = R.string.title_llm_prompt_editor,
        onBack = { onDone(text) }
    ) { innerPadding, scrollModifier ->
        SettingsLazyColumn(
            uiMode = uiMode,
            modifier = Modifier.fillMaxSize(),
            miuixScrollModifier = scrollModifier,
            contentPadding = SettingsLayoutMetrics.pageContentPadding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(SettingsLayoutMetrics.SectionSpacing)
        ) {
            item("editor") {
                SettingsTextField(
                    uiMode = uiMode,
                    value = text,
                    onValueChange = { text = it },
                    label = stringResource(R.string.label_llm_prompt),
                    singleLine = false,
                    minLines = 8,
                    keyboardType = KeyboardType.Text
                )
            }
            item("done") {
                SettingsActionButtonRow(uiMode = uiMode) {
                    SettingsActionButton(
                        uiMode = uiMode,
                        text = stringResource(R.string.btn_llm_prompt_editor_done),
                        onClick = { onDone(text) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
