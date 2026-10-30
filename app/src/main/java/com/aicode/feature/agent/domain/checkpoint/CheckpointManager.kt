package com.aicode.feature.agent.domain.checkpoint

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.entity.CheckpointEntity
import com.aicode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.tool.WorkspaceFileLock
import com.aicode.feature.git.domain.GitCommandEngine
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.sha256Hex
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckpointManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val checkpointDao: CheckpointDao,
    private val fileAccess: FileAccessProvider,
    private val fileLock: WorkspaceFileLock = WorkspaceFileLock()
) {
    /** Hilt member-injects the command backend; manually constructed unit-test instances keep file snapshots. */
    private var gitCommandEngine: GitCommandEngine? = null

    @Inject
    fun bindGitCommandEngine(engine: GitCommandEngine) {
        gitCommandEngine = engine
    }

    // 检查点备份根路径: <filesDir>/checkpoints/<sessionId>/<checkpointId>/
    private val baseCheckpointDir: File
        get() = File(context.filesDir, "checkpoints")

    // 活动 checkpoint 必须按会话隔离：多个会话（含父会话与其子代理）会并行跑 workflow，
    // 用单一字段会被后创建的会话覆盖，导致文件快照挂到别的会话名下，
    // 那个会话一撤销就会把本会话刚写好的文件还原/删除。
    private val activeCheckpointIds = ConcurrentHashMap<String, String>()

    /** Capture a Git tree without touching the user's real index. A sidecar keeps the Room schema stable. */
    suspend fun captureGitTreeBeforeChanges(sessionId: String, checkpointId: String, workspacePath: String): GitSnapshotCapture =
        withContext(Dispatchers.IO) {
            if (workspacePath.isBlank()) return@withContext saveGitSnapshotState(sessionId, checkpointId, "UNAVAILABLE: workspace path is empty")
            val commandEngine = gitCommandEngine
                ?: return@withContext saveGitSnapshotState(sessionId, checkpointId, "UNAVAILABLE: Git snapshot engine is not configured")
            val command = """
                set -eu
                root=${'$'}(git rev-parse --show-toplevel 2>/dev/null) || exit 40
                index=${'$'}(git rev-parse --git-path index)
                case "${'$'}index" in /*) ;; *) index="${'$'}root/${'$'}index" ;; esac
                tmp=${'$'}(mktemp "${'$'}{TMPDIR:-/tmp}/aicode-checkpoint-index.XXXXXX")
                trap 'rm -f "${'$'}tmp"' EXIT HUP INT TERM
                if [ -f "${'$'}index" ]; then cp "${'$'}index" "${'$'}tmp"; else GIT_INDEX_FILE="${'$'}tmp" git read-tree --empty; fi
                oversized=${'$'}(
                    {
                        git diff --relative --name-only --diff-filter=ACMRTUXB -z -- .
                        git diff --relative --cached --name-only --diff-filter=ACMRTUXB -z -- .
                        git ls-files --others --exclude-standard -z -- .
                    } | xargs -0 -r -n 1 sh -c '
                        path=${'$'}1
                        [ -f "${'$'}path" ] || exit 0
                        size=${'$'}(wc -c < "${'$'}path")
                        if [ "${'$'}size" -gt ${GitTreeSnapshotPolicy.MAX_FILE_BYTES} ]; then
                            printf "%s (%s bytes)\\n" "${'$'}path" "${'$'}size"
                        fi
                    ' _
                )
                if [ -n "${'$'}oversized" ]; then
                    printf 'CHECKPOINT_LIMIT: Git snapshot skipped because modified files exceed %s bytes:\\n%s\\n' '${GitTreeSnapshotPolicy.MAX_FILE_BYTES}' "${'$'}oversized"
                    exit 41
                fi
                GIT_INDEX_FILE="${'$'}tmp" git add -A -- .
                GIT_INDEX_FILE="${'$'}tmp" git write-tree
            """.trimIndent()
            val result = runCatching {
                commandEngine.runCommandSyncUnbounded(command, workspacePath, CommandEngine.DEFAULT_TIMEOUT_MS)
            }.getOrElse { error ->
                return@withContext saveGitSnapshotState(sessionId, checkpointId, "UNAVAILABLE: ${error.message.orEmpty()}")
            }
            val tree = result.output.trim().lineSequence().lastOrNull()?.trim().orEmpty()
            if (result.exitCode != 0 || result.outputTruncated || !GIT_OBJECT_ID.matches(tree)) {
                val detail = result.output.trim().takeLast(MAX_GIT_DIAGNOSTIC_CHARS).ifBlank { "当前工作区不是可快照的 Git 仓库" }
                return@withContext saveGitSnapshotState(sessionId, checkpointId, "UNAVAILABLE: $detail")
            }
            writeGitSnapshotState(sessionId, checkpointId, tree)
            GitSnapshotCapture(tree = tree)
        }

    /**
     * 在用户发送新消息时调用，创建一个新的 Checkpoint 节点
     */
    suspend fun createCheckpoint(
        sessionId: String,
        userMessageId: String,
        prompt: String
    ): CheckpointEntity = withContext(Dispatchers.IO) {
        val checkpointId = UUID.randomUUID().toString()
        val snippet = if (prompt.length > 60) prompt.take(60) + "..." else prompt
        val entity = CheckpointEntity(
            id = checkpointId,
            sessionId = sessionId,
            userMessageId = userMessageId,
            promptSnippet = snippet,
            createdAt = System.currentTimeMillis()
        )
        checkpointDao.insertCheckpoint(entity)
        activeCheckpointIds[sessionId] = checkpointId
        entity
    }

    fun setActiveCheckpointId(sessionId: String, checkpointId: String?) {
        if (checkpointId == null) {
            activeCheckpointIds.remove(sessionId)
        } else {
            activeCheckpointIds[sessionId] = checkpointId
        }
    }

    /** The lock key uses the same display-path mapping across local, container and SSH backends. */
    suspend fun <T> withWorkspaceModificationLock(workspacePath: String, block: suspend () -> T): T =
        fileLock.withLock(workspaceLockKey(workspacePath), block)

    suspend fun <T> withFileModificationLock(
        filePath: String,
        workspacePath: String,
        block: suspend () -> T
    ): T = fileLock.withLocks(
        listOf(workspaceLockKey(workspacePath), fileAccess.toDisplayPath(filePath)),
        block
    )

    private suspend fun workspaceLockKey(workspacePath: String): String =
        "workspace:${fileAccess.toDisplayPath(workspacePath).trimEnd('/', '\\')}"

    /**
     * 在工具（editFile/writeFile）将要修改或新建文件前调用。
     * 若该文件在当前 Checkpoint 周期内未快照过，则保存其原始状态。
     *
     * 返回 [SnapshotOutcome]：被体积/空间限制跳过时 [SnapshotOutcome.skipReason] 非空，
     * 调用方应把「未创建快照，无法回滚」提示给模型与界面，不静默跳过。
     */
    suspend fun beforeFileModified(
        sessionId: String,
        filePath: String,
        checkpointIdOverride: String? = null
    ): SnapshotOutcome = withContext(Dispatchers.IO) {
        if (checkpointIdOverride != null && checkpointIdOverride.isBlank()) {
            return@withContext SnapshotOutcome(
                saved = false,
                skipReason = "此子代理没有关联到发起它的父对话检查点；本次写入无法自动回退。"
            )
        }
        val checkpointId = resolveCheckpointId(sessionId, checkpointIdOverride)
            ?: return@withContext SnapshotOutcome(saved = false)

        // 查重：同一个 checkpointId 内对同一文件只保留最原始的一次快照
        if (checkpointDao.countSnapshot(checkpointId, filePath) > 0) {
            return@withContext SnapshotOutcome(saved = false)
        }

        val snapshotDir = File(baseCheckpointDir, "$sessionId/$checkpointId")
        if (!snapshotDir.exists()) {
            snapshotDir.mkdirs()
        }

        val exists = fileAccess.exists(filePath)
        val changeType = if (exists) "MODIFY" else "CREATE"
        val snapshotFileName = "${UUID.randomUUID()}_${File(filePath).name}"
        val snapshotFile = File(snapshotDir, snapshotFileName)

        if (changeType == "MODIFY") {
            val size = fileAccess.fileSize(filePath)
            // 单文件快照体积上限：超过就跳过快照并明确告知（大文件编辑本就容易被工具层拒绝）。
            SnapshotPolicy.skipReason(size)?.let { reason ->
                FileLogger.w(TAG, "跳过快照: $reason path=$filePath")
                return@withContext SnapshotOutcome(saved = false, skipReason = reason)
            }
            // 存储空间预检：快照分区剩余空间不足时同样跳过并提示，
            // 避免写一半 ENOSPC 留下半截快照（回滚时会静默还原失败）。
            val usable = snapshotDir.usableSpace
            if (usable < size + SNAPSHOT_SPACE_MARGIN_BYTES) {
                val reason = "存储空间不足（剩余 ${usable / 1024 / 1024}MB），未创建快照，无法回滚"
                FileLogger.w(TAG, "跳过快照: $reason path=$filePath")
                return@withContext SnapshotOutcome(saved = false, skipReason = reason)
            }
            // 流式复制：本地模式 copyToLocal 直接给宿主文件、远程模式下载到临时文件，
            // 再 copyTo 磁盘到磁盘——整份内容不进堆（旧实现 readFile + writeText
            // 约占 2 倍文件大小的堆内存，几十 MB 的数据文件就能把移动端顶爆）。
            val local = fileAccess.copyToLocal(filePath)
            local.copyTo(snapshotFile, overwrite = true)
        } else {
            snapshotFile.writeText("") // 标示创建空记录
        }

        val snapshotEntity = CheckpointFileSnapshotEntity(
            id = UUID.randomUUID().toString(),
            checkpointId = checkpointId,
            filePath = filePath,
            snapshotRelativePath = "$sessionId/$checkpointId/$snapshotFileName",
            changeType = changeType,
            expectedFingerprint = if (changeType == "MODIFY") snapshotFile.sha256Hex() else MISSING_FILE_FINGERPRINT
        )
        checkpointDao.insertFileSnapshot(snapshotEntity)
        FileLogger.i(TAG, "快照: session=$sessionId cp=$checkpointId type=$changeType path=$filePath")
        SnapshotOutcome(saved = true)
    }

    /** Persist the version left by a successful file tool so rewind can detect later external edits. */
    suspend fun recordFileVersion(
        sessionId: String,
        filePath: String,
        checkpointIdOverride: String? = null
    ): String? = withContext(Dispatchers.IO) {
        if (checkpointIdOverride != null && checkpointIdOverride.isBlank()) {
            return@withContext "此子代理没有关联到发起它的父对话检查点；文件版本未登记，无法安全自动回退。"
        }
        val checkpointId = resolveCheckpointId(sessionId, checkpointIdOverride) ?: return@withContext null
        val fingerprint = runCatching { fileAccess.contentFingerprint(filePath) }
            .onFailure { FileLogger.w(TAG, "无法记录文件版本: $filePath", it) }
            .getOrNull()
        checkpointDao.updateExpectedFingerprint(checkpointId, filePath, fingerprint)
        if (fingerprint == null) "无法确认文件当前版本，回退时会跳过以保护其他改动：$filePath" else null
    }

    /**
     * Record changed tracked and non-ignored untracked paths after a shell batch. The Git tree is the
     * baseline; these fingerprints let rewind restore only paths still at this session's version.
     */
    suspend fun recordWorkspaceVersions(
        sessionId: String,
        workspacePath: String,
        checkpointIdOverride: String? = null
    ): String? = withContext(Dispatchers.IO) {
        if (checkpointIdOverride != null && checkpointIdOverride.isBlank()) {
            return@withContext "此子代理没有关联到发起它的父对话检查点；本轮 shell 命令改动无法自动回退。"
        }
        val checkpointId = resolveCheckpointId(sessionId, checkpointIdOverride) ?: return@withContext null
        // 无 Git 树 / 引擎未装配是「会话级恒定条件」（工作区不是 Git 仓库，或引擎未装配），
        // 不是某一条命令的问题：曾按每条命令注入同一句 warning，结果 Bash 的 stdout
        // 被这句话淹没（用户视角=工具坏了），而信息量零。改为只记日志，回退路径
        // 在真正无法还原时给出明确错误。
        val tree = readGitSnapshotState(sessionId, checkpointId)
            ?.takeIf { GIT_OBJECT_ID.matches(it) }
            ?: run {
                FileLogger.w(TAG, "会话无 Git 检查点树，shell 改动不登记版本: session=$sessionId cp=$checkpointId")
                return@withContext null
            }
        val engine = gitCommandEngine
            ?: run {
                FileLogger.w(TAG, "Git 检查点引擎未装配，shell 改动不登记版本: session=$sessionId")
                return@withContext null
            }
        val trackedResult = runCatching {
            engine.runCommandSyncUnbounded("git diff --relative --name-only -z '$tree' -- .", workspacePath, CommandEngine.DEFAULT_TIMEOUT_MS)
        }.getOrElse { error ->
            FileLogger.w(TAG, "无法检查 shell 文件改动: ${error.message.orEmpty()}")
            return@withContext "无法读取 Git 工作区改动清单；本轮 shell 命令改动无法自动回退。"
        }
        val untrackedResult = runCatching {
            engine.runCommandSyncUnbounded("git ls-files --others --exclude-standard -z -- .", workspacePath, CommandEngine.DEFAULT_TIMEOUT_MS)
        }.getOrElse { error ->
            FileLogger.w(TAG, "无法检查 shell 新建文件: ${error.message.orEmpty()}")
            return@withContext "无法读取 Git 新建文件清单；本轮 shell 命令改动无法自动回退。"
        }
        if (trackedResult.exitCode != 0 || trackedResult.outputTruncated ||
            untrackedResult.exitCode != 0 || untrackedResult.outputTruncated
        ) {
            FileLogger.w(TAG, "无法记录 shell 文件版本: git diff/ls-files 返回失败或输出被截断")
            return@withContext "Git 改动清单失败或超出输出上限；本轮 shell 命令改动无法自动回退。"
        }
        val tracked = trackedResult.output.split('\u0000').asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        val untrackedCandidates = untrackedResult.output.split('\u0000').asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if ((tracked.size + untrackedCandidates.size) > MAX_TRACKED_PATHS) {
            FileLogger.w(TAG, "shell 改动路径超过 $MAX_TRACKED_PATHS，跳过版本登记以避免无界 I/O")
            return@withContext "本轮 shell 命令改动超过 $MAX_TRACKED_PATHS 个文件，未登记版本；这些改动无法安全自动回退。"
        }

        // The temporary index also contains files that were already untracked before this turn.
        // Compare just those candidates against the saved tree so unchanged pre-existing files are
        // not attributed to this session, while modifications to them remain rewindable.
        val baselineUntracked = if (untrackedCandidates.isEmpty()) {
            emptyMap()
        } else {
            val prefixResult = runCatching {
                engine.runCommandSyncUnbounded("git rev-parse --show-prefix", workspacePath, CommandEngine.DEFAULT_TIMEOUT_MS)
            }.getOrNull()?.takeIf { it.exitCode == 0 && !it.outputTruncated }
                ?: return@withContext "无法确定 Git 工作区相对路径；本轮 shell 命令改动无法安全自动回退。"
            val gitPrefix = prefixResult.output.trimEnd('\r', '\n')
            val treeCommand = buildString {
                append("git --literal-pathspecs ls-tree -r -z ")
                append(shellQuote(tree))
                append(" -- ")
                append(untrackedCandidates.joinToString(" ") { shellQuote(gitPrefix + it) })
            }
            runCatching {
                engine.runCommandSyncUnbounded(treeCommand, workspacePath, CommandEngine.DEFAULT_TIMEOUT_MS)
            }.getOrNull()
                ?.takeIf { it.exitCode == 0 && !it.outputTruncated }
                ?.output
                ?.split('\u0000')
                ?.filter(String::isNotEmpty)
                ?.mapNotNull { entry ->
                    val tab = entry.lastIndexOf('\t')
                    if (tab < 0) return@mapNotNull null
                    val metadata = entry.substring(0, tab).split(' ')
                    val repositoryPath = entry.substring(tab + 1)
                    val relative = repositoryPath.removePrefix(gitPrefix)
                    val objectId = metadata.getOrNull(2)
                    if (relative.isBlank() || relative == repositoryPath && gitPrefix.isNotBlank() || objectId.isNullOrBlank()) {
                        null
                    } else relative to objectId
                }
                ?.toMap()
        }
        val currentUntrackedHashes = if (untrackedCandidates.isEmpty()) {
            emptyMap()
        } else {
            val hashCommand = buildString {
                append("for p in ")
                append(untrackedCandidates.joinToString(" ") { shellQuote(it) })
                append("; do printf '%s\\0' \"\$p\"; hash=\$(git hash-object --path=\"\$p\" -- \"\$p\") || exit 1; printf '%s\\0' \"\$hash\"; done")
            }
            runCatching {
                engine.runCommandSyncUnbounded(hashCommand, workspacePath, CommandEngine.DEFAULT_TIMEOUT_MS)
            }.getOrNull()
                ?.takeIf { it.exitCode == 0 && !it.outputTruncated }
                ?.output
                ?.split('\u0000')
                ?.let { fields ->
                    if (fields.size % 2 != 1 || fields.last().isNotEmpty()) return@let null
                    fields.dropLast(1).chunked(2).mapNotNull { pair ->
                        val path = pair.getOrNull(0)
                        val objectId = pair.getOrNull(1)?.trim()
                        if (path.isNullOrBlank() || objectId.isNullOrBlank()) null else path to objectId
                    }.toMap().takeIf { it.size == untrackedCandidates.size }
                }
        }
        val untrackedMembershipKnown = baselineUntracked != null && currentUntrackedHashes != null
        val untracked = if (untrackedMembershipKnown) {
            untrackedCandidates.filter { relative -> baselineUntracked[relative] != currentUntrackedHashes[relative] }
        } else {
            FileLogger.w(TAG, "无法确认未跟踪文件与检查点基线的差异；将登记为不可安全回退")
            emptyList()
        }
        val changedPaths = (tracked + untracked).distinct()
        val unverifiable = mutableListOf<String>()

        suspend fun persistExpectedVersion(path: String, fingerprint: String?) {
            if (checkpointDao.countSnapshot(checkpointId, path) > 0) {
                checkpointDao.updateExpectedFingerprint(checkpointId, path, fingerprint)
            } else {
                checkpointDao.insertFileSnapshot(
                    CheckpointFileSnapshotEntity(
                        id = UUID.randomUUID().toString(),
                        checkpointId = checkpointId,
                        filePath = path,
                        snapshotRelativePath = "",
                        changeType = GIT_SNAPSHOT_ENTRY,
                        expectedFingerprint = fingerprint
                    )
                )
            }
        }

        if (!untrackedMembershipKnown) {
            untrackedCandidates.forEach { relative ->
                val path = workspacePath.trimEnd('/', '\\') + "/" + relative
                persistExpectedVersion(path, null)
                unverifiable += path
            }
        }
        for (relative in changedPaths) {
            val path = workspacePath.trimEnd('/', '\\') + "/" + relative
            if (GitTreeSnapshotPolicy.repositoryRelativePath(path, workspacePath) == null) {
                unverifiable += path
                continue
            }
            val fingerprint = runCatching { fileAccess.contentFingerprint(path) }
                .onFailure { FileLogger.w(TAG, "无法记录 shell 文件版本: $path", it) }
                .getOrNull()
            persistExpectedVersion(path, fingerprint)
            if (fingerprint == null) unverifiable += path
        }
        unverifiable.takeIf { it.isNotEmpty() }
            ?.let { paths -> "无法确认以下文件的当前版本，回退时会跳过以保护其他改动：${paths.take(8).joinToString(", ")}" }
    }

    /**
     * 将代码回滚到指定 Checkpoint 节点的初始状态
     */
    suspend fun restoreCodeToCheckpoint(
        sessionId: String,
        targetCheckpointId: String
    ): Int = restoreCodeToCheckpointDetailed(sessionId, targetCheckpointId).restoredFileCount

    /** Restore only paths whose current contents still match this session's last recorded version. */
    suspend fun restoreCodeToCheckpointDetailed(
        sessionId: String,
        targetCheckpointId: String,
        workspacePath: String? = null
    ): CheckpointRestoreResult {
        val restore: suspend () -> CheckpointRestoreResult = {
            withContext(Dispatchers.IO) {
        val allCheckpoints = checkpointDao.getCheckpointsForSession(sessionId)
        val targetIndex = allCheckpoints.indexOfFirst { it.id == targetCheckpointId }
        if (targetIndex == -1) return@withContext CheckpointRestoreResult(failures = listOf("找不到目标检查点"))

        // 收集 targetCheckpointId 及其之后所有 Checkpoint 的快照，倒序还原。
        val checkpointsToRollback = allCheckpoints.subList(targetIndex, allCheckpoints.size).reversed()
        var restoredFileCount = 0
        var restoredWorkspaceFileCount = 0
        var restoredGitFileCount = 0
        val failures = mutableListOf<String>()
        val limitations = mutableListOf<String>()
        var gitRestored = false
        FileLogger.i(
            TAG,
            "还原开始: session=$sessionId target=$targetCheckpointId 涉及 ${checkpointsToRollback.size} 个检查点"
        )

        val conflictedPaths = mutableSetOf<String>()
        for (cp in checkpointsToRollback) {
            val snapshots = checkpointDao.getFileSnapshotsForCheckpoint(cp.id).sortedByDescending { it.createdAt }
            for (snapshot in snapshots) {
                val displayPath = runCatching { fileAccess.toDisplayPath(snapshot.filePath) }
                    .getOrDefault(snapshot.filePath)
                if (displayPath in conflictedPaths) continue

                val expected = snapshot.expectedFingerprint
                if (expected == null) {
                    conflictedPaths += displayPath
                    limitations += "文件版本未记录，已跳过回退以保护现有内容：$displayPath"
                    continue
                }
                val current = runCatching { fileAccess.contentFingerprint(snapshot.filePath) }
                    .onFailure { FileLogger.w(TAG, "无法检查回退冲突: $displayPath", it) }
                    .getOrNull()
                if (current == null || current != expected) {
                    conflictedPaths += displayPath
                    limitations += "文件在此会话写入后又发生变化，已跳过以保护其他改动：$displayPath"
                    continue
                }

                val snapshotFile = File(baseCheckpointDir, snapshot.snapshotRelativePath)
                try {
                    val tree = readGitSnapshotState(sessionId, cp.id)
                        ?.takeIf { GIT_OBJECT_ID.matches(it) }
                    val engine = gitCommandEngine
                    val relativePath = if (!tree.isNullOrBlank() && !workspacePath.isNullOrBlank()) {
                        GitTreeSnapshotPolicy.repositoryRelativePath(snapshot.filePath, workspacePath)
                    } else null
                    val inTree = if (relativePath != null && engine != null) {
                        isPathInGitSnapshot(tree!!, snapshot.filePath, workspacePath!!, engine)
                    } else null

                    when {
                        inTree == true && relativePath != null && engine != null -> {
                            val command = "git --literal-pathspecs restore --source=${shellQuote(tree!!)} --worktree -- ${shellQuote(relativePath)}"
                            val result = engine.runCommandSyncUnbounded(command, workspacePath!!, CommandEngine.DEFAULT_TIMEOUT_MS)
                            if (result.exitCode == 0 && !result.outputTruncated) {
                                gitRestored = true
                                restoredFileCount++
                                restoredGitFileCount++
                                FileLogger.i(TAG, "还原 Git 文件: cp=${cp.id} path=$displayPath")
                            } else {
                                failures += "$displayPath：Git 文件还原失败 ${result.exitCode}"
                                conflictedPaths += displayPath
                            }
                        }
                        snapshot.changeType == GIT_SNAPSHOT_ENTRY && inTree == false -> {
                            if (fileAccess.exists(snapshot.filePath)) {
                                fileLock.withLock(displayPath) { fileAccess.delete(snapshot.filePath) }
                                restoredFileCount++
                                restoredGitFileCount++
                            }
                        }
                        snapshot.changeType == GIT_SNAPSHOT_ENTRY -> {
                            limitations += "无法确认 Git 文件的检查点版本，已跳过回退：$displayPath"
                            conflictedPaths += displayPath
                        }
                        snapshot.changeType == "MODIFY" && snapshotFile.isFile -> {
                            fileLock.withLock(displayPath) {
                                FileInputStream(snapshotFile).use { input ->
                                    fileAccess.writeStream(snapshot.filePath, input, overwrite = true)
                                }
                            }
                            restoredFileCount++
                            restoredWorkspaceFileCount++
                            FileLogger.i(TAG, "还原文件快照: cp=${cp.id} path=$displayPath")
                        }
                        snapshot.changeType == "MODIFY" -> failures += "$displayPath：本地快照文件缺失"
                        snapshot.changeType == "CREATE" && fileAccess.exists(snapshot.filePath) -> {
                            fileLock.withLock(displayPath) { fileAccess.delete(snapshot.filePath) }
                            restoredFileCount++
                            restoredWorkspaceFileCount++
                            FileLogger.i(TAG, "还原删除: cp=${cp.id} path=$displayPath")
                        }
                    }
                } catch (error: Exception) {
                    failures += "$displayPath：${error.message ?: "还原失败"}"
                    conflictedPaths += displayPath
                }
            }
        }
        FileLogger.i(TAG, "还原结束: session=$sessionId 共处理 $restoredFileCount 个文件")
        CheckpointRestoreResult(
            restoredFileCount = restoredFileCount,
            failures = failures.distinct(),
            limitations = limitations.distinct(),
            restoredGitTree = gitRestored,
            restoredWorkspaceFileCount = restoredWorkspaceFileCount,
            restoredGitFileCount = restoredGitFileCount
        )
            }
        }
        return if (workspacePath.isNullOrBlank()) restore() else withWorkspaceModificationLock(workspacePath, restore)
    }

    private suspend fun isPathInGitSnapshot(
        tree: String,
        filePath: String,
        workspacePath: String,
        commandEngine: GitCommandEngine?
    ): Boolean? {
        val engine = commandEngine ?: return null
        val relative = GitTreeSnapshotPolicy.repositoryRelativePath(filePath, workspacePath) ?: return null
        val result = runCatching {
            engine.runCommandSyncUnbounded(
                "git --literal-pathspecs ls-tree -r --name-only -z ${shellQuote(tree)} -- ${shellQuote(relative)}",
                workspacePath,
                CommandEngine.DEFAULT_TIMEOUT_MS
            )
        }.getOrNull() ?: return null
        if (result.outputTruncated || result.exitCode != 0) return null
        return result.output.split('\u0000').any { it == relative }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

    /** Explicit child checkpoints never fall through to whatever parent turn is currently active. */
    private suspend fun resolveCheckpointId(sessionId: String, override: String?): String? {
        val id = when {
            override != null -> override.takeIf(String::isNotBlank) ?: return null
            else -> activeCheckpointIds[sessionId] ?: return null
        }
        val checkpoint = checkpointDao.getCheckpointById(id) ?: return null
        return checkpoint.id.takeIf { checkpoint.sessionId == sessionId }
    }

    /**
     * 删除 Session 关联的所有 Checkpoint 快照与记录
     */
    suspend fun clearSessionCheckpoints(sessionId: String) = withContext(Dispatchers.IO) {
        activeCheckpointIds.remove(sessionId)
        checkpointDao.deleteFileSnapshotsForSession(sessionId)
        checkpointDao.deleteCheckpointsForSession(sessionId)
        val sessionDir = File(baseCheckpointDir, sessionId)
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
        }
    }

    /** Remove checkpoint metadata and sidecars for a conversation suffix that has been rewound. */
    suspend fun clearCheckpointsFrom(sessionId: String, cutoffTimestamp: Long) = withContext(Dispatchers.IO) {
        val removed = checkpointDao.getCheckpointsForSession(sessionId)
            .filter { it.createdAt >= cutoffTimestamp }
        if (removed.isEmpty()) return@withContext
        checkpointDao.deleteFileSnapshotsFrom(sessionId, cutoffTimestamp)
        checkpointDao.deleteCheckpointsFrom(sessionId, cutoffTimestamp)
        removed.forEach { checkpoint ->
            File(baseCheckpointDir, "$sessionId/${checkpoint.id}").deleteRecursively()
        }
        if (activeCheckpointIds[sessionId] in removed.map { it.id }) {
            activeCheckpointIds.remove(sessionId)
        }
    }

    suspend fun hasGitSnapshot(sessionId: String, checkpointId: String): Boolean =
        readGitSnapshotState(sessionId, checkpointId)?.let { GIT_OBJECT_ID.matches(it) } == true

    private fun gitSnapshotFile(sessionId: String, checkpointId: String): File =
        File(baseCheckpointDir, "$sessionId/$checkpointId/git-tree.txt")

    private fun writeGitSnapshotState(sessionId: String, checkpointId: String, state: String) {
        gitSnapshotFile(sessionId, checkpointId).apply {
            parentFile?.mkdirs()
            writeText(state)
        }
    }

    private fun saveGitSnapshotState(sessionId: String, checkpointId: String, state: String): GitSnapshotCapture {
        runCatching { writeGitSnapshotState(sessionId, checkpointId, state) }
        return GitSnapshotCapture(warning = state.removePrefix("UNAVAILABLE:").trim())
    }

    private fun readGitSnapshotState(sessionId: String, checkpointId: String): String? =
        gitSnapshotFile(sessionId, checkpointId).takeIf(File::isFile)?.readText()?.trim()

    private companion object {
        const val TAG = "CheckpointManager"
        const val MAX_GIT_DIAGNOSTIC_CHARS = 1200
        const val MAX_TRACKED_PATHS = 1000
        const val GIT_SNAPSHOT_ENTRY = "GIT"
        val GIT_OBJECT_ID = Regex("[0-9a-f]{40}|[0-9a-f]{64}")

        /** 快照空间预检的安全余量：分区元数据与文件系统开销，避免「够大但写不下」。 */
        const val SNAPSHOT_SPACE_MARGIN_BYTES = 16L * 1024 * 1024
    }
}

