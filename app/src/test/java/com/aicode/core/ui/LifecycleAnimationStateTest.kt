package com.aicode.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LifecycleAnimationStateTest {
    @Test
    fun resumedAnimationPicksUpAtItsOriginalCyclePhase() {
        assertEquals(325, animationStartOffsetMillis(1_000L, 1_325L, 900))
        assertEquals(25, animationStartOffsetMillis(1_000L, 1_925L, 900))
    }

    @Test
    fun invalidPeriodOrEarlierClockStartsAtZero() {
        assertEquals(0, animationStartOffsetMillis(1_000L, 2_000L, 0))
        assertEquals(0, animationStartOffsetMillis(1_000L, 2_000L, -1))
        assertEquals(0, animationStartOffsetMillis(2_000L, 1_000L, 900))
    }
}
