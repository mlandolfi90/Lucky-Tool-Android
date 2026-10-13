package com.danielealbano.androidremotecontrolmcp.policy.gate

import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolPolicyGateScreenTest : ToolPolicyGateTestBase() {
    @Test
    fun `approved notify runs once the approval screen has closed`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTap()
            screen.onShown()
            approveWhenPending()
            launch {
                delay(SCREEN_CLOSES_AFTER_MS)
                screen.onHidden()
            }

            tap()

            assertEquals(1, executions)
        }

    @Test
    fun `approved notify while the approval screen stays open returns awaiting and keeps the grant`() =
        runTest {
            useProfiles(bank(PolicyActionType.ACTION_BUTTON to RuleMode.NOTIFY))
            scriptTap()
            screen.onShown()
            approveWhenPending()

            val first = tap()

            assertEquals(GateResults.STATUS_AWAITING, jsonOf(first).getValue("status").jsonPrimitive.content)
            assertEquals(0, executions)

            screen.onHidden()
            tap()

            assertEquals(1, executions)
        }

    private companion object {
        const val SCREEN_CLOSES_AFTER_MS = 500L
    }
}
