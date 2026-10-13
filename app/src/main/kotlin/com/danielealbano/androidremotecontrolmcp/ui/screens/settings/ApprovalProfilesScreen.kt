package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ApprovalProfilesViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ProfileListItem
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ProfilesListUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalProfilesScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ApprovalProfilesViewModel = hiltViewModel(),
) {
    val state by viewModel.listState.collectAsStateWithLifecycle()
    val icons by viewModel.appIcons.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.loadApps() }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(stringResource(R.string.approval_profiles_title), modifier = Modifier.semantics { heading() })
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.approval_profiles_back),
                    )
                }
            },
            actions = {
                IconButton(onClick = onAdd) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.approval_profiles_add))
                }
            },
            windowInsets = WindowInsets(0),
        )
        ListNotices(state = state, onRetry = viewModel::retryLoad, onDismissDeleteError = viewModel::dismissDeleteError)
        if (state.items.isEmpty()) {
            EmptyProfiles(onAdd = onAdd)
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.items, key = { it.packageName }) { item ->
                    ProfileRow(
                        item = item,
                        icon = icons[item.packageName],
                        onEdit = onEdit,
                        onDelete = viewModel::delete,
                    )
                }
            }
        }
    }
}

@Composable
private fun ListNotices(
    state: ProfilesListUiState,
    onRetry: () -> Unit,
    onDismissDeleteError: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = stringResource(R.string.approval_profiles_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.storeUnreadable) StoreUnreadableCard(onRetry = onRetry)
        if (state.deleteFailed) {
            DismissibleError(
                message = stringResource(R.string.approval_profiles_delete_failed),
                onDismiss = onDismissDeleteError,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun StoreUnreadableCard(onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.approval_profiles_store_unreadable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.approval_profiles_retry)) }
        }
    }
}

@Composable
private fun ProfileRow(
    item: ProfileListItem,
    icon: Bitmap?,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val title = item.appName ?: item.packageName
    if (showConfirm) {
        DeleteProfileDialog(
            appName = title,
            onConfirm = {
                showConfirm = false
                onDelete(item.packageName)
            },
            onDismiss = { showConfirm = false },
        )
    }
    val editLabel = stringResource(R.string.approval_profiles_edit_action)
    val rowModifier =
        if (item.unreadable) {
            Modifier
        } else {
            Modifier.clickable(onClickLabel = editLabel) { onEdit(item.packageName) }
        }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { ProfileRowSupporting(item = item) },
        leadingContent = { AppIconImage(bitmap = icon) },
        trailingContent = {
            IconButton(onClick = { showConfirm = true }) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.approval_profiles_delete_cd, title),
                )
            }
        },
        modifier = rowModifier,
    )
}

@Composable
private fun ProfileRowSupporting(item: ProfileListItem) {
    val template = item.template
    val text =
        when {
            item.unreadable -> {
                stringResource(R.string.approval_profiles_unreadable, item.packageName)
            }

            template != null -> {
                stringResource(
                    R.string.approval_profiles_row_summary,
                    item.packageName,
                    stringResource(template.labelRes()),
                )
            }

            else -> {
                item.packageName
            }
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (item.unreadable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DeleteProfileDialog(
    appName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.approval_profiles_delete_title)) },
        text = { Text(stringResource(R.string.approval_profiles_delete_body, appName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.approval_profiles_delete_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.approval_profiles_cancel)) }
        },
    )
}

@Composable
private fun EmptyProfiles(onAdd: () -> Unit) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = stringResource(R.string.approval_profiles_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onAdd, modifier = Modifier.padding(top = 16.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.approval_profiles_add))
        }
    }
}
