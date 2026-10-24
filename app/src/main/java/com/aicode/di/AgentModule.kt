package com.aicode.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.aicode.feature.agent.data.local.dao.AgentMessageDao
import com.aicode.feature.agent.data.local.dao.ChatSessionDao
import com.aicode.feature.agent.data.local.dao.CheckpointDao
import com.aicode.feature.agent.data.local.dao.LlmCallRecordDao
import com.aicode.feature.agent.data.local.dao.TodoItemDao
import com.aicode.feature.settings.data.local.dao.AIProviderDao
import com.aicode.feature.settings.domain.repository.AIProviderRepository
import com.aicode.feature.agent.data.local.database.AgentDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aicode.feature.agent.data.remote.anthropic.AnthropicApi
import com.aicode.feature.agent.data.remote.gemini.GeminiApi
import com.aicode.feature.agent.data.remote.openai.OpenAIApi
import com.aicode.feature.agent.domain.container.CommandEngine
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.container.DelegatingCommandEngine
import com.aicode.feature.agent.domain.container.DefaultRgEnvironment
import com.aicode.feature.agent.domain.container.LinuxContainerEngine
import com.aicode.feature.agent.domain.container.RemoteSshConnection
import com.aicode.feature.agent.domain.container.RemoteSshEngine
import com.aicode.feature.agent.domain.container.RgEnvironment
import com.aicode.feature.agent.domain.container.SharedPrefsSshHostKeyStore
import com.aicode.feature.agent.domain.container.SharedPrefsSshLoginKeyStore
import com.aicode.feature.agent.domain.container.SshHostKeyStore
import com.aicode.feature.agent.domain.container.SshLoginKeyStore
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import com.aicode.feature.agent.domain.tool.file.ReadFileTool
import com.aicode.feature.agent.domain.tool.file.SendFileTool
import com.aicode.feature.agent.domain.tool.file.GenerateImageTool
import com.aicode.feature.agent.domain.tool.file.ViewImageTool
import com.aicode.feature.agent.domain.tool.file.WriteFileTool
import com.aicode.feature.agent.domain.tool.editor.EditFileTool
import com.aicode.feature.agent.domain.tool.container.ExecuteCommandTool
import com.aicode.feature.agent.domain.tool.container.TerminalSessionTool
import com.aicode.feature.agent.domain.tool.explorer.ListFilesTool
import com.aicode.feature.agent.domain.tool.explorer.SearchCodeTool
import com.aicode.feature.agent.domain.tool.shizuku.ShizukuTool
import com.aicode.feature.agent.domain.tool.skill.LoadSkillTool
import com.aicode.feature.agent.domain.tool.question.AskUserQuestionTool
import com.aicode.feature.agent.domain.tool.todo.TodoTool
import com.aicode.feature.agent.domain.tool.subagent.TaskTool
import com.aicode.feature.agent.domain.tool.subagent.MessageParentTool
import com.aicode.feature.agent.domain.subagent.SubAgentEventBus
import com.aicode.feature.agent.domain.prompt.SystemPromptProvider
import com.aicode.feature.agent.domain.workflow.AgentWorkflow
import com.aicode.feature.agent.domain.tool.ToolPermissionManager
import com.aicode.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aicode.feature.agent.domain.tool.ToolRegistry
import com.aicode.feature.agent.domain.tool.git.CreatePullRequestTool
import com.aicode.feature.agent.domain.tool.ToolInvoker
import com.aicode.feature.settings.data.remote.ModelMetadataService
import com.aicode.feature.terminal.domain.DelegatingTerminalSessionProvider
import com.aicode.feature.terminal.domain.RemoteTerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionManager
import com.aicode.feature.terminal.domain.TerminalSessionProvider
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.DelegatingFileAccess
import com.aicode.feature.workspace.domain.LocalFileAccess
import com.aicode.feature.workspace.domain.RemoteSftpFileAccess
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

