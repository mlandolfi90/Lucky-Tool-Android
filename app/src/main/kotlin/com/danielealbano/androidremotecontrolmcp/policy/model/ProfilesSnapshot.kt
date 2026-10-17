package com.danielealbano.androidremotecontrolmcp.policy.model

/**
 * Immutable view of every stored approval profile, keyed by package. [unreadable] holds the packages whose stored
 * profile failed to decode or validate; the engine treats them fail-closed (every non-READ action is FORBIDDEN).
 */
data class ProfilesSnapshot(
    val profiles: Map<String, ApprovalProfile>,
    val unreadable: Set<String>,
) {
    /** Number of stored profile keys, readable or not: what the storage cap counts. */
    val storedCount: Int get() = profiles.size + unreadable.size

    /** True when at least one profile key exists, readable or not. */
    val hasAnyProfile: Boolean get() = storedCount > 0

    companion object {
        /** No profile stored. */
        val EMPTY = ProfilesSnapshot(profiles = emptyMap(), unreadable = emptySet())
    }
}
