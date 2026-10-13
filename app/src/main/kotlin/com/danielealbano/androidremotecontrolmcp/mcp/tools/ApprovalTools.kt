package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalState
import com.danielealbano.androidremotecontrolmcp.policy.approval.ApprovalStatusView
import com.danielealbano.androidremotecontrolmcp.policy.approval.ChatConfirmResult
import com.danielealbano.androidremotecontrolmcp.policy.subject.ToolPolicyCatalog
import com.danielealbano.androidremotecontrolmcp.policy.wireCode
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val PARAM_APPROVAL_ID = "approval_id"
private const val PARAM_WAIT_MS = "wait_ms"
internal const val MAX_STATUS_WAIT_MS = 25_000L
private const val KEY_APPROVAL_ID = "approval_id"
private const val KEY_STATUS = "status"
private const val KEY_EXPIRES_AT_MS = "expires_at_ms"
private const val STATUS_NOT_CONFIRMABLE = "not_confirmable"

private fun requireApprovalId(arguments: JsonObject?): String {
    val approvalId = McpToolUtils.requireString(arguments, PARAM_APPROVAL_ID)
    if (approvalId.isBlank()) {
        throw McpToolException.InvalidParams("Parameter '$PARAM_APPROVAL_ID' must not be empty")
    }
    return approvalId
}

private fun requireWaitMs(arguments: JsonObject?): Long {
    val waitMs = McpToolUtils.optionalLong(arguments, PARAM_WAIT_MS, 0L)
    if (waitMs !in 0L..MAX_STATUS_WAIT_MS) {
        throw McpToolException.InvalidParams(
            "Parameter '$PARAM_WAIT_MS' must be between 0 and $MAX_STATUS_WAIT_MS, got: $waitMs",
        )
    }
    return waitMs
}

/** Long-poll budget: [requestedMs] capped at the time left before a pending request expires; 0 when not pending. */
private fun waitBudgetMs(
    status: ApprovalStatusView,
    requestedMs: Long,
    nowMs: Long,
): Long {
    val expiresAtMs = status.expiresAtMs
    return if (status.state == ActionApprovalState.PENDING && expiresAtMs != null) {
        minOf(requestedMs, expiresAtMs - nowMs).coerceAtLeast(0L)
    } else {
        0L
    }
}

/** Server-generated status JSON; `expires_at_ms` only while pending. */
private fun statusJson(
    approvalId: String,
    state: ActionApprovalState,
    expiresAtMs: Long?,
): String =
    buildJsonObject {
        put(KEY_APPROVAL_ID, approvalId)
        put(KEY_STATUS, state.wireCode())
        if (state == ActionApprovalState.PENDING && expiresAtMs != null) {
            put(KEY_EXPIRES_AT_MS, expiresAtMs)
        }
    }.toString()

private fun statusOnlyError(status: String): CallToolResult =
    McpToolUtils.errorTextResult(buildJsonObject { put(KEY_STATUS, status) }.toString())

/**
 * MCP tool: get_approval_status
 *
 * State of an approval created by the profile gate; optional long-poll that ends when the request leaves PENDING.
 * State and expiry come from one [ActionApprovalCoordinator.statusOf] read. Action-only: server-generated text.
 */
class GetApprovalStatusTool(
    private val coordinator: ActionApprovalCoordinator,
    private val nowMs: () -> Long,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val approvalId = requireApprovalId(arguments)
        val waitMs = requireWaitMs(arguments)
        val startMs = nowMs()
        val initial = coordinator.statusOf(approvalId, startMs)
        val budgetMs = waitBudgetMs(initial, waitMs, startMs)
        val status =
            if (budgetMs > 0L) {
                coordinator.awaitDecision(approvalId, budgetMs)
                coordinator.statusOf(approvalId, nowMs())
            } else {
                initial
            }
        return McpToolUtils.textResult(statusJson(approvalId, status.state, status.expiresAtMs))
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Returns the status of an approval request returned as needs_human_confirmation or " +
                    "awaiting_approval by a call on an app with an approval profile. With wait_ms (0-25000) it " +
                    "waits until the request leaves pending or wait_ms runs out. pending: not decided yet. " +
                    "approved: repeat the original call exactly, within 2 minutes; the approval is single use. " +
                    "denied: stop, do not retry. expired: not decided within 5 minutes (or unknown id); repeat " +
                    "the original call once, and if that expires too, stop and tell the human. not_required: the " +
                    "owner added an exception; repeat the exact call, it runs without approval. already_used: an " +
                    "identical call already used this approval; re-read the screen first, the action may already " +
                    "be done; do not repeat it blindly.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject(PARAM_APPROVAL_ID) {
                                put("type", "string")
                                put(
                                    "description",
                                    "Approval id from a needs_human_confirmation or awaiting_approval result",
                                )
                            }
                            putJsonObject(PARAM_WAIT_MS) {
                                put("type", "integer")
                                put("description", "Long-poll up to this many milliseconds (0-25000, default 0)")
                            }
                        },
                    required = listOf(PARAM_APPROVAL_ID),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = ToolPolicyCatalog.GET_APPROVAL_STATUS
    }
}

