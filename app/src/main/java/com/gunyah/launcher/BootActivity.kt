package com.gunyah.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.gunyah.launcher.core.GunyahManager
import java.io.File

/**
 * 启动下发状态：MainActivity 点「启动」后立即进控制台，真正的校验+下发在后台线程做，
 * 通过本对象把「已下发 / 前置失败原因」传给 BootActivity 显示并决定是否自动返回主页。
 */
object BootLaunchState {
    @Volatile var launched = false          // start() 已成功下发启动脚本
    @Volatile var failReason: String? = null // 前置失败原因（非空即判定启动失败）
    fun begin() { launched = false; failReason = null }
    fun launched() { launched = true }
    fun fail(reason: String) { failReason = reason }
}

/**
 * 启动控制台：点「启动」后先进这里，实时显示 boot.log（含 UEFI/GRUB 文字），
 * 检测到「UEFI 帧缓冲就绪」后立即跳进原生显示器。
 *
 * v1.9.30：仿 Windows 启动画面——
 *  - 纯黑全屏沉浸（隐藏状态栏/导航栏），底部三按钮全部移除；
 *  - 中央按模式显示 Logo（Win=徽标 / Linux=企鹅），整体内容严格垂直居中；
 *  - Logo 下方两句固定提示语 + 启动进度条（按 boot.log 真实阶段推进）；
 *  - 日志一行跑马灯上下居中扩散滚动；报错时红字，长按可调用系统复制。
 */
class BootActivity : BaseActivity() {

    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var alive = true
    private lateinit var logView: TextView
    private lateinit var logFile: File
    private lateinit var downgradeFile: File
    private var startedAt = 0L
    private var jumped = false   // 防止重复 jumpDisplay
    private var backScheduled = false  // 防止重复调度「失败自动返回主页」
    private val BOOT_TIMEOUT_MS = 8 * 60 * 1000L  // 8 分钟仍未成功→判定失败返回主页

    // 进度条
    private lateinit var progressTrack: View
    private lateinit var progressFill: View
    private lateinit var progressPct: TextView
    private var targetProgress = 6      // 目标进度（按日志阶段）
    private var currentProgress = 0     // 平滑追赶的显示进度
    private var errorState = false
    private var heartTick = 0           // 大页采集心跳动画计数

    // 固定的两行提示语（用户要求文案固定，不随状态变化）
    private val hintLine1 = "正在启动虚拟机 · 请稍候"
    private val hintLine2 = "UEFI 初始化 → 加载系统 → 进入显示器"

