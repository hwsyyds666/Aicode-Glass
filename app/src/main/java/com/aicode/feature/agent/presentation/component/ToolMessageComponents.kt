package com.aicode.feature.agent.presentation.component

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Brand
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.rememberLifecyclePulseAlpha
import com.aicode.core.theme.semanticColors
import com.aicode.feature.agent.domain.session.SessionUseCase
import com.aicode.feature.agent.presentation.AgentUIMessage
import com.aicode.core.ui.ExpandableChevronIcon
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Cpu
import compose.icons.feathericons.Database
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Search
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Tool
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aicode.R

internal val DiffAddBg: Color
    @Composable get() = MaterialTheme.semanticColors.diffAddBg

internal val DiffAddText: Color
    @Composable get() = MaterialTheme.semanticColors.diffAdd

internal val DiffRemoveBg: Color
    @Composable get() = MaterialTheme.semanticColors.diffRemoveBg

internal val DiffRemoveText: Color
    @Composable get() = MaterialTheme.semanticColors.diffRemove

internal const val DIFF_COLLAPSE_THRESHOLD = 20
internal const val TOOL_SECTION_LINE_LIMIT = 20

/** 静态（非 live）工具输出渲染的字符上限：视口限高 240dp 本就看不完超长输出，但
 *  Text 会按全文做布局测量（O 字符数）——50KB 输出的卡片滚入视口时单次布局可达 10ms+，
 *  是快速滑动聊天记录时组合尖峰/掉帧的直接来源。超限截断展示，完整内容走原始记录。 */
