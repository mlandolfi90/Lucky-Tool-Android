package com.danielealbano.androidremotecontrolmcp.policy.approval

import com.danielealbano.androidremotecontrolmcp.mcp.oauth.ApprovalState
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyDecision
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicySubject
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode

/**
 * What the gate asks the coordinator to hold. [subject] carries the amount text and node labels: memory only,
 * never logged ([toString] prints neither the subject nor the [fingerprint]).
 */
data class PendingActionRequest(
    val toolName: String,
    val subject: PolicySubject,
    val decision: PolicyDecision,
    val fingerprint: String,
    val method: RuleMode,
) {
    override fun toString(): String =
        "PendingActionRequest(toolName=$toolName, packageName=${decision.packageName}, " +
            "actionType=${decision.actionType}, method=$method)"
}

/**
 * A pending approval as the owner UI and the gate see it. [id] is the 32-hex approval nonce; [subject] keeps the
 * amount text and field label in memory only, for the approval screen and for re-evaluation. [toString] prints
 * neither the id nor the subject.
 */
data class PendingAction(
    val id: String,
    val toolName: String,
    val subject: PolicySubject,
    val decision: PolicyDecision,
    val method: RuleMode,
    val createdAtMs: Long,
    val expiresAtMs: Long,
) {
    override fun toString(): String =
        "PendingAction(toolName=$toolName, packageName=${decision.packageName}, " +
            "actionType=${decision.actionType}, method=$method, expiresAtMs=$expiresAtMs)"
}

/**
 * State and expiry of one entry, read in a single critical section by [ActionApprovalCoordinator.statusOf].
 * [expiresAtMs] is non-null exactly when [state] is PENDING.
 */
data class ApprovalStatusView(
    val state: ApprovalState,
    val expiresAtMs: Long?,
)

/** Result of `confirm_human_approval` on an entry. */
enum class ChatConfirmResult { CONFIRMED, ALREADY_APPROVED, NOT_CONFIRMABLE, DENIED, EXPIRED }

/** Result of re-evaluating a pending entry after the owner added an exception. Never an approval. */
enum class ReevaluationOutcome { STILL_PENDING, NO_LONGER_NEEDS_APPROVAL, NOW_FORBIDDEN, NOT_PENDING, NOT_ELIGIBLE }

/** Thrown by [ActionApprovalCoordinator.createOrJoin] when [ActionApprovalPolicy.MAX_PENDING] entries are pending. */
class TooManyPendingActionsException : IllegalStateException(ActionApprovalPolicy.TOO_MANY_PENDING_CODE)
