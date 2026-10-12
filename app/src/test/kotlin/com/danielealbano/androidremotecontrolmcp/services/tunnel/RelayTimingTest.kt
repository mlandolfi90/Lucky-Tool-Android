package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.utils.Logger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RelayTiming")
class RelayTimingTest {
    @Suppress("LongParameterList")
    private fun timing(
        id: String = FRAME_ID,
        method: String = "POST",
        path: String = "/mcp",
        status: Int = 200,
        receivedAtMs: Long = 1_791_700_000_123L,
        queueMs: Long = 2L,
        replayMs: Long = 57L,
        totalMs: Long = 61L,
        sent: Boolean = true,
    ): RelayTiming =
        RelayTiming(
            id = id,
            method = method,
            path = path,
            status = status,
            receivedAtMs = receivedAtMs,
            queueMs = queueMs,
            replayMs = replayMs,
            totalMs = totalMs,
            sent = sent,
        )

    @Test
    fun `logLine uses the first 8 characters of the id`() {
        val line = timing().logLine()

        assertTrue(line.contains("rid=${FRAME_ID.take(RelayTiming.RID_LENGTH)}"), line)
        assertFalse(line.contains(FRAME_ID), line)
        assertEquals(line, Logger.sanitize(line))
    }

    @Test
    fun `logLine sanitizes the path`() {
        val query = timing(path = "/mcp?code=x").logLine()
        assertTrue(query.contains(" /mcp "), query)
        assertFalse(query.contains("code"), query)

        val capability = timing(path = "/s/tok?x").logLine()
        assertTrue(capability.contains("/s/<redacted>"), capability)
        assertFalse(capability.contains("tok"), capability)
    }

    @Test
    fun `logLine flags an unsent frame`() {
        assertTrue(timing(sent = false).logLine().endsWith(" sent=false"))
        assertFalse(timing(sent = true).logLine().contains("sent="))
    }

    @Test
    fun `logLine prints status and all timings`() {
        val line = timing(status = 503, receivedAtMs = 42L, queueMs = 1L, replayMs = 2L, totalMs = 3L).logLine()

        assertTrue(line.contains("status=503"), line)
        assertTrue(line.contains("recvAt=42"), line)
        assertTrue(line.contains("queueMs=1"), line)
        assertTrue(line.contains("replayMs=2"), line)
        assertTrue(line.contains("totalMs=3"), line)
    }

    @Test
    fun `logLine strips control characters`() {
        val newlines =
            timing(
                id = "12\n45678-aaaa-bbbb-cccc-ddddddddddddd",
                method = "GET\nX",
                path = "/mcp\r\nforged",
            ).logLine()
        assertFalse(newlines.contains("\n"), newlines)
        assertFalse(newlines.contains("\r"), newlines)
        assertTrue(newlines.contains("GET?X"), newlines)
        assertTrue(newlines.contains("/mcp??forged"), newlines)

        val unicode = timing(method = "GET\u0085X", path = "/mcp‮evil x").logLine()
        assertTrue(unicode.contains("GET?X"), unicode)
        assertTrue(unicode.contains("/mcp?evil?x"), unicode)
        assertFalse(unicode.contains("\u0085"), unicode)
        assertFalse(unicode.contains("‮"), unicode)
        assertFalse(unicode.contains(" "), unicode)
    }

    @Test
    fun `logLine cuts long paths at 128 characters`() {
        val path = "/" + "a".repeat(300)

        val line = timing(path = path).logLine()

        val token = line.substringAfter("POST ").substringBefore(" status=")
        assertEquals("${path.take(RelayTiming.MAX_PATH_CHARS)}…", token)
    }

    private companion object {
        private const val FRAME_ID = "3f2a9c1e-7b2d-4a11-9c3e-6f0a1b2c3d4e"
    }
}
