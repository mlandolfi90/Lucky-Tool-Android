package com.danielealbano.androidremotecontrolmcp.probejuez

import org.json.JSONObject
import java.io.File

/**
 * One question for the judge: the approved plan, its current step and the action the agent wants to do.
 * [image] names a screenshot in the images folder that is sent together with the question.
 */
data class JudgeCase(
    val id: String,
    val plan: String,
    val step: Int,
    val action: String,
    val expected: String,
    val image: String?,
)

/**
 * One way of asking: the system instruction, the question template ({plan}, {step}, {action} and {imageNote} are
 * filled per case), how the reply is read ([answer] is "sino" or "paso"), the optional [marker] after which the
 * verdict is read, and the output limits without and with thinking.
 */
data class JudgePrompt(
    val system: String,
    val question: String,
    val answer: String,
    val marker: String?,
    val maxTokens: Int,
    val maxTokensThinking: Int,
)

/** The questions and the ways of asking them, read from a JSON file on the phone: changing them needs no build. */
class JudgeConfig(
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

    fun questionFor(
        prompt: JudgePrompt,
        case: JudgeCase,
    ): String =
        prompt.question
            .replace("{plan}", case.plan)
            .replace("{step}", case.step.toString())
            .replace("{action}", case.action)
            .replace("{imageNote}", if (case.image != null) imageNote else "")

    companion object {
        const val YES = "SI"
        const val NO = "NO"
        const val INVALID = "INVALIDO"
        private const val ANSWER_YES_NO = "sino"
        private const val ANSWER_STEP = "paso"
        private const val DEFAULT_MAX_TOKENS = 16
        private const val DEFAULT_MAX_TOKENS_THINKING = 1024

        /**
         * Gemma 4's thought channel, should it ever reach the reply text (LiteRT-LM keeps it apart); it runs to the
         * end when the reply was cut.
         */
        private val THINK_BLOCK = Regex("(?s)<\\|channel>.*?(?:<channel\\|>|\\z)")
        private val YES_NO = Regex("(?<!\\p{L})(SÍ|SI|NO)(?!\\p{L})")
        private val NUMBER = Regex("\\d+")

        fun load(file: File): JudgeConfig = parse(JSONObject(file.readText()))

        fun parse(root: JSONObject): JudgeConfig {
            val plans = root.getJSONObject("plans").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
            val prompts =
                root.getJSONObject("prompts").let { o ->
                    o.keys().asSequence().associateWith { promptOf(it, o.getJSONObject(it)) }
                }
            val list = root.getJSONArray("cases")
            val cases = (0 until list.length()).map { caseOf(list.getJSONObject(it), plans) }
            require(cases.map { it.id }.toSet().size == cases.size) { "case ids must be unique" }
            return JudgeConfig(prompts, root.optString("imageNote", ""), cases)
        }

        /**
         * The verdict of [reply] for [case]: thinking removed, then the text after the last [JudgePrompt.marker]
         * (none = [INVALID]) read as the first SI/NO word, or as the first number compared with the case's step.
         */
        fun verdict(
            prompt: JudgePrompt,
            case: JudgeCase,
            reply: String,
        ): String {
            val visible = reply.replace(THINK_BLOCK, " ")
            val answer =
                if (prompt.marker == null) {
                    visible
                } else {
                    val at = visible.lastIndexOf(prompt.marker, ignoreCase = true)
                    if (at < 0) return INVALID
                    visible.substring(at + prompt.marker.length)
                }
            if (prompt.answer == ANSWER_STEP) {
                val step = NUMBER.find(answer)?.value?.toIntOrNull() ?: return INVALID
                return if (step == case.step) YES else NO
            }
            val match = YES_NO.find(answer.uppercase()) ?: return INVALID
            return if (match.value == NO) NO else YES
        }

        private fun promptOf(
            name: String,
            o: JSONObject,
        ): JudgePrompt {
            val answer = o.optString("answer", ANSWER_YES_NO)
            require(answer == ANSWER_YES_NO || answer == ANSWER_STEP) { "prompt '$name': answer must be sino or paso" }
            return JudgePrompt(
                system = o.getString("system"),
                question = o.getString("question"),
                answer = answer,
                marker = o.textOrNull("marker"),
                maxTokens = o.optInt("maxTokens", DEFAULT_MAX_TOKENS),
                maxTokensThinking = o.optInt("maxTokensThinking", DEFAULT_MAX_TOKENS_THINKING),
            )
        }

        private fun caseOf(
            o: JSONObject,
            plans: Map<String, String>,
        ): JudgeCase {
            val id = o.getString("id")
            val expected = o.getString("expected").uppercase()
            require(expected == YES || expected == NO) { "case $id: expected must be SI or NO" }
            return JudgeCase(
                id = id,
                plan = plans[o.getString("plan")] ?: error("case $id: unknown plan ${o.getString("plan")}"),
                step = o.getInt("step"),
                action = o.getString("action"),
                expected = expected,
                image = o.textOrNull("image"),
            )
        }

        private fun JSONObject.textOrNull(name: String): String? =
            if (has(name) && !isNull(name)) getString(name).takeIf { it.isNotBlank() } else null
    }
}
