package com.aicode.feature.agent.domain.checkpoint

/** Resource bound for the temporary Git index used by whole-worktree undo snapshots. */
object GitTreeSnapshotPolicy {
    const val MAX_FILE_BYTES = 50L * 1024 * 1024
    private const val VIRTUAL_WORKSPACE = "/root/workspace"
    private const val VIRTUAL_WORKSPACE_TILDE = "~/workspace"

    fun exceedsFileLimit(sizeBytes: Long): Boolean = sizeBytes > MAX_FILE_BYTES

    /** Convert a file-tool path to a repository-relative path for checking membership in a saved Git tree. */
    fun repositoryRelativePath(filePath: String, workspacePath: String): String? {
        val path = filePath.trim().replace('\\', '/').trimEnd('/')
        val rawRoot = workspacePath.trim().replace('\\', '/').trimEnd('/')
        if (path.isBlank() || rawRoot.isBlank()) return null
        val root = when (rawRoot) {
            VIRTUAL_WORKSPACE_TILDE -> VIRTUAL_WORKSPACE
            else -> rawRoot
        }
        val relative = when {
            path == VIRTUAL_WORKSPACE_TILDE || path == VIRTUAL_WORKSPACE -> ""
            path.startsWith("$VIRTUAL_WORKSPACE_TILDE/") -> path.removePrefix("$VIRTUAL_WORKSPACE_TILDE/")
            path.startsWith("$VIRTUAL_WORKSPACE/") -> path.removePrefix("$VIRTUAL_WORKSPACE/")
            path == root -> ""
            path.startsWith("$root/") -> path.removePrefix("$root/")
            path.startsWith('/') || path.startsWith('~') -> return null
            else -> path
        }
        if (relative.isBlank() || relative.split('/').any { it == ".." || it.isBlank() }) return null
        return relative.split('/').filter { it != "." }.joinToString("/").takeIf(String::isNotBlank)
    }
}
