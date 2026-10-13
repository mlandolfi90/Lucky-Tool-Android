package com.danielealbano.androidremotecontrolmcp.policy.approval

import com.danielealbano.androidremotecontrolmcp.mcp.oauth.ApprovalState
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.SecureRandom
import java.util.Locale

/** One ledger row; [state] is observed by [ActionApprovalCoordinator.awaitDecision]. */
internal class PendingActionEntry(
    var action: PendingAction,
    val fingerprint: String,
) {
    val state = MutableStateFlow(ApprovalState.PENDING)

    fun isPending(): Boolean = state.value == ApprovalState.PENDING

    /** Joins [request] (same fingerprint): a stricter method replaces subject, decision and method in place. */
    fun joinWith(request: PendingActionRequest): PendingAction {
        if (request.method.isStricterThan(action.method)) {
            action = action.copy(subject = request.subject, decision = request.decision, method = request.method)
        }
        return action
    }
}

/** Insertion-ordered rows. Not thread-safe: every call happens under the coordinator's mutex. */
internal class PendingActionTable(
    private val random: SecureRandom = SecureRandom(),
) {
    private val rows = LinkedHashMap<String, PendingActionEntry>()

    fun get(id: String): PendingActionEntry? = rows[id]

    fun findPendingByFingerprint(fingerprint: String): PendingActionEntry? =
        rows.values.firstOrNull { it.fingerprint == fingerprint && it.isPending() }

    fun pendingCount(): Int = rows.values.count { it.isPending() }

    fun pendingSnapshot(): List<PendingAction> = rows.values.filter { it.isPending() }.map { it.action }

    /** Adds a PENDING row for [request]; at [ActionApprovalPolicy.MAX_TRACKED_ENTRIES] the oldest decided row goes. */
    fun create(
        request: PendingActionRequest,
        nowMs: Long,
    ): PendingAction {
        val action =
            PendingAction(
                id = newId(),
                toolName = request.toolName,
                subject = request.subject,
                decision = request.decision,
                method = request.method,
                createdAtMs = nowMs,
                expiresAtMs = nowMs + ActionApprovalPolicy.APPROVAL_WINDOW_MS,
            )
        if (rows.size >= ActionApprovalPolicy.MAX_TRACKED_ENTRIES) {
            rows.values.firstOrNull { !it.isPending() }?.let { rows.remove(it.action.id) }
        }
        rows[action.id] = PendingActionEntry(action, request.fingerprint)
        return action
    }

    /** Lapsed PENDING rows become EXPIRED (waking awaiters); every lapsed row is removed. */
    fun purge(nowMs: Long) {
        val iterator = rows.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs >= entry.action.expiresAtMs) {
                if (entry.isPending()) entry.state.value = ApprovalState.EXPIRED
                iterator.remove()
            }
        }
    }

    /** Every PENDING row becomes DENIED (waking awaiters); decided rows are kept for status reads. */
    fun denyAllPending() {
        rows.values.filter { it.isPending() }.forEach { it.state.value = ApprovalState.DENIED }
    }

    /** 16 SecureRandom bytes as 32 lowercase hex chars, unique among tracked rows. */
    private fun newId(): String {
        var id: String
        do {
            id = ByteArray(ID_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
        } while (rows.containsKey(id))
        return id
    }

    private companion object {
        const val ID_BYTES = 16
    }
}
