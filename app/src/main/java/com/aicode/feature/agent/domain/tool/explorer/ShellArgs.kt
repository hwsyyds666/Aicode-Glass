package com.aicode.feature.agent.domain.tool.explorer

/**
 * explorer 工具（search/list）共用的 shell 参数解析与安全白名单逻辑。
 *
 * 模型常把 `| head -30` 这类 shell 管道直接写进工具 args（如 `search(args="-n \"foo\" src | head -30")`）。
 * 这里把 `|` 从 rg/ls 参数中分离出来单独解析：**只放行 head 行数截断**，其余管道命令一律拒绝，
 * 防止借管道语法在容器里执行任意命令。
 */

/** 按 shell 词法把 args 拆成 tokens；引号未闭合返回 null。`\|`/`|` 解析为独立 token `|`，引号内的 `|` 是字面量。 */
fun parseShellWords(input: String): List<String>? {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var tokenStarted = false
    var i = 0
    while (i < input.length) {
        val c = input[i]
        when {
            quote == '\'' -> {
                if (c == '\'') quote = null else current.append(c)
                tokenStarted = true
            }
            quote == '"' -> {
                when (c) {
                    '"' -> quote = null
                    '\\' -> {
                        if (i + 1 < input.length) {
                            i++
                            current.append(input[i])
                        } else {
                            current.append(c)
                        }
                    }
                    else -> current.append(c)
                }
                tokenStarted = true
            }
            c.isWhitespace() -> {
                if (tokenStarted) {
                    result.add(current.toString())
                    current.clear()
                    tokenStarted = false
                }
            }
            c == '\'' || c == '"' -> {
                quote = c
                tokenStarted = true
            }
            c == '\\' -> {
                if (i + 1 < input.length) {
                    i++
                    current.append(input[i])
                } else {
                    current.append(c)
                }
                tokenStarted = true
            }
            else -> {
                current.append(c)
                tokenStarted = true
            }
        }
        i++
    }
    if (quote != null) return null
    if (tokenStarted) result.add(current.toString())
    return result
}

/**
 * 把 tokens 按独立的 `|` 拆为 (管道前的 tokens, 管道段列表)。
 * 无管道返回 (tokens, 空列表)；`|` 前没有任何参数返回 null。
 * 引号内（如 `"a|b"`）的 `|` 属于 token 本身，不会触发拆分。
 */
fun splitPipes(tokens: List<String>): Pair<List<String>, List<List<String>>>? {
    val pipeIndex = tokens.indexOf("|")
    if (pipeIndex < 0) return tokens to emptyList()
    val headTokens = tokens.subList(0, pipeIndex)
    if (headTokens.isEmpty()) return null
    val segments = mutableListOf<List<String>>()
    var i = pipeIndex
    while (i < tokens.size) {
        if (tokens[i] != "|") return null
        i++
        val segStart = i
        while (i < tokens.size && tokens[i] != "|") i++
        segments += tokens.subList(segStart, i)
    }
    return headTokens to segments
}

/**
 * head 管道段白名单：仅 `head`（默认 10 行）、`head -N`、`head -n N`，N 必须纯数字。
 * 返回该段允许输出的行数；命令不是 head、或带其它参数（含 `;`、重定向、`head -c` 等）返回 null。
 */
fun headLinesOf(seg: List<String>): Int? = when {
    seg.isEmpty() -> null
    seg[0] != "head" -> null
    seg.size == 1 -> 10
    seg.size == 2 -> {
        val n = seg[1]
        if (n.length > 1 && n[0] == '-' && n.drop(1).all(Char::isDigit)) n.drop(1).toInt() else null
    }
    seg.size == 3 && seg[1] == "-n" -> {
        val n = seg[2]
        if (n.isNotEmpty() && n.all(Char::isDigit)) n.toInt() else null
    }
    else -> null
}

/**
 * 组装传给容器的 rg 搜索命令：基础参数 + 全部参数 token（单引号转义、`~/` 展开为 [home]），
 * 若带管道则原样追加白名单校验过的 `| head ...` 段。管道段含非 head 命令/非法参数时返回 null。
 *
 * [home] 为命令执行环境的 home（本地 PRoot 容器为 /root，远程 SSH 为远程用户 home），
 * 用于展开 `~/` 参数——quote 后 shell 不会自动展开 `~`，故在构造命令时显式展开。
 */
