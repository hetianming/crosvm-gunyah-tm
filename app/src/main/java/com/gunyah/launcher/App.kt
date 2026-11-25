package com.gunyah.launcher

import android.app.Application
import android.util.Log
import com.gunyah.launcher.core.GunyahManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // 手机重启后内核模块是空的（Android 没有 init.d，gunyah-host-share 不会自己回来），
        // 冷启动时静默补一次；已加载的会跳过，重复调用无副作用。
        Thread {
            runCatching { Thread.sleep(1500) }
            // 先释放内置工具链（4 个 .ko 模块 + crosvm + 固件都在 zip 里），
            // 否则 autoLoadModules 找不到 modules.ko 目录 → /dev/gunyah 不会出现。
            val (ok, msg) = GunyahManager.ensureToolchain(this)
            Log.i("GunyahLauncher", "工具链释放: ok=$ok $msg")
            if (ok) {
                val loaded = GunyahManager.autoLoadModules(this)
                Log.i("GunyahLauncher", "开机模块预加载: ${loaded.joinToString()}")
            }
        }.start()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                java.io.File(filesDir, "crash.log").writeText(
                    "time: ${java.util.Date()}\nthread: ${thread.name}\n\n${throwable.stackTraceToString()}"
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
