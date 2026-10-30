package com.aicode.feature.agent.domain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRelevanceMatcherTest {

    private fun skill(name: String, description: String) =
        Skill(name = name, description = description, instructions = "正文")

    private val rootSkills = listOf(
        skill("root-toolkit", "以 root 身份执行命令、刷机与救砖的专用流程"),
        skill("pdf-report", "把 Markdown 渲染成 PDF 报告")
    )

    @Test
    fun matchesOnNameAndDescriptionTerms() {
        val hint = SkillRelevanceMatcher.buildHint(rootSkills, "手机变砖了，帮我救砖，需要 root")
        assertNotNull(hint)
        assertTrue(hint!!.contains("root-toolkit"))
        assertFalse("不相关的技能不应出现", hint.contains("pdf-report"))
        assertTrue(hint.contains("loadSkill"))
        assertTrue(hint.contains("忽略本段"))
    }

    @Test
    fun unrelatedInput_returnsNull() {
        assertNull(SkillRelevanceMatcher.buildHint(rootSkills, "今天天气不错，随便聊聊"))
    }

    @Test
    fun slashCommandInput_returnsNull() {
        // `/技能名` 触发的技能正文已作为该轮指令注入，无需再提醒 loadSkill。
        assertNull(SkillRelevanceMatcher.buildHint(rootSkills, "/root-toolkit 救砖"))
    }

    @Test
    fun emptySkillsOrBlankInput_returnsNull() {
        assertNull(SkillRelevanceMatcher.buildHint(emptyList(), "救砖"))
        assertNull(SkillRelevanceMatcher.buildHint(rootSkills, "   "))
    }

    @Test
    fun capsAtThreeSkills() {
        val skills = (1..10).map { skill("skill-$it", "通用的救砖刷机 root 流程说明") }
        val hint = SkillRelevanceMatcher.buildHint(skills, "救砖 刷机 root")
        assertNotNull(hint)
        val listed = hint!!.lines().filter { it.startsWith("- skill-") }
        assertEquals(3, listed.size)
    }

    @Test
    fun longDescriptionInHint_isTruncated() {
        val skills = listOf(skill("verbose", "救砖刷机" + "x".repeat(300)))
        val hint = SkillRelevanceMatcher.buildHint(skills, "救砖 刷机")
        assertNotNull(hint)
        val line = hint!!.lines().first { it.startsWith("- verbose") }
        // 行 = "- verbose: "（11 字符）+ 描述截断到 120 字符 + 省略号
        assertTrue("长描述应截断: ${line.length}", line.length <= 132)
        assertTrue(line.endsWith("…"))
    }

    @Test
    fun singleDescriptionHit_isNotEnough() {
        // 只撞一个描述二元组（如「文件」这种高频词）达不到 MIN_SCORE，不提醒。
        val skills = listOf(skill("file-ops", "读写文件与目录的通用流程"))
        assertNull(SkillRelevanceMatcher.buildHint(skills, "看看这个文件"))
    }
}
