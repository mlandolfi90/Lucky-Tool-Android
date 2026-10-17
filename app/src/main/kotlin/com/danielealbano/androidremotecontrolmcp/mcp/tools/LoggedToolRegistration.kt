package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import com.danielealbano.androidremotecontrolmcp.policy.gate.ToolPolicyGate
import com.danielealbano.androidremotecontrolmcp.policy.subject.ToolPolicyCatalog
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

private const val NANOS_PER_MILLI = 1_000_000L

/**
 * Constant, NON-SENSITIVE TOOL_CALL messages. [FAILED] marks an isError result or a thrown exception; the other
 * markers are written by the profile gate for a call it halted and replace [FAILED].
 */
object ToolCallMarker {
    const val FAILED = "failed"
    const val AWAITING_APPROVAL = "awaiting_approval"
    const val NEEDS_CONFIRMATION = "needs_confirmation"
    const val FORBIDDEN = "forbidden"
    const val DENIED = "denied"
}

/** Per-invocation slot installed by [loggedToolHandler]; the gate writes the marker of a call it halted. */
internal class ToolCallMarkerSlot : AbstractCoroutineContextElement(Key) {
    @Volatile
    var marker: String? = null

    companion object Key : CoroutineContext.Key<ToolCallMarkerSlot>
}

/** Sets the TOOL_CALL marker of the current invocation; a no-op outside [loggedToolHandler]. */
internal suspend fun markToolCall(marker: String) {
    coroutineContext[ToolCallMarkerSlot]?.marker = marker
}

/**
 * Wraps a tool handler so every invocation records a TOOL_CALL server-log entry: un-prefixed tool name + duration
 * (approval waits included), plus a constant NON-SENSITIVE [ToolCallMarker]: the gate's marker when it halted the
 * call, else [ToolCallMarker.FAILED] on failure (isError result or thrown exception). Parameters and free-form error
 * text are NEVER logged (tool errors can echo device-derived data such as URLs). try/finally keeps the wrapper
 * transparent — NO exception is caught; a cancelled invocation is not logged (its coroutine is no longer active in
 * the finally block).
 */
internal fun loggedToolHandler(
    serverLog: ServerLogRepository,
    toolName: String,
    handler: suspend (CallToolRequest) -> CallToolResult,
): suspend (CallToolRequest) -> CallToolResult =
    { request ->
        val startNs = System.nanoTime()
        val slot = ToolCallMarkerSlot()
        var completed: CallToolResult? = null
        try {
            val result = withContext(slot) { handler(request) }
            completed = result
            result
        } finally {
            val durationMs = (System.nanoTime() - startNs) / NANOS_PER_MILLI
            val result = completed
            when {
                result != null -> {
                    serverLog.log(
                        type = ServerLogEntry.Type.TOOL_CALL,
                        message = toolCallMessage(slot.marker, result),
                        toolName = toolName,
                        durationMs = durationMs,
                    )
                }

                coroutineContext.isActive -> {
                    serverLog.log(
                        type = ServerLogEntry.Type.TOOL_CALL,
                        message = ToolCallMarker.FAILED,
                        toolName = toolName,
                        durationMs = durationMs,
                    )
                }
            }
        }
    }

private fun toolCallMessage(
    marker: String?,
    result: CallToolResult,
): String = marker ?: if (result.isError == true) ToolCallMarker.FAILED else ""

/** Shows the tool-call overlay only while [handler] runs — never while the gate waits for an approval. */
internal fun indicatedToolHandler(
    toolName: String,
    toolCallIndicator: ToolCallIndicator,
    handler: suspend (CallToolRequest) -> CallToolResult,
): suspend (CallToolRequest) -> CallToolResult =
    { request ->
        runCatching { toolCallIndicator.onToolCallStarted(toolName) }
        try {
            handler(request)
        } finally {
            runCatching { toolCallIndicator.onToolCallFinished(toolName) }
        }
    }

/** Passes every invocation through [gate]; [handler] runs only when the gate executes it. */
internal fun gatedToolHandler(
    gate: ToolPolicyGate,
    toolName: String,
    handler: suspend (CallToolRequest) -> CallToolResult,
): suspend (CallToolRequest) -> CallToolResult =
    { request ->
        gate.guard(toolName, request.arguments) { handler(request) }
    }

/** The production composition: `logged(gate(indicator(handler)))`. */
internal fun wrappedToolHandler(
    serverLog: ServerLogRepository,
    policyGate: ToolPolicyGate,
    toolCallIndicator: ToolCallIndicator,
    toolName: String,
    handler: suspend (CallToolRequest) -> CallToolResult,
): suspend (CallToolRequest) -> CallToolResult =
    loggedToolHandler(
        serverLog,
        toolName,
        gatedToolHandler(policyGate, toolName, indicatedToolHandler(toolName, toolCallIndicator, handler)),
    )

/**
 * Registers tools on [server], wrapping every handler as `logged(gate(indicator(handler)))`. A tool missing from
 * [ToolPolicyCatalog] fails registration (fail-closed: no tool can skip the profile check).
 * A class method is used instead of a `Server` extension so `addTool` stays within detekt's default
 * `LongParameterList` function cap (5 value parameters) and every `registerXxxTools` keeps its parameter count.
 */
class LoggedToolRegistrar(
    private val server: Server,
    private val serverLog: ServerLogRepository,
    private val policyGate: ToolPolicyGate,
    private val toolCallIndicator: ToolCallIndicator = ToolCallIndicator.NONE,
) {
    /** [Server.addTool] with the profile gate and per-call TOOL_CALL logging. [toolName] is the un-prefixed name. */
    fun addTool(
        toolName: String,
        name: String,
        description: String,
        inputSchema: ToolSchema,
        handler: suspend (CallToolRequest) -> CallToolResult,
    ) {
        ToolPolicyCatalog.requireClass(toolName)
        val wrapped = wrappedToolHandler(serverLog, policyGate, toolCallIndicator, toolName, handler)
        server.addTool(name = name, description = description, inputSchema = inputSchema) { request ->
            wrapped(request)
        }
    }
}
