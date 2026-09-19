package com.brycewg.asrkb.ui.floatingball

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.brycewg.asrkb.store.Prefs
import com.brycewg.asrkb.util.HapticFeedbackHelper
import com.brycewg.asrkb.util.currentUsableWindowSize
import com.brycewg.asrkb.util.legacyUsableWindowSize

/**
 * 悬浮球触摸处理器
 * 封装复杂的触摸逻辑：拖动、长按、点击判定
 */
class FloatingBallTouchHandler(
    private val context: Context,
    private val prefs: Prefs,
    private val viewManager: FloatingBallViewManager,
    private val windowManager: WindowManager,
    private val listener: TouchEventListener
) {
    companion object {
        private const val TAG = "FloatingBallTouchHandler"
        private const val DIRECT_MOVE_HOLD_TIMEOUT_MS = 2000L
    }

    interface TouchEventListener {
        fun onSingleTap()
        fun onLongPress()
        fun onLongPressGestureMovedBeyondSlop()
        fun onLongPressRelease()
        fun onLongPressCancel()
        fun onLongPressDragStart(initialRawX: Float, initialRawY: Float)
        fun onLongPressDragMove(rawX: Float, rawY: Float)
        fun onLongPressDragRelease(rawX: Float, rawY: Float)
        fun onMoveStarted()
        fun onMoveEnded()
        fun onDragCancelled()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val scaledTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()

    // 触摸状态
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moved = false
    private var isDragging = false
    private var longActionFired = false
    private var longPressPosted = false
    private var dragSelecting = false
    private var longHoldMovePosted = false
    private var holdMovementExceededNotified = false
    private var moveStarted = false
    private var directMoveEnabled = false
    private var holdToRecordEnabled = false
    private var gestureSlops = resolveFloatingBallGestureSlops(
        density = context.resources.displayMetrics.density,
        scaledTouchSlop = scaledTouchSlop,
        holdToRecordEnabled = false,
        directMoveEnabled = false
    )
    private var dragScreenW = 0
    private var dragScreenH = 0

    private val longPressRunnable = Runnable {
        longPressPosted = false
        longActionFired = true
        hapticFeedback()
        listener.onLongPress()
    }

    private val longHoldMoveRunnable = Runnable {
        longHoldMovePosted = false
        if (isDragging || dragSelecting) return@Runnable
        isDragging = true
        moveStarted = true
        hapticFeedback()
        listener.onMoveStarted()
    }

    /** 创建触摸监听器 */
    fun createTouchListener(isMoveMode: () -> Boolean): View.OnTouchListener {
        return View.OnTouchListener { v, e ->
            viewManager.getLayoutParams() ?: return@OnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handleActionDown(e, isMoveMode())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    handleActionMove(v, e)
                }
                MotionEvent.ACTION_UP -> handleActionUp(v, e)
                MotionEvent.ACTION_CANCEL -> handleActionCancel()
                else -> false
            }
        }
    }

    /** 取消长按回调 */
    fun cancelLongPress() {
        if (longPressPosted) {
            try {
                handler.removeCallbacks(longPressRunnable)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to remove long press callback", e)
            }
            longPressPosted = false
        }
    }

    private fun cancelLongHoldMove() {
        if (longHoldMovePosted) {
            try {
                handler.removeCallbacks(longHoldMoveRunnable)
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to remove long hold move callback", e)
            }
            longHoldMovePosted = false
        }
    }

    /** 清理资源 */
    fun cleanup() {
        cancelLongPress()
        cancelLongHoldMove()
    }

    // ==================== 私有处理方法 ====================

    private fun handleActionDown(
        e: MotionEvent,
        isMoveMode: Boolean
    ) {
        moved = false
        isDragging = isMoveMode
        longActionFired = false
        dragSelecting = false
        moveStarted = false
        holdMovementExceededNotified = false
        directMoveEnabled = prefs.floatingBallDirectDragEnabled
        holdToRecordEnabled = prefs.floatingBallHoldToRecordEnabled
        gestureSlops = resolveFloatingBallGestureSlops(
            density = context.resources.displayMetrics.density,
            scaledTouchSlop = scaledTouchSlop,
            holdToRecordEnabled = holdToRecordEnabled,
            directMoveEnabled = directMoveEnabled
        )
        val screen = getUsableScreenSize()
        dragScreenW = screen.first
        dragScreenH = screen.second
        downX = e.rawX
        downY = e.rawY
        val logicalPosition = viewManager.getLogicalBallPositionSnapshot()
        startX = logicalPosition.first
        startY = logicalPosition.second

        // 移动模式下不触发长按
        if (!isMoveMode && !longPressPosted) {
            try {
                if (shouldStartFloatingHoldRecordingOnDown(
                        holdToRecordEnabled = holdToRecordEnabled,
                        isMoveMode = isMoveMode
                    )
                ) {
                    longActionFired = true
                    hapticFeedback()
                    listener.onLongPress()
                } else {
                    handler.postDelayed(longPressRunnable, longPressTimeout)
                    longPressPosted = true
                    if (shouldScheduleFloatingLongHoldMove(
                            holdToRecordEnabled = holdToRecordEnabled,
                            directMoveEnabled = directMoveEnabled
                        )
                    ) {
                        handler.postDelayed(longHoldMoveRunnable, DIRECT_MOVE_HOLD_TIMEOUT_MS)
                        longHoldMovePosted = true
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to post long press callback", e)
            }
        }
    }

    private fun handleActionMove(v: View, e: MotionEvent): Boolean {
        val dx = (e.rawX - downX).toInt()
        val dy = (e.rawY - downY).toInt()
        val moveSlop = gestureSlops.moveActivationPx
        val movementExceeded = exceedsFloatingBallMoveSlop(dx, dy, moveSlop)

        if (!moved && (kotlin.math.abs(dx) > moveSlop || kotlin.math.abs(dy) > moveSlop)) {
            moved = true
        }

        if (holdToRecordEnabled &&
            longActionFired &&
            movementExceeded &&
            !holdMovementExceededNotified
        ) {
            holdMovementExceededNotified = true
            listener.onLongPressGestureMovedBeyondSlop()
        }

        if (!isDragging &&
            directMoveEnabled &&
            !longActionFired &&
            !dragSelecting &&
            exceedsFloatingBallMoveSlop(dx, dy, gestureSlops.directMovePx)
        ) {
            isDragging = true
            cancelLongPress()
            cancelLongHoldMove()
        }

        if (!isDragging) {
            // 非移动模式：处理长按后的拖拽选中逻辑
            if (longActionFired) {
                if (dragSelecting) {
                    listener.onLongPressDragMove(e.rawX, e.rawY)
                    return true
                }

                val shouldOpenMenu = if (holdToRecordEnabled) {
                    shouldStartFloatingBallMenuTowardCenter(
                        dx = dx,
                        dy = dy,
                        slop = gestureSlops.menuSelectionPx,
                        downX = downX,
                        downY = downY,
                        screenWidth = dragScreenW,
                        screenHeight = dragScreenH
                    )
                } else {
                    shouldStartFloatingBallMenuSelection(
                        dx = dx,
                        dy = dy,
                        slop = gestureSlops.menuSelectionPx
                    )
                }
                val movingTowardScreenCenter = holdToRecordEnabled &&
                    isFloatingBallMovementTowardCenter(
                        dx = dx,
                        dy = dy,
                        downX = downX,
                        downY = downY,
                        screenWidth = dragScreenW,
                        screenHeight = dragScreenH
                    )
                val holdMoveAction = if (holdToRecordEnabled) {
                    resolveFloatingBallHoldMoveAction(
                        movementExceeded = movementExceeded,
                        menuThresholdExceeded = shouldOpenMenu,
                        movingTowardScreenCenter = movingTowardScreenCenter,
                        directMoveEnabled = directMoveEnabled
                    )
                } else if (shouldOpenMenu) {
                    FloatingBallHoldMoveAction.OpenMenu
                } else {
                    FloatingBallHoldMoveAction.None
                }

                when (holdMoveAction) {
                    FloatingBallHoldMoveAction.OpenMenu -> {
                        dragSelecting = true
                        cancelLongHoldMove()
                        listener.onLongPressDragStart(e.rawX, e.rawY)
                    }
                    FloatingBallHoldMoveAction.MoveBall -> {
                        isDragging = true
                        moveStarted = true
                        cancelLongPress()
                        cancelLongHoldMove()
                        listener.onMoveStarted()
                    }
                    FloatingBallHoldMoveAction.None -> Unit
                }
                if (!isDragging) return true
            } else {
                // 移动超过阈值，取消未触发的长按
                if (moved) {
                    if (longPressPosted) {
                        try {
                            handler.removeCallbacks(longPressRunnable)
                        } catch (ex: Throwable) {
                            Log.w(TAG, "Failed to remove long press callback", ex)
                        }
                        longPressPosted = false
                    }
                    cancelLongHoldMove()
                }
                return true
            }
        }

        // 拖动中：更新位置
        if (!moveStarted) {
            moveStarted = true
            listener.onMoveStarted()
        }
        val (screenW, screenH) = if (dragScreenW > 0 && dragScreenH > 0) {
            dragScreenW to dragScreenH
        } else {
            getUsableScreenSize()
        }
        val ballSidePx = viewManager.getLogicalBallSizeSnapshotPx()
        val vw = ballSidePx
        val vh = ballSidePx
        val maxX = (screenW - vw).coerceAtLeast(0)
        val maxY = (screenH - vh).coerceAtLeast(0)
        val nx = (startX + dx).coerceIn(0, maxX)
        val ny = (startY + dy).coerceIn(0, maxY)
        val currentLogicalPosition = viewManager.getLogicalBallPositionSnapshot()
        if (currentLogicalPosition.first == nx && currentLogicalPosition.second == ny) {
            return true
        }
        viewManager.updateLogicalBallPosition(v, nx, ny)
        return true
    }

    private fun handleActionUp(v: View, e: MotionEvent): Boolean {
        cancelLongPress()
        cancelLongHoldMove()

        if (dragSelecting) {
            // 拖拽选择释放
            listener.onLongPressDragRelease(e.rawX, e.rawY)
        } else if (isDragging) {
            val targetView = viewManager.getBallView() ?: v
            try {
                viewManager.animateSnapToEdge(targetView) {
                    listener.onMoveEnded()
                }
            } catch (ex: Throwable) {
                Log.e(TAG, "Failed to animate snap to edge, falling back to instant snap", ex)
                viewManager.snapToEdge(targetView)
                listener.onMoveEnded()
            }
        } else if (longActionFired) {
            listener.onLongPressRelease()
        } else if (!moved) {
            // 非移动模式的点按
            hapticFeedback()
            listener.onSingleTap()
        }

        moved = false
        isDragging = false
        longActionFired = false
        dragSelecting = false
        moveStarted = false
        holdMovementExceededNotified = false
        dragScreenW = 0
        dragScreenH = 0
        return true
    }

    private fun handleActionCancel(): Boolean {
        cancelLongPress()
        cancelLongHoldMove()
        if (isDragging) {
            listener.onMoveEnded()
        } else if (dragSelecting) {
            listener.onDragCancelled()
        } else if (longActionFired) {
            listener.onLongPressCancel()
        }
        moved = false
        isDragging = false
        longActionFired = false
        dragSelecting = false
        moveStarted = false
        holdMovementExceededNotified = false
        dragScreenW = 0
        dragScreenH = 0
        return true
    }

    private fun hapticFeedback() {
        HapticFeedbackHelper.performTap(context, prefs, viewManager.getBallView())
    }

    /**
     * 与 ViewManager 保持一致：获取可用屏幕宽高，排除系统栏/切口区域，避免 Y 轴越界。
     */
    private fun getUsableScreenSize(): Pair<Int, Int> = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val size = windowManager.currentUsableWindowSize()
            size.width to size.height
        } else {
            val size = windowManager.legacyUsableWindowSize()
            size.width to size.height
        }
    } catch (e: Throwable) {
        android.util.Log.w(
            TAG,
            "Failed to get usable screen size, fallback to displayMetrics",
            e
        )
        val dm = context.resources.displayMetrics
        dm.widthPixels to dm.heightPixels
    }
}
