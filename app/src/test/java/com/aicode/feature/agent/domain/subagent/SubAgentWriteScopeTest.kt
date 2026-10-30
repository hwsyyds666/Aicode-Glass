package com.aicode.feature.agent.domain.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.ToolCapability
import kotlinx.serialization.json.JsonPrimitive

class SubAgentWriteScopeTest {
    private val root = "/workspace/app"

    @Test
    fun parseResolvesRelativePathsAndRejectsEscape() {
        assertEquals(listOf("/workspace/app/src", "/workspace/app/docs"), SubAgentWriteScope.parse("src\ndocs", root).paths)
        assertFalse(SubAgentWriteScope.parse("../outside", root).paths != null)
        assertFalse(SubAgentWriteScope.parse("/etc", root).paths != null)
        assertFalse(SubAgentWriteScope.parse("src/**", root).paths != null)
        assertFalse(SubAgentWriteScope.parse("\${'$'}HOME", root).paths != null)
    }

    @Test
    fun pathBoundaryDoesNotConfuseSiblingPrefixes() {
        val scopes = listOf("/workspace/app/src")
        assertTrue(SubAgentWriteScope.allows(scopes, "src/main/A.kt", root))
        assertTrue(SubAgentWriteScope.allows(scopes, "~/workspace/src/main/A.kt", root))
        assertTrue(SubAgentWriteScope.allows(scopes, "/root/workspace/src/main/A.kt", root))
        assertTrue(SubAgentWriteScope.allows(scopes, "/workspace/app/src/main/A.kt", root))
        assertFalse(SubAgentWriteScope.allows(scopes, "/workspace/app/src2/A.kt", root))
        assertFalse(SubAgentWriteScope.allows(scopes, "~/workspace/src/../../secrets.txt", root))
    }

    @Test
    fun scopesAcceptVirtualPathsButPersistWorkspaceAbsolutePaths() {
        val workspace = "/data/user/0/com.aicode/files/projects/demo"
        assertEquals(
            listOf("$workspace/app/src"),
            SubAgentWriteScope.parse("~/workspace/app/src", workspace).paths
        )
        assertEquals(
            listOf("$workspace/app/src"),
            SubAgentWriteScope.parse("/root/workspace/app/src", workspace).paths
        )
        assertEquals(
            listOf("$workspace/app/src"),
            SubAgentWriteScope.parse("app/src", workspace).paths
        )
        assertFalse(SubAgentWriteScope.parse("/etc", workspace).paths != null)
    }

    @Test
    fun nestedScopesOverlapButSiblingScopesDoNot() {
        assertTrue(SubAgentWriteScope.overlaps(listOf("/workspace/app/src"), listOf("/workspace/app/src/main")))
        assertFalse(SubAgentWriteScope.overlaps(listOf("/workspace/app/src"), listOf("/workspace/app/docs")))
    }

