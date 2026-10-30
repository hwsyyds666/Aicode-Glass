package com.aicode.feature.agent.domain.permission

import kotlinx.serialization.Serializable

/**
 * 高危拦截规则：「规则拦截」挡位下，命中任一启用规则的 shell 命令必须转人工确认（ASK）。
 *
 * 与授权规则（[PermissionRule]）的区别：授权规则由「始终允许/拒绝」的记忆产生，判定方向是放行/禁止；
 * 高危规则由系统预设、用户可改，判定方向是「必须人工看一眼」，不产生放行语义。
 *
 * 匹配方式：
 * - [HighRiskMatchType.PREFIX]：命令按顶层段拆分后，任一段命中 token 前缀（复用 [ShellCommandParser.matches]，
 *   如 `rm -rf` 命中 `rm -rf /data` 但不命中 `rm -rfd`）；
 * - [HighRiskMatchType.REGEX]：正则作用整行命令，且对每个顶层段（join 回原样）也各试一次，
 *   使 `^`/`$` 锚点在段级同样可用（如 `curl … | sh` 管道、写块设备）。
 */
@Serializable
data class HighRiskRule(
    /** 稳定 id：预设规则以 `preset.` 开头，用户自建规则用时间戳生成。 */
    val id: String,
    val pattern: String,
    val matchType: HighRiskMatchType = HighRiskMatchType.PREFIX,
    /** 分类名（UI 分组徽标），如「删除与擦除」。 */
    val category: String,
    /** 人类可读的命中说明，弹窗与列表中展示。 */
    val description: String = "",
    val enabled: Boolean = true,
    /** 是否系统预设：预设可「恢复默认」找回；用户删除预设后再恢复会回到启用态。 */
    val builtin: Boolean = true
)

enum class HighRiskMatchType { PREFIX, REGEX }

/** 高危规则匹配：返回第一条命中的启用规则，无命中返回 null。 */
object HighRiskRuleMatcher {

    fun matchAny(rules: List<HighRiskRule>, command: String): HighRiskRule? {
        val analysis = ShellCommandParser.analyze(command)
        for (rule in rules) {
            if (!rule.enabled) continue
            when (rule.matchType) {
                HighRiskMatchType.PREFIX -> {
                    if (analysis.segments.any { ShellCommandParser.matches(rule.pattern, it) }) return rule
                }
                HighRiskMatchType.REGEX -> {
                    val regex = runCatching { Regex(rule.pattern) }.getOrNull() ?: continue
                    if (regex.containsMatchIn(command)) return rule
                    if (analysis.segments.any { regex.containsMatchIn(it.joinToString(" ")) }) return rule
                }
            }
        }
        return null
    }
}
