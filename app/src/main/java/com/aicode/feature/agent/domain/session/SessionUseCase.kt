package com.aicode.feature.agent.domain.session

import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.presentation.MessageRole
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionUseCase @Inject constructor(
    private val chatSessionDao: ChatSessionDao,
    private val agentMessageDao: AgentMessageDao
) {
    companion object {
        private const val TAG = "SessionUseCase"
        const val TITLE_MAX = 20
        /** 工具占位行前缀：标记「执行中、结果未回」的孤儿，UI 与回放据此识别。 */
        const val PENDING_TOOL_MARKER = "[running]"
        /** 历史版本 emoji 前缀；冷启动收尾与回放仍需识别。 */
        const val LEGACY_PENDING_TOOL_MARKER = "\u23F3"
        /** 历史版本停止 emoji 前缀；UI 剥离结果文本时兼容。 */
        const val LEGACY_STOPPED_TOOL_MARKER = "\u23F9"
        const val INTERRUPTED_TOOL_TEXT = "执行被中断（应用已关闭）"
    }

    /** 冷启动收尾：上次进程被杀时若有工具正在执行，其占位行会永久显示「执行中」。 */
    suspend fun initColdStartCleanup() {
        runCatching {
            chatSessionDao.markRunningSubagentsCancelled()
            val n = listOf(PENDING_TOOL_MARKER, LEGACY_PENDING_TOOL_MARKER).sumOf { marker ->
                agentMessageDao.markPendingToolsInterrupted(
                    toolRole = MessageRole.TOOL.name,
                    pendingPrefix = "$marker%",
                    interruptedContent = INTERRUPTED_TOOL_TEXT
                )
            }
            if (n > 0) FileLogger.i(TAG, "冷启动收尾 $n 条残留「执行中」工具行为已中断")
        }.onFailure { FileLogger.e(TAG, "回收残留执行中工具行失败", it) }
    }

    fun newSessionEntity(
        workspacePath: String,
        providerId: String? = null,
        model: String? = null,
        reasoningEffort: String = ReasoningEffort.MEDIUM.name
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        return ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = "新会话",
            workspacePath = workspacePath,
            createdAt = now,
            updatedAt = now,
            providerId = providerId,
            model = model,
            reasoningEffort = reasoningEffort
        )
    }

    fun deriveTitle(request: String): String {
        val clean = request.trim().replace(Regex("\\s+"), " ")
        return if (clean.length <= TITLE_MAX) clean.ifBlank { "新对话" }
        else clean.take(TITLE_MAX) + "…"
    }

    /** 删除会话及其全部子代理会话（消息 + 会话记录一并删除）。返回被删除的会话 id 列表。 */
    suspend fun deleteSession(id: String): List<String> {
        val deleted = getSessionCascadeIds(id)
        // Children first: this remains safe if a future schema adds a parent foreign key.
        deleted.forEach { sessionId ->
            agentMessageDao.deleteBySession(sessionId)
            chatSessionDao.delete(sessionId)
        }
        return deleted
    }

    /** Returns a stable post-order list (descendants before root), tolerating corrupt cycles/orphans. */
    suspend fun getSessionCascadeIds(id: String): List<String> {
        val visited = linkedSetOf<String>()
        val postOrder = mutableListOf<String>()
        suspend fun visit(sessionId: String) {
            if (!visited.add(sessionId)) return
            chatSessionDao.getSubSessionsByParentOnce(sessionId).forEach { child -> visit(child.id) }
            postOrder += sessionId
        }
        visit(id)
        return postOrder
    }

    /** 删除某工作区下的全部会话（含子代理会话）及其消息，返回删除的会话数。 */
    suspend fun deleteSessionsByWorkspace(workspacePath: String): Int {
        val sessions = chatSessionDao.getAllSessionsByWorkspaceOnce(workspacePath)
        if (sessions.isEmpty()) return 0
        sessions.forEach { session -> agentMessageDao.deleteBySession(session.id) }
        chatSessionDao.deleteByWorkspace(workspacePath)
        return sessions.size
    }

    suspend fun getFirstSessionOfWorkspace(workspacePath: String): ChatSessionEntity? {
        return chatSessionDao.getRootSessionsByWorkspaceOnce(workspacePath).firstOrNull()
    }

    /** 工作区里最近更新的根会话（忽略置顶），供启动时进入最近会话。 */
    suspend fun getMostRecentSessionOfWorkspace(workspacePath: String): ChatSessionEntity? {
        return chatSessionDao.getLatestRootSessionByWorkspace(workspacePath)
    }

    /** 回收工作区下多余的空会话（从未发送过消息），保留 [keepId]；保证列表最多一个空会话。 */
    suspend fun recycleEmptySessions(workspacePath: String, keepId: String? = null): Int {
        var count = 0
        chatSessionDao.getRootSessionsByWorkspaceOnce(workspacePath).forEach { session ->
            if (session.id != keepId && !agentMessageDao.hasMessages(session.id)) {
                deleteSession(session.id)
                count++
            }
        }
        return count
    }

    /**
     * 创建子代理会话，继承父会话的 provider/model/reasoningEffort/workspacePath。
     * @param title 子会话标题（由 task 描述派生）
     * @param parentId 父会话 id
     * @param subagentType 子代理类型 id（自定义 agent 名，或默认通用子代理标识）
     * @param providerId 覆盖 provider；null 表示继承父会话
     * @param model 覆盖模型；null 表示继承父会话
     * @param reasoningEffort 覆盖思考强度；null 表示继承父会话
     * @param mode 覆盖运行模式；null 表示继承父会话
     */
    fun newSubSessionEntity(
        title: String,
        parentId: String,
        parent: ChatSessionEntity,
        subagentType: String,
        providerId: String? = null,
        model: String? = null,
        reasoningEffort: String? = null,
        mode: AgentMode? = null,
        allowedWritePaths: List<String> = emptyList(),
        allowedReadPaths: List<String> = emptyList(),
        dispatchMessageId: String? = null
    ): ChatSessionEntity {
        val now = System.currentTimeMillis()
        return ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            workspacePath = parent.workspacePath,
            createdAt = now,
            updatedAt = now,
            // 默认继承父会话 mode：PLAN 的只读策略必须继续传递到子代理，BUILD 则保留父会话
            // 的正常授权流程。自定义子代理可用定义的 mode 覆盖该默认值。
            mode = mode?.name ?: parent.mode,
            providerId = providerId ?: parent.providerId,
            model = model ?: parent.model,
            reasoningEffort = reasoningEffort ?: parent.reasoningEffort,
            parentId = parentId,
            subagentType = subagentType,
            subagentStatus = "STARTING",
            allowedWritePaths = allowedWritePaths.joinToString("\n"),
            allowedReadPaths = allowedReadPaths.joinToString("\n"),
            dispatchMessageId = dispatchMessageId
        )
    }

    suspend fun updateSubagentStatus(sessionId: String, status: String) {
        chatSessionDao.updateSubagentStatus(sessionId, status)
    }

    suspend fun updateSuperAgentEnabled(sessionId: String, enabled: Boolean) {
        chatSessionDao.updateSuperAgentEnabled(sessionId, enabled)
    }

    suspend fun upsertSession(entity: ChatSessionEntity) {
        chatSessionDao.upsert(entity)
    }

    suspend fun updateTitle(sessionId: String, title: String) {
        chatSessionDao.updateTitle(sessionId, title)
    }

    suspend fun updatePinned(sessionId: String, pinned: Boolean) {
        chatSessionDao.updatePinned(sessionId, pinned)
    }

    suspend fun touch(sessionId: String, timestamp: Long) {
        chatSessionDao.touch(sessionId, timestamp)
    }

    suspend fun getSessionById(id: String): ChatSessionEntity? {
        return chatSessionDao.getById(id)
    }

    suspend fun updateMode(sessionId: String, mode: String) {
        val s = chatSessionDao.getById(sessionId) ?: return
        val newMode = runCatching { AgentMode.valueOf(mode) }.getOrNull() ?: return
        val currentMode = runCatching { AgentMode.valueOf(s.mode) }.getOrNull()
        // 用户手动进入 PLAN 时同样记下进入前的模式，退出 PLAN 时恢复；AI 退出还要经过
        // planMode 的用户批准流程，避免计划代理自行开始实施。
        val modeBeforePlan = when {
            newMode == AgentMode.PLAN && currentMode != AgentMode.PLAN -> currentMode?.name
            newMode == AgentMode.PLAN -> s.modeBeforePlan
            else -> null
        }
        chatSessionDao.upsert(s.copy(mode = newMode.name, modeBeforePlan = modeBeforePlan))
    }

    suspend fun updateProviderModel(sessionId: String, providerId: String?, model: String?) {
        chatSessionDao.updateProviderModel(sessionId, providerId, model)
    }

    suspend fun updateReasoningEffort(sessionId: String, effort: String) {
        chatSessionDao.updateReasoningEffort(sessionId, effort)
    }

    suspend fun isSessionEmpty(sessionId: String): Boolean {
        return !agentMessageDao.hasMessages(sessionId)
    }
}
