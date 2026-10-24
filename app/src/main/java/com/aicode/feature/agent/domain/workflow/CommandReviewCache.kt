package com.aicode.feature.agent.domain.workflow

import com.aicode.feature.settings.data.repository.CommandAuthLevel

/** Full identity of a review decision; changing any safety-relevant input invalidates the cached verdict. */
internal data class CommandReviewCacheKey(
    val sessionId: String,
    val requestId: String,
    val toolName: String,
    val normalizedCommand: String,
    val declaredIntent: String,
    val latestUserRequest: String,
    val workingDirectory: String,
    val authLevel: CommandAuthLevel,
    val environment: String,
    val reviewerProviderId: String,
    val reviewerModel: String,
    val contextFingerprint: String
)

/** Small thread-safe access-ordered cache for immutable review decisions. */
internal class CommandReviewCache<V>(private val maxEntries: Int) {
    init {
        require(maxEntries > 0)
    }

    private val entries = LinkedHashMap<CommandReviewCacheKey, V>(maxEntries, 0.75f, true)

    @Synchronized
    operator fun get(key: CommandReviewCacheKey): V? = entries[key]

    @Synchronized
    operator fun set(key: CommandReviewCacheKey, value: V) {
        entries[key] = value
        if (entries.size > maxEntries) {
            val eldest = entries.entries.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
    }

    @Synchronized
    fun clear() = entries.clear()
}
