package com.aicode.feature.agent.domain.permission

import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.subagent.SubAgentWriteScopeGuard
import com.aicode.feature.settings.data.repository.CommandAuthLevel
import com.aicode.feature.settings.data.repository.ToolSafetySettingsRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具授权策略引擎：在弹窗之前评估一次工具调用应「自动放行 / 自动拒绝 / 询问」，并在用户选择
 * 「始终允许」后把规则记忆下来。
 *
 * 会话模式（[com.aicode.feature.agent.domain.model.AgentMode]）与授权挡位
 * （[CommandAuthLevel]）解耦，各司其职（对齐 Claude Code 的 Build/Plan/Ask）：
 * - ASK（提问）：只读问答沙盒，一切写操作硬拒绝；AI 不得自行切换计划模式。
 * - PLAN（计划）：先通过 todo 工具建立可视计划，再按 BUILD 同一授权挡位执行；计划本身不授予额外权限。
 * - BUILD（构建）：完整工具集；授权口径**完全由设置决定**——按「命令授权挡位」分流
 *   （规则拦截 / 自主执行 / 完全权限），不再有模式级「全放行」开关（原 AUTO 模式的职责
 *   由「完全权限」挡位承担）。
 *
 * 评估顺序（Bash，BUILD 模式）：
 *   0) 删除命令专项流程：所有删除逐次授权；根目录/系统关键目录/工作区根目录本身 → 永久硬拒绝；
 *   1) Shizuku 高危工具：一律弹窗且不可记忆；
 *   2) 可静态判定且每段都命中内置只读白名单，或 Bash 下命中受限的工作区常规验证命令 → 自动放行；
 *   3) 按「命令授权挡位」（[CommandAuthLevel]）分流：
 *      - 规则拦截（默认）：命中任一启用的高危拦截规则（见 [HighRiskRuleMatcher]）→ ASK 人工确认；
 *        命令不可静态判定 → 对原文做破坏特征扫描（宽松版 [looksDestructiveLoose]），
 *        无破坏特征 ALLOW，有破坏特征 ASK；其余 → ALLOW；
 *      - 自主执行：提权、凭据、外发和工作区外删除等高风险命令直接 ASK；其余交「AI 命令审查器」，
 *        ALLOW 留审查记录并放行，DENY 返回给 agent，UNSURE/失败转人工弹窗；
 *      - 完全权限：普通命令直接 ALLOW；删除命令仍走步骤 0，受保护目标始终硬拒绝。
 *
 * 命令不再支持「始终允许」记忆：旧的项目/全局 ALLOW 规则会先于挡位生效，等于绕过高危拦截。
 * 非 shell 工具（文件读写/编辑等）按整工具（pattern=`*`）匹配，保留「始终允许此工具」记忆
 * （这类工具无高危规则可匹配，去掉记忆会导致每次写文件都弹窗）。
 *
 * [SHIZUKU_TOOL]（Shizuku）按高危处理：一律 ASK 且不可记忆；ASK 模式下直接拒绝。
 * 「禁用安全拦截」仅关闭通用命令拦截与 Shizuku 逐次确认；删除授权流程和受保护目标硬拦截始终有效。
 */