import com.aicode.core.db.MigrationLoader
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.checkpoint.CheckpointManager
import com.aicode.feature.agent.domain.notification.AgentEventInjector
import com.aicode.feature.agent.domain.notification.AgentNotificationCenter
import com.aicode.feature.agent.domain.notification.DefaultAgentEventInjector
import com.aicode.feature.agent.domain.session.MessagePersistenceUseCase
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.domain.tool.mcp.ManageMcpTool
import com.aicode.feature.agent.domain.tool.memory.MemoryTool
import com.aicode.feature.agent.domain.tool.mode.PlanModeTool
import com.aicode.feature.agent.domain.tool.search.WebFetchTool
import com.aicode.feature.agent.domain.tool.search.WebSearchTool
import com.aicode.feature.agent.domain.tool.browser.BrowserTool
import com.aicode.feature.agent.domain.workflow.ContextCompactor
import com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow
import com.aicode.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aicode.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aicode.feature.settings.data.repository.GeneralSettingsRepository
import com.aicode.feature.settings.data.repository.LanguageSettingsRepository
import com.aicode.feature.settings.data.repository.ProviderKeyRotator
import com.aicode.feature.settings.data.repository.OpenAIAccountRepository
import com.aicode.feature.settings.data.local.dao.OpenAIAccountDao
import com.aicode.feature.settings.data.repository.ReviewerModelSettingsRepository
import com.aicode.feature.settings.data.repository.TitleModelSettingsRepository
import com.aicode.feature.workspace.data.local.dao.RemoteConnectionDao
import com.aicode.feature.workspace.data.repository.WorkspaceRepository

@Module
@InstallIn(SingletonComponent::class)
object AgentModule {

    @Provides
    @Singleton
    fun provideAgentDatabase(@ApplicationContext context: Context): AgentDatabase {
        return Room.databaseBuilder(
            context,
            AgentDatabase::class.java,
            AgentDatabase.DATABASE_NAME
        ).addMigrations(*MigrationLoader.loadMigrations(context))
            .build()
    }

    @Provides
    @Singleton
    fun provideCheckpointDao(database: AgentDatabase): CheckpointDao {
        return database.checkpointDao()
    }

    @Provides
    @Singleton
    fun provideAgentMessageDao(database: AgentDatabase): AgentMessageDao {
        return database.agentMessageDao()
    }

    @Provides
    @Singleton
    fun provideChatSessionDao(database: AgentDatabase): ChatSessionDao {
        return database.chatSessionDao()
    }

    @Provides
    @Singleton
    fun provideAIProviderDao(database: AgentDatabase): AIProviderDao {
        return database.aiProviderDao()
    }

    @Provides
    @Singleton
    fun provideOpenAIAccountDao(database: AgentDatabase): OpenAIAccountDao {
        return database.openAIAccountDao()
    }

    @Provides
    @Singleton
    fun provideRemoteConnectionDao(database: AgentDatabase): RemoteConnectionDao {
        return database.remoteConnectionDao()
    }

    @Provides
    @Singleton
    fun provideLlmCallRecordDao(database: AgentDatabase): LlmCallRecordDao {
        return database.llmCallRecordDao()
    }

    @Provides
    @Singleton
    fun provideTodoItemDao(database: AgentDatabase): TodoItemDao {
        return database.todoItemDao()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        // 统一 UA 为 aicode/<版本> (Android)；请求已带显式 UA（如用户自定义头）时不覆盖。
        // 版本号走 PackageManager（项目未开启 BuildConfig），dev 构建为 1.x.y-dev.N+hash 天然可溯源。
        val userAgent = runCatching {
            val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            "aicode/$name (Android)"
        }.getOrDefault("aicode (Android)")
        // 流式 SSE 下读超时是「相邻数据块之间」的等待上限，设为 0（无限制），
        // 慢生成不会因块间隔超时被掐断；首字节前的卡死由上层 watchdog（RetryPolicy）兜底。
        return OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .eventListenerFactory(EventListener.Factory { call ->
                object : EventListener() {
                    private val callId = Integer.toHexString(System.identityHashCode(call))
                    private val host = call.request().url.host
                    private var callStartNanos = 0L
                    private var connectStartNanos = 0L
                    private var connectionId = "none"
                    private var protocol = "unknown"

                    private fun logStage(stage: String, details: String = "") {
                        val totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - callStartNanos)
                        FileLogger.i(
                            "OkHttp",
                            "call=$callId host=$host connection=$connectionId protocol=$protocol " +
                                "stage=$stage totalMs=$totalMs$details"
                        )
                    }

                    override fun callStart(call: Call) {
                        callStartNanos = System.nanoTime()
                        logStage("callStart")
                    }

                    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                        connectStartNanos = System.nanoTime()
                        connectionId = "none"
                        protocol = "unknown"
                    }

                    override fun connectEnd(
                        call: Call,
                        inetSocketAddress: InetSocketAddress,
                        proxy: Proxy,
                        protocol: Protocol?
                    ) {
                        this.protocol = protocol?.toString() ?: "unknown"
                        val connectMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectStartNanos)
                        logStage("connectEnd", " connectMs=$connectMs")
                    }

                    override fun connectFailed(
                        call: Call,
                        inetSocketAddress: InetSocketAddress,
                        proxy: Proxy,
                        protocol: Protocol?,
                        ioe: IOException
                    ) {
                        this.protocol = protocol?.toString() ?: "unknown"
                        val connectMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - connectStartNanos)
                        logStage("connectFailed", " connectMs=$connectMs exception=${ioe.javaClass.name}")
                    }

