# GunyahVM（GunyahLauncher）

> 在安卓手机上，用高通 Gunyah 虚拟化 + crosvm 运行 **Windows 11 ARM64 / Windows PE / Linux** 的启动器。

`GunyahVM` 是一个面向高通平台的安卓虚拟机 APP。它把 crosvm、UEFI 固件、内核模块等一整套工具链打包下载到手机上，配合大页内存与原生显示，直接在手机里启动 Windows To Go、PE 维护盘或 Linux 发行版。vmpkg 备份格式与 DroidVM 官方兼容（ZSTD 压缩），可导入、导出成品镜像。

- **包名**：`com.gunyah.launcher`
- **当前版本**：`1.9.81`（versionCode 137）
- **架构**：仅 `arm64-v8a`
- **最低安卓版本**：Android 9（API 28）

---

## 一、功能一览

### 1. 环境与运行状态
首页顶部一张状态卡，集中显示：内核版本、ROOT 权限、`/dev/gunyah` 设备、大页池容量、工具链是否就绪、内核模块加载数（N/M）、虚拟机运行状态。绿色即就绪，缺项会直接告诉用户缺什么、点哪里修。

### 2. 工具链（一键下载 + 完整性自愈）
- 在线下载 `unixnu-bin.zip`（约 11MB），内含 `crosvm`、UEFI 固件（`edk2-gunyah.fd` / `vars.fd`）、显示库与内核模块。
- 多线路测速选最快（直连 + gh-proxy / ghfast.top / ghproxy.net / ghproxy.cc 多个加速线路）。
- 下载后**严格校验全部核心文件**，缺任意一个（固件 / `.so` / `modules.ko`）一律判失败并引导重新下载，杜绝"提示下载失败却能启动"或"判就绪却启动即失败"。
- 每 8 秒后台复核本地真实状态，工具链被误删也能自愈。

### 3. 大页内存（4K 屏 + 5G 连续页）
- 自动加载 `gh_hugepage_reserve` 内核模块，在开机早期预留连续 2MB 大页，避免 VM 卡在 `MEM_ACCEPT`。
- 内存池目标下限自动设为 **5G**，无需用户手动计算；一键"设池并重启"。
- 模块被看门狗锁定时提供"一键修复"，多数情况无需重启。

### 4. 磁盘工具 / 系统盘
| 能力 | 说明 |
|---|---|
| 导入 PE 镜像 | 选择本地 `.qcow2` / `.img` / vmpkg 解包结果作为 PE 启动盘 |
| 导入成品镜像 | 导入已装好系统的 qcow2，直接进系统 |
| 新建安装盘 | 用 `qemu-img` 创建 8~512GB 空白 qcow2，作为装系统目标 |
| 打包备份 | 把成品 qcow2 压缩成 **vmpkg**（ZSTD），保存到 `/storage/emulated/0/GunyahLauncher/disks/` |
| 解包 vmpkg | 解出成品镜像并自动修正属主，可直接改写 |
| 导入 / 移除光盘 ISO | 挂载安装 ISO，装完系统可只取消挂载、保留文件 |
| 设为 C 盘 / 切线 | 指定从哪个盘启动，切换启动目标 |

启动目标二选一：**优先 C 盘（进系统）** 或 **优先 PE（装系统）**。

### 5. vmpkg 备份格式（兼容 DroidVM 官方）
- 文件头 `VMPKG\0`（24 字节）+ `manifest.json` + ZSTD 压缩的 tar 流。
- 压缩类型为 `3`（与 DroidVM `Compression.ZSTD` 默认值一致），`dataSize` 回填压缩后字节数。
- 实测 8GB qcow2 可压到约 3GB、13GB 压到约 6GB，便于备份与分享。
- 打包 / 解包双向支持，落盘自动 `mkdir -p` + `chown media_rw:media_rw` + `chmod 0666`。

