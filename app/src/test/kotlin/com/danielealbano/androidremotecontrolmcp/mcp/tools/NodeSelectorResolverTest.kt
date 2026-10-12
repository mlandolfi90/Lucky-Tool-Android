package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PiiCategory
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.privacy.PseudonymStore
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.testutil.PrivacyToolTestDoubles
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("NodeSelectorResolver")
class NodeSelectorResolverTest {
    private val resolver =
        NodeSelectorResolver(
            ElementFinder(),
            PrivacyToolTestDoubles.passthroughGate(),
            PrivacyToolTestDoubles.identitySubstitutor(),
        )

    private fun node(
        id: String,
        text: String? = "OK",
        visible: Boolean = true,
    ): AccessibilityNodeData =
        AccessibilityNodeData(
            id = id,
            className = "android.widget.Button",
            text = text,
            bounds = BoundsData(0, 0, 100, 48),
            clickable = true,
            enabled = true,
            visible = visible,
        )

    private fun windowOf(
        children: List<AccessibilityNodeData>,
        windowId: Int = 0,
        windowType: String = "APPLICATION",
    ): WindowData =
        WindowData(
            windowId = windowId,
            windowType = windowType,
            packageName = "com.example.app",
            title = "Main",
            focused = true,
            tree =
                AccessibilityNodeData(
                    id = "node_root_w$windowId",
                    className = "android.widget.FrameLayout",
                    resourceId = "com.example.app:id/root",
                    bounds = BoundsData(0, 0, 1080, 2400),
                    enabled = true,
                    visible = true,
                    children = children,
                ),
        )

    private fun selectorOf(
        value: String = "OK",
        resolverToUse: NodeSelectorResolver = resolver,
    ): NodeSelector {
        val target =
            resolverToUse.parseTarget(
                buildJsonObject {
                    put("by", "text")
                    put("value", value)
                },
            )
        return (target as NodeTarget.BySelector).selector
    }

    private fun placeholderResolver(): Triple<NodeSelectorResolver, String, String> {
        val store = PseudonymStore()
        val real = "a@b.co"
        val placeholder = store.placeholderFor(real, PiiCategory.EMAILS, PlaceholderFormat.HASHED)
        val withStore =
            NodeSelectorResolver(
                ElementFinder(),
                PrivacyToolTestDoubles.passthroughGate(),
                PlaceholderSubstitutor(store),
            )
        return Triple(withStore, placeholder, real)
    }

    private fun invalidParamsOf(arguments: JsonObject?): String =
        assertThrows<McpToolException.InvalidParams> { resolver.parseTarget(arguments) }.message!!

    @Test
    fun `node_id only returns ById`() {
        val target = resolver.parseTarget(buildJsonObject { put("node_id", "node_a") })
        assertEquals(NodeTarget.ById("node_a"), target)
    }

    @Test
    fun `selector only returns BySelector`() {
        val selector = selectorOf()
        assertEquals(FindBy.TEXT, selector.findBy)
        assertEquals("OK", selector.value)
        assertFalse(selector.exactMatch)
    }

    @Test
    fun `exact_match true is parsed`() {
        val target =
            resolver.parseTarget(
                buildJsonObject {
                    put("by", "text")
                    put("value", "OK")
                    put("exact_match", true)
                },
            )
        assertTrue((target as NodeTarget.BySelector).selector.exactMatch)
    }

    @Test
    fun `node_id with selector is rejected`() {
        val message =
            invalidParamsOf(
                buildJsonObject {
                    put("node_id", "node_a")
                    put("by", "text")
                    put("value", "OK")
                },
            )
        assertTrue(message.contains("not both"), message)
    }

    @Test
    fun `neither form is rejected`() {
        val message = invalidParamsOf(buildJsonObject {})
        assertTrue(message.contains("Missing required parameter 'node_id'"), message)
    }

    @Test
    fun `null arguments are rejected`() {
        val message = invalidParamsOf(null)
        assertTrue(message.contains("Missing required parameter 'node_id'"), message)
    }

    @Test
    fun `by without value is rejected`() {
        val message = invalidParamsOf(buildJsonObject { put("by", "text") })
        assertTrue(message.contains("Missing required parameter 'value'"), message)
    }

    @Test
    fun `value without by is rejected`() {
        val message = invalidParamsOf(buildJsonObject { put("value", "OK") })
        assertTrue(message.contains("Missing required parameter 'by'"), message)
    }

    @Test
    fun `empty value is rejected`() {
        val message =
            invalidParamsOf(
                buildJsonObject {
                    put("by", "text")
                    put("value", "")
                },
            )
        assertTrue(message.contains("'value' must be non-empty"), message)
    }

