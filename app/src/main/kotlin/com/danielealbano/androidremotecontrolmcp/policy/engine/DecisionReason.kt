package com.danielealbano.androidremotecontrolmcp.policy.engine

/** Why the engine chose a mode. Codes are logged and returned to the agent; they never carry values. */
enum class DecisionReason {
    NO_PROFILE,
    OWN_PACKAGE,
    RULE,
    AMOUNT_TIER,
    AMOUNT_ABOVE_MAX,
    UNKNOWN_TARGET,
    ACTION_KEYWORD,
    PROFILE_UNREADABLE,
}