fun buildSearchCommand(tokens: List<String>, home: String = "/root", rgBin: String? = null): String? {
    val (rgTokens, pipeSegments) = splitPipes(tokens) ?: return null
    val args = mutableListOf(
        // rgBin：随包内置的静态 rg（真机无包管理器时用它），为空则用 PATH 里的 rg
        rgBin?.let(::shellQuote) ?: "rg",
        "--line-number",
        "--no-heading",
        "--with-filename",
        "--color",
        "never"
    )
    args.addAll(rgTokens.map { shellQuote(expandTilde(it, home)) })
    var command = args.joinToString(" ")
    for (seg in pipeSegments) {
        if (headLinesOf(seg) == null) return null
        command += " | " + seg.joinToString(" ")
    }
    return command
}

/** 把 `~`/`~/` 开头的路径参数展开为 [home] 路径，避免被单引号包裹后 shell 不展开 `~`。 */
fun expandTilde(arg: String, home: String = "/root"): String =
    when {
        arg == "~" -> home
        arg.startsWith("~/") -> home.trimEnd('/') + arg.removePrefix("~")
        else -> arg
    }

/**
 * grep 兜底命令的构建结果。
 *
 * @property command 供执行环境运行的完整 grep 命令（含白名单 `| head` 管道）
 * @property droppedFlags 被丢弃的 rg 旗标。这些旗标在 grep 里没有对应能力，但**丢弃不改变
 *           搜索语义**：例如 `-u/--no-ignore*` 要求"不遵守忽略规则"，而 grep 本身就不读任何
 *           忽略规则；`--hidden` 要求"含点文件"，而 `grep -r` 默认就包含。调用方应把该列表
 *           回传给调用者，避免出现"命令成功、结果为空、调用方以为真的没有匹配"的误判。
 */
data class GrepFallbackCommand(
    val command: String,
    val droppedFlags: List<String> = emptyList()
)

/**
 * 组装 rg 缺失时的 **grep 兜底**搜索命令：把 rg 参数翻译成 grep 参数，输出格式
 * （`文件:行号:内容`）与 [buildSearchCommand] 的 rg 输出一致。
 *
 * 设计原则（2026-10-24 修正）：**宽容降级，而不是拒绝执行**。旧实现遇到任何 rg 专有参数
 * 就返回 null，调用方只能报 RG_UNAVAILABLE —— 表现为"搜索 0 结果"或"工具不可用"，而实际
 * 上绝大多数 rg 旗标对 grep 来说要么天然成立（`-u/--no-ignore/--hidden`），要么只影响次要
 * 信息（`-M/--max-columns`、`--sort`、`--stats`）。现在只有**真的无法用 grep 表达**的参数
 * 才拒绝兜底：改变输出结构的 `--json/--vimgrep/--null/-0`，以及 `--files`（列文件模式）。
 *
 * 翻译细节：
 *  - 短旗标簇按 rg 语义展开（`-uu` → 两个 `-u`；`-ig` → `-i` + 缺值的 `-g`），
 *    带值旗标后面紧跟另一个旗标时视为**缺值并丢弃**，而不是把下一个旗标当值
 *    （否则 `-ig -n PATTERN` 会变成按 glob `-n` 过滤，直接 0 命中）；
 *  - `-g/--glob/--iglob` → `--include`，否定 glob（`!x`）→ `--exclude=x`；
 *  - `-t/--type` → 预置扩展名表 [RG_TYPE_GLOBS]，`-T/--type-not` → `--exclude`；
 *  - `-m/-A/-B/-C/-v/-x/-o/-c/-l/-w/-F/-i/-f/-q` 等 grep 原生支持，原样透传；
 *  - `-S/--smart-case`：模式串全小写时补 `-i`（与 rg smart-case 语义一致）；
 *  - `-a/--text/--binary`：去掉 `-I`（改为读取二进制文件）；
 *  - 正则按 POSIX ERE（-E）解释，与 rg 的 Rust 正则不完全一致（如 `\d` 不可用）。
 *
 * [minimal] 为真时只保留 `grep -rnI -E`（并丢弃 --include/--exclude/--exclude-dir），
 * 供个别精简 grep（toybox 老版本不认这些旗标）用法错误后重试。
 */
