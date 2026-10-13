package com.danielealbano.androidremotecontrolmcp.policy.model

/** Why a profile cannot be saved. Declaration order is the order the validator reports them in. */
enum class ProfileValidationError {
    PACKAGE_NAME_INVALID,
    RULES_INCOMPLETE,
    MAX_AMOUNT_REQUIRED,
    MAX_AMOUNT_INVALID,
    TIERS_REQUIRE_MAX_AMOUNT,
    TOO_MANY_TIERS,
    TIER_AMOUNT_INVALID,
    TIERS_NOT_ASCENDING,
    TIER_ABOVE_MAX_AMOUNT,
    TIER_MODES_DECREASING,
    TOO_MANY_EXCEPTIONS,
    EXCEPTION_SELECTOR_INVALID,
}
