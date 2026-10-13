<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 71 — Accessibility event debug logging: the per-event logcat line only when you ask for it

**Scope**: `docs/PENDIENTES.md` point 5 (branch `feat/pending-requests`), owner decisions of 2026-10-11 and
2026-10-13. `McpAccessibilityService.onAccessibilityEvent` writes `Window content changed: package=…` to logcat for
every content change of any app (2326 such lines measured in the buffer). After this plan that line is written ONLY
while a new persisted setting `accessibility_event_logging_enabled` is on. The setting is off by default and does not
depend on the build type. With it off, nothing is written for those events (no per-minute summary). Out of scope:
logging for recorded tasks (later plan), relay timing lines (plan 67), any other log change.

**Branch**: `feat/accessibility-debug-logging`, stacked on `feat/approval-profiles` (plan 70; PR base = that branch;
nothing is merged). The plan document itself is committed by the planning run.

**Precondition (before Task 1.1)**: `git fetch origin feat/approval-profiles && git merge
origin/feat/approval-profiles`, then check all three; if any fails, plan 70's code is not there yet: stop and ask the
owner.
- `config/detekt/detekt.yml` exists (plan 70 US6);
- line 1 of `M/ui/screens/settings/GeneralSettingsScreen.kt` is `@file:Suppress("LongMethod")` (plan 70 US6);
- `R/values/strings.xml` contains `<!-- Plan 70 · US8 -->`.

**Paths**: `M/` = `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/`,
`T/` = `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/`,
`TG/` = `app/src/testGms/kotlin/com/danielealbano/androidremotecontrolmcp/`,
`R/` = `app/src/main/res/`.

**Constraints for every action**

- Preserve each file's existing line endings.
- The build fails on any Kotlin compiler warning (`allWarningsAsErrors`, plan 69).
- ktlint_official at 140 columns, detekt defaults at 120: every new line ≤ 120. Import order is ktlint_official
  lexicographic; remove every import a change leaves unused.
- Every user-visible UI text lives in `R/values/strings.xml` under `<!-- Plan 71 · US<n> -->` (texts ready for a later
  language switch). Server-log entry texts (the `SETTINGS` entry of Action 1.1.2) are persisted log data and stay in
  Kotlin, like every existing settings-change message.
- `@Suppress`: no new one. Existing suppressions stay as they are. `SettingsRepositoryImpl` has 2 lines left under
  detekt `LargeClass`, so the new setting lives in its own slice (`DebugSettings`), like `RelaySettings`.
- Logging rules of `CLAUDE.md` apply: the gated line keeps its current content (package name only); no node text, no
  tree, no token is added to any line.
- No tests or linters during implementation; one pass at the end (US5).

**Review budget**: one `plan-reviewer` round before implementation; `code-reviewer` compliance rounds per D8.

---

## Decisions pending (owner)

The plan body already uses each proposal; approve or change them before implementation starts.

| ID | Question | Proposal | Reason |
|----|----------|----------|--------|
| D1 | Where does the toggle live in the UI? | Settings → General, last row, under a new "Debugging" heading (US2). | General already holds the app-wide switches (tool-call indicator, hide from recents); one row does not justify a new settings entry. |
| D2 | Texts of the heading, label and description? | Heading "Debugging"; label "Log accessibility events"; description "Writes a logcat line every time any app changes what is on screen (app package only). Turn it on only while debugging; it fills logcat quickly." Settings-change log entry: "Accessibility event logging enabled/disabled". | States what is written, what is not, and why it is off. |
| D3 | Are other per-event logcat lines gated too? | No. Only `Window content changed` (TYPE_WINDOW_CONTENT_CHANGED). `Window state changed: package=…, activity=…` and `Windows changed (e.g. soft-keyboard show/hide)` stay as today. | The measured noise is the content-change line; the other two fire on app/screen/keyboard transitions only and help read a trace. The owner scoped "any other log change" out. |
| D4 | Does the setting turn itself off (timer, reboot, server stop)? | No. It stays as set (persisted in DataStore) until the owner turns it off. | The owner turns it on on purpose. Forgetting it costs logcat space and a small privacy cost (an app-usage timeline in logcat, readable over adb or in a bug report), comparable to the existing unconditional window-state line. |
| D5 | Is the toggle usable while the MCP server runs, and when does it take effect? | Always usable; takes effect immediately without restarting anything (the accessibility service observes the setting). | It changes logging only, not the server; a restart to debug would disturb the state being debugged. |
| D6 | Can it also be set over adb? | Yes: `--ez accessibility_event_logging <bool>` on the existing `ADB_CONFIGURE` broadcast (US3, a separate story; Actions 4.1.4 and 4.2.x go with it). | Whoever reads logcat already has adb attached; no need to unlock the phone and open the app. |
| D7 | `PENDIENTES.md` calls the recorded-tasks plan "plan 71" and the «Grabar» button "plan 72", but the `CLAUDE.md` ID counter gave 71 to this file. | Keep 71 here (counter rule); the tasks plan and the record plan take the next free IDs when written (72 and 73 if nothing else comes first). | The counter is the repository rule; renumbering a written plan would break references. |
| D8 | `CLAUDE.md` says the plan-compliance `code-reviewer` repeats "until clean"; plan 70 ran under the owner's order of 2026-10-13 of one round per plan. Which applies here? | One round, as plan 70: every finding fixed or brought to the owner with a one-line reason (Task 5.1). | Same owner, same day, same workflow; the change is small. |

