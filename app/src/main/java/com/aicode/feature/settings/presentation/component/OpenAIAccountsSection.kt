package com.aicode.feature.settings.presentation.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.settings.data.local.entity.OpenAIAccountEntity
import com.aicode.feature.settings.data.repository.OpenAIAccountSummary
import com.aicode.feature.settings.presentation.OpenAIAccountsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.User
import compose.icons.feathericons.Zap
import java.text.DateFormat
import java.util.Date

/**
 * ChatGPT 账号管理入口卡片（设置页列表项）。
 */
@Composable
internal fun OpenAIAccountsEntryCard(onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.semanticColors.cardSurface),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.size(42.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = FeatherIcons.User,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.openai_accounts_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = stringResource(R.string.openai_accounts_entry_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * ChatGPT 账号管理主区域。
 *
 * 视觉对齐 cockpit-tools 的账号卡：计划徽章 + 状态pill + 5h/每周额度迷你条（剩余比例着色）
 * + 本地用量 + 图标动作行；模型选择改用与全局一致的 [AdaptiveModalBottomSheet]。
 */
@Composable
internal fun OpenAIAccountsSection(
    viewModel: OpenAIAccountsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var removeTarget by remember { mutableStateOf<OpenAIAccountSummary?>(null) }
    val context = LocalContext.current
    val isConnecting = state.busyAccountId == "__connecting__"

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.semanticColors.cardSurface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.openai_accounts_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.weight(1f)
                    )
                    if (state.accounts.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.openai_accounts_count, state.accounts.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.openai_accounts_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (state.accounts.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.openai_account_single_profile_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (state.accounts.isEmpty()) {
                        Button(
                            onClick = viewModel::connect,
                            enabled = state.busyAccountId == null
                        ) {
                            if (isConnecting) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(Spacing.sm))
                            }
                            Text(stringResource(R.string.openai_account_connect))
                        }
                    }
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OPENAI_USAGE_URL)))
                        }
                    }) {
                        Icon(
                            imageVector = FeatherIcons.ExternalLink,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.openai_account_manage_usage))
                    }
                }
                state.message?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.messageIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.semanticColors.success
                    )
                }
            }
        }

        state.accounts.forEach { account ->
            OpenAIAccountCard(
                account = account,
                busy = state.busyAccountId == account.id,
                onToggle = { viewModel.setEnabled(account.id, it) },
                onRefreshModels = { viewModel.refreshModels(account.id) },
                onRefreshUsage = { viewModel.refreshUsage(account.id) },
                onReauthorize = { viewModel.reauthorize(account.id) },
                onSelectModel = { viewModel.selectModel(account.id, it) },
                onOpenUsagePage = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OPENAI_USAGE_URL)))
                    }
                },
                onRemove = { removeTarget = account }
            )
        }
    }

    removeTarget?.let { account ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.openai_account_remove_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.openai_account_remove_message,
                        account.email.ifBlank { account.displayName }
                    )
                )
            },
            confirmButton = {
                TextButton(
                    enabled = state.busyAccountId == null,
                    onClick = {
                        viewModel.remove(account.id)
                        removeTarget = null
                    }
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OpenAIAccountCard(
    account: OpenAIAccountSummary,
    busy: Boolean,
    onToggle: (Boolean) -> Unit,
    onRefreshModels: () -> Unit,
    onRefreshUsage: () -> Unit,
    onReauthorize: () -> Unit,
    onSelectModel: (String) -> Unit,
    onOpenUsagePage: () -> Unit,
    onRemove: () -> Unit
) {
    var modelSheetVisible by remember { mutableStateOf(false) }
    val outline = MaterialTheme.colorScheme.outlineVariant

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.semanticColors.cardSurface),
        border = BorderStroke(1.dp, outline.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 顶部：账号 + 计划徽章 + 启用开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.email.ifBlank { account.displayName },
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        PlanTierBadge(planType = account.planType)
                        StatusPill(account = account)
                    }
                }
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    AppSwitch(
                        checked = account.enabled,
                        onCheckedChange = onToggle,
                        enabled = account.planUsageAuthorized && account.status != OpenAIAccountEntity.STATUS_NEEDS_LOGIN
                    )
                }
            }

            // 额度区：5h / 每周 迷你条
            val quotas = quotaWindows(account)
            if (quotas.isEmpty()) {
                Text(
                    text = if (account.planUsageAuthorized) stringResource(R.string.openai_account_quota_window_unknown, stringResource(R.string.openai_quota_weekly))
                    else stringResource(R.string.openai_account_plan_access_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    quotas.forEach { quota ->
                        QuotaMiniRow(quota = quota)
                    }
                    account.quotaUpdatedAt?.let { updatedAt ->
                        Text(
                            text = stringResource(
                                R.string.openai_account_quota_updated,
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(updatedAt))
                            ),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            account.quotaError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            // 本地用量
            Text(
                text = stringResource(
                    R.string.openai_account_local_usage,
                    account.localCalls,
                    account.localInputTokens,
                    account.localOutputTokens
                ),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 使用模型（底部弹层选择）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = account.models.isNotEmpty() && !busy) { modelSheetVisible = true }
                    .padding(vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.openai_account_model_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = account.models.firstOrNull { it.slug == account.selectedModel }?.displayName
                        ?: account.models.firstOrNull()?.displayName
                        ?: stringResource(R.string.openai_account_no_models),
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            // 动作行：刷新额度 / 刷新模型 / 重新授权 / 打开用量 / 移除
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CardActionButton(
                    icon = FeatherIcons.RefreshCw,
                    contentDescription = stringResource(R.string.openai_account_refresh_usage),
                    enabled = !busy && account.planUsageAuthorized,
                    onClick = onRefreshUsage
                )
                CardActionButton(
                    icon = FeatherIcons.Zap,
                    contentDescription = stringResource(R.string.openai_account_refresh_models),
                    enabled = !busy,
                    onClick = onRefreshModels
                )
                if (account.status != OpenAIAccountEntity.STATUS_CONNECTED || !account.planUsageAuthorized) {
                    CardActionButton(
                        icon = FeatherIcons.Settings,
                        contentDescription = stringResource(R.string.openai_account_reauthorize),
                        enabled = !busy,
                        onClick = onReauthorize
                    )
                }
                CardActionButton(
                    icon = FeatherIcons.ExternalLink,
                    contentDescription = stringResource(R.string.openai_account_manage_usage),
                    enabled = true,
                    onClick = onOpenUsagePage
                )
                Spacer(Modifier.weight(1f))
                CardActionButton(
                    icon = FeatherIcons.Trash2,
                    contentDescription = stringResource(R.string.common_delete),
                    enabled = !busy,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onRemove
                )
            }

            // 底部：账号标识
            Text(
                text = stringResource(R.string.openai_account_identifier, account.id.take(8)),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            account.lastError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (modelSheetVisible && account.models.isNotEmpty()) {
        AdaptiveModalBottomSheet(onDismissRequest = { modelSheetVisible = false }) {
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                Text(
                    text = stringResource(R.string.openai_account_select_model_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.padding(bottom = Spacing.sm)
                )
                account.models.forEach { model ->
                    val selected = model.slug == account.selectedModel
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                modelSheetVisible = false
                                onSelectModel(model.slug)
                            }
                            .padding(horizontal = Spacing.xs, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }
        }
    }
}

/** 计划徽章：配色对齐 cockpit-tools 的 tier-badge（plus 翠绿 / pro 蓝 / free 灰等）。 */
@Composable
private fun PlanTierBadge(planType: String?, modifier: Modifier = Modifier) {
    val normalized = planType?.lowercase()?.replace('-', '_')
    val (bg, content) = when (normalized) {
        "plus", "edu_plus" -> Color(0x2600B894) to Color(0xFF059669)
        "go" -> Color(0x26F59E0B) to Color(0xFFB45309)
        "pro", "prolite", "pro_max", "edu_pro" -> Color(0xFF1D4ED8) to Color.White
        "team", "free_workspace" -> Color(0x261D4ED8) to Color(0xFF1E40AF)
        "business", "self_serve_business_prolite", "self_serve_business_usage_based" ->
            Color(0x260EA5A5) to Color(0xFF0F766E)
        "enterprise", "enterprise_cbp_automation", "enterprise_cbp_usage_based" ->
            Color(0x267C3AED) to Color(0xFF6D28D9)
        "edu", "education", "k12" -> Color(0x26EC4899) to Color(0xFFBE185D)
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = when (normalized) {
        "free", "guest" -> stringResource(R.string.openai_quota_plan_free)
        "go" -> stringResource(R.string.openai_quota_plan_go)
        "plus", "edu_plus" -> stringResource(R.string.openai_quota_plan_plus)
        "pro", "prolite", "pro_max", "edu_pro" -> stringResource(R.string.openai_quota_plan_pro)
        "team", "free_workspace" -> stringResource(R.string.openai_quota_plan_team)
        "business", "self_serve_business_prolite", "self_serve_business_usage_based" ->
            stringResource(R.string.openai_quota_plan_business)
        "enterprise", "enterprise_cbp_automation", "enterprise_cbp_usage_based" ->
            stringResource(R.string.openai_quota_plan_enterprise)
        "edu", "education", "k12" -> stringResource(R.string.openai_quota_plan_education)
        else -> stringResource(R.string.openai_quota_plan_unknown)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1
        )
    }
}

/** 状态 pill：已连接（绿）/ 需重登（红）/ 已暂停（橙）。 */
@Composable
private fun StatusPill(account: OpenAIAccountSummary, modifier: Modifier = Modifier) {
    val (labelRes, tint) = when (account.status) {
        OpenAIAccountEntity.STATUS_CONNECTED ->
            R.string.openai_account_connected to MaterialTheme.semanticColors.success
        OpenAIAccountEntity.STATUS_USAGE_PAUSED ->
            R.string.openai_account_usage_paused to MaterialTheme.semanticColors.warning
        else -> R.string.openai_account_needs_login to MaterialTheme.colorScheme.error
    }
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = stringResource(labelRes),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = tint,
            maxLines = 1
        )
    }
}

/** 单个额度窗口迷你条：标签 + 剩余百分比（着色）+ 4dp 进度条 + 重置时间。 */
@Composable
private fun QuotaMiniRow(quota: QuotaWindowUi) {
    val remaining = (100 - quota.usedPercent).coerceIn(0, 100)
    val level = when {
        remaining >= 50 -> QuotaLevel.HIGH
        remaining >= 20 -> QuotaLevel.MEDIUM
        remaining >= 5 -> QuotaLevel.LOW
        else -> QuotaLevel.CRITICAL
    }
    val barColor = when (level) {
        QuotaLevel.HIGH -> MaterialTheme.semanticColors.success
        QuotaLevel.MEDIUM -> QuotaAmber
        QuotaLevel.LOW -> QuotaOrange
        QuotaLevel.CRITICAL -> MaterialTheme.colorScheme.error
    }
    val label = when {
        quota.durationSeconds != null && quota.durationSeconds <= FIVE_HOUR_WINDOW_MAX_SECONDS ->
            stringResource(R.string.openai_quota_five_hour)
        quota.durationSeconds != null && quota.durationSeconds >= WEEKLY_WINDOW_MIN_SECONDS ->
            stringResource(R.string.openai_quota_weekly)
        quota.durationSeconds != null -> stringResource(
            R.string.openai_quota_other_window,
            (quota.durationSeconds / 60).toInt()
        )
        else -> stringResource(R.string.openai_quota_weekly)
    }
    val reset = quota.resetAtEpochSeconds?.let { epochSeconds ->
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(epochSeconds * 1000))
    } ?: stringResource(R.string.openai_quota_reset_unknown)

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.openai_account_quota_remaining, remaining),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = barColor
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(remaining / 100f)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(barColor)
            )
        }
        Text(
            text = stringResource(R.string.openai_account_quota_reset_at, reset),
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private enum class QuotaLevel { HIGH, MEDIUM, LOW, CRITICAL }

/** 圆形图标动作按钮（对齐 cockpit card-action-btn 的紧凑手感）。 */
@Composable
private fun CardActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            modifier = Modifier.size(16.dp)
        )
    }
}

private data class QuotaWindowUi(
    val usedPercent: Int,
    val durationSeconds: Long?,
    val resetAtEpochSeconds: Long?
)

private fun quotaWindows(account: OpenAIAccountSummary): List<QuotaWindowUi> = buildList {
    account.quotaPrimaryUsedPercent?.let { used ->
        add(QuotaWindowUi(used, account.quotaPrimaryWindowSeconds, account.quotaPrimaryResetAt))
    }
    account.quotaSecondaryUsedPercent?.let { used ->
        add(QuotaWindowUi(used, account.quotaSecondaryWindowSeconds, account.quotaSecondaryResetAt))
    }
}

private val QuotaAmber = Color(0xFFF59E0B)
private val QuotaOrange = Color(0xFFF97316)

private const val FIVE_HOUR_WINDOW_MAX_SECONDS = 6 * 60 * 60L
private const val WEEKLY_WINDOW_MIN_SECONDS = 24 * 60 * 60L

private const val OPENAI_USAGE_URL = "https://chatgpt.com/settings/usage"
