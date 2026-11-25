package com.gunyah.launcher.core

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class ShellResult(val code: Int, val stdout: String, val stderr: String) {
    val success: Boolean get() = code == 0
}

object RootShell {
    @Volatile
    var lastLog: ((String) -> Unit)? = null

    // 探测到的 su 命令；避免依赖 APP 进程的 PATH（Android APP 的 PATH 常只有 /system/bin）
    @Volatile
    private var suCmd: Array<String>? = null
    @Volatile
    private var probed = false

    private val SU_PATHS = arrayOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/system/sbin/su",
        "/sbin/su",
        "/vendor/bin/su",
        "/vendor/xbin/su",
        "/su/bin/su",
        "/magisk/.core/bin/su",
        "/magisk/bin/su",
        "/data/adb/ap/bin/su",
        "/data/adb/ksu/bin/su",
        "/data/local/tmp/su",
        "/data/su/su"
    )

    // KernelSU / Magisk 主程序：须带 su 子参数才能进 root shell
    private val SU_APPLETS = arrayOf(
        "/data/adb/ksu/bin/ksu",
        "/data/adb/magisk/magisk"
    )

    /** 解析出可执行的 su 命令（含参数）。找不到时返回 null。 */
    private fun resolveSu(): Array<String>? {
        if (probed) {
            val c = suCmd ?: return null
            return c.copyOf()
        }
        for (p in SU_PATHS) {
            if (File(p).exists()) {
                suCmd = arrayOf(p)
                probed = true
                return suCmd!!.copyOf()
            }
        }
        for (p in SU_APPLETS) {
            if (File(p).exists()) {
                suCmd = arrayOf(p, "su")
                probed = true
                return suCmd!!.copyOf()
            }
        }
        // 兜底回到系统 PATH 解析（与旧行为一致）
        probed = true
        suCmd = arrayOf("su")
        return suCmd!!.copyOf()
    }

    /** 供 UI 显示当前 su 探测结论。 */
    fun suDiagnostics(): String {
        val c = resolveSu() ?: return "未找到 su：请确认已装 Magisk / KernelSU / APatch 并授予本应用 ROOT"
        val joined = c.joinToString(" ")
        return if (c[0] == "su") {
            "su 走系统 PATH（可能找不到，建议用绝对路径方案）"
        } else {
            "su = $joined"
        }
    }

    fun available(timeoutMs: Long = 15000): Boolean {
        val result = exec("id -u", timeoutMs = timeoutMs)
        return result.success && result.stdout.trim().endsWith("0")
    }

    fun exec(script: String, timeoutMs: Long = 30000, log: ((String) -> Unit)? = null): ShellResult {
        val sink = log ?: lastLog
        val cmd = resolveSu() ?: return ShellResult(-1, "", suDiagnostics())
        try {
            val process = ProcessBuilder(*cmd).start()
            process.outputStream.use { stream ->
                stream.write((script.trimEnd() + "\n").toByteArray())
                stream.write("echo __RC_$?__\n".toByteArray())
                stream.write("exit\n".toByteArray())
                stream.flush()
            }
            val stdout = StringBuilder()
            val stderr = StringBuilder()
            val t1 = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).forEachLine { line ->
                        synchronized(stdout) { stdout.appendLine(line) }
                        sink?.invoke(line)
                    }
                }
            }
            val t2 = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.errorStream)).forEachLine { line ->
                        synchronized(stderr) { stderr.appendLine(line) }
                        sink?.invoke(line)
                    }
                }
            }
            t1.start(); t2.start()
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                return ShellResult(-1, stdout.toString(), "timeout after ${timeoutMs}ms")
            }
            t1.join(3000); t2.join(3000)
            val out = synchronized(stdout) { stdout.toString() }
            val err = synchronized(stderr) { stderr.toString() }
            val code = Regex("__RC_(\\d+)__").find(out)?.groupValues?.get(1)?.toIntOrNull()
                ?: process.exitValue().let { if (it == 0) -1 else it }
            return ShellResult(code, out.replace(Regex("__RC_\\d+__\n?"), ""), err)
        } catch (e: Exception) {
            val base = e.message ?: e.javaClass.simpleName
            return ShellResult(-1, "", "$base；${suDiagnostics()}")
        }
    }

    fun getprop(name: String): String =
        exec("getprop $name", timeoutMs = 10000).stdout.trim()

    // root 流式读取文件（su cat）：app 无直读权限的大文件（GSI zip 等）零拷贝读取
    // 关闭流时同时结束 su 进程，避免残留
    fun openStream(path: String): java.io.InputStream {
        val cmd = resolveSu() ?: throw IllegalStateException(suDiagnostics())
        val process = ProcessBuilder(*cmd).start()
        process.outputStream.use { output ->
            output.write("cat \"$path\"\n".toByteArray())
            output.flush()
        }
        return object : java.io.FilterInputStream(process.inputStream) {
            override fun close() {
                runCatching { super.close() }
                runCatching { process.destroy() }
            }
        }
    }

    /** shell 单引号转义：' -> '\''（用于把不可信路径安全嵌进单引号串）。 */
    fun shEscape(s: String): String = s.replace("'", "'\\''")

    /**
     * root 流式写文件（su + cat > path）。app 进程无权写 /sdcard 的 FUSE 挂载点或
     * /data/media/0 真实路径，必须借 root 落盘。调用方写完 close() 即完成写盘。
     * 父目录须已存在（由调用方 root mkdir）。
     */
    fun openOutputStream(path: String): java.io.OutputStream {
        val cmd = resolveSu() ?: throw IllegalStateException(suDiagnostics())
        val process = ProcessBuilder(*cmd).start()
        val sink = process.outputStream
        sink.write("cat > '${shEscape(path)}'\n".toByteArray())
        sink.flush()
        return object : java.io.FilterOutputStream(sink) {
            override fun close() {
                runCatching { super.close() }
                runCatching { process.waitFor(8000, TimeUnit.MILLISECONDS) }
                runCatching { process.destroy() }
            }
        }
    }

    /** root 判断路径是否存在（文件/目录）。 */
    fun pathExists(path: String): Boolean =
        exec("[ -e '${shEscape(path)}' ] && echo __Y__ || echo __N__", timeoutMs = 10000)
            .stdout.contains("__Y__")

    /** root 取文件字节数；失败或不存蹆返回 -1。 */
    fun fileSize(path: String): Long =
        exec("stat -c %s '${shEscape(path)}' 2>/dev/null", timeoutMs = 10000)
            .stdout.trim().lines().lastOrNull()?.toLongOrNull() ?: -1L
}
