package com.aicode.feature.workspace.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseDirectoryPageTest {
    @Test
    fun `large directory exposes bounded page and next page`() {
        val entries = (0 until 1_200).toList()

        val first = browseDirectoryPage(entries, BROWSE_DIRECTORY_PAGE_SIZE)
        assertEquals((0 until 500).toList(), first.visibleEntries)
        assertEquals(700, first.remainingCount)
        assertEquals(1_000, first.nextVisibleLimit)

        val second = browseDirectoryPage(entries, first.nextVisibleLimit)
        assertEquals((0 until 1_000).toList(), second.visibleEntries)
        assertEquals(200, second.remainingCount)
        assertEquals(1_500, second.nextVisibleLimit)

        val last = browseDirectoryPage(entries, second.nextVisibleLimit)
        assertEquals(entries, last.visibleEntries)
        assertEquals(0, last.remainingCount)
    }

    @Test
    fun `small directories and invalid limits stay usable`() {
        val entries = listOf("a", "b")

        assertEquals(entries, browseDirectoryPage(entries, BROWSE_DIRECTORY_PAGE_SIZE).visibleEntries)
        assertEquals(0, browseDirectoryPage(entries, BROWSE_DIRECTORY_PAGE_SIZE).remainingCount)
        assertEquals(listOf("a"), browseDirectoryPage(entries, 0).visibleEntries)
    }
}
