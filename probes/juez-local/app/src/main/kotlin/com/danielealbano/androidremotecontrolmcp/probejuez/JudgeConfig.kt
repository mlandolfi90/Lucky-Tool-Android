package com.danielealbano.androidremotecontrolmcp.probejuez

import org.json.JSONObject
import java.io.File

/** Plan id → step → field → the values the plan accepts for that field in that step. */
private typealias StepData = Map<String, Map<Int, Map<String, List<String>>>>

/**
 * One question for the judge: the approved plan, its current step and the action the agent wants to do.
 * [image] names a screenshot in the images folder that is sent together with the question. [allowed] is the plan's
 * fixed data for the current step, field by field: the values the plan accepts (read by the "datos" answer).
 */
data class JudgeCase(
    val id: String,
    val plan: String,
    val step: Int,
    val action: String,
    val expected: String,
    val image: String?,
    val allowed: Map<String, List<String>> = emptyMap(),
)

/**
 * One way of asking: the system instruction, the question template ({plan}, {step}, {action} and {imageNote} are
 * filled per case), how the reply is read ([answer] is "sino", "paso" or "datos", see [ReplyReader]), the optional
 * [marker] (`LABEL:`) whose line holds the verdict, the output limits without and with thinking, and for "datos" the
 * [fields]: the lines (`APP: …`) the model copies from the action and the app compares with the plan, of which
 * [amountFields] hold amounts (compared by number and currency, see [PlanValues]).
 */
data class JudgePrompt(
    val system: String,
    val question: String,
    val answer: String,
    val marker: String?,
    val maxTokens: Int,
    val maxTokensThinking: Int,
    val fields: List<String> = emptyList(),
    val amountFields: List<String> = emptyList(),
)

