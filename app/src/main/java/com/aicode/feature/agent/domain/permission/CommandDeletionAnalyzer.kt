package com.aicode.feature.agent.domain.permission

/** 静态识别 shell 删除命令及其目标；解析不完整时仍返回删除候选并按高风险处理。 */
object CommandDeletionAnalyzer {
    data class Target(
        val path: String,
        val outsideWorkspace: Boolean,
        val wildcard: Boolean
    )

    data class Analysis(
        val isDeletion: Boolean,
        val parseCertain: Boolean,
        val targets: List<Target>,
        val recursive: Boolean,
        val hardBlockReason: String? = null,
        val riskLabels: List<String> = emptyList()
    )

    private data class Candidate(
        val path: String,
        val mayDeleteDirectoryItself: Boolean = true,
        val wildcard: Boolean = false
    )

    private val scriptDeleteCall = Regex(
        "(?:(?:os|File)\\.(?:remove|unlink|rmdir|delete|removeall)|(?:shutil|FileUtils)\\.(?:rmtree|rm|rm_r|rm_rf|remove|remove_entry)|Path\\([^)]*\\)\\.(?:unlink|rmdir)|(?:fs|require\\(['\"]fs['\"]\\))(?:\\.promises)?\\.(?:rm|unlink|rmdir)(?:Sync)?)\\s*\\(\\s*(['\"])(.*?)\\1",
        RegexOption.IGNORE_CASE
    )
    private val scriptDeleteMarker = Regex(
        "(?:(?:os|File)\\.(?:remove|unlink|rmdir|delete|removeall)|(?:shutil|FileUtils)\\.(?:rmtree|rm|rm_r|rm_rf|remove|remove_entry)|Path\\([^)]*\\)\\.(?:unlink|rmdir)|(?:fs|require\\(['\"]fs['\"]\\))(?:\\.promises)?\\.(?:rm|unlink|rmdir)(?:Sync)?)\\s*\\(",
        RegexOption.IGNORE_CASE
    )
    private val quotedScriptPath = Regex("(['\"])(/[^'\"]*|~(?:/[^'\"]*)?|\\\$HOME(?:/[^'\"]*)?|\\\$\\{HOME\\}(?:/[^'\"]*)?)\\1")
    private val dynamicProtectedScriptPath = Regex(
        "(?:Path\\.home\\(\\)|Path\\.cwd\\(\\)|os\\.getcwd\\(\\)|expanduser\\(\\s*['\"]~['\"]\\s*\\)|File\\.expand_path\\(\\s*['\"]~['\"])",
        RegexOption.IGNORE_CASE
    )
    private val protectedSystemRoots = setOf(
        "/apex", "/bin", "/boot", "/data", "/dev", "/etc", "/home", "/lib", "/lib64", "/lost+found",
        "/media", "/metadata", "/mnt", "/odm", "/opt", "/proc", "/product", "/root", "/run", "/sbin",
        "/srv", "/sys", "/system", "/usr", "/var", "/vendor"
    )
    private val rootLevelGlob = Regex("^/[^/]*[*?][^/]*$")
    private val opaqueDeleteMarker = Regex(
        "(?:\\b(?:rm|rmdir|del|erase|unlink|remove-item)\\b|\\bfind\\b[^;\\n]*\\s-delete\\b|\\bgit\\s+(?:clean|rm)\\b|\\brsync\\b[^;\\n]*--delete)",
        RegexOption.IGNORE_CASE
    )
    private val wrappedOpaqueDeleteMarker = Regex(
        "\\b(?:sudo|su|doas|env|command|busybox|timeout|nice|nohup|exec|sh|bash|zsh|powershell|pwsh|cmd)\\b[^;\\n]*\\b(?:rm|rmdir|del|erase|unlink|remove-item|clean)\\b",
        RegexOption.IGNORE_CASE
    )
    private val opaqueAbsoluteDeleteTarget = Regex(
        "\\b(?:rm|rmdir|del|erase|unlink|remove-item)\\b\\s+(?:-[^\\s]+\\s+)*(?:--\\s+)?(['\"]?)(/[^'\"\\s);]*|~[^'\"\\s);]*|\\\$HOME(?:/[^'\"\\s);]*)?)\\1",
        RegexOption.IGNORE_CASE
    )
    private val wrapperPrograms = setOf("sudo", "su", "doas", "env", "command", "busybox", "timeout", "nice", "nohup", "exec", "powershell", "pwsh", "cmd")
    private val deletionPrograms = setOf("rm", "rmdir", "del", "erase", "unlink", "remove-item", "find", "git", "rsync")

