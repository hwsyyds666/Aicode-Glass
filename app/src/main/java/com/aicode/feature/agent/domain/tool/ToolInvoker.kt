package com.aicode.feature.agent.domain.tool

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.subagent.SubAgentWriteScopeGuard
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.domain.workflow.AgentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/** 单个工具一次执行的结果：transport 文本（喂模型）+ 错误标记 + 展示附件/图片。 */
data class ToolRunResult(
    val raw: String,
    val isError: Boolean,
    /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
    val attachments: List<AgentAttachment> = emptyList(),
    val images: List<AgentImage> = emptyList()
)

/**
 * 工具执行组件：workflow 的 [com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow]
 * 把批内每个工具的执行委托到这里，由本组件统一负责线程调度与结果后处理。
 *
 * ## 为什么必须统一切 IO（血泪修复）
 * workflow 的收集方（AIAgentViewModel.executeAgentRequestStream）是 `viewModelScope.launch`
 * 无调度器参数，即 `Dispatchers.Main.immediate`。批次执行处 `coroutineScope { async { … } }`
 * 继承该单线程调度器：若不在此统一切 IO，
 * 1. `async` 只是挂在单线程上的挂起点，**并行批次实际是串行**；
 * 2. 工具的阻塞 IO 直接占用主线程——Bash 的 `runCommandSync`（DEVICE_ROOT 下是
 *    `su -c` fork + `Process.waitFor()`）、list 的逐项 `fileAccess.exists/isDirectory`
 *    （ROOT 模式每项一次 fork）、search 的 `runCommandSyncIfReady`；
 * 3. 表现为「AI 并行调用几个工具就整体卡住、多点几下屏幕直接 ANR 被杀（闪退）」。
 *
 * 统一切 IO 后：IO 线程池（默认 64 线程）上真正并行、主线程只负责事件分发。
 * 需要主线程协作的工具（浏览器 WebView、后台终端 sessionManager）自行
 * `withContext(Dispatchers.Main)` 切回，不受本组件影响；工具内部已自切 IO 的
 * （readFile 等）只是一次 no-op 切换。
 *
 * 另外本组件集中承载结果后处理（输出限幅裁剪、附件提取/剥离、异常包装），
 * 使其脱离 workflow 可独立单测。
 */
