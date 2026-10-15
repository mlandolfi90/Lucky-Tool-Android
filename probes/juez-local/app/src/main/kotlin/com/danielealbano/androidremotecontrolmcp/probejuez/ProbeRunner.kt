@file:OptIn(ExperimentalApi::class)

package com.danielealbano.androidremotecontrolmcp.probejuez

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.ModelInfo
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Loads one model with one backend, asks every question of the question file pushed to the phone ([JudgeConfig])
 * and logs and stores the measurements.
 */
class ProbeRunner(
    private val context: Context,
) {
    suspend fun run(request: RunRequest) {
        // Unknown words fail here, before the model is loaded.
        val speculative = request.speculative
        val benchmark = request.benchmark
        delay(request.delaySec * MILLIS_PER_SECOND)
        val configFile =
            ProbeFiles.findConfig(context, request.casesFile)
                ?: error("question file ${request.casesFile} not found in ${ProbeFiles.configDir(context)}")
        val config = JudgeConfig.load(configFile)
        val prompt = config.prompt(request.prompt)
        val cases = config.casesFor(request.set)
        require(cases.isNotEmpty()) { "${request.casesFile} has no cases for set ${request.set}" }
        // A missing screenshot fails now, not after the answers before it (results are stored at the end).
        cases.mapNotNull { it.image }.distinct().forEach { ProbeMetrics.imageFile(context, it) }
        val modelFile =
            ProbeFiles.findModel(context, request.model)
                ?: error("model ${request.model} not found in ${ProbeFiles.modelDirs(context)}")
        val withImages = cases.any { it.image != null }
        val setup =
            RunSetup(
                questions = cases.size,
                casesVersion = config.version,
                casesSha256 = sha256(configFile),
                prompt = prompt,
                maxOutputTokens = ProbeMetrics.maxOutputTokens(request, prompt),
                vision = withImages,
                modelSpeculative = modelSpeculativeSupport(modelFile),
            )
        Engine.setNativeMinLogSeverity(LogSeverity.WARNING)
        // Process-wide flags, read when the engine is created and on every message: set again on every run.
        ExperimentalFlags.enableSpeculativeDecoding = speculative
        ExperimentalFlags.visualTokenBudget = request.visualBudget
        ExperimentalFlags.enableBenchmark = benchmark
        val engineConfig =
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backendFor(request),
                visionBackend = if (withImages) visionBackendFor(request) else null,
                maxNumTokens = if (withImages) MAX_NUM_TOKENS_IMAGES else MAX_NUM_TOKENS,
                maxNumImages = if (withImages) 1 else null,
                cacheDir = context.cacheDir.path,
            )
        val importanceAtStart = ProbeMetrics.importance()
        val summary =
            Engine(engineConfig).use { engine ->
                val initStarted = SystemClock.elapsedRealtime()
                engine.initialize()
                val initMs = SystemClock.elapsedRealtime() - initStarted
                val pssAfterInitKb = ProbeMetrics.pssKb()
                ProbeMetrics.logInit(request, setup, initMs, pssAfterInitKb, importanceAtStart)
                val outcomes =
                    (1..request.repeat).flatMap { round ->
                        cases.map { case -> judge(engine, config, setup, case, request, round) }
                    }
                ProbeMetrics.summarize(request, setup, initMs, pssAfterInitKb, importanceAtStart, outcomes)
            }
        ProbeMetrics.publish(context, request, summary)
    }

    private fun judge(
        engine: Engine,
        config: JudgeConfig,
        setup: RunSetup,
        case: JudgeCase,
        request: RunRequest,
        round: Int,
    ): JSONObject {
        val prompt = setup.prompt
        val conversationConfig =
            ConversationConfig(
                systemInstruction = Contents.of(prompt.system),
                samplerConfig =
                    if (request.sampler == RunRequest.SAMPLER_NONE) {
                        null
                    } else {
                        SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)
                    },
                maxOutputToken = setup.maxOutputTokens,
                thinkingConfig =
                    when (request.thinking) {
                        "config" -> ThinkingConfig(enableThinking = false)
                        RunRequest.THINKING_ON -> ThinkingConfig(enableThinking = true)
                        else -> null
                    },
                extraContext =
                    when (request.thinking) {
                        "none" -> emptyMap()
                        RunRequest.THINKING_ON -> mapOf("enable_thinking" to true)
                        else -> mapOf("enable_thinking" to false)
                    },
                enableSpeculativeDecoding = request.speculative,
            )
        val question = config.questionFor(prompt, case)
        val imageBytes = case.image?.let { ProbeMetrics.imageFile(context, it).readBytes() }
        val started = SystemClock.elapsedRealtime()
        val answer =
            engine.createConversation(conversationConfig).use { conversation ->
                val message =
                    if (imageBytes == null) {
                        conversation.sendMessage(question)
                    } else {
                        conversation.sendMessage(Contents.of(Content.ImageBytes(imageBytes), Content.Text(question)))
                    }
                val bench =
                    if (request.benchmark) {
                        runCatching { ProbeMetrics.benchRecord(conversation.getBenchmarkInfo()) }
                            .getOrElse { ProbeMetrics.benchError(it) }
                    } else {
                        null
                    }
                JudgeAnswer(reply = message.toString(), channels = message.channels, bench = bench)
            }
        val elapsedMs = SystemClock.elapsedRealtime() - started
        val verdict = JudgeConfig.verdict(prompt, case, answer.reply)
        return ProbeMetrics.caseResult(request, round, case, answer, verdict, elapsedMs)
    }

    private fun backendFor(request: RunRequest): Backend =
        when (request.backend) {
            BACKEND_GPU -> Backend.GPU()
            BACKEND_CPU -> Backend.CPU(threadCount = request.threads)
            else -> error("backend ${request.backend} is not $BACKEND_GPU or $BACKEND_CPU")
        }

    private fun visionBackendFor(request: RunRequest): Backend =
        if (request.backend == BACKEND_GPU) Backend.GPU() else Backend.CPU(threadCount = request.threads)

    /** Whether the model file carries a draft model; null when its metadata cannot be read. */
    private fun modelSpeculativeSupport(modelFile: File): Boolean? =
        runCatching {
            ModelInfo.from(modelFile.absolutePath).use { (it as? ModelInfo.Llm)?.hasSpeculativeDecodingSupport() }
        }.getOrNull()

    private fun sha256(file: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val MAX_NUM_TOKENS = 2048
        const val MAX_NUM_TOKENS_IMAGES = 4096
        const val BACKEND_GPU = "gpu"
        const val BACKEND_CPU = "cpu"
    }
}