data class GitSnapshotCapture(val tree: String? = null, val warning: String? = null)

data class CheckpointRestoreResult(
    val restoredFileCount: Int = 0,
    val failures: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val restoredGitTree: Boolean = false,
    val restoredWorkspaceFileCount: Int = 0,
    val restoredGitFileCount: Int = 0
)

/**
 * 检查点快照的体积策略：工具侧提示与 [CheckpointManager] 实际跳过着同一套阈值，避免两边标准不一。
 */
internal object SnapshotPolicy {
    /** 单文件快照上限（默认 50MB）：超过即跳过快照，由调用方提示「未创建快照，无法回滚」。 */
    const val MAX_SNAPSHOT_FILE_BYTES = 50L * 1024 * 1024

    /** 超过上限时返回原因文案；未超过返回 null。 */
    fun skipReason(size: Long): String? =
        if (size > MAX_SNAPSHOT_FILE_BYTES) {
            "文件过大（${size / 1024 / 1024}MB > ${MAX_SNAPSHOT_FILE_BYTES / 1024 / 1024}MB），未创建快照，无法回滚"
        } else {
            null
        }
}

/** [CheckpointManager.beforeFileModified] 的结果。 */
data class SnapshotOutcome(
    /** 是否已落盘快照（无需快照的无活动检查点/已快照过场景为 false）。 */
    val saved: Boolean,
    /** 非空表示因体积/空间限制被跳过，调用方应把该原因提示给模型与界面，不静默跳过。 */
    val skipReason: String? = null
)
