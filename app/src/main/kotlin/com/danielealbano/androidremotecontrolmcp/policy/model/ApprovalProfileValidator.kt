package com.danielealbano.androidremotecontrolmcp.policy.model

import java.math.BigDecimal

/** Pure validation of an [ApprovalProfile]. An empty result means the profile can be saved and enforced. */
object ApprovalProfileValidator {
    fun validate(profile: ApprovalProfile): List<ProfileValidationError> {
        val errors = mutableSetOf<ProfileValidationError>()
        if (!isValidPackageName(profile.packageName)) errors += ProfileValidationError.PACKAGE_NAME_INVALID
        if (!PolicyActionType.entries.all { it in profile.rules }) errors += ProfileValidationError.RULES_INCOMPLETE
        errors += amountErrors(profile)
        errors += exceptionErrors(profile)
        return errors.sorted()
    }

    private fun amountErrors(profile: ApprovalProfile): Set<ProfileValidationError> {
        val maxText = profile.maxAmount
        val max = maxText?.let(AmountLimits::parse)
        val errors = mutableSetOf<ProfileValidationError>()
        if (maxText == null && profile.template == ProfileTemplate.MONEY_APP) {
            errors += ProfileValidationError.MAX_AMOUNT_REQUIRED
        }
        if (maxText != null && max == null) errors += ProfileValidationError.MAX_AMOUNT_INVALID
        if (profile.amountTiers.isNotEmpty()) errors += tierErrors(profile.amountTiers, maxText != null, max)
        return errors
    }

    private fun tierErrors(
        tiers: List<AmountTier>,
        maxConfigured: Boolean,
        max: BigDecimal?,
    ): Set<ProfileValidationError> {
        val errors = mutableSetOf<ProfileValidationError>()
        if (!maxConfigured) errors += ProfileValidationError.TIERS_REQUIRE_MAX_AMOUNT
        if (tiers.size > PolicyLimits.MAX_AMOUNT_TIERS) errors += ProfileValidationError.TOO_MANY_TIERS
        val limits = tiers.map { AmountLimits.parse(it.upTo) }
        if (limits.any { it == null }) errors += ProfileValidationError.TIER_AMOUNT_INVALID
        val parsed = limits.filterNotNull()
        if (parsed.zipWithNext().any { (lower, upper) -> lower >= upper }) {
            errors += ProfileValidationError.TIERS_NOT_ASCENDING
        }
        if (max != null && parsed.any { it > max }) errors += ProfileValidationError.TIER_ABOVE_MAX_AMOUNT
        if (tiers.zipWithNext().any { (lower, upper) -> lower.mode.isStricterThan(upper.mode) }) {
            errors += ProfileValidationError.TIER_MODES_DECREASING
        }
        return errors
    }

    private fun exceptionErrors(profile: ApprovalProfile): Set<ProfileValidationError> {
        val lists = listOf(profile.notAmountFields, profile.notActionButtons)
        val errors = mutableSetOf<ProfileValidationError>()
        if (lists.any { it.size > PolicyLimits.MAX_EXCEPTIONS_PER_LIST }) {
            errors += ProfileValidationError.TOO_MANY_EXCEPTIONS
        }
        if (lists.flatten().any { !isValidSelector(it.selector) }) {
            errors += ProfileValidationError.EXCEPTION_SELECTOR_INVALID
        }
        return errors
    }

    private fun isValidSelector(selector: PolicySelector): Boolean =
        selector.value.isNotBlank() && selector.value.length <= PolicyLimits.MAX_SELECTOR_VALUE_LENGTH

    private fun isValidPackageName(packageName: String): Boolean {
        val segments = packageName.split('.')
        return packageName.length <= PolicyLimits.MAX_PACKAGE_NAME_LENGTH &&
            segments.size > 1 &&
            segments.all(::isValidPackageSegment)
    }

    private fun isValidPackageSegment(segment: String): Boolean =
        segment.isNotEmpty() &&
            isAsciiLetter(segment.first()) &&
            segment.all { isAsciiLetter(it) || it in '0'..'9' || it == '_' }

    private fun isAsciiLetter(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z'
}
