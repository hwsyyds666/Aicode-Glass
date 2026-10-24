package com.aicode.feature.settings.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAIAccountRoutingPolicyTest {
    @Test
    fun allowsOnlyFirstConnectionAndSameProfileReauthorization() {
        assertTrue(OpenAIAccountSelectionPolicy.canConnect(emptySet(), null))
        assertTrue(OpenAIAccountSelectionPolicy.canConnect(setOf("profile-a"), "profile-a"))
        assertFalse(OpenAIAccountSelectionPolicy.canConnect(setOf("profile-a"), null))
    }

    @Test
    fun refusesAddingOrReauthorizingWhenLegacyMultipleProfilesExist() {
        assertFalse(OpenAIAccountSelectionPolicy.canConnect(setOf("profile-a", "profile-b"), null))
        assertFalse(OpenAIAccountSelectionPolicy.canConnect(setOf("profile-a", "profile-b"), "profile-a"))
        assertFalse(OpenAIAccountSelectionPolicy.canConnect(setOf("profile-a"), "missing"))
    }

    @Test
    fun routesOnlyTheSoleSavedProfileAndFailsClosedForLegacyPools() {
        assertTrue(OpenAIAccountSelectionPolicy.canRoute(setOf("profile-a"), "profile-a"))
        assertFalse(OpenAIAccountSelectionPolicy.canRoute(setOf("profile-a"), "profile-b"))
        assertFalse(OpenAIAccountSelectionPolicy.canRoute(setOf("profile-a", "profile-b"), "profile-a"))
    }
}
