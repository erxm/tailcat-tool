package cc.coauto.tailcat.core

import cc.coauto.tailcat.core.config.AppConfiguration
import cc.coauto.tailcat.core.config.ToolSettings
import cc.coauto.tailcat.core.model.ConnectionState
import cc.coauto.tailcat.core.model.DeviceProfile
import cc.coauto.tailcat.core.model.TaskState
import cc.coauto.tailcat.core.model.newId
import cc.coauto.tailcat.core.session.SessionManager
import cc.coauto.tailcat.core.session.SessionManagerDependencies
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 会话状态机的行为测试 —— 这份测试就是本次移植的规格说明书，
 * 逐条对应原 macOS 版 `Tests/TailcatCoreTests/SessionTests.swift`。
 */
class SessionManagerTest {

    private val address = "tc" + "A".repeat(30)

    private class Env(
        val manager: SessionManager,
        val runner: FakeRunner,
        val transport: FakeTransport,
        val profiles: List<DeviceProfile>,
        val vault: SpyCredentialStore,
        val store: MemoryConfigurationStore,
    )

    private fun env(
        profiles: List<DeviceProfile>? = null,
        vault: SpyCredentialStore = SpyCredentialStore(),
        store: MemoryConfigurationStore = MemoryConfigurationStore(),
    ): Env {
        val list = profiles ?: listOf(
            DeviceProfile(id = newId(), name = "A", preferredPort = 5556),
            DeviceProfile(id = newId(), name = "B", preferredPort = 5557),
        )
        list.forEach { vault.write(address, it.id) }
        val runner = FakeRunner()
        val transport = FakeTransport()
        val services = TestServices(runner, transport)
        val manager = SessionManager(
            configuration = AppConfiguration(
                profiles = list,
                tools = ToolSettings(
                    tailcat = "/test/bin/tailcat",
                    adb = "/test/bin/adb",
                    scrcpy = "/test/bin/scrcpy",
                ),
            ),
            deps = SessionManagerDependencies(store, vault, services),
            // 监视间隔调大，避免测试里触发轮询
            monitorIntervalMillis = 60_000,
        )
        return Env(manager, runner, transport, list, vault, store)
    }

    @Test
    fun concurrentSessionsAndStoppingOnePreservesOther() = runTest {
        val e = env()
        val first = e.manager.connect(e.profiles[0].id)!!
        val second = e.manager.connect(e.profiles[1].id)!!
        first.join()
        second.join()

        assertEquals("127.0.0.1:5556", e.manager.session(e.profiles[0].id).serial)
        assertEquals("127.0.0.1:5557", e.manager.session(e.profiles[1].id).serial)

        // 断开一台时，它的 ADB 入口必须先被摘掉、本地监听才允许关闭（否则端口可能被别的会话抢先复用）。
        var listenerKeptUntilAdbDisconnect = false
        e.runner.onDisconnect = { serial ->
            if (serial == "127.0.0.1:5556") {
                listenerKeptUntilAdbDisconnect = e.transport.handles[5556]?.isRunning == true
            }
        }
        e.manager.disconnect(e.profiles[0].id).join()

        assertTrue(listenerKeptUntilAdbDisconnect)
        assertFalse(e.transport.handles[5556]!!.isRunning)
        assertTrue(e.transport.handles[5557]!!.isRunning)
        assertEquals(ConnectionState.Online, e.manager.session(e.profiles[1].id).state)
        assertTrue(e.runner.calls.any { it == listOf("disconnect", "127.0.0.1:5556") })
        assertFalse(e.runner.calls.any { it == listOf("disconnect", "127.0.0.1:5557") })
        // 绝不重启全局 ADB 服务
        assertFalse(e.runner.calls.any { it.contains("kill-server") })
        e.manager.shutdown()
    }

    @Test
    fun tasksBindTargetEvenWhenAnotherDeviceConnects() = runTest {
        val e = env()
        e.manager.connect(e.profiles[0].id)!!.join()
        e.runner.operationDelayMillis = 60
        val task = e.manager.beginInstall(e.profiles[0].id, "/tmp/a file.apk")
        // 任务执行期间连上第二台设备，不应改变任务目标
        e.manager.connect(e.profiles[1].id)!!.join()
        e.manager.waitForTask(task)

        assertTrue(
            e.runner.calls.any { it == listOf("-s", "127.0.0.1:5556", "install", "-r", "/tmp/a file.apk") },
            "任务应绑定启动时的 serial",
        )
        assertFalse(e.runner.calls.any { it == listOf("-s", "127.0.0.1:5557", "install", "-r", "/tmp/a file.apk") })
        assertEquals(TaskState.Succeeded, e.manager.tasks.value.first { it.id == task }.state)
        e.manager.shutdown()
    }

