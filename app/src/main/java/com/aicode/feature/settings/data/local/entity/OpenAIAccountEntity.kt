package com.aicode.feature.settings.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local Sign in with ChatGPT profile. Every token field is KeystoreCipher ciphertext. */
@Entity(
    tableName = "openai_accounts",
    indices = [
        Index(value = ["providerId"], unique = true),
        Index(value = ["subject", "clientId"], unique = true)
    ]
)
data class OpenAIAccountEntity(
    @PrimaryKey val id: String,
    val providerId: String,
    val subject: String,
    val email: String,
    val displayName: String,
    val clientId: String,
    val encryptedAccessToken: String,
    val encryptedRefreshToken: String,
    val encryptedIdToken: String,
    val scopes: String,
    val accessTokenExpiresAt: Long,
    /** Installation-scoped host identity captured for this registration; shared by this install's accounts. */
    val hostId: String = "",
    /** Account-specific model catalog JSON: [{"slug":...,"display_name":...}]. */
    val modelCatalogJson: String = "[]",
    val status: String = STATUS_CONNECTED,
    val lastError: String? = null,
    /** Desired provider state retained for credential-free backup placeholders until reauthorization. */
    val restoreProviderEnabled: Boolean = true,
    val chatgptAccountId: String? = null,
    val planType: String? = null,
    val quotaPrimaryUsedPercent: Int? = null,
    val quotaPrimaryWindowSeconds: Long? = null,
    val quotaPrimaryResetAt: Long? = null,
    val quotaSecondaryUsedPercent: Int? = null,
    val quotaSecondaryWindowSeconds: Long? = null,
    val quotaSecondaryResetAt: Long? = null,
    val quotaUpdatedAt: Long? = null,
    val quotaError: String? = null,
    val usageCooldownUntil: Long? = null,
    val createdAt: Long,
    val updatedAt: Long
) {
    override fun toString(): String =
        "OpenAIAccountEntity(id=$id, providerId=$providerId, email=[REDACTED], status=$status, tokenFields=[REDACTED])"

    companion object {
        const val STATUS_CONNECTED = "CONNECTED"
        const val STATUS_NEEDS_LOGIN = "NEEDS_LOGIN"
        const val STATUS_USAGE_PAUSED = "USAGE_PAUSED"
    }
}
