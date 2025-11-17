package app.gamenative.ui.screen.library.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import app.gamenative.enums.OSArch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import app.gamenative.service.SteamService
import app.gamenative.data.DepotInfo
import kotlinx.coroutines.launch

/**
 * Dialog that lists owned DLC for a given app and allows enabling/disabling each DLC.
 * - Shows only DLCs that SteamService reports as owned (via getOwnedAppDlc)
 * - Checked state reflects presence in installed depots (SteamService.getInstalledDepotsOf)
 * - Enabling starts a depot download for that depot
 * - Disabling removes the depot id from the stored AppInfo record (does not remove files)
 */
@Composable
fun DlcManagerDialog(
    appId: Int,
    visible: Boolean,
    onDismissRequest: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val dlcList = remember { mutableStateListOf<Pair<Int, DepotInfo>>() }
    // When no downloadable depots exist for this platform, we can still show known DLC app ids
    // store as Triple<appId, name, owned>
    val knownDlcList = remember { mutableStateListOf<Triple<Int, String, Boolean>>() }
    var manualDlcInput by remember { mutableStateOf("") }
    var installedDepots by remember { mutableStateOf<List<Int>>(emptyList()) }
    // map depotId -> checked state (user-editable)
    val checkedMap = remember { mutableStateMapOf<Int, Boolean>() }
    var loading by remember { mutableStateOf(true) }
    var showDiagnostics by remember { mutableStateOf(false) }
    val diagnostics = remember { mutableStateListOf<String>() }

    LaunchedEffect(appId, visible) {
        if (!visible) return@LaunchedEffect
        loading = true
        // Fetch downloadable depots (filters by OS/arch/language and ownership)
        val downloadables = try {
            SteamService.getDownloadableDepots(appId)
        } catch (t: Throwable) {
            emptyMap<Int, DepotInfo>()
        }

        // Keep only DLC depots and dedupe by DLC app id (prefer 64-bit depots)
        val dlcDepots = downloadables.filter { (_, depot) -> depot.dlcAppId != SteamService.INVALID_APP_ID }
        val deduped = dlcDepots.values
            .groupBy { it.dlcAppId }
            .mapNotNull { (_, depots) ->
                val preferred = depots.firstOrNull { it.osArch == OSArch.Arch64 } ?: depots.firstOrNull()
                preferred?.let { it.depotId to it }
            }
            .toMap()

        dlcList.clear()
        deduped.toList().forEach { pair -> dlcList.add(pair) }

        // installed depots
        installedDepots = SteamService.getInstalledDepotsOf(appId) ?: emptyList()
        // initialize checked map from installed depots
        checkedMap.clear()
        dlcList.forEach { (depotId, _) -> checkedMap[depotId] = depotId in installedDepots }
        loading = false
    }

    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = "Manage DLC", style = MaterialTheme.typography.titleLarge) },
        text = {
            Surface(modifier = Modifier.fillMaxWidth()) {
                Column {
                    // quick actions row (always visible so user can refresh even when no DLC entries)
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
                        Button(onClick = {
                            scope.launch {
                                loading = true
                                try {
                                    // Re-fetch downloadable depots and derive DLC app ids to check
                                    val downloadables = try {
                                        SteamService.getDownloadableDepots(appId)
                                    } catch (t: Throwable) {
                                        emptyMap<Int, DepotInfo>()
                                    }

                                    // First, collect downloadable depots for this platform (these already respect ownership via getOwnedAppDlc)
                                    val dlcAppIdsFromDownloadables = downloadables.values
                                        .mapNotNull { if (it.dlcAppId != SteamService.INVALID_APP_ID) it.dlcAppId else null }
                                        .toSet()

                                    if (dlcAppIdsFromDownloadables.isNotEmpty()) {
                                        // We already have platform-appropriate depots; update the depot list and checked state
                                        val dlcDepots = downloadables.filter { (_, depot) -> depot.dlcAppId != SteamService.INVALID_APP_ID }
                                        val deduped = dlcDepots.values
                                            .groupBy { it.dlcAppId }
                                            .mapNotNull { (_, depots) ->
                                                val preferred = depots.firstOrNull { it.osArch == OSArch.Arch64 } ?: depots.firstOrNull()
                                                preferred?.let { it.depotId to it }
                                            }
                                            .toMap()

                                        dlcList.clear()
                                        deduped.toList().forEach { pair -> dlcList.add(pair) }

                                        installedDepots = SteamService.getInstalledDepotsOf(appId) ?: emptyList()
                                        checkedMap.clear()
                                        dlcList.forEach { (depotId, _) -> checkedMap[depotId] = depotId in installedDepots }
                                        knownDlcList.clear()

                                        // Optionally inform user of how many DLC depots are available (no PICS required)
                                        Toast.makeText(ctx, "Discovered ${dlcList.size} downloadable DLC entries for this platform.", Toast.LENGTH_LONG).show()
                                        return@launch
                                    }

                                    // No platform-specific downloadables; fall back to manifest-listed DLC app ids and use PICS to check ownership
                                    val manifestDlc: List<Int> = try {
                                        SteamService.getAppDlc(appId).values.map { it.dlcAppId }.distinct()
                                    } catch (t: Throwable) {
                                        emptyList<Int>()
                                    }

                                    if (manifestDlc.isEmpty()) {
                                        Toast.makeText(ctx, "No DLC app ids to check", Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }

                                        // Try to auto-discover DLC app ids from the user's owned games (match by name) as a fast path
                                        val baseName = SteamService.getAppInfoOf(appId)?.name ?: ""
                                        val discovered = mutableSetOf<Int>()
                                        try {
                                            val userSteamId = SteamService.userSteamId
                                            if (userSteamId != null) {
                                                val ownedGames = SteamService.getOwnedGames(userSteamId.convertToUInt64())
                                                val ownedIds = ownedGames.map { it.appId }
                                                ownedIds.forEach { cid ->
                                                    val info = SteamService.getAppInfoOf(cid)
                                                    val nm = info?.name ?: ""
                                                    if (baseName.isNotEmpty() && nm.contains(baseName, ignoreCase = true) && cid != appId) {
                                                        discovered.add(cid)
                                                    }
                                                }
                                            }
                                        } catch (_: Throwable) {
                                            // ignore
                                        }

                                        // Combine manifest list and discovered candidates to check ownership via PICS if needed
                                        val candidates = (manifestDlc + discovered).toSet()
                                        val owned = if (candidates.isNotEmpty()) SteamService.checkDlcOwnershipViaPICSBatch(candidates) else emptySet()
                                        val msg = "Owned DLC count: ${owned.size}/${candidates.size} (discovered ${discovered.size} by name)"
                                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

                                    // Show the known manifest DLC names and their ownership status
                                    dlcList.clear()
                                    knownDlcList.clear()
                                    manifestDlc.forEach { did ->
                                        val name = SteamService.getAppInfoOf(did)?.name ?: "DLC $did"
                                        knownDlcList.add(Triple(did, name, did in owned))
                                    }
                                    installedDepots = SteamService.getInstalledDepotsOf(appId) ?: emptyList()
                                    checkedMap.clear()
                                } catch (t: Throwable) {
                                    Toast.makeText(ctx, "Ownership check failed: ${t.message}", Toast.LENGTH_LONG).show()
                                } finally {
                                    loading = false
                                }
                            }
                        }) { Text("Refresh ownership") }
                        Spacer(modifier = Modifier.padding(6.dp))
                        TextButton(onClick = {
                            showDiagnostics = !showDiagnostics
                            if (showDiagnostics) {
                                diagnostics.clear()
                                scope.launch {
                                    diagnostics.add("-- Diagnostics --")
                                    try {
                                        val appDlc = SteamService.getAppDlc(appId)
                                        diagnostics.add("getAppDlc: ${appDlc.size} entries")
                                        appDlc.forEach { (k, v) ->
                                            diagnostics.add("Depot ${k}: dlcAppId=${v.dlcAppId} osArch=${v.osArch} osList=${v.osList} language='${v.language}' manifests=${v.manifests.keys}")
                                        }
                                    } catch (_: Throwable) {
                                        diagnostics.add("getAppDlc: failed")
                                    }

                                    try {
                                        val (downloadables, excludedReasons) = SteamService.getDownloadableDepotsDebug(appId)
                                        diagnostics.add("getDownloadableDepots: ${downloadables.size} entries")
                                        downloadables.forEach { (k, v) ->
                                            diagnostics.add("Downloadable Depot ${k}: dlcAppId=${v.dlcAppId} osArch=${v.osArch} language='${v.language}' manifests=${v.manifests.keys}")
                                        }
                                        if (excludedReasons.isNotEmpty()) {
                                            diagnostics.add("-- Excluded depots (reason) --")
                                            excludedReasons.forEach { r -> diagnostics.add(r) }
                                        }
                                        // Show potential depots that were excluded only for OS/arch/lang reasons
                                        val potentials = SteamService.getPotentialDownloadableDepots(appId)
                                        if (potentials.isNotEmpty()) {
                                            diagnostics.add("-- Potential depots (relaxed) --")
                                            potentials.forEach { (did, pair) ->
                                                val (dep, reason) = pair
                                                diagnostics.add("Potential Depot ${did}: dlcAppId=${dep.dlcAppId} osArch=${dep.osArch} language='${dep.language}' manifests=${dep.manifests.keys}  reason=$reason")
                                            }
                                        }
                                    } catch (_: Throwable) {
                                        diagnostics.add("getDownloadableDepots: failed")
                                    }

                                    try {
                                        val manifest = SteamService.getAppDlc(appId).values.map { it.dlcAppId }.distinct()
                                        val ownedSet = if (manifest.isNotEmpty()) SteamService.checkDlcOwnershipViaPICSBatch(manifest.toSet()) else emptySet<Int>()
                                        diagnostics.add("PICS-owned DLC appIds: ${ownedSet.joinToString(",")}")
                                    } catch (_: Throwable) {
                                        diagnostics.add("PICS ownership check: failed")
                                    }

                                    try {
                                        val inst = SteamService.getInstalledDepotsOf(appId) ?: emptyList()
                                        diagnostics.add("installedDepots: ${inst.joinToString(",")}")
                                    } catch (_: Throwable) {
                                        diagnostics.add("installedDepots: failed")
                                    }
                                }
                            }
                        }) { Text(if (showDiagnostics) "Hide diagnostics" else "Show diagnostics") }
                    }

                    // Manual add DLC by App ID
                    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.Start) {
                        androidx.compose.material3.OutlinedTextField(
                            value = manualDlcInput,
                            onValueChange = { manualDlcInput = it },
                            label = { Text("Add DLC App ID") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.padding(6.dp))
                        Button(onClick = {
                            scope.launch {
                                val txt = manualDlcInput.trim()
                                val id = txt.toIntOrNull()
                                if (id == null) {
                                    Toast.makeText(ctx, "Invalid app id", Toast.LENGTH_SHORT).show()
                                    return@launch
                                }

                                try {
                                    val info = SteamService.getAppInfoOf(id)
                                    val name = info?.name ?: "App $id"
                                    // check ownership via PICS for this single app id
                                    val owned = try {
                                        SteamService.checkDlcOwnershipViaPICSBatch(setOf(id)).contains(id)
                                    } catch (_: Throwable) {
                                        false
                                    }

                                    // add or update known list
                                    val existingIndex = knownDlcList.indexOfFirst { it.first == id }
                                    if (existingIndex >= 0) {
                                        knownDlcList[existingIndex] = Triple(id, name, owned)
                                    } else {
                                        knownDlcList.add(Triple(id, name, owned))
                                    }

                                    Toast.makeText(ctx, "Added: $name (${if (owned) "owned" else "not owned"})", Toast.LENGTH_SHORT).show()
                                    manualDlcInput = ""
                                } catch (t: Throwable) {
                                    Toast.makeText(ctx, "Failed to query app: ${t.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) { Text("Add") }
                    }

                    if (loading) {
                        Text("Loading...")
                    }

                    // Diagnostics view (toggleable)
                    if (showDiagnostics) {
                        Surface(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(top = 8.dp)) {
                            LazyColumn {
                                items(diagnostics) { line ->
                                    Text(line, modifier = Modifier.padding(6.dp))
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    } else if (dlcList.isEmpty()) {
                        if (knownDlcList.isNotEmpty()) {
                            Column {
                                Text("Known DLC for this game:")
                                Spacer(modifier = Modifier.height(8.dp))
                                knownDlcList.forEach { (did, name, owned) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(text = name)
                                        Row {
                                            Text(text = if (owned) "Owned" else "Not owned")
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                Text("No downloadable depots available for this platform.")
                            }
                        } else {
                            Column {
                                Text("No owned DLC detected for this game.")
                                Spacer(modifier = Modifier.height(8.dp))
                                // hint for user
                                Text("Tap 'Refresh ownership' to re-check Steam ownership.")
                            }
                        }
                    } else {
                        LazyColumn {
                            items(dlcList) { (depotId, depotInfo) ->
                                val checked = remember { checkedMap[depotId] ?: false }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = (SteamService.getAppInfoOf(depotInfo.dlcAppId)?.name ?: "DLC ${depotInfo.dlcAppId}"))
                                    Row {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = { isChecked ->
                                                // Update the local (unsaved) state only
                                                checkedMap[depotId] = isChecked
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            // Save — compute differences and apply
            Button(onClick = {
                scope.launch {
                    try {
                        val newSelected = checkedMap.filterValues { it }.keys.toList()
                        val removed = installedDepots.filterNot { it in newSelected }
                        val added = newSelected.filterNot { it in installedDepots }

                            val selectedDlcFromDepots = dlcList.filter { (depotId, _) -> depotId in newSelected }
                                .map { it.second.dlcAppId }
                            val selectedManualDlc = knownDlcList.filter { it.third }.map { it.first }
                            val selectedDlcAppIds = (selectedDlcFromDepots + selectedManualDlc).distinct()

                        val setOk = SteamService.setAppSelection(appId, newSelected, selectedDlcAppIds)
                        if (!setOk) {
                            Toast.makeText(ctx, "Failed to save selection", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(ctx, "Applying changes and rebuilding...", Toast.LENGTH_SHORT).show()
                            SteamService.rebuildAppWithDepots(appId, newSelected)
                        }
                    } catch (t: Throwable) {
                        Toast.makeText(ctx, "Failed to apply DLC changes", Toast.LENGTH_SHORT).show()
                    } finally {
                        onDismissRequest()
                    }
                }
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = { onDismissRequest() }) { Text("Cancel") }
        }
    )
}
