<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 68 — Lucky brand: launcher icon, notification icon and app name

Replaces the upstream placeholder identity with the Lucky DevOps brand and renames the app to
`lucky-mcp-android`.

## Source of the artwork

`Lucky-Estilo/brand/logo/` in the sibling workspace repository, governed by
`Lucky-Estilo/brand/DECISION.md` (status: accepted, **mandatory base design**). Two facts from that
ADR drive this plan:

- The **shield (mark) is constant** across every `lucky-*` repository — it is the fixed identity, and
  `brand/logo/lucky-mark.svg` is the named icon/favicon asset. Only the wordmark's second word
  changes per repo, and a launcher icon carries no wordmark.
- Brand colours: neon `#00FF66`, slate shield `#2b2f38` → `#1c1f26`, background `#0b0d0f`.

The SVGs are pure paths, so every drawable below is a hand-converted `<vector>` — no rasterisation,
no new asset files to copy in. The two source SVGs are the only input:

```text
lucky-mark.svg       shield (outer stroke 1.8) + inner shield (stroke 2.1, opacity .85, scaled .86
                     about centre) + flame (fill #00FF66) + "< / >" knocked out in #0b0d0f (stroke 2.6)
lucky-mark-mono.svg  the same geometry, every element stroked in #00FF66, no fills, round caps
```

## Geometry: one calculation the whole plan depends on

A vector-drawable `<group>` applies `translate(-pivot) → scale → rotate → translate(translate+pivot)`,
so with `pivotX`/`pivotY` at 0 a point `p` lands at `p * scale + translate`. Everything below follows
from that, and no other section repeats it.

The mark lives in a 64×64 box whose centre is `(32,32)`. Its farthest painted point from that centre
is the shield shoulder `(57,13)` at radius `√(25² + 19²) = 31.40`, plus half the outer stroke
(`1.8 / 2 = 0.9`) → **32.30**. On the 108×108 adaptive canvas, centring requires `32·s + t = 54`:

| scale `s` | translate `t` | painted radius on the canvas | verdict |
|---|---|---|---|
| 1.125 | 18 | 36.34 | **rejected** — the circular launcher mask has radius 36, so both shoulders are shaved |
| **1.0** | **22** | **32.30** | **chosen** — inside Google's 66dp key-content circle (radius 33) |

`s = 1.0` is therefore the scale used by the foreground and the monochrome layer alike.

## Gates — decisions taken at their stated default

The `/goal` for this plan is "aplica todo lo discutido, de logo usa mi logo lucky. se llama la app
lucky-mcp-android", to be executed without further intervention. Each gate is a choice that would
otherwise need the user; the default taken is recorded so it can be reversed in one edit.

| Gate | Default taken | Why |
|------|---------------|-----|
| **G1 — base branch** | Branch from and target `feat/mcp-tool-latency-optimization` (PR #7), not `main`. | The user's standing instruction is not to merge the open PRs and to stack new work on them. TOOLS.md's "branch from main" is overridden for the same reason plan 67 changed its G1. |
| **G2 — Gradle gates** | `ktlintCheck`, `detekt`, `test` and `build` run in CI only. | The test host has no Android SDK (no `ANDROID_HOME`, no `local.properties`). Local pre-checks use the standalone linter jars of Action 1. |
| **G3 — `applicationId`** | **Unchanged** (`com.danielealbano.androidremotecontrolmcp`). Only the user-visible name changes. | Changing it installs a *different* app: the user loses their settings, their OAuth approvals and the relay configuration, and must uninstall first. The user asked for a name, not a reinstall. A package rename needs its own plan and a data-migration decision. |
| **G4 — display name** | The literal string `lucky-mcp-android`, exactly as the user wrote it. | It matches the `lucky-<nombre>` convention the brand ADR makes mandatory. A title-case "Lucky MCP Android" is a one-line change if the user prefers it. |
| **G5 — notification icon** | Shield silhouette with a **single** 2.6-wide border and a **filled** flame; the inner border and the `< / >` are dropped. | At 24dp the brand's 1.8-unit border is `1.8 × 24/64 = 0.675dp`, under one device pixel on most densities, and the two concentric borders — 3.92 units apart at the apex, ≈3.5 at the flanks — merge into one smear. One border at 2.6 (`0.975dp`) is the same silhouette at a weight that survives the size. |
| **G6 — monochrome layer** | Added. | `minSdk` is 33 and `<monochrome>` is supported from exactly that level, so every target device can use it. Without the layer a themed-icon launcher simply keeps showing the standard icon — no breakage, but the brand never participates in the user's theme. |
| **G7 — repository and plugin naming** | **Out of scope.** Only the two app string resources change. | The literal `Android Remote Control MCP` also appears in `.claude-plugin/marketplace.json`, `channel-plugin/.claude-plugin/plugin.json`, `channel-plugin/index.ts`, `Makefile`, `README.md`, `app/proguard-rules.pro` and four `docs/` files. None of them is inside the app, so none affects the name the user sees on the device; renaming the repository, the plugin and the documentation is a separate decision with its own blast radius. |
| **G8 — stroke widths** | The brand's own widths are kept (1.8 / 2.1 / 0.9 / 2.6); they are **not** thickened for legibility. | Choosing `s = 1.0` over 1.125 already thins every rendered stroke by 11 %, and at a 48dp launcher icon the outer border lands near 0.8dp. Thickening would break the ratios the brand ADR fixes. Whether the result reads well is a question only the eye can answer: MB1 and MB2 decide, and a follow-up may revisit it with the user. |

---

## User story 1 — Launcher icon from the constant Lucky mark

The launcher icon is the app's identity on the user's home screen and it is currently upstream's
generic glyph on a green square. The brand ADR makes the shield mandatory and constant, so the icon
is a conversion of `lucky-mark.svg`, not a new design.

**Acceptance criteria:**
- [x] `ic_launcher_foreground.xml` draws the shield with both borders, the flame and the `< / >`
      knockout, from the mark's own path data.
- [x] The mark's 64-unit coordinate box maps to 22..86 of the 108 canvas, which keeps every painted
      pixel inside the 66dp key-content circle (painted radius 32.30 ≤ 33).
- [x] The adaptive background is the brand background `#0b0d0f`.
- [x] Both `ic_launcher.xml` and `ic_launcher_round.xml` declare background, foreground **and**
      monochrome. (`ic_launcher_round.xml` is currently unreferenced — `AndroidManifest.xml` declares
      `android:icon` and no `android:roundIcon` — but it is kept in step with its sibling so the two
      never diverge.)

### Task 1.1 — Replace the foreground drawable

**Action 1 — modify** `app/src/main/res/drawable/ic_launcher_foreground.xml`: replace the whole file.

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <!-- Centres the mark's 64-unit box on the 108 canvas: p * 1.0 + 22, so 0->22 and 64->86. -->
    <group
        android:pivotX="0"
        android:pivotY="0"
        android:scaleX="1.0"
        android:scaleY="1.0"
        android:translateX="22"
        android:translateY="22">

        <!-- Shield: slate gradient body with the neon outer border. -->
        <path
            android:pathData="M32,4 L57,13 V31 C57,47 45,56 32,60 C19,56 7,47 7,31 V13 Z"
            android:strokeColor="#00FF66"
            android:strokeWidth="1.8"
            android:strokeLineJoin="round">
            <aapt:attr name="android:fillColor">
                <gradient
                    android:type="linear"
                    android:startX="32"
                    android:startY="4"
                    android:endX="32"
                    android:endY="60">
                    <item android:offset="0" android:color="#2b2f38" />
                    <item android:offset="1" android:color="#1c1f26" />
                </gradient>
            </aapt:attr>
        </path>

        <!-- Inner border: the same outline at 0.86 about the centre, thicker and slightly faded. -->
        <group
            android:pivotX="32"
            android:pivotY="32"
            android:scaleX="0.86"
            android:scaleY="0.86">
            <path
                android:pathData="M32,4 L57,13 V31 C57,47 45,56 32,60 C19,56 7,47 7,31 V13 Z"
                android:strokeColor="#00FF66"
                android:strokeWidth="2.1"
                android:strokeAlpha="0.85"
                android:strokeLineJoin="round" />
        </group>

        <!-- Flame (Bootstrap Icons "fire", MIT), placed by the brand's measured transform. -->
        <group
            android:pivotX="0"
            android:pivotY="0"
            android:scaleX="2.2"
            android:scaleY="2.2"
            android:translateX="14.4"
            android:translateY="15.4">
            <path
                android:pathData="M8,16c3.314,0 6,-2 6,-5.5 0,-1.5 -0.5,-4 -2.5,-6 0.25,1.5 -1.25,2 -1.25,2 C11,4 9,0.5 6,0 c0.357,2 0.5,4 -2,6 -1.25,1 -2,2.729 -2,4.5 C2,14 4.686,16 8,16 Z"
                android:fillColor="#00FF66" />
        </group>

        <!-- "< / >" knocked out of the flame in the brand background colour. -->
        <path
            android:pathData="M26,37.64 L23,41.64 L26,45.64"
            android:strokeColor="#0b0d0f"
            android:strokeWidth="2.6"
            android:strokeLineCap="round"
            android:strokeLineJoin="round" />
        <path
            android:pathData="M33.5,36.64 L30.5,46.64"
            android:strokeColor="#0b0d0f"
            android:strokeWidth="2.6"
            android:strokeLineCap="round"
            android:strokeLineJoin="round" />
        <path
            android:pathData="M38,37.64 L41,41.64 L38,45.64"
            android:strokeColor="#0b0d0f"
            android:strokeWidth="2.6"
            android:strokeLineCap="round"
            android:strokeLineJoin="round" />
    </group>
</vector>
```

**Definition of Done:**
- [x] The root declares `xmlns:android` and `xmlns:aapt`, and no other namespace.
- [x] The outer group's transform attributes are exactly `pivot 0,0`, `scale 1.0`, `translate 22`.

### Task 1.2 — Brand background colour

**Action 1 — modify** `app/src/main/res/values/ic_launcher_background.xml`.

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#0b0d0f</color>
</resources>
```

**Definition of Done:**
- [x] The resource name is unchanged, so both adaptive-icon XMLs and `AboutScreen` keep resolving.

### Task 1.3 — Monochrome layer

**Action 1 — create** `app/src/main/res/drawable/ic_launcher_monochrome.xml`.

Launchers tint this layer, so it carries the mono mark's geometry as strokes only — no fill, no
gradient, no brand colour — and uses the same `scale 1.0 / translate 22` group as Task 1.1.

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <group
        android:pivotX="0"
        android:pivotY="0"
        android:scaleX="1.0"
        android:scaleY="1.0"
        android:translateX="22"
        android:translateY="22">
        <path
            android:pathData="M32,4 L57,13 V31 C57,47 45,56 32,60 C19,56 7,47 7,31 V13 Z"
            android:strokeColor="#FFFFFF"
            android:strokeWidth="1.8"
            android:strokeLineJoin="round"
            android:strokeLineCap="round" />
        <group
            android:pivotX="32"
            android:pivotY="32"
            android:scaleX="0.86"
            android:scaleY="0.86">
            <path
                android:pathData="M32,4 L57,13 V31 C57,47 45,56 32,60 C19,56 7,47 7,31 V13 Z"
                android:strokeColor="#FFFFFF"
                android:strokeWidth="2.1"
                android:strokeAlpha="0.85"
                android:strokeLineJoin="round"
                android:strokeLineCap="round" />
        </group>
        <group
            android:pivotX="0"
            android:pivotY="0"
            android:scaleX="2.2"
            android:scaleY="2.2"
            android:translateX="14.4"
            android:translateY="15.4">
            <path
                android:pathData="M8,16c3.314,0 6,-2 6,-5.5 0,-1.5 -0.5,-4 -2.5,-6 0.25,1.5 -1.25,2 -1.25,2 C11,4 9,0.5 6,0 c0.357,2 0.5,4 -2,6 -1.25,1 -2,2.729 -2,4.5 C2,14 4.686,16 8,16 Z"
                android:strokeColor="#FFFFFF"
                android:strokeWidth="0.9"
                android:strokeLineJoin="round"
                android:strokeLineCap="round" />
        </group>
        <path
            android:pathData="M26,37.64 L23,41.64 L26,45.64"
            android:strokeColor="#FFFFFF"
            android:strokeWidth="2.6"
            android:strokeLineCap="round"
            android:strokeLineJoin="round" />
        <path
            android:pathData="M33.5,36.64 L30.5,46.64"
            android:strokeColor="#FFFFFF"
            android:strokeWidth="2.6"
            android:strokeLineCap="round"
            android:strokeLineJoin="round" />
        <path
            android:pathData="M38,37.64 L41,41.64 L38,45.64"
            android:strokeColor="#FFFFFF"
            android:strokeWidth="2.6"
            android:strokeLineCap="round"
            android:strokeLineJoin="round" />
    </group>
</vector>
```

**Action 2 — modify** `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` **and**
`app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`: both get the third layer. The two files
end up identical, as they already are.

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
```

**Definition of Done:**
- [x] The monochrome drawable contains no `android:fillColor` and no `<gradient>`.

### Task 1.4 — Keep the About screen's icon the right size

**Context the code does not show:** `AboutScreen` renders `R.drawable.ic_launcher_foreground` at
`72.dp` inside a 72dp circle filled with `R.color.ic_launcher_background`, so changing Tasks 1.1 and
1.2 changes that screen too. With the mark occupying 64 of the 108-unit canvas, a 72dp render puts a
**43.1dp** shield inside a 72dp circle — correct artwork, visibly adrift in empty space. Rendering
the same drawable at `108.dp` inside the still-clipped 72dp circle gives a **64.6dp** shield, which
fills the circle with a small margin. The circle keeps its own `CircleShape` clip, so the oversized
child cannot spill.

**Action 1 — modify** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/AboutScreen.kt`:
the inner `Image`'s size only. The enclosing `Box` keeps `size(72.dp)`, its `clip(CircleShape)` and
its background.

```kotlin
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = stringResource(R.string.app_name),
                        modifier = Modifier.size(108.dp),
                    )
