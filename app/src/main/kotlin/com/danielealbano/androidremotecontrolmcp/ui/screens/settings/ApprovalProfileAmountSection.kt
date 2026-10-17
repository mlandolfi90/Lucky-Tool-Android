package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyLimits
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileValidationError
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ProfileDraft
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.TierDraft
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.amountReadingOrNull
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.isValidAmountInput

@Composable
internal fun AmountSection(
    draft: ProfileDraft,
    errors: List<ProfileValidationError>,
    actions: AmountEditorActions,
) {
    val maxError = errors.firstOrNull { it in maxAmountFieldErrors }
    val labelRes =
        if (draft.template == ProfileTemplate.MONEY_APP) {
            R.string.approval_profile_max_amount_required_label
        } else {
            R.string.approval_profile_max_amount_label
        }
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft.maxAmountText,
            onValueChange = actions.onMaxAmountChange,
            label = { Text(stringResource(labelRes)) },
            supportingText = { Text(maxAmountSupportingText(maxError, amountReadingOrNull(draft.maxAmountText))) },
            isError = maxError != null,
            keyboardOptions = amountKeyboard,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.approval_profile_tiers_explanation),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        draft.tiers.forEachIndexed { index, tier -> TierRow(index = index, tier = tier, actions = actions) }
        AddTierButton(tierCount = draft.tiers.size, onAddTier = actions.onAddTier)
    }
}

@Composable
private fun AddTierButton(
    tierCount: Int,
    onAddTier: () -> Unit,
) {
    val canAdd = tierCount < PolicyLimits.MAX_AMOUNT_TIERS
    TextButton(onClick = onAddTier, enabled = canAdd) {
        Icon(Icons.Default.Add, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.approval_profile_add_tier))
    }
    if (!canAdd) {
        Text(
            text = stringResource(R.string.approval_profile_tiers_max_reached, PolicyLimits.MAX_AMOUNT_TIERS),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TierRow(
    index: Int,
    tier: TierDraft,
    actions: AmountEditorActions,
) {
    val position = index + 1
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = tier.amountText,
                onValueChange = { actions.onTierChange(index, tier.copy(amountText = it)) },
                label = { Text(stringResource(R.string.approval_profile_tier_up_to, position)) },
                isError = !isValidAmountInput(tier.amountText),
                supportingText =
                    amountReadingOrNull(tier.amountText)?.let { reading ->
                        { Text(stringResource(R.string.approval_profile_amount_reads_as, reading)) }
                    },
                keyboardOptions = amountKeyboard,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { actions.onRemoveTier(index) }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.approval_profile_remove_tier_cd, position),
                )
            }
        }
        RuleModeDropdown(
            selected = tier.mode,
            onSelect = { actions.onTierChange(index, tier.copy(mode = it)) },
            fieldLabel = stringResource(R.string.approval_profile_tier_label, position),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The field error, else what a valid input is stored as when it differs from the typed text, else the hint. */
@Composable
private fun maxAmountSupportingText(
    error: ProfileValidationError?,
    reading: String?,
): String =
    when {
        error != null -> stringResource(error.messageRes())
        reading != null -> stringResource(R.string.approval_profile_amount_reads_as, reading)
        else -> stringResource(R.string.approval_profile_max_amount_hint)
    }

private val maxAmountFieldErrors =
    setOf(
        ProfileValidationError.MAX_AMOUNT_REQUIRED,
        ProfileValidationError.MAX_AMOUNT_INVALID,
        ProfileValidationError.TIERS_REQUIRE_MAX_AMOUNT,
    )

/** Decimal keyboard; the input rules (dot only, up to 8 decimals, no grouping) are enforced by validation. */
private val amountKeyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal, autoCorrectEnabled = false)
