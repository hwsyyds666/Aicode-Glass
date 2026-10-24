package com.aicode.feature.agent.domain.workflow

import android.os.SystemClock
import android.util.Base64
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.model.AgentImage
import com.aicode.feature.agent.domain.model.AgentMessage
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.notification.AgentEventInjector
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.AgentNotificationKind
import com.aicode.feature.agent.domain.notification.PendingNotification
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.permission.PermissionScope
import com.aicode.feature.agent.domain.permission.CommandDeletionAnalyzer
import com.aicode.feature.agent.domain.permission.CommandReviewProtocol
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.provider.AIProvider
import com.aicode.feature.agent.domain.provider.AIResponse
import com.aicode.feature.agent.domain.provider.AIStreamChunk
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.StreamingAgentTool
import com.aicode.feature.agent.domain.tool.ToolCall
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.agent.domain.tool.ToolInvoker
import com.aicode.feature.agent.domain.tool.ToolRunResult
import com.aicode.feature.agent.domain.tool.ToolCapability

import com.aicode.feature.agent.domain.tool.modelToolResultText
import com.aicode.feature.agent.domain.tool.toTransportString
import com.aicode.feature.agent.presentation.AgentAttachment
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.CommandAuthLevel
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.LanguageSettingsRepository
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.data.repository.OpenAIAccountRepository
import com.aicode.feature.settings.data.repository.ReviewerModelSettingsRepository
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.agent.domain.provider.AnthropicAdapter
import com.aicode.feature.agent.domain.provider.fixedTemperature
import com.aicode.feature.agent.domain.provider.GeminiAdapter
import com.aicode.feature.agent.domain.provider.AllKeysFailedException
import com.aicode.feature.agent.domain.provider.KeySwitchOutcome
import com.aicode.feature.agent.domain.provider.isKeySwitchFailure
import com.aicode.feature.agent.domain.provider.OpenAIAdapter
import com.aicode.feature.settings.domain.model.ProviderType
import com.aicode.feature.settings.domain.model.ModelContextPolicy
import com.aicode.feature.agent.domain.provider.StreamApiException
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * 阶段三重构 (完全版)：基于不可变状态 (Immutable State) 与 MVI 架构的 Agent 工作流引擎。
 * 通过定义明确的 AgentSessionState, AgentAction 与 AgentSideEffect，
 * 采用 Reducer 来进行状态扭转，将纯函数的业务逻辑与带有副作用的外部环境操作完全解耦。
 */
