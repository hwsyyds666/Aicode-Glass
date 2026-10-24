package com.aicode.feature.workspace.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.BuildConfig
import com.aicode.R
import com.aicode.core.security.KeystoreCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.githubOAuthDataStore by preferencesDataStore(name = "github_oauth")

data class GitHubAccount(val login: String, val avatarUrl: String, val htmlUrl: String)

data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val intervalSeconds: Long,
    val expiresInSeconds: Long
) {
    override fun toString(): String =
        "GitHubDeviceCode(deviceCode=[REDACTED], userCode=[REDACTED], verificationUri=$verificationUri, intervalSeconds=$intervalSeconds, expiresInSeconds=$expiresInSeconds)"
}

data class GitHubRepositoryItem(
    val id: Long,
    val name: String,
    val fullName: String,
    val owner: String,
    val description: String?,
    val private: Boolean,
    val archived: Boolean,
    val defaultBranch: String,
    val updatedAt: String,
    val cloneUrl: String,
    val htmlUrl: String
)

data class GitHubRepositoryPage(val repositories: List<GitHubRepositoryItem>, val nextPage: Int?)

data class GitHubPullRequest(val number: Int, val title: String, val htmlUrl: String)

internal enum class GitHubDevicePollStatus { PENDING, SLOW_DOWN, DENIED, EXPIRED, AUTHORIZED, UNKNOWN }

internal data class GitHubDevicePollResult(
    val status: GitHubDevicePollStatus,
    val accessToken: String? = null
) {
    override fun toString(): String = "GitHubDevicePollResult(status=$status, accessToken=[REDACTED])"
}

