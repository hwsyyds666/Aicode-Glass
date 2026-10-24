package com.aicode.feature.agent.domain.container

import com.aicode.core.util.FileLogger
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * rg（ripgrep）保障的结果状态。
 */
sealed interface RipgrepStatus {
    /** rg 可用（[justInstalled] 表示本次调用刚自动装上）。 */
    data class Available(val justInstalled: Boolean) : RipgrepStatus

    /** 自动安装失败（无包管理器 / 网络不可达 / 无免密 sudo 等），调用方应走兜底。 */
    data class Unavailable(val reason: String) : RipgrepStatus

    /** 执行环境本身未就绪（rootfs 未解压 / SSH 未连接 / ROOT 不可用）。 */
    data object NotReady : RipgrepStatus
}

/**
 * rg 保障所需的执行环境抽象：按执行模式回答「当前目标环境 / 缓存 key / 是否就绪 / 执行命令」。
 *
 * 抽成接口是为了单测可替换——真实实现依赖 DataStore、SSH 连接与三套命令引擎，无法在 JVM 测试中构造。
 */
interface RgEnvironment {
    /** 当前全局执行模式对应的目标环境（AUTO 已归一化为具体环境）。 */
    fun currentTarget(): DelegatingCommandEngine.TargetEnv

    /**
     * 目标环境的缓存 key：同一容器内稳定，容器重建后变化。
     * 本地 = profile id + rootfs 安装标记 mtime（rootfs 重装/重置即变）；
     * 远程 = 连接身份 host:port:username（换服务器即变）；真机固定。
     */
    fun envKey(target: DelegatingCommandEngine.TargetEnv): String

    /** 目标环境是否已就绪可执行命令。 */
    fun isReady(target: DelegatingCommandEngine.TargetEnv): Boolean

    /** 在目标环境中同步执行命令并返回结果（环境未就绪时由实现返回 null）。 */
    suspend fun run(
        target: DelegatingCommandEngine.TargetEnv,
        command: String,
        timeoutMs: Long
    ): CommandResult?
}

/**
 * [RgEnvironment] 的生产实现。
 *
 * 本地容器缓存 key 带 rootfs 安装标记的 mtime：rootfs 重新解压（版本升级/重置/换镜像）会重写
 * 标记文件，key 随之变化、缓存自然失效——重建后的容器下次检测时重新补装 rg；而 apk add 装 rg
 * 本身不动标记，缓存不会误失效。
 */
class DefaultRgEnvironment @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val localEngine: LinuxContainerEngine,
    private val connection: RemoteSshConnection,
    private val containerInstaller: ContainerInstaller,
    private val delegatingEngine: DelegatingCommandEngine
) : RgEnvironment {

    override fun currentTarget(): DelegatingCommandEngine.TargetEnv = when (modeHolder.currentMode()) {
        ExecutionMode.REMOTE_SSH -> DelegatingCommandEngine.TargetEnv.REMOTE
        ExecutionMode.DEVICE_ROOT -> DelegatingCommandEngine.TargetEnv.DEVICE
        ExecutionMode.LOCAL_PROOT -> DelegatingCommandEngine.TargetEnv.CONTAINER
    }

    override fun envKey(target: DelegatingCommandEngine.TargetEnv): String = when (target) {
        DelegatingCommandEngine.TargetEnv.CONTAINER -> {
            val profile = localEngine.activeProfile()
            val marker = containerInstaller.installedMarkerFor(profile)
            "local:${profile.id}:${if (marker.isFile) marker.lastModified() else "absent"}"
        }
        DelegatingCommandEngine.TargetEnv.REMOTE ->
            connection.config?.let { "remote:${it.host}:${it.port}:${it.username}" } ?: "remote:unknown"
        DelegatingCommandEngine.TargetEnv.DEVICE -> "device"
        // currentTarget 已归一化，不会以 AUTO 查询；兜底防枚举新增值漏判
        DelegatingCommandEngine.TargetEnv.AUTO -> "auto"
    }

    override fun isReady(target: DelegatingCommandEngine.TargetEnv): Boolean =
        delegatingEngine.engineFor(target).isContainerInstalled()

    override suspend fun run(
        target: DelegatingCommandEngine.TargetEnv,
        command: String,
        timeoutMs: Long
    ): CommandResult? = delegatingEngine.engineFor(target)
        .runCommandSyncIfReady(command, timeoutMs = timeoutMs)
}

