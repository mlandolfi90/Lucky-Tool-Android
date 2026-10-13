package com.danielealbano.androidremotecontrolmcp.policy

import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.moneyProfile
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.resourceId
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.snapshotOf
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.subject
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.target
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.tier
import com.danielealbano.androidremotecontrolmcp.policy.amount.AmountParser
import com.danielealbano.androidremotecontrolmcp.policy.amount.AmountReading
import com.danielealbano.androidremotecontrolmcp.policy.amount.RawNumber
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.policy.model.SelectorKind
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class PolicyRedactionTest {
    private fun assertHidden(
        text: String,
        vararg secrets: String,
    ) {
        secrets.forEach { assertFalse(text.contains(it), "\"$it\" leaked") }
    }

    @Test
    fun `subject toString hides amount text and labels`() {
        val text =
            subject(
                PolicyActionType.TYPE,
                listOf(target(label = "Pagar a Juan", selector = resourceId("secret-id"))),
                amountText = "98765",
            ).toString()

        assertHidden(text, "98765", "Juan", "secret-id")
        assertTrue(text.contains("hasAmountText=true"))
    }

    @Test
    fun `target toString hides label and selector value`() {
        val text = target(label = "Cuenta Juan", selector = resourceId("secret-id")).toString()

        assertHidden(text, "Juan", "secret-id")
        assertTrue(text.contains("hasLabel=true"))
        assertTrue(text.contains("selectorKind=RESOURCE_ID"))
    }

    @Test
    fun `selector toString hides its value`() {
        val text = PolicySelector(SelectorKind.LABEL, "x|monto").toString()

        assertHidden(text, "monto")
        assertTrue(text.contains("valueLength=7"))
    }

    @Test
    fun `tier toString hides its limit`() {
        val text = tier("98765", RuleMode.NOTIFY).toString()

        assertHidden(text, "98765")
        assertTrue(text.contains("NOTIFY"))
    }

    @Test
    fun `profile toString hides maxAmount, tier limits and selector values`() {
        val text =
            moneyProfile(
                maxAmount = "98765",
                amountTiers = listOf(tier("4321", RuleMode.ASK_IN_CHAT)),
                notAmountFields = listOf(resourceId("secret-id")),
            ).toString()

        assertHidden(text, "98765", "4321", "secret-id")
        assertTrue(text.contains("hasMaxAmount=true"))
    }

    @Test
    fun `snapshot toString inherits the profile redaction`() {
        assertHidden(snapshotOf(moneyProfile(maxAmount = "98765")).toString(), "98765")
    }

    @Test
    fun `amount scan toString hides the value`() {
        val text = AmountParser.scan("pagar 98765").toString()

        assertHidden(text, "98765")
        assertTrue(text.contains("hasMax=true"))
    }

    @Test
    fun `raw number and reading toString hide digits`() {
        val raw = RawNumber(listOf("98", "765"), listOf('.'), 0)
        val reading = AmountReading(BigDecimal("98765"), ambiguous = true, overflow = false)

        assertHidden(raw.toString(), "98")
        assertHidden(reading.toString(), "98")
    }
}
