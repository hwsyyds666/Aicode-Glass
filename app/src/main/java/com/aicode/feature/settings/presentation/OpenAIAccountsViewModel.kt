package com.aicode.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.OpenAIAccountRepository
import com.aicode.feature.settings.data.repository.OpenAIAccountSummary
import com.aicode.feature.settings.data.repository.OpenAILoginSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OpenAIAccountsUiState(
    val accounts: List<OpenAIAccountSummary> = emptyList(),
    /** 新会话默认 providerId（用于标记哪个账号是"默认"）；空表示未设置。 */
    val defaultProviderId: String = "",
    val busyAccountId: String? = null,
    /** 进行中的登录会话：非空时 UI 展示授权链接 / 手动回调入口。 */
    val loginSession: OpenAILoginSession? = null,
    val loginPreparing: Boolean = false,
    /** 登录弹层是否显示：失败时保持显示，方便就地重试。 */
    val loginVisible: Boolean = false,
    /** 正在刷新全部账号的额度/模型。 */
    val refreshing: Boolean = false,
    /** 登录弹层内的错误提示（弹层会盖住页面下方的提示，必须就地显示）。 */
    val loginError: String? = null,
    /** 导出账号得到的 JSON；非空时展示导出对话框。 */
    val exportJson: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false
)

