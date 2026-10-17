package com.danielealbano.androidremotecontrolmcp.data.repository

import java.io.IOException

/** Whether [ApprovalProfileRepository] could read its store. */
enum class ProfileStoreStatus {
    /** No read attempted yet. */
    NOT_LOADED,

    /** The store was read; the snapshot reflects it. */
    LOADED,

    /** The last read failed: [ApprovalProfileRepository.current] throws and retries on the next call. */
    UNREADABLE,
}

/**
 * Thrown by [ApprovalProfileRepository.current] while the approval-profile store cannot be read at all. Fail-closed
 * callers treat it as "every non-READ action is forbidden"; it never means "no profiles".
 */
class ProfileStoreUnreadableException(
    cause: IOException,
) : IOException("The approval profile store cannot be read", cause)
