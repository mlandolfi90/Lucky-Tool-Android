package com.danielealbano.androidremotecontrolmcp.ui.screens.settings

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.policy.model.PolicyLimits
import com.danielealbano.androidremotecontrolmcp.policy.model.ProfileTemplate
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ApprovalProfilesViewModel
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.ManualPackageError
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.PickerApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalProfileAddScreen(
    onBack: () -> Unit,
    onContinue: (String, ProfileTemplate) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ApprovalProfilesViewModel = hiltViewModel(),
) {
    val apps by viewModel.pickerApps.collectAsStateWithLifecycle()
    val icons by viewModel.appIcons.collectAsStateWithLifecycle()
    var chosenPackage by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.loadApps() }

    chosenPackage?.let { packageName ->
        TemplateChoiceDialog(
            packageName = packageName,
            onDismiss = { chosenPackage = null },
            onConfirm = { template ->
                chosenPackage = null
                onContinue(packageName, template)
            },
        )
    }
    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(stringResource(R.string.approval_profiles_add_title), modifier = Modifier.semantics { heading() })
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
        ManualPackageEntry(validate = viewModel::validateManualPackage, onAccept = { chosenPackage = it })
        AppPickerList(apps = apps, icons = icons, onPick = { chosenPackage = it }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ManualPackageEntry(
    validate: (String) -> ManualPackageError?,
    onAccept: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val error = if (text.isBlank()) null else validate(text)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SectionTitle(text = stringResource(R.string.approval_profiles_manual_title))
        Row(verticalAlignment = Alignment.Top) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(PolicyLimits.MAX_PACKAGE_NAME_LENGTH) },
                label = { Text(stringResource(R.string.approval_profiles_manual_label)) },
                placeholder = { Text(stringResource(R.string.approval_profiles_manual_placeholder)) },
                isError = error != null,
                supportingText = {
                    Text(stringResource(error?.messageRes() ?: R.string.approval_profiles_manual_hint))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onAccept(text.trim()) },
                enabled = text.isNotBlank() && error == null,
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.approval_profiles_manual_use))
            }
        }
    }
}

@Composable
private fun AppPickerList(
    apps: List<PickerApp>,
    icons: Map<String, Bitmap>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered =
        remember(apps, query) {
            apps.filter {
                it.appName.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
            }
        }
    Column(modifier = modifier) {
        SectionTitle(
            text = stringResource(R.string.approval_profiles_apps_title),
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(MAX_QUERY_LENGTH) },
            label = { Text(stringResource(R.string.approval_profiles_search)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.approval_profiles_no_apps),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(filtered, key = { it.packageName }) { app ->
                    PickerAppRow(app = app, icon = icons[app.packageName], onPick = onPick)
                }
            }
        }
    }
}

@Composable
private fun PickerAppRow(
    app: PickerApp,
    icon: Bitmap?,
    onPick: (String) -> Unit,
) {
    ListItem(
        headlineContent = { Text(app.appName) },
        supportingContent = { Text(app.packageName, style = MaterialTheme.typography.bodySmall) },
        leadingContent = { AppIconImage(bitmap = icon) },
        modifier =
            Modifier.clickable(onClickLabel = stringResource(R.string.approval_profiles_pick_action)) {
                onPick(app.packageName)
            },
    )
}

@Composable
private fun TemplateChoiceDialog(
    packageName: String,
    onDismiss: () -> Unit,
    onConfirm: (ProfileTemplate) -> Unit,
) {
    var selected by rememberSaveable { mutableStateOf(DEFAULT_TEMPLATE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.approval_profiles_template_title)) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                Text(
                    text = stringResource(R.string.approval_profiles_template_for, packageName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                ProfileTemplate.entries.forEach { template ->
                    TemplateOption(
                        template = template,
                        selected = template == selected,
                        onSelect = { selected = template },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.approval_profiles_template_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.approval_profiles_cancel)) }
        },
    )
}

@Composable
private fun TemplateOption(
    template: ProfileTemplate,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = MIN_TOUCH_TARGET_DP.dp)
                .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = stringResource(template.labelRes()), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(template.descriptionRes()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val MAX_QUERY_LENGTH = 100
private const val MIN_TOUCH_TARGET_DP = 48
