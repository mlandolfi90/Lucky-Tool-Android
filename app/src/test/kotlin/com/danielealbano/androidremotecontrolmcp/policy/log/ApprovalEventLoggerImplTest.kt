package com.danielealbano.androidremotecontrolmcp.policy.log

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.policy.engine.DecisionReason
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ApprovalEventLoggerImplTest {
    private val serverLog = RecordingServerLogRepository()
    private val logger = ApprovalEventLoggerImpl(serverLog)

    @Test
    fun `log writes one APPROVAL entry with the encoded message`() {
        val record =
            ApprovalLogRecord(
                outcome = ApprovalLogOutcome.FORBIDDEN,
                actionType = PolicyActionType.TYPE,
                mode = RuleMode.FORBIDDEN,
                reason = DecisionReason.RULE,
                packageName = "com.example.bank",
            )

        logger.log(record, toolName = "tap", durationMs = 12L)

        val entry = serverLog.entries.single()
        assertEquals(ServerLogEntry.Type.APPROVAL, entry.type)
        assertEquals(ApprovalLogMessage.encode(record), entry.message)
        assertEquals("tap", entry.toolName)
        assertEquals(12L, entry.durationMs)
    }

    @Test
    fun `owner and profile events carry no tool and no duration`() {
        logger.log(ApprovalLogRecord(ApprovalLogOutcome.EXCEPTION_REMOVED, packageName = "p"))

        val entry = serverLog.entries.single()
        assertNull(entry.toolName)
        assertNull(entry.durationMs)
    }
}
