<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 69 — Zero Kotlin compiler warnings, and a gate that keeps them at zero

**Baseline**: CI run `38339761087` on `32124ae` (same tree as `6cc922a`): 272 `w: file:///` log lines = **43 distinct
warnings in 29 files, all in `:app`**. Zero in `:privacy`, `:privacy-benchmark`, `:compose-test-app`, `:e2e-tests`.

**Branch**: `feat/kotlin-warnings-zero`, stacked on `feat/lucky-brand-icon-and-name` (PR base = that branch; nothing is
merged).

**Paths**: `M/` = `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/`,
`G/` = `app/src/gms/kotlin/com/danielealbano/androidremotecontrolmcp/`,
`T/` = `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/`,
`TD/` = `app/src/testDebug/kotlin/com/danielealbano/androidremotecontrolmcp/`.

**Constraints for every action**

- Working-tree files are CRLF (`core.autocrlf=true`); edit line-ending-aware.
- Import order is ktlint_official lexicographic (`kotlinx.*` sorts after `com.*`). Remove every import a change leaves unused.
- No `@Suppress` anywhere except the single one in Task 4.3, which needs the user's explicit approval for that line only.
- No tests or linters during implementation; one pass at the end (Task 6.1).
- Each `.kt` file belongs to exactly one user story, so commits split cleanly by story. `app/build.gradle.kts` is
  touched by US1 and US5 in separate hunks and separate commits.

**Review budget**: one `plan-reviewer` round before implementation, one `code-reviewer` compliance round after.
Findings that break an acceptance criterion are fixed; anything else is reported to the user in one line, with no new
round.

---

## US1 — Move `hiltViewModel` to its new package

Hilt 1.4.0 moved `hiltViewModel` to `androidx.hilt.lifecycle.viewmodel.compose` (the old one inlines the new one), and
the code will import that artifact directly, so it is declared directly. `hilt-navigation-compose` stays: it supplies
the `navigation-compose` version, which the BOM does not manage.

**Acceptance criteria**
- [x] No source file imports `androidx.hilt.navigation.compose.hiltViewModel`.
- [x] `androidx.hilt:hilt-lifecycle-viewmodel-compose` is declared in the catalog and in `:app` dependencies; `hilt-navigation-compose` is still declared.
- [x] The 18 warnings in the 15 files below are gone.

### Task 1.1 — Declare the artifact

**Action 1.1.1** — modify `gradle/libs.versions.toml` (libraries section, after `androidx-hilt-compiler`):

```diff
 androidx-hilt-compiler = { group = "androidx.hilt", name = "hilt-compiler", version.ref = "androidx-hilt" }
+androidx-hilt-lifecycle-viewmodel-compose = { group = "androidx.hilt", name = "hilt-lifecycle-viewmodel-compose", version.ref = "hilt-navigation-compose" }
```

`version.ref` is `hilt-navigation-compose` on purpose: that artifact depends on this one via `api` at the same version,
so one ref keeps them aligned on every bump.

**Action 1.1.2** — modify `app/build.gradle.kts` (Hilt dependency block):

```diff
     implementation(libs.hilt.android)
     implementation(libs.hilt.navigation.compose)
+    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
     ksp(libs.hilt.compiler)
```

**Definition of Done**
- [x] Both diffs applied; `hilt-navigation-compose` is `1.4.0` (unchanged).

### Task 1.2 — Swap the import in 15 files

**Action 1.2.1** — modify each file below with the same one-line change, at the import's current position (the new
import keeps lexicographic order with its neighbours in every file):

```diff
-import androidx.hilt.navigation.compose.hiltViewModel
+import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
```

