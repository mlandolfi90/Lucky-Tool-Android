<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 67: MCP tool latency optimization

Cuts the device-side cost of the node tools and starts measuring the network cost. Four stories: fewer IPCs per
tree read (US1), opt-in slimmer `get_screen_state` / `find_nodes` output (US2), `tap_node` / `click_node` by
selector in one call (US3), per-request timing logs on the relay and on the phone (US4, measurement only).

**Context not derivable from code or project docs:**

- Measured evidence (device logs of the current debug build, Samsung One UI phone, Android 14+, a Compose-heavy
  third-party app):
  - Full tree reads (`find_nodes`, `tap_node`, `wait_for_node`, `get_screen_state`) took 7-8 s each for about 500
    nodes while the app was busy, and about 1 s for 380-417 nodes when it was quieter. `wait_for_node` logged
    `found after 7484ms (1 attempts)`: a single tree walk.
  - `get_screen_state` pages were 21-24k characters; about 37% were off-screen rows (hidden pager pages, prefetched
    rows) and about 18% the `hierarchy:` section.
  - Every tap needed two calls (`find_nodes`, then `tap_node`), each doing a full tree read.
  - Each call spent about 1.2 s between the relay and the phone (0.9 s to reach it, 0.3 s back). The code-side share
    is estimated at 5-20 ms. The cause (client handshake per call, VPS distance, radio wake-up) is unknown, so US4
    only measures and changes no behaviour.
- Framework cache semantics behind US1: `getFreshWindowsLocked` always calls `clearFrameworkNodeCache()`
  (`NodeActionTools.kt:679`) inside `AccessibilityTreeLock` before `window.root` / `getChild`; `parseTree` has no
  other caller; `rawNodeExistsLocked` clears at `UtilityTools.kt:413`. The nodes read after the clear are fetched live
  (or prefetched by that same read), so the per-node `refresh()` that follows re-fetches each node again: one blocking
  IPC per node on the target app's UI thread. US1 reverses plan 44's decision to refresh virtual/Compose children in
  the raw walk (plan 44, lines 13 and 470-471). `ActionExecutorImpl.kt:679` (`cached.node.refresh()`) runs without a
  preceding clear and is NOT redundant; it stays. JVM mocks cannot prove framework cache behaviour, and
  `E2EComposeRefreshTest` / `E2EWebViewRefreshTest` (CI `E2E Tests`) poll for 10-15 s, so they prove only eventual
  freshness: a stale FIRST read caused by dropping `refresh()` would not fail CI. Single-read freshness is checked by
  Manual Test M2, and by CI only if D1.5 selects the tightened E2E check. Known leftover (D1.7 default): the KDoc of
  `E2EWebViewRefreshTest.kt` (lines 29-30, outside the scope boundary) keeps the old "refresh() cannot re-fetch"
  claim that US1 rewords in `NodeActionTools.kt` and `UtilityTools.kt`.
- Privacy constraint (repository agent rules, section 7, privacy detection table):
  `ContextExtractor.computeNearestLabels` uses every node of a window, off-screen ones included, and `WindowPacker`
  packs a batch of segments into shared model windows. Every new filter
  (off-screen rows, `visible_only`, selector candidates) therefore runs AFTER `privacyToolGate.tree()` / `texts()`
  on the unchanged input, so redaction of the kept items is byte-identical to today. No file of the
  `MEASURED_DETECTION_RATES` list is touched: `make privacy-benchmark` is not triggered.
- Relay and phone clocks are not synchronized. Cross-host comparisons use durations; the wall-clock fields are
  labelled skew-dependent. The relay sits behind the TLS proxy, so client-to-proxy time is invisible to it.
- Quality gates: Gradle lint/test/build results are read from CI with `gh`, valid only under decision G2. `act` is
  not installed.
- Plan-wide code constraints (stated once here, not repeated per task): no new `@Suppress` anywhere; every Kotlin line
  ≤ 120 characters (ktlint allows 140, detekt 120: use block bodies instead of 121-140 character expression bodies);
  no Mermaid diagram is changed (if one ever is, validate it with `npx -y -p @mermaid-js/mermaid-cli mmdc`).
- Line endings (`core.autocrlf=true`, `git ls-files --eol`): Kotlin and docs working-tree files are CRLF, except the
  Plan 66 files, which are LF like everything under `relay/` (`services/tunnel/*.kt`, their tests, `RelaySettings*.kt`;
  `RelaySession.kt` and `RelayTunnelProviderTest.kt` among the files touched here). Keep each file's existing line
  endings: edit with the Edit tool, never `sed` with `\n`.
- Line numbers below refer to `feat/websocket-relay-provider` HEAD `1e820e0`. Locate each edit by its anchor text
  when the numbers drift.
- Every behaviour choice below implements the proposed default of the "Pending decisions" list at the end of this
  plan. Implementation MUST NOT start until every entry of that list is marked confirmed by the user (or replaced by
  the chosen alternative, with the affected actions amended in this plan).

**Prerequisites:**

- US4 touches `relay/` and `services/tunnel/Relay*.kt`, which exist only on PR #3 (Plan 66, branch
  `feat/websocket-relay-provider`). PR #3 must be merged into `main` first. Check:
  `git fetch origin && git ls-tree origin/main relay/src/relay.js app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/RelaySession.kt`.
  Empty output: STOP and ask the user (decision G1).
- All work branches from the latest `main`.
- Before the first code change, with the pre-plan debug build still installed on the phone: run Manual Test M1
  (baseline) and keep its table for the PR body.

**Scope:** exactly US1-US4 below.

**Out of scope (deferred until US4 measurements exist):**

- Event-driven waits (`wait_for_node` / `wait_for_idle` reacting to accessibility events instead of re-reading).
- Actions reading only the focused/application window instead of every window.
- Fast text input through `ACTION_SET_TEXT` (`setTextOnNode` exists but no tool uses it).
- Scroll without fling.
- Caching of the privacy settings per call.
- Transport changes: response compression, keep-warm pings while in use, VPS placement, faster reconnect on network
  change.
- Selector form for `long_click_node` and `scroll_to_node`.
- The `wait_for_node` double walk on success (`rawNodeExists`, then `getFreshWindows`).

**Scope boundary — files this plan may touch (and NO others):**

`.../` = `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp`, `.../test/` =
`app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp`.

- US1: `.../services/accessibility/AccessibilityTreeParser.kt`, `.../services/accessibility/AccessibilityTreeLock.kt`
  (KDoc), `.../mcp/tools/NodeActionTools.kt`, `.../mcp/tools/UtilityTools.kt`,
  `.../test/services/accessibility/AccessibilityTreeParserTest.kt`, `.../test/mcp/tools/NodeActionToolsTest.kt`,
  `.../test/mcp/tools/UtilityToolsTest.kt`, `docs/PROJECT.md`
- US2: `.../services/accessibility/CompactFormatOptions.kt` (new), `.../services/accessibility/CompactTreeFormatter.kt`,
  `.../services/accessibility/ScreenStateSnapshotCache.kt`, `.../mcp/tools/ScreenIntrospectionTools.kt`,
  `.../mcp/tools/NodeActionTools.kt`, `.../test/services/accessibility/PaginationTestTrees.kt`,
  `.../test/services/accessibility/CompactTreeFormatterTest.kt`, `.../test/mcp/tools/ScreenIntrospectionToolsTest.kt`,
  `.../test/mcp/tools/NodeActionToolsTest.kt`, `.../test/mcp/tools/McpToolUtilsTest.kt`,
  `.../test/integration/ScreenIntrospectionIntegrationTest.kt`,
  `.../test/integration/NodeActionIntegrationTest.kt`, `docs/MCP_TOOLS.md`, `docs/PROJECT.md`
- US3: `.../mcp/tools/McpToolUtils.kt`, `.../mcp/tools/NodeSelectorResolver.kt` (new), `.../mcp/tools/NodeActionTools.kt`,
  `.../test/mcp/tools/McpToolUtilsTest.kt`, `.../test/mcp/tools/NodeSelectorResolverTest.kt` (new),
  `.../test/mcp/tools/NodeActionToolsTest.kt`, `.../test/integration/NodeActionIntegrationTest.kt`,
  `.../test/integration/PrivacyModeIntegrationTest.kt`,
  `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/E2ECalculatorTest.kt`, `docs/MCP_TOOLS.md`,
  `docs/PROJECT.md`
- US4: `relay/src/requestLog.js` (new), `relay/src/relay.js`, `relay/package.json`, `relay/README.md`,
  `relay/test/helpers.js`, `relay/test/requestLog.test.js` (new), `relay/test/relay.test.js`,
  `.../services/tunnel/RelayTiming.kt` (new), `.../services/tunnel/RelaySession.kt`,
  `.../test/services/tunnel/RelayTimingTest.kt` (new), `.../test/services/tunnel/RelayTunnelProviderTest.kt`,
  `docs/PROJECT.md`

Explicitly unchanged (stated once here, not repeated per story or task): `ActionExecutor.kt`, `ActionExecutorImpl.kt`,
`ElementFinder.kt`, `McpServerService`, `McpIntegrationTestHelper.kt`, `RelayFrames.kt`, `relay/src/frames.js`,
`relay/src/config.js`, `LocalRequestReplayer.kt`, `RelayTunnelProvider.kt`, `docs/ARCHITECTURE.md`, root `README.md`,
every file in `privacy/`. Each story touches only the files of its own list (e.g. `docs/MCP_TOOLS.md` is not changed
by US1 or US4). Signatures that stay: `registerNodeActionTools`, `getFreshWindows`,
`RelaySession`'s constructor, `formatMultiWindowPage`.

**Plan document handling:** this file is a tracked, PERMANENT artifact committed alone as `docs: add plan 67`. It is
the ONLY file outside the list above that the PR adds. Do not touch any other file in `docs/plans/`.

**Branch:** `feat/mcp-tool-latency-optimization`, created from the latest `main` only after the prerequisite check
passes. The untracked `.lifecycle/` is never staged.

**PR title:** `Plan 67: MCP tool latency optimization`.

**Suggested commits** (each compiles on its own):

| Story | Commit |
|-------|--------|
| — | `docs: add plan 67` |
| US1 | `refactor: drop redundant node refresh after the framework cache clear` (Tasks 1.1-1.3, code + tests) |
| US1 | `docs: document the tree-read freshness contract` |
| US2 | `feat: add output options to get_screen_state` (Tasks 2.1-2.3 + their tests) |
| US2 | `feat: add visible_only to find_nodes` (Task 2.4 + its tests) |
| US2 | `docs: document get_screen_state output options and pagination` |
| US3 | `feat: add an untrusted error result helper` (Task 3.1) |
| US3 | `feat: resolve click_node and tap_node targets by selector` (Tasks 3.2-3.4) |
| US3 | `test: add a selector-based calculator e2e flow` (Task 3.5) |
| US3 | `docs: document selector targets for click_node and tap_node` |
| US4 | `feat: log per-request timing in the relay` (Tasks 4.1-4.3) |
| US4 | `feat: log relayed request timing on the device` (Tasks 4.4-4.5) |
| US4 | `docs: document the relay request timing log` |

---

## User Story 1 — Faster tree reads

**Why:** every fresh read already clears the framework node cache inside `AccessibilityTreeLock`, so each node comes
back live; the per-node `refresh()` after it adds one blocking IPC per node.

**Acceptance criteria:**
- [x] `git grep -nE '^[[:space:]]*[^/[:space:]*].*\.refresh\(\)' app/src/main` returns exactly one hit:
      `ActionExecutorImpl.kt` (`if (cached.node.refresh()) {`).
- [x] `getFreshWindows`, `rawNodeExists`, `generateCurrentFingerprint` and `findFocusedEditableNode` still call
      `clearFrameworkNodeCache()` first; the existing `verifyOrder` tests and `ActionExecutorImplTest` (S1 refresh
      tests) are not modified.

### Task 1.1 — Remove per-node refresh from the tree parser

**Action 1 — modify** `.../services/accessibility/AccessibilityTreeParser.kt`: class KDoc (lines 19-22).

```diff
  * The root node passed to [parseTree] is NOT recycled by the parser — the caller retains
  * ownership. When [nodeMap] is provided, the root IS stored in the map.
  *
+ * Freshness contract: callers MUST clear the framework node cache
+ * ([AccessibilityServiceProvider.clearFrameworkNodeCache]) inside the same [AccessibilityTreeLock]
+ * critical section immediately before [parseTree]. Every node returned by `getChild` is then
+ * fetched live (or prefetched) by this read, so the parser performs no per-node `refresh()`
+ * (one would add a blocking IPC to the target app's UI thread per node).
+ *
  * This class is Hilt-injectable and stateless (no injected cache, no internal mutable state).
```

**Action 2 — modify** same file: child loop in `parseNode` (lines 153-178).

```diff
             for (i in 0 until childCount) {
                 val childNode = node.getChild(i)
                 if (childNode != null) {
-                    // Refresh nodes that are likely virtual (Compose, WebView, custom
-                    // AccessibilityNodeProvider implementations). Virtual nodes can
-                    // return stale cached data after recomposition/DOM changes.
-                    // - No viewIdResourceName: virtual nodes typically lack resource IDs
-                    // - Compose extra data key: definitive marker for Compose virtual nodes
-                    // Nodes with a resource ID are real Views and don't need refresh.
-                    if (childNode.viewIdResourceName == null ||
-                        childNode.availableExtraData.contains(COMPOSE_SEMANTICS_ID_KEY)
-                    ) {
-                        childNode.refresh()
-                    }
                     children.add(
```

**Action 3 — modify** same file: comment at lines 203-205.

```diff
             // Store real node reference + metadata in cache map (if caching is active).
             // Metadata (depth, index, parentId) is needed by ActionExecutorImpl to
-            // re-verify node identity after refresh() (S1 fix).
+            // re-verify node identity after its refresh() in ActionExecutorImpl.performNodeAction (S1 fix).
             nodeMap?.put(nodeId, CachedNode(node, depth, index, parentId))
```

`COMPOSE_SEMANTICS_ID_KEY` stays in this task: `UtilityTools.kt:487` and `UtilityToolsTest.kt:427` still use it until
Task 1.3, which deletes it in its last action.

**Action 4 — modify** `.../test/services/accessibility/AccessibilityTreeParserTest.kt`: add tests.

**Setup**: existing relaxed `createMockNode` helper. Compose child: `every { child.availableExtraData } returns
listOf("androidx.compose.ui.semantics.id")` plus a non-null `resourceId`. File style: `@DisplayName` + camelCase.
New import: `org.junit.jupiter.api.Assertions.assertSame` (`verify` is already imported).

| Group | Test (`@DisplayName`) | Verifies |
|-------|-----------------------|----------|
| `ParseTree` | `does not refresh child nodes without a resource id` | Child with `resourceId = null` parsed; `verify(exactly = 0) { child.refresh() }` |
| `ParseTree` | `does not refresh Compose virtual child nodes` | Child with the Compose extra key parsed; `verify(exactly = 0) { child.refresh() }` |
| `ParseTreeWithNodeMap` | `does not refresh children and caches the getChild instance` | Non-null `nodeMap`: no child `refresh()`; the child's `CachedNode.node` is `assertSame` to the `getChild(0)` instance |

**Definition of Done:**
- [x] `parseNode` has no `refresh()` call.
- [x] The class KDoc states the freshness contract; the S1 comment names `ActionExecutorImpl.performNodeAction`.
- [x] The 3 parser guard tests are added (no root-refresh row: `parseTree` never refreshed the root, so such a row
      would pass before and after the change).

### Task 1.2 — Remove root refresh from `getFreshWindows`

**Action 1 — modify** `.../mcp/tools/NodeActionTools.kt`: comment before the clear (lines 671-679). The removed clause
contradicts AOSP (`AccessibilityNodeInfo.refresh()` bypasses the cache) and the freshness contract below.

```diff
     // Drop the framework's accessibility node cache before reading. Chromium WebView
     // suppresses/throttles the content-changed events that invalidate that cache, so a JavaScript
-    // DOM change (e.g. updated text) would otherwise read back stale — and node.refresh() cannot
-    // re-fetch what the cache still serves as current. Clearing here makes get_screen_state,
-    // find_nodes, and the action tools' re-reads (which all go through getFreshWindows) round-trip
-    // live and populate the node cache with live nodes. The lightweight wait_for_node/wait_for_idle
-    // poll paths do not go through here and clear the cache themselves (see UtilityTools). See
-    // AccessibilityServiceProvider.clearFrameworkNodeCache.
+    // DOM change (e.g. updated text) would otherwise be served stale from the framework cache. Clearing
+    // here makes get_screen_state, find_nodes, and the action tools' re-reads (which all go through
+    // getFreshWindows) round-trip live and populate the node cache with live nodes. The lightweight
+    // wait_for_node/wait_for_idle poll paths do not go through here and clear the cache themselves
+    // (see UtilityTools). See AccessibilityServiceProvider.clearFrameworkNodeCache. Nodes read after
+    // this clear are fetched live: window.root bypasses the cache, and getChild returns nodes fetched
+    // or prefetched by this same read. The traversal therefore performs no per-node refresh().
     accessibilityServiceProvider.clearFrameworkNodeCache()
```

**Action 2 — modify** same file: multi-window loop (lines 696-705).

```diff
                 val rootNode = window.root ?: continue

-                // Force the accessibility framework to re-query the underlying
-                // AccessibilityNodeProvider (e.g., Compose's virtual node provider).
-                // Without this, window.root can return stale cached snapshots —
-                // particularly problematic for Jetpack Compose apps where
-                // TYPE_WINDOW_CONTENT_CHANGED events may be throttled or missed.
-                rootNode.refresh()
-
                 // Extract metadata BEFORE parsing
```

**Action 3 — modify** same file: fallback path (lines 769-774).

```diff
                     "The screen may be transitioning.",
             )

-    // Force fresh data from the accessibility framework (see multi-window path comment).
-    rootNode.refresh()
-
     // Extract metadata from the root node before recycling.
```

**Action 4 — modify** `.../test/mcp/tools/NodeActionToolsTest.kt`: add `import io.mockk.verify`; add top-level tests
after `getFreshWindowsSerializesConcurrentReads`, same `@DisplayName` + camelCase style.

**Setup**: call `getFreshWindows(mockTreeParser, mockAccessibilityServiceProvider, mockNodeCache)` directly. The
fallback test also stubs `getAccessibilityWindows()` → `emptyList()`, `getRootNode()` → `mockRootNode`,
`mockRootNode.windowId` → `0`, `mockRootNode.window` → `null` (strict mocks).

| Test (`@DisplayName`) | Verifies |
|-----------------------|----------|
| `getFreshWindows does not refresh window root nodes` | `verify(exactly = 0) { mockRootNode.refresh() }`; `verifyOrder { clearFrameworkNodeCache(); getAccessibilityWindows(); mockTreeParser.parseTree(mockRootNode, "root_w0", any()) }` |
| `getFreshWindows fallback does not refresh the active root node` | `verify(exactly = 0) { mockRootNode.refresh() }`; `verifyOrder { clearFrameworkNodeCache(); getRootNode(); mockTreeParser.parseTree(mockRootNode, "root_w0", any()) }` |

**Definition of Done:**
- [x] `getFreshWindowsLocked` has no `refresh()` call and the clear comment states why.
- [x] The 2 guard tests are added. Existing `every { mockRootNode.refresh() }` stubs stay untouched (D1.3).

### Task 1.3 — Remove refresh from the `wait_for_node` raw walk; KDoc and docs

**Action 1 — modify** `.../mcp/tools/UtilityTools.kt`: `rawNodeExists` KDoc (lines 366-368).

```diff
- * Refreshes root nodes and virtual/Compose child nodes using the same logic as
- * [AccessibilityTreeParser.parseNode] to ensure fresh data from Jetpack Compose
- * and other virtual node providers.
+ * Clears the framework node cache before the walk, so every node read is fetched live; no
+ * per-node refresh() is performed (same freshness contract as [AccessibilityTreeParser]).
```

**Action 2 — modify** same file: `rawNodeExistsLocked` (comment at lines 408-412, refreshes at lines 421 and 443).

```diff
     // Drop the framework's accessibility node cache before this poll read. Chromium WebView
     // suppresses/throttles the content-changed events that invalidate that cache, so a node added
-    // by a JavaScript DOM change would otherwise never appear here (refresh() cannot re-fetch what
-    // the cache still serves as current) and wait_for_node would time out on present content. See
+    // by a JavaScript DOM change would otherwise never appear here (stale data would be served from
+    // the framework cache) and wait_for_node would time out on present content. See
     // AccessibilityServiceProvider.clearFrameworkNodeCache.
```

```diff
             for (window in accessibilityWindows) {
                 val rootNode = window.root ?: continue
-                rootNode.refresh()
                 if (rawNodeMatchesRecursive(rootNode, findBy, value, 0)) {
```

```diff
                     "The screen may be transitioning.",
             )
-    rootNode.refresh()
     return rawNodeMatchesRecursive(rootNode, findBy, value, 0)
```

**Action 3 — modify** same file: `rawNodeMatchesRecursive` KDoc (lines 448-450) and child loop (lines 482-495).

```diff
  * Recursively walks a raw [AccessibilityNodeInfo] tree checking for a match.
- * Returns true immediately on first match (early exit). Refreshes virtual/Compose
- * child nodes before reading their properties.
+ * Returns true immediately on first match (early exit).
```

```diff
     for (i in 0 until childCount) {
         val child = node.getChild(i) ?: continue
-
-        // Refresh virtual/Compose nodes — same logic as AccessibilityTreeParser.parseNode
-        if (child.viewIdResourceName == null ||
-            child.availableExtraData.contains(AccessibilityTreeParser.COMPOSE_SEMANTICS_ID_KEY)
-        ) {
-            child.refresh()
-        }
-
         if (rawNodeMatchesRecursive(child, findBy, value, depth + 1)) {
```

The `AccessibilityTreeParser` import of `UtilityTools.kt` stays: `AccessibilityTreeParser.MAX_TREE_DEPTH` (line 476)
still uses it.

**Action 4 — modify** `.../services/accessibility/AccessibilityTreeLock.kt`: line 8.

```diff
- * it (`getWindows`/`getRootInActiveWindow` + `getChild`/`refresh`) to repopulate live nodes. Without
+ * it (`getWindows`/`getRootInActiveWindow` + `getChild`) to repopulate live nodes. Without
```

**Action 5 — modify** `docs/PROJECT.md`, section "AccessibilityService Best Practices": insert after the bullet
``- Check `node.refresh()` before using cached nodes (stale detection)`` (which stays).

```diff
 - Check `node.refresh()` before using cached nodes (stale detection)
+- Fresh tree reads (`getFreshWindows`, `rawNodeExists`, the wait_for_idle fingerprint, `findFocusedEditableNode`) clear the framework node cache via `AccessibilityServiceProvider.clearFrameworkNodeCache()` inside `AccessibilityTreeLock` before traversing; traversals MUST NOT call per-node `refresh()` (one blocking IPC per node on the target app's UI thread). `refresh()` is used only to validate nodes taken from `AccessibilityNodeCache` (ActionExecutorImpl S1 check)
 - All node operations MUST happen on main thread
```

**Action 6 — modify** `.../test/mcp/tools/UtilityToolsTest.kt` (`WaitForNodeToolTests`):
- Add `private const val COMPOSE_SEMANTICS_KEY = "androidx.compose.ui.semantics.id"` in a `companion object` of
  `UtilityToolsTest`; line 427 uses it instead of `AccessibilityTreeParser.COMPOSE_SEMANTICS_ID_KEY`.
- Rewrite `rawNodeExists refreshes virtual and Compose child nodes` (lines 406-451) in place, keeping its three strict
  children (null resource id; Compose key; real View). No test is deleted.

| Test | Verifies |
|------|----------|
| `rawNodeExists does not refresh child nodes after clearing the framework cache` (rewrite of line 407) | `verify(exactly = 0) { x.refresh() }` for `virtualChild`, `composeChild`, `realChild`; `verifyOrder { clearFrameworkNodeCache(); getAccessibilityWindows() }` |
| `rawNodeExists does not refresh window root nodes` (new) | Multi-window path: `verify(exactly = 0) { mockRootNode.refresh() }` |
| `rawNodeExists falls back to single root node when no windows available` (extend, lines 540-558) | Adds `verify(exactly = 0) { fallbackRoot.refresh() }` and `verifyOrder { clearFrameworkNodeCache(); getRootNode() }` |

**Action 7 — modify** `.../services/accessibility/AccessibilityTreeParser.kt`: delete the now-unused constant (lines
269-274) (D1.2). Runs last: Actions 3 and 6 removed its two remaining users.

