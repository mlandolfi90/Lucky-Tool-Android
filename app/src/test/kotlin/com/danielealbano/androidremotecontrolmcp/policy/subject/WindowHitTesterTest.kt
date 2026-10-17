package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.BANK
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.FULL_SCREEN
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.KEYBOARD
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.OWN_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.SYSTEM_UI
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.button
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.container
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.window
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WindowHitTesterTest {
    private val hitTester = WindowHitTester(OWN_PACKAGE)
    private val buttonBounds = BoundsData(100, 100, 300, 200)

    private fun appWindow(
        children: List<AccessibilityNodeData>,
        packageName: String = BANK,
        type: String = SubjectSupport.WINDOW_TYPE_APPLICATION,
        bounds: BoundsData = FULL_SCREEN,
        layer: Int = 1,
    ): WindowData = window(packageName, type, container("root_$layer", bounds, children), focused = true, layer = layer)

    @Test
    fun `point inside one window returns that window and its deepest node`() {
        val payButton = button("pay", buttonBounds, "Pagar")
        val app = appWindow(listOf(payButton))

        val hits = hitTester.hitTest(listOf(app), 150f, 150f)

        assertEquals(listOf(NodeLocation(app, payButton)), hits)
    }

    @Test
    fun `nearest interactive ancestor wins over a non-interactive deepest node`() {
        val label = AccessibilityNodeData(id = "label", text = "Pagar", bounds = buttonBounds, visible = true)
        val payButton = button("pay", buttonBounds, null).copy(children = listOf(label))

        val hits = hitTester.hitTest(listOf(appWindow(listOf(payButton))), 150f, 150f)

        assertEquals("pay", hits.single().node.id)
    }

    @Test
    fun `deepest node is returned when nothing on the path is interactive`() {
        val canvas = AccessibilityNodeData(id = "canvas", bounds = buttonBounds, visible = true)

        val hits = hitTester.hitTest(listOf(appWindow(listOf(canvas))), 150f, 150f)

        assertEquals("canvas", hits.single().node.id)
    }

    @Test
    fun `last overlapping visible sibling wins`() {
        val first = button("first", buttonBounds, "A")
        val second = button("second", buttonBounds, "B")

        val hits = hitTester.hitTest(listOf(appWindow(listOf(first, second))), 150f, 150f)

        assertEquals("second", hits.single().node.id)
    }

    @Test
    fun `invisible child is skipped`() {
        val hidden = button("hidden", buttonBounds, "A").copy(visible = false)
        val parent = button("parent", BoundsData(0, 0, 500, 500), "P").copy(children = listOf(hidden))

        val hits = hitTester.hitTest(listOf(appWindow(listOf(parent))), 150f, 150f)

        assertEquals("parent", hits.single().node.id)
    }

    @Test
    fun `every window containing the point is returned top layer first`() {
        val app = appWindow(listOf(button("pay", buttonBounds, "Pagar")))
        val overlay = appWindow(emptyList(), packageName = SYSTEM_UI, type = "SYSTEM", layer = 5)

        val hits = hitTester.hitTest(listOf(app, overlay), 150f, 150f)

        assertEquals(listOf(SYSTEM_UI, BANK), hits.map { it.window.packageName })
    }

    @Test
    fun `window not containing the point is excluded`() {
        val app = appWindow(emptyList())
        val dialog = appWindow(emptyList(), packageName = SYSTEM_UI, bounds = BoundsData(500, 500, 800, 800), layer = 3)

        val hits = hitTester.hitTest(listOf(app, dialog), 150f, 150f)

        assertEquals(listOf(BANK), hits.map { it.window.packageName })
    }

    @Test
    fun `right and bottom edges are exclusive`() {
        val app = appWindow(emptyList(), bounds = BoundsData(0, 0, 100, 100))

        assertTrue(hitTester.hitTest(listOf(app), 100f, 50f).isEmpty())
        assertTrue(hitTester.hitTest(listOf(app), 50f, 100f).isEmpty())
    }

    @Test
    fun `own touch-through accessibility overlay is ignored`() {
        val app = appWindow(emptyList())
        val overlay =
            appWindow(
                emptyList(),
                packageName = OWN_PACKAGE,
                type = SubjectSupport.WINDOW_TYPE_ACCESSIBILITY_OVERLAY,
                layer = 9,
            )

        val hits = hitTester.hitTest(listOf(app, overlay), 150f, 150f)

        assertEquals(listOf(BANK), hits.map { it.window.packageName })
    }

    @Test
    fun `another package's accessibility overlay is hit`() {
        val app = appWindow(emptyList())
        val overlay =
            appWindow(
                emptyList(),
                packageName = SYSTEM_UI,
                type = SubjectSupport.WINDOW_TYPE_ACCESSIBILITY_OVERLAY,
                layer = 9,
            )

        val hits = hitTester.hitTest(listOf(app, overlay), 150f, 150f)

        assertEquals(listOf(SYSTEM_UI, BANK), hits.map { it.window.packageName })
    }

    @Test
    fun `IME window is returned like any window`() {
        val keyboard =
            appWindow(emptyList(), packageName = KEYBOARD, type = SubjectSupport.WINDOW_TYPE_INPUT_METHOD, layer = 4)

        val hits = hitTester.hitTest(listOf(keyboard), 150f, 150f)

        assertEquals(listOf(KEYBOARD), hits.map { it.window.packageName })
    }

    @Test
    fun `point outside every window returns empty list`() {
        val app = appWindow(emptyList(), bounds = BoundsData(0, 0, 100, 100))

        assertTrue(hitTester.hitTest(listOf(app), 500f, 500f).isEmpty())
    }

    @Test
    fun `NodeLocation toString omits node text`() {
        val location = NodeLocation(appWindow(emptyList()), button("pay", buttonBounds, "Pagar"))

        assertFalse(location.toString().contains("Pagar"))
    }
}