---

## US1 — Persisted setting, mirrored live into the accessibility service, gating the content-change line

`onAccessibilityEvent` runs on the main thread for every event, so it cannot suspend or read DataStore: the service
mirrors a one-key `Flow<Boolean>` (collected on `Dispatchers.IO`) into a `@Volatile` flag, and the gated line is
produced by a pure function so "off writes nothing" is a JVM test.

**Acceptance criteria**
- [ ] `ServerConfig.accessibilityEventLoggingEnabled` defaults to `false`; `mapPreferencesToServerConfig` maps a missing key to `false`.
- [ ] `DebugSettings.accessibilityEventLoggingEnabled` emits `false` when unset and only distinct values; `updateAccessibilityEventLoggingEnabled` persists the value and submits one coalesced `SETTINGS` entry `Accessibility event logging enabled|disabled` (none for a no-op write).
- [ ] `SettingsRepository` exposes both through `DebugSettings by debugSettings`; `SettingsRepositoryImpl` gains no other line.
- [ ] `contentChangedLogLine(null | false, …)` returns `null` (nothing built, nothing written); `contentChangedLogLine(true, pkg)` returns exactly `Window content changed: package=<pkg>`.
- [ ] `AccessibilityEventLogSwitch` starts `false`, follows every emitted value, falls back to `false` and reports once on a read failure (and stays off until the service reconnects), and stops when its scope is cancelled.
- [ ] The service starts the switch in `onServiceConnected`, reads it once per content-change event, and drops it in `onDestroy` after cancelling its scope; if Hilt cannot provide `SettingsRepository`, the switch is `null` and nothing is logged.
- [ ] No other line of `onAccessibilityEvent` changes (D3).

### Task 1.1 — Model and settings slice

**Action 1.1.1** — `M/data/model/ServerConfig.kt` — modify

```diff
  * @property hideFromRecents Whether to exclude the app from Android's Recent Apps / Overview screen.
+ * @property accessibilityEventLoggingEnabled Whether the accessibility service writes one logcat line per
+ *   window-content-change event of any app (package name only). Off by default, any build type.
  */
@@
     val hideFromRecents: Boolean = false,
+    val accessibilityEventLoggingEnabled: Boolean = false,
 ) {
```

**Action 1.1.2** — `M/data/repository/DebugSettings.kt` — create

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.repository

import kotlinx.coroutines.flow.Flow

/** Settings slice for debugging aids; [SettingsRepository] delegates to it. */
interface DebugSettings {
    /** Whether the per-event logcat line for window-content changes is on (default off); distinct values only. */
    val accessibilityEventLoggingEnabled: Flow<Boolean>

    /** Enables or disables the per-event logcat line for window-content changes. */
    suspend fun updateAccessibilityEventLoggingEnabled(enabled: Boolean)
}
```

**Action 1.1.3** — `M/data/repository/DebugSettingsImpl.kt` — create

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** Preferences key for accessibility event logging; also read by `mapPreferencesToServerConfig`. */
internal val ACCESSIBILITY_EVENT_LOGGING_ENABLED_KEY = booleanPreferencesKey("accessibility_event_logging_enabled")

/**
 * [DebugSettings] implementation backed by the shared Preferences DataStore.
 *
 * @property dataStore The Preferences DataStore instance provided by Hilt.
 * @property settingsChangeLogger Coalescing logger that surfaces settings changes in the server log.
 */
class DebugSettingsImpl
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
        private val settingsChangeLogger: SettingsChangeLogger,
    ) : DebugSettings {
        override val accessibilityEventLoggingEnabled: Flow<Boolean> =
            dataStore.data.map { it[ACCESSIBILITY_EVENT_LOGGING_ENABLED_KEY] ?: false }.distinctUntilChanged()

        override suspend fun updateAccessibilityEventLoggingEnabled(enabled: Boolean) {
            dataStore.edit { prefs ->
                val old = prefs[ACCESSIBILITY_EVENT_LOGGING_ENABLED_KEY] ?: false
                prefs[ACCESSIBILITY_EVENT_LOGGING_ENABLED_KEY] = enabled
                settingsChangeLogger.submit("accessibility_event_logging", old.toString(), enabled.toString()) { _, n ->
                    "Accessibility event logging ${if (n.toBoolean()) "enabled" else "disabled"}"
                }
            }
        }
    }
```

**Action 1.1.4** — `M/data/repository/SettingsRepository.kt` — modify

```diff
 interface SettingsRepository :
     EventChannelSettings,
-    RelaySettings {
+    RelaySettings,
+    DebugSettings {
```

**Action 1.1.5** — `M/data/repository/SettingsRepositoryImpl.kt` — modify

```diff
         hideFromRecents = prefs[HIDE_FROM_RECENTS_KEY] ?: false,
+        accessibilityEventLoggingEnabled = prefs[ACCESSIBILITY_EVENT_LOGGING_ENABLED_KEY] ?: false,
     )
@@
         eventChannelSettings: EventChannelSettings,
         relaySettings: RelaySettings,
+        debugSettings: DebugSettings,
     ) : SettingsRepository,
         EventChannelSettings by eventChannelSettings,
-        RelaySettings by relaySettings {
+        RelaySettings by relaySettings,
+        DebugSettings by debugSettings {
```

Context: the mapping line is in the top-level `mapPreferencesToServerConfig` (already under
`@Suppress("CyclomaticComplexMethod")`), outside the class; the class grows by 2 lines (measured within `LargeClass`).

