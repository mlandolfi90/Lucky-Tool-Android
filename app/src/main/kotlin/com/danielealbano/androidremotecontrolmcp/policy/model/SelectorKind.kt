package com.danielealbano.androidremotecontrolmcp.policy.model

import kotlinx.serialization.Serializable

/**
 * How a [PolicySelector] identifies a node: [RESOURCE_ID] holds `viewIdResourceName`; [LABEL] holds
 * `<className>|<normalized label>` built by `PolicyLabels.labelSelector`.
 */
@Serializable
enum class SelectorKind {
    RESOURCE_ID,
    LABEL,
}
