package com.aicode.feature.agent.domain.checkpoint

import android.content.ContextWrapper
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.entity.CheckpointEntity
import com.aicode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/**
 * 活动 checkpoint 的会话归属。
 *
 * 回归点：活动 checkpoint 曾是全局单字段，会话 B 发消息会覆盖会话 A 的活动 checkpoint，
 * A 之后写文件产生的快照被挂到 B 名下；B 一撤销就把 A 已写盘的文件还原或删除
 * （表现为「写盘成功但文件消失」的幽灵文件）。
 */
class CheckpointManagerSessionIsolationTest {

    private val root: File = File(System.getProperty("java.io.tmpdir"), "cp-test-${System.nanoTime()}").apply { mkdirs() }
    private val filesDir = File(root, "files").apply { mkdirs() }
    private val workspace = File(root, "workspace").apply { mkdirs() }

    private val dao = FakeCheckpointDao()
    private val fileAccess = LocalFileAccess()
    private val manager = CheckpointManager(FakeContext(filesDir), dao, fileAccess)

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun restoringOtherSession_doesNotRevertModifiedFile() = runTest {
        val path = File(workspace, "a.txt").apply { writeText("original") }.absolutePath

        manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        val cpB = manager.createCheckpoint("sessionB", "msgB", "B 的请求")

        // A 的 Agent 在 B 发过消息之后才动文件
        manager.beforeFileModified("sessionA", path)
        fileAccess.writeFile(path, "A 的新内容", overwrite = true)

        val restored = manager.restoreCodeToCheckpoint("sessionB", cpB.id)

        assertEquals(0, restored)
        assertEquals("A 的新内容", File(path).readText())
    }

    @Test
    fun restoringOtherSession_doesNotDeleteCreatedFile() = runTest {
        val path = File(workspace, "new.txt").absolutePath

        manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        val cpB = manager.createCheckpoint("sessionB", "msgB", "B 的请求")

        manager.beforeFileModified("sessionA", path)
        fileAccess.writeFile(path, "A 新建的文件", overwrite = true)

        manager.restoreCodeToCheckpoint("sessionB", cpB.id)

        assertTrue(File(path).exists())
        assertEquals("A 新建的文件", File(path).readText())
    }

    @Test
    fun restoringOwnSession_revertsAndDeletes() = runTest {
        val modified = File(workspace, "m.txt").apply { writeText("original") }.absolutePath
        val created = File(workspace, "c.txt").absolutePath

        val cpA = manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        manager.createCheckpoint("sessionB", "msgB", "B 的请求")

        manager.beforeFileModified("sessionA", modified)
        fileAccess.writeFile(modified, "改过了", overwrite = true)
        manager.recordFileVersion("sessionA", modified)
        manager.beforeFileModified("sessionA", created)
        fileAccess.writeFile(created, "新建的", overwrite = true)
        manager.recordFileVersion("sessionA", created)

        val restored = manager.restoreCodeToCheckpoint("sessionA", cpA.id)

        assertEquals(2, restored)
        assertEquals("original", File(modified).readText())
        assertFalse(File(created).exists())
    }

    @Test
    fun restoringOwnSession_skipsFileChangedByAnotherWriterAfterward() = runTest {
        val path = File(workspace, "shared.txt").apply { writeText("before A") }.absolutePath
        val checkpoint = manager.createCheckpoint("sessionA", "msgA", "A request")

        manager.beforeFileModified("sessionA", path)
        fileAccess.writeFile(path, "written by A", overwrite = true)
        manager.recordFileVersion("sessionA", path)
        fileAccess.writeFile(path, "later change from another session", overwrite = true)

        val result = manager.restoreCodeToCheckpointDetailed("sessionA", checkpoint.id)

        assertEquals(0, result.restoredFileCount)
        assertEquals("later change from another session", File(path).readText())
        assertTrue(result.limitations.any { it.contains("已跳过") })
    }

    @Test
    fun snapshotsAreScopedToOwningSession() = runTest {
        val pathA = File(workspace, "a.txt").apply { writeText("a") }.absolutePath
        val pathB = File(workspace, "b.txt").apply { writeText("b") }.absolutePath

        val cpA = manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        val cpB = manager.createCheckpoint("sessionB", "msgB", "B 的请求")

        manager.beforeFileModified("sessionA", pathA)
        manager.beforeFileModified("sessionB", pathB)

        assertEquals(listOf(pathA), dao.getFileSnapshotsForCheckpoint(cpA.id).map { it.filePath })
        assertEquals(listOf(pathB), dao.getFileSnapshotsForCheckpoint(cpB.id).map { it.filePath })
    }

