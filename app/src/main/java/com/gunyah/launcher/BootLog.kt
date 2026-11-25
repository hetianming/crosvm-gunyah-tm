package com.gunyah.launcher

import java.io.File

/** boot.log 读取工具：给启动控制台实时显示进度、检测 UEFI 就绪标记。 */
object BootLog {

    /** 读日志尾部 N 行。文件不存在返回空串。 */
    fun readTail(file: File, maxLines: Int = 220): String {
        if (!file.exists()) return ""
        return runCatching {
            val lines = file.readLines(Charsets.UTF_8)
            val tail = if (lines.size > maxLines) lines.takeLast(maxLines) else lines
            tail.joinToString("\n")
        }.getOrDefault("")
    }

    /**
     * 启动控制台专用「清洗视图」（v1.9.4）：
     *  - 只显示 crosvm / UEFI / guest 的真实日志（带时间戳行、UEFI 文本、错误行）；
     *  - 启动脚本自身的进度 echo（[*] [✓] [!] memlock-runner 等）全部隐藏；
     *  - 「分片大页连续内存」准备段（mthp 的 GH: 系列）折叠成一句提示语。
     * 原始 boot.log 仍完整落盘，供「复制日志」与崩溃诊断使用，本方法只影响显示。
     */
    fun readConsoleView(file: File, maxLines: Int = 300): String {
        if (!file.exists()) return ""
        return runCatching {
            val lines = file.readLines(Charsets.UTF_8)
            val out = ArrayList<String>()
            var hugeHintShown = false
            for (raw in lines) {
                val t = raw.trimStart()
                // 1) 分片大页 / 连续内存准备段 → 一句提示语代替（只插入一次）
                if (t.contains("hypervisor::gunyah::mthp") && t.contains("GH:")) {
                    if (!hugeHintShown) {
                        out.add("[i] 正在准备连续大页内存（分片采集，稍候…）")
                        hugeHintShown = true
                    }
                    continue
                }
                // 2) 启动脚本自身的进度/诊断 echo → 隐藏（含缩进续行）
                if (isScriptLine(raw, t)) continue
                // 3) 其余一律视为 crosvm / UEFI / guest 输出 → 显示
                out.add(raw)
            }
            val tail = if (out.size > maxLines) out.takeLast(maxLines) else out
            tail.joinToString("\n")
        }.getOrDefault("")
    }

    /** 是否为启动脚本自身的输出（非 crosvm）。
     *  crosvm 的真实日志都以 "[2026-... LEVEL module]" 形式开头；脚本 echo 用的是 [*] [✓] 等。 */
    private fun isScriptLine(raw: String, t: String): Boolean {
        if (t.isEmpty()) return true
        // crosvm 时间戳行：'[2' 开头（年份 2xxx），一律保留
        if (t.startsWith("[2") && t.contains("+00:00")) return false
        if (t.startsWith("[memlock-runner]") || t.startsWith("[log-guard]")) return true
        if (t.startsWith("[*]") || t.startsWith("[✓") || t.startsWith("[v]") ||
            t.startsWith("[!]") || t.startsWith("[!!") || t.startsWith("[错误")) return true
        if (t.startsWith("CMD:")) return true
        if (t.startsWith("====") || t.startsWith("----")) return true
        if (t.startsWith("LD_LIBRARY_PATH=")) return true
        if (t.startsWith("gunydhcpd:") || t.startsWith("gunytap:") ||
            t.startsWith("[dd]") || t.startsWith("dhcpcd:")) return true
        // 脚本的缩进中文续行提示（原文以空格开头，trim 后无 crosvm 时间戳特征）
        if (raw.length != raw.trimStart().length && !t.startsWith("[")) return true
        return false
    }

    /** 日志中是否出现过某标记。 */
    fun containsMarker(file: File, marker: String): Boolean {
        if (!file.exists()) return false
        return runCatching {
            file.useLines { seq -> seq.any { it.contains(marker) } }
        }.getOrDefault(false)
    }

    /** 是否检测到 crosvm 崩溃（供控制台提示用户，不再黑屏干等）。 */
    fun hasCrash(file: File): Boolean = containsMarker(file, "crosvm fatal signal") ||
        containsMarker(file, "DroidVM crosvm crash handler")
}
