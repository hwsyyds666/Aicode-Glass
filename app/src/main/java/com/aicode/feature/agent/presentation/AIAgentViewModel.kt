package com.aicode.feature.agent.presentation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.widget.Toast
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.MainActivity
import com.aicode.R
import com.aicode.core.util.FileLogger
import com.aicode.core.util.toUserMessage
import com.aicode.core.util.formatCostUsd
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.device.RootNetworkGuard
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.data.local.entity.ChatSessionEntity
import com.aicode.feature.agent.domain.container.ContainerInitState
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.service.ModelCostCalculator
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aicode.feature.settings.data.repository.SubAgentSettingsRepository
import com.aicode.feature.settings.data.repository.SubAgentModelSettingsRepository
import com.aicode.feature.settings.data.repository.SubAgentModelTier
import com.aicode.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aicode.feature.settings.data.repository.StartupSessionMode
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ChatSession
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationFormatter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.NotificationOutcome
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.mcp.McpManager
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.BuiltInSubAgentCatalog
import com.aicode.feature.agent.domain.subagent.LegacySubAgentMigration
import com.aicode.feature.agent.domain.subagent.SubAgentEvent
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.subagent.SubAgentEventType
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.terminal.domain.TabFinishedEvent
import com.aicode.feature.terminal.domain.TerminalKeepaliveService
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.takeTailLines
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.agent.domain.workflow.AgentEvent
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aicode.feature.agent.domain.tool.question.UserQuestionAnswer
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.aicode.feature.backup.domain.BackupManager
import com.aicode.feature.agent.domain.command.SlashCommand
import com.aicode.feature.agent.domain.command.SlashCommandContext
import com.aicode.feature.agent.domain.command.SlashCommandRegistry
import com.aicode.feature.agent.domain.command.SlashCommandRegistry.ResolvedCommand
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.agent.presentation.component.RewindOption
import com.aicode.feature.agent.presentation.component.formatTokenCount
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext
import java.io.OutputStream
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class AIAgentViewModel @Inject constructor(
    private val agentWorkflow: AgentWorkflow,
    private val toolRegistry: ToolRegistry,
    private val agentMessageDao: AgentMessageDao,
    private val chatSessionDao: ChatSessionDao,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val modelCostCalculator: ModelCostCalculator,
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val modelReasoningEffortRepository: ModelReasoningEffortRepository,
    private val toolPermissionManager: ToolPermissionManager,
    private val askUserQuestionManager: AskUserQuestionManager,
    private val containerEngine: LinuxContainerEngine,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val terminalSessionManager: TerminalSessionManager,
    private val slashCommandRegistry: SlashCommandRegistry,
    private val checkpointManager: CheckpointManager,
    private val checkpointDao: CheckpointDao,
    private val backupManager: BackupManager,
    private val mcpManager: McpManager,
    private val agentSoundSettings: AgentSoundSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val keepaliveSettings: KeepaliveSettingsRepository,
    private val subAgentEventBus: SubAgentEventBus,
    private val subAgentSettings: SubAgentSettingsRepository,
    private val subAgentModelSettings: SubAgentModelSettingsRepository,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val legacySubAgentMigration: LegacySubAgentMigration,
    private val todoItemDao: TodoItemDao,
    private val openAIAccountRepository: com.aicode.feature.settings.data.repository.OpenAIAccountRepository,
    val fileAccess: FileAccessProvider,
    private val rootNetworkGuard: RootNetworkGuard,
    @param:ApplicationContext private val context: Context
) : ViewModel(), SlashCommandContext {

    private val sessionJobs = mutableMapOf<String, Job>()
    /** Prevents new requests from racing a rewind/delete after the old workflows have been joined. */
    private val rewindLock = Any()
    private val rewindingSessions = mutableSetOf<String>()
    /** 子代理进入销毁阶段后，拒绝稍后才出队的 SPAWNED 事件重新启动它。 */
    private val deletingSubAgentIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val stoppingSubAgentIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * agent 执行期间持有的 CPU 唤醒锁：熄屏后系统会挂起进程，使流式响应中断、工具调用卡死。
     * 不计数（setReferenceCounted(false)），多会话共用一把锁，最后一个任务结束时统一释放。
     */
    private val wakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiCode:AgentWorkflow")
            .apply { setReferenceCounted(false) }
    }

    /** 设置里用户手动开启的常驻保活；agent 任务收尾时不能把它一并关掉。 */
    @Volatile
    private var userKeepaliveEnabled = false

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    /**
     * ChatGPT 账号 provider 的档位信息：providerId → [AccountModelInfo]。
     * 供模型选择器按订阅等级排序、显示免费/付费标签。
     */
    val accountModelInfo: StateFlow<Map<String, com.aicode.feature.settings.presentation.component.AccountModelInfo>> =
        openAIAccountRepository.observeAccounts()
            .map { accounts ->
                accounts.associate { account ->
                    // 账号自己的目录为空时退回内置目录，保证 paidOnly 判定不漏过付费专属模型。
                    val models = account.models.ifEmpty {
                        com.aicode.feature.settings.data.repository.OpenAIAccountRepository.defaultAccountModels(account.planType)
                    }
                    account.providerId to com.aicode.feature.settings.presentation.component.AccountModelInfo(
                        planType = account.planType,
                        paidOnlyBySlug = models.associate { it.slug to it.paidOnly }
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** 打开模型选择器时调用：目录超过 TTL 的账号静默重新拉取官方模型列表。 */
    fun refreshAccountModelsIfStale() {
        viewModelScope.launch { openAIAccountRepository.refreshStaleAccountModels() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentSessionTodoItems: StateFlow<List<TodoItem>> = _currentSessionId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(emptyList())
            else todoItemDao.getBySession(id).map { entities -> entities.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _agentStates = MutableStateFlow<Map<String, AgentUIState>>(emptyMap())
    val agentStates: StateFlow<Map<String, AgentUIState>> = _agentStates.asStateFlow()

    /**
     * 各会话「最后一轮是否正常完成」：只有正常收尾（收到完成标记）才在轮末回复下挂「复制 / 更多」
     * 按钮；主动暂停 / 出错 / 未开始都不挂，避免按钮挂在半截输出上。
     */
    private val _completedSessions = MutableStateFlow<Set<String>>(emptySet())
    val completedSessions: StateFlow<Set<String>> = _completedSessions.asStateFlow()

    val agentState: StateFlow<AgentUIState> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(AgentUIState.Idle)
            else _agentStates.map { it[id] ?: AgentUIState.Idle }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentUIState.Idle)

    private fun setAgentState(sessionId: String, state: AgentUIState) {
        _agentStates.value = _agentStates.value + (sessionId to state)
    }

    /**
     * 失败文案：服务端给了类型码（如拒答/上下文超限）时用本地化说明，
     * 并附上服务端的具体理由（如果有）；无类型码时直接展示原错误文本。
     */
    private fun describeFailure(event: AgentEvent.Failed): String {
        val localized = when (event.reasonCode) {
            // Gemini 的 finishReason 全大写，这几种与 Anthropic 的 refusal 同义（内容策略拦截）。
            "refusal", "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" ->
                context.getString(R.string.agent_stop_refusal)
            "model_context_window_exceeded" -> context.getString(R.string.agent_stop_context_exceeded)
            "input_budget_exceeded" -> context.getString(R.string.agent_input_budget_exceeded)
            else -> null
        } ?: return event.error
        return if (event.error.isBlank()) localized else "$localized\n${event.error}"
    }

    private val _messageLimit = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val defaultLimit = 30
    /**
     * 记录「已实际加载到 UI 的 limit 值」。仅在 [messagesState] 的 onEach 中写入。
     * [loadMoreMessages] 用它防重入：上次 bump 的 limit 尚未落地（Room 查询仍在跑）时，
     * loadedMessageLimits[sid] < _messageLimit[sid]，直接 return，避免 fling 经过
     * index 3→2→1→0 时连续四次 bump（30→150），flatMapLatest 不断取消重启查询，
     * 用户划到顶部却看不到加载结果。
     */
    private val loadedMessageLimits = mutableMapOf<String, Int>()

    /** 聊天记录搜索：命中条数上限、输入防抖、片段上下文宽度、定位时预留的分页余量。 */
    private val chatSearchLimit = 50
    private val chatSearchDebounceMs = 300L
    private val snippetContext = 40
    private val messageLimitMargin = 5

    /**
     * 各会话各自的输入草稿，按会话区分持久化到磁盘：进程重启后草稿依然保留。
     * 以前是全局单一一份，在 A 打了半截话切到 B 那半截话会跟着跑过去。
     */
    private val draftPrefs = context.getSharedPreferences("agent_input_drafts", Context.MODE_PRIVATE)
    private val _inputDrafts = MutableStateFlow<Map<String, String>>(
        draftPrefs.all.mapNotNull { (k, v) ->
            (v as? String)?.takeIf { it.isNotEmpty() }?.let { k to it }
        }.toMap()
    )
    /** 草稿防抖落盘簿记：有待写盘的会话 id + 当前防抖任务（见 DRAFT_PERSIST_DEBOUNCE_MS）。 */
    private val dirtyDraftIds = mutableSetOf<String>()
    private var draftPersistJob: Job? = null
    val inputDraft: StateFlow<String> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf("") else _inputDrafts.map { it[id].orEmpty() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun updateInputDraft(text: String) {
        updateInputDraft(_currentSessionId.value, text)
    }

    /**
     * 保存指定会话的输入框草稿。内存 Map 立即更新（切会话/回页面时 [draftOf] 同步查表零延迟），
     * 磁盘落盘走 300ms 防抖合并：打字时每击键一次 editor.apply() 会带来高频的
     * SharedPreferences 内存快照克隆（调用线程=主线程）+ XML 全文件序列化磁盘写（IO 线程），
     * 是输入期间可感知的持续负载。防抖窗口内的多次修改合并为一次写。
     */
    fun updateInputDraft(sessionId: String?, text: String) {
        val id = sessionId?.takeIf(String::isNotBlank) ?: return
        _inputDrafts.value = if (text.isEmpty()) _inputDrafts.value - id
        else _inputDrafts.value + (id to text)
        synchronized(dirtyDraftIds) { dirtyDraftIds += id }
        draftPersistJob?.cancel()
        draftPersistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(DRAFT_PERSIST_DEBOUNCE_MS)
            val ids: List<String>
            synchronized(dirtyDraftIds) {
                ids = dirtyDraftIds.toList()
                dirtyDraftIds.clear()
            }
            if (ids.isEmpty()) return@launch
            runCatching {
                val editor = draftPrefs.edit()
                // 读当前快照而非闭包里的 text：取消-重启的旧任务可能带着别的会话的脏标记，
                // 永远以最新值为准；已被删除/清空的会话读到 null → remove（幂等）。
                val snapshot = _inputDrafts.value
                for (sid in ids) {
                    val t = snapshot[sid]
                    if (t.isNullOrEmpty()) editor.remove(sid) else editor.putString(sid, t)
                }
                editor.apply()
            }
        }
    }

    fun clearInputDraft() {
        val id = _currentSessionId.value ?: return
        _inputDrafts.value = _inputDrafts.value - id
        draftPrefs.edit().remove(id).apply()
    }

    /**
     * 草稿表同步快照读取（内存 Map 查询，零开销、不挂起）。
     *
     * 给输入框在会话切换时取初值用：[inputDraft] 走 flatMapLatest，会话切换的发射是异步的，
     * 切换瞬间流里仍是上一个会话的旧草稿；直接查表永远拿到当前会话的正确值，
     * 避免旧会话草稿闪进新会话输入框（极快输入时甚至会把旧草稿写进新会话）。
     */
    fun draftOf(sessionId: String?): String =
        sessionId?.takeIf(String::isNotBlank)?.let { _inputDrafts.value[it] }.orEmpty()

    /**
     * 工具调用（分组头与单条工具卡片）的手动展开态：key = 分组 key（`toolgroup:<组内首条消息 id>`）
     * 或单条消息 id。
     *
     * 放在 ViewModel 而不是组合里：窄窗下打开设置 / 终端 / Git / 编辑器都是全屏路由，聊天页整棵
     * 组合被 dispose，`remember` 的 map 与按 message.id 的 remember 会一起丢——展开过的工具
     * 一离开视线（滚出屏幕被回收、切页返回）就缩回默认态。这里按 App 进程的内存保留，
     * 会话间互不影响（key 取消息 id，全局唯一），不落盘。
     */
    val toolExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** 记录一次手动展开/收起（取值由调用方按当前可见态取反后传入）。 */
    fun setToolExpanded(key: String, expanded: Boolean) {
        toolExpansionOverrides[key] = expanded
    }

    /**
     * 整轮任务折叠（外层「执行中 / 已完成」头）的手动展开态：key = `turn:<轮首消息 id>`。
     *
     * 与 [toolExpansionOverrides] 同款：按 App 进程内存保留、不落盘。没记录时按「末轮运行中默认展开、
     * 其余默认收起」推默认值（见 AIChatPanel.buildChatItems 的 activeTurnKey）。
     */
    val turnExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /**
     * 按会话 id 缓存「消息 id → 稳定轮 key」映射。分页补齐时新加载的消息可能插在已有轮首之前，
     * 导致同一轮的 key 在补齐前后不一致。reconcileTurnKeys 沿用这里的旧 key，保证展开选择与
     * LazyColumn 锚点不丢。进程内存、不落盘（切会话/杀进程后重建，无副作用）。
     */
    val turnMessageKeys = mutableMapOf<String, MutableMap<String, String>>()

    fun setTurnExpanded(key: String, expanded: Boolean) {
        turnExpansionOverrides[key] = expanded
    }

    /**
     * 思考过程展开态的手动选择：key = 助手消息 id。与 [toolExpansionOverrides] 同款（进程内存、不落盘），
     * 使思考卡片划出视口回收、切页返回后仍保持展开/收起。
     */
    val reasoningExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** 记录一次思考卡片的展开/收起。 */
    fun setReasoningExpanded(messageId: String, expanded: Boolean) {
        reasoningExpansionOverrides[messageId] = expanded
    }

    /** 思考过程耗时（毫秒）：key = 助手消息 id。记录流式思考的实际耗时，供落库后的卡片展示一位小数秒数。 */
    val reasoningDurations = mutableStateMapOf<String, Long>()

    fun loadMoreMessages() {
        val sid = _currentSessionId.value ?: return
        val state = messagesState.value
        if (state.sessionId != sid || !state.loaded || !state.hasMore) return
        val currentLimit = _messageLimit.value[sid] ?: defaultLimit
        if (loadedMessageLimits[sid] != currentLimit) return
        _messageLimit.value = _messageLimit.value + (sid to (currentLimit + 30))
    }

    /** 容器初始化实时进度（解压/部署/装包），AI 页底部气泡展示。 */
    val containerInit: StateFlow<ContainerInitState> = containerEngine.initProgress

    private val _currentWorkspace = MutableStateFlow<String>("")
    fun setWorkspace(path: String) {
        if (path.isBlank() || _currentWorkspace.value == path) return
        _currentWorkspace.value = path
        viewModelScope.launch {
            val result = legacySubAgentMigration.run(path)
            if (result.scanComplete && result.hasLegacyDefinitions && subAgentModelSettings.shouldShowLegacyNoticeOnce()) {
                Toast.makeText(
                    context,
                    context.getString(R.string.subagents_legacy_notice, result.files.size),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        // 搜索限定当前工作区，切区后旧结果无意义，一并清空。
        _chatSearchQuery.value = ""
    }

    val sessions: StateFlow<List<ChatSession>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyList())
            else chatSessionDao.getRootSessionsByWorkspace(path)
                .map { list -> list.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 所有根会话的子代理，按父会话 id 分组，供侧边栏会话行就地展开。
     * 用全量查询而非逐行惰加载：同一张表一次读完，避开每展开一行开一个 Flow 的订阅风暴。
     */
    val subSessionsByParent: StateFlow<Map<String, List<ChatSession>>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyMap())
            else chatSessionDao.getAllSessionsByWorkspace(path).map { list ->
                list.filter { it.parentId != null }
                    .groupBy({ it.parentId!! }, { it.toDomain() })
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ── 聊天记录全局搜索（限当前工作区）──
    private val _chatSearchQuery = MutableStateFlow("")
    val chatSearchQuery: StateFlow<String> = _chatSearchQuery.asStateFlow()

    /** 待定位的消息（会话 id to 消息 id）；聊天页滚动到位或确认无法定位后消费清除。 */
    private val _pendingScrollMessage = MutableStateFlow<Pair<String, String>?>(null)
    val pendingScrollMessage: StateFlow<Pair<String, String>?> = _pendingScrollMessage.asStateFlow()

    /**
     * 搜索状态：工作区与关键词变化时防抖后查询，命中映射为带片段的 UI 模型。
     * 查询走 IO 调度器，避免 LIKE 全表扫描卡住主线程。
     */
    val chatSearchState: StateFlow<ChatSearchState> =
        combine(_currentWorkspace, _chatSearchQuery) { ws, q -> ws to q }
            .debounce(chatSearchDebounceMs)
            .flatMapLatest { (workspace, query) ->
                val keyword = query.trim()
                if (workspace.isBlank() || keyword.isEmpty()) {
                    flowOf(ChatSearchState(query = query))
                } else {
                    flow {
                        emit(ChatSearchState(query = query, loading = true))
                        val hits = withContext(Dispatchers.IO) {
                            agentMessageDao.searchInWorkspace(
                                workspace,
                                escapeLike(keyword),
                                chatSearchLimit
                            ).map { m ->
                                ChatSearchHit(
                                    sessionId = m.sessionId,
                                    sessionTitle = m.sessionTitle,
                                    messageId = m.messageId,
                                    snippet = buildSnippet(m.content, keyword),
                                    timestamp = m.timestamp
                                )
                            }
                        }
                        emit(ChatSearchState(query = query, hits = hits))
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ChatSearchState())

    fun updateChatSearchQuery(query: String) {
        _chatSearchQuery.value = query
    }

    fun clearChatSearch() {
        _chatSearchQuery.value = ""
        _pendingScrollMessage.value = null
    }

    /**
     * 打开一条搜索命中：切到对应会话，把该会话分页上限抬到足够包含目标消息，并登记待定位消息。
     * 实际滚动由聊天页在消息就绪后完成。搜索词与结果保留，重开侧边栏仍停在结果列表。
     */
    fun openChatSearchHit(hit: ChatSearchHit) {
        selectSession(hit.sessionId)
        viewModelScope.launch {
            val timestamp = withContext(Dispatchers.IO) {
                agentMessageDao.getMessageById(hit.messageId)?.timestamp
            }
            if (timestamp != null) {
                val needed = withContext(Dispatchers.IO) {
                    agentMessageDao.countMessagesFromTimestamp(hit.sessionId, timestamp)
                } + messageLimitMargin
                val current = _messageLimit.value[hit.sessionId] ?: defaultLimit
                if (needed > current) {
                    _messageLimit.value = _messageLimit.value + (hit.sessionId to needed)
                }
            }
            _pendingScrollMessage.value = hit.sessionId to hit.messageId
        }
    }

    fun consumePendingScroll() {
        _pendingScrollMessage.value = null
    }

    /** 转义 LIKE 通配符，配合 SQL 里的 ESCAPE '!'（转义字符本身需最先处理）。 */
    private fun escapeLike(raw: String): String =
        raw.replace("!", "!!").replace("%", "!%").replace("_", "!_")

    /** 以命中词为中心截取片段：折叠换行空白，两端按需加省略号；未命中时退回开头一段。 */
    private fun buildSnippet(content: String, keyword: String): String {
        val flat = content.replace(Regex("\\s+"), " ").trim()
        val index = flat.indexOf(keyword, ignoreCase = true)
        if (index < 0) return flat.take(snippetContext * 2)
        val start = (index - snippetContext).coerceAtLeast(0)
        val end = (index + keyword.length + snippetContext).coerceAtMost(flat.length)
        return buildString {
            if (start > 0) append('\u2026')
            append(flat, start, end)
            if (end < flat.length) append('\u2026')
        }
    }

    /** 当前会话完整信息（根会话与子会话通用；null 表示尚未解析出会话）。 */
    val currentSessionState: StateFlow<ChatSession?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else chatSessionDao.getByIdFlow(id).map { it?.toDomain() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentSessionMode: StateFlow<AgentMode> = currentSessionState.map { it?.mode ?: AgentMode.BUILD }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentMode.BUILD)

    /** 当前会话的思考强度（默认 MEDIUM）。 */
    val currentSessionReasoningEffort: StateFlow<ReasoningEffort> =
        currentSessionState.map { it?.reasoningEffort ?: ReasoningEffort.MEDIUM }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ReasoningEffort.MEDIUM)

    /** 当前会话绑定的 providerId/model（null 表示未绑定，回退全局 active provider）。 */
    val currentSessionProviderModel: StateFlow<Pair<String?, String?>> =
        currentSessionState.map { s -> (s?.providerId ?: "") to (s?.model ?: "") }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Pair(null, null))

    /**
     * 当前会话的消息状态：会话切换时自动切换到对应历史，并携带所属会话 id 与 loaded 标志，
     * 使 UI 能区分「切换/冷启动加载中」与「空会话」——避免先闪 Welcome 或上一个会话的消息再突然刷新。
     * 过滤掉「纯工具调用」的空助手行（content 为空、仅用于回放配对，不应显示为气泡）。
     */
    val messagesState: StateFlow<ChatMessagesState> = combine(
        _currentSessionId,
        _messageLimit
    ) { id, limitMap -> id to (limitMap[id] ?: defaultLimit) }
        .flatMapLatest { (id, limit) ->
            if (id == null) flowOf(ChatMessagesState(null, emptyList(), loaded = false))
            else {
                val messageMapper = CachedUiMessageMapper()
                agentMessageDao.getMessagesBySessionPaged(id, limit)
                    .distinctUntilChanged()
                    .map { list ->
                        val visibleEntities = list.filterNot {
                            it.role == MessageRole.ASSISTANT.name &&
                                !it.content.hasVisibleContent() &&
                                it.reasoning.isNullOrEmpty()
                        }
                        ChatMessagesState(
                            sessionId = id,
                            messages = messageMapper.map(id, visibleEntities),
                            loaded = true,
                            hasMore = list.size >= limit,
                            isLoadingMore = false
                        )
                    }
                    .flowOn(Dispatchers.Default)
                    .onEach { loadedMessageLimits[id] = limit }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatMessagesState(null, emptyList(), loaded = false))


    private val _runningTools = MutableStateFlow<Map<String, Map<String, RunningToolOutput>>>(emptyMap())
    private val runningToolSelector = RunningToolFlowSelector(_currentSessionId, _runningTools)
    private data class ToolProgressKey(val sessionId: String, val messageId: String)
    private val toolProgressLock = Any()
    private val pendingToolProgress = mutableMapOf<ToolProgressKey, RunningToolOutput>()
    private val toolProgressJobs = mutableMapOf<ToolProgressKey, Job>()
    private val lastToolProgressAtMs = mutableMapOf<ToolProgressKey, Long>()

    /** 顶栏只订阅工具名称；同一工具的高频输出不会令整个聊天页重组。 */
    val activeRunningToolName = runningToolSelector.activeToolName

    /** 工具卡片按 messageId 订阅自己的实时输出。 */
    fun runningToolOutput(messageId: String) = runningToolSelector.outputFor(messageId)

    /** 工具分组只订阅组内是否仍有运行项。 */
    fun hasRunningTool(messageIds: Set<String>) = runningToolSelector.anyRunning(messageIds)

    /** 添加/更新一个运行中工具（按 msgId 定位，支持多个工具并行）。 */
    private fun setRunningTool(sessionId: String, msgId: String, tool: RunningToolOutput) {
        synchronized(toolProgressLock) {
            val sessionTools = _runningTools.value[sessionId] ?: emptyMap()
            _runningTools.value = _runningTools.value + (sessionId to (sessionTools + (msgId to tool)))
        }
    }

    /** 移除一个运行中工具；会话无剩余运行工具时清除该会话条目。 */
    private fun removeRunningTool(sessionId: String, msgId: String) {
        synchronized(toolProgressLock) {
            cancelPendingToolProgressLocked(ToolProgressKey(sessionId, msgId), clearLast = true)
            val sessionTools = _runningTools.value[sessionId] ?: return
            val updated = sessionTools - msgId
            _runningTools.value = if (updated.isEmpty()) {
                _runningTools.value - sessionId
            } else {
                _runningTools.value + (sessionId to updated)
            }
        }
    }

    /** 合并高频工具进度，最多每 60ms 更新一次卡片；窗口末尾冲刷最新文本，终态会取消待发旧值。 */
    private fun publishToolProgress(sessionId: String, messageId: String, output: RunningToolOutput) {
        val key = ToolProgressKey(sessionId, messageId)
        val now = System.nanoTime() / NANOS_PER_MILLISECOND
        synchronized(toolProgressLock) {
            pendingToolProgress[key] = output
            val elapsed = now - (lastToolProgressAtMs[key] ?: Long.MIN_VALUE / 2)
            if (elapsed >= TOOL_PROGRESS_INTERVAL_MS) {
                cancelPendingToolProgressLocked(key, clearLast = false)
                pendingToolProgress.remove(key)
                lastToolProgressAtMs[key] = now
                val sessionTools = _runningTools.value[sessionId] ?: emptyMap()
                _runningTools.value = _runningTools.value + (sessionId to (sessionTools + (messageId to output)))
            } else if (toolProgressJobs[key] == null) {
                val remaining = (TOOL_PROGRESS_INTERVAL_MS - elapsed).coerceAtLeast(1L)
                toolProgressJobs[key] = viewModelScope.launch {
                    delay(remaining)
                    synchronized(toolProgressLock) {
                        val latest = pendingToolProgress.remove(key)
                        toolProgressJobs.remove(key)
                        if (latest != null) {
                            lastToolProgressAtMs[key] = System.nanoTime() / NANOS_PER_MILLISECOND
                            val sessionTools = _runningTools.value[sessionId] ?: emptyMap()
                            _runningTools.value = _runningTools.value + (sessionId to (sessionTools + (messageId to latest)))
                        }
                    }
                }
            }
        }
    }

    /** Must be called while [toolProgressLock] is held. */
    private fun cancelPendingToolProgressLocked(key: ToolProgressKey, clearLast: Boolean) {
        toolProgressJobs.remove(key)?.cancel()
        pendingToolProgress.remove(key)
        if (clearLast) lastToolProgressAtMs.remove(key)
    }

    private fun clearPendingToolProgress(sessionId: String) {
        synchronized(toolProgressLock) {
            (toolProgressJobs.keys + pendingToolProgress.keys + lastToolProgressAtMs.keys)
                .filter { it.sessionId == sessionId }
                .distinct()
                .forEach { cancelPendingToolProgressLocked(it, clearLast = true) }
        }
    }

    private val _preparingTools = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 模型正在流式产出、还没开始执行的工具名（如 `editFile`）。
     *
     * 长参数工具（写整份文件、长命令）的参数流式可能持续好几秒，期间没有正文也没有思考增量，
     * UI 只能显示笼统的「正在思考」。上游在工具名一出现就上报（[AgentEvent.ToolCallPreparing]），
     * UI 据此把状态换成具体场景（「正在编辑文件」）。工具真正开始执行后由 [activeRunningToolName] 接管。
     */
    val preparingTool: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _preparingTools.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setPreparingTool(sessionId: String, toolName: String?) {
        val current = _preparingTools.value
        if (toolName == null) {
            if (current.containsKey(sessionId)) _preparingTools.value = current - sessionId
        } else if (current[sessionId] != toolName) {
            _preparingTools.value = current + (sessionId to toolName)
        }
    }

    private val _compactingSessions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val _contextUsages = MutableStateFlow<Map<String, AgentEvent.ContextUsage>>(emptyMap())
    val contextUsages: StateFlow<Map<String, AgentEvent.ContextUsage>> = _contextUsages.asStateFlow()

    private val _llmCallEvents = MutableSharedFlow<LlmCallEvent>(extraBufferCapacity = 16)
    /** 每次单次 LLM 请求返回事件（携带单次 Token 统计）。 */
    val llmCallEvents: SharedFlow<LlmCallEvent> = _llmCallEvents.asSharedFlow()
    val isCompacting: StateFlow<Boolean> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(false)
            else _compactingSessions.map { it[id] == true }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun setCompacting(sessionId: String, compacting: Boolean) {
        _compactingSessions.value = if (compacting) {
            _compactingSessions.value + (sessionId to true)
        } else {
            _compactingSessions.value - sessionId
        }
    }

    private val _streamingTexts = MutableStateFlow<Map<String, String?>>(emptyMap())
    /** 流式正文发射节流簿记：上次发射时刻 + 被节流窗口暂存的最新值（见 STREAMING_EMIT_MIN_INTERVAL_MS）。 */
    private val lastStreamingTextEmitAt = mutableMapOf<String, Long>()
    private val pendingStreamingTexts = mutableMapOf<String, String?>()
    val streamingText: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingTexts.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setStreamingText(sessionId: String, text: String?) {
        // 增量去重：相同值不触发 emission，避免 delta 期间每秒 ~16 次 Map 复制 + 重组
        val current = _streamingTexts.value[sessionId]
        if (text == current) return
        if (text != null) {
            // 时间窗口节流：累积文本单调增长，丢中间值零损失；null 清空直通保证状态切换即时。
            val now = System.currentTimeMillis()
            if (now - (lastStreamingTextEmitAt[sessionId] ?: 0L) < STREAMING_EMIT_MIN_INTERVAL_MS) {
                pendingStreamingTexts[sessionId] = text
                return
            }
            lastStreamingTextEmitAt[sessionId] = now
        } else {
            lastStreamingTextEmitAt.remove(sessionId)
        }
        pendingStreamingTexts.remove(sessionId)
        _streamingTexts.value = if (text == null) _streamingTexts.value - sessionId
        else _streamingTexts.value + (sessionId to text)
    }

    /** 最新流式正文（含节流窗口暂存的待发射值）：停止/收尾路径读取，保证落库部分内容不缺尾部。 */
    private fun latestStreamingTextOf(sessionId: String): String? =
        pendingStreamingTexts[sessionId] ?: _streamingTexts.value[sessionId]

    private val _reasoningTimings = MutableStateFlow<Map<String, Pair<Long, Long?>>>(emptyMap())
    val reasoningTiming: StateFlow<Pair<Long, Long?>?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else _reasoningTimings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _streamingReasonings = MutableStateFlow<Map<String, String?>>(emptyMap())
    /** 思考流发射节流簿记（与正文流同理，见 STREAMING_EMIT_MIN_INTERVAL_MS）。 */
    private val lastStreamingReasoningEmitAt = mutableMapOf<String, Long>()
    private val pendingStreamingReasonings = mutableMapOf<String, String?>()
    val streamingReasoning: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingReasonings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setStreamingReasoning(sessionId: String, text: String?) {
        // 增量去重 + 修复：清空 reasoning 文本不应清空 reasoning timings（timings 在 finishReasoning / 会话重置时管理）
        val current = _streamingReasonings.value[sessionId]
        if (text == current) return
        if (text != null) {
            val now = System.currentTimeMillis()
            if (now - (lastStreamingReasoningEmitAt[sessionId] ?: 0L) < STREAMING_EMIT_MIN_INTERVAL_MS) {
                pendingStreamingReasonings[sessionId] = text
                return
            }
            lastStreamingReasoningEmitAt[sessionId] = now
        } else {
            lastStreamingReasoningEmitAt.remove(sessionId)
        }
        pendingStreamingReasonings.remove(sessionId)
        _streamingReasonings.value = if (text == null) _streamingReasonings.value - sessionId
        else _streamingReasonings.value + (sessionId to text)
    }

    /** 最新思考流文本（含节流窗口暂存值）：停止路径落库部分思考用。 */
    private fun latestStreamingReasoningOf(sessionId: String): String? =
        pendingStreamingReasonings[sessionId] ?: _streamingReasonings.value[sessionId]

    /** 按 sessionId 维护的重试状态；流式恢复或结束后置 null。 */
    private val _retryStates = MutableStateFlow<Map<String, RetryState?>>(emptyMap())
    val retryState: StateFlow<RetryState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _retryStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setRetryState(sessionId: String, state: RetryState?) {
        _retryStates.value = if (state == null) _retryStates.value - sessionId else _retryStates.value + (sessionId to state)
    }

    /** 按 sessionId 维护的多 Key 切换提示；重新出内容或本轮结束时置 null。 */
    private val _keySwitchStates = MutableStateFlow<Map<String, KeySwitchState?>>(emptyMap())
    val keySwitchState: StateFlow<KeySwitchState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _keySwitchStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setKeySwitchState(sessionId: String, state: KeySwitchState?) {
        _keySwitchStates.value = if (state == null) _keySwitchStates.value - sessionId else _keySwitchStates.value + (sessionId to state)
    }

    val pendingToolPermission = toolPermissionManager.pendingRequest

    /** 当前展示的授权弹窗所属会话标题（多会话并行时供弹窗标注归属）。会话不存在时回退空串。 */
    val pendingToolPermissionSessionTitle: StateFlow<String> = combine(
        toolPermissionManager.pendingRequest,
        sessions,
        subSessionsByParent
    ) { req, roots, subs ->
        val sid = req?.sessionId.orEmpty()
        if (sid.isBlank()) return@combine ""
        val title = roots.firstOrNull { it.id == sid }?.title
            ?: subs.values.asSequence().flatten().firstOrNull { it.id == sid }?.title
        title.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 正在等待用户授权的会话 id 集合，供侧边栏会话行点亮橙色指示灯。 */
    val awaitingPermissionSessionIds: StateFlow<Set<String>> = toolPermissionManager.awaitingSessionIds

    val pendingUserQuestion = askUserQuestionManager.pendingQuestion

    private val _queuedRequests = MutableStateFlow<Map<String, List<QueuedRequest>>>(emptyMap())
    // 正在执行斜杠命令的会话集合：命令执行期间同样视为 busy（
    // 不注册 sessionJobs，否则 /compress 等命令内部的自检会误判为运行中），
    // 用于 enqueueAgentRequest 判断新消息应入队而非并行执行。
    private val _runningCommandSessions = MutableStateFlow<Set<String>>(emptySet())
    val queuedRequests: StateFlow<List<QueuedRequest>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _queuedRequests.map { it[id] ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // 工具调用传入参数（argsPreview）按落库消息 id 暂存：ToolCallStarted 落库后，
    // ToolCallFinished / 用户停止会用同 id REPLACE 整行，需在此把参数带到后续落库。
    private val toolArgsByMsgId = mutableMapOf<String, String>()

    /** 是否有正在运行、可被打断的 agent 任务。 */
    val isRunning: Boolean get() {
        val sid = _currentSessionId.value ?: return false
        return sessionJobs[sid]?.isActive == true
    }

    fun hasRunningSessionsInCurrentWorkspace(): Boolean {
        if (sessions.value.any { sessionJobs[it.id]?.isActive == true }) return true
        // 子代理会话不在 sessions（只含根会话）里：漏掉它们会在父代理这一轮结束时
        // 提前释放唤醒锁与前台服务，仍在跑的子代理会被系统挂起。
        return subSessionsByParent.value.values.any { subs ->
            subs.any { sessionJobs[it.id]?.isActive == true }
        }
    }

    /**
     * 任务开始：拉起前台保活服务（注册为活跃会话）并获取 CPU 唤醒锁，
     * 确保切后台 / 熄屏时进程不被系统挂起、网络 I/O 不断。
     *
     * 使用 startSession（ACTION_START_SESSION）而非 enablePersistent：
     * - startSession 递增 sessionCount，前台服务知道有活跃会话在跑；
     * - startSession 用 startForegroundService 启动，Android 8+ 从后台恢复时也能正确拉起；
     * - ensureForeground 中根据 sessionCount > 0 显示「N 个会话运行中」而非笼统的「保活已开启」。
     */
    private fun acquireKeepalive() {
        TerminalKeepaliveService.startSession(context)
        // 有 ROOT 时顺带解除系统侧的后台网络限制（netpolicy/standby/appops）；
        // 无 ROOT 静默跳过。走 IO 线程：内部要 fork su 子进程，不能碰主线程。
        viewModelScope.launch(Dispatchers.IO) { rootNetworkGuard.applyIfRooted() }
        if (wakeLock.isHeld) return
        runCatching { wakeLock.acquire(KEEPALIVE_TIMEOUT_MS) }
            .onFailure { FileLogger.e(TAG, "acquire wakeLock failed", it) }
    }

    /**
     * 任务收尾：释放唤醒锁；结束活跃会话计数（sessionCount 递减）。
     * 仅当既无后台终端任务、用户也没手动开保活时才停前台服务。
     */
    private fun releaseKeepalive() {
        if (wakeLock.isHeld) {
            runCatching { wakeLock.release() }
                .onFailure { FileLogger.e(TAG, "release wakeLock failed", it) }
        }
        TerminalKeepaliveService.stopSession(context)
        if (!userKeepaliveEnabled && !terminalSessionManager.hasBackgroundTabs()) {
            TerminalKeepaliveService.disablePersistent(context)
        }
    }

    // ── 通知栏任务进度 ──
    // 每个会话只记录「最近一次推给通知的步骤文本」：一节正文收尾时的小段总结去重，
    // 避免相邻两节内容雷同时通知反复刷。
    private val lastPushedTaskStep = mutableMapOf<String, String>()
    // 流式实时预览的时间窗口节流：同一会话 200ms 内只推一次，
    // 避免 delta 期间每秒 ~16 次 SSE chunk 刷爆通知栏（通知重建+startForeground 系统调用）。
    // 200ms = 用户在通知栏看到 AI 实时输出的体感阈值，再快人眼也分不清。
    private val lastPushedPreviewTime = mutableMapOf<String, Long>()
    private val lastPushedPreviewText = mutableMapOf<String, String>()

    /** 推进通知栏里的任务步骤（一节正文的小段总结）；与上一步相同则忽略。 */
    private fun pushTaskStep(sessionId: String, step: String) {
        if (lastPushedTaskStep[sessionId] == step) return
        lastPushedTaskStep[sessionId] = step
        // 写 steps 列表的同时清掉 livePreview：这节正文已收尾，实时预览让位给小结
        TerminalKeepaliveService.updateTaskProgress(sessionId, title = null, step = step, livePreview = "")
    }

    /**
     * 推送流式实时预览到通知栏正文（不进 steps 列表）。
     * 每收到一个 delta 就覆盖更新，让用户在通知栏实时看到 AI 正在写什么。
     * 传 null 清空预览（切到工具调用等场景）。
     *
     * 节流：200ms 时间窗口 + 增量去重（相同文本不重复推）。
     * delta 期间每秒 ~16 次 SSE chunk，不做节流会让通知重建+startForeground 系统调用
     * 刷爆主线程——这是 1.1.0 引入通知栏进度后全局卡顿的直接元凶。
     *
     * 性能：传入原始 accumulated 文本，**只在通过节流窗口后**才提取最后一段段落。
     * 不在调用端提取是因为 lines() 在不断增长的正文上每秒调用 16 次会产生
     * ~3MB/秒的 String 分配（GC 锯齿元凶）。节流后 200ms 才提取一次，分配量降到 ~1/40。
     */
    private fun pushTaskLivePreview(sessionId: String, rawContent: String?) {
        if (rawContent == null) {
            // null = 清空预览
            if (lastPushedPreviewText[sessionId] == "") return
            lastPushedPreviewText[sessionId] = ""
            lastPushedPreviewTime[sessionId] = System.currentTimeMillis()
            TerminalKeepaliveService.updateTaskProgress(
                sessionId, title = null, step = null, livePreview = ""
            )
            return
        }
        // 时间窗口：200ms 内同会话只推一次。
        // **在提取前检查**：避免对 100KB+ 的全文调 lines() 后才发现被节流丢弃。
        val now = System.currentTimeMillis()
        val lastTime = lastPushedPreviewTime[sessionId] ?: 0L
        if (now - lastTime < 200) return
        // 提取最后一段非空行（只在通过节流后执行一次）
        val preview = extractLastParagraph(rawContent)
        if (preview.isEmpty()) return
        // 增量去重：文本相同则跳过
        if (lastPushedPreviewText[sessionId] == preview) return
        lastPushedPreviewText[sessionId] = preview
        lastPushedPreviewTime[sessionId] = now
        TerminalKeepaliveService.updateTaskProgress(
            sessionId, title = null, step = null, livePreview = preview
        )
    }

    /**
     * 把一节正文提炼成通知栏小段总结：取首个非空行，超长截断加省略号。
     * 一节正文往往就是 AI 对当前要做的事情的一句话说明（"我来搜索…"、"找到了，现在编辑…"），
     * 取首行即是对 AI 当前意图的最佳概括。收尾后写入 steps 列表（展开通知逐行回看各节做了什么）。
     *
     * 零分配：直接搜索第一个换行符取首行，不用 lines()（lines() 会 split 全文创建 List+全部行 String）。
     */
    private fun summarizeForNotification(content: String): String {
        if (content.isBlank()) return ""
        // 找第一个非空白行的结尾
        var start = 0
        var end = content.length
        // 跳过前导空白行
        var i = 0
        while (i < content.length) {
            // 跳过空白字符
            while (i < content.length && content[i].isWhitespace() && content[i] != '\n') i++
            if (i >= content.length) break
            if (content[i] == '\n') { i++; continue }
            // 找到非空行起点
            start = i
            val nl = content.indexOf('\n', start)
            end = if (nl < 0) content.length else nl
            break
        }
        val firstLine = content.substring(start, end).trim()
        if (firstLine.isEmpty()) return ""
        return if (firstLine.length <= NOTIFICATION_SUMMARY_MAX) firstLine
        else firstLine.take(NOTIFICATION_SUMMARY_MAX) + "…"
    }

    /**
     * 从累积正文中提取最后一段非空行——即 AI 此刻正在写的那一小段。
     *
     * 零分配实现：直接搜索最后一个换行符，取其后的子串。
     * 不用 content.lines()：lines() 会对全文做 split 创建 List + 全部行的 String 对象，
     * 当正文增长到 100KB+(2000+ 行) 时每次调用分配 ~200KB，16/秒 = 3MB/秒(GC 锯齿元凶)。
     * 直接 lastIndexOf+substring 只创建 1 个 String 对象。
     *
     * 超长从尾部截断（保留段落开头）：一小段的开头往往是"现在/接着/于是"这类动作词，
     * 看开头即知 AI 在做什么；尾部往往是半句话，截断尾部信息损失更小。
     */
    private fun extractLastParagraph(content: String): String {
        // 从尾部跳过空白行，找到最后一个有内容的行
        var end = content.length
        // 跳过尾部空白
        while (end > 0 && content[end - 1].isWhitespace()) end--
        if (end == 0) return ""
        // 找最后一个换行符（在 end 之前）
        var nl = content.lastIndexOf('\n', end - 1)
        // 如果最后一个字符就是换行，往前再找一个
        if (nl == end - 1) {
            nl = content.lastIndexOf('\n', nl - 1)
        }
        val lastLine = content.substring(nl + 1, end).trim()
        if (lastLine.isEmpty()) return ""
        return if (lastLine.length <= NOTIFICATION_SUMMARY_MAX) lastLine
        else lastLine.take(NOTIFICATION_SUMMARY_MAX) + "…"
    }

    /** 任务收工：清掉通知栏进度条目与去重记录（通知随即回退为普通保活文案）。 */
    private fun clearTaskProgress(sessionId: String) {
        lastPushedTaskStep.remove(sessionId)
        TerminalKeepaliveService.clearTaskProgress(sessionId)
    }

    /** App 退到后台时 Agent 完成，弹一条可点击的系统通知（标题=任务完成，正文=用户消息）。 */
    private fun showAgentCompletedNotification(userRequest: String) {
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AGENT_COMPLETE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(context.getString(R.string.agent_complete_notification_title))
            .setContentText(agentCompleteNotificationBody(userRequest))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(AGENT_COMPLETE_NOTIFICATION_ID, notification)
        }.onFailure { FileLogger.e(TAG, "发送 agent 完成通知失败", it) }
    }

    /**
     * 系统通知正文：普通用户消息直接展示；后台回调触发的轮次不裸露
     * 「[系统通知 - 非用户输入]…」内部构造文本，改为展示任务标题。
     */
    private fun agentCompleteNotificationBody(userRequest: String): String {
        if (userRequest.startsWith(BACKGROUND_NOTIFICATION_PREFIX)) {
            val titles = TASK_NOTIFICATION_TITLE_REGEX.findAll(userRequest)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() }
                .toList()
            return when {
                titles.isEmpty() -> context.getString(R.string.agent_complete_notification_body)
                titles.size == 1 -> context.getString(
                    R.string.agent_complete_notification_background_body, titles.first()
                )
                else -> context.getString(
                    R.string.agent_complete_notification_background_multi_body, titles.size
                )
            }
        }
        return userRequest.ifBlank { context.getString(R.string.agent_complete_notification_body) }
    }

    private companion object {
        const val TAG = "AIAgentViewModel"
        const val TOOL_PROGRESS_INTERVAL_MS = 60L
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val MAX_REWIND_FAILURES_IN_DIALOG = 30
        const val MAX_REGENERATED_IMAGE_BYTES = 20L * 1024 * 1024
        const val REWIND_CANCEL_TIMEOUT_MS = 15_000L
        const val MAX_DELETE_CASCADE_STABILIZATION_ATTEMPTS = 3
        const val AGENT_COMPLETE_CHANNEL = "agent_complete"
        const val AGENT_COMPLETE_NOTIFICATION_ID = 100
        /** 通知栏小段总结最大字符数：超长截断加省略号，保证通知正文一行能看完。 */
        const val NOTIFICATION_SUMMARY_MAX = 48
        /**
         * 流式正文/思考的 UI 发射节流窗口。快模型的 delta 可达 20-50 次/秒，每次 emission 都会让
         * 聊天尾部 __active__ item 全量重组（前缀检查 + 两个 LaunchedEffect 重启 + 打字机推进），
         * 是 AI 高频输出/工具执行期间全局掉帧的持续负载源。累积文本单调增长，窗口内丢掉中间值
         * 零损失（下一个 delta 是超集）；打字机渲染有自适应速率，视觉不需要 20-50Hz 的源数据。
         * null（清空/切换/收尾）不节流直通。被节流的最新值存 [pendingStreamingTexts]，
         * 停止路径（stopAgentSession 落库部分内容）通过 latestStreamingTextOf 读到含暂存值的最新文本。
         */
        const val STREAMING_EMIT_MIN_INTERVAL_MS = 80L
        /**
         * 草稿落盘防抖窗口：内存 Map 即时更新，磁盘写合并到停顿后一次（见 updateInputDraft）。
         * 超过该窗口未落盘即进程被杀会丢最多 300ms 的击键——SharedPreferences.apply 本就
         * 不承诺落盘即时性，此取舍换取输入期间零高频磁盘写。
         */
        const val DRAFT_PERSIST_DEBOUNCE_MS = 300L
        /** wakeLock 超时保险：构建、装依赖类工具动辄十几分钟，给足 60 分钟；任务正常结束会主动释放。 */
        const val KEEPALIVE_TIMEOUT_MS = 60 * 60 * 1000L
        /** 从后台任务通知文本中提取 <title> 内容，供系统通知正文展示。 */
        val TASK_NOTIFICATION_TITLE_REGEX = Regex("<title>([^<]+)</title>")
    }

    init {
        // 冷启动收尾：上次进程被杀时残留的「执行中」工具回填为「已中断」。在后台执行，不阻塞首屏与会话展示。
        viewModelScope.launch(Dispatchers.IO) {
            sessionUseCase.initColdStartCleanup()
        }

        // 启动与工作区切换时重新扫描技能（项目级技能随工作区变化），刷新 `/` 命令菜单。
        viewModelScope.launch {
            _currentWorkspace.collect { slashCommandRegistry.refresh() }
        }

        viewModelScope.launch {
            _currentWorkspace.collectLatest { path ->
                if (path.isBlank()) return@collectLatest
                val recent = sessionUseCase.getMostRecentSessionOfWorkspace(path)
                // 立即确定并设置当前会话，确保首帧 UI 秒开、侧边栏立即出现新会话，彻底消除转圈卡顿。
                // 「打开最近会话」有历史就直接进最近那个（按 updatedAt，不受置顶影响）；
                // 「新开会话」复用还没发过消息的空会话（避免每次启动都堆一个空会话），其余情况新建。
                val targetId = when {
                    recent == null -> createAndUpsertSession(path)
                    generalSettingsRepository.startupSessionMode() == StartupSessionMode.RECENT_SESSION -> recent.id
                    sessionUseCase.isSessionEmpty(recent.id) -> recent.id
                    else -> createAndUpsertSession(path)
                }
                _currentSessionId.value = targetId

                // 异步回收多余的空会话（保留当前 targetId），在后台执行，不卡主线程与首帧渲染。
                if (recent != null) {
                    launch(Dispatchers.IO) {
                        sessionUseCase.recycleEmptySessions(path, keepId = targetId)
                    }
                }
            }
        }

        // 订阅后台命令完成事件：notify=true 的命令结束后自动注入消息并触发 AI 新一轮。
        // 会话忙碌期间到达的事件会被缓存，待本轮结束后合并成一条发送（见 [flushMergedNotifications]）。
        viewModelScope.launch {
            terminalSessionManager.tabFinishedEvents.collect { event ->
                handleBackgroundCommandFinished(event)
            }
        }

        viewModelScope.launch {
            keepaliveSettings.enabledFlow.collect { userKeepaliveEnabled = it }
        }

        // 订阅子代理生命周期事件（类比 terminal 的 notify=true 异步回调）：
        // - SPAWNED：task 工具已创建子会话并替用户发消息，这里在子会话上自动启动 AI 工作流；
        // - STOP_REQUESTED：task(action="stop") 请求停止；并发/写入范围槽位保留到协程真正结束；
        // - STOPPED：取消完成后的终态；COMPLETED/FAILED/TIMED_OUT 同样注入父会话通知。
        viewModelScope.launch {
            subAgentEventBus.events.collect { event ->
                when (event.type) {
                    SubAgentEventType.SPAWNED -> spawnSubAgentWorkflow(event)
                    SubAgentEventType.STOP_REQUESTED -> stopAgentSession(event.subSessionId)
                    SubAgentEventType.STOPPED -> {
                        stoppingSubAgentIds.remove(event.subSessionId)
                        enqueueSubAgentNotification(event)
                    }
                    SubAgentEventType.DELETE_REQUESTED -> deleteSubAgentAfterStop(event)
                    SubAgentEventType.COMPLETED, SubAgentEventType.FAILED, SubAgentEventType.TIMED_OUT -> {
                        enqueueSubAgentNotification(event)
                    }
                    SubAgentEventType.MESSAGE_FROM_PARENT -> deliverMessageToSubAgent(event)
                    SubAgentEventType.MESSAGE_FROM_SUB -> deliverMessageToParent(event)
                }
            }
        }
    }

    /**
     * 子代理已创建（task 工具已创建子会话）：在子会话上启动 AI 工作流。
     * 消息由 executeAgentRequestStream 统一落库（与用户手动发消息一致），
     * 标题保留 task 传入的 description（skipTitleUpdate）。
     */
    private suspend fun spawnSubAgentWorkflow(event: SubAgentEvent) {
        // 事件总线是有缓冲的：父工作流可能在 SPAWNED 已入队、尚未消费时就请求删除。
        // 此时不启动新工作流，已创建的行仍由删除级联一起清掉。
        if (event.subSessionId in deletingSubAgentIds || event.parentSessionId in deletingSubAgentIds) {
            subAgentEventBus.release(event.subSessionId)
            return
        }
        val parentSession = sessionUseCase.getSessionById(event.parentSessionId)
        if (parentSession == null) {
            FileLogger.w(TAG, "子代理父会话不存在: ${event.parentSessionId}")
            sessionUseCase.updateSubagentStatus(event.subSessionId, "FAILED")
            subAgentEventBus.release(event.subSessionId)
            return
        }
        // 子会话可能已被用户删除，跳过
        if (sessionUseCase.getSessionById(event.subSessionId) == null) {
            FileLogger.w(TAG, "子代理会话已被删除，跳过启动: ${event.subSessionId}")
            subAgentEventBus.release(event.subSessionId)
            return
        }
        // 子代理运行中不允许重复启动（同一会话已有活跃 job）
        if (sessionJobs[event.subSessionId]?.isActive == true) return

        sessionUseCase.updateSubagentStatus(event.subSessionId, "RUNNING")
        val childJob = executeAgentRequestStream(
            request = event.detail,
            projectRoot = parentSession.workspacePath,
            targetSessionId = event.subSessionId,
            skipTitleUpdate = true
        )
        val timeoutMillis = subAgentSettings.timeoutMillis()
        viewModelScope.launch {
            val finished = withTimeoutOrNull(timeoutMillis) {
                childJob.join()
                true
            } == true
            if (!finished && childJob.isActive) {
                childJob.cancelAndJoin()
                sessionUseCase.updateSubagentStatus(event.subSessionId, "TIMED_OUT")
                subAgentEventBus.emit(
                    SubAgentEvent(
                        subSessionId = event.subSessionId,
                        parentSessionId = event.parentSessionId,
                        type = SubAgentEventType.TIMED_OUT,
                        detail = "子代理运行超过配置的 ${timeoutMillis / 60_000} 分钟上限，已取消。"
                    )
                )
            }
        }
    }

    /** Deletion is serialized through the workflow owner: never remove a running child row before its job has joined. */
    private fun deleteSubAgentAfterStop(event: SubAgentEvent) {
        deletingSubAgentIds += event.subSessionId
        viewModelScope.launch {
            try {
                val child = sessionUseCase.getSessionById(event.subSessionId) ?: return@launch
                if (child.parentId != event.parentSessionId) return@launch

                // Stop/join before the final traversal. The root can have emitted a child-dispatch event
                // just before cancellation; repeat until the post-order tree is stable, then delete it.
                var cascade = sessionUseCase.getSessionCascadeIds(event.subSessionId)
                var stable = false
                for (attempt in 0 until MAX_DELETE_CASCADE_STABILIZATION_ATTEMPTS) {
                    if (!cancelSessionsAndAwait(cascade)) return@launch
                    val refreshed = sessionUseCase.getSessionCascadeIds(event.subSessionId)
                    if (refreshed.toSet() == cascade.toSet()) {
                        cascade = refreshed
                        stable = true
                        break
                    }
                    cascade = refreshed
                }
                if (!stable) {
                    showRewindNotice("子代理仍有新任务正在登记；为避免留下孤儿会话，本次删除已取消，请稍后重试。")
                    return@launch
                }
                cascade.forEach { sid ->
                    checkpointManager.clearSessionCheckpoints(sid)
                    todoItemDao.deleteBySession(sid)
                }
                val deletedIds = sessionUseCase.deleteSession(event.subSessionId).toSet()
                deletedIds.forEach { sid ->
                    deletingSubAgentIds.remove(sid)
                    clearSessionTransientState(sid)
                }
                if (_currentSessionId.value in deletedIds) {
                    _currentSessionId.value = event.parentSessionId.takeIf { sessionUseCase.getSessionById(it) != null }
                        ?: createAndUpsertSession(child.workspacePath)
                }
                showRewindNotice("子代理会话、消息、待办和检查点已删除；用量统计保留。")
            } finally {
                deletingSubAgentIds.remove(event.subSessionId)
            }
        }
    }

    /**
     * 子代理完成/失败通知：父会话忙碌时先入队（由本轮内下一批工具结果搭车送达，见
     * [com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow]），空闲时立即注入一条系统通知消息触发新一轮。
     * 父代理据此得知子代理结束，可 task(action="read") 取回结果。与 terminal 后台通知同机制。
     */
    private fun enqueueSubAgentNotification(event: SubAgentEvent) {
        viewModelScope.launch {
            val state = when (event.type) {
                SubAgentEventType.COMPLETED -> "COMPLETED"
                SubAgentEventType.TIMED_OUT -> "TIMED_OUT"
                SubAgentEventType.STOPPED -> "CANCELLED"
                else -> "FAILED"
            }
            sessionUseCase.updateSubagentStatus(event.subSessionId, state)
            val title = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "子代理"
            notifyParentSubAgentFinished(
                parentSessionId = event.parentSessionId,
                subSessionId = event.subSessionId,
                title = title,
                outcome = when (event.type) {
                    SubAgentEventType.COMPLETED -> NotificationOutcome.COMPLETED
                    SubAgentEventType.STOPPED -> NotificationOutcome.STOPPED
                    else -> NotificationOutcome.FAILED
                },
                detail = event.detail.takeIf { it.isNotBlank() && event.type != SubAgentEventType.COMPLETED }
            )
        }
    }

    /**
     * 主会话发给运行中/已完成子代理的消息：收件人忙碌时入队搭车，空闲时触发新一轮。
     * 与 [notifyParentSubAgentFinished] 同分发逻辑，方向相反。
     */
    private suspend fun deliverMessageToSubAgent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.parentSessionId)?.title ?: "主会话"
        deliverAgentMessage(
            recipientSessionId = event.subSessionId,
            senderSessionId = event.parentSessionId,
            senderTitle = senderTitle,
            message = event.detail,
            fromParent = true
        )
    }

    /** 子代理发给主会话的消息：同上，收件人为主会话。 */
    private suspend fun deliverMessageToParent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "子代理"
        deliverAgentMessage(
            recipientSessionId = event.parentSessionId,
            senderSessionId = event.subSessionId,
            senderTitle = senderTitle,
            message = event.detail,
            fromParent = false
        )
    }

    /**
     * 投递一条代理间消息：收件人忙碌时入 [AgentNotificationCenter]（本轮内工具结果搭车，或整轮结束后兜底），
     * 空闲时以一条通知消息触发其新一轮。消息正文随通知一并送达，收件方无需再另行读取。
     */
    private suspend fun deliverAgentMessage(
        recipientSessionId: String,
        senderSessionId: String,
        senderTitle: String,
        message: String,
        fromParent: Boolean
    ) {
        if (message.isBlank()) return
        if (sessionUseCase.getSessionById(recipientSessionId) == null) return
        val item = PendingNotification(
            kind = AgentNotificationKind.AGENT_MESSAGE,
            sourceId = senderSessionId,
            title = senderTitle,
            outcome = NotificationOutcome.COMPLETED,
            message = message,
            fromParent = fromParent
        )
        deliverSystemEvent(recipientSessionId, item)
    }

    /**
     * 向父会话投递一条子代理结束通知：父会话忙碌时入队搭车，空闲时立即注入触发新一轮。
     * 失败与被终止都要带上原因，否则父代理只知道「没成」，还得再 read 一次才可能拿到线索。
     */
    private suspend fun notifyParentSubAgentFinished(
        parentSessionId: String,
        subSessionId: String,
        title: String,
        outcome: NotificationOutcome,
        detail: String?
    ) {
        val item = PendingNotification(
            kind = AgentNotificationKind.SUBAGENT,
            sourceId = subSessionId,
            title = title,
            outcome = outcome,
            detail = detail
        )
        deliverSystemEvent(parentSessionId, item)
    }

    /**
     * 后台命令（notify=true）结束后的回调。
     *
     * - 会话忙碌：入队 [agentNotificationCenter]，由本轮内下一批工具结果搭车送达（AI 当轮即可感知，
     *   省掉「等本轮结束再起一轮」的 LLM 往返）；整轮再没有工具调用时由 [flushPendingNotifications] 兜底。
     * - 会话空闲：立即以一条系统通知消息触发 Agent 新一轮。
     *
     * 兜底路径用 user 消息而非 assistant(tool_call) + tool_result 消息对：后者会与原 terminal 工具调用的
     * tool 结果在落库顺序上错位（后台回调异步触发，可能抢先于原 terminal 结果落库），导致 messages
     * 违反 OpenAI「assistant(tool_calls) → tool 结果紧跟」的配对约束，上游返回 400。user 消息无需与
     * 任何 tool_call 配对，天然不破坏顺序。通知文本带围栏说明，防止 AI 误判为用户的新指令或批准；
     * AI 据此用 terminal(read) 取回完整输出。
     *
     * 不自行 persist 通知、用 isAutoTrigger=false 走 enqueueAgentRequest 正常流程：由
     * executeAgentRequestStream 统一 persist 这条 user 消息，workflow 的 InitRequest 追加的同一条
     * UserMessage 即是它，避免重复落库或出现空占位消息。
     */
    private fun handleBackgroundCommandFinished(event: TabFinishedEvent) {
        val sessionId = event.sourceSessionId ?: return
        val jobActive = sessionJobs[sessionId]?.isActive == true
        val currentSid = _currentSessionId.value
        FileLogger.d(TAG, "handleBgFinished: eventSid=$sessionId currentSid=$currentSid jobActive=$jobActive state=${_agentStates.value[sessionId]}")
        val item = event.toPendingNotification()
        deliverSystemEvent(sessionId, item)
    }

    /**
     * 统一投递一条系统事件给指定会话：忙碌则入 [agentNotificationCenter]，由本轮内工具结果搭车送达；
     * 空闲则以一条系统通知消息触发新一轮。后台任务完成、子代理结束、代理间消息、模式切换共用此分发，
     * 避免各处重复判断忙碌/空闲。
     */
    private fun deliverSystemEvent(sessionId: String, item: PendingNotification) {
        if (sessionJobs[sessionId]?.isActive == true) {
            agentNotificationCenter.enqueue(sessionId, item)
            return
        }
        viewModelScope.launch {
            enqueueAgentRequest(
                request = AgentNotificationFormatter.buildMessage(listOf(item)),
                projectRoot = _currentWorkspace.value,
                targetSessionId = sessionId
            )
        }
    }

    private fun TabFinishedEvent.toPendingNotification() = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = tabId,
        title = title,
        outcome = if (exitCode == 0) NotificationOutcome.COMPLETED else NotificationOutcome.FAILED,
        command = command,
        exitCode = exitCode,
        tailOutput = tailOutput
    )

    /**
     * 本轮结束后的兜底送达：把仍留在队列里的通知合并成一条消息发送。
     * 已被工具结果搭车送达的通知此时已 ack 移除，取到空则什么都不做。
     */
    private fun flushPendingNotifications(sessionId: String) {
        val items = agentNotificationCenter.drain(sessionId)
        if (items.isEmpty()) return
        FileLogger.d(TAG, "flushPendingNotifications: sid=$sessionId items=${items.size} state=${_agentStates.value[sessionId]}")
        viewModelScope.launch {
            enqueueAgentRequest(
                request = AgentNotificationFormatter.buildMessage(items),
                projectRoot = _currentWorkspace.value,
                targetSessionId = sessionId
            )
        }
    }

    fun enqueueAgentRequest(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        isAutoTrigger: Boolean = false,
        targetSessionId: String? = null
    ) {
        val sid = targetSessionId ?: _currentSessionId.value
        if (sid != null && isSessionRewinding(sid)) {
            showRewindNotice("此会话正在回退或删除；操作完成后再发送新消息。")
            return
        }
        val isCurrentRunning = sid != null &&
            (sessionJobs[sid]?.isActive == true || sid in _runningCommandSessions.value)
        if (isCurrentRunning) {
            val req = QueuedRequest(
                id = UUID.randomUUID().toString(),
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                isAutoTrigger = isAutoTrigger
            )
            val currentList = _queuedRequests.value[sid] ?: emptyList()
            _queuedRequests.value = _queuedRequests.value + (sid to (currentList + req))
        } else {
            executeAgentRequestStream(
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                targetSessionId = sid,
                isAutoTrigger = isAutoTrigger
            )
        }
    }

    /** 从当前会话队列移除指定条目（队列面板删除按钮）。 */
    fun removeQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.filterNot { it.id == id })
    }

    /** 调整当前会话队列条目的顺序（队列面板拖拽排序）。 */
    fun moveQueuedRequest(fromIndex: Int, toIndex: Int) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return
        val reordered = queue.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        _queuedRequests.value = _queuedRequests.value + (sid to reordered)
    }

    /** 编辑当前会话队列条目的文本（队列面板编辑）。 */
    fun updateQueuedRequest(id: String, newText: String) {
        val text = newText.trim()
        if (text.isEmpty()) return
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.map { req ->
            if (req.id != id) req
            else req.copy(request = text, modelRequest = syncQueuedModelRequest(req, text))
        })
    }

    /**
     * 用编辑后的文本重建 modelRequest：无附件注入时与 request 同步；有附件注入时
     * （拼成 `request + "\n\n" + 附件清单`）只替换文本前缀、保留附件部分。
     */
    private fun syncQueuedModelRequest(req: QueuedRequest, newText: String): String {
        if (req.modelRequest == req.request) return newText
        val prefix = req.request.trimEnd()
        return if (req.modelRequest.startsWith(prefix)) {
            newText.trimEnd() + req.modelRequest.removePrefix(prefix)
        } else {
            newText
        }
    }

    /**
     * 把当前会话队列中某条消息立即插入正在运行的轮次：从队列移除，转成用户插话通知交给
     * [deliverSystemEvent]——忙碌时搭车注入本批工具结果，AI 当前轮即可感知；空闲时作为
     * 新一轮用户消息发送。
     */
    fun interjectQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        val req = queue.firstOrNull { it.id == id } ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.filterNot { it.id == id })
        deliverSystemEvent(
            sid,
            PendingNotification(
                kind = AgentNotificationKind.USER_MESSAGE,
                sourceId = sid,
                title = "",
                outcome = NotificationOutcome.COMPLETED,
                message = req.request
            )
        )
    }

    private fun processNextInQueue(sessionId: String) {
        if (isSessionRewinding(sessionId)) return
        // 已有活跃 job（可能是本次收尾前由通知合并/flush 等入口启动的）时不消费，
        // 避免队列被多个收尾入口重复消费、同一会话并发跑两个 job。
        if (sessionJobs[sessionId]?.isActive == true) return
        val queue = _queuedRequests.value[sessionId] ?: return
        val next = queue.firstOrNull() ?: return
        _queuedRequests.value = _queuedRequests.value + (sessionId to queue.drop(1))
        executeAgentRequestStream(
            request = next.request,
            modelRequest = next.modelRequest,
            currentFile = next.currentFile,
            selectedCode = next.selectedCode,
            projectRoot = next.projectRoot,
            inputImages = next.inputImages,
            inputAttachments = next.inputAttachments,
            targetSessionId = sessionId,
            isAutoTrigger = next.isAutoTrigger
        )
    }

    /**
     * 执行斜杠命令：先把命令文本作为用户消息落库（进入对话上下文），再按类型派发——
     * 内置命令走本地动作，技能则把其正文作为本轮指令触发 agent 回合。
     * 执行期间标记为命令占用（防新消息并行执行），结束后接续队列中排队的下一条。
     */
    private fun runResolvedCommand(resolved: ResolvedCommand, input: String, sessionId: String) {
        viewModelScope.launch {
            _runningCommandSessions.value = _runningCommandSessions.value + sessionId
            try {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, input)
                sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
                when (resolved) {
                    is ResolvedCommand.Action -> resolved.handler.execute(this@AIAgentViewModel, resolved.args)
                    is ResolvedCommand.SkillCommand -> runSkill(resolved.skill, resolved.args)
                }
            } finally {
                _runningCommandSessions.value = _runningCommandSessions.value - sessionId
                processNextInQueue(sessionId)
            }
        }
    }

    fun executeAgentRequestStream(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        targetSessionId: String? = null,
        isAutoTrigger: Boolean = false,
        /** 子代理等场景：已预设会话标题，跳过首条消息的标题推导/生成，保留预设标题。 */
        skipTitleUpdate: Boolean = false
    ): Job = viewModelScope.launch {
        val sessionId = targetSessionId ?: ensureSession()
        if (sessionId.isBlank()) {
            FileLogger.w(TAG, "工作区未就绪，跳过请求")
            return@launch
        }
        if (isSessionRewinding(sessionId)) {
            FileLogger.i(TAG, "跳过回退/删除期间到达的请求: session=$sessionId")
            return@launch
        }
        // 命令分流：命中斜杠命令（内置命令或技能）时，不走 agent workflow，直接执行命令操作
        // （命令文本已作为用户消息落库，进入对话上下文）。不注册 sessionJobs，
        // 因此 isRunning 保持 false，/compress 等命令内部的自检可以正常工作。
        if (request.startsWith("/")) {
            slashCommandRegistry.resolve(request)?.let { command ->
                runResolvedCommand(command, request, sessionId)
                return@launch
            }
        }
        // 兼容历史会话：若会话尚未持久化绑定 providerId/model，发消息时将其固化，避免后续默认模型变动影响已有会话
        val currentSession = sessionUseCase.getSessionById(sessionId)
        if (currentSession?.parentId != null && currentSession.subagentStatus == "STARTING") {
            // A bounded post-completion rework wakes a terminal child through the normal message
            // queue. Move its persisted lifecycle back to RUNNING when that queued request starts.
            sessionUseCase.updateSubagentStatus(sessionId, "RUNNING")
        }
        if (currentSession != null && (currentSession.providerId.isNullOrBlank() || currentSession.model.isNullOrBlank())) {
            val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
            val defaultModel = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
            if (defaultProviderId != null && defaultModel != null) {
                sessionUseCase.updateProviderModel(sessionId, defaultProviderId, defaultModel)
            }
        }

        coroutineContext[Job]?.let { sessionJobs[sessionId] = it }
        FileLogger.d(TAG, "stream start: sid=$sessionId prevState=${_agentStates.value[sessionId]} isAutoTrigger=$isAutoTrigger")
        setAgentState(sessionId, AgentUIState.Streaming)
        acquireKeepalive()
        // 通知栏任务进度：注册任务标题（复用会话标题的截断规则），初始步骤为「准备中」。
        // 流式生成中由 AssistantDelta 推实时预览到通知正文；每节正文收尾（AssistantText）
        // 把小段总结写进 steps 列表；工具调用不写 steps，清掉预览让上一节小结继续展示。
        lastPushedTaskStep.remove(sessionId)
        TerminalKeepaliveService.updateTaskProgress(
            sessionId,
            title = sessionUseCase.deriveTitle(request),
            step = context.getString(R.string.notification_agent_task_preparing)
        )

        try {
            var failed = false
            // 必须在插入本次用户消息之前读取历史：workflow 会自己 add(userRequest)，避免重复。
            val history = messagePersistenceUseCase.buildHistory(sessionId, SessionUseCase.PENDING_TOOL_MARKER)
            val isFirst = history.isEmpty()

            val userMsgId = UUID.randomUUID().toString()
            if (!isAutoTrigger) {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, request, id = userMsgId, attachments = inputAttachments)
                val checkpoint = checkpointManager.createCheckpoint(sessionId, userMsgId, request)
                val gitSnapshot = checkpointManager.captureGitTreeBeforeChanges(
                    sessionId = sessionId,
                    checkpointId = checkpoint.id,
                    workspacePath = currentSession?.workspacePath ?: projectRoot
                )
                if (gitSnapshot.warning != null) {
                    FileLogger.w(TAG, "Git 工作区快照不可用: session=$sessionId checkpoint=${checkpoint.id}: ${gitSnapshot.warning}")
                }
                if (isFirst && !skipTitleUpdate) {
                    sessionUseCase.updateTitle(sessionId, sessionUseCase.deriveTitle(request))
                    // 后台异步用 LLM 生成更贴切的标题替换临时标题；失败/取不到时保留临时标题
                    viewModelScope.launch {
                        agentWorkflow.generateTitle(sessionId, request)?.let { sessionUseCase.updateTitle(sessionId, it) }
                    }
                }
            } else {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, modelRequest, id = userMsgId,
                    attachments = inputAttachments)
            }
            messagePersistenceUseCase.invalidateHistory(sessionId)
            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())

            val sessionEntity = sessionUseCase.getSessionById(sessionId)
            val sessionDomain = sessionEntity?.toDomain()
            val mode = sessionDomain?.mode ?: AgentMode.BUILD
            val isSub = sessionEntity?.parentId != null
            val checkpointSessionId = sessionEntity?.parentId ?: sessionId
            val childCheckpointId = if (isSub) {
                sessionEntity?.dispatchMessageId
                    ?.let { checkpointDao.getCheckpointBySessionAndMessage(checkpointSessionId, it)?.id }
                    .orEmpty()
            } else null
            // 旧版自定义 agent 类型无法再恢复其文件配置；未知/历史类型安全降级为只读 explore，
            // 避免旧会话在定义缺失时意外获得完整工具集。
            val agentDefinition = if (isSub) {
                sessionEntity?.subagentType?.let { BuiltInSubAgentCatalog.find(it) }
                    ?: BuiltInSubAgentCatalog.find("explore")
            } else null

            val agentContext = AgentContext(
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                language = currentFile?.let { detectLanguage(it) },
                history = history,
                inputImages = inputImages,
                sessionId = sessionId,
                inputMessageId = userMsgId,
                lastInputTokens = sessionEntity?.lastInputTokens ?: 0,
                mode = mode,
                modeBeforePlan = sessionDomain?.modeBeforePlan,
                reasoningEffort = sessionDomain?.reasoningEffort?.apiValue,
                agentDefinition = agentDefinition,
                sourceLabel = sessionEntity?.takeIf { it.parentId != null }?.let {
                    "子代理「${agentDefinition?.name ?: it.subagentType ?: "子代理"}」"
                },
                allowedWritePaths = sessionEntity?.takeIf { it.parentId != null }?.allowedWritePaths?.split('\n'),
                allowedReadPaths = sessionEntity?.takeIf { it.parentId != null }?.allowedReadPaths?.split('\n'),
                explicitSubAgentRequest = request.contains(
                    Regex("(?:明确|请|让|安排|派发|委派).{0,12}(?:子代理|子 agent|subagent|并行)|(?:使用|启动|派发|委派).{0,12}(?:子代理|子 agent|subagent)", RegexOption.IGNORE_CASE)
                ),
                superAgentEnabled = sessionEntity?.superAgentEnabled == true,
                checkpointSessionId = checkpointSessionId,
                checkpointId = childCheckpointId
            )

            val allTools = toolRegistry.getAvailableTools()
            val tools = when {
                agentDefinition != null -> {
                    val allowed = agentDefinition.filterToolNames(allTools.map { it.name }).toSet()
                    allTools.filter { it.name in allowed }
                }
                isSub -> allTools.filterNot { it.name == AgentDefinition.NESTED_TOOL }
                else -> allTools.filterNot { it.name == AgentDefinition.PARENT_MESSAGE_TOOL }
            }

            var currentReasoningStart: Long? = null
            var currentReasoningEnd: Long? = null
            fun finishReasoning() {
                val start = currentReasoningStart ?: return
                if (currentReasoningEnd == null) {
                    val end = System.currentTimeMillis()
                    currentReasoningEnd = end
                    _reasoningTimings.value = _reasoningTimings.value + (sessionId to (start to end))
                }
            }
            agentWorkflow.executeEvents(
                userRequest = modelRequest,
                context = agentContext,
                tools = tools
            ).collect { event ->
                when (event) {
                    is AgentEvent.AssistantDelta -> {
                        if (event.accumulated.hasVisibleContent()) finishReasoning()
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        // 通知栏实时预览：取 AI 正在写的【最后一段】推给通知——
                        // 一节正文常分多段，取首行会让预览卡在第一段不动；
                        // 取最后一段，每写完一小段通知就跳到那一段，用户实时看到 AI 写到哪了。
                        // 注意：extractLastParagraph 的提取推入 pushTaskLivePreview 内部，
                        // 经 200ms 节流窗口后才执行——避免每个 delta(16/秒)都对全文调 lines()
                        // 产生 ~3MB/秒的 String 分配风暴(GC 锯齿的元凶)。
                        if (event.accumulated.hasVisibleContent()) {
                            pushTaskLivePreview(sessionId, event.accumulated)
                        }
                        setStreamingText(sessionId, event.accumulated)
                    }
                    is AgentEvent.ReasoningDelta -> {
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        // 思考中作为实时预览（不进 steps 列表：思维链不是用户要看的「小结」）
                        pushTaskLivePreview(sessionId, context.getString(R.string.notification_agent_task_thinking))
                        // timing 只在 start 首次设置时写一次：delta 期间 start/end 不变，
                        // 重复写入相同值只会触发 StateFlow emission + Map 复制 + Compose 重组。
                        // end timestamp 在 finishReasoning 里补写。
                        if (currentReasoningStart == null) {
                            currentReasoningStart = System.currentTimeMillis()
                            currentReasoningEnd = null
                            _reasoningTimings.value = _reasoningTimings.value +
                                (sessionId to (currentReasoningStart!! to currentReasoningEnd))
                        }
                        setStreamingReasoning(sessionId, event.accumulated)
                    }
                    is AgentEvent.ToolCallPreparing -> {
                        finishReasoning()
                        // 工具名先于参数到达：让 UI 把「正在思考」换成具体场景（「正在编辑文件」）。
                        // 参数流完、工具真正开始执行后由 ToolCallStarted 清掉，改由工具行表达。
                        setPreparingTool(sessionId, event.toolName)
                    }
                    is AgentEvent.Retrying -> {
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setRetryState(sessionId, RetryState(event.attempt, event.maxRetries, event.error))
                        // 重试会从头重新流式输出：清掉已展示的正文/思维链气泡，
                        // 否则重连后思维链重新生成而旧正文残留（workflow 已同步清空累积器）。
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        setKeySwitchState(sessionId, null)
                    }
                    is AgentEvent.KeySwitched -> {
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setKeySwitchState(sessionId, KeySwitchState(event.newIndex, event.total))
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                    }
                    is AgentEvent.CompactionStarted -> {
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        setCompacting(sessionId, true)
                    }
                    AgentEvent.CompactionFinished -> {
                        setCompacting(sessionId, false)
                        _contextUsages.value = _contextUsages.value - sessionId
                    }
                    is AgentEvent.ContextUsage -> {
                        _contextUsages.value = _contextUsages.value + (sessionId to event)
                    }
                    is AgentEvent.CompactionFailed -> {
                        setCompacting(sessionId, false)
                        // 落库为无配对的 TOOL 消息：UI 渲染失败卡片，buildHistory 回放自动丢弃，不进模型上下文。
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            event.reason,
                            toolName = COMPACTION_FAILURE_TOOL_NAME,
                            isError = true
                        )
                    }
                    is AgentEvent.AssistantText -> {
                        finishReasoning()
                        val reasoningDuration = currentReasoningStart?.let { start ->
                            currentReasoningEnd?.minus(start)
                        }
                        currentReasoningStart = null
                        currentReasoningEnd = null

                        // 流式收尾：在落库并触发 UI messages 更新之前，先同步清空流式状态，
                        // 避免落库消息先行发射导致 UI 出现「落库消息与流式气泡同屏并存」的时差。
                        setStreamingReasoning(sessionId, null)
                        setStreamingText(sessionId, null)
                        if (event.toolCalls.isEmpty()) {
                            setPreparingTool(sessionId, null)
                        }

                        val normalized = if (event.content.hasVisibleContent()) event.content else ""
                        val reasoning = event.reasoning.takeIf { it.hasVisibleContent() }
                        val msgId = event.messageId
                        if (reasoningDuration != null && reasoningDuration > 0) {
                            reasoningDurations[msgId] = reasoningDuration
                        }
                        // 通知栏：一节正文收尾，把首行小段总结写进 steps 列表（用户展开通知看到的
                        // 全是 AI 各节说了什么，而不是调了什么工具）；同时清掉流式实时预览。
                        val summary = summarizeForNotification(normalized)
                        if (summary.isNotEmpty()) pushTaskStep(sessionId, summary)
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.ASSISTANT,
                            normalized,
                            id = msgId,
                            toolCalls = event.toolCalls,
                            reasoning = reasoning,
                            signature = event.signature.ifEmpty { null },
                            thinkingBlocksJson = event.thinkingBlocksJson.ifEmpty { null },
                            attachments = event.attachments,
                            inputTokens = event.inputTokens,
                            outputTokens = event.outputTokens,
                            cachedInputTokens = event.cachedInputTokens
                        )
                        if (event.inputTokens > 0 || event.outputTokens > 0) {
                            _llmCallEvents.tryEmit(LlmCallEvent(sessionId, event.inputTokens, event.outputTokens, event.cachedInputTokens))
                            // 同步写库：工具循环下一轮 CallLlm 前会重读 lastInputTokens 判断压缩，
                            // 异步写库可能读到压缩前的旧大值导致重复触发压缩。
                            runCatching {
                                chatSessionDao.addTokenUsage(sessionId, event.inputTokens, event.outputTokens)
                                if (event.inputTokens > 0) {
                                    chatSessionDao.updateLastInputTokens(sessionId, event.inputTokens)
                                }
                            }
                        }
                        event.persisted?.complete(Unit)
                    }
                    is AgentEvent.ToolCallStarted -> {
                        val msgId = "tool_${event.id}"
                        setStreamingText(sessionId, null)
                        setPreparingTool(sessionId, null)
                        // 通知栏：工具调用不写进 steps（用户要看到的是 AI 各节说了什么，
                        // 不是调了什么工具）。清掉流式实时预览，让上一节小结继续展示在通知正文。
                        pushTaskLivePreview(sessionId, null)
                        toolArgsByMsgId[msgId] = event.argsPreview
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            "${SessionUseCase.PENDING_TOOL_MARKER} ${context.getString(R.string.agent_tool_executing, event.toolName)}",
                            id = msgId,
                            toolCallId = event.id,
                            toolName = event.toolName,
                            toolArgs = event.argsPreview,
                            isError = false
                        )
                        setRunningTool(sessionId, msgId, RunningToolOutput(msgId, "", event.toolName, event.argsPreview))
                    }
                    is AgentEvent.ToolCallProgress -> {
                        val msgId = "tool_${event.id}"
                        publishToolProgress(sessionId, msgId, RunningToolOutput(
                            msgId,
                            event.accumulated,
                            event.toolName,
                            toolArgsByMsgId[msgId] ?: ""
                        ))
                    }
                    is AgentEvent.ToolCallFinished -> {
                        val msgId = "tool_${event.id}"
                        messagePersistenceUseCase.persist(
                            sessionId,
                            MessageRole.TOOL,
                            event.result,
                            id = msgId,
                            toolCallId = event.id,
                            toolName = event.toolName,
                            toolArgs = event.argsPreview ?: toolArgsByMsgId[msgId],
                            isError = event.isError,
                            attachments = event.attachments
                        )
                        toolArgsByMsgId.remove(msgId)
                        removeRunningTool(sessionId, msgId)
                        event.persisted?.complete(Unit)
                    }
                    is AgentEvent.Failed -> {
                        failed = true
                        setCompacting(sessionId, false)
                        setAgentState(sessionId, AgentUIState.Error(describeFailure(event)))
                        // 子代理会话失败时通知父会话
                        if (isSub) {
                            sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                                subAgentEventBus.emit(
                                    SubAgentEvent(
                                        subSessionId = sessionId,
                                        parentSessionId = parentId,
                                        type = SubAgentEventType.FAILED,
                                        detail = event.error
                                    )
                                )
                            }
                        }
                    }
                    AgentEvent.Completed -> {
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setCompacting(sessionId, false)
                        // 子代理会话完成时通知父会话（异步回调）
                        if (isSub) {
                            sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                                subAgentEventBus.emit(
                                    SubAgentEvent(
                                        subSessionId = sessionId,
                                        parentSessionId = parentId,
                                        type = SubAgentEventType.COMPLETED
                                    )
                                )
                            }
                        }
                        // 仅当 App 不在前台时发 agent 完成通知（避免打扰正在看对话的用户）。
                        val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                            .isAtLeast(Lifecycle.State.STARTED)
                        if (!inForeground && agentSoundSettings.isEnabled()) {
                            showAgentCompletedNotification(modelRequest)
                        }
                    }
                    is AgentEvent.ModeChanged -> {
                        // 模式切换通知会随工具结果写入聊天记录；此处由上层状态观察器更新模式显示。
                    }
                }
            }

            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
            // 仅当本 job 仍持有忙状态时才置完成态：并发场景下队列/通知可能已启动新的 job
            // 并把状态改为 Streaming，不能被先结束的 job 误覆盖成 Result（按钮会提前变回发送）。
            val finishedState = _agentStates.value[sessionId]
            if (!failed && (finishedState is AgentUIState.Loading || finishedState is AgentUIState.Streaming)) {
                setAgentState(sessionId, AgentUIState.Result(WorkflowStatus.SUCCESS))
                _completedSessions.value = _completedSessions.value + sessionId
            }
            setStreamingText(sessionId, null)

        } catch (e: CancellationException) {
            val cancelledState = _agentStates.value[sessionId]
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream cancelled: sid=$sessionId isOwnJob=$isOwnJob state=$cancelledState")
            if (isOwnJob &&
                (cancelledState is AgentUIState.Loading || cancelledState is AgentUIState.Streaming)
            ) {
                setAgentState(sessionId, AgentUIState.Idle)
                _completedSessions.value = _completedSessions.value - sessionId
            }
            throw e
        } catch (e: Exception) {
             FileLogger.e(TAG, "executeAgentRequestStream 失败: request=$request", e)
             setAgentState(sessionId, AgentUIState.Error(e.toUserMessage()))
             _completedSessions.value = _completedSessions.value - sessionId
        } finally {
            clearPendingToolProgress(sessionId)
            // 通知栏进度先行清除：后面 releaseKeepalive 停服务时，通知已回退为普通保活文案，
            // 不会残留「正在执行」的过期步骤。
            clearTaskProgress(sessionId)
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream finally: sid=$sessionId isOwnJob=$isOwnJob state=${_agentStates.value[sessionId]}")
            if (isOwnJob) {
                sessionJobs.remove(sessionId)
            }
            _runningTools.value = _runningTools.value - sessionId
            setStreamingText(sessionId, null)
            setStreamingReasoning(sessionId, null)
            setPreparingTool(sessionId, null)
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)
            setKeySwitchState(sessionId, null)

            // 本轮未能搭车送达的后台通知：本轮结束且 job 已移除后，合并成一条发送
            flushPendingNotifications(sessionId)

            // 正常完成时先回到 Idle，再处理队列；队列若有下一轮会重新设 Streaming
            val currentState = _agentStates.value[sessionId]
            if (currentState !is AgentUIState.Error && currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                setAgentState(sessionId, AgentUIState.Idle)
            }
            if (currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                processNextInQueue(sessionId)
            }
            // 放在队列处理之后：flushPendingNotifications / processNextInQueue 会同步注册接替的 job，
            // 此时 hasRunningSessions 才能反映真实状态，不会刚释放又立即重新获取。
            if (!hasRunningSessionsInCurrentWorkspace()) {
                releaseKeepalive()
            }
        }
    }.also { job ->
        // 同步注册 job：launch 内的 sessionJobs 赋值是异步的，finally 中 flushPendingNotifications
        // 与 processNextInQueue 会在赋值前都看到 isActive=false 而双消费启动两个 job，
        // 先结束的 job 把状态置 Idle/Result 覆盖仍在跑的 job 的 Streaming。
        if (targetSessionId != null && slashCommandRegistry.resolve(request) == null) {
            sessionJobs[targetSessionId] = job
        }
    }

    fun resolveToolPermission(id: String, choice: PermissionChoice) {
        toolPermissionManager.resolve(id, choice)
    }

    fun resolveUserQuestion(id: String, answer: UserQuestionAnswer) {
        askUserQuestionManager.resolve(id, answer)
    }

    /** 停止当前工作区所有正在运行的 AI 会话并关闭所有终端标签（切换工作区前调用）。 */
    fun stopAllAndCloseTerminal() {
        stopAllAgents()
        terminalSessionManager.tabs.value.map { it.id }.forEach { terminalSessionManager.closeTab(it) }
    }

    /** 停止当前工作区所有正在运行的 AI 会话（切换工作区前调用）。 */
    fun stopAllAgents() {
        val jobs = sessionJobs.values.filter { it.isActive }
        jobs.forEach { it.cancel() }
        sessionJobs.clear()
        agentNotificationCenter.clearAll()
        _queuedRequests.value = emptyMap()
        _runningCommandSessions.value = emptySet()
        _agentStates.value = _agentStates.value.mapValues { AgentUIState.Idle }
        // 直接清底层流并同步清发射节流簿记（pending/emitAt）
        _streamingTexts.value = emptyMap()
        _streamingReasonings.value = emptyMap()
        pendingStreamingTexts.clear()
        pendingStreamingReasonings.clear()
        lastStreamingTextEmitAt.clear()
        lastStreamingReasoningEmitAt.clear()
        _reasoningTimings.value = emptyMap()
        _runningTools.value = emptyMap()
        _retryStates.value = emptyMap()
        releaseKeepalive()
    }

    /**
     * 主动打断当前会话正在运行的 agent：取消协程（会一并取消挂起的网络请求与容器命令进程），
     * 并把「执行中」的工具占位行收尾为「已停止」，避免悬挂的 spinner 与孤儿记录。
     */
    fun stopAgent() {
        val sessionId = _currentSessionId.value ?: return
        stopAgentSession(sessionId)
    }

    /**
     * 停止指定会话的 AI 任务（子代理停止/用户手动停止共用）。
     * 取消 job 并把未完成的流式内容落库为「已停止」；队列下一条照常执行。
     */
    fun stopAgentSession(sessionId: String) {
        val job = sessionJobs[sessionId]
        val isActiveSubAgent = sessionId in subAgentEventBus.activeSubSessionIds.value
        if (isActiveSubAgent && !stoppingSubAgentIds.add(sessionId)) return
        // Snapshot before cancellation: the child may finish synchronously on Main.immediate.
        // 走 latestStreamingTextOf：含发射节流窗口暂存的最新值，保证落库的部分内容不缺最后几字。
        val runningTools = _runningTools.value[sessionId]?.values?.toList() ?: emptyList()
        val streamingText = latestStreamingTextOf(sessionId)
        val streamingReasoning = latestStreamingReasoningOf(sessionId)
        val pendingPermission = toolPermissionManager.pendingForSession(sessionId)
        val stoppedText = context.getString(R.string.agent_stopped_by_user)
        val pendingNotifs = agentNotificationCenter.pendingCount(sessionId)
        // Stopping a parent is a cancellation boundary: cancel every child immediately and persist
        // terminal states even when the parent workflow itself has already returned.
        viewModelScope.launch {
            val session = sessionUseCase.getSessionById(sessionId) ?: return@launch
            if (session.parentId == null) {
                chatSessionDao.getSubSessionsByParentOnce(sessionId)
                    .filter { it.id in subAgentEventBus.activeSubSessionIds.value }
                    .forEach { child ->
                        val accepted = subAgentEventBus.emit(
                            SubAgentEvent(child.id, sessionId, SubAgentEventType.STOP_REQUESTED, "父任务已停止")
                        )
                        if (!accepted) stopAgentSession(child.id)
                    }
            } else if (sessionId in subAgentEventBus.activeSubSessionIds.value) {
                // Keep the reservation and write scope until the child coroutine has actually stopped.
                // Releasing before join allows a second worker to overlap while the old tool is still unwinding.
                _queuedRequests.value = _queuedRequests.value - sessionId
                agentNotificationCenter.clear(sessionId)
                job?.cancelAndJoin()
                sessionUseCase.updateSubagentStatus(sessionId, "CANCELLED")
                subAgentEventBus.emit(
                    SubAgentEvent(
                        subSessionId = sessionId,
                        parentSessionId = session.parentId,
                        type = SubAgentEventType.STOPPED,
                        detail = "子代理已停止；当前任务未完成。"
                    )
                )
            }
        }
        if (job == null || !job.isActive) return
        FileLogger.d(TAG, "stopAgent: sid=$sessionId runningTools=${runningTools.size} pendingPerm=${pendingPermission?.id} pendingNotifs=$pendingNotifs state=${_agentStates.value[sessionId]}")
        // cancel() 在 Dispatchers.Main.immediate 上可能立即恢复挂起协程
        // （如 awaitApproval 的 CompletableDeferred.await），旧 job 的 finally →
        // flushPendingNotifications 在 cancel() 调用栈内同步执行并可能启动新 job。
        // 不预先清除待送通知——它们应由 finally 正常 flush 给新 job 处理。
        job.cancel()
        // cancel 可能已同步执行完 finally（flush 启动了新 job 并注册到 sessionJobs），
        // 此时不能再覆盖新 job 的状态；仅当无新 job 接管时才做清理。
        if (sessionJobs[sessionId]?.isActive != true) {
            agentNotificationCenter.clear(sessionId)
            setAgentState(sessionId, AgentUIState.Idle)
        }
        _runningTools.value = _runningTools.value - sessionId
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        setCompacting(sessionId, false)
        setRetryState(sessionId, null)
        viewModelScope.launch {
            if (runningTools.isNotEmpty()) {
                // 并行执行被中止：所有未完成的工具都落库为「已停止」
                runningTools.forEach { running ->
                    val partial = running.text.trimEnd()
                    val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
                    messagePersistenceUseCase.persist(
                        sessionId = sessionId,
                        role = MessageRole.TOOL,
                        content = content,
                        id = running.messageId,
                        toolCallId = running.messageId.removePrefix("tool_"),
                        toolName = running.toolName.ifBlank { null },
                        toolArgs = running.toolArgs.ifBlank { toolArgsByMsgId[running.messageId] },
                        isError = true
                    )
                    toolArgsByMsgId.remove(running.messageId)
                }
            } else if (!streamingText.isNullOrEmpty() || !streamingReasoning.isNullOrEmpty()) {
                val partial = (streamingText ?: "").trimEnd()
                val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
                val reasoning = streamingReasoning?.takeIf { it.hasVisibleContent() }
                messagePersistenceUseCase.persist(
                    sessionId = sessionId,
                    role = MessageRole.ASSISTANT,
                    content = content,
                    reasoning = reasoning
                )
            }
            // 授权弹窗挂起中的工具调用：awaitApproval 挂起期间 _runningTools 为空
            // （ToolCallStarted 在授权通过后才发出），但 AssistantText 已落库了带
            // tool_call 声明的 assistant 消息。不补结果会导致该 tool_call 成为
            // 孤立记录，被 buildHistory 的 validIds 交集过滤掉，AI 不知道自己曾调用过。
            if (pendingPermission != null) {
                val msgId = "tool_${pendingPermission.id}"
                messagePersistenceUseCase.persist(
                    sessionId = sessionId,
                    role = MessageRole.TOOL,
                    content = stoppedText,
                    id = msgId,
                    toolCallId = pendingPermission.id,
                    toolName = pendingPermission.toolName,
                    isError = true
                )
            }
            setStreamingText(sessionId, null)
            setStreamingReasoning(sessionId, null)
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)
            // 点「停止」= 跳过当前轮，立即执行队列下一条
            processNextInQueue(sessionId)
        }
    }

    // region 会话管理

    /** 新建会话；若当前会话还是空的则直接复用，避免堆积空会话。 */
    fun newSession() = viewModelScope.launch {
        if (_currentWorkspace.value.isBlank()) return@launch
        val curId = _currentSessionId.value
        if (curId != null && sessionUseCase.isSessionEmpty(curId)) {
            setAgentState(curId, AgentUIState.Idle)
            return@launch
        }
        // 新会话时异步重连未连接的 MCP server，让 manageMcp 新增的配置真正生效；
        // 不阻塞会话创建——MCP 环境未就绪/超时时不能卡住「新建会话」。
        mcpManager.reconnectUnconnectedAsync()
        val sid = createAndUpsertSession(_currentWorkspace.value)
        _currentSessionId.value = sid
    }

    fun setCurrentSessionId(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
    }

    fun setSessionMode(mode: AgentMode) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateMode(sid, mode.name)
            // 工作期间切换：把新模式作为系统事件投递给运行中的 agent，本轮即时生效（见 StatefulAgentWorkflow）。
            // 空闲时无需投递——下一轮开轮会按新模式注入一次提醒。
            if (sessionJobs[sid]?.isActive == true) {
                agentNotificationCenter.enqueue(
                    sid,
                    PendingNotification(
                        kind = AgentNotificationKind.MODE_CHANGE,
                        sourceId = "user",
                        title = mode.name,
                        outcome = NotificationOutcome.COMPLETED,
                        newMode = mode
                    )
                )
            }
        }
    }

    /** Session-level Super Agent toggle. The current request keeps its captured AgentContext; the next request sees this value. */
    fun setSuperAgentEnabled(enabled: Boolean) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            val session = sessionUseCase.getSessionById(sid) ?: return@launch
            if (session.parentId != null) return@launch
            sessionUseCase.updateSuperAgentEnabled(sid, enabled)
        }
    }

    fun showSuperAgentExplanation() {
        Toast.makeText(context, R.string.chat_super_agent_explanation, Toast.LENGTH_LONG).show()
    }

    fun setSessionReasoningEffort(effort: ReasoningEffort) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateReasoningEffort(sid, effort.name)
            // 同步记忆到模型级默认档位，供后续新建会话沿用
            val s = sessionUseCase.getSessionById(sid)?.toDomain()
            val pid = s?.providerId
            val model = s?.model
            if (!pid.isNullOrBlank() && !model.isNullOrBlank()) {
                modelReasoningEffortRepository.set(pid, model, effort.name)
            }
        }
    }

    fun setSessionProviderModel(providerId: String, model: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateProviderModel(sid, providerId, model)
            _contextUsages.value = _contextUsages.value - sid
            // 空会话中的选择视为「新会话默认模型」，供下次新建会话沿用
            if (sessionUseCase.isSessionEmpty(sid)) {
                defaultModelSettingsRepository.setDefaultModel(providerId, model)
            }
        }
    }

    /** 暴露给 UI：输入框 `/` 菜单展示的命令列表（内置命令 + 已启用技能）。 */
    val slashCommands: StateFlow<List<SlashCommand>> get() = slashCommandRegistry.commands

    /** 重新扫描技能并刷新命令菜单；进入 `/` 菜单时调用，反映技能的增删改。 */
    fun refreshSlashCommands() {
        viewModelScope.launch { slashCommandRegistry.refresh() }
    }

    /** /init —— 触发 agent 回合，分析代码库并生成/改进项目规则文件。 */
    override fun initProject(prompt: String) {
        val sid = _currentSessionId.value ?: return
        executeAgentRequestStream(
            request = prompt,
            projectRoot = _currentWorkspace.value,
            targetSessionId = sid,
            isAutoTrigger = true
        )
    }

    /** 技能触发 —— 把技能正文作为本轮指令执行。 */
    override fun runSkill(skill: Skill, args: String) {
        val sid = _currentSessionId.value ?: return
        executeAgentRequestStream(
            request = SlashCommandRegistry.buildSkillPrompt(skill, args),
            projectRoot = _currentWorkspace.value,
            targetSessionId = sid,
            isAutoTrigger = true
        )
    }

    /** /usage —— 以 Markdown 表格输出今日与累计的调用次数、token 用量与预估费用。 */
    override fun showUsage() {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val todayStart = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val today = loadUsageSummary(todayStart)
            val allTime = loadUsageSummary(0L)
            val table = buildString {
                appendLine("| 项目 | 今日 | 累计 |")
                appendLine("|---|---|---|")
                appendLine("| 调用次数 | ${today.calls} | ${allTime.calls} |")
                appendLine("| 输入 tokens | ${formatTokenCount(today.inputTokens)} | ${formatTokenCount(allTime.inputTokens)} |")
                appendLine("| 输出 tokens | ${formatTokenCount(today.outputTokens)} | ${formatTokenCount(allTime.outputTokens)} |")
                appendLine("| 缓存命中 tokens | ${formatTokenCount(today.cachedInputTokens)} | ${formatTokenCount(allTime.cachedInputTokens)} |")
                appendLine("| 预估费用 | ${formatCostUsd(today.costUsd)} | ${formatCostUsd(allTime.costUsd)} |")
            }
            sessionUseCase.touch(sid, messagePersistenceUseCase.nextTimestamp())
            messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, table.trimEnd(), isContextExcluded = true)
        }
    }

    private data class UsageSummary(
        val calls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedInputTokens: Long,
        val costUsd: Double
    )

    /** 汇总自 [start] 起的调用次数、token 与预估费用；费用按「渠道 + 模型」分组逐组估算，才取得到自定义单价。 */
    private suspend fun loadUsageSummary(start: Long): UsageSummary = withContext(Dispatchers.IO) {
        val summary = llmCallRecordDao.getSummary(start).first()
        val providerTypes = aiProviderRepository.getAllProviders().first().associate { it.id to it.type }
        val cost = llmCallRecordDao.getModelProviderCostStats(start).first().sumOf { stat ->
            val model = stat.model ?: return@sumOf 0.0
            modelCostCalculator.costUsd(
                providerId = stat.providerId.orEmpty(),
                providerType = stat.providerId?.let { providerTypes[it] } ?: ProviderType.OPENAI,
                model = model,
                inputTokens = stat.inputTokens,
                cachedInputTokens = stat.cachedInputTokens,
                outputTokens = stat.outputTokens,
                cacheCreationTokens = stat.cacheCreationTokens
            ) ?: 0.0
        }
        UsageSummary(summary.calls, summary.inputTokens, summary.outputTokens, summary.cachedInputTokens, cost)
    }

    /** /compress —— 手动触发当前会话的上下文压缩。 */
    override fun compactCurrentSession() {
        val sid = _currentSessionId.value ?: return
        if (isRunning) return
        sessionJobs[sid]?.let { if (it.isActive) return }
        val job = viewModelScope.launch {
            setCompacting(sid, true)
            var failed = false
            try {
                val changed = agentWorkflow.compactSession(sid) { event ->
                    when (event) {
                        is AgentEvent.CompactionStarted -> setCompacting(sid, true)
                        AgentEvent.CompactionFinished -> {
                            setCompacting(sid, false)
                            _contextUsages.value = _contextUsages.value - sid
                        }
                        is AgentEvent.CompactionFailed -> {
                            failed = true
                            setCompacting(sid, false)
                            // 与自动压缩一致：落库无配对的 TOOL 消息渲染失败卡片，buildHistory 回放丢弃。
                            messagePersistenceUseCase.persist(
                                sessionId = sid,
                                role = MessageRole.TOOL,
                                content = event.reason,
                                toolName = COMPACTION_FAILURE_TOOL_NAME,
                                isError = true
                            )
                        }
                        else -> {}
                    }
                }
                // 压缩成功时 marker 分隔线 + 摘要卡片已提供反馈，不再落库重复的提示气泡；
                // 仅「无需压缩」（head 为空/无可压缩内容）时给出一条提示。
                if (!failed && !changed) {
                    messagePersistenceUseCase.persist(
                        sessionId = sid,
                        role = MessageRole.ASSISTANT,
                        content = context.getString(R.string.agent_context_no_compaction)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "手动压缩失败: session=$sid", e)
                messagePersistenceUseCase.persist(
                    sessionId = sid,
                    role = MessageRole.ASSISTANT,
                    content = context.getString(R.string.agent_compaction_failed, e.message)
                )
            } finally {
                setCompacting(sid, false)
                // 压缩是异步流程，结束后接续队列中排队的下一条
                processNextInQueue(sid)
            }
        }
        sessionJobs[sid] = job
    }

    fun selectSession(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
    }

    fun deleteSessions(ids: Set<String>) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        val cascadeIds = ids.flatMap { sessionUseCase.getSessionCascadeIds(it) }.distinct()
        if (!acquireRewindLocks(cascadeIds)) return@launch
        if (!cancelSessionsAndAwait(cascadeIds)) return@launch
        cascadeIds.forEach { sid ->
            checkpointManager.clearSessionCheckpoints(sid)
            todoItemDao.deleteBySession(sid)
        }
        val allDeletedIds = mutableSetOf<String>()
        ids.forEach { id ->
            val deletedSession = sessionUseCase.getSessionById(id)
            if (deletedSession?.parentId != null) {
                subAgentEventBus.release(id)
            }
            val deleted = sessionUseCase.deleteSession(id)
            allDeletedIds.addAll(deleted)
        }

        allDeletedIds.forEach { sid ->
            clearSessionTransientState(sid)
        }

        if (_currentSessionId.value in allDeletedIds) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                if (remaining != null) {
                    _currentSessionId.value = remaining.id
                } else {
                    _currentSessionId.value = createAndUpsertSession(ws)
                }
            }
        }
    }

    fun deleteSession(id: String) = viewModelScope.launch {
        // 删掉运行中的子代理时先取它的父会话与标题（删库后就取不到了），稍后告知父代理。
        // 删的是父会话时无需通知（父会话本身也没了），但级联删掉的子会话仍要交回并发槽位。
        val deletedSession = sessionUseCase.getSessionById(id)
        val wasActiveSubAgent = deletedSession?.takeIf {
            it.parentId != null && id in subAgentEventBus.activeSubSessionIds.value
        }
        val cascadeIds = sessionUseCase.getSessionCascadeIds(id)
        if (!acquireRewindLocks(cascadeIds)) return@launch
        if (!cancelSessionsAndAwait(cascadeIds)) return@launch

        cascadeIds.forEach { sid ->
            checkpointManager.clearSessionCheckpoints(sid)
            todoItemDao.deleteBySession(sid)
        }
        val deletedIds = sessionUseCase.deleteSession(id)

        deletedIds.forEach { sid ->
            clearSessionTransientState(sid)
        }

        if (_currentSessionId.value in deletedIds) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                if (remaining != null) {
                    _currentSessionId.value = remaining.id
                } else {
                    _currentSessionId.value = createAndUpsertSession(ws)
                }
            }
        }

        wasActiveSubAgent?.let { sub ->
            notifyParentSubAgentFinished(
                parentSessionId = sub.parentId!!,
                subSessionId = sub.id,
                title = sub.title,
                outcome = NotificationOutcome.STOPPED,
                detail = "用户删除了这个子代理会话，任务未完成，其对话记录已不可读取。"
            )
        }
    }

    // Checkpoint Rewind 选中的目标消息 id
    private val _targetRewindMessageId = MutableStateFlow<String?>(null)
    val targetRewindMessageId: StateFlow<String?> = _targetRewindMessageId.asStateFlow()

    private val _rewindConfirmation = MutableStateFlow<RewindConfirmation?>(null)
    val rewindConfirmation: StateFlow<RewindConfirmation?> = _rewindConfirmation.asStateFlow()

    private val _rewindNotice = MutableStateFlow<String?>(null)
    val rewindNotice: StateFlow<String?> = _rewindNotice.asStateFlow()

    fun requestRewindConfirmation(messageId: String, option: RewindOption) = viewModelScope.launch {
        val message = agentMessageDao.getMessageById(messageId) ?: return@launch
        val sessionId = _currentSessionId.value ?: return@launch
        if (message.sessionId != sessionId) return@launch
        val checkpoint = checkpointDao.getCheckpointBySessionAndMessage(sessionId, messageId)
        val allChildren = sessionUseCase.getSessionCascadeIds(sessionId).filter { it != sessionId }
        val removesConversation = option != RewindOption.RESTORE_CODE
        val removedChildren = if (removesConversation) descendantsDerivedAtOrAfter(sessionId, message.timestamp) else emptyList()
        val affectedMessages = if (removesConversation) {
            agentMessageDao.countMessagesFromTimestamp(sessionId, message.timestamp)
        } else 0
        val snapshots = checkpoint?.let { checkpointDao.countFileSnapshotsFrom(sessionId, it.createdAt) } ?: 0
        val unverifiableSnapshots = checkpoint?.let {
            checkpointDao.countUnverifiableFileSnapshotsFrom(sessionId, it.createdAt)
        } ?: 0
        val gitSnapshot = checkpoint?.let { checkpointManager.hasGitSnapshot(sessionId, it.id) } ?: false
        _rewindConfirmation.value = RewindConfirmation(
            messageId = messageId,
            option = option,
            removedMessageCount = affectedMessages,
            removedSubagentCount = removedChildren.size,
            stoppedSubagentCount = allChildren.count { it in subAgentEventBus.activeSubSessionIds.value },
            fileSnapshotCount = snapshots,
            unverifiableFileSnapshotCount = unverifiableSnapshots,
            hasGitSnapshot = gitSnapshot
        )
    }

    fun dismissRewindConfirmation() {
        _rewindConfirmation.value = null
    }

    fun dismissRewindNotice() {
        _rewindNotice.value = null
    }

    fun openRewindMenu(messageId: String) {
        _targetRewindMessageId.value = messageId
    }

    fun dismissRewindMenu() {
        _targetRewindMessageId.value = null
    }

    fun executeRewindOption(
        messageId: String,
        option: RewindOption,
        onFillPrompt: (String, List<AgentAttachment>) -> Unit
    ) = viewModelScope.launch {
        val sessionId = _currentSessionId.value ?: return@launch
        dismissRewindMenu()

        val targetMsgEntity = agentMessageDao.getMessageById(messageId) ?: return@launch
        if (targetMsgEntity.sessionId != sessionId) {
            FileLogger.w(TAG, "拒绝跨会话回退: message=$messageId session=$sessionId")
            return@launch
        }
        val checkpoint = checkpointDao.getCheckpointBySessionAndMessage(sessionId, messageId)
        val workspacePath = sessionUseCase.getSessionById(sessionId)?.workspacePath

        // 1. 回退前先停掉整个派生分支，并使所有待审批恢复为拒绝。
        // 子代理不能嵌套派生，但对旧数据仍按 parentId 递归收集，避免遗留孤儿任务。
        val allChildren = sessionUseCase.getSessionCascadeIds(sessionId).filter { it != sessionId }
        val descendantsToDelete = descendantsDerivedAtOrAfter(sessionId, targetMsgEntity.timestamp)
        if (!acquireRewindLocks(listOf(sessionId) + allChildren)) return@launch
        if (!cancelSessionsAndAwait(listOf(sessionId) + allChildren)) return@launch

        // 2. 重置检查点游标；已取消的旧工作流不会在还原之后继续写入。
        checkpointManager.setActiveCheckpointId(sessionId, null)

        val attachments = targetMsgEntity.toUIMessage().attachments

        when (option) {
            RewindOption.RESTORE_CODE_AND_CONVERSATION -> {
                var restoreResult: com.aicode.feature.agent.domain.checkpoint.CheckpointRestoreResult? = null
                if (checkpoint != null) {
                    restoreResult = checkpointManager.restoreCodeToCheckpointDetailed(
                        sessionId,
                        checkpoint.id,
                        workspacePath
                    )
                } else {
                    showRewindNotice("找不到该消息对应的检查点；代码未还原。")
                }
                finishRewindConversation(sessionId, targetMsgEntity.timestamp, descendantsToDelete)
                restoreResult?.let(::showRestoreResult)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CONVERSATION -> {
                finishRewindConversation(sessionId, targetMsgEntity.timestamp, descendantsToDelete)
                showRewindNotice("仅回退对话记录；工作区文件未还原。")
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CODE -> {
                if (checkpoint != null) {
                    val result = checkpointManager.restoreCodeToCheckpointDetailed(
                        sessionId,
                        checkpoint.id,
                        workspacePath
                    )
                    showRestoreResult(result)
                } else {
                    showRewindNotice("找不到该消息对应的检查点；代码未还原。")
                }
            }
        }
    }

    /** 重命名会话标题。仅更新 title，不改 updatedAt，列表顺序保持不变。 */
    fun renameSession(id: String, newTitle: String) = viewModelScope.launch {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return@launch
        sessionUseCase.updateTitle(id, trimmed)
    }

    /** 置顶/取消置顶会话。置顶后排在列表最前（置顶分组），不改 updatedAt。 */
    fun togglePinSession(id: String) = viewModelScope.launch {
        val pinned = sessions.value.find { it.id == id }?.isPinned ?: return@launch
        sessionUseCase.updatePinned(id, !pinned)
    }

    /** 导出单个会话为无密码备份格式（tar.gz），流式写入 [output]（调用方打开，本方法负责关闭）。成功回调 true，失败回调 false。 */
    fun exportSession(sessionId: String, output: OutputStream, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        try {
            backupManager.exportSession(sessionId, output)
            onResult(true)
        } catch (e: Exception) {
            FileLogger.e("AIAgentViewModel", "exportSession failed", e)
            onResult(false)
        } finally {
            runCatching { output.close() }
        }
    }

    private suspend fun ensureSession(): String {
        _currentSessionId.value?.let { return it }
        val ws = _currentWorkspace.value
        if (ws.isBlank()) return ""
        val id = sessionUseCase.getFirstSessionOfWorkspace(ws)?.id ?: createAndUpsertSession(ws)
        _currentSessionId.value = id
        return id
    }

    /** 新建会话并落库，返回 id。 */
    private suspend fun createAndUpsertSession(workspacePath: String): String {
        val s = createSession(workspacePath)
        sessionUseCase.upsertSession(s)
        return s.id
    }

    /**
     * 创建新会话并按「新会话默认模型」绑定 provider/model；未设置默认时回退全局 active provider。
     * 所有新建会话的入口（冷启动、新建、删除兜底、ensureSession）都走这里。
     */
    private suspend fun createSession(workspacePath: String): ChatSessionEntity {
        val providerId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
        val model = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
        val effort = if (providerId != null && model != null) {
            modelReasoningEffortRepository.get(providerId, model) ?: ReasoningEffort.MEDIUM.name
        } else {
            ReasoningEffort.MEDIUM.name
        }
        return sessionUseCase.newSessionEntity(
            workspacePath = workspacePath,
            providerId = providerId,
            model = model,
            reasoningEffort = effort
        )
    }

    // endregion

    fun updateMessageContent(messageId: String, newContent: String) = viewModelScope.launch {
        try {
            messagePersistenceUseCase.updateContent(messageId, newContent)
        } catch (e: Exception) {
            FileLogger.e(TAG, "更新消息失败", e)
        }
    }

    /** Editing a user turn rewinds dependent output and fills the composer; it never leaves stale descendants. */
    fun editUserMessageAndResend(
        messageId: String,
        editedText: String,
        onFillPrompt: (String, List<AgentAttachment>) -> Unit
    ) = viewModelScope.launch {
        val message = agentMessageDao.getMessageById(messageId) ?: return@launch
        if (message.role != MessageRole.USER.name || editedText.isBlank()) return@launch
        val session = sessionUseCase.getSessionById(message.sessionId) ?: return@launch
        val checkpoint = checkpointDao.getCheckpointBySessionAndMessage(message.sessionId, messageId)
        val children = descendantsDerivedAtOrAfter(message.sessionId, message.timestamp)
        val cascade = sessionUseCase.getSessionCascadeIds(message.sessionId)
        if (!acquireRewindLocks(cascade)) return@launch
        if (!cancelSessionsAndAwait(cascade)) return@launch
        if (checkpoint != null) {
            showRestoreResult(
                checkpointManager.restoreCodeToCheckpointDetailed(message.sessionId, checkpoint.id, session.workspacePath)
            )
        } else {
            showRewindNotice("找不到对应的文件检查点；旧轮对话将移除，但工作区文件不会自动还原。")
        }
        finishRewindConversation(message.sessionId, message.timestamp, children)
        withContext(Dispatchers.Main) { onFillPrompt(editedText, message.toUIMessage().attachments) }
    }

    /** Re-run an assistant turn from its source user message after reverting files and derived subagents. */
    fun regenerateAssistantMessage(messageId: String) = viewModelScope.launch {
        val assistant = agentMessageDao.getMessageById(messageId) ?: return@launch
        if (assistant.role != MessageRole.ASSISTANT.name) return@launch
        val messages = agentMessageDao.getMessagesBySessionOnce(assistant.sessionId)
        val source = messages.lastOrNull {
            it.role == MessageRole.USER.name && it.timestamp < assistant.timestamp
        } ?: run {
            showRewindNotice("找不到这条回复对应的用户消息，无法重新生成。")
            return@launch
        }
        val session = sessionUseCase.getSessionById(assistant.sessionId) ?: return@launch
        val checkpoint = checkpointDao.getCheckpointBySessionAndMessage(assistant.sessionId, source.id)
        val attachments = source.toUIMessage().attachments
        val children = descendantsDerivedAtOrAfter(assistant.sessionId, source.timestamp)
        val cascade = sessionUseCase.getSessionCascadeIds(assistant.sessionId)
        if (!acquireRewindLocks(cascade)) return@launch
        if (!cancelSessionsAndAwait(cascade)) return@launch
        if (checkpoint != null) {
            showRestoreResult(
                checkpointManager.restoreCodeToCheckpointDetailed(assistant.sessionId, checkpoint.id, session.workspacePath)
            )
        } else {
            showRewindNotice("找不到原始文件检查点；对话会重新生成，但之前的工作区文件改动不会自动还原。")
        }
        finishRewindConversation(assistant.sessionId, source.timestamp, children)
        val images = withContext(Dispatchers.IO) { attachments.toAgentImagesForRegeneration() }
        enqueueAgentRequest(
            request = source.content,
            modelRequest = source.content,
            projectRoot = session.workspacePath,
            inputImages = images,
            inputAttachments = attachments,
            targetSessionId = assistant.sessionId
        )
    }

    private suspend fun List<AgentAttachment>.toAgentImagesForRegeneration(): List<AgentImage> {
        val result = mutableListOf<AgentImage>()
        for (attachment in this) {
            if (!attachment.isImage || attachment.sizeBytes <= 0 || attachment.sizeBytes > MAX_REGENERATED_IMAGE_BYTES) continue
            runCatching {
                val local = attachment.localPath.takeIf { it.isNotBlank() && java.io.File(it).isFile }
                    ?.let { java.io.File(it) }
                    ?: fileAccess.copyToLocal(attachment.containerPath)
                if (!local.isFile || local.length() <= 0 || local.length() > MAX_REGENERATED_IMAGE_BYTES) return@runCatching
                result += AgentImage(
                    mimeType = attachment.mimeType.ifBlank { "image/jpeg" },
                    base64Data = java.util.Base64.getEncoder().encodeToString(local.readBytes()),
                    path = attachment.containerPath
                )
            }.onFailure { error ->
                FileLogger.w(TAG, "重新生成时读取图片附件失败: ${attachment.fileName}", error)
            }
        }
        return result
    }

    fun deleteMessage(messageId: String) = viewModelScope.launch {
        try {
            val msg = agentMessageDao.getMessageById(messageId)
            if (msg == null) return@launch
            val session = sessionUseCase.getSessionById(msg.sessionId) ?: return@launch
            // 删除任一轮内消息时按整轮回退，防止助手/tool 结果及子代理输出失去来源。
            val turnStart = if (msg.role == MessageRole.USER.name) {
                msg
            } else {
                agentMessageDao.getMessagesBySessionOnce(msg.sessionId)
                    .lastOrNull { it.role == MessageRole.USER.name && it.timestamp <= msg.timestamp }
            }
            if (turnStart != null) {
                val checkpoint = checkpointDao.getCheckpointBySessionAndMessage(msg.sessionId, turnStart.id)
                val children = descendantsDerivedAtOrAfter(msg.sessionId, turnStart.timestamp)
                val cascade = sessionUseCase.getSessionCascadeIds(msg.sessionId)
                if (!acquireRewindLocks(cascade)) return@launch
                if (!cancelSessionsAndAwait(cascade)) return@launch
                if (checkpoint != null) {
                    val restore = checkpointManager.restoreCodeToCheckpointDetailed(
                        msg.sessionId,
                        checkpoint.id,
                        session.workspacePath
                    )
                    showRestoreResult(restore)
                } else {
                    showRewindNotice("找不到对应的文件检查点；删除了对话，但没有自动修改工作区文件。")
                }
                finishRewindConversation(msg.sessionId, turnStart.timestamp, children)
            } else {
                agentMessageDao.deleteMessageById(messageId)
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "删除消息失败", e)
        }
    }

    /** Cancels all jobs before destructive conversation/session operations and drains pending approvals. */
    private suspend fun cancelSessionsAndAwait(sessionIds: List<String>): Boolean {
        val ids = sessionIds.distinct()
        val activeSubagents = subAgentEventBus.activeSubSessionIds.value
        val activeJobs = ids.filterTo(linkedSetOf()) { sessionJobs[it]?.isActive == true }
        val activeIds = activeJobs + activeSubagents.intersect(ids.toSet())
        ids.forEach { sid ->
            toolPermissionManager.cancelPendingForSession(sid)
            _queuedRequests.value = _queuedRequests.value + (sid to emptyList())
            if (sid in activeIds) {
                agentNotificationCenter.clear(sid)
                sessionJobs[sid]?.cancel()
            }
        }
        val stopped = withTimeoutOrNull(REWIND_CANCEL_TIMEOUT_MS) {
            activeJobs.forEach { sid -> sessionJobs[sid]?.cancelAndJoin() }
            true
        } ?: false
        if (!stopped) {
            showRewindNotice("仍有工作流未能在 ${REWIND_CANCEL_TIMEOUT_MS / 1000} 秒内停止；为避免继续写入，未执行回退或删除。")
            return false
        }
        activeIds.forEach { sid ->
            sessionJobs.remove(sid)
            setAgentState(sid, AgentUIState.Idle)
            _runningTools.value = _runningTools.value - sid
            // 走 setter 清流：一并清掉发射节流的 pending/emitAt 簿记
            setStreamingText(sid, null)
            setStreamingReasoning(sid, null)
            setCompacting(sid, false)
            setRetryState(sid, null)
            _contextUsages.value = _contextUsages.value - sid
            _queuedRequests.value = _queuedRequests.value - sid
            subAgentEventBus.release(sid)
            if (sid in activeSubagents && sessionUseCase.getSessionById(sid)?.parentId != null) {
                sessionUseCase.updateSubagentStatus(sid, "CANCELLED")
            }
        }
        return true
    }

    /** Removes per-session transient state after the session's persistent rows have been deleted. */
    private fun clearSessionTransientState(sessionId: String) {
        sessionJobs.remove(sessionId)
        subAgentEventBus.release(sessionId)
        agentNotificationCenter.clear(sessionId)
        deletingSubAgentIds.remove(sessionId)
        _agentStates.value = _agentStates.value - sessionId
        _completedSessions.value = _completedSessions.value - sessionId
        _messageLimit.value = _messageLimit.value - sessionId
        loadedMessageLimits.remove(sessionId)
        turnMessageKeys.remove(sessionId)
        // 走 setter 清流：一并清掉发射节流的 pending/emitAt 簿记
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        _reasoningTimings.value = _reasoningTimings.value - sessionId
        _runningTools.value = _runningTools.value - sessionId
        _preparingTools.value = _preparingTools.value - sessionId
        _compactingSessions.value = _compactingSessions.value - sessionId
        _contextUsages.value = _contextUsages.value - sessionId
        _retryStates.value = _retryStates.value - sessionId
        _keySwitchStates.value = _keySwitchStates.value - sessionId
        _queuedRequests.value = _queuedRequests.value - sessionId
        _inputDrafts.value = _inputDrafts.value - sessionId
        _runningCommandSessions.value = _runningCommandSessions.value - sessionId
        if (_pendingScrollMessage.value?.first == sessionId) _pendingScrollMessage.value = null
        draftPrefs.edit().remove(sessionId).apply()
    }

    /** Atomically reserves a full session cascade until the calling coroutine completes or is cancelled. */
    private suspend fun acquireRewindLocks(sessionIds: Collection<String>): Boolean {
        val ids = sessionIds.filter(String::isNotBlank).toSet()
        if (ids.isEmpty()) return true
        synchronized(rewindLock) {
            if (ids.any { it in rewindingSessions }) {
                showRewindNotice("此会话正在执行另一个回退或删除操作，请稍后重试。")
                return false
            }
            rewindingSessions.addAll(ids)
        }
        val owner = coroutineContext[Job]
        if (owner == null) {
            synchronized(rewindLock) { rewindingSessions.removeAll(ids) }
            return false
        }
        owner.invokeOnCompletion {
            synchronized(rewindLock) { rewindingSessions.removeAll(ids) }
        }
        return true
    }

    private fun isSessionRewinding(sessionId: String): Boolean = synchronized(rewindLock) {
        sessionId in rewindingSessions
    }

    private suspend fun descendantsDerivedAtOrAfter(parentSessionId: String, cutoff: Long): List<String> {
        val candidates = sessionUseCase.getSessionCascadeIds(parentSessionId).filter { it != parentSessionId }
        val messageIds = agentMessageDao.getMessagesBySessionOnce(parentSessionId)
            .asSequence()
            .filter { it.timestamp >= cutoff }
            .map { it.id }
            .toSet()
        return candidates.filter { childId ->
            val child = sessionUseCase.getSessionById(childId) ?: return@filter false
            child.dispatchMessageId in messageIds || (child.dispatchMessageId == null && child.createdAt >= cutoff)
        }
    }

    private suspend fun finishRewindConversation(sessionId: String, cutoff: Long, childIds: List<String>) {
        // Child artifacts are removed first; usage rows are intentionally retained for /usage.
        val handled = mutableSetOf<String>()
        childIds.forEach { childId ->
            if (childId in handled) return@forEach
            val descendants = sessionUseCase.getSessionCascadeIds(childId)
            handled += descendants
            descendants.forEach { sid ->
                checkpointManager.clearSessionCheckpoints(sid)
                todoItemDao.deleteBySession(sid)
            }
            sessionUseCase.deleteSession(childId)
            descendants.forEach { sid ->
                clearSessionTransientState(sid)
            }
        }
        messagePersistenceUseCase.rewindConversation(sessionId, cutoff)
        todoItemDao.deleteBySession(sessionId)
        checkpointManager.clearCheckpointsFrom(sessionId, cutoff)
        checkpointManager.setActiveCheckpointId(sessionId, null)
        val remaining = agentMessageDao.getMessagesBySessionOnce(sessionId)
        val lastTokenCount = remaining.lastOrNull { it.role == MessageRole.ASSISTANT.name }?.inputTokens ?: 0
        chatSessionDao.updateLastInputTokens(sessionId, lastTokenCount)
    }

    private fun showRestoreResult(result: com.aicode.feature.agent.domain.checkpoint.CheckpointRestoreResult) {
        // A successful restore is self evident in the workspace. Keep the UI quiet and reserve the
        // notice dialog for skipped files or actual failures that require the user's attention.
        if (result.failures.isEmpty() && result.limitations.isEmpty()) return
        val message = buildList {
            if (result.restoredWorkspaceFileCount > 0) {
                add(context.getString(R.string.checkpoint_rewind_workspace_restored, result.restoredWorkspaceFileCount))
            }
            if (result.restoredGitFileCount > 0) {
                add(context.getString(R.string.checkpoint_rewind_git_restored, result.restoredGitFileCount))
            }
            addAll(result.limitations)
            if (result.failures.isNotEmpty()) {
                val visibleFailures = result.failures.take(MAX_REWIND_FAILURES_IN_DIALOG)
                add("未还原项：\n${visibleFailures.joinToString("\n")}")
                if (result.failures.size > visibleFailures.size) {
                    add("另有 ${result.failures.size - visibleFailures.size} 项未展开")
                }
            }
        }.joinToString("\n")
        showRewindNotice(message)
    }

    private fun showRewindNotice(message: String) {
        _rewindNotice.value = message
    }

    private fun detectLanguage(filePath: String): String {
        return when (filePath.substringAfterLast(".").lowercase()) {
            "kt", "kotlin" -> "kotlin"
            "java" -> "java"
            "dart" -> "dart"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "tsx" -> "typescript"
            "jsx" -> "javascript"
            "go" -> "go"
            "rs" -> "rust"
            else -> "text"
        }
    }

}

data class RewindConfirmation(
    val messageId: String,
    val option: RewindOption,
    val removedMessageCount: Int,
    val removedSubagentCount: Int,
    val stoppedSubagentCount: Int,
    val fileSnapshotCount: Int,
    val unverifiableFileSnapshotCount: Int,
    val hasGitSnapshot: Boolean
)
