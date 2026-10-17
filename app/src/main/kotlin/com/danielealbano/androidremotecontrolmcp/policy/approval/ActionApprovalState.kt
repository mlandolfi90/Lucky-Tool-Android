package com.danielealbano.androidremotecontrolmcp.policy.approval

/**
 * Lifecycle of an action approval; its wire code (`wireCode()`) is the status the gate and the approval tools return.
 * Kept apart from the OAuth `ApprovalState`, whose values and wire codes do not change.
 * - [EXPIRED]: not decided within [ActionApprovalPolicy.APPROVAL_WINDOW_MS], or approved too late; an unknown id too.
 * - [NOT_REQUIRED]: closed without a grant by a re-evaluation after an owner exception: the call now runs as it is.
 * - [ALREADY_USED]: approved, and its single-use grant was consumed by a call with the same fingerprint.
 */
enum class ActionApprovalState { PENDING, APPROVED, DENIED, EXPIRED, NOT_REQUIRED, ALREADY_USED }
