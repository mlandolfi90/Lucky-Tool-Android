package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicySubject
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import kotlinx.serialization.json.JsonObject

/**
 * One call to resolve. [profiledPackages] = packages with a decoded OR unreadable profile. [arguments] may carry
 * typed text and selector values: never logged ([toString] omits it).
 */
data class SubjectRequest(
    val toolName: String,
    val arguments: JsonObject?,
    val profiledPackages: Set<String>,
) {
    override fun toString(): String = "SubjectRequest(toolName=$toolName, profiledPackages=${profiledPackages.size})"
}

/** Why a call is FORBIDDEN before any profile rule is read. [code] is the wire/log reason code. */
enum class BlockCause(
    val code: String,
) {
    TARGET_UNRESOLVED("target_unresolved"),
    IME_INPUT_IN_PROFILED_APP("ime_input_in_profiled_app"),
    BACKGROUND_COMPONENT_IN_PROFILED_APP("background_component_in_profiled_app"),
    UNRESOLVED_IMPLICIT_INTENT("unresolved_implicit_intent"),
}

/** Outcome of subject resolution. */
sealed interface SubjectResolution {
    /** The tool never targets an app (files, camera, location, global reads, approval tools). */
    data object NotAppScoped : SubjectResolution

    /** Leaving the current app (home, recents, shade, keyboard dismissal): always allowed. */
    data object ExemptExit : SubjectResolution

    /** No app could be attributed and no profile exists: the call runs as today. */
    data object Unattributed : SubjectResolution

    /** Attributed: the engine evaluates [subject] (its own [toString] redacts labels and amount text). */
    data class Resolved(
        val subject: PolicySubject,
    ) : SubjectResolution

    /** FORBIDDEN regardless of rules; [packageName] is the app it would have reached, when known. */
    data class Blocked(
        val packageName: String?,
        val actionType: PolicyActionType,
        val cause: BlockCause,
    ) : SubjectResolution
}