```

**Definition of Done:**
- [x] Only the `Modifier.size` of that one `Image` changes; the `Box` and every other composable in
      the file are untouched.

---

## User story 2 — Notification icon from the mono mark

The status-bar icon is upstream's generic phone glyph. Android renders a notification small icon from
its **alpha channel only** and tints it itself, so the mono mark is the correct source and the colours
in the file are carriers of opacity, not of appearance. Per G5 the inner border and the `< / >` are
dropped because they do not survive 24dp.

**Acceptance criteria:**
- [x] `ic_notification.xml` draws the shield with a single 2.6-wide border and a filled flame; the
      inner border and the `< / >` are absent.
- [x] Its declared size stays 24dp and its viewport becomes the mark's own 64×64, so the path data
      needs no rescaling.
- [x] The `android:tint` attribute keeps its current value. It has no effect on a small icon — all
      four uses are `setSmallIcon`, and the drawable appears in no Compose code — so this is status
      quo preservation, not a rendering requirement.

### Task 2.1 — Replace the notification drawable

**Action 1 — modify** `app/src/main/res/drawable/ic_notification.xml`: replace the whole file.

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="64"
    android:viewportHeight="64"
    android:tint="?attr/colorControlNormal">
    <path
        android:pathData="M32,4 L57,13 V31 C57,47 45,56 32,60 C19,56 7,47 7,31 V13 Z"
        android:strokeColor="#FFFFFF"
        android:strokeWidth="2.6"
        android:strokeLineJoin="round"
        android:strokeLineCap="round" />
    <group
        android:pivotX="0"
        android:pivotY="0"
        android:scaleX="2.2"
        android:scaleY="2.2"
        android:translateX="14.4"
        android:translateY="15.4">
        <path
            android:pathData="M8,16c3.314,0 6,-2 6,-5.5 0,-1.5 -0.5,-4 -2.5,-6 0.25,1.5 -1.25,2 -1.25,2 C11,4 9,0.5 6,0 c0.357,2 0.5,4 -2,6 -1.25,1 -2,2.729 -2,4.5 C2,14 4.686,16 8,16 Z"
            android:fillColor="#FFFFFF" />
    </group>
</vector>
```

**Definition of Done:**
- [x] The `android:tint` value is byte-identical to the file's previous value.
- [x] No path strokes `#0b0d0f`.

---

## User story 3 — App name

The user named the app `lucky-mcp-android`. Only the two app string resources change: per G3 the
`applicationId` stays, and per G7 the repository, plugin and documentation names are out of scope.
`app_name` is the manifest `android:label` and the only name the app renders; `share_target_label` is
the share-alias label and today matches it.

**Acceptance criteria:**
- [x] `app_name` is `lucky-mcp-android`.
- [x] `share_target_label` is `lucky-mcp-android`.
- [x] No Gradle file changes: `applicationId`, `namespace` and both flavour suffixes are untouched.

### Task 3.1 — Rename in the string resources

**Action 1 — modify** `app/src/main/res/values/strings.xml`: the first two strings.

```xml
    <string name="app_name">lucky-mcp-android</string>
    <string name="share_target_label">lucky-mcp-android</string>
```

**Context:** `app_bar_title` ("MCP Remote Control", line 15) is **deliberately left alone**. No code
references `R.string.app_bar_title`, so it is dead and renaming it would be an undeclared change to a
string this plan has no reason to touch.

**Definition of Done:**
- [x] `git diff` on `app/build.gradle.kts` is empty.
- [x] Only those two strings change.

---

## User story 4 — Guard the brand resources with tests

AAPT2 does not validate `pathData`: a malformed path compiles and then throws at inflate time on the
device. The transforms are worse — a wrong pivot or translate produces a perfectly valid icon in the
wrong place, which no build step can see. These JVM guards (JUnit 5, `@DisplayName`, no mocks, no
Robolectric, no device) therefore check the three things that can silently ship: path syntax, every
group transform, and the brand values.

**What they cannot do:** judge whether the result *looks* right at 48dp. That is MB1-MB4.

**Acceptance criteria:**
- [x] Every brand drawable parses, and every `pathData` is structurally valid — starts with a move
      command and gives each command a legal number of parameters.
- [x] All three group transforms of the foreground are asserted, not only the outer one.
- [x] The brand colours, the adaptive layers, the notification geometry, both app-name strings and the
      unchanged `applicationId` are asserted.

### Task 4.1 — Brand resource guards

**Action 1 — create** `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/brand/BrandResourcesTest.kt`.