private const val TOOL_SECTION_CHAR_LIMIT = 16_000
private const val TOOL_ERROR_SUMMARY_INPUT_LIMIT = 4_096
private const val TOOL_ERROR_SUMMARY_CHAR_LIMIT = 140
private val JSON_ERROR_MESSAGE = Regex(""""message"\s*:\s*"((?:\\.|[^"\\])*)"""")

/** 工具「指令 / 结果」展开窗口的最大高度：超出后在窗口内滚动，不再无限撑高。 */
private val ToolSectionMaxHeight = 240.dp

/** 工具窗口底部渐隐遮罩高度（仅当内容还可继续下滚时显示）。 */
private val ToolSectionFadeHeight = 24.dp

/**
 * 工具消息（DSH 扁平行）：一行「工具图标 + 工具名 + 路径 + 增删统计 + 箭头」，行上方一条
 * 1px 细线做分隔，不再套描边卡片；点击展开查看「指令 / 结果」，或对 editFile / writeFile
 * 展开为白底描边的差异卡（头部路径 + 复制，页脚增删统计）。
 *
 * 状态不再只用颜色表达：运行中图标脉冲、失败额外挂一个警示图标，读屏另有状态语义。
 * [liveOutput] 非空时进入「实时输出」模式：显示逐行累积输出。
 *
 * 展开态由 [expandedOverride] + [onExpandedChange] 受控（宿主持久化，见
 * [com.aicode.feature.agent.presentation.AIAgentViewModel.toolExpansionOverrides]）：
 * 从前用 `remember(message.id)` 存在这里，item 滚出视口被 LazyColumn 回收、或全屏路由
 * 把整棵聊天组合 dispose 后就会缩回默认态。null 表示用户还没手动开关过——此时**默认收起**，
 * 所有工具（含差异卡、待办卡）一视同仁，看细节要点开。
 */
@Composable
internal fun ToolMessageBody(
    message: AgentUIMessage,
    liveOutput: String? = null,
    expandedOverride: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onToggle: (() -> Unit)? = null
) {
    val streaming = liveOutput != null
    val running = message.isToolRunning(liveOutput)
    val edit = if (!running && !message.isError &&
        (message.toolName == "editFile" || message.toolName == "writeFile")
    ) {
        remember(message.id, message.content) { parseEditDiff(message.content) }
    } else null

    val resultText = if (!running) {
        remember(message.id, message.content) { formatToolResult(message.content) }
    } else null
    val argHint = remember(message.toolArgs) { toolArgHint(message.toolArgs) }
    val argsFull = remember(message.toolArgs) { formatToolArgs(message.toolArgs) }

    val todoData = if (message.toolName == "todo" && !running && !message.isError) {
        remember(message.id, message.content) { parseTodoResult(message.content) }
    } else null
    val webSearchData = if (message.toolName == "websearch" && !running && !message.isError) {
        remember(message.id, message.content) { parseWebSearchResult(message.content) }
    } else null
    // 后台任务/子代理完成通知：搭车在本次工具结果里送给 AI 的，同时常显给用户看。
    val notifications = if (!running) {
        remember(message.id, message.content) { parseToolNotifications(message.content) }
    } else emptyList()

    // 执行中也可折叠/展开（如 bash 刷屏时可收起只看标题行），无论当前是否有输出；无输出时折叠态无内容，但保持可点击与箭头一致
    val hasLiveOutput = !liveOutput.isNullOrBlank()
    val expandable = streaming || (!running && (edit != null || !resultText.isNullOrBlank() || !argsFull.isNullOrBlank()
            || (todoData != null && todoData.items.isNotEmpty()) || webSearchData != null))
    // 工具调用一律默认收起（差异卡、待办卡也不例外）：要不要看细节由用户点开，
    // 手动开关过（expandedOverride 非 null）就以用户的选择为准。展开态由宿主保管，
    // 因此滚出视口、切页返回都不会再丢。
    val effectiveExpanded = expandedOverride == true

    val toolLabel = message.toolName ?: stringResource(R.string.common_tool)
    // 文件相关工具：从结构化 diff 或工具参数里取路径，统一按「工具名 + 路径 + 文件名」展示
    val filePath = if (edit != null) {
        edit.path
    } else {
        remember(message.toolArgs) { extractFilePathArg(message.toolArgs) }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ChatStyle.toolRowMinHeight)
                .then(
                    if (expandable) Modifier.clickable {
                        onExpandedChange?.invoke(!effectiveExpanded)
                        onToggle?.invoke()
                    } else Modifier
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolStatusIcon(running = running, isError = message.isError, toolName = message.toolName)
            Spacer(Modifier.width(Spacing.sm))
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!filePath.isNullOrBlank()) {
                    Text(
                        text = toolLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    // 路径段（可省略）+ 文件名段（永远完整，优先级最高）：等宽字，与代码卡头部一致
                    val monoLabel = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    )
                    val pathDir = filePath.substringBeforeLast('/')
                    if (pathDir.isNotEmpty()) {
                        Text(
                            text = pathDir + "/",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = monoLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                    Text(
                        text = filePath.substringAfterLast('/'),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = monoLabel,
                        maxLines = 1
                    )
                } else {
                    Text(
                        text = toolLabel,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!argHint.isNullOrBlank()) {
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = argHint,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
            }
            if (running) {
                // 运行中的这条：行尾用统一的涟漪场景文案说明「正在做什么」。工具名在开始执行前
                // 就已到达（AgentEvent.ToolCallStarted），所以不必停在笼统的「执行中」；
                // 折叠态也看得见，不必展开才知道它在跑。
                Spacer(Modifier.width(Spacing.sm))
                RippleText(text = stringResource(toolRunningLabelRes(message.toolName)))
            }
            if (edit != null) {
                DiffStat(added = edit.added, removed = edit.removed)
                Spacer(Modifier.width(Spacing.sm))
            }
            if (todoData != null && todoData.total > 0) {
                Text(
                    text = "${todoData.completed}/${todoData.total}",
                    color = if (todoData.completed == todoData.total) DiffAddText
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(Spacing.sm))
            }
            if (expandable) {
                ExpandableChevronIcon(
                    expanded = effectiveExpanded,
                    contentDescription = if (effectiveExpanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
                    tint = Brand.IconGray,
                    size = 18.dp
                )
            }
        }
        if (streaming) {
            // 展开态与落库卡片同构：先「指令」（工具参数），再「结果」（实时输出尾部）；
            // 折叠态只保留标题行。「还在跑」由标题行行尾的涟漪场景文案表达，不在这里重复一遍。
            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column {
                    if (!argsFull.isNullOrBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_instruction), content = argsFull)
                    }
                    if (hasLiveOutput) {
                        val truncated = remember(liveOutput) { liveOutput.takeLastLines(TOOL_SECTION_LINE_LIMIT) }
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_result), content = truncated, live = true)
                    }
                }
            }
        } else {
            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column(
                    modifier = Modifier.pointerInput(message.id) {
                        detectDoubleTapToCollapse {
                            onExpandedChange?.invoke(false)
                            onToggle?.invoke()
                        }
                    }
                ) {
                if (todoData != null && todoData.items.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.xs))
                    TodoCard(items = todoData.items)
                } else if (webSearchData != null) {
                    Spacer(Modifier.height(Spacing.xs))
                    WebSearchResultCard(result = webSearchData)
                } else if (edit != null) {
                    // 差异卡：头部给路径与「复制」，页脚给增删统计（DSH 那块白底描边卡）
                    Spacer(Modifier.height(Spacing.xs))
                    // 降级提示必须显式：差异过大（不显示逐行对比）/ 快照被跳过（无法回滚）都不能静默
                    val diffSkippedHint = if (edit.hunks.any { it.diffSkipped }) {
                        stringResource(R.string.tool_diff_skipped_hint)
                    } else {
                        null
                    }
                    val warnings = listOfNotNull(diffSkippedHint, edit.warning)
                    if (warnings.isNotEmpty()) {
                        Text(
                            text = warnings.joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(bottom = Spacing.xs)
                        )
                    }
                    ChatCodeCard(
                        title = edit.path.ifBlank { null },
                        copyText = edit.hunks.joinToString("\n") { it.diff },
                        footer = stringResource(
                            R.string.tool_changed_files_summary,
                            edit.added,
                            edit.removed,
                            if (edit.path.isBlank()) 0 else 1
                        )
                    ) {
                        Column {
                            edit.hunks.forEach { h -> DiffView(diff = h.diff, startLine = h.startLine) }
                            Spacer(Modifier.height(Spacing.xs))
                        }
                    }
                } else {
                    if (!argsFull.isNullOrBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_instruction), content = argsFull)
                    }
                    if (!resultText.isNullOrBlank()) {
                        Spacer(Modifier.height(Spacing.sm))
                        ToolSection(label = stringResource(R.string.tool_result), content = resultText)
                    }
                }
                }
            }
        }
        if (notifications.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.sm))
            notifications.forEach { ToolNotificationRow(it) }
        }
        // 文件列表：工具结束后常显在消息底部，一行一个文件，点击用系统 app 打开。
        if (!running && message.attachments.isNotEmpty()) {
            val opener = LocalAttachmentOpener.current
            MessageAttachmentList(
                attachments = message.attachments,
                onClick = { opener.open(it) }
            )
        }
    }
}