class StatefulAgentWorkflow @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val toolInvoker: ToolInvoker,
    private val aiProviderRepository: AIProviderRepository,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    private val promptProvider: SystemPromptProvider,
    private val permissionManager: ToolPermissionManager,
    private val policyEngine: ToolPermissionPolicyEngine,
    private val contextCompactor: ContextCompactor,
    private val modelMetadataService: ModelMetadataService,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val titleModelSettingsRepository: TitleModelSettingsRepository,
    private val reviewerModelSettingsRepository: ReviewerModelSettingsRepository,
    private val languageSettingsRepository: LanguageSettingsRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val checkpointManager: CheckpointManager,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val keyRotator: ProviderKeyRotator,
    private val openAIAccountRepository: OpenAIAccountRepository,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val eventInjector: AgentEventInjector,
    private val fileAccess: FileAccessProvider
) : AgentWorkflow {

    /**
     * 落库专用 scope：独立于调用方协程树、不可取消。
     *
     * 用途：把「LLM 调用记录写盘」从协程取消展开路径上摘出去。
     * 详见各 finally 处注释——在取消展开期间调用任何 suspend（包括 withContext）
     * 会让协程状态机在挂起/恢复中错乱，触发 ART 原生 abort「No pending exception
     * expected」/「Unexpected instruction」直接闪退。
     * 用 [CoroutineScope.launch] 派发：launch 不是 suspend 函数，不会挂起当前协程。
     */
    private val recordScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val TAG = "StatefulAgentWorkflow"
        const val OPENAI_ACCOUNT_BASE_URL = "https://api.openai.com/"
        const val USER_REJECTED_CODE = "USER_REJECTED"
        const val TITLE_GENERATOR_FILE = "agent/title-generator.md"
        const val TITLE_MAX_CHARS = 50
        const val COMMIT_GENERATOR_FILE = "agent/commit-generator.md"
        /** AI 命令审查器提示词：「自主执行」挡位下逐条评审 shell 命令。 */
        const val COMMAND_REVIEWER_FILE = "agent/command-reviewer.md"
        const val COMMAND_REVIEW_CACHE_LIMIT = 128
        /** 模式提醒提示词：复用 prompts 目录文件（用户可自定义覆盖），切换时随消息注入而非进 system。 */
        const val MODE_REMINDER_PLAN_FILE = "agent/plan-mode.md"
        const val MODE_REMINDER_ASK_FILE = "agent/ask-mode.md"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
        /** 模型直出图片落盘目录（与 GenerateImageTool 保持一致）。 */
        const val GENERATED_IMAGE_DIR = "~/.aicode/generated-images"
        const val MAX_GENERATED_IMAGE_BYTES = 20L * 1024 * 1024
    }

    /**
     * 每会话「上次已注入模式提醒的模式」。模式提醒只在变化时注入，避免每轮把同一段文本
     * 重复拼到最新用户消息上——那样下一轮重建历史时该位置（Anthropic 缓存断点）内容不一致，
     * 会白白丢掉一段前缀缓存。进程内缓存，重启后首轮再注入一次（无害）。
     */
    private val lastInjectedMode = java.util.concurrent.ConcurrentHashMap<String, AgentMode>()

    /** 会话内命令审查缓存；只缓存审查结论，人工授权选择仍逐次生效。 */
    private val commandReviewCache = CommandReviewCache<AiReviewResult>(COMMAND_REVIEW_CACHE_LIMIT)

    /** 不可变状态树 */
    data class AgentSessionState(
        val messages: List<AgentMessage> = emptyList(),
        val iterations: Int = 0,
        val isFinished: Boolean = false,
        val error: String? = null,
        /** 错误类型码（如服务端 stop_reason），供 UI 换成本地化文案；null 表示直接展示 [error]。 */
        val errorCode: String? = null,
        /** 本批模型返回的 toolCalls（原始顺序，用于最后按序组装 tool 响应） */
        val batchToolCalls: List<ToolCall> = emptyList(),
        /** 待请求权限的 toolCall（逐个弹窗收集） */
        val pendingPermissionCalls: List<ToolCall> = emptyList(),
        /** 已批准、待并行执行的 toolCall */
        val approvedToolCalls: List<ToolCall> = emptyList(),
        /** 被策略/系统拒绝（非用户拒绝）的 tool 结果，key = toolCall.id */
        val rejectedToolResults: Map<String, ToolBatchResult> = emptyMap()
    )

    /** 改变状态的动作 (Action) */
    sealed interface AgentAction {
        data class InitRequest(val initialMessages: List<AgentMessage>) : AgentAction
        data class LlmResponse(val response: AIResponse, val messageId: String) : AgentAction
        data class LlmError(val error: String, val reasonCode: String? = null) : AgentAction
        data class PermissionEvaluated(
            val toolCall: ToolCall,
            val approved: Boolean,
            val argsPreview: String,
            val denyReason: String = "用户拒绝执行该工具",
            val errorCode: String = "USER_REJECTED"
        ) : AgentAction
        data class ToolBatchFinished(
            val results: List<ToolBatchResult>
        ) : AgentAction
    }

    private data class PermissionCheckResult(
        val approved: Boolean,
        val denyReason: String = "用户拒绝执行该工具",
        val errorCode: String = "USER_REJECTED",
        val reviewRecord: String? = null
    )

    /** 批量工具执行结果：携带 toolCall 元信息，供最后按原始顺序组装 ToolResultMessage。 */
    data class ToolBatchResult(
        val id: String,
        val toolName: String,
        val result: String,
        val isError: Boolean,
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<AgentAttachment> = emptyList(),
        val images: List<AgentImage> = emptyList()
    )

    /** 需要在外部环境中执行的副作用 (SideEffect) */
    sealed interface AgentSideEffect {
        object CallLlm : AgentSideEffect
        data class PersistUser(val message: AgentMessage.UserMessage) : AgentSideEffect
        data class RequestPermission(val toolCall: ToolCall) : AgentSideEffect
        /** 批量并行执行已批准的工具；传入空列表表示本批无工具可执行，直接进入收尾。 */
        data class ExecuteToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
        /** 整批取消（用户拒绝批次中某个调用）：补发已启动工具的完成事件，清理 UI「执行中」状态。 */
        data class CancelToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
    }

    private suspend fun getEffectiveProvider(sessionId: String?): AIProvider {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 供应商，请到设置中添加并选择一个")
        if (!config.hasUsableApiKey) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        return createStandaloneProvider(config, sessionId)
    }

    /**
     * 解析当前生效的 provider 配置：优先用 session 绑定的 providerId/model，回退全局 active provider。
     * session 绑定的 provider 不存在或已禁用时回退全局，保证老会话与异常数据不中断。
     */
    private suspend fun resolveProviderConfig(sessionId: String?): AIProviderConfig? {
        if (sessionId != null) {
            val session = sessionUseCase.getSessionById(sessionId)
            val boundProviderId = session?.providerId
            val boundModel = session?.model
            if (!boundProviderId.isNullOrBlank()) {
                val config = aiProviderRepository.getProviderById(boundProviderId)
                val isOAuthAccount = config?.let(OpenAIAccountRepository::isAccountProvider) == true
                if (config != null && (config.isEnabled || isOAuthAccount) && config.hasUsableApiKey) {
                    // 绑定的模型可能已被移出该 provider 的模型列表，此时绑定失效、继续往下回退默认模型
                    if (boundModel.isNullOrBlank()) return config
                    if (boundModel in config.models || isOAuthAccount) return config.copy(selectedModel = boundModel)
                }
            }
        }
        // 回退：新会话默认模型（主页空会话中选择后记忆）；未设置则返回 null，由调用方报错引导。
        val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId()
        val defaultModel = defaultModelSettingsRepository.getDefaultModel()
        if (defaultProviderId.isNotBlank() && defaultModel.isNotBlank()) {
            val config = aiProviderRepository.getProviderById(defaultProviderId)
            val isOAuthAccount = config?.let(OpenAIAccountRepository::isAccountProvider) == true
            if (config != null && (config.isEnabled || isOAuthAccount) && config.hasUsableApiKey &&
                (defaultModel in config.models || isOAuthAccount)
            ) {
                return config.copy(selectedModel = defaultModel)
            }
        }
        return null
    }

    override suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit): Boolean {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 供应商，请到设置中添加并选择一个")
        if (!config.hasUsableApiKey) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        val provider = createStandaloneProvider(config, sessionId)
        val history = messagePersistenceUseCase.buildHistory(sessionId, "__manual_compress__")
        if (history.size <= 2) return false
        val compactionProvider = resolveCompactionFallbackProvider(sessionId) ?: provider
        // 摘要仍用专用压缩模型（尊重用户选择），但保留最近消息的窗口预算按主聊天模型算，
        // 与自动压缩一致——否则小窗口的压缩模型会让手动压缩保留的最近上下文偏少。
        val result = contextCompactor.compactIfNeeded(history, compactionProvider, sessionId, force = true, windowProvider = provider, onEvent = onEvent)
        return result.compacted
    }

    /**
     * 根据 [config] 创建一个全新的、独立的 [AIProvider] 实例。
     * 用于上下文压缩等独立请求场景，完全不占用或修改主对话所用的 Provider 单例。
     * 同时把提供商级 LLM 缓存开关（Anthropic 断点 / OpenAI cache key）应用到实例。
     */
    private suspend fun createStandaloneProvider(config: AIProviderConfig, sessionId: String?): AIProvider {
        val openAIAccountId = OpenAIAccountRepository.accountIdFromProviderMarker(config.apiKey)
        val isOpenAIAccount = openAIAccountId != null
        if (isOpenAIAccount && config.type != ProviderType.OPENAI) {
            throw IllegalStateException(openAIAccountRepository.invalidProviderTypeMessage())
        }
        val accountRoute = if (isOpenAIAccount) {
            openAIAccountRepository.routeForModel(openAIAccountId!!, config.effectiveModel)
                ?: throw IllegalStateException(openAIAccountRepository.noAvailableAccountMessage(config.effectiveModel))
        } else null
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also {
                it.chatCacheKeyEnabled = config.openaiChatCacheKey && !isOpenAIAccount
                if (isOpenAIAccount) {
                    it.chatGptPlanUsage = true
                    it.chatGptUsageLimitMessage = openAIAccountRepository.usageLimitMessage()
                }
            }
        }
        // 多 Key 模式下由轮换器决定本次用哪个 Key（会话内粘住，Key 不可用时切下一个并重发）。
        val activeKeys = if (isOpenAIAccount) emptyList() else config.effectiveApiKeys
        val accountToken = accountRoute?.accessToken
        provider.apiKey = accountToken ?: keyRotator.activeKey(config, sessionId)
            ?: throw IllegalStateException("「${config.name}」的 Key 均在冷却中，请稍后重试")
        // 只有真正的多 Key 才需要自动切换：单 Key 冷却后没有可切换目标，只会把用户锁死一段时间。
        val keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? =
            if (activeKeys.size > 1) {
                { error, failedKey, triedKeys ->
                    if (!error.isKeySwitchFailure(config.effectiveKeySwitchStatusCodes)) {
                        null
                    } else {
                        val switched = keyRotator.reportFailure(config.id, sessionId, failedKey, triedKeys)
                        if (switched == null) {
                            throw AllKeysFailedException(
                                "「${config.name}」的 ${activeKeys.size} 个 Key 均失败：${error.message ?: error.javaClass.simpleName}",
                                error
                            )
                        }
                        KeySwitchOutcome(switched.newKey, switched.newIndex, switched.total)
                    }
                }
            } else {
                null
            }
        provider.keySwitcher = keySwitcher
        provider.baseUrl = if (isOpenAIAccount) OPENAI_ACCOUNT_BASE_URL else config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = if (isOpenAIAccount) false else config.useFullUrl
        provider.useResponseApi = if (isOpenAIAccount) true else config.useResponseApi
        provider.providerId = accountRoute?.providerId ?: config.id
        provider.logSessionId = sessionId
        provider.customHeaders = if (isOpenAIAccount) emptyMap() else config.customHeaders
        val metadata = modelMetadataService.resolve(accountRoute?.providerId ?: config.id, config.type, config.effectiveModel)
        // 模型元数据的输出上限（models.dev limit.output）：不传时 Anthropic 会把输出卡在 adapter 兜底值上。
        provider.maxOutputTokens = metadata.outputTokens
        // 元数据说不接受自定义温度就不发该字段（kimi-k3、gpt-5 系带了直接 400）；允许的只发官方固定值。
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = if (isOpenAIAccount) 0 else generalSettingsRepository.maxNetworkRetries()
        return provider
    }

    /** 核心 Reducer，接收旧状态与 Action，返回新状态以及触发的副作用列表 (纯函数) */
    private fun reduce(
        state: AgentSessionState,
        action: AgentAction
    ): Pair<AgentSessionState, List<AgentSideEffect>> {
        var newState = state
        val effects = mutableListOf<AgentSideEffect>()

        when (action) {
            is AgentAction.InitRequest -> {
                newState = state.copy(messages = action.initialMessages)
                effects.add(AgentSideEffect.CallLlm)
            }
            is AgentAction.LlmResponse -> {
                val assistantMsg = AgentMessage.AssistantMessage(
                    id = action.messageId,
                    content = action.response.content,
                    toolCalls = action.response.toolCalls,
                    reasoning = action.response.reasoning ?: "",
                    signature = action.response.signature ?: "",
                    thinkingBlocksJson = action.response.thinkingBlocksJson ?: "",
                    images = action.response.images,
                    inputTokens = action.response.inputTokens
                )
                newState = state.copy(
                    messages = state.messages + assistantMsg,
                    iterations = state.iterations + 1
                )
                
                if (action.response.toolCalls.isEmpty()) {
                    if (action.response.isAborted) {
                        // 拒答/上下文超限：正文可能为空，静默结束会让用户看到空白气泡以为卡死。
                        newState = newState.copy(
                            isFinished = true,
                            error = action.response.stopDetail ?: "",
                            errorCode = action.response.stopReason
                        )
                    } else if (action.response.isTruncated) {
                        val continuation = AgentMessage.UserMessage(content = "Your response was truncated. Continue from where it stopped.")
                        newState = newState.copy(messages = newState.messages + continuation)
                        effects.add(AgentSideEffect.PersistUser(continuation))
                        effects.add(AgentSideEffect.CallLlm)
                    } else {
                        newState = newState.copy(isFinished = true)
                    }
                } else {
                    // 本批多个 tool_call：全部进入待权限队列，逐个弹窗收集批准；
                    // 全部批准后才进入并行执行阶段（见 PermissionEvaluated / ToolBatchFinished）。
                    val toolCalls = action.response.toolCalls.toList()
                    newState = newState.copy(
                        batchToolCalls = toolCalls,
                        pendingPermissionCalls = toolCalls,
                        approvedToolCalls = emptyList(),
                        rejectedToolResults = emptyMap()
                    )
                    effects.add(AgentSideEffect.RequestPermission(toolCalls.first()))
                }
            }
            is AgentAction.LlmError -> {
                newState = state.copy(isFinished = true, error = action.error, errorCode = action.reasonCode)
            }
            is AgentAction.PermissionEvaluated -> {
                if (action.approved) {
                    // 批准：当前 toolCall 移入已批准集合；若还有待请求权限的则继续弹窗，否则开始并行执行。
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    val approved = newState.approvedToolCalls + action.toolCall
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        approvedToolCalls = approved
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(approved))
                    }
                } else {
                    val rawResult = ToolResult.Error(action.denyReason, action.errorCode).toTransportString()
                    if (action.errorCode == USER_REJECTED_CODE) {
                        // 模型一次可能返回多个 tool_calls。用户拒绝批次中任意一个 → 整批取消：
                        // 按 batchToolCalls 原始顺序为所有调用补上 tool 响应（不重复不遗漏），
                        // 否则 assistant(toolCalls=N) 后只有部分 tool 消息，OpenAI 会报 400
                        // "insufficient tool messages following tool_calls"。
                        val cancelled = newState.batchToolCalls.map { call ->
                            AgentMessage.ToolResultMessage(
                                id = call.id,
                                toolName = call.name,
                                result = ToolResult.Error(
                                    "用户拒绝了本轮工具调用，该调用未执行。",
                                    USER_REJECTED_CODE
                                ).toTransportString()
                            )
                        }
                        newState = state.copy(
                            messages = state.messages + cancelled,
                            batchToolCalls = emptyList(),
                            pendingPermissionCalls = emptyList(),
                            approvedToolCalls = emptyList(),
                            isFinished = true
                        )
                        // 已批准未执行（已收到 ToolCallStarted）的工具需补发完成事件，
                        // 否则 UI 与落库消息会一直停留在「执行中」。
                        if (state.approvedToolCalls.isNotEmpty()) {
                            effects.add(AgentSideEffect.CancelToolBatch(state.approvedToolCalls))
                        }
                        return newState to effects
                    }
                    // 策略/系统拒绝（如 ASK 模式禁止写入）：记录拒绝结果，继续收集后续权限。
                    val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                    newState = newState.copy(
                        pendingPermissionCalls = remaining,
                        rejectedToolResults = newState.rejectedToolResults + (
                            action.toolCall.id to ToolBatchResult(
                                id = action.toolCall.id,
                                toolName = action.toolCall.name,
                                result = rawResult,
                                isError = true
                            )
                        )
                    )
                    if (remaining.isNotEmpty()) {
                        effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                    } else {
                        effects.add(AgentSideEffect.ExecuteToolBatch(newState.approvedToolCalls))
                    }
                }
            }
            is AgentAction.ToolBatchFinished -> {
                // 本批工具全部执行完，按 batchToolCalls 原始顺序组装 tool 响应：
                // 优先取策略拒绝结果，其次取并行执行结果，保证与 assistant(toolCalls) 顺序一致。
                val resultsById = action.results.associateBy { it.id }
                val appendedMessages = mutableListOf<AgentMessage>()
                newState.batchToolCalls.forEach { call ->
                    val batchResult = newState.rejectedToolResults[call.id] ?: resultsById[call.id] ?: return@forEach
                    appendedMessages.add(
                        AgentMessage.ToolResultMessage(
                            id = batchResult.id,
                            toolName = batchResult.toolName,
                            result = batchResult.result,
                            images = batchResult.images,
                            modelResult = modelToolResultText(batchResult.toolName, batchResult.result)
                        )
                    )
                }
                newState = state.copy(
                    messages = state.messages + appendedMessages,
                    batchToolCalls = emptyList(),
                    pendingPermissionCalls = emptyList(),
                    approvedToolCalls = emptyList(),
                    rejectedToolResults = emptyMap()
                )
                effects.add(AgentSideEffect.CallLlm)
            }
        }
        
        return Pair(newState, effects)
    }

    override fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent> = channelFlow {
        var currentContext = context
        // Each user request in PLAN mode must establish a visible plan before any write-like tool runs.
        var planSubmittedForRequest = false
        var state = AgentSessionState()
        var currentTools = tools
        val actionQueue = ArrayDeque<AgentAction>()
        // 模式提醒放在最新用户消息尾部，不改 system 前缀。PLAN 每个新请求都重申“本轮先提交计划”，
        // 因为计划门禁按用户请求重置；ASK/BUILD 仍只在模式变化时注入，避免无意义重复。
        val modeReminder = if (currentContext.mode == AgentMode.PLAN) {
            buildModeReminder(AgentMode.PLAN)
        } else {
            takeModeReminderIfChanged(currentContext.sessionId, currentContext.mode)
        }
        actionQueue.addLast(
            AgentAction.InitRequest(
                currentContext.history + AgentMessage.UserMessage(
                    id = currentContext.inputMessageId,
                    content = if (modeReminder == null) userRequest else "$userRequest\n\n$modeReminder",
                    images = currentContext.inputImages
                )
            )
        )

        // 构建系统提示词会扫描 Skill / 规则 / 记忆目录，在真机 ROOT 通道下这些文件读写是通过
        // `su -c` 子进程 + Process.waitFor() 完成的，属于阻塞式 IO，必须切到 IO 线程；
        // 否则主线程会被 waitFor() 卡住直到 ANR、进程被系统杀掉（表现为闪退）。
        val systemPrompt = withContext(Dispatchers.IO) { promptProvider.build(currentContext) }
        val aiProvider = getEffectiveProvider(currentContext.sessionId)
        // 压缩失败后本轮（本次用户请求内）不再重复尝试压缩，避免每次 LLM 调用都白试一次。
        var compactionAttemptFailed = false
        val metadata = modelMetadataService.resolve(aiProvider.providerId, when (aiProvider) {
            is AnthropicAdapter -> ProviderType.ANTHROPIC
            is GeminiAdapter -> ProviderType.GEMINI
            else -> ProviderType.OPENAI
        }, aiProvider.model)
        val inputBudget = ModelContextPolicy.effectiveInputBudget(metadata)
        if (aiProvider is AnthropicAdapter) {
            aiProvider.maxOutputTokens = ModelContextPolicy.outputReserveTokens(metadata)
        }
        val lastAssistantIndex = currentContext.history.indexOfLast { it is AgentMessage.AssistantMessage && it.inputTokens > 0 }
        var baselineEstimate = ContextTokenEstimator.estimate(systemPrompt,
            currentContext.history.take(lastAssistantIndex.coerceAtLeast(0)), currentTools)
        var baselineUsage = if (currentContext.lastInputTokens > 0 && lastAssistantIndex >= 0) {
            (currentContext.history[lastAssistantIndex] as AgentMessage.AssistantMessage).inputTokens
        } else 0
        var overflowRecoveryAttempted = false

        while (!state.isFinished && actionQueue.isNotEmpty()) {
            val action = actionQueue.removeFirst()
            val (newState, effects) = reduce(state, action)
            state = newState

            for (effect in effects) {
                when (effect) {
                    is AgentSideEffect.PersistUser -> {
                        currentContext.sessionId?.let { sessionId ->
                            messagePersistenceUseCase.persist(sessionId, com.aicode.feature.agent.presentation.MessageRole.USER,
                                effect.message.content, id = effect.message.id)
                            messagePersistenceUseCase.invalidateHistory(sessionId)
                        }
                    }
                    is AgentSideEffect.CallLlm -> {
                        val providerInUse = aiProvider
                        // 压缩轮：若配置了压缩专用模型，使用独立压缩模型压缩
                        val compactionProvider = resolveCompactionFallbackProvider(currentContext.sessionId) ?: providerInUse
                        var compactedMessages = state.messages
                        if (!compactionAttemptFailed) {
                            val estimate = ContextTokenEstimator.estimate(systemPrompt, state.messages, currentTools)
                            val predictedInput = ContextTokenEstimator.calibrated(estimate, baselineEstimate, baselineUsage)
                            val compaction = contextCompactor.compactIfNeeded(state.messages, compactionProvider, context.sessionId,
                                windowProvider = aiProvider, systemPrompt = systemPrompt, tools = currentTools,
                                currentInputTokens = predictedInput) { event ->
                                if (event is AgentEvent.CompactionFailed) compactionAttemptFailed = true
                                send(event)
                            }
                            compactedMessages = compaction.messages
                            if (compaction.compacted) {
                                state = state.copy(messages = compaction.messages)
                                baselineUsage = 0
                                baselineEstimate = 0
                            }
                        }
                        val requestEstimate = ContextTokenEstimator.estimate(systemPrompt, compactedMessages, currentTools)
                        val predictedInput = ContextTokenEstimator.calibrated(requestEstimate, baselineEstimate, baselineUsage)
                        send(AgentEvent.ContextUsage(predictedInput, inputBudget, true))
                        if (predictedInput >= inputBudget) {
                            actionQueue.addLast(AgentAction.LlmError("", "input_budget_exceeded"))
                            continue
                        }

                        val acc = StringBuilder()
                        val reasoningAcc = StringBuilder()
                        var finalResponse: AIResponse? = null

                        // 调用统计埋点：记录请求发出/首字/结束时刻与 usage，失败与取消同样留痕。
                        val callStartElapsed = SystemClock.elapsedRealtime()
                        val callStartWall = System.currentTimeMillis()
                        val callKind = "chat"
                        var ttfbElapsed: Long? = null
                        var callError: String? = null
                        var callCompleted = false

                        // 流式 delta 节流：上游每个 chunk 都携带完整累积文本，逐条 send 会让
                        // ViewModel 端每秒重建几十次状态、UI 端反复重启打字机协程。按时间窗口合并：
                        // 窗口内只保留最新累积文本，到窗口边界才发送，把下游事件频率压到 ~16/s。
                        // 打字机渲染本身有 100ms 节流，少发中间态无感知；collect 结束补发最后 pending。
                        val DELTA_THROTTLE_MS = 60L
                        var lastTextDeltaSentAt = 0L
                        var lastReasoningDeltaSentAt = 0L
                        var pendingTextDelta: String? = null
                        var pendingReasoningDelta: String? = null
                        var retryAttempts = 0
                        suspend fun flushPendingTextDelta() {
                            val text = pendingTextDelta ?: return
                            pendingTextDelta = null
                            send(AgentEvent.AssistantDelta(text))
                        }
                        suspend fun flushPendingReasoningDelta() {
                            val text = pendingReasoningDelta ?: return
                            pendingReasoningDelta = null
                            send(AgentEvent.ReasoningDelta(text))
                        }

                        try {
                            // 发送前按实际模型的视觉能力处理图片（同 execute 路径）。
                            val supportsVision = activeModelSupportsVision(currentContext.sessionId)
                            val messagesToSend = withSkillHint(
                                sanitizeImagesForModel(compactedMessages, supportsVision),
                                currentContext,
                                currentTools
                            )
                            providerInUse.completeStream(systemPrompt, messagesToSend, currentTools, currentContext.reasoningEffort).collect { chunk ->
                                when (chunk) {
                                    is AIStreamChunk.TextDelta -> {
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        acc.append(chunk.text)
                                        pendingTextDelta = acc.toString()
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastTextDeltaSentAt >= DELTA_THROTTLE_MS) {
                                            flushPendingTextDelta()
                                            lastTextDeltaSentAt = now
                                        }
                                    }
                                    is AIStreamChunk.ReasoningDelta -> {
                                        // 思考内容也算首字（推理模型先吐思考再吐正文）
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        reasoningAcc.append(chunk.text)
                                        pendingReasoningDelta = reasoningAcc.toString()
                                        val now = SystemClock.elapsedRealtime()
                                        if (now - lastReasoningDeltaSentAt >= DELTA_THROTTLE_MS) {
                                            flushPendingReasoningDelta()
                                            lastReasoningDeltaSentAt = now
                                        }
                                    }
                                    is AIStreamChunk.Retrying -> {
                                        retryAttempts = maxOf(retryAttempts, chunk.attempt)
                                        acc.setLength(0)
                                        reasoningAcc.setLength(0)
                                        pendingTextDelta = null
                                        pendingReasoningDelta = null
                                        lastTextDeltaSentAt = 0L
                                        lastReasoningDeltaSentAt = 0L
                                        send(AgentEvent.Retrying(chunk.attempt, chunk.maxRetries, chunk.error))
                                    }
                                    is AIStreamChunk.KeySwitched -> {
                                        acc.setLength(0)
                                        reasoningAcc.setLength(0)
                                        pendingTextDelta = null
                                        pendingReasoningDelta = null
                                        lastTextDeltaSentAt = 0L
                                        lastReasoningDeltaSentAt = 0L
                                        send(AgentEvent.KeySwitched(chunk.newIndex, chunk.total))
                                    }
                                    is AIStreamChunk.Final -> {
                                        // 纯工具调用轮没有文本/思考增量，Final 是首个内容事件，兜底记为 TTFB
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        finalResponse = chunk.response
                                    }
                                    is AIStreamChunk.ToolCallDeclared -> {
                                        // 工具名先于参数到达：立刻告诉 UI「模型准备调什么」，
                                        // 免得长参数流式期间一直停在「正在思考」。
                                        if (ttfbElapsed == null) ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed
                                        send(AgentEvent.ToolCallPreparing(chunk.name))
                                    }
                                }
                            }
                            // 节流窗口内可能还压着最新累积文本：补发，保证 UI 尾巴拿到完整文本再交接落库。
                            flushPendingTextDelta()
                            flushPendingReasoningDelta()
                            val aiResponse = finalResponse ?: AIResponse(content = acc.toString())
                            // 模型直出图片（Gemini 图像模型）不随流式增量到达，整块在 Final 里：
                            // 先把 base64 落盘到 ~/.aicode/generated-images/ 并构造 UI 附件（只存路径不存 base64），
                            // 再随 LlmResponse 把带 path 的 images 交 reduce 挂上 AssistantMessage 供下一回放。
                            val (persistedImages, attachments) =
                                if (aiResponse.images.isNotEmpty()) persistModelImages(aiResponse.images) else emptyList<AgentImage>() to emptyList()
                            callCompleted = true
                            // 将本轮 reasoning 附加到 AIResponse，以便 reduce 时存入 AssistantMessage 并在下一轮回传
                            if (aiResponse.stopReason == "model_context_window_exceeded" && !overflowRecoveryAttempted) {
                                overflowRecoveryAttempted = true
                                val recovery = contextCompactor.compactIfNeeded(state.messages, compactionProvider,
                                    currentContext.sessionId, force = true, windowProvider = aiProvider,
                                    systemPrompt = systemPrompt, tools = currentTools, currentInputTokens = predictedInput) { send(it) }
                                if (recovery.compacted) {
                                    state = state.copy(messages = recovery.messages)
                                    baselineUsage = 0
                                    baselineEstimate = 0
                                    actionQueue.addLast(AgentAction.InitRequest(recovery.messages))
                                    continue
                                }
                            }
                            baselineEstimate = requestEstimate
                            baselineUsage = aiResponse.inputTokens
                            send(AgentEvent.ContextUsage(
                                if (baselineUsage > 0) baselineUsage else requestEstimate, inputBudget, baselineUsage <= 0
                            ))
                            val responseMessageId = UUID.randomUUID().toString()
                            val responseWithReasoning = if (reasoningAcc.isNotEmpty()) {
                                aiResponse.copy(reasoning = reasoningAcc.toString())
                            } else aiResponse

                            if (aiResponse.content.isNotBlank() || aiResponse.toolCalls.isNotEmpty() || attachments.isNotEmpty()) {
                                val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                                send(
                                    AgentEvent.AssistantText(
                                        aiResponse.content,
                                        aiResponse.toolCalls,
                                        reasoningAcc.toString(),
                                        aiResponse.signature ?: "",
                                        aiResponse.inputTokens,
                                        aiResponse.outputTokens,
                                        aiResponse.cachedInputTokens,
                                        aiResponse.thinkingBlocksJson ?: "",
                                        attachments = attachments,
                                        messageId = responseMessageId,
                                        persisted = persisted
                                    )
                                )
                                persisted.await()
                            }
                            actionQueue.addLast(
                                AgentAction.LlmResponse(
                                    if (persistedImages.isNotEmpty()) responseWithReasoning.copy(images = persistedImages)
                                    else responseWithReasoning,
                                    messageId = responseMessageId
                                )
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (!overflowRecoveryAttempted && acc.isEmpty() && reasoningAcc.isEmpty() && isContextOverflow(e)) {
                                overflowRecoveryAttempted = true
                                val recovery = contextCompactor.compactIfNeeded(state.messages, compactionProvider,
                                    currentContext.sessionId, force = true, windowProvider = aiProvider,
                                    systemPrompt = systemPrompt, tools = currentTools, currentInputTokens = predictedInput) { send(it) }
                                if (recovery.compacted) {
                                    state = state.copy(messages = recovery.messages)
                                    baselineUsage = 0
                                    baselineEstimate = 0
                                    actionQueue.addLast(AgentAction.InitRequest(recovery.messages))
                                    callError = e.message
                                    continue
                                }
                            }
                            val partial = acc.toString()
                            val reasoning = reasoningAcc.toString()
                            // 流式被中断时也要落库已收到的思考：否则下方 finally 会清空流式思考气泡，
                            // 而落库的接力消息又没产生，表现为「思考显示后凭空消失且无报错」。
                            // 有正文或有思考其一即落库；两者皆空则不写空消息。
                            if (partial.isNotEmpty() || reasoning.isNotBlank()) {
                                send(AgentEvent.AssistantText(partial, emptyList(), reasoning))
                            }
                            // 多 Key 的自动切换与重发已在 adapter 内完成（见 AIProvider.keySwitcher）；
                            // 走到这里说明不是 Key 问题、或候选 Key 已全部失败，直接上报原始错误。
                            val errorText = "LLM 调用失败: ${e.message}"
                            actionQueue.addLast(AgentAction.LlmError(errorText))
                            callError = e.message ?: e.javaClass.simpleName
                        } finally {
                            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
                            val usage = finalResponse
                            // 关键：取消展开（用户点停止）期间绝不能调用 suspend。
                            // 即使用 withContext(NonCancellable) 包住 Room insert 也一样会闪退——
                            // 设备 tombstone（2026-10-05，8+ 次复现）栈全部落在
                            // StatefulAgentWorkflow$executeEvents$1.invokeSuspend+0，abort 文案
                            // 「No pending exception expected: JobCancellationException」与
                            // 「Unexpected instruction: unused-e5」，即协程状态机在挂起/恢复中
                            // label 错乱，ART 解释器随后崩在非法指令上。
                            // 因此这里改为派发到独立的不可取消 scope：launch 不是 suspend 函数，
                            // 不会挂起/恢复当前协程，彻底避开取消展开路径上的状态机跳转。
                            val record = LlmCallRecordEntity(
                                sessionId = currentContext.sessionId,
                                providerId = providerInUse.providerId.ifBlank { null },
                                model = providerInUse.model,
                                reasoningEffort = currentContext.reasoningEffort,
                                kind = callKind,
                                inputTokens = usage?.inputTokens ?: 0,
                                outputTokens = usage?.outputTokens ?: 0,
                                cachedInputTokens = usage?.cachedInputTokens ?: 0,
                                cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                                ttfbMillis = ttfbElapsed?.toInt(),
                                durationMillis = durationMillis,
                                status = when {
                                    callCompleted -> "success"
                                    callError != null -> "error"
                                    else -> "cancelled"
                                },
                                errorMessage = callError,
                                stopReason = usage?.stopReason,
                                retryCount = retryAttempts,
                                createdAt = callStartWall
                            )
                            recordScope.launch { runCatching { llmCallRecordDao.insert(record) } }
                        }
                    }
                    is AgentSideEffect.RequestPermission -> {
                        val tool = toolRegistry.getTool(effect.toolCall.name)
                        val argsPreview = JsonObject(effect.toolCall.arguments).toString().take(500)
                        val checkResult = requestPermissionIfNeeded(
                            tool = tool,
                            callId = effect.toolCall.id,
                            arguments = effect.toolCall.arguments,
                            argsPreview = argsPreview,
                            mode = currentContext.mode,
                            sessionId = currentContext.sessionId,
                            userRequest = userRequest,
                            context = currentContext
                        )
                        val displayArgsPreview = if (checkResult.approved && checkResult.reviewRecord != null) {
                            withCommandReviewRecord(argsPreview, checkResult.reviewRecord)
                        } else argsPreview

                        if (!checkResult.approved) {
                            val rawResult = ToolResult.Error(checkResult.denyReason, checkResult.errorCode).toTransportString()
                            send(AgentEvent.ToolCallFinished(effect.toolCall.id, effect.toolCall.name, rawResult, true, argsPreview))
                        } else {
                            send(AgentEvent.ToolCallStarted(effect.toolCall.id, effect.toolCall.name, displayArgsPreview))
                        }
                        actionQueue.addLast(AgentAction.PermissionEvaluated(effect.toolCall, checkResult.approved, displayArgsPreview, checkResult.denyReason, checkResult.errorCode))
                    }
                    is AgentSideEffect.CancelToolBatch -> {
                        // 整批取消：已批准未执行的工具补发完成事件（内容为未执行），
                        // 让 ViewModel 清理 runningTool 并 REPLACE 掉「执行中」占位消息。
                        effect.toolCalls.forEach { toolCall ->
                            send(
                                AgentEvent.ToolCallFinished(
                                    id = toolCall.id,
                                    toolName = toolCall.name,
                                    result = ToolResult.Error(
                                        "用户拒绝了本轮工具调用，该调用未执行。",
                                        USER_REJECTED_CODE
                                    ).toTransportString(),
                                    isError = true
                                )
                            )
                        }
                    }
                    is AgentSideEffect.ExecuteToolBatch -> {
                        // 并行执行本批已批准的工具。先统一记录 checkpoint（editFile/writeFile 修改前快照），
                        // 再并行执行；mode 切换检查在结果收集后于主协程串行处理。
                        val toolCalls = effect.toolCalls
                        val runResultsById = mutableMapOf<String, ToolRunResult>()

                        // A PLAN batch can contain a plan update and edits in the same model response.
                        // Execute the todo snapshot first; otherwise edits could race ahead. Clearing
                        // the plan also clears this request's execution gate.
                        val planEnforcedForBatch = currentContext.mode == AgentMode.PLAN || toolCalls.any { call ->
                            call.name == "planMode" &&
                                (call.arguments["action"] as? JsonPrimitive)?.contentOrNull.equals("enter", ignoreCase = true)
                        }
                        val enteringPlanThisBatch = currentContext.mode != AgentMode.PLAN && planEnforcedForBatch
                        if (enteringPlanThisBatch) planSubmittedForRequest = false
                        var planSubmittedInCurrentBatch = false
                        val planCalls = if (planEnforcedForBatch) {
                            toolCalls.filter { it.name == "todo" }
                        } else emptyList()
                        planCalls.forEach { toolCall ->
                            val planContext = if (currentContext.mode == AgentMode.PLAN) currentContext
                            else currentContext.copy(mode = AgentMode.PLAN)
                            val runResult = toolInvoker.runSync(toolRegistry.getTool(toolCall.name), toolCall, planContext)
                            runResultsById[toolCall.id] = runResult
                            if (!runResult.isError) {
                                planSubmittedForRequest = PlanModeExecutionGuard.isPlanSubmission(toolCall)
                                planSubmittedInCurrentBatch = planSubmittedForRequest
                            }
                        }

                        val pendingCalls = toolCalls.filterNot { it.id in runResultsById }
                        pendingCalls.forEach { toolCall ->
                            if (planEnforcedForBatch && !planSubmittedForRequest &&
                                PlanModeExecutionGuard.requiresPlan(toolRegistry.getTool(toolCall.name), toolCall.arguments)
                            ) {
                                runResultsById[toolCall.id] = ToolRunResult(
                                    ToolResult.Error(
                                        "PLAN 模式要求先成功提交本轮可见计划（todo 非空 items），再执行写入或命令操作。请先提交/更新计划后重试。",
                                        "PLAN_REQUIRED"
                                    ).toTransportString(),
                                    true
                                )
                            }
                        }
                        val executableCalls = pendingCalls.filterNot { it.id in runResultsById }

                        // 快照被体积/空间限制跳过时，原因按 callId 收集，稍后注入该工具的结果里，
                        // 让模型与界面都看到「未创建快照，无法回滚」，绝不静默跳过。
                        val snapshotSkipReasons = ConcurrentHashMap<String, String>()

                        if (executableCalls.isNotEmpty()) {
                            coroutineScope {
                                executableCalls.map { toolCall ->
                                    async {
                                        val tool = toolRegistry.getTool(toolCall.name)
                                        suspend fun invokeTool(): ToolRunResult = if (tool is StreamingAgentTool) {
                                            toolInvoker.runStream(tool, toolCall, currentContext) { send(it) }
                                        } else {
                                            toolInvoker.runSync(tool, toolCall, currentContext)
                                        }

                                        val path = if (toolCall.name == "editFile" || toolCall.name == "writeFile") {
                                            (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull
                                        } else null
                                        val checkpointSessionId = currentContext.checkpointSessionId ?: currentContext.sessionId
                                        if (path != null && checkpointSessionId != null) {
                                            checkpointManager.withFileModificationLock(path, currentContext.projectRoot) {
                                                val outcome = checkpointManager.beforeFileModified(
                                                    checkpointSessionId,
                                                    path,
                                                    currentContext.checkpointId
                                                )
                                                outcome.skipReason?.let { reason ->
                                                    FileLogger.w(TAG, "快照跳过(将注入工具结果): callId=${toolCall.id} path=$path reason=$reason")
                                                    snapshotSkipReasons[toolCall.id] = reason
                                                }
                                                invokeTool().also { result ->
                                                    if (!result.isError) {
                                                        checkpointManager.recordFileVersion(
                                                            checkpointSessionId,
                                                            path,
                                                            currentContext.checkpointId
                                                        )?.let { warning ->
                                                            snapshotSkipReasons[toolCall.id] = warning
                                                        }
                                                    }
                                                }
                                            }
                                        } else if (
                                            tool?.effectiveCapabilities(toolCall.arguments).orEmpty()
                                                .contains(ToolCapability.EXECUTE_COMMANDS)
                                        ) {
                                            // A shell can touch arbitrary paths, so serialize it with file tools
                                            // on the workspace lock and record versions before releasing the lock.
                                            checkpointManager.withWorkspaceModificationLock(currentContext.projectRoot) {
                                                invokeTool().also {
                                                    checkpointSessionId?.let { sessionId ->
                                                        checkpointManager.recordWorkspaceVersions(
                                                            currentContext.checkpointSessionId ?: sessionId,
                                                            currentContext.projectRoot,
                                                            currentContext.checkpointId
                                                        )?.let { warning -> snapshotSkipReasons[toolCall.id] = warning }
                                                    }
                                                }
                                            }
                                        } else {
                                            invokeTool()
                                        }
                                    }
                                }.awaitAll().forEachIndexed { index, result ->
                                    runResultsById[executableCalls[index].id] = result
                                }
                            }
                        }

                        // 串行处理 mode 切换并组装批量结果。
                        val batchResults = mutableListOf<ToolBatchResult>()
                        toolCalls.forEachIndexed { index, toolCall ->
                            val runResult = runResultsById[toolCall.id]
                                ?: ToolRunResult(ToolResult.Error("工具未执行", "TOOL_NOT_EXECUTED").toTransportString(), true)
                            var rawResult = runResult.raw
                            snapshotSkipReasons[toolCall.id]?.let { reason ->
                                rawResult = withSnapshotSkipWarning(rawResult, reason)
                            }
                            var isError = runResult.isError
                            val (newCtx, updated) = checkAndUpdateMode(toolCall, isError, currentContext)
                            if (updated) {
                                val reason = (toolCall.arguments["reason"] as? JsonPrimitive)?.content?.trim()
                                    ?: toolCall.arguments["reason"]?.toString()?.replace("\"", "")?.trim()
                                    ?: ""
                                send(AgentEvent.ModeChanged(newCtx.mode, reason))

                                // PLAN 退出只恢复进入前的模式；执行是否需要授权仍由当前权限挡位决定，
                                // 不再额外挂起等待「批准计划」面板。
                                currentContext = newCtx
                                if (newCtx.mode == AgentMode.PLAN && !planSubmittedInCurrentBatch) {
                                    planSubmittedForRequest = false
                                }
                                rawResult += buildModeSwitchNotice(newCtx.mode)
                            }
                            batchResults.add(ToolBatchResult(toolCall.id, toolCall.name, rawResult, isError, runResult.attachments, runResult.images))
                        }

                        // 本轮内到达的后台任务/子代理完成通知：搭在本批最后一条工具结果上立即送达，
                        // AI 当轮即可感知，不必等本轮结束再起新一轮。ack 放到完成事件发出（结果已落库）之后：
                        // 中途被取消时通知仍留在队列里，由后续批次或本轮结束的兜底路径送达。
                        val notifySessionId = currentContext.sessionId
                        val notifications = if (notifySessionId != null && batchResults.isNotEmpty()) {
                            agentNotificationCenter.peek(notifySessionId)
                        } else {
                            emptyList()
                        }
                        if (notifications.isNotEmpty()) {
                            val modeChange = notifications.lastOrNull { it.kind == AgentNotificationKind.MODE_CHANGE }
                            val newMode = modeChange?.newMode
                            if (newMode != null) {
                                // 用户在工作期间切换模式：本轮后续批次的权限判定立即改用新模式。
                                // 与 SessionUseCase.updateMode 保持同一套语义：进入 PLAN 记下进入前的模式，其余情况清空。
                                val prevMode = currentContext.mode
                                currentContext = if (newMode == AgentMode.PLAN) {
                                    currentContext.copy(
                                        mode = AgentMode.PLAN,
                                        modeBeforePlan = if (prevMode != AgentMode.PLAN) prevMode else currentContext.modeBeforePlan
                                    )
                                } else {
                                    currentContext.copy(mode = newMode, modeBeforePlan = null)
                                }
                                if (newMode == AgentMode.PLAN && prevMode != AgentMode.PLAN) {
                                    planSubmittedForRequest = false
                                }
                            }
                            val last = batchResults.last()
                            var injected = eventInjector.inject(last.result, notifications)
                            if (newMode != null) {
                                // 模式约束提示随工具结果落库，留在历史里供后续轮沿用。
                                injected += buildExternalModeSwitchNotice(newMode)
                            }
                            batchResults[batchResults.lastIndex] = last.copy(result = injected)
                        }

                        // 逐个推送完成事件（保持与 batchToolCalls 一致顺序），并进入收尾。
                        batchResults.forEach { br ->
                            val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
                            send(AgentEvent.ToolCallFinished(br.id, br.toolName, br.result, br.isError,
                                attachments = br.attachments, persisted = persisted))
                            persisted.await()
                        }
                        if (notifySessionId != null && notifications.isNotEmpty()) {
                            agentNotificationCenter.ack(notifySessionId, notifications.map { it.seq })
                        }
                        actionQueue.addLast(AgentAction.ToolBatchFinished(batchResults))
                    }
                }
            }
        }
        
        state.error?.let { send(AgentEvent.Failed(it, state.errorCode)) }
        send(AgentEvent.Completed)
    }

    private fun isContextOverflow(error: Throwable): Boolean {
        if (error is StreamApiException && error.code == "context_window_exceeded") return true
        val text = error.message.orEmpty().lowercase()
        return listOf("context_length_exceeded", "context_window_exceeded", "maximum context length",
            "prompt is too long", "input token limit").any { it in text }
    }

    private suspend fun activeModelSupportsVision(sessionId: String?): Boolean {
        val config = resolveProviderConfig(sessionId) ?: return false
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        return metadata.supportsVision
    }

    /**
     * 发送前按模型视觉能力处理消息中的图片：
     * - 支持 vision：原样返回。
     * - 不支持：剥离所有图片（仅影响本次发送，不动持久化数据），历史/输入中的图片不会原样发给
     *   非多模态模型导致请求失败；切回多模态模型后图片上下文仍可正常使用。
     */
    private fun sanitizeImagesForModel(
        messages: List<AgentMessage>,
        supportsVision: Boolean
    ): List<AgentMessage> {
        if (supportsVision) return messages
        return messages.map { msg ->
            when (msg) {
                is AgentMessage.UserMessage ->
                    if (msg.images.isEmpty()) msg
                    else msg.copy(images = emptyList(), content = msg.content.ifBlank { "（图片已省略：当前模型不支持图片输入）" })
                is AgentMessage.ToolResultMessage ->
                    if (msg.images.isEmpty()) msg else msg.copy(images = emptyList())
                is AgentMessage.AssistantMessage -> msg
            }
        }
    }

    /**
     * 每轮注入「相关技能」提醒（对标 Claude Code 的 "Skills relevant to your task:"）：
     * 系统提示里的技能清单按会话只注入一次，模型跑几轮工具后就淡忘了；在本轮请求发出前，
     * 按最新 user 消息把最相关的几个技能连描述再推到他眼前，提高 `loadSkill` 调用率。
     *
     * 只追加到本轮最新 user 消息末尾、不落库：系统提示与历史消息逐字节不变，
     * provider 前缀缓存照常命中（KV Cache 友好）。
     * 仅主代理注入：子代理的工具白名单可能不含 loadSkill，其提示词也未必注入了技能清单。
     * 首次技能扫描已在系统提示构建时完成并缓存，这里正常情况下是纯内存计算；
     * 防御性切 IO 线程，避免真机 ROOT 通道下的阻塞 IO 卡住主线程。
     */
    private suspend fun withSkillHint(
        messages: List<AgentMessage>,
        ctx: AgentContext,
        tools: List<AgentTool>
    ): List<AgentMessage> {
        if (ctx.agentDefinition != null) return messages
        if (tools.none { it.name == "loadSkill" }) return messages
        val lastUserIndex = messages.indexOfLast { it is AgentMessage.UserMessage }
        if (lastUserIndex < 0) return messages
        val lastUser = messages[lastUserIndex] as AgentMessage.UserMessage
        if (lastUser.content.isBlank()) return messages
        val hint = withContext(Dispatchers.IO) {
            runCatching { promptProvider.buildSkillHint(ctx, lastUser.content) }.getOrNull()
        } ?: return messages
        return messages.toMutableList().apply {
            this[lastUserIndex] = lastUser.copy(content = lastUser.content.trimEnd() + "\n\n" + hint)
        }
    }

    /**
     * 压缩轮专用 provider 解析。若用户配置了压缩专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveCompactionFallbackProvider(sessionId: String? = null): AIProvider? {
        val providerId = compactionModelSettingsRepository.getCompactionProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = compactionModelSettingsRepository.getCompactionModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * 标题生成专用 provider 解析。若用户配置了标题总结专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveTitleFallbackProvider(sessionId: String?): AIProvider? {
        val providerId = titleModelSettingsRepository.getTitleProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = titleModelSettingsRepository.getTitleModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * AI 命令审查器专用 provider 解析。若用户配置了审查器专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveReviewerFallbackProvider(sessionId: String?): AIProvider? {
        val providerId = reviewerModelSettingsRepository.getReviewerProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = reviewerModelSettingsRepository.getReviewerModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /** AI 命令审查结论：允许、拒绝或交给人工确认。 */
    private sealed interface AiReviewResult {
        data class Allow(val reason: String) : AiReviewResult
        data class Deny(val reason: String) : AiReviewResult
        data class Ask(val reason: String) : AiReviewResult
    }

    /**
     * 「自主执行」挡位下的命令独立评审：默认跟随当前聊天模型，配置了审查器专用模型则用之。
     * 提示词来自 [SystemPromptProvider] 的 `agent/command-reviewer.md`。
     * DENY 返回结构化拒绝给 agent；UNSURE、超时和异常回退人工确认。
     */
    private suspend fun reviewCommandWithAi(
        command: String,
        intent: String?,
        userRequest: String,
        history: List<AgentMessage>,
        requestId: String,
        workingDirectory: String,
        authLevel: CommandAuthLevel,
        toolName: String,
        environment: String,
        sessionId: String?
    ): AiReviewResult {
        if (intent.isNullOrBlank()) {
            return AiReviewResult.Ask("Agent 未提供执行意图，无法核实该命令与当前任务是否一致。请补充原因后再审查。")
        }
        val reviewerProvider = try {
            resolveReviewerFallbackProvider(sessionId) ?: getEffectiveProvider(sessionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FileLogger.w(TAG, "无法准备 AI 命令审查模型，回退人工确认", error)
            return AiReviewResult.Ask("AI 命令审查模型暂时不可用，已转为人工确认。")
        }
        val promptAndContext = try {
            val prompt = promptProvider.resolvePrompt(COMMAND_REVIEWER_FILE)
                .replace(LEADING_COMMENT, "") + reviewerLanguageInstruction()
            prompt to reviewerHistoryContext(history)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FileLogger.w(TAG, "无法准备 AI 命令审查上下文，回退人工确认", error)
            return AiReviewResult.Ask("AI 命令审查上下文暂时不可用，已转为人工确认。")
        }
        val (reviewerPrompt, recentBackground) = promptAndContext
        val cacheKey = CommandReviewCacheKey(
            sessionId = sessionId.orEmpty(),
            requestId = requestId,
            toolName = toolName,
            normalizedCommand = normalizeCommandForReview(command).take(5_000),
            declaredIntent = intent.trim().take(600),
            latestUserRequest = userRequest.trim().take(3_000),
            workingDirectory = workingDirectory.trim().replace('\\', '/').trimEnd('/'),
            authLevel = authLevel,
            environment = environment,
            reviewerProviderId = reviewerProvider.providerId,
            reviewerModel = reviewerProvider.model,
            contextFingerprint = commandReviewContextFingerprint(reviewerPrompt, recentBackground)
        )
        val cached = commandReviewCache[cacheKey]
        if (cached != null) return cached

        val timeoutMs = try {
            policyEngine.commandReviewTimeoutMillis()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            FileLogger.w(TAG, "无法读取 AI 命令审查超时设置，回退人工确认", error)
            return AiReviewResult.Ask("AI 命令审查设置暂时不可用，已转为人工确认。")
        }
        val result = reviewCommandWithAiUncached(
            command = command,
            intent = intent,
            userRequest = userRequest,
            workingDirectory = workingDirectory,
            authLevel = authLevel,
            toolName = toolName,
            environment = environment,
            sessionId = sessionId,
            reviewerProvider = reviewerProvider,
            reviewerPrompt = reviewerPrompt,
            recentBackground = recentBackground,
            timeoutMs = timeoutMs
        )
        if (result is AiReviewResult.Allow || result is AiReviewResult.Deny) {
            commandReviewCache[cacheKey] = result
        }
        return result
    }

    private suspend fun reviewCommandWithAiUncached(
        command: String,
        intent: String?,
        userRequest: String,
        workingDirectory: String,
        authLevel: CommandAuthLevel,
        toolName: String,
        environment: String,
        sessionId: String?,
        reviewerProvider: AIProvider,
        reviewerPrompt: String,
        recentBackground: String,
        timeoutMs: Long
    ): AiReviewResult {
        return try {
            val pathContext = reviewPathContext(command, workingDirectory)
            val reversibility = when {
                Regex("\\b(?:rm|rmdir|del|unlink|clean|reset|push|upload|delete)\\b", RegexOption.IGNORE_CASE).containsMatchIn(command) ->
                    "可能删除数据、改写历史或向外部发送数据；需要结合具体目标判断可逆性。"
                Regex("\\b(?:build|test|check|lint|vet)\\b", RegexOption.IGNORE_CASE).containsMatchIn(command) ->
                    "看起来像可重复的验证/构建操作，但仍需核对脚本和输出路径。"
                else -> "由命令和上下文判断；无法确认时选择 UNSURE。"
            }
            val reviewInput = JsonObject(
                mapOf(
                    "latest_user_request" to JsonPrimitive(userRequest.take(3000)),
                    "recent_conversation_background" to JsonPrimitive(recentBackground),
                    "agent_declared_intent" to JsonPrimitive(intent?.trim()?.take(600).orEmpty()),
                    "command" to JsonPrimitive(command.take(5000)),
                    "working_directory" to JsonPrimitive(workingDirectory.take(1200)),
                    "path_context" to JsonPrimitive(pathContext),
                    "reversibility_assessment" to JsonPrimitive(reversibility),
                    "authorization_level" to JsonPrimitive(authLevel.name),
                    "tool" to JsonPrimitive(toolName),
                    "execution_environment" to JsonPrimitive(environment)
                )
            ).toString()
            val callStartWall = System.currentTimeMillis()
            val callStartElapsed = SystemClock.elapsedRealtime()
            var callCompleted = false
            var callError: String? = null
            var usage: AIResponse? = null
            val response = try {
                val resp = withTimeout(timeoutMs) {
                    reviewerProvider.complete(
                        systemPrompt = reviewerPrompt,
                        messages = listOf(AgentMessage.UserMessage(content = reviewInput)),
                        tools = emptyList()
                    )
                }
                usage = resp
                callCompleted = true
                resp
            } catch (e: TimeoutCancellationException) {
                callError = "command reviewer timeout after ${timeoutMs}ms"
                throw e
            } catch (e: CancellationException) {
                callError = "cancelled"
                throw e
            } catch (e: Exception) {
                callError = e.message ?: e.javaClass.simpleName
                throw e
            } finally {
                val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
                // 同 executeEvents：取消展开期间不调 suspend，派发到不可取消 scope 落库。
                val record = LlmCallRecordEntity(
                    sessionId = sessionId,
                    providerId = reviewerProvider.providerId.ifBlank { null },
                    model = reviewerProvider.model,
                    kind = "command_review",
                    inputTokens = usage?.inputTokens ?: 0,
                    outputTokens = usage?.outputTokens ?: 0,
                    cachedInputTokens = usage?.cachedInputTokens ?: 0,
                    cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                    ttfbMillis = null,
                    durationMillis = durationMillis,
                    status = if (callCompleted) "success" else "error",
                    errorMessage = callError,
                    stopReason = usage?.stopReason,
                    createdAt = callStartWall
                )
                recordScope.launch { runCatching { llmCallRecordDao.insert(record) } }
            }
            parseReviewVerdict(response.content)
        } catch (e: TimeoutCancellationException) {
            FileLogger.w(TAG, "AI 命令审查超时，回退人工确认")
            AiReviewResult.Ask("AI 命令审查超过 ${timeoutMs / 1000} 秒，已转为人工确认。")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "AI 命令审查失败，回退人工确认", e)
            AiReviewResult.Ask("AI 命令审查暂时不可用，已转为人工确认。")
        }
    }

    private fun reviewerHistoryContext(history: List<AgentMessage>): String =
        history.takeLast(6).asSequence().mapNotNull { message ->
            when (message) {
                is AgentMessage.UserMessage -> "user: ${message.content.take(1200)}"
                is AgentMessage.AssistantMessage -> message.content.takeIf { it.isNotBlank() }
                    ?.let { "assistant: ${it.take(1200)}" }
                is AgentMessage.ToolResultMessage -> {
                    val result = (message.modelResult ?: message.result).take(1200)
                    "tool(${message.toolName}): $result"
                }
            }
        }.joinToString("\n").take(5_000)

    /** Cache identity includes the actual review instructions and relevant history without retaining either. */
    private fun commandReviewContextFingerprint(prompt: String, background: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest("$prompt\u0000$background".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    /** 只折叠引号外的空白，避免不同引号内参数因缓存归一化而被误认为同一命令。 */
    private fun normalizeCommandForReview(command: String): String {
        val normalized = StringBuilder(command.length)
        var quote: Char? = null
        var escaped = false
        var pendingWhitespace = false
        for (char in command.trim()) {
            if (escaped) {
                if (pendingWhitespace) normalized.append(' ')
                pendingWhitespace = false
                normalized.append(char)
                escaped = false
                continue
            }
            if (char == '\\' && quote != '\'') {
                if (pendingWhitespace) normalized.append(' ')
                pendingWhitespace = false
                normalized.append(char)
                escaped = true
                continue
            }
            if (quote != null) {
                normalized.append(char)
                if (char == quote) quote = null
                continue
            }
            if (char == '\'' || char == '"') {
                if (pendingWhitespace) normalized.append(' ')
                pendingWhitespace = false
                quote = char
                normalized.append(char)
            } else if (char.isWhitespace()) {
                pendingWhitespace = true
            } else {
                if (pendingWhitespace) normalized.append(' ')
                pendingWhitespace = false
                normalized.append(char)
            }
        }
        return normalized.toString()
    }

    private fun reviewPathContext(command: String, workingDirectory: String): String {
        val candidates = com.aicode.feature.agent.domain.permission.ShellCommandParser
            .analyze(command).segments.flatten()
            .filter { token -> token.startsWith('/') || token.startsWith("~/") || token.startsWith("../") || token.contains('/') }
            .distinct()
            .take(20)
        if (candidates.isEmpty()) return "No explicit path token found; relative paths use the stated working directory."
        val workspace = normalizeReviewPath(workingDirectory)
        return candidates.joinToString("\n") { path ->
            val resolved = when {
                path.startsWith("~/") -> normalizeReviewPath("/root/${path.drop(2)}")
                path.startsWith('/') -> normalizeReviewPath(path)
                else -> normalizeReviewPath("$workingDirectory/$path")
            }
            val relation = if (resolved == workspace || resolved.startsWith("$workspace/")) "inside workspace" else "outside/uncertain"
            "$path -> $relation"
        }
    }

    private fun normalizeReviewPath(path: String): String {
        val parts = ArrayDeque<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return "/" + parts.joinToString("/")
    }

    /**
     * 审查器警告语言的追加指令：REASON 是给点「拒绝/存疑」弹窗的人看的，
     * 必须跟随应用界面语言（如中文界面就写中文），否则英文警告对用户形同虚设。
     * 只约束 REASON 的自然语言部分，VERDICT/ALLOW/DENY/UNSURE 等机器解析关键字仍固定英文。
     */
    private suspend fun reviewerLanguageInstruction(): String {
        val tag = languageSettingsRepository.snapshot() ?: Locale.getDefault().toLanguageTag()
        val name = when (tag.substringBefore('-').lowercase()) {
            "zh" -> "Simplified Chinese (简体中文)"
            "en" -> "English"
            "ja" -> "Japanese"
            "ko" -> "Korean"
            "ru" -> "Russian"
            "fr" -> "French"
            "de" -> "German"
            "es" -> "Spanish"
            else -> null
        }
        val lang = name ?: "the language identified by BCP-47 tag \"$tag\""
        return "\n\n<language>\nThe human approver reads $lang. " +
            "Write the REASON line in $lang. " +
            "Keep the literal keywords \"VERDICT:\", \"ALLOW\", \"DENY\", \"UNSURE\", \"REASON:\" in English exactly as specified.\n</language>"
    }

    /** 严格解析审查器输出；多余前后文、缺少理由或未知结论一律转人工确认。 */
    private fun parseReviewVerdict(content: String): AiReviewResult {
        val decision = CommandReviewProtocol.parse(content)
            ?: return AiReviewResult.Ask("AI 审查器返回格式无效或缺少理由，已转为人工确认。")
        return when (decision.verdict) {
            CommandReviewProtocol.Verdict.ALLOW -> AiReviewResult.Allow(decision.reason)
            CommandReviewProtocol.Verdict.DENY -> AiReviewResult.Deny(decision.reason)
            CommandReviewProtocol.Verdict.UNSURE -> AiReviewResult.Ask(decision.reason)
        }
    }

    /**
     * 为新建会话生成标题：默认跟随当前聊天模型，配置了标题总结专用模型则用之。
     * 提示词来自 [SystemPromptProvider] 的 `agent/title-generator.md`。
     * 生成失败或取不到标题时返回 null（调用方保留临时标题）。
     */
    override suspend fun generateTitle(sessionId: String, request: String): String? = runCatching {
        val provider = resolveTitleFallbackProvider(sessionId) ?: getEffectiveProvider(sessionId)
        val prompt = promptProvider.resolvePrompt(TITLE_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val callStartWall = System.currentTimeMillis()
        val callStartElapsed = SystemClock.elapsedRealtime()
        var callCompleted = false
        var callError: String? = null
        var usage: AIResponse? = null
        val response = try {
            val resp = provider.complete(
                systemPrompt = prompt,
                messages = listOf(AgentMessage.UserMessage(content = request)),
                tools = emptyList()
            )
            usage = resp
            callCompleted = true
            resp
        } catch (e: CancellationException) {
            callError = "cancelled"
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
            // 同 executeEvents：取消展开期间不调 suspend，派发到不可取消 scope 落库。
            val record = LlmCallRecordEntity(
                sessionId = sessionId,
                providerId = provider.providerId.ifBlank { null },
                model = provider.model,
                kind = "title",
                inputTokens = usage?.inputTokens ?: 0,
                outputTokens = usage?.outputTokens ?: 0,
                cachedInputTokens = usage?.cachedInputTokens ?: 0,
                cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                ttfbMillis = null,
                durationMillis = durationMillis,
                status = if (callCompleted) "success" else "error",
                errorMessage = callError,
                stopReason = usage?.stopReason,
                createdAt = callStartWall
            )
            recordScope.launch { runCatching { llmCallRecordDao.insert(record) } }
        }
        response.content.trim().take(TITLE_MAX_CHARS).ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "生成会话标题失败", e)
    }.getOrNull()

    override suspend fun generateCommitMessage(diff: String): String? = runCatching {
        if (diff.isBlank()) return@runCatching null
        val provider = getEffectiveProvider(sessionId = null)
        val prompt = promptProvider.resolvePrompt(COMMIT_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val truncatedDiff = diff.take(12000)
        val resp = provider.complete(
            systemPrompt = prompt,
            messages = listOf(AgentMessage.UserMessage(content = "git diff:\n```diff\n$truncatedDiff\n```")),
            tools = emptyList()
        )
        val line = resp.content.lines().firstOrNull { it.isNotBlank() }?.trim()
            ?.removeSurrounding("`")
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.trim()
        line?.take(100)?.ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "生成提交信息失败", e)
    }.getOrNull()

    /**
     * 把模型直出的图片（base64）落盘到 `~/.aicode/generated-images/`，返回带容器路径的 images
     * 与一一对应的 UI 附件（附件只带路径不含 base64，落库不撑爆数据库行）。
     */
    private suspend fun persistModelImages(images: List<AgentImage>): Pair<List<AgentImage>, List<AgentAttachment>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val persisted = mutableListOf<AgentImage>()
            val attachments = mutableListOf<AgentAttachment>()
            images.forEach { image ->
                runCatching {
                    val estimatedBytes = image.base64Data.length.toLong() * 3 / 4
                    if (estimatedBytes > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val bytes = Base64.decode(image.base64Data, Base64.DEFAULT)
                    if (bytes.isEmpty() || bytes.size > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val unique = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
                    val targetPath = "$GENERATED_IMAGE_DIR/gen_$unique.${extForMime(image.mimeType)}"
                    fileAccess.writeBytes(targetPath, bytes, overwrite = false)
                    val displayPath = fileAccess.toDisplayPath(targetPath)
                    val localFile = fileAccess.copyToLocal(targetPath)
                    persisted.add(image.copy(path = displayPath))
                    attachments.add(
                        AgentAttachment(
                            fileName = targetPath.substringAfterLast('/'),
                            containerPath = displayPath,
                            localPath = localFile.absolutePath,
                            mimeType = image.mimeType,
                            sizeBytes = bytes.size.toLong(),
                            isImage = true
                        )
                    )
                }
            }
            persisted to attachments
        }

    private fun extForMime(mime: String): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    private fun checkAndUpdateMode(toolCall: ToolCall, isError: Boolean, currentContext: AgentContext): Pair<AgentContext, Boolean> {
        if (toolCall.name == "planMode" && !isError) {
            val action = (toolCall.arguments["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
                ?: toolCall.arguments["action"]?.toString()?.replace("\"", "")?.trim()?.lowercase()
            when (action) {
                // 进入 PLAN 时记住当前模式；退出时恢复到它（BUILD/ASK→PLAN 的规划往返结束后回到原模式而非 BUILD）。
                "enter" -> if (currentContext.mode != AgentMode.PLAN) {
                    return currentContext.copy(mode = AgentMode.PLAN, modeBeforePlan = currentContext.mode) to true
                }
                "exit" -> if (currentContext.mode == AgentMode.PLAN) {
                    return currentContext.copy(mode = currentContext.modeBeforePlan ?: AgentMode.BUILD) to true
                }
            }
        }
        return currentContext to false
    }

    /**
     * 当前模式提醒：随最新用户消息注入（借鉴 opencode SessionReminders 的思路）。
     * 模式提示词不进 system——一旦切换就要重建 system、打断前缀缓存；
     * 改为消息级提醒：每次用户请求拼在最新用户消息末尾，位置在消息流尾部，前缀保持稳定。
     */
    private fun buildModeReminder(mode: AgentMode): String? = when (mode) {
        AgentMode.PLAN -> promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "【模式提醒】$it" }
        AgentMode.ASK -> promptProvider.resolvePrompt(MODE_REMINDER_ASK_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "【模式提醒】$it" }
        AgentMode.BUILD -> null
    }

    /**
     * 把待送通知注入工具结果：优先作为 transport JSON 顶层的 `notifications` 字段，结果仍是合法 JSON，
     * UI 的 formatToolResult（只读 data/message）与各类结构化解析不受影响。
     * raw 已被模式切换提示等纯文本追加过、不再是合法 JSON 时，退化为文本追加。
     */
    /**
     * 取本次应注入的模式提醒：仅当模式与上次注入时不同（含首次）才返回文本并记录，否则为 null。
     */
    private fun takeModeReminderIfChanged(sessionId: String?, mode: AgentMode): String? {
        if (sessionId == null) return buildModeReminder(mode)
        if (lastInjectedMode[sessionId] == mode) return null
        lastInjectedMode[sessionId] = mode
        return buildModeReminder(mode)
    }

    /** 工具调用成功后拼进 planMode 工具结果的模式状态通知（当轮即可见，无需等下一条用户消息）。 */
    private fun buildModeSwitchNotice(mode: AgentMode): String = when (mode) {
        AgentMode.PLAN -> "\n\n" + promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
        AgentMode.BUILD -> "\n\n【模式切换】你已切换到 BUILD（构建）模式，可以继续处理用户请求。"
        AgentMode.ASK -> "\n\n【模式切换】你已恢复到 ASK（提问）模式：保持只读，仅围绕用户后续提问作答，不要实施变更。"
    }

    /** 用户在外部（界面）手动切换模式时拼进工具结果的提示；与 AI 自切的 [buildModeSwitchNotice] 区分，避免误称「计划已获批准」。 */
    private fun buildExternalModeSwitchNotice(mode: AgentMode): String = when (mode) {
        AgentMode.PLAN -> "\n\n" + promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
        AgentMode.BUILD -> "\n\n【模式切换】用户已将模式切换为 BUILD（构建）模式，可以正常执行写操作。"
        AgentMode.ASK -> "\n\n【模式切换】用户已将模式切换为 ASK（提问）模式：保持只读，仅作答与探索，不要实施变更。"
    }

    private suspend fun requestPermissionIfNeeded(
        tool: AgentTool?,
        callId: String,
        arguments: Map<String, JsonElement>,
        argsPreview: String,
        mode: AgentMode,
        sessionId: String?,
        userRequest: String,
        context: AgentContext
    ): PermissionCheckResult {
        if (tool == null) {
            return PermissionCheckResult(true)
        }

        val eval = policyEngine.evaluate(tool, tool.name, arguments, mode, context.projectRoot, context)
        if (eval.verdict == ToolPermissionPolicyEngine.Verdict.DENY) {
            val reason = eval.denyReason ?: "该工具被项目安全规则策略禁止执行"
            val code = if (mode == AgentMode.ASK) "ASK_MODE_REJECTED" else "SYSTEM_DENIED"
            return PermissionCheckResult(false, reason, code)
        }

        // A tool-level AUTO_APPROVE default must never bypass the deletion-specific approval or
        // catastrophic-path deny returned by the policy engine.
        if (tool.permissionPolicy == ToolPermissionPolicy.AUTO_APPROVE && eval.deletionAnalysis?.isDeletion != true) {
            return PermissionCheckResult(true)
        }

        return when (eval.verdict) {
            ToolPermissionPolicyEngine.Verdict.ALLOW -> PermissionCheckResult(true, reviewRecord = eval.reviewRecord)
            ToolPermissionPolicyEngine.Verdict.DENY -> PermissionCheckResult(false)
            ToolPermissionPolicyEngine.Verdict.ASK -> {
                eval.deletionAnalysis?.takeIf { it.isDeletion }?.let { deletion ->
                    return requestDeletionApproval(
                        tool = tool,
                        callId = callId,
                        arguments = arguments,
                        argsPreview = argsPreview,
                        analysis = deletion,
                        userRequest = userRequest,
                        context = context,
                        sessionId = sessionId
                    )
                }
                // 「自主执行」挡位：先交 AI 命令审查器独立评审，通过直接放行；
                // DENY 返回给 agent，UNSURE/失败回退人工弹窗。
                val permissionCommand = ((arguments["command"] ?: arguments["input"]) as? JsonPrimitive)?.contentOrNull
                var aiReviewNote: String? = null
                if (eval.aiReviewable) {
                    val command = ((arguments["command"] ?: arguments["input"]) as? JsonPrimitive)?.contentOrNull
                    if (command != null) {
                        val intent = (arguments["intent"] as? JsonPrimitive)?.contentOrNull
                        val authLevel = policyEngine.currentCommandAuthLevel()
                        val environment = (arguments["env"] as? JsonPrimitive)?.contentOrNull
                            ?: (arguments["action"] as? JsonPrimitive)?.contentOrNull
                            ?: "auto"
                        when (val review = reviewCommandWithAi(
                            command = command,
                            intent = intent,
                            userRequest = userRequest,
                            history = context.history,
                            requestId = context.inputMessageId,
                            workingDirectory = context.projectRoot.ifBlank { "/root/workspace" },
                            authLevel = authLevel,
                            toolName = tool.name,
                            environment = environment,
                            sessionId = sessionId
                        )) {
                            is AiReviewResult.Allow -> {
                                return PermissionCheckResult(true, reviewRecord = "AI 审查通过：${review.reason}")
                            }
                            is AiReviewResult.Deny -> return PermissionCheckResult(false, review.reason, "AI_REVIEW_DENIED")
                            is AiReviewResult.Ask -> aiReviewNote = review.reason
                        }
                    }
                }
                val base = tool.buildPermissionRequest(callId, arguments, argsPreview)
                val intent = (arguments["intent"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(600)
                val riskReason = eval.rememberDisabledReason?.takeIf { !eval.aiReviewable }
                val details = buildString {
                    append(base.details)
                    if (!permissionCommand.isNullOrBlank()) append("\n\n完整命令：\n").append(permissionCommand)
                    if (!intent.isNullOrBlank()) append("\nAgent 声明意图：").append(intent)
                    if (!riskReason.isNullOrBlank()) append("\n风险提示：").append(riskReason)
                }
                val request = base.copy(
                    title = eval.askTitle ?: base.title,
                    details = details,
                    rememberablePatterns = eval.rememberablePatterns,
                    rememberDisabledReason = eval.rememberDisabledReason,
                    sessionId = sessionId.orEmpty(),
                    reviewNote = aiReviewNote,
                    sourceLabel = context.sourceLabel
                )
                when (permissionManager.awaitApproval(request)) {
                    PermissionChoice.REJECT -> PermissionCheckResult(false, "用户拒绝执行该工具", "USER_REJECTED")
                    PermissionChoice.ONCE -> PermissionCheckResult(true)
                    PermissionChoice.ALWAYS -> {
                        if (eval.rememberablePatterns.isNotEmpty()) {
                            policyEngine.remember(tool.name, eval.rememberablePatterns, PermissionScope.PROJECT)
                        }
                        PermissionCheckResult(true)
                    }
                    PermissionChoice.REVIEW -> PermissionCheckResult(
                        false,
                        "AI 审查选项只适用于删除命令授权",
                        "INVALID_PERMISSION_CHOICE"
                    )
                }
            }
        }
    }

    private suspend fun requestDeletionApproval(
        tool: AgentTool,
        callId: String,
        arguments: Map<String, JsonElement>,
        argsPreview: String,
        analysis: CommandDeletionAnalyzer.Analysis,
        userRequest: String,
        context: AgentContext,
        sessionId: String?
    ): PermissionCheckResult {
        val command = ((arguments["command"] ?: arguments["input"]) as? JsonPrimitive)?.contentOrNull
        val intent = (arguments["intent"] as? JsonPrimitive)?.contentOrNull?.trim()?.take(600)
        val base = tool.buildPermissionRequest(callId, arguments, argsPreview)
        val details = buildString {
            append(base.details)
            if (!command.isNullOrBlank()) append("\n\n完整命令：\n").append(command)
            if (!intent.isNullOrBlank()) append("\nAgent 声明意图：").append(intent)
            append("\n\n删除目标（共 ").append(analysis.targets.size).append(" 项）：")
            if (analysis.targets.isEmpty()) append("\n- 静态解析无法确定目标，按高风险处理")
            analysis.targets.forEach { target ->
                append("\n- ").append(target.path)
                if (target.outsideWorkspace) append(" [工作区外/无法确认]")
                else append(" [工作区内]")
                if (target.wildcard) append(" [通配符]")
            }
            if (!analysis.parseCertain) append("\n注意：删除目标无法被完整静态解析，实际影响范围可能更大。")
            if (analysis.riskLabels.isNotEmpty()) append("\n风险标签：").append(analysis.riskLabels.joinToString("、"))
            val estimate = estimateDeletionImpact(analysis, context.projectRoot.ifBlank { "/root/workspace" })
            if (estimate.isNotBlank()) append("\n影响估算：").append(estimate)
        }
        val request = base.copy(
            title = "确认删除操作",
            summary = base.summary.ifBlank { command ?: "删除操作" },
            details = details,
            rememberablePatterns = emptyList(),
            rememberDisabledReason = "删除授权仅对本次命令有效，不会记入始终允许规则。",
            sessionId = sessionId.orEmpty(),
            deletionReview = true,
            sourceLabel = context.sourceLabel
        )
        return when (permissionManager.awaitApproval(request)) {
            PermissionChoice.REJECT -> PermissionCheckResult(false, "用户拒绝删除操作", "USER_REJECTED")
            PermissionChoice.ONCE -> PermissionCheckResult(true)
            PermissionChoice.ALWAYS -> PermissionCheckResult(
                false,
                "删除命令不支持始终允许；请重新请求并选择单次放行",
                "INVALID_PERMISSION_CHOICE"
            )
            PermissionChoice.REVIEW -> {
                val review = if (command.isNullOrBlank()) {
                    AiReviewResult.Ask("无法提取完整命令，AI 审查未能判断")
                } else {
                    reviewCommandWithAi(
                        command = command,
                        intent = intent,
                        userRequest = userRequest,
                        history = context.history,
                        requestId = context.inputMessageId,
                        workingDirectory = context.projectRoot.ifBlank { "/root/workspace" },
                        authLevel = policyEngine.currentCommandAuthLevel(),
                        toolName = tool.name,
                        environment = (arguments["env"] as? JsonPrimitive)?.contentOrNull ?: "auto",
                        sessionId = sessionId
                    )
                }
                when (review) {
                    is AiReviewResult.Allow -> PermissionCheckResult(
                        approved = true,
                        reviewRecord = "AI 删除审查通过：${review.reason}"
                    )
                    is AiReviewResult.Deny, is AiReviewResult.Ask -> {
                        val reason = when (review) {
                            is AiReviewResult.Deny -> "AI 审查拒绝：${review.reason}"
                            is AiReviewResult.Ask -> "AI 无法确认安全性：${review.reason}"
                            is AiReviewResult.Allow -> error("unreachable")
                        }
                        val secondChoice = permissionManager.awaitApproval(
                            request.copy(
                                deletionReviewCompleted = true,
                                reviewNote = reason,
                                rememberDisabledReason = "AI 未自动放行。你可以拒绝，或在核对完整目标后仍然单次放行。"
                            )
                        )
                        if (secondChoice == PermissionChoice.ONCE) PermissionCheckResult(true)
                        else PermissionCheckResult(false, "用户拒绝删除操作", "USER_REJECTED")
                    }
                }
            }
        }
    }

    private suspend fun estimateDeletionImpact(
        analysis: CommandDeletionAnalyzer.Analysis,
        workingDirectory: String
    ): String {
        val estimate = withTimeoutOrNull(700) {
            analysis.targets
                .filterNot { it.wildcard }
                .take(5)
                .map { target ->
                    val path = resolveDeletionPath(target.path, workingDirectory)
                    try {
                        when {
                            !fileAccess.exists(path) -> "${target.path}: 不存在或无法访问"
                            !fileAccess.isDirectory(path) -> "${target.path}: 文件 ${fileAccess.fileSize(path)} B"
                            else -> {
                                val children = fileAccess.listFiles(path).take(201)
                                val count = minOf(children.size, 200)
                                val byteCount = children.take(200).filterNot { it.isDirectory }.sumOf { it.size }
                                "${target.path}: 直接子项约 ${if (children.size > 200) "200+" else count} 个，已知文件约 ${byteCount} B"
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        "${target.path}: 大小估算不可用"
                    }
                }.toList().joinToString("；")
        }
        return estimate.orEmpty()
    }

    private fun resolveDeletionPath(path: String, workingDirectory: String): String {
        val expanded = when {
            path == "~" -> "/root"
            path.startsWith("~/") -> "/root/${path.drop(2)}"
            path.startsWith('/') -> path
            else -> "${workingDirectory.trimEnd('/')}/$path"
        }
        val parts = ArrayDeque<String>()
        expanded.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return "/" + parts.joinToString("/")
    }
}

/** 把自动审查放行的依据加入工具卡片参数 JSON，复用现有展开区域持久化展示。 */
private fun withCommandReviewRecord(argsPreview: String, record: String): String = runCatching {
    val original = Json.parseToJsonElement(argsPreview).jsonObject
    JsonObject(original.toMap() + ("_ai_review" to JsonPrimitive(record))).toString()
}.getOrElse { "$argsPreview\n_ai_review: $record" }

/**
 * 把「快照被跳过、无法回滚」的警告注入工具结果（仅 status=success）。
 *
 * error 结果的消息本身对模型可见，不需要注入；解析失败时原样返回——
 * 警告是附加信息，绝不能因为注入失败而弄坏工具结果本身。
 */
private fun withSnapshotSkipWarning(raw: String, reason: String): String = runCatching {
    val obj = Json.parseToJsonElement(raw).jsonObject
    if (obj["status"]?.jsonPrimitive?.contentOrNull != "success") return@runCatching raw
    val data = obj["data"]
    // data 可能是字符串（Bash/terminal 的 stdout）、数组或对象：
    // - 对象：warning 合并进 data（既有 UI 解析路径不变）；
    // - 其它（尤其字符串）：data 原样保留，warning 放同级顶层。
    // 曾经的 bug：无条件 `obj["data"] as? JsonObject ?: JsonObject(emptyMap())`，
    // 字符串 data 被替换成空对象 → Bash 的 stdout 整体消失，模型只看到 warning。
    val mergedData: JsonElement = if (data is JsonObject) {
        JsonObject(data.toMap() + ("warning" to JsonPrimitive(reason)))
    } else {
        data ?: JsonPrimitive(reason)
    }
    JsonObject(obj.toMap() + ("data" to mergedData) + ("warning" to JsonPrimitive(reason))).toString()
}.getOrDefault(raw)
