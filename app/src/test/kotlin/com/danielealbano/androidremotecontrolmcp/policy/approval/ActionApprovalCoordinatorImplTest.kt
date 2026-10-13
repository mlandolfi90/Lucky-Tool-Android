package com.danielealbano.androidremotecontrolmcp.policy.approval

import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aDecision
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aPendingAction
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aRequest
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aSubject
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingActionFixtures.aTarget
import com.danielealbano.androidremotecontrolmcp.policy.engine.DecisionReason
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@OptIn(ExperimentalCoroutinesApi::class)
class ActionApprovalCoordinatorImplTest {
    private val coordinator = ActionApprovalCoordinatorImpl()

    private suspend fun create(
        fingerprint: String = "fp-1",
        method: RuleMode = RuleMode.NOTIFY,
        nowMs: Long = 0L,
    ): PendingAction = coordinator.createOrJoin(aRequest(fingerprint = fingerprint, method = method), nowMs)

    private fun pendingIds(): List<String> = coordinator.observePending().value.map { it.id }

    @Test
    fun `createOrJoin creates a pending entry visible in observePending`() =
        runTest {
            val action = create()

            assertEquals(listOf(action), coordinator.observePending().value)
            assertEquals(ActionApprovalState.PENDING, coordinator.stateOf(action.id, 0L))
        }

    @Test
    fun `id is 32 lowercase hex and expiry is now plus window`() =
        runTest {
            val action = create(nowMs = NOW)

            assertTrue(Regex("^[0-9a-f]{32}$").matches(action.id))
            assertEquals(NOW + W, action.expiresAtMs)
            assertEquals(NOW, action.createdAtMs)
        }

    @Test
    fun `identical fingerprint joins the same entry`() =
        runTest {
            val first = create()
            val second = create()

            assertEquals(first.id, second.id)
            assertEquals(1, pendingIds().size)
        }

    @Test
    fun `stricter method upgrades the joined entry in place`() =
        runTest {
            val first = create(method = RuleMode.ASK_IN_CHAT)
            val upgradeDecision = aDecision(mode = RuleMode.NOTIFY, reason = DecisionReason.AMOUNT_TIER)

            val joined =
                coordinator.createOrJoin(aRequest(method = RuleMode.NOTIFY, decision = upgradeDecision), 0L)

            assertEquals(first.id, joined.id)
            assertEquals(RuleMode.NOTIFY, joined.method)
            assertEquals(upgradeDecision, joined.decision)
            assertEquals(listOf(joined), coordinator.observePending().value)
        }

    @Test
    fun `weaker method keeps the stricter entry`() =
        runTest {
            val first = create(method = RuleMode.NOTIFY)

            val joined = create(method = RuleMode.ASK_IN_CHAT)

            assertEquals(first.id, joined.id)
            assertEquals(RuleMode.NOTIFY, joined.method)
        }

    @Test
    fun `join does not count toward the cap`() =
        runTest {
            repeat(ActionApprovalPolicy.MAX_PENDING) { create(fingerprint = "fp-$it") }

            create(fingerprint = "fp-0")

            assertEquals(ActionApprovalPolicy.MAX_PENDING, pendingIds().size)
        }

    @Test
    fun `eleventh distinct request is refused`() =
        runTest {
            repeat(ActionApprovalPolicy.MAX_PENDING) { create(fingerprint = "fp-$it") }
            val before = coordinator.observePending().value

            val error = assertThrows<TooManyPendingActionsException> { create(fingerprint = "fp-new") }

            assertEquals(ActionApprovalPolicy.TOO_MANY_PENDING_CODE, error.message)
            assertEquals(before, coordinator.observePending().value)
        }

    @Test
    fun `lapsed entries free cap slots`() =
        runTest {
            val old = (0 until ActionApprovalPolicy.MAX_PENDING).map { create(fingerprint = "fp-$it") }

            val fresh = create(fingerprint = "fp-new", nowMs = W)

            assertEquals(listOf(fresh.id), pendingIds())
            old.forEach { assertEquals(ActionApprovalState.EXPIRED, coordinator.stateOf(it.id, W)) }
        }

