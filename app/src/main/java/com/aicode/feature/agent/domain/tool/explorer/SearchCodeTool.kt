package com.aicode.feature.agent.domain.tool.explorer

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.BundledRipgrep
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.DelegatingCommandEngine
import com.aicode.feature.agent.domain.container.RipgrepProvisioner
import com.aicode.feature.agent.domain.container.RipgrepStatus
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import kotlinx.coroutines.CancellationException
import java.io.File
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * ripgrep 搜索工具：参数原样传给执行环境内的 ripgrep；支持 `| head [-n N]` 截断输出，
 * 其余管道命令一律拒绝（见 [buildSearchCommand]），执行环境未就绪则报错。
 *
 * rg 缺失时不再是终态：先经 [RipgrepProvisioner] 自动安装一次（幂等）并重试，仍不可用则
 * 回退 grep 搜索（[buildFallbackSearchCommand]，输出格式 `文件:行号:内容` 与 rg 一致），
 * 保证搜索始终可用；只有安装与兜底都失败才报错并给出可操作说明。
 *
 * 执行环境选择（[pickSearchEnv]）：默认跟随全局执行模式；**真机 ROOT 模式下搜索目标整体
 * 位于当前工作区内时改走本地容器**——工作区已 bind mount 到容器 `~/workspace`（与真机
 * symlink 指向同一份文件），容器内有真 rg（缺失也可 apk add 自装）；真机没有包管理器，
 * rg 装不上只能退 toybox grep（无 `--exclude-dir` 的老版本还会用法错误）。工作区外路径
 * （/system、/data 等真机专属）才走真机引擎，容器里看不到这些路径。
 */
