package com.aicode.testutil

import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * 测试用的 [FileAccessProvider]：把传入路径直接当本地文件系统路径处理。
 * 覆盖技能/子代理目录扫描与写回、附件写入所需的能力。
 */
class TestFileAccessProvider : FileAccessProvider {

    override suspend fun readFile(path: String): String {
        val file = File(path)
        if (!file.exists()) throw NoSuchFileException(file)
        return file.readText()
    }

    override fun readLines(path: String): Flow<String> {
        val file = File(path)
        if (!file.exists()) throw NoSuchFileException(file)
        return flow { emitAll(file.bufferedReader().readLines().asFlow()) }
    }

    override suspend fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) {
        val file = File(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        file.parentFile?.mkdirs()
        file.writeText(content, encoding)
    }

    override suspend fun exists(path: String): Boolean = File(path).exists()

    override suspend fun isDirectory(path: String): Boolean = File(path).isDirectory

    override suspend fun isFile(path: String): Boolean = File(path).isFile

    override suspend fun fileSize(path: String): Long = File(path).length()

    override suspend fun lastModified(path: String): Long = File(path).lastModified()

    override suspend fun permissions(path: String): String = "rwx"

    override suspend fun listFiles(path: String): List<FileEntry> =
        File(path).listFiles().orEmpty().map { child ->
            FileEntry(
                name = child.name,
                isDirectory = child.isDirectory,
                size = child.length(),
                lastModified = child.lastModified(),
                localFile = child
            )
        }

    override suspend fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        val dir = File(path)
        if (!dir.isDirectory) return emptyList()
        return dir.walkTopDown().maxDepth(maxDepth)
            .filter { it.isFile }
            .map { it.relativeTo(dir).invariantSeparatorsPath }
            .toList()
    }

    override suspend fun readBytes(path: String): ByteArray = File(path).readBytes()

    override suspend fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
        val file = File(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(bytes) }
    }

    override suspend fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        val file = File(path)
        if (file.exists() && !overwrite) throw FileAlreadyExistsException(file)
        file.parentFile?.mkdirs()
        return FileOutputStream(file).use { out -> input.copyTo(out) }
    }

    override suspend fun copyToLocal(path: String): File = File(path)

    override suspend fun delete(path: String) {
        File(path).delete()
    }

    override suspend fun deleteRecursively(path: String) {
        File(path).deleteRecursively()
    }

    override suspend fun rename(path: String, newPath: String) {
        File(path).renameTo(File(newPath))
    }

    override suspend fun copy(path: String, newPath: String, overwrite: Boolean) {
        val src = File(path)
        if (!src.exists()) throw NoSuchFileException(src)
        val dst = File(newPath)
        if (dst.exists() && !overwrite) throw FileAlreadyExistsException(dst)
        if (src.isDirectory) {
            src.copyRecursively(dst, overwrite = true)
        } else {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        }
    }

    override suspend fun move(path: String, newPath: String, overwrite: Boolean) {
        val src = File(path)
        if (!src.exists()) throw NoSuchFileException(src)
        val dst = File(newPath)
        if (dst.exists() && !overwrite) throw FileAlreadyExistsException(dst)
        dst.parentFile?.mkdirs()
        if (!src.renameTo(dst)) {
            copy(path, newPath, overwrite = true)
            src.deleteRecursively()
        }
    }

    override suspend fun mkdirs(path: String) {
        File(path).mkdirs()
    }

    override suspend fun parentPath(path: String): String? = File(path).parentFile?.path

    override suspend fun toDisplayPath(path: String): String = path
}
