package com.aicode.feature.backup.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class BoundedBackupEntryReaderTest {
    @Test
    fun readsAnEntryAtTheLimitAndValidatesItsDeclaredLength() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        assertArrayEquals(bytes, BoundedBackupEntryReader.read(ByteArrayInputStream(bytes), "metadata.json", 4, 4))
    }

    @Test
    fun rejectsOversizedDeclarationsBeforeReading() {
        val input = TrackingInputStream(byteArrayOf(1, 2, 3, 4))
        val error = assertFailure { BoundedBackupEntryReader.read(input, "snapshot.json", 5, 4) }
        assertEquals(0, input.bytesRead)
        assertEquals(true, error.message?.contains("过大"))
    }

    @Test
    fun rejectsAnEntryThatExceedsTheHardReadLimit() {
        val error = assertFailure {
            BoundedBackupEntryReader.read(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)), "metadata.json", 4, 4)
        }
        assertEquals(true, error.message?.contains("上限"))
    }

    @Test
    fun rejectsTruncatedEntryContent() {
        val error = assertFailure {
            BoundedBackupEntryReader.read(ByteArrayInputStream(byteArrayOf(1, 2)), "snapshot.json", 3, 4)
        }
        assertEquals(true, error.message?.contains("长度不完整"))
    }

    private fun assertFailure(block: () -> Unit): IllegalArgumentException = try {
        block()
        fail("Expected an oversized or truncated entry to be rejected")
        error("unreachable")
    } catch (failure: IllegalArgumentException) {
        failure
    }

    private class TrackingInputStream(private val bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var bytesRead = 0
            private set

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { count -> if (count > 0) bytesRead += count }
    }
}
