package com.danielealbano.androidremotecontrolmcp.policy.approval

import androidx.annotation.StringRes
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.policy.engine.DecisionReason
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode

/**
 * The single source of user-visible labels for [PolicyActionType] and [RuleMode] (approval notification, approval
 * screen, profile editor), plus the approval screen's [DecisionReason] texts. Every `when` is exhaustive without
 * `else`: a new enum value fails to compile until it gets a label.
 */
object PolicyApprovalLabels {
    @StringRes
    fun actionType(type: PolicyActionType): Int =
        when (type) {
            PolicyActionType.READ -> R.string.policy_action_type_read
            PolicyActionType.NAVIGATE -> R.string.policy_action_type_navigate
            PolicyActionType.TYPE -> R.string.policy_action_type_type
            PolicyActionType.ACTION_BUTTON -> R.string.policy_action_type_action_button
        }

    @StringRes
    fun ruleMode(mode: RuleMode): Int =
        when (mode) {
            RuleMode.RUN -> R.string.policy_rule_mode_run
            RuleMode.ASK_IN_CHAT -> R.string.policy_rule_mode_ask_in_chat
            RuleMode.NOTIFY -> R.string.policy_rule_mode_notify
            RuleMode.FORBIDDEN -> R.string.policy_rule_mode_forbidden
        }

    @StringRes
    fun reason(reason: DecisionReason): Int =
        when (reason) {
            DecisionReason.NO_PROFILE -> R.string.policy_approval_reason_no_profile
            DecisionReason.OWN_PACKAGE -> R.string.policy_approval_reason_own_package
            DecisionReason.RULE -> R.string.policy_approval_reason_rule
            DecisionReason.AMOUNT_TIER -> R.string.policy_approval_reason_amount_tier
            DecisionReason.AMOUNT_ABOVE_MAX -> R.string.policy_approval_reason_amount_above_max
            DecisionReason.UNKNOWN_TARGET -> R.string.policy_approval_reason_unknown_target
            DecisionReason.ACTION_KEYWORD -> R.string.policy_approval_reason_action_keyword
            DecisionReason.PROFILE_UNREADABLE -> R.string.policy_approval_reason_profile_unreadable
        }
}
