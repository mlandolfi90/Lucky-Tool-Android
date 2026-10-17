package com.danielealbano.androidremotecontrolmcp.policy.approval

import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures
import com.danielealbano.androidremotecontrolmcp.policy.engine.DecisionReason
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyDecision
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicySubject
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyTarget
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode

/** Builders for approval-ledger tests; everything targets [PACKAGE] unless a test copies a target. */
object PendingActionFixtures {
    const val PACKAGE = PolicyFixtures.BANK_PACKAGE
    const val TOOL = "click_node"
    val FIELD_SELECTOR: PolicySelector = PolicyFixtures.resourceId("com.example.bank:id/amount")

    fun aTarget(
        selector: PolicySelector? = FIELD_SELECTOR,
        label: String? = "Amount",
        identifiable: Boolean = true,
        isPassword: Boolean = false,
    ): PolicyTarget = PolicyFixtures.target(PACKAGE, label, selector, isPassword, identifiable)

    fun aDecision(
        mode: RuleMode = RuleMode.NOTIFY,
        reason: DecisionReason = DecisionReason.RULE,
        actionType: PolicyActionType = PolicyActionType.ACTION_BUTTON,
    ): PolicyDecision = PolicyDecision(mode, reason, PACKAGE, actionType)

    fun aSubject(
        decision: PolicyDecision = aDecision(),
        targets: List<PolicyTarget> = listOf(aTarget()),
        amountText: String? = null,
    ): PolicySubject = PolicyFixtures.subject(decision.actionType, targets, amountText)

    fun aRequest(
        fingerprint: String = "fp-1",
        method: RuleMode = RuleMode.NOTIFY,
        decision: PolicyDecision = aDecision(mode = method),
        subject: PolicySubject = aSubject(decision),
    ): PendingActionRequest = PendingActionRequest(TOOL, subject, decision, fingerprint, method)

    fun aPendingAction(
        id: String = "id-1",
        decision: PolicyDecision = aDecision(),
        subject: PolicySubject = aSubject(decision),
        expiresAtMs: Long = ActionApprovalPolicy.APPROVAL_WINDOW_MS,
    ): PendingAction = PendingAction(id, TOOL, subject, decision, decision.mode, 0L, expiresAtMs)
}
