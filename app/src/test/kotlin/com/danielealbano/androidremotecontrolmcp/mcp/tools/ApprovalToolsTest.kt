package com.danielealbano.androidremotecontrolmcp.mcp.tools

import com.danielealbano.androidremotecontrolmcp.mcp.McpToolException
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalCoordinatorImpl
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalPolicy
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalState
import com.danielealbano.androidremotecontrolmcp.policy.approval.ApprovalStatusView
import com.danielealbano.androidremotecontrolmcp.policy.approval.ChatConfirmResult
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.testutil.PolicyTestDoubles
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalToolsTest {
    private val coordinator = mockk<ActionApprovalCoordinator>()
    private val nowMs = { NOW_MS }
    private val statusTool = GetApprovalStatusTool(coordinator, nowMs)
    private val confirmTool = ConfirmHumanApprovalTool(coordinator, nowMs)

    init {
        coEvery { coordinator.statusOf(ID, any()) } returns
            ApprovalStatusView(ActionApprovalState.PENDING, EXPIRES_AT_MS)
        coEvery { coordinator.awaitDecision(any(), any()) } returns ActionApprovalState.PENDING
    }

    private fun args(
        approvalId: String? = ID,
        waitMs: Long? = null,
    ): JsonObject =
        buildJsonObject {
            approvalId?.let { put("approval_id", it) }
            waitMs?.let { put("wait_ms", it) }
        }

    private fun textOf(result: CallToolResult): String = (result.content.single() as TextContent).text

    @Test
    fun `status pending includes expiry`() =
        runTest {
            val result = statusTool.execute(args())

            assertEquals("""{"approval_id":"id-1","status":"pending","expires_at_ms":5000}""", textOf(result))
            assertNotEquals(true, result.isError)
            assertFalse(textOf(result).startsWith(McpToolUtils.UNTRUSTED_CONTENT_WARNING))
            verify(exactly = 0) { coordinator.observePending() }
            coVerify(exactly = 0) { coordinator.stateOf(any(), any()) }
        }

    @Test
    fun `every status other than pending omits expiry`() =
        runTest {
            ActionApprovalState.entries.filter { it != ActionApprovalState.PENDING }.forEach { state ->
                listOf(null, EXPIRES_AT_MS).forEach { expiry ->
                    coEvery { coordinator.statusOf(ID, any()) } returns ApprovalStatusView(state, expiry)

                    val result = statusTool.execute(args())

                    val status = state.name.lowercase()
                    assertEquals("""{"approval_id":"id-1","status":"$status"}""", textOf(result))
                    assertNotEquals(true, result.isError)
                }
            }
        }

    @Test
    fun `wait_ms long-polls then reads the status again`() =
        runTest {
            coEvery { coordinator.statusOf(ID, any()) } returnsMany
                listOf(
                    ApprovalStatusView(ActionApprovalState.PENDING, EXPIRES_AT_MS),
                    ApprovalStatusView(ActionApprovalState.APPROVED, null),
                )

            val result = statusTool.execute(args(waitMs = 3_000L))

            coVerifyOrder {
                coordinator.statusOf(ID, NOW_MS)
                coordinator.awaitDecision(ID, 3_000L)
                coordinator.statusOf(ID, NOW_MS)
            }
            assertEquals("""{"approval_id":"id-1","status":"approved"}""", textOf(result))
        }

    @Test
    fun `wait_ms is capped at the request's expiry`() =
        runTest {
            statusTool.execute(args(waitMs = 25_000L))

            coVerify { coordinator.awaitDecision(ID, EXPIRES_AT_MS - NOW_MS) }
        }

    @Test
    fun `wait_ms does not wait for a request that is not pending`() =
        runTest {
            coEvery { coordinator.statusOf(ID, any()) } returns ApprovalStatusView(ActionApprovalState.APPROVED, null)
            statusTool.execute(args(waitMs = 3_000L))
            coEvery { coordinator.statusOf(ID, any()) } returns ApprovalStatusView(ActionApprovalState.PENDING, NOW_MS)
            statusTool.execute(args(waitMs = 3_000L))

            coVerify(exactly = 0) { coordinator.awaitDecision(any(), any()) }
            coVerify(exactly = 2) { coordinator.statusOf(ID, any()) }
        }

    @Test
    fun `wait_ms zero does not wait`() =
        runTest {
            statusTool.execute(args(waitMs = 0L))

            coVerify(exactly = 0) { coordinator.awaitDecision(any(), any()) }
            coVerify(exactly = 1) { coordinator.statusOf(ID, any()) }
        }

    @Test
    fun `wait_ms out of range is invalid`() =
        runTest {
            listOf(-1L, 25_001L).forEach { waitMs ->
                assertThrows<McpToolException.InvalidParams> { statusTool.execute(args(waitMs = waitMs)) }
            }
        }

    @Test
    fun `missing or blank approval id is invalid`() =
        runTest {
            listOf(args(approvalId = null), args(approvalId = ""), args(approvalId = "  ")).forEach { arguments ->
                assertThrows<McpToolException.InvalidParams> { statusTool.execute(arguments) }
                assertThrows<McpToolException.InvalidParams> { confirmTool.execute(arguments) }
            }
        }

    @Test
    fun `confirm maps chat results`() =
        runTest {
            listOf(ChatConfirmResult.CONFIRMED, ChatConfirmResult.ALREADY_APPROVED).forEach { chat ->
                coEvery { coordinator.confirmInChat(ID, NOW_MS) } returns chat

                val result = confirmTool.execute(args())

                assertEquals("""{"approval_id":"id-1","status":"approved"}""", textOf(result))
                assertNotEquals(true, result.isError)
            }
            mapOf(
                ChatConfirmResult.NOT_CONFIRMABLE to """{"status":"not_confirmable"}""",
                ChatConfirmResult.DENIED to """{"status":"denied"}""",
                ChatConfirmResult.EXPIRED to """{"status":"expired"}""",
                ChatConfirmResult.NOT_REQUIRED to """{"status":"not_required"}""",
                ChatConfirmResult.ALREADY_USED to """{"status":"already_used"}""",
            ).forEach { (chat, expected) ->
                coEvery { coordinator.confirmInChat(ID, NOW_MS) } returns chat

                val result = confirmTool.execute(args())

                assertEquals(expected, textOf(result))
                assertEquals(true, result.isError)
            }
        }

    @Test
    fun `registerApprovalTools registers both tools`() {
        val server = mockk<Server>(relaxed = true)
        val registrar =
            LoggedToolRegistrar(server, RecordingServerLogRepository(), PolicyTestDoubles.passthroughGate())

        registerApprovalTools(registrar, coordinator, nowMs, "android_")

        listOf("android_get_approval_status", "android_confirm_human_approval").forEach { name ->
            verify { server.addTool(name = name, description = any(), inputSchema = any(), handler = any()) }
        }
    }

    @Test
    fun `long poll returns expired when the request lapses during the wait`() =
        runTest {
            val realCoordinator = ActionApprovalCoordinatorImpl()
            val tool = GetApprovalStatusTool(realCoordinator) { testScheduler.currentTime }
            val pending = realCoordinator.createOrJoin(PendingActionFixtures.aRequest(), 0L)
            advanceTimeBy(ActionApprovalPolicy.APPROVAL_WINDOW_MS - 1_000L)

            val result = tool.execute(args(approvalId = pending.id, waitMs = 25_000L))

            assertEquals("""{"approval_id":"${pending.id}","status":"expired"}""", textOf(result))
            assertEquals(ActionApprovalPolicy.APPROVAL_WINDOW_MS, testScheduler.currentTime)
        }

    @Test
    fun `status and confirm read not required after a re-evaluation to run`() =
        runTest {
            val realCoordinator = ActionApprovalCoordinatorImpl()
            val request = PendingActionFixtures.aRequest(method = RuleMode.ASK_IN_CHAT)
            val pending = realCoordinator.createOrJoin(request, NOW_MS)
            realCoordinator.applyReevaluation(pending.id, PendingActionFixtures.aDecision(mode = RuleMode.RUN), NOW_MS)

            val status = GetApprovalStatusTool(realCoordinator, nowMs).execute(args(approvalId = pending.id))
            val confirmed = ConfirmHumanApprovalTool(realCoordinator, nowMs).execute(args(approvalId = pending.id))

            assertEquals("""{"approval_id":"${pending.id}","status":"not_required"}""", textOf(status))
            assertNotEquals(true, status.isError)
            assertEquals("""{"status":"not_required"}""", textOf(confirmed))
            assertEquals(true, confirmed.isError)
        }

    @Test
    fun `status and confirm read already used once the grant is consumed`() =
        runTest {
            val realCoordinator = ActionApprovalCoordinatorImpl()
            val request = PendingActionFixtures.aRequest(fingerprint = FINGERPRINT, method = RuleMode.ASK_IN_CHAT)
            val pending = realCoordinator.createOrJoin(request, NOW_MS)
            val confirmTool = ConfirmHumanApprovalTool(realCoordinator, nowMs)
            confirmTool.execute(args(approvalId = pending.id))
            realCoordinator.consumeGrant(FINGERPRINT, RuleMode.ASK_IN_CHAT, NOW_MS)

            val status = GetApprovalStatusTool(realCoordinator, nowMs).execute(args(approvalId = pending.id))
            val confirmed = confirmTool.execute(args(approvalId = pending.id))

            assertEquals("""{"approval_id":"${pending.id}","status":"already_used"}""", textOf(status))
            assertNotEquals(true, status.isError)
            assertEquals("""{"status":"already_used"}""", textOf(confirmed))
            assertEquals(true, confirmed.isError)
        }

    private companion object {
        const val ID = "id-1"
        const val NOW_MS = 1_000L
        const val EXPIRES_AT_MS = 5_000L
        const val FINGERPRINT = "fp-used"
    }
}
