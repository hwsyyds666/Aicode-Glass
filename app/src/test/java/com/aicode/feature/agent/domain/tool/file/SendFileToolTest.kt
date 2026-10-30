package com.aicode.feature.agent.domain.tool.file

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.just
import io.mockk.Runs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SendFileToolTest {
    private lateinit var fileAccess: FileAccessProvider
    private lateinit var settings: GeneralSettingsRepository
    private lateinit var messages: AgentMessageDao
    private lateinit var tool: SendFileTool

    @Before
    fun setUp() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } just Runs
        every { FileLogger.w(any(), any(), any()) } just Runs
        every { FileLogger.e(any(), any(), any()) } just Runs

        fileAccess = mockk(relaxed = true)
        settings = mockk()
        messages = mockk(relaxed = true)
        every { settings.sendFileMaxSizeMbFlow } returns flowOf(1)
        coEvery { settings.sendFileMaxSizeMb() } returns 1
        coEvery { messages.getMessagesBySessionOnce(any()) } returns emptyList()
        tool = SendFileTool(fileAccess, settings, messages)
    }

    @After
    fun tearDown() {
        unmockkObject(FileLogger)
    }

    @Test
    fun missingPathReturnsActionableError() = runTest {
        coEvery { fileAccess.exists("~/workspace/missing.md") } returns false

        val result = tool.executeWithContext(args("~/workspace/missing.md"), context()) as ToolResult.Error

        assertEquals("INVALID_FILE", result.code)
        assertTrue(result.message.contains("不存在或不可访问"))
        assertTrue(result.message.contains("list/search"))
        coVerify(exactly = 0) { fileAccess.copyToLocal(any()) }
    }

    @Test
    fun fileOverConfiguredLimitIsRejectedBeforeCopy() = runTest {
        coEvery { fileAccess.exists("~/workspace/large.bin") } returns true
        coEvery { fileAccess.isFile("~/workspace/large.bin") } returns true
        coEvery { fileAccess.fileSize("~/workspace/large.bin") } returns 1_048_577L

        val result = tool.executeWithContext(args("~/workspace/large.bin"), context()) as ToolResult.Error

        assertEquals("INVALID_FILE", result.code)
        assertTrue(result.message.contains("超过 1MB 限制"))
        coVerify(exactly = 0) { fileAccess.copyToLocal(any()) }
    }

    @Test
    fun previouslyDeliveredPathIsNotSentAgain() = runTest {
        val attachment = AgentAttachment(
            fileName = "report.md",
            containerPath = "~/workspace/reports/report.md",
            localPath = "/tmp/report.md",
            mimeType = "text/markdown",
            sizeBytes = 20,
            isImage = false
        )
        val oldToolMessage = AgentMessageEntity(
            id = "tool-1",
            sessionId = "session-1",
            role = "TOOL",
            content = "delivered",
            timestamp = 1,
            toolName = "sendFile",
            attachmentsJson = Json.encodeToString(listOf(attachment))
        )
        coEvery { messages.getMessagesBySessionOnce("session-1") } returns listOf(oldToolMessage)

        val result = tool.executeWithContext(args("~/workspace/reports/report.md"), context()) as ToolResult.Error

        assertEquals("ALREADY_SENT", result.code)
        coVerify(exactly = 0) { fileAccess.exists(any()) }
    }

    @Test
    fun containerPathMapsToDisplayAndLocalAttachmentPaths() = runTest {
        val source = "~/workspace/reports/final.md"
        coEvery { fileAccess.exists(source) } returns true
        coEvery { fileAccess.isFile(source) } returns true
        coEvery { fileAccess.fileSize(source) } returns 42L
        coEvery { fileAccess.toDisplayPath(source) } returns "~/workspace/reports/final.md"
        coEvery { fileAccess.copyToLocal(source) } returns File("C:/cache/final.md")

        val result = tool.executeWithContext(args(source), context()) as ToolResult.Success
        val file = (result.data as JsonObject)["files"] as JsonArray
        val attachment = file.single().jsonObject

        assertEquals("~/workspace/reports/final.md", attachment.getValue("path").jsonPrimitive.content)
        assertEquals(File("C:/cache/final.md").absolutePath, attachment.getValue("local_path").jsonPrimitive.content)
        assertEquals("final.md", attachment.getValue("name").jsonPrimitive.content)
        assertEquals("42", attachment.getValue("size_bytes").jsonPrimitive.content)
    }

    private fun args(path: String) = mapOf(
        "paths" to JsonArray(listOf(JsonPrimitive(path)))
    )

    private fun context() = AgentContext(
        currentFile = null,
        selectedCode = null,
        projectRoot = "~/workspace",
        language = "en",
        sessionId = "session-1"
    )
}