**Setup**: no mocks. The shared infrastructure below is given in full because it fixes the
file-resolution and path-validation contract for every row. It anchors on the module directory by
looking for a file that only the module has, which removes the ambiguity of trying
`File(x)` then `File("app", x)` — a repository-root CWD would otherwise resolve
`build.gradle.kts` to the **settings-level** build file, which contains no `applicationId`.

```kotlin
package com.danielealbano.androidremotecontrolmcp.brand

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the Lucky brand resources: the launcher icon, its monochrome layer, the notification icon
 * and the app name.
 *
 * AAPT2 accepts any `android:pathData` string and a wrong group transform is valid XML, so neither
 * failure reaches a build error — the icon simply draws wrongly, or throws when inflated on the
 * device. These tests parse the checked-in resources directly, with no Robolectric and no device.
 */
@DisplayName("Lucky brand resources")
class BrandResourcesTest {
    /** The module directory, whichever CWD the test runner chose. */
    private val moduleDir: File =
        listOf(File("."), File("app"))
            .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("module directory not found from ${File(".").absolutePath}")

    private fun read(relativePath: String): String =
        File(moduleDir, relativePath).also {
            assertTrue(it.isFile) { "missing resource: ${it.path}" }
        }.readText()

    private fun parse(relativePath: String): Document =
        DocumentBuilderFactory
            .newInstance()
            .apply { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            .newDocumentBuilder()
            .parse(File(moduleDir, relativePath))

    /** Every element with the given tag, in document order, including nested ones. */
    private fun Document.elements(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** The transform attributes of one group, as a map of the six names this plan sets. */
    private fun Element.transform(): Map<String, String> =
        listOf("pivotX", "pivotY", "scaleX", "scaleY", "translateX", "translateY")
            .associateWith { getAttribute("android:$it") }

    /**
     * Asserts that [pathData] is a structurally valid SVG path for Android's parser: it starts with
     * a move command, every command letter is known, and each one gets a legal parameter count.
     */
    private fun assertValidPathData(
        pathData: String,
        where: String,
    ) {
        assertTrue(pathData.isNotBlank()) { "$where: empty pathData" }
        assertTrue(pathData.trimStart().first() in "Mm") { "$where: must start with M or m" }
        val commands = Regex("[A-Za-z][^A-Za-z]*").findAll(pathData).toList()
        assertTrue(commands.isNotEmpty()) { "$where: no commands" }
        commands.forEach { match ->
            val letter = match.value.first()
            val expected =
                PARAMETER_COUNTS[letter.uppercaseChar()]
                    ?: error("$where: unknown path command '$letter'")
            val parameters =
                match.value
                    .drop(1)
                    .split(',', ' ', '\t', '\n')
                    .flatMap { chunk -> Regex("(?<=\\d)-").split(chunk) }
                    .filter { it.isNotBlank() }
            if (expected == 0) {
                assertTrue(parameters.isEmpty()) { "$where: '$letter' takes no parameters" }
            } else {
                assertTrue(parameters.isNotEmpty() && parameters.size % expected == 0) {
                    "$where: '$letter' got ${parameters.size} parameters, expected a multiple of $expected"
                }
            }
        }
    }

    private companion object {
        const val FOREGROUND = "src/main/res/drawable/ic_launcher_foreground.xml"
        const val MONOCHROME = "src/main/res/drawable/ic_launcher_monochrome.xml"
        const val NOTIFICATION = "src/main/res/drawable/ic_notification.xml"
        const val BACKGROUND_COLOR = "src/main/res/values/ic_launcher_background.xml"
        const val LAUNCHER = "src/main/res/mipmap-anydpi-v26/ic_launcher.xml"
        const val LAUNCHER_ROUND = "src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml"
        const val STRINGS = "src/main/res/values/strings.xml"
        const val BUILD_FILE = "build.gradle.kts"
        const val ABOUT_SCREEN =
            "src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/AboutScreen.kt"

        const val APP_NAME = "lucky-mcp-android"
        const val NEON = "#00ff66"
        const val KNOCKOUT = "#0b0d0f"

        val DRAWABLES = listOf(FOREGROUND, MONOCHROME, NOTIFICATION)

        /** SVG path commands and the number of parameters each repetition consumes. */
        val PARAMETER_COUNTS =
            mapOf(
                'M' to 2, 'L' to 2, 'T' to 2,
                'H' to 1, 'V' to 1,
                'C' to 6, 'S' to 4, 'Q' to 4,
                'A' to 7, 'Z' to 0,
            )
    }
}
```

Colour comparisons lowercase both sides: the checked-in files mix `#00FF66` with `#2b2f38`, and a
cosmetic case change must not break a guard.

| Test | Verifies |
|------|----------|
| `every brand drawable has structurally valid path data` | Each of the three drawables parses; every `<path>`'s `android:pathData` passes `assertValidPathData`. **Setup**: iterate `DRAWABLES`, then `elements("path")` — which reaches paths nested in groups |
| `launcher foreground centres the mark inside the key content circle` | The **first** `<group>` has `pivot 0,0`, `scale 1.0`, `translate 22,22` — the painted radius is then 32.30 ≤ 33 |
| `launcher foreground scales the inner border about the mark centre` | The group that wraps the second shield path has `pivotX`/`pivotY` `32` and `scale 0.86` |
| `launcher foreground places the flame by the brand transform` | The group that wraps the flame path has `pivot 0,0`, `scale 2.2`, `translate 14.4,15.4` |
| `launcher foreground carries the brand colours` | `#00FF66` appears as a `strokeColor`; the two `<item>` colours are `#2b2f38` and `#1c1f26`. Case-insensitive |
| `launcher foreground knocks the symbol out in the background colour` | Exactly three paths have `strokeColor` `#0b0d0f` and `strokeWidth` `2.6` |
| `launcher foreground declares only the android and aapt namespaces` | The root element's attributes whose name starts with `xmlns:` are exactly `xmlns:android` and `xmlns:aapt`. **Setup**: iterate `documentElement.attributes` — a namespace declaration is readable there even though it is not a normal attribute |
| `launcher background is the brand background` | `ic_launcher_background.xml` gives `#0b0d0f` for the resource named `ic_launcher_background` |
| `both adaptive icons declare the same three layers` | Each of the two mipmap files has exactly one `background`, one `foreground` and one `monochrome`, and the two files' texts are equal |
| `monochrome layer is strokes only` | The monochrome drawable has no `android:fillColor` attribute and no `<gradient>` element, and its first group's transform equals the foreground's first group's transform |
| `notification icon keeps 24dp over the mark viewport` | `android:width` and `android:height` are `24dp`; `viewportWidth` and `viewportHeight` are `64` |
| `notification icon drops the inner border and the symbol` | It has exactly two `<path>` elements, none stroking `#0b0d0f`, and its `android:tint` is `?attr/colorControlNormal` |
| `app name is the Lucky name` | `app_name` and `share_target_label` both read `lucky-mcp-android` |
| `application id is not renamed` | `build.gradle.kts` resolved from `moduleDir` contains `applicationId = "com.danielealbano.androidremotecontrolmcp"` |
| `about screen renders the icon large enough to fill its circle` | `AboutScreen.kt` contains `Modifier.size(108.dp)` on the `painterResource(id = R.drawable.ic_launcher_foreground)` image. **Setup**: read as text; assert the substring `Modifier.size(108.dp)` occurs after the `ic_launcher_foreground` reference |

**Definition of Done:**
- [x] The file exists with 15 test methods and the shared infrastructure above.
- [x] No `@Suppress` anywhere in the file.

---

## Quality task Q.1 — gates, review and delivery

### Action 1 — local pre-checks

Gradle is not available on this host (G2). Run the standalone linters from the folder plan 67's G4
established, `<LINT_TOOLS>` = `$(cygpath -m "$HOME/.lint-tools")` in Git Bash — outside every
repository. Before use, `sha256sum` must print:

- `a3fd620207d5c40da6ca789b95e7f823c54e854b7fade7f613e91096a3706d75` for `ktlint/ktlint.jar`
  (ktlint 1.8.0);
- `4992d226183f2125f517fe59c21f1a2a80bbe40c223f2481eef5ac1b4694f35c` for
  `detekt/detekt-cli-all.jar` (detekt-cli 2.0.0-alpha.5).

A jar whose digest differs is not used.

- ktlint: `java -jar <LINT_TOOLS>/ktlint/ktlint.jar "app/src/main/kotlin/**/*.kt" "app/src/test/kotlin/**/*.kt"`
  → 0 findings.
- detekt: `MSYS_NO_PATHCONV=1 java -jar <LINT_TOOLS>/detekt/detekt-cli-all.jar --input app/src/main/kotlin`,
  then the same for `app/src/test/kotlin` → exit 0 each (detekt takes one `--input`).

XML is outside both linters' scope; the XML guard is Task 4.1 plus the CI build.

### Action 2 — branch, commits and PR

Per G1, branch from `feat/mcp-tool-latency-optimization`:

```bash
git checkout feat/mcp-tool-latency-optimization && git pull origin feat/mcp-tool-latency-optimization
git checkout -b feat/lucky-brand-icon-and-name
```

Suggested commits, in order — each one coherent on its own:

| Commit | Contents |
|--------|----------|
| `feat: draw the launcher icon from the Lucky mark` | Tasks 1.1, 1.2, 1.3 |
| `fix: size the about screen icon for the new mark` | Task 1.4 |
| `feat: draw the notification icon from the Lucky mono mark` | Task 2.1 |
| `feat: name the app lucky-mcp-android` | Task 3.1 |
| `test: guard the brand resources` | Task 4.1 |

Never `git add -A`. Stage the named files only. No AI attribution in any message.

PR with `--repo mlandolfi90/Lucky-Tool-Android --base feat/mcp-tool-latency-optimization`, body with
the TOOLS.md sections, written to a scratch file (long heredocs fail in this environment).

### Action 3 — read CI with `gh`, never assume

`gh pr checks <N> --repo mlandolfi90/Lucky-Tool-Android --watch`. Required green — the five checks of
`ci.yml`: `Lint`, `Unit & Integration Tests`, `E2E Tests`, `Build Release - GMS`,
`Build Release - FOSS`.

`Relay tests and image` is **not** expected: `relay.yml` is `paths`-filtered to `relay/**` and to
itself, and this plan touches neither, so GitHub never creates that check. Its absence is correct and
must not be read as a failure. The widened `pull_request` filter from plan 67 (`[main, "feat/**"]`) is
what lets the five run at all on this stacked base.

### Action 4 — guard reversion

Each row: break the guard, run the named test, see it FAIL, restore with the Edit tool — never
`git checkout` or `git restore`. These rows need a Gradle toolchain this host does not have, so they
run only under an explicit probe-branch consent; without it, report them as not executed in the PR
body rather than claiming them.

| Reverted guard | Named test | Expected |
|---|---|---|
| Outer group `translateX` set to `18` and `scaleX` to `1.125` | `launcher foreground centres the mark inside the key content circle` | FAILS |
| Flame group `translateY` set to `0` | `launcher foreground places the flame by the brand transform` | FAILS |
| One `C` command given five parameters instead of six | `every brand drawable has structurally valid path data` | FAILS |
| `<monochrome>` removed from `ic_launcher_round.xml` | `both adaptive icons declare the same three layers` | FAILS |
| `app_name` left as the upstream string | `app name is the Lucky name` | FAILS |
| `AboutScreen` image size left at `72.dp` | `about screen renders the icon large enough to fill its circle` | FAILS |

### Action 5 — plan-compliance review

Spawn `code-reviewer` in plan-compliance mode over
`git diff feat/mcp-tool-latency-optimization...HEAD`. Record each round in this plan as
`## Review findings — code-reviewer (plan compliance), round N, <date>` with an
`| ID | Finding | Resolution |` table. Repeat until clean. Every finding is addressed — critical,
warning and info alike.

### Action 6 — manual check (the only thing a test cannot do)

Install the PR's `app-gms-debug` artifact and confirm, with the user:

- **MB1** — the launcher icon shows the Lucky shield, neon on near-black, with no shoulder clipped by
  the launcher's mask, and the name under it reads `lucky-mcp-android`.
- **MB2** — with a themed-icon launcher setting enabled, the monochrome layer is used and the shield
  is still recognisable.
- **MB3** — the foreground notification of the MCP server shows the Lucky shield in the status bar.
- **MB4** — the About screen shows the shield filling its circle, not adrift in it.

MB1 and MB2 are also the only judges of G8 (brand stroke widths kept, not thickened). Report MB1-MB4
in the PR body; if any is not taken, say so, and never present an estimate as an observation.

**Definition of Done:**
- [x] Local pre-checks clean; the five CI checks green, read with `gh`; no Kotlin `w: ` warning
      introduced by this plan.
- [x] Every reversion row observed failing and restored, or declared as not executed.
- [ ] `code-reviewer` plan-compliance pass clean and recorded.
- [x] MB1-MB4 reported in the PR body, taken or declared; PR URL reported.

---

## Review findings — `plan-reviewer`, round 1, 2026-10-12

Audited before any implementation. The plan text above is the corrected version; this table records
what the audit found and how each item was resolved.

| ID | Finding | Resolution |
|----|---------|------------|
| C1 | CRITICAL: US2's acceptance criteria demanded "both borders, stroked, with no fill", which Task 2.1's own XML contradicts (one border, filled flame). An implementer following the plan to the letter would have to stop. | The AC was the defect, not the code. Rewritten to describe a single 2.6-wide border and a filled flame, with the inner border and the `< / >` dropped per G5 |
| W2 | WARNING: at `scale 1.125` the mark's painted radius on the canvas is 36.34 against a circular mask radius of 36, so both shield shoulders are shaved — and the shoulders sit outside the 66dp key-content circle. No automated gate in this repository catches it. | Verified independently (shoulder `(57,13)` → radius 31.40 + 0.9 half-stroke = 32.30 in mark space). Changed to `scale 1.0 / translate 22`, giving 32.30 ≤ 33. Propagated to Tasks 1.1 and 1.3, the ACs, the DoDs and the test rows, and the arithmetic is now stated once in its own section |
| W3 | WARNING: `Relay tests and image` can never report on this PR — `relay.yml` is `paths`-filtered to `relay/**`, which this plan does not touch — so "the six CI checks green" was unachievable. | Action 3 now requires the five `ci.yml` checks and states that the Relay check is path-skipped by design |
| W4 | WARNING: the test helper could not resolve `app/build.gradle.kts` unambiguously; from a repository-root CWD it would have matched the settings-level `build.gradle.kts`, which contains no `applicationId`, giving a false failure. | The helper now anchors on the module directory by probing for `src/main/AndroidManifest.xml`, and every path is resolved from it |
| W5 | WARNING: US4 claimed to guard malformed `pathData` while only asserting non-blankness, which cannot detect a bad command or parameter count. | Added `assertValidPathData`, given in full: it requires a leading move command, known command letters and a legal parameter count per command. US4 also now states plainly what the guards cannot do |
| W6 | WARNING: the two most error-prone transforms — the inner shield and the flame — had no test, so a pivot or translate regression would pass all rows. | Two rows added, one per group, plus a reversion row for the flame |
| W7 | WARNING: US2's size and viewport criteria had no test, and the viewport is exactly what this change rewrites (24 → 64). | Row added asserting 24dp and viewport 64 |
| W8 | WARNING: the row "monochrome layer uses no brand colour" guarded a non-invariant — launchers tint that layer, so its colours never render. | Replaced by a load-bearing row: no `fillColor`, no `<gradient>`, and the same safe-zone transform as the foreground |
| W9 | WARNING: Task 4.1's DoD required CI passes and clean linters, but `CLAUDE.md` puts those at the end of the plan, and Q.1's DoD already covers them. | Task 4.1's DoD reduced to structural facts |
| W10 | WARNING: the "written in full" helper block was three bare functions — no package, no class, no imports — although the plan asserted it was complete. | Given as a complete file: package, imports, KDoc, `@DisplayName` class, helpers and companion |
| W11 | WARNING: `AboutScreen` renders both changed resources and was nowhere declared; the new mark would appear as a 43.1dp shield inside a 72dp circle. | Declared and fixed: new Task 1.4 renders the image at `108.dp` (giving 64.6dp of shield), with the arithmetic stated, a guard row and a new manual check MB4 |
| W12 | WARNING: US3 claimed "only the user-visible strings change" without having enumerated the other occurrences of the old name (marketplace and plugin JSON, `channel-plugin/index.ts`, `Makefile`, `README.md`, proguard comment, four docs). | New gate G7 records them as deliberately out of scope, with the reason that none is inside the app |
| W13 | WARNING: Task 1.1's DoD said the file declares the `aapt` namespace "and no other", which its own code contradicts. | Corrected to `xmlns:android` and `xmlns:aapt`, and no others; the matching test row asserts exactly that |
| I1 | INFO: G6's rationale was wrong — without a monochrome layer the launcher keeps the standard icon; it does not synthesise a themed one. | Rationale rewritten; the decision stands |
| I2 | INFO: US2's tint rationale was wrong — a small icon is alpha-only and all four call sites are `setSmallIcon`, so the tint and the white strokes have no production effect. | US2 now says this explicitly and frames keeping the attribute as status quo preservation |
| I3 | INFO: G5 said the two borders are "2 units apart"; with the 0.86 scale about the centre the separation is 3.92 at the apex. | Corrected in G5; the conclusion is unchanged |
| I4 | INFO: AC2 described the mapped coordinate box as if it were the artwork's extent. | Reworded to "the mark's 64-unit coordinate box maps to 22..86", with the painted radius stated separately |
| I5 | INFO: the colour assertions were case-sensitive although the files mix case, so a cosmetic normalisation would break four guards. | The test task states that colour comparisons lowercase both sides |
| I6 | INFO: `ic_launcher_round.xml` is unreferenced — the manifest declares `android:icon` and no `android:roundIcon`. | US1 AC4 now says so, and explains why the file is still kept in step with its sibling |
| I7 | INFO: `app_bar_title` keeps the old-style name and is dead, but the DoD forbade touching it in silence. | Task 3.1 carries an explicit note on why it is left alone |
| I8 | INFO: the group-transform rule was explained three times. | Stated once, in its own section; the XML comments now only say where the mark lands |
| I9 | INFO: DoD items duplicated acceptance criteria and test rows across levels. | The duplicated items were removed from the task DoDs |
| I10 | INFO: Action 1 deferred the linter jar paths and digests to plan 67, forcing the implementer to open another document. | Both digests, the folder and the exact commands are inlined |
| I11 | INFO: both launcher layers are hairline line art, and the W2 fix thins every stroke by a further 11 %. | New gate G8 records the decision to keep the brand's widths and names MB1/MB2 as the judges |
| I12 | INFO: the test framework was never named for the new class. | US4 names JUnit 5 with `@DisplayName`, no mocks, no Robolectric |
| I13 | INFO: no architecture, performance or security findings — a resources-and-strings change with no service, DI, coroutine, secret, logging or input surface. | Nothing to do; recorded |