    @Test
    fun `RUN and FORBIDDEN methods are rejected`() =
        runTest {
            listOf(RuleMode.RUN, RuleMode.FORBIDDEN).forEach { method ->
                assertThrows<IllegalArgumentException> { create(method = method) }
            }
            assertTrue(pendingIds().isEmpty())
        }

    @Test
    fun `approve before deadline approves and leaves pending`() =
        runTest {
            val action = create()

            assertEquals(ActionApprovalState.APPROVED, coordinator.approve(action.id, W - 1))
            assertTrue(pendingIds().isEmpty())
            assertEquals(ActionApprovalState.APPROVED, coordinator.stateOf(action.id, W - 1))
        }

    @Test
    fun `approve at deadline expires instead`() =
        runTest {
            val action = create()

            assertEquals(ActionApprovalState.EXPIRED, coordinator.approve(action.id, action.createdAtMs + W))
            assertFalse(coordinator.consumeGrant("fp-1", RuleMode.NOTIFY, action.createdAtMs + W))
        }

    @Test
    fun `deny marks denied and later approve is ignored`() =
        runTest {
            val action = create()

            coordinator.deny(action.id)

            assertEquals(ActionApprovalState.DENIED, coordinator.stateOf(action.id, 1L))
            assertEquals(ActionApprovalState.DENIED, coordinator.approve(action.id, 1L))
            assertFalse(coordinator.consumeGrant("fp-1", RuleMode.NOTIFY, 1L))
        }

    @Test
    fun `unknown id reads expired everywhere`() =
        runTest {
            val action = create()

            assertEquals(ActionApprovalState.EXPIRED, coordinator.stateOf("unknown", 0L))
            assertEquals(ActionApprovalState.EXPIRED, coordinator.approve("unknown", 0L))
            assertEquals(ActionApprovalState.EXPIRED, coordinator.awaitDecision("unknown", 1_000L))
            assertEquals(ChatConfirmResult.EXPIRED, coordinator.confirmInChat("unknown", 0L))
            coordinator.deny("unknown")
            assertEquals(listOf(action.id), pendingIds())
        }

    @Test
    fun `stateOf expires a lapsed pending entry`() =
        runTest {
            val action = create()

            assertEquals(ActionApprovalState.EXPIRED, coordinator.stateOf(action.id, action.createdAtMs + W))
            assertTrue(pendingIds().isEmpty())
        }

    @Test
    fun `statusOf pending returns state and expiry`() =
        runTest {
            val action = create(nowMs = 1_000L)

            assertEquals(
                ApprovalStatusView(ActionApprovalState.PENDING, 1_000L + W),
                coordinator.statusOf(action.id, 1_000L),
            )
        }

    @Test
    fun `statusOf decided entry has no expiry`() =
        runTest {
            val approved = create(fingerprint = "fp-a")
            val denied = create(fingerprint = "fp-d")
            coordinator.approve(approved.id, 1L)
            coordinator.deny(denied.id)

            assertEquals(ApprovalStatusView(ActionApprovalState.APPROVED, null), coordinator.statusOf(approved.id, 1L))
            assertEquals(ApprovalStatusView(ActionApprovalState.DENIED, null), coordinator.statusOf(denied.id, 1L))
        }

    @Test
    fun `statusOf unknown id is expired without expiry`() =
        runTest {
            assertEquals(ApprovalStatusView(ActionApprovalState.EXPIRED, null), coordinator.statusOf("unknown", 5L))
        }

    @Test
    fun `statusOf at the deadline expires the entry`() =
        runTest {
            val action = create()
            val awaiter = async { coordinator.awaitDecision(action.id, LONG_WAIT_MS) }
            runCurrent()

            val status = coordinator.statusOf(action.id, action.createdAtMs + W)

            assertEquals(ApprovalStatusView(ActionApprovalState.EXPIRED, null), status)
            assertTrue(pendingIds().isEmpty())
            assertEquals(ActionApprovalState.EXPIRED, awaiter.await())
        }

