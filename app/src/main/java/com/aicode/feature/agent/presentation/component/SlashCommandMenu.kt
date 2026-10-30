package com.aicode.feature.agent.presentation.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.command.SlashCommand
import com.aicode.feature.agent.domain.command.SlashCommandKind

/** 菜单单行高度：同时作为最小触摸高度（满足 48dp 可点区域）。 */
internal val SLASH_MENU_ROW_HEIGHT = 48.dp

/** 菜单高度上限的「约 8 行」：与调用方实测的可用空间 50% 取小。 */
internal val SLASH_MENU_MAX_ROWS = 8

/** 最近使用记录的最大条数（内存态，见 [ChatInputBar] 的 recentSlashNames）。 */
private const val SLASH_RECENT_MAX = 20

/**
 * 斜杠命令菜单的键盘导航状态：外接键盘 ↑↓ 在此移动选中项；
 * Enter/Tab 选中、Esc 收起由 ChatInputBar 在 TextField 拿到键事件之前
 * （`onPreviewKeyEvent`）消费，避免与输入法 / 文本编辑行为冲突。
 */
internal class SlashCommandMenuState {
    var selectedIndex by mutableIntStateOf(0)
        internal set

    internal fun moveUp(count: Int) {
        if (count > 0) selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
    }

    internal fun moveDown(count: Int) {
        if (count > 0) selectedIndex = (selectedIndex + 1).coerceAtMost(count - 1)
    }
}

/** 菜单分组：内置命令在前、技能在后（顺序即 [SlashCommandKind.entries]）。 */
internal data class SlashCommandGroup(
    val kind: SlashCommandKind,
    val items: List<SlashCommand>
)

/** LazyColumn 的行模型：分组标题或命令项；[flatIndex] 为命令在全部组中的序号（键盘选中用）。 */
private sealed interface SlashMenuRow {
    val key: String

    data class Header(
        override val key: String,
        val kind: SlashCommandKind,
        val count: Int
    ) : SlashMenuRow

    data class Command(
        override val key: String,
        val command: SlashCommand,
        val flatIndex: Int
    ) : SlashMenuRow
}

/**
 * 斜杠命令 / 技能菜单：输入 `/` 后弹出，底边贴在输入框（玻璃栏）上沿向上展开。
 *
 * - 高度由调用方按「输入框上沿到可视区域顶部可用空间的 50%」与「约 8 行」取小后传入
 *   （见 [maxHeight]），超出即在内部滚动，永不遮挡输入框、不撑满屏幕；
 * - 内容用 LazyColumn + 带 key 的 item 渲染，上百项也只组合可见行；
 * - 选中项由 [state] 驱动，↑↓ 移动时自动滚动到可见位置；
 * - 手指滚动 / 鼠标滚轮都走 LazyColumn 自带滚动；本浮层与消息列表是兄弟节点，
 *   不存在嵌套滚动传递，滑到顶 / 底不会再带动聊天页。
 */