---

## Review findings — `code-reviewer` (plan compliance), round 1, 2026-10-12

No critical findings. The four resource files are byte-identical to the plan's XML blocks, the
geometry is the chosen `pivot 0,0 / scale 1.0 / translate 22`, the scope is exactly the ten permitted
files, the five `ci.yml` checks are green on `c92223f` and both standalone linters are clean.

| ID | Finding | Resolution |
|----|---------|------------|
| W-1 | WARNING: the plan (US1 AC4 and finding I6) and the PR body both state that `ic_launcher_round.xml` is unreferenced because the manifest declares no `android:roundIcon`. It does: `app/src/main/AndroidManifest.xml:54`, since the repository's first commit. | **The claim was false and is withdrawn here.** Verified: line 54 declares `android:roundIcon="@mipmap/ic_launcher_round"`, and this branch does not touch the manifest. The round icon is therefore **live**, which makes AC4's "kept in step with its sibling" mandatory rather than merely tidy — and both files were in fact updated identically, so the shipped result is correct either way. The AC text itself is sacred and stays; this row is the correction, and the PR body was fixed |
| W-2 | WARNING: the inner-border row selected its group by `pivotX == "32"` and then asserted `pivotX` — self-fulfilling — and would still pass if the wrapped `<path>` were deleted and the empty group kept. | Fixed. New `childPaths()` and `groupWrapping()` helpers select a group by the path it wraps; the row now asserts the whole transform map against `INNER_BORDER_TRANSFORM`. Verified by mutation: with the wrapped path removed, no group matches and the row fails |
| W-3 | WARNING: the flame row had the same gap, so the flame's presence in the foreground was not guarded at all. | Fixed the same way, selecting on the flame's `pathData` prefix. Verified by mutation on a scratch copy: deleting the flame path leaves zero matches, so the row now fails where it previously passed |
| I-1 | INFO: the dropped return type on `Element.transform()` was attributed to a ktlint/detekt conflict, but the conflict only existed because the attribute list had been hoisted into a companion constant — the plan's own inline form is clean under both tools. | The plan's exact form was restored (explicit return type, inline `listOf`, `.associateWith` on its own line) and `TRANSFORM_ATTRIBUTES` removed, so the deviation disappears instead of being declared. Re-verified: ktlint 0 findings, detekt exit 0, no line over 120 |
| I-2 | INFO: other semantics-preserving deviations from the "in full" infrastructure block were undeclared — a reformatted `read()` chain, hoisted `Regex` constants, a split assertion message, the extra `strokeColours()` helper and several extra companion constants. | Declared in the PR body. Each is required by a plan row or neutral to it; the hoisted `Regex` constants exist because detekt flags recompiled literals, and the extra constants carry the expected transform maps the rows compare against |
| I-3 | INFO: the monochrome row checked `android:fillColor` only on `<path>` elements, while the plan's row says the drawable has none at all. | Widened to a text-wide scan of the drawable, matching the row's wording exactly |
| I-4 | INFO: no plan checkbox had been ticked and this review section was missing. | Both done in this commit. Only the `code-reviewer` DoD item stays open until round 2 reports clean |
| I-5 | INFO: the working tree carries an untracked `.lifecycle/`, unrelated to this plan. | Pre-existing and never staged; noted so it is not staged by accident |

**Kotlin compiler warnings, measured on the green run 38318033159:** 43 distinct warnings, the same
count as before this branch. One falls in a file this plan touches — `AboutScreen.kt:70`, the
inherited `hiltViewModel` package move — at a line this plan does not modify. This plan introduces
none.

---

## Review findings — `code-reviewer` (plan compliance), round 2, 2026-10-12

Verdict: **NOT CLEAN** — no critical findings, 1 WARNING and 6 INFO, all addressed below. The
shipped resources, geometry, `AboutScreen` change, strings, scope and plan-file integrity were
re-verified compliant at `c776249`, with the five `ci.yml` checks green (run `38318033159` for the
earlier head, `38327032915` for `c776249`) and `BrandResourcesTest` reporting 15/15.