    @Test
    fun `awaitDecision returns approved when approved during the wait`() =
        runTest {
            val action = create()
            val awaiter = async { coordinator.awaitDecision(action.id, WAIT_MS) }
            advanceTimeBy(1_000L)

            coordinator.approve(action.id, 1_000L)

            assertEquals(ActionApprovalState.APPROVED, awaiter.await())
        }

    @Test
    fun `awaitDecision returns pending after the timeout`() =
        runTest {
            val action = create()

            assertEquals(ActionApprovalState.PENDING, coordinator.awaitDecision(action.id, WAIT_MS))
            assertTrue(testScheduler.currentTime >= WAIT_MS)
        }

    @Test
    fun `awaitDecision wakes with expired when a purge lapses the entry`() =
        runTest {
            val action = create()
            val awaiter = async { coordinator.awaitDecision(action.id, LONG_WAIT_MS) }
            runCurrent()

            create(fingerprint = "fp-other", nowMs = W)

            assertEquals(ActionApprovalState.EXPIRED, awaiter.await())
        }

    @Test
    fun `awaitDecision with zero timeout returns current state`() =
        runTest {
            val approved = create(fingerprint = "fp-a")
            val pending = create(fingerprint = "fp-p")
            coordinator.approve(approved.id, 1L)

            assertEquals(ActionApprovalState.APPROVED, coordinator.awaitDecision(approved.id, 0L))
            assertEquals(ActionApprovalState.PENDING, coordinator.awaitDecision(pending.id, 0L))
        }

    @Test
    fun `confirmInChat confirms an ask-in-chat entry and grants it`() =
        runTest {
            val action = create(method = RuleMode.ASK_IN_CHAT)

            assertEquals(ChatConfirmResult.CONFIRMED, coordinator.confirmInChat(action.id, 1L))
            assertTrue(coordinator.consumeGrant("fp-1", RuleMode.ASK_IN_CHAT, 1L))
        }

    @Test
    fun `confirmInChat refuses notify entries`() =
        runTest {
            val action = create(method = RuleMode.NOTIFY)

            assertEquals(ChatConfirmResult.NOT_CONFIRMABLE, coordinator.confirmInChat(action.id, 1L))
            assertEquals(ActionApprovalState.PENDING, coordinator.stateOf(action.id, 1L))
            assertFalse(coordinator.consumeGrant("fp-1", RuleMode.ASK_IN_CHAT, 1L))
        }

    @Test
    fun `confirmInChat twice returns already approved and one grant`() =
        runTest {
            val action = create(method = RuleMode.ASK_IN_CHAT)
            coordinator.confirmInChat(action.id, 1L)

            assertEquals(ChatConfirmResult.ALREADY_APPROVED, coordinator.confirmInChat(action.id, 2L))
            assertTrue(coordinator.consumeGrant("fp-1", RuleMode.ASK_IN_CHAT, 2L))
            assertFalse(coordinator.consumeGrant("fp-1", RuleMode.ASK_IN_CHAT, 2L))
        }

    @Test
    fun `confirmInChat after deadline returns expired`() =
        runTest {
            val action = create(method = RuleMode.ASK_IN_CHAT)

            assertEquals(ChatConfirmResult.EXPIRED, coordinator.confirmInChat(action.id, W))
            assertFalse(coordinator.consumeGrant("fp-1", RuleMode.ASK_IN_CHAT, W))
        }

    @Test
    fun `confirmInChat on denied entry returns denied`() =
        runTest {
            val action = create(method = RuleMode.ASK_IN_CHAT)
            coordinator.deny(action.id)

            assertEquals(ChatConfirmResult.DENIED, coordinator.confirmInChat(action.id, 1L))
        }

