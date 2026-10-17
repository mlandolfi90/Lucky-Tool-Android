package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class ToolSubjectResolverImplTest {
    private val windowResolver = mockk<WindowSubjectResolver>()
    private val nodeResolver = mockk<NodeSubjectResolver>()
    private val coordinateResolver = mockk<CoordinateSubjectResolver>()
    private val textResolver = mockk<TextSubjectResolver>()
    private val notificationResolver = mockk<NotificationSubjectResolver>()
    private val launchResolver = mockk<LaunchSubjectResolver>()
    private val resolver =
        ToolSubjectResolverImpl(
            windowResolver,
            nodeResolver,
            coordinateResolver,
            textResolver,
            notificationResolver,
            launchResolver,
        )
    private val arguments = buildJsonObject { put("x", 1) }
    private val profiled = setOf(SubjectFixtures.BANK)

    private fun resolverOf(kind: ResolverKind): PolicySubjectResolver =
        when (kind) {
            ResolverKind.WINDOW -> windowResolver
            ResolverKind.NODE -> nodeResolver
            ResolverKind.COORDINATE -> coordinateResolver
            ResolverKind.TEXT -> textResolver
            ResolverKind.NOTIFICATION -> notificationResolver
            ResolverKind.LAUNCH -> launchResolver
            ResolverKind.NONE -> error("No resolver for NONE")
        }

    @Test
    fun `NOT_APP_SCOPED tool returns NotAppScoped without calling any resolver`() =
        runTest {
            assertEquals(SubjectResolution.NotAppScoped, resolver.resolve("read_file", arguments, profiled))
        }

    @Test
    fun `EXEMPT_EXIT tool returns ExemptExit`() =
        runTest {
            assertEquals(SubjectResolution.ExemptExit, resolver.resolve("press_home", arguments, profiled))
        }

    @ParameterizedTest
    @CsvSource(
        "get_screen_state, WINDOW",
        "click_node, NODE",
        "tap, COORDINATE",
        "type_append_text, TEXT",
        "notification_open, NOTIFICATION",
        "send_intent, LAUNCH",
    )
    fun `app-scoped tool is routed to its kind's resolver with the request`(
        toolName: String,
        kind: ResolverKind,
    ) = runTest {
        val target = resolverOf(kind)
        val policyClass = ToolPolicyCatalog.requireClass(toolName)
        val expected = SubjectResolution.Unattributed
        coEvery { target.resolve(any(), any()) } returns expected

        val result = resolver.resolve(toolName, arguments, profiled)

        assertSame(expected, result)
        coVerify { target.resolve(policyClass, SubjectRequest(toolName, arguments, profiled)) }
    }

    @Test
    fun `unknown tool throws IllegalStateException`() =
        runTest {
            assertThrows<IllegalStateException> { resolver.resolve("nope", arguments, profiled) }
        }

    @Test
    fun `resolver exception propagates unchanged`() =
        runTest {
            val failure = McpToolException.NodeNotFound("Node 'x' not found in accessibility tree")
            coEvery { nodeResolver.resolve(any(), any()) } throws failure

            val thrown =
                assertThrows<McpToolException.NodeNotFound> { resolver.resolve("click_node", arguments, profiled) }

            assertSame(failure, thrown)
        }

    @Test
    fun `SubjectRequest toString omits arguments`() {
        val request = SubjectRequest("type_append_text", buildJsonObject { put("text", "1500") }, profiled)

        assertFalse(request.toString().contains("1500"))
    }
}