```diff
             private const val EXTRA_KEY_TARGET_URL = "AccessibilityNodeInfo.targetUrl"

-            /**
-             * Extra data key added by Compose's AccessibilityNodeProvider to every
-             * virtual accessibility node. Present in [AccessibilityNodeInfo.getAvailableExtraData].
-             */
-            internal const val COMPOSE_SEMANTICS_ID_KEY = "androidx.compose.ui.semantics.id"
-
             /** Maps [AccessibilityWindowInfo] type constants to human-readable labels. */
```

**Definition of Done:**
- [x] `rawNodeExistsLocked` and `rawNodeMatchesRecursive` have no `refresh()` call.
- [x] `git grep -n COMPOSE_SEMANTICS_ID_KEY app/src` returns no hits.
- [x] KDoc of `rawNodeExists`, `rawNodeMatchesRecursive` and `AccessibilityTreeLock` no longer mention refresh in
      traversals; the `rawNodeExistsLocked` comment no longer claims `refresh()` cannot re-fetch; the
      `docs/PROJECT.md` bullet is added.
- [x] `UtilityToolsTest` has 1 rewritten, 1 extended and 1 new test; no new import (`verify`, `verifyOrder` exist;
      the `AccessibilityTreeParser` import stays for `mockTreeParser`).

---

## User Story 2 — Slimmer get_screen_state and find_nodes output

**Why:** agents that only need what is on screen pay for off-screen rows and the hierarchy on every page; opt-in
options remove them while the default output stays unchanged. The full tree is still parsed, merged and redacted, so
the saving is output size, not read time.

**Definition (binding for every task):** an off-screen node is an `AccessibilityNodeData` (or `ElementInfo`) with
`visible == false`, i.e. `AccessibilityNodeInfo.isVisibleToUser` at parse time (flag `off`). Bounds are not consulted;
a row clipped at the screen edge is on-screen.

**Acceptance criteria:**
- [x] `get_screen_state` accepts `include_offscreen` and `include_hierarchy` (boolean, default `true`) (D2.1).
- [x] Without the options, the output is byte-identical to the pre-plan output for a fresh single page, a fresh paged
      capture and every cursor page.
- [x] `include_offscreen=false` omits every `visible == false` node from TSV rows and hierarchy; on-screen
      descendants of an omitted node are promoted to the nearest emitted ancestor's depth (as structural-only nodes
      are today); `NOTE_LINE_OFFSCREEN_HINT` is replaced by `NOTE_LINE_OFFSCREEN_OMITTED` (D2.3).
- [x] `include_hierarchy=false` prints no `hierarchy:` header or lines in any window, single-page and paged (D2.4).
- [x] Page count and `nodes:a-b/total` count emitted rows: `countKeptNodes(result, options)` equals the TSV row count
      for all 4 option combinations.
- [x] A cursor call renders with the options stored in its snapshot; option params on a cursor call are validated
      and otherwise ignored (D2.2).
- [x] `find_nodes` accepts `visible_only` (boolean, default `false`); when `true` only `visible == true` matches are
      returned (D2.8; filter position per Context, privacy constraint).
- [x] A non-boolean value (string, number, JSON null, object) for any of the three params returns
      `CallToolResult(isError = true)` via `McpToolUtils.optionalBoolean` (D2.5).
- [x] Unchanged with any option values: screenshot annotation, `privacyToolGate.tree` / `texts` input (the full
      merged tree / full match list), node-cache population, `include_screenshot` and `exact_match` parsing, settings
      UI (D2.7).
- [x] Schemas expose the three params with defaults; `docs/MCP_TOOLS.md` and `docs/PROJECT.md` are updated.

### Task 2.1 — Output options model and snapshot field

**Action 1 — create** `.../services/accessibility/CompactFormatOptions.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.accessibility

/**
 * Output-shaping options for get_screen_state (Plan 67). The defaults reproduce the pre-Plan-67
 * output byte-for-byte.
 *
 * @property includeOffscreen When false, nodes with `visible == false` are not emitted (no TSV row,
 *   no hierarchy line); their emitted descendants are promoted like children of structural nodes.
 * @property includeHierarchy When false, no `hierarchy:` section is emitted for any window.
 */
data class CompactFormatOptions(
    val includeOffscreen: Boolean = true,
    val includeHierarchy: Boolean = true,
)
```

**Action 2 — modify** `.../services/accessibility/ScreenStateSnapshotCache.kt`. The new field is last and defaulted,
so existing positional constructor calls compile.

```diff
  * @property totalKeptNodes Total number of kept TSV nodes across all windows.
  * @property totalPages Number of pages (ceil(totalKeptNodes / PAGE_SIZE)).
+ * @property options Output options of the capture that created this snapshot; every cursor page
+ *   of this snapshot is rendered with them.
  */
 data class ScreenStateSnapshot(
     val id: String,
     val result: MultiWindowResult,
     val screenInfo: ScreenInfo,
     val totalKeptNodes: Int,
     val totalPages: Int,
+    val options: CompactFormatOptions = CompactFormatOptions(),
 )
```

**Definition of Done:**
- [x] `CompactFormatOptions` exists with both defaults `true`; `ScreenStateSnapshot.options` is the 6th and last
      constructor param, defaulted.

### Task 2.2 — Formatter honours options through one emission predicate

All edits in `.../services/accessibility/CompactTreeFormatter.kt`.

**Action 1 — modify** companion object: add after `NOTE_LINE_OFFSCREEN_HINT` (lines 330-331).

```diff
             const val NOTE_LINE_OFFSCREEN_HINT =
                 "note:offscreen items require scroll_to_node before interaction"
+            const val NOTE_LINE_OFFSCREEN_OMITTED =
+                "note:offscreen nodes are omitted (include_offscreen=false); scroll, or call without " +
+                    "include_offscreen=false, to see them"
```

**Action 2 — modify**: insert between the class's closing `}` (line 347) and the `// Pagination (Plan 47)` banner.

```kotlin
// ─────────────────────────────────────────────────────────────────────────────
// Output options (Plan 67)
//
// EVERY row/count/closure/hierarchy walker MUST use isEmitted so that page totals, TSV rows and
// hierarchy lines stay consistent.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * A node is emitted (TSV row + hierarchy line) iff it passes the keep filter and, when off-screen
 * nodes are excluded, is visible.
 */
private fun CompactTreeFormatter.isEmitted(
    node: AccessibilityNodeData,
    options: CompactFormatOptions,
): Boolean = shouldKeepNode(node) && (options.includeOffscreen || node.visible)

/** Global note lines shared by [CompactTreeFormatter.formatMultiWindow] and [formatMultiWindowPage]. */
private fun appendGlobalNotes(
    sb: StringBuilder,
    degraded: Boolean,
    options: CompactFormatOptions,
) {
    if (degraded) sb.appendLine(CompactTreeFormatter.DEGRADATION_NOTE)
    sb.appendLine(CompactTreeFormatter.NOTE_LINE)
    sb.appendLine(CompactTreeFormatter.NOTE_LINE_CUSTOM_ELEMENTS)
    sb.appendLine(CompactTreeFormatter.NOTE_LINE_FLAGS_LEGEND)
    sb.appendLine(
        if (options.includeOffscreen) {
            CompactTreeFormatter.NOTE_LINE_OFFSCREEN_HINT
        } else {
            CompactTreeFormatter.NOTE_LINE_OFFSCREEN_OMITTED
        },
    )
}
```

**Action 3 — modify** `walkTree` (lines 91-106). `format()` (single window) keeps calling it with the default.

```diff
         private fun walkTree(
             node: AccessibilityNodeData,
             depth: Int = 0,
             visitors: List<(node: AccessibilityNodeData, depth: Int) -> Unit>,
+            options: CompactFormatOptions = CompactFormatOptions(),
         ) {
-            val isKept = shouldKeepNode(node)
+            val isKept = isEmitted(node, options)
             if (isKept) {
                 for (visitor in visitors) {
                     visitor(node, depth)
                 }
             }
             val childDepth = if (isKept) depth + 1 else depth
             for (child in node.children) {
-                walkTree(child, childDepth, visitors)
+                walkTree(child, childDepth, visitors, options)
             }
         }
```

**Action 4 — modify**: replace `formatMultiWindow` and its KDoc (lines 130-180).

```kotlin
        /**
         * Formats multi-window screen state as a compact string.
         *
         * Each window gets its own section with a header line containing window metadata,
         * followed by a TSV column header and element rows, then (when
         * [CompactFormatOptions.includeHierarchy]) its `hierarchy:` block. [options] defaults
         * reproduce the legacy output.
         */
        fun formatMultiWindow(
            result: MultiWindowResult,
            screenInfo: ScreenInfo,
            options: CompactFormatOptions = CompactFormatOptions(),
        ): String {
            val sb = StringBuilder()

            // Degradation note (if applicable) + global note lines
            appendGlobalNotes(sb, result.degraded, options)

            // Screen info (global)
            sb.appendLine(
                "screen:${screenInfo.width}x${screenInfo.height} " +
                    "density:${screenInfo.densityDpi} orientation:${screenInfo.orientation}",
            )

            // Per-window sections
            for (windowData in result.windows) {
                sb.appendLine(buildWindowHeader(windowData))
                sb.appendLine(HEADER)
                val hierarchySb = StringBuilder()
                val rowVisitor: (AccessibilityNodeData, Int) -> Unit = { node, _ -> appendElementRow(sb, node) }
                val hierarchyVisitor: (AccessibilityNodeData, Int) -> Unit = { node, depth ->
                    repeat(depth) { hierarchySb.append(HIERARCHY_INDENT) }
                    hierarchySb.appendLine(node.id)
                }
                val visitors =
                    if (options.includeHierarchy) listOf(rowVisitor, hierarchyVisitor) else listOf(rowVisitor)
                walkTree(windowData.tree, visitors = visitors, options = options)
                if (options.includeHierarchy) {
                    sb.appendLine(HIERARCHY_HEADER)
                    sb.append(hierarchySb)
                }
            }

            return sb.toString().trimEnd('\n')
        }
```

**Action 5 — modify** `countKeptNodes` (lines 357-365).

```diff
-/** Total number of kept nodes across all windows (equals the number of TSV rows emitted). */
-internal fun CompactTreeFormatter.countKeptNodes(result: MultiWindowResult): Int {
+/** Total number of emitted nodes across all windows for [options] (equals the number of TSV rows emitted). */
+internal fun CompactTreeFormatter.countKeptNodes(
+    result: MultiWindowResult,
+    options: CompactFormatOptions = CompactFormatOptions(),
+): Int {
     fun count(node: AccessibilityNodeData): Int {
-        var c = if (shouldKeepNode(node)) 1 else 0
+        var c = if (isEmitted(node, options)) 1 else 0
         for (child in node.children) c += count(child)
         return c
     }
     return result.windows.sumOf { count(it.tree) }
 }
```

**Action 6 — modify** `collectKeptNodes` (lines 434-447).

```diff
-/** Pre-order list of kept nodes (same order/filter as walkTree's TSV emission). */
+/** Pre-order list of emitted nodes (same order/filter as walkTree's TSV emission for [options]). */
 private fun collectKeptNodes(
     formatter: CompactTreeFormatter,
     root: AccessibilityNodeData,
+    options: CompactFormatOptions,
 ): List<AccessibilityNodeData> {
     val out = ArrayList<AccessibilityNodeData>()

     fun walk(node: AccessibilityNodeData) {
-        if (formatter.shouldKeepNode(node)) out.add(node)
+        if (formatter.isEmitted(node, options)) out.add(node)
         for (child in node.children) walk(child)
     }
```

**Action 7 — modify**: replace `appendWindowPageSection` and its KDoc (lines 449-491). 5 params is the detekt
`LongParameterList` cap; do not add a 6th. Rows are written first so `include_hierarchy=false` skips the closure
work; with default options the output is identical because `covers` never writes to `sb`.

```kotlin
/**
 * Emits a window section for [pageNodes]: window header, TSV rows and — when
 * [CompactFormatOptions.includeHierarchy] — a `hierarchy:` block holding those rows PLUS their
 * emitted ancestors (emitted-ancestor closure), at the same emitted-depth indentation as
 * [CompactTreeFormatter.formatMultiWindow].
 */
private fun appendWindowPageSection(
    formatter: CompactTreeFormatter,
    sb: StringBuilder,
    windowData: WindowData,
    pageNodes: List<AccessibilityNodeData>,
    options: CompactFormatOptions,
) {
    sb.appendLine(formatter.buildWindowHeader(windowData))
    sb.appendLine(CompactTreeFormatter.HEADER)
    for (node in pageNodes) formatter.appendElementRow(sb, node)
    if (!options.includeHierarchy) return

    val pageNodeIds = pageNodes.mapTo(HashSet()) { it.id }
    val closure = HashSet<String>()

    fun covers(node: AccessibilityNodeData): Boolean {
        var subtreeCovers = node.id in pageNodeIds
        for (child in node.children) {
            if (covers(child)) subtreeCovers = true
        }
        if (subtreeCovers && formatter.isEmitted(node, options)) closure.add(node.id)
        return subtreeCovers
    }
    covers(windowData.tree)

    sb.appendLine(CompactTreeFormatter.HIERARCHY_HEADER)

    fun appendHierarchy(
        node: AccessibilityNodeData,
        depth: Int,
    ) {
        val isKept = formatter.isEmitted(node, options)
        if (isKept && node.id in closure) {
            repeat(depth) { sb.append(CompactTreeFormatter.HIERARCHY_INDENT) }
            sb.appendLine(node.id)
        }
        val childDepth = if (isKept) depth + 1 else depth
        for (child in node.children) appendHierarchy(child, childDepth)
    }
    appendHierarchy(windowData.tree, 0)
}
```

**Action 8 — modify** `formatMultiWindowPage` (lines 382-411).

```diff
     val result = snapshot.result
     val screenInfo = snapshot.screenInfo
+    val options = snapshot.options
     val startIndex = (page - 1) * CompactTreeFormatter.PAGE_SIZE
     val endIndexExclusive = minOf(page * CompactTreeFormatter.PAGE_SIZE, snapshot.totalKeptNodes)
-    val perWindowKept = result.windows.map { collectKeptNodes(this, it.tree) }
+    val perWindowKept = result.windows.map { collectKeptNodes(this, it.tree, options) }

     val sb = StringBuilder()
-    if (result.degraded) sb.appendLine(CompactTreeFormatter.DEGRADATION_NOTE)
-    sb.appendLine(CompactTreeFormatter.NOTE_LINE)
-    sb.appendLine(CompactTreeFormatter.NOTE_LINE_CUSTOM_ELEMENTS)
-    sb.appendLine(CompactTreeFormatter.NOTE_LINE_FLAGS_LEGEND)
-    sb.appendLine(CompactTreeFormatter.NOTE_LINE_OFFSCREEN_HINT)
+    appendGlobalNotes(sb, result.degraded, options)
     sb.appendLine(
@@
-            appendWindowPageSection(this, sb, windowData, pageNodes)
+            appendWindowPageSection(this, sb, windowData, pageNodes, options)
```

In its KDoc, replace "kept-ancestors (kept-ancestor closure), preserving today's kept-depth indentation." with
"emitted ancestors (emitted-ancestor closure), preserving the emitted-depth indentation; rendered with
[ScreenStateSnapshot.options]."

**Definition of Done:**
- [x] Outside `isEmitted`, no walker in this file calls `shouldKeepNode(`.
- [x] `CompactTreeFormatter`'s member function count is unchanged (10): new logic is top-level or extension
      functions (detekt `TooManyFunctions`).
- [x] `appendGlobalNotes` with default options prints exactly the pre-plan note lines in the same order.

### Task 2.3 — get_screen_state params

All edits in `.../mcp/tools/ScreenIntrospectionTools.kt`.

**Action 1 — modify** imports (alphabetical).

```diff
 import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
+import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactFormatOptions
 import com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter
```

**Action 2 — modify** companion object (after `CURSOR_RADIX`).

```diff
             internal const val CURSOR_RADIX = 36
+            internal const val PARAM_INCLUDE_OFFSCREEN = "include_offscreen"
+            internal const val PARAM_INCLUDE_HIERARCHY = "include_hierarchy"
```

**Action 3 — modify**: insert immediately before the `parseCursor` KDoc (line 400). The `include_screenshot` and
`cursor` blocks are copied verbatim from `register` (lines 298-321); Action 7 then removes them from `register`.

```kotlin
/** get_screen_state input-schema properties (kept out of [GetScreenStateHandler.register] for length). */
private fun buildGetScreenStateProperties(includeScreenshotParamEnabled: Boolean): JsonObject =
    buildJsonObject {
        if (includeScreenshotParamEnabled) {
            putJsonObject("include_screenshot") {
                put("type", "boolean")
                put(
                    "description",
                    "Include a low-resolution screenshot. " +
                        "Only request when the UI node list is not sufficient.",
                )
                put("default", false)
            }
        }
        putJsonObject("cursor") {
            put("type", "string")
            put(
                "description",
                "Pagination cursor from a previous response (format " +
                    "\"<id>.<page>\"). Omit to capture a fresh screen state " +
                    "starting at page 1. A cursor is tied to one screen " +
                    "snapshot; if the screen changed you will be told to " +
                    "request a fresh one.",
            )
        }
        putJsonObject(GetScreenStateHandler.PARAM_INCLUDE_OFFSCREEN) {
            put("type", "boolean")
            put(
                "description",
                "Include nodes not visible to the user (flag 'off': hidden pager pages, prefetched " +
                    "list rows). Set false for a smaller response with on-screen nodes only. Applies " +
                    "to fresh captures only; cursor pages reuse the value of the capture that issued " +
                    "the cursor.",
            )
            put("default", true)
        }
        putJsonObject(GetScreenStateHandler.PARAM_INCLUDE_HIERARCHY) {
            put("type", "boolean")
            put(
                "description",
                "Include the 'hierarchy:' section (node nesting by indentation) for each window. " +
                    "Set false for a smaller response. Applies to fresh captures only; cursor pages " +
                    "reuse the value of the capture that issued the cursor.",
            )
            put("default", true)
        }
    }
```

The handler class is `GetScreenStateHandler` (`ScreenIntrospectionTools.kt:55`). The moved `include_screenshot` and
`cursor` blocks must be byte-identical to the current ones.

**Action 4 — modify** `buildFreshPageText` (lines 122-142); 5 params is the cap.

```diff
         private fun buildFreshPageText(
             result: MultiWindowResult,
             screenInfo: ScreenInfo,
             totalKept: Int,
             totalPages: Int,
+            options: CompactFormatOptions,
         ): String =
             if (totalPages <= 1) {
                 screenStateSnapshotCache.clear()
-                compactTreeFormatter.formatMultiWindow(result, screenInfo)
+                compactTreeFormatter.formatMultiWindow(result, screenInfo, options)
             } else {
                 val snapshot =
                     ScreenStateSnapshot(
                         System.currentTimeMillis().toString(CURSOR_RADIX),
                         result,
                         screenInfo,
                         totalKept,
                         totalPages,
+                        options,
                     )
```

`buildPagedText`, `buildScreenshotResult` and `collectOnScreenElements` do not change.

**Action 5 — modify** `handleFreshRequest` (lines 97-120).

```diff
-        private suspend fun handleFreshRequest(includeScreenshot: Boolean): CallToolResult {
+        private suspend fun handleFreshRequest(
+            includeScreenshot: Boolean,
+            options: CompactFormatOptions,
+        ): CallToolResult {
@@
-            val totalKept = compactTreeFormatter.countKeptNodes(result)
+            val totalKept = compactTreeFormatter.countKeptNodes(result, options)
             val totalPages = ceilDiv(totalKept, CompactTreeFormatter.PAGE_SIZE)
-            val compactOutput = buildFreshPageText(result, screenInfo, totalKept, totalPages)
-            Log.d(TAG, "get_screen_state: includeScreenshot=$includeScreenshot pages=$totalPages")
+            val compactOutput = buildFreshPageText(result, screenInfo, totalKept, totalPages, options)
+            Log.d(
+                TAG,
+                "get_screen_state: includeScreenshot=$includeScreenshot " +
+                    "includeOffscreen=${options.includeOffscreen} " +
+                    "includeHierarchy=${options.includeHierarchy} pages=$totalPages",
+            )
```

**Action 6 — modify** `execute` (lines 73-88).

```diff
         suspend fun execute(arguments: JsonObject?): CallToolResult {
             val includeScreenshot = parseIncludeScreenshot(arguments)
+            // Strictly validated on BOTH paths; a cursor call ignores the values (its page is rendered
+            // with the snapshot's options).
+            val options =
+                CompactFormatOptions(
+                    includeOffscreen = McpToolUtils.optionalBoolean(arguments, PARAM_INCLUDE_OFFSCREEN, true),
+                    includeHierarchy = McpToolUtils.optionalBoolean(arguments, PARAM_INCLUDE_HIERARCHY, true),
+                )
             val cursorElement = arguments?.get("cursor")
@@
             return if (isFresh) {
-                handleFreshRequest(includeScreenshot)
+                handleFreshRequest(includeScreenshot, options)
             } else {
```

**Action 7 — modify** `register`: description (line 290) and move the property building to a top-level function so
`register` stays under detekt `LongMethod` without a suppression.

```diff
-                        "Includes a hierarchy section showing node nesting via indentation. " +
+                        "Includes a hierarchy section showing node nesting via indentation " +
+                        "(omit it with include_hierarchy=false). " +
                         "Large screens are split into pages of 200 nodes: the response includes a " +
                         "'page:N/total' line and a cursor; call again with that cursor to fetch the " +
                         "next page. You do NOT need to fetch every page — stop once you have found " +
-                        "what you need. A screenshot can only be requested on page 1 (without a cursor).",
+                        "what you need. A screenshot can only be requested on page 1 (without a cursor). " +
+                        "Set include_offscreen=false to omit off-screen nodes and shrink the response.",
                 inputSchema =
                     ToolSchema(
-                        properties =
-                            buildJsonObject {
-                                ... (lines 298-321, content moved verbatim into Action 3) ...
-                            },
+                        properties = buildGetScreenStateProperties(includeScreenshotParamEnabled),
                         required = emptyList(),
                     ),
```

**Definition of Done:**
- [x] Schema property order (with `include_screenshot` enabled): `include_screenshot`, `cursor`,
      `include_offscreen`, `include_hierarchy`.
- [x] The cursor path is unchanged apart from the validation; it never reads the parsed `options`.

### Task 2.4 — find_nodes visible_only

All edits in `.../mcp/tools/NodeActionTools.kt` (`FindNodesTool`). `ElementFinder.findElements` is unchanged.

**Action 1 — modify** companion object.

```diff
         companion object {
             private const val TAG = "MCP:FindNodesTool"
             const val TOOL_NAME = "find_nodes"
+            internal const val PARAM_VISIBLE_ONLY = "visible_only"
         }
```

**Action 2 — modify** `execute` (lines 74-110).

```diff
             val exactMatch = arguments["exact_match"]?.jsonPrimitive?.booleanOrNull ?: false
+            val visibleOnly = McpToolUtils.optionalBoolean(arguments, PARAM_VISIBLE_ONLY, false)
@@
-            Log.d(TAG, "find_nodes: by=$byStr, found=${elements.size}")
+            Log.d(TAG, "find_nodes: by=$byStr, visibleOnly=$visibleOnly, found=${elements.size}")
@@
             val redactedElements =
                 elements.mapIndexed { index, element ->
                     element.copy(text = redactedFields[index * 2], contentDescription = redactedFields[index * 2 + 1])
                 }
+            // Filter AFTER redaction: the privacy pass keeps today's input (WindowPacker batches all segments).
+            val returnedElements = if (visibleOnly) redactedElements.filter { it.visible } else redactedElements
@@
-                            redactedElements.forEach { element ->
+                            returnedElements.forEach { element ->
```

**Action 3 — modify** `register` schema: add after the `exact_match` block.

```diff
                                 putJsonObject("exact_match") {
                                     ...
                                 }
+                                putJsonObject(PARAM_VISIBLE_ONLY) {
+                                    put("type", "boolean")
+                                    put("default", false)
+                                    put(
+                                        "description",
+                                        "If true, return only nodes visible to the user (visible=true); " +
+                                            "off-screen matches are omitted",
+                                    )
+                                }
```

**Definition of Done:**
- [x] The `find_nodes` schema lists `visible_only` right after `exact_match`; the debug line carries `visibleOnly`.

### Task 2.5 — Tests for US2

**Shared infra — modify** `.../test/services/accessibility/PaginationTestTrees.kt`: add inside
`object PaginationTestTrees`, after `keptNodeWindow`.