| File | Warnings |
|------|----------|
| `G/ui/screens/settings/GeofenceDestinations.kt` | 13:52, 22:52 |
| `G/ui/screens/settings/GeofenceEventSourceItem.kt` | 28:52 |
| `M/ui/screens/AboutScreen.kt` | 70:40 |
| `M/ui/screens/ApprovalScreen.kt` | 68:36 |
| `M/ui/screens/MainScreen.kt` | 41:32, 42:40 |
| `M/ui/screens/ServerTabScreen.kt` | 21:32 |
| `M/ui/screens/SettingsScreen.kt` | 39:32, 40:42 |
| `M/ui/screens/settings/AccessSettingsScreen.kt` | 60:34 |
| `M/ui/screens/settings/GeneralSettingsScreen.kt` | 53:32 |
| `M/ui/screens/settings/McpToolsSettingsScreen.kt` | 212:32 |
| `M/ui/screens/settings/OAuthClientsScreen.kt` | 65:40 |
| `M/ui/screens/settings/PermissionsSettingsScreen.kt` | 66:32 |
| `M/ui/screens/settings/PrivacySettingsScreen.kt` | 53:35 |
| `M/ui/screens/settings/SecuritySettingsScreen.kt` | 49:32 |
| `M/ui/screens/settings/StorageSettingsScreen.kt` | 74:32 |

`ServerScreen.kt`, `TunnelSettingsScreen.kt` and `LogsScreen.kt` get the same swap inside US3/US2, which own them.

**Definition of Done**
- [x] All 15 files changed; call sites untouched.
- [x] Tests: none new. The guard is US5's gate: the old import is a deprecation warning, which fails compilation once the gate is on.

---

## US2 — Remove the null-safety noise the compiler already proves

K2 smart casts make these `?.`, `!!` and repeated null checks dead, so removing them changes no accept/reject decision
(critical in OAuth). The new tests pin the branches the simplified conditions guard, uncovered today.

**Acceptance criteria**
- [x] The 11 warnings below are gone (LogsScreen 2, AppIconCache 1, EventChannelService 1, OAuth 6, E2EConfigReceiverTest 1).
- [x] OAuth accept/reject behaviour is identical for every input, pinned by the tests in Task 2.2.

### Task 2.1 — UI and services

**Action 2.1.1** — modify `M/ui/screens/LogsScreen.kt` (warnings 69:32, 199:33). `ServerLogEntry.message` is a
non-null `String`, and `!entry?.message.isNullOrEmpty()` smart-casts `entry`:

```diff
-import androidx.hilt.navigation.compose.hiltViewModel
+import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
@@
             if (!entry?.message.isNullOrEmpty()) {
                 Text(
-                    text = entry?.message.orEmpty(),
+                    text = entry.message,
```

**Action 2.1.2** — modify `M/services/apps/AppIconCache.kt` (warning 136:58; `ResolveInfo.loadLabel` never returns
null by contract):

```diff
                     val pkgName = resolveInfo.activityInfo?.packageName ?: return@mapNotNull null
-                    val label = resolveInfo.loadLabel(pm)?.toString() ?: return@mapNotNull null
+                    val label = resolveInfo.loadLabel(pm).toString()
                     if (label.isBlank() || label == pkgName) return@mapNotNull null
```

**Action 2.1.3** — modify `M/services/channel/EventChannelService.kt` (warning 66:44; inside the
`ACTION_GEOFENCE_EVENT` branch `intent` is smart-cast non-null):

```diff
-            ACTION_GEOFENCE_EVENT -> intent?.let { geofenceController.handleGeofenceIntent(it) }
+            ACTION_GEOFENCE_EVENT -> geofenceController.handleGeofenceIntent(intent)
```

**Action 2.1.4** — modify `TD/debug/E2EConfigReceiverTest.kt` (warning 52:29). A null superclass must fail the test
explicitly instead of through an unchecked call:

```diff
 import org.junit.jupiter.api.Test
+import org.junit.jupiter.api.assertNotNull
@@
-        val injectedField = receiver.javaClass.superclass.getDeclaredField("injected")
+        val hiltBaseClass = receiver.javaClass.superclass
+        assertNotNull(hiltBaseClass, "E2EConfigReceiver must extend the Hilt-generated base class")
+        val injectedField = hiltBaseClass.getDeclaredField("injected")
```

`org.junit.jupiter.api.assertNotNull` is the Kotlin top-level function with a `returns() implies (actual != null)`
contract, which is what smart-casts `hiltBaseClass`.

