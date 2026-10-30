package com.aicode.feature.workspace.domain

import com.aicode.core.watch.ChangeKind
import com.aicode.core.watch.FileChangeBatch

/**
 * 一批文件变更对 [DirectoryListingCache] 的影响计划。
 *
 * @property relistDirs 需要重新读盘的目录（集合为空且 [fullRefresh] 为假时无需重建树）。
 * @property dropTrees 需要连同子孙缓存一起丢弃的目录（删除——目录可能删了又重建，子列表会骗人）。
 * @property fullRefresh 明细被截断/batch 不可解析时：只保证「有变更」这一信号，调用方应清全缓存重建。
 * @property gitignoreTouched 根 `.gitignore` 被改动：调用方需丢弃已解析的忽略规则缓存。
 */
data class BrowseInvalidation(
    val relistDirs: Set<String> = emptySet(),
    val dropTrees: Set<String> = emptySet(),
    val fullRefresh: Boolean = false,
    val gitignoreTouched: Boolean = false
) {
    val isEmpty: Boolean
        get() = !fullRefresh && relistDirs.isEmpty() && dropTrees.isEmpty() && !gitignoreTouched
}

/**
 * 把 [FileChangeBatch] 翻译成 [BrowseInvalidation]（纯函数，便于单测）。
 *
 * 规则与理由：
 * - 变更路径 **P 的父目录**一定要重列——文件的新增/删除/重命名改的是父目录的列表；
 * - **P 自身**也重列：单层 inotify 语义下 P 是订阅目录的直接子级，若 P 是目录，其内部变化
 *   以 P 自身变更的形式到来（MODIFIED），重列 P 才能刷新展开着的子树；对文件路径是廉价空转；
 * - **DELETED 的目录**整棵树丢缓存：路径删除后缓存里的子孙全部作废，且同名目录重建后
 *   子内容可能完全不同（粘贴覆盖、checkout 都属此类），只丢自己不够；
 * - **truncated batch** 明细被截断（[FileChangeBatch.truncated]），失去逐条判断依据，只能全清；
 * - **`<root>/.gitignore`** 被触碰时提醒调用方重读忽略规则（树按它过滤节点）。
 *
 * @param batch 监听中心的一个批量窗口。
 * @param rootPath 工作区容器根路径（用于识别 `.gitignore` 是否指根级）。
 */
fun planBrowseInvalidation(batch: FileChangeBatch, rootPath: String): BrowseInvalidation {
    if (batch.truncated) return BrowseInvalidation(fullRefresh = true)
    val relist = LinkedHashSet<String>()
    val drop = LinkedHashSet<String>()
    var gitignoreTouched = false
    val gitignorePath = "$rootPath/.gitignore"
    for (change in batch.changes) {
        val p = change.containerPath
        val parent = p.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) relist.add(parent)
        if (p.isNotEmpty()) relist.add(p)
        if (change.kind == ChangeKind.DELETED) drop.add(p)
        if (p == gitignorePath) gitignoreTouched = true
    }
    return BrowseInvalidation(
        relistDirs = relist,
        dropTrees = drop,
        fullRefresh = false,
        gitignoreTouched = gitignoreTouched
    )
}
