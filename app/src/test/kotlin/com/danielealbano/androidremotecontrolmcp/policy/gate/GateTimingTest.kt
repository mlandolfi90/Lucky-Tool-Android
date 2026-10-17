package com.danielealbano.androidremotecontrolmcp.policy.gate

import com.danielealbano.androidremotecontrolmcp.mcp.tools.MAX_STATUS_WAIT_MS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GateTimingTest {
    @Test
    fun `notify wait stays at 25 seconds, below the client timeout seen on a device`() {
        assertEquals(25_000L, GateTiming.NOTIFY_WAIT_MS)
    }

    @Test
    fun `status long poll cap is not longer than the notify wait`() {
        assertTrue(MAX_STATUS_WAIT_MS <= GateTiming.NOTIFY_WAIT_MS)
    }
}