@Singleton
class ToolPermissionPolicyEngine @Inject constructor(
    private val rulesRepo: PermissionRulesRepository,
    private val toolSafetySettings: ToolSafetySettingsRepository,
    private val highRiskRulesRepo: HighRiskRulesRepository
) {
    private companion object {
        /** 以 `command` 参数承载 shell 命令、按命令前缀做指令级匹配的工具。 */
        val SHELL_TOOLS = setOf("Bash", SHIZUKU_TOOL)

        /** Shizuku 工具：以 adb shell 身份直接操作宿主 Android 系统，按高危处理（一律弹窗、不可记忆）。 */
        const val SHIZUKU_TOOL = "Shizuku"

        const val REASON_SHIZUKU =
            "Shizuku 直接以 adb shell 身份操作宿主 Android 系统，权限高危，仅支持单次放行，不可记忆"

        /**
         * 合并后的终端会话工具：其 `start` 动作承载 shell 命令，需走指令级前缀匹配；
         * `send`/`read` 动作不承载命令，按整工具匹配。故在 [evaluate] 中按 action 路由。
         */
        const val TERMINAL_TOOL = "terminal"
        const val TERMINAL_SHELL_ACTION = "start"
        val NON_REMEMBERABLE_CAPABILITIES = setOf(
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV,
            // External effects must never inherit FULL_ACCESS or a remembered whole-tool grant.
            ToolCapability.EXTERNAL_TOOL
        )

        /**
         * 合并后的子代理工具：只读与发消息操作（read/list/send）自动放行，无需弹窗；
         * 写操作（create/stop）走正常规则评估。
         */
        const val TASK_TOOL = "task"
        private val TASK_AUTO_ACTIONS = setOf("read", "list", "send")

        /**
         * 浏览器工具的 action 分类仍供 ASK 只读沙盒使用；PLAN 与 BUILD 一样按用户授权挡位执行。
         */
        const val BROWSER_TOOL = "browser"
        private val BROWSER_READ_ONLY_ACTIONS = setOf("getText", "getHtml", "getBackbone", "screenshot", "console", "wait", "listTabs")

        /** 容器内 `~` 展开目标（PRoot 以 root 运行）。 */
        const val HOME_DIR = "/root"

        const val REASON_ASK_DENY =
            "当前处于 ASK（提问）模式，系统物理沙盒已禁止修改系统状态或执行写操作。请在提问模式下仅做只读问答与探索；如需实施变更，请提示用户切换到「构建」或「计划」模式。"

        const val REASON_ASK_NO_PLAN_SWITCH =
            "当前处于 ASK（提问）模式，AI 不得自行切换计划模式。如需规划，请由用户在界面手动切换到「计划」模式。"

        /** 疑似破坏性程序（仅在命令不可静态判定时用于保守拦截）。 */
        val DESTRUCTIVE_PROGRAM = Regex(
            "(^|[\\s;&|()`<>])(rm|dd|shred|truncate|mkfs(?:\\.[a-z0-9]+)?|wipefs|fdisk|sfdisk|parted|mkswap|blkdiscard)([\\s;&|()`<>]|$)"
        )

        val ROUTINE_TOKEN = Regex("^[A-Za-z0-9_:.=+@,-]+$")
        val ROUTINE_GRADLE_TASK = Regex("^:?[-A-Za-z0-9_.]+(?::[-A-Za-z0-9_.]+)*$")
        val ROUTINE_GRADLE_TASK_SUFFIX = Regex("^(?:test[A-Za-z0-9_]*|check|build|assemble[A-Za-z0-9_]*|lint[A-Za-z0-9_]*|detekt|ktlintCheck|spotlessCheck|connectedCheck)$", RegexOption.IGNORE_CASE)
        val ROUTINE_GRADLE_FLAGS = setOf("--continue", "--offline", "--no-daemon", "--console=plain", "--quiet", "-q", "--stacktrace")
        val ROUTINE_PACKAGE_SCRIPTS = setOf("test", "lint", "build", "check", "typecheck", "type-check")
        val ROUTINE_CARGO_FLAGS = setOf("--all", "--all-targets", "--workspace", "--locked", "--offline", "--quiet", "-q")
        val ROUTINE_PACKAGE_NAME = Regex("^[-A-Za-z0-9_]+$")
        val ROUTINE_GO_FLAGS = setOf("-race", "-cover", "-short", "-v", "-run", "-count=1")
        val ROUTINE_GO_PACKAGE = Regex("^(?:\\./)?[A-Za-z0-9_./*-]+$")
        val ROUTINE_TEST_PATH = Regex("^(?:\\./)?[A-Za-z0-9_./*-]+$")
        val ROUTINE_TEST_FLAGS = setOf("-q", "-v", "--quiet", "--verbose", "--maxfail=1", "--disable-warnings")
        val ROUTINE_LINT_FLAGS = setOf("--fix-dry-run", "--no-warn-ignored", "--max-warnings=0")
        val ROUTINE_TYPECHECK_FLAGS = setOf("--pretty", "false", "--project", "tsconfig.json")
        val PRIVILEGE_COMMAND = Regex("(?:^|[\\s;&|])(?:sudo|su)(?:$|[\\s;&|])", RegexOption.IGNORE_CASE)
        val REMOTE_PUBLISH = Regex(
            "\\bgit\\s+(?:(?:-[Cc]\\s+\\S+|--(?:git-dir|work-tree)(?:=\\S+)?)\\s+)*push\\b",
            RegexOption.IGNORE_CASE
        )
        val GITHUB_CLI_MUTATION = Regex(
            "\\bgh\\s+(?:(?:--repo|-R)\\s+\\S+\\s+)*(?:pr\\s+(?:create|edit|close|merge|ready|reopen|review)|" +
                "repo\\s+(?:create|delete|edit|rename|archive|unarchive)|" +
                "release\\s+(?:create|delete|edit|upload)|" +
                "issue\\s+(?:create|edit|close|reopen|comment)|workflow\\s+run)\\b|" +
                "\\bgh\\s+api\\b[^\\n;|&]*(?:\\s(?:-X|--method)\\s*|\\s--method=)(?:POST|PUT|PATCH|DELETE)\\b",
            RegexOption.IGNORE_CASE
        )
        val LOCAL_BRANCH_DELETE = Regex(
            "\\bgit\\s+(?:(?:-[Cc]\\s+\\S+|--(?:git-dir|work-tree)(?:=\\S+)?)\\s+)*branch\\s+(?:-D|-d|--delete)(?:\\s|$)",
            RegexOption.IGNORE_CASE
        )
        val EXTERNAL_WRITE = Regex("\\b(?:curl|wget)\\b[^\\n;|&]*(?:--data(?:-[a-z]+)?|--upload-file|--post-file|--request\\s+(?:POST|PUT|PATCH|DELETE)|\\s-X\\s*(?:POST|PUT|PATCH|DELETE)\\b|\\s-[dT]\\b|\\s-[A-Za-z]*[dT][A-Za-z]*\\b)", RegexOption.IGNORE_CASE)
        val REMOTE_TRANSFER = Regex("\\b(?:scp|sftp|ftp|rsync)\\b[^\\n;|&]*", RegexOption.IGNORE_CASE)
        val DOWNLOAD_PIPE_TO_SHELL = Regex("\\b(?:curl|wget)\\b[^\\n;|&]*\\|\\s*(?:sh|bash|zsh)\\b", RegexOption.IGNORE_CASE)
        val CREDENTIAL_PATH = Regex("(?:^|[/\\s])(?:\\.env(?:\\.[^/\\s]+)?|\\.ssh(?:/|$)|id_(?:rsa|ed25519)|credentials(?:\\.[^/\\s]+)?|secrets?\\.[^/\\s]+|[^/\\s]*(?:token|password|secret)[^/\\s]*)", RegexOption.IGNORE_CASE)
    }

    enum class Verdict { ALLOW, DENY, ASK }

    /**
     * @param verdict 评估结论。
     * @param rememberablePatterns 当 [verdict] 为 ASK 时，「始终允许」会记忆的模式；为空表示不可记忆
     *   （命令不可静态判定，只能单次放行）。
     * @param aiReviewable true 表示该 ASK 来自「自主执行」挡位，工作流层应先交 AI 命令审查器评审；
     *   审查拒绝回传给 agent，不确定或失败才回退人工弹窗。
     */
    data class EvalResult(
        val verdict: Verdict,
        val rememberablePatterns: List<String>,
        val denyReason: String? = null,
        val rememberDisabledReason: String? = null,
        /** ASK 时的弹窗标题覆盖；null 表示用工具默认标题。 */
        val askTitle: String? = null,
        val aiReviewable: Boolean = false,
        /** 命令自动放行时附加到可展开工具卡片的审查依据。 */
        val reviewRecord: String? = null,
        /** Shell deletion commands always use a dedicated human authorization flow. */
        val deletionAnalysis: CommandDeletionAnalyzer.Analysis? = null
    )

    suspend fun evaluate(
        tool: AgentTool?,
        toolName: String,
        args: Map<String, JsonElement>,
        mode: com.aicode.feature.agent.domain.model.AgentMode,
        workingDirectory: String? = null,
        context: AgentContext? = null
    ): EvalResult {
        val capabilities = tool?.effectiveCapabilities(args).orEmpty()
        context?.agentDefinition?.let { definition ->
            if (!definition.allowsTool(toolName)) {
                return EvalResult(Verdict.DENY, emptyList(), denyReason = "${definition.name} 子代理类型不允许调用工具 $toolName")
            }
        }
        if (context?.allowedWritePaths != null || context?.allowedReadPaths != null) {
            val scopeCheck = SubAgentWriteScopeGuard.check(
                toolName = toolName,
                args = args,
                capabilities = capabilities,
                context = context,
                workingDirectory = workingDirectory?.takeIf(String::isNotBlank) ?: context.projectRoot.ifBlank { "/root/workspace" }
            )
            if (!scopeCheck.allowed) {
                return EvalResult(Verdict.DENY, emptyList(), denyReason = scopeCheck.reason)
            }
        }
        if (mode == com.aicode.feature.agent.domain.model.AgentMode.ASK) {
            // ASK 是纯只读问答模式：写操作硬拒绝；同时禁止 AI 自行切换计划模式，
            // 模式切换只能由用户在界面完成（planMode 的 MODIFY_SESSION_STATE 能力不在
            // isDangerousTool 的危险能力集里，需单独拦截，否则会弹窗后被执行）。
            if (toolName == "planMode") {
                return EvalResult(Verdict.DENY, emptyList(), denyReason = REASON_ASK_NO_PLAN_SWITCH)
            }
            if (isDangerousTool(toolName, args, capabilities)) {
                return EvalResult(Verdict.DENY, emptyList(), denyReason = REASON_ASK_DENY)
            }
        }

        if (capabilities == setOf(ToolCapability.READ_AGENT_CONFIG)) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // task 只读动作（read/list）：直接自动放行（不弹窗）。
        if (toolName == TASK_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
            if (action in TASK_AUTO_ACTIONS) return EvalResult(Verdict.ALLOW, emptyList())
        }

        val level = toolSafetySettings.getCommandAuthLevel()
        // 「禁用安全拦截」关闭通用命令拦截及 Shizuku 逐次确认；删除授权在 evaluateShell 中优先处理。
        val safetyDisabled = toolSafetySettings.isSafetyInterceptionDisabled()
        return if (isShellTool(toolName, args)) {
            // 规则拦截用用户配置决定拦截项；自主执行复用预设/自定义高危项作为 L3 人工确认信号。
            val highRiskRules = if (level == CommandAuthLevel.RULE_BASED || level == CommandAuthLevel.AUTONOMOUS) {
                highRiskRulesRepo.getRulesOnce()
            } else emptyList()
            evaluateShell(
                args,
                toolName = toolName,
                workingDirectory = workingDirectory,
                forceAsk = toolName == SHIZUKU_TOOL,
                level = level,
                highRiskRules = highRiskRules,
                safetyDisabled = safetyDisabled,
                workspaceRoot = workingDirectory
            )
        } else {
            evaluateGeneric(toolName, capabilities, level)
        }
    }

    /** 当前审查缓存的授权挡位，供工作流以同一快照生成缓存键和审查上下文。 */
    suspend fun currentCommandAuthLevel(): CommandAuthLevel = toolSafetySettings.getCommandAuthLevel()

    /** 当前审查调用超时；设置范围由 ToolSafetySettingsRepository 限定。 */
    suspend fun commandReviewTimeoutMillis(): Long =
        toolSafetySettings.getCommandReviewTimeoutSeconds() * 1_000L

    private fun isDangerousTool(toolName: String, args: Map<String, JsonElement>, capabilities: Set<ToolCapability>): Boolean {
        val dangerousCapabilities = setOf(
            ToolCapability.WRITE_WORKSPACE,
            ToolCapability.EXECUTE_COMMANDS,
            ToolCapability.NETWORK_WRITE,
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV,
            ToolCapability.EXTERNAL_TOOL
        )
        if (capabilities.any { it in dangerousCapabilities }) return true

        val dangerousTools = setOf(
            "writeFile",
            "editFile",
            "Bash"
        )
        if (toolName in dangerousTools) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action != "read"
        }
        if (toolName == BROWSER_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()
            return action !in BROWSER_READ_ONLY_ACTIONS
        }
        return false
    }

    /** 是否按 shell 命令前缀匹配：[SHELL_TOOLS] 中的工具，或终端工具的 start/send 动作。 */
    private fun isShellTool(toolName: String, args: Map<String, JsonElement>): Boolean {
        if (toolName in SHELL_TOOLS) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action == TERMINAL_SHELL_ACTION || action == "send"
        }
        return false
    }

    /**
     * 破坏性判定（用于「不可静态分析」命令的兜底分流）。宁可多问，不误放——但也不能
     * 把只读排查命令全部判成破坏。这里只认：
     * 1) [DESTRUCTIVE_PROGRAM] 命中（rm/dd/mkfs…，正则边界含 `()` 与反引号，
     *    覆盖 `$(rm -rf x)` 这类藏在命令替换内部的破坏）；
     * 2) `find … -delete`；
     * 3) 覆盖写重定向 `> 目标`——目标不是 /dev/null、不是 fd 复制（2>&1）、
     *    且不是 `>>` 追加。
     *
     * 历史：曾用「含 `>` 即破坏」的严格版，AI 排查类命令几乎条条带 `2>/dev/null`，
     * 大量与删除无关的命令被误判为破坏（RULE_BASED 与 AUTO 两个挡位均已修为宽松版），
     * 严格版实现已删除，只保留本函数。
     */
    private fun looksDestructiveLoose(command: String): Boolean =
        command.contains("-delete") ||
            DESTRUCTIVE_PROGRAM.containsMatchIn(command) ||
            hasOverwriteRedirect(command)

    /** 是否存在覆盖写重定向（`> file`）。`>>` 追加、`2>/dev/null`、`2>&1`、`<<<` 不算。 */
    private fun hasOverwriteRedirect(command: String): Boolean {
        var i = 0
        val n = command.length
        while (i < n) {
            if (command[i] == '>') {
                val double = i + 1 < n && command[i + 1] == '>'
                // herestring（<<<）里的 > 不算重定向
                if (i > 0 && command[i - 1] == '<') { i++; continue }
                if (!double) {
                    val rest = command.substring(i + 1).trimStart()
                    // 空 target / fd 复制（&1）/ 丢弃输出（/dev/null）都无害
                    if (rest.isNotEmpty() && !rest.startsWith("&") && !rest.startsWith("/dev/null")) {
                        return true
                    }
                }
                i += if (double) 2 else 1
            } else {
                i++
            }
        }
        return false
    }

    /** 把「始终允许」的选择落库为 ALLOW 规则（去重交给仓库）。 */
    suspend fun remember(toolName: String, patterns: List<String>, scope: PermissionScope) {
        patterns.distinct().forEach { pattern ->
            rulesRepo.add(scope, PermissionRule(toolName, pattern, PermissionDecision.ALLOW))
        }
    }

    /**
     * 非 shell 工具（文件读写/编辑等）的裁决。
     *
     * 命令类工具的授权口径已统一收敛到三挡位 + 高危规则，旧的「项目/全局」记忆规则不再参与；
     * 但对非命令类工具仍然保留「始终允许」记忆——这类工具没有高危规则可匹配，
     * 若去掉记忆，每次写文件都要弹窗，无法正常使用。
     */
    private suspend fun evaluateGeneric(
        toolName: String,
        capabilities: Set<ToolCapability>,
        level: CommandAuthLevel
    ): EvalResult {
        if (capabilities.any { it in NON_REMEMBERABLE_CAPABILITIES }) {
            return EvalResult(
                Verdict.ASK,
                rememberablePatterns = emptyList(),
                rememberDisabledReason = "该工具会修改 Agent 配置、容器环境或调用外部动态工具，为降低误授权风险，仅支持单次放行"
            )
        }
        // 完全权限：命令与非命令工具一律放行。
        if (level == CommandAuthLevel.FULL_ACCESS) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }
        // 已记忆的整工具 ALLOW（「始终允许此工具」）→ 直接放行。
        val rules = rulesRepo.loadEffectiveForCurrentProject()
        if (rules.any { it.toolName == toolName && it.decision == PermissionDecision.ALLOW && it.pattern == PermissionRule.WHOLE_TOOL }) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }
        return EvalResult(Verdict.ASK, listOf(PermissionRule.WHOLE_TOOL))
    }

    private fun externalOperationReason(command: String): String? = when {
        REMOTE_PUBLISH.containsMatchIn(command) -> "命令会把提交推送到远端仓库；请核对目标仓库与分支。"
        GITHUB_CLI_MUTATION.containsMatchIn(command) -> "命令会创建或修改 GitHub 资源；请核对仓库和操作内容。"
        LOCAL_BRANCH_DELETE.containsMatchIn(command) -> "命令会删除本地 Git 分支；请核对分支名及未合并改动。"
        else -> null
    }

    private fun evaluateShell(
        args: Map<String, JsonElement>,
        toolName: String,
        workingDirectory: String?,
        forceAsk: Boolean = false,
        level: CommandAuthLevel,
        highRiskRules: List<HighRiskRule>,
        safetyDisabled: Boolean = false,
        workspaceRoot: String? = workingDirectory
    ): EvalResult {
        val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
            ?: return EvalResult(Verdict.ASK, emptyList())

        val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
        val hasCommandPayload = when (toolName) {
            "Bash", SHIZUKU_TOOL -> true
            TERMINAL_TOOL -> action == TERMINAL_SHELL_ACTION || action == "send"
            else -> false
        }
        val intent = (args["intent"] as? JsonPrimitive)?.content?.trim()
        if (hasCommandPayload && intent.isNullOrBlank()) {
            return EvalResult(
                verdict = Verdict.DENY,
                rememberablePatterns = emptyList(),
                denyReason = "命令缺少执行意图说明。请先说明它如何服务于用户当前任务，再重新请求；不能因此绕过后续安全审查。"
            )
        }

        val analysis = ShellCommandParser.analyze(command)

        // Deletion authorization stays active when generic interception is disabled. A hard-blocked
        // target can never be approved; all other delete commands require an explicit human choice.
        val deletion = CommandDeletionAnalyzer.analyze(
            command = command,
            workingDirectory = workingDirectory?.takeIf { it.isNotBlank() }
                ?: workspaceRoot?.takeIf { it.isNotBlank() }
                ?: "/root/workspace",
            workspaceRoot = workspaceRoot?.takeIf { it.isNotBlank() }
                ?: workingDirectory?.takeIf { it.isNotBlank() }
                ?: "/root/workspace"
        )
        if (deletion.isDeletion) {
            deletion.hardBlockReason?.let { reason ->
                return EvalResult(Verdict.DENY, emptyList(), denyReason = reason, deletionAnalysis = deletion)
            }
            return EvalResult(
                verdict = Verdict.ASK,
                rememberablePatterns = emptyList(),
                askTitle = "删除操作授权",
                rememberDisabledReason = "删除命令不可记忆；请检查完整命令、目标路径及风险标签。",
                aiReviewable = true,
                deletionAnalysis = deletion
            )
        }

        // Publishing or mutating GitHub state is always a one-shot human decision, even when
        // generic safety interception is disabled or the auth level is FULL_ACCESS.
        externalOperationReason(command)?.let { reason ->
            return EvalResult(
                verdict = Verdict.ASK,
                rememberablePatterns = emptyList(),
                askTitle = "外部 GitHub 操作需要确认",
                rememberDisabledReason = reason
            )
        }

        // 1) Shizuku 高危工具：无论内置白名单，一律弹窗且不可记忆（关闭安全拦截时除外）。
        if (forceAsk && !safetyDisabled) {
            return EvalResult(Verdict.ASK, emptyList(), rememberDisabledReason = REASON_SHIZUKU)
        }

        // 2) 可静态判定且每段都命中内置只读白名单（ls/git status 等）→ 自动放行。
        //    必须要求可判定：`cat $(rm -rf /)` 之类命令替换攻击的段首仍是 cat，不可凭白名单放行。
        if (!(level == CommandAuthLevel.AUTONOMOUS && CREDENTIAL_PATH.containsMatchIn(command)) &&
            analysis.analyzable && analysis.segments.isNotEmpty() &&
            analysis.segments.all { BuiltInSafeCommands.isSafe(it) }
        ) {
            return EvalResult(Verdict.ALLOW, emptyList(), reviewRecord = "规则快速放行：可静态确认的只读命令。")
        }

        // L1：Bash 固定在当前工作区执行；只开放单条、可静态解析的常规构建/测试/lint 命令。
        // terminal 会话可能已切换目录，故不对 terminal start/send 套用此白名单。
        if (toolName == "Bash" && analysis.analyzable && isRoutineWorkspaceCommand(command, analysis.segments)) {
            return EvalResult(Verdict.ALLOW, emptyList(), reviewRecord = "规则快速放行：工作区内受限的构建、测试或 lint 命令。")
        }

        // 3) 挡位分流。
        //    注：旧的「项目/全局」记忆规则（ALLOW/DENY）已整体下线——它会先于挡位生效，
        //    等于绕过高危拦截规则；现在授权口径统一由三挡位 + 高危规则决定。
        return when (level) {
            // 完全权限：删除命令已由上方专项流程处理；其余 shell 命令按挡位定义直接放行。
            // 此前这里还有一道「不可静态判定且疑似破坏性 → 拦一次」的兜底，但它依据
            // 「含 `>` 即破坏」的严格判定：只要命令里出现任何一个 `>` 就命中，连
            // `2>/dev/null` 这种丢弃输出的写法也算。AI 排查类命令几乎条条带
            // `$()`/`2>/dev/null`，结果大量与删除无关的命令被硬拒绝，与「完全权限」的语义矛盾。
            // 用户既然选了这一挡，就按其字面执行。
            CommandAuthLevel.FULL_ACCESS -> EvalResult(
                Verdict.ALLOW,
                emptyList(),
                reviewRecord = "完全权限挡位放行；删除命令由专项授权流程单独处理。"
            )

            // 规则拦截：命中高危规则 → 人工确认；不可静态判定 → 破坏特征扫描；其余自动放行。
            CommandAuthLevel.RULE_BASED -> {
                HighRiskRuleMatcher.matchAny(highRiskRules, command)?.let { hit ->
                    return askWithRememberable(
                        analysis,
                        askTitle = "命中高危规则：${hit.description.ifBlank { hit.pattern }}"
                    )
                }
                if (!analysis.analyzable) {
                    // 不可静态判定（命令替换/子shell/循环等）。此前一律弹窗——AI 排查类
                    // 命令充满 $() 与反引号，实测条条命中兜底分支，高危规则反而一次没
                    // 触发过，授权弹窗形同骚扰。改为对**原文**做破坏特征扫描：
                    // DESTRUCTIVE_PROGRAM 正则的边界符含 `()` 与反引号，能覆盖 $()
                    // 内部；覆盖写重定向（> file）单独判定（2>/dev/null、>>、2>&1 无害）。
                    // 无破坏特征 → 放行；有 → 人工确认。
                    if (looksDestructiveLoose(command)) {
                        return askWithRememberable(
                            analysis,
                            askTitle = "命令含复杂构造（命令替换/子shell）且检测到破坏性特征，需人工确认"
                        )
                    }
                    return EvalResult(Verdict.ALLOW, emptyList(), reviewRecord = "规则挡位放行：未检测到已知破坏性特征。")
                }
                EvalResult(Verdict.ALLOW, emptyList(), reviewRecord = "规则挡位放行：未命中启用的高危拦截规则。")
            }

            // 自主执行：高风险命令直接交人工；中间风险交独立审查器。
            CommandAuthLevel.AUTONOMOUS -> {
                val configuredHighRisk = HighRiskRuleMatcher.matchAny(highRiskRules, command)?.description
                val highRiskReason = autonomousHighRiskReason(command, analysis, workingDirectory)
                    ?: configuredHighRisk?.takeIf { it.isNotBlank() }?.let { "命令命中高危规则：$it。" }
                if (highRiskReason != null) {
                    askWithRememberable(
                        analysis,
                        askTitle = "高风险命令需要人工确认",
                        rememberable = false,
                        rememberDisabledReason = highRiskReason
                    )
                } else {
                    askWithRememberable(analysis, aiReviewable = true)
                }
            }
        }
    }

    /** L1 的受限开发命令表：命令名和任务名固定，拒绝 shell 组合、路径跳转及脚本注入参数。 */
    private fun isRoutineWorkspaceCommand(command: String, segments: List<List<String>>): Boolean {
        if (command.any { it in ";|&><`\n\r" }) return false
        if (segments.size != 1) return false
        val tokens = segments.single()
        val program = ShellCommandParser.programPrefix(tokens) ?: return false
        val args = tokens.drop(1)
        if (args.isEmpty() || args.any { token ->
                hasPathTraversalSegment(token) || token.startsWith('/') || token.startsWith('~') ||
                    (!ROUTINE_TOKEN.matches(token) && !ROUTINE_TEST_PATH.matches(token))
            }
        ) return false

        return when (program) {
            "./gradlew", "gradlew", "gradle" -> {
                val tasks = args.filterNot { it in ROUTINE_GRADLE_FLAGS }
                args.all { token -> token in ROUTINE_GRADLE_FLAGS ||
                    (ROUTINE_GRADLE_TASK.matches(token) && !token.startsWith('-') &&
                        ROUTINE_GRADLE_TASK_SUFFIX.matches(token.substringAfterLast(':')))
                } && tasks.isNotEmpty()
            }
            "npm" -> when (args.firstOrNull()) {
                "test" -> args.size == 1
                "run" -> args.size == 2 && args[1] in ROUTINE_PACKAGE_SCRIPTS
                else -> false
            }
            "yarn", "pnpm" -> args.firstOrNull() in ROUTINE_PACKAGE_SCRIPTS && args.size == 1
            "cargo" -> args.firstOrNull() in setOf("check", "test", "build", "clippy") &&
                args.drop(1).all { it in ROUTINE_CARGO_FLAGS || it.matches(ROUTINE_PACKAGE_NAME) }
            "go" -> args.firstOrNull() in setOf("test", "build", "vet") &&
                args.drop(1).all { it in ROUTINE_GO_FLAGS || ROUTINE_GO_PACKAGE.matches(it) }
            "pytest" -> args.all { ROUTINE_TEST_PATH.matches(it) || it in ROUTINE_TEST_FLAGS }
            "ruff" -> args.firstOrNull() == "check" && args.drop(1).all { ROUTINE_TEST_PATH.matches(it) }
            "eslint" -> args.isNotEmpty() && args.all { ROUTINE_TEST_PATH.matches(it) || it in ROUTINE_LINT_FLAGS }
            "tsc" -> "--noEmit" in args && args.all { it == "--noEmit" || it in ROUTINE_TYPECHECK_FLAGS }
            else -> false
        }
    }

    private fun hasPathTraversalSegment(token: String): Boolean =
        token.replace('\\', '/').split('/').any { it == ".." } ||
            token.substringAfterLast('=').let { it == ".." || it.startsWith("../") }

    /** AUTONOMOUS 的 L3：已知高风险外联、提权、凭据读取或工作区外删除不交给模型自动放行。 */
    private fun autonomousHighRiskReason(
        command: String,
        analysis: ShellCommandParser.Analysis,
        workingDirectory: String?
    ): String? {
        if (PRIVILEGE_COMMAND.containsMatchIn(command)) return "命令请求 sudo/su 等提权，需你确认执行主体与影响范围。"
        if (REMOTE_PUBLISH.containsMatchIn(command)) return "命令会向远端仓库发布提交；请确认目标仓库和发布范围。"
        if (EXTERNAL_WRITE.containsMatchIn(command) || REMOTE_TRANSFER.containsMatchIn(command) || DOWNLOAD_PIPE_TO_SHELL.containsMatchIn(command)) {
            return "命令可能向外部主机发送数据或执行未经审核的下载内容。"
        }
        if (CREDENTIAL_PATH.containsMatchIn(command)) return "命令涉及凭据或密钥路径，可能读取敏感信息。"

        val rmSegments = analysis.segments.map(ShellCommandParser::parseRmInfo).filter { it.isRm }
        if (rmSegments.isNotEmpty()) {
            val workspace = workingDirectory?.let(::normalizeAbsolutePath)
            for (rm in rmSegments) {
                for (target in rm.targetPaths) {
                    if (target.contains('*') || target.contains('?')) {
                        return "删除命令含通配符，实际影响范围无法静态确认。"
                    }
                    if (target.startsWith("/") || target.startsWith("~") || target.contains("..")) {
                        val resolved = resolveTargetPath(target, workingDirectory)
                        if (workspace == null || resolved == null || !isWithinWorkspace(resolved, workspace)) {
                            return "删除目标位于工作区外或无法确认是否位于工作区内。"
                        }
                    }
                }
            }
        }
        return null
    }

    private fun resolveTargetPath(target: String, workingDirectory: String?): String? {
        val expanded = when {
            target == "~" -> "/root"
            target.startsWith("~/") -> "/root/${target.drop(2)}"
            target.startsWith('/') -> target
            workingDirectory != null -> "$workingDirectory/$target"
            else -> return null
        }
        return normalizeAbsolutePath(expanded)
    }

    /** 纯字符串路径归一化，不访问文件系统。 */
    private fun normalizeAbsolutePath(path: String): String {
        val parts = ArrayDeque<String>()
        path.split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return "/" + parts.joinToString("/")
    }

    private fun isWithinWorkspace(target: String, workspace: String): Boolean =
        target == workspace || target.startsWith("$workspace/")

    /**
     * 构造命令类 ASK 裁决。命令的「始终允许」记忆已下线——它会落到项目/全局规则里，
     * 先于挡位与高危拦截规则生效，等于绕过授权挡位；命令一律只支持单次放行。
     */
    private fun askWithRememberable(
        analysis: ShellCommandParser.Analysis,
        askTitle: String? = null,
        aiReviewable: Boolean = false,
        rememberable: Boolean = true,
        rememberDisabledReason: String? = null
    ): EvalResult {
        val hasRmUnrememberable = analysis.segments.any { seg ->
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            rmInfo.isRm && (rmInfo.targetPaths.isEmpty() || rmInfo.isRecursive || rmInfo.isWildcard)
        }
        val reason = rememberDisabledReason ?: if (!rememberable) {
            "高风险命令仅支持单次人工确认，不可记忆"
        } else if (hasRmUnrememberable) {
            "高风险删除操作（递归/通配/强制批量删除），为确保数据安全不可记忆，仅可单次放行"
        } else {
            "命令授权由「授权挡位 + 高危拦截规则」统一管理，仅支持单次放行"
        }
        return EvalResult(
            verdict = Verdict.ASK,
            rememberablePatterns = emptyList(),
            rememberDisabledReason = reason,
            askTitle = askTitle,
            aiReviewable = aiReviewable
        )
    }

}
