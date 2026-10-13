package com.danielealbano.androidremotecontrolmcp.policy.amount

/**
 * One number candidate: ASCII digit groups, the canonical separators between them, and its clamped exponent.
 * [toString] omits the digits (amounts are sensitive).
 */
internal data class RawNumber(
    val groups: List<String>,
    val separators: List<Char>,
    val exponent: Int,
) {
    override fun toString(): String =
        "RawNumber(groups=${groups.size}, separators=${separators.size}, " +
            "hasExponent=${exponent != 0})"
}

/**
 * Single-pass, regex-free tokenizer over code points, linear in the input: a space look-ahead reads at most
 * [GROUP_SIZE] + 1 code points and every other code point is consumed once. Signs, parentheses, currency symbols
 * and letters are skipped, so `-200` and `(500)` read as their absolute value and `5k` reads as 5.
 */
internal class AmountTokenizer(
    text: String,
) {
    private val codePoints: IntArray = text.codePoints().toArray()
    private var pos = 0

    fun tokenize(): List<RawNumber> {
        val numbers = mutableListOf<RawNumber>()
        while (pos < codePoints.size) {
            if (digitAt(pos) >= 0) {
                numbers += readNumber()
            } else {
                pos++
            }
        }
        return numbers
    }

    private fun readNumber(): RawNumber {
        val groups = mutableListOf(readDigits())
        val separators = mutableListOf<Char>()
        var separator = separatorAhead(groups.last().length, firstGroup = true)
        while (separator != null) {
            separators += separator
            pos++
            groups += readDigits()
            separator = separatorAhead(groups.last().length, firstGroup = false)
        }
        return RawNumber(groups, separators, readExponent())
    }

    private fun readDigits(): String {
        val builder = StringBuilder()
        var digit = digitAt(pos)
        while (digit >= 0) {
            builder.append('0' + digit)
            pos++
            digit = digitAt(pos)
        }
        return builder.toString()
    }

    /** The canonical separator at [pos] when it continues the current number, else null. */
    private fun separatorAhead(
        groupLength: Int,
        firstGroup: Boolean,
    ): Char? {
        if (digitAt(pos + 1) < 0) return null
        return when (val kind = canonicalSeparator(codePoints[pos])) {
            SPACE -> if (spaceJoins(groupLength, firstGroup)) SPACE else null
            else -> kind
        }
    }

    /** Spaces join only thousands groups: `1 500` joins, `4111 1111` (card groups of 4) does not. */
    private fun spaceJoins(
        groupLength: Int,
        firstGroup: Boolean,
    ): Boolean {
        val currentGroupFits = groupLength == GROUP_SIZE || (firstGroup && groupLength < GROUP_SIZE)
        return currentGroupFits && digitRunLength(pos + 1) == GROUP_SIZE
    }

    private fun digitRunLength(start: Int): Int {
        var end = start
        while (end - start <= GROUP_SIZE && digitAt(end) >= 0) {
            end++
        }
        return end - start
    }

    /** Reads `e`/`E`, an optional sign and digits; the magnitude saturates at `MAX_EXPONENT + 1`. */
    private fun readExponent(): Int {
        val signAt = pos + 1
        val digitsAt = if (codePointAt(signAt) in SIGNS) signAt + 1 else signAt
        if (codePointAt(pos) !in EXPONENT_MARKERS || digitAt(digitsAt) < 0) return 0
        val sign = if (codePointAt(signAt) in MINUS_SIGNS) -1 else 1
        pos = digitsAt
        var magnitude = 0
        var digit = digitAt(pos)
        while (digit >= 0) {
            magnitude = minOf(magnitude * RADIX + digit, AmountParser.MAX_EXPONENT + 1)
            pos++
            digit = digitAt(pos)
        }
        return sign * magnitude
    }

    private fun codePointAt(index: Int): Int = if (index < codePoints.size) codePoints[index] else NO_CODE_POINT

    private fun digitAt(index: Int): Int {
        if (index >= codePoints.size) return NO_DIGIT
        return Character.digit(codePoints[index], RADIX)
    }

    companion object {
        const val DOT = '.'
        const val COMMA = ','
        const val APOSTROPHE = '\''
        const val SPACE = ' '
        const val GROUP_SIZE = 3
        private const val RADIX = 10
        private const val NO_DIGIT = -1
        private const val NO_CODE_POINT = -1
        private const val NBSP = 0x00A0
        private const val NARROW_NBSP = 0x202F
        private const val RIGHT_SINGLE_QUOTE = 0x2019
        private const val ARABIC_DECIMAL_SEPARATOR = 0x066B
        private const val ARABIC_THOUSANDS_SEPARATOR = 0x066C
        private const val MINUS_SIGN = 0x2212
        private val EXPONENT_MARKERS = setOf('e'.code, 'E'.code)
        private val MINUS_SIGNS = setOf('-'.code, MINUS_SIGN)
        private val SIGNS = MINUS_SIGNS + '+'.code

        private fun canonicalSeparator(codePoint: Int): Char? =
            when (codePoint) {
                DOT.code, ARABIC_DECIMAL_SEPARATOR -> DOT
                COMMA.code, ARABIC_THOUSANDS_SEPARATOR -> COMMA
                APOSTROPHE.code, RIGHT_SINGLE_QUOTE -> APOSTROPHE
                SPACE.code, NBSP, NARROW_NBSP -> SPACE
                else -> null
            }
    }
}
