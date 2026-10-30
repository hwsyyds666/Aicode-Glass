package com.aicode.feature.agent.domain.subagent

import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.permission.BuiltInSafeCommands
import com.aicode.feature.agent.domain.permission.ShellCommandParser
import com.aicode.feature.agent.domain.tool.explorer.parseShellWords
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Enforces a child's declared write boundary before a permission prompt can approve the operation. */
object SubAgentWriteScopeGuard {
    data class Decision(val allowed: Boolean, val reason: String? = null)

    fun check(
        toolName: String,
        args: Map<String, JsonElement>,
        capabilities: Set<com.aicode.feature.agent.domain.tool.ToolCapability>,
        context: AgentContext,
        workingDirectory: String = context.projectRoot
    ): Decision {
        if (context.allowedWritePaths == null && context.allowedReadPaths == null) return Decision(true)
        val scopes = context.allowedWritePaths.orEmpty()
        val readScopes = context.allowedReadPaths.orEmpty()
        val writable = com.aicode.feature.agent.domain.tool.ToolCapability.WRITE_WORKSPACE in capabilities
        val executable = com.aicode.feature.agent.domain.tool.ToolCapability.EXECUTE_COMMANDS in capabilities
        val readable = com.aicode.feature.agent.domain.tool.ToolCapability.READ_WORKSPACE in capabilities
        if (!writable && !executable && !readable) return Decision(true)
        if (writable) {
            val path = ((args["path"] ?: args["output_path"]) as? JsonPrimitive)?.contentOrNull
                ?: return Decision(false, "子代理写入工具缺少可验证的 path，已按范围策略拒绝")
            if (!SubAgentWriteScope.allows(scopes, path, workingDirectory)) {
                return Decision(false, "写入路径越出子代理声明的可写范围：$path")
            }
        }
        if (readable) {
            val candidatePaths = readPaths(toolName, args, workingDirectory)
            val readableScopes = readScopes + scopes
            val outside = candidatePaths.firstOrNull { path -> readableScopes.none { SubAgentWriteScope.allows(listOf(it), path, workingDirectory) } }
            if (outside != null) return Decision(false, "读取路径越出子代理声明的只读/可写范围：$outside")
        }
        if (executable) {
            val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.contentOrNull
                ?: return Decision(false, "子代理命令缺少可验证的命令文本，已按范围策略拒绝")
            val analysis = ShellCommandParser.analyze(command)
            if (!analysis.analyzable || analysis.segments.isEmpty()) {
                return Decision(false, "无法静态验证该命令的写入路径；为避免越界写入，子代理不能执行此命令")
            }
            // A shell command can execute arbitrary code (or follow symlinks), so lexical path
            // inspection cannot enforce a worker's write boundary. Permit the immutable read-only
            // allowlist plus a narrow set of standard Gradle verification tasks. The latter must
            // run inside the declared write scope with an explicit container/remote environment;
            // shell redirection and parent traversal are always rejected because redirects are
            // intentionally omitted from parsed tokens.
            if (command.contains('>')) return Decision(false, "子代理 shell 重定向无法按路径范围可靠校验；请使用文件工具写入")
            if (PARENT_TRAVERSAL.containsMatchIn(command)) return Decision(false, "命令包含目录回退路径，无法证明读写范围安全")
            if (analysis.segments.any { !isSafeScopedReadOnlyCommand(it) } &&
                !isScopedBuildVerificationCommand(toolName, analysis.segments, args, scopes, context, workingDirectory)
            ) {
                return Decision(false, "子代理 shell 仅允许只读安全命令或范围内的标准 Gradle 构建/测试任务；代码修改请使用范围受限的文件工具")
            }
            val readableScopes = readScopes + scopes
            val outside = analysis.segments.flatMap { readOnlyCommandPaths(it, workingDirectory) }
                .firstOrNull { path -> readableScopes.none { SubAgentWriteScope.allows(listOf(it), path, workingDirectory) } }
            if (outside != null) return Decision(false, "命令读取了声明范围外的路径：$outside")
        }
        return Decision(true)
    }