### 6. 显示模式
| 模式 | 说明 |
|---|---|
| **固件帧缓冲（simplefb）** | 最稳，兼容性最好，推荐首选 |
| **GPU 3D · DRM** | 3D 图形加速通路，需在系统内装好显卡驱动才能生效（Linux 可装 `mesa-guest.deb`） |

分辨率支持：手机默认、平板横屏 16:10（`1920x1200`）预设，以及自定义宽高（`480~3840`，重启 VM 生效）。

### 7. 原生显示与输入
- crosvm 原生显示直通（不走 VNC），APP 自带 `VmDisplayActivity` 显示器。
- 内置键盘，无需系统输入法；同时支持切换宿主机输入法。
- 鼠键桥接：触摸板模式、软件光标、按键映射；修复了"退出再回来鼠键失灵"的问题。
- 原生显示 root 服务绑定 4 路输入 socket，画面与触控坐标自动适配横竖屏。

### 8. 启动控制台
点"启动"立即进入控制台，实时滚动 `boot.log`：UEFI 初始化 → 加载系统 → 进入显示器。自动检测 UEFI 帧缓冲并切入显示器；内存不足时自动降级并震动提示。

### 9. 网络
内置 `gunytap` + `gunydhcpd`，为 guest 提供桥接网络与 DHCP。

### 10. 其他
中英双语界面、拟态玻璃主题、按键震动反馈、device owner 屏幕固定与熄屏控制。

---

## 二、运行环境要求

| 项 | 要求 |
|---|---|
| 设备 SoC | 高通骁龙平台，需支持 Gunyah 虚拟化（已在骁龙 8 Elite 等较新机型验证） |
| 系统 | Android 9+（API 28+），**必须已 ROOT**（Magisk / KernelSU 等） |
| 内核 | 需可加载 `gh_hugepage_reserve` 与 gunyah 相关内核模块 |
| 内存 | 建议 8GB+ 物理内存（VM 目标 5G 大页 + 系统自身开销） |
| 存储 | 成品 qcow2 镜像通常 8~20GB，需预留充足空间 |

> 非高通平台或未提供 Gunyah 支持的机型无法启动 crosvm。

---

## 三、快速开始（使用说明）

1. **装 APP**：安装本仓库 Releases 里的 `GunyahVM-v1.9.81.apk`（已用发布签名，可直接覆盖安装旧版）。
2. **授权 ROOT**：首次打开授予 ROOT 权限。
3. **下载工具链**：首页"工具链"卡片点"下载"，等测速完成、下载并解压到全部核心就绪。
4. **准备大页**：点"安装大页池模块"→"设池并重启"；手机重启后再进 APP，确认大页池显示已就绪（5G）。
5. **选启动目标**：
   - 装系统：先"导入 PE 镜像"和"新建安装盘"，设为"优先 PE 启动"。
   - 直接进系统：把成品 qcow2 设为 C 盘，选"优先 C 盘启动"。
6. **启动**：点"启动"，在控制台看日志，UEFI 起来后自动进显示器。
7. **备份**：进"系统盘"→"打包备份"，把成品盘压成 vmpkg 备份。

显示异常优先切回"固件帧缓冲（simplefb）"；要用 3D 再切 GPU DRM 并确认 guest 已装显卡驱动。

---

## 四、从源码构建

### 环境
| 项 | 版本 |
|---|---|
| JDK | 17 |
| Android SDK | platform 36 + build-tools |
| Android NDK | `26.1.10909125` |
| Gradle Wrapper | 8.11.1（已随仓库提供） |

### 步骤
```bash
# 1) 配置本机 SDK 路径（local.properties 不入库）
echo "sdk.dir=/你的/Android/Sdk" > local.properties

# 2) Debug 包（无需签名）
./gradlew assembleDebug

# 3) Release 包（需要签名，见下）
./gradlew assembleRelease
```

