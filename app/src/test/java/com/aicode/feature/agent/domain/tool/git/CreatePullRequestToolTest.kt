package com.aicode.feature.agent.domain.tool.git

import com.aicode.feature.workspace.data.repository.isSafeGitRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CreatePullRequestToolTest {
    @Test
    fun githubRemoteParserAcceptsHttpsAndSshForms() {
        assertEquals("owner/repo", parseGitHubRepositoryFullName("https://github.com/owner/repo.git"))
        assertEquals("owner/repo", parseGitHubRepositoryFullName("git@github.com:owner/repo.git"))
        assertEquals("owner/repo", parseGitHubRepositoryFullName("ssh://git@github.com/owner/repo"))
    }

    @Test
    fun githubRemoteParserRejectsCredentialBearingAndLookalikeHosts() {
        assertNull(parseGitHubRepositoryFullName("https://token@github.com/owner/repo.git"))
        assertNull(parseGitHubRepositoryFullName("https://github.com.evil.example/owner/repo.git"))
        assertNull(parseGitHubRepositoryFullName("https://evil.example/owner/repo.git"))
        assertNull(parseGitHubRepositoryFullName("http://github.com/owner/repo.git"))
        assertNull(parseGitHubRepositoryFullName("https://github.com/owner/repo/tree/main"))
    }

    @Test
    fun pullRequestBranchValidationRejectsShellAndGitPathTricks() {
        assertEquals(true, isSafeGitRef("feature/account-sync"))
        assertEquals(true, isSafeGitRef("release-1.2"))
        assertFalse(isSafeGitRef("../main"))
        assertFalse(isSafeGitRef("main;touch-file"))
        assertFalse(isSafeGitRef("/main"))
        assertFalse(isSafeGitRef("feature//child"))
    }
}
