/**
 * IME 面板渲染器。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager
import com.brycewg.asrkb.store.debug.StreamingPreviewDiag
import java.util.WeakHashMap

internal class ImeUiRenderer(
    private val context: Context,
    private val prefs: Prefs,
    private val views: ImeViewRefs,
    private val inputHelper: InputConnectionHelper,
    private val actionHandler: KeyboardActionHandler,
    private val inputConnectionProvider: () -> android.view.inputmethod.InputConnection?,
    private val performKeyHaptic: (View?) -> Unit,
    private val isAiEditPanelVisible: () -> Boolean,
    private val micGestureController: () -> MicGestureController?,
    private val downloadClipboardFileById: (String) -> Unit,
    private val markShownClipboardText: (String) -> Unit,
    private val copyTextToSystemClipboard: (label: String, text: String) -> Boolean
) {
    private enum class RenderMode {
        Idle,
        Listening,
        Processing,
        AiProcessing,
        AiEditListening,
        AiEditProcessing
    }

    private var clipboardPreviewTimeout: Runnable? = null
    private var aiEditHintResetRunnable: Runnable? = null
    private var postprocessUndoTimeout: Runnable? = null
    private var lastRenderMode: RenderMode? = null
    private var forceNextStructuralRender: Boolean = true
    private var lastPreviewWritten: String? = null
    private val imageResCache = WeakHashMap<android.widget.ImageView, Int>()

    fun forceStructuralRenderOnNextFrame() {
        forceNextStructuralRender = true
    }

    fun render(state: KeyboardState) {
        val renderMode = state.toRenderMode()
        if (forceNextStructuralRender || lastRenderMode != renderMode) {
            forceNextStructuralRender = false
            lastRenderMode = renderMode
            when (state) {
                is KeyboardState.Idle -> updateUiIdle()
                is KeyboardState.Listening -> updateUiListening(state)
                is KeyboardState.Processing -> updateUiProcessing()
                is KeyboardState.AiProcessing -> updateUiAiProcessing(state)
                is KeyboardState.AiEditListening -> updateUiAiEditListening()
                is KeyboardState.AiEditProcessing -> updateUiAiEditProcessing()
            }
        } else if (state is KeyboardState.Listening) {
            showRecordingGesturesOverlay(state)
        } else if (state is KeyboardState.AiProcessing) {
            updateAiProcessingLabel(state)
        }

        // 更新中间结果到 composing
        if (state is KeyboardState.Listening && state.partialText != null) {
            inputConnectionProvider()?.let { ic ->
                val sameText = lastPreviewWritten == state.partialText
                if (sameText && DebugLogManager.isRecording()) {
                    try {
                        DebugLogManager.log(
                            "ime",
                            "preview_render",
                            mapOf(
                                "sameText" to true,
                                "fp" to StreamingPreviewDiag.fingerprint(state.partialText)
                            )
                        )
                    } catch (_: Throwable) { }
                }
                lastPreviewWritten = state.partialText
                inputHelper.setStreamingPreview(ic, state.partialText)
            }
        }

        updateAiEditInfoBar(state)
    }

    fun showStatusMessage(message: String, reportError: Boolean = true) {
        clearStatusTextStyle()
        setTextIfChanged(views.txtStatusText, message)
        enableStatusMarquee()

        val isError = message.contains("错误", ignoreCase = true) ||
            message.contains("失败", ignoreCase = true) ||
            message.contains("异常", ignoreCase = true) ||
            message.contains("error", ignoreCase = true) ||
            message.contains("failed", ignoreCase = true) ||
            message.contains("failure", ignoreCase = true) ||
            message.contains("exception", ignoreCase = true) ||
            message.contains("invalid", ignoreCase = true) ||
            message.contains(Regex("\\b(401|403|404|500|502|503)\\b"))

        if (reportError && isError) {
            val copied = try {
                copyTextToSystemClipboard("ASR Error", message)
            } catch (e: Exception) {
                android.util.Log.e("AsrKeyboardService", "Failed to copy error message", e)
                false
            }
            if (copied) {
                Toast.makeText(
                    context,
                    context.getString(R.string.error_auto_copied),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        if (isAiEditPanelVisible()) {
            val tv = views.txtAiEditInfo
            if (tv != null) {
                val state = actionHandler.getCurrentState()
                val allowOverride = when (state) {
                    is KeyboardState.AiEditListening -> state.instruction.isNullOrBlank() || isError
                    else -> true
                }
                if (allowOverride) {
                    applyInfoBarMarquee(tv, enabled = true)
                    setTextIfChanged(tv, message)
                }
            }
        }
    }

    fun updateWaveformSensitivity() {
        views.waveformView?.sensitivity = prefs.waveformSensitivity
    }

    fun updatePostprocIcon() {
        views.btnPostproc?.setImageResource(
            if (prefs.postProcessEnabled) R.drawable.magic_wand_fill else R.drawable.magic_wand
        )
    }

    fun showAiEditFunctionHint(message: String, autoHideMs: Long = 1500L) {
        if (!isAiEditPanelVisible()) return
        val tv = views.txtAiEditInfo ?: return
        applyInfoBarMarquee(tv, enabled = true)
        setTextIfChanged(tv, message)
        aiEditHintResetRunnable?.let(tv::removeCallbacks)
        val restoreRunnable = Runnable { render(actionHandler.getCurrentState()) }
        aiEditHintResetRunnable = restoreRunnable
        tv.postDelayed(restoreRunnable, autoHideMs)
    }

    fun showClipboardPreview(preview: ClipboardPreview) {
        val tv = views.txtStatusText ?: return
        disableStatusMarquee()
        setTextIfChanged(tv, preview.displaySnippet)

        // 限制粘贴板内容为单行显示，避免破坏 UI 布局（txtStatusText 默认已单行，这里冗余保证）
        tv.maxLines = 1
        tv.isSingleLine = true

        // 取消圆角遮罩与额外内边距：使用中心按钮原生背景
        tv.background = null
        tv.setPaddingRelative(0, 0, 0, 0)

        // 启用点击：文本类型为粘贴，文件类型为拉取
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { v ->
            performKeyHaptic(v)
            if (preview.type == ClipboardPreviewType.FILE) {
                val entryId = preview.fileEntryId
                if (!entryId.isNullOrEmpty()) {
                    downloadClipboardFileById(entryId)
                }
            } else {
                actionHandler.handleClipboardPreviewClick(inputConnectionProvider())
            }
        }

        // 若当前处于录音波形显示，临时切换为文本以展示预览
        setVisibilityIfChanged(views.txtStatusText, View.VISIBLE)
        setVisibilityIfChanged(views.waveformView, View.GONE)
        views.waveformView?.stop()

        // 标记最近一次展示的剪贴板内容，避免重复触发
        markShownClipboardText(preview.fullText)

        // 超时自动恢复
        clipboardPreviewTimeout?.let { tv.removeCallbacks(it) }
        val r = Runnable { actionHandler.hideClipboardPreview() }
        clipboardPreviewTimeout = r
        tv.postDelayed(r, 10_000)
    }

    fun hideClipboardPreview() {
        val tv = views.txtStatusText ?: return
        clipboardPreviewTimeout?.let { tv.removeCallbacks(it) }
        clipboardPreviewTimeout = null

        tv.isClickable = false
        tv.isFocusable = false
        tv.setOnClickListener(null)
        tv.background = null
        tv.setPaddingRelative(0, 0, 0, 0)
        // 保持单行显示以匹配中心信息栏设计
        tv.maxLines = 1
        tv.isSingleLine = true
        forceNextStructuralRender = true
        render(actionHandler.getCurrentState())
    }

    fun showRetryChip(label: String) {
        val tv = views.txtStatusText ?: return
        setTextIfChanged(tv, label)
        // 移除芯片样式，仅保持可点击
        tv.background = null
        tv.setPaddingRelative(0, 0, 0, 0)
        // 在中心信息栏展示，并临时隐藏波形
        setVisibilityIfChanged(views.txtStatusText, View.VISIBLE)
        setVisibilityIfChanged(views.waveformView, View.GONE)
        views.waveformView?.stop()
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.handleRetryClick()
        }
    }

    fun showPostprocessUndo(label: String) {
        val tv = views.txtStatusText ?: return
        postprocessUndoTimeout?.let { tv.removeCallbacks(it) }
        postprocessUndoTimeout = null

        setTextIfChanged(tv, label)
        tv.background = null
        tv.setPaddingRelative(0, 0, 0, 0)
        setVisibilityIfChanged(views.txtStatusText, View.VISIBLE)
        setVisibilityIfChanged(views.waveformView, View.GONE)
        views.waveformView?.stop()
        tv.maxLines = 1
        tv.isSingleLine = true
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { v ->
            performKeyHaptic(v)
            val ok = actionHandler.handlePostprocessUndoClick(inputConnectionProvider())
            if (ok) {
                postprocessUndoTimeout?.let { tv.removeCallbacks(it) }
                postprocessUndoTimeout = null
            }
        }

        val r = Runnable {
            postprocessUndoTimeout = null
            hidePostprocessUndo()
        }
        postprocessUndoTimeout = r
        tv.postDelayed(r, 3_000L)
    }

    fun hidePostprocessUndo() {
        val tv = views.txtStatusText ?: return
        postprocessUndoTimeout?.let { tv.removeCallbacks(it) }
        postprocessUndoTimeout = null
        clearStatusTextStyle()
        forceNextStructuralRender = true
        render(actionHandler.getCurrentState())
    }

    fun hideRetryChip() {
        clearStatusTextStyle()
        forceNextStructuralRender = true
    }

    fun clearStatusTextStyle() {
        val tv = views.txtStatusText ?: return
        postprocessUndoTimeout?.let { tv.removeCallbacks(it) }
        postprocessUndoTimeout = null
        enableStatusMarquee()
        tv.isClickable = false
        tv.isFocusable = false
        tv.setOnClickListener(null)
        tv.background = null
        tv.setPaddingRelative(0, 0, 0, 0)
        // 中心信息栏保持单行，以避免布局跳动
        tv.maxLines = 1
        tv.isSingleLine = true
    }

    fun enableStatusMarquee() {
        val tv = views.txtStatusText ?: return
        tv.ellipsize = TextUtils.TruncateAt.MARQUEE
        tv.marqueeRepeatLimit = -1
        tv.isSelected = true
    }

    fun disableStatusMarquee() {
        val tv = views.txtStatusText ?: return
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.isSelected = false
    }

    private fun applyInfoBarMarquee(tv: TextView?, enabled: Boolean) {
        if (tv == null) return
        if (enabled) {
            tv.ellipsize = TextUtils.TruncateAt.MARQUEE
            tv.marqueeRepeatLimit = -1
            tv.isSelected = true
        } else {
            tv.ellipsize = TextUtils.TruncateAt.END
            tv.isSelected = false
        }
    }

    private fun getAiEditGuideText(): String = context.getString(
        if (prefs.micTapToggleEnabled) R.string.ime_ai_edit_guide_tap else R.string.ime_ai_edit_guide_hold
    )

    private fun updateAiEditInfoBar(state: KeyboardState) {
        if (!isAiEditPanelVisible()) return
        val tv = views.txtAiEditInfo ?: return
        applyInfoBarMarquee(tv, enabled = true)
        val text = when (state) {
            is KeyboardState.AiEditListening -> state.instruction?.takeIf { it.isNotBlank() }
                ?: context.getString(R.string.status_ai_edit_listening)

            is KeyboardState.AiEditProcessing -> context.getString(R.string.status_ai_editing)
            else -> getAiEditGuideText()
        }
        setTextIfChanged(tv, text)
    }

    private fun updateUiIdle() {
        hideRecordingGesturesOverlay()
        clearStatusTextStyle()
        // 显示文字，隐藏波形
        setVisibilityIfChanged(views.txtStatusText, View.VISIBLE)
        setTextIfChanged(views.txtStatusText, context.getString(R.string.status_idle))
        setVisibilityIfChanged(views.waveformView, View.GONE)
        views.waveformView?.stop()

        setMicButtons(selected = false, imageRes = R.drawable.microphone)
        setImageResourceIfChanged(views.btnPromptPicker, R.drawable.pencil_simple_line)
        lastPreviewWritten = null
        try {
            DebugLogManager.log(
                "ime",
                "idle_finish_composing",
                mapOf("icNull" to (inputConnectionProvider() == null))
            )
        } catch (_: Throwable) { }
        inputConnectionProvider()?.let { inputHelper.finishComposingText(it) }
    }

    private fun updateUiListening(state: KeyboardState.Listening? = null) {
        clearStatusTextStyle()
        // 隐藏文字，显示波形动画
        setVisibilityIfChanged(views.txtStatusText, View.GONE)
        setVisibilityIfChanged(views.waveformView, View.VISIBLE)
        views.waveformView?.start()

        setMicButtons(selected = true, imageRes = R.drawable.microphone_fill)
        setImageResourceIfChanged(views.btnPromptPicker, R.drawable.pencil_simple_line)
        showRecordingGesturesOverlay(state)
    }

    private fun updateUiProcessing() {
        hideRecordingGesturesOverlay()
        clearStatusTextStyle()
        // 显示文字，隐藏波形
        setVisibilityIfChanged(views.txtStatusText, View.VISIBLE)
        setTextIfChanged(views.txtStatusText, context.getString(R.string.status_recognizing))
        setVisibilityIfChanged(views.waveformView, View.GONE)
        views.waveformView?.stop()

        setMicButtons(selected = false, imageRes = R.drawable.microphone)
        setImageResourceIfChanged(views.btnPromptPicker, R.drawable.pencil_simple_line)
    }

    private fun updateUiAiProcessing(state: KeyboardState.AiProcessing) {
        hideRecordingGesturesOverlay()
        clearStatusTextStyle()
        // 显示文字，隐藏波形
        setVisibilityIfChanged(views.txtStatusText, View.VISIBLE)
        val tv = views.txtStatusText
        setTextIfChanged(tv, context.aiProcessingStatusText(state.promptTitle, state.selectingPrompt))
        tv?.isClickable = true
        tv?.isFocusable = true
        tv?.setOnClickListener { v ->
            performKeyHaptic(v)
            actionHandler.handleInfoBarClick()
        }
        setVisibilityIfChanged(views.waveformView, View.GONE)
        views.waveformView?.stop()

        setMicButtons(selected = false, imageRes = R.drawable.microphone)
        setImageResourceIfChanged(views.btnPromptPicker, R.drawable.pencil_simple_line)
    }

    private fun updateAiProcessingLabel(state: KeyboardState.AiProcessing) {
        val tv = views.txtStatusText ?: return
        val text = context.aiProcessingStatusText(state.promptTitle, state.selectingPrompt)
        if (tv.text?.contentEquals(text) == true) return
        setTextIfChanged(tv, text)
        // 文案变长后重新挂上跑马灯，避免沿用上一次 isSelected 导致不滚动
        tv.isSelected = false
        tv.isSelected = true
    }

    private fun updateUiAiEditListening() {
        hideRecordingGesturesOverlay()
        clearStatusTextStyle()
        // AI Edit 录音状态也使用文字显示（避免与普通录音混淆）
        setVisibilityIfChanged(views.txtStatusText, View.VISIBLE)
        setTextIfChanged(views.txtStatusText, context.getString(R.string.status_ai_edit_listening))
        setVisibilityIfChanged(views.waveformView, View.GONE)
        views.waveformView?.stop()

        setMicButtons(selected = false, imageRes = R.drawable.microphone_fill)
        setImageResourceIfChanged(views.btnPromptPicker, R.drawable.pencil_simple_line_fill)
    }

    private fun updateUiAiEditProcessing() {
        hideRecordingGesturesOverlay()
        clearStatusTextStyle()
        // 显示文字，隐藏波形
        setVisibilityIfChanged(views.txtStatusText, View.VISIBLE)
        setTextIfChanged(views.txtStatusText, context.getString(R.string.status_ai_editing))
        setVisibilityIfChanged(views.waveformView, View.GONE)
        views.waveformView?.stop()

        setMicButtons(selected = false, imageRes = R.drawable.microphone)
        setImageResourceIfChanged(views.btnPromptPicker, R.drawable.pencil_simple_line_fill)
    }

    private fun showRecordingGesturesOverlay(state: KeyboardState.Listening?) {
        setVisibilityIfChanged(views.rowRecordingGestures, View.VISIBLE)
        // 根据点按/长按模式设置按钮文案
        if (prefs.micTapToggleEnabled) {
            setTextIfChanged(
                views.btnGestureCancel,
                context.getString(R.string.label_recording_tap_cancel)
            )
            setTextIfChanged(
                views.btnGestureSend,
                context.getString(R.string.label_recording_tap_send)
            )
        } else {
            setTextIfChanged(
                views.btnGestureCancel,
                context.getString(R.string.label_recording_gesture_cancel)
            )
            setTextIfChanged(
                views.btnGestureSend,
                context.getString(R.string.label_recording_gesture_send)
            )
        }
        applyLockZoneUi(state)
    }

    private fun hideRecordingGesturesOverlay() {
        setVisibilityIfChanged(views.rowRecordingGestures, View.GONE)
        resetLockZoneUi()
        micGestureController()?.resetPressedState()
    }

    private fun applyLockZoneUi(state: KeyboardState.Listening?) {
        val spaceKey = views.btnExtCenter2 ?: return
        if (prefs.micTapToggleEnabled || state == null) {
            resetLockZoneUi()
            return
        }
        setEnabledIfChanged(spaceKey, false)
        setTextIfChanged(
            spaceKey,
            context.getString(
                if (state.lockedBySwipe) R.string.hint_tap_to_stop_recording else R.string.hint_swipe_down_lock
            )
        )
    }

    private fun resetLockZoneUi() {
        setEnabledIfChanged(views.btnExtCenter2, true)
        setTextIfChanged(views.btnExtCenter2, context.getString(R.string.cd_space))
    }

    private fun setVisibilityIfChanged(view: View?, visibility: Int) {
        if (view != null && view.visibility != visibility) {
            view.visibility = visibility
        }
    }

    private fun setTextIfChanged(view: TextView?, text: CharSequence) {
        if (view != null && view.text?.contentEquals(text) != true) {
            view.text = text
        }
    }

    private fun setSelectedIfChanged(view: View?, selected: Boolean) {
        if (view != null && view.isSelected != selected) {
            view.isSelected = selected
        }
    }

    private fun setEnabledIfChanged(view: View?, enabled: Boolean) {
        if (view != null && view.isEnabled != enabled) {
            view.isEnabled = enabled
        }
    }

    private fun setMicButtons(selected: Boolean, imageRes: Int) {
        setSelectedIfChanged(views.btnMic, selected)
        setImageResourceIfChanged(views.btnMic, imageRes)
        setSelectedIfChanged(views.btnAiPanelMic, selected)
        setImageResourceIfChanged(views.btnAiPanelMic, imageRes)
    }

    private fun setImageResourceIfChanged(view: View?, resId: Int) {
        when (view) {
            is android.widget.ImageView -> {
                val current = imageResCache[view]
                if (current != resId) {
                    view.setImageResource(resId)
                    imageResCache[view] = resId
                }
            }
            else -> Unit
        }
    }

    private fun KeyboardState.toRenderMode(): RenderMode = when (this) {
        is KeyboardState.Idle -> RenderMode.Idle
        is KeyboardState.Listening -> RenderMode.Listening
        is KeyboardState.Processing -> RenderMode.Processing
        is KeyboardState.AiProcessing -> RenderMode.AiProcessing
        is KeyboardState.AiEditListening -> RenderMode.AiEditListening
        is KeyboardState.AiEditProcessing -> RenderMode.AiEditProcessing
    }
}
