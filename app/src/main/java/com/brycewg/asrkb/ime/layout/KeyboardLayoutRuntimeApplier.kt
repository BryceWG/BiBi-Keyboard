/**
 * 将自定义键盘布局应用到 IME View 树。
 *
 * 归属模块：ime/layout
 */
package com.brycewg.asrkb.ime.layout

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ime.ImeKeyboardViewFactory

object KeyboardLayoutRuntimeApplier {
    private const val KEY_MARGIN_DP = 6f

    fun applyAll(root: View, bundle: KeyboardLayoutBundle, scale: Float): Boolean {
        val mainPanelVisible = root.findViewById<View>(R.id.layoutMainKeyboard)?.visibility == View.VISIBLE
        val changedMain = applyPanel(
            root = root,
            container = root.findViewById(R.id.keyboardLayoutCanvas),
            layout = bundle.main,
            scale = scale,
            extraViews = mapOf("mic" to root.findViewById(R.id.groupMicStatus)),
            isPlacedViewVisible = { view -> view.id != R.id.groupMicStatus || mainPanelVisible }
        )
        val changedAi = applyPanel(
            root = root,
            container = root.findViewById(R.id.layoutAiEditPanel),
            layout = bundle.aiEdit,
            scale = scale
        )
        val changedRecording = applyPanel(
            root = root,
            container = root.findViewById(R.id.rowRecordingGestures),
            layout = bundle.recording,
            scale = scale
        )
        if (mainPanelVisible) {
            root.findViewById<View>(R.id.groupMicStatus)?.bringToFront()
        }
        return changedMain || changedAi || changedRecording
    }

    private fun applyPanel(
        root: View,
        container: View?,
        layout: KeyboardLayout,
        scale: Float,
        extraViews: Map<String, View?> = emptyMap(),
        isPlacedViewVisible: (View) -> Boolean = { true }
    ): Boolean {
        val frame = container as? FrameLayout ?: return false
        val registry = BlockDefRegistry.default
        val width = resolveFrameWidth(frame, root) ?: return false
        val margin = dp(root, KEY_MARGIN_DP * scale)
        val cellWidth = width.toFloat() / layout.gridSize.cols.toFloat()
        val cellHeight = cellWidth * scale
        val targetHeight = (cellHeight * layout.gridSize.rows).toInt()
        var changed = setSize(frame, ViewGroup.LayoutParams.MATCH_PARENT, targetHeight)
        changed = resetFrameTopMargin(frame) || changed

        val placedViewIds = mutableSetOf<Int>()
        val placedDynamicViews = mutableSetOf<View>()
        layout.blocks.forEachIndexed { index, block ->
            val def = registry.get(layout.panel, block.defId) ?: registry.get(block.defId) ?: return@forEachIndexed
            val view = extraViews[block.defId]
                ?: def.viewId?.let(root::findViewById)
                ?: findDynamicBlockView(frame, layout.panel, block.defId, index)
                ?: createDynamicBlockView(frame, layout.panel, def)
                ?: return@forEachIndexed
            KeyboardLayoutViewTags.markInstance(view, index, block)
            if (KeyboardLayoutViewTags.isDynamicBlockView(view)) placedDynamicViews.add(view)
            if (block.defId == RecordingLayoutButtons.PROMPT_POLISH && view is TextView) {
                val title = com.brycewg.asrkb.store.Prefs(view.context).getPromptPresets()
                    .firstOrNull { it.id == block.config[RecordingLayoutButtons.PROMPT_ID] }
                    ?.title ?: view.context.getString(R.string.keyboard_layout_prompt_required)
                view.text = title
                view.contentDescription = title
            }
            val placement = block.placement.snapped()
            val left = (placement.col * cellWidth).toInt() + margin / 2
            val top = (placement.row * cellHeight).toInt() + margin / 2
            val blockWidth = (placement.width * cellWidth).toInt() - margin
            val blockHeight = (placement.height * cellHeight).toInt() - margin
            val micBounds = if (view.id == R.id.btnAiPanelMic) {
                scaledMicBounds(left, top, blockWidth, blockHeight)
            } else {
                PlacedBounds(left, top, blockWidth, blockHeight)
            }
            changed = placeView(
                view = view,
                preferredParent = frame,
                left = micBounds.left,
                top = micBounds.top,
                width = micBounds.width,
                height = micBounds.height,
                visible = isPlacedViewVisible(view)
            ) ||
                changed
            def.viewId?.let(placedViewIds::add)
        }

        registry.defsFor(layout.panel).forEach { def ->
            val id = def.viewId ?: return@forEach
            if (id !in placedViewIds && extraViews.values.none { it?.id == id }) {
                root.findViewById<View>(id)?.visibility = View.GONE
            }
        }
        hideUnplacedDynamicBlockViews(frame, layout.panel, placedDynamicViews)
        return changed
    }

