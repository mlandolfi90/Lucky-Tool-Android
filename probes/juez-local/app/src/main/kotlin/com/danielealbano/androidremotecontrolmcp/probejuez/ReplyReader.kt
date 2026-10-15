package com.danielealbano.androidremotecontrolmcp.probejuez

import java.math.BigDecimal
import java.util.Locale

/**
 * One field of a "datos" reply: the value the model copied (null: the line is missing) and whether the plan
 * accepts it.
 */
data class FieldCheck(
    val read: String?,
    val accepted: Boolean,
)

/**
 * A "datos" reply: the model's own SI/NO on whether the action is the current step ([stepAnswer], read after the
 * marker) and the fields it copied. The model only copies them; the app compares each one with the plan.
 */
data class DataCheck(
    val stepAnswer: String,
    val fields: Map<String, FieldCheck>,
) {
    /** SI only when the model says SI and the plan accepts every copied value; a missing line is INVALIDO. */
    val verdict: String
        get() =
            when {
                stepAnswer == JudgeConfig.INVALID || fields.values.any { it.read == null } -> JudgeConfig.INVALID
                stepAnswer == JudgeConfig.NO || fields.values.any { !it.accepted } -> JudgeConfig.NO
                else -> JudgeConfig.YES
            }
}

/** The verdict of one reply and, for the "datos" answer, the copied data behind it. */
data class Judgement(
    val verdict: String,
    val check: DataCheck?,
)

/** Reads the model's replies into verdicts, one way per [JudgePrompt.answer]. */
object ReplyReader {
    /**
     * Thinking written inside the reply text: `<think>` blocks and Gemma 4's thought channel, which runs to the
     * end when the reply was cut. LiteRT-LM keeps Gemma's thinking apart; this guards any reply that carries it.
     */
    private val THINK_BLOCK = Regex("(?s)<think>.*?</think>|<\\|channel>.*?(?:<channel\\|>|\\z)")
    private val YES_NO = Regex("(?<!\\p{L})(SÍ|SI|NO)(?!\\p{L})")
    private val NUMBER = Regex("\\d+")

    /** Marks a copied line may start with: list bullets and markdown bold (`- **APP:** CambioApp`). */
    private val LINE_MARKS = charArrayOf('-', '*', '•', '>', ' ')
    private const val BOLD = "**"

    /** The verdict of [reply] for [case]; [judge] also returns the copied data of a "datos" reply. */
    fun verdict(
        prompt: JudgePrompt,
        case: JudgeCase,
        reply: String,
    ): String = judge(prompt, case, reply).verdict

    /**
     * Reads [reply] for [case]: thinking removed, then the text after the last [JudgePrompt.marker] (none =
     * [JudgeConfig.INVALID]) read as the first SI/NO word, as the first number compared with the case's step, or for
     * "datos" as the model's SI/NO together with the plan check of every copied field ([PlanValues.same]).
     */
    fun judge(
        prompt: JudgePrompt,
        case: JudgeCase,
        reply: String,
    ): Judgement {
        val visible = reply.replace(THINK_BLOCK, " ")
        val answer = if (prompt.marker == null) visible else afterLast(visible, prompt.marker)
        return when (prompt.answer) {
            JudgeConfig.ANSWER_DATA -> dataCheck(prompt, case, visible, answer).let { Judgement(it.verdict, it) }
            JudgeConfig.ANSWER_STEP -> Judgement(stepVerdict(case, answer), null)
            else -> Judgement(yesNo(answer), null)
        }
    }

    private fun afterLast(
        text: String,
        marker: String,
    ): String? {
        val at = text.lastIndexOf(marker, ignoreCase = true)
        return if (at < 0) null else text.substring(at + marker.length)
    }

    private fun yesNo(answer: String?): String =
        when (YES_NO.find(answer?.uppercase().orEmpty())?.value) {
            null -> JudgeConfig.INVALID
            JudgeConfig.NO -> JudgeConfig.NO
            else -> JudgeConfig.YES
        }

