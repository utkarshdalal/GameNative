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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                Text("Mod profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onCreate) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("New")
                }
            }
            Text(
                "Profiles save which mods are enabled and their file priority for this game.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (profiles.isEmpty()) {
                Text("Default profile will be created automatically.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text("Active", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        if (profile.profileId != activeProfile?.profileId) {
                            TextButton(onClick = { onActivate(profile) }) {
                                Text("Use")
                            }
                        }
                        TextButton(onClick = { onRename(profile) }) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Rename")
                        }
                        IconButton(
                            onClick = { onDelete(profile) },
                            enabled = profiles.size > 1,
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete profile")
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
                label = { Text("Profile name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.trim().isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
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
                        Text("Mods & file priority", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        OutlinedButton(
                            onClick = onApplyOrder,
                            enabled = hasEnabledPlaceableMods,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Apply order", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Mods & file priority", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        OutlinedButton(
                            onClick = onApplyOrder,
                            enabled = hasEnabledPlaceableMods,
                        ) {
                            Text("Apply order")
                        }
                    }
                }
            if (installs.any { it.canPlaceFiles() }) {
                Text(
                    "Enable mods for this profile and choose which mod wins when files overlap. Mods higher in this list overwrite lower mods after Apply order.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (installs.isEmpty()) {
                Text("No Nexus mods imported for this game yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val orderedInstalls = installs
                .sortedWith(compareByDescending<ModInstall> { priorityByInstallId[it.installId] ?: 0 }.thenBy { it.modName.lowercase() })
            val visibleInstalls = orderedInstalls.filter { install ->
                matchesNexusSearch(
                    modSearchQuery,
                    install.modName,
                    install.fileName,
                    install.status,
                    install.errorMessage(),
                    "priority ${priorityByInstallId[install.installId] ?: 0}",
                )
            }
            if (installs.isNotEmpty()) {
                NexusModsSearchField(
                    value = modSearchQuery,
                    placeholder = "Search mods",
                    onValueChange = { modSearchQuery = it },
                )
                if (modSearchQuery.isNotBlank()) {
                    Text(
                        "${visibleInstalls.size} of ${orderedInstalls.size} mod(s) shown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (visibleInstalls.isEmpty() && installs.isNotEmpty()) {
                Text("No mods match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            visibleInstalls
                .forEach { install ->
                    val index = orderedInstalls.indexOfFirst { it.installId == install.installId }
                    val enabledInProfile = isEnabledInProfile(install, enabledByInstallId)
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
                                InstalledModTitle(install, maxNameLines = 1, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onDelete(install) }, modifier = Modifier.size(38.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = if (install.canRetryImport()) "Remove" else "Delete")
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                StatusChip(if (install.canPlaceFiles() && !enabledInProfile) "PROFILE_DISABLED" else install.status)
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
                                        Text(if (enabledInProfile) "Disable" else "Enable")
                                    }
                                }
                                if (install.canPlaceFiles()) {
                                    IconButton(
                                        onClick = { onMovePriority(install.installId, -1) },
                                        enabled = index > 0,
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                                    }
                                    IconButton(
                                        onClick = { onMovePriority(install.installId, 1) },
                                        enabled = index < orderedInstalls.lastIndex,
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
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
                            StatusChip(if (install.canPlaceFiles() && !enabledInProfile) "PROFILE_DISABLED" else install.status)
                            if (install.canRetryImport()) {
                                TextButton(onClick = { onRetry(install) }) {
                                    Text(retryLabel(install), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            } else if (install.canPlaceFiles()) {
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    IconButton(onClick = { onMovePriority(install.installId, -1) }, enabled = index > 0) {
                                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                                    }
                                    IconButton(onClick = { onMovePriority(install.installId, 1) }, enabled = index < orderedInstalls.lastIndex) {
                                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                                    }
                                }
                                TextButton(onClick = { onSetEnabled(install, !enabledInProfile) }) {
                                    Text(if (enabledInProfile) "Disable" else "Enable")
                                }
                            }
                            IconButton(onClick = { onDelete(install) }) {
                                Icon(Icons.Default.Delete, contentDescription = if (install.canRetryImport()) "Remove" else "Delete")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun retryLabel(install: ModInstall): String {
    if (install.status == ModInstallStatus.PAUSED.name) return "Resume"
    if (install.status == ModInstallStatus.CANCELED.name) return "Retry"
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
    return if (retainedArchive && !downloadError) "Retry unpack" else "Retry download"
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
            "Priority $priority - ${if (enabledInProfile) "Enabled in profile" else "Disabled in profile"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (install.canRetryImport()) {
        Text(
            install.errorMessage().ifBlank { "This mod did not finish importing." },
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
                Text("File overwrite conflicts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (groups.size > 8) {
                    TextButton(onClick = { showAllConflicts = !showAllConflicts }) {
                        Text(if (showAllConflicts) "Show fewer" else "Show all")
                    }
                }
            }
            Text(
                text = "These mods install some of the same files. The higher-priority mod wins when you apply order.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            visibleGroups.forEach { group ->
                val conflict = group.conflicts.first()
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val visiblePathLimit = if (showAllConflicts) 10 else 4
                        Text(
                            text = "${group.conflicts.size} file conflict(s)",
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
                                text = "${group.conflicts.size - visiblePathLimit} more file(s) in this conflict set",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = group.participantNames,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val winner = conflict.participants.firstOrNull { it.wins }
                        if (winner != null) {
                            Text(
                                text = "${winner.modName} wins over ${conflict.participants.size - 1} other mod(s)",
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
                                        text = "Priority ${participant.priority} - ${File(participant.sourcePath).name}",
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
                                        Text("Win")
                                    }
                                }
                                TextButton(onClick = { onSelectInstall(participant.installId) }) {
                                    Text("Open")
                                }
                                IconButton(
                                    onClick = { onMovePriority(participant.installId, -1) },
                                    modifier = Modifier.size(36.dp),
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                                }
                            }
                        }
                        if (!showAllConflicts && conflict.participants.size > 4) {
                            Text(
                                "${conflict.participants.size - 4} more conflicting mod(s)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (!showAllConflicts && groups.size > 8) {
                Text(
                    text = "${groups.size - 8} more conflict set(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class ConflictGroup(
    val conflicts: List<ModFileConflictReport>,
) {
    val participantNames: String =
        conflicts.firstOrNull()
            ?.participants
            ?.joinToString(" vs ") { it.modName }
            .orEmpty()
}

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
