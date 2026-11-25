package com.gunyah.launcher

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent

/**
 * 全局点击震动基类（v1.8.4）。
 * 所有 APP 界面统一继承它：任何一次轻点都给一次短震，滑动/长按不震。
 * 事件照常 super 分发，不拦截、不消费，零干扰原有点击逻辑。
 */
open class BaseActivity : Activity() {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        ClickHaptic.onTouch(this, ev)
        return super.dispatchTouchEvent(ev)
    }
}