/** GitHub OAuth authorization and REST access. Access tokens are only persisted as KeystoreCipher ciphertext. */
@Singleton
class GitHubOAuthRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    client: OkHttpClient
) {
    internal var pollDelay: suspend (Long) -> Unit = { millis -> kotlinx.coroutines.delay(millis) }

    private val client = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val clientIdKey = stringPreferencesKey("client_id")
    private val encryptedTokenKey = stringPreferencesKey("encrypted_access_token")
    private val loginKey = stringPreferencesKey("account_login")
    private val avatarKey = stringPreferencesKey("account_avatar")
    private val htmlUrlKey = stringPreferencesKey("account_html_url")

    suspend fun configuredClientId(): String {
        val stored = context.githubOAuthDataStore.data.first()[clientIdKey]?.trim().orEmpty()
        return stored.ifBlank { BuildConfig.GITHUB_OAUTH_CLIENT_ID }
    }

    suspend fun saveClientId(value: String) {
        context.githubOAuthDataStore.edit { it[clientIdKey] = value.trim() }
    }

    suspend fun account(): GitHubAccount? {
        val preferences = context.githubOAuthDataStore.data.first()
        val token = accessToken() ?: return null
        if (token.isBlank()) return null
        val login = preferences[loginKey]?.takeIf(String::isNotBlank) ?: return null
        return GitHubAccount(login, preferences[avatarKey].orEmpty(), preferences[htmlUrlKey].orEmpty())
    }

    suspend fun accessToken(): String? {
        val encrypted = context.githubOAuthDataStore.data.first()[encryptedTokenKey] ?: return null
        return runCatching { KeystoreCipher.decryptString(encrypted) }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
    }

    /**
     * Starts the GitHub Device Flow and returns the code the user confirms in the browser.
     * Device flow carries no redirect_uri, so GitHub never warns about an unregistered callback.
     */
    suspend fun beginDeviceAuthorization(): GitHubDeviceCode {
        val clientId = configuredClientId().trim()
        if (clientId.isBlank()) throw IOException(context.getString(R.string.workspace_github_error_client_id_required))
        return requestDeviceCode(clientId)
    }

    /** Polls until the user confirms the device code, reporting the remaining time while waiting. */
    suspend fun completeDeviceAuthorization(
        deviceCode: GitHubDeviceCode,
        onWaiting: suspend (Long) -> Unit
    ): GitHubAccount {
        val clientId = configuredClientId().trim()
        if (clientId.isBlank()) throw IOException(context.getString(R.string.workspace_github_error_client_id_required))
        return pollDeviceCode(clientId, deviceCode, onWaiting)
    }

    suspend fun requestDeviceCode(clientId: String): GitHubDeviceCode = withContext(Dispatchers.IO) {
        require(clientId.isNotBlank()) { context.getString(R.string.workspace_github_error_client_id_required) }
        val body = FormBody.Builder()
            .add("client_id", clientId.trim())
            // GitHub OAuth Apps expose private-repository access as the broad `repo` scope.
            // Explain this to the user before authorization; never request workflow/admin scopes.
            .add("scope", "repo read:user")
            .build()
        val json = postForm("https://github.com/login/device/code", body, acceptJson = true)
        // Device flow must be enabled on the OAuth App, otherwise GitHub answers with an error body.
        json.optString("error").takeIf(String::isNotBlank)?.let { error ->
            val description = json.optString("error_description")
            throw IOException(
                if (error == "device_flow_disabled" || description.contains("device", ignoreCase = true)) {
                    context.getString(R.string.workspace_github_error_device_flow)
                } else {
                    description.takeIf(String::isNotBlank)
                        ?: context.getString(R.string.workspace_github_error_authorization_response)
                }
            )
        }
        val verificationUri = json.requiredString("verification_uri")
        if (!isAllowedGitHubVerificationUri(verificationUri)) {
            throw IOException(context.getString(R.string.workspace_github_error_malformed_response))
        }
        GitHubDeviceCode(
            deviceCode = json.requiredString("device_code"),
            userCode = json.requiredString("user_code"),
            verificationUri = verificationUri,
            intervalSeconds = json.optLong("interval", 5).coerceAtLeast(5),
            expiresInSeconds = json.optLong("expires_in", 900).coerceAtLeast(1)
        )
    }

    /** Polls no faster than GitHub's interval and honors slow_down, denial, and expiry responses. */
    suspend fun pollDeviceCode(
        clientId: String,
        deviceCode: GitHubDeviceCode,
        onWaiting: suspend (remainingSeconds: Long) -> Unit
    ): GitHubAccount = withContext(Dispatchers.IO) {
        var interval = deviceCode.intervalSeconds.coerceAtLeast(5)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(deviceCode.expiresInSeconds)
        while (true) {
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) throw IOException(context.getString(R.string.workspace_github_error_authorization_expired))
            val remainingSeconds = TimeUnit.NANOSECONDS.toSeconds(remainingNanos).coerceAtLeast(1)
            onWaiting(remainingSeconds)
            pollDelay(TimeUnit.SECONDS.toMillis(interval))
            val result = postForm(
                "https://github.com/login/oauth/access_token",
                FormBody.Builder()
                    .add("client_id", clientId.trim())
                    .add("device_code", deviceCode.deviceCode)
                    .add("grant_type", DEVICE_GRANT_TYPE)
                    .build(),
                acceptJson = true
            )
            val poll = parseGitHubDevicePollResult(result)
            when (poll.status) {
                GitHubDevicePollStatus.PENDING -> Unit
                GitHubDevicePollStatus.SLOW_DOWN -> interval += 5
                GitHubDevicePollStatus.DENIED -> throw IOException(context.getString(R.string.workspace_github_error_authorization_denied))
                GitHubDevicePollStatus.EXPIRED -> throw IOException(context.getString(R.string.workspace_github_error_authorization_expired))
                GitHubDevicePollStatus.AUTHORIZED -> {
                    val token = poll.accessToken ?: throw IOException(context.getString(R.string.workspace_github_error_authorization_response))
                    return@withContext fetchAndPersistAccount(token)
                }
                GitHubDevicePollStatus.UNKNOWN -> throw IOException(context.getString(R.string.workspace_github_error_authorization_response))
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    suspend fun listRepositories(page: Int = 1, perPage: Int = 50): GitHubRepositoryPage = withContext(Dispatchers.IO) {
        val token = accessToken() ?: throw IOException(context.getString(R.string.workspace_github_error_not_connected))
        require(page >= 1)
        val url = "https://api.github.com/user/repos?visibility=all&affiliation=owner%2Ccollaborator%2Corganization_member" +
            "&sort=updated&direction=desc&per_page=${perPage.coerceIn(1, 100)}&page=$page"
        val request = authorizedRequest(url, token).get().build()
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) throw httpFailure(it)
            val array = JSONArray(it.body?.string().orEmpty())
            val repos = buildList(array.length()) {
                for (index in 0 until array.length()) add(array.getJSONObject(index).toRepositoryItem())
            }
            GitHubRepositoryPage(repos, githubNextPage(it.header("Link")))
        }
    }

    /** Creates a pull request using the encrypted-at-rest OAuth token; callers must enforce user approval. */
    suspend fun createPullRequest(
        fullName: String,
        title: String,
        body: String,
        head: String,
        base: String
    ): GitHubPullRequest = withContext(Dispatchers.IO) {
        val token = accessToken() ?: throw IOException(context.getString(R.string.workspace_github_error_not_connected))
        val parts = fullName.split('/')
        require(parts.size == 2 && parts.all { REPOSITORY_COMPONENT.matches(it) }) { "GitHub 仓库标识无效" }
        require(title.isNotBlank() && title.length <= 256) { "PR 标题必须为 1–256 个字符" }
        require(body.length <= MAX_PULL_REQUEST_BODY_CHARS) { "PR 描述过长（最多 $MAX_PULL_REQUEST_BODY_CHARS 个字符）" }
        require(isSafeGitRef(head) && isSafeGitRef(base)) { "PR 分支名无效" }

        val payload = JSONObject()
            .put("title", title.trim())
            .put("body", body)
            .put("head", head)
            .put("base", base)
        val request = authorizedRequest("https://api.github.com/repos/${parts[0]}/${parts[1]}/pulls", token)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) throw httpFailure(it)
            val result = JSONObject(it.body?.string().orEmpty())
            GitHubPullRequest(
                number = result.optInt("number").takeIf { number -> number > 0 }
                    ?: throw IOException(context.getString(R.string.workspace_github_error_malformed_response)),
                title = result.requiredString("title"),
                htmlUrl = result.requiredString("html_url")
            )
        }
    }

    /** Returns the current remote commit for a branch, used to prove the source commit was pushed. */
    suspend fun branchHeadSha(fullName: String, branch: String): String = withContext(Dispatchers.IO) {
        val token = accessToken() ?: throw IOException(context.getString(R.string.workspace_github_error_not_connected))
        val parts = fullName.split('/')
        require(parts.size == 2 && parts.all { REPOSITORY_COMPONENT.matches(it) }) { "GitHub 仓库标识无效" }
        require(isSafeGitRef(branch)) { "GitHub 分支名无效" }
        val encodedBranch = java.net.URLEncoder.encode(branch, "UTF-8").replace("+", "%20")
        val request = authorizedRequest(
            "https://api.github.com/repos/${parts[0]}/${parts[1]}/branches/$encodedBranch",
            token
        ).get().build()
        val response = client.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) throw httpFailure(it)
            JSONObject(it.body?.string().orEmpty())
                .optJSONObject("commit")
                ?.requiredString("sha")
                ?: throw IOException(context.getString(R.string.workspace_github_error_malformed_response))
        }
    }

    suspend fun disconnect() {
        context.githubOAuthDataStore.edit {
            it.remove(encryptedTokenKey)
            it.remove(loginKey)
            it.remove(avatarKey)
            it.remove(htmlUrlKey)
        }
    }

    private suspend fun fetchAndPersistAccount(token: String): GitHubAccount {
        val request = authorizedRequest("https://api.github.com/user", token).get().build()
        val response = client.newCall(request).execute()
        val account = response.use {
            if (!it.isSuccessful) throw httpFailure(it)
            val json = JSONObject(it.body?.string().orEmpty())
            GitHubAccount(
                login = json.requiredString("login"),
                avatarUrl = json.optString("avatar_url"),
                htmlUrl = json.optString("html_url")
            )
        }
        val encryptedToken = KeystoreCipher.encryptString(token)
        context.githubOAuthDataStore.edit {
            it[encryptedTokenKey] = encryptedToken
            it[loginKey] = account.login
            it[avatarKey] = account.avatarUrl
            it[htmlUrlKey] = account.htmlUrl
        }
        return account
    }

    private fun postForm(url: String, body: FormBody, acceptJson: Boolean): JSONObject {
        val builder = Request.Builder().url(url).post(body)
            .header("User-Agent", "Aicode-Glass-Android")
        if (acceptJson) builder.header("Accept", "application/json")
        val response = client.newCall(builder.build()).execute()
        return response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw httpFailure(it)
            JSONObject(text)
        }
    }

    private fun authorizedRequest(url: String, token: String): Request.Builder =
        Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Aicode-Glass-Android")
            .header("Authorization", "Bearer $token")

    private fun httpFailure(response: Response): IOException = when (response.code) {
        401 -> IOException(context.getString(R.string.workspace_github_error_token_revoked))
        403, 429 -> {
            val remaining = response.header("X-RateLimit-Remaining")
            val waitSeconds = githubRateLimitWaitSeconds(
                remaining = remaining,
                retryAfter = response.header("Retry-After"),
                resetAt = response.header("X-RateLimit-Reset"),
                nowEpochSeconds = System.currentTimeMillis() / 1_000L
            )
            if (waitSeconds != null) {
                IOException(context.getString(R.string.workspace_github_error_rate_limited, waitSeconds))
            } else {
                IOException(context.getString(R.string.workspace_github_error_forbidden))
            }
        }
        404 -> IOException(context.getString(R.string.workspace_github_error_not_found))
        else -> IOException(context.getString(R.string.workspace_github_error_http, response.code))
    }

    private fun JSONObject.requiredString(key: String): String =
        optString(key).takeIf(String::isNotBlank)
            ?: throw IOException(context.getString(R.string.workspace_github_error_malformed_response))

    private fun JSONObject.toRepositoryItem(): GitHubRepositoryItem {
        val ownerObject = getJSONObject("owner")
        return GitHubRepositoryItem(
            id = getLong("id"),
            name = requiredString("name"),
            fullName = requiredString("full_name"),
            owner = ownerObject.requiredString("login"),
            description = optString("description").takeIf(String::isNotBlank),
            private = optBoolean("private"),
            archived = optBoolean("archived"),
            defaultBranch = optString("default_branch").ifBlank { "main" },
            updatedAt = optString("updated_at"),
            cloneUrl = requiredString("clone_url"),
            htmlUrl = requiredString("html_url")
        )
    }

    private companion object {
        const val DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code"
        const val MAX_PULL_REQUEST_BODY_CHARS = 65_536
        val REPOSITORY_COMPONENT = Regex("[A-Za-z0-9_.-]{1,100}")
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    }
}

