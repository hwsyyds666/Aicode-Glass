package com.aicode.feature.settings.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class OpenAIAccountRoutingPolicyTest {

    @Test
    fun allowsAddingAccountsUpToTheLimit() {
        assertTrue(OpenAIAccountSelectionPolicy.canConnect(emptySet(), null))
        assertTrue(OpenAIAccountSelectionPolicy.canConnect(setOf("profile-a"), null))
        assertTrue(OpenAIAccountSelectionPolicy.canConnect(setOf("profile-a", "profile-b"), null))
        assertTrue(
            OpenAIAccountSelectionPolicy.canConnect(
                (1 until OpenAIAccountSelectionPolicy.MAX_ACCOUNTS).map { "p$it" }.toSet(),
                null
            )
        )
        assertFalse(
            OpenAIAccountSelectionPolicy.canConnect(
                (0 until OpenAIAccountSelectionPolicy.MAX_ACCOUNTS).map { "p$it" }.toSet(),
                null
            )
        )
        assertEquals(10, OpenAIAccountSelectionPolicy.MAX_ACCOUNTS)
    }

    @Test
    fun reauthorizationRequiresAnExistingAccount() {
        assertTrue(OpenAIAccountSelectionPolicy.canConnect(setOf("profile-a"), "profile-a"))
        assertTrue(OpenAIAccountSelectionPolicy.canConnect(setOf("profile-a", "profile-b"), "profile-b"))
        assertFalse(OpenAIAccountSelectionPolicy.canConnect(setOf("profile-a"), "missing"))
        assertFalse(OpenAIAccountSelectionPolicy.canConnect(emptySet(), "profile-a"))
    }

    @Test
    fun canRouteOnlyAcceptsSavedAccounts() {
        // canRoute 只回答「这个账号在不在档」；能力回退顺序由 orderFallbackCandidates 决定
        assertTrue(OpenAIAccountSelectionPolicy.canRoute(setOf("profile-a"), "profile-a"))
        assertTrue(OpenAIAccountSelectionPolicy.canRoute(setOf("profile-a", "profile-b"), "profile-b"))
        assertFalse(OpenAIAccountSelectionPolicy.canRoute(setOf("profile-a"), "profile-b"))
        assertFalse(OpenAIAccountSelectionPolicy.canRoute(emptySet(), "profile-a"))
    }

    @Test
    fun paidOnlyModelsFallBackToPaidAccountsOnly() {
        val candidates = listOf(
            OpenAIAccountSelectionPolicy.FallbackCandidate("free-1", isFreePlan = true),
            OpenAIAccountSelectionPolicy.FallbackCandidate("plus-1", isFreePlan = false),
            OpenAIAccountSelectionPolicy.FallbackCandidate("free-2", isFreePlan = true),
            OpenAIAccountSelectionPolicy.FallbackCandidate("pro-1", isFreePlan = false)
        )
        assertEquals(
            listOf("plus-1", "pro-1"),
            OpenAIAccountSelectionPolicy.orderFallbackCandidates(candidates, modelPaidOnly = true)
        )
    }

    @Test
    fun freeTierModelsPreferFreeAccountsToSavePaidQuota() {
        val candidates = listOf(
            OpenAIAccountSelectionPolicy.FallbackCandidate("plus-1", isFreePlan = false),
            OpenAIAccountSelectionPolicy.FallbackCandidate("free-1", isFreePlan = true),
            OpenAIAccountSelectionPolicy.FallbackCandidate("pro-1", isFreePlan = false),
            OpenAIAccountSelectionPolicy.FallbackCandidate("free-2", isFreePlan = true)
        )
        // 免费账号优先，同优先级保持原始顺序
        assertEquals(
            listOf("free-1", "free-2", "plus-1", "pro-1"),
            OpenAIAccountSelectionPolicy.orderFallbackCandidates(candidates, modelPaidOnly = false)
        )
    }

    @Test
    fun fallbackKeepsOriginalOrderWithinTheSameTier() {
        val candidates = listOf(
            OpenAIAccountSelectionPolicy.FallbackCandidate("free-b", isFreePlan = true),
            OpenAIAccountSelectionPolicy.FallbackCandidate("free-a", isFreePlan = true)
        )
        assertEquals(
            listOf("free-b", "free-a"),
            OpenAIAccountSelectionPolicy.orderFallbackCandidates(candidates, modelPaidOnly = false)
        )
    }
}