    @Test
    fun guardRejectsOutOfScopeReadsWritesAndCommands() {
        val context = AgentContext(
            currentFile = null,
            selectedCode = null,
            projectRoot = root,
            language = null,
            allowedWritePaths = listOf("/workspace/app/src"),
            allowedReadPaths = listOf("/workspace/app/docs")
        )
        val write = SubAgentWriteScopeGuard.check(
            "writeFile", mapOf("path" to JsonPrimitive("src/Main.kt")), setOf(ToolCapability.WRITE_WORKSPACE), context, root
        )
        assertTrue(write.allowed)
        val virtualWrite = SubAgentWriteScopeGuard.check(
            "writeFile", mapOf("path" to JsonPrimitive("~/workspace/src/Main.kt")), setOf(ToolCapability.WRITE_WORKSPACE), context, root
        )
        assertTrue(virtualWrite.allowed)
        val deniedWrite = SubAgentWriteScopeGuard.check(
            "writeFile", mapOf("path" to JsonPrimitive("docs/guide.md")), setOf(ToolCapability.WRITE_WORKSPACE), context, root
        )
        assertFalse(deniedWrite.allowed)
        val deniedRead = SubAgentWriteScopeGuard.check(
            "readFile", mapOf("path" to JsonPrimitive("README.md")), setOf(ToolCapability.READ_WORKSPACE), context, root
        )
        assertFalse(deniedRead.allowed)
        val deniedCommand = SubAgentWriteScopeGuard.check(
            "Bash", mapOf("command" to JsonPrimitive("rm -rf /workspace/app/docs")), setOf(ToolCapability.EXECUTE_COMMANDS), context, root
        )
        assertFalse(deniedCommand.allowed)
        val allowedRead = SubAgentWriteScopeGuard.check(
            "Bash", mapOf("command" to JsonPrimitive("rg TODO src")), setOf(ToolCapability.EXECUTE_COMMANDS), context, root
        )
        assertTrue(allowedRead.allowed)
        val deniedTraversal = SubAgentWriteScopeGuard.check(
            "Bash", mapOf("command" to JsonPrimitive("cat ../secrets.txt")), setOf(ToolCapability.EXECUTE_COMMANDS), context, root
        )
        assertFalse(deniedTraversal.allowed)
        val deniedRedirect = SubAgentWriteScopeGuard.check(
            "Bash", mapOf("command" to JsonPrimitive("cat src/Main.kt > ../outside.txt")), setOf(ToolCapability.EXECUTE_COMMANDS), context, root
        )
        assertFalse(deniedRedirect.allowed)
        val deniedScript = SubAgentWriteScopeGuard.check(
            "Bash", mapOf("command" to JsonPrimitive("python3 -c 'open(\"/tmp/outside\", \"w\").write(\"x\")'")), setOf(ToolCapability.EXECUTE_COMMANDS), context, root
        )
        assertFalse(deniedScript.allowed)
        val deniedListPath = SubAgentWriteScopeGuard.check(
            "list", mapOf("args" to JsonPrimitive("-la ../secrets")), setOf(ToolCapability.READ_WORKSPACE), context, root
        )
        assertFalse(deniedListPath.allowed)
        val deniedSearchPath = SubAgentWriteScopeGuard.check(
            "search", mapOf("args" to JsonPrimitive("-e TODO /etc")), setOf(ToolCapability.READ_WORKSPACE), context, root
        )
        assertFalse(deniedSearchPath.allowed)
        val deniedAttachmentPath = SubAgentWriteScopeGuard.check(
            "sendFile", mapOf("paths" to kotlinx.serialization.json.buildJsonArray { add(JsonPrimitive("/etc/passwd")) }),
            setOf(ToolCapability.READ_WORKSPACE), context, root
        )
        assertFalse(deniedAttachmentPath.allowed)
        listOf(
            "git diff -- /etc/passwd",
            "rg --files /etc",
            "sort -o /etc/hosts src/Main.kt",
            "grep -f /etc/passwd TODO src"
        ).forEach { command ->
            val decision = SubAgentWriteScopeGuard.check(
                "Bash", mapOf("command" to JsonPrimitive(command)), setOf(ToolCapability.EXECUTE_COMMANDS), context, root
            )
            assertFalse("should reject scoped shell command: $command", decision.allowed)
        }

        val fullWorkspaceWorker = context.copy(allowedWritePaths = listOf(root))
        val allowedTest = SubAgentWriteScopeGuard.check(
            "Bash",
            mapOf(
                "command" to JsonPrimitive("./gradlew :app:testUniversalDebugUnitTest"),
                "env" to JsonPrimitive("container")
            ),
            setOf(ToolCapability.EXECUTE_COMMANDS),
            fullWorkspaceWorker,
            root
        )
        assertTrue(allowedTest.allowed)
        val deniedDeviceTest = SubAgentWriteScopeGuard.check(
            "Bash",
            mapOf(
                "command" to JsonPrimitive("./gradlew :app:testUniversalDebugUnitTest"),
                "env" to JsonPrimitive("device")
            ),
            setOf(ToolCapability.EXECUTE_COMMANDS),
            fullWorkspaceWorker,
            root
        )
        assertFalse(deniedDeviceTest.allowed)
        val deniedBuildPathEscape = SubAgentWriteScopeGuard.check(
            "Bash",
            mapOf(
                "command" to JsonPrimitive("./gradlew -p ../outside :app:testUniversalDebugUnitTest"),
                "env" to JsonPrimitive("container")
            ),
            setOf(ToolCapability.EXECUTE_COMMANDS),
            fullWorkspaceWorker,
            root
        )
        assertFalse(deniedBuildPathEscape.allowed)
    }
}
