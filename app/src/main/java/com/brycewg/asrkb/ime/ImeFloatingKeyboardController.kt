/**
 * IME 悬浮键盘窗口、拖动、缩放与 inset 协调。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.util.WindowSizePx
import com.brycewg.asrkb.util.legacyUsableWindowSize
import com.brycewg.asrkb.util.maximumUsableWindowSize

internal class ImeFloatingKeyboardController(
    private val prefs: Prefs,
    private val windowProvider: () -> Window?,
    private val onResizeFrameChanged: () -> Unit = {}
) {
    var isActive: Boolean = false
        private set
    var isDragging: Boolean = false
        private set
    var isResizing: Boolean = false
        private set

    private var rootView: View? = null
    private var inputViewVisible: Boolean = false
    private var dragStartRawX: Float = 0f
    private var dragStartRawY: Float = 0f
    private var dragStartWindowX: Int = 0
    private var dragStartWindowY: Int = 0
    private var dragMoved: Boolean = false
    private var touchSlop: Int = 0
    private var frameApplyPosted: Boolean = false
    private var dockedWindowFlags: Int? = null
    private var dockedWindowAnimations: Int? = null
    private var dragMovePosted: Boolean = false
    private var hasPendingDragMove: Boolean = false
    private var pendingDragWindowX: Int = 0
    private var pendingDragWindowY: Int = 0
    private var resizeCorner: ResizeCorner? = null
    private var resizeStartRawX: Float = 0f
    private var resizeStartRawY: Float = 0f
    private var resizeStartWindowX: Int = 0
    private var resizeStartWindowY: Int = 0
    private var resizeStartWidth: Int = 0
    private var resizeStartHeight: Int = 0
    private var resizeLongPressTriggered: Boolean = false
    private var resizeLongPressRunnable: Runnable? = null
    private var resizeLongPressView: View? = null
    private var resizeFramePosted: Boolean = false
    private var hasPendingResizeFrame: Boolean = false
    private var pendingResizeWindowX: Int = 0
    private var pendingResizeWindowY: Int = 0
    private var pendingResizeWidth: Int = 0
    private var pendingResizeHeight: Int = 0
    private var activeResizeWindowX: Int? = null
    private var activeResizeWindowY: Int? = null
    private var activeResizeWidth: Int? = null
    private var activeResizeHeight: Int? = null

    fun install(rootView: View) {
        this.rootView = rootView
        touchSlop = ViewConfiguration.get(rootView.context).scaledTouchSlop
        installDrag(rootView)
        installResizeHandles(rootView)
    }

    fun onInputViewStarted() {
        inputViewVisible = true
        rootView?.alpha = 0f
        rootView?.let { keyboardPanel(it).alpha = 0f }
    }

    fun onInputViewFinished() {
        inputViewVisible = false
        isDragging = false
        isResizing = false
        dragMoved = false
        frameApplyPosted = false
        dragMovePosted = false
        hasPendingDragMove = false
        cancelResizeLongPress()
        resizeFramePosted = false
        hasPendingResizeFrame = false
        clearActiveResizeFrame()
        rootView?.takeIf { isActive }?.let { root ->
            root.alpha = 0f
            keyboardPanel(root).alpha = 0f
        }
    }

    fun applyFrame(root: View): Boolean {
        if (!inputViewVisible) return false

        val panel = keyboardPanel(root)
        val handle = root.findViewById<View>(R.id.keyboardDragHandle)
        val availableWidth = screenSize(root).width
        val shouldFloat = shouldUseFloatingKeyboard(root, availableWidth) && panel !== root
        isActive = shouldFloat
        ImeKeyboardViewFactory.applyKeyboardPanelBackground(root, prefs, shouldFloat)

        return if (shouldFloat) {
            applyFloatingFrame(root, panel, handle, availableWidth)
        } else {
            applyDockedFrame(root, panel, handle)
        }
    }

    fun fixInsets(
        input: View,
        decor: View,
        outInsets: InputMethodService.Insets
    ) {
        val decorW = decor.width
        val decorH = decor.height
        if (decorW <= 0 || decorH <= 0) return

        val panel = keyboardPanel(input)
        if (panel.alpha == 0f) {
            outInsets.contentTopInsets = decorH
            outInsets.visibleTopInsets = decorH
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(0, 0, 0, 0)
            return
        }
        val inputLoc = IntArray(2)
        val decorLoc = IntArray(2)
        try {
            panel.getLocationInWindow(inputLoc)
            decor.getLocationInWindow(decorLoc)
        } catch (t: Throwable) {
            android.util.Log.w("AsrKeyboardService", "fixFloatingKeyboardInsets location failed", t)
            outInsets.contentTopInsets = decorH
            outInsets.visibleTopInsets = decorH
            outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.set(0, 0, 0, 0)
            return
        }

        val left = (inputLoc[0] - decorLoc[0]).coerceIn(0, decorW)
        val top = (inputLoc[1] - decorLoc[1]).coerceIn(0, decorH)
        val right = (left + panel.width.coerceAtLeast(1)).coerceIn(left, decorW)
        val bottom = (top + panel.height.coerceAtLeast(1)).coerceIn(top, decorH)

        // 浮动键盘不占用宿主应用布局空间；只有面板本体接收触摸。
        outInsets.contentTopInsets = decorH
        outInsets.visibleTopInsets = decorH
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(left, top, right, bottom)
    }

    private fun installDrag(root: View) {
        val touchTarget = root.findViewById<View>(R.id.keyboardDragHandleRow)
            ?: root.findViewById(R.id.keyboardDragHandle)
            ?: return
        touchTarget.setOnTouchListener { v, event ->
            if (!isActive) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    windowProvider()?.attributes?.let { attrs ->
                        dragStartWindowX = attrs.x
                        dragStartWindowY = attrs.y
                    }
                    isDragging = true
                    dragMoved = false
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - dragStartRawX
                    val dy = event.rawY - dragStartRawY
                    if (!dragMoved && kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        dragMoved = true
                    }
                    scheduleWindowMove(
                        root = root,
                        x = dragStartWindowX + dx.toInt(),
                        y = dragStartWindowY + dy.toInt()
                    )
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (!dragMoved) {
                        v.performClick()
                    }
                    applyPendingWindowMove(root)
                    saveWindowPosition(root)
                    isDragging = false
                    ViewCompat.requestApplyInsets(root)
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    hasPendingDragMove = false
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    true
                }

                else -> true
            }
        }
    }

    private fun installResizeHandles(root: View) {
        root.findViewById<View>(R.id.keyboardResizeHandleBottomLeft)?.installResizeHandle(root, ResizeCorner.BOTTOM_LEFT)
        root.findViewById<View>(R.id.keyboardResizeHandleBottomRight)?.installResizeHandle(root, ResizeCorner.BOTTOM_RIGHT)
        root.findViewById<View>(R.id.keyboardResizeHandleTopLeft)?.installResizeHandle(root, ResizeCorner.TOP_LEFT)
        root.findViewById<View>(R.id.keyboardResizeHandleTopRight)?.installResizeHandle(root, ResizeCorner.TOP_RIGHT)
    }

    private fun View.installResizeHandle(root: View, corner: ResizeCorner) {
        setOnTouchListener { v, event ->
            if (!isActive) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    resizeStartRawX = event.rawX
                    resizeStartRawY = event.rawY
                    resizeCorner = corner
                    resizeLongPressTriggered = false
                    cancelResizeLongPress()
                    resizeLongPressView = v
                    resizeLongPressRunnable = Runnable {
                        beginResize(root, v, corner)
                    }.also { runnable ->
                        v.postDelayed(runnable, ViewConfiguration.getLongPressTimeout().toLong())
                    }
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!resizeLongPressTriggered) {
                        val dx = event.rawX - resizeStartRawX
                        val dy = event.rawY - resizeStartRawY
                        if (kotlin.math.hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                            cancelResizeLongPress()
                            beginResize(root, v, corner)
                            resizeFloatingKeyboard(root, event.rawX, event.rawY)
                        }
                        return@setOnTouchListener true
                    }
                    resizeFloatingKeyboard(root, event.rawX, event.rawY)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    cancelResizeLongPress()
                    if (isResizing) {
                        applyPendingResizeFrame(root)
                        saveActiveResizeFrame(root)
                        onResizeFrameChanged()
                    } else {
                        v.performClick()
                    }
                    finishResize(v)
                    true
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelResizeLongPress()
                    if (isResizing) {
                        applyPendingResizeFrame(root)
                        saveActiveResizeFrame(root)
                    } else {
                        hasPendingResizeFrame = false
                    }
                    finishResize(v)
                    true
                }

                else -> true
            }
        }
    }

    private fun beginResize(root: View, handle: View, corner: ResizeCorner) {
        if (!isActive || resizeCorner != corner) return
        val window = windowProvider() ?: return
        val attrs = window.attributes
        val panel = keyboardPanel(root)
        val currentWidth = attrs.width.takeIf { it > 0 }
            ?: panel.width.takeIf { it > 0 }
            ?: root.width.takeIf { it > 0 }
            ?: return
        val currentHeight = window.decorView.height.takeIf { it > 0 }
            ?: panel.height.takeIf { it > 0 }
            ?: root.height.takeIf { it > 0 }
            ?: 1
        resizeStartWindowX = attrs.x
        resizeStartWindowY = attrs.y
        resizeStartWidth = currentWidth
        resizeStartHeight = currentHeight
        resizeLongPressTriggered = true
        isResizing = true
        handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun resizeFloatingKeyboard(root: View, rawX: Float, rawY: Float) {
        if (!isResizing) return
        val corner = resizeCorner ?: return
        val dx = rawX - resizeStartRawX
        val dy = rawY - resizeStartRawY
        val minWidth = floatingMinResizableWidth(root, screenWidth(root))
        val maxWidth = floatingMaxResizableWidth(root, screenWidth(root))
        val horizontalMaxWidth = when (corner.horizontalEdge) {
            ResizeEdge.LEFT -> resizeStartWindowX + resizeStartWidth
            ResizeEdge.RIGHT -> screenWidth(root) - resizeStartWindowX
            else -> maxWidth
        }.coerceAtLeast(minWidth)
        val verticalMaxHeight = when (corner.verticalEdge) {
            ResizeEdge.TOP -> resizeStartWindowY + resizeStartHeight
            ResizeEdge.BOTTOM -> screenHeight(root) - resizeStartWindowY
            else -> resizeStartHeight
        }.coerceAtLeast(1)
        val verticalMaxWidth = widthForEstimatedResizeHeight(verticalMaxHeight).coerceAtLeast(minWidth)
        val nextWidth = (resizeStartWidth + dominantResizeDeltaWidth(corner, dx, dy).toInt())
            .coerceIn(minWidth, minOf(maxWidth, horizontalMaxWidth, verticalMaxWidth))
        val nextHeight = estimatedResizeHeight(nextWidth)
        val nextX = when (corner.horizontalEdge) {
            ResizeEdge.LEFT -> resizeStartWindowX + resizeStartWidth - nextWidth
            ResizeEdge.RIGHT -> resizeStartWindowX
            else -> resizeStartWindowX
        }
        val nextY = when (corner.verticalEdge) {
            ResizeEdge.TOP -> resizeStartWindowY + resizeStartHeight - nextHeight
            ResizeEdge.BOTTOM -> resizeStartWindowY
            else -> resizeStartWindowY
        }
        scheduleResizeFrame(root, nextX, nextY, nextWidth, nextHeight)
    }

    private fun dominantResizeDeltaWidth(corner: ResizeCorner, dx: Float, dy: Float): Float {
        val horizontalDelta = when (corner.horizontalEdge) {
            ResizeEdge.LEFT -> -dx
            ResizeEdge.RIGHT -> dx
            else -> 0f
        }
        val aspect = resizeStartHeight.toFloat()
            .div(resizeStartWidth.toFloat().coerceAtLeast(1f))
            .coerceAtLeast(0.1f)
        val verticalDelta = when (corner.verticalEdge) {
            ResizeEdge.TOP -> -dy / aspect
            ResizeEdge.BOTTOM -> dy / aspect
            else -> 0f
        }
        return if (kotlin.math.abs(horizontalDelta) >= kotlin.math.abs(verticalDelta)) {
            horizontalDelta
        } else {
            verticalDelta
        }
    }

    private fun estimatedResizeHeight(width: Int): Int {
        if (resizeStartWidth <= 0 || resizeStartHeight <= 0) return resizeStartHeight.coerceAtLeast(1)
        return (resizeStartHeight.toFloat() * width.toFloat() / resizeStartWidth.toFloat())
            .toInt()
            .coerceAtLeast(1)
    }

    private fun widthForEstimatedResizeHeight(height: Int): Int {
        if (resizeStartWidth <= 0 || resizeStartHeight <= 0) return resizeStartWidth.coerceAtLeast(1)
        return (resizeStartWidth.toFloat() * height.toFloat() / resizeStartHeight.toFloat())
            .toInt()
            .coerceAtLeast(1)
    }

    private fun finishResize(handle: View) {
        isResizing = false
        resizeLongPressTriggered = false
        resizeCorner = null
        clearActiveResizeFrame()
        handle.parent?.requestDisallowInterceptTouchEvent(false)
    }

    private fun cancelResizeLongPress() {
        val runnable = resizeLongPressRunnable ?: return
        resizeLongPressView?.removeCallbacks(runnable)
        resizeLongPressRunnable = null
        resizeLongPressView = null
    }

    private fun applyFloatingFrame(
        root: View,
        panel: View,
        handle: View?,
        availableWidth: Int
    ): Boolean {
        var changed = false
        val dragHandleChanged = setDragHandleVisible(root, handle, visible = true)
        val resizeHandlesChanged = setResizeHandlesVisible(root, visible = true)
        changed = dragHandleChanged || resizeHandlesChanged || changed
        handle?.bringToFront()
        resizeHandleIds.forEach { id ->
            root.findViewById<View>(id)?.bringToFront()
        }

        val targetWidth = floatingTargetWidth(root, availableWidth)

        val rootSizeChanged = setRootSize(
            root = root,
            width = ViewGroup.LayoutParams.MATCH_PARENT,
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        )
        val panelFrameChanged = updatePanelFrame(
            panel = panel,
            width = targetWidth,
            gravity = Gravity.TOP or Gravity.START
        )
        changed = rootSizeChanged || panelFrameChanged || changed

        val panelHeight = panel.dimensionOrLayoutParam(isWidth = false)
        if (panelHeight == null || rootSizeChanged || panelFrameChanged) {
            changed = hideUntilPositioned(root, panel) || changed
            if (changed) {
                ViewCompat.requestApplyInsets(root)
            }
            postApplyFrame(root)
            return changed
        }

        val hostWindowChanged = if (isDragging) {
            false
        } else {
            applyFloatingHostWindowFrame(root, targetWidth, panelHeight)
        }
        changed = hostWindowChanged || changed
        changed = updateDragHandleForCurrentWindow(root, panelHeight) || changed
        changed = movePanelToOrigin(panel) || changed
        changed = showPositioned(root, panel) || changed
        if (changed) {
            ViewCompat.requestApplyInsets(root)
        }
        return changed
    }

    private fun applyDockedFrame(root: View, panel: View, handle: View?): Boolean {
        var changed = false
        changed = showPositioned(root, panel) || changed
        changed = setDragHandleVisible(root, handle, visible = false) || changed
        changed = setResizeHandlesVisible(root, visible = false) || changed
        changed = restoreDockedImeWindow(root) || changed
        changed = setRootSize(
            root = root,
            width = ViewGroup.LayoutParams.MATCH_PARENT,
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        ) ||
            changed
        changed = updatePanelFrame(
            panel = panel,
            width = ViewGroup.LayoutParams.MATCH_PARENT,
            gravity = Gravity.TOP
        ) ||
            changed
        changed = movePanelToOrigin(panel) || changed
        if (changed) {
            ViewCompat.requestApplyInsets(root)
        }
        return changed
    }

    private fun setResizeHandlesVisible(root: View, visible: Boolean): Boolean {
        var changed = false
        val target = if (visible) View.VISIBLE else View.GONE
        resizeHandleIds.forEach { id ->
            root.findViewById<View>(id)?.let { handle ->
                if (handle.visibility != target) {
                    handle.visibility = target
                    changed = true
                }
                changed = updateResizeHandlePlacement(root, handle, visible) || changed
            }
        }
        return changed
    }

    private fun updateResizeHandlePlacement(root: View, handle: View, visible: Boolean): Boolean {
        val lp = handle.layoutParams as? FrameLayout.LayoutParams ?: return false
        val handleHeight = lp.height.takeIf { it > 0 }
            ?: handle.height.takeIf { it > 0 }
            ?: dp(root, RESIZE_HANDLE_HEIGHT_DP)
        val topHandle = (lp.gravity and Gravity.TOP) == Gravity.TOP
        val panel = keyboardPanel(root)
        val paddingBand = if (topHandle) panel.paddingTop else panel.paddingBottom
        val targetMargin = if (visible && paddingBand > 0) {
            -((paddingBand + handleHeight) / 2)
        } else {
            0
        }
        if (topHandle) {
            if (lp.topMargin == targetMargin && lp.bottomMargin == 0) return false
            lp.topMargin = targetMargin
            lp.bottomMargin = 0
        } else {
            if (lp.bottomMargin == targetMargin && lp.topMargin == 0) return false
            lp.topMargin = 0
            lp.bottomMargin = targetMargin
        }
        handle.layoutParams = lp
        return true
    }

    private fun setDragHandleVisible(root: View, handle: View?, visible: Boolean): Boolean {
        var changed = false
        val target = if (visible) View.VISIBLE else View.GONE
        val row = root.findViewById<View>(R.id.keyboardDragHandleRow)
        row?.let {
            if (it.visibility != target) {
                it.visibility = target
                changed = true
            }
            changed = updateDragHandleRowPlacement(
                root = root,
                row = it,
                visible = visible,
                placement = FloatingDragHandlePlacement.BOTTOM
            ) ||
                changed
        }
        handle?.let {
            if (it.visibility != target) {
                it.visibility = target
                changed = true
            }
        }
        return changed
    }

    private fun updateDragHandleForCurrentWindow(root: View, panelHeight: Int): Boolean {
        val row = root.findViewById<View>(R.id.keyboardDragHandleRow) ?: return false
        val placement = currentDragHandlePlacement(root, panelHeight)
        return updateDragHandleRowPlacement(root, row, visible = true, placement = placement)
    }

    private fun currentDragHandlePlacement(root: View, panelHeight: Int): FloatingDragHandlePlacement {
        val windowY = windowProvider()?.attributes?.y ?: return FloatingDragHandlePlacement.BOTTOM
        return resolveFloatingDragHandlePlacement(
            windowY = windowY,
            panelHeight = panelHeight,
            screenHeight = screenHeight(root),
            bottomThresholdPx = dp(root, DRAG_HANDLE_BOTTOM_THRESHOLD_DP)
        )
    }

    private fun updateDragHandleRowPlacement(
        root: View,
        row: View,
        visible: Boolean,
        placement: FloatingDragHandlePlacement
    ): Boolean {
        val lp = row.layoutParams as? FrameLayout.LayoutParams ?: return false
        val rowHeight = lp.height.takeIf { it > 0 }
            ?: row.height.takeIf { it > 0 }
            ?: dp(root, DRAG_HANDLE_ROW_HEIGHT_DP)
        val panel = keyboardPanel(root)
        val topHandle = visible && placement == FloatingDragHandlePlacement.TOP
        val targetGravity = if (topHandle) {
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        } else {
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
        val paddingBand = if (!visible) {
            0
        } else if (topHandle) {
            panel.paddingTop
        } else {
            panel.paddingBottom
        }
        val targetMargin = if (visible && paddingBand > 0) {
            -((paddingBand + rowHeight) / 2)
        } else {
            0
        }
        val targetTopMargin = if (topHandle) targetMargin else 0
        val targetBottomMargin = if (topHandle) 0 else targetMargin
        if (lp.gravity == targetGravity &&
            lp.topMargin == targetTopMargin &&
            lp.bottomMargin == targetBottomMargin
        ) {
            return false
        }
        lp.gravity = targetGravity
        lp.topMargin = targetTopMargin
        lp.bottomMargin = targetBottomMargin
        row.layoutParams = lp
        return true
    }

    private fun showPositioned(root: View, panel: View): Boolean {
        var changed = false
        if (root.alpha != 1f) {
            root.alpha = 1f
            changed = true
        }
        if (panel.alpha != 1f) {
            panel.alpha = 1f
            changed = true
        }
        return changed
    }

    private fun hideUntilPositioned(root: View, panel: View): Boolean {
        var changed = false
        if (root.alpha != 1f) {
            root.alpha = 1f
            changed = true
        }
        if (panel.alpha != 0f) {
            panel.alpha = 0f
            changed = true
        }
        return changed
    }

    private fun postApplyFrame(root: View) {
        if (frameApplyPosted) return
        frameApplyPosted = true
        root.post {
            frameApplyPosted = false
            if (!inputViewVisible) return@post
            if (applyFrame(root)) {
                root.requestLayout()
            }
        }
    }

    private fun shouldUseFloatingKeyboard(root: View, availableWidth: Int): Boolean = prefs.imeTabletFloatingKeyboardEnabled && availableWidth > 0

    private fun keyboardPanel(root: View): View = root.findViewById<View>(R.id.keyboardFloatingPanel) ?: root

    private fun applyFloatingHostWindowFrame(root: View, targetWidth: Int, targetHeight: Int): Boolean {
        val nextX = activeResizeWindowX.takeIf { isResizing } ?: floatingWindowX(root, targetWidth)
        val nextY = activeResizeWindowY.takeIf { isResizing } ?: floatingWindowY(root, targetHeight)
        return applyFloatingHostWindowFrame(root, targetWidth, targetHeight, nextX, nextY)
    }

    private fun applyFloatingHostWindowFrame(
        root: View,
        targetWidth: Int,
        targetHeight: Int,
        requestedX: Int,
        requestedY: Int
    ): Boolean {
        val window = windowProvider() ?: return false
        val attrs = window.attributes
        val (maxX, maxY) = floatingWindowMaxOffsets(root, targetWidth, targetHeight)
        val nextX = requestedX.coerceIn(0, maxX)
        val nextY = requestedY.coerceIn(0, maxY)
        val floatingGravity = Gravity.START or Gravity.TOP
        if (dockedWindowFlags == null) {
            // 常亮是录音会话态，不能写进贴底快照，否则停录后回贴底会把常亮带回来。
            dockedWindowFlags = attrs.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        }
        if (dockedWindowAnimations == null) {
            dockedWindowAnimations = attrs.windowAnimations
        }
        val changed = attrs.width != targetWidth ||
            attrs.height != ViewGroup.LayoutParams.WRAP_CONTENT ||
            attrs.gravity != floatingGravity ||
            attrs.x != nextX ||
            attrs.y != nextY ||
            (attrs.flags and FLOATING_WINDOW_FLAGS) != FLOATING_WINDOW_FLAGS ||
            attrs.windowAnimations != 0
        if (!changed) return false

        attrs.width = targetWidth
        attrs.height = ViewGroup.LayoutParams.WRAP_CONTENT
        attrs.gravity = floatingGravity
        attrs.x = nextX
        attrs.y = nextY
        attrs.flags = attrs.flags or FLOATING_WINDOW_FLAGS
        attrs.windowAnimations = 0
        window.attributes = attrs
        window.setLayout(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        return true
    }

    private fun restoreDockedImeWindow(root: View): Boolean {
        val window = windowProvider() ?: return false
        val attrs = window.attributes
        val changed = attrs.width != ViewGroup.LayoutParams.MATCH_PARENT ||
            attrs.height != ViewGroup.LayoutParams.WRAP_CONTENT ||
            attrs.gravity != Gravity.BOTTOM ||
            attrs.x != 0 ||
            attrs.y != 0 ||
            dockedWindowFlags != null ||
            dockedWindowAnimations != null
        if (!changed) return false

        attrs.width = ViewGroup.LayoutParams.MATCH_PARENT
        attrs.height = ViewGroup.LayoutParams.WRAP_CONTENT
        attrs.gravity = Gravity.BOTTOM
        attrs.x = 0
        attrs.y = 0
        dockedWindowFlags?.let { restored ->
            val keepScreenOn = attrs.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            attrs.flags = (restored and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()) or
                keepScreenOn
        }
        dockedWindowFlags = null
        dockedWindowAnimations?.let { attrs.windowAnimations = it }
        dockedWindowAnimations = null
        window.attributes = attrs
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        ViewCompat.requestApplyInsets(root)
        return true
    }

    private fun updatePanelFrame(panel: View, width: Int, gravity: Int): Boolean {
        val lp = (panel.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (lp.width == width &&
            lp.height == ViewGroup.LayoutParams.WRAP_CONTENT &&
            lp.leftMargin == 0 &&
            lp.topMargin == 0 &&
            lp.gravity == gravity
        ) {
            return false
        }
        lp.width = width
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.leftMargin = 0
        lp.topMargin = 0
        lp.gravity = gravity
        panel.layoutParams = lp
        return true
    }

    private fun setRootSize(root: View, width: Int, height: Int): Boolean {
        val lp = root.layoutParams ?: return false
        if (lp.width == width && lp.height == height) return false
        lp.width = width
        lp.height = height
        root.layoutParams = lp
        return true
    }

    private fun movePanelToOrigin(panel: View): Boolean {
        val changed = panel.x != 0f || panel.y != 0f
        if (changed) {
            panel.x = 0f
            panel.y = 0f
        }
        return changed
    }

    private fun scheduleWindowMove(root: View, x: Int, y: Int) {
        pendingDragWindowX = x
        pendingDragWindowY = y
        hasPendingDragMove = true
        if (dragMovePosted) return
        dragMovePosted = true
        ViewCompat.postOnAnimation(root) {
            dragMovePosted = false
            applyPendingWindowMove(root)
        }
    }

    private fun applyPendingWindowMove(root: View) {
        if (!hasPendingDragMove) return
        hasPendingDragMove = false
        moveWindow(root, pendingDragWindowX, pendingDragWindowY)
    }

    private fun scheduleResizeFrame(root: View, x: Int, y: Int, width: Int, height: Int) {
        pendingResizeWindowX = x
        pendingResizeWindowY = y
        pendingResizeWidth = width
        pendingResizeHeight = height
        hasPendingResizeFrame = true
        if (resizeFramePosted) return
        resizeFramePosted = true
        ViewCompat.postOnAnimation(root) {
            resizeFramePosted = false
            applyPendingResizeFrame(root)
        }
    }

    private fun applyPendingResizeFrame(root: View) {
        if (!hasPendingResizeFrame) return
        hasPendingResizeFrame = false
        if (applyResizeFrame(root, pendingResizeWindowX, pendingResizeWindowY, pendingResizeWidth, pendingResizeHeight)) {
            onResizeFrameChanged()
        }
    }

    private fun applyResizeFrame(root: View, x: Int, y: Int, width: Int, height: Int): Boolean {
        val window = windowProvider() ?: return false
        val panel = keyboardPanel(root)
        val targetHeight = height.takeIf { it > 0 }
            ?: window.decorView.height.takeIf { it > 0 }
            ?: panel.height.takeIf { it > 0 }
            ?: resizeStartHeight.takeIf { it > 0 }
            ?: root.height.takeIf { it > 0 }
            ?: 1
        val (maxX, maxY) = floatingWindowMaxOffsets(root, width, targetHeight)
        val nextX = x.coerceIn(0, maxX)
        val nextY = y.coerceIn(0, maxY)
        activeResizeWindowX = nextX
        activeResizeWindowY = nextY
        activeResizeWidth = width
        activeResizeHeight = targetHeight
        var changed = false
        changed = updatePanelFrame(panel, width, Gravity.TOP or Gravity.START) || changed
        changed = setRootSize(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) || changed
        changed = applyFloatingHostWindowFrame(root, width, targetHeight, nextX, nextY) || changed
        changed = updateDragHandleForCurrentWindow(root, targetHeight) || changed
        changed = movePanelToOrigin(panel) || changed
        if (changed) {
            ViewCompat.requestApplyInsets(root)
        }
        return changed
    }

    private fun moveWindow(root: View, x: Int, y: Int): Boolean {
        val window = windowProvider() ?: return false
        val attrs = window.attributes
        val targetWidth = attrs.width.takeIf { it > 0 }
            ?: window.decorView.width.takeIf { it > 0 }
            ?: root.width.takeIf { it > 0 }
            ?: return false
        val targetHeight = window.decorView.height.takeIf { it > 0 }
            ?: root.height.takeIf { it > 0 }
            ?: return false
        val (maxX, maxY) = floatingWindowMaxOffsets(root, targetWidth, targetHeight)
        val nextX = x.coerceIn(0, maxX)
        val nextY = y.coerceIn(0, maxY)
        val floatingGravity = Gravity.START or Gravity.TOP
        val changed = attrs.x != nextX ||
            attrs.y != nextY ||
            attrs.gravity != floatingGravity ||
            (attrs.flags and FLOATING_WINDOW_FLAGS) != FLOATING_WINDOW_FLAGS ||
            attrs.windowAnimations != 0
        if (changed) {
            attrs.gravity = floatingGravity
            attrs.x = nextX
            attrs.y = nextY
            attrs.flags = attrs.flags or FLOATING_WINDOW_FLAGS
            attrs.windowAnimations = 0
            window.attributes = attrs
        }
        val dragHandleChanged = updateDragHandleForCurrentWindow(root, targetHeight)
        return changed || dragHandleChanged
    }

    private fun saveWindowPosition(root: View) {
        val window = windowProvider() ?: return
        val attrs = window.attributes
        val targetWidth = attrs.width.takeIf { it > 0 } ?: window.decorView.width.takeIf { it > 0 } ?: return
        val targetHeight = window.decorView.height.takeIf { it > 0 } ?: root.height.takeIf { it > 0 } ?: return
        saveWindowFrame(root, attrs.x, attrs.y, targetWidth, targetHeight)
    }

    private fun saveWindowFrame(root: View, x: Int, y: Int, targetWidth: Int, targetHeight: Int) {
        val (maxX, maxY) = floatingWindowMaxOffsets(root, targetWidth, targetHeight)
        prefs.imeFloatingKeyboardXFraction = if (maxX > 0) x.coerceIn(0, maxX) / maxX.toFloat() else 0.5f
        prefs.imeFloatingKeyboardYFraction = if (maxY > 0) y.coerceIn(0, maxY) / maxY.toFloat() else 1.0f
        val baseWidth = defaultFloatingWidth(root, screenWidth(root))
        if (baseWidth > 0) {
            prefs.imeFloatingKeyboardWidthScale = targetWidth / baseWidth.toFloat()
        }
    }

    private fun saveActiveResizeFrame(root: View) {
        val targetWidth = activeResizeWidth ?: return
        val targetHeight = activeResizeHeight
            ?: windowProvider()?.decorView?.height?.takeIf { it > 0 }
            ?: root.height.takeIf { it > 0 }
            ?: 1
        val x = activeResizeWindowX ?: windowProvider()?.attributes?.x ?: 0
        val y = activeResizeWindowY ?: windowProvider()?.attributes?.y ?: 0
        saveWindowFrame(root, x, y, targetWidth, targetHeight)
    }

    private fun clearActiveResizeFrame() {
        activeResizeWindowX = null
        activeResizeWindowY = null
        activeResizeWidth = null
        activeResizeHeight = null
    }

    private fun floatingWindowX(root: View, targetWidth: Int): Int {
        val maxX = (screenWidth(root) - targetWidth).coerceAtLeast(0)
        return (maxX * prefs.imeFloatingKeyboardXFraction).toInt().coerceIn(0, maxX)
    }

    private fun floatingWindowY(root: View, targetHeight: Int): Int {
        val maxY = (screenHeight(root) - targetHeight).coerceAtLeast(0)
        return (maxY * prefs.imeFloatingKeyboardYFraction).toInt().coerceIn(0, maxY)
    }

    private fun floatingWindowMaxOffsets(root: View, targetWidth: Int, targetHeight: Int): Pair<Int, Int> = (screenWidth(root) - targetWidth).coerceAtLeast(0) to
        (screenHeight(root) - targetHeight).coerceAtLeast(0)

    private fun floatingTargetWidth(root: View, availableWidth: Int): Int {
        val baseWidth = defaultFloatingWidth(root, availableWidth)
        val minWidth = floatingMinResizableWidth(root, availableWidth)
        val maxWidth = floatingMaxResizableWidth(root, availableWidth)
        activeResizeWidth
            ?.takeIf { isResizing }
            ?.let { return it.coerceIn(minWidth, maxWidth) }
        return (baseWidth.toDouble() * prefs.imeFloatingKeyboardWidthScale.toDouble())
            .coerceIn(minWidth.toDouble(), maxWidth.toDouble())
            .toInt()
    }

    private fun defaultFloatingWidth(root: View, availableWidth: Int): Int {
        val sideMargin = dp(root, FLOATING_SIDE_MARGIN_DP)
        val maxWidth = dp(root, FLOATING_MAX_WIDTH_DP)
        val minWidth = dp(root, FLOATING_MIN_WIDTH_DP)
        return (availableWidth - sideMargin * 2)
            .coerceAtMost(maxWidth)
            .coerceAtLeast(minOf(minWidth, availableWidth))
    }

    private fun floatingMinResizableWidth(root: View, availableWidth: Int): Int = minOf(dp(root, FLOATING_RESIZE_MIN_WIDTH_DP), availableWidth)
        .coerceAtLeast(1)

    private fun floatingMaxResizableWidth(root: View, availableWidth: Int): Int {
        val sideMargin = dp(root, FLOATING_SIDE_MARGIN_DP)
        val defaultWidth = defaultFloatingWidth(root, availableWidth)
        return (availableWidth - sideMargin * 2)
            .coerceAtLeast(defaultWidth)
            .coerceAtMost(availableWidth)
            .coerceAtLeast(1)
    }

    private fun screenWidth(root: View): Int = screenSize(root).width

    private fun screenHeight(root: View): Int = screenSize(root).height

    private fun screenSize(root: View): WindowSizePx {
        val windowManager = windowProvider()?.windowManager
            ?: root.context.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // The IME moves within its display, not within the already-shrunk floating window.
            return windowManager.maximumUsableWindowSize()
        }
        return windowManager.legacyUsableWindowSize()
    }

    private fun dp(view: View, value: Float): Int = (value * view.resources.displayMetrics.density + 0.5f).toInt()

    private fun View.dimensionOrLayoutParam(isWidth: Boolean): Int? {
        val lpValue = if (isWidth) layoutParams?.width else layoutParams?.height
        if (lpValue != null && lpValue > 0) return lpValue
        val current = if (isWidth) width else height
        if (current > 0) return current
        val measured = if (isWidth) measuredWidth else measuredHeight
        return measured.takeIf { it > 0 }
    }

    private companion object {
        private const val FLOATING_MAX_WIDTH_DP = 600f
        private const val FLOATING_MIN_WIDTH_DP = 320f
        private const val FLOATING_RESIZE_MIN_WIDTH_DP = 260f
        private const val FLOATING_SIDE_MARGIN_DP = 20f
        private const val DRAG_HANDLE_ROW_HEIGHT_DP = 24f
        private const val DRAG_HANDLE_BOTTOM_THRESHOLD_DP = 100f
        private const val RESIZE_HANDLE_HEIGHT_DP = 24f
        private val FLOATING_WINDOW_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        private val resizeHandleIds = intArrayOf(
            R.id.keyboardResizeHandleBottomLeft,
            R.id.keyboardResizeHandleBottomRight,
            R.id.keyboardResizeHandleTopLeft,
            R.id.keyboardResizeHandleTopRight
        )
    }

    private enum class ResizeEdge {
        LEFT,
        RIGHT,
        TOP,
        BOTTOM
    }

    private enum class ResizeCorner(
        val horizontalEdge: ResizeEdge,
        val verticalEdge: ResizeEdge
    ) {
        TOP_LEFT(ResizeEdge.LEFT, ResizeEdge.TOP),
        TOP_RIGHT(ResizeEdge.RIGHT, ResizeEdge.TOP),
        BOTTOM_LEFT(ResizeEdge.LEFT, ResizeEdge.BOTTOM),
        BOTTOM_RIGHT(ResizeEdge.RIGHT, ResizeEdge.BOTTOM)
    }
}