    /** 启动阶段：按顺序匹配 boot.log 中真实出现的 marker，任一命中即算到达该阶段。 */
    private data class Stage(val name: String, val markers: List<String>)
    private val stages = listOf(
        Stage("初始化工具链", listOf("引导模式:", "使用工具链 crosvm", "libc++.so", "crosvm-mmio")),
        Stage("加载 Gunyah 驱动", listOf("/dev/gunyah 已就绪", "gunyah 已就绪")),
        Stage("准备连续大页内存", listOf("大页池已就绪", "采池前整理物理内存", "采集大页", "池已就绪")),
        Stage("配置显示与固件", listOf("simplefb 显示", "UEFI 固件", "vars.fd", "显示模式")),
        Stage("挂载磁盘与网络", listOf("盘位检测", "tap ", "网络模式", " gunytap")),
        // v1.9.32：85% 只在 guest 真正确认起来后才到，不再用「启动命令回显」误判，
        // 避免大页采集还在进行时进度条就顶到 85%、日志却卡在一行 → 看起来像死机。
        Stage("启动 crosvm", listOf("guest 已进入固件阶段", "启动成功, crosvm", "VNC 已监听")),
        Stage("进入 UEFI 画面", listOf("UEFI 帧缓冲已就绪")),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全屏沉浸：隐藏状态栏 + 导航栏，纯黑一整块
        immersiveFullscreen()
        startedAt = System.currentTimeMillis()
        val priv = GunyahManager.privateDir(this)
        logFile = File(priv, "boot.log")
        downgradeFile = File(priv, "mem_downgrade")

        val d = resources.displayMetrics.density
        val mode = intent.getIntExtra("mode", 0)
        val mp = ViewGroup.LayoutParams.MATCH_PARENT
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT

        // ---- 中央 Logo（哪个模式显示哪个）----
        val logo = ImageView(this).apply {
            setImageResource(if (mode == 0) R.drawable.mode_win_clear_ico else R.drawable.mode_linux_clear_ico)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        // ---- 固定提示语两行（Logo 下方，距离适中）----
        val hint1 = TextView(this).apply {
            text = hintLine1
            textSize = 19f
            setTextColor(0xFFF2F6FA.toInt())
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val hint2 = TextView(this).apply {
            text = hintLine2
            textSize = 13f
            setTextColor(0xFF8B97A8.toInt())
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }

        // ---- 进度条：圆角轨道 + 平滑填充 + 百分比 ----
        fun barBg(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 4f * d
            setColor(color)
        }
        val barH = (7 * d).toInt()
        progressTrack = View(this).apply { background = barBg(0xFF1E2A3C.toInt()) }
        progressFill = View(this).apply {
            background = barBg(0xFF4FC3F7.toInt())
            layoutParams = FrameLayout.LayoutParams(0, barH)
        }
        progressPct = TextView(this).apply {
            text = "0%"
            textSize = 12f
            setTextColor(0xFF7FB8D0.toInt())
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            isSingleLine = true
        }
        val barBox = FrameLayout(this).apply {
            addView(progressTrack, FrameLayout.LayoutParams(mp, barH))
            addView(progressFill, FrameLayout.LayoutParams(0, barH))
        }
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(barBox, LinearLayout.LayoutParams(0, barH, 1f))
            addView(progressPct, LinearLayout.LayoutParams((48 * d).toInt(), wc).apply {
                leftMargin = (10 * d).toInt()
            })
        }

        // ---- 日志一行：跑马灯上下居中扩散滚动，可选中长按复制 ----
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(0xFF66FF99.toInt())
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            setHorizontallyScrolling(true)
            isSelected = true
            setTextIsSelectable(true)
            setOnLongClickListener { it.isSelected = true; false }
            text = "● 正在启动，实时输出 crosvm 引导日志…"
        }

        // Logo + 提示语 + 进度条 + 日志整体居中，垂直排布
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val logoW = (140 * d).toInt()
            addView(logo, LinearLayout.LayoutParams(logoW, logoW))
            addView(hint1, LinearLayout.LayoutParams(mp, wc).apply { topMargin = (32 * d).toInt() })
            addView(hint2, LinearLayout.LayoutParams(mp, wc).apply { topMargin = (12 * d).toInt() })
            addView(progressRow, LinearLayout.LayoutParams((240 * d).toInt(), wc).apply { topMargin = (30 * d).toInt() })
            addView(logView, LinearLayout.LayoutParams(mp, wc).apply { topMargin = (26 * d).toInt() })
        }

        // 强制几何居中：内容块上下各放一个等权重(1:1)空白，把整块严格夹在屏幕正中。
        // 不再依赖 FrameLayout+gravity 的居中计算（实测上下空白不等、整体偏下）。
        fun spacer() = View(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            addView(spacer(), LinearLayout.LayoutParams(mp, 0, 1f))
            addView(center, LinearLayout.LayoutParams(mp, wc))
            addView(spacer(), LinearLayout.LayoutParams(mp, 0, 1f))
        }
        setContentView(root)

