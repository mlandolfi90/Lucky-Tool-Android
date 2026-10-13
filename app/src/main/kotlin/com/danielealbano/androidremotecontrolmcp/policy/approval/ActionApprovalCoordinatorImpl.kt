package com.danielealbano.androidremotecontrolmcp.policy.approval

import com.danielealbano.androidremotecontrolmcp.policy.engine.PolicyDecision
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory, thread-safe [ActionApprovalCoordinator] (same shape as `OAuthApprovalCoordinatorImpl`): one [Mutex],
 * insertion-ordered rows, expiry wins, unknown id = EXPIRED. Unlike OAuth, a full ledger refuses the NEW request
 * instead of dropping the oldest, so no waiting money action is cancelled silently. No critical section suspends.
 */
@Singleton
class ActionApprovalCoordinatorImpl
    @Inject
    constructor() : ActionApprovalCoordinator {
        private val mutex = Mutex()
        private val table = PendingActionTable()
        private val grants = GrantLedger()
        private val pending = MutableStateFlow<List<PendingAction>>(emptyList())

        override fun observePending(): StateFlow<List<PendingAction>> = pending.asStateFlow()

        override suspend fun createOrJoin(
            request: PendingActionRequest,
            nowMs: Long,
        ): PendingAction {
            require(request.method in ActionApprovalPolicy.APPROVAL_METHODS) {
                "Only ASK_IN_CHAT and NOTIFY create pending approvals"
            }
            return mutex.withLock {
                table.purge(nowMs)
                val existing = table.findPendingByFingerprint(request.fingerprint)
                val action =
                    when {
                        existing != null -> existing.joinWith(request)
                        table.pendingCount() >= ActionApprovalPolicy.MAX_PENDING -> null
                        else -> table.create(request, nowMs)
                    }
                pending.value = table.pendingSnapshot()
                action ?: throw TooManyPendingActionsException()
            }
        }

        override suspend fun awaitDecision(
            id: String,
            timeoutMs: Long,
        ): ActionApprovalState {
            val state = mutex.withLock { table.get(id)?.state } ?: return ActionApprovalState.EXPIRED
            return withTimeoutOrNull(timeoutMs.coerceAtLeast(0L)) {
                state.first { it != ActionApprovalState.PENDING }
            } ?: state.value
        }

        override suspend fun approve(
            id: String,
            nowMs: Long,
        ): ActionApprovalState =
            mutex.withLock {
                val entry = table.get(id)
                val state =
                    when {
                        entry == null -> ActionApprovalState.EXPIRED
                        !entry.isPending() -> entry.state.value
                        else -> entry.approveWithin(grants, ActionApprovalPolicy.OWNER_DEVICE_GRANT_STRENGTH, nowMs)
                    }
                pending.value = table.pendingSnapshot()
                state
            }

        override suspend fun confirmInChat(
            id: String,
            nowMs: Long,
        ): ChatConfirmResult =
            mutex.withLock {
                val entry = table.get(id)
                val result =
                    when {
                        entry == null -> ChatConfirmResult.EXPIRED
                        entry.action.method != RuleMode.ASK_IN_CHAT -> ChatConfirmResult.NOT_CONFIRMABLE
                        else -> entry.confirmFromChat(grants, nowMs)
                    }
                pending.value = table.pendingSnapshot()
                result
            }

        override suspend fun deny(id: String) {
            mutex.withLock {
                table
                    .get(id)
                    ?.takeIf { it.isPending() }
                    ?.let { it.state.value = ActionApprovalState.DENIED }
                pending.value = table.pendingSnapshot()
            }
        }

        override suspend fun stateOf(
            id: String,
            nowMs: Long,
        ): ActionApprovalState = statusOf(id, nowMs).state

        override suspend fun statusOf(
            id: String,
            nowMs: Long,
        ): ApprovalStatusView =
            mutex.withLock {
                val entry = table.get(id) ?: return@withLock ApprovalStatusView(ActionApprovalState.EXPIRED, null)
                if (entry.isPending() && nowMs >= entry.action.expiresAtMs) {
                    entry.state.value = ActionApprovalState.EXPIRED
                    pending.value = table.pendingSnapshot()
                }
                val state = entry.state.value
                ApprovalStatusView(state, entry.action.expiresAtMs.takeIf { state == ActionApprovalState.PENDING })
            }

        override suspend fun consumeGrant(
            fingerprint: String,
            requiredMode: RuleMode,
            nowMs: Long,
        ): Boolean =
            mutex.withLock {
                grants.consume(fingerprint, requiredMode, nowMs).also { consumed ->
                    if (consumed) table.markGrantUsed(fingerprint)
                }
            }

        override suspend fun applyReevaluation(
            id: String,
            decision: PolicyDecision,
            nowMs: Long,
        ): ReevaluationOutcome =
            mutex.withLock {
                table.purge(nowMs)
                val entry = table.get(id)?.takeIf { it.isPending() }
                val outcome =
                    when {
                        entry == null -> {
                            ReevaluationOutcome.NOT_PENDING
                        }

                        decision.mode in ActionApprovalPolicy.APPROVAL_METHODS -> {
                            entry.action = entry.action.copy(decision = decision, method = decision.mode)
                            ReevaluationOutcome.STILL_PENDING
                        }

                        decision.mode == RuleMode.RUN -> {
                            entry.state.value = ActionApprovalState.NOT_REQUIRED
                            ReevaluationOutcome.NO_LONGER_NEEDS_APPROVAL
                        }

                        else -> {
                            entry.state.value = ActionApprovalState.DENIED
                            ReevaluationOutcome.NOW_FORBIDDEN
                        }
                    }
                pending.value = table.pendingSnapshot()
                outcome
            }

        override suspend fun clearAll() {
            mutex.withLock {
                table.denyAllPending()
                grants.clear()
                pending.value = table.pendingSnapshot()
            }
        }
    }

/** Expiry wins over a late approval: a grant is never handed out past the window. Caller holds the mutex. */
private fun PendingActionEntry.approveWithin(
    grants: GrantLedger,
    strength: RuleMode,
    nowMs: Long,
): ActionApprovalState {
    val approved = nowMs < action.expiresAtMs
    state.value = if (approved) ActionApprovalState.APPROVED else ActionApprovalState.EXPIRED
    if (approved) grants.grant(fingerprint, strength, nowMs)
    return state.value
}

/** Chat confirmation of an ASK_IN_CHAT entry in any state. Caller holds the mutex. */
private fun PendingActionEntry.confirmFromChat(
    grants: GrantLedger,
    nowMs: Long,
): ChatConfirmResult =
    when (state.value) {
        ActionApprovalState.PENDING -> {
            val approved = approveWithin(grants, ActionApprovalPolicy.CHAT_GRANT_STRENGTH, nowMs)
            if (approved == ActionApprovalState.APPROVED) ChatConfirmResult.CONFIRMED else ChatConfirmResult.EXPIRED
        }

        ActionApprovalState.APPROVED -> {
            ChatConfirmResult.ALREADY_APPROVED
        }

        ActionApprovalState.DENIED -> {
            ChatConfirmResult.DENIED
        }

        ActionApprovalState.EXPIRED -> {
            ChatConfirmResult.EXPIRED
        }

        ActionApprovalState.NOT_REQUIRED -> {
            ChatConfirmResult.NOT_REQUIRED
        }

        ActionApprovalState.ALREADY_USED -> {
            ChatConfirmResult.ALREADY_USED
        }
    }
