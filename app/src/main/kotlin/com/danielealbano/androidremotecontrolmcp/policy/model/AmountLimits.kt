package com.danielealbano.androidremotecontrolmcp.policy.model

import java.math.BigDecimal

/**
 * Parses and formats the amount limits stored in a profile. Only plain ASCII decimals (`1500`, `1500.5`,
 * `0.00000001`) are accepted: `.` as the only decimal mark, at most [MAX_DECIMALS] decimals, no exponent, no
 * grouping, no sign, so a stored limit can never be expensive to compare or print.
 */
object AmountLimits {
    /** Most decimals a limit may carry (covers 8-decimal currencies). */
    const val MAX_DECIMALS = 8

    /** Returns the value of a valid, strictly positive plain decimal, or null. */
    fun parse(text: String): BigDecimal? {
        val plain = text.length <= PolicyLimits.MAX_AMOUNT_TEXT_LENGTH && isPlainDecimal(text)
        val value = if (plain) BigDecimal(text) else null
        return value?.takeIf { it.signum() > 0 }
    }

    /** Canonical stored form: `1500.50` → `1500.5`, `1E+3` → `1000`. */
    fun canonical(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    private fun isPlainDecimal(text: String): Boolean {
        val dot = text.indexOf('.')
        val integerPart = if (dot < 0) text else text.substring(0, dot)
        val fraction = if (dot < 0) "" else text.substring(dot + 1)
        val integerValid = integerPart.isNotEmpty() && integerPart.all(::isAsciiDigit)
        val fractionValid = dot < 0 || (fraction.length in 1..MAX_DECIMALS && fraction.all(::isAsciiDigit))
        return integerValid && fractionValid
    }

    private fun isAsciiDigit(char: Char): Boolean = char in '0'..'9'
}