/**
 * rg（ripgrep）保障：让 search 工具「总有 rg 可用——没有就自动装，装不上就兜底 grep」。
 *
 * 背景（为何需要本类）：rg 不在 Alpine minirootfs 镜像里，原有唯一安装路径是首次进入终端时的
 * 交互式初始化菜单（provision.sh 选「自动安装」→ apk add ripgrep）。用户没进过终端、选了
 * 「手动安装」、rootfs 被重置重装、或远程/真机模式下，rg 缺失就是终态，search 直接报
 * 「容器内未安装 rg」。本类把安装并入容器初始化流程（[com.aicode.feature.agent.domain.container.
 * LinuxContainerEngine.ensureInstalled] / RemoteSshEngine.ensureInstalled 调用），并提供按容器
 * 缓存的检测，供 search 工具在 rg 缺失时自愈。
 *
 * 设计要点：
 * - 幂等：已装则跳过（命令内 command -v 预检 + 按容器缓存），安装末尾 `rg --version` 校验；
 * - 按容器缓存：key 见 [RgEnvironment.envKey]，容器重建（rootfs 重装 / 换 SSH 服务器）即失效；
 * - 失败不永久缓存：安装失败按 [UNAVAILABLE_TTL_MS] 缓存，过期后允许再次尝试（网络临时故障可自愈）；
 * - force：search 实际执行 rg 返回 127 时强制重检，覆盖「缓存说可用但 rg 被手动删掉」的误判。
 */
@Singleton
class RipgrepProvisioner @Inject constructor(
    private val environment: RgEnvironment
) {
    private companion object {
        const val TAG = "RipgrepProvisioner"

        /** 检测超时：command -v + --version，一次 proot/ssh fork 的量级。 */
        const val DETECT_TIMEOUT_MS = 20_000L

        /** 安装超时：apt/dnf 等要更新索引再装包，给足 4 分钟（仍在 CommandEngine 上限内）。 */
        const val INSTALL_TIMEOUT_MS = 240_000L

        /** 安装失败记录的缓存时长：避免每次 search 都重跑一遍注定失败的安装；过期后允许重试。 */
        const val UNAVAILABLE_TTL_MS = 5 * 60 * 1000L
    }

    /** envKey → (可用?, 记录时刻)。可用结果长期有效（容器 key 变化即失效）；失败按 TTL 允许重试。 */
    private val cache = ConcurrentHashMap<String, Pair<Boolean, Long>>()

    /** 安装串行化：避免多个 search 并发时重复触发安装。 */
    private val installMutex = Mutex()

    /**
     * 幂等地确保指定执行环境有可用的 rg。
     *
     * @param force 跳过缓存强制重检——供 search 在实际执行 rg 返回 127 时调用（缓存可能因
     *   用户手动删除 rg 等原因为陈旧可用状态）。
     * @param target 目标环境；null 按当前全局模式。供调用方显式指定通道使用——典型场景：
     *   真机 ROOT 模式下工作区内搜索改走本地容器（容器 bind 了工作区且有真 rg），
     *   rg 自愈也必须对容器目标生效，不能对真机目标（真机无包管理器，装了也白装）。
     */
    suspend fun ensureAvailable(
        force: Boolean = false,
        target: DelegatingCommandEngine.TargetEnv? = null
    ): RipgrepStatus {
        val effectiveTarget = target ?: environment.currentTarget()
        if (!environment.isReady(effectiveTarget)) {
            FileLogger.w(TAG, "执行环境未就绪，跳过 rg 保障: target=$effectiveTarget")
            return RipgrepStatus.NotReady
        }
        val key = environment.envKey(effectiveTarget)
        if (!force) cachedStatus(key)?.let { return it }
        return installMutex.withLock {
            // 等锁期间可能已被并发调用装好，再查一次缓存
            if (!force) cachedStatus(key)?.let { return it }
            runCatching { detectOrInstall(effectiveTarget) }
                .onFailure { FileLogger.e(TAG, "rg 保障执行异常: target=$effectiveTarget", it) }
                .fold(
                    onSuccess = { status ->
                        cache[key] = (status is RipgrepStatus.Available) to System.currentTimeMillis()
                        status
                    },
                    onFailure = { e ->
                        cache[key] = false to System.currentTimeMillis()
                        RipgrepStatus.Unavailable("rg 保障异常: ${e.message}")
                    }
                )
        }
    }

    /** 读缓存：可用长期有效；失败记录超 [UNAVAILABLE_TTL_MS] 后清除并允许重试。 */
    private fun cachedStatus(key: String): RipgrepStatus? {
        val (available, ts) = cache[key] ?: return null
        if (available) return RipgrepStatus.Available(justInstalled = false)
        if (System.currentTimeMillis() - ts < UNAVAILABLE_TTL_MS) {
            return RipgrepStatus.Unavailable("rg 自动安装刚失败，${UNAVAILABLE_TTL_MS / 60000} 分钟内不重复尝试")
        }
        cache.remove(key)
        return null
    }

    /** 检测 →（缺失则）安装 → 校验；返回 Available 或 Unavailable（缓存由调用方统一记）。 */
    private suspend fun detectOrInstall(target: DelegatingCommandEngine.TargetEnv): RipgrepStatus {
        if (detect(target)) {
            FileLogger.i(TAG, "rg 已就绪: target=$target")
            return RipgrepStatus.Available(justInstalled = false)
        }
        FileLogger.i(TAG, "rg 缺失，尝试自动安装: target=$target")
        val failure = install(target)
        if (failure == null) {
            FileLogger.i(TAG, "rg 自动安装成功: target=$target")
            return RipgrepStatus.Available(justInstalled = true)
        }
        FileLogger.w(TAG, "rg 自动安装失败: target=$target reason=$failure")
        return RipgrepStatus.Unavailable(failure)
    }

    /** 检测 rg 是否可用：command -v 定位 + --version 可运行，双通过才算可用。 */
    private suspend fun detect(target: DelegatingCommandEngine.TargetEnv): Boolean {
        val result = environment.run(target, RG_DETECT_COMMAND, DETECT_TIMEOUT_MS) ?: return false
        return result.exitCode == 0 && result.output.contains("ripgrep", ignoreCase = true)
    }

    /** 执行幂等安装；成功返回 null，失败返回原因（输出尾部，供提示用户）。 */
    private suspend fun install(target: DelegatingCommandEngine.TargetEnv): String? {
        val result = environment.run(target, RG_INSTALL_COMMAND, INSTALL_TIMEOUT_MS)
        if (result != null && result.exitCode == 0 && result.output.contains("ripgrep", ignoreCase = true)) {
            return null
        }
        return result?.output?.trim()?.takeLast(400)?.ifBlank { null }
            ?: "安装命令无输出（环境未就绪或执行被拒绝）"
    }
}

