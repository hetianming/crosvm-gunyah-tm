package com.gunyah.launcher.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService

/**
 * v2.0.10：APP 进程内**常驻**持有到 [NativeDisplayRootService] 的 root 服务连接。
 *
 * 为什么必须常驻、且要在 VM 启动前就绑：crosvm 只在启动那一刻 connect 一次输入 socket，
 * 之后永不重连（对已运行的 VM）。root 服务进程里托管着那 4 个 unix socket
 * （[InputSocketHost]），一旦服务进程因「所有绑定都断开」被回收，fd 关闭、输入就永久死。
 * 所以这里 bind 后**从不 unbind**，跟随 APP 进程生命周期保活。
 *
 * 时序：VM 启动线程与本页 onCreate 都会尽早调 [ensureListening]；脚本 4.7 段会轮询等
 * 4 个 socket 出现作为最终同步点，双保险，不依赖哪个先执行。
 *
 * 输入字节路径：APP → [write] (AIDL writeInput) → root 服务进程 → Os.write(socket fd)
 * → crosvm --input。彻底没有 untrusted_app 直连本地 TCP 这一步（旧 touchd 桥被 SELinux
 * 拦截，是「能显示不能操作」的根因）。
 */
object InputServiceClient {

    private const val TAG = "InputServiceClient"

    @Volatile private var svc: INativeDisplayService? = null
    @Volatile private var bindIssued = false
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = binder?.let { INativeDisplayService.Stub.asInterface(it) }
            svc = s
            // 连上就立刻把 4 路 listener 建好（幂等）。此时 crosvm 通常还没启动。
            s?.let { runCatching { it.ensureInputListening() }
                .onFailure { Log.e(TAG, "ensureInputListening on connect failed", it) } }
            Log.i(TAG, "root service connected; input listeners requested")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
            // 连接意外断开：清标志，下次 ensureListening 会重新 bind
            synchronized(lock) { bindIssued = false }
            Log.w(TAG, "root service disconnected")
        }
    }

    /** 幂等。尽早调用；内部把 bind 发到主线程，异步完成不阻塞调用方。 */
    fun ensureListening(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(lock) {
            if (bindIssued) {
                // 已绑过；若服务已连上，补一次 ensure（幂等，覆盖 listener 意外丢失）
                svc?.let { runCatching { it.ensureInputListening() } }
                return
            }
            bindIssued = true
        }
        main.post {
            runCatching {
                // v1.9.82：必须用 daemon 模式。否则 APP 进程被杀（用户划掉后台）时，
                // libsu 会终止非 daemon 的 root 服务进程 → 它托管的 4 路输入 socket 全关 →
                // crosvm 只 connect 一次、挂断后永久不再读输入 → 重开 APP 显示正常但键鼠全废。
                // daemon 进程独立于 APP 存活，socket 与 crosvm 的 peer 连接都保住。
                val intent = Intent(app, NativeDisplayRootService::class.java).apply {
                    addCategory(RootService.CATEGORY_DAEMON_MODE)
                }
                RootService.bind(intent, conn)
            }.onFailure {
                synchronized(lock) { bindIssued = false }
                Log.e(TAG, "RootService.bind failed", it)
            }
        }
    }

    fun isConnected(): Boolean = svc != null

    /**
     * VM 启动前调用：发起绑定并**同步等待**到 4 路 listener 真的建好（最多 [timeoutMs]）。
     * 必须在后台线程调用（内部有 sleep），返回后脚本才 exec crosvm，保证 connect 不落空。
     * 超时也返回（脚本 4.7 段还有一轮轮询兜底），不阻塞启动流程。
     */
    fun ensureListeningAndWait(ctx: Context, timeoutMs: Long = 9000): Boolean {
        ensureListening(ctx)
        if (svc != null) {
            runCatching { svc?.ensureInputListening() }
            return true
        }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val s = svc
            if (s != null) {
                runCatching { s.ensureInputListening() }
                return true
            }
            SystemClock.sleep(60)
        }
        Log.w(TAG, "ensureListeningAndWait timed out after ${timeoutMs}ms")
        return false
    }

    /**
     * v1.9.82：从后台回到显示器时调用——无条件确认 daemon 连接并重建 4 路 listener。
     *
     * 场景：APP 被划后台杀掉后重开。daemon root 服务进程仍活着（socket 与 crosvm 的
     * peer 连接都保住了），但新 APP 进程与它之间的 binder 连接是全新的：
     *  - 若 svc 还在（同进程内切后台返回）：直接补一次 ensureInputListening（幂等）。
     *  - 若 svc 为空（进程被杀后重建）：强制清 bindIssued 重新 bind，连上后
     *    onServiceConnected 会调 ensureInputListening 重建监听。
     * 这样不用重启 VM 就能恢复键鼠。
     */
    fun reload() {
        val s = svc
        if (s != null) {
            runCatching { s.ensureInputListening() }
                .onFailure { Log.w(TAG, "reload ensure failed", it) }
            return
        }
        // 连接不在：允许重新发起 bind（可能上次 bind 卡住或进程刚重建）
        synchronized(lock) { bindIssued = false }
    }

    /** 把一段 evdev 字节交给 root 进程写入对应 socket。false = 未连接 / 该路未连 / 写失败。 */
    fun write(channel: Int, data: ByteArray): Boolean =
        runCatching { svc?.writeInput(channel, data) ?: false }
            .onFailure { Log.d(TAG, "writeInput failed: ${it.javaClass.simpleName}") }
            .getOrDefault(false)

    /** 各路 crosvm 是否已连上事件源（即时快照，来自 alive peer 判定）。 */
    fun ready(): BooleanArray =
        runCatching { svc?.getInputChannelsReady() }
            .onFailure { Log.d(TAG, "getInputChannelsReady failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: BooleanArray(4)

    /**
     * v2.0.11：root 侧输入链路真实诊断（每路 alive 连接数/累计连接/挂断/回读/收发/最后错误）。
     * 只在输入写线程里周期性调用，不占用 UI 线程。
     */
    fun diagnostics(): String =
        runCatching { svc?.inputDiagnostics() ?: "root 服务未连接" }
            .onFailure { Log.d(TAG, "inputDiagnostics failed: ${it.javaClass.simpleName}") }
            .getOrDefault("inputDiagnostics 异常")
}
