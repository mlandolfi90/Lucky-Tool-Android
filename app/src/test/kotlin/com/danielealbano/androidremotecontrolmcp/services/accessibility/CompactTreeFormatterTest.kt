package com.danielealbano.androidremotecontrolmcp.services.accessibility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CompactTreeFormatter")
class CompactTreeFormatterTest {
    private val formatter = CompactTreeFormatter()

    private val defaultScreenInfo =
        ScreenInfo(
            width = 1080,
            height = 2400,
            densityDpi = 420,
            orientation = "portrait",
        )

    @Suppress("LongParameterList")
    private fun makeNode(
        id: String = "node_test",
        className: String? = null,
        text: String? = null,
        contentDescription: String? = null,
        resourceId: String? = null,
        bounds: BoundsData = BoundsData(0, 0, 100, 100),
        clickable: Boolean = false,
        longClickable: Boolean = false,
        focusable: Boolean = false,
        scrollable: Boolean = false,
        editable: Boolean = false,
        enabled: Boolean = false,
        visible: Boolean = false,
        children: List<AccessibilityNodeData> = emptyList(),
    ) = AccessibilityNodeData(
        id = id,
        className = className,
        text = text,
        contentDescription = contentDescription,
        resourceId = resourceId,
        bounds = bounds,
        clickable = clickable,
        longClickable = longClickable,
        focusable = focusable,
        scrollable = scrollable,
        editable = editable,
        enabled = enabled,
        visible = visible,
        children = children,
    )

    private fun snapshotOf(
        result: MultiWindowResult,
        id: String = "snap1",
        options: CompactFormatOptions = CompactFormatOptions(),
    ): ScreenStateSnapshot {
        val total = formatter.countKeptNodes(result, options)
        val size = CompactTreeFormatter.PAGE_SIZE
        val pages = if (total <= 0) 1 else (total + size - 1) / size
        return ScreenStateSnapshot(id, result, defaultScreenInfo, total, pages, options)
    }

    private fun tsvRows(pageText: String): List<String> {
        val rows = pageText.lines().filter { it.contains('\t') && !it.startsWith("node_id\t") }
        return rows
    }

    private fun hierarchyLines(pageText: String): List<String> {
        val lines = pageText.lines()
        val idx = lines.indexOfLast { it == CompactTreeFormatter.HIERARCHY_HEADER }
        return if (idx < 0) emptyList() else lines.subList(idx + 1, lines.size)
    }