    @Test
    fun `owner grant is single use`() =
        runTest {
            val action = create()
            coordinator.approve(action.id, 1L)

            assertTrue(coordinator.consumeGrant("fp-1", RuleMode.NOTIFY, 1L))
            assertFalse(coordinator.consumeGrant("fp-1", RuleMode.NOTIFY, 1L))
        }

    @Test
    fun `grant expires after ttl`() =
        runTest {
            val approvedAt = 1_000L
            coordinator.approve(create(fingerprint = "fp-late").id, approvedAt)
            coordinator.approve(create(fingerprint = "fp-early").id, approvedAt)

            assertTrue(coordinator.consumeGrant("fp-early", RuleMode.NOTIFY, approvedAt + G - 1))
            assertFalse(coordinator.consumeGrant("fp-late", RuleMode.NOTIFY, approvedAt + G))
        }

    @Test
    fun `chat grant does not satisfy notify`() =
        runTest {
            val action = create(method = RuleMode.ASK_IN_CHAT)
            coordinator.confirmInChat(action.id, 1L)

            assertFalse(coordinator.consumeGrant("fp-1", RuleMode.NOTIFY, 1L))
            assertTrue(coordinator.consumeGrant("fp-1", RuleMode.ASK_IN_CHAT, 1L))
        }

    @Test
    fun `owner grant satisfies ask-in-chat`() =
        runTest {
            val action = create(method = RuleMode.ASK_IN_CHAT)
            coordinator.approve(action.id, 1L)

            assertTrue(coordinator.consumeGrant("fp-1", RuleMode.ASK_IN_CHAT, 1L))
        }

    @Test
    fun `consumeGrant without grant is false`() =
        runTest {
            assertFalse(coordinator.consumeGrant("fp-unknown", RuleMode.ASK_IN_CHAT, 1L))
        }

    @Test
    fun `applyReevaluation keeps pending with the new decision`() =
        runTest {
            val action = create(method = RuleMode.NOTIFY)
            val decision = aDecision(mode = RuleMode.ASK_IN_CHAT)

            assertEquals(ReevaluationOutcome.STILL_PENDING, coordinator.applyReevaluation(action.id, decision, 1L))
            val updated = coordinator.observePending().value.single()
            assertEquals(action.id, updated.id)
            assertEquals(RuleMode.ASK_IN_CHAT, updated.method)
            assertEquals(decision, updated.decision)
        }

    @Test
    fun `applyReevaluation to run closes the entry without a grant`() =
        runTest {
            val action = create()

            val outcome = coordinator.applyReevaluation(action.id, aDecision(mode = RuleMode.RUN), 1L)

            assertEquals(ReevaluationOutcome.NO_LONGER_NEEDS_APPROVAL, outcome)
            assertEquals(
                ApprovalStatusView(ActionApprovalState.NOT_REQUIRED, null),
                coordinator.statusOf(action.id, 1L),
            )
            assertFalse(coordinator.consumeGrant("fp-1", RuleMode.ASK_IN_CHAT, 1L))
            assertTrue(pendingIds().isEmpty())
        }

    @Test
    fun `applyReevaluation to run wakes awaiters with not required`() =
        runTest {
            val action = create()
            val awaiter = async { coordinator.awaitDecision(action.id, WAIT_MS) }
            runCurrent()

            coordinator.applyReevaluation(action.id, aDecision(mode = RuleMode.RUN), 1L)

            assertEquals(ActionApprovalState.NOT_REQUIRED, awaiter.await())
            assertTrue(testScheduler.currentTime < WAIT_MS)
        }

    @Test
    fun `not required entry stays not required after its window`() =
        runTest {
            val action = create()
            coordinator.applyReevaluation(action.id, aDecision(mode = RuleMode.RUN), 1L)

            assertEquals(ActionApprovalState.NOT_REQUIRED, coordinator.stateOf(action.id, action.createdAtMs + W))
        }

