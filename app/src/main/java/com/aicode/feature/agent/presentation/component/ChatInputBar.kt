package com.aicode.feature.agent.presentation.component

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.aicode.R
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.rememberImeBottomInset
import com.aicode.feature.onboarding.domain.OnboardingStep
import com.aicode.feature.onboarding.presentation.onboardingTarget
import com.aicode.feature.agent.domain.command.SlashCommand
import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.model.ReasoningEffort
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.permission.PermissionChoice
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.mode.PlanApprovalRequest
import com.aicode.feature.agent.presentation.AgentUIState
import com.aicode.feature.agent.presentation.QueuedRequest
import com.aicode.feature.settings.domain.model.AIProviderConfig
import com.aicode.feature.settings.domain.model.ModelMetadata
import com.aicode.feature.settings.domain.model.modelMetadataKey
import com.aicode.feature.settings.domain.model.ProviderDashboardState
import com.aicode.feature.workspace.presentation.WorkspaceViewModel
import com.aicode.feature.workspace.presentation.component.WorkspaceIconButton
import com.aicode.core.ui.ExpandableChevronIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.ArrowUp
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Map
import compose.icons.feathericons.Maximize
import compose.icons.feathericons.MessageCircle
import compose.icons.feathericons.Play
import compose.icons.feathericons.Minimize
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Square
import compose.icons.feathericons.Shield
import kotlinx.coroutines.launch

/** 输入框区域蒙版高度：盖住圆角容器，滚动内容滑入时被渐变遮罩；随键盘（IME）上移。 */
private val INPUT_BAR_MASK_HEIGHT = 110.dp

/** 输入框高度上下限：超过上限后文本在框内滚动，右上角露出「展开」按钮。与 TextField 的 heightIn 一致。 */
private val INPUT_FIELD_MIN_HEIGHT = 44.dp
private val INPUT_FIELD_MAX_HEIGHT = 140.dp

/**
 * M3 无标签 TextField 的默认 contentPadding（上下左右各 16dp）。
 * 文本是否超过输入框高度要先用相同约束实测一遍，反推文本可视区域得靠这个值。
 */
private val INPUT_FIELD_CONTENT_PADDING = 16.dp

