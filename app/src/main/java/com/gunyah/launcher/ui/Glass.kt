package com.gunyah.launcher.ui

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.roundToInt

/**
 * 液态玻璃组件工厂：玻璃底、玻璃描边、按下水晶反馈。
 * isLight 由 MainActivity 在构建 UI 前设置，切换主题时整体重建。
 */
object Glass {

    @Volatile
    var isLight: Boolean = false

    private fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density).roundToInt()

    /**
     * 玻璃面板/按钮背景：
     * - 半透明填充（透出底下光晕）—— 深色主题用半透明白，浅色主题用更实的白
     * - 描边模拟玻璃高光边缘
     * - pressed 态略微提亮
     */
    fun panel(ctx: Context, radiusDp: Float = 18f, pressed: Boolean = false): GradientDrawable {
        val fill: Int
        val stroke: Int
        if (isLight) {
            // 浅底：白玻璃需要更实、描边偏冷灰，才能从浅背景里浮出来
            fill = if (pressed) 0xF2FFFFFF.toInt() else 0xDEFFFFFF.toInt()
            stroke = if (pressed) 0x99A9C4.toInt() else 0x6690B4D8.toInt()
        } else {
            // v1.9.54：夜间卡片填充/描边提亮，从背景中浮出更清晰
            fill = if (pressed) 0x3DFFFFFF.toInt() else 0x2BFFFFFF.toInt()
            stroke = if (pressed) 0x88FFFFFF.toInt() else 0x55FFFFFF.toInt()
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(ctx, 1f), stroke)
        }
    }

    /**
     * 对话框玻璃底：比普通面板更实，能压住背后的 UI，同时保留玻璃高光边缘。
     * 深浅两套配色，跟随 isLight。
     */
    fun dialogPanel(ctx: Context, radiusDp: Float = 24f): GradientDrawable {
        val fill: Int
        val stroke: Int
        if (isLight) {
            fill = 0xE8FFFFFF.toInt()    // 浅色：高不透明白玻璃，配合窗口背景模糊成毛玻璃
            stroke = 0x6690B4D8.toInt()
        } else {
            fill = 0xE0161C2B.toInt()    // 深色：高不透明深玻璃，压住背后文字
            stroke = 0x55FFFFFF.toInt()
        }
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(fill)
            setStroke(dp(ctx, 1f).coerceAtLeast(1), stroke)
        }
    }

    /** 选中态玻璃：淡淡的蓝色玻璃，透底 + 蓝描边高光 */
    fun panelSelected(ctx: Context, radiusDp: Float = 18f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(if (isLight) 0x2E4A90FF.toInt() else 0x2A3A6BFF.toInt())
            setStroke(dp(ctx, 1.5f), if (isLight) 0xAA3D78D6.toInt() else 0x99AACCFF.toInt())
        }

    /** 带按下高亮的 StateListDrawable（普通按钮用） */
    fun button(ctx: Context, radiusDp: Float = 16f): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), panel(ctx, radiusDp, pressed = true))
            addState(intArrayOf(), panel(ctx, radiusDp, pressed = false))
        }

    /**
     * 细边框按钮：在玻璃底外面描一圈清晰高光细边（1.25dp），用于主操作长条按钮。
     * 深色边偏亮白微蓝、浅色边偏冷蓝，玻璃填充比普通按钮略透，按下时边框更亮。
     */
    /**
     * 高透玻璃边框底：填充比 outlinedButton 更透，用于需要透出底下签名图的卡片。
     * 保留 1.25dp 高光细边和圆角，填充压到很低透明度，让底层签名隐约透出。
     */
    fun outlinedTransparent(ctx: Context, radiusDp: Float = 18f): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(if (isLight) 0x59FFFFFF.toInt() else 0x0FFFFFFF.toInt())
            setStroke(dp(ctx, 1.25f), if (isLight) 0x804A86D8.toInt() else 0x8093AFFF.toInt())
        }

    fun outlinedButton(ctx: Context, radiusDp: Float = 16f): StateListDrawable {
        fun edge(pressed: Boolean): GradientDrawable {
            val fill: Int
            val stroke: Int
            if (isLight) {
                fill = if (pressed) 0xE8FFFFFF.toInt() else 0xB3FFFFFF.toInt()
                stroke = if (pressed) 0xFF3D78D6.toInt() else 0x804A86D8.toInt()
            } else {
                // v1.9.54：夜间高光细边卡填充提亮
                fill = if (pressed) 0x38FFFFFF.toInt() else 0x24FFFFFF.toInt()
                stroke = if (pressed) 0xCCBBD5FF.toInt() else 0x99A8FFFF.toInt()
            }
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(ctx, radiusDp).toFloat()
                setColor(fill)
                setStroke(dp(ctx, 1.25f), stroke)
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), edge(true))
            addState(intArrayOf(), edge(false))
        }
    }

    /**
     * LOGO 动态效果：选中的模式 LOGO 持续呼吸缩放 + 轻微上下浮动；
     * 未选中的停止动画并复位到原始大小/位置。动画实例存在 view.tag，切换时先取消旧的。
     */
    fun animateLogo(ctx: Context, img: View, active: Boolean) {
        (img.tag as? AnimatorSet)?.let { it.cancel(); img.tag = null }
        img.scaleX = 1f; img.scaleY = 1f; img.translationY = 0f
        if (!active) return
        val dur = 1700L
        fun loop(a: ObjectAnimator) = a.apply {
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            duration = dur
            interpolator = DecelerateInterpolator()
        }
        val sx = loop(ObjectAnimator.ofFloat(img, View.SCALE_X, 1f, 1.085f))
        val sy = loop(ObjectAnimator.ofFloat(img, View.SCALE_Y, 1f, 1.085f))
        val ty = loop(ObjectAnimator.ofFloat(img, View.TRANSLATION_Y, 0f, -dp(ctx, 6f).toFloat()))
        AnimatorSet().apply {
            playTogether(sx, sy, ty)
            start()
        }.also { img.tag = it }
    }

    /** 水晶点击反馈：轻按缩放回弹（0.94 -> 1.0）。给任意 View 绑定。 */
    fun crystalTouch(v: View) {
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().cancel()
                    view.animate().scaleX(0.94f).scaleY(0.94f)
                        .setDuration(90).setInterpolator(DecelerateInterpolator()).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f)
                        .setDuration(220).setInterpolator(DecelerateInterpolator()).start()
                }
            }
            false // 不拦截，onClick 照常触发
        }
    }

    /** 选中切换时的一次性高光脉冲（透明 -> 亮 -> 透明） */
    fun pulse(v: View) {
        ObjectAnimator.ofFloat(v, View.ALPHA, 0.35f, 1f, 0.35f).apply {
            duration = 520
            interpolator = DecelerateInterpolator()
            start()
        }
    }

    // ---- 主题色：文字 / 指示条 / 分隔线 / 状态栏图标 ----
    fun textPrimary() = if (isLight) 0xFF1A2540.toInt() else 0xFFF7FAFF.toInt()
    // v1.9.55：夜间次要文字提亮为清亮蓝白——标签/提示在提亮后的背景上依然清晰，不再灰暗不搭
    fun textLog() = if (isLight) 0xFF34445F.toInt() else 0xFFDCE7FA.toInt()
    fun textHint() = if (isLight) 0xFF60708C.toInt() else 0xFFB9C9E6.toInt()
    fun indicator() = if (isLight) 0xFF2E6FD6.toInt() else 0xFF8FC4FF.toInt()
    fun divider() = if (isLight) 0x7A8BA6B8.toInt() else 0x4AFFFFFF.toInt()

    /** 运行状态色：正常(绿)/注意(橙)/异常(红)/中性(主题蓝)，深浅主题都清晰可辨。 */
    fun statusOk()   = if (isLight) 0xFF128A4E.toInt() else 0xFF4ADE80.toInt()
    fun statusWarn() = if (isLight) 0xFFA96200.toInt() else 0xFFFBBF24.toInt()
    fun statusBad()  = if (isLight) 0xFFC62828.toInt() else 0xFFF87171.toInt()

    /** mini 状态块玻璃底：比主卡略实、圆角更小。 */
    fun chipPanel(ctx: Context, radiusDp: Float = 12f): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(ctx, radiusDp).toFloat()
        setColor(if (isLight) 0xB3FFFFFF.toInt() else 0x20FFFFFF.toInt())
        setStroke(dp(ctx, 1f), if (isLight) 0x3D90B4D8.toInt() else 0x26FFFFFF.toInt())
    }

    /** 状态圆点（实心圆），用于状态灯。 */
    fun dot(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }
    fun winClear() = if (isLight) com.gunyah.launcher.R.drawable.mode_win_clear_light else com.gunyah.launcher.R.drawable.mode_win_clear
    fun winBlur() = if (isLight) com.gunyah.launcher.R.drawable.mode_win_blur_light else com.gunyah.launcher.R.drawable.mode_win_blur
    fun linuxClear() = if (isLight) com.gunyah.launcher.R.drawable.mode_linux_clear_light else com.gunyah.launcher.R.drawable.mode_linux_clear
    fun linuxBlur() = if (isLight) com.gunyah.launcher.R.drawable.mode_linux_blur_light else com.gunyah.launcher.R.drawable.mode_linux_blur

    // v1.9.12：统一规格纯图标（同画布/同高度/居中），不分深浅、不分选中态，两个模式视觉大小一致
    fun winIco() = com.gunyah.launcher.R.drawable.mode_win_clear_ico
    fun linuxIco() = com.gunyah.launcher.R.drawable.mode_linux_clear_ico

    /** 模式标题文字颜色：两个模式统一用主题主文字色，固定不参与动画。 */
    fun modeTitleColor() = textPrimary()
}