    @Test
    fun childWriteUsesDispatchCheckpointEvenAfterParentStartsAnotherTurn() = runTest {
        val path = File(workspace, "child.txt").apply { writeText("before parent turn") }.absolutePath
        val dispatchCheckpoint = manager.createCheckpoint("parent", "parent-msg-1", "dispatch child")
        manager.createCheckpoint("parent", "parent-msg-2", "a later parent turn")

        manager.beforeFileModified("parent", path, dispatchCheckpoint.id)
        fileAccess.writeFile(path, "written by child", overwrite = true)
        manager.recordFileVersion("parent", path, dispatchCheckpoint.id)

        assertEquals(
            listOf(path),
            dao.getFileSnapshotsForCheckpoint(dispatchCheckpoint.id).map { it.filePath }
        )
        assertTrue(dao.getFileSnapshotsForCheckpoint(
            dao.getCheckpointsForSession("parent").last().id
        ).isEmpty())

        val restored = manager.restoreCodeToCheckpoint("parent", dispatchCheckpoint.id)
        assertEquals(1, restored)
        assertEquals("before parent turn", File(path).readText())
    }

    @Test
    fun explicitMissingOrForeignCheckpointNeverFallsBackToAnotherActiveTurn() = runTest {
        val path = File(workspace, "unattributed.txt").absolutePath
        val active = manager.createCheckpoint("sessionA", "msgA", "active turn")
        val foreign = manager.createCheckpoint("sessionB", "msgB", "foreign turn")

        manager.beforeFileModified("sessionA", path, checkpointIdOverride = "")
        manager.beforeFileModified("sessionA", path, checkpointIdOverride = foreign.id)

        assertTrue(dao.getFileSnapshotsForCheckpoint(active.id).isEmpty())
        assertTrue(dao.getFileSnapshotsForCheckpoint(foreign.id).isEmpty())
    }

    @Test
    fun clearingSessionDropsItsActiveCheckpoint() = runTest {
        val path = File(workspace, "a.txt").apply { writeText("original") }.absolutePath
        val cpA = manager.createCheckpoint("sessionA", "msgA", "A 的请求")

        manager.clearSessionCheckpoints("sessionA")
        manager.beforeFileModified("sessionA", path)

        assertTrue(dao.getFileSnapshotsForCheckpoint(cpA.id).isEmpty())
    }

    // ---- 17·P0-2：流式快照与体积上限 ----

    @Test
    fun snapshotUsesStreamingPath_notFullReadFile() = runTest {
        val path = File(workspace, "big.txt").apply { writeText("x".repeat(4096)) }.absolutePath

        manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        val outcome = manager.beforeFileModified("sessionA", path)

        assertTrue(outcome.saved)
        // 流式复制：不允许出现全量 readFile（那是旧实现 OOM 的根源）
        assertEquals(0, fileAccess.readFileCalls)
        assertTrue(fileAccess.copyToLocalCalls > 0)
    }

    @Test
    fun oversizedFileSkipsSnapshotWithReason() = runTest {
        val path = File(workspace, "huge.bin").apply { writeText("small") }.absolutePath
        fileAccess.sizeOverrides = mapOf(path to 51L * 1024 * 1024)

        manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        val outcome = manager.beforeFileModified("sessionA", path)

        assertFalse(outcome.saved)
        assertNotNull(outcome.skipReason)
        assertTrue(outcome.skipReason!!.contains("未创建快照"))
        // 跳过时不落快照记录，还原时也不会去动这个文件
        assertTrue(dao.getFileSnapshotsForCheckpoint(
            dao.getCheckpointsForSession("sessionA").last().id
        ).isEmpty())
    }

    @Test
    fun snapshotWithinLimitStillSaves() = runTest {
        val path = File(workspace, "ok.txt").apply { writeText("content") }.absolutePath
        fileAccess.sizeOverrides = mapOf(path to 10L * 1024 * 1024) // 10MB < 50MB 上限

        manager.createCheckpoint("sessionA", "msgA", "A 的请求")
        val outcome = manager.beforeFileModified("sessionA", path)

        assertTrue(outcome.saved)
        assertNull(outcome.skipReason)
    }

    private class FakeContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    private class FakeCheckpointDao : CheckpointDao {
        private val checkpoints = mutableListOf<CheckpointEntity>()
        private val snapshots = mutableListOf<CheckpointFileSnapshotEntity>()

        override suspend fun insertCheckpoint(checkpoint: CheckpointEntity) {
            checkpoints.removeAll { it.id == checkpoint.id }
            checkpoints += checkpoint
        }

        override suspend fun insertFileSnapshot(snapshot: CheckpointFileSnapshotEntity) {
            snapshots.removeAll { it.id == snapshot.id }
            snapshots += snapshot
        }

        // 同一毫秒创建的 checkpoint 按插入顺序稳定排序，对齐 Room 的 createdAt ASC
        override suspend fun getCheckpointsForSession(sessionId: String): List<CheckpointEntity> =
            checkpoints.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }

        override suspend fun getCheckpointBySessionAndMessage(sessionId: String, messageId: String): CheckpointEntity? =
            checkpoints.firstOrNull { it.sessionId == sessionId && it.userMessageId == messageId }

        override suspend fun getCheckpointById(checkpointId: String): CheckpointEntity? =
            checkpoints.firstOrNull { it.id == checkpointId }

        override suspend fun getFileSnapshotsForCheckpoint(checkpointId: String): List<CheckpointFileSnapshotEntity> =
            snapshots.filter { it.checkpointId == checkpointId }

