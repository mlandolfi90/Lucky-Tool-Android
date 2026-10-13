package com.danielealbano.androidremotecontrolmcp.policy.gate

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ApprovalProfileRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileStoreUnreadableException
import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallMarker
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallMarkerSlot
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.BANK_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.NOTES_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.NOW_MS
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.OWN_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalCoordinatorImpl
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalPolicy
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicySubject
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalEventLoggerImpl
import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfile
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.policy.subject.BlockCause
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectResolution
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import com.danielealbano.androidremotecontrolmcp.testutil.ScriptedToolSubjectResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

@OptIn(ExperimentalCoroutinesApi::class)
class ToolPolicyGateImplTest {
    private val subjects = ScriptedToolSubjectResolver()
    private val profiles = MutableStateFlow(ProfilesSnapshot.EMPTY)
    private val repository =
        mockk<ApprovalProfileRepository>().also {
            every { it.profiles } returns profiles
            coEvery { it.current() } answers { profiles.value }
        }
    private val coordinator = ActionApprovalCoordinatorImpl()
    private val clock = AtomicLong(NOW_MS)
    private val serverLog = RecordingServerLogRepository()
    private val gate =
        ToolPolicyGateImpl(
            subjects,
            PolicyFixtures.engine(),
            coordinator,
            repository,
            ApprovalEventLoggerImpl(serverLog),
            GateTiming(NOTIFY_WAIT_MS) { clock.get() },
        )
    private val paySelector = PolicyFixtures.resourceId(PAY_RESOURCE_ID)
    private val paySubject =
        PolicyFixtures.subject(
            PolicyActionType.ACTION_BUTTON,
            listOf(PolicyFixtures.target(label = "Pay", selector = paySelector)),
        )
    private var executions = 0
    private val execute: suspend () -> CallToolResult = {
        executions++
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    private fun subjectOn(
        packageName: String,
        actionType: PolicyActionType = PolicyActionType.ACTION_BUTTON,
    ): PolicySubject =
        PolicyFixtures.subject(
            actionType,
            listOf(PolicyFixtures.target(packageName = packageName, label = "Pay", selector = paySelector)),
        )

    private fun bank(vararg rules: Pair<PolicyActionType, RuleMode>): ApprovalProfile =
        PolicyFixtures.moneyProfile().let { it.copy(rules = it.rules + rules) }

    private fun useProfiles(vararg approvalProfiles: ApprovalProfile) {
        profiles.value = PolicyFixtures.snapshotOf(*approvalProfiles)
    }

    private fun scriptTap(subject: PolicySubject = paySubject) {
        subjects.script("tap") { SubjectResolution.Resolved(subject) }
    }

    private fun args(x: Int = 1): JsonObject = buildJsonObject { put("x", x) }

    private suspend fun tap(
        arguments: JsonObject = args(),
        body: suspend () -> CallToolResult = execute,
    ): CallToolResult = gate.guard("tap", arguments, body)

    private suspend fun guardMarked(
        toolName: String = "tap",
        arguments: JsonObject = args(),
    ): Pair<CallToolResult, String?> {
        val slot = ToolCallMarkerSlot()
        val result = withContext(slot) { gate.guard(toolName, arguments, execute) }
        return result to slot.marker
    }

    private fun textOf(result: CallToolResult): String = (result.content.single() as TextContent).text

    private fun jsonOf(result: CallToolResult): JsonObject =
        Json.parseToJsonElement(textOf(result).lines().last()).jsonObject

    private fun approvalMessages(): List<String> = serverLog.ofType(ServerLogEntry.Type.APPROVAL).map { it.message }

    private fun forbiddenJson(reason: String): JsonObject =
        buildJsonObject {
            put("status", "forbidden")
            put("app", BANK_PACKAGE)
            put("action", "action_button")
            put("reason", reason)
        }

    private fun TestScope.approveWhenPending() =
        launch {
            val pending = coordinator.observePending().first { it.isNotEmpty() }.first()
            coordinator.approve(pending.id, clock.get())
        }

    private fun idOf(result: CallToolResult): String = jsonOf(result).getValue("approval_id").jsonPrimitive.content

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
            launch { coordinator.deny(coordinator.observePending().first { it.isNotEmpty() }.first().id) }

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
        }

    @Test
    fun `joined notify call whose grant was used returns expired`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTap()
            val first = async { tap() }
            val second = async { tap() }
            runCurrent()

            coordinator.approve(coordinator.observePending().value.single().id, clock.get())
            val results = listOf(first.await(), second.await())

            assertEquals(1, executions)
            assertEquals(1, results.count { textOf(it) == "ok" })
            assertEquals(1, results.count { textOf(it) == """{"status":"expired"}""" })
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

    private companion object {
        const val NOTIFY_WAIT_MS = 1_000L
        const val PAY_RESOURCE_ID = "com.example.bank:id/pay"
        val PENDING_KEYS = listOf("status", "approval_id", "app", "action", "expires_at_ms", "next")
    }
}
