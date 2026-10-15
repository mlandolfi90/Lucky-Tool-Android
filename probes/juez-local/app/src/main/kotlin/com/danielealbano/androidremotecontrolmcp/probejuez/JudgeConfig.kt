package com.danielealbano.androidremotecontrolmcp.probejuez

import org.json.JSONArray
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
 * [marker] after which the verdict is read, the output limits without and with thinking, and for "datos" the
 * [fields]: the lines (`APP: …`) the model copies from the action and the app compares with the plan.
 */
data class JudgePrompt(
    val system: String,
    val question: String,
    val answer: String,
    val marker: String?,
    val maxTokens: Int,
    val maxTokensThinking: Int,
    val fields: List<String> = emptyList(),
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
        private val ROOT_KEYS = setOf("version", "note", "imageNote", "plans", "stepData", "prompts", "cases")
        private val PROMPT_KEYS =
            setOf("system", "question", "answer", "marker", "fields", "maxTokens", "maxTokensThinking")
        private val CASE_KEYS = setOf("id", "plan", "step", "action", "expected", "image")
        private val REQUIRED_PLACEHOLDERS = listOf("{plan}", "{action}")
        private val PLACEHOLDER = Regex("\\{[A-Za-z]+\\}")

        fun load(file: File): JudgeConfig = parse(JSONObject(file.readText()))

        fun parse(root: JSONObject): JudgeConfig {
            root.requireKnownKeys("the question file", ROOT_KEYS)
            val plans = root.getJSONObject("plans").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
            val stepData = root.optJSONObject("stepData")?.let { stepDataOf(it, plans.keys) }.orEmpty()
            val prompts =
                root.getJSONObject("prompts").let { o ->
                    o.keys().asSequence().associateWith { promptOf(it, o.getJSONObject(it)) }
                }
            val list = root.getJSONArray("cases")
            val cases = (0 until list.length()).map { caseOf(list.getJSONObject(it), plans, stepData) }
            require(cases.map { it.id }.toSet().size == cases.size) { "case ids must be unique" }
            prompts.forEach { (name, prompt) -> requirePlanValues(name, prompt, cases) }
            return JudgeConfig(root.optInt("version", 0), prompts, root.optString("imageNote", ""), cases)
        }

        private fun promptOf(
            name: String,
            o: JSONObject,
        ): JudgePrompt {
            o.requireKnownKeys("prompt '$name'", PROMPT_KEYS)
            val answer = o.optString("answer", ANSWER_YES_NO)
            require(answer in ANSWERS) { "prompt '$name': answer must be one of $ANSWERS" }
            val question = o.getString("question")
            REQUIRED_PLACEHOLDERS.forEach { require(it in question) { "prompt '$name': the question has no $it" } }
            val marker = o.textOrNull("marker")
            val fields = o.optJSONArray("fields")?.let { textsOf(it, "prompt '$name' fields") }.orEmpty()
            if (answer == ANSWER_DATA) {
                require(marker != null && fields.isNotEmpty()) { "prompt '$name': datos needs a marker and fields" }
            } else {
                require(fields.isEmpty()) { "prompt '$name': only the datos answer reads fields" }
            }
            return JudgePrompt(
                system = o.getString("system"),
                question = question,
                answer = answer,
                marker = marker,
                maxTokens = o.optInt("maxTokens", DEFAULT_MAX_TOKENS),
                maxTokensThinking = o.optInt("maxTokensThinking", DEFAULT_MAX_TOKENS_THINKING),
                fields = fields,
            )
        }

        private fun caseOf(
            o: JSONObject,
            plans: Map<String, String>,
            stepData: StepData,
        ): JudgeCase {
            val id = o.getString("id")
            o.requireKnownKeys("case $id", CASE_KEYS)
            val expected = o.getString("expected").uppercase()
            require(expected == YES || expected == NO) { "case $id: expected must be SI or NO" }
            val plan = o.getString("plan")
            val step = o.getInt("step")
            return JudgeCase(
                id = id,
                plan = plans[plan] ?: error("case $id: unknown plan $plan"),
                step = step,
                action = o.getString("action"),
                expected = expected,
                image = o.textOrNull("image"),
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
                    val number = requireNotNull(step.toIntOrNull()) { "stepData $plan: step '$step' is not a number" }
                    number to fieldsOf(steps.getJSONObject(step), "stepData $plan $step")
                }
            }

        private fun fieldsOf(
            o: JSONObject,
            at: String,
        ): Map<String, List<String>> = o.keys().asSequence().associateWith { textsOf(o.getJSONArray(it), "$at $it") }

        private fun textsOf(
            array: JSONArray,
            at: String,
        ): List<String> {
            val texts = (0 until array.length()).map { array.getString(it) }
            require(texts.isNotEmpty() && texts.none { it.isBlank() }) { "$at: needs values, none of them blank" }
            return texts
        }

        /** A "datos" prompt needs the plan's values of every field for every case, or its verdicts are INVALIDO. */
        private fun requirePlanValues(
            name: String,
            prompt: JudgePrompt,
            cases: List<JudgeCase>,
        ) {
            cases.forEach { case ->
                prompt.fields.forEach { field ->
                    require(!case.allowed[field].isNullOrEmpty()) {
                        "prompt '$name': case ${case.id} (step ${case.step}) has no plan values for $field"
                    }
                }
            }
        }

        /** A misspelled key would otherwise be ignored and its default used without notice. */
        private fun JSONObject.requireKnownKeys(
            where: String,
            known: Set<String>,
        ) {
            val unknown = keys().asSequence().filterNot { it in known }.toList()
            require(unknown.isEmpty()) { "$where: unknown keys $unknown" }
        }

        private fun JSONObject.textOrNull(name: String): String? =
            if (has(name) && !isNull(name)) getString(name).takeIf { it.isNotBlank() } else null
    }
}
