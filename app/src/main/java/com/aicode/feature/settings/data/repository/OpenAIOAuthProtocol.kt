package com.aicode.feature.settings.data.repository

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** Pure helpers for the documented Sign in with ChatGPT open-source client flow. */
internal object OpenAIOAuthProtocol {
    const val ISSUER = "https://auth.openai.com"
    const val RESOURCE = "https://api.openai.com/v1"
    const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
    const val CALLBACK_PATH = "/auth/callback"
    const val PLAN_USAGE_SCOPE = "chatgpt.tokens.use.direct"

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

    fun authorizationUrl(
        redirectUri: String,
        state: String,
        nonce: String,
        challenge: String,
        hostId: String,
        clientId: String = DYNAMIC_CLIENT_ID,
        accountName: String? = null,
        loginHint: String? = null
    ): HttpUrl {
        val builder = "$ISSUER/api/accounts/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("ext_agent_host_id", hostId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", "openid profile email offline_access resource.invoke $PLAN_USAGE_SCOPE")
            .addQueryParameter("resource", RESOURCE)
            .addQueryParameter("state", state)
            .addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge)
        accountName?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("agent_name_hint", it) }
        loginHint?.takeIf(String::isNotBlank)?.let { builder.addQueryParameter("login_hint", it) }
        return builder.build()
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

    fun planUsageGranted(scopes: Set<String>): Boolean =
        PLAN_USAGE_SCOPE in scopes && "resource.invoke" in scopes

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
