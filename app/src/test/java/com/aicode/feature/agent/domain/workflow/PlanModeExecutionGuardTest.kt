package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolCall
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanModeExecutionGuardTest {
    @Test
    fun `read operations do not require a plan but state-changing operations do`() {
        assertFalse(PlanModeExecutionGuard.requiresPlan(setOf(ToolCapability.READ_WORKSPACE)))
        assertFalse(PlanModeExecutionGuard.requiresPlan(setOf(ToolCapability.READ_WORKSPACE, ToolCapability.NETWORK_READ)))
        assertTrue(PlanModeExecutionGuard.requiresPlan(setOf(ToolCapability.WRITE_WORKSPACE)))
        assertTrue(PlanModeExecutionGuard.requiresPlan(setOf(ToolCapability.EXECUTE_COMMANDS)))
        assertTrue(PlanModeExecutionGuard.requiresPlan(setOf(ToolCapability.NETWORK_WRITE)))
    }

    @Test
    fun `unknown tool is treated conservatively as write-like`() {
        assertTrue(PlanModeExecutionGuard.requiresPlan(null, emptyMap()))
    }

    @Test
    fun `only a non-empty todo snapshot establishes a plan`() {
        val nonEmpty = ToolCall(
            id = "todo-1",
            name = "todo",
            arguments = buildJsonObject {
                put("items", buildJsonArray { add(buildJsonObject { put("subject", "Inspect") }) })
            }
        )
        val empty = ToolCall("todo-2", "todo", buildJsonObject { put("items", buildJsonArray {}) })
        val unrelated = nonEmpty.copy(name = "writeFile")

        assertTrue(PlanModeExecutionGuard.isPlanSubmission(nonEmpty))
        assertFalse(PlanModeExecutionGuard.isPlanSubmission(empty))
        assertFalse(PlanModeExecutionGuard.isPlanSubmission(unrelated))
    }
}
