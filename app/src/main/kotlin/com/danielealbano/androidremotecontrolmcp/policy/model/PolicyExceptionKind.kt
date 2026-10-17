package com.danielealbano.androidremotecontrolmcp.policy.model

/** Which exception list of an [ApprovalProfile] an exception belongs to. */
enum class PolicyExceptionKind {
    /** "This field is not an amount": [ApprovalProfile.notAmountFields]. */
    NOT_AMOUNT_FIELD,

    /** "This is not an action button": [ApprovalProfile.notActionButtons]. */
    NOT_ACTION_BUTTON,
}
