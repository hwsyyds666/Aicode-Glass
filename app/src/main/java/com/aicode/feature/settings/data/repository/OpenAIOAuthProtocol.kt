package com.aicode.feature.settings.data.repository

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Pure helpers mirroring the official Codex Desktop OAuth contract (see cockpit-tools codex_oauth.rs). */
internal object OpenAIOAuthProtocol {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    /** Legacy dynamic-registration client; only kept so older stored accounts can still refresh. */
    const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
    /** Official Codex Desktop client id: the login page then reads “登录到 Codex”. */
    const val CODEX_DESKTOP_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
    /** Public client id used by the open-source Codex CLI; only a fallback for imported credentials. */
    const val CODEX_CLI_CLIENT_ID = "app_EMoamEEZ73f0CkXaX11ZibmR"
    const val ORIGINATOR = "Codex Desktop"
    const val HOSTED_AUTH_ENDPOINT = "https://chatgpt.com/codex/desktop-auth"
    const val CALLBACK_PATH = "/auth/callback"
    const val PLAN_USAGE_SCOPE = "chatgpt.tokens.use.direct"
    const val CODEX_SCOPES = "openid profile email offline_access api.connectors.read api.connectors.invoke"

    data class Pkce(val verifier: String, val challenge: String)
    data class ModelCatalogEntry(val slug: String, val displayName: String, val visibility: String)
    data class Callback(val values: Map<String, String>) {
        fun value(name: String): String? = values[name]
    }

    fun newOpaqueValue(): String = base64Url(ByteArray(32).also(SecureRandom()::nextBytes))

    fun newPkce(): Pkce {
        val verifier = newOpaqueValue()
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))
        return Pkce(verifier, challenge)
    }

    /**
     * Codex Desktop authorization URL, wrapped in the official hosted login page exactly like
     * the desktop client does: browser shows “登录到 Codex”, then redirects to the loopback
     * callback that the app listens on (fixed port 1455, fallback 1457 — same as Codex).
     */
    fun authorizationUrl(
        redirectUri: String,
        state: String,
        challenge: String,
        clientId: String = CODEX_DESKTOP_CLIENT_ID
    ): HttpUrl {
        val authorize = "$ISSUER/oauth/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", CODEX_SCOPES)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("id_token_add_organizations", "true")
            .addQueryParameter("codex_cli_simplified_flow", "true")
            .addQueryParameter("codex_streamlined_login", "true")
            .addQueryParameter("state", state)
            .addQueryParameter("originator", ORIGINATOR)
            .build()
        return HOSTED_AUTH_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("authorize_url", authorize.toString())
            .addQueryParameter("codex_streamlined_login", "true")
            .addQueryParameter("no_universal_links", "1")
            .build()
    }

    /** Parses only the exact loopback callback path; duplicate query keys are rejected. */
    fun parseCallbackTarget(requestTarget: String): Callback? {
        val pathAndQuery = requestTarget.takeIf { it.startsWith('/') } ?: return null
        val path = pathAndQuery.substringBefore('?').substringBefore('#')
        if (path != CALLBACK_PATH) return null
        val query = pathAndQuery.substringAfter('?', "").substringBefore('#')
        val values = linkedMapOf<String, String>()
        if (query.isNotEmpty()) {
            for (part in query.split('&')) {
                if (part.isEmpty()) continue
                val key = decodeFormComponent(part.substringBefore('=')) ?: return null
                val value = decodeFormComponent(part.substringAfter('=', "")) ?: return null
                if (key.isEmpty() || values.putIfAbsent(key, value) != null) return null
            }
        }
        return Callback(values)
    }

    fun constantTimeEquals(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(StandardCharsets.UTF_8), right.toByteArray(StandardCharsets.UTF_8))

    /** SiWC accounts carry explicit plan scopes; Codex Desktop accounts carry the connectors scope. */
    fun planUsageGranted(scopes: Set<String>): Boolean =
        (PLAN_USAGE_SCOPE in scopes && "resource.invoke" in scopes) ||
            "api.connectors.invoke" in scopes

    /** Host IDs identify this app installation, not an account; all registrations on one install reuse it. */
    fun resolveStableHostId(persisted: String?, legacyAccountHostId: String?, generated: String): String =
        sequenceOf(persisted, legacyAccountHostId, generated)
            .filterNotNull()
            .firstOrNull(::isSupportedHostId)
            ?: throw IllegalArgumentException("A valid UUIDv4 host ID is required")

    fun isSupportedHostId(value: String): Boolean {
        if (!value.startsWith("urn:uuid:")) return false
        val uuid = runCatching { UUID.fromString(value.removePrefix("urn:uuid:")) }.getOrNull() ?: return false
        return uuid.version() == 4
    }

    /** Keep only account-visible model entries, preserving OpenAI's order and slug identity. */
    fun visibleModels(entries: List<ModelCatalogEntry>): List<OpenAIAccountModel> = entries
        .asSequence()
        .filter { it.visibility == "list" }
        .map { OpenAIAccountModel(it.slug.trim(), it.displayName.trim()) }
        .filter { it.slug.isNotEmpty() && it.slug.length <= 200 && it.displayName.isNotEmpty() && it.displayName.length <= 200 }
        .distinctBy(OpenAIAccountModel::slug)
        .toList()

    private fun decodeFormComponent(value: String): String? =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrNull()

    private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
}
