/**
 * 悬浮球录音时的全屏透明流光波形。
 *
 * 波形画在靠近屏幕底部、略小于全屏的区域内。录音时是色带，
 * 转录等待时带着波动余韵收成流动的柔光球体；消失从当前形态接着收拢，不等变形结束。
 *
 * 归属模块：ui/floatingball
 */
package com.brycewg.asrkb.ui.floatingball

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import androidx.annotation.ArrayRes
import androidx.core.graphics.ColorUtils
import com.brycewg.asrkb.R
import com.brycewg.asrkb.ui.BibiViewTheme
import com.brycewg.asrkb.ui.BibiViewThemes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * 叠在屏幕上的录音流光。开始时自下浮起并横向展开，录音中随音量起伏，
 * 转录时从色带收成会呼吸的球体，结束后从当前形态向中心收拢并淡出。
 */
internal class RecordingRibbonOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private class Ribbon(
        val colors: IntArray,
        val orbColors: IntArray,
        val freq1: Float,
        val freq2: Float,
        val speed1: Float,
        val speed2: Float,
        val phase: Float,
        val ampScale: Float,
        val thickness: Float,
        val yOffset: Float,
        val colorSpeed: Float,
        val alpha: Float
    ) {
        var shaderColors = colors
        val path = Path()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val matrix = Matrix()
        var shader: LinearGradient? = null
        val orbMatrix = Matrix()
        var orbShader = RadialGradient(0f, 0f, 1f, orbColors, null, Shader.TileMode.CLAMP)
            private set
        val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = orbShader
        }

        fun setOrbColors(colors: IntArray) {
            orbShader = RadialGradient(0f, 0f, 1f, colors, null, Shader.TileMode.CLAMP)
            orbShader.setLocalMatrix(orbMatrix)
            orbPaint.shader = orbShader
        }
    }

    private enum class Phase {
        Hidden,
        Intro,
        Live,
        Loading,
        Exit
    }

    private val ribbons = listOf(
        Ribbon(
            colors = colorArray(R.array.recording_ribbon_teal),
            orbColors = colorArray(R.array.recording_orb_teal),
            freq1 = 0.6f,
            freq2 = 1.3f,
            speed1 = 1.3f,
            speed2 = 2.1f,
            phase = 4.1f,
            ampScale = 0.65f,
            thickness = 0.07f,
            yOffset = 0f,
            colorSpeed = 0.06f,
            alpha = 0.78f
        ),
        Ribbon(
            colors = colorArray(R.array.recording_ribbon_warm),
            orbColors = colorArray(R.array.recording_orb_warm),
            freq1 = 0.8f,
            freq2 = 1.7f,
            speed1 = 1.6f,
            speed2 = 1.1f,
            phase = 0.9f,
            ampScale = 0.9f,
            thickness = 0.10f,
            yOffset = 0f,
            colorSpeed = 0.05f,
            alpha = 0.84f
        ),
        Ribbon(
            colors = colorArray(R.array.recording_ribbon_magenta),
            orbColors = colorArray(R.array.recording_orb_magenta),
            freq1 = 1.9f,
            freq2 = 0.7f,
            speed1 = 1.9f,
            speed2 = 2.6f,
            phase = 2.3f,
            ampScale = 0.7f,
            thickness = 0.07f,
            yOffset = 0f,
            colorSpeed = 0.08f,
            alpha = 0.74f
        ),
        Ribbon(
            colors = colorArray(R.array.recording_ribbon_base),
            orbColors = colorArray(R.array.recording_orb_body),
            freq1 = 1.1f,
            freq2 = 2.3f,
            speed1 = 2.2f,
            speed2 = 1.4f,
            phase = 0f,
            ampScale = 1.0f,
            thickness = 0.15f,
            yOffset = 0f,
            colorSpeed = 0.07f,
            alpha = 0.65f
        ),
        Ribbon(
            colors = colorArray(R.array.recording_ribbon_ice),
            orbColors = colorArray(R.array.recording_orb_ice),
            freq1 = 1.4f,
            freq2 = 2.9f,
            speed1 = 2.8f,
            speed2 = 1.9f,
            phase = 1.4f,
            ampScale = 0.8f,
            thickness = 0.08f,
            yOffset = 0f,
            colorSpeed = 0.10f,
            alpha = 0.80f
        ),
        Ribbon(
            colors = colorArray(R.array.recording_ribbon_core),
            orbColors = colorArray(R.array.recording_orb_core),
            freq1 = 1.1f,
            freq2 = 2.3f,
            speed1 = 2.2f,
            speed2 = 1.4f,
            phase = 0f,
            ampScale = 1.0f,
            thickness = 0.022f,
            yOffset = 0f,
            colorSpeed = 0.12f,
            alpha = 1.0f
        )
    )

    private var glowColors = colorArray(R.array.recording_ribbon_glow)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var orbGlowColors = colorArray(R.array.recording_orb_glow)
    private val orbGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbBody = ribbons[3]

    // 录音只叠半透明彩色色带；白色基底和高光仅在收成球体时渐入。
    private val colorLayers = listOf(ribbons[0], ribbons[1], ribbons[2], ribbons[4])
    private val shapeLayers = listOf(orbBody) + colorLayers
    private val orbLayers = shapeLayers + ribbons[5]
    private val darkRibbonFilter = ColorMatrixColorFilter(
        ColorMatrix().apply {
            setScale(DARK_RIBBON_BRIGHTNESS, DARK_RIBBON_BRIGHTNESS, DARK_RIBBON_BRIGHTNESS, 1f)
        }
    )
    private val xs = FloatArray(SAMPLE_COUNT)
    private val topXs = FloatArray(SAMPLE_COUNT)
    private val topYs = FloatArray(SAMPLE_COUNT)
    private val botXs = FloatArray(SAMPLE_COUNT)
    private val botYs = FloatArray(SAMPLE_COUNT)
    private val density = resources.displayMetrics.density

    private var reveal = 0f
    private var energy = 0f
    private var fade = 0f
    private var loadMorph = 0f
    private var phase = Phase.Hidden

    @Volatile
    private var targetLevel = 0f
    private var level = 0f
    private var time = 0.0
    private var lastFrameNs = 0L
    private var running = false
    private var transition: ValueAnimator? = null
    private var exitListener: (() -> Unit)? = null
    private var shaderWidth = 0f
    private var shaderHeight = 0f
    private var darkTheme = false
    private var materialPalette: RecordingAnimationPalette? = null

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        applyBlur()
    }

    fun setTheme(theme: BibiViewTheme) {
        val isDark = theme.isDark
        val palette = if (theme.isMiuix) {
            null
        } else {
            RecordingAnimationPalette.material(BibiViewThemes.materialColorScheme(context, isDark), isDark)
        }
        if (darkTheme == isDark && materialPalette == palette) return
        darkTheme = isDark
        materialPalette = palette
        val ribbonFilter = if (isDark) darkRibbonFilter else null
        // 只压低录音色带的 RGB，保留 alpha 和球体彩色光团的原有混合。
        for (ribbon in colorLayers) ribbon.paint.colorFilter = ribbonFilter
        glowPaint.colorFilter = ribbonFilter
        if (palette == null) {
            restoreFixedPalette(isDark)
        } else {
            applyMaterialPalette(palette)
        }
        // 让下一帧按现有尺寸重建渐变，不重置动画进度或形态。
        shaderWidth = 0f
        shaderHeight = 0f
        invalidate()
    }

    private fun restoreFixedPalette(isDark: Boolean) {
        for (ribbon in colorLayers) {
            ribbon.shaderColors = ribbon.colors
            ribbon.setOrbColors(ribbon.orbColors)
        }
        glowColors = colorArray(R.array.recording_ribbon_glow)
        orbBody.setOrbColors(colorArray(if (isDark) R.array.recording_orb_body_dark else R.array.recording_orb_body))
        ribbons[5].setOrbColors(colorArray(if (isDark) R.array.recording_orb_core_dark else R.array.recording_orb_core))
        orbGlowColors = colorArray(if (isDark) R.array.recording_orb_glow_dark else R.array.recording_orb_glow)
    }

    private fun applyMaterialPalette(palette: RecordingAnimationPalette) {
        for ((index, ribbon) in colorLayers.withIndex()) {
            val colors = palette.ribbons[index]
            ribbon.shaderColors = colors.toIntArray()
            // 沿用光团各渐变点的 alpha，只替换 RGB，保留原有融合与消散方式。
            ribbon.setOrbColors(
                IntArray(ribbon.orbColors.size) { stop ->
                    ColorUtils.setAlphaComponent(colors[stop], Color.alpha(ribbon.orbColors[stop]))
                }
            )
        }
        orbBody.setOrbColors(palette.body.toIntArray())
        ribbons[5].setOrbColors(palette.core.toIntArray())
        glowColors = palette.ribbonGlow.toIntArray()
        orbGlowColors = palette.orbGlow.toIntArray()
    }

    fun beginRecording() {
        if (phase == Phase.Intro || phase == Phase.Live) return
        phase = Phase.Intro
        exitListener = null
        visibility = VISIBLE
        startMotion()
    }

    fun beginLoading() {
        if (phase == Phase.Loading) return
        phase = Phase.Loading
        targetLevel = 0f
        exitListener = null
        visibility = VISIBLE
        startLoadMorph()
    }

    fun updateLevel(level: Float) {
        if (phase != Phase.Intro && phase != Phase.Live) return
        targetLevel = level.coerceIn(0f, 1f)
    }

    fun playExit(onEnd: () -> Unit) {
        if (phase == Phase.Exit) return
        if (phase == Phase.Hidden || (reveal <= 0.001f && fade <= 0.001f)) {
            onEnd()
            return
        }
        phase = Phase.Exit
        exitListener = onEnd
        targetLevel = 0f
        // 停在当前变形进度上再收拢，转录很快返回时也不会跳形态。
        cancelTransition()
        val revealStart = reveal
        val energyStart = energy
        val fadeStart = fade
        var canceled = false
        startLoop()
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = EXIT_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                energy = lerp(energyStart, 0f, FLATTEN.getInterpolation(seg(progress, 0f, 0.45f)))
                reveal = lerp(revealStart, 0f, COLLAPSE.getInterpolation(seg(progress, 0.25f, 1f)))
                fade = lerp(fadeStart, 0f, seg(progress, 0.65f, 1f))
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (canceled || transition !== animation) return
                    running = false
                    transition = null
                    val callback = exitListener
                    exitListener = null
                    callback?.invoke()
                }
            })
            start()
        }
    }

    fun cancelMotion() {
        exitListener = null
        cancelTransition()
        running = false
        phase = Phase.Hidden
        reveal = 0f
        energy = 0f
        fade = 0f
        loadMorph = 0f
        targetLevel = 0f
        level = 0f
        visibility = INVISIBLE
    }

    override fun onDetachedFromWindow() {
        exitListener = null
        cancelTransition()
        running = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 0f else ((now - lastFrameNs) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameNs = now
        time += dt

        val response = if (targetLevel > level) LEVEL_ATTACK else LEVEL_RELEASE
        level += (targetLevel - level) * (1f - exp(-response * dt))

        val wave = waveRect()
        if (wave.width > 1f && wave.height > 1f && reveal > 0.001f && fade > 0.001f) {
            canvas.save()
            canvas.translate(wave.left, wave.top)
            drawWaves(canvas, wave.width, wave.height)
            canvas.restore()
        }
        if (running) postInvalidateOnAnimation()
    }

    private fun startMotion() {
        cancelTransition()
        targetLevel = 0f
        level = 0f
        val revealStart = reveal
        val energyStart = energy
        val fadeStart = fade
        val morphStart = loadMorph
        startLoop()
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = INTRO_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                reveal = lerp(revealStart, 1f, EXPAND.getInterpolation(seg(progress, 0f, 0.55f)))
                fade = lerp(fadeStart, 1f, seg(progress, 0f, 0.25f))
                energy = lerp(energyStart, 1f, OVERSHOOT.getInterpolation(seg(progress, 0.15f, 1f)))
                loadMorph = lerp(morphStart, 0f, MORPH.getInterpolation(progress))
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false

                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (canceled || transition !== animation) return
                    transition = null
                    if (phase == Phase.Intro) phase = Phase.Live
                }
            })
            start()
        }
    }

    private fun startLoadMorph() {
        cancelTransition()
        val morphStart = loadMorph
        val revealStart = reveal
        val energyStart = energy
        val fadeStart = fade
        val expand = revealStart < 0.98f || fadeStart < 0.98f
        startLoop()
        transition = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (expand) INTRO_MS else LOAD_MORPH_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val progress = animator.animatedValue as Float
                loadMorph = lerp(morphStart, 1f, MORPH.getInterpolation(progress))
                if (!expand) return@addUpdateListener
                reveal = lerp(revealStart, 1f, EXPAND.getInterpolation(seg(progress, 0f, 0.55f)))
                fade = lerp(fadeStart, 1f, seg(progress, 0f, 0.25f))
                energy = lerp(energyStart, 1f, OVERSHOOT.getInterpolation(seg(progress, 0.15f, 1f)))
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false

                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (canceled || transition !== animation) return
                    transition = null
                }
            })
            start()
        }
    }

    private fun drawWaves(canvas: Canvas, waveWidth: Float, waveHeight: Float) {
        ensureShaders(waveWidth, waveHeight)

        val midX = waveWidth / 2f
        val rise = (1f - reveal) * waveHeight * RISE_FRACTION
        val midY = waveHeight / 2f + rise
        val span = waveWidth * reveal
        val left = midX - span / 2f
        val waveTime = time * WAVE_SPEED_SCALE
        val breathe = BREATHE_FLOOR + BREATHE_SWING * sin(waveTime * BREATHE_SPEED).toFloat()
        val amp = energy * (breathe + (1f - breathe) * level)
        val maxAmp = waveHeight * MAX_AMP_FRACTION
        val morph = loadMorph.coerceIn(0f, 1f)
        // 横向收拢与纵向饱满同步，保留逐渐衰减的波动，不先压平成静止的胶囊。
        val shapeBulge = smootherstep(0.04f, MORPH_BULGE_END, morph)
        val shapeTuck = horizontalMorph(morph)
        val calm = smootherstep(0.08f, MORPH_CALM_END, morph)
        val orbBlend = smootherstep(0.12f, 0.92f, morph)
        val ballBreathe = orbBreathe(time)
        val ballRadius = waveHeight * BALL_RADIUS_FRACTION * reveal * ballBreathe
        val settled = smootherstep(0.25f, 1f, morph)
        val floatY = ballRadius * sin(time * ORB_FLOAT_SPEED).toFloat() * ORB_FLOAT_AMOUNT * settled
        val ballScale = (BALL_RADIUS_FRACTION * 2f * reveal * ballBreathe).coerceAtLeast(0.01f)
        val glowScaleX = lerp((span / waveHeight).coerceAtLeast(0.01f), ballScale, shapeTuck)
        val glowScaleY = lerp(GLOW_SCALE_MIN + GLOW_SCALE_SPAN * amp, ballScale, shapeBulge)

        val glowAlpha = lerp(GLOW_ALPHA_FLOOR + GLOW_ALPHA_SPAN * amp, ORB_GLOW_ALPHA, orbBlend)
        glowPaint.alpha = (fade * glowAlpha * (1f - orbBlend) * 255f).toInt().coerceIn(0, 255)
        orbGlowPaint.alpha = (fade * glowAlpha * orbBlend * 255f).toInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(0f, rise + floatY)
        canvas.scale(glowScaleX, glowScaleY, midX, waveHeight / 2f)
        if (orbBlend < 1f) canvas.drawCircle(midX, waveHeight / 2f, waveHeight / 2f, glowPaint)
        if (orbBlend > 0f) canvas.drawCircle(midX, waveHeight / 2f, waveHeight / 2f, orbGlowPaint)
        canvas.restore()

        for (index in 0 until SAMPLE_COUNT) {
            xs[index] = left + span * index / (SAMPLE_COUNT - 1)
        }

        val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        // 不用恒定角速度。摇摆只在接近成球后轻轻加上，变形前半段上下边保持对齐，避免自交。
        val drift = orbDrift(time) * smootherstep(0.7f, 1f, morph).toDouble()
        val wobbleWeight = smootherstep(0.32f, 1f, morph)
        val curveRoundness = smootherstep(0.22f, 1f, morph)
        for (ribbon in shapeLayers) {
            if (orbBlend == 1f && ribbon !== orbBody) continue
            val radiusScale = (BALL_RADIUS_MIN + ribbon.thickness / MAIN_THICKNESS * (1f - BALL_RADIUS_MIN))
                .coerceIn(BALL_RADIUS_MIN, 1.05f)
            val radius = ballRadius * radiusScale
            val layerMotion = if (ribbon === orbBody) 0f else ORB_LAYER_FLOAT
            val centerX = midX + radius * sin(time * ORB_LAYER_SPEED + ribbon.phase).toFloat() * layerMotion
            val centerY = midY + floatY +
                radius * cos(time * ORB_LAYER_SPEED * 0.83 + ribbon.phase).toFloat() * layerMotion
            val stretch = 1f + ORB_STRETCH * sin(time * ORB_STRETCH_SPEED + ribbon.phase).toFloat() * wobbleWeight
            for (index in 0 until SAMPLE_COUNT) {
                val u = index.toFloat() / (SAMPLE_COUNT - 1)
                val globalX = xs[index] / waveWidth
                val env = envelope(u)
                val wave = WAVE_PRIMARY * sin(globalX * ribbon.freq1 * TAU + waveTime * ribbon.speed1 + ribbon.phase) +
                    WAVE_SECONDARY * sin(globalX * ribbon.freq2 * TAU - waveTime * ribbon.speed2 + ribbon.phase * 1.7)
                val pulse = PULSE_FLOOR + PULSE_SWING * sin(globalX * PULSE_FREQ + waveTime * PULSE_SPEED + ribbon.phase)
                val pulseCalm = lerp(pulse.toFloat(), 1f, calm)
                val ribbonY = midY + ribbon.yOffset * waveHeight * energy * (1f - shapeBulge) +
                    (wave * maxAmp * ribbon.ampScale * env * amp * (1f - calm)).toFloat()
                val half = (
                    ribbon.thickness * waveHeight * env * (THICKNESS_FLOOR + THICKNESS_SPAN * amp) * pulseCalm
                    ).toFloat().coerceAtLeast(MIN_HALF_THICKNESS_PX)
                val yMorph = verticalMorph(morph, u, shapeBulge)
                val angleTop = PI * (1.0 - u) + drift
                val angleBot = -PI * (1.0 - u) + drift
                val topRadius = radius * radialWobble(angleTop, time, ribbon.phase, wobbleWeight)
                val botRadius = radius * radialWobble(angleBot, time, ribbon.phase, wobbleWeight)
                topXs[index] = lerp(xs[index], centerX + cos(angleTop).toFloat() * topRadius * stretch, shapeTuck)
                topYs[index] = lerp(ribbonY - half, centerY - sin(angleTop).toFloat() * topRadius / stretch, yMorph)
                botXs[index] = lerp(xs[index], centerX + cos(angleBot).toFloat() * botRadius * stretch, shapeTuck)
                botYs[index] = lerp(ribbonY + half, centerY - sin(angleBot).toFloat() * botRadius / stretch, yMorph)
            }

            buildEdgePath(ribbon.path, curveRoundness)
            if (orbBlend < 1f && ribbon !== orbBody) {
                ribbon.matrix.setTranslate(((time * ribbon.colorSpeed * waveWidth) % (2.0 * waveWidth)).toFloat(), 0f)
                ribbon.shader?.setLocalMatrix(ribbon.matrix)
                val alpha = (ribbon.alpha * fade * (1f - orbBlend) * 255f).toInt().coerceIn(0, 255)
                drawRibbonLayer(canvas, ribbon.path, ribbon.paint, alpha, canBlur, midX, midY)
            }
        }
        if (orbBlend > 0f) {
            drawOrbLayers(canvas, midX, midY + floatY, ballRadius, span, orbBlend, canBlur)
        }
    }

    private fun drawOrbLayers(
        canvas: Canvas,
        midX: Float,
        midY: Float,
        radius: Float,
        span: Float,
        blend: Float,
        canBlur: Boolean
    ) {
        for (ribbon in orbLayers) {
            val isBody = ribbon === orbBody
            val travel = time * ORB_COLOR_SPEED + ribbon.phase
            val offsetX = if (isBody) -0.2f else cos(travel).toFloat() * ORB_COLOR_ORBIT
            val offsetY = if (isBody) -0.25f else sin(travel * 0.91 + ribbon.phase).toFloat() * ORB_COLOR_ORBIT
            val glowScale = when (ribbon) {
                orbBody -> ORB_BODY_GLOW_RADIUS
                ribbons[5] -> ORB_CORE_GLOW_RADIUS
                else -> ORB_PATCH_GLOW_RADIUS
            }
            val glowRadius = radius * glowScale
            val stretch = if (isBody) 1f else 1f + ORB_COLOR_STRETCH * sin(travel * 1.3).toFloat()
            // 渐变也随色带一起收拢；先拉成长条再变圆，避免变形中途突然只亮中间。
            val gradientWidth = lerp(maxOf(span * 0.65f, glowRadius), glowRadius, blend)
            ribbon.orbMatrix.setScale(gradientWidth * stretch, glowRadius / stretch)
            ribbon.orbMatrix.postTranslate(midX + offsetX * radius, midY + offsetY * radius)
            ribbon.orbShader.setLocalMatrix(ribbon.orbMatrix)
            val alpha = (fade * blend * 255f).toInt().coerceIn(0, 255)
            // 所有光团共用外轮廓，由渐变自然消散，避免内层小圆把仍明亮的颜色截断。
            drawRibbonLayer(canvas, orbBody.path, ribbon.orbPaint, alpha, canBlur, midX, midY)
        }
    }

    private fun drawRibbonLayer(
        canvas: Canvas,
        path: Path,
        paint: Paint,
        alpha: Int,
        canBlur: Boolean,
        midX: Float,
        midY: Float
    ) {
        if (!canBlur) {
            paint.alpha = alpha / HALO_ALPHA_DIVISOR
            canvas.save()
            canvas.scale(HALO_SCALE, HALO_SCALE, midX, midY)
            canvas.drawPath(path, paint)
            canvas.restore()
        }
        paint.alpha = alpha
        canvas.drawPath(path, paint)
    }

    /**
     * 闭合样条穿过采样点。录音色带用更紧的切线，接近成球后放到圆周常用的 1/6，
     * 避免 quadTo 把上一点当控制点时在圆上切出棱角。
     */
    private fun buildEdgePath(path: Path, roundness: Float) {
        val last = SAMPLE_COUNT - 1
        val tension = lerp(RIBBON_CURVE_TENSION, CIRCLE_CURVE_TENSION, roundness.coerceIn(0f, 1f))
        path.rewind()
        path.moveTo(edgeX(0, last), edgeY(0, last))
        val count = last * 2
        for (index in 0 until count) {
            val x0 = edgeX(index - 1, last)
            val y0 = edgeY(index - 1, last)
            val x1 = edgeX(index, last)
            val y1 = edgeY(index, last)
            val x2 = edgeX(index + 1, last)
            val y2 = edgeY(index + 1, last)
            val x3 = edgeX(index + 2, last)
            val y3 = edgeY(index + 2, last)
            path.cubicTo(
                x1 + (x2 - x0) * tension,
                y1 + (y2 - y0) * tension,
                x2 - (x3 - x1) * tension,
                y2 - (y3 - y1) * tension,
                x2,
                y2
            )
        }
        path.close()
    }

    private fun edgeX(index: Int, last: Int): Float {
        val wrapped = wrapIndex(index, last * 2)
        return if (wrapped <= last) topXs[wrapped] else botXs[last - (wrapped - last)]
    }

    private fun edgeY(index: Int, last: Int): Float {
        val wrapped = wrapIndex(index, last * 2)
        return if (wrapped <= last) topYs[wrapped] else botYs[last - (wrapped - last)]
    }

    private fun wrapIndex(index: Int, count: Int): Int {
        val mod = index % count
        return if (mod < 0) mod + count else mod
    }

    private fun ensureShaders(waveWidth: Float, waveHeight: Float) {
        if (shaderWidth == waveWidth && shaderHeight == waveHeight) return
        shaderWidth = waveWidth
        shaderHeight = waveHeight
        for (ribbon in ribbons) {
            ribbon.shader = LinearGradient(
                0f,
                0f,
                waveWidth,
                0f,
                ribbon.shaderColors,
                null,
                Shader.TileMode.MIRROR
            ).also { ribbon.paint.shader = it }
        }
        glowPaint.shader = RadialGradient(
            waveWidth / 2f,
            waveHeight / 2f,
            waveHeight / 2f,
            glowColors,
            floatArrayOf(0f, GLOW_MID_STOP, 1f),
            Shader.TileMode.CLAMP
        )
        orbGlowPaint.shader = RadialGradient(
            waveWidth / 2f,
            waveHeight / 2f,
            waveHeight / 2f,
            orbGlowColors,
            floatArrayOf(0f, GLOW_MID_STOP, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun cancelTransition() {
        val animator = transition ?: return
        transition = null
        animator.cancel()
    }

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameNs = System.nanoTime()
        postInvalidateOnAnimation()
    }

    private fun applyBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val radius = dp(BLUR_RADIUS_DP)
        setRenderEffect(
            RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL)
        )
    }

    private fun waveRect(): WaveRect {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val waveWidth = viewWidth * WIDTH_FRACTION
        val waveHeight = viewHeight * WAVE_HEIGHT_FRACTION
        val left = (viewWidth - waveWidth) / 2f
        val bottomInset = maxOf(dp(BOTTOM_INSET_DP), viewHeight * BOTTOM_INSET_FRACTION)
        val top = (viewHeight - bottomInset - waveHeight).coerceAtLeast(0f)
        return WaveRect(left, top, waveWidth, waveHeight)
    }

    private fun colorArray(@ArrayRes id: Int): IntArray {
        val typed = resources.obtainTypedArray(id)
        return try {
            IntArray(typed.length()) { index -> typed.getColor(index, Color.TRANSPARENT) }
        } finally {
            typed.recycle()
        }
    }

    private fun envelope(u: Float): Float = sin(PI * u).toFloat().coerceAtLeast(0f).pow(ENVELOPE_POWER)

    private fun verticalMorph(morph: Float, u: Float, bulge: Float): Float {
        val center = sin(PI * u).toFloat()
        val lead = sin(PI * morph).toFloat() * center * MORPH_CENTER_LEAD
        return (bulge + lead * (1f - bulge)).coerceIn(0f, 1f)
    }

    private fun horizontalMorph(morph: Float): Float = smootherstep(MORPH_TUCK_START, 1f, morph)

    private fun smootherstep(start: Float, end: Float, value: Float): Float {
        val amount = ((value - start) / (end - start)).coerceIn(0f, 1f)
        return amount * amount * amount * (amount * (amount * 6f - 15f) + 10f)
    }

    private fun orbBreathe(time: Double): Float {
        val primary = sin(time * BALL_BREATHE_SPEED)
        val secondary = sin(time * BALL_BREATHE_SLOW + 1.2)
        return (1.0 + BALL_BREATHE_AMP * (primary * 0.78 + secondary * 0.22)).toFloat()
    }

    private fun orbDrift(time: Double): Double = sin(time * ORB_DRIFT_A_SPEED) * ORB_DRIFT_A + sin(time * ORB_DRIFT_B_SPEED + 0.6) * ORB_DRIFT_B

    private fun radialWobble(angle: Double, time: Double, phase: Float, weight: Float): Float {
        val travel = wobblePhase(time) + phase * BALL_WOBBLE_PHASE
        val shifted = angle + travel
        val shape = sin(shifted * 2.0) * 0.66 + sin(shifted * 3.0 - travel * 0.4 + 1.1) * 0.34
        return (1.0 + BALL_WOBBLE_AMP * shape * weight.toDouble()).toFloat()
    }

    private fun wobblePhase(time: Double): Double = BALL_WOBBLE_TRAVEL * time - (BALL_WOBBLE_VAR / BALL_WOBBLE_VAR_SPEED) * cos(time * BALL_WOBBLE_VAR_SPEED)

    private fun seg(progress: Float, start: Float, end: Float): Float = ((progress - start) / (end - start)).coerceIn(0f, 1f)

    private fun lerp(start: Float, end: Float, fraction: Float): Float = start + (end - start) * fraction

    private fun dp(value: Float): Float = value * density

    private data class WaveRect(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float
    )

    companion object {
        private const val TAU = 2.0 * PI
        private const val SAMPLE_COUNT = 64
        private const val INTRO_MS = 720L
        private const val LOAD_MORPH_MS = 1100L
        private const val EXIT_MS = 500L
        private const val BLUR_RADIUS_DP = 3f
        private const val WIDTH_FRACTION = 0.84f
        private const val WAVE_HEIGHT_FRACTION = 0.34f
        private const val BOTTOM_INSET_FRACTION = 0.06f
        private const val BOTTOM_INSET_DP = 64f
        private const val RISE_FRACTION = 0.25f
        private const val MAX_AMP_FRACTION = 0.224f
        private const val BREATHE_FLOOR = 0.16f
        private const val BREATHE_SWING = 0.05f

        // 横向波速提高后按同样比例收回，空闲起伏的周期保持不变。
        private const val BREATHE_SPEED = 1.725
        private const val LEVEL_ATTACK = 28f
        private const val LEVEL_RELEASE = 8f
        private const val WAVE_SPEED_SCALE = 1.6
        private const val DARK_RIBBON_BRIGHTNESS = 0.82f
        private const val WAVE_PRIMARY = 0.62
        private const val WAVE_SECONDARY = 0.38
        private const val PULSE_FLOOR = 0.78
        private const val PULSE_SWING = 0.22
        private const val PULSE_FREQ = 4.0
        private const val PULSE_SPEED = 1.6
        private const val THICKNESS_FLOOR = 0.3f
        private const val THICKNESS_SPAN = 0.7f
        private const val MIN_HALF_THICKNESS_PX = 0.5f
        private const val GLOW_ALPHA_FLOOR = 0.35f
        private const val GLOW_ALPHA_SPAN = 0.45f
        private const val GLOW_SCALE_MIN = 0.55f
        private const val GLOW_SCALE_SPAN = 0.45f
        private const val GLOW_MID_STOP = 0.55f
        private const val HALO_SCALE = 1.12f
        private const val HALO_ALPHA_DIVISOR = 4
        private const val BALL_RADIUS_FRACTION = 0.16f
        private const val BALL_RADIUS_MIN = 0.42f
        private const val MAIN_THICKNESS = 0.15f
        private const val BALL_BREATHE_AMP = 0.065
        private const val BALL_BREATHE_SPEED = 1.45
        private const val BALL_BREATHE_SLOW = 0.43
        private const val BALL_WOBBLE_AMP = 0.055
        private const val BALL_WOBBLE_TRAVEL = 0.72
        private const val BALL_WOBBLE_VAR = 0.20
        private const val BALL_WOBBLE_VAR_SPEED = 0.33
        private const val BALL_WOBBLE_PHASE = 0.35
        private const val ORB_DRIFT_A = 0.14
        private const val ORB_DRIFT_A_SPEED = 0.25
        private const val ORB_DRIFT_B = 0.05
        private const val ORB_DRIFT_B_SPEED = 0.47
        private const val ORB_FLOAT_SPEED = 1.12
        private const val ORB_FLOAT_AMOUNT = 0.08f
        private const val ORB_LAYER_FLOAT = 0.18f
        private const val ORB_LAYER_SPEED = 0.63
        private const val ORB_STRETCH = 0.045f
        private const val ORB_STRETCH_SPEED = 1.27
        private const val ORB_COLOR_SPEED = 0.58
        private const val ORB_COLOR_ORBIT = 0.48f
        private const val ORB_COLOR_STRETCH = 0.12f
        private const val ORB_BODY_GLOW_RADIUS = 1.75f
        private const val ORB_PATCH_GLOW_RADIUS = 1.05f
        private const val ORB_CORE_GLOW_RADIUS = 0.66f
        private const val ORB_GLOW_ALPHA = 0.58f
        private const val MORPH_BULGE_END = 0.98f
        private const val MORPH_TUCK_START = 0f
        private const val MORPH_CENTER_LEAD = 0.12f
        private const val MORPH_CALM_END = 0.94f
        private const val RIBBON_CURVE_TENSION = 0.10f
        private const val CIRCLE_CURVE_TENSION = 1f / 6f
        private const val ENVELOPE_POWER = 1.6f

        private val EXPAND = PathInterpolator(0.2f, 0f, 0f, 1f)
        private val OVERSHOOT = OvershootInterpolator(2.4f)
        private val FLATTEN = PathInterpolator(0.4f, 0f, 1f, 1f)
        private val COLLAPSE = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
        private val MORPH = PathInterpolator(0.42f, 0f, 0.24f, 1f)
    }
}