        override suspend fun countSnapshot(checkpointId: String, filePath: String): Int =
            snapshots.count { it.checkpointId == checkpointId && it.filePath == filePath }

        override suspend fun updateExpectedFingerprint(checkpointId: String, filePath: String, fingerprint: String?) {
            snapshots.replaceAll { snapshot ->
                if (snapshot.checkpointId == checkpointId && snapshot.filePath == filePath) {
                    snapshot.copy(expectedFingerprint = fingerprint)
                } else snapshot
            }
        }

        override suspend fun deleteCheckpointsForSession(sessionId: String) {
            checkpoints.removeAll { it.sessionId == sessionId }
        }

        override suspend fun deleteFileSnapshotsForSession(sessionId: String) {
            val ids = checkpoints.filter { it.sessionId == sessionId }.map { it.id }.toSet()
            snapshots.removeAll { it.checkpointId in ids }
        }

        override suspend fun deleteCheckpointsBefore(cutoffTimestamp: Long) {
            checkpoints.removeAll { it.createdAt < cutoffTimestamp }
        }

        override suspend fun deleteFileSnapshotsFrom(sessionId: String, cutoffTimestamp: Long) {
            val ids = checkpoints
                .filter { it.sessionId == sessionId && it.createdAt >= cutoffTimestamp }
                .map { it.id }
                .toSet()
            snapshots.removeAll { it.checkpointId in ids }
        }

        override suspend fun deleteCheckpointsFrom(sessionId: String, cutoffTimestamp: Long) {
            checkpoints.removeAll { it.sessionId == sessionId && it.createdAt >= cutoffTimestamp }
        }

        override suspend fun countFileSnapshotsFrom(sessionId: String, cutoffTimestamp: Long): Int {
            val ids = checkpoints
                .filter { it.sessionId == sessionId && it.createdAt >= cutoffTimestamp }
                .map { it.id }
                .toSet()
            return snapshots.asSequence()
                .filter { it.checkpointId in ids }
                .map { it.filePath }
                .distinct()
                .count()
        }

        override suspend fun countUnverifiableFileSnapshotsFrom(sessionId: String, cutoffTimestamp: Long): Int {
            val ids = checkpoints
                .filter { it.sessionId == sessionId && it.createdAt >= cutoffTimestamp }
                .map { it.id }
                .toSet()
            return snapshots.asSequence()
                .filter { it.checkpointId in ids && it.expectedFingerprint == null }
                .map { it.filePath }
                .distinct()
                .count()
        }
    }

    private class LocalFileAccess : FileAccessProvider {
        /** readFile 调用计数：流式快照路径下必须保持 0（P0-2 回归点）。 */
        var readFileCalls = 0
            private set
        /** copyToLocal 调用计数：流式快照走的是它。 */
        var copyToLocalCalls = 0
            private set
        /** 测试注入的假体积（模拟「实际很小但元数据很大」的超限文件）。 */
        var sizeOverrides: Map<String, Long> = emptyMap()

        override suspend fun readFile(path: String): String {
            readFileCalls++
            return File(path).readText()
        }
        override suspend fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) {
            File(path).parentFile?.mkdirs()
            File(path).writeText(content, encoding)
        }

        override suspend fun exists(path: String): Boolean = File(path).exists()
        override suspend fun delete(path: String) {
            File(path).delete()
        }

        override fun readLines(path: String): kotlinx.coroutines.flow.Flow<String> = throw UnsupportedOperationException()
        override suspend fun isDirectory(path: String): Boolean = File(path).isDirectory
        override suspend fun isFile(path: String): Boolean = File(path).isFile
        override suspend fun fileSize(path: String): Long = sizeOverrides[path] ?: File(path).length()
        override suspend fun lastModified(path: String): Long = throw UnsupportedOperationException()
        override suspend fun permissions(path: String): String = throw UnsupportedOperationException()
        override suspend fun listFiles(path: String): List<FileEntry> = throw UnsupportedOperationException()
        override suspend fun readBytes(path: String): ByteArray = File(path).readBytes()
        override suspend fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) {
            File(path).parentFile?.mkdirs()
            File(path).writeBytes(bytes)
        }
        override suspend fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
            File(path).parentFile?.mkdirs()
            File(path).outputStream().use { out -> input.copyTo(out) }
            return File(path).length()
        }
        override suspend fun listFilesRecursive(path: String, maxDepth: Int): List<String> = emptyList()
        override suspend fun copyToLocal(path: String): File {
            copyToLocalCalls++
            return File(path)
        }
        override suspend fun deleteRecursively(path: String) = throw UnsupportedOperationException()
        override suspend fun rename(path: String, newPath: String) = throw UnsupportedOperationException()
        override suspend fun copy(path: String, newPath: String, overwrite: Boolean) = throw UnsupportedOperationException()
        override suspend fun move(path: String, newPath: String, overwrite: Boolean) = throw UnsupportedOperationException()
        override suspend fun mkdirs(path: String) = throw UnsupportedOperationException()
        override suspend fun parentPath(path: String): String? = throw UnsupportedOperationException()
        override suspend fun toDisplayPath(path: String): String = path
    }
}
