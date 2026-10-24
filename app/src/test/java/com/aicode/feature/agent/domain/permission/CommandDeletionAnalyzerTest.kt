package com.aicode.feature.agent.domain.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandDeletionAnalyzerTest {
    private val root = "/workspace/project"

    private fun analyze(command: String) = CommandDeletionAnalyzer.analyze(
        command = command,
        workingDirectory = root,
        workspaceRoot = root
    )

    @Test
    fun ordinaryAndWildcardWorkspaceDeletesRequireApprovalButAreNotHardBlocked() {
        val file = analyze("rm -f build/output.txt")
        assertTrue(file.isDeletion)
        assertNull(file.hardBlockReason)
        assertEquals("build/output.txt", file.targets.single().path)
        assertFalse(file.targets.single().outsideWorkspace)

        val wildcard = analyze("rm -rf *")
        assertTrue(wildcard.isDeletion)
        assertNull(wildcard.hardBlockReason)
        assertTrue(wildcard.targets.single().wildcard)
    }

    @Test
    fun rootAndWorkspaceRootTargetsAreNeverApprovalEligible() {
        assertNotNull(analyze("rm -rf /").hardBlockReason)
        assertNotNull(analyze("rm -rf .").hardBlockReason)
        assertNotNull(analyze("sudo rm -rf /root").hardBlockReason)
        assertNotNull(analyze("rm -rf /home/alice").hardBlockReason)
        assertNotNull(analyze("rm -rf /system/framework").hardBlockReason)
        assertNotNull(analyze("bash -c 'rm -rf /'").hardBlockReason)
    }

    @Test
    fun allDocumentedShellDeletionFamiliesAreRecognized() {
        val commands = listOf(
            "rmdir empty-dir",
            "del /q old.txt",
            "Remove-Item -Path old.txt",
            "find build -name '*.tmp' -delete",
            "git clean -fdx",
            "git rm src/old.kt",
            "rsync -a --delete source/ destination/",
            "unlink old.txt",
            "python -c \"import shutil; shutil.rmtree('/outside')\"",
            "ruby -e \"FileUtils.rm_rf('build/cache')\"",
            "ruby -e \"File.delete('/outside/old.txt')\"",
            "node -e \"require('fs').rmSync('build/cache', {recursive:true})\"",
            "node -e \"require('fs').promises.unlink('old.txt')\"",
            "cat paths.txt | xargs rm -rf",
            "git -C /etc clean -fdx"
        )
        commands.forEach { command -> assertTrue("not recognized: $command", analyze(command).isDeletion) }
    }

    @Test
    fun dynamicTargetsAreMarkedUncertainAndExplicitProtectedPathStillHardBlocks() {
        val dynamic = analyze("rm -rf \$(find . -name '*.tmp')")
        assertTrue(dynamic.isDeletion)
        assertFalse(dynamic.parseCertain)
        assertTrue(dynamic.riskLabels.isNotEmpty())
        assertNotNull(analyze("echo \$(rm -rf /)").hardBlockReason)
        assertNotNull(analyze("python -c \"target='/'; shutil.rmtree(target)\"").hardBlockReason)
    }

    @Test
    fun blankWorkspaceDoesNotTreatFilesystemRootAsTheProject() {
        val result = CommandDeletionAnalyzer.analyze("rm -f build/tmp", "", "")
        assertTrue(result.isDeletion)
        assertTrue(result.targets.single().outsideWorkspace)
    }
}
