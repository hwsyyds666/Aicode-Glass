package com.aicode.feature.workspace.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class SftpOperationGateTest {
    @Test
    fun oneHundredSlowConcurrentOperationsSerializeAndComplete() = runTest {
        val gate = SftpOperationGate(ioDispatcher = StandardTestDispatcher(testScheduler))
        val active = AtomicInteger()
        val peak = AtomicInteger()

        val results = coroutineScope {
            (0 until 100).map { index ->
                async {
                    gate.execute(timeoutMs = 10_000, onOperationTimeout = {}) {
                        val now = active.incrementAndGet()
                        peak.updateAndGet { previous -> maxOf(previous, now) }
                        try {
                            delay(5)
                            index
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                }
            }.awaitAll()
        }

        assertEquals((0 until 100).toList(), results)
        assertEquals("sshj SFTPClient is not thread-safe", 1, peak.get())
        assertEquals(0, active.get())
    }

    @Test
    fun cancellationOfQueuedOperationDoesNotStrandTheGate() = runTest {
        val gate = SftpOperationGate(ioDispatcher = StandardTestDispatcher(testScheduler))
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var queuedEntered = false

        val first = launch {
            gate.execute(timeoutMs = 1_000, onOperationTimeout = {}) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val queued = launch {
            gate.execute(timeoutMs = 1_000, onOperationTimeout = {}) { queuedEntered = true }
        }
        yield()
        assertFalse(queuedEntered)
        queued.cancelAndJoin()

        releaseFirst.complete(Unit)
        first.join()
        gate.execute(timeoutMs = 1_000, onOperationTimeout = {}) { queuedEntered = true }
        assertTrue("a cancelled waiter must not leave the mutex locked", queuedEntered)
    }

    @Test
    fun cancellingInFlightOperationInvalidatesBeforeTheNextOperationStarts() = runTest {
        val gate = SftpOperationGate(ioDispatcher = StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val invalidations = AtomicInteger()
        var nextStartedAfterInvalidation = false

        val active = launch {
            gate.execute(timeoutMs = 1_000, onOperationTimeout = { invalidations.incrementAndGet() }) {
                entered.complete(Unit)
                CompletableDeferred<Unit>().await()
            }
        }
        entered.await()
        active.cancelAndJoin()

        gate.execute(timeoutMs = 1_000, onOperationTimeout = {}) {
            nextStartedAfterInvalidation = invalidations.get() == 1
        }
        assertTrue("the cancelled sshj client must be invalidated before the gate is released", nextStartedAfterInvalidation)
    }

    @Test
    fun deadlineIncludesQueueWaitAndDoesNotInvalidateAnotherCallersClient() = runTest {
        val gate = SftpOperationGate(ioDispatcher = StandardTestDispatcher(testScheduler))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val invalidations = AtomicInteger()

        val activeOperation = launch {
            gate.execute(timeoutMs = 1_000, onOperationTimeout = { invalidations.incrementAndGet() }) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        val error = runCatching {
            gate.execute(timeoutMs = 25, onOperationTimeout = { invalidations.incrementAndGet() }) { Unit }
        }.exceptionOrNull()
        assertTrue("queue deadline should surface as a useful I/O error", error is IOException)
        assertEquals("a timed-out waiter must not tear down the active SFTP channel", 0, invalidations.get())

        release.complete(Unit)
        activeOperation.join()
        assertEquals("the active operation should remain healthy", 0, invalidations.get())
    }

    @Test
    fun timedOutOperationInvalidatesAndReleasesTheGate() = runTest {
        val gate = SftpOperationGate(ioDispatcher = StandardTestDispatcher(testScheduler))
        val invalidations = AtomicInteger()

        val error = runCatching {
            gate.execute(timeoutMs = 25, onOperationTimeout = { invalidations.incrementAndGet() }) {
                delay(1_000)
            }
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals(1, invalidations.get())
        assertEquals(42, gate.execute(timeoutMs = 1_000, onOperationTimeout = {}) { 42 })
    }
}
