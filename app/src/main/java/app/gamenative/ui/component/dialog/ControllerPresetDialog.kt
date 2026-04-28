package app.gamenative.ui.component.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.util.SnackbarManager
import com.winlator.inputcontrols.Binding

@Composable
internal fun LoadControllerPresetDialog(
    onDismiss: () -> Unit,
    onPresetSelected: (Map<Int, Binding>) -> Unit,
) {
    val context = LocalContext.current
    var presets by remember { mutableStateOf(ControllerPresetManager.getAllPresets(context)) }
    var renamingPreset by remember { mutableStateOf<ControllerPresetManager.ControllerPreset?>(null) }
    var deletingPreset by remember { mutableStateOf<ControllerPresetManager.ControllerPreset?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.load_controller_preset)) },
        text = {
            if (presets.isEmpty()) {
                Text(stringResource(R.string.no_presets_available))
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    itemsIndexed(presets, key = { _, preset -> "${preset.isFactory}_${preset.name}" }) { index, preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPresetSelected(preset.bindings)
                                    onDismiss()
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (preset.isFactory) FontWeight.Bold else FontWeight.Normal,
                                )
                                if (preset.isFactory) {
                                    Text(
                                        text = stringResource(R.string.factory_preset),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (!preset.isFactory) {
                                IconButton(onClick = { renamingPreset = preset }) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.rename),
                                    )
                                }
                                IconButton(onClick = { deletingPreset = preset }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.delete),
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                        if (index < presets.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )

    renamingPreset?.let { preset ->
        RenamePresetDialog(
            currentName = preset.name,
            existingNames = presets.map { it.name }.toSet(),
            onDismiss = { renamingPreset = null },
            onRename = { newName ->
                if (ControllerPresetManager.renameUserPreset(context, preset, newName)) {
                    presets = presets.map {
                        if (it == preset) it.copy(name = newName) else it
                    }
                    renamingPreset = null
                } else {
                    SnackbarManager.show(context.getString(R.string.preset_rename_failed))
                }
            },
        )
    }

    deletingPreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { deletingPreset = null },
            title = { Text(stringResource(R.string.delete_preset_title)) },
            text = { Text(stringResource(R.string.delete_preset_message, preset.name)) },
            confirmButton = {
                TextButton(onClick = {
                    if (ControllerPresetManager.deleteUserPreset(context, preset)) {
                        presets = presets.filter { it != preset }
                    } else {
                        SnackbarManager.show(context.getString(R.string.preset_delete_failed))
                    }
                    deletingPreset = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingPreset = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
internal fun SaveControllerPresetDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val context = LocalContext.current
    val (factoryNames, userNames) = remember {
        val presets = ControllerPresetManager.getAllPresets(context)
        Pair(
            presets.filter { it.isFactory }.mapTo(mutableSetOf()) { it.name },
            presets.filter { !it.isFactory }.mapTo(mutableSetOf()) { it.name },
        )
    }
    val trimmed = name.trim()
    val isFactoryConflict = trimmed in factoryNames
    val nameConflict = trimmed in userNames

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_controller_preset)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoExtractOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.preset_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isFactoryConflict) {
                    Text(
                        text = stringResource(R.string.preset_name_already_exists),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (nameConflict) {
                    Text(
                        text = stringResource(R.string.preset_will_overwrite),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            val hasConflict = isFactoryConflict || nameConflict
            if (hasConflict) {
                val uniqueName = ControllerPresetManager.uniqueName(factoryNames + userNames, trimmed)
                TextButton(onClick = { onSave(uniqueName); onDismiss() }) {
                    Text(stringResource(R.string.save_as_new, uniqueName))
                }
            }
            if (nameConflict) {
                TextButton(onClick = { onSave(trimmed); onDismiss() }) {
                    Text(stringResource(R.string.overwrite))
                }
            }
            if (!hasConflict) {
                TextButton(
                    onClick = { onSave(trimmed); onDismiss() },
                    enabled = trimmed.isNotBlank(),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
internal fun LoadLayoutPresetDialog(
    onDismiss: () -> Unit,
    onPresetSelected: (ControllerPresetManager.LayoutPreset) -> Unit,
) {
    val context = LocalContext.current
    var presets by remember { mutableStateOf(ControllerPresetManager.getLayoutPresets(context)) }
    var renamingPreset by remember { mutableStateOf<ControllerPresetManager.LayoutPreset?>(null) }
    var deletingPreset by remember { mutableStateOf<ControllerPresetManager.LayoutPreset?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.load_layout_preset)) },
        text = {
            if (presets.isEmpty()) {
                Text(stringResource(R.string.no_presets_available))
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    itemsIndexed(presets, key = { _, preset -> preset.name }) { index, preset ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPresetSelected(preset)
                                    onDismiss()
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = preset.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { renamingPreset = preset }) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = stringResource(R.string.rename),
                                )
                            }
                            IconButton(onClick = { deletingPreset = preset }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (index < presets.lastIndex) {
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )

    renamingPreset?.let { preset ->
        RenamePresetDialog(
            currentName = preset.name,
            existingNames = presets.map { it.name }.toSet(),
            onDismiss = { renamingPreset = null },
            onRename = { newName ->
                if (ControllerPresetManager.renameLayoutPreset(context, preset, newName)) {
                    presets = presets.map {
                        if (it == preset) it.copy(name = newName) else it
                    }
                    renamingPreset = null
                } else {
                    SnackbarManager.show(context.getString(R.string.preset_rename_failed))
                }
            },
        )
    }

    deletingPreset?.let { preset ->
        AlertDialog(
            onDismissRequest = { deletingPreset = null },
            title = { Text(stringResource(R.string.delete_preset_title)) },
            text = { Text(stringResource(R.string.delete_preset_message, preset.name)) },
            confirmButton = {
                TextButton(onClick = {
                    if (ControllerPresetManager.deleteLayoutPreset(context, preset)) {
                        presets = presets.filter { it != preset }
                    } else {
                        SnackbarManager.show(context.getString(R.string.preset_delete_failed))
                    }
                    deletingPreset = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingPreset = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
internal fun SaveLayoutPresetDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val context = LocalContext.current
    val existingNames = remember {
        ControllerPresetManager.getLayoutPresets(context).mapTo(mutableSetOf()) { it.name }
    }
    val trimmed = name.trim()
    val nameConflict = trimmed in existingNames

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.save_layout_preset)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NoExtractOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.preset_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (nameConflict) {
                    Text(
                        text = stringResource(R.string.preset_will_overwrite),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (nameConflict) {
                val uniqueName = ControllerPresetManager.uniqueName(existingNames, trimmed)
                TextButton(onClick = { onSave(uniqueName); onDismiss() }) {
                    Text(stringResource(R.string.save_as_new, uniqueName))
                }
                TextButton(onClick = { onSave(trimmed); onDismiss() }) {
                    Text(stringResource(R.string.overwrite))
                }
            } else {
                TextButton(
                    onClick = { onSave(trimmed); onDismiss() },
                    enabled = trimmed.isNotBlank(),
                ) {
                    Text(stringResource(R.string.save))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun RenamePresetDialog(
    currentName: String,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    val trimmed = name.trim()
    val isDuplicate = trimmed != currentName && trimmed in existingNames

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename_preset)) },
        text = {
            Column {
                NoExtractOutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.preset_name)) },
                    singleLine = true,
                    isError = isDuplicate,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isDuplicate) {
                    Text(
                        text = stringResource(R.string.preset_name_already_exists),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(trimmed) },
                enabled = trimmed.isNotBlank() && trimmed != currentName && !isDuplicate,
            ) {
                Text(stringResource(R.string.rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
