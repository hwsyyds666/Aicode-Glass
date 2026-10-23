package com.aicode.feature.agent.presentation

import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class CachedUiMessageMapperTest {

    @Test
    fun unchangedMessagesReusePreviousUiModelsWhenPageIsReemitted() {
        val conversions = AtomicInteger()
        val mapper = CachedUiMessageMapper { entity ->
            conversions.incrementAndGet()
            entity.toUIMessage()
        }
        val firstPage = listOf(entity("one"), entity("two"))

        val first = mapper.map("session", firstPage)
        val reemitted = mapper.map("session", firstPage.map { it.copy() })

        assertEquals("相同 Room 快照不应重复映射", 2, conversions.get())
        assertSame(first[0], reemitted[0])
        assertSame(first[1], reemitted[1])
        assertEquals(first, reemitted)
    }

    @Test
    fun changedMessageRemapsOnlyThatMessageAndKeepsOrder() {
        val conversions = AtomicInteger()
        val mapper = CachedUiMessageMapper { entity ->
            conversions.incrementAndGet()
            entity.toUIMessage()
        }
        val firstPage = listOf(entity("one", "first"), entity("two", "second"))
        val first = mapper.map("session", firstPage)

        val updated = mapper.map("session", listOf(firstPage[0], firstPage[1].copy(content = "updated")))

        assertEquals("仅流式更新的消息需要重新映射", 3, conversions.get())
        assertSame(first[0], updated[0])
        assertNotSame(first[1], updated[1])
        assertEquals(listOf("one", "two"), updated.map { it.id })
        assertEquals("updated", updated[1].content)
    }

    @Test
    fun removedMessagesAreEvictedSoCompactionCannotReuseStaleModels() {
        val conversions = AtomicInteger()
        val mapper = CachedUiMessageMapper { entity ->
            conversions.incrementAndGet()
            entity.toUIMessage()
        }
        val compacted = entity("old")
        val retained = entity("new")

        mapper.map("session", listOf(compacted, retained))
        mapper.map("session", listOf(retained))
        mapper.map("session", listOf(compacted, retained))

        assertEquals("压缩后移除的消息重新出现时必须重新映射", 3, conversions.get())
    }

    @Test
    fun sessionSwitchStartsWithAnEmptyCache() {
        val conversions = AtomicInteger()
        val mapper = CachedUiMessageMapper { entity ->
            conversions.incrementAndGet()
            entity.toUIMessage()
        }
        val message = entity("same-id")

        mapper.map("first", listOf(message))
        mapper.map("second", listOf(message))

        assertEquals(2, conversions.get())
    }

    private fun entity(id: String, content: String = id) = AgentMessageEntity(
        id = id,
        sessionId = "session",
        role = MessageRole.ASSISTANT.name,
        content = content,
        timestamp = 1L
    )
}
