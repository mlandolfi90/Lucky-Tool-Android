package com.danielealbano.androidremotecontrolmcp.policy.approval

import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode

/** Bounds and timings of the in-memory action-approval ledger. */
object ActionApprovalPolicy {
    /** How long a pending approval stays open. */
    const val APPROVAL_WINDOW_MS = 300_000L

    /** How long an approval grant can be consumed after it is approved. */
    const val GRANT_TTL_MS = 120_000L

    /** Maximum simultaneous PENDING entries; a new distinct request beyond it is refused. */
    const val MAX_PENDING = 10

    /** Maximum entries (any state) kept for status reads; the oldest non-PENDING one is dropped first. */
    const val MAX_TRACKED_ENTRIES = 100

    /** Maximum unconsumed grants; the oldest is dropped first. */
    const val MAX_GRANTS = 100

    /** Error code of a refused request (the gate returns it with isError=true). */
    const val TOO_MANY_PENDING_CODE = "too_many_pending"

    /** Modes that create a pending approval. */
    val APPROVAL_METHODS: Set<RuleMode> = setOf(RuleMode.ASK_IN_CHAT, RuleMode.NOTIFY)

    /** Strength of a grant approved by the owner on the device (satisfies ASK_IN_CHAT and NOTIFY). */
    val OWNER_DEVICE_GRANT_STRENGTH: RuleMode = RuleMode.NOTIFY

    /** Strength of a grant confirmed through the chat tool (satisfies ASK_IN_CHAT only). */
    val CHAT_GRANT_STRENGTH: RuleMode = RuleMode.ASK_IN_CHAT
}
