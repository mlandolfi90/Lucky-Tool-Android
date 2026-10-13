package com.danielealbano.androidremotecontrolmcp.policy.approval

import com.danielealbano.androidremotecontrolmcp.mcp.oauth.ApprovalState
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyDecision
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import kotlinx.coroutines.flow.StateFlow

/** In-memory ledger of ASK_IN_CHAT / NOTIFY approvals and their single-use grants. */
interface ActionApprovalCoordinator {
    /** PENDING entries in creation order (drives the notification and the approval screen). */
    fun observePending(): StateFlow<List<PendingAction>>

    /**
     * Joins the PENDING entry with the same fingerprint (upgrading it in place when [request] is stricter) or
     * creates one. Throws [TooManyPendingActionsException] when a new entry would exceed the cap and
     * [IllegalArgumentException] when the method is not ASK_IN_CHAT or NOTIFY.
     */
    suspend fun createOrJoin(
        request: PendingActionRequest,
        nowMs: Long,
    ): PendingAction

    /**
     * Suspends until [id] leaves PENDING or [timeoutMs] elapses; unknown id → EXPIRED. A PENDING result is not
     * expiry-checked: callers apply expiry via [stateOf] or [statusOf].
     */
    suspend fun awaitDecision(
        id: String,
        timeoutMs: Long,
    ): ApprovalState

    /** Owner approval on the device. Expiry wins; on APPROVED a device-strength grant is created. */
    suspend fun approve(
        id: String,
        nowMs: Long,
    ): ApprovalState

    /** `confirm_human_approval`: only ASK_IN_CHAT entries; creates a chat-strength grant. */
    suspend fun confirmInChat(
        id: String,
        nowMs: Long,
    ): ChatConfirmResult

    /** Owner denial; a non-PENDING or unknown id is left as it is. */
    suspend fun deny(id: String)

    /** Current state; a lapsed PENDING entry becomes EXPIRED; unknown id → EXPIRED. Same as `statusOf(...).state`. */
    suspend fun stateOf(
        id: String,
        nowMs: Long,
    ): ApprovalState

    /**
     * State and expiry read atomically (one critical section): a lapsed PENDING entry becomes EXPIRED; unknown id →
     * EXPIRED. The expiry is non-null exactly when the returned state is PENDING.
     */
    suspend fun statusOf(
        id: String,
        nowMs: Long,
    ): ApprovalStatusView

    /** Consumes the grant for [fingerprint] once, only if its strength satisfies [requiredMode]. */
    suspend fun consumeGrant(
        fingerprint: String,
        requiredMode: RuleMode,
        nowMs: Long,
    ): Boolean

    /**
     * Applies a re-evaluated [decision] to a PENDING entry: ASK_IN_CHAT/NOTIFY → entry updated in place
     * (STILL_PENDING); RUN → entry closed as EXPIRED without a grant (NO_LONGER_NEEDS_APPROVAL: the agent
     * repeats the call and is evaluated fresh); any other mode → DENIED (NOW_FORBIDDEN); not pending → NOT_PENDING.
     */
    suspend fun applyReevaluation(
        id: String,
        decision: PolicyDecision,
        nowMs: Long,
    ): ReevaluationOutcome

    /** Server stop: every PENDING entry becomes DENIED (waking awaiters) and every grant is dropped. */
    suspend fun clearAll()
}