    @Test
    fun `confirmInChat on a not required entry returns not required without a grant`() =
        runTest {
            val action = create(method = RuleMode.ASK_IN_CHAT)
            coordinator.applyReevaluation(action.id, aDecision(mode = RuleMode.RUN), 1L)

            assertEquals(ChatConfirmResult.NOT_REQUIRED, coordinator.confirmInChat(action.id, 1L))
            assertFalse(coordinator.consumeGrant("fp-1", RuleMode.ASK_IN_CHAT, 1L))
        }

    @Test
    fun `consumed grant marks the approved entry already used`() =
        runTest {
            val action = create()
            coordinator.approve(action.id, 1L)

            assertTrue(coordinator.consumeGrant("fp-1", RuleMode.NOTIFY, 1L))

            assertEquals(
                ApprovalStatusView(ActionApprovalState.ALREADY_USED, null),
                coordinator.statusOf(action.id, 1L),
            )
            assertEquals(ActionApprovalState.ALREADY_USED, coordinator.approve(action.id, 1L))
        }

    @Test
    fun `refused consume leaves the approved entry approved`() =
        runTest {
            val action = create(method = RuleMode.ASK_IN_CHAT)
            coordinator.confirmInChat(action.id, 1L)

            assertFalse(coordinator.consumeGrant("fp-1", RuleMode.NOTIFY, 1L))

            assertEquals(ActionApprovalState.APPROVED, coordinator.stateOf(action.id, 1L))
        }

    @Test
    fun `consumed grant marks only entries with that fingerprint`() =
        runTest {
            val used = create(fingerprint = "fp-used")
            val other = create(fingerprint = "fp-other")
            coordinator.approve(used.id, 1L)
            coordinator.approve(other.id, 1L)

            coordinator.consumeGrant("fp-used", RuleMode.NOTIFY, 1L)

            assertEquals(ActionApprovalState.ALREADY_USED, coordinator.stateOf(used.id, 1L))
            assertEquals(ActionApprovalState.APPROVED, coordinator.stateOf(other.id, 1L))
        }

    @Test
    fun `confirmInChat on a used entry returns already used without a new grant`() =
        runTest {
            val action = create(method = RuleMode.ASK_IN_CHAT)
            coordinator.confirmInChat(action.id, 1L)
            coordinator.consumeGrant("fp-1", RuleMode.ASK_IN_CHAT, 1L)

            assertEquals(ChatConfirmResult.ALREADY_USED, coordinator.confirmInChat(action.id, 2L))
            assertFalse(coordinator.consumeGrant("fp-1", RuleMode.ASK_IN_CHAT, 2L))
        }

    @Test
    fun `applyReevaluation to forbidden denies`() =
        runTest {
            val action = create()

            val outcome = coordinator.applyReevaluation(action.id, aDecision(mode = RuleMode.FORBIDDEN), 1L)

            assertEquals(ReevaluationOutcome.NOW_FORBIDDEN, outcome)
            assertEquals(ActionApprovalState.DENIED, coordinator.stateOf(action.id, 1L))
        }

    @Test
    fun `applyReevaluation on a non-pending entry`() =
        runTest {
            val approved = create(fingerprint = "fp-a")
            val denied = create(fingerprint = "fp-d")
            coordinator.approve(approved.id, 1L)
            coordinator.deny(denied.id)
            val decision = aDecision(mode = RuleMode.ASK_IN_CHAT)

            assertEquals(ReevaluationOutcome.NOT_PENDING, coordinator.applyReevaluation(approved.id, decision, 1L))
            assertEquals(ReevaluationOutcome.NOT_PENDING, coordinator.applyReevaluation(denied.id, decision, 1L))
            assertEquals(ReevaluationOutcome.NOT_PENDING, coordinator.applyReevaluation("unknown", decision, 1L))
            assertEquals(ActionApprovalState.APPROVED, coordinator.stateOf(approved.id, 1L))
            assertEquals(ActionApprovalState.DENIED, coordinator.stateOf(denied.id, 1L))
        }

