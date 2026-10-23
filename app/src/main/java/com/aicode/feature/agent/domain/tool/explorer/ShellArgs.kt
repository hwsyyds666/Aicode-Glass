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
fun buildSearchCommand(tokens: List<String>, home: String = "/root"): String? {
    val (rgTokens, pipeSegments) = splitPipes(tokens) ?: return null
    val args = mutableListOf(
        "rg",
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
 * 组装 rg 缺失时的 **grep 兜底**搜索命令：把与 grep 原生兼容的参数透传，输出格式
 * （`文件:行号:内容`）与 [buildSearchCommand] 的 rg 输出一致；遇到 rg 专有参数
 * （--json/-l/-c/-o/-N/--type/--files/-u 等）返回 null，由调用方提示用户先装 rg。
 *
 * 已知妥协（兜底路径与 rg 的差异，调用方应在结果的 backend 字段标注 grep）：
 *  - grep 不读 .gitignore（rg 默认读），以 `--exclude-dir` 排除 .git 与常见构建目录近似；
 *  - `-g` 仅映射正向 glob 到 `--include`，否定 glob（`!`）语义不同，拒绝兜底；
 *  - `--hidden` 为 no-op（grep -r 默认即含隐藏文件）；
 *  - 正则按 POSIX ERE（-E）解释，与 rg 的 Rust 正则不完全一致（如 `\d` 不可用）。
 */
fun buildFallbackSearchCommand(tokens: List<String>, home: String = "/root"): String? {
    val (searchTokens, pipeSegments) = splitPipes(tokens) ?: return null
    val grepArgs = mutableListOf(
        "grep", "-rnI", "-E",
        "--exclude-dir=.git", "--exclude-dir=node_modules", "--exclude-dir=.gradle"
    )
    val positional = mutableListOf<String>()
    var hasRegexpFlag = false
    var afterDashDash = false
    var i = 0
    while (i < searchTokens.size) {
        val t = searchTokens[i]
        if (afterDashDash) {
            positional += t
            i++
            continue
        }
        when {
            t == "--" -> afterDashDash = true
            // 与 grep 原生兼容的开关：原样透传
            t == "-i" || t == "--ignore-case" || t == "-F" || t == "--fixed-strings" ||
                t == "-w" || t == "--word-regexp" -> grepArgs += t
            // 上下文行数：rg 与 grep 语义一致，透传（值必须纯数字）
            t == "-A" || t == "-B" || t == "-C" -> {
                if (i + 1 >= searchTokens.size) return null
                val n = searchTokens[i + 1]
                if (n.isEmpty() || !n.all(Char::isDigit)) return null
                grepArgs += listOf(t, n)
                i++
            }
            // -e/--regexp 带模式值：grep 同样支持多 -e 或语义
            t == "-e" || t == "--regexp" -> {
                if (i + 1 >= searchTokens.size) return null
                hasRegexpFlag = true
                grepArgs += listOf("-e", shellQuote(searchTokens[i + 1]))
                i++
            }
            // -g/--glob → --include（仅正向 glob）
            t == "-g" || t == "--glob" -> {
                if (i + 1 >= searchTokens.size) return null
                val glob = searchTokens[i + 1]
                if (glob.startsWith("!")) return null
                grepArgs += "--include=${shellQuote(glob)}"
                i++
            }
            // rg 默认输出参数与 grep -rn 重复或无需映射：跳过（--color 连带跳过其值 never）
            t == "--hidden" || t == "-n" || t == "--line-number" || t == "--no-heading" ||
                t == "--with-filename" -> Unit
            t == "--color" -> i++
            // 其它 rg 专有参数：无法安全映射，拒绝兜底
            t.startsWith("-") -> return null
            else -> positional += t
        }
        i++
    }
    // 模式来源：有 -e 时全部位置参数都是路径；否则第一个位置参数是模式
    if (!hasRegexpFlag && positional.isEmpty()) return null
    val pattern = if (hasRegexpFlag) null else positional.first()
    val paths = (if (hasRegexpFlag) positional else positional.drop(1))
        .map { expandTilde(it, home) }
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
    return sb.toString()
}

/** 单引号包裹，shell 命令安全。 */
fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}