internal fun isSafeGitRef(value: String): Boolean =
    value.length in 1..255 &&
        value.matches(Regex("[A-Za-z0-9_./-]+")) &&
        !value.startsWith('/') && !value.endsWith('/') &&
        ".." !in value && "//" !in value && "@{" !in value

internal fun parseGitHubDevicePollResult(json: JSONObject): GitHubDevicePollResult =
    parseGitHubDevicePollResult(
        error = runCatching { json.getString("error") }.getOrNull(),
        accessToken = runCatching { json.getString("access_token") }.getOrNull()
    )

internal fun parseGitHubDevicePollResult(error: String?, accessToken: String? = null): GitHubDevicePollResult =
    when (error.orEmpty()) {
        "authorization_pending" -> GitHubDevicePollResult(GitHubDevicePollStatus.PENDING)
        "slow_down" -> GitHubDevicePollResult(GitHubDevicePollStatus.SLOW_DOWN)
        "access_denied" -> GitHubDevicePollResult(GitHubDevicePollStatus.DENIED)
        "expired_token" -> GitHubDevicePollResult(GitHubDevicePollStatus.EXPIRED)
        "" -> accessToken
            ?.takeIf(String::isNotBlank)
            ?.let { GitHubDevicePollResult(GitHubDevicePollStatus.AUTHORIZED, it) }
            ?: GitHubDevicePollResult(GitHubDevicePollStatus.UNKNOWN)
        else -> GitHubDevicePollResult(GitHubDevicePollStatus.UNKNOWN)
    }