    fun analyze(command: String, workingDirectory: String, workspaceRoot: String): Analysis {
        val effectiveWorkingDirectory = workingDirectory.ifBlank { "/root/workspace" }
        val workspaceIsKnown = workspaceRoot.isNotBlank() || workingDirectory.isNotBlank()
        val effectiveWorkspaceRoot = workspaceRoot.ifBlank { effectiveWorkingDirectory }
        val candidates = mutableListOf<Candidate>()
        val risks = linkedSetOf<String>()
        var recognized = false
        var certain = true
        var recursive = false
        val shell = ShellCommandParser.analyze(command)
        if (!shell.analyzable) {
            certain = false
            risks += "Shell 结构包含命令替换、子 Shell 或其他无法静态确认的语法"
        }

        for (tokens in shell.segments) {
            val rawProgram = ShellCommandParser.programPrefix(tokens) ?: continue
            var program = rawProgram.substringAfterLast('/').lowercase()
            val programIndex = tokens.indexOf(rawProgram)
            var args = tokens.drop((programIndex + 1).coerceAtLeast(1))
            if (program in wrapperPrograms) {
                val nestedIndex = args.indexOfFirst { it.substringAfterLast('/').lowercase() in deletionPrograms }
                if (nestedIndex >= 0 && args[nestedIndex].substringAfterLast('/').lowercase() != "xargs") {
                    program = args[nestedIndex].substringAfterLast('/').lowercase()
                    args = args.drop(nestedIndex + 1)
                }
            }
            when (program) {
                "rm" -> {
                    val info = ShellCommandParser.parseRmInfo(listOf("rm") + args)
                    recognized = true
                    recursive = recursive || info.isRecursive
                    if (info.isRecursive) risks += "递归删除"
                    val parsed = info.targetPaths.map { path ->
                        Candidate(path, mayDeleteDirectoryItself = true, wildcard = path.contains('*') || path.contains('?'))
                    }
                    if (parsed.isEmpty()) certain = false else candidates += parsed
                }
                "rmdir", "del", "unlink", "remove-item", "erase" -> {
                    recognized = true
                    val pathArgs = if (program in setOf("rmdir", "del", "erase")) {
                        args.filterNot { it.lowercase() in setOf("/s", "/q", "/f", "/a", "/s/q", "/q/s") }
                    } else {
                        extractPathArguments(args, powershell = program == "remove-item")
                    }
                    val hasRecursive = args.any { it.equals("-r", true) || it.equals("-recurse", true) || it.equals("-recursive", true) || it.equals("/s", true) }
                    recursive = recursive || hasRecursive
                    if (hasRecursive) risks += "递归删除"
                    if (pathArgs.isEmpty()) certain = false
                    candidates += pathArgs.map { path -> Candidate(path, wildcard = hasWildcard(path)) }
                }
                "find" -> if (args.any { it == "-delete" || it == "-delete=true" } ||
                    ("-exec" in args && args.any { it.substringAfterLast('/').lowercase() in setOf("rm", "unlink", "rmdir") })
                ) {
                    recognized = true
                    recursive = true
                    risks += "按条件批量删除"
                    if ("-exec" in args) {
                        certain = false
                        risks += "find -exec 的目标取决于匹配结果"
                    }
                    val root = args.firstOrNull { !it.startsWith('-') } ?: "."
                    // find -delete removes matching entries, not the traversal root itself.
                    candidates += Candidate(root, mayDeleteDirectoryItself = false, wildcard = hasWildcard(root))
                }
                "git" -> {
                    val gitSubcommandIndex = args.indexOfFirst { it == "clean" || it == "rm" }
                    when (args.getOrNull(gitSubcommandIndex)) {
                    "clean" -> {
                        recognized = true
                        recursive = true
                        certain = false
                        risks += "清理 Git 未跟踪文件"
                        val gitRoot = args.getOrNull(1)?.takeIf { args.firstOrNull() == "-C" }
                        candidates += Candidate(
                            gitRoot?.let { "$it/." } ?: ".",
                            mayDeleteDirectoryItself = false
                        )
                    }
                    "rm" -> {
                        recognized = true
                        val gitRoot = args.getOrNull(1)?.takeIf { args.firstOrNull() == "-C" }
                        val pathArgs = gitRmPaths(args.drop(gitSubcommandIndex + 1)).map { path ->
                            if (gitRoot != null && !path.startsWith('/')) "$gitRoot/$path" else path
                        }
                        if (pathArgs.isEmpty()) certain = false
                        candidates += pathArgs.map { path -> Candidate(path, wildcard = hasWildcard(path)) }
                    }
                    }
                }
                "rsync" -> if (args.any { it == "--delete" || it.startsWith("--delete-") }) {
                    recognized = true
                    recursive = true
                    certain = false
                    risks += "同步时删除目标端多余文件"
                    val destination = args.lastOrNull { !it.startsWith('-') }
                    if (destination == null) certain = false
                    else candidates += Candidate(destination, mayDeleteDirectoryItself = false, wildcard = hasWildcard(destination))
                }
                "xargs" -> if (args.any { it.substringAfterLast('/').lowercase() in setOf("rm", "rmdir", "unlink", "del", "remove-item") }) {
                    recognized = true
                    certain = false
                    recursive = args.any { it == "-r" || it == "-R" || it == "--recursive" }
                    risks += "xargs 删除目标来自标准输入，无法静态枚举"
                    if (recursive) risks += "递归删除"
                }
            }
        }

        val inlineCalls = scriptDeleteCall.findAll(command).toList()
        val hasScriptDelete = scriptDeleteMarker.containsMatchIn(command)
        if (hasScriptDelete) {
            recognized = true
            recursive = recursive || command.contains("shutil.rmtree", ignoreCase = true) ||
                (command.contains("fs.rm", ignoreCase = true) && command.contains("recursive", ignoreCase = true))
            if (recursive) risks += "脚本内递归删除"
            if (inlineCalls.isEmpty()) certain = false
            candidates += inlineCalls.map { match -> Candidate(match.groupValues[2], wildcard = hasWildcard(match.groupValues[2])) }
            if (inlineCalls.isEmpty()) {
                candidates += quotedScriptPath.findAll(command).map { match ->
                    Candidate(match.groupValues[2], wildcard = hasWildcard(match.groupValues[2]))
                }
            }
            if (dynamicProtectedScriptPath.containsMatchIn(command)) {
                candidates += Candidate(if (command.contains("cwd", ignoreCase = true)) effectiveWorkingDirectory else "/root")
            }
        }

        if (!recognized && opaqueDeleteMarker.containsMatchIn(command) &&
            (!shell.analyzable || wrappedOpaqueDeleteMarker.containsMatchIn(command))
        ) {
            recognized = true
            certain = false
            risks += "命令替换或脚本中的删除目标无法静态解析"
            candidates += opaqueAbsoluteDeleteTarget.findAll(command).map { match ->
                Candidate(match.groupValues[2], wildcard = hasWildcard(match.groupValues[2]))
            }
        }
        if (!recognized) return Analysis(false, true, emptyList(), false)
        val workspace = normalize(effectiveWorkspaceRoot)
        val normalizedTargets = candidates.distinctBy { it.path }.map { candidate ->
            val resolved = resolve(candidate.path, effectiveWorkingDirectory)
            val inside = workspaceIsKnown && resolved != null &&
                (workspace == "/" || resolved == workspace || resolved.startsWith("$workspace/"))
            val wildcard = candidate.wildcard || hasWildcard(candidate.path)
            if (wildcard) risks += "通配符目标"
            Target(candidate.path, outsideWorkspace = !inside, wildcard = wildcard) to (resolved to candidate.mayDeleteDirectoryItself)
        }

        val hardBlock = normalizedTargets.firstNotNullOfOrNull { (target, resolvedAndSelf) ->
            val (resolved, mayDeleteRoot) = resolvedAndSelf
            if (resolved == null) return@firstNotNullOfOrNull null
            val workspaceRootTarget = resolved == workspace && mayDeleteRoot
            val homeChild = resolved.removePrefix("/home/")
            val protectedHome = resolved == "/root" || resolved == "/root/*" || resolved == "/home" ||
                (homeChild.isNotBlank() && !homeChild.contains('/'))
            val protectedSystem = protectedSystemRoots
                .filterNot { it == "/root" || it == "/home" }
                .any { resolved == it || resolved.startsWith("$it/") }
            val protectedRoot = resolved == "/" || protectedHome || protectedSystem ||
                resolved == "/tmp" || rootLevelGlob.matches(resolved)
            when {
                workspaceRootTarget -> "安全防护：禁止删除工作区根目录本身（${target.path}）"
                protectedRoot -> "安全防护：禁止删除根目录、用户主目录或系统关键目录（${target.path}）"
                else -> null
            }
        }

        if (normalizedTargets.any { it.first.outsideWorkspace }) risks += "目标位于工作区外或无法确认"
        if (!certain) risks += "无法完整静态解析目标"
        return Analysis(
            isDeletion = true,
            parseCertain = certain,
            targets = normalizedTargets.map { it.first },
            recursive = recursive,
            hardBlockReason = hardBlock,
            riskLabels = risks.toList()
        )
    }