    @Test
    fun unauthorizedKeepsTunnelAndRejectsDeviceOperations() = runTest {
        val e = env()
        e.runner.unauthorized += "127.0.0.1:5556"
        e.manager.connect(e.profiles[0].id)!!.join()

        assertEquals(ConnectionState.Unauthorized, e.manager.session(e.profiles[0].id).state)
        assertTrue(e.transport.handles[5556]!!.isRunning, "待授权时通道必须保留")

        assertEquals(
            ConnectionState.Offline,
            runCatching { e.manager.terminalCommand(e.profiles[0].id) }
                .exceptionOrNull()
                ?.let { ConnectionState.Offline } ?: ConnectionState.Online,
        )
        e.manager.shutdown()
    }

    @Test
    fun disconnectCancelsPendingConnectWithoutLeakingTunnel() = runTest {
        val e = env()
        e.transport.openDelayMillis = 150
        val job = e.manager.connect(e.profiles[0].id)
        delay(20)
        e.manager.disconnect(e.profiles[0].id).join()
        job?.join()

        assertEquals(ConnectionState.Disconnected, e.manager.session(e.profiles[0].id).state)
        assertTrue(e.transport.handles.values.all { !it.isRunning }, "不得泄漏本地监听")
        e.manager.shutdown()
    }

    @Test
    fun reconnectCancelsOldTasksBeforeReplacingSession() = runTest {
        val e = env()
        e.manager.connect(e.profiles[0].id)!!.join()
        val old = e.transport.handles[5556]!!
        e.runner.operationDelayMillis = 800
        val task = e.manager.beginShell(e.profiles[0].id, "sleep 1")
        // 等任务真的开始跑（已登记进 taskJobs），否则重连时无从取消
        assertTrue(awaitUntil { e.manager.tasks.value.any { it.id == task } })
        // 重连必须先取消该设备正在执行的任务，再替换会话
        e.manager.connect(e.profiles[0].id)!!.join()
        e.manager.waitForTask(task)

        assertEquals(TaskState.Cancelled, e.manager.tasks.value.first { it.id == task }.state)
        assertFalse(old.isRunning)
        assertTrue(e.transport.handles[5556]!!.isRunning)
        assertEquals(ConnectionState.Online, e.manager.session(e.profiles[0].id).state)
        e.manager.shutdown()
    }

    @Test
    fun unexpectedTransportExitDoesNotAffectOtherDevice() = runTest {
        val e = env()
        e.manager.connect(e.profiles[0].id)!!.join()
        e.manager.connect(e.profiles[1].id)!!.join()
        e.transport.crash(5556)
        // 退出回调从进程线程投递回状态线程，需要真实时间（runTest 的 delay 是虚拟时间）
        assertTrue(
            awaitUntil { e.manager.session(e.profiles[0].id).state == ConnectionState.Failed },
            "转发进程退出应把该设备标为失败",
        )

        assertEquals(ConnectionState.Failed, e.manager.session(e.profiles[0].id).state)
        assertNull(e.manager.session(e.profiles[0].id).serial)
        assertEquals(ConnectionState.Online, e.manager.session(e.profiles[1].id).state)
        e.manager.shutdown()
    }

    @Test
    fun temporaryDevicesAreMemoryOnlyAndCloseIndependently() = runTest {
        val saved = DeviceProfile(id = newId(), name = "Saved", preferredPort = 5556)
        val vault = SpyCredentialStore()
        val store = MemoryConfigurationStore()
        val e = env(profiles = listOf(saved), vault = vault, store = store)

        e.manager.connect(saved.id)!!.join()
        vault.resetCounts()

        val first = e.manager.saveTemporaryDevice(null, "", "tc" + "T".repeat(30), 6666, 5555)
        val second = e.manager.saveTemporaryDevice(null, "", "tc" + "U".repeat(30), 6667, 5555)
        assertEquals(listOf("临时设备 1", "临时设备 2"), e.manager.temporaryProfiles.value.map { it.name })

        e.manager.connect(first)!!.join()
        e.manager.connect(second)!!.join()
        assertEquals(ConnectionState.Online, e.manager.session(first).state)
        assertEquals(listOf(saved.id), e.manager.profiles.value.map { it.id })
        assertNull(store.saved?.profiles?.firstOrNull { it.id == first }, "临时设备不得写入配置")

        val task = e.manager.beginShell(first, "id")
        e.manager.waitForTask(task)
        assertEquals(TaskState.Succeeded, e.manager.tasks.value.first { it.id == task }.state)
        assertTrue(e.runner.calls.any { it == listOf("-s", "127.0.0.1:6666", "shell", "id") })

        e.manager.removeDevice(first)
        assertFalse(e.transport.handles[6666]!!.isRunning)
        assertTrue(e.transport.handles[6667]!!.isRunning)
        assertTrue(e.transport.handles[5556]!!.isRunning)
        assertFalse(e.manager.isTemporary(first))

        // 临时设备全程不碰持久凭据
        assertEquals(0, vault.reads)
        assertEquals(0, vault.writes)
        assertEquals(0, vault.deletes)

        e.manager.shutdown()
        assertTrue(e.manager.temporaryProfiles.value.isEmpty())
        assertTrue(e.transport.handles.values.all { !it.isRunning })
    }

