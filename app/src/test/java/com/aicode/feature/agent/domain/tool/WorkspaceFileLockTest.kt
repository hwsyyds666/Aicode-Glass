package com.aicode.feature.agent.domain.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceFileLockTest {
    @Test
    fun samePathSerializesAndDifferentPathsCanRunConcurrently() = runTest {
        val lock = WorkspaceFileLock()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val first = launch {
            lock.withLock("workspace/file-a") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val samePathWaiter = launch {
            lock.withLock("workspace/file-a") { secondEntered.complete(Unit) }
        }
        yield()
        assertFalse("same-path mutation must wait", secondEntered.isCompleted)

        val differentPathEntered = CompletableDeferred<Unit>()
        lock.withLock("workspace/file-b") { differentPathEntered.complete(Unit) }
        assertTrue("unrelated paths should not be serialized", differentPathEntered.isCompleted)

        releaseFirst.complete(Unit)
        secondEntered.await()
        first.join()
        samePathWaiter.join()
    }

    @Test
    fun cancelledWaiterReleasesItsLockReference() = runTest {
        val lock = WorkspaceFileLock()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val first = launch {
            lock.withLock("workspace/file") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val cancelledWaiter = launch { lock.withLock("workspace/file") {} }
        yield()
        cancelledWaiter.cancelAndJoin()
        releaseFirst.complete(Unit)
        first.join()

        var acquired = false
        lock.withLock("workspace/file") { acquired = true }
        assertTrue("the cancelled waiter must not strand the mutex", acquired)
    }
}
