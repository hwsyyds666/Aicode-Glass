package com.aicode.feature.agent.domain.skill

/**
 * 每轮按用户输入匹配「可能相关」的技能，生成一小段追加到本轮最新 user 消息末尾的提醒。
 *
 * 对标依据：Claude Code 每轮向模型注入 "Skills relevant to your task:"——清单只在系统提示里
 * 出现一次，模型做着做着就忘了；在决策点（本轮请求）再把最相关的几个技能连描述带名字推到眼前，
 * 是它技能调用率高的关键一招。Codex 则靠清单预算 + 强制措辞达到同样目的。
 *
 * 匹配是纯本地启发式，不求全：[MIN_SCORE] 分门槛保证只有撞上多个触发词才算相关；
 * 提醒文案自带「均不适用的忽略本段」，误报的代价只是一小段可忽略文本。
 *
 * 触发词抽取（用户文本与技能两侧用同一套规则）：
 * - 中文：连续两个汉字构成一个二元组（bigram），如「刷机」「救砖」；
 * - 英文/数字：长度 ≥ 3 的词（小写化），如 root、adb、ssh、pdf、apk。
 * 技能名命中权重加倍：名字撞上基本就是它。
 */
object SkillRelevanceMatcher {

    /** 提醒里最多列几个技能：3 个足够覆盖绝大多数任务，再多又变成被无视的清单。 */
    const val MAX_HINT_SKILLS = 3

    /** 得分门槛：至少两个触发词命中（或一个技能名命中）才提醒，避免满屏误报。 */
    const val MIN_SCORE = 2

    /** 提醒里单条描述的最大长度，防止相关技能本身又把本轮请求撑大。 */
    private const val HINT_DESC_CHARS = 120

    private val LATIN_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9_.+-]{2,}")

    /** 汉字区间（基本区 + 扩展 A）。 */
    private fun isCjk(c: Char): Boolean = c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF

    /**
     * 生成提醒文本；无命中 / 无技能 / 用户以 `/技能名` 触发 slash 命令时返回 null
     * （slash 命令的技能正文已作为该轮指令注入，无需再提醒 loadSkill）。
     */
    fun buildHint(skills: List<Skill>, userText: String): String? {
        if (skills.isEmpty() || userText.isBlank()) return null
        if (userText.trimStart().startsWith("/")) return null

        val userTerms = extractTerms(userText)
        if (userTerms.isEmpty()) return null

        val scored = skills.mapNotNull { skill ->
            val score = score(skill, userTerms)
            if (score >= MIN_SCORE) skill to score else null
        }
        if (scored.isEmpty()) return null

        val lines = scored
            .sortedByDescending { it.second }
            .take(MAX_HINT_SKILLS)
            .joinToString("\n") { (skill, _) -> "- ${skill.name}: ${truncate(skill.description)}" }

        return "[系统提醒] 以下技能可能与本轮任务相关：若某个适配，你必须先调用 loadSkill 加载其正文并在本轮遵循，再开始行动；均不适用的忽略本段。\n$lines"
    }

    /** 中文 bigram + 长度 ≥3 的英文数字词。 */
    private fun extractTerms(text: String): Set<String> {
        val terms = LinkedHashSet<String>()
        LATIN_TOKEN.findAll(text).forEach { terms.add(it.value.lowercase()) }
        var i = 0
        while (i < text.length - 1) {
            if (isCjk(text[i]) && isCjk(text[i + 1])) {
                terms.add(text.substring(i, i + 2))
                i += 1 // 滑动窗口：相邻 bigram 都要，便于命中连续词
            } else {
                i += 1
            }
        }
        return terms
    }

    /** 技能名命中 ×2 + 描述命中 ×1。 */
    private fun score(skill: Skill, userTerms: Set<String>): Int {
        var s = 0
        for (token in nameTerms(skill.name)) {
            if (token in userTerms) s += 2
        }
        for (token in extractTerms(skill.description)) {
            if (token in userTerms) s += 1
        }
        return s
    }

    /** 技能名切词：英文数字分词（≥3 字符），中文整段再拆成 bigram，与用户侧对齐。 */
    private fun nameTerms(name: String): Set<String> {
        val terms = LinkedHashSet<String>()
        LATIN_TOKEN.findAll(name).forEach { terms.add(it.value.lowercase()) }
        var i = 0
        while (i < name.length - 1) {
            if (isCjk(name[i]) && isCjk(name[i + 1])) {
                terms.add(name.substring(i, i + 2))
                i += 1
            } else {
                i += 1
            }
        }
        return terms
    }

    private fun truncate(description: String): String {
        val d = description.trim().ifEmpty { "（无描述）" }
        return if (d.length <= HINT_DESC_CHARS) d else d.take(HINT_DESC_CHARS) + "…"
    }
}
