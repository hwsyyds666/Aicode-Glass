package com.aicode.feature.agent.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunningToolFlowSelectorTest {

    private val sessionId = MutableStateFlow<String?>("session-a")
    private val runningTools = MutableStateFlow<Map<String, Map<String, RunningToolOutput>>>(emptyMap())
    private val selector = RunningToolFlowSelector(sessionId, runningTools)

    @Test
    fun outputFor_selectsOnlyTheRequestedTool() = runTest {
        runningTools.value = tools(
            "tool-one" to RunningToolOutput("tool-one", "first", "readFile"),
            "tool-two" to RunningToolOutput("tool-two", "second", "searchCode"),
        )

        assertEquals("first", selector.outputFor("tool-one").first())
        assertEquals("second", selector.outputFor("tool-two").first())
        assertNull(selector.outputFor("missing").first())
    }

    @Test
    fun activeToolName_tracksTheCurrentSessionOnly() = runTest {
        runningTools.value = mapOf(
            "session-a" to mapOf("a" to RunningToolOutput("a", "", "readFile")),
            "session-b" to mapOf("b" to RunningToolOutput("b", "", "writeFile")),
        )

        assertEquals("readFile", selector.activeToolName.first())
        sessionId.value = "session-b"
        assertEquals("writeFile", selector.activeToolName.first())
    }

    @Test
    fun anyRunning_ignoresProgressFromToolsOutsideTheGroup() = runTest {
        runningTools.value = tools("other" to RunningToolOutput("other", "progress", "searchCode"))

        assertFalse(selector.anyRunning(setOf("target")).first())
        runningTools.value = tools("target" to RunningToolOutput("target", "progress", "readFile"))
        assertTrue(selector.anyRunning(setOf("target")).first())
    }

    private fun tools(vararg entries: Pair<String, RunningToolOutput>) =
        mapOf("session-a" to mapOf(*entries))
}
