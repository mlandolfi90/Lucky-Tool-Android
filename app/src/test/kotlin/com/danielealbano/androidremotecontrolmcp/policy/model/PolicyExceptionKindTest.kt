package com.danielealbano.androidremotecontrolmcp.policy.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PolicyExceptionKindTest {
    @Test
    fun `exception kinds are exactly the two profile lists`() {
        assertEquals(
            listOf(PolicyExceptionKind.NOT_AMOUNT_FIELD, PolicyExceptionKind.NOT_ACTION_BUTTON),
            PolicyExceptionKind.entries.toList(),
        )
    }
}
