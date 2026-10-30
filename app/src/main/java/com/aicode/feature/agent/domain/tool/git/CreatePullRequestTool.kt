package com.aicode.feature.agent.domain.tool.git

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ParameterType
import com.aicode.feature.agent.domain.tool.PendingToolPermission
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.tool.ToolParameter
import com.aicode.feature.agent.domain.tool.ToolPermissionPolicy
import com.aicode.feature.agent.domain.tool.ToolResult
import com.aicode.feature.workspace.data.repository.GitHubOAuthRepository
import com.aicode.feature.workspace.data.repository.isSafeGitRef
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import javax.inject.Inject

/** Creates a PR through GitHub's REST API. EXTERNAL_TOOL keeps this one-shot human-approved in every auth mode. */
class CreatePullRequestTool @Inject constructor(
    private val commandEngine: com.aicode.feature.git.domain.GitCommandEngine,
    private val github: GitHubOAuthRepository
) : AgentTool() {
    override val name = "createPullRequest"
    override val description = "为当前 GitHub 工作区创建 Pull Request。仅在用户明确要求创建 PR 时调用；必须填写 repository、head、base、title 和 body。repository 必须与当前工作区 origin 完全匹配，head 必须是当前检出的分支；工具会再次验证两者。此操作会发起外部变更，并且每次都需要用户单次确认；不会推送分支，也不会把 OAuth token 暴露给模型。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXTERNAL_TOOL)

    override val parameters = linkedMapOf(
        "repository" to ToolParameter("repository", ParameterType.STRING, "GitHub 仓库 owner/name；必须与当前 origin 匹配。", required = true),
        "head" to ToolParameter("head", ParameterType.STRING, "当前已推送的源分支名；必须等于当前检出的本地分支。", required = true),
        "base" to ToolParameter("base", ParameterType.STRING, "目标分支名，例如 main。", required = true),
        "title" to ToolParameter("title", ParameterType.STRING, "Pull Request 标题（1–256 字符）。", required = true),
        "body" to ToolParameter("body", ParameterType.STRING, "Pull Request 描述（最多 65536 字符）。", required = true)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult =
        ToolResult.Error("创建 Pull Request 需要当前工作区上下文。", "CONTEXT_REQUIRED")

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val repository = textArg(args, "repository")
            ?: return ToolResult.Error("缺少 repository（owner/name）。", "INVALID_ARGS")
        val head = textArg(args, "head")
            ?: return ToolResult.Error("缺少 head 源分支。", "INVALID_ARGS")
        val base = textArg(args, "base")
            ?: return ToolResult.Error("缺少 base 目标分支。", "INVALID_ARGS")
        val title = textArg(args, "title")
            ?: return ToolResult.Error("缺少 PR 标题。", "INVALID_ARGS")
        val body = textArg(args, "body")
            ?: return ToolResult.Error("缺少 PR 描述；无正文时请传空字符串。", "INVALID_ARGS")
        if (context.projectRoot.isBlank()) return ToolResult.Error("当前会话没有工作区，无法创建 PR。", "NO_WORKSPACE")
        if (!isSafeGitRef(head) || !isSafeGitRef(base)) return ToolResult.Error("分支名无效。", "INVALID_BRANCH")
        if (title.isBlank() || title.length > MAX_TITLE_CHARS) return ToolResult.Error("PR 标题必须为 1–256 个字符。", "INVALID_TITLE")
        if (body.length > MAX_BODY_CHARS) return ToolResult.Error("PR 描述过长（最多 $MAX_BODY_CHARS 个字符）。", "BODY_TOO_LARGE")

        return try {
            val remoteResult = commandEngine.runCommandSyncWithExit(
                command = "git remote get-url origin",
                projectPath = context.projectRoot,
                timeoutMs = GIT_QUERY_TIMEOUT_MS
            )
            if (remoteResult.exitCode != 0) return ToolResult.Error("无法读取当前工作区的 origin；请先配置 GitHub remote。", "ORIGIN_UNAVAILABLE")
            val remote = remoteResult.output.trim().lineSequence().filter(String::isNotBlank).singleOrNull()
                ?: return ToolResult.Error("origin 返回了无法识别的内容，已拒绝创建 PR。", "INVALID_ORIGIN")
            val actualRepository = parseGitHubRepositoryFullName(remote)
                ?: return ToolResult.Error("当前 origin 不是安全的 github.com 仓库地址。", "UNSUPPORTED_REMOTE")
            if (!actualRepository.equals(repository, ignoreCase = true)) {
                return ToolResult.Error("repository 与当前 origin 不匹配（origin: $actualRepository）。", "REPOSITORY_MISMATCH")
            }

            val branchResult = commandEngine.runCommandSyncWithExit(
                command = "git branch --show-current",
                projectPath = context.projectRoot,
                timeoutMs = GIT_QUERY_TIMEOUT_MS
            )
            val actualHead = branchResult.output.trim()
            if (branchResult.exitCode != 0 || !isSafeGitRef(actualHead)) {
                return ToolResult.Error("无法确认当前分支；请退出 detached HEAD 并重试。", "BRANCH_UNAVAILABLE")
            }
            if (actualHead != head) {
                return ToolResult.Error("head 与当前检出分支不匹配（当前：$actualHead）。", "HEAD_MISMATCH")
            }

            val localCommitResult = commandEngine.runCommandSyncWithExit(
                command = "git rev-parse HEAD",
                projectPath = context.projectRoot,
                timeoutMs = GIT_QUERY_TIMEOUT_MS
            )
            val localCommit = localCommitResult.output.trim()
            if (localCommitResult.exitCode != 0 || !GIT_OBJECT_ID.matches(localCommit)) {
                return ToolResult.Error("无法确认当前提交，已拒绝创建 PR。", "COMMIT_UNAVAILABLE")
            }
            val remoteCommit = github.branchHeadSha(actualRepository, actualHead)
            if (!remoteCommit.equals(localCommit, ignoreCase = true)) {
                return ToolResult.Error("当前分支还未推送，或远端提交与本地不同；先按权限流程推送后再创建 PR。", "HEAD_NOT_PUSHED")
            }

            val pullRequest = github.createPullRequest(actualRepository, title.trim(), body, actualHead, base)
            ToolResult.Success(
                kotlinx.serialization.json.buildJsonObject {
                    put("number", pullRequest.number)
                    put("title", pullRequest.title)
                    put("url", pullRequest.htmlUrl)
                    put("content", "Pull Request #${pullRequest.number} 已创建：${pullRequest.htmlUrl}")
                }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            ToolResult.Error(error.message ?: "创建 Pull Request 失败。", "CREATE_PULL_REQUEST_FAILED")
        }
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val repository = textArg(args, "repository").orEmpty()
        val head = textArg(args, "head").orEmpty()
        val base = textArg(args, "base").orEmpty()
        val title = textArg(args, "title").orEmpty()
        val bodyPreview = textArg(args, "body").orEmpty().take(PERMISSION_BODY_PREVIEW_CHARS)
        val suffix = if (textArg(args, "body").orEmpty().length > PERMISSION_BODY_PREVIEW_CHARS) "…" else ""
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认创建 GitHub Pull Request",
            summary = "$repository：$head → $base",
            details = "标题：$title\n仓库：$repository\n源分支：$head\n目标分支：$base\n\n正文预览：\n$bodyPreview$suffix",
            argsPreview = argsPreview,
            rememberablePatterns = emptyList(),
            rememberDisabledReason = "外部 Pull Request 操作仅支持单次确认"
        )
    }

    private fun textArg(args: Map<String, JsonElement>, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val MAX_TITLE_CHARS = 256
        const val MAX_BODY_CHARS = 65_536
        const val PERMISSION_BODY_PREVIEW_CHARS = 2_000
        const val GIT_QUERY_TIMEOUT_MS = 15_000L
        val GIT_OBJECT_ID = Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")
    }
}

