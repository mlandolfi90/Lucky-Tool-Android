package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyException
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicySelector
import com.danielealbano.androidremotecontrolmcp.policy.model.SelectorKind
import java.text.DateFormat
import java.util.Date

/** Learned exceptions, shown as display-only data (labels come from other apps and are never interpreted). */
@Composable
internal fun ExceptionsSection(
    notAmountFields: List<PolicyException>,
    notActionButtons: List<PolicyException>,
    onRemove: (PolicyExceptionKind, PolicySelector) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.approval_profile_exceptions_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ExceptionGroup(kind = PolicyExceptionKind.NOT_AMOUNT_FIELD, exceptions = notAmountFields, onRemove = onRemove)
        ExceptionGroup(kind = PolicyExceptionKind.NOT_ACTION_BUTTON, exceptions = notActionButtons, onRemove = onRemove)
    }
}

@Composable
private fun ExceptionGroup(
    kind: PolicyExceptionKind,
    exceptions: List<PolicyException>,
    onRemove: (PolicyExceptionKind, PolicySelector) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(text = stringResource(kind.titleRes()), style = MaterialTheme.typography.titleSmall)
        if (exceptions.isEmpty()) {
            Text(
                text = stringResource(R.string.approval_profile_exceptions_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        exceptions.forEach { exception ->
            key(exception.selector) {
                ExceptionRow(kind = kind, exception = exception, onRemove = onRemove)
            }
        }
    }
}

@Composable
private fun ExceptionRow(
    kind: PolicyExceptionKind,
    exception: PolicyException,
    onRemove: (PolicyExceptionKind, PolicySelector) -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val label = exception.selector.displayText()
    if (showConfirm) {
        RemoveExceptionDialog(
            kind = kind,
            label = label,
            onConfirm = {
                showConfirm = false
                onRemove(kind, exception.selector)
            },
            onDismiss = { showConfirm = false },
        )
    }
    ListItem(
        headlineContent = { Text(text = label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                stringResource(
                    R.string.approval_profile_exception_supporting,
                    stringResource(exception.selector.kind.labelRes()),
                    dateFormat.format(Date(exception.createdAtMs)),
                ),
            )
        },
        trailingContent = {
            IconButton(onClick = { showConfirm = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.approval_profile_exception_remove_cd, label),
                )
            }
        },
    )
}

@Composable
private fun RemoveExceptionDialog(
    kind: PolicyExceptionKind,
    label: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.approval_profile_exception_remove_title)) },
        text = { Text(stringResource(kind.removeBodyRes(), label)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.approval_profile_exception_remove_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.approval_profiles_cancel)) }
        },
    )
}

private fun PolicySelector.displayText(): String =
    when (kind) {
        SelectorKind.RESOURCE_ID -> value
        SelectorKind.LABEL -> value.substringAfter(LABEL_SEPARATOR)
    }

@StringRes
private fun PolicyExceptionKind.titleRes(): Int =
    when (this) {
        PolicyExceptionKind.NOT_AMOUNT_FIELD -> R.string.approval_profile_not_amount_title
        PolicyExceptionKind.NOT_ACTION_BUTTON -> R.string.approval_profile_not_button_title
    }

@StringRes
private fun PolicyExceptionKind.removeBodyRes(): Int =
    when (this) {
        PolicyExceptionKind.NOT_AMOUNT_FIELD -> R.string.approval_profile_exception_remove_not_amount_body
        PolicyExceptionKind.NOT_ACTION_BUTTON -> R.string.approval_profile_exception_remove_not_button_body
    }

@StringRes
private fun SelectorKind.labelRes(): Int =
    when (this) {
        SelectorKind.RESOURCE_ID -> R.string.approval_profile_selector_resource_id
        SelectorKind.LABEL -> R.string.approval_profile_selector_label
    }

/** `SelectorKind.LABEL` values are `<className>|<normalized label>`, built by `PolicyLabels.labelSelector`. */
private const val LABEL_SEPARATOR = '|'