**Action 1.1.6** — `M/di/AppModule.kt` — modify (`RepositoryModule`: 8 functions after plan 70 → 9, under
`TooManyFunctions` 11)

```diff
+import com.danielealbano.androidremotecontrolmcp.data.repository.DebugSettings
+import com.danielealbano.androidremotecontrolmcp.data.repository.DebugSettingsImpl
 import com.danielealbano.androidremotecontrolmcp.data.repository.EventChannelSettings
@@
     abstract fun bindRelaySettings(impl: RelaySettingsImpl): RelaySettings
+
+    /** Binds the debugging-aids settings slice that [SettingsRepositoryImpl] delegates to. */
+    @Binds
+    @Singleton
+    abstract fun bindDebugSettings(impl: DebugSettingsImpl): DebugSettings
```

**Action 1.1.7** — add the new constructor argument at the 6 test construction sites of `SettingsRepositoryImpl(`

| File | Argument added after the `RelaySettingsImpl(…)` argument |
|------|----------------------------------------------------------|
| `T/data/repository/SettingsRepositoryImplTest.kt` | `DebugSettingsImpl(dataStore, changeLogger),` |
| `T/data/repository/SettingsRepositoryLoggingTest.kt` | `DebugSettingsImpl(dataStore, changeLogger),` |
| `T/data/repository/SettingsRepositoryServerRunningTest.kt` | `DebugSettingsImpl(dataStore, changeLogger),` |
| `T/data/repository/SettingsRepositoryUpdateCheckTest.kt` | `DebugSettingsImpl(dataStore, changeLogger),` |
| `T/data/repository/EventChannelSettingsTest.kt` | `DebugSettingsImpl(mockk(relaxed = true), SettingsChangeLogger(RecordingServerLogRepository(), Dispatchers.Unconfined, 0L)),` (wrapped like its `RelaySettingsImpl(…)` neighbour) |
| `TG/data/repository/GeofenceConfigRepositoryTest.kt` | `DebugSettingsImpl(dataStore, changeLogger),` |

**DoD**
- [ ] Key string `accessibility_event_logging_enabled` defined once (`DebugSettingsImpl.kt`); `grep -rn "SettingsRepositoryImpl(" app/src` lists only the production class and the 6 updated sites.
- [ ] No other `SettingsRepository` / `DebugSettings` implementation exists in `app/src` (tests use MockK mocks).

### Task 1.2 — Switch and gated line

**Action 1.2.1** — `M/services/accessibility/AccessibilityEventLogSwitch.kt` — create

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.accessibility

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Mirrors the persisted accessibility-event-logging setting for [McpAccessibilityService.onAccessibilityEvent], which
 * runs on the main thread for every event and must not suspend or touch DataStore.
 *
 * Starts disabled: until the first value arrives nothing is logged. A read failure turns it off and ends the
 * collection; it stays off until the service reconnects (the next [observe]).
 */
internal class AccessibilityEventLogSwitch(
    private val enabledFlow: Flow<Boolean>,
    private val onReadFailure: (Throwable) -> Unit,
) {
    @Volatile
    var isEnabled: Boolean = false
        private set

    /** Collects [enabledFlow] on [dispatcher] inside [scope]; cancelling [scope] stops it. */
    fun observe(
        scope: CoroutineScope,
        dispatcher: CoroutineDispatcher,
    ): Job =
        scope.launch(dispatcher) {
            enabledFlow
                .catch { error ->
                    isEnabled = false
                    onReadFailure(error)
                }.collect { isEnabled = it }
        }
}
```

Context: without `catch`, a DataStore `IOException` would reach the service's `SupervisorJob` scope, which has no
handler, and crash the app. `}.collect` is the form `ktlint --format` produces (chain-method-continuation).

**Action 1.2.2** — `M/services/accessibility/McpAccessibilityService.kt` — modify

```diff
 import android.widget.TextView
+import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
 import dagger.hilt.EntryPoint
@@
     interface NodeCacheEntryPoint {
         fun nodeCache(): AccessibilityNodeCache
     }
 
+    @EntryPoint
+    @InstallIn(SingletonComponent::class)
+    interface SettingsEntryPoint {
+        fun settingsRepository(): SettingsRepository
+    }
+
     private var serviceScope: CoroutineScope? = null
 
     private var nodeCache: AccessibilityNodeCache? = null
 
+    private var eventLogSwitch: AccessibilityEventLogSwitch? = null
+
     private var cacheInvalidationDebouncer: CacheInvalidationDebouncer? = null
@@
         serviceScope = scope
         nodeCache = resolveNodeCache()
+        eventLogSwitch = startEventLogSwitch(scope)
         cacheInvalidationDebouncer =
@@
             AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
-                Log.d(TAG, "Window content changed: package=${event.packageName}")
+                val line = contentChangedLogLine(eventLogSwitch?.isEnabled, event.packageName)
+                if (line != null) Log.d(TAG, line)
             }
@@
         serviceScope?.cancel()
         serviceScope = null
+        eventLogSwitch = null
         currentPackageName = null
@@
         } catch (e: IllegalStateException) {
             Log.w(TAG, "Could not resolve node cache", e)
             null
         }
 
