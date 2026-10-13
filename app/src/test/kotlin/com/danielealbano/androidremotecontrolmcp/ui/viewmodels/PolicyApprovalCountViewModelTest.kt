package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingAction
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aPendingAction
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PolicyApprovalCountViewModelTest {
    private val pendingFlow = MutableStateFlow<List<PendingAction>>(emptyList())
    private val coordinator = mockk<ActionApprovalCoordinator>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { coordinator.observePending() } returns pendingFlow
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `count follows the pending list`() =
        runTest {
            val viewModel = PolicyApprovalCountViewModel(coordinator)

            viewModel.pendingCount.test {
                assertEquals(0, awaitItem())
                pendingFlow.value = listOf(aPendingAction(id = "a"), aPendingAction(id = "b"))
                assertEquals(2, awaitItem())
                pendingFlow.value = listOf(aPendingAction(id = "a"))
                assertEquals(1, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
