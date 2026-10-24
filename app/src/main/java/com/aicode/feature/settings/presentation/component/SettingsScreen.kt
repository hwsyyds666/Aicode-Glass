package com.aicode.feature.settings.presentation.component

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.core.theme.AppThemePreset
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.isDynamicColorSupported
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.pageEnter
import com.aicode.core.ui.pageExit
import com.aicode.core.util.LogLevel
import com.aicode.R
import com.aicode.feature.agent.domain.mcp.McpServerEntry
import com.aicode.feature.agent.domain.mcp.McpServerConfig
import com.aicode.feature.agent.domain.prompt.PromptFragment
import com.aicode.feature.agent.domain.prompt.PromptFragmentSource
import com.aicode.feature.agent.domain.mcp.McpServerStatus
import com.aicode.feature.agent.presentation.component.MarkdownContent
import com.aicode.feature.agent.presentation.component.MarkdownRenderCache
import com.aicode.feature.backup.presentation.BackupSection
import com.aicode.feature.settings.data.repository.AppThemeMode
import com.aicode.feature.settings.data.repository.BackgroundSettingsRepository
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.presentation.SettingsViewModel
import com.aicode.feature.settings.presentation.ShizukuViewModel
import com.aicode.feature.settings.presentation.SkillImportState
import com.aicode.feature.settings.presentation.SkillUiEntry
import com.aicode.feature.agent.domain.skill.SkillImportError
import com.aicode.feature.agent.domain.skill.SkillScope
import compose.icons.FeatherIcons
import compose.icons.feathericons.Activity
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.BarChart2
import compose.icons.feathericons.Book
import compose.icons.feathericons.BookOpen
import compose.icons.feathericons.Box
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Download
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Globe
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Image
import compose.icons.feathericons.Info
import compose.icons.feathericons.Lock
import compose.icons.feathericons.Moon
import compose.icons.feathericons.MessageSquare
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.PieChart
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Save
import compose.icons.feathericons.Server
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Users
import compose.icons.feathericons.Zap
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.onboardingTarget
import com.aicode.feature.settings.data.local.ProviderPreset
import com.aicode.feature.terminal.data.repository.TerminalSettings
import com.aicode.feature.terminal.presentation.component.TerminalSettingsSheet

/** 使用手册在线文档站地址。 */
private const val USER_GUIDE_DOCS_URL = "https://aicode.murk.top"

/** 大屏设置页左侧常驻菜单宽度。 */
private val SettingsMenuWidth = 320.dp

/**
 * 设置页走双栏所需的**自身**最小宽度。
 *
 * 不能直接拿窗口宽度判定：大屏下设置页左边还有常驻会话侧栏，按窗口算的话
 * 小平板（约 840dp）上扇出三栏，详情区只剩 200dp 不到，根本没法用。
 */
private val SettingsTwoPaneMinWidth = 840.dp

/** 设置页内部二级菜单分区。Menu 为首页菜单，其余为各自的二级页。 */
internal enum class SettingsSection(@param:StringRes val titleRes: Int) {
    Menu(R.string.settings_title),
    General(R.string.settings_general),
    Providers(R.string.settings_providers),
    OpenAIAccounts(R.string.openai_accounts_title),
    ProviderEditor(R.string.settings_provider_editor),
    DefaultModels(R.string.settings_default_models),
    ContextWindow(R.string.settings_context_window),
    Mcp(R.string.settings_mcp),
    Skills(R.string.settings_skills),
    Prompts(R.string.prompts_title),
    PromptDetail(R.string.prompts_title),
    PromptEditor(R.string.prompts_title),
    SkillDetail(R.string.settings_skills),
    SkillEditor(R.string.settings_skills),
    ExecutionChannel(R.string.settings_execution_channel),
    Container(R.string.settings_container),
    ContainerDownloads(R.string.container_download_image),
    Proxy(R.string.proxy_title),
    Log(R.string.settings_log),
    Permissions(R.string.settings_permissions),
    AppPermissions(R.string.settings_app_permissions),
    BackgroundRun(R.string.settings_category_background),
    RemoteServers(R.string.settings_remote_servers),
    Storage(R.string.settings_storage),
    TokenStats(R.string.settings_token_stats_title),
    PerfPanel(R.string.perf_panel_title),
    Backup(R.string.settings_backup),
    About(R.string.settings_about)
}

/**
 * 分区层级：0 首页菜单，1 一级分区，2 分区里再翻一层的详情 / 编辑页，3 详情页之上的编辑页。
 *
 * 只用于判定切换方向（变浅算返回）。[SettingsSection.Log] 的上一层是动态的（见 logReturnSection），
 * 统一当二级算：从菜单直接进它仍是 0 → 2 的「前进」，方向不会反。
 */
