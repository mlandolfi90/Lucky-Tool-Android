package com.danielealbano.androidremotecontrolmcp.probejuez

import java.math.BigDecimal
import java.util.Locale

/**
 * Compares a value a "datos" reply copied with one the plan accepts, by the field's type: in an amount field an
 * amount compares by number and currency (`20.000,00 Bs` is `20000 Bs`; `20000` alone, `+20.000 Bs` or
 * `hasta 20000 Bs` is not, see [Amounts]); anything else compares as text, without letter case and without the
 * quotes, brackets or bold around the whole value. No other character is dropped: `juanp2p_`, `juanp2p.` and
 * `pedro099` are not `juanp2p` or `pedro99`. Only NINGUNO means "no value".
 */
object PlanValues {
    /** Pairs that may wrap a whole copied value. */
    private val WRAPPERS =
        listOf(
            "«" to "»",
            "\"" to "\"",
            "'" to "'",
            "“" to "”",
            "‘" to "’",
            "(" to ")",
            "[" to "]",
            "`" to "`",
            "**" to "**",
        )
    private const val NONE_VALUE = "ninguno"

    /** Whether [read] is the plan's [planValue]; [amount]: the field holds amounts (NINGUNO still compares as text). */
    fun same(
        read: String,
        planValue: String,
        amount: Boolean,
    ): Boolean {
        val planAmount = if (amount) Amounts.of(planValue) else null
        return if (planAmount == null) {
            textOf(read) == textOf(planValue)
        } else {
            Amounts.of(read)?.matches(planAmount) == true
        }
    }

    fun isNone(value: String): Boolean = textOf(value) == NONE_VALUE

    /** Whether [value] is a plan value an amount field can hold: NINGUNO or an amount with its currency. */
    fun isAmountOrNone(value: String): Boolean = isNone(value) || Amounts.of(value) != null

    /**
     * Whether [read] is written in [text]: an amount as one of the text's numbers (read as [Amounts.numberOf] reads
     * them) and its currency as a word; anything else as a whole word or phrase, without letter case.
     */
    fun inText(
        read: String,
        text: String,
        amount: Boolean,
    ): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val copied = if (amount) Amounts.of(read) else null
        return if (copied == null) {
            containsWord(lower, textOf(read))
        } else {
            Amounts.numbersIn(lower).any { it.compareTo(copied.number) == 0 } && containsWord(lower, copied.currency)
        }
    }

    /** The value without the pairs around it, in lower case. */
    fun textOf(value: String): String = unwrap(value).lowercase(Locale.ROOT)

    private fun unwrap(value: String): String =
        generateSequence(value.trim()) { text ->
            WRAPPERS
                .firstOrNull { (open, close) ->
                    text.length >= open.length + close.length && text.startsWith(open) && text.endsWith(close)
                }?.let { (open, close) -> text.substring(open.length, text.length - close.length).trim() }
        }.last()

    /** [word] in [text] with no letter, digit or `_` glued to it on a side where the word itself has one. */
    private fun containsWord(
        text: String,
        word: String,
    ): Boolean =
        word.isNotEmpty() &&
            generateSequence(text.indexOf(word).takeIf { it >= 0 }) { at ->
                text.indexOf(word, at + 1).takeIf { it >= 0 }
            }.any { at ->
                (!isWordChar(word.first()) || !isWordChar(text.getOrNull(at - 1))) &&
                    (!isWordChar(word.last()) || !isWordChar(text.getOrNull(at + word.length)))
            }

    private fun isWordChar(char: Char?): Boolean = char != null && (char.isLetterOrDigit() || char == '_')
}

/** An amount as its number and currency; equal numbers match at any scale (`20000.00` is `20000`). */
class Amount(
    val number: BigDecimal,
    val currency: String,
) {
    fun matches(other: Amount): Boolean = currency == other.currency && number.compareTo(other.number) == 0
}

/** Reads the amounts of the "datos" answer: one number with one currency, the separators checked ([numberOf]). */
object Amounts {
    /** A copied amount: one number with its currency before or after it (`20.000,00 bs`, `bs. 20000`). */
    private val AMOUNT = Regex("^(?:([\\p{L}\$€]+)\\.?\\s*)?(\\d(?:[\\d.,]*\\d)?)(?:\\s*([\\p{L}\$€]+))?$")

    /** A number written alone in a text, not glued to letters (`TRC20` has none). */
    private val NUMBER = Regex("(?<![\\p{L}\\d_])\\d(?:[\\d.,]*\\d)?(?![\\p{L}\\d_])")
    private const val ABBREVIATION_DOT = "."
    private val THOUSANDS = mapOf('.' to Regex("\\d{1,3}(?:\\.\\d{3})+"), ',' to Regex("\\d{1,3}(?:,\\d{3})+"))

    /** The currency is required, on one side only; a dot may end the value (`20000 Bs.`). */
    fun of(value: String): Amount? {
        val match = AMOUNT.matchEntire(PlanValues.textOf(value).removeSuffix(ABBREVIATION_DOT)) ?: return null
        val (before, digits, after) = match.destructured
        val currency = before + after
        val oneCurrency = currency.isNotEmpty() && (before.isEmpty() || after.isEmpty())
        return numberOf(digits)?.takeIf { oneCurrency }?.let { Amount(it, currency) }
    }

    /** Every number of [text] that reads as one. */
    fun numbersIn(text: String): Sequence<BigDecimal> = NUMBER.findAll(text).mapNotNull { numberOf(it.value) }

    /**
     * Thousands and decimal separators either way, with the grouping checked: of two different separators the last
     * one marks the decimals and the other groups thousands (`20.000,00`, `20,000.00`); one kind repeated groups
     * thousands (`1.234.567`); one alone groups thousands before exactly three digits (`20.000`) and marks decimals
     * otherwise (`15,50`, `20000.5`). Anything else (`200.00,00`, `1.23.456`) is no number. `1.250` is 1250 even where
     * it means 1,25: read that way a number is never smaller than the screen's, so a match never lets more through
     * than the plan says.
     */
    fun numberOf(digits: String): BigDecimal? {
        val separators = digits.filter { it == '.' || it == ',' }.toSet()
        val plain =
            when (separators.size) {
                0 -> digits
                1 -> oneSeparator(digits, separators.single())
                else -> twoSeparators(digits)
            }
        return plain?.toBigDecimalOrNull()
    }

    private fun oneSeparator(
        digits: String,
        separator: Char,
    ): String? =
        when {
            THOUSANDS.getValue(separator).matches(digits) -> digits.replace(separator.toString(), "")
            digits.count { it == separator } == 1 -> digits.replace(separator, '.')
            else -> null
        }

    private fun twoSeparators(digits: String): String? {
        val decimal = digits.last { it == '.' || it == ',' }
        val whole = digits.substringBeforeLast(decimal)
        val thousands = if (decimal == '.') ',' else '.'
        return if (THOUSANDS.getValue(thousands).matches(whole)) {
            whole.replace(thousands.toString(), "") + "." + digits.substringAfterLast(decimal)
        } else {
            null
        }
    }
}
