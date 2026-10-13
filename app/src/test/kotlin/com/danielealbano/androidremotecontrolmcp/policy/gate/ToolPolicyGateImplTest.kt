package com.danielealbano.androidremotecontrolmcp.policy.gate

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileStoreUnreadableException
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallMarker
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.BANK_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.NOW_MS
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.OWN_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalPolicy
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.policy.subject.BlockCause
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectResolution
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ToolPolicyGateImplTest : ToolPolicyGateTestBase() {
    @Test
    fun `not app scoped tool executes without reading profiles`() =
        runTest {
            gate.guard("list_files", null, execute)

            assertEquals(1, executions)
            assertEquals(0, subjects.resolveCount)
            coVerify(exactly = 0) { repository.current() }
        }

    @Test
    fun `exempt exit tool executes without reading profiles`() =
        runTest {
            gate.guard("press_home", null, execute)

            assertEquals(1, executions)
            assertEquals(0, subjects.resolveCount)
            coVerify(exactly = 0) { repository.current() }
        }

    @Test
    fun `unclassified tool throws`() =
        runTest {
            assertThrows<IllegalStateException> { gate.guard("nope", null, execute) }
            assertEquals(0, executions)
        }

    @Test
    fun `unattributed call executes`() =
        runTest {
            tap()

            assertEquals(1, executions)
            assertEquals(1, subjects.resolveCount)
            assertTrue(approvalMessages().isEmpty())
        }

    @Test
    fun `blocked call is forbidden with the cause code`() =
        runTest {
            subjects.script("tap") {
                SubjectResolution.Blocked(BANK_PACKAGE, PolicyActionType.ACTION_BUTTON, BlockCause.TARGET_UNRESOLVED)
            }

            val (result, marker) = guardMarked()

            assertEquals(true, result.isError)
            assertTrue(textOf(result).startsWith(McpToolUtils.UNTRUSTED_CONTENT_WARNING))
            assertEquals(forbiddenJson("target_unresolved"), jsonOf(result))
            val message = approvalMessages().single()
            assertTrue(message.startsWith("forbidden"))
            assertTrue(message.contains("reason=target_unresolved"))
            assertEquals(ToolCallMarker.FORBIDDEN, marker)
            assertEquals(0, executions)
        }

    @Test
    fun `run decision executes and logs nothing`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.RUN))
            scriptTap()

            val (_, marker) = guardMarked()

            assertEquals(1, executions)
            assertTrue(approvalMessages().isEmpty())
            assertNull(marker)
        }

    @Test
    fun `forbidden returns untrusted error json`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.FORBIDDEN))
            scriptTap()

            val (result, marker) = guardMarked()

            assertEquals(true, result.isError)
            assertEquals(McpToolUtils.UNTRUSTED_CONTENT_WARNING, textOf(result).lines().first())
            assertEquals(forbiddenJson("rule"), jsonOf(result))
            assertEquals(0, executions)
            assertTrue(approvalMessages().single().startsWith("forbidden"))
            assertEquals(ToolCallMarker.FORBIDDEN, marker)
        }

    @Test
    fun `own package is forbidden with zero profiles`() =
        runTest {
            scriptTap(subjectOn(OWN_PACKAGE))

            val result = tap()

            assertEquals("own_package", jsonOf(result).getValue("reason").jsonPrimitive.content)
            assertEquals(0, executions)
        }

    @Test
    fun `unreadable profile is forbidden`() =
        runTest {
            profiles.value = PolicyFixtures.snapshotOf(unreadable = setOf(BANK_PACKAGE))
            scriptTap()

            assertEquals(forbiddenJson("profile_unreadable"), jsonOf(tap()))
            assertEquals(0, executions)
        }

    @Test
    fun `unreadable store forbids mutating calls`() =
        runTest {
            coEvery { repository.current() } throws ProfileStoreUnreadableException(IOException())
            scriptTap()

            val result = tap()

            assertEquals(true, result.isError)
            assertTrue(textOf(result).startsWith(McpToolUtils.UNTRUSTED_CONTENT_WARNING))
            assertEquals(forbiddenJson("profile_unreadable"), jsonOf(result))
            assertEquals(0, executions)
            assertEquals(setOf(ProfilesView.UNREADABLE_STORE_MARKER), subjects.lastProfiledPackages)
        }

    @Test
    fun `unreadable store keeps the own package reason`() =
        runTest {
            coEvery { repository.current() } throws ProfileStoreUnreadableException(IOException())
            scriptTap(subjectOn(OWN_PACKAGE))

            assertEquals("own_package", jsonOf(tap()).getValue("reason").jsonPrimitive.content)
        }

    @Test
    fun `unreadable store lets reads run`() =
        runTest {
            coEvery { repository.current() } throws ProfileStoreUnreadableException(IOException())
            subjects.script("get_screen_state") {
                SubjectResolution.Resolved(subjectOn(BANK_PACKAGE, PolicyActionType.READ))
            }

            gate.guard("get_screen_state", null, execute)

            assertEquals(1, executions)
            assertTrue(approvalMessages().isEmpty())
        }

    @Test
    fun `store readable again resumes normal decisions`() =
        runTest {
            coEvery { repository.current() } throws ProfileStoreUnreadableException(IOException())
            scriptTap()

            assertEquals(true, tap().isError)

            coEvery { repository.current() } returns ProfilesSnapshot.EMPTY
            subjects.script("tap") { SubjectResolution.Unattributed }
            tap()

            assertEquals(1, executions)
        }

    @Test
    fun `ask in chat returns needs human confirmation`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.ASK_IN_CHAT))
            scriptTap()

            val (result, marker) = guardMarked()

            val json = jsonOf(result)
            assertNotEquals(true, result.isError)
            assertTrue(textOf(result).startsWith(McpToolUtils.UNTRUSTED_CONTENT_WARNING))
            assertEquals(GateResults.STATUS_NEEDS_CONFIRMATION, json.getValue("status").jsonPrimitive.content)
            assertTrue(Regex("^[0-9a-f]{32}$").matches(idOf(result)))
            assertEquals(BANK_PACKAGE, json.getValue("app").jsonPrimitive.content)
            assertEquals("action_button", json.getValue("action").jsonPrimitive.content)
            val expiresAtMs = json.getValue("expires_at_ms").jsonPrimitive.long
            assertEquals(NOW_MS + ActionApprovalPolicy.APPROVAL_WINDOW_MS, expiresAtMs)
            assertEquals(GateResults.NEXT_CONFIRM, json.getValue("next").jsonPrimitive.content)
            assertEquals(PENDING_KEYS, json.keys.toList())
            assertEquals(ToolCallMarker.NEEDS_CONFIRMATION, marker)
            assertEquals(0, executions)
        }

    @Test
    fun `identical ask in chat calls join one approval`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.ASK_IN_CHAT))
            scriptTap()

            val first = idOf(tap())
            val second = idOf(tap())

            assertEquals(first, second)
            assertEquals(1, coordinator.observePending().value.size)
        }

    @Test
    fun `confirmed chat grant runs the repeat exactly once`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.ASK_IN_CHAT))
            scriptTap()
            val id = idOf(tap())

            coordinator.confirmInChat(id, clock.get())
            tap()
            val third = tap()

            assertEquals(1, executions)
            assertEquals(GateResults.STATUS_NEEDS_CONFIRMATION, jsonOf(third).getValue("status").jsonPrimitive.content)
            assertNotEquals(id, idOf(third))
            assertEquals(1, approvalMessages().count { it.startsWith("approved") })
        }

    @Test
    fun `chat grant does not satisfy notify`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.ASK_IN_CHAT))
            scriptTap()
            coordinator.confirmInChat(idOf(tap()), clock.get())
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))

            val result = tap()

            assertEquals(0, executions)
            assertEquals(GateResults.STATUS_AWAITING, jsonOf(result).getValue("status").jsonPrimitive.content)
        }

    @Test
    fun `notify approved within the wait executes once`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTap()
            approveWhenPending()

            tap()

            assertEquals(1, executions)
            val approved = serverLog.ofType(ServerLogEntry.Type.APPROVAL).single { it.message.startsWith("approved") }
            assertTrue((approved.durationMs ?: -1L) >= 0L)
        }

    @Test
    fun `notify still pending after the wait returns awaiting approval`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTap()

            val (result, marker) = guardMarked()

            val json = jsonOf(result)
            assertNotEquals(true, result.isError)
            assertEquals(McpToolUtils.UNTRUSTED_CONTENT_WARNING, textOf(result).lines().first())
            assertEquals(GateResults.STATUS_AWAITING, json.getValue("status").jsonPrimitive.content)
            assertEquals(BANK_PACKAGE, json.getValue("app").jsonPrimitive.content)
            assertEquals("action_button", json.getValue("action").jsonPrimitive.content)
            assertEquals(GateResults.NEXT_POLL, json.getValue("next").jsonPrimitive.content)
            assertEquals(PENDING_KEYS, json.keys.toList())
            assertEquals(ToolCallMarker.AWAITING_APPROVAL, marker)
            assertEquals(0, executions)
        }

    @Test
    fun `notify denied returns denied`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTap()
            launch {
                coordinator.deny(
                    coordinator
                        .observePending()
                        .first { it.isNotEmpty() }
                        .first()
                        .id,
                )
            }

            val (result, marker) = guardMarked()

            assertEquals(true, result.isError)
            assertEquals("""{"status":"denied"}""", textOf(result))
            assertEquals(ToolCallMarker.DENIED, marker)
            assertEquals(0, executions)
        }

    @Test
    fun `notify expired returns expired`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTap()
            launch {
                coordinator.observePending().first { it.isNotEmpty() }
                clock.addAndGet(ActionApprovalPolicy.APPROVAL_WINDOW_MS)
            }

            val (result, marker) = guardMarked()

            assertEquals(true, result.isError)
            assertEquals("""{"status":"expired"}""", textOf(result))
            assertNull(marker)
            assertEquals(0, executions)
            assertTrue(approvalMessages().last().startsWith("expired"))
        }

    @Test
    fun `notify closed by a re-evaluation returns not required`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTap()
            launch {
                val pending = coordinator.observePending().first { it.isNotEmpty() }.first()
                coordinator.applyReevaluation(pending.id, pending.decision.copy(mode = RuleMode.RUN), clock.get())
            }

            val (result, marker) = guardMarked()

            assertEquals(true, result.isError)
            assertEquals("""{"status":"not_required"}""", textOf(result))
            assertNull(marker)
            assertEquals(0, executions)
            assertTrue(approvalMessages().last().startsWith("not_required"))
        }

    @Test
    fun `call repeated after not required runs without approval`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTap()
            launch {
                val pending = coordinator.observePending().first { it.isNotEmpty() }.first()
                useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.RUN))
                coordinator.applyReevaluation(pending.id, pending.decision.copy(mode = RuleMode.RUN), clock.get())
            }
            tap()

            val repeat = tap()

            assertEquals("ok", textOf(repeat))
            assertEquals(1, executions)
        }

    @Test
    fun `joined notify call whose grant was used returns already used`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTap()
            val first = async { tap() }
            val second = async { tap() }
            runCurrent()

            coordinator.approve(
                coordinator
                    .observePending()
                    .value
                    .single()
                    .id,
                clock.get(),
            )
            val results = listOf(first.await(), second.await())

            assertEquals(1, executions)
            assertEquals(1, results.count { textOf(it) == "ok" })
            assertEquals(1, results.count { textOf(it) == """{"status":"already_used"}""" })
            assertEquals(1, approvalMessages().count { it.startsWith("already_used") })
            assertTrue(approvalMessages().none { it.startsWith("expired") })
        }

    @Test
    fun `notify call whose grant an identical call used while it waited returns already used`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTap()
            val other =
                async {
                    val pending = coordinator.observePending().first { it.isNotEmpty() }.first()
                    coordinator.approve(pending.id, clock.get())
                    tap()
                }

            val (result, marker) = guardMarked()

            assertEquals("ok", textOf(other.await()))
            assertEquals("""{"status":"already_used"}""", textOf(result))
            assertNull(marker)
            assertEquals(1, executions)
        }

    @Test
    fun `ask in chat joining a notify entry returns awaiting approval`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTap()
            val notifyId = idOf(tap())
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.ASK_IN_CHAT))

            val result = tap()

            assertEquals(GateResults.STATUS_AWAITING, jsonOf(result).getValue("status").jsonPrimitive.content)
            assertEquals(notifyId, idOf(result))
        }

    @Test
    fun `too many pending returns too many pending`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.ASK_IN_CHAT))
            scriptTap()
            repeat(ActionApprovalPolicy.MAX_PENDING) { tap(args(it)) }

            val result = tap(args(ActionApprovalPolicy.MAX_PENDING))

            assertEquals(true, result.isError)
            assertEquals("""{"status":"too_many_pending"}""", textOf(result))
            assertEquals(0, executions)
        }
}
