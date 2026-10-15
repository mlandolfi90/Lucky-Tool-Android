package com.danielealbano.androidremotecontrolmcp.probejuez

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What both judge runners share: the questions, the verdict rules, the measurements and the result records. */
object ProbeMetrics {
    private const val TAG = ProbeService.TAG
    private const val MAX_OUTPUT_TOKENS = 16
    private const val MAX_OUTPUT_TOKENS_THINKING = 1024
    private const val REPLY_LOG_CHARS = 120
    private const val P50 = 50
    private const val P90 = 90
    private const val HUNDRED = 100

    fun casesFor(request: RunRequest): List<JudgeCase> =
        if (request.set == RunRequest.SET_IMAGES) JudgeCases.imageCases else JudgeCases.all

    fun systemFor(request: RunRequest): String =
        if (request.prompt == RunRequest.PROMPT_STEP) JudgeCases.SYSTEM_STEP else JudgeCases.SYSTEM

    fun promptFor(
        request: RunRequest,
        case: JudgeCase,
    ): String = if (request.prompt == RunRequest.PROMPT_STEP) case.stepPrompt() else case.prompt()

    fun thinkingOn(request: RunRequest): Boolean = request.thinking == RunRequest.THINKING_ON

    fun maxOutputTokens(request: RunRequest): Int =
        if (thinkingOn(request)) MAX_OUTPUT_TOKENS_THINKING else MAX_OUTPUT_TOKENS

    fun imageFile(
        context: Context,
        name: String,
    ): File =
        File(ProbeFiles.imagesDir(context), name).takeIf { it.isFile }
            ?: error("image $name not found in ${ProbeFiles.imagesDir(context)}")

    fun logInit(
        request: RunRequest,
        details: String,
        initMs: Long,
        pssKb: Int,
        importance: Int,
    ) {
        Log.i(
            TAG,
            "JUEZ INIT tag=${request.tag} model=${request.model} backend=${request.backend} " +
                "set=${request.set} prompt=${request.prompt} thinking=${request.thinking} " +
                "sampler=${request.sampler} $details initMs=$initMs pssKb=$pssKb importance=$importance",
        )
    }

    /** Turns one reply into a verdict, logs it as a JUEZ CASE line and returns its record. */
    fun caseResult(
        request: RunRequest,
        round: Int,
        case: JudgeCase,
        reply: String,
        elapsedMs: Long,
    ): JSONObject {
        val verdict =
            if (request.prompt == RunRequest.PROMPT_STEP) {
                JudgeCases.stepVerdict(reply, case.step)
            } else {
                JudgeCases.verdictOf(reply)
            }
        val importance = importance()
        val shortReply = reply.replace('\n', ' ').take(REPLY_LOG_CHARS)
        Log.i(
            TAG,
            "JUEZ CASE tag=${request.tag} round=$round id=${case.id} expected=${case.expectedLabel} " +
                "verdict=$verdict ms=$elapsedMs importance=$importance reply=$shortReply",
        )
        return JSONObject()
            .put("round", round)
            .put("id", case.id)
            .put("expected", case.expectedLabel)
            .put("verdict", verdict)
            .put("ms", elapsedMs)
            .put("importance", importance)
            .put("reply", shortReply)
    }

    fun summarize(
        request: RunRequest,
        initMs: Long,
        pssAfterInitKb: Int,
        importanceAtStart: Int,
        outcomes: List<JSONObject>,
    ): JSONObject {
        val times = outcomes.map { it.getLong("ms") }.sorted()
        return JSONObject()
            .put("tag", request.tag)
            .put("model", request.model)
            .put("backend", request.backend)
            .put("threads", request.threads)
            .put("thinking", request.thinking)
            .put("prompt", request.prompt)
            .put("set", request.set)
            .put("sampler", request.sampler)
            .put("delaySec", request.delaySec)
            .put("initMs", initMs)
            .put("pssAfterInitKb", pssAfterInitKb)
            .put("pssEndKb", pssKb())
            .put("peakRssKb", peakRssKb())
            .put("importanceAtStart", importanceAtStart)
            .put("importanceDuring", JSONArray(outcomes.map { it.getInt("importance") }.distinct()))
            .put("cases", outcomes.size)
            .put("correct", outcomes.count { it.getString("verdict") == it.getString("expected") })
            .put("dangerousYes", outcomes.count { it.isExpected(JudgeCases.NO) && it.isVerdict(JudgeCases.YES) })
            .put("extraStops", outcomes.count { it.isExpected(JudgeCases.YES) && !it.isVerdict(JudgeCases.YES) })
            .put("invalid", outcomes.count { it.isVerdict(JudgeCases.INVALID) })
            .put("firstMs", outcomes.firstOrNull()?.getLong("ms") ?: 0L)
            .put("p50Ms", percentile(times, P50))
            .put("p90Ms", percentile(times, P90))
            .put("maxMs", times.lastOrNull() ?: 0L)
            .put("avgMs", if (times.isEmpty()) 0L else times.sum() / times.size)
            .put("results", JSONArray(outcomes))
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

    private fun JSONObject.isExpected(label: String): Boolean = getString("expected") == label

    private fun JSONObject.isVerdict(label: String): Boolean = getString("verdict") == label

    private fun percentile(
        sorted: List<Long>,
        percent: Int,
    ): Long = if (sorted.isEmpty()) 0L else sorted[(sorted.size - 1) * percent / HUNDRED]
}