| ID | Finding | Resolution |
|----|---------|------------|
| W-R2-1 | WARNING: the PR body declared the two `Regex` objects hoisted into companion constants *"because detekt flags a literal recompiled on every call"*. Measured false — the plan's inline form is clean under both tools, exactly as with `Element.transform()` under round-1 I-1. | **Resolved by removing the deviation, not by re-wording it.** I restored the plan's inline `Regex("[A-Za-z][^A-Za-z]*")` and `Regex("(?<=\\d)-")`, deleted `COMMAND` and `NEGATIVE_SEPARATOR`, and rejoined the split assertion message (107 characters, under both limits). Measured after: ktlint 0 findings, detekt exit 0 on both source sets. Three deviations disappear |
| I-R2-1 | INFO: the `read()` deviation was declared as *"reformatted to fit the column limit"*; the plan's form is 62 characters at its longest, so that reason is wrong. | Measured the real cause by restoring the plan's form and running ktlint: `standard:chain-method-continuation` — *"Expected newline before '.'"*. The deviation is forced; only the stated reason was wrong, and the PR body now names the rule |
| I-R2-2 | INFO: the `PARAMETER_COUNTS` reformat (one entry per line instead of the plan's grouped rows) was not declared at all. | Measured as forced by ktlint `standard:argument-list-wrapping`, 6 findings in the plan's form. Now declared in the PR body, naming the rule |
| I-R2-3 | INFO: the declared list of added companion constants named 4 of them and omitted the rest, and the two new helpers were never named. | Enumerated exhaustively in the PR body: **12** constants added beyond the plan's 14 and **3** helpers beyond the plan's 6, each with its reason |
| I-R2-4 | INFO: a 28-mutation audit found **7 mutations that survived all 15 guards**. The worst is the first: the **outer** shield `pathData` — the one actually painted — was unguarded beyond syntax, so moving a shoulder to `(70,2)` (painted radius 48.4, far outside the mask) passed. The others: the mipmaps' `<monochrome>` repointed at the colour foreground; the notification border thinned below 2.6; the notification flame made transparent; the foreground outer border recoloured off-brand; the monochrome artwork deleted or re-widened; and the gradient re-attached to a different `aapt:attr` name. | **All seven closed**, inside the rows that already own each subject, so the count stays at 15 tests and no new row is invented. Added: the outer group's first child path must equal `SHIELD_PATH`; `LAYER_DRAWABLES` pins all three adaptive layers to their drawables; the monochrome layer's `pathData` list must equal the foreground's and its stroke widths must equal `MONOCHROME_STROKE_WIDTHS`; the notification shield must keep the brand outline at width `2.6` and its flame must be filled opaque; the foreground's **first** stroke colour must be the neon; the single `aapt:attr` must target `android:fillColor`. Proven by re-running the same mutations against scratch copies of the resource tree: **8 of 8 now caught, 0 survive**, and all seven new assertions hold on the shipped files |
| I-R2-5 | INFO: the CI DoD box was ticked in `c776249`, the very commit that triggered the run, so at tick time four of the five checks were still in progress. | The claim is now true and was read with `gh`, not assumed: run `38327032915` on `c776249`, `conclusion: success`, and the downloaded `unit-test-results` artifact shows `BrandResourcesTest` 15 tests / 0 failures / 0 skipped. The sequencing is the finding and is recorded here; the correct order is to push, read CI, then tick |
| I-R2-6 | INFO: the PR #8 checklist still showed CI unticked although the run was green. | Ticked, with the per-check table and the test-report evidence added to the PR body |

**Out-of-scope observation, recorded and not acted on:** round 2 reported that the
`testGmsDebugUnitTest` report in the `unit-test-results` artifact contains no `BrandResourcesTest`
entry. Measured directly from the same artifact: the directory
`testGmsDebugUnitTest/com.danielealbano.androidremotecontrolmcp.brand.BrandResourcesTest/` exists and
its report reads 15 tests, 0 failures, 0 ignored, so the class ran under **both** variant tasks. This
plan changes nothing about variant test wiring either way.

---

## Review findings — `code-reviewer` (plan compliance), round 3, 2026-10-12

Verdict: **NOT CLEAN** — 0 critical, 3 WARNING, 4 INFO, all addressed below. Round 3 confirmed every
round-2 resolution except the helper enumeration, re-derived the geometry independently, re-verified
the five resource files byte-identical to the plan's blocks and the scope at exactly ten files, and
extended the mutation matrix from 28 to 45.

| ID | Finding | Resolution |
|----|---------|------------|
| W-R3-1 | WARNING: the round-2 resolution of I-R2-3 claimed the deviation list was "enumerated exhaustively". It was not. **Four** helpers were added, not three — `strokeColours()` is additive too, and the PR body justified keeping its three-line shape as *"the plan's own three-line chain"*. The plan has no `strokeColours()`, so it has no own form: a false premise, the same class of claim as round-1 I-1 and round-2 W-R2-1. The bullet's "exactly five places" arithmetic also did not close. | Confirmed by measurement, not argument: the plan's block declares **6** members (`moduleDir`, `read`, `parse`, `Document.elements`, `Element.transform`, `assertValidPathData`) and the shipped file **10**, so the added helpers are `childPaths()`, `groupWrapping()`, `pathAttributes()` **and `strokeColours()`** — four. The underlying measurement was sound and reproduced: delegating `strokeColours()` through `pathAttributes()` joins to 134 characters, which ktlint accepts and detekt rejects (`MaxLineLength`); only the attribution to the plan was false. The PR body now declares four helpers, gives that justification without inventing a plan form, drops the broken count, and states that **plan line 743's "3 helpers beyond the plan's 6" is superseded by this row** |
| W-R3-2 | WARNING: the painted-radius invariant had two unguarded inputs. `viewportWidth`/`viewportHeight` were asserted nowhere, so changing the foreground canvas 108 → 216 left `translate 22 / scale 1.0` asserting clean while the mark rendered half-size and off-centre; and the foreground's own stroke widths were unpinned, so the outer border at 6.0 puts the painted radius at 31.40 + 3.0 = **34.4 > 33**, outside the key-content circle US1 AC2 guarantees. The asymmetry gave it away: the monochrome layer's widths were pinned, the foreground's were not. | Closed inside the rows that own the subject. Row 02 now pins the foreground canvas to `CANVAS` and the whole `FOREGROUND_STROKE_WIDTHS` list; row 10 pins the monochrome canvas. Verified by mutation: E8 (foreground viewport), E8b (monochrome viewport) and E10 (outer stroke 6.0) are all caught, each by the row that owns it |
| W-R3-3 | WARNING: the notification shield's stroke opacity was unguarded, in a file where US2 states that Android renders a small icon **from its alpha channel only**, so colour is opacity. `#FFFFFF` → `#20FFFFFF` passed all 15 rows while erasing 87 % of the silhouette. Round 2 had closed exactly this defect for the flame's fill, leaving the closure asymmetric. | Row 12 now pins the full stroke-colour list to `listOf(OPAQUE_WHITE, "")`, which also subsumes the weaker "the knockout colour does not appear" assertion it replaces. Verified: E32 is caught |
| I-R3-1 | INFO: thirteen further mutations survived all 15 rows, and the matrix would keep growing without a declared terminus. The notable ones: the three `< / >` knockout paths moved **identically in both layers** (row 10 compares the layers only to each other, so a coordinated edit was invisible); the notification flame's `pathData` and its group scale; the foreground flame's fill and the inner border's stroke recoloured off-brand; `strokeAlpha` 0.85 → 0.1; the gradient inverted, re-typed, or its stops banded to 0.4/0.6. | **Nine closed**, all inside existing rows: `KNOCKOUT_PATHS` pins the symbol's geometry literally (and row 10 then propagates it to the tinted layer); row 12 pins the notification flame's whole `pathData` and its group transform; row 05 pins the complete `strokeColor`, `fillColor` and `strokeAlpha` lists plus `GRADIENT_GEOMETRY` and the two stop offsets. `FLAME_PREFIX` was replaced by `FLAME_PATH`, so the flame group is now selected by its entire path rather than a prefix. Verified: **13 of 13 round-3 survivors caught**, and the `FLAME_PATH` constant — assembled from three string literals — was checked equal to the `pathData` in all three drawables, since a concatenation typo would have produced a silently false guard. **Declared terminus**, the boundary this suite deliberately does not pin: `strokeLineCap`/`strokeLineJoin` (cosmetic; the miter tip at the shoulder computes to r ≈ 32.5, still inside 33) and the declared `dp` size of the two adaptive drawables (launcher layers are scaled to the mask, and `AboutScreen` overrides with `Modifier.size`) |
| I-R3-2 | INFO: the PR body attributed ktlint's 140-column limit to `.editorconfig`. | Measured false and corrected. The repository's `.editorconfig` is 8 lines and sets exactly one ktlint property, `ktlint_function_naming_ignore_when_annotated_with = Composable`; `max_line_length` appears in no `.editorconfig` or Gradle file. 140 is ktlint 1.8.0's `ktlint_official` default. The 140-vs-120 band argument stands; only the source was wrong |
| I-R3-3 | INFO: the plan's MB row was ticked while the PR's was not, for the same requirement. | The plan's wording is "MB1-MB4 **reported in the PR body, taken or declared**", which the body satisfies by declaring all four as pending. The ambiguity was in the PR row, which read as though they had been taken; it now says explicitly **declared as pending, not taken**, naming what each needs. Neither row claims a manual check that has not happened |
| I-R3-4 | INFO: round 2's out-of-scope observation — that the `testGmsDebugUnitTest` report contained no `BrandResourcesTest` entry — was wrong. | **Withdrawn**, by its author on re-measurement and independently by me on both artifacts (runs `38327032915` and `38329449571`): each contains `testGmsDebugUnitTest/com.danielealbano.androidremotecontrolmcp.brand.BrandResourcesTest/`, labelled `Gradle Test Run :app:testGmsDebugUnitTest`, reading 15 tests / 0 failures / 0 skipped, with all 15 row names present. The class runs under both variant tasks |

**Suite shape after round 3:** still **15 test methods** — no row was invented, every assertion went
into the row that already owned its subject. The shared infrastructure now differs from the plan's
"in full" block in **six** places: two forced by ktlint (`read()` by
`standard:chain-method-continuation`, `PARAMETER_COUNTS` by `standard:argument-list-wrapping`), and
four additive — **4** helpers and **19** companion constants beyond the plan's 6 and 14. Each added
member is a value some row compares against, or a helper some row needs.

**Measured after the change:** ktlint 1.8.0 → 0 findings on `app/src/main/kotlin` and
`app/src/test/kotlin`; detekt 2.0.0-alpha.5 → exit 0 on both.

---

## Review findings — `code-reviewer` (plan compliance), round 4, 2026-10-12

Verdict: **NOT CLEAN** — 0 critical, 1 WARNING, 4 INFO, all addressed below. No compliance defect was
found: the geometry, all five resource blocks (and both mipmaps against the shared block), the
`AboutScreen` line, the two strings, the ten-file scope, the plan-file integrity, both linters and CI
were re-verified correct.

**The round's most useful result is a correction to this plan's own premise, not a finding.** Rounds
1–3 and the PR body all rested on "Gradle cannot run on this host, so every assertion was
re-implemented in Python". That is false, and round 4 disproved it by doing the thing it declared
impossible: `BrandResourcesTest` is a pure-JVM JUnit 5 test — `javax.xml` DOM plus JUnit, no Android
class anywhere — so it needs no Gradle, no Android SDK and no emulator. **I reproduced that
independently rather than taking it on report**: `kotlin-compiler-embeddable` 2.4.0 and
`junit-platform-console-standalone` from the session scratchpad, `kotlin-stdlib`, `annotations`,
`kotlin-reflect` and `kotlinx-coroutines-core-jvm` from the existing Gradle cache, the module
mirrored into the scratchpad so `moduleDir` resolves there, and the repository never written to.

| ID | Finding | Resolution |
|----|---------|------------|
| WARNING-R4-1 | WARNING: the monochrome layer's `strokeColor` and `strokeAlpha` were unpinned, so recolouring all six strokes to `#20FFFFFF` (X5) or fading the inner border to 0.1 (X6) passed all 15 rows while leaving the themed icon at 12.5 % alpha. This is the **identical defect class** round 3 graded WARNING and closed for `ic_notification.xml`: a launcher *tints* this layer, so its colour is a carrier of opacity, and the row's own message — "a layer with no fill is defined entirely by its stroke widths" — was the incomplete premise. Breaks no stated acceptance criterion. | **Closed, not declared away.** The row that already owns the layer now pins `strokeColours(MONOCHROME)` to `MONOCHROME_STROKE_COLOURS` and its `strokeAlpha` list to `FOREGROUND_STROKE_ALPHAS`, so both layers must fade the inner border identically. Verified **by the real compiled test**: X5 and X6 each fail one row |
| INFO-R4-1 | INFO: round 3's W-R3-1 row claimed the PR body states that line 743's "3 helpers beyond the plan's 6" is superseded. The body contained neither the word nor the reference — an unverified claim about a document, the same class rounds 1–3 kept finding. | The sentence was added to the PR body, which now names the superseded count explicitly. The substance was never at risk: W-R3-1 itself supersedes line 743, and the shipped counts are 4 helpers and now 20 constants |
| INFO-R4-2 | INFO: round 3's I-R3-1 row said "a concatenation typo would have produced a silently false guard". Measured the other way: the three-literal `FLAME_PATH` is byte-exact in all three drawables, and a one-character typo makes `groupWrapping` match zero groups, failing two rows loudly with `expected: <1> but was: <0>`. | **The claim is inverted and is corrected here.** The selector is fail-closed, which is the stronger property; the check was still worth doing, but it protects against a wrong value, not a silent one |
| INFO-R4-3 | INFO: the monochrome layer's non-first group transforms were unpinned (X7 flame `translateY`, X8 inner `scaleX`, X9 flame `scaleX` all survived), while the foreground's and the notification's were pinned. No stated AC breached. | Closed in the same row: all three of the tinted layer's group transforms are now compared against `listOf(SAFE_ZONE_TRANSFORM, INNER_BORDER_TRANSFORM, FLAME_TRANSFORM)` — absolute constants, replacing a relative first-group-only comparison. Verified by the real test: X7, X8 and X9 each fail one row |
| INFO-R4-4 | INFO: the PR body's deviation "**Guard reversion not executed** — the plan's six reversion rows need a Gradle toolchain this host does not have" rests on the false premise above, and the Testing table's "40/40 mirrored assertions" row exists only because of it. | **The deviation is withdrawn and replaced by an observation.** I ran the real suite on this host — **15 successful, 0 failed** — and then executed all six of Task 4.1 Action 4's reversion rows on scratch copies: every one failed, by the test the plan names (scale 1.125/translate 18 → 1 failure; flame `translateY` 0 → 1; a `C` given five parameters → 2; `<monochrome>` removed from the round icon → 1; `app_name` reverted → 1; `AboutScreen` back to `72.dp` → 1). The plan's guard-reversion requirement is now **satisfied by observation rather than by declaration**, and eleven mutations from rounds 1–4 were re-judged by the real test as well, all caught |

**Suite shape after round 4:** still **15 test methods**. The shared infrastructure differs from the
plan's "in full" block in **six** places: two forced by ktlint (`read()` by
`standard:chain-method-continuation`, `PARAMETER_COUNTS` by `standard:argument-list-wrapping`) and
four additive — **4** helpers and **20** companion constants beyond the plan's 6 and 14
(`MONOCHROME_STROKE_COLOURS` is the twentieth, added by this round).