+    /**
+     * Resolves the singleton [SettingsRepository] via Hilt's application entry point. Returns null (and logs) if Hilt
+     * is not initialized; accessibility event logging then stays off.
+     */
+    private fun resolveSettingsRepository(): SettingsRepository? =
+        try {
+            EntryPointAccessors
+                .fromApplication(applicationContext, SettingsEntryPoint::class.java)
+                .settingsRepository()
+        } catch (e: IllegalStateException) {
+            Log.w(TAG, "Could not resolve settings repository", e)
+            null
+        }
+
+    /** Starts mirroring the event-logging setting on [Dispatchers.IO]; null when the settings are unreachable. */
+    private fun startEventLogSwitch(scope: CoroutineScope): AccessibilityEventLogSwitch? {
+        val repository = resolveSettingsRepository() ?: return null
+        val logSwitch =
+            AccessibilityEventLogSwitch(
+                enabledFlow = repository.accessibilityEventLoggingEnabled,
+                onReadFailure = { Log.w(TAG, "Could not read the accessibility event logging setting", it) },
+            )
+        logSwitch.observe(scope, Dispatchers.IO)
+        return logSwitch
+    }
+
@@
 internal fun formatToolName(toolName: String): String = toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }
 
+/**
+ * The logcat line for an [AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED] event, or null when accessibility event
+ * logging is off or its state is unknown (`null`: settings unreachable); then no string is built and nothing is
+ * written. Package name only: never node text or the tree.
+ *
+ * Top-level and `internal` so the gate can be unit-tested without instantiating the service.
+ */
+internal fun contentChangedLogLine(
+    loggingEnabled: Boolean?,
+    packageName: CharSequence?,
+): String? = if (loggingEnabled == true) "Window content changed: package=$packageName" else null
+
```

Context: the one-key flow (Action 1.1.3) avoids re-running `mapPreferencesToServerConfig` (JSON decoding included) in
the service on every settings write, and is already distinct.

**DoD**
- [ ] `onAccessibilityEvent` reads the flag once per content-change event; the `Window state changed` and `Windows changed` lines are byte-identical to before.
- [ ] `onDestroy` order: debouncer cancel → node cache clear → `serviceScope?.cancel()` → `eventLogSwitch = null` (the collector dies with the scope).

### Task 1.3 — Tests

**Action 1.3.1** — `T/data/model/ServerConfigTest.kt` — modify

| Test | Verifies |
|------|----------|
| `default accessibility event logging is false` | `ServerConfig().accessibilityEventLoggingEnabled` is `false` |

**Action 1.3.2** — `T/data/repository/SettingsRepositoryImplTest.kt` — modify (setup unchanged apart from Action
1.1.7). Add `assertFalse(config.accessibilityEventLoggingEnabled)` to `returns default values when no settings stored`.

| Test | Verifies |
|------|----------|
| `UpdateAccessibilityEventLoggingEnabled › enables accessibility event logging` | `true` persisted; `getServerConfig().accessibilityEventLoggingEnabled` is `true` and `repository.accessibilityEventLoggingEnabled.first()` is `true` |
| `UpdateAccessibilityEventLoggingEnabled › disables accessibility event logging` | `true` then `false` reads back `false` on both paths |

**Action 1.3.3** — `T/data/repository/SettingsRepositoryLoggingTest.kt` — modify (its `serverLog`,
`settingsMessages()` and `advanceUntilIdle()` pattern)

| Test | Verifies |
|------|----------|
| `accessibility event logging logs enabled` | `updateAccessibilityEventLoggingEnabled(true)` → single message `Accessibility event logging enabled` |
| `accessibility event logging logs disabled after enable` | enable, `serverLog.clear()`, disable → single message `Accessibility event logging disabled` |
| `accessibility event logging no-op write logs nothing` | `updateAccessibilityEventLoggingEnabled(false)` on the default → no message |

**Action 1.3.4** — `T/data/repository/DebugSettingsImplTest.kt` — create

**Setup**: a file-backed `PreferenceDataStoreFactory.create` DataStore and a `SettingsChangeLogger` over a
`RecordingServerLogRepository`, as in `SettingsRepositoryImplTest.setUp()`; Turbine for the flow.

| Test | Verifies |
|------|----------|
| `flow emits false when unset` | First item `false` |
| `flow follows updates with distinct values only` | `false`, then `true` after enable; a second `true` write and an unrelated key write (`dataStore.edit { it[booleanPreferencesKey("other")] = true }`) emit nothing; `false` after disable |

**Action 1.3.5** — `T/services/accessibility/AccessibilityEventLogSwitchTest.kt` — create

**Setup**: class-level `@OptIn(ExperimentalCoroutinesApi::class)`, as in `CacheInvalidationDebouncerTest`; `runTest`;
`dispatcher = StandardTestDispatcher(testScheduler)`; observe in `observeScope = CoroutineScope(Job() + dispatcher)`,
then `runCurrent()`; `failures` = `mutableListOf<Throwable>()` filled by `onReadFailure`; cancel `observeScope` at the
end of each test.

| Test | Verifies |
|------|----------|
| `is disabled before the first value` | `isEnabled == false` before `runCurrent()` with `MutableStateFlow(true)` |
| `follows the setting on and off` | `MutableStateFlow(false)` → `false`; set `true` → `true`; set `false` → `false` |
| `read failure turns it off and reports once` | `flow { emit(true); throw IOException("boom") }` → `isEnabled == false`, `failures` = that one exception |
| `stops following when its scope is cancelled` | `observeScope.cancel()`, set the flow to `true`, `runCurrent()` → still `false` |

**Action 1.3.6** — `T/services/accessibility/McpAccessibilityServiceTest.kt` — modify: add a `@Nested`
`contentChangedLogLine` class.

| Test | Verifies |
|------|----------|
| `returns null when logging is off` | `contentChangedLogLine(false, "com.example.app")` is `null` |
| `returns null when the switch is unavailable` | `contentChangedLogLine(null, "com.example.app")` is `null` |
| `returns the package line when logging is on` | `contentChangedLogLine(true, "com.example.app")` == `Window content changed: package=com.example.app` |
| `keeps the current text for a null package` | `contentChangedLogLine(true, null)` == `Window content changed: package=null` |

**DoD**
- [ ] Reversal check (US5): replace `loggingEnabled == true` with `loggingEnabled != false` and `returns null when the switch is unavailable` fails; drop `.catch { … }` and `read failure turns it off and reports once` fails.

---

## US2 — The toggle in Settings → General (D1, D2, D5)

The owner turns the logging on and off from the phone; the switch is live (D5), so unlike its neighbours it is not
disabled while the server runs.

**Acceptance criteria**
- [ ] General settings ends with a "Debugging" heading (heading semantics) and one toggle row bound to `serverConfig.accessibilityEventLoggingEnabled`.
- [ ] The whole row is one toggleable control (`Role.Switch`) labelled by its texts: one TalkBack stop, a full-width touch target ≥ 48 dp.
- [ ] Enabled whatever the server status.
- [ ] All three texts in `strings.xml` under `<!-- Plan 71 · US2 -->`; only `MaterialTheme` colours and typography.

### Task 2.1 — Strings

**Action 2.1.1** — `R/values/strings.xml` — modify: insert immediately before `</resources>` (after plan 70's
`<!-- Plan 70 · US8 -->` block)

```diff
+    <!-- Plan 71 · US2 -->
+    <string name="config_debugging_title">Debugging</string>
+    <string name="config_accessibility_event_logging_label">Log accessibility events</string>
+    <string name="config_accessibility_event_logging_description">Writes a logcat line every time any app changes what is on screen (app package only). Turn it on only while debugging; it fills logcat quickly.</string>
 </resources>
