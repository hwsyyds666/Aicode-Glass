package com.aicode.feature.agent.domain.prompt

import android.content.Context
import com.aicode.core.util.FileLogger
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.memory.MemoryRepository
import com.aicode.feature.agent.domain.memory.MemoryScope
import com.aicode.feature.agent.domain.model.AgentContext
import com.aicode.feature.agent.domain.skill.Skill
import com.aicode.feature.agent.domain.skill.SkillListFormatter
import com.aicode.feature.agent.domain.skill.SkillRelevanceMatcher
import com.aicode.feature.agent.domain.skill.SkillRepository
import com.aicode.feature.agent.domain.subagent.AgentDefinition
import com.aicode.feature.agent.domain.subagent.BuiltInSubAgentCatalog
import com.aicode.feature.agent.domain.subagent.InjectPart
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 按模块组装系统提示词：稳定基线放最前（享受 KV Cache），仅日期为低频变化。
 * 多数 Source 维护内容缓存，避免重复读取与格式化；静态基线片段除外，每次读盘以保证编辑即时生效。
 *
 * 片段分两类：
 * - 静态基线：`prompts/` 顶层 `<NN>-<名称>.md`，可被 `prompts.custom/` 按数字身份覆盖或新增；
 * - 按需叶子：`prompts/agent/` 下的无数字片段（模式提醒、子代理基线、压缩/标题提示词），按精确同名覆盖。
 *
 * `prompts.custom/` 存在 [PromptFragmentResolver.DISABLE_BUILTIN_FILE] 时，主代理提示词只由自定义数字片段组成，
 * 不再注入任何内置来源；此时用 `{{AICODE_*}}` 变量按需取回动态内容。
 */
