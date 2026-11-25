package com.gunyah.launcher

import android.app.Dialog
import android.content.Context
import android.view.MotionEvent

/**
 * 带全局点击震动的弹窗（v1.8.4）。
 * Dialog 是独立 Window，触摸不经过 Activity.dispatchTouchEvent，故单独覆盖。
 */
open class HapticDialog(context: Context) : Dialog(context) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        ClickHaptic.onTouch(context, ev)
        return super.dispatchTouchEvent(ev)
    }
}