```

**DoD**
- [ ] The three names are new in `strings.xml` and each is referenced by Action 2.2.2.

### Task 2.2 — ViewModel and screen

**Action 2.2.1** — `M/ui/viewmodels/MainViewModel.kt` — modify

```diff
         fun updateToolCallIndicatorEnabled(enabled: Boolean) {
             viewModelScope.launch(ioDispatcher) {
                 settingsRepository.updateToolCallIndicatorEnabled(enabled)
             }
         }
 
+        fun updateAccessibilityEventLoggingEnabled(enabled: Boolean) {
+            viewModelScope.launch(ioDispatcher) {
+                settingsRepository.updateAccessibilityEventLoggingEnabled(enabled)
+            }
+        }
+
```

**Action 2.2.2** — `M/ui/screens/settings/GeneralSettingsScreen.kt` — modify (line 1 is already
`@file:Suppress("LongMethod")` after plan 70 US6; unchanged here)

```diff
 import androidx.compose.foundation.rememberScrollState
+import androidx.compose.foundation.selection.toggleable
 import androidx.compose.foundation.text.KeyboardOptions
@@
 import androidx.compose.ui.res.stringResource
+import androidx.compose.ui.semantics.Role
+import androidx.compose.ui.semantics.heading
+import androidx.compose.ui.semantics.semantics
 import androidx.compose.ui.text.input.KeyboardType
@@
                 Switch(
                     checked = serverConfig.hideFromRecents,
                     onCheckedChange = viewModel::updateHideFromRecents,
                     enabled = isEnabled,
                 )
             }
+
+            Spacer(modifier = Modifier.height(24.dp))
+
+            AccessibilityEventLoggingSection(
+                checked = serverConfig.accessibilityEventLoggingEnabled,
+                onCheckedChange = viewModel::updateAccessibilityEventLoggingEnabled,
+            )
         }
     }
 }
