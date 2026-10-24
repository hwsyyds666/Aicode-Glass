package com.aicode.feature.agent.presentation.component

import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultWarningTest {
    @Test
    fun snapshotWarningRemainsVisibleBesideSuccessfulToolOutput() {
        val result = formatToolResult(
            """{"status":"success","data":{"content":"file written","warning":"rollback is protected"}}"""
        )

        assertTrue(result.contains("rollback is protected"))
        assertTrue(result.contains("file written"))
    }
}
