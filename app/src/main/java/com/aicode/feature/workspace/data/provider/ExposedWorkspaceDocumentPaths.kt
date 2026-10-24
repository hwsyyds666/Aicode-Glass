package com.aicode.feature.workspace.data.provider

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The DocumentsProvider exposes only filesDir/projects and filesDir/aicode (plus filesDir as the
 * navigational root). Resolve canonical paths so docIds or symlinks cannot escape those roots.
 */
internal fun isExposedWorkspaceDocument(baseDir: File, candidate: File): Boolean = runCatching {
    val base = resolveWorkspacePath(baseDir) ?: return@runCatching false
    val path = resolveWorkspacePath(candidate) ?: return@runCatching false
    if (path == base) return@runCatching true

    listOf("projects", "aicode").any { name ->
        val root = resolveWorkspacePath(File(baseDir, name)) ?: return@any false
        // A replaced exposed directory must not turn the whitelist into an alias for filesDir or
        // another location on disk.
        if (!isStrictChildPath(base, root)) return@any false
        path == root || isStrictChildPath(root, path)
    }
}.getOrDefault(false)

internal fun isExposedWorkspaceRoot(baseDir: File, candidate: File): Boolean = runCatching {
    val path = resolveWorkspacePath(candidate) ?: return@runCatching false
    path == resolveWorkspacePath(baseDir) || listOf("projects", "aicode")
        .any { path == resolveWorkspacePath(File(baseDir, it)) }
}.getOrDefault(false)

private fun resolveWorkspacePath(file: File): String? = runCatching {
    val absolute = file.toPath().toAbsolutePath().normalize()
    var existing: Path = absolute
    val missingSegments = mutableListOf<Path>()

    // toRealPath follows symlinks. For a not-yet-created file (e.g. createDocument), resolve the
    // nearest existing ancestor first, then append the missing suffix so linked parents cannot
    // redirect creation outside the allowed tree.
    while (!Files.exists(existing)) {
        if (Files.isSymbolicLink(existing)) return@runCatching null
        val name = existing.fileName ?: return@runCatching null
        missingSegments.add(0, name)
        existing = existing.parent ?: return@runCatching null
    }

    var resolved = existing.toRealPath()
    missingSegments.forEach { resolved = resolved.resolve(it) }
    resolved.normalize().toString()
}.getOrNull()

internal fun isStrictChildPath(parentPath: String, childPath: String): Boolean {
    val parent = parentPath.trimEnd(File.separatorChar)
    return childPath.startsWith(parent + File.separator)
}
