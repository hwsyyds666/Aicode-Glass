package com.aicode.feature.settings.presentation.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPresetSheetTest {
    @Test
    fun hidesTheInternalAiCodePresetCodeWithoutHidingOtherProtocolLabels() {
        assertFalse(shouldShowProviderProtocolCode("aicode"))
        assertFalse(shouldShowProviderProtocolCode("AICODE"))
        assertTrue(shouldShowProviderProtocolCode("openai"))
        assertTrue(shouldShowProviderProtocolCode("deepseek"))
    }
}
