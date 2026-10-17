package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.BANK
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.FULL_SCREEN
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.container
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.field
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.read
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.request
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.window
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.AccessibilityNodeData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.BoundsData
import com.danielealbano.androidremotecontrolmcp.services.accessibility.ElementFinder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TextSubjectResolverTest {
    private val windowSource = FakePolicyWindowSource()
    private val substitutor =
        mockk<PlaceholderSubstitutor> {
            every { substitute(any()) } answers { firstArg<String>().replace("AMOUNT#1", "9000") }
        }
    private val resolver = TextSubjectResolver(windowSource, ElementFinder(), substitutor)
    private val fieldBounds = BoundsData(0, 0, 500, 100)

    private fun showField(node: AccessibilityNodeData) {
        val root = container("root", FULL_SCREEN, listOf(node))
        windowSource.windows = read(window(BANK, SubjectSupport.WINDOW_TYPE_APPLICATION, root, true, 1))
    }

    private fun amountField(
        text: String?,
        hint: String? = "Monto",
        isPassword: Boolean = false,
    ): AccessibilityNodeData = field("f", fieldBounds, text, hint, isPassword)

    private suspend fun type(
        toolName: String,
        arguments: JsonObject,
        profiled: Set<String> = emptySet(),
    ): SubjectResolution = resolver.resolve(ToolPolicyClass.TEXT_EDIT, request(toolName, arguments, profiled))

    private suspend fun pressKey(
        key: String,
        profiled: Set<String> = emptySet(),
    ): SubjectResolution {
        val arguments = buildJsonObject { put("key", key) }
        return resolver.resolve(ToolPolicyClass.PRESS_KEY, request("press_key", arguments, profiled))
    }

    private fun subjectOf(resolution: SubjectResolution) = (resolution as SubjectResolution.Resolved).subject

    private fun append(text: String): JsonObject =
        buildJsonObject {
            put("node_id", "f")
            put("text", text)
        }

    @Test
    fun `type_append_text amountText is current value plus appended text`() =
        runTest {
            showField(amountField("100"))

            val subject = subjectOf(type("type_append_text", append("0")))

            assertEquals("1000", subject.amountText)
            assertEquals(PolicyActionType.TYPE, subject.actionType)
            assertEquals(PolicyTargets.field(BANK, amountField("100")), subject.targets.single())
            assertFalse(subject.classifyTarget)
        }

    @Test
    fun `placeholders are substituted before computing the value`() =
        runTest {
            showField(amountField(""))

            val amountText = subjectOf(type("type_append_text", append("AMOUNT#1"))).amountText.orEmpty()

            assertTrue(amountText.contains("9000"))
            assertFalse(amountText.contains("AMOUNT#1"))
        }

    @Test
    fun `field showing its hint counts as empty`() =
        runTest {
            showField(amountField("0,00", hint = "0,00"))

            assertEquals("5", subjectOf(type("type_append_text", append("5"))).amountText)
        }

    private fun insert(offset: Int): JsonObject =
        buildJsonObject {
            put("node_id", "f")
            put("text", "0")
            put("offset", offset)
        }

    @Test
    fun `type_insert_text inserts at offset`() =
        runTest {
            showField(amountField("15"))

            assertEquals("105", subjectOf(type("type_insert_text", insert(1))).amountText)
        }

    @Test
    fun `type_insert_text clamps an offset beyond the value`() =
        runTest {
            showField(amountField("15"))

            assertEquals("150", subjectOf(type("type_insert_text", insert(99))).amountText)
        }

    private fun replace(
        search: String,
        newText: String,
    ): JsonObject =
        buildJsonObject {
            put("node_id", "f")
            put("search", search)
            put("new_text", newText)
        }

    @Test
    fun `type_replace_text replaces the first occurrence`() =
        runTest {
            showField(amountField("1.500,00"))

            assertEquals("1500,00", subjectOf(type("type_replace_text", replace(".", ""))).amountText)
        }

    @Test
    fun `type_replace_text with search missing evaluates both values`() =
        runTest {
            showField(amountField("100"))

            assertEquals("100\n9000", subjectOf(type("type_replace_text", replace("x", "9000"))).amountText)
        }

    @Test
    fun `type_clear_text amountText is empty`() =
        runTest {
            showField(amountField("100"))

            val subject = subjectOf(type("type_clear_text", buildJsonObject { put("node_id", "f") }))

            assertEquals("", subject.amountText)
        }

    @Test
    fun `TYPE target carries field label selector and isPassword`() =
        runTest {
            showField(amountField("12", hint = "PIN", isPassword = true))

            val target = subjectOf(type("type_append_text", append("3"))).targets.single()

            assertTrue(target.isPassword)
            assertEquals("PIN", target.nodeLabel)
            assertTrue(target.identifiable)
        }

    @Test
    fun `unknown node_id throws NodeNotFound`() =
        runTest {
            showField(amountField("100"))
            val arguments =
                buildJsonObject {
                    put("node_id", "zz")
                    put("text", "1")
                }

            assertThrows<McpToolException.NodeNotFound> { type("type_append_text", arguments) }
        }

    @Test
    fun `empty node_id throws InvalidParams before reading`() =
        runTest {
            val arguments =
                buildJsonObject {
                    put("node_id", "")
                    put("text", "1")
                }

            assertThrows<McpToolException.InvalidParams> { type("type_append_text", arguments) }
            assertEquals(0, windowSource.readCount)
        }

    @Test
    fun `press_key HOME is ExemptExit`() =
        runTest {
            assertEquals(SubjectResolution.ExemptExit, pressKey("HOME"))
            assertEquals(0, windowSource.readCount)
        }

    @Test
    fun `press_key BACK is NAVIGATE on the focused application window`() =
        runTest {
            showField(amountField("1"))

            val subject = subjectOf(pressKey("BACK"))

            assertEquals(PolicyActionType.NAVIGATE, subject.actionType)
            assertEquals(listOf(PolicyTargets.packageOnly(BANK)), subject.targets)
        }

    @Test
    fun `press_key ENTER is unclassified package-only ACTION_BUTTON on the focused field package`() =
        runTest {
            windowSource.focusedField = FocusedField(BANK, amountField("100"))

            val subject = subjectOf(pressKey("ENTER"))

            assertEquals(PolicyActionType.ACTION_BUTTON, subject.actionType)
            assertEquals(listOf(PolicyTargets.packageOnly(BANK)), subject.targets)
            assertFalse(subject.classifyTarget)
        }

    @Test
    fun `press_key DEL amountText drops the last character`() =
        runTest {
            windowSource.focusedField = FocusedField(BANK, amountField("1,5000"))

            val subject = subjectOf(pressKey("DEL"))

            assertEquals("1,500", subject.amountText)
            assertEquals(PolicyActionType.TYPE, subject.actionType)
        }

    @Test
    fun `press_key TAB and SPACE append to the field value`() =
        runTest {
            windowSource.focusedField = FocusedField(BANK, amountField("12"))

            assertEquals("12\t", subjectOf(pressKey("TAB")).amountText)
            assertEquals("12 ", subjectOf(pressKey("SPACE")).amountText)
        }

    @Test
    fun `press_key lowercase key is accepted`() =
        runTest {
            windowSource.focusedField = FocusedField(BANK, amountField("12"))

            assertEquals(PolicyActionType.ACTION_BUTTON, subjectOf(pressKey("enter")).actionType)
        }

    @Test
    fun `press_key without a focused field throws NodeNotFound with the tool message`() =
        runTest {
            val enter = assertThrows<McpToolException.NodeNotFound> { pressKey("ENTER") }
            val del = assertThrows<McpToolException.NodeNotFound> { pressKey("DEL") }

            assertEquals("No focused node found for ENTER key", enter.message)
            assertEquals("No focused editable node found for DEL key", del.message)
        }

    @Test
    fun `press_key unknown key throws InvalidParams`() =
        runTest {
            assertThrows<McpToolException.InvalidParams> { pressKey("DPAD_CENTER") }
        }

    @Test
    fun `focused field without package`() =
        runTest {
            windowSource.focusedField = FocusedField(null, amountField("12"))

            assertEquals(
                SubjectResolution.Blocked(null, PolicyActionType.ACTION_BUTTON, BlockCause.TARGET_UNRESOLVED),
                pressKey("ENTER", setOf(BANK)),
            )
            assertEquals(SubjectResolution.Unattributed, pressKey("ENTER"))
        }

    @Test
    fun `FocusedField toString omits field text`() {
        assertFalse(FocusedField(BANK, amountField("98765")).toString().contains("98765"))
    }
}
