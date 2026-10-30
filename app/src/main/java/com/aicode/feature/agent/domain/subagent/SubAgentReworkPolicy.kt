package com.aicode.feature.agent.domain.subagent

/** Runtime-independent policy for bounded follow-up work after a child agent has finished. */
internal object SubAgentReworkPolicy {
    const val MAX_ROUNDS = 2

    fun isTerminal(status: String?): Boolean = status in TERMINAL_STATES

    fun remaining(completedRounds: Int): Int = (MAX_ROUNDS - completedRounds).coerceAtLeast(0)

    private val TERMINAL_STATES = setOf("COMPLETED", "FAILED", "TIMED_OUT", "CANCELLED")
}
