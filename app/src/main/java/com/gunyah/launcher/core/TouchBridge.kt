package com.gunyah.launcher.core

/**
 * 旧 touchd/TCP 输入桥的兼容 stub。
 *
 * 新 APP 只用 crosvm 的 Android 原生显示 + root 服务托管的 4 路 unix socket 输入
 * （[NativeInputBridge] / [InputServiceClient]），不再走 touchd。GunyahManager 的
 * 启动脚本生成里还留着这两处调用，保留空实现使其编译通过、且不会误启动旧桥。
 */
object TouchBridge {
    fun start(path: String, w: Int, h: Int) { /* no-op: 原生显示不使用 touchd */ }
    fun stop() { /* no-op */ }
}
