package cc.coauto.tailcat.core

import cc.coauto.tailcat.core.creds.ClientIdentityRole
import cc.coauto.tailcat.core.creds.ClientIdentityStore
import cc.coauto.tailcat.core.model.ManagerException
import cc.coauto.tailcat.core.proc.Command
import cc.coauto.tailcat.core.proc.CommandExecuting
import cc.coauto.tailcat.core.proc.CommandResult
import cc.coauto.tailcat.core.proc.RunningProcess
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 客户端身份的获取与串行化。
 *
 * 与原 macOS 版 `IdentityTests.swift` 对应，但实现换了一种做法：那边把身份文件放在自己的
 * 配置目录里、自己管权限和 flock；这里只给 tailcat 一个**名字**，文件读写全由它负责。
 * 所以这里的用例断言的是「命令长什么样、什么时候才会生成、会不会互相踩」。
 */
class ClientIdentityTest {

    private val device = "11111111-2222-3333-4444-555555555555"
    private val tailcat = "/t/tailcat"

    /** 模拟 tailcat 的密钥目录：`printpub` 读、`genkey` 建且**拒绝覆盖**。 */
    private class KeyRunner : CommandExecuting {
        val calls = mutableListOf<List<String>>()
        val keys = mutableSetOf<String>()

        /** 文件在、但读不出公钥（内容损坏的情形）。 */
        val unreadable = mutableSetOf<String>()
        var runDelayMillis = 0L

        var active = 0
        var maxActive = 0

        override suspend fun start(
            command: Command,
            onOutput: (String) -> Unit,
            onExit: (Int) -> Unit,
        ): RunningProcess = FakeProcess(1)

        override suspend fun run(command: Command, onOutput: (String) -> Unit): CommandResult {
            val args = command.arguments
            calls += args

            if (args.firstOrNull()?.startsWith("--key=") == true && args.getOrNull(1) == "printpub") {
                val name = args[0].removePrefix("--key=")
                return when {
                    name in unreadable -> CommandResult(exitCode = 1, output = "unable to read key\n")
                    name in keys -> CommandResult(output = "nodekey:" + "a".repeat(64) + "\n")
                    else -> CommandResult(exitCode = 1, output = "open $name.private.json: no such file\n")
                }
            }

            if (args.firstOrNull() == "genkey") {
                val name = args.firstOrNull { it.startsWith("--key=") }?.removePrefix("--key=").orEmpty()
                return if (keys.add(name)) {
                    CommandResult(exitCode = 0)
                } else {
                    CommandResult(exitCode = 1, output = "$name already exists; use --force to overwrite\n")
                }
            }

            // 其余命令（即真正跑起来的 ping）：记录并发峰值。
            active++
            if (active > maxActive) maxActive = active
            if (runDelayMillis > 0) delay(runDelayMillis)
            active--
            return CommandResult(output = "ok\n")
        }
    }

    @Test
    fun createsIdentityOnFirstUseThenReusesIt() = runTest {
        val runner = KeyRunner()
        val store = ClientIdentityStore(runner)

        val argument = store.argument(tailcat, device, ClientIdentityRole.Connection)

        assertEquals("--key=tailcat-tool-$device-connection", argument)
        assertEquals(1, runner.calls.count { it.firstOrNull() == "genkey" })
        assertEquals(setOf("tailcat-tool-$device-connection"), runner.keys)

        // 第二次只该读，不该再生成 —— 身份必须稳定，否则对端每次看到的都是新节点。
        store.argument(tailcat, device, ClientIdentityRole.Connection)
        assertEquals(1, runner.calls.count { it.firstOrNull() == "genkey" })
    }

    @Test
    fun connectionAndProbeGetSeparateIdentities() = runTest {
        val runner = KeyRunner()
        val store = ClientIdentityStore(runner)

        val connection = store.argument(tailcat, device, ClientIdentityRole.Connection)
        val probe = store.argument(tailcat, device, ClientIdentityRole.Probe)

        assertNotEquals(connection, probe)
        assertEquals(2, runner.keys.size, "连接与探测不能共用一个 node key")
    }