    private fun readOnlyCommandPaths(tokens: List<String>, workingDirectory: String): List<String> {
        val program = ShellCommandParser.programPrefix(tokens)?.substringAfterLast('/')?.lowercase() ?: return emptyList()
        val rawArgs = tokens.dropWhile { it != ShellCommandParser.programPrefix(tokens) }.drop(1)
        val positional = mutableListOf<String>()
        var afterDoubleDash = false
        var skipValue = false
        rawArgs.forEach { token ->
            if (skipValue) {
                skipValue = false
                return@forEach
            }
            when {
                afterDoubleDash -> positional += token
                token == "--" -> afterDoubleDash = true
                token in VALUE_OPTIONS -> skipValue = true
                token.startsWith("--") && '=' in token -> Unit
                token.startsWith("-") && program in SEARCH_COMMANDS && token.length > 2 && token.startsWith("-e") -> Unit
                token.startsWith("-") -> Unit
                else -> positional += token
            }
        }
        val paths = when (program) {
            "ls", "tree", "cat", "head", "tail", "less", "more", "wc", "nl", "tac", "stat", "file", "du",
            "realpath", "readlink", "diff", "cmp", "comm", "sort" -> positional
            "grep", "egrep", "fgrep", "rg", "ack", "zgrep" ->
                if ("--files" in rawArgs || "--files-with-matches" in rawArgs || "--files-without-match" in rawArgs) positional
                else if ("-e" in rawArgs || "--regexp" in rawArgs || rawArgs.any { it.startsWith("--regexp=") }) positional
                else positional.drop(1)
            else -> emptyList()
        }
        val filtered = paths.filter { it.isNotBlank() && it !in setOf("-", "--") }
        return if (filtered.isEmpty() && program in PATH_READING_COMMANDS) listOf(workingDirectory) else filtered
    }

    private fun isSafeScopedReadOnlyCommand(tokens: List<String>): Boolean {
        val program = ShellCommandParser.programPrefix(tokens)?.substringAfterLast('/')?.lowercase() ?: return false
        val args = tokens.dropWhile { it != ShellCommandParser.programPrefix(tokens) }.drop(1)
        // Git content/history commands can read arbitrary repository paths, while status/config/remotes
        // can expose out-of-scope names or embedded credentials. Scoped agents use file tools instead.
        if (program == "git") return false
        if (program == "sort" && args.any { it == "-o" || it == "--output" || it.startsWith("-o") && it.length > 2 || it.startsWith("--output=") }) return false
        if (program in setOf("grep", "egrep", "fgrep", "zgrep") && args.any { it == "-f" || it == "--file" || it.startsWith("--file=") || it.startsWith("-f") && it.length > 2 }) return false
        if (program in setOf("rg", "ack") && args.any { it == "--ignore-file" || it.startsWith("--ignore-file=") || it == "--pre" || it.startsWith("--pre=") }) return false
        return BuiltInSafeCommands.isSafe(tokens)
    }

