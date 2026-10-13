package com.danielealbano.androidremotecontrolmcp.policy.engine

import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyLimits
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import com.danielealbano.androidremotecontrolmcp.policy.model.SelectorKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Locale

class PolicyLabelsTest {
    @Test
    fun `normalize folds case accents digits and number punctuation`() {
        assertEquals("pagar $", PolicyLabels.normalize("  Pagár   $1.500 "))
        assertEquals("pagar $", PolicyLabels.normalize("PAGAR $2.000,50"))
    }

    @Test
    fun `normalize folds full-width letters`() {
        assertEquals("transferir", PolicyLabels.normalize("ＴＲＡＮＳＦＥＲＩＲ"))
    }

    @Test
    fun `normalize uses root locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"))

            assertEquals("titulo", PolicyLabels.normalize("TITULO"))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `words splits on every non-letter`() {
        assertEquals(listOf("transferi", "ahora", "x"), PolicyLabels.words("Transferí-ahora 2x"))
        assertEquals(emptyList<String>(), PolicyLabels.words(""))
    }

    @Test
    fun `only the first MAX_LABEL_INPUT_LENGTH chars count`() {
        val label = "a".repeat(PolicyLabels.MAX_LABEL_INPUT_LENGTH) + " pagar"

        assertFalse("pagar" in PolicyLabels.words(label))
    }

    @Test
    fun `resourceIdSelector`() {
        assertNull(PolicyLabels.resourceIdSelector(null))
        assertNull(PolicyLabels.resourceIdSelector(""))
        assertNull(PolicyLabels.resourceIdSelector("  "))
        assertEquals(
            PolicySelector(SelectorKind.RESOURCE_ID, "com.bank:id/amount"),
            PolicyLabels.resourceIdSelector("com.bank:id/amount"),
        )
        assertNull(PolicyLabels.resourceIdSelector("x".repeat(PolicyLimits.MAX_SELECTOR_VALUE_LENGTH + 1)))
    }

    @Test
    fun `labelSelector builds class and normalized label`() {
        assertEquals(
            PolicySelector(SelectorKind.LABEL, "android.widget.EditText|monto $"),
            PolicyLabels.labelSelector("android.widget.EditText", "Monto $"),
        )
        assertEquals(PolicySelector(SelectorKind.LABEL, "|monto"), PolicyLabels.labelSelector(null, "Monto"))
    }

    @Test
    fun `labelSelector needs a letter`() {
        assertNull(PolicyLabels.labelSelector("android.widget.Button", "$ 1.500"))
        assertNull(PolicyLabels.labelSelector("android.widget.Button", "123"))
        assertNull(PolicyLabels.labelSelector("android.widget.Button", null))
    }

    @Test
    fun `labelSelector caps the value`() {
        val selector = PolicyLabels.labelSelector("android.widget.Button", "a".repeat(600))

        assertEquals(PolicyLimits.MAX_SELECTOR_VALUE_LENGTH, selector?.value?.length)
    }

    @Test
    fun `preferredSelector prefers the resource id`() {
        assertEquals(
            SelectorKind.RESOURCE_ID,
            PolicyLabels.preferredSelector("com.bank:id/pay", "android.widget.Button", "Pagar")?.kind,
        )
        assertEquals(SelectorKind.LABEL, PolicyLabels.preferredSelector(" ", "android.widget.Button", "Pagar")?.kind)
        assertNull(PolicyLabels.preferredSelector(null, "android.widget.Button", null))
    }
}