class ChatGptAccountAffinityTest {

    @Test
    fun bindingKeyIsCollisionFree() {
        val affinity = ChatGptAccountAffinity()
        // 长度前缀编码：分隔符本身出现在值里也不会串键
        assertTrue(affinity.key("a|b", "m", "i") != affinity.key("a", "b|m", "i"))
        assertTrue(affinity.key("s", "m", "i") != affinity.key("s", "m", "i2"))
    }

    @Test
    fun bindsSessionAndModelToAnAccount() {
        val affinity = ChatGptAccountAffinity()
        affinity.remember("session-1", "gpt-6-luna", "session-1", "account-a", nowMs = 1_000)
        assertEquals("account-a", affinity.preferredAccountId("session-1", "gpt-6-luna", "session-1", nowMs = 2_000))
    }

    @Test
    fun differentSessionOrModelGetsDifferentBinding() {
        val affinity = ChatGptAccountAffinity()
        affinity.remember("session-1", "gpt-6-luna", "session-1", "account-a", nowMs = 1_000)
        assertNull(affinity.preferredAccountId("session-2", "gpt-6-luna", "session-2", nowMs = 2_000))
        assertNull(affinity.preferredAccountId("session-1", "gpt-6-sol", "session-1", nowMs = 2_000))
    }

    @Test
    fun bindingExpiresAfterTtl() {
        val affinity = ChatGptAccountAffinity()
        affinity.remember("session-1", "gpt-6-luna", "session-1", "account-a", nowMs = 1_000)
        val ttl = ChatGptAccountAffinity.SESSION_AFFINITY_TTL_MS
        assertEquals("account-a", affinity.preferredAccountId("session-1", "gpt-6-luna", "session-1", ttlMs = ttl, nowMs = 1_000 + ttl))
        assertNull(affinity.preferredAccountId("session-1", "gpt-6-luna", "session-1", ttlMs = ttl, nowMs = 1_000 + ttl + 1))
    }

    @Test
    fun ttlNeverDropsBelowTheFloor() {
        val affinity = ChatGptAccountAffinity()
        affinity.remember("session-1", "gpt-6-luna", "session-1", "account-a", nowMs = 1_000)
        // 配成 0 也要保留下限，避免绑定永不生效/永不过期
        assertEquals(
            "account-a",
            affinity.preferredAccountId("session-1", "gpt-6-luna", "session-1", ttlMs = 0L, nowMs = 1_000 + ChatGptAccountAffinity.TTL_FLOOR_MS)
        )
        assertNull(
            affinity.preferredAccountId("session-1", "gpt-6-luna", "session-1", ttlMs = 0L, nowMs = 1_000 + ChatGptAccountAffinity.TTL_FLOOR_MS + 1)
        )
    }

    @Test
    fun softFailureUnbindsSoNextRequestCanReroute() {
        val affinity = ChatGptAccountAffinity()
        affinity.remember("session-1", "gpt-6-luna", "session-1", "account-a", nowMs = 1_000)
        affinity.forget("session-1", "gpt-6-luna", "session-1")
        assertNull(affinity.preferredAccountId("session-1", "gpt-6-luna", "session-1", nowMs = 2_000))
    }

    @Test
    fun removingAnAccountDropsItsBindings() {
        val affinity = ChatGptAccountAffinity()
        affinity.remember("session-1", "gpt-6-luna", "session-1", "account-a", nowMs = 1_000)
        affinity.remember("session-2", "gpt-6-luna", "session-2", "account-b", nowMs = 1_000)
        affinity.forgetAccount("account-a")
        assertNull(affinity.preferredAccountId("session-1", "gpt-6-luna", "session-1", nowMs = 2_000))
        assertEquals("account-b", affinity.preferredAccountId("session-2", "gpt-6-luna", "session-2", nowMs = 2_000))
    }
}
