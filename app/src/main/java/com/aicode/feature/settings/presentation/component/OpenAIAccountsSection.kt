package com.aicode.feature.settings.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.core.ui.AdaptiveModalBottomSheet
import com.aicode.core.ui.AppSwitch
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.dialogTextFieldColors
import com.aicode.feature.settings.data.local.entity.OpenAIAccountEntity
import com.aicode.feature.settings.data.repository.OpenAILoginSession
import com.aicode.feature.settings.data.repository.OpenAIAccountSummary
import com.aicode.feature.settings.presentation.OpenAIAccountsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Download
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Plus
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Settings
import compose.icons.feathericons.Star
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Upload
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
 * 视觉对齐 cockpit-tools 的账号卡并重排为整齐分区：头部（账号 + 档位/状态徽章 + 开关）、
 * 额度区（5h / 每周双列迷你条，灰色容器收拢）、本地用量、模型行（行内刷新）、
 * 文字标签动作行（窄屏自动换行）、底部标识。账号按订阅等级降序（付费号在上）。
 * 模型选择改用与全局一致的 [AdaptiveModalBottomSheet]。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun OpenAIAccountsSection(
    viewModel: OpenAIAccountsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var removeTarget by remember { mutableStateOf<OpenAIAccountSummary?>(null) }
    var showImport by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val isConnecting = state.loginPreparing || state.loginSession != null

    // 每次打开本页自动刷新一次全部账号额度，避免看到过期的配额数据
    LaunchedEffect(Unit) { viewModel.refreshAll() }

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
                        Spacer(Modifier.width(Spacing.xs))
                    }
                    // 手动刷新入口：进页面已自动刷一次，这里给用户随时再刷的按钮
                    IconButton(
                        onClick = viewModel::refreshAll,
                        enabled = !state.refreshing && state.busyAccountId == null && !isConnecting,
                        modifier = Modifier.size(34.dp)
                    ) {
                        if (state.refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                imageVector = FeatherIcons.RefreshCw,
                                contentDescription = stringResource(R.string.openai_account_refresh_usage),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                // 操作区：FlowRow 自动换行，窄屏也不会挤成一团
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    // 多账号：无论当前已有几个账号，「添加账号」始终可用
                    Button(
                        onClick = { viewModel.startLogin(null) },
                        enabled = state.busyAccountId == null && !isConnecting,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Plus,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        if (isConnecting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(Spacing.sm))
                        }
                        Text(
                            stringResource(
                                if (state.accounts.isEmpty()) R.string.openai_account_connect
                                else R.string.openai_account_add
                            )
                        )
                    }
                    OutlinedButton(
                        onClick = { showImport = true },
                        enabled = state.busyAccountId == null && !isConnecting,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = FeatherIcons.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.openai_account_import_action))
                    }
                    if (state.accounts.isNotEmpty()) {
                        OutlinedButton(
                            onClick = viewModel::exportAccounts,
                            enabled = state.busyAccountId == null && !isConnecting && !state.refreshing,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.Upload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.openai_account_export_action))
                        }
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

        // 账号按订阅等级降序（付费号在上），与模型选择器的排序保持一致
        val sortedAccounts = remember(state.accounts) {
            state.accounts.sortedByDescending { planTierRank(it.planType) }
        }
        sortedAccounts.forEach { account ->
            OpenAIAccountCard(
                account = account,
                busy = state.busyAccountId == account.id,
                isDefault = state.defaultProviderId.isNotBlank() &&
                    account.providerId == state.defaultProviderId,
                onSetDefault = { viewModel.setAsDefault(account.id) },
                onToggle = { viewModel.setEnabled(account.id, it) },
                onRefreshModels = { viewModel.refreshModels(account.id) },
                onRefreshUsage = { viewModel.refreshUsage(account.id) },
                onReauthorize = { viewModel.reauthorize(account.id) },
                onSelectModel = { viewModel.selectModel(account.id, it) },
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

    if (state.loginVisible) {
        ChatGptLoginSheet(
            session = state.loginSession,
            preparing = state.loginPreparing,
            isNewAccount = state.loginSession?.accountId == null,
            error = state.loginError,
            onDismiss = viewModel::cancelLogin,
            onRetry = { viewModel.retryLogin(state.loginSession?.accountId) },
            onSubmitCallback = viewModel::submitLoginCallback
        )
    }

    if (showImport) {
        ImportAccountDialog(
            onDismiss = { showImport = false },
            onImport = { raw ->
                showImport = false
                viewModel.importAccount(raw)
            }
        )
    }

    state.exportJson?.let { json ->
        ExportAccountsDialog(
            json = json,
            onDismiss = viewModel::clearExportJson
        )
    }
}

/**
 * 登录/重新授权弹层： cockpit-tools 的 ChatGptPlanDialog 同思路——先给授权链接与「打开浏览器」，
 * 再给一个手动粘贴回调链接的入口，浏览器无法回跳 127.0.0.1 时依然能登录成功。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatGptLoginSheet(
    session: OpenAILoginSession?,
    preparing: Boolean,
    isNewAccount: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onSubmitCallback: (String) -> Unit
) {
    val context = LocalContext.current
    var callbackInput by remember(session?.sessionId) { mutableStateOf("") }
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(
                    if (isNewAccount) R.string.openai_login_add_title else R.string.openai_login_reauthorize_title
                ),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            // 错误就地显示：弹层会盖住页面下方的提示条，用户看不到就等于「点了没反应」
            error?.let { message ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(Spacing.sm)
                    )
                }
            }

            if (session == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = stringResource(R.string.openai_login_preparing),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                // 登录失败后保留弹层，让用户直接重试，而不是回到页面再点一次
                error?.let {
                    Button(onClick = onRetry, enabled = !preparing) {
                        Text(stringResource(R.string.openai_login_retry))
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.openai_login_authorize_url_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = session.authorizeUrl,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(Spacing.sm)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(session.authorizeUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    ) {
                        Icon(
                            imageVector = FeatherIcons.ExternalLink,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.openai_login_open_browser))
                    }
                    OutlinedButton(onClick = {
                        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        manager?.setPrimaryClip(ClipData.newPlainText("openai-auth-url", session.authorizeUrl))
                    }) {
                        Icon(
                            imageVector = FeatherIcons.Copy,
                            contentDescription = stringResource(R.string.common_copy),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.openai_login_manual_callback_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppTextField(
                        value = callbackInput,
                        onValueChange = { callbackInput = it },
                        label = stringResource(R.string.openai_login_manual_callback_placeholder),
                        singleLine = true,
                        colors = dialogTextFieldColors(),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(Spacing.xs))
                    Button(
                        enabled = callbackInput.isNotBlank() && !preparing,
                        onClick = { onSubmitCallback(callbackInput) }
                    ) { Text(stringResource(R.string.openai_login_submit)) }
                }
                Text(
                    text = stringResource(R.string.openai_login_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
            Spacer(Modifier.height(Spacing.lg))
        }
    }
}

/**
 * 导入账号：对齐 cockpit-tools 的多种导入来源，用分段控件切换。
 *
 *  - 粘贴 JSON：Codex CLI `auth.json` / Web Session / 顶层 token 对象 / JSON 数组（批量）
 *  - Access Token：给一段 token 起个名字直接建号
 *  - 批量文本：每行一个 JSON、一个裸 token，或 `邮箱----密码----2FA秘钥----邮件地址`
 *  - 选择文件：直接选 cockpit-tools 导出的 TXT / JSON 文件（读取后立即走同一套解析）
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ImportAccountDialog(onDismiss: () -> Unit, onImport: (String) -> Unit) {
    var tab by remember { mutableStateOf(ImportTab.JSON) }
    var raw by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var fileError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // 文件选择： cockpit-tools 导出的 TXT 常被识别为 text/plain 或通用类型，MIME 放宽确保能选到
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                }
            }.getOrNull()
            if (text.isNullOrBlank()) {
                fileError = context.getString(R.string.openai_import_file_read_failed)
            } else if (text.length > MAX_IMPORT_FILE_CHARS) {
                fileError = context.getString(R.string.openai_import_file_too_large)
            } else {
                // 读到的内容直接走统一解析入口（与粘贴完全相同）
                onImport(text)
            }
        }
    }

    val canSubmit = when (tab) {
        ImportTab.JSON, ImportTab.LINES -> raw.isNotBlank()
        ImportTab.TOKEN -> token.isNotBlank()
    }
    val hint = stringResource(
        when (tab) {
            ImportTab.JSON -> R.string.openai_import_json_hint
            ImportTab.TOKEN -> R.string.openai_import_token_hint
            ImportTab.LINES -> R.string.openai_import_lines_hint
        }
    )
    val placeholder = stringResource(
        when (tab) {
            ImportTab.JSON -> R.string.openai_import_json_placeholder
            ImportTab.TOKEN -> R.string.openai_import_token_value_placeholder
            ImportTab.LINES -> R.string.openai_import_lines_placeholder
        }
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.openai_account_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    FilterChip(
                        selected = tab == ImportTab.JSON,
                        onClick = { tab = ImportTab.JSON },
                        label = { Text(stringResource(R.string.openai_import_tab_json)) }
                    )
                    FilterChip(
                        selected = tab == ImportTab.TOKEN,
                        onClick = { tab = ImportTab.TOKEN },
                        label = { Text(stringResource(R.string.openai_import_tab_token)) }
                    )
                    FilterChip(
                        selected = tab == ImportTab.LINES,
                        onClick = { tab = ImportTab.LINES },
                        label = { Text(stringResource(R.string.openai_import_tab_lines)) }
                    )
                }
                // 文件导入入口：选中 TXT / JSON 文件后直接解析，无需手动粘贴
                if (tab != ImportTab.TOKEN) {
                    OutlinedButton(
                        onClick = {
                            fileError = null
                            filePicker.launch(arrayOf("text/plain", "text/*", "application/json", "*/*"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = FeatherIcons.FileText,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.openai_import_pick_file))
                    }
                }
                fileError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (tab == ImportTab.TOKEN) {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { displayName = it },
                        label = { Text(stringResource(R.string.openai_import_token_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        placeholder = { Text(placeholder, maxLines = 2) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    OutlinedTextField(
                        value = raw,
                        onValueChange = { raw = it },
                        placeholder = { Text(placeholder, maxLines = 2) },
                        minLines = 5,
                        maxLines = 10,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSubmit,
                onClick = {
                    onImport(
                        when (tab) {
                            ImportTab.TOKEN -> importTokenPayload(displayName, token)
                            else -> raw
                        }
                    )
                }
            ) { Text(stringResource(R.string.openai_account_import_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 导入文件大小上限：防止误选超大文件把进程拖垮。 */
private const val MAX_IMPORT_FILE_CHARS = 2 * 1024 * 1024

/** Access Token 导入也走统一的文本解析入口，内部统一包成 JSON。 */
private fun importTokenPayload(displayName: String, token: String): String = JSONObject()
    .put("email", displayName.trim())
    .put("access_token", token.trim())
    .toString()

/** 导出结果：直接展示 JSON，并给「复制」把内容放进剪贴板。 */
@Composable
private fun ExportAccountsDialog(json: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.openai_account_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = stringResource(R.string.openai_account_export_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = json,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(Spacing.sm)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                copyToClipboard(context, "aicode-openai-accounts", json)
                Toast.makeText(context, R.string.openai_notice_export_copied, Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.common_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
        }
    )
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    manager?.setPrimaryClip(ClipData.newPlainText(label, value))
}

private enum class ImportTab { JSON, TOKEN, LINES }


@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OpenAIAccountCard(
    account: OpenAIAccountSummary,
    busy: Boolean,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRefreshModels: () -> Unit,
    onRefreshUsage: () -> Unit,
    onReauthorize: () -> Unit,
    onSelectModel: (String) -> Unit,
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
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ① 头部：账号名 + 档位/状态徽章 + 启用开关
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = account.email.ifBlank { account.displayName },
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        PlanTierBadge(planType = account.planType)
                        StatusPill(account = account)
                        if (isDefault) DefaultBadge()
                    }
                }
                Spacer(Modifier.width(Spacing.sm))
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

            // ② 额度区：5h / 每周 双列排布（两个窗口时左右分栏，单窗口时通排）
            val quotas = quotaWindows(account)
            if (quotas.isEmpty()) {
                Text(
                    text = if (account.planUsageAuthorized) {
                        stringResource(R.string.openai_account_quota_window_unknown, stringResource(R.string.openai_quota_weekly))
                    } else {
                        stringResource(R.string.openai_account_plan_access_missing)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (quotas.size >= 2) {
                            // 双列：5 小时与每周额度并排，高度对齐更整齐
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                quotas.take(2).forEach { quota ->
                                    Box(modifier = Modifier.weight(1f)) {
                                        QuotaMiniRow(quota = quota)
                                    }
                                }
                            }
                        } else {
                            QuotaMiniRow(quota = quotas.first())
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
            }
            account.quotaError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            // ③ 本地用量
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

            // ④ 使用模型（底部弹层选择）+ 行内刷新模型
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    .clickable(enabled = account.models.isNotEmpty() && !busy) { modelSheetVisible = true }
                    .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
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
                // 刷新模型列表：从隐秘的闪电图标挪到模型行内，语义更直接
                IconButton(
                    onClick = onRefreshModels,
                    enabled = !busy,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = FeatherIcons.RefreshCw,
                        contentDescription = stringResource(R.string.openai_account_refresh_models),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(15.dp)
                    )
                }
                Icon(
                    imageVector = FeatherIcons.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }

            // ⑤ 动作行：带文字标签的按钮，窄屏自动换行，不再是一排含义不明的圆图标
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                CardActionButton(
                    icon = FeatherIcons.Star,
                    text = stringResource(R.string.openai_account_set_default),
                    enabled = !busy && !isDefault && account.planUsageAuthorized,
                    tint = if (isDefault) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    onClick = onSetDefault
                )
                CardActionButton(
                    icon = FeatherIcons.RefreshCw,
                    text = stringResource(R.string.openai_account_refresh_usage),
                    enabled = !busy && account.planUsageAuthorized,
                    onClick = onRefreshUsage
                )
                if (account.status != OpenAIAccountEntity.STATUS_CONNECTED || !account.planUsageAuthorized) {
                    CardActionButton(
                        icon = FeatherIcons.Settings,
                        text = stringResource(R.string.openai_account_reauthorize),
                        enabled = !busy,
                        onClick = onReauthorize
                    )
                }
                CardActionButton(
                    icon = FeatherIcons.Trash2,
                    text = stringResource(R.string.common_delete),
                    enabled = !busy,
                    tint = MaterialTheme.colorScheme.error,
                    onClick = onRemove
                )
            }

            // ⑥ 底部：账号标识 + 错误
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

/** 「默认」徽章：该账号是新会话默认使用的账号。 */
@Composable
private fun DefaultBadge(modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Icon(
                imageVector = FeatherIcons.Star,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(10.dp)
            )
            Text(
                text = stringResource(R.string.openai_account_default_badge),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = tint,
                maxLines = 1
            )
        }
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

/** 胶囊动作按钮：图标 + 文字标签，语义自解释（替代原先含义不明的纯圆图标行）。 */
@Composable
private fun CardActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
            color = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
            maxLines = 1
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