**Measured after the change:** the real compiled test 15/15; ktlint 1.8.0 → 0 findings on
`app/src/main/kotlin` and `app/src/test/kotlin`; detekt 2.0.0-alpha.5 → exit 0 on both.

**Declared terminus, unchanged and still defensible** — round 4 recomputed both of its claims and
both hold. `strokeLineCap`/`strokeLineJoin` stay unpinned: with the join removed, the miter tip at
the shoulder lands at r ≈ 32.50 (miter ratio 1.22, inside the default limit of 4), still within the
33 key-content circle. The two adaptive drawables' declared `dp` stays unpinned: their only three
consumers are the two mipmaps, where `AdaptiveIconDrawable` lays each layer out to the mask, and
`AboutScreen`, which overrides with `Modifier.size`. There are no density mipmap fallbacks.

---

## Review findings — `code-reviewer` (plan compliance), round 5, 2026-10-13

Verdict: **CLEAN** — 0 critical, 0 warnings, 0 findings requiring a change, plus one INFO whose
resolution the reviewer offered as record-only. **It was closed in code instead**, so the DoD
checkbox below stays open until a sixth pass confirms that delta; a clean verdict cannot cover a
change made after it was given.

Round 5 re-verified every round-4 resolution by its own measurement rather than on report:
it compiled and ran the real suite on this host (**15/15**), caught X5–X9 each by the row that owns
the tinted layer, observed all six of Task 4.1 Action 4's reversion rows failing by the test the plan
names, re-judged **30 of 30** mutations as caught, compared all five resource blocks (and both
mipmaps against the shared block) byte-identical to the plan, confirmed the ten-file scope and the
plan file at 27 flips and nothing else, ran both linters clean, and read run `38334334395` on
`76d2f10` green on all five checks with `BrandResourcesTest` 15/15 under both variant tasks.

Two of its checks are worth keeping because they close holes the earlier rounds left in the
*reasoning*, not in the code:

- **The replacement of the relative monochrome comparison is strictly stronger, not merely
  different.** Round 4 swapped a foreground↔monochrome first-group comparison for absolute
  constants, which raised the question of whether the inter-layer coupling was lost. Measured both
  directions: moving the tinted layer's outer group alone is caught, and moving **both** layers
  identically to `scale 1.125 / translate 18` is caught by two rows. The coupling survives
  transitively because the foreground's own group is pinned absolutely, and the absolute form
  additionally pins the group count and order (inserting a fourth group is caught).
- **The fail-closed property of `groupWrapping` was confirmed by observation**, settling round-4's
  INFO-R4-2: corrupting a selected `pathData` makes the selector match zero groups and fail loudly at
  `assertEquals(1, matches.size)`.

| ID | Finding | Resolution |
|----|---------|------------|
| I-R5-1 | INFO: three mutations survived all 15 rows, reachable only by **adding** an opacity attribute that no shipped drawable declares — `strokeAlpha` on the notification shield, and `fillAlpha` on the notification and foreground flames. `strokeAlpha` was pinned for the foreground and the tinted layer, so additions there were caught; `fillAlpha` was pinned nowhere. Breaks no stated acceptance criterion, and is a different mutation class from the 45+ graded in rounds 1–4, every one of which changed an *existing* value. | **Closed in code rather than declared.** The reviewer offered a record-only terminus; pinning costs four assertions and the threat is real — a faded flame ships as a gap in the shield and no colour, width or path assertion can see it. Each goes in the row that already owns the file: the foreground's `fillAlpha` list must be `NO_FILL_ALPHA`; the tinted layer, which already forbids a fill, now also forbids fading one; the notification icon's `strokeAlpha` and `fillAlpha` lists must both be empty, because at 24dp the silhouette *is* the icon. Verified **by the real compiled test**: all three survivors now fail one row each, and so does a fourth probe adding `fillAlpha` to the tinted layer. Suite still **15** rows; ktlint 0 findings, detekt exit 0 |

**Declared terminus, re-measured by round 5 and unchanged.** `strokeLineCap`/`strokeLineJoin` stay
unpinned — removing the foreground's `strokeLineJoin` survives, and the miter tip at the shoulder
recomputes to r ≈ 32.50 inside the 33 circle (interior angle 109.8°, miter ratio 1.222, inside the
default limit of 4). The two adaptive drawables' declared `dp` stays unpinned — halving it survives,
and their only three consumers are the two mipmaps, where `AdaptiveIconDrawable` lays each layer out
to the mask, and `AboutScreen`, which overrides with `Modifier.size`. With I-R5-1 closed, no opacity
attribute remains outside the guards.

**Suite shape:** **15 test methods**; the shared infrastructure differs from the plan's "in full"
block in **six** places — two forced by ktlint and four additive: **4** helpers and **21** companion
constants beyond the plan's 6 and 14 (`NO_FILL_ALPHA` is the twenty-first).

---

## Review findings — `code-reviewer` (plan compliance), round 6, 2026-10-13

Scope: the single commit `58a1e28`, which closed round-5's I-R5-1. Rounds 4 and 5 had each audited the
whole change independently and round 5 returned CLEAN, so this round was deliberately narrow.

Verdict: **the delta is sound, the round is NOT CLEAN.** The four added assertions were confirmed
correct and non-tautological — pinning a list of empty strings constrains both the *arity* and the
*absence at every position*, so neither adding a path nor moving the attribute between elements
satisfies it (both escapes were measured and caught). But the item I had explicitly asked the round to
hunt — whether any other way of fading or hiding the art slips past — found **four, each breaking a
stated acceptance criterion**. They were pre-existing gaps, not regressions from `58a1e28`.

