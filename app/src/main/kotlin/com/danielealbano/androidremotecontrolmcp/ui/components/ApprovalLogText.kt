package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallMarker
import com.danielealbano.androidremotecontrolmcp.policy.engine.DecisionReason
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogMessage
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogOutcome
import com.danielealbano.androidremotecontrolmcp.policy.subject.BlockCause

/**
 * The text a log row shows: APPROVAL messages and the profile gate's TOOL_CALL markers localized, everything else as
 * stored.
 */
@Composable
fun logMessageText(
    type: ServerLogEntry.Type,
    message: String,
): String {
    val markerRes = if (type == ServerLogEntry.Type.TOOL_CALL) ApprovalLogLabels.toolCallMarker(message) else null
    return when {
        type == ServerLogEntry.Type.APPROVAL -> approvalLogText(message)
        markerRes != null -> stringResource(markerRes)
        else -> message
    }
}

/** Localized text of an APPROVAL log message; a message that does not decode is shown as stored. */
@Composable
fun approvalLogText(message: String): String {
    val record = remember(message) { ApprovalLogMessage.decode(message) }
    val reasonRes =
        record?.reason?.let(ApprovalLogLabels::reason) ?: record?.blockCause?.let(ApprovalLogLabels::cause)
    return when {
        record == null -> {
            message
        }

        record.packageName == null -> {
            stringResource(ApprovalLogLabels.outcome(record.outcome))
        }

        reasonRes == null -> {
            stringResource(
                R.string.approval_log_line_app,
                stringResource(ApprovalLogLabels.outcome(record.outcome)),
                record.packageName,
            )
        }

        else -> {
            stringResource(
                R.string.approval_log_line_full,
                stringResource(ApprovalLogLabels.outcome(record.outcome)),
                record.packageName,
                stringResource(reasonRes),
            )
        }
    }
}

/**
 * String resources of the APPROVAL log codes and of the gate's TOOL_CALL markers. Reasons and causes use exhaustive
 * `when`s; the 15 outcomes use a map, and `ApprovalLogLabelsTest` fails when an outcome has no entry.
 */
internal object ApprovalLogLabels {
    private val OUTCOMES: Map<ApprovalLogOutcome, Int> =
        mapOf(
            ApprovalLogOutcome.APPROVED to R.string.approval_log_outcome_approved,
            ApprovalLogOutcome.DENIED to R.string.approval_log_outcome_denied,
            ApprovalLogOutcome.EXPIRED to R.string.approval_log_outcome_expired,
            ApprovalLogOutcome.FORBIDDEN to R.string.approval_log_outcome_forbidden,
            ApprovalLogOutcome.NEEDS_CONFIRMATION to R.string.approval_log_outcome_needs_confirmation,
            ApprovalLogOutcome.AWAITING_APPROVAL to R.string.approval_log_outcome_awaiting_approval,
            ApprovalLogOutcome.TOO_MANY_PENDING to R.string.approval_log_outcome_too_many_pending,
            ApprovalLogOutcome.REEVALUATED to R.string.approval_log_outcome_reevaluated,
            ApprovalLogOutcome.OWNER_APPROVED to R.string.approval_log_outcome_owner_approved,
            ApprovalLogOutcome.OWNER_DENIED to R.string.approval_log_outcome_owner_denied,
            ApprovalLogOutcome.EXCEPTION_ADDED to R.string.approval_log_outcome_exception_added,
            ApprovalLogOutcome.EXCEPTION_REMOVED to R.string.approval_log_outcome_exception_removed,
            ApprovalLogOutcome.PROFILE_CREATED to R.string.approval_log_outcome_profile_created,
            ApprovalLogOutcome.PROFILE_UPDATED to R.string.approval_log_outcome_profile_updated,
            ApprovalLogOutcome.PROFILE_DELETED to R.string.approval_log_outcome_profile_deleted,
        )

    private val TOOL_CALL_MARKERS: Map<String, Int> =
        mapOf(
            ToolCallMarker.FORBIDDEN to R.string.approval_log_marker_forbidden,
            ToolCallMarker.NEEDS_CONFIRMATION to R.string.approval_log_marker_needs_confirmation,
            ToolCallMarker.AWAITING_APPROVAL to R.string.approval_log_marker_awaiting_approval,
            ToolCallMarker.DENIED to R.string.approval_log_marker_denied,
        )

    @StringRes
    fun outcome(outcome: ApprovalLogOutcome): Int = OUTCOMES.getValue(outcome)

    /** The label of a TOOL_CALL marker written by the profile gate; null for any other message (shown as stored). */
    @StringRes
    fun toolCallMarker(message: String): Int? = TOOL_CALL_MARKERS[message]

    @StringRes
    fun reason(reason: DecisionReason): Int =
        when (reason) {
            DecisionReason.NO_PROFILE -> R.string.approval_log_reason_no_profile
            DecisionReason.OWN_PACKAGE -> R.string.approval_log_reason_own_package
            DecisionReason.RULE -> R.string.approval_log_reason_rule
            DecisionReason.AMOUNT_TIER -> R.string.approval_log_reason_amount_tier
            DecisionReason.AMOUNT_ABOVE_MAX -> R.string.approval_log_reason_amount_above_max
            DecisionReason.UNKNOWN_TARGET -> R.string.approval_log_reason_unknown_target
            DecisionReason.ACTION_KEYWORD -> R.string.approval_log_reason_action_keyword
            DecisionReason.PROFILE_UNREADABLE -> R.string.approval_log_reason_profile_unreadable
        }

    @StringRes
    fun cause(cause: BlockCause): Int =
        when (cause) {
            BlockCause.TARGET_UNRESOLVED -> R.string.approval_log_cause_target_unresolved
            BlockCause.IME_INPUT_IN_PROFILED_APP -> R.string.approval_log_cause_ime_input
            BlockCause.BACKGROUND_COMPONENT_IN_PROFILED_APP -> R.string.approval_log_cause_background_component
            BlockCause.UNRESOLVED_IMPLICIT_INTENT -> R.string.approval_log_cause_unresolved_implicit_intent
        }
}
