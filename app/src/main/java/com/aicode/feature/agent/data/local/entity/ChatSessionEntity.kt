package com.aicode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.domain.model.ReasoningEffort

@Entity(
    tableName = "chat_sessions",
    indices = [Index(value = ["workspacePath"])]
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    val mode: String = AgentMode.BUILD.name,
    /** 进入 PLAN 前的模式（BUILD/ASK）；退出 PLAN 时恢复，null 视为 BUILD。 */
    val modeBeforePlan: String? = null,
    val reasoningEffort: String = ReasoningEffort.MEDIUM.name,
    val providerId: String? = null,
    val model: String? = null,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val lastInputTokens: Int = 0,
    val isPinned: Boolean = false,
    /** 子代理会话：父会话 id；null 表示普通根会话。 */
    val parentId: String? = null,
    /** 子代理会话：派生子代理的类型（如 coder / researcher）；null 表示普通根会话。 */
    val subagentType: String? = null,
    /** Persisted lifecycle state for child agents; null for root/legacy sessions. */
    val subagentStatus: String? = null,
    /** Newline-separated canonical writable path scopes; null means legacy unrestricted behavior. */
    val allowedWritePaths: String? = null,
    /** Newline-separated readable path scopes; null means legacy unrestricted behavior. */
    val allowedReadPaths: String? = null,
    /** Lifetime child-agent dispatch counter for enforcing the per-parent cap across restarts/deletions. */
    @ColumnInfo(defaultValue = "0") val subagentCreatedCount: Int = 0,
    /** Number of bounded post-completion rework rounds requested for this child. */
    @ColumnInfo(defaultValue = "0") val subagentReworkCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val superAgentEnabled: Boolean = false,
    /** User-message id in the parent chat that dispatched this child; null for root sessions. */
    val dispatchMessageId: String? = null
) {
    fun toDomain(): ChatSession = ChatSession(
        id = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        workspacePath = workspacePath,
        mode = runCatching { AgentMode.valueOf(mode) }.getOrDefault(AgentMode.BUILD),
        modeBeforePlan = modeBeforePlan?.let { runCatching { AgentMode.valueOf(it) }.getOrNull() },
        reasoningEffort = runCatching { ReasoningEffort.valueOf(reasoningEffort) }.getOrDefault(ReasoningEffort.MEDIUM),
        providerId = providerId,
        model = model,
        totalInputTokens = totalInputTokens,
        totalOutputTokens = totalOutputTokens,
        lastInputTokens = lastInputTokens,
        isPinned = isPinned,
        parentId = parentId,
        subagentType = subagentType,
        subagentStatus = subagentStatus,
        allowedWritePaths = allowedWritePaths,
        allowedReadPaths = allowedReadPaths,
        subagentCreatedCount = subagentCreatedCount,
        subagentReworkCount = subagentReworkCount,
        superAgentEnabled = superAgentEnabled,
        dispatchMessageId = dispatchMessageId
    )

    companion object {
        fun fromDomain(session: ChatSession): ChatSessionEntity = ChatSessionEntity(
            id = session.id,
            title = session.title,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
            workspacePath = session.workspacePath,
            mode = session.mode.name,
            modeBeforePlan = session.modeBeforePlan?.name,
            reasoningEffort = session.reasoningEffort.name,
            providerId = session.providerId,
            model = session.model,
            totalInputTokens = session.totalInputTokens,
            totalOutputTokens = session.totalOutputTokens,
            lastInputTokens = session.lastInputTokens,
            isPinned = session.isPinned,
            parentId = session.parentId,
            subagentType = session.subagentType,
            subagentStatus = session.subagentStatus,
            allowedWritePaths = session.allowedWritePaths,
            allowedReadPaths = session.allowedReadPaths,
            subagentCreatedCount = session.subagentCreatedCount,
            subagentReworkCount = session.subagentReworkCount,
            superAgentEnabled = session.superAgentEnabled,
            dispatchMessageId = session.dispatchMessageId
        )
    }
}
