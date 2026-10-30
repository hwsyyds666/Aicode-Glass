package com.aicode.feature.agent.domain.skill

/**
 * 把启用技能列表格式化为注入系统提示的清单，带总量预算与逐级降级。
 *
 * 对标依据（为什么必须有预算）：
 * - Codex（codex-rs/ext/skills）：技能清单约占上下文 2%（硬顶 10000 token），超预算时先删除
 *   描述退化为纯名称、再整体省略剩余并在提示里告知省略数量，保证大头留给真正的对话。
 * - Claude Code：约 1% 上下文预算，单条描述上限 250 字符。
 * 本机实测（改进前的证据）：118 个技能的纯清单 15441 字符，占 29526 字符系统提示的 53.2%，
 * 且无任何总量约束——同一份统计里 456 条消息 / 239 次工具调用中 loadSkill 调用为 0，
 * 模型对半屏清单直接无视，不如压缩清单 + 强制措辞 + 每轮相关提醒的组合拳。
 *
 * 降级顺序（预算单位为字符，按给定顺序贪心占位，顺序由调用方保证确定性）：
 * 1. `- name: description` 完整条目；
 * 2. 完整条目放不下后，剩余技能退化为 `- name` 纯名称条目（保留可发现性）；
 * 3. 纯名称也放不下则省略，并在末尾告知省略数量与自助查找路径。
 */
object SkillListFormatter {

    /**
     * 清单字符预算。取 Claude Code 1% 与 Codex 2% 之间偏保守的值：按主流 128K 上下文折算，
     * 1%≈4000 字符、2%≈8000 字符（中英混排），取上限；纯名称降级后实际占用通常低于此值。
     * 需要调整时只改这里，格式逻辑与测试均与之解耦。
     */
    const val DEFAULT_CHAR_BUDGET = 8_000

    /** 省略告知行的长度余量：预留出来，避免告知本身把清单顶超预算。 */
    private const val NOTICE_SLACK = 240

    /** 无描述时的占位，保持清单每行结构一致。 */
    private const val NO_DESC = "（无描述）"

    /**
     * 格式化技能清单；[skills] 为空返回 ""。
     * 输出长度不超过 [charBudget]（含省略告知行），除非单条名称本身就超过剩余预算——
     * 那种情况下宁可不列该条也不会突破预算上限。
     */
    fun format(skills: List<Skill>, charBudget: Int = DEFAULT_CHAR_BUDGET): String {
        if (skills.isEmpty()) return ""
        if (charBudget <= 0) return ""

        val limit = (charBudget - NOTICE_SLACK).coerceAtLeast(0)
        val full = StringBuilder()
        val namesOnly = StringBuilder()
        var used = 0
        var omitted = 0
        var descPhaseDone = false

        for (skill in skills) {
            val name = skill.name.trim()
            if (name.isEmpty()) continue
            val fullLine = "- $name: ${skill.description.trim().ifEmpty { NO_DESC }}"
            if (!descPhaseDone) {
                val cost = fullLine.length + 1 // +1 = 行尾换行
                if (used + cost <= limit) {
                    full.appendLine(fullLine)
                    used += cost
                    continue
                }
                descPhaseDone = true
            }
            val nameLine = "- $name"
            val nameCost = nameLine.length + 1
            if (used + nameCost <= limit) {
                namesOnly.appendLine(nameLine)
                used += nameCost
            } else {
                omitted++
            }
        }

        // 一条都放不下（预算小于 NOTICE_SLACK）时只剩告知行，也比空清单好。
        if (full.isEmpty() && namesOnly.isEmpty() && omitted == 0) return ""

        return buildString {
            if (full.isNotEmpty()) append(full.trimEnd())
            if (namesOnly.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(namesOnly.trimEnd())
            }
            if (omitted > 0) {
                if (isNotEmpty()) append('\n')
                append(omissionNotice(omitted, charBudget))
            }
        }.trim()
    }

    /** 省略告知：告诉模型少了多少、去哪看全量、以及未列出的也能按名称尝试加载。 */
    private fun omissionNotice(omitted: Int, charBudget: Int): String =
        "…另有 $omitted 个技能因提示词预算（上限 $charBudget 字符）未在此列出；" +
            "完整名单见 ~/.aicode/skills/ 与 <项目根目录>/.aicode/skills/ 目录，" +
            "任务相关时可用 loadSkill 按名称尝试加载（工具会返回当前可用名单）。"
}