internal fun parseGitHubRepositoryFullName(remote: String): String? {
    val normalized = remote.trim()
    val scp = SCP_GITHUB_REMOTE.matchEntire(normalized)?.groupValues?.getOrNull(1)
    if (scp != null) return normalizeGitHubSlug(scp)

    val uri = runCatching { java.net.URI(normalized) }.getOrNull() ?: return null
    if (!uri.scheme.equals("https", ignoreCase = true) && !uri.scheme.equals("ssh", ignoreCase = true)) return null
    if (!uri.host.equals("github.com", ignoreCase = true) || uri.rawQuery != null || uri.rawFragment != null) return null
    if (uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo != null) return null
    if (uri.scheme.equals("ssh", ignoreCase = true) && uri.rawUserInfo?.let { it != "git" } == true) return null
    return normalizeGitHubSlug(uri.path.trim('/'))
}

private fun normalizeGitHubSlug(rawPath: String): String? {
    val path = rawPath.removeSuffix(".git")
    val parts = path.split('/')
    if (parts.size != 2 || parts.any { !it.matches(Regex("[A-Za-z0-9_.-]{1,100}")) }) return null
    return parts.joinToString("/")
}

private val SCP_GITHUB_REMOTE = Regex("^(?:[^@/:]+@)?github\\.com:([A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}(?:\\.git)?)$")
