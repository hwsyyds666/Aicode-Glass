package com.aicode.feature.settings.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProviderLogoTest {
    @Test
    fun stepFunProviderAndModelNamesResolveToStepFunMark() {
        assertEquals("stepfun", modelBrandKey("StepFun"))
        assertEquals("stepfun", modelBrandKey("step-3.7-flash"))
        assertEquals("stepfun", modelBrandKey("阶跃星辰 Step 5"))
        assertNotNull(brandLogoRes("stepfun"))
    }

    @Test
    fun xiaomiMimoProviderAndModelNamesResolveToXiaomiMark() {
        assertEquals("xiaomi", modelBrandKey("Xiaomi MiMo"))
        assertEquals("xiaomi", modelBrandKey("mimo-v2-flash"))
        assertEquals("xiaomi", modelBrandKey("小米 MiMo"))
        assertNotNull(brandLogoRes("xiaomi"))
    }

    @Test
    fun unrelatedStepAndMimoSubstringsDoNotCaptureCommonNames() {
        assertEquals("other", modelBrandKey("step counter model"))
        assertEquals("other", modelBrandKey("minimal model"))
    }
}
