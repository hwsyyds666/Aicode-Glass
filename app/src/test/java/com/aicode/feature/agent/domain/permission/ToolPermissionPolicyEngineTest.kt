package com.aicode.feature.agent.domain.permission

import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.settings.data.repository.CommandAuthLevel
import com.aicode.feature.settings.data.repository.ToolSafetySettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具授权策略引擎的分支覆盖。规则加载经 mock 注入，聚焦 [ToolPermissionPolicyEngine.evaluate]
 * 的裁决逻辑：PLAN/ASK 模式边界、完全权限挡位、DENY 优先、白名单/记忆规则、rm 高危删除防护与不可记忆降级。
 * 底层 [ShellCommandParser] / [BuiltInSafeCommands] 的解析细节由各自独立测试覆盖，不在此重复。
 */
class ToolPermissionPolicyEngineTest {

    /**
     * 构造策略引擎：授权规则与挡位经 mock 注入；高危规则默认使用真实预设集（贴近生产行为），
     * 挡位默认 [CommandAuthLevel.RULE_BASED]（与仓库默认值一致），可按用例覆盖。
     */
    private fun engine(
        vararg rules: PermissionRule,
        safetyDisabled: Boolean = false,
        level: CommandAuthLevel = CommandAuthLevel.RULE_BASED,
        highRiskRules: List<HighRiskRule> = HighRiskRulePresets.RULES
    ): ToolPermissionPolicyEngine {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        coEvery { repo.loadEffectiveForCurrentProject() } returns rules.toList()
        val safety = mockk<ToolSafetySettingsRepository>(relaxed = true)
        coEvery { safety.isSafetyInterceptionDisabled() } returns safetyDisabled
        coEvery { safety.getCommandAuthLevel() } returns level
        val highRiskRepo = mockk<HighRiskRulesRepository>(relaxed = true)
        coEvery { highRiskRepo.getRulesOnce() } returns highRiskRules
        return ToolPermissionPolicyEngine(repo, safety, highRiskRepo)
    }

    private fun tool(vararg caps: ToolCapability): AgentTool {
        val t = mockk<AgentTool>(relaxed = true)
        every { t.capabilities } returns caps.toSet()
        every { t.effectiveCapabilities(any()) } returns caps.toSet()
        return t
    }

    private fun bash(command: String) = mapOf(
        "command" to JsonPrimitive(command),
        "intent" to JsonPrimitive("test command for current user task")
    )

    private fun terminal(action: String) = mapOf(
        "action" to JsonPrimitive(action),
        "command" to JsonPrimitive("echo terminal-policy-test"),
        "input" to JsonPrimitive("echo terminal-policy-test"),
        "intent" to JsonPrimitive("Verify terminal authorization behavior in the current test.")
    )

    // ── PLAN 与 BUILD 使用相同的工具授权挡位，ASK 仍独立只读 ──────────

