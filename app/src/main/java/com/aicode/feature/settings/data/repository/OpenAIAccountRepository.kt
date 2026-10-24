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
import kotlinx.coroutines.CompletableDeferred
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
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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

/** 批量导入结果：成功导入的账号、逐条失败原因、以及只登记了邮箱待登录的条目。 */
data class OpenAIAccountImportOutcome(
    val imported: List<OpenAIAccountSummary> = emptyList(),
    val failures: List<String> = emptyList(),
    val pendingEmails: List<String> = emptyList()
)

/** 从某条导入来源解析出的凭据（至少需要 access_token 或 refresh_token）。 */
private data class ImportedCredential(
    val idToken: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val clientId: String = "",
    val email: String = "",
    val accountId: String = "",
    val displayName: String = ""
)

/**
 * 账号目录里的一个模型。
 *
 * @param paidOnly true 表示需要付费订阅（官方 `available_in_plans` 非空且不含 free）。
 *   用于额度调度：付费专属模型只路由给付费账号；免费模型优先消耗免费账号额度。
 */
data class OpenAIAccountModel(val slug: String, val displayName: String, val paidOnly: Boolean = false)
data class OpenAIAccountRefreshResult(val models: List<OpenAIAccountModel>, val warning: String? = null)

/**
 * A pending ChatGPT login. [authorizeUrl] is opened by the UI; completion comes either from the
 * loopback redirect or from the user pasting the callback URL back into the app.
 */
