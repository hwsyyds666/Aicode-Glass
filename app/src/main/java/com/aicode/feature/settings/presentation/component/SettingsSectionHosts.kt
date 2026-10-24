package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.aicode.R
import com.aicode.feature.agent.domain.mcp.McpServerEntry
import com.aicode.feature.agent.domain.prompt.PromptFragment
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.presentation.SettingsViewModel
import com.aicode.feature.settings.presentation.SkillSaveState
import com.aicode.feature.settings.presentation.SkillUiEntry
import com.aicode.feature.settings.presentation.ShizukuViewModel
import com.aicode.feature.agent.domain.skill.SkillScope

/**
 * SettingsScreen keeps only state shared by navigation, global sheets, or the menu summary.
 * Section-specific StateFlows are collected in these small hosts so updates restart only the
 * active section instead of the large settings navigation composable.
 */
@Composable
internal fun GeneralSettingsSectionHost(viewModel: SettingsViewModel) {
    val autoRemoveStaleModels by viewModel.autoRemoveStaleModels.collectAsStateWithLifecycle()
    val startupSessionMode by viewModel.startupSessionMode.collectAsStateWithLifecycle()
    val firstByteTimeoutSec by viewModel.firstByteTimeoutSec.collectAsStateWithLifecycle()
    val streamIdleTimeoutSec by viewModel.streamIdleTimeoutSec.collectAsStateWithLifecycle()
    val maxNetworkRetries by viewModel.maxNetworkRetries.collectAsStateWithLifecycle()
    val enterToSend by viewModel.enterToSend.collectAsStateWithLifecycle()
    val compactionThresholdPercent by viewModel.compactionThresholdPercent.collectAsStateWithLifecycle()
    val sendFileMaxSizeMb by viewModel.sendFileMaxSizeMb.collectAsStateWithLifecycle()
    val deleteExternalWorkspaceSessions by viewModel.deleteExternalWorkspaceSessions.collectAsStateWithLifecycle()
    val todoDisplayPosition by viewModel.todoDisplayPosition.collectAsStateWithLifecycle()

    GeneralSettingsSection(
        autoRemoveStaleModels = autoRemoveStaleModels,
        onToggleAutoRemoveStaleModels = viewModel::setAutoRemoveStaleModels,
        startupSessionMode = startupSessionMode,
        onSelectStartupSessionMode = viewModel::setStartupSessionMode,
        firstByteTimeoutSec = firstByteTimeoutSec,
        onSetFirstByteTimeoutSec = viewModel::setFirstByteTimeoutSec,
        streamIdleTimeoutSec = streamIdleTimeoutSec,
        onSetStreamIdleTimeoutSec = viewModel::setStreamIdleTimeoutSec,
        maxNetworkRetries = maxNetworkRetries,
        onSetMaxNetworkRetries = viewModel::setMaxNetworkRetries,
        enterToSend = enterToSend,
        onToggleEnterToSend = viewModel::setEnterToSend,
        compactionThresholdPercent = compactionThresholdPercent,
        onSetCompactionThresholdPercent = viewModel::setCompactionThresholdPercent,
        sendFileMaxSizeMb = sendFileMaxSizeMb,
        onSetSendFileMaxSizeMb = viewModel::setSendFileMaxSizeMb,
        deleteExternalWorkspaceSessions = deleteExternalWorkspaceSessions,
        onToggleDeleteExternalWorkspaceSessions = viewModel::setDeleteExternalWorkspaceSessions,
        todoDisplayPosition = todoDisplayPosition,
        onSelectTodoDisplayPosition = viewModel::setTodoDisplayPosition
    )
}

