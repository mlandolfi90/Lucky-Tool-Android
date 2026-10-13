package com.danielealbano.androidremotecontrolmcp.policy.model

import kotlinx.serialization.Serializable

/**
 * Per-app approval profile. [maxAmount] null = no amount limit; when set, every number in a typed field's final
 * value counts as an amount (except password fields and [notAmountFields]). [toString] omits [maxAmount] and
 * selector values so a stray log line cannot leak them.
 */
@Serializable
data class ApprovalProfile(
    val packageName: String,
    val template: ProfileTemplate,
    val rules: Map<PolicyActionType, RuleMode>,
    val maxAmount: String?,
    val amountTiers: List<AmountTier>,
    val notAmountFields: List<PolicyException>,
    val notActionButtons: List<PolicyException>,
    val updatedAtMs: Long,
) {
    override fun toString(): String =
        "ApprovalProfile(packageName=$packageName, template=$template, rules=$rules, " +
            "hasMaxAmount=${maxAmount != null}, amountTiers=${amountTiers.size}, " +
            "notAmountFields=${notAmountFields.size}, notActionButtons=${notActionButtons.size}, " +
            "updatedAtMs=$updatedAtMs)"
}