    @Test
    fun `empty node_id is rejected`() {
        val message = invalidParamsOf(buildJsonObject { put("node_id", "") })
        assertTrue(message.contains("'node_id' must be non-empty"), message)
    }

    @Test
    fun `invalid by is rejected`() {
        val message =
            invalidParamsOf(
                buildJsonObject {
                    put("by", "tag")
                    put("value", "OK")
                },
            )
        assertTrue(message.contains("Invalid 'by' value"), message)
    }

    @Test
    fun `exact_match with node_id is rejected`() {
        val message =
            invalidParamsOf(
                buildJsonObject {
                    put("node_id", "node_a")
                    put("exact_match", true)
                },
            )
        assertTrue(message.contains("only valid with a selector"), message)
    }

    @Test
    fun `placeholder value is substituted for matching`() {
        val (withStore, placeholder, real) = placeholderResolver()
        val selector = selectorOf(value = placeholder, resolverToUse = withStore)
        assertEquals(real, selector.value)
    }

    @Test
    fun `toString omits values`() {
        val (withStore, placeholder, real) = placeholderResolver()
        val rendered = selectorOf(value = placeholder, resolverToUse = withStore).toString()
        assertFalse(rendered.contains(placeholder), rendered)
        assertFalse(rendered.contains(real), rendered)
    }

    @Test
    fun `single on-screen match resolves`() {
        val windows = listOf(windowOf(listOf(node("node_ok"))))

        val resolution = resolver.resolve(windows, selectorOf())

        assertEquals("node_ok", (resolution as SelectorResolution.Resolved).element.id)
    }

    @Test
    fun `off-screen duplicate is ignored`() {
        val windows = listOf(windowOf(listOf(node("node_ok"), node("node_hidden", visible = false))))

        val resolution = resolver.resolve(windows, selectorOf())

        assertEquals("node_ok", (resolution as SelectorResolution.Resolved).element.id)
    }

    @Test
    fun `two on-screen matches are ambiguous`() {
        val windows = listOf(windowOf(listOf(node("node_ok1"), node("node_ok2"))))

        val resolution = resolver.resolve(windows, selectorOf())

        val ambiguous = resolution as SelectorResolution.Ambiguous
        assertEquals(2, ambiguous.onScreenCount)
        assertEquals(listOf("node_ok1", "node_ok2"), ambiguous.matches.map { it.id })
    }

    @Test
    fun `resolves a unique match in a second window`() {
        val windows =
            listOf(
                windowOf(listOf(node("node_other", text = "Cancel")), windowId = 42),
                windowOf(listOf(node("node_dialog_ok")), windowId = 99, windowType = "SYSTEM"),
            )

        val resolution = resolver.resolve(windows, selectorOf())

        assertEquals("node_dialog_ok", (resolution as SelectorResolution.Resolved).element.id)
    }

    @Test
    fun `resolve log line omits selector values`() {
        val (withStore, placeholder, real) = placeholderResolver()
        val debugLines = mutableListOf<String>()
        mockkStatic(Log::class)
        try {
            every { Log.d(any<String>(), capture(debugLines)) } returns 0
            val windows = listOf(windowOf(listOf(node("node_ok", text = real))))

            withStore.resolve(windows, selectorOf(value = placeholder, resolverToUse = withStore))

            assertTrue(debugLines.isNotEmpty(), "resolve should log a debug line")
            assertTrue(debugLines.none { it.contains(placeholder) }, debugLines.toString())
            assertTrue(debugLines.none { it.contains(real) }, debugLines.toString())
        } finally {
            unmockkStatic(Log::class)
        }
    }

    @Test
    fun `no match throws NodeNotFound`() {
        val windows = listOf(windowOf(listOf(node("node_other", text = "Cancel"))))

        val message =
            assertThrows<McpToolException.NodeNotFound> {
                resolver.resolve(windows, selectorOf())
            }.message!!

        assertTrue(message.contains("No on-screen node matches selector"), message)
        assertFalse(message.contains("off-screen"), message)
    }

    @Test
    fun `only off-screen matches throw NodeNotFound with ids`() {
        val windows = listOf(windowOf(listOf(node("node_hidden", visible = false))))

        val message =
            assertThrows<McpToolException.NodeNotFound> {
                resolver.resolve(windows, selectorOf())
            }.message!!

        assertTrue(message.contains("node_hidden"), message)
        assertTrue(message.contains("scroll_to_node"), message)
    }