    @Test
    fun planMode_usesCurrentAuthLevelForWorkspaceWrites() = runTest {
        val ruleBased = engine(level = CommandAuthLevel.RULE_BASED)
            .evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.PLAN)
        val buildRuleBased = engine(level = CommandAuthLevel.RULE_BASED)
            .evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        val fullAccess = engine(level = CommandAuthLevel.FULL_ACCESS)
            .evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.PLAN)
        assertEquals(buildRuleBased.verdict, ruleBased.verdict)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, ruleBased.verdict)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, fullAccess.verdict)
    }

    @Test
    fun planMode_shellUsesSameAuthAndDeletionRulesAsBuild() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val ordinary = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("touch output.txt"), AgentMode.PLAN)
        val deletion = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -f output.txt"), AgentMode.PLAN, "/root/workspace")
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, ordinary.verdict)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, deletion.verdict)
        assertNotNull(deletion.deletionAnalysis)
    }

    @Test
    fun planMode_terminalStartUsesCurrentAuthLevel() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "terminal", terminal("start"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun terminalCommandWithoutIntentIsDeniedEvenAtFullAccess() = runTest {
        val result = engine(level = CommandAuthLevel.FULL_ACCESS).evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "terminal",
            mapOf("action" to JsonPrimitive("start"), "command" to JsonPrimitive("echo hi")),
            AgentMode.BUILD,
            "/root/workspace"
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, result.verdict)
    }

    // ── 完全权限挡位：直接放行但保留灾难性 rm 防护 ────────────────────
    // （原 AUTO 会话模式的「全放行」语义由「完全权限」挡位承担，AUTO 已退场）

    @Test
    fun fullAccessLevel_allowsAnyTool() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun fullAccessLevel_allowsBash() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun fullAccessLevel_stillBlocksCatastrophicRm() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason?.startsWith("安全防护：禁止删除根目录") == true)
    }

    @Test
    fun fullAccessLevel_workspaceWildcardDeleteStillNeedsExplicitApproval() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf ~/workspace/*"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertNotNull(r.deletionAnalysis)
    }

    @Test
    fun workspaceFileDeleteUsesDedicatedOneShotReviewFlow() = runTest {
        val r = engine().evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            bash("rm -f build/cache.bin"),
            AgentMode.BUILD,
            workingDirectory = "/root/workspace"
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.aiReviewable)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.deletionAnalysis)
        assertTrue(r.deletionAnalysis?.targets?.single()?.outsideWorkspace == false)
    }

    @Test
    fun disabledSafety_stillHardBlocksRootDelete() = runTest {
        // 删除硬拦截始终生效，不随通用安全拦截开关或完全权限挡位关闭。
        val e = engine(safetyDisabled = true, level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun shellCommandWithoutDeclaredIntent_isDeniedBeforeEveryAuthLevel() = runTest {
        for (level in CommandAuthLevel.entries) {
            val e = engine(level = level, safetyDisabled = true)
            val result = e.evaluate(
                tool(ToolCapability.EXECUTE_COMMANDS),
                "Bash",
                mapOf("command" to JsonPrimitive("pwd")),
                AgentMode.BUILD,
                workingDirectory = "/root/workspace"
            )
            assertEquals("$level", ToolPermissionPolicyEngine.Verdict.DENY, result.verdict)
            assertTrue(result.denyReason?.contains("意图") == true)
        }
    }

    // ── ASK 模式：写操作硬拒绝，且禁止 AI 自行切入 PLAN ─────────────

    @Test
    fun askMode_deniesWriteTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.ASK)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun askMode_deniesBash() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.ASK)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun askMode_deniesPlanModeSwitch() = runTest {
        // planMode 的能力不在危险能力集里，须单独拦截，否则会弹窗后被执行
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.MODIFY_SESSION_STATE),
            "planMode",
            mapOf("action" to JsonPrimitive("enter")),
            AgentMode.ASK
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun askMode_allowsReadOnlyTool() = runTest {
        val e = engine(
            PermissionRule("list", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "list", emptyMap(), AgentMode.ASK)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── 只读 Agent 配置自动放行 ─────────────────────────────────────

    @Test
    fun readAgentConfig_capabilityAutoAllow() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_AGENT_CONFIG), "customTool", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── task 只读动作放行，whole DENY 仍生效 ─────────────────────────

    @Test
    fun taskReadAction_allowsWithoutRules() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXTERNAL_TOOL), "task", terminal("read"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun taskReadAction_alwaysAllowed() = runTest {
        // task 的只读动作走 evaluate 的快捷放行分支，早于挡位与规则匹配，规则不影响结果
        val e = engine(
            PermissionRule("task", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.EXTERNAL_TOOL), "task", terminal("read"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── 记忆规则已下线：shell 授权只由「挡位 + 高危规则」决定 ──────────

    @Test
    fun shellDenyRuleIgnored_authLevelDecides() = runTest {
        // 命令类工具的 ALLOW/DENY 记忆规则已整体下线（会先于挡位生效、绕过高危拦截），
        // ls 命中内置只读白名单 → 放行，DENY 规则不参与。
        val e = engine(
            PermissionRule("Bash", "ls", PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── 不可静态判定：无破坏特征则放行 ───────────────────────────────

    @Test
    fun unanalyzableBenignCommand_allowed() = runTest {
        // 命令替换使静态判定失效，但对原文做破坏特征扫描无命中 → 放行（AI 排查命令常含 $()）
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo $(whoami)"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── 内置安全白名单自动放行 ───────────────────────────────────────

    @Test
    fun safeCommand_autoAllowed() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun mixOfSafeCommands_allowed() = runTest {
        // 高危规则只覆盖递归/通配/批量删除，裸 `rm x` 未命中；整条命令可静态判定 → 放行。
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la && git status --short"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── 已记忆 ALLOW 规则 ───────────────────────────────────────────

    @Test
    fun rememberedPrefix_allows() = runTest {
        val e = engine(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git pull origin main"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun rememberedPrefix_doesNotMatchOtherSubcommand() = runTest {
        // 「规则拦截」挡位语义：git clone 未命中高危规则且可静态判定 → 直接放行（不再逐次询问）；
        // 同时也验证了 "git pull" 记忆规则不会误放行其它 git 子命令后再被高危规则拦下。
        val e = engine(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git clone https://x"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── rm 精细校验：无目标 / 递归 / 通配的规则不得放行 ───────────────

    @Test
    fun bareRmRuleIgnored_nonRecursiveRmStillRequiresOneShotApproval() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm file.txt"), AgentMode.BUILD)
        // 规则不参与；`rm file.txt` 非递归/无通配，未命中高危规则 → 放行
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    @Test
    fun nonRecursiveRmRule_doesNotAllowRecursiveRm() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm file.txt", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /some/dir"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty()) // 递归删除不可记忆
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun recursiveRmRuleIgnored_stillAsks() = runTest {
        // ALLOW 记忆规则已下线，递归删除命中高危规则 → 人工确认且不可记忆
        val e = engine(
            PermissionRule("Bash", "rm -rf /tmp/build", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /tmp/build"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    // ── 灾难性 rm 防护在 BUILD 模式同样生效 ─────────────────────────

    @Test
    fun catastrophicRm_deniedEvenWithAllowRule() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm -rf", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun systemDirRm_denied() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /etc"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 删除硬拦截不可由 elevate 绕过 ────────────────────────────────

    @Test
    fun catastrophicRm_elevateStillHardDenies() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            mapOf(
                "command" to JsonPrimitive("rm -rf /"),
                "intent" to JsonPrimitive("Delete a path requested by the user."),
                "elevate" to JsonPrimitive(true)
            ),
            AgentMode.BUILD
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertTrue(r.denyReason?.contains("安全防护") == true)
    }

    @Test
    fun catastrophicRm_denyReasonDoesNotSuggestElevation() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason?.contains("elevate") == false)
    }

    @Test
    fun catastrophicRm_fullAccessLevel_elevateStillHardDenies() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            mapOf(
                "command" to JsonPrimitive("rm -rf /"),
                "intent" to JsonPrimitive("Delete a path requested by the user."),
                "elevate" to JsonPrimitive(true)
            ),
            AgentMode.BUILD
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 路径归一化：多种等价写法均需拦截，工作区子目录放行 ─────────────

    private suspend fun denied(command: String): Boolean =
        engine().evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash(command), AgentMode.BUILD).verdict ==
            ToolPermissionPolicyEngine.Verdict.DENY

    @Test
    fun abnormalPathSpellings_denied() = runTest {
        assertTrue(denied("rm -rf //etc"))
        assertTrue(denied("rm -rf /./etc"))
        assertTrue(denied("rm -rf /etc/../etc"))
        assertTrue(denied("rm -rf /tmp/../etc"))
        assertTrue(denied("rm -rf /foo/../"))
        assertTrue(denied("rm -rf \$HOME"))
        assertTrue(!denied("rm -rf \${HOME}/foo")) // 主目录内的普通目标需人工确认，不作为根目录硬拦截
        assertTrue(denied("rm -rf /et*"))
        assertTrue(denied("rm -rf /usr*"))
        assertTrue(denied("rm -rf /srv"))
        assertTrue(denied("rm -rf /mnt"))
    }

    @Test
    fun workspaceSubdir_allowed() = runTest {
        assertTrue(!denied("rm -rf ~/workspace/build"))
        assertTrue(!denied("rm -rf ~/workspace/app/src"))
        assertTrue(!denied("rm -rf /tmp/build"))
    }

    @Test
    fun workspaceRootItself_isHardBlockedButItsContentsRequireApproval() = runTest {
        assertTrue(denied("rm -rf ~/workspace"))
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ASK,
            engine().evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf ~/workspace/*"), AgentMode.BUILD).verdict
        )
        assertTrue(denied("rm -rf ~/workspace/.."))
    }

    // ── 完全权限挡位：不可静态判定的命令按字面放行 ─────────────────────

    @Test
    fun fullAccess_unanalyzableBenign_allowed() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo \$(date)"), AgentMode.BUILD).verdict
        )
    }

    @Test
    fun fullAccess_unanalyzableBenignWithStderrRedirect_allowed() = runTest {
        // 回归（10-18 真机复现）：原 AUTO 分支曾用「含 `>` 即破坏」的严格判定，而
        // ShellCommandParser 把 `> /dev/null` 判为不可静态判定——两个条件叠加，
        // 条条带 `2>/dev/null` 的只读排查命令（getprop/pidof/cat /proc/...）被
        // 硬拒绝。现已统一为宽松判定（只认 find -delete / 破坏性程序 / 覆盖写重定向），
        // 且「完全权限」按字面放行，不再有第二道兜底拦截。
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            bash("id; getprop ro.product.cpu.abi; getenforce 2>/dev/null; cat /proc/sys/kernel/perf_event_paranoid 2>/dev/null; echo \"--- dfm proc ---\"; pidof com.tencent.tmgp.dfm 2>/dev/null || echo \"not running\"; echo \"--- dfm installed ---\"; ls /data/app/ | grep -i tmgp.dfm"),
            AgentMode.BUILD
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun ruleBased_unanalyzableDestructiveInSubstitution_stillAsks() = runTest {
        // 安全边界不因误报修复而放松：藏在命令替换里的 find -delete 在「规则拦截」挡位仍须人工确认
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            bash("echo \$(find /data -name '*.tmp' -delete)"),
            AgentMode.BUILD
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun safetyDisabled_unanalyzable_allowed() = runTest {
        val e = engine(safetyDisabled = true, level = CommandAuthLevel.FULL_ACCESS)
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.BUILD).verdict
        )
    }

    // ── Shizuku 高危工具：一律弹窗、不可记忆，完全权限也不豁免 ─────────

    private fun shizuku(command: String) = mapOf(
        "command" to JsonPrimitive(command),
        "intent" to JsonPrimitive("test command for current user task")
    )

    @Test
    fun shizuku_buildMode_asksWithoutRememberable() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun shizuku_safeCommandStillAsks() = runTest {
        val e = engine()
        // 内置安全白名单（ls）对 Shizuku 不适用，仍需弹窗
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("ls -la /sdcard"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_rememberedAllowRuleIgnored() = runTest {
        val e = engine(PermissionRule("Shizuku", "pm", PermissionDecision.ALLOW))
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_denyRuleStillAsks() = runTest {
        // Shizuku 一律走 forceAsk 分支（弹窗且不可记忆），DENY 规则不参与 → 结果为 ASK
        val e = engine(PermissionRule("Shizuku", "pm", PermissionDecision.DENY))
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_fullAccess_asks() = runTest {
        // 完全权限不豁免 Shizuku 逐次确认
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun shizuku_safetyDisabled_allows() = runTest {
        // 「禁用安全拦截」对全部模式与挡位生效：Shizuku 也不再逐次确认
        val e = engine(safetyDisabled = true, level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun shizuku_planMode_usesHighRiskApproval() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    // ── 非 shell 工具 ───────────────────────────────────────────────

    @Test
    fun genericTool_wholeRuleAllows() = runTest {
        val e = engine(
            PermissionRule("writeFile", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun genericTool_unrememberableCapability_asksOnce() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.MODIFY_AGENT_CONFIG), "editFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun externalToolIsOneShotEvenWithFullAccessOrRememberedAllow() = runTest {
        val result = engine(
            PermissionRule("createPullRequest", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW),
            level = CommandAuthLevel.FULL_ACCESS
        ).evaluate(
            tool(ToolCapability.EXTERNAL_TOOL),
            "createPullRequest",
            emptyMap(),
            AgentMode.BUILD
        )

        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, result.verdict)
        assertTrue(result.rememberablePatterns.isEmpty())
        assertNotNull(result.rememberDisabledReason)
    }

    @Test
    fun genericTool_wholeDenyRule_asks() = runTest {
        // 非 shell 工具只认「整工具 ALLOW」记忆，DENY 规则不产生硬拒绝 → 落到默认的整工具 ASK
        val e = engine(
            PermissionRule("writeFile", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertEquals(listOf(PermissionRule.WHOLE_TOOL), r.rememberablePatterns)
    }

    // ── remember：去重后逐条落库 ────────────────────────────────────

    @Test
    fun remember_dedupesAndAddsEach() = runTest {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        coEvery { repo.add(any(), any()) } just runs
        val e = ToolPermissionPolicyEngine(repo, mockk(relaxed = true), mockk(relaxed = true))

        e.remember("Bash", listOf("git pull", "git pull", "ls"), PermissionScope.PROJECT)

        coVerify(exactly = 2) { repo.add(PermissionScope.PROJECT, any()) }
        coVerify { repo.add(PermissionScope.PROJECT, PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)) }
        coVerify { repo.add(PermissionScope.PROJECT, PermissionRule("Bash", "ls", PermissionDecision.ALLOW)) }
    }

    // ── 「规则拦截」挡位：命中高危规则 → 人工确认；普通命令直接放行 ─────────────

    @Test
    fun ruleBased_highRiskHit_asksWithRuleTitle() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("dd if=/dev/zero of=/dev/sda"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.askTitle?.startsWith("命中高危规则") == true)
        // 递归删除不可记忆
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun ruleBased_nonRecursiveRmWithoutRuleHitRequiresOneShotApproval() = runTest {
        // 裸 rm 单文件不在高危规则覆盖范围内（高危只管递归/通配/批量），可静态判定 → 放行
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm file.txt"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    @Test
    fun ruleBased_allRulesDisabledStillRequiresDeletionApproval() = runTest {
        // Disabling generic high-risk rules never disables the dedicated delete approval flow.
        val e = engine(highRiskRules = HighRiskRulePresets.RULES.map { it.copy(enabled = false) })
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm file.txt"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    @Test
    fun ruleBased_unanalyzableBenign_allowed() = runTest {
        // 未命中高危规则且不可静态判定（含命令替换）→ 对原文做破坏特征扫描，无命中则放行
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo $(whoami)"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun ruleBased_unanalyzableDestructive_asks() = runTest {
        // 不可静态判定且原文含破坏特征 → 人工确认，且不可记忆
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf $(echo /tmp/x)"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    // ── 「完全权限」挡位：直接放行，仅保留灾难防护与可选兜底 ──────────────────

    @Test
    fun fullAccess_allowsHighRiskCommand() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git reset --hard origin/main"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun gitHubMutationsAlwaysRequireOneShotApproval() = runTest {
        for (level in CommandAuthLevel.entries) {
            val engine = engine(level = level, safetyDisabled = true)
            for (command in listOf(
                "git push origin feature",
                "git -C /root/workspace push origin feature",
                "gh pr create --title x --body y",
                "gh --repo owner/repo pr create --title x --body y",
                "gh api repos/owner/repo/pulls --method POST",
                "git branch -D feature",
                "git -C /root/workspace branch --delete feature"
            )) {
                val result = engine.evaluate(
                    tool(ToolCapability.EXECUTE_COMMANDS),
                    "Bash",
                    bash(command),
                    AgentMode.BUILD,
                    workingDirectory = "/root/workspace"
                )
                assertEquals("$level should require approval for: $command", ToolPermissionPolicyEngine.Verdict.ASK, result.verdict)
                assertTrue(result.rememberablePatterns.isEmpty())
                assertNotNull(result.rememberDisabledReason)
                assertFalse(result.aiReviewable)
            }
        }
    }

    @Test
    fun fullAccess_unanalyzableDestructive_isAllowed() = runTest {
        // 「完全权限」按字面执行：灾难防护之外一律放行，不再有第二道兜底拦截
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun fullAccess_safetyDisabled_allowsUnanalyzable() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS, safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun fullAccess_allowsNonDestructiveCommandWithStderrRedirect() = runTest {
        // 回归：此前 looksDestructive 把任何含 `>` 的命令判成破坏性，
        // 导致「不可静态判定 + 2>/dev/null」这类与删除无关的排查命令被硬拒绝。
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            bash("cat $(find . -name '*.log') 2>/dev/null | head -50"),
            AgentMode.BUILD
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun fullAccess_safetyDisabled_stillHardBlocksRootRm() = runTest {
        // 通用安全拦截开关不关闭删除目标的硬拦截。
        val e = engine(level = CommandAuthLevel.FULL_ACCESS, safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 「自主执行」挡位：统一标记 aiReviewable 交工作流层 AI 审查 ─────────────

    @Test
    fun autonomous_marksAiReviewable() = runTest {
        val e = engine(level = CommandAuthLevel.AUTONOMOUS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git clone https://x"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.aiReviewable)
        // 命令的「始终允许」记忆已下线，一律只支持单次放行
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun shellCommandWithoutIntent_isRejectedEvenIfOtherwiseSafe() = runTest {
        val e = engine(level = CommandAuthLevel.AUTONOMOUS)
        val result = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            mapOf("command" to JsonPrimitive("pwd")),
            AgentMode.BUILD,
            "/root/workspace"
        )

        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, result.verdict)
        assertTrue(result.denyReason.orEmpty().contains("意图"))
    }

    @Test
    fun autonomous_safeCommand_stillAutoAllowed() = runTest {
        // 内置白名单优先于挡位分流：ls 仍直接放行，不进入 AI 审查
        val e = engine(level = CommandAuthLevel.AUTONOMOUS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun autonomous_recursiveRm_reviewableButNotRememberable() = runTest {
        val e = engine(level = CommandAuthLevel.AUTONOMOUS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /tmp/build"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.aiReviewable)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun autonomous_catastrophicRm_stillDenied() = runTest {
        // 灾难防护在挡位分流之前，自主执行也不能硬闯
        val e = engine(level = CommandAuthLevel.AUTONOMOUS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /etc"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }
}
