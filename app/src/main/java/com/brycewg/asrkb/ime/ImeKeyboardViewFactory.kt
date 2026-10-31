/**
 * 输入法主键盘 View 树工厂，替代旧 keyboard_view.xml 主布局。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import android.animation.AnimatorInflater
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.UiColorTokens
import com.brycewg.asrkb.UiColors
import com.brycewg.asrkb.ime.layout.BlockDef
import com.brycewg.asrkb.ime.layout.BlockDefRegistry
import com.brycewg.asrkb.ime.layout.ButtonViewKind
import com.brycewg.asrkb.ime.layout.KeyboardLayoutPanel
import com.brycewg.asrkb.ime.layout.KeyboardLayoutViewTags
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.ui.BibiViewThemes
import com.brycewg.asrkb.ui.widgets.PunctKeyView
import com.brycewg.asrkb.ui.widgets.WaveformView

internal object ImeKeyboardViewFactory {

    fun create(context: Context, prefs: Prefs): View {
        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            clipToPadding = false
            clipChildren = false
            alpha = 0f
            setBackgroundColor(Color.TRANSPARENT)
        }

        val contentPanel = createKeyboardContentPanel(context).apply {
            addView(createMainKeyboard(context))
            addView(createAiEditPanel(context))
            addView(createNumpadPanel(context))
            addView(createClipboardPanel(context))
            addView(createAsrHistoryPanel(context))
            addView(createMicStatusGroup(context))
        }
        val panel = createKeyboardPanel(context).apply {
            addView(contentPanel)
            addView(createDockButton(context, R.id.keyboardDockButtonLeft, R.string.cd_keyboard_dock_left, R.drawable.arrow_left_toggle))
            addView(createDockButton(context, R.id.keyboardDockButtonRight, R.string.cd_keyboard_dock_right, R.drawable.arrow_right_toggle))
            addView(createDragHandleRow(context))
            addView(createResizeHandle(context, R.id.keyboardResizeHandleBottomLeft, Gravity.BOTTOM or Gravity.START))
            addView(createResizeHandle(context, R.id.keyboardResizeHandleBottomRight, Gravity.BOTTOM or Gravity.END))
            addView(createResizeHandle(context, R.id.keyboardResizeHandleTopLeft, Gravity.TOP or Gravity.START))
            addView(createResizeHandle(context, R.id.keyboardResizeHandleTopRight, Gravity.TOP or Gravity.END))
        }
        root.addView(panel)
        root.addView(createSystemBottomSpace(context))
        applyTheme(root, prefs)
        return root
    }

    private fun createKeyboardPanel(context: Context): FrameLayout = FrameLayout(context).apply {
        id = R.id.keyboardFloatingPanel
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        clipToPadding = false
        clipChildren = true
        background = ContextCompat.getDrawable(context, R.drawable.bg_keyboard_container)
        setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8))
    }

    private fun createSystemBottomSpace(context: Context): View = View(context).apply {
        id = R.id.keyboardSystemBottomSpace
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            0
        ).apply {
            gravity = Gravity.BOTTOM
        }
    }

    private fun createKeyboardContentPanel(context: Context): FrameLayout = FrameLayout(context).apply {
        id = R.id.keyboardContentPanel
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        )
        clipToPadding = false
        clipChildren = false
    }

    fun applyTheme(root: View, prefs: Prefs) {
        val context = root.context
        val theme = BibiViewThemes.resolve(context, prefs)
        applyKeyboardPanelBackground(root, prefs, floating = false)
        root.findViewById<View>(R.id.layoutClipboardPanel)?.setBackgroundColor(theme.keyboardBackground)
        root.findViewById<View>(R.id.layoutAsrHistoryPanel)?.setBackgroundColor(theme.keyboardBackground)
        root.findViewById<View>(R.id.keyboardSystemBottomSpace)?.setBackgroundColor(theme.keyboardBackground)
        root.findViewById<View>(R.id.keyboardDragHandle)?.background =
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 9).toFloat()
                setColor(theme.panelSummary)
            }

        iconKeyIds.forEach { id ->
            root.findViewById<ImageButton>(id)?.apply {
                background = BibiViewThemes.roundedRipple(
                    context,
                    theme.keyBackground,
                    theme.ripple,
                    theme.iconKeyRadiusDp,
                    insetDp = theme.keyInsetDp
                )
                imageTintList = ColorStateList.valueOf(theme.keyContent)
            }
        }
        rectKeyIds.forEach { id ->
            root.findViewById<View>(id)?.applyRectKeyTheme(theme)
        }
        applyTaggedKeys(root, theme)

        listOf(R.id.btnPunct2, R.id.btnPunct3).forEach { id ->
            root.findViewById<PunctKeyView>(id)?.apply {
                background = BibiViewThemes.roundedRipple(
                    context,
                    theme.keyBackground,
                    theme.ripple,
                    theme.iconKeyRadiusDp,
                    insetDp = theme.keyInsetDp
                )
                setKeyTextColor(theme.keyContent)
            }
        }

        listOf(R.id.btnMic, R.id.btnAiPanelMic).forEach { id ->
            root.findViewById<ImageButton>(id)?.applyMicButtonTheme(theme)
        }
        root.findViewById<WaveformView>(R.id.waveformView)?.setWaveformColor(theme.primary)

        statusTextIds.forEach { id ->
            root.findViewById<TextView>(id)?.setTextColor(theme.panelSummary)
        }
        root.findViewById<TextView>(R.id.txtStatusText)?.setTextColor(theme.panelContent)
        root.findViewById<TextView>(R.id.txtAiEditInfo)?.setTextColor(theme.panelContent)
        root.findViewById<TextView>(R.id.clip_txtCount)?.setTextColor(theme.panelContent)
        root.findViewById<TextView>(R.id.asr_hist_txtCount)?.setTextColor(theme.panelContent)
    }

    fun applyKeyboardPanelBackground(root: View, prefs: Prefs, floating: Boolean) {
        val context = root.context
        val theme = BibiViewThemes.resolve(context, prefs)
        val panel = root.findViewById<View>(R.id.keyboardFloatingPanel)
        root.setBackgroundColor(if (floating) Color.TRANSPARENT else theme.keyboardBackground)
        if (panel == null) {
            root.setBackgroundColor(theme.keyboardBackground)
            return
        }
        panel.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = if (floating) {
                dp(context, FLOATING_PANEL_RADIUS_DP).toFloat()
            } else {
                0f
            }
            setColor(theme.keyboardBackground)
        }
        panel.clipToOutline = floating
    }

    private fun createMainKeyboard(context: Context): View {
        val container = FrameLayout(context).apply {
            id = R.id.layoutMainKeyboard
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val canvas = FrameLayout(context).apply {
            id = R.id.keyboardLayoutCanvas
            clipChildren = false
            clipToPadding = false
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        addMainKeyboardButtons(context, canvas)
        container.addView(canvas)
        container.addView(createRecordingGestureLayer(context))
        return container
    }

    private fun addMainKeyboardButtons(context: Context, canvas: FrameLayout) {
        BlockDefRegistry.default.defsFor(KeyboardLayoutPanel.Main)
            .filter { it.viewId != R.id.groupMicStatus }
            .forEach { def -> canvas.addFrameChild(createLayoutBlockButton(context, KeyboardLayoutPanel.Main, def)) }
    }

    private fun createStatusFrame(context: Context, def: BlockDef): View = keyFrame(
        context,
        def.viewId ?: View.NO_ID
    ).apply {
        contentDescription = context.getString(def.labelRes)
        isClickable = true
        isFocusable = true
        clipChildren = true
        clipToPadding = true
        val textId = if (def.viewId == R.id.aiEditInfoBar) R.id.txtAiEditInfo else R.id.txtStatusText
        addView(statusText(context, textId))
        if (def.viewId == R.id.btnExtCenter1) {
            addView(
                WaveformView(context).apply {
                    id = R.id.waveformView
                    visibility = View.GONE
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    ).apply {
                        setMargins(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
                    }
                }
            )
        }
    }

    private fun createRecordingGestureLayer(context: Context): View {
        val row = FrameLayout(context).apply {
            id = R.id.rowRecordingGestures
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        BlockDefRegistry.default.defsFor(KeyboardLayoutPanel.Recording)
            .filter { it.viewId != null }
            .forEach { row.addFrameChild(createButtonFromDef(context, it)) }
        return row
    }

    private fun createClipboardPanel(context: Context): View = LinearLayout(context).apply {
        id = R.id.layoutClipboardPanel
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_keyboard_container)
        setPadding(0, dp(context, 4), 0, 0)
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
        }
        addView(createClipboardHeader(context))
        addView(
            RecyclerView(context).apply {
                id = R.id.clip_list
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                clipToPadding = false
                setPadding(dp(context, 2), dp(context, 4), dp(context, 2), dp(context, 8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0
                ).apply {
                    weight = 1f
                }
            }
        )
    }

    private fun createClipboardHeader(context: Context): View {
        val header = ConstraintLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 48)
            )
            setPadding(dp(context, 2), 0, dp(context, 2), 0)
        }
        header.addView(
            imageButton(context, R.id.clip_btnBack, R.string.cd_clipboard_back, R.drawable.arrow_left_toggle)
                .withConstraints {
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                }
        )
        header.addView(
            TextView(context).apply {
                id = R.id.clip_txtCount
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(UiColors.get(context, UiColorTokens.panelFg))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = ConstraintLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    startToEnd = R.id.clip_btnBack
                    endToStart = R.id.clip_btnDelete
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    marginStart = dp(context, 4)
                    marginEnd = dp(context, 4)
                }
            }
        )
        header.addView(
            imageButton(context, R.id.clip_btnDelete, R.string.cd_clipboard_delete, R.drawable.trash_toggle)
                .withConstraints {
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                }
        )
        return header
    }

    private fun createAsrHistoryPanel(context: Context): View = LinearLayout(context).apply {
        id = R.id.layoutAsrHistoryPanel
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_keyboard_container)
        setPadding(0, dp(context, 4), 0, 0)
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
        }
        addView(createAsrHistoryHeader(context))
        addView(
            FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0
                ).apply {
                    weight = 1f
                }
                addView(
                    RecyclerView(context).apply {
                        id = R.id.asr_hist_list
                        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                        clipToPadding = false
                        setPadding(dp(context, 2), dp(context, 4), dp(context, 2), dp(context, 8))
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                )
                addView(
                    TextView(context).apply {
                        id = R.id.asr_hist_empty
                        gravity = Gravity.CENTER
                        includeFontPadding = false
                        visibility = View.GONE
                        setText(R.string.asr_history_empty)
                        setTextColor(UiColors.get(context, UiColorTokens.panelFg))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                )
            }
        )
    }

    private fun createAsrHistoryHeader(context: Context): View {
        val header = ConstraintLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(context, 48)
            )
            setPadding(dp(context, 2), 0, dp(context, 2), 0)
        }
        header.addView(
            imageButton(context, R.id.asr_hist_btnBack, R.string.cd_asr_history_back, R.drawable.arrow_left_toggle)
                .withConstraints {
                    startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                }
        )
        header.addView(
            View(context).apply {
                id = R.id.asr_hist_headerEndSpacer
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = ConstraintLayout.LayoutParams(dp(context, 40), dp(context, 40)).apply {
                    endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                }
            }
        )
        header.addView(
            TextView(context).apply {
                id = R.id.asr_hist_txtCount
                gravity = Gravity.CENTER
                includeFontPadding = false
                setTextColor(UiColors.get(context, UiColorTokens.panelFg))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTypeface(typeface, Typeface.BOLD)
                layoutParams = ConstraintLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    startToEnd = R.id.asr_hist_btnBack
                    endToStart = R.id.asr_hist_headerEndSpacer
                    topToTop = ConstraintLayout.LayoutParams.PARENT_ID
                    bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
                    marginStart = dp(context, 4)
                    marginEnd = dp(context, 4)
                }
            }
        )
        return header
    }

    private fun createAiEditPanel(context: Context): View {
        val panel = FrameLayout(context).apply {
            id = R.id.layoutAiEditPanel
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
            }
        }

        BlockDefRegistry.default.defsFor(KeyboardLayoutPanel.AiEdit)
            .forEach { panel.addFrameChild(createLayoutBlockButton(context, KeyboardLayoutPanel.AiEdit, it)) }
        return panel
    }

    private fun createDragHandleRow(context: Context): View = FrameLayout(context).apply {
        id = R.id.keyboardDragHandleRow
        visibility = View.GONE
        contentDescription = context.getString(R.string.cd_floating_keyboard_drag)
        isClickable = true
        isFocusable = true
        layoutParams = FrameLayout.LayoutParams(
            dp(context, 176),
            dp(context, 24)
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        addView(
            View(context).apply {
                id = R.id.keyboardDragHandle
                alpha = 0.62f
                layoutParams = FrameLayout.LayoutParams(dp(context, 112), dp(context, 6)).apply {
                    gravity = Gravity.CENTER
                }
            }
        )
    }

    private fun createResizeHandle(context: Context, handleId: Int, handleGravity: Int): View = View(context).apply {
        id = handleId
        visibility = View.GONE
        alpha = 0f
        contentDescription = context.getString(R.string.cd_floating_keyboard_resize)
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.TRANSPARENT)
        layoutParams = FrameLayout.LayoutParams(dp(context, 96), dp(context, 24)).apply {
            gravity = handleGravity
        }
    }

    private fun createDockButton(context: Context, buttonId: Int, contentDescriptionRes: Int, iconRes: Int): ImageButton = ImageButton(context).apply {
        id = buttonId
        visibility = View.GONE
        contentDescription = context.getString(contentDescriptionRes)
        isClickable = true
        isFocusable = true
        background = selectableBorderless(context)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setImageResource(iconRes)
        setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
        layoutParams = FrameLayout.LayoutParams(dp(context, 48), dp(context, 48)).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
    }

    internal fun createLayoutBlockButton(
        context: Context,
        panel: KeyboardLayoutPanel,
        def: BlockDef
    ): View {
        val view = createButtonFromDef(context, def)
        val dynamic = def.viewId == null
        KeyboardLayoutViewTags.markBlockView(view, panel, def, dynamic)
        if (dynamic) {
            view.applyDynamicLayoutKeyTheme(BibiViewThemes.resolve(context, Prefs(context)))
            view.visibility = View.GONE
        }
        return view
    }

    private fun createButtonFromDef(context: Context, def: BlockDef): View {
        val id = def.viewId ?: View.NO_ID
        if (id == R.id.btnAiPanelMic) return micButton(context, id, def.labelRes)
        if (def.viewKind == ButtonViewKind.Status && def.viewId == null) {
            return keyButton(context, id, def.labelRes).apply {
                text = context.getString(def.labelRes)
            }
        }
        return when (def.viewKind) {
            ButtonViewKind.Icon -> imageButton(context, id, def.labelRes, def.iconRes)
            ButtonViewKind.Text -> keyButton(context, id, def.labelRes).apply {
                text = context.getString(def.labelRes)
            }
            ButtonViewKind.Status -> createStatusFrame(context, def)
            ButtonViewKind.Punctuation -> punctButton(context, id, def.labelRes)
            ButtonViewKind.Gesture -> gestureButton(context, id, def.labelRes)
            ButtonViewKind.External -> imageButton(context, id, def.labelRes, def.iconRes)
        }
    }

    private fun FrameLayout.addFrameChild(view: View) {
        view.layoutParams = FrameLayout.LayoutParams(dp(context, 40), dp(context, 40))
        addView(view)
    }

    private fun createNumpadPanel(context: Context): View = LinearLayout(context).apply {
        id = R.id.layoutNumpadPanel
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP
        }
        addView(createNumpadDigitsRow(context))
        addView(createNumpadPunctuationContainer(context))
        addView(createNumpadBottomBar(context))
    }

    private fun createNumpadDigitsRow(context: Context): View = LinearLayout(context).apply {
        id = R.id.rowNumpadDigits
        orientation = LinearLayout.HORIZONTAL
        weightSum = 10f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(context, 4)
        }
        listOf(
            R.id.np_key_1 to "1",
            R.id.np_key_2 to "2",
            R.id.np_key_3 to "3",
            R.id.np_key_4 to "4",
            R.id.np_key_5 to "5",
            R.id.np_key_6 to "6",
            R.id.np_key_7 to "7",
            R.id.np_key_8 to "8",
            R.id.np_key_9 to "9",
            R.id.np_key_0 to "0"
        ).forEachIndexed { index, item ->
            val (id, label) = item
            addView(numpadTextKey(context, id, label, marginEndDp = if (index == 9) 0 else 4))
        }
    }

    private fun createNumpadPunctuationContainer(context: Context): View = LinearLayout(context).apply {
        id = R.id.containerPunct
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        addView(
            LinearLayout(context).apply {
                id = R.id.rowPunctWrap
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                addView(
                    createPunctRow(
                        context,
                        R.id.rowPunct1,
                        listOf("，", "。", "、", "！", "？", "：", "；", "“", "”", "@")
                    )
                )
                addView(createSecondPunctRow(context))
            }
        )
    }

    private fun createPunctRow(context: Context, rowId: Int, labels: List<String>): View = LinearLayout(context).apply {
        id = rowId
        orientation = LinearLayout.HORIZONTAL
        weightSum = 10f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(context, 4)
        }
        labels.forEachIndexed { index, label ->
            addView(numpadTextKey(context, View.NO_ID, label, marginEndDp = if (index == labels.lastIndex) 0 else 4))
        }
    }

    private fun createSecondPunctRow(context: Context): View = LinearLayout(context).apply {
        id = R.id.rowPunct2
        orientation = LinearLayout.HORIZONTAL
        weightSum = 10f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(context, 4)
        }
        listOf("（", ")", "[", "]", "{", "}", "/", "`").forEachIndexed { index, label ->
            addView(numpadTextKey(context, View.NO_ID, label, marginEndDp = if (index == 7) 4 else 4))
        }
        addView(
            numpadImageButton(
                context,
                R.id.np_btnBackspace,
                R.string.cd_backspace,
                R.drawable.backspace_toggle,
                weight = 2f,
                marginEndDp = 0
            )
        )
    }

    private fun createNumpadBottomBar(context: Context): View = LinearLayout(context).apply {
        id = R.id.rowNumpadBottomBar
        orientation = LinearLayout.HORIZONTAL
        weightSum = 8f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(context, 4)
        }
        addView(
            numpadImageButton(
                context,
                R.id.np_btnBack,
                R.string.cd_numpad_back,
                R.drawable.arrow_u_up_left_toggle,
                weight = 2f,
                marginEndDp = 4
            )
        )
        addView(
            numpadImageButton(
                context,
                R.id.np_btnPunctToggle,
                R.string.cd_punct_lang_toggle,
                R.drawable.translate,
                weight = 1f,
                marginEndDp = 4
            )
        )
        addView(
            numpadTextKey(context, R.id.np_key_space, context.getString(R.string.cd_space), weight = 3f, marginEndDp = 4).apply {
                contentDescription = context.getString(R.string.cd_space)
            }
        )
        addView(
            numpadImageButton(
                context,
                R.id.np_btnEnter,
                R.string.cd_enter,
                R.drawable.key_return_toggle,
                weight = 2f,
                marginEndDp = 0
            )
        )
    }

    private fun createMicStatusGroup(context: Context): View = LinearLayout(context).apply {
        id = R.id.groupMicStatus
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        addView(micButton(context, R.id.btnMic, R.string.cd_mic))
        addView(
            TextView(context).apply {
                id = R.id.txtStatus
                text = context.getString(R.string.status_idle)
                gravity = Gravity.CENTER
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(UiColors.get(context, UiColorTokens.panelFgVariant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = Typeface.create(typeface, Typeface.BOLD)
                includeFontPadding = false
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = dp(context, 90)
                    marginEnd = dp(context, 90)
                    topMargin = dp(context, 16)
                }
            }
        )
    }

    private fun imageButton(
        context: Context,
        id: Int,
        contentDescriptionRes: Int,
        imageRes: Int? = null
    ): ImageButton = ImageButton(context).apply {
        this.id = id
        contentDescription = context.getString(contentDescriptionRes)
        layoutParams = ConstraintLayout.LayoutParams(dp(context, 40), dp(context, 40))
        background = ContextCompat.getDrawable(context, R.drawable.bg_key_button)
        foreground = selectableBorderless(context)
        clipToOutline = true
        setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
        imageTintList = ColorStateList.valueOf(UiColors.get(context, UiColorTokens.kbdKeyFg))
        scaleType = ImageView.ScaleType.CENTER
        stateListAnimator = AnimatorInflater.loadStateListAnimator(
            context,
            R.animator.key_button_state_list
        )
        imageRes?.let(::setImageResource)
    }

    private fun numpadImageButton(
        context: Context,
        id: Int,
        contentDescriptionRes: Int,
        imageRes: Int,
        weight: Float,
        marginEndDp: Int
    ): ImageButton = ImageButton(context).apply {
        this.id = id
        tag = "key40"
        contentDescription = context.getString(contentDescriptionRes)
        background = ContextCompat.getDrawable(context, R.drawable.bg_key_rect_button)
        clipToOutline = true
        setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
        imageTintList = ColorStateList.valueOf(UiColors.get(context, UiColorTokens.kbdKeyFg))
        scaleType = ImageView.ScaleType.CENTER
        stateListAnimator = AnimatorInflater.loadStateListAnimator(
            context,
            R.animator.key_button_state_list
        )
        setImageResource(imageRes)
        layoutParams = LinearLayout.LayoutParams(0, dp(context, 40)).apply {
            this.weight = weight
            marginEnd = dp(context, marginEndDp)
        }
    }

    private fun micButton(context: Context, id: Int, contentDescriptionRes: Int): ImageButton = ImageButton(context).apply {
        this.id = id
        contentDescription = context.getString(contentDescriptionRes)
        setImageResource(R.drawable.microphone)
        background = BibiViewThemes.roundedRipple(
            context = context,
            color = UiColors.get(context, UiColorTokens.secondaryContainer),
            rippleColor = UiColors.get(context, UiColorTokens.ripple),
            radiusDp = 8f,
            insetDp = 0
        )
        imageTintList = ColorStateList.valueOf(
            UiColors.get(context, UiColorTokens.onSecondaryContainer)
        )
        scaleType = ImageView.ScaleType.FIT_CENTER
        adjustViewBounds = false
        setPadding(dp(context, 16), dp(context, 16), dp(context, 16), dp(context, 16))
        minimumWidth = 0
        minimumHeight = 0
        stateListAnimator = null
        layoutParams = LinearLayout.LayoutParams(
            dp(context, 72),
            dp(context, 72)
        )
    }

    private fun numpadTextKey(
        context: Context,
        id: Int,
        label: String,
        weight: Float = 1f,
        marginEndDp: Int
    ): TextView = TextView(context).apply {
        if (id != View.NO_ID) this.id = id
        tag = "key40"
        text = label
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        background = ContextCompat.getDrawable(context, R.drawable.bg_key_rect_button)
        clipToOutline = true
        setTextColor(UiColors.get(context, UiColorTokens.kbdKeyFg))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        includeFontPadding = false
        setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 4))
        layoutParams = LinearLayout.LayoutParams(0, dp(context, 40)).apply {
            this.weight = weight
            marginEnd = dp(context, marginEndDp)
        }
    }

    private fun punctButton(context: Context, id: Int, contentDescriptionRes: Int): PunctKeyView = PunctKeyView(context).apply {
        this.id = id
        contentDescription = context.getString(contentDescriptionRes)
        layoutParams = ConstraintLayout.LayoutParams(dp(context, 40), dp(context, 40))
        background = ContextCompat.getDrawable(context, R.drawable.bg_key_button)
        foreground = selectableBorderless(context)
        clipToOutline = true
        isClickable = true
        isFocusable = true
        stateListAnimator = AnimatorInflater.loadStateListAnimator(
            context,
            R.animator.key_button_state_list
        )
    }

    private fun keyFrame(context: Context, id: Int): FrameLayout = FrameLayout(context).apply {
        this.id = id
        background = ContextCompat.getDrawable(context, R.drawable.bg_key_rect_button)
        clipToOutline = true
        stateListAnimator = AnimatorInflater.loadStateListAnimator(
            context,
            R.animator.key_button_state_list
        )
    }

    private fun keyButton(context: Context, id: Int, contentDescriptionRes: Int): Button = Button(context).apply {
        this.id = id
        contentDescription = context.getString(contentDescriptionRes)
        background = ContextCompat.getDrawable(context, R.drawable.bg_key_rect_button)
        clipToOutline = true
        stateListAnimator = AnimatorInflater.loadStateListAnimator(
            context,
            R.animator.key_button_state_list
        )
        setTextColor(UiColors.get(context, UiColorTokens.kbdKeyFg))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        includeFontPadding = false
        isAllCaps = false
        minHeight = 0
        minWidth = 0
        minimumHeight = 0
        minimumWidth = 0
    }

    private fun gestureButton(context: Context, id: Int, textRes: Int): TextView = TextView(context).apply {
        this.id = id
        text = context.getString(textRes)
        background = ContextCompat.getDrawable(context, R.drawable.bg_key_rect_button)
        foreground = selectableBorderless(context)
        clipToOutline = true
        isClickable = true
        isFocusable = true
        gravity = Gravity.CENTER
        setTextColor(UiColors.get(context, UiColorTokens.kbdKeyFg))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        layoutParams = ConstraintLayout.LayoutParams(dp(context, 86), dp(context, 86))
    }

    private fun statusText(context: Context, id: Int): TextView = TextView(context).apply {
        this.id = id
        gravity = Gravity.CENTER
        setTextColor(UiColors.get(context, UiColorTokens.panelFg))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MARQUEE
        marqueeRepeatLimit = -1
        isFocusable = true
        isFocusableInTouchMode = true
        includeFontPadding = false
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
    }

    private fun View.withConstraints(
        block: ConstraintLayout.LayoutParams.() -> Unit
    ): View = apply {
        val existing = layoutParams as? ConstraintLayout.LayoutParams
        layoutParams = (existing ?: ConstraintLayout.LayoutParams(dp(context, 40), dp(context, 40)))
            .apply(block)
    }

    private fun selectableBorderless(context: Context): android.graphics.drawable.Drawable? {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        return ContextCompat.getDrawable(context, outValue.resourceId)
    }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun View.applyRectKeyTheme(theme: com.brycewg.asrkb.ui.BibiViewTheme) {
        background = BibiViewThemes.roundedRipple(
            context,
            theme.keyBackground,
            theme.ripple,
            theme.rectKeyRadiusDp,
            insetDp = theme.keyInsetDp
        )
        when (this) {
            is TextView -> setTextColor(theme.keyContent)
            is ImageButton -> imageTintList = ColorStateList.valueOf(theme.keyContent)
        }
    }

    private fun ImageButton.applyMicButtonTheme(theme: com.brycewg.asrkb.ui.BibiViewTheme) {
        background = BibiViewThemes.roundedRipple(
            context,
            theme.micContainer,
            theme.ripple,
            theme.iconKeyRadiusDp,
            insetDp = 0
        )
        imageTintList = ColorStateList.valueOf(theme.micContent)
    }

    private fun applyTaggedKeys(view: View, theme: com.brycewg.asrkb.ui.BibiViewTheme) {
        if (view.tag == "key40") {
            view.applyRectKeyTheme(theme)
        }
        if (KeyboardLayoutViewTags.isDynamicBlockView(view)) {
            view.applyDynamicLayoutKeyTheme(theme)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applyTaggedKeys(view.getChildAt(i), theme)
            }
        }
    }

    private fun View.applyDynamicLayoutKeyTheme(theme: com.brycewg.asrkb.ui.BibiViewTheme) {
        when (this) {
            is ImageButton -> {
                background = BibiViewThemes.roundedRipple(
                    context,
                    theme.keyBackground,
                    theme.ripple,
                    theme.iconKeyRadiusDp,
                    insetDp = theme.keyInsetDp
                )
                imageTintList = ColorStateList.valueOf(theme.keyContent)
            }
            is Button -> applyRectKeyTheme(theme)
            is TextView -> applyRectKeyTheme(theme)
        }
    }

    private val iconKeyIds: IntArray = (
        keyboardButtonIds(ButtonViewKind.Icon) +
            keyboardButtonIds(ButtonViewKind.External) +
            listOf(
                R.id.clip_btnBack,
                R.id.clip_btnDelete,
                R.id.asr_hist_btnBack,
                R.id.keyboardDockButtonLeft,
                R.id.keyboardDockButtonRight
            )
        ).distinct().toIntArray()

    private val rectKeyIds: IntArray = (
        keyboardButtonIds(ButtonViewKind.Text) +
            keyboardButtonIds(ButtonViewKind.Status) +
            keyboardButtonIds(ButtonViewKind.Gesture)
        ).distinct().toIntArray()

    private val statusTextIds = intArrayOf(
        R.id.txtStatusText,
        R.id.txtAiEditInfo,
        R.id.txtStatus,
        R.id.clip_txtCount,
        R.id.asr_hist_txtCount,
        R.id.asr_hist_empty
    )

    private fun keyboardButtonIds(kind: ButtonViewKind): List<Int> = KeyboardLayoutPanel.values().flatMap { panel ->
        BlockDefRegistry.default.defsFor(panel)
            .filter { it.viewKind == kind }
            .mapNotNull { it.viewId }
            .filter { it != R.id.groupMicStatus && it != R.id.btnAiPanelMic }
    }

    private const val FLOATING_PANEL_RADIUS_DP = 18
}
