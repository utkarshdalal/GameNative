package app.gamenative.ui.component.dialog

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.R
import app.gamenative.ui.component.settings.SettingsCenteredLabel
import app.gamenative.ui.theme.settingsTileColors
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.winlator.container.Container

@Composable
fun DrivesTabContent(state: ContainerConfigState) {
    val context = LocalContext.current
    val config = state.config.value
    SettingsGroup() {
        if (config.drives.isNotEmpty()) {
            for (drive in Container.drivesIterator(config.drives)) {
                val driveLetter = drive[0]
                val drivePath = drive[1]
                SettingsMenuLink(
                    colors = settingsTileColors(),
                    title = { Text(driveLetter) },
                    subtitle = { Text(drivePath) },
                    onClick = {},
                    action = if (driveLetter !in state.nonDeletableDriveLetters) {
                        {
                            IconButton(
                                onClick = {
                                    val drivesBuilder = StringBuilder()
                                    for (existingDrive in Container.drivesIterator(config.drives)) {
                                        if (existingDrive[0] != driveLetter) {
                                            drivesBuilder.append("${existingDrive[0]}:${existingDrive[1]}")
                                        }
                                    }
                                    state.config.value = config.copy(drives = drivesBuilder.toString())
                                },
                                content = {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "Delete drive",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        } else {
            SettingsCenteredLabel(
                colors = settingsTileColors(),
                title = { Text(text = stringResource(R.string.no_drives)) },
            )
        }

        SettingsMenuLink(
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddCircleOutline,
                        contentDescription = "Add drive",
                    )
                }
            },
            onClick = {
                if (state.availableDriveLetters.isEmpty()) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.no_available_drive_letters),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@SettingsMenuLink
                }
                state.selectedDriveLetter.value = state.availableDriveLetters.first()
                state.driveLetterMenuExpanded.value = false
                state.showAddDriveDialog.value = true
            },
        )
    }

    if (state.showAddDriveDialog.value) {
        AlertDialog(
            onDismissRequest = { state.showAddDriveDialog.value = false },
            title = { Text(text = stringResource(R.string.add_drive)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = state.selectedDriveLetter.value,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(text = stringResource(R.string.drive_letter)) },
                        trailingIcon = {
                            IconButton(
                                onClick = { state.driveLetterMenuExpanded.value = true },
                                content = {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Outlined.ViewList,
                                        contentDescription = null,
                                    )
                                },
                            )
                        },
                    )
                    DropdownMenu(
                        expanded = state.driveLetterMenuExpanded.value,
                        onDismissRequest = { state.driveLetterMenuExpanded.value = false },
                    ) {
                        state.availableDriveLetters.forEach { letter ->
                            DropdownMenuItem(
                                text = { Text(text = letter) },
                                onClick = {
                                    state.selectedDriveLetter.value = letter
                                    state.driveLetterMenuExpanded.value = false
                                },
                            )
                        }
                    }
                    if (state.availableDriveLetters.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_available_drive_letters),
                            color = MaterialTheme.colorScheme.error,
                            style = TextStyle(fontSize = 14.sp),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = state.selectedDriveLetter.value.isNotBlank() &&
                        state.availableDriveLetters.contains(state.selectedDriveLetter.value),
                    onClick = { state.launchFolderPicker() },
                    content = { Text(text = stringResource(R.string.ok)) },
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { state.showAddDriveDialog.value = false },
                    content = { Text(text = stringResource(R.string.cancel)) },
                )
            },
        )
    }
}
