package com.aicode.feature.workspace.presentation

import android.net.Uri
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.R
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.CommandEvent
import com.aicode.feature.git.domain.GitCommandEngine
import com.aicode.feature.workspace.data.repository.GitHubOAuthRepository
import com.aicode.feature.workspace.data.repository.GitHubAccount
import com.aicode.feature.workspace.data.repository.GitHubDeviceCode
import com.aicode.feature.workspace.data.repository.GitHubRepositoryItem
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.model.Workspace
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    private val repository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val githubOAuthRepository: GitHubOAuthRepository,
    private val gitCommandEngine: GitCommandEngine,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    data class GitHubUiState(
        val clientId: String = "",
        val account: GitHubAccount? = null,
        val deviceCode: GitHubDeviceCode? = null,
        val authorizing: Boolean = false,
        val authorizationRemainingSeconds: Long? = null,
        val repositories: List<GitHubRepositoryItem> = emptyList(),
        val nextPage: Int? = null,
        val loadingRepositories: Boolean = false,
        val cloningRepository: String? = null,
        val cloneProgress: String? = null,
        val cloneProgressPercent: Int? = null,
        val error: String? = null
    )

    private val _github = kotlinx.coroutines.flow.MutableStateFlow(GitHubUiState())
    val github: StateFlow<GitHubUiState> = _github.asStateFlow()
    private var activeGitHubAuthorizationJob: Job? = null
    private var activeGitHubCloneJob: Job? = null

    val workspaces: StateFlow<List<Workspace>> = repository.workspaces
    val current: StateFlow<Workspace?> = repository.current

    /** 本地执行模式（非远程 SSH）：本地模式下才可添加外部本地目录工作区。 */
    val isLocalMode: StateFlow<Boolean> = executionModeHolder.mode
        .map { it != ExecutionMode.REMOTE_SSH }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    /** 远程工作区初始化失败提示（根路径/默认工作区创建失败）。 */
    val initError: StateFlow<String?> = repository.initError

    /** 添加外部本地工作区失败提示（目录不可写/无法解析等）。 */
    val addError: StateFlow<String?> = repository.addError
    val externalWarningDismissed: StateFlow<Boolean> = repository.externalWarningDismissed
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 移除外部本地工作区时是否一并删除其聊天记录（用于删除确认文案）。 */
    val deleteExternalWorkspaceSessions: StateFlow<Boolean> = repository.deleteExternalWorkspaceSessionsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** 消费初始化错误提示，避免重复弹 Toast。 */
    fun consumeInitError() {
        repository.consumeInitError()
    }

    /** 消费添加外部工作区失败提示，避免重复弹 Toast。 */
    fun consumeAddError() {
        repository.consumeAddError()
    }

    init {
        viewModelScope.launch { runCatching { repository.initialize() } }
        viewModelScope.launch {
            val clientId = githubOAuthRepository.configuredClientId()
            val account = runCatching { githubOAuthRepository.account() }.getOrNull()
            _github.update { it.copy(clientId = clientId, account = account) }
        }
        // 模式切换后重新加载工作区列表（本地 File.listFiles ↔ 远程 SFTP ls）。
        // drop(1) 跳过首帧（init 已调 initialize），仅响应后续切换。
        viewModelScope.launch {
            executionModeHolder.mode.drop(1).distinctUntilChanged().collect {
                runCatching { repository.initialize() }
            }
        }
    }

    fun selectWorkspace(name: String) = viewModelScope.launch {
        runCatching { repository.selectWorkspace(name) }
    }

    fun createWorkspace(name: String, onResult: (Workspace?) -> Unit = {}) = viewModelScope.launch {
        val ws = runCatching { repository.createWorkspace(name) }.getOrNull()
        onResult(ws)
    }

    /** 打开面板前刷新外部工作区可用性（存储卡拔插后列表与当前选中及时更新）。 */
    fun refreshAvailability() = viewModelScope.launch {
        runCatching { repository.refreshAvailability() }
    }

    fun setExternalWarningDismissed(dismissed: Boolean) = viewModelScope.launch {
        runCatching { repository.setExternalWarningDismissed(dismissed) }
    }

    /** 注册用户所选设备目录为外部本地工作区，不自动切换；切换由调用方走确认流程。 */
    fun addExternalWorkspace(uri: Uri, onResult: (Workspace?) -> Unit = {}) = viewModelScope.launch {
        val ws = runCatching { repository.addExternalWorkspace(uri) }.getOrNull()
        onResult(ws)
    }

    fun deleteWorkspace(name: String) = viewModelScope.launch {
        runCatching { repository.deleteWorkspace(name) }
    }

    /** Begin Device Flow; the UI opens GitHub's verification URI while polling proceeds in the background. */
    fun connectGitHub(): Job {
        activeGitHubAuthorizationJob?.cancel()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            // 零配置连接：client_id 取用户存储值，缺省用内置 OAuth App 的（见 GitHubOAuthRepository）。
            // 两者都为空（此版本未内置）时给出明确指引，而不是要用户手填 ID。
            val normalizedClientId = githubOAuthRepository.configuredClientId().trim()
            if (normalizedClientId.isBlank()) {
                _github.update { it.copy(error = context.getString(R.string.workspace_github_error_client_id_required)) }
                return@launch
            }
            _github.update {
                it.copy(
                    clientId = normalizedClientId,
                    deviceCode = null,
                    authorizing = true,
                    authorizationRemainingSeconds = null,
                    error = null
                )
            }
            try {
                val deviceCode = githubOAuthRepository.requestDeviceCode(normalizedClientId)
                _github.update { it.copy(deviceCode = deviceCode) }
                val account = githubOAuthRepository.pollDeviceCode(normalizedClientId, deviceCode) { remaining ->
                    _github.update { it.copy(authorizationRemainingSeconds = remaining) }
                }
                _github.update {
                    it.copy(account = account, authorizing = false, deviceCode = null, authorizationRemainingSeconds = null)
                }
                loadGitHubRepositories(reset = true)
            } catch (error: CancellationException) {
                _github.update {
                    it.copy(authorizing = false, deviceCode = null, authorizationRemainingSeconds = null)
                }
                throw error
            } catch (error: Exception) {
                _github.update {
                    it.copy(
                        authorizing = false,
                        deviceCode = null,
                        authorizationRemainingSeconds = null,
                        error = error.message ?: context.getString(R.string.workspace_github_error_authorization)
                    )
                }
            }
        }
        activeGitHubAuthorizationJob = job
        job.invokeOnCompletion { if (activeGitHubAuthorizationJob === job) activeGitHubAuthorizationJob = null }
        job.start()
        return job
    }

    fun cancelGitHubAuthorization() {
        activeGitHubAuthorizationJob?.cancel()
    }

    fun disconnectGitHub() = viewModelScope.launch {
        try {
            githubOAuthRepository.disconnect()
            _github.update { it.copy(account = null, repositories = emptyList(), nextPage = null, error = null) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            _github.update { it.copy(error = context.getString(R.string.workspace_github_error_disconnect)) }
        }
    }

    fun loadGitHubRepositories(reset: Boolean = false) = viewModelScope.launch {
        val current = _github.value
        if (current.loadingRepositories || current.account == null) return@launch
        val page = if (reset) 1 else current.nextPage ?: return@launch
        _github.update { it.copy(loadingRepositories = true, error = null, repositories = if (reset) emptyList() else it.repositories) }
        try {
            val result = githubOAuthRepository.listRepositories(page)
                _github.update {
                    it.copy(
                        repositories = if (reset) result.repositories else (it.repositories + result.repositories).distinctBy(GitHubRepositoryItem::id),
                        nextPage = result.nextPage,
                        loadingRepositories = false
                    )
                }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _github.update {
                it.copy(loadingRepositories = false, error = error.message ?: context.getString(R.string.workspace_github_error_load_repositories))
            }
        }
    }

    /** Clone through the configured Git engine; credentials are provided transiently by the local helper. */
    fun cancelGitHubClone() {
        activeGitHubCloneJob?.cancel()
    }

    fun cloneGitHubRepository(repo: GitHubRepositoryItem, onCreated: (Workspace?) -> Unit = {}): Job {
        activeGitHubCloneJob?.cancel()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            val currentMode = executionModeHolder.currentMode()
            if (currentMode == ExecutionMode.REMOTE_SSH && repo.private) {
                _github.update { it.copy(error = context.getString(R.string.workspace_github_error_remote_private_clone)) }
                return@launch
            }
            if (!repo.owner.matches(GITHUB_COMPONENT) || !repo.name.matches(GITHUB_COMPONENT) ||
                repo.owner == "." || repo.owner == ".." || repo.name == "." || repo.name == ".."
            ) {
                _github.update { it.copy(error = context.getString(R.string.workspace_github_error_invalid_repository)) }
                return@launch
            }
            if (repo.archived) {
                _github.update { it.copy(error = context.getString(R.string.workspace_github_error_archived_repository)) }
                return@launch
            }

            val workspaceName = "${repo.owner}-${repo.name}".take(64)
            _github.update {
                it.copy(
                    cloningRepository = repo.fullName,
                    cloneProgress = context.getString(R.string.workspace_github_preparing_git),
                    cloneProgressPercent = null,
                    error = null
                )
            }
            var workspace: Workspace? = null
            var cloneCompleted = false
            try {
                val readyHint = gitCommandEngine.notReadyHint()
                check(readyHint == null) { readyHint ?: context.getString(R.string.workspace_github_error_git_not_ready) }
                workspace = repository.createWorkspace(workspaceName)
                    ?: throw IllegalStateException(context.getString(R.string.workspace_github_error_workspace_exists, workspaceName))
                val url = "https://github.com/${repo.owner}/${repo.name}.git"
                val command = "git clone --progress -- '$url' ."
                var exitCode: Int? = null
                gitCommandEngine.runCommandStream(
                    command = command,
                    projectPath = workspace.path,
                    timeoutMs = CommandEngine.MAX_TIMEOUT_MS
                ).collect { event ->
                    when (event) {
                        is CommandEvent.Line -> _github.update {
                            it.copy(
                                cloneProgress = event.text.takeLast(180),
                                cloneProgressPercent = githubCloneProgressPercent(event.text) ?: it.cloneProgressPercent
                            )
                        }
                        is CommandEvent.Exit -> exitCode = event.code
                    }
                }
                check(exitCode == 0) {
                    context.getString(R.string.workspace_github_error_clone_exit, exitCode?.toString() ?: "?")
                }
                repository.recordGitHubWorkspace(workspace.name, repo.fullName, repo.htmlUrl)
                cloneCompleted = true
                _github.update {
                    it.copy(
                        cloningRepository = null,
                        cloneProgress = context.getString(R.string.workspace_github_clone_complete),
                        cloneProgressPercent = 100,
                        error = null
                    )
                }
                onCreated(workspace)
            } catch (error: CancellationException) {
                withContext(NonCancellable) {
                    workspace?.let { runCatching { repository.deleteWorkspace(it.name) } }
                }
                _github.update {
                    it.copy(
                        cloningRepository = null,
                        cloneProgress = null,
                        cloneProgressPercent = null,
                        error = context.getString(R.string.workspace_github_clone_cancelled)
                    )
                }
                throw error
            } catch (error: Exception) {
                val cleanupFailed = workspace?.let { target ->
                    runCatching { repository.deleteWorkspace(target.name) }.isFailure
                } ?: false
                val message = error.message ?: context.getString(R.string.workspace_github_error_clone)
                _github.update {
                    it.copy(
                        cloningRepository = null,
                        cloneProgress = null,
                        cloneProgressPercent = null,
                        error = if (cleanupFailed) "$message\n${context.getString(R.string.workspace_github_error_partial_clone_cleanup)}" else message
                    )
                }
                onCreated(null)
            } finally {
                if (!cloneCompleted) _github.update { it.copy(cloningRepository = null) }
            }
        }
        activeGitHubCloneJob = job
        job.invokeOnCompletion { if (activeGitHubCloneJob === job) activeGitHubCloneJob = null }
        job.start()
        return job
    }

    private companion object {
        val GITHUB_COMPONENT = Regex("[A-Za-z0-9_.-]{1,100}")
    }
}

internal fun githubCloneProgressPercent(line: String): Int? =
    Regex("(?:Receiving objects|Resolving deltas|Updating files):\\s*(\\d{1,3})%", RegexOption.IGNORE_CASE)
        .find(line)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.coerceIn(0, 100)