产物位于 `app/build/outputs/apk/release/app-release.apk`（本仓库配置下实际输出到 `/tmp/gunyabuild/app/outputs/apk/release/`）。

### Release 签名
Release 构建**强制校验签名**，缺少 keystore 或签名属性会直接失败，不会产出未签名包。配置方式：

```bash
cp gradle.properties.example gradle.properties
# 编辑 gradle.properties，填入 RELEASE_STORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
# 把发布用 keystore 放到 keystore/release.jks
```

> `gradle.properties`、`keystore/`、`*.jks`、`*.apk` 均已加入 `.gitignore`，私钥与本地签名配置不会被提交到仓库。

---

## 五、项目结构

```
GunyahLauncher/
├── app/
│   ├── src/main/java/com/gunyah/launcher/
│   │   ├── MainActivity.kt              # 主界面、磁盘/工具链/大页/显示模式入口
│   │   ├── VmDisplayActivity.kt         # 原生显示屏、输入与画面适配
│   │   ├── BootActivity.kt              # 启动控制台、实时 boot.log
│   │   ├── FilePickerActivity.kt        # 内置文件选择器
│   │   └── core/
│   │       ├── GunyahManager.kt         # 工具链下载/解压/校验、启动脚本、crosvm 参数
│   │       ├── VmpkgPacker.kt           # vmpkg 打包（ZSTD）
│   │       ├── VmpkgExtractor.kt        # vmpkg 解包
│   │       ├── Qcow2Converter.kt        # qemu-img 建盘/转换
│   │       ├── NativeInputBridge.kt / ImeKeyBridge.kt / TouchBridge.kt  # 输入
│   │       ├── EvdevEncoder.kt / KeyCodeMapper.kt / InputSocketHost.kt  # 输入编码
│   │       ├── NativeDisplayRootService.kt  # 原生显示 root 服务
│   │       └── RootShell.kt / HttpUtil.kt / BootLog.kt / ...            # 基础能力
│   ├── src/main/aidl/                   # AIDL 接口（显示配置、特权服务）
│   └── src/main/assets/                 # gunytap / gunydhcpd / touchd / memlock-runner
├── native/                              # C / Rust 原生小工具源码
├── third_party/terminal-emulator/       # Termux 终端模拟器
├── build.gradle.kts / settings.gradle.kts
└── gradle.properties.example            # 签名配置模板（不含真实密码）
```

---

## 六、常见问题

- **启动卡在 `MEM_ACCEPT`**：大页池没备好。装大页模块并设池到 5G 后重启手机。
- **提示工具链不完整**：按卡片右侧"重新下载"，等全部核心校验通过再启动。
- **DRM 模式花屏**：先在系统内装好显卡驱动；不稳定就切回 simplefb。
- **vmpkg 解包后不能改写**：确认解包目录在 `/storage/emulated/0/GunyahLauncher/disks/`（真实路径 `/data/media/0`），APP 已自动修正属主。
- **`/storage` 下无法写入**：不要用 FUSE 挂载点直接写，改用 APP 内的磁盘工具，它会落到正确路径。

---

## 七、致谢与许可

本项目依赖并整合了多个开源组件，各自遵循其原始许可：

- [libsu](https://github.com/topjohnwu/libsu)（Apache-2.0）
- [zstd-jni](https://github.com/luben/zstd-jni)（BSD-2-Clause）、[XZ for Java](https://tukaani.org/xz/java.html)（0BSD）
- [hiddenapibypass](https://github.com/LSPosed/HiddenApiBypass)（Apache-2.0）
- [Termux Terminal Emulator](https://github.com/termux/termux-app)（GPL-3.0）
- crosvm / UEFI edk2 等工具链二进制来自各自上游发行版

vmpkg 格式参考 [Droid-VM](https://github.com/Droid-VM) 官方实现。Windows、骁龙、Gunyah 等商标归各自权利人所有。各原生子模块的许可文件见 `native/` 下对应目录。
