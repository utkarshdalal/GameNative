package app.gamenative.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gamenative.PrefManager
import app.gamenative.utils.CustomGameScanner
import com.alorma.compose.settings.ui.SettingsGroup

@Composable
fun SettingsGroupCustomGames() {
    var paths by remember { mutableStateOf(PrefManager.customGamePaths.toMutableSet()) }
    var newPath by remember { mutableStateOf("") }

    val defaultPath = CustomGameScanner.defaultRootPath

    // Counts per root
    var counts by remember { mutableStateOf(CustomGameScanner.countGamesByRoot()) }

    // Automatically refresh counts when this section is shown and when paths change
    LaunchedEffect(Unit) {
        counts = CustomGameScanner.countGamesByRoot()
    }
    LaunchedEffect(paths) {
        counts = CustomGameScanner.countGamesByRoot()
    }

    SettingsGroup(title = { Text(text = "Custom Games") }) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(text = "Default root (always scanned):")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = defaultPath)
                    Text(text = "${counts[defaultPath] ?: 0} folders found")
                }
            }

            // Existing extra paths list
            if (paths.isEmpty()) {
                Text(text = "No additional paths added")
            } else {
                paths.forEach { path ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = path)
                            Text(text = "${counts[path] ?: 0} folders found")
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(onClick = {
                                val copy = paths.toMutableSet()
                                copy.remove(path)
                                paths = copy
                                PrefManager.customGamePaths = copy
                                // Counts will refresh via LaunchedEffect(paths)
                            }) {
                                Icon(imageVector = Icons.Default.Delete, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    modifier = Modifier.weight(1f),
                    value = newPath,
                    onValueChange = { newPath = it },
                    label = { Text("Add path") },
                    singleLine = true,
                )
                Button(
                    modifier = Modifier.heightIn(min = 56.dp),
                    onClick = {
                        val path = newPath.trim()
                        if (path.isNotEmpty()) {
                            val copy = paths.toMutableSet()
                            copy.add(path)
                            paths = copy
                            PrefManager.customGamePaths = copy
                            newPath = ""
                            // Counts will refresh via LaunchedEffect(paths)
                        }
                    }
                ) {
                    Icon(imageVector = Icons.Default.Add, contentDescription = null)
                    Text(text = "Add")
                }
            }

            Text(text = "Folders in these paths are scanned for .exe files and listed as custom games.", modifier = Modifier.padding(top = 8.dp))
        }
    }
}