+
+/** "Debugging" heading plus the live accessibility-event-logging toggle; never disabled (no restart needed). */
+@Composable
+private fun AccessibilityEventLoggingSection(
+    checked: Boolean,
+    onCheckedChange: (Boolean) -> Unit,
+    modifier: Modifier = Modifier,
+) {
+    Column(modifier = modifier.fillMaxWidth()) {
+        Text(
+            text = stringResource(R.string.config_debugging_title),
+            style = MaterialTheme.typography.labelLarge,
+            modifier = Modifier.semantics { heading() },
+        )
+        Spacer(modifier = Modifier.height(4.dp))
+        Row(
+            modifier =
+                Modifier
+                    .fillMaxWidth()
+                    .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
+            verticalAlignment = Alignment.CenterVertically,
+        ) {
+            Column(modifier = Modifier.weight(1f)) {
+                Text(
+                    text = stringResource(R.string.config_accessibility_event_logging_label),
+                    style = MaterialTheme.typography.bodyLarge,
+                )
+                Text(
+                    text = stringResource(R.string.config_accessibility_event_logging_description),
+                    style = MaterialTheme.typography.bodySmall,
+                    color = MaterialTheme.colorScheme.onSurfaceVariant,
+                )
+            }
+            Switch(checked = checked, onCheckedChange = null)
+        }
+    }
+}
```

Context: `onCheckedChange = null` makes the `Switch` purely visual; the row owns the toggle and the semantics.

**DoD**
- [ ] The new section passes no `enabled` argument (never disabled); no hardcoded text in it.

### Task 2.3 — Tests

**Action 2.3.1** — `T/ui/viewmodels/MainViewModelTest.kt` — modify

| Test | Verifies |
|------|----------|
| `updateAccessibilityEventLoggingEnabled calls repository` | `coVerify { settingsRepository.updateAccessibilityEventLoggingEnabled(true) }` after `advanceUntilIdle()` |

**DoD**
- [ ] Same shape as `updateHideFromRecents calls repository`.

### Manual QA (owner + phone, debug build, dark and light theme)

| ID | Step | Expected |
|----|------|----------|
| M2.1 | Settings → General, scroll to the end | "Debugging" heading, "Log accessibility events" row, switch off |
| M2.2 | Start the MCP server, return to General | The logging row is still enabled; the rows above it are disabled as before |
| M2.3 | TalkBack on: heading navigation, then focus the row | "Debugging" is reachable as a heading; the row is one stop reading label, description and "switch, off"; double-tap turns it on |

---

## US3 — `accessibility_event_logging` extra on the adb configure broadcast (D6)

Debugging is done with adb attached; one more optional boolean extra on the existing `ADB_CONFIGURE` broadcast lets
the owner flip the setting from the computer. Dropping this story drops Actions 4.1.4, 4.2.1 and 4.2.2 with it.

**Acceptance criteria**
- [ ] `--ez accessibility_event_logging true|false` calls `updateAccessibilityEventLoggingEnabled` with that value and logs `Accessibility event logging updated to <value>` at info under `MCP:AdbConfigHandler`; without the extra nothing is called.

### Task 3.1 — Handler and receiver KDoc

**Action 3.1.1** — `M/services/mcp/AdbConfigHandler.kt` — modify

```diff
         applyHideFromRecents(context, intent)
+        applyAccessibilityEventLogging(intent)
         applyHttpsEnabled(intent)
@@
         Log.i(TAG, "Hide from recents updated to $value")
     }
 
+    private suspend fun applyAccessibilityEventLogging(intent: Intent) {
+        if (!intent.hasExtra(EXTRA_ACCESSIBILITY_EVENT_LOGGING)) return
+        val value = intent.getBooleanExtra(EXTRA_ACCESSIBILITY_EVENT_LOGGING, false)
+        settingsRepository.updateAccessibilityEventLoggingEnabled(value)
+        Log.i(TAG, "Accessibility event logging updated to $value")
+    }
+
@@
         internal const val EXTRA_HIDE_FROM_RECENTS = "hide_from_recents"
+        internal const val EXTRA_ACCESSIBILITY_EVENT_LOGGING = "accessibility_event_logging"
         internal const val EXTRA_HTTPS_ENABLED = "https_enabled"
```

**Action 3.1.2** — `M/services/mcp/AdbConfigReceiver.kt` — modify (KDoc usage block)

```diff
  *   --ez hide_from_recents false \
+ *   --ez accessibility_event_logging false \
  *   --ez https_enabled false \
```

**DoD**
- [ ] The extra is optional and follows the `hasExtra` pattern of its neighbours (repeating the broadcast is harmless).

### Task 3.2 — Tests

**Action 3.2.1** — `T/services/mcp/AdbConfigHandlerTest.kt` — modify: three tests in `ConfigureBasicTests`, beside the
`hide_from_recents` tests, same fixtures.

| Test | Verifies |
|------|----------|
| `accessibility_event_logging true enables the setting` | `coVerify { settingsRepository.updateAccessibilityEventLoggingEnabled(true) }` |
| `accessibility_event_logging false disables the setting` | `coVerify { settingsRepository.updateAccessibilityEventLoggingEnabled(false) }` |
| `accessibility_event_logging is not applied when extra is absent` | `coVerify(exactly = 0) { settingsRepository.updateAccessibilityEventLoggingEnabled(any()) }` |

**DoD**
- [ ] Same shape as the three `hide_from_recents` tests.

---

## US4 — Documentation

Defaults belong in PROJECT.md (`CLAUDE.md` §6), and ARCHITECTURE.md currently says every setting needs a restart.

**Acceptance criteria**
- [ ] `docs/PROJECT.md`, `docs/ARCHITECTURE.md` and (with US3) `README.md` state the setting, its default, that it applies live and (with US3) the adb extra; no Mermaid block is added or changed.

### Task 4.1 — `docs/PROJECT.md`

**Action 4.1.1** — modify, Folder Structure: insert after the `services/accessibility/` and `data/repository/` lines
(plan 70 adds no line after either)

```diff
   - `services/accessibility/` — `McpAccessibilityService.kt`, `AccessibilityTreeParser.kt`, `ElementFinder.kt`, `ActionExecutor.kt`, `ActionExecutorImpl.kt`, `AccessibilityServiceProvider.kt`, `AccessibilityServiceProviderImpl.kt`, `TypeInputController.kt`, `TypeInputControllerImpl.kt`, `ScreenInfo.kt`, `CompactTreeFormatter.kt`, `CompactFormatOptions.kt`, `AccessibilityNodeCache.kt`, `AccessibilityNodeCacheImpl.kt`
+  - `services/accessibility/` (event debug logging) — `AccessibilityEventLogSwitch.kt`
@@
   - `data/repository/` — `SettingsRepository.kt`, `SettingsRepositoryImpl.kt`, `RelaySettings.kt`, `RelaySettingsImpl.kt`
