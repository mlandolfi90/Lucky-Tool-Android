package com.danielealbano.androidremotecontrolmcp.services.mcp

import app.cash.turbine.test
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingAction
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aDecision
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aPendingAction
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PolicyApprovalNotifierTest {
    private var nowMs = NOW_MS

    private fun entry(
        id: String,
        mode: RuleMode = RuleMode.NOTIFY,
        expiresAtMs: Long = NOW_MS + WINDOW_MS,
    ): PendingAction = aPendingAction(id = id, decision = aDecision(mode = mode), expiresAtMs = expiresAtMs)

    private val a = entry("a")
    private val b = entry("b", expiresAtMs = NOW_MS + WINDOW_MS / 2)
    private val askInChat = entry("ask", mode = RuleMode.ASK_IN_CHAT)

    @Test
    fun `awaiting keeps unexpired notify entries in order`() {
        assertEquals(listOf(b, a), PolicyApprovalNotifier.awaiting(listOf(b, a), NOW_MS))
    }

    @Test
    fun `awaiting drops ask-in-chat entries`() {
        assertEquals(listOf(a), PolicyApprovalNotifier.awaiting(listOf(a, askInChat), NOW_MS))
    }

    @Test
    fun `awaiting drops lapsed entries`() {
        val atDeadline = entry("now", expiresAtMs = NOW_MS)
        val lapsed = entry("old", expiresAtMs = NOW_MS - 1)

        assertTrue(PolicyApprovalNotifier.awaiting(listOf(atDeadline, lapsed), NOW_MS).isEmpty())
    }

    @Test
    fun `awaiting of an empty list is empty`() {
        assertTrue(PolicyApprovalNotifier.awaiting(emptyList(), NOW_MS).isEmpty())
    }

    @Test
    fun `awaitingChanges emits only when the notify ids change`() =
        runTest {
            val source = MutableSharedFlow<List<PendingAction>>(replay = 0)

            PolicyApprovalNotifier.awaitingChanges(source) { nowMs }.test {
                source.emit(listOf(a))
                assertEquals(listOf(a), awaitItem())
                source.emit(listOf(a))
                source.emit(listOf(a, askInChat))
                expectNoEvents()
                source.emit(listOf(a, b))
                assertEquals(listOf(a, b), awaitItem())
                source.emit(listOf(b))
                assertEquals(listOf(b), awaitItem())
                source.emit(emptyList())
                assertEquals(emptyList<PendingAction>(), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `awaitingChanges ignores a reordered list with the same ids`() =
        runTest {
            val source = MutableSharedFlow<List<PendingAction>>(replay = 0)

            PolicyApprovalNotifier.awaitingChanges(source) { nowMs }.test {
                source.emit(listOf(a, b))
                assertEquals(listOf(a, b), awaitItem())
                source.emit(listOf(b, a))
                expectNoEvents()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `awaitingChanges emits when an entry lapses`() =
        runTest {
            val source = MutableSharedFlow<List<PendingAction>>(replay = 0)

            PolicyApprovalNotifier.awaitingChanges(source) { nowMs }.test {
                source.emit(listOf(a, b))
                assertEquals(listOf(a, b), awaitItem())
                nowMs = b.expiresAtMs + 1
                source.emit(listOf(a, b))
                assertEquals(listOf(a), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    private companion object {
        const val NOW_MS = 1_000_000L
        const val WINDOW_MS = 300_000L
    }
}
