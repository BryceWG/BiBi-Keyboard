package com.brycewg.asrkb.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.util.TypedValue
import com.brycewg.asrkb.UiColorTokens
import com.brycewg.asrkb.UiColors

/**
 * 自定义标点按键视图：
 * - 居中绘制主符号（考虑字形边界进行精确水平/垂直居中），避免全角标点看起来偏移
 * - 顶部小字绘制次符号，便于提示“上滑”动作
 */
class PunctKeyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var primaryText: String = ""
    private var secondaryText: String = ""

    private val primaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT // 我们自行做边界校正
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
    }

    private val primaryBounds = Rect()
    private val secondaryBounds = Rect()

    fun setTexts(primary: String, secondary: String) {
        if (primaryText == primary && secondaryText == secondary) return
        primaryText = primary
        secondaryText = secondary
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 取色：键盘按键前景色
        val fg = try { UiColors.get(context, UiColorTokens.kbdKeyFg) } catch (_: Throwable) { 0xFF222222.toInt() }
        primaryPaint.color = fg
        secondaryPaint.color = fg

        // 动态字号：主符号尽量大但不裁剪；次符号小号
        val h = height.toFloat().coerceAtLeast(1f)
        val w = width.toFloat().coerceAtLeast(1f)

        // 主字号基线：40dp 高度时约 18sp；按高度同比例缩放
        val primaryTextSizePx = (h * 0.45f).coerceAtMost(sp(20f))
        val secondaryTextSizePx = (h * 0.22f).coerceAtMost(sp(12f))
        primaryPaint.textSize = primaryTextSizePx
        secondaryPaint.textSize = secondaryTextSizePx

        // 计算主符号边界并进行中心校正（使用字形边界而非 advance 宽度）
        val cx = w / 2f
        val cy = h / 2f

        val prim = primaryText
        if (prim.isNotEmpty()) {
            primaryPaint.getTextBounds(prim, 0, prim.length, primaryBounds)
            val fm = primaryPaint.fontMetrics
            // 将字形边界中心对齐到视图中心
            val textCenterXOffset = (primaryBounds.left + primaryBounds.right) / 2f
            val textCenterYOffset = (fm.ascent + fm.descent) / 2f
            val drawX = cx - textCenterXOffset
            // 轻微下移主符号，改善视觉平衡（按高度的 4% 并限制在 1-3dp）
            val shift = (h * 0.05f).coerceIn(dp(1f), dp(3f))
            val drawY = cy - textCenterYOffset + shift
            canvas.drawText(prim, drawX, drawY, primaryPaint)
        }

        val sec = secondaryText
        if (sec.isNotEmpty()) {
            secondaryPaint.getTextBounds(sec, 0, sec.length, secondaryBounds)
            val topMargin = dp(2f)
            val fm2 = secondaryPaint.fontMetrics
            val y = topMargin - fm2.ascent // 顶部对齐：y 使文本顶部接近 topMargin
            val textCenterXOffset2 = (secondaryBounds.left + secondaryBounds.right) / 2f
            val x = cx - textCenterXOffset2
            canvas.drawText(sec, x, y, secondaryPaint)
        }
    }

    private fun dp(v: Float): Float {
        val d = resources.displayMetrics.density
        return v * d
    }

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
}
