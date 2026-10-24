package com.aicode.feature.settings.data.repository

/** Enforces one locally stored ChatGPT plan-usage profile and same-profile reauthorization. */
internal object OpenAIAccountSelectionPolicy {
    fun canConnect(existingAccountIds: Set<String>, reauthorizeAccountId: String?): Boolean =
        when {
            reauthorizeAccountId == null -> existingAccountIds.isEmpty()
            reauthorizeAccountId !in existingAccountIds -> false
            else -> existingAccountIds.all { it == reauthorizeAccountId }
        }

    fun canRoute(existingAccountIds: Set<String>, requestedAccountId: String): Boolean =
        existingAccountIds.size == 1 && existingAccountIds.single() == requestedAccountId
}
