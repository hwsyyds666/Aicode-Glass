package com.aicode.feature.agent.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.GlassSurface
import com.aicode.feature.agent.domain.model.TodoItem
import com.aicode.feature.agent.domain.model.TodoStatus
import com.aicode.core.ui.ExpandableChevronIcon
import com.styropyr0.prismal.sources.PrismalGlassLayer
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp

/**
 * 待办任务常驻面板（两种形态）：
 * - 消息栏上方：内嵌在输入区列里的普通卡片（默认，改造前既有行为）；
 * - 标题栏下方：悬浮玻璃浮层（传入 [glassBackdrop] 启用），与标题栏共用同一层背景采样。
 *
 * 共性：
 * - 仅在当前会话有待办项时显示；
 * - 支持折叠为单行紧凑摘要与展开查看完整列表；
 * - 记住各会话的展开/折叠状态；
 * - 弹窗/键盘叠加时支持联动强制收起。
 */
@Composable
fun TodoDashboardBar(
    items: List<TodoItem>,
    sessionId: String,
    modifier: Modifier = Modifier,
    forceCollapse: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    /** 背景采样层；非 null 时容器切换为液态玻璃（标题栏下方浮层形态）。 */
    glassBackdrop: PrismalGlassLayer? = null,
    /** 背景亮度（0..1），玻璃模式专用。 */
    glassLuminance: () -> Float = { 0.5f },
    /** 玻璃上的前景色：随背景亮度自适应；Unspecified 时退回色板色。 */
    glassContentColor: Color = Color.Unspecified
) {
    if (items.isEmpty()) return

    // 按会话隔离记忆展开状态，新会话默认收起
    var isExpanded by rememberSaveable(sessionId) { mutableStateOf(false) }
    val effectiveExpanded = isExpanded && !forceCollapse

    LaunchedEffect(effectiveExpanded) {
        onExpandedChange(effectiveExpanded)
    }

    val totalCount = items.size
    val completedCount = items.count { it.status == TodoStatus.COMPLETED }
    val inProgressItem = items.firstOrNull { it.status == TodoStatus.IN_PROGRESS }

    val hasGlass = glassBackdrop != null
    val titleColor = if (hasGlass && glassContentColor != Color.Unspecified) {
        glassContentColor
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val secondaryColor = if (hasGlass && glassContentColor != Color.Unspecified) {
        glassContentColor.copy(alpha = 0.72f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 8.dp)
        ) {
            // 单行标题栏（点击切换折叠/展开）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = FeatherIcons.CheckSquare,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = stringResource(R.string.todo_dashboard_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.Bold,
                    color = titleColor
                )
                Spacer(Modifier.width(Spacing.sm))

                // 进度胶囊或当前进行中的任务简述
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (completedCount == totalCount && totalCount > 0) {
                        MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    } else {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    }
                ) {
                    Text(
                        text = if (completedCount == totalCount && totalCount > 0) {
                            stringResource(R.string.todo_dashboard_all_done)
                        } else {
                            stringResource(R.string.todo_dashboard_progress, completedCount, totalCount)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = secondaryColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                if (!effectiveExpanded && inProgressItem != null) {
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = inProgressItem.subject,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }

                ExpandableChevronIcon(
                    expanded = effectiveExpanded,
                    contentDescription = if (effectiveExpanded) {
                        stringResource(R.string.common_collapse_action)
                    } else {
                        stringResource(R.string.common_expand)
                    },
                    tint = secondaryColor,
                    size = 16.dp
                )
            }

            // 展开时展示待办列表（平滑淡入展开、向上卷折淡出）
            AnimatedVisibility(
                visible = effectiveExpanded,
                enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                exit = fadeOut(tween(140)) + shrinkVertically(tween(180))
            ) {
                Column {
                    Spacer(Modifier.height(Spacing.xs))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items.forEach { todo ->
                            TodoItemRow(item = todo.toParsedItem())
                        }
                    }
                }
            }
        }
    }

    if (glassBackdrop != null) {
        // 标题栏下方浮层：液态玻璃容器，与标题栏同圆角、同采样层
        GlassSurface(
            backdrop = glassBackdrop,
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xs),
            cornerRadius = 20.dp,
            blurRadius = 8.dp,
            tintAlpha = 0.10f,
            luminance = glassLuminance
        ) {
            body()
        }
    } else {
        // 消息栏上方：普通卡片（保持改造前观感）
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xs),
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            body()
        }
    }
}

private fun TodoItem.toParsedItem(): ParsedTodoItem = ParsedTodoItem(
    id = id,
    subject = subject,
    description = description,
    status = status.name.lowercase(),
    priority = priority,
    order = order
)
