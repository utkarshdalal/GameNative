package app.gamenative.ui.component.dialog

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.steamcontroller.ScConfigKind
import app.gamenative.steamcontroller.ScConfigStore
import app.gamenative.ui.util.SnackbarManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-game Steam Controller config management — the piece that used to live in the container-config Controller tab,
 * now folded into the QuickMenu so SC settings never bleed into container settings (they matter to almost no one
 * there). Lists the running game's saved configs ([storeKey]) and lets the user pick the active one, duplicate /
 * rename / delete it, import a Steam `.vdf`, and clear custom menu labels. [onChanged] fires after any change so the
 * live driver reloads with no relaunch. Controller-navigable (d-pad + A, B = back) like the other QuickMenu editors;
 * the pad cursor also works. Editing the active config's bindings lives in the separate Bindings editor.
 */
@Composable
fun ScConfigManagerDialog(storeKey: String, onChanged: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Bumped after any change to recompute the list + active entry.
    var version by remember { mutableIntStateOf(0) }
    val configs = remember(version) { ScConfigStore.listConfigs(context, storeKey) }
    val activeId = remember(version) { ScConfigStore.activeConfigId(context, storeKey) }
    val activeEntry = configs.firstOrNull { it.id == activeId } ?: configs.firstOrNull()
    val hasLabels = remember(version) { ScConfigStore.hasLabels(context, storeKey) }

    var showSelector by remember { mutableStateOf(false) }
    var nameAction by remember { mutableStateOf<NameAction?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            }
            if (text.isNullOrBlank()) { SnackbarManager.show("Could not read the selected file"); return@launch }
            // The config is named after the file you picked ("Raid healer.gnsc.json" → "Raid healer"), like a document.
            val fileName = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) {
                        c.getString(i)?.replace(Regex("""(\.gnsc)?\.json$|\.vdf$""", RegexOption.IGNORE_CASE), "")
                    } else null
                }
            }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
            // A GameNative export (from "Export active config") restores as-is, edits included.
            withContext(Dispatchers.IO) { ScConfigStore.parseExport(text) }?.let { export ->
                // An unrenamed file only carries the sanitized name ("Raid_ healer"); keep the exact stored one then.
                val name = fileName?.takeIf { it != exportFileStem(export.name) } ?: export.name
                val newId = withContext(Dispatchers.IO) { ScConfigStore.importExport(context, storeKey, export, name) }
                version++; onChanged()
                SnackbarManager.show(if (newId != null) "Imported $name — now active" else "Could not import the config")
                return@launch
            }
            val parsed = withContext(Dispatchers.IO) { ScConfigStore.validate(text) }
            if (parsed == null) { SnackbarManager.show("Not a Steam .vdf or exported GameNative config"); return@launch }
            // Fallback when the file name is unavailable: the vdf's top-level `title`, then a generic label.
            val title = Regex("\"title\"\\s+\"([^\"#][^\"]*)\"").find(text)?.groupValues?.getOrNull(1)?.trim()
            val name = fileName ?: title?.takeIf { it.isNotBlank() } ?: "Imported"
            val newId = withContext(Dispatchers.IO) { ScConfigStore.importVdfConfig(context, storeKey, text, name) }
            version++; onChanged()
            SnackbarManager.show(
                if (newId != null) "Imported ${parsed.sets.size} action set(s) — now active" else "Could not save the imported config",
            )
        }
    }

    // Export: the config id waiting for the user to pick a destination file.
    var exportingId by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val id = exportingId
        exportingId = null
        if (uri == null || id == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val text = ScConfigStore.exportConfig(context, storeKey, id) ?: return@withContext false
                runCatching { context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(text) } != null }.getOrDefault(false)
            }
            SnackbarManager.show(if (ok) "Exported" else "Could not export the config")
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).padding(8.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
        ) {
            val nav = remember { ScNavState() }
            ScNavDialogColumn(nav, onBack = onDismiss, modifier = Modifier.padding(16.dp)) {
                ScBackTitle("Manage configs", onBack = onDismiss)
                Text(
                    if (activeEntry == null) "No saved config — using the built-in default mapping."
                    else "${configs.size} saved · active: ${activeEntry.name}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                HorizontalDivider()

                if (activeEntry != null) {
                    ScNavRow(nav, 0, "Active config: ${activeEntry.name}  ▾") { showSelector = true }
                    ScNavRow(nav, 1, "Duplicate active config") {
                        nameAction = NameAction(NameActionKind.DUPLICATE, activeEntry.id, "${activeEntry.name} copy")
                    }
                    ScNavRow(nav, 2, "Rename active config") {
                        nameAction = NameAction(NameActionKind.RENAME, activeEntry.id, activeEntry.name)
                    }
                    ScNavRow(nav, 3, "Delete active config") {
                        if (ScConfigStore.deleteConfig(context, storeKey, activeEntry.id)) {
                            version++; onChanged(); SnackbarManager.show("Deleted ${activeEntry.name}")
                        }
                    }
                    ScNavRow(nav, 4, "Export active config") {
                        exportingId = activeEntry.id
                        exportLauncher.launch(exportFileStem(activeEntry.name) + ".gnsc.json")
                    }
                }
                ScNavRow(nav, 5, "Import config (Steam .vdf or export)") { importLauncher.launch(arrayOf("*/*")) }
                if (hasLabels) {
                    ScNavRow(nav, 6, "Remove custom menu labels") {
                        if (ScConfigStore.removeLabels(context, storeKey)) {
                            version++; onChanged(); SnackbarManager.show("Removed custom menu labels")
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    ScNavItem(nav, line = 7, onActivate = onDismiss) { ScActionChip("Done", onClick = onDismiss, filled = true) }
                }
            }
        }
    }

    if (showSelector) {
        ScNavChoiceDialog(
            title = "Active config",
            options = configs.map { it.id to (it.name + if (it.kind == ScConfigKind.VDF) "  (.vdf)" else "  (custom)") },
            selected = activeId ?: "",
            onPick = { id -> if (ScConfigStore.setActiveConfig(context, storeKey, id)) { version++; onChanged() } },
            onDismiss = { showSelector = false },
        )
    }

    nameAction?.let { action ->
        ScOnScreenKeyboardDialog(
            label = if (action.kind == NameActionKind.DUPLICATE) "Duplicate as" else "Rename to",
            initial = action.initial,
            onCancel = { nameAction = null },
            onDone = { name ->
                val ok = when (action.kind) {
                    NameActionKind.DUPLICATE -> ScConfigStore.duplicateConfig(context, storeKey, action.configId, name) != null
                    NameActionKind.RENAME -> ScConfigStore.renameConfig(context, storeKey, action.configId, name)
                }
                nameAction = null
                if (ok) { version++; onChanged(); SnackbarManager.show(if (action.kind == NameActionKind.DUPLICATE) "Duplicated" else "Renamed") }
            },
        )
    }
}

/** A full-width, d-pad-navigable text row (the config manager's list items all share this shape). */
@Composable
private fun ScNavRow(nav: ScNavState, line: Int, label: String, onActivate: () -> Unit) {
    ScNavItem(nav, line = line, modifier = Modifier.fillMaxWidth(), onActivate = onActivate) {
        Text(label, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp))
    }
}

private enum class NameActionKind { DUPLICATE, RENAME }

/** A pending duplicate/rename name prompt for config [configId]. */
private data class NameAction(val kind: NameActionKind, val configId: String, val initial: String)

/** File-name-safe form of a config name, used for the export's suggested file name. */
private fun exportFileStem(name: String): String = name.replace(Regex("[^A-Za-z0-9 ._-]"), "_")
