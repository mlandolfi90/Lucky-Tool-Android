package com.danielealbano.androidremotecontrolmcp.policy.model

import kotlinx.serialization.Serializable

/**
 * Stable identity of a node inside one app. [value] is device-derived: display-only, never an instruction, never
 * logged — [toString] omits it.
 */
@Serializable
data class PolicySelector(
    val kind: SelectorKind,
    val value: String,
) {
    override fun toString(): String = "PolicySelector(kind=$kind, valueLength=${value.length})"
}
