package com.aicode.feature.agent.domain.device

import android.content.Context
import android.system.Os
import com.aicode.R
import com.aicode.core.util.BoundedLineReader
import com.aicode.core.util.FileLogger
import com.aicode.core.util.LINE_TRUNCATED_NOTE
import com.aicode.feature.agent.domain.container.BoundedOutput
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.MAX_UNBOUNDED_CHARS
import com.aicode.feature.agent.domain.container.sanitizeCommandForLog
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 真机 ROOT 命令执行后端：AI 的命令不走 PRoot 沙盒容器，直接以 root 身份在真机上执行。
 *
 * 实现方式（用户拍板）：每条命令经 `su -c` 执行（`ProcessBuilder(listOf("su", "-c", command))`，
 * argv 直传无需 shell 转义）。Magisk/KernelSU 首次执行时弹授权框，用户勾选「记住」后后续命令无感。
 *
 * 与 [com.aicode.feature.agent.domain.container.LinuxContainerEngine] 的差异：
 * - 无 rootfs/proot 安装概念——就绪判定 = 一次 `su` 探测（`id -u` 返回 0）；
 * - [projectPath] 是真机绝对路径，经 `cd '<path>' && <command>` 进入工作目录；
 * - 子进程环境为 su 默认 root 环境（PATH 通常含 /system/bin:/system/xbin:/sbin，具体取决于 su 实现）。
 *
 * 安全提示：该通道下命令以 uid 0 运行，授权挡位（[com.aicode.feature.settings.data.repository.CommandAuthLevel]）
 * 与灾难性 rm 防护照常生效；UI 在切换到本通道时另有倒计时确认。
 */
