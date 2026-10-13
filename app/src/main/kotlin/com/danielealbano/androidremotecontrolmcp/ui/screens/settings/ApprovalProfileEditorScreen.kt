package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.policy.approval.PolicyApprovalLabels
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyActionType
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileValidationError
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ApprovalProfileEditorViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ProfileDraft
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ProfileEditorUiState
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.SaveStatus

/** [template] non-null = new unsaved profile from that template; null = edit the stored profile. */
@Composable
fun ApprovalProfileEditorScreen(
    packageName: String,
    template: ProfileTemplate?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ApprovalProfileEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actions =
        remember(viewModel) {
            ProfileEditorActions(
                onRuleChange = viewModel::setRule,
                amount =
                    AmountEditorActions(
                        onMaxAmountChange = viewModel::setMaxAmount,
                        onAddTier = viewModel::addTier,
                        onTierChange = viewModel::setTier,
                        onRemoveTier = viewModel::removeTier,
                    ),
                onRemoveException = viewModel::removeException,
                onDismissRemoveError = viewModel::dismissRemoveError,
                onSave = viewModel::save,
            )
        }

    LaunchedEffect(packageName, template) { viewModel.load(packageName, template) }
    LaunchedEffect(state.saveStatus) {
        if (state.saveStatus == SaveStatus.SAVED) onBack()
    }

    Column(modifier = modifier.fillMaxSize()) {
        EditorTopBar(isNew = state.isNew, onBack = onBack)
        EditorBody(state = state, actions = actions, modifier = Modifier.weight(1f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorTopBar(
    isNew: Boolean,
    onBack: () -> Unit,
) {
    TopAppBar(
        title = {
            val titleRes = if (isNew) R.string.approval_profile_new_title else R.string.approval_profile_edit_title
            Text(stringResource(titleRes))
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.approval_profiles_back),
                )
            }
        },
        windowInsets = WindowInsets(0),
    )
}

@Composable
private fun EditorBody(
    state: ProfileEditorUiState,
    actions: ProfileEditorActions,
    modifier: Modifier = Modifier,
) {
    val draft = state.draft
    when {
        state.packageName.isEmpty() -> Unit
        !state.loaded -> CircularProgressIndicator(modifier = modifier.padding(16.dp))
        draft != null -> EditorContent(state = state, draft = draft, actions = actions, modifier = modifier)
        state.storeUnreadable -> EditorMessage(R.string.approval_profile_store_unreadable, modifier)
        state.unreadable -> EditorMessage(R.string.approval_profile_unreadable, modifier)
        else -> EditorMessage(R.string.approval_profile_not_found, modifier)
    }
}

@Composable
private fun EditorMessage(
    @StringRes messageRes: Int,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(messageRes),
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier.padding(16.dp),
    )
}

@Composable
private fun EditorContent(
    state: ProfileEditorUiState,
    draft: ProfileDraft,
    actions: ProfileEditorActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        ProfileHeader(draft = draft, isNew = state.isNew)
        SectionTitle(text = stringResource(R.string.approval_profile_rules_title))
        RulesSection(rules = draft.rules, onRuleChange = actions.onRuleChange)
        SectionTitle(text = stringResource(R.string.approval_profile_amount_title))
        AmountSection(draft = draft, errors = state.errors, actions = actions.amount)
        ValidationErrorsCard(errors = state.errors)
        SectionTitle(text = stringResource(R.string.approval_profile_exceptions_title))
        if (state.removeFailed) {
            DismissibleError(
                message = stringResource(R.string.approval_profile_exception_remove_failed),
                onDismiss = actions.onDismissRemoveError,
            )
        }
        ExceptionsSection(
            notAmountFields = state.notAmountFields,
            notActionButtons = state.notActionButtons,
            onRemove = actions.onRemoveException,
        )
        SaveSection(canSave = state.canSave, saveStatus = state.saveStatus, onSave = actions.onSave)
    }
}

@Composable
private fun ProfileHeader(
    draft: ProfileDraft,
    isNew: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = draft.packageName, style = MaterialTheme.typography.titleMedium)
        Text(
            text = stringResource(R.string.approval_profile_template_line, stringResource(draft.template.labelRes())),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isNew) {
            Text(
                text = stringResource(R.string.approval_profile_new_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RulesSection(
    rules: Map<PolicyActionType, RuleMode>,
    onRuleChange: (PolicyActionType, RuleMode) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        PolicyActionType.entries.forEach { type ->
            RuleRow(type = type, mode = rules[type], onSelect = { mode -> onRuleChange(type, mode) })
        }
    }
}

@Composable
private fun RuleRow(
    type: PolicyActionType,
    mode: RuleMode?,
    onSelect: (RuleMode) -> Unit,
) {
    val typeLabel = stringResource(PolicyApprovalLabels.actionType(type))
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = typeLabel, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = stringResource(type.descriptionRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        RuleModeDropdown(
            selected = mode,
            onSelect = onSelect,
            fieldLabel = typeLabel,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun ValidationErrorsCard(errors: List<ProfileValidationError>) {
    if (errors.isNotEmpty()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.approval_profile_errors_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                errors.forEach { error ->
                    Text(
                        text = stringResource(R.string.approval_profile_error_item, stringResource(error.messageRes())),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun SaveSection(
    canSave: Boolean,
    saveStatus: SaveStatus,
    onSave: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
        saveStatus.failureMessageRes()?.let { messageRes ->
            Text(
                text = stringResource(messageRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Button(onClick = onSave, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.approval_profile_save))
        }
    }
}
