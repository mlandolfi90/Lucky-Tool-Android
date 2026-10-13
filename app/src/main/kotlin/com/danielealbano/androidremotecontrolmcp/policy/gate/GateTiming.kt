package com.danielealbano.androidremotecontrolmcp.policy.gate

/** Clock and NOTIFY wait of the gate; tests inject a controllable clock and a short wait. */
class GateTiming(
    val notifyWaitMs: Long,
    val nowMs: () -> Long,
) {
    companion object {
        /** How long a NOTIFY call waits for the owner before returning `awaiting_approval`. */
        const val NOTIFY_WAIT_MS = 60_000L

        val PRODUCTION = GateTiming(NOTIFY_WAIT_MS) { System.currentTimeMillis() }
    }
}
