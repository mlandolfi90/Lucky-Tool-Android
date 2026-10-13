package com.danielealbano.androidremotecontrolmcp.policy.approval

import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind

/**
 * Result of [PendingReevaluator.addExceptionAndReevaluate]. [exceptionStored] is true when the profile holds the
 * exception after the call, even if the entry was no longer pending when it was re-evaluated ([outcome] NOT_PENDING)
 * or the store could not be read back to re-evaluate it ([outcome] NOT_ELIGIBLE, entry left as it was).
 * When it is false nothing was written and [outcome] is NOT_PENDING or NOT_ELIGIBLE.
 */
data class ExceptionReevaluation(
    val exceptionStored: Boolean,
    val outcome: ReevaluationOutcome,
)

/**
 * Adds an owner-confirmed exception for the target of a pending entry, then re-runs the engine on the entry's
 * stored subject and applies the result with [ActionApprovalCoordinator.applyReevaluation]. It never approves.
 * Returns NOT_PENDING when [id] is not pending and NOT_ELIGIBLE when [ExceptionEligibility.selectorFor] is null for
 * [kind] or the repository refused the write, both with [ExceptionReevaluation.exceptionStored] false; NOT_ELIGIBLE
 * with it true when the write succeeded but the profiles could not be read back. It never throws for an unreadable
 * store.
 */
interface PendingReevaluator {
    suspend fun addExceptionAndReevaluate(
        id: String,
        kind: PolicyExceptionKind,
    ): ExceptionReevaluation
}
