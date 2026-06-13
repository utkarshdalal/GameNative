package app.gamenative.ui.component.dialog

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.mods.NexusCollectionFile
import app.gamenative.mods.NexusCollectionInstallClassification
import app.gamenative.mods.NexusFileSelector
import app.gamenative.mods.NexusModFile
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.utils.StorageUtils

private enum class CollectionDisplayFilter(@StringRes val labelRes: Int) {
    ALL(R.string.nexus_filter_all),
    AUTO(R.string.nexus_filter_downloadable),
    PLACEMENT(R.string.nexus_filter_placement),
    MANUAL(R.string.nexus_filter_manual),
    UNSUPPORTED(R.string.nexus_filter_unsupported),
}
@Composable
internal fun ApiKeySection(
    apiKey: String,
    validationState: ApiKeyValidationState?,
    onApiKeyChange: (String) -> Unit,
    onValidate: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.nexus_account_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                stringResource(R.string.nexus_account_api_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.nexus_account_key_local),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NoExtractOutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.nexus_personal_api_key)) },
                singleLine = true,
            )
            OutlinedButton(onClick = onValidate, enabled = apiKey.isNotBlank()) {
                Text(stringResource(R.string.nexus_save_check_key))
            }
            validationState?.let { state ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.checking) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (state.success) {
                            true -> MaterialTheme.colorScheme.primary
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
@Composable
internal fun ImportSection(
    nexusUrl: String,
    onUrlChange: (String) -> Unit,
    onImport: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Link, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.nexus_add_from_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            NoExtractOutlinedTextField(
                value = nexusUrl,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.nexus_url_label)) },
                singleLine = true,
            )
            Button(onClick = onImport, enabled = nexusUrl.isNotBlank()) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.nexus_find_files))
            }
        }
    }
}

