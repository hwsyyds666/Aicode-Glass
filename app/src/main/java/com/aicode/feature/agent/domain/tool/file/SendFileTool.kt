package com.aicode.feature.agent.domain.tool.file

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AbstractContextualTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URLConnection
import javax.inject.Inject

/**
 * 把工作区文件以「文件行」形式发送到聊天区展示（一行一个文件：缩略图/类型图标 + 文件名 + 大小·路径），
 * 点击用系统对应 app 打开，图片则在应用内全屏预览。
 *
 * 原子语义：所有文件必须全部存在且合法，任一失败则整体失败（不渲染任何文件行），
 * 错误信息逐条列出失败项，AI 可修正 paths 后重新调用。
 * 文件数据（内容/base64）不会进入模型上下文，模型只收到文件元数据文本。
 */
class SendFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val agentMessageDao: AgentMessageDao
) : AbstractContextualTool() {
    override val name = "sendFile"

    /**
     * 单个文件大小上限可在偏好设置调整（默认 100MB）。工具定义（[description] / [parameters]）由模型侧
     * 读取、且为非挂起属性，故用 [cachedMaxFileSizeMb] 缓存最近一次设置值；实际校验以 [execute] 内挂起
     * 读取的权威值为准。
     */
    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var cachedMaxFileSizeMb = DEFAULT_MAX_FILE_SIZE_MB

    /** 同一会话并行 sendFile 时，避免两个结果同时通过“尚未发送”检查。 */
    private val sendMutex = Mutex()

    init {
        settingsScope.launch {
            generalSettingsRepository.sendFileMaxSizeMbFlow.collect { cachedMaxFileSizeMb = it }
        }
    }

    override val description get() = """
        把本任务产出的、用户确实需要的文件交付到聊天区（报告、最终文档、压缩包、图片或构建产物）。
        不要发送临时文件、无关文件，也不要重复发送同一会话里已经交付的路径。最多 $MAX_FILES 个文件，
        每个文件不超过 ${cachedMaxFileSizeMb}MB；全部路径必须存在且为文件，否则整体失败并返回可修正原因。
        只展示附件，不读取内容，也不会把文件内容放进模型上下文。参数 paths 是容器/工作区路径数组；
        names 可选且须与 paths 一一对应。
        正例：生成最终报告后 sendFile(paths=["reports/result.md"])；生成截图后交付截图。
        反例：sendFile(paths=["/tmp/partial.log"])（临时调试文件）；重复发送已交付的报告。
        纯问答、没有产出用户需要的文件时不要调用。
    """.trimIndent()

    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override val parameters get() = mapOf(
        "paths" to ToolParameter(
            name = "paths",
            type = ParameterType.ARRAY,
            description = "要发送的文件路径列表，支持 ~/workspace/... 项目文件或容器绝对路径；一次最多 $MAX_FILES 个，单个文件不超过 ${cachedMaxFileSizeMb}MB。",
            required = true,
            itemsSchema = mapOf("type" to "string")
        ),
        "names" to ToolParameter(
            name = "names",
            type = ParameterType.ARRAY,
            description = "可选的自定义显示名列表，与 paths 一一对应；缺省用文件名。",
            required = false,
            itemsSchema = mapOf("type" to "string")
        )
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult =
        sendMutex.withLock {
            executeSerialized(args, context)
        }

    private suspend fun executeSerialized(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        return try {
            val sessionId = context.sessionId
                ?: return ToolResult.Error("当前会话信息缺失，无法登记附件去重记录", "NO_SESSION")
            val paths = (args["paths"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (paths.isEmpty()) {
                return ToolResult.Error("缺少必需参数：paths", "MISSING_PATHS")
            }
            if (paths.size > MAX_FILES) {
                return ToolResult.Error("一次最多发送 $MAX_FILES 个文件，当前 ${paths.size} 个", "TOO_MANY_FILES")
            }
            val names = (args["names"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.map { it.trim() }
                .orEmpty()
            if (names.isNotEmpty() && names.size != paths.size) {
                return ToolResult.Error("names 数量（${names.size}）与 paths 数量（${paths.size}）不一致", "ARGS_MISMATCH")
            }

            val normalizedPaths = paths.map(::normalizePathForDedup)
            if (normalizedPaths.distinct().size != normalizedPaths.size) {
                return ToolResult.Error("paths 中包含重复路径；请每个文件只列一次。", "DUPLICATE_PATH")
            }
            val alreadySent = agentMessageDao.getMessagesBySessionOnce(sessionId)
                .asSequence()
                .filter { it.role == "TOOL" && it.toolName == name }
                .flatMap { entity -> entity.toUIMessage().attachments.asSequence() }
                .map { normalizePathForDedup(it.containerPath) }
                .toSet()
            val duplicates = paths.filterIndexed { index, _ -> normalizedPaths[index] in alreadySent }
            if (duplicates.isNotEmpty()) {
                return ToolResult.Error(
                    "这些文件已在当前会话交付过，请勿重复发送：${duplicates.joinToString()}。如需更新，请先生成不同路径的新文件。",
                    "ALREADY_SENT"
                )
            }

            // 原子校验：全部通过才成功，任一失败整体失败并列出所有失败项。
            val maxSizeMb = generalSettingsRepository.sendFileMaxSizeMb()
            val maxSizeBytes = maxSizeMb * 1024L * 1024L
            val failures = mutableListOf<String>()
            val measuredSizes = MutableList<Long?>(paths.size) { null }
            paths.forEachIndexed { index, path ->
                if (!fileAccess.exists(path)) {
                    failures.add("文件不存在或不可访问：$path。请先用 list/search 检查路径。")
                } else if (!fileAccess.isFile(path)) {
                    failures.add("路径不是普通文件：$path。请传入文件而不是目录。")
                } else {
                    val sizeBytes = fileAccess.fileSize(path)
                    measuredSizes[index] = sizeBytes
                    if (sizeBytes > maxSizeBytes) {
                        val sizeMb = sizeBytes / (1024 * 1024)
                        failures.add("文件超过 ${maxSizeMb}MB 限制：$path（${sizeMb}MB）")
                    }
                }
            }
            if (failures.isNotEmpty()) {
                FileLogger.w(TAG, "sendFile 原子校验失败: ${failures.size} 项；路径详情仅返回给当前会话")
                return ToolResult.Error(failures.joinToString("；"), "INVALID_FILE")
            }

            val displayPaths = paths.map { fileAccess.toDisplayPath(it) }
            val files = paths.mapIndexed { index, path ->
                val rawName = path.substringAfterLast('/').ifBlank { path }
                // 显示名允许自定义；MIME 类型必须按原始文件名推断，自定义名缺后缀/加前缀都不影响类型识别。
                val displayName = names.getOrNull(index)?.takeIf { it.isNotBlank() } ?: rawName
                JsonObject(
                    mapOf(
                        "path" to JsonPrimitive(displayPaths[index]),
                        "local_path" to JsonPrimitive(fileAccess.copyToLocal(path).absolutePath),
                        "name" to JsonPrimitive(displayName),
                        "mime_type" to JsonPrimitive(guessMimeType(rawName)),
                        "size_bytes" to JsonPrimitive(measuredSizes[index] ?: 0L),
                        "is_image" to JsonPrimitive(guessMimeType(rawName).startsWith("image/"))
                    )
                )
            }

            // toDisplayPath 现为 suspend：先在挂起作用域里算好显示名，再进非挂起的 joinToString。
            FileLogger.i(TAG, "sendFile 成功: session=$sessionId files=${paths.size}")
            ToolResult.Success(
                JsonObject(
                    mapOf(
                        "content" to JsonPrimitive(
                            buildString {
                                append("已发送 ${files.size} 个文件：")
                                files.forEach { file ->
                                    append('\n')
                                    append("- ")
                                    append(file["name"]?.jsonPrimitive?.contentOrNull)
                                    append("：")
                                    append(file["path"]?.jsonPrimitive?.contentOrNull)
                                }
                            }
                        ),
                        "files" to JsonArray(files),
                        "file_count" to JsonPrimitive(files.size)
                    )
                )
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            FileLogger.e(TAG, "sendFile 异常", e)
            ToolResult.Error(e.message ?: "发送文件失败", "SEND_FILE_ERROR")
        }
    }

    private fun normalizePathForDedup(path: String): String =
        path.trim().replace('\\', '/').replace(MULTIPLE_SLASHES, "/").trimEnd('/')

    private fun guessMimeType(fileName: String): String {
        val extMime = when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "pdf" -> "application/pdf"
            "zip" -> "application/zip"
            "txt", "md", "kt", "java", "js", "ts", "json", "xml", "html", "css", "py", "sh", "gradle", "kts" -> "text/plain"
            "apk" -> "application/vnd.android.package-archive"
            else -> null
        }
        return extMime ?: URLConnection.guessContentTypeFromName(fileName) ?: "application/octet-stream"
    }

    private companion object {
        const val TAG = "SendFileTool"
        const val MAX_FILES = 10
        /** 设置未就绪前的回退值，与偏好设置里的默认值一致。 */
        const val DEFAULT_MAX_FILE_SIZE_MB = 100
        val MULTIPLE_SLASHES = Regex("/+")
    }
}
