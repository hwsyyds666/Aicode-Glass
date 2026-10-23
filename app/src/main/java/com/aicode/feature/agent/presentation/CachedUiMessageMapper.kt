package com.aicode.feature.agent.presentation

import com.aicode.feature.agent.data.local.entity.AgentMessageEntity

/**
 * 为当前会话消息页复用已完成的 Entity -> UI 映射。
 *
 * Room 在同一张表变动时可能重新发出当前页；未变化的实体（包含附件 JSON）无需再次
 * 反序列化。缓存只保留当前页的实体快照：分页、压缩或消息删除后不再出现的条目会立即
 * 淘汰；同一 id 的任意实体字段变化都会重新映射，避免流式写入或上下文压缩读到旧 UI。
 */
internal class CachedUiMessageMapper(
    private val convert: (AgentMessageEntity) -> AgentUIMessage = AgentMessageEntity::toUIMessage
) {
    private var activeSessionId: String? = null
    private val cachedEntries = mutableMapOf<String, CachedEntry>()

    fun map(sessionId: String, entities: List<AgentMessageEntity>): List<AgentUIMessage> {
        if (activeSessionId != sessionId) {
            activeSessionId = sessionId
            cachedEntries.clear()
        }

        val nextEntries = HashMap<String, CachedEntry>(entities.size)
        val messages = entities.map { entity ->
            val cached = cachedEntries[entity.id]
            val message = if (cached?.entity == entity) cached.message else convert(entity)
            nextEntries[entity.id] = CachedEntry(entity, message)
            message
        }
        cachedEntries.clear()
        cachedEntries.putAll(nextEntries)
        return messages
    }

    private data class CachedEntry(
        val entity: AgentMessageEntity,
        val message: AgentUIMessage
    )
}