```kotlin
    /**
     * Same shape as [keptNodeWindow], but leaf `i` is off-screen (`visible = false`) when
     * `i % offscreenEvery == 0`. Ancestors stay on-screen. Emitted count with off-screen excluded =
     * [ancestorDepth] + leaves with `i % offscreenEvery != 0`.
     */
    fun mixedVisibilityWindow(
        keptLeafCount: Int,
        offscreenEvery: Int,
        ancestorDepth: Int = 2,
        windowId: Int = 1,
        packageName: String = "com.example.app",
    ): WindowData {
        require(offscreenEvery > 0) { "offscreenEvery must be > 0" }
        val base = keptNodeWindow(keptLeafCount, ancestorDepth, windowId, packageName)
        return base.copy(tree = markOffscreenLeaves(base.tree, offscreenEvery))
    }

    /** Deep copy of [node] with EVERY node off-screen (`visible = false`). */
    fun allOffscreen(node: AccessibilityNodeData): AccessibilityNodeData =
        node.copy(visible = false, children = node.children.map { allOffscreen(it) })

    private fun markOffscreenLeaves(
        node: AccessibilityNodeData,
        offscreenEvery: Int,
    ): AccessibilityNodeData =
        if (node.id.startsWith(LEAF_PREFIX)) {
            node.copy(visible = node.id.removePrefix(LEAF_PREFIX).toInt() % offscreenEvery != 0)
        } else {
            node.copy(children = node.children.map { markOffscreenLeaves(it, offscreenEvery) })
        }

    private const val LEAF_PREFIX = "node_leaf_"
```

**File**: `.../test/services/accessibility/CompactTreeFormatterTest.kt`

**Setup**: new `@Nested` class `FormatOptionsTests` (`@DisplayName("format options")`) using the real `formatter`,
`makeNode`, `defaultScreenInfo`, `PaginationTestTrees`. Move `snapshotOf`, `tsvRows`, `hierarchyLines` from
`PaginationTests` to outer-class `private` scope; `snapshotOf` gains `options: CompactFormatOptions =
CompactFormatOptions()`, passed to `countKeptNodes(result, options)` and to `ScreenStateSnapshot`. Off-screen parent
tree: on-screen kept `node_root` (resourceId) → OFF-screen kept `node_off_parent` (resourceId, `visible = false`) →
on-screen kept `node_on_child` (text). No new import (same package as `CompactFormatOptions`).

Legacy pin fixture (pre-plan output written out by hand from the current constants, so a change to the default
output fails the test): one window `WindowData(windowId = 0, windowType = "APPLICATION", packageName =
"com.example.app", title = "Main", activityName = null, layer = 0, focused = true, tree = root)`, `degraded = false`;
`root = makeNode(id = "node_root", className = "android.widget.FrameLayout", resourceId = "com.example.app:id/root",
bounds = BoundsData(0, 0, 1080, 2400), enabled = true, visible = true, children = listOf(on, off))`;
`on = makeNode(id = "node_on", className = "android.widget.Button", text = "OK", bounds = BoundsData(0, 0, 100, 48),
clickable = true, enabled = true, visible = true)`; `off = makeNode(id = "node_off", className =
"android.widget.TextView", text = "Later", bounds = BoundsData(0, 2500, 100, 2548))` (`visible` defaults to false).
Expected string = these lines joined with `"\n"` (no trailing newline): `CompactTreeFormatter.NOTE_LINE`,
`NOTE_LINE_CUSTOM_ELEMENTS`, `NOTE_LINE_FLAGS_LEGEND`, `NOTE_LINE_OFFSCREEN_HINT`,
`"screen:1080x2400 density:420 orientation:portrait"`,
`"--- window:0 type:APPLICATION pkg:com.example.app title:Main layer:0 focused:true ---"`, `HEADER`,
`"node_root\tFrameLayout\t-\t-\tcom.example.app:id/root\t0,0,1080,2400\ton,ena"`,
`"node_on\tButton\tOK\t-\t-\t0,0,100,48\ton,clk,ena"`, `"node_off\tTextView\tLater\t-\t-\t0,2500,100,2548\toff"`,
`"hierarchy:"`, `"node_root"`, `"  node_on"`, `"  node_off"`.

| Test | Verifies |
|------|----------|
| `default options output equals pinned legacy output` | Legacy pin fixture: `assertEquals(expected, formatMultiWindow(r, defaultScreenInfo))` and `assertEquals(expected, formatMultiWindow(r, defaultScreenInfo, CompactFormatOptions()))` |
| `default paged output keeps the legacy note lines` | `snapshotOf(result(keptNodeWindow(250)))` (2 pages), page 1 AND page 2 (the cursor page): in each, the first 5 lines are `NOTE_LINE`, `NOTE_LINE_CUSTOM_ELEMENTS`, `NOTE_LINE_FLAGS_LEGEND`, `NOTE_LINE_OFFSCREEN_HINT`, then the `screen:` line (the existing `PaginationTests` stay the guard for rows, hierarchy and pagination lines) |
| `includeOffscreen false omits off-screen rows` | No TSV row's flags start with `off`; every `visible = true` node id present |
| `includeOffscreen false replaces offscreen hint note` | Has `NOTE_LINE_OFFSCREEN_OMITTED`, not `NOTE_LINE_OFFSCREEN_HINT`; the default output is the reverse |
| `includeOffscreen false promotes on-screen child of off-screen kept parent` | Off-screen parent tree: `node_off_parent` absent from rows and hierarchy; `node_on_child` hierarchy line has exactly one `HIERARCHY_INDENT` |
| `includeHierarchy false omits hierarchy section in every window` | Two-window result: no `hierarchy:`; `tsvRows` equal the default output's |
| `countKeptNodes with options matches emitted row count` | All 4 combinations on `mixedVisibilityWindow(10, 3)`: `countKeptNodes(r, o) == tsvRows(formatMultiWindow(r, si, o)).size` |
| `paged output honours snapshot options for rows and closure` | `snapshotOf(result(mixedVisibilityWindow(448, 2)), options = CompactFormatOptions(includeOffscreen = false))`: totalPages 2; page 1 has `nodes:1-200/226`; no `off` row and no off-screen leaf id in either page's hierarchy |
| `paged output without hierarchy has no hierarchy header` | `includeHierarchy = false`, page 1 and last page: no `hierarchy:`; `page:` line and pagination note present |
| `paged notes use omitted note when offscreen excluded` | Pages 1 and 2 both contain `NOTE_LINE_OFFSCREEN_OMITTED` |
| `includeOffscreen false with only off-screen nodes emits no rows` | `w = keptNodeWindow(5)`, `r = result(w.copy(tree = allOffscreen(w.tree)))`, `o = CompactFormatOptions(includeOffscreen = false)`: `countKeptNodes(r, o) == 0`; `tsvRows` and `hierarchyLines` of `formatMultiWindow(r, si, o)` are empty; the output still has the note lines (`NOTE_LINE_OFFSCREEN_OMITTED`), the `screen:` line, the window header and `HEADER`, and no `page:` line |

**File**: `.../test/mcp/tools/ScreenIntrospectionToolsTest.kt`

**Setup**: update the strict-mock stubs to the 3-arg form: line 144 → `formatMultiWindow(any(), eq(sampleScreenInfo),
any())`; lines 403-406 → `formatMultiWindow(match { it.degraded }, eq(sampleScreenInfo), any())`. `PaginationTests`
rows use that nested class's real formatter and real cache; their `PrivacyToolTestDoubles.passthroughGate()` instance
is kept in a `val gate` of `PaginationTests` so the privacy rows can verify it. Off-screen parent tree (privacy rows):
on-screen kept `node_root` (resourceId) → OFF-screen kept `node_off_parent` (resourceId, `visible = false`) →
on-screen kept `node_on_child` (text), passed to `stubCaptureWithTree`. Screenshot stubs as in `screenshot honored on
cursorless page 1`, with `every { mockScreenshotAnnotator.annotate(any(), capture(annotated), any(), any()) }` where
`annotated = mutableListOf<List<AccessibilityNodeData>>()`. New imports:
`com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactFormatOptions`,
`kotlinx.serialization.json.JsonNull` (`verify`, `coVerify`, `buildJsonObject`, `put`, `putJsonObject` exist).

| Test | Verifies |
|------|----------|
| `passes default options when params absent` | `verify { formatMultiWindow(any(), eq(sampleScreenInfo), eq(CompactFormatOptions())) }` |
| `passes parsed options to formatter` | `include_offscreen=false`, `include_hierarchy=false` → `eq(CompactFormatOptions(includeOffscreen = false, includeHierarchy = false))` |
| `rejects string include_offscreen` | `"false"` (string) → `McpToolException.InvalidParams` |
| `rejects non-boolean include_hierarchy` | `1` → `InvalidParams` |
| `rejects null include_offscreen` | `put("include_offscreen", JsonNull)` → `InvalidParams` |
| `rejects object include_hierarchy` | `putJsonObject("include_hierarchy") {}` → `InvalidParams` |
| `rejects include_offscreen on cursor call when not boolean` | Cursor `"x.2"` + `include_offscreen = "no"` → `InvalidParams` |
| (PaginationTests) `include_offscreen false shrinks page count` | `mixedVisibilityWindow(250, 2)`: default call → `page:1/2` + snapshot; next call with `include_offscreen=false` → no `page:` line and `realCache.get(<previous id>)` is null |
| (PaginationTests) `cursor page reuses snapshot options` | Fresh call `include_hierarchy=false` on `keptNodeWindow(250)` → 2 pages; cursor `"<id>.2"` with `include_hierarchy=true` has no `hierarchy:` |
| (PaginationTests) `snapshot stores options` | After that fresh call: `realCache.get(id)!!.options == CompactFormatOptions(includeOffscreen = true, includeHierarchy = false)` |
| (PaginationTests) `include_offscreen false still redacts the full tree` | Off-screen parent tree, `include_offscreen=false`: `coVerify(exactly = 1) { gate.tree(match { r -> r.windows.single().tree.children.any { it.id == "node_off_parent" } }) }`; the response text has no `node_off_parent` and has `node_on_child` |
| (PaginationTests) `include_offscreen false keeps screenshot annotation unchanged` | Off-screen parent tree; call 1 `include_screenshot=true`, call 2 `include_screenshot=true` + `include_offscreen=false`: `annotated[0] == annotated[1]`, and both hold `node_on_child` |
| (PaginationTests) `include_offscreen false with all nodes off-screen returns a single empty page and clears the cache` | `stubCaptureWithTree(largeTree())`, default call → snapshot id; then `stubCaptureWithTree(PaginationTestTrees.allOffscreen(largeTree()))` with `include_offscreen=false` → `isError != true`, no `page:` line, no line starting with `node_` except `CompactTreeFormatter.HEADER`, `realCache.get(<previous id>)` is null |
| (PaginationTests) `include_offscreen false still populates the node cache` | Off-screen parent tree, `include_offscreen=false`: `verify(exactly = 1) { mockNodeCache.populate(any()) }` (the options never reach `getFreshWindows`) |

**File**: `.../test/mcp/tools/NodeActionToolsTest.kt` (`FindNodesToolTests`)

**Setup** (an edit to the existing fixture, listed in the DoD): the `PrivacyToolTestDoubles.passthroughGate()` instance
passed to `FindNodesTool` is kept in a `val gate` of `FindNodesToolTests` so tests can verify it; stub
`findElements(sampleWindows, FindBy.TEXT, "7", false)` →
`[sampleElementInfo, sampleElementInfo.copy(id = "node_off", visible = false)]`. New imports: `io.mockk.coVerify`,
`kotlinx.serialization.json.JsonNull`.

| Test | Verifies |
|------|----------|
| `visible_only true omits off-screen matches` | Only `node_abc` returned |
| `visible_only absent returns all matches` | Both nodes returned |
| `rejects string visible_only` | `"true"` (string) → `McpToolException.InvalidParams` |
| `rejects number visible_only` | `1` → `InvalidParams` |
| `rejects null visible_only` | `put("visible_only", JsonNull)` → `InvalidParams` |
| `visible_only filters after privacy gate` | `visible_only = true`: `coVerify { gate.texts(match { it.size == 4 }) }` (2 matches × 2 fields) |
| `visible_only true with only off-screen matches returns empty nodes` | Extra stub `findElements(sampleWindows, FindBy.TEXT, "9", false)` → `[sampleElementInfo.copy(id = "node_off", visible = false)]`; `value = "9"`, `visible_only = true` → `isError != true`, `nodes` is an empty array |

**File**: `.../test/integration/ScreenIntrospectionIntegrationTest.kt` (new `@Nested` class `OutputOptions`)

**Setup**: `McpIntegrationTestHelper.setupMultiWindowMock(deps, tree, screenInfo)`; tree = root holding one kept node
with `visible = true` and one with `visible = false`. New imports:
`com.danielealbano.androidremotecontrolmcp.services.accessibility.CompactTreeFormatter` (`NOTE_LINE_OFFSCREEN_OMITTED`),
`kotlinx.serialization.json.jsonObject`, `kotlinx.serialization.json.jsonPrimitive` (schema rows).

| Test | Verifies |
|------|----------|
| `get_screen_state include_offscreen false omits off rows over http` | No `off` row; text has `NOTE_LINE_OFFSCREEN_OMITTED` |
| `get_screen_state include_hierarchy false omits hierarchy over http` | No `hierarchy:` line |
| `get_screen_state schema exposes new params with defaults` | `client.listTools()`: `android_get_screen_state` has `include_offscreen`, `include_hierarchy`, each `"default": true`; `inputSchema.properties!!.keys.toList() == listOf("include_screenshot", "cursor", "include_offscreen", "include_hierarchy")` (the default `ToolPermissionsConfig()` enables `include_screenshot`) |
| `get_screen_state invalid include_offscreen returns isError` | `"yes"` → `isError == true` |

**File**: `.../test/integration/NodeActionIntegrationTest.kt` (no new import: `ElementInfo`, `FindBy`, `WindowData`,
`Json`, `jsonArray`, `jsonObject`, `jsonPrimitive` exist)

| Test | Verifies |
|------|----------|
| `find_nodes visible_only filters over http` | Stubbed `findElements` returns one visible and one invisible node; `visible_only = true` → only the visible `node_id` |
| `find_nodes schema exposes visible_only default false` | `listTools`: `visible_only` with `"default": false` |

**File**: `.../test/mcp/tools/McpToolUtilsTest.kt`: new `@Nested @DisplayName("optionalBoolean") inner class
OptionalBooleanTests` right after `OptionalStringTests` (the one parser behind all three new params; no direct test
exists today). New imports: `kotlinx.serialization.json.JsonNull`, `kotlinx.serialization.json.putJsonObject`,
`org.junit.jupiter.api.Assertions.assertFalse`.

| Test | Verifies |
|------|----------|
| `returns default when parameter is absent` | `{}` and `null` params → the given default (both `true` and `false` defaults) |
| `returns true and false` | `true` → `true`; `false` → `false` (default opposite to the value) |
| `rejects string-encoded boolean` | `"true"` → `InvalidParams`, message contains `"got string"` |
| `rejects number` | `1` → `InvalidParams` |
| `rejects JSON null` | `put("flag", JsonNull)` → `InvalidParams` |
| `rejects object` | `putJsonObject("flag") {}` → `InvalidParams`, message contains `"must be a boolean"` |

**Definition of Done:**
- [x] All rows implemented. The only edits to existing tests are: the two `formatMultiWindow` stub updates and the
      `val gate` extraction in `PaginationTests` of `ScreenIntrospectionToolsTest`; the move of `snapshotOf` /
      `tsvRows` / `hierarchyLines` to outer-class scope (with the new `options` parameter of `snapshotOf`) in
      `CompactTreeFormatterTest`; the `val gate` extraction in `FindNodesToolTests`; and the import additions listed
      per file.

### Task 2.6 — Documentation for US2

**Action 1 — modify** `docs/MCP_TOOLS.md`, section ``### `android_get_screen_state` `` (line 181):
- (a) Replace the **Input Schema** JSON block with:

```json
{
  "type": "object",
  "properties": {
    "include_screenshot": {
      "type": "boolean",
      "description": "Include a low-resolution screenshot. Only request when the UI node list is not sufficient.",
      "default": false
    },
    "cursor": {
      "type": "string",
      "description": "Pagination cursor from a previous response (format \"<id>.<page>\"). Omit to capture a fresh screen state starting at page 1. A cursor is tied to one screen snapshot; if the screen changed you will be told to request a fresh one."
    },
    "include_offscreen": {
      "type": "boolean",
      "description": "Include nodes not visible to the user (flag 'off': hidden pager pages, prefetched list rows). Set false for a smaller response with on-screen nodes only. Applies to fresh captures only; cursor pages reuse the value of the capture that issued the cursor.",
      "default": true
    },
    "include_hierarchy": {
      "type": "boolean",
      "description": "Include the 'hierarchy:' section (node nesting by indentation) for each window. Set false for a smaller response. Applies to fresh captures only; cursor pages reuse the value of the capture that issued the cursor.",
      "default": true
    }
  },
  "required": []
}
```

  Below it: "`include_screenshot` is absent from the schema when it is disabled in the tool permissions."
- (b) After the existing **Request Example** (with screenshot) block, insert:

````markdown
**Request Example** (slim output):
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "android_get_screen_state",
    "arguments": {
      "include_offscreen": false,
      "include_hierarchy": false
    }
  }
}
```
````

- (c) **Output Format** edits:

```diff
    - `note:offscreen items require scroll_to_node before interaction`
+     (replaced by `note:offscreen nodes are omitted (include_offscreen=false); scroll, or call without include_offscreen=false, to see them` when `include_offscreen=false`)
```

```diff
-   - **Hierarchy header**: `hierarchy:`
-   - **Hierarchy rows**: One line per kept node (node ID only), indented by 2 spaces per nesting depth. Structural-only nodes are omitted; their kept children are promoted to the parent's depth.
+   - **Hierarchy header**: `hierarchy:` (only when `include_hierarchy=true`)
+   - **Hierarchy rows**: One line per kept node (node ID only), indented by 2 spaces per nesting depth. Structural-only nodes are omitted; their kept children are promoted to the parent's depth. With `include_offscreen=false`, off-screen nodes are omitted too, and their emitted descendants are promoted the same way.
```

- (d) Insert after the **Output Format** section (after the paragraph ending "...produce different IDs.") and before
  `#### Flags Reference` (G3 drift fix):

```markdown
#### Pagination

Large screens are split into pages of up to 200 emitted nodes (TSV rows). A result that fits in one page has no `page:` line and no cursor.

- **Page line**: on multi-page results the `screen:` line is followed by `page:<N>/<T> snapshot:<id> nodes:<a>-<b>/<total>`.
- **Windows**: a page prints only the windows that contribute rows to it; a window that spans pages repeats its header on each page.
- **Hierarchy**: each window's `hierarchy:` block lists the page's rows plus their emitted ancestors, at the same indentation as the unpaged output.
- **Last line**: `note:more nodes available — call get_screen_state with cursor "<id>.<N+1>" to continue. ...` on every page but the last; `note:end of snapshot (page <N>/<T>). ...` on the last page.
- **Cursor**: `<id>.<page>`. Only the latest snapshot is kept; any fresh capture (a call without a cursor) replaces it.
- **Cursor errors**: an invalid cursor, a snapshot that is no longer available, or a page that does not exist return a guidance `note:` line, not an error.
- **Options on a cursor call**: `include_screenshot` is ignored with a `note:` line (a screenshot can only be requested on page 1); `include_offscreen` and `include_hierarchy` are validated but ignored, because a cursor page is rendered with the values of the capture that issued the cursor.
- **Counts**: page counts and node ranges count emitted rows, so `include_offscreen=false` can reduce the number of pages.
```