+  - `data/repository/` (event debug logging) — `DebugSettings.kt`, `DebugSettingsImpl.kt`
```

Context: plan 70 inserts its `data/repository/` (approval profiles) line directly after the same anchor; put this
line after plan 70's.

**Action 4.1.2** — modify, Android-Specific Conventions → AccessibilityService Best Practices: insert after
`- Keep \`onAccessibilityEvent()\` fast, offload heavy work to coroutines`

```markdown
- Per-event logcat lines for window-content changes (`Window content changed: package=…`) are written only while the `accessibility_event_logging_enabled` setting is on (default off, any build type). The service mirrors the setting (`DebugSettings.accessibilityEventLoggingEnabled`, collected on `Dispatchers.IO`) into a `@Volatile` flag (`AccessibilityEventLogSwitch`), so the event path never reads DataStore; with the setting off no string is built and nothing is written. A failed settings read turns it off until the service reconnects.
```

**Action 4.1.3** — modify, Default Configuration → Accessibility Defaults: insert after
`- **Capabilities**: CAPABILITY_CAN_PERFORM_GESTURES, CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT` (plan 70's
`### Approval Profile Defaults` stays below it)

```markdown
- **Event debug logging** (`accessibility_event_logging_enabled`): Disabled. Settings → General → Debugging; applies immediately, no restart.
```

**Action 4.1.4** — (only with US3) modify the line of Action 4.1.3

```diff
-- **Event debug logging** (`accessibility_event_logging_enabled`): Disabled. Settings → General → Debugging; applies immediately, no restart.
+- **Event debug logging** (`accessibility_event_logging_enabled`): Disabled. Settings → General → Debugging, or `--ez accessibility_event_logging true` over adb; applies immediately, no restart.
```

**DoD**
- [ ] Every name in the new lines matches the code of US1–US3.

### Task 4.2 — `README.md` (adb configuration section; only with US3)

**Action 4.2.1** — modify the `ADB_CONFIGURE` example block

```diff
   --ez hide_from_recents false \
+  --ez accessibility_event_logging false \
   --ez https_enabled false \
```

**Action 4.2.2** — modify the extras table

```diff
 | `hide_from_recents` | boolean | Exclude the app from the Android Recent Tasks list |
+| `accessibility_event_logging` | boolean | Write one logcat line per window-content change of any app (package name only; debugging aid, default off) |
 | `https_enabled` | boolean | Enable HTTPS with TLS |
```

**DoD**
- [ ] The extra name matches `AdbConfigHandler.EXTRA_ACCESSIBILITY_EVENT_LOGGING`.

### Task 4.3 — `docs/ARCHITECTURE.md`

**Action 4.3.1** — modify, Configuration Flow (text under the diagram; the Mermaid block is unchanged)

```diff
 Settings are read at server start time. Changing settings while the server is
 running requires a restart (UI disables config editing when server is running).
+Exception: `accessibility_event_logging_enabled` is observed live by `McpAccessibilityService`
+and stays editable while the server runs.
```

**DoD**
- [ ] No other ARCHITECTURE.md line changes.

---

## US5 — Verify once, publish