    internal data class PlacedBounds(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int
    )

    internal fun scaledMicBounds(left: Int, top: Int, width: Int, height: Int): PlacedBounds {
        val visualWidth = scaledMicDimension(width)
        val visualHeight = scaledMicDimension(height)
        return PlacedBounds(
            left = left + (width - visualWidth) / 2,
            top = top + (height - visualHeight) / 2,
            width = visualWidth,
            height = visualHeight
        )
    }

    private fun scaledMicDimension(value: Int): Int = (value.coerceAtLeast(1) * MIC_SIZE_RATIO).toInt().coerceAtLeast(1)

    private fun resolveFrameWidth(frame: FrameLayout, root: View): Int? {
        ancestorExplicitContentWidth(frame, R.id.keyboardContentPanel)?.let { return it }
        ancestorContentWidth(frame, R.id.keyboardFloatingPanel)?.let { return it }
        frame.width.takeIf { it > 0 }?.let { return it }
        val parentView = frame.parent as? View
        val parentContentWidth = parentView?.let { parent ->
            parent.width - parent.paddingStart - parent.paddingEnd
        } ?: 0
        parentContentWidth.takeIf { it > 0 }?.let { return it }
        val rootContentWidth = root.width - root.paddingStart - root.paddingEnd
        return rootContentWidth.takeIf { it > 0 }
    }

    private fun ancestorExplicitContentWidth(view: View, ancestorId: Int): Int? {
        var parent = view.parent as? View
        while (parent != null) {
            if (parent.id == ancestorId) {
                val lpWidth = parent.layoutParams?.width ?: 0
                return lpWidth.takeIf { it > 0 }?.let {
                    (it - parent.paddingStart - parent.paddingEnd).coerceAtLeast(1)
                }
            }
            parent = parent.parent as? View
        }
        return null
    }

    private fun ancestorContentWidth(view: View, ancestorId: Int): Int? {
        var parent = view.parent as? View
        while (parent != null) {
            if (parent.id == ancestorId) {
                val lpWidth = parent.layoutParams?.width ?: 0
                if (lpWidth > 0) {
                    return (lpWidth - parent.paddingStart - parent.paddingEnd).coerceAtLeast(1)
                }
                val measured = parent.width - parent.paddingStart - parent.paddingEnd
                if (measured > 0) return measured
                return null
            }
            parent = parent.parent as? View
        }
        return null
    }

