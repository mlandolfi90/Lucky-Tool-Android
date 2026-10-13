package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danielealbano.androidremotecontrolmcp.di.ApprovalProfilesDataStore
import com.danielealbano.androidremotecontrolmcp.policy.model.AmountTier
import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfile
import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfileValidator
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyException
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyLimits
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.utils.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MCP:ApprovalProfiles"
private const val KEY_PREFIX = "profile:"

/**
 * [ApprovalProfileRepository] on the dedicated `approval_profiles` DataStore. No own scope: the first [current],
 * [observeProfiles] collection or mutation loads the store in the caller's coroutine under [mutex]; every mutation
 * writes disk first, then the snapshot.
 */
@Singleton
class ApprovalProfileRepositoryImpl
    @Inject
    constructor(
        @param:ApprovalProfilesDataStore private val dataStore: DataStore<Preferences>,
    ) : ApprovalProfileRepository {
        /** Test seam for the clock; defaults to the wall clock. */
        internal var clockMs: () -> Long = { System.currentTimeMillis() }

        private val state = MutableStateFlow(ProfilesSnapshot.EMPTY)
        private val status = MutableStateFlow(ProfileStoreStatus.NOT_LOADED)
        private val mutex = Mutex()

        @Volatile
        private var loaded = false

        override val profiles: StateFlow<ProfilesSnapshot> = state.asStateFlow()

        override val storeStatus: StateFlow<ProfileStoreStatus> = status.asStateFlow()

        override fun observeProfiles(): Flow<ProfilesSnapshot> =
            flow {
                try {
                    current()
                } catch (_: ProfileStoreUnreadableException) {
                    // storeStatus reads UNREADABLE; the flow keeps emitting so the owner's UI can show it.
                }
                emitAll(state)
            }

        override suspend fun current(): ProfilesSnapshot {
            if (!loaded) mutex.withLock { loadLocked() }
            return state.value
        }

        override suspend fun upsert(profile: ApprovalProfile): ProfileSaveResult =
            mutate<ProfileSaveResult>(ProfileSaveResult.StoreUnavailable) { snapshot ->
                val existing = snapshot.profiles[profile.packageName]
                val isNew = existing == null && profile.packageName !in snapshot.unreadable
                if (isNew && snapshot.storedCount >= PolicyLimits.MAX_PROFILES) {
                    ProfileSaveResult.ProfileLimitReached
                } else {
                    saveLocked(
                        candidate =
                            profile.copy(
                                notAmountFields = existing?.notAmountFields.orEmpty(),
                                notActionButtons = existing?.notActionButtons.orEmpty(),
                                updatedAtMs = clockMs(),
                            ),
                        created = isNew,
                    )
                }
            }

        override suspend fun updateSettings(
            packageName: String,
            template: ProfileTemplate,
            rules: Map<PolicyActionType, RuleMode>,
            maxAmount: String?,
            amountTiers: List<AmountTier>,
        ): ProfileSaveResult =
            mutate<ProfileSaveResult>(ProfileSaveResult.StoreUnavailable) { snapshot ->
                val existing = snapshot.profiles[packageName]
                if (existing == null) {
                    ProfileSaveResult.NotFound
                } else {
                    saveLocked(
                        candidate =
                            existing.copy(
                                template = template,
                                rules = rules,
                                maxAmount = maxAmount,
                                amountTiers = amountTiers,
                                updatedAtMs = clockMs(),
                            ),
                        created = false,
                    )
                }
            }

        override suspend fun delete(packageName: String): ProfileDeleteResult =
            mutate(ProfileDeleteResult.STORE_UNAVAILABLE) { snapshot ->
                if (packageName in snapshot.profiles || packageName in snapshot.unreadable) {
                    dataStore.edit { it.remove(profileKey(packageName)) }
                    state.value =
                        ProfilesSnapshot(
                            profiles = snapshot.profiles - packageName,
                            unreadable = snapshot.unreadable - packageName,
                        )
                    ProfileDeleteResult.DELETED
                } else {
                    ProfileDeleteResult.NOT_FOUND
                }
            }

        override suspend fun addException(
            packageName: String,
            kind: PolicyExceptionKind,
            selector: PolicySelector,
        ): ProfileSaveResult =
            mutate<ProfileSaveResult>(ProfileSaveResult.StoreUnavailable) { snapshot ->
                val existing = snapshot.profiles[packageName]
                val list = existing?.exceptionsOf(kind).orEmpty()
                when {
                    existing == null -> {
                        ProfileSaveResult.NotFound
                    }

                    list.any { it.selector == selector } -> {
                        ProfileSaveResult.Saved(existing, created = false)
                    }

                    list.size >= PolicyLimits.MAX_EXCEPTIONS_PER_LIST -> {
                        ProfileSaveResult.ExceptionLimitReached
                    }

                    else -> {
                        val nowMs = clockMs()
                        val added = list + PolicyException(selector, nowMs)
                        saveLocked(existing.withExceptions(kind, added, nowMs), created = false)
                    }
                }
            }

        override suspend fun removeException(
            packageName: String,
            kind: PolicyExceptionKind,
            selector: PolicySelector,
        ): ProfileSaveResult =
            mutate<ProfileSaveResult>(ProfileSaveResult.StoreUnavailable) { snapshot ->
                val existing = snapshot.profiles[packageName]
                val list = existing?.exceptionsOf(kind).orEmpty()
                when {
                    existing == null -> {
                        ProfileSaveResult.NotFound
                    }

                    list.none { it.selector == selector } -> {
                        ProfileSaveResult.Saved(existing, created = false)
                    }

                    else -> {
                        val remaining = list.filterNot { it.selector == selector }
                        saveLocked(existing.withExceptions(kind, remaining, clockMs()), created = false)
                    }
                }
            }

        /**
         * Runs [change] under [mutex] after loading. A store that cannot be read or written yields [unavailable];
         * DataStore writes are atomic, so nothing was written and the snapshot is unchanged.
         */
        private suspend fun <T> mutate(
            unavailable: T,
            change: suspend (ProfilesSnapshot) -> T,
        ): T =
            mutex.withLock {
                try {
                    loadLocked()
                    change(state.value)
                } catch (_: IOException) {
                    Logger.w(TAG, "Approval profile store unavailable; nothing was written")
                    unavailable
                }
            }

        /** Decodes every `profile:` key once. MUST be called with [mutex] held. */
        private suspend fun loadLocked() {
            if (loaded) return
            val stored =
                try {
                    dataStore.data.first()
                } catch (e: IOException) {
                    status.value = ProfileStoreStatus.UNREADABLE
                    Logger.w(TAG, "Approval profile store cannot be read")
                    throw ProfileStoreUnreadableException(e)
                }
            val decoded = mutableMapOf<String, ApprovalProfile?>()
            stored.asMap().forEach { (key, value) ->
                packageOfKey(key)?.let { pkg -> decoded[pkg] = decodeProfile(pkg, value) }
            }
            val unreadable = decoded.filterValues { it == null }.keys.toSet()
            unreadable.forEach { Logger.w(TAG, "Approval profile unreadable, package=$it") }
            state.value =
                ProfilesSnapshot(
                    profiles = decoded.mapNotNull { (pkg, profile) -> profile?.let { pkg to it } }.toMap(),
                    unreadable = unreadable,
                )
            status.value = ProfileStoreStatus.LOADED
            loaded = true
        }

        /** Validates [candidate], then writes disk and snapshot. MUST be called with [mutex] held. */
        private suspend fun saveLocked(
            candidate: ApprovalProfile,
            created: Boolean,
        ): ProfileSaveResult {
            val errors = ApprovalProfileValidator.validate(candidate)
            if (errors.isNotEmpty()) return ProfileSaveResult.Invalid(errors)
            dataStore.edit { it[profileKey(candidate.packageName)] = Json.encodeToString(candidate) }
            val snapshot = state.value
            state.value =
                ProfilesSnapshot(
                    profiles = snapshot.profiles + (candidate.packageName to candidate),
                    unreadable = snapshot.unreadable - candidate.packageName,
                )
            return ProfileSaveResult.Saved(candidate, created)
        }
    }

