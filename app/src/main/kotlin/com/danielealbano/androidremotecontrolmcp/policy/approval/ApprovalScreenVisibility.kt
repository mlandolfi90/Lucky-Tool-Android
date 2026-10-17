package com.danielealbano.androidremotecontrolmcp.policy.approval

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the owner's approval screen is on screen. An approved NOTIFY call waits for it to close before its
 * re-check, so the re-check reads the app the call targets and not the approval screen on top of it.
 */
interface ApprovalScreenVisibility {
    fun onShown()

    fun onHidden()

    /** Suspends until no approval screen is visible or [timeoutMs] passes; true when none is visible. */
    suspend fun awaitHidden(timeoutMs: Long): Boolean
}

@Singleton
class ApprovalScreenVisibilityImpl
    @Inject
    constructor() : ApprovalScreenVisibility {
        private val visible = MutableStateFlow(0)

        override fun onShown() {
            visible.update { it + 1 }
        }

        override fun onHidden() {
            visible.update { (it - 1).coerceAtLeast(0) }
        }

        override suspend fun awaitHidden(timeoutMs: Long): Boolean {
            if (visible.value == 0) return true
            val hidden = withTimeoutOrNull(timeoutMs) { visible.first { it == 0 } } != null
            // The screen just closed: give the accessibility window list time to show the app underneath.
            if (hidden) delay(WINDOW_SETTLE_MS)
            return hidden
        }

        internal companion object {
            const val WINDOW_SETTLE_MS = 250L
        }
    }
