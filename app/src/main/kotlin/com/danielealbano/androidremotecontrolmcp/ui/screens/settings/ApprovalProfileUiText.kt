package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.annotation.StringRes
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileValidationError
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ManualPackageError
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.SaveStatus

/** Template preselected in the add flow's template dialog: the stricter one. */
internal val DEFAULT_TEMPLATE = ProfileTemplate.MONEY_APP

/** What each action type covers, shown under its rule in the editor (the type labels are `PolicyApprovalLabels`). */
@StringRes
internal fun PolicyActionType.descriptionRes(): Int =
    when (this) {
        PolicyActionType.READ -> R.string.approval_profile_action_read_description
        PolicyActionType.NAVIGATE -> R.string.approval_profile_action_navigate_description
        PolicyActionType.TYPE -> R.string.approval_profile_action_type_description
        PolicyActionType.ACTION_BUTTON -> R.string.approval_profile_action_button_description
    }

@StringRes
internal fun ProfileTemplate.labelRes(): Int =
    when (this) {
        ProfileTemplate.MONEY_APP -> R.string.approval_template_money
        ProfileTemplate.COMMON_APP -> R.string.approval_template_common
    }

@StringRes
internal fun ProfileTemplate.descriptionRes(): Int =
    when (this) {
        ProfileTemplate.MONEY_APP -> R.string.approval_template_money_description
        ProfileTemplate.COMMON_APP -> R.string.approval_template_common_description
    }

@StringRes
internal fun ProfileValidationError.messageRes(): Int =
    when (this) {
        ProfileValidationError.PACKAGE_NAME_INVALID -> R.string.approval_profile_error_package_name_invalid
        ProfileValidationError.RULES_INCOMPLETE -> R.string.approval_profile_error_rules_incomplete
        ProfileValidationError.MAX_AMOUNT_REQUIRED -> R.string.approval_profile_error_max_amount_required
        ProfileValidationError.MAX_AMOUNT_INVALID -> R.string.approval_profile_error_max_amount_invalid
        ProfileValidationError.TIERS_REQUIRE_MAX_AMOUNT -> R.string.approval_profile_error_tiers_require_max_amount
        ProfileValidationError.TOO_MANY_TIERS -> R.string.approval_profile_error_too_many_tiers
        ProfileValidationError.TIER_AMOUNT_INVALID -> R.string.approval_profile_error_tier_amount_invalid
        ProfileValidationError.TIERS_NOT_ASCENDING -> R.string.approval_profile_error_tiers_not_ascending
        ProfileValidationError.TIER_ABOVE_MAX_AMOUNT -> R.string.approval_profile_error_tier_above_max_amount
        ProfileValidationError.TIER_MODES_DECREASING -> R.string.approval_profile_error_tier_modes_decreasing
        ProfileValidationError.TOO_MANY_EXCEPTIONS -> R.string.approval_profile_error_too_many_exceptions
        ProfileValidationError.EXCEPTION_SELECTOR_INVALID -> R.string.approval_profile_error_exception_selector_invalid
    }

@StringRes
internal fun ManualPackageError.messageRes(): Int =
    when (this) {
        ManualPackageError.EMPTY -> R.string.approval_profiles_manual_error_empty
        ManualPackageError.INVALID_FORMAT -> R.string.approval_profiles_manual_error_format
        ManualPackageError.OWN_PACKAGE -> R.string.approval_profiles_manual_error_own
        ManualPackageError.ALREADY_PROFILED -> R.string.approval_profiles_manual_error_exists
    }

/** Message of a failed save; null for the statuses that are not failures. */
@StringRes
internal fun SaveStatus.failureMessageRes(): Int? =
    when (this) {
        SaveStatus.IDLE, SaveStatus.SAVING, SaveStatus.SAVED -> null
        SaveStatus.STORE_UNAVAILABLE -> R.string.approval_profile_save_store_unavailable
        SaveStatus.PROFILE_LIMIT_REACHED -> R.string.approval_profile_save_limit_reached
        SaveStatus.NOT_FOUND -> R.string.approval_profile_save_not_found
        SaveStatus.REJECTED -> R.string.approval_profile_save_rejected
    }
