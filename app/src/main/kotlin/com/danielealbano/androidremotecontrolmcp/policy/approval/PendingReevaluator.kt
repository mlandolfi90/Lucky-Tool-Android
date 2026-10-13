package com.danielealbano.androidremotecontrolmcp.policy.approval

import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind

/**
 * Adds an owner-confirmed exception for the target of a pending entry, then re-runs the engine on the entry's
 * stored subject and applies the result with [ActionApprovalCoordinator.applyReevaluation]. It never approves.
 * Returns [ReevaluationOutcome.NOT_PENDING] when [id] is not pending and [ReevaluationOutcome.NOT_ELIGIBLE] when
 * [ExceptionEligibility.selectorFor] is null for [kind].
 */
interface PendingReevaluator {
    suspend fun addExceptionAndReevaluate(
        id: String,
        kind: PolicyExceptionKind,
    ): ReevaluationOutcome
}