Same gates as plan 70 US10: tests and linters run only here, CI is the full-suite and `./gradlew build` gate (the
local machine runs only this plan's JVM tests), CI read once per pushed fix.

### Task 5.1 — Local gates and review (the only lint and test pass of the plan)

- [ ] `ktlint --format`, then `ktlint`, and `detekt` with `--config config/detekt/detekt.yml --build-upon-default-config`, from `~/.lint-tools/` on `app/src/main`, `app/src/test`, `app/src/testDebug`, `app/src/gms`, `app/src/testGms`: zero findings beyond the recorded `testGms` baseline (in particular no `LargeClass` on `SettingsRepositoryImpl`).
- [ ] The new and changed JVM tests (`ServerConfigTest`, `SettingsRepositoryImplTest`, `SettingsRepositoryLoggingTest`, `SettingsRepositoryServerRunningTest`, `SettingsRepositoryUpdateCheckTest`, `EventChannelSettingsTest`, `GeofenceConfigRepositoryTest`, `DebugSettingsImplTest`, `AccessibilityEventLogSwitchTest`, `McpAccessibilityServiceTest`, `MainViewModelTest`, `AdbConfigHandlerTest`) run once locally with `~/.lint-tools/jvm-test/run_jvm_test.py`; failures fixed at the root cause.
- [ ] Grep: no `TODO`, no new `@Suppress`, no hardcoded UI text in the changed Kotlin files, no AI attribution.
- [ ] Reversal checks of Task 1.3 done by hand once (change the guard, see the test fail, restore).
- [ ] `code-reviewer` in plan-compliance mode per D8 (proposal: one round). Every finding is either fixed or brought to the owner with a one-line reason; none is dropped silently.

### Task 5.2 — Publish and read CI

- [ ] Commits in order, staging named files only: US1 → US2 → US3 → US4; push after each story with `git push -u origin feat/accessibility-debug-logging`.
- [ ] PR `feat/accessibility-debug-logging` → `feat/approval-profiles` (`gh pr create --repo mlandolfi90/Lucky-Tool-Android --base feat/approval-profiles`), title `Plan 71: Accessibility event debug logging`, body per TOOLS.md, Manual QA steps of US2 and US5 listed as declared and not taken if the phone is offline.
- [ ] Read the CI run once with `gh run view`: all jobs green, `0` lines matching `w: file:///`, the new test classes listed as passed. If a job fails: fix the root cause, push, and read the new run once.

### Manual QA (owner + phone, debug build, adb attached)

`COUNT` = `adb shell "logcat -d | grep -c 'Window content changed: package='"` (no `-s` filter: the tag contains a
colon, which logcat would read as `tag:priority`; the grep runs on the device, so it works from a Windows host too).

| ID | Step | Expected |
|----|------|----------|
| M5.1 | Setting off; `adb logcat -c`; use two apps for one minute; `COUNT` | `0` |
| M5.2 | Turn the setting on (UI or US3 extra) without restarting anything; `adb logcat -c`; repeat the minute; `COUNT` | > 0; each matching line is `Window content changed: package=<pkg>` with no other field |
| M5.3 | Turn it off again; `adb logcat -c`; repeat the minute; `COUNT` | `0` |
| M5.4 | Setting on; force-stop the app, re-enable the accessibility service if Android asks; `adb logcat -c`; repeat the minute; `COUNT` | > 0 (persisted) |

---

## Review findings — plan-reviewer, round 1 of 1 (2026-10-13)

The reviewer ran ktlint 1.8.0 and detekt 2.0.0-alpha.5 (with plan 70's `detekt.yml` and the repo `.editorconfig`) on
scratch copies of the 8 changed main files with every diff applied; all diff anchors applied cleanly
(`InvalidPackageDeclaration` came from the scratch layout). No Gradle build or test run (no Android SDK). Verified OK:
header and structure, anchors, no conflict with plan 70, imports and line lengths, threading of the event path, Hilt
entry point, Compose toggle semantics, nothing new reaching logcat, owner decisions honoured, test feasibility.

| ID | Class | Finding | Resolution |
|----|-------|---------|------------|
| R1 | CRITICAL | The new method in `SettingsRepositoryImpl` triggers detekt `LargeClass` (2 lines of budget left); no new `@Suppress` allowed | New `DebugSettings` / `DebugSettingsImpl` slice delegated like `RelaySettings` (Actions 1.1.2–1.1.7): +2 class lines, `@Binds` in `RepositoryModule`, the 6 test construction sites, a slice test, PROJECT.md folder line |
| R2 | WARNING | ktlint `chain-method-continuation` on `.collect` after the `catch` lambda | `}.collect { isEnabled = it }` (Action 1.2.1) |
| R3 | WARNING | Nothing stops implementation if plan 70's code is not on the base branch yet | Precondition block before Task 1.1 (three checks, else stop and ask) |
| R4 | WARNING | PROJECT.md defaults line named the adb extra although US3 is droppable | adb clause moved to Action 4.1.4 "only with US3"; US3 intro lists the dependent actions |
| R5 | WARNING | The "settings unreachable → nothing logged" criterion had no test | `contentChangedLogLine` takes `Boolean?` (null = off); test `returns null when the switch is unavailable`; reversal check updated |
| R6 | WARNING | Logging tests duplicated `SettingsRepositoryLoggingTest`'s harness; "disabled" untested | Logging tests moved to `SettingsRepositoryLoggingTest` (enabled, disabled, no-op); `SettingsRepositoryImplTest` setup unchanged |
| R7 | WARNING | strings.xml constraint vs the Kotlin `SETTINGS` log text | Constraint now covers UI text; server-log texts stay in Kotlin like existing settings messages |
| R8 | WARNING | ARCHITECTURE.md says every setting needs a restart | Action 4.3.1 (text only, Mermaid unchanged) |
| R9 | WARNING | Tasks 2.1–4.2 had no DoD | DoD added to every task |
| R10 | WARNING (OWNER) | `CLAUDE.md` says repeat `code-reviewer` until clean; the plan said one round | Owner decision D8 (proposal: one round, as plan 70); Task 5.1 refers to D8 |
| R11 | WARNING | `logcat -s MCP:AccessibilityService` is parsed as `tag:priority`, so the count is always 0 | Manual QA uses `adb shell "logcat -d \| grep -c …"` |
| R12 | INFO | `runCurrent` / `StandardTestDispatcher` need `@OptIn(ExperimentalCoroutinesApi::class)` | Added to the Action 1.3.5 setup |
| R13 | INFO | Test cancelled the `Job` while the service cancels the scope | Test observes in a child scope and cancels it |
| R14 | INFO | After a read failure the switch stays off until reconnect, unstated | Stated in the class KDoc, US1 AC and PROJECT.md bullet; no retry (small debug aid; a retry would be new behaviour) |
| R15 | INFO | The service re-mapped the whole `ServerConfig` (JSON included) on every settings write | Service collects the one-key, distinct `DebugSettings.accessibilityEventLoggingEnabled` |
| R16 | INFO | Composable emitted 3 siblings without a `modifier`; heading lacked heading semantics | `Column(modifier)` root with `modifier` parameter; `semantics { heading() }`; Manual QA M2.3 checks it |
| R17 | INFO (OWNER) | D4 reason understated the privacy cost | D4 reason reworded (app-usage timeline, comparable to the existing window-state line) |
| R18 | INFO (OWNER) | D7 omitted that the record plan shifts too | D7 names both shifts |
| R19 | INFO | CRLF claim wrong here; plan file untracked; full suite only in CI | "Preserve each file's existing line endings"; the planning run commits the plan; US5 states CI is the full-suite and build gate |