**Tests** — **File**: `T/services/apps/AppIconCacheTest.kt` (create). **Setup**: `Context` mock whose
`packageManager` is a `mockk<PackageManager>`; `queryIntentActivities(any<Intent>(), any<PackageManager.ResolveInfoFlags>())`
returns `mockk<ResolveInfo>` items with `activityInfo` assigned as a field and `loadLabel(pm)` stubbed (with
`isReturnDefaultValues` it would return null). `ResolveInfoFlags.of(0)` returns null on the JVM, which
`any<PackageManager.ResolveInfoFlags>()` accepts, so it needs no static mock.

| Test | Verifies |
|------|----------|
| `getLaunchableApps returns loadLabel text as app name` | Label comes from `loadLabel(pm).toString()` |
| `getLaunchableApps skips blank labels and labels equal to package name` | The blank / equals-package filter still drops rows |
| `getLaunchableApps skips entries without activityInfo` | The untouched `activityInfo?.packageName` guard still drops rows |
| `getLaunchableApps dedupes by package and sorts case-insensitively` | `distinctBy` and the lowercase sort |

**File**: `T/services/channel/EventChannelServiceTest.kt` (modify; new `@Nested` `onStartCommand`). **Setup**:
`EventChannelService()` instantiated on the JVM, no `Dispatchers.setMain` (building the service's scope dispatches
nothing and neither case uses it; `setMain` is an opt-in API whose warning the US5 gate would turn into an error);
`geofenceController` assigned a relaxed mock. Rewrite the file's header comment (lines 1–4), which says the service
cannot be instantiated on the JVM: it now covers `onStartCommand` routing, while full lifecycle (`ACTION_START`/`STOP`,
listeners, config observer) still needs Robolectric or instrumented tests. If the service cannot be instantiated on
the JVM, stop and ask the user — do not delete or suppress.

| Test | Verifies |
|------|----------|
| `onStartCommand with ACTION_GEOFENCE_EVENT forwards the same intent to geofenceController` | Same `Intent` instance reaches `handleGeofenceIntent`; returns `START_STICKY` |
| `onStartCommand with null intent returns START_STICKY and does nothing` | Sticky restart with a null intent never calls the controller |

**Definition of Done**
- [x] Four actions applied; tests written per the tables.

### Task 2.2 — OAuth

**Action 2.2.1** — modify `M/mcp/oauth/OAuthAccessValidator.kt` (warning 31:22; `valid` already contains
`client != null`):

```diff
         val valid = OAuthPolicy.resourceMatches(claims.audience, canonicalResource) && client != null
-        if (valid && client != null) {
+        if (valid) {
```

**Action 2.2.2** — modify `M/mcp/oauth/OAuthTokenGrants.kt` (warnings 96:21, 106:44; `rejected` already contains
`claims == null` and `client == null`; `OAuthClient.clientName` is `String?`, so the elvis stays):

```diff
-    if (rejected || claims == null) {
+    if (rejected) {
         respondOAuthError(HttpStatusCode.BadRequest, "invalid_grant")
         return
     }
@@
-        "OAuth token refreshed for ${client?.clientName ?: claims.clientId}",
+        "OAuth token refreshed for ${client.clientName ?: claims.clientId}",
```

**Action 2.2.3** — modify `M/mcp/oauth/OAuthRoutes.kt` (warnings 133:28, 134:32, 135:38; the `redirectValid` guard
above is unchanged). The aliases and their comment stay — minimal diff, and the comment tells the reader why the values
are safe past the guard:

```diff
     // redirectValid guarantees the following are non-null.
-    val safeClient = client!!
-    val safeClientId = clientId!!
-    val safeRedirectUri = redirectUri!!
+    val safeClient = client
+    val safeClientId = clientId
+    val safeRedirectUri = redirectUri
```

**Tests** — **File**: `T/mcp/oauth/OAuthAccessValidatorTest.kt` (modify)

