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

    /** One attribute of every `path` in the drawable, in document order. */
    private fun pathAttributes(
        relativePath: String,
        attribute: String,
    ): List<String> =
        parse(relativePath)
            .elements("path")
            .map { it.getAttribute("android:$attribute") }

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
        assertEquals(SHIELD_PATH, outer.childPaths().first().getAttribute("android:pathData")) {
            "the painted outline sets the radius as much as the transform does: a shoulder " +
                "moved outwards clips under the circular mask however the group is scaled"
        }
        val root = parse(FOREGROUND).documentElement
        assertEquals(CANVAS, root.getAttribute("android:viewportWidth")) {
            "mapping the mark to 22..86 means nothing unless the canvas is $CANVAS units wide"
        }
        assertEquals(CANVAS, root.getAttribute("android:viewportHeight"))
        assertEquals(FOREGROUND_STROKE_WIDTHS, pathAttributes(FOREGROUND, "strokeWidth")) {
            "the radius is the outline plus half the outer stroke: at 6.0 it reaches 34.4"
        }
    }

    @Test
    fun `launcher foreground scales the inner border about the mark centre`() {
        val inner = groupWrapping(FOREGROUND) { it == SHIELD_PATH }
        assertEquals(INNER_BORDER_TRANSFORM, inner.transform())
    }

    @Test
    fun `launcher foreground places the flame by the brand transform`() {
        val flame = groupWrapping(FOREGROUND) { it == FLAME_PATH }
        assertEquals(FLAME_TRANSFORM, flame.transform())
    }

    @Test
    fun `launcher foreground carries the brand colours`() {
        assertEquals(FOREGROUND_STROKE_COLOURS, strokeColours(FOREGROUND)) {
            "every stroke is either the neon border or the knockout, in that order"
        }
        assertEquals(
            FOREGROUND_FILL_COLOURS,
            pathAttributes(FOREGROUND, "fillColor").map { it.lowercase() },
        ) { "the flame is the only filled path, and it is neon" }
        assertEquals(FOREGROUND_STROKE_ALPHAS, pathAttributes(FOREGROUND, "strokeAlpha")) {
            "the brand fades the inner border to 0.85 and nothing else"
        }
        assertEquals(
            listOf("android:fillColor"),
            parse(FOREGROUND).elements("aapt:attr").map { it.getAttribute("name") },
        ) { "the gradient must fill the shield, not some other attribute" }
        val gradient = parse(FOREGROUND).elements("gradient").single()
        assertEquals(
            GRADIENT_GEOMETRY,
            GRADIENT_GEOMETRY.keys.associateWith { gradient.getAttribute("android:$it") },
        ) { "a swapped or re-typed gradient lights the slate body from the wrong end" }
        val stops =
            parse(FOREGROUND)
                .elements("item")
                .map { it.getAttribute("android:color").lowercase() }
        assertEquals(listOf(SLATE_TOP, SLATE_BOTTOM), stops)
        assertEquals(
            listOf("0", "1"),
            parse(FOREGROUND).elements("item").map { it.getAttribute("android:offset") },
        ) { "the two stops must span the whole body, not a band inside it" }
    }

    @Test
    fun `launcher foreground knocks the symbol out in the background colour`() {
        val knockouts =
            parse(FOREGROUND)
                .elements("path")
                .filter { it.getAttribute("android:strokeColor").lowercase() == KNOCKOUT }
        assertEquals(3, knockouts.size) { "the < / > symbol is three strokes" }
        knockouts.forEach { assertEquals("2.6", it.getAttribute("android:strokeWidth")) }
        assertEquals(KNOCKOUT_PATHS, knockouts.map { it.getAttribute("android:pathData") }) {
            "comparing the two layers to each other cannot catch a coordinated edit"
        }
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
            LAYER_DRAWABLES.forEach { (layer, drawable) ->
                val layers = document.elements(layer)
                assertEquals(1, layers.size) { "$icon must declare one <$layer>" }
                assertEquals(drawable, layers.single().getAttribute("android:drawable")) {
                    "$icon: <$layer> must point at $drawable"
                }
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
            listOf(SAFE_ZONE_TRANSFORM, INNER_BORDER_TRANSFORM, FLAME_TRANSFORM),
            document.elements("group").map { it.transform() },
        ) { "the tinted layer must place the mark exactly as the foreground does" }
        assertEquals(
            pathAttributes(FOREGROUND, "pathData"),
            pathAttributes(MONOCHROME, "pathData"),
        ) { "the tinted layer must draw the same artwork, path for path" }
        assertEquals(MONOCHROME_STROKE_WIDTHS, pathAttributes(MONOCHROME, "strokeWidth")) {
            "a layer with no fill is defined entirely by its stroke widths"
        }
        assertEquals(CANVAS, document.documentElement.getAttribute("android:viewportWidth"))
        assertEquals(CANVAS, document.documentElement.getAttribute("android:viewportHeight"))
        assertEquals(MONOCHROME_STROKE_COLOURS, strokeColours(MONOCHROME)) {
            "a launcher tints this layer, so its colour is opacity: a faded stroke fades " +
                "the themed icon, which no stroke width or path can show"
        }
        assertEquals(FOREGROUND_STROKE_ALPHAS, pathAttributes(MONOCHROME, "strokeAlpha")) {
            "both layers fade the inner border to 0.85 and leave everything else opaque"
        }
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
        assertEquals(listOf(OPAQUE_WHITE, ""), strokeColours(NOTIFICATION)) {
            "a small icon renders alpha only, so a faded stroke erases the silhouette " +
                "and the knockout colour must not appear at all at 24dp"
        }
        assertEquals(SHIELD_PATH, pathAttributes(NOTIFICATION, "pathData").first())
        assertEquals(FLAME_PATH, pathAttributes(NOTIFICATION, "pathData").last())
        assertEquals(FLAME_TRANSFORM, groupWrapping(NOTIFICATION) { it == FLAME_PATH }.transform())
        assertEquals("2.6", pathAttributes(NOTIFICATION, "strokeWidth").first()) {
            "below 2.6 the border thins past one device pixel at 24dp"
        }
        assertEquals(
            listOf("", OPAQUE_WHITE),
            pathAttributes(NOTIFICATION, "fillColor").map { it.lowercase() },
        ) { "the shield is stroked and the flame is filled opaque" }
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
        const val OPAQUE_WHITE = "#ffffff"
        const val CANVAS = "108"
        const val FLAME_PATH =
            "M8,16c3.314,0 6,-2 6,-5.5 0,-1.5 -0.5,-4 -2.5,-6 0.25,1.5 -1.25,2 -1.25,2 " +
                "C11,4 9,0.5 6,0 c0.357,2 0.5,4 -2,6 -1.25,1 -2,2.729 -2,4.5 " +
                "C2,14 4.686,16 8,16 Z"

        val KNOCKOUT_PATHS =
            listOf(
                "M26,37.64 L23,41.64 L26,45.64",
                "M33.5,36.64 L30.5,46.64",
                "M38,37.64 L41,41.64 L38,45.64",
            )

        val FOREGROUND_STROKE_WIDTHS = listOf("1.8", "2.1", "", "2.6", "2.6", "2.6")
        val FOREGROUND_STROKE_COLOURS =
            listOf(NEON, NEON, "", KNOCKOUT, KNOCKOUT, KNOCKOUT)
        val FOREGROUND_FILL_COLOURS = listOf("", "", NEON, "", "", "")
        val FOREGROUND_STROKE_ALPHAS = listOf("", "0.85", "", "", "", "")
        val MONOCHROME_STROKE_COLOURS = List(6) { OPAQUE_WHITE }

        val GRADIENT_GEOMETRY =
            mapOf(
                "type" to "linear",
                "startX" to "32",
                "startY" to "4",
                "endX" to "32",
                "endY" to "60",
            )

        val LAYER_DRAWABLES =
            mapOf(
                "background" to "@color/ic_launcher_background",
                "foreground" to "@drawable/ic_launcher_foreground",
                "monochrome" to "@drawable/ic_launcher_monochrome",
            )

        val MONOCHROME_STROKE_WIDTHS = listOf("1.8", "2.1", "0.9", "2.6", "2.6", "2.6")

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
    }
}
