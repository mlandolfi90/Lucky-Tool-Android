package com.danielealbano.androidremotecontrolmcp.policy.log

import com.danielealbano.androidremotecontrolmcp.data.model.ServerLogEntry
import com.danielealbano.androidremotecontrolmcp.data.repository.ServerLogRepository
import javax.inject.Inject

/**
 * The single writer of APPROVAL server-log entries. [toolName] is the un-prefixed tool name (null for owner and
 * profile events); [durationMs] is set only by the gate.
 */
interface ApprovalEventLogger {
    fun log(
        record: ApprovalLogRecord,
        toolName: String? = null,
        durationMs: Long? = null,
    )
}

/** [ApprovalEventLogger] over the server log; the message is [ApprovalLogMessage.encode] of the record. */
class ApprovalEventLoggerImpl
    @Inject
    constructor(
        private val serverLog: ServerLogRepository,
    ) : ApprovalEventLogger {
        override fun log(
            record: ApprovalLogRecord,
            toolName: String?,
            durationMs: Long?,
        ) {
            serverLog.log(
                type = ServerLogEntry.Type.APPROVAL,
                message = ApprovalLogMessage.encode(record),
                toolName = toolName,
                durationMs = durationMs,
            )
        }
    }