data class OpenAILoginSession(
    val sessionId: String,
    val authorizeUrl: String,
    val expiresAtMillis: Long,
    val accountId: String?
)

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
    private val accountAffinity: ChatGptAccountAffinity,
    client: OkHttpClient
) {
    private fun text(resourceId: Int, vararg args: Any): String = context.getString(resourceId, *args)

    private val http = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()
    private val refreshLocks = ConcurrentHashMap<String, Mutex>()
    private val pendingLogins = ConcurrentHashMap<String, PendingLogin>()

    private data class PendingLogin(
        val server: ServerSocket,
        val state: String,
        val pkce: OpenAIOAuthProtocol.Pkce,
        val discovery: Discovery,
        val previous: OpenAIAccountEntity?,
        val previousIsBackupPlaceholder: Boolean,
        val knownClientId: String,
        val redirectUri: String,
        val expiresAtMillis: Long,
        val callback: CompletableDeferred<OpenAIOAuthProtocol.Callback>
    )

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

    /**
     * 一次性快照：返回当前已连接（有凭据）的账号 id。
     * 供进入 ChatGPT 账号页时批量刷新额度使用，避免为了拿列表而常驻订阅。
     */
    suspend fun snapshotAccountIds(): List<String> = accountDao.getAllOnce()
        .filter { it.encryptedAccessToken.isNotBlank() || it.encryptedRefreshToken.isNotBlank() }
        .map { it.id }

    /** 某个账号当前可用的模型 slug 列表（用于判断是否需要刷新模型目录）。 */
    suspend fun modelsOf(accountId: String): List<String> {
        val account = accountDao.getById(accountId) ?: return emptyList()
        val catalog = decodeModelCatalog(account.modelCatalogJson)
        if (catalog.isNotEmpty()) return catalog.map { it.slug }
        return providerRepository.getProviderById(account.providerId)?.models.orEmpty()
    }

    fun usageLimitMessage(): String = text(R.string.openai_error_usage_limit)
    fun noAvailableAccountMessage(model: String): String = text(R.string.openai_error_no_available_account, model)
    fun invalidProviderTypeMessage(): String = text(R.string.openai_error_missing_provider_type)
    fun missingCredentialsMessage(): String = text(R.string.openai_error_missing_credentials)

    /** 免费订阅判定：档位缺失（还没拉到用量信息）时保守按非免费处理，避免误拦付费号。 */
    private fun isFreePlan(planType: String?): Boolean =
        planType?.trim()?.equals("free", ignoreCase = true) == true

    /**
     * 模型目录 TTL 刷新：打开模型选择器等入口时调用，目录超过 [MODEL_CATALOG_TTL_MS]
     * 的已连接账号会重新拉取一次官方 Codex 目录（官方上/下架模型后本地列表随之更新）。
     * 单个账号失败不影响其他账号；全程静默，不打断用户操作。
     */
    suspend fun refreshStaleAccountModels(maxAgeMs: Long = MODEL_CATALOG_TTL_MS) {
        val now = System.currentTimeMillis()
        val accounts = accountDao.getAllOnce()
        for (account in accounts) {
            if (account.status != OpenAIAccountEntity.STATUS_CONNECTED) continue
            if (now - account.updatedAt <= maxAgeMs) continue
            runCatching { refreshModels(account.id) }
                .onFailure { FileLogger.w(TAG, "TTL 刷新模型目录失败(${account.id}): ${it.message}") }
        }
    }

    /** Creates an authorization session and returns the URL to open. Nothing is opened here. */
    suspend fun beginLogin(existingAccountId: String? = null): OpenAILoginSession {
        val previous = existingAccountId?.let { accountDao.getById(it) }
            ?: if (existingAccountId != null) throw OpenAIAccountException(text(R.string.openai_error_account_missing)) else null
        val savedIds = accountDao.getAllOnce().mapTo(mutableSetOf(), OpenAIAccountEntity::id)
        if (!OpenAIAccountSelectionPolicy.canConnect(savedIds, previous?.id)) {
            throw OpenAIAccountException(
                text(R.string.openai_error_account_limit, OpenAIAccountSelectionPolicy.MAX_ACCOUNTS)
            )
        }
        val discovery = loadDiscovery()
        val state = OpenAIOAuthProtocol.newOpaqueValue()
        val pkce = OpenAIOAuthProtocol.newPkce()
        // The OAuth client registration is account-scoped, but the host id is installation-scoped.
        val hostId = stableHostId()
        val previousIsBackupPlaceholder = previous?.let {
            it.encryptedAccessToken.isBlank() && it.encryptedRefreshToken.isBlank()
        } == true

        // Official Codex Desktop contract: fixed loopback callback ports, "localhost" in the URI.
        val server = bindLoopbackCallbackServer()
        var registered = false
        val sessionId = UUID.randomUUID().toString()
        try {
            val redirectUri = "http://localhost:${server.localPort}${OpenAIOAuthProtocol.CALLBACK_PATH}"
            val knownClientId = previous?.clientId
                ?.takeUnless { it == OpenAIOAuthProtocol.DYNAMIC_CLIENT_ID }
                ?: OpenAIOAuthProtocol.CODEX_DESKTOP_CLIENT_ID
            val authUrl = OpenAIOAuthProtocol.authorizationUrl(
                redirectUri = redirectUri,
                state = state,
                challenge = pkce.challenge,
                clientId = knownClientId
            )
            val expiresAt = System.currentTimeMillis() + LOGIN_SESSION_WINDOW_MS
            pendingLogins[sessionId] = PendingLogin(
                server = server,
                state = state,
                pkce = pkce,
                discovery = discovery,
                previous = previous,
                previousIsBackupPlaceholder = previousIsBackupPlaceholder,
                knownClientId = knownClientId,
                redirectUri = redirectUri,
                expiresAtMillis = expiresAt,
                callback = CompletableDeferred()
            )
            registered = true
            return OpenAILoginSession(
                sessionId = sessionId,
                authorizeUrl = authUrl.toString(),
                expiresAtMillis = expiresAt,
                accountId = existingAccountId
            )
        } finally {
            if (!registered) runCatching { server.close() }
        }
    }

    /**
     * Binds the loopback callback listener on the official Codex ports (1455, fallback 1457).
     * The browser on the same device reaches 127.0.0.1 directly, so the redirect lands in-app.
     */
    private fun bindLoopbackCallbackServer(): ServerSocket {
        var lastError: Exception? = null
        for (port in CALLBACK_PORTS) {
            val server = ServerSocket()
            try {
                server.reuseAddress = false
                // backlog 给足：浏览器会先建预连接/探针连接，队列太短会把真正的回调挤掉
                server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), CALLBACK_BACKLOG)
                // 不设 accept 超时：等待窗口由调用方的 withTimeout 控制，超时后关闭 socket 中断 accept
                server.soTimeout = 0
                return server
            } catch (error: Exception) {
                lastError = error
                runCatching { server.close() }
            }
        }
        FileLogger.w(TAG, "Codex callback ports ${CALLBACK_PORTS.joinToString("/")} unavailable")
        throw OpenAIAccountException(
            text(R.string.openai_error_callback_port_busy, CALLBACK_PORTS.joinToString("/"))
                .ifBlank { lastError?.message.orEmpty() }
        )
    }

    /** Legacy one-shot login: opens the browser itself and blocks until the loopback callback lands. */
    suspend fun signIn(existingAccountId: String? = null): OpenAIAccountSummary {
        val session = beginLogin(existingAccountId)
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(session.authorizeUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return try {
            awaitLoginCompletion(session.sessionId)
        } finally {
            cancelLogin(session.sessionId)
        }
    }

    /**
     * Waits for the authorization callback. Either the browser's loopback redirect or a manually
     * pasted callback URL (see [submitLoginCallback]) can complete it — browsers on some devices
     * refuse to hit 127.0.0.1, and the manual path keeps login usable there.
     */
    suspend fun awaitLoginCompletion(sessionId: String): OpenAIAccountSummary {
        val pending = pendingLogins[sessionId]
            ?: throw OpenAIAccountException(text(R.string.openai_error_login_expired))
        val callback = coroutineScope {
            val listener = async(Dispatchers.IO) {
                runCatching { awaitCallback(pending.server, pending.state) }
                    .onSuccess { pending.callback.complete(it) }
            }
            try {
                withTimeout((pending.expiresAtMillis - System.currentTimeMillis()).coerceAtLeast(1_000L)) {
                    pending.callback.await()
                }
            } finally {
                listener.cancelAndJoin()
                runCatching { pending.server.close() }
            }
        }
        return try {
            completeLogin(pending, callback)
        } finally {
            pendingLogins.remove(sessionId)
        }
    }

    /**
     * Completes a pending login from whatever the user pasted: the full URL the browser finally
     * landed on, just its `?code=…&state=…` part, or a bare authorization code.
     *
     * A bare code is accepted because some browsers collapse the address bar and users only manage
     * to copy the code itself; the pending session's state is filled in for it. When the pasted text
     * carries a state, it is still checked, so a code from another session cannot be replayed.
     */
    suspend fun submitLoginCallback(sessionId: String, rawInput: String): Boolean {
        val pending = pendingLogins[sessionId]
            ?: throw OpenAIAccountException(text(R.string.openai_error_login_expired))
        val raw = rawInput.trim()
        val query = raw.substringAfter('?', raw).substringBefore('#').trim()
        if (query.isBlank()) throw OpenAIAccountException(text(R.string.openai_error_callback_invalid))

        val values: Map<String, String> = if (query.contains('=')) {
            OpenAIOAuthProtocol.parseCallbackTarget("${OpenAIOAuthProtocol.CALLBACK_PATH}?$query")?.values
                ?: throw OpenAIAccountException(text(R.string.openai_error_callback_invalid))
        } else {
            mapOf("code" to query, "state" to pending.state)
        }
        val callback = OpenAIOAuthProtocol.Callback(values)

        val pastedState = callback.value("state").orEmpty()
        if (pastedState.isNotBlank() &&
            !OpenAIOAuthProtocol.constantTimeEquals(pastedState, pending.state)
        ) {
            throw OpenAIAccountException(text(R.string.openai_error_auth_state_mismatch))
        }
        callback.value("error")?.takeIf(String::isNotBlank)?.let { error ->
            throw OpenAIAccountException(
                text(
                    if (error == "access_denied") R.string.openai_error_auth_cancelled
                    else R.string.openai_error_auth_incomplete,
                    error
                )
            )
        }
        if (callback.value("code").isNullOrBlank()) {
            throw OpenAIAccountException(text(R.string.openai_error_auth_code_missing))
        }
        return pending.callback.complete(callback)
    }

    fun cancelLogin(sessionId: String?) {
        val pending = pendingLogins.remove(sessionId ?: return) ?: return
        runCatching { pending.server.close() }
        runCatching { pending.callback.cancel() }
    }

    /**
     * Imports an existing ChatGPT credential (Codex CLI `auth.json`, or a JSON object carrying
     * id_token / access_token / refresh_token). No browser round-trip is involved.
     */
    /**
     * Imports one existing ChatGPT credential (Codex CLI `auth.json`, a Web Session export, or a
     * JSON object carrying id_token / access_token / refresh_token). No browser round-trip.
     */
    suspend fun importAccountFromJson(raw: String): OpenAIAccountSummary {
        val outcome = importAccountsFromText(raw)
        return outcome.imported.firstOrNull()
            ?: throw OpenAIAccountException(
                outcome.failures.firstOrNull() ?: text(R.string.openai_error_import_invalid)
            )
    }

    /**
     * 批量导入入口，自动识别与 cockpit-tools 一致的各类凭据来源：
     *
     *  - Codex CLI `auth.json`：`{"tokens": {"id_token"/"access_token"/"refresh_token"}}`
     *  - Web Session 导出：`{"session": {...}}`，或顶层 `{"accessToken": ...}`
     *  - 顶层就是凭据的 JSON 对象（snake_case / camelCase 都收）
     *  - JSON 数组，或逐行一个 JSON（可混合）——一次导入多个账号
     *  - 纯文本 token 行（按 access token 处理）
     *  - 快速待授权行：`邮箱----密码----2FA秘钥----邮件地址`（3 段也可，仅登记邮箱）
     */
    suspend fun importAccountsFromText(raw: String): OpenAIAccountImportOutcome {
        val content = raw.trim()
        val imported = mutableListOf<OpenAIAccountSummary>()
        val failures = mutableListOf<String>()
        val pendingEmails = linkedSetOf<String>()
        if (content.isEmpty()) {
            failures += text(R.string.openai_error_import_empty)
            return OpenAIAccountImportOutcome(imported, failures, pendingEmails.toList())
        }
        for ((index, source) in splitImportSources(content).withIndex()) {
            val email = pendingEmailFromLine(source)
            if (email != null) {
                pendingEmails += email
                continue
            }
            val credential = parseCredentialFromSource(source)
            if (credential == null) {
                failures += text(R.string.openai_error_import_line_failed, (index + 1).toString(), "")
                continue
            }
            val summary = runCatching { persistImportedAccount(credential) }.getOrElse { error ->
                if (error is CancellationException) throw error
                val reason = error.message?.takeIf(String::isNotBlank).orEmpty()
                failures += text(R.string.openai_error_import_line_failed, (index + 1).toString(), reason)
                null
            }
            if (summary != null) imported += summary
        }
        return OpenAIAccountImportOutcome(imported, failures, pendingEmails.toList())
    }

    /** 用 access token 直接建号（对应 cockpit 的「命名 token 导入」）。 */
    suspend fun importAccessTokenAccount(displayName: String, accessToken: String): OpenAIAccountSummary {
        val token = accessToken.trim()
        if (!looksLikeToken(token)) throw OpenAIAccountException(text(R.string.openai_error_import_missing_token))
        return persistImportedAccount(
            ImportedCredential(
                idToken = "",
                accessToken = token,
                refreshToken = "",
                clientId = OpenAIOAuthProtocol.CODEX_CLI_CLIENT_ID,
                email = "",
                accountId = "",
                displayName = displayName.trim()
            )
        )
    }

    /** 导出全部账号为 JSON，可直接再次导入回本应用。 */
    suspend fun exportAccounts(): String {
        val array = JSONArray()
        accountDao.getAllOnce().forEach { account ->
            array.put(
                JSONObject()
                    .put("email", account.email)
                    .put("displayName", account.displayName)
                    .put("planType", account.planType.orEmpty())
                    .put("clientId", account.clientId)
                    .put("scopes", account.scopes)
                    .put(
                        "tokens",
                        JSONObject()
                            .put("id_token", decryptToken(account.encryptedIdToken))
                            .put("access_token", decryptToken(account.encryptedAccessToken))
                            .put("refresh_token", decryptToken(account.encryptedRefreshToken))
                    )
            )
        }
        return array.toString(2)
    }

    /**
     * 把一段文本切成若干可导入来源（对齐 cockpit-tools 的导入入口）：
     *
     *  - 整个内容是 JSON 数组：展开为逐条
     *  - 整个内容是 JSON 对象：sub2api 导出（`{"accounts":[…],"exported_at"/"proxies":…}`）展开
     *    accounts 数组；否则整段作为一条
     *  - 否则逐行：每行可以是 JSON 对象、JSON 数组（展开）、裸 token，
     *    或 `邮箱----密码----2FA秘钥----邮件地址` 待授权行
     */
    private fun splitImportSources(content: String): List<String> {
        val text = content.trim().removePrefix("\uFEFF").trim()
        return when {
            text.startsWith("[") -> {
                val array = runCatching { JSONArray(text) }.getOrNull()
                if (array == null) {
                    listOf(text)
                } else {
                    (0 until array.length()).map { index -> array.opt(index)?.toString().orEmpty() }
                        .filter { it.isNotBlank() }
                }
            }
            text.startsWith("{") -> {
                val json = runCatching { JSONObject(text) }.getOrNull()
                val accounts = json?.optJSONArray("accounts")
                if (json != null && accounts != null && looksLikeSub2ApiExport(json, accounts)) {
                    (0 until accounts.length()).map { index -> accounts.opt(index)?.toString().orEmpty() }
                        .filter { it.isNotBlank() }
                        .ifEmpty { listOf(text) }
                } else {
                    listOf(text)
                }
            }
            else -> text.lineSequence()
                .map { it.trim().removePrefix("\uFEFF") }
                .filter { it.isNotEmpty() }
                // 单行内嵌 JSON 数组也要展开（cockpit 的 token 行导入同样逐行解析数组）
                .flatMap { line ->
                    if (line.startsWith("[")) {
                        val array = runCatching { JSONArray(line) }.getOrNull()
                        if (array != null && array.length() > 0) {
                            (0 until array.length()).map { array.opt(it)?.toString().orEmpty() }
                                .filter { it.isNotBlank() }
                        } else {
                            listOf(line)
                        }
                    } else {
                        listOf(line)
                    }
                }
                .toList()
        }
    }

    /**
     * sub2api 导出识别（对齐 cockpit 的 `looks_like_sub2api_export`）：
     * 带 `exported_at` 或 `proxies`，或 accounts 条目同时含 `credentials` 与 `platform`。
     */
    private fun looksLikeSub2ApiExport(json: JSONObject, accounts: JSONArray): Boolean {
        if (json.has("exported_at") || json.has("proxies")) return true
        return (0 until accounts.length()).any { index ->
            val item = accounts.optJSONObject(index) ?: return@any false
            item.has("credentials") && item.has("platform")
        }
    }

    /** 解析一条来源：优先按 JSON 凭据对象处理，否则整段当 access token。 */
    private fun parseCredentialFromSource(source: String): ImportedCredential? {
        val json = runCatching { JSONObject(source) }.getOrNull()
        if (json != null) return parseCredentialJson(json)
        return parseCredentialText(source)
    }

    /**
     * 从一个凭据 JSON 里取出 token 三元组。
     * 兼容 Codex CLI 的 `tokens` 容器、Web Session 的 `session` 容器、sub2api 导出的
     * `credentials` 容器，以及顶层的 snake_case / camelCase 字段。
     */
    private fun parseCredentialJson(json: JSONObject): ImportedCredential? {
        val container = json.optJSONObject("tokens")
            ?: json.optJSONObject("session")
            ?: json.optJSONObject("auth")
            ?: json.optJSONObject("credentials")
            ?: json

        fun read(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
            container.optString(key).takeIf { it.isNotBlank() }
                ?: json.optString(key).takeIf { it.isNotBlank() }
        }.orEmpty()

        val idToken = read("id_token", "idToken")
        val accessToken = read("access_token", "accessToken")
        val refreshToken = read("refresh_token", "refreshToken")
        if (idToken.isBlank() && accessToken.isBlank() && refreshToken.isBlank()) return null
        return ImportedCredential(
            idToken = idToken,
            accessToken = accessToken,
            refreshToken = refreshToken,
            clientId = read("client_id", "clientId").ifBlank { OpenAIOAuthProtocol.CODEX_CLI_CLIENT_ID },
            email = read("email", "chatgpt_user_email"),
            accountId = read(
                "account_id", "chatgpt_account_id", "accountId", "chatgptAccountId",
                "workspace_id", "workspaceId"
            ),
            displayName = ""
        )
    }

    /** 裸 token 文本：长度够且不含空白即按 access token 收下。 */
    private fun parseCredentialText(value: String): ImportedCredential? {
        val token = value.trim()
        if (!looksLikeToken(token)) return null
        return ImportedCredential(
            idToken = "",
            accessToken = token,
            refreshToken = "",
            clientId = OpenAIOAuthProtocol.CODEX_CLI_CLIENT_ID,
            email = "",
            accountId = "",
            displayName = ""
        )
    }

    private fun looksLikeToken(value: String): Boolean =
        value.length >= 20 && value.none { it.isWhitespace() }

    /**
     * 快速待授权行：`邮箱----账号密码----2FA秘钥----邮件地址`，3 段（无邮件地址）也可。
     * 命中后返回邮箱；这类条目没有可用 token，需用户登录后才生效。
     */
    private fun pendingEmailFromLine(line: String): String? {
        if (!line.contains("----")) return null
        val parts = line.split("----").map { it.trim() }
        if (parts.size !in 3..4) return null
        val email = parts[0]
        if (!email.contains('@')) return null
        val (local, domain) = email.split('@', limit = 2).let {
            it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty()
        }
        if (local.isEmpty() || !domain.contains('.')) return null
        return email
    }


    /** 把解析出的凭据写入本机加密存储，并建好配套 provider。 */
    private suspend fun persistImportedAccount(credential: ImportedCredential): OpenAIAccountSummary {
        if (credential.accessToken.isBlank() && credential.refreshToken.isBlank()) {
            throw OpenAIAccountException(text(R.string.openai_error_import_missing_token))
        }
        // 仅带 refresh_token 的导入（cockpit TXT 导出的常见形态）：先拿它换一套完整令牌，
        // 否则拉不到模型目录、账号也会以空模型列表落户（对齐 cockpit 的 refresh-token 导入）。
        val resolved = if (credential.accessToken.isBlank()) {
            refreshImportedCredential(credential)
        } else {
            credential
        }
        val savedIds = accountDao.getAllOnce().mapTo(mutableSetOf(), OpenAIAccountEntity::id)
        if (!OpenAIAccountSelectionPolicy.canConnect(savedIds, null)) {
            throw OpenAIAccountException(
                text(R.string.openai_error_account_limit, OpenAIAccountSelectionPolicy.MAX_ACCOUNTS)
            )
        }
        val hostId = stableHostId()
        val now = System.currentTimeMillis()
        val claims = decodeIdTokenClaims(resolved.idToken)
        val auth = claims?.optJSONObject("https://api.openai.com/auth")
        val subject = claims?.optString("sub")?.takeIf(String::isNotBlank)
            ?: resolved.accountId.takeIf(String::isNotBlank)
            ?: "imported-${UUID.randomUUID()}"
        val email = claims?.optString("email")?.takeIf(String::isNotBlank)
            ?: auth?.optString("chatgpt_user_email")
            ?: resolved.email
        val name = claims?.optString("name")?.takeIf(String::isNotBlank)
            ?: email.ifBlank { resolved.displayName.ifBlank { text(R.string.openai_account_default_name) } }
        val existing = accountDao.getAllOnce().firstOrNull { it.subject == subject }
        val accountId = existing?.id ?: UUID.randomUUID().toString()
        val providerId = existing?.providerId ?: "$PROVIDER_ID_PREFIX$accountId"
        // id_token 的 auth claims 里带订阅档位（chatgpt_plan_type）与账号 ID，模型目录按档位过滤
        val planType = auth?.optString("chatgpt_plan_type")?.takeIf(String::isNotBlank)
        val chatgptAccountId = auth?.optString("chatgpt_account_id")?.takeIf(String::isNotBlank)
        val models = if (resolved.accessToken.isBlank()) {
            emptyList()
        } else {
            runCatching { fetchModelCatalog(resolved.accessToken, planType, chatgptAccountId) }
                .getOrElse { error ->
                    if (error is CancellationException) throw error
                    FileLogger.w(TAG, "导入账号模型目录拉取失败，改用内置列表: ${error.message}")
                    defaultAccountModels(planType)
                }
        }
        val scopes = listOf(
            "openid", "profile", "email", "offline_access",
            "resource.invoke", OpenAIOAuthProtocol.PLAN_USAGE_SCOPE
        ).sorted().joinToString(" ")
        val account = OpenAIAccountEntity(
            id = accountId,
            providerId = providerId,
            subject = subject,
            email = email,
            displayName = name,
            clientId = resolved.clientId,
            encryptedAccessToken = KeystoreCipher.encryptString(resolved.accessToken),
            encryptedRefreshToken = KeystoreCipher.encryptString(resolved.refreshToken),
            encryptedIdToken = KeystoreCipher.encryptString(resolved.idToken),
            scopes = scopes,
            accessTokenExpiresAt = now + TimeUnit.HOURS.toMillis(1),
            hostId = hostId,
            modelCatalogJson = encodeModelCatalog(models),
            status = if (resolved.refreshToken.isBlank()) {
                OpenAIAccountEntity.STATUS_NEEDS_LOGIN
            } else {
                OpenAIAccountEntity.STATUS_CONNECTED
            },
            lastError = null,
            chatgptAccountId = chatgptAccountId,
            planType = planType,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        val oldProvider = providerRepository.getProviderById(providerId)
        val modelSlugs = models.map(OpenAIAccountModel::slug)
        val selected = oldProvider?.effectiveModel?.takeIf { it in modelSlugs }
            ?: modelSlugs.firstOrNull().orEmpty()
        val provider = AIProviderConfig(
            id = providerId,
            name = accountProviderName(account),
            type = ProviderType.OPENAI,
            apiKey = providerMarker(accountId),
            baseUrl = OPENAI_BASE_URL,
            defaultModel = selected,
            models = modelSlugs,
            selectedModel = selected,
            isEnabled = resolved.refreshToken.isNotBlank() && (oldProvider?.isEnabled ?: true),
            useResponseApi = true,
            sortOrder = oldProvider?.sortOrder ?: -1
        )
        try {
            database.withTransaction {
                accountDao.upsert(account)
                providerRepository.saveProvider(provider)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FileLogger.e(TAG, "OpenAI account import save failed", error)
            throw OpenAIAccountException(text(R.string.openai_error_secure_storage_failed))
        }
        runCatching { refreshUsage(account.id) }
        return OpenAIAccountSummary(
            id = account.id,
            providerId = providerId,
            email = account.email,
            displayName = account.displayName,
            status = account.status,
            lastError = account.lastError,
            planUsageAuthorized = resolved.refreshToken.isNotBlank(),
            enabled = provider.isEnabled,
            models = models,
            selectedModel = selected
        )
    }

    /**
     * 仅带 refresh_token 的导入凭据：走一次 refresh_token grant 换出完整令牌三元组
     * （对齐 cockpit 的 `upsert_account_from_refresh_token`）。失败直接抛错，让该行导入
     * 以明确原因失败，而不是落户一个令牌已失效的账号。
     */
    private suspend fun refreshImportedCredential(credential: ImportedCredential): ImportedCredential {
        val discovery = loadDiscovery()
        val body = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("client_id", credential.clientId)
            .add("refresh_token", credential.refreshToken)
            .apply {
                if (credential.clientId == OpenAIOAuthProtocol.DYNAMIC_CLIENT_ID) {
                    add("resource", OpenAIOAuthProtocol.RESOURCE)
                }
            }
            .build()
        val response = postForm(discovery.tokenEndpoint, body)
        if (response.code !in 200..299) {
            val errorCode = oauthErrorCode(response.body)
            if (response.code == 401 || errorCode in INVALID_REFRESH_TOKEN_CODES) {
                throw OpenAIAccountException(text(R.string.openai_error_login_expired))
            }
            throw OpenAIAccountException(
                text(R.string.openai_error_token_refresh_http, response.code, errorCode?.let { " ($it)" } ?: "")
            )
        }
        val json = JSONObject(response.body)
        val access = json.optString("access_token")
        if (access.isBlank()) {
            throw OpenAIAccountException(text(R.string.openai_error_token_response_incomplete))
        }
        return credential.copy(
            accessToken = access,
            refreshToken = json.optString("refresh_token").ifBlank { credential.refreshToken },
            idToken = json.optString("id_token").ifBlank { credential.idToken }
        )
    }


    private fun decodeIdTokenClaims(idToken: String): JSONObject? {
        if (idToken.isBlank()) return null
        val segments = idToken.split('.')
        if (segments.size != 3) return null
        return runCatching {
            JSONObject(decodeBase64Url(segments[1]).toString(StandardCharsets.UTF_8))
        }.getOrNull()
    }

    private suspend fun completeLogin(
        pending: PendingLogin,
        callback: OpenAIOAuthProtocol.Callback
    ): OpenAIAccountSummary {
        val previous = pending.previous
        val previousIsBackupPlaceholder = pending.previousIsBackupPlaceholder
        val hostId = stableHostId()
        val discovery = pending.discovery
        val pkce = pending.pkce
        val redirectUri = pending.redirectUri
        try {
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
                    ?: pending.knownClientId
            }
            val tokens = exchangeAuthorizationCode(discovery.tokenEndpoint, clientId, code, pkce.verifier, redirectUri)
            val identity = verifyIdentity(tokens.idToken, clientId, null, discovery.jwksUri)
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
            // ChatGPT OAuth token 打 api.openai.com/v1/models 会被拒（403），官方 Codex 客户端
            // 走 chatgpt.com 后端目录端点；失败即回退到按订阅分级的内置列表，保证登录后立即可用。
            val planType = decodeIdTokenClaims(tokens.idToken)
                ?.optJSONObject("https://api.openai.com/auth")
                ?.optString("chatgpt_plan_type")
                ?.takeIf(String::isNotBlank)
            val modelResult = when {
                !canUsePlan -> Result.success(emptyList<OpenAIAccountModel>())
                tokens.refreshToken.isBlank() -> Result.failure(OpenAIAccountException(text(R.string.openai_error_refresh_token_missing)))
                else -> runCatching { fetchModelCatalog(tokens.accessToken, planType, identity.chatgptAccountId) }
                    .recoverCatching { error ->
                        if (error is CancellationException) throw error
                        FileLogger.w(TAG, "账号模型目录拉取失败，改用内置列表: ${error.message}")
                        defaultAccountModels(planType)
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
                planType = planType ?: previous?.planType ?: priorIdentity?.planType,
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
        val account = accountDao.getById(accountId) ?: throw OpenAIAccountException(text(R.string.openai_error_account_missing))
        // 拉不到账号目录时回退内置列表，让「暂时没有模型」的账号也能恢复可用；
        // 同时把原因作为 warning 告诉用户，避免误以为模型是真的只有这些。
        val (models, warning) = try {
            fetchModelCatalog(token, account.planType, account.chatgptAccountId) to null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FileLogger.w(TAG, "刷新模型目录失败，改用内置列表: ${error.message}")
            defaultAccountModels(account.planType) to (error.message?.takeIf(String::isNotBlank)
                ?: text(R.string.openai_warning_model_catalog_unavailable))
        }
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
        return OpenAIAccountRefreshResult(models, warning)
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

    /**
     * 解析本次调用用哪个账号（两层路由 + 会话亲和，对齐 cockpit-tools 的默认调度模式）：
     *
     *  1. **显式选择优先**：[preferredAccountId] 能服务就用它，并把 (会话, 模型) 绑到它；
     *  2. **能力回退**：显式账号服务不了该模型（目录里没有 / 档位不匹配 / 令牌失效）时，
     *     先看同会话同模型的亲和绑定，再按额度调度顺序找其它账号——付费专属模型只找付费号，
     *     免费档模型优先免费号（省付费额度）。
     *
     * 界线：额度暂停 / provider 停用 / 未连接这类**用户可见状态**绝不静默切换，直接返回 null
     * 让调用方提示用户处理；只有"这个账号根本跑不了这个模型"才回退。
     *
     * @param sessionId 调用方会话 id（聊天会话 / 子代理会话）。为空则不启用亲和绑定。
     */
    suspend fun routeForModel(
        preferredAccountId: String,
        model: String,
        sessionId: String? = null
    ): ChatGptAccountTarget? {
        val accounts = accountDao.getAllOnce()
        val caller = sessionId?.takeIf(String::isNotBlank)
        val explicit = accounts.firstOrNull { it.id == preferredAccountId }
        if (explicit != null) {
            // 用户可见的停用状态：不回退，让用户自己处理（暂停额度/停用 provider/未连接/未授权）
            if (isUserVisibleBlocked(explicit)) return null
            val target = resolveAccountTarget(explicit, model)
            if (target != null) {
                caller?.let { accountAffinity.remember(it, model, it, target.accountId) }
                return target
            }
            // 显式账号存在但跑不了这个模型：能力问题才允许回退
            val tierBlocked = isTierBlocked(explicit, model)
            val fallback = routeViaAffinityOrFallback(accounts, model, caller, excludeAccountId = explicit.id)
            if (fallback != null) return fallback
            if (tierBlocked) {
                throw OpenAIAccountException(text(R.string.openai_error_model_requires_paid_plan, model))
            }
            return null
        }
        // 显式账号不在档（被删除等）：按亲和 + 能力回退自愈，避免整个会话直接瘫掉
        return routeViaAffinityOrFallback(accounts, model, caller, excludeAccountId = null)
    }

    /** 用户可见的停用状态（暂停额度 / provider 停用 / 未连接 / 未授权计划）：这些状态不回退账号。 */
    private suspend fun isUserVisibleBlocked(account: OpenAIAccountEntity): Boolean {
        val provider = providerRepository.getProviderById(account.providerId) ?: return true
        return !provider.isEnabled ||
            account.status != OpenAIAccountEntity.STATUS_CONNECTED ||
            !OpenAIOAuthProtocol.planUsageGranted(account.scopesAsSet())
    }

    /** 档位硬约束命中：账号是免费档，而模型在它的目录里标记为付费专属。 */
    private fun isTierBlocked(account: OpenAIAccountEntity, model: String): Boolean =
        decodeModelCatalog(account.modelCatalogJson).firstOrNull { it.slug == model }?.paidOnly == true &&
            isFreePlan(account.planType)

    /**
     * 单个账号能否服务该模型：目录包含（空目录放行）、档位匹配、授权范围够、令牌可获取、
     * 且刷新后仍是已连接 + provider 启用。任何一条不过返回 null。
     */
    private suspend fun resolveAccountTarget(account: OpenAIAccountEntity, model: String): ChatGptAccountTarget? {
        val provider = providerRepository.getProviderById(account.providerId) ?: return null
        if (!provider.isEnabled) return null
        if (account.status != OpenAIAccountEntity.STATUS_CONNECTED) return null
        if (!OpenAIOAuthProtocol.planUsageGranted(account.scopesAsSet())) return null
        val catalog = decodeModelCatalog(account.modelCatalogJson)
        val modelSet = provider.models.toSet().ifEmpty { catalog.map(OpenAIAccountModel::slug).toSet() }
        // An empty catalog (e.g. the model list endpoint was unreachable at login) must not
        // block chatting: accept the requested model and let the upstream call fail if it is real.
        if (modelSet.isNotEmpty() && model !in modelSet) return null
        // 付费专属模型不路由给免费账号（免费号打过去上游必然拒绝）
        if (catalog.firstOrNull { it.slug == model }?.paidOnly == true && isFreePlan(account.planType)) return null
        val token = try {
            accessToken(account.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val refreshed = accountDao.getById(account.id) ?: return null
        val refreshedProvider = providerRepository.getProviderById(refreshed.providerId) ?: return null
        if (!refreshedProvider.isEnabled || refreshed.status != OpenAIAccountEntity.STATUS_CONNECTED) return null
        return ChatGptAccountTarget(
            refreshed.id,
            refreshed.providerId,
            token,
            refreshed.email.ifBlank { refreshed.displayName },
            refreshed.chatgptAccountId
        )
    }

    /** 亲和绑定优先，其次按额度调度顺序做能力回退；成功即写入亲和绑定。 */
    private suspend fun routeViaAffinityOrFallback(
        accounts: List<OpenAIAccountEntity>,
        model: String,
        caller: String?,
        excludeAccountId: String?
    ): ChatGptAccountTarget? {
        if (caller != null) {
            val boundId = accountAffinity.preferredAccountId(caller, model, caller)
            if (boundId != null && boundId != excludeAccountId) {
                val bound = accounts.firstOrNull { it.id == boundId }
                if (bound != null && !isUserVisibleBlocked(bound)) {
                    resolveAccountTarget(bound, model)?.let { return it }
                }
            }
        }
        val candidates = accounts.filter { it.id != excludeAccountId && !isUserVisibleBlocked(it) }
        // 任一账号目录把该模型标为付费专属，就按付费专属调度（保守：避免路由到会被上游拒绝的免费号）
        val modelPaidOnly = candidates.any { account ->
            decodeModelCatalog(account.modelCatalogJson).firstOrNull { it.slug == model }?.paidOnly == true
        }
        val ordered = OpenAIAccountSelectionPolicy.orderFallbackCandidates(
            candidates.map { OpenAIAccountSelectionPolicy.FallbackCandidate(it.id, isFreePlan(it.planType)) },
            modelPaidOnly = modelPaidOnly
        )
        for (accountId in ordered) {
            val account = accounts.firstOrNull { it.id == accountId } ?: continue
            val target = resolveAccountTarget(account, model) ?: continue
            caller?.let { accountAffinity.remember(it, model, it, target.accountId) }
            return target
        }
        return null
    }

    suspend fun setEnabled(accountId: String, enabled: Boolean) {
        val account = accountDao.getById(accountId) ?: throw OpenAIAccountException(text(R.string.openai_error_account_missing))
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

    /**
     * 账号对应的 providerId 与当前选中模型，供「设为新会话默认」写入默认模型配置。
     * 账号或 provider 不存在时返回 null。
     */
    suspend fun providerSelectionOf(accountId: String): Pair<String, String>? {
        val account = accountDao.getById(accountId) ?: return null
        val provider = providerRepository.getProviderById(account.providerId) ?: return null
        val model = provider.effectiveModel.ifBlank { provider.models.firstOrNull().orEmpty() }
        return account.providerId to model
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
        // 清掉该账号的全部会话亲和绑定，防止路由层继续记住一个已不存在的账号
        accountAffinity.forgetAccount(accountId)
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
                .apply {
                    // Same contract split as the code exchange: legacy dynamic clients
                    // require `resource`, official Codex clients reject the extra field.
                    if (account.clientId == OpenAIOAuthProtocol.DYNAMIC_CLIENT_ID) {
                        add("resource", OpenAIOAuthProtocol.RESOURCE)
                    }
                }
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
            .apply {
                // Official Codex clients never send `resource`; only the legacy dynamic
                // registration contract requires it. Sending it there breaks the exchange.
                if (clientId == OpenAIOAuthProtocol.DYNAMIC_CLIENT_ID) add("resource", OpenAIOAuthProtocol.RESOURCE)
            }
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

    /**
     * 拉取账号自己的模型目录（官方 Codex 客户端契约）。
     *
     * 端点：`chatgpt.com/backend-api/codex/models?client_version=...`，带账号 token 与
     * `chatgpt-account-id` 头。响应每个模型带 `visibility`（`hide` 的是内部模型，如
     * codex-auto-review，不展示）和 `available_in_plans`（为空表示全档位可用；非空时
     * 只有账号订阅档位命中才可见——免费号与付费号可见模型不同就是这个机制）。
     *
     * planType 为空（还没拉到用量信息）时不做档位过滤，只挡内部模型。
     */
    private suspend fun fetchModelCatalog(
        accessToken: String,
        planType: String?,
        chatgptAccountId: String?
    ): List<OpenAIAccountModel> {
        val url = OPENAI_CODEX_MODELS_URL.toHttpUrl().newBuilder()
            .addQueryParameter("client_version", OPENAI_CODEX_CLIENT_VERSION)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .header("originator", "codex_cli_rs")
            .apply { chatgptAccountId?.takeIf(String::isNotBlank)?.let { header("ChatGPT-Account-Id", it) } }
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
        val plan = planType?.trim()?.lowercase().orEmpty()
        val entries = buildList {
            for (index in 0 until items.length()) {
                val model = items.optJSONObject(index) ?: continue
                val slug = model.optString("slug").trim()
                if (slug.isEmpty()) continue
                // 内部模型（visibility=hide，如 codex-auto-review / 图像模型）不进入选择器
                val visibility = model.optString("visibility").trim()
                if (visibility.equals("hide", ignoreCase = true)) continue
                // available_in_plans 为空 = 全档位可用；非空时账号档位必须命中（档位未知则不过滤）。
                // 同时记录付费独占性：非空且不含 free = 付费专属模型（额度调度用）。
                val plans = model.optJSONArray("available_in_plans")
                val planRestricted = plans != null && plans.length() > 0
                val planNames = (0 until (plans?.length() ?: 0)).map { plans.optString(it).trim().lowercase() }
                val planAllowed = !planRestricted || plan.isEmpty() || plan in planNames
                if (!planAllowed) continue
                add(OpenAIOAuthProtocol.ModelCatalogEntry(
                    slug = slug,
                    // 展示名缺失时退回 slug，避免被下游的合法性过滤整条丢掉
                    displayName = model.optString("display_name").trim().ifBlank { slug },
                    // 没写 visibility 的条目按可见处理（下游只认 "list"）
                    visibility = visibility.ifBlank { "list" },
                    // 免费号也能用的模型（含 free 或全档位）标记为免费档，其余为付费专属
                    paidOnly = planRestricted && "free" !in planNames
                ))
            }
        }
        // 端点对过旧 client_version 会返回空列表且不报错：空列表视为拉取失败，让调用方回退内置目录。
        if (entries.isEmpty()) {
            throw OpenAIAccountException(text(R.string.openai_error_model_catalog_invalid))
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

    /**
     * Accepts loopback connections until the real OAuth callback arrives.
     *
     * Chrome and WebView open speculative connections (and send `/favicon.ico`) before or around
     * the redirect. Those must not abort the login: any unusable connection is answered and skipped,
     * and only a callback whose `state` matches completes the wait.
     */
    private suspend fun awaitCallback(server: ServerSocket, expectedState: String): OpenAIOAuthProtocol.Callback {
        while (true) {
            val socket = try {
                server.accept()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                FileLogger.w(TAG, "Codex callback accept aborted: ${error.message}")
                throw OpenAIAccountException(text(R.string.openai_error_callback_failed))
            }
            val handled = try {
                socket.use { client ->
                    client.soTimeout = CALLBACK_CLIENT_READ_TIMEOUT_MS
                    if (!client.inetAddress.isLoopbackAddress) return@use null
                    val requestTarget = readHttpRequestTarget(client)
                    if (requestTarget == null) {
                        writeCallbackResponse(client, 400)
                        return@use null
                    }
                    if (requestTarget.substringBefore('?') != OpenAIOAuthProtocol.CALLBACK_PATH) {
                        // 浏览器预取 /favicon.ico 之类的探针请求
                        writeCallbackResponse(client, 404)
                        return@use null
                    }
                    val callback = OpenAIOAuthProtocol.parseCallbackTarget(requestTarget)
                    if (callback == null ||
                        !OpenAIOAuthProtocol.constantTimeEquals(callback.value("state").orEmpty(), expectedState)
                    ) {
                        writeCallbackResponse(client, 400)
                        return@use null
                    }
                    writeCallbackResponse(client, 200)
                    callback
                }
            } catch (error: Exception) {
                FileLogger.w(TAG, "Codex callback connection failed: ${error.message}")
                null
            }
            if (handled != null) return handled
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
        models.forEach { model ->
            put(JSONObject().put("slug", model.slug).put("display_name", model.displayName).put("paid_only", model.paidOnly))
        }
    }.toString()

    private fun decodeModelCatalog(json: String): List<OpenAIAccountModel> = runCatching {
        val items = JSONArray(json)
        buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val slug = item.optString("slug").trim()
                val display = item.optString("display_name").trim()
                // 旧版目录没有 paid_only 字段：缺省按免费档处理（保守，避免误拦免费号）
                val paidOnly = item.optBoolean("paid_only", false)
                if (slug.isNotEmpty() && display.isNotEmpty()) add(OpenAIAccountModel(slug, display, paidOnly))
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
        /**
         * 官方 Codex 客户端的账号模型目录端点（ChatGPT 订阅 token 专用）。
         * `api.openai.com/v1/models` 面向 API platform，订阅 token 打它会被拒（403）；
         * 官方 Codex 客户端走的是 chatgpt.com 后端的这个端点，且 `client_version` 必填、
         * 受版本门控（过旧返回空列表不报错）。响应里每个模型带 `visibility` 与
         * `available_in_plans`：免费号与付费号（plus/pro/team…）可见的模型确实不同。
         */
        private const val OPENAI_CODEX_MODELS_URL = "https://chatgpt.com/backend-api/codex/models"
        private const val OPENAI_CODEX_CLIENT_VERSION = "0.153.4"
        /** Official Codex login callback ports; 1455 primary, 1457 is Codex's own fallback. */
        private val CALLBACK_PORTS = intArrayOf(1455, 1457)
        private const val CALLBACK_TIMEOUT_MS = 180_000
        private const val CALLBACK_BACKLOG = 32
        private const val LOGIN_SESSION_WINDOW_MS = 180_000L
        private const val CALLBACK_CLIENT_READ_TIMEOUT_MS = 5_000
        private const val MAX_CALLBACK_REQUEST_BYTES = 16 * 1024
        private const val MAX_CALLBACK_REQUEST_LINE = 8 * 1024
        private const val HTTP_TIMEOUT_SECONDS = 30L
        /** 模型目录 TTL：超过该时长未刷新过的账号，在打开模型选择器时自动重新拉取官方目录。 */
        private const val MODEL_CATALOG_TTL_MS = 30 * 60 * 1000L
        private const val TOKEN_REFRESH_MARGIN_MS = 60_000L
        private const val CLOCK_SKEW_SECONDS = 30L
        private const val TAG = "OpenAIAccountRepository"
        private val OFFICIAL_HOSTS = setOf("auth.openai.com", "api.openai.com", "chatgpt.com")
        private val INVALID_REFRESH_TOKEN_CODES = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused"
        )

        /**
         * ChatGPT 账号的兜底模型目录（官方 Codex 当前在售阵容，跟随 cockpit-tools 的随包清单）。
         *
         * 账号目录端点拉不到（网络问题 / 403 / 空列表）时用这份列表顶上，保证登录/导入后
         * 账号立即可用、模型选择器里能看到官方模型。免费号只给轻量子集，付费号给全量——
         * 与官方按订阅等级开放模型的策略一致（真实可用集合以端点返回的 available_in_plans 为准）。
         */
        private val ACCOUNT_MODELS_PAID: List<OpenAIAccountModel> = listOf(
            OpenAIAccountModel("gpt-6.1-sol", "GPT-6.1 Sol", paidOnly = true),
            OpenAIAccountModel("gpt-6-astra", "GPT-6 Astra", paidOnly = true),
            OpenAIAccountModel("gpt-6-sol", "GPT-6 Sol", paidOnly = true),
            OpenAIAccountModel("gpt-6-luna", "GPT-6 Luna", paidOnly = false),
            OpenAIAccountModel("gpt-5.6-sol", "GPT-5.6 Sol", paidOnly = true),
            OpenAIAccountModel("gpt-5.6-terra", "GPT-5.6 Terra", paidOnly = true),
            OpenAIAccountModel("gpt-5.6-luna", "GPT-5.6 Luna", paidOnly = false),
            OpenAIAccountModel("gpt-5.4", "GPT-5.4", paidOnly = false),
            OpenAIAccountModel("gpt-5.4-mini", "GPT-5.4 Mini", paidOnly = false),
            OpenAIAccountModel("gpt-5.3-codex", "GPT-5.3 Codex", paidOnly = false),
            OpenAIAccountModel("gpt-5.3-codex-spark", "GPT-5.3 Codex Spark", paidOnly = false),
            OpenAIAccountModel("gpt-reserve", "GPT Reserve", paidOnly = true)
        )

        /**
         * 免费订阅可用模型：轻量子集 + Luna 日常款（官方对免费号开放 GPT-6 Luna / GPT-5.6 Luna），
         * 前沿型号（sol / astra / terra / reserve）不开放。
         */
        private val ACCOUNT_MODELS_FREE: List<OpenAIAccountModel> = listOf(
            OpenAIAccountModel("gpt-6-luna", "GPT-6 Luna", paidOnly = false),
            OpenAIAccountModel("gpt-5.6-luna", "GPT-5.6 Luna", paidOnly = false),
            OpenAIAccountModel("gpt-5.4", "GPT-5.4", paidOnly = false),
            OpenAIAccountModel("gpt-5.4-mini", "GPT-5.4 Mini", paidOnly = false),
            OpenAIAccountModel("gpt-5.3-codex", "GPT-5.3 Codex", paidOnly = false),
            OpenAIAccountModel("gpt-5.3-codex-spark", "GPT-5.3 Codex Spark", paidOnly = false)
        )

        /**
         * 按订阅等级取兜底目录：free 只给轻量子集；其余（plus/pro/team/business/enterprise）
         * 与未知档位都给全量——真实可用集合由账号目录端点的 available_in_plans 决定。
         */
        fun defaultAccountModels(planType: String?): List<OpenAIAccountModel> =
            if (planType?.trim()?.equals("free", ignoreCase = true) == true) {
                ACCOUNT_MODELS_FREE
            } else {
                ACCOUNT_MODELS_PAID
            }

        fun isAccountProvider(provider: AIProviderConfig): Boolean = accountIdFromProviderMarker(provider.apiKey) != null
        fun accountIdFromProviderMarker(value: String): String? = value.takeIf { it.startsWith(PROVIDER_MARKER_PREFIX) }
            ?.removePrefix(PROVIDER_MARKER_PREFIX)
            ?.takeIf(String::isNotBlank)
    }
}

class OpenAIAccountException(message: String) : IllegalStateException(message)