- (e) Insert after the **Flags Reference** section (after the line "Example: `on,clk,ena` means onscreen + clickable +
  enabled.") and before `#### Class Name Simplification`:

```markdown
#### Off-screen definition

The `off` flag means the node was not visible to the user (`AccessibilityNodeInfo.isVisibleToUser() == false`) when the screen was captured. It is not a bounds check: a row clipped at the screen edge is `on`. `include_offscreen=false` omits exactly the `off` rows.

WebView caveat: merged web nodes are filtered by their own visibility, so off-screen page text that was already merged into an on-screen link, block or web root stays in the output.

To act on an off-screen node, call `android_get_screen_state` with the default `include_offscreen=true` to get its `node_id`, then `android_scroll_to_node`.
```

- (f) **Error Cases** of the section: append as the last bullet:

```markdown
- **Invalid params**: `include_offscreen` or `include_hierarchy` present but not a JSON boolean (strings, numbers, objects and `null` are rejected)
```

**Action 2 — modify** `docs/MCP_TOOLS.md`, section ``### `android_find_nodes` `` (line 1047):

```diff
     "exact_match": {
       "type": "boolean",
       "default": false,
       "description": "If true, match exactly. If false, match contains (case-insensitive)"
-    }
+    },
+    "visible_only": {
+      "type": "boolean",
+      "default": false,
+      "description": "If true, return only nodes visible to the user (visible=true); off-screen matches are omitted"
+    }
   },
```

```diff
       "editable": false,
-      "enabled": true
+      "enabled": true,
+      "visible": true
     }
```

```diff
-- **Invalid params**: Invalid `by` value, empty `value`, or missing required parameters
+- **Invalid params**: Invalid `by` value, empty `value`, missing required parameters, or `visible_only` present but not a JSON boolean
```

**Action 3 — modify** `docs/PROJECT.md`:

```diff
-  - `services/accessibility/` — `McpAccessibilityService.kt`, `AccessibilityTreeParser.kt`, `ElementFinder.kt`, `ActionExecutor.kt`, `ActionExecutorImpl.kt`, `AccessibilityServiceProvider.kt`, `AccessibilityServiceProviderImpl.kt`, `TypeInputController.kt`, `TypeInputControllerImpl.kt`, `ScreenInfo.kt`, `CompactTreeFormatter.kt`, `AccessibilityNodeCache.kt`, `AccessibilityNodeCacheImpl.kt`
+  - `services/accessibility/` — `McpAccessibilityService.kt`, `AccessibilityTreeParser.kt`, `ElementFinder.kt`, `ActionExecutor.kt`, `ActionExecutorImpl.kt`, `AccessibilityServiceProvider.kt`, `AccessibilityServiceProviderImpl.kt`, `TypeInputController.kt`, `TypeInputControllerImpl.kt`, `ScreenInfo.kt`, `CompactTreeFormatter.kt`, `CompactFormatOptions.kt`, `AccessibilityNodeCache.kt`, `AccessibilityNodeCacheImpl.kt`
```

```diff
-| `android_get_screen_state` | Returns consolidated screen state: app info, screen dimensions, and a compact filtered flat TSV list of UI nodes | `include_screenshot` (boolean, optional, default false) | `TextContent` with compact TSV (text/desc truncated to 100 chars, comma-separated abbreviated flags with on/off visibility). Optionally includes `ImageContent` with annotated low-resolution JPEG screenshot (700px max, red bounding boxes with node ID labels). |
+| `android_get_screen_state` | Returns consolidated screen state: app info, screen dimensions, and a compact filtered flat TSV list of UI nodes | `include_screenshot` (boolean, optional, default false), `cursor` (string, optional), `include_offscreen` (boolean, optional, default true), `include_hierarchy` (boolean, optional, default true) | `TextContent` with compact TSV (text/desc truncated to 100 chars, comma-separated abbreviated flags with on/off visibility). Optionally includes `ImageContent` with annotated low-resolution JPEG screenshot (700px max, red bounding boxes with node ID labels). |
```

Append to the end of the `**Note**: `android_get_screen_state` returns a filtered flat TSV list ...` paragraph (same
line, after "...for on-screen nodes."):

```markdown
 `include_offscreen=false` omits nodes not visible to the user and `include_hierarchy=false` omits the `hierarchy:` section; both apply to fresh captures only (cursor pages reuse the capture's values).
```

```diff
-| `android_find_nodes` | Find UI nodes by criteria | `by` (string: text/content_desc/resource_id/class_name), `value` (string) | `exact_match` (boolean, default false) |
+| `android_find_nodes` | Find UI nodes by criteria | `by` (string: text/content_desc/resource_id/class_name), `value` (string) | `exact_match` (boolean, default false), `visible_only` (boolean, default false) |
```

**Definition of Done:**
- [x] Documented schemas match `buildGetScreenStateProperties` and `FindNodesTool.register` strings exactly.
- [x] No size-saving figure is published.

---

## User Story 3 — Selector-based tap_node / click_node

**Why:** a tap costs two calls and two full tree reads today (`find_nodes`, then `tap_node`/`click_node`). A selector
form resolves and acts from one `getFreshWindows` read; more than one on-screen match is never guessed.

**Acceptance criteria:**
- [ ] `click_node` and `tap_node` accept exactly one of `node_id` or a selector (`by` + `value` [+ `exact_match`]).
      Both, neither, or an incomplete selector → `InvalidParams` before any tree read.
- [ ] A selector call does exactly one `getFreshWindows`; `tap_node` does not call `findNodeById` on that path.
      (`ActionExecutorImpl.performNodeAction` may still re-walk on a node-cache miss, as for `node_id`.)
- [ ] A selector matches only on-screen nodes (`ElementInfo.visible`) across all windows (D3.7).
- [ ] 1 on-screen match: the action runs; the success text is byte-identical to the `node_id` path, with the
      resolved `node_id`.
- [ ] >1 on-screen matches: no action; `CallToolResult(isError=true)`, first line `UNTRUSTED_CONTENT_WARNING`, then
      JSON `{error:"ambiguous_selector", match_count, candidates[≤5], hint}` (D3.1, D3.2). Candidate texts are
      redacted by one `PrivacyToolGate.texts` pass over the FULL match list (same input as `find_nodes`).
- [ ] 0 on-screen matches: `NodeNotFound`; with off-screen matches the message lists up to 5 of their `node_id`s and
      suggests `scroll_to_node` (D3.3).
- [ ] The selector `value` passes through `PlaceholderSubstitutor` and is used only for matching. Neither the agent
      value nor its substituted form appears in any error message or app log line. Thrown tool errors must not carry
      it: MCP Kotlin SDK 0.15.0 `Server.handleCallTool` logs every thrown exception with
      `logger.error(e) { "Error executing tool <name>" }`, and SLF4J-Android routes the throwable (message included)
      to logcat.
- [ ] `node_id`-only calls behave as before (pins at `NodeActionToolsTest.kt:456` and `:534` hold), except these
      intentional changes: `node_id` with `by`, `value` or `exact_match` → `InvalidParams` (D3.4, D3.12); the
      missing-target message gains "(or a selector: 'by' + 'value')"; both schemas declare `"required": []`.

### Task 3.1 — Untrusted error result helper

**Action 1 — modify** `.../mcp/tools/McpToolUtils.kt`: insert right after `untrustedTextResult` (lines 442-443).

```kotlin
    /**
     * Creates an error [CallToolResult] (`isError = true`) with [UNTRUSTED_CONTENT_WARNING] prepended to the text.
     * Use ONLY for error results that carry device-derived content (e.g. ambiguous-selector candidates).
     */
    fun untrustedErrorResult(text: String): CallToolResult =
        CallToolResult(content = listOf(TextContent(text = "$UNTRUSTED_CONTENT_WARNING\n$text")), isError = true)
```

**Action 2 — modify** `.../test/mcp/tools/McpToolUtilsTest.kt`: add `@Nested @DisplayName("untrustedErrorResult")
inner class UntrustedErrorResultTests` right after `UntrustedTextResultTests`.

| Test | Verifies |
|------|----------|
| `sets isError true` | `result.isError == true` |
| `prepends warning as first line` | Single `TextContent`; starts with `UNTRUSTED_CONTENT_WARNING + "\n"`, ends with the payload |

**Definition of Done:**
- [ ] Helper with KDoc next to `untrustedTextResult`; both tests added.

### Task 3.2 — NodeSelectorResolver

**Action 1 — create** `.../mcp/tools/NodeSelectorResolver.kt` (D3.10). All types are public because the public
`@Inject` constructors of `ClickNodeTool` / `TapNodeTool` take the resolver. Every validation failure goes through
`invalid()`, keeping detekt `ThrowsCount` satisfied. Concrete class, no interface (D3.13).

```kotlin
package com.danielealbano.androidremotecontrolmcp.mcp.tools

import android.util.Log
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.privacy.PrivacyToolGate
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo
import com.danielealbano.androidremotecontrolmcp.services.accessibility.FindBy
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject

/**
 * Selector form of a node target. [value] is the agent-supplied value with pseudonym placeholders reversed; it is
 * used ONLY for matching and MUST NEVER be echoed or logged (nor is the agent-supplied form).
 */
data class NodeSelector(
    val findBy: FindBy,
    val by: String,
    val value: String,
    val exactMatch: Boolean,
) {
    override fun toString(): String = "NodeSelector(by=$by, exactMatch=$exactMatch)"
}

/** Target of a node action: an explicit node ID or a selector resolved against the current tree. */
sealed interface NodeTarget {
    data class ById(
        val nodeId: String,
    ) : NodeTarget

    data class BySelector(
        val selector: NodeSelector,
    ) : NodeTarget
}

/** Outcome of resolving a [NodeSelector] (zero on-screen matches throws instead). */
sealed interface SelectorResolution {
    data class Resolved(
        val element: ElementInfo,
    ) : SelectorResolution

    /** [matches] is the full match list in tree order (off-screen included); [onScreenCount] is > 1. */
    data class Ambiguous(
        val matches: List<ElementInfo>,
        val onScreenCount: Int,
    ) : SelectorResolution
}

/**
 * Parses and resolves the target of click_node / tap_node: `node_id` OR selector (`by` + `value` [+ `exact_match`]).
 * A selector resolves among on-screen nodes only ([ElementInfo.visible]) across all windows, against the windows
 * of the caller's single tree read. More than one on-screen match is never guessed.
 */
class NodeSelectorResolver
    @Inject
    constructor(
        private val elementFinder: ElementFinder,
        private val privacyToolGate: PrivacyToolGate,
        private val substitutor: PlaceholderSubstitutor,
    ) {
        /**
         * Validates the target arguments; performs no tree read. Throws [McpToolException.InvalidParams] for missing,
         * empty or conflicting targets; non-primitive values fail like the other node tools.
         */
        fun parseTarget(arguments: JsonObject?): NodeTarget {
            val nodeId = arguments?.get("node_id")?.jsonPrimitive?.contentOrNull
            val byStr = arguments?.get("by")?.jsonPrimitive?.contentOrNull
            val rawValue = arguments?.get("value")?.jsonPrimitive?.contentOrNull
            val exactMatchArg = arguments?.get("exact_match")?.takeUnless { it is JsonNull }
            val hasSelector = byStr != null || rawValue != null
            if (nodeId == null) {
                if (!hasSelector) invalid("Missing required parameter 'node_id' (or a selector: 'by' + 'value')")
                return NodeTarget.BySelector(parseSelector(byStr, rawValue, exactMatchArg))
            }
            if (hasSelector) invalid("Provide either 'node_id' or a selector ('by' + 'value'), not both")
            if (exactMatchArg != null) {
                invalid("Parameter 'exact_match' is only valid with a selector ('by' + 'value')")
            }
            if (nodeId.isEmpty()) invalid("Parameter 'node_id' must be non-empty")
            return NodeTarget.ById(nodeId)
        }

        /**
         * Resolves [selector] among the on-screen nodes of [windows].
         *
         * @throws McpToolException.NodeNotFound when no on-screen node matches.
         */
        fun resolve(
            windows: List<WindowData>,
            selector: NodeSelector,
        ): SelectorResolution {
            val matches = elementFinder.findElements(windows, selector.findBy, selector.value, selector.exactMatch)
            val onScreen = matches.filter { it.visible }
            Log.d(TAG, "resolve: by=${selector.by}, matches=${matches.size}, onScreen=${onScreen.size}")
            return when {
                onScreen.size == 1 -> SelectorResolution.Resolved(onScreen.single())
                onScreen.size > 1 -> SelectorResolution.Ambiguous(matches, onScreen.size)
                else -> throw McpToolException.NodeNotFound(notFoundMessage(selector, matches))
            }
        }

        /**
         * Builds the ambiguous-selector error: untrusted warning first, candidates redacted by Privacy Mode. The
         * redaction input is the full match list, i.e. exactly what find_nodes redacts for the same selector.
         */
        suspend fun ambiguityResult(ambiguous: SelectorResolution.Ambiguous): CallToolResult {
            val matches = ambiguous.matches
            val redactedFields =
                privacyToolGate.texts(
                    matches.flatMap { listOf(it.text to "node text", it.contentDescription to "node description") },
                )
            val candidates =
                matches
                    .mapIndexed { index, element ->
                        element.copy(
                            text = redactedFields[index * 2],
                            contentDescription = redactedFields[index * 2 + 1],
                        )
                    }.filter { it.visible }
                    .take(MAX_AMBIGUOUS_CANDIDATES)
            val resultJson =
                buildJsonObject {
                    put("error", "ambiguous_selector")
                    put("match_count", ambiguous.onScreenCount)
                    put("candidates", buildJsonArray { candidates.forEach { add(McpToolUtils.buildNodeJson(it)) } })
                    put("hint", AMBIGUITY_HINT)
                }
            return McpToolUtils.untrustedErrorResult(Json.encodeToString(resultJson))
        }

        private fun parseSelector(
            byStr: String?,
            rawValue: String?,
            exactMatchArg: JsonElement?,
        ): NodeSelector {
            if (byStr == null) invalid("Missing required parameter 'by' $SELECTOR_PAIR_NOTE")
            if (rawValue == null) invalid("Missing required parameter 'value' $SELECTOR_PAIR_NOTE")
            if (rawValue.isEmpty()) invalid("Parameter 'value' must be non-empty")
            val findBy =
                mapFindBy(byStr)
                    ?: invalid(
                        "Invalid 'by' value: '$byStr'. Must be one of: text, content_desc, resource_id, class_name",
                    )
            return NodeSelector(
                findBy = findBy,
                by = byStr,
                value = substitutor.substitute(rawValue),
                exactMatch = exactMatchArg?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }

        private fun notFoundMessage(
            selector: NodeSelector,
            offScreenMatches: List<ElementInfo>,
        ): String {
            // No selector value here: the SDK logs thrown tool errors (message included) to logcat.
            val base = "No on-screen node matches selector (by=${selector.by}, exact_match=${selector.exactMatch})"
            if (offScreenMatches.isEmpty()) return base
            val ids = offScreenMatches.take(MAX_OFFSCREEN_IDS).joinToString(", ") { it.id }
            return "$base; ${offScreenMatches.size} matching node(s) are off-screen: [$ids]. " +
                "Call scroll_to_node with one of these node_ids first"
        }

        private fun invalid(message: String): Nothing = throw McpToolException.InvalidParams(message)

        companion object {
            private const val TAG = "MCP:NodeSelectorResolver"
            const val MAX_AMBIGUOUS_CANDIDATES = 5
            const val MAX_OFFSCREEN_IDS = 5
            private const val SELECTOR_PAIR_NOTE = "(a selector requires both 'by' and 'value')"
            private const val AMBIGUITY_HINT =
                "More than one on-screen node matches; retry with the intended node_id " +
                    "or a more specific selector (e.g. exact_match=true)"
        }
    }

/** Schema properties shared by click_node and tap_node: `node_id` OR selector (`by` + `value` [+ `exact_match`]). */
internal fun JsonObjectBuilder.putNodeTargetProperties(toolNamePrefix: String) {
    putJsonObject("node_id") {
        put("type", "string")
        put("description", "Node ID from ${toolNamePrefix}find_nodes. Mutually exclusive with 'by' + 'value'")
    }
    putJsonObject("by") {
        put("type", "string")
        put(
            "enum",
            buildJsonArray {
                add(JsonPrimitive("text"))
                add(JsonPrimitive("content_desc"))
                add(JsonPrimitive("resource_id"))
                add(JsonPrimitive("class_name"))
            },
        )
        put("description", "Selector criteria type; use with 'value' instead of 'node_id'")
    }
    putJsonObject("value") {
        put("type", "string")
        put("description", "Selector value; use with 'by'")
    }
    putJsonObject("exact_match") {
        put("type", "boolean")
        put("default", false)
        put("description", "Selector only. If true, match exactly. If false, match contains (case-insensitive)")
    }
}
```

No `kotlinx.serialization.encodeToString` import (same as `NodeActionTools.kt`).

**Action 2 — create** `.../test/mcp/tools/NodeSelectorResolverTest.kt`

**Setup**: `resolver = NodeSelectorResolver(ElementFinder(), PrivacyToolTestDoubles.passthroughGate(),
PrivacyToolTestDoubles.identitySubstitutor())` (real `ElementFinder`, pure JVM); windows are `WindowData` lists of
`AccessibilityNodeData` with `visible = true/false`. Imports beyond the tested types: `android.util.Log`,
`com.danielealbano.androidremotecontrolmcp.data.model.PlaceholderFormat`,
`com.danielealbano.androidremotecontrolmcp.mcp.McpToolException` (the `assertThrows` rows; `stripUntrustedWarning` and
`McpToolUtils` share the test's package and need no import),
`com.danielealbano.androidremotecontrolmcp.privacy.{PiiCategory, PlaceholderSubstitutor, PrivacyToolGate,
PseudonymStore}`, `...services.accessibility.{AccessibilityNodeData, BoundsData, ElementFinder, FindBy, WindowData}`,
`...testutil.PrivacyToolTestDoubles`, `io.mockk.{coEvery, coVerify, every, mockk, mockkStatic, unmockkStatic}`,
`io.modelcontextprotocol.kotlin.sdk.types.TextContent`, `kotlinx.coroutines.test.runTest`,
`kotlinx.serialization.json.{Json, JsonObject, buildJsonObject, jsonArray, jsonObject, jsonPrimitive, put}`,
`org.junit.jupiter.api.{DisplayName, Test, assertThrows}`, `org.junit.jupiter.api.Assertions.*` members used (one
import each; no wildcard).

| Test | Verifies |
|------|----------|
| `node_id only returns ById` | `{node_id:"node_a"}` → `ById("node_a")` |
| `selector only returns BySelector` | `{by:"text", value:"OK"}` → `findBy=TEXT`, `value="OK"`, `exactMatch=false` |
| `exact_match true is parsed` | → `exactMatch=true` |
| `node_id with selector is rejected` | `InvalidParams` containing `"not both"` |
| `neither form is rejected` | `{}` → message contains `"Missing required parameter 'node_id'"` |
| `null arguments are rejected` | `null` → same message |
| `by without value is rejected` | Contains `"Missing required parameter 'value'"` |
| `value without by is rejected` | Contains `"Missing required parameter 'by'"` |
| `empty value is rejected` | Contains `"'value' must be non-empty"` |
| `empty node_id is rejected` | Contains `"'node_id' must be non-empty"` |
| `invalid by is rejected` | Contains `"Invalid 'by' value"` |
| `exact_match with node_id is rejected` | Contains `"only valid with a selector"` |
| `placeholder value is substituted for matching` | `value` is the real string. **Setup**: `val store = PseudonymStore(); val placeholder = store.placeholderFor("a@b.co", PiiCategory.EMAILS, PlaceholderFormat.HASHED)`; resolver built with `PlaceholderSubstitutor(store)`; selector `value = placeholder` |
| `toString omits values` | Placeholder substitutor: `selector.toString()` contains neither the placeholder nor `"a@b.co"` |
| `single on-screen match resolves` | `Resolved` with that node's id |
| `off-screen duplicate is ignored` | One visible + one invisible match → `Resolved(visible one)` |
| `two on-screen matches are ambiguous` | `Ambiguous(onScreenCount = 2)`, `matches` holds every match |
| `resolves a unique match in a second window` | Two `WindowData` (APPLICATION window with no match; SYSTEM dialog window holding one visible `"OK"` node) → `Resolved` with the dialog node's id (real multi-window matching) |
| `resolve log line omits selector values` | **Setup**: `mockkStatic(Log::class)`; `every { Log.d(any<String>(), capture(debugLines)) } returns 0`; `unmockkStatic(Log::class)` in `finally`. Placeholder substitutor as above: no captured line contains `placeholder` or `"a@b.co"` |
| `no match throws NodeNotFound` | Message contains `"No on-screen node matches selector"`, not `"off-screen"` |
| `only off-screen matches throw NodeNotFound with ids` | Message contains the invisible ids and `"scroll_to_node"` |
| `NodeNotFound echoes no selector value` | Placeholder substitutor, no match and only off-screen matches: neither message contains the placeholder or `"a@b.co"` |
| `ambiguityResult is untrusted error` | `isError==true`; starts with `UNTRUSTED_CONTENT_WARNING`; JSON after `stripUntrustedWarning` has `error=="ambiguous_selector"`, `match_count`, `candidates[].node_id`, `hint` |
| `ambiguityResult caps candidates at 5` | 7 visible matches → `match_count == 7`, 5 candidates |
| `ambiguityResult lists only on-screen candidates` | 2 visible + 1 invisible → 2 candidates, none with the invisible id |
| `ambiguityResult redacts the full match list` | `mockk<PrivacyToolGate>` with `coEvery { texts(any()) } answers { firstArg<List<Pair<String?, String>>>().map { "REDACTED" } }`: candidate `text`/`contentDescription` are `"REDACTED"`; `coVerify { texts(match { it.size == 6 }) }` for 3 matches (off-screen included) |

**Definition of Done:**
- [ ] `NodeSelectorResolver.kt` exists as above; the substituted `value` never appears in messages or logs.
- [ ] All rows added.

### Task 3.3 — Wire the selector into click_node and tap_node

**Action 1 — modify** `.../mcp/tools/NodeActionTools.kt`: add import
`com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData`; replace `ClickNodeTool` with its KDoc
(lines 165-224).

```kotlin
/**
 * MCP tool: click_node
 *
 * Clicks the accessibility node identified by node_id, or by a selector resolved among on-screen nodes.
 */
class ClickNodeTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
        private val selectorResolver: NodeSelectorResolver,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val target = selectorResolver.parseTarget(arguments)

            val windows = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache).windows

            val nodeId =
                when (target) {
                    is NodeTarget.ById -> {
                        target.nodeId
                    }

                    is NodeTarget.BySelector -> {
                        when (val resolution = selectorResolver.resolve(windows, target.selector)) {
                            is SelectorResolution.Resolved -> resolution.element.id
                            is SelectorResolution.Ambiguous -> return selectorResolver.ambiguityResult(resolution)
                        }
                    }
                }

            val result = actionExecutor.clickNode(nodeId, windows)
            result.onFailure { e -> mapNodeActionException(e, nodeId) }

            Log.d(TAG, "click_node: nodeId=$nodeId succeeded (selector=${target is NodeTarget.BySelector})")
            return McpToolUtils.textResult("Click performed on node '$nodeId'")
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Click an accessibility node by node_id, or by selector (by + value [+ exact_match]) " +
                        "resolved among on-screen nodes only; provide exactly one form. A selector matching " +
                        "several on-screen nodes returns an error listing the candidates. " +
                        "Returns after the click is performed.",
                inputSchema =
                    ToolSchema(
                        properties = buildJsonObject { putNodeTargetProperties(toolNamePrefix) },
                        required = emptyList(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:ClickNodeTool"
            const val TOOL_NAME = "click_node"
        }
    }
```

**Action 2 — modify** same file: replace `TapNodeTool` with its KDoc (lines 287-411). `randomFloatInRange`, the
constants and the success text are unchanged; the tap-point math moves unchanged into `computeTapPoint`. The existing
`@Suppress("ThrowsCount")` on `execute` is REMOVED: the `node_id` checks move into
`NodeSelectorResolver.parseTarget`, leaving 2 `throw` expressions (inset range, `NodeNotFound`), which is within
detekt's default `ThrowsCount` `max: 2` (no detekt config file in the repo, so defaults apply). Both outer `when (target)` blocks brace every entry and separate the
entries with a blank line (ktlint 1.8.0 `when-entry-bracing` and `blank-line-between-when-conditions`).

```kotlin
/**
 * MCP tool: tap_node
 *
 * Performs a gesture-based tap at a random point within the bounds of the node identified by node_id, or by a
 * selector resolved among on-screen nodes. Unlike click_node (which uses the accessibility ACTION_CLICK), this
 * performs a coordinate-based touch gesture.
 */
class TapNodeTool
    @Inject
    constructor(
        private val treeParser: AccessibilityTreeParser,
        private val elementFinder: ElementFinder,
        private val actionExecutor: ActionExecutor,
        private val accessibilityServiceProvider: AccessibilityServiceProvider,
        private val nodeCache: AccessibilityNodeCache,
        private val selectorResolver: NodeSelectorResolver,
    ) {
        suspend fun execute(arguments: JsonObject?): CallToolResult {
            val target = selectorResolver.parseTarget(arguments)

            val insetPercentage =
                McpToolUtils.optionalFloat(arguments, "inset_percentage", DEFAULT_INSET_PERCENTAGE)

            if (insetPercentage < MIN_INSET_PERCENTAGE || insetPercentage > MAX_INSET_PERCENTAGE) {
                throw McpToolException.InvalidParams(
                    "Parameter 'inset_percentage' must be between $MIN_INSET_PERCENTAGE and $MAX_INSET_PERCENTAGE",
                )
            }

            val windows = getFreshWindows(treeParser, accessibilityServiceProvider, nodeCache).windows

            val (nodeId, bounds) =
                when (target) {
                    is NodeTarget.ById -> {
                        val node =
                            elementFinder.findNodeById(windows, target.nodeId)
                                ?: throw McpToolException.NodeNotFound("Node '${target.nodeId}' not found")
                        target.nodeId to node.bounds
                    }

                    is NodeTarget.BySelector -> {
                        when (val resolution = selectorResolver.resolve(windows, target.selector)) {
                            is SelectorResolution.Resolved -> resolution.element.id to resolution.element.bounds
                            is SelectorResolution.Ambiguous -> return selectorResolver.ambiguityResult(resolution)
                        }
                    }
                }

            val (tapX, tapY) = computeTapPoint(bounds, insetPercentage)

            Log.d(TAG, "tap_node: nodeId=$nodeId, tapAt=($tapX, $tapY)")

            val result = actionExecutor.tap(tapX, tapY)
            return McpToolUtils.handleActionResult(
                result,
                "Tap executed at (${tapX.toInt()}, ${tapY.toInt()}) within node '$nodeId'",
            )
        }

        private fun computeTapPoint(
            bounds: BoundsData,
            insetPercentage: Float,
        ): Pair<Float, Float> {
            val width = bounds.right - bounds.left
            val height = bounds.bottom - bounds.top
            return if (width < SMALL_ELEMENT_THRESHOLD && height < SMALL_ELEMENT_THRESHOLD) {
                Pair(bounds.left.toFloat(), bounds.top.toFloat())
            } else {
                val insetFraction = insetPercentage / PERCENTAGE_DIVISOR
                val insetLeft = bounds.left + width * insetFraction
                val insetRight = bounds.right - width * insetFraction
                val insetTop = bounds.top + height * insetFraction
                val insetBottom = bounds.bottom - height * insetFraction
                Pair(
                    randomFloatInRange(insetLeft, insetRight),
                    randomFloatInRange(insetTop, insetBottom),
                )
            }
        }

        internal fun randomFloatInRange(
            min: Float,
            max: Float,
        ): Float {
            if (min >= max) return min
            return min + Random.nextFloat() * (max - min)
        }

        fun register(
            registrar: LoggedToolRegistrar,
            toolNamePrefix: String,
        ) {
            registrar.addTool(
                toolName = TOOL_NAME,
                name = "$toolNamePrefix$TOOL_NAME",
                description =
                    "Performs a gesture-based tap at a random point within the bounds of a node, identified " +
                        "by node_id or by selector (by + value [+ exact_match]) resolved among on-screen nodes " +
                        "only; provide exactly one form. A selector matching several on-screen nodes returns an " +
                        "error listing the candidates. Unlike click_node (which uses the accessibility " +
                        "ACTION_CLICK), this performs a coordinate-based touch gesture. The tap point is " +
                        "randomized within the node bounds, inset by a configurable percentage (default 5%) " +
                        "from each edge to avoid hitting borders. Returns after the gesture completes.",
                inputSchema =
                    ToolSchema(
                        properties =
                            buildJsonObject {
                                putNodeTargetProperties(toolNamePrefix)
                                putJsonObject("inset_percentage") {
                                    put("type", "number")
                                    put("default", DEFAULT_INSET_PERCENTAGE.toDouble())
                                    put(
                                        "description",
                                        "Percentage to inset from each edge of the node bounds " +
                                            "(0.0-45.0). Default 5.0",
                                    )
                                }
                            },
                        required = emptyList(),
                    ),
            ) { request -> execute(request.arguments) }
        }

        companion object {
            private const val TAG = "MCP:TapNodeTool"
            const val TOOL_NAME = "tap_node"
            private const val DEFAULT_INSET_PERCENTAGE = 5.0f
            private const val MIN_INSET_PERCENTAGE = 0.0f
            private const val MAX_INSET_PERCENTAGE = 45.0f
            private const val SMALL_ELEMENT_THRESHOLD = 5
            private const val PERCENTAGE_DIVISOR = 100.0f
        }
    }
```

**Action 3 — modify** same file, `registerNodeActionTools` (lines 820-852).

```diff
 ) {
+    val selectorResolver = NodeSelectorResolver(elementFinder, privacyToolGate, substitutor)
     if (perms.isToolEnabled(FindNodesTool.TOOL_NAME)) {
@@
     if (perms.isToolEnabled(ClickNodeTool.TOOL_NAME)) {
-        ClickNodeTool(treeParser, actionExecutor, accessibilityServiceProvider, nodeCache)
+        ClickNodeTool(treeParser, actionExecutor, accessibilityServiceProvider, nodeCache, selectorResolver)
             .register(registrar, toolNamePrefix)
     }
@@
     if (perms.isToolEnabled(TapNodeTool.TOOL_NAME)) {
-        TapNodeTool(treeParser, elementFinder, actionExecutor, accessibilityServiceProvider, nodeCache)
-            .register(registrar, toolNamePrefix)
+        TapNodeTool(
+            treeParser,
+            elementFinder,
+            actionExecutor,
+            accessibilityServiceProvider,
+            nodeCache,
+            selectorResolver,
+        ).register(registrar, toolNamePrefix)
     }
```

**Action 4 — modify** `.../test/mcp/tools/NodeActionToolsTest.kt`: add the class-level
`private val selectorResolver = NodeSelectorResolver(mockElementFinder, PrivacyToolTestDoubles.passthroughGate(),
PrivacyToolTestDoubles.identitySubstitutor())` and pass it as the last constructor argument at `ClickNodeTool(...)`
(line 306) and `TapNodeTool(...)` (lines 370-376); these two fixture edits are the only changes to existing tests.
Appending the argument to the one-line `ClickNodeTool(...)` call would make it 128 characters, so it is wrapped:

```diff
         private val tool =
-            ClickNodeTool(mockTreeParser, mockActionExecutor, mockAccessibilityServiceProvider, mockNodeCache)
+            ClickNodeTool(
+                mockTreeParser,
+                mockActionExecutor,
+                mockAccessibilityServiceProvider,
+                mockNodeCache,
+                selectorResolver,
+            )
```

`mockElementFinder` is strict: each selector test stubs `findElements(sampleWindows, FindBy.TEXT, "7", false)` (or its
exact args). New imports (on top of `io.mockk.verify` from Task 1.2 and `io.mockk.coVerify` from Task 2.5):
`android.util.Log`, `io.mockk.mockkStatic`, `io.mockk.slot`, `io.mockk.unmockkStatic`.

`ClickNodeToolTests` additions:

| Test | Verifies |
|------|----------|
| `clicks node resolved by selector` | `{by:"text", value:"7"}`, `findElements` → `[sampleElementInfo]`; `coVerify { clickNode("node_abc", sampleWindows) }`; text `"Click performed on node 'node_abc'"` |
| `selector reads the tree exactly once` | `verify(exactly = 1)` on `getAccessibilityWindows()` and on `parseTree(any(), any(), any())` |
| `ambiguous selector returns untrusted error and does not click` | `findElements` → `[sampleElementInfo, sampleElementInfo.copy(id = "node_def")]`; `isError == true`; starts with `UNTRUSTED_CONTENT_WARNING`; `coVerify(exactly = 0) { clickNode(any(), any()) }` |
| `selector with only off-screen match throws NodeNotFound` | `[sampleElementInfo.copy(visible = false)]` → message contains `"scroll_to_node"` |
| `invalid selector params rejected before tree read` | `{by:"text"}` → `InvalidParams`; `verify(exactly = 0) { getAccessibilityWindows() }` |
| `node_id plus selector rejected` | `InvalidParams` containing `"not both"` |
| `not clickable selector target maps to ActionFailed` | `clickNode` → `Result.failure(IllegalStateException("Node 'node_abc' is not clickable"))` → `McpToolException.ActionFailed` |
| `selector success log omits selector value` | `{by:"text", value:"Seven-Label"}` (stub `findElements(sampleWindows, FindBy.TEXT, "Seven-Label", false)` → `[sampleElementInfo]`). **Setup**: `mockkStatic(Log::class)`; `every { Log.d(any<String>(), capture(debugLines)) } returns 0`; `unmockkStatic(Log::class)` in `finally`. No captured line contains `"Seven-Label"` |

`TapNodeToolTests` additions:

| Test | Verifies |
|------|----------|
| `taps within bounds of node resolved by selector` | `slot<Float>()` for x and y via `coEvery { tap(capture(x), capture(y)) }`: both inside `sampleBounds` inset 5%; text ends with `"within node 'node_abc'"` |
| `selector path does not call findNodeById` | `verify(exactly = 0) { mockElementFinder.findNodeById(any<List<WindowData>>(), any()) }` |
| `selector reads the tree exactly once` | `verify(exactly = 1)` on `getAccessibilityWindows()` |
| `ambiguous selector returns untrusted error and does not tap` | `isError == true`; warning first; `coVerify(exactly = 0) { tap(any(), any()) }` |
| `invalid inset with selector rejected before tree read` | `{by, value, inset_percentage: 50}` → `InvalidParams`; `verify(exactly = 0) { getAccessibilityWindows() }` |
| `node_id plus selector rejected` | `InvalidParams` containing `"not both"` |

**Definition of Done:**
- [ ] `ClickNodeTool` has 5 constructor params, `TapNodeTool` 6; `TapNodeTool.execute` no longer carries
      `@Suppress("ThrowsCount")`.
- [ ] Both schemas use `putNodeTargetProperties` with `required = emptyList()`; `node_id` paths keep their messages
      and success texts.
- [ ] `registerNodeActionTools` builds one resolver; both tables added.

### Task 3.4 — JVM integration coverage

**Action 1 — modify** `.../test/integration/NodeActionIntegrationTest.kt`: reuse `sampleTree` (lines 34-52) and the
existing window mock setup.

**Setup**: `deps.elementFinder` is relaxed (`McpIntegrationTestHelper.kt:207`); each test stubs
`every { deps.elementFinder.findElements(any<List<WindowData>>(), FindBy.TEXT, "OK", false) }` explicitly. New
imports: `com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils` (`UNTRUSTED_CONTENT_WARNING`; only
`stripUntrustedWarning` is imported today), `io.mockk.coVerify`, `io.mockk.slot`.

| Test | Verifies |
|------|----------|
| `click_node by selector clicks the unique on-screen match` | One visible `ElementInfo(id="node_btn", visible=true, …)`; `coVerify { actionExecutor.clickNode("node_btn", any()) }`; `isError != true` |
| `click_node by selector with two on-screen matches returns untrusted error with candidates` | Starts with `UNTRUSTED_CONTENT_WARNING`; JSON from `stripUntrustedWarning` has `match_count == 2`, 2 `candidates` |
| `click_node by selector ignores off-screen matches` | Only a `visible=false` match → `isError == true`; text contains `"scroll_to_node"` |
| `tap_node by selector taps inside resolved bounds` | Captured `tap` slots inside `BoundsData(50, 800, 250, 1000)` |
| `tap_node with node_id and selector returns invalid params error` | `isError == true`; contains `"not both"` |
| `click_node by selector passes the two-window tree and the stubbed id to clickNode` | `MultiWindowScenarios` with `setupTwoWindowMock` (lines 229-263); the relaxed `findElements` stub returns the dialog-window node. Limitation: the finder is stubbed, so this row checks only that the stubbed id and the two-window list reach `clickNode`; real multi-window matching is covered by `NodeSelectorResolverTest` `resolves a unique match in a second window` |
| `tools list exposes selector params for click_node and tap_node` | `listTools`: both have `node_id`, `by`, `value`, `exact_match`; `required` is empty |

**Action 2 — modify** `.../test/integration/PrivacyModeIntegrationTest.kt`: mirror `find_nodes substitutes placeholder
argument` (lines 272-297).

**Setup**: `McpIntegrationTestHelper.setPrivacy(deps, deterministicEnabled, PrivacyModeStatus.ReadyDeterministicOnly)`.
New imports: `com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementInfo` (ambiguous row fixtures)
(`slot`, `WindowData`, `BoundsData` exist).

| Test | Verifies |
|------|----------|
| `tap_node selector substitutes placeholder argument` | Captured `value` slot of `findElements` is the real email; the agent sent the placeholder |
| `click_node ambiguous selector candidates are redacted` | Two visible `ElementInfo` whose `text` holds an email → result contains `"EMAIL#"`, not the raw email; `isError == true` |
| `click_node selector not found echoes no selector value` | No match → `isError == true`; the error text contains neither the placeholder nor the real email |

**Definition of Done:**
- [ ] All rows added; `ErrorHandlingIntegrationTest`, `ToolPermissionsIntegrationTest` and
      `McpProtocolIntegrationTest` are not modified.

### Task 3.5 — E2E selector flow (CI only)

**Action 1 — modify** `e2e-tests/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/e2e/E2ECalculatorTest.kt`.

Facts read from the bundled `simple-calculator.apk` (`resources.arsc` + its single main layout): resource package
`com.simplemobiletools.calculator`; the display is the `TextView` `id/result` (formula in `id/formula`); `id/btn_clear`
is a visible `Button` with text `C`; `id/btn_reset` (`AC`) is `visibility=gone`. `launchCalculator` uses `am start`
without a force-stop, so the display left by `@Order(2)` ("10") may still be shown, and what a digit pressed after `=`
does to it is not verified. The test therefore clears the display first and asserts on the result node exactly, never
on the whole screen text (a `contains("13")` check would accept "113" or any other text holding "13").

Companion constants: `RESULT_ID = "$CALCULATOR_PACKAGE:id/result"`, `CLEAR_ID = "$CALCULATOR_PACKAGE:id/btn_clear"`,
`MAX_CLEAR_PRESSES = 6`. New imports: `org.junit.jupiter.api.Assertions.assertEquals`, `org.junit.jupiter.api.fail`
(the Kotlin `fail(message): Nothing`). Two private helpers next to `findNodeWithRetry`:

| Helper | Contract |
|--------|----------|
| `clickBySelectorWithRetry(by: String, value: String)` | Calls `click_node` with `{"by": by, "value": value, "exact_match": true}`; returns on the first result with `isError != true`. Retries ONLY on an `isError == true` result: keeps its text as `lastText`, `Thread.sleep(500)`, until `NODE_WAIT_TIMEOUT_MS` (same deadline and interval as `findNodeWithRetry`); safe because every `isError` path of `click_node` (no match, ambiguous selector, not clickable, click failed) performs no click. Exceptions are NOT caught (unlike `findNodeWithRetry`): a thrown transport error may follow a click the server already performed, so a retry could press twice. Timeout: `fail("click_node $by='$value' did not succeed within $NODE_WAIT_TIMEOUT_MS ms; last: $lastText")` |
| `resultText(): String` | Calls `find_nodes` with `{"by": "resource_id", "value": RESULT_ID, "exact_match": true}`; `isError == true` → `fail("find_nodes for the result failed: <text>")`; parses `stripUntrustedWarning(text)`; `assertEquals(1, nodes.size, …)`; returns that node's `text` (`contentOrNull ?: ""`) |

Then add `@Test @Order(4) fun \`calculate 8 plus 5 via click_node selector\`()`:
1. Same setup as `@Order(2)` (lines 101-112: `press_home`, `launchCalculator`, `waitForAppVisible`).
2. Clear: up to `MAX_CLEAR_PRESSES` times, stop when `resultText()` is `""` or `"0"`, otherwise
   `clickBySelectorWithRetry("resource_id", CLEAR_ID)` and `Thread.sleep(500)`. Then
   `assertTrue(resultText() in setOf("", "0"), "display not cleared: '<text>'")` (works whether `C` clears the whole
   display or one character).
3. `clickBySelectorWithRetry("text", X)` for `8`, `+`, `5`, `=`, each followed by the same `Thread.sleep` as
   `@Order(2)` (500 ms; 1 000 ms after `=`); then `wait_for_idle` (`timeout` 3000).
4. `assertEquals("13", resultText(), …)`.

After the clear no digit is pressed while that digit is alone on the display (which would make the `text` selector
ambiguous); the clear button is selected by `resource_id`, which is unique.

| Test | Verifies |
|------|----------|
| `calculate 8 plus 5 via click_node selector` | Chosen approach: clear the display first (`btn_clear` by `resource_id` selector, confirmed `""`/`"0"`), then exact equality of the `id/result` node text with `"13"` (a wrong or appended result such as `"113"` fails). Every selector click succeeds within the retry deadline (otherwise `fail` prints the last result text, so ambiguity candidates show) |

**Definition of Done:**
- [ ] Test and helpers added; it runs only through `make test-e2e` in CI (redroid + rootful podman).

### Task 3.6 — Documentation for US3

**Action 1 — modify** `docs/MCP_TOOLS.md`, section ``### `android_click_node` `` (line 1113): replace the section body (from the description line
after the heading's blank line through the last **Error Cases** bullet, keeping the blank lines around it and the `---`
before ``### `android_long_click_node` `` (line 1149)) with:

````markdown
Click an accessibility node by `node_id`, or by selector (`by` + `value` [+ `exact_match`]). Provide exactly one form. A selector is resolved among on-screen (visible) nodes only, across all windows, from a single tree read; a selector matching several on-screen nodes returns an error listing the candidates.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "node_id": { "type": "string", "description": "Node ID from find_nodes. Mutually exclusive with 'by' + 'value'" },
    "by": { "type": "string", "enum": ["text", "content_desc", "resource_id", "class_name"], "description": "Selector criteria type; use with 'value' instead of 'node_id'" },
    "value": { "type": "string", "description": "Selector value; use with 'by'" },
    "exact_match": { "type": "boolean", "default": false, "description": "Selector only. If true, match exactly. If false, match contains (case-insensitive)" }
  },
  "required": []
}
```

**Output**: `"Click performed on node '<node_id>'"` (with a selector, `<node_id>` is the resolved node)

**Example** (by node ID):
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "android_click_node", "arguments": { "node_id": "node_abc123" } }
  }'
```

