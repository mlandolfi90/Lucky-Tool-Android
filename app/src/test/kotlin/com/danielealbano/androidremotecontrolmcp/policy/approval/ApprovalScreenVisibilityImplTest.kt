package com.danielealbano.androidremotecontrolmcp.policy.approval

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalScreenVisibilityImplTest {
    private val visibility = ApprovalScreenVisibilityImpl()

    @Test
    fun `awaitHidden returns at once when no screen is shown`() =
        runTest {
            assertTrue(visibility.awaitHidden(TIMEOUT_MS))
            assertEquals(0L, testScheduler.currentTime)
        }

    @Test
    fun `awaitHidden returns after the screen hides and the windows settle`() =
        runTest {
            visibility.onShown()
            val hidden = async { visibility.awaitHidden(TIMEOUT_MS) }
            advanceTimeBy(HIDE_AFTER_MS)
            visibility.onHidden()

            assertTrue(hidden.await())
            assertEquals(HIDE_AFTER_MS + ApprovalScreenVisibilityImpl.WINDOW_SETTLE_MS, testScheduler.currentTime)
        }

    @Test
    fun `awaitHidden returns false when the screen stays shown`() =
        runTest {
            visibility.onShown()

            assertFalse(visibility.awaitHidden(TIMEOUT_MS))
            assertEquals(TIMEOUT_MS, testScheduler.currentTime)
        }

    @Test
    fun `an extra onHidden never makes a shown screen count as hidden`() =
        runTest {
            visibility.onHidden()
            visibility.onShown()
            val hidden = async { visibility.awaitHidden(TIMEOUT_MS) }
            runCurrent()

            assertFalse(hidden.isCompleted)
            visibility.onHidden()
            assertTrue(hidden.await())
        }

    private companion object {
        const val TIMEOUT_MS = 3_000L
        const val HIDE_AFTER_MS = 400L
    }
}
