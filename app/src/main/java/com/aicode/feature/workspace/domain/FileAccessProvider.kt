package com.aicode.feature.workspace.domain

import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import kotlinx.coroutines.flow.Flow

/** 目录条目信息，供 [FileAccessProvider.listFiles] 返回。 */
data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    /** 文件大小（字节）；目录可为 0。 */
    val size: Long,
    /** 最后修改时间（epoch 毫秒）。 */
    val lastModified: Long,
    /** 本地模式下可提供宿主 [File]，供需要本地路径的调用方（如 [BitmapFactory]）使用；远程模式为 null。 */
    val localFile: File? = null,
    /** 可读/可写/可执行权限位（rwx 字符串，如 "rwx"）；远程按 SFTP 权限解析，无法获取时给合理默认。 */
    val permissions: String = "---"
)

/**
 * 文件读写后端抽象：把"在哪读写文件"从硬编码的本地 java.io.File 解耦。
 *
 * 两套实现：
 * - `LocalFileAccess`：包一层 [WorkspacePathMapper]，走 java.io.File 直读（本地模式原行为不变）；
 * - `RemoteSftpFileAccess`：用 SFTP 读写远程文件。
 *
 * 路径入参统一为 AI 给的"容器路径"（`~/workspace/...`），由实现内部映射到本地宿主路径或远程路径。
 * 工具层（FileTools/ImageTools/ListFilesTool/EditFileTool）依赖本接口而非具体实现，
 * 由 DI 按当前执行模式注入对应实例。
 *
 * **全挂起接口**：远程实现的每次读写都要过 SFTP 互斥锁与网络往返，同步阻塞会把调用线程
 * 卡住（工具批次并行执行时还会借占 IO 线程池，造成线程饥饿甚至 ANR）。因此所有方法都是
 * suspend 的，实现内部自行切 IO 调度；调用方一律在协程里使用，不要用 runBlocking 包。
 * 需要本地文件路径的能力（如 [android.graphics.BitmapFactory.decodeFile]）用 [readBytes] 或
 * [copyToLocal] 拿到本地临时文件再处理——远程模式下会 SFTP 下载到临时目录。
 */
interface FileAccessProvider {

    /**
     * 读取文件全文文本。文件不存在时抛 [NoSuchFileException]。
     * 远程模式对超过实现上限的文件抛 [RemoteOutputTooLargeException]，避免整篇读进内存。
     */
    suspend fun readFile(path: String): String

    /**
     * 惰性逐行读取文件，供 [ReadFileTool] 按行窗口读取：读到哪算哪，整文件不进内存；
     * 单行超过上限（默认 64K 字符）会被截断。
     * 冷 Flow：每次收集才重新打开底层读取，收集结束或抛错时自动关闭；可取消。
     * 文件不存在时抛 [NoSuchFileException]。
     */
    fun readLines(path: String): Flow<String>

    /**
     * 写入文件全文。父目录不存在则自动创建。[overwrite] 为 false 且文件已存在时抛 [FileAlreadyExistsException]。
     * [encoding] 指定落盘字符编码；缺省 UTF-8。调用方应把「读入时检测到的编码」原样回传，
     * 避免 GBK 等非 UTF-8 文件在保存时被静默转码破坏。
     */
    suspend fun writeFile(path: String, content: String, overwrite: Boolean = true, encoding: Charset = Charsets.UTF_8)

    /** 文件是否存在。 */
    suspend fun exists(path: String): Boolean

    /** 文件是否为目录。 */
    suspend fun isDirectory(path: String): Boolean

    /** 文件是否为普通文件。 */
    suspend fun isFile(path: String): Boolean

    /** 文件大小（字节）。 */
    suspend fun fileSize(path: String): Long

    /** 最后修改时间（epoch 毫秒）。 */
    suspend fun lastModified(path: String): Long

    /** 权限位字符串（如 "rwx"）。 */
    suspend fun permissions(path: String): String

    /**
     * 列出目录下的条目。本地实现用 [File.listFiles]；远程实现用 SFTP ls。
     * 不含 `.` 和 `..`。返回的 [FileEntry.localFile] 在远程模式下为 null。
     */
    suspend fun listFiles(path: String): List<FileEntry>

    /**
     * Lightweight directory listing for the file tree. Implementations may omit metadata that
     * is only needed by the file listing tool, keeping expansion responsive on slower storage.
     */
    suspend fun listFilesForBrowse(path: String): List<FileEntry> = listFiles(path)

    /**
     * 递归列出 [path] 下所有普通文件，深度不超过 [maxDepth]（[path] 的直接子项算第 1 层）。
     * 返回相对 [path] 的路径（`/` 分隔）；目录不存在时返回空列表。
     * 供技能/子代理目录扫描使用。
     */
    suspend fun listFilesRecursive(path: String, maxDepth: Int): List<String>

