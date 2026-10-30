package com.aicode.feature.workspace.domain

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.io.FileAlreadyExistsException
import kotlin.io.NoSuchFileException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

/**
 * LocalFileAccess：以真实临时目录（TemporaryFolder）为宿主的 java.io.File 文件操作。
 * pathMapper 是 mockk：toHostFile 一律映射到 tmp 根下（路径字符串原样拼进临时目录），
 * toContainerPath 原样返回，聚焦文件行为本身。
 */
class LocalFileAccessTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val pathMapper: WorkspacePathMapper = mockk()

    private fun newAccess(): LocalFileAccess {
        every { pathMapper.toHostFile(any()) } answers { File(tmp.root, firstArg<String>()) }
        every { pathMapper.toContainerPath(any()) } answers { firstArg<String>() }
        return LocalFileAccess(pathMapper)
    }

    // ---------- readFile / writeFile ----------

    @Test
    fun writeFile_createsFileAndReadBack() = runBlocking {
        newAccess().writeFile("a.txt", "hello", overwrite = true)
        assertEquals("hello", File(tmp.root, "a.txt").readText())
        assertEquals("hello", newAccess().readFile("a.txt"))
    }

    @Test
    fun writeFile_usesProvidedEncoding() = runBlocking {
        newAccess().writeFile("cn.txt", "中文内容", overwrite = true, encoding = Charsets.UTF_8)
        assertEquals("中文内容", newAccess().readFile("cn.txt"))
    }

    @Test
    fun writeFile_overwriteTrue_replacesExisting() = runBlocking {
        newAccess().writeFile("a.txt", "v1", overwrite = true)
        newAccess().writeFile("a.txt", "v2", overwrite = true)
        assertEquals("v2", newAccess().readFile("a.txt"))
    }

    @Test
    fun writeFile_overwriteFalse_existingFile_throws() = runBlocking<Unit> {
        newAccess().writeFile("a.txt", "v1", overwrite = true)
        assertThrows(FileAlreadyExistsException::class.java) {
            runBlocking { newAccess().writeFile("a.txt", "v2", overwrite = false) }
        }
    }

    @Test
    fun writeFile_createsParentDirectories() = runBlocking {
        newAccess().writeFile("sub/dir/a.txt", "deep", overwrite = true)
        assertTrue(File(tmp.root, "sub/dir/a.txt").isFile)
    }

    @Test
    fun readFile_missingFile_throws() = runBlocking<Unit> {
        assertThrows(NoSuchFileException::class.java) {
            runBlocking { newAccess().readFile("missing.txt") }
        }
    }

    // ---------- readLines ----------

    @Test
    fun readLines_iteratesAllLines_includingEmpty() = runBlocking {
        newAccess().writeFile("lines.txt", "line1\n\nline3\n", overwrite = true)
        assertEquals(listOf("line1", "", "line3"), newAccess().readLines("lines.txt").toList())
    }

    @Test
    fun readLines_sequenceCanIterateMultipleTimes() = runBlocking {
        // 实现是惰性 sequence（每次迭代重新打开 reader），验证可重复迭代
        newAccess().writeFile("lines.txt", "a\nb\n", overwrite = true)
        val seq = newAccess().readLines("lines.txt")
        assertEquals(listOf("a", "b"), seq.toList())
        assertEquals(listOf("a", "b"), seq.toList())
    }

    @Test
    fun readLines_missingFile_throws() = runBlocking<Unit> {
        assertThrows(NoSuchFileException::class.java) {
            runBlocking { newAccess().readLines("missing.txt") }
        }
    }

    // ---------- 元数据：exists / isDirectory / isFile / fileSize / lastModified ----------

    @Test
    fun exists_isDirectory_isFile() = runBlocking {
        val access = newAccess()
        assertFalse(access.exists("nope"))
        access.writeFile("f.txt", "x", overwrite = true)
        assertTrue(access.exists("f.txt"))
        assertTrue(access.isFile("f.txt"))
        assertFalse(access.isDirectory("f.txt"))
        assertTrue(access.isDirectory("."))
        assertFalse(access.isFile("."))
    }

    @Test
    fun fileSizeAndLastModified_reflectRealFile() = runBlocking {
        val access = newAccess()
        access.writeFile("f.txt", "12345", overwrite = true)
        assertEquals(5L, access.fileSize("f.txt"))
        assertTrue(access.lastModified("f.txt") > 0)
    }

    // ---------- listFiles / permissions ----------

    @Test
    fun listFiles_returnsFileEntries() = runBlocking {
        val access = newAccess()
        access.writeFile("a.txt", "data", overwrite = true)
        tmp.newFolder("sub")
        tmp.newFile("b.txt")

        val entries = access.listFiles(".")
        assertEquals(3, entries.size)

        val a = entries.first { it.name == "a.txt" }
        assertEquals(4L, a.size)
        assertFalse(a.isDirectory)
        assertNotNull(a.localFile)
        val expectedFilePerm = if (a.localFile?.canExecute() == true) "rwx" else "rw-"
        assertEquals(expectedFilePerm, a.permissions)

        val sub = entries.first { it.name == "sub" }
        assertTrue(sub.isDirectory)
        assertEquals("rwx", sub.permissions)

        val b = entries.first { it.name == "b.txt" }
        assertEquals(0L, b.size)
    }

    @Test
    fun listFiles_missingDir_returnsEmpty() = runBlocking {
        assertEquals(emptyList<FileEntry>(), newAccess().listFiles("missing"))
    }

    @Test
    fun permissions_fileAndDirectory() = runBlocking {
        val access = newAccess()
        access.writeFile("f.txt", "x", overwrite = true)
        val expectedFilePerm = if (File(tmp.root, "f.txt").canExecute()) "rwx" else "rw-"
        assertEquals(expectedFilePerm, access.permissions("f.txt"))
        // 目录恒带 x 位
        assertEquals("rwx", access.permissions("."))
    }

    // ---------- readBytes / writeBytes ----------

    @Test
    fun readBytesAndWriteBytes_roundTrip() = runBlocking {
        val access = newAccess()
        val bytes = byteArrayOf(1, 2, 3, -1, 0)
        access.writeBytes("bin.dat", bytes, overwrite = true)
        assertTrue(bytes.contentEquals(access.readBytes("bin.dat")))
    }

    @Test
    fun writeBytes_overwriteFalse_existing_throws() = runBlocking<Unit> {
        val access = newAccess()
        access.writeBytes("bin.dat", byteArrayOf(1), overwrite = true)
        assertThrows(FileAlreadyExistsException::class.java) {
            runBlocking { access.writeBytes("bin.dat", byteArrayOf(2), overwrite = false) }
        }
    }

    // ---------- delete / rename / copy / move / mkdirs ----------

    @Test
    fun delete_removesFile() = runBlocking {
        val access = newAccess()
        access.writeFile("f.txt", "x", overwrite = true)
        assertTrue(access.exists("f.txt"))
        access.delete("f.txt")
        assertFalse(access.exists("f.txt"))
    }

    @Test
    fun deleteRecursively_removesTree() = runBlocking {
        val access = newAccess()
        access.writeFile("dir/a.txt", "x", overwrite = true)
        access.writeFile("dir/sub/b.txt", "y", overwrite = true)
        access.deleteRecursively("dir")
        assertFalse(access.exists("dir"))
    }

    @Test
    fun deleteRecursively_missing_silent() = runBlocking {
        newAccess().deleteRecursively("missing")
    }

    @Test
    fun rename_movesFile() = runBlocking {
        val access = newAccess()
        access.writeFile("src.txt", "x", overwrite = true)
        access.rename("src.txt", "dst.txt")
        assertFalse(access.exists("src.txt"))
        assertEquals("x", access.readFile("dst.txt"))
    }

    @Test
    fun rename_missingSource_throws() = runBlocking<Unit> {
        assertThrows(NoSuchFileException::class.java) {
            runBlocking { newAccess().rename("missing", "dst.txt") }
        }
    }

    @Test
    fun rename_targetExists_throws() = runBlocking<Unit> {
        val access = newAccess()
        access.writeFile("src.txt", "x", overwrite = true)
        access.writeFile("dst.txt", "y", overwrite = true)
        assertThrows(FileAlreadyExistsException::class.java) {
            runBlocking { access.rename("src.txt", "dst.txt") }
        }
    }

    @Test
    fun copy_fileToNewPath() = runBlocking {
        val access = newAccess()
        access.writeFile("src.txt", "content", overwrite = true)
        access.copy("src.txt", "dst.txt", overwrite = false)
        assertEquals("content", access.readFile("dst.txt"))
        assertEquals("content", access.readFile("src.txt"))
    }

    @Test
    fun copy_missingSource_throws() = runBlocking<Unit> {
        assertThrows(NoSuchFileException::class.java) {
            runBlocking { newAccess().copy("missing", "dst.txt", overwrite = true) }
        }
    }

    @Test
    fun copy_targetExistsWithoutOverwrite_throws() = runBlocking<Unit> {
        val access = newAccess()
        access.writeFile("src.txt", "x", overwrite = true)
        access.writeFile("dst.txt", "y", overwrite = true)
        assertThrows(FileAlreadyExistsException::class.java) {
            runBlocking { access.copy("src.txt", "dst.txt", overwrite = false) }
        }
    }

    @Test
    fun copy_samePathOverwrite_preservesSource() = runBlocking {
        val access = newAccess()
        access.writeFile("src.txt", "content", overwrite = true)
        assertThrows(java.io.IOException::class.java) {
            runBlocking { access.copy("src.txt", "./src.txt", overwrite = true) }
        }
        assertEquals("content", access.readFile("src.txt"))
    }

    @Test
    fun move_samePathOverwrite_preservesSource() = runBlocking {
        val access = newAccess()
        access.writeFile("src.txt", "content", overwrite = true)
        assertThrows(java.io.IOException::class.java) {
            runBlocking { access.move("src.txt", "./src.txt", overwrite = true) }
        }
        assertEquals("content", access.readFile("src.txt"))
    }

    @Test
    fun copy_directoryIntoDescendant_preservesSource() = runBlocking {
        val access = newAccess()
        access.writeFile("dir/file.txt", "content", overwrite = true)
        assertThrows(java.io.IOException::class.java) {
            runBlocking { access.copy("dir", "dir/child", overwrite = true) }
        }
        assertEquals("content", access.readFile("dir/file.txt"))
        assertFalse(access.exists("dir/child"))
    }

    @Test
    fun move_directoryIntoDescendant_preservesSource() = runBlocking {
        val access = newAccess()
        access.writeFile("dir/file.txt", "content", overwrite = true)
        assertThrows(java.io.IOException::class.java) {
            runBlocking { access.move("dir", "dir/child", overwrite = true) }
        }
        assertEquals("content", access.readFile("dir/file.txt"))
        assertFalse(access.exists("dir/child"))
    }

    @Test
    fun copy_directoryToSiblingWithSharedPrefix_succeeds() = runBlocking {
        val access = newAccess()
        access.writeFile("dir/file.txt", "content", overwrite = true)
        access.copy("dir", "directory", overwrite = false)
        assertEquals("content", access.readFile("directory/file.txt"))
    }

    @Test
    fun move_file() = runBlocking {
        val access = newAccess()
        access.writeFile("src.txt", "x", overwrite = true)
        access.move("src.txt", "dst.txt", overwrite = false)
        assertFalse(access.exists("src.txt"))
        assertEquals("x", access.readFile("dst.txt"))
    }

    @Test
    fun mkdirs_createsNestedDirectories() = runBlocking {
        newAccess().mkdirs("a/b/c")
        assertTrue(File(tmp.root, "a/b/c").isDirectory)
    }

    // ---------- 路径回显 ----------

    @Test
    fun parentPath_returnsContainerPathOfParent() = runBlocking {
        val access = newAccess()
        assertEquals(tmp.root.absolutePath, access.parentPath("child.txt"))
    }

    @Test
    fun toDisplayPath_returnsContainerPath() = runBlocking {
        val access = newAccess()
        assertEquals(File(tmp.root, "f.txt").absolutePath, access.toDisplayPath("f.txt"))
    }

    @Test
    fun copyToLocal_returnsHostFile() = runBlocking {
        val access = newAccess()
        access.writeFile("f.txt", "x", overwrite = true)
        assertEquals(File(tmp.root, "f.txt"), access.copyToLocal("f.txt"))
    }
}