private fun profileKey(packageName: String): Preferences.Key<String> = stringPreferencesKey(KEY_PREFIX + packageName)

private fun packageOfKey(key: Preferences.Key<*>): String? {
    val name = key.name
    return if (name.startsWith(KEY_PREFIX)) name.removePrefix(KEY_PREFIX) else null
}

/** Null (→ unreadable) unless [raw] is a string that decodes strictly, matches its key and passes validation. */
private fun decodeProfile(
    packageName: String,
    raw: Any,
): ApprovalProfile? {
    val decoded =
        (raw as? String)?.let { text ->
            try {
                Json.decodeFromString<ApprovalProfile>(text)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    return decoded?.takeIf { it.packageName == packageName && ApprovalProfileValidator.validate(it).isEmpty() }
}

private fun ApprovalProfile.exceptionsOf(kind: PolicyExceptionKind): List<PolicyException> =
    when (kind) {
        PolicyExceptionKind.NOT_AMOUNT_FIELD -> notAmountFields
        PolicyExceptionKind.NOT_ACTION_BUTTON -> notActionButtons
    }

private fun ApprovalProfile.withExceptions(
    kind: PolicyExceptionKind,
    list: List<PolicyException>,
    nowMs: Long,
): ApprovalProfile =
    when (kind) {
        PolicyExceptionKind.NOT_AMOUNT_FIELD -> copy(notAmountFields = list, updatedAtMs = nowMs)
        PolicyExceptionKind.NOT_ACTION_BUTTON -> copy(notActionButtons = list, updatedAtMs = nowMs)
    }
