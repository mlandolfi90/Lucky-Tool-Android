package com.danielealbano.androidremotecontrolmcp.ui.viewmodels

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danielealbano.androidremotecontrolmcp.di.IoDispatcher
import com.danielealbano.androidremotecontrolmcp.policy.amount.AmountParser
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalCoordinator
import com.danielealbano.androidremotecontrolmcp.policy.approval.ActionApprovalState
import com.danielealbano.androidremotecontrolmcp.policy.approval.ExceptionEligibility
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingAction
import com.danielealbano.androidremotecontrolmcp.policy.approval.PendingReevaluator
import com.danielealbano.androidremotecontrolmcp.policy.approval.ReevaluationOutcome
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalEventLogger
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogOutcome
import com.danielealbano.androidremotecontrolmcp.policy.log.ApprovalLogRecord
import com.danielealbano.androidremotecontrolmcp.policy.log.toLogRecord
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.services.apps.AppIconCache
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the action-approval screen: Approve / Deny, and owner-confirmed exceptions that only trigger a
 * re-evaluation ([PendingReevaluator]); the exception path never calls [ActionApprovalCoordinator.approve].
 * Logs owner approve / deny (late ones as `expired`) and `exception_added` through [ApprovalEventLogger]: codes
 * and the package only, never the amount, the field label or the selector.
 */
@HiltViewModel
class PolicyApprovalViewModel
    @Inject
    constructor(
        private val coordinator: ActionApprovalCoordinator,
        private val reevaluator: PendingReevaluator,
        private val appIconCache: AppIconCache,
        private val events: ApprovalEventLogger,
        @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val confirmation = MutableStateFlow<ExceptionConfirmation?>(null)
        private val outcome = MutableStateFlow<ReevaluationOutcome?>(null)

        val uiState: StateFlow<PolicyApprovalUiState> =
            combine(
                coordinator.observePending().map { pending -> pending.map(::toCard) }.flowOn(ioDispatcher),
                confirmation,
                outcome,
            ) { cards, pendingConfirmation, lastOutcome ->
                PolicyApprovalUiState(cards, pendingConfirmation, lastOutcome)
            }.stateIn(viewModelScope, SharingStarted.Eagerly, PolicyApprovalUiState())

        /** Owner approval; logged as `owner_approved`, or as `expired` when the window had already closed. */
        fun approve(id: String) {
            viewModelScope.launch(ioDispatcher) {
                val action = pendingAction(id)
                if (action != null) {
                    when (coordinator.approve(id, System.currentTimeMillis())) {
                        ActionApprovalState.APPROVED -> logDecision(action, ApprovalLogOutcome.OWNER_APPROVED)

                        ActionApprovalState.EXPIRED -> logDecision(action, ApprovalLogOutcome.EXPIRED)

                        // Decided or closed elsewhere before this approval: nothing to log.
                        ActionApprovalState.PENDING,
                        ActionApprovalState.DENIED,
                        ActionApprovalState.NOT_REQUIRED,
                        ActionApprovalState.ALREADY_USED,
                        -> Unit
                    }
                }
            }
        }

        /** Owner denial; logged as `owner_denied` only when the entry really ended DENIED (not approved meanwhile). */
        fun deny(id: String) {
            viewModelScope.launch(ioDispatcher) {
                val action = pendingAction(id)
                if (action != null) {
                    coordinator.deny(id)
                    if (coordinator.stateOf(id, System.currentTimeMillis()) == ActionApprovalState.DENIED) {
                        logDecision(action, ApprovalLogOutcome.OWNER_DENIED)
                    }
                }
            }
        }

        /** First tap on an exception button: opens the confirmation dialog only if the card offers [kind]. */
        fun requestException(
            id: String,
            kind: PolicyExceptionKind,
        ) {
            val card = uiState.value.cards.firstOrNull { it.id == id }
            val offered =
                when (kind) {
                    PolicyExceptionKind.NOT_AMOUNT_FIELD -> card?.canMarkNotAmount == true
                    PolicyExceptionKind.NOT_ACTION_BUTTON -> card?.canMarkNotActionButton == true
                }
            if (card != null && offered) {
                confirmation.value = ExceptionConfirmation(id, kind, card.appName ?: card.packageName, card.fieldLabel)
            }
        }

        fun dismissException() {
            confirmation.value = null
        }

        /** Second confirmation: stores the exception and re-evaluates through [PendingReevaluator]; never approves. */
        fun confirmException() {
            val confirmed = confirmation.value ?: return
            confirmation.value = null
            viewModelScope.launch(ioDispatcher) {
                val action = pendingAction(confirmed.pendingId)
                val result = reevaluator.addExceptionAndReevaluate(confirmed.pendingId, confirmed.kind)
                // Stored is what counts, even when the entry was decided or lapsed before the re-evaluation.
                if (action != null && result.exceptionStored) {
                    events.log(
                        ApprovalLogRecord(
                            outcome = ApprovalLogOutcome.EXCEPTION_ADDED,
                            actionType = action.decision.actionType,
                            packageName = action.decision.packageName,
                        ),
                    )
                }
                outcome.value = result.outcome
            }
        }

        fun outcomeShown() {
            outcome.value = null
        }

        fun iconFor(packageName: String): Bitmap? = appIconCache[packageName]

        private fun pendingAction(id: String): PendingAction? =
            coordinator
                .observePending()
                .value
                .firstOrNull { it.id == id }

        /** Owner events carry no tool name and no duration (US5's `ApprovalEventLogger` contract). */
        private fun logDecision(
            action: PendingAction,
            logOutcome: ApprovalLogOutcome,
        ) {
            events.log(action.decision.toLogRecord(logOutcome))
        }

        private fun toCard(action: PendingAction): PolicyApprovalCard {
            val packageName = action.decision.packageName
            val target = ExceptionEligibility.target(action)
            // Mirrors the engine: a password field never counts as an amount, so its value is never shown.
            val scan =
                action.subject.amountText
                    ?.takeUnless { target?.isPassword == true }
                    ?.let(AmountParser::scan)
            return PolicyApprovalCard(
                id = action.id,
                packageName = packageName,
                appName = packageName?.let(::appNameFor),
                actionType = action.decision.actionType,
                toolName = action.toolName,
                method = action.method,
                reason = action.decision.reason,
                amount = scan?.max?.toPlainString(),
                amountUnreadable = scan?.exceedsLimits == true,
                fieldLabel = target?.nodeLabel,
                expiresAtMs = action.expiresAtMs,
                canMarkNotAmount =
                    ExceptionEligibility.selectorFor(action, PolicyExceptionKind.NOT_AMOUNT_FIELD) != null,
                canMarkNotActionButton =
                    ExceptionEligibility.selectorFor(action, PolicyExceptionKind.NOT_ACTION_BUTTON) != null,
            )
        }

        private fun appNameFor(packageName: String): String? =
            appIconCache.getLaunchableApps().firstOrNull { it.first == packageName }?.second
    }
