package app.gamenative.ui.screen.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.ui.component.dialog.ScreenshotControllerBindDialog
import app.gamenative.ui.component.dialog.currentScreenshotComboLabel
import app.gamenative.ui.components.rememberCustomGameFolderPicker
import app.gamenative.ui.components.requestPermissionsForPath
import app.gamenative.ui.theme.settingsTileColorsAlt
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.utils.CustomGameScanner
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink

@Composable
fun SettingsGroupScreenshots() {
    val context = LocalContext.current
    var useExternal by rememberSaveable { mutableStateOf(PrefManager.screenshotUseExternal) }
    var externalPath by rememberSaveable { mutableStateOf(PrefManager.screenshotExternalPath) }

    val internalLabel = stringResource(R.string.settings_screenshots_storage_internal)
    val folderFailedMsg = stringResource(R.string.settings_screenshots_folder_failed)
    val unboundLabel = stringResource(R.string.settings_screenshots_controller_unbound)

    var showBindDialog by rememberSaveable { mutableStateOf(false) }
    var boundButtonLabel by remember { mutableStateOf(currentScreenshotComboLabel(context)) }

    // Same all-files-access flow custom game folders use: needed to write screenshots into a
    // user-picked folder outside the app sandbox (pre-30 runtime perms; 30+ all-files settings page).
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    val folderPicker = rememberCustomGameFolderPicker(
        onPathSelected = { path ->
            externalPath = path
            useExternal = true
            PrefManager.screenshotExternalPath = path
            PrefManager.screenshotUseExternal = true
            // Ensure we can actually write there; request broad storage access if not.
            val folder = java.io.File(path)
            val canAccess = runCatching { folder.isDirectory && folder.canWrite() }.getOrDefault(false)
            if (!canAccess && !CustomGameScanner.hasStoragePermission(context, path)) {
                requestPermissionsForPath(context, path, storagePermissionLauncher)
            }
        },
        onFailure = { SnackbarManager.show(folderFailedMsg) },
    )

    SettingsGroup(modifier = Modifier.background(Color.Transparent)) {
        val storageSubtitle = if (useExternal && externalPath.isNotBlank()) externalPath else internalLabel
        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.settings_screenshots_storage_title)) },
            subtitle = { Text(text = storageSubtitle) },
            onClick = { folderPicker.launchPicker() },
        )

        if (useExternal && externalPath.isNotBlank()) {
            SettingsMenuLink(
                colors = settingsTileColorsAlt(),
                title = { Text(text = stringResource(R.string.settings_screenshots_use_internal)) },
                onClick = {
                    useExternal = false
                    externalPath = ""
                    PrefManager.screenshotUseExternal = false
                    PrefManager.screenshotExternalPath = ""
                },
            )
        }

        SettingsMenuLink(
            colors = settingsTileColorsAlt(),
            title = { Text(text = stringResource(R.string.settings_screenshots_controller_bind)) },
            subtitle = { Text(text = boundButtonLabel ?: unboundLabel) },
            onClick = { showBindDialog = true },
        )
    }

    if (showBindDialog) {
        ScreenshotControllerBindDialog(
            onDismiss = { showBindDialog = false },
            onChanged = { newLabel -> boundButtonLabel = newLabel },
        )
    }
}
