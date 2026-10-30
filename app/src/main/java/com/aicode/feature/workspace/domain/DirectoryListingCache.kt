package com.aicode.feature.workspace.domain

import java.util.LinkedHashMap

/**
 * 目录列举缓存：容器路径 → 该目录最近一次**成功列举**的条目快照。
 *
 * 存在的原因：文件树的每次重建（展开/折叠/文件变更/手动刷新）都不必重新 listFiles 所有已展开
 * 目录——本地模式一次 list 是几十上百次 stat，ROOT 模式是一次 `su` fork + 逐项 stat fork，
 * 远程模式是一次网络往返。展开一个目录后再次折叠再展开，内容没有理由再读一遍盘。
 *
 * 设计约定（长期维护请先读这段）：
 * - **只缓存成功结果**：列举失败的目录不入缓存，下次重建会重试，错误状态不会粘住；
 * - **失效完全由事件驱动**：本类不提供 TTL，正确性依赖调用方在文件变更时调用
 *   [invalidate]/[invalidateTree]（见 [planBrowseInvalidation]）。漏失效的代价是树与磁盘短暂
 *   不一致，因此调用方必须保留可手动全量刷新的入口兜底；
 * - **有容量上限**：超过 [maxEntries] 按插入序淘汰最旧条目，防止用户把整棵仓库展开后内存无界增长；
 * - **线程安全**：内部 `synchronized`，读写都很快（纳秒级），不会与 listFiles 的 IO 竞争锁。
 *
 * @param maxEntries 最多缓存的目录数；淘汰是「整目录丢弃」，不含任何子级联逻辑。
 */
class DirectoryListingCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    private data class Snapshot(val entries: List<FileEntry>)

    private val lock = Any()

    /** 插入序 LinkedHashMap：淘汰时从头删最旧。 */
    private val map = LinkedHashMap<String, Snapshot>(16, 0.75f, false)

    /** 命中返回条目列表；未命中返回 null（调用方负责 listFiles 回填）。 */
    fun get(path: String): List<FileEntry>? = synchronized(lock) {
        map[path]?.entries
    }

    /** 写入成功列举结果；超过容量时淘汰最旧条目。 */
    fun put(path: String, entries: List<FileEntry>) = synchronized(lock) {
        map.remove(path)
        map[path] = Snapshot(entries)
        while (map.size > maxEntries) {
            val eldest = map.keys.iterator().next()
            map.remove(eldest)
        }
    }

    /** 失效单个目录（其列表重新读盘）。路径不存在时是 no-op。 */
    fun invalidate(path: String) = synchronized(lock) {
        if (path.isNotEmpty()) map.remove(path)
    }

    /**
     * 失效目录及其所有后代（目录被删除/移动时用）：缓存里的 `path/...` 子级一并丢弃，
     * 避免目录删了又重建后展示旧的子列表。
     */
    fun invalidateTree(path: String) = synchronized(lock) {
        if (path.isEmpty()) return@synchronized
        map.remove(path)
        val prefix = "$path/"
        map.keys.iterator().let { iter ->
            while (iter.hasNext()) if (iter.next().startsWith(prefix)) iter.remove()
        }
    }

    /** 清空全部（工作区切换、用户手动刷新时用）。 */
    fun clear() = synchronized(lock) { map.clear() }

    /** 当前缓存的目录数（诊断/测试用）。 */
    fun size(): Int = synchronized(lock) { map.size }

    companion object {
        /** 默认容量：按「用户实际展开的目录数」量级取，256 个目录 × 每目录数百条目不构成内存压力。 */
        const val DEFAULT_MAX_ENTRIES = 256
    }
}