| Test | Verifies |
|------|----------|
| `rejected token never touches last-used` | Wrong audience, and separately `getClient` returning null: `validate` returns false and `touchLastUsed` is called exactly 0 times |

**File**: `T/integration/OAuthFlowIntegrationTest.kt` (modify). **Setup**: the private helper `refreshRequest` takes
`clientId: String?` and appends `client_id` only when non-null; existing callers are unchanged.

| Test | Verifies |
|------|----------|
| `refresh rejects invalid refresh token` | Garbage `refresh_token` (claims == null) → 400 `invalid_grant` |
| `refresh rejects client_id mismatch` | Present, different `client_id` → 400 `invalid_grant`; a later refresh with the right `client_id` still succeeds (jti not rotated) |
| `refresh rejects revoked client` | After `clientRepository.revoke(clientId)` (client == null) → 400 `invalid_grant` |
| `refresh accepts omitted client_id` | No `client_id` → 200 with new tokens |
| `authorize with unknown or missing client_id returns 400 HTML without Location` | Unknown id and a raw `GET /authorize` without `client_id` → 400 `text/html`, no `Location` header |

**Definition of Done**
- [x] Three actions applied; tests written per the tables.

---

## US3 — Replace the deprecated clipboard API

`LocalClipboardManager` is replaced by `LocalClipboard`, whose `setClipEntry` is `suspend`. One shared helper keeps the
clip identical to today's (`ClipData.newPlainText("plain text", text)`, the exact label compose-ui 1.12.0's
`setText` used) and gives the change a JVM-testable unit; each call site launches it from `rememberCoroutineScope()`.

**Acceptance criteria**
- [x] No source file references `LocalClipboardManager` or `ClipboardManager.setText`.
- [x] The helper builds the same `ClipData` as before (label `"plain text"`, same text), pinned by a JVM test; the three call sites and the ServerScreen toast are covered by the Manual QA Steps only (no Robolectric / Compose UI test infrastructure).
- [x] The 3 clipboard warnings and the 5 Hilt warnings of `ServerScreen.kt` / `TunnelSettingsScreen.kt` are gone.

### Task 3.1 — Shared helper

**Action 3.1.1** — create `M/ui/components/ClipboardExtensions.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.ui.components

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

/** Label compose-ui's deprecated `ClipboardManager.setText` used; kept so copied clips look the same to other apps. */
internal const val PLAIN_TEXT_CLIP_LABEL = "plain text"

/** Copies [text] as a plain-text clip: the suspend replacement for `ClipboardManager.setText`. */
internal suspend fun Clipboard.setPlainText(text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText(PLAIN_TEXT_CLIP_LABEL, text)))
}
```

**Tests** — **File**: `T/ui/components/ClipboardExtensionsTest.kt` (create). **Setup**: `mockkStatic(ClipData::class)`
with `newPlainText` returning a `mockk<ClipData>` (under `isReturnDefaultValues` it returns null); relaxed
`mockk<Clipboard>`; capture the `ClipEntry` with a slot; `runTest`.

| Test | Verifies |
|------|----------|
| `setPlainText sets a ClipEntry wrapping ClipData newPlainText with the plain text label` | One `setClipEntry` call whose `clipData` is the mock returned for `("plain text", text)` |
| `setPlainText passes the text unchanged` | Multi-line and empty strings reach `newPlainText` exactly |

**Definition of Done**
- [x] Helper created; tests written per the table.

### Task 3.2 — Call sites

**Action 3.2.1** — modify `M/ui/screens/ServerScreen.kt` (warnings 71:32, 72:42, 73:42, 76:28, 77:40). Imports: add
`androidx.compose.runtime.rememberCoroutineScope`, `androidx.compose.ui.platform.LocalClipboard`,
`com.danielealbano.androidremotecontrolmcp.ui.components.setPlainText`, `kotlinx.coroutines.launch`; remove
`androidx.compose.ui.platform.LocalClipboardManager` and `androidx.compose.ui.text.AnnotatedString`; swap the Hilt
import as in Action 1.2.1.

