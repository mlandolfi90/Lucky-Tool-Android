package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.data.repository.ApprovalProfileRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileSaveResult
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileStoreStatus
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalEventLogger
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogOutcome
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogRecord
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyLimits
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplates
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Backs the profile editor: an editable draft, live validation, save, and immediate exception removal. */
@HiltViewModel
class ApprovalProfileEditorViewModel
    @Inject
    constructor(
        private val repository: ApprovalProfileRepository,
        private val events: ApprovalEventLogger,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val session = MutableStateFlow(EditorSession())

        val state: StateFlow<ProfileEditorUiState> =
            combine(session, repository.observeProfiles(), repository.storeStatus) { current, snapshot, status ->
                buildEditorState(current, snapshot, storeUnreadable = status == ProfileStoreStatus.UNREADABLE)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), ProfileEditorUiState())

        /**
         * Idempotent: only the first call binds the editor. A stored profile always wins over [template]; without a
         * stored profile and without a template the draft stays null (not found, unreadable or store unreadable).
         */
        fun load(
            packageName: String,
            template: ProfileTemplate?,
        ) {
            var bound = false
            session.update { current ->
                bound = current.target == null
                if (bound) current.copy(target = EditorTarget(packageName, template)) else current
            }
            if (!bound) return
            viewModelScope.launch(ioDispatcher) {
                val stored = repository.observeProfiles().first().profiles[packageName]
                // The timestamp of a template profile is discarded: the repository stamps every write.
                val created = template?.let { ProfileTemplates.newProfile(packageName, it, nowMs = 0L) }
                val draft = (stored ?: created)?.toDraft()
                session.update { it.copy(loaded = true, draft = draft) }
            }
        }

        fun setRule(
            type: PolicyActionType,
            mode: RuleMode,
        ) {
            editDraft { copy(rules = rules + (type to mode)) }
        }

        fun setMaxAmount(text: String) {
            editDraft { copy(maxAmountText = text.take(PolicyLimits.MAX_AMOUNT_TEXT_LENGTH)) }
        }

        /** Appends a blank tier with the last tier's rule; ignored at `PolicyLimits.MAX_AMOUNT_TIERS`. */
        fun addTier() {
            editDraft {
                val mode = tiers.lastOrNull()?.mode ?: RuleMode.ASK_IN_CHAT
                if (tiers.size < PolicyLimits.MAX_AMOUNT_TIERS) {
                    copy(tiers = tiers + TierDraft(amountText = "", mode = mode))
                } else {
                    this
                }
            }
        }

        /** Replaces the tier at [index]; an index outside the list is ignored. */
        fun setTier(
            index: Int,
            tier: TierDraft,
        ) {
            editDraft {
                val bounded = tier.copy(amountText = tier.amountText.take(PolicyLimits.MAX_AMOUNT_TEXT_LENGTH))
                if (index in tiers.indices) {
                    copy(tiers = tiers.mapIndexed { i, old -> if (i == index) bounded else old })
                } else {
                    this
                }
            }
        }

        fun removeTier(index: Int) {
            editDraft { copy(tiers = tiers.filterIndexed { i, _ -> i != index }) }
        }

        /** Immediate, independent of Save: the exception stops applying as soon as the repository writes. */
        fun removeException(
            kind: PolicyExceptionKind,
            selector: PolicySelector,
        ) {
            val packageName = session.value.target?.packageName ?: return
            val stored = repository.profiles.value.profiles[packageName]
            val listed =
                when (kind) {
                    PolicyExceptionKind.NOT_AMOUNT_FIELD -> stored?.notAmountFields
                    PolicyExceptionKind.NOT_ACTION_BUTTON -> stored?.notActionButtons
                }
            val wasListed = listed.orEmpty().any { it.selector == selector }
            session.update { it.copy(removeFailed = false) }
            viewModelScope.launch(ioDispatcher) {
                val result = repository.removeException(packageName, kind, selector)
                if (result is ProfileSaveResult.Saved) {
                    if (wasListed) events.logProfileEvent(ApprovalLogOutcome.EXCEPTION_REMOVED, packageName)
                } else if (result != ProfileSaveResult.NotFound) {
                    session.update { it.copy(removeFailed = true) }
                }
            }
        }

        fun dismissRemoveError() {
            session.update { it.copy(removeFailed = false) }
        }

        /** Saves a valid draft once: `upsert` for a new profile, the atomic `updateSettings` for a stored one. */
        fun save() {
            var draft: ProfileDraft? = null
            session.update { current ->
                draft = current.draft?.takeIf { current.saveStatus.allowsSave() && validateDraft(it).isEmpty() }
                if (draft != null) current.copy(saveStatus = SaveStatus.SAVING) else current
            }
            val toSave = draft ?: return
            viewModelScope.launch(ioDispatcher) {
                val result = repository.saveDraft(toSave)
                session.update { it.copy(saveStatus = result.toSaveStatus()) }
                if (result is ProfileSaveResult.Saved) {
                    val outcome =
                        if (result.created) {
                            ApprovalLogOutcome.PROFILE_CREATED
                        } else {
                            ApprovalLogOutcome.PROFILE_UPDATED
                        }
                    events.logProfileEvent(outcome, toSave.packageName)
                }
            }
        }

        /** Applies [transform] unless there is no draft or a save is running or done; clears a failed save. */
        private fun editDraft(transform: ProfileDraft.() -> ProfileDraft) {
            session.update { current ->
                val draft = current.draft
                if (draft == null || !current.saveStatus.allowsSave()) {
                    current
                } else {
                    val status = if (current.saveStatus.isFailure) SaveStatus.IDLE else current.saveStatus
                    current.copy(draft = draft.transform(), saveStatus = status)
                }
            }
        }

        private companion object {
            const val FLOW_TIMEOUT_MS = 5_000L
        }
    }

private fun SaveStatus.allowsSave(): Boolean = this != SaveStatus.SAVING && this != SaveStatus.SAVED

/** A stored readable profile keeps its exceptions through `updateSettings`; anything else is created by `upsert`. */
private suspend fun ApprovalProfileRepository.saveDraft(draft: ProfileDraft): ProfileSaveResult {
    val profile = draft.toProfile()
    return if (draft.packageName in profiles.value.profiles) {
        updateSettings(profile.packageName, profile.template, profile.rules, profile.maxAmount, profile.amountTiers)
    } else {
        upsert(profile)
    }
}

private fun ApprovalEventLogger.logProfileEvent(
    outcome: ApprovalLogOutcome,
    packageName: String,
) {
    log(ApprovalLogRecord(outcome, packageName = packageName))
}