**Example** (by selector):
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "android_click_node", "arguments": { "by": "text", "value": "OK", "exact_match": true } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: both `node_id` and a selector; neither; a selector missing `by` or `value`; empty `node_id` or `value`; invalid `by`; `exact_match` together with `node_id`
- **Permission denied**: Accessibility service not enabled
- **Node not found**: Node not found in accessibility tree; with a selector, no on-screen node matches (the message lists up to 5 matching off-screen `node_id`s, if any, and suggests `android_scroll_to_node`)
- **Ambiguous selector**: more than one on-screen node matches; no action is performed. The text starts with the untrusted-content warning, followed by `{"error":"ambiguous_selector","match_count":<n>,"candidates":[<up to 5 nodes in find_nodes format, Privacy Mode redacted>],"hint":"..."}`. With Privacy Mode on, every match of the selector (off-screen included) is redacted before the candidates are listed, so a broad selector costs as much as the same `android_find_nodes` call: prefer `exact_match=true` or a `resource_id` selector.
- **Action failed**: Node is not clickable or click action failed
````

**Action 2 — modify** `docs/MCP_TOOLS.md`, section ``### `android_tap_node` `` (line 1185): replace the section body (from the description line
after the heading's blank line through the last **Error Cases** bullet, keeping the blank lines around it and the `---`
before ``### `android_scroll_to_node` `` (line 1222)) with:

````markdown
Performs a gesture-based tap at a random point within the bounds of a node, identified by `node_id` or by selector (`by` + `value` [+ `exact_match`]). Provide exactly one form. A selector is resolved among on-screen (visible) nodes only, across all windows, from a single tree read; a selector matching several on-screen nodes returns an error listing the candidates. Unlike `click_node` (which uses the accessibility ACTION_CLICK), this performs a coordinate-based touch gesture. The tap point is randomized within the node bounds, inset by a configurable percentage (default 5%) from each edge to avoid hitting borders.

**Input Schema**:
```json
{
  "type": "object",
  "properties": {
    "node_id": { "type": "string", "description": "Node ID from find_nodes. Mutually exclusive with 'by' + 'value'" },
    "by": { "type": "string", "enum": ["text", "content_desc", "resource_id", "class_name"], "description": "Selector criteria type; use with 'value' instead of 'node_id'" },
    "value": { "type": "string", "description": "Selector value; use with 'by'" },
    "exact_match": { "type": "boolean", "default": false, "description": "Selector only. If true, match exactly. If false, match contains (case-insensitive)" },
    "inset_percentage": { "type": "number", "default": 5.0, "description": "Percentage to inset from each edge of the node bounds (0.0-45.0). Default 5.0" }
  },
  "required": []
}
```

**Output**: `"Tap executed at (<x>, <y>) within node '<node_id>'"` (with a selector, `<node_id>` is the resolved node)

**Example** (by node ID):
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "android_tap_node", "arguments": { "node_id": "node_abc123" } }
  }'
```

**Example** (by selector):
```bash
curl -X POST http://localhost:8080/mcp \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0", "id": 1, "method": "tools/call",
    "params": { "name": "android_tap_node", "arguments": { "by": "text", "value": "OK", "exact_match": true } }
  }'
```

**Error Cases** (returned as `CallToolResult(isError = true)`):
- **Invalid params**: both `node_id` and a selector; neither; a selector missing `by` or `value`; empty `node_id` or `value`; invalid `by`; `exact_match` together with `node_id`; `inset_percentage` out of range (0.0-45.0)
- **Permission denied**: Accessibility service not enabled
- **Node not found**: Node not found in accessibility tree; with a selector, no on-screen node matches (the message lists up to 5 matching off-screen `node_id`s, if any, and suggests `android_scroll_to_node`)
- **Ambiguous selector**: more than one on-screen node matches; no tap is performed. Same result format and Privacy Mode cost as for `android_click_node`.
- **Action failed**: Tap gesture failed
````

**Action 3 — modify** `docs/MCP_TOOLS.md`, `### Response Format (Tool Error)`: insert after its JSON block (before
`### Anti-Prompt-Injection Warning`):

```markdown
Exception: the ambiguous-selector error of `android_click_node` / `android_tap_node` lists device-derived node text, so its text starts with the untrusted-content warning (see below).
```

**Action 4 — modify** `docs/MCP_TOOLS.md`, `### Anti-Prompt-Injection Warning`: insert after the line "This warning is
NOT present in pure action confirmations (tap, click, swipe, etc.)." (as its own paragraph):

```markdown
The one error result that carries it is the ambiguous-selector error of `android_click_node` / `android_tap_node`, which lists candidate node text.
```

**Action 5 — modify** `docs/PROJECT.md`:

```diff
-  - `mcp/tools/` — `McpToolUtils.kt`, `TreeFingerprint.kt`, `ScreenIntrospectionTools.kt`, `TouchActionTools.kt`, `NodeActionTools.kt`, `TextInputTools.kt`, `SystemActionTools.kt`, `GestureTools.kt`, `UtilityTools.kt`, `FileTools.kt`, `AppManagementTools.kt`, `CameraTools.kt`, `LocationTools.kt`
+  - `mcp/tools/` — `McpToolUtils.kt`, `TreeFingerprint.kt`, `ScreenIntrospectionTools.kt`, `TouchActionTools.kt`, `NodeActionTools.kt`, `NodeSelectorResolver.kt`, `TextInputTools.kt`, `SystemActionTools.kt`, `GestureTools.kt`, `UtilityTools.kt`, `FileTools.kt`, `AppManagementTools.kt`, `CameraTools.kt`, `LocationTools.kt`
```

```diff
-| `android_click_node` | Click an accessibility node | `node_id` (string) | — |
+| `android_click_node` | Click an accessibility node | `node_id` (string) OR selector `by` (string: text/content_desc/resource_id/class_name) + `value` (string) | `exact_match` (boolean, default false; selector only) |
```

```diff
-| `android_tap_node` | Gesture-based tap at a random point within node bounds (unlike `android_click_node`, which uses ACTION_CLICK) | `node_id` (string) | `inset_percentage` (number: 0.0-45.0, default 5.0) |
+| `android_tap_node` | Gesture-based tap at a random point within node bounds (unlike `android_click_node`, which uses ACTION_CLICK) | `node_id` (string) OR selector `by` (string: text/content_desc/resource_id/class_name) + `value` (string) | `exact_match` (boolean, default false; selector only), `inset_percentage` (number: 0.0-45.0, default 5.0) |
```

```diff
-**Errors**: Returns `CallToolResult(isError = true)` if node not found (ID invalid or stale) or node not clickable. `android_find_nodes` returns empty array (not error) when no matches found.
+**Errors**: Returns `CallToolResult(isError = true)` if node not found (ID invalid or stale) or node not clickable. `android_find_nodes` returns empty array (not error) when no matches found. `android_click_node`/`android_tap_node` with a selector return an error when no on-screen node matches, or an untrusted-warning error listing candidates when more than one on-screen node matches.
```

**Definition of Done:**
- [ ] Both docs updated as listed; the documented schemas match `putNodeTargetProperties` and the `inset_percentage`
      block (the docs keep their existing unprefixed `find_nodes` wording).

---

## User Story 4 — Relay and tunnel timing logs (measurement only)

**Why:** the per-call time between relay and phone has no known cause (see Context). One relay line and one phone
line per request, joined by `rid`, attribute that time; no other behaviour changes.

**Acceptance criteria:**
- [ ] Every relay HTTP request that reaches the relay's request handler, except `/relay/health`, logs exactly one
      `relay: request …` line when its response closes: answered, refused (400 malformed target, 408 body timeout,
      413, 503), failed (502, 504, and the `createServer` 500 fallback) or client-aborted (`aborted=1`). Not logged:
      the `/device` WebSocket upgrade, and requests that Node's HTTP parser rejects before any handler runs
      (`clientError` 400, Node's own `server.requestTimeout` 408). The 500 fallback has no automated test (no
      fault-injection seam; adding one would change relay code beyond measurement): it is verified by reading the
      diff in Q.1 Action 6. A failure while formatting or writing the timing line never escapes the `close`
      listener: it logs the constant warning `relay: timing log failed` instead.
- [ ] The relay line holds `rid`, `method`, sanitized `path`, `rpc`, optional `tool`, `status`, `reqBytes`,
      `resBytes`, `inflight`, `idleMs`, `sentAt`, `bodyMs`, `deviceMs`, `totalMs`; a phase that never ran prints `-`.
- [ ] Every relayed request frame whose replay completes and whose response frame is encoded logs exactly one
      `MCP:RelaySession` info line `Relay request rid=<8> <METHOD> <sanitized path> status=… recvAt=… queueMs=…
      replayMs=… totalMs=…`, with ` sent=false` when the frame could not be sent; the busy 503 path included.
      Not logged: frames whose replay is cancelled because the socket went away (`replayScope.cancel()`), and frames
      whose replay or encoding throws. Logcat only, never the in-app server log. Id, `method` and path go through
      `RelayTiming.logSafe` (Unicode control, line/paragraph-separator and bidi characters → `?`); the path is cut at
      128 characters, as on the relay.
- [ ] Neither line ever contains header names or values (Authorization included), query strings, bodies, JSON-RPC
      params or arguments, client IP or exception messages; `/s/` paths print as `/s/<redacted>`.
- [ ] Status codes, response headers, bodies, limits and the frame format are unchanged. The only edits to existing
      relay and app tests are the `startRelay` / `extraRelay` logger parameter (Task 4.3 Actions 1-2) and the
      extension of `RelayTunnelProviderTest` `ninth concurrent request gets 503 while eight are in flight` with a
      `Log.i` capture and one assertion (Task 4.5 Action 2).

### Task 4.1 — Relay log helpers

**Action 1 — create** `relay/src/requestLog.js`