        // 进度条宽度就绪后先渲染一次初始进度
        progressTrack.post { applyProgress(currentProgress) }
        startPolling()
    }

    /** 把当前进度值画到进度条（宽度 + 百分比）。 */
    private fun applyProgress(p: Int) {
        val w = progressTrack.width
        if (w <= 0) return
        val lp = progressFill.layoutParams as FrameLayout.LayoutParams
        lp.width = (w * p / 100f).toInt().coerceAtLeast(if (p > 0) 4 else 0)
        progressFill.layoutParams = lp
        progressPct.text = "$p%"
    }

    /** 按 boot.log 全文推断当前到达的阶段序号（0..stages.size），再换算成进度百分比。 */
    private fun detectStagePercent(text: String): Int {
        var reached = -1
        for (i in stages.indices) {
            if (stages[i].markers.any { text.contains(it) }) reached = i
        }
        return ((reached + 1) * 100 / stages.size).coerceIn(0, 100)
    }

    /** 沉浸全屏：状态栏/导航栏隐藏，内容铺满，点屏不留系统栏。 */
    private fun immersiveFullscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                )
        } else {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
            )
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersiveFullscreen()
    }

    private fun startPolling() {
        Thread({
            while (alive) {
                val txt = BootLog.readConsoleView(logFile, 300)
                val uefiReady = BootLog.containsMarker(logFile, "检测到 uefi: UEFI 帧缓冲已就绪")
                val nowCrashed = BootLog.hasCrash(logFile)
                // 前置失败（工具链/镜像/单实例冲突/下发失败）由 MainActivity 后台写入
                val preflightFail = BootLaunchState.failReason
                val elapsed = System.currentTimeMillis() - startedAt
                val timedOut = elapsed > BOOT_TIMEOUT_MS && !uefiReady
                // 任一失败：前置失败 / boot.log 崩溃 marker / 超时
                val failMsg = when {
                    preflightFail != null -> preflightFail
                    nowCrashed -> txt
                    timedOut -> "启动超时（超过 8 分钟仍未进入固件）"
                    else -> null
                }
                val anyFail = failMsg != null

                // 内存降级提示：脚本写了标记就震动一次并删除（避免重复震动）
                if (downgradeFile.exists()) {
                    val down = runCatching { downgradeFile.readText().trim() }.getOrDefault("")
                    runCatching { downgradeFile.delete() }
                    ui.post { vibrateDowngrade() }
                    android.util.Log.i("BootActivity", "内存降级到 ${down}MB，已震动提示")
                }

                // 进度：读全文按阶段推断目标
                val full = runCatching { logFile.readText(Charsets.UTF_8) }.getOrDefault("")
                targetProgress = detectStagePercent(full)
                if (uefiReady) targetProgress = 100

                // 大页采集阶段：日志长时间停在同一句固定提示，加动态心跳（旋转点 + 已等秒数 + 进度说明），
                // 让用户明确看到"还在采集、不是卡死"。
                // 仅当控制台视图基本只剩这一句固定提示时才启用心跳；一旦有真实 UEFI 日志进来就显示真日志。
                // v1.9.37：大页采集等待阶段——日志停在「正在准备连续大页内存 / mTHP 策略 /
                // 后半段兜底 single」这类固定行、迟迟没有新日志时，加动态心跳避免误判卡死。
                // 保留当前那行日志，下面加一行跳动的采集提示 + 已等秒数 + 请耐心等待。
                // 仅当控制台视图基本只剩这一两句时启用；真实 UEFI 日志进来自动切回真日志。
                val uefiHandled = uefiReady || anyFail
                val bigPageHints = listOf("正在准备连续大页内存", "后半段兜底", "mTHP 策略", "分片采集", "采集大页")
                val nonBlank = txt.lines().filter { it.isNotBlank() }
                val fewLines = nonBlank.count() <= 3
                // v1.9.38：Win 模式常见——脚本 echo 被清洗后控制台视图为空，进度却已推进到
                // 大页/显示/挂载阶段(28~85%)，此时静态占位「● 正在启动…」一直不动 → 看着像卡死。
                // 把这种「静默等待」也纳入心跳，和 Linux 保持一致；真有 crosvm/UEFI 日志时不介入。
                val silentWait = nonBlank.isEmpty() && targetProgress in 28..85
                val inBigPage = (bigPageHints.any { txt.contains(it) } || silentWait) &&
                    fewLines && !uefiHandled
                val secWaited = (System.currentTimeMillis() - startedAt) / 1000
                heartTick++
                val shownLog = if (inBigPage) {
                    val dots = "." + " .".repeat(heartTick % 4)
                    val cur = nonBlank.firstOrNull()?.trim() ?: "[i] 正在准备连续大页内存"
                    "$cur\n" +
                        "    正在采集连续大页$dots 已等待 ${secWaited}s（最长约 90 秒，请耐心等待，勿退出）"
                } else {
                    txt.ifBlank { "● 正在启动，实时输出 crosvm 引导日志…" }
                }

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    logView.text = shownLog
                    // 心跳阶段允许两行显示，正常阶段保持单行跑马灯
                    logView.isSingleLine = !inBigPage
                    logView.maxLines = if (inBigPage) 2 else 1
                    logView.isSelected = true
                    // 平滑追赶目标进度，避免阶段跳变太生硬
                    if (currentProgress < targetProgress) {
                        currentProgress = (currentProgress + 3).coerceAtMost(targetProgress)
                    }
                    applyProgress(currentProgress)

                    if (anyFail) {
                        errorState = true
                        progressFill.background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 4f * resources.displayMetrics.density
                            setColor(0xFFFF6B6B.toInt())
                        }
                        logView.setTextColor(0xFFFF6B6B.toInt())
                        logView.ellipsize = android.text.TextUtils.TruncateAt.END
                        logView.isSelected = false
                        logView.text = "启动失败 · $failMsg"
                    } else if (preflightFail == null && !BootLaunchState.launched) {
                        // 前置校验仍在进行（工具链/大页/输入监听），给用户明确提示，别以为是死机
                        logView.text = "● 正在准备启动环境（工具链 / 大页内存 / 输入服务）…"
                    }
                }

                // 成功：UEFI 帧缓冲就绪即立即进入显示器。
                if (uefiReady && !anyFail && !jumped) {
                    jumped = true
                    ui.post { jumpDisplay() }
                    break
                }
                // 失败：红字停留约 3 秒后自动返回 APP 主页。
                if (anyFail) {
                    scheduleBack(failMsg ?: "启动失败")
                    break
                }
                Thread.sleep(300)
            }
        }, "boot-console").apply { isDaemon = true }.start()
    }

    /** 内存降档震动提示：比键盘轻震更明显，一次 180ms。 */
    private fun vibrateDowngrade() {
        runCatching {
            val v = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(180)
            }
        }
    }

    /**
     * 启动失败：红字停留约 3 秒让用户看清原因，然后自动结束本页回到 APP 主页（ MainActivity
     * 一直在返回栈底）。同时把失败原因写进主页日志/Toast，避免用户回到主页不知道发生了什么。
     */
    private fun scheduleBack(reason: String) {
        if (backScheduled) return
        backScheduled = true
        ui.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            runCatching {
                setResult(android.app.Activity.RESULT_OK, Intent().putExtra("boot_fail", reason))
            }
            finish()
        }, 3000)
    }

    private fun jumpDisplay() {
        if (isFinishing || isDestroyed) return
        // 用当前配置的分辨率作为显示器尺寸基准
        val cfg = GunyahManager.loadConfig(this)
        ConfigHolder.last = cfg.resolution.lowercase().replace('×', 'x').replace('*', 'x').let { r ->
            val w = r.substringBefore('x').toIntOrNull() ?: 720
            val h = r.substringAfter('x', "1584").toIntOrNull() ?: 1584
            w to h
        }
        startActivity(Intent(this, VmDisplayActivity::class.java))
    }

    override fun onDestroy() {
        alive = false
        super.onDestroy()
    }
}
