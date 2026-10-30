package com.aicode.feature.workspace.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubPaginationTest {
    @Test
    fun parsesNextPageFromLinkHeaderRegardlessOfLinkOrder() {
        val header = "<https://api.github.com/user/repos?page=8&per_page=50>; rel=\"last\", " +
            "<https://api.github.com/user/repos?per_page=50&page=3>; rel=\"next\", " +
            "<https://api.github.com/user/repos?page=2&per_page=50>; rel=\"prev\""
        assertEquals(3, githubNextPage(header))
    }

    @Test
    fun absentOrMalformedNextPageReturnsNull() {
        assertNull(githubNextPage("<https://api.github.com/user/repos?page=1>; rel=\"prev\""))
        assertNull(githubNextPage("<not a URL>; rel=\"next\""))
        assertNull(githubNextPage(null))
    }

    @Test
    fun deviceFlowPollResponsesMapToExpectedTransitions() {
        assertEquals(
            GitHubDevicePollStatus.PENDING,
            parseGitHubDevicePollResult("authorization_pending").status
        )
        assertEquals(
            GitHubDevicePollStatus.SLOW_DOWN,
            parseGitHubDevicePollResult("slow_down").status
        )
        assertEquals(
            GitHubDevicePollStatus.DENIED,
            parseGitHubDevicePollResult("access_denied").status
        )
        assertEquals(
            GitHubDevicePollStatus.EXPIRED,
            parseGitHubDevicePollResult("expired_token").status
        )
        val authorized = parseGitHubDevicePollResult(error = null, accessToken = "opaque-token")
        assertEquals(GitHubDevicePollStatus.AUTHORIZED, authorized.status)
        assertEquals("opaque-token", authorized.accessToken)
        assertEquals(
            GitHubDevicePollStatus.UNKNOWN,
            parseGitHubDevicePollResult("unexpected", "ignored").status
        )
    }

    @Test
    fun rateLimitWaitPrefersRetryAfterAndUsesResetOnlyWhenExhausted() {
        assertEquals(42L, githubRateLimitWaitSeconds("0", "42", "9999", 100L))
        assertEquals(17L, githubRateLimitWaitSeconds("12", "17", null, 100L))
        assertEquals(60L, githubRateLimitWaitSeconds("0", null, "160", 100L))
        assertEquals(1L, githubRateLimitWaitSeconds("0", null, "99", 100L))
        assertEquals(42L, githubRateLimitWaitSeconds("12", "42", "9999", 100L))
        assertNull(githubRateLimitWaitSeconds("0", "invalid", "invalid", 100L))
    }

    @Test
    fun verificationUriMustUseTrustedGithubHttpsOrigin() {
        org.junit.Assert.assertTrue(isAllowedGitHubVerificationUri("https://github.com/login/device"))
        org.junit.Assert.assertFalse(isAllowedGitHubVerificationUri("http://github.com/login/device"))
        org.junit.Assert.assertFalse(isAllowedGitHubVerificationUri("https://github.com.evil.example/login"))
        org.junit.Assert.assertFalse(isAllowedGitHubVerificationUri("https://user@github.com/login/device"))
    }

}
