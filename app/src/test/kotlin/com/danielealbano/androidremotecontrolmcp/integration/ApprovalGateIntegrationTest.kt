package com.danielealbano.androidremotecontrolmcp.integration

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.mcp.tools.McpToolUtils
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallMarker
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.BANK_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalPolicy
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicySubject
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogMessage
import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfile
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.policy.subject.BlockCause
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectResolution
import io.mockk.coEvery
import io.mockk.coVerify
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ApprovalGateIntegrationTest {
    private val paySubject: PolicySubject =
        PolicyFixtures.subject(
            PolicyActionType.ACTION_BUTTON,
            listOf(PolicyFixtures.target(label = "Pay", selector = PolicyFixtures.resourceId(PAY_RESOURCE_ID))),
        )

    @BeforeEach
    fun setUp() {
        McpIntegrationTestHelper.mockAndroidLog()
    }

    @AfterEach
    fun tearDown() {
        McpIntegrationTestHelper.unmockAndroidLog()
    }

    private fun bank(vararg rules: Pair<PolicyActionType, RuleMode>): ApprovalProfile =
        PolicyFixtures.moneyProfile().let { it.copy(rules = it.rules + rules) }

    private fun dependencies(
        actionButtonMode: RuleMode? = null,
        notifyWaitMs: Long = PolicyTestRig.DEFAULT_NOTIFY_WAIT_MS,
    ): MockDependencies {
        val deps = McpIntegrationTestHelper.createMockDependencies(notifyWaitMs)
        coEvery { deps.actionExecutor.tap(500f, 800f) } returns Result.success(Unit)
        deps.policy.subjects.script("tap") { SubjectResolution.Resolved(paySubject) }
        actionButtonMode?.let { deps.policy.setProfiles(bank(PolicyActionType.ACTION_BUTTON to it)) }
        return deps
    }

    private suspend fun Client.tap(): CallToolResult =
        callTool(name = "android_tap", arguments = mapOf("x" to 500, "y" to 800))

    private suspend fun Client.status(
        approvalId: String,
        waitMs: Int = 0,
    ): CallToolResult =
        callTool(
            name = "android_get_approval_status",
            arguments = mapOf("approval_id" to approvalId, "wait_ms" to waitMs),
        )

    private suspend fun Client.confirm(approvalId: String): CallToolResult =
        callTool(name = "android_confirm_human_approval", arguments = mapOf("approval_id" to approvalId))

    private fun textOf(result: CallToolResult): String = (result.content[0] as TextContent).text

    private fun jsonOf(result: CallToolResult): JsonObject =
        Json.parseToJsonElement(textOf(result).lines().last()).jsonObject

    private fun field(
        result: CallToolResult,
        key: String,
    ): String? = jsonOf(result)[key]?.jsonPrimitive?.content

    private fun toolCallMessages(deps: MockDependencies): List<String> =
        deps.serverLog.ofType(ServerLogEntry.Type.TOOL_CALL).filter { it.toolName == "tap" }.map { it.message }

    private fun approvalMessages(deps: MockDependencies): List<String> =
        deps.serverLog.ofType(ServerLogEntry.Type.APPROVAL).map { it.message }

    @Test
    fun `unprofiled app behaves as today`() =
        runTest {
            val deps = dependencies()
            deps.policy.setProfiles(PolicyFixtures.commonProfile())
            deps.policy.subjects.script("tap") {
                SubjectResolution.Resolved(
                    PolicyFixtures.subject(
                        PolicyActionType.ACTION_BUTTON,
                        listOf(PolicyFixtures.target(packageName = PolicyFixtures.UNPROFILED_PACKAGE)),
                    ),
                )
            }

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                assertNotEquals(true, client.tap().isError)
            }

            coVerify(exactly = 1) { deps.actionExecutor.tap(500f, 800f) }
            assertTrue(approvalMessages(deps).isEmpty())
            assertEquals(listOf(""), toolCallMessages(deps))
        }

    @Test
    fun `run rule lets the tap execute`() =
        runTest {
            val deps = dependencies(RuleMode.RUN)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ -> client.tap() }

            coVerify(exactly = 1) { deps.actionExecutor.tap(500f, 800f) }
        }

    @Test
    fun `forbidden rule blocks the tap`() =
        runTest {
            val deps = dependencies(RuleMode.FORBIDDEN)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val result = client.tap()

                assertEquals(true, result.isError)
                assertTrue(textOf(result).startsWith(McpToolUtils.UNTRUSTED_CONTENT_WARNING))
                assertEquals("forbidden", field(result, "status"))
                assertEquals("rule", field(result, "reason"))
            }

            coVerify(exactly = 0) { deps.actionExecutor.tap(any(), any()) }
            assertEquals(listOf(ToolCallMarker.FORBIDDEN), toolCallMessages(deps))
        }

    @Test
    fun `blocked resolution blocks the tap`() =
        runTest {
            val deps = dependencies(RuleMode.RUN)
            deps.policy.subjects.script("tap") {
                SubjectResolution.Blocked(
                    BANK_PACKAGE,
                    PolicyActionType.ACTION_BUTTON,
                    BlockCause.IME_INPUT_IN_PROFILED_APP,
                )
            }

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                assertEquals("ime_input_in_profiled_app", field(client.tap(), "reason"))
            }

            coVerify(exactly = 0) { deps.actionExecutor.tap(any(), any()) }
        }

    @Test
    fun `own package is forbidden with zero profiles`() =
        runTest {
            val deps = dependencies()
            deps.policy.subjects.script("tap") {
                SubjectResolution.Resolved(
                    PolicyFixtures.subject(
                        PolicyActionType.ACTION_BUTTON,
                        listOf(PolicyFixtures.target(packageName = PolicyFixtures.OWN_PACKAGE)),
                    ),
                )
            }

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                assertEquals("own_package", field(client.tap(), "reason"))
            }

            coVerify(exactly = 0) { deps.actionExecutor.tap(any(), any()) }
        }

    @Test
    fun `ask in chat then confirm then repeat executes once`() =
        runTest {
            val deps = dependencies(RuleMode.ASK_IN_CHAT)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val first = client.tap()
                assertEquals("needs_human_confirmation", field(first, "status"))
                val id = checkNotNull(field(first, "approval_id"))

                val confirmed = client.confirm(id)
                assertEquals("approved", field(confirmed, "status"))
                assertEquals(id, field(confirmed, "approval_id"))

                assertNotEquals(true, client.tap().isError)
                val again = client.tap()
                assertEquals("needs_human_confirmation", field(again, "status"))
                assertNotEquals(id, field(again, "approval_id"))
            }

            coVerify(exactly = 1) { deps.actionExecutor.tap(500f, 800f) }
        }

    @Test
    fun `notify approved within the wait executes`() =
        runTest {
            val deps = dependencies(RuleMode.NOTIFY)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                coroutineScope {
                    val call = async { client.tap() }
                    deps.policy.coordinator.approve(deps.policy.awaitPending().id, deps.policy.nowMs())
                    assertNotEquals(true, call.await().isError)
                }
            }

            coVerify(exactly = 1) { deps.actionExecutor.tap(500f, 800f) }
            assertTrue(approvalMessages(deps).any { it.startsWith("approved") })
        }

    @Test
    fun `notify timeout then approve then repeat executes`() =
        runTest {
            val deps = dependencies(RuleMode.NOTIFY, notifyWaitMs = SHORT_WAIT_MS)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val first = client.tap()
                assertNotEquals(true, first.isError)
                assertTrue(textOf(first).startsWith(McpToolUtils.UNTRUSTED_CONTENT_WARNING))
                assertEquals("awaiting_approval", field(first, "status"))
                assertEquals(BANK_PACKAGE, field(first, "app"))
                val id = checkNotNull(field(first, "approval_id"))

                val pending = client.status(id)
                assertEquals("pending", field(pending, "status"))
                assertNotNull(field(pending, "expires_at_ms"))

                deps.policy.coordinator.approve(id, deps.policy.nowMs())
                assertEquals("approved", field(client.status(id), "status"))

                assertNotEquals(true, client.tap().isError)
            }

            coVerify(exactly = 1) { deps.actionExecutor.tap(500f, 800f) }
        }

    @Test
    fun `get approval status long poll sees the approval`() =
        runTest {
            val deps = dependencies(RuleMode.NOTIFY, notifyWaitMs = SHORT_WAIT_MS)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val id = checkNotNull(field(client.tap(), "approval_id"))
                coroutineScope {
                    val poll = async { client.status(id, waitMs = LONG_POLL_MS) }
                    deps.policy.coordinator.approve(id, deps.policy.nowMs())
                    assertEquals("approved", field(poll.await(), "status"))
                }
            }
        }

    @Test
    fun `notify denied`() =
        runTest {
            val deps = dependencies(RuleMode.NOTIFY)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                coroutineScope {
                    val call = async { client.tap() }
                    deps.policy.coordinator.deny(deps.policy.awaitPending().id)
                    val result = call.await()
                    assertEquals(true, result.isError)
                    assertEquals("""{"status":"denied"}""", textOf(result))
                }
            }

            assertEquals(listOf(ToolCallMarker.DENIED), toolCallMessages(deps))
        }

    @Test
    fun `expired confirmation`() =
        runTest {
            val deps = dependencies(RuleMode.ASK_IN_CHAT)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val id = checkNotNull(field(client.tap(), "approval_id"))
                deps.policy.advanceClock(ActionApprovalPolicy.APPROVAL_WINDOW_MS)

                val confirmed = client.confirm(id)
                assertEquals(true, confirmed.isError)
                assertEquals("""{"status":"expired"}""", textOf(confirmed))

                val repeat = client.tap()
                assertNotEquals(id, field(repeat, "approval_id"))
            }

            coVerify(exactly = 0) { deps.actionExecutor.tap(any(), any()) }
        }

    @Test
    fun `confirm refuses notify requests`() =
        runTest {
            val deps = dependencies(RuleMode.NOTIFY, notifyWaitMs = SHORT_WAIT_MS)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                val id = checkNotNull(field(client.tap(), "approval_id"))

                val confirmed = client.confirm(id)
                assertEquals(true, confirmed.isError)
                assertEquals("""{"status":"not_confirmable"}""", textOf(confirmed))
            }

            coVerify(exactly = 0) { deps.actionExecutor.tap(any(), any()) }
        }

    @Test
    fun `unknown approval id reads expired`() =
        runTest {
            val unknown = "0".repeat(32)

            McpIntegrationTestHelper.withTestApplication(dependencies()) { client, _ ->
                val result = client.status(unknown)
                assertNotEquals(true, result.isError)
                assertEquals("expired", field(result, "status"))
            }
        }

    @Test
    fun `approval tools are not gated`() =
        runTest {
            val deps = dependencies()
            deps.policy.setProfiles(bank(*PolicyActionType.entries.map { it to RuleMode.FORBIDDEN }.toTypedArray()))
            val unknown = "0".repeat(32)

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                assertEquals("expired", field(client.status(unknown), "status"))
                assertEquals("""{"status":"expired"}""", textOf(client.confirm(unknown)))
            }

            assertTrue(approvalMessages(deps).isEmpty())
        }

    @Test
    fun `TOOL_CALL markers and APPROVAL codes only`() =
        runTest {
            val deps = dependencies(RuleMode.FORBIDDEN, notifyWaitMs = SHORT_WAIT_MS)
            val ids = mutableListOf<String>()

            McpIntegrationTestHelper.withTestApplication(deps) { client, _ ->
                client.tap()
                deps.policy.setProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.ASK_IN_CHAT))
                ids += checkNotNull(field(client.tap(), "approval_id"))
                deps.policy.advanceClock(ActionApprovalPolicy.APPROVAL_WINDOW_MS)
                deps.policy.setProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
                ids += checkNotNull(field(client.tap(), "approval_id"))
                deps.policy.advanceClock(ActionApprovalPolicy.APPROVAL_WINDOW_MS)
                // Lapse the waiting NOTIFY entry so the next pending entry is the new call's.
                deps.policy.coordinator.stateOf(ids.last(), deps.policy.nowMs())
                coroutineScope {
                    val call = async { client.tap() }
                    deps.policy.coordinator.deny(deps.policy.awaitPending().id.also { ids += it })
                    call.await()
                }
            }

            assertEquals(
                listOf(
                    ToolCallMarker.FORBIDDEN,
                    ToolCallMarker.NEEDS_CONFIRMATION,
                    ToolCallMarker.AWAITING_APPROVAL,
                    ToolCallMarker.DENIED,
                ),
                toolCallMessages(deps),
            )
            val messages = approvalMessages(deps)
            assertTrue(messages.isNotEmpty())
            messages.forEach { message ->
                assertNotNull(ApprovalLogMessage.decode(message), message)
                (ids + listOf("500", "800", "Pay")).forEach { assertFalse(message.contains(it), "$it in $message") }
            }
        }

    private companion object {
        const val PAY_RESOURCE_ID = "com.example.bank:id/pay"
        const val SHORT_WAIT_MS = 200L
        const val LONG_POLL_MS = 5_000
    }
}
