package com.danielealbano.androidremotecontrolmcp.probejuez

import java.util.Locale

/**
 * One field of a "datos" reply: the value the model copied (null: the line is missing or repeated), whether the plan
 * accepts it and, for a text question, whether it is written in the action ([inAction]; null: not checked, for
 * NINGUNO and for image questions, whose data is in the screenshot).
 */
data class FieldCheck(
    val read: String?,
    val accepted: Boolean,
    val inAction: Boolean? = null,
)

/**
 * A "datos" reply: the model's own SI/NO on whether the action is the current step ([stepAnswer], read from the
 * marker line), the fields it copied and, when the reply is not in the asked format, why ([formatError]). The model
 * only copies the fields; the app compares each one with the plan and, for a text question, looks for it in the action.
 */
data class DataCheck(
    val stepAnswer: String,
    val fields: Map<String, FieldCheck>,
    val formatError: String? = null,
) {
    /**
     * SI only when the model says SI, the plan accepts every copied value and none is missing from the action; a reply
     * out of format, without its answer or with a missing line is INVALIDO.
     */
    val verdict: String
        get() {
            val unreadable =
                formatError != null || stepAnswer == JudgeConfig.INVALID || fields.values.any { it.read == null }
            val refused = stepAnswer == JudgeConfig.NO || fields.values.any { !it.accepted || it.inAction == false }
            return when {
                unreadable -> JudgeConfig.INVALID
                refused -> JudgeConfig.NO
                else -> JudgeConfig.YES
            }
        }
}

/** The verdict of one reply and, for the "datos" answer, the copied data behind it. */
data class Judgement(
    val verdict: String,
    val check: DataCheck?,
)

/**
 * Reads the model's replies into verdicts, one way per [JudgePrompt.answer]. The reading is strict: the answer must
 * be exactly the word or number asked for (marks around it aside) and, with a marker, the marker line must be the last
 * line and the only one with the marker. Anything else is INVALIDO, never a guess.
 */
object ReplyReader {
    /**
     * Thinking written inside the reply text: `<think>` blocks and Gemma 4's thought channel, each running to the end
     * when the reply was cut. LiteRT-LM keeps Gemma's thinking apart; this guards any reply that carries it.
     */
    private val THINK_BLOCK = Regex("(?s)<think>.*?(?:</think>|\\z)|<\\|channel>.*?(?:<channel\\|>|\\z)")

    /** List bullets a line may start with (`- `, `* `, `• `, `> `), each followed by a space. */
    private val BULLETS = Regex("^(?:[-*•+>]\\s+)+")
    private const val BOLD = "**"
    private const val LABEL_END = ":"
    private const val YES_ACCENT = "SÍ"
    private const val REPEATED = "-repetido"

    /** The verdict of [reply] for [case]; [judge] also returns the copied data of a "datos" reply. */
    fun verdict(
        prompt: JudgePrompt,
        case: JudgeCase,
        reply: String,
    ): String = judge(prompt, case, reply).verdict

    /**
     * Reads [reply] for [case]: thinking removed, then the whole reply or, with a [JudgePrompt.marker], the marker
     * line ([marked]) read as SI/NO, as a step number compared with the case's step, or for "datos" as the model's
     * SI/NO together with the plan check of every copied field ([dataCheck]).
     */
    fun judge(
        prompt: JudgePrompt,
        case: JudgeCase,
        reply: String,
    ): Judgement {
        val visible = reply.replace(THINK_BLOCK, " ")
        val marked = if (prompt.marker == null) Marked(emptyList(), visible, null) else marked(visible, prompt.marker)
        return when (prompt.answer) {
            JudgeConfig.ANSWER_DATA -> dataCheck(prompt, case, marked).let { Judgement(it.verdict, it) }
            JudgeConfig.ANSWER_STEP -> Judgement(stepVerdict(case, marked.answer), null)
            else -> Judgement(yesNo(marked.answer), null)
        }
    }

    /** A reply split at its marker line: the lines before it, the answer after the marker and what was wrong. */
    private class Marked(
        val before: List<String>,
        val answer: String?,
        val error: String?,
    )