fun buildFallbackSearchCommand(tokens: List<String>, home: String = "/root", minimal: Boolean = false): GrepFallbackCommand? {
    val (searchTokens, pipeSegments) = splitPipes(tokens) ?: return null
    val args = normalizeSearchArgs(searchTokens) ?: return null

    val grepArgs = mutableListOf("grep", "-rnI", "-E")
    val includes = mutableListOf<String>()
    val excludes = mutableListOf<String>()
    val dropped = mutableListOf<String>()
    val positional = mutableListOf<String>()
    var hasRegexpFlag = false
    var smartCase = false
    var binaryAsText = false

    for (arg in args) {
        if (arg is SearchArg.Positional) {
            positional += arg.text
            continue
        }
        val flag = (arg as SearchArg.Flag).name
        val value = arg.value
        when (flag) {
            // —— grep 原生兼容：原样透传 ——
            "-i", "--ignore-case", "-F", "--fixed-strings", "-w", "--word-regexp",
            "-v", "--invert-match", "-x", "--line-regexp", "-o", "--only-matching",
            "-c", "--count", "-l", "--files-with-matches", "--files-without-match",
            "-q", "--quiet", "--silent" ->
                grepArgs += when (flag) {
                    "--files-without-match" -> "-L"
                    "--quiet", "--silent" -> "-q"
                    else -> flag
                }
            "-f", "--file" -> if (value != null) grepArgs += listOf("-f", shellQuote(value)) else dropped += flag
            "-m", "--max-count", "-A", "--after-context", "-B", "--before-context", "-C", "--context" -> {
                val short = when (flag) {
                    "--after-context" -> "-A"
                    "--before-context" -> "-B"
                    "--context" -> "-C"
                    else -> flag
                }
                if (value != null && value.isNotEmpty() && value.all(Char::isDigit)) {
                    grepArgs += listOf(short, value)
                } else {
                    dropped += flag
                }
            }
            "-e", "--regexp" -> if (value != null) {
                hasRegexpFlag = true
                grepArgs += listOf("-e", shellQuote(value))
            } else dropped += flag
            // —— 文件过滤：glob / type ——
            "-g", "--glob", "--iglob" -> when {
                value == null -> dropped += flag
                value.startsWith("!") -> excludes += value.removePrefix("!")
                else -> includes += value
            }
            "-t", "--type" -> {
                val globs = value?.let { RG_TYPE_GLOBS[it.lowercase()] }
                if (globs == null) dropped += "$flag ${value ?: ""}".trim() else includes += globs
            }
            "-T", "--type-not" -> {
                val globs = value?.let { RG_TYPE_GLOBS[it.lowercase()] }
                if (globs == null) dropped += "$flag ${value ?: ""}".trim() else excludes += globs
            }
            // —— 改变输出结构，grep 无法表达：拒绝兜底 ——
            "-0", "--null", "--null-data", "--json", "--vimgrep", "--files", "-r", "--replace" -> return null
            // —— 语义已被 grep 默认行为满足，或只影响次要信息：丢弃 ——
            "-u", "--no-ignore", "--no-ignore-vcs", "--no-ignore-dot", "--no-ignore-parent",
            "--no-ignore-global", "--no-ignore-exclude", "--hidden", "--no-config",
            "--no-require-git", "--no-messages", "-N", "--no-line-number", "-n", "--line-number",
            "--no-heading", "--with-filename", "--color", "--colour", "--stats", "--passthru",
            "--trim", "-U", "--multiline", "-M", "--max-columns", "--max-filesize",
            "--sort", "--sortr", "--threads", "-P", "--pcre2", "-z", "--search-zip", "--crlf",
            "--mmap", "--no-unicode", "--one-file-system", "--engine", "--dfa-size-limit",
            "--regex-size-limit", "--pre", "--pre-glob", "--context-separator", "--encoding",
            "--colors", "--type-add", "--hostname", "--path-separator", "--hyperlink-format",
            "--glob-case-insensitive", "--fixed-strings-off", "--auto-hybrid-regex",
            "--max-depth", "-L", "--follow" -> dropped += flag
            "-a", "--text", "--binary" -> binaryAsText = true
            "-S", "--smart-case" -> smartCase = true
            else -> dropped += flag
        }
    }

    // 模式来源：有 -e 时全部位置参数都是路径；否则第一个位置参数是模式
    if (!hasRegexpFlag && positional.isEmpty()) return null
    val pattern = if (hasRegexpFlag) null else positional.first()
    val paths = (if (hasRegexpFlag) positional else positional.drop(1))
        .map { expandTilde(it, home) }

    if (!minimal) {
        grepArgs += listOf("--exclude-dir=.git", "--exclude-dir=node_modules", "--exclude-dir=.gradle")
        includes.forEach { grepArgs += "--include=${shellQuote(it)}" }
        excludes.forEach { grepArgs += "--exclude=${shellQuote(it)}" }
    } else {
        dropped += includes.map { "-g $it" } + excludes.map { "-g !$it" }
    }
    if (binaryAsText) grepArgs.remove("-I")
    // smart-case：模式串里没有大写字母时按忽略大小写处理（与 rg 语义一致）
    if (smartCase && pattern != null && pattern.none(Char::isUpperCase)) grepArgs += "-i"

    val sb = StringBuilder(grepArgs.joinToString(" "))
    if (pattern != null) sb.append(" -e ").append(shellQuote(pattern))
    if (paths.isEmpty()) {
        // grep -r 不带路径会读 stdin，必须显式给目录（命令 cwd 已是工作区）
        sb.append(" .")
    } else {
        paths.forEach { sb.append(" ").append(shellQuote(it)) }
    }
    for (seg in pipeSegments) {
        if (headLinesOf(seg) == null) return null
        sb.append(" | ").append(seg.joinToString(" "))
    }
    return GrepFallbackCommand(sb.toString(), dropped.distinct())
}