@Composable
internal fun SlashCommandMenu(
    groups: List<SlashCommandGroup>,
    state: SlashCommandMenuState,
    maxHeight: Dp,
    onSelect: (SlashCommand) -> Unit,
    modifier: Modifier = Modifier
) {
    val flatCommands = groups.flatMap { it.items }
    val rows = remember(groups) {
        buildList {
            var flat = 0
            groups.forEach { group ->
                add(SlashMenuRow.Header("header_${group.kind}", group.kind, group.items.size))
                group.items.forEach { command ->
                    add(SlashMenuRow.Command("cmd_${group.kind}_${command.name}", command, flat))
                    flat++
                }
            }
        }
    }
    val listState = rememberLazyListState()

    // 过滤词 / 排序变化导致列表重建后，选中项复位到第一项（键盘导航从新的
    // 第一个匹配开始；键事件里另有 getOrNull / clamp 兜底，不会越界）。
    LaunchedEffect(groups) { state.selectedIndex = 0 }
    // 选中项移动时自动滚动到可见位置（animateScrollToItem 只滚到露出为止）。
    LaunchedEffect(state.selectedIndex, rows.size) {
        val index = rows.indexOfFirst { row ->
            row is SlashMenuRow.Command && row.flatIndex == state.selectedIndex
        }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        // 不透明底色：背景不透明度满足 ≥90% 的可读性要求，深浅主题都走主题色
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        if (flatCommands.isEmpty()) {
            // 无匹配结果：保留一条等高的提示行，Esc / 返回键仍可收起菜单
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = SLASH_MENU_ROW_HEIGHT)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = stringResource(R.string.chat_slash_menu_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight),
                contentPadding = PaddingValues(vertical = Spacing.xs)
            ) {
                rows.forEach { row ->
                    when (row) {
                        is SlashMenuRow.Header -> item(
                            key = row.key,
                            contentType = "header"
                        ) {
                            val title = stringResource(
                                when (row.kind) {
                                    SlashCommandKind.ACTION -> R.string.chat_slash_menu_commands_group
                                    SlashCommandKind.SKILL -> R.string.chat_slash_menu_skills_group
                                },
                                row.count
                            )
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = Spacing.md,
                                    end = Spacing.md,
                                    top = Spacing.sm,
                                    bottom = Spacing.xs
                                )
                            )
                        }

                        is SlashMenuRow.Command -> item(
                            key = row.key,
                            contentType = "command"
                        ) {
                            val selected = row.flatIndex == state.selectedIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = SLASH_MENU_ROW_HEIGHT)
                                    .clip(RoundedCornerShape(Radius.sm))
                                    .background(
                                        if (selected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        } else {
                                            Color.Transparent
                                        }
                                    )
                                    .clickable { onSelect(row.command) }
                                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "/${row.command.name}",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(Spacing.sm))
                                Text(
                                    text = row.command.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (row.command.kind == SlashCommandKind.SKILL) {
                                    Spacer(Modifier.width(Spacing.sm))
                                    Text(
                                        text = stringResource(R.string.settings_skills),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 斜杠菜单展示条件：仅当 `/` 位于输入开头（且未换行）时弹出；
 * 输入路径（src/app）、网址、中间出现的 `/` 都不触发；输入法组合输入期间
 * 只按当前组合文本过滤，不会修改文本、不会丢字。
 *
 * - [value] 输入框全文；[isBusy] AI 忙时沿用旧行为不弹菜单；
 * - [dismissed] 用户主动收起（Esc / 返回键 / 点击消息列表）；
 * - [commands] 注册表命令（内置命令 + 技能）。
 */
internal fun shouldShowSlashMenu(
    value: String,
    isBusy: Boolean,
    dismissed: Boolean,
    commands: List<SlashCommand>
): Boolean {
    if (isBusy || dismissed || commands.isEmpty()) return false
    if (!value.startsWith("/") || value.contains('\n')) return false
    // 已完整输入某条命令名并开始输参数（出现空格且首片段命中命令名）时收起：
    // 保持旧行为——输完 "/name " 后菜单自动消失，不再干扰参数输入。
    if (value.contains(' ')) {
        val firstToken = value.drop(1).substringBefore(' ').trim()
        if (firstToken.isNotEmpty() && commands.any { it.name.equals(firstToken, ignoreCase = true) }) {
            return false
        }
    }
    return true
}

/**
 * 查询词过滤：取 `/` 之后、首个空格之前的片段，同时匹配名称与描述、不区分大小写；
 * 名称前缀匹配排在名称包含、描述包含之前（[sortedBy] 稳定排序，同档保持注册表名称序）。
 */
internal fun filterSlashCommands(commands: List<SlashCommand>, query: String): List<SlashCommand> {
    val q = query.trim()
    if (q.isEmpty()) return commands
    val lower = q.lowercase()
    return commands
        .mapNotNull { command ->
            val name = command.name.lowercase()
            val rank = when {
                name.startsWith(lower) -> 0
                name.contains(lower) -> 1
                command.description.lowercase().contains(lower) -> 2
                else -> null
            }
            rank?.let { command to it }
        }
        .sortedBy { it.second }
        .map { it.first }
}

/**
 * 分组与排序：内置命令在前、技能在后；组内最近用过的排最前（按 [recentNames] 的先后），
 * 其余保持注册表既有顺序（已按名称排序）。只输出非空分组。
 */
internal fun groupSlashCommands(
    commands: List<SlashCommand>,
    recentNames: List<String>
): List<SlashCommandGroup> {
    val recentLower = recentNames.map { it.lowercase() }
    fun recentFirst(items: List<SlashCommand>): List<SlashCommand> {
        val recent = recentLower.mapNotNull { name -> items.firstOrNull { it.name.lowercase() == name } }
        val rest = items.filterNot { it.name.lowercase() in recentLower }
        return recent + rest
    }
    return buildList {
        SlashCommandKind.entries.forEach { kind ->
            val items = commands.filter { it.kind == kind }
            if (items.isNotEmpty()) add(SlashCommandGroup(kind, recentFirst(items)))
        }
    }
}

/** 记录一次使用：置顶该名称并去重（内存态，容量 [SLASH_RECENT_MAX]）。 */
internal fun List<String>.withRecentSlashCommand(name: String): List<String> {
    val key = name.lowercase()
    return (listOf(key) + filterNot { it == key }).take(SLASH_RECENT_MAX)
}