internal data class ToolNotificationInfo(val summary: String, val succeeded: Boolean, val isMessage: Boolean = false)

/**
 * 从工具结果 transport JSON 顶层的 `notifications` 字段提取搭车通知（后台任务/子代理完成）。
 * 该字段由 [com.aicode.feature.agent.domain.workflow.StatefulAgentWorkflow] 在 AI 忙碌时注入。
 */
internal fun parseToolNotifications(raw: String): List<ToolNotificationInfo> {
    val array = parseToolTransport(raw.withoutToolStatusPrefix())?.get("notifications") as? JsonArray
        ?: return emptyList()
    return array.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val summary = (obj["summary"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val status = (obj["status"] as? JsonPrimitive)?.contentOrNull
        ToolNotificationInfo(
            summary = summary,
            succeeded = status == "completed",
            isMessage = status == "message"
        )
    }
}

/** 工具卡片底部的搭车通知提示条：状态点 + 摘要。 */
@Composable
private fun ToolNotificationRow(info: ToolNotificationInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    when {
                        info.isMessage -> MaterialTheme.colorScheme.primary
                        info.succeeded -> MaterialTheme.semanticColors.success
                        else -> MaterialTheme.colorScheme.error
                    }
                )
        )
        Text(
            text = info.summary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun String.takeLastLines(maxLines: Int): String {
    if (maxLines <= 0 || isEmpty()) return ""
    var seen = 0
    for (i in lastIndex downTo 0) {
        if (this[i] == '\n' && ++seen == maxLines) {
            return substring(i + 1)
        }
    }
    return this
}

/**
 * 双击折叠检测：detectTapGestures 的 awaitFirstDown 默认 requireUnconsumed=true，
 * 而展开区内容包在 SelectionContainer / clickable 里会消费 down，导致首击被忽略、
 * 双击永远不触发。这里首击不要求未消费，只做「快速连点两次」判定。
 */
private suspend fun PointerInputScope.detectDoubleTapToCollapse(onDoubleTap: () -> Unit) {
    val viewConfig = viewConfiguration
    awaitEachGesture {
        val firstDown = awaitFirstDown(requireUnconsumed = false)
        // 两次点击期间若发生明显位移（用户在滑动），放弃双击判定
        if (!awaitTapOrSwipe(firstDown.id, firstDown.position, viewConfig.touchSlop)) return@awaitEachGesture
        val secondDown = withTimeoutOrNull(viewConfig.doubleTapTimeoutMillis) {
            awaitFirstDown(requireUnconsumed = false)
        } ?: return@awaitEachGesture
        if (secondDown.uptimeMillis - firstDown.uptimeMillis < viewConfig.doubleTapMinTimeMillis) {
            return@awaitEachGesture
        }
        if ((secondDown.position - firstDown.position).getDistance() > viewConfig.touchSlop) {
            return@awaitEachGesture
        }
        if (!awaitTapOrSwipe(secondDown.id, secondDown.position, viewConfig.touchSlop)) return@awaitEachGesture
        onDoubleTap()
    }
}

/** 等待指定指针抬起；期间若位移超过 slop（发生滑动），返回 false。 */
private suspend fun AwaitPointerEventScope.awaitTapOrSwipe(
    pointerId: PointerId,
    downPosition: Offset,
    slop: Float
): Boolean {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        val change = event.changes.firstOrNull { it.id == pointerId } ?: continue
        if ((change.position - downPosition).getDistance() > slop) return false
        if (!change.pressed) return true
    }
}

