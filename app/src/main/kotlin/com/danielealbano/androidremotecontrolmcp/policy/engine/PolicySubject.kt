package com.danielealbano.androidremotecontrolmcp.policy.engine

import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType

/**
 * What a tool call does, independent of MCP. [amountText] is the final text whose numbers count as amounts
 * (sensitive: never logged; [toString] omits it). When [classifyTarget] is true, [actionType] must be
 * ACTION_BUTTON and the engine may downgrade each target to NAVIGATE (see `TargetClassifier`).
 */
data class PolicySubject(
    val actionType: PolicyActionType,
    val targets: List<PolicyTarget>,
    val amountText: String?,
    val classifyTarget: Boolean = false,
) {
    override fun toString(): String =
        "PolicySubject(actionType=$actionType, targets=$targets, hasAmountText=${amountText != null}, " +
            "classifyTarget=$classifyTarget)"
}
