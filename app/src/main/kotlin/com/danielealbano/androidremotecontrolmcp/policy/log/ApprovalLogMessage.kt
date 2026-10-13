package com.danielealbano.androidremotecontrolmcp.policy.log

import com.danielealbano.androidremotecontrolmcp.policy.engine.DecisionReason
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyDecision
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.policy.subject.BlockCause
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectResolution
import com.danielealbano.androidremotecontrolmcp.policy.wireCode

/**
 * What happened to a gated call, an approval or a profile. Codes are stored on disk: never rename. Writers: the gate
 * (APPROVED … TOO_MANY_PENDING, NOT_REQUIRED, ALREADY_USED), the re-evaluator (REEVALUATED), the approval UI
 * (OWNER_APPROVED, OWNER_DENIED, EXPIRED for a late approval, EXCEPTION_ADDED), the profile editor
 * (EXCEPTION_REMOVED, PROFILE_*).
 */
enum class ApprovalLogOutcome {
    APPROVED,
    DENIED,
    EXPIRED,
    FORBIDDEN,
    NEEDS_CONFIRMATION,
    AWAITING_APPROVAL,
    TOO_MANY_PENDING,
    REEVALUATED,
    OWNER_APPROVED,
    OWNER_DENIED,
    EXCEPTION_ADDED,
    EXCEPTION_REMOVED,
    PROFILE_CREATED,
    PROFILE_UPDATED,
    PROFILE_DELETED,
    NOT_REQUIRED,
    ALREADY_USED,
}

/**
 * One APPROVAL log entry: codes and the package only — no field can hold an amount, field text, selector value or
 * approval id. At most one of [reason] (engine decision) and [blockCause] (subject resolution) is set.
 */
data class ApprovalLogRecord(
    val outcome: ApprovalLogOutcome,
    val actionType: PolicyActionType? = null,
    val mode: RuleMode? = null,
    val reason: DecisionReason? = null,
    val blockCause: BlockCause? = null,
    val packageName: String? = null,
)

/** The record of an engine decision (also used by the approval UI with `PendingAction.decision`). */
fun PolicyDecision.toLogRecord(outcome: ApprovalLogOutcome): ApprovalLogRecord =
    ApprovalLogRecord(
        outcome = outcome,
        actionType = actionType,
        mode = mode,
        reason = reason,
        packageName = packageName,
    )

/** The record of a call blocked during subject resolution (always FORBIDDEN). */
fun SubjectResolution.Blocked.toLogRecord(): ApprovalLogRecord =
    ApprovalLogRecord(
        outcome = ApprovalLogOutcome.FORBIDDEN,
        actionType = actionType,
        mode = RuleMode.FORBIDDEN,
        blockCause = cause,
        packageName = packageName,
    )

/**
 * `<outcome> action=<code> mode=<code> reason=<code> app=<package>`; absent fields are omitted and `app` is last,
 * because the server log truncates the end of a message (`ServerLogSegmentedStore.MAX_ENTRY_DATA_BYTES`).
 * `reason` holds a [DecisionReason] code or a [BlockCause.code] (the two code sets do not overlap).
 */
object ApprovalLogMessage {
    private const val SEPARATOR = " "
    private const val ASSIGN = '='
    private const val KEY_ACTION = "action"
    private const val KEY_MODE = "mode"
    private const val KEY_REASON = "reason"
    private const val KEY_APP = "app"

    fun encode(record: ApprovalLogRecord): String =
        buildList {
            add(record.outcome.wireCode())
            record.actionType?.let { add("$KEY_ACTION$ASSIGN${it.wireCode()}") }
            record.mode?.let { add("$KEY_MODE$ASSIGN${it.wireCode()}") }
            (record.reason?.wireCode() ?: record.blockCause?.code)?.let { add("$KEY_REASON$ASSIGN$it") }
            record.packageName?.let { add("$KEY_APP$ASSIGN$it") }
        }.joinToString(SEPARATOR)

    /** Null when [message] does not start with an [ApprovalLogOutcome] code; unknown keys and codes are ignored. */
    fun decode(message: String): ApprovalLogRecord? {
        val tokens = message.split(SEPARATOR)
        val outcome = ApprovalLogOutcome.entries.byWireCode(tokens.first()) ?: return null
        val fields = tokens.drop(1).associate { it.substringBefore(ASSIGN) to it.substringAfter(ASSIGN, "") }
        val reasonCode = fields[KEY_REASON]
        return ApprovalLogRecord(
            outcome = outcome,
            actionType = PolicyActionType.entries.byWireCode(fields[KEY_ACTION]),
            mode = RuleMode.entries.byWireCode(fields[KEY_MODE]),
            reason = DecisionReason.entries.byWireCode(reasonCode),
            blockCause = BlockCause.entries.firstOrNull { it.code == reasonCode },
            packageName = fields[KEY_APP]?.takeIf { it.isNotEmpty() },
        )
    }

    private fun <E : Enum<E>> List<E>.byWireCode(code: String?): E? {
        val wanted = code ?: return null
        return firstOrNull { it.wireCode() == wanted }
    }
}
