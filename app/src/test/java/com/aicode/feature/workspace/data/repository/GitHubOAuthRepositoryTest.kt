package com.aicode.feature.workspace.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aicode.core.security.KeystoreCipher
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.util.ArrayDeque

@RunWith(RobolectricTestRunner::class)
class GitHubOAuthRepositoryTest {
    companion object {
        @JvmStatic
        @BeforeClass
        fun requireSupportedRobolectricCryptoRuntime() {
            val conscryptLoadable = runCatching {
                Class.forName("org.conscrypt.Conscrypt")
                    .getMethod("isAvailable")
                    .invoke(null) as Boolean
            }.getOrDefault(false)
            assumeTrue(
                "Robolectric OAuth tests require a JVM with a loadable Conscrypt native library",
                conscryptLoadable
            )
        }
    }

    private val deviceCodeJson = """{"device_code":"device-secret","user_code":"ABCD-EFGH","verification_uri":"https://github.com/login/device","interval":5,"expires_in":900}"""

    @Test
    fun fakeHttpPendingThenDeniedStopsPollingWithoutPersistingToken() = runBlocking {
        val (repository, requests) = repositoryWithResponses(
            deviceCodeJson,
            """{"error":"authorization_pending"}""",
            """{"error":"access_denied"}"""
        )
        val code = repository.requestDeviceCode("public-client-id")
        assertEquals("ABCD-EFGH", code.userCode)
        assertTrue(isAllowedGitHubVerificationUri(code.verificationUri))

        var remainingUpdates = 0
        try {
            repository.pollDeviceCode("public-client-id", code) { remainingUpdates++ }
            throw AssertionError("Expected denied authorization")
        } catch (_: IOException) {
            // Expected: user denial must stop polling and never persist credentials.
        }

        assertEquals(2, remainingUpdates)
        assertEquals(3, requests.size)
        val deviceRequestBody = Buffer().also { requests.first().body!!.writeTo(it) }.readUtf8()
        assertTrue(deviceRequestBody.contains("client_id=public-client-id"))
        assertTrue(deviceRequestBody.contains("scope="))
        assertTrue(deviceRequestBody.contains("repo"))
        assertTrue(deviceRequestBody.contains("read%3Auser"))
        assertFalse(requests.any { it.url.toString().contains("device-secret") })
    }

    @Test
    fun fakeHttpExpiredResponseStopsAuthorization() = runBlocking {
        val (repository, requests) = repositoryWithResponses(
            deviceCodeJson,
            """{"error":"expired_token"}"""
        )
        val code = repository.requestDeviceCode("public-client-id")
        try {
            repository.pollDeviceCode("public-client-id", code) {}
            throw AssertionError("Expected expired authorization")
        } catch (_: IOException) {
            // Expected: caller can offer to start a new Device Flow.
        }
        assertEquals(2, requests.size)
    }

    @Test
    fun fakeHttpPendingThenSuccessLoadsUserAndEncryptsTokenBeforePersistence() = runBlocking {
        mockkObject(KeystoreCipher)
        every { KeystoreCipher.encryptString("opaque-access-token") } returns "encrypted-access-token"
        try {
            val (repository, requests) = repositoryWithResponses(
                deviceCodeJson,
                """{"error":"authorization_pending"}""",
                """{"access_token":"opaque-access-token","token_type":"bearer","scope":"repo,read:user"}""",
                """{"login":"test-user","avatar_url":"https://avatars.githubusercontent.com/u/1","html_url":"https://github.com/test-user"}"""
            )
            val code = repository.requestDeviceCode("public-client-id")
            val account = repository.pollDeviceCode("public-client-id", code) {}
            assertEquals("test-user", account.login)
            assertEquals(4, requests.size)
            val tokenRequestBody = Buffer().also { requests[2].body!!.writeTo(it) }.readUtf8()
            assertFalse(tokenRequestBody.contains("opaque-access-token"))
            assertTrue(requests[3].header("Authorization").orEmpty().contains("opaque-access-token"))
            repository.disconnect()
        } finally {
            unmockkObject(KeystoreCipher)
        }
    }

    @Test
    fun createPullRequestUsesEncryptedTokenAndSubmitsValidatedJson() = runBlocking {
        mockkObject(KeystoreCipher)
        every { KeystoreCipher.encryptString("opaque-access-token") } returns "encrypted-access-token"
        every { KeystoreCipher.decryptString("encrypted-access-token") } returns "opaque-access-token"
        try {
            val (repository, requests) = repositoryWithResponses(
                deviceCodeJson,
                """{"access_token":"opaque-access-token","token_type":"bearer","scope":"repo,read:user"}""",
                """{"login":"test-user","avatar_url":"https://avatars.githubusercontent.com/u/1","html_url":"https://github.com/test-user"}""",
                """{"name":"feature","commit":{"sha":"0123456789012345678901234567890123456789"}}""",
                """{"number":17,"title":"Ship feature","html_url":"https://github.com/owner/repo/pull/17"}"""
            )
            repository.disconnect()
            val code = repository.requestDeviceCode("public-client-id")
            repository.pollDeviceCode("public-client-id", code) {}

            val remoteSha = repository.branchHeadSha("owner/repo", "feature")
            val pullRequest = repository.createPullRequest(
                fullName = "owner/repo",
                title = "Ship feature",
                body = "Validated summary",
                head = "feature",
                base = "main"
            )

            assertEquals("0123456789012345678901234567890123456789", remoteSha)
            assertEquals(17, pullRequest.number)
            assertEquals("https://github.com/owner/repo/pull/17", pullRequest.htmlUrl)
            assertEquals("GET", requests[3].method)
            assertTrue(requests[3].header("Authorization").orEmpty().contains("opaque-access-token"))
            assertEquals("POST", requests[4].method)
            assertTrue(requests[4].url.encodedPath.endsWith("/repos/owner/repo/pulls"))
            assertTrue(requests[4].header("Authorization").orEmpty().contains("opaque-access-token"))
            val payload = JSONObject(Buffer().also { requests[4].body!!.writeTo(it) }.readUtf8())
            assertEquals("feature", payload.getString("head"))
            assertEquals("main", payload.getString("base"))
            assertEquals("Ship feature", payload.getString("title"))
            assertFalse(payload.toString().contains("opaque-access-token"))
            repository.disconnect()
        } finally {
            unmockkObject(KeystoreCipher)
        }
    }

    private fun repositoryWithResponses(vararg bodies: String): Pair<GitHubOAuthRepository, MutableList<Request>> {
        val pendingBodies = ArrayDeque(bodies.toList())
        val requests = mutableListOf<Request>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val body = checkNotNull(pendingBodies.pollFirst()) { "Unexpected HTTP request: ${request.url}" }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val context = ApplicationProvider.getApplicationContext<Context>()
        return GitHubOAuthRepository(context, client).apply { pollDelay = {} } to requests
    }
}
