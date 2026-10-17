package com.danielealbano.androidremotecontrolmcp.policy.gate

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ApprovalProfileRepository
import com.danielealbano.androidremotecontrolmcp.mcp.tools.ToolCallMarkerSlot
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.BANK_PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures.NOW_MS
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalCoordinatorImpl
import com.danielealbano.androidremotecontrolmcp.policy.approval.ApprovalScreenVisibilityImpl
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicySubject
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalEventLoggerImpl
import com.danielealbano.androidremotecontrolmcp.policy.model.ApprovalProfile
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.policy.subject.SubjectResolution
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import com.danielealbano.androidremotecontrolmcp.testutil.ScriptedToolSubjectResolver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

/** Shared wiring of the profile-gate tests: real engine and coordinator, scripted subjects, fake store. */
@OptIn(ExperimentalCoroutinesApi::class)
open class ToolPolicyGateTestBase {
    protected val subjects = ScriptedToolSubjectResolver()
    protected val profiles = MutableStateFlow(ProfilesSnapshot.EMPTY)
    protected val repository =
        mockk<ApprovalProfileRepository>().also {
            every { it.profiles } returns profiles
            coEvery { it.current() } answers { profiles.value }
        }
    protected val coordinator = ActionApprovalCoordinatorImpl()
    protected val clock = AtomicLong(NOW_MS)
    protected val serverLog = RecordingServerLogRepository()
    protected val screen = ApprovalScreenVisibilityImpl()
    protected val gate =
        ToolPolicyGateImpl(
            subjects,
            PolicyFixtures.engine(),
            coordinator,
            repository,
            ApprovalEventLoggerImpl(serverLog),
            GateTiming(NOTIFY_WAIT_MS) { clock.get() },
            screen,
        )
    protected val paySelector = PolicyFixtures.resourceId(PAY_RESOURCE_ID)
    protected val paySubject =
        PolicyFixtures.subject(
            PolicyActionType.ACTION_BUTTON,
            listOf(PolicyFixtures.target(label = "Pay", selector = paySelector)),
        )
    protected var executions = 0
    protected val execute: suspend () -> CallToolResult = {
        executions++
        CallToolResult(content = listOf(TextContent(text = "ok")))
    }

    protected fun subjectOn(
        packageName: String,
        actionType: PolicyActionType = PolicyActionType.ACTION_BUTTON,
    ): PolicySubject =
        PolicyFixtures.subject(
            actionType,
            listOf(PolicyFixtures.target(packageName = packageName, label = "Pay", selector = paySelector)),
        )

    protected fun bank(vararg rules: Pair<PolicyActionType, RuleMode>): ApprovalProfile =
        PolicyFixtures.moneyProfile().let { it.copy(rules = it.rules + rules) }

    protected fun useProfiles(vararg approvalProfiles: ApprovalProfile) {
        profiles.value = PolicyFixtures.snapshotOf(*approvalProfiles)
    }

    protected fun scriptTap(subject: PolicySubject = paySubject) {
        subjects.script("tap") { SubjectResolution.Resolved(subject) }
    }

    protected fun args(x: Int = 1): JsonObject = buildJsonObject { put("x", x) }

    protected suspend fun tap(
        arguments: JsonObject = args(),
        body: suspend () -> CallToolResult = execute,
    ): CallToolResult = gate.guard("tap", arguments, body)

    protected suspend fun guardMarked(
        toolName: String = "tap",
        arguments: JsonObject = args(),
    ): Pair<CallToolResult, String?> {
        val slot = ToolCallMarkerSlot()
        val result = withContext(slot) { gate.guard(toolName, arguments, execute) }
        return result to slot.marker
    }

    protected fun textOf(result: CallToolResult): String = (result.content.single() as TextContent).text

    protected fun jsonOf(result: CallToolResult): JsonObject {
        val json = textOf(result).lines().last()
        return Json.parseToJsonElement(json).jsonObject
    }

    protected fun approvalMessages(): List<String> = serverLog.ofType(ServerLogEntry.Type.APPROVAL).map { it.message }

    protected fun forbiddenJson(reason: String): JsonObject =
        buildJsonObject {
            put("status", "forbidden")
            put("app", BANK_PACKAGE)
            put("action", "action_button")
            put("reason", reason)
        }

    protected fun TestScope.approveWhenPending() =
        launch {
            val pending = coordinator.observePending().first { it.isNotEmpty() }.first()
            coordinator.approve(pending.id, clock.get())
        }

    protected fun idOf(result: CallToolResult): String = jsonOf(result).getValue("approval_id").jsonPrimitive.content

    protected companion object {
        const val NOTIFY_WAIT_MS = 1_000L
        const val PAY_RESOURCE_ID = "com.example.bank:id/pay"
        val PENDING_KEYS = listOf("status", "approval_id", "app", "action", "expires_at_ms", "next")
    }
}
