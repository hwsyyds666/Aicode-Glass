package com.aicode.feature.workspace.data.provider

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class ExposedWorkspaceDocumentPathsTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun exposesOnlyProjectsAndAicodeAndAllowsFilesDirAsNavigationRoot() {
        val base = temp.newFolder("files")
        val projects = File(base, "projects").apply { mkdirs() }
        val projectFile = File(projects, "src/Main.kt").apply { parentFile?.mkdirs(); writeText("source") }
        val aicode = File(base, "aicode").apply { mkdirs() }
        val skill = File(aicode, "skills/review/SKILL.md").apply {
            parentFile?.mkdirs()
            writeText("skill")
        }
        val internal = File(base, "databases/private.db").apply { parentFile?.mkdirs(); writeText("db") }

        assertTrue(isExposedWorkspaceDocument(base, base))
        assertTrue(isExposedWorkspaceRoot(base, base))
        assertTrue(isExposedWorkspaceRoot(base, projects))
        assertTrue(isExposedWorkspaceRoot(base, aicode))
        assertFalse(isExposedWorkspaceRoot(base, projectFile))
        assertTrue(isExposedWorkspaceDocument(base, projectFile))
        assertTrue(isExposedWorkspaceDocument(base, skill))
        assertFalse(isExposedWorkspaceDocument(base, internal))
        assertFalse(isExposedWorkspaceDocument(base, File(base.parentFile, "outside.txt")))
    }

    @Test
    fun canonicalTraversalCannotEscapeAnExposedDirectory() {
        val base = temp.newFolder("files")
        val projects = File(base, "projects").apply { mkdirs() }
        val internal = File(base, "rootfs/etc/passwd").apply {
            parentFile?.mkdirs()
            writeText("secret")
        }
        val traversal = File(projects, "../../rootfs/etc/passwd")

        assertTrue(internal.exists())
        assertFalse(isExposedWorkspaceDocument(base, traversal))
    }

    @Test
    fun symlinkToInternalDataIsNotExposedWhenPlatformAllowsSymlinks() {
        val base = temp.newFolder("files")
        val projects = File(base, "projects").apply { mkdirs() }
        val internal = File(base, "databases/private.db").apply {
            parentFile?.mkdirs()
            writeText("db")
        }
        val link = File(projects, "leak.db")
        try {
            Files.createSymbolicLink(link.toPath(), internal.toPath())
        } catch (_: Exception) {
            // Windows environments without symlink privileges still exercise path traversal above.
            return
        }

        assertFalse(isExposedWorkspaceDocument(base, link))

        val directoryLink = File(projects, "external")
        try {
            Files.createSymbolicLink(directoryLink.toPath(), requireNotNull(internal.parentFile).toPath())
        } catch (_: Exception) {
            return
        }
        assertFalse(
            "a not-yet-created child under a linked directory must stay outside the provider",
            isExposedWorkspaceDocument(base, File(directoryLink, "new-file.txt")),
        )
    }
}
