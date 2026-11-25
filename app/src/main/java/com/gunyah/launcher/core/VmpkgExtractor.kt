package com.gunyah.launcher.core

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Vector
import java.util.zip.GZIPInputStream

/**
 * DroidVM vmpkg 成品镜像解包器（只读提取，不依赖 DroidVM / daemon）。
 *
 * vmpkg 是 DroidVM 的专有封装格式：
 *   24 字节头（MAGIC="VMPKG\0" + 版本/压缩/分卷数/dataSize）
 *   -> 头按 0x1000 对齐 -> manifest.json（记录 disks 的 archive_path/name/size/format）
 *   -> 按 0x1000 对齐 -> 压缩(tar) 数据流；压缩支持 none/gzip/xz/zstd
 *   可能分卷：<base>.vmpkg 只是元数据头，数据在 <base>.vmpkg.001/.002… 子卷里。
 *
 * 本类只做用户需要的事：把包里的磁盘镜像流式解到目标目录，返回主盘路径。
 * VM 配置 / 网络 / 引导文件不考虑——GunyahLauncher 只需要一块能启动的 C 盘镜像。
 */
object VmpkgExtractor {
    private const val TAG = "VmpkgExtractor"

    private const val MAGIC = "VMPKG"
    private const val HEADER_SIZE = 24
    private const val ALIGN = 0x1000
    private const val VOLUME_SUFFIX_DIGITS = 3

    // 压缩类型（与 DroidVM Compression.type 一致）
    private const val COMP_NONE = 0
    private const val COMP_GZIP = 1
    private const val COMP_XZ = 2
    private const val COMP_ZSTD = 3

    data class DiskInfo(
        val archivePath: String,
        val name: String,
        val size: Long,
        val format: String,
        val attached: Boolean,
        val backingArchive: String,
        val index: Int,          // 对应官方 DiskRef.index；backing 镜像为 -1
    )

    data class Result(
        val ok: Boolean,
        val message: String,
        val disks: List<String> = emptyList(),  // 解压出的磁盘文件绝对路径
        val mainDisk: String = "",               // 主 C 盘（attached 中最大的）
    )

    private class Header(
        val manifestVersion: Int,
        val compression: Int,
        val volumeCount: Int,
        var dataSize: Long,
        val manifestSize: Int,
    )