/**
 * rg 检测命令：先定位再验证可运行。
 *
 * PATH 前缀覆盖两类非默认布局：远程 SSH exec channel 是非登录 shell，不加载 /etc/profile 与
 * ~/.bashrc，rg 若装在 /usr/local/bin 或 ~/.local/bin 会不在默认 PATH 里；真机 `su -c` 的
 * PATH 亦由 su 实现决定。`${PATH:+:$PATH}` 避免 PATH 未定义时引入空项（空项 = 当前目录）。
 */
internal val RG_DETECT_COMMAND: String =
    """export PATH="${'$'}HOME/.local/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin${'$'}{PATH:+:${'$'}PATH}"; command -v rg >/dev/null 2>&1 && rg --version"""

/**
 * rg 幂等安装命令（本地容器 / 远程 SSH 通用，真机无包管理器自然走到 else 分支失败）：
 * 已装跳过；按包管理器（apk/apt-get/dnf/yum/pacman）安装 ripgrep，末尾 `rg --version` 校验。
 *
 * - 非 root 且无免密 sudo 时安装失败（`sudo -n` 快速失败不挂起），由调用方走 grep 兜底；
 * - apt 分支带 `apt-get update`：最小化 debootstrap 镜像可能没有包索引；
 * - `$SUDO` 故意不加引号：空值时消失，非空时展开为 `sudo -n` 两个词。
 */
internal val RG_INSTALL_COMMAND: String = """
export PATH="${'$'}HOME/.local/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin${'$'}{PATH:+:${'$'}PATH}"
command -v rg >/dev/null 2>&1 && rg --version >/dev/null 2>&1 && { echo "rg already installed"; exit 0; }
SUDO=""
[ "${'$'}(id -u 2>/dev/null)" = "0" ] || SUDO="sudo -n"
if command -v apk >/dev/null 2>&1; then
  ${'$'}SUDO apk add --no-cache ripgrep
elif command -v apt-get >/dev/null 2>&1; then
  DEBIAN_FRONTEND=noninteractive ${'$'}SUDO apt-get update -y && DEBIAN_FRONTEND=noninteractive ${'$'}SUDO apt-get install -y ripgrep
elif command -v dnf >/dev/null 2>&1; then
  ${'$'}SUDO dnf install -y ripgrep
elif command -v yum >/dev/null 2>&1; then
  ${'$'}SUDO yum install -y ripgrep
elif command -v pacman >/dev/null 2>&1; then
  ${'$'}SUDO pacman -Sy --noconfirm ripgrep
else
  echo "aicode: 未识别的包管理器（apk/apt-get/dnf/yum/pacman），无法自动安装 rg" >&2
  exit 1
fi
rg --version
""".trimIndent()