    private fun extractPathArguments(args: List<String>, powershell: Boolean): List<String> {
        val paths = mutableListOf<String>()
        var afterDoubleDash = false
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            if (arg == "--") {
                afterDoubleDash = true
            } else if (powershell && arg.lowercase() in setOf("-path", "-literalpath")) {
                args.getOrNull(index + 1)?.let(paths::add)
                index++
            } else if (afterDoubleDash || !arg.startsWith('-')) {
                paths += arg
            }
            index++
        }
        return paths
    }

    private fun gitRmPaths(args: List<String>): List<String> {
        val ignoredOptions = setOf("-f", "--force", "-r", "-q", "--quiet", "--cached", "--ignore-unmatch")
        val optionWithValue = setOf("--pathspec-from-file", "--pathspec-file-nul")
        val paths = mutableListOf<String>()
        var index = 0
        var afterDoubleDash = false
        while (index < args.size) {
            val arg = args[index]
            when {
                arg == "--" -> afterDoubleDash = true
                arg in optionWithValue -> index++
                afterDoubleDash || !arg.startsWith('-') -> paths += arg
                arg in ignoredOptions -> Unit
                else -> Unit
            }
            index++
        }
        return paths
    }

    private fun hasWildcard(path: String): Boolean = path.contains('*') || path.contains('?') || path.contains('[')

    private fun resolve(path: String, workingDirectory: String): String? {
        val expanded = when {
            path == "~" -> "/root"
            path.startsWith("~/") -> "/root/${path.drop(2)}"
            path == "\$HOME" || path == "\${HOME}" -> "/root"
            path.startsWith("\$HOME/") -> "/root/${path.drop(6)}"
            path.startsWith("\${HOME}/") -> "/root/${path.drop(8)}"
            path in setOf("\$PWD", "\${PWD}", "\$(pwd)", "`pwd`") -> workingDirectory
            path.startsWith("\$PWD/") -> "$workingDirectory/${path.drop(5)}"
            path.startsWith("\${PWD}/") -> "$workingDirectory/${path.drop(7)}"
            path.startsWith("\$(pwd)/") -> "$workingDirectory/${path.drop(7)}"
            path.startsWith('/') -> path
            path.contains(":") -> return null
            else -> "$workingDirectory/$path"
        }
        return normalize(expanded)
    }

    private fun normalize(path: String): String {
        val parts = ArrayDeque<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return "/" + parts.joinToString("/")
    }
}
