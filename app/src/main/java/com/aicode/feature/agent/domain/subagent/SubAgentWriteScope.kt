package com.aicode.feature.agent.domain.subagent

/** Path-scope validation shared by dispatch-time overlap checks and execution-time enforcement. */
object SubAgentWriteScope {
    private const val VIRTUAL_WORKSPACE = "/root/workspace"
    private const val VIRTUAL_WORKSPACE_TILDE = "~/workspace"

    data class Result(val paths: List<String>?, val error: String? = null)

    fun parse(raw: String, projectRoot: String): Result {
        if (raw.isBlank()) return Result(null, "可写范围不能为空；只读任务请明确填写 none")
        if (raw.trim().equals("none", ignoreCase = true)) return Result(emptyList())
        val root = normalize(projectRoot, VIRTUAL_WORKSPACE)
            ?: return Result(null, "当前工作区根目录路径无效")
        val paths = raw.lines().flatMap { it.split(',') }.map(String::trim).filter(String::isNotEmpty)
        if (paths.isEmpty()) return Result(null, "可写范围至少需要一个路径或 none")
        if (paths.any(::containsPathExpression)) return Result(null, "可写范围不能包含通配符、变量展开或 shell 元字符")
        val normalized = paths.map { normalize(it, root) }
        if (normalized.any { it == null }) return Result(null, "可写范围包含无法解析的路径")
        val resolved = normalized.filterNotNull().distinct()
        if (resolved.any { !within(root, it) }) return Result(null, "子代理写入范围必须位于当前工作区内")
        return Result(resolved)
    }

    fun allows(scopes: List<String>?, path: String, projectRoot: String): Boolean {
        if (scopes == null) return true // Existing sessions created before scope enforcement remain compatible.
        val target = normalize(path, projectRoot) ?: return false
        return scopes.any { scope -> normalize(scope, projectRoot)?.let { within(it, target) } == true }
    }

    fun overlaps(left: List<String>, right: List<String>): Boolean = left.any { a ->
        right.any { b -> within(a, b) || within(b, a) }
    }

    fun normalize(path: String, base: String): String? {
        val input = path.trim().replace('\\', '/')
        if (input.isEmpty() || input.contains('\u0000') || containsPathExpression(input)) return null
        val normalizedBase = normalizeAbsolute(base) ?: return null
        val expanded = when {
            input == VIRTUAL_WORKSPACE_TILDE || input == VIRTUAL_WORKSPACE -> normalizedBase
            input.startsWith("$VIRTUAL_WORKSPACE_TILDE/") ->
                "$normalizedBase/${input.removePrefix("$VIRTUAL_WORKSPACE_TILDE/")}"
            input.startsWith("$VIRTUAL_WORKSPACE/") ->
                "$normalizedBase/${input.removePrefix("$VIRTUAL_WORKSPACE/")}"
            input.startsWith("~/") -> return null
            input.startsWith('/') -> input
            else -> "${normalizedBase.trimEnd('/')}/$input"
        }
        return normalizeAbsolute(expanded)
    }

    private fun normalizeAbsolute(path: String): String? {
        val parts = ArrayDeque<String>()
        path.trim().replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun within(parent: String, child: String): Boolean =
        child == parent || child.startsWith(parent.trimEnd('/') + "/")

    /** Scope entries are literal filesystem paths, never shell/glob expressions. */
    private fun containsPathExpression(path: String): Boolean =
        path.any { it in "*?[]{}$`'\";|&<>" } || path.contains("${'$'}(") || path.contains("${'$'}{")
}
