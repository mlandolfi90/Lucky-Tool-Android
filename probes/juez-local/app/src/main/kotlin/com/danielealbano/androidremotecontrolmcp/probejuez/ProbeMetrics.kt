package com.danielealbano.androidremotecontrolmcp.probejuez

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import com.google.ai.edge.litertlm.BenchmarkInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One answer of the model: the reply text, the text the engine kept apart in named channels (Gemma 4's thinking)
 * and, when the run asked for them, the engine's own token counts and speeds ([BenchStats.record]).
 */
data class JudgeAnswer(
    val reply: String,
    val channels: Map<String, String>,
    val bench: JSONObject?,
)

/**
 * What a run asked and how, beyond the request: the question file's version and SHA-256 (the file changes without a
 * build), the prompt's reading rules and output limit, whether the vision encoder was loaded (in set `todo` also for
 * the text questions) and whether the model file carries a draft model for speculative decoding (null: unknown).
 */
data class RunSetup(
    val questions: Int,
    val casesVersion: Int,
    val casesSha256: String,
    val prompt: JudgePrompt,
    val maxOutputTokens: Int,
    val vision: Boolean,
    val modelSpeculative: Boolean?,
)

/** The memory and the priority of this app's process. */
object ProcessStats {
    fun importance(): Int {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance
    }

    fun pssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss

    fun peakRssKb(): Long =
        File("/proc/self/status")
            .readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.filter { it.isDigit() }
            ?.toLongOrNull() ?: -1L
}

/** The engine's own token counts and speeds for each answer (benchmark mode) and their averages for a run. */
object BenchStats {
    private const val TENTHS = 10.0
    private const val MILLIS_PER_SECOND = 1000.0
    private const val TTFT_MS = "ttftMs"
    private const val PREFILL_TOKENS = "prefillTokens"
    private const val DECODE_TOKENS = "decodeTokens"
    private const val PREFILL_TPS = "prefillTps"
    private const val DECODE_TPS = "decodeTps"

    /** The counts are the engine's last prefill and decode of the answer. */
    fun record(info: BenchmarkInfo): JSONObject =
        JSONObject()
            .put(TTFT_MS, Math.round(info.timeToFirstTokenInSecond * MILLIS_PER_SECOND))
            .put(PREFILL_TOKENS, info.lastPrefillTokenCount)
            .put(DECODE_TOKENS, info.lastDecodeTokenCount)
            .put(PREFILL_TPS, tenths(info.lastPrefillTokensPerSecond))
            .put(DECODE_TPS, tenths(info.lastDecodeTokensPerSecond))

    /** Stands in for [record] when the engine could not give its counts, so the answer itself is kept. */
    fun failure(e: Throwable): JSONObject = JSONObject().put("error", "${e.javaClass.simpleName}: ${e.message}")

    /** Adds the run's average speeds and decoded tokens to [summary], when some answers carry them. */
    fun putAverages(
        summary: JSONObject,
        outcomes: List<JSONObject>,
    ) {
        val benches = outcomes.mapNotNull { it.optJSONObject("bench") }.filter { it.has(DECODE_TPS) }
        if (benches.isNotEmpty()) {
            summary
                .put("prefillTpsAvg", average(benches, PREFILL_TPS))
                .put("decodeTpsAvg", average(benches, DECODE_TPS))
                .put("decodeTokensAvg", average(benches, DECODE_TOKENS))
        }
    }

    private fun average(
        benches: List<JSONObject>,
        key: String,
    ): Double = tenths(benches.sumOf { it.getDouble(key) } / benches.size)

    private fun tenths(value: Double): Double = Math.round(value * TENTHS) / TENTHS
}

/** The measurements of a run and its records: one JUEZ CASE line per answer, the summary and the stored JSON. */
object ProbeMetrics {
    private const val TAG = ProbeService.TAG
    private const val REPLY_LOG_CHARS = 120

    /** Whole replies and thinking are kept in the result record (1024 tokens fit well within this). */
    private const val RAW_REPLY_CHARS = 8000
    private const val P50 = 50
    private const val P90 = 90
    private const val HUNDRED = 100
    private const val DEFAULT = "default"
    private const val NONE = "none"
    private const val UNKNOWN = "unknown"
    private const val STEP_ANSWER = "stepAnswer"

    fun logInit(
        request: RunRequest,
        setup: RunSetup,
        initMs: Long,
        pssKb: Int,
        importance: Int,
    ) {
        Log.i(
            TAG,
            "JUEZ INIT tag=${request.tag} model=${request.model} backend=${request.backend} " +
                "casesFile=${request.casesFile} casesVersion=${setup.casesVersion} " +
                "casesSha256=${setup.casesSha256} set=${request.set} questions=${setup.questions} " +
                "prompt=${request.prompt} answer=${setup.prompt.answer} marker=${setup.prompt.marker ?: NONE} " +
                "fields=${setup.prompt.fields.joinToString(",") { it.replace(' ', '_') }.ifEmpty { NONE }} " +
                "maxTokens=${setup.maxOutputTokens} thinking=${request.thinking} sampler=${request.sampler} " +
                "speculative=${request.speculativeMode} modelSpeculative=${setup.modelSpeculative ?: UNKNOWN} " +
                "visual=${request.visualBudget ?: DEFAULT} vision=${setup.vision} bench=${request.benchMode} " +
                "initMs=$initMs pssKb=$pssKb importance=$importance",
        )
    }

    /**
     * Logs one answer as a JUEZ CASE line and returns its record. `thoughtChars` counts the text the engine kept
     * apart from the reply (Gemma 4's thinking); a "datos" answer adds what the model said on the step and the plan
     * check of each copied field; the reply always goes last in the line.
     */
    fun caseResult(
        request: RunRequest,
        round: Int,
        case: JudgeCase,
        answer: JudgeAnswer,
        judgement: Judgement,
        elapsedMs: Long,
    ): JSONObject {
        val importance = ProcessStats.importance()
        val reply = answer.reply
        val shortReply = reply.replace('\n', ' ').take(REPLY_LOG_CHARS)
        val bench = answer.bench
        val benchFields = bench?.let { b -> b.keys().asSequence().joinToString("") { " $it=${b.get(it)}" } }.orEmpty()
        val check = judgement.check
        Log.i(
            TAG,
            "JUEZ CASE tag=${request.tag} round=$round id=${case.id} expected=${case.expected} " +
                "verdict=${judgement.verdict} ms=$elapsedMs importance=$importance chars=${reply.length} " +
                "thoughtChars=${answer.channels.values.sumOf { it.length }}$benchFields" +
                "${check?.let(::checkLog).orEmpty()} reply=$shortReply",
        )
        val record =
            JSONObject()
                .put("round", round)
                .put("id", case.id)
                .put("expected", case.expected)
                .put("verdict", judgement.verdict)
                .put("ms", elapsedMs)
                .put("importance", importance)
                .put("reply", shortReply)
                .put("raw", reply.take(RAW_REPLY_CHARS))
                .put("channels", JSONObject(answer.channels.mapValues { it.value.take(RAW_REPLY_CHARS) }))
        bench?.let { record.put("bench", it) }
        check?.let { record.put(STEP_ANSWER, it.stepAnswer).put("checks", checkRecord(it)) }
        return record
    }

    fun summarize(
        request: RunRequest,
        setup: RunSetup,
        initMs: Long,
        pssAfterInitKb: Int,
        importanceAtStart: Int,
        outcomes: List<JSONObject>,
    ): JSONObject {
        val times = outcomes.map { it.getLong("ms") }.sorted()
        val summary =
            JSONObject()
                .put("tag", request.tag)
                .put("model", request.model)
                .put("backend", request.backend)
                .put("threads", request.threads)
                .put("thinking", request.thinking)
                .put("prompt", request.prompt)
                .put("answer", setup.prompt.answer)
                .put("marker", setup.prompt.marker ?: NONE)
                .put("fields", JSONArray(setup.prompt.fields))
                .put("maxTokens", setup.maxOutputTokens)
                .put("casesFile", request.casesFile)
                .put("casesVersion", setup.casesVersion)
                .put("casesSha256", setup.casesSha256)
                .put("set", request.set)
                .put("sampler", request.sampler)
                .put("speculative", request.speculativeMode)
                .put("modelSpeculative", setup.modelSpeculative ?: UNKNOWN)
                .put("visual", request.visualBudget ?: DEFAULT)
                .put("vision", setup.vision)
                .put("bench", request.benchMode)
                .put("delaySec", request.delaySec)
                .put("initMs", initMs)
                .put("pssAfterInitKb", pssAfterInitKb)
                .put("pssEndKb", ProcessStats.pssKb())
                .put("peakRssKb", ProcessStats.peakRssKb())
                .put("importanceAtStart", importanceAtStart)
                .put("importanceDuring", JSONArray(outcomes.map { it.getInt("importance") }.distinct()))
                .put("cases", outcomes.size)
                .put("correct", outcomes.count { it.getString("verdict") == it.getString("expected") })
                .put("dangerousYes", outcomes.count { it.isExpected(JudgeConfig.NO) && it.isVerdict(JudgeConfig.YES) })
                .put("extraStops", outcomes.count { it.isExpected(JudgeConfig.YES) && !it.isVerdict(JudgeConfig.YES) })
                .put("invalid", outcomes.count { it.isVerdict(JudgeConfig.INVALID) })
                .put("firstMs", outcomes.firstOrNull()?.getLong("ms") ?: 0L)
                .put("p50Ms", percentile(times, P50))
                .put("p90Ms", percentile(times, P90))
                .put("maxMs", times.lastOrNull() ?: 0L)
                .put("avgMs", if (times.isEmpty()) 0L else times.sum() / times.size)
        BenchStats.putAverages(summary, outcomes)
        putDataCounts(summary, outcomes)
        return summary.put("results", JSONArray(outcomes))
    }

    /** Stores the full record as `<tag>.json` and logs the summary (without the per-case results). */
    fun publish(
        context: Context,
        request: RunRequest,
        summary: JSONObject,
    ) {
        File(ProbeFiles.resultsDir(context), "${request.tag}.json").writeText(summary.toString(2))
        summary.remove("results")
        Log.i(TAG, "JUEZ SUMMARY $summary")
    }

    /** ` model=SI checks=APP:ok,MONTO:NO`: the model's SI/NO on the step and the plan check of each copied field. */
    private fun checkLog(check: DataCheck): String =
        " model=${check.stepAnswer} checks=" +
            check.fields.entries.joinToString(",") { (field, result) ->
                val outcome =
                    when {
                        result.read == null -> "falta"
                        result.accepted -> "ok"
                        else -> JudgeConfig.NO
                    }
                "${field.replace(' ', '_')}:$outcome"
            }

    private fun checkRecord(check: DataCheck): JSONObject =
        JSONObject().also { record ->
            check.fields.forEach { (field, result) ->
                val values = JSONObject().put("read", result.read ?: JSONObject.NULL).put("accepted", result.accepted)
                record.put(field, values)
            }
        }

    /**
     * For a "datos" run: how often the model said SI, the NO questions where the app's comparison stopped that SI
     * (`appCaught`) and the SI questions it stopped (`appExtraStops`).
     */
    private fun putDataCounts(
        summary: JSONObject,
        outcomes: List<JSONObject>,
    ) {
        val modelYes = outcomes.filter { it.has(STEP_ANSWER) && it.getString(STEP_ANSWER) == JudgeConfig.YES }
        if (outcomes.any { it.has(STEP_ANSWER) }) {
            summary
                .put("modelYes", modelYes.size)
                .put("appCaught", modelYes.count { it.isExpected(JudgeConfig.NO) && it.isVerdict(JudgeConfig.NO) })
                .put("appExtraStops", modelYes.count { it.isExpected(JudgeConfig.YES) && it.isVerdict(JudgeConfig.NO) })
        }
    }

    private fun JSONObject.isExpected(label: String): Boolean = getString("expected") == label

    private fun JSONObject.isVerdict(label: String): Boolean = getString("verdict") == label

    private fun percentile(
        sorted: List<Long>,
        percent: Int,
    ): Long = if (sorted.isEmpty()) 0L else sorted[(sorted.size - 1) * percent / HUNDRED]
}
