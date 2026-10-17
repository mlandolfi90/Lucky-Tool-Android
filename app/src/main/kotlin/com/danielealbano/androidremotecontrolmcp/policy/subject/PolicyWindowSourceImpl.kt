package com.danielealbano.androidremotecontrolmcp.policy.subject

import android.view.accessibility.AccessibilityNodeInfo
import com.danielealbano.androidremotecontrolmcp.mcp.tools.findFocusedEditableNode
import com.danielealbano.androidremotecontrolmcp.mcp.tools.getFreshWindows
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import javax.inject.Inject

class PolicyWindowSourceImpl
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
    ) : PolicyWindowSource {
        override fun readWindows(): MultiWindowResult =
            getFreshWindows(
                treeParser,
                accessibilityServiceProvider,
                nodeCache,
            )

        override fun focusedEditableField(): FocusedField? =
            findFocusedEditableNode(accessibilityServiceProvider)?.let { node ->
                FocusedField(node.packageName?.toString(), toNodeData(node))
            }

        override fun screenInfo(): ScreenInfo = accessibilityServiceProvider.getScreenInfo()

        private fun toNodeData(node: AccessibilityNodeInfo): AccessibilityNodeData =
            AccessibilityNodeData(
                id = FOCUSED_FIELD_ID,
                className = node.className?.toString(),
                text = node.text?.toString(),
                contentDescription = node.contentDescription?.toString(),
                resourceId = node.viewIdResourceName,
                bounds = NO_BOUNDS,
                editable = node.isEditable,
                isPassword = node.isPassword,
                hintText = node.hintText?.toString()?.takeIf { it.isNotEmpty() },
            )

        companion object {
            private const val FOCUSED_FIELD_ID = "focused_field"
            private val NO_BOUNDS = BoundsData(0, 0, 0, 0)
        }
    }