@Composable
internal fun DefaultModelsSectionHost(
    viewModel: SettingsViewModel,
    providers: List<AIProviderConfig>,
    onOpenContextWindow: () -> Unit
) {
    val visionProviderId by viewModel.visionProviderId.collectAsStateWithLifecycle()
    val visionModel by viewModel.visionModel.collectAsStateWithLifecycle()
    val compactionProviderId by viewModel.compactionProviderId.collectAsStateWithLifecycle()
    val compactionModel by viewModel.compactionModel.collectAsStateWithLifecycle()
    val titleProviderId by viewModel.titleProviderId.collectAsStateWithLifecycle()
    val titleModel by viewModel.titleModel.collectAsStateWithLifecycle()
    val imageGenProviderId by viewModel.imageGenProviderId.collectAsStateWithLifecycle()
    val imageGenModel by viewModel.imageGenModel.collectAsStateWithLifecycle()
    val reviewerProviderId by viewModel.reviewerProviderId.collectAsStateWithLifecycle()
    val reviewerModel by viewModel.reviewerModel.collectAsStateWithLifecycle()
    val fastSubAgentModel by viewModel.fastSubAgentModel.collectAsStateWithLifecycle()
    val powerSubAgentModel by viewModel.powerSubAgentModel.collectAsStateWithLifecycle()
    val maxSubAgentRunning by viewModel.maxSubAgentRunning.collectAsStateWithLifecycle()
    val maxSubAgentTotal by viewModel.maxSubAgentTotal.collectAsStateWithLifecycle()
    val subAgentTimeoutMinutes by viewModel.subAgentTimeoutMinutes.collectAsStateWithLifecycle()
    val modelMetadata by viewModel.modelMetadata.collectAsStateWithLifecycle()
    val contextWindowOverride by viewModel.contextWindowOverride.collectAsStateWithLifecycle()

    DefaultModelsSection(
        providers = providers,
        visionProviderId = visionProviderId,
        visionModel = visionModel,
        compactionProviderId = compactionProviderId,
        compactionModel = compactionModel,
        titleProviderId = titleProviderId,
        titleModel = titleModel,
        imageGenProviderId = imageGenProviderId,
        imageGenModel = imageGenModel,
        reviewerProviderId = reviewerProviderId,
        reviewerModel = reviewerModel,
        fastSubAgentModel = fastSubAgentModel,
        powerSubAgentModel = powerSubAgentModel,
        maxSubAgentRunning = maxSubAgentRunning,
        maxSubAgentTotal = maxSubAgentTotal,
        subAgentTimeoutMinutes = subAgentTimeoutMinutes,
        modelMetadata = modelMetadata,
        onLoadMetadata = viewModel::loadAllModelMetadata,
        onSelectVisionModel = viewModel::setVisionModel,
        onClearVisionModel = viewModel::clearVisionModel,
        onSelectCompactionModel = viewModel::setCompactionModel,
        onClearCompactionModel = viewModel::clearCompactionModel,
        onSelectTitleModel = viewModel::setTitleModel,
        onClearTitleModel = viewModel::clearTitleModel,
        onSelectImageGenModel = viewModel::setImageGenModel,
        onClearImageGenModel = viewModel::clearImageGenModel,
        onSelectReviewerModel = viewModel::setReviewerModel,
        onClearReviewerModel = viewModel::clearReviewerModel,
        onSelectSubAgentModel = viewModel::setSubAgentModel,
        onClearSubAgentModel = viewModel::clearSubAgentModel,
        onChangeSubAgentReasoning = viewModel::setSubAgentReasoningEffort,
        onSetMaxSubAgentRunning = viewModel::setMaxSubAgentRunning,
        onSetMaxSubAgentTotal = viewModel::setMaxSubAgentTotal,
        onSetSubAgentTimeoutMinutes = viewModel::setSubAgentTimeoutMinutes,
        contextWindowEnabled = contextWindowOverride.enabled,
        onOpenContextWindow = onOpenContextWindow
    )
}

@Composable
internal fun McpSectionHost(
    viewModel: SettingsViewModel,
    reloading: Boolean,
    onEdit: (McpServerEntry) -> Unit
) {
    val entries by viewModel.mcpEntries.collectAsStateWithLifecycle()
    val statuses by viewModel.mcpStatuses.collectAsStateWithLifecycle()
    McpSection(
        entries = entries,
        statuses = statuses,
        reloading = reloading,
        onReload = viewModel::reloadMcp,
        onToggle = viewModel::setMcpServerEnabled,
        onEdit = onEdit,
        onDelete = viewModel::deleteMcpServer
    )
}