```js
/**
 * Log-safe pieces of the per-request timing line. Pure: these functions never receive the request object or its
 * headers. `rpcSummary` reads only the top-level `method` and `params.name` of the body, regex-filtered, so no
 * argument, param or id reaches the line.
 */

export const RPC_SNIFF_MAX_BYTES = 64 * 1024;
/** Characters of the frame id logged as `rid`; the phone logs the same prefix (a full UUID is redacted there). */
export const RID_LENGTH = 8;

const MAX_PATH_CHARS = 128;
const CAPABILITY_PREFIX = '/s/';
const RPC_METHOD = /^[A-Za-z][A-Za-z0-9_./-]{0,63}$/;
const TOOL_NAME = /^[A-Za-z0-9_.-]{1,128}$/;
const NOT_INSPECTED = Object.freeze({ rpc: '-', tool: null });
const UNREADABLE = Object.freeze({ rpc: '?', tool: null });

export function shortId(id) {
  return id.slice(0, RID_LENGTH);
}

/** `pathname` is WHATWG-parsed (no query, percent-encoded); capability links are redacted like on the phone. */
export function safePath(pathname) {
  if (pathname.startsWith(CAPABILITY_PREFIX)) return `${CAPABILITY_PREFIX}<redacted>`;
  return pathname.length > MAX_PATH_CHARS ? `${pathname.slice(0, MAX_PATH_CHARS)}…` : pathname;
}

/**
 * JSON-RPC method (and tool name for `tools/call`) of a `POST /mcp` JSON body of at most RPC_SNIFF_MAX_BYTES.
 * Any other request (OAuth `/token` and `/register` bodies included) is never parsed: `rpc` is `-`.
 * Unparseable or unsafe values give `?`; a batch gives `batch`. Params, arguments and the JSON-RPC id are never read.
 */
export function rpcSummary(input) {
  if (input === null) return NOT_INSPECTED;
  const { method, pathname, contentType, body } = input;
  if (method !== 'POST' || pathname !== '/mcp') return NOT_INSPECTED;
  const mediaType = (contentType ?? '').split(';')[0].trim().toLowerCase();
  if (mediaType !== 'application/json' || body.length > RPC_SNIFF_MAX_BYTES) return NOT_INSPECTED;
  let message;
  try {
    message = JSON.parse(body.toString('utf8'));
  } catch {
    return UNREADABLE;
  }
  if (Array.isArray(message)) return { rpc: 'batch', tool: null };
  const rpc = typeof message?.method === 'string' && RPC_METHOD.test(message.method) ? message.method : '?';
  const name = message?.params?.name;
  const tool = rpc === 'tools/call' && typeof name === 'string' && TOOL_NAME.test(name) ? name : null;
  return { rpc, tool };
}

const ms = (value) => (value === null ? '-' : value.toFixed(1));

/** One log line; `null` fields are phases the request never reached and print as `-`. */
export function formatRequestLog({
  rid, method, path, rpc, tool, status, reqBytes, resBytes, inflight, idleMs, sentAt, bodyMs, deviceMs, totalMs,
  aborted,
}) {
  const parts = ['relay: request', `rid=${rid}`, `method=${method}`, `path=${path}`, `rpc=${rpc}`];
  if (tool !== null) parts.push(`tool=${tool}`);
  parts.push(
    `status=${status ?? '-'}`,
    `reqBytes=${reqBytes}`,
    `resBytes=${resBytes}`,
    `inflight=${inflight}`,
    `idleMs=${idleMs ?? '-'}`,
    `sentAt=${sentAt ?? '-'}`,
    `bodyMs=${ms(bodyMs)}`,
    `deviceMs=${ms(deviceMs)}`,
    `totalMs=${ms(totalMs)}`,
  );
  if (aborted) parts.push('aborted=1');
  return parts.join(' ');
}
```

**Action 2 — modify** `relay/package.json`, `lint` script: add the new module.

```diff
-    "lint": "node --check src/config.js && node --check src/frames.js && node --check src/relay.js && node --check src/server.js",
+    "lint": "node --check src/config.js && node --check src/frames.js && node --check src/relay.js && node --check src/requestLog.js && node --check src/server.js",
```

**Definition of Done:**
- [ ] `requestLog.js` has no I/O and no imports; it is in the `lint` script.

### Task 4.2 — Relay per-request timing

**Action 1 — modify** `relay/src/relay.js`: imports (lines 1-4).

```diff
 import { createServer } from 'node:http';
 import { randomUUID, timingSafeEqual } from 'node:crypto';
+import { performance } from 'node:perf_hooks';
 import { WebSocketServer } from 'ws';
 import { FRAME_TYPES, decodeDeviceFrame, encodePing, encodeRequestFrame } from './frames.js';
+import { formatRequestLog, rpcSummary, safePath, shortId } from './requestLog.js';
```

**Action 2 — modify** `relay/src/relay.js`, `forward` (lines 208-212): the id comes from the caller.

```diff
-  function forward(req, url, body) {
+  function forward(req, url, body, id) {
     if (device === null) {
       return Promise.reject(new RelayError(503, 'device not connected'));
     }
-    const id = randomUUID();
     const frame = encodeRequestFrame({
```

**Action 3 — modify** `relay/src/relay.js`, `writeResponse` (lines 254-265): return the body bytes sent.

```diff
-  function writeResponse(req, res, frame) {
+  /** Returns the body bytes sent (0 for HEAD, 204 and 304). */
+  function writeResponse(req, res, frame) {
     for (const [name, values] of Object.entries(frame.headers)) {
       if (!HOP_BY_HOP_HEADERS.has(name.toLowerCase())) res.setHeader(name, values);
     }
     res.statusCode = frame.status;
     if (req.method === 'HEAD' || NO_BODY_STATUSES.has(frame.status)) {
       res.end();
-      return;
+      return 0;
     }
     res.setHeader('content-length', frame.body.length);
     res.end(frame.body);
+    return frame.body.length;
   }
```

**Action 4 — modify** `relay/src/relay.js`, `sendJson` (lines 331-337): return the body bytes sent.

```diff
+/** Returns the body bytes sent. */
 function sendJson(res, status, payload, closeConnection = false) {
   const body = Buffer.from(JSON.stringify(payload), 'utf8');
   const headers = { 'content-type': 'application/json', 'content-length': body.length };
   if (closeConnection) headers.connection = 'close';
   res.writeHead(status, headers);
   res.end(body);
+  return body.length;
 }
```

**Action 5 — modify** `relay/src/relay.js`: replace `handleHttp` (lines 177-206) with `startTiming` plus the timed
`handleHttp`.

```js
  /**
   * Starts the timing record of one request and logs it once, when its response closes: answered, refused, failed
   * (the createServer 500 included, with resBytes=0) or abandoned by the client (`aborted=1`; phases still running
   * print `-`). `totalMs`, status and abort state are read first; JSON-RPC sniffing runs after them, so it never
   * adds to the measured latency. A failure while building or writing the line is contained here: a missing log
   * line must never crash the relay.
   */
  function startTiming(req, res, url) {
    const timing = {
      rid: '-',
      method: req.method,
      path: url === null ? '-' : safePath(url.pathname),
      reqBytes: 0,
      resBytes: 0,
      inflight: inFlight,
      idleMs: null,
      sentAt: null,
      bodyMs: null,
      deviceMs: null,
      rpcInput: null,
    };
    const startedAt = performance.now();
    res.once('close', () => {
      const totalMs = performance.now() - startedAt;
      const status = res.headersSent ? res.statusCode : null;
      const aborted = !res.writableFinished;
      try {
        const { rpcInput, ...fields } = timing;
        logger.info(formatRequestLog({ ...fields, ...rpcSummary(rpcInput), status, totalMs, aborted }));
      } catch {
        logger.warn('relay: timing log failed');
      }
    });
    return timing;
  }

  async function handleHttp(req, res) {
    const url = parseTarget(req.url);
    if (url !== null && url.pathname === HEALTH_PATH) {
      sendJson(res, 200, { status: 'ok', deviceConnected: device !== null });
      return;
    }
    const timing = startTiming(req, res, url);
    if (url === null) {
      timing.resBytes = sendJson(res, 400, { error: 'invalid request target' }, true);
      return;
    }
    try {
      if (device === null) throw new RelayError(503, 'device not connected');
      if (inFlight >= config.maxPending) throw new RelayError(503, 'too many pending requests');
      inFlight += 1;
      try {
        const bodyStart = performance.now();
        let body;
        try {
          body = await readBody(req, config.maxBodyBytes, config.bodyTimeoutMs);
        } finally {
          timing.bodyMs = performance.now() - bodyStart;
        }
        timing.reqBytes = body.length;
        timing.rpcInput = { method: req.method, pathname: url.pathname, contentType: req.headers['content-type'], body };
        const id = randomUUID();
        timing.rid = shortId(id);
        timing.sentAt = Date.now();
        timing.idleMs = device === null ? null : timing.sentAt - device.lastSeen;
        const deviceStart = performance.now();
        let response;
        try {
          response = await forward(req, url, body, id);
        } finally {
          timing.deviceMs = performance.now() - deviceStart;
        }
        if (response.body.length > config.maxResponseBytes) throw new RelayError(502, 'device response too large');
        timing.resBytes = writeResponse(req, res, response);
      } finally {
        inFlight -= 1;
      }
    } catch (err) {
      if (err instanceof RelayError) {
        timing.resBytes = sendJson(res, err.status, { error: err.message }, CLOSE_CONNECTION_STATUSES.has(err.status));
        return;
      }
      throw err;
    }
  }
```

**Definition of Done:**
- [ ] The frame `id` is still a full `randomUUID()`, generated once per forwarded request.
- [ ] `formatRequestLog` receives only scalars (never `req`, `req.headers`, the frame or an error message).
- [ ] The `close` listener reads `totalMs`, `status` and `aborted` before calling `rpcSummary`.
- [ ] `/relay/health` and every status code, header and body are unchanged.

### Task 4.3 — Relay tests

**Action 1 — modify** `relay/test/helpers.js` (shared infrastructure, in full):

```diff
 const silentLogger = { info() {}, warn() {}, error() {} };
+
+/** Logger that keeps every line (all levels) in `lines`. */
+export function recordingLogger() {
+  const lines = [];
+  const record = (message) => {
+    lines.push(message);
+  };
+  return { lines, info: record, warn: record, error: record };
+}

 /** Starts a relay on an ephemeral loopback port. */
-export async function startRelay(overrides = {}) {
-  const relay = createRelay({ ...TEST_CONFIG, ...overrides }, silentLogger);
+export async function startRelay(overrides = {}, logger = silentLogger) {
+  const relay = createRelay({ ...TEST_CONFIG, ...overrides }, logger);
```

**Action 2 — modify** `relay/test/relay.test.js`: `extraRelay(overrides)` becomes `extraRelay(overrides, logger)` calling
`startRelay(overrides, logger)`; import `recordingLogger`; add
`const timingLines = (logger) => logger.lines.filter((line) => line.startsWith('relay: request '));`.

**Action 3 — create** `relay/test/requestLog.test.js`

| Test | Verifies |
|------|----------|
| `shortId keeps the first 8 characters` | UUID in, 8-char prefix out |
| `safePath redacts capability links` | `/s/abc` → `/s/<redacted>`; `/mcp` unchanged |
| `safePath truncates long paths` | 300-char path → 128 chars + `…` |
| `rpcSummary reads method and tool of a tools/call` | POST `/mcp`, `application/json; charset=utf-8`, `{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tap_node","arguments":{"text":"secret-arg"}}}` → `{ rpc: 'tools/call', tool: 'tap_node' }` |
| `rpcSummary reports initialize without tool` | `initialize` → tool `null` |
| `rpcSummary is not inspected without input` | `null` → `{ rpc: '-', tool: null }` |
| `rpcSummary ignores other paths and methods` | POST `/token` and `/register` with JSON bodies, GET `/mcp` → `-` |
| `rpcSummary ignores non-JSON content types` | `text/plain`, `application/x-www-form-urlencoded`, missing → `-` |
| `rpcSummary skips bodies over the sniff limit` | `Buffer.alloc(RPC_SNIFF_MAX_BYTES + 1)` → `-` |
| `rpcSummary maps invalid JSON to ?` | `{` and `null` → `?` |
| `rpcSummary rejects unsafe method and tool names` | Method with a space, `\n`, or 65 chars → `?`; tool `a\nb` → tool `null` |
| `rpcSummary reports batches` | `[{"method":"tools/list"}]` → `batch` |
| `formatRequestLog prints - for phases that did not run` | `null` status, idleMs, sentAt, bodyMs, deviceMs → `-` each |
| `formatRequestLog adds tool and aborted only when set` | `tool: null, aborted: false` → neither token; set → `tool=x`, `aborted=1` |
| `formatRequestLog writes one line with one-decimal durations` | No `\n`; `totalMs=12.3` for 12.34 |

**Action 4 — modify** `relay/test/relay.test.js`: add `describe('timing log')` NESTED inside the existing
`describe('relay')` (just before its closing `});`, line 539), so its rows use that block's `device(...)`,
`extraRelay(...)` closures and its shared `beforeEach` / `afterEach` cleanup.

**Setup**: `const logger = recordingLogger(); const extra = await extraRelay({}, logger);` and devices from
`device(extra.wsUrl)`. Wait with `eventually(() => timingLines(logger).length === n, DEADLINE_MS, …)`: the response
can close after `fetch` resolves, so never assert synchronously.

| Test | Verifies |
|------|----------|
| `proxied request logs one timing line` | POST `/mcp` `tools/call` body for `tap_node`: one line with `rid=<frame.id.slice(0, 8)>`, `method=POST`, `path=/mcp`, `rpc=tools/call`, `tool=tap_node`, `status=200`, `reqBytes=<sent length>`, `resBytes=<response body length>`, `inflight=0`, numeric `idleMs`, `sentAt`, `bodyMs`, `deviceMs`, `totalMs` |
| `timing line keeps secrets out` | Header `authorization: Bearer secret-client-token`, target `/mcp?code=secret-code`, body argument `secret-arg`: no recorded line contains `secret`; the timing line does not contain `127.0.0.1` |
| `capability path is redacted` | GET `/s/secret-token` answered by the device → `path=/s/<redacted>`, `rpc=-` |
| `deviceMs covers the device's answer time` | Device answers after `sleep(60)` → parsed `deviceMs` ≥ 50 |
| `request without device is logged with 503` | No device → `status=503`, `rid=-`, `bodyMs=-`, `deviceMs=-` |
| `device timeout is logged with 504` | Device never answers → `status=504`, numeric `deviceMs` |
| `health checks are not logged` | GET `/relay/health` → no timing line after the response plus `sleep(50)` |
| `malformed target is logged with 400` | `rawRequest` with `GET http://[/x` (as in the existing 400 test) → `status=400`, `path=-` |
| `client abort is flagged` | `openSocket` POST `/mcp` (`Content-Length: 2`, body `{}`); await the frame; `socket.destroy()` → a line with `aborted=1`, `status=-`, `deviceMs=-`; then `respond(ws, frame.id)` and `sleep(50)`: still exactly one line |
| `oversized body is logged with 413` | `await device(extra.wsUrl)` first, then `rawRequest(extra.port, …)` with the chunked 2048-byte body of `body over the limit returns 413 and closes the connection` → `status=413`, `rid=-`, numeric `bodyMs`, `deviceMs=-` |
| `stalled upload is logged with 408` | `const strict = await extraRelay({ maxPending: 1, bodyTimeoutMs: 150 }, logger); await device(strict.wsUrl);` (without a device the relay answers 503 before reading the body), then `rawRequest(strict.port, STALLED_UPLOAD)` → `status=408`, `rid=-`, parsed `bodyMs` ≥ 100, `deviceMs=-` |
| `oversized device response is logged with 502` | Device answers `{ body: 'a'.repeat(2049) }` as in `oversized device response returns 502` → `status=502`, `rid=<frame.id.slice(0, 8)>`, numeric `deviceMs`, `resBytes` = byte length of the 502 JSON body |
| `bodiless answers log resBytes=0` | As in `head and 204 responses carry no body`: HEAD `/x` answered with body `'ignored'`, then POST `/x` answered `{ status: 204, body: 'ignored' }` → two lines, `status=200` and `status=204`, both `resBytes=0` |
| `inflight counts requests already in flight` | `await device(extra.wsUrl)`; POST `/mcp` #1, await its frame (its `inFlight += 1` has run); POST `/mcp` #2, await its frame; `respond` to both (within the 300 ms `requestTimeoutMs`) → two lines: `rid=<frame 1 id prefix>` with `inflight=0`, `rid=<frame 2 id prefix>` with `inflight=1` (TEST_CONFIG `maxPending` is 2) |
| `timing log failure is contained` | Logger `{ lines, info(m) { if (m.startsWith('relay: request ')) throw new Error('boom'); lines.push(m); }, warn: push, error: push }`; two requests without a device → both get 503 (the relay process is still serving) and `lines` holds `relay: timing log failed` twice |

**Definition of Done:**
- [ ] All rows written; no existing relay test changes behaviour.

### Task 4.4 — Phone timing line

**Action 1 — create** `.../services/tunnel/RelayTiming.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

/**
 * One relayed request as measured on the device, logged by [RelaySession] under `MCP:RelaySession`.
 * Only scalars: never headers, body, query string or exception text.
 * - [queueMs]: frame received → replay started (UTF-8/JSON decode + dispatch).
 * - [replayMs]: loopback replay (auth, MCP SDK, tool, response packing); about 0 for the busy 503.
 * - [totalMs]: frame received → response handed to the socket's outgoing queue (wire transmission excluded).
 * - [receivedAtMs]: wall clock at receipt; comparable with the relay's `sentAt` only up to the clock skew.
 */
internal data class RelayTiming(
    val id: String,
    val method: String,
    val path: String,
    val status: Int,
    val receivedAtMs: Long,
    val queueMs: Long,
    val replayMs: Long,
    val totalMs: Long,
    val sent: Boolean,
) {
    /**
     * `rid` is the id's first [RID_LENGTH] characters, as on the relay: `Logger` redacts a full UUID. Frame-supplied
     * text (id, method, path) goes through [logSafe], so a malicious relay cannot forge logcat lines or reorder the
     * line on screen, and the path is cut at [MAX_PATH_CHARS] like the relay's.
     */
    fun logLine(): String {
        val cleanPath = logSafe(LocalRequestReplayer.sanitizedPath(path))
        val shownPath = if (cleanPath.length > MAX_PATH_CHARS) "${cleanPath.take(MAX_PATH_CHARS)}…" else cleanPath
        val line =
            "Relay request rid=${logSafe(id.take(RID_LENGTH))} ${logSafe(method)} $shownPath " +
                "status=$status recvAt=$receivedAtMs queueMs=$queueMs replayMs=$replayMs totalMs=$totalMs"
        return if (sent) line else "$line sent=false"
    }

    companion object {
        const val RID_LENGTH = 8
        const val MAX_PATH_CHARS = 128
        private val CONTROL_CHARS = Regex("[\\p{Cc}\\p{Zl}\\p{Zp}\\u202A-\\u202E\\u2066-\\u2069]")

        /**
         * Replaces with `?` every Unicode control character (C0 and C1: CR, LF, tab, U+0085 NEL, U+009B CSI, ...),
         * U+2028 / U+2029 and the bidi overrides and isolates (U+202A-U+202E, U+2066-U+2069).
         */
        internal fun logSafe(value: String): String = CONTROL_CHARS.replace(value, "?")
    }
}
```

**Action 2 — modify** `.../services/tunnel/RelaySession.kt`: class KDoc (lines 30-36), append one sentence.

```diff
  * Frame text (UTF-8 and JSON) is decoded and encoded on the Default dispatcher: bodies reach several MiB of
  * base64.
+ * Each request frame whose replay completes logs one info line built by [RelayTiming] (none when cancelled).
  */
```

**Action 3 — modify** `.../services/tunnel/RelaySession.kt`: replace `handleText`, `answer` and `sendFrame`
(lines 87-123).

```kotlin
    private suspend fun handleText(
        replayScope: CoroutineScope,
        frame: Frame.Text,
    ) {
        val receivedNs = System.nanoTime()
        val receivedAtMs = System.currentTimeMillis()
        when (val inbound = withContext(Dispatchers.Default) { RelayFrameCodec.decode(frame.readText()) }) {
            is RelayInbound.Request -> replayScope.launch { answer(inbound.frame, receivedNs, receivedAtMs) }
            RelayInbound.Ping -> replayScope.launch { sendFrame(Frame.Text(RelayFrameCodec.encodePong())) }
            RelayInbound.Pong -> Unit
            is RelayInbound.Unknown -> Logger.w(TAG, "Ignoring relay frame of unknown type '${inbound.type}'")
            is RelayInbound.Malformed -> Logger.w(TAG, "Ignoring malformed relay frame: ${inbound.reason}")
        }
    }

    private suspend fun answer(
        frame: RelayRequestFrame,
        receivedNs: Long,
        receivedAtMs: Long,
    ) {
        val replayStartNs = System.nanoTime()
        val response =
            if (replaySlots.tryAcquire()) {
                try {
                    replayer.replay(frame, localPort)
                } finally {
                    replaySlots.release()
                }
            } else {
                Logger.w(TAG, "Relay request refused: all replay slots are busy")
                replayer.errorFrame(frame.id, HttpStatusCode.ServiceUnavailable, "device busy")
            }
        val replayEndNs = System.nanoTime()
        val sent = sendFrame(withContext(Dispatchers.Default) { Frame.Text(RelayFrameCodec.encode(response)) })
        val timing =
            RelayTiming(
                id = frame.id,
                method = frame.method,
                path = frame.path,
                status = response.status,
                receivedAtMs = receivedAtMs,
                queueMs = (replayStartNs - receivedNs) / NANOS_PER_MILLI,
                replayMs = (replayEndNs - replayStartNs) / NANOS_PER_MILLI,
                totalMs = (System.nanoTime() - receivedNs) / NANOS_PER_MILLI,
                sent = sent,
            )
        Logger.i(TAG, timing.logLine())
    }

    /** `false` when the socket was already closed or the write failed (logged here). */
    private suspend fun sendFrame(frame: Frame.Text): Boolean =
        try {
            session.send(frame)
            true
        } catch (e: ClosedSendChannelException) {
            Logger.w(TAG, "Relay socket closed before the frame could be sent", e)
            false
        } catch (e: IOException) {
            Logger.w(TAG, "Failed to send frame to relay", e)
            false
        }
```

**Action 4 — modify** `.../services/tunnel/RelaySession.kt`, companion object: add after `CLOSE_TIMEOUT_MS`.

```diff
         private const val CLOSE_TIMEOUT_MS = 2_000L
+        private const val NANOS_PER_MILLI = 1_000_000L
```

**Definition of Done:**
- [ ] The line uses only id prefix, method, sanitized path, status and timings, with frame-supplied text passed
      through `logSafe`; `Logger.i`, no `String.format`.

### Task 4.5 — Phone tests

**Action 1 — create** `.../test/services/tunnel/RelayTimingTest.kt`

**Setup**: `timing(...)` factory with defaults (id = a fixed UUID, `POST`, `/mcp`, 200, `sent = true`). Imports:
`com.danielealbano.androidremotecontrolmcp.utils.Logger`, `org.junit.jupiter.api.Assertions.assertEquals`,
`org.junit.jupiter.api.Assertions.assertFalse`, `org.junit.jupiter.api.Assertions.assertTrue`,
`org.junit.jupiter.api.DisplayName`, `org.junit.jupiter.api.Test`.

| Test | Verifies |
|------|----------|
| `logLine uses the first 8 characters of the id` | `rid=<first 8>`; `Logger.sanitize(line) == line` |
| `logLine sanitizes the path` | `/mcp?code=x` → ` /mcp ` without `code`; `/s/tok?x` → `/s/<redacted>` |
| `logLine flags an unsent frame` | `sent = false` → ends with ` sent=false`; `sent = true` → no `sent=` token |
| `logLine prints status and all timings` | `status=`, `recvAt=`, `queueMs=`, `replayMs=`, `totalMs=` carry the given values |
| `logLine strips control characters` | `method = "GET\nX"`, `path = "/mcp\r\nforged"`, id with a `\n` in its first 8 characters → the line has no `\n` or `\r`; it contains `GET?X` and `/mcp??forged`. Second timing: `method = "GET\u0085X"`, `path = "/mcp\u202Eevil\u2028x"` → contains `GET?X` and `/mcp?evil?x`, and no U+0085, U+202E or U+2028 |
| `logLine cuts long paths at 128 characters` | `path = "/" + "a".repeat(300)` → the path token is the first 128 characters followed by `…` |

**Action 2 — modify** `.../test/services/tunnel/RelayTunnelProviderTest.kt`

**Setup**: each test below wraps its body in `mockkStatic(Log::class)` + `try { … } finally {
unmockkStatic(Log::class) }`; inside, `every { Log.i(any<String>(), any<String>()) } answers {
infos.add("${firstArg<String>()}|${secondArg<String>()}"); 0 }` with `infos = CopyOnWriteArrayList<String>()` (IO
threads). Wait with `awaitCondition { infos.any { … } }`: the line is written after the frame is sent. Precedent:
`LocalRequestReplayerTest` lines 46-56. New imports: `android.util.Log`, `io.mockk.every`, `io.mockk.mockkStatic`,
`io.mockk.unmockkStatic`, `java.util.concurrent.CopyOnWriteArrayList`.