    /**
     * 批量读取多个文件全文，返回 `path -> content`；单个文件读取失败时该条目缺席（不抛）。
     *
     * 默认实现逐个 [readFile]。真机 ROOT / SFTP 这类「一次调用 = 一次跨进程或跨网络往返」
     * 的通道**必须**覆写为一次往返批量取回：技能扫描、子代理扫描会一次读上百个文件，
     * 逐个读会退化成上百次子进程 fork（每个数十~数百毫秒），直接把调用方拖到 ANR。
     */
    suspend fun readFiles(paths: List<String>): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(paths.size)
        for (p in paths) runCatching { readFile(p) }.onSuccess { out[p] = it }
        return out
    }

    /**
     * 批量读取多个文件的前 [maxBytes] 个字符（按字符截断，不保证 UTF-8 边界对齐——
     * 仅用于解析 frontmatter 等只关心文件头的场景）。
     *
     * 默认实现 = [readFiles] 后逐条截断；真机 ROOT / SFTP 这类「一次调用 = 一次跨进程或跨网络往返」的
     * 通道**必须**覆写为一次往返批量取回：按技能名定位技能要读上百个 SKILL.md 的文件头，
     * 逐个读会退化成上百次子进程 fork（每个数十~数百毫秒），直接把 loadSkill 拖到 ANR。
     * 与 [readFiles] 同一套返回约定：读取失败的条目缺席，不抛。
     */
    suspend fun readFileHeads(paths: List<String>, maxBytes: Int): Map<String, String> {
        if (paths.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(paths.size)
        for (p in paths) runCatching { readFile(p) }.onSuccess { out[p] = it.take(maxBytes) }
        return out
    }

    /**
     * 读取文件原始字节。供 [ViewImageTool] 等需要二进制数据的工具使用。
     * 远程模式下若调用方需要本地文件路径，改用 [copyToLocal]；文件过大时抛 [RemoteOutputTooLargeException]。
     */
    suspend fun readBytes(path: String): ByteArray

    /**
     * 写入文件原始字节。父目录不存在则自动创建。[overwrite] 为 false 且文件已存在时抛 [FileAlreadyExistsException]。
     * 供 [GenerateImageTool] 等需要落盘二进制数据（图片等）的工具使用，也用于上传附件等二进制写入；
     * 文本内容仍用 [writeFile]。
     */
    suspend fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean = true)

    /**
     * 从 [input] 流式写入文件，返回写入的字节数；内容不整体驻留内存。
     * 父目录不存在则自动创建。[overwrite] 为 false 且文件已存在时抛 [FileAlreadyExistsException]。
     * 供上传大附件等场景使用。
     */
    suspend fun writeStream(path: String, input: InputStream, overwrite: Boolean = true): Long

    /**
     * 把文件复制到本地临时文件并返回其 [File]。
     * 本地模式直接返回映射后的宿主 [File]（不复制）；
     * 远程模式 SFTP 下载到缓存目录，返回本地临时文件。
     * 供 [ViewImageTool]（需本地路径喂 BitmapFactory）等使用。
     */
    suspend fun copyToLocal(path: String): File

    /** Hash a bounded regular file. Backend overrides should stream where possible. */
    suspend fun sha256(path: String, maxBytes: Long): String? {
        if (!exists(path) || !isFile(path)) return null
        val size = fileSize(path)
        if (size < 0L || size > maxBytes) return null
        val bytes = readBytes(path)
        if (bytes.size.toLong() != size || bytes.size.toLong() > maxBytes) return null
        return bytes.sha256Hex()
    }

    /** 删除文件或空目录。 */
    suspend fun delete(path: String)

    /** 递归删除文件或目录（目录非空时连同内容一起删）。目标不存在时静默返回。 */
    suspend fun deleteRecursively(path: String)

    /**
     * 重命名 / 移动。[newPath] 已存在时抛 [FileAlreadyExistsException]，
     * [path] 不存在时抛 [NoSuchFileException]，其它失败抛 [java.io.IOException]。
     */
    suspend fun rename(path: String, newPath: String)

    /**
     * 复制文件或目录（含子内容）到 [newPath]。[path] 不存在时抛 [NoSuchFileException]，
     * 其它失败抛 [java.io.IOException]。[overwrite] 为 false 且 [newPath] 已存在时抛
     * [FileAlreadyExistsException]；为 true 时覆盖。
     */
    suspend fun copy(path: String, newPath: String, overwrite: Boolean = false)

    /**
     * 将 [path] 移动（剪切）到 [newPath]。[path] 不存在时抛 [NoSuchFileException]，
     * 其它失败抛 [java.io.IOException]。[overwrite] 为 false 且 [newPath] 已存在时抛
     * [FileAlreadyExistsException]；为 true 时覆盖。
     * 实现可用 rename 或「复制 + 删除源」。
     */
    suspend fun move(path: String, newPath: String, overwrite: Boolean = false)

    /** 创建目录（含父目录）。 */
    suspend fun mkdirs(path: String)

    /** 获取父目录路径（容器路径形式）。 */
    suspend fun parentPath(path: String): String?

    /** 把内部真实路径还原为 AI 视角的容器路径（回显用）。 */
    suspend fun toDisplayPath(path: String): String
}

/**
 * 单个目录条目名是否合法：非空、不含路径分隔符、不是 `.` 或 `..`。
 * 拦掉 `../` 这类会跳出当前目录的输入，供文件浏览的新建/重命名使用。
 */
fun isValidFileEntryName(name: String): Boolean {
    val trimmed = name.trim()
    return trimmed.isNotEmpty() &&
        !trimmed.contains('/') &&
        !trimmed.contains('\\') &&
        trimmed != "." &&
        trimmed != ".."
}
