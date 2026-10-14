package com.danielealbano.androidremotecontrolmcp.probejuez

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Loads one model with one backend, asks every [JudgeCases] question and logs and stores the measurements. */
class ProbeRunner(
    private val context: Context,
) {
    suspend fun run(request: RunRequest) {
        delay(request.delaySec * MILLIS_PER_SECOND)
        val modelFile =
            ProbeFiles.findModel(context, request.model)
                ?: error("model ${request.model} not found in ${ProbeFiles.modelDirs(context)}")
        Engine.setNativeMinLogSeverity(LogSeverity.WARNING)
        val backend = if (request.backend == "gpu") Backend.GPU() else Backend.CPU(threadCount = request.threads)
        val engineConfig =
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backend,
                maxNumTokens = MAX_NUM_TOKENS,
                cacheDir = context.cacheDir.path,
            )
        val importanceAtStart = ownImportance()
        val summary =
            Engine(engineConfig).use { engine ->
                val initStarted = SystemClock.elapsedRealtime()
                engine.initialize()
                val initMs = SystemClock.elapsedRealtime() - initStarted
                val pssAfterInitKb = pssKb()
                Log.i(
                    TAG,
                    "JUEZ INIT tag=${request.tag} model=${request.model} backend=${request.backend} " +
                        "initMs=$initMs pssKb=$pssAfterInitKb importance=$importanceAtStart",
                )
                val outcomes =
                    (1..request.repeat).flatMap { round ->
                        JudgeCases.all.map { case -> judge(engine, case, request, round) }
                    }
                summarize(request, initMs, pssAfterInitKb, importanceAtStart, outcomes)
            }
        File(ProbeFiles.resultsDir(context), "${request.tag}.json").writeText(summary.toString(2))
        summary.remove("results")
        Log.i(TAG, "JUEZ SUMMARY $summary")
    }

    private fun judge(
        engine: Engine,
        case: JudgeCase,
        request: RunRequest,
        round: Int,
    ): JSONObject {
        val config =
            ConversationConfig(
                systemInstruction = Contents.of(JudgeCases.SYSTEM),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                maxOutputToken = MAX_OUTPUT_TOKENS,
                thinkingConfig = if (request.thinking == "config") ThinkingConfig(enableThinking = false) else null,
                extraContext = if (request.thinking == "none") emptyMap() else mapOf("enable_thinking" to false),
            )
        val started = SystemClock.elapsedRealtime()
        val reply = engine.createConversation(config).use { it.sendMessage(case.prompt()).toString() }
        val elapsedMs = SystemClock.elapsedRealtime() - started
        val verdict = JudgeCases.verdictOf(reply)
        val importance = ownImportance()
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

    private fun summarize(
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

    private fun JSONObject.isExpected(label: String): Boolean = getString("expected") == label

    private fun JSONObject.isVerdict(label: String): Boolean = getString("verdict") == label

    private fun percentile(
        sorted: List<Long>,
        percent: Int,
    ): Long = if (sorted.isEmpty()) 0L else sorted[(sorted.size - 1) * percent / HUNDRED]

    private fun ownImportance(): Int =
        ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance

    private fun pssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss

    private fun peakRssKb(): Long =
        File("/proc/self/status")
            .readLines()
            .firstOrNull { it.startsWith("VmHWM:") }
            ?.filter { it.isDigit() }
            ?.toLongOrNull() ?: -1L

    private companion object {
        const val TAG = ProbeService.TAG
        const val MILLIS_PER_SECOND = 1000L
        const val MAX_NUM_TOKENS = 2048
        const val MAX_OUTPUT_TOKENS = 16
        const val REPLY_LOG_CHARS = 120
        const val P50 = 50
        const val P90 = 90
        const val HUNDRED = 100
    }
}
