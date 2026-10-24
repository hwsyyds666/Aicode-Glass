package com.aicode.feature.agent.domain.permission

/** Strict two-line wire protocol used by the isolated command reviewer. */
internal object CommandReviewProtocol {
    enum class Verdict { ALLOW, DENY, UNSURE }

    data class Decision(val verdict: Verdict, val reason: String)

    fun parse(content: String): Decision? {
        val lines = content.trim().lines().map(String::trim)
        if (lines.size != 2 || !lines[0].startsWith("VERDICT:", ignoreCase = true) ||
            !lines[1].startsWith("REASON:", ignoreCase = true)
        ) return null

        val verdict = when (lines[0].substringAfter(':').trim().uppercase()) {
            "ALLOW" -> Verdict.ALLOW
            "DENY" -> Verdict.DENY
            "UNSURE" -> Verdict.UNSURE
            else -> return null
        }
        val reason = lines[1].substringAfter(':').trim().takeIf(String::isNotEmpty)?.take(600)
            ?: return null
        return Decision(verdict, reason)
    }
}