```diff
     val context = LocalContext.current
-    val clipboardManager = LocalClipboardManager.current
+    val clipboard = LocalClipboard.current
+    val scope = rememberCoroutineScope()
@@
                 onCopyAll = { text ->
-                    clipboardManager.setText(AnnotatedString(text))
-                    Toast.makeText(context, copiedToClipboardMessage, Toast.LENGTH_SHORT).show()
+                    scope.launch {
+                        clipboard.setPlainText(text)
+                        Toast.makeText(context, copiedToClipboardMessage, Toast.LENGTH_SHORT).show()
+                    }
                 },
```

**Action 3.2.2** — modify `M/ui/screens/settings/TunnelSettingsScreen.kt` (warnings 66:32, 527:28). Same import
changes as Action 3.2.1 (no `Toast`).

```diff
     var showToken by remember { mutableStateOf(false) }
-    val clipboardManager = LocalClipboardManager.current
+    val clipboard = LocalClipboard.current
+    val scope = rememberCoroutineScope()
@@
-            IconButton(onClick = { clipboardManager.setText(AnnotatedString(serviceUrl)) }) {
+            IconButton(onClick = { scope.launch { clipboard.setPlainText(serviceUrl) } }) {
```

**Action 3.2.3** — modify `M/ui/screens/settings/ChannelSettingsScreen.kt` (warning 63:28). Same import changes as
Action 3.2.1 minus the Hilt swap (this file has none).

```diff
     var tokenVisible by rememberSaveable { mutableStateOf(false) }
-    val clipboardManager = LocalClipboardManager.current
+    val clipboard = LocalClipboard.current
+    val scope = rememberCoroutineScope()
@@
                             IconButton(
-                                onClick = { clipboardManager.setText(AnnotatedString(authTokenInput)) },
+                                onClick = {
+                                    val token = authTokenInput
+                                    scope.launch { clipboard.setPlainText(token) }
+                                },
                             ) {
```

`authTokenInput` is a delegated `State`; reading it before `launch` copies the value shown at click time, as the
synchronous call did.

**Definition of Done**
- [x] Three call sites migrated; the `AnnotatedString` import is removed from all three (it has no other use in them).

**Manual QA Steps** (declared, not run by the implementer): on the phone, tap Copy in Server (toast appears), in
Tunnel › Cloudflare service URL, and in Event Channel › auth token; paste each elsewhere and compare.

---

## US4 — Replace deprecated platform APIs

Each of these has a replacement only at a higher API level or with different validation, so each one is chosen to keep
today's result on every supported level (minSdk 33, compileSdk 37).

**Acceptance criteria**
- [x] The 6 platform-deprecation warnings are gone (`CountryDisplay` 1, `labeledBy` 4, `NotificationProviderImpl` 1).
- [x] `CountryDisplay.name` returns the same value for every input it accepts today and never throws.
- [x] `labeledByText` is unchanged on API 33–35 and picks the platform's single-label choice (the last added) on 36+.

### Task 4.1 — `Locale` constructor

**Action 4.1.1** — modify `M/geo/CountryDisplay.kt` (warning 24:20). `Locale.of` is not public API at minSdk 33;
`Locale.Builder.setRegion` throws on ill-formed regions, but `normalize()` only lets `[A-Z]{2}` through, which is
always a valid region:

```diff
-        val name = Locale("", c).getDisplayCountry(Locale.getDefault())
+        val name = Locale.Builder().setRegion(c).build().getDisplayCountry(Locale.getDefault())
```

**Tests** — **File**: `T/geo/CountryDisplayTest.kt` (modify)

| Test | Verifies |
|------|----------|
| `name never throws for any two-letter code` | All 676 pairs `AA`..`ZZ`, plus lowercase and padded forms, return without throwing |
| `name normalizes case and whitespace before resolving` | `name(" us ") == name("US")`; `name("")` is null |
| `name matches an independent region lookup for every two-letter code` | For all 676 codes, `name(c)` equals the same rule applied to `Locale.forLanguageTag("und-$c").getDisplayCountry(Locale.getDefault())` (empty or echoed code → null) |

