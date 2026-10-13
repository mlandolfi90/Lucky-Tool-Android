package com.danielealbano.androidremotecontrolmcp.policy.amount

import java.math.BigDecimal

/**
 * Result of [AmountParser.scan]. Amount values are sensitive: never log them.
 *
 * @property max the largest number found; null when the text holds no number.
 * @property ambiguous true when [max] can only be read as the larger of two readings (`1.500` → 1500, not 1.5).
 * @property exceedsLimits true when the input or an exponent exceeded the parser caps; callers MUST treat it as an
 *   amount above every limit.
 */
data class AmountScan(
    val max: BigDecimal?,
    val ambiguous: Boolean,
    val exceedsLimits: Boolean = false,
) {
    override fun toString(): String =
        "AmountScan(hasMax=${max != null}, ambiguous=$ambiguous, " +
            "exceedsLimits=$exceedsLimits)"
}
