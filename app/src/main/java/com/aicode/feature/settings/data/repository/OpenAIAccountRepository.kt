package com.aicode.feature.settings.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.aicode.R
import com.aicode.core.security.KeystoreCipher
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.database.AgentDatabase
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.domain.provider.ChatGptAccountTarget
import com.aicode.feature.settings.data.local.dao.OpenAIAccountDao
import com.aicode.feature.settings.data.local.entity.OpenAIAccountEntity
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val Context.openAIAccountPreferences by preferencesDataStore(name = "openai_account_prefs")
private val OPENAI_HOST_ID_KEY = stringPreferencesKey("stable_host_id")

data class OpenAIAccountSummary(
    val id: String,
    val providerId: String,
    val email: String,
    val displayName: String,
    val status: String,
    val lastError: String?,
    val planUsageAuthorized: Boolean,
    val enabled: Boolean,
    val models: List<OpenAIAccountModel>,
    val selectedModel: String,
    val planType: String? = null,
    val quotaPrimaryUsedPercent: Int? = null,
    val quotaPrimaryWindowSeconds: Long? = null,
    val quotaPrimaryResetAt: Long? = null,
    val quotaSecondaryUsedPercent: Int? = null,
    val quotaSecondaryWindowSeconds: Long? = null,
    val quotaSecondaryResetAt: Long? = null,
    val quotaUpdatedAt: Long? = null,
    val quotaError: String? = null,
    val localCalls: Int = 0,
    val localInputTokens: Long = 0,
    val localOutputTokens: Long = 0
)

data class OpenAIAccountModel(val slug: String, val displayName: String)
data class OpenAIAccountRefreshResult(val models: List<OpenAIAccountModel>, val warning: String? = null)

/**
 * Official Sign in with ChatGPT local profile store and Responses API provider bridge.
 * OAuth tokens are KeystoreCipher-encrypted in Room and never copied into provider settings,
 * logs, model context, backups, or browser URLs. Usage refresh uses the first-party but undocumented
 * WHAM endpoint also observed in the open-source Codex client; the endpoint may change without notice.
 */
