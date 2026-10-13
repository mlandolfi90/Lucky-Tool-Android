package com.danielealbano.androidremotecontrolmcp.policy.engine

import com.danielealbano.androidremotecontrolmcp.policy.amount.AmountParser
import com.danielealbano.androidremotecontrolmcp.policy.amount.AmountScan
import com.danielealbano.androidremotecontrolmcp.policy.model.AmountLimits
import com.danielealbano.androidremotecontrolmcp.policy.model.AmountTier
import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfile
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import java.math.BigDecimal

internal data class AmountOutcome(
    val mode: RuleMode,
    val reason: DecisionReason,
)

/**
 * Amount check of a profiled target: applies only when the profile has `maxAmount`, the subject carries
 * `amountText`, the target is not a password field and its selector is not in `notAmountFields`. Above
 * `maxAmount` (or past the parser caps) → FORBIDDEN; otherwise the first tier whose `upTo` covers the amount, and
 * an amount above the last tier keeps the last tier's mode (tiers never get weaker as the amount rises).
 */
internal object AmountRule {
    fun evaluate(
        amountText: String?,
        target: PolicyTarget,
        profile: ApprovalProfile,
    ): AmountOutcome? {
        val max = profile.maxAmount?.let(AmountLimits::parse)
        if (amountText == null || max == null || !counts(target, profile)) return null
        return outcomeFor(AmountParser.scan(amountText), max, profile.amountTiers)
    }

    private fun counts(
        target: PolicyTarget,
        profile: ApprovalProfile,
    ): Boolean = !target.isPassword && !TargetClassifier.isExcepted(target.selector, profile.notAmountFields)

    private fun outcomeFor(
        scan: AmountScan,
        max: BigDecimal,
        tiers: List<AmountTier>,
    ): AmountOutcome? {
        val amount = scan.max
        return when {
            scan.exceedsLimits || (amount != null && amount > max) -> {
                AmountOutcome(RuleMode.FORBIDDEN, DecisionReason.AMOUNT_ABOVE_MAX)
            }

            amount == null -> {
                null
            }

            else -> {
                tierFor(amount, tiers)?.let { AmountOutcome(it.mode, DecisionReason.AMOUNT_TIER) }
            }
        }
    }

    private fun tierFor(
        amount: BigDecimal,
        tiers: List<AmountTier>,
    ): AmountTier? =
        tiers.firstOrNull { tier -> AmountLimits.parse(tier.upTo)?.let { amount <= it } == true }
            ?: tiers.lastOrNull()
}
