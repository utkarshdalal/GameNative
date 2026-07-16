package app.gamenative.ui.component.dialog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SnippetFolder
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModPlacementMode
import app.gamenative.data.ModTargetRoot
import app.gamenative.mods.FomodInstaller
import app.gamenative.mods.ModArchiveEntry
import app.gamenative.mods.ModPlacementPreset
import app.gamenative.mods.ModPlacementSources
import app.gamenative.mods.ModTargetResolver
import app.gamenative.mods.ResolvedModTargetRoot
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.utils.StorageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
@Composable
internal fun StatusChip(status: String) {
    val (label, color, contentColor) = when (status) {
        ModInstallStatus.READY.name -> Triple(stringResource(R.string.nexus_status_ready), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        ModInstallStatus.APPLIED.name -> Triple(stringResource(R.string.nexus_status_applied), MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        ModInstallStatus.DISABLED.name -> Triple(stringResource(R.string.nexus_status_disabled), MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
        ModInstallStatus.ERROR.name -> Triple(stringResource(R.string.nexus_status_failed), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        ModInstallStatus.IMPORTING.name -> Triple(stringResource(R.string.nexus_status_importing), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        ModInstallStatus.PAUSED.name -> Triple(stringResource(R.string.nexus_status_paused), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        ModInstallStatus.CANCELED.name -> Triple(stringResource(R.string.nexus_status_canceled), MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
        "PROFILE_DISABLED" -> Triple(stringResource(R.string.nexus_status_off_in_profile), MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
        "NEEDS_PLACEMENT" -> Triple(stringResource(R.string.nexus_status_needs_placement), MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        "ENABLED" -> Triple(stringResource(R.string.nexus_status_enabled), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        "WINS" -> Triple(stringResource(R.string.nexus_status_wins), MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        else -> Triple(status.lowercase().replaceFirstChar { it.uppercase() }, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = RoundedCornerShape(999.dp), color = color) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = contentColor,
        )
    }
}
@Composable
internal fun PlacementSection(
    install: ModInstall,
    entries: List<ModArchiveEntry>,
    fomodInstaller: FomodInstaller?,
    roots: List<ResolvedModTargetRoot>,
    drafts: List<RecipeDraft>,
    presetOptions: List<PlacementPresetOption>,
    placementChoice: PlacementChoice,
    canUseLastPlacement: Boolean,
    onPlacementChoiceChange: (PlacementChoice) -> Unit,
    onUseLastPlacement: () -> Unit,
    onPresetSelected: (List<RecipeDraft>) -> Unit,
    onUpdateDraft: (Int, RecipeDraft) -> Unit,
    onAddDraft: () -> Unit,
    onRemoveDraft: (Int) -> Unit,
    onFomodRecipes: (List<RecipeDraft>, Int) -> Unit,
    applyStatusMessage: String?,
    onSaveAndApply: () -> Unit,
) {
    var showArchiveBrowser by remember(install.installId, entries) { mutableStateOf(false) }
    var showFomodWizard by remember(install.installId, fomodInstaller) { mutableStateOf(false) }
    val destinationsValid = drafts.all { draft -> roots.any { it.type.name == draft.targetRoot } }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.nexus_placement_where_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(install.modName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (!install.canPlaceFiles()) {
                Text(
                    text = when (install.status) {
                        ModInstallStatus.IMPORTING.name -> stringResource(R.string.nexus_mod_still_importing)
                        ModInstallStatus.PAUSED.name -> install.errorMessage().ifBlank { stringResource(R.string.nexus_import_paused) }
                        ModInstallStatus.CANCELED.name -> install.errorMessage().ifBlank { stringResource(R.string.nexus_import_canceled) }
                        else -> install.errorMessage().ifBlank { stringResource(R.string.nexus_mod_not_finished_importing) }
                    },
                    color = if (install.status == ModInstallStatus.ERROR.name) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            } else {
                if (entries.isEmpty()) {
                    Text(stringResource(R.string.nexus_mod_not_finished_importing), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                val firstDraft = drafts.firstOrNull()
                ArchivePreview(
                    entries = entries,
                    importFinished = true,
                    onBrowse = { showArchiveBrowser = true },
                )

                fomodInstaller?.let {
                    FomodSummarySection(
                        installer = it,
                        onConfigure = { showFomodWizard = true },
                    )
                }

                PlacementChoiceSelector(
                    selected = placementChoice,
                    hasPresets = presetOptions.isNotEmpty(),
                    canUseLastPlacement = canUseLastPlacement,
                    onSelect = {
                        if (it == PlacementChoice.LAST_USED) onUseLastPlacement() else onPlacementChoiceChange(it)
                    },
                )

                if (placementChoice == PlacementChoice.PRESET && presetOptions.isNotEmpty()) {
                    PresetSelectionSection(
                        presets = presetOptions,
                        selectedDrafts = drafts,
                        onSelect = onPresetSelected,
                    )
                }

                if (firstDraft != null && placementChoice != PlacementChoice.CUSTOM) {
                    drafts.forEach { draft ->
                        PlacementSummaryCard(
                            choice = placementChoice,
                            draft = draft,
                            roots = roots,
                        )
                    }
                } else {
                    drafts.forEachIndexed { index, draft ->
                        PlacementDraftEditor(
                            index = index,
                            draft = draft,
                            entries = entries,
                            roots = roots,
                            canRemove = drafts.size > 1,
                            onUpdate = { onUpdateDraft(index, it) },
                            onRemove = { onRemoveDraft(index) },
                        )
                    }
                }

                if (!destinationsValid) {
                    Text(
                        text = stringResource(R.string.nexus_choose_destination_inside),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                applyStatusMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compactActions = maxWidth < 420.dp
                    if (compactActions) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            if (placementChoice == PlacementChoice.CUSTOM) {
                                OutlinedButton(onClick = onAddDraft, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(R.string.nexus_add_location))
                                }
                            }
                            Button(
                                onClick = onSaveAndApply,
                                enabled = roots.isNotEmpty() && destinationsValid,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.nexus_apply_mod))
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (placementChoice == PlacementChoice.CUSTOM) {
                                OutlinedButton(onClick = onAddDraft) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(8.dp))
                                    Text(stringResource(R.string.nexus_add_location))
                                }
                            }
                            Button(onClick = onSaveAndApply, enabled = roots.isNotEmpty() && destinationsValid) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(8.dp))
                                Text(stringResource(R.string.nexus_apply_mod))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showArchiveBrowser) {
        ArchiveBrowserDialog(
            title = stringResource(R.string.nexus_files_in_mod),
            entries = entries,
            onSelect = null,
            onDismiss = { showArchiveBrowser = false },
        )
    }

    if (showFomodWizard && fomodInstaller != null) {
        FomodWizardDialog(
            installId = install.installId,
            installer = fomodInstaller,
            extractedRoot = File(install.extractedPath),
            baseDraft = drafts.firstOrNull() ?: RecipeDraft(),
            onApply = { generatedDrafts, unsupportedCount ->
                showFomodWizard = false
                onFomodRecipes(generatedDrafts, unsupportedCount)
            },
            onDismiss = { showFomodWizard = false },
        )
    }
}

@Composable
private fun PresetSelectionSection(
    presets: List<PlacementPresetOption>,
    selectedDrafts: List<RecipeDraft>,
    onSelect: (List<RecipeDraft>) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.nexus_game_presets), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        presets.forEach { option ->
            val selected = option.drafts == selectedDrafts
            val content: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                    Text(option.preset.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        option.preset.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Button(onClick = { onSelect(option.drafts) }, modifier = Modifier.fillMaxWidth(), content = { content() })
            } else {
                OutlinedButton(onClick = { onSelect(option.drafts) }, modifier = Modifier.fillMaxWidth(), content = { content() })
            }
        }
    }
}
@Composable
private fun PlacementChoiceSelector(
    selected: PlacementChoice,
    hasPresets: Boolean,
    canUseLastPlacement: Boolean,
    onSelect: (PlacementChoice) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.nexus_placement_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 420.dp
            val choices = listOf(
                Triple(PlacementChoice.AUTOMATIC, stringResource(R.string.nexus_placement_automatic), true),
                Triple(PlacementChoice.PRESET, stringResource(R.string.nexus_placement_preset), hasPresets),
                Triple(PlacementChoice.LAST_USED, stringResource(R.string.nexus_placement_last_used), canUseLastPlacement),
                Triple(PlacementChoice.CUSTOM, stringResource(R.string.nexus_placement_custom), true),
            )
            val rows = if (compact) choices.chunked(2) else listOf(choices)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                rows.forEach { rowChoices ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        rowChoices.forEach { (choice, label, enabled) ->
                            PlacementChoiceButton(
                                text = label,
                                selected = selected == choice,
                                enabled = enabled,
                                onClick = { onSelect(choice) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun PlacementChoiceButton(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content: @Composable () -> Unit = {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier, content = { content() })
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier, content = { content() })
    }
}

@Composable
private fun PlacementSummaryCard(
    choice: PlacementChoice,
    draft: RecipeDraft,
    roots: List<ResolvedModTargetRoot>,
) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = when (choice) {
                    PlacementChoice.AUTOMATIC -> stringResource(R.string.nexus_automatic_placement)
                    PlacementChoice.PRESET -> stringResource(R.string.nexus_preset_placement)
                    PlacementChoice.LAST_USED -> stringResource(R.string.nexus_last_used_placement)
                    PlacementChoice.CUSTOM -> stringResource(R.string.nexus_custom_placement)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                    text = stringResource(R.string.nexus_destination_folder_value, placementSummary(draft, roots, stringResource(R.string.nexus_base_folder))),
                style = MaterialTheme.typography.bodyMedium,
            )
            val selectedSources = ModPlacementSources.decode(draft.sourceSubpath).filter { it.isNotBlank() }
            val sourceSummary = sourceSelectionSummaryText(draft.sourceSubpath)
            if (selectedSources.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.nexus_source_folder_value, sourceSummary),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DestinationScopeText(draft, roots)
        }
    }
}

@Composable
private fun ArchivePreview(
    entries: List<ModArchiveEntry>,
    importFinished: Boolean,
    onBrowse: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 360.dp
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.nexus_files_in_mod), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    if (entries.isNotEmpty()) {
                        OutlinedButton(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.nexus_browse))
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.nexus_files_in_mod), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    if (entries.isNotEmpty()) {
                        OutlinedButton(onClick = onBrowse) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.nexus_browse))
                        }
                    }
                }
            }
        }
        val shown = entries.take(30)
        if (shown.isEmpty()) {
            Text(
                if (importFinished) stringResource(R.string.nexus_no_extracted_entries) else stringResource(R.string.nexus_mod_not_finished_importing),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            shown.forEach { entry ->
                Text(
                    text = if (entry.directory) "${entry.path}/" else entry.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entries.size > shown.size) {
                Text(
                    stringResource(R.string.nexus_more_files_available_browse, entries.size - shown.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlacementDraftEditor(
    index: Int,
    draft: RecipeDraft,
    entries: List<ModArchiveEntry>,
    roots: List<ResolvedModTargetRoot>,
    canRemove: Boolean,
    onUpdate: (RecipeDraft) -> Unit,
    onRemove: () -> Unit,
) {
    var showSourcePicker by remember(index) { mutableStateOf(false) }
    var showDestinationPicker by remember(index) { mutableStateOf(false) }
    var showManualPaths by remember(index) { mutableStateOf(false) }

    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.nexus_location_number, index + 1), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                if (canRemove) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.nexus_remove_placement_row))
                    }
                }
            }

            PickerButton(
                label = stringResource(R.string.nexus_source_folder_label),
                value = sourceSelectionSummaryText(draft.sourceSubpath),
                icon = Icons.Default.FolderOpen,
                onClick = { showSourcePicker = true },
            )

            PickerButton(
                label = stringResource(R.string.nexus_destination_folder),
                value = placementSummary(draft, roots, stringResource(R.string.nexus_base_folder)),
                icon = Icons.Default.Folder,
                onClick = { showDestinationPicker = true },
            )
            DestinationScopeText(draft, roots)

            DropdownField(
                label = stringResource(R.string.nexus_install_method),
                value = placementModeLabelText(draft.mode),
                options = ModPlacementMode.entries.map { placementModeLabelText(it.name) to it.name },
                onSelect = { onUpdate(draft.copy(mode = it)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Checkbox(
                    checked = draft.includeSourceDirectory,
                    onCheckedChange = { onUpdate(draft.copy(includeSourceDirectory = it)) },
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.nexus_create_folder_for_selection))
                    Text(
                        text = stringResource(R.string.nexus_create_folder_for_selection_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            TextButton(onClick = { showManualPaths = !showManualPaths }) {
                Text(if (showManualPaths) stringResource(R.string.nexus_hide_manual_paths) else stringResource(R.string.nexus_manual_paths))
            }

            if (showManualPaths) {
                NoExtractOutlinedTextField(
                    value = sourceManualText(draft.sourceSubpath),
                    onValueChange = { onUpdate(draft.copy(sourceSubpath = ModPlacementSources.encode(it.lines()))) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.nexus_source_folders_files_label)) },
                    placeholder = { Text(stringResource(R.string.nexus_source_paths_placeholder)) },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                )
                NoExtractOutlinedTextField(
                    value = draft.targetRelativePath,
                    onValueChange = {
                        val normalized = if (draft.targetRoot == ModTargetRoot.CUSTOM_ABSOLUTE.name) {
                            it.trim().replace('\\', '/')
                        } else {
                            ModTargetResolver.normalizeRelativePath(it)
                        }
                        onUpdate(draft.copy(targetRelativePath = normalized))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.nexus_destination_folder)) },
                    placeholder = { Text(stringResource(R.string.nexus_destination_placeholder)) },
                    singleLine = true,
                )
            }
        }
    }

    if (showSourcePicker) {
        ArchiveBrowserDialog(
            title = stringResource(R.string.nexus_choose_from_mod),
            entries = entries,
            onSelect = null,
            selectedPaths = ModPlacementSources.decode(draft.sourceSubpath)
                .filter { it.isNotBlank() }
                .toSet(),
            onSelectMultiple = { paths ->
                onUpdate(draft.copy(sourceSubpath = ModPlacementSources.encode(paths)))
                showSourcePicker = false
            },
            onDismiss = { showSourcePicker = false },
        )
    }

    if (showDestinationPicker) {
        ContainerDestinationPickerDialog(
            roots = roots,
            currentDraft = draft,
            onSelect = {
                onUpdate(it)
                showDestinationPicker = false
            },
            onDismiss = { showDestinationPicker = false },
        )
    }
}

