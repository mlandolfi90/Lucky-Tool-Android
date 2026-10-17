package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NodeLabelsTest {
    private val bounds = BoundsData(0, 0, 100, 100)

    private fun text(
        id: String,
        value: String,
    ): AccessibilityNodeData = AccessibilityNodeData(id = id, text = value, bounds = bounds, visible = true)

    @Test
    fun `interactiveLabel joins own and descendant text breadth-first`() {
        val node =
            SubjectFixtures.button("b", bounds, "Continuar").copy(children = listOf(text("t", "Pagar")))

        assertEquals("Continuar Pagar", NodeLabels.interactiveLabel(node))
    }

    @Test
    fun `interactiveLabel keeps each distinct part once`() {
        val node = SubjectFixtures.button("b", bounds, "7").copy(contentDescription = "7")

        assertEquals("7", NodeLabels.interactiveLabel(node))
    }

    @Test
    fun `interactiveLabel ignores case and spacing when one node repeats a part`() {
        val node = SubjectFixtures.button("b", bounds, "Pagar").copy(contentDescription = " PAGAR ")

        assertEquals("Pagar", NodeLabels.interactiveLabel(node))
    }

    @Test
    fun `interactiveLabel keeps a part repeated by a different node`() {
        val node = SubjectFixtures.button("b", bounds, "Pagar").copy(children = listOf(text("t", "Pagar")))

        assertEquals("Pagar Pagar", NodeLabels.interactiveLabel(node))
    }

    @Test
    fun `fieldLabel shows a hint repeated by the description once`() {
        val node = SubjectFixtures.field("f", bounds, text = "1500", hint = "Monto").copy(contentDescription = "Monto")

        assertEquals("Monto", NodeLabels.fieldLabel(node))
    }

    @Test
    fun `interactiveLabel of an editable node excludes its value`() {
        val node = SubjectFixtures.field("f", bounds, text = "1500", hint = "Monto")

        assertEquals("Monto", NodeLabels.interactiveLabel(node))
    }

    @Test
    fun `interactiveLabel skips editable descendants' values`() {
        val node =
            SubjectFixtures.container(
                "c",
                bounds,
                listOf(text("t", "Total"), SubjectFixtures.field("f", bounds, text = "999", hint = null)),
            )

        assertFalse(NodeLabels.interactiveLabel(node).orEmpty().contains("999"))
    }

    @Test
    fun `interactiveLabel visits at most 25 nodes`() {
        val children = (1..40).map { text("t$it", "w$it") }
        val node = SubjectFixtures.container("c", bounds, children)

        val label = NodeLabels.interactiveLabel(node).orEmpty().split(" ")

        assertEquals((1..24).map { "w$it" }, label)
    }

    @Test
    fun `interactiveLabel caps length at 200 characters`() {
        val node = SubjectFixtures.button("b", bounds, "a".repeat(500))

        assertEquals(200, NodeLabels.interactiveLabel(node)?.length)
    }

    @Test
    fun `interactiveLabel collapses whitespace`() {
        val node = SubjectFixtures.button("b", bounds, "Pagar \n  ahora")

        assertEquals("Pagar ahora", NodeLabels.interactiveLabel(node))
    }

    @Test
    fun `interactiveLabel returns null for an unlabeled icon`() {
        val icon =
            AccessibilityNodeData(
                id = "i",
                className = "android.widget.ImageView",
                bounds = bounds,
                clickable = true,
                visible = true,
            )

        assertNull(NodeLabels.interactiveLabel(icon))
    }

    @Test
    fun `fieldLabel uses hint labeledBy and description only`() {
        val node =
            SubjectFixtures
                .field("f", bounds, text = "1500", hint = "Monto")
                .copy(labeledByText = "Importe", contentDescription = "Campo")

        val label = NodeLabels.fieldLabel(node).orEmpty()

        assertEquals("Monto Importe Campo", label)
        assertTrue(!label.contains("1500"))
    }
}
