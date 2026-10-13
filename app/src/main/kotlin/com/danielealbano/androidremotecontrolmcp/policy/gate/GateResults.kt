package com.danielealbano.androidremotecontrolmcp.policy.gate

import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingAction
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyDecision
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.wireCode
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Agent-facing results of a halted call. None carries an amount, field text, label or selector value. */
internal object GateResults {
    const val STATUS_FORBIDDEN = "forbidden"
    const val STATUS_NEEDS_CONFIRMATION = "needs_human_confirmation"
    const val STATUS_AWAITING = "awaiting_approval"
    const val STATUS_DENIED = "denied"
    const val STATUS_EXPIRED = "expired"
    const val STATUS_TOO_MANY_PENDING = "too_many_pending"
    const val NEXT_CONFIRM =
        "ask the human in the conversation; if they agree call confirm_human_approval then repeat this exact call"
    const val NEXT_POLL = "poll get_approval_status, then repeat this exact call"

    private const val KEY_STATUS = "status"
    private const val KEY_APP = "app"
    private const val KEY_ACTION = "action"
    private const val KEY_REASON = "reason"
    private const val KEY_APPROVAL_ID = "approval_id"
    private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
    private const val KEY_NEXT = "next"

    /** [reasonCode] is a `DecisionReason` wire code or a `BlockCause.code`. */
    fun forbidden(
        packageName: String?,
        actionType: PolicyActionType,
        reasonCode: String,
    ): CallToolResult =
        McpToolUtils.untrustedErrorResult(
            buildJsonObject {
                put(KEY_STATUS, STATUS_FORBIDDEN)
                put(KEY_APP, packageName)
                put(KEY_ACTION, actionType.wireCode())
                put(KEY_REASON, reasonCode)
            }.toString(),
        )

    fun needsConfirmation(
        decision: PolicyDecision,
        pending: PendingAction,
    ): CallToolResult = pendingResult(STATUS_NEEDS_CONFIRMATION, decision, pending, NEXT_CONFIRM)

    fun awaiting(
        decision: PolicyDecision,
        pending: PendingAction,
    ): CallToolResult = pendingResult(STATUS_AWAITING, decision, pending, NEXT_POLL)

    fun denied(): CallToolResult = statusOnlyError(STATUS_DENIED)

    fun expired(): CallToolResult = statusOnlyError(STATUS_EXPIRED)

    fun tooManyPending(): CallToolResult = statusOnlyError(STATUS_TOO_MANY_PENDING)

    private fun pendingResult(
        status: String,
        decision: PolicyDecision,
        pending: PendingAction,
        next: String,
    ): CallToolResult =
        McpToolUtils.untrustedTextResult(
            buildJsonObject {
                put(KEY_STATUS, status)
                put(KEY_APPROVAL_ID, pending.id)
                put(KEY_APP, decision.packageName)
                put(KEY_ACTION, decision.actionType.wireCode())
                put(KEY_EXPIRES_AT_MS, pending.expiresAtMs)
                put(KEY_NEXT, next)
            }.toString(),
        )

    private fun statusOnlyError(status: String): CallToolResult =
        McpToolUtils.errorTextResult(buildJsonObject { put(KEY_STATUS, status) }.toString())
}