/** 归一后的搜索参数：带值旗标（值可能为 null = 缺值）或位置参数。 */
private sealed interface SearchArg {
    data class Flag(val name: String, val value: String?) : SearchArg
    data class Positional(val text: String) : SearchArg
}

/** 短旗标中带值的字母（rg 语义：值可紧跟在同一 token 内，也可取下一个 token）。 */
private val SHORT_VALUE_CHARS: Set<Char> = setOf('e', 'f', 'A', 'B', 'C', 'm', 'g', 't', 'T', 'M', 'j', 'r')

/** 长旗标中带值的（无值时若下一个 token 不是旗标则取之）。 */
private val LONG_VALUE_FLAGS: Set<String> = setOf(
    "--regexp", "--glob", "--iglob", "--type", "--type-not", "--type-add", "--after-context",
    "--before-context", "--context", "--max-count", "--max-columns", "--max-filesize",
    "--max-depth", "--encoding", "--sort", "--sortr", "--pre", "--pre-glob", "--context-separator",
    "--colors", "--color", "--colour", "--engine", "--dfa-size-limit", "--regex-size-limit",
    "--threads", "--path-separator", "--hyperlink-format", "--replace"
)

/** rg `-t/--type` 常见类型到 glob 的映射；未收录的类型按缺值丢弃（搜索范围退回全量，不会漏匹配）。 */
private val RG_TYPE_GLOBS: Map<String, List<String>> = mapOf(
    "kt" to listOf("*.kt"), "kotlin" to listOf("*.kt", "*.kts"),
    "java" to listOf("*.java"),
    "py" to listOf("*.py"), "python" to listOf("*.py"),
    "js" to listOf("*.js", "*.jsx", "*.mjs"),
    "ts" to listOf("*.ts", "*.tsx"),
    "c" to listOf("*.c", "*.h"),
    "cpp" to listOf("*.cpp", "*.hpp", "*.cc", "*.hh", "*.cxx", "*.hxx"),
    "go" to listOf("*.go"),
    "rs" to listOf("*.rs"), "rust" to listOf("*.rs"),
    "rb" to listOf("*.rb"), "ruby" to listOf("*.rb"),
    "md" to listOf("*.md"), "markdown" to listOf("*.md"),
    "json" to listOf("*.json"),
    "yaml" to listOf("*.yaml", "*.yml"), "yml" to listOf("*.yaml", "*.yml"),
    "xml" to listOf("*.xml"),
    "html" to listOf("*.html", "*.htm"),
    "css" to listOf("*.css"),
    "sh" to listOf("*.sh"), "shell" to listOf("*.sh"),
    "swift" to listOf("*.swift"),
    "scala" to listOf("*.scala"),
    "php" to listOf("*.php"),
    "cs" to listOf("*.cs"),
    "gradle" to listOf("*.gradle", "*.gradle.kts"),
    "toml" to listOf("*.toml"),
    "sql" to listOf("*.sql")
)

