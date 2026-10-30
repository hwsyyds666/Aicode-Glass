package com.aicode.feature.agent.domain.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandReviewProtocolTest {
    @Test
    fun parsesOnlyTheThreeExplicitVerdictsAndBoundsReason() {
        assertEquals(
            CommandReviewProtocol.Decision(CommandReviewProtocol.Verdict.ALLOW, "safe"),
            CommandReviewProtocol.parse("VERDICT: ALLOW\nREASON: safe")
        )
        assertEquals(
            CommandReviewProtocol.Verdict.DENY,
            CommandReviewProtocol.parse("VERDICT: DENY\nREASON: dangerous")?.verdict
        )
        assertEquals(
            CommandReviewProtocol.Verdict.UNSURE,
            CommandReviewProtocol.parse("VERDICT: UNSURE\nREASON: unknown target")?.verdict
        )
        assertEquals(600, CommandReviewProtocol.parse("VERDICT: UNSURE\nREASON: ${"x".repeat(900)}")?.reason?.length)
    }

    @Test
    fun malformedOrPromptPrefixedResponsesFailClosed() {
        assertNull(CommandReviewProtocol.parse("ALLOW"))
        assertNull(CommandReviewProtocol.parse("Preamble\nVERDICT: ALLOW\nREASON: safe"))
        assertNull(CommandReviewProtocol.parse("VERDICT: MAYBE\nREASON: safe"))
        assertNull(CommandReviewProtocol.parse("VERDICT: ALLOW\nREASON:"))
        assertNull(CommandReviewProtocol.parse("VERDICT: ALLOW\nREASON: safe\nextra"))
    }
}
