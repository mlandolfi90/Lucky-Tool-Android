package com.danielealbano.androidremotecontrolmcp.policy.gate

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ApprovalProfileRepository
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileSaveResult
import com.danielealbano.androidremotecontrolmcp.data.repository.ProfileStoreUnreadableException
import com.danielealbano.androidremotecontrolmcp.policy.PolicyFixtures
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.policy.approval.ExceptionReevaluation
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingAction
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures
import com.danielealbano.androidremotecontrolmcp.policy.approval.ReevaluationOutcome
import com.danielealbano.androidremotecontrolmcp.policy.engine.DecisionReason
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyDecision
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyEngine
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalEventLoggerImpl
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileValidationError
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class PendingReevaluatorImplTest {
    private val coordinator = mockk<ActionApprovalCoordinator>()
    private val repository = mockk<ApprovalProfileRepository>()
    private val engine = mockk<PolicyEngine>()
    private val serverLog = RecordingServerLogRepository()
    private val reevaluator =
        PendingReevaluatorImpl(coordinator, engine, repository, ApprovalEventLoggerImpl(serverLog)) {
            PolicyFixtures.NOW_MS
        }
    private val snapshot = PolicyFixtures.snapshotOf(PolicyFixtures.moneyProfile())
    private val freshDecision =
        PolicyDecision(RuleMode.ASK_IN_CHAT, DecisionReason.RULE, PendingActionFixtures.PACKAGE, PolicyActionType.TYPE)
    private val saved = ProfileSaveResult.Saved(PolicyFixtures.moneyProfile(), created = false)

    init {
        coEvery { repository.current() } returns snapshot
        every { engine.evaluate(any(), any()) } returns freshDecision
    }

    private fun pendingWith(reason: DecisionReason): PendingAction {
        val action =
            PendingActionFixtures.aPendingAction(decision = PendingActionFixtures.aDecision(reason = reason))
        every { coordinator.observePending() } returns MutableStateFlow(listOf(action))
        return action
    }

    private fun assertNeverApproves() {
        coVerify(exactly = 0) { coordinator.approve(any(), any()) }
        coVerify(exactly = 0) { coordinator.confirmInChat(any(), any()) }
    }

    @Test
    fun `unknown id is not pending`() =
        runTest {
            pendingWith(DecisionReason.AMOUNT_TIER)

            val result = reevaluator.addExceptionAndReevaluate("unknown", PolicyExceptionKind.NOT_AMOUNT_FIELD)

            assertEquals(ExceptionReevaluation(false, ReevaluationOutcome.NOT_PENDING), result)
            coVerify(exactly = 0) { repository.addException(any(), any(), any()) }
            coVerify(exactly = 0) { repository.current() }
            verify(exactly = 0) { engine.evaluate(any(), any()) }
            assertNeverApproves()
        }

    @Test
    fun `ineligible kind is not eligible`() =
        runTest {
            val action = pendingWith(DecisionReason.RULE)

            val result = reevaluator.addExceptionAndReevaluate(action.id, PolicyExceptionKind.NOT_AMOUNT_FIELD)

            assertEquals(ExceptionReevaluation(false, ReevaluationOutcome.NOT_ELIGIBLE), result)
            coVerify(exactly = 0) { repository.addException(any(), any(), any()) }
            assertNeverApproves()
        }

    @Test
    fun `not an amount stores the field exception and applies the fresh decision`() =
        runTest {
            val action = pendingWith(DecisionReason.AMOUNT_TIER)
            val kind = PolicyExceptionKind.NOT_AMOUNT_FIELD
            coEvery {
                repository.addException(PendingActionFixtures.PACKAGE, kind, PendingActionFixtures.FIELD_SELECTOR)
            } returns saved
            coEvery {
                coordinator.applyReevaluation(action.id, freshDecision, PolicyFixtures.NOW_MS)
            } returns ReevaluationOutcome.STILL_PENDING

            val result = reevaluator.addExceptionAndReevaluate(action.id, kind)

            assertEquals(ExceptionReevaluation(true, ReevaluationOutcome.STILL_PENDING), result)
            verify { engine.evaluate(action.subject, snapshot) }
            assertNeverApproves()
        }

    @Test
    fun `not an action button stores the same kind`() =
        runTest {
            val action = pendingWith(DecisionReason.UNKNOWN_TARGET)
            val kind = PolicyExceptionKind.NOT_ACTION_BUTTON
            coEvery { repository.addException(any(), any(), any()) } returns saved
            coEvery { coordinator.applyReevaluation(any(), any(), any()) } returns ReevaluationOutcome.STILL_PENDING

            val result = reevaluator.addExceptionAndReevaluate(action.id, kind)

            assertTrue(result.exceptionStored)
            coVerify {
                repository.addException(PendingActionFixtures.PACKAGE, kind, PendingActionFixtures.FIELD_SELECTOR)
            }
            assertNeverApproves()
        }

    @Test
    fun `already present exception counts as stored`() =
        runTest {
            val action = pendingWith(DecisionReason.AMOUNT_TIER)
            coEvery { repository.addException(any(), any(), any()) } returns saved
            coEvery { coordinator.applyReevaluation(any(), any(), any()) } returns ReevaluationOutcome.STILL_PENDING

            val result = reevaluator.addExceptionAndReevaluate(action.id, PolicyExceptionKind.NOT_AMOUNT_FIELD)

            assertTrue(result.exceptionStored)
            coVerify(exactly = 1) { coordinator.applyReevaluation(any(), any(), any()) }
        }

    @Test
    fun `stored exception on an entry decided meanwhile reports stored`() =
        runTest {
            val action = pendingWith(DecisionReason.AMOUNT_TIER)
            coEvery { repository.addException(any(), any(), any()) } returns saved
            coEvery { coordinator.applyReevaluation(any(), any(), any()) } returns ReevaluationOutcome.NOT_PENDING

            val result = reevaluator.addExceptionAndReevaluate(action.id, PolicyExceptionKind.NOT_AMOUNT_FIELD)

            assertEquals(ExceptionReevaluation(true, ReevaluationOutcome.NOT_PENDING), result)
        }

    @Test
    fun `refused write is not eligible and re-evaluates nothing`() =
        runTest {
            val action = pendingWith(DecisionReason.AMOUNT_TIER)
            val refusals =
                listOf(
                    ProfileSaveResult.Invalid(listOf(ProfileValidationError.EXCEPTION_SELECTOR_INVALID)),
                    ProfileSaveResult.ExceptionLimitReached,
                    ProfileSaveResult.NotFound,
                    ProfileSaveResult.StoreUnavailable,
                )

            refusals.forEach { refusal ->
                coEvery { repository.addException(any(), any(), any()) } returns refusal

                val result = reevaluator.addExceptionAndReevaluate(action.id, PolicyExceptionKind.NOT_AMOUNT_FIELD)

                assertEquals(ExceptionReevaluation(false, ReevaluationOutcome.NOT_ELIGIBLE), result, refusal.toString())
            }
            verify(exactly = 0) { engine.evaluate(any(), any()) }
            coVerify(exactly = 0) { coordinator.applyReevaluation(any(), any(), any()) }
            assertTrue(serverLog.entries.isEmpty())
            assertNeverApproves()
        }

    @Test
    fun `never approves`() =
        runTest {
            val action = pendingWith(DecisionReason.UNKNOWN_TARGET)
            coEvery { repository.addException(any(), any(), any()) } returns saved
            listOf(
                ReevaluationOutcome.STILL_PENDING,
                ReevaluationOutcome.NO_LONGER_NEEDS_APPROVAL,
                ReevaluationOutcome.NOW_FORBIDDEN,
                ReevaluationOutcome.NOT_PENDING,
            ).forEach { outcome ->
                coEvery { coordinator.applyReevaluation(any(), any(), any()) } returns outcome

                reevaluator.addExceptionAndReevaluate(action.id, PolicyExceptionKind.NOT_ACTION_BUTTON)
            }

            assertNeverApproves()
        }

    @Test
    fun `unreadable store after the write reports stored and not eligible`() =
        runTest {
            val action = pendingWith(DecisionReason.AMOUNT_TIER)
            coEvery { repository.addException(any(), any(), any()) } returns saved
            coEvery { repository.current() } throws ProfileStoreUnreadableException(IOException())

            val result = reevaluator.addExceptionAndReevaluate(action.id, PolicyExceptionKind.NOT_AMOUNT_FIELD)

            assertEquals(ExceptionReevaluation(true, ReevaluationOutcome.NOT_ELIGIBLE), result)
            verify(exactly = 0) { engine.evaluate(any(), any()) }
            coVerify(exactly = 0) { coordinator.applyReevaluation(any(), any(), any()) }
            assertTrue(serverLog.ofType(ServerLogEntry.Type.APPROVAL).isEmpty())
        }

    @Test
    fun `logs only the reevaluated decision without values`() =
        runTest {
            val action = pendingWith(DecisionReason.AMOUNT_TIER)
            coEvery { repository.addException(any(), any(), any()) } returns saved
            coEvery { coordinator.applyReevaluation(any(), any(), any()) } returns ReevaluationOutcome.STILL_PENDING

            reevaluator.addExceptionAndReevaluate(action.id, PolicyExceptionKind.NOT_AMOUNT_FIELD)

            val message = serverLog.ofType(ServerLogEntry.Type.APPROVAL).single().message
            assertEquals("reevaluated action=type mode=ask_in_chat reason=rule app=com.example.bank", message)
            assertFalse(message.contains("exception_added"))
            assertFalse(message.contains(PendingActionFixtures.FIELD_SELECTOR.value))

            serverLog.entries.clear()
            reevaluator.addExceptionAndReevaluate("unknown", PolicyExceptionKind.NOT_AMOUNT_FIELD)
            pendingWith(DecisionReason.RULE).also {
                reevaluator.addExceptionAndReevaluate(it.id, PolicyExceptionKind.NOT_AMOUNT_FIELD)
            }
            assertTrue(serverLog.ofType(ServerLogEntry.Type.APPROVAL).isEmpty())
        }
}
