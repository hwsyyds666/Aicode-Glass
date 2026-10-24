package com.aicode.feature.settings.data.repository

import com.google.gson.JsonParser

internal data class OpenAIUsageWindow(
    val usedPercent: Int,
    val durationSeconds: Long?,
    val resetAtEpochSeconds: Long?
)

internal data class OpenAIUsageSnapshot(
    val planType: String?,
    val primary: OpenAIUsageWindow?,
    val secondary: OpenAIUsageWindow?
)

/** Parser for the first-party Codex client's observed WHAM usage response. */
internal object OpenAIUsageProtocol {
    fun parse(body: String, nowEpochSeconds: Long): OpenAIUsageSnapshot? = runCatching {
        val root = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val rateLimit = root.get("rate_limit")?.takeIf { it.isJsonObject }?.asJsonObject
        OpenAIUsageSnapshot(
            planType = root.get("plan_type")?.takeIf { it.isJsonPrimitive }?.asString
                ?.trim()?.takeIf(String::isNotEmpty),
            primary = parseWindow(rateLimit?.get("primary_window"), nowEpochSeconds),
            secondary = parseWindow(rateLimit?.get("secondary_window"), nowEpochSeconds)
        )
    }.getOrNull()

    private fun parseWindow(value: com.google.gson.JsonElement?, nowEpochSeconds: Long): OpenAIUsageWindow? {
        val window = value?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val used = window.get("used_percent")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asInt ?: return null
        val duration = window.get("limit_window_seconds")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong?.takeIf { it > 0L }
        val resetAt = window.get("reset_at")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong?.takeIf { it > 0L }
            ?: window.get("reset_after_seconds")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong
                ?.takeIf { it >= 0L }?.let { nowEpochSeconds + it }
        return OpenAIUsageWindow(used.coerceIn(0, 100), duration, resetAt)
    }
}
