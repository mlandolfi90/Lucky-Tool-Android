package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.policy.gate.ToolPolicyGate
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LoggedToolRegistrarTest {
    private val server = mockk<Server>(relaxed = true)
    private val serverLog = RecordingServerLogRepository()
    private val events = mutableListOf<String>()
    private val indicator =
        object : ToolCallIndicator {
            override fun onToolCallStarted(toolName: String) {
                events += "started:$toolName"
            }

            override fun onToolCallFinished(toolName: String) {
                events += "finished:$toolName"
            }

            override fun setEnabled(enabled: Boolean) {
                events += "enabled:$enabled"
            }
        }

    /** Records what it sees; executes the call, or halts it with the FORBIDDEN marker. */
    private inner class RecordingGate(
        private val halt: Boolean,
    ) : ToolPolicyGate {
        var toolName: String? = null
        var arguments: JsonObject? = null

        override suspend fun guard(
            toolName: String,
            arguments: JsonObject?,
            execute: suspend () -> CallToolResult,
        ): CallToolResult {
            this.toolName = toolName
            this.arguments = arguments
            events += "gate-start"
            val result =
                if (halt) {
                    markToolCall(ToolCallMarker.FORBIDDEN)
                    CallToolResult(content = listOf(TextContent(text = "halted")), isError = true)
                } else {
                    execute()
                }
            events += "gate-end"
            return result
        }
    }

    private var handlerCalls = 0
    private val handler: suspend (CallToolRequest) -> CallToolResult = {
        handlerCalls++
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    private fun request(arguments: JsonObject): CallToolRequest {
        val request = mockk<CallToolRequest>()
        every { request.arguments } returns arguments
        return request
    }

    private fun emptyRequest(): CallToolRequest = request(JsonObject(emptyMap()))

    private fun wrapped(halt: Boolean): suspend (CallToolRequest) -> CallToolResult =
        wrappedToolHandler(serverLog, RecordingGate(halt), indicator, "tap", handler)

    @Test
    fun `addTool throws for a tool missing from the catalog`() {
        val registrar = LoggedToolRegistrar(server, serverLog, RecordingGate(halt = false))

        assertThrows<IllegalStateException> {
            registrar.addTool("not_a_tool", "android_not_a_tool", "d", ToolSchema(), handler)
        }
        verify(exactly = 0) { server.addTool(name = any(), description = any(), inputSchema = any(), handler = any()) }
    }

    @Test
    fun `addTool registers a classified tool once`() {
        val registrar = LoggedToolRegistrar(server, serverLog, RecordingGate(halt = false))

        registrar.addTool("tap", "android_tap", "d", ToolSchema(), handler)

        verify(exactly = 1) {
            server.addTool(name = "android_tap", description = any(), inputSchema = any(), handler = any())
        }
    }

    @Test
    fun `gate receives the un-prefixed name and the request arguments`() =
        runTest {
            val gate = RecordingGate(halt = false)
            val arguments = buildJsonObject { put("x", 1) }

            wrappedToolHandler(serverLog, gate, indicator, "tap", handler)(request(arguments))

            assertEquals("tap", gate.toolName)
            assertSame(arguments, gate.arguments)
        }

    @Test
    fun `indicator is not shown when the gate halts`() =
        runTest {
            wrapped(halt = true)(emptyRequest())

            assertFalse(events.any { it.startsWith("started") || it.startsWith("finished") })
            assertEquals(0, handlerCalls)
        }

    @Test
    fun `indicator runs inside the gate when the gate executes`() =
        runTest {
            wrapped(halt = false)(emptyRequest())

            assertEquals(listOf("gate-start", "started:tap", "finished:tap", "gate-end"), events)
            assertEquals(1, handlerCalls)
        }

    @Test
    fun `halted call logs the gate marker`() =
        runTest {
            val result = wrapped(halt = true)(emptyRequest())

            assertTrue(result.isError == true)
            assertEquals(ToolCallMarker.FORBIDDEN, serverLog.ofType(ServerLogEntry.Type.TOOL_CALL).single().message)
        }
}
