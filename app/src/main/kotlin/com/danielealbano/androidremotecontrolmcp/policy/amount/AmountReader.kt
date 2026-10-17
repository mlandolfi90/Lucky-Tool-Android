package com.danielealbano.androidremotecontrolmcp.policy.amount

import java.math.BigDecimal

/**
 * Value of one [RawNumber]; [overflow] = exponent above [AmountParser.MAX_EXPONENT] ([value] unscaled).
 * [toString] omits [value].
 */
internal data class AmountReading(
    val value: BigDecimal,
    val ambiguous: Boolean,
    val overflow: Boolean,
) {
    override fun toString(): String = "AmountReading(ambiguous=$ambiguous, overflow=$overflow)"
}

/**
 * Locale-independent reading: spaces and apostrophes always group; with both `.` and `,` the rightmost is the
 * decimal mark; a repeated mark groups; a single mark followed by exactly 3 digits is ambiguous and reads as the
 * larger value (grouping); any other single mark is the decimal mark.
 */
internal object AmountReader {
    fun read(raw: RawNumber): AmountReading {
        val mark = decimalMark(raw.separators, raw.groups.last().length)
        val base =
            if (mark == null || mark.ambiguous) {
                BigDecimal(raw.groups.joinToString(separator = ""))
            } else {
                decimalValue(raw.groups, mark.index)
            }
        val overflow = raw.exponent > AmountParser.MAX_EXPONENT
        val value = if (overflow) base else base.scaleByPowerOfTen(raw.exponent)
        return AmountReading(value, ambiguous = mark?.ambiguous == true, overflow = overflow)
    }

    private data class DecimalMark(
        val index: Int,
        val ambiguous: Boolean,
    )

    private fun decimalMark(
        separators: List<Char>,
        lastGroupLength: Int,
    ): DecimalMark? {
        val last = separators.lastOrNull()
        val decimalKind = last == AmountTokenizer.DOT || last == AmountTokenizer.COMMA
        if (!decimalKind || separators.count { it == last } > 1) return null
        val otherKind = if (last == AmountTokenizer.DOT) AmountTokenizer.COMMA else AmountTokenizer.DOT
        val ambiguous = otherKind !in separators && lastGroupLength == AmountTokenizer.GROUP_SIZE
        return DecimalMark(separators.lastIndex, ambiguous)
    }

    private fun decimalValue(
        groups: List<String>,
        separatorIndex: Int,
    ): BigDecimal {
        val integerPart = groups.subList(0, separatorIndex + 1).joinToString(separator = "")
        val fraction = groups.subList(separatorIndex + 1, groups.size).joinToString(separator = "")
        return BigDecimal("$integerPart.$fraction")
    }
}