/**
 * MCP tool: confirm_human_approval
 *
 * Records that the human agreed in the conversation to an ASK_IN_CHAT request (chat-strength grant). NOTIFY
 * requests are refused: only the owner approves them on the device. Action-only: server-generated text.
 */
class ConfirmHumanApprovalTool(
    private val coordinator: ActionApprovalCoordinator,
    private val nowMs: () -> Long,
) {
    suspend fun execute(arguments: JsonObject?): CallToolResult {
        val approvalId = requireApprovalId(arguments)
        return when (coordinator.confirmInChat(approvalId, nowMs())) {
            ChatConfirmResult.CONFIRMED, ChatConfirmResult.ALREADY_APPROVED -> {
                McpToolUtils.textResult(statusJson(approvalId, ActionApprovalState.APPROVED, null))
            }

            ChatConfirmResult.NOT_CONFIRMABLE -> {
                statusOnlyError(STATUS_NOT_CONFIRMABLE)
            }

            ChatConfirmResult.DENIED -> {
                statusOnlyError(ActionApprovalState.DENIED.wireCode())
            }

            ChatConfirmResult.EXPIRED -> {
                statusOnlyError(ActionApprovalState.EXPIRED.wireCode())
            }

            ChatConfirmResult.NOT_REQUIRED -> {
                statusOnlyError(ActionApprovalState.NOT_REQUIRED.wireCode())
            }

            ChatConfirmResult.ALREADY_USED -> {
                statusOnlyError(ActionApprovalState.ALREADY_USED.wireCode())
            }
        }
    }

    fun register(
        registrar: LoggedToolRegistrar,
        toolNamePrefix: String,
    ) {
        registrar.addTool(
            toolName = TOOL_NAME,
            name = "$toolNamePrefix$TOOL_NAME",
            description =
                "Call ONLY after the human explicitly agreed in the conversation to the action of a call that " +
                    "returned needs_human_confirmation. Marks that request approved and creates a single-use " +
                    "approval valid for 2 minutes; then repeat the original call exactly. Requests waiting for " +
                    "the device owner (awaiting_approval) cannot be confirmed here. not_required: repeat the " +
                    "call, it runs without approval. already_used: re-read the screen first, the action may " +
                    "already be done.",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            putJsonObject(PARAM_APPROVAL_ID) {
                                put("type", "string")
                                put("description", "Approval id from a needs_human_confirmation result")
                            }
                        },
                    required = listOf(PARAM_APPROVAL_ID),
                ),
        ) { request -> execute(request.arguments) }
    }

    companion object {
        const val TOOL_NAME = ToolPolicyCatalog.CONFIRM_HUMAN_APPROVAL
    }
}

/**
 * Registers the approval tools. ALWAYS registered and never subject to tool permissions: they act only on in-memory
 * approvals and are the only way past ASK_IN_CHAT / NOTIFY (both are NOT_APP_SCOPED in [ToolPolicyCatalog]).
 */
fun registerApprovalTools(
    registrar: LoggedToolRegistrar,
    coordinator: ActionApprovalCoordinator,
    nowMs: () -> Long,
    toolNamePrefix: String,
) {
    GetApprovalStatusTool(coordinator, nowMs).register(registrar, toolNamePrefix)
    ConfirmHumanApprovalTool(coordinator, nowMs).register(registrar, toolNamePrefix)
}
