package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider

/**
 * 目录型技能源的共享扫描逻辑：递归查找 SKILL.md / CLAUDE.md，
 * 每个含指令文件的目录解析为一个 Skill。
 *
 * 目录经 [FileAccessProvider] 以容器路径访问，本地与远程（SSH）同一套逻辑。
 */
object SkillDirectoryScanner {
    private const val TAG = "SkillDirectoryScanner"

    /** 允许一定的嵌套深度（比如 repo/skills/my-skill/SKILL.md）。 */
    private const val MAX_DEPTH = 4
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"

    /**
     * 头部匹配时读取的字符数：frontmatter 只有寥寥几行（name/description/required_tools），
     * 8K 足够覆盖；超长 frontmatter 截断后匹配不到会走全量回退，不会误判。
     */
    private const val FRONTMATTER_HEAD_CHARS = 8 * 1024

    /** 是否指令文件名（SKILL.md / CLAUDE.md，忽略大小写）。 */
    private fun isInstructionFile(relative: String): Boolean {
        val name = relative.substringAfterLast('/')
        return name.equals(SKILL_FILE, ignoreCase = true) || name.equals(CLAUDE_FILE, ignoreCase = true)
    }

    /**
     * 扫描 [root] 目录下所有合法技能，按名称排序。
     * 目录不存在时返回空列表；扫描过程异常（IO 失败、远程工作区未连接等）时降级为空列表并记日志，
     * 不向上抛——技能扫描失败不该让调用方（工作区切换、AI 请求）崩溃。
     *
     * 挂起函数：底层经 [FileAccessProvider]（远程模式走 SFTP），调用方须在协程里调用。
     */
    suspend fun scan(provider: FileAccessProvider, root: String): List<Skill> = runCatching {
        val picked = instructionFiles(provider, root)
        if (picked.isEmpty()) return@runCatching emptyList()

        // 一次批量读取（ROOT 通道一次 su 往返取回整批），替代逐个 readFile 的上百次子进程。
        val contents = provider.readFiles(picked.map { it.first })
        picked.mapNotNull { (filePath, dirPath) ->
            val text = contents[filePath] ?: return@mapNotNull null
            SkillParser.parseText(text, dirPath.substringAfterLast('/').ifBlank { dirPath })
                .copy(dirPath = dirPath)
        }.sortedBy { it.name.lowercase() }
    }.getOrElse { e ->
        FileLogger.w(TAG, "扫描技能目录失败: $root", e)
        emptyList()
    }

    /**
     * 精准定位并读取单个技能的正文（loadSkill 专用）：只读各候选文件的**头部**匹配 frontmatter 的
     * name，命中后再读该文件全文。相比 [scan]「读全部技能全文」，成本从
     * 「递归列举 + 全量正文批量读」降为「递归列举 + 一批头部读 + 一次全文读」。
     *
     * 匹配语义与 [scan] 完全一致：frontmatter 缺 name 时回退目录名；同目录 SKILL.md 优先于 CLAUDE.md。
     * 头部匹配全部落空时回退 [scan] 全量匹配——frontmatter 超长被截断、或技能确实不存在时
     * 仍给出与全量扫描一致的结果（技能不存在时这份开销就是一次准确的「未找到」判据）。
     *
     * 挂起函数：底层经 [FileAccessProvider]（远程模式走 SFTP），调用方须在协程里调用。
     */
    suspend fun locateAndRead(provider: FileAccessProvider, root: String, name: String): String? = runCatching {
        val picked = instructionFiles(provider, root)
        if (picked.isEmpty()) return@runCatching null

        // 头部批量读：frontmatter 在文件头，8K 足够；ROOT 通道一次 su 往返取回整批。
        val heads = provider.readFileHeads(picked.map { it.first }, FRONTMATTER_HEAD_CHARS)
        for ((filePath, dirPath) in picked) {
            val head = heads[filePath] ?: continue
            val meta = SkillParser.parseText(head, dirPath.substringAfterLast('/').ifBlank { dirPath })
            if (!meta.name.equals(name, ignoreCase = true)) continue
            // 命中：读全文并用真实 frontmatter 重新解析（头部可能截断了正文与 name 字段）
            val text = provider.readFiles(listOf(filePath))[filePath] ?: provider.readFile(filePath)
            return@runCatching SkillParser.parseText(text, meta.name).instructions.trim()
        }
        // 头部匹配不到：回退全量扫描（含「技能不存在」的准确判据）
        scan(provider, root).firstOrNull { it.name.equals(name, ignoreCase = true) }?.instructions
    }.getOrElse { e ->
        FileLogger.w(TAG, "定位技能失败: $root/$name", e)
        null
    }

    /**
     * 递归列举 [root] 下的指令文件，返回 `(文件容器路径, 目录容器路径)`；
     * 同目录同时存在 SKILL.md / CLAUDE.md 时与 [scan] 一样只取 SKILL.md。
     */
    private suspend fun instructionFiles(provider: FileAccessProvider, root: String): List<Pair<String, String>> {
        val base = root.trimEnd('/')
        // 递归结果里已带文件名（形如 "repo/skills/my-skill/SKILL.md"），直接据此挑出技能文件，
        // 不再对每个目录回查一次 listFiles——真机 ROOT 通道下那等于每个 skill 多 fork 一个 su 进程。
        return provider.listFilesRecursive(root, MAX_DEPTH)
            .map { it.removePrefix("./") }
            .filter { isInstructionFile(it) }
            .groupBy { it.substringBeforeLast('/', "") }
            .values.mapNotNull { files ->
                val chosen = files.firstOrNull { it.substringAfterLast('/').equals(SKILL_FILE, ignoreCase = true) }
                    ?: files.firstOrNull { it.substringAfterLast('/').equals(CLAUDE_FILE, ignoreCase = true) }
                    ?: return@mapNotNull null
                val dirRel = chosen.substringBeforeLast('/', "")
                val dirPath = if (dirRel.isEmpty()) base else "$base/$dirRel"
                "$base/$chosen" to dirPath
            }
    }
}