@Composable
internal fun FileSelectionSection(
    pending: PendingFileSelection,
    onImport: (NexusModFile) -> Unit,
) {
    var showOlderFiles by remember(pending) { mutableStateOf(false) }
    val currentFiles = remember(pending.files) { NexusFileSelector.currentFiles(pending.files) }
    val olderFiles = remember(pending.files) { NexusFileSelector.olderFiles(pending.files) }
    val filesToShow = if (showOlderFiles) currentFiles + olderFiles else currentFiles

    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.nexus_choose_file_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                pending.modInfo.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (filesToShow.isEmpty()) {
                Text(stringResource(R.string.nexus_no_current_files), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            filesToShow.forEach { file ->
                FileRow(file = file, onImport = onImport)
            }

            if (olderFiles.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = showOlderFiles,
                        onCheckedChange = { showOlderFiles = it },
                    )
                    Text(
                        text = stringResource(R.string.nexus_show_older_files),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
internal fun CollectionSelectionSection(
    pending: PendingCollectionSelection,
    selectedKeys: Set<String>,
    queueItems: Map<String, CollectionQueueItem>,
    availableBytes: Long,
    estimatedRequiredBytes: Long,
    paused: Boolean,
    cancelEnabled: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onImportSelected: () -> Unit,
    onRetryFailed: () -> Unit,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onCancelAll: () -> Unit,
) {
    val context = LocalContext.current
    val importable = pending.mods.count { it.canImport }
    val failed = pending.mods.size - importable
    val selectedCount = pending.mods.count { it.canImport && it.collectionKey() in selectedKeys }
    val failedQueueCount = queueItems.values.count { it.status == CollectionQueueStatus.FAILED }
    val autoCount = pending.mods.count { it.collectionFile.classification == NexusCollectionInstallClassification.AUTO_INSTALLABLE && it.canImport }
    val placementCount = pending.mods.count { it.collectionFile.classification == NexusCollectionInstallClassification.NEEDS_PLACEMENT }
    val manualCount = pending.mods.count { it.collectionFile.classification == NexusCollectionInstallClassification.EXTERNAL_MANUAL }
    val unsupportedOrUnresolvedCount = pending.mods.count {
        it.collectionFile.classification == NexusCollectionInstallClassification.UNSUPPORTED || !it.canImport
    }
    var filter by remember(pending) { mutableStateOf(CollectionDisplayFilter.ALL) }
    val visibleFilters = remember(autoCount, placementCount, manualCount, unsupportedOrUnresolvedCount) {
        buildList {
            add(CollectionDisplayFilter.ALL)
            if (autoCount > 0) add(CollectionDisplayFilter.AUTO)
            if (placementCount > 0) add(CollectionDisplayFilter.PLACEMENT)
            if (manualCount > 0) add(CollectionDisplayFilter.MANUAL)
            if (unsupportedOrUnresolvedCount > 0) add(CollectionDisplayFilter.UNSUPPORTED)
        }
    }
    val activeFilter = if (filter in visibleFilters) filter else CollectionDisplayFilter.ALL
    val filteredMods = remember(pending, activeFilter) {
        pending.mods.filter { mod ->
            when (activeFilter) {
                CollectionDisplayFilter.ALL -> true
                CollectionDisplayFilter.AUTO -> mod.collectionFile.classification == NexusCollectionInstallClassification.AUTO_INSTALLABLE && mod.canImport
                CollectionDisplayFilter.PLACEMENT -> mod.collectionFile.classification == NexusCollectionInstallClassification.NEEDS_PLACEMENT
                CollectionDisplayFilter.MANUAL -> mod.collectionFile.classification == NexusCollectionInstallClassification.EXTERNAL_MANUAL
                CollectionDisplayFilter.UNSUPPORTED -> mod.collectionFile.classification == NexusCollectionInstallClassification.UNSUPPORTED || !mod.canImport
            }
        }
    }
    val collectionIndexByKey = remember(pending) {
        pending.mods.mapIndexed { index, mod -> mod.collectionKey() to index }.toMap()
    }

    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Inventory2, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.nexus_collection_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        pending.collection.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = if (failed == 0) {
                    stringResource(R.string.nexus_collection_ready, importable, selectedCount)
                } else {
                    stringResource(R.string.nexus_collection_ready_with_failed, importable, failed, selectedCount)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.nexus_estimated_temp_space,
                    StorageUtils.formatBinarySize(estimatedRequiredBytes),
                    StorageUtils.formatBinarySize(availableBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (availableBytes < estimatedRequiredBytes) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.nexus_import_space_cache_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CollectionChecklist(
                autoCount = autoCount,
                placementCount = placementCount,
                manualCount = manualCount,
                unsupportedCount = unsupportedOrUnresolvedCount,
            )
            CollectionManifestDetails(pending)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth < 420.dp
                val firstRow: @Composable () -> Unit = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onSelectAll, enabled = importable > 0) {
                            Text(stringResource(R.string.nexus_select_all))
                        }
                        TextButton(onClick = onClearSelection, enabled = selectedCount > 0) {
                            Text(stringResource(R.string.nexus_clear))
                        }
                    }
                }
                val secondRow: @Composable () -> Unit = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (failedQueueCount > 0) {
                            TextButton(onClick = onRetryFailed) {
                                Text(stringResource(R.string.nexus_retry_failed))
                            }
                        }
                    }
                }
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        firstRow()
                        secondRow()
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        firstRow()
                        secondRow()
                    }
                }
            }
            if (visibleFilters.size > 1) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compact = maxWidth < 520.dp
                    if (compact) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            visibleFilters.chunked(3).forEach { rowFilters ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    rowFilters.forEach { item ->
                                        CollectionFilterButton(item, activeFilter == item, Modifier.weight(1f)) { filter = item }
                                    }
                                }
                            }
                        }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            visibleFilters.forEach { item ->
                                CollectionFilterButton(item, activeFilter == item) { filter = item }
                            }
                        }
                    }
                }
            }
            filteredMods.forEach { mod ->
                val key = mod.collectionKey()
                val queueItem = queueItems[key]
                val selected = key in selectedKeys
                val index = collectionIndexByKey[key] ?: 0
                val fileFallback = stringResource(R.string.nexus_file_fallback, mod.collectionFile.fileId)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Checkbox(
                        checked = selected,
                        enabled = mod.canImport && queueItem?.status != CollectionQueueStatus.IMPORTING,
                        onCheckedChange = { onToggle(key, it) },
                    )
                    Text("${index + 1}.", style = MaterialTheme.typography.labelMedium)
                    Column(Modifier.weight(1f)) {
                        Text(
                            mod.modInfo?.name ?: mod.collectionFile.modName.ifBlank {
                                stringResource(R.string.nexus_collection_mod_fallback_name, mod.collectionFile.modId)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            mod.file?.name ?: mod.collectionFile.fileName.ifBlank { mod.error ?: fileFallback },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (mod.canImport) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        CollectionClassificationText(mod)
                        queueItem?.let { item ->
                            Text(
                                text = collectionQueueLabel(
                                    item = item,
                                    queuedLabel = stringResource(R.string.nexus_queue_queued),
                                    importingLabel = stringResource(R.string.nexus_queue_importing),
                                    importedLabel = stringResource(R.string.nexus_queue_imported),
                                    failedLabel = stringResource(R.string.nexus_status_failed),
                                    canceledLabel = stringResource(R.string.nexus_status_canceled),
                                    etaLeft = { eta -> context.getString(R.string.nexus_eta_left, eta) },
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = when (item.status) {
                                    CollectionQueueStatus.FAILED -> MaterialTheme.colorScheme.error
                                    CollectionQueueStatus.CANCELED -> MaterialTheme.colorScheme.onSurfaceVariant
                                    CollectionQueueStatus.IMPORTED -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (item.status == CollectionQueueStatus.IMPORTING) {
                                if (item.progress > 0f && item.progress < 1f) {
                                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                                } else {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                }
                            }
                            if (item.status == CollectionQueueStatus.IMPORTED && item.error.isNotBlank()) {
                                Text(
                                    text = item.error,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            if (filteredMods.isEmpty()) {
                Text(stringResource(R.string.nexus_no_collection_items_filter), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val compact = maxWidth < 420.dp
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onImportSelected, enabled = selectedCount > 0, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.nexus_download_selected))
                        }
                        CollectionQueueControlButtons(
                            paused = paused,
                            cancelEnabled = cancelEnabled,
                            onPauseAll = onPauseAll,
                            onResumeAll = onResumeAll,
                            onCancelAll = onCancelAll,
                            compact = true,
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onImportSelected, enabled = selectedCount > 0) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(R.string.nexus_download_selected))
                        }
                        CollectionQueueControlButtons(
                            paused = paused,
                            cancelEnabled = cancelEnabled,
                            onPauseAll = onPauseAll,
                            onResumeAll = onResumeAll,
                            onCancelAll = onCancelAll,
                            compact = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CollectionChecklist(
    autoCount: Int,
    placementCount: Int,
    manualCount: Int,
    unsupportedCount: Int,
) {
    val downloadableLabel = stringResource(R.string.nexus_filter_downloadable)
    val placementLabel = stringResource(R.string.nexus_filter_placement)
    val manualLabel = stringResource(R.string.nexus_filter_manual)
    val unsupportedLabel = stringResource(R.string.nexus_filter_unsupported)
    val summary = remember(autoCount, placementCount, manualCount, unsupportedCount, downloadableLabel, placementLabel, manualLabel, unsupportedLabel) {
        buildList {
            if (autoCount > 0) add("$autoCount $downloadableLabel")
            if (placementCount > 0) add("$placementCount $placementLabel")
            if (manualCount > 0) add("$manualCount $manualLabel")
            if (unsupportedCount > 0) add("$unsupportedCount $unsupportedLabel")
        }.joinToString(" | ")
    }
    if (summary.isBlank()) return

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.nexus_collection_checklist_title), style = MaterialTheme.typography.labelLarge)
        Text(
            summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CollectionManifestDetails(pending: PendingCollectionSelection) {
    val info = pending.collection.manifestInfo
    val rules = info.rules
    if (info.manualSteps.isEmpty() && rules.rawRuleCount == 0 && rules.pluginLoadOrder.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (rules.pluginLoadOrder.isNotEmpty()) {
            Text(
                stringResource(R.string.nexus_plugin_load_order_detected, rules.pluginLoadOrder.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (rules.fileConflictRules.isNotEmpty()) {
            Text(
                stringResource(R.string.nexus_file_conflict_rules_detected, rules.fileConflictRules.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (rules.ruleSources.isNotEmpty()) {
            Text(
                stringResource(
                    R.string.nexus_rule_fields,
                    rules.ruleSources.take(4).joinToString { it.path },
                    if (rules.ruleSources.size > 4) stringResource(R.string.nexus_rule_fields_more) else "",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (rules.unsupportedRules.isNotEmpty()) {
            Text(
                stringResource(R.string.nexus_unsupported_collection_rules, rules.unsupportedRules.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        info.manualSteps.take(4).forEach { step ->
            val body = listOf(step.title, step.body, step.expectedDestination, step.url)
                .filter { it.isNotBlank() }
                .joinToString(" - ")
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CollectionFilterButton(
    filter: CollectionDisplayFilter,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(stringResource(filter.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(stringResource(filter.labelRes), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun CollectionClassificationText(mod: PendingCollectionMod) {
    val classification = mod.collectionFile.classification
    val label = when (classification) {
        NexusCollectionInstallClassification.AUTO_INSTALLABLE -> stringResource(R.string.nexus_class_downloadable)
        NexusCollectionInstallClassification.NEEDS_PLACEMENT -> stringResource(R.string.nexus_class_needs_placement)
        NexusCollectionInstallClassification.EXTERNAL_MANUAL -> stringResource(R.string.nexus_class_manual_external)
        NexusCollectionInstallClassification.UNSUPPORTED -> stringResource(R.string.nexus_class_unsupported)
    }
    val color = when (classification) {
        NexusCollectionInstallClassification.AUTO_INSTALLABLE -> MaterialTheme.colorScheme.onSurfaceVariant
        NexusCollectionInstallClassification.NEEDS_PLACEMENT -> MaterialTheme.colorScheme.primary
        NexusCollectionInstallClassification.EXTERNAL_MANUAL,
        NexusCollectionInstallClassification.UNSUPPORTED -> MaterialTheme.colorScheme.error
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
    if (mod.collectionFile.expectedDestination.isNotBlank()) {
        Text(
            stringResource(R.string.nexus_destination, mod.collectionFile.expectedDestination),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    mod.collectionFile.notes.firstOrNull()?.let { note ->
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (mod.collectionFile.externalUrl.isNotBlank()) {
        Text(
            mod.collectionFile.externalUrl,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CollectionQueueControlButtons(
    paused: Boolean,
    cancelEnabled: Boolean,
    onPauseAll: () -> Unit,
    onResumeAll: () -> Unit,
    onCancelAll: () -> Unit,
    compact: Boolean,
) {
    val modifier = if (compact) Modifier.fillMaxWidth() else Modifier
    if (paused) {
        OutlinedButton(onClick = onResumeAll, modifier = modifier) {
            Text(stringResource(R.string.nexus_resume_queue))
        }
    } else {
        OutlinedButton(onClick = onPauseAll, enabled = cancelEnabled, modifier = modifier) {
            Text(stringResource(R.string.nexus_pause_after_current))
        }
    }
    OutlinedButton(onClick = onCancelAll, enabled = cancelEnabled, modifier = modifier) {
        Text(stringResource(R.string.nexus_cancel_queue))
    }
}

@Composable
private fun FileRow(
    file: NexusModFile,
    onImport: (NexusModFile) -> Unit,
) {
    val categoryLabel = file.categoryName.ifBlank { stringResource(R.string.nexus_file_category_default) }
    val versionPrefix = if (file.version.isNotBlank()) {
        stringResource(R.string.nexus_file_version_prefix, file.version)
    } else {
        ""
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onImport(file) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(file.name.ifBlank { file.fileName }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    if (versionPrefix.isNotBlank()) {
                        append(versionPrefix)
                        append(" - ")
                    }
                    append(categoryLabel)
                    append(" - ")
                    append(StorageUtils.formatBinarySize(file.sizeBytes))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                file.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (file.isPrimary) {
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Text(
                    text = stringResource(R.string.nexus_recommended),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