@Composable
internal fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    /** 回车键是否直接发送：开启后 IME 回车键变为「发送」，关闭则回车换行（默认）。 */
    enterToSend: Boolean = false,
    onStop: () -> Unit,
    isBusy: Boolean,
    workspaceViewModel: WorkspaceViewModel?,
    hasRunningSessions: () -> Boolean,
    onSwitchWorkspaceConfirmed: () -> Unit = {},
    activeProvider: AIProviderConfig?,
    providers: List<AIProviderConfig>,
    modelMetadata: Map<String, ModelMetadata>,
    onSelectModel: (String, String) -> Unit,
    currentMode: AgentMode,
    onToggleMode: (AgentMode) -> Unit,
    reasoningEffort: ReasoningEffort,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    pendingAttachments: List<PendingUploadAttachment>,
    onRemoveAttachment: (Int) -> Unit,
    canUploadFiles: Boolean,
    canUploadImages: Boolean,
    onUploadFile: () -> Unit,
    onUploadImage: () -> Unit,
    onTakePhoto: () -> Unit,
    slashCommands: List<SlashCommand> = emptyList(),
    /**
     * 斜杠菜单是否已被用户主动收起（Esc / 系统返回键 / 点击消息列表）。
     * 输入不再是 `/` 开头（含删回光杆 `/`）时由调用方复位，保证下次输入能重新弹出。
     */
    slashMenuDismissed: Boolean = false,
    onSlashMenuDismiss: () -> Unit = {},
    queuedRequests: List<QueuedRequest> = emptyList(),
    onRemoveQueued: (String) -> Unit = {},
    onMoveQueued: (Int, Int) -> Unit = { _, _ -> },
    onEditQueued: (QueuedRequest) -> Unit = {},
    onInterjectQueued: (String) -> Unit = {},
    tokenProgress: Float = 0f,
    tokenEstimated: Boolean = false,
    dashboardState: ProviderDashboardState = ProviderDashboardState.Idle,
    onRefreshDashboard: () -> Unit = {},
    onRefreshDashboardByButton: () -> Unit = {},
    onDashboardExpandedChange: (Boolean) -> Unit = {},
    todoItems: List<TodoItem> = emptyList(),
    /** 玻璃背景采样层（与聊天页标题栏共用同一层）；null 时降级为普通容器。 */
    glassBackdrop: com.styropyr0.prismal.sources.PrismalGlassLayer? = null,
    glassLuminance: () -> Float = { 0.5f },
    sessionId: String = "",
    onTodoExpandedChange: (Boolean) -> Unit = {},
    forceCollapseDashboard: Boolean = false,
    /** 消息列表正在滚动时内容区淡出到 40%，停止滚动恢复；用于长列表阅读时降低底部干扰（同 git 页 tab 栏）。 */
    isScrolling: Boolean = false,
    forceOpenModelSheet: Boolean = false,
    onSelectModelInOnboarding: (() -> Unit)? = null,
    onModelSheetDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val hasContent = value.isNotBlank() || pendingAttachments.isNotEmpty()
    val canSend = hasContent
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showFullScreenInput by remember { mutableStateOf(false) }
    // TextField 的 String 重载在整串替换时会保留旧 selection 下标（点快捷命令补全时光标会停在中间），
    // 这里自持 TextFieldValue：外部改值时把光标移到末尾。
    var inputFieldValue by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    LaunchedEffect(value) {
        if (inputFieldValue.text != value) {
            inputFieldValue = TextFieldValue(value, TextRange(value.length))
        }
    }
    // —— 斜杠命令 / 技能菜单 ——
    // 触发条件见 shouldShowSlashMenu：仅 "/" 位于输入开头（输入路径、网址、中间的 "/"
    // 不触发；输入法组合输入期间只按当前文本过滤，不改文本、不丢字）。
    // 键盘导航状态：外接键盘 ↑↓ 移动、Enter/Tab 选中、Esc 收起（见 slashKeyModifier）。
    val slashMenuState = remember { SlashCommandMenuState() }
    // 最近使用（内存态：项目暂无持久化，重启后清空）；选中时置顶，各组内排最前。
    var recentSlashNames by remember { mutableStateOf<List<String>>(emptyList()) }
    val showSlashMenu = shouldShowSlashMenu(value, isBusy, slashMenuDismissed, slashCommands)
    // 查询词："/" 之后、首个空格之前的片段；同时匹配名称与描述、不区分大小写，
    // 名称前缀匹配排在包含匹配之前；组内最近用过的排最前，其余按名称排序。
    val slashQuery = value.drop(1).substringBefore(' ').trim()
    val slashGroups = remember(slashCommands, slashQuery, recentSlashNames, showSlashMenu) {
        if (showSlashMenu) {
            groupSlashCommands(filterSlashCommands(slashCommands, slashQuery), recentSlashNames)
        } else {
            emptyList()
        }
    }
    val slashVisibleCommands = slashGroups.flatMap { it.items }
    // 选中：把 "/名称"（acceptsArgs 时补一个空格）填入输入框并收起菜单，
    // 保持「填入而非直接发送」的现有行为。
    fun selectSlashCommand(command: SlashCommand) {
        recentSlashNames = recentSlashNames.withRecentSlashCommand(command.name)
        onSlashMenuDismiss()
        onValueChange(if (command.acceptsArgs) "/${command.name} " else "/${command.name}")
    }
    // 外接键盘导航：挂在菜单与输入框的共同容器上，onPreviewKeyEvent 在 TextField
    // 拿到键事件之前拦截——软键盘（输入法）不产生键事件，不受影响；只有物理键盘触发。
    val slashKeyModifier = if (showSlashMenu) {
        Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (event.key) {
                Key.DirectionUp -> {
                    slashMenuState.moveUp(slashVisibleCommands.size)
                    true
                }
                Key.DirectionDown -> {
                    slashMenuState.moveDown(slashVisibleCommands.size)
                    true
                }
                Key.Enter, Key.NumPadEnter, Key.Tab -> {
                    val command = slashVisibleCommands.getOrNull(slashMenuState.selectedIndex)
                    if (command != null) {
                        selectSlashCommand(command)
                        true
                    } else {
                        false
                    }
                }
                Key.Escape -> {
                    onSlashMenuDismiss()
                    true
                }
                else -> false
            }
        }
    } else {
        Modifier
    }
    // 菜单高度上限：输入框（玻璃栏）上沿到窗口顶部可用空间的 50%，与约 8 行取小。
    // 输入框上沿用 onGloballyPositioned 实测窗口坐标：软键盘弹出（imeInset 顶高底栏）、
    // 横竖屏切换、折叠屏 / 分屏尺寸变化都会重新布局，自动按当前可视区域重算，不写死高度。
    var inputTopInWindowPx by remember { mutableIntStateOf(0) }
    val slashMenuMaxHeight = with(LocalDensity.current) {
        val rowCapPx = SLASH_MENU_ROW_HEIGHT.toPx() * SLASH_MENU_MAX_ROWS
        val capPx = if (inputTopInWindowPx > 0) {
            minOf(inputTopInWindowPx * 0.5f, rowCapPx)
        } else {
            rowCapPx // 首帧未实测时先用「约 8 行」兜底
        }
        capPx.toDp()
    }

    // 文本是否已经超出输入框高度（超出后框内滚动，展开按钮才有意义）。
    // 按换行符数硬编码阈值在长行自动折行时会判断错，这里按 TextField 的实际宽度实测文本高度。
    val textMeasurer = rememberTextMeasurer()
    val inputDensity = LocalDensity.current
    var inputFieldWidthPx by remember { mutableStateOf(0) }
    val inputTextStyle = MaterialTheme.typography.bodyLarge
    val inputOverflows = remember(value, inputFieldWidthPx, inputDensity, inputTextStyle) {
        val horizontalPaddingPx = with(inputDensity) { INPUT_FIELD_CONTENT_PADDING.toPx() }
        val contentWidthPx = inputFieldWidthPx - (horizontalPaddingPx * 2).toInt()
        val maxTextHeightPx = with(inputDensity) {
            (INPUT_FIELD_MAX_HEIGHT - INPUT_FIELD_CONTENT_PADDING * 2).toPx()
        }
        contentWidthPx > 0 && value.isNotEmpty() && textMeasurer.measure(
            text = AnnotatedString(value),
            style = inputTextStyle,
            constraints = Constraints(maxWidth = contentWidthPx)
        ).size.height > maxTextHeightPx
    }

    Surface(
        color = Color.Transparent,
        modifier = modifier.fillMaxWidth()
    ) {
        val imeInset = rememberImeBottomInset()
        // 滚动弱化：内容区（slash 菜单/排队面板/数据面板/输入框本体）整体淡出到 40%，蒙版渐变保持不透明。
        val contentAlpha by animateFloatAsState(
            targetValue = if (isScrolling) 0.4f else 1f,
            animationSpec = tween(200),
            label = "inputbar-content-alpha"
        )
        // 渐变终点固定在蒙版可视高度内：若跟随整个 Box（含 imeInset 被键盘拉长的部分），
        // 键盘弹起时可见区域只占渐变前段，alpha 被摊薄到几乎透明——看起来像没有蒙版。
        val maskGradientEndY = with(LocalDensity.current) { INPUT_BAR_MASK_HEIGHT.toPx() }
        Box(
            modifier = Modifier.fillMaxWidth()
        ) {
            // 半透明渐变蒙版：盖住输入框区域 + 导航栏（手势小白条）区域，滚动内容滑入时被遮罩
            // （能看见但看不清）；高度含 IME inset 随键盘上移，渐变在 INPUT_BAR_MASK_HEIGHT 内完成，
            // 之下为纯色。注意 IME padding 不能加在外层 Box 上——align(BottomCenter) 的子项
            // 对齐在 padding 内部，会漏掉导航栏那条区域。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(INPUT_BAR_MASK_HEIGHT + imeInset)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.98f)
                            ),
                            startY = 0f,
                            endY = maskGradientEndY
                        )
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { alpha = contentAlpha }
                    .then(slashKeyModifier)
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.md)
                    .padding(bottom = imeInset)
            ) {
            if (showSlashMenu) {
                // 斜杠命令 / 技能菜单：LazyColumn 内部滚动，高度上限由 slashMenuMaxHeight
                // 按当前可视区域动态计算（输入框上沿 50% 与约 8 行取小），不再撑满屏幕。
                SlashCommandMenu(
                    groups = slashGroups,
                    state = slashMenuState,
                    maxHeight = slashMenuMaxHeight,
                    onSelect = ::selectSlashCommand,
                    modifier = Modifier.padding(bottom = Spacing.sm)
                )
            }

            if (queuedRequests.isNotEmpty()) {
                QueuedRequestPanel(
                    queuedRequests = queuedRequests,
                    sessionId = sessionId,
                    forceCollapse = forceCollapseDashboard,
                    onRemoveQueued = onRemoveQueued,
                    onMoveQueued = onMoveQueued,
                    onEditQueued = onEditQueued,
                    onInterjectQueued = onInterjectQueued
                )
            }

            if (todoItems.isNotEmpty()) {
                TodoDashboardBar(
                    items = todoItems,
                    sessionId = sessionId,
                    forceCollapse = forceCollapseDashboard,
                    onExpandedChange = onTodoExpandedChange
                )
            }

            if (activeProvider != null && activeProvider.dashboardScriptPath.isNotBlank()) {
                ProviderDashboardBar(
                    provider = activeProvider,
                    state = dashboardState,
                    onRefresh = onRefreshDashboard,
                    onRefreshByButton = onRefreshDashboardByButton,
                    onExpandedChange = onDashboardExpandedChange,
                    forceCollapse = forceCollapseDashboard
                )
            }

            // 工具栏与输入框合并成一个统一的玻璃 Surface（原来背景/描边就在这层）：
            // 不再有多张彼此独立的卡片，模式 / 模型 / 附件 / 发送与文本域同属一块玻璃。
            com.aicode.core.ui.GlassSurface(
                backdrop = glassBackdrop,
                modifier = Modifier
                    .fillMaxWidth()
                    // 实测输入框（玻璃栏）上沿在窗口中的位置：驱动斜杠菜单高度上限，
                    // 键盘弹出 / 旋转 / 分屏时自动重算
                    .onGloballyPositioned { inputTopInWindowPx = it.positionInWindow().y.toInt() },
                cornerRadius = Radius.lg,
                blurRadius = 12.dp,
                tintAlpha = 0.14f,
                luminance = glassLuminance
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            ) {
                PendingAttachmentPreviewList(
                    attachments = pendingAttachments,
                    onRemoveAttachment = onRemoveAttachment
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top
                ) {
                    TextField(
                        value = inputFieldValue,
                        onValueChange = { new ->
                            inputFieldValue = new
                            onValueChange(new.text)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = INPUT_FIELD_MIN_HEIGHT, max = INPUT_FIELD_MAX_HEIGHT)
                            .onSizeChanged { inputFieldWidthPx = it.width },
                        placeholder = {
                            Text(
                                stringResource(if (isBusy) R.string.chat_queue_hint else R.string.chat_input_placeholder),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        enabled = true,
                        keyboardOptions = if (enterToSend) {
                            KeyboardOptions(imeAction = ImeAction.Send)
                        } else {
                            KeyboardOptions(imeAction = ImeAction.Default)
                        },
                        keyboardActions = if (enterToSend) {
                            KeyboardActions(onSend = { onSend() })
                        } else {
                            KeyboardActions.Default
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent
                        )
                    )

                    if (inputOverflows) {
                        IconButton(
                            onClick = { showFullScreenInput = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                FeatherIcons.Maximize,
                                contentDescription = stringResource(R.string.chat_input_expand),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AgentModeChip(
                            mode = currentMode,
                            onClick = {
                                // 三档循环：构建 → 计划 → 提问（对齐 Claude Code 的 Build/Plan/Ask）
                                val nextMode = when (currentMode) {
                                    AgentMode.BUILD -> AgentMode.PLAN
                                    AgentMode.PLAN -> AgentMode.ASK
                                    AgentMode.ASK -> AgentMode.BUILD
                                }
                                onToggleMode(nextMode)
                            }
                        )
                        Spacer(Modifier.width(Spacing.xs))

                        ModelIconButton(
                            provider = activeProvider,
                            providers = providers,
                            modelMetadata = modelMetadata,
                            onSelectModel = onSelectModel,
                            forceOpenSheet = forceOpenModelSheet,
                            onSelectSuccessInOnboarding = onSelectModelInOnboarding,
                            onSheetDismiss = onModelSheetDismiss,
                            modifier = Modifier.onboardingTarget(OnboardingStep.OPEN_MODEL_PICKER)
                        )

                        if (workspaceViewModel != null) {
                            WorkspaceIconButton(
                                viewModel = workspaceViewModel,
                                hasRunningSessions = hasRunningSessions,
                                onSwitchConfirmed = onSwitchWorkspaceConfirmed,
                                modifier = Modifier.size(36.dp),
                                iconSize = 20.dp
                            )
                        }

                        // 元数据未命中时（如中转站改了模型名）退回全部档位由用户自选：
                        // 这类模型可能强制开启推理，隐藏按钮会导致无法调档。
                        val availableEfforts = remember(activeProvider, modelMetadata) {
                            activeProvider?.let { provider ->
                                modelMetadata[modelMetadataKey(provider.id, provider.effectiveModel)]?.reasoningEffortOptions
                                    ?.let { ReasoningEffort.fromValues(it) }
                            }.orEmpty().ifEmpty { ReasoningEffort.entries }
                        }
                        ReasoningEffortSelector(
                            effort = reasoningEffort,
                            availableEfforts = availableEfforts,
                            onChange = onReasoningEffortChange,
                            enabled = !isBusy
                        )
                    }
                    UploadIconButton(
                        enabled = !isBusy,
                        icon = FeatherIcons.Plus,
                        contentDescription = stringResource(R.string.chat_add_attachment),
                        onClick = { showAttachmentSheet = true }
                    )
                    SendButton(canSend = canSend, hasContent = hasContent, isBusy = isBusy, tokenProgress = tokenProgress, tokenEstimated = tokenEstimated, onSend = onSend, onStop = onStop)
                }
            }
            }
        }
        }
    }

    if (showAttachmentSheet) {
        AttachmentSheet(
            canUploadFiles = canUploadFiles && !isBusy,
            canUploadImages = canUploadImages && !isBusy,
            onUploadFile = {
                showAttachmentSheet = false
                onUploadFile()
            },
            onUploadImage = {
                showAttachmentSheet = false
                onUploadImage()
            },
            onTakePhoto = {
                showAttachmentSheet = false
                onTakePhoto()
            },
            onDismiss = { showAttachmentSheet = false }
        )
    }

    if (showFullScreenInput) {
        FullScreenInputDialog(
            value = value,
            onValueChange = onValueChange,
            placeholder = stringResource(if (isBusy) R.string.chat_queue_hint else R.string.chat_input_placeholder),
            onDismiss = { showFullScreenInput = false }
        )
    }
}

