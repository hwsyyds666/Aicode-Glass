package com.aicode.feature.agent.domain.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentReworkPolicyTest {
    @Test
    fun onlyTerminalChildrenCanStartAReworkRound() {
        listOf("COMPLETED", "FAILED", "TIMED_OUT", "CANCELLED").forEach {
            assertTrue("$it should permit a bounded follow-up", SubAgentReworkPolicy.isTerminal(it))
        }
        listOf(null, "STARTING", "RUNNING").forEach {
            assertFalse("$it should not count as rework", SubAgentReworkPolicy.isTerminal(it))
        }
    }

    @Test
    fun reworkBudgetNeverExceedsTwoRounds() {
        assertEquals(2, SubAgentReworkPolicy.remaining(0))
        assertEquals(1, SubAgentReworkPolicy.remaining(1))
        assertEquals(0, SubAgentReworkPolicy.remaining(2))
        assertEquals(0, SubAgentReworkPolicy.remaining(9))
    }
}
