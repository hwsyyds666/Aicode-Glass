package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.settings.data.repository.CommandAuthLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandReviewCacheTest {
    @Test
    fun decisionsAreScopedToSessionRequestAndCompleteReviewContext() {
        val cache = CommandReviewCache<String>(8)
        val key = key()
        cache[key] = "ALLOW"

        assertEquals("ALLOW", cache[key.copy()])
        listOf(
            key.copy(sessionId = "other-session"),
            key.copy(requestId = "other-request"),
            key.copy(normalizedCommand = "rm -rf build"),
            key.copy(declaredIntent = "different intent"),
            key.copy(latestUserRequest = "different task"),
            key.copy(workingDirectory = "/outside"),
            key.copy(authLevel = CommandAuthLevel.RULE_BASED),
            key.copy(environment = "remote-ssh"),
            key.copy(reviewerProviderId = "other-provider"),
            key.copy(reviewerModel = "other-model"),
            key.copy(contextFingerprint = "different-context")
        ).forEach { changed -> assertNull("Cache must miss after a safety-relevant change: $changed", cache[changed]) }
    }

    @Test
    fun accessOrderEvictsLeastRecentlyUsedAndClearDropsSessionDecisions() {
        val cache = CommandReviewCache<String>(2)
        val first = key(requestId = "1")
        val second = key(requestId = "2")
        val third = key(requestId = "3")
        cache[first] = "ALLOW"
        cache[second] = "DENY"

        assertEquals("ALLOW", cache[first]) // Make the second entry least recently used.
        cache[third] = "UNSURE"

        assertNull(cache[second])
        assertEquals("ALLOW", cache[first])
        assertEquals("UNSURE", cache[third])
        cache.clear()
        assertNull(cache[first])
        assertNull(cache[third])
    }

    private fun key(requestId: String = "request") = CommandReviewCacheKey(
        sessionId = "session",
        requestId = requestId,
        toolName = "Bash",
        normalizedCommand = "git status --short",
        declaredIntent = "inspect changes",
        latestUserRequest = "review repository state",
        workingDirectory = "/workspace",
        authLevel = CommandAuthLevel.AUTONOMOUS,
        environment = "local",
        reviewerProviderId = "provider",
        reviewerModel = "model",
        contextFingerprint = "fingerprint"
    )
}
