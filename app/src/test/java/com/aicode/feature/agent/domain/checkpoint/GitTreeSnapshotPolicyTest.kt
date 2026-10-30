package com.aicode.feature.agent.domain.checkpoint

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitTreeSnapshotPolicyTest {
    @Test
    fun acceptsFilesAtTheLimitAndRejectsLargerFiles() {
        assertFalse(GitTreeSnapshotPolicy.exceedsFileLimit(0))
        assertFalse(GitTreeSnapshotPolicy.exceedsFileLimit(GitTreeSnapshotPolicy.MAX_FILE_BYTES))
        assertTrue(GitTreeSnapshotPolicy.exceedsFileLimit(GitTreeSnapshotPolicy.MAX_FILE_BYTES + 1))
    }

    @Test
    fun mapsVirtualAndAbsoluteWorkspacePathsToRepositoryPaths() {
        val root = "/data/user/0/com.aicode/files/projects/demo"
        assertEquals("app/src/Main.kt", GitTreeSnapshotPolicy.repositoryRelativePath("~/workspace/app/src/Main.kt", root))
        assertEquals("app/src/Main.kt", GitTreeSnapshotPolicy.repositoryRelativePath("/root/workspace/app/src/Main.kt", root))
        assertEquals("app/src/Main.kt", GitTreeSnapshotPolicy.repositoryRelativePath("$root/app/src/Main.kt", root))
        assertEquals("app/src/Main.kt", GitTreeSnapshotPolicy.repositoryRelativePath("app/src/Main.kt", root))
        assertNull(GitTreeSnapshotPolicy.repositoryRelativePath("/etc/passwd", root))
        assertNull(GitTreeSnapshotPolicy.repositoryRelativePath("~/workspace/../outside", root))
    }
}
