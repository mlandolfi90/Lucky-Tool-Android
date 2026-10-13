package com.danielealbano.androidremotecontrolmcp.policy.gate

import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalState
import com.danielealbano.androidremotecontrolmcp.policy.approval.ApprovalScreenVisibility
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingAction
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionRequest
import com.danielealbano.androidremotecontrolmcp.policy.approval.TooManyPendingActionsException
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode

/**
 * Turns one [Assessment] into an [Authorization]: consumes a grant whose strength satisfies the mode, or creates /
 * joins the pending entry. For NOTIFY, [awaitOwner] waits for the owner's answer. Never locks, never executes a tool.
 */
internal class GateAuthorizer(
    private val coordinator: ActionApprovalCoordinator,
    private val outcomes: GateOutcomes,
    private val nowMs: () -> Long,
    private val screen: ApprovalScreenVisibility,
) {
    suspend fun authorize(
        call: GateCall,
        assessment: Assessment,
    ): Authorization =
        when (assessment) {
            Assessment.Unchecked -> Verdict.Proceed(RuleMode.RUN)
            is Assessment.Blocked -> Verdict.Halt(outcomes.blocked(call, assessment.resolution))
            is Assessment.Evaluated -> authorizeDecision(call, assessment)
        }

    /**
     * Waits up to [waitMs] (0: no wait) for the owner, then reads the entry once. APPROVED returns
     * [Verdict.Proceed] WITHOUT consuming the grant: the caller consumes it with [consumeOwnerGrant] right before
     * executing. After a wait, an approval is used only once the approval screen has closed: while it still covers
     * the app (the owner has more requests open), the call returns `awaiting_approval` and the grant stays.
     */
    suspend fun awaitOwner(
        call: GateCall,
        awaiting: Authorization.AwaitOwner,
        waitMs: Long,
    ): Verdict {
        val assessment = awaiting.assessment
        val decision = assessment.decision
        if (waitMs > 0L) {
            coordinator.awaitDecision(awaiting.pending.id, waitMs)
        }
        return when (coordinator.stateOf(awaiting.pending.id, nowMs())) {
            ActionApprovalState.APPROVED -> {
                if (waitMs > 0L && !screen.awaitHidden(SCREEN_CLOSE_WAIT_MS)) {
                    Verdict.Halt(outcomes.awaiting(call, decision, awaiting.pending))
                } else {
                    Verdict.Proceed(RuleMode.NOTIFY)
                }
            }

            ActionApprovalState.DENIED -> {
                Verdict.Halt(outcomes.denied(call, decision))
            }

            ActionApprovalState.EXPIRED -> {
                Verdict.Halt(outcomes.expired(call, decision))
            }

            ActionApprovalState.NOT_REQUIRED -> {
                Verdict.Halt(outcomes.notRequired(call, decision))
            }

            ActionApprovalState.ALREADY_USED -> {
                Verdict.Halt(outcomes.alreadyUsed(call, decision))
            }

            ActionApprovalState.PENDING -> {
                Verdict.Halt(outcomes.awaiting(call, decision, awaiting.pending))
            }
        }
    }

    /**
     * Consumes the owner's NOTIFY grant for [assessment]. When it is gone, an identical joined call used it first:
     * `already_used`, so the agent re-reads the screen instead of repeating an action that may be done.
     */
    suspend fun consumeOwnerGrant(
        call: GateCall,
        assessment: Assessment.Evaluated,
    ): Verdict =
        if (coordinator.consumeGrant(assessment.fingerprint, RuleMode.NOTIFY, nowMs())) {
            outcomes.approved(call, assessment.decision)
            Verdict.Proceed(RuleMode.NOTIFY)
        } else {
            Verdict.Halt(outcomes.alreadyUsed(call, assessment.decision))
        }

    private suspend fun authorizeDecision(
        call: GateCall,
        assessment: Assessment.Evaluated,
    ): Authorization {
        val decision = assessment.decision
        return when (decision.mode) {
            RuleMode.RUN -> {
                Verdict.Proceed(RuleMode.RUN)
            }

            RuleMode.FORBIDDEN -> {
                Verdict.Halt(outcomes.forbidden(call, decision))
            }

            RuleMode.ASK_IN_CHAT, RuleMode.NOTIFY -> {
                when {
                    coordinator.consumeGrant(assessment.fingerprint, decision.mode, nowMs()) -> {
                        outcomes.approved(call, decision)
                        Verdict.Proceed(decision.mode)
                    }

                    decision.mode == RuleMode.ASK_IN_CHAT -> {
                        requestConfirmation(call, assessment)
                    }

                    else -> {
                        requestOwnerApproval(call, assessment)
                    }
                }
            }
        }
    }

    private suspend fun requestConfirmation(
        call: GateCall,
        assessment: Assessment.Evaluated,
    ): Verdict {
        val decision = assessment.decision
        val pending = createPending(call, assessment, RuleMode.ASK_IN_CHAT)
        val result =
            when {
                pending == null -> {
                    outcomes.tooManyPending(call, decision)
                }

                // Joined a stricter NOTIFY entry for the same call: only the owner can approve it.
                pending.method == RuleMode.NOTIFY -> {
                    outcomes.awaiting(call, decision, pending)
                }

                else -> {
                    outcomes.needsConfirmation(call, decision, pending)
                }
            }
        return Verdict.Halt(result)
    }

    private suspend fun requestOwnerApproval(
        call: GateCall,
        assessment: Assessment.Evaluated,
    ): Authorization {
        val pending = createPending(call, assessment, RuleMode.NOTIFY)
        return if (pending == null) {
            Verdict.Halt(outcomes.tooManyPending(call, assessment.decision))
        } else {
            Authorization.AwaitOwner(assessment, pending)
        }
    }

    private suspend fun createPending(
        call: GateCall,
        assessment: Assessment.Evaluated,
        method: RuleMode,
    ): PendingAction? =
        try {
            coordinator.createOrJoin(
                PendingActionRequest(
                    toolName = call.toolName,
                    subject = assessment.subject,
                    decision = assessment.decision,
                    fingerprint = assessment.fingerprint,
                    method = method,
                ),
                nowMs(),
            )
        } catch (_: TooManyPendingActionsException) {
            null
        }

    private companion object {
        /** How long an approved call waits for the approval screen to close before its re-check. */
        const val SCREEN_CLOSE_WAIT_MS = 3_000L
    }
}