    /** 供 UI 判断一个路径是否像 vmpkg（含分卷子卷 .NNN）。 */
    fun isVmpkg(path: String): Boolean {
        val f = File(path)
        val n = f.name
        // 子卷 xxx.vmpkg.001
        if (n.matches(Regex(".*\\.vmpkg\\.\\d{$VOLUME_SUFFIX_DIGITS}"))) return true
        if (n.endsWith(".vmpkg", ignoreCase = true)) return true
        // 也按文件头识别（用户可能改过后缀）
        return try {
            val b = ByteArray(MAGIC.length + 1)
            FileInputStream(f).use { it.read(b) }
            checkMagic(b)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 修正已有镜像/目录的属主，使其在 /storage/emulated/0(FUSE) 下可读写。
     * 用于历史文件：旧版解包只 chmod 没 chown，属主仍是 root，普通进程改不动。
     * 传入用户可见路径(/sdcard/...)或 root 真实路径均可。
     */
    fun fixOwnership(visiblePath: String) {
        val real = toReal(visiblePath)
        val parent = real.substringBeforeLast('/')
        runCatching {
            RootShell.exec(buildString {
                append("chown media_rw:media_rw '${RootShell.shEscape(parent)}' 2>/dev/null\n")
                append("chmod 0775 '${RootShell.shEscape(parent)}' 2>/dev/null\n")
                append("[ -f '${RootShell.shEscape(real)}' ] && {\n")
                append("  chown media_rw:media_rw '${RootShell.shEscape(real)}' 2>/dev/null\n")
                append("  chmod 0666 '${RootShell.shEscape(real)}' 2>/dev/null\n")
                append("}\n")
            }, timeoutMs = 15000)
        }
    }

    private fun checkMagic(h: ByteArray): Boolean {
        if (h.size < MAGIC.length + 1) return false
        val m = MAGIC.toByteArray(StandardCharsets.UTF_8)
        for (i in m.indices) if (h[i] != m[i]) return false
        return h[m.size] == 0.toByte()
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun i64(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xffL)
        return v
    }

    // 向上对齐到 0x1000：必须用 ~(ALIGN-1) 清低位，不能用 (ALIGN-1) 取模。
    // 之前误写成取模会把 dataStart 算成 0..4095 的余数，导致官方包解压失败。
    private fun alignUp(v: Long): Long = (v + ALIGN - 1) and (ALIGN - 1).toLong().inv()
    private fun alignUpStrict(v: Long): Long {
        val a = alignUp(v)
        return if (a == v) v + ALIGN else a
    }

    private fun readFully(ins: InputStream, buf: ByteArray) {
        var n = 0
        while (n < buf.size) {
            val r = ins.read(buf, n, buf.size - n)
            if (r < 0) throw EOFException("vmpkg 数据提前结束")
            n += r
        }
    }

    private fun skipFully(ins: InputStream, count: Long) {
        var rem = count
        while (rem > 0) {
            val r = ins.skip(rem)
            if (r > 0) rem -= r
            else if (ins.read() < 0) throw EOFException("vmpkg tar 条目提前结束")
            else rem--
        }
    }

    // ---------- root 落盘 ----------
    // /sdcard 是 FUSE 挂载点，app 进程直接写会 EPERM；必须走 root 写 /data/media/0 真实路径。
    // 与 GunyahManager.createBlankDisk / MainActivity.deleteVmpkgPackage 完全同一套映射。

    /** 用户可见路径 -> root 真实路径。 */
    private fun toReal(path: String): String = path.replace("/sdcard/", "/data/media/0/")

    /** root 真实路径 -> 用户可见路径（写进配置/启动脚本/UI 展示）。 */
    private fun toVisible(path: String): String = path.replace("/data/media/0/", "/sdcard/")

    /**
     * root 创建目录（含多级）。失败抛异常。
     * /data/media/0 经 FUSE 映射为 /storage/emulated/0；root 建的目录属主是 root，
     * 普通进程在 FUSE 视图下无法在该目录内新建/改写/删除文件，必须交给 media_rw。
     */
    private fun mkdirsViaRoot(realDir: String) {
        val parent = realDir.substringBeforeLast('/')  // /data/media/0/GunyahLauncher
        val r = RootShell.exec(buildString {
            append("mkdir -p '${RootShell.shEscape(realDir)}'\n")
            append("chown media_rw:media_rw '${RootShell.shEscape(parent)}' '${RootShell.shEscape(realDir)}' 2>/dev/null\n")
            append("chmod 0775 '${RootShell.shEscape(parent)}' '${RootShell.shEscape(realDir)}' 2>/dev/null\n")
        }, timeoutMs = 15000)
        if (!r.success) throw IOException("root 创建目录失败: $realDir（${r.stderr.take(80)}）")
    }

    /** root 判断真实文件是否存在。 */
    private fun existsViaRoot(realPath: String): Boolean = RootShell.pathExists(realPath)

    /**
     * 高速写一个镜像：先由 app 用普通 FileOutputStream 写到私有目录（真实 ext4，无跨进程），
     * 写完用 root `mv` 秒搬到真实磁盘目录。
     *
     * 慢路径对比：su cat > 每 256KB 都要 app→管道→shell→cat 跨进程同步，8.7GB 约 3.4 万次，
     * 耗时 6 分钟；本路径写盘全在本地进程内，mv 若在同一文件系统是 rename 瞬时完成，
     * 跨文件系统才退化为内核 cp（仍远快于逐块跨进程），接近 DroidVM daemon 直写速度。
     *
     * @param workDir app 私有可写目录（filesDir 下），仅作落盘暂存
     */
    private fun writeViaRoot(
        realPath: String,
        content: InputStream,
        size: Long,
        workDir: String,
        onBytes: ((written: Long) -> Unit)? = null
    ) {
        // 暂存目录由 app 自己建（root 建的目录属主是 root，app 反而无权写）
        val tmpDir = "$workDir/vmpkg_tmp"
        val tmpDirFile = File(tmpDir)
        if (!tmpDirFile.exists() && !tmpDirFile.mkdirs())
            throw IOException("无法创建暂存目录: $tmpDir")
        val tmpName = ".vmpkg_" + File(realPath).name + "." + System.nanoTime()
        val tmp = "$tmpDir/$tmpName"
        try {
            // 第一遍：app 本地顺序写（真实文件系统，无跨进程管道）—— 这是提速的关键
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(1024 * 1024)
                var rem = size
                var written = 0L
                while (rem > 0) {
                    val n = content.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                    if (n <= 0) throw IOException("镜像数据不足（还差 $rem 字节）")
                    out.write(buf, 0, n)
                    rem -= n
                    written += n
                    onBytes?.invoke(written)
                }
                out.flush()
            }
            if (RootShell.fileSize(tmp) != size)
                throw IOException("镜像暂存写入校验失败（${File(realPath).name}）")
            // 第二遍：root mv 秒搬（同分区 rename；跨分区自动退化为 cp）
            // 关键：mv 后必须 chown media_rw:media_rw，否则文件属主是 root，
            // 通过 /storage/emulated/0(FUSE) 只能读不能改写；只有 /data/media/0 能写。
            val script = buildString {
                append("rm -f '${RootShell.shEscape(realPath)}'\n")
                append("mv '${RootShell.shEscape(tmp)}' '${RootShell.shEscape(realPath)}'\n")
                append("chown media_rw:media_rw '${RootShell.shEscape(realPath)}' 2>/dev/null\n")
                append("chmod 0666 '${RootShell.shEscape(realPath)}' 2>/dev/null\n")
            }
            val r = RootShell.exec(script, timeoutMs = 600000)
            if (!r.success) throw IOException("root 搬运镜像失败: ${r.stderr.take(120)}")
        } finally {
            // 异常时清掉可能残留的暂存文件（root 可见）
            runCatching { RootShell.exec("rm -f '${RootShell.shEscape(tmp)}'", timeoutMs = 10000) }
        }
        val actual = RootShell.fileSize(realPath)
        if (actual != size)
            throw IOException("镜像写入校验失败（${File(realPath).name}: $actual != $size）")
    }

    /** 把任意选中路径映射为元数据主文件（去掉 .NNN 子卷后缀）。 */
    private fun masterOf(path: String): String {
        val n = File(path).name
        return if (n.matches(Regex(".*\\.\\d{$VOLUME_SUFFIX_DIGITS}")))
            path.substring(0, path.length - VOLUME_SUFFIX_DIGITS - 1)
        else path
    }

    private fun parseHeader(bytes: ByteArray): Header {
        if (bytes.size < HEADER_SIZE || !checkMagic(bytes))
            throw IOException("不是有效的 vmpkg 文件（magic 不符）")
        return Header(
            manifestVersion = u16(bytes, 6),
            compression = u16(bytes, 12),
            volumeCount = u16(bytes, 14),
            dataSize = i64(bytes, 16),
            manifestSize = u16(bytes, 10),
        )
    }

    /** 去掉 JSON 前后的 NUL/空白（某些包 manifest 尾部带对齐零）。 */
    private fun jsonText(bytes: ByteArray): String {
        var s = String(bytes, StandardCharsets.UTF_8)
        s = s.trim('\u0000', '\uFEFF', ' ', '\n', '\r', '\t')
        return s
    }

    private fun parseManifest(bytes: ByteArray): List<DiskInfo> {
        val json = JSONObject(jsonText(bytes))
        val arr = json.optJSONArray("disks") ?: return emptyList()
        val out = ArrayList<DiskInfo>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                DiskInfo(
                    archivePath = o.optString("archive_path"),
                    name = o.optString("name", "disk.img"),
                    size = o.optLong("size"),
                    format = o.optString("format", "raw"),
                    attached = o.optBoolean("attached", true),
                    backingArchive = o.optString("backing_archive", ""),
                    index = o.optInt("index", 0),
                )
            )
        }
        return out
    }

    private fun wrapCompression(ins: InputStream, type: Int): InputStream = when (type) {
        COMP_NONE -> ins
        COMP_GZIP -> GZIPInputStream(ins, 64 * 1024)
        COMP_XZ -> org.tukaani.xz.XZInputStream(ins)
        COMP_ZSTD -> com.github.luben.zstd.ZstdInputStream(ins)
        else -> throw IOException("不支持的 vmpkg 压缩类型: $type")
    }

    /**
     * 主入口。srcPath 是用户选中的文件（可能是 master 或某个子卷）。
     * destDir 解压目标目录（用户可见路径，会映射为 root 真实路径并 mkdirs）。
     * workDir 是 app 私有可写目录（filesDir.absolutePath），用作高速落盘暂存。
     */
    fun extract(
        srcPath: String,
        destDir: String,
        workDir: String,
        onProgress: ((percent: Int, text: String) -> Unit)? = null
    ): Result = try {
        val master = masterOf(srcPath)
        val masterFile = File(master)
        if (!masterFile.isFile) throw IOException("找不到 vmpkg 主文件: ${masterFile.name}")

        // destDir 是用户可见路径(/sdcard/...)，root 写必须落到 /data/media/0 真实路径
        val realDir = toReal(destDir)
        mkdirsViaRoot(realDir)

        onProgress?.invoke(3, "读取 vmpkg 头部…")
        // 读主文件头（master 一般很小，直接读）
        val headBytes = ByteArray(HEADER_SIZE)
        FileInputStream(masterFile).use { readFully(it, headBytes) }
        val header = parseHeader(headBytes)

        onProgress?.invoke(8, "解析包清单…")
        // manifest 固定在文件绝对偏移 0x1000 处。这里是【新开的流】，必须从 0 跳到 4096；
        // 官方写法是 skip(4096-24) 因为它复用已读完 header 的同一个流——别照搬，否则错位 24B。
        val manifestStart = ALIGN.toLong()
        val manifest = ByteArray(header.manifestSize)
        FileInputStream(masterFile).use { ins ->
            skipFully(ins, manifestStart)
            readFully(ins, manifest)
        }
        val disks = parseManifest(manifest)
        if (disks.isEmpty()) throw IOException("vmpkg 里没有磁盘镜像")

        // 构造数据区字节流：单文件直接读 master 的数据段；多卷拼接子卷
        onProgress?.invoke(12, "准备数据流（压缩类型 ${
            when (header.compression) {
                COMP_GZIP -> "gzip"
                COMP_XZ -> "xz"
                COMP_ZSTD -> "zstd"
                else -> "none"
            }
        }）…")
        val logical: InputStream
        val dataSize: Long
        if (header.volumeCount > 0) {
            val set = openMultiVolume(masterFile, header)
            logical = set.first
            dataSize = set.second
        } else {
            val dataStart = alignUpStrict(manifestStart + header.manifestSize)
            val f = FileInputStream(masterFile)
            skipFully(f, dataStart)
            logical = f
            dataSize = header.dataSize
        }

        val raw = LimitedInputStream(logical, dataSize)
        val tar = wrapCompression(raw, header.compression)
        // 记录 (镜像信息 -> 实际落盘路径)，主盘据此精确选取，避免文件名 -N 后缀导致误判
        val placed = ArrayList<Pair<DiskInfo, String>>()
        var done = 0
        val total = disks.size
        // 按"已写字节"推进进度：单盘包也能实时看到进度，不再卡在 15% 不动。
        val totalBytes = disks.sumOf { it.size.coerceAtLeast(0) }.coerceAtLeast(1)
        var writtenBytes = 0L
        var lastPct = -1

        onProgress?.invoke(15, "开始解出 $total 块磁盘镜像…")
        TarReader(tar).forEach { name, _mode, size, content ->
            val disk = disks.firstOrNull { it.archivePath == name }
            if (disk == null) {
                // 非磁盘条目（boot/其它），跳过
                skipFully(content, size)
                return@forEach
            }
            val outName = uniqueName(realDir, disk.name)
            val realOut = "$realDir/$outName"
            val baseBytes = writtenBytes
            writeViaRoot(realOut, content, size, workDir) { wroteThis ->
                writtenBytes = baseBytes + wroteThis
                val pct = (15 + writtenBytes * 70 / totalBytes).toInt().coerceIn(15, 85)
                if (pct != lastPct) {
                    lastPct = pct
                    val mb = writtenBytes / 1024 / 1024
                    val totalMb = totalBytes / 1024 / 1024
                    onProgress?.invoke(pct, "解出 ${outName}（${mb}/${totalMb}MB）…")
                }
            }
            writtenBytes = baseBytes + size
            placed.add(disk to toVisible(realOut))
            done++
        }

        if (placed.isEmpty()) throw IOException("vmpkg 里没有解出任何磁盘镜像")
        raw.drain()

        // 官方 VMImportTask.relinkBackingChains()：把每个 overlay 的 qcow2 backing_file
        // 重新指向包内一起解出的父盘（导出时 header 里写的是导出手机上的路径，已失效）。
        // 手机上没有 qemu-img，这里直接改写 qcow2 头里的 backing filename 为父盘文件名
        // （与 overlay 同目录，相对路径，crosvm 可解析）。
        onProgress?.invoke(90, "重连 qcow2 磁盘链…")
        relinkBackingChains(placed)

        val main = pickMain(placed)
        onProgress?.invoke(95, "完成，共解出 $done 块镜像")
        Result(
            ok = true,
            message = "已解出 $done 块镜像：${placed.joinToString("、") { File(it.second).name }}",
            disks = placed.map { it.second },
            mainDisk = main,
        )
    } catch (e: Exception) {
        Log.w(TAG, "vmpkg 解压失败", e)
        val msg = e.message ?: e.javaClass.simpleName
        // 清理可能产生的半成品：只在明显是本次新建的空目录且失败时提示，不主动删用户文件
        Result(false, "vmpkg 解压失败：$msg")
    }

    /**
     * 官方 relinkBackingChains()：对每个带 backing_archive 的 overlay，把它的 qcow2
     * backing filename 改成同目录里父盘的文件名。header 只有字符串需要改，数据不动。
     */
    private fun relinkBackingChains(placed: List<Pair<DiskInfo, String>>) {
        // archive_path -> 实际落盘路径
        val byArchive = placed.associate { it.first.archivePath to it.second }
        for ((disk, path) in placed) {
            if (disk.backingArchive.isEmpty()) continue
            val parentPath = byArchive[disk.backingArchive]
            if (parentPath == null) {
                Log.w(TAG, "缺少 backing 父盘 ${disk.backingArchive}（${disk.archivePath} 需要它）")
                continue
            }
            val parentName = File(parentPath).name
            val ok = rewriteQcow2Backing(path, parentName)
            Log.i(TAG, "backing 重连 ${File(path).name} -> $parentName: $ok")
        }
    }

    /**
     * 改写 qcow2 v2/v3 头里的 backing filename。成功返回 true；非 qcow2 / 无 backing /
     * 空间不足返回 false（不破坏文件）。qcow2 头：magic(0) version(4) backing_file_offset(8,8B)
     * backing_file_size(16,4B)。字符串写在 backing_file_offset 处，改短后补零。
     *
     * 全部走 root：app 进程无权读写 /data/media/0 真实路径。读头用 su cat（只读前若干字节
     * 即 close），写回用 su + base64|dd 原地覆盖字符串区，不动镜像数据区。
     */
    private fun rewriteQcow2Backing(visiblePath: String, newName: String): Boolean {
      return try {
        val realPath = toReal(visiblePath)
        // 读头部 72 字节（root 流，读完即 close 结束 cat 进程）
        val h = ByteArray(72)
        RootShell.openStream(realPath).use { ins -> readFully(ins, h) }
        if (be32(h, 0) != 0x514649FBL) return false          // "QFI\xfb"
        val ver = be32(h, 4)
        if (ver != 2L && ver != 3L) return false
        val off = be64(h, 8)
        val oldSize = be32(h, 16)
        if (off <= 0 || oldSize <= 0 || oldSize > 65536) return false
        val nameBytes = newName.toByteArray(StandardCharsets.UTF_8)
        if (nameBytes.size > oldSize) {
            Log.w(TAG, "backing 新名过长（${nameBytes.size} > $oldSize），跳过重连: $newName")
            return false
        }
        // 构造替换区：新名 + 补零到原长度，base64 经 shell 传递避免 NUL/转义问题
        val payload = ByteArray(oldSize.toInt())
        System.arraycopy(nameBytes, 0, payload, 0, nameBytes.size)
        val b64 = Base64.getEncoder().encodeToString(payload)
        val script = "printf '%s' '$b64' | base64 -d | " +
            "dd of='${RootShell.shEscape(realPath)}' bs=1 seek=$off conv=notrunc 2>/dev/null"
        RootShell.exec(script, timeoutMs = 15000).success
      } catch (e: Exception) {
        Log.w(TAG, "改写 qcow2 backing 失败", e)
        false
      }
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xff) shl 24) or ((b[o + 1].toLong() and 0xff) shl 16) or
            ((b[o + 2].toLong() and 0xff) shl 8) or (b[o + 3].toLong() and 0xff)

    private fun be64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[o + i].toLong() and 0xff)
        return v
    }

    /** 选主盘：严格照官方 VMImportUtils.remapDiskPaths——attached=true 中 index 最小者。 */
    private fun pickMain(placed: List<Pair<DiskInfo, String>>): String {
        val attached = placed.filter { it.first.attached }
        val pool = if (attached.isNotEmpty()) attached else placed
        // 老包所有 index 都是 0，稳定排序保持原打包顺序（placed 即 tar 顺序）
        return pool.minByOrNull { it.first.index }?.second ?: ""
    }

    /** 生成不覆盖已有文件的安全文件名（root 真实路径判重）。 */
    private fun uniqueName(realDir: String, rawName: String): String {
        var name = File(rawName).name.ifBlank { "disk.img" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        var i = 2
        while (existsViaRoot("$realDir/$name")) {
            val dot = name.lastIndexOf('.')
            name = if (dot > 0) "${name.substring(0, dot)}-$i${name.substring(dot)}" else "$name-$i"
            i++
        }
        return name
    }

    /**
     * 多卷：读 master 里的 VolumeIndex，拼接 <base>.NNN 子卷，**只返回数据区字节流**。
     * 注意：header/manifest 已由 extract() 单独解析过，这里绝不能再把 masterHead
     * 拼进流里——否则解压器会把文件头当作压缩数据（官方 VolumeSet 返回完整流后会
     * 交给 PackageInput.open 重新跳过头部，本类不走那条路径，故只需纯数据）。
     */
    private fun openMultiVolume(master: File, header: Header): Pair<InputStream, Long> {
        val all = master.readBytes()
        if (all.size < HEADER_SIZE) throw IOException("vmpkg master 过小")
        val dataStart = alignUpStrict(ALIGN.toLong() + header.manifestSize)
        if (all.size < dataStart) throw IOException("vmpkg master 被截断")
        val indexJson = jsonText(all.copyOfRange(dataStart.toInt(), all.size))
        val idx = JSONObject(indexJson)
        if (idx.optString("magic") != "VMPV") throw IOException("vmpkg 卷索引 magic 不符")
        if (idx.optInt("version", 1) != 1) throw IOException("不支持的 vmpkg 卷版本")
        val dataSize = idx.optLong("data_size")
        val vols = idx.optJSONArray("volumes") ?: throw IOException("vmpkg 卷索引为空")
        if (vols.length() != header.volumeCount)
            throw IOException("vmpkg 分卷数不符（头 ${header.volumeCount} / 索引 ${vols.length()}）")

        val streams = Vector<InputStream>()
        var total = 0L
        val base = master.absolutePath
        for (i in 0 until vols.length()) {
            val v = vols.optJSONObject(i) ?: continue
            val expectSize = v.optLong("size")
            val sub = File("$base.${String.format("%0${VOLUME_SUFFIX_DIGITS}d", i + 1)}")
            if (!sub.isFile()) throw IOException("缺少分卷: ${sub.name}")
            if (sub.length() != expectSize)
                throw IOException("分卷 ${sub.name} 大小不符（${sub.length()} != $expectSize）")
            total += expectSize
            streams.add(BufferedInputStream(FileInputStream(sub), 64 * 1024))
        }
        // 数据区总长必须与卷索引声明一致；多出的按尾部对齐零填充补齐（解压流会忽略）。
        if (total != dataSize)
            throw IOException("vmpkg 分卷总长不符（$total != $dataSize）")
        val tailPad = alignUp(dataSize) - dataSize
        if (tailPad > 0) streams.add(ZeroInputStream(tailPad))
        return Pair(SequenceInputStream(streams.elements()), dataSize)
    }

    // ---------- 内部类 ----------

    private class LimitedInputStream(private val src: InputStream, private var remaining: Long) : InputStream() {
        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = src.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
            if (n > 0) remaining -= n
            return n
        }

        fun drain() {
            while (remaining > 0) {
                val r = src.skip(remaining)
                if (r > 0) remaining -= r
                else if (src.read() < 0) break
                else remaining--
            }
        }
    }

    private class ZeroInputStream(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining > 0) { remaining--; 0 } else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val n = minOf(len.toLong(), remaining).toInt()
            java.util.Arrays.fill(b, off, off + n, 0.toByte())
            remaining -= n
            return n
        }
    }

    /** 极简 ustar tar 读取：逐条目回调，支持 GNU base-256 大尺寸、长名 'L' 扩展。 */
    private class TarReader(private val ins: InputStream) {
        fun forEach(handler: (name: String, mode: Int, size: Long, content: InputStream) -> Unit) {
            while (true) {
                val hdr = readBlock() ?: return
                var name = cstr(hdr, 0, 100)
                var type = hdr[156].toInt() and 0xff
                var size = parseOctal(hdr, 124, 12)
                if (type == 'L'.code) {
                    val nb = ByteArray(size.toInt())
                    readFullyLocal(nb)
                    skipPad(size)
                    name = String(nb, StandardCharsets.UTF_8).trim()
                    val h2 = readBlock() ?: return
                    size = parseOctal(h2, 124, 12)
                    type = h2[156].toInt() and 0xff
                }
                if (type != '0'.code && type != 0) {
                    skipFullyLocal(size)
                    skipPad(size)
                    continue
                }
                val limited = LimitedInputStream(ins, size)
                handler(name, 0, size, limited)
                limited.drain()
                skipPad(size)
            }
        }

        private fun readBlock(): ByteArray? {
            val buf = ByteArray(512)
            var n = 0
            while (n < 512) {
                val r = ins.read(buf, n, 512 - n)
                if (r < 0) {
                    if (n == 0) return null
                    throw EOFException("tar 头不完整")
                }
                n += r
            }
            if (buf.all { it == 0.toByte() }) return null
            return buf
        }

        private fun readFullyLocal(buf: ByteArray) {
            var n = 0
            while (n < buf.size) {
                val r = ins.read(buf, n, buf.size - n)
                if (r < 0) throw EOFException("tar 长名数据不完整")
                n += r
            }
        }

        private fun skipFullyLocal(count: Long) {
            var rem = count
            while (rem > 0) {
                val r = ins.skip(rem)
                if (r > 0) rem -= r
                else if (ins.read() < 0) throw EOFException("tar 数据提前结束")
                else rem--
            }
        }

        private fun skipPad(size: Long) {
            val pad = (512 - (size % 512)) % 512
            if (pad > 0) skipFullyLocal(pad)
        }

        private fun cstr(b: ByteArray, off: Int, len: Int): String {
            var e = off
            while (e < off + len && b[e] != 0.toByte()) e++
            return if (e == off) "" else String(b, off, e - off, StandardCharsets.UTF_8)
        }

        private fun parseOctal(b: ByteArray, off: Int, len: Int): Long {
            if (b[off].toInt() and 0x80 != 0) {
                var v = b[off].toLong() and 0x7f
                for (i in off + 1 until off + len) v = (v shl 8) or (b[i].toLong() and 0xff)
                return v
            }
            val s = cstr(b, off, len).trim()
            if (s.isEmpty()) return 0
            return try { s.toLong(8) } catch (e: NumberFormatException) { 0 }
        }
    }
}