/**
 * 工具状态图标：按工具类型给出字形（文件 / 终端 / 搜索 / 任务…），并按状态着色——
 * 运行中脉冲、失败整枚图标转红、成功回到中性色（满屏绿色会抢注意力）。
 *
 * 失败不再额外挂一枚警示图标：行首图标格保持一枚字形，状态只由颜色与读屏语义表达，
 * 免得同一行出现两个图标、行首位置还随成败跳动。色弱用户靠 [statusLabel]（读屏）分辨成败。
 */
@Composable
internal fun ToolStatusIcon(running: Boolean, isError: Boolean, toolName: String?) {
    val tint = when {
        isError -> DiffRemoveText
        running -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val pulseAlpha = if (running) {
        rememberLifecyclePulseAlpha(label = "tool-status-pulse", minAlpha = 0.35f)
    } else null
    val statusLabel = stringResource(
        when {
            running -> R.string.chat_tool_running
            isError -> R.string.chat_tool_failed
            else -> R.string.chat_tool_succeeded
        }
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = toolIcon(toolName),
            contentDescription = statusLabel,
            tint = tint,
            modifier = Modifier
                // 与思考行同一格宽：两行的图标中心与后续文字左边缘才对得齐
                .size(ChatStyle.rowIconSize)
                // Read animation state in the layer block so the pulse only invalidates drawing,
                // not the whole tool row on every animation frame.
                .graphicsLayer { alpha = pulseAlpha?.value ?: 1f }
        )
    }
}

/** 工具名字形映射：键与 [com.aicode.feature.agent.domain.tool.ToolRegistry] 注册名一致（比对时忽略大小写）。 */
private fun toolIcon(toolName: String?): ImageVector = when (toolName?.lowercase()) {
    "editfile", "writefile", "generateimage" -> FeatherIcons.Edit3
    "readfile", "list", "sendfile", "viewimage" -> FeatherIcons.FileText
    "search", "websearch", "webfetch", "browser" -> FeatherIcons.Search
    "bash", "terminal" -> FeatherIcons.Terminal
    "todo" -> FeatherIcons.Check
    "task" -> FeatherIcons.Cpu
    "memory" -> FeatherIcons.Database
    else -> FeatherIcons.Tool
}

