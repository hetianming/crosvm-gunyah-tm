package com.gunyah.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gunyah.launcher.core.RootShell
import java.io.File

/**
 * 内嵌文件浏览器。
 * 双通道列目录：① Java File API（app 进程视角 + READ_EXTERNAL_STORAGE 权限）
 *              ② root shell（兼容 /sdcard 与 /storage/emulated/0 两种路径）
 * 两条结果合并去重，目录在前、镜像文件在后。支持手动输入绝对路径、快捷目录。
 */
class FilePickerActivity : BaseActivity() {

    companion object {
        const val EXTRA_PATH = "picked_path"
        private const val REQ_STORAGE = 1001
        private val IMAGE_EXT = listOf(
            "img", "qcow2", "raw", "bin", "iso", "qcow", "vmdk", "vdi", "vhd", "gpt", "dmg", "vmpkg"
        )
        // DroidVM 官方分卷主包名为 xxx.vmpkg，子卷为 xxx.vmpkg.001 / .002 …
        // 子卷的“扩展名”是 001/002，不在白名单里，单独按名称规律放行。
        private val VMPKG_PART = Regex(".*\\.vmpkg\\.\\d{3}", RegexOption.IGNORE_CASE)
    }

    private lateinit var pathView: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var pathInput: EditText
    private var current = "/sdcard"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "选择镜像文件"

        // 浅色页面：代码强制深色状态栏图标（主题项在部分 ROM 上不生效）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
        window.statusBarColor = 0xFFE8EEF5.toInt()

        val pad = (14 * resources.displayMetrics.density).toInt()
        val wc = ViewGroup.LayoutParams.WRAP_CONTENT
        val mp = ViewGroup.LayoutParams.MATCH_PARENT

