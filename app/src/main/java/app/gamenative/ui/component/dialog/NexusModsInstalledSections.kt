package app.gamenative.ui.component.dialog

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.ModInstall
import app.gamenative.data.ModInstallStatus
import app.gamenative.data.ModProfile
import app.gamenative.mods.ModFileConflictReport
import app.gamenative.ui.component.NoExtractOutlinedTextField
import java.io.File
@Composable
internal fun ProfilesSection(
    profiles: List<ModProfile>,
    activeProfile: ModProfile?,
    onActivate: (ModProfile) -> Unit,
    onCreate: () -> Unit,
    onRename: (ModProfile) -> Unit,
    onDelete: (ModProfile) -> Unit,
) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.nexus_profile_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onCreate) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.nexus_profile_new))
                }
            }
            Text(
                stringResource(R.string.nexus_profile_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (profiles.isEmpty()) {
                Text(stringResource(R.string.nexus_profile_default_created), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                profiles.forEach { profile ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (profile.profileId == activeProfile?.profileId) {
                                Text(stringResource(R.string.nexus_profile_active), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (profile.profileId != activeProfile?.profileId) {
                            TextButton(onClick = { onActivate(profile) }) {
                                Text(stringResource(R.string.nexus_profile_use))
                            }
                        }
                        TextButton(onClick = { onRename(profile) }) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.nexus_profile_rename))
                        }
                        IconButton(
                            onClick = { onDelete(profile) },
                            enabled = profiles.size > 1,
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.nexus_profile_delete))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProfileNameDialog(
    title: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            NoExtractOutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.nexus_profile_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim()) },
                enabled = name.trim().isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
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
internal fun InstalledModsSection(
    installs: List<ModInstall>,
    priorityByInstallId: Map<String, Int>,
    enabledByInstallId: Map<String, Boolean>,
    selectedInstall: ModInstall?,
    placementNeededInstallIds: Set<String>,
    onSelect: (ModInstall) -> Unit,
    onSetEnabled: (ModInstall, Boolean) -> Unit,
    onDelete: (ModInstall) -> Unit,
    onRetry: (ModInstall) -> Unit,
    onMovePriority: (String, Int) -> Unit,
    onApplyOrder: () -> Unit,
) {
    var modSearchQuery by remember { mutableStateOf("") }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val compact = maxWidth < 520.dp
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val hasEnabledPlaceableMods = installs.any { it.canPlaceFiles() && isEnabledInProfile(it, enabledByInstallId) }
                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.nexus_mods_file_priority_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        OutlinedButton(
                            onClick = onApplyOrder,
                            enabled = hasEnabledPlaceableMods,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.nexus_apply_order), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.nexus_mods_file_priority_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = onApplyOrder,
                            enabled = hasEnabledPlaceableMods,
                        ) {
                            Text(stringResource(R.string.nexus_apply_order))
                        }
                    }
                }
            if (installs.any { it.canPlaceFiles() }) {
                Text(
                    stringResource(R.string.nexus_mods_file_priority_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (installs.isEmpty()) {
                Text(stringResource(R.string.nexus_no_mods_imported), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val orderedInstalls = installs
                .sortedWith(compareByDescending<ModInstall> { priorityByInstallId[it.installId] ?: 0 }.thenBy { it.modName.lowercase() })
            val visibleInstalls = orderedInstalls.filter { install ->
                val placementStatus = if (install.installId in placementNeededInstallIds) "needs placement" else ""
                matchesNexusSearch(
                    modSearchQuery,
                    install.modName,
                    install.fileName,
                    install.status,
                    install.errorMessage(),
                    placementStatus,
                    "priority ${priorityByInstallId[install.installId] ?: 0}",
                )
            }
            if (installs.isNotEmpty()) {
                NexusModsSearchField(
                    value = modSearchQuery,
                    placeholder = stringResource(R.string.nexus_search_mods),
                    onValueChange = { modSearchQuery = it },
                )
                if (modSearchQuery.isNotBlank()) {
                    Text(
                        stringResource(R.string.nexus_mods_shown, visibleInstalls.size, orderedInstalls.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (visibleInstalls.isEmpty() && installs.isNotEmpty()) {
                Text(stringResource(R.string.nexus_no_mods_match), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            visibleInstalls
                .forEach { install ->
                    val index = orderedInstalls.indexOfFirst { it.installId == install.installId }
                    val enabledInProfile = isEnabledInProfile(install, enabledByInstallId)
                    val status = if (install.installId in placementNeededInstallIds) {
                        "NEEDS_PLACEMENT"
                    } else {
                        install.profileStatus(enabledInProfile)
                    }
                    if (compact) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(install) }
                                .padding(vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Icon(
                                    Icons.Default.Extension,
                                    contentDescription = null,
                                    tint = if (selectedInstall?.installId == install.installId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(22.dp),
                                )
                                InstalledModTitle(install, maxNameLines = 2, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onDelete(install) }, modifier = Modifier.size(38.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = if (install.canRetryImport()) stringResource(R.string.nexus_remove) else stringResource(R.string.delete))
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                StatusChip(status)
                                if (install.canPlaceFiles()) {
                                    Text(
                                        text = "P${priorityByInstallId[install.installId] ?: 0}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.weight(1f))
                                if (install.canRetryImport()) {
                                    TextButton(onClick = { onRetry(install) }) {
                                        Text(retryLabel(install), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                } else if (install.canPlaceFiles()) {
                                    TextButton(onClick = { onSetEnabled(install, !enabledInProfile) }) {
                                        Text(if (enabledInProfile) stringResource(R.string.nexus_disable) else stringResource(R.string.nexus_enable))
                                    }
                                }
                                if (install.canPlaceFiles()) {
                                    IconButton(
                                        onClick = { onMovePriority(install.installId, -1) },
                                        enabled = index > 0,
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.nexus_move_up))
                                    }
                                    IconButton(
                                        onClick = { onMovePriority(install.installId, 1) },
                                        enabled = index < orderedInstalls.lastIndex,
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.nexus_move_down))
                                    }
                                }
                            }
                            if (install.canRetryImport()) {
                                InstalledModMetadata(install, priorityByInstallId[install.installId] ?: 0, enabledInProfile, errorMaxLines = 2)
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(install) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                Icons.Default.Extension,
                                contentDescription = null,
                                tint = if (selectedInstall?.installId == install.installId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Column(Modifier.weight(1f)) {
                                InstalledModTitle(install, maxNameLines = 1)
                                InstalledModMetadata(install, priorityByInstallId[install.installId] ?: 0, enabledInProfile, errorMaxLines = 1)
                            }
                            StatusChip(status)
                            if (install.canRetryImport()) {
                                TextButton(onClick = { onRetry(install) }) {
                                    Text(retryLabel(install), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            } else if (install.canPlaceFiles()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    IconButton(onClick = { onMovePriority(install.installId, -1) }, enabled = index > 0) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.nexus_move_up))
                                    }
                                    IconButton(onClick = { onMovePriority(install.installId, 1) }, enabled = index < orderedInstalls.lastIndex) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.nexus_move_down))
                                    }
                                }
                                TextButton(onClick = { onSetEnabled(install, !enabledInProfile) }) {
                                    Text(if (enabledInProfile) stringResource(R.string.nexus_disable) else stringResource(R.string.nexus_enable))
                                }
                            }
                            IconButton(onClick = { onDelete(install) }) {
                                Icon(Icons.Default.Delete, contentDescription = if (install.canRetryImport()) stringResource(R.string.nexus_remove) else stringResource(R.string.delete))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun retryLabel(install: ModInstall): String {
    if (install.status == ModInstallStatus.PAUSED.name) return stringResource(R.string.nexus_resume)
    if (install.status == ModInstallStatus.CANCELED.name) return stringResource(R.string.nexus_retry)
    val error = install.errorMessage().lowercase()
    val archive = install.archivePath.takeIf(String::isNotBlank)?.let(::File)
    val retainedArchive = archive?.isFile == true ||
        archive?.parentFile?.let { File(it, "${archive.name}.part").isFile } == true
    val downloadError = listOf(
        "download",
        "nexus",
        "wi-fi",
        "network",
        "link",
        "does not exist",
        "unsupported archive type: .part",
    ).any(error::contains)
    return if (retainedArchive && !downloadError) stringResource(R.string.nexus_retry_unpack) else stringResource(R.string.nexus_retry_download)
}

@Composable
private fun InstalledModTitle(
    install: ModInstall,
    maxNameLines: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(install.modName, maxLines = maxNameLines, overflow = TextOverflow.Ellipsis)
        Text(
            install.fileName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InstalledModMetadata(
    install: ModInstall,
    priority: Int,
    enabledInProfile: Boolean,
    errorMaxLines: Int,
) {
    if (install.canPlaceFiles()) {
        Text(
            stringResource(
                R.string.nexus_mod_priority_state,
                priority,
                if (enabledInProfile) stringResource(R.string.nexus_enabled_in_profile) else stringResource(R.string.nexus_disabled_in_profile),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (install.canRetryImport()) {
        Text(
            install.errorMessage().ifBlank { stringResource(R.string.nexus_mod_not_finished_importing) },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = errorMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ConflictSummarySection(
    conflicts: List<ModFileConflictReport>,
    onSelectInstall: (String) -> Unit,
    onMovePriority: (String, Int) -> Unit,
    onMakeWinner: (String) -> Unit,
) {
    var showAllConflicts by remember(conflicts.size) { mutableStateOf(false) }
    val groups = remember(conflicts) { conflicts.groupedByParticipants() }
    val visibleGroups = if (showAllConflicts) groups else groups.take(8)
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.nexus_file_conflicts_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (groups.size > 8) {
                    TextButton(onClick = { showAllConflicts = !showAllConflicts }) {
                        Text(if (showAllConflicts) stringResource(R.string.nexus_show_fewer) else stringResource(R.string.nexus_show_all))
                    }
                }
            }
            Text(
                text = stringResource(R.string.nexus_file_conflicts_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            visibleGroups.forEach { group ->
                val conflict = group.conflicts.first()
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val visiblePathLimit = if (showAllConflicts) 10 else 4
                        Text(
                            text = stringResource(R.string.nexus_file_conflicts_count, group.conflicts.size),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        group.conflicts.take(visiblePathLimit).forEach { groupedConflict ->
                            Text(
                                text = groupedConflict.targetRelativePath,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (group.conflicts.size > visiblePathLimit) {
                            Text(
                                text = stringResource(R.string.nexus_more_files_in_conflict, group.conflicts.size - visiblePathLimit),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val participantNames = group.conflicts.firstOrNull()
                            ?.participants
                            ?.joinToString(stringResource(R.string.nexus_conflict_participant_separator)) { it.modName }
                            .orEmpty()
                        Text(
                            text = participantNames,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val winner = conflict.participants.firstOrNull { it.wins }
                        if (winner != null) {
                            Text(
                                text = stringResource(R.string.nexus_conflict_winner_summary, winner.modName, conflict.participants.size - 1),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        val visibleParticipants = if (showAllConflicts) conflict.participants else conflict.participants.take(4)
                        visibleParticipants.forEach { participant ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = participant.modName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (participant.wins) FontWeight.SemiBold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = stringResource(R.string.nexus_participant_priority, participant.priority, File(participant.sourcePath).name),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (participant.wins) {
                                    StatusChip("WINS")
                                } else {
                                    TextButton(onClick = { onMakeWinner(participant.installId) }) {
                                        Text(stringResource(R.string.nexus_win))
                                    }
                                }
                                TextButton(onClick = { onSelectInstall(participant.installId) }) {
                                    Text(stringResource(R.string.nexus_open))
                                }
                                IconButton(
                                    onClick = { onMovePriority(participant.installId, -1) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.nexus_move_up))
                                }
                            }
                        }
                        if (!showAllConflicts && conflict.participants.size > 4) {
                            Text(
                                stringResource(R.string.nexus_more_conflicting_mods, conflict.participants.size - 4),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (!showAllConflicts && groups.size > 8) {
                Text(
                    text = stringResource(R.string.nexus_more_conflict_sets, groups.size - 8),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class ConflictGroup(
    val conflicts: List<ModFileConflictReport>,
)

private fun List<ModFileConflictReport>.groupedByParticipants(): List<ConflictGroup> =
    groupBy { conflict ->
        conflict.participants
            .map { it.installId }
            .sorted()
            .joinToString("|")
    }
        .values
        .map { ConflictGroup(it.sortedBy { conflict -> conflict.targetRelativePath.lowercase() }) }
        .sortedBy { it.conflicts.firstOrNull()?.targetRelativePath.orEmpty().lowercase() }
