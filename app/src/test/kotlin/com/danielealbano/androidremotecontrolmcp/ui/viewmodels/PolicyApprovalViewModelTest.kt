package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.policy.amount.AmountParser
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalPolicy
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalState
import com.danielealbano.androidremotecontrolmcp.policy.approval.ExceptionReevaluation
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingAction
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.PACKAGE
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aDecision
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aPendingAction
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aSubject
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aTarget
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingReevaluator
import com.danielealbano.androidremotecontrolmcp.policy.approval.ReevaluationOutcome
import com.danielealbano.androidremotecontrolmcp.policy.engine.DecisionReason
import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyDecision
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalEventLogger
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogMessage
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogOutcome
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogRecord
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.services.apps.AppIconCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PolicyApprovalViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val pendingFlow = MutableStateFlow(listOf(aPendingAction()))
    private val coordinator = mockk<ActionApprovalCoordinator>(relaxed = true)
    private val reevaluator = mockk<PendingReevaluator>()
    private val appIconCache = mockk<AppIconCache>(relaxed = true)
    private val events = mockk<ApprovalEventLogger>(relaxed = true)
    private val records = mutableListOf<ApprovalLogRecord>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { coordinator.observePending() } returns pendingFlow
        coEvery { coordinator.approve(any(), any()) } returns ActionApprovalState.APPROVED
        coEvery { coordinator.stateOf(any(), any()) } returns ActionApprovalState.DENIED
        coEvery { reevaluator.addExceptionAndReevaluate(any(), any()) } returns
            ExceptionReevaluation(exceptionStored = true, outcome = ReevaluationOutcome.STILL_PENDING)
        every { appIconCache.getLaunchableApps() } returns listOf(PACKAGE to "Bank")
        every { events.log(capture(records), any(), any()) } returns Unit
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = PolicyApprovalViewModel(coordinator, reevaluator, appIconCache, events, dispatcher)

    private fun pendingWith(
        reason: DecisionReason = DecisionReason.RULE,
        amountText: String? = null,
        isPassword: Boolean = false,
    ): PendingAction {
        val decision = aDecision(reason = reason)
        val action =
            aPendingAction(
                decision = decision,
                subject = aSubject(decision, listOf(aTarget(isPassword = isPassword)), amountText),
            )
        pendingFlow.value = listOf(action)
        return action
    }

    private fun PolicyApprovalViewModel.card(): PolicyApprovalCard = uiState.value.cards.single()

    @Test
    fun `maps a pending action to a card`() {
        val card = viewModel().card()

        assertEquals("Bank", card.appName)
        assertEquals(PendingActionFixtures.TOOL, card.toolName)
        assertEquals(PolicyActionType.ACTION_BUTTON, card.actionType)
        assertEquals(RuleMode.NOTIFY, card.method)
        assertEquals(DecisionReason.RULE, card.reason)
        assertEquals(ActionApprovalPolicy.APPROVAL_WINDOW_MS, card.expiresAtMs)
        assertEquals("Amount", card.fieldLabel)
    }

    @Test
    fun `amount is the largest parsed number`() {
        pendingWith(amountText = "Pay 1.500 now")
        val withAmount = viewModel().card()
        pendingWith(amountText = null)
        val withoutAmount = viewModel().card()

        assertEquals("1500", withAmount.amount)
        assertFalse(withAmount.amountUnreadable)
        assertNull(withoutAmount.amount)
    }

    @Test
    fun `password field never shows an amount`() {
        pendingWith(amountText = "4321", isPassword = true)

        val card = viewModel().card()

        assertNull(card.amount)
        assertFalse(card.amountUnreadable)
    }

    @Test
    fun `amount over the parser caps is unreadable`() {
        pendingWith(amountText = "1".repeat(AmountParser.MAX_INPUT_LENGTH + 1))

        val card = viewModel().card()

        assertNull(card.amount)
        assertTrue(card.amountUnreadable)
    }

    @Test
    fun `unknown package has no app name`() {
        val decision =
            PolicyDecision(RuleMode.NOTIFY, DecisionReason.RULE, "com.other.app", PolicyActionType.ACTION_BUTTON)
        pendingFlow.value = listOf(aPendingAction(decision = decision))

        assertNull(viewModel().card().appName)
    }

    @Test
    fun `exception flags follow ExceptionEligibility`() {
        pendingWith(DecisionReason.AMOUNT_TIER)
        val amountCard = viewModel().card()
        pendingWith(DecisionReason.UNKNOWN_TARGET)
        val unknownCard = viewModel().card()
        pendingWith(DecisionReason.RULE)
        val ruleCard = viewModel().card()

        assertTrue(amountCard.canMarkNotAmount && !amountCard.canMarkNotActionButton)
        assertTrue(!unknownCard.canMarkNotAmount && unknownCard.canMarkNotActionButton)
        assertTrue(!ruleCard.canMarkNotAmount && !ruleCard.canMarkNotActionButton)
    }

    @Test
    fun `cards follow the pending flow`() =
        runTest(dispatcher) {
            val viewModel = viewModel()

            viewModel.uiState.test {
                assertEquals(1, awaitItem().cards.size)
                pendingFlow.value = emptyList()
                assertTrue(awaitItem().cards.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `card and confirmation toString hide amount, label and id`() {
        pendingWith(DecisionReason.AMOUNT_TIER, amountText = "1500")
        val viewModel = viewModel()
        viewModel.requestException("id-1", PolicyExceptionKind.NOT_AMOUNT_FIELD)

        val texts =
            listOf(
                viewModel.card().toString(),
                viewModel.uiState.value.confirmation
                    .toString(),
            )

        texts.forEach { text -> listOf("1500", "Amount", "id-1").forEach { assertFalse(text.contains(it), it) } }
    }

    @Test
    fun `approve logs owner_approved with decision codes only`() {
        viewModel().approve("id-1")

        coVerify { coordinator.approve("id-1", any()) }
        val record = records.single()
        assertEquals(ApprovalLogOutcome.OWNER_APPROVED, record.outcome)
        assertEquals(PACKAGE, record.packageName)
        assertEquals(PolicyActionType.ACTION_BUTTON, record.actionType)
        assertEquals(RuleMode.NOTIFY, record.mode)
        assertEquals(DecisionReason.RULE, record.reason)
        verify { events.log(any(), null, null) }
    }

    @Test
    fun `late approve logs expired`() {
        coEvery { coordinator.approve(any(), any()) } returns ActionApprovalState.EXPIRED

        viewModel().approve("id-1")

        assertEquals(ApprovalLogOutcome.EXPIRED, records.single().outcome)
    }

    @Test
    fun `approve that finds the entry already denied logs nothing`() {
        coEvery { coordinator.approve(any(), any()) } returns ActionApprovalState.DENIED

        viewModel().approve("id-1")

        verify(exactly = 0) { events.log(any(), any(), any()) }
    }

    @Test
    fun `approve that finds the entry not required or already used logs nothing`() {
        listOf(ActionApprovalState.NOT_REQUIRED, ActionApprovalState.ALREADY_USED).forEach { state ->
            coEvery { coordinator.approve(any(), any()) } returns state

            viewModel().approve("id-1")
        }

        verify(exactly = 0) { events.log(any(), any(), any()) }
    }

    @Test
    fun `approve of an id no longer pending does nothing`() {
        viewModel().approve("unknown")

        coVerify(exactly = 0) { coordinator.approve(any(), any()) }
        verify(exactly = 0) { events.log(any(), any(), any()) }
    }

    @Test
    fun `deny logs owner_denied when the entry ended denied`() {
        viewModel().deny("id-1")

        coVerify { coordinator.deny("id-1") }
        assertEquals(ApprovalLogOutcome.OWNER_DENIED, records.single().outcome)
        verify { events.log(any(), null, null) }
    }

    @Test
    fun `deny logs nothing when the entry was approved meanwhile`() {
        coEvery { coordinator.stateOf(any(), any()) } returns ActionApprovalState.APPROVED

        viewModel().deny("id-1")

        coVerify { coordinator.deny("id-1") }
        verify(exactly = 0) { events.log(any(), any(), any()) }
    }

    @Test
    fun `requestException opens the confirmation when offered`() {
        pendingWith(DecisionReason.AMOUNT_TIER)
        val viewModel = viewModel()

        viewModel.requestException("id-1", PolicyExceptionKind.NOT_AMOUNT_FIELD)

        assertEquals(
            ExceptionConfirmation("id-1", PolicyExceptionKind.NOT_AMOUNT_FIELD, "Bank", "Amount"),
            viewModel.uiState.value.confirmation,
        )
    }

    @Test
    fun `requestException ignores kinds the card does not offer`() {
        pendingWith(DecisionReason.AMOUNT_TIER)
        val viewModel = viewModel()

        viewModel.requestException("id-1", PolicyExceptionKind.NOT_ACTION_BUTTON)
        assertNull(viewModel.uiState.value.confirmation)
        viewModel.requestException("unknown", PolicyExceptionKind.NOT_AMOUNT_FIELD)
        assertNull(viewModel.uiState.value.confirmation)
    }

    @Test
    fun `dismissException clears the confirmation`() {
        pendingWith(DecisionReason.AMOUNT_TIER)
        val viewModel = viewModel()
        viewModel.requestException("id-1", PolicyExceptionKind.NOT_AMOUNT_FIELD)

        viewModel.dismissException()

        assertNull(viewModel.uiState.value.confirmation)
        coVerify(exactly = 0) { reevaluator.addExceptionAndReevaluate(any(), any()) }
    }

    @Test
    fun `confirmException re-evaluates once, logs exception_added and never approves`() {
        pendingWith(DecisionReason.AMOUNT_TIER)
        val viewModel = viewModel()
        viewModel.requestException("id-1", PolicyExceptionKind.NOT_AMOUNT_FIELD)

        viewModel.confirmException()

        coVerify(exactly = 1) { reevaluator.addExceptionAndReevaluate("id-1", PolicyExceptionKind.NOT_AMOUNT_FIELD) }
        coVerify(exactly = 0) { coordinator.approve(any(), any()) }
        val record = records.single()
        assertEquals(ApprovalLogOutcome.EXCEPTION_ADDED, record.outcome)
        assertEquals(PACKAGE, record.packageName)
        verify { events.log(any(), null, null) }
        assertNull(viewModel.uiState.value.confirmation)
        assertEquals(ReevaluationOutcome.STILL_PENDING, viewModel.uiState.value.outcome)
    }

    @Test
    fun `confirmException logs exception_added when stored but no longer pending`() {
        coEvery { reevaluator.addExceptionAndReevaluate(any(), any()) } returns
            ExceptionReevaluation(true, ReevaluationOutcome.NOT_PENDING)
        pendingWith(DecisionReason.AMOUNT_TIER)
        val viewModel = viewModel()
        viewModel.requestException("id-1", PolicyExceptionKind.NOT_AMOUNT_FIELD)

        viewModel.confirmException()

        assertEquals(ApprovalLogOutcome.EXCEPTION_ADDED, records.single().outcome)
        assertEquals(ReevaluationOutcome.NOT_PENDING, viewModel.uiState.value.outcome)
    }

    @Test
    fun `confirmException logs nothing when the exception was not stored`() {
        pendingWith(DecisionReason.AMOUNT_TIER)
        val viewModel = viewModel()
        listOf(
            ExceptionReevaluation(false, ReevaluationOutcome.NOT_ELIGIBLE),
            ExceptionReevaluation(false, ReevaluationOutcome.NOT_PENDING),
        ).forEach { result ->
            coEvery { reevaluator.addExceptionAndReevaluate(any(), any()) } returns result
            viewModel.requestException("id-1", PolicyExceptionKind.NOT_AMOUNT_FIELD)

            viewModel.confirmException()

            assertEquals(result.outcome, viewModel.uiState.value.outcome)
        }
        verify(exactly = 0) { events.log(any(), any(), any()) }
    }

    @Test
    fun `confirmException without a confirmation does nothing`() {
        viewModel().confirmException()

        coVerify(exactly = 0) { reevaluator.addExceptionAndReevaluate(any(), any()) }
        verify(exactly = 0) { events.log(any(), any(), any()) }
    }

    @Test
    fun `outcomeShown clears the outcome`() {
        pendingWith(DecisionReason.AMOUNT_TIER)
        val viewModel = viewModel()
        viewModel.requestException("id-1", PolicyExceptionKind.NOT_AMOUNT_FIELD)
        viewModel.confirmException()

        viewModel.outcomeShown()

        assertNull(viewModel.uiState.value.outcome)
    }

    @Test
    fun `log records never carry the amount or the label`() {
        pendingWith(DecisionReason.AMOUNT_TIER, amountText = "Pay 1500")
        val viewModel = viewModel()

        viewModel.approve("id-1")
        viewModel.requestException("id-1", PolicyExceptionKind.NOT_AMOUNT_FIELD)
        viewModel.confirmException()

        assertTrue(records.isNotEmpty())
        records.map(ApprovalLogMessage::encode).forEach { message ->
            assertFalse(message.contains("1500"))
            assertFalse(message.contains("Amount"))
        }
    }
}
