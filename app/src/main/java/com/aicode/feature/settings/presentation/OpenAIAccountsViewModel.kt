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
    private var loginJob: Job? = null

    @Suppress("UNCHECKED_CAST")
    val state: StateFlow<OpenAIAccountsUiState> = combine(
        repository.observeAccounts(),
        defaultModelSettingsRepository.providerIdFlow,
        busyAccountId,
        loginSession,
        loginPreparing,
        message,
        messageIsError
    ) { values ->
        OpenAIAccountsUiState(
            accounts = values[0] as List<OpenAIAccountSummary>,
            defaultProviderId = values[1] as String,
            busyAccountId = values[2] as String?,
            loginSession = values[3] as OpenAILoginSession?,
            loginPreparing = values[4] as Boolean,
            message = values[5] as String?,
            messageIsError = values[6] as Boolean
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OpenAIAccountsUiState())

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
        loginPreparing.value = true
        loginJob = viewModelScope.launch {
            val session = try {
                repository.beginLogin(accountId)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                loginPreparing.value = false
                message.value = error.message?.takeIf(String::isNotBlank)
                    ?: context.getString(R.string.openai_error_action_failed)
                messageIsError.value = true
                return@launch
            }
            loginSession.value = session
            loginPreparing.value = false
            try {
                repository.awaitLoginCompletion(session.sessionId)
                loginSession.value = null
                message.value = context.getString(
                    if (accountId == null) R.string.openai_notice_connected else R.string.openai_notice_reauthorized
                )
                messageIsError.value = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                loginSession.value = null
                message.value = error.message?.takeIf(String::isNotBlank)
                    ?: context.getString(R.string.openai_error_action_failed)
                messageIsError.value = true
            }
        }
    }

    /** 用浏览器最终停留的回调链接完成登录（自动回跳失败时的兜底）。 */
    fun submitLoginCallback(raw: String) = viewModelScope.launch {
        val session = loginSession.value ?: return@launch
        try {
            repository.submitLoginCallback(session.sessionId, raw)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            message.value = error.message?.takeIf(String::isNotBlank)
                ?: context.getString(R.string.openai_error_callback_invalid)
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
    }

    fun reauthorize(accountId: String) {
        startLogin(accountId)
    }

    fun importAccount(rawJson: String) = runAccountAction(IMPORTING) {
        repository.importAccountFromJson(rawJson)
        message.value = context.getString(R.string.openai_notice_imported)
        messageIsError.value = false
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
    }
}