/**
 * 全屏输入框：底部输入框被高度上限截断、框内滚动时，点右上角「展开」进入这里，
 * 用整屏编辑同一份草稿（内容实时回写，收起后输入框里就是最新文本）。
 */
@Composable
private fun FullScreenInputDialog(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            // 不设则 dialog window 是 WRAP_CONTENT，内容铺不满屏
            usePlatformDefaultWidth = false,
            // 不设则内容被系统栏 inset 挤进安全区，底色在状态栏处断开、露出后面的聊天界面
            decorFitsSystemWindows = false
        )
    ) {
        // dialog 有自己的 window：系统栏图标色要单独按当前主题设置，否则浅色主题下状态栏图标压在浅底上看不见。
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        val lightBackground = MaterialTheme.colorScheme.background.luminance() > 0.5f
        SideEffect {
            dialogWindow?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightBackground
                    isAppearanceLightNavigationBars = lightBackground
                }
            }
        }

        val imeInset = rememberImeBottomInset()
        val focusRequester = remember { FocusRequester() }
        // 打开即聚焦，键盘不停，可以接着往下写。
        LaunchedEffect(Unit) { focusRequester.requestFocus() }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(bottom = imeInset)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.chat_input_fullscreen_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(
                            FeatherIcons.Minimize,
                            contentDescription = stringResource(R.string.common_collapse_action),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text(
                            placeholder,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
            }
        }
    }
}