| Test | Verifies |
|------|----------|
| `relayed request logs a timing line without headers or body` | Frame id = a fixed UUID, header `Authorization: Bearer secret-client-token`, path `/mcp?code=secret`, body `secret-arg`; local `post("/mcp")` route does `delay(50)` then 200. One `MCP:RelaySession` entry with `Relay request rid=<first 8>`, `POST /mcp `, `status=200`, parsed `replayMs` ≥ 50; no captured entry contains `secret` |
| `ninth concurrent request gets 503 while eight are in flight` (extend existing, line 300) | Also captures `Log.i`: an entry with the refused frame's `rid` and `status=503` appears |
| `replay cancelled by a closed socket logs no timing line` (new) | First connection only (an `AtomicInteger` counts connections; later ones `awaitClientClose()`): send `requestJson("c1", "GET", "/hang")`, await `routeHit`, then `close(CloseReason(CloseReason.Codes.NORMAL, "bye"))`. Local `get("/hang")` completes `routeHit` and awaits `release`. After `awaitError(provider)` and `delay(200)`: no captured entry contains `Relay request`; `release` is completed afterwards. No new import |

**Definition of Done:**
- [ ] No assertion depends on `Logger.d` or `BuildConfig.DEBUG`; `Log` is unmocked after every test that mocks it.
- [ ] Not covered by an automated row (no seam makes `session.send` fail or encoding throw without also cancelling
      the replay): the ` sent=false` line after a failed send and the silent encode-failure path. Both are verified
      by reading the diff in Q.1 Action 6; the ` sent=false` text itself is pinned by `logLine flags an unsent frame`.

### Task 4.6 — Documentation for US4

**Action 1 — modify** `relay/README.md`: insert after the `## HTTP semantics` section, before `## Limits`.

````markdown
## Request timing log

Every request except `/relay/health` writes one line when its response closes (answered, refused, failed or
abandoned by the client). Requests that Node's HTTP parser rejects before the relay sees them (malformed requests,
Node's own request timeout) are not logged. The phone does not log frames whose replay is cancelled because the
socket went away, or whose response cannot be encoded.

```text
relay: request rid=3f2a9c1e method=POST path=/mcp rpc=tools/call tool=tap_node status=200 reqBytes=231 resBytes=1840 inflight=0 idleMs=4210 sentAt=1791700000123 bodyMs=0.4 deviceMs=1184.2 totalMs=1185.3
```

| Field | Meaning |
|---|---|
| `rid` | First 8 characters of the frame id; the phone logs the same `rid` (`MCP:RelaySession`, `Relay request rid=…`) |
| `method` | HTTP method |
| `path` | Path without query; `/s/…` capability links as `/s/<redacted>`; cut at 128 characters |
| `rpc` / `tool` | JSON-RPC method (and tool name for `tools/call`), read only from `POST /mcp` JSON bodies up to 64 KiB; `-` = not inspected, `?` = unreadable, `batch` = JSON-RPC batch |
| `status` | Status sent; `-` when the client left before any response |
| `reqBytes` / `resBytes` | Request body read / response body sent; `resBytes` is 0 on the internal-error 500 fallback |
| `inflight` | Requests already in flight when this one arrived |
| `idleMs` | Time since the phone's socket last spoke (message or pong) when the frame was sent |
| `sentAt` | Wall clock (epoch ms) when the relay tried to send the frame to the phone; if the phone disconnected while the body was read, no frame is sent (`idleMs=-`, `status=503`) |
| `bodyMs` / `deviceMs` / `totalMs` | Reading the body / waiting for the phone's answer / whole request inside the relay |
| `aborted=1` | The client closed the connection before the response finished |

`-` means the phase was never reached. Never logged: headers or their values (`Authorization` included), query
strings, bodies, JSON-RPC params or arguments, client IP or `X-Forwarded-For`. Console lines carry no timestamp:
use `docker logs -t`. With Traefik `buffering`, `bodyMs` is near 0 (Traefik already read the body).

Reading a slow call, from the outside in:

- Client-observed time − `totalMs`: client ↔ proxy ↔ relay network and TLS (invisible to the relay).
- `totalMs` − (`bodyMs` + `deviceMs`): relay overhead.
- `deviceMs` − phone `totalMs` (same `rid`): WebSocket round trip both ways. It does not depend on the clocks.
- Phone `replayMs` − the in-app tool duration: the phone's HTTP stack, auth and MCP SDK.
- Phone `recvAt` − `sentAt`: relay → phone leg, only as accurate as the two clocks agree.
- `rpc=initialize` before every `tools/call` means the client repeats the handshake on each call.
- A `deviceMs` that grows with `idleMs` points at the phone's radio waking up.
````

**Action 2 — modify** `docs/PROJECT.md`, WebSocket relay paragraph (line 646): append after its last sentence.

```markdown
Each relayed request is logged on both ends with the same `rid` (first 8 characters of the frame id): the relay writes `relay: request rid=… bodyMs=… deviceMs=… totalMs=…` and the app writes `Relay request rid=… queueMs=… replayMs=… totalMs=…` at info level under `MCP:RelaySession`. Only method, sanitized path, status, sizes and timings are logged, plus the JSON-RPC method and tool name of `POST /mcp` JSON bodies on the relay. Headers, query strings and bodies never are (see `relay/README.md`, "Request timing log").
```

**Action 3 — modify** `docs/PROJECT.md`, `services/tunnel/` file list (line 138): insert `` `RelayTiming.kt`, `` after
`` `RelaySession.kt`, ``.

**Definition of Done:**
- [ ] The README field table matches `formatRequestLog` (same fields, same order).

---

## Quality gates and delivery (run ONLY after all user stories are implemented)

### Task Q.1 — Gates, review loop and PR

**Action 1 — local pre-checks** (Gradle is not run on this PC, G2; linter jars per G4):
- `<LINT_TOOLS>` = the folder chosen under G4 (default, pending: `LINT_TOOLS="$(cygpath -m "$HOME/.lint-tools")"` in
  Git Bash, which yields the `C:/...` form; persistent, outside every repository; no literal user path in this
  file). Step 0:
  if `<LINT_TOOLS>/ktlint/ktlint.jar` or `<LINT_TOOLS>/detekt/detekt-cli-all.jar` is missing, place it there first
  (copied from the location the user names under G4, or downloaded from that tool's GitHub release page for exactly
  ktlint 1.8.0 / detekt-cli 2.0.0-alpha.5). Before use, `sha256sum` must print
  `a3fd620207d5c40da6ca789b95e7f823c54e854b7fade7f613e91096a3706d75` for `ktlint/ktlint.jar` and
  `4992d226183f2125f517fe59c21f1a2a80bbe40c223f2481eef5ac1b4694f35c` for `detekt/detekt-cli-all.jar`. A jar that
  cannot be placed or does not match is never used: stop and ask the user (a skipped pre-check hides lint failures
  until CI).
- ktlint: `java -jar <LINT_TOOLS>/ktlint/ktlint.jar "app/src/main/kotlin/**/*.kt" "app/src/test/kotlin/**/*.kt"
  "e2e-tests/src/test/kotlin/**/*.kt"` — zero findings.
- detekt: `MSYS_NO_PATHCONV=1 java -jar <LINT_TOOLS>/detekt/detekt-cli-all.jar --input <dir>` with the jar path
  written in `C:/...` form (a `/c/...` path makes Java exit 1 with an empty-looking report), default rules, one run
  per `--input` (`app/src/main/kotlin`, then `app/src/test/kotlin`) — exit 0 each. `app/src/testGms/kotlin` (49
  inherited findings) is not touched by this plan.
- Relay: `cd relay && npm ci && npm run lint && npm test && docker build -t lucky-tool-android-relay .`
- `gh act --validate` is skipped (`act` not installed); workflows are validated by the CI run.

**Action 2 — push and open the PR.** Write the body to a scratch file (long heredocs fail in this environment), with
the TOOLS.md sections `## Summary`, `## Plan Reference` (`docs/plans/67_mcp_tool_latency_optimization_20261011041813.md`),
`## Changes`, `## Testing` (CI checks, reversion results, Manual Test table) and `## Checklist`.

```bash
git push -u origin feat/mcp-tool-latency-optimization
gh pr create --repo mlandolfi90/Lucky-Tool-Android --base main --head feat/mcp-tool-latency-optimization \
  --title "Plan 67: MCP tool latency optimization" --body-file <scratch>/pr67-body.md
```

Verify the returned URL is `https://github.com/mlandolfi90/Lucky-Tool-Android/pull/<N>` (without `--repo` it targets
upstream).

**Action 3 — read CI with gh, never assume:** `gh pr checks <N> --repo mlandolfi90/Lucky-Tool-Android --watch`, then
`gh run view <run-id> --repo mlandolfi90/Lucky-Tool-Android` for any failure. Required green, mapping the
repository agent rules' quality gates (valid as the gate substitute only under G2):
- `Lint` = `make lint` (`./gradlew ktlintCheck detekt`).
- `Unit & Integration Tests` = `make test-unit` + `make test-integration` (`./gradlew :app:test :privacy:test
  :privacy-benchmark:test jacocoTestReport jacocoTestCoverageVerification`); it includes the unmodified
  `ActionExecutorImplTest` (S1 refresh), the existing clear-first `verifyOrder` tests and every new row of this plan.
- `E2E Tests` = `make test-e2e` (`E2EComposeRefreshTest`, `E2EWebViewRefreshTest` and the new calculator flow).
- `Build Release - GMS`, `Build Release - FOSS`: compile both flavours (`./gradlew assemble<Flavor>Debug
  assemble<Flavor>Release`, `ci.yml:393`). This is NOT `./gradlew build`, and no module sets `allWarningsAsErrors`,
  so a green check does not prove "no build warnings".
- `Relay tests and image` (runs because `relay/**` changed; every existing relay test included).
- Warnings check (the "no build warnings" gate): download the logs of the `Unit & Integration Tests` job and of both
  `Build Release` jobs with `gh run view <run-id> --repo mlandolfi90/Lucky-Tool-Android --log`, and confirm there is
  no Kotlin compiler warning line (`w: file:///...`) for any file this plan creates or modifies. Any such warning is
  fixed at its root cause (never suppressed) and the checks are read again.

**Action 4 — MCP protocol compliance check:** in the green `Unit & Integration Tests` run, confirm
`McpProtocolIntegrationTest`, `ToolPermissionsIntegrationTest`, `ErrorHandlingIntegrationTest` and the new schema rows
(`get_screen_state schema exposes new params with defaults`, `find_nodes schema exposes visible_only default false`,
`tools list exposes selector params for click_node and tap_node`) passed (`gh run view --log` filtered by class name).
Confirm by reading the diff that every new schema property has `type`, `description` and (booleans) `default`, that
`required` lists only always-required params, that invalid params raise `McpToolException.InvalidParams`, surfaced
as `CallToolResult(isError = true)` (not a JSON-RPC error; see `docs/MCP_TOOLS.md` "Error Handling"), verified by the
integration rows `get_screen_state invalid include_offscreen returns isError` and `tap_node with node_id and selector
returns invalid params error`, and that the tool count stays 57.

**Action 5 — guard reversion.** For each row: re-add/remove the guard, run the named test, see it FAIL, restore the
code with the Edit tool (never `git checkout` / `git restore`). Relay rows run locally with the exact `npm test`
command on scratch copies. Kotlin rows run in CI, and ONLY with the user's explicit consent to the probe-branch
approach (G5), because it creates remote-visible objects: commit the reverted guards on a `probe/plan-67-guards`
branch built with git plumbing from the PR head (one probe commit per independent group), open it as a DRAFT PR with
`--repo mlandolfi90/Lucky-Tool-Android`, read the failing tests with `gh run view`, then close the draft unmerged and
delete the probe branch. Nothing from this step lands on the feature branch. Without that consent, the Kotlin rows
are reported in the PR body as not executed. Record every result in the PR body.

| Story | Reverted guard | Test that MUST fail |
|-------|----------------|---------------------|
| US1 | `childNode.refresh()` back in `AccessibilityTreeParser.parseNode` | `does not refresh child nodes without a resource id`, `does not refresh Compose virtual child nodes`, `does not refresh children and caches the getChild instance` |
| US1 | `rootNode.refresh()` after `window.root` in `getFreshWindowsLocked` | `getFreshWindows does not refresh window root nodes` |
| US1 | `rootNode.refresh()` after `getRootNode()` in `getFreshWindowsLocked` | `getFreshWindows fallback does not refresh the active root node` |
| US1 | `rootNode.refresh()` after `window.root` in `rawNodeExistsLocked` | `rawNodeExists does not refresh window root nodes` |
| US1 | `rootNode.refresh()` after `getRootNode()` in `rawNodeExistsLocked` | `rawNodeExists falls back to single root node when no windows available` |
| US1 | `child.refresh()` back in `rawNodeMatchesRecursive` | `rawNodeExists does not refresh child nodes after clearing the framework cache` |
| US2 | `isEmitted` ignores `options.includeOffscreen` | `includeOffscreen false omits off-screen rows`, `paged output honours snapshot options for rows and closure` |
| US2 | `formatMultiWindowPage` uses `CompactFormatOptions()` instead of `snapshot.options` | `cursor page reuses snapshot options` |
| US2 | `find_nodes` filters before `privacyToolGate.texts` | `visible_only filters after privacy gate` |
| US2 | probe-only code in `handleFreshRequest`: prune `visible == false` nodes from `rawResult` before `privacyToolGate.tree` | `include_offscreen false still redacts the full tree` |
| US3 | `if (hasSelector) invalid(...)` removed in `parseTarget` | `node_id with selector is rejected` (resolver and both tool tables) |
| US3 | `onScreen.size > 1` branch returns `Resolved(onScreen.first())` | `two on-screen matches are ambiguous`, `ambiguous selector returns untrusted error and does not click` |
| US3 | `.filter { it.visible }` removed in `resolve` | `off-screen duplicate is ignored`, `click_node by selector ignores off-screen matches` |
| US3 | `privacyToolGate.texts` skipped in `ambiguityResult` | `ambiguityResult redacts the full match list`, `click_node ambiguous selector candidates are redacted` |
| US3 | `notFoundMessage` appends `value='${selector.value}'` | `NodeNotFound echoes no selector value`, `click_node selector not found echoes no selector value` |
| US4 | `/s/` branch removed from `safePath` | `safePath redacts capability links`, `capability path is redacted` |
| US4 | `POST /mcp` check removed from `rpcSummary` | `rpcSummary ignores other paths and methods` |
| US4 | `path: req.url` instead of `safePath(url.pathname)` | `timing line keeps secrets out` |
| US4 | `id.take(RID_LENGTH)` replaced by `id` in `RelayTiming.logLine` | `logLine uses the first 8 characters of the id` |
| US4 | `LocalRequestReplayer.sanitizedPath(path)` replaced by `path` | `logLine sanitizes the path` |
| US4 | `logSafe` returns its input unchanged | `logLine strips control characters` |
| US4 | `try` / `catch` removed around the `close` listener body in `startTiming` | `timing log failure is contained` |

**Action 6 — plan-compliance review** per the repository agent rules, over `git diff main...HEAD`; record each round
in this plan as `## Review findings — code-reviewer (plan compliance), <date>` (`| ID | Finding | Resolution |`).

**Action 7 — deliver:** report the PR URL. The installable build is the `app-gms-debug` artifact of the PR's CI run:
`gh run download <run-id> -n app-gms-debug -R mlandolfi90/Lucky-Tool-Android`. Install it, run Manual Tests M2-M6
with it (M1 was taken before implementation, see Prerequisites) and add the M1-M6 table to the PR body.

**Definition of Done:**
- [ ] Local pre-checks clean; the six CI checks green, read with `gh`;
      no Kotlin `w: ` warning for files of this plan.
- [ ] MCP protocol compliance confirmed (Action 4).
- [ ] Every reversion row observed failing and restored (Kotlin rows only under G5); results in the PR body.
- [ ] `code-reviewer` plan-compliance pass clean and recorded; PR URL reported.

---

## Manual Test (not automated — real phone, relay deployed; MANUAL=)

Warn the user before running: it needs the phone, the deployed relay and real credentials. Same device, same
Compose-heavy third-party app and same screen for "before" and "after", once while the app is busy and once quiet.
Logcat: `adb logcat -v time | grep -E "MCP:(WaitForNodeTool|RelaySession|TapNodeTool|ClickNodeTool)"` (`-s` does not
filter `MCP:` tags reliably). Order: M1 runs before the first code change (Prerequisites), on the pre-plan build;
M2-M6 run after Q.1 Action 7 installs the plan's build. Only measured numbers are reported; no speed-up figure is
promised.

- **M1 — Baseline (pre-plan debug build, before implementation starts):** call `get_screen_state`, `find_nodes`,
  `tap_node` and `wait_for_node` 3 times each; record the in-app server-log `durationMs` per call, the logcat
  `wait_for_node: found after Xms` line, the node count (number of `get_screen_state` rows) and the character count
  of the `get_screen_state` page. If M1 could not be taken, say so in the PR body; never present an estimate as a
  measurement.
- **M2 — After (plan build):** repeat M1 exactly. Freshness: on a screen with a live-updating value (counter, timer or
  WebView text), call `get_screen_state` and then `find_nodes` for the new value right after it changes; the value
  must match what the screen shows.
- **M3 — Slim output:** `get_screen_state` with `include_offscreen=false`, with `include_hierarchy=false`, and with
  both; record the character counts next to M2's default call.
- **M4 — Selector tap:** `tap_node` with `by`/`value` on a unique on-screen label; confirm the tap and that logcat
  shows a single call (one server-log entry) where M1 needed `find_nodes` + `tap_node`. Then a label present twice on
  screen: confirm the ambiguous-selector error lists the candidates and nothing is tapped.
- **M5 — Relay attribution:** make 10 consecutive calls through the relay, then 3 calls after 2 minutes idle. Pair
  `docker logs -t <relay container>` `relay: request` lines with phone `Relay request` lines by `rid`; tabulate
  `deviceMs` − phone `totalMs`, `idleMs`, and whether `rpc=initialize` precedes each `tools/call`.
- **M6 — tools/list:** through the relay, `tools/list` shows `include_offscreen`, `include_hierarchy`, `visible_only`
  and the selector params with their defaults; the tool count is 57.

Record M1-M6 as a table (per tool: median `durationMs`, node count, busy/quiet; char counts; relay columns) in the PR
body.

---

## Pending decisions (confirm before implementation; the plan text implements each proposed default)

Status of every entry: **PENDING** until the user answers (plan-reviewer round 4, R4-11: the whole list is to be put
to the user). On each answer, append `— CONFIRMED <date>` (or `— CHANGED <date>: <chosen alternative>` and amend the
affected actions) to that entry. D1.2 (a code removal), D3.13 (a deviation from the interface-first rule), G2 (CI
instead of local Gradle gates), G4 (linter jar source) and G5 (remote probe branch) need an explicit answer;
implementation does not start while any entry is PENDING.

