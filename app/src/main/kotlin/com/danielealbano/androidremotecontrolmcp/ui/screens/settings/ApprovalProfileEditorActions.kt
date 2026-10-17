package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.runtime.Immutable
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.TierDraft

/** Callbacks of the amount section (max amount and tiers). */
@Immutable
internal class AmountEditorActions(
    val onMaxAmountChange: (String) -> Unit,
    val onAddTier: () -> Unit,
    val onTierChange: (Int, TierDraft) -> Unit,
    val onRemoveTier: (Int) -> Unit,
)

/** Callbacks of the profile editor, built once per ViewModel so recomposition does not re-create lambdas. */
@Immutable
internal class ProfileEditorActions(
    val onRuleChange: (PolicyActionType, RuleMode) -> Unit,
    val amount: AmountEditorActions,
    val onRemoveException: (PolicyExceptionKind, PolicySelector) -> Unit,
    val onDismissRemoveError: () -> Unit,
    val onSave: () -> Unit,
)
