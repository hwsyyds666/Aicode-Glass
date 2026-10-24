package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.R
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.SegmentedTabs
import com.aicode.core.ui.SwipeToDeleteRow
import com.aicode.feature.agent.domain.permission.HighRiskMatchType
import com.aicode.feature.agent.domain.permission.HighRiskRule
import com.aicode.feature.settings.data.repository.CommandAuthLevel
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertTriangle
import compose.icons.feathericons.Check
import compose.icons.feathericons.Filter
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Zap
import kotlinx.coroutines.delay

/**
 * 「工具授权」二级页：与设置页其它二级页一致的 iOS 分组列表。
 * 顶部是「命令授权」三挡位（自主执行 / 规则拦截 / 完全权限）；
 * 其下是「高危拦截规则」管理（预设 + 用户自建，可开关/编辑/删除/恢复预设）。
 *
 * 旧的「安全防护」开关与「当前项目 / 全局」记忆规则已整体移除：授权口径统一收敛到三挡位 +
 * 高危规则这套机制上——旧的项目/全局 ALLOW 规则会先于挡位生效，等于绕过高危拦截，语义冲突。
 */
@Composable
internal fun PermissionsSection(
    commandAuthLevel: CommandAuthLevel,
    commandReviewTimeoutSeconds: Int,
    highRiskRules: List<HighRiskRule>,
    onSelectAuthLevel: (CommandAuthLevel) -> Unit,
    onSelectReviewTimeout: (Int) -> Unit,
    onToggleHighRiskRule: (HighRiskRule, Boolean) -> Unit,
    onSaveHighRiskRule: (HighRiskRule, Boolean) -> Unit,
    onDeleteHighRiskRule: (HighRiskRule) -> Unit,
    onResetHighRiskRules: () -> Unit
) {
    // 切到「完全权限」前强制确认（倒计时）。
    var showFullAccessConfirm by remember { mutableStateOf(false) }
    // 高危规则编辑/新增弹窗目标；isNew 区分新增与编辑。
    var editingHighRiskRule by remember { mutableStateOf<HighRiskRule?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var showResetPresetsConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // ── 命令授权挡位 ──
        SettingsGroupHeader(text = stringResource(R.string.perm_auth_group))
        SettingsGroup {
            AuthLevelRow(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.perm_auth_autonomous),
                subtitle = stringResource(R.string.perm_auth_autonomous_desc),
                selected = commandAuthLevel == CommandAuthLevel.AUTONOMOUS,
                onClick = { onSelectAuthLevel(CommandAuthLevel.AUTONOMOUS) }
            )
            SettingsDivider()
            AuthLevelRow(
                icon = FeatherIcons.Filter,
                title = stringResource(R.string.perm_auth_rule_based),
                subtitle = stringResource(R.string.perm_auth_rule_based_desc),
                selected = commandAuthLevel == CommandAuthLevel.RULE_BASED,
                onClick = { onSelectAuthLevel(CommandAuthLevel.RULE_BASED) }
            )
            SettingsDivider()
            AuthLevelRow(
                icon = FeatherIcons.AlertTriangle,
                title = stringResource(R.string.perm_auth_full_access),
                subtitle = stringResource(R.string.perm_auth_full_access_desc),
                selected = commandAuthLevel == CommandAuthLevel.FULL_ACCESS,
                danger = true,
                onClick = {
                    if (commandAuthLevel == CommandAuthLevel.FULL_ACCESS) {
                        Unit
                    } else {
                        showFullAccessConfirm = true
                    }
                }
            )
        }

        if (commandAuthLevel == CommandAuthLevel.AUTONOMOUS) {
            SettingsGroupHeader(text = stringResource(R.string.perm_review_timeout_title))
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Text(
                        text = stringResource(R.string.perm_review_timeout_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SegmentedTabs(
                        selected = listOf(10, 15, 20).indexOf(commandReviewTimeoutSeconds).coerceAtLeast(0),
                        labels = listOf(
                            stringResource(R.string.perm_review_timeout_10s),
                            stringResource(R.string.perm_review_timeout_15s),
                            stringResource(R.string.perm_review_timeout_20s)
                        ),
                        onSelect = { onSelectReviewTimeout(listOf(10, 15, 20)[it]) }
                    )
                }
            }
        }

        // ── 高危拦截规则 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsGroupHeader(
                text = stringResource(R.string.perm_high_risk_group),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.perm_high_risk_reset),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { showResetPresetsConfirm = true }
            )
        }
        if (commandAuthLevel != CommandAuthLevel.RULE_BASED) {
            Text(
                text = stringResource(R.string.perm_high_risk_note_inactive),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.md)
            )
        }
        SettingsGroup {
            if (highRiskRules.isEmpty()) {
                RuleEmptyHint(stringResource(R.string.perm_high_risk_empty))
            } else {
                highRiskRules.forEachIndexed { index, rule ->
                    if (index > 0) SettingsDivider()
                    HighRiskRuleRow(
                        rule = rule,
                        onToggle = { enabled -> onToggleHighRiskRule(rule, enabled) },
                        onClick = {
                            editingHighRiskRule = rule
                            editingIsNew = false
                        },
                        onDelete = { onDeleteHighRiskRule(rule) }
                    )
                }
            }
            SettingsDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        editingHighRiskRule = HighRiskRule(
                            id = "user-${System.currentTimeMillis()}",
                            pattern = "",
                            matchType = HighRiskMatchType.PREFIX,
                            category = "",
                            description = "",
                            enabled = true,
                            builtin = false
                        )
                        editingIsNew = true
                    }
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = FeatherIcons.Plus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.md))
                Text(
                    text = stringResource(R.string.perm_high_risk_add),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

    }

    if (showFullAccessConfirm) {
        CountdownConfirmDialog(
            title = stringResource(R.string.perm_full_access_confirm_title),
            message = stringResource(R.string.perm_full_access_confirm_message),
            onConfirm = {
                onSelectAuthLevel(CommandAuthLevel.FULL_ACCESS)
                showFullAccessConfirm = false
            },
            onDismiss = { showFullAccessConfirm = false }
        )
    }

    if (showResetPresetsConfirm) {
        AlertDialog(
            onDismissRequest = { showResetPresetsConfirm = false },
            title = { Text(stringResource(R.string.perm_high_risk_reset_confirm_title)) },
            text = { Text(stringResource(R.string.perm_high_risk_reset_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onResetHighRiskRules()
                    showResetPresetsConfirm = false
                }) { Text(stringResource(R.string.perm_high_risk_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { showResetPresetsConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    editingHighRiskRule?.let { rule ->
        HighRiskRuleEditDialog(
            initial = rule,
            isNew = editingIsNew,
            onDismiss = { editingHighRiskRule = null },
            onConfirm = { saved ->
                onSaveHighRiskRule(saved, editingIsNew)
                editingHighRiskRule = null
            }
        )
    }
}

/** 挡位单选行：图标 + 标题/副标题，右侧选中对勾；危险项标题用错误色。 */
@Composable
private fun AuthLevelRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = when {
                danger -> MaterialTheme.colorScheme.error
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (selected) {
            Spacer(Modifier.width(Spacing.sm))
            Icon(
                imageVector = FeatherIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * 高危规则行：分类徽标 + 危险说明（主）+ 匹配模式/类型（次），右侧启用开关；
 * 点击进入编辑弹窗，左滑删除（预设删除后可用「恢复预设」找回）。
 */
@Composable
private fun HighRiskRuleRow(
    rule: HighRiskRule,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    SwipeToDeleteRow(onDelete = onDelete, onClick = onClick) {
        // 与项目其它滑动行保持一致：左右均 Spacing.lg、纵向 12.dp
        // （原 end=Spacing.xs 会让开关比「命令授权挡位」各行多贴右 12dp）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(text = rule.category.ifBlank { stringResource(R.string.perm_high_risk_custom_category) })
                    if (rule.builtin) {
                        Spacer(Modifier.width(Spacing.xs))
                        CategoryBadge(
                            text = stringResource(R.string.perm_high_risk_builtin_badge),
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                Text(
                    text = rule.description.ifBlank { rule.pattern },
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Text(
                    text = rule.pattern + " · " + stringResource(
                        if (rule.matchType == HighRiskMatchType.REGEX) {
                            R.string.perm_high_risk_match_regex
                        } else {
                            R.string.perm_high_risk_match_prefix
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AppSwitch(checked = rule.enabled, onCheckedChange = onToggle)
        }
    }
}

/** 分类/预设徽标：主题色浅底胶囊小字。 */
@Composable
private fun CategoryBadge(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(Radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/** 高危规则编辑/新增弹窗：匹配模式 + 前缀/正则切换 + 危险说明 + 分类。 */
@Composable
private fun HighRiskRuleEditDialog(
    initial: HighRiskRule,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (HighRiskRule) -> Unit
) {
    var pattern by remember { mutableStateOf(initial.pattern) }
    var matchType by remember { mutableStateOf(initial.matchType) }
    var description by remember { mutableStateOf(initial.description) }
    var category by remember { mutableStateOf(initial.category) }
    val matchTypes = HighRiskMatchType.values()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (isNew) R.string.perm_high_risk_add else R.string.perm_high_risk_edit
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = stringResource(R.string.perm_high_risk_pattern),
                    placeholder = stringResource(R.string.perm_high_risk_pattern_hint),
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
                SegmentedTabs(
                    selected = matchTypes.indexOf(matchType).coerceAtLeast(0),
                    labels = listOf(
                        stringResource(R.string.perm_high_risk_match_prefix),
                        stringResource(R.string.perm_high_risk_match_regex)
                    ),
                    onSelect = { matchType = matchTypes[it] }
                )
                AppTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = stringResource(R.string.perm_high_risk_desc),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                AppTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = stringResource(R.string.perm_high_risk_category),
                    placeholder = stringResource(R.string.perm_high_risk_custom_category),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        initial.copy(
                            pattern = pattern.trim(),
                            matchType = matchType,
                            description = description.trim(),
                            category = category.trim()
                        )
                    )
                },
                enabled = pattern.isNotBlank()
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 「禁用安全拦截」确认框的倒计时秒数。 */
private const val SAFETY_CONFIRM_COUNTDOWN_SECONDS = 5

/** 高危操作强制确认框：确认按钮带 5 秒倒计时，倒计时结束前不可点。 */
@Composable
internal fun CountdownConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var secondsLeft by remember { mutableIntStateOf(SAFETY_CONFIRM_COUNTDOWN_SECONDS) }
    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1_000)
            secondsLeft--
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = secondsLeft == 0
            ) {
                Text(
                    if (secondsLeft > 0) {
                        stringResource(R.string.perm_safety_confirm_action, secondsLeft)
                    } else {
                        stringResource(R.string.perm_safety_confirm_action_ready)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 分组内空状态：一行灰字，与行内容对齐。 */
@Composable
private fun RuleEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
    )
}

