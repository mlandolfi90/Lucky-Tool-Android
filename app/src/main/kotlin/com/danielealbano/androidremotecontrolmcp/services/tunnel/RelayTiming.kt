package com.danielealbano.androidremotecontrolmcp.services.tunnel

/**
 * One relayed request as measured on the device, logged by [RelaySession] under `MCP:RelaySession`.
 * Only scalars: never headers, body, query string or exception text.
 * - [queueMs]: frame received → replay started (UTF-8/JSON decode + dispatch).
 * - [replayMs]: loopback replay (auth, MCP SDK, tool, response packing); about 0 for the busy 503.
 * - [totalMs]: frame received → response handed to the socket's outgoing queue (wire transmission excluded).
 * - [receivedAtMs]: wall clock at receipt; comparable with the relay's `sentAt` only up to the clock skew.
 */
internal data class RelayTiming(
    val id: String,
    val method: String,
    val path: String,
    val status: Int,
    val receivedAtMs: Long,
    val queueMs: Long,
    val replayMs: Long,
    val totalMs: Long,
    val sent: Boolean,
) {
    /**
     * `rid` is the id's first [RID_LENGTH] characters, as on the relay: `Logger` redacts a full UUID. Frame-supplied
     * text (id, method, path) goes through [logSafe], so a malicious relay cannot forge logcat lines or reorder the
     * line on screen, and the path is cut at [MAX_PATH_CHARS] like the relay's.
     */
    fun logLine(): String {
        val cleanPath = logSafe(LocalRequestReplayer.sanitizedPath(path))
        val shownPath = if (cleanPath.length > MAX_PATH_CHARS) "${cleanPath.take(MAX_PATH_CHARS)}…" else cleanPath
        val line =
            "Relay request rid=${logSafe(id.take(RID_LENGTH))} ${logSafe(method)} $shownPath " +
                "status=$status recvAt=$receivedAtMs queueMs=$queueMs replayMs=$replayMs totalMs=$totalMs"
        return if (sent) line else "$line sent=false"
    }

    companion object {
        const val RID_LENGTH = 8
        const val MAX_PATH_CHARS = 128
        private val CONTROL_CHARS = Regex("[\\p{Cc}\\p{Zl}\\p{Zp}\\u202A-\\u202E\\u2066-\\u2069]")

        /**
         * Replaces with `?` every Unicode control character (C0 and C1: CR, LF, tab, U+0085 NEL, U+009B CSI, ...),
         * U+2028 / U+2029 and the bidi overrides and isolates (U+202A-U+202E, U+2066-U+2069).
         */
        internal fun logSafe(value: String): String = CONTROL_CHARS.replace(value, "?")
    }
}
