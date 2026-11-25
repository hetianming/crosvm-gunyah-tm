package com.gunyah.launcher

/** 跨 Activity 传递当前显示器基准分辨率（宽, 高）。 */
object ConfigHolder {
    @Volatile
    var last: Pair<Int, Int> = 720 to 1584
}
