plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
}

/*
 * 构建产物目录重定向。
 *
 * 原因：本机项目路径含非 ASCII 字符（`...\魔改apk\tailcat-tool`），而 JDK 的 jlink 在经由
 * 「响应文件」接收 `--output` 路径时会失败（报 `Error: directory already exists`，但该目录
 * 实际并不存在）。实测同一份参数直接内联在命令行上传入就能成功，纯 ASCII 路径经响应文件也成功——
 * 问题出在非 ASCII 路径 + 响应文件的组合。
 *
 * Compose 插件固定用 `@argsfile` 调 jlink，这一点无法从 Gradle 侧改掉，所以把 buildDir 挪到
 * 纯 ASCII 位置（源码位置不受影响）。路径本来就是 ASCII 时不做任何改动。
 *
 * 末尾带上项目目录名：同一台机器上可能有多个副本（比如主项目与 windows 分支各一份），
 * 都在中文路径下的话会撞到同一个目标目录、互相覆盖构建产物。
 */
private val asciiBuildRoot: String? = run {
    if (rootDir.absolutePath.all { it.code < 128 }) return@run null
    val base = System.getenv("LOCALAPPDATA") ?: return@run null
    "$base\\TailcatManager\\build\\${rootDir.name}"
}

if (asciiBuildRoot != null) {
    layout.buildDirectory.set(file("$asciiBuildRoot\\root"))
    subprojects {
        layout.buildDirectory.set(file("$asciiBuildRoot\\${project.name}"))
    }
    logger.lifecycle("项目路径含非 ASCII 字符，构建产物已重定向到：$asciiBuildRoot")
}
