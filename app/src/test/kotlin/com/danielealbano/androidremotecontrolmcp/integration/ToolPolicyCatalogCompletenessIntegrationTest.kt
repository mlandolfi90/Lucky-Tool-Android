package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.policy.subject.ToolPolicyCatalog
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ToolPolicyCatalogCompletenessIntegrationTest {
    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    private suspend fun registeredNames(): Set<String> {
        val prefix = McpToolUtils.buildToolNamePrefix("")
        var names = emptySet<String>()
        McpIntegrationTestHelper.withTestApplication { client, _ ->
            names =
                client
                    .listTools()
                    .tools
                    .map { it.name.removePrefix(prefix) }
                    .toSet()
        }
        return names
    }

    @Test
    fun `every registered tool has a catalog entry`() =
        runTest {
            val missing = registeredNames() - ToolPolicyCatalog.classes.keys

            assertTrue(missing.isEmpty(), "Tools without a ToolPolicyCatalog entry: $missing")
        }

    @Test
    fun `every catalog entry is registered except the approval tools`() =
        runTest {
            val unregistered = ToolPolicyCatalog.classes.keys - registeredNames()

            assertTrue(
                setOf(ToolPolicyCatalog.GET_APPROVAL_STATUS, ToolPolicyCatalog.CONFIRM_HUMAN_APPROVAL)
                    .containsAll(unregistered),
                "Catalog entries never registered: $unregistered",
            )
        }

    @Test
    fun `listing is not vacuous`() =
        runTest {
            assertTrue(registeredNames().size >= MIN_REGISTERED_TOOLS)
        }

    private companion object {
        const val MIN_REGISTERED_TOOLS = 57
    }
}
