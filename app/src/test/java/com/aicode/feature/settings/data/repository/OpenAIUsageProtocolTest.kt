package com.aicode.feature.settings.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OpenAIUsageProtocolTest {
    @Test
    fun parsesPlanAndBothWindowsWithoutConfusingUsedWithRemaining() {
        val parsed = OpenAIUsageProtocol.parse(
            """{"plan_type":"plus","rate_limit":{"primary_window":{"used_percent":25,"limit_window_seconds":18000,"reset_at":1900000000},"secondary_window":{"used_percent":60,"limit_window_seconds":604800,"reset_after_seconds":90}}}""",
            nowEpochSeconds = 1_800_000_000L
        )

        assertNotNull(parsed)
        assertEquals("plus", parsed?.planType)
        assertEquals(25, parsed?.primary?.usedPercent)
        assertEquals(18_000L, parsed?.primary?.durationSeconds)
        assertEquals(1_900_000_000L, parsed?.primary?.resetAtEpochSeconds)
        assertEquals(60, parsed?.secondary?.usedPercent)
        assertEquals(1_800_000_090L, parsed?.secondary?.resetAtEpochSeconds)
    }

    @Test
    fun clampsPercentAndTreatsMissingRateLimitAsNoSnapshot() {
        val parsed = OpenAIUsageProtocol.parse(
            """{"plan_type":"pro","rate_limit":{"primary_window":{"used_percent":120}}}""",
            nowEpochSeconds = 1
        )
        assertEquals(100, parsed?.primary?.usedPercent)
        assertEquals(null, parsed?.primary?.durationSeconds)
        assertNull(OpenAIUsageProtocol.parse("{}", nowEpochSeconds = 1)?.primary)
    }
}
