package com.danielealbano.androidremotecontrolmcp.policy.subject

import kotlinx.serialization.json.JsonObject

/** Single entry point of subject resolution. */
interface ToolSubjectResolver {
    /** @throws IllegalStateException when [toolName] is not in [ToolPolicyCatalog]. */
    suspend fun resolve(
        toolName: String,
        arguments: JsonObject?,
        profiledPackages: Set<String>,
    ): SubjectResolution
}
