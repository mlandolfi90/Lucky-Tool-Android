package com.danielealbano.androidremotecontrolmcp.ui.components

import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallMarker
import com.danielealbano.androidremotecontrolmcp.policy.engine.DecisionReason
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogOutcome
import com.danielealbano.androidremotecontrolmcp.policy.subject.BlockCause
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApprovalLogLabelsTest {
    private fun assertDistinctLabels(
        ids: List<Int>,
        expectedSize: Int,
    ) {
        assertTrue(ids.all { it != 0 })
        assertEquals(expectedSize, ids.distinct().size)
    }

    @Test
    fun `every outcome has a label`() {
        val ids = ApprovalLogOutcome.entries.map(ApprovalLogLabels::outcome)

        assertDistinctLabels(ids, ApprovalLogOutcome.entries.size)
    }

    @Test
    fun `every reason and cause has a distinct label`() {
        assertDistinctLabels(DecisionReason.entries.map(ApprovalLogLabels::reason), DecisionReason.entries.size)
        assertDistinctLabels(BlockCause.entries.map(ApprovalLogLabels::cause), BlockCause.entries.size)
    }

    @Test
    fun `toolCallMarker labels only the gate markers`() {
        val markers =
            listOf(
                ToolCallMarker.FORBIDDEN,
                ToolCallMarker.NEEDS_CONFIRMATION,
                ToolCallMarker.AWAITING_APPROVAL,
                ToolCallMarker.DENIED,
            ).map(ApprovalLogLabels::toolCallMarker)

        assertDistinctLabels(markers.map { checkNotNull(it) }, 4)
        listOf(ToolCallMarker.FAILED, "", "tap").forEach { assertNull(ApprovalLogLabels.toolCallMarker(it), it) }
    }
}