class ToolInvoker @Inject constructor(
    private val toolOutputStore: ToolOutputStore
) {
    /** 执行非流式工具。返回可直接进模型上下文的 transport 文本。 */
    suspend fun runSync(
        tool: AgentTool?,
        toolCall: ToolCall,
        context: AgentContext
    ): ToolRunResult {
        val name = toolCall.name
        if (tool == null) {
            return ToolRunResult(ToolResult.Error("工具 $name 不存在", "TOOL_NOT_FOUND").toTransportString(), true)
        }
        context.agentDefinition?.takeUnless { it.allowsTool(name) }?.let { definition ->
            return ToolRunResult(ToolResult.Error("${definition.name} 子代理类型不允许调用工具 $name", "SUBAGENT_TOOL_DENIED").toTransportString(), true)
        }
        if (context.allowedWritePaths != null || context.allowedReadPaths != null) {
            val boundary = SubAgentWriteScopeGuard.check(name, toolCall.arguments, tool.effectiveCapabilities(toolCall.arguments), context)
            if (!boundary.allowed) {
                return ToolRunResult(ToolResult.Error(boundary.reason ?: "子代理范围检查拒绝", "SUBAGENT_SCOPE_DENIED").toTransportString(), true)
            }
        }
        return try {
            withContext(Dispatchers.IO) {
                val result = tool.executeWithContext(toolCall.arguments, context)
                val attachments = if (name == "sendFile" || name == "generateImage") extractAttachments(result) else emptyList()
                val images = if (result is ToolResult.Success) result.images else emptyList()
                val transportResult = if (attachments.isNotEmpty()) stripAttachments(result) else result
                val processed = toolOutputStore.process(name, toolCall.id, transportResult)
                ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, attachments, images)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    /**
     * 执行流式工具：进度事件经 [onEvent] 回传（累积尾部限幅 [LIVE_TAIL_CHARS] 字符、
     * [PROGRESS_INTERVAL_MS] 毫秒节流），结束返回最终结果。
     * [onEvent] 即 workflow 的 `send`——channelFlow 的 send 可从任意线程调用，IO 线程回传安全。
     */
    suspend fun runStream(
        tool: AgentTool,
        toolCall: ToolCall,
        context: AgentContext,
        onEvent: suspend (AgentEvent) -> Unit
    ): ToolRunResult {
        val streamingTool = tool as? StreamingAgentTool
            ?: return ToolRunResult(ToolResult.Error("工具不支持流式执行", "STREAM_NOT_SUPPORTED").toTransportString(), true)
        context.agentDefinition?.takeUnless { it.allowsTool(tool.name) }?.let { definition ->
            return ToolRunResult(ToolResult.Error("${definition.name} 子代理类型不允许调用工具 ${tool.name}", "SUBAGENT_TOOL_DENIED").toTransportString(), true)
        }
        if (context.allowedWritePaths != null || context.allowedReadPaths != null) {
            val boundary = SubAgentWriteScopeGuard.check(
                tool.name,
                toolCall.arguments,
                tool.effectiveCapabilities(toolCall.arguments),
                context
            )
            if (!boundary.allowed) {
                return ToolRunResult(ToolResult.Error(boundary.reason ?: "子代理范围检查拒绝", "SUBAGENT_SCOPE_DENIED").toTransportString(), true)
            }
        }
        val live = StringBuilder()
        var lastEmitMs = 0L
        var finalResult: ToolResult? = null
        return try {
            withContext(Dispatchers.IO) {
                streamingTool.executeStream(toolCall.arguments, context).collect { ev ->
                    when (ev) {
                        is ToolStreamEvent.Progress -> {
                            live.append(ev.chunk).append('\n')
                            if (live.length > LIVE_TAIL_CHARS) {
                                live.delete(0, live.length - LIVE_TAIL_CHARS)
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= PROGRESS_INTERVAL_MS) {
                                lastEmitMs = now
                                onEvent(AgentEvent.ToolCallProgress(toolCall.id, toolCall.name, live.toString()))
                            }
                        }
                        is ToolStreamEvent.Completed -> finalResult = ev.result
                    }
                }
                val result = finalResult ?: ToolResult.Error("流式工具未返回结果", "MISSING_STREAM_RESULT")
                val processed = toolOutputStore.process(toolCall.name, toolCall.id, result)
                val images = if (result is ToolResult.Success) result.images else emptyList()
                ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, images = images)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(ToolResult.Error("工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED").toTransportString(), true)
        }
    }

    /**
     * 从 sendFile 工具结果的 `files` 数组提取文件卡片元数据（含宿主本地路径，供 UI 打开文件用）。
     * 任一文件缺关键字段则整体返回空（与 sendFile 的原子语义一致）。
     */
    private fun extractAttachments(result: ToolResult): List<AgentAttachment> {
        val data = (result as? ToolResult.Success)?.data as? JsonObject ?: return emptyList()
        val files = data["files"] as? JsonArray ?: return emptyList()
        val attachments = files.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val localPath = obj["local_path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: path.substringAfterLast('/')
            val mimeType = obj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
            AgentAttachment(
                fileName = name,
                containerPath = path,
                localPath = localPath,
                mimeType = mimeType,
                sizeBytes = obj["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                isImage = obj["is_image"]?.jsonPrimitive?.booleanOrNull ?: mimeType.startsWith("image/")
            )
        }
        return if (attachments.size == files.size) attachments else emptyList()
    }

    /** 从回传给模型的 sendFile 结果中剥离宿主本地路径（模型只应看到容器路径）。 */
    private fun stripAttachments(result: ToolResult): ToolResult {
        val success = result as? ToolResult.Success ?: return result
        val data = success.data as? JsonObject ?: return result
        val strippedFiles = (data["files"] as? JsonArray)?.map { elem ->
            val obj = elem as? JsonObject ?: return@map elem
            JsonObject(obj.toMutableMap().apply { remove("local_path") })
        } ?: return result
        val strippedData = data.toMutableMap().apply {
            this["files"] = JsonArray(strippedFiles)
            this["files_attached"] = JsonPrimitive(true)
        }
        return ToolResult.Success(JsonObject(strippedData))
    }

    private companion object {
        /** 流式工具回传 UI 的累积输出保留字符数（防止超长输出拖慢重组）。 */
        const val LIVE_TAIL_CHARS = 4_000
        /** 流式工具进度事件最小间隔（毫秒）。 */
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