class SearchCodeTool @Inject constructor(
    private val delegatingEngine: DelegatingCommandEngine,
    private val modeHolder: ExecutionModeHolder,
    private val workspaceRepository: WorkspaceRepository,
    private val pathHomeResolver: PathHomeResolver,
    private val ripgrepProvisioner: RipgrepProvisioner,
    private val bundledRipgrep: BundledRipgrep
) : AgentTool() {

    private companion object {
        const val TAG = "SearchTool"
        const val SEARCH_TIMEOUT_MS = 30_000L

        /** 容器内工作区挂载点：与 LinuxContainerEngine.buildBaseProotArgv 的 bind 目标一致。 */
        const val CONTAINER_WORKSPACE = "/root/workspace"

        /** rg 缺失时的最大自愈次数：第一次按缓存/检测安装，第二次强制重检（覆盖缓存误判）。 */
        const val MAX_RG_RECOVERY_ATTEMPTS = 2
    }

    override val name = "search"
    override val description = "使用 ripgrep（rg）搜索文本内容。例：args=\"-n \\\"fun main\\\" ~/workspace/app\"。支持追加 `| head [-n N]` 截断输出。rg 缺失时会自动安装，仍不可用则回退 grep（输出格式一致）。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "args" to ToolParameter(
            name = "args",
            type = ParameterType.STRING,
            description = "ripgrep 参数。不填无效。常用：-i -F -e -g --hidden --。支持末尾追加 `| head [-n N]` 截断输出；其它管道命令（grep/sort/wc 等）不支持。rg 缺失回退 grep 时仅支持 -i/-F/-w/-e/-g/-A/-B/-C 参数。",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val rawArgs = args["args"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (rawArgs.isEmpty()) return ToolResult.Error("缺少必需参数：args", "MISSING_ARGS")

            val tokens = parseShellWords(rawArgs)
                ?: return ToolResult.Error("args 中存在未闭合的引号", "INVALID_ARGS")
            if (tokens.isEmpty()) return ToolResult.Error("缺少必需参数：args", "MISSING_ARGS")

            val projectPath = workspaceRepository.currentPath()
            val env = pickSearchEnv(tokens, projectPath)
            val useContainer = env == DelegatingCommandEngine.TargetEnv.CONTAINER
            // 走容器但当前模式不是容器时：把真机工作区路径翻译为容器内挂载点路径，
            // 并改用容器 home 展开 `~`（真机 filesDir 在容器里不存在）。
            val effectiveTokens = if (useContainer && modeHolder.currentMode() != ExecutionMode.LOCAL_PROOT) {
                val workspaceRoot = canonicalOrNull(projectPath)
                if (workspaceRoot != null) {
                    translateTokensForContainer(tokens, workspaceRoot, pathHomeResolver.home(), CONTAINER_WORKSPACE)
                } else {
                    tokens
                }
            } else {
                tokens
            }
            val home = if (useContainer) "/root" else pathHomeResolver.home()
            // 真机通道没有包管理器、rg 永远装不上：优先改用随包内置的静态 rg，
            // 有它就完全等价原生 rg（--glob / -uu / 忽略规则等全部行为一致）。
            val rgBin = if (env == DelegatingCommandEngine.TargetEnv.DEVICE) bundledRipgrep.binaryPath() else null
            if (rgBin != null) FileLogger.i(TAG, "使用内置 rg: $rgBin")

            val command = buildSearchCommand(effectiveTokens, home, rgBin = rgBin)
                ?: return ToolResult.Error("search 仅支持 | head [-n N] 截断输出，不支持其它管道命令", "INVALID_PIPE")

            val startedAt = System.currentTimeMillis()
            var result = runSearch(command, env, projectPath)
                ?: return ToolResult.Error("执行环境未就绪，无法执行搜索", "ENVIRONMENT_NOT_READY")

            // 127 = shell 找不到命令；合并 stderr 后通常也能看到 command not found，双保险。
            // rg 缺失时先自动安装（幂等）重试，仍不行回退 grep，保证搜索可用。
            var rgMissing = isRgMissing(result.output) || result.exitCode == 127
            if (rgMissing) {
                var reason = "rg 缺失"
                var attempt = 0
                while (rgMissing && attempt < MAX_RG_RECOVERY_ATTEMPTS) {
                    when (val status = ripgrepProvisioner.ensureAvailable(force = attempt > 0, target = env)) {
                        is RipgrepStatus.NotReady ->
                            return ToolResult.Error("执行环境未就绪，无法执行 rg", "ENVIRONMENT_NOT_READY")
                        is RipgrepStatus.Unavailable -> {
                            reason = status.reason
                            break
                        }
                        is RipgrepStatus.Available -> {
                            val retry = runSearch(command, env, projectPath)
                                ?: return ToolResult.Error("执行环境未就绪，无法执行搜索", "ENVIRONMENT_NOT_READY")
                            result = retry
                            rgMissing = isRgMissing(result.output) || result.exitCode == 127
                        }
                    }
                    attempt++
                }
                if (rgMissing) return searchWithGrepFallback(effectiveTokens, env, home, startedAt, reason)
            }

            if (result.exitCode != null && result.exitCode > 1) {
                return ToolResult.Error(result.output.ifBlank { "rg 执行失败" }, "RG_ERROR")
            }

            val lines = result.output.lineSequence().filter { it.isNotBlank() }.toList()
            ToolResult.Success(JsonObject(mapOf(
                "content" to JsonPrimitive(result.output),
                "matches" to JsonPrimitive(lines.size),
                "truncated" to JsonPrimitive(false),
                "elapsed_ms" to JsonPrimitive(System.currentTimeMillis() - startedAt),
                "backend" to JsonPrimitive("rg")
            )))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "search 异常", e)
            ToolResult.Error(e.message ?: "搜索失败", "SEARCH_ERROR")
        }
    }

    /**
     * 决定 search 的执行环境。
     *
     * 默认跟随全局模式；唯一例外：真机 ROOT 模式下搜索目标整体位于当前工作区内、
     * 且本地容器已安装就绪时走容器（理由见类文档）。工作区外路径（`/system`、`/data`
     * 等）或容器未就绪时保持真机通道——容器 bind 不到真机专属路径。
     */
    private fun pickSearchEnv(
        tokens: List<String>,
        projectPath: String
    ): DelegatingCommandEngine.TargetEnv {
        val mode = modeHolder.currentMode()
        if (mode != ExecutionMode.DEVICE_ROOT) {
            return when (mode) {
                ExecutionMode.REMOTE_SSH -> DelegatingCommandEngine.TargetEnv.REMOTE
                else -> DelegatingCommandEngine.TargetEnv.CONTAINER
            }
        }
        val workspaceRoot = canonicalOrNull(projectPath)
        val insideWorkspace = workspaceRoot != null &&
            isSearchTargetInsideWorkspace(tokens, workspaceRoot, pathHomeResolver.home())
        if (!insideWorkspace) {
            FileLogger.i(TAG, "真机模式搜索目标含工作区外路径，走真机通道")
            return DelegatingCommandEngine.TargetEnv.DEVICE
        }
        val containerReady = delegatingEngine.engineFor(DelegatingCommandEngine.TargetEnv.CONTAINER)
            .isContainerInstalled()
        if (!containerReady) {
            FileLogger.i(TAG, "真机模式容器未安装，搜索回退真机通道（grep 兜底）")
            return DelegatingCommandEngine.TargetEnv.DEVICE
        }
        FileLogger.i(TAG, "真机模式工作区内搜索，改走容器通道（容器内有真 rg）")
        return DelegatingCommandEngine.TargetEnv.CONTAINER
    }

    private fun canonicalOrNull(path: String): String? =
        runCatching { File(path).canonicalPath }.getOrNull()

    /** 在指定执行环境中执行搜索命令（环境未就绪时返回 null）。 */
    private suspend fun runSearch(
        command: String,
        env: DelegatingCommandEngine.TargetEnv,
        projectPath: String
    ): CommandResult? =
        delegatingEngine.engineFor(env).runCommandSyncIfReady(
            command = command,
            projectPath = projectPath,
            timeoutMs = SEARCH_TIMEOUT_MS
        )

    /**
     * rg 不可用且自动安装失败时的 grep 兜底：输出格式（文件:行号:内容）与 rg 一致。
     * 参数无法映射到 grep 时，给出可操作提示（装 rg 或简化参数）。
     */
    private suspend fun searchWithGrepFallback(
        tokens: List<String>,
        env: DelegatingCommandEngine.TargetEnv,
        home: String,
        startedAt: Long,
        reason: String
    ): ToolResult {
        val fallback = buildFallbackSearchCommand(tokens, home)
            ?: return ToolResult.Error(
                "rg 不可用（自动安装失败：$reason），且当前参数无法用 grep 兜底。" +
                    "可进入终端执行 aicode 安装基础工具（本地容器内为 apk add ripgrep；远程服务器请用其包管理器安装 ripgrep）后重试；" +
                    "或将参数简化为 -i/-F/-w/-e/-g/-A/-B/-C 后重试。",
                "RG_UNAVAILABLE"
            )
        val fallbackCommand = fallback.command
        val dropped = fallback.droppedFlags
        FileLogger.w(TAG, "rg 不可用，search 回退 grep 兜底: reason=$reason env=$env dropped=$dropped")
        var result = runSearch(fallbackCommand, env, workspaceRepository.currentPath())
            ?: return ToolResult.Error("执行环境未就绪，无法执行搜索", "ENVIRONMENT_NOT_READY")
        // 退出码 2 = 用法错误（个别精简 grep 不认 --exclude-dir 等旗标；toybox 老版本常见）。
        // 判定不能只看输出为空——用法错误信息走 stderr，已合并进 output（见
        // DeviceRootEngine.redirectErrorStream(true)），会非空。故同时要求输出中
        // 没有任何 `文件:行号:内容` 命中行才算纯错误，重试最小命令（仅 -rnI -E）。
        if (result.exitCode == 2 && looksLikePureErrorOutput(result.output)) {
            val minimalCommand = buildFallbackSearchCommand(tokens, home, minimal = true)?.command
            if (minimalCommand != null) {
                FileLogger.w(TAG, "grep 兜底用法错误，重试最小命令: $minimalCommand")
                result = runSearch(minimalCommand, env, workspaceRepository.currentPath())
                    ?: return ToolResult.Error("执行环境未就绪，无法执行搜索", "ENVIRONMENT_NOT_READY")
            }
        }
        if (result.exitCode != null && result.exitCode > 1) {
            // 退出码 2 也可能是「部分文件读不了」（真机 /sdcard 权限拒绝常见）——
            // 此时 stdout 里已有命中，整体丢弃对外表现就是"搜索结果不可靠"。
            // 有命中一律按成功返回并标注可能不完整；纯错误输出（用法错误等）才报错。
            if (result.output.isNotBlank() && !looksLikePureErrorOutput(result.output)) {
                FileLogger.w(TAG, "grep 兜底退出码 ${result.exitCode} 但有命中输出，按部分结果返回")
                return ToolResult.Success(JsonObject(mapOf(
                    "content" to JsonPrimitive(result.output),
                    "matches" to JsonPrimitive(result.output.lineSequence().count { it.isNotBlank() }),
                    "truncated" to JsonPrimitive(false),
                    "elapsed_ms" to JsonPrimitive(System.currentTimeMillis() - startedAt),
                    "backend" to JsonPrimitive("grep"),
                    "degraded" to JsonPrimitive(true),
                    "dropped_flags" to JsonPrimitive(dropped.joinToString(" ")),
                    "warning" to JsonPrimitive(
                        buildString {
                            append("部分文件无法读取（权限或不存在），结果可能不完整")
                            if (dropped.isNotEmpty()) append("；已忽略 rg 专有参数：").append(dropped.joinToString(" "))
                        }
                    )
                )))
            }
            return ToolResult.Error(result.output.ifBlank { "grep 兜底搜索失败" }, "SEARCH_ERROR")
        }
        val lines = result.output.lineSequence().filter { it.isNotBlank() }.toList()
        return ToolResult.Success(JsonObject(mapOf(
            "content" to JsonPrimitive(result.output),
            "matches" to JsonPrimitive(lines.size),
            "truncated" to JsonPrimitive(false),
            "elapsed_ms" to JsonPrimitive(System.currentTimeMillis() - startedAt),
            "backend" to JsonPrimitive("grep"),
            "degraded" to JsonPrimitive(true),
            "dropped_flags" to JsonPrimitive(dropped.joinToString(" ")),
            "warning" to JsonPrimitive(
                if (dropped.isEmpty()) "rg 不可用，已用 grep 兜底：不读忽略规则，正则按 ERE 解释"
                else "rg 不可用，已用 grep 兜底：不读忽略规则，正则按 ERE 解释；已忽略 rg 专有参数 ${dropped.joinToString(" ")}"
            )
        )))
    }

    /**
     * 输出是否为"纯错误"（无任何搜索命中）：grep 命中行形态为 `文件:行号:内容`。
     * 用法错误、权限拒绝等提示信息不含该形态，据此与"有命中的部分结果"区分。
     */
    private fun looksLikePureErrorOutput(output: String): Boolean =
        output.lineSequence().none { line ->
            HIT_LINE_REGEX.containsMatchIn(line.trim())
        }

    private fun isRgMissing(output: String): Boolean {
        // toybox（Android 真机 device 环境）找不到命令时的报错是
        // 「rg: inaccessible or not found」——既不含 "command not found"
        // 也不含 "rg: not found"，只靠这两句会漏检（仓库 GitCommandEngine
        // 的注释早已记录该格式）。加一条精确匹配，避免误伤内容里的 "not found"。
        return output.contains("inaccessible or not found", ignoreCase = true) ||
            output.contains("command not found", ignoreCase = true) ||
            output.contains("rg: not found", ignoreCase = true)
    }
}

/** grep/rg 命中行：`文件:行号:内容`（文件路径无冒号形态）。 */
private val HIT_LINE_REGEX = Regex("^[^:\\n]+:\\d+:")
