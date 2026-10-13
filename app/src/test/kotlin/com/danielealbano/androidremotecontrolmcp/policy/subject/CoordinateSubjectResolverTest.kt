package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicySubject
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.SelectorKind
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
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CoordinateSubjectResolverTest {
    private val windowSource = FakePolicyWindowSource()
    private val resolver = CoordinateSubjectResolver(windowSource, WindowHitTester(OWN_PACKAGE), OWN_PACKAGE)

    private fun app(
        packageName: String?,
        children: List<AccessibilityNodeData> = emptyList(),
        bounds: BoundsData = FULL_SCREEN,
        layer: Int = 1,
        type: String = SubjectSupport.WINDOW_TYPE_APPLICATION,
        focused: Boolean = true,
    ): WindowData = window(packageName, type, container("root_$layer", bounds, children), focused, layer)

    private fun keyboard(bounds: BoundsData = BoundsData(0, 1600, 1080, 2400)): WindowData =
        app(KEYBOARD, bounds = bounds, layer = 5, type = SubjectSupport.WINDOW_TYPE_INPUT_METHOD, focused = false)

    private fun point(
        x: Number,
        y: Number,
    ): JsonObject =
        buildJsonObject {
            put("x", x)
            put("y", y)
        }

    private suspend fun resolve(
        toolName: String,
        policyClass: ToolPolicyClass,
        arguments: JsonObject,
        profiled: Set<String> = emptySet(),
    ): SubjectResolution = resolver.resolve(policyClass, request(toolName, arguments, profiled))

    private suspend fun tap(
        x: Number,
        y: Number,
        profiled: Set<String> = emptySet(),
    ): SubjectResolution = resolve("tap", ToolPolicyClass.POINT_GESTURE, point(x, y), profiled)

    private fun subjectOf(resolution: SubjectResolution): PolicySubject =
        (resolution as SubjectResolution.Resolved).subject

    private fun pathOf(
        x: Int,
        y: Int,
        points: Int = 2,
    ) = buildJsonArray {
        repeat(points) { index ->
            addJsonObject {
                put("x", x)
                put("y", y + index)
                put("time", index * 100)
            }
        }
    }

    private fun gesture(paths: List<JsonElement>): JsonObject = buildJsonObject { put("paths", JsonArray(paths)) }

    @Test
    fun `tap on a labeled button resolves a classified ACTION_BUTTON subject`() =
        runTest {
            windowSource.windows = read(app(BANK, listOf(button("b", BoundsData(0, 0, 500, 500), "Ver movimientos"))))

            val subject = subjectOf(tap(100, 100))

            val target = subject.targets.single()
            assertEquals("Ver movimientos", target.nodeLabel)
            assertEquals(SelectorKind.LABEL, target.selector?.kind)
            assertTrue(target.identifiable)
            assertEquals(PolicyActionType.ACTION_BUTTON, subject.actionType)
            assertTrue(subject.classifyTarget)
        }

    @Test
    fun `tap on an unlabeled view without id yields an unidentifiable target`() =
        runTest {
            val icon = AccessibilityNodeData(id = "icon", bounds = ICON_BOUNDS, clickable = true, visible = true)
            windowSource.windows = read(app(BANK, listOf(icon)))

            val subject = subjectOf(tap(100, 100))

            assertNull(subject.targets.single().selector)
            assertFalse(subject.targets.single().identifiable)
            assertTrue(subject.classifyTarget)
        }

    @Test
    fun `tap hitting an overlay and an app yields one target per window`() =
        runTest {
            windowSource.windows = read(app(BANK), app(SYSTEM_UI, layer = 5, type = "SYSTEM", focused = false))

            val subject = subjectOf(tap(100, 100))

            assertEquals(listOf(SYSTEM_UI, BANK), subject.targets.map { it.packageName })
        }

    @Test
    fun `long_press and double_tap use x and y and classify like tap`() =
        runTest {
            windowSource.windows = read(app(BANK, listOf(button("b", BoundsData(0, 0, 500, 500), "Ver"))))

            listOf("long_press", "double_tap").forEach { tool ->
                val subject = subjectOf(resolve(tool, ToolPolicyClass.POINT_GESTURE, point(100, 100)))

                assertEquals(BANK, subject.targets.single().packageName)
                assertTrue(subject.classifyTarget)
            }
        }

    @Test
    fun `swipe resolves unclassified package-only ACTION_BUTTON at the start point`() =
        runTest {
            windowSource.windows =
                read(
                    app(BANK, listOf(button("b", BoundsData(0, 0, 1080, 1000), "Deslizar")), TOP_HALF),
                    app(SHOP, bounds = BoundsData(0, 1000, 1080, 2400), layer = 2, focused = false),
                )
            val arguments =
                buildJsonObject {
                    put("x1", 100)
                    put("y1", 500)
                    put("x2", 100)
                    put("y2", 2000)
                }

            val subject = subjectOf(resolve("swipe", ToolPolicyClass.SWIPE, arguments))

            val target = subject.targets.single()
            assertEquals(BANK, target.packageName)
            assertNull(target.nodeLabel)
            assertNull(target.selector)
            assertEquals(PolicyActionType.ACTION_BUTTON, subject.actionType)
            assertFalse(subject.classifyTarget)
        }

    @Test
    fun `scroll hit-tests the screen center as unclassified NAVIGATE`() =
        runTest {
            windowSource.windows =
                read(
                    app(BANK),
                    app(SHOP, bounds = BoundsData(0, 1400, 1080, 2400), layer = 2, focused = false),
                )
            val arguments = buildJsonObject { put("direction", "down") }

            val subject = subjectOf(resolve("scroll", ToolPolicyClass.SCREEN_SCROLL, arguments))

            assertEquals(listOf(BANK), subject.targets.map { it.packageName })
            assertEquals(PolicyActionType.NAVIGATE, subject.actionType)
            assertFalse(subject.classifyTarget)
        }

    private fun pinchArguments(
        centerX: Int,
        centerY: Int,
    ): JsonObject =
        buildJsonObject {
            put("center_x", centerX)
            put("center_y", centerY)
            put("scale", 2.0)
        }

    @Test
    fun `pinch hit-tests the centre and both finger start points and is classified`() =
        runTest {
            val buttons =
                listOf(440, 540, 640).map { x ->
                    val bounds = BoundsData(x - 20, 1150, x + 20, 1250)
                    button("b$x", bounds, "Zoom", resourceId = "com.example.bank:id/b$x")
                }
            windowSource.windows = read(app(BANK, buttons))

            val subject = subjectOf(resolve("pinch", ToolPolicyClass.PINCH, pinchArguments(540, 1200)))

            assertEquals(3, subject.targets.size)
            assertTrue(subject.targets.all { it.packageName == BANK })
            assertTrue(subject.classifyTarget)
        }

    @Test
    fun `pinch finger landing in another app's window adds that app's target`() =
        runTest {
            windowSource.windows =
                read(app(BANK), app(SHOP, bounds = BoundsData(600, 1000, 1080, 1400), layer = 2, focused = false))

            val subject = subjectOf(resolve("pinch", ToolPolicyClass.PINCH, pinchArguments(540, 1200)))

            assertEquals(setOf(BANK, SHOP), subject.targets.map { it.packageName }.toSet())
        }

    @Test
    fun `pinch finger start points are clamped to the screen`() =
        runTest {
            windowSource.windows =
                read(app(BANK), app(SHOP, bounds = BoundsData(0, 0, 20, 2400), layer = 2, focused = false))

            val subject = subjectOf(resolve("pinch", ToolPolicyClass.PINCH, pinchArguments(30, 1200)))

            assertTrue(SHOP in subject.targets.map { it.packageName })
        }

    @Test
    fun `pinch finger on the keyboard while a profiled app is focused is Blocked`() =
        runTest {
            windowSource.windows = read(app(BANK), keyboard(BoundsData(600, 1000, 1080, 2400)))

            val resolution = resolve("pinch", ToolPolicyClass.PINCH, pinchArguments(540, 1200), setOf(BANK))

            assertEquals(
                SubjectResolution.Blocked(BANK, PolicyActionType.ACTION_BUTTON, BlockCause.IME_INPUT_IN_PROFILED_APP),
                resolution,
            )
        }

    @Test
    fun `pinch without center_x throws InvalidParams before reading windows`() =
        runTest {
            val arguments = buildJsonObject { put("center_y", 1200) }

            assertThrows<McpToolException.InvalidParams> { resolve("pinch", ToolPolicyClass.PINCH, arguments) }
            assertEquals(0, windowSource.readCount)
        }

    @Test
    fun `custom_gesture hit-tests the first point of every path and is classified`() =
        runTest {
            windowSource.windows =
                read(
                    app(BANK, bounds = BoundsData(0, 0, 1080, 1200)),
                    app(SHOP, bounds = BoundsData(0, 1200, 1080, 2400), layer = 2, focused = false),
                )

            val subject =
                subjectOf(
                    resolve(
                        "custom_gesture",
                        ToolPolicyClass.CUSTOM_GESTURE,
                        gesture(listOf(pathOf(100, 100), pathOf(100, 2000))),
                    ),
                )

            assertEquals(setOf(BANK, SHOP), subject.targets.map { it.packageName }.toSet())
            assertEquals(2, subject.targets.size)
            assertTrue(subject.classifyTarget)
        }

    private suspend fun assertGestureError(
        arguments: JsonObject,
        message: String,
    ) {
        val error =
            assertThrows<McpToolException.InvalidParams> {
                resolve("custom_gesture", ToolPolicyClass.CUSTOM_GESTURE, arguments)
            }
        assertEquals(message, error.message)
        assertEquals(0, windowSource.readCount)
    }

    @Test
    fun `custom_gesture without paths throws InvalidParams`() =
        runTest {
            assertGestureError(buildJsonObject { }, "Missing required parameter: 'paths'")
        }

    @Test
    fun `custom_gesture with empty paths throws InvalidParams`() =
        runTest {
            assertGestureError(gesture(emptyList()), "Parameter 'paths' must not be empty")
        }

    @Test
    fun `custom_gesture with 11 paths throws InvalidParams`() =
        runTest {
            assertGestureError(gesture(List(11) { pathOf(100, 100) }), "Too many paths (max 10), got: 11")
        }

    @Test
    fun `custom_gesture with a non-array path throws InvalidParams`() =
        runTest {
            assertGestureError(gesture(listOf(point(1, 1))), "Path at index 0 must be an array of point objects")
        }

    @Test
    fun `custom_gesture with a one-point path throws InvalidParams`() =
        runTest {
            assertGestureError(
                gesture(listOf(pathOf(1, 1, points = 1))),
                "Path at index 0 must have at least 2 points, has 1",
            )
        }

    @Test
    fun `tap without x throws InvalidParams before reading windows`() =
        runTest {
            val arguments = buildJsonObject { put("y", 10) }

            assertThrows<McpToolException.InvalidParams> { resolve("tap", ToolPolicyClass.POINT_GESTURE, arguments) }
            assertEquals(0, windowSource.readCount)
        }

    @Test
    fun `tap on the keyboard while a profiled app is focused is Blocked`() =
        runTest {
            windowSource.windows = read(app(BANK), keyboard())

            assertEquals(
                SubjectResolution.Blocked(BANK, PolicyActionType.ACTION_BUTTON, BlockCause.IME_INPUT_IN_PROFILED_APP),
                tap(500, 2000, setOf(BANK)),
            )
        }

    @Test
    fun `tap on the keyboard while this app is focused is Blocked with zero profiles`() =
        runTest {
            windowSource.windows = read(app(OWN_PACKAGE), keyboard())

            assertEquals(
                SubjectResolution.Blocked(
                    OWN_PACKAGE,
                    PolicyActionType.ACTION_BUTTON,
                    BlockCause.IME_INPUT_IN_PROFILED_APP,
                ),
                tap(500, 2000),
            )
        }

    @Test
    fun `tap on the keyboard while an unprofiled app is focused targets the keyboard`() =
        runTest {
            windowSource.windows = read(app(SHOP, bounds = BoundsData(0, 0, 1080, 1600)), keyboard())

            val subject = subjectOf(tap(500, 2000, setOf(BANK)))

            assertEquals(listOf(KEYBOARD), subject.targets.map { it.packageName })
        }

    @Test
    fun `tap on the keyboard with no application window is Blocked when profiles exist`() =
        runTest {
            windowSource.windows = read(keyboard())

            assertEquals(
                SubjectResolution.Blocked(null, PolicyActionType.ACTION_BUTTON, BlockCause.IME_INPUT_IN_PROFILED_APP),
                tap(500, 2000, setOf(BANK)),
            )
        }

    @Test
    fun `tap outside every window`() =
        runTest {
            windowSource.windows = read(app(BANK, bounds = BoundsData(0, 0, 100, 100)))

            assertEquals(SubjectResolution.Unattributed, tap(500, 500))
            assertEquals(
                SubjectResolution.Blocked(null, PolicyActionType.ACTION_BUTTON, BlockCause.TARGET_UNRESOLVED),
                tap(500, 500, setOf(BANK)),
            )
        }

    @Test
    fun `degraded read`() =
        runTest {
            windowSource.windows = MultiWindowResult(listOf(app(BANK)), degraded = true)

            assertEquals(SubjectResolution.Unattributed, tap(100, 100))
            assertEquals(
                SubjectResolution.Blocked(null, PolicyActionType.ACTION_BUTTON, BlockCause.TARGET_UNRESOLVED),
                tap(100, 100, setOf(BANK)),
            )
        }

    @Test
    fun `hit window without package is Blocked with profiles`() =
        runTest {
            windowSource.windows = read(app(null))

            assertEquals(
                SubjectResolution.Blocked(null, PolicyActionType.ACTION_BUTTON, BlockCause.TARGET_UNRESOLVED),
                tap(100, 100, setOf(BANK)),
            )
        }

    private companion object {
        val TOP_HALF = BoundsData(0, 0, 1080, 1000)
        val ICON_BOUNDS = BoundsData(0, 0, 500, 500)
    }
}
