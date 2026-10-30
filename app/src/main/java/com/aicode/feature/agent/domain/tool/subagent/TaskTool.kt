package com.aicode.feature.agent.domain.tool.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.subagent.BuiltInSubAgentCatalog
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.subagent.SubAgentReworkPolicy
import com.aicode.feature.agent.domain.subagent.SubAgentWriteScope
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.presentation.MessageRole
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.settings.data.repository.SubAgentModelSettingsRepository
import com.aicode.feature.settings.data.repository.SubAgentSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject

/**
 * 子代理工具 `task`：统一管理子代理的生命周期。
 *
 * 通过 `action` 参数区分操作类型：
 * - `create`（默认）：创建一个子代理会话并让 AI 替用户向其发消息，子代理自动开始回复。
 * - `send`：向指定子代理发送一条消息（可反复发送）。运行中的子代理会在下一批工具结果里搭车收到，
 *   已完成的子代理会被重新唤醒；消息按发送顺序送达，终态后的唤醒受两轮返工上限约束。
 * - `read`：读取指定子代理的最后输出（最后一条助手回复）。
 * - `stop`：停止指定子代理的执行（取消其 AI 任务）。
 * - `del`：删除指定子代理会话及其全部消息。
 * - `list`：列出当前会话的全部子代理及其状态。
 *
 * 同时运行与每会话派发总数上限可配置（默认 3 / 12，create 时检查，超限返回错误）。
 * 子代理不能嵌套创建子代理（其工具集中不含 `task`）。
 *
 * `create` 仅接受 explore/research/worker/reviewer 四种应用内置类型，类型决定工具范围与模型档位。
 */
