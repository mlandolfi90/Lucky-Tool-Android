package com.danielealbano.androidremotecontrolmcp.policy.approval

import com.danielealbano.androidremotecontrolmcp.policy.engine.DecisionReason
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyApprovalLabelsTest {
    private fun assertDistinct(
        ids: List<Int>,
        size: Int,
    ) {
        assertTrue(ids.all { it != 0 })
        assertEquals(size, ids.distinct().size)
    }

    @Test
    fun `every action type has a distinct label`() {
        assertDistinct(PolicyActionType.entries.map(PolicyApprovalLabels::actionType), PolicyActionType.entries.size)
    }

    @Test
    fun `every rule mode has a distinct label`() {
        assertDistinct(RuleMode.entries.map(PolicyApprovalLabels::ruleMode), RuleMode.entries.size)
    }

    @Test
    fun `every decision reason has a distinct label`() {
        assertDistinct(DecisionReason.entries.map(PolicyApprovalLabels::reason), DecisionReason.entries.size)
    }
}
