package com.aicode.feature.agent.domain.tool.mode

import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/** 让 AI 进入或退出 PLAN（计划）模式；退出时恢复到进入 PLAN 之前的模式。 */
class PlanModeTool @Inject constructor(
    private val chatSessionDao: ChatSessionDao
) : AbstractContextualTool() {

    override val name = "planMode"
    override val description = "进入或退出 PLAN（计划）模式。PLAN 的职责是先制定并展示执行计划，再按清单逐步完成；它拥有与 BUILD 相同的工具能力和权限挡位，不代表免授权或只读。进入后先用 todo 提交完整步骤清单，执行过程中及时更新 in_progress/completed，并在失败或计划变化时同步更新；不要在计划为空时直接开始多步骤工作。action=\"exit\"：计划执行完或不再需要计划时退出；退出后自动恢复进入 PLAN 之前的模式（从 BUILD 进入回 BUILD，从 ASK 进入回 ASK）。切换模式不再额外等待计划批准面板；文件、命令、网络等操作仍由用户当前授权挡位和危险操作专用授权决定。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作：'enter' 进入 PLAN 模式（先计划后执行），'exit' 退出 PLAN 模式（恢复到进入前的模式）",
            required = true,
            enum = listOf("enter", "exit")
        ),
        "reason" to ToolParameter(
            name = "reason",
            type = ParameterType.STRING,
            description = "进入或退出 PLAN 模式的理由，记入模式切换记录",
            required = true
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("缺少必需参数：action", "MISSING_ACTION")

        val reason = args["reason"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数：reason", "MISSING_REASON")

        val entering = when (action) {
            "enter" -> true
            "exit" -> false
            else -> return ToolResult.Error("无效的 action：$action，只能是 'enter' 或 'exit'", "INVALID_ACTION")
        }

        if (entering && context.mode == AgentMode.PLAN) {
            return ToolResult.Success(JsonPrimitive("当前已处于 PLAN 模式，无需进入。"))
        }
        if (!entering && context.mode != AgentMode.PLAN) {
            return ToolResult.Error("当前不在 PLAN 模式，无法退出", "NOT_IN_PLAN")
        }

        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话 ID，无法切换模式", "NO_SESSION")

        val sessionEntity = chatSessionDao.getById(sessionId)
            ?: return ToolResult.Error("找不到会话记录", "SESSION_NOT_FOUND")

        // 进入 PLAN 时记下当时的模式，退出时恢复到它：从 ASK/BUILD 进入 PLAN 的规划往返结束后
        // 应回到原模式，而不是一律落回 BUILD。该字段只在处于 PLAN 时被读取，每次进入 PLAN 都会覆写，因此无需在退出时清理。
        val effectiveMode: AgentMode
        val modeBeforePlan: String?
        if (entering) {
            effectiveMode = AgentMode.PLAN
            modeBeforePlan = context.mode.name
        } else {
            effectiveMode = sessionEntity.modeBeforePlan
                ?.let { runCatching { AgentMode.valueOf(it) }.getOrNull() }
                ?: AgentMode.BUILD
            modeBeforePlan = sessionEntity.modeBeforePlan
        }

        // 保存到数据库。UI 层通过 flow 监听，会自动更新外观与后续流程的上下文。
        chatSessionDao.upsert(sessionEntity.copy(mode = effectiveMode.name, modeBeforePlan = modeBeforePlan))

        return ToolResult.Success(
            JsonPrimitive(
                if (entering) "已进入 PLAN 模式。" else "已退出 PLAN 模式，恢复为 ${effectiveMode.name} 模式。"
            )
        )
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val entering = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.equals("enter", ignoreCase = true) ?: true
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull ?: "无理由"
        val label = if (entering) "进入 PLAN 模式" else "退出 PLAN 模式"

        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "计划模式申请",
            summary = "AI 申请$label",
            details = "操作：$label\n\n申请理由：$reason",
            argsPreview = argsPreview,
            rememberablePatterns = emptyList()
        )
    }
}
