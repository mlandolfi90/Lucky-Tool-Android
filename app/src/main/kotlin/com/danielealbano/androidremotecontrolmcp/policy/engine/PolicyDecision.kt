package com.danielealbano.androidremotecontrolmcp.policy.engine

import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode

/**
 * The strictest outcome for a [PolicySubject]. [packageName] is the deciding target's package (null when the
 * subject had no target); [actionType] is that target's classified type.
 */
data class PolicyDecision(
    val mode: RuleMode,
    val reason: DecisionReason,
    val packageName: String?,
    val actionType: PolicyActionType,
)
