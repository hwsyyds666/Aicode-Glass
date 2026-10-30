package com.aicode.feature.agent.presentation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 从全局运行工具快照中筛出单条 UI 所需的数据。
 *
 * 聊天面板不能直接订阅整个 Map：某条工具输出增长会令整页组合失效。这里每个消费者
 * 只接收自身关心的值，且通过 distinctUntilChanged 忽略其他工具的进度更新。
 */
internal class RunningToolFlowSelector(
    private val currentSessionId: StateFlow<String?>,
    private val runningTools: StateFlow<Map<String, Map<String, RunningToolOutput>>>
) {
    val activeToolName: Flow<String?> = currentSessionId.flatMapLatest { sessionId ->
        if (sessionId == null) {
            flowOf(null)
        } else {
            runningTools.map { sessions ->
                sessions[sessionId]
                    ?.values
                    ?.lastOrNull()
                    ?.toolName
                    ?.takeIf(String::isNotBlank)
            }
        }
    }.distinctUntilChanged()

    fun outputFor(messageId: String): Flow<String?> = currentSessionId.flatMapLatest { sessionId ->
        if (sessionId == null) {
            flowOf(null)
        } else {
            runningTools.map { sessions -> sessions[sessionId]?.get(messageId)?.text }
        }
    }.distinctUntilChanged()

    fun anyRunning(messageIds: Set<String>): Flow<Boolean> {
        if (messageIds.isEmpty()) return flowOf(false)
        return currentSessionId.flatMapLatest { sessionId ->
            if (sessionId == null) {
                flowOf(false)
            } else {
                runningTools.map { sessions ->
                    val tools = sessions[sessionId].orEmpty()
                    messageIds.any(tools::containsKey)
                }
            }
        }.distinctUntilChanged()
    }
}
