package com.gunyah.launcher.core

import android.util.Log
import com.github.luben.zstd.ZstdOutputStream
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * 把成品磁盘镜像（qcow2/raw）打包成 DroidVM 兼容的 vmpkg，便于备份/分享。
 *
 * 与 VmpkgExtractor 严格对称，格式照 DroidVM 官方 VMExportTask 复刻：
 *   24 字节头 "VMPKG\0" + manifestVersion/appCode/manifestSize/compression/volumeCount/dataSize
 *   -> 头按 0x1000 对齐 -> manifest.json（manifestSize <= 0xffff）
 *   -> 再按 0x1000 对齐 -> ZSTD 压缩 tar 流（压缩类型 3=zstd，与 DroidVM 官方默认一致）
 *   tar 第一个条目固定是 manifest.json，随后是各磁盘镜像（archive_path 与 manifest 对应）。
 *
 * v1.9.73：改用官方默认 ZSTD 压缩（实测 8GB qcow2 可压到 ~3GB）。dataSize 记的是
 * 【压缩后】tar 流的字节数；解包端 LimitedInputStream(dataSize) 后接 ZstdInputStream，
 * 与官方 PackageInput.open() 完全对称。压缩 level=3 + 4 workers（同官方 TarWriter）。
 */
object VmpkgPacker {
    private const val TAG = "VmpkgPacker"

    private const val MAGIC = "VMPKG"
    private const val HEADER_SIZE = 24
    private const val ALIGN = 0x1000
    private const val MANIFEST_VERSION = 1
    private const val APP_VERSION_CODE = 99   // 我们的 app code 不写进官方映射，填占位（解包不校验）
    private const val COMP_ZSTD = 3           // DroidVM Compression.ZSTD.type，官方默认值
    private const val ZSTD_LEVEL = 3
    private const val ZSTD_WORKERS = 4
    private const val BUFFER = 256 * 1024

    data class Result(
        val ok: Boolean,
        val message: String,
        val outPath: String = "",
        val size: Long = 0,
    )

    /** 用户可见路径 -> root 真实路径。 */
    private fun toReal(path: String): String = path.replace("/sdcard/", "/data/media/0/")
    private fun toVisible(path: String): String = path.replace("/data/media/0/", "/sdcard/")