private fun SettingsSection.depth(): Int = when (this) {
    SettingsSection.Menu -> 0
    // 技能编辑页既可从列表(1) 进也可从详情页(2) 进，必须比详情页更深：
    // 同深度会让「编辑 → 详情」也被当成前进，返回时页面从右侧滑入，方向是反的。
    SettingsSection.SkillEditor,
    SettingsSection.PromptEditor -> 3
    SettingsSection.ProviderEditor,
    SettingsSection.OpenAIAccounts,
    SettingsSection.PromptDetail,
    SettingsSection.SkillDetail,
    SettingsSection.ContainerDownloads,
        SettingsSection.ContextWindow,
        SettingsSection.PerfPanel,
        SettingsSection.Log -> 2
    else -> 1
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onStopAllAndCloseTerminal: () -> Unit = {},
    onRerunOnboarding: () -> Unit = {},
    onboardingStep: OnboardingStep? = null,
    onOnboardingModelAdded: (() -> Unit)? = null,
    onOnboardingDismissFetchDialog: (() -> Unit)? = null
) {
    // Frequent per-section flows are collected by the section hosts below; only truly shared state stays here.
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val skillImportState by viewModel.skillImportState.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themePresetId by viewModel.themePresetId.collectAsStateWithLifecycle()
    val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
    val backgroundImagePath by viewModel.backgroundImagePath.collectAsStateWithLifecycle()
    val languageTag by viewModel.languageTag.collectAsStateWithLifecycle()
    val containerAnnouncementText by viewModel.containerAnnouncementText.collectAsStateWithLifecycle()
    val containerAnnouncementOutdated by viewModel.containerAnnouncementOutdated.collectAsStateWithLifecycle()
    val promptsAnnouncementText by viewModel.promptsAnnouncementText.collectAsStateWithLifecycle()
    val promptsAnnouncementOutdated by viewModel.promptsAnnouncementOutdated.collectAsStateWithLifecycle()
    val terminalSettings by viewModel.terminalSettings.collectAsStateWithLifecycle()
    var showTerminalSettingsSheet by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val currentLanguageDisplayName = if (languageTag.isNullOrBlank()) {
        stringResource(R.string.language_follow_system)
    } else {
        com.aicode.core.util.LanguageRegistry.languages.firstOrNull { it.tag == languageTag }?.displayName
            ?: stringResource(R.string.language_follow_system)
    }


    var section by remember { mutableStateOf(SettingsSection.Menu) }
    var logReturnSection by remember { mutableStateOf(SettingsSection.Menu) }
    var editingProvider by remember { mutableStateOf<AIProviderConfig?>(null) }
    var showAddProviderSheet by remember { mutableStateOf(false) }
    var providerPresetPrefill by remember { mutableStateOf<ProviderPreset?>(null) }
    var showMcpDialog by remember { mutableStateOf(false) }
    var editingMcp by remember { mutableStateOf<McpServerEntry?>(null) }

    LaunchedEffect(onboardingStep) {
        when (onboardingStep) {
            OnboardingStep.CONFIG_PROVIDER -> {
                section = SettingsSection.Menu
            }
            OnboardingStep.PROVIDER_ADD -> {
                section = SettingsSection.Providers
            }
            OnboardingStep.PROVIDER_CONFIG_INFO -> {
                if (section != SettingsSection.ProviderEditor) {
                    val deepseek = providers.firstOrNull { it.name.contains("deepseek", ignoreCase = true) }
                        ?: providers.firstOrNull()
                    editingProvider = deepseek
                    if (deepseek == null && providerPresetPrefill == null) {
                        providerPresetPrefill = ProviderPreset(
                            id = "deepseek",
                            name = "DeepSeek",
                            type = "DEEPSEEK",
                            baseUrl = "https://api.deepseek.com",
                            models = emptyList<String>()
                        )
                    }
                    section = SettingsSection.ProviderEditor
                }
            }
            OnboardingStep.PROVIDER_FETCH_MODELS,
            OnboardingStep.SIMULATE_FETCH_DIALOG -> {
                if (section != SettingsSection.ProviderEditor) {
                    val deepseek = providers.firstOrNull { it.name.contains("deepseek", ignoreCase = true) }
                        ?: providers.firstOrNull()
                    editingProvider = deepseek
                    if (deepseek == null && providerPresetPrefill == null) {
                        providerPresetPrefill = ProviderPreset(
                            id = "deepseek",
                            name = "DeepSeek",
                            type = "DEEPSEEK",
                            baseUrl = "https://api.deepseek.com",
                            models = emptyList<String>()
                        )
                    }
                    section = SettingsSection.ProviderEditor
                }
            }
            else -> {}
        }
    }
    var selectedSkill by remember { mutableStateOf<SkillUiEntry?>(null) }
    var skillToDelete by remember { mutableStateOf<SkillUiEntry?>(null) }

    // 自定义提示词：右上角「+」弹层可见性 + 编辑目标（新建/编辑/固定片段）
    var showPromptsAddSheet by remember { mutableStateOf(false) }
    var promptEditTarget by remember { mutableStateOf<PromptEditTarget?>(null) }
    var selectedPrompt by remember { mutableStateOf<PromptFragment?>(null) }
    var showPromptsAnnouncement by remember { mutableStateOf(false) }
    // 编辑页返回目标：从详情进就回详情，从列表「+」进就回列表。
    var promptEditorReturn by remember { mutableStateOf(SettingsSection.Prompts) }
    // 技能编辑目标：null 表示新建一个；编辑现有技能时指向被编辑的条目。
    var editingSkill by remember { mutableStateOf<SkillUiEntry?>(null) }
    // 编辑页的返回目标：从详情页进就回详情页，从列表顶栏「＋」进就回列表。
    var skillEditorReturn by remember { mutableStateOf(SettingsSection.Skills) }
    // 保存后要在详情页展示的技能名：列表刷新是异步的，先记名字等刷新完再换快照。
    var pendingSkillName by remember { mutableStateOf<String?>(null) }
    // 「添加技能」底部弹层：选择作用域后走手动新建 / 文件导入 / 压缩包导入。
    var showSkillAddSheet by remember { mutableStateOf(false) }
    var skillImportScope by remember { mutableStateOf(SkillScope.GLOBAL) }
    // 技能文件 / 压缩包选择器：结果交给 ViewModel 读取并落盘到所选作用域。
    val skillFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSkillFromMarkdown(uri, skillImportScope)
    }
    val skillZipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importSkillsFromZip(uri, skillImportScope)
    }
    // 技能正文 Markdown 解析缓存：详情页多次进入复用，避免重复解析卡顿
    val skillMarkdownCache = remember { MarkdownRenderCache() }
    var showContainerAddSheet by remember { mutableStateOf(false) }
    var showContainerAnnouncement by remember { mutableStateOf(false) }
    var showImageSourceSheet by remember { mutableStateOf(false) }
    var showThemeSheet by remember { mutableStateOf(false) }
    var showBackgroundSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    var showResetTokenStats by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val expanded = maxWidth >= SettingsTwoPaneMinWidth

    // 返回目标：null 表示没有上一层，返回动作交还上层导航（退出设置页）。
    // 大屏左菜单常驻，一级分区页没有「上一层」——回菜单只会得到一个空占位。
    // 返回键与顶栏返回箭头共用这一份判定，不再各写一套（之前两处不一致，镜像下载页返回会直接跳回菜单）。
    val parentSection: SettingsSection? = when (section) {
        SettingsSection.Menu -> null
        SettingsSection.ProviderEditor -> SettingsSection.Providers
        SettingsSection.OpenAIAccounts -> SettingsSection.Providers
        SettingsSection.Log -> logReturnSection.takeUnless { expanded && it == SettingsSection.Menu }
        SettingsSection.SkillDetail -> SettingsSection.Skills
        SettingsSection.PromptEditor -> promptEditorReturn
        SettingsSection.PromptDetail -> SettingsSection.Prompts
        SettingsSection.SkillEditor -> skillEditorReturn
        SettingsSection.ContainerDownloads -> SettingsSection.Container
        else -> if (expanded) null else SettingsSection.Menu
    }

    // 有上一层时系统返回键先回上一层；没有则交还给上层导航。
    BackHandler(enabled = parentSection != null) {
        parentSection?.let { section = it }
    }

    // settingsViewModel 为 Activity 级共享实例，每次进入设置页重新扫描技能，反映磁盘增删改。
    LaunchedEffect(Unit) {
        viewModel.refreshSkills()
    }

    // 首次（或公告内容更新后）进入「容器与镜像」页自动弹出使用说明；哈希比对在 ViewModel 完成。
    LaunchedEffect(section, containerAnnouncementOutdated) {
        if (section == SettingsSection.Container && containerAnnouncementOutdated && containerAnnouncementText.isNotBlank()) {
            showContainerAnnouncement = true
        }
    }

    // 首次（或公告内容更新后）进入「提示词」页自动弹出使用说明公告；哈希比对在 ViewModel 完成。
    LaunchedEffect(section, promptsAnnouncementOutdated) {
        if (section == SettingsSection.Prompts && promptsAnnouncementOutdated && promptsAnnouncementText.isNotBlank()) {
            showPromptsAnnouncement = true
        }
    }

    // 菜单内容：窄窗当首页用，大屏当常驻左栏用，共用同一份。
    // 滚动位置必须提到 AnimatedContent 之外持有：菜单页作为分区分支被切走时会被 dispose，
    // 内部 rememberScrollState 一并丢弃，从二级页返回就跳回顶部。
    val menuScrollState = rememberScrollState()
    val menuBody: @Composable () -> Unit = {
        SettingsMenuAlphaHost(
            viewModel = viewModel,
            scrollState = menuScrollState,
            themeMode = themeMode,
            themePresetId = themePresetId,
            dynamicColorEnabled = dynamicColorEnabled,
            terminalSettings = terminalSettings,
            currentLanguageDisplayName = currentLanguageDisplayName,
            backgroundImagePath = backgroundImagePath,
            onOpenThemeSheet = { showThemeSheet = true },
            onOpenTerminalSettingsSheet = { showTerminalSettingsSheet = true },
            onOpenBackgroundSheet = { showBackgroundSheet = true },
            onOpenLanguageSheet = { showLanguageSheet = true },
            onOpenManual = {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(USER_GUIDE_DOCS_URL))
                )
            },
            onRerunOnboarding = onRerunOnboarding,
            onOpen = {
                if (it == SettingsSection.Log) {
                    logReturnSection = SettingsSection.Menu
                    viewModel.refreshLogs(filterServerName = null)
                }
                section = it
            }
        )
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // 大屏：菜单常驻左栏，右栏只换详情，不再整页来回切。
        if (expanded) {
            Column(
                modifier = Modifier
                    .width(SettingsMenuWidth)
                    .fillMaxHeight()
                    .background(settingsPageBackground())
            ) {
                TopAppBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = settingsPageBackground(),
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                FeatherIcons.ArrowLeft,
                                contentDescription = stringResource(R.string.common_back)
                            )
                        }
                    }
                )
                menuBody()
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }

        // 过渡期间退场页会向左移出自身区域（sizeTransform 为 null 时 AnimatedContent 不裁剪），
        // 不夹住就会画到大屏左侧常驻菜单上。
        Box(modifier = Modifier.weight(1f).clipToBounds()) {
        // 分区切换走整页过渡（顶栏一起滑），而不是瞬切；方向按层级深度定：进更深一层从右来，返回从左来。
        AnimatedContent(
            targetState = section,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val forward = targetState.depth() >= initialState.depth()
                // sizeTransform 必须给 null：新旧内容尺寸本就一样，默认的尺寸补间只会让容器裁剪拖影。
                ContentTransform(pageEnter(forward), pageExit(forward), sizeTransform = null)
            },
            label = "settings-section"
        ) { current ->
        when {
            // 这两页自带顶栏，不能嵌进下面的 Scaffold（会双层顶栏）：窄窗占整屏，大屏占右栏。
            current == SettingsSection.ProviderEditor -> ProviderEditorScreen(
                viewModel = viewModel,
                initialProvider = editingProvider,
                presetPrefill = providerPresetPrefill,
                initialTab = if (onboardingStep == OnboardingStep.PROVIDER_FETCH_MODELS || onboardingStep == OnboardingStep.SIMULATE_FETCH_DIALOG) 1 else 0,
                onboardingStep = onboardingStep,
                onOnboardingModelAdded = onOnboardingModelAdded,
                onOnboardingDismissFetchDialog = onOnboardingDismissFetchDialog,
                onNavigateBack = {
                    section = SettingsSection.Providers
                    providerPresetPrefill = null
                },
                onSave = { provider ->
                    viewModel.saveProvider(provider)
                }
            )

            current == SettingsSection.SkillEditor -> {
                SkillEditorSectionHost(
                    viewModel = viewModel,
                    initial = editingSkill,
                    defaultScope = skillImportScope,
                    onSave = { form, scope -> viewModel.saveSkill(form, scope, editingSkill?.name) },
                    onSaved = { savedName ->
                        viewModel.clearSkillSaveState()
                        // 从详情页进来的改完回详情页，但得等新快照到位再展示
                        if (skillEditorReturn == SettingsSection.SkillDetail) {
                            pendingSkillName = savedName
                        }
                        section = skillEditorReturn
                    },
                    onNavigateBack = {
                        viewModel.clearSkillSaveState()
                        section = skillEditorReturn
                    }
                )
            }

            current == SettingsSection.PromptEditor -> {
                val number = promptEditTarget?.number
                PromptEditorSectionHost(
                    number = number,
                    onSaved = {
                        section = promptEditorReturn
                    },
                    onNavigateBack = { section = promptEditorReturn }
                )
            }

            current == SettingsSection.RemoteServers ->
                com.aicode.feature.workspace.presentation.remote.RemoteServerScreen(
                    onNavigateBack = { section = SettingsSection.Menu }
                )

            else -> {
            // 存储页的顶栏刷新按钮与页面内容要共用同一个 ViewModel，故在此分支创建；
            // 它的构造即触发一次全盘统计，不能提到 SettingsScreen 顶层（那样每次进设置页都会扫盘）。
            val storageViewModel: com.aicode.feature.settings.presentation.StorageViewModel? =
                if (current == SettingsSection.Storage) androidx.hilt.navigation.compose.hiltViewModel() else null
            val mcpReloading = if (current == SettingsSection.Mcp) {
                viewModel.mcpReloading.collectAsStateWithLifecycle().value
            } else false
            Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (current == SettingsSection.SkillDetail) {
                            selectedSkill?.name ?: stringResource(current.titleRes)
                        } else if (expanded && current == SettingsSection.Menu) {
                            // 大屏左栏顶部已经写着「设置」，右栏空态不再重复
                            ""
                        } else {
                            stringResource(current.titleRes)
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                navigationIcon = {
                    // 大屏一级分区页不给返回箭头：左栏菜单随时可切，退出设置的入口在左栏顶部。
                    if (!expanded || parentSection != null) {
                        IconButton(onClick = {
                            val parent = parentSection
                            if (parent != null) section = parent else onNavigateBack()
                        }) {
                            Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                },
                actions = {
                    when (current) {
                        SettingsSection.Providers -> IconButton(
                            onClick = {
                                providerPresetPrefill = null
                                showAddProviderSheet = true
                            },
                            modifier = Modifier.onboardingTarget(OnboardingStep.PROVIDER_ADD)
                        ) {
                            Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.settings_add_provider))
                        }
                        SettingsSection.Mcp -> {
                            IconButton(onClick = { viewModel.reloadMcp() }) {
                                if (mcpReloading) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(
                                        FeatherIcons.RefreshCw,
                                        contentDescription = stringResource(R.string.settings_reconnect),
                                        tint = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                            IconButton(onClick = {
                                editingMcp = null
                                showMcpDialog = true
                            }) {
                                Icon(
                                    FeatherIcons.Plus,
                                    contentDescription = stringResource(R.string.settings_add_mcp_server),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        SettingsSection.Container -> {
                            IconButton(onClick = { showContainerAnnouncement = true }) {
                                Icon(
                                    FeatherIcons.Info,
                                    contentDescription = stringResource(R.string.container_announcement_title),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconButton(onClick = { section = SettingsSection.ContainerDownloads }) {
                                Icon(
                                    FeatherIcons.Download,
                                    contentDescription = stringResource(R.string.container_download_image),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconButton(onClick = { showContainerAddSheet = true }) {
                                Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.container_add_image))
                            }
                        }
                        SettingsSection.ContainerDownloads -> {
                            IconButton(onClick = { showImageSourceSheet = true }) {
                                Icon(
                                    FeatherIcons.Globe,
                                    contentDescription = stringResource(R.string.container_download_current_source_title),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        SettingsSection.Skills -> IconButton(onClick = {
                            showSkillAddSheet = true
                        }) {
                            Icon(
                                FeatherIcons.Plus,
                                contentDescription = stringResource(R.string.skills_add),
                                tint = MaterialTheme.colorScheme.onBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        SettingsSection.SkillDetail -> selectedSkill?.let { entry ->
                            IconButton(onClick = {
                                editingSkill = entry
                                skillEditorReturn = SettingsSection.SkillDetail
                                section = SettingsSection.SkillEditor
                            }) {
                                Icon(
                                    FeatherIcons.Edit2,
                                    contentDescription = stringResource(R.string.skills_edit),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        SettingsSection.Log -> {
                            IconButton(onClick = { viewModel.refreshLogs() }) {
                                Icon(FeatherIcons.RefreshCw, contentDescription = stringResource(R.string.settings_refresh_logs))
                            }
                        }
                        SettingsSection.TokenStats -> {
                            IconButton(onClick = { showResetTokenStats = true }) {
                                Icon(
                                    FeatherIcons.Trash2,
                                    contentDescription = stringResource(R.string.settings_token_stats_reset),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        SettingsSection.Prompts -> {
                            IconButton(onClick = { showPromptsAnnouncement = true }) {
                                Icon(
                                    FeatherIcons.Info,
                                    contentDescription = stringResource(R.string.prompts_help),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            IconButton(onClick = { showPromptsAddSheet = true }) {
                                Icon(
                                    FeatherIcons.Plus,
                                    contentDescription = stringResource(R.string.prompts_add_prompt),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        SettingsSection.PromptDetail -> selectedPrompt?.let { fragment ->
                            IconButton(onClick = {
                                promptEditTarget = PromptEditTarget(fragment.number)
                                promptEditorReturn = SettingsSection.PromptDetail
                                section = SettingsSection.PromptEditor
                            }) {
                                Icon(
                                    FeatherIcons.Edit2,
                                    contentDescription = stringResource(R.string.subagent_edit),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        SettingsSection.Storage -> {
                            IconButton(onClick = { storageViewModel?.refresh() }) {
                                Icon(
                                    FeatherIcons.RefreshCw,
                                    contentDescription = stringResource(R.string.storage_refresh),
                                    tint = MaterialTheme.colorScheme.onBackground,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        else -> {}
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (current) {
                // 大屏菜单已常驻左栏，右栏在没选中分区时给个占位提示
                SettingsSection.Menu -> if (expanded) SettingsDetailPlaceholder() else menuBody()
                SettingsSection.General -> GeneralSettingsSectionHost(viewModel)
                SettingsSection.Providers -> {
                    // ChatGPT 账号由「ChatGPT 账号」入口管理，不作为独立供应商出现在列表里。
                    // 过滤结果必须按 providers 记住：AnimatedContent 过渡动画每帧都会重组这一分支，
                    // 每次重组都新建列表实例，会让 ProvidersSection 里的 LaunchedEffect(providers)
                    // 逐帧重启并回写本地拖拽顺序，从编辑页返回时就表现为卡顿不连贯。
                    val sectionProviders = remember(providers) {
                        providers.filterNot {
                            com.aicode.feature.settings.data.repository.OpenAIAccountRepository.isAccountProvider(it)
                        }
                    }
                    ProvidersSection(
                        providers = sectionProviders,
                        onEdit = {
                            if (!com.aicode.feature.settings.data.repository.OpenAIAccountRepository.isAccountProvider(it)) {
                                editingProvider = it
                                section = SettingsSection.ProviderEditor
                            }
                        },
                        onDelete = {
                            if (!com.aicode.feature.settings.data.repository.OpenAIAccountRepository.isAccountProvider(it)) {
                                viewModel.deleteProvider(it.id)
                            }
                        },
                        onReorder = { ordered -> viewModel.reorderProviders(ordered) },
                        onOpenOpenAIAccounts = { section = SettingsSection.OpenAIAccounts }
                    )
                }
                SettingsSection.OpenAIAccounts -> OpenAIAccountsSection()
                SettingsSection.DefaultModels -> DefaultModelsSectionHost(
                    viewModel = viewModel,
                    providers = providers,
                    onOpenContextWindow = { section = SettingsSection.ContextWindow }
                )
                SettingsSection.Mcp -> McpSectionHost(
                    viewModel = viewModel,
                    reloading = mcpReloading,
                    onEdit = {
                        editingMcp = it
                        showMcpDialog = true
                    }
                )
                SettingsSection.Skills -> SkillsSectionHost(
                    viewModel = viewModel,
                    pendingSkillName = pendingSkillName,
                    onResolvePendingSkill = {
                        selectedSkill = it
                        pendingSkillName = null
                    },
                    onDelete = { skillToDelete = it },
                    onOpenDetail = {
                        selectedSkill = it
                        section = SettingsSection.SkillDetail
                    }
                )
                SettingsSection.Prompts -> {
                    PromptsSectionHost(
                        onOpenFragment = { fragment ->
                            selectedPrompt = fragment
                            section = SettingsSection.PromptDetail
                        }
                    )
                    if (showPromptsAddSheet) {
                        PromptsAddSheet(
                            onDismiss = { showPromptsAddSheet = false },
                            onAddFragment = {
                                showPromptsAddSheet = false
                                promptEditTarget = PromptEditTarget(null)
                                promptEditorReturn = SettingsSection.Prompts
                                section = SettingsSection.PromptEditor
                            },
                            onHelp = {
                                showPromptsAddSheet = false
                                showPromptsAnnouncement = true
                            }
                        )
                    }
                }
                SettingsSection.PromptDetail -> {
                    PromptDetailSectionHost(selectedPrompt)
                }
                SettingsSection.PromptEditor -> Unit
                SettingsSection.SkillDetail -> selectedSkill?.let { entry ->
                    SkillDetailSection(
                        entry = entry,
                        cache = skillMarkdownCache,
                        onToggle = { enabled ->
                            viewModel.setSkillEnabled(entry.name, enabled, entry.scope)
                            // 同步更新详情页快照，开关立即响应
                            selectedSkill = selectedSkill?.copy(disabled = !enabled)
                        }
                    )
                }
                SettingsSection.ExecutionChannel -> ExecutionChannelSectionHost(viewModel)
                SettingsSection.Container -> ContainerSectionHost(
                    viewModel = viewModel,
                    showAddSheet = showContainerAddSheet,
                    onDismissAddSheet = { showContainerAddSheet = false },
                    onStopAllAndCloseTerminal = onStopAllAndCloseTerminal
                )
                SettingsSection.ContainerDownloads -> ContainerImageDownloadSectionHost(viewModel, languageTag)
                SettingsSection.Proxy -> ProxySectionHost(viewModel)
                SettingsSection.Log -> LogSectionHost(viewModel)
                SettingsSection.Permissions -> PermissionsSectionHost(viewModel)
                SettingsSection.ContextWindow -> ContextWindowSectionHost(viewModel)
                SettingsSection.AppPermissions -> AppPermissionsSectionHost()
                SettingsSection.BackgroundRun -> BackgroundRunSectionHost(viewModel)
                SettingsSection.Backup -> {
                    val backupViewModel: com.aicode.feature.backup.presentation.BackupViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    BackupSection(viewModel = backupViewModel)
                }
                SettingsSection.TokenStats -> TokenStatsSectionHost(viewModel)
                SettingsSection.Storage -> storageViewModel?.let { StorageSectionHost(viewModel = it) }
                SettingsSection.PerfPanel -> {
                    val perfViewModel: com.aicode.feature.settings.presentation.PerfPanelViewModel =
                        androidx.hilt.navigation.compose.hiltViewModel()
                    PerfPanelSection(viewModel = perfViewModel)
                }
                SettingsSection.ProviderEditor -> {} // 已在上方 early return 处理
                SettingsSection.SkillEditor -> {} // 已在上方 early return 处理
                SettingsSection.RemoteServers -> {} // 已在上方 early return 处理
                SettingsSection.About -> AboutSectionHost(viewModel)
            }
        }
    }
        } // else 分支结束（storageViewModel 作用域）
        } // 详情区 when 结束
        } // AnimatedContent 结束
        } // 右栏结束
    } // Row 结束

    if (showMcpDialog) {
        McpServerEditDialog(
            initial = editingMcp?.server,
            initialScope = editingMcp?.scope,
            tools = viewModel.getMcpServerTools(editingMcp?.server?.name),
            onRefreshTools = { editingMcp?.let { viewModel.reloadMcpServer(it.server.name) } },
            onOpenLogs = editingMcp?.let { existing ->
                {
                    showMcpDialog = false
                    logReturnSection = SettingsSection.Mcp
                    viewModel.refreshLogs(filterServerName = existing.server.name)
                    section = SettingsSection.Log
                }
            },
            onDismiss = { showMcpDialog = false },
            onSave = { config, scope ->
                viewModel.upsertMcpServer(editingMcp?.server?.name, editingMcp?.scope, config, scope)
                showMcpDialog = false
            }
        )
    }

    if (showAddProviderSheet) {
        ProviderPresetSheet(
            onDismiss = { showAddProviderSheet = false },
            onSelectCustom = {
                showAddProviderSheet = false
                editingProvider = null
                providerPresetPrefill = null
                section = SettingsSection.ProviderEditor
            },
            onSelectOfficial = { preset ->
                showAddProviderSheet = false
                editingProvider = null
                providerPresetPrefill = preset
                section = SettingsSection.ProviderEditor
            }
        )
    }

    if (showSkillAddSheet) {
        SkillAddSheet(
            scope = skillImportScope,
            onScopeChange = { skillImportScope = it },
            onManual = {
                showSkillAddSheet = false
                editingSkill = null
                skillEditorReturn = SettingsSection.Skills
                section = SettingsSection.SkillEditor
            },
            onPickFile = {
                showSkillAddSheet = false
                skillFileLauncher.launch(arrayOf("text/*", "application/octet-stream"))
            },
            onPickZip = {
                showSkillAddSheet = false
                skillZipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            },
            onDismiss = { showSkillAddSheet = false }
        )
    }

    if (showThemeSheet) {
        ThemeSelectionSheet(
            selected = themeMode,
            selectedPresetId = themePresetId,
            dynamicColorEnabled = dynamicColorEnabled,
            onSelected = { viewModel.setThemeMode(it) },
            onPresetSelected = { viewModel.setThemePreset(it) },
            onDynamicColorChanged = { viewModel.setDynamicColorEnabled(it) },
            onDismiss = { showThemeSheet = false }
        )
    }

    if (showTerminalSettingsSheet) {
        TerminalSettingsSheet(
            settings = terminalSettings,
            onDismiss = { showTerminalSettingsSheet = false },
            onSelectTheme = { viewModel.setTerminalTheme(it) },
            onChangeFontSize = { viewModel.setTerminalFontSize(it) },
            onChangeCursorStyle = { viewModel.setTerminalCursorStyle(it) },
            onChangeFontPath = { viewModel.setTerminalFontPath(it) }
        )
    }

    if (showBackgroundSheet) {
        BackgroundImageSheetStateHost(
            viewModel = viewModel,
            imagePath = backgroundImagePath,
            onPickImage = { viewModel.setBackgroundImage(it) },
            onRemove = { viewModel.clearBackgroundImage() },
            onDismiss = { showBackgroundSheet = false }
        )
    }

    if (showLanguageSheet) {
        LanguageSelectionSheet(
            currentTag = languageTag,
            onSelect = { viewModel.setLanguage(it) },
            onDismiss = { showLanguageSheet = false }
        )
    }

    if (showImageSourceSheet) {
        SourceSelectionSheetHost(
            viewModel = viewModel,
            languageTag = languageTag,
            onDismiss = { showImageSourceSheet = false }
        )
    }

    if (showResetTokenStats) {
        AlertDialog(
            onDismissRequest = { showResetTokenStats = false },
            title = { Text(stringResource(R.string.settings_token_stats_reset_title)) },
            text = { Text(stringResource(R.string.settings_token_stats_reset_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetTokenStats()
                    showResetTokenStats = false
                }) { Text(stringResource(R.string.settings_token_stats_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetTokenStats = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    skillToDelete?.let { target ->
        SkillDeleteDialogHost(
            viewModel = viewModel,
            target = target,
            onDismiss = { skillToDelete = null }
        )
    }

    SkillImportResultDialog(state = skillImportState, onDismiss = { viewModel.clearSkillImportState() })

    // 「容器与镜像」使用说明公告：首次进入（或内容更新后）自动弹出，右上角 Info 按钮可随时重看。
    if (showContainerAnnouncement) {
        val dismiss = {
            showContainerAnnouncement = false
            viewModel.markContainerAnnouncementShown()
        }
        Dialog(onDismissRequest = dismiss) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.72f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.container_announcement_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (containerAnnouncementText.isNotBlank()) {
                        // mikepenz Markdown 内部是 Column（非 LazyColumn），本身不可滚动，
                        // 必须由外层提供滚动容器，否则超出弹窗高度的内容被直接裁剪。
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 8.dp, bottom = Spacing.lg)
                        ) {
                            MarkdownContent(
                                text = containerAnnouncementText,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                                loading = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                            )
                        }
                    }
                    Button(
                        onClick = dismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.container_announcement_got_it))
                    }
                }
            }
        }
    }

    // 提示词使用说明公告：首次进入（或内容更新后）自动弹出，右上角 Info 按钮可随时重看。
    if (showPromptsAnnouncement) {
        val dismiss = {
            showPromptsAnnouncement = false
            viewModel.markPromptsAnnouncementShown()
        }
        Dialog(onDismissRequest = dismiss) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.72f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(20.dp)
                ) {
                    Text(
                        text = stringResource(R.string.prompts_help),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (promptsAnnouncementText.isNotBlank()) {
                        // mikepenz Markdown 内部是 Column（非 LazyColumn），本身不可滚动，
                        // 必须由外层提供滚动容器，否则超出弹窗高度的内容被直接裁剪。
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 8.dp, bottom = Spacing.lg)
                        ) {
                            MarkdownContent(
                                text = promptsAnnouncementText,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.fillMaxWidth(),
                                loading = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator()
                                    }
                                }
                            )
                        }
                    }
                    Button(
                        onClick = dismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .padding(top = 4.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.common_got_it))
                    }
                }
            }
        }
    }
    } // BoxWithConstraints 结束
}

/** 大屏右栏未选中分区时的占位提示。 */
@Composable
private fun SettingsDetailPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = stringResource(R.string.settings_select_section_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.semanticColors.subtleText
        )
    }
}

@Composable
private fun SettingsMenuAlphaHost(
    viewModel: SettingsViewModel,
    scrollState: ScrollState,
    themeMode: AppThemeMode,
    themePresetId: String?,
    dynamicColorEnabled: Boolean,
    terminalSettings: TerminalSettings,
    currentLanguageDisplayName: String,
    backgroundImagePath: String?,
    onOpenThemeSheet: () -> Unit,
    onOpenTerminalSettingsSheet: () -> Unit,
    onOpenBackgroundSheet: () -> Unit,
    onOpenLanguageSheet: () -> Unit,
    onOpenManual: () -> Unit,
    onRerunOnboarding: () -> Unit,
    onOpen: (SettingsSection) -> Unit
) {
    val backgroundAlpha by viewModel.backgroundAlpha.collectAsStateWithLifecycle()
    SettingsMenu(
        scrollState = scrollState,
        themeMode = themeMode,
        themePresetId = themePresetId,
        dynamicColorEnabled = dynamicColorEnabled,
        terminalSettings = terminalSettings,
        currentLanguageDisplayName = currentLanguageDisplayName,
        backgroundImagePath = backgroundImagePath,
        backgroundAlpha = backgroundAlpha,
        onOpenThemeSheet = onOpenThemeSheet,
        onOpenTerminalSettingsSheet = onOpenTerminalSettingsSheet,
        onOpenBackgroundSheet = onOpenBackgroundSheet,
        onOpenLanguageSheet = onOpenLanguageSheet,
        onOpenManual = onOpenManual,
        onRerunOnboarding = onRerunOnboarding,
        onOpen = onOpen
    )
}

@Composable
private fun BackgroundImageSheetStateHost(
    viewModel: SettingsViewModel,
    imagePath: String?,
    onPickImage: (Uri) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    val alpha by viewModel.backgroundAlpha.collectAsStateWithLifecycle()
    BackgroundImageSheet(
        imagePath = imagePath,
        alpha = alpha,
        onPickImage = onPickImage,
        onAlphaChange = viewModel::setBackgroundAlpha,
        onRemove = onRemove,
        onDismiss = onDismiss
    )
}

/** 设置首页：每个分区一个可点击的二级菜单入口。 */
@Composable
internal fun SettingsMenu(
    scrollState: ScrollState,
    themeMode: AppThemeMode,
    themePresetId: String?,
    dynamicColorEnabled: Boolean,
    terminalSettings: TerminalSettings,
    currentLanguageDisplayName: String,
    backgroundImagePath: String?,
    backgroundAlpha: Float,
    onOpenThemeSheet: () -> Unit,
    onOpenTerminalSettingsSheet: () -> Unit,
    onOpenBackgroundSheet: () -> Unit,
    onOpenLanguageSheet: () -> Unit,
    onOpenManual: () -> Unit,
    onRerunOnboarding: () -> Unit,
    onOpen: (SettingsSection) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // ── 通用设置 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_general))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Sliders,
                title = stringResource(SettingsSection.General.titleRes),
                onClick = { onOpen(SettingsSection.General) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Moon,
                title = stringResource(R.string.settings_theme_title),
                onClick = onOpenThemeSheet,
                trailing = {
                    val colorLabel = if (dynamicColorEnabled && isDynamicColorSupported) {
                        stringResource(R.string.theme_dynamic_color)
                    } else {
                        stringResource(AppThemePreset.findById(themePresetId).nameRes)
                    }
                    Text(
                        text = "${stringResource(themeMode.labelRes)} · $colorLabel",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Terminal,
                title = stringResource(R.string.terminal_settings_title),
                onClick = onOpenTerminalSettingsSheet,
                trailing = {
                    Text(
                        text = stringResource(terminalSettings.theme.nameRes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Image,
                title = stringResource(R.string.settings_background_image),
                onClick = onOpenBackgroundSheet,
                trailing = {
                    Text(
                        text = if (backgroundImagePath != null) "${BackgroundSettingsRepository.alphaToSlider(backgroundAlpha).toInt()}%"
                        else stringResource(R.string.settings_background_image_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Globe,
                title = stringResource(R.string.settings_language),
                onClick = onOpenLanguageSheet,
                trailing = {
                    Text(
                        text = currentLanguageDisplayName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            )
        }

        // ── AI 配置 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_ai))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Cloud,
                title = stringResource(SettingsSection.Providers.titleRes),
                onClick = { onOpen(SettingsSection.Providers) },
                modifier = Modifier.onboardingTarget(OnboardingStep.CONFIG_PROVIDER)
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Cpu,
                title = stringResource(SettingsSection.DefaultModels.titleRes),
                onClick = { onOpen(SettingsSection.DefaultModels) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Box,
                title = stringResource(SettingsSection.Mcp.titleRes),
                onClick = { onOpen(SettingsSection.Mcp) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Book,
                title = stringResource(SettingsSection.Skills.titleRes),
                onClick = { onOpen(SettingsSection.Skills) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.MessageSquare,
                title = stringResource(SettingsSection.Prompts.titleRes),
                onClick = { onOpen(SettingsSection.Prompts) }
            )
        }

        // ── 运行环境 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_environment))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Zap,
                title = stringResource(SettingsSection.ExecutionChannel.titleRes),
                onClick = { onOpen(SettingsSection.ExecutionChannel) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.HardDrive,
                title = stringResource(SettingsSection.Container.titleRes),
                onClick = { onOpen(SettingsSection.Container) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Globe,
                title = stringResource(SettingsSection.Proxy.titleRes),
                onClick = { onOpen(SettingsSection.Proxy) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Server,
                title = stringResource(SettingsSection.RemoteServers.titleRes),
                onClick = { onOpen(SettingsSection.RemoteServers) }
            )
        }

        // ── 权限与后台 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_permissions))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Lock,
                title = stringResource(SettingsSection.Permissions.titleRes),
                onClick = { onOpen(SettingsSection.Permissions) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Shield,
                title = stringResource(SettingsSection.AppPermissions.titleRes),
                onClick = { onOpen(SettingsSection.AppPermissions) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.RefreshCw,
                title = stringResource(SettingsSection.BackgroundRun.titleRes),
                onClick = { onOpen(SettingsSection.BackgroundRun) }
            )
        }

        // ── 数据与诊断 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_data))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.BarChart2,
                title = stringResource(SettingsSection.TokenStats.titleRes),
                onClick = { onOpen(SettingsSection.TokenStats) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.PieChart,
                title = stringResource(SettingsSection.Storage.titleRes),
                onClick = { onOpen(SettingsSection.Storage) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Activity,
                title = stringResource(SettingsSection.PerfPanel.titleRes),
                onClick = { onOpen(SettingsSection.PerfPanel) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Save,
                title = stringResource(SettingsSection.Backup.titleRes),
                onClick = { onOpen(SettingsSection.Backup) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.FileText,
                title = stringResource(SettingsSection.Log.titleRes),
                onClick = { onOpen(SettingsSection.Log) }
            )
        }

        // ── 帮助与关于 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_category_help))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.BookOpen,
                title = stringResource(R.string.settings_user_guide),
                onClick = onOpenManual
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.settings_rerun_onboarding),
                onClick = onRerunOnboarding
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Info,
                title = stringResource(SettingsSection.About.titleRes),
                onClick = { onOpen(SettingsSection.About) }
            )
        }
    }

}

/** 导入结果弹窗：导入中显示转圈；完成时展示成功数量与逐个跳过原因（整体失败则直接报错误）。 */
@Composable
private fun SkillImportResultDialog(state: SkillImportState, onDismiss: () -> Unit) {
    when (state) {
        SkillImportState.Idle -> Unit
        SkillImportState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.skills_import_running)) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            },
            confirmButton = {}
        )
        is SkillImportState.Done -> {
            val report = state.report
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(stringResource(R.string.skills_import_result_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        val fatal = report.fatal
                        if (fatal != null) {
                            Text(stringResource(fatal.messageRes()))
                        } else {
                            Text(
                                stringResource(
                                    if (report.failures.isEmpty()) R.string.skills_import_success
                                    else R.string.skills_import_partial,
                                    report.imported.size,
                                    report.failures.size
                                )
                            )
                            report.failures.forEach { failure ->
                                Text(
                                    text = stringResource(
                                        R.string.skills_import_failure_line,
                                        failure.name,
                                        stringResource(failure.error.messageRes())
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) }
                }
            )
        }
    }
}

private fun SkillImportError.messageRes(): Int = when (this) {
    SkillImportError.INVALID_NAME -> R.string.skills_import_error_invalid_name
    SkillImportError.NAME_CONFLICT -> R.string.skills_import_error_name_conflict
    SkillImportError.EMPTY_CONTENT -> R.string.skills_import_error_empty_content
    SkillImportError.NO_SKILL_FOUND -> R.string.skills_import_error_no_skill
    SkillImportError.INVALID_ARCHIVE -> R.string.skills_import_error_invalid_archive
    SkillImportError.UNSUPPORTED_FILE -> R.string.skills_import_error_unsupported_file
    SkillImportError.IO_FAILED -> R.string.skills_import_error_io
}
