package com.gunyah.launcher

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent

/**
 * 全局点击震动反馈（v1.8.4）。
 *
 * 设计要点：
 *  - [onTouch] 放在 Activity/Dialog 的 dispatchTouchEvent 里：这是所有触摸事件的必经之路，
 *    比给根视图 setOnTouchListener 可靠（后者在子 View 消费事件时收不到 ACTION_UP）。
 *  - 只认「轻点」：位移小、时长短，滑动/长按不震。
 *  - [tick] 带全局节流，虚拟键盘按键（hapticTick）也走这里，避免同一次点击震两下。
 */
object ClickHaptic {
    private const val THROTTLE_MS = 70L          // 70ms 内只震一次，防连点/双震
    private var lastMs = 0L

    // 轻点判定状态（Activity 与 Dialog 不会同时多指交错，共用一组即可）
    private var downX = 0f
    private var downY = 0f
    private var downT = 0L

    /** 在 dispatchTouchEvent 中调用（返回 false，不拦截、不消费事件）。 */
    fun onTouch(ctx: Context, ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; downT = SystemClock.elapsedRealtime()
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val dt = SystemClock.elapsedRealtime() - downT
                val slop = 14f * ctx.resources.displayMetrics.density
                if (dx * dx + dy * dy <= slop * slop && dt < 800L) {
                    tick(ctx, 14, 90)
                }
            }
        }
    }

    /** 主动震动。虚拟键盘按键用 (18,100) 保持原手感；全局点击用 (14,90) 更轻快。 */
    @Suppress("DEPRECATION")
    fun tick(ctx: Context, ms: Int = 14, amplitude: Int = 90) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastMs < THROTTLE_MS) return
        lastMs = now
        runCatching {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms.toLong(), amplitude))
            } else {
                v.vibrate(ms.toLong())
            }
        }
    }
}
