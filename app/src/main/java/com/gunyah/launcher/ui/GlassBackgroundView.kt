package com.gunyah.launcher.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * 液态玻璃的「底」：渐变 + 几颗彩色光晕球缓慢流动 + 顶部柔光。
 * [light]=false 深色主题（深蓝紫底 + 亮光球）；true 浅色主题（浅蓝紫底 + 柔光球）。
 * 玻璃卡片半透明盖在上面，透出这些光就成了 Liquid Glass 质感。
 */
class GlassBackgroundView(ctx: Context, private val light: Boolean = false) : View(ctx) {

    private data class Orb(val color: Int, val rFrac: Float, val cxFrac: Float,
                           val cyFrac: Float, val ax: Float, val ay: Float,
                           val phase: Float, val speed: Float)

    // 蓝 / 紫 / 品红 / 青，低饱和高亮度；浅色主题降低透明度避免刺眼
    private val orbs = listOf(
        Orb(0xFF3D7BFF.toInt(), 0.62f, 0.18f, 0.18f, 0.10f, 0.08f, 0.0f, 1.0f),
        Orb(0xFF8A4DFF.toInt(), 0.58f, 0.82f, 0.30f, 0.09f, 0.11f, 2.1f, 0.8f),
        Orb(0xFFFF4D8D.toInt(), 0.50f, 0.70f, 0.78f, 0.12f, 0.07f, 4.2f, 1.2f),
        Orb(0xFF24D3E6.toInt(), 0.55f, 0.22f, 0.72f, 0.08f, 0.10f, 5.4f, 0.95f),
    )

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val orbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private var animator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // 软件层保证径向渐变/动画平滑
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val colors = if (light) {
            intArrayOf(0xFFEEF2FB.toInt(), 0xFFF2EEFA.toInt(), 0xFFEAF6F8.toInt())
        } else {
            // v1.9.54：夜间底从近纯黑提亮为中等深蓝紫——不再发闷，卡片/文字更清晰，仍保有夜间氛围
            intArrayOf(0xFF232B4E.toInt(), 0xFF2B3159.toInt(), 0xFF1C2240.toInt())
        }
        bgPaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            colors, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // 光球：径向渐变，中心有色、边缘全透明；浅色主题透明度减半
        val base = minOf(w, h)
        for (o in orbs) {
            val px = (o.cxFrac + sin(t * o.speed + o.phase) * o.ax) * w
            val py = (o.cyFrac + cos(t * o.speed * 0.85f + o.phase) * o.ay) * h
            val r = base * o.rFrac
            val grad = RadialGradient(px, py, r,
                intArrayOf(o.color, o.color and 0x00FFFFFF),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            orbPaint.shader = grad
            orbPaint.alpha = if (light) 44 else 150
            c.drawCircle(px, py, r, orbPaint)
        }

        // 顶部一道柔光，增强玻璃感
        val topColors = if (light) {
            intArrayOf(0x30FFFFFF, 0x00FFFFFF)
        } else {
            intArrayOf(0x38FFFFFF, 0x00FFFFFF)
        }
        val top = LinearGradient(0f, 0f, 0f, h * 0.32f,
            topColors, floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = top }
        c.drawRect(0f, 0f, w.toFloat(), h * 0.32f, tp)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1000f).apply {
            duration = 240_000L          // 极慢，水晶缓缓流动
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float * 0.01f
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel(); animator = null
        super.onDetachedFromWindow()
    }
}
