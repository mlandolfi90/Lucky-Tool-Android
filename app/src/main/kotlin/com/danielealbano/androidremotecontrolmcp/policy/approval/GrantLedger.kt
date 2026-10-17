package com.danielealbano.androidremotecontrolmcp.policy.approval

import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode

/** Single-use, time-bound grants keyed by fingerprint. Not thread-safe: used under the coordinator's mutex. */
internal class GrantLedger {
    private class Grant(
        val strength: RuleMode,
        val expiresAtMs: Long,
    )

    private val grants = LinkedHashMap<String, Grant>()

    /** Records (or refreshes) a grant; a still-valid stronger grant keeps its strength. */
    fun grant(
        fingerprint: String,
        strength: RuleMode,
        nowMs: Long,
    ) {
        purge(nowMs)
        val previous = grants.remove(fingerprint)
        val effective =
            if (previous != null && previous.strength.isStricterThan(strength)) previous.strength else strength
        if (grants.size >= ActionApprovalPolicy.MAX_GRANTS) grants.remove(grants.keys.first())
        grants[fingerprint] = Grant(effective, nowMs + ActionApprovalPolicy.GRANT_TTL_MS)
    }

    /** True at most once per grant, and only when its strength satisfies [requiredMode]. */
    fun consume(
        fingerprint: String,
        requiredMode: RuleMode,
        nowMs: Long,
    ): Boolean {
        purge(nowMs)
        val usable = grants[fingerprint]?.let { !requiredMode.isStricterThan(it.strength) } == true
        if (usable) grants.remove(fingerprint)
        return usable
    }

    /** Drops every grant. */
    fun clear() {
        grants.clear()
    }

    private fun purge(nowMs: Long) {
        grants.values.removeAll { nowMs >= it.expiresAtMs }
    }
}
