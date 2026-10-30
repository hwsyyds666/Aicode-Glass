package com.aicode.feature.agent.domain.tool.skill

import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.tool.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LoadSkillTool] 的参数校验与仓库委托行为。
 *
 * 覆盖 10-18 真机崩溃的入口：AI 调 loadSkill(re-analyze) 后进程死亡——
 * 根因在执行链（全量扫描），工具层保证参数与错误路径行为不回归。
 */
class LoadSkillToolTest {

    private val repository = mockk<SkillRepository>()
    private val tool = LoadSkillTool(repository)

    private fun args(name: String?): Map<String, JsonElement> =
        if (name == null) emptyMap() else mapOf("skill_name" to JsonPrimitive(name))

    @Test
    fun execute_returnsInstructionsFromRepository() = runBlocking {
        coEvery { repository.loadInstructions("re-analyze") } returns "技能正文"

        val result = tool.execute(args("re-analyze"))

        assertTrue(result is ToolResult.Success)
        assertEquals(
            "技能正文",
            ((result as ToolResult.Success).data as JsonPrimitive).content
        )
    }

    @Test
    fun execute_missingNameReturnsError() = runBlocking {
        val result = tool.execute(args(null))

        assertTrue(result is ToolResult.Error)
        assertEquals("MISSING_SKILL_NAME", (result as ToolResult.Error).code)
    }

    @Test
    fun execute_blankNameReturnsError() = runBlocking {
        val result = tool.execute(args("   "))

        assertTrue(result is ToolResult.Error)
        assertEquals("MISSING_SKILL_NAME", (result as ToolResult.Error).code)
    }

    @Test
    fun execute_unknownSkillListsAvailableOnes() = runBlocking {
        coEvery { repository.loadInstructions("nope") } returns null
        coEvery { repository.listSkills() } returns listOf(
            Skill(name = "re-analyze", description = "逆向分析", instructions = "x")
        )

        val result = tool.execute(args("nope"))

        assertTrue(result is ToolResult.Error)
        val error = result as ToolResult.Error
        assertEquals("SKILL_NOT_FOUND", error.code)
        assertTrue(error.message.contains("re-analyze"))
    }

    @Test
    fun execute_passesNameThrough() = runBlocking {
        coEvery { repository.loadInstructions(any()) } returns "正文"

        val result = tool.execute(args("Re-Analyze"))

        assertTrue(result is ToolResult.Success)
    }
}
