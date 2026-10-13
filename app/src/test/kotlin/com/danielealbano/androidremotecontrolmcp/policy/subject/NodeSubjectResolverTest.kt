package com.danielealbano.androidremotecontrolmcp.policy.subject

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.tools.NodeSelectorResolver
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicySubject
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.BANK
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.FULL_SCREEN
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.KEYBOARD
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.OWN_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.SHOP
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.SYSTEM_UI
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.button
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.container
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.read
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.request
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.window
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import com.danielealbano.androidremotecontrolmcp.testutil.PrivacyToolTestDoubles
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class NodeSubjectResolverTest {
    private val windowSource = FakePolicyWindowSource()
    private val elementFinder = ElementFinder()
    private val resolver =
        NodeSubjectResolver(
            windowSource,
            NodeSelectorResolver(
                elementFinder,
                PrivacyToolTestDoubles.passthroughGate(),
                PrivacyToolTestDoubles.identitySubstitutor(),
            ),
            elementFinder,
            WindowHitTester(OWN_PACKAGE),
            OWN_PACKAGE,
        )

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun app(
        packageName: String?,
        children: List<AccessibilityNodeData>,
        layer: Int = 1,
        type: String = SubjectSupport.WINDOW_TYPE_APPLICATION,
        focused: Boolean = false,
        bounds: BoundsData = FULL_SCREEN,
    ): WindowData = window(packageName, type, container("root_$layer", bounds, children), focused, layer)

    private fun payButton(
        id: String,
        top: Int = 100,
        visible: Boolean = true,
    ): AccessibilityNodeData =
        button(id, BoundsData(100, top, 300, top + 100), "Pagar", resourceId = "com.example:id/$id")
            .copy(visible = visible)

    private fun byId(nodeId: String): JsonObject = buildJsonObject { put("node_id", nodeId) }

    private fun bySelector(value: String): JsonObject =
        buildJsonObject {
            put("by", "text")
            put("value", value)
        }

    private suspend fun resolve(
        toolName: String,
        policyClass: ToolPolicyClass,
        arguments: JsonObject,
        profiled: Set<String> = emptySet(),
    ): SubjectResolution = resolver.resolve(policyClass, request(toolName, arguments, profiled))

    private suspend fun click(
        arguments: JsonObject,
        profiled: Set<String> = emptySet(),
    ): SubjectResolution = resolve("click_node", ToolPolicyClass.NODE_CLICK, arguments, profiled)

    private fun subjectOf(resolution: SubjectResolution): PolicySubject =
        (resolution as SubjectResolution.Resolved).subject

    @Test
    fun `click_node by node_id resolves a classified ACTION_BUTTON on the node's window package`() =
        runTest {
            windowSource.windows =
                read(app(BANK, listOf(payButton("other")), focused = true), app(SHOP, listOf(payButton("pay")), 2))

            val subject = subjectOf(click(byId("pay")))

            assertEquals(listOf(SHOP), subject.targets.map { it.packageName })
            assertEquals(PolicyActionType.ACTION_BUTTON, subject.actionType)
            assertTrue(subject.classifyTarget)
        }

    @Test
    fun `click_node by selector resolves the single on-screen match`() =
        runTest {
            windowSource.windows = read(app(BANK, listOf(payButton("pay")), focused = true))

            val subject = subjectOf(click(bySelector("Pagar")))

            assertEquals("Pagar", subject.targets.single().nodeLabel)
        }

    @Test
    fun `click_node ambiguous selector yields one target per on-screen candidate`() =
        runTest {
            windowSource.windows =
                read(
                    app(
                        BANK,
                        listOf(payButton("a"), payButton("b", top = 300), payButton("c", top = 500, visible = false)),
                        focused = true,
                    ),
                )

            val subject = subjectOf(click(bySelector("Pagar")))

            assertEquals(2, subject.targets.size)
        }

    @Test
    fun `click_node selector without on-screen match throws NodeNotFound`() =
        runTest {
            windowSource.windows = read(app(BANK, listOf(payButton("pay")), focused = true))

            assertThrows<McpToolException.NodeNotFound> { click(bySelector("Transferir")) }
        }

    @Test
    fun `click_node unknown node_id throws NodeNotFound`() =
        runTest {
            windowSource.windows = read(app(BANK, listOf(payButton("pay")), focused = true))

            val error = assertThrows<McpToolException.NodeNotFound> { click(byId("x")) }

            assertEquals("Node 'x' not found in accessibility tree", error.message)
        }

    @Test
    fun `click_node with node_id and selector throws InvalidParams before reading`() =
        runTest {
            val arguments =
                buildJsonObject {
                    put("node_id", "pay")
                    put("by", "text")
                    put("value", "Pagar")
                }

            assertThrows<McpToolException.InvalidParams> { click(arguments) }
            assertEquals(0, windowSource.readCount)
        }

    @Test
    fun `long_click_node requires node_id, ignores selector arguments, is classified`() =
        runTest {
            windowSource.windows = read(app(BANK, listOf(payButton("pay")), focused = true))

            val error =
                assertThrows<McpToolException.InvalidParams> {
                    resolve("long_click_node", ToolPolicyClass.NODE_LONG_CLICK, bySelector("Pagar"))
                }
            val arguments =
                buildJsonObject {
                    put("node_id", "pay")
                    put("by", "text")
                }
            val subject = subjectOf(resolve("long_click_node", ToolPolicyClass.NODE_LONG_CLICK, arguments))

            assertEquals("Missing required parameter 'node_id'", error.message)
            assertEquals(BANK, subject.targets.single().packageName)
            assertTrue(subject.classifyTarget)
        }

    @Test
    fun `tap_node adds the hit-test targets at the node center`() =
        runTest {
            windowSource.windows =
                read(
                    app(BANK, listOf(payButton("pay")), focused = true),
                    app(SYSTEM_UI, emptyList(), layer = 5, type = "SYSTEM", bounds = BoundsData(0, 0, 1080, 400)),
                )

            val subject = subjectOf(resolve("tap_node", ToolPolicyClass.NODE_TAP, byId("pay")))

            assertEquals(setOf(BANK, SYSTEM_UI), subject.targets.map { it.packageName }.toSet())
            assertTrue(subject.classifyTarget)
        }

    @Test
    fun `scroll_to_node resolves unclassified NAVIGATE package-only`() =
        runTest {
            windowSource.windows = read(app(BANK, listOf(payButton("pay")), focused = true))

            val subject = subjectOf(resolve("scroll_to_node", ToolPolicyClass.NODE_SCROLL, byId("pay")))

            assertEquals(PolicyActionType.NAVIGATE, subject.actionType)
            assertEquals(PolicyTargets.packageOnly(BANK), subject.targets.single())
            assertFalse(subject.classifyTarget)
        }

    private fun nodeIds(vararg ids: String): JsonObject =
        buildJsonObject { putJsonArray("node_ids") { ids.forEach { add(it) } } }

    @Test
    fun `get_node_details reads only the windows of the requested ids`() =
        runTest {
            windowSource.windows =
                read(app(BANK, listOf(payButton("a")), focused = true), app(SHOP, listOf(payButton("b")), 2))

            val subject = subjectOf(resolve("get_node_details", ToolPolicyClass.NODE_DETAILS_READ, nodeIds("b")))

            assertEquals(PolicyActionType.READ, subject.actionType)
            assertEquals(listOf(SHOP), subject.targets.map { it.packageName })
        }

    @Test
    fun `get_node_details with no known id reads every window`() =
        runTest {
            windowSource.windows =
                read(app(BANK, listOf(payButton("a")), focused = true), app(SHOP, listOf(payButton("b")), 2))

            val subject = subjectOf(resolve("get_node_details", ToolPolicyClass.NODE_DETAILS_READ, nodeIds("zz")))

            assertEquals(listOf(BANK, SHOP), subject.targets.map { it.packageName })
        }

    private fun keyboardWindow(): WindowData =
        app(KEYBOARD, listOf(payButton("key")), layer = 5, type = SubjectSupport.WINDOW_TYPE_INPUT_METHOD)

    @Test
    fun `click_node on a keyboard node while a profiled app is focused is Blocked`() =
        runTest {
            windowSource.windows = read(app(BANK, emptyList(), focused = true), keyboardWindow())

            assertEquals(
                SubjectResolution.Blocked(BANK, PolicyActionType.ACTION_BUTTON, BlockCause.IME_INPUT_IN_PROFILED_APP),
                click(byId("key"), setOf(BANK)),
            )
        }

    @Test
    fun `click_node on a keyboard node while this app is focused is Blocked with zero profiles`() =
        runTest {
            windowSource.windows = read(app(OWN_PACKAGE, emptyList(), focused = true), keyboardWindow())

            assertEquals(
                SubjectResolution.Blocked(
                    OWN_PACKAGE,
                    PolicyActionType.ACTION_BUTTON,
                    BlockCause.IME_INPUT_IN_PROFILED_APP,
                ),
                click(byId("key")),
            )
        }

    @Test
    fun `click_node ambiguous selector over two apps targets both apps`() =
        runTest {
            windowSource.windows =
                read(app(BANK, listOf(payButton("a")), focused = true), app(SHOP, listOf(payButton("b")), 2))

            val subject = subjectOf(click(bySelector("Pagar")))

            assertEquals(setOf(BANK, SHOP), subject.targets.map { it.packageName }.toSet())
        }

    @Test
    fun `get_node_details on a keyboard node is not IME-blocked`() =
        runTest {
            windowSource.windows = read(app(BANK, emptyList(), focused = true), keyboardWindow())

            val resolution =
                resolve("get_node_details", ToolPolicyClass.NODE_DETAILS_READ, nodeIds("key"), setOf(BANK))

            assertEquals(listOf(KEYBOARD), subjectOf(resolution).targets.map { it.packageName })
        }

    @Test
    fun `node in a window without package`() =
        runTest {
            windowSource.windows = read(app(null, listOf(payButton("pay")), focused = true))

            assertEquals(
                SubjectResolution.Blocked(null, PolicyActionType.ACTION_BUTTON, BlockCause.TARGET_UNRESOLVED),
                click(byId("pay"), setOf(BANK)),
            )
            assertEquals(SubjectResolution.Unattributed, click(byId("pay")))
        }

    @Test
    fun `degraded read is Unattributed without profiles`() =
        runTest {
            windowSource.windows = MultiWindowResult(listOf(app(BANK, listOf(payButton("pay")))), degraded = true)

            assertEquals(SubjectResolution.Unattributed, click(byId("pay")))
        }
}
