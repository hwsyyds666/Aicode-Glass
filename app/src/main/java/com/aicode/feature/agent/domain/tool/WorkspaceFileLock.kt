package com.aicode.feature.agent.domain.tool

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Serializes file operations across parent/child sessions without retaining idle path locks. */
@Singleton
class WorkspaceFileLock @Inject constructor() {
    private data class Entry(val mutex: Mutex = Mutex(), var references: Int = 0)

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun <T> withLock(path: String, block: suspend () -> T): T =
        withLocks(listOf(path), block)

    suspend fun <T> withLocks(paths: Collection<String>, block: suspend () -> T): T {
        val keys = paths.asSequence().map(String::trim).filter(String::isNotEmpty).distinct().sorted().toList()
        if (keys.isEmpty()) return block()

        val acquired = ArrayList<Pair<String, Entry>>(keys.size)
        try {
            for (key in keys) {
                val entry = entries.compute(key) { _, previous ->
                    (previous ?: Entry()).also { it.references++ }
                }!!
                try {
                    entry.mutex.lock()
                    acquired += key to entry
                } catch (cancelled: Throwable) {
                    release(key, entry)
                    throw cancelled
                }
            }
            return block()
        } finally {
            acquired.asReversed().forEach { (key, entry) ->
                entry.mutex.unlock()
                release(key, entry)
            }
        }
    }

    private fun release(key: String, entry: Entry) {
        entries.computeIfPresent(key) { _, current ->
            if (current !== entry) current
            else {
                current.references--
                current.takeIf { it.references > 0 }
            }
        }
    }
}
