package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.policy.engine.OwnPackageName
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import javax.inject.Inject

/** A node inside a parsed window. [toString] omits node text. */
data class NodeLocation(
    val window: WindowData,
    val node: AccessibilityNodeData,
) {
    override fun toString(): String {
        val windowPart = "windowId=${window.windowId}, windowType=${window.windowType}"
        return "NodeLocation($windowPart, nodeId=${node.id})"
    }
}

/**
 * What a screen point touches: EVERY window whose bounds (its root node bounds) contain the point, top layer first,
 * each with the nearest clickable/long-clickable/editable ancestor-or-self of the deepest visible node at the point
 * (the deepest node itself when none is interactive; later siblings are drawn on top). This app's own touch-through
 * accessibility overlay (the tool-call indicator, FLAG_NOT_TOUCHABLE; package = [ownPackageName]) is skipped.
 */
class WindowHitTester
    @Inject
    constructor(
        @param:OwnPackageName private val ownPackageName: String,
    ) {
        fun hitTest(
            windows: List<WindowData>,
            x: Float,
            y: Float,
        ): List<NodeLocation> =
            windows
                .filterNot(::isOwnTouchThroughOverlay)
                .filter { contains(it.tree.bounds, x, y) }
                .sortedByDescending { it.layer }
                .map { NodeLocation(it, targetNodeAt(it.tree, x, y)) }

        private fun targetNodeAt(
            root: AccessibilityNodeData,
            x: Float,
            y: Float,
        ): AccessibilityNodeData {
            var current = root
            var interactive: AccessibilityNodeData? = root.takeIf(::isInteractive)
            var next = topChildAt(current, x, y)
            while (next != null) {
                current = next
                if (isInteractive(current)) interactive = current
                next = topChildAt(current, x, y)
            }
            return interactive ?: current
        }

        private fun topChildAt(
            node: AccessibilityNodeData,
            x: Float,
            y: Float,
        ): AccessibilityNodeData? = node.children.lastOrNull { it.visible && contains(it.bounds, x, y) }

        private fun isInteractive(node: AccessibilityNodeData): Boolean =
            node.clickable ||
                node.longClickable ||
                node.editable

        private fun isOwnTouchThroughOverlay(window: WindowData): Boolean =
            window.windowType == SubjectSupport.WINDOW_TYPE_ACCESSIBILITY_OVERLAY &&
                window.packageName == ownPackageName

        private fun contains(
            bounds: BoundsData,
            x: Float,
            y: Float,
        ): Boolean = x >= bounds.left && x < bounds.right && y >= bounds.top && y < bounds.bottom
    }