    @Test
    fun differentDevicesGetSeparateIdentities() = runTest {
        val runner = KeyRunner()
        val store = ClientIdentityStore(runner)
        val other = "99999999-8888-7777-6666-555555555555"

        store.argument(tailcat, device, ClientIdentityRole.Connection)
        store.argument(tailcat, other, ClientIdentityRole.Connection)

        assertEquals(2, runner.keys.size)
    }

    @Test
    fun invalidDeviceIdIsRejectedBeforeRunningAnything() = runTest {
        val runner = KeyRunner()
        val store = ClientIdentityStore(runner)

        // 名字最后会变成文件名，所以绝不允许它构成路径。
        assertFailsWith<ManagerException> {
            store.argument(tailcat, "../../evil", ClientIdentityRole.Connection)
        }
        assertTrue(runner.calls.isEmpty(), "名字不合法时不该真的去跑 tailcat")
    }

    @Test
    fun probesForTheSameDeviceAreSerialized() = runTest {
        val runner = KeyRunner().apply { runDelayMillis = 1_000 }
        val store = ClientIdentityStore(runner)

        val jobs = List(3) {
            launch {
                store.usingIdentity(tailcat, device, ClientIdentityRole.Probe) { argument ->
                    runner.run(Command(tailcat, listOf(argument, "ping", "tc" + "A".repeat(30))))
                }
            }
        }
        jobs.joinAll()

        assertEquals(1, runner.maxActive, "同一设备的探测必须排队，不能两个进程同时用一个身份")
    }

    @Test
    fun connectionDoesNotSerializeTheWholeSession() = runTest {
        val runner = KeyRunner().apply { runDelayMillis = 1_000 }
        val store = ClientIdentityStore(runner)

        // 连接只取参数、不长占这条道：两个不同设备的长命转发可以并行起。
        val jobs = List(2) { index ->
            launch {
                store.argument(tailcat, "device-$index", ClientIdentityRole.Connection)
                delay(1_000)
                runner.run(Command(tailcat, listOf("forward")))
            }
        }
        jobs.joinAll()

        assertEquals(2, runner.maxActive)
    }

    @Test
    fun unreadableIdentityIsNeverReplaced() = runTest {
        val runner = KeyRunner()
        val name = "tailcat-tool-$device-connection"
        runner.keys += name
        runner.unreadable += name
        val store = ClientIdentityStore(runner)

        val error = assertFailsWith<ManagerException> {
            store.argument(tailcat, device, ClientIdentityRole.Connection)
        }

        assertTrue(error.message.orEmpty().contains("不会自动更换"), "错误信息要说明身份不会被换掉")
        assertTrue(name in runner.keys, "绝不能删除或覆盖已有身份")
        assertEquals(
            0,
            runner.calls.count { it.firstOrNull() == "genkey" && "--force" in it },
            "永远不能带 --force",
        )
    }

    @Test
    fun generationFailingForOtherReasonsSurfacesTheDetail() = runTest {
        val runner = object : CommandExecuting {
            override suspend fun start(
                command: Command,
                onOutput: (String) -> Unit,
                onExit: (Int) -> Unit,
            ): RunningProcess = FakeProcess(1)

            override suspend fun run(command: Command, onOutput: (String) -> Unit): CommandResult =
                if (command.arguments.getOrNull(1) == "printpub") {
                    CommandResult(exitCode = 1, output = "no such file\n")
                } else {
                    CommandResult(exitCode = 1, output = "permission denied\n")
                }
        }
        val store = ClientIdentityStore(runner)

        val error = assertFailsWith<ManagerException> {
            store.argument(tailcat, device, ClientIdentityRole.Connection)
        }
        assertTrue(error.message.orEmpty().contains("permission denied"), "要把 tailcat 的原始报错带出来")
    }
}
