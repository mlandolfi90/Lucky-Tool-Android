package com.danielealbano.androidremotecontrolmcp.probejuez

import android.content.Context
import android.os.SystemClock
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.VlmWrapper
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import com.geniex.sdk.bean.ProfilingData
import com.geniex.sdk.bean.RuntimeIdValue
import com.geniex.sdk.bean.SamplerConfig
import com.geniex.sdk.bean.VlmChatMessage
import com.geniex.sdk.bean.VlmContent
import com.geniex.sdk.bean.VlmCreateInput
import org.json.JSONObject

/**
 * Asks the judge questions with Qualcomm's GenieX kit on the NPU (QAIRT runtime). [RunRequest.model] names a
 * QAIRT bundle (AI Hub .zip or its extracted folder) in the model folders or in /data/local/tmp/juez; the kit
 * imports it into its own cache once, and later runs load it from there.
 */
object GenieXProbe {
    private const val SEED = 42

    suspend fun run(
        context: Context,
        request: RunRequest,
    ) {
        val failures = StringBuilder()
        GenieXSdk.getInstance().init(
            context,
            object : GenieXSdk.InitCallback {
                override fun onSuccess() = Unit

                override fun onFailure(reason: String) {
                    failures.append(reason)
                }
            },
        )
        check(failures.isEmpty()) { "GenieX init failed: $failures" }
        val name = "local/${request.model.removeSuffix(".zip")}"
        val importStarted = SystemClock.elapsedRealtime()
        if (ModelManagerWrapper.getPaths(name) == null) {
            val bundle =
                ProbeFiles.findBundle(context, request.model)
                    ?: error("bundle ${request.model} not found in ${ProbeFiles.bundleDirs(context)}")
            ModelManagerWrapper
                .pullFlow(ModelPullInput(model_name = name, hub = HubSource.LOCALFS, local_path = bundle.absolutePath))
                .collect { event ->
                    if (event is ModelManagerWrapper.PullEvent.Error) {
                        error("GenieX import failed rc=${event.code}: ${event.message}")
                    }
                }
        }
        val importMs = SystemClock.elapsedRealtime() - importStarted
        val paths = ModelManagerWrapper.getPaths(name) ?: error("GenieX has no model $name after the import")
        val runtime = paths.runtime_id.ifEmpty { RuntimeIdValue.QAIRT.value }
        val importanceAtStart = ProbeMetrics.importance()
        val initStarted = SystemClock.elapsedRealtime()
        val vlm =
            VlmWrapper
                .builder()
                .vlmCreateInput(
                    VlmCreateInput(
                        model_path = paths.model_path,
                        mmproj_path = paths.mmproj_path,
                        config = ModelConfig(nCtx = 0),
                        runtime_id = runtime,
                        compute_unit = null,
                    ),
                ).build()
                .getOrThrow()
        val summary =
            vlm.use {
                val initMs = SystemClock.elapsedRealtime() - initStarted
                val pssAfterInitKb = ProbeMetrics.pssKb()
                ProbeMetrics.logInit(
                    request,
                    "runtime=$runtime importMs=$importMs",
                    initMs,
                    pssAfterInitKb,
                    importanceAtStart,
                )
                val outcomes =
                    (1..request.repeat).flatMap { round ->
                        ProbeMetrics.casesFor(request).map { case -> judge(context, vlm, case, request, round) }
                    }
                ProbeMetrics
                    .summarize(request, initMs, pssAfterInitKb, importanceAtStart, outcomes)
                    .put("importMs", importMs)
                    .put("runtime", runtime)
            }
        ProbeMetrics.publish(context, request, summary)
    }

    private suspend fun judge(
        context: Context,
        vlm: VlmWrapper,
        case: JudgeCase,
        request: RunRequest,
        round: Int,
    ): JSONObject {
        vlm.reset()
        val userContents =
            buildList {
                case.image?.let { add(VlmContent("image", ProbeMetrics.imageFile(context, it).absolutePath)) }
                add(VlmContent("text", ProbeMetrics.promptFor(request, case)))
            }
        val messages =
            arrayOf(
                VlmChatMessage(role = "system", contents = listOf(VlmContent("text", ProbeMetrics.systemFor(request)))),
                VlmChatMessage(role = "user", contents = userContents),
            )
        val started = SystemClock.elapsedRealtime()
        val formatted =
            vlm.applyChatTemplate(messages, null, ProbeMetrics.thinkingOn(request)).getOrThrow().formattedText
        val config =
            vlm.injectMediaPathsToConfig(
                messages,
                GenerationConfig(maxTokens = ProbeMetrics.maxOutputTokens(request), samplerConfig = samplerFor(request)),
            )
        val reply = StringBuilder()
        var profile: ProfilingData? = null
        vlm.generateStreamFlow(formatted, config).collect { result ->
            when (result) {
                is LlmStreamResult.Token -> reply.append(result.text)
                is LlmStreamResult.Completed -> profile = result.profile
                is LlmStreamResult.Error -> throw result.throwable
            }
        }
        val elapsedMs = SystemClock.elapsedRealtime() - started
        val outcome = ProbeMetrics.caseResult(request, round, case, reply.toString(), elapsedMs)
        profile?.let {
            outcome
                .put("ttftMs", it.ttftMs)
                .put("mediaMs", it.mediaMs)
                .put("promptTokens", it.promptTokens)
                .put("prefillSpeed", it.prefillSpeed)
        }
        return outcome
    }

    /** Greedy unless asked otherwise: top-k 1 keeps the most likely token (zero fields mean "bundle default"). */
    private fun samplerFor(request: RunRequest): SamplerConfig? =
        if (request.sampler == RunRequest.SAMPLER_NONE) null else SamplerConfig(topK = 1, seed = SEED)
}
