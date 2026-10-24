package com.aicode.feature.settings.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun routesTheExplicitlyRequestedAccountButNeverFallsBackToAnother() {
        assertTrue(OpenAIAccountSelectionPolicy.canRoute(setOf("profile-a"), "profile-a"))
        assertTrue(OpenAIAccountSelectionPolicy.canRoute(setOf("profile-a", "profile-b"), "profile-b"))
        assertFalse(OpenAIAccountSelectionPolicy.canRoute(setOf("profile-a"), "profile-b"))
        assertFalse(OpenAIAccountSelectionPolicy.canRoute(emptySet(), "profile-a"))
    }
}
