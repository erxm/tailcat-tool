package cc.coauto.tailcat.core.creds

import cc.coauto.tailcat.core.model.ManagerException
import cc.coauto.tailcat.core.proc.Command
import cc.coauto.tailcat.core.proc.CommandExecuting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 用哪一种身份跑 tailcat。
 *
 * 连接与探测**必须分开**：两边都可能是「同时在跑的进程」，共用一个身份就会让两个进程
 * 拿同一个 node key 去连 DERP，互相抢路径。
 */
public enum class ClientIdentityRole(public val tag: String) {
    /** 真正承载转发的 `tailcat forward`。 */
    Connection("connection"),

    /** 链路探测的 `tailcat ping`（心跳与「测试全部设备」共用这一档）。 */
    Probe("probe"),
}

/**
 * 每台设备一个**持久化**的 tailcat 客户端身份。
 *
 * 为什么需要它：不传 `--key` 时，tailcat 会找已落盘的默认客户端密钥
 * （`$CONFIG/tailcat/keys/client-default.private.json`），有就**所有进程共用**它 ——
 * 两个并发会话会拿着同一个 node key 抢同一条 DERP 身份；没有则退回临时密钥，
 * 每次连接都换一个身份（重连要重新打洞，对端看到的也是一个新节点）。
 * 两种情况都不是我们想要的，所以要显式按「设备 × 角色」各给一个稳定密钥。
 *
 * ## 为什么用密钥名而不是文件路径
 *
 * macOS 原版把身份文件放在自己的配置目录里，于是不得不自己处理：建目录、`0600` 权限、
 * 属主校验、拒绝符号链接、`flock` 串行化。这套东西 core 一行都写不了
 * （commonMain 不能碰文件系统）。
 *
 * 而 tailcat 的 `--key` 本来就支持**名字**：`--key=foo` 读写的是
 * `$CONFIG/tailcat/keys/foo.private.json`，目录由它自己建、写入由它自己管。
 * 所以这里只传名字，文件层面的安全交给真正写文件的那个程序 —— 代码量少一个数量级，
 * 也不会出现「我们的校验规则和 tailcat 的不一致」这种裂缝。
 *
 * 名字带 `tailcat-tool-` 前缀：`$CONFIG/tailcat/keys/` 是**同一台机器上所有 tailcat
 * 使用者共用的**（Windows 上是 `%APPDATA%\tailcat\keys`），不加前缀容易和别人的撞名。
 *
 * ## 并发
 *
 * 同一个名字的「初始化 + 使用」按名字串行（[usingIdentity] 全程持锁）。
 * 只做进程内串行是够的：桌面端有单实例互斥锁，Android 端只有一个应用进程。
 * 跨进程的极端情况由 tailcat 自己兜底 —— `genkey` **不带 `--force` 时拒绝覆盖已有文件**，
 * 所以就算撞上，最坏结果是「读到一个已经建好的身份」，不会把密钥悄悄换掉。
 */
public class ClientIdentityStore(
    private val runner: CommandExecuting,
    private val namespace: String = "tailcat-tool",
) {

    /** 以名字为粒度的串行锁。 */
    private val lanes = HashMap<String, Mutex>()
    private val lanesGuard = Mutex()

    /** 只取参数、不持有锁执行任何东西（连接用：`forward` 是长命进程，不该一直占着这条道）。 */
    public suspend fun argument(
        executable: String,
        deviceId: String,
        role: ClientIdentityRole,
    ): String = usingIdentity(executable, deviceId, role) { it }

    /**
     * 取参数并在**[block] 执行期间一直持有**这个名字的锁。
     *
     * 探测走这条路径：同一个持久身份不能被两个 `tailcat ping` 进程同时用。
     * 后到的调用会排队等前一个跑完，而不是悄悄换成一个临时身份 —— 那等于探测的和连接的
     * 压根不是同一个节点，结果没有意义。
     */
    public suspend fun <T> usingIdentity(
        executable: String,
        deviceId: String,
        role: ClientIdentityRole,
        block: suspend (String) -> T,
    ): T {
        val name = keyName(deviceId, role)
        return laneFor(name).withLock {
            ensureKey(executable, name)
            block("--key=$name")
        }
    }

    private suspend fun laneFor(name: String): Mutex =
        lanesGuard.withLock { lanes.getOrPut(name) { Mutex() } }

    private fun keyName(deviceId: String, role: ClientIdentityRole): String {
        val name = "$namespace-$deviceId-${role.tag}"
        // 名字最后会变成文件名，所以只放行「不可能构成路径」的字符。
        // 设备 id 是本应用自己生成的 UUID 形状，正常永远命中；这条是防将来有人把
        // 外部数据（导入的地址簿之类）喂进来。
        if (!KEY_NAME.matches(name)) throw ManagerException("客户端身份名称无效：$name")
        return name
    }

    /**
     * 确保身份文件存在。已存在就什么都不做 —— **绝不覆盖**。
     *
     * 判定「存在」用 `printpub` 而不是查文件：查文件要猜 tailcat 的配置目录在哪，
     * 而 `printpub` 走的正是 tailcat 自己那条解析路径，还顺手把公钥读出来了。
     */
    private suspend fun ensureKey(executable: String, name: String) {
        if (publicKey(executable, name) != null) return

        val created = try {
            runner.run(
                Command(executable, listOf("genkey", "--client", "--key=$name"), timeoutMillis = 5_000),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            throw ManagerException("无法创建客户端身份：${t.message.orEmpty()}")
        }

        // 再读一次：要么刚才生成成功，要么另一个进程抢先建好了 —— 两种都是好事。
        if (publicKey(executable, name) != null) return

        val detail = created.output.trim().lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
        throw ManagerException(
            if (created.exitCode == 0) {
                "客户端身份 $name 已生成但读不出公钥"
            } else {
                // 到这一步通常意味着身份文件存在但内容坏了。**不要**自作主张换身份：
                // 换了之后对端看到的是另一个节点，而且用户无从得知。
                "客户端身份不可用（不会自动更换已有身份）：$detail"
            },
        )
    }

    /** 读已落盘身份的公钥；不存在或不可读都返回 null（交给调用方决定是生成还是报错）。 */
    private suspend fun publicKey(executable: String, name: String): String? {
        val result = try {
            runner.run(
                Command(executable, listOf("--key=$name", "printpub"), timeoutMillis = 5_000),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // 名字不存在时 tailcat 就是非零退出 + 一句「open ...: cannot find the path」，
            // 这里不该把它当异常往上抛 —— 「还没生成」是正常路径。
            return null
        }
        if (result.timedOut || result.cancelled || result.exitCode != 0) return null
        return result.output.lineSequence()
            .map { it.trim() }
            .lastOrNull { NODE_KEY.matches(it) }
    }

    private companion object {
        /** 名字最终是文件名：不允许 `.`、`/`、`\` 之类能构成路径的东西。 */
        val KEY_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,95}$")

        /** `printpub` 的输出就是 `nodekey:<64 位十六进制>`。 */
        val NODE_KEY = Regex("^nodekey:[0-9a-fA-F]{64}$")
    }
}
