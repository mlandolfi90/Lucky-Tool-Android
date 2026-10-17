package com.danielealbano.androidremotecontrolmcp.policy.gate

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.BANK_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.NOTES_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.OWN_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.policy.subject.BlockCause
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectResolution
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class ToolPolicyGateImplLockTest : ToolPolicyGateTestBase() {
    @Test
    fun `run under a profile resolves once`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.RUN))
            scriptTap()

            tap()

            assertEquals(1, executions)
            assertEquals(1, subjects.resolveCount)
        }

    @Test
    fun `consumed chat grant under a profile resolves once`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.ASK_IN_CHAT))
            scriptTap()
            coordinator.confirmInChat(idOf(tap()), clock.get())
            val before = subjects.resolveCount

            tap()

            assertEquals(1, executions)
            assertEquals(before + 1, subjects.resolveCount)
        }

    @Test
    fun `notify approval re-resolves once after the wait`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTap()
            approveWhenPending()

            tap()

            assertEquals(1, executions)
            assertEquals(2, subjects.resolveCount)
        }

    @Test
    fun `notify wait does not hold the lock`() =
        runTest {
            useProfiles(
                bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY, PolicyActionType.NAVIGATE to RuleMode.RUN),
            )
            scriptTap()
            subjects.script("swipe") {
                SubjectResolution.Resolved(subjectOn(BANK_PACKAGE, PolicyActionType.NAVIGATE))
            }
            val waiting = async { tap() }
            runCurrent()

            gate.guard("swipe", null, execute)

            assertEquals(1, executions)
            assertFalse(waiting.isCompleted)
            waiting.await()
        }

    private fun scriptTapSequence(second: SubjectResolution) {
        var calls = 0
        subjects.script("tap") {
            calls++
            if (calls == 1) SubjectResolution.Resolved(paySubject) else second
        }
    }

    @Test
    fun `stricter decision after the wait wins`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTapSequence(SubjectResolution.Resolved(subjectOn(OWN_PACKAGE)))
            approveWhenPending()

            val result = tap()

            assertEquals("own_package", jsonOf(result).getValue("reason").jsonPrimitive.content)
            assertEquals(0, executions)
        }

    @Test
    fun `blocked after the wait wins`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTapSequence(
                SubjectResolution.Blocked(
                    BANK_PACKAGE,
                    PolicyActionType.ACTION_BUTTON,
                    BlockCause.IME_INPUT_IN_PROFILED_APP,
                ),
            )
            approveWhenPending()

            val result = tap()

            assertEquals(forbiddenJson("ime_input_in_profiled_app"), jsonOf(result))
            assertEquals(0, executions)
        }

    @Test
    fun `changed target after the wait needs a new approval`() =
        runTest {
            val notes =
                PolicyFixtures.commonProfile().let {
                    it.copy(rules = it.rules + (PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
                }
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY), notes)
            scriptTapSequence(SubjectResolution.Resolved(subjectOn(NOTES_PACKAGE)))
            var firstId: String? = null
            launch {
                val pending = coordinator.observePending().first { it.isNotEmpty() }.first()
                firstId = pending.id
                coordinator.approve(pending.id, clock.get())
            }

            val result = tap()

            assertEquals(GateResults.STATUS_AWAITING, jsonOf(result).getValue("status").jsonPrimitive.content)
            assertNotEquals(firstId, idOf(result))
            assertTrue(testScheduler.currentTime < NOTIFY_WAIT_MS)
            assertEquals(0, executions)
        }

    @Test
    fun `unchanged target after the wait executes`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTap()
            approveWhenPending()

            assertEquals("ok", textOf(tap()))
            assertEquals(1, executions)
        }

    @Test
    fun `mutating calls are serialized while a profile exists`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.RUN))
            scriptTap()
            val release = CompletableDeferred<Unit>()
            var secondEntered = false
            val first =
                async {
                    tap(args(1)) {
                        release.await()
                        CallToolResult(content = listOf(TextContent(text = "first")))
                    }
                }
            runCurrent()
            val second =
                async {
                    tap(args(2)) {
                        secondEntered = true
                        CallToolResult(content = listOf(TextContent(text = "second")))
                    }
                }
            runCurrent()

            assertEquals(1, subjects.resolveCount)
            assertFalse(secondEntered)

            release.complete(Unit)
            first.await()
            second.await()

            assertTrue(secondEntered)
            assertEquals(2, subjects.resolveCount)
        }

    @Test
    fun `reads never take the mutation lock`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.RUN))
            scriptTap()
            subjects.script("get_screen_state") {
                SubjectResolution.Resolved(subjectOn(BANK_PACKAGE, PolicyActionType.READ))
            }
            val release = CompletableDeferred<Unit>()
            val mutating =
                async {
                    tap {
                        release.await()
                        CallToolResult(content = listOf(TextContent(text = "tap")))
                    }
                }
            runCurrent()

            gate.guard("get_screen_state", null, execute)

            assertEquals(1, executions)
            assertFalse(mutating.isCompleted)
            release.complete(Unit)
            mutating.await()
        }

    @Test
    fun `read only class resolving a mutating subject re-checks under the lock`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.RUN))
            scriptTap()
            subjects.script("get_screen_state") { SubjectResolution.Resolved(paySubject) }
            val release = CompletableDeferred<Unit>()
            val mutating =
                async {
                    tap {
                        release.await()
                        CallToolResult(content = listOf(TextContent(text = "tap")))
                    }
                }
            runCurrent()
            val before = subjects.resolveCount
            val read = async { gate.guard("get_screen_state", null, execute) }
            runCurrent()

            assertEquals(0, executions)
            assertFalse(read.isCompleted)

            release.complete(Unit)
            mutating.await()
            read.await()

            assertEquals(1, executions)
            assertEquals(before + 2, subjects.resolveCount)
        }

    @Test
    fun `no profile means no lock`() =
        runTest {
            scriptTap()
            val release = CompletableDeferred<Unit>()
            var inside = 0
            val body: suspend () -> CallToolResult = {
                inside++
                release.await()
                CallToolResult(content = listOf(TextContent(text = "ok")))
            }
            val first = async { tap(args(1), body) }
            val second = async { tap(args(2), body) }
            runCurrent()

            assertEquals(2, inside)
            assertEquals(2, subjects.resolveCount)

            release.complete(Unit)
            first.await()
            second.await()
        }

    @Test
    fun `resolution failure propagates and nothing runs`() =
        runTest {
            val failure = McpToolException.NodeNotFound("Node 'x' not found in accessibility tree")
            subjects.script("tap") { throw failure }

            val thrown = assertThrows<McpToolException.NodeNotFound> { tap() }

            assertEquals(failure, thrown)
            assertEquals(0, executions)
        }

    @Test
    fun `results and logs never contain the amount`() =
        runTest {
            val typeSubject =
                PolicyFixtures.subject(
                    PolicyActionType.TYPE,
                    listOf(PolicyFixtures.target(label = "Pay", selector = paySelector)),
                    amountText = "98765",
                )
            subjects.script("type_append_text") { SubjectResolution.Resolved(typeSubject) }
            val askProfile =
                PolicyFixtures.commonProfile(packageName = BANK_PACKAGE).let {
                    it.copy(rules = it.rules + (PolicyActionType.TYPE to RuleMode.ASK_IN_CHAT))
                }
            useProfiles(askProfile)
            val ask = gate.guard("type_append_text", args(), execute)
            useProfiles(PolicyFixtures.moneyProfile())
            val forbidden = gate.guard("type_append_text", args(), execute)

            assertEquals(GateResults.STATUS_NEEDS_CONFIRMATION, jsonOf(ask).getValue("status").jsonPrimitive.content)
            assertEquals("amount_above_max", jsonOf(forbidden).getValue("reason").jsonPrimitive.content)
            val texts = listOf(textOf(ask), textOf(forbidden)) + serverLog.entries.map { it.message }
            texts.forEach { text ->
                listOf("98765", "Pay", PAY_RESOURCE_ID).forEach { assertFalse(text.contains(it), "$it in $text") }
            }
        }
}
