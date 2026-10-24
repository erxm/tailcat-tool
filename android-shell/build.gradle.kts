import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URI
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

/**
 * 只放 xz 一个库，用**独立类加载器**加载，见下面 [forEachDebEntry] 的说明。
 * 必须在 dependencies 块之前声明。
 */
val xzClasspath: Configuration by configurations.creating

dependencies {
    // 管理端逻辑（设备会话状态机、ADB 客户端抽象、tailcat 通道）全在 core 里。
    implementation(project(":core"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    // 上传目录：SAF 选目录树之后要递归遍历它（OpenDocumentTree 给的是树 Uri）
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.zxing.core)
    // 扫二维码用 CameraX 取帧，解码仍由上面的 zxing.core 做（省一个解码库）。
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    // 配置文件与凭据库都是 JSON（core 里的 AppConfiguration 已经带了 @Serializable）
    implementation(libs.kotlinx.serialization.json)
    // core 用它做「不可变 List」以保证 Compose 稳定推断（DeviceSession.logs 就是），
    // core 是 implementation 依赖，消费方必须自己再声明一遍才看得到这个类型。
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.miuix) {
        // MIUIX 声明了这个依赖，但**从未引用它的类**（扫过 miuix 的 classes.jar，0 处引用）。
        // 而本机环境拒绝在 Gradle 的转换缓存里创建以该 AAR 包名命名的目录
        //（.../transformed/androidx.compose.material3.windowsizeclass 报 Permission denied，
        //  同目录下其它任何名字都能建），导致 AarResourcesCompilerTransform 必然失败。
        // 该 AAR 本身也没有任何资源（R.txt 为空、无 res/），排除掉没有任何损失。
        exclude(group = "androidx.compose.material3", module = "material3-window-size-class")
        exclude(group = "androidx.compose.material3", module = "material3-window-size-class-android")
        exclude(group = "org.jetbrains.compose.material3", module = "material3-window-size-class")
    }

    xzClasspath("org.tukaani:xz:1.10")
}

android {
    buildFeatures {
        compose = true
    }
}

val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs/arm64-v8a")

/** 16 KB 页大小的对齐要求。Android 15 起对 targetSdk 35+ 的应用强制这条。 */
val pageAlignment16K = 16 * 1024L

/*
 * ——— 解 .deb 需要的三样东西：ar、tar、xz ———
 *
 * ar 和 tar 都是定长头格式，自己解十几行就够。**xz 做不到**，必须用库。
 *
 * 而把 commons-compress 放进 buildscript classpath 是不行的：Gradle 自己也打包了一份
 * commons-compress，脚本执行期的类加载器会先命中 Gradle 那一份，于是 Commons 的类能加载、
 * 它依赖的 org.tukaani.xz 却不在 —— 报 `NoClassDefFoundError: org/tukaani/xz/XZInputStream`。
 *
 * buildSrc 是更正统的解法，但本机环境拒绝为它生成的 accessor 缓存目录创建文件
 *（`FileNotFoundException: ...\kotlin-dsl\accessors\<hash>\classes\... (拒绝访问)`，
 *  清掉缓存后仍稳定复现，和 .gitignore 里那条 windowsizeclass 记录是同一个毛病），
 * 所以退而求其次：用一个**独立的类加载器**只装 xz，绕开 Gradle 内部那份的干扰。
 */

/** 按 C 字符串取值：读到 NUL 或长度上限为止。避免在源码里写 NUL 字符字面量。 */
fun cStringOf(bytes: ByteArray, start: Int, length: Int, charset: java.nio.charset.Charset): String {
    var end = start
    val limit = minOf(start + length, bytes.size)
    while (end < limit && bytes[end] != 0.toByte()) end++
    return String(bytes, start, end - start, charset)
}