/**
 * 把 rg 参数 token 归一为 [SearchArg] 序列：展开短旗标簇并按 rg 语义识别带值旗标。
 *
 * 宽容点：带值旗标后面紧跟另一个旗标（如 `-ig -n PATTERN` 里的 `-g`）视为**缺值**并保留为
 * `value == null`，而不是把下一个旗标当值——后者会变成"按 glob `-n` 过滤"，直接导致 0 命中。
 */
private fun normalizeSearchArgs(tokens: List<String>): List<SearchArg>? {
    val result = mutableListOf<SearchArg>()
    var afterDashDash = false
    var i = 0
    while (i < tokens.size) {
        val t = tokens[i]
        if (afterDashDash) {
            result += SearchArg.Positional(t)
            i++
            continue
        }
        when {
            t == "--" -> afterDashDash = true
            t == "-" -> result += SearchArg.Positional(t)
            t.startsWith("--") -> {
                val eq = t.indexOf('=')
                if (eq > 0) {
                    result += SearchArg.Flag(t.substring(0, eq), t.substring(eq + 1))
                } else if (t in LONG_VALUE_FLAGS) {
                    val next = tokens.getOrNull(i + 1)
                    if (next != null && !next.startsWith("-")) {
                        result += SearchArg.Flag(t, next)
                        i++
                    } else {
                        result += SearchArg.Flag(t, null)
                    }
                } else {
                    result += SearchArg.Flag(t, null)
                }
            }
            t.startsWith("-") -> {
                val cluster = t.removePrefix("-")
                var ci = 0
                while (ci < cluster.length) {
                    val c = cluster[ci]
                    val rest = cluster.substring(ci + 1)
                    if (c in SHORT_VALUE_CHARS) {
                        if (rest.isNotEmpty()) {
                            result += SearchArg.Flag("-$c", rest)
                            ci = cluster.length
                        } else {
                            val next = tokens.getOrNull(i + 1)
                            if (next != null && !next.startsWith("-")) {
                                result += SearchArg.Flag("-$c", next)
                                i++
                            } else {
                                result += SearchArg.Flag("-$c", null)
                            }
                            ci++
                        }
                    } else {
                        result += SearchArg.Flag("-$c", null)
                        ci++
                    }
                }
            }
            else -> result += SearchArg.Positional(t)
        }
        i++
    }
    return result
}

/** 单引号包裹，shell 命令安全。 */
fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}

/**
 * rg/搜索参数中带值的旗标（值不是路径，翻译与位置参数识别时都要跳过）。
 * 覆盖 rg 常用带值旗标；未知带值旗标按保守策略处理（不当路径，见调用方注释）。
 */
internal val SEARCH_VALUE_FLAGS: Set<String> = setOf(
    "-e", "--regexp", "-g", "--glob", "-A", "-B", "-C", "-f", "--file",
    "-t", "--type", "-T", "--type-not", "-M", "--max-columns", "-m", "--max-count",
    "--context-separator", "--encoding", "--colors", "--type-add", "--iglob",
    "--max-filesize", "--threads", "--sort", "--sortr", "--pre", "--hostname"
)

/**
 * tokens 中的路径位置参数（rg 语法 `[OPTIONS] PATTERN [PATH...]`）：
 * 有 `-e` 时全部位置参数都是路径；否则第一个位置参数是模式串，路径从第二个起。
 * `--` 之后一律按位置参数处理。
 */
internal fun positionalPathArgs(tokens: List<String>): List<String> {
    val positional = mutableListOf<String>()
    var hasRegexpFlag = false
    var afterDashDash = false
    var i = 0
    while (i < tokens.size) {
        val t = tokens[i]
        if (afterDashDash) {
            positional += t
            i++
            continue
        }
        when {
            t == "--" -> afterDashDash = true
            t.startsWith("-") -> {
                if (t in SEARCH_VALUE_FLAGS) i++ // 跳过旗标值
                if (t == "-e" || t == "--regexp") hasRegexpFlag = true
            }
            else -> positional += t
        }
        i++
    }
    return if (hasRegexpFlag) positional else positional.drop(1)
}

/**
 * 搜索目标是否整体位于当前工作区内（canonical 解析，symlink 如真机 `~/workspace` 归一）。
 *
 * 无显式路径参数时搜索 cwd（= 工作区），视为工作区内；有路径参数时逐个判定，
 * 任一路径在工作区外（或无法解析）即 false。相对路径相对 cwd，视为工作区内。
 *
 * @param workspaceRoot canonical 后的工作区根路径
 * @param home 当前执行环境 home（用于展开 `~`）
 */