                    override fun connectionAcquired(call: Call, connection: Connection) {
                        connectionId = Integer.toHexString(System.identityHashCode(connection))
                        protocol = connection.protocol().toString()
                        logStage("connectionAcquired")
                    }

                    override fun requestHeadersEnd(call: Call, request: Request) {
                        logStage("requestHeadersEnd")
                    }

                    override fun responseHeadersEnd(call: Call, response: Response) {
                        logStage("responseHeadersEnd")
                    }

                    override fun callFailed(call: Call, ioe: IOException) {
                        logStage("callFailed", " exception=${ioe.javaClass.name}")
                    }

                    override fun callEnd(call: Call) {
                        logStage("callEnd")
                    }
                }
            })
            .addInterceptor { chain ->
                val request = chain.request()
                val finalRequest = if (request.header("User-Agent") != null) {
                    request
                } else {
                    request.newBuilder().header("User-Agent", userAgent).build()
                }
                chain.proceed(finalRequest)
            }
            .build()
    }

    @Provides
    @Singleton
    @Named("OpenAI")
    fun provideOpenAIRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.openai.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    @Named("Anthropic")
    fun provideAnthropicRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://api.anthropic.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideOpenAIApi(@Named("OpenAI") retrofit: Retrofit): OpenAIApi {
        return retrofit.create(OpenAIApi::class.java)
    }

    @Provides
    @Singleton
    fun provideAnthropicApi(@Named("Anthropic") retrofit: Retrofit): AnthropicApi {
        return retrofit.create(AnthropicApi::class.java)
    }

    @Provides
    @Singleton
    @Named("Gemini")
    fun provideGeminiRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://generativelanguage.googleapis.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideGeminiApi(@Named("Gemini") retrofit: Retrofit): GeminiApi {
        return retrofit.create(GeminiApi::class.java)
    }

    @Provides
    @Singleton
    fun provideCommandEngine(delegate: DelegatingCommandEngine): CommandEngine = delegate

    /**
     * rg 保障的执行环境实现：按当前模式回答「目标环境 / 缓存 key / 就绪判定 / 命令执行」。
     * 本地缓存 key 含 rootfs 安装标记 mtime（容器重建即失效），远程取 SSH 连接身份。
     */
    @Provides
    @Singleton
    fun provideRgEnvironment(
        modeHolder: ExecutionModeHolder,
        localEngine: LinuxContainerEngine,
        connection: RemoteSshConnection,
        containerInstaller: ContainerInstaller,
        delegatingEngine: DelegatingCommandEngine
    ): RgEnvironment = DefaultRgEnvironment(
        modeHolder = modeHolder,
        localEngine = localEngine,
        connection = connection,
        containerInstaller = containerInstaller,
        delegatingEngine = delegatingEngine
    )

    @Provides
    @Singleton
    fun provideFileAccess(delegate: DelegatingFileAccess): FileAccessProvider = delegate

    @Provides
    @Singleton
    fun provideTerminalSessionProvider(delegate: DelegatingTerminalSessionProvider): TerminalSessionProvider = delegate

    @Provides
    @Singleton
    fun provideDelegatingTerminalSessionProvider(
        modeHolder: ExecutionModeHolder,
        local: TerminalSessionManager,
        remote: RemoteTerminalSessionManager
    ): DelegatingTerminalSessionProvider = DelegatingTerminalSessionProvider(modeHolder, local, remote)

    @Provides
    @Singleton
    fun provideRemoteSftpFileAccess(
        connection: RemoteSshConnection,
        workspaceRepository: WorkspaceRepository
    ): RemoteSftpFileAccess = RemoteSftpFileAccess(connection, workspaceRepository)

    @Provides
    @Singleton
    fun provideSshHostKeyStore(impl: SharedPrefsSshHostKeyStore): SshHostKeyStore = impl

    @Provides
    @Singleton
    fun provideSshLoginKeyStore(impl: SharedPrefsSshLoginKeyStore): SshLoginKeyStore = impl

    @Provides
    @Singleton
    fun provideToolRegistry(
        readFileTool: ReadFileTool,
        sendFileTool: SendFileTool,
        viewImageTool: ViewImageTool,
        writeFileTool: WriteFileTool,
        editFileTool: EditFileTool,
        generateImageTool: GenerateImageTool,
        executeCommandTool: ExecuteCommandTool,
        shizukuTool: ShizukuTool,
        terminalSessionTool: TerminalSessionTool,
        listFilesTool: ListFilesTool,
        searchCodeTool: SearchCodeTool,
        loadSkillTool: LoadSkillTool,
        askUserQuestionTool: AskUserQuestionTool,
        manageMcpTool: ManageMcpTool,
        webSearchTool: WebSearchTool,
        webFetchTool: WebFetchTool,
        planModeTool: PlanModeTool,
        todoTool: TodoTool,
        memoryTool: MemoryTool,
        taskTool: TaskTool,
        messageParentTool: MessageParentTool,
        browserTool: BrowserTool,
        createPullRequestTool: CreatePullRequestTool
    ): ToolRegistry {
        return ToolRegistry().apply {
            register("readFile", readFileTool)
            register("sendFile", sendFileTool)
            register("viewImage", viewImageTool)
            register("writeFile", writeFileTool)
            register("editFile", editFileTool)
            // loadSkill 提前到文件工具组之后、Bash 之前：注册顺序即工具 schema 顺序，
            // 模型对清单前段的工具更敏感（本机实测 loadSkill 原在第 12 位，239 次调用 0 命中）。
            register("loadSkill", loadSkillTool)
            register("generateImage", generateImageTool)
            register("Bash", executeCommandTool)
            register("Shizuku", shizukuTool)
            register("terminal", terminalSessionTool)
            register("list", listFilesTool)
            register("search", searchCodeTool)
            register("askUserQuestion", askUserQuestionTool)
            register("manageMcp", manageMcpTool)
            register("websearch", webSearchTool)
            register("webfetch", webFetchTool)
            register("planMode", planModeTool)
            register("todo", todoTool)
            register("memory", memoryTool)
            register("task", taskTool)
            register("messageParent", messageParentTool)
            register("browser", browserTool)
            register("createPullRequest", createPullRequestTool)
        }
    }

    @Provides
    @Singleton
    fun provideAgentEventInjector(): AgentEventInjector = DefaultAgentEventInjector()

    @Provides
    @Singleton
    fun provideAgentWorkflow(
        toolRegistry: ToolRegistry,
        toolInvoker: ToolInvoker,
        aiProviderRepository: AIProviderRepository,
        openAIApi: OpenAIApi,
        anthropicApi: AnthropicApi,
        geminiApi: GeminiApi,
        promptProvider: SystemPromptProvider,
        permissionManager: ToolPermissionManager,
        policyEngine: ToolPermissionPolicyEngine,
        contextCompactor: ContextCompactor,
        modelMetadataService: ModelMetadataService,
        compactionModelSettingsRepository: CompactionModelSettingsRepository,
        titleModelSettingsRepository: TitleModelSettingsRepository,
        reviewerModelSettingsRepository: ReviewerModelSettingsRepository,
        languageSettingsRepository: LanguageSettingsRepository,
        defaultModelSettingsRepository: DefaultModelSettingsRepository,
        generalSettingsRepository: GeneralSettingsRepository,
        sessionUseCase: SessionUseCase,
        messagePersistenceUseCase: MessagePersistenceUseCase,
        checkpointManager: CheckpointManager,
        llmCallRecordDao: LlmCallRecordDao,
        keyRotator: ProviderKeyRotator,
        openAIAccountRepository: OpenAIAccountRepository,
        agentNotificationCenter: AgentNotificationCenter,
        eventInjector: AgentEventInjector,
        fileAccess: FileAccessProvider
    ): AgentWorkflow {
        return StatefulAgentWorkflow(
            toolRegistry,
            toolInvoker,
            aiProviderRepository,
            openAIApi,
            anthropicApi,
            geminiApi,
            promptProvider,
            permissionManager,
            policyEngine,
            contextCompactor,
            modelMetadataService,
            compactionModelSettingsRepository,
            titleModelSettingsRepository,
            reviewerModelSettingsRepository,
            languageSettingsRepository,
            defaultModelSettingsRepository,
            generalSettingsRepository,
            sessionUseCase,
            messagePersistenceUseCase,
            checkpointManager,
            llmCallRecordDao,
            keyRotator,
            openAIAccountRepository,
            agentNotificationCenter,
            eventInjector,
            fileAccess
        )
    }
}