@Composable
internal fun SkillsSectionHost(
    viewModel: SettingsViewModel,
    pendingSkillName: String?,
    onResolvePendingSkill: (SkillUiEntry) -> Unit,
    onDelete: (SkillUiEntry) -> Unit,
    onOpenDetail: (SkillUiEntry) -> Unit
) {
    val skills by viewModel.skills.collectAsStateWithLifecycle()
    val currentProjectName by viewModel.currentProjectName.collectAsStateWithLifecycle()
    LaunchedEffect(skills, pendingSkillName) {
        val target = pendingSkillName ?: return@LaunchedEffect
        skills.firstOrNull { it.name.equals(target, ignoreCase = true) }?.let(onResolvePendingSkill)
    }
    SkillsSection(
        projectName = currentProjectName,
        entries = skills,
        onDelete = onDelete,
        onOpenDetail = onOpenDetail
    )
}

@Composable
internal fun PromptsSectionHost(
    onOpenFragment: (PromptFragment) -> Unit
) {
    val viewModel: com.aicode.feature.settings.presentation.PromptsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    PromptsSection(
        state = state,
        onOpenFragment = onOpenFragment,
        onDeleteFragment = viewModel::deleteFragment,
        onReorder = viewModel::reorderFragments,
        onToggleBuiltinDisabled = viewModel::setBuiltinDisabled
    )
}

@Composable
internal fun PromptDetailSectionHost(selectedPrompt: PromptFragment?) {
    val viewModel: com.aicode.feature.settings.presentation.PromptsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    selectedPrompt?.let { selected ->
        state.fragments.firstOrNull { it.number == selected.number }?.let { fragment ->
            PromptDetailSection(fragment = fragment)
        }
    }
}

@Composable
internal fun ExecutionChannelSectionHost(viewModel: SettingsViewModel) {
    val executionMode by viewModel.executionMode.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val defaultContainerId by viewModel.defaultContainerId.collectAsStateWithLifecycle()
    val rootProbeState by viewModel.rootProbeState.collectAsStateWithLifecycle()
    ExecutionChannelSection(
        executionMode = executionMode,
        profiles = profiles,
        activeProfileId = activeProfileId,
        defaultContainerId = defaultContainerId,
        rootProbeState = rootProbeState,
        onSwitchChannel = viewModel::switchExecutionChannel,
        onProbeRoot = viewModel::probeDeviceRoot
    )
}

@Composable
internal fun ContainerSectionHost(
    viewModel: SettingsViewModel,
    showAddSheet: Boolean,
    onDismissAddSheet: () -> Unit,
    onStopAllAndCloseTerminal: () -> Unit
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val defaultContainerId by viewModel.defaultContainerId.collectAsStateWithLifecycle()
    val osMap by viewModel.containerOsMap.collectAsStateWithLifecycle()
    val resetState by viewModel.containerReset.collectAsStateWithLifecycle()
    val remoteConnections by viewModel.remoteConnections.collectAsStateWithLifecycle()
    ContainerSection(
        profiles = profiles,
        activeProfileId = activeProfileId,
        defaultContainerId = defaultContainerId,
        osMap = osMap,
        showAddSheetExternal = showAddSheet,
        onDismissAddSheet = onDismissAddSheet,
        onSelect = viewModel::setActiveContainerProfile,
        onSetDefaultContainer = viewModel::setDefaultContainerId,
        onSaveCustom = viewModel::saveCustomContainerProfile,
        onEditCustom = viewModel::editCustomContainerProfile,
        onDeleteProfile = viewModel::deleteContainerProfile,
        onSwitchConfirmed = onStopAllAndCloseTerminal,
        onResetProfile = { profile ->
            onStopAllAndCloseTerminal()
            viewModel.resetContainer(profile)
        },
        resetState = resetState,
        onRestoreBuiltin = viewModel::restoreBuiltinAlpine,
        remoteConnections = remoteConnections
    )
}

@Composable
internal fun ContainerImageDownloadSectionHost(viewModel: SettingsViewModel, languageTag: String?) {
    val catalog by viewModel.imageCatalog.collectAsStateWithLifecycle()
    val download by viewModel.containerImageDownload.collectAsStateWithLifecycle()
    val downloadedImages by viewModel.downloadedImages.collectAsStateWithLifecycle()
    val unavailableIds by viewModel.sourceUnavailableIds.collectAsStateWithLifecycle()
    val selectedSource by viewModel.selectedImageSource.collectAsStateWithLifecycle()
    ContainerImageDownloadSection(
        catalog = catalog,
        state = download,
        downloadedImages = downloadedImages,
        sourceUnavailableIds = unavailableIds,
        selectedSourceName = viewModel.sourceDisplayName(selectedSource, languageTag),
        onDownload = { entry -> viewModel.startContainerImageDownload(entry, selectedSource) },
        onCancel = viewModel::cancelContainerImageDownload,
        onImport = viewModel::importDownloadedImage,
        onDelete = viewModel::deleteDownloadedImage
    )
}

