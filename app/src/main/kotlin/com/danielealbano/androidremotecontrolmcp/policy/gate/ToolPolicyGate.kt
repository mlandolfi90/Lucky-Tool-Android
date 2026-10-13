package com.danielealbano.androidremotecontrolmcp.policy.gate

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject

/**
 * The profile check every MCP tool call passes. [toolName] is un-prefixed; [execute] runs the tool (at most once)
 * and is not called when the gate halts the call. There is no pass-through implementation in production.
 */
interface ToolPolicyGate {
    suspend fun guard(
        toolName: String,
        arguments: JsonObject?,
        execute: suspend () -> CallToolResult,
    ): CallToolResult
}