@Singleton
class OpenAIAccountRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: AgentDatabase,
    private val accountDao: OpenAIAccountDao,
    private val providerRepository: AIProviderRepository,
    private val llmCallRecordDao: LlmCallRecordDao,
    client: OkHttpClient
) {
    private fun text(resourceId: Int, vararg args: Any): String = context.getString(resourceId, *args)

    private val http = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()

    fun observeAccounts(): Flow<List<OpenAIAccountSummary>> = combine(
        accountDao.observeAll(),
        providerRepository.getAllProviders(),
        llmCallRecordDao.getProviderStats(start = 0L).map { stats -> stats.associateBy { it.providerId } }
    ) { accounts, providers, statsByProvider ->
        accounts.map { account ->
            val provider = providers.firstOrNull { it.id == account.providerId }
            val usage = statsByProvider[account.providerId]
            OpenAIAccountSummary(
                id = account.id,
                providerId = account.providerId,
                email = account.email,
                displayName = account.displayName,
                status = account.status,
                lastError = account.lastError,
                planUsageAuthorized = OpenAIOAuthProtocol.planUsageGranted(account.scopesAsSet()),
                enabled = provider?.isEnabled == true,
                models = decodeModelCatalog(account.modelCatalogJson).ifEmpty {
                    provider?.models.orEmpty().map { OpenAIAccountModel(it, it) }
                },
                selectedModel = provider?.effectiveModel.orEmpty(),
                planType = account.planType,
                quotaPrimaryUsedPercent = account.quotaPrimaryUsedPercent,
                quotaPrimaryWindowSeconds = account.quotaPrimaryWindowSeconds,
                quotaPrimaryResetAt = account.quotaPrimaryResetAt,
                quotaSecondaryUsedPercent = account.quotaSecondaryUsedPercent,
                quotaSecondaryWindowSeconds = account.quotaSecondaryWindowSeconds,
                quotaSecondaryResetAt = account.quotaSecondaryResetAt,
                quotaUpdatedAt = account.quotaUpdatedAt,
                quotaError = account.quotaError,
                localCalls = usage?.calls ?: 0,
                localInputTokens = usage?.inputTokens ?: 0L,
                localOutputTokens = usage?.outputTokens ?: 0L
            )
        }
    }

    fun usageLimitMessage(): String = text(R.string.openai_error_usage_limit)
    fun noAvailableAccountMessage(model: String): String = text(R.string.openai_error_no_available_account, model)
    fun invalidProviderTypeMessage(): String = text(R.string.openai_error_missing_provider_type)
    fun missingCredentialsMessage(): String = text(R.string.openai_error_missing_credentials)

    /** Connects one local ChatGPT plan-usage profile, or reauthorizes that same profile. */
    suspend fun signIn(existingAccountId: String? = null): OpenAIAccountSummary {
        val previous = existingAccountId?.let { accountDao.getById(it) }
            ?: if (existingAccountId != null) throw OpenAIAccountException(text(R.string.openai_error_account_missing)) else null
        val savedIds = accountDao.getAllOnce().mapTo(mutableSetOf(), OpenAIAccountEntity::id)
        if (!OpenAIAccountSelectionPolicy.canConnect(savedIds, previous?.id)) {
            throw OpenAIAccountException(text(R.string.openai_error_single_profile))
        }
        val discovery = loadDiscovery()
        val state = OpenAIOAuthProtocol.newOpaqueValue()
        val nonce = OpenAIOAuthProtocol.newOpaqueValue()
        val pkce = OpenAIOAuthProtocol.newPkce()
        // The OAuth client registration is account-scoped, but ext_agent_host_id is installation-scoped.
        // Reuse one persisted ID across all account registrations and reauthorizations on this device.
        val hostId = stableHostId()
        val previousIsBackupPlaceholder = previous?.let {
            it.encryptedAccessToken.isBlank() && it.encryptedRefreshToken.isBlank()
        } == true

        val server = ServerSocket()
        try {
            server.reuseAddress = false
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
            server.soTimeout = CALLBACK_TIMEOUT_MS
            val redirectUri = "http://127.0.0.1:${server.localPort}${OpenAIOAuthProtocol.CALLBACK_PATH}"
            val knownClientId = previous?.clientId ?: OpenAIOAuthProtocol.DYNAMIC_CLIENT_ID
            val authUrl = OpenAIOAuthProtocol.authorizationUrl(
                redirectUri = redirectUri,
                state = state,
                nonce = nonce,
                challenge = pkce.challenge,
                hostId = hostId,
                clientId = knownClientId,
                accountName = if (previous == null) APP_NAME else null,
                loginHint = previous?.email
            )

            val callback = coroutineScope {
                val listener = async(Dispatchers.IO) { awaitCallback(server, state) }
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(authUrl.toString()))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    withTimeout(CALLBACK_TIMEOUT_MS.toLong()) { listener.await() }
                } catch (cancelled: CancellationException) {
                    runCatching { server.close() }
                    listener.cancelAndJoin()
                    throw cancelled
                } finally {
                    server.close()
                }
            }
            callback.value("error")?.let { error ->
                throw OpenAIAccountException(
                    text(if (error == "access_denied") R.string.openai_error_auth_cancelled else R.string.openai_error_auth_incomplete, error)
                )
            }
            val code = callback.value("code")?.takeIf(String::isNotBlank)
                ?: throw OpenAIAccountException(text(R.string.openai_error_auth_code_missing))
            val issuedClientId = callback.value("client_id")?.takeIf(String::isNotBlank)
            val clientId = when {
                previous != null -> {
                    if (issuedClientId != null && issuedClientId != previous.clientId) {
                        throw OpenAIAccountException(text(R.string.openai_error_client_mismatch))
                    }
                    previous.clientId
                }
                else -> issuedClientId?.takeIf { it != OpenAIOAuthProtocol.DYNAMIC_CLIENT_ID }
                    ?: throw OpenAIAccountException(text(R.string.openai_error_client_id_missing))
            }
            val tokens = exchangeAuthorizationCode(discovery.tokenEndpoint, clientId, code, pkce.verifier, redirectUri)
            val identity = verifyIdentity(tokens.idToken, clientId, nonce, discovery.jwksUri)
            if (previous != null && !previousIsBackupPlaceholder && identity.subject != previous.subject) {
                throw OpenAIAccountException(text(R.string.openai_error_identity_mismatch))
            }
            val grantedScopes = tokens.scope.split(Regex("\\s+")).filter(String::isNotBlank).toSet()
            val planUsageAuthorized = OpenAIOAuthProtocol.planUsageGranted(grantedScopes)
            val canUsePlan = planUsageAuthorized

            val priorIdentity = accountDao.getByIdentity(identity.subject, clientId)
            val shouldReuseExistingIdentity = previousIsBackupPlaceholder && priorIdentity != null
            val accountId = when {
                shouldReuseExistingIdentity -> priorIdentity!!.id
                previous != null -> previous.id
                priorIdentity != null -> priorIdentity.id
                else -> UUID.randomUUID().toString()
            }
            val providerId = when {
                shouldReuseExistingIdentity -> priorIdentity!!.providerId
                previous != null -> previous.providerId
                priorIdentity != null -> priorIdentity.providerId
                else -> "$PROVIDER_ID_PREFIX$accountId"
            }
            val now = System.currentTimeMillis()
            val modelResult = when {
                !canUsePlan -> Result.success(emptyList<OpenAIAccountModel>())
                tokens.refreshToken.isBlank() -> Result.failure(OpenAIAccountException(text(R.string.openai_error_refresh_token_missing)))
                else -> try {
                    Result.success(fetchModelCatalog(tokens.accessToken))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
            val models = modelResult.getOrDefault(emptyList())
            val connectedStatus = if (canUsePlan && tokens.refreshToken.isBlank()) {
                OpenAIAccountEntity.STATUS_NEEDS_LOGIN
            } else {
                OpenAIAccountEntity.STATUS_CONNECTED
            }
            val account = OpenAIAccountEntity(
                id = accountId,
                providerId = providerId,
                subject = identity.subject,
                email = identity.email.ifBlank { previous?.email.orEmpty() },
                displayName = identity.name.ifBlank { identity.email.ifBlank { previous?.displayName ?: text(R.string.openai_account_default_name) } },
                clientId = clientId,
                encryptedAccessToken = KeystoreCipher.encryptString(tokens.accessToken),
                encryptedRefreshToken = KeystoreCipher.encryptString(tokens.refreshToken),
                encryptedIdToken = KeystoreCipher.encryptString(tokens.idToken),
                scopes = grantedScopes.sorted().joinToString(" "),
                accessTokenExpiresAt = now + tokens.expiresInSeconds.coerceAtLeast(60) * 1000,
                hostId = hostId,
                modelCatalogJson = encodeModelCatalog(models),
                status = connectedStatus,
                lastError = modelResult.exceptionOrNull()?.message
                    ?: modelResult.exceptionOrNull()?.let { text(R.string.openai_warning_model_catalog_unavailable) },
                chatgptAccountId = identity.chatgptAccountId,
                createdAt = previous?.createdAt ?: priorIdentity?.createdAt ?: now,
                updatedAt = now
            )
            val oldProvider = providerRepository.getProviderById(providerId)
            val desiredEnabled = if (previousIsBackupPlaceholder) {
                previous!!.restoreProviderEnabled
            } else {
                oldProvider?.isEnabled ?: true
            }
            val modelSlugs = models.map(OpenAIAccountModel::slug)
            val selected = oldProvider?.effectiveModel?.takeIf { it in modelSlugs } ?: modelSlugs.firstOrNull().orEmpty()
            val provider = AIProviderConfig(
                id = providerId,
                name = accountProviderName(account),
                type = ProviderType.OPENAI,
                apiKey = providerMarker(accountId),
                baseUrl = OPENAI_BASE_URL,
                defaultModel = selected,
                models = modelSlugs,
                selectedModel = selected,
                isEnabled = canUsePlan && connectedStatus == OpenAIAccountEntity.STATUS_CONNECTED && desiredEnabled,
                useResponseApi = true,
                sortOrder = oldProvider?.sortOrder ?: -1
            )
            try {
                database.withTransaction {
                    accountDao.upsert(account)
                    providerRepository.saveProvider(provider)
                    if (shouldReuseExistingIdentity && previous != null && previous.id != accountId) {
                        providerRepository.deleteProvider(previous.providerId)
                        accountDao.delete(previous.id)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FileLogger.e(TAG, "OpenAI account metadata save failed", error)
                throw OpenAIAccountException(text(R.string.openai_error_secure_storage_failed))
            }
            if (modelResult.isFailure) {
                FileLogger.w(TAG, "OpenAI account connected; model catalog refresh will be retried")
            }
            try {
                refreshUsage(account.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Login and model access do not depend on this undocumented usage endpoint.
            }
            return OpenAIAccountSummary(
                id = account.id,
                providerId = providerId,
                email = account.email,
                displayName = account.displayName,
                status = account.status,
                lastError = account.lastError,
                planUsageAuthorized = planUsageAuthorized,
                enabled = provider.isEnabled,
                models = models,
                selectedModel = selected,
                planType = account.planType,
                quotaPrimaryUsedPercent = account.quotaPrimaryUsedPercent,
                quotaPrimaryWindowSeconds = account.quotaPrimaryWindowSeconds,
                quotaPrimaryResetAt = account.quotaPrimaryResetAt,
                quotaSecondaryUsedPercent = account.quotaSecondaryUsedPercent,
                quotaSecondaryWindowSeconds = account.quotaSecondaryWindowSeconds,
                quotaSecondaryResetAt = account.quotaSecondaryResetAt,
                quotaUpdatedAt = account.quotaUpdatedAt,
                quotaError = account.quotaError,
                localCalls = 0,
                localInputTokens = 0L,
                localOutputTokens = 0L
            )
        } catch (error: IOException) {
            throw OpenAIAccountException(text(R.string.openai_error_callback_failed))
        } finally {
            runCatching { server.close() }
        }
    }

    suspend fun accessToken(accountId: String, allowUsagePaused: Boolean = false): String {
        val lock = refreshLocks.getOrPut(accountId) { Mutex() }
        return lock.withLock {
            var account = accountDao.getById(accountId) ?: throw OpenAIAccountException(text(R.string.openai_error_account_deleted_select))
            if (account.status == OpenAIAccountEntity.STATUS_USAGE_PAUSED &&
                account.usageCooldownUntil?.let { it <= System.currentTimeMillis() } == true
            ) {
                resumeAfterCooldown(account)
                account = accountDao.getById(accountId)
                    ?: throw OpenAIAccountException(text(R.string.openai_error_account_missing))
            }
            when (account.status) {
                OpenAIAccountEntity.STATUS_NEEDS_LOGIN ->
                    throw OpenAIAccountException(text(R.string.openai_error_reauthorize))
                OpenAIAccountEntity.STATUS_USAGE_PAUSED -> if (!allowUsagePaused) {
                    throw OpenAIAccountException(text(R.string.openai_error_usage_paused))
                }
            }
            if (!OpenAIOAuthProtocol.planUsageGranted(account.scopesAsSet())) {
                throw OpenAIAccountException(text(R.string.openai_error_plan_scope_missing))
            }
            if (account.accessTokenExpiresAt > System.currentTimeMillis() + TOKEN_REFRESH_MARGIN_MS) {
                return@withLock decryptToken(account.encryptedAccessToken)
                    .takeIf(String::isNotBlank)
                    ?: markNeedsLogin(account, text(R.string.openai_error_access_token_decrypt))
            }
            account = accountDao.getById(accountId) ?: throw OpenAIAccountException(text(R.string.openai_error_account_missing))
            refreshTokens(account)
        }
    }

    suspend fun refreshModels(accountId: String): OpenAIAccountRefreshResult {
        val token = accessToken(accountId, allowUsagePaused = true)
        val models = fetchModelCatalog(token)
        val account = accountDao.getById(accountId) ?: throw OpenAIAccountException(text(R.string.openai_error_account_missing))
        val oldProvider = providerRepository.getProviderById(account.providerId)
        val modelSlugs = models.map(OpenAIAccountModel::slug)
        val selected = oldProvider?.effectiveModel?.takeIf { it in modelSlugs } ?: modelSlugs.firstOrNull().orEmpty()
        val now = System.currentTimeMillis()
        database.withTransaction {
            providerRepository.saveProvider(
                accountProviderConfig(account, oldProvider, modelSlugs, selected)
            )
            accountDao.upsert(account.copy(
                modelCatalogJson = encodeModelCatalog(models),
                status = if (account.status == OpenAIAccountEntity.STATUS_USAGE_PAUSED) {
                    OpenAIAccountEntity.STATUS_USAGE_PAUSED
                } else {
                    OpenAIAccountEntity.STATUS_CONNECTED
                },
                lastError = if (account.status == OpenAIAccountEntity.STATUS_USAGE_PAUSED) account.lastError else null,
                updatedAt = now
            ))
        }
        return OpenAIAccountRefreshResult(models)
    }

    /** Refreshes plan and rate-limit windows from ChatGPT's first-party, undocumented WHAM endpoint. */
    internal suspend fun refreshUsage(accountId: String): OpenAIUsageSnapshot {
        val token = accessToken(accountId, allowUsagePaused = true)
        val account = accountDao.getById(accountId)
            ?: throw OpenAIAccountException(text(R.string.openai_error_account_missing))
        val request = Request.Builder()
            .url(OPENAI_USAGE_URL)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .apply { account.chatgptAccountId?.takeIf(String::isNotBlank)?.let { header("ChatGPT-Account-Id", it) } }
            .get()
            .build()
        val response = try {
            execute(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            val message = text(R.string.openai_error_quota_network)
            storeQuotaError(account, message)
            throw OpenAIAccountException(message)
        }
        if (response.code !in 200..299) {
            val message = text(R.string.openai_error_quota_http, response.code)
            storeQuotaError(account, message)
            throw OpenAIAccountException(message)
        }
        val now = System.currentTimeMillis()
        val snapshot = OpenAIUsageProtocol.parse(response.body, now / 1000)
            ?.takeIf { it.primary != null || it.secondary != null }
            ?: run {
                val message = text(R.string.openai_error_quota_unavailable)
                storeQuotaError(account, message)
                throw OpenAIAccountException(message)
            }
        val latest = accountDao.getById(accountId)
            ?: throw OpenAIAccountException(text(R.string.openai_error_account_missing))
        accountDao.upsert(latest.copy(
            planType = snapshot.planType ?: latest.planType,
            quotaPrimaryUsedPercent = snapshot.primary?.usedPercent,
            quotaPrimaryWindowSeconds = snapshot.primary?.durationSeconds,
            quotaPrimaryResetAt = snapshot.primary?.resetAtEpochSeconds,
            quotaSecondaryUsedPercent = snapshot.secondary?.usedPercent,
            quotaSecondaryWindowSeconds = snapshot.secondary?.durationSeconds,
            quotaSecondaryResetAt = snapshot.secondary?.resetAtEpochSeconds,
            quotaUpdatedAt = now,
            quotaError = null,
            updatedAt = now
        ))
        return snapshot
    }

    /** Resolves only the profile explicitly selected by the provider; never selects another account. */
    suspend fun routeForModel(preferredAccountId: String, model: String): ChatGptAccountTarget? {
        // Fail closed when upgrading a database that already contains legacy multi-account rows.
        // The supported SIWC integration is a single local profile and must never become a quota pool.
        val savedIds = accountDao.getAllOnce().mapTo(mutableSetOf(), OpenAIAccountEntity::id)
        if (!OpenAIAccountSelectionPolicy.canRoute(savedIds, preferredAccountId)) return null
        val account = accountDao.getById(preferredAccountId) ?: return null
        val provider = providerRepository.getProviderById(account.providerId) ?: return null
        val modelSet = provider.models.toSet().ifEmpty {
            decodeModelCatalog(account.modelCatalogJson).map(OpenAIAccountModel::slug).toSet()
        }
        if (model !in modelSet || !OpenAIOAuthProtocol.planUsageGranted(account.scopesAsSet())) return null
        val token = try {
            accessToken(account.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val refreshedAccount = accountDao.getById(preferredAccountId) ?: return null
        val refreshedProvider = providerRepository.getProviderById(refreshedAccount.providerId) ?: return null
        if (!refreshedProvider.isEnabled || refreshedAccount.status != OpenAIAccountEntity.STATUS_CONNECTED) return null
        return ChatGptAccountTarget(
            refreshedAccount.id,
            refreshedAccount.providerId,
            token,
            refreshedAccount.email.ifBlank { refreshedAccount.displayName }
        )
    }

    suspend fun setEnabled(accountId: String, enabled: Boolean) {
        val account = accountDao.getById(accountId) ?: throw OpenAIAccountException(text(R.string.openai_error_account_missing))
        if (enabled && accountDao.getAllOnce().any { it.id != accountId }) {
            throw OpenAIAccountException(text(R.string.openai_error_single_profile))
        }
        database.withTransaction {
            accountDao.upsert(account.copy(
                restoreProviderEnabled = enabled,
                usageCooldownUntil = if (enabled) null else account.usageCooldownUntil,
                updatedAt = System.currentTimeMillis()
            ))
            if (enabled) {
                if (account.status == OpenAIAccountEntity.STATUS_NEEDS_LOGIN) {
                    throw OpenAIAccountException(text(R.string.openai_error_reauthorize))
                }
                if (!OpenAIOAuthProtocol.planUsageGranted(account.scopesAsSet())) {
                    throw OpenAIAccountException(text(R.string.openai_error_plan_scope_missing))
                }
                if (decryptToken(account.encryptedRefreshToken).isBlank()) {
                    throw OpenAIAccountException(text(R.string.openai_error_refresh_token_missing))
                }
                if (account.status == OpenAIAccountEntity.STATUS_USAGE_PAUSED) {
                    accountDao.updateStatus(account.id, OpenAIAccountEntity.STATUS_CONNECTED, null, System.currentTimeMillis())
                }
            }
            providerRepository.setProviderEnabled(account.providerId, enabled)
        }
    }

    suspend fun setSelectedModel(accountId: String, model: String) {
        val account = accountDao.getById(accountId) ?: throw OpenAIAccountException(text(R.string.openai_error_account_missing))
        val provider = providerRepository.getProviderById(account.providerId)
            ?: throw OpenAIAccountException(text(R.string.openai_error_reauthorize))
        if (model !in provider.models) throw OpenAIAccountException(text(R.string.openai_error_model_catalog_invalid))
        providerRepository.setSelectedModel(account.providerId, model)
    }

    /** Deletes local credentials even if revocation is temporarily offline; returns remote confirmation. */
    suspend fun deleteAccount(accountId: String): Boolean {
        val account = accountDao.getById(accountId) ?: return true
        val revoked = try {
            revokeRefreshToken(account)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        database.withTransaction {
            providerRepository.deleteProvider(account.providerId)
            accountDao.delete(account.id)
        }
        refreshLocks.remove(accountId)
        return revoked
    }

    /** Called by StatefulAgentWorkflow only for a provider marked as one of our OAuth profiles. */
    suspend fun accessTokenForProvider(providerApiKey: String): String? {
        val accountId = accountIdFromProviderMarker(providerApiKey) ?: return null
        return accessToken(accountId)
    }

    private suspend fun resumeAfterCooldown(account: OpenAIAccountEntity) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            accountDao.upsert(account.copy(
                status = OpenAIAccountEntity.STATUS_CONNECTED,
                lastError = null,
                usageCooldownUntil = null,
                updatedAt = now
            ))
            providerRepository.setProviderEnabled(account.providerId, account.restoreProviderEnabled)
        }
    }

    private suspend fun storeQuotaError(account: OpenAIAccountEntity, message: String) {
        val latest = accountDao.getById(account.id) ?: return
        accountDao.upsert(latest.copy(quotaError = message, updatedAt = System.currentTimeMillis()))
        FileLogger.w(TAG, "OpenAI account usage refresh failed")
    }

    suspend fun removeOrphanedProvider(providerApiKey: String) {
        val accountId = accountIdFromProviderMarker(providerApiKey) ?: return
        if (accountDao.getById(accountId) == null) {
            throw OpenAIAccountException(text(R.string.openai_error_backup_without_token))
        }
    }

    private suspend fun refreshTokens(account: OpenAIAccountEntity): String {
        val refreshToken = decryptToken(account.encryptedRefreshToken)
        if (refreshToken.isBlank()) return markNeedsLogin(account, text(R.string.openai_error_refresh_token_decrypt))
        return try {
            val discovery = loadDiscovery()
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", account.clientId)
                .add("refresh_token", refreshToken)
                .add("resource", OpenAIOAuthProtocol.RESOURCE)
                .build()
            val response = postForm(discovery.tokenEndpoint, body)
            if (response.code !in 200..299) {
                val errorCode = oauthErrorCode(response.body)
                if (response.code == 401 || errorCode in INVALID_REFRESH_TOKEN_CODES) {
                    return markNeedsLogin(account, text(R.string.openai_error_login_expired))
                }
                throw OpenAIAccountException(text(R.string.openai_error_token_refresh_http, response.code, errorCode?.let { " ($it)" } ?: ""))
            }
            val json = JSONObject(response.body)
            val newAccess = json.optString("access_token")
            val newRefresh = json.optString("refresh_token").ifBlank { refreshToken }
            val newIdToken = json.optString("id_token").ifBlank { decryptToken(account.encryptedIdToken) }
            val newScopes = json.optString("scope").ifBlank { account.scopes }
            if (newAccess.isBlank() || newRefresh.isBlank()) {
                return markNeedsLogin(account, text(R.string.openai_error_token_response_incomplete))
            }
            // The refresh response may omit id_token. The already-verified stored identity remains
            // authoritative; revalidating its original exp would incorrectly expire the account.
            val identity = json.optString("id_token").takeIf(String::isNotBlank)?.let {
                verifyIdentity(it, account.clientId, null, discovery.jwksUri)
            }
            if (identity != null && identity.subject != account.subject) {
                return markNeedsLogin(account, text(R.string.openai_error_identity_mismatch))
            }
            val normalizedScopes = newScopes.split(Regex("\\s+")).filter(String::isNotBlank).distinct().sorted().joinToString(" ")
            val expiresIn = json.optLong("expires_in", 3600).coerceAtLeast(60)
            val updated = account.copy(
                email = identity?.email?.ifBlank { account.email } ?: account.email,
                displayName = identity?.name?.ifBlank { account.displayName } ?: account.displayName,
                chatgptAccountId = identity?.chatgptAccountId ?: account.chatgptAccountId,
                encryptedAccessToken = KeystoreCipher.encryptString(newAccess),
                encryptedRefreshToken = KeystoreCipher.encryptString(newRefresh),
                encryptedIdToken = KeystoreCipher.encryptString(newIdToken),
                scopes = normalizedScopes,
                accessTokenExpiresAt = System.currentTimeMillis() + expiresIn * 1000,
                status = if (account.status == OpenAIAccountEntity.STATUS_USAGE_PAUSED) {
                    OpenAIAccountEntity.STATUS_USAGE_PAUSED
                } else {
                    OpenAIAccountEntity.STATUS_CONNECTED
                },
                lastError = if (account.status == OpenAIAccountEntity.STATUS_USAGE_PAUSED) account.lastError else null,
                updatedAt = System.currentTimeMillis()
            )
            val refreshedScopes = normalizedScopes.split(Regex("\\s+")).filter(String::isNotBlank).toSet()
            database.withTransaction {
                accountDao.upsert(updated)
                if (!OpenAIOAuthProtocol.planUsageGranted(refreshedScopes)) {
                    providerRepository.setProviderEnabled(account.providerId, false)
                }
            }
            if (!OpenAIOAuthProtocol.planUsageGranted(refreshedScopes)) {
            throw OpenAIAccountException(text(R.string.openai_error_plan_scope_revoked))
            }
            newAccess
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: OpenAIAccountException) {
            throw error
        } catch (error: Exception) {
            FileLogger.w(TAG, "OpenAI token refresh failed (${error.javaClass.simpleName})")
            throw OpenAIAccountException(text(R.string.openai_error_refresh_failed))
        }
    }

    private suspend fun stableHostId(): String {
        var resolved = ""
        context.openAIAccountPreferences.edit { preferences ->
            val legacy = accountDao.getAllOnce().firstNotNullOfOrNull { account ->
                account.hostId.takeIf(OpenAIOAuthProtocol::isSupportedHostId)
            }
            resolved = OpenAIOAuthProtocol.resolveStableHostId(
                persisted = preferences[OPENAI_HOST_ID_KEY],
                legacyAccountHostId = legacy,
                generated = "urn:uuid:${UUID.randomUUID()}"
            )
            preferences[OPENAI_HOST_ID_KEY] = resolved
        }
        return resolved
    }

    private suspend fun markNeedsLogin(account: OpenAIAccountEntity, reason: String): Nothing {
        database.withTransaction {
            accountDao.upsert(account.copy(
                encryptedAccessToken = KeystoreCipher.encryptString(""),
                encryptedRefreshToken = KeystoreCipher.encryptString(""),
                accessTokenExpiresAt = 0L,
                status = OpenAIAccountEntity.STATUS_NEEDS_LOGIN,
                lastError = reason,
                updatedAt = System.currentTimeMillis()
            ))
            providerRepository.setProviderEnabled(account.providerId, false)
        }
        throw OpenAIAccountException(reason)
    }

    private suspend fun revokeRefreshToken(account: OpenAIAccountEntity): Boolean {
        val refresh = decryptToken(account.encryptedRefreshToken)
        if (refresh.isBlank()) return false
        val endpoint = loadDiscovery().revocationEndpoint ?: return false
        val body = FormBody.Builder()
            .add("token", refresh)
            .add("token_type_hint", "refresh_token")
            .add("client_id", account.clientId)
            .build()
        return postForm(endpoint, body).code in 200..299
    }

    private suspend fun exchangeAuthorizationCode(
        endpoint: String,
        clientId: String,
        code: String,
        verifier: String,
        redirectUri: String
    ): TokenResponse {
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", clientId)
            .add("code", code)
            .add("code_verifier", verifier)
            .add("redirect_uri", redirectUri)
            .add("resource", OpenAIOAuthProtocol.RESOURCE)
            .build()
        val response = postForm(endpoint, form)
        if (response.code !in 200..299) {
            throw OpenAIAccountException(
                if (response.code == 400) text(R.string.openai_error_auth_code_expired)
                else text(R.string.openai_error_token_exchange_http, response.code)
            )
        }
        val json = JSONObject(response.body)
        return TokenResponse(
            accessToken = json.optString("access_token"),
            refreshToken = json.optString("refresh_token"),
            idToken = json.optString("id_token"),
            scope = json.optString("scope"),
            expiresInSeconds = json.optLong("expires_in", 3600)
        ).also {
            if (it.accessToken.isBlank() || it.idToken.isBlank()) {
                throw OpenAIAccountException(text(R.string.openai_error_token_response_incomplete))
            }
        }
    }

    private suspend fun fetchModelCatalog(accessToken: String): List<OpenAIAccountModel> {
        val request = Request.Builder()
            .url("$OPENAI_BASE_URL/v1/models")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .get()
            .build()
        val response = execute(request)
        if (response.code !in 200..299) {
            throw OpenAIAccountException(
                if (response.code == 401) text(R.string.openai_error_login_required)
                else text(R.string.openai_error_model_list_http, response.code)
            )
        }
        val json = JSONObject(response.body)
        // Sign in with ChatGPT's account-specific catalog contract is intentionally strict:
        // never fall back to a different public API shape or display hidden models.
        val items = json.optJSONArray("models")
            ?: throw OpenAIAccountException(text(R.string.openai_error_model_catalog_invalid))
        val entries = buildList {
            for (index in 0 until items.length()) {
                val model = items.optJSONObject(index) ?: continue
                add(OpenAIOAuthProtocol.ModelCatalogEntry(
                    slug = model.optString("slug"),
                    displayName = model.optString("display_name"),
                    visibility = model.optString("visibility")
                ))
            }
        }
        return OpenAIOAuthProtocol.visibleModels(entries)
    }

    private suspend fun loadDiscovery(): Discovery {
        val response = execute(Request.Builder().url("${OpenAIOAuthProtocol.ISSUER}/.well-known/openid-configuration").get().build())
        if (response.code !in 200..299) throw OpenAIAccountException(text(R.string.openai_error_login_service_unavailable))
        val json = JSONObject(response.body)
        if (json.optString("issuer") != OpenAIOAuthProtocol.ISSUER) throw OpenAIAccountException(text(R.string.openai_error_issuer_invalid))
        fun endpoint(name: String, required: Boolean = true): String? {
            val value = json.optString(name).takeIf(String::isNotBlank) ?: return if (required) throw OpenAIAccountException(text(R.string.openai_error_discovery_field_missing, name)) else null
            val uri = runCatching { Uri.parse(value) }.getOrNull()
            if (uri?.scheme != "https" || uri.host != "auth.openai.com") throw OpenAIAccountException(text(R.string.openai_error_non_official_endpoint))
            return value
        }
        return Discovery(
            authorizationEndpoint = endpoint("authorization_endpoint")!!,
            tokenEndpoint = endpoint("token_endpoint")!!,
            jwksUri = endpoint("jwks_uri")!!,
            revocationEndpoint = endpoint("revocation_endpoint", required = false)
        )
    }

    private suspend fun verifyIdentity(idToken: String, clientId: String, expectedNonce: String?, jwksUrl: String): Identity {
        val segments = idToken.split('.')
        if (segments.size != 3) throw OpenAIAccountException(text(R.string.openai_error_identity_invalid))
        val header = runCatching { JSONObject(decodeBase64Url(segments[0]).toString(StandardCharsets.UTF_8)) }.getOrNull()
            ?: throw OpenAIAccountException(text(R.string.openai_error_identity_invalid))
        if (header.optString("alg") != "RS256") throw OpenAIAccountException(text(R.string.openai_error_identity_invalid))
        val kid = header.optString("kid").takeIf(String::isNotBlank)
            ?: throw OpenAIAccountException(text(R.string.openai_error_identity_invalid))
        val claims = runCatching { JSONObject(decodeBase64Url(segments[1]).toString(StandardCharsets.UTF_8)) }.getOrNull()
            ?: throw OpenAIAccountException(text(R.string.openai_error_identity_invalid))
        val jwks = execute(Request.Builder().url(jwksUrl).header("Accept", "application/json").get().build())
        if (jwks.code !in 200..299) throw OpenAIAccountException(text(R.string.openai_error_identity_keys_unavailable))
        val keys = JSONObject(jwks.body).optJSONArray("keys") ?: throw OpenAIAccountException(text(R.string.openai_error_identity_invalid))
        val jwk = (0 until keys.length()).asSequence().mapNotNull(keys::optJSONObject)
            .firstOrNull { it.optString("kid") == kid && it.optString("kty") == "RSA" && (it.optString("use").isBlank() || it.optString("use") == "sig") }
            ?: throw OpenAIAccountException(text(R.string.openai_error_identity_keys_unavailable))
        val modulus = BigInteger(1, decodeBase64Url(jwk.optString("n")))
        val exponent = BigInteger(1, decodeBase64Url(jwk.optString("e")))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
        val signature = Signature.getInstance("SHA256withRSA")
        signature.initVerify(publicKey)
        signature.update("${segments[0]}.${segments[1]}".toByteArray(StandardCharsets.US_ASCII))
        if (!signature.verify(decodeBase64Url(segments[2]))) throw OpenAIAccountException(text(R.string.openai_error_identity_invalid))

        val nowSeconds = System.currentTimeMillis() / 1000
        val audience = claims.opt("aud")
        val audienceMatches = when (audience) {
            is String -> audience == clientId
            is org.json.JSONArray -> (0 until audience.length()).any { audience.optString(it) == clientId }
            else -> false
        }
        if (claims.optString("iss") != OpenAIOAuthProtocol.ISSUER || !audienceMatches ||
            claims.optLong("exp", 0) <= nowSeconds - CLOCK_SKEW_SECONDS ||
            claims.optLong("iat", Long.MAX_VALUE) > nowSeconds + CLOCK_SKEW_SECONDS ||
            claims.optString("sub").isBlank() ||
            (claims.has("azp") && claims.optString("azp") != clientId) ||
            (expectedNonce != null && !OpenAIOAuthProtocol.constantTimeEquals(claims.optString("nonce"), expectedNonce))
        ) {
            throw OpenAIAccountException(text(R.string.openai_error_identity_invalid))
        }
        val authClaims = claims.optJSONObject("https://api.openai.com/auth")
        return Identity(
            subject = claims.optString("sub"),
            email = claims.optString("email"),
            name = claims.optString("name"),
            chatgptAccountId = authClaims?.optString("chatgpt_account_id")
                ?.takeIf(String::isNotBlank) ?: claims.optString("chatgpt_account_id").takeIf(String::isNotBlank)
        )
    }

    private suspend fun postForm(url: String, form: FormBody): HttpResult {
        val request = Request.Builder().url(url).header("Accept", "application/json").post(form).build()
        return execute(request)
    }

    private suspend fun execute(request: Request): HttpResult {
        val url = request.url
        if (!url.isHttps || url.host !in OFFICIAL_HOSTS || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty()
        ) {
            throw OpenAIAccountException(text(R.string.openai_error_request_non_official))
        }
        val response = http.newCall(request).awaitResponse()
        response.use {
            val body = it.body?.string().orEmpty()
            return HttpResult(it.code, body)
        }
    }

    private suspend fun awaitCallback(server: ServerSocket, expectedState: String): OpenAIOAuthProtocol.Callback {
        while (true) {
            val socket = server.accept()
            socket.use { client ->
                client.soTimeout = CALLBACK_CLIENT_READ_TIMEOUT_MS
                if (!client.inetAddress.isLoopbackAddress) return@use
                val requestTarget = readHttpRequestTarget(client)
                if (requestTarget == null) {
                    writeCallbackResponse(client, 400)
                    return@use
                }
                val callback = OpenAIOAuthProtocol.parseCallbackTarget(requestTarget)
                if (callback == null || !OpenAIOAuthProtocol.constantTimeEquals(callback.value("state").orEmpty(), expectedState)) {
                    writeCallbackResponse(client, 400)
                    return@use
                }
                writeCallbackResponse(client, 200)
                return callback
            }
        }
    }

    private fun readHttpRequestTarget(socket: Socket): String? {
        val out = ByteArrayOutputStream()
        val input = socket.getInputStream()
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (out.size() < MAX_CALLBACK_REQUEST_BYTES) {
            val next = input.read()
            if (next < 0) return null
            out.write(next)
            val bytes = out.toByteArray()
            if (bytes.size >= end.size && bytes.takeLast(end.size).toByteArray().contentEquals(end)) break
        }
        val firstLine = out.toString(StandardCharsets.US_ASCII.name()).substringBefore("\r\n")
        if (firstLine.length > MAX_CALLBACK_REQUEST_LINE) return null
        val pieces = firstLine.split(' ')
        if (pieces.size != 3 || pieces[0] != "GET" || pieces[2] != "HTTP/1.1") return null
        return pieces[1]
    }

    private fun writeCallbackResponse(socket: Socket, status: Int) {
        val title = android.text.Html.escapeHtml(text(R.string.openai_callback_title))
        val message = android.text.Html.escapeHtml(
            text(if (status == 200) R.string.openai_callback_success else R.string.openai_callback_failure)
        )
        val body = "<!doctype html><meta charset=utf-8><title>$title</title><p>$message</p>"
            .toByteArray(StandardCharsets.UTF_8)
        val label = if (status == 200) "OK" else "Bad Request"
        val headers = "HTTP/1.1 $status $label\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${body.size}\r\n\r\n"
        socket.getOutputStream().apply {
            write(headers.toByteArray(StandardCharsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private fun encodeModelCatalog(models: List<OpenAIAccountModel>): String = JSONArray().apply {
        models.forEach { model -> put(JSONObject().put("slug", model.slug).put("display_name", model.displayName)) }
    }.toString()

    private fun decodeModelCatalog(json: String): List<OpenAIAccountModel> = runCatching {
        val items = JSONArray(json)
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val slug = item.optString("slug").trim()
                val display = item.optString("display_name").trim()
                if (slug.isNotEmpty() && display.isNotEmpty()) add(OpenAIAccountModel(slug, display))
            }
        }.distinctBy(OpenAIAccountModel::slug)
    }.getOrDefault(emptyList())

    private fun OpenAIAccountEntity.scopesAsSet(): Set<String> = scopes.split(Regex("\\s+")).filter(String::isNotBlank).toSet()

    private fun oauthErrorCode(body: String): String? = runCatching {
        val json = JSONObject(body)
        when (val error = json.opt("error")) {
            is String -> error.takeIf(String::isNotBlank)
            is JSONObject -> error.optString("code").takeIf(String::isNotBlank)
                ?: error.optString("error").takeIf(String::isNotBlank)
            else -> null
        }
    }.getOrNull()

    private fun decryptToken(value: String): String = runCatching { KeystoreCipher.decryptString(value) }.getOrDefault("")

    private fun accountProviderConfig(
        account: OpenAIAccountEntity,
        old: AIProviderConfig?,
        models: List<String>,
        selected: String
    ) = AIProviderConfig(
        id = account.providerId,
        name = accountProviderName(account),
        type = ProviderType.OPENAI,
        apiKey = providerMarker(account.id),
        baseUrl = OPENAI_BASE_URL,
        defaultModel = selected,
        models = models,
        selectedModel = selected,
        isEnabled = old?.isEnabled ?: true,
        useResponseApi = true,
        sortOrder = old?.sortOrder ?: -1
    )

    private fun accountProviderName(account: OpenAIAccountEntity) =
        text(R.string.openai_account_provider_name, account.email.ifBlank { account.displayName })

    private fun providerMarker(accountId: String) = "$PROVIDER_MARKER_PREFIX$accountId"

    private fun decodeBase64Url(value: String): ByteArray = Base64.getUrlDecoder().decode(value)

    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    private data class Discovery(
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val jwksUri: String,
        val revocationEndpoint: String?
    )
    private class TokenResponse(
        val accessToken: String,
        val refreshToken: String,
        val idToken: String,
        val scope: String,
        val expiresInSeconds: Long
    ) {
        override fun toString(): String = "TokenResponse(tokens=[REDACTED], scope=$scope, expiresInSeconds=$expiresInSeconds)"
    }
    private data class Identity(val subject: String, val email: String, val name: String, val chatgptAccountId: String?)
    private data class HttpResult(val code: Int, val body: String)

    companion object {
        const val PROVIDER_MARKER_PREFIX = "aicode-openai-account:"
        const val PROVIDER_ID_PREFIX = "openai_account_"
        private const val OPENAI_BASE_URL = "https://api.openai.com/"
        private const val OPENAI_USAGE_URL = "https://chatgpt.com/backend-api/wham/usage"
        private const val APP_NAME = "Aicode-Glass"
        private const val CALLBACK_TIMEOUT_MS = 180_000
        private const val CALLBACK_CLIENT_READ_TIMEOUT_MS = 5_000
        private const val MAX_CALLBACK_REQUEST_BYTES = 16 * 1024
        private const val MAX_CALLBACK_REQUEST_LINE = 8 * 1024
        private const val HTTP_TIMEOUT_SECONDS = 30L
        private const val TOKEN_REFRESH_MARGIN_MS = 60_000L
        private const val CLOCK_SKEW_SECONDS = 30L
        private const val TAG = "OpenAIAccountRepository"
        private val OFFICIAL_HOSTS = setOf("auth.openai.com", "api.openai.com", "chatgpt.com")
        private val INVALID_REFRESH_TOKEN_CODES = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused"
        )

        fun isAccountProvider(provider: AIProviderConfig): Boolean = accountIdFromProviderMarker(provider.apiKey) != null
        fun accountIdFromProviderMarker(value: String): String? = value.takeIf { it.startsWith(PROVIDER_MARKER_PREFIX) }
            ?.removePrefix(PROVIDER_MARKER_PREFIX)
            ?.takeIf(String::isNotBlank)
    }
}

class OpenAIAccountException(message: String) : IllegalStateException(message)
