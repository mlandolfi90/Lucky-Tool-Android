package com.danielealbano.androidremotecontrolmcp.policy.model

/** Caps shared by the validator, the storage and the selector builders. */
object PolicyLimits {
    /** Maximum stored profile keys, readable and unreadable together. */
    const val MAX_PROFILES = 100

    /** Maximum amount tiers in one profile. */
    const val MAX_AMOUNT_TIERS = 5

    /** Maximum entries in each exception list of one profile. */
    const val MAX_EXCEPTIONS_PER_LIST = 100

    /** Maximum length of a [PolicySelector.value]. */
    const val MAX_SELECTOR_VALUE_LENGTH = 256

    /** Maximum length of a stored amount limit (`maxAmount`, `AmountTier.upTo`). */
    const val MAX_AMOUNT_TEXT_LENGTH = 30

    /** Maximum length of an Android package name. */
    const val MAX_PACKAGE_NAME_LENGTH = 255
}