    @Test
    fun promotionKeepsConnectionAndRunningTask() = runTest {
        val vault = SpyCredentialStore()
        val store = MemoryConfigurationStore()
        val e = env(profiles = emptyList(), vault = vault, store = store)

        val address = "tc" + "P".repeat(30)
        val id = e.manager.saveTemporaryDevice(null, "", address, 6666, 5555)
        e.manager.connect(id)!!.join()
        val handle = e.transport.handles[6666]!!
        val serial = e.manager.session(id).serial

        e.runner.operationDelayMillis = 60
        val task = e.manager.beginShell(id, "id")
        // 等任务真的开始跑。`runTest` 的 delay 是虚拟时间，不会给真实调度器任何机会。
        assertTrue(awaitUntil { e.manager.tasks.value.any { it.id == task } })

        val connectsBefore = e.runner.calls.count { it.firstOrNull() == "connect" }
        e.manager.promoteTemporaryDevice(id, "Keep this device")

        assertFalse(e.manager.isTemporary(id))
        assertEquals(1, e.manager.allProfiles().count { it.id == id })
        assertEquals(serial, e.manager.session(id).serial, "转正不得打断既有会话")
        assertTrue(handle.isRunning)
        assertEquals(0, handle.stopCount)
        assertEquals(connectsBefore, e.runner.calls.count { it.firstOrNull() == "connect" }, "转正不得触发重连")

        e.manager.waitForTask(task)
        assertEquals(TaskState.Succeeded, e.manager.tasks.value.first { it.id == task }.state)

        assertEquals(1, vault.writes)
        assertEquals(address, vault.read(id))
        assertEquals(id, store.saved?.profiles?.first { it.id == id }?.id)
        e.manager.shutdown()
    }

    @Test
    fun failedPromotionRollsBackThenStaysTemporary() = runTest {
        val vault = SpyCredentialStore()
        val store = MemoryConfigurationStore().apply { failOnSave = true }
        val e = env(profiles = emptyList(), vault = vault, store = store)

        val id = e.manager.saveTemporaryDevice(null, "", "tc" + "F".repeat(30), 6666, 5555)
        e.manager.connect(id)!!.join()

        assertFailsWith<Exception> { e.manager.promoteTemporaryDevice(id, "Failed save") }

        assertTrue(e.manager.isTemporary(id))
        assertTrue(e.manager.profiles.value.isEmpty())
        assertTrue(e.transport.handles[6666]!!.isRunning)
        // 凭据写入后落盘失败，必须回滚
        assertEquals(1, vault.writes)
        assertEquals(1, vault.deletes)
        assertNull(vault.read(id))
        e.manager.shutdown()
    }

    @Test
    fun repeatedImportPreservesEditsAndIsIdempotent() = runTest {
        val legacy = "tc" + "L".repeat(30)
        val entry = cc.coauto.tailcat.core.config.LegacyEntry("carA", legacy)
        val runner = FakeRunner()
        val transport = FakeTransport()
        val services = TestServices(runner, transport).apply {
            addressBook = { cc.coauto.tailcat.core.config.LegacyImport(listOf(entry), 0) }
        }
        val vault = SpyCredentialStore()
        val store = MemoryConfigurationStore()
        val manager = SessionManager(
            configuration = AppConfiguration(tools = ToolSettings("/test/bin/tailcat", "/test/bin/adb")),
            deps = SessionManagerDependencies(store, vault, services),
            monitorIntervalMillis = 60_000,
        )

        assertEquals(1, manager.importLegacy())
        val id = manager.profiles.value[0].id

        val edited = "tc" + "E".repeat(30)
        manager.saveDevice(id, "Edited name", edited, 6666, 5555)

        // 再次导入：已存在的用户名不再重复添加，也不覆盖 GUI 内的编辑
        assertEquals(0, manager.importLegacy())
        assertEquals(1, manager.profiles.value.size)
        assertEquals("Edited name", manager.profiles.value[0].name)
        assertEquals(edited, vault.read(id))

        // 配置文件里绝不能出现任何凭据
        val saved = store.saved!!
        assertTrue(saved.profiles.none { it.name.contains(legacy) || it.name.contains(edited) })
        manager.shutdown()
    }
}
