package com.aicode.feature.agent.presentation

import com.aicode.feature.agent.data.local.entity.AgentMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class CachedUiMessageMapperTest {
    @Test
    fun unchangedEntitiesReuseMappedMessagesAndChangedEntitiesRemapOnlyThatEntry() {
        val conversions = AtomicInteger()
        val mapper = CachedUiMessageMapper { entity ->
            conversions.incrementAndGet()
            entity.toUIMessage()
        }
        val first = message("one", "first")
        val second = message("two", "second")

        val initial = mapper.map("session", listOf(first, second))
        val unchanged = mapper.map("session", listOf(first.copy(), second.copy()))

        assertEquals(2, conversions.get())
        assertSame(initial[0], unchanged[0])
        assertSame(initial[1], unchanged[1])

        val edited = mapper.map("session", listOf(first.copy(content = "edited"), second))
        assertEquals(3, conversions.get())
        assertNotSame(unchanged[0], edited[0])
        assertSame(unchanged[1], edited[1])
        assertEquals("edited", edited[0].content)
    }

    @Test
    fun cacheIsBoundToCurrentPageAndSession() {
        val conversions = AtomicInteger()
        val mapper = CachedUiMessageMapper { entity ->
            conversions.incrementAndGet()
            entity.toUIMessage()
        }
        val first = message("one", "first")
        val second = message("two", "second")

        mapper.map("session-a", listOf(first, second))
        mapper.map("session-a", listOf(second))
        mapper.map("session-a", listOf(first, second))
        assertEquals("An item evicted from the current page is converted again", 3, conversions.get())

        mapper.map("session-b", listOf(first, second))
        assertEquals("Changing sessions clears prior page snapshots", 5, conversions.get())
    }

    private fun message(id: String, content: String) = AgentMessageEntity(
        id = id,
        sessionId = "session",
        role = MessageRole.USER.name,
        content = content,
        timestamp = id.hashCode().toLong()
    )
}
