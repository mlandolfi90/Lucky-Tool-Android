package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Number of pending action approvals for the Server screen card: the in-app path to the approval screen when
 * notifications are off (POST_NOTIFICATIONS denied) or were dismissed. Counts only, never content.
 */
@HiltViewModel
class PolicyApprovalCountViewModel
    @Inject
    constructor(
        coordinator: ActionApprovalCoordinator,
    ) : ViewModel() {
        val pendingCount: StateFlow<Int> =
            coordinator
                .observePending()
                .map { it.size }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(FLOW_TIMEOUT_MS), 0)

        private companion object {
            const val FLOW_TIMEOUT_MS = 5_000L
        }
    }
