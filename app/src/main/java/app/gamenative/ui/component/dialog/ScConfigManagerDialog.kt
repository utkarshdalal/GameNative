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
            val parsed = withContext(Dispatchers.IO) { ScConfigStore.validate(text) }
            if (parsed == null) { SnackbarManager.show("Not a valid Steam Controller .vdf config"); return@launch }
            // Name the config from the vdf's top-level `title` (e.g. "testGyroect123"), falling back to the file name,
            // then a generic label — instead of the old "Imported (.vdf)" (which doubled the "(.vdf)" list suffix).
            val title = Regex("\"title\"\\s+\"([^\"#][^\"]*)\"").find(text)?.groupValues?.getOrNull(1)?.trim()
            val fileName = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i)?.substringBeforeLast('.') else null
                }
            }.getOrNull()
            val name = title?.takeIf { it.isNotBlank() } ?: fileName?.takeIf { it.isNotBlank() } ?: "Imported"
            val newId = withContext(Dispatchers.IO) { ScConfigStore.importVdfConfig(context, storeKey, text, name) }
            version++; onChanged()
            SnackbarManager.show(
                if (newId != null) "Imported ${parsed.sets.size} action set(s) — now active" else "Could not save the imported config",
            )
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
                Text("Manage configs", style = MaterialTheme.typography.titleLarge)
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
                }
                ScNavRow(nav, 4, "Import config (.vdf)") { importLauncher.launch(arrayOf("*/*")) }
                if (hasLabels) {
                    ScNavRow(nav, 5, "Remove custom menu labels") {
                        if (ScConfigStore.removeLabels(context, storeKey)) {
                            version++; onChanged(); SnackbarManager.show("Removed custom menu labels")
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    ScNavItem(nav, line = 6, onActivate = onDismiss) { ScActionChip("Done", onClick = onDismiss, filled = true) }
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
