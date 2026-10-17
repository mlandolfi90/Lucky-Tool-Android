package com.danielealbano.androidremotecontrolmcp.policy.subject

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PolicyWindowSourceImplTest {
    private val provider = mockk<AccessibilityServiceProvider>(relaxed = true)
    private val treeParser = mockk<AccessibilityTreeParser>(relaxed = true)
    private val nodeCache = mockk<AccessibilityNodeCache>(relaxed = true)
    private val windowInfo = mockk<AccessibilityWindowInfo>(relaxed = true)
    private val rootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
    private val source = PolicyWindowSourceImpl(treeParser, provider, nodeCache)
    private val tree = SubjectFixtures.container("root_w0", SubjectFixtures.FULL_SCREEN, emptyList())

    private fun stubOneWindow() {
        every { provider.isReady() } returns true
        every { provider.getAccessibilityWindows() } returns listOf(windowInfo)
        every { windowInfo.id } returns 0
        every { windowInfo.root } returns rootNode
        every { windowInfo.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { windowInfo.layer } returns 0
        every { windowInfo.isFocused } returns true
        every { rootNode.packageName } returns SubjectFixtures.BANK
        every { treeParser.parseTree(rootNode, "root_w0", any()) } returns tree
    }

    private fun focusedNode(editable: Boolean): AccessibilityNodeInfo =
        mockk<AccessibilityNodeInfo>(relaxed = true).also { node ->
            every { node.isEditable } returns editable
            every { node.packageName } returns SubjectFixtures.BANK
            every { node.className } returns SubjectFixtures.FIELD_CLASS
            every { node.text } returns "1500"
            every { node.hintText } returns "Monto"
            every { node.isPassword } returns true
            every { node.viewIdResourceName } returns "com.example.bank:id/amount"
            every { rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } returns node
        }

    @Test
    fun `readWindows returns the fresh read and populates the node cache`() {
        stubOneWindow()

        val result = source.readWindows()

        assertEquals(SubjectFixtures.BANK, result.windows.single().packageName)
        assertEquals(tree, result.windows.single().tree)
        verify { nodeCache.populate(any()) }
    }

    @Test
    fun `readWindows throws PermissionDenied when the service is not ready`() {
        every { provider.isReady() } returns false

        assertThrows<McpToolException.PermissionDenied> { source.readWindows() }
    }

    @Test
    fun `focusedEditableField maps the input-focused editable node`() {
        stubOneWindow()
        focusedNode(editable = true)

        val field = source.focusedEditableField()

        assertEquals(SubjectFixtures.BANK, field?.packageName)
        val node = field!!.node
        assertEquals("1500", node.text)
        assertEquals("Monto", node.hintText)
        assertTrue(node.isPassword)
        assertTrue(node.editable)
        assertEquals("com.example.bank:id/amount", node.resourceId)
        assertEquals(SubjectFixtures.FIELD_CLASS, node.className)
    }

    @Test
    fun `focusedEditableField returns null when the focused node is not editable`() {
        stubOneWindow()
        focusedNode(editable = false)

        assertNull(source.focusedEditableField())
    }

    @Test
    fun `screenInfo delegates to the provider`() {
        every { provider.getScreenInfo() } returns SubjectFixtures.SCREEN

        assertEquals(SubjectFixtures.SCREEN, source.screenInfo())
    }
}
