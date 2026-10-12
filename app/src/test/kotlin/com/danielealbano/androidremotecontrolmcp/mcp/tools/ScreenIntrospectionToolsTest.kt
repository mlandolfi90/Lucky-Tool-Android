package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.danielealbano.androidremotecontrolmcp.data.model.ScreenshotData
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeCache
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityServiceProvider
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityTreeParser
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactFormatOptions
import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
import com.danielealbano.androidremotecontrolmcp.services.accessibility.PaginationTestTrees
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ScreenStateSnapshotCacheImpl
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WebViewNodeMerger
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenCaptureProvider
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotAnnotator
import com.danielealbano.androidremotecontrolmcp.services.screencapture.ScreenshotEncoder
import com.danielealbano.androidremotecontrolmcp.testutil.PrivacyToolTestDoubles
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("Screen Introspection Tools")
class ScreenIntrospectionToolsTest {
    private lateinit var mockAccessibilityServiceProvider: AccessibilityServiceProvider
    private lateinit var mockScreenCaptureProvider: ScreenCaptureProvider
    private lateinit var mockTreeParser: AccessibilityTreeParser
    private lateinit var mockCompactTreeFormatter: CompactTreeFormatter
    private lateinit var mockScreenshotAnnotator: ScreenshotAnnotator
    private lateinit var mockScreenshotEncoder: ScreenshotEncoder
    private lateinit var mockNodeCache: AccessibilityNodeCache
    private lateinit var mockRootNode: AccessibilityNodeInfo
    private lateinit var mockWindowInfo: AccessibilityWindowInfo

