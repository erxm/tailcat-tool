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

        nativeDistributions {
            // AppImage 是免安装的绿色目录；Exe 是安装包，**需要 WiX 3.x**（见 README）。
            targetFormats(TargetFormat.AppImage, TargetFormat.Exe)
            packageName = "TailcatManager"
            packageVersion = "1.1.1"
            // 必须是纯 ASCII：这个值会被 jpackage 写进 MSI，而 WiX 默认按代码页 1252 编码，
            // 中文或全角符号会让 light.exe 直接以 LGHT0311 失败（退出码 311）。
            description = "Tailcat to ADB session manager"
            vendor = "coauto.cc"
            // 注意：不要加 --win-console，否则启动器会变成控制台子系统并弹窗。
            // jdk.unsupported 是 JNA 需要的（它用了 sun.misc.Unsafe）。
            modules("java.base", "java.desktop", "java.logging", "jdk.crypto.ec", "jdk.unsupported")

            // exe/msi 安装包的升级标识。**必须固定不变**：换了值，新版会被当成另一个应用装成两份。
            windows {
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
                upgradeUuid = "470F5296-AE6E-4E07-A4DC-D2DB587CC175"
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
