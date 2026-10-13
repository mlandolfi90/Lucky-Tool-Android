package com.danielealbano.androidremotecontrolmcp.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.policy.approval.PolicyApprovalLabels
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyExceptionKind
import com.danielealbano.androidremotecontrolmcp.policy.model.RuleMode
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.PolicyApprovalCard
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * One pending action approval: app, action, amount (only here), field, tool, why, rule, countdown, the exception
 * buttons the card offers, Deny / Approve. The amount and the field label are device-derived: shown, never acted on.
 */
@Composable
fun PolicyApprovalCardView(
    card: PolicyApprovalCard,
    icon: Bitmap?,
    onDecision: (approve: Boolean) -> Unit,
    onException: (PolicyExceptionKind) -> Unit,
) {
    val remaining = rememberSecondsLeft(card.expiresAtMs)
    val expired = remaining <= 0L
    val colors =
        if (expired) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        } else {
            CardDefaults.cardColors()
        }
    val appName = card.appName ?: card.packageName ?: stringResource(R.string.policy_approval_unknown_app)
    Card(modifier = Modifier.fillMaxWidth(), colors = colors) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppHeader(appName = appName, packageName = card.packageName, icon = icon)
            CardDetails(card = card)
            SecondsLeftLabel(remaining = remaining)
            ExceptionButtons(card = card, onException = onException)
            DecisionButtons(appName = appName, expired = expired, onDecision = onDecision)
        }
    }
}

@Composable
private fun AppHeader(
    appName: String,
    packageName: String?,
    icon: Bitmap?,
) {
    val iconDescription = stringResource(R.string.policy_approval_app_icon_description, appName)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            val image = remember(icon) { icon.asImageBitmap() }
            Image(bitmap = image, contentDescription = iconDescription, modifier = Modifier.size(APP_ICON_DP.dp))
        } else {
            Icon(
                imageVector = Icons.Filled.Android,
                contentDescription = iconDescription,
                modifier = Modifier.size(APP_ICON_DP.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(text = appName, style = MaterialTheme.typography.titleMedium)
            if (packageName != null && packageName != appName) {
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CardDetails(card: PolicyApprovalCard) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text =
                stringResource(
                    R.string.policy_approval_action_label,
                    stringResource(PolicyApprovalLabels.actionType(card.actionType)),
                ),
            style = MaterialTheme.typography.bodyLarge,
        )
        AmountLine(amount = card.amount, unreadable = card.amountUnreadable)
        card.fieldLabel?.let { label ->
            Text(
                text = stringResource(R.string.policy_approval_field_label, label),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Text(
            text = stringResource(R.string.policy_approval_tool_label, card.toolName),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text =
                stringResource(
                    R.string.policy_approval_rule_label,
                    stringResource(PolicyApprovalLabels.reason(card.reason)),
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text =
                stringResource(
                    R.string.policy_approval_method_label,
                    stringResource(PolicyApprovalLabels.ruleMode(card.method)),
                ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (card.method == RuleMode.ASK_IN_CHAT) {
            Text(
                text = stringResource(R.string.policy_approval_ask_in_chat_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AmountLine(
    amount: String?,
    unreadable: Boolean,
) {
    val text =
        when {
            amount != null -> stringResource(R.string.policy_approval_amount_label, amount)
            unreadable -> stringResource(R.string.policy_approval_amount_unreadable)
            else -> null
        }
    if (text != null) {
        Text(text = text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ExceptionButtons(
    card: PolicyApprovalCard,
    onException: (PolicyExceptionKind) -> Unit,
) {
    if (card.canMarkNotAmount || card.canMarkNotActionButton) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (card.canMarkNotAmount) {
                TextButton(
                    onClick = { onException(PolicyExceptionKind.NOT_AMOUNT_FIELD) },
                    modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp),
                ) { Text(stringResource(R.string.policy_approval_not_amount)) }
            }
            if (card.canMarkNotActionButton) {
                TextButton(
                    onClick = { onException(PolicyExceptionKind.NOT_ACTION_BUTTON) },
                    modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp),
                ) { Text(stringResource(R.string.policy_approval_not_action_button)) }
            }
        }
    }
}

@Composable
private fun DecisionButtons(
    appName: String,
    expired: Boolean,
    onDecision: (approve: Boolean) -> Unit,
) {
    val denyDescription = stringResource(R.string.policy_approval_deny_description, appName)
    val approveDescription = stringResource(R.string.policy_approval_approve_description, appName)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = { onDecision(false) },
            modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp).semantics { contentDescription = denyDescription },
        ) { Text(stringResource(R.string.approval_deny)) }
        Spacer(Modifier.width(12.dp))
        Button(
            onClick = { onDecision(true) },
            enabled = !expired,
            modifier = Modifier.heightIn(min = MIN_TOUCH_DP.dp).semantics { contentDescription = approveDescription },
        ) { Text(stringResource(R.string.approval_approve)) }
    }
}

/** Ticking countdown (seconds) of the approval window; 0 once lapsed. */
@Composable
private fun rememberSecondsLeft(expiresAtMs: Long): Long {
    var remaining by remember(expiresAtMs) { mutableLongStateOf(secondsLeft(expiresAtMs)) }
    LaunchedEffect(expiresAtMs) {
        while (remaining > 0L) {
            delay(MILLIS_PER_SECOND)
            remaining = secondsLeft(expiresAtMs)
        }
    }
    return remaining
}

@Composable
private fun SecondsLeftLabel(remaining: Long) {
    val expired = remaining <= 0L
    val text =
        if (expired) {
            stringResource(R.string.approval_expired)
        } else {
            val mmss = "%d:%02d".format(Locale.ROOT, remaining / SECONDS_PER_MINUTE, remaining % SECONDS_PER_MINUTE)
            stringResource(R.string.approval_expires_in, mmss)
        }
    Text(
        text = text,
        style = if (expired) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
        fontWeight = if (expired) FontWeight.Bold else null,
        color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun secondsLeft(expiresAtMs: Long): Long {
    val remainingMs = (expiresAtMs - System.currentTimeMillis()).coerceAtLeast(0L)
    return remainingMs / MILLIS_PER_SECOND
}

private const val APP_ICON_DP = 40
private const val MIN_TOUCH_DP = 48
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