/**
 * 工具执行中的场景文案：把工具名归成用户一眼能懂的「正在做什么」，供统一忙碌指示器
 * （[AgentBusyIndicator] / [RippleText]）使用。
 *
 * 键与 [toolIcon] 一致（工具注册名，忽略大小写）。归类原则是「用户视角」而不是「实现视角」：
 * 读文件与列目录都算「正在读取文件」，联网搜索与抓网页都算「正在联网搜索」。工具名在执行前
 * 就已到达（`AgentEvent.ToolCallStarted`），所以文案能跟着实际动作走，而不必停在「正在思考」。
 * MCP 动态注册工具、自定义工具等未归类的，一律回落 [R.string.chat_status_calling_tool]。
 */
@StringRes
internal fun toolRunningLabelRes(toolName: String?): Int = when (toolName?.lowercase()) {
    "editfile", "writefile" -> R.string.chat_status_editing_file
    "readfile", "list", "sendfile", "viewimage" -> R.string.chat_status_reading_file
    "search", "websearch", "webfetch", "browser" -> R.string.chat_status_searching_web
    "bash", "terminal" -> R.string.chat_status_running_command
    "generateimage" -> R.string.chat_status_generating_image
    "todo" -> R.string.chat_status_updating_todo
    "task" -> R.string.chat_status_starting_subagent
    "memory" -> R.string.chat_status_reading_memory
    else -> R.string.chat_status_calling_tool
}

/**
 * 工具是否仍在执行：实时输出中，或结果仍是 pending 占位。
 * [AIChatPanel] 判定工具调用分组是否要保持展开时复用这一条，避免两处判定漂移。
 */
internal fun AgentUIMessage.isToolRunning(liveOutput: String?): Boolean =
    liveOutput != null ||
        content.startsWith(SessionUseCase.PENDING_TOOL_MARKER) ||
        content.startsWith(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)

/**
 * 「N 次工具调用」分组头：DSH 把一轮任务里连续的工具调用折成一行，**默认收起**，
 * 点一下手动展开/收起（运行中也不自动弹开）。
 * 布局对齐思考行：左侧工具图标（锤子/施工） + 中间调用计数 + 折叠箭头。
 *
 * [running] 为真时给「N 次工具调用」这行文案本身走涟漪高光，表示这批调用还在跑。
 * 不再另挂三个跳动的点，也不在这里重复场景文案（「正在编辑文件」这类说的是**具体哪个工具**，
 * 展开后由每条工具行的运行状态给出，见 [ToolMessageBody]）。
 */
@Composable
internal fun ToolCallGroupHeader(
    count: Int,
    running: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ChatStyle.toolRowMinHeight)
            .clip(ChatStyle.panelShape)
            .clickable(
                // 无障碍：点这一行会展开还是收起，读屏能念出来（展开状态本身由 count 文本表达）
                onClickLabel = stringResource(
                    if (expanded) R.string.common_collapse_action else R.string.common_expand
                ),
                onClick = onToggle
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Construction,
            contentDescription = null,
            tint = Brand.IconGray,
            modifier = Modifier.size(ChatStyle.rowIconSize)
        )
        Spacer(Modifier.width(Spacing.sm))
        // 权重挂在外层占位，涟漪画在文字自身的尺寸上：光带只扫「N 次工具调用」这几个字，
        // 不会在整行空白里慢慢爬（见 Modifier.rippleHighlight）。
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            RippleText(
                text = stringResource(R.string.chat_tool_calls_count, count),
                highlight = if (running) Color.White else null
            )
        }
        ExpandableChevronIcon(
            expanded = expanded,
            contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
            tint = Brand.IconGray,
            size = 18.dp
        )
    }
}