internal fun isSearchTargetInsideWorkspace(
    tokens: List<String>,
    workspaceRoot: String,
    home: String
): Boolean {
    val root = normalizeSeparators(workspaceRoot)
    val paths = positionalPathArgs(tokens)
    if (paths.isEmpty()) return true
    return paths.all { token ->
        val expanded = expandHomeFor(token, home)
        if (!expanded.startsWith("/")) return true
        val canonical = canonicalOrNormalized(expanded) ?: return false
        canonical == root || canonical.startsWith("$root/")
    }
}

/**
 * 把 tokens 中的路径参数从真机路径空间翻译到容器路径空间：位于工作区内的绝对路径
 * （含经 symlink 展开的 `~/workspace/...`）改写为 `<containerWorkspace>/<相对路径>`；
 * 相对路径、模式串与旗标值不动（容器内 cwd 即挂载点，相对路径同样有效）。
 *
 * 第一个裸位置参数按模式串处理不翻译（模式不会是工作区内绝对路径；真有误伤也只是
 * 该路径搜不到、rg 报错，而非静默搜错地方）。
 *
 * @param workspaceRoot canonical 后的工作区根路径
 * @param home 当前执行环境 home（用于展开 `~`）
 */
internal fun translateTokensForContainer(
    tokens: List<String>,
    workspaceRoot: String,
    home: String,
    containerWorkspace: String = "/root/workspace"
): List<String> {
    val root = normalizeSeparators(workspaceRoot)
    // 有 -e/--regexp 时模式已显式给出，全部位置参数都是路径；否则第一个位置参数是模式串。
    val hasRegexpFlag = tokens.any { it == "-e" || it == "--regexp" }
    var seenPositional = !hasRegexpFlag
    val result = mutableListOf<String>()
    var afterDashDash = false
    var i = 0
    while (i < tokens.size) {
        val t = tokens[i]
        val next = tokens.getOrNull(i + 1)
        if (afterDashDash) {
            if (!seenPositional) {
                seenPositional = true // `--` 后第一个 token 是模式串（不论是否以 - 开头）
            } else if (!t.startsWith("-")) {
                translateOne(t, root, home, containerWorkspace)?.let { result += it; i++; continue }
            }
            result += t
            i++
            continue
        }
        if (t.startsWith("-") && t != "--" && next != null && t in SEARCH_VALUE_FLAGS) {
            result += t
            result += next
            i += 2
            continue
        }
        if (t == "--") {
            result += t
            afterDashDash = true
            i++
            continue
        }
        if (!t.startsWith("-")) {
            if (seenPositional) {
                translateOne(t, root, home, containerWorkspace)?.let { result += it; i++; continue }
            }
            seenPositional = true
        }
        result += t
        i++
    }
    return result
}

/** 单个路径 token 的容器翻译；非工作区内绝对路径或无法解析时返回 null（调用方原样保留）。 */
private fun translateOne(
    token: String,
    workspaceRoot: String,
    home: String,
    containerWorkspace: String
): String? {
    val expanded = expandHomeFor(token, home)
    if (!expanded.startsWith("/")) return null
    val canonical = canonicalOrNormalized(expanded) ?: return null
    if (canonical != workspaceRoot && !canonical.startsWith("$workspaceRoot/")) return null
    val rel = canonical.removePrefix(workspaceRoot).trimStart('/')
    return if (rel.isEmpty()) containerWorkspace else "$containerWorkspace/$rel"
}

/** canonical 解析并把分隔符统一为正斜杠（Windows canonicalPath 返回 `\`，Android 为 `/`）。 */
private fun canonicalOrNormalized(path: String): String? =
    runCatching { normalizeSeparators(java.io.File(path).canonicalPath) }.getOrNull()

/** 路径分隔符归一：Windows 的 `\` 与 POSIX 的 `/` 统一为 `/`，保证跨平台前缀比较一致。 */
private fun normalizeSeparators(path: String): String = path.replace('\\', '/')

/** 展开 `~`/`~/` 前缀（search 工具内的私有展开，与 PathHomeResolver.expandHome 同规则）。 */
private fun expandHomeFor(path: String, home: String): String = when {
    path == "~" -> home
    path.startsWith("~/") -> home.trimEnd('/') + path.removePrefix("~")
    else -> path
}
