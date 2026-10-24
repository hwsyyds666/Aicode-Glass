package com.aicode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllSessionsByWorkspace(workspacePath: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND parentId IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    fun getRootSessionsByWorkspace(workspacePath: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAllSessionsByWorkspaceOnce(workspacePath: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND parentId IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getRootSessionsByWorkspaceOnce(workspacePath: String): List<ChatSessionEntity>

    /** 最近更新的根会话（忽略置顶，仅按 updatedAt 降序），供启动时「打开最近会话」。 */
    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND parentId IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestRootSessionByWorkspace(workspacePath: String): ChatSessionEntity?

    /** 指定父会话的全部子会话（子代理），按最近更新降序。 */
    @Query("SELECT * FROM chat_sessions WHERE parentId = :parentId ORDER BY updatedAt DESC")
    fun getSubSessionsByParent(parentId: String): Flow<List<ChatSessionEntity>>

    /** 指定父会话的全部子会话（子代理），一次性查询。 */
    @Query("SELECT * FROM chat_sessions WHERE parentId = :parentId ORDER BY updatedAt DESC")
    suspend fun getSubSessionsByParentOnce(parentId: String): List<ChatSessionEntity>

    @Query("SELECT COUNT(*) FROM chat_sessions WHERE parentId = :parentId")
    suspend fun countSubSessionsByParent(parentId: String): Int

    @Query("SELECT subagentCreatedCount FROM chat_sessions WHERE id = :parentId")
    suspend fun getSubagentCreatedCount(parentId: String): Int?

    @Query("UPDATE chat_sessions SET subagentCreatedCount = subagentCreatedCount + 1 WHERE id = :parentId AND subagentCreatedCount < :maxTotal")
    suspend fun reserveSubagentCreation(parentId: String, maxTotal: Int): Int

    @Query("UPDATE chat_sessions SET subagentCreatedCount = MAX(0, subagentCreatedCount - 1) WHERE id = :parentId")
    suspend fun rollbackSubagentCreation(parentId: String)

    @Query("UPDATE chat_sessions SET subagentStatus = :status WHERE id = :id AND parentId IS NOT NULL")
    suspend fun updateSubagentStatus(id: String, status: String)

    /** Atomically reserve one bounded rework round only after a child reached a terminal state. */
    @Query("UPDATE chat_sessions SET subagentReworkCount = subagentReworkCount + 1, subagentStatus = 'STARTING' " +
        "WHERE id = :id AND parentId = :parentId AND subagentStatus IN ('COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED') " +
        "AND subagentReworkCount < :maxRounds")
    suspend fun reserveSubagentRework(id: String, parentId: String, maxRounds: Int): Int

    /** Roll back a reservation only while its wake-up event has not changed the STARTING state. */
    @Query("UPDATE chat_sessions SET subagentReworkCount = MAX(0, subagentReworkCount - 1), subagentStatus = :previousStatus " +
        "WHERE id = :id AND parentId = :parentId AND subagentStatus = 'STARTING' AND subagentReworkCount > 0")
    suspend fun rollbackSubagentRework(id: String, parentId: String, previousStatus: String): Int

    @Query("UPDATE chat_sessions SET subagentStatus = 'CANCELLED' WHERE parentId IS NOT NULL AND subagentStatus IN ('STARTING', 'RUNNING')")
    suspend fun markRunningSubagentsCancelled()

    @Query("SELECT * FROM chat_sessions")
    suspend fun getAllOnce(): List<ChatSessionEntity>

    /** 分页读取（keyset：按 updatedAt,id 字典序取 [limit] 条），供备份流式导出。 */
    @Query("SELECT * FROM chat_sessions WHERE updatedAt > :lastUpdatedAt OR (updatedAt = :lastUpdatedAt AND id > :lastId) ORDER BY updatedAt ASC, id ASC LIMIT :limit")
    suspend fun getPageAfter(lastUpdatedAt: Long, lastId: String, limit: Int): List<ChatSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<ChatSessionEntity>)

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getById(id: String): ChatSessionEntity?

    /** 单个会话的实时流（根会话与子会话通用）。 */
    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    fun getByIdFlow(id: String): Flow<ChatSessionEntity?>

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("UPDATE chat_sessions SET isPinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: String, pinned: Boolean)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM chat_sessions WHERE workspacePath = :workspacePath")
    suspend fun deleteByWorkspace(workspacePath: String)

    @Query("UPDATE chat_sessions SET providerId = :providerId, model = :model, lastInputTokens = 0 WHERE id = :id")
    suspend fun updateProviderModel(id: String, providerId: String?, model: String?)

    @Query("UPDATE chat_sessions SET reasoningEffort = :effort WHERE id = :id")
    suspend fun updateReasoningEffort(id: String, effort: String)

    @Query("UPDATE chat_sessions SET totalInputTokens = totalInputTokens + :inputTokens, totalOutputTokens = totalOutputTokens + :outputTokens WHERE id = :id")
    suspend fun addTokenUsage(id: String, inputTokens: Int, outputTokens: Int)

    @Query("UPDATE chat_sessions SET lastInputTokens = :lastInputTokens WHERE id = :id")
    suspend fun updateLastInputTokens(id: String, lastInputTokens: Int)

    @Query("UPDATE chat_sessions SET superAgentEnabled = :enabled WHERE id = :id AND parentId IS NULL")
    suspend fun updateSuperAgentEnabled(id: String, enabled: Boolean)
}
