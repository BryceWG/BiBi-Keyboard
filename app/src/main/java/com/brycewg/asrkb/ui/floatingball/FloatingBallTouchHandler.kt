package com.brycewg.asrkb.ui.floatingball

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowInsets
import android.os.Build
import com.brycewg.asrkb.store.Prefs

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
        fun onLongPressDragStart(initialRawX: Float, initialRawY: Float)
        fun onLongPressDragMove(rawX: Float, rawY: Float)
        fun onLongPressDragRelease(rawX: Float, rawY: Float)
        fun onMoveStarted()
        fun onMoveEnded()
        fun onDragCancelled()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = dp(4)
    private val directMoveSlop = maxOf(dp(8), ViewConfiguration.get(context).scaledTouchSlop)
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
    private var moveStarted = false
    private var directMoveEnabled = false
    private var activeMoveSlop = touchSlop

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
            val lp = viewManager.getLayoutParams() ?: return@OnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    handleActionDown(lp, e, isMoveMode())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    handleActionMove(v, lp, e)
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
        lp: WindowManager.LayoutParams,
        e: MotionEvent,
        isMoveMode: Boolean
    ) {
        moved = false
        isDragging = isMoveMode
        longActionFired = false
        dragSelecting = false
        moveStarted = false
        directMoveEnabled = prefs.floatingBallDirectDragEnabled
        activeMoveSlop = if (directMoveEnabled) directMoveSlop else touchSlop
        downX = e.rawX
        downY = e.rawY
        startX = lp.x
        startY = lp.y

        // 移动模式下不触发长按
        if (!isMoveMode && !longPressPosted) {
            try {
                handler.postDelayed(longPressRunnable, longPressTimeout)
                longPressPosted = true
                if (!directMoveEnabled) {
                    handler.postDelayed(longHoldMoveRunnable, DIRECT_MOVE_HOLD_TIMEOUT_MS)
                    longHoldMovePosted = true
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to post long press callback", e)
            }
        }
    }

    private fun handleActionMove(
        v: View,
        lp: WindowManager.LayoutParams,
        e: MotionEvent
    ): Boolean {
        val dx = (e.rawX - downX).toInt()
        val dy = (e.rawY - downY).toInt()
        val moveSlop = activeMoveSlop

        if (!moved && (kotlin.math.abs(dx) > moveSlop || kotlin.math.abs(dy) > moveSlop)) {
            moved = true
        }

        if (!isDragging && directMoveEnabled && moved && !longActionFired && !dragSelecting) {
            isDragging = true
            cancelLongPress()
            cancelLongHoldMove()
        }

        if (!isDragging) {
            // 非移动模式：处理长按后的拖拽选中逻辑
            if (longActionFired) {
                val absDx = kotlin.math.abs(dx)
                val absDy = kotlin.math.abs(dy)
                if (!dragSelecting) {
                    // 要求初次“向左右方向”滑动超过阈值才进入拖拽选中
                    if (absDx > touchSlop && absDx >= absDy) {
                        dragSelecting = true
                        cancelLongHoldMove()
                        listener.onLongPressDragStart(e.rawX, e.rawY)
                    }
                } else {
                    listener.onLongPressDragMove(e.rawX, e.rawY)
                }
                return true
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
        val (screenW, screenH) = getUsableScreenSize()
        val root = viewManager.getBallView() ?: v
        val vw = if (root.width > 0) root.width else lp.width
        val vh = if (root.height > 0) root.height else lp.height
        val nx = (startX + dx).coerceIn(0, screenW - vw)
        val ny = (startY + dy).coerceIn(0, screenH - vh)
        lp.x = nx
        lp.y = ny
        viewManager.updateViewLayout(viewManager.getBallView() ?: v, lp)
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
            // 已触发长按但未进入拖拽选择：通知取消以清理可见性保护
            listener.onDragCancelled()
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
        return true
    }

    private fun handleActionCancel(): Boolean {
        cancelLongPress()
        cancelLongHoldMove()
        if (isDragging) {
            listener.onMoveEnded()
        } else if (dragSelecting) {
            listener.onDragCancelled()
        }
        moved = false
        isDragging = false
        longActionFired = false
        dragSelecting = false
        moveStarted = false
        return true
    }

    private fun hapticFeedback() {
        try {
            if (prefs.micHapticEnabled) {
                viewManager.getBallView()?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to perform haptic feedback", e)
        }
    }

    private fun dp(v: Int): Int {
        val d = context.resources.displayMetrics.density
        return (v * d + 0.5f).toInt()
    }

    /**
     * 与 ViewManager 保持一致：获取可用屏幕宽高，排除系统栏/切口区域，避免 Y 轴越界。
     */
    private fun getUsableScreenSize(): Pair<Int, Int> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager.currentWindowMetrics
                val bounds = metrics.bounds
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val w = (bounds.width() - insets.left - insets.right).coerceAtLeast(0)
                val h = (bounds.height() - insets.top - insets.bottom).coerceAtLeast(0)
                w to h
            } else {
                val dm = context.resources.displayMetrics
                dm.widthPixels to dm.heightPixels
            }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "Failed to get usable screen size, fallback to displayMetrics", e)
            val dm = context.resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }
}