/** 展开区的一段带小标题的内容块（如「指令」「结果」）：弱底等宽小面板，超出限高后在窗口内滚动。 */
@Composable
internal fun ToolSection(label: String, content: String, live: Boolean = false) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(2.dp))

    val scrollState = rememberScrollState()
    var followLive by remember { mutableStateOf(true) }
    var displayedContent by remember { mutableStateOf(content) }
    LaunchedEffect(scrollState, live) {
        if (!live) return@LaunchedEffect
        scrollState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) followLive = false
        }
    }
    LaunchedEffect(scrollState, live) {
        if (!live) return@LaunchedEffect
        snapshotFlow { scrollState.isScrollInProgress to scrollState.canScrollForward }
            .collect { (scrolling, canScrollForward) ->
                if (!scrolling && !canScrollForward) followLive = true
            }
    }
    LaunchedEffect(content, live, followLive, scrollState.isScrollInProgress) {
        if (!live || (followLive && !scrollState.isScrollInProgress)) displayedContent = content
    }
    val fadeColor = MaterialTheme.colorScheme.background
    // 静态超长输出截断（见 TOOL_SECTION_CHAR_LIMIT）；live 已由调用方 takeLastLines 控制。
    val renderContent = remember(content, live) {
        if (!live && content.length > TOOL_SECTION_CHAR_LIMIT) {
            content.take(TOOL_SECTION_CHAR_LIMIT) + "\n…（输出超长，已截断展示）"
        } else {
            content
        }
    }
    Box {
        ChatMonoPanel(
            modifier = Modifier
                .heightIn(max = ToolSectionMaxHeight)
                .nestedScroll(rememberBoundNestedScrollConnection(scrollState))
                .verticalScroll(scrollState)
        ) {
            SelectionContainer {
                Text(
                    text = if (live) displayedContent else renderContent,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    )
                )
            }
        }
        // 底部渐隐：只有还能继续往下滚时才盖一层，提示“下面还有内容”
        if (scrollState.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(ToolSectionFadeHeight)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, fadeColor)))
            )
        }
    }
}

