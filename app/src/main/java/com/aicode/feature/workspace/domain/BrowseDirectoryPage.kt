package com.aicode.feature.workspace.domain

/** Page size for the expanded file tree. Further entries remain reachable through its load-more row. */
internal const val BROWSE_DIRECTORY_PAGE_SIZE = 500

internal data class BrowseDirectoryPage<T>(
    val visibleEntries: List<T>,
    val remainingCount: Int,
    val nextVisibleLimit: Int
)

internal fun <T> browseDirectoryPage(entries: List<T>, visibleLimit: Int): BrowseDirectoryPage<T> {
    val limit = visibleLimit.coerceAtLeast(1)
    val visibleCount = minOf(entries.size, limit)
    return BrowseDirectoryPage(
        visibleEntries = entries.take(visibleCount),
        remainingCount = (entries.size - visibleCount).coerceAtLeast(0),
        nextVisibleLimit = visibleCount + BROWSE_DIRECTORY_PAGE_SIZE
    )
}
