package com.danielealbano.androidremotecontrolmcp.ui.screens

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.policy.approval.ReevaluationOutcome
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.ui.components.PolicyApprovalCardView
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ExceptionConfirmation
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.PolicyApprovalCard
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.PolicyApprovalViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PolicyApprovalScreen(
    viewModel: PolicyApprovalViewModel = hiltViewModel(),
    onAllHandled: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    AutoCloseWhenHandled(
        hasCards = state.cards.isNotEmpty(),
        outcomePending = state.outcome != null,
        onAllHandled = onAllHandled,
    )
    OutcomeSnackbar(outcome = state.outcome, snackbarHostState = snackbarHostState, onShown = viewModel::outcomeShown)
    state.confirmation?.let { confirmation ->
        ExceptionConfirmationDialog(
            confirmation = confirmation,
            onConfirm = viewModel::confirmException,
            onDismiss = viewModel::dismissException,
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.policy_approval_title)) },
                windowInsets = WindowInsets(0),
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        if (state.cards.isEmpty()) {
            Text(
                text = stringResource(R.string.policy_approval_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        } else {
            PendingCardList(
                cards = state.cards,
                iconFor = viewModel::iconFor,
                onApprove = viewModel::approve,
                onDeny = viewModel::deny,
                onException = viewModel::requestException,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
private fun PendingCardList(
    cards: List<PolicyApprovalCard>,
    iconFor: (packageName: String) -> Bitmap?,
    onApprove: (id: String) -> Unit,
    onDeny: (id: String) -> Unit,
    onException: (id: String, kind: PolicyExceptionKind) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(cards, key = { it.id }) { card ->
            PolicyApprovalCardView(
                card = card,
                icon = card.packageName?.let(iconFor),
                onDecision = { approve -> if (approve) onApprove(card.id) else onDeny(card.id) },
                onException = { kind -> onException(card.id, kind) },
            )
        }
    }
}

/** Closes once every request was handled, but not while an outcome message is still to be shown. */
@Composable
private fun AutoCloseWhenHandled(
    hasCards: Boolean,
    outcomePending: Boolean,
    onAllHandled: () -> Unit,
) {
    var hadCards by remember { mutableStateOf(false) }
    LaunchedEffect(hasCards, outcomePending) {
        if (hasCards) {
            hadCards = true
        } else if (hadCards && !outcomePending) {
            onAllHandled()
        }
    }
}

@Composable
private fun OutcomeSnackbar(
    outcome: ReevaluationOutcome?,
    snackbarHostState: SnackbarHostState,
    onShown: () -> Unit,
) {
    val message = outcome?.let { stringResource(outcomeMessage(it)) }
    LaunchedEffect(message) {
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            onShown()
        }
    }
}

@StringRes
private fun outcomeMessage(outcome: ReevaluationOutcome): Int =
    when (outcome) {
        ReevaluationOutcome.STILL_PENDING -> R.string.policy_approval_outcome_still_pending
        ReevaluationOutcome.NO_LONGER_NEEDS_APPROVAL -> R.string.policy_approval_outcome_no_longer_needs_approval
        ReevaluationOutcome.NOW_FORBIDDEN -> R.string.policy_approval_outcome_now_forbidden
        ReevaluationOutcome.NOT_PENDING -> R.string.policy_approval_outcome_not_pending
        ReevaluationOutcome.NOT_ELIGIBLE -> R.string.policy_approval_outcome_not_eligible
    }

@Composable
private fun ExceptionConfirmationDialog(
    confirmation: ExceptionConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val app = confirmation.appName ?: stringResource(R.string.policy_approval_unknown_app)
    val target = confirmation.targetLabel ?: stringResource(R.string.policy_approval_exception_unnamed_target)
    val (titleRes, bodyRes) =
        when (confirmation.kind) {
            PolicyExceptionKind.NOT_AMOUNT_FIELD -> {
                R.string.policy_approval_exception_not_amount_title to
                    R.string.policy_approval_exception_not_amount_body
            }

            PolicyExceptionKind.NOT_ACTION_BUTTON -> {
                R.string.policy_approval_exception_not_action_button_title to
                    R.string.policy_approval_exception_not_action_button_body
            }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(bodyRes, app, target)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp)) {
                Text(stringResource(R.string.policy_approval_exception_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp)) {
                Text(stringResource(R.string.policy_approval_exception_cancel))
            }
        },
    )
}

private const val MIN_TOUCH_DP = 48