@Composable
internal fun ProxySectionHost(viewModel: SettingsViewModel) {
    val config by viewModel.proxyConfig.collectAsStateWithLifecycle()
    val testState by viewModel.proxyTestState.collectAsStateWithLifecycle()
    ProxySection(
        config = config,
        testState = testState,
        onTestProxy = viewModel::testProxy,
        onSetEnabled = viewModel::setProxyEnabled,
        onSetType = viewModel::setProxyType,
        onSetHost = viewModel::setProxyHost,
        onSetPort = viewModel::setProxyPort,
        onSetUsername = viewModel::setProxyUsername,
        onSetPassword = viewModel::setProxyPassword,
        onSetNoProxy = viewModel::setProxyNoProxy
    )
}

@Composable
internal fun LogSectionHost(viewModel: SettingsViewModel) {
    val logLevel by viewModel.logLevel.collectAsStateWithLifecycle()
    val logState by viewModel.logViewerState.collectAsStateWithLifecycle()
    LogSection(
        current = logLevel,
        onSelect = viewModel::setLogLevel,
        state = logState,
        onSelectFile = viewModel::selectLogFile,
        onClearFilter = { viewModel.refreshLogs(filterServerName = null) },
        onRefresh = { viewModel.refreshLogs(silent = true) }
    )
}

@Composable
internal fun PermissionsSectionHost(viewModel: SettingsViewModel) {
    val authLevel by viewModel.commandAuthLevel.collectAsStateWithLifecycle()
    val timeout by viewModel.commandReviewTimeoutSeconds.collectAsStateWithLifecycle()
    val rules by viewModel.highRiskRules.collectAsStateWithLifecycle()
    PermissionsSection(
        commandAuthLevel = authLevel,
        commandReviewTimeoutSeconds = timeout,
        highRiskRules = rules,
        onSelectAuthLevel = viewModel::setCommandAuthLevel,
        onSelectReviewTimeout = viewModel::setCommandReviewTimeoutSeconds,
        onToggleHighRiskRule = { rule, enabled -> viewModel.updateHighRiskRule(rule.copy(enabled = enabled)) },
        onSaveHighRiskRule = { rule, isNew ->
            if (isNew) viewModel.addHighRiskRule(rule) else viewModel.updateHighRiskRule(rule)
        },
        onDeleteHighRiskRule = { viewModel.removeHighRiskRule(it.id) },
        onResetHighRiskRules = viewModel::resetHighRiskRules
    )
}

@Composable
internal fun ContextWindowSectionHost(viewModel: SettingsViewModel) {
    val state by viewModel.contextWindowOverride.collectAsStateWithLifecycle()
    ContextWindowSection(
        state = state,
        onToggleEnabled = viewModel::setContextWindowOverrideEnabled,
        onSave = viewModel::saveContextWindowOverride
    )
}

@Composable
internal fun AppPermissionsSectionHost() {
    val viewModel: ShizukuViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    AppPermissionsSection(
        shizukuState = state,
        onRequestShizukuPermission = viewModel::requestPermission,
        onOpenShizuku = viewModel::openShizukuApp,
        onRefreshShizuku = viewModel::refresh
    )
}

@Composable
internal fun BackgroundRunSectionHost(viewModel: SettingsViewModel) {
    val keepalive by viewModel.keepaliveEnabled.collectAsStateWithLifecycle()
    val screenOn by viewModel.screenOnEnabled.collectAsStateWithLifecycle()
    val sound by viewModel.agentSoundEnabled.collectAsStateWithLifecycle()
    BackgroundRunSection(
        keepaliveEnabled = keepalive,
        onToggleKeepalive = viewModel::setKeepaliveEnabled,
        screenOnEnabled = screenOn,
        onToggleScreenOn = viewModel::setScreenOnEnabled,
        agentSoundEnabled = sound,
        onToggleAgentSound = viewModel::setAgentSoundEnabled
    )
}