| ID | Finding | Resolution |
|----|---------|------------|
| W-R6-1 | WARNING: **`android:alpha` on the `<vector>` root was unguarded in all three drawables**, including `alpha="0"`, which ships a wholly invisible icon. No row read the root element's non-`xmlns:` attributes: the namespace row filtered to names starting `xmlns:`, and the size and viewport rows read named attributes only. This is I-R5-1's own class one level up the tree — a fade that changes no colour, width, path or transform, except it fades the entire layer. Breaks US1 AC1 and US2 AC1. | **Closed.** Each drawable's root attribute-name set is now pinned as a complete sorted list (`FOREGROUND_ROOT_ATTRIBUTES`, `MONOCHROME_ROOT_ATTRIBUTES`, `NOTIFICATION_ROOT_ATTRIBUTES`) in the row that owns that file's root element, through a new `rootAttributes()` helper. Any attribute added at the root now fails, named or not. Verified by the real compiled test: `alpha="0.1"` on each of the three files and `alpha="0"` on the foreground each fail one row |
| W-R6-2 | WARNING: **an added root `android:tint` was unguarded on the foreground and the tinted layer.** With the default `SRC_IN` mode, `tint="#0b0d0f"` repaints the whole mark in the colour it sits on, and `tint="#00000000"` makes the layer transparent. The notification file was protected only because its tint *value* is pinned; the other two declare no tint, and adding one was invisible to every row. Breaks US1 AC1. | Closed by the same complete root attribute sets. Verified: both mutations fail one row. The notification's `tintMode` (round 6 graded it merely pinnable, since US2 AC3 states the tint has no effect on a small icon) is caught as well, because the set is exhaustive rather than a list of forbidden names |
| W-R6-3 | WARNING: **an added `<clip-path>` inside any `<group>` clipped the art away undetected.** It is a documented `VectorDrawable` group child, and every row selects the tag `path`, so a degenerate clip region was invisible to all of them. Breaks US1 AC1. | **Closed** in the row that iterates all three drawables: each must declare no `clip-path` at all. Verified: a degenerate clip in the foreground's outer group and in the tinted layer's inner group each fail one row |
| W-R6-4 | WARNING: **the notification's `strokeColor` could be redirected to a transparent `aapt:attr` gradient** while the plain attribute stayed `#FFFFFF`, so reading the attribute alone still looked right. The foreground is guarded against exactly this by its `aapt:attr` name-list assertion and the tinted layer by its no-gradient assertion; the notification had neither. Breaks US2 AC1. | **Closed**: the notification row now asserts it declares no `aapt:attr` and no `gradient` — it is painted flat, because at 24dp the silhouette is the icon. Verified: the full mutation (declaring `xmlns:aapt`, keeping the plain colour, wrapping the stroke in a transparent two-stop gradient) now fails two rows |
| I-R6-1 | INFO: `<clip-path>`'s own `pathData` was never structurally validated, although US4 AC1 says "every `pathData`" is. | Moot and closed by W-R6-3: no `clip-path` may exist in any of the three drawables. Verified with an invalid `"Q 1 2 3"` clip region, which now fails |
| I-R6-2 | INFO: three measured no-ops survive and are the genuine terminus candidates — `android:fillType` (both the shield and the flame are simple closed paths, so `evenOdd` ≡ `nonZero`), `android:strokeMiterLimit` (every join in all three files is `strokeLineJoin="round"`, so the limit is never consulted; and with the join removed a low limit bevels *inward*), and `android:alpha` on a `<group>` (not a `VectorDrawable` group attribute, so it is ignored — reported with the honest caveat that this host has no SDK, so only the test's silence was observed, not the renderer). | **Added to the declared terminus** rather than pinned. Re-measured after the closure: `fillType` and `strokeMiterLimit` still survive, which is correct — they are path attributes, and the closure pins root attributes. A duplicate attribute needs no guard: the DOM parser rejects it and eight rows fail |
| I-R6-3 | INFO: the round-5 heading is dated `2026-10-13` while rounds 1–4 read `2026-10-12`. | Not an error: the round-5 work ran past midnight UTC, and the date in each heading is the date that section was written. Recorded here so the sequence is not read as a typo |

**Suite shape after round 6:** still **15 test methods**. The shared infrastructure differs from the
plan's "in full" block in **six** places — two forced by ktlint and four additive: **5** helpers
(`rootAttributes()` is the fifth) and **24** companion constants beyond the plan's 6 members and 14
constants.

**Measured after the change:** the real compiled test 15/15 on this host; eleven "make the brand
disappear" mutations all caught, each by the row that owns its file; ktlint 1.8.0 → 0 findings and
detekt 2.0.0-alpha.5 → exit 0 on both source sets.

**Declared terminus, now complete.** Deliberately unpinned, each measured to have no effect on these
shapes: `strokeLineCap` and `strokeLineJoin` (the miter tip at the shoulder lands at r ≈ 32.50, inside
the 33 circle); the declared `dp` size of the two adaptive drawables (their only consumers are the two
mipmaps, where `AdaptiveIconDrawable` lays each layer out to the mask, and `AboutScreen`, which
overrides with `Modifier.size`); and `fillType`, `strokeMiterLimit` and a group-level `alpha`, per
I-R6-2. Everything that can fade, repaint, clip or hide the art is now pinned.

---

## Review findings — `code-reviewer` (plan compliance), round 7, 2026-10-13

Scope: the single commit `22755a4`. Verdict: **the delta is sound, the round is NOT CLEAN.** The five
assertions it added were confirmed correct, non-vacuous and strictly stronger than the
namespace-filtered form they replaced, and all eleven claimed closures reproduced on the reviewer's own
compiled runs. The hunt then found **four more visible ways** to change how the shipped art renders
with all 15 rows passing — none a no-op, none in the declared terminus.

**On the trade-off round 7 raised.** It offered a bounded but exhaustive closure — pin every group's
and every path's complete attribute-name set — and flagged, correctly, that this would retire part of
the documented terminus by guarding `fillType`, `strokeMiterLimit`, `strokeLineCap`, `strokeLineJoin`
and `name`, and said that trade needed a decision. **The trade turned out to be avoidable.** The
`VectorDrawable` attribute surface is finite and round 7 enumerated it; within it, the *visible* items
left unpinned were exactly the four findings. Pinning those four precisely closes the class and leaves
the terminus as measured, so nothing had to be decided away. Verified after the change: all four
classes are caught **and** every terminus item still survives, which is the check that the fix did not
quietly overreach.

| ID | Finding | Resolution |
|----|---------|------------|
| W-R7-1 | WARNING: **`android:trimPathStart` / `android:trimPathEnd` were unguarded on every path in all three drawables.** They trim the stroked geometry, so `trimPathEnd="0"` draws nothing while every colour, width, alpha, pathData and transform assertion stays green. Measured: the foreground's neon outer border gone; the notification's 24dp silhouette erased, leaving a bare flame; and on every monochrome path at once, **a wholly invisible themed icon** — that layer is strokes only, so it has nothing else to show. Breaks US1 AC1 and US2 AC1. | **Closed** in the row that iterates all three drawables: neither attribute may appear in any of the three files. `trimPathOffset` is deliberately left out and stays in the terminus — measured a no-op at the default full range. Verified by the real compiled test: four such mutations each fail one row |
| W-R7-2 | WARNING: **`android:rotation` was unguarded on every group.** `Element.transform()` read six attribute names; `<group>` admits exactly two more, and `rotation` is the visible one. A rotation about pivot `0,0` moves the whole 64-unit box, so it defeats US1 AC2 (every painted pixel inside the key-content circle) and Task 1.1's DoD, which requires the outer group's transform to be *exactly* pivot 0,0 / scale 1.0 / translate 22. | **Closed at the root of the problem** rather than per row: `rotation` was added to the helper's key list, so every group whose transform is asserted now pins it — the foreground's three, the tinted layer's three and the notification's one, which is every group in the change. The three expected-transform constants each gained `"rotation" to ""`. Verified: 90°, 180°, 45° and a plausible accidental 7° each fail one row |
| W-R7-3 | WARNING: **an extra nesting `<group>` was unguarded in the foreground and the notification.** Wrapping the existing groups in a `scale 0.1` group shrinks everything under it and passed all 15 rows; the tinted layer was already immune because its row pins its group-transform list to exactly three. Breaks US1 AC2 and US2 AC1. | **Closed** by pinning the group *count* in the two rows that own those files — three in the foreground, one in the notification — which leaves the existing path-to-transform bindings doing their own job instead of duplicating them. Verified: the foreground wrapper fails 8 rows and the notification wrapper 3 |
| W-R7-4 | WARNING: **the tinted layer's `strokeColor` could still be redirected through an `aapt:attr` child** whose namespace is declared on the `<path>` rather than the root, so the new root attribute set never saw it, and that layer's only paint guard forbade `<gradient>`, not `aapt:attr`. This is round 6's W-R6-4 class, closed for the notification and the foreground and left open on the one layer whose colour *is* its opacity. Breaks US1 AC1. | **Closed**: the tinted layer may not mention `aapt` anywhere, on any element — which covers the attr child and the namespace wherever it is declared. Verified with the full mutation, keeping the plain `#FFFFFF` and redirecting the stroke to a transparent `ColorStateList`: it now fails one row. The reviewer's honest caveat is kept: with no SDK on this host the XML legality and the suite's silence were measured, the renderer's acceptance of an inline `ColorStateList` was inferred from the documented attribute type |
| W-R7-5 | WARNING: two records overstated the coverage. The plan's round-6 section ends "**Everything that can fade, repaint, clip or hide the art is now pinned**", and `22755a4`'s commit message says the notification's flat painting is "which the other two files already required". Both are contradicted by the four findings above. | **Corrected here, not by rewriting either record.** The round-6 sentence stays as written — it is a sacred review section and this row is its correction; the claim was true of everything round 6 had measured and false as a general statement, which is exactly why round 7 was asked to hunt. The commit message stands as pushed: amending it would mean force-pushing rewritten history for a prose fix. The PR body's copy of the same sentence **was** editable and was corrected there |

**Suite shape after round 7:** still **15 test methods**. The shared infrastructure differs from the
plan's "in full" block in **six** places — two forced by ktlint and four additive: **5** helpers and
**25** companion constants beyond the plan's 6 members and 14 constants (`TRIM_ATTRIBUTES` is the
twenty-fifth).

**Measured after the change:** the real compiled test 15/15 on this host; the eleven mutations of
round 6 plus these eleven all caught, each in the row that owns its file; every terminus item still
surviving; ktlint 1.8.0 → 0 findings and detekt 2.0.0-alpha.5 → exit 0 on both source sets.

**Declared terminus, re-measured and unchanged.** Deliberately unpinned, each measured to have no
visible effect on these shapes: `strokeLineCap` and `strokeLineJoin`; the declared `dp` size of the
two adaptive drawables; `fillType`; `strokeMiterLimit`; `trimPathOffset`; `android:name` on a group or
a path; `android:alpha` on a group; and `tileMode` on the gradient, whose stops already span the whole
body. The honest form of the claim round 6 got wrong: **within the `VectorDrawable` attribute surface
that round 7 enumerated, every item that visibly changes the art is pinned, and the rest are listed
here with the measurement that found them harmless.**