internal fun isAllowedGitHubVerificationUri(value: String): Boolean {
    val uri = runCatching { java.net.URI(value) }.getOrNull()
    return uri?.scheme.equals("https", ignoreCase = true) &&
        uri?.host.equals("github.com", ignoreCase = true) && uri?.rawUserInfo == null
}

internal fun githubRateLimitWaitSeconds(
    remaining: String?,
    retryAfter: String?,
    resetAt: String?,
    nowEpochSeconds: Long
): Long? {
    // Retry-After can represent a secondary abuse limit even while primary quota remains.
    retryAfter?.toLongOrNull()?.coerceAtLeast(1)?.let { return it }
    if (remaining != "0") return null
    return resetAt?.toLongOrNull()?.minus(nowEpochSeconds)?.coerceAtLeast(1)
}

internal fun githubNextPage(linkHeader: String?): Int? {
    val nextUrl = linkHeader.orEmpty().split(',')
        .firstOrNull { link -> Regex("rel=\\\"?next\\\"?", RegexOption.IGNORE_CASE).containsMatchIn(link) }
        ?.substringBefore(';')
        ?.trim()
        ?.removePrefix("<")
        ?.removeSuffix(">")
        ?: return null
    val uri = runCatching { java.net.URI(nextUrl) }.getOrNull() ?: return null
    return uri.rawQuery.orEmpty().split('&')
        .firstOrNull { it.substringBefore('=') == "page" }
        ?.substringAfter('=')
        ?.toIntOrNull()
        ?.takeIf { it > 0 }
}
