package app.gamenative.ui.screen.xserver

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.ui.component.dialog.ControllerPresetManager
import app.gamenative.ui.component.dialog.LoadLayoutPresetDialog
import app.gamenative.ui.component.dialog.SaveLayoutPresetDialog
import app.gamenative.ui.util.SnackbarManager
import com.winlator.container.Container
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.widget.InputControlsView
import timber.log.Timber

class XServerScreenUtils {
    /**
     * Replace DLLs from DirectX Redistributable
     */
    companion object {
        fun applyDefaultControllerPresetIfNeeded(
            context: Context,
            container: Container,
            targetProfile: ControlsProfile,
        ) {
            ControllerPresetManager.ensureWildcardController(context, targetProfile)
            synchronized(container) {
                if (container.getExtra(ControllerPresetManager.CONTROLLER_PRESET_APPLIED_KEY).isEmpty()) {
                    if (ControllerPresetManager.applyDefaultPreset(context, targetProfile)) {
                        Timber.d("Applied default controller preset for container: ${container.name}")
                    }
                    container.putExtra(ControllerPresetManager.CONTROLLER_PRESET_APPLIED_KEY, "true")
                    container.saveData()
                }
            }
        }

        fun applyDefaultLayoutPresetIfNeeded(
            context: Context,
            container: Container,
            profile: ControlsProfile,
            icView: InputControlsView,
        ) {
            synchronized(container) {
                if (container.getExtra(ControllerPresetManager.LAYOUT_PRESET_APPLIED_KEY).isEmpty()) {
                    if (ControllerPresetManager.applyDefaultLayoutPreset(context, profile, icView)) {
                        Timber.d("Applied default layout preset for container: ${container.name}")
                    }
                    container.putExtra(ControllerPresetManager.LAYOUT_PRESET_APPLIED_KEY, "true")
                    container.saveData()
                }
            }
        }

    }
}

data class LayoutPresetCallbacks(
    val onSave: () -> Unit,
    val onLoad: () -> Unit,
    val onExport: () -> Unit,
    val onImport: () -> Unit,
)

@Composable
fun rememberLayoutPresetCallbacks(context: Context): LayoutPresetCallbacks {
    var showSaveDialog by remember { mutableStateOf(false) }
    var showLoadDialog by remember { mutableStateOf(false) }

    val applyLayoutPresetToView = { preset: ControllerPresetManager.LayoutPreset ->
        val currentProfile = PluviaApp.inputControlsView?.profile
        PluviaApp.inputControlsView?.let { icView ->
            if (currentProfile != null) {
                icView.post {
                    ControllerPresetManager.applyLayoutPreset(preset, currentProfile, icView)
                    SnackbarManager.show(context.getString(R.string.layout_preset_loaded))
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val profile = PluviaApp.inputControlsView?.profile
            if (profile != null) {
                val preset = ControllerPresetManager.currentLayoutPreset(profile)
                if (!ControllerPresetManager.exportLayoutPresetToUri(context, uri, preset)) {
                    SnackbarManager.show(context.getString(R.string.export_layout_preset_failed))
                }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val imported = ControllerPresetManager.importLayoutPresetFromUri(context, uri)
            if (imported != null) {
                applyLayoutPresetToView(imported)
            } else {
                SnackbarManager.show(context.getString(R.string.import_layout_preset_failed))
            }
        }
    }

    if (showSaveDialog) {
        SaveLayoutPresetDialog(
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                val profile = PluviaApp.inputControlsView?.profile
                if (profile != null) {
                    val saved = ControllerPresetManager.saveLayoutPreset(context, name, profile.elements)
                    SnackbarManager.show(
                        context.getString(
                            if (saved) R.string.layout_preset_saved else R.string.save_layout_preset_failed,
                        ),
                    )
                }
            },
        )
    }

    if (showLoadDialog) {
        LoadLayoutPresetDialog(
            onDismiss = { showLoadDialog = false },
            onPresetSelected = { preset -> applyLayoutPresetToView(preset) },
        )
    }

    return LayoutPresetCallbacks(
        onSave = { showSaveDialog = true },
        onLoad = { showLoadDialog = true },
        onExport = {
            val profile = PluviaApp.inputControlsView?.profile
            exportLauncher.launch("${profile?.name ?: "layout"}.json")
        },
        onImport = {
            importLauncher.launch(arrayOf("application/json"))
        },
    )
}

@Composable
fun EditToolbarOverflowMenu(
    onCopyFromProfile: (Int) -> Unit,
    layoutPresetCallbacks: LayoutPresetCallbacks,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    var copyFromOpen by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { overflowOpen = !overflowOpen }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More", tint = Color.White)
        }

        DropdownMenu(
            expanded = overflowOpen,
            onDismissRequest = { overflowOpen = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy_from)) },
                leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                onClick = {
                    overflowOpen = false
                    copyFromOpen = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.save_layout_preset)) },
                leadingIcon = { Icon(Icons.Default.SaveAlt, contentDescription = null) },
                onClick = {
                    overflowOpen = false
                    layoutPresetCallbacks.onSave()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.load_layout_preset)) },
                leadingIcon = { Icon(Icons.Default.FileOpen, contentDescription = null) },
                onClick = {
                    overflowOpen = false
                    layoutPresetCallbacks.onLoad()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.import_layout_preset)) },
                leadingIcon = { Icon(Icons.Default.FileDownload, contentDescription = null) },
                onClick = {
                    overflowOpen = false
                    layoutPresetCallbacks.onImport()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.export_layout_preset)) },
                leadingIcon = { Icon(Icons.Default.FileUpload, contentDescription = null) },
                onClick = {
                    overflowOpen = false
                    layoutPresetCallbacks.onExport()
                },
            )
        }
    }

    if (copyFromOpen) {
        val knownProfiles = PluviaApp.inputControlsManager?.getProfiles(false) ?: emptyList()
        DropdownMenu(
            expanded = true,
            onDismissRequest = { copyFromOpen = false },
        ) {
            for (knownProfile in knownProfiles) {
                DropdownMenuItem(
                    text = { Text(knownProfile.name) },
                    onClick = {
                        onCopyFromProfile(knownProfile.id)
                        copyFromOpen = false
                    },
                )
            }
        }
    }
}