**Definition of Done**
- [x] Action applied; tests written per the table.

### Task 4.2 — `labeledBy`

**Action 4.2.1** — modify `M/services/accessibility/AccessibilityTreeParser.kt` (warning 226:22).
`AccessibilityNodeInfoCompat.getLabeledByList()` (androidx.core 1.19.0, already a dependency via `core-ktx`) is not
deprecated and handles the API split itself: below 36 it wraps the platform getter in a 0/1-element list. The platform
getter returns the most recently added label, so `lastOrNull()` keeps today's choice. Unwrapping keeps the platform
`text` (compat `getText()` rebuilds spans).

```diff
 import android.view.accessibility.AccessibilityWindowInfo
+import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
 import javax.inject.Inject
@@
         /**
-         * Resolves the text of the node's label association ([AccessibilityNodeInfo.getLabeledBy]),
-         * preferring the label's text and falling back to its content description. Returns null when
-         * there is no label or it has no text. [getLabeledBy] can throw [IllegalStateException] on
-         * stale nodes, so failures are swallowed to null.
+         * Resolves the text of the node's label association ([AccessibilityNodeInfoCompat.getLabeledByList]),
+         * preferring the label's text and falling back to its content description. Returns null when
+         * there is no label or it has no text. The last label is taken because the platform's single-label
+         * getter returns the most recently added one. Label lookups can throw [IllegalStateException] on
+         * stale nodes, so failures are swallowed to null.
          */
         private fun readLabeledByText(node: AccessibilityNodeInfo): String? =
             runCatching {
-                node.labeledBy?.let { label ->
+                AccessibilityNodeInfoCompat.wrap(node).labeledByList.lastOrNull()?.unwrap()?.let { label ->
                     (label.text?.toString() ?: label.contentDescription?.toString())?.takeIf { it.isNotEmpty() }
                 }
             }.getOrNull()
```

