package com.aicode.feature.agent.domain.tool.editor

import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.domain.FileAccessProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 17·P0-2：editFile 的大文件保护。
 *
 * - 超 2MB 的文件读前拒绝（FILE_TOO_LARGE），且绝不发生全量 readFile；
 * - 阈值内编辑行为不变（原子写盘 + unified diff）；
 * - 单处编辑任一侧行数超过上限时跳过 LCS，diff_skipped=true 供界面提示。
 */
class EditFileToolSizeLimitTest {

    private val fileAccess = mockk<FileAccessProvider>(relaxed = true)
    private val tool = EditFileTool(fileAccess)

    private fun editsJson(vararg pairs: Pair<String, String>): JsonArray =
        JsonArray(pairs.map { (old, new) ->
            JsonObject(mapOf(
                "old_string" to JsonPrimitive(old),
                "new_string" to JsonPrimitive(new),
                "replace_all" to JsonPrimitive(false)
            ))
        })

    private fun args(path: String, edits: JsonArray) =
        mapOf<String, kotlinx.serialization.json.JsonElement>(
            "path" to JsonPrimitive(path),
            "edits" to edits
        )

    @Test
    fun rejectsOversizedFileWithoutReadingContent() = runTest {
        coEvery { fileAccess.exists(any()) } returns true
        coEvery { fileAccess.fileSize(any()) } returns 3L * 1024 * 1024 // 3MB > 2MB 上限

        val result = tool.execute(args("/workspace/big.txt", editsJson("a" to "b")))

        assertTrue(result is ToolResult.Error)
        assertEquals("FILE_TOO_LARGE", (result as ToolResult.Error).code)
        assertTrue(result.message.contains("read_file"))
        // 关键：读前拒绝，绝不全量读入内存
        coVerify(exactly = 0) { fileAccess.readFile(any()) }
        coVerify(exactly = 0) { fileAccess.writeFile(any(), any(), any(), any()) }
    }

    @Test
    fun editsFileWithinLimitNormally() = runTest {
        coEvery { fileAccess.exists(any()) } returns true
        coEvery { fileAccess.fileSize(any()) } returns 1024
        coEvery { fileAccess.readFile(any()) } returns "hello world"
        val written = io.mockk.slot<String>()
        coEvery { fileAccess.writeFile(any(), capture(written), any(), any()) } returns Unit

        val result = tool.execute(args("/workspace/a.txt", editsJson("hello" to "hi")))

        assertTrue(result is ToolResult.Success)
        assertEquals("hi world", written.captured)
        val data = (result as ToolResult.Success).data.jsonObject
        assertEquals("edited", data["status"]?.jsonPrimitive?.contentOrNull)
        val hunks = data["hunks"]!!.jsonArray
        assertEquals(1, hunks.size)
        assertEquals(false, hunks[0].jsonObject["diff_skipped"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun skipsLineDiffWhenEditSidesExceedLineLimit() = runTest {
        // 单处编辑任一侧 2500 行 > 2000 行上限：跳过 LCS，降级为「整段视为新增」
        val old = (1..2500).joinToString("\n") { "line $it" }
        val content = "HEAD\n$old\nTAIL"
        coEvery { fileAccess.exists(any()) } returns true
        coEvery { fileAccess.fileSize(any()) } returns content.length.toLong()
        coEvery { fileAccess.readFile(any()) } returns content

        val result = tool.execute(args("/workspace/big.txt", editsJson(old to "collapsed")))

        assertTrue(result is ToolResult.Success)
        val data = (result as ToolResult.Success).data.jsonObject
        val hunk = data["hunks"]!!.jsonArray[0].jsonObject
        assertEquals(true, hunk["diff_skipped"]?.jsonPrimitive?.boolean)
        // 降级呈现：整段新内容视为一行新增
        assertEquals(1, hunk["added"]?.jsonPrimitive?.int)
        assertEquals(0, hunk["removed"]?.jsonPrimitive?.int)
    }

    @Test
    fun missingFileStillRejected() = runTest {
        coEvery { fileAccess.exists(any()) } returns false

        val result = tool.execute(args("/workspace/none.txt", editsJson("a" to "b")))

        assertTrue(result is ToolResult.Error)
        assertEquals("FILE_NOT_FOUND", (result as ToolResult.Error).code)
    }
}
