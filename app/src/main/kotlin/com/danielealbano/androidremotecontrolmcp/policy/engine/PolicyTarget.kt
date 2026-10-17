package com.danielealbano.androidremotecontrolmcp.policy.engine

import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector

/**
 * One app a call lands in. [nodeLabel] is device-derived (display-only, never logged); [identifiable] is false
 * when the hit-test found no node (canvas, WebView, unlabeled Compose). [toString] omits label and selector value.
 */
data class PolicyTarget(
    val packageName: String,
    val nodeLabel: String?,
    val selector: PolicySelector?,
    val isPassword: Boolean,
    val identifiable: Boolean,
) {
    override fun toString(): String =
        "PolicyTarget(packageName=$packageName, hasLabel=${nodeLabel != null}, " +
            "selectorKind=${selector?.kind}, isPassword=$isPassword, identifiable=$identifiable)"
}
