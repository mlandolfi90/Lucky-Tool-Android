package com.danielealbano.androidremotecontrolmcp.policy.gate

import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallMarker
import com.danielealbano.androidremotecontrolmcp.mcp.tools.markToolCall
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingAction
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyDecision
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalEventLogger
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogOutcome
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogRecord
import com.danielealbano.androidremotecontrolmcp.policy.log.toLogRecord
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectResolution
import com.danielealbano.androidremotecontrolmcp.policy.wireCode
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult

/** Every non-RUN gate outcome: one APPROVAL log entry, the TOOL_CALL marker, and the agent-facing result. */
internal class GateOutcomes(
    private val events: ApprovalEventLogger,
    private val nowMs: () -> Long,
) {
    suspend fun forbidden(
        call: GateCall,
        decision: PolicyDecision,
    ): CallToolResult {
        record(call, decision.toLogRecord(ApprovalLogOutcome.FORBIDDEN))
        markToolCall(ToolCallMarker.FORBIDDEN)
        return GateResults.forbidden(decision.packageName, decision.actionType, decision.reason.wireCode())
    }

    suspend fun blocked(
        call: GateCall,
        blocked: SubjectResolution.Blocked,
    ): CallToolResult {
        record(call, blocked.toLogRecord())
        markToolCall(ToolCallMarker.FORBIDDEN)
        return GateResults.forbidden(blocked.packageName, blocked.actionType, blocked.cause.code)
    }

    suspend fun needsConfirmation(
        call: GateCall,
        decision: PolicyDecision,
        pending: PendingAction,
    ): CallToolResult {
        record(call, decision.toLogRecord(ApprovalLogOutcome.NEEDS_CONFIRMATION))
        markToolCall(ToolCallMarker.NEEDS_CONFIRMATION)
        return GateResults.needsConfirmation(decision, pending)
    }

    suspend fun awaiting(
        call: GateCall,
        decision: PolicyDecision,
        pending: PendingAction,
    ): CallToolResult {
        record(call, decision.toLogRecord(ApprovalLogOutcome.AWAITING_APPROVAL))
        markToolCall(ToolCallMarker.AWAITING_APPROVAL)
        return GateResults.awaiting(decision, pending)
    }

    suspend fun denied(
        call: GateCall,
        decision: PolicyDecision,
    ): CallToolResult {
        record(call, decision.toLogRecord(ApprovalLogOutcome.DENIED))
        markToolCall(ToolCallMarker.DENIED)
        return GateResults.denied()
    }

    fun expired(
        call: GateCall,
        decision: PolicyDecision,
    ): CallToolResult {
        record(call, decision.toLogRecord(ApprovalLogOutcome.EXPIRED))
        return GateResults.expired()
    }

    fun notRequired(
        call: GateCall,
        decision: PolicyDecision,
    ): CallToolResult {
        record(call, decision.toLogRecord(ApprovalLogOutcome.NOT_REQUIRED))
        return GateResults.notRequired()
    }

    fun alreadyUsed(
        call: GateCall,
        decision: PolicyDecision,
    ): CallToolResult {
        record(call, decision.toLogRecord(ApprovalLogOutcome.ALREADY_USED))
        return GateResults.alreadyUsed()
    }

    fun tooManyPending(
        call: GateCall,
        decision: PolicyDecision,
    ): CallToolResult {
        record(call, decision.toLogRecord(ApprovalLogOutcome.TOO_MANY_PENDING))
        return GateResults.tooManyPending()
    }

    fun approved(
        call: GateCall,
        decision: PolicyDecision,
    ) {
        record(call, decision.toLogRecord(ApprovalLogOutcome.APPROVED))
    }

    private fun record(
        call: GateCall,
        logRecord: ApprovalLogRecord,
    ) {
        events.log(logRecord, call.toolName, (nowMs() - call.startMs).coerceAtLeast(0L))
    }
}
