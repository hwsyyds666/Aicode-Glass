package com.aicode.feature.settings.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextWindowOverridePolicyTest {
    @Test
    fun acceptsValidTotalAndOptionalInputBudgets() {
        assertTrue(ContextWindowOverridePolicy.isValid(128_000, null, 8_192))
        assertTrue(ContextWindowOverridePolicy.isValid(128_000, 120_000, 8_000))
    }

    @Test
    fun rejectsMissingOrOutOfBoundsValues() {
        assertFalse(ContextWindowOverridePolicy.isValid(128_000, null, null))
        assertFalse(ContextWindowOverridePolicy.isValid(0, null, 1))
        assertFalse(ContextWindowOverridePolicy.isValid(10_000_001, null, 1))
        assertFalse(ContextWindowOverridePolicy.isValid(100, 90, 11))
        assertFalse(ContextWindowOverridePolicy.isValid(100, 101, 1))
    }
}
