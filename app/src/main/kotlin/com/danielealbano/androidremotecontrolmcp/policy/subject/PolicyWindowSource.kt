package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo

/** The input-focused editable field: its package and its properties as parsed node data (no children/bounds). */
data class FocusedField(
    val packageName: String?,
    val node: AccessibilityNodeData,
) {
    override fun toString(): String = "FocusedField(packageName=$packageName, isPassword=${node.isPassword})"
}

/** Live accessibility reads used for subject resolution. */
interface PolicyWindowSource {
    /** Fresh read of every on-screen window from `getWindows()`; refreshes the node cache like any tool read. */
    fun readWindows(): MultiWindowResult

    /** The input-focused editable node, or null; same lookup `press_key` uses. */
    fun focusedEditableField(): FocusedField?

    fun screenInfo(): ScreenInfo
}