    /**
     * The marker must be on exactly one line, and that line must be the last one with text and start with the marker
     * (`ES EL PASO: SI`); the answer is read only from there.
     */
    private fun marked(
        visible: String,
        marker: String,
    ): Marked {
        val label = marker.removeSuffix(LABEL_END)
        val lines = visible.lines().filter { it.isNotBlank() }
        val markerLines =
            lines.count { line -> line.contains(marker, ignoreCase = true) || valueAfter(line, label) != null }
        val answer = lines.lastOrNull()?.let { valueAfter(it, label) }
        val error =
            when {
                markerLines == 0 -> "sin-marcador"
                markerLines > 1 -> "marcador$REPEATED"
                answer == null -> "marcador-mal-ubicado"
                else -> null
            }
        return if (error == null) Marked(lines.dropLast(1), answer, null) else Marked(lines, null, error)
    }

    /**
     * The value of a `LABEL: value` line, or null if [line] is not one: the line may start with bullets (`- `) and the
     * label may be bold (`**APP:**`, `**APP**:`); the value keeps every character but a bold pair around all of it.
     */
    private fun valueAfter(
        line: String,
        label: String,
    ): String? {
        val text = line.trim().replace(BULLETS, "")
        val head =
            listOf("$BOLD$label$LABEL_END$BOLD", "$BOLD$label$BOLD$LABEL_END", "$label$LABEL_END")
                .firstOrNull { text.startsWith(it, ignoreCase = true) }
        return head?.let { unbold(text.substring(it.length).trim()) }
    }

    private fun unbold(value: String): String =
        if (value.length > 2 * BOLD.length && value.startsWith(BOLD) && value.endsWith(BOLD)) {
            value.substring(BOLD.length, value.length - BOLD.length).trim()
        } else {
            value
        }

    /** The answer word without the marks around it (`**Sí.**` is `SÍ`); `SI, porque…` stays as it is. */
    private fun bareWord(answer: String): String = answer.trim { !it.isLetterOrDigit() }.uppercase(Locale.ROOT)

    private fun yesNo(answer: String?): String =
        when (answer?.let(::bareWord)) {
            JudgeConfig.YES, YES_ACCENT -> JudgeConfig.YES
            JudgeConfig.NO -> JudgeConfig.NO
            else -> JudgeConfig.INVALID
        }

    private fun stepVerdict(
        case: JudgeCase,
        answer: String?,
    ): String {
        val word = answer?.let(::bareWord)
        val number = word?.takeIf { text -> text.isNotEmpty() && text.all { it in '0'..'9' } }?.toIntOrNull()
        return when (number) {
            null -> JudgeConfig.INVALID
            case.step -> JudgeConfig.YES
            else -> JudgeConfig.NO
        }
    }

    /** Each field's line must be written once, before the marker line; a repeated one makes the reply INVALIDO. */
    private fun dataCheck(
        prompt: JudgePrompt,
        case: JudgeCase,
        marked: Marked,
    ): DataCheck {
        val reads = prompt.fields.associateWith { field -> marked.before.mapNotNull { valueAfter(it, field) } }
        val repeated = reads.entries.firstOrNull { it.value.size > 1 }?.key
        val fields =
            reads.mapValues { (field, values) ->
                fieldCheck(prompt, case, field, values.singleOrNull()?.takeIf { it.isNotEmpty() })
            }
        val error = marked.error ?: repeated?.let { "${it.replace(' ', '_')}$REPEATED" }
        return DataCheck(yesNo(marked.answer), fields, error)
    }

    private fun fieldCheck(
        prompt: JudgePrompt,
        case: JudgeCase,
        field: String,
        read: String?,
    ): FieldCheck {
        if (read == null) return FieldCheck(null, accepted = false)
        val amount = field in prompt.amountFields
        val accepted = case.allowed[field].orEmpty().any { PlanValues.same(read, it, amount) }
        val checked = case.image == null && !PlanValues.isNone(read)
        return FieldCheck(read, accepted, if (checked) PlanValues.inText(read, case.action, amount) else null)
    }
}
