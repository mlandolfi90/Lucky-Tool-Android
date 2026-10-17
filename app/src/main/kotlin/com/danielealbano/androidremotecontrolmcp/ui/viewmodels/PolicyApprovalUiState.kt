package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.policy.approval.ReevaluationOutcome
import com.danielealbano.androidremotecontrolmcp.policy.engine.DecisionReason
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode

/**
 * One approval card. [amount] and [fieldLabel] are device-derived and stay in memory for this screen only;
 * [toString] prints neither them nor the approval [id].
 */
data class PolicyApprovalCard(
    val id: String,
    val packageName: String?,
    val appName: String?,
    val actionType: PolicyActionType,
    val toolName: String,
    val method: RuleMode,
    val reason: DecisionReason,
    val amount: String?,
    val amountUnreadable: Boolean,
    val fieldLabel: String?,
    val expiresAtMs: Long,
    val canMarkNotAmount: Boolean,
    val canMarkNotActionButton: Boolean,
) {
    override fun toString(): String =
        "PolicyApprovalCard(packageName=$packageName, actionType=$actionType, " +
            "method=$method, reason=$reason)"
}

/** The exception the owner tapped, waiting for the second (dialog) confirmation. */
data class ExceptionConfirmation(
    val pendingId: String,
    val kind: PolicyExceptionKind,
    val appName: String?,
    val targetLabel: String?,
) {
    override fun toString(): String = "ExceptionConfirmation(kind=$kind)"
}

data class PolicyApprovalUiState(
    val cards: List<PolicyApprovalCard> = emptyList(),
    val confirmation: ExceptionConfirmation? = null,
    val outcome: ReevaluationOutcome? = null,
)
