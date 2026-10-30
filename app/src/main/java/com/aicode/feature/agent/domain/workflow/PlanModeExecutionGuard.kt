package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolCapability
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Legacy helpers kept for source compatibility. PLAN writes are now hard-denied by
 * ToolPermissionPolicyEngine and execution is unlocked only by the planMode approval flow.
 */
internal object PlanModeExecutionGuard {
    private val planRequiredCapabilities = setOf(
        ToolCapability.WRITE_WORKSPACE,
        ToolCapability.EXECUTE_COMMANDS,
        ToolCapability.NETWORK_WRITE,
        ToolCapability.MODIFY_AGENT_CONFIG,
        ToolCapability.MODIFY_CONTAINER_ENV,
        ToolCapability.EXTERNAL_TOOL
    )

    fun requiresPlan(tool: AgentTool?, arguments: Map<String, JsonElement>): Boolean =
        tool == null || requiresPlan(tool.effectiveCapabilities(arguments))

    fun requiresPlan(capabilities: Set<ToolCapability>): Boolean =
        capabilities.any { it in planRequiredCapabilities }

    fun isPlanSubmission(toolCall: ToolCall): Boolean =
        toolCall.name == "todo" &&
            ((toolCall.arguments["items"] as? JsonArray)?.isNotEmpty() == true)
}