Known API 36+ edge cases (accepted, both turn a `null` into an older label's text): the newest label was removed while
others remain; the newest label's node cannot be resolved.

**Tests** — **File**: `T/services/accessibility/AccessibilityTreeParserTest.kt` (modify; warnings 738:39, 745:39,
753:26). **Setup**: private helper `stubLabeledByList(node, vararg labels)` that maps each label to a
`mockk<AccessibilityNodeInfoCompat>` whose `unwrap()` returns it and stubs `AccessibilityNodeInfoCompat.wrap(node)`;
every test using it wraps `mockkStatic(AccessibilityNodeInfoCompat::class)` in `try/finally { unmockkStatic(...) }`
(same pattern as `AppManagerTest` with `PackageInfoCompat`). No test stubs `node.labeledBy` any more.

| Test | Verifies |
|------|----------|
| `parseNode resolves labeledBy text with contentDescription fallback` (update) | Label text first, content description when text is null, via the compat list |
| `parseNode labeledBy failure yields null` (update) | `labeledByList` throwing `IllegalStateException` → null |
| `parseNode labeledBy uses the most recently added label` | Two labels with different text → the last one's text |
| `parseNode labeledBy without labels yields null` | Empty list → null |
| `parseNode labeledBy ignores empty label text` | Text `""`, no content description → null |
| `parseNode labeledBy below API 36 reads the platform single-label getter` | No static mock: on the JVM `SDK_INT` is 0, so the real compat calls `node.getLabeledBy()`. **Setup**: stub it dynamically with `every { node invoke "getLabeledBy" withArguments listOf<Any>() } returns label`, so the test never references the deprecated API at compile time |

**Definition of Done**
- [x] Action applied; tests written per the table.

### Task 4.3 — Background-activity-start mode

**Action 4.3.1** — modify `M/services/notifications/NotificationProviderImpl.kt` (warning 185:37).
`MODE_BACKGROUND_ACTIVITY_START_ALLOWED` was deprecated in API 36. No warning-free replacement keeps behaviour on
every supported level: `MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS` (API 36) makes `setPendingIntentBackgroundActivityStartMode`
throw `IllegalArgumentException` on Android 14; the literal value would trade the warning for a lint `WrongConstant`;
raising minSdk to 36 drops Android 13–15. **This action requires the user's explicit approval** of one line-scoped
suppression for this constant (CLAUDE.md, "No linting suppression"); it is removed when minSdk reaches 36. If the user
does not approve, this task is replaced by the option the user picks before this task is implemented.

```diff
 private fun buildBalOptions(): Bundle? =
     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
+        // ALLOWED is deprecated since API 36, but its replacement ALLOW_ALWAYS does not exist below API 36 (Android 14
+        // throws on it). Keep it until minSdk reaches 36, then switch to ALLOW_ALWAYS and drop this suppression.
+        @Suppress("DEPRECATION")
+        val allowed = ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
         ActivityOptions
             .makeBasic()
             .apply {
-                setPendingIntentBackgroundActivityStartMode(
-                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
-                )
+                setPendingIntentBackgroundActivityStartMode(allowed)
             }.toBundle()
```

Tests: none new — the mode passed is unchanged and no test can set `SDK_INT` (no Robolectric).

**Definition of Done**
- [x] Action applied.

---

## US5 — The gate: any Kotlin compiler warning fails the build

Applied **after** US1–US4 so no commit is red. The flag lives in each module's existing `kotlin.compilerOptions`
block, so every compile task of every variant and test source set (and every CI job that compiles) enforces it; no
`ci.yml` or Makefile change is needed. KSP warnings are out of scope (not compiler warnings; today there are none).

**Acceptance criteria**
- [x] All five Kotlin modules set `allWarningsAsErrors`.
- [ ] A deliberately introduced warning fails CI (Task 6.2).

### Task 5.1 — Enable the flag

**Action 5.1.1** — modify `app/build.gradle.kts`:

```diff
 kotlin {
     compilerOptions {
         jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
+        // Zero-warning gate: any Kotlin compiler warning fails the build (every variant and test source set).
+        allWarningsAsErrors.set(true)
     }
 }
```

**Action 5.1.2** — modify `privacy/build.gradle.kts`, `privacy-benchmark/build.gradle.kts`,
`compose-test-app/build.gradle.kts` and `e2e-tests/build.gradle.kts`, each in its existing block:

```diff
     compilerOptions {
         jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
+        allWarningsAsErrors.set(true)
     }
```

**Definition of Done**
- [x] Five files changed.

---

## US6 — Verify once, publish

`ci.yml` runs only on pull requests to `main`/`feat/**` (and pushes to `main`), so a CI run exists only once the PR
does: the PR is opened before CI is read.

### Task 6.1 — Local gates and review (the only lint pass of the plan)

- [x] `ktlint` and `detekt` from `~/.lint-tools/` on `app/src/main`, `app/src/test`, `app/src/testDebug`, `app/src/gms`: zero findings beyond the recorded `testGms` baseline.
- [x] Grep: no `androidx.hilt.navigation.compose.hiltViewModel`, no `LocalClipboardManager`, no match for the regex `\.labeledBy\b` in `app/src`, exactly one `@Suppress` added by this plan.
- [x] One `code-reviewer` round in plan-compliance mode; blocking findings fixed before pushing.

### Task 6.2 — Publish and read CI once

- [ ] Commits in order, staging named files only: plan doc → US1 → US2 → US3 → US4 → US5. Push once.
- [ ] PR `feat/kotlin-warnings-zero` → `feat/lucky-brand-icon-and-name` (`gh pr create --repo mlandolfi90/Lucky-Tool-Android`), body per TOOLS.md, Manual QA Steps of US3 listed as declared and not taken.
- [ ] At the same time, branch `probe/werror-gate` = final head + one commit reverting Action 4.1.1 only, with a **draft** PR against `feat/kotlin-warnings-zero`, so both CI runs overlap.
- [ ] Read each CI run **once** with `gh run view`:
  - PR: all five jobs green, `0` lines matching `w: file:///` in the log, the new and updated tests listed as passed.
  - Probe: the compile jobs fail with `warnings found and -Werror specified` naming `CountryDisplay.kt:24`.
- [ ] Close the probe PR and delete the probe branch.

---

## Review findings — plan-reviewer, round 1 of 1 (2026-10-13)

Coverage confirmed: 43/43 warnings, one action each; every diff's context matches the files at `6cc922a`.

| ID | Class | Finding | Resolution |
|----|-------|---------|------------|
| B1 | BLOCKING | CI runs only on PRs, but the plan read CI before opening the PR | US6 reordered: PR and probe PR opened before the single CI read |
| B2 | BLOCKING | `Dispatchers.setMain` is opt-in; its warning becomes an error under the gate | `EventChannelServiceTest` setup drops `setMain` (unused by both cases) |
| N1 | non-blocking | `app/build.gradle.kts` is touched by US1 and US5 | Constraint narrowed to `.kt` files |
| N2 | non-blocking | US4 had no "why"; per-task DoD missing; long "why" texts | US4 "why" added, per-task DoD added, US1/US2 trimmed |
| N3 | non-blocking | `.labeledBy` grep also matches `labeledByList`/`labeledByText` | Regex `\.labeledBy\b` |
| N4 | non-blocking | `AnnotatedString` grep already verified | DoD states it |
| N5 | non-blocking | Conditional `mockkStatic(ResolveInfoFlags)` | Removed; `any()` accepts the JVM's null |
| N6 | non-blocking | `EventChannelServiceTest` header contradicts the new tests | Header rewrite added to the test setup |
| N7 | non-blocking | API 33–35 `labeledBy` path unpinned | Test with a dynamic `getLabeledBy` stub added |
| N8 | non-blocking | "same value for every input" unpinned | 676-code equivalence test added |
| N9 | non-blocking | US3 criterion claimed call-site coverage the JVM cannot give | Criterion states Manual QA for the call sites |
| N10 | non-blocking | Two version refs for one `api` pair | `version.ref = "hilt-navigation-compose"` |
| N11 | non-blocking | `safe*` aliases now redundant | Kept: declared minimal-diff choice |
| N12 | non-blocking | Token read after dispatch in ChannelSettingsScreen | Captured before `launch` |
| N13 | non-blocking | `getLabeledByList()` resolves every label on API 36+ | Accepted: 0–1 labels in practice |
| N14 | non-blocking | Copied tokens could be marked `EXTRA_IS_SENSITIVE` | Out of scope (changes the clip); offered to the user separately |
| N15 | non-blocking | Comment said "throws on API 34"; threshold is 36 | Comment reworded |
| N16 | non-blocking | No new test for Task 4.3 | Accepted: constant unchanged, branch unreachable on the JVM |

**User decision, 2026-10-13 — Task 4.3:** option A approved: the single line-scoped `@Suppress("DEPRECATION")` on
`MODE_BACKGROUND_ACTIVITY_START_ALLOWED`. Rejected: raising minSdk to 36 (drops Android 13–15) and not enabling the gate.

## Review findings — code-reviewer, plan compliance, round 1 of 1 (2026-10-13)

**No BLOCKING findings.** Every action and test row implemented; the four lint-forced or approved deviations
(multi-line `Locale.Builder` chain from ktlint, `OAuthFlowTestSupport.kt` split for detekt `LargeClass`, block body in
`EventChannelServiceTest.newService()`, the approved `@Suppress`) verified sound. The reviewer compiled
`CountryDisplay.kt` + `CountryDisplayTest.kt` locally with kotlinc `-Werror`: zero warnings, 6/6 tests pass.

| ID | Finding | Resolution |
|----|---------|------------|
| N1 | `getLabeledByList` fallback, `ClipEntry` stability and `ResolveInfo.activityInfo` field assignment on a mock cannot be run without the SDK | Decided by the CI run of Task 6.2 |
| N2 | ServerScreen toast is skipped if the screen leaves composition before the coroutine runs | Accepted: as specified by the plan |
| N3 | The baseline covers only the variants CI compiles | Accepted: by design; CI green is the proof |
| N4 | Plan checkboxes unticked | Ticked |