    @BeforeEach
    fun setUp() {
        mockAccessibilityServiceProvider = mockk<AccessibilityServiceProvider>(relaxed = true)
        mockScreenCaptureProvider = mockk<ScreenCaptureProvider>()
        mockTreeParser = mockk<AccessibilityTreeParser>()
        mockCompactTreeFormatter = mockk<CompactTreeFormatter>()
        mockScreenshotAnnotator = mockk(relaxed = true)
        mockScreenshotEncoder = mockk(relaxed = true)
        mockNodeCache = mockk<AccessibilityNodeCache>(relaxed = true)
        mockRootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
        mockWindowInfo = mockk<AccessibilityWindowInfo>(relaxed = true)
        // countKeptNodes (an extension fn) walks the tree via shouldKeepNode; stub it on the strict
        // formatter mock so every existing test's small tree counts as <=200 nodes and stays on the
        // single-page (formatMultiWindow) path. Pagination tests below use a real CompactTreeFormatter.
        every { mockCompactTreeFormatter.shouldKeepNode(any()) } returns true
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private val sampleTree =
        AccessibilityNodeData(
            id = "node_root",
            className = "android.widget.FrameLayout",
            bounds = BoundsData(0, 0, 1080, 2400),
            visible = true,
            enabled = true,
            children =
                listOf(
                    AccessibilityNodeData(
                        id = "node_btn",
                        className = "android.widget.Button",
                        text = "OK",
                        bounds = BoundsData(100, 200, 300, 260),
                        clickable = true,
                        visible = true,
                        enabled = true,
                    ),
                ),
        )

    private val sampleScreenInfo =
        ScreenInfo(
            width = 1080,
            height = 2400,
            densityDpi = 420,
            orientation = "portrait",
        )

    private val sampleCompactOutput =
        "note:structural-only nodes are omitted from the tree\n" +
            "note:certain elements are custom and will not be properly reported, " +
            "if needed or if tools are not working as expected set " +
            "include_screenshot=true to see the screen and take what you see into account\n" +
            "note:flags: on=onscreen off=offscreen clk=clickable lclk=longClickable " +
            "foc=focusable scr=scrollable edt=editable ena=enabled\n" +
            "note:offscreen items require scroll_to_node before interaction\n" +
            "screen:1080x2400 density:420 orientation:portrait\n" +
            "--- window:0 type:APPLICATION pkg:com.example title:Test activity:.Main " +
            "layer:0 focused:true ---\n" +
            "node_id\tclass\ttext\tdesc\tres_id\tbounds\tflags\n" +
            "node_btn\tButton\tOK\t-\t-\t100,200,300,260\ton,clk,ena\n" +
            "hierarchy:\nnode_btn"

    @Suppress("LongMethod")
    private fun setupReadyService() {
        every { mockAccessibilityServiceProvider.isReady() } returns true
        every { mockWindowInfo.id } returns 0
        every { mockWindowInfo.root } returns mockRootNode
        every { mockWindowInfo.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
        every { mockWindowInfo.title } returns "Test"
        every { mockWindowInfo.layer } returns 0
        every { mockWindowInfo.isFocused } returns true
        every { mockRootNode.refresh() } returns true
        every { mockRootNode.packageName } returns "com.example"
        every {
            mockAccessibilityServiceProvider.getAccessibilityWindows()
        } returns listOf(mockWindowInfo)
        every { mockAccessibilityServiceProvider.getCurrentPackageName() } returns "com.example"
        every { mockAccessibilityServiceProvider.getCurrentActivityName() } returns ".Main"
        every { mockAccessibilityServiceProvider.getScreenInfo() } returns sampleScreenInfo
        every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } returns sampleTree
        every {
            mockCompactTreeFormatter.formatMultiWindow(any(), eq(sampleScreenInfo), any())
        } returns sampleCompactOutput
        every { mockCompactTreeFormatter.shouldKeepNode(any()) } returns true
    }

    // ─────────────────────────────────────────────────────────────────────
    // get_screen_state
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("GetScreenStateHandler")
    inner class GetScreenStateTests {
        private lateinit var handler: GetScreenStateHandler

        @BeforeEach
        fun setUp() {
            handler =
                GetScreenStateHandler(
                    mockTreeParser,
                    mockAccessibilityServiceProvider,
                    mockScreenCaptureProvider,
                    mockCompactTreeFormatter,
                    mockScreenshotAnnotator,
                    mockScreenshotEncoder,
                    mockNodeCache,
                    ScreenStateSnapshotCacheImpl(),
                    WebViewNodeMerger(),
                    PrivacyToolTestDoubles.passthroughGate(),
                    PrivacyToolTestDoubles.screenshotRedactor(),
                )
        }

        @Test
        @DisplayName("returns compact tree when service is ready")
        fun returnsCompactTreeWhenServiceIsReady() =
            runTest {
                setupReadyService()

                val result = handler.execute(null)

                assertEquals(1, result.content.size)
                val textContent = result.content[0] as TextContent
                assertEquals(sampleCompactOutput, stripUntrustedWarning(textContent.text))
            }

        @Test
        @DisplayName("clears the framework node cache before reading windows on a fresh request")
        fun clearsFrameworkNodeCacheBeforeFreshRead() =
            runTest {
                setupReadyService()

                handler.execute(null)

                // The cache clear MUST precede the tree read, otherwise the read still serves stale
                // WebView nodes from the framework cache (the whole point of the fix).
                verifyOrder {
                    mockAccessibilityServiceProvider.clearFrameworkNodeCache()
                    mockAccessibilityServiceProvider.getAccessibilityWindows()
                }
            }

        @Test
        @DisplayName("does not clear the framework node cache on a paged (cursor) request")
        fun doesNotClearFrameworkNodeCacheOnPagedRequest() =
            runTest {
                setupReadyService()

                // A cursor request is served from the snapshot cache and performs no fresh tree
                // read, so there is nothing to un-stale — clearing the framework cache would be
                // pointless work.
                handler.execute(buildJsonObject { put("cursor", "missing.2") })

                verify(exactly = 0) { mockAccessibilityServiceProvider.clearFrameworkNodeCache() }
            }

        @Test
        @DisplayName("includes screenshot when include_screenshot is true")
        fun includesScreenshotWhenIncludeScreenshotIsTrue() =
            runTest {
                setupReadyService()
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns true
                val mockBitmap = mockk<Bitmap>(relaxed = true)
                val mockAnnotatedBitmap = mockk<Bitmap>(relaxed = true)
                coEvery {
                    mockScreenCaptureProvider.captureScreenshotBitmap(
                        GetScreenStateHandler.SCREENSHOT_MAX_SIZE,
                        GetScreenStateHandler.SCREENSHOT_MAX_SIZE,
                    )
                } returns Result.success(mockBitmap)
                every {
                    mockScreenshotAnnotator.annotate(any(), any(), any(), any())
                } returns mockAnnotatedBitmap
                every {
                    mockScreenshotEncoder.bitmapToScreenshotData(any(), any())
                } returns ScreenshotData(data = "base64data", width = 700, height = 500)

                val params = buildJsonObject { put("include_screenshot", true) }
                val result = handler.execute(params)

                assertEquals(2, result.content.size)
                val textContent = result.content[0] as TextContent
                assertEquals(sampleCompactOutput, stripUntrustedWarning(textContent.text))
                val imageContent = result.content[1] as ImageContent
                assertEquals("base64data", imageContent.data)
                assertEquals("image/jpeg", imageContent.mimeType)
            }

        @Test
        @DisplayName("screenshot uses 700px max size")
        fun screenshotUses700pxMaxSize() =
            runTest {
                setupReadyService()
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns true
                val mockBitmap = mockk<Bitmap>(relaxed = true)
                val mockAnnotatedBitmap = mockk<Bitmap>(relaxed = true)
                coEvery {
                    mockScreenCaptureProvider.captureScreenshotBitmap(any(), any())
                } returns Result.success(mockBitmap)
                every {
                    mockScreenshotAnnotator.annotate(any(), any(), any(), any())
                } returns mockAnnotatedBitmap
                every {
                    mockScreenshotEncoder.bitmapToScreenshotData(any(), any())
                } returns ScreenshotData(data = "base64data", width = 700, height = 500)

                val params = buildJsonObject { put("include_screenshot", true) }
                handler.execute(params)

                coVerify(exactly = 1) {
                    mockScreenCaptureProvider.captureScreenshotBitmap(
                        GetScreenStateHandler.SCREENSHOT_MAX_SIZE,
                        GetScreenStateHandler.SCREENSHOT_MAX_SIZE,
                    )
                }
            }

        @Test
        @DisplayName("does not include screenshot by default")
        fun doesNotIncludeScreenshotByDefault() =
            runTest {
                setupReadyService()

                val result = handler.execute(null)

                assertEquals(1, result.content.size)
                assertTrue(result.content[0] is TextContent)
                coVerify(exactly = 0) {
                    mockScreenCaptureProvider.captureScreenshotBitmap(any(), any())
                }
            }

        @Test
        @DisplayName("does not include screenshot when false")
        fun doesNotIncludeScreenshotWhenFalse() =
            runTest {
                setupReadyService()

                val params = buildJsonObject { put("include_screenshot", false) }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                assertTrue(result.content[0] is TextContent)
                coVerify(exactly = 0) {
                    mockScreenCaptureProvider.captureScreenshotBitmap(any(), any())
                }
            }

        @Test
        @DisplayName("throws PermissionDenied when accessibility service not ready")
        fun throwsPermissionDeniedWhenAccessibilityServiceNotReady() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns false

                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        handler.execute(null)
                    }
                assertTrue(exception.message!!.contains("Accessibility service not enabled"))
            }