@Singleton
class SystemPromptProvider @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val skillRepository: SkillRepository,
    private val memoryRepository: MemoryRepository,
    private val promptFragmentCatalog: PromptFragmentCatalog,
    private val containerInstaller: ContainerInstaller,
    private val executionModeHolder: ExecutionModeHolder
) {
    // 抽象独立的 Source
    interface PromptSource {
        suspend fun build(ctx: AgentContext): String?
    }

    private inner class StaticRuleSource : PromptSource {
        // 每次都重新读盘：未编辑时字符串一致，KV Cache 照常命中；编辑后立即生效，无需重启。
        // 技能/记忆等运行期内容由末尾的 99-runtime-context.md 片段用 {{AICODE_*}} 变量承载。
        override suspend fun build(ctx: AgentContext): String = promptFragmentCatalog.renderStatic(ctx.projectRoot)
    }

    private inner class ActiveSkillsSource : PromptSource {
        // 清单被预算降级时只记一次日志：build() 每轮 LLM 调用都会走到，逐次打会刷屏。
        @Volatile private var degradationLogged = false

        // 会话级缓存：同一 (sessionId, projectRoot) 内只扫一次磁盘，保持 system prompt 稳定以命中 KV 缓存；
        // 新开会话 / 切换工作区 / 重启 App 时缓存自然失效重建。扫描失败返回空列表且不缓存，下轮重试。
        private val cachedListsByKey = ConcurrentHashMap<SourceCacheKey, List<Skill>>()

        /** 启用技能列表快照：系统提示清单与每轮相关技能提醒共用，避免重复扫盘。 */
        suspend fun skills(ctx: AgentContext): List<Skill> {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            cachedListsByKey[key]?.let { return it }
            val skills = try {
                skillRepository.listSkills()
            } catch (e: Exception) {
                FileLogger.w(TAG, "扫描技能失败: ${e.message}", e)
                return emptyList()
            }
            cachedListsByKey[key] = skills
            trimIfNeeded()
            return skills
        }

        override suspend fun build(ctx: AgentContext): String? {
            val skills = skills(ctx)
            if (skills.isEmpty()) return null
            val content = SkillListFormatter.format(skills)
            if (content.isEmpty()) return null
            logDegradationOnce(skills, content)
            return content
        }

        private fun trimIfNeeded() {
            if (cachedListsByKey.size > SOURCE_CACHE_LIMIT) cachedListsByKey.clear()
        }

        private fun logDegradationOnce(skills: List<Skill>, formatted: String) {
            if (degradationLogged) return
            // 行数没少（每个技能都带描述列出来了）说明没触发降级，不打扰日志。
            if (formatted.lines().size >= skills.size) return
            degradationLogged = true
            val fullChars = skills.sumOf { it.name.length + it.description.length + 4 }
            FileLogger.i(
                TAG,
                "技能清单受预算降级: ${skills.size} 个技能，完整清单约 $fullChars 字符，" +
                    "实际注入 ${formatted.length} 字符（预算 ${SkillListFormatter.DEFAULT_CHAR_BUDGET}）"
            )
        }
    }

    /** 子代理专用精简基线：只保留工具用法、路径约定与安全边界，不含模式切换、结尾总结等主代理专属规则。 */
    private inner class SubAgentBaseSource : PromptSource {
        @Volatile private var cached: String? = null

        override suspend fun build(ctx: AgentContext): String =
            cached ?: resolvePrompt(SUBAGENT_BASE_FILE)
                .replace(LEADING_COMMENT, "")
                .trim()
                .also { cached = it }
    }

    /**
     * 内置子代理类型清单（仅注入主代理），由类型单一来源生成。
     * 会话级缓存，保持 system prompt 稳定以命中 KV 缓存。
     */
    private inner class SubAgentListSource : PromptSource {
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, String>()

        override suspend fun build(ctx: AgentContext): String? {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            val cached = cachedByKey[key]
            if (cached != null) return cached.ifEmpty { null }
            val content = BuiltInSubAgentCatalog.parentGuidance()
            cachedByKey[key] = content
            trimIfNeeded()
            return content
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    private inner class ProjectRuleSource : PromptSource {
        @Volatile private var cached: String? = null
        private var lastModified: Long = 0
        private var lastProjectRoot: String = ""

        override suspend fun build(ctx: AgentContext): String? {
            if (ctx.projectRoot.isBlank()) return null
            val agentsFile = File(ctx.projectRoot, AGENTS_FILE)
            val claudeFile = File(ctx.projectRoot, CLAUDE_FILE)
            val file = when {
                agentsFile.isFile && agentsFile.canRead() -> agentsFile to AGENTS_FILE
                claudeFile.isFile && claudeFile.canRead() -> claudeFile to CLAUDE_FILE
                else -> return null
            }
            
            val currentMod = file.first.lastModified()
            // 如果文件未修改且路径一致，直接返回快照基线，避免重复读取与格式化
            if (ctx.projectRoot == lastProjectRoot && currentMod == lastModified && cached != null) {
                return cached
            }
            
            val text = try { file.first.readText() } catch (e: Exception) { return null }
            if (text.isBlank()) return null
            
            cached = if (text.length > MAX_AGENTS_CHARS) {
                text.take(MAX_AGENTS_CHARS) + "\n…（${file.second} 过长，已截断）"
            } else {
                text
            }.trim()
            lastModified = currentMod
            lastProjectRoot = ctx.projectRoot
            return cached
        }
    }

    private inner class WorkspaceSource : PromptSource {
        override suspend fun build(ctx: AgentContext): String =
            if (ctx.projectRoot.isNotBlank()) "~/workspace" else "（未选择工作区）"
    }

    private inner class EnvironmentSource : PromptSource {
        override suspend fun build(ctx: AgentContext): String =
            when (executionModeHolder.currentMode()) {
                ExecutionMode.LOCAL_PROOT -> "本地容器（PRoot）"
                ExecutionMode.REMOTE_SSH -> "远程 SSH 服务器"
                ExecutionMode.DEVICE_ROOT ->
                    "真机 ROOT（命令经 su 直接在本机以 root 身份执行，非沙盒；对系统的修改真实生效且可能不可逆，务必谨慎）"
            }
    }

    /** 记忆清单拆分为全局/项目两组纯列表，供 `{{AICODE_MEMORY_GLOBAL}}` / `{{AICODE_MEMORY_PROJECT}}` 变量使用。 */
    private data class MemoryLists(val global: String?, val project: String?)

    private inner class MemoryListSource {
        // 会话级缓存：同一 (sessionId, projectRoot) 内只读一次盘，保持 system prompt 稳定以命中 KV 缓存；
        // 新开会话 / 切换工作区 / 重启 App 时缓存自然失效重建。空组用 null 字段表示。
        private val cachedByKey = ConcurrentHashMap<SourceCacheKey, MemoryLists>()

        fun build(ctx: AgentContext): MemoryLists {
            val key = SourceCacheKey(ctx.sessionId, ctx.projectRoot)
            cachedByKey[key]?.let { return it }
            val memories = try { memoryRepository.listMemories(ctx.projectRoot) } catch (e: Exception) {
                return MemoryLists(null, null)
            }
            val global = memories.filter { it.scope == MemoryScope.GLOBAL }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "无" } }" }
            val project = memories.filter { it.scope == MemoryScope.PROJECT }
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n") { "- ${it.name}: ${it.description.ifBlank { "无" } }" }
            val result = MemoryLists(global, project)
            cachedByKey[key] = result
            trimIfNeeded()
            return result
        }

        private fun trimIfNeeded() {
            if (cachedByKey.size > SOURCE_CACHE_LIMIT) cachedByKey.clear()
        }
    }

    /** 会话级缓存 key：同一会话同一工作区共享一份快照，避免每轮重扫磁盘导致 system prompt 变化。 */
    private data class SourceCacheKey(val sessionId: String?, val projectRoot: String)

    private val staticRuleSource = StaticRuleSource()
    private val subAgentBaseSource = SubAgentBaseSource()
    private val subAgentListSource = SubAgentListSource()
    private val memoryListSource = MemoryListSource()

    private val activeSkillsSource = ActiveSkillsSource()
    private val projectRuleSource = ProjectRuleSource()
    private val workspaceSource = WorkspaceSource()
    private val environmentSource = EnvironmentSource()

    private val customDir: File
        get() = File(containerInstaller.aicodeDir, "prompts.custom")

    /** 自定义目录顶层数字片段（数字身份 → 文件），进程内只扫一次（重启 App 才刷新）。 */
    private val customFragmentsByNumber: Map<Int, File> by lazy {
        PromptFragmentResolver.numberedFragments(customDir).toMap()
    }

    suspend fun build(agentContext: AgentContext): String {
        agentContext.agentDefinition?.let { return buildForSubAgent(it, agentContext) }

        if (PromptFragmentResolver.isBuiltinDisabled(customDir)) {
            return buildCustomOnly(agentContext)
        }

        // 1. 获取各个 Source 的基线快照。
        val rawStatic = staticRuleSource.build(agentContext)
        val skillsContent = activeSkillsSource.build(agentContext)
        val subAgentsContent = subAgentListSource.build(agentContext)
        val memories = memoryListSource.build(agentContext)
        val projectRules = projectRuleSource.build(agentContext)
        val workspaceContent = workspaceSource.build(agentContext)
        val environmentContent = environmentSource.build(agentContext)

        // 2. 变量就地展开：`{{AICODE_*}}` 替换为纯数据；未写变量的片段原样保留。
        val staticContent = renderVariables(
            rawStatic,
            skillsContent,
            memories.global,
            memories.project,
            subAgentsContent,
            projectRules,
            workspaceContent,
            environmentContent,
            currentDate()
        )

        // 4. 压缩空清单展开留下的多余空行；稳定基线在前、动态内容集中在末尾片段，均利于 KV Cache。
        return applySuperAgentGuidance(collapseBlankLines(staticContent), agentContext)
    }

    /**
     * 每轮「相关技能」提醒（对标 Claude Code 的 "Skills relevant to your task:"）。
     *
     * 系统提示里的技能清单按会话只注入一次，模型跑几轮工具后就淡忘了；在本轮请求发出前，
     * 按用户最新输入把最相关的几个技能连描述再推到他眼前，是提高 `loadSkill` 调用率的关键一招。
     * 命中时返回一小段文本，由调用方追加到本轮最新 user 消息末尾——不落库、不改系统提示，
     * 系统提示与历史消息逐字节不变，provider 前缀缓存照常命中（KV Cache 友好）。
     * 未命中 / 无技能返回 null。
     */
    suspend fun buildSkillHint(ctx: AgentContext, latestUserText: String): String? {
        if (latestUserText.isBlank()) return null
        val skills = activeSkillsSource.skills(ctx)
        if (skills.isEmpty()) return null
        return SkillRelevanceMatcher.buildHint(skills, latestUserText)
    }

    /**
     * [PromptFragmentResolver.DISABLE_BUILTIN_FILE] 生效时：只输出 `prompts.custom/` 顶层的数字片段，
     * 不注入任何内置来源；动态内容仅通过 `{{AICODE_*}}` 变量按需取回。
     */
    private suspend fun buildCustomOnly(ctx: AgentContext): String {
        val content = promptFragmentCatalog.renderCustomOnly(ctx.projectRoot)
        if (content.isEmpty()) {
            FileLogger.w(
                TAG,
                "已启用 ${PromptFragmentResolver.DISABLE_BUILTIN_FILE}，但 $customDir 下没有 <两位数字>-<名称>.md 片段，系统提示词为空"
            )
            return ""
        }
        val memories = memoryListSource.build(ctx)
        return applySuperAgentGuidance(renderVariables(
            content,
            activeSkillsSource.build(ctx),
            memories.global,
            memories.project,
            subAgentListSource.build(ctx),
            projectRuleSource.build(ctx),
            workspaceSource.build(ctx),
            environmentSource.build(ctx),
            currentDate()
        ), ctx)
    }

    private suspend fun applySuperAgentGuidance(prompt: String, ctx: AgentContext): String {
        if (!ctx.superAgentEnabled) return prompt
        return collapseBlankLines(
            "$prompt\n\n${resolvePrompt(SUPER_AGENT_FILE)}"
        )
    }

    /**
     * 按子代理定义组装提示词：只注入 [AgentDefinition.inject] 列出的片段，再接 agent 自己的提示词。
     * 不注入可用子代理清单（子代理不能嵌套派发）。定义正文里的 `{{AICODE_*}}` 变量同样会展开。
     */
    private suspend fun buildForSubAgent(
        definition: AgentDefinition,
        agentContext: AgentContext
    ): String = buildString {
        if (InjectPart.MAIN_RULES in definition.inject) {
            append(staticRuleSource.build(agentContext))
            append("\n\n")
        }
        if (InjectPart.BASE in definition.inject) {
            append(subAgentBaseSource.build(agentContext))
            append("\n\n")
        }

        val memories = memoryListSource.build(agentContext)
        append(
            renderVariables(
                definition.prompt,
                activeSkillsSource.build(agentContext),
                memories.global,
                memories.project,
                subAgentListSource.build(agentContext),
                projectRuleSource.build(agentContext),
                workspaceSource.build(agentContext),
                environmentSource.build(agentContext),
                currentDate()
            )
        )

        if (InjectPart.SKILLS in definition.inject) {
            activeSkillsSource.build(agentContext)?.let {
                append("\n\n")
                append(it)
            }
        }
        if (InjectPart.MEMORY in definition.inject) {
            listOfNotNull(memories.global, memories.project).takeIf { it.isNotEmpty() }?.let { groups ->
                append("\n\n")
                append(groups.joinToString("\n\n"))
            }
        }
        if (InjectPart.PROJECT_RULES in definition.inject) {
            projectRuleSource.build(agentContext)?.let {
                append("\n\n")
                append(it)
            }
        }

        append("\n\n")
        append(
            renderVariables(
                resolvePrompt(SUBAGENT_CONTEXT_FILE),
                null,
                null,
                null,
                null,
                null,
                workspaceSource.build(agentContext),
                environmentSource.build(agentContext),
                currentDate(),
                definition.name
            )
        )
    }

    /** 把片段里的 `{{AICODE_*}}` 占位符替换为真实内容；未出现的占位符保持原样，不影响 `{{INSTRUCTION}}` 等其它占位符。 */
    private fun renderVariables(
        text: String,
        skills: String?,
        memoryGlobal: String?,
        memoryProject: String?,
        subAgents: String?,
        projectRules: String?,
        workspace: String,
        environment: String,
        date: String,
        subAgentName: String = ""
    ): String {
        var out = text
        out = out.replace(SKILLS_VAR, skills.orEmpty())
        out = out.replace(MEMORY_GLOBAL_VAR, memoryGlobal.orEmpty())
        out = out.replace(MEMORY_PROJECT_VAR, memoryProject.orEmpty())
        out = out.replace(SUBAGENTS_VAR, subAgents.orEmpty())
        out = out.replace(PROJECT_RULES_VAR, projectRules.orEmpty())
        out = out.replace(WORKSPACE_VAR, workspace)
        out = out.replace(ENVIRONMENT_VAR, environment)
        out = out.replace(DATE_VAR, date)
        out = out.replace(SUBAGENT_NAME_VAR, subAgentName)
        return out
    }

    private fun currentDate(): String =
        java.time.ZonedDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    /** 把连续 3 个以上换行压成 2 个：变量展开后空清单会留下额外空行。 */
    private fun collapseBlankLines(text: String): String =
        text.trim().replace(Regex("\\n{3,}"), "\n\n")

    /**
     * 按优先级解析单个提示词片段：
     * - 名字是顶层 `<NN>-*.md`：先按数字身份在 `prompts.custom/` 顶层找覆盖（尾部名称可自由改），
     * - 其余名字（含 `agent/` 子目录）：按精确同名在 `prompts.custom/<name>` 找覆盖；
     * 再落到 `prompts/<name>`（本地默认副本），最后 assets（内置兜底）。
     *
     * 本地副本由 [ContainerInstaller.extractPrompts] 在启动时全量释放，App 升级后随之更新。
     */
    fun resolvePrompt(name: String): String {
        PromptFragmentResolver.parseNumber(name)
            ?.let { number -> readFileOrNull(customFragmentsByNumber[number])?.let { return it } }
        readFileOrNull(File(customDir, name))?.let { return it }
        readFileOrNull(File(File(containerInstaller.aicodeDir, "prompts"), name))?.let { return it }
        return context.assets.open("prompts/$name").bufferedReader().use { it.readText() }
    }

    private fun readFileOrNull(file: File?): String? {
        if (file == null || !file.isFile) return null
        return try {
            file.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取提示词失败 ${file.name}: ${e.message}", e)
            null
        }
    }

    private companion object {
        const val TAG = "SystemPromptProvider"
        const val AGENTS_FILE = "AGENTS.md"
        const val CLAUDE_FILE = "CLAUDE.md"
        const val SUBAGENT_BASE_FILE = "agent/subagent-base.md"
        const val SUBAGENT_CONTEXT_FILE = "agent/subagent-context.md"
        const val SUPER_AGENT_FILE = "agent/super-agent.md"
        const val MAX_AGENTS_CHARS = 32_000
        /** 会话级缓存 key 数量上限：超过后整体清空，仅防长期累积；正常会话数远小于此。 */
        const val SOURCE_CACHE_LIMIT = 32
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")

        /** 内置静态基线：数字身份 → 规范文件名，决定默认拼接顺序。 */
        // 片段里可用的运行期变量，渲染时替换为真实内容
        const val SKILLS_VAR = "{{AICODE_SKILLS}}"
        const val MEMORY_GLOBAL_VAR = "{{AICODE_MEMORY_GLOBAL}}"
        const val MEMORY_PROJECT_VAR = "{{AICODE_MEMORY_PROJECT}}"
        const val SUBAGENTS_VAR = "{{AICODE_SUBAGENTS}}"
        const val PROJECT_RULES_VAR = "{{AICODE_PROJECT_RULES}}"
        const val WORKSPACE_VAR = "{{AICODE_WORKSPACE}}"
        const val ENVIRONMENT_VAR = "{{AICODE_ENVIRONMENT}}"
        const val DATE_VAR = "{{AICODE_DATE}}"
        const val SUBAGENT_NAME_VAR = "{{AICODE_SUBAGENT_NAME}}"
    }
}
