package com.danielealbano.androidremotecontrolmcp.probejuez

import android.content.Context
import android.os.SystemClock
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Loads one model with one backend, asks every [JudgeCases] question and logs and stores the measurements.
 * The `geniex` backend goes to [GenieXProbe] (Qualcomm's kit, only in the geniex build flavor).
 */
class ProbeRunner(
    private val context: Context,
) {
    suspend fun run(request: RunRequest) {
        delay(request.delaySec * MILLIS_PER_SECOND)
        if (request.backend == RunRequest.BACKEND_GENIEX) {
            GenieXProbe.run(context, request)
            return
        }
        val modelFile =
            ProbeFiles.findModel(context, request.model)
                ?: error("model ${request.model} not found in ${ProbeFiles.modelDirs(context)}")
        Engine.setNativeMinLogSeverity(LogSeverity.WARNING)
        val withImages = request.set == RunRequest.SET_IMAGES
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
        val chatTemplate =
            if (request.model.startsWith(QWEN3_PREFIX)) {
                context.assets.open(QWEN3_TEMPLATE_ASSET).bufferedReader().use { it.readText() }
            } else {
                null
            }
        val templateLabel = if (chatTemplate == null) "model" else "litertlm-qwen3"
        val summary =
            Engine(engineConfig).use { engine ->
                val initStarted = SystemClock.elapsedRealtime()
                engine.initialize()
                val initMs = SystemClock.elapsedRealtime() - initStarted
                val pssAfterInitKb = ProbeMetrics.pssKb()
                ProbeMetrics.logInit(request, "template=$templateLabel", initMs, pssAfterInitKb, importanceAtStart)
                val outcomes =
                    (1..request.repeat).flatMap { round ->
                        ProbeMetrics.casesFor(request).map { case -> judge(engine, case, request, round, chatTemplate) }
                    }
                ProbeMetrics
                    .summarize(request, initMs, pssAfterInitKb, importanceAtStart, outcomes)
                    .put("template", templateLabel)
            }
        ProbeMetrics.publish(context, request, summary)
    }

    private fun judge(
        engine: Engine,
        case: JudgeCase,
        request: RunRequest,
        round: Int,
        chatTemplate: String?,
    ): JSONObject {
        val config =
            ConversationConfig(
                systemInstruction = Contents.of(ProbeMetrics.systemFor(request)),
                samplerConfig =
                    if (request.sampler == RunRequest.SAMPLER_NONE) {
                        null
                    } else {
                        SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)
                    },
                maxOutputToken = ProbeMetrics.maxOutputTokens(request),
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
                chatTemplate = chatTemplate,
            )
        val prompt = ProbeMetrics.promptFor(request, case)
        val imageBytes = case.image?.let { ProbeMetrics.imageFile(context, it).readBytes() }
        val started = SystemClock.elapsedRealtime()
        val reply =
            engine.createConversation(config).use { conversation ->
                if (imageBytes == null) {
                    conversation.sendMessage(prompt).toString()
                } else {
                    conversation.sendMessage(Contents.of(Content.ImageBytes(imageBytes), Content.Text(prompt))).toString()
                }
            }
        val elapsedMs = SystemClock.elapsedRealtime() - started
        return ProbeMetrics.caseResult(request, round, case, reply, elapsedMs)
    }

    private fun backendFor(request: RunRequest): Backend =
        when (request.backend) {
            "gpu" -> Backend.GPU()
            "npu" -> Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir)
            else -> Backend.CPU(threadCount = request.threads)
        }

    /** The vision encoder never runs on the NPU; with the decoder on the NPU it goes to the CPU. */
    private fun visionBackendFor(request: RunRequest): Backend =
        if (request.backend == "gpu") Backend.GPU() else Backend.CPU(threadCount = request.threads)

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val MAX_NUM_TOKENS = 2048
        const val MAX_NUM_TOKENS_IMAGES = 4096
        const val QWEN3_PREFIX = "qwen3"

        /** LiteRT-LM's canonical Qwen3 template: the one inside the model file cannot render list contents. */
        const val QWEN3_TEMPLATE_ASSET = "qwen3_chat_template.jinja"
    }
}
