# Tailcat Manager

管理多个独立 **Tailcat → ADB** 会话的图形界面，三端同源：**Windows 桌面端**、**Android 手机端**，
以及仅作参考的 macOS 原版。

[Tailcat](https://github.com/tailscale/tailcat) 是 Tailscale 官方的「会打洞的 netcat」：服务端跑
`tailcat serve 5555` 拿到一串 `tc...` 地址（端口被代理到本机 localhost 的同号端口），客户端跑
`tailcat forward <tc地址> <本地端口>:5555` 就能在本地起一个监听。本应用把这条链路和后续的
ADB 操作（`adb connect 127.0.0.1:<端口>`、装 APK、推拉文件、shell、屏幕控制）收在一个界面里，
让 NAT 后面的车机 / 电视 / 盒子变成一条普通的远程 ADB 会话。

> ⚠️ `serve` 的参数**只能是端口号 / 端口区间 / 服务名**（v0.7.0 实测；上游主分支 README 里
> `5555:10.2.200.213:5555` 那种带主机映射的写法在这个版本会报 `is not a known named service`）。
> 想暴露别的端口就写 `tailcat serve 5555 8080`。

**当前状态**：Windows 端与 Android 端均可用。

- **Windows**：完整管理端 —— 设备管理、连接链路、shell、装包、推拉文件（含**整目录递归**）、
  可达性批量测试、屏幕控制（拉起外部 `scrcpy.exe`）、托盘与全局快捷键、缺失组件一键安装。
- **Android**：**双角色**。服务端角色把自己的 ADB 通过 Tailcat 暴露出去（含固定地址、二维码、
  前台服务保活、开机自启）；管理端角色连到别的设备上跑 shell / 装包 / 推拉文件（含**整目录**）/
  扫码添加设备 / **屏幕控制** —— 后者不依赖外部 scrcpy，是**在应用内自己实现 scrcpy 协议 +
  MediaCodec 硬解**。命令与任务输出合并在一个 **Shell 窗口**里（见
  [Shell 窗口](#shell-窗口并非持久-shell)）。
- **macOS 原版**（`legacy-macos/`）：仅作参考，不参与构建。

---

## 项目结构

Kotlin Multiplatform + Compose Multiplatform。

```
core/                   共享逻辑（commonMain 纯 Kotlin，无 java.* / JNA / android.*）
  model/                设备、会话、任务、日志
  creds/                凭据脱敏与输出过滤
  proc/                 命令抽象、超时与取消
  adb/                  AdbClient 抽象 ← 多端的关键抽象
  transport/            Tailcat 转发通道与端口占用
  config/               配置模型与地址簿
  session/              SessionManager 状态机
  reach/                批量可达性测试
  tools/                工具组件与查找/安装接口
  jvmMain/ androidMain/ 仅承载 nowMillis() 的 actual

desktop/                Windows 桌面端（Compose Desktop）
  win/                  DPAPI 凭据、隐藏控制台、winget 安装、托盘、原生对话框…
  ui/                   侧栏 + 设备详情 + 对话框

android-shell/          Android 手机端，两个角色共用一个应用
  MainActivity/AppRoot  顶部两个页签：服务端 / 设备
  TailcatServer/Service 服务端角色：起停 tailcat serve、固定地址、二维码、前台服务、开机自启
  manager/              管理端角色：设备列表与详情、编辑器、扫码添加
  screen/               屏幕控制：scrcpy 服务端推送、协议、MediaCodec 硬解、触摸注入、悬浮球
  platform/             子进程与 native 库运行时、Android Keystore 凭据、SAF

legacy-macos/           原 macOS 版 Swift 源码，仅作参考，不参与构建
```

`core` 同时声明 jvm 与 android 两个 target，是让**编译器强制**守住 commonMain 的平台无关边界
——一旦有人把 `java.*` / JNA / `android.*` 写进共享代码，构建立刻失败。管理端的会话状态机
（`SessionManager`）、ADB 客户端抽象、转发通道、任务管线都在这里，**桌面端与 Android 端共用同一份**。

---

## 构建与运行

前置：JDK 17+（本机用 Android Studio 自带的 JBR 21 编译）、Android SDK（仅 Android target 需要）。

```sh
./gradlew :desktop:run                    # 直接运行
./gradlew :desktop:run --args="--demo"    # 演示模式：内存配置 + 模拟命令，不碰真实设备与凭据
./gradlew :core:jvmTest :desktop:test     # 行为测试（49 项）
./gradlew :desktop:packageAppImage        # 打包成自带运行时的 Windows 应用

./gradlew :android-shell:assembleDebug    # 手机端
./gradlew :android-shell:assembleRelease  # 手机端（签名发布包）
```

debug 包的 applicationId 带 `.debug` 后缀（`cc.coauto.tailcat.manager.debug`），可以和 release 包
**同时装在测试机上**，不用先卸载 release。它同时保持 debuggable —— 排查进程类问题时要靠
`run-as <包名> <命令>` 以应用自己的 uid 执行操作（例如对应用进程单独发 `SIGKILL`，
release 包不是 debuggable，`run-as` 会直接拒绝）。

打包产物：`desktop/build/compose/binaries/main/app/TailcatManager/TailcatManager.exe`

**Android 发布签名**：密钥**故意放在仓库外**（默认 `../tailcat-signing/keystore.properties`，
可用 `-Ptailcat.signing=<路径>` 或环境变量 `TAILCAT_SIGNING` 指向别处）。`.gitignore` 里也兜底
排除了 `keystore.properties` / `*.jks` / `*.p12`。那个密钥是应用的**身份** —— 丢了就再也无法给
已安装的版本做升级（新包签名不同，覆盖不上去）。

> 属性文件里的 `storeFile` 路径含中文时**必须用 UTF-8 的 Reader 读**（`build.gradle.kts` 已如此）。
> `Properties.load(InputStream)` 按 ISO-8859-1 解码，会把路径读成乱码，然后报一个和签名毫无
> 关系的「文件不存在」。

> release 构建关掉了 lint（`lint { checkReleaseBuilds = false }`）：AGP 8.13.2 自带的 lint 用
> Kotlin 2.2 的元数据读取器，读不了本项目 Kotlin 2.4.21 编出的 `.kotlin_module`，
> `lintVitalRelease` 必然失败。这是工具链版本不匹配，不是代码问题。

> **本机注意事项**：项目路径含中文（`...\魔改apk\tailcat-tool`），而 JDK 的 `jlink` 经由
> 响应文件接收非 ASCII 输出路径时会失败（报 `directory already exists`，但该目录并不存在）。
> Compose 插件固定用 `@argsfile` 调 jlink，无法从 Gradle 侧改掉，因此根 `build.gradle.kts`
> 在检测到非 ASCII 路径时把构建产物重定向到 `%LOCALAPPDATA%\TailcatManager\build`。
> 源码位置不受影响；把项目挪到纯 ASCII 路径即可恢复默认行为。

本机没有 WiX，所以只能产出 app-image（免安装目录），无法产出 MSI/exe 安装包。产物未做代码签名，
首次运行会有 SmartScreen「未知发布者」提示。

---

## 使用

1. 在目标设备上跑 `tailcat serve 5555`（要暴露多个端口就 `serve 5555 8080`），拿到 `tc` 开头的地址。
2. 应用里「添加设备」填入该地址；本地端口留空则从 5556 起自动分配，已有连接保持。
3. 点「连接」。应用会 `tailcat forward` 建本地监听，再 `adb connect 127.0.0.1:<端口>`。
4. 在线后即可装 APK、上传下载、执行 shell、开 scrcpy 屏幕，或开一个 `cmd` 窗口跑 adb。
5. 「临时连接」建立的会话只存在于内存中，不写配置、不写凭据；可以「保存到我的设备」转正，
   转正时当前的 UUID、通道和正在执行的任务全部保持不变。

每次 `tailcat forward` / `tailcat ping` 都显式带 `--key=tailcat-tool-<设备 id>-<connection|probe>`，
让**每台设备有一个稳定的客户端身份**：重连沿用同一个 node key，对端始终看到同一个节点，
DERP 路径也不必每次重新协商。不指定 `--key` 时 tailcat 会去用已落盘的默认客户端密钥
（那是**所有进程共享**的，两个并发会话就会抢同一条 DERP 身份），没有则退回临时密钥
（每次连接换一个身份）—— 两种情况都不是我们要的。连接与探测用两个**不同**的身份，
且同一台设备的探测会排队执行（同一个 node key 不能同时给两个 ping 进程用）。
密钥文件由 tailcat 自己写在 `$CONFIG/tailcat/keys/` 下并自己管权限，本应用只负责给名字。

工具栏「测试全部设备」批量 ping 所有设备的 Tailcat 可达性（每台 5 秒超时、并发 3 台），
显示延迟与走的是直连还是 DERP 中继。它不创建转发、不执行 adb connect/disconnect，
所以**未收到 pong 并不代表已有 ADB 离线**。

关闭主窗口后连接继续运行，从系统托盘重新打开；托盘菜单也可断开全部连接或退出。
托盘里每条临时设备还有一个「关闭临时设备」——主窗口收进托盘后，那是唯一能清掉临时设备的入口。

**上传支持整个目录**：选中的目录会被 `adb push` 递归推到远端同名子目录下；远端路径以 `/` 结尾
时放进该目录，否则按完整路径处理。

Windows 端用原生文件对话框直接选目录。Android 端走 SAF 的 `OpenDocumentTree` 选目录树 ——
拿到的是树 Uri，所以先用 `DocumentFile` 递归遍历、在应用缓存里镜像一份（空子目录也保留），
再**一次性** `adb push` 整个目录（`adb push` 本身就支持目录递归）。镜像是为了只付一条 adb 命令
的代价，代价则是占双份磁盘，所以设了 2000 个文件的上限兜底。

菜单栏（`ComposeWindow.setJMenuBar`，Swing 实现，所以 Win32 的 `GetMenu` 看不到它）：

| 快捷键 | 动作 |
|---|---|
| `Ctrl+N` / `Ctrl+Shift+N` | 添加设备 / 临时连接 |
| `Ctrl+I` | 导入地址簿 |
| `Ctrl+T` | 测试全部设备 |
| `Ctrl+,` | 工具设置 |
| `Ctrl+Shift+M` | 显示主窗口 |
| `Ctrl+Q` | 退出 |

菜单项自己不改状态，统一通过 `ManagerCommands` 这条单向命令流把请求发给 `ManagerScreen`
（所有对话框状态都 `remember` 在那边，和窗口级的菜单栏不在同一棵组合树里）。

ADB 掉线后会自动恢复：前 6 次按指数退避快速重试，用尽后**不放弃**，转为每 30 秒一次的慢速
后台重探。这一点很重要——「问题已被外部修好」本地是感知不到的（比如在手机上补开了
ADB over TCP，或手机端换过配置后重启了服务端），一旦停手就再也不会自己恢复。
待授权的设备不走这条路，那要等设备侧点授权，重试没有意义。

### 新电脑上从零配起来

不需要预先装任何东西——Android SDK 没装、`adb` 没有、连 winget 都没有，也能一键配好。
每个缺失组件按**三级降级**处理，任一级成功即止：

1. **磁盘上已经能找到** → 直接配置（配置里的路径失效、但工具其实装了的情况）；
2. **包管理器（winget）** → 用户级、静默、非交互，不弹 UAC；
3. **官方直链下载** → 下载官方发布包、解压到 `%LOCALAPPDATA%\TailcatManager\tools\<组件>\`。

第 2 级失败**不抛错**，而是降到第 3 级；两级都断了才算这个组件失败。侧栏会列出缺哪些组件并给出
「一键安装缺失组件」，没装包管理器时会说明「将直接从官方地址下载」，不必先去装 winget。

直链用的是上游正式发布地址：

| 组件 | 地址 |
|---|---|
| Tailcat | `github.com/tailscale/tailcat/releases/…/tailcat_0.7.0_windows_amd64.zip` |
| ADB | `dl.google.com/android/repository/platform-tools-latest-windows.zip`（Google 的「最新版」，永远指向当前稳定版） |
| scrcpy | `github.com/Genymobile/scrcpy/releases/download/v4.1/scrcpy-win64-v4.1.zip` |

压缩包**整包解压**而不是只挑出可执行文件：scrcpy 依赖随包的 DLL 和 `scrcpy-server`，
platform-tools 依赖 `AdbWinApi.dll`，只复制 exe 出来跑不起来。解压时还会剥掉所有条目的**公共顶层目录**
（platform-tools 的包多一层 `platform-tools/`，scrcpy 多一层 `scrcpy-win64-vX.Y/`，而 tailcat 是平铺的），
剥完之后三者的可执行文件都落在目标目录根部。

> 这三个下载路径有实测覆盖（`WindowsArchiveFetcherTest`），但**默认跳过**——
> 用 `./gradlew :desktop:test -Dtailcat.networkTests=true` 才跑，免得常规测试被网络拖累。
> 它的价值在于：上游一旦改了资产名或目录结构，它会立刻失败，而那正是这条自助路径唯一的外部依赖。

### 在 Android 手机上跑服务端

调试时常常需要把手机自己当成被连接的一端。手机上放一个 tailcat 二进制即可：

```sh
# 1. 取 arm64 静态二进制（v0.7.0 起官方 Linux 二进制可直接在 Android 上运行）
curl -LO https://github.com/tailscale/tailcat/releases/download/v0.7.0/tailcat_0.7.0_linux_arm64.tar.gz
tar -xzf tailcat_0.7.0_linux_arm64.tar.gz

# 2. 推到设备（/data/local/tmp 是 userdata，不动系统分区）
adb push tailcat /data/local/tmp/tailcat
adb push scripts/android/ct.sh /data/local/tmp/ct.sh
adb shell "chmod 755 /data/local/tmp/tailcat /data/local/tmp/ct.sh"

# 3. 让 adbd 监听 TCP，否则没有可暴露的端点
adb tcpip 5555

# 4. 起服务端并把本机 5555 暴露出去，打印 tc 地址
adb shell "sh /data/local/tmp/ct.sh start"
```

之后在桌面端「添加设备」里填这个 `tc` 地址，本地端口留空即可。

`ct.sh` 还支持 `stop` / `status` / `addr` / `log`；`start` 后面可以跟别的端口号（比如
`ct.sh start 8080` 就暴露手机上的 8080）。地址每次启动都是新的临时密钥。

> ⚠️ **`adb tcpip 5555` 会让 adbd 绑定 `0.0.0.0:5555`，同局域网内可见。**
> adb 仍需 RSA 密钥授权才能连上（`ro.adb.secure=1`），不是完全敞开，但这确实扩大了你手机的
> 攻击面。不需要时用 `adb usb` 还原。
>
> ⚠️ **tc 地址本身就是凭据**（内含 WireGuard 预共享密钥），拿到它就能连上你的隧道。
> 不要贴到聊天群、issue、日志或博客里。临时密钥在服务端进程退出后即失效。

### Android 手机端（`android-shell`）—— 服务端角色

上面那套要敲 adb 命令才能开关，`android-shell` 把它变成一个手机上的应用：一键起停、
直接看到并复制 tc 地址、二维码（地址 150+ 字符，手敲不现实；对面可以**扫码填入**，
见 [扫码添加设备](#扫码添加设备)）、常驻通知里也显示地址、
可自定义要暴露的端口、日志落盘可回看、可开机自启。

**壳会主动检测「被暴露的端口后面到底有没有服务」**。这是最容易踩、又最难自己发现的坑：
隧道建得起来不代表对端有东西，如果你的端口后面是空的（最典型的是没开 ADB over TCP——
`service.adb.tcp.port` 没设，adbd 就不在 5555 上），电脑端会一直停在「ADB 尚未上线」，
而手机上看起来一切正常。做法是每 3 秒对每个端口发起一次 TCP 连接（没人监听会立刻被拒），
状态卡里直接标 ✓ / ⚠，5555 还额外给出指引。

**地址有两种生命周期，这是个要主动选的坑**：

- **临时密钥（默认）**：每次启动生成新密钥，进程退出地址即作废。安全，但**服务端每重启一次，
  地址就变一次，电脑端存的那个就失效了** —— 表现为电脑端「本地通道运行中，ADB 尚未上线」，
  因为 PC 侧的 forward 是个独立进程，它不知道对端已经换了地址，照样在本地监听。
- **固定地址（长期密钥）**：打开壳里的这个开关后，会先用
  `tailcat genkey --key=default --fixed-region` 落盘一个持久密钥，之后 `serve` 自动复用它，
  地址跨重启不变，电脑端只需填一次。关掉开关时 `serve` 会显式带 `--key=new` 走临时密钥，
  但**不会删除**已落盘的密钥，所以再打开能拿回同一个地址。

> ⚠️ 打开固定地址的代价：地址从「一次性」变成**长期有效的凭据**。临时密钥在服务端退出时即失效，
> 持久的不会——你曾经把它给过谁，谁就能在以后任何时候连进来。别贴到任何公开或半公开的地方。

**关键难点是 Android 的 W^X 限制**：targetSdk ≥ 29 的 App **不能 `execve()` 自己可写数据目录里的
文件**（SELinux 对 `app_data_file` 拒绝 `execute_no_trans`），所以「解压到 filesDir 再执行」这条路是死的。

走通的是另一条：**把二进制当作 native library 打包**。APK 里的 `lib/arm64/*.so` 安装时会被提取到
`ApplicationInfo.nativeLibraryDir`，那里的文件 SELinux 类型是 `apk_data_file`，策略明确允许
`untrusted_app` 对它 `execute`。而 tailcat 是**静态链接**的，没有共享库依赖，可以直接执行，
不需要建 rootfs 符号链接。落地要点：

- 二进制放 `src/main/jniLibs/arm64-v8a/libtailcat.so` —— **文件名必须以 `lib` 开头、`.so` 结尾**，
  否则 PackageManager 不会提取它。Gradle 会自动从官方 release 下载，不入库（见 `android-shell/build.gradle.kts`）。
- 必须开 `packaging { jniLibs { useLegacyPackaging = true } }`（等价于 `extractNativeLibs=true`）。
- 实测环境：Android 17 / API 37，进程域 `untrusted_app_34`，直接执行返回 `v0.7.0`。

两个踩过的坑：

- **`/system/bin/linker64 <二进制>` 这条路对 tailcat 无效**。那个办法只适用于 ET_DYN(PIE)，
  而静态 Go 二进制是 ET_EXEC，linker64 会报 `has unexpected e_type: 2`。别拿它当兜底。
- **必须给子进程设 `HOME` / `XDG_CONFIG_HOME`**。App 进程默认没有 `HOME`，而 Go 的
  `os.UserConfigDir()` 在两者都为空时直接报错退出：`neither $XDG_CONFIG_HOME nor $HOME are defined`。
  壳把它们指到应用私有目录。

#### 管理端：手机也能去管别的设备

应用顶部是两个入口：**服务端**（上面那套，把自己的 ADB 暴露出去）和**设备**（连到别的设备的
Tailcat 服务端去管它）。管理端复用 `core` 里桌面端那一整套会话状态机 / ADB 客户端 / 任务管线，
平台差异全部走 `PlatformServices` 注入，所以 `SessionManager` 一行都没改。

难点只有一个：**Android 上没有 adb**，而管理端整套逻辑是围绕 adb 命令行写的。在进程内重新实现
一遍 ADB 协议代价太大，所以干脆**自带一份 arm64 的 adb**，和 tailcat 走同一条路
（当作 native library 打包 → 提取到 `nativeLibraryDir` → 那里允许 `execve`）。

adb 不是单文件程序，它依赖 abseil / protobuf / brotli / lz4 / zstd / z / libc++ 一大串共享库，
而且这些库在 Linux 下的名字带版本号（`libzstd.so.1`），而 **PackageManager 只解压名字形如
`lib*.so` 的文件**。解法是打包时统一叫 `<真名>.so`，运行时再在 `filesDir` 下按真名建一套符号链接，
把那个目录放进 `LD_LIBRARY_PATH`（末尾补 `:/system/lib64`，让 libc / libdl / libm 走设备自己的）。
真名取 ELF 的 SONAME —— 它就是链接时写进 `DT_NEEDED` 的那个字符串。

只取 adb 的**依赖闭包**（58 个库，用 `readelf -d` 算传递闭包得到），少一个不行、多一个白占体积；
`libc/libdl/liblog/libm` 一律用设备自己的。

> ⚠️ **16 KB 页大小对齐** —— 踩过，也是换掉第一版 adb 的唯一原因。
>
> Android 15 起对 `targetSdk 35+` 的应用强制要求 native 库按 16 KB 对齐。不满足时：
> 设备页大小是 4 KB（绝大多数）的话，系统只开个兼容模式并**弹一个「不兼容」提示**
>（logcat 里是 `NativeLibraryHelper: Setting page size compat mode PAGE_SIZE_APP_COMPAT_FLAG_ELF_NOT_ALIGNED`），
> App 照常能用；但**页大小真是 16 KB 的设备上，这些库会直接加载失败**。
>
> 关键是**不能靠改 `p_align` 糊过去**：16 KB 要求 `vaddr ≡ offset (mod 16384)`，
> 而 4 KB 链接出来的库通常是 `vaddr - offset == 0x1000`，硬改会让动态链接器算出非页对齐的
> mmap 偏移，产出一个加载不了的坏文件 —— 这种文件在 4 KB 设备上照样跑，只在 16 KB 设备上炸，
> 属于最难查的一类。**必须换真正重链接过的产物。**
>
> 现在用的是 **Termux 的 `android-tools`**（NDK 构建、链接 bionic，实测 `align=0x4000`）。
> 选它的理由：AOSP 自己不发 arm64 的 adb；Debian/Ubuntu 的 arm64 包链接 glibc，在 Android 上根本起不来；
> 最初的 `nightmare-space/adb_kit` v1.5.4（2024-10）全部是 4 KB 对齐，且上游没有更新版本。
> 构建脚本会逐个校验每个 `.so` 的 `p_align` **和** vaddr/offset 同余，不满足就直接让构建失败 ——
> 这条检查比事后在设备上发现要便宜得多。

其余实现说明：

- 界面用 [MIUIX](https://github.com/miuix-kotlin-multiplatform/miuix)（`top.yukonga.miuix.kmp:miuix`），
  MIUI/HyperOS 风格。CSS 里没有的东西这里也不硬造——卡片、开关、按钮、小标题都用它自带的组件。
- **修过的一个显示 bug**：`targetSdk 35+` 在 Android 15 及以上是**强制 edge-to-edge** 的，内容会画到
  状态栏和导航栏底下。修复是在根布局上加 `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)`。
  实测：标题从被压在状态栏里的 y=48 变成 y=156（= 状态栏 120 + 12dp 内边距 36），滚到底部
  也没有元素越过导航栏的 2420 边界。
- 主题用 `Theme.DeviceDefault.NoActionBar`：MIUI 风格没有系统标题栏，留着会和界面自己的标题重复、
  还白占 56dp 高度。
- 过渡：`animateContentSize` / `AnimatedVisibility` / `Crossfade` / 动画圆点，统一 320ms 时长。
- 前台服务用 `specialUse` 类型（`dataSync` 在 Android 15 起有 6 小时上限，而隧道本来就该一直开着）；
  开机自启受 Android 12+ 后台启动前台服务的限制，可能要先解锁一次，失败会记进日志而不是假装成功。

> ⚠️ 构建时排除了 `material3-window-size-class`。原因很怪但值得记下来：那个 AAR 的包名是
> `androidx.compose.material3.windowsizeclass`，AGP 会按包名在 Gradle 转换缓存里建目录，而**本机环境
> 拒绝创建这个特定的目录名**（同一目录下其它任何名字都能建）。已核实 MIUIX 从未引用它的类
> （`classes.jar` 里 0 处引用），该 AAR 也不含任何资源，排除掉没有损失。

> 构建注意：Compose 版本由 `composeBom = 2026.02.00`（Compose 1.10.3）钉住，而这个 BOM
> 恰好就是 MIUIX 0.8.8 依赖的那一档。Compose 1.12.x 起要求 `compileSdk 37`，而本机 SDK
> 只装到 platform 36，所以不能跟着 `compose = "1.12.1"`（那是跨平台 Compose 的版本）走。
> 装了 platform 37 之后可以把这个约束放开。

---

## Android 端的实现要点

### 界面：底部四档导航 + 分组设置式列表

界面按 **KernelSU 管理器**的形态重排过（不是抄它的代码 —— 它是自定义 Compose 设计系统、
GPL-3.0；这里是**量它的几何再自己实现**：`screencap` 抓图 + `uiautomator dump` 拿控件 bounds，
按密度换算成 dp，深浅两套配色各采一次）。

- **导航**：底部四项 —— 首页 / 设备 / 服务端 / 设置。首页给出「服务端是否在跑」「连了几台」
  和最近任务；工具路径这类运维信息收进设置。
- **几何全部走令牌**（`android-shell/.../ui/Dimens.kt`）：页边距 12dp、卡片间距 12dp、圆角 12dp、
  卡片内边距 16dp、行高 73dp、行首图标 24dp。之前每个界面各写一套 padding（10/12/16/24 混用），
  圆角与边距看着乱就出在这里。**新代码不要在调用处写裸 dp。**
- **组件层**（`ui/Components.kt`）：只有 `GroupCard`（一卡多行，行间无分隔线）、`SettingRow`
  （图标 + 标题 + 副标题 + 尾部：箭头/开关/值/按钮）、`SectionHeader`、`BigTitleBar`、`StateDot`。
- **配色**（`ui/TailcatTheme.kt`）：固定色板，**关掉 MIUIX 的 monet**（默认会从系统壁纸取色，
  同一界面在不同手机上完全是两个样子，这正是一开始「看着乱」的根源），同时关掉超椭圆圆角。
  主色是钢蓝，整套界面里唯一的暖色留给错误态。
- **图标**：`res/drawable/ic_*.xml` 里 14 个 24dp 线性图标（设备/服务端/设置/终端/上传/下载/投屏 …），
  统一黑色描边、由 `Icon(tint = …)` 着色，深浅色与选中态共用一份资源。

### 屏幕控制：在应用内自己实现 scrcpy 客户端

Android 端**不调用外部 scrcpy**（那是桌面程序，依赖桌面显示）。它自带一份官方 scrcpy 服务端推到
被控设备上跑，自己在应用内说协议：

```
push scrcpy-server.jar → adb forward → app_process 起服务端 → 连两条 socket → MediaCodec 硬解 → SurfaceView
```

- **服务端**用官方 release 的 `scrcpy-server`（Apache-2.0），构建期下载并校验 SHA-256；版本串
  必须与客户端**一字不差**（当前 `5.0.1`），不等服务端直接抛异常退出。
- **协议层**在 `core/screen/ScrcpyProtocol.kt`（纯字节、可单测），握手与解复用逐条对着
  v5.0.1 源码核实过。
- **解码层**移植自 `Miuzarte/ScrcpyForAndroid`（Apache-2.0），见 `THIRD_PARTY_NOTICES.md`。
  那里最有价值的设计是**起始包缓存与重放**：服务端每次 reset 都会重发 session packet，把
  config + 关键帧 + 后续若干帧缓存下来，解码器重建后立刻回放，不用干等下一个关键帧。
- **触摸**按控件尺寸换算成视频帧坐标发回。`screen_size` 必须**精确等于**视频尺寸，否则服务端的
  `PositionMapper` 会**静默丢弃**该事件 —— 没有任何报错，表现就是「点了没反应」。

两个真机踩出来的坑（都写进了代码注释）：

- **主线程不能做网络 I/O**。触摸是在 Compose 的 `pointerInput` 回调（主线程）里发的，直接对
  socket 阻塞写会抛 `NetworkOnMainThreadException`。这个异常由 BlockGuard 用**无参构造**抛出、
  `getMessage()` 是 null，于是日志里只剩一句「发送触摸失败：null」；而服务端**一个字节都没收到**，
  连它那条「位置事件尺寸不匹配，忽略」都不会打。修法是给控制通道一个专用单线程写队列
  （顺带保证按下→移动→抬起不乱序）。
- **收尾要杀干净**。服务端是跑在**被控设备**上的独立进程，只停本机那条 `adb shell` 不够 ——
  App 被系统杀掉或覆盖安装时连接是硬断的，服务端收不到任何信号，会一直留在那边占着硬件编码器。
  所以退出/挂起时按 `scid` 精确 `pkill`（不误伤并发会话），且收尾**幂等**（`onDispose` 与读流
  线程的失败路径会同时打到收尾）。

**宿主机手势要做预期管理**：`setSystemGestureExclusionRects` 的排除高度被系统**封顶在 200dp**，
而底部上滑回桌面**根本不可排除**（官方给的唯一出路是沉浸模式）。所以是两条腿 —— 沉浸模式 +
左右边缘各排除 200dp 降低误触，另外在悬浮球里给「返回 / 桌面 / 多任务」三个键**发给被控端执行**；
手势挡不住，那就用按钮把同一件事做掉。

### 扫码添加设备

对端「服务端」页上的地址有 150+ 字符，手敲到另一台手机上不现实，所以表单里有「扫码填入」：
**CameraX 取帧 + 已有的 `zxing.core` 解码**。

用 CameraX 而**不用 ML Kit**：后者带 native 库，而这个项目已经被 16KB 页对齐坑过一次（见上文
adb 那节）；CameraX 是纯 Java/AndroidX，没有原生库，而 zxing 本来就在依赖里。代价是包大了约 3.6MB。

一个容易写错的地方：必须按 **`rowStride` 而不是 `width`** 构造 `PlanarYUVLuminanceSource`
—— 相机的行宽是对齐过的，按 width 切会让每行数据错位，表现是「图很清晰，就是扫不出来」。

### Shell 窗口（并非持久 shell）

设备详情页的「打开 Shell 窗口」把**命令输入**和**所有本机任务**（装包 / 上传 / 下载 / shell）
的输出合成一份连续的终端式日志：`$ 命令` 回显、输出正文、`已完成 — <任务名>` 收尾行按先后顺序
排在一起。详情页上原来那份「本机任务」列表因此去掉了 —— 它和这个窗口是重复的。

> 取消运行中的任务仍在**设备列表页**的「任务」区块（跨设备的全局视图，运行中的任务那里有「取消」）。

**它不是持久 shell，这是有意的取舍。** core 的进程抽象（`RunningProcess`）只有
`pid` / `isRunning` / `stop()`，**没有 stdin**，做不到「指着一个长驻 `adb shell` 一直写」。
要做得给进程层加 stdin 并在两端实现，而且：不加 pty 就没有提示符与行编辑（但有 stdin 时 `cd`、
`export` 这类状态是能保住的）；加了 `adb shell -t` 拿到 pty，设备端会吐出一堆光标定位转义序列，
那就必须配一个真正的终端模拟器才看得懂 —— Termux 的那套是 GPL-3.0，不能引进这个 MIT 项目。
对「查属性 / 列包 / dumpsys / am / input」这类设备调试来说，一次性命令够用。

实现上有一点要注意：任务的输出是**增量**贴进日志的（记录每个任务已消费到的长度，只追加新增部分）。
core 会把任务输出裁到末尾 32768 字符，一旦发生裁剪，已记录的下标就失效了 —— 所以那里单独判了
「变短」的分支并整段重放，而不是让下标错位或越界。另外「已收尾」用的是**另一个**集合而不是拿长度
字段兼职当哨兵，否则下一轮会把哨兵值当成索引、把整段输出重放一遍。

---

## Windows 端的实现要点

| 机制 | 做法 | 原因 |
|---|---|---|
| 凭据存储 | **DPAPI**（`CryptProtectData`）加密整份 `secrets.dat` | 免提示，因此可以整块去掉 macOS 版那个要校验签名的凭据助手子进程；`CredWrite` 的 blob 有大小上限，而 tc 地址按规范可长到 8192 字符 |
| 配置位置 | `%LOCALAPPDATA%\TailcatManager\` | 不用会漫游的 `%APPDATA%`：DPAPI 主密钥实际不随漫游迁移，跨机解密会失败且报错晦涩 |
| 组件安装 | `winget`（`Tailscale.tailcat` / `Genymobile.scrcpy`），用户级、静默 | app-image 没有安装器，无法申请提权 |
| 工具查找 | 直接扫目录，不用 PATH | winget 装完的别名要重启 shell 才进 PATH，本进程永远看不到 |
| 黑框抑制 | 启动时给本进程分配一个**隐藏控制台**，子进程继承它 | GUI 子系统进程启动控制台子进程时，Windows 会为每个子进程新建可见控制台窗口；JDK 的 `ProcessImpl` 不传 `CREATE_NO_WINDOW` |
| 单实例 | 实例目录下的 `instance.lock` **文件锁**（`FileChannel.tryLock`） | 进程退出（含被强杀）时由内核自动释放，没有陈旧锁，且是纯 Java API，不必为读 `GetLastError` 去和 JNA 的调用约定纠缠。**Windows 上跨进程冲突抛的 `IOException` 而不是返回 `null`**，别当成「拿到锁了」 |
| 第二次启动 | 第二个实例往实例目录写 `activate.request` 后退出；已有实例按 0.5s 轮询消费它并 `visible = true` + `isMinimized = false` | 不直接在第二个进程里 `SetForegroundWindow`：那是绕过 Compose 去显示别人的原生窗口，对方状态机里的 `visible` 还是 false，下次关闭就再也收不回去 |
| 目录选择 | Swing `JFileChooser(DIRECTORIES_ONLY)` | AWT `FileDialog` 在 Windows 上不能选目录 |
| 终端 | `cmd.exe /k "<adb> -s <serial> shell"` | 本机没有 Windows Terminal；注意 `cmd` 的引号规则与 POSIX 不同，单独写了 `CmdQuoter` |
| 对话框 | 一律套 `DialogPanel`（不透明 `Surface`） | Compose Desktop 的 `Dialog` 是**独立窗口、本身没有背景**；内容不自己兜底就会直接叠在父窗口上，字和底下的界面糊在一起看不清 |
| 地址簿导入 | 只读，并拒绝 `users` 是**符号链接**的情况 | 与 macOS 版 `Persistence.swift` 的 `rejectSymlink(users)` 对齐。根目录由用户在设置里指定、允许是链接，但里面的 `users` 必须是真目录，否则一个链接就能把读取重定向到任意位置 |

---

## 相对原 macOS 版的范围变化

1. **移除「CLI 外部连接」观测**。原实现依赖 `/bin/ps` 与 `/usr/sbin/lsof` 去识别 macOS/Linux 上
   以 `socat` 搭建的 `tailcat-adb` 桥；Windows 上既没有该 CLI，纯 Java 也拿不到「端口 → PID」归属
   （需要 JNA `GetExtendedTcpTable` 或再起一个 `netstat` 进程），是投入产出比最低的一块。
   侧栏的「CLI 外部 N」计数与相关界面一并移除。
2. 不做「唤醒后重检」（Windows 无对应通知），依赖原有的轮询兜底。
3. ADB 服务器沿用共享的 5037 端口（与原版一致，Android Studio 能看到同一批设备）；
   `ProcessAdbClient` 已预留 `serverPort` 参数，将来可切到私有端口。
4. 「探测链路」「刷新信息」「导入地址簿」等其余功能全部保留。

（各端共同的缺口见文末「已知缺口」。）

---

## 验证

```sh
./gradlew :core:jvmTest :desktop:test     # 行为测试（49 项）
./gradlew :desktop:run --args="--demo"    # 演示模式
```

分布：`core` **43** 项、`desktop` **6** 项。

测试覆盖：双设备隔离、断开时先摘 ADB 入口再释放端口、任务在启动时固定 serial（期间另一台设备
连上也不改指）、待授权保留通道、连接被取消时不泄漏本地监听、重连先取消旧任务、转发进程意外退出
只影响该设备、临时设备纯内存且不碰持久凭据、转正保持会话与在跑的任务、转正失败回滚凭据、
重复导入不覆盖 GUI 编辑且配置文件中不含凭据；另有凭据脱敏（含跨读取分片的凭据不泄漏）、
转发就绪输出解析（含 CRLF）、`adb devices -l` 解析，以及 `desktop` 侧官方压缩包解压与
Windows 文件安全。

**scrcpy 线上格式**专门有一套：12 字节包头的位域解析、session / config / keyframe 三个标志、
PTS 的 61 位掩码、session packet 的宽高、SPS/PPS 切分，以及触摸（32 字节）、滚动（21 字节）、
按键（14 字节）、返回（2 字节）四条消息**逐字节**比对官方 C 客户端
（`app/tests/test_control_msg_serialize.c`）的测试向量。这一层错了在真机上的表现是花屏或者
「点了没反应」，两种都不报错，只能靠肉眼反查，所以值得钉死。

---

## 已知缺口

- **崩溃 / 强杀后的孤儿进程**：三端情况并不相同。
  - **Windows：确实是洞，已修**。正常退出会断开连接并回收子进程树，但强制结束或崩溃时回收代码
    根本没机会跑，留下的 `tailcat` / `adb` / `scrcpy` 会一直占着端口，下次启动撞上「端口被占用」。
    现在每个子进程在 `ProcessBuilder.start()` 之后立刻纳入一个
    `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` 的 Job Object（`desktop/.../win/ProcessJob.kt`），
    应用进程消失时由内核连带回收。`ProcessJobTest` 的三个用例真的起进程、真的关句柄，
    并且用 `IsProcessInJob` 验证了对照组与实验组的差异。
  - **Android：没有这个洞，平台已经替我们做了**。每个应用进程位于独立 cgroup
    （`/sys/fs/cgroup/apps/uid_<uid>/pid_<pid>`，cgroup v2），fork 出来的子进程继承同一个
    cgroup；AMS 移除应用进程时走 `Process.killProcessGroup`，把整棵子树收掉 ——
    子进程即使已被 init 收养（PPID=1）也一样被带走。实测四种结束方式
    （`am force-stop`、`am crash`、`am kill`、以及借 `run-as` 对应用进程单独发 `SIGKILL`）
    **都没有留下任何同 uid 进程**；其中 adb 守护进程是最难缠的形态（自我 detach 成 PPID=1），
    照样一起消失。`StaleProcessReaper` 只是兜底，正常情况下一次都不会触发。
  - **Android 端 scrcpy 服务端是另一回事**：它跑在**被控设备**上，本机 cgroup 管不到它。
    目前靠 `ScreenSession` 收尾时按 scid `pkill`；应用被强杀时它会留在被控端直到下次连接。
- **Android 端没有手动「导入地址簿」入口**，只在首启静默导入一次。
- **三端都没有主题切换与多语言**，界面字符串硬编码中文。

---

## 两个留档的判断错误

> **「普通 Android 应用不能 spawn 二进制」** —— 早期据此认为 Tailcat 得用 gomobile 编成 AAR、
> ADB 得用 Kotlin 重说协议。**这个前提是错的。** targetSdk ≥ 29 的应用确实不能 `execve()`
> 自己可写数据目录里的文件，但 `ApplicationInfo.nativeLibraryDir`（SELinux 类型
> `apk_data_file`）里的文件可以。所以只要把二进制按 `lib*.so` 打包进 jniLibs 就能直接执行 ——
> tailcat 和 adb 都是这么跑的，`ForwardTransport` / `AdbClient` 一行都没改。

> **「设备的编码器不稳、反复重启采集会话」** —— 曾经把服务端日志里刷屏的
> `Capture/encoding error` 归因于设备。用官方 PC 客户端连**同一台设备**实测：该错误 **0 次**，
> 而用完全相同参数跑官方客户端也是 0 次 —— 也就是说那些报错是**客户端自己造成的**。
> 更深一层：那串错误其实是「有人反复请求 reset」的下游症状（`mediaCodec.reset()` 才是
> `Pending dequeue output buffer request cancelled` 的来源）。教训有两条 ——
> 一是别拿单一现象反推硬件结论；二是**「被控端＝本机」的自环验证是无效的**（镜像无限嵌套必然
> 收敛成近黑），必须用真正的外部设备。

---

## 许可

源码采用 [MIT License](LICENSE)。`legacy-macos/resources` 中的官方 Tailcat 猫图及其衍生应用图标
保留上游 BSD 3-Clause 许可，详见 [第三方素材声明](THIRD_PARTY_NOTICES.md)。

三端用的是同一张图：Windows 端的 `.ico` 与 Android 端的自适应图标都由
`legacy-macos/resources/AppIcon.png` 生成（`desktop/icons/GenerateIcon.java` 与
`android-shell/icons/GenerateIcon.java`），只做缩放与留白，不重绘。