    private fun placeView(
        view: View,
        preferredParent: FrameLayout,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        visible: Boolean
    ): Boolean {
        var changed = false
        if (view.parent == null && view.id != R.id.groupMicStatus) {
            preferredParent.addView(view)
            changed = true
        }
        val targetVisibility = if (visible) View.VISIBLE else View.GONE
        if (view.visibility != targetVisibility) {
            view.visibility = targetVisibility
            changed = true
        }
        val finalWidth = width.coerceAtLeast(1)
        val finalHeight = height.coerceAtLeast(1)
        val lp = (view.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(finalWidth, finalHeight)
        if (lp.width != finalWidth || lp.height != finalHeight || lp.leftMargin != left || lp.topMargin != top) {
            lp.width = finalWidth
            lp.height = finalHeight
            lp.leftMargin = left
            lp.topMargin = top
            lp.gravity = Gravity.START or Gravity.TOP
            view.layoutParams = lp
            changed = true
        }
        changed = applyPlacedMicSizing(view, finalWidth, finalHeight) || changed
        return changed
    }

    private fun applyPlacedMicSizing(view: View, width: Int, height: Int): Boolean = when (view.id) {
        R.id.btnAiPanelMic -> applyMicButtonSizing(view as? ImageButton, width, height)
        R.id.groupMicStatus -> {
            val centered = applyMainMicContainerGravity(view)
            val micBounds = scaledMicBounds(0, 0, width, height)
            applyMicButtonSizing(
                button = view.findViewById(R.id.btnMic),
                width = micBounds.width,
                height = micBounds.height
            ) ||
                centered
        }
        else -> false
    }

    private fun applyMainMicContainerGravity(view: View): Boolean {
        val group = view as? LinearLayout ?: return false
        if (group.gravity == Gravity.CENTER) return false
        group.gravity = Gravity.CENTER
        return true
    }

    private fun applyMicButtonSizing(button: ImageButton?, width: Int, height: Int): Boolean {
        if (button == null) return false
        val finalWidth = width.coerceAtLeast(1)
        val finalHeight = height.coerceAtLeast(1)
        var changed = false
        val lp = button.layoutParams ?: LinearLayout.LayoutParams(finalWidth, finalHeight)
        if (lp.width != finalWidth || lp.height != finalHeight) {
            lp.width = finalWidth
            lp.height = finalHeight
            button.layoutParams = lp
            changed = true
        }
        val iconSize = (minOf(finalWidth, finalHeight) * MIC_ICON_SIZE_RATIO).toInt().coerceAtLeast(1)
        val horizontalPadding = ((finalWidth - iconSize) / 2).coerceAtLeast(0)
        val verticalPadding = ((finalHeight - iconSize) / 2).coerceAtLeast(0)
        if (button.paddingLeft != horizontalPadding ||
            button.paddingRight != horizontalPadding ||
            button.paddingTop != verticalPadding ||
            button.paddingBottom != verticalPadding
        ) {
            button.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            changed = true
        }
        if (button.scaleType != ImageView.ScaleType.FIT_CENTER) {
            button.scaleType = ImageView.ScaleType.FIT_CENTER
            changed = true
        }
        return changed
    }

    private fun setSize(view: View, width: Int, height: Int): Boolean {
        val lp = view.layoutParams ?: return false
        if (lp.width == width && lp.height == height) return false
        lp.width = width
        lp.height = height
        view.layoutParams = lp
        return true
    }

    private fun resetFrameTopMargin(view: View): Boolean {
        val lp = view.layoutParams as? FrameLayout.LayoutParams ?: return false
        if (lp.topMargin == 0 && lp.leftMargin == 0) return false
        lp.topMargin = 0
        lp.leftMargin = 0
        view.layoutParams = lp
        return true
    }

    private fun dp(view: View, value: Float): Int = (value * view.resources.displayMetrics.density + 0.5f).toInt()

    private fun findDynamicBlockView(frame: FrameLayout, panel: KeyboardLayoutPanel, defId: String, index: Int): View? {
        for (i in 0 until frame.childCount) {
            val child = frame.getChildAt(i)
            if (KeyboardLayoutViewTags.panelOf(child) == panel &&
                KeyboardLayoutViewTags.defIdOf(child) == defId &&
                (KeyboardLayoutViewTags.indexOf(child) == index || KeyboardLayoutViewTags.indexOf(child) == null) &&
                KeyboardLayoutViewTags.isDynamicBlockView(child)
            ) {
                return child
            }
        }
        return null
    }

    private fun createDynamicBlockView(frame: FrameLayout, panel: KeyboardLayoutPanel, def: BlockDef): View? {
        if (def.viewId != null) return null
        return ImeKeyboardViewFactory.createLayoutBlockButton(frame.context, panel, def).also { frame.addView(it) }
    }

    private fun hideUnplacedDynamicBlockViews(frame: FrameLayout, panel: KeyboardLayoutPanel, placedViews: Set<View>) {
        for (i in 0 until frame.childCount) {
            val child = frame.getChildAt(i)
            if (KeyboardLayoutViewTags.panelOf(child) == panel &&
                KeyboardLayoutViewTags.isDynamicBlockView(child) &&
                child !in placedViews
            ) {
                child.visibility = View.GONE
            }
        }
    }

    private const val MIC_SIZE_RATIO = 1f
    private const val MIC_ICON_SIZE_RATIO = 0.42f
}
