package com.aicode.feature.agent.domain.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillListFormatterTest {

    private fun skill(name: String, description: String = "描述") =
        Skill(name = name, description = description, instructions = "正文")

    @Test
    fun emptyList_returnsEmptyString() {
        assertEquals("", SkillListFormatter.format(emptyList()))
    }

    @Test
    fun zeroBudget_returnsEmptyString() {
        assertEquals("", SkillListFormatter.format(listOf(skill("a")), charBudget = 0))
    }

    @Test
    fun smallList_keepsFullEntries() {
        val skills = listOf(skill("a", "甲"), skill("b", "乙"))
        val out = SkillListFormatter.format(skills, charBudget = 1000)
        assertTrue(out.contains("- a: 甲"))
        assertTrue(out.contains("- b: 乙"))
        assertFalse(out.contains("未在此列出"))
        assertEquals(2, out.lines().size)
    }

    @Test
    fun blankDescription_usesPlaceholder() {
        val out = SkillListFormatter.format(listOf(skill("a", "")), charBudget = 1000)
        assertTrue(out.contains("- a: （无描述）"))
    }

    @Test
    fun budgetExhausted_degradesToNameOnly_thenOmitsWithNotice() {
        // 40 个等长技能，预算 600：完整条目占不满预算后整体退化为纯名称，再放不下的省略并告知。
        val skills = (1..40).map { skill("skill-%02d".format(it), "描".repeat(30)) }
        val budget = 600
        val out = SkillListFormatter.format(skills, charBudget = budget)

        val fullLines = out.lines().filter { it.startsWith("- skill-") && it.contains(": ") }
        val nameLines = out.lines().filter { it.startsWith("- skill-") && !it.contains(": ") }
        assertTrue("完整条目应先占预算", fullLines.isNotEmpty())
        assertTrue("预算耗尽后应退化为纯名称", nameLines.isNotEmpty())

        val omitted = skills.size - fullLines.size - nameLines.size
        assertTrue("应有省略技能", omitted > 0)
        assertTrue(out.contains("$omitted 个技能"))
        assertTrue(out.contains("~/.aicode/skills/"))
        assertTrue(out.contains("loadSkill"))
        assertTrue("输出长度 ${out.length} 超过预算 $budget", out.length <= budget)

        val lastFull = out.lines().indexOfLast { it.startsWith("- skill-") && it.contains(": ") }
        val firstName = out.lines().indexOfFirst { it.startsWith("- skill-") && !it.contains(": ") }
        assertTrue("完整条目必须排在纯名称之前", firstName > lastFull)
    }

    @Test
    fun tinyBudget_onlyNoticeAndStaysBounded() {
        val skills = (1..10).map { skill("skill-$it", "描述") }
        val out = SkillListFormatter.format(skills, charBudget = 200)
        assertTrue(out.contains("未在此列出"))
        assertTrue(out.contains("${skills.size} 个技能"))
        assertTrue("输出长度 ${out.length} 超过预算 200", out.length <= 200)
    }

    @Test
    fun defaultBudget_fitsRealWorldScale() {
        // 真机实测规模：118 个技能、平均描述约 131 字符，改进前裸清单 15441 字符。
        val skills = (1..118).map { skill("builtin-skill-$it", "描".repeat(131)) }
        val out = SkillListFormatter.format(skills)
        assertTrue(out.length <= SkillListFormatter.DEFAULT_CHAR_BUDGET)
        assertTrue(out.contains("未在此列出"))
    }

    @Test
    fun allEntriesFit_nothingOmitted() {
        val skills = (1..50).map { skill("s$it", "短描述") }
        val out = SkillListFormatter.format(skills)
        assertFalse(out.contains("未在此列出"))
        assertEquals(50, out.lines().size)
    }
}
