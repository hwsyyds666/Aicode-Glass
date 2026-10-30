package com.aicode.feature.workspace.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * Serializes access to sshj's non-thread-safe SFTP client without blocking the caller thread.
 * The deadline covers both waiting in the queue and the actual operation. A waiter timing out
 * does not invalidate a client currently used by another coroutine; an in-flight timeout does.
 */
internal class SftpOperationGate(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mutex: Mutex = Mutex()
) {
    suspend fun <T> execute(
        timeoutMs: Long,
        onOperationTimeout: suspend () -> Unit,
        operation: suspend () -> T
    ): T {
        require(timeoutMs > 0) { "timeoutMs must be positive" }
        return try {
            withContext(ioDispatcher) {
                withTimeout(timeoutMs) {
                    mutex.withLock {
                        var completedNormally = false
                        try {
                            operation().also { completedNormally = true }
                        } finally {
                            // Invalidate before releasing the mutex. Otherwise a queued operation
                            // could reuse a half-failed sshj client in the small timeout race window.
                            if (!completedNormally && !currentCoroutineContext().isActive) {
                                withContext(NonCancellable) {
                                    try {
                                        onOperationTimeout()
                                    } catch (_: Exception) {
                                        // Preserve the original cancellation/timeout cause.
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // Do not convert parent/job cancellation (including a parent timeout) into an I/O error.
            currentCoroutineContext().ensureActive()
            throw IOException("SFTP 操作超时（${timeoutMs}ms，包含排队等待），请检查网络后重试", e)
        } catch (e: CancellationException) {
            throw e
        }
    }
}
