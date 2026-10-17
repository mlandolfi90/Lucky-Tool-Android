package com.danielealbano.androidremotecontrolmcp.policy.approval

import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.FIELD_SELECTOR
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aDecision
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aPendingAction
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aSubject
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aTarget
import com.danielealbano.androidremotecontrolmcp.policy.engine.DecisionReason
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyTarget
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ExceptionEligibilityTest {
    private fun action(
        reason: DecisionReason,
        targets: List<PolicyTarget> = listOf(aTarget()),
    ): PendingAction {
        val decision = aDecision(reason = reason)
        return aPendingAction(decision = decision, subject = aSubject(decision, targets))
    }

    private fun selectorFor(
        reason: DecisionReason,
        kind: PolicyExceptionKind,
        target: PolicyTarget = aTarget(),
    ): PolicySelector? = ExceptionEligibility.selectorFor(action(reason, listOf(target)), kind)

    @Test
    fun `not an amount offered for amount tier and above max with selector`() {
        listOf(DecisionReason.AMOUNT_TIER, DecisionReason.AMOUNT_ABOVE_MAX).forEach { reason ->
            assertEquals(FIELD_SELECTOR, selectorFor(reason, PolicyExceptionKind.NOT_AMOUNT_FIELD))
        }
    }

    @Test
    fun `not an amount refused for password fields`() {
        val password = aTarget(isPassword = true)

        assertNull(selectorFor(DecisionReason.AMOUNT_TIER, PolicyExceptionKind.NOT_AMOUNT_FIELD, password))
    }

    @Test
    fun `not an amount refused without selector or when not identifiable`() {
        val kind = PolicyExceptionKind.NOT_AMOUNT_FIELD

        assertNull(selectorFor(DecisionReason.AMOUNT_TIER, kind, aTarget(selector = null)))
        assertNull(selectorFor(DecisionReason.AMOUNT_TIER, kind, aTarget(identifiable = false)))
    }

    @Test
    fun `not an amount refused for other reasons`() {
        listOf(DecisionReason.RULE, DecisionReason.ACTION_KEYWORD, DecisionReason.UNKNOWN_TARGET).forEach { reason ->
            assertNull(selectorFor(reason, PolicyExceptionKind.NOT_AMOUNT_FIELD), reason.name)
        }
    }

    @Test
    fun `not an action button offered only for unknown target`() {
        val kind = PolicyExceptionKind.NOT_ACTION_BUTTON

        assertEquals(FIELD_SELECTOR, selectorFor(DecisionReason.UNKNOWN_TARGET, kind))
        listOf(DecisionReason.ACTION_KEYWORD, DecisionReason.RULE, DecisionReason.AMOUNT_TIER).forEach { reason ->
            assertNull(selectorFor(reason, kind), reason.name)
        }
    }

    @Test
    fun `not an action button refused without selector or when not identifiable`() {
        val kind = PolicyExceptionKind.NOT_ACTION_BUTTON

        assertNull(selectorFor(DecisionReason.UNKNOWN_TARGET, kind, aTarget(selector = null)))
        assertNull(selectorFor(DecisionReason.UNKNOWN_TARGET, kind, aTarget(identifiable = false)))
    }

    @Test
    fun `target of another package is never used`() {
        val other = aTarget().copy(packageName = "other")

        assertNull(ExceptionEligibility.target(action(DecisionReason.UNKNOWN_TARGET, listOf(other))))
        assertNull(selectorFor(DecisionReason.AMOUNT_TIER, PolicyExceptionKind.NOT_AMOUNT_FIELD, other))
        assertNull(selectorFor(DecisionReason.UNKNOWN_TARGET, PolicyExceptionKind.NOT_ACTION_BUTTON, other))
    }

    @Test
    fun `target picks the decided package among several`() {
        val other = aTarget().copy(packageName = "other")
        val own = aTarget()

        val target = ExceptionEligibility.target(action(DecisionReason.UNKNOWN_TARGET, listOf(other, own)))

        assertEquals(own, target)
        assertEquals(PACKAGE, target?.packageName)
    }
}
