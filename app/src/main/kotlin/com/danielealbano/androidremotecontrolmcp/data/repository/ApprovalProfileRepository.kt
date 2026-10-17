package com.danielealbano.androidremotecontrolmcp.data.repository

import com.danielealbano.androidremotecontrolmcp.policy.model.AmountTier
import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfile
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Per-app approval profiles on a DEDICATED Preferences DataStore (`approval_profiles`), one JSON value per package
 * under the key `profile:<packageName>`. Undecodable or invalid values are reported in [ProfilesSnapshot.unreadable]
 * (fail-closed), never dropped. Exception lists change only through [addException] / [removeException]. Only the
 * policy gate and the owner's UI use this; no MCP tool writes it.
 */
interface ApprovalProfileRepository {
    /**
     * In-memory snapshot for display. It is [ProfilesSnapshot.EMPTY] until the first successful load and STAYS empty
     * while the store is unreadable: enforcement MUST use [current], which throws in that case.
     */
    val profiles: StateFlow<ProfilesSnapshot>

    /** Read state of the store; [ProfileStoreStatus.UNREADABLE] while the last read failed. */
    val storeStatus: StateFlow<ProfileStoreStatus>

    /** Loads on collection, then emits the snapshot and every later change. Never throws on a read failure. */
    fun observeProfiles(): Flow<ProfilesSnapshot>

    /**
     * Returns the snapshot, loading it from disk on the first call (under a mutex); later calls are lock-free.
     *
     * @throws ProfileStoreUnreadableException when the store cannot be read; the next call retries.
     */
    suspend fun current(): ProfilesSnapshot

    /**
     * Creates or replaces the profile of [ApprovalProfile.packageName], stamped with the current time. The incoming
     * exception lists are ignored: an existing profile keeps its stored ones, a new or unreadable one starts empty.
     */
    suspend fun upsert(profile: ApprovalProfile): ProfileSaveResult

    /**
     * Replaces the five owner settings of an existing readable profile in one locked step, keeping its stored
     * exception lists. Returns [ProfileSaveResult.NotFound] when the package has no readable profile.
     */
    suspend fun updateSettings(
        packageName: String,
        template: ProfileTemplate,
        rules: Map<PolicyActionType, RuleMode>,
        maxAmount: String?,
        amountTiers: List<AmountTier>,
    ): ProfileSaveResult

    /** Removes the profile key, readable or unreadable. */
    suspend fun delete(packageName: String): ProfileDeleteResult

    /** Adds [selector] to the [kind] list; an already-present selector returns [ProfileSaveResult.Saved] unchanged. */
    suspend fun addException(
        packageName: String,
        kind: PolicyExceptionKind,
        selector: PolicySelector,
    ): ProfileSaveResult

    /** Removes [selector] from the [kind] list; an absent selector returns [ProfileSaveResult.Saved] unchanged. */
    suspend fun removeException(
        packageName: String,
        kind: PolicyExceptionKind,
        selector: PolicySelector,
    ): ProfileSaveResult
}