    // ─────────────────────────────────────────────────────────────────────
    // format
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("format")
    inner class FormatTests {
        @Test
        @DisplayName("produces note line as first line")
        fun producesNoteLineAsFirstLine() {
            val tree = makeNode(text = "hello")
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val lines = output.lines()
            assertEquals(CompactTreeFormatter.NOTE_LINE, lines[0])
        }

        @Test
        @DisplayName("produces custom elements note as second line")
        fun producesCustomElementsNoteAsSecondLine() {
            val tree = makeNode(text = "hello")
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val lines = output.lines()
            assertEquals(CompactTreeFormatter.NOTE_LINE_CUSTOM_ELEMENTS, lines[1])
        }

        @Test
        @DisplayName("produces flags legend note as third line")
        fun producesFlagsLegendNoteAsThirdLine() {
            val tree = makeNode(text = "hello")
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val lines = output.lines()
            assertEquals(CompactTreeFormatter.NOTE_LINE_FLAGS_LEGEND, lines[2])
        }

        @Test
        @DisplayName("produces offscreen hint note as fourth line")
        fun producesOffscreenHintNoteAsFourthLine() {
            val tree = makeNode(text = "hello")
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val lines = output.lines()
            assertEquals(CompactTreeFormatter.NOTE_LINE_OFFSCREEN_HINT, lines[3])
        }

        @Test
        @DisplayName("produces correct metadata lines")
        fun producesCorrectMetadataLines() {
            val tree = makeNode(text = "hello")
            val output = formatter.format(tree, "com.example.app", ".MainActivity", defaultScreenInfo)
            val lines = output.lines()
            assertEquals("app:com.example.app activity:.MainActivity", lines[4])
            assertEquals("screen:1080x2400 density:420 orientation:portrait", lines[5])
        }

        @Test
        @DisplayName("produces correct header line")
        fun producesCorrectHeaderLine() {
            val tree = makeNode(text = "hello")
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val lines = output.lines()
            assertEquals("node_id\tclass\ttext\tdesc\tres_id\tbounds\tflags", lines[6])
        }

        @Test
        @DisplayName("outputs kept nodes as flat TSV rows")
        fun outputsKeptNodesAsFlatTsvRows() {
            val tree =
                makeNode(
                    id = "node_btn",
                    className = "android.widget.Button",
                    text = "OK",
                    resourceId = "btn_ok",
                    bounds = BoundsData(100, 200, 300, 260),
                    clickable = true,
                    visible = true,
                    enabled = true,
                )
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val lines = output.lines()
            // Data rows start at line 7 (0-indexed)
            assertEquals("node_btn\tButton\tOK\t-\tbtn_ok\t100,200,300,260\ton,clk,ena", lines[7])
        }

        @Test
        @DisplayName("filters out noise nodes")
        fun filtersOutNoiseNodes() {
            val tree =
                makeNode(
                    id = "node_frame",
                    className = "android.widget.FrameLayout",
                    bounds = BoundsData(0, 0, 1080, 2400),
                )
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val lines = output.lines()
            // notes + metadata + header = 7 lines + hierarchy: label = 8 lines, no data rows
            assertEquals(8, lines.size)
        }

        @Test
        @DisplayName("includes children of filtered nodes")
        fun includesChildrenOfFilteredNodes() {
            val child =
                makeNode(
                    id = "node_child",
                    className = "android.widget.Button",
                    text = "Click me",
                    clickable = true,
                    visible = true,
                    enabled = true,
                )
            val parent =
                makeNode(
                    id = "node_parent",
                    className = "android.widget.FrameLayout",
                    children = listOf(child),
                )
            val output = formatter.format(parent, "com.example", ".Main", defaultScreenInfo)
            val lines = output.lines()
            // Parent is filtered, child appears + hierarchy: + hierarchy node
            assertEquals(10, lines.size)
            assertTrue(lines[7].startsWith("node_child\t"))
        }

        @Test
        @DisplayName("includes non-visible nodes that pass filter")
        fun includesNonVisibleNodesThatPassFilter() {
            val tree =
                makeNode(
                    id = "node_hidden",
                    className = "android.widget.Button",
                    text = "Hidden",
                    visible = false,
                    enabled = true,
                )
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val lines = output.lines()
            // data row + hierarchy: + hierarchy node = 10 lines
            assertEquals(10, lines.size)
            assertTrue(lines[7].contains("node_hidden"))
            // Check the flags column (last tab-separated field) starts with "off"
            val flags = lines[7].split("\t").last()
            assertTrue(flags.startsWith("off"), "Flags should start with 'off' but was: $flags")
            assertFalse(flags.startsWith("on"), "Flags should not start with 'on' but was: $flags")
        }

        @Test
        @DisplayName("handles tree with all nodes filtered")
        fun handlesTreeWithAllNodesFiltered() {
            val tree =
                makeNode(
                    id = "node_root",
                    className = "android.widget.FrameLayout",
                    children =
                        listOf(
                            makeNode(
                                id = "node_inner",
                                className = "android.widget.LinearLayout",
                            ),
                        ),
                )
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val lines = output.lines()
            // notes + metadata + header = 7 lines + hierarchy: label = 8 lines
            assertEquals(8, lines.size)
        }

        @Test
        @DisplayName("bounds formatted correctly")
        fun boundsFormattedCorrectly() {
            val tree =
                makeNode(
                    id = "node_a",
                    text = "Test",
                    bounds = BoundsData(10, 20, 300, 400),
                )
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val lines = output.lines()
            assertTrue(lines[7].contains("10,20,300,400"))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // walkTree hierarchy
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("walkTree hierarchy")
    inner class WalkHierarchyTests {
        private fun extractHierarchy(output: String): List<String> {
            val lines = output.lines()
            val headerIdx = lines.indexOf(CompactTreeFormatter.HIERARCHY_HEADER)
            return if (headerIdx >= 0) lines.drop(headerIdx + 1) else emptyList()
        }

        @Test
        @DisplayName("hierarchy section present after elements")
        fun hierarchySectionPresentAfterElements() {
            val tree = makeNode(id = "node_a", text = "Hello", visible = true)
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val lines = output.lines()
            val headerIdx = lines.indexOf(CompactTreeFormatter.HIERARCHY_HEADER)
            assertTrue(headerIdx > 0, "hierarchy: header should be present")
            // hierarchy: should come after the last element row
            val lastElementRow = lines.indexOfLast { it.startsWith("node_") && it.contains("\t") }
            assertTrue(headerIdx > lastElementRow, "hierarchy: should be after last element row")
        }

        @Test
        @DisplayName("single kept node produces flat hierarchy")
        fun singleKeptNodeProducesFlatHierarchy() {
            val tree = makeNode(id = "node_a", text = "Hello", visible = true)
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val hierarchy = extractHierarchy(output)
            assertEquals(1, hierarchy.size)
            assertEquals("node_a", hierarchy[0])
        }

        @Test
        @DisplayName("parent-child nesting uses 2-space indentation")
        fun parentChildNestingUses2SpaceIndentation() {
            val child = makeNode(id = "node_child", text = "Child", visible = true)
            val parent = makeNode(id = "node_parent", text = "Parent", visible = true, children = listOf(child))
            val output = formatter.format(parent, "com.example", ".Main", defaultScreenInfo)
            val hierarchy = extractHierarchy(output)
            assertEquals(2, hierarchy.size)
            assertEquals("node_parent", hierarchy[0])
            assertEquals("  node_child", hierarchy[1])
        }

        @Test
        @DisplayName("filtered parent promotes children to parent depth")
        fun filteredParentPromotesChildrenToParentDepth() {
            val child = makeNode(id = "node_child", text = "Child", visible = true)
            val parent =
                makeNode(
                    id = "node_structural",
                    className = "android.widget.FrameLayout",
                    children = listOf(child),
                )
            val output = formatter.format(parent, "com.example", ".Main", defaultScreenInfo)
            val hierarchy = extractHierarchy(output)
            assertEquals(1, hierarchy.size)
            assertEquals("node_child", hierarchy[0])
        }

        @Test
        @DisplayName("deep nesting produces correct indentation levels")
        fun deepNestingProducesCorrectIndentationLevels() {
            val grandchild = makeNode(id = "node_gc", text = "GC", visible = true)
            val child = makeNode(id = "node_c", text = "C", visible = true, children = listOf(grandchild))
            val root = makeNode(id = "node_r", text = "R", visible = true, children = listOf(child))
            val output = formatter.format(root, "com.example", ".Main", defaultScreenInfo)
            val hierarchy = extractHierarchy(output)
            assertEquals(3, hierarchy.size)
            assertEquals("node_r", hierarchy[0])
            assertEquals("  node_c", hierarchy[1])
            assertEquals("    node_gc", hierarchy[2])
        }

        @Test
        @DisplayName("multiple children at same level")
        fun multipleChildrenAtSameLevel() {
            val child1 = makeNode(id = "node_c1", text = "C1", visible = true)
            val child2 = makeNode(id = "node_c2", text = "C2", visible = true)
            val parent = makeNode(id = "node_p", text = "P", visible = true, children = listOf(child1, child2))
            val output = formatter.format(parent, "com.example", ".Main", defaultScreenInfo)
            val hierarchy = extractHierarchy(output)
            assertEquals(3, hierarchy.size)
            assertEquals("node_p", hierarchy[0])
            assertEquals("  node_c1", hierarchy[1])
            assertEquals("  node_c2", hierarchy[2])
        }

        @Test
        @DisplayName("all nodes filtered produces only hierarchy label")
        fun allNodesFilteredProducesOnlyHierarchyLabel() {
            val tree =
                makeNode(
                    id = "node_root",
                    className = "android.widget.FrameLayout",
                    children =
                        listOf(
                            makeNode(id = "node_inner", className = "android.widget.LinearLayout"),
                        ),
                )
            val output = formatter.format(tree, "com.example", ".Main", defaultScreenInfo)
            val hierarchy = extractHierarchy(output)
            assertTrue(hierarchy.isEmpty(), "No hierarchy nodes expected when all are filtered")
            assertTrue(output.contains(CompactTreeFormatter.HIERARCHY_HEADER))
        }

        @Test
        @DisplayName("mixed kept and filtered in deep tree")
        fun mixedKeptAndFilteredInDeepTree() {
            val leaf = makeNode(id = "node_leaf", text = "Leaf", visible = true)
            val mid =
                makeNode(
                    id = "node_mid",
                    className = "android.widget.FrameLayout",
                    children = listOf(leaf),
                )
            val root = makeNode(id = "node_root", text = "Root", visible = true, children = listOf(mid))
            val output = formatter.format(root, "com.example", ".Main", defaultScreenInfo)
            val hierarchy = extractHierarchy(output)
            assertEquals(2, hierarchy.size)
            assertEquals("node_root", hierarchy[0])
            assertEquals("  node_leaf", hierarchy[1])
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // shouldKeepNode
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("shouldKeepNode")
    inner class ShouldKeepNodeTests {
        @Test
        @DisplayName("returns true for node with text")
        fun returnsTrueForNodeWithText() {
            assertTrue(formatter.shouldKeepNode(makeNode(text = "hello")))
        }

        @Test
        @DisplayName("returns true for node with contentDescription")
        fun returnsTrueForNodeWithContentDescription() {
            assertTrue(formatter.shouldKeepNode(makeNode(contentDescription = "desc")))
        }

        @Test
        @DisplayName("returns true for node with resourceId")
        fun returnsTrueForNodeWithResourceId() {
            assertTrue(formatter.shouldKeepNode(makeNode(resourceId = "com.example:id/btn")))
        }

        @Test
        @DisplayName("returns true for clickable node")
        fun returnsTrueForClickableNode() {
            assertTrue(formatter.shouldKeepNode(makeNode(clickable = true)))
        }

        @Test
        @DisplayName("returns true for longClickable node")
        fun returnsTrueForLongClickableNode() {
            assertTrue(formatter.shouldKeepNode(makeNode(longClickable = true)))
        }

        @Test
        @DisplayName("returns true for scrollable node")
        fun returnsTrueForScrollableNode() {
            assertTrue(formatter.shouldKeepNode(makeNode(scrollable = true)))
        }

        @Test
        @DisplayName("returns true for editable node")
        fun returnsTrueForEditableNode() {
            assertTrue(formatter.shouldKeepNode(makeNode(editable = true)))
        }

        @Test
        @DisplayName("returns false for empty non-interactive node")
        fun returnsFalseForEmptyNonInteractiveNode() {
            assertFalse(formatter.shouldKeepNode(makeNode()))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // simplifyClassName
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("simplifyClassName")
    inner class SimplifyClassNameTests {
        @Test
        @DisplayName("strips package prefix")
        fun stripsPackagePrefix() {
            assertEquals("Button", formatter.simplifyClassName("android.widget.Button"))
        }

        @Test
        @DisplayName("handles no package")
        fun handlesNoPackage() {
            assertEquals("Button", formatter.simplifyClassName("Button"))
        }

        @Test
        @DisplayName("returns dash for null")
        fun returnsDashForNull() {
            assertEquals("-", formatter.simplifyClassName(null))
        }

        @Test
        @DisplayName("returns dash for empty")
        fun returnsDashForEmpty() {
            assertEquals("-", formatter.simplifyClassName(""))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // sanitizeText
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sanitizeText")
    inner class SanitizeTextTests {
        @Test
        @DisplayName("replaces tabs with spaces")
        fun replacesTabsWithSpaces() {
            assertEquals("hello world", formatter.sanitizeText("hello\tworld"))
        }

        @Test
        @DisplayName("replaces newlines with spaces")
        fun replacesNewlinesWithSpaces() {
            assertEquals("hello world", formatter.sanitizeText("hello\nworld"))
        }

        @Test
        @DisplayName("replaces carriage returns with spaces")
        fun replacesCarriageReturnsWithSpaces() {
            assertEquals("hello world", formatter.sanitizeText("hello\rworld"))
        }

        @Test
        @DisplayName("returns dash for null")
        fun returnsDashForNull() {
            assertEquals("-", formatter.sanitizeText(null))
        }

        @Test
        @DisplayName("returns dash for empty string")
        fun returnsDashForEmptyString() {
            assertEquals("-", formatter.sanitizeText(""))
        }

        @Test
        @DisplayName("returns dash for whitespace-only after sanitization")
        fun returnsDashForWhitespaceOnlyAfterSanitization() {
            assertEquals("-", formatter.sanitizeText("\t\n"))
        }

        @Test
        @DisplayName("truncates long text to 100 chars with suffix")
        fun truncatesLongTextTo100CharsWithSuffix() {
            val longText = "a".repeat(150)
            val result = formatter.sanitizeText(longText)
            assertEquals("a".repeat(100) + "...truncated", result)
        }

        @Test
        @DisplayName("does not truncate text at exactly 100 chars")
        fun doesNotTruncateTextAtExactly100Chars() {
            val exactText = "a".repeat(100)
            assertEquals(exactText, formatter.sanitizeText(exactText))
        }

        @Test
        @DisplayName("truncates text at 101 chars")
        fun truncatesTextAt101Chars() {
            val text = "a".repeat(101)
            val result = formatter.sanitizeText(text)
            assertEquals("a".repeat(100) + "...truncated", result)
        }

        @Test
        @DisplayName("does not truncate when truncate=false (merged WebView content)")
        fun doesNotTruncateWhenTruncateFalse() {
            val longText = "a".repeat(150)
            assertEquals(longText, formatter.sanitizeText(longText, truncate = false))
        }

        @Test
        @DisplayName("appendElementRow does not truncate web nodes but truncates native nodes")
        fun appendElementRowSkipsTruncationForWebNodes() {
            val longText = "b".repeat(150)
            val webNode =
                AccessibilityNodeData(
                    id = "w",
                    bounds = BoundsData(0, 0, 1, 1),
                    text = longText,
                    webRole = "article",
                )
            val nativeNode =
                AccessibilityNodeData(
                    id = "n",
                    bounds = BoundsData(0, 0, 1, 1),
                    text = longText,
                )
            val webRow = StringBuilder().also { formatter.appendElementRow(it, webNode) }.toString()
            val nativeRow = StringBuilder().also { formatter.appendElementRow(it, nativeNode) }.toString()
            assertTrue(webRow.contains(longText), "web node text must not be truncated")
            assertTrue(nativeRow.contains("...truncated"), "native node text must still truncate")
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // sanitizeResourceId
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("sanitizeResourceId")
    inner class SanitizeResourceIdTests {
        @Test
        @DisplayName("returns dash for null")
        fun returnsDashForNull() {
            assertEquals("-", formatter.sanitizeResourceId(null))
        }

        @Test
        @DisplayName("returns dash for empty")
        fun returnsDashForEmpty() {
            assertEquals("-", formatter.sanitizeResourceId(""))
        }

        @Test
        @DisplayName("does not truncate long resourceId")
        fun doesNotTruncateLongResourceId() {
            val longId = "com.example.very.long.package:id/" + "a".repeat(200)
            assertEquals(longId, formatter.sanitizeResourceId(longId))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // buildFlags
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildFlags")
    inner class BuildFlagsTests {
        @Test
        @DisplayName("includes all set flags in correct order")
        fun includesAllSetFlagsInCorrectOrder() {
            val node =
                makeNode(
                    visible = true,
                    clickable = true,
                    longClickable = true,
                    focusable = true,
                    scrollable = true,
                    editable = true,
                    enabled = true,
                )
            assertEquals("on,clk,lclk,foc,scr,edt,ena", formatter.buildFlags(node))
        }

        @Test
        @DisplayName("includes only set flags")
        fun includesOnlySetFlags() {
            val node =
                makeNode(
                    visible = true,
                    clickable = true,
                    enabled = true,
                )
            assertEquals("on,clk,ena", formatter.buildFlags(node))
        }

        @Test
        @DisplayName("returns off when no flags set except visibility")
        fun returnsOffWhenNoFlagsSet() {
            assertEquals("off", formatter.buildFlags(makeNode()))
        }

        @Test
        @DisplayName("includes off flag for non-visible nodes")
        fun includesOffFlagForNonVisibleNodes() {
            val node =
                makeNode(
                    visible = false,
                    clickable = true,
                    enabled = true,
                )
            assertEquals("off,clk,ena", formatter.buildFlags(node))
        }

        @Test
        @DisplayName("on is always first flag")
        fun onIsAlwaysFirstFlag() {
            val node =
                makeNode(
                    visible = true,
                    enabled = true,
                )
            val flags = formatter.buildFlags(node)
            assertTrue(flags.startsWith("on,") || flags == "on")
        }

        @Test
        @DisplayName("returns only on when visible and no other flags set")
        fun returnsOnlyOnWhenVisibleAndNoOtherFlags() {
            val node = makeNode(visible = true)
            assertEquals("on", formatter.buildFlags(node))
        }

        @Test
        @DisplayName("includes off flag with all other flags set")
        fun includesOffFlagWithAllOtherFlagsSet() {
            val node =
                makeNode(
                    visible = false,
                    clickable = true,
                    longClickable = true,
                    focusable = true,
                    scrollable = true,
                    editable = true,
                    enabled = true,
                )
            assertEquals("off,clk,lclk,foc,scr,edt,ena", formatter.buildFlags(node))
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // formatMultiWindow
    // ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("formatMultiWindow")
    inner class FormatMultiWindow {
        @Test
        @DisplayName("single window produces correct output")
        fun singleWindowProducesCorrectOutput() {
            val tree =
                makeNode(
                    id = "node_btn",
                    className = "android.widget.Button",
                    text = "OK",
                    bounds = BoundsData(0, 0, 100, 48),
                    clickable = true,
                    visible = true,
                    enabled = true,
                )
            val result =
                MultiWindowResult(
                    windows =
                        listOf(
                            WindowData(
                                windowId = 0,
                                windowType = "APPLICATION",
                                packageName = "com.example.app",
                                title = "MainActivity",
                                activityName = "com.example.app.MainActivity",
                                layer = 0,
                                focused = true,
                                tree = tree,
                            ),
                        ),
                    degraded = false,
                )
            val output = formatter.formatMultiWindow(result, defaultScreenInfo)
            assertTrue(output.contains("screen:1080x2400"))
            assertTrue(
                output.contains(
                    "--- window:0 type:APPLICATION pkg:com.example.app title:MainActivity " +
                        "activity:com.example.app.MainActivity layer:0 focused:true ---",
                ),
            )
            assertTrue(output.contains("node_btn"))
            assertFalse(output.contains(CompactTreeFormatter.DEGRADATION_NOTE))
        }

        @Test
        @DisplayName("two windows produce both sections")
        fun twoWindowsProduceBothSections() {
            val appTree = makeNode(id = "node_app", text = "App", clickable = true, visible = true)
            val dialogTree = makeNode(id = "node_allow", text = "Allow", clickable = true, visible = true)
            val result =
                MultiWindowResult(
                    windows =
                        listOf(
                            WindowData(
                                windowId = 0,
                                windowType = "APPLICATION",
                                packageName = "com.example",
                                title = "Main",
                                layer = 0,
                                focused = false,
                                tree = appTree,
                            ),
                            WindowData(
                                windowId = 1,
                                windowType = "SYSTEM",
                                packageName = "com.android.permissioncontroller",
                                title = "Permission",
                                layer = 1,
                                focused = true,
                                tree = dialogTree,
                            ),
                        ),
                )
            val output = formatter.formatMultiWindow(result, defaultScreenInfo)
            assertTrue(output.contains("--- window:0 type:APPLICATION"))
            assertTrue(output.contains("--- window:1 type:SYSTEM"))
            assertTrue(output.contains("node_app"))
            assertTrue(output.contains("node_allow"))
        }

        @Test
        @DisplayName("activity omitted when null")
        fun activityOmittedWhenNull() {
            val tree = makeNode(id = "node_x", text = "X", clickable = true, visible = true)
            val result =
                MultiWindowResult(
                    windows =
                        listOf(
                            WindowData(
                                windowId = 0,
                                windowType = "SYSTEM",
                                packageName = "com.android.systemui",
                                title = "StatusBar",
                                activityName = null,
                                layer = 0,
                                focused = false,
                                tree = tree,
                            ),
                        ),
                )
            val output = formatter.formatMultiWindow(result, defaultScreenInfo)
            assertTrue(output.contains("--- window:0 type:SYSTEM"))
            assertFalse(output.contains("activity:"))
        }

        @Test
        @DisplayName("degraded mode adds note")
        fun degradedModeAddsNote() {
            val tree = makeNode(id = "node_x", text = "X", clickable = true, visible = true)
            val result =
                MultiWindowResult(
                    windows =
                        listOf(
                            WindowData(windowId = 0, windowType = "APPLICATION", tree = tree, focused = true),
                        ),
                    degraded = true,
                )
            val output = formatter.formatMultiWindow(result, defaultScreenInfo)
            assertTrue(output.startsWith(CompactTreeFormatter.DEGRADATION_NOTE))
        }

        @Test
        @DisplayName("null packageName rendered as unknown")
        fun nullPackageNameRenderedAsUnknown() {
            val tree = makeNode(id = "node_x", text = "X", clickable = true, visible = true)
            val result =
                MultiWindowResult(
                    windows =
                        listOf(
                            WindowData(
                                windowId = 0,
                                windowType = "APPLICATION",
                                packageName = null,
                                tree = tree,
                                focused = true,
                            ),
                        ),
                )
            val output = formatter.formatMultiWindow(result, defaultScreenInfo)
            assertTrue(output.contains("pkg:unknown"))
        }

        @Test
        @DisplayName("null title rendered as unknown")
        fun nullTitleRenderedAsUnknown() {
            val tree = makeNode(id = "node_x", text = "X", clickable = true, visible = true)
            val result =
                MultiWindowResult(
                    windows =
                        listOf(
                            WindowData(
                                windowId = 0,
                                windowType = "APPLICATION",
                                title = null,
                                tree = tree,
                                focused = true,
                            ),
                        ),
                )
            val output = formatter.formatMultiWindow(result, defaultScreenInfo)
            assertTrue(output.contains("title:unknown"))
        }

        @Test
        @DisplayName("buildWindowHeader produces correct format with all fields")
        fun buildWindowHeaderAllFields() {
            val wd =
                WindowData(
                    windowId = 2,
                    windowType = "INPUT_METHOD",
                    packageName = "com.google.android.inputmethod.latin",
                    title = "Gboard",
                    activityName = null,
                    layer = 5,
                    focused = false,
                    tree = makeNode(id = "node_x"),
                )
            val header = formatter.buildWindowHeader(wd)
            assertEquals(
                "--- window:2 type:INPUT_METHOD pkg:com.google.android.inputmethod.latin " +
                    "title:Gboard layer:5 focused:false ---",
                header,
            )
        }

        @Test
        @DisplayName("buildWindowHeader includes activity when present")
        fun buildWindowHeaderWithActivity() {
            val wd =
                WindowData(
                    windowId = 0,
                    windowType = "APPLICATION",
                    packageName = "com.example",
                    title = "Main",
                    activityName = "com.example.MainActivity",
                    layer = 0,
                    focused = true,
                    tree = makeNode(id = "node_x"),
                )
            val header = formatter.buildWindowHeader(wd)
            assertTrue(header.contains("activity:com.example.MainActivity"))
        }

        @Test
        @DisplayName("each window has hierarchy section")
        fun eachWindowHasHierarchySection() {
            val appTree = makeNode(id = "node_app", text = "App", clickable = true, visible = true)
            val dialogTree = makeNode(id = "node_dialog", text = "Dialog", clickable = true, visible = true)
            val result =
                MultiWindowResult(
                    windows =
                        listOf(
                            WindowData(
                                windowId = 0,
                                windowType = "APPLICATION",
                                packageName = "com.example",
                                title = "Main",
                                layer = 0,
                                focused = false,
                                tree = appTree,
                            ),
                            WindowData(
                                windowId = 1,
                                windowType = "SYSTEM",
                                packageName = "com.android.system",
                                title = "Dialog",
                                layer = 1,
                                focused = true,
                                tree = dialogTree,
                            ),
                        ),
                )
            val output = formatter.formatMultiWindow(result, defaultScreenInfo)
            val lines = output.lines()
            val hierarchyIndices = lines.indices.filter { lines[it] == CompactTreeFormatter.HIERARCHY_HEADER }
            assertEquals(2, hierarchyIndices.size, "Each window should have its own hierarchy: section")
            // First window hierarchy should contain node_app
            val firstHierarchyNodeIdx = hierarchyIndices[0] + 1
            assertTrue(lines[firstHierarchyNodeIdx] == "node_app")
            // Second window hierarchy should contain node_dialog
            val secondHierarchyNodeIdx = hierarchyIndices[1] + 1
            assertTrue(lines[secondHierarchyNodeIdx] == "node_dialog")
        }
    }

    @Nested
    @DisplayName("pagination")
    inner class PaginationTests {
        @Test
        @DisplayName("countKeptNodes matches formatMultiWindow row count")
        fun countKeptNodesMatchesFormatMultiWindowRowCount() {
            val result = PaginationTestTrees.result(PaginationTestTrees.keptNodeWindow(50))
            val rows = tsvRows(formatter.formatMultiWindow(result, defaultScreenInfo))
            assertEquals(rows.size, formatter.countKeptNodes(result))
        }

        @Test
        @DisplayName("page 1 contains first 200 rows")
        fun page1ContainsFirst200Rows() {
            // ancestorDepth=2 + 448 leaves = 450 kept -> 3 pages (200/200/50)
            val result = PaginationTestTrees.result(PaginationTestTrees.keptNodeWindow(448))
            val page = formatter.formatMultiWindowPage(snapshotOf(result), 1)
            assertTrue(page.contains("page:1/3 snapshot:snap1 nodes:1-200/450"))
            assertEquals(200, tsvRows(page).size)
            assertTrue(page.contains("node_anc_0\t"))
            assertTrue(page.contains("node_leaf_0\t"))
            assertTrue(page.contains("node_leaf_197\t"))
            assertFalse(page.contains("node_leaf_198\t"))
        }

        @Test
        @DisplayName("page 2 contains rows 201..400")
        fun page2ContainsRows201To400() {
            val result = PaginationTestTrees.result(PaginationTestTrees.keptNodeWindow(448))
            val page = formatter.formatMultiWindowPage(snapshotOf(result), 2)
            assertTrue(page.contains("page:2/3 snapshot:snap1 nodes:201-400/450"))
            assertEquals(200, tsvRows(page).size)
            assertTrue(page.contains("node_leaf_198\t"))
            assertTrue(page.contains("node_leaf_397\t"))
            assertFalse(page.contains("node_leaf_197\t"))
            assertFalse(page.contains("node_leaf_398\t"))
        }

        @Test
        @DisplayName("last page has end note and earlier pages have next-cursor note")
        fun lastPageHasEndNote() {
            val snap = snapshotOf(PaginationTestTrees.result(PaginationTestTrees.keptNodeWindow(448)))
            assertTrue(formatter.formatMultiWindowPage(snap, 1).contains("cursor \"snap1.2\""))
            assertTrue(formatter.formatMultiWindowPage(snap, 2).contains("cursor \"snap1.3\""))
            val lastPage = formatter.formatMultiWindowPage(snap, 3)
            assertTrue(lastPage.contains("note:end of snapshot (page 3/3)"))
            assertFalse(lastPage.contains("cursor \"snap1.4\""))
        }

        @Test
        @DisplayName("page hierarchy includes kept ancestors of page rows but not in TSV")
        fun pageHierarchyIncludesKeptAncestors() {
            val result = PaginationTestTrees.result(PaginationTestTrees.keptNodeWindow(448))
            val page2 = formatter.formatMultiWindowPage(snapshotOf(result), 2)
            val hierarchy = hierarchyLines(page2)
            // ancestors of page-2 leaves appear in the hierarchy...
            assertTrue(hierarchy.contains("node_anc_0"))
            assertTrue(hierarchy.contains("  node_anc_1"))
            // ...but NOT as TSV rows on page 2 (their rows are on page 1)
            assertFalse(page2.contains("node_anc_0\t"))
            assertFalse(page2.contains("node_anc_1\t"))
        }

        @Test
        @DisplayName("page hierarchy indentation equals full hierarchy")
        fun pageHierarchyIndentationEqualsFullHierarchy() {
            val result = PaginationTestTrees.result(PaginationTestTrees.keptNodeWindow(448))
            val full = formatter.formatMultiWindow(result, defaultScreenInfo)
            val page2 = formatter.formatMultiWindowPage(snapshotOf(result), 2)
            // leaf_198 sits at depth 2 (anc_0 -> anc_1 -> leaf) => 4-space indent, in BOTH outputs
            assertTrue(full.lines().contains("    node_leaf_198"))
            assertTrue(page2.lines().contains("    node_leaf_198"))
        }

        @Test
        @DisplayName("window header repeats when window spans pages")
        fun windowHeaderRepeatsWhenWindowSpansPages() {
            val window = PaginationTestTrees.keptNodeWindow(448, windowId = 7, packageName = "com.span")
            val snap = snapshotOf(PaginationTestTrees.result(window))
            val header = formatter.buildWindowHeader(window)
            assertTrue(formatter.formatMultiWindowPage(snap, 1).contains(header))
            assertTrue(formatter.formatMultiWindowPage(snap, 2).contains(header))
        }

        @Test
        @DisplayName("multi-window page boundary splits correctly")
        fun multiWindowPageBoundarySplitsCorrectly() {
            val w1 = PaginationTestTrees.keptNodeWindow(148, windowId = 1, packageName = "com.app1")
            val w2 = PaginationTestTrees.keptNodeWindow(98, windowId = 2, packageName = "com.app2")
            // w1=150 kept, w2=100 kept, total=250 -> 2 pages; page 2 (rows 201-250) is window 2 only
            val snap = snapshotOf(MultiWindowResult(windows = listOf(w1, w2)))
            val page2 = formatter.formatMultiWindowPage(snap, 2)
            assertTrue(page2.contains("page:2/2 snapshot:snap1 nodes:201-250/250"))
            assertTrue(page2.contains("window:2 "))
            assertTrue(page2.contains("pkg:com.app2 "))
            assertFalse(page2.contains("window:1 "))
            assertFalse(page2.contains("pkg:com.app1 "))
        }

        @Test
        @DisplayName("single page output has no pagination metadata")
        fun singlePageOutputHasNoPaginationMetadata() {
            val result = PaginationTestTrees.result(PaginationTestTrees.keptNodeWindow(10))
            val output = formatter.formatMultiWindow(result, defaultScreenInfo)
            assertFalse(output.contains("page:"))
            assertFalse(output.contains("cursor"))
        }

        @Test
        @DisplayName("degraded result paginates with degradation note on each page")
        fun degradedResultPaginatesWithDegradationNoteOnEachPage() {
            val window = PaginationTestTrees.keptNodeWindow(448)
            val snap = snapshotOf(PaginationTestTrees.result(window, degraded = true))
            val note = CompactTreeFormatter.DEGRADATION_NOTE
            assertEquals(note, formatter.formatMultiWindowPage(snap, 1).lines().first())
            assertEquals(note, formatter.formatMultiWindowPage(snap, 2).lines().first())
            assertEquals(note, formatter.formatMultiWindowPage(snap, 3).lines().first())
        }
    }

    @Nested
    @DisplayName("format options")
    inner class FormatOptionsTests {
        private val offscreenParentTree =
            makeNode(
                id = "node_root",
                className = "android.widget.FrameLayout",
                resourceId = "com.example.app:id/root",
                enabled = true,
                visible = true,
                children =
                    listOf(
                        makeNode(
                            id = "node_off_parent",
                            className = "android.widget.FrameLayout",
                            resourceId = "com.example.app:id/off_parent",
                            enabled = true,
                            visible = false,
                            children =
                                listOf(
                                    makeNode(
                                        id = "node_on_child",
                                        className = "android.widget.TextView",
                                        text = "Child",
                                        enabled = true,
                                        visible = true,
                                    ),
                                ),
                        ),
                    ),
            )

        private fun offscreenParentResult(): MultiWindowResult =
            PaginationTestTrees.result(
                WindowData(
                    windowId = 0,
                    windowType = "APPLICATION",
                    packageName = "com.example.app",
                    title = "Main",
                    focused = true,
                    tree = offscreenParentTree,
                ),
            )

        private fun legacyPinResult(): MultiWindowResult {
            val on =
                makeNode(
                    id = "node_on",
                    className = "android.widget.Button",
                    text = "OK",
                    bounds = BoundsData(0, 0, 100, 48),
                    clickable = true,
                    enabled = true,
                    visible = true,
                )
            val off =
                makeNode(
                    id = "node_off",
                    className = "android.widget.TextView",
                    text = "Later",
                    bounds = BoundsData(0, 2500, 100, 2548),
                )
            val root =
                makeNode(
                    id = "node_root",
                    className = "android.widget.FrameLayout",
                    resourceId = "com.example.app:id/root",
                    bounds = BoundsData(0, 0, 1080, 2400),
                    enabled = true,
                    visible = true,
                    children = listOf(on, off),
                )
            return PaginationTestTrees.result(
                WindowData(
                    windowId = 0,
                    windowType = "APPLICATION",
                    packageName = "com.example.app",
                    title = "Main",
                    activityName = null,
                    layer = 0,
                    focused = true,
                    tree = root,
                ),
            )
        }

        private fun legacyPinExpected(): String =
            listOf(
                CompactTreeFormatter.NOTE_LINE,
                CompactTreeFormatter.NOTE_LINE_CUSTOM_ELEMENTS,
                CompactTreeFormatter.NOTE_LINE_FLAGS_LEGEND,
                CompactTreeFormatter.NOTE_LINE_OFFSCREEN_HINT,
                "screen:1080x2400 density:420 orientation:portrait",
                "--- window:0 type:APPLICATION pkg:com.example.app title:Main layer:0 focused:true ---",
                CompactTreeFormatter.HEADER,
                "node_root\tFrameLayout\t-\t-\tcom.example.app:id/root\t0,0,1080,2400\ton,ena",
                "node_on\tButton\tOK\t-\t-\t0,0,100,48\ton,clk,ena",
                "node_off\tTextView\tLater\t-\t-\t0,2500,100,2548\toff",
                "hierarchy:",
                "node_root",
                "  node_on",
                "  node_off",
            ).joinToString("\n")

        @Test
        @DisplayName("default options output equals pinned legacy output")
        fun defaultOptionsOutputEqualsPinnedLegacyOutput() {
            val result = legacyPinResult()
            val expected = legacyPinExpected()
            assertEquals(expected, formatter.formatMultiWindow(result, defaultScreenInfo))
            assertEquals(expected, formatter.formatMultiWindow(result, defaultScreenInfo, CompactFormatOptions()))
        }

        @Test
        @DisplayName("default paged output keeps the legacy note lines")
        fun defaultPagedOutputKeepsLegacyNoteLines() {
            val result = PaginationTestTrees.result(PaginationTestTrees.keptNodeWindow(250))
            val snap = snapshotOf(result)
            assertEquals(2, snap.totalPages)
            val expectedNotes =
                listOf(
                    CompactTreeFormatter.NOTE_LINE,
                    CompactTreeFormatter.NOTE_LINE_CUSTOM_ELEMENTS,
                    CompactTreeFormatter.NOTE_LINE_FLAGS_LEGEND,
                    CompactTreeFormatter.NOTE_LINE_OFFSCREEN_HINT,
                )
            for (page in 1..2) {
                val lines = formatter.formatMultiWindowPage(snap, page).lines()
                assertEquals(expectedNotes, lines.take(4))
                assertTrue(lines[4].startsWith("screen:"))
            }
        }

        @Test
        @DisplayName("includeOffscreen false omits off-screen rows")
        fun includeOffscreenFalseOmitsOffScreenRows() {
            val result = PaginationTestTrees.result(PaginationTestTrees.mixedVisibilityWindow(10, 3))
            val options = CompactFormatOptions(includeOffscreen = false)
            val output = formatter.formatMultiWindow(result, defaultScreenInfo, options)
            val rows = tsvRows(output)
            assertTrue(rows.none { it.substringAfterLast('\t').startsWith(CompactTreeFormatter.FLAG_OFFSCREEN) })
            val visibleIds = mutableListOf<String>()

            fun collect(node: AccessibilityNodeData) {
                if (node.visible) visibleIds.add(node.id)
                node.children.forEach { collect(it) }
            }
            collect(result.windows.single().tree)
            for (id in visibleIds) {
                assertTrue(rows.any { it.startsWith("$id\t") }, "missing row for $id")
            }
        }

        @Test
        @DisplayName("includeOffscreen false replaces offscreen hint note")
        fun includeOffscreenFalseReplacesOffscreenHintNote() {
            val result = PaginationTestTrees.result(PaginationTestTrees.mixedVisibilityWindow(4, 2))
            val slim =
                formatter.formatMultiWindow(result, defaultScreenInfo, CompactFormatOptions(includeOffscreen = false))
            assertTrue(slim.contains(CompactTreeFormatter.NOTE_LINE_OFFSCREEN_OMITTED))
            assertFalse(slim.contains(CompactTreeFormatter.NOTE_LINE_OFFSCREEN_HINT))

            val default = formatter.formatMultiWindow(result, defaultScreenInfo)
            assertTrue(default.contains(CompactTreeFormatter.NOTE_LINE_OFFSCREEN_HINT))
            assertFalse(default.contains(CompactTreeFormatter.NOTE_LINE_OFFSCREEN_OMITTED))
        }

        @Test
        @DisplayName("includeOffscreen false promotes on-screen child of off-screen kept parent")
        fun includeOffscreenFalsePromotesOnScreenChildOfOffScreenParent() {
            val output =
                formatter.formatMultiWindow(
                    offscreenParentResult(),
                    defaultScreenInfo,
                    CompactFormatOptions(includeOffscreen = false),
                )
            val rows = tsvRows(output)
            assertTrue(rows.none { it.startsWith("node_off_parent\t") })
            val hierarchy = hierarchyLines(output)
            assertFalse(hierarchy.contains("node_off_parent"))
            assertTrue(hierarchy.contains("${CompactTreeFormatter.HIERARCHY_INDENT}node_on_child"))
        }

        @Test
        @DisplayName("includeHierarchy false omits hierarchy section in every window")
        fun includeHierarchyFalseOmitsHierarchySectionInEveryWindow() {
            val w1 = PaginationTestTrees.keptNodeWindow(3, windowId = 1)
            val w2 = PaginationTestTrees.keptNodeWindow(2, windowId = 2)
            val result = MultiWindowResult(windows = listOf(w1, w2))
            val output =
                formatter.formatMultiWindow(result, defaultScreenInfo, CompactFormatOptions(includeHierarchy = false))
            assertFalse(output.lines().contains(CompactTreeFormatter.HIERARCHY_HEADER))
            assertEquals(tsvRows(formatter.formatMultiWindow(result, defaultScreenInfo)), tsvRows(output))
        }

        @Test
        @DisplayName("countKeptNodes with options matches emitted row count")
        fun countKeptNodesWithOptionsMatchesEmittedRowCount() {
            val result = PaginationTestTrees.result(PaginationTestTrees.mixedVisibilityWindow(10, 3))
            val combinations =
                listOf(
                    CompactFormatOptions(),
                    CompactFormatOptions(includeOffscreen = false),
                    CompactFormatOptions(includeHierarchy = false),
                    CompactFormatOptions(includeOffscreen = false, includeHierarchy = false),
                )
            for (options in combinations) {
                val rows = tsvRows(formatter.formatMultiWindow(result, defaultScreenInfo, options))
                assertEquals(rows.size, formatter.countKeptNodes(result, options), "mismatch for $options")
            }
        }

        @Test
        @DisplayName("paged output honours snapshot options for rows and closure")
        fun pagedOutputHonoursSnapshotOptionsForRowsAndClosure() {
            val result = PaginationTestTrees.result(PaginationTestTrees.mixedVisibilityWindow(448, 2))
            val snap = snapshotOf(result, options = CompactFormatOptions(includeOffscreen = false))
            assertEquals(2, snap.totalPages)
            val page1 = formatter.formatMultiWindowPage(snap, 1)
            assertTrue(page1.contains("nodes:1-200/226"))
            val offscreenLeafIds =
                (0 until 448).filter { it % 2 == 0 }.map { "node_leaf_$it" }.toSet()
            for (page in 1..2) {
                val text = formatter.formatMultiWindowPage(snap, page)
                assertTrue(
                    tsvRows(text).none { it.substringAfterLast('\t').startsWith(CompactTreeFormatter.FLAG_OFFSCREEN) },
                )
                assertTrue(hierarchyLines(text).none { it.trim() in offscreenLeafIds })
            }
        }

        @Test
        @DisplayName("paged output promotes rows under an off-screen kept ancestor")
        fun pagedOutputPromotesRowsUnderOffScreenKeptAncestor() {
            // Guards appendWindowPageSection's hierarchy depth: node_anc_1 is kept but off-screen, so
            // its leaves move up one level exactly as formatMultiWindow renders them.
            val base = PaginationTestTrees.keptNodeWindow(250)
            val root = base.tree
            val offAncestor = root.children.single().copy(visible = false)
            val window = base.copy(tree = root.copy(children = listOf(offAncestor)))
            val result = PaginationTestTrees.result(window)
            val options = CompactFormatOptions(includeOffscreen = false)
            val snap = snapshotOf(result, options = options)
            assertEquals(2, snap.totalPages)

            val singlePageHierarchy = hierarchyLines(formatter.formatMultiWindow(result, defaultScreenInfo, options))
            // formatMultiWindowPage puts the pagination note after the hierarchy block; keep node lines only.
            val pagedHierarchy =
                (1..snap.totalPages)
                    .flatMap { hierarchyLines(formatter.formatMultiWindowPage(snap, it)) }
                    .filter { it.trim().startsWith("node_") }
            assertTrue(pagedHierarchy.none { it.trim() == "node_anc_1" })
            assertTrue(
                (1..snap.totalPages).none { page ->
                    tsvRows(formatter.formatMultiWindowPage(snap, page)).any { it.startsWith("node_anc_1\t") }
                },
            )
            for (line in pagedHierarchy) {
                if (line.trim() == "node_anc_0") continue
                assertTrue(singlePageHierarchy.contains(line), "indentation differs for '$line'")
            }
        }

        @Test
        @DisplayName("paged output without hierarchy has no hierarchy header")
        fun pagedOutputWithoutHierarchyHasNoHierarchyHeader() {
            val result = PaginationTestTrees.result(PaginationTestTrees.keptNodeWindow(250))
            val snap = snapshotOf(result, options = CompactFormatOptions(includeHierarchy = false))
            assertEquals(2, snap.totalPages)
            for (page in 1..2) {
                val text = formatter.formatMultiWindowPage(snap, page)
                assertFalse(text.lines().contains(CompactTreeFormatter.HIERARCHY_HEADER))
                assertTrue(text.contains("page:$page/2"))
                assertTrue(text.lines().last().startsWith("note:"))
            }
        }

        @Test
        @DisplayName("paged notes use omitted note when offscreen excluded")
        fun pagedNotesUseOmittedNoteWhenOffscreenExcluded() {
            val result = PaginationTestTrees.result(PaginationTestTrees.mixedVisibilityWindow(448, 2))
            val snap = snapshotOf(result, options = CompactFormatOptions(includeOffscreen = false))
            for (page in 1..2) {
                assertTrue(
                    formatter.formatMultiWindowPage(snap, page)
                        .contains(CompactTreeFormatter.NOTE_LINE_OFFSCREEN_OMITTED),
                )
            }
        }

        @Test
        @DisplayName("includeOffscreen false with only off-screen nodes emits no rows")
        fun includeOffscreenFalseWithOnlyOffScreenNodesEmitsNoRows() {
            val window = PaginationTestTrees.keptNodeWindow(5)
            val result = PaginationTestTrees.result(window.copy(tree = PaginationTestTrees.allOffscreen(window.tree)))
            val options = CompactFormatOptions(includeOffscreen = false)
            assertEquals(0, formatter.countKeptNodes(result, options))

            val output = formatter.formatMultiWindow(result, defaultScreenInfo, options)
            assertTrue(tsvRows(output).isEmpty())
            assertTrue(hierarchyLines(output).isEmpty())
            assertTrue(output.contains(CompactTreeFormatter.NOTE_LINE_OFFSCREEN_OMITTED))
            assertTrue(output.contains("screen:1080x2400"))
            assertTrue(output.contains("--- window:1 type:APPLICATION"))
            assertTrue(output.contains(CompactTreeFormatter.HEADER))
            assertFalse(output.contains("page:"))
        }
    }
}
