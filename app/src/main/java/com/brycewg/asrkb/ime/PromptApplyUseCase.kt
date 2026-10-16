package com.brycewg.asrkb.ime

import android.content.Context
import android.util.Log
import android.view.inputmethod.InputConnection
import com.brycewg.asrkb.R
import com.brycewg.asrkb.asr.LlmPostProcessor
import com.brycewg.asrkb.store.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class PromptApplyUseCase(
    private val context: Context,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
    private val inputHelper: InputConnectionHelper,
    private val llmPostProcessor: LlmPostProcessor,
    private val uiListenerProvider: () -> KeyboardActionHandler.UiListener?,
    private val saveUndoSnapshot: (InputConnection) -> Unit,
    private val getLastAsrCommitText: () -> String?,
    private val updateSessionContext: ((KeyboardSessionContext) -> KeyboardSessionContext) -> Unit,
    private val logTag: String
) {
    fun apply(ic: InputConnection?, promptOverride: String?, promptTitle: String? = null) {
        if (ic == null) return
        scope.launch {
            if (!prefs.hasLlmKeys()) {
                uiListenerProvider()?.onStatusMessage(
                    context.getString(R.string.hint_need_llm_keys)
                )
                return@launch
            }

            val target = resolveTargetText(ic) ?: return@launch

            uiListenerProvider()?.onStatusMessage(
                context.aiProcessingStatusText(resolvePromptTitle(promptOverride, promptTitle)),
                reportError = false
            )

            val res = try {
                com.brycewg.asrkb.util.AsrFinalFilters.applyWithAi(
                    context,
                    prefs,
                    target.text,
                    llmPostProcessor,
                    promptOverride = promptOverride,
                    forceAi = true
                )
            } catch (t: Throwable) {
                Log.e(logTag, "apply prompt failed", t)
                null
            }

            val out = res?.text ?: target.text
            val ok = res?.ok == true

            saveUndoSnapshot(ic)
            when (target.mode) {
                TargetMode.SELECTION -> {
                    inputHelper.commitText(ic, out)
                }
                TargetMode.LAST_ASR -> {
                    val replaced = inputHelper.replaceText(ic, target.text, out)
                    if (!replaced) {
                        uiListenerProvider()?.onStatusMessage(
                            context.getString(R.string.status_last_asr_not_found)
                        )
                        return@launch
                    }
                    updateSessionContext { prev -> prev.copy(lastAsrCommitText = out) }
                }
                TargetMode.ENTIRE -> {
                    val snapshot = inputHelper.captureUndoSnapshot(ic)
                    inputHelper.clearAllText(ic, snapshot)
                    inputHelper.commitText(ic, out)
                }
            }

            uiListenerProvider()?.onVibrate()

            updateSessionContext { prev ->
                prev.copy(
                    lastPostprocCommit = if (ok && out.isNotEmpty() && out != target.text) {
                        PostprocCommit(processed = out, raw = target.text)
                    } else {
                        null
                    }
                )
            }

            if (!ok) {
                uiListenerProvider()?.onStatusMessage(
                    context.getString(R.string.status_llm_failed_used_raw)
                )
            } else {
                uiListenerProvider()?.onStatusMessage(context.getString(R.string.status_idle))
            }
        }
    }

    private fun resolvePromptTitle(promptOverride: String?, promptTitle: String?): String? {
        promptTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (promptOverride.isNullOrBlank()) {
            return prefs.activePromptTitle.takeIf { it.isNotBlank() }
        }
        return prefs.getPromptPresets()
            .firstOrNull { it.content == promptOverride }
            ?.title
            ?.takeIf { it.isNotBlank() }
            ?: prefs.activePromptTitle.takeIf { it.isNotBlank() }
    }

    private data class Target(val mode: TargetMode, val text: String)

    private enum class TargetMode {
        SELECTION,
        LAST_ASR,
        ENTIRE
    }

    private fun resolveTargetText(ic: InputConnection): Target? {
        val selected = try {
            inputHelper.getSelectedText(ic, 0)?.toString()
        } catch (_: Throwable) {
            null
        }
        if (!selected.isNullOrEmpty()) return Target(TargetMode.SELECTION, selected)

        if (prefs.aiEditDefaultToLastAsr) {
            val last = getLastAsrCommitText()
            if (!last.isNullOrEmpty()) return Target(TargetMode.LAST_ASR, last)
        }

        val before = inputHelper.getTextBeforeCursor(ic, 10000)?.toString() ?: ""
        val after = inputHelper.getTextAfterCursor(ic, 10000)?.toString() ?: ""
        val all = before + after
        if (all.isEmpty()) {
            uiListenerProvider()?.onStatusMessage(context.getString(R.string.hint_cannot_read_text))
            return null
        }
        return Target(TargetMode.ENTIRE, all)
    }
}