@Composable
private fun PickerButton(
    label: String,
    value: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun placementModeLabelText(mode: String): String = when (mode) {
    ModPlacementMode.SYMLINK.name -> stringResource(R.string.nexus_mode_link_files)
    ModPlacementMode.COPY.name -> stringResource(R.string.nexus_mode_copy_files)
    ModPlacementMode.OVERWRITE_COPY.name -> stringResource(R.string.nexus_mode_overwrite_backup)
    else -> mode
}

@Composable
private fun sourceSelectionSummaryText(sourceSubpath: String): String {
    val sources = ModPlacementSources.decode(sourceSubpath).filter { it.isNotBlank() }
    return when (sources.size) {
        0 -> stringResource(R.string.nexus_everything_in_mod)
        1 -> sources.single()
        else -> stringResource(
            R.string.nexus_source_selected_summary,
            sources.size,
            sources.take(2).joinToString(", "),
            if (sources.size > 2) ", ..." else "",
        )
    }
}

@Composable
private fun DestinationScopeText(
    draft: RecipeDraft,
    roots: List<ResolvedModTargetRoot>,
) {
    val root = roots.firstOrNull { it.type.name == draft.targetRoot }
    Text(
        text = if (root != null) {
            stringResource(R.string.nexus_inside_root, root.label)
        } else {
            stringResource(R.string.nexus_destination_not_inside)
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (root != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ArchiveBrowserDialog(
    title: String,
    entries: List<ModArchiveEntry>,
    onSelect: ((String) -> Unit)?,
    selectedPaths: Set<String> = emptySet(),
    onSelectMultiple: ((Set<String>) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var currentPath by remember(entries) { mutableStateOf("") }
    var selected by remember(entries, selectedPaths) {
        mutableStateOf(selectedPaths.map(ModPlacementSources::normalize).filter { it.isNotBlank() }.toSet())
    }
    val children = remember(entries, currentPath) { archiveChildren(entries, currentPath) }
    val allFilesLabel = stringResource(R.string.nexus_all_files)
    val breadcrumb = currentPath.ifBlank { allFilesLabel }.replace("/", " / ")
    val multiSelect = onSelectMultiple != null

    fun toggleSelection(path: String) {
        val normalized = ModPlacementSources.normalize(path)
        if (normalized.isBlank()) return
        selected = if (normalized in selected) selected - normalized else selected + normalized
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .height(540.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = breadcrumb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (currentPath.isNotBlank()) {
                    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(
                            modifier = Modifier
                                .clickable { currentPath = parentArchivePath(currentPath) }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), modifier = Modifier.size(18.dp))
                            Text(parentArchivePath(currentPath).ifBlank { allFilesLabel }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                HorizontalDivider()

                if (entries.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.nexus_no_extracted_entries), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (children.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                Icons.Default.FolderOff,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(stringResource(R.string.nexus_no_files_in_folder), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(children, key = { it.path }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (multiSelect && !item.directory) {
                                            toggleSelection(item.path)
                                        } else if (item.directory) {
                                            currentPath = item.path
                                        } else {
                                            onSelect?.invoke(item.path)
                                        }
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                if (multiSelect) {
                                    Checkbox(
                                        checked = item.path in selected,
                                        onCheckedChange = { toggleSelection(item.path) },
                                    )
                                }
                                Icon(
                                    if (item.directory) Icons.Default.Folder else Icons.Default.Description,
                                    contentDescription = null,
                                    tint = if (item.directory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (!item.directory && item.sizeBytes > 0L) {
                                        Text(
                                            StorageUtils.formatBinarySize(item.sizeBytes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (item.directory) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                            )
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    if (onSelectMultiple != null) {
                        OutlinedButton(
                            onClick = {
                                onSelectMultiple(
                                    if (currentPath.isBlank()) emptySet() else setOf(currentPath),
                                )
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(if (currentPath.isBlank()) stringResource(R.string.nexus_use_all_files) else stringResource(R.string.nexus_use_this_folder))
                        }
                        Button(
                            onClick = { onSelectMultiple(selected) },
                            enabled = selected.isNotEmpty(),
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(stringResource(R.string.nexus_use_selected))
                        }
                    } else if (onSelect != null) {
                        Button(
                            onClick = { onSelect(currentPath) },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(if (currentPath.isBlank()) stringResource(R.string.nexus_use_all_files) else stringResource(R.string.nexus_use_this_folder))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContainerDestinationPickerDialog(
    roots: List<ResolvedModTargetRoot>,
    currentDraft: RecipeDraft,
    onSelect: (RecipeDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    var currentRootName by remember(currentDraft.targetRoot, roots) {
        mutableStateOf(roots.firstOrNull { it.type.name == currentDraft.targetRoot }?.type?.name.orEmpty())
    }
    var currentDir by remember(currentDraft.targetRoot, currentDraft.targetRelativePath, roots) {
        val root = roots.firstOrNull { it.type.name == currentDraft.targetRoot }
        val current = root?.let { targetRoot ->
            val candidate = if (currentDraft.targetRelativePath.isBlank()) {
                targetRoot.dir
            } else {
                File(targetRoot.dir, currentDraft.targetRelativePath)
            }
            runCatching { candidate.canonicalFile }
                .getOrNull()
                ?.takeIf { it.isDirectory && it.isInsideOrEqual(targetRoot.dir) }
        }
        mutableStateOf(current)
    }
    var subDirs by remember { mutableStateOf<List<File>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val currentRoot = roots.firstOrNull { it.type.name == currentRootName }

    LaunchedEffect(currentDir, currentRoot) {
        val dir = currentDir
        if (dir != null && dir.isDirectory) {
            loading = true
            try {
                val root = currentRoot
                subDirs = withContext(Dispatchers.IO) {
                    dir.listFiles()
                        ?.filter {
                            it.isDirectory &&
                                !it.name.startsWith(".") &&
                                (root == null || it.isInsideOrEqual(root.dir))
                        }
                        ?.sortedBy { it.name.lowercase() }
                        ?: emptyList()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                subDirs = emptyList()
            } finally {
                loading = false
            }
        } else {
            subDirs = emptyList()
        }
    }

    val breadcrumb = remember(currentDir, currentRoot) {
        val dir = currentDir ?: return@remember ""
        val root = currentRoot ?: return@remember dir.name
        val relative = runCatching {
            dir.canonicalFile.relativeToOrNull(root.dir.canonicalFile)?.path.orEmpty()
        }.getOrDefault("")
        if (relative.isBlank()) root.label else "${root.label} / ${relative.replace(File.separatorChar, '/')}"
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .height(540.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.nexus_destination_folder), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        text = if (currentDir == null) stringResource(R.string.nexus_choose_game_container_location) else breadcrumb,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                if (currentDir != null) {
                    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    val root = currentRoot
                                    val parent = currentDir?.parentFile
                                    currentDir = if (root != null && parent != null && parent.isInsideOrEqual(root.dir)) {
                                        parent
                                    } else {
                                        null
                                    }
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), modifier = Modifier.size(18.dp))
                            Text(currentDir?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    HorizontalDivider()
                }

                if (currentDir == null) {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(roots, key = { it.type.name }) { root ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        currentRootName = root.type.name
                                        currentDir = root.dir
                                    }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                            shape = RoundedCornerShape(8.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        targetRootIcon(root.type),
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                Text(root.label, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                } else if (loading) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                    }
                } else if (subDirs.isEmpty()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                Icons.Default.FolderOff,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(stringResource(R.string.nexus_no_subdirectories), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        items(subDirs, key = { it.absolutePath }) { dir ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { currentDir = dir }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(dir.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 20.dp),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                            )
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    val selectedRoot = currentRoot
                    val selectedDir = currentDir
                    if (selectedRoot != null && selectedDir != null) {
                        Button(
                            onClick = {
                                val relative = runCatching {
                                    selectedDir.canonicalFile.relativeToOrNull(selectedRoot.dir.canonicalFile)
                                        ?.path
                                        ?.replace(File.separatorChar, '/')
                                        .orEmpty()
                                }.getOrDefault("")
                                onSelect(
                                    currentDraft.copy(
                                        targetRoot = selectedRoot.type.name,
                                        targetRelativePath = relative,
                                    ),
                                )
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(stringResource(R.string.nexus_select))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DropdownField(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall)
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, storedValue) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        expanded = false
                        onSelect(storedValue)
                    },
                )
            }
        }
    }
}