@Composable
internal fun TokenStatsSectionHost(viewModel: SettingsViewModel) {
    val state by viewModel.tokenStats.collectAsStateWithLifecycle()
    TokenStatsSection(
        state = state,
        onSelectPeriod = viewModel::setTokenStatsPeriod,
        onSelectPage = viewModel::setTokenStatsPage,
        onSelectProviderPage = viewModel::setProviderStatsPage,
        onSelectModelPage = viewModel::setModelStatsPage,
        onSelectFilterProvider = viewModel::setFilterProviderId,
        onSelectFilterModel = viewModel::setFilterModel,
        onClearFilters = viewModel::clearTokenStatsFilters
    )
}

@Composable
internal fun AboutSectionHost(viewModel: SettingsViewModel) {
    val enabled by viewModel.updateCheckEnabled.collectAsStateWithLifecycle()
    val channel by viewModel.updateCheckChannel.collectAsStateWithLifecycle()
    AboutSection(
        updateCheckEnabled = enabled,
        updateCheckChannel = channel,
        onToggleUpdateCheck = viewModel::setUpdateCheckEnabled,
        onSelectChannel = viewModel::setUpdateCheckChannel,
        onCheckUpdate = { viewModel.checkUpdate(manual = true) }
    )
}

@Composable
internal fun SkillEditorSectionHost(
    viewModel: SettingsViewModel,
    initial: SkillUiEntry?,
    defaultScope: SkillScope,
    onSave: (com.aicode.feature.agent.domain.skill.SkillForm, SkillScope) -> Unit,
    onSaved: (String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val saveState by viewModel.skillSaveState.collectAsStateWithLifecycle()
    SkillEditorScreen(
        initial = initial,
        saveState = saveState,
        defaultScope = defaultScope,
        onSave = onSave,
        onSaved = onSaved,
        onNavigateBack = onNavigateBack
    )
}

@Composable
internal fun PromptEditorSectionHost(
    number: Int?,
    onSaved: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val viewModel: com.aicode.feature.settings.presentation.PromptsViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val fragment = number?.let { selected -> state.fragments.firstOrNull { it.number == selected } }
    PromptEditorScreen(
        isNew = number == null,
        initialNumber = number ?: 0,
        initialTitle = fragment?.title.orEmpty(),
        initialDescription = fragment?.description.orEmpty(),
        initialContent = fragment?.body.orEmpty(),
        initialScope = fragment?.source ?: com.aicode.feature.agent.domain.prompt.PromptFragmentSource.GLOBAL,
        hasWorkspace = state.hasWorkspace,
        onSave = { savedNumber, title, scope, content ->
            viewModel.saveFragment(savedNumber, title, scope, content, previousNumber = number)
            onSaved()
        },
        onNavigateBack = onNavigateBack
    )
}

@Composable
internal fun SkillDeleteDialogHost(
    viewModel: SettingsViewModel,
    target: SkillUiEntry,
    onDismiss: () -> Unit
) {
    val deletingSkill by viewModel.skillDeleting.collectAsStateWithLifecycle()
    val deleting = deletingSkill == target.name
    var deleteStarted by remember(target) { mutableStateOf(false) }
    LaunchedEffect(deleteStarted, deleting) {
        if (deleteStarted && !deleting) onDismiss()
    }
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(stringResource(R.string.skills_delete_confirm_title)) },
        text = { Text(stringResource(R.string.skills_delete_confirm_message, target.name)) },
        confirmButton = {
            TextButton(
                enabled = !deleting,
                onClick = {
                    deleteStarted = true
                    viewModel.deleteSkill(target.name, target.scope)
                }
            ) {
                if (deleting) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.common_delete))
            }
        },
        dismissButton = {
            TextButton(enabled = !deleting, onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

@Composable
internal fun SourceSelectionSheetHost(
    viewModel: SettingsViewModel,
    languageTag: String?,
    onDismiss: () -> Unit
) {
    val options by viewModel.imageSourceOptions.collectAsStateWithLifecycle()
    val selected by viewModel.selectedImageSource.collectAsStateWithLifecycle()
    SourceSelectionSheet(
        options = options.map { it to viewModel.sourceDisplayName(it, languageTag) },
        selected = selected,
        onSelected = viewModel::setImageSource,
        onDismiss = onDismiss
    )
}