class TaskTool @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao,
    private val eventBus: SubAgentEventBus,
    private val aiProviderRepository: AIProviderRepository,
    private val subAgentSettings: SubAgentSettingsRepository,
    private val subAgentModelSettings: SubAgentModelSettingsRepository
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "TaskTool"
        const val TASK_DESCRIPTION_MAX = 30
    }

    override val name = "task"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
        return when (action) {
            "read", "list", "send" -> emptySet()
            else -> setOf(ToolCapability.MODIFY_SESSION_STATE)
        }
    }

    override val description = "管理隔离上下文的子代理。当任务存在可并行的独立方向时主动拆分派发——研究/调查类按主题拆多个 research，多模块改动按目录拆多个 worker。简单单步或强顺序依赖的任务由主代理直接完成。可选类型：\n${BuiltInSubAgentCatalog.parentGuidance()}\n创建时必须提交自包含的完整交接：目标背景、可写/只读范围、共同约束、禁止事项、验收标准、返回格式。worker 必须声明非空且与其他运行任务不重叠的可写范围；代码修改必须使用范围校验的文件工具；构建/测试仅允许从可写范围根目录调用受限 Gradle 任务并明确选择 container 或 remote 环境。其他 shell 仅开放安全只读命令；其他类型只能只读或联网研究。派发后主代理必须审查结果和差异，必要时精准返工，最多两轮；大型或高风险改动可再派只读 reviewer 独立核对原始需求与差异。子代理不能嵌套。完成后等待后台通知，不要轮询。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型：create（默认，创建子代理并执行任务）/ send（向子代理发消息；运行中的可继续接收，终态后最多返工两轮）/ read（读取子代理的最后输出）/ stop（停止子代理的执行）/ del（删除子代理会话及其消息）/ list（列出当前会话的全部子代理）",
            required = false
        ),
        "id" to ToolParameter(
            name = "id",
            type = ParameterType.STRING,
            description = "子会话 id（read/send/stop/del 必填，取自 task 返回的 id）",
            required = false
        ),
        "description" to ToolParameter(
            name = "description",
            type = ParameterType.STRING,
            description = "子代理任务描述（create 用，作为子会话标题，如「修复登录 bug」）",
            required = false
        ),
        "prompt" to ToolParameter(
            name = "prompt",
            type = ParameterType.STRING,
            description = "给子代理的自包含目标与背景（create 必填），将作为它的第一条用户消息",
            required = false
        ),
        "type" to ToolParameter("type", ParameterType.STRING, "内置类型：explore（只读本地探索）、research（仅网页研究）、worker（指定范围内改代码）、reviewer（只读独立审查）。create 必填。", required = false, enum = BuiltInSubAgentCatalog.typeNames),
        "depth" to ToolParameter("depth", ParameterType.STRING, "explore 检查深度：fast / normal / thorough；仅 explore 使用。", required = false, enum = listOf("fast", "normal", "thorough")),
        "message" to ToolParameter(
            name = "message",
            type = ParameterType.STRING,
            description = "发给子代理的消息正文（send 必填）。运行中的子代理会尽快收到；终态子代理重新唤醒计入持久化返工预算，最多两轮",
            required = false
        ),
        "writable_scope" to ToolParameter("writable_scope", ParameterType.STRING, "可写路径（worker create 必填，工作区内路径，逗号或换行分隔）；只读类型填写 none。", required = false),
        "read_only_scope" to ToolParameter("read_only_scope", ParameterType.STRING, "只读路径（create 必填，工作区内路径，逗号或换行分隔）；research 填 none。", required = false),
        "shared_constraints" to ToolParameter("shared_constraints", ParameterType.STRING, "主代理已决定且子任务必须遵循的共同约束（create 必填）", required = false),
        "do_not" to ToolParameter("do_not", ParameterType.STRING, "子代理明确不得做的事项（create 必填）", required = false),
        "acceptance_criteria" to ToolParameter("acceptance_criteria", ParameterType.STRING, "验收标准（create 必填）", required = false),
        "return_format" to ToolParameter("return_format", ParameterType.STRING, "返回格式（create 必填，包含完成项、文件/测试、风险和未完成项）", required = false)
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
        return when (action) {
            "create" -> createSubagent(args, context)
            "send" -> sendToSubagent(args, context)
            "read" -> readSubagent(args, context)
            "stop" -> stopSubagent(args, context)
            "del" -> deleteSubagent(args, context)
            "list" -> listSubagents(context)
            else -> ToolResult.Error("未知的 action：$action，支持：create / send / read / stop / del / list", "INVALID_ARGS")
        }
    }

    /** 创建子代理并启动执行。 */
    private suspend fun createSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val parentSession = sessionUseCase.getSessionById(parentSessionId)
            ?: return ToolResult.Error("当前会话不存在", "SESSION_NOT_FOUND")

        if (parentSession.parentId != null) {
            return ToolResult.Error("子代理最多一层；当前调用者本身是子代理，禁止继续派生。", "NESTED_SUBAGENT_DENIED")
        }

        val rawPrompt = requiredText(args, "prompt") ?: return missingHandoffField("prompt")
        val typeName = requiredText(args, "type") ?: return missingHandoffField("type")
        val definition = BuiltInSubAgentCatalog.findCanonical(typeName)
            ?: return ToolResult.Error("未知子代理类型：$typeName。仅支持 ${BuiltInSubAgentCatalog.typeNames.joinToString(", ")}", "INVALID_SUBAGENT_TYPE")
        val writableRaw = requiredText(args, "writable_scope") ?: return missingHandoffField("writable_scope")
        val readRaw = requiredText(args, "read_only_scope") ?: return missingHandoffField("read_only_scope")
        val sharedConstraints = requiredText(args, "shared_constraints") ?: return missingHandoffField("shared_constraints")
        val doNot = requiredText(args, "do_not") ?: return missingHandoffField("do_not")
        val acceptance = requiredText(args, "acceptance_criteria") ?: return missingHandoffField("acceptance_criteria")
        val returnFormat = requiredText(args, "return_format") ?: return missingHandoffField("return_format")
        val projectRoot = parentSession.workspacePath.ifBlank { "/root/workspace" }
        val writeScopeResult = SubAgentWriteScope.parse(writableRaw, projectRoot)
        val writeScopes = writeScopeResult.paths
            ?: return ToolResult.Error(writeScopeResult.error ?: "可写范围无效", "INVALID_WRITE_SCOPE")
        val readScopeResult = SubAgentWriteScope.parse(readRaw, projectRoot)
        val readScopes = readScopeResult.paths
            ?: return ToolResult.Error(readScopeResult.error ?: "只读范围无效", "INVALID_READ_SCOPE")

        when (definition.name) {
            "explore" -> {
                if (writeScopes.isNotEmpty()) return ToolResult.Error("explore 是只读类型，writable_scope 必须是 none", "INVALID_TYPE_SCOPE")
                if (readScopes.isEmpty()) return ToolResult.Error("explore 必须声明至少一个只读路径", "INVALID_TYPE_SCOPE")
            }
            "research" -> {
                if (writeScopes.isNotEmpty() || readScopes.isNotEmpty()) return ToolResult.Error("research 仅访问网页；writable_scope 和 read_only_scope 都必须是 none", "INVALID_TYPE_SCOPE")
            }
            "worker" -> {
                if (writeScopes.isEmpty()) return ToolResult.Error("worker 必须声明至少一个非空可写路径", "INVALID_TYPE_SCOPE")
            }
            "reviewer" -> {
                if (writeScopes.isNotEmpty()) return ToolResult.Error("reviewer 是只读类型，writable_scope 必须是 none", "INVALID_TYPE_SCOPE")
                if (readScopes.isEmpty()) return ToolResult.Error("reviewer 必须声明至少一个只读路径", "INVALID_TYPE_SCOPE")
            }
        }

        if (!context.superAgentEnabled && !context.explicitSubAgentRequest) {
            return ToolResult.Error("超级 Agent 未开启；仅在用户明确要求派发时创建子代理。", "SUPER_AGENT_DISABLED")
        }

        val depth = (args["depth"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?.takeIf { it in setOf("fast", "normal", "thorough") } ?: "normal"
        val prompt = buildHandoffPrompt(rawPrompt, writableRaw, readRaw, sharedConstraints, doNot, acceptance, returnFormat, definition.name, depth)
        val description = (args["description"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?.replace(Regex("\\s+"), " ")
            ?.take(TASK_DESCRIPTION_MAX)
            ?: "子代理任务"

        val configuredChoice = subAgentModelSettings.getChoice(definition.modelTier)
        val selectedProvider = if (configuredChoice.followsParent) null else aiProviderRepository.getProviderById(configuredChoice.providerId)
        val selectedModelAvailable = selectedProvider?.let { provider ->
            provider.isEnabled && provider.hasUsableApiKey && configuredChoice.model in provider.models
        } == true
        val useTierChoice = configuredChoice.followsParent || selectedModelAvailable
        val modelWarning = if (!configuredChoice.followsParent && !selectedModelAvailable) {
            "${definition.modelTier.name.lowercase()} 档设置的供应商或模型不可用，已回退到父会话模型。"
        } else null

        // 创建子代理会话
        val subSession = sessionUseCase.newSubSessionEntity(
            title = description,
            parentId = parentSessionId,
            parent = parentSession,
            subagentType = definition.name,
            providerId = if (useTierChoice && selectedModelAvailable) selectedProvider?.id else null,
            model = if (useTierChoice && selectedModelAvailable) configuredChoice.model else null,
            reasoningEffort = if (useTierChoice && selectedModelAvailable) {
                ReasoningEffort.entries.firstOrNull { it.apiValue == configuredChoice.reasoningEffort }?.name
            } else null,
            allowedWritePaths = writeScopes,
            allowedReadPaths = readScopes,
            dispatchMessageId = context.inputMessageId
        )
        val maxRunning = subAgentSettings.maxRunning()
        val maxTotal = subAgentSettings.maxTotalPerParent()
        val reservation = eventBus.reserve(
            parentId = parentSessionId,
            childId = subSession.id,
            persistedCreatedCount = chatSessionDao.getSubagentCreatedCount(parentSessionId) ?: 0,
            maxRunning = maxRunning,
            maxTotal = maxTotal,
            writablePaths = writeScopes
        )
        if (reservation != SubAgentEventBus.Reservation.Reserved) {
            val reason = when (reservation) {
                SubAgentEventBus.Reservation.ConcurrentLimit -> "同时运行的子代理达到上限 $maxRunning"
                SubAgentEventBus.Reservation.TotalLimit -> "本会话派生子代理总数达到上限 $maxTotal"
                SubAgentEventBus.Reservation.AlreadyRunning -> "该子代理已处于运行状态"
                SubAgentEventBus.Reservation.OverlappingWriteScope -> "写入范围与运行中的子代理重叠；请拆分到互不相交的目录后再并行派发"
                SubAgentEventBus.Reservation.Reserved -> ""
            }
            val code = if (reservation == SubAgentEventBus.Reservation.OverlappingWriteScope) "OVERLAPPING_WRITE_SCOPE" else "MAX_SUBAGENTS_REACHED"
            return ToolResult.Error(reason, code)
        }
        var counterReserved = false
        try {
            val countReserved = withContext(NonCancellable) {
                val updated = chatSessionDao.reserveSubagentCreation(parentSessionId, maxTotal) == 1
                counterReserved = updated
                updated
            }
            if (!countReserved) {
                eventBus.rollbackReservation(subSession.id)
                return ToolResult.Error("本会话派生子代理总数达到上限 $maxTotal；请减少拆分数量。", "MAX_SUBAGENTS_REACHED")
            }
            sessionUseCase.upsertSession(subSession)
            val spawned = eventBus.emit(
                SubAgentEvent(
                    subSessionId = subSession.id,
                    parentSessionId = parentSessionId,
                    type = SubAgentEventType.SPAWNED,
                    detail = prompt
                )
            )
            if (!spawned) {
                eventBus.rollbackReservation(subSession.id)
                withContext(NonCancellable) {
                    chatSessionDao.rollbackSubagentCreation(parentSessionId)
                    runCatching { sessionUseCase.deleteSession(subSession.id) }
                }
                return ToolResult.Error("子代理任务队列暂时繁忙，创建已回滚，请稍后重试。", "SUBAGENT_QUEUE_FULL")
            }
        } catch (cancelled: CancellationException) {
            eventBus.rollbackReservation(subSession.id)
            withContext(NonCancellable) {
                if (counterReserved) chatSessionDao.rollbackSubagentCreation(parentSessionId)
                runCatching { sessionUseCase.deleteSession(subSession.id) }
            }
            throw cancelled
        } catch (error: Exception) {
            eventBus.rollbackReservation(subSession.id)
            withContext(NonCancellable) {
                if (counterReserved) chatSessionDao.rollbackSubagentCreation(parentSessionId)
                runCatching { sessionUseCase.deleteSession(subSession.id) }
            }
            return ToolResult.Error("创建子代理会话失败：${error.message ?: "数据库错误"}", "SUBAGENT_CREATE_FAILED")
        }
        val subSessionId = subSession.id
        FileLogger.i(TAG, "子代理已创建: session=$subSessionId parent=$parentSessionId type=${definition.name} tier=${definition.modelTier}")

        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "running")
                put("writableScope", writeScopes.joinToString(",").ifBlank { "none" })
                put("type", definition.name)
                put("modelTier", definition.modelTier.name.lowercase())
                modelWarning?.let { put("warning", it) }
                put("message", "子代理已创建并开始执行，任务完成后会收到通知。主代理必须审查结果、范围和验收标准；必要时精准返工，最多两轮。可用 task(action=\"read\", id=...) 读取输出，task(action=\"stop\", id=...) 主动关闭。")
            }
        )
    }

    private fun requiredText(args: Map<String, JsonElement>, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

    private fun missingHandoffField(field: String) = ToolResult.Error(
        "拒绝派发：缺少必填交接字段 $field。必须提供目标背景、范围、共同约束、禁止事项、验收标准和返回格式。",
        "INCOMPLETE_HANDOFF"
    )

    private fun buildHandoffPrompt(
        objective: String,
        writable: String,
        readable: String,
        shared: String,
        doNot: String,
        acceptance: String,
        format: String,
        type: String,
        depth: String
    ): String = """你是受范围约束的 $type 子代理。本任务上下文与主代理隔离；不得假设你能看到主对话。

任务类型与检查深度：
$type${if (type == "explore") " / $depth" else ""}

目标与背景：
$objective

可写范围（范围外任何写入都将被系统拒绝）：
$writable

只读范围：
$readable

主代理已决定、不得擅自更改：
$shared

不要做：
$doNot

验收标准：
$acceptance

返回格式：
$format

仅在声明范围内工作。完成后汇报改动、运行的验证、发现的风险以及未完成事项；不确定或失败时给出结构化原因，不要谎称完成。""".trim()

    /**
     * 向指定子代理发送一条消息（可反复发送）。
     *
     * 事件交由 ViewModel 按收件人状态分发：运行中的子代理把消息入通知队列、在下一批工具结果里搭车送达；
     * 已完成的子代理则被重新唤醒并起新一轮；终态后重新唤醒计入持久化的两轮返工预算。
     * 不在工具层直接投递，以复用同一套忙碌/空闲分发逻辑。
     */
    private suspend fun sendToSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val message = (args["message"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (message.isNullOrBlank()) {
            return ToolResult.Error("参数无效：message 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在：$subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != parentSessionId) {
            return ToolResult.Error("只能向当前会话派生的子代理发消息", "NOT_YOUR_SUBAGENT")
        }

        var reworkRound: Int? = null
        var reservedPreviousStatus: String? = null
        if (SubAgentReworkPolicy.isTerminal(sub.subagentStatus)) {
            val previousStatus = requireNotNull(sub.subagentStatus)
            val reserved = chatSessionDao.reserveSubagentRework(
                id = subSessionId,
                parentId = parentSessionId,
                maxRounds = SubAgentReworkPolicy.MAX_ROUNDS
            )
            if (reserved == 1) {
                reworkRound = sub.subagentReworkCount + 1
                reservedPreviousStatus = previousStatus
            } else {
                // A concurrent sender may already have moved the child to STARTING.
                val latest = sessionUseCase.getSessionById(subSessionId)
                    ?: return ToolResult.Error("子会话不存在：$subSessionId", "SESSION_NOT_FOUND")
                if (SubAgentReworkPolicy.isTerminal(latest.subagentStatus)) {
                    return ToolResult.Error(
                        "该子代理的两轮返工已用尽（${latest.subagentReworkCount}/${SubAgentReworkPolicy.MAX_ROUNDS}）；请将未解决项告知用户或创建新任务。",
                        "SUBAGENT_REWORK_LIMIT"
                    )
                }
            }
        }

        val delivered = eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = parentSessionId,
                type = SubAgentEventType.MESSAGE_FROM_PARENT,
                detail = message
            )
        )
        if (!delivered) {
            reservedPreviousStatus?.let { previousStatus ->
                chatSessionDao.rollbackSubagentRework(subSessionId, parentSessionId, previousStatus)
            }
            return ToolResult.Error("子代理消息队列暂时繁忙，消息未确认投递；请稍后重试。", "SUBAGENT_MESSAGE_QUEUE_FULL")
        }
        FileLogger.i(TAG, "向子代理发送消息: session=$subSessionId parent=$parentSessionId")
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "delivered")
                reworkRound?.let {
                    put("reworkRound", it)
                    put("reworkRoundsRemaining", SubAgentReworkPolicy.remaining(it))
                }
                put(
                    "message",
                    if (reworkRound != null) {
                        "返工第 $reworkRound/${SubAgentReworkPolicy.MAX_ROUNDS} 轮已投递；运行中的将在下一批工具结果里收到，完成后请重新核验。"
                    } else {
                        "消息已投递给子代理；运行中的将在下一批工具结果里收到。终态子代理重新唤醒时计入两轮返工预算。"
                    }
                )
            }
        )
    }

    /** 读取指定子代理的最后输出。 */
    private suspend fun readSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在：$subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("只能读取当前会话派生的子代理", "NOT_YOUR_SUBAGENT")
        }

        val messages = agentMessageDao.getMessagesBySessionOnce(subSessionId)
        // 取最后一条有内容的助手回复（跳过 reasoning-only 的中间消息）
        val lastAssistant = messages.lastOrNull {
            it.role == MessageRole.ASSISTANT.name && it.content.isNotBlank()
        }
        val fallback = messages.lastOrNull { it.role == MessageRole.USER.name }
        val content = when {
            lastAssistant != null -> lastAssistant.content
            fallback != null -> "（子代理尚未回复）请求内容：${fallback.content.take(500)}"
            else -> "（子代理会话为空）"
        }
        val last = runCatching { messages.lastOrNull()?.timestamp ?: 0L }.getOrDefault(0L)
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("title", sub.title)
                put("updatedAt", last)
                put("lastOutput", content)
            }
        )
    }

    /** 停止指定子代理的执行。 */
    private suspend fun stopSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在：$subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("只能关闭当前会话派生的子代理", "NOT_YOUR_SUBAGENT")
        }

        val accepted = eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = context.sessionId!!,
                type = SubAgentEventType.STOP_REQUESTED
            )
        )
        if (!accepted) return ToolResult.Error("停止请求未能进入生命周期队列，请稍后重试。", "SUBAGENT_STOP_QUEUE_FULL")
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "stopping")
                put("message", "已请求停止子代理，正在取消其 AI 任务。")
            }
        )
    }

    /** 请求由 ViewModel 先取消并 join 子代理任务，再级联清理其数据。 */
    private suspend fun deleteSubagent(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val subSessionId = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (subSessionId.isNullOrBlank()) {
            return ToolResult.Error("参数无效：id 不能为空", "INVALID_ARGS")
        }
        val sub = sessionUseCase.getSessionById(subSessionId)
            ?: return ToolResult.Error("子会话不存在：$subSessionId", "SESSION_NOT_FOUND")
        if (sub.parentId != context.sessionId) {
            return ToolResult.Error("只能删除当前会话派生的子代理", "NOT_YOUR_SUBAGENT")
        }
        val accepted = eventBus.emit(
            SubAgentEvent(
                subSessionId = subSessionId,
                parentSessionId = context.sessionId!!,
                type = SubAgentEventType.DELETE_REQUESTED
            )
        )
        if (!accepted) return ToolResult.Error("删除请求未能进入生命周期队列，请稍后重试", "SUBAGENT_DELETE_QUEUE_FULL")
        FileLogger.i(TAG, "已请求删除子代理: session=$subSessionId parent=${context.sessionId}")
        return ToolResult.Success(
            buildJsonObject {
                put("id", subSessionId)
                put("state", "deleting")
                put("message", "已请求删除子代理；系统会先停止并等待其工作流，再删除会话、消息、待办与检查点。")
            }
        )
    }

    /** 列出当前会话的全部子代理。 */
    private suspend fun listSubagents(context: AgentContext): ToolResult {
        val parentSessionId = context.sessionId ?: return ToolResult.Error("缺少会话上下文", "NO_SESSION")
        val subs = chatSessionDao.getSubSessionsByParentOnce(parentSessionId)
        val activeIds = eventBus.activeSubSessionIds.value

        val jsonArray = buildJsonArray {
            subs.forEach { entity ->
                addJsonObject {
                    put("id", entity.id)
                    put("title", entity.title)
                    put("state", entity.subagentStatus?.lowercase() ?: if (entity.id in activeIds) "running" else "completed")
                    put("createdAt", entity.createdAt)
                    put("updatedAt", entity.updatedAt)
                }
            }
        }

        return ToolResult.Success(
            buildJsonObject {
                put("subagents", jsonArray)
                put("count", subs.size)
                put("runningCount", activeIds.size)
                put("maxRunning", subAgentSettings.maxRunning())
                put("maxTotalPerParent", subAgentSettings.maxTotalPerParent())
            }
        )
    }
}
