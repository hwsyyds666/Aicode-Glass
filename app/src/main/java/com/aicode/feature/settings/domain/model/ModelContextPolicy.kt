package com.aicode.feature.settings.domain.model

object ModelContextPolicy {
    /** Used only when the settings UI seeds a new override; request budgeting never fabricates this value. */
    const val DEFAULT_CONTEXT_TOKENS = 128_000
    /** Unknown provider limits are left to the provider's own overflow response. */
    const val UNKNOWN_INPUT_BUDGET = Int.MAX_VALUE
    const val MIN_PRESERVE_RECENT_TOKENS = 2_000
    const val MAX_PRESERVE_RECENT_TOKENS = 20_000
    const val CHARS_PER_TOKEN = 4

    fun preserveRecentTokens(usableTokens: Int): Int =
        (usableTokens / 4).coerceIn(MIN_PRESERVE_RECENT_TOKENS, MAX_PRESERVE_RECENT_TOKENS)

    fun estimateTokens(chars: Int): Int =
        chars / CHARS_PER_TOKEN + if (chars % CHARS_PER_TOKEN == 0) 0 else 1

    fun estimateTextTokens(text: String): Int {
        var ascii = 0
        var other = 0
        text.forEach { if (it.code < 128) ascii++ else other++ }
        return estimateTokens(ascii) + other
    }

    fun outputReserveTokens(metadata: ModelMetadata): Int {
        val context = metadata.contextTokens.takeIf { it > 0 }
            ?: metadata.outputTokens?.takeIf { it > 0 }
            ?: 8_192
        val reserve = (context / 10).coerceIn(1_024, 8_192).coerceAtMost(context / 4)
        return metadata.outputTokens?.takeIf { it > 0 }?.coerceAtMost(reserve) ?: reserve
    }

    fun effectiveInputBudget(metadata: ModelMetadata): Int {
        val input = metadata.inputTokens?.takeIf { it > 0 }
        val context = metadata.contextTokens.takeIf { it > 0 }
        if (context == null && input == null) return UNKNOWN_INPUT_BUDGET
        if (context == null) return input ?: UNKNOWN_INPUT_BUDGET
        val shared = (context - outputReserveTokens(metadata)).coerceAtLeast(1)
        return input?.coerceAtMost(shared) ?: shared
    }
}