@Singleton
class DeviceRootEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pathHomeResolver: PathHomeResolver,
    private val workspaceRepository: WorkspaceRepository,
    private val containerInstaller: ContainerInstaller
) : CommandEngine {

    // 非 private：终端/文件访问等同类实现需要复用 [shellQuote] 与超时常量。
    companion object {
        private const val TAG = "DeviceRootEngine"

        /** 子进程 stdin 接空设备：防「等输入」型程序阻塞到超时（同 LinuxContainerEngine）。 */
        private val STDIN_FROM_DEV_NULL: ProcessBuilder.Redirect =
            ProcessBuilder.Redirect.from(java.io.File("/dev/null"))

        /** 命令超时上限（毫秒），与 [CommandEngine.MAX_TIMEOUT_MS] 对齐。 */
        private const val MAX_TIMEOUT_MS = CommandEngine.MAX_TIMEOUT_MS

        /** 超时后给进程的优雅退出宽限（毫秒），过后强杀。 */
        private const val TIMEOUT_KILL_GRACE_MS = 200L

        /** su 探测结果缓存时长（毫秒）：避免每条命令都 fork 一次探测进程。 */
        private const val PROBE_CACHE_MS = 30_000L

        /** 单引号包裹 shell 字符串（`'` → `'\''`）。 */
        fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
    }

    /** 真机通道无需初始化：进度恒 Ready。 */
    private val _initProgress = MutableStateFlow<ContainerInitState>(ContainerInitState.Ready)
    override val initProgress: StateFlow<ContainerInitState> = _initProgress.asStateFlow()

    /** su 探测缓存：结果 + 探测时刻。 */
    @Volatile
    private var probeCache: Pair<Boolean, Long>? = null

    /**
     * 探测 ROOT 是否可用：`su -c id -u` 退出码 0 且输出首行为 "0"。
     * 结果缓存 [PROBE_CACHE_MS] 毫秒；首次探测可能触发 su 授权弹窗（取决于 su 实现）。
     */
    private fun probeRoot(): Boolean {
        val now = System.currentTimeMillis()
        probeCache?.let { (ok, ts) -> if (now - ts < PROBE_CACHE_MS) return ok }
        val ok = runCatching {
            val process = ProcessBuilder(listOf("su", "-c", "id -u"))
                .redirectErrorStream(true)
                .redirectInput(STDIN_FROM_DEV_NULL)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            exitCode == 0 && output.lineSequence().firstOrNull()?.trim() == "0"
        }.getOrElse {
            FileLogger.w(TAG, "su 探测失败: ${it.message}")
            false
        }
        probeCache = ok to now
        return ok
    }

    /** 强制重新探测（设置页「检测 ROOT」按钮用，绕过缓存）。 */
    fun reprobeRoot(): Boolean {
        probeCache = null
        return probeRoot()
    }

    override fun isContainerInstalled(): Boolean = probeRoot()

    override fun isProvisioned(): Boolean = probeRoot()

    override fun defaultShell(): String = "/system/bin/sh"

    override fun notReadyHint(): String? =
        if (probeRoot()) null else context.getString(R.string.device_root_not_ready_hint)

    override suspend fun ensureInstalled() {
        if (!probeRoot()) {
            throw IllegalStateException(context.getString(R.string.device_root_not_ready_hint))
        }
    }

    override fun runCommandStream(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        notReadyHint()?.let {
            emit(CommandEvent.Line(it))
            emit(CommandEvent.Exit(null))
            return@flow
        }
        emitAll(streamExec(command, projectPath, timeoutMs))
    }.flowOn(Dispatchers.IO)

    /**
     * 流式执行（与 [com.aicode.feature.agent.domain.container.LinuxContainerEngine] 同构）：
     * 逐行 emit [CommandEvent.Line]，结束 emit [CommandEvent.Exit]；超时由看门狗 destroy 进程解除
     * readLine 阻塞，并追加超时提示行、Exit(null)。
     */
    private fun streamExec(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): Flow<CommandEvent> = flow {
        val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
        FileLogger.d(TAG, "执行命令(真机,流式) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
        val process = startRootProcess(command, projectPath)
        val timedOut = AtomicBoolean(false)
        val watchScope = CoroutineScope(Dispatchers.IO + Job())
        val watchdog = launchKillWatchdog(watchScope, process, effectiveTimeout, timedOut, command)
        val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause is CancellationException && process.isAlive) {
                FileLogger.i(TAG, "命令被取消，终止进程: ${sanitizeCommandForLog(command)}")
                runCatching { process.destroy() }
                runCatching { process.destroyForcibly() }
            }
        }
        val reader = BoundedLineReader(InputStreamReader(process.inputStream))
        try {
            while (true) {
                val line = reader.readLine() ?: break
                emit(CommandEvent.Line(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text))
            }
            val exitCode = process.waitFor()
            watchdog.cancel()
            if (timedOut.get()) {
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: ${sanitizeCommandForLog(command)}")
                emit(CommandEvent.Line(timeoutNotice(effectiveTimeout)))
                emit(CommandEvent.Exit(null))
            } else {
                if (exitCode != 0) FileLogger.w(TAG, "命令退出码=$exitCode: ${sanitizeCommandForLog(command)}")
                emit(CommandEvent.Exit(exitCode))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 与容器引擎同理：看门狗 destroy 关闭管道会让 readLine 抛 IOException，按超时/异常分流，
            // 保证 Exit 一定 emit、已输出内容不丢。
            watchdog.cancel()
            if (timedOut.get()) {
                emit(CommandEvent.Line(timeoutNotice(effectiveTimeout)))
                emit(CommandEvent.Exit(null))
            } else {
                FileLogger.e(TAG, "命令读输出异常(已保留此前输出): ${sanitizeCommandForLog(command)}", e)
                emit(CommandEvent.Line("[命令执行异常：${e.message}]"))
                emit(CommandEvent.Exit(null))
            }
        } finally {
            cancellationHook?.dispose()
            watchdog.cancel()
            watchScope.cancel()
            runCatching { reader.close() }
            runCatching { process.destroy() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun runCommandSync(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): String = withContext(Dispatchers.IO) {
        notReadyHint()?.let { return@withContext it }
        execCaptured(command, projectPath, timeoutMs).output
    }

    override suspend fun runCommandSyncWithExit(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        notReadyHint()?.let { return@withContext CommandResult(it, null) }
        val r = execCaptured(command, projectPath, timeoutMs)
        CommandResult(r.output, r.exitCode, r.truncated)
    }

    override suspend fun runCommandSyncUnbounded(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult = withContext(Dispatchers.IO) {
        notReadyHint()?.let { return@withContext CommandResult(it, null) }
        val r = execCaptured(command, projectPath, timeoutMs, unbounded = true)
        CommandResult(r.output, r.exitCode, r.truncated)
    }

    override suspend fun runCommandSyncIfReady(
        command: String,
        projectPath: String?,
        timeoutMs: Long
    ): CommandResult? {
        if (!probeRoot()) return null
        val r = execCaptured(command, projectPath, timeoutMs)
        return CommandResult(r.output, r.exitCode, r.truncated)
    }

    /** 一次真机执行的内部结果：限幅后的完整输出 + 退出码（超时/异常时为 null）。 */
    private data class ExecResult(val output: String, val exitCode: Int?, val truncated: Boolean = false)

    /** 同步执行并捕获输出（与容器引擎同构：限幅累积 + 看门狗 + 取消回收）。 */
    private suspend fun execCaptured(
        command: String,
        projectPath: String?,
        timeoutMs: Long,
        unbounded: Boolean = false
    ): ExecResult = withContext(Dispatchers.IO) {
        try {
            val effectiveTimeout = timeoutMs.coerceIn(1L, MAX_TIMEOUT_MS)
            FileLogger.d(TAG, "执行命令(真机,同步) cwd=$projectPath timeout=${effectiveTimeout}ms: ${sanitizeCommandForLog(command)}")
            val process = startRootProcess(command, projectPath)
            val timedOut = AtomicBoolean(false)
            val cancellationHook = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                if (cause is CancellationException && process.isAlive) {
                    FileLogger.i(TAG, "命令被取消，终止进程: ${sanitizeCommandForLog(command)}")
                    runCatching { process.destroy() }
                    runCatching { process.destroyForcibly() }
                }
            }

            val output = if (unbounded) BoundedOutput.hardCapped(MAX_UNBOUNDED_CHARS) else BoundedOutput()
            var exitCode: Int? = null
            try {
                coroutineScope {
                    val watchdog = launchKillWatchdog(this, process, effectiveTimeout, timedOut, command)
                    val reader = BoundedLineReader(InputStreamReader(process.inputStream))
                    try {
                        while (true) {
                            val line = reader.readLine() ?: break
                            output.append(if (line.truncated) "${line.text}\n$LINE_TRUNCATED_NOTE" else line.text)
                            output.append("\n")
                        }
                        exitCode = process.waitFor()
                    } finally {
                        watchdog.cancel()
                        runCatching { reader.close() }
                    }
                }
            } finally {
                cancellationHook?.dispose()
                runCatching { process.destroy() }
            }

            if (timedOut.get()) {
                FileLogger.w(TAG, "命令超时(${effectiveTimeout}ms)已终止: ${sanitizeCommandForLog(command)}")
                output.append(timeoutNotice(effectiveTimeout))
                output.append("\n")
                ExecResult(output.build(), null, output.truncated)
            } else {
                ExecResult(output.build(), exitCode, output.truncated)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "执行命令异常: ${sanitizeCommandForLog(command)}", e)
            ExecResult("Error: ${e.message}", null)
        }
    }

    /** 看门狗：到点进程仍存活则标记超时并优雅→强制终止（真机通道无凭据弹窗宽限场景）。 */
    private fun launchKillWatchdog(
        scope: CoroutineScope,
        process: Process,
        timeoutMs: Long,
        timedOut: AtomicBoolean,
        command: String
    ): Job = scope.launch {
        delay(timeoutMs)
        if (!process.isAlive) return@launch
        timedOut.set(true)
        FileLogger.w(TAG, "命令执行超过 ${timeoutMs}ms，终止进程: ${sanitizeCommandForLog(command)}")
        runCatching { process.destroy() }
        delay(TIMEOUT_KILL_GRACE_MS)
        if (process.isAlive) runCatching { process.destroyForcibly() }
    }

    /** 超时提示行（拼进输出，喂回模型/展示给用户）。 */
    private fun timeoutNotice(timeoutMs: Long): String =
        "[命令执行超时：超过 ${timeoutMs}ms 已被强制终止]"

    /**
     * 以 root 身份启动命令：`su -c '<command>'`；[projectPath] 非空时先 `cd` 进去。
     * argv 逐项传递（不经 shell 拼接），命令文本本身仍由 root shell 解析（与容器引擎语义一致）。
     *
     * 前缀先 `export HOME=<filesDir>`：su 会把 HOME 重置为 /root（设备上不存在），
     * 而提示词约定工作区别名为 `~/workspace`——HOME 指向 filesDir 后，shell 内 `~/workspace`
     * 与文件工具的路径别名解析到同一处（经 [refreshWorkspaceLinks] 建的符号链接）。
     */
    private fun startRootProcess(command: String, projectPath: String?): Process {
        refreshWorkspaceLinks()
        val sb = StringBuilder()
        sb.append("export HOME=").append(shellQuote(pathHomeResolver.home())).append("; ")
        if (!projectPath.isNullOrBlank()) {
            sb.append("cd ").append(shellQuote(projectPath)).append(" && ")
        }
        sb.append(command)
        val pb = ProcessBuilder(listOf("su", "-c", sb.toString()))
        pb.redirectErrorStream(true)
        pb.redirectInput(STDIN_FROM_DEV_NULL)
        return pb.start()
    }

    /** 上次成功建链的工作区路径：工作区未变时跳过，避免每条命令重复 stat/symlink。 */
    @Volatile
    private var linkedWorkspace: String? = null

    /**
     * 在 `$HOME`（App 私有 filesDir）下建 `workspace` / `.aicode` 符号链接，分别指向当前工作区
     * 与全局配置目录——对齐容器模式里 `~/workspace` bind mount、`/root/.aicode` 绑定的布局，
     * 让真机 root shell 里手动 `cd ~/workspace`、`ls ~/.aicode` 与文件工具的路径别名一致。
     *
     * best-effort：链接建在 ext4 的 filesDir 内（app 自己可建，无需 su），指向 /sdcard 等
     * 外部目标也没问题；仅在目标已是真实目录/文件时不覆盖。任何失败静默忽略——
     * 文件工具的别名映射不依赖链接真实存在。
     */
    fun refreshWorkspaceLinks() {
        val ws = workspaceRepository.currentPath().trim().trimEnd('/')
        if (ws.isEmpty() || ws == linkedWorkspace) return
        val home = context.filesDir.absolutePath
        linkIfPossible(File(home, "workspace"), ws)
        linkIfPossible(File(home, ".aicode"), containerInstaller.aicodeDir.absolutePath)
        linkedWorkspace = ws
    }

    /** 建单个符号链接；[link] 已是指向同一目标的链接时跳过，已是真实文件/目录时不覆盖。 */
    private fun linkIfPossible(link: File, target: String) {
        runCatching {
            val path = link.toPath()
            if (java.nio.file.Files.isSymbolicLink(path)) {
                val existing = runCatching { Os.readlink(link.absolutePath) }.getOrNull()
                if (existing == target) return
                if (!link.delete()) return
            } else if (link.exists()) {
                return
            }
            Os.symlink(target, link.absolutePath)
        }.onFailure { FileLogger.w(TAG, "建符号链接失败 ${link.absolutePath} -> $target: ${it.message}") }
    }
}
