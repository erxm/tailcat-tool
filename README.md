# Tailcat Manager for Windows

Compose Desktop 设备侧栏应用，管理多个独立 Tailcat → ADB 会话。由 macOS 版（SwiftUI）移植而来，
共享逻辑收在 Kotlin Multiplatform 的 `core` 模块里。面向 Windows 10 及以上 64 位系统。

发行形态是 `app-image` 目录，不是安装包；本机没有 WiX，也没有代码签名，首次运行会出现
SmartScreen「未知发布者」提示。Tailcat、ADB 和 scrcpy 由本机工具、winget 或应用内的官方直链下载提供。

macOS 版见 [上游仓库](https://github.com/suyunkai/tailcat-tool)。

项目源码采用 [MIT License](LICENSE)。应用图标沿用 macOS 版的同一张原图（由官方 Tailcat 猫图衍生），
该素材保留上游 BSD 3-Clause 许可，不属于本项目的 MIT 授权范围；本项目是独立 GUI 项目，上游名称
未被用于声称获得 Tailscale 或其贡献者的认可。详见[第三方声明](THIRD_PARTY_NOTICES.md)。

## 构建和启动

需要 JDK 21 或以上（打包环节要**完整的 JDK**——Android Studio 自带的 JBR 是精简运行时，没有
`jpackage.exe`，只有编译用的工具链能从它取）。已验证 Kotlin 2.4.21 + Compose Multiplatform 1.12.1
+ Gradle 9.5.1。

```bat
gradlew.bat :desktop:packageAppImage
```

产物在 `desktop\build\compose\binaries\main\app\TailcatManager\`，双击其中的 `TailcatManager.exe` 启动。

也可以打成安装包：

```bat
gradlew.bat :desktop:packageExe
```

产物在 `desktop\build\compose\binaries\main\exe\TailcatManager-<版本>.exe`。安装包内置了 jlink 运行时，
**目标机器不需要装 Java**。默认按用户级安装到 `%LOCALAPPDATA%\Programs\TailcatManager`，不需要管理员权限，
可在向导里改目录，并创建开始菜单项和桌面快捷方式。

打包 exe/msi 需要 **WiX Toolset 3.x**（jpackage 通过 PATH 找 `candle.exe` / `light.exe`）。免安装用法：
从 [WiX 3 发布页](https://github.com/wixtoolset/wix3/releases) 下载 `wix314-binaries.zip` 解压，
把解压目录加进 PATH 再跑上面的命令。没有 WiX 时 `packageExe` 会失败，但 `packageAppImage` 不受影响。

⚠️ `nativeDistributions.description` 必须是**纯 ASCII**：它会被写进 MSI，而 WiX 默认按代码页 1252 编码，
中文或全角符号会让 `light.exe` 直接以 `LGHT0311` 失败（退出码 311）。

项目路径含非 ASCII 字符时，构建输出会被自动改写到
`%LOCALAPPDATA%\TailcatManager\build\<项目目录名>`：JDK 的 `jlink` 经 `@响应文件` 接收非 ASCII 输出路径
会报 `directory already exists`（但该目录实际不存在），而 Compose 插件固定用响应文件调 jlink，
从 Gradle 侧绕不开。把项目放到纯 ASCII 路径即可根治。

`gradle.properties` 里有两处依赖本机 JDK 位置，换机器要改：`org.gradle.java.installations.paths`
（交给 `jvmToolchain(21)` 的那个 JDK）和 `tailcat.packaging.javaHome`（带 `jpackage.exe` 的那个 JDK）。

也可以开发运行：

```bat
gradlew.bat :desktop:run
gradlew.bat :desktop:run --args="--demo"
```

演示模式使用内存配置和模拟命令，不读取真实地址簿、不写凭据，也不执行真实设备操作。

工具从 PATH、winget 链接目录、Android SDK 以及 Scoop / Chocolatey 目录检测。“工具设置”可指定 Tailcat、
ADB、scrcpy 可执行文件和地址簿目录。ADB 必需；scrcpy 仅用于屏幕控制。

缺失组件优先用 winget 用户级静默安装（`Tailscale.tailcat`、`Genymobile.scrcpy`），不需要管理员权限；
未检测到 winget 或安装失败时，自动改为从官方地址直接下载并解压到应用目录，因此新机器不必先装包管理器。
安装完成后自动重新扫描目录并保存路径。

## 使用

- 首次启动时，如果应用还没有设备，会导入现有 `tailcat-adb/users/` 地址簿。之后可点工具栏“导入地址簿”，
  只添加缺失用户，保留应用内的编辑。地址簿所在目录允许是链接，但其中的 `users` 若是符号链接则拒绝读取。
- 工具栏“测试全部设备”一键测试我的设备和临时设备的 Tailcat ping，显示每台的响应、延迟、直连或 DERP 中继路径、
  已有 ADB 状态。每台 ping 5 秒超时，并发最多 3 台；不创建转发、不执行 ADB connect/disconnect。
  结果是一次测试的快照：未收到 pong 也可能是服务端版本不支持或新链路建连超时，不能据此断定已有 ADB 离线。
- “临时连接”或 `Ctrl+Shift+N` 建立临时设备，只需填连接地址，名称可选。临时设备单独分组，
  支持多台同时连接及全部常用 ADB 操作。
- 临时配置和凭据仅保存在内存中，不写配置文件、不写凭据库。关闭临时设备或退出应用时停止其通道并清除临时配置；
  需要保留时点“保存到我的设备”，当前 UUID、通道和正在执行的任务保持。
- 添加设备后选择“连接”。每台设备有独立的 `tailcat forward` 进程和 ADB serial，支持同时连接。
  本地端口留空则从 5556 起逐个实际绑定探测，已有监听端口保持；也可为设备指定固定端口。
- 待授权、离线、转发进程退出分别显示。只有 ADB `device` 状态允许安装、文件传输、Shell 和 scrcpy。
- 所有任务在启动时固定设备、ADB 路径及 serial。切换侧栏不会改变任务目标；重连、断开会先取消该设备正在执行的任务。
- 支持 APK 覆盖安装、文件或整个目录的上传下载、单条 Shell 命令、独立 `cmd` 窗口和独立 scrcpy 窗口。
  任务输出与取消按钮位于底部。
- 关闭主窗口后连接继续运行，从系统托盘重新打开。托盘菜单可逐台连接/断开、关闭临时设备、重新检测连接、
  断开应用管理的全部连接（没有活动通道时置灰）或退出。**托盘菜单是英文的**——那条路径由 Windows
  自己绘制原生菜单文字，中文在部分机器上会渲染成方块，所以退回英文；界面其它部分不受影响。
  设备名是用户自己起的，仍可能是中文。
- 窗口菜单栏分“文件 / 设备 / 窗口”三组：`Ctrl+N` 添加设备、`Ctrl+Shift+N` 临时连接、`Ctrl+I` 导入地址簿、
  `Ctrl+T` 测试全部设备、`Ctrl+,` 工具设置、`Ctrl+Shift+M` 显示主窗口、`Ctrl+Q` 退出。
- 重复启动会唤出已在运行的实例的窗口（含从最小化恢复），不会开启第二个实例。
- ADB 掉线后前 6 次按指数退避快速重试，用尽后**不放弃**，转为每 30 秒后台重探，接通后自动恢复。
  待授权不自动重试，那要等设备侧点授权。
- 取消会停止本机命令进程，远端已经发生的安装或文件写入不会自动回滚。传输任务、Shell 命令、连接状态都有超时；
  持续使用的 scrcpy 会话最多运行 24 小时。

## 配置与凭据

设备连接地址（`tc...`，本身是含预共享密钥的凭据）用 Windows DPAPI 加密后整份存入：

```text
%LOCALAPPDATA%\TailcatManager\secrets.dat
```

DPAPI 免提示，因此不需要 macOS 版那套要校验签名的凭据助手子进程，也没有授权对话框和
“授权识别 CLI 连接”这类按钮。用 `%LOCALAPPDATA%` 而不是会漫游的 `%APPDATA%`：DPAPI 主密钥实际不随
漫游迁移，跨机解密会失败且报错晦涩。

名称、端口、工具路径和地址簿目录保存在 `%LOCALAPPDATA%\TailcatManager\configuration.json`，不含连接凭据。
从官方直链下载的工具解压到 `%LOCALAPPDATA%\TailcatManager\tools\`。日志自动隐藏完整 `tc...` 地址。

默认地址簿目录是 `%USERPROFILE%\.local\state\tailcat-adb`，与 macOS 版的
`${XDG_STATE_HOME:-~/.local/state}/tailcat-adb` 同形。地址簿是只读来源：不改写其中的文件，也不接管
其他工具建立的通道。

仓库只包含代码、测试与构建脚本；本机配置、凭据库、下载的工具二进制、日志和构建输出都不入库。

## 验证

```bat
gradlew.bat check
gradlew.bat check -Dtailcat.networkTests=true
```

注意是 `check` 而不是 `test`：`core` 是 KMP 模块，它的 JVM 测试任务叫 `jvmTest`，不在 `test` 这个任务名下，
`gradlew test` 只会跑到 `desktop` 的 6 项。

默认运行 30 项（`core` 24 项 + `desktop` 6 项），覆盖双设备隔离、固定任务目标、待授权、连接与重连取消旧任务、
异常退出、配置拒绝符号链接、参数传递、进程超时和取消、可达性并发上限与输出脱敏、地址簿只读导入与符号链接拒绝、
唤醒请求一次性。

加上 `-Dtailcat.networkTests=true` 会额外跑三个直链下载测试，真实下载并解压 Tailcat、platform-tools 和 scrcpy
的官方发布包，需要联网，默认跳过。上游一旦改动发布包的目录结构或资产名它会立刻失败——而那正是
“新机器一键配环境”这条路唯一的外部依赖。

界面演示：

```bat
gradlew.bat :desktop:run --args="--demo"
```

## 目录

- `core/`：共享逻辑。配置、凭据、进程、独立会话、任务与 ADB 状态管理、可达性、组件安装。
- `desktop/`：Windows 端的 Compose Desktop 界面与平台层（DPAPI、JNA 进程、winget、托盘、菜单栏）。
- `desktop/src/main/resources/icons/app-icon.png`：应用图标原图，与 macOS 版同一张。
- `desktop/icons/`：由原图生成的 `TailcatManager.ico`（exe 图标），以及生成脚本 `GenerateIcon.java`
  （只做缩放、不重绘）。换了原图重跑一次 `java desktop/icons/GenerateIcon.java` 即可。
- `gradle/libs.versions.toml`：版本目录。

图标只有这一份源文件：exe 图标、窗口标题栏图标、托盘图标都来自它，不会出现两套。

`core` 只声明 JVM target，因此桌面端不需要 Android SDK 或 AGP 就能构建。

## 与 macOS 版的差异

- **不再观测 CLI 外部连接**。原实现靠 `/bin/ps` 与 `/usr/sbin/lsof` 识别 macOS/Linux 上以 `socat` 搭建的
  `tailcat-adb` 桥；Windows 上既没有该 CLI，纯 Java 也拿不到“端口 → PID”归属。侧栏的“CLI 外部 N”计数一并移除。
- **不做唤醒后重检**（Windows 无对应通知），依赖原有轮询兜底。
- ADB 服务器沿用共享的 5037 端口，与 Android Studio 看到同一批设备。
- 菜单栏、终端、目录选择分别是 Windows 菜单栏、`cmd.exe`、Swing 的 `JFileChooser`（AWT 的 `FileDialog`
  在 Windows 上不能选目录）。
- 凭据从 Keychain 换成 DPAPI，因此没有签名校验、没有授权弹窗，也没有“授权识别 CLI 连接”这类按钮。
- 单实例检测改用实例目录下的文件锁；重复启动会唤出已有窗口，而不是静默退出。
- **托盘菜单用英文**（macOS 版是中文）：系统托盘菜单由 Windows 绘制原生菜单文字，中文在部分机器上
  渲染成方块，显式指定含 CJK 的字体也没能解决。窗口菜单栏和界面本体是 Skia 绘制的，中文正常。

第一版不包含远端 Tailcat 部署、客户端密钥管理、内嵌视频/终端、云同步或应用内自动更新。

正常退出会断开 GUI 启动的连接并回收 `tailcat` / `adb` / `scrcpy` 子进程；**强制结束或崩溃后留下的孤儿进程
尚未处理**（彻底解法是把子进程归入一个 `KILL_ON_JOB_CLOSE` 的 Job Object，交给内核回收）。
旧监听会被新实例避让，不会按 PID 文件自动结束不明进程。
