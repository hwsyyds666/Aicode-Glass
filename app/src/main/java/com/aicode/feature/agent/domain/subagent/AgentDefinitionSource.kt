package com.aicode.feature.agent.domain.subagent

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider

/** 子代理定义来源：一个目录下的 `*.md`，每个文件一个 agent。 */
interface AgentDefinitionSource {
    /** 挂起函数：底层经 [FileAccessProvider]（远程模式走 SFTP），调用方须在协程里调用。 */
    suspend fun listDefinitions(): List<AgentDefinition>
}

/** 目录扫描：只取顶层 `*.md`，避免把技能目录等无关内容误当 agent 定义。 */
internal object AgentDefinitionDirectoryScanner {
    private const val TAG = "AgentDefinitionScanner"

    /**
     * 扫描 [root] 下顶层 `*.md`，每个文件一个定义。目录不存在时返回空列表；
     * 扫描过程异常（IO 失败、远程工作区未连接等）时降级为空列表并记日志，不向上抛。
     */
    suspend fun scan(provider: FileAccessProvider, root: String): List<AgentDefinition> = runCatching {
        if (!provider.isDirectory(root)) return@runCatching emptyList()
        val base = root.trimEnd('/')
        val paths = provider.listFiles(root)
            .filter { !it.isDirectory && it.name.endsWith(".md", ignoreCase = true) }
            .map { "$base/${it.name}" }
        if (paths.isEmpty()) return@runCatching emptyList()
        // 一次批量读取（ROOT 通道一次 su 往返），替代逐个 parse 的 N 次子进程。
        val contents = provider.readFiles(paths)
        paths.mapNotNull { path -> contents[path]?.let { AgentDefinitionParser.parseText(it, path) } }
            .sortedBy { it.name.lowercase() }
    }.getOrElse { e ->
        FileLogger.w(TAG, "扫描子代理定义目录失败: $root", e)
        emptyList()
    }
}
