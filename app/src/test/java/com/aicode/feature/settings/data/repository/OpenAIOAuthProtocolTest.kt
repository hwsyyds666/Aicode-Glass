package com.aicode.feature.settings.data.repository

import okhttp3.HttpUrl.Companion.toHttpUrl
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
    fun authorizationUrlMatchesOfficialCodexDesktopContract() {
        val url = OpenAIOAuthProtocol.authorizationUrl(
            redirectUri = "http://localhost:1455/auth/callback",
            state = "state-value",
            challenge = "challenge-value"
        )
        // 与 cockpit-tools codex_oauth.rs 一致：授权地址包在官方 hosted login 里，
        // 内层才是 auth.openai.com/oauth/authorize；固定 Codex client 显示“登录到 Codex”。
        assertEquals("chatgpt.com", url.host)
        assertEquals("/codex/desktop-auth", url.encodedPath)
        assertEquals("true", url.queryParameter("codex_streamlined_login"))
        assertEquals("1", url.queryParameter("no_universal_links"))
        val authorize = OpenAIOAuthProtocol.HOSTED_AUTH_ENDPOINT.toHttpUrl().newBuilder().build()
        assertNotNull(authorize)
        val inner = url.queryParameter("authorize_url")!!.toHttpUrl()
        assertEquals("auth.openai.com", inner.host)
        assertEquals("/oauth/authorize", inner.encodedPath)
        assertEquals(OpenAIOAuthProtocol.CODEX_DESKTOP_CLIENT_ID, inner.queryParameter("client_id"))
        assertEquals("http://localhost:1455/auth/callback", inner.queryParameter("redirect_uri"))
        assertEquals("state-value", inner.queryParameter("state"))
        assertEquals("S256", inner.queryParameter("code_challenge_method"))
        assertEquals("challenge-value", inner.queryParameter("code_challenge"))
        assertEquals("Codex Desktop", inner.queryParameter("originator"))
        assertEquals("true", inner.queryParameter("codex_cli_simplified_flow"))
        assertEquals("true", inner.queryParameter("codex_streamlined_login"))
        assertEquals(
            OpenAIOAuthProtocol.CODEX_SCOPES,
            inner.queryParameter("scope")
        )
        assertTrue(inner.queryParameterNames.containsAll(setOf("id_token_add_organizations")))
        // 旧 SiWC 动态注册参数必须彻底消失，否则授权页又变成注册第三方应用
        assertEquals(setOf("authorize_url", "codex_streamlined_login", "no_universal_links"), url.queryParameterNames)
        assertFalse("nonce" in inner.queryParameterNames)
        assertFalse("agent_name_hint" in inner.queryParameterNames)
        assertFalse("ext_agent_host_id" in inner.queryParameterNames)
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
        // Codex Desktop 授权拿到的 scope（不含 SiWC 专属 scope）同样视为可用
        assertTrue(OpenAIOAuthProtocol.planUsageGranted(setOf(
            "openid", "profile", "email", "offline_access",
            "api.connectors.read", "api.connectors.invoke"
        )))
        assertFalse(OpenAIOAuthProtocol.planUsageGranted(setOf("openid", "api.connectors.read")))
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
