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
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.agent.domain.tool.question.PendingUserQuestion
import com.aicode.feature.agent.domain.tool.question.QuestionItem
import com.aicode.feature.agent.domain.tool.question.QuestionOption
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject

/**
 * OpenCode 2.0-style plan agent switch.
 *
 * Entering plan mode and leaving it are both explicit user decisions. While PLAN is active,
 * the policy engine rejects write/command tools, so a model cannot start implementation just
 * because it decided to call this tool.
 */
class PlanModeTool @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val questionManager: AskUserQuestionManager
) : AbstractContextualTool() {

    override val name = "planMode"
    override val description = "切换 OpenCode 风格的计划代理。enter 会先请求用户确认进入只读计划模式；" +
        "PLAN 中只能读取、调查、提问并更新 todo 计划，不能编辑文件、执行命令或进行其他写操作。" +
        "计划完成后调用 exit，只有用户明确批准才切换到 BUILD 并开始实施。"
    // The structured question below is the approval boundary; showing a second generic permission
    // dialog would duplicate the OpenCode plan_enter/plan_exit interaction.
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作：'enter' 请求进入只读 PLAN 模式，'exit' 请求在计划完成后切换到 BUILD",
            required = true,
            enum = listOf("enter", "exit")
        ),
        "reason" to ToolParameter(
            name = "reason",
            type = ParameterType.STRING,
            description = "进入或退出计划模式的简短理由",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("缺少必需参数：action", "MISSING_ACTION")

        val reason = args["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

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

        val approval = if (entering) {
            awaitApproval(
                question = "这个请求适合先制定计划。是否切换到 PLAN 模式并先进行只读调查？",
                yesLabel = "进入 PLAN（推荐）",
                yesDescription = "先读取和分析项目，创建计划，暂不修改文件或执行命令。",
                noLabel = "留在 BUILD",
                noDescription = "继续当前构建模式，直接处理请求。"
            )
        } else {
            awaitApproval(
                question = "计划已完成。是否切换到 BUILD 模式并开始执行？",
                yesLabel = "批准并开始执行（推荐）",
                yesDescription = "切换到 BUILD 模式，允许按计划使用写入和命令工具。",
                noLabel = "继续完善计划",
                noDescription = "保持 PLAN 只读模式，继续调查或修改计划。"
            )
        }
        if (!approval) {
            return ToolResult.Error(
                if (entering) "用户没有批准进入 PLAN 模式，保持当前模式。"
                else "用户没有批准开始执行，继续保持 PLAN 模式。",
                if (entering) "PLAN_NOT_APPROVED" else "PLAN_EXECUTION_NOT_APPROVED"
            )
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

    private suspend fun awaitApproval(
        question: String,
        yesLabel: String,
        yesDescription: String,
        noLabel: String,
        noDescription: String
    ): Boolean {
        val pending = PendingUserQuestion(
            id = UUID.randomUUID().toString(),
            questions = listOf(
                QuestionItem(
                    question = question,
                    header = "计划",
                    options = listOf(
                        QuestionOption(yesLabel, yesDescription),
                        QuestionOption(noLabel, noDescription)
                    )
                )
            )
        )
        val answer = questionManager.awaitAnswer(pending)
        val selected = answer.answers.firstOrNull()?.selected.orEmpty()
        return selected.any { it == yesLabel }
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
