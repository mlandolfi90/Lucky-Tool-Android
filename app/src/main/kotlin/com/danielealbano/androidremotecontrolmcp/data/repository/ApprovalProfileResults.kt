package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfile
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileValidationError

/** Outcome of a profile write. Nothing is written unless the result is [Saved]. */
sealed interface ProfileSaveResult {
    /**
     * [profile] is exactly what is stored now. [created] is true when no key existed for the package before this
     * call (an idempotent no-op returns the stored profile with [created] false).
     */
    data class Saved(
        val profile: ApprovalProfile,
        val created: Boolean,
    ) : ProfileSaveResult

    /** Rejected by `ApprovalProfileValidator`. */
    data class Invalid(
        val errors: List<ProfileValidationError>,
    ) : ProfileSaveResult

    /** A new package was refused because `PolicyLimits.MAX_PROFILES` keys are stored. */
    data object ProfileLimitReached : ProfileSaveResult

    /** An exception was refused because its list holds `PolicyLimits.MAX_EXCEPTIONS_PER_LIST` entries. */
    data object ExceptionLimitReached : ProfileSaveResult

    /** The package has no readable profile. */
    data object NotFound : ProfileSaveResult

    /** The store could not be read or written; nothing changed. */
    data object StoreUnavailable : ProfileSaveResult
}

/** Outcome of [ApprovalProfileRepository.delete]. */
enum class ProfileDeleteResult {
    /** The key (readable or unreadable profile) was removed. */
    DELETED,

    /** No key was stored for the package. */
    NOT_FOUND,

    /** The store could not be read or written; nothing changed. */
    STORE_UNAVAILABLE,
}