@HiltViewModel
class OpenAIAccountsViewModel @Inject constructor(
    private val repository: OpenAIAccountRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val busyAccountId = MutableStateFlow<String?>(null)
    private val loginSession = MutableStateFlow<OpenAILoginSession?>(null)
    private val loginPreparing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val messageIsError = MutableStateFlow(false)
    private val refreshing = MutableStateFlow(false)
    private val loginError = MutableStateFlow<String?>(null)
    private val exportJson = MutableStateFlow<String?>(null)
    private val loginVisible = MutableStateFlow(false)
    private var loginJob: Job? = null
    private var refreshJob: Job? = null

    @Suppress("UNCHECKED_CAST")
    val state: StateFlow<OpenAIAccountsUiState> = combine(
        repository.observeAccounts(),
        defaultModelSettingsRepository.providerIdFlow,
        busyAccountId,
        loginSession,
        loginPreparing,
        refreshing,
        loginError,
        exportJson,
        message,
        messageIsError,
        loginVisible
    ) { values ->
        OpenAIAccountsUiState(
            accounts = values[0] as List<OpenAIAccountSummary>,
            defaultProviderId = values[1] as String,
            busyAccountId = values[2] as String?,
            loginSession = values[3] as OpenAILoginSession?,
            loginPreparing = values[4] as Boolean,
            refreshing = values[5] as Boolean,
            loginError = values[6] as String?,
            exportJson = values[7] as String?,
            message = values[8] as String?,
            messageIsError = values[9] as Boolean,
            loginVisible = values[10] as Boolean
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OpenAIAccountsUiState())

    /**
     * 刷新全部账号的额度（并对还没有模型目录的账号顺带刷新模型）。
     * 进入 ChatGPT 账号页时自动调用一次，用户也能通过刷新按钮手动触发。
     */
    fun refreshAll() {
        if (refreshJob?.isActive == true) return
        if (busyAccountId.value != null) return
        refreshJob = viewModelScope.launch {
            refreshing.value = true
            loginError.value = null
            try {
                val accountIds = repository.snapshotAccountIds()
                if (accountIds.isEmpty()) {
                    message.value = context.getString(R.string.openai_notice_no_accounts)
                    messageIsError.value = false
                    return@launch
                }
                var failed = 0
                val warnings = mutableListOf<String>()
                accountIds.forEach { accountId ->
                    val ok = runCatching { repository.refreshUsage(accountId) }.isSuccess
                    if (!ok) failed++
                    if (repository.modelsOf(accountId).isEmpty()) {
                        runCatching { repository.refreshModels(accountId) }
                            .onSuccess { result -> result.warning?.let { warnings += it } }
                    }
                }
                message.value = buildString {
                    if (failed == 0) {
                        append(context.getString(R.string.openai_notice_usage_refreshed))
                    } else {
                        append(context.getString(R.string.openai_warning_usage_refresh_partial, failed, accountIds.size))
                    }
                    if (warnings.isNotEmpty()) append("；").append(warnings.distinct().joinToString("；"))
                }
                messageIsError.value = failed != 0
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                refreshing.value = false
            }
        }
    }

    /**
     * 开始一次登录（新增账号 accountId=null，或对已有账号重新授权）。
     * 只负责拿到授权链接；是否打开浏览器、是否手动粘贴回调都由 UI 决定，
     * 这样在浏览器无法回跳 127.0.0.1 的设备上也还能登录成功。
     */
    fun startLogin(accountId: String? = null) {
        loginJob?.cancel()
        cancelLoginInternal()
        message.value = null
        messageIsError.value = false
        loginError.value = null
        loginVisible.value = true
        loginPreparing.value = true
        loginJob = viewModelScope.launch {
            val session = try {
                repository.beginLogin(accountId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                loginPreparing.value = false
                val text = error.message?.takeIf(String::isNotBlank)
                    ?: context.getString(R.string.openai_error_action_failed)
                loginError.value = text
                message.value = text
                messageIsError.value = true
                return@launch
            }
            loginSession.value = session
            loginPreparing.value = false
            try {
                repository.awaitLoginCompletion(session.sessionId)
                loginSession.value = null
                loginError.value = null
                loginVisible.value = false
                message.value = context.getString(
                    if (accountId == null) R.string.openai_notice_connected else R.string.openai_notice_reauthorized
                )
                messageIsError.value = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                loginSession.value = null
                val text = error.message?.takeIf(String::isNotBlank)
                    ?: context.getString(R.string.openai_error_action_failed)
                loginError.value = text
                message.value = text
                messageIsError.value = true
            }
        }
    }

    /** 登录失败后重试：清空错误并重新发起一次授权。 */
    fun retryLogin(accountId: String?) {
        startLogin(accountId)
    }

    /**
     * 用浏览器最终停留的回调链接完成登录（自动回跳失败时的兜底）。
     *
     * 兼容三种粘贴形态：完整 URL、`?code=…&state=…` 片段、以及单独的 code。
     * 无论成败都在弹层内给出提示——之前失败提示落在被弹层盖住的页面上，看起来像「点了没反应」。
     */
    fun submitLoginCallback(raw: String) = viewModelScope.launch {
        val session = loginSession.value
        if (session == null) {
            loginError.value = context.getString(R.string.openai_error_no_pending_login)
            return@launch
        }
        loginError.value = null
        val accepted = try {
            repository.submitLoginCallback(session.sessionId, raw)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val text = error.message?.takeIf(String::isNotBlank)
                ?: context.getString(R.string.openai_error_callback_invalid)
            loginError.value = text
            message.value = text
            messageIsError.value = true
            return@launch
        }
        if (!accepted) {
            // 回调已被自动回跳抢先完成，或本次粘贴重复提交
            val text = context.getString(R.string.openai_error_login_expired)
            loginError.value = text
            messageIsError.value = true
        }
    }

    fun cancelLogin() {
        loginJob?.cancel()
        loginJob = null
        cancelLoginInternal()
    }

    private fun cancelLoginInternal() {
        loginSession.value?.let { repository.cancelLogin(it.sessionId) }
        loginSession.value = null
        loginPreparing.value = false
        loginVisible.value = false
        loginError.value = null
    }

    fun reauthorize(accountId: String) {
        startLogin(accountId)
    }

    /**
     * 批量导入：一次粘贴可包含多个账号（JSON 数组或换行分隔），结果按「成功 N 个 / 待登录邮箱 / 失败原因」汇总。
     */
    fun importAccount(rawText: String) = runAccountAction(IMPORTING) {
        val outcome = repository.importAccountsFromText(rawText)
        val parts = mutableListOf<String>()
        if (outcome.imported.isNotEmpty()) {
            parts += context.getString(R.string.openai_notice_import_result, outcome.imported.size)
        }
        if (outcome.pendingEmails.isNotEmpty()) {
            parts += context.getString(
                R.string.openai_notice_import_pending,
                outcome.pendingEmails.joinToString(", ")
            )
        }
        parts += outcome.failures
        message.value = parts.joinToString("；").ifBlank {
            context.getString(R.string.openai_error_import_invalid)
        }
        messageIsError.value = outcome.imported.isEmpty()
    }

    /** 导出全部账号为可再次导入的 JSON。 */
    fun exportAccounts() = runAccountAction(EXPORTING) {
        exportJson.value = repository.exportAccounts()
    }

    fun clearExportJson() {
        exportJson.value = null
    }

    fun refreshModels(accountId: String) = runAccountAction(accountId) {
        val result = repository.refreshModels(accountId)
        message.value = result.warning ?: context.getString(R.string.openai_notice_models_refreshed, result.models.size)
        messageIsError.value = result.warning != null
    }

    fun refreshUsage(accountId: String) = runAccountAction(accountId) {
        repository.refreshUsage(accountId)
        message.value = context.getString(R.string.openai_notice_usage_refreshed)
        messageIsError.value = false
    }

    fun setEnabled(accountId: String, enabled: Boolean) = runAccountAction(accountId) {
        repository.setEnabled(accountId, enabled)
    }

    /**
     * 把该账号设为新会话默认（写入默认 provider + 该账号当前模型）。
     *
     * 只是「新会话默认」，不触碰其它账号，也不做任何自动轮换：用户仍然可以在聊天里
     * 临时切换到别的账号。
     */
    fun setAsDefault(accountId: String) = runAccountAction(accountId) {
        val selection = repository.providerSelectionOf(accountId)
            ?: throw IllegalStateException(context.getString(R.string.openai_error_account_missing))
        val model = selection.second.ifBlank { defaultModelSettingsRepository.getDefaultModel() }
        defaultModelSettingsRepository.setDefaultModel(selection.first, model)
        message.value = context.getString(R.string.openai_notice_default_account_set)
        messageIsError.value = false
    }

    fun selectModel(accountId: String, model: String) = runAccountAction(accountId) {
        repository.setSelectedModel(accountId, model)
    }

    fun remove(accountId: String) = runAccountAction(accountId) {
        val revoked = repository.deleteAccount(accountId)
        message.value = if (revoked) {
            context.getString(R.string.openai_notice_removed_revoked)
        } else {
            context.getString(R.string.openai_notice_removed_local_only)
        }
        messageIsError.value = false
    }

    fun clearMessage() {
        message.value = null
        messageIsError.value = false
    }

    private fun runAccountAction(accountId: String, action: suspend () -> Unit) {
        if (busyAccountId.value != null) return
        busyAccountId.value = accountId
        message.value = null
        messageIsError.value = false
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message.value = error.message?.takeIf(String::isNotBlank) ?: context.getString(R.string.openai_error_action_failed)
                messageIsError.value = true
            } finally {
                busyAccountId.value = null
            }
        }
    }

    private companion object {
        const val CONNECTING = "__connecting__"
        const val IMPORTING = "__importing__"
        const val EXPORTING = "__exporting__"
    }
}