    @Test
    fun `NodeNotFound echoes no selector value`() {
        val (withStore, placeholder, real) = placeholderResolver()
        val selector = selectorOf(value = placeholder, resolverToUse = withStore)

        val noMatch =
            assertThrows<McpToolException.NodeNotFound> {
                withStore.resolve(listOf(windowOf(listOf(node("node_other", text = "Cancel")))), selector)
            }.message!!
        val offScreenOnly =
            assertThrows<McpToolException.NodeNotFound> {
                withStore.resolve(
                    listOf(windowOf(listOf(node("node_hidden", text = real, visible = false)))),
                    selector,
                )
            }.message!!

        listOf(noMatch, offScreenOnly).forEach { message ->
            assertFalse(message.contains(placeholder), message)
            assertFalse(message.contains(real), message)
        }
    }

    @Test
    fun `ambiguityResult is untrusted error`() =
        runTest {
            val windows = listOf(windowOf(listOf(node("node_ok1"), node("node_ok2"))))
            val ambiguous = resolver.resolve(windows, selectorOf()) as SelectorResolution.Ambiguous

            val result = resolver.ambiguityResult(ambiguous)

            assertEquals(true, result.isError)
            val text = (result.content[0] as TextContent).text
            assertTrue(text.startsWith("${McpToolUtils.UNTRUSTED_CONTENT_WARNING}\n"))
            val parsed = Json.parseToJsonElement(stripUntrustedWarning(text)).jsonObject
            assertEquals("ambiguous_selector", parsed["error"]!!.jsonPrimitive.content)
            assertEquals(2, parsed["match_count"]!!.jsonPrimitive.content.toInt())
            assertEquals(
                listOf("node_ok1", "node_ok2"),
                parsed["candidates"]!!.jsonArray.map { it.jsonObject["node_id"]!!.jsonPrimitive.content },
            )
            assertTrue(parsed["hint"]!!.jsonPrimitive.content.isNotEmpty())
        }

    @Test
    fun `ambiguityResult caps candidates at 5`() =
        runTest {
            val windows = listOf(windowOf((1..7).map { node("node_ok$it") }))
            val ambiguous = resolver.resolve(windows, selectorOf()) as SelectorResolution.Ambiguous

            val result = resolver.ambiguityResult(ambiguous)

            val parsed =
                Json
                    .parseToJsonElement(
                        stripUntrustedWarning((result.content[0] as TextContent).text),
                    ).jsonObject
            assertEquals(7, parsed["match_count"]!!.jsonPrimitive.content.toInt())
            assertEquals(NodeSelectorResolver.MAX_AMBIGUOUS_CANDIDATES, parsed["candidates"]!!.jsonArray.size)
        }

    @Test
    fun `ambiguityResult lists only on-screen candidates`() =
        runTest {
            val windows =
                listOf(
                    windowOf(
                        listOf(node("node_ok1"), node("node_ok2"), node("node_hidden", visible = false)),
                    ),
                )
            val ambiguous = resolver.resolve(windows, selectorOf()) as SelectorResolution.Ambiguous

            val result = resolver.ambiguityResult(ambiguous)

            val ids =
                Json
                    .parseToJsonElement(
                        stripUntrustedWarning((result.content[0] as TextContent).text),
                    ).jsonObject["candidates"]!!
                    .jsonArray
                    .map { it.jsonObject["node_id"]!!.jsonPrimitive.content }
            assertEquals(listOf("node_ok1", "node_ok2"), ids)
        }

    @Test
    fun `ambiguityResult redacts the full match list`() =
        runTest {
            val gate = mockk<PrivacyToolGate>()
            coEvery { gate.texts(any()) } answers {
                firstArg<List<Pair<String?, String>>>().map { "REDACTED" }
            }
            val withGate =
                NodeSelectorResolver(ElementFinder(), gate, PrivacyToolTestDoubles.identitySubstitutor())
            val windows =
                listOf(
                    windowOf(
                        listOf(node("node_ok1"), node("node_ok2"), node("node_hidden", visible = false)),
                    ),
                )
            val ambiguous = withGate.resolve(windows, selectorOf()) as SelectorResolution.Ambiguous

            val result = withGate.ambiguityResult(ambiguous)

            val candidates =
                Json
                    .parseToJsonElement(
                        stripUntrustedWarning((result.content[0] as TextContent).text),
                    ).jsonObject["candidates"]!!
                    .jsonArray
                    .map { it.jsonObject }
            candidates.forEach { candidate ->
                assertEquals("REDACTED", candidate["text"]!!.jsonPrimitive.content)
                assertEquals("REDACTED", candidate["contentDescription"]!!.jsonPrimitive.content)
            }
            coVerify { gate.texts(match { it.size == 6 }) }
        }
}
