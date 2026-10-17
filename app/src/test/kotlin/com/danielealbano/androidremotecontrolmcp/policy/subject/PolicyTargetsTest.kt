package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyLabels
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import com.danielealbano.androidremotecontrolmcp.policy.model.SelectorKind
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyTargetsTest {
    private val bounds = BoundsData(0, 0, 100, 100)
    private val bank = SubjectFixtures.BANK

    @Test
    fun `interactive target prefers the resource id`() {
        val node = SubjectFixtures.button("b", bounds, "Pagar", resourceId = "com.example.bank:id/pay")

        val target = PolicyTargets.interactive(bank, node)

        assertEquals(PolicySelector(SelectorKind.RESOURCE_ID, "com.example.bank:id/pay"), target.selector)
        assertTrue(target.identifiable)
        assertEquals("Pagar", target.nodeLabel)
    }

    @Test
    fun `interactive target without id gets a LABEL selector`() {
        val target = PolicyTargets.interactive(bank, SubjectFixtures.button("b", bounds, "Pagar $1.500"))

        assertEquals(PolicySelector(SelectorKind.LABEL, "android.widget.Button|pagar $"), target.selector)
        assertEquals(
            PolicyLabels.preferredSelector(null, SubjectFixtures.BUTTON_CLASS, "Pagar $1.500"),
            target.selector,
        )
    }

    @Test
    fun `blank resource id falls back to the label`() {
        val target = PolicyTargets.interactive(bank, SubjectFixtures.button("b", bounds, "Pagar", resourceId = "  "))

        assertEquals(SelectorKind.LABEL, target.selector?.kind)
    }

    @Test
    fun `label without a letter is not identifiable`() {
        val target = PolicyTargets.interactive(bank, SubjectFixtures.button("b", bounds, "$ 1.500"))

        assertNull(target.selector)
        assertFalse(target.identifiable)
        assertEquals("$ 1.500", target.nodeLabel)
    }

    @Test
    fun `field target carries hint label and isPassword`() {
        val node = SubjectFixtures.field("f", bounds, text = "1234", hint = "PIN", isPassword = true)

        val target = PolicyTargets.field(bank, node)

        assertEquals("PIN", target.nodeLabel)
        assertTrue(target.isPassword)
        assertEquals(PolicySelector(SelectorKind.LABEL, "android.widget.EditText|pin"), target.selector)
    }

    @Test
    fun `packageOnly target has no label selector or identity`() {
        val target = PolicyTargets.packageOnly(bank)

        assertEquals(bank, target.packageName)
        assertNull(target.nodeLabel)
        assertNull(target.selector)
        assertFalse(target.isPassword)
        assertFalse(target.identifiable)
    }

    @Test
    fun `PolicyTarget toString never prints the label or selector value`() {
        val text = PolicyTargets.interactive(bank, SubjectFixtures.button("b", bounds, "Pagar")).toString()

        assertFalse(text.contains("Pagar"))
        assertFalse(text.contains("pagar"))
    }
}
