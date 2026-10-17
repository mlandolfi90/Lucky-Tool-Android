package com.danielealbano.androidremotecontrolmcp.policy.log

import com.danielealbano.androidremotecontrolmcp.policy.engine.DecisionReason
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyDecision
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.policy.subject.BlockCause
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectResolution
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ApprovalLogMessageTest {
    @Test
    fun `encode puts the outcome first and app last`() {
        val record =
            ApprovalLogRecord(
                outcome = ApprovalLogOutcome.FORBIDDEN,
                actionType = PolicyActionType.TYPE,
                mode = RuleMode.FORBIDDEN,
                reason = DecisionReason.AMOUNT_ABOVE_MAX,
                packageName = "com.example.bank",
            )

        assertEquals(
            "forbidden action=type mode=forbidden reason=amount_above_max app=com.example.bank",
            ApprovalLogMessage.encode(record),
        )
    }

    @Test
    fun `encode omits absent fields`() {
        val record = ApprovalLogRecord(ApprovalLogOutcome.PROFILE_CREATED, packageName = "p")

        assertEquals("profile_created app=p", ApprovalLogMessage.encode(record))
    }

    @Test
    fun `blocked record encodes the cause code as reason`() {
        val blocked =
            SubjectResolution.Blocked("p", PolicyActionType.ACTION_BUTTON, BlockCause.IME_INPUT_IN_PROFILED_APP)

        assertEquals(
            "forbidden action=action_button mode=forbidden reason=ime_input_in_profiled_app app=p",
            ApprovalLogMessage.encode(blocked.toLogRecord()),
        )
    }

    @Test
    fun `decision record carries action mode reason and app`() {
        val decision = PolicyDecision(RuleMode.NOTIFY, DecisionReason.RULE, "p", PolicyActionType.ACTION_BUTTON)

        assertEquals(
            "owner_approved action=action_button mode=notify reason=rule app=p",
            ApprovalLogMessage.encode(decision.toLogRecord(ApprovalLogOutcome.OWNER_APPROVED)),
        )
    }

    @Test
    fun `decode round-trips every outcome, action, mode, reason and cause`() {
        val base = ApprovalLogRecord(ApprovalLogOutcome.APPROVED, packageName = "com.example.bank")
        val records =
            ApprovalLogOutcome.entries.map { base.copy(outcome = it) } +
                PolicyActionType.entries.map { base.copy(actionType = it) } +
                RuleMode.entries.map { base.copy(mode = it) } +
                DecisionReason.entries.map { base.copy(reason = it) } +
                BlockCause.entries.map { base.copy(blockCause = it) } +
                base.copy(packageName = null)

        records.forEach { assertEquals(it, ApprovalLogMessage.decode(ApprovalLogMessage.encode(it))) }
    }

    @Test
    fun `decode returns null for messages that are not approval codes`() {
        listOf("", "Server started on 0.0.0.0:8080", "failed").forEach {
            assertNull(ApprovalLogMessage.decode(it), it)
        }
    }

    @Test
    fun `decode ignores unknown keys and unknown codes`() {
        val record = ApprovalLogMessage.decode("denied foo=bar mode=zzz app=p")

        assertEquals(ApprovalLogOutcome.DENIED, record?.outcome)
        assertNull(record?.mode)
        assertEquals("p", record?.packageName)
    }
}
