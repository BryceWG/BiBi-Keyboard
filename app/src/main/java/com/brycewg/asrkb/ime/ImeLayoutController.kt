/**
 * IME 面板布局缩放、悬浮键盘与 inset 协调器。
 *
 * 归属模块：ime
 */
package com.brycewg.asrkb.ime

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ime.layout.BlockDefRegistry
import com.brycewg.asrkb.ime.layout.KeyboardLayoutBundle
import com.brycewg.asrkb.ime.layout.KeyboardLayoutPanel
import com.brycewg.asrkb.ime.layout.KeyboardLayoutRuntimeApplier
import com.brycewg.asrkb.ime.layout.KeyboardLayoutStore
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.store.debug.DebugLogManager

internal class ImeLayoutController(
    private val prefs: Prefs,
    private val themeStyler: ImeThemeStyler,
    private val windowProvider: () -> Window?,
    private val viewRefsProvider: () -> ImeViewRefs?
) {
    private val floatingKeyboardController = ImeFloatingKeyboardController(
        prefs = prefs,
        windowProvider = windowProvider,
        onResizeFrameChanged = ::postFloatingResizeLayoutPass
    )
    private var rootView: View? = null
    private var systemNavBarBottomInset: Int = 0
    private var hasReceivedSystemInsets: Boolean = false
    private var lastAppliedHeightScale: Float = 1.0f
    private var floatingResizeLayoutPassPosted: Boolean = false
    private var layoutApplyPosted: Boolean = false
    private var dockedTabletAlignment: DockedTabletAlignment = DockedTabletAlignment.CENTER
    private var cachedLayoutSource: String? = null
    private var cachedLayoutBundle: KeyboardLayoutBundle? = null
    private var lastLayoutSignature: LayoutSignature? = null
    private var lastLoggedSkippedSignature: LayoutSignature? = null
    private var lastKeyboardContentHeight: Int = 0
    private var loggedMissingContentHeight: Boolean = false
    private var layoutMeasureCache: ImeLayoutMeasureCache? = null
    private var pendingMeasuredInputHeight: Int? = null
    private var lastRecordedInputMeasurement: Triple<Int, Int, Int>? = null
    private var measureCacheResolutionLogged: Boolean = false

    fun installKeyboardInsetsListener(rootView: View) {
        this.rootView = rootView
        systemNavBarBottomInset = 0
        hasReceivedSystemInsets = false
        lastLayoutSignature = null
        lastLoggedSkippedSignature = null
        pendingMeasuredInputHeight = null
        lastRecordedInputMeasurement = null
        measureCacheResolutionLogged = false
        layoutMeasureCache = ImeLayoutMeasureCache(rootView.context)
        floatingKeyboardController.install(rootView)
        themeStyler.installKeyboardInsetsListener(rootView) { bottom ->
            hasReceivedSystemInsets = true
            if (systemNavBarBottomInset == bottom) return@installKeyboardInsetsListener
            systemNavBarBottomInset = bottom
            pendingMeasuredInputHeight = null
            lastRecordedInputMeasurement = null
            if (applySystemBottomInset(rootView)) {
                rootView.requestLayout()
            }
        }
        rootView.findViewById<View>(R.id.keyboardDockButtonLeft)?.setOnClickListener {
            dockedTabletAlignment = dockedTabletAlignment.moveLeft()
            applyKeyboardHeightScaleAndRequestLayout()
        }
        rootView.findViewById<View>(R.id.keyboardDockButtonRight)?.setOnClickListener {
            dockedTabletAlignment = dockedTabletAlignment.moveRight()
            applyKeyboardHeightScaleAndRequestLayout()
        }
    }

    fun bindMicVerticalFix(views: ImeViewRefs) {
        views.btnMic?.translationY = 0f
    }

    fun onInputViewStarted() {
        pendingMeasuredInputHeight = null
        measureCacheResolutionLogged = false
        floatingKeyboardController.onInputViewStarted()
    }

    fun onInputViewFinished() {
        floatingResizeLayoutPassPosted = false
        layoutApplyPosted = false
        floatingKeyboardController.onInputViewFinished()
    }

    private fun postFloatingResizeLayoutPass() {
        val root = rootView ?: viewRefsProvider()?.rootView ?: return
        if (floatingResizeLayoutPassPosted) return
        floatingResizeLayoutPassPosted = true
        root.postOnAnimation {
            floatingResizeLayoutPassPosted = false
            applyKeyboardHeightScaleAndRequestLayout()
        }
    }

    private fun applyKeyboardHeightScaleAndRequestLayout() {
        val root = rootView ?: viewRefsProvider()?.rootView ?: return
        if (applyKeyboardHeightScale()) {
            root.requestLayout()
        }
    }

    fun scheduleKeyboardLayoutApply() {
        val root = rootView ?: viewRefsProvider()?.rootView ?: return
        if (layoutApplyPosted) return
        layoutApplyPosted = true
        root.postOnAnimation {
            layoutApplyPosted = false
            if (rootView !== root) return@postOnAnimation
            applyKeyboardHeightScaleAndRequestLayout()
        }
    }

    fun applyKeyboardHeightScale(): Boolean {
        val root = rootView ?: viewRefsProvider()?.rootView ?: return false
        val refs = viewRefsProvider()
        if (floatingKeyboardController.isDragging) return false
        var layoutChanged = false

        val tier = prefs.keyboardHeightTier
        val scale = when (tier) {
            1 -> 0.85f
            3 -> 1.15f
            else -> 1.0f
        }

        fun dp(v: Float): Int = dp(root, v)
        layoutChanged = floatingKeyboardController.applyFrame(root) || layoutChanged

        val signature = layoutSignature(root, scale)
        if (lastLayoutSignature == signature) {
            if (lastLoggedSkippedSignature != signature) {
                lastLoggedSkippedSignature = signature
                DebugLogManager.log(
                    category = "ime",
                    event = "ime_layout_apply_skip",
                    data = mapOf("reason" to "unchanged")
                )
            }
            return layoutChanged
        }
        lastLayoutSignature = signature
        lastLoggedSkippedSignature = null
        lastRecordedInputMeasurement = null

        // 麦克风现在由容器居中；缩放变化时清掉旧版本可能留下的位移。
        if (kotlin.math.abs(lastAppliedHeightScale - scale) > 1e-3f) {
            lastAppliedHeightScale = scale
            refs?.btnMic?.translationY = 0f
        }

        fun updateLayoutSize(view: View?, width: Int? = null, height: Int? = null) {
            if (view == null) return
            val lp = view.layoutParams ?: return
            var changed = false
            if (width != null && lp.width != width) {
                lp.width = width
                changed = true
            }
            if (height != null && lp.height != height) {
                lp.height = height
                changed = true
            }
            if (changed) {
                view.layoutParams = lp
                layoutChanged = true
            }
        }

        // 同步一次当前 RootWindowInsets，避免首次缩放时 bottom inset 尚未写入导致底部裁剪。
        run {
            val rw = ViewCompat.getRootWindowInsets(root)
            if (rw != null) {
                systemNavBarBottomInset = ImeInsetsResolver.resolveBottomInset(rw, root.resources)
                hasReceivedSystemInsets = true
            }
        }

        val fl = root.findViewById<View>(R.id.keyboardFloatingPanel) ?: root
        run {
            val ps = fl.paddingStart
            val pe = fl.paddingEnd
            val pt = if (floatingKeyboardController.isActive) {
                dp(FLOATING_PANEL_TOP_PADDING_DP)
            } else {
                dp(DOCKED_PANEL_VERTICAL_PADDING_DP * scale)
            }
            val scaledBasePb = dp(DOCKED_PANEL_VERTICAL_PADDING_DP * scale)
            val basePb = if (floatingKeyboardController.isActive) {
                scaledBasePb.coerceAtLeast(dp(FLOATING_PANEL_BOTTOM_PADDING_DP))
            } else {
                scaledBasePb
            }
            val extraPadding = if (floatingKeyboardController.isActive) {
                0
            } else {
                dp(prefs.keyboardBottomPaddingDp.toFloat())
            }
            // 对齐 Trime/fcitx 的分层：键盘内容不消费系统 inset，底部系统区域由独立 spacer 承接。
            val pb = basePb + extraPadding
            if (fl.paddingTop != pt || fl.paddingBottom != pb) {
                fl.setPaddingRelative(ps, pt, pe, pb)
                layoutChanged = true
            }
            layoutChanged = applySystemBottomInset(root) || layoutChanged
        }
        layoutChanged = applyDockedTabletContentWidth(root, fl, scale) || layoutChanged

        fun scaleSquareButton(id: Int) {
            val v = root.findViewById<View>(id) ?: return
            updateLayoutSize(v, width = dp(40f * scale), height = dp(40f * scale))
        }

        fun scaleGestureButton(v: View?) {
            val baseSize = 86f * scale
            updateLayoutSize(v, width = dp(baseSize), height = dp(baseSize))
        }

        fun scaleChildrenByTag(root: View?, tag: String, height: Int) {
            if (root == null) return
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) {
                    scaleChildrenByTag(root.getChildAt(i), tag, height)
                }
            }
            val t = root.tag as? String
            if (t == tag) {
                updateLayoutSize(root, height = height)
            }
        }

        val ids40 = (
            KeyboardLayoutPanel.values().flatMap { panel ->
                BlockDefRegistry.default.defsFor(panel).mapNotNull { it.viewId }
            } +
                listOf(
                    R.id.clip_btnBack,
                    R.id.clip_btnDelete,
                    R.id.asr_hist_btnBack,
                    R.id.asr_hist_headerEndSpacer
                )
            )
            .filter { it != R.id.groupMicStatus }
            .distinct()
        ids40.forEach { scaleSquareButton(it) }
        scaleGestureButton(refs?.btnGestureCancel)
        scaleGestureButton(refs?.btnGestureSend)

        run {
            val v1: View? = refs?.btnExtCenter1 ?: root.findViewById(R.id.btnExtCenter1)
            updateLayoutSize(v1, height = dp(40f * scale))
        }

        run {
            val v2: View? = refs?.btnExtCenter2 ?: root.findViewById(R.id.btnExtCenter2)
            updateLayoutSize(v2, height = dp(40f * scale))
        }

        if (KeyboardLayoutRuntimeApplier.applyAll(root, currentLayoutBundle(), scale)) {
            layoutChanged = true
        }

        run {
            val panel: View? = refs?.layoutNumpadPanel ?: root.findViewById(R.id.layoutNumpadPanel)
            if (panel != null) {
                val targetPanelHeight = resolveKeyboardContentHeight(root, scale)
                val gapPx = dp(6f)
                val rowHeightPx = ((targetPanelHeight - gapPx * 3) / 4).coerceAtLeast(dp(32f))
                updateLayoutSize(panel, height = targetPanelHeight)
                scaleChildrenByTag(panel, "key40", rowHeightPx)

                val ps = panel.paddingStart
                val pe = panel.paddingEnd
                val pb = panel.paddingBottom
                if (panel.paddingTop != 0) {
                    panel.setPaddingRelative(ps, 0, pe, pb)
                    layoutChanged = true
                }

                fun updateLinearTopMargin(id: Int, topPx: Int) {
                    val v = panel.findViewById<View>(id) ?: return
                    val lp = v.layoutParams as? LinearLayout.LayoutParams ?: return
                    if (lp.topMargin == topPx) return
                    lp.topMargin = topPx
                    v.layoutParams = lp
                    layoutChanged = true
                }

                fun updateLinearBottomMargin(id: Int, bottomPx: Int) {
                    val v = panel.findViewById<View>(id) ?: return
                    val lp = v.layoutParams as? LinearLayout.LayoutParams ?: return
                    if (lp.bottomMargin == bottomPx) return
                    lp.bottomMargin = bottomPx
                    v.layoutParams = lp
                    layoutChanged = true
                }

                updateLinearBottomMargin(R.id.rowNumpadDigits, gapPx)
                updateLinearBottomMargin(R.id.rowPunct1, gapPx)
                updateLinearBottomMargin(R.id.rowPunct2, gapPx)
                updateLinearTopMargin(R.id.rowNumpadBottomBar, 0)
            }
        }

        refs?.groupMicStatus?.let { group ->
            if (group.translationY != 0f) {
                group.translationY = 0f
            }
        }
        refs?.btnMic?.let { mic ->
            val group = refs.groupMicStatus
            val groupWidth = group?.dimensionOrLayoutParam(isWidth = true)
            val groupHeight = group?.dimensionOrLayoutParam(isWidth = false)
            val targetWidth: Int
            val targetHeight: Int
            if (groupWidth != null && groupHeight != null) {
                targetWidth = scaledMicDimension(groupWidth)
                targetHeight = scaledMicDimension(groupHeight)
            } else {
                val fallbackSize = dp(80f * scale)
                targetWidth = fallbackSize
                targetHeight = fallbackSize
            }
            layoutChanged = applyMicButtonSizing(mic, targetWidth, targetHeight) || layoutChanged
        }
        refs
            ?.takeIf { it.layoutMainKeyboard?.visibility == View.VISIBLE }
            ?.groupMicStatus
            ?.bringToFront()
        layoutChanged = floatingKeyboardController.applyFrame(root) || layoutChanged
        return layoutChanged
    }

    private fun applyDockedTabletContentWidth(root: View, keyboardPanel: View, scale: Float): Boolean {
        val contentPanel = root.findViewById<View>(R.id.keyboardContentPanel) ?: return false
        val availableWidth = keyboardPanel.contentWidth()
            ?: (root.width - root.paddingStart - root.paddingEnd).takeIf { it > 0 }
            ?: root.resources.displayMetrics.widthPixels
        if (availableWidth <= 0) return false

        val shouldConstrain = !floatingKeyboardController.isActive &&
            !prefs.imeTabletFloatingKeyboardEnabled &&
            shouldUseDockedTabletConstraint(root, availableWidth)
        val targetWidth = if (shouldConstrain) {
            resolveDockedTabletContentWidth(root, keyboardPanel, scale, availableWidth)
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        val constrained = targetWidth != ViewGroup.LayoutParams.MATCH_PARENT
        if (!constrained) {
            dockedTabletAlignment = DockedTabletAlignment.CENTER
        }
        val targetGravity = if (constrained) {
            dockedTabletAlignment.gravity
        } else {
            Gravity.TOP
        }
        var changed = updateDockedTabletButtons(root, constrained, targetWidth, availableWidth)

        val lp = (contentPanel.layoutParams as? FrameLayout.LayoutParams)
            ?: FrameLayout.LayoutParams(targetWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (lp.width == targetWidth &&
            lp.height == ViewGroup.LayoutParams.WRAP_CONTENT &&
            lp.gravity == targetGravity &&
            lp.leftMargin == 0 &&
            lp.topMargin == 0 &&
            lp.rightMargin == 0
        ) {
            return changed
        }
        lp.width = targetWidth
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        lp.gravity = targetGravity
        lp.leftMargin = 0
        lp.topMargin = 0
        lp.rightMargin = 0
        contentPanel.layoutParams = lp
        changed = true
        return changed
    }

    private fun applySystemBottomInset(root: View): Boolean {
        val panel = root.findViewById<View>(R.id.keyboardFloatingPanel) ?: root
        val bottomInset = if (floatingKeyboardController.isActive) {
            0
        } else {
            systemNavBarBottomInset.coerceAtLeast(0)
        }
        return updatePanelBottomMargin(panel, bottomInset) or
            updateSystemBottomSpace(root.findViewById(R.id.keyboardSystemBottomSpace), bottomInset)
    }

    /**
     * 覆盖面板（数字/符号小键盘）的高度基准。
     *
     * 主键盘容器在输入视图首帧尚未量到高度，被面板覆盖期间也不会重新布局，
     * 此时直接回退到固定 dp 会让按键行高与真实键盘高度脱节；故缓存最近一次有效测量值作为回退。
     */
    private fun resolveKeyboardContentHeight(root: View, scale: Float): Int {
        val measured = root.findViewById<View>(R.id.keyboardLayoutCanvas)?.height?.takeIf { it > 0 }
            ?: viewRefsProvider()?.layoutMainKeyboard?.height?.takeIf { it > 0 }
        if (measured != null) {
            lastKeyboardContentHeight = measured
            return measured
        }
        lastKeyboardContentHeight.takeIf { it > 0 }?.let { return it }
        if (!loggedMissingContentHeight) {
            loggedMissingContentHeight = true
            DebugLogManager.logBase(
                category = "ime",
                event = "ime_panel_height_fallback",
                data = mapOf("scale" to scale)
            )
        }
        return dp(root, 190f * scale)
    }

    private fun currentLayoutBundle(): KeyboardLayoutBundle {
        val source = currentLayoutSource()
        cachedLayoutBundle?.takeIf { cachedLayoutSource == source }?.let { return it }
        return KeyboardLayoutStore.load(prefs).also { bundle ->
            cachedLayoutSource = source
            cachedLayoutBundle = bundle
            DebugLogManager.log(
                category = "ime",
                event = "ime_layout_bundle_cache_miss",
                data = mapOf("sourceLength" to source.length)
            )
        }
    }

    private fun layoutSignature(root: View, scale: Float): LayoutSignature {
        val config = root.resources.configuration
        val panel = root.findViewById<View>(R.id.keyboardFloatingPanel)
        val content = root.findViewById<View>(R.id.keyboardContentPanel)
        return LayoutSignature(
            rootWidth = root.width,
            panelWidth = panel?.width ?: 0,
            panelLayoutWidth = panel?.layoutParams?.width ?: 0,
            contentWidth = content?.width ?: 0,
            keyboardContentHeight = resolveKeyboardContentHeight(root, scale),
            screenWidthDp = config.screenWidthDp,
            screenHeightDp = config.screenHeightDp,
            orientation = config.orientation,
            densityDpi = root.resources.displayMetrics.densityDpi,
            fontScale = config.fontScale,
            scale = scale,
            bottomPaddingDp = prefs.keyboardBottomPaddingDp,
            layoutSource = currentLayoutSource(),
            floatingEnabled = prefs.imeTabletFloatingKeyboardEnabled,
            floatingActive = floatingKeyboardController.isActive,
            dockedAlignment = dockedTabletAlignment,
            mainVisible = root.findViewById<View>(R.id.layoutMainKeyboard)?.visibility == View.VISIBLE,
            aiVisible = root.findViewById<View>(R.id.layoutAiEditPanel)?.visibility == View.VISIBLE,
            recordingVisible = root.findViewById<View>(R.id.rowRecordingGestures)?.visibility == View.VISIBLE,
            numpadVisible = root.findViewById<View>(R.id.layoutNumpadPanel)?.visibility == View.VISIBLE
        )
    }

    private fun currentLayoutSource(): String = buildString {
        append(prefs.customKeyboardLayoutsJson)
        append('|').append(prefs.extBtn1.id)
        append('|').append(prefs.extBtn2.id)
        append('|').append(prefs.extBtn3.id)
        append('|').append(prefs.extBtn4.id)
    }

    private fun updateDockedTabletButtons(
        root: View,
        constrained: Boolean,
        contentWidth: Int,
        availableWidth: Int
    ): Boolean {
        val leftButton = root.findViewById<View>(R.id.keyboardDockButtonLeft)
        val rightButton = root.findViewById<View>(R.id.keyboardDockButtonRight)
        var changed = false
        val leftVisible = constrained && dockedTabletAlignment.canMoveLeft
        val rightVisible = constrained && dockedTabletAlignment.canMoveRight
        changed = setDockButtonVisible(leftButton, leftVisible) || changed
        changed = setDockButtonVisible(rightButton, rightVisible) || changed
        if (constrained) {
            val totalSideSpace = (availableWidth - contentWidth).coerceAtLeast(0)
            val centeredSideSpace = totalSideSpace / 2
            val leftSideSpace = if (dockedTabletAlignment == DockedTabletAlignment.RIGHT) {
                totalSideSpace
            } else {
                centeredSideSpace
            }
            val rightSideSpace = if (dockedTabletAlignment == DockedTabletAlignment.LEFT) {
                totalSideSpace
            } else {
                centeredSideSpace
            }
            changed = updateDockButtonFrame(
                root = root,
                button = leftButton,
                sideSpace = leftSideSpace,
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
            ) ||
                changed
            changed = updateDockButtonFrame(
                root = root,
                button = rightButton,
                sideSpace = rightSideSpace,
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
            ) ||
                changed
            leftButton?.bringToFront()
            rightButton?.bringToFront()
        }
        return changed
    }

    private fun setDockButtonVisible(button: View?, visible: Boolean): Boolean {
        val target = if (visible) View.VISIBLE else View.GONE
        if (button == null || button.visibility == target) return false
        button.visibility = target
        return true
    }

    private fun updateDockButtonFrame(
        root: View,
        button: View?,
        sideSpace: Int,
        gravity: Int
    ): Boolean {
        if (button == null) return false
        val lp = button.layoutParams as? FrameLayout.LayoutParams ?: return false
        val minimumMargin = dp(root, DOCKED_TABLET_DOCK_BUTTON_SIDE_MARGIN_DP)
        val buttonWidth = button.dimensionOrLayoutParam(isWidth = true)?.coerceAtLeast(1) ?: lp.width.coerceAtLeast(1)
        val sideMargin = ((sideSpace - buttonWidth) / 2).coerceAtLeast(minimumMargin)
        if (lp.gravity == gravity &&
            lp.leftMargin == sideMargin &&
            lp.rightMargin == sideMargin &&
            lp.topMargin == 0 &&
            lp.bottomMargin == 0
        ) {
            return false
        }
        lp.gravity = gravity
        lp.leftMargin = sideMargin
        lp.rightMargin = sideMargin
        lp.topMargin = 0
        lp.bottomMargin = 0
        button.layoutParams = lp
        return true
    }

    private fun resolveDockedTabletContentWidth(
        root: View,
        keyboardPanel: View,
        scale: Float,
        availableWidth: Int
    ): Int {
        val bundle = currentLayoutBundle()
        val maxRowsColsRatio = listOf(bundle.main, bundle.aiEdit, bundle.recording)
            .maxOf { layout -> layout.gridSize.rows.toFloat() / layout.gridSize.cols.toFloat() }
            .coerceAtLeast(0.1f)
        val screenHeight = root.resources.displayMetrics.heightPixels.takeIf { it > 0 }
            ?: root.rootView?.height?.takeIf { it > 0 }
            ?: root.height.takeIf { it > 0 }
            ?: return ViewGroup.LayoutParams.MATCH_PARENT
        val baseBottomPadding = if (floatingKeyboardController.isActive) {
            dp(root, DOCKED_PANEL_VERTICAL_PADDING_DP * scale)
                .coerceAtLeast(dp(root, DOCKED_PANEL_VERTICAL_PADDING_DP))
        } else {
            dp(root, DOCKED_PANEL_VERTICAL_PADDING_DP * scale)
        }
        val maxContentHeight = screenHeight * DOCKED_TABLET_MAX_HEIGHT_RATIO -
            keyboardPanel.paddingTop -
            baseBottomPadding

        val minWidth = minOf(dp(root, DOCKED_TABLET_MIN_CONTENT_WIDTH_DP), availableWidth).coerceAtLeast(1)
        val maxWidthByHeight = if (maxContentHeight > 0f) {
            (maxContentHeight / scale.coerceAtLeast(0.1f) / maxRowsColsRatio).toInt()
        } else {
            availableWidth
        }
        val maxWidthByScreen = (availableWidth * DOCKED_TABLET_MAX_WIDTH_RATIO).toInt().coerceAtLeast(1)
        val targetWidth = minOf(maxWidthByHeight, maxWidthByScreen)
            .coerceIn(minWidth, availableWidth)
        return if (targetWidth >= availableWidth) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            targetWidth
        }
    }

    // Keep tall portrait phones full-width while covering tablets, unfolded foldables, and phone landscape.
    private fun shouldUseDockedTabletConstraint(root: View, availableWidth: Int): Boolean {
        val density = root.resources.displayMetrics.density.coerceAtLeast(1f)
        val availableWidthDp = (availableWidth / density).toInt()
        val config = root.resources.configuration
        val configWidthDp = config.screenWidthDp.takeIf {
            it > 0 && it != Configuration.SCREEN_WIDTH_DP_UNDEFINED
        } ?: availableWidthDp
        val configHeightDp = config.screenHeightDp.takeIf {
            it > 0 && it != Configuration.SCREEN_HEIGHT_DP_UNDEFINED
        } ?: (root.resources.displayMetrics.heightPixels / density).toInt()
        val smallestDp = config.smallestScreenWidthDp.takeIf {
            it > 0 && it != Configuration.SMALLEST_SCREEN_WIDTH_DP_UNDEFINED
        } ?: 0
        val shortSideDp = minOf(configWidthDp, configHeightDp)
        val longSideDp = maxOf(configWidthDp, configHeightDp)
        val aspectRatio = if (shortSideDp > 0) {
            longSideDp.toFloat() / shortSideDp.toFloat()
        } else {
            Float.MAX_VALUE
        }
        val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE ||
            configWidthDp > configHeightDp
        val tabletWindow = smallestDp >= TABLET_SMALLEST_WIDTH_DP ||
            shortSideDp >= TABLET_SMALLEST_WIDTH_DP
        val foldableLikeWindow = shortSideDp >= FOLDABLE_LIKE_MIN_SHORT_SIDE_DP &&
            longSideDp >= FOLDABLE_LIKE_MIN_LONG_SIDE_DP &&
            aspectRatio <= FOLDABLE_LIKE_MAX_ASPECT_RATIO
        val phoneLandscapeWindow = isLandscape &&
            availableWidthDp >= PHONE_LANDSCAPE_MIN_WIDTH_DP &&
            shortSideDp >= PHONE_LANDSCAPE_MIN_SHORT_SIDE_DP
        return tabletWindow || foldableLikeWindow || phoneLandscapeWindow
    }

    private fun View.contentWidth(): Int? {
        val current = width - paddingStart - paddingEnd
        if (current > 0) return current
        val measured = measuredWidth - paddingStart - paddingEnd
        if (measured > 0) return measured
        return (layoutParams?.width ?: 0)
            .takeIf { it > 0 }
            ?.let { (it - paddingStart - paddingEnd).coerceAtLeast(1) }
    }

    private fun updatePanelBottomMargin(panel: View, bottomMargin: Int): Boolean {
        val lp = panel.layoutParams as? ViewGroup.MarginLayoutParams ?: return false
        if (lp.bottomMargin == bottomMargin) return false
        lp.bottomMargin = bottomMargin
        panel.layoutParams = lp
        return true
    }

    private fun updateSystemBottomSpace(space: View?, height: Int): Boolean {
        if (space == null) return false
        val targetHeight = height.coerceAtLeast(0)
        var changed = false
        val targetVisibility = if (targetHeight > 0) View.VISIBLE else View.GONE
        if (space.visibility != targetVisibility) {
            space.visibility = targetVisibility
            changed = true
        }
        val lp = space.layoutParams ?: return changed
        if (lp.height != targetHeight) {
            lp.height = targetHeight
            space.layoutParams = lp
            changed = true
        }
        return changed
    }

    private fun dp(view: View, value: Float): Int = (value * view.resources.displayMetrics.density + 0.5f).toInt()

    private fun View.dimensionOrLayoutParam(isWidth: Boolean): Int? {
        val lpValue = if (isWidth) layoutParams?.width else layoutParams?.height
        if (lpValue != null && lpValue > 0) return lpValue
        val current = if (isWidth) width else height
        return current.takeIf { it > 0 }
    }

    private fun scaledMicDimension(value: Int): Int = (value.coerceAtLeast(1) * MIC_SIZE_RATIO).toInt().coerceAtLeast(1)

    private fun applyMicButtonSizing(button: ImageButton, width: Int, height: Int): Boolean {
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

    private companion object {
        private const val TABLET_SMALLEST_WIDTH_DP = 600
        private const val DOCKED_PANEL_VERTICAL_PADDING_DP = 8f
        private const val FLOATING_PANEL_TOP_PADDING_DP = 24f
        private const val FLOATING_PANEL_BOTTOM_PADDING_DP = 24f
        private const val DOCKED_TABLET_MAX_HEIGHT_RATIO = 0.30f
        private const val DOCKED_TABLET_MAX_WIDTH_RATIO = 0.65f
        private const val DOCKED_TABLET_MIN_CONTENT_WIDTH_DP = 360f
        private const val DOCKED_TABLET_DOCK_BUTTON_SIDE_MARGIN_DP = 8f
        private const val FOLDABLE_LIKE_MIN_SHORT_SIDE_DP = 520
        private const val FOLDABLE_LIKE_MIN_LONG_SIDE_DP = 680
        private const val FOLDABLE_LIKE_MAX_ASPECT_RATIO = 1.7f
        private const val PHONE_LANDSCAPE_MIN_WIDTH_DP = 600
        private const val PHONE_LANDSCAPE_MIN_SHORT_SIDE_DP = 320
        private const val MIC_SIZE_RATIO = 1f
        private const val MIC_ICON_SIZE_RATIO = 0.42f
    }

    private enum class DockedTabletAlignment(val gravity: Int) {
        CENTER(Gravity.TOP or Gravity.CENTER_HORIZONTAL),
        LEFT(Gravity.TOP or Gravity.START),
        RIGHT(Gravity.TOP or Gravity.END);

        val canMoveLeft: Boolean
            get() = this != LEFT

        val canMoveRight: Boolean
            get() = this != RIGHT

        fun moveLeft(): DockedTabletAlignment = when (this) {
            LEFT -> LEFT
            CENTER -> LEFT
            RIGHT -> CENTER
        }

        fun moveRight(): DockedTabletAlignment = when (this) {
            LEFT -> CENTER
            CENTER -> RIGHT
            RIGHT -> RIGHT
        }
    }

    fun hasResolvedBottomInset(): Boolean = systemNavBarBottomInset > 0

    fun fixImeInsetsIfNeeded(
        imeViewVisible: Boolean,
        outInsets: InputMethodService.Insets,
        decorView: View?
    ) {
        if (!imeViewVisible) return
        val input = rootView ?: viewRefsProvider()?.rootView ?: return
        val decor = decorView ?: return

        val decorH = decor.height
        val decorW = decor.width
        if (decorH <= 0 || decorW <= 0) return
        if (floatingKeyboardController.isActive) {
            floatingKeyboardController.fixInsets(input, decor, outInsets)
            return
        }

        val laidOutInputHeight = input.height
        var inputH = laidOutInputHeight
        if (inputH <= 0) {
            inputH = pendingMeasuredInputHeight ?: if (hasReceivedSystemInsets) {
                layoutMeasureCache?.read(
                    root = input,
                    prefs = prefs,
                    decorWidth = decorW,
                    decorHeight = decorH,
                    bottomInset = systemNavBarBottomInset
                )
            } else {
                null
            } ?: 0
            val cacheHit = inputH > 0
            if (!measureCacheResolutionLogged) {
                measureCacheResolutionLogged = true
                DebugLogManager.log(
                    category = "ime",
                    event = if (cacheHit) "ime_measure_cache_hit" else "ime_measure_cache_miss",
                    data = mapOf(
                        "decorW" to decorW,
                        "decorH" to decorH,
                        "insetsReady" to hasReceivedSystemInsets
                    )
                )
            }
            if (!cacheHit) {
                try {
                    val wSpec = View.MeasureSpec.makeMeasureSpec(decorW, View.MeasureSpec.EXACTLY)
                    val hSpec = View.MeasureSpec.makeMeasureSpec(decorH, View.MeasureSpec.AT_MOST)
                    input.measure(wSpec, hSpec)
                    inputH = input.measuredHeight
                    pendingMeasuredInputHeight = inputH.takeIf { it > 0 }
                } catch (t: Throwable) {
                    android.util.Log.w("AsrKeyboardService", "fixImeInsets measure failed", t)
                    return
                }
            }
        } else {
            pendingMeasuredInputHeight = null
            val measurement = Triple(decorW, decorH, inputH)
            if (hasReceivedSystemInsets && lastRecordedInputMeasurement != measurement) {
                lastRecordedInputMeasurement = measurement
                layoutMeasureCache?.record(
                    root = input,
                    prefs = prefs,
                    decorWidth = decorW,
                    decorHeight = decorH,
                    bottomInset = systemNavBarBottomInset,
                    measuredHeight = inputH
                )
            }
        }
        if (inputH <= 0) return

        val beforeContentTop = outInsets.contentTopInsets
        val beforeVisibleTop = outInsets.visibleTopInsets

        val topByHeight = (decorH - inputH).coerceIn(0, decorH)
        var locationTop = -1
        run {
            try {
                val loc = IntArray(2)
                input.getLocationInWindow(loc)
                locationTop = loc[1]
            } catch (t: Throwable) {
                android.util.Log.w(
                    "AsrKeyboardService",
                    "fixImeInsets getLocationInWindow failed",
                    t
                )
            }
        }
        val top = if (laidOutInputHeight > 0 && locationTop in 0 until decorH) {
            minOf(locationTop, topByHeight)
        } else {
            topByHeight
        }

        val correctionThresholdPx = (decor.resources.displayMetrics.density * 2f + 0.5f).toInt().coerceAtLeast(
            1
        )
        val needsColdStartFix = beforeContentTop <= 0
        // 系统给出的 contentTopInsets 偏大时会导致宿主上移不足，出现输入框被键盘遮挡。
        val needsOverlapFix = beforeContentTop > top + correctionThresholdPx
        val needsFix = top > 0 && decorH > inputH && (needsColdStartFix || needsOverlapFix)
        if (!needsFix) return

        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
        outInsets.touchableInsets = InputMethodService.Insets.TOUCHABLE_INSETS_REGION
        // 触摸区域限定为键盘区域，避免空白区域吞触摸。
        outInsets.touchableRegion.set(0, top, decorW, decorH)

        DebugLogManager.log(
            category = "ime",
            event = "compute_insets_fix",
            data = mapOf(
                "decorH" to decorH,
                "decorW" to decorW,
                "inputH" to inputH,
                "beforeContentTop" to beforeContentTop,
                "beforeVisibleTop" to beforeVisibleTop,
                "topByHeight" to topByHeight,
                "locationTop" to locationTop,
                "needsColdStartFix" to needsColdStartFix,
                "needsOverlapFix" to needsOverlapFix,
                "correctionThresholdPx" to correctionThresholdPx,
                "afterTop" to top
            )
        )
    }

    private data class LayoutSignature(
        val rootWidth: Int,
        val panelWidth: Int,
        val panelLayoutWidth: Int,
        val contentWidth: Int,
        val keyboardContentHeight: Int,
        val screenWidthDp: Int,
        val screenHeightDp: Int,
        val orientation: Int,
        val densityDpi: Int,
        val fontScale: Float,
        val scale: Float,
        val bottomPaddingDp: Int,
        val layoutSource: String,
        val floatingEnabled: Boolean,
        val floatingActive: Boolean,
        val dockedAlignment: DockedTabletAlignment,
        val mainVisible: Boolean,
        val aiVisible: Boolean,
        val recordingVisible: Boolean,
        val numpadVisible: Boolean
    )
}
