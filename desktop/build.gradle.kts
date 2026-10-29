import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    // 用 DSL 访问器（独立坐标 org.jetbrains.compose.material3:material3 目前只有 alpha 版）
    @Suppress("DEPRECATION")
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    // DeviceSession.logs 是 ImmutableList，UI 侧要能引用到这个类型
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.jna)
    implementation(libs.jna.platform)

    testImplementation(kotlin("test"))
}

/*
 * Gradle 9 会在任务动作执行前预先创建 @OutputDirectory，而 jlink 拒绝写入「已存在」的输出目录
 * （报 `Error: directory already exists`）。Compose 插件是针对 Gradle 8.x 写的，没有先删除。
 * 这里在 jlink 真正执行前把该目录清掉。
 */
tasks.matching { it.name == "createRuntimeImage" }.configureEach {
    doFirst {
        val dir = layout.buildDirectory.dir("compose/tmp/main/runtime").get().asFile
        println("TAILCAT-DEBUG: doFirst 前 exists=${dir.exists()}")
        if (dir.exists()) dir.deleteRecursively()
        println("TAILCAT-DEBUG: doFirst 后 exists=${dir.exists()}")
    }
}

compose.desktop {
    application {
        mainClass = "cc.coauto.tailcat.desktop.MainKt"

        // 编译用 JBR 21（工具链），但打包必须用带 jpackage 的完整 JDK：
        // Android Studio 自带的 JBR 是精简运行时，没有 jpackage.exe。
        javaHome = providers.gradleProperty("tailcat.packaging.javaHome")
            .getOrElse("C:/Program Files/Java/jdk-25")

        // JNA 会调用受限的 System::load，加上这个避免每次启动都打警告。
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")

        /*
         * 关掉 Skiko 的「全局 Swing/AWT Look&Feel」。
         *
         * Compose 的 `application {}` 在 `compose.application.configure.swing.globals=true` 时会调用
         * `configureSwingGlobalsForCompose()`，而它对三个 skiko 属性读取的**默认值都是 "true"**
         * （见 ConfigureSwingGlobalsForCompose.desktop.kt 的 `$default` 字节码）。于是 Skiko 会接管
         * 本进程里 Swing/AWT 菜单的渲染——实测同一份 `java.awt.PopupMenu` 在
         * `skiko.rendering.laf.global=true` 下深色像素比 `false` 多 57%，也就是**画出来的东西不一样**。
         * 系统托盘菜单正是 AWT PopupMenu，中文字符在这条路径上会渲染成方块。
         *
         * 这一项在命令行上先于 Compose 设置，所以 Compose 读到 false 后只会原样再写回 false。
         */
        jvmArgs += listOf("-Dskiko.rendering.laf.global=false")

        /*
         * release 变体默认会跑整程序压缩（R8/ProGuard）：把每个依赖 jar 重写，只保留"可达"的类。
         * 对普通代码没问题，但对**按名字与原生库绑定**的库是致命的 ——
         * 实测 JNA 的 `Native.dispose` 被裁掉，运行时报
         * `Can't obtain static method dispose from class com.sun.jna.Native`
         *（我们自己机器和用户机器上都复现）。Skiko 的 DLL 因为有显式资源规则没中招，JNA 没有。
         *
         * 这里直接关掉压缩：桌面应用几十 MB 的体积换"不会莫名其妙裁坏反射/JNI 契约"，值得。
         * 真要开，必须配 keep 规则（-keep class com.sun.jna.** { *; } 之类）并逐项验证。
         */
        buildTypes.release {
            proguard {
                isEnabled.set(false)
            }
        }

        nativeDistributions {
            // AppImage 是免安装的绿色目录；Exe 是安装包，**需要 WiX 3.x**（见 README）。
            targetFormats(TargetFormat.AppImage, TargetFormat.Exe)
            packageName = "TailcatManager"
            packageVersion = "1.1.6"
            // 必须是纯 ASCII：这个值会被 jpackage 写进 MSI，而 WiX 默认按代码页 1252 编码，
            // 中文或全角符号会让 light.exe 直接以 LGHT0311 失败（退出码 311）。
            description = "Tailcat to ADB session manager"
            vendor = "coauto.cc"
            // 注意：不要加 --win-console，否则启动器会变成控制台子系统并弹窗。
            // jdk.unsupported 是 JNA 需要的（它用了 sun.misc.Unsafe）。
            modules("java.base", "java.desktop", "java.logging", "jdk.crypto.ec", "jdk.unsupported")

            // exe/msi 安装包的升级标识。同一代内**必须固定不变**：换了值，新版会被当成另一个应用装成两份。
            // 但 1.1.1~1.1.5 这一代必须换掉（见下面 upgradeUuid 的说明），所以这里是 1.1.6 线的起点。
            windows {
                /*
                 * 程序装到 %LOCALAPPDATA%\TailcatManagerApp，**不要**落在
                 * %LOCALAPPDATA%\TailcatManager —— 那是本应用的数据目录（configuration.json /
                 * secrets.dat / logs / jna）。1.1.1 时三者叠在一个目录里（程序 + 数据 + Gradle 构建产物），
                 * 结果那份 MSI 的文件表把构建产物也算了进去；文件一没，卸载在 InstallValidate 就报
                 * `Error 2318: File does not exist`，产品从此既卸不掉也升不上去。
                 *
                 * ⚠️ 这里只能写**单级目录名**（MSI 的 Directory Name 不能含分隔符），实测：
                 *   - "Programs\\TailcatManager" → 反斜杠被吃掉，装成 `%LOCALAPPDATA%\ProgramsTailcatManager`；
                 *   - "Programs/TailcatManager"   → light.exe 直接失败（退出码 204）。
                 * 单级名同样是可移植的：per-user 安装以 %LOCALAPPDATA% 为基准，不含用户名、不需要管理员。
                 */
                installationPath = "TailcatManagerApp"
                // exe 的图标（任务栏、开始菜单、桌面快捷方式都取它）。
                // 不指定的话 jpackage 会塞一个自带的黑白默认图标，跟运行时的窗口/托盘图标对不上。
                // 图形与 src/main/resources/icons/app-icon.png 一致；
                // 换了原图重跑：java desktop/icons/GenerateIcon.java
                iconFile.set(project.file("icons/TailcatManager.ico"))

                // 装到 %LOCALAPPDATA%\Programs，不需要管理员权限。
                perUserInstall = true
                dirChooser = true
                shortcut = true
                menu = true
                menuGroup = "Tailcat Manager"
                /*
                 * 换过一代升级标识：原来用的是 470F5296-AE6E-4E07-A4DC-D2DB587CC175（1.1.1~1.1.5）。
                 * 那一代的 MSI 文件表被构建产物污染（见上），**卸载必然 2318**；而新版安装器的
                 * RemoveExistingProducts 会先去找同 upgradeUuid 的旧产品并移除它，于是新包也装不上。
                 *
                 * 换新值 = 断开与坏线的关系，1.1.6 起可以正常装/卸/升级。代价是 1.1.5 及更早的用户
                 * 升级后会多出一条旧记录，用 desktop/tools/cleanup-old-versions.ps1 清掉。
                 * ⚠️ 从这个值起不要再改，否则 1.1.6+ 内部又会变成两份应用。
                 */
                upgradeUuid = "7C3D9A54-1B62-4E8F-9E3A-5D2F6B8C41A7"
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // 直链下载测试需要联网，默认跳过；加 -Dtailcat.networkTests=true 才跑。
    systemProperty("tailcat.networkTests", System.getProperty("tailcat.networkTests") ?: "false")
    testLogging { events("passed", "failed", "skipped") }
}