    private fun stepVerdict(
        case: JudgeCase,
        answer: String?,
    ): String =
        when (answer?.let { NUMBER.find(it) }?.value?.toIntOrNull()) {
            null -> JudgeConfig.INVALID
            case.step -> JudgeConfig.YES
            else -> JudgeConfig.NO
        }

    /** The last line starting with each field's label (`APP:`), checked against the values the plan accepts. */
    private fun dataCheck(
        prompt: JudgePrompt,
        case: JudgeCase,
        visible: String,
        answer: String?,
    ): DataCheck {
        val lines = visible.lines().map { it.trim().trimStart(*LINE_MARKS).replace(BOLD, "") }
        val fields =
            prompt.fields.associateWith { field ->
                val read =
                    lines
                        .lastOrNull { it.startsWith("$field:", ignoreCase = true) }
                        ?.substring(field.length + 1)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                FieldCheck(read, read != null && case.allowed[field].orEmpty().any { PlanValues.same(read, it) })
            }
        return DataCheck(yesNo(answer), fields)
    }
}

/**
 * Compares a value a "datos" reply copied with one the plan accepts. Amounts compare by number and currency
 * (`20.000,00 Bs` is `20000 Bs`; `20000` alone or `hasta 20000 Bs` is not); anything else as text, ignoring letter
 * case and the quotes, marks and spaces around it. No other character is dropped: `juanp2p_` is not `juanp2p`.
 */
object PlanValues {
    /** Quotes, brackets, markdown and end punctuation around a copied value; `_` and letters always count. */
    private const val EDGE_MARKS = "«»\"'“”‘’<>()[]*`.,;:!¡?¿"
    private val SPACES = Regex("\\s+")
    private const val NONE_VALUE = "ninguno"
    private val NONE_WORDS = setOf("ninguno", "ninguna", "ningún", "ningun", "nadie")

    /** A copied amount: one number with its currency before or after it (`20.000,00 bs`, `bs. 20000`). */
    private val AMOUNT = Regex("^(?:([\\p{L}\$€]+)\\.?\\s*)?(\\d(?:[\\d.,]*\\d)?)(?:\\s*([\\p{L}\$€]+))?$")
    private const val THOUSANDS_DIGITS = 3

    fun same(
        read: String,
        planValue: String,
    ): Boolean {
        val planAmount = amountOf(planValue)
        return if (planAmount == null) textOf(read) == textOf(planValue) else amountOf(read) == planAmount
    }

    private fun textOf(value: String): String {
        val text = value.trim { it.isWhitespace() || it in EDGE_MARKS }.replace(SPACES, " ").lowercase(Locale.ROOT)
        return if (text in NONE_WORDS) NONE_VALUE else text
    }

    /** An amount as its number and currency; `20.000` and `20000,00` are both twenty thousand. */
    private data class Amount(
        val number: BigDecimal,
        val currency: String,
    )

    private fun amountOf(value: String): Amount? =
        AMOUNT.matchEntire(textOf(value))?.destructured?.let { (before, digits, after) ->
            numberOf(digits)?.takeIf { before.isEmpty() || after.isEmpty() }?.let { Amount(it, before + after) }
        }

    /** Thousands and decimal separators either way: of two different separators, the last marks the decimals. */
    private fun numberOf(digits: String): BigDecimal? {
        val dot = digits.lastIndexOf('.')
        val comma = digits.lastIndexOf(',')
        val plain =
            when {
                dot >= 0 && comma >= 0 && dot > comma -> digits.replace(",", "")
                dot >= 0 && comma >= 0 -> digits.replace(".", "").replace(',', '.')
                dot >= 0 -> grouped(digits, '.')
                comma >= 0 -> grouped(digits, ',')
                else -> digits
            }
        return plain?.toBigDecimalOrNull()?.stripTrailingZeros()
    }

    /** `20.000` and `1.234.567` group thousands; `20000.5` and `15,50` carry decimals; else no number. */
    private fun grouped(
        digits: String,
        separator: Char,
    ): String? {
        val parts = digits.split(separator)
        return when {
            parts.drop(1).all { it.length == THOUSANDS_DIGITS } -> parts.joinToString("")
            parts.size == 2 -> parts.joinToString(".")
            else -> null
        }
    }
}