    @Test
    fun `clearAll denies every pending entry`() =
        runTest {
            val actions =
                listOf(
                    create(fingerprint = "fp-1", method = RuleMode.ASK_IN_CHAT),
                    create(fingerprint = "fp-2", method = RuleMode.NOTIFY),
                    create(fingerprint = "fp-3", method = RuleMode.ASK_IN_CHAT),
                )

            coordinator.clearAll()

            actions.forEach { assertEquals(ActionApprovalState.DENIED, coordinator.stateOf(it.id, 1L)) }
            assertTrue(pendingIds().isEmpty())
        }

    @Test
    fun `clearAll wakes awaiters with denied`() =
        runTest {
            val action = create()
            val awaiter = async { coordinator.awaitDecision(action.id, WAIT_MS) }
            runCurrent()

            coordinator.clearAll()

            assertEquals(ActionApprovalState.DENIED, awaiter.await())
            assertTrue(testScheduler.currentTime < WAIT_MS)
        }

    @Test
    fun `clearAll drops every grant`() =
        runTest {
            coordinator.approve(create(fingerprint = "fp-owner").id, 1L)
            coordinator.confirmInChat(create(fingerprint = "fp-chat", method = RuleMode.ASK_IN_CHAT).id, 1L)

            coordinator.clearAll()

            assertFalse(coordinator.consumeGrant("fp-owner", RuleMode.ASK_IN_CHAT, 1L))
            assertFalse(coordinator.consumeGrant("fp-chat", RuleMode.ASK_IN_CHAT, 1L))
        }

    @Test
    fun `clearAll keeps decided entries and the ledger usable`() =
        runTest {
            val approved = create(fingerprint = "fp-a")
            coordinator.approve(approved.id, 1L)

            coordinator.clearAll()
            val fresh = create(fingerprint = "fp-a", nowMs = 2L)

            assertEquals(ActionApprovalState.APPROVED, coordinator.stateOf(approved.id, 2L))
            assertEquals(ActionApprovalState.PENDING, coordinator.stateOf(fresh.id, 2L))
            assertNotEquals(approved.id, fresh.id)
        }

    @Test
    fun `tracked entries are bounded`() =
        runTest {
            val approved =
                (0 until ActionApprovalPolicy.MAX_TRACKED_ENTRIES).map {
                    create(fingerprint = "fp-$it").also { action -> coordinator.approve(action.id, 0L) }
                }

            val fresh = create(fingerprint = "fp-new")

            assertEquals(ActionApprovalState.EXPIRED, coordinator.stateOf(approved.first().id, 0L))
            assertEquals(ActionApprovalState.APPROVED, coordinator.stateOf(approved.last().id, 0L))
            assertEquals(ActionApprovalState.PENDING, coordinator.stateOf(fresh.id, 0L))
        }

    @Test
    fun `concurrent identical requests create one entry`() =
        runTest {
            val ids =
                withContext(Dispatchers.Default) {
                    (1..CONCURRENT_CALLS).map { async { create().id } }.awaitAll()
                }

            assertEquals(1, ids.toSet().size)
        }

    @Test
    fun `toString never exposes amount, label, fingerprint or id`() {
        val decision = aDecision()
        val subject = aSubject(decision, listOf(aTarget(label = "Amount")), amountText = "1500")
        val id = "0123456789abcdef0123456789abcdef"
        val action = aPendingAction(id = id, decision = decision, subject = subject)
        val request = aRequest(fingerprint = "fp-secret", decision = decision, subject = subject)

        listOf(action.toString(), request.toString()).forEach { text ->
            listOf("1500", "Amount", "fp-secret", id).forEach { secret ->
                assertFalse(text.contains(secret), "\"$secret\" leaked in $text")
            }
        }
    }

    private companion object {
        const val W = ActionApprovalPolicy.APPROVAL_WINDOW_MS
        const val G = ActionApprovalPolicy.GRANT_TTL_MS
        const val NOW = 5_000L
        const val WAIT_MS = 60_000L
        const val LONG_WAIT_MS = 600_000L
        const val CONCURRENT_CALLS = 100
    }
}
