package com.danielealbano.androidremotecontrolmcp.policy.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuleModeTest {
    @Test
    fun `severity is strictly ascending in declaration order`() {
        val severities = RuleMode.entries.map { it.severity }

        assertTrue(severities.zipWithNext().all { (lower, upper) -> lower < upper })
        assertEquals(
            listOf(RuleMode.RUN, RuleMode.ASK_IN_CHAT, RuleMode.NOTIFY, RuleMode.FORBIDDEN),
            RuleMode.entries.toList(),
        )
    }

    @Test
    fun `isStricterThan compares severity`() {
        assertTrue(RuleMode.FORBIDDEN.isStricterThan(RuleMode.NOTIFY))
        assertFalse(RuleMode.RUN.isStricterThan(RuleMode.RUN))
        assertFalse(RuleMode.ASK_IN_CHAT.isStricterThan(RuleMode.NOTIFY))
    }
}
