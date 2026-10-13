package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyLabels
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyTarget
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData

/**
 * Builds [PolicyTarget]s. Selectors come ONLY from [PolicyLabels.preferredSelector], so live targets and stored
 * exceptions compare equal. Package-only targets are never classified by label nor excepted.
 */
internal object PolicyTargets {
    /** Tap/click target: the engine classifies it NAVIGATE vs ACTION_BUTTON from label, selector, identifiable. */
    fun interactive(
        packageName: String,
        node: AccessibilityNodeData,
    ): PolicyTarget = labelled(packageName, node, NodeLabels.interactiveLabel(node))

    /** Text field target: selector for the "not an amount" exception, isPassword for the amount skip. */
    fun field(
        packageName: String,
        node: AccessibilityNodeData,
    ): PolicyTarget = labelled(packageName, node, NodeLabels.fieldLabel(node))

    fun packageOnly(packageName: String): PolicyTarget =
        PolicyTarget(
            packageName = packageName,
            nodeLabel = null,
            selector = null,
            isPassword = false,
            identifiable = false,
        )

    private fun labelled(
        packageName: String,
        node: AccessibilityNodeData,
        label: String?,
    ): PolicyTarget {
        val selector = PolicyLabels.preferredSelector(node.resourceId, node.className, label)
        return PolicyTarget(
            packageName = packageName,
            nodeLabel = label,
            selector = selector,
            isPassword = node.isPassword,
            identifiable = selector != null,
        )
    }
}
