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
 * and, when the run asked for them, the engine's own token counts and speeds ([ProbeMetrics.benchRecord]).
 */
data class JudgeAnswer(
    val reply: String,
    val channels: Map<String, String>,
    val bench: JSONObject?,
)

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
    private const val TENTHS = 10.0
    private const val MILLIS_PER_SECOND = 1000.0
    private const val BENCH_TTFT_MS = "ttftMs"
    private const val BENCH_PREFILL_TOKENS = "prefillTokens"
    private const val BENCH_DECODE_TOKENS = "decodeTokens"
    private const val BENCH_PREFILL_TPS = "prefillTps"
    private const val BENCH_DECODE_TPS = "decodeTps"

    fun thinkingOn(request: RunRequest): Boolean = request.thinking == RunRequest.THINKING_ON

    fun maxOutputTokens(
        request: RunRequest,
        prompt: JudgePrompt,
    ): Int = if (thinkingOn(request)) prompt.maxTokensThinking else prompt.maxTokens

    fun imageFile(
        context: Context,
        name: String,
    ): File =
        File(ProbeFiles.imagesDir(context), name).takeIf { it.isFile }
            ?: error("image $name not found in ${ProbeFiles.imagesDir(context)}")

    fun logInit(
        request: RunRequest,
        questions: Int,
        initMs: Long,
        pssKb: Int,
        importance: Int,
    ) {
        Log.i(
            TAG,
            "JUEZ INIT tag=${request.tag} model=${request.model} backend=${request.backend} " +
                "casesFile=${request.casesFile} set=${request.set} questions=$questions prompt=${request.prompt} " +
                "thinking=${request.thinking} sampler=${request.sampler} " +
                "speculative=${request.speculative ?: DEFAULT} visual=${request.visualBudget ?: DEFAULT} " +
                "bench=${request.benchmark} initMs=$initMs pssKb=$pssKb importance=$importance",
        )
    }

    /** The engine's own counts and speeds for one answer (only filled when the engine started in benchmark mode). */
    fun benchRecord(info: BenchmarkInfo): JSONObject =
        JSONObject()
            .put(BENCH_TTFT_MS, Math.round(info.timeToFirstTokenInSecond * MILLIS_PER_SECOND))
            .put(BENCH_PREFILL_TOKENS, info.lastPrefillTokenCount)
            .put(BENCH_DECODE_TOKENS, info.lastDecodeTokenCount)
            .put(BENCH_PREFILL_TPS, tenths(info.lastPrefillTokensPerSecond))
            .put(BENCH_DECODE_TPS, tenths(info.lastDecodeTokensPerSecond))

    /**
     * Logs one answer as a JUEZ CASE line and returns its record. `thoughtChars` counts the text the engine kept
     * apart from the reply (Gemma 4's thinking); the reply always goes last in the line.
     */
    fun caseResult(
        request: RunRequest,
        round: Int,
        case: JudgeCase,
        answer: JudgeAnswer,
        verdict: String,
        elapsedMs: Long,
    ): JSONObject {
        val importance = importance()
        val reply = answer.reply
        val shortReply = reply.replace('\n', ' ').take(REPLY_LOG_CHARS)
        val bench = answer.bench
        val benchFields = bench?.let { b -> b.keys().asSequence().joinToString("") { " $it=${b.get(it)}" } }.orEmpty()
        Log.i(
            TAG,
            "JUEZ CASE tag=${request.tag} round=$round id=${case.id} expected=${case.expected} " +
                "verdict=$verdict ms=$elapsedMs importance=$importance chars=${reply.length} " +
                "thoughtChars=${answer.channels.values.sumOf { it.length }}$benchFields reply=$shortReply",
        )
        val record =
            JSONObject()
                .put("round", round)
                .put("id", case.id)
                .put("expected", case.expected)
                .put("verdict", verdict)
                .put("ms", elapsedMs)
                .put("importance", importance)
                .put("reply", shortReply)
                .put("raw", reply.take(RAW_REPLY_CHARS))
                .put("channels", JSONObject(answer.channels.mapValues { it.value.take(RAW_REPLY_CHARS) }))
        bench?.let { record.put("bench", it) }
        return record
    }

    fun summarize(
        request: RunRequest,
        initMs: Long,
        pssAfterInitKb: Int,
        importanceAtStart: Int,
        outcomes: List<JSONObject>,
    ): JSONObject {
        val times = outcomes.map { it.getLong("ms") }.sorted()
        val benches = outcomes.mapNotNull { it.optJSONObject("bench") }
        val summary =
            JSONObject()
                .put("tag", request.tag)
                .put("model", request.model)
                .put("backend", request.backend)
                .put("threads", request.threads)
                .put("thinking", request.thinking)
                .put("prompt", request.prompt)
                .put("casesFile", request.casesFile)
                .put("set", request.set)
                .put("sampler", request.sampler)
                .put("speculative", request.speculative ?: DEFAULT)
                .put("visual", request.visualBudget ?: DEFAULT)
                .put("delaySec", request.delaySec)
                .put("initMs", initMs)
                .put("pssAfterInitKb", pssAfterInitKb)
                .put("pssEndKb", pssKb())
                .put("peakRssKb", peakRssKb())
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
        if (benches.isNotEmpty()) {
            summary
                .put("prefillTpsAvg", average(benches, BENCH_PREFILL_TPS))
                .put("decodeTpsAvg", average(benches, BENCH_DECODE_TPS))
                .put("decodeTokensAvg", average(benches, BENCH_DECODE_TOKENS))
        }
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

    fun importance(): Int =
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance

    fun pssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss

    private fun peakRssKb(): Long =
        File("/proc/self/status")
            .readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.filter { it.isDigit() }
            ?.toLongOrNull() ?: -1L

    private fun average(
        benches: List<JSONObject>,
        key: String,
    ): Double = tenths(benches.sumOf { it.getDouble(key) } / benches.size)

    private fun tenths(value: Double): Double = Math.round(value * TENTHS) / TENTHS

    private fun JSONObject.isExpected(label: String): Boolean = getString("expected") == label

    private fun JSONObject.isVerdict(label: String): Boolean = getString("verdict") == label

    private fun percentile(
        sorted: List<Long>,
        percent: Int,
    ): Long = if (sorted.isEmpty()) 0L else sorted[(sorted.size - 1) * percent / HUNDRED]
}
