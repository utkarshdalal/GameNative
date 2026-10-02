package app.gamenative.ui.screen.savebackup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.gamenative.savebackup.SaveLocation
import app.gamenative.savebackup.SaveRootMatcher
import app.gamenative.ui.component.dialog.ContainerFilesDialogHeader
import app.gamenative.ui.component.dialog.ContainerFolderBrowser
import app.gamenative.ui.component.dialog.SAVE_BACKUP_BROWSABLE_ROOTS
import app.gamenative.ui.component.dialog.rememberContainerFolderBrowserState
import com.winlator.container.Container
import java.io.File
import java.nio.file.Paths

/**
 * Full-screen container-side save-location picker for the save-backup flow, built on the shared
 * [ContainerFolderBrowser] (game-save-backup Req 16 — one browser to maintain). The user navigates
 * the container filesystem and confirms a directory; the confirmed absolute folder is mapped to the
 * most-specific supported save root via [SaveRootMatcher], and a single-root [SaveLocation] is
 * returned through [onConfirmed]. A directory under no supported root cannot be confirmed (the
 * "Select this folder" button is disabled with an explanation — Req 3.9).
 *
 * This replaces the branch's bespoke `ContainerBrowser`/`ContainerBrowserScreen`.
 *
 * @param container the game's resolved Wine container (supplies `rootDir` + the wine prefix).
 * @param gameId numeric game id, passed to [SaveRootMatcher] so roots resolve exactly as the
 *   resolver would.
 * @param gameName shown in the header.
 * @param onConfirmed invoked with the mapped single-root [SaveLocation] when the user confirms a
 *   supported directory.
 * @param onCancel invoked when the user dismisses without selecting (orchestrator → Cancelled).
 */
@Composable
fun SaveLocationBrowserScreen(
    container: Container,
    gameId: Int,
    gameName: String,
    onConfirmed: (SaveLocation) -> Unit,
    onCancel: () -> Unit,
) {
    val winePrefix = File(container.rootDir, ".wine").absolutePath
    val state = rememberContainerFolderBrowserState(
        gameRootDir = null,
        winePrefix = winePrefix,
        browsableRoots = SAVE_BACKUP_BROWSABLE_ROOTS,
    )

    // The matcher resolves candidate roots exactly as the resolver does, so a confirmed folder maps
    // to the save root it will later resolve back to. SteamUserData is excluded from the browser
    // roots, so account-id-free candidate roots are sufficient here.
    val candidateRoots = remember(container, gameId) {
        SaveRootMatcher.candidateRoots(container, gameId, accountId = 0L, includeSteamUserData = false)
    }

    // The currently-browsed folder mapped to a single-root SaveLocation, or null when the folder is
    // under no supported save root (not confirmable — Req 3.9).
    val currentFolder: File? = state.folder
    val confirmable: SaveLocation? = remember(currentFolder) {
        currentFolder
            ?.let { folder -> SaveRootMatcher.match(candidateRoots, Paths.get(folder.absolutePath)) }
            ?.let { root -> SaveLocation.of(root) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            ContainerFilesDialogHeader(
                icon = Icons.Default.FolderOpen,
                title = "Select save folder",
                gameName = gameName,
                closeEnabled = true,
                onClose = onCancel,
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ContainerFolderBrowser(
                    state = state,
                    enabled = true,
                    noContainerText = "The container filesystem is unavailable.",
                    // Show files read-only so the user can confirm they're selecting the right save
                    // folder; they still select the folder, not individual files.
                    showFiles = true,
                    filesSelectable = false,
                )

                if (currentFolder != null && confirmable == null) {
                    Text(
                        text = "This folder is not a supported save location. " +
                            "Pick a folder under Documents, AppData, Saved Games, ProgramData, or the game directory.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { confirmable?.let(onConfirmed) },
                        enabled = confirmable != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Select this folder")
                    }
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
