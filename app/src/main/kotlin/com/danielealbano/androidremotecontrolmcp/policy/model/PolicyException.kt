package com.danielealbano.androidremotecontrolmcp.policy.model

import kotlinx.serialization.Serializable

/** An owner-confirmed exception that weakens a profile for one [selector] until the owner removes it. */
@Serializable
data class PolicyException(
    val selector: PolicySelector,
    val createdAtMs: Long,
)
