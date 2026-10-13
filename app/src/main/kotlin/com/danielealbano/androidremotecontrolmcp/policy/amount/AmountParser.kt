package com.danielealbano.androidremotecontrolmcp.policy.amount

import java.math.BigDecimal
import java.text.Normalizer

/**
 * Finds the largest number in a text (decision "A": every number typed in a profiled app counts as an amount).
 * Pure and JVM-testable. The text is NFKC-normalized first (full-width digits and punctuation, superscripts), then
 * any Unicode decimal digit counts (`Character.digit`). Words (`mil`, `5k`) are not numbers.
 */
object AmountParser {
    /** Longest text scanned; longer input returns `exceedsLimits = true`. */
    const val MAX_INPUT_LENGTH = 10_000

    /** Largest exponent accepted in scientific notation; larger returns `exceedsLimits = true`. */
    const val MAX_EXPONENT = 30

    private const val NORMALIZED_LENGTH_FACTOR = 4
    private const val MAX_NORMALIZED_LENGTH = MAX_INPUT_LENGTH * NORMALIZED_LENGTH_FACTOR
    private val EXCEEDED = AmountScan(max = null, ambiguous = false, exceedsLimits = true)

    fun scan(text: String): AmountScan {
        val normalized =
            if (text.length <= MAX_INPUT_LENGTH) Normalizer.normalize(text, Normalizer.Form.NFKC) else null
        if (normalized == null || normalized.length > MAX_NORMALIZED_LENGTH) return EXCEEDED
        return summarize(AmountTokenizer(normalized).tokenize().map(AmountReader::read))
    }

    private fun summarize(readings: List<AmountReading>): AmountScan {
        var max: BigDecimal? = null
        var ambiguous = false
        for (reading in readings.filterNot { it.overflow }) {
            val current = max
            if (current == null || reading.value > current) {
                max = reading.value
                ambiguous = reading.ambiguous
            } else if (reading.value.compareTo(current) == 0) {
                ambiguous = ambiguous && reading.ambiguous
            }
        }
        return AmountScan(max, ambiguous, exceedsLimits = readings.any { it.overflow })
    }
}