/** 从 .deb（ar 容器）里取出成员内容。 */
fun readArMember(deb: File, wanted: String): ByteArray {
    val bytes = deb.readBytes()
    check(bytes.size > 8 && String(bytes, 0, 8, Charsets.US_ASCII) == "!<arch>\n") {
        "$deb 不是 ar 归档"
    }
    var pos = 8
    while (pos + 60 <= bytes.size) {
        val header = String(bytes, pos, 60, Charsets.US_ASCII)
        val name = header.substring(0, 16).trim().removeSuffix("/")
        val size = header.substring(48, 58).trim().toInt()
        val dataStart = pos + 60
        if (name == wanted) return bytes.copyOfRange(dataStart, dataStart + size)
        pos = dataStart + size + (size % 2) // ar 的成员按偶数边界对齐
    }
    throw GradleException("$deb 里没有 $wanted")
}

/** 只处理普通文件；目录、符号链接、静态库一律不看（调用方还会再用 ELF 魔数过滤一道）。 */
fun forEachTarEntry(tar: ByteArray, action: (path: String, bytes: ByteArray) -> Unit) {
    fun field(offset: Int, length: Int, base: Int): String =
        cStringOf(tar, base + offset, length, Charsets.US_ASCII)

    var pos = 0
    var longName: String? = null
    while (pos + 512 <= tar.size) {
        val name = field(0, 100, pos)
        if (name.isEmpty()) break // 全零块 = 归档结束
        val size = field(124, 12, pos).trim().toLongOrNull(8) ?: 0L
        // typeflag 用 Int 比：老式 tar 里普通文件这一位就是 0，写成字符字面量不直观
        val type = tar[pos + 156].toInt() and 0xFF
        val dataStart = pos + 512
        when (type) {
            // GNU 长文件名：真名在数据块里，下一个头才是目标文件
            'L'.code -> longName = cStringOf(tar, dataStart, size.toInt(), Charsets.UTF_8)
            '0'.code, 0 -> {
                val path = longName ?: run {
                    // ustar 把超过 100 字符的路径拆在 prefix 字段里
                    val prefix = field(345, 155, pos)
                    if (prefix.isEmpty()) name else "$prefix/$name"
                }
                longName = null
                action(path, tar.copyOfRange(dataStart, dataStart + size.toInt()))
            }
        }
        pos = dataStart + (((size + 511) / 512) * 512).toInt()
    }
}

/**
 * 遍历 .deb 里的文件，路径已转成相对 Termux `usr/` 的形式（`bin/adb`、`lib/libz.so.1.3.2`）。
 */
fun forEachDebEntry(deb: File, action: (path: String, bytes: ByteArray) -> Unit) {
    val data = readArMember(deb, "data.tar.xz")
    val loader = URLClassLoader(
        xzClasspath.files.map { it.toURI().toURL() }.toTypedArray(),
        ClassLoader.getPlatformClassLoader(),
    )
    val xz = loader.loadClass("org.tukaani.xz.XZInputStream")
        .getConstructor(InputStream::class.java)
        .newInstance(ByteArrayInputStream(data)) as InputStream
    val tar = xz.use { it.readBytes() }

    val prefix = "./data/data/com.termux/files/usr/"
    forEachTarEntry(tar) { path, bytes ->
        if (path.startsWith(prefix)) action(path.removePrefix(prefix), bytes)
    }
}

/** 一个 ELF 文件里我们关心的两件事。 */
data class ElfFacts(val soname: String?, val loadAlignment: Long)

/**
 * `lib/` 下不只有共享库：还有 `.a` 静态库、`libz.so` 这类符号链接、链接器脚本。
 * 按名字筛一定会漏，直接看魔数。
 */
fun looksLikeElf(bytes: ByteArray): Boolean =
    bytes.size >= 18 &&
        bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte() &&
        bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte()

/**
 * 只认 ELF64 小端（aarch64 只有这一种）。
 *
 * - `p_align` 取所有 PT_LOAD 里最大的那个：16 KB 要求它不小于 16 KB，**并且**每个 LOAD
 *   还要满足 `p_vaddr ≡ p_offset (mod p_align)`。后者一并检查，因为把 4 KB 的文件硬改
 *   `p_align` 会让动态链接器算出非页对齐的 mmap 偏移，产出一个加载不了的坏文件 ——
 *   那种文件在 4 KB 设备上照样能跑，只有真正 16 KB 的设备上才会炸，属于最难查的一类问题。
 * - SONAME 走 PT_DYNAMIC 拿，不走节头：省掉解析节名字符串表那一整套。
 */