    private fun isScopedBuildVerificationCommand(
        toolName: String,
        segments: List<List<String>>,
        args: Map<String, JsonElement>,
        scopes: List<String>,
        context: AgentContext,
        workingDirectory: String
    ): Boolean {
        if (toolName != "Bash" || segments.size != 1) return false
        val env = (args["env"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
        if (env !in setOf("container", "linux", "proot", "remote", "ssh")) return false
        if (!SubAgentWriteScope.allows(scopes, workingDirectory, context.projectRoot)) return false

        val tokens = segments.single()
        val rawProgram = ShellCommandParser.programPrefix(tokens) ?: return false
        val program = rawProgram.substringAfterLast('/').lowercase()
        if (program !in setOf("gradle", "gradlew")) return false
        val rawArgs = tokens.dropWhile { it != rawProgram }.drop(1)
        if (rawArgs.isEmpty()) return false

        val taskPattern = Regex("(?:^|:)(?:test[A-Za-z0-9_]*|check|lint[A-Za-z0-9_]*|assemble[A-Za-z0-9_]*|compile[A-Za-z0-9_]*|verify[A-Za-z0-9_]*|build)$")
        val safeFlags = setOf("--offline", "--no-daemon", "--stacktrace", "--continue", "--console=plain", "--warning-mode=all")
        var index = 0
        var sawTask = false
        while (index < rawArgs.size) {
            val token = rawArgs[index]
            when {
                token in safeFlags -> Unit
                token == "--tests" -> {
                    val testFilter = rawArgs.getOrNull(index + 1) ?: return false
                    if (!Regex("[A-Za-z0-9_.*]+").matches(testFilter)) return false
                    index++
                }
                token.startsWith("--max-workers=") -> {
                    if (token.substringAfter('=').toIntOrNull()?.let { it in 1..64 } != true) return false
                }
                token.startsWith('-') -> return false
                taskPattern.containsMatchIn(token) -> sawTask = true
                else -> return false
            }
            index++
        }
        return sawTask
    }

    private fun readPaths(toolName: String, args: Map<String, JsonElement>, workingDirectory: String): List<String> {
        (args["path"] as? JsonPrimitive)?.contentOrNull?.let { return listOf(it) }
        (args["directory"] as? JsonPrimitive)?.contentOrNull?.let { return listOf(it) }
        for (key in listOf("paths", "images")) {
            (args[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
        }
        val raw = (args["args"] as? JsonPrimitive)?.contentOrNull
        if (toolName == "list" && raw != null) {
            val tokens = parseShellWords(raw) ?: return listOf(INVALID_PATH)
            val beforePipe = tokens.takeWhile { it != "|" }
            var afterOptions = false
            val paths = beforePipe.filter { token ->
                when {
                    token == "--" -> { afterOptions = true; false }
                    !afterOptions && token.startsWith("-") -> false
                    else -> true
                }
            }
            return paths.ifEmpty { listOf(workingDirectory) }
        }
        if (toolName == "search" && raw != null) {
            val tokens = parseShellWords(raw) ?: return listOf(INVALID_PATH)
            val positional = mutableListOf<String>()
            var afterOptions = false
            var hasExplicitPattern = false
            var index = 0
            while (index < tokens.size && tokens[index] != "|") {
                val token = tokens[index]
                if (afterOptions) {
                    positional += token
                    index++
                    continue
                }
                when {
                    token == "--" -> afterOptions = true
                    token in VALUE_OPTIONS -> {
                        if (index + 1 >= tokens.size || tokens[index + 1] == "|") return listOf(INVALID_PATH)
                        if (token == "-e" || token == "--regexp") hasExplicitPattern = true
                        index++
                    }
                    token.startsWith("--regexp=") || token.startsWith("-e") && token.length > 2 -> hasExplicitPattern = true
                    token.startsWith("--") && '=' in token -> Unit
                    token.startsWith("-") -> Unit
                    else -> positional += token
                }
                index++
            }
            val paths = if (hasExplicitPattern) positional else positional.drop(1)
            return paths.ifEmpty { listOf(workingDirectory) }
        }
        return listOf(workingDirectory)
    }

    private val INVALID_PATH = "\u0000"
    private val PARENT_TRAVERSAL = Regex("(?:^|[/\\\\])\\.\\.(?:[/\\\\]|$)")
    private val PATH_READING_COMMANDS = setOf(
        "ls", "tree", "cat", "head", "tail", "less", "more", "wc", "nl", "tac", "stat", "file", "du",
        "realpath", "readlink", "diff", "cmp", "comm", "sort", "grep", "egrep", "fgrep", "rg", "ack", "zgrep"
    )
    private val SEARCH_COMMANDS = setOf("grep", "egrep", "fgrep", "rg", "ack", "zgrep")
    private val VALUE_OPTIONS = setOf(
        "-n", "--lines", "-A", "--after-context", "-B", "--before-context", "-C", "--context",
        "-e", "--regexp", "-f", "--file", "-g", "--glob", "-m", "--max-count", "-t", "--type",
        "-T", "--type-not", "-j", "--threads"
    )
}
