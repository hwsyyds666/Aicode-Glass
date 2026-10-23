package com.aicode.feature.agent.domain.checkpoint

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.entity.CheckpointEntity
import com.aicode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.aicode.feature.workspace.domain.FileAccessProvider
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
    private val fileAccess: FileAccessProvider
) {
    // 检查点备份根路径: <filesDir>/checkpoints/<sessionId>/<checkpointId>/
    private val baseCheckpointDir: File
        get() = File(context.filesDir, "checkpoints")

    // 活动 checkpoint 必须按会话隔离：多个会话（含父会话与其子代理）会并行跑 workflow，
    // 用单一字段会被后创建的会话覆盖，导致文件快照挂到别的会话名下，
    // 那个会话一撤销就会把本会话刚写好的文件还原/删除。
    private val activeCheckpointIds = ConcurrentHashMap<String, String>()

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

    /**
     * 在工具（editFile/writeFile）将要修改或新建文件前调用。
     * 若该文件在当前 Checkpoint 周期内未快照过，则保存其原始状态。
     *
     * 返回 [SnapshotOutcome]：被体积/空间限制跳过时 [SnapshotOutcome.skipReason] 非空，
     * 调用方应把「未创建快照，无法回滚」提示给模型与界面，不静默跳过。
     */
    suspend fun beforeFileModified(
        sessionId: String,
        filePath: String
    ): SnapshotOutcome = withContext(Dispatchers.IO) {
        val checkpointId = activeCheckpointIds[sessionId] ?: return@withContext SnapshotOutcome(saved = false)

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
            changeType = changeType
        )
        checkpointDao.insertFileSnapshot(snapshotEntity)
        FileLogger.i(TAG, "快照: session=$sessionId cp=$checkpointId type=$changeType path=$filePath")
        SnapshotOutcome(saved = true)
    }

    /**
     * 将代码回滚到指定 Checkpoint 节点的初始状态
     */
    suspend fun restoreCodeToCheckpoint(
        sessionId: String,
        targetCheckpointId: String
    ): Int = withContext(Dispatchers.IO) {
        val allCheckpoints = checkpointDao.getCheckpointsForSession(sessionId)
        val targetIndex = allCheckpoints.indexOfFirst { it.id == targetCheckpointId }
        if (targetIndex == -1) return@withContext 0

        // 收集 targetCheckpointId 及其之后所有 Checkpoint 的快照，倒序还原
        val checkpointsToRollback = allCheckpoints.subList(targetIndex, allCheckpoints.size).reversed()
        var restoredFileCount = 0
        FileLogger.i(
            TAG,
            "还原开始: session=$sessionId target=$targetCheckpointId 涉及 ${checkpointsToRollback.size} 个检查点"
        )

        for (cp in checkpointsToRollback) {
            val snapshots = checkpointDao.getFileSnapshotsForCheckpoint(cp.id)
            for (snapshot in snapshots) {
                val snapshotFile = File(baseCheckpointDir, snapshot.snapshotRelativePath)

                if (snapshot.changeType == "MODIFY") {
                    if (snapshotFile.exists()) {
                        // 流式写回：不把整份快照读进堆（旧实现 readText 后 writeFile
                        // 约占 2 倍大小的堆内存）。writeStream 写中途失败会清掉半截文件。
                        FileInputStream(snapshotFile).use { input ->
                            fileAccess.writeStream(snapshot.filePath, input, overwrite = true)
                        }
                        restoredFileCount++
                        FileLogger.i(TAG, "还原覆盖: cp=${cp.id} path=${snapshot.filePath}")
                    }
                } else if (snapshot.changeType == "CREATE") {
                    // 若是原先新建的文件，回滚时安全删除
                    if (fileAccess.exists(snapshot.filePath)) {
                        fileAccess.delete(snapshot.filePath)
                        restoredFileCount++
                        FileLogger.i(TAG, "还原删除: cp=${cp.id} path=${snapshot.filePath}")
                    }
                }
            }
        }
        FileLogger.i(TAG, "还原结束: session=$sessionId 共处理 $restoredFileCount 个文件")
        restoredFileCount
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

    private companion object {
        const val TAG = "CheckpointManager"

        /** 快照空间预检的安全余量：分区元数据与文件系统开销，避免「够大但写不下」。 */
        const val SNAPSHOT_SPACE_MARGIN_BYTES = 16L * 1024 * 1024
    }
}

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
