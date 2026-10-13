package com.danielealbano.androidremotecontrolmcp.policy.model

import kotlinx.serialization.Serializable

/** Starting values chosen when a profile is created (see `ProfileTemplates`). Persisted by name. */
@Serializable
enum class ProfileTemplate {
    MONEY_APP,
    COMMON_APP,
}
