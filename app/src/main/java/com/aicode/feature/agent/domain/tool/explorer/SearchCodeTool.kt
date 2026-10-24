package com.aicode.feature.agent.domain.tool.explorer

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandResult
import com.aicode.feature.agent.domain.container.RipgrepProvisioner
import com.aicode.feature.agent.domain.container.RipgrepStatus
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.PathHomeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * ripgrep 搜索工具：参数原样传给容器内的 ripgrep；支持 `| head [-n N]` 截断输出，
 * 其余管道命令一律拒绝（见 [buildSearchCommand]），容器未就绪则报错。
 *
 * rg 缺失时不再是终态：先经 [RipgrepProvisioner] 自动安装一次（幂等）并重试，仍不可用则
 * 回退 grep 搜索（[buildFallbackSearchCommand]，输出格式 `文件:行号:内容` 与 rg 一致），
 * 保证搜索始终可用；只有安装与兜底都失败才报错并给出可操作说明。
 */
class SearchCodeTool @Inject constructor(
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val pathHomeResolver: PathHomeResolver,
    private val ripgrepProvisioner: RipgrepProvisioner
) : AgentTool() {

    private companion object {
        const val TAG = "SearchTool"
        const val SEARCH_TIMEOUT_MS = 30_000L

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

            val command = buildSearchCommand(tokens, pathHomeResolver.home())
                ?: return ToolResult.Error("search 仅支持 | head [-n N] 截断输出，不支持其它管道命令", "INVALID_PIPE")

            val startedAt = System.currentTimeMillis()
            var result = runSearch(command)
                ?: return ToolResult.Error("容器未就绪，无法执行 rg", "CONTAINER_NOT_READY")

            // 127 = shell 找不到命令；合并 stderr 后通常也能看到 command not found，双保险。
            // rg 缺失时先自动安装（幂等）重试，仍不行回退 grep，保证搜索可用。
            var rgMissing = isRgMissing(result.output) || result.exitCode == 127
            if (rgMissing) {
                var reason = "rg 缺失"
                var attempt = 0
                while (rgMissing && attempt < MAX_RG_RECOVERY_ATTEMPTS) {
                    when (val status = ripgrepProvisioner.ensureAvailable(force = attempt > 0)) {
                        is RipgrepStatus.NotReady ->
                            return ToolResult.Error("容器未就绪，无法执行 rg", "CONTAINER_NOT_READY")
                        is RipgrepStatus.Unavailable -> {
                            reason = status.reason
                            break
                        }
                        is RipgrepStatus.Available -> {
                            val retry = runSearch(command)
                                ?: return ToolResult.Error("容器未就绪，无法执行 rg", "CONTAINER_NOT_READY")
                            result = retry
                            rgMissing = isRgMissing(result.output) || result.exitCode == 127
                        }
                    }
                    attempt++
                }
                if (rgMissing) return searchWithGrepFallback(tokens, startedAt, reason)
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

    /** 在当前工作区执行搜索命令（执行环境未就绪时返回 null）。 */
    private suspend fun runSearch(command: String): CommandResult? =
        commandEngine.runCommandSyncIfReady(
            command = command,
            projectPath = workspaceRepository.currentPath(),
            timeoutMs = SEARCH_TIMEOUT_MS
        )

    /**
     * rg 不可用且自动安装失败时的 grep 兜底：输出格式（文件:行号:内容）与 rg 一致。
     * 参数无法映射到 grep 时，给出可操作提示（装 rg 或简化参数）。
     */
    private suspend fun searchWithGrepFallback(
        tokens: List<String>,
        startedAt: Long,
        reason: String
    ): ToolResult {
        val fallbackCommand = buildFallbackSearchCommand(tokens, pathHomeResolver.home())
            ?: return ToolResult.Error(
                "rg 不可用（自动安装失败：$reason），且当前参数无法用 grep 兜底。" +
                    "可进入终端执行 aicode 安装基础工具（本地容器内为 apk add ripgrep；远程服务器请用其包管理器安装 ripgrep）后重试；" +
                    "或将参数简化为 -i/-F/-w/-e/-g/-A/-B/-C 后重试。",
                "RG_UNAVAILABLE"
            )
        FileLogger.w(TAG, "rg 不可用，search 回退 grep 兜底: reason=$reason")
        var result = runSearch(fallbackCommand)
            ?: return ToolResult.Error("容器未就绪，无法执行搜索", "CONTAINER_NOT_READY")
        // 退出码 2 且无输出 = 用法错误（个别精简 grep 不认 --exclude-dir 等旗标）：
        // 用最小命令（仅 -rnI -E）重试一次，避免整条兜底链失效。
        if (result.exitCode == 2 && result.output.isBlank()) {
            val minimalCommand = buildFallbackSearchCommand(tokens, pathHomeResolver.home(), minimal = true)
            if (minimalCommand != null) {
                FileLogger.w(TAG, "grep 兜底用法错误，重试最小命令: $minimalCommand")
                result = runSearch(minimalCommand)
                    ?: return ToolResult.Error("容器未就绪，无法执行搜索", "CONTAINER_NOT_READY")
            }
        }
        if (result.exitCode != null && result.exitCode > 1) {
            // 退出码 2 也可能是「部分文件读不了」（真机 /sdcard 权限拒绝常见）——
            // 此时 stdout 里已有命中，整体丢弃对外表现就是"搜索结果不可靠"。
            // 有输出一律按成功返回并标注可能不完整；完全无输出才报错。
            if (result.output.isNotBlank()) {
                FileLogger.w(TAG, "grep 兜底退出码 ${result.exitCode} 但有 ${result.output.length} 字符输出，按部分结果返回")
                return ToolResult.Success(JsonObject(mapOf(
                    "content" to JsonPrimitive(result.output),
                    "matches" to JsonPrimitive(result.output.lineSequence().count { it.isNotBlank() }),
                    "truncated" to JsonPrimitive(false),
                    "elapsed_ms" to JsonPrimitive(System.currentTimeMillis() - startedAt),
                    "backend" to JsonPrimitive("grep"),
                    "warning" to JsonPrimitive("部分文件无法读取（权限或不存在），结果可能不完整")
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
            "backend" to JsonPrimitive("grep")
        )))
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
