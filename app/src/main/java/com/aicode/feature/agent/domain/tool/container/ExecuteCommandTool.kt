package com.aicode.feature.agent.domain.tool.container

import com.aicode.feature.agent.domain.container.BoundedOutput
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.agent.domain.container.DelegatingCommandEngine
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.StreamingAgentTool
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolStreamEvent
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * Tool that allows the AI agent to execute commands inside the Linux container.
 *
 * 命令在当前选中工作区目录下执行，使 AI 的 shell 操作（npm install、git 等）
 * 与文件工具作用于同一目录。
 *
 * 同时实现 [StreamingAgentTool]：优先逐行流式输出，让聊天里能实时看到命令执行过程；
 * [execute] 作为非流式兜底保留，最终聚合结果两者一致（喂回模型不变）。
 */
class ExecuteCommandTool @Inject constructor(
    private val delegatingEngine: DelegatingCommandEngine,
    private val workspaceRepository: WorkspaceRepository
) : AgentTool(), StreamingAgentTool {
    private companion object {
        const val TAG = "ExecuteCommandTool"

        /** 默认超时（秒），与 [LinuxContainerEngine.DEFAULT_TIMEOUT_MS] 对齐。 */
        const val DEFAULT_TIMEOUT_SECONDS = 120L

        /** 超时上限（秒），与 [LinuxContainerEngine.MAX_TIMEOUT_MS] 对齐。 */
        const val MAX_TIMEOUT_SECONDS = 1_800L
    }

    override val name = "Bash"
    override val description = "执行一次性 Shell 命令，同步返回输出。默认在用户设置的执行环境（当前为首选项环境）中执行；可用 env 参数显式选择执行环境：操作真机系统（/storage 文件、dumpsys/pm/am 等设备命令）用 \"device\"，需要容器内完整 Linux 工具链（git/npm/python/gcc/make 等构建环境）用 \"container\"，默认 \"auto\" 跟随用户首选项。耗时或常驻任务（安装依赖、启动服务等）请改用 `terminal`，不要用 `&` 挂后台。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "要执行的 shell 命令",
            required = true
        ),
        "intent" to ToolParameter(
            name = "intent",
            type = ParameterType.STRING,
            description = "必填：用一句话说明本条命令如何服务于用户当前任务；必须与实际命令效果一致。该声明会和命令本身一起接受安全审查，不能代替实际命令判断。",
            required = true
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "命令最长执行时间（秒），超时将被强制终止。默认 $DEFAULT_TIMEOUT_SECONDS 秒，上限 $MAX_TIMEOUT_SECONDS 秒。耗时命令（如安装依赖）可适当调大。",
            required = false
        ),
        "env" to ToolParameter(
            name = "env",
            type = ParameterType.STRING,
            description = "执行环境选择。\"auto\"（默认）跟随用户设置的首选执行环境；\"device\" 在真机上直接执行（适合操作 /storage 文件、系统命令 dumpsys/pm/am、设备状态查询等宿主操作）；\"container\" 在本地 Linux 容器内执行（适合需要容器内已安装的完整工具链：git/npm/python/gcc/make 等构建与开发环境）。分清场合：日常跟随 auto，明确需要另一个环境时才显式指定。",
            required = false
        )
    )

    /** 解析 env 参数为执行环境；缺省或非法值回退 AUTO（跟随用户首选项）。 */
    private fun resolveEnv(args: Map<String, JsonElement>): DelegatingCommandEngine.TargetEnv {
        val raw = args["env"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() ?: return DelegatingCommandEngine.TargetEnv.AUTO
        return when (raw) {
            "container", "linux", "proot" -> DelegatingCommandEngine.TargetEnv.CONTAINER
            "device", "host", "root" -> DelegatingCommandEngine.TargetEnv.DEVICE
            "remote", "ssh" -> DelegatingCommandEngine.TargetEnv.REMOTE
            else -> DelegatingCommandEngine.TargetEnv.AUTO
        }
    }

    /** 解析 timeout（秒）参数并钳到合法范围，返回毫秒；缺省用默认值。 */
    private fun resolveTimeoutMs(args: Map<String, JsonElement>): Long {
        val seconds = args["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_SECONDS
        return seconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: "未知命令"
        val timeoutSeconds = resolveTimeoutMs(args) / 1000L
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认执行命令",
            summary = command,
            details = "将在当前执行环境中执行。\n超时：${timeoutSeconds} 秒",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数：command")

        return try {
            val env = resolveEnv(args)
            // 所有环境都传宿主绝对路径作为工作区：容器引擎内部用 -b 把它 bind 成容器内 /root/workspace。
            // 不能在此先 toContainerPath()——它返回字面量 ~/workspace，proot 在宿主上解析不到该路径，
            // 导致绑定失败、容器内 /root/workspace 变成空占位（常驻终端走宿主绝对路径故正常）。
            val workdir = workspaceRepository.currentPath()
            val engine = delegatingEngine.engineFor(env)
            // 显式选择环境时先做就绪检查：容器未装/未初始化时给 AI 明确原因，而不是让命令莫名失败。
            if (env != DelegatingCommandEngine.TargetEnv.AUTO) {
                engine.notReadyHint()?.let { hint ->
                    return ToolResult.Error("执行环境未就绪（${env.name}）：$hint")
                }
            }
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "execute_command (env=${env.name}, timeout=${timeoutMs}ms): $command")
            val output = engine.runCommandSync(command, workdir, timeoutMs)
            FileLogger.v(TAG, "execute_command 完成，输出 ${output.length} 字符")
            ToolResult.Success(JsonPrimitive(output))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "execute_command 失败: $command", e)
            ToolResult.Error("执行命令失败: ${e.message}")
        }
    }

    /**
     * 流式执行：逐行 emit [ToolStreamEvent.Progress]，命令结束 emit [ToolStreamEvent.Completed]，
     * 其最终结果与 [execute] 等价（同样经 [BoundedOutput] 限幅：超大输出仅保留开头+结尾），
     * 保证喂回模型的内容一致且不会撑爆上下文。
     */
    override fun executeStream(
        args: Map<String, JsonElement>,
        context: com.aicode.feature.agent.domain.model.AgentContext
    ): Flow<ToolStreamEvent> = flow {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
        if (command == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("缺少必需参数：command")))
            return@flow
        }

        // 限幅累积：喂回模型的最终结果只保留开头+结尾，避免超大输出撑爆上下文。
        val accumulated = BoundedOutput()
        try {
            val env = resolveEnv(args)
            // 同 execute：所有环境传宿主绝对路径，容器引擎内部 -b 映射为 /root/workspace。
            val workdir = workspaceRepository.currentPath()
            val engine = delegatingEngine.engineFor(env)
            if (env != DelegatingCommandEngine.TargetEnv.AUTO) {
                engine.notReadyHint()?.let { hint ->
                    emit(ToolStreamEvent.Completed(ToolResult.Error("执行环境未就绪（${env.name}）：$hint")))
                    return@flow
                }
            }
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "execute_command(流式, env=${env.name}, timeout=${timeoutMs}ms): $command")
            engine.runCommandStream(command, workdir, timeoutMs).collect { event ->
                when (event) {
                    is CommandEvent.Line -> {
                        accumulated.append(event.text)
                        accumulated.append("\n")
                        emit(ToolStreamEvent.Progress(event.text))
                    }
                    is CommandEvent.Exit -> { /* 结束在流完成后统一聚合 */ }
                }
            }
            FileLogger.v(TAG, "execute_command(流式) 完成，输出 ${accumulated.totalChars} 字符")
            emit(ToolStreamEvent.Completed(ToolResult.Success(JsonPrimitive(accumulated.build()))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 兜底：底层 flow 异常终止时，已逐行 emit 给用户的 Progress 仍应作为最终结果保留，
            // 而不是被这里抛出的空 Error 覆盖掉（否则模型只看到“执行失败”，之前展示的输出全丢）。
            FileLogger.e(TAG, "execute_command(流式) 异常(已保留此前输出 ${accumulated.totalChars} 字符): $command", e)
            val saved = accumulated.build()
            val result = if (saved.isNotEmpty()) {
                ToolResult.Success(JsonPrimitive(saved))
            } else {
                ToolResult.Error("执行命令失败：${e.message}")
            }
            emit(ToolStreamEvent.Completed(result))
        }
    }
}
