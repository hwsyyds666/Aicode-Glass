package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.settings.data.repository.SubAgentModelTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInSubAgentCatalogTest {
    @Test
    fun catalogHasExactlyFourBuiltInTypesWithBoundedToolsAndModelTiers() {
        assertEquals(listOf("explore", "research", "worker", "reviewer"), BuiltInSubAgentCatalog.typeNames)
        val explore = requireNotNull(BuiltInSubAgentCatalog.findCanonical("explore"))
        val research = requireNotNull(BuiltInSubAgentCatalog.findCanonical("research"))
        val worker = requireNotNull(BuiltInSubAgentCatalog.findCanonical("worker"))
        val reviewer = requireNotNull(BuiltInSubAgentCatalog.findCanonical("reviewer"))

        assertEquals(SubAgentModelTier.FAST, explore.modelTier)
        assertEquals(SubAgentModelTier.FAST, research.modelTier)
        assertEquals(SubAgentModelTier.POWER, worker.modelTier)
        assertEquals(SubAgentModelTier.POWER, reviewer.modelTier)
        assertEquals(setOf("readFile", "list", "search", "loadSkill"), explore.allowedTools)
        assertEquals(setOf("websearch", "webfetch"), research.allowedTools)
        assertTrue(worker.allowedTools == null)
        assertEquals(setOf("readFile", "list", "search"), reviewer.allowedTools)
        listOf(explore, research, reviewer).forEach { definition ->
            assertFalse("${definition.name} must not nest", definition.allowsTool("task"))
        }
        assertTrue(worker.allowsTool("writeFile"))
        assertFalse(worker.allowsTool("task"))
    }

    @Test
    fun creationRejectsAliasesButLegacyHistoryCanStillResolveThem() {
        assertNull(BuiltInSubAgentCatalog.findCanonical("coder"))
        assertNull(BuiltInSubAgentCatalog.findCanonical("WORKER"))
        assertEquals("worker", BuiltInSubAgentCatalog.find("coder")?.name)
        assertEquals("research", BuiltInSubAgentCatalog.find("researcher")?.name)
        assertEquals("reviewer", BuiltInSubAgentCatalog.find("code-reviewer")?.name)
    }
}
