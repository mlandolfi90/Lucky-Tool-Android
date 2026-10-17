package com.danielealbano.androidremotecontrolmcp.policy.subject

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.BANK
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.FULL_SCREEN
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.KEYBOARD
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.SHOP
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.SYSTEM_UI
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.container
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.read
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.request
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectFixtures.window
import com.danielealbano.androidremotecontrolmcp.privacy.PlaceholderSubstitutor
import com.danielealbano.androidremotecontrolmcp.services.accessibility.MultiWindowResult
import com.danielealbano.androidremotecontrolmcp.services.accessibility.WindowData
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WindowSubjectResolverTest {
    private val windowSource = FakePolicyWindowSource()
    private val substitutor =
        mockk<PlaceholderSubstitutor> {
            every { substitute(any()) } answers { firstArg<String>().replace("AMOUNT#1", "9000") }
        }
    private val resolver = WindowSubjectResolver(windowSource, substitutor)

    private fun app(
        packageName: String?,
        layer: Int,
        type: String = SubjectSupport.WINDOW_TYPE_APPLICATION,
        focused: Boolean = false,
    ): WindowData = window(packageName, type, container("root_$layer", FULL_SCREEN, emptyList()), focused, layer)

    private suspend fun resolve(
        policyClass: ToolPolicyClass,
        toolName: String,
        arguments: JsonObject = buildJsonObject { },
        profiled: Set<String> = emptySet(),
    ): SubjectResolution = resolver.resolve(policyClass, request(toolName, arguments, profiled))

    private suspend fun screenRead(profiled: Set<String> = emptySet()): SubjectResolution =
        resolve(ToolPolicyClass.SCREEN_READ, "get_screen_state", profiled = profiled)

    private suspend fun setClipboard(profiled: Set<String>): SubjectResolution {
        val arguments = buildJsonObject { put("text", "AMOUNT#1") }
        return resolve(ToolPolicyClass.CLIPBOARD_WRITE, "set_clipboard", arguments, profiled)
    }

    private fun subjectOf(resolution: SubjectResolution) = (resolution as SubjectResolution.Resolved).subject

    private val readBlocked =
        SubjectResolution.Blocked(null, PolicyActionType.READ, BlockCause.TARGET_UNRESOLVED)

    @Test
    fun `screen read targets every window package`() =
        runTest {
            windowSource.windows =
                read(
                    app(BANK, 1, focused = true),
                    app(KEYBOARD, 2, SubjectSupport.WINDOW_TYPE_INPUT_METHOD),
                    app(SYSTEM_UI, 3, "SYSTEM"),
                )

            val subject = subjectOf(screenRead())

            assertEquals(PolicyActionType.READ, subject.actionType)
            assertEquals(listOf(BANK, KEYBOARD, SYSTEM_UI), subject.targets.map { it.packageName })
        }

    @Test
    fun `screen read with a package-less window`() =
        runTest {
            windowSource.windows = read(app(BANK, 1, focused = true), app(null, 2, "SYSTEM"))

            assertEquals(readBlocked, screenRead(setOf(BANK)))
            assertEquals(SubjectResolution.Unattributed, screenRead())
        }

    @Test
    fun `screen read on a degraded read`() =
        runTest {
            windowSource.windows = MultiWindowResult(listOf(app(BANK, 1, focused = true)), degraded = true)

            assertEquals(readBlocked, screenRead(setOf(BANK)))
            assertEquals(SubjectResolution.Unattributed, screenRead())
        }

    @Test
    fun `press_back targets the focused application window`() =
        runTest {
            windowSource.windows = read(app(BANK, 1, focused = true), app(SHOP, 2))

            val subject = subjectOf(resolve(ToolPolicyClass.PRESS_BACK, "press_back"))

            assertEquals(PolicyActionType.NAVIGATE, subject.actionType)
            assertEquals(listOf(PolicyTargets.packageOnly(BANK)), subject.targets)
        }

    @Test
    fun `press_back falls back to the highest-layer application window`() =
        runTest {
            windowSource.windows = read(app(BANK, 1), app(SHOP, 3), app(SYSTEM_UI, 9, "SYSTEM"))

            val subject = subjectOf(resolve(ToolPolicyClass.PRESS_BACK, "press_back"))

            assertEquals(listOf(SHOP), subject.targets.map { it.packageName })
        }

    @Test
    fun `set_clipboard in a profiled focused app resolves TYPE with the substituted text`() =
        runTest {
            windowSource.windows = read(app(BANK, 1, focused = true))

            val subject = subjectOf(setClipboard(setOf(BANK)))

            assertEquals(PolicyActionType.TYPE, subject.actionType)
            assertEquals(listOf(PolicyTargets.packageOnly(BANK)), subject.targets)
            assertEquals("9000", subject.amountText)
        }

    @Test
    fun `set_clipboard in an unprofiled focused app is NotAppScoped`() =
        runTest {
            windowSource.windows = read(app(SHOP, 1, focused = true))

            assertEquals(SubjectResolution.NotAppScoped, setClipboard(setOf(BANK)))
        }

    @Test
    fun `set_clipboard without text throws InvalidParams before reading`() =
        runTest {
            assertThrows<McpToolException.InvalidParams> { resolve(ToolPolicyClass.CLIPBOARD_WRITE, "set_clipboard") }
            assertEquals(0, windowSource.readCount)
        }

    @Test
    fun `set_clipboard with no application window is Blocked when profiles exist`() =
        runTest {
            windowSource.windows = read(app(SYSTEM_UI, 1, "SYSTEM"))

            assertEquals(
                SubjectResolution.Blocked(null, PolicyActionType.TYPE, BlockCause.TARGET_UNRESOLVED),
                setClipboard(setOf(BANK)),
            )
        }
}
