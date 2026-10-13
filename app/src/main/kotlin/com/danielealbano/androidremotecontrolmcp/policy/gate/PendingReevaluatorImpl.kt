package com.danielealbano.androidremotecontrolmcp.policy.gate

import com.danielealbano.androidremotecontrolmcp.data.repository.ApprovalProfileRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileSaveResult
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileStoreUnreadableException
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.policy.approval.ExceptionEligibility
import com.danielealbano.androidremotecontrolmcp.policy.approval.ExceptionReevaluation
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingAction
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingReevaluator
import com.danielealbano.androidremotecontrolmcp.policy.approval.ReevaluationOutcome
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyEngine
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalEventLogger
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogOutcome
import com.danielealbano.androidremotecontrolmcp.policy.log.toLogRecord
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores an owner-confirmed exception for the decided target of a pending entry, then re-runs the engine on the
 * entry's stored subject and hands the result to [ActionApprovalCoordinator.applyReevaluation]. Never approves.
 * A write the repository refuses (invalid, limit reached, profile gone, store unavailable) reads as NOT_ELIGIBLE;
 * so does a stored exception whose profiles cannot be read back (then with `exceptionStored` true).
 */
@Singleton
class PendingReevaluatorImpl internal constructor(
    private val coordinator: ActionApprovalCoordinator,
    private val engine: PolicyEngine,
    private val profileRepository: ApprovalProfileRepository,
    private val events: ApprovalEventLogger,
    private val nowMs: () -> Long,
) : PendingReevaluator {
    @Inject
    constructor(
        coordinator: ActionApprovalCoordinator,
        engine: PolicyEngine,
        profileRepository: ApprovalProfileRepository,
        events: ApprovalEventLogger,
    ) : this(coordinator, engine, profileRepository, events, { System.currentTimeMillis() })

    override suspend fun addExceptionAndReevaluate(
        id: String,
        kind: PolicyExceptionKind,
    ): ExceptionReevaluation {
        val action = coordinator.observePending().value.firstOrNull { it.id == id }
        val packageName = action?.decision?.packageName
        val selector = action?.let { ExceptionEligibility.selectorFor(it, kind) }
        return when {
            action == null -> NOT_STORED_NOT_PENDING
            packageName == null || selector == null -> NOT_STORED_NOT_ELIGIBLE
            store(packageName, kind, selector) -> reevaluate(action)
            else -> NOT_STORED_NOT_ELIGIBLE
        }
    }

    private suspend fun store(
        packageName: String,
        kind: PolicyExceptionKind,
        selector: PolicySelector,
    ): Boolean = profileRepository.addException(packageName, kind, selector) is ProfileSaveResult.Saved

    /** The exception is stored: whatever happens next, [ExceptionReevaluation.exceptionStored] is true. */
    private suspend fun reevaluate(action: PendingAction): ExceptionReevaluation {
        val snapshot =
            try {
                profileRepository.current()
            } catch (_: ProfileStoreUnreadableException) {
                null
            }
        if (snapshot == null) {
            return STORED_NOT_REEVALUATED
        }
        val decision = engine.evaluate(action.subject, snapshot)
        val outcome = coordinator.applyReevaluation(action.id, decision, nowMs())
        events.log(decision.toLogRecord(ApprovalLogOutcome.REEVALUATED), action.toolName)
        return ExceptionReevaluation(exceptionStored = true, outcome = outcome)
    }

    private companion object {
        val NOT_STORED_NOT_PENDING = ExceptionReevaluation(false, ReevaluationOutcome.NOT_PENDING)
        val NOT_STORED_NOT_ELIGIBLE = ExceptionReevaluation(false, ReevaluationOutcome.NOT_ELIGIBLE)
        val STORED_NOT_REEVALUATED = ExceptionReevaluation(true, ReevaluationOutcome.NOT_ELIGIBLE)
    }
}