        pathView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF333333.toInt())
            setPadding(pad, pad, pad, pad)
        }

        val btnUp = Button(this).apply { text = "↑ 上级" }
        btnUp.setOnClickListener { goUp() }
        val btnCancel = Button(this).apply { text = "取消" }
        btnCancel.setOnClickListener { finish() }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFE8EEF5.toInt())
            addView(btnUp, LinearLayout.LayoutParams(0, wc, 1f))
            addView(btnCancel, LinearLayout.LayoutParams(0, wc, 1f))
        }

        // 快捷目录
        val shortcuts = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, 6, pad, 2)
            for (p in listOf("/sdcard", "/sdcard/Download", "/sdcard/Documents", "/storage/emulated/0")) {
                addView(Button(this@FilePickerActivity).apply {
                    text = when {
                        p.endsWith("Download") -> "Download"
                        p.endsWith("Documents") -> "Documents"
                        p == "/sdcard" || p.endsWith("/0") -> "根目录"
                        else -> p
                    }
                    textSize = 11f
                    setOnClickListener { browse(p); pathInput.setText(p) }
                }, LinearLayout.LayoutParams(0, wc, 1f))
            }
        }

        // 手动路径输入 + 前往
        pathInput = EditText(this).apply {
            hint = "输入目录路径，如 /sdcard/Download"
            setText(current)
            isSingleLine = true
            textSize = 13f
            setTextColor(0xFF222222.toInt())
        }
        val btnGo = Button(this).apply { text = "前往" }
        btnGo.setOnClickListener {
            val p = pathInput.text.toString().trim()
            if (p.isNotEmpty()) browse(p)
        }
        val pathRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, 4, pad, 8)
            addView(pathInput, LinearLayout.LayoutParams(0, wc, 1f))
            addView(btnGo, LinearLayout.LayoutParams(wc, wc))
        }

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listBox, mp, wc) }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pathView)
            addView(top)
            addView(shortcuts)
            addView(pathRow)
            addView(scroll, LinearLayout.LayoutParams(mp, 0, 1f))
        }
        setContentView(root)

        // 有权限直接列；没有就申请，申请结果回来后再列
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            browse(current)
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE) browse(current)
    }

    private fun goUp() {
        val parent = File(current).parent ?: return
        browse(if (parent == "/") "/sdcard" else parent)
    }

    /** Java File API 列目录，返回 名称->是否目录。 */
    private fun listViaJava(path: String): Map<String, Boolean> {
        return runCatching {
            val f = File(path)
            val kids = f.listFiles() ?: return emptyMap()
            kids.associate { it.name to it.isDirectory }
        }.getOrDefault(emptyMap())
    }

    /** root 列目录，/sdcard 不可见时自动换成 /storage/emulated/0。 */
    private fun listViaRoot(path: String): Map<String, Boolean> {
        val candidates = mutableListOf(path)
        if (path == "/sdcard" || path.startsWith("/sdcard/")) {
            candidates.add("/storage/emulated/0" + path.removePrefix("/sdcard"))
        } else if (path == "/storage/emulated/0" || path.startsWith("/storage/emulated/0/")) {
            candidates.add("/sdcard" + path.removePrefix("/storage/emulated/0"))
        }
        for (p in candidates) {
            // -1F：目录带 /，便于识别；不吞 stderr 以便诊断
            val res = RootShell.exec("ls -1F '$p' 2>&1", timeoutMs = 15000)
            val lines = res.stdout.lines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("__RC_") }
                .filter { !it.contains("No such file") && !it.contains("Permission denied") && !it.contains("cannot access") }
            if (lines.isNotEmpty()) {
                val out = linkedMapOf<String, Boolean>()
                for (raw in lines) {
                    if (raw.endsWith("/")) out[raw.removeSuffix("/")] = true
                    else out[raw.trimEnd('*', '@')] = false
                }
                return out
            }
        }
        return emptyMap()
    }

    private fun browse(path: String) {
        current = path
        pathView.text = "位置: $path"
        listBox.removeAllViews()

        // 双通道合并：Java 为准（准确识别目录），root 补漏
        val merged = linkedMapOf<String, Boolean>()
        listViaRoot(path).forEach { (k, v) -> merged[k] = v }
        listViaJava(path).forEach { (k, v) -> merged[k] = v }

        val dirs = mutableListOf<Pair<String, String>>()
        val files = mutableListOf<Pair<String, String>>()
        for ((name, isDir) in merged) {
            if (name.startsWith(".")) continue
            if (isDir) {
                dirs.add(name to "$path/$name".replace("//", "/"))
            } else {
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext in IMAGE_EXT || name.matches(VMPKG_PART))
                    files.add(name to "$path/$name".replace("//", "/"))
            }
        }
        dirs.sortBy { it.first.lowercase() }
        files.sortBy { it.first.lowercase() }

        if (dirs.isEmpty() && files.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "（此目录没有可显示的文件夹或镜像文件）\n支持的格式: ${IMAGE_EXT.joinToString(", ")}\n\n诊断: ${RootShell.suDiagnostics()}"
                textSize = 12f
                setTextColor(0xFF888888.toInt())
                gravity = Gravity.CENTER
                setPadding(padDp(), 48, padDp(), 48)
            })
        }

        for ((label, full) in dirs) {
            listBox.addView(makeRow("📁 $label/") { browse(full) })
        }
        for ((label, full) in files) {
            val size = runCatching {
                val j = File(full).length()
                if (j > 0) j else RootShell.exec("stat -c %s '$full' 2>/dev/null", 8000).stdout.trim().toLongOrNull()
            }.getOrNull() ?: 0L
            val sizeText = when {
                size >= 1024L * 1024 * 1024 -> "%.2f GB".format(size / 1024.0 / 1024 / 1024)
                size >= 1024L * 1024 -> "%.0f MB".format(size / 1024.0 / 1024)
                size > 0 -> "${size / 1024} KB"
                else -> ""
            }
            listBox.addView(makeRow("💿 $label    $sizeText") {
                setResult(RESULT_OK, Intent().apply { putExtra(EXTRA_PATH, full) })
                finish()
            })
        }
    }

    private fun makeRow(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(0xFF222222.toInt())
        setPadding(padDp(), 22, padDp(), 22)
        setOnClickListener { onClick() }
    }

    private fun padDp() = (14 * resources.displayMetrics.density).toInt()
}
