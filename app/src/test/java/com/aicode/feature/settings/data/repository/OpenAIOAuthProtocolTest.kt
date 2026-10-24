package com.aicode.feature.settings.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class OpenAIOAuthProtocolTest {
    @Test
    fun pkceAndNonceAreFreshUrlSafeValues() {
        val first = OpenAIOAuthProtocol.newPkce()
        val second = OpenAIOAuthProtocol.newPkce()
        assertNotEquals(first.verifier, second.verifier)
        assertNotEquals(first.challenge, second.challenge)
        assertTrue(first.verifier.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertTrue(first.challenge.matches(Regex("[A-Za-z0-9_-]{43}")))
        assertNotEquals(OpenAIOAuthProtocol.newOpaqueValue(), OpenAIOAuthProtocol.newOpaqueValue())
    }

    @Test
    fun authorizationUrlUsesOfficialLoopbackContractAndScopes() {
        val url = OpenAIOAuthProtocol.authorizationUrl(
            redirectUri = "http://127.0.0.1:54321/auth/callback",
            state = "state-value",
            nonce = "nonce-value",
            challenge = "challenge-value",
            hostId = "urn:uuid:test",
            accountName = "Aicode-Glass"
        )
        assertEquals("auth.openai.com", url.host)
        assertEquals("dynamic_agent_client", url.queryParameter("client_id"))
        assertEquals("http://127.0.0.1:54321/auth/callback", url.queryParameter("redirect_uri"))
        assertTrue(url.queryParameter("scope").orEmpty().contains(OpenAIOAuthProtocol.PLAN_USAGE_SCOPE))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals("Aicode-Glass", url.queryParameter("agent_name_hint"))
        assertNull(url.queryParameter("access_token"))
        assertNull(url.queryParameter("id_token_hint"))
    }

    @Test
    fun callbackParserAcceptsOnlyExactRouteAndRejectsDuplicateParameters() {
        val callback = OpenAIOAuthProtocol.parseCallbackTarget(
            "/auth/callback?code=abc%2B123&state=expected&scope=openid+profile"
        )
        assertNotNull(callback)
        assertEquals("abc+123", callback?.value("code"))
        assertEquals("openid profile", callback?.value("scope"))
        assertNull(OpenAIOAuthProtocol.parseCallbackTarget("/callback?code=a&state=b"))
        assertNull(OpenAIOAuthProtocol.parseCallbackTarget("/auth/callback?state=a&state=b"))
        assertNull(OpenAIOAuthProtocol.parseCallbackTarget("https://attacker.invalid/auth/callback?state=x"))
    }

    @Test
    fun constantTimeComparisonHasExactValueSemantics() {
        assertTrue(OpenAIOAuthProtocol.constantTimeEquals("same", "same"))
        assertFalse(OpenAIOAuthProtocol.constantTimeEquals("same", "diff"))
    }

    @Test
    fun planInferenceRequiresDirectPermissionAndResourceInvoke() {
        assertFalse(OpenAIOAuthProtocol.planUsageGranted(setOf("openid", "profile", "email")))
        assertFalse(OpenAIOAuthProtocol.planUsageGranted(setOf(OpenAIOAuthProtocol.PLAN_USAGE_SCOPE)))
        assertTrue(OpenAIOAuthProtocol.planUsageGranted(setOf(
            OpenAIOAuthProtocol.PLAN_USAGE_SCOPE,
            "resource.invoke",
            "offline_access"
        )))
    }

    @Test
    fun stableHostIdentityIsSharedAcrossAccountsAndMigratesLegacyValue() {
        val firstInstallationHost = "urn:uuid:${UUID.randomUUID()}"
        val unusedGeneratedHost = "urn:uuid:${UUID.randomUUID()}"
        assertEquals(
            firstInstallationHost,
            OpenAIOAuthProtocol.resolveStableHostId(firstInstallationHost, null, unusedGeneratedHost)
        )
        assertEquals(
            firstInstallationHost,
            OpenAIOAuthProtocol.resolveStableHostId(null, firstInstallationHost, unusedGeneratedHost)
        )
        assertEquals(
            firstInstallationHost,
            OpenAIOAuthProtocol.resolveStableHostId("invalid", firstInstallationHost, unusedGeneratedHost)
        )
        assertNotEquals(
            firstInstallationHost,
            OpenAIOAuthProtocol.resolveStableHostId(null, null, unusedGeneratedHost)
        )
        assertFalse(OpenAIOAuthProtocol.isSupportedHostId("urn:uuid:not-a-uuid"))
        assertFalse(OpenAIOAuthProtocol.isSupportedHostId("urn:uuid:00000000-0000-1000-8000-000000000000"))
    }

    @Test
    fun visibleAccountModelsExcludeHiddenAndMalformedEntriesPreservingOrder() {
        val models = OpenAIOAuthProtocol.visibleModels(listOf(
            OpenAIOAuthProtocol.ModelCatalogEntry("gpt-a", "GPT A", "list"),
            OpenAIOAuthProtocol.ModelCatalogEntry("gpt-hidden", "Hidden", "hide"),
            OpenAIOAuthProtocol.ModelCatalogEntry("gpt-a", "Duplicate", "list"),
            OpenAIOAuthProtocol.ModelCatalogEntry("", "No slug", "list"),
            OpenAIOAuthProtocol.ModelCatalogEntry("gpt-b", "GPT B", "list")
        ))
        assertEquals(listOf("gpt-a", "gpt-b"), models.map { it.slug })
        assertEquals(listOf("GPT A", "GPT B"), models.map { it.displayName })
    }
}