@Composable
internal fun UploadIconButton(
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(36.dp)
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
internal fun SendButton(
    canSend: Boolean,
    hasContent: Boolean,
    isBusy: Boolean,
    tokenProgress: Float,
    tokenEstimated: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    // AI 工作中：输入框为空显示停止按钮；有内容显示橙色发送（消息排队发送）；空闲时按常规发送按钮
    val showStop = isBusy && !hasContent
    val clickable = showStop || canSend
    val buttonColor = when {
        showStop -> MaterialTheme.colorScheme.error
        isBusy -> Brand.Orange
        canSend -> MaterialTheme.semanticColors.success
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    // 前景色相对按钮底色而非主题：深色主题下的亮绿底用主题 onSurface（近白）会看不清。
    val iconTint = when {
        !clickable -> MaterialTheme.colorScheme.onSurfaceVariant
        buttonColor.luminance() > 0.5f -> Color(0xFF1C1C1E)
        else -> Color.White
    }
    val arcColor = buttonColor.copy(alpha = 0.85f)
    val clampedProgress = tokenProgress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .padding(Spacing.xs)
            .size(44.dp),
        contentAlignment = Alignment.Center
    ) {
        if (clampedProgress > 0f) {
            val usageLabel = stringResource(
                if (tokenEstimated) R.string.chat_context_usage_estimated else R.string.chat_context_usage,
                (clampedProgress * 100).toInt()
            )
            Canvas(
                modifier = Modifier
                    .size(44.dp)
                    // 上下文用量只靠这圈弧表达，给读屏补上百分比。
                    .semantics { contentDescription = usageLabel }
            ) {
                val stroke = 3.dp.toPx()
                val arcSize = size.minDimension - stroke
                val topLeft = androidx.compose.ui.geometry.Offset(stroke / 2f, stroke / 2f)
                drawArc(
                    color = arcColor,
                    startAngle = -90f,
                    sweepAngle = 360f * clampedProgress,
                    useCenter = false,
                    topLeft = topLeft,
                    size = androidx.compose.ui.geometry.Size(arcSize, arcSize),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .clickable(enabled = clickable, onClick = if (showStop) onStop else onSend),
            contentAlignment = Alignment.Center
        ) {
            if (showStop) {
                Icon(
                    FeatherIcons.Square,
                    contentDescription = stringResource(R.string.chat_stop),
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Icon(
                    FeatherIcons.ArrowUp,
                    contentDescription = stringResource(R.string.chat_send),
                    tint = iconTint,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
internal fun ToolPermissionPanel(
    request: PendingToolPermission,
    onChoice: (PermissionChoice) -> Unit,
    modifier: Modifier = Modifier,
    sessionTitle: String = "",
    forceCollapse: Boolean = false,
    isScrolling: Boolean = false
) {
    var expanded by remember { mutableStateOf(true) }
    // 余额面板展开时同帧收起本面板，避免叠加
    val effectiveExpanded = expanded && !forceCollapse

    // 滚动弱化：用户上下滑动列表时，悬浮授权弹窗跟随淡化为 40%，避免遮挡用户查看背后消息；停止滑动后平滑恢复
    val panelAlpha by animateFloatAsState(
        targetValue = if (isScrolling) 0.4f else 1f,
        animationSpec = tween(durationMillis = 200),
        label = "permission-panel-alpha"
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs)
            .graphicsLayer { alpha = panelAlpha },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant
        )
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Text(
                            text = request.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = request.toolName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    if (sessionTitle.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.chat_perm_session_label, sessionTitle),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(28.dp)
                ) {
                    ExpandableChevronIcon(
                        expanded = effectiveExpanded,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 18.dp
                    )
                }
            }

            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column {
                    Spacer(Modifier.height(Spacing.sm))
                    // AI 命令审查器的高危提示（「自主执行」挡位审查拒绝/不确定转人工时）：醒目置顶展示
                    if (!request.reviewNote.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(Radius.sm),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = FeatherIcons.AlertTriangle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(Spacing.xs))
                                Text(
                                    text = stringResource(R.string.chat_perm_review_note, request.reviewNote.orEmpty()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Spacer(Modifier.height(Spacing.xs))
                    }
                    Surface(
                        shape = RoundedCornerShape(Radius.sm),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        border = androidx.compose.foundation.BorderStroke(
                            0.5.dp,
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SelectionContainer {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                                    .heightIn(max = 160.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = request.summary,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (request.details.isNotBlank()) {
                                    Spacer(Modifier.height(Spacing.xs))
                                    Text(
                                        text = request.details,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    val canRemember = request.rememberablePatterns.isNotEmpty()
                    val rememberLabel = when {
                        !canRemember -> request.rememberDisabledReason ?: stringResource(R.string.chat_perm_single_use_desc)
                        request.rememberablePatterns == listOf("*") -> stringResource(R.string.chat_perm_always_tool_desc)
                        else -> stringResource(R.string.chat_perm_always_prefix) + request.rememberablePatterns.joinToString("、")
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = rememberLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        AgentActionButton(
                            text = stringResource(R.string.chat_perm_deny),
                            onClick = { onChoice(PermissionChoice.REJECT) },
                            modifier = Modifier.weight(1f),
                            tone = AgentActionTone.Danger
                        )
                        AgentActionButton(
                            text = stringResource(R.string.chat_perm_always_allow),
                            onClick = { onChoice(PermissionChoice.ALWAYS) },
                            modifier = Modifier.weight(1f),
                            enabled = canRemember,
                            tone = AgentActionTone.Neutral
                        )
                        AgentActionButton(
                            text = stringResource(R.string.common_allow),
                            onClick = { onChoice(PermissionChoice.ONCE) },
                            modifier = Modifier.weight(1f),
                            tone = AgentActionTone.Success
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun StatusBanner(state: AgentUIState) {
    // 退出动画期间 state 已不是 Error，when 会走 else 渲染空内容，横幅的淡出就变成了瞬间消失；
    // 拿最后一次的错误文本兜住整段退场。
    val lastError = rememberLastNonNull((state as? AgentUIState.Error)?.message)
    AnimatedVisibility(
        visible = state is AgentUIState.Error,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        lastError?.let { ErrorBubble(message = it) }
    }
}

/**
 * 失败错误气泡：风格对齐 ToolPermissionPanel / AskUserQuestionPanel（内联面板，与输入框同宽且两侧留距）。
 * 默认折叠为一行标题（请求失败），点击展开完整错误详情；右上角可一键复制。
 */
@Composable
private fun ErrorBubble(message: String) {
    var expanded by remember(message) { mutableStateOf(false) }
    var copied by remember(message) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val copyScope = rememberCoroutineScope()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    FeatherIcons.AlertCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_error_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    copyScope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("error", message)))
                        copied = true
                    }
                }) {
                    Icon(
                        if (copied) FeatherIcons.Check else FeatherIcons.Copy,
                        contentDescription = if (copied) stringResource(R.string.chat_copied) else stringResource(R.string.chat_copy),
                        tint = if (copied) MaterialTheme.colorScheme.primary else Brand.IconGray,
                        modifier = Modifier.size(16.dp)
                    )
                }
                ExpandableChevronIcon(
                    expanded = expanded,
                    contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    size = 18.dp
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column {
                    Spacer(Modifier.height(Spacing.sm))
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 160.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 会话模式芯片：点击在 构建 → 计划 → 提问 三档间循环（对齐 Claude Code 的 Build/Plan/Ask）。
 * 中文标签 + 模式图标；颜色区分：构建=成功绿、计划=主题蓝、提问=紫。
 */
@Composable
private fun AgentModeChip(
    mode: AgentMode,
    onClick: () -> Unit
) {
    val chipColor = when (mode) {
        AgentMode.BUILD -> MaterialTheme.semanticColors.success
        AgentMode.PLAN -> MaterialTheme.colorScheme.primaryContainer
        AgentMode.ASK -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val chipTextColor = when (mode) {
        AgentMode.BUILD -> MaterialTheme.semanticColors.onSuccess
        AgentMode.PLAN -> MaterialTheme.colorScheme.onPrimaryContainer
        AgentMode.ASK -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    val chipIcon = when (mode) {
        AgentMode.BUILD -> FeatherIcons.Play
        AgentMode.PLAN -> FeatherIcons.Map
        AgentMode.ASK -> FeatherIcons.MessageCircle
    }
    val chipLabel = stringResource(
        when (mode) {
            AgentMode.BUILD -> R.string.chat_mode_build
            AgentMode.PLAN -> R.string.chat_mode_plan
            AgentMode.ASK -> R.string.chat_mode_ask
        }
    )
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = chipColor,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = chipIcon,
                contentDescription = null,
                tint = chipTextColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = chipLabel,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    color = chipTextColor
                )
            )
        }
    }
}

/**
 * 计划审查面板：AI 从 PLAN 模式切回 BUILD 时弹出，展示计划摘要供用户批准或继续反馈。
 * 风格与 ToolPermissionPanel / AskUserQuestionPanel 一致。
 */
@Composable
internal fun PlanApprovalPanel(
    state: PlanApprovalRequest,
    onApprove: () -> Unit,
    onRefine: () -> Unit,
    forceCollapse: Boolean = false
) {
    var expanded by remember { mutableStateOf(true) }
    // 余额面板展开时同帧收起本面板，避免叠加顶开输入框
    val effectiveExpanded = expanded && !forceCollapse

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(Radius.md),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.tertiary)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.chat_plan_completed),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                ExpandableChevronIcon(
                    expanded = effectiveExpanded,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 18.dp
                )
            }

            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column {
                    if (state.reason.isNotBlank()) {
                        Spacer(Modifier.height(Spacing.xs))
                        SelectionContainer {
                            Column(
                                modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = state.reason,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = stringResource(R.string.chat_plan_approval_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        AgentActionButton(
                            text = stringResource(R.string.chat_continue_feedback),
                            onClick = onRefine,
                            modifier = Modifier.weight(1f),
                            tone = AgentActionTone.Neutral
                        )
                        AgentActionButton(
                            text = stringResource(R.string.chat_approve_and_implement),
                            onClick = onApprove,
                            modifier = Modifier.weight(1f),
                            tone = AgentActionTone.Success
                        )
                    }
                }
            }
        }
    }
}