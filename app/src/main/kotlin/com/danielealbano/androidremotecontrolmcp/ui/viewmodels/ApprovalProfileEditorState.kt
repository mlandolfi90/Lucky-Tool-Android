package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileSaveResult
import com.danielealbano.androidremotecontrolmcp.policy.model.AmountLimits
import com.danielealbano.androidremotecontrolmcp.policy.model.AmountTier
import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfile
import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfileValidator
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyException
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileValidationError
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode

/** One editable amount tier; [amountText] is the raw owner input. [toString] omits it (an amount limit). */
data class TierDraft(
    val amountText: String,
    val mode: RuleMode,
) {
    override fun toString(): String = "TierDraft(mode=$mode, amountLength=${amountText.length})"
}

/** Editable copy of the five owner settings of an [ApprovalProfile]; [toString] omits the amount limits. */
data class ProfileDraft(
    val packageName: String,
    val template: ProfileTemplate,
    val rules: Map<PolicyActionType, RuleMode>,
    val maxAmountText: String,
    val tiers: List<TierDraft>,
) {
    override fun toString(): String =
        "ProfileDraft(packageName=$packageName, template=$template, rules=$rules, " +
            "maxAmountLength=${maxAmountText.length}, tiers=$tiers)"
}

/** Save progress; every [isFailure] value is cleared by the next edit. */
enum class SaveStatus(
    val isFailure: Boolean,
) {
    IDLE(false),
    SAVING(false),
    SAVED(false),
    STORE_UNAVAILABLE(true),
    PROFILE_LIMIT_REACHED(true),
    NOT_FOUND(true),
    REJECTED(true),
}

/** State of the profile editor; the exception lists always come from the live snapshot. */
data class ProfileEditorUiState(
    val packageName: String = "",
    val loaded: Boolean = false,
    val draft: ProfileDraft? = null,
    val isNew: Boolean = false,
    val unreadable: Boolean = false,
    val storeUnreadable: Boolean = false,
    val notAmountFields: List<PolicyException> = emptyList(),
    val notActionButtons: List<PolicyException> = emptyList(),
    val errors: List<ProfileValidationError> = emptyList(),
    val saveStatus: SaveStatus = SaveStatus.IDLE,
    val removeFailed: Boolean = false,
) {
    val canSave: Boolean
        get() = draft != null && errors.isEmpty() && saveStatus != SaveStatus.SAVING && saveStatus != SaveStatus.SAVED
}

/** The package the editor is bound to; [template] non-null = a new profile from that template. */
internal data class EditorTarget(
    val packageName: String,
    val template: ProfileTemplate?,
)

/** Everything the editor ViewModel owns, updated atomically as one value. */
internal data class EditorSession(
    val target: EditorTarget? = null,
    val loaded: Boolean = false,
    val draft: ProfileDraft? = null,
    val saveStatus: SaveStatus = SaveStatus.IDLE,
    val removeFailed: Boolean = false,
)

/**
 * `updatedAtMs` of a profile built from a draft: the repository stamps every write with its own clock, so this
 * value is never stored.
 */
private const val DRAFT_TIMESTAMP_MS = 0L

/** True when [text] (trimmed) is a valid amount limit: plain decimal, `.` as decimal mark, up to 8 decimals, > 0. */
internal fun isValidAmountInput(text: String): Boolean = AmountLimits.parse(text.trim()) != null

/** What a valid amount input is stored as, when that differs from the typed text (`1.500` → `1.5`); else null. */
internal fun amountReadingOrNull(text: String): String? {
    val trimmed = text.trim()
    val canonical = AmountLimits.parse(trimmed)?.let(AmountLimits::canonical)
    return canonical?.takeIf { it != trimmed }
}

/** Canonical stored form of a valid limit; otherwise the trimmed text, which the validator then reports. */
private fun amountForProfile(text: String): String {
    val trimmed = text.trim()
    return AmountLimits.parse(trimmed)?.let(AmountLimits::canonical) ?: trimmed
}

internal fun ApprovalProfile.toDraft(): ProfileDraft =
    ProfileDraft(
        packageName = packageName,
        template = template,
        rules = rules,
        maxAmountText = maxAmount.orEmpty(),
        tiers = amountTiers.map { TierDraft(it.upTo, it.mode) },
    )

/** The draft as a profile with empty exception lists: the repository keeps the stored ones on every write. */
internal fun ProfileDraft.toProfile(): ApprovalProfile =
    ApprovalProfile(
        packageName = packageName,
        template = template,
        rules = rules,
        maxAmount = maxAmountText.takeIf { it.isNotBlank() }?.let(::amountForProfile),
        amountTiers = tiers.map { AmountTier(upTo = amountForProfile(it.amountText), mode = it.mode) },
        notAmountFields = emptyList(),
        notActionButtons = emptyList(),
        updatedAtMs = DRAFT_TIMESTAMP_MS,
    )

internal fun validateDraft(draft: ProfileDraft): List<ProfileValidationError> {
    val profile = draft.toProfile()
    return ApprovalProfileValidator.validate(profile)
}

internal fun ProfileSaveResult.toSaveStatus(): SaveStatus =
    when (this) {
        is ProfileSaveResult.Saved -> SaveStatus.SAVED
        is ProfileSaveResult.Invalid -> SaveStatus.REJECTED
        ProfileSaveResult.ExceptionLimitReached -> SaveStatus.REJECTED
        ProfileSaveResult.ProfileLimitReached -> SaveStatus.PROFILE_LIMIT_REACHED
        ProfileSaveResult.NotFound -> SaveStatus.NOT_FOUND
        ProfileSaveResult.StoreUnavailable -> SaveStatus.STORE_UNAVAILABLE
    }

internal fun buildEditorState(
    session: EditorSession,
    snapshot: ProfilesSnapshot,
    storeUnreadable: Boolean,
): ProfileEditorUiState {
    val target = session.target ?: return ProfileEditorUiState()
    val stored = snapshot.profiles[target.packageName]
    return ProfileEditorUiState(
        packageName = target.packageName,
        loaded = session.loaded,
        draft = session.draft,
        isNew = stored == null,
        unreadable = stored == null && target.packageName in snapshot.unreadable,
        storeUnreadable = storeUnreadable,
        notAmountFields = stored?.notAmountFields.orEmpty(),
        notActionButtons = stored?.notActionButtons.orEmpty(),
        errors = session.draft?.let(::validateDraft).orEmpty(),
        saveStatus = session.saveStatus,
        removeFailed = session.removeFailed,
    )
}
