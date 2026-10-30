package com.aicode.feature.agent.domain.tool.todo

import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.data.local.entity.TodoItemEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.tool.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodoToolTest {

    @Test
    fun planSnapshot_tracksStepProgressAndSynchronizesPlanChanges() = runTest {
        val dao = mockk<TodoItemDao>(relaxed = true)
        var rows = emptyList<TodoItemEntity>()
        coEvery { dao.getBySessionOnce("session") } answers { rows }
        coEvery { dao.replaceForSession("session", any()) } answers { rows = secondArg() }
        val tool = TodoTool(dao)
        val context = AgentContext(
            currentFile = null,
            selectedCode = null,
            projectRoot = "/workspace",
            language = null,
            sessionId = "session"
        )

        tool.executeWithContext(
            mapOf("items" to Json.parseToJsonElement("""
                [
                  {"subject":"Inspect code","status":"in_progress"},
                  {"subject":"Implement fix","status":"pending"},
                  {"subject":"Verify","status":"pending"}
                ]
            """.trimIndent())),
            context
        )
        val originalInspectId = rows.first().id

        tool.executeWithContext(
            mapOf("items" to Json.parseToJsonElement("""
                [
                  {"subject":"Inspect code","status":"completed"},
                  {"subject":"Implement fix","status":"in_progress"},
                  {"subject":"Verify","status":"failed","description":"本地环境缺少所需设备"}
                ]
            """.trimIndent())),
            context
        )
        assertEquals(listOf("COMPLETED", "IN_PROGRESS", "FAILED"), rows.map { it.status })
        assertEquals(originalInspectId, rows.first().id)

        val result = tool.executeWithContext(
            mapOf("items" to Json.parseToJsonElement("""
                [
                  {"subject":"Implement fix","status":"completed"},
                  {"subject":"Run expanded verification","status":"in_progress","priority":2}
                ]
            """.trimIndent())),
            context
        )
        assertTrue(result is ToolResult.Success)
        assertEquals(listOf("Implement fix", "Run expanded verification"), rows.map { it.subject })
        assertEquals(listOf("COMPLETED", "IN_PROGRESS"), rows.map { it.status })
        assertNotEquals(originalInspectId, rows.last().id)
        val json = (result as ToolResult.Success).data.jsonObject
        assertEquals(2, json["total"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun planSnapshotRejectsInvalidStatusInsteadOfRecordingFalseProgress() = runTest {
        val dao = mockk<TodoItemDao>(relaxed = true)
        coEvery { dao.getBySessionOnce(any()) } returns emptyList()
        val result = TodoTool(dao).executeWithContext(
            mapOf("items" to Json.parseToJsonElement("[{\"subject\":\"Unverified\",\"status\":\"done\"}]")),
            AgentContext(null, null, "/workspace", null, sessionId = "session")
        )
        assertTrue(result is ToolResult.Error)
    }

    @Test
    fun planModeAllowsOnlyOneInProgressStep() = runTest {
        val dao = mockk<TodoItemDao>(relaxed = true)
        coEvery { dao.getBySessionOnce(any()) } returns emptyList()
        val result = TodoTool(dao).executeWithContext(
            mapOf("items" to Json.parseToJsonElement("""
                [
                  {"subject":"First","status":"in_progress"},
                  {"subject":"Second","status":"in_progress"}
                ]
            """.trimIndent())),
            AgentContext(null, null, "/workspace", null, sessionId = "session", mode = AgentMode.PLAN)
        )

        assertTrue(result is ToolResult.Error)
        coVerify(exactly = 0) { dao.replaceForSession(any(), any()) }
    }
}