        @Test
        @DisplayName("throws ActionFailed when no windows and no root node available")
        fun throwsActionFailedWhenNoWindowsAndNoRootNode() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns true
                every {
                    mockAccessibilityServiceProvider.getAccessibilityWindows()
                } returns emptyList()
                every { mockAccessibilityServiceProvider.getRootNode() } returns null

                assertThrows<McpToolException.ActionFailed> {
                    handler.execute(null)
                }
            }

        @Test
        @DisplayName("throws PermissionDenied when screenshot requested but capture not available")
        fun throwsPermissionDeniedWhenScreenshotRequestedButCaptureNotAvailable() =
            runTest {
                setupReadyService()
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns false

                val params = buildJsonObject { put("include_screenshot", true) }
                val exception =
                    assertThrows<McpToolException.PermissionDenied> {
                        handler.execute(params)
                    }
                assertTrue(exception.message!!.contains("Screen capture not available"))
            }

        @Test
        @DisplayName("throws ActionFailed when screenshot capture fails")
        fun throwsActionFailedWhenScreenshotCaptureFails() =
            runTest {
                setupReadyService()
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns true
                coEvery {
                    mockScreenCaptureProvider.captureScreenshotBitmap(any(), any())
                } returns Result.failure(RuntimeException("Screenshot capture failed"))

                val params = buildJsonObject { put("include_screenshot", true) }
                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        handler.execute(params)
                    }
                assertTrue(exception.message!!.contains("Screenshot capture failed"))
            }

        @Test
        @DisplayName("populates cache and recycles window info but not root node after parsing")
        fun populatesCacheAndRecyclesWindowInfoButNotRootNodeAfterParsing() =
            runTest {
                setupReadyService()

                handler.execute(null)

                @Suppress("DEPRECATION")
                verify(exactly = 0) { mockRootNode.recycle() }
                verify(exactly = 1) { mockNodeCache.populate(any()) }
                @Suppress("DEPRECATION")
                verify(exactly = 1) { mockWindowInfo.recycle() }
            }

        @Test
        @DisplayName("getFreshWindows fallback path returns degraded output")
        fun getFreshWindowsFallbackPathReturnsDegradedOutput() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns true
                every {
                    mockAccessibilityServiceProvider.getAccessibilityWindows()
                } returns emptyList()
                val fallbackRootNode = mockk<AccessibilityNodeInfo>(relaxed = true)
                every { mockAccessibilityServiceProvider.getRootNode() } returns fallbackRootNode
                every { fallbackRootNode.windowId } returns 42
                every { fallbackRootNode.packageName } returns "com.fallback"
                every { mockTreeParser.parseTree(fallbackRootNode, "root_w42", any()) } returns sampleTree
                every { mockAccessibilityServiceProvider.getScreenInfo() } returns sampleScreenInfo
                val degradedOutput = "degraded output"
                every {
                    mockCompactTreeFormatter.formatMultiWindow(
                        match { it.degraded },
                        eq(sampleScreenInfo),
                        any(),
                    )
                } returns degradedOutput

                val result = handler.execute(null)

                assertEquals(1, result.content.size)
                val textContent = result.content[0] as TextContent
                assertEquals(degradedOutput, stripUntrustedWarning(textContent.text))

                @Suppress("DEPRECATION")
                verify(exactly = 0) { fallbackRootNode.recycle() }
                verify(exactly = 1) { mockNodeCache.populate(any()) }
            }

        @Test
        @DisplayName("getFreshWindows throws ActionFailed when all windows have null roots")
        fun throwsActionFailedWhenAllWindowsHaveNullRoots() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns true
                val mockNullRootWindow = mockk<AccessibilityWindowInfo>(relaxed = true)
                every { mockNullRootWindow.root } returns null
                every {
                    mockAccessibilityServiceProvider.getAccessibilityWindows()
                } returns listOf(mockNullRootWindow)

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        handler.execute(null)
                    }
                assertTrue(exception.message!!.contains("All windows returned null root nodes"))

                @Suppress("DEPRECATION")
                verify(exactly = 1) { mockNullRootWindow.recycle() }
            }

        @Test
        @DisplayName("getFreshWindows throws ActionFailed when no windows and no root node")
        fun throwsActionFailedWhenNoWindowsAndNoRoot() =
            runTest {
                every { mockAccessibilityServiceProvider.isReady() } returns true
                every {
                    mockAccessibilityServiceProvider.getAccessibilityWindows()
                } returns emptyList()
                every { mockAccessibilityServiceProvider.getRootNode() } returns null

                val exception =
                    assertThrows<McpToolException.ActionFailed> {
                        handler.execute(null)
                    }
                assertTrue(exception.message!!.contains("No windows available"))
            }

        @Test
        @DisplayName("passes default options when params absent")
        fun passesDefaultOptionsWhenParamsAbsent() =
            runTest {
                setupReadyService()

                handler.execute(null)

                verify {
                    mockCompactTreeFormatter.formatMultiWindow(
                        any(),
                        eq(sampleScreenInfo),
                        eq(CompactFormatOptions()),
                    )
                }
            }

        @Test
        @DisplayName("passes parsed options to formatter")
        fun passesParsedOptionsToFormatter() =
            runTest {
                setupReadyService()

                handler.execute(
                    buildJsonObject {
                        put("include_offscreen", false)
                        put("include_hierarchy", false)
                    },
                )

                verify {
                    mockCompactTreeFormatter.formatMultiWindow(
                        any(),
                        eq(sampleScreenInfo),
                        eq(CompactFormatOptions(includeOffscreen = false, includeHierarchy = false)),
                    )
                }
            }

        @Test
        @DisplayName("rejects string include_offscreen")
        fun rejectsStringIncludeOffscreen() =
            runTest {
                setupReadyService()

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject { put("include_offscreen", "false") })
                }
            }

        @Test
        @DisplayName("rejects non-boolean include_hierarchy")
        fun rejectsNonBooleanIncludeHierarchy() =
            runTest {
                setupReadyService()

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject { put("include_hierarchy", 1) })
                }
            }

        @Test
        @DisplayName("rejects null include_offscreen")
        fun rejectsNullIncludeOffscreen() =
            runTest {
                setupReadyService()

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject { put("include_offscreen", JsonNull) })
                }
            }

        @Test
        @DisplayName("rejects object include_hierarchy")
        fun rejectsObjectIncludeHierarchy() =
            runTest {
                setupReadyService()

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(buildJsonObject { putJsonObject("include_hierarchy") {} })
                }
            }

        @Test
        @DisplayName("rejects include_offscreen on cursor call when not boolean")
        fun rejectsIncludeOffscreenOnCursorCall() =
            runTest {
                setupReadyService()

                assertThrows<McpToolException.InvalidParams> {
                    handler.execute(
                        buildJsonObject {
                            put("cursor", "x.2")
                            put("include_offscreen", "no")
                        },
                    )
                }
            }
    }

    @Nested
    @DisplayName("get_screen_state pagination")
    inner class PaginationTests {
        private val realFormatter = CompactTreeFormatter()
        private lateinit var realCache: ScreenStateSnapshotCacheImpl
        private lateinit var handler: GetScreenStateHandler

        // Held so the privacy rows can verify what the gate received (it must stay the full tree).
        private lateinit var gate: PrivacyToolGate

        @BeforeEach
        fun setUp() {
            realCache = ScreenStateSnapshotCacheImpl()
            gate = PrivacyToolTestDoubles.passthroughGate()
            handler =
                GetScreenStateHandler(
                    mockTreeParser,
                    mockAccessibilityServiceProvider,
                    mockScreenCaptureProvider,
                    realFormatter,
                    mockScreenshotAnnotator,
                    mockScreenshotEncoder,
                    mockNodeCache,
                    realCache,
                    WebViewNodeMerger(),
                    gate,
                    PrivacyToolTestDoubles.screenshotRedactor(),
                )
        }

        private fun offscreenParentTree(): AccessibilityNodeData =
            AccessibilityNodeData(
                id = "node_root",
                className = "android.widget.FrameLayout",
                resourceId = "com.example.app:id/root",
                bounds = BoundsData(0, 0, 1080, 2400),
                enabled = true,
                visible = true,
                children =
                    listOf(
                        AccessibilityNodeData(
                            id = "node_off_parent",
                            className = "android.widget.FrameLayout",
                            resourceId = "com.example.app:id/off_parent",
                            bounds = BoundsData(0, 0, 100, 100),
                            enabled = true,
                            visible = false,
                            children =
                                listOf(
                                    AccessibilityNodeData(
                                        id = "node_on_child",
                                        className = "android.widget.TextView",
                                        text = "Child",
                                        bounds = BoundsData(0, 0, 100, 50),
                                        enabled = true,
                                        visible = true,
                                    ),
                                ),
                        ),
                    ),
            )

        // 252 kept (anc 2 + 250 leaves) -> 2 pages; 12 kept -> single page.
        private fun largeTree() = PaginationTestTrees.keptNodeWindow(250).tree

        private fun smallTree() = PaginationTestTrees.keptNodeWindow(10).tree

        private fun stubCaptureWithTree(tree: AccessibilityNodeData) {
            every { mockAccessibilityServiceProvider.isReady() } returns true
            every { mockWindowInfo.id } returns 0
            every { mockWindowInfo.root } returns mockRootNode
            every { mockWindowInfo.type } returns AccessibilityWindowInfo.TYPE_APPLICATION
            every { mockWindowInfo.title } returns "Test"
            every { mockWindowInfo.layer } returns 0
            every { mockWindowInfo.isFocused } returns true
            every { mockRootNode.refresh() } returns true
            every { mockRootNode.packageName } returns "com.example"
            every {
                mockAccessibilityServiceProvider.getAccessibilityWindows()
            } returns listOf(mockWindowInfo)
            every { mockAccessibilityServiceProvider.getCurrentPackageName() } returns "com.example"
            every { mockAccessibilityServiceProvider.getCurrentActivityName() } returns ".Main"
            every { mockAccessibilityServiceProvider.getScreenInfo() } returns sampleScreenInfo
            every { mockTreeParser.parseTree(mockRootNode, "root_w0", any()) } returns tree
        }

        private suspend fun freshText(): String = (handler.execute(null).content[0] as TextContent).text

        private suspend fun pagedText(cursor: String): String {
            val params = buildJsonObject { put("cursor", cursor) }
            return (handler.execute(params).content[0] as TextContent).text
        }

        private fun snapshotId(text: String): String = Regex("snapshot:(\\S+)").find(text)!!.groupValues[1]

        @Test
        @DisplayName("cursorless small screen returns single page no cursor and clears cache")
        fun cursorlessSmallScreenReturnsSinglePageNoCursorAndClearsCache() =
            runTest {
                stubCaptureWithTree(largeTree())
                val id = snapshotId(freshText())
                assertNotNull(realCache.get(id))

                stubCaptureWithTree(smallTree())
                val text = freshText()
                assertFalse(text.contains("page:"))
                assertNull(realCache.get(id))
            }

        @Test
        @DisplayName("cursorless large screen returns page 1 and stores snapshot")
        fun cursorlessLargeScreenReturnsPage1AndStoresSnapshot() =
            runTest {
                stubCaptureWithTree(largeTree())
                val text = freshText()
                assertTrue(text.contains("page:1/2"))
                assertNotNull(realCache.get(snapshotId(text)))
            }

        @Test
        @DisplayName("cursor returns requested page without re-capturing")
        fun cursorReturnsRequestedPageWithoutRecapturing() =
            runTest {
                stubCaptureWithTree(largeTree())
                val id = snapshotId(freshText())

                val text = pagedText("$id.2")
                assertTrue(text.contains("page:2/2"))
                // cursor path must NOT re-capture: getAccessibilityWindows called only by the fresh call
                verify(exactly = 1) { mockAccessibilityServiceProvider.getAccessibilityWindows() }
                // ...and it must NOT clear the framework cache: serving a stored page performs no
                // fresh read, so the only clear is the one from the initial fresh capture.
                verify(exactly = 1) { mockAccessibilityServiceProvider.clearFrameworkNodeCache() }
            }

        @Test
        @DisplayName("malformed cursor returns invalid-cursor guidance")
        fun malformedCursorReturnsInvalidCursorGuidance() =
            runTest {
                val text = pagedText("garbage")
                assertTrue(text.contains("invalid cursor"))
            }

        @Test
        @DisplayName("non-primitive cursor returns invalid-cursor guidance")
        fun nonPrimitiveCursorReturnsInvalidCursorGuidance() =
            runTest {
                val params = buildJsonObject { putJsonObject("cursor") { put("nested", "x") } }
                val result = handler.execute(params)
                assertEquals(1, result.content.size)
                assertTrue((result.content[0] as TextContent).text.contains("invalid cursor"))
            }

        @Test
        @DisplayName("stale snapshot id returns snapshot-gone guidance")
        fun staleSnapshotIdReturnsSnapshotGoneGuidance() =
            runTest {
                assertTrue(pagedText("nonexistent.1").contains("no longer available"))
            }

        @Test
        @DisplayName("out of range page returns no-such-page guidance")
        fun outOfRangePageReturnsNoSuchPageGuidance() =
            runTest {
                stubCaptureWithTree(largeTree())
                val id = snapshotId(freshText())

                val tooHigh = pagedText("$id.999")
                assertTrue(tooHigh.contains("does not exist"))
                assertTrue(tooHigh.contains("2 page(s)"))
                assertTrue(pagedText("$id.0").contains("does not exist"))
            }

        @Test
        @DisplayName("screenshot honored on cursorless page 1")
        fun screenshotHonoredOnCursorlessPage1() =
            runTest {
                stubCaptureWithTree(largeTree())
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns true
                val mockBitmap = mockk<Bitmap>(relaxed = true)
                coEvery {
                    mockScreenCaptureProvider.captureScreenshotBitmap(any(), any())
                } returns Result.success(mockBitmap)
                every {
                    mockScreenshotAnnotator.annotate(any(), any(), any(), any())
                } returns mockBitmap
                every {
                    mockScreenshotEncoder.bitmapToScreenshotData(any(), any())
                } returns ScreenshotData(data = "imgdata", width = 700, height = 500)

                val params = buildJsonObject { put("include_screenshot", true) }
                val result = handler.execute(params)

                assertEquals(2, result.content.size)
                assertTrue(result.content[1] is ImageContent)
                assertTrue((result.content[0] as TextContent).text.contains("page:1/2"))
            }

        @Test
        @DisplayName("screenshot ignored with cursor and note appended")
        fun screenshotIgnoredWithCursorAndNoteAppended() =
            runTest {
                stubCaptureWithTree(largeTree())
                val id = snapshotId(freshText())

                val params =
                    buildJsonObject {
                        put("cursor", "$id.2")
                        put("include_screenshot", true)
                    }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("page:2/2"))
                assertTrue(text.contains("screenshot can only be requested on page 1"))
            }

        @Test
        @DisplayName("screenshot note appended on cursor guidance paths")
        fun screenshotNoteAppendedOnCursorGuidancePaths() =
            runTest {
                val params =
                    buildJsonObject {
                        put("cursor", "garbage")
                        put("include_screenshot", true)
                    }
                val result = handler.execute(params)

                assertEquals(1, result.content.size)
                val text = (result.content[0] as TextContent).text
                assertTrue(text.contains("invalid cursor"))
                assertTrue(text.contains("screenshot can only be requested on page 1"))
            }

        @Test
        @DisplayName("include_offscreen false shrinks page count")
        fun includeOffscreenFalseShrinksPageCount() =
            runTest {
                stubCaptureWithTree(PaginationTestTrees.mixedVisibilityWindow(250, 2).tree)
                val firstId = snapshotId(freshText())

                val params = buildJsonObject { put("include_offscreen", false) }
                val text = (handler.execute(params).content[0] as TextContent).text
                assertFalse(text.contains("page:"))
                assertNull(realCache.get(firstId))
            }

        @Test
        @DisplayName("cursor page reuses snapshot options")
        fun cursorPageReusesSnapshotOptions() =
            runTest {
                stubCaptureWithTree(largeTree())
                val params = buildJsonObject { put("include_hierarchy", false) }
                val fresh = (handler.execute(params).content[0] as TextContent).text
                assertTrue(fresh.contains("page:1/2"))
                val id = snapshotId(fresh)

                val cursorParams =
                    buildJsonObject {
                        put("cursor", "$id.2")
                        put("include_hierarchy", true)
                    }
                val page2 = (handler.execute(cursorParams).content[0] as TextContent).text
                assertFalse(page2.lines().contains(CompactTreeFormatter.HIERARCHY_HEADER))
            }

        @Test
        @DisplayName("snapshot stores options")
        fun snapshotStoresOptions() =
            runTest {
                stubCaptureWithTree(largeTree())
                val params = buildJsonObject { put("include_hierarchy", false) }
                val id = snapshotId((handler.execute(params).content[0] as TextContent).text)

                assertEquals(
                    CompactFormatOptions(includeOffscreen = true, includeHierarchy = false),
                    realCache.get(id)!!.options,
                )
            }

        @Test
        @DisplayName("include_offscreen false still redacts the full tree")
        fun includeOffscreenFalseStillRedactsFullTree() =
            runTest {
                stubCaptureWithTree(offscreenParentTree())

                val params = buildJsonObject { put("include_offscreen", false) }
                val text = (handler.execute(params).content[0] as TextContent).text

                coVerify(exactly = 1) {
                    gate.tree(
                        match { r ->
                            r.windows
                                .single()
                                .tree.children
                                .any { it.id == "node_off_parent" }
                        },
                    )
                }
                assertFalse(text.contains("node_off_parent"))
                assertTrue(text.contains("node_on_child"))
            }

        @Test
        @DisplayName("include_offscreen false keeps screenshot annotation unchanged")
        fun includeOffscreenFalseKeepsScreenshotAnnotationUnchanged() =
            runTest {
                stubCaptureWithTree(offscreenParentTree())
                every { mockScreenCaptureProvider.isScreenCaptureAvailable() } returns true
                val mockBitmap = mockk<Bitmap>(relaxed = true)
                coEvery {
                    mockScreenCaptureProvider.captureScreenshotBitmap(any(), any())
                } returns Result.success(mockBitmap)
                val annotated = mutableListOf<List<AccessibilityNodeData>>()
                every {
                    mockScreenshotAnnotator.annotate(any(), capture(annotated), any(), any())
                } returns mockBitmap
                every {
                    mockScreenshotEncoder.bitmapToScreenshotData(any(), any())
                } returns ScreenshotData(data = "imgdata", width = 700, height = 500)

                handler.execute(buildJsonObject { put("include_screenshot", true) })
                handler.execute(
                    buildJsonObject {
                        put("include_screenshot", true)
                        put("include_offscreen", false)
                    },
                )

                assertEquals(2, annotated.size)
                assertEquals(annotated[0], annotated[1])
                assertTrue(annotated[0].any { it.id == "node_on_child" })
            }

        @Test
        @DisplayName("include_offscreen false with all nodes off-screen returns an empty page and clears the cache")
        fun includeOffscreenFalseWithAllNodesOffScreenReturnsEmptyPage() =
            runTest {
                stubCaptureWithTree(largeTree())
                val firstId = snapshotId(freshText())

                stubCaptureWithTree(PaginationTestTrees.allOffscreen(largeTree()))
                val result = handler.execute(buildJsonObject { put("include_offscreen", false) })
                assertNotEquals(true, result.isError)
                val text = (result.content[0] as TextContent).text
                assertFalse(text.contains("page:"))
                assertTrue(
                    text.lines().none { it.startsWith("node_") && it != CompactTreeFormatter.HEADER },
                )
                assertNull(realCache.get(firstId))
            }

        @Test
        @DisplayName("include_offscreen false still populates the node cache")
        fun includeOffscreenFalseStillPopulatesNodeCache() =
            runTest {
                stubCaptureWithTree(offscreenParentTree())

                handler.execute(buildJsonObject { put("include_offscreen", false) })

                verify(exactly = 1) { mockNodeCache.populate(any()) }
            }
    }
}
