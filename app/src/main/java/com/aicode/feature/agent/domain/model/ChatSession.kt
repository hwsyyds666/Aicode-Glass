package com.aicode.feature.agent.domain.model

/**
 * Agent 的工作模式（对齐 Claude Code 的 Build / Plan / Ask 三段式）：
 * - [BUILD] 构建：可读写文件、执行命令；是否弹窗授权由「授权挡位 + 高危规则」等权限设置决定，
 *   模式本身不再内置放行策略（原 AUTO 的「全放行」语义由「完全权限」挡位承担）。
 * - [PLAN] 计划：先创建可见清单，再按清单推进；工具权限与 BUILD 的用户授权挡位一致。
 * - [ASK] 提问：只读问答，禁止一切写操作，不进入计划执行流程（对齐 Claude Code 的 Ask）。
 */
enum class AgentMode {
    BUILD, // 构建模式（默认）：允许所有授权操作，具体授权口径见 ToolPermissionPolicyEngine
    PLAN,  // 计划模式，先计划后执行；不额外放宽工具权限
    ASK    // 提问模式，只读问答；写操作被物理拦截，且不允许 AI 自行切换计划模式
}

/**
 * 一次独立的聊天会话。消息通过 sessionId 归属到会话，切换会话即切换聊天历史。
 */
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val workspacePath: String = "",
    val mode: AgentMode = AgentMode.BUILD,
    /** 进入 PLAN 前的模式（BUILD 或 ASK）；退出 PLAN 时恢复，null 视为 BUILD。 */
    val modeBeforePlan: AgentMode? = null,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM,
    val providerId: String? = null,
    val model: String? = null,
    val totalInputTokens: Int = 0,
    val totalOutputTokens: Int = 0,
    val lastInputTokens: Int = 0,
    val isPinned: Boolean = false,
    /** 子代理会话：父会话 id；null 表示普通根会话。 */
    val parentId: String? = null,
    /** 子代理会话：派生子代理的类型（如 coder / researcher）；null 表示普通根会话。 */
    val subagentType: String? = null,
    val subagentStatus: String? = null,
    val allowedWritePaths: String? = null,
    val allowedReadPaths: String? = null,
    val subagentCreatedCount: Int = 0,
    val subagentReworkCount: Int = 0,
    val superAgentEnabled: Boolean = false,
    val dispatchMessageId: String? = null
)
