package com.danielealbano.androidremotecontrolmcp.policy.engine

import com.danielealbano.androidremotecontrolmcp.policy.model.ProfilesSnapshot

/** Pure, synchronous rule resolution. Never logs, never suspends, never touches Android. */
interface PolicyEngine {
    fun evaluate(
        subject: PolicySubject,
        snapshot: ProfilesSnapshot,
    ): PolicyDecision
}
