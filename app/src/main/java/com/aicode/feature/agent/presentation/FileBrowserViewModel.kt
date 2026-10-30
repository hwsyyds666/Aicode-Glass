package com.aicode.feature.agent.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.core.util.FileLogger
import com.aicode.core.util.GitIgnoreMatcher
import com.aicode.core.watch.FileChangeBatch
import com.aicode.core.watch.FileChangeHub
import com.aicode.core.watch.asDirtySignal
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import com.aicode.feature.workspace.domain.DirectoryListingCache
import com.aicode.feature.workspace.domain.BROWSE_DIRECTORY_PAGE_SIZE
import com.aicode.feature.workspace.domain.FileAccessProvider
import com.aicode.feature.workspace.domain.FileEntry
import com.aicode.feature.workspace.domain.WorkspacePathMapper
import com.aicode.feature.workspace.domain.browseDirectoryPage
import com.aicode.feature.workspace.domain.isValidFileEntryName
import com.aicode.feature.workspace.domain.planBrowseInvalidation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import java.util.concurrent.ConcurrentHashMap

/** Owns the workspace file-tree state independently from chat/session state. */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val fileChangeHub: FileChangeHub,
    private val workspaceRepository: WorkspaceRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel() {
    private val _workspacePath = MutableStateFlow("")
    private val _expandedPaths = MutableStateFlow(setOf(WorkspacePathMapper.CONTAINER_ROOT))
    private val expansionPrefs = context.getSharedPreferences("file_tree_expansion", Context.MODE_PRIVATE)
    private val _expandingPath = MutableStateFlow<String?>(null)
    private val _fileOpPaths = MutableStateFlow<Set<String>>(emptySet())
    private val _browseRefresh = MutableStateFlow(0)
    private val dirListingCache = DirectoryListingCache()
    private var gitignoreCache: List<String>? = null
    private val _browseClipboard = MutableStateFlow<BrowseClipboard?>(null)
    private val _pasteConflict = MutableStateFlow<Pair<String, String>?>(null)
    private val visibleEntryLimits = ConcurrentHashMap<String, Int>()

    val expandedPaths: StateFlow<Set<String>> = _expandedPaths.asStateFlow()
    val expandingPath: StateFlow<String?> = _expandingPath.asStateFlow()
    val fileOpPaths: StateFlow<Set<String>> = _fileOpPaths.asStateFlow()
    val browseClipboard: StateFlow<BrowseClipboard?> = _browseClipboard.asStateFlow()
    val pasteConflict: StateFlow<Pair<String, String>?> = _pasteConflict.asStateFlow()

    init {
        viewModelScope.launch {
            workspaceRepository.current
                .map { it?.path.orEmpty() }
                .distinctUntilChanged()
                .collect { path ->
                    _workspacePath.value = path
                    dirListingCache.clear()
                    visibleEntryLimits.clear()
                    gitignoreCache = null
                    _expandedPaths.value = loadExpansion(path)
                    _browseClipboard.value = null
                    _pasteConflict.value = null
                    _browseRefresh.value++
                }
        }
    }

    private fun loadExpansion(workspace: String): Set<String> =
        workspace.takeIf(String::isNotBlank)
            ?.let { expansionPrefs.getStringSet(it, null)?.toSet() }
            ?: setOf(WorkspacePathMapper.CONTAINER_ROOT)

    private fun saveExpansion(workspace: String, paths: Set<String>) {
        if (workspace.isBlank()) return
        expansionPrefs.edit().putStringSet(workspace, HashSet(paths)).apply()
    }

    /** Cached snapshots let collapse/re-expand avoid IO; change events precisely invalidate affected paths. */
    val browseState: StateFlow<FileBrowseState> = _expandedPaths
        .flatMapLatest { expanded ->
            val watched = expanded + WorkspacePathMapper.CONTAINER_ROOT
            val triggers = merge(
                watched.map {
                    fileChangeHub.watchWorkspace(it, fallbackPoll = false)
                        .onEach { batch -> applyBrowseInvalidation(batch) }
                        .asDirtySignal()
                }.merge().debounce(BROWSE_DEBOUNCE_MS),
                _browseRefresh.drop(1).map { Unit }
            )
            flow {
                emit(buildBrowseTree(expanded))
                triggers.collect { emit(buildBrowseTree(expanded)) }
            }.flowOn(Dispatchers.IO)
        }
        .onEach { _expandingPath.value = null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FileBrowseState.Loading)

    private fun applyBrowseInvalidation(batch: FileChangeBatch) {
        val plan = planBrowseInvalidation(batch, WorkspacePathMapper.CONTAINER_ROOT)
        if (plan.fullRefresh) {
            dirListingCache.clear()
            gitignoreCache = null
            return
        }
        plan.relistDirs.forEach(dirListingCache::invalidate)
        plan.dropTrees.forEach(dirListingCache::invalidateTree)
        if (plan.gitignoreTouched) gitignoreCache = null
    }

    private suspend fun buildBrowseTree(expanded: Set<String>): FileBrowseState {
        val startedAt = System.currentTimeMillis()
        val listingCount = ListingCounter()
        val root = WorkspacePathMapper.CONTAINER_ROOT
        val rootEntries = listDirCached(root, listingCount) ?: run {
            FileLogger.w(TAG, "列目录失败: $root")
            return FileBrowseState.Error("无法读取工作区根目录")
        }
        val ignorePatterns = cachedRootGitignore(root)
        val rootExpanded = root in expanded
        val nodes = mutableListOf<FileTreeNode>()
        nodes += FileTreeNode(
            entry = FileEntry(name = "workspace", isDirectory = true, size = 0, lastModified = 0),
            path = root,
            depth = 0,
            isRoot = true,
            isExpanded = rootExpanded
        )
        if (rootExpanded) {
            appendBrowseChildren(root, rootEntries, 1, expanded, ignorePatterns, emptyList(), listingCount, nodes)
        }
        FileLogger.d(
            TAG,
            "文件树重建: 命中缓存目录=${dirListingCache.size()} 实列目录=${listingCount.value} 节点=${nodes.size} 耗时=${System.currentTimeMillis() - startedAt}ms"
        )
        return FileBrowseState.Success(nodes)
    }

    private class ListingCounter(var value: Int = 0)

    private suspend fun listDirCached(path: String, listingCount: ListingCounter): List<FileEntry>? {
        dirListingCache.get(path)?.let { return it }
        val entries = try {
            fileAccess.listFilesForBrowse(path)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return null
        }
        dirListingCache.put(path, entries)
        listingCount.value++
        return entries
    }

    private suspend fun cachedRootGitignore(root: String): List<String> {
        gitignoreCache?.let { return it }
        val patterns = try {
            val path = "$root/.gitignore"
            if (!fileAccess.exists(path)) emptyList()
            else fileAccess.readFile(path).lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
                .map { it.removeSuffix("/") }
                .toList()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        gitignoreCache = patterns
        return patterns
    }

    private suspend fun appendBrowseChildren(
        parent: String,
        entries: List<FileEntry>,
        depth: Int,
        expanded: Set<String>,
        ignorePatterns: List<String>,
        relParts: List<String>,
        listingCount: ListingCounter,
        out: MutableList<FileTreeNode>
    ) {
        val orderedEntries = entries.distinctBy { it.name }.sortedWith(BROWSE_ORDER)
        val page = browseDirectoryPage(
            entries = orderedEntries,
            visibleLimit = visibleEntryLimits[parent] ?: BROWSE_DIRECTORY_PAGE_SIZE
        )
        for (entry in page.visibleEntries) {
            val path = "$parent/${entry.name}"
            val parts = relParts + entry.name
            val ignored = ignorePatterns.isNotEmpty() && GitIgnoreMatcher.isIgnored(ignorePatterns, parts)
            val open = entry.isDirectory && path in expanded
            if (!open) {
                out += FileTreeNode(entry, path, depth, isRoot = false, isExpanded = false, ignored = ignored)
                continue
            }
            val children = listDirCached(path, listingCount)
            out += FileTreeNode(entry, path, depth, isRoot = false, isExpanded = true, hasError = children == null, ignored = ignored)
            if (children != null) appendBrowseChildren(path, children, depth + 1, expanded, ignorePatterns, parts, listingCount, out)
        }
        if (page.remainingCount > 0) {
            out += FileTreeNode(
                entry = FileEntry(name = "", isDirectory = false, size = 0, lastModified = 0),
                path = "$parent$LOAD_MORE_PATH_SUFFIX",
                depth = depth,
                isRoot = false,
                isExpanded = false,
                isLoadMore = true,
                loadMoreCount = page.remainingCount
            )
        }
    }

    fun refreshBrowse() {
        dirListingCache.clear()
        gitignoreCache = null
        _browseRefresh.value++
    }

    private fun rebuildBrowseAfterWrite() {
        _browseRefresh.value++
    }

    fun toggleExpand(path: String) {
        if (path.endsWith(LOAD_MORE_PATH_SUFFIX)) {
            val parent = path.removeSuffix(LOAD_MORE_PATH_SUFFIX)
            val currentLimit = visibleEntryLimits[parent] ?: BROWSE_DIRECTORY_PAGE_SIZE
            visibleEntryLimits[parent] = currentLimit + BROWSE_DIRECTORY_PAGE_SIZE
            _browseRefresh.value++
            return
        }
        if (path == _expandingPath.value) return
        val current = _expandedPaths.value
        val updated = if (path in current) {
            current.filterNot { it == path || it.startsWith("$path/") }.toSet()
        } else {
            _expandingPath.value = path
            current + path
        }
        _expandedPaths.value = updated
        saveExpansion(_workspacePath.value, updated)
    }

    private fun mutateBrowse(
        busyPaths: Set<String> = emptySet(),
        onResult: (Boolean) -> Unit,
        block: suspend () -> Boolean
    ) = viewModelScope.launch {
        if (busyPaths.isNotEmpty()) _fileOpPaths.value = _fileOpPaths.value + busyPaths
        try {
            val success = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    FileLogger.w(TAG, "文件操作失败", error)
                    false
                }
            }
            if (success) {
                busyPaths.forEach { path ->
                    dirListingCache.invalidate(path.substringBeforeLast('/', ""))
                    dirListingCache.invalidateTree(path)
                }
                rebuildBrowseAfterWrite()
            }
            onResult(success)
        } finally {
            if (busyPaths.isNotEmpty()) _fileOpPaths.value = _fileOpPaths.value - busyPaths
        }
    }

    private fun browseChildPath(parent: String, name: String): String? =
        if (isValidFileEntryName(name)) "$parent/${name.trim()}" else null

    fun createBrowseFile(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = browseChildPath(parent, name)
        mutateBrowse(target?.let { setOf(it) } ?: emptySet(), onResult) {
            if (target == null || fileAccess.exists(target)) false
            else {
                fileAccess.writeFile(target, "", overwrite = false)
                true
            }
        }
    }

    fun createBrowseFolder(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = browseChildPath(parent, name)
        mutateBrowse(target?.let { setOf(it) } ?: emptySet(), onResult) {
            if (target == null || fileAccess.exists(target)) false
            else {
                fileAccess.mkdirs(target)
                fileAccess.isDirectory(target)
            }
        }
    }

    fun renameBrowseEntry(path: String, newName: String, onResult: (Boolean) -> Unit) =
        mutateBrowse(setOf(path), onResult) {
            val parent = path.substringBeforeLast('/', "")
            if (parent.isEmpty() || !isValidFileEntryName(newName)) false
            else {
                fileAccess.rename(path, "$parent/${newName.trim()}")
                true
            }
        }

    fun deleteBrowseEntry(path: String, onResult: (Boolean) -> Unit) =
        mutateBrowse(setOf(path), onResult) {
            fileAccess.deleteRecursively(path)
            true
        }

    fun copyBrowseEntry(path: String, name: String) {
        _browseClipboard.value = BrowseClipboard(path, name, isCut = false)
    }

    fun cutBrowseEntry(path: String, name: String) {
        _browseClipboard.value = BrowseClipboard(path, name, isCut = true)
    }

    fun clearBrowseClipboard() {
        _browseClipboard.value = null
    }

    fun pasteBrowseEntry(targetDir: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val clip = _browseClipboard.value ?: return@launch onResult(false)
        if (clip.sourcePath == targetDir) return@launch onResult(false)
        val name = clip.sourceName
        if (!isValidFileEntryName(name)) return@launch onResult(false)
        val target = "$targetDir/$name"
        if (target == clip.sourcePath || target.startsWith("${clip.sourcePath.trimEnd('/')}/")) {
            return@launch onResult(false)
        }
        if (withContext(Dispatchers.IO) { fileAccess.exists(target) }) {
            _pasteConflict.value = clip.sourcePath to target
            return@launch
        }
        performPaste(clip, target, overwrite = false, onResult)
    }

    fun pasteBrowseEntryOverwrite() {
        val conflict = _pasteConflict.value ?: return
        val clip = _browseClipboard.value ?: return
        _pasteConflict.value = null
        performPaste(clip, conflict.second, overwrite = true) {}
    }

    fun clearPasteConflict() {
        _pasteConflict.value = null
    }

    private fun performPaste(clip: BrowseClipboard, target: String, overwrite: Boolean, onResult: (Boolean) -> Unit) {
        val busyPaths = if (clip.isCut) setOf(clip.sourcePath, target) else setOf(target)
        mutateBrowse(busyPaths, { success ->
            if (success) _browseClipboard.value = null
            onResult(success)
        }) {
            if (clip.isCut) fileAccess.move(clip.sourcePath, target, overwrite)
            else fileAccess.copy(clip.sourcePath, target, overwrite)
            true
        }
    }

    private companion object {
        const val TAG = "FileBrowserViewModel"
        const val BROWSE_DEBOUNCE_MS = 300L
        const val LOAD_MORE_PATH_SUFFIX = "/\u0000aicode-load-more"
        val BROWSE_ORDER: Comparator<FileEntry> = compareByDescending<FileEntry> { it.isDirectory }.thenBy { it.name.lowercase() }
    }
}
