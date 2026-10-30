package com.aicode.feature.agent.domain.permission

/** Two-line core protocol used by the isolated command reviewer. */
internal object CommandReviewProtocol {
    enum class Verdict { ALLOW, DENY, UNSURE }

    data class Decision(val verdict: Verdict, val reason: String)

    fun parse(content: String): Decision? {
        val lines = content.trim().lines().map(String::trim)
        // Models occasionally wrap the two protocol lines in a short markdown fence or
        // preamble. Keep the machine fields strict, but do not turn harmless formatting
        // into UNSURE and an unnecessary human prompt.
        val verdictLine = lines.firstOrNull { it.startsWith("VERDICT:", ignoreCase = true) } ?: return null
        val reasonLine = lines.firstOrNull { it.startsWith("REASON:", ignoreCase = true) } ?: return null

        val verdict = when (verdictLine.substringAfter(':').trim().uppercase()) {
            "ALLOW" -> Verdict.ALLOW
            "DENY" -> Verdict.DENY
            "UNSURE" -> Verdict.UNSURE
            else -> return null
        }
        val reason = reasonLine.substringAfter(':').trim().takeIf(String::isNotEmpty)?.take(600)
            ?: return null
        return Decision(verdict, reason)
    }
}
