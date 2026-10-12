package com.danielealbano.androidremotecontrolmcp.services.tunnel

import com.danielealbano.androidremotecontrolmcp.utils.Logger
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.http.HttpStatusCode
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Why a relay session ended; `replaced` is the relay's close code 4000 (another device took the slot). */
internal data class RelaySessionEnd(
    val reason: String,
    val replaced: Boolean,
)

/**
 * Pumps one open device socket: answers pings, replays request frames concurrently (bounded by [replaySlots])
 * and ends when the relay closes the socket or stays silent for [idleTimeoutMs]. In-flight replays are
 * cancelled when the socket goes away; the Close frame is sent even if the caller is being cancelled.
 * Frame text (UTF-8 and JSON) is decoded and encoded on the Default dispatcher: bodies reach several MiB of
 * base64.
 * Each request frame whose replay completes logs one info line built by [RelayTiming] (none when cancelled).
 */
internal class RelaySession(
    private val session: DefaultClientWebSocketSession,
    private val localPort: Int,
    private val replayer: LocalRequestReplayer,
    private val replaySlots: Semaphore,
    private val idleTimeoutMs: Long,
) {
    suspend fun pump(): RelaySessionEnd {
        val context = currentCoroutineContext()
        val replayScope = CoroutineScope(context + SupervisorJob(context[Job]))
        var end: RelaySessionEnd? = null
        try {
            while (end == null) {
                val received = withTimeoutOrNull(idleTimeoutMs) { session.incoming.receiveCatching() }
                val frame = received?.getOrNull()
                when {
                    received == null -> end = RelaySessionEnd("Relay silent for $idleTimeoutMs ms", replaced = false)
                    frame == null -> end = closedEnd()
                    frame is Frame.Text -> handleText(replayScope, frame)
                }
            }
        } finally {
            replayScope.cancel()
            withContext(NonCancellable) {
                withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                    session.close(CloseReason(CloseReason.Codes.NORMAL, "device closing"))
                }
            }
        }
        return end
    }

    private suspend fun closedEnd(): RelaySessionEnd {
        val reason = withTimeoutOrNull(CLOSE_TIMEOUT_MS) { session.closeReason.await() }
        return when {
            reason == null -> {
                RelaySessionEnd("Relay connection closed", replaced = false)
            }

            reason.code == CLOSE_CODE_REPLACED -> {
                RelaySessionEnd(REPLACED_MESSAGE, replaced = true)
            }

            else -> {
                val detail = "code ${reason.code}: ${reason.message}"
                RelaySessionEnd("Relay connection closed ($detail)", replaced = false)
            }
        }
    }

    private suspend fun handleText(
        replayScope: CoroutineScope,
        frame: Frame.Text,
    ) {
        val receivedNs = System.nanoTime()
        val receivedAtMs = System.currentTimeMillis()
        when (val inbound = withContext(Dispatchers.Default) { RelayFrameCodec.decode(frame.readText()) }) {
            is RelayInbound.Request -> replayScope.launch { answer(inbound.frame, receivedNs, receivedAtMs) }
            RelayInbound.Ping -> replayScope.launch { sendFrame(Frame.Text(RelayFrameCodec.encodePong())) }
            RelayInbound.Pong -> Unit
            is RelayInbound.Unknown -> Logger.w(TAG, "Ignoring relay frame of unknown type '${inbound.type}'")
            is RelayInbound.Malformed -> Logger.w(TAG, "Ignoring malformed relay frame: ${inbound.reason}")
        }
    }

    private suspend fun answer(
        frame: RelayRequestFrame,
        receivedNs: Long,
        receivedAtMs: Long,
    ) {
        val replayStartNs = System.nanoTime()
        val response =
            if (replaySlots.tryAcquire()) {
                try {
                    replayer.replay(frame, localPort)
                } finally {
                    replaySlots.release()
                }
            } else {
                Logger.w(TAG, "Relay request refused: all replay slots are busy")
                replayer.errorFrame(frame.id, HttpStatusCode.ServiceUnavailable, "device busy")
            }
        val replayEndNs = System.nanoTime()
        val sent = sendFrame(withContext(Dispatchers.Default) { Frame.Text(RelayFrameCodec.encode(response)) })
        val timing =
            RelayTiming(
                id = frame.id,
                method = frame.method,
                path = frame.path,
                status = response.status,
                receivedAtMs = receivedAtMs,
                queueMs = (replayStartNs - receivedNs) / NANOS_PER_MILLI,
                replayMs = (replayEndNs - replayStartNs) / NANOS_PER_MILLI,
                totalMs = (System.nanoTime() - receivedNs) / NANOS_PER_MILLI,
                sent = sent,
            )
        Logger.i(TAG, timing.logLine())
    }

    /** `false` when the socket was already closed or the write failed (logged here). */
    private suspend fun sendFrame(frame: Frame.Text): Boolean =
        try {
            session.send(frame)
            true
        } catch (e: ClosedSendChannelException) {
            Logger.w(TAG, "Relay socket closed before the frame could be sent", e)
            false
        } catch (e: IOException) {
            Logger.w(TAG, "Failed to send frame to relay", e)
            false
        }

    companion object {
        private const val TAG = "MCP:RelaySession"
        private const val CLOSE_TIMEOUT_MS = 2_000L
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val REPLACED_MESSAGE = "Relay replaced this device with another connection"

        /** Relay close code: another authenticated device connection took this device's slot. */
        internal const val CLOSE_CODE_REPLACED: Short = 4000
    }
}
