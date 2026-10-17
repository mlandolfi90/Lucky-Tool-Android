package com.danielealbano.androidremotecontrolmcp.policy.approval

import com.danielealbano.androidremotecontrolmcp.policy.engine.DecisionReason
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyTarget
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector

/** Which exception a pending entry may offer, and for which selector. */
object ExceptionEligibility {
    private val AMOUNT_REASONS = setOf(DecisionReason.AMOUNT_TIER, DecisionReason.AMOUNT_ABOVE_MAX)

    /** The target that belongs to the decided package; a target of another package is never used. */
    fun target(action: PendingAction): PolicyTarget? {
        val packageName = action.decision.packageName
        return action.subject.targets.firstOrNull { it.packageName == packageName }
    }

    /** The selector the exception would store, or null when [kind] is not offered for [action]. */
    fun selectorFor(
        action: PendingAction,
        kind: PolicyExceptionKind,
    ): PolicySelector? {
        val target = target(action) ?: return null
        val eligible =
            target.identifiable &&
                when (kind) {
                    PolicyExceptionKind.NOT_AMOUNT_FIELD -> {
                        action.decision.reason in AMOUNT_REASONS && !target.isPassword
                    }

                    PolicyExceptionKind.NOT_ACTION_BUTTON -> {
                        action.decision.reason == DecisionReason.UNKNOWN_TARGET
                    }
                }
        return if (eligible) target.selector else null
    }
}
