package com.danielealbano.androidremotecontrolmcp.policy.model

import kotlinx.serialization.Serializable

/** The four action types a profile rules. Persisted by name: never rename an entry. */
@Serializable
enum class PolicyActionType {
    READ,
    NAVIGATE,
    TYPE,
    ACTION_BUTTON,
}
