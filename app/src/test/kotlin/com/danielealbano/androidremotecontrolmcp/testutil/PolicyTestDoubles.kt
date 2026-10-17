package com.danielealbano.androidremotecontrolmcp.testutil

import com.danielealbano.androidremotecontrolmcp.policy.gate.ToolPolicyGate
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject

/** Policy doubles for unit tests that register tools but do not exercise approval profiles. */
object PolicyTestDoubles {
    fun passthroughGate(): ToolPolicyGate = PassthroughToolPolicyGate

    private object PassthroughToolPolicyGate : ToolPolicyGate {
        override suspend fun guard(
            toolName: String,
            arguments: JsonObject?,
            execute: suspend () -> CallToolResult,
        ): CallToolResult = execute()
    }
}
