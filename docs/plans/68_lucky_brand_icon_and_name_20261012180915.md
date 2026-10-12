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
- [ ] `ic_launcher_foreground.xml` draws the shield with both borders, the flame and the `< / >`
      knockout, from the mark's own path data.
- [ ] The mark's 64-unit coordinate box maps to 22..86 of the 108 canvas, which keeps every painted
      pixel inside the 66dp key-content circle (painted radius 32.30 ≤ 33).
- [ ] The adaptive background is the brand background `#0b0d0f`.
- [ ] Both `ic_launcher.xml` and `ic_launcher_round.xml` declare background, foreground **and**
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
- [ ] The root declares `xmlns:android` and `xmlns:aapt`, and no other namespace.
- [ ] The outer group's transform attributes are exactly `pivot 0,0`, `scale 1.0`, `translate 22`.

### Task 1.2 — Brand background colour

**Action 1 — modify** `app/src/main/res/values/ic_launcher_background.xml`.

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#0b0d0f</color>
</resources>
```

**Definition of Done:**
- [ ] The resource name is unchanged, so both adaptive-icon XMLs and `AboutScreen` keep resolving.

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
- [ ] The monochrome drawable contains no `android:fillColor` and no `<gradient>`.

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
- [ ] Only the `Modifier.size` of that one `Image` changes; the `Box` and every other composable in
      the file are untouched.

---

## User story 2 — Notification icon from the mono mark

The status-bar icon is upstream's generic phone glyph. Android renders a notification small icon from
its **alpha channel only** and tints it itself, so the mono mark is the correct source and the colours
in the file are carriers of opacity, not of appearance. Per G5 the inner border and the `< / >` are
dropped because they do not survive 24dp.

**Acceptance criteria:**
- [ ] `ic_notification.xml` draws the shield with a single 2.6-wide border and a filled flame; the
      inner border and the `< / >` are absent.
- [ ] Its declared size stays 24dp and its viewport becomes the mark's own 64×64, so the path data
      needs no rescaling.
- [ ] The `android:tint` attribute keeps its current value. It has no effect on a small icon — all
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
- [ ] The `android:tint` value is byte-identical to the file's previous value.
- [ ] No path strokes `#0b0d0f`.

---

## User story 3 — App name

The user named the app `lucky-mcp-android`. Only the two app string resources change: per G3 the
`applicationId` stays, and per G7 the repository, plugin and documentation names are out of scope.
`app_name` is the manifest `android:label` and the only name the app renders; `share_target_label` is
the share-alias label and today matches it.

**Acceptance criteria:**
- [ ] `app_name` is `lucky-mcp-android`.
- [ ] `share_target_label` is `lucky-mcp-android`.
- [ ] No Gradle file changes: `applicationId`, `namespace` and both flavour suffixes are untouched.

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
- [ ] `git diff` on `app/build.gradle.kts` is empty.
- [ ] Only those two strings change.

---

## User story 4 — Guard the brand resources with tests

AAPT2 does not validate `pathData`: a malformed path compiles and then throws at inflate time on the
device. The transforms are worse — a wrong pivot or translate produces a perfectly valid icon in the
wrong place, which no build step can see. These JVM guards (JUnit 5, `@DisplayName`, no mocks, no
Robolectric, no device) therefore check the three things that can silently ship: path syntax, every
group transform, and the brand values.

**What they cannot do:** judge whether the result *looks* right at 48dp. That is MB1-MB4.

**Acceptance criteria:**
- [ ] Every brand drawable parses, and every `pathData` is structurally valid — starts with a move
      command and gives each command a legal number of parameters.
- [ ] All three group transforms of the foreground are asserted, not only the outer one.
- [ ] The brand colours, the adaptive layers, the notification geometry, both app-name strings and the
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
- [ ] The file exists with 15 test methods and the shared infrastructure above.
- [ ] No `@Suppress` anywhere in the file.

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
- [ ] Local pre-checks clean; the five CI checks green, read with `gh`; no Kotlin `w: ` warning
      introduced by this plan.
- [ ] Every reversion row observed failing and restored, or declared as not executed.
- [ ] `code-reviewer` plan-compliance pass clean and recorded.
- [ ] MB1-MB4 reported in the PR body, taken or declared; PR URL reported.

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