fun parseElf(bytes: ByteArray): ElfFacts {
    fun u16(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
    fun u32(o: Int) = u16(o).toLong() or (u16(o + 2).toLong() shl 16)
    fun u64(o: Int) = u32(o) or (u32(o + 4) shl 32)

    val phoff = u64(0x20)
    val phentsize = u16(0x36)
    val phnum = u16(0x38)

    var maxAlign = 0L
    // vaddr, offset, filesz —— 把虚拟地址翻译回文件偏移要用
    val loads = ArrayList<Triple<Long, Long, Long>>()
    var dynOffset = -1L
    var dynSize = 0L

    for (i in 0 until phnum) {
        val p = (phoff + i.toLong() * phentsize).toInt()
        when (u32(p)) {
            1L -> { // PT_LOAD
                val offset = u64(p + 8)
                val vaddr = u64(p + 16)
                val filesz = u64(p + 32)
                val align = u64(p + 48)
                loads += Triple(vaddr, offset, filesz)
                if (align > maxAlign) maxAlign = align
                check(align <= 1 || (vaddr - offset) % align == 0L) {
                    "PT_LOAD 的 vaddr/offset 对不齐：vaddr=$vaddr offset=$offset align=$align"
                }
            }
            2L -> { // PT_DYNAMIC
                dynOffset = u64(p + 8)
                dynSize = u64(p + 32)
            }
        }
    }

    var soname: String? = null
    if (dynOffset >= 0) {
        var strtabVaddr = -1L
        var sonameOffset = -1L
        for (i in 0 until (dynSize / 16).toInt()) {
            val d = (dynOffset + i * 16L).toInt()
            val tag = u64(d)
            if (tag == 0L) break // DT_NULL
            when (tag) {
                5L -> strtabVaddr = u64(d + 8)  // DT_STRTAB
                14L -> sonameOffset = u64(d + 8) // DT_SONAME
            }
        }
        if (strtabVaddr >= 0 && sonameOffset >= 0) {
            val segment = loads.firstOrNull { strtabVaddr >= it.first && strtabVaddr < it.first + it.third }
            if (segment != null) {
                val at = (segment.second + (strtabVaddr - segment.first) + sonameOffset).toInt()
                if (at in bytes.indices) {
                    var end = at
                    while (end < bytes.size && bytes[end] != 0.toByte()) end++
                    if (end > at) soname = String(bytes, at, end - at, Charsets.UTF_8)
                }
            }
        }
    }
    return ElfFacts(soname, maxAlign)
}

fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

/**
 * tailcat 官方发布的 Linux arm64 二进制是**静态链接**的 aarch64 ELF，
 * 可以直接在 Android 上执行，不需要 Android NDK 重新编译。
 *
 * 它必须被当作 **native library** 打包：Android 10 起 App 不能 execve 自己可写数据目录里的文件
 * （SELinux 对 app_data_file 拒绝 execute_no_trans），但 APK 里提取到 nativeLibraryDir 的文件
 * 类型是 apk_data_file，untrusted_app 被允许 execute。
 * 而静态二进制没有共享库依赖，可以直接执行，不需要建 rootfs 符号链接。
 *
 * 硬性要求：**文件名必须以 lib 开头、.so 结尾**，否则 PackageManager 不会提取它。
 * Go 的链接器本来就按 64 KB 对齐，所以它天然满足 16 KB 要求（实测 align=0x10000）。
 */
val tailcatVersion = "0.7.0"
val tailcatArchive = layout.buildDirectory.file("tailcat-$tailcatVersion-linux-arm64.tar.gz")

val downloadTailcat by tasks.registering {
    description = "下载官方 tailcat Linux arm64 静态二进制"
    val archive = tailcatArchive.get().asFile
    outputs.file(archive)
    onlyIf { !archive.exists() }
    doLast {
        archive.parentFile?.mkdirs()
        val url = URI(
            "https://github.com/tailscale/tailcat/releases/download/v$tailcatVersion/" +
                "tailcat_${tailcatVersion}_linux_arm64.tar.gz",
        ).toURL()
        logger.lifecycle("下载 $url")
        url.openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
    }
}

val prepareTailcatBinary by tasks.registering(Copy::class) {
    description = "把 tailcat 解出来并按 native library 命名放进 jniLibs"
    dependsOn(downloadTailcat)
    from(tarTree(resources.gzip(tailcatArchive))) {
        include("tailcat")
        rename { "libtailcat.so" }
    }
    into(jniLibsDir)
}

/*
 * ——— adb ———
 *
 * Android 上没有系统 adb，而管理端的 AdbClient/会话状态机全是围绕 adb 命令行写的。
 * 与其在进程内重新实现一遍 ADB 协议，不如**自带一份 arm64 的 adb**，这样 core 一行都不用改。
 *
 * 来源：Termux 的 android-tools 包（NDK 构建、链接 bionic，见 THIRD_PARTY_NOTICES.md）。
 * 选它而不是别的 arm64 adb，唯一的理由是 **16 KB 页大小对齐**：
 * AOSP 自己不发 arm64 的 adb；Debian/Ubuntu 的 arm64 包链接 glibc，在 Android 上根本起不来；
 * 原先用的 nightmare-space/adb_kit v1.5.4（2024-10）全部是 4 KB 对齐，且上游没有更新版本。
 *
 * 这些 .so 只有在**改回原名**后才能被动态链接器找到（DT_NEEDED 认的是真名，
 * 而 PackageManager 只解压 lib*.so），所以运行时会在 filesDir 里建一套符号链接 —— 见 AdbRuntime.kt。
 * 本任务用 ELF 的 SONAME 当那个「真名」，因为它就是链接时写进 DT_NEEDED 的那个字符串。
 */
data class TermuxPackage(val name: String, val path: String, val sha256: String)

val termuxPackages = listOf(
    TermuxPackage(
        "android-tools",
        "pool/main/a/android-tools/android-tools_37.0.0p1-1_aarch64.deb",
        "bafdbb636f780b0b477a074832ae26dde0f1a934c4f2743dfef2b32e95a3c642",
    ),
    TermuxPackage(
        "abseil-cpp",
        "pool/main/a/abseil-cpp/abseil-cpp_20260526.0_aarch64.deb",
        "e489fac652cddc39d9436141e627285f1034a545a06fbb19c420514a419ad877",
    ),
    TermuxPackage(
        "brotli",
        "pool/main/b/brotli/brotli_1.2.0_aarch64.deb",
        "db1502601d40fb44e6085ad8bfd9311a8b472e98db831ceec9d404c5708bb52c",
    ),
    TermuxPackage(
        "fmt",
        "pool/main/f/fmt/fmt_1:11.2.0-1_aarch64.deb",
        "dad595afcb3b1096d725c6772c0c5531764caa5841affba3c533c6298a32ab08",
    ),
    TermuxPackage(
        "libcxx",
        "pool/main/libc/libc++/libc++_30_aarch64.deb",
        "53d0b84a7ba7459024257cb94d5b136fe13ef858567f65a8064b35950799f2ca",
    ),
    TermuxPackage(
        "liblz4",
        "pool/main/libl/liblz4/liblz4_1.10.0-1_aarch64.deb",
        "09b9449418d5c2dc4f5c1c140ba8138d56be3e9ae5fd3be3318825ec9f8a0499",
    ),
    TermuxPackage(
        "libprotobuf",
        "pool/main/libp/libprotobuf/libprotobuf_2:36.2_aarch64.deb",
        "2f79e70af72127b6e9d8e8ea1a8bf261ea5f390914e667049b4f1dbc81f21fb0",
    ),
    TermuxPackage(
        "zlib",
        "pool/main/z/zlib/zlib_1.3.2_aarch64.deb",
        "75e7d0af17fcc3b40004309fdc00a1ddb9ae08346dce5e269902c34ac3966ac9",
    ),
    TermuxPackage(
        "zstd",
        "pool/main/z/zstd/zstd_1.5.7-1_aarch64.deb",
        "e1b4a5113648da8de189620ba1fce74c48b2d0833d9043391b9a1c91fb606fd3",
    ),
)

/**
 * adb 运行时的**依赖闭包**（按 SONAME）。
 *
 * 这份清单是用 `readelf -d` 从 adb 出发做传递闭包算出来的：闭包里除这 58 个之外，
 * 只剩 libc.so / libdl.so / liblog.so / libm.so —— 那四个一律用设备自己的，不带。
 * pcre2 虽然被 android-tools 声明为依赖，但不在闭包里，所以不取（省 1 MB）。
 *
 * Termux 升级这些包的大版本时要重新算；下面的数量断言会当场发现漏取。
 */
val adbRuntimeNames = setOf(
    "libabsl_base.so", "libabsl_city.so", "libabsl_cord.so", "libabsl_cord_internal.so",
    "libabsl_cordz_functions.so", "libabsl_cordz_handle.so", "libabsl_cordz_info.so",
    "libabsl_crc32c.so", "libabsl_crc_cord_state.so", "libabsl_crc_internal.so",
    "libabsl_die_if_null.so", "libabsl_examine_stack.so", "libabsl_exponential_biased.so",
    "libabsl_hash.so", "libabsl_hashtablez_sampler.so", "libabsl_int128.so",
    "libabsl_kernel_timeout_internal.so", "libabsl_leak_check.so", "libabsl_log_globals.so",
    "libabsl_log_internal_check_op.so", "libabsl_log_internal_conditions.so",
    "libabsl_log_internal_fnmatch.so", "libabsl_log_internal_format.so",
    "libabsl_log_internal_globals.so", "libabsl_log_internal_log_sink_set.so",
    "libabsl_log_internal_message.so", "libabsl_log_internal_nullguard.so",
    "libabsl_log_internal_proto.so", "libabsl_log_internal_structured_proto.so",
    "libabsl_log_sink.so", "libabsl_malloc_internal.so", "libabsl_raw_hash_set.so",
    "libabsl_raw_logging_internal.so", "libabsl_spinlock_wait.so", "libabsl_stacktrace.so",
    "libabsl_status.so", "libabsl_statusor.so", "libabsl_str_format_internal.so",
    "libabsl_strerror.so", "libabsl_strings.so", "libabsl_strings_internal.so",
    "libabsl_symbolize.so", "libabsl_synchronization.so", "libabsl_throw_delegate.so",
    "libabsl_time.so", "libabsl_time_zone.so", "libabsl_tracing_internal.so",
    "libabsl_vlog_config_internal.so",
    "libbrotlicommon.so", "libbrotlidec.so", "libbrotlienc.so", "libc++_shared.so",
    "libfmt.so", "liblz4.so", "libprotobuf.so", "libutf8_validity.so", "libz.so.1", "libzstd.so.1",
)

val termuxArchiveDir = layout.buildDirectory.dir("termux")

val downloadTermuxPackages by tasks.registering {
    description = "下载 Termux 的 android-tools 及其依赖包（.deb），逐个校验 SHA-256"
    val outDir = termuxArchiveDir.get().asFile
    val packages = termuxPackages
    outputs.dir(outDir)
    doLast {
        outDir.mkdirs()
        val base = "https://packages.termux.dev/apt/termux-main/"
        for (pkg in packages) {
            val target = File(outDir, "${pkg.name}.deb")
            if (!target.exists() || sha256(target) != pkg.sha256) {
                // 路径里的 epoch（fmt_1:11.2.0-1）必须编码，否则 URI 会把冒号当 scheme 分隔符
                val url = URI(base + pkg.path.replace(":", "%3A")).toURL()
                logger.lifecycle("下载 $url")
                url.openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }
            val actual = sha256(target)
            if (actual != pkg.sha256) {
                target.delete()
                throw GradleException("${pkg.name} 校验失败：期望 ${pkg.sha256}，实际 $actual")
            }
        }
    }
}

val prepareAdbBinary by tasks.registering {
    description = "从 Termux 的 .deb 里取出 adb 及其依赖闭包，按 SONAME 命名放进 jniLibs"
    dependsOn(downloadTermuxPackages)
    val archDir = termuxArchiveDir.get().asFile
    val outDir = jniLibsDir.asFile
    val packages = termuxPackages
    val wanted = adbRuntimeNames
    inputs.dir(archDir)
    outputs.dir(outDir)
    doLast {
        outDir.mkdirs()
        val expected = HashSet<String>()
        var taken = 0

        for (pkg in packages) {
            forEachDebEntry(File(archDir, "${pkg.name}.deb")) { path, bytes ->
                val fileName = path.substringAfterLast('/')
                val isAdb = path == "bin/adb"
                // 只看 lib/ 这一层的直接子项：pkgconfig/、cmake/ 下面的东西同样以 lib 开头
                val isLib = path.startsWith("lib/") &&
                    !path.removePrefix("lib/").contains('/') &&
                    fileName.startsWith("lib")
                if ((isAdb || isLib) && looksLikeElf(bytes)) {
                    val facts = runCatching { parseElf(bytes) }
                        .getOrElse { throw GradleException("$path 解析失败：${it.message}") }
                    if (facts.loadAlignment < pageAlignment16K) {
                        throw GradleException(
                            "$path 只有 ${facts.loadAlignment} 字节对齐，16 KB 页大小的设备上加载不了",
                        )
                    }
                    // adb 本体是可执行文件，没有 SONAME；库一律用 SONAME 当运行时真名
                    val realName = if (isAdb) "libadb.so" else facts.soname
                    if (realName != null && (isAdb || realName in wanted)) {
                        // 文件名必须 lib*.so 结尾，否则 PackageManager 不解压到 nativeLibraryDir
                        File(outDir, "$realName.so").writeBytes(bytes)
                        expected += "$realName.so"
                        taken++
                    }
                }
            }
        }

        if (taken != wanted.size + 1) {
            throw GradleException("取到 $taken 个文件，期望 ${wanted.size + 1} 个（含 adb 本体）")
        }

        // 清掉上一次留下的、这次不再需要的东西（比如换来源之前的 adb_kit 那一批）。
        // libtailcat.so 归 prepareTailcatBinary 管，不动。
        outDir.listFiles()?.forEach { file ->
            if (file.name != "libtailcat.so" && file.name !in expected) {
                logger.lifecycle("清理过期的 ${file.name}")
                file.delete()
            }
        }
        logger.lifecycle("从 Termux 取出 $taken 个 adb 运行时文件到 $outDir")
    }
}

/*
 * ——— scrcpy-server ———
 *
 * 屏幕控制跑在**被控设备**上：这个 jar 会被 `adb push` 过去，再用 `app_process` 起来。
 * 所以它既不是 jniLibs（那是给本机 execve 用的），也不是普通资源 —— 放 assets，
 * 运行时读出来落到 cacheDir 再推过去。
 *
 * ⚠️ 版本串必须和客户端传给服务端的第一个参数**一字不差**（`Server.java` 里会比对
 * `BuildConfig.VERSION_NAME`，不匹配直接抛异常退出）。所以版本号在这里只有一份定义，
 * `ScrcpyServer.kt` 里的常量要和它对上。
 *
 * 来源：Genymobile 官方 release。用户已明确同意从这个来源取（见 THIRD_PARTY_NOTICES.md）。
 */
val scrcpyVersion = "5.0.1"
val scrcpyServerSha256 = "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536"
val scrcpyServerArchive = layout.buildDirectory.file("scrcpy-server-v$scrcpyVersion.jar")
val assetsDir = layout.projectDirectory.dir("src/main/assets")

val downloadScrcpyServer by tasks.registering {
    description = "下载官方 scrcpy-server（会推到被控设备上运行）"
    val archive = scrcpyServerArchive.get().asFile
    outputs.file(archive)
    doLast {
        archive.parentFile?.mkdirs()
        if (!archive.exists() || sha256(archive) != scrcpyServerSha256) {
            val url = URI(
                "https://github.com/Genymobile/scrcpy/releases/download/" +
                    "v$scrcpyVersion/scrcpy-server-v$scrcpyVersion",
            ).toURL()
            logger.lifecycle("下载 $url")
            url.openStream().use { input -> archive.outputStream().use { input.copyTo(it) } }
        }
        val actual = sha256(archive)
        if (actual != scrcpyServerSha256) {
            archive.delete()
            throw GradleException("scrcpy-server 校验失败：期望 $scrcpyServerSha256，实际 $actual")
        }
    }
}

val prepareScrcpyServer by tasks.registering(Copy::class) {
    description = "把 scrcpy-server 放进 assets，运行时由 App 读出来推给被控设备"
    dependsOn(downloadScrcpyServer)
    from(scrcpyServerArchive) {
        // 服务端要求文件名是 scrcpy-server.jar（其实是个未签名的 APK）
        rename { "scrcpy-server.jar" }
    }
    into(assetsDir)
}

// ————————————————— 发布签名 —————————————————
//
// 密钥**故意放在仓库外**（`../tailcat-signing/`），不能被提交进版本库：那个 p12 是应用的
// 身份，泄露等于任何人都能签出「同一个应用」的升级包冒充你。
//
// ⚠️ 必须用 **UTF-8 的 Reader** 读这个属性文件。`Properties.load(InputStream)` 按
// ISO-8859-1 解码，而 storeFile 的路径里含中文（`魔改apk`），读出来会是乱码、
// 然后报一个和签名毫无关系的「文件不存在」。
//
// 另注：这里用短名 `File` / `Properties` 而不是 `java.io.File` —— Kotlin 构建脚本里
// `java` 被 Gradle 的 java 插件扩展遮蔽，写全限定名会解析成 `JavaPluginExtension.io`。
val signingPropertiesFile: File? = listOfNotNull(
    providers.gradleProperty("tailcat.signing").orNull,
    System.getenv("TAILCAT_SIGNING"),
    rootProject.file("keystore.properties").absolutePath,
    rootProject.file("../tailcat-signing/keystore.properties").absolutePath,
).map { File(it) }.firstOrNull { it.isFile }

val signingProperties = Properties().apply {
    signingPropertiesFile?.let {
        FileInputStream(it).reader(Charsets.UTF_8).use { reader -> load(reader) }
    }
}

android {
    namespace = "cc.coauto.tailcat.shell"
    compileSdk = 36

    defaultConfig {
        // 应用身份。`namespace`（源码/R 类所在包）刻意保持不变，改它要连带搬动所有源文件，
        // 与「出一个可安装的签名包」无关，留作单独的整理项。
        applicationId = "cc.coauto.tailcat.manager"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (signingPropertiesFile != null) {
            create("release") {
                storeFile = File(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // 带后缀，让 debug 和 release 能同时装在测试机上。除了「不用先卸载 release」，
            // 它还让 debug 包保持 debuggable —— 排查孤儿进程这类问题要靠 `run-as` 以
            // 应用自己的 uid 执行命令（release 包不是 debuggable，run-as 会拒绝）。
            applicationIdSuffix = ".debug"
        }

        getByName("release") {
            // 不开 R8：这个包里 Compose、MIUIX 和 kotlinx.serialization 都吃反射/生成的
            // Serializer，混淆规则没配全套时**不会编译报错**，只会在运行时炸在某个协程里。
            // 体积换确定性，这种规模的 App 不值得赌。
            isMinifyEnabled = false
            isShrinkResources = false
            signingPropertiesFile?.let { signingConfig = signingConfigs.getByName("release") }
        }
    }

    // 必须把 native library 解压到 nativeLibraryDir，否则没有可执行的文件。
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    lint {
        // AGP 8.13.2 自带的 lint 用的是 Kotlin 2.2 的元数据读取器，读不了本项目
        // Kotlin 2.4.21 编出来的 `.kotlin_module`，于是 release 的 lint vital 必然失败：
        //
        //   Module was compiled with an incompatible version of Kotlin.
        //   The binary version of its metadata is 2.4.0, expected version is 2.2.0.
        //
        // 这是**工具链版本不匹配**，不是代码问题。debug 构建不跑 lint vital，所以之前一直
        // 没暴露。真正的保障在 core / desktop 的测试套件里。
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.matching { it.name.matches(Regex("merge.*JniLibFolders")) }.configureEach {
    dependsOn(prepareTailcatBinary)
    dependsOn(prepareAdbBinary)
}

// assets 是另一条打包路径，得单独挂钩子
tasks.matching { it.name.matches(Regex("merge.*Assets")) }.configureEach {
    dependsOn(prepareScrcpyServer)
}
