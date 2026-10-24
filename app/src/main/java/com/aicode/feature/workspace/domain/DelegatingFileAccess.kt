package com.aicode.feature.workspace.domain

import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FileAccessProvider] 的委托层：同时持有本地容器、远程 SFTP 与真机 ROOT 三套实现，
 * 每次方法调用时按 [ExecutionModeHolder.currentMode] 转发到对应实现。
 *
 * 这样 Hilt 注入时机不再影响最终行为——无论 [FileAccessProvider] 在何时被首次注入，
 * 真正读写文件时才读取当前模式。
 */
@Singleton
class DelegatingFileAccess @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val localFileAccess: LocalFileAccess,
    private val remoteSftpFileAccess: RemoteSftpFileAccess,
    private val deviceRootFileAccess: DeviceRootFileAccess
) : FileAccessProvider {

    private fun delegate(): FileAccessProvider = when (modeHolder.currentMode()) {
        ExecutionMode.REMOTE_SSH -> remoteSftpFileAccess
        ExecutionMode.DEVICE_ROOT -> deviceRootFileAccess
        ExecutionMode.LOCAL_PROOT -> localFileAccess
    }

    override suspend fun readFile(path: String): String = delegate().readFile(path)

    override fun readLines(path: String): Flow<String> = delegate().readLines(path)

    override suspend fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        delegate().writeFile(path, content, overwrite, encoding)

    override suspend fun exists(path: String): Boolean = delegate().exists(path)

    override suspend fun isDirectory(path: String): Boolean = delegate().isDirectory(path)

    override suspend fun isFile(path: String): Boolean = delegate().isFile(path)

    override suspend fun fileSize(path: String): Long = delegate().fileSize(path)

    override suspend fun lastModified(path: String): Long = delegate().lastModified(path)

    override suspend fun permissions(path: String): String = delegate().permissions(path)

    override suspend fun listFiles(path: String): List<FileEntry> = delegate().listFiles(path)

    override suspend fun listFilesRecursive(path: String, maxDepth: Int): List<String> =
        delegate().listFilesRecursive(path, maxDepth)

    override suspend fun readFiles(paths: List<String>): Map<String, String> = delegate().readFiles(paths)

    override suspend fun readFileHeads(paths: List<String>, maxBytes: Int): Map<String, String> =
        delegate().readFileHeads(paths, maxBytes)

    override suspend fun readBytes(path: String): ByteArray = delegate().readBytes(path)

    override suspend fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) =
        delegate().writeBytes(path, bytes, overwrite)

    override suspend fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long =
        delegate().writeStream(path, input, overwrite)

    override suspend fun copyToLocal(path: String): File = delegate().copyToLocal(path)

    override suspend fun sha256(path: String, maxBytes: Long): String? = delegate().sha256(path, maxBytes)

    override suspend fun delete(path: String) = delegate().delete(path)

    override suspend fun deleteRecursively(path: String) = delegate().deleteRecursively(path)

    override suspend fun rename(path: String, newPath: String) = delegate().rename(path, newPath)

    override suspend fun copy(path: String, newPath: String, overwrite: Boolean) = delegate().copy(path, newPath, overwrite)

    override suspend fun move(path: String, newPath: String, overwrite: Boolean) = delegate().move(path, newPath, overwrite)

    override suspend fun mkdirs(path: String) = delegate().mkdirs(path)

    override suspend fun parentPath(path: String): String? = delegate().parentPath(path)

    override suspend fun toDisplayPath(path: String): String = delegate().toDisplayPath(path)
}
