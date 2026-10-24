package com.aicode.feature.workspace.presentation.component

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.aicode.core.ui.AppTextField
import com.aicode.core.ui.dialogTextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aicode.core.theme.Radius
import com.aicode.core.theme.Spacing
import com.aicode.feature.workspace.domain.model.Workspace
import com.aicode.feature.workspace.domain.model.WorkspaceType
import com.aicode.feature.workspace.presentation.WorkspaceViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Github
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.MoreHorizontal
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import com.aicode.R
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceChip(
    viewModel: WorkspaceViewModel,
    hasRunningSessions: () -> Boolean = { false },
    onSwitchConfirmed: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val current by viewModel.current.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(Radius.pill))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { showSheet = true }
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            FeatherIcons.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = current?.name ?: stringResource(R.string.workspace_select),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Icon(
            FeatherIcons.MoreHorizontal,
            contentDescription = stringResource(R.string.workspace_switch),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }

    WorkspaceSelectionHost(
        visible = showSheet,
        onDismiss = { showSheet = false },
        viewModel = viewModel,
        hasRunningSessions = hasRunningSessions,
        onSwitchConfirmed = onSwitchConfirmed
    )
}

@Composable
private fun GitHubRepositoriesDialog(
    viewModel: WorkspaceViewModel,
    onDismiss: () -> Unit,
    onWorkspaceCreated: (Workspace) -> Unit
) {
    val state by viewModel.github.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var ownerFilter by remember { mutableStateOf("") }
    var showPrivate by remember { mutableStateOf(true) }
    var showPublic by remember { mutableStateOf(true) }

    LaunchedEffect(state.account?.login, state.repositories.size) {
        if (state.account != null && state.repositories.isEmpty() && !state.loadingRepositories) {
            viewModel.loadGitHubRepositories(reset = true)
        }
    }

    val filteredRepos = remember(state.repositories, query, ownerFilter, showPrivate, showPublic) {
        state.repositories.filter { repo ->
            (showPrivate || !repo.private) && (showPublic || repo.private) &&
                (ownerFilter.isBlank() || repo.owner.contains(ownerFilter.trim(), ignoreCase = true)) &&
                (query.isBlank() || repo.fullName.contains(query.trim(), ignoreCase = true) ||
                    repo.description.orEmpty().contains(query.trim(), ignoreCase = true))
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.88f).widthIn(max = 640.dp),
            shape = RoundedCornerShape(Radius.lg),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize().padding(Spacing.lg)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.workspace_github_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                }

                val account = state.account
                if (account == null) {
                    if (state.clientId.isBlank()) {
                        Text(
                            stringResource(R.string.workspace_github_not_configured),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = Spacing.sm)
                        )
                    } else {
                        Text(
                            stringResource(R.string.workspace_github_scope_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(bottom = Spacing.sm)
                        )
                    }
                    // One tap opens GitHub in the system browser; the local callback completes the connection.
                    Spacer(Modifier.height(Spacing.md))
                    androidx.compose.material3.Button(
                        onClick = { viewModel.connectGitHub() },
                        enabled = !state.authorizing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (state.authorizing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(Spacing.sm))
                        }
                        Text(stringResource(if (state.authorizing) R.string.workspace_github_authorizing else R.string.workspace_github_connect))
                    }
                    state.userCode?.let { code ->
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            text = stringResource(R.string.workspace_github_device_code_hint, code),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    state.waitingSeconds?.let { remaining ->
                        if (state.userCode != null) {
                            Text(
                                text = stringResource(R.string.workspace_github_waiting, remaining),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (state.authorizing) {
                        TextButton(onClick = viewModel::cancelGitHubAuthorization, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text(stringResource(R.string.common_cancel))
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GitHubAccountAvatar(account.avatarUrl)
                        Spacer(Modifier.width(Spacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text(account.login, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.workspace_github_connected), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(
                            enabled = state.cloningRepository == null,
                            onClick = viewModel::disconnectGitHub
                        ) { Text(stringResource(R.string.workspace_github_disconnect)) }
                    }
                    AppTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = stringResource(R.string.workspace_github_search),
                        singleLine = true,
                        colors = dialogTextFieldColors()
                    )
                    AppTextField(
                        value = ownerFilter,
                        onValueChange = { ownerFilter = it },
                        label = stringResource(R.string.workspace_github_owner_filter),
                        singleLine = true,
                        colors = dialogTextFieldColors()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = showPrivate, onCheckedChange = { showPrivate = it })
                        Text(stringResource(R.string.workspace_github_private))
                        Checkbox(checked = showPublic, onCheckedChange = { showPublic = it })
                        Text(stringResource(R.string.workspace_github_public))
                        Spacer(Modifier.weight(1f))
                        if (state.loadingRepositories) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                    if (state.repositories.isEmpty() && !state.loadingRepositories) {
                        Text(stringResource(R.string.workspace_github_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            items(filteredRepos, key = { it.id }) { repo ->
                                Column(
                                    Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.sm))
                                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                        .padding(Spacing.sm)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(repo.fullName, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            stringResource(if (repo.private) R.string.workspace_github_private else R.string.workspace_github_public),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    repo.description?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            stringResource(R.string.workspace_github_updated, repo.updatedAt.take(10)),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            enabled = !repo.archived && state.cloningRepository == null,
                                            onClick = {
                                                viewModel.cloneGitHubRepository(repo) { workspace ->
                                                    if (workspace != null) onWorkspaceCreated(workspace)
                                                }
                                            }
                                        ) {
                                            Text(stringResource(if (repo.archived) R.string.workspace_github_archived else R.string.workspace_github_clone))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (state.nextPage != null) {
                        TextButton(
                            enabled = !state.loadingRepositories,
                            onClick = { viewModel.loadGitHubRepositories(reset = false) },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) { Text(stringResource(R.string.workspace_github_load_more)) }
                    }
                    state.cloningRepository?.let { repo ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.workspace_github_cloning, repo), style = MaterialTheme.typography.bodySmall)
                                Text(state.cloneProgress.orEmpty(), style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            TextButton(onClick = viewModel::cancelGitHubClone) {
                                Text(stringResource(R.string.workspace_github_cancel_clone))
                            }
                        }
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { (state.cloneProgressPercent ?: 0).coerceIn(0, 100) / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                state.error?.let { error ->
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = Spacing.sm))
                }
            }
        }
    }
}

/** Always hand GitHub authorization to the system browser. */
private fun openExternalGitHubUri(context: Context, value: String): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(value)).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return runCatching { context.startActivity(intent) }.isSuccess
}

@Composable
private fun GitHubAccountAvatar(avatarUrl: String) {
    val avatar by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = avatarUrl
    ) {
        value = withContext(Dispatchers.IO) { downloadGitHubAvatar(avatarUrl) }
    }
    if (avatar != null) {
        Image(
            bitmap = avatar!!,
            contentDescription = null,
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(50))
        )
    } else {
        Icon(
            FeatherIcons.Github,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
    }
}

private fun downloadGitHubAvatar(value: String): androidx.compose.ui.graphics.ImageBitmap? {
    val url = runCatching { URL(value) }.getOrNull() ?: return null
    if (url.protocol != "https" || url.host != "avatars.githubusercontent.com") return null
    val connection = runCatching { url.openConnection() as? HttpURLConnection }.getOrNull() ?: return null
    return try {
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Accept", "image/*")
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
        val contentLength = connection.contentLengthLong
        if (contentLength > MAX_GITHUB_AVATAR_BYTES) return null
        val bytes = connection.inputStream.use { input ->
            val output = ByteArrayOutputStream(minOf(contentLength.coerceAtLeast(0).toInt(), 64 * 1024))
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_GITHUB_AVATAR_BYTES) return null
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 4096 || bounds.outHeight > 4096) {
            return null
        }
        val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 256)
        val bitmap = BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: return null
        bitmap.asImageBitmap()
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

private const val MAX_GITHUB_AVATAR_BYTES = 1_048_576

/**
 * 顶栏的工作区选择图标按钮：点击弹出选择/新建/删除面板。
 */
@Composable
fun WorkspaceIconButton(
    viewModel: WorkspaceViewModel,
    hasRunningSessions: () -> Boolean = { false },
    onSwitchConfirmed: () -> Unit = {},
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp
) {
    var showSheet by remember { mutableStateOf(false) }

    IconButton(
        onClick = { showSheet = true },
        modifier = modifier
    ) {
        Icon(
            FeatherIcons.Folder,
            contentDescription = stringResource(R.string.workspace_open),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(iconSize)
        )
    }

    // 远程工作区路径/默认工作区创建失败提示：消费后清除，避免重复弹 Toast
    val initError by viewModel.initError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(initError) {
        if (initError != null) {
            Toast.makeText(context, initError, Toast.LENGTH_LONG).show()
            viewModel.consumeInitError()
        }
    }

    WorkspaceSelectionHost(
        visible = showSheet,
        onDismiss = { showSheet = false },
        viewModel = viewModel,
        hasRunningSessions = hasRunningSessions,
        onSwitchConfirmed = onSwitchConfirmed
    )
}

/**
 * 工作区选择面板 + 切换确认弹窗的公共宿主：Chip 与 IconButton 两个入口共用，
 * 有会话运行时切换需先确认（确认回调由调用方执行停止逻辑）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceSelectionHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    viewModel: WorkspaceViewModel,
    hasRunningSessions: () -> Boolean,
    onSwitchConfirmed: () -> Unit
) {
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()
    val isLocalMode by viewModel.isLocalMode.collectAsStateWithLifecycle()
    val addError by viewModel.addError.collectAsStateWithLifecycle()
    val externalWarningDismissed by viewModel.externalWarningDismissed.collectAsStateWithLifecycle()
    val deleteExternalWorkspaceSessions by viewModel.deleteExternalWorkspaceSessions.collectAsStateWithLifecycle()
    val githubState by viewModel.github.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingWorkspaceSelect by remember { mutableStateOf<Workspace?>(null) }
    var showGitHubRepositories by remember { mutableStateOf(false) }
    var awaitingGitHubList by remember { mutableStateOf(false) }

    // 一键连接：设备流的验证页直接交给系统浏览器，中间不再弹任何窗口
    LaunchedEffect(githubState.browserUrl) {
        val url = githubState.browserUrl ?: return@LaunchedEffect
        val opened = openExternalGitHubUri(context, url)
        viewModel.onGitHubBrowserUrlHandled()
        if (!opened) viewModel.onGitHubBrowserUnavailable()
    }

    LaunchedEffect(githubState.userCode) {
        val code = githubState.userCode ?: return@LaunchedEffect
        runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            manager.setPrimaryClip(android.content.ClipData.newPlainText("github-device-code", code))
        }
        Toast.makeText(
            context,
            context.getString(R.string.workspace_github_device_code_hint, code),
            Toast.LENGTH_LONG
        ).show()
    }

    // 本次是「点了 GitHub 之后才连上」的，才自动展开仓库列表；已连接状态下打开面板不打扰
    LaunchedEffect(githubState.account?.login) {
        if (awaitingGitHubList && githubState.account != null) {
            awaitingGitHubList = false
            showGitHubRepositories = true
        }
    }
    var showExternalWarning by remember { mutableStateOf(false) }
    var dontShowExternalWarningAgain by remember { mutableStateOf(false) }

    fun select(ws: Workspace) {
        if (hasRunningSessions()) {
            pendingWorkspaceSelect = ws
        } else {
            onSwitchConfirmed()
            viewModel.selectWorkspace(ws.name)
            onDismiss()
        }
    }

    // 添加外部本地工作区失败提示：消费后清除，避免重复弹 Toast
    LaunchedEffect(addError) {
        if (addError != null) {
            Toast.makeText(context, addError, Toast.LENGTH_LONG).show()
            viewModel.consumeAddError()
        }
    }

    // 系统目录选择器：选设备上的目录注册为外部本地工作区
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.addExternalWorkspace(uri) { created ->
                if (created != null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.workspace_external_added, created.name),
                        Toast.LENGTH_LONG
                    ).show()
                    onDismiss()
                    select(created)
                }
            }
        }
    }

    LaunchedEffect(visible) {
        if (visible) viewModel.refreshAvailability()
    }

    if (visible) {
        WorkspaceSheet(
            workspaces = workspaces,
            current = current,
            showExternalEntry = isLocalMode,
            onAddExternal = {
                if (externalWarningDismissed) folderLauncher.launch(null)
                else showExternalWarning = true
            },
            onAddGitHub = {
                if (githubState.account != null) {
                    showGitHubRepositories = true
                } else {
                    awaitingGitHubList = true
                    viewModel.connectGitHub()
                }
            },
            onSelect = ::select,
            onCreate = { name ->
                viewModel.createWorkspace(name) { created ->
                    if (created != null) select(created)
                }
            },
            onDelete = { viewModel.deleteWorkspace(it.name) },
            onStopCurrentSessions = onSwitchConfirmed,
            deleteExternalWorkspaceSessions = deleteExternalWorkspaceSessions,
            onDismiss = onDismiss
        )
    }

    if (showExternalWarning) {
        AlertDialog(
            onDismissRequest = { showExternalWarning = false },
            title = { Text(stringResource(R.string.workspace_external_warning_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.workspace_external_warning_message))
                    Spacer(Modifier.height(Spacing.sm))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dontShowExternalWarningAgain = !dontShowExternalWarningAgain },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = dontShowExternalWarningAgain,
                            onCheckedChange = { dontShowExternalWarningAgain = it }
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.workspace_external_warning_dont_show_again))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontShowExternalWarningAgain) viewModel.setExternalWarningDismissed(true)
                    showExternalWarning = false
                    folderLauncher.launch(null)
                }) { Text(stringResource(R.string.workspace_external_warning_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showExternalWarning = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    if (showGitHubRepositories) {
        GitHubRepositoriesDialog(
            viewModel = viewModel,
            onDismiss = { showGitHubRepositories = false },
            onWorkspaceCreated = ::select
        )
    }

    pendingWorkspaceSelect?.let { ws ->
        AlertDialog(
            onDismissRequest = { pendingWorkspaceSelect = null },
            title = { Text(stringResource(R.string.workspace_switch)) },
            text = { Text(stringResource(R.string.workspace_switch_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    onSwitchConfirmed()
                    viewModel.selectWorkspace(ws.name)
                    pendingWorkspaceSelect = null
                    onDismiss()
                }) { Text(stringResource(R.string.workspace_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingWorkspaceSelect = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkspaceSheet(
    workspaces: List<Workspace>,
    current: Workspace?,
    showExternalEntry: Boolean,
    onAddExternal: () -> Unit,
    onAddGitHub: () -> Unit,
    onSelect: (Workspace) -> Unit,
    onCreate: (String) -> Unit,
    onDelete: (Workspace) -> Unit,
    onStopCurrentSessions: () -> Unit,
    deleteExternalWorkspaceSessions: Boolean,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Workspace?>(null) }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.common_workspace),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (showExternalEntry) {
                    TextButton(onClick = onAddExternal) {
                        Icon(FeatherIcons.HardDrive, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.workspace_add_local_dir))
                    }
                }
                TextButton(onClick = onAddGitHub) {
                    Icon(FeatherIcons.Github, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.workspace_add_github))
                }
                TextButton(onClick = { showCreateDialog = true }) {
                    Icon(FeatherIcons.Plus, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.workspace_new))
                }
            }

            if (workspaces.isEmpty()) {
                Text(
                    stringResource(R.string.workspace_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.md)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    items(workspaces, key = { it.name }) { ws ->
                        WorkspaceRow(
                            workspace = ws,
                            selected = ws.name == current?.name,
                            canDelete = workspaces.size > 1,
                            onClick = { onSelect(ws) },
                            onDelete = { pendingDelete = ws }
                        )
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateWorkspaceDialog(
            existingNames = workspaces.map { it.name },
            onConfirm = {
                onCreate(it)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    pendingDelete?.let { ws ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.workspace_delete)) },
            text = {
                Text(
                    if (ws.type == WorkspaceType.EXTERNAL_LOCAL) {
                        stringResource(
                            if (deleteExternalWorkspaceSessions) R.string.workspace_external_remove_confirm_sessions
                            else R.string.workspace_external_remove_confirm,
                            ws.name
                        )
                    } else {
                        stringResource(R.string.workspace_delete_confirm, ws.name)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (ws.name == current?.name) onStopCurrentSessions()
                    onDelete(ws)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun WorkspaceRow(
    workspace: Workspace,
    selected: Boolean,
    canDelete: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val external = workspace.type == WorkspaceType.EXTERNAL_LOCAL
    val disabled = !workspace.available
    val displayPath = when (workspace.type) {
        WorkspaceType.INTERNAL -> "projects/${workspace.name}"
        WorkspaceType.EXTERNAL_LOCAL, WorkspaceType.REMOTE -> workspace.path
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            when {
                workspace.sourceRepository != null -> FeatherIcons.Github
                external -> FeatherIcons.HardDrive
                else -> FeatherIcons.Folder
            },
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = workspace.name,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    disabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = displayPath,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    disabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            workspace.sourceRepository?.let { repositoryName ->
                Text(
                    text = stringResource(R.string.workspace_github_source, repositoryName),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (external && disabled) {
                Text(
                    text = stringResource(R.string.workspace_external_unavailable_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (canDelete) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    FeatherIcons.Trash2,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun CreateWorkspaceDialog(
    existingNames: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val duplicate = existingNames.any { it.equals(trimmed, ignoreCase = true) }
    val canConfirm = trimmed.isNotEmpty() && !duplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_new_workspace)) },
        text = {
            Column {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = stringResource(R.string.common_name),
                    placeholder = stringResource(R.string.workspace_name_hint),
                    isError = duplicate,
                    colors = dialogTextFieldColors()
                )
                if (duplicate) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        stringResource(R.string.workspace_name_exists),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canConfirm, onClick = { onConfirm(trimmed) }) { Text(stringResource(R.string.common_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