**ALL ENTRIES ANSWERED 2026-10-11.** The user put the whole list to the default ("ejecuta el plan sin mi
intervención"), so every entry below is CONFIRMED at its proposed default, with these three exceptions, each marked
on its own entry: **G1 CHANGED** (no merge: this plan stacks on `feat/websocket-relay-provider`), **G4** resolved by
copying the verified jars into the default folder, and **G5** kept at its default of "no probe branch", so the Kotlin
reversion rows are reported as not executed. The removals and deviations that needed an explicit answer (D1.2,
D3.13) are covered by that same blanket answer and are called out in the PR body.

General:
- **G1 — PR #3 prerequisite / branch strategy.** Default: wait until the user merges PR #3, then one branch
  `feat/mcp-tool-latency-optimization` from the latest `main`. Alternatives: two PRs from this plan (US1-US3 now from
  `main`, US4 after the merge; `Relay tests and image` then runs only on the second); or stack on
  `feat/websocket-relay-provider` (breaks TOOLS.md's branch-from-main rule).
  — CHANGED 2026-10-11: the third alternative. The user does not want to merge yet ("NO QUIERO MERGUEAR QUIERO
  PULIR MI VERSION", 2026-10-11), so the branch is created from `feat/websocket-relay-provider` and the PR targets
  that branch, not `main`. Consequences that amend this plan: the PR #3 prerequisite check is replaced by a check
  that the branch point contains `relay/src/relay.js` and `services/tunnel/RelaySession.kt`; "All work branches from
  the latest `main`" and the Q.1 Action 2 `--base main` become `--base feat/websocket-relay-provider`; the Q.1
  Action 6 review diff becomes `git diff feat/websocket-relay-provider...HEAD`. The deviation from TOOLS.md's
  branch-from-main rule is the user's decision and is stated in the PR body.
- **G2 — Gate execution on this PC.** The repository agent rules require the implementer to run lint, the full test
  suite and the build itself. Default: standalone ktlint/detekt and relay npm/docker locally; Gradle lint, tests and
  build read in CI via `gh`, plus the Kotlin-warnings log check of Q.1 Action 3. This rests only on the user's
  decision recorded here (no in-repo document grants it). Alternative: set up a local Gradle/SDK toolchain and run
  `make lint`, `make test-unit`, `make test-integration` and `./gradlew build` locally.
  — CONFIRMED 2026-10-11: default chosen by the user; recorded as an exception to the agent rules in `REGLAS.md`
  ("Excepciones a `CLAUDE.md`", PR #2).
- **G3 — Pre-existing doc drift.** Default: document the existing `cursor` param, pagination and the `visible` field
  of `find_nodes` output inside US2 (same blocks are edited); only report, not fix: PROJECT.md says CI runs
  "sequential" (it is parallel), `:app:testDebugUnitTest` vs Makefile `:app:testGmsDebugUnitTest`, and `test-unit` =
  `./gradlew test` vs the Makefile's module list.

- **G4 — Standalone linter jars.** The jars verified while writing this plan sit in a session-scoped temporary
  folder that an implementing session will not have, so they cannot be the default. Default: persistent folder
  `<LINT_TOOLS>` = `$(cygpath -m "$HOME/.lint-tools")` (outside every repository; written relative to `$HOME` because
  this public plan must not publish an account-specific absolute path), filled by Q.1 Action 1 step 0 from a source
  the user names (a copy of the verified jars, or the official GitHub release of exactly ktlint 1.8.0 / detekt-cli
  2.0.0-alpha.5), accepted only on the SHA-256 values of Q.1 Action 1; a jar that cannot be placed or does not match
  stops the work and goes to the user (no silent skip). Alternatives: another permanent location (recorded here as a
  literal path only if the user accepts publishing it, otherwise relative to `$HOME`); or skip the local linters and
  rely on CI `Lint` alone (lint failures then surface only in CI).
  — CONFIRMED 2026-10-11: default. Source named by the blanket answer: a copy of the jars this session already
  verified, placed in `<LINT_TOOLS>` and accepted only on the SHA-256 values of Q.1 Action 1.
- **G5 — Kotlin guard reversion through a probe branch.** Default: none until the user consents; with consent, the
  draft-PR probe of Q.1 Action 5 (remote-visible: a pushed branch and a closed draft PR). Alternative: run the Kotlin
  reversion rows locally once a Gradle toolchain exists (G2 alternative), or report them as not executed.
  — CONFIRMED 2026-10-11: default, i.e. NO probe branch. The blanket answer authorises the plan's defaults, and this
  default is "none until the user consents"; a pushed branch plus a draft PR is remote-visible, so it needs consent
  to that specific step. The Kotlin reversion rows are therefore reported in the PR body as not executed; the relay
  rows still run locally.
- **G6 — Wording about the agent rules file.** Default: the plan names it only as "the repository agent rules" and
  restates none of its rules (git hygiene, attribution). Alternative: cite the file by name and section.

US1:
- **D1.1 — Which refresh calls go.** Default: child refreshes (`AccessibilityTreeParser.kt:165`, `UtilityTools.kt:489`)
  and the root refreshes right after a clear (`NodeActionTools.kt:703/772`, `UtilityTools.kt:421/443`). Alternative:
  remove only the child refreshes first and measure.
- **D1.2 — `COMPOSE_SEMANTICS_ID_KEY` becomes unused.** Default: delete it (a removal: needs explicit approval); tests
  use a private literal constant. Alternative: keep it as dead main code.
  — CONFIRMED 2026-10-11: default. The blanket answer is the explicit approval for this removal; it is listed in the
  PR body as a removal the user approved.
- **D1.3 — Dead `refresh()` stubs on strict mocks** (UtilityToolsTest, NodeActionToolsTest:120, TextInputToolsTest:122,
  ScreenIntrospectionToolsTest:134/498, GetNodeDetailsToolTest:96, McpIntegrationTestHelper:148,
  ScreenIntrospectionIntegrationTest:316/325/380, NodeActionIntegrationTest:242/251). Default: leave them (minimal
  diff) and rely on the new `verify(exactly = 0)` guards. Alternative: remove them so strict mocks throw on any
  reintroduced `refresh()`.
- **D1.4 — `ActionExecutorImpl.kt:679` `cached.node.refresh()` + S1 identity check.** Default: keep unchanged.
- **D1.5 — E2E tightening.** Default: no E2E change; rely on CI plus Manual Test M2 for single-read freshness (CI
  limit: see Context, framework cache bullet). Recommended safer alternative: in
  `E2EComposeRefreshTest` and `E2EWebViewRefreshTest`, after the fixture value changes and `wait_for_idle` returns,
  assert the new value on the FIRST `get_screen_state` and the FIRST `find_nodes` read, with no polling (adds files
  to the scope boundary and an action to US1).
- **D1.6 — Per-read timing log.** Default: none; use the server-log `durationMs` and the `wait_for_node` logcat line.
- **D1.7 — Stale freshness claim in `E2EWebViewRefreshTest.kt` KDoc (lines 29-30, outside the scope boundary).** US1
  rewords "refresh() cannot re-fetch what the cache still serves as current" in `NodeActionTools.kt` and
  `UtilityTools.kt`; the same claim stays in that E2E KDoc. Default: leave the file untouched and record the
  leftover inconsistency (Context, framework cache bullet). Alternative: add the file (KDoc only) to the US1 scope
  list and append to Task 1.3 this diff:
  ```diff
  - * JavaScript DOM change the cache — and every subsequent read — stays stale;
  - * per-node refresh() alone cannot re-fetch what the cache still serves as current.
  + * JavaScript DOM change the cache — and every subsequent read — stays stale.
  ```

US2:
- **D2.1 — Param names and defaults.** Default: `include_offscreen` = true, `include_hierarchy` = true, `visible_only`
  = false (backward compatible). Alternative: `omit_offscreen` / `omit_hierarchy` (default false), or slimmer
  defaults (breaking output change).
- **D2.2 — Option values on a cursor call.** Default: validated, then ignored silently; documented as "fresh captures
  only". Alternative: append a note, as `include_screenshot` does.
- **D2.3 — Note when off-screen rows are omitted.** Default: replace the offscreen hint with
  `NOTE_LINE_OFFSCREEN_OMITTED`, no count. Alternative: include the omitted-node count (one extra pass).
- **D2.4 — Note when the hierarchy is omitted.** Default: none. Alternative: `note:hierarchy omitted`.
- **D2.5 — Validation strictness.** Default: strict `McpToolUtils.optionalBoolean` for the three new params;
  `include_screenshot` and `exact_match` keep their lenient parsing. Alternative: lenient `booleanOrNull`.
- **D2.6 — WebView filtering point.** Default: filter the merged tree by each merged node's own `visible`; document
  the caveat. Alternative: filter off-screen web nodes before merging (changes merger semantics).
- **D2.7 — Per-param permission toggles** (Settings > MCP Tools, like `include_screenshot`). Default: none for the US2
  and US3 params.
- **D2.8 — Where `visible_only` filters.** Default: after `privacyToolGate.texts` on the full match list (redaction
  byte-identical to today). Alternative: before it (less privacy work, but changes the model input of kept items).

US3:
- **D3.1 — Ambiguity policy (>1 on-screen match).** Default: error, never pick; no tie-breaking. Alternatives: first
  match in tree order; prefer the single exact-equality match when `exact_match=false`; an extra `index` param.
- **D3.2 — Ambiguity result form.** Default: `isError` result via the new `McpToolUtils.untrustedErrorResult`, up to
  `MAX_AMBIGUOUS_CANDIDATES = 5` candidates in `find_nodes` node JSON (redacted), `match_count` always the full
  on-screen count. Alternative: thrown `McpToolException` with only `node_id` + bounds (no device text, no warning,
  but candidates cannot be told apart without another call). Cost trade-off (also D2.8): with Privacy Mode on, the
  whole match list (off-screen included) goes through `PrivacyToolGate.texts`, so a broad selector (e.g.
  `by=class_name`, `value=TextView`) pays model-backed detection on hundreds of texts on an error path, the same as
  the equivalent `find_nodes` call. Default: keep that (redaction input identical to `find_nodes`) and document it in
  the MCP_TOOLS.md "Ambiguous selector" error case (Task 3.6), steering agents to exact selectors. Alternative:
  redact only the first 5 on-screen candidates (changes the model input relative to `find_nodes`).
- **D3.3 — Zero on-screen matches.** Default: `NodeNotFound`, listing up to 5 off-screen matching `node_id`s with a
  `scroll_to_node` hint; the message names `by` and `exact_match` but never the selector value (the SDK logs thrown
  tool-error messages to logcat). Alternatives: no off-screen hint; echo the agent-supplied value (it would then reach
  logcat).
- **D3.4 — `exact_match` with `node_id`.** Default: reject with `InvalidParams`. Alternative: ignore.
- **D3.12 — Stray `by` / `value` next to `node_id`.** Before this plan they were ignored. Default: reject with
  `InvalidParams` ("not both"), so a caller never gets a click on a different node than it described; documented as
  an intentional change in the US3 acceptance criteria. Alternative: keep ignoring them when `node_id` is present
  (fully backward compatible; `parseTarget` returns `ById` and the "not both" rows change).
- **D3.5 — `exact_match` parsing.** Default: lenient, as `find_nodes` (`booleanOrNull ?: false`). Alternative: strict.
- **D3.6 — `click_node` and non-clickable matches.** Default: no `clickable` filter, no climbing to a clickable
  ancestor; the existing "not clickable" `ActionFailed` surfaces. Alternatives: prefer the single clickable match;
  climb to the nearest clickable ancestor.
- **D3.7 — Window scope.** Default: all windows, on-screen only. Alternative: APPLICATION windows only.
- **D3.8 — Tap point of partially visible nodes.** Default: no clamping (same as the `node_id` path). Alternative:
  intersect bounds with the screen rect before randomizing.
- **D3.9 — Tools covered.** Default: only `click_node` and `tap_node`; `long_click_node` and `scroll_to_node` stay
  `node_id`-only.
- **D3.10 — Resolver placement.** Default: new file `mcp/tools/NodeSelectorResolver.kt` (+ PROJECT.md file list).
  Alternative: inside `NodeActionTools.kt` (already 889 lines).
- **D3.11 — E2E coverage.** Default: add the `@Order(4)` selector calculator flow (CI only). Alternative: no E2E change.
- **D3.13 — Resolver without interface** (deviation from the repository agent rules' interface-first default for
  unit-testable business logic). Default: concrete `NodeSelectorResolver` class: pure logic over `ElementFinder`
  (itself concrete), no Android service; its tests use the real `ElementFinder` and tool tests build it from their
  existing mocks. Alternative: `NodeSelectorResolver` interface + `NodeSelectorResolverImpl`, bound with Hilt
  `@Binds`, and tool tests mock the interface.
  — CONFIRMED 2026-10-11: default. The blanket answer is the explicit answer this deviation needed; it is stated in
  the PR body.

US4:
- **D4.1 — Correlation key.** Default: first 8 characters of the frame id on both sides (not a UUID, so `Logger` keeps
  it; not a secret). Alternative: a relay per-process sequence number (protocol change).
- **D4.2 — Phone log level.** Default: `Logger.i` (also on release/edge builds; testable in every variant). Alternative:
  `Logger.d` (debug builds only).
- **D4.3 — JSON-RPC method on the relay.** Default: log the top-level `method` and, for `tools/call`, `params.name`
  only, from `POST /mcp` `application/json` bodies ≤ 64 KiB, regex-filtered; this answers whether the client repeats
  `initialize` per call. Alternative: no body inspection at all (`rpc` dropped; that question stays open).
- **D4.4 — Client address.** Default: never logged (no `X-Forwarded-For`, no socket address).
- **D4.5 — `Server-Timing` header.** Default: not added. Alternative: `relay;dur, device;dur` on proxied responses.
- **D4.6 — Wall-clock fields.** Default: include relay `sentAt` and phone `recvAt` with the clock-skew caveat.
  Alternative: durations only.
- **D4.7 — Wire extension (`elapsedMs` in response frames).** Default: no; logs joined by `rid`. Alternative: yes
  (touches `RelayFrames.kt`, `frames.js` and docs).
- **D4.8 — Switch.** Default: always on, no `TIMING_LOG` env flag. Alternative: flag defaulting to on (config.js,
  config.test.js, README row).
- **D4.9 — Line format.** Default: plain logfmt-style line prefixed `relay: request`. Alternative: JSON lines.

---

## Review findings — `plan-reviewer`, round 1, 2026-10-11

| ID | Finding | Resolution |
|----|---------|------------|
| R1-01 | CRITICAL: `TapNodeTool.execute` kept `@Suppress("ThrowsCount")` with only 2 throws left | Suppression removed (Task 3.3 Action 2); throw count stated against detekt default `max: 2`; deterministic no-suppression fallback (`invalidInset(): Nothing`) given; DoD checks the removal |
| R1-02 | CRITICAL: forward dependencies inside Tasks 2.2, 2.3, 2.4, 4.2 | Actions reordered: 2.2 constant → helpers → `walkTree` → `formatMultiWindow` → `countKeptNodes` → `collectKeptNodes` → `appendWindowPageSection` → `formatMultiWindowPage`; 2.3 imports → constants → properties fn → `buildFreshPageText` → `handleFreshRequest` → `execute` → `register`; 2.4 constant first; 4.2 `forward` / `writeResponse` / `sendJson` before `handleHttp` |
| R1-03 | WARNING: `PiiCategory.EMAIL` does not exist; invalid `apply` setup | Setup uses `PiiCategory.EMAILS` and `store.placeholderFor(...)` with `PlaceholderSubstitutor(store)` |
| R1-04 | WARNING: Q.1 claimed `-32602`-backed `InvalidParams` | Replaced by `McpToolException.InvalidParams` surfaced as `CallToolResult(isError = true)`, with the two verifying integration rows |
| R1-05 | WARNING: Build Release checks are not `./gradlew build`; warnings unproven | Claim removed; Q.1 Action 3 adds a `gh run view --log` check for Kotlin `w: ` lines in files of this plan |
| R1-06 | WARNING: linter jar paths did not exist / were placeholders | Q.1 Action 1 names the scratchpad `tools/` jars with SHA-256 values and a skip rule; location choice is G4 (pending) |
| R1-07 | WARNING: CI-only gates justified by an unmerged file | Context and G2 now rest on the user's recorded decision; G2 pending explicit confirmation |
| R1-08 | WARNING: weak safety net for the US1 refresh removal | Context and US1 AC state E2E proves eventual freshness only; D1.5 rewritten with the first-read E2E alternative recommended; pending |
| R1-09 | WARNING: ~35 decisions implemented as defaults; D1.2 is a removal | Status rule added: every entry PENDING until answered, implementation blocked meanwhile; D1.2, G2, G4, G5 need explicit answers |
| R1-10 | WARNING: restated git/PR rules and repeated measured figures | Git workflow and attribution restatements deleted (branch, PR title, commit table kept); figures kept only in Context; US1/US2/US4 Why shortened |
| R1-11 | WARNING: DoD constraints repeated across levels | No-`@Suppress`, ≤120 columns and no-Mermaid stated once in Context; story/task duplicates removed |
| R1-12 | WARNING: doc actions described text instead of giving it | Tasks 2.6 and 3.6 now give verbatim markdown/JSON/diff blocks for every inserted section, schema, example, error bullet and table row |
| R1-13 | WARNING: acceptance criteria without tests | Rows added: null/object `include_*`, number/null `visible_only`, relay 413/408/502 and log-failure containment, selector-value log rows; tautological legacy row replaced by a pinned expected string plus a paged-notes row; 500 path documented as untested |
| R1-14 | WARNING: "node_id calls behave exactly as before" was not true | AC now lists the intentional changes (stray selector params rejected, `required` empty); D3.12 added (pending) |
| R1-15 | WARNING: "every frame/request logs exactly one line" not guaranteed | Phone AC narrowed to completed replays (cancelled/throwing not logged); relay AC excludes Node parser-level rejections; README text says so |
| R1-16 | INFO: `wait_for_node` double walk mentioned but not handled | Clause dropped from US1 Why; added to Out of scope |
| R1-17 | INFO: `getChild` comment inaccurate | Reworded to "getChild returns nodes fetched or prefetched by this same read" |
| R1-18 | INFO: "existing tests unchanged" contradicted by fixture edits | Task 2.5 DoD, Task 3.3 Action 4 and US4 AC list every edit to existing tests |
| R1-19 | INFO: missing test imports | New imports listed per file (parser, NodeActionTools, ScreenIntrospection, integration, resolver, RelayTiming, RelayTunnelProvider, E2E) |
| R1-20 | INFO: `close` listener could crash the relay | Listener body wrapped in `try`/`catch` logging `relay: timing log failed`; test row and reversion row added |
| R1-21 | INFO: log injection through frame `method`/path | `RelayTiming.logSafe` replaces control characters in id, method and path; path cut at 128; two test rows and a reversion row added |
| R1-22 | INFO: ambiguity redaction cost on broad selectors | Trade-off written into D3.2 (pending) and the MCP_TOOLS.md "Ambiguous selector" error case |
| R1-23 | INFO: stubbed-finder integration row overstated | Row renamed with its limitation; real two-window matching moved to `NodeSelectorResolverTest` `resolves a unique match in a second window` |
| R1-24 | INFO: resolver is concrete, not interface-first | Kept concrete; one-line rationale added in Task 3.2 Action 1 |
| R1-25 | INFO: `CompactFormatOptions.kt` missing from PROJECT.md list | Added in Task 2.6 Action 3 |
| R1-26 | INFO: probe branch / draft PR are remote-visible | Made conditional on explicit consent (G5, pending); otherwise Kotlin reversion rows reported as not executed |
| R1-27 | INFO: `silentLogger` diff noise | Shown as a context line |
| R1-28 | INFO: references to the agent rules file by name and an attribution restatement | Replaced by "the repository agent rules"; restatement removed; G6 added (pending) |
| R1-29 | INFO: empty review section with `<date>` placeholder | Replaced by this dated round-1 record |
| R1-30 | INFO: E2E selector clicks without retry | `clickBySelectorWithRetry` helper (same deadline/interval as `findNodeWithRetry`, failure prints the last result text) |

## Review findings — `plan-reviewer`, round 2, 2026-10-11

| ID | Finding | Resolution |
|----|---------|------------|
| R2-01 | CRITICAL: Task 1.1 deleted `COMPOSE_SEMANTICS_ID_KEY` while `UtilityTools.kt:487` and `UtilityToolsTest.kt:427` still used it until Task 1.3 | Deletion moved to Task 1.3 Action 7 (after Actions 3 and 6); Task 1.1 notes the constant stays; the `git grep` check lives only in the Task 1.3 DoD |
| R2-02 | WARNING: outer `when (target)` blocks in Task 3.3 fail ktlint 1.8.0 `when-entry-bracing` / `blank-line-between-when-conditions` | Every entry of both outer blocks braced, entries separated by a blank line; constraint stated once in Task 3.3 Action 2 |
| R2-03 | WARNING: no test proves the get_screen_state options leave the privacy gate, screenshot annotation and node cache untouched | `PaginationTests` rows `include_offscreen false still redacts the full tree`, `... keeps screenshot annotation unchanged`, `... still populates the node cache` (with the `val gate` fixture edit); Q.1 Action 5 reversion row (prune before `privacyToolGate.tree`) |
| R2-04 | WARNING: test/lint results used as story ACs and task DoDs; conditional ThrowsCount fallback tied to Q.1 | "green/pass" wording removed from US1 AC, Task 3.4 DoD and US4 AC (structural "not modified" kept); suites listed only in Q.1 Action 3/4; reversion and Manual Test ACs removed from US1; `invalidInset()` fallback dropped |
| R2-05 | WARNING: the same DoD repeated across hierarchy levels | Constant removal: Task 1.3 DoD only. Member-function rule: Task 2.2 DoD only (preamble, US2 AC and code-banner restatement removed). Unchanged files and signatures: one "Explicitly unchanged" paragraph (US3 AC bullet, Task 3.3/4.4/4.6/1.3 DoD items and two "signature unchanged" notes removed). Filter-after-gate: Context plus the US2 "Unchanged" AC (US2 `visible_only` sentence, Task 2.3 note and Task 2.4 DoD removed) |
| R2-06 | INFO: `ClickNodeTool` fixture would reach 128 characters | Wrapped multi-line diff added to Task 3.3 Action 4 |
| R2-07 | INFO: E2E helper retried after exceptions, risking a double key press | Helper contract: retry only on `isError == true`; exceptions are not caught |
| R2-08 | INFO: retained comment "refresh() cannot re-fetch what the cache still serves as current" contradicts AOSP | Reworded in Task 1.2 Action 1 (`NodeActionTools.kt:673-674`) and Task 1.3 Action 2 (`UtilityTools.kt:410-411`); Task 1.3 DoD checks it |
| R2-09 | INFO (user decision): scratchpad linter jars are session-scoped | Q.1 Action 1 uses a persistent `LINT_TOOLS` with a step 0 that places and checksums the jars, and stops instead of skipping; G4 rewritten with this default, still PENDING |
| R2-10 | INFO: MCP_TOOLS.md headings quoted without backticks | All six references now quote ``### `android_…` `` with their line numbers |
| R2-11 | INFO: CRLF claim too broad | Context: Kotlin/docs CRLF except the Plan 66 files, which are LF like `relay/` (`RelaySession.kt`, `RelayTunnelProviderTest.kt` included); keep each file's endings |
| R2-12 | INFO: non-boolean kinds not covered for every param | `McpToolUtilsTest` `OptionalBooleanTests` (absent/null params, true, false, string, number, JSON null, object); `McpToolUtilsTest` added to the US2 scope list |
| R2-13 | INFO: schema property order untested | Integration row `get_screen_state schema exposes new params with defaults` asserts `properties.keys` order |
| R2-14 | INFO: default note lines of cursor pages not pinned | `default paged output keeps the legacy note lines` checks page 1 and page 2 |
| R2-15 | INFO: NodeNotFound echoed the agent value; the SDK may log it | Verified: kotlin-sdk 0.15.0 `Server.handleCallTool` calls `logger.error(e) { "Error executing tool <name>" }` (SLF4J-Android → logcat). The selector value is dropped from the message; `NodeSelector.rawValue` removed; AC, rows, reversion row and D3.3 updated |
| R2-16 | INFO: M1 must precede the new build | M1 is a Prerequisites step before the first code change; Q.1 Action 7 runs M2-M6; Manual Test states the order |
| R2-17 | INFO: placement of `describe('timing log')` | Nested inside `describe('relay')`, before its closing line 539 |
| R2-18 | INFO: missing `McpToolException` import in `NodeSelectorResolverTest` | Added to the import list |
| R2-19 | INFO: verbosity (Logger facts, Task 4.2 trailing prose, Q.1 Action 6 loop, US2 Why, single-use E2E helper code) | Logger bullet and Task 4.2 prose removed; Action 6 points to the agent rules and keeps only the recording format; US2 Why is 2 sentences; helper given as a contract table instead of code |

## Review findings — `plan-reviewer`, round 3, 2026-10-11

| ID | Finding | Resolution |
|----|---------|------------|
| R3-01 | WARNING: missing imports for `UNTRUSTED_CONTENT_WARNING` and `NOTE_LINE_OFFSCREEN_OMITTED` in two integration tests | Task 3.4 Action 1 adds `mcp.tools.McpToolUtils`; Task 2.5 `ScreenIntrospectionIntegrationTest` adds `services.accessibility.CompactTreeFormatter` (both verified absent at HEAD) |
| R3-02 | WARNING: relay `totalMs` included the JSON-RPC sniffing time | `close` listener reads `totalMs`, `status` and `aborted` before `rpcSummary`; KDoc reworded; Task 4.2 DoD item added |
| R3-03 | INFO: `rid` / `sentAt` set although no frame is sent when the device leaves during the body read | README `sentAt` row reworded ("tried to send"; no frame when the phone disconnected: `idleMs=-`, `status=503`) |
| R3-04 | INFO: changed missing-target message for `{}` / null not listed as intentional | Added to the US3 "Intentional changes" sentence |
| R3-05 | INFO (user decision): concrete resolver deviates from interface-first | D3.13 added (default concrete class), listed among entries needing an explicit answer; Task 3.2 Action 1 cites it |
| R3-06 | INFO (user decision): same stale claim in `E2EWebViewRefreshTest.kt:29-30`, outside scope | D1.7 added (default: leave it and record the leftover, now in Context; alternative carries the exact diff) |
| R3-07 | INFO (user decision): account-specific absolute path in a public plan | Q.1 Action 1 and G4 use `<LINT_TOOLS>` = `$(cygpath -m "$HOME/.lint-tools")`; a literal path is published only if the user accepts it under G4 |
| R3-08 | INFO: coverage gaps (`resBytes=0`, cancelled/failed phone sends, non-discriminating root-refresh row) | Relay row `bodiless answers log resBytes=0`; `RelayTunnelProviderTest` row `replay cancelled by a closed socket logs no timing line`; `sent=false` and encode-failure paths declared code-read only (Task 4.5 DoD); parser root-refresh row dropped (3 parser guards) |
| R3-09 | INFO: conditional `encodeToString` import instruction | Replaced by "No `kotlinx.serialization.encodeToString` import (same as `NodeActionTools.kt`)" |
| R3-10 | INFO: verbosity in Task 3.3 Action 2; README omitted the encode-failure exclusion | "standalone detekt run" clause deleted; README adds "or whose response cannot be encoded" |

## Review findings — `plan-reviewer`, round 4, 2026-10-11

| ID | Finding | Resolution |
|----|---------|------------|
| R4-01 | WARNING: E2E `contains("13")` could pass without 8+5 being computed (leftover "10", "113", other text) | Task 3.5 clears the display first (`id/btn_clear` by `resource_id` selector until the result reads `""`/`"0"`), then asserts `assertEquals("13", resultText())` on the `id/result` node (`find_nodes` by `resource_id`, `exact_match=true`); ids and visibility read from the bundled APK (`btn_reset` is `gone`) |
| R4-02 | WARNING: no zero-result tests for the new filters | Shared infra `PaginationTestTrees.allOffscreen`; rows `includeOffscreen false with only off-screen nodes emits no rows`, `include_offscreen false with all nodes off-screen returns a single empty page and clears the cache`, `visible_only true with only off-screen matches returns empty nodes` |
| R4-03 | INFO: 408 row used an undefined `strict` and no device; 413 row's device implicit | 408 row defines `strict` and connects `device(strict.wsUrl)` (503 otherwise); 413 row connects `device(extra.wsUrl)` and targets `extra.port` |
| R4-04 | INFO: `\p{Cntrl}` misses C1, U+2028/2029 and bidi controls | `CONTROL_CHARS = Regex("[\p{Cc}\p{Zl}\p{Zp}\u202A-\u202E\u2066-\u2069]")`; KDoc and US4 AC updated; `logLine strips control characters` adds U+0085, U+202E, U+2028 |
| R4-05 | INFO: `parseTarget` KDoc claimed InvalidParams only | KDoc: InvalidParams for missing, empty or conflicting targets; non-primitive values fail like the other node tools |
| R4-06 | INFO: `requestLog.js` header overstated what the functions receive | Reworded: no request object or headers; `rpcSummary` reads only regex-filtered top-level `method` and `params.name` |
| R4-07 | INFO: README `resBytes` silent on the 500 fallback | Row appends "`resBytes` is 0 on the internal-error 500 fallback" |
| R4-08 | INFO: dangling comment line in Task 1.2 Action 1 | Comment block reflowed (≤ 120 columns) |
| R4-09 | INFO: restated text across levels | (a) gates substitution: G2 only, Context points to it; (b) eventual freshness: Context only (D1.5 and Q.1 Action 3 trimmed); (c) D3.13 rationale moved into D3.13, Task 3.2 cites it; (d) US4 prerequisite line removed; (e) untested 500 fallback: US4 AC only (Task 4.3 DoD item removed; the `startTiming` KDoc only states `resBytes=0`); (f) last US3 AC shortened to the intentional changes, pointing to D3.4/D3.12 |
| R4-10 | INFO: only `inflight=0` tested | Row `inflight counts requests already in flight` (overlapping POSTs: `inflight=0` then `inflight=1`) |
| R4-11 | INFO (user decision): every pending decision still PENDING | Not decided here; the status paragraph of "Pending decisions" records that the whole list goes to the user and names D1.2, D3.13, G2, G4, G5 as needing an explicit answer |
