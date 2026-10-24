package com.aicode.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.settings.data.repository.OpenAIAccountRepository
import com.aicode.feature.settings.data.repository.OpenAIAccountSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OpenAIAccountsUiState(
    val accounts: List<OpenAIAccountSummary> = emptyList(),
    val busyAccountId: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false
)

@HiltViewModel
class OpenAIAccountsViewModel @Inject constructor(
    private val repository: OpenAIAccountRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val busyAccountId = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)
    private val messageIsError = MutableStateFlow(false)

    val state: StateFlow<OpenAIAccountsUiState> = combine(
        repository.observeAccounts(),
        busyAccountId,
        message,
        messageIsError
    ) { accounts, busy, notice, isError ->
        OpenAIAccountsUiState(accounts = accounts, busyAccountId = busy, message = notice, messageIsError = isError)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OpenAIAccountsUiState())

    fun connect() = runAccountAction(CONNECTING) {
        repository.signIn()
        message.value = context.getString(R.string.openai_notice_connected)
        messageIsError.value = false
    }

    fun reauthorize(accountId: String) = runAccountAction(accountId) {
        repository.signIn(accountId)
        message.value = context.getString(R.string.openai_notice_reauthorized)
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
    }
}
