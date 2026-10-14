package cc.coauto.tailcat.desktop.win

import cc.coauto.tailcat.core.model.ManagerException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 两处「读别人的文件」的边界：地址簿目录不许是符号链接，实例目录里的唤醒请求是一次性的。
 */
class WindowsFileSafetyTest {

    private val temporaryDirectories = mutableListOf<java.io.File>()

    @AfterTest
    fun cleanUp() {
        temporaryDirectories.forEach { runCatching { it.deleteRecursively() } }
        temporaryDirectories.clear()
    }

    private fun tempDirectory(prefix: String): java.io.File =
        Files.createTempDirectory(prefix).toFile().also { temporaryDirectories += it }

    /** 建不出符号链接就直接返回（要管理员或开发者模式），避免在受限环境下误报失败。 */
    private fun symlinkOrNull(link: java.io.File, target: java.io.File): java.io.File? =
        runCatching { Files.createSymbolicLink(link.toPath(), target.toPath()) }
            .onFailure { println("跳过：当前环境不允许创建符号链接（${it.message}）") }
            .getOrNull()
            ?.let { link }

    @Test
    fun addressBookRefusesSymlinkedUsersDirectory() {
        val real = tempDirectory("tailcat-book-real")
        java.io.File(real, "users").mkdirs()
        java.io.File(java.io.File(real, "users"), "car").writeText("tc" + "a".repeat(40))

        val root = tempDirectory("tailcat-book-root")
        val link = symlinkOrNull(java.io.File(root, "users"), java.io.File(real, "users")) ?: return

        val error = assertFailsWith<ManagerException> { LegacyAddressBookReader.read(root.absolutePath) }
        assertTrue(
            error.message.orEmpty().contains("符号链接"),
            "应明确说明拒绝的是符号链接，实际：${error.message}",
        )
        // 链接本身没被删/被改写
        assertTrue(Files.isSymbolicLink(link.toPath()), "只读导入不该动链接本身")
    }

    @Test
    fun addressBookStillReadsRegularDirectory() {
        val root = tempDirectory("tailcat-book-ok")
        val users = java.io.File(root, "users").apply { mkdirs() }
        val address = "tc" + "b".repeat(40)
        java.io.File(users, "car").writeText("$address\n")
        // 非法条目（名字不合规）只计数，不导致整体失败
        java.io.File(users, "bad name").writeText("$address\n")

        val (entries, skipped) = LegacyAddressBookReader.read(root.absolutePath)
        assertEquals(listOf("car" to address), entries)
        assertEquals(1, skipped)
    }

    @Test
    fun activationRequestIsOneShot() {
        val directory = tempDirectory("tailcat-instance")
        assertTrue(SingleInstance.acquire(directory.absolutePath), "临时目录里应当能拿到实例锁")

        assertFalse(SingleInstance.consumeActivationRequest(), "没有请求时不该误报")

        SingleInstance.requestActivation()
        assertTrue(SingleInstance.consumeActivationRequest(), "写入请求后应当被消费到")
        assertFalse(SingleInstance.consumeActivationRequest(), "同一个请求只能唤醒一次")
    }
}
