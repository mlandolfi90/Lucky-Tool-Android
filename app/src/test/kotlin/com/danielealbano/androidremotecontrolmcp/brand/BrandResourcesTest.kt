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
        File(moduleDir, relativePath)
            .also { assertTrue(it.isFile) { "missing resource: ${it.path}" } }
            .readText()

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

    /** The `path` elements that are direct children of this element, not descendants. */
    private fun Element.childPaths(): List<Element> =
        (0 until childNodes.length)
            .mapNotNull { childNodes.item(it) as? Element }
            .filter { it.tagName == "path" }

    /**
     * The one group in [relativePath] whose single direct path child satisfies [pathData].
     *
     * Selecting a group by the path it wraps — rather than by the transform the test then asserts —
     * keeps the assertion honest and makes deleting the wrapped path a failure.
     */
    private fun groupWrapping(
        relativePath: String,
        pathData: (String) -> Boolean,
    ): Element {
        val matches =
            parse(relativePath).elements("group").filter { group ->
                val paths = group.childPaths()
                paths.size == 1 && pathData(paths.single().getAttribute("android:pathData"))
            }
        assertEquals(1, matches.size) { "expected one group wrapping exactly that path" }
        return matches.single()
    }

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
        val commands = COMMAND.findAll(pathData).toList()
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
                    .flatMap { chunk -> NEGATIVE_SEPARATOR.split(chunk) }
                    .filter { it.isNotBlank() }
            if (expected == 0) {
                assertTrue(parameters.isEmpty()) { "$where: '$letter' takes no parameters" }
            } else {
                assertTrue(parameters.isNotEmpty() && parameters.size % expected == 0) {
                    "$where: '$letter' got ${parameters.size} parameters, " +
                        "expected a multiple of $expected"
                }
            }
        }
    }

    private fun strokeColours(relativePath: String): List<String> =
        parse(relativePath)
            .elements("path")
            .map { it.getAttribute("android:strokeColor").lowercase() }

    @Test
    fun `every brand drawable has structurally valid path data`() {
        DRAWABLES.forEach { drawable ->
            val paths = parse(drawable).elements("path")
            assertTrue(paths.isNotEmpty()) { "$drawable declares no path" }
            paths.forEachIndexed { index, path ->
                assertValidPathData(path.getAttribute("android:pathData"), "$drawable path #$index")
            }
        }
    }

    @Test
    fun `launcher foreground centres the mark inside the key content circle`() {
        val outer = parse(FOREGROUND).elements("group").first()
        assertEquals(SAFE_ZONE_TRANSFORM, outer.transform()) {
            "the mark must map to 22..86 of the 108 canvas (painted radius 32.30 <= 33)"
        }
    }

    @Test
    fun `launcher foreground scales the inner border about the mark centre`() {
        val inner = groupWrapping(FOREGROUND) { it == SHIELD_PATH }
        assertEquals(INNER_BORDER_TRANSFORM, inner.transform())
    }

    @Test
    fun `launcher foreground places the flame by the brand transform`() {
        val flame = groupWrapping(FOREGROUND) { it.startsWith(FLAME_PREFIX) }
        assertEquals(FLAME_TRANSFORM, flame.transform())
    }

    @Test
    fun `launcher foreground carries the brand colours`() {
        assertTrue(NEON in strokeColours(FOREGROUND)) { "the neon border colour is missing" }
        val stops =
            parse(FOREGROUND)
                .elements("item")
                .map { it.getAttribute("android:color").lowercase() }
        assertEquals(listOf(SLATE_TOP, SLATE_BOTTOM), stops)
    }

    @Test
    fun `launcher foreground knocks the symbol out in the background colour`() {
        val knockouts =
            parse(FOREGROUND)
                .elements("path")
                .filter { it.getAttribute("android:strokeColor").lowercase() == KNOCKOUT }
        assertEquals(3, knockouts.size) { "the < / > symbol is three strokes" }
        knockouts.forEach { assertEquals("2.6", it.getAttribute("android:strokeWidth")) }
    }

    @Test
    fun `launcher foreground declares only the android and aapt namespaces`() {
        val attributes = parse(FOREGROUND).documentElement.attributes
        val namespaces =
            (0 until attributes.length)
                .map { attributes.item(it).nodeName }
                .filter { it.startsWith("xmlns:") }
                .sorted()
        assertEquals(listOf("xmlns:aapt", "xmlns:android"), namespaces) {
            "the gradient needs xmlns:aapt, and no other namespace belongs here"
        }
    }

    @Test
    fun `launcher background is the brand background`() {
        val colour =
            parse(BACKGROUND_COLOR)
                .elements("color")
                .first { it.getAttribute("name") == "ic_launcher_background" }
        assertEquals(KNOCKOUT, colour.textContent.trim().lowercase())
    }

    @Test
    fun `both adaptive icons declare the same three layers`() {
        listOf(LAUNCHER, LAUNCHER_ROUND).forEach { icon ->
            val document = parse(icon)
            listOf("background", "foreground", "monochrome").forEach { layer ->
                assertEquals(1, document.elements(layer).size) { "$icon must declare one <$layer>" }
            }
        }
        assertEquals(read(LAUNCHER), read(LAUNCHER_ROUND)) {
            "the round variant must stay in step with its sibling"
        }
    }

    @Test
    fun `monochrome layer is strokes only`() {
        val document = parse(MONOCHROME)
        assertTrue(document.elements("gradient").isEmpty()) { "a tinted layer needs no gradient" }
        assertFalse("android:fillColor" in read(MONOCHROME)) {
            "launchers tint the monochrome layer, so it carries strokes only"
        }
        assertEquals(
            parse(FOREGROUND).elements("group").first().transform(),
            document.elements("group").first().transform(),
        ) { "the monochrome layer must use the foreground's safe-zone transform" }
    }

    @Test
    fun `notification icon keeps 24dp over the mark viewport`() {
        val root = parse(NOTIFICATION).documentElement
        assertEquals("24dp", root.getAttribute("android:width"))
        assertEquals("24dp", root.getAttribute("android:height"))
        assertEquals("64", root.getAttribute("android:viewportWidth"))
        assertEquals("64", root.getAttribute("android:viewportHeight"))
    }

    @Test
    fun `notification icon drops the inner border and the symbol`() {
        val document = parse(NOTIFICATION)
        assertEquals(2, document.elements("path").size) { "one shield border and the flame" }
        assertFalse(KNOCKOUT in strokeColours(NOTIFICATION)) { "the < / > does not survive 24dp" }
        assertEquals(
            "?attr/colorControlNormal",
            document.documentElement.getAttribute("android:tint"),
        )
    }

    @Test
    fun `app name is the Lucky name`() {
        val strings =
            parse(STRINGS)
                .elements("string")
                .associate { it.getAttribute("name") to it.textContent.trim() }
        assertEquals(APP_NAME, strings["app_name"])
        assertEquals(APP_NAME, strings["share_target_label"])
    }

    @Test
    fun `application id is not renamed`() {
        assertTrue(APPLICATION_ID in read(BUILD_FILE)) {
            "renaming the package would install a different app and discard the user's settings"
        }
    }

    @Test
    fun `about screen renders the icon large enough to fill its circle`() {
        val source = read(ABOUT_SCREEN)
        val iconReference = source.indexOf("R.drawable.ic_launcher_foreground")
        assertTrue(iconReference >= 0) { "the about screen no longer renders the launcher mark" }
        assertTrue(source.indexOf(ABOUT_ICON_SIZE, iconReference) >= 0) {
            "at 72.dp the mark would sit 43.1dp inside a 72dp circle; $ABOUT_ICON_SIZE fills it"
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
        const val APPLICATION_ID = "applicationId = \"com.danielealbano.androidremotecontrolmcp\""
        const val ABOUT_ICON_SIZE = "Modifier.size(108.dp)"
        const val NEON = "#00ff66"
        const val SLATE_TOP = "#2b2f38"
        const val SLATE_BOTTOM = "#1c1f26"
        const val KNOCKOUT = "#0b0d0f"

        const val SHIELD_PATH = "M32,4 L57,13 V31 C57,47 45,56 32,60 C19,56 7,47 7,31 V13 Z"
        const val FLAME_PREFIX = "M8,16c3.314"

        val DRAWABLES = listOf(FOREGROUND, MONOCHROME, NOTIFICATION)

        val SAFE_ZONE_TRANSFORM =
            mapOf(
                "pivotX" to "0",
                "pivotY" to "0",
                "scaleX" to "1.0",
                "scaleY" to "1.0",
                "translateX" to "22",
                "translateY" to "22",
            )

        val INNER_BORDER_TRANSFORM =
            mapOf(
                "pivotX" to "32",
                "pivotY" to "32",
                "scaleX" to "0.86",
                "scaleY" to "0.86",
                "translateX" to "",
                "translateY" to "",
            )

        val FLAME_TRANSFORM =
            mapOf(
                "pivotX" to "0",
                "pivotY" to "0",
                "scaleX" to "2.2",
                "scaleY" to "2.2",
                "translateX" to "14.4",
                "translateY" to "15.4",
            )

        /** SVG path commands and the number of parameters each repetition consumes. */
        val PARAMETER_COUNTS =
            mapOf(
                'M' to 2,
                'L' to 2,
                'T' to 2,
                'H' to 1,
                'V' to 1,
                'C' to 6,
                'S' to 4,
                'Q' to 4,
                'A' to 7,
                'Z' to 0,
            )

        val COMMAND = Regex("[A-Za-z][^A-Za-z]*")
        val NEGATIVE_SEPARATOR = Regex("(?<=\\d)-")
    }
}