    private fun alignUp(v: Long): Long = (v + ALIGN - 1) and (ALIGN - 1).toLong().inv()
    private fun alignUpStrict(v: Long): Long {
        val a = alignUp(v)
        return if (a == v) v + ALIGN else a
    }

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v ushr 8) and 0xff).toByte()
    }

    private fun putI64(b: ByteArray, off: Int, v: Long) {
        for (i in 0 until 8) b[off + i] = (v ushr (8 * i)).toByte()
    }

    private fun padOctal(v: Long, digits: Int): String {
        var s = java.lang.Long.toOctalString(v)
        if (s.length < digits) s = "0".repeat(digits - s.length) + s
        return s
    }

    /** 写一个 512B ustar 头（照 TarWriter.writeHeader）。 */
    private fun writeTarHeader(out: OutputStream, name: String, size: Long) {
        val hdr = ByteArray(512)
        val nb = name.toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(nb, 0, hdr, 0, minOf(nb.size, 100))
        // mode 0644 / uid 0 / gid 0，各 8 字节字段
        field(hdr, 100, 8, padOctal(0x1A4L, 7))  // 0644
        field(hdr, 108, 8, padOctal(0, 7))
        field(hdr, 116, 8, padOctal(0, 7))
        if (size > 0x1FFFFFFFFFFL) {
            // GNU base-256（>8GB 单盘少见但支持）
            hdr[124] = 0x80.toByte()
            var v = size
            for (i in 135 downTo 125) { hdr[i] = (v and 0xff).toByte(); v = v shr 8 }
        } else {
            field(hdr, 124, 12, padOctal(size, 11))
        }
        field(hdr, 136, 12, padOctal(System.currentTimeMillis() / 1000, 11))
        for (i in 148 until 156) hdr[i] = ' '.toByte()  // checksum 先空格
        hdr[156] = '0'.toByte()                          // type REG
        System.arraycopy("ustar".toByteArray(StandardCharsets.US_ASCII), 0, hdr, 257, 5)
        hdr[262] = 0
        var sum = 0
        for (b in hdr) sum += b.toInt() and 0xff
        field(hdr, 148, 8, java.lang.Long.toOctalString(sum.toLong()) + " ")
        out.write(hdr)
    }

    private fun field(hdr: ByteArray, off: Int, len: Int, s: String) {
        val b = s.toByteArray(StandardCharsets.US_ASCII)
        val n = minOf(b.size, len)
        System.arraycopy(b, 0, hdr, off, n)
        for (i in off + n until off + len) hdr[i] = 0
    }

    /** 统计实际写入字节数——ZSTD 压缩后落盘的真实长度即为 dataSize。 */
    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        private var count = 0L
        fun count(): Long = count
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        // zstd.close() 会级联调用到这里；只 flush 不关闭底层 out，
        // 否则后续写尾部对齐字节会 Stream closed。out 由外层 use{} 负责关闭。
        override fun close() = flush()
    }

    /**
     * 流式复制：src 读到 out，直到 size 字节，回调进度。
     * src 走 root openStream（/sdcard 是 FUSE，app 直接读会 EPERM）。
     */
    private fun copyEntry(
        src: InputStream,
        out: OutputStream,
        size: Long,
        onBytes: (Long) -> Unit
    ) {
        val buf = ByteArray(BUFFER)
        var rem = size
        var done = 0L
        while (rem > 0) {
            val n = src.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
            if (n <= 0) throw IOException("镜像读取提前结束，还差 $rem 字节")
            out.write(buf, 0, n)
            rem -= n
            done += n
            onBytes(done)
        }
    }

    /**
     * 主入口。srcPath 是成品镜像（用户可见 /sdcard/...，可能是 vmpkg 解出的 qcow2）。
     * outPath 是输出 vmpkg（用户可见路径，会映射 root 真实路径写）。
     * workDir 是 app 私有可写目录，用作 tar 暂存（写完 root mv 到目标）。
     */
    fun pack(
        srcPath: String,
        outPath: String,
        diskName: String,
        workDir: String,
        onProgress: ((percent: Int, text: String) -> Unit)? = null,
    ): Result = try {
        val realSrc = toReal(srcPath)
        if (!RootShell.pathExists(realSrc))
            throw IOException("找不到源镜像：${File(srcPath).name}")

        val srcSize = RootShell.fileSize(realSrc)
        if (srcSize <= 0) throw IOException("源镜像为空")

        onProgress?.invoke(2, "构建包清单…")
        // manifest 字段照官方 PackageManifest.toJson（ Gunyah 解包只读 disks，官方还读 vm/compression）
        val diskJson = JSONObject().apply {
            put("index", 0)
            put("name", diskName)
            put("format", if (diskName.lowercase().endsWith(".qcow2")) "qcow2" else "raw")
            put("size", srcSize)
            put("archive_path", "disks/$diskName")
            put("attached", true)
        }
        val disks = org.json.JSONArray().put(diskJson)
        val manifest = JSONObject().apply {
            put("manifest_version", MANIFEST_VERSION)
            put("format", "vmpkg")
            put("created_at", System.currentTimeMillis())
            put("app_version", "GunyahLauncher")
            put("app_version_code", APP_VERSION_CODE)
            put("compression", "zstd")
            put("disks", disks)
            put("boots", org.json.JSONArray())
            put("networks", org.json.JSONArray())
        }
        val manifestBytes = manifest.toString(2).toByteArray(StandardCharsets.UTF_8)
        if (manifestBytes.size > 0xffff) throw IOException("manifest 过大")

        // ---- 第一步：在 app 私有目录写完整包（头占位 + manifest + tar）----
        val realOut = toReal(outPath)
        val tmpDir = File(workDir, "vmpkg_pack_tmp").apply { if (!exists()) mkdirs() }
        val tmp = File(tmpDir, ".pack_${System.nanoTime()}.vmpkg")

        var dataSize = 0L
        FileOutputStream(tmp).use { out ->
            // 24B 头占位
            out.write(ByteArray(HEADER_SIZE))
            // 头后补齐到 0x1000
            val pad0 = alignUp(HEADER_SIZE.toLong()).toInt() - HEADER_SIZE
            if (pad0 > 0) out.write(ByteArray(pad0))
            out.write(manifestBytes)
            // manifest 后按 alignUpStrict 对齐（即使已对齐也补一整块，与官方一致）
            val afterManifest = (HEADER_SIZE + pad0 + manifestBytes.size).toLong()
            val dataStart = alignUpStrict(afterManifest)
            val pad1 = (dataStart - afterManifest).toInt()
            if (pad1 > 0) out.write(ByteArray(pad1))

            val dataStartActual = out.channel.position()

            // ---- ZSTD 压缩流：tar 内容写进压缩器，压缩后的字节才落盘 ----
            // 官方 TarWriter.wrapCompressionOutput(ZSTD)：level 3 + 4 workers，
            // 输出是单个标准 zstd 帧，任何 ZstdInputStream 都能解。
            val counting = CountingOutputStream(out)
            val zstd = ZstdOutputStream(counting, ZSTD_LEVEL).setWorkers(ZSTD_WORKERS)
            zstd.use { comp ->
                // tar: 第一个条目 manifest.json
                writeTarHeader(comp, "manifest.json", manifestBytes.size.toLong())
                comp.write(manifestBytes)
                val mp = (512 - manifestBytes.size % 512) % 512
                if (mp > 0) comp.write(ByteArray(mp))

                // tar: 磁盘镜像（root 流读取，流式写进压缩器）
                onProgress?.invoke(5, "压缩打包镜像：${File(srcPath).name}…")
                writeTarHeader(comp, "disks/$diskName", srcSize)
                RootShell.openStream(realSrc).use { ins ->
                    copyEntry(ins, comp, srcSize) { wrote ->
                        val pct = (5 + wrote * 85 / srcSize).toInt().coerceIn(5, 90)
                        val mb = wrote / 1024 / 1024
                        val totalMb = srcSize / 1024 / 1024
                        onProgress?.invoke(pct, "ZSTD 压缩中（${mb}/${totalMb}MB）…")
                    }
                }
                val dp = (512 - (srcSize % 512)) % 512
                if (dp > 0) comp.write(ByteArray(dp.toInt()))

                // tar 结尾两个 512 空块
                comp.write(ByteArray(1024))
            } // zstd.close() 落完帧尾并 flush 到 counting
            dataSize = counting.count()

            val dataEnd = out.channel.position()
            if (dataEnd - dataStartActual != dataSize)
                Log.w(TAG, "dataSize 统计偏差: ${dataEnd - dataStartActual} vs $dataSize")

            // 尾部对齐
            val tailPad = alignUp(dataEnd) - dataEnd
            if (tailPad > 0) out.write(ByteArray(tailPad.toInt()))

            out.flush()
        }
        // 回填头必须用 RandomAccessFile（append 流会强制写末尾，无法覆盖开头）
        val hdrBytes = ByteArray(HEADER_SIZE)
        val mg = MAGIC.toByteArray(StandardCharsets.UTF_8)
        System.arraycopy(mg, 0, hdrBytes, 0, mg.size)
        hdrBytes[mg.size] = 0
        putU16(hdrBytes, 6, MANIFEST_VERSION)
        putU16(hdrBytes, 8, APP_VERSION_CODE)
        putU16(hdrBytes, 10, manifestBytes.size)
        putU16(hdrBytes, 12, COMP_ZSTD)
        putU16(hdrBytes, 14, 0)
        putI64(hdrBytes, 16, dataSize)
        java.io.RandomAccessFile(tmp, "rw").use { it.seek(0); it.write(hdrBytes) }

        onProgress?.invoke(93, "写入目标位置…")
        // ---- 第二步：root mv 到目标（同分区 rename 秒完成）----
        // 目录+文件都交给 media_rw：FUSE 视图(/storage/emulated/0)下普通进程才能改写/分享。
        val realDir = File(realOut).parent ?: "/data/media/0/Download"
        val parentDir = realDir.substringBeforeLast('/')
        val script = buildString {
            append("mkdir -p '${RootShell.shEscape(realDir)}'\n")
            append("chown media_rw:media_rw '${RootShell.shEscape(parentDir)}' '${RootShell.shEscape(realDir)}' 2>/dev/null\n")
            append("chmod 0775 '${RootShell.shEscape(parentDir)}' '${RootShell.shEscape(realDir)}' 2>/dev/null\n")
            append("rm -f '${RootShell.shEscape(realOut)}'\n")
            append("mv '${RootShell.shEscape(tmp.absolutePath)}' '${RootShell.shEscape(realOut)}'\n")
            append("chown media_rw:media_rw '${RootShell.shEscape(realOut)}' 2>/dev/null\n")
            append("chmod 0666 '${RootShell.shEscape(realOut)}' 2>/dev/null\n")
        }
        val r = RootShell.exec(script, timeoutMs = 300000)
        if (!r.success) throw IOException("写入 vmpkg 失败：${r.stderr.take(120)}")

        val finalSize = RootShell.fileSize(realOut)
        if (finalSize <= 0) throw IOException("输出文件为空")

        onProgress?.invoke(100, "完成")
        Result(true, "打包完成：${File(outPath).name}", toVisible(realOut), finalSize)
    } catch (e: Exception) {
        Log.w(TAG, "vmpkg 打包失败", e)
        Result(false, "打包失败：${e.message ?: e.javaClass.simpleName}")
    }
}