/** 增删统计胶囊：绿色「+N」与红色「−M」。 */
@Composable
internal fun DiffStat(added: Int, removed: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (added > 0) {
            Text(
                text = "+$added",
                color = DiffAddText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        if (added > 0 && removed > 0) Spacer(Modifier.width(Spacing.xs))
        if (removed > 0) {
            Text(
                text = "−$removed",
                color = DiffRemoveText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * 彩色行级差异视图
 */
@Composable
internal fun DiffView(diff: String, startLine: Int) {
    val lines = remember(diff) { diff.split("\n") }
    val collapsible = lines.size > DIFF_COLLAPSE_THRESHOLD
    var expanded by remember(diff) { mutableStateOf(false) }
    val visibleLines = if (collapsible && !expanded) lines.take(DIFF_COLLAPSE_THRESHOLD) else lines

    val mono = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace
    )
    val removeCount = lines.count { it.startsWith("-") }
    val addCount = lines.count { it.startsWith("+") }
    val maxLineNo = startLine + lines.size - removeCount - addCount + maxOf(removeCount, addCount)
    val gutterChars = maxOf(2, maxLineNo.toString().length)
    val gutterColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Column(modifier = Modifier.fillMaxWidth()) {
        // 横向滚动容器在外层、文本选择在内层：滚动手势优先，
        // 避免 SelectionContainer 偶发抢占左右滑动（选择模式激活后拖动被选词消费）。
        // 底色与圆角由外层 ChatCodeCard 提供，这里只负责行渲染与横滑。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            SelectionContainer {
                Column {
                    var oldLineNo = startLine
                    var newLineNo = startLine
                    visibleLines.forEach { line ->
                        val marker = line.firstOrNull()
                        val (bg, fg) = when (marker) {
                            '+' -> DiffAddBg to DiffAddText
                            '-' -> DiffRemoveBg to DiffRemoveText
                            else -> Color.Transparent to MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val lineNo = when (marker) {
                            '-' -> oldLineNo++
                            '+' -> newLineNo++
                            else -> { val n = newLineNo; oldLineNo++; newLineNo++; n }
                        }
                        val gutter = lineNo.toString().padStart(gutterChars)
                        val styled = buildAnnotatedString {
                            withStyle(SpanStyle(color = gutterColor)) {
                                append(gutter)
                                append("  ")
                            }
                            withStyle(SpanStyle(color = fg)) {
                                append(line.ifEmpty { " " })
                            }
                        }
                        Text(
                            text = styled,
                            style = mono,
                            softWrap = false,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(bg)
                                .padding(horizontal = Spacing.sm, vertical = 1.dp)
                        )
                    }
                }
            }
        }
        if (collapsible) {
            DiffExpandToggle(
                expanded = expanded,
                hiddenCount = lines.size - DIFF_COLLAPSE_THRESHOLD,
                onToggle = { expanded = !expanded }
            )
        }
    }
}

/** 长差异的页脚切换 */
@Composable
internal fun DiffExpandToggle(expanded: Boolean, hiddenCount: Int, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(onClick = onToggle)
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        ExpandableChevronIcon(
            expanded = expanded,
            contentDescription = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.common_expand),
            tint = Brand.IconGray,
            size = 16.dp
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = if (expanded) stringResource(R.string.common_collapse_action) else stringResource(R.string.tool_expand_remaining, hiddenCount),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

/** editFile 单处编辑的差异片段。 */
internal data class EditHunk(
    val startLine: Int,
    val diff: String,
    /** true = 该处编辑过大已跳过 LCS，diff 为「整段视为新增」的降级呈现。 */
    val diffSkipped: Boolean = false
)

/** editFile 结果中解析出的结构化差异 */
internal data class EditDiff(
    val path: String,
    val added: Int,
    val removed: Int,
    val hunks: List<EditHunk>,
    /** 快照被体积/空间限制跳过时工具结果里的 warning（未创建快照，无法回滚）。 */
    val warning: String? = null
)

/**
 * 从持久化的 TOOL 内容中解析 editFile / writeFile 的结构化差异
 */
internal fun parseEditDiff(content: String): EditDiff? {
    val dataObj = extractToolDataObject(content)
    if (dataObj != null) {
        return parseEditDiffObject(dataObj)
    }

    val start = content.indexOf('{')
    val end = content.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return runCatching {
        parseEditDiffObject(Json.parseToJsonElement(content.substring(start, end + 1)).jsonObject)
    }.getOrNull()
}

private fun parseEditDiffObject(obj: JsonObject): EditDiff? {
    val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: ""
    val added = obj["added_lines"]?.jsonPrimitive?.intOrNull ?: 0
    val removed = obj["removed_lines"]?.jsonPrimitive?.intOrNull ?: 0

    val hunks = obj["hunks"]?.jsonArray?.mapNotNull { el ->
        val ho = el.jsonObject
        val d = ho["diff"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        EditHunk(
            startLine = ho["start_line"]?.jsonPrimitive?.intOrNull ?: 1,
            diff = d,
            diffSkipped = ho["diff_skipped"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    } ?: run {
        val d = obj["diff"]?.jsonPrimitive?.contentOrNull ?: return null
        listOf(EditHunk(startLine = obj["start_line"]?.jsonPrimitive?.intOrNull ?: 1, diff = d))
    }
    if (hunks.isEmpty()) return null
    return EditDiff(
        path = path,
        added = added,
        removed = removed,
        hunks = hunks,
        warning = obj["warning"]?.jsonPrimitive?.contentOrNull
    )
}

/**
 * 把落库的原始工具结果清洗成可读文本
 */
internal fun formatToolResult(raw: String): String {
    val s = raw.withoutToolStatusPrefix()
    parseToolTransport(s)?.let { obj ->
        return when (obj["status"]?.jsonPrimitive?.contentOrNull) {
            "error" -> obj["message"]?.jsonPrimitive?.contentOrNull ?: s
            "success", "partial" -> formatToolData(obj["data"]) ?: s
            else -> s
        }
    }

    when {
        s.startsWith("Error(") -> {
            val msgIdx = s.indexOf("message=")
            if (msgIdx >= 0) {
                var body = s.substring(msgIdx + "message=".length)
                val codeIdx = body.lastIndexOf(", code=")
                body = if (codeIdx >= 0) body.substring(0, codeIdx) else body.removeSuffix(")")
                return body.trim()
            }
        }
        s.startsWith("Success(data=") -> {
            val inner = s.removePrefix("Success(data=").removeSuffix(")")
            return formatJsonData(inner) ?: inner.trim()
        }
        s.startsWith("Partial(data=") -> {
            var inner = s.removePrefix("Partial(data=")
            val msgIdx = inner.lastIndexOf(", message=")
            inner = if (msgIdx >= 0) inner.substring(0, msgIdx) else inner.removeSuffix(")")
            return formatJsonData(inner) ?: inner.trim()
        }
    }
    return s
}

/**
 * 失败工具的折叠摘要：只解析有界输入，并限制输出长度，避免长 stderr/工具结果在列表构建时造成额外卡顿。
 * 错误原文仍保留在展开详情里；这里仅用于任务头上始终可见的短摘要。
 */
internal fun summarizeToolFailure(raw: String): String? {
    val cleaned = raw.withoutToolStatusPrefix()
    if (cleaned.isBlank()) return null

    val summary = if (cleaned.length <= TOOL_ERROR_SUMMARY_INPUT_LIMIT) {
        formatToolResult(cleaned)
    } else {
        val bounded = cleaned.take(TOOL_ERROR_SUMMARY_INPUT_LIMIT)
        val encodedMessage = JSON_ERROR_MESSAGE.find(bounded)?.groupValues?.getOrNull(1)
        encodedMessage?.let { encoded ->
            runCatching { Json.parseToJsonElement("\"$encoded\"").jsonPrimitive.contentOrNull }
                .getOrNull()
        } ?: bounded.substringBefore('\n')
    }
    val line = summary.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
        ?: summary.trim()
    val compact = line.filter { it == '\t' || !it.isISOControl() }.replace('\t', ' ').trim()
    if (compact.isBlank()) return null
    return if (compact.length > TOOL_ERROR_SUMMARY_CHAR_LIMIT) {
        compact.take(TOOL_ERROR_SUMMARY_CHAR_LIMIT - 1).trimEnd() + "…"
    } else compact
}

private fun parseToolTransport(raw: String): JsonObject? {
    return runCatching {
        val obj = Json.parseToJsonElement(raw.trim()).jsonObject
        if (obj["status"] != null) obj else null
    }.getOrNull()
}

private fun extractToolDataObject(raw: String): JsonObject? {
    return (parseToolTransport(raw)?.get("data") as? JsonObject)
}

private fun formatToolData(data: JsonElement?): String? {
    return when (data) {
        is JsonPrimitive -> data.contentOrNull ?: data.toString()
        is JsonObject -> {
            val main = data["content"] ?: data["output"] ?: data["stdout"] ?: data["text"]
            val mainStr = (main as? JsonPrimitive)?.contentOrNull
            val warning = (data["warning"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            if (mainStr != null && warning != null) return listOf("⚠ $warning", mainStr).joinToString("\n")
            mainStr ?: data.entries.joinToString("\n") { (k, v) ->
                val vv = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
                "$k: $vv"
            }
        }
        null -> null
        else -> data.toString()
    }
}

internal fun String.withoutToolStatusPrefix(): String = trim()
    .removePrefix(SessionUseCase.LEGACY_STOPPED_TOOL_MARKER)
    .removePrefix(SessionUseCase.LEGACY_PENDING_TOOL_MARKER)
    .removePrefix(SessionUseCase.PENDING_TOOL_MARKER)
    .trim()

/**
 * 把 `data=` 里的 JsonElement 文本渲染成可读结果
 */
internal fun formatJsonData(jsonStr: String): String? = runCatching {
    when (val el = Json.parseToJsonElement(jsonStr.trim())) {
        is JsonPrimitive -> formatToolData(el) ?: jsonStr.trim()
        is JsonObject -> formatToolData(el) ?: jsonStr.trim()
        else -> jsonStr.trim()
    }
}.getOrNull()

/** 把传入参数 JSON 列成 `key: value` 多行 */
internal fun formatToolArgs(argsJson: String?): String? {
    if (argsJson.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(argsJson).jsonObject
        if (obj.isEmpty()) return null
        obj.entries.joinToString("\n") { (k, v) ->
            val vv = (v as? JsonPrimitive)?.contentOrNull ?: v.toString()
            "$k: $vv"
        }
    }.getOrNull() ?: argsJson.trim()
}

/** 标题行内联的参数摘要 */
internal fun toolArgHint(argsJson: String?): String? {
    if (argsJson.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(argsJson).jsonObject
        val preferred = listOf("command", "cmd", "path", "file_path", "file", "query", "pattern", "url", "name")
        val v = preferred.firstNotNullOfOrNull { obj[it] } ?: obj.values.firstOrNull()
        val str = (v as? JsonPrimitive)?.contentOrNull ?: v?.toString()
        str?.replace("\n", " ")?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

/** 从工具参数 JSON 中提取文件路径（readFile/editFile/writeFile 的 path 参数）。 */
private fun extractFilePathArg(argsJson: String?): String? {
    if (argsJson.isNullOrBlank()) return null
    return runCatching {
        val obj = Json.parseToJsonElement(argsJson).jsonObject
        listOf("path", "file_path", "file").firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNull
        }
    }.getOrNull()
}