/** The questions and the ways of asking them, read from a JSON file on the phone: changing them needs no build. */
class JudgeConfig(
    val version: Int,
    private val prompts: Map<String, JudgePrompt>,
    private val imageNote: String,
    private val cases: List<JudgeCase>,
) {
    fun prompt(name: String): JudgePrompt =
        prompts[name] ?: error("prompt '$name' is not in the question file (it has: ${prompts.keys.joinToString()})")

    fun casesFor(set: String): List<JudgeCase> =
        when (set) {
            RunRequest.SET_TEXT -> cases.filter { it.image == null }
            RunRequest.SET_IMAGES -> cases.filter { it.image != null }
            RunRequest.SET_ALL -> cases
            else -> error("set '$set' is not ${RunRequest.SET_TEXT}, ${RunRequest.SET_IMAGES} or ${RunRequest.SET_ALL}")
        }

    /** The filled question; a placeholder left unfilled (a misspelled one) fails instead of reaching the model. */
    fun questionFor(
        prompt: JudgePrompt,
        case: JudgeCase,
    ): String {
        val question =
            prompt.question
                .replace("{plan}", case.plan)
                .replace("{step}", case.step.toString())
                .replace("{action}", case.action)
                .replace("{imageNote}", if (case.image != null) imageNote else "")
        PLACEHOLDER.find(question)?.let { error("case ${case.id}: unknown placeholder ${it.value} in the question") }
        return question
    }

    companion object {
        const val YES = "SI"
        const val NO = "NO"
        const val INVALID = "INVALIDO"
        const val ANSWER_YES_NO = "sino"
        const val ANSWER_STEP = "paso"
        const val ANSWER_DATA = "datos"
        private val ANSWERS = setOf(ANSWER_YES_NO, ANSWER_STEP, ANSWER_DATA)
        private const val DEFAULT_MAX_TOKENS = 16
        private const val DEFAULT_MAX_TOKENS_THINKING = 1024
        private const val FILE = "the question file"
        private const val FIELDS = "fields"
        private const val AMOUNT_FIELDS = "amountFields"
        private const val LABEL_END = ":"
        private val ROOT_KEYS = setOf("version", "note", "imageNote", "plans", "stepData", "prompts", "cases")
        private val PROMPT_KEYS =
            setOf("system", "question", "answer", "marker", FIELDS, AMOUNT_FIELDS, "maxTokens", "maxTokensThinking")
        private val CASE_KEYS = setOf("id", "plan", "step", "action", "expected", "image")
        private val REQUIRED_PLACEHOLDERS = listOf("{plan}", "{action}")
        private val PLACEHOLDER = Regex("\\{[A-Za-z]+\\}")

        /** A step key as the plans number their steps: `6`, never `06` (both would be step 6). */
        private val STEP_KEY = Regex("[1-9]\\d*")

        fun load(file: File): JudgeConfig = parse(JSONObject(file.readText()))

        fun parse(root: JSONObject): JudgeConfig {
            root.requireKnownKeys(FILE, ROOT_KEYS)
            val plansJson = root.getJSONObject("plans")
            val plans = plansJson.keys().asSequence().associateWith { plansJson.textAt(it, "plans") }
            val stepData = root.optJSONObject("stepData")?.let { stepDataOf(it, plans.keys) }.orEmpty()
            val prompts =
                root.getJSONObject("prompts").let { o ->
                    o.keys().asSequence().associateWith { promptOf(it, o.getJSONObject(it)) }
                }
            val list = root.getJSONArray("cases")
            val cases = (0 until list.length()).map { caseOf(list.getJSONObject(it), plans, stepData) }
            require(cases.map { it.id }.toSet().size == cases.size) { "case ids must be unique" }
            prompts.forEach { (name, prompt) -> requirePlanValues(name, prompt, cases) }
            val imageNote = root.textOrNullAt("imageNote", FILE).orEmpty()
            return JudgeConfig(root.intAt("version", FILE, default = 0), prompts, imageNote, cases)
        }

        private fun promptOf(
            name: String,
            o: JSONObject,
        ): JudgePrompt {
            val where = "prompt '$name'"
            o.requireKnownKeys(where, PROMPT_KEYS)
            val answer = if (o.has("answer")) o.textAt("answer", where) else ANSWER_YES_NO
            require(answer in ANSWERS) { "$where: answer must be one of $ANSWERS" }
            val question = o.textAt("question", where)
            REQUIRED_PLACEHOLDERS.forEach { require(it in question) { "$where: the question has no $it" } }
            val marker = o.textOrNullAt("marker", where)
            require(marker == null || (marker.endsWith(LABEL_END) && marker in question)) {
                "$where: the marker must end with '$LABEL_END' and be written in the question"
            }
            val fields = if (o.has(FIELDS)) textsOf(o.getJSONArray(FIELDS), "$where $FIELDS") else null
            val amounts =
                if (o.has(AMOUNT_FIELDS)) {
                    textsOf(o.getJSONArray(AMOUNT_FIELDS), "$where $AMOUNT_FIELDS", allowEmpty = true)
                } else {
                    null
                }
            val prompt =
                JudgePrompt(
                    system = o.textAt("system", where),
                    question = question,
                    answer = answer,
                    marker = marker,
                    maxTokens = o.intAt("maxTokens", where, DEFAULT_MAX_TOKENS),
                    maxTokensThinking = o.intAt("maxTokensThinking", where, DEFAULT_MAX_TOKENS_THINKING),
                    fields = fields.orEmpty(),
                    amountFields = amounts.orEmpty(),
                )
            requireDataLines(where, prompt, amounts != null)
            return prompt
        }

        /**
         * A "datos" prompt asks for each field as a `FIELD:` line of its question and for the verdict on the marker
         * line, and says which fields hold amounts ([AMOUNT_FIELDS], which may be empty); other answers read no fields.
         */
        private fun requireDataLines(
            where: String,
            prompt: JudgePrompt,
            declaresAmounts: Boolean,
        ) {
            val fields = prompt.fields
            if (prompt.answer != ANSWER_DATA) {
                require(fields.isEmpty() && !declaresAmounts) { "$where: only the datos answer reads fields" }
                return
            }
            require(prompt.marker != null && fields.isNotEmpty() && declaresAmounts) {
                "$where: datos needs a marker, fields and $AMOUNT_FIELDS"
            }
            require(fields.toSet().size == fields.size && fields.all { it == it.trim() && LABEL_END !in it }) {
                "$where: fields must be unique and have no spaces around them and no '$LABEL_END'"
            }
            fields.forEach { field ->
                require("$field$LABEL_END" in prompt.question) { "$where: the question has no '$field$LABEL_END' line" }
            }
            val amounts = prompt.amountFields
            require(amounts.toSet().size == amounts.size && amounts.all { it in fields }) {
                "$where: $AMOUNT_FIELDS must be fields of the prompt, each one once"
            }
        }

        private fun caseOf(
            o: JSONObject,
            plans: Map<String, String>,
            stepData: StepData,
        ): JudgeCase {
            val id = o.textAt("id", "a case")
            val where = "case $id"
            o.requireKnownKeys(where, CASE_KEYS)
            val expected = o.textAt("expected", where).uppercase()
            require(expected == YES || expected == NO) { "$where: expected must be SI or NO" }
            val plan = o.textAt("plan", where)
            val step = o.intAt("step", where)
            require(step >= 1) { "$where: step must be 1 or more" }
            return JudgeCase(
                id = id,
                plan = plans[plan] ?: error("$where: unknown plan $plan"),
                step = step,
                action = o.textAt("action", where),
                expected = expected,
                image = o.textOrNullAt("image", where),
                allowed = stepData[plan]?.get(step).orEmpty(),
            )
        }

        private fun stepDataOf(
            o: JSONObject,
            plans: Set<String>,
        ): StepData =
            o.keys().asSequence().associateWith { plan ->
                require(plan in plans) { "stepData: unknown plan $plan" }
                val steps = o.getJSONObject(plan)
                steps.keys().asSequence().associate { step ->
                    require(STEP_KEY.matches(step)) { "stepData $plan: step '$step' must be a whole number from 1" }
                    step.toInt() to fieldsOf(steps.getJSONObject(step), "stepData $plan $step")
                }
            }

        private fun fieldsOf(
            o: JSONObject,
            at: String,
        ): Map<String, List<String>> = o.keys().asSequence().associateWith { textsOf(o.getJSONArray(it), "$at $it") }

        /**
         * A "datos" prompt needs the plan's values of every field for every case (without them every copied value
         * would be refused: verdict NO), and an amount field's values must be NINGUNO or amounts with their currency.
         */
        private fun requirePlanValues(
            name: String,
            prompt: JudgePrompt,
            cases: List<JudgeCase>,
        ) {
            cases.forEach { case ->
                prompt.fields.forEach { field ->
                    val values = case.allowed[field].orEmpty()
                    val where = "prompt '$name': case ${case.id} (step ${case.step}) $field"
                    require(values.isNotEmpty()) { "$where has no plan values" }
                    require(field !in prompt.amountFields || values.all(PlanValues::isAmountOrNone)) {
                        "$where: each value must be NINGUNO or an amount with its currency"
                    }
                }
            }
        }
    }
}
