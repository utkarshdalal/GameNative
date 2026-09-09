package app.gamenative.ui.screen.savebackup

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.savebackup.BrowserEntry
import app.gamenative.savebackup.ConfirmResult
import app.gamenative.savebackup.ContainerBrowser
import app.gamenative.savebackup.NavResult
import app.gamenative.savebackup.OpenResult
import app.gamenative.savebackup.SaveLocation
import app.gamenative.savebackup.SaveRootShortcut
import app.gamenative.ui.theme.PluviaTheme
import java.nio.file.Path
import kotlinx.coroutines.launch

/**
 * The in-app [ContainerBrowser] UI (Requirement 3, Task 8.5).
 *
 * The composable is a thin view over the pure [ContainerBrowser]/[ContainerBrowser.BrowserView]:
 * all navigation, confirm mapping and the "listable ≠ selectable" rule live in the pure component,
 * and this screen only holds the current [ContainerBrowser.BrowserView] as view state and calls
 * [ContainerBrowser.BrowserView.into]/[ContainerBrowser.BrowserView.up]/[ContainerBrowser.BrowserView.confirm]
 * in response to user actions, updating state from the results.
 *
 * The screen is driven from an already-computed [OpenResult] so it stays testable and the
 * orchestrator (Task 11) opens the real container. When [openResult] is [OpenResult.Unavailable]
 * the screen shows an error surface with the reason and offers no navigable list (Requirement 3.2).
 *
 * @param openResult the outcome of [ContainerBrowser.open] — the initial view or the unavailable
 *   reason.
 * @param driveCPath the absolute `drive_c` root path, used to render the breadcrumb relative to it.
 * @param onConfirmed invoked with the mapped [SaveLocation] when the user selects a confirmable
 *   folder (Requirement 3.8). Persistence is the caller's responsibility (Task 8.2/orchestrator).
 * @param onCancel invoked when the user dismisses the browser without selecting a location.
 * @param onError optional channel for surfacing navigation/confirm errors to the caller in addition
 *   to the in-screen snackbar (Requirement 3.5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerBrowserScreen(
    openResult: OpenResult,
    driveCPath: Path,
    onConfirmed: (SaveLocation) -> Unit,
    onCancel: () -> Unit,
    onError: (String) -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // View state: the current BrowserView, or null when the container is unavailable.
    var view by remember {
        mutableStateOf((openResult as? OpenResult.Available)?.view)
    }

    fun surfaceError(message: String) {
        onError(message)
        scope.launch {
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.container_browser_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            val currentView = view
            if (currentView != null) {
                ConfirmBar(
                    isConfirmable = currentView.isConfirmable,
                    onConfirm = {
                        when (val result = currentView.confirm()) {
                            is ConfirmResult.Selected -> onConfirmed(result.saveLocation)
                            is ConfirmResult.Rejected -> surfaceError(result.reason)
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        val currentView = view
        if (currentView == null) {
            val reason = (openResult as? OpenResult.Unavailable)?.reason
                ?: stringResource(R.string.container_browser_unavailable)
            UnavailableSurface(
                reason = reason,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Breadcrumb(
                driveC = driveCPath,
                currentDir = currentView.currentDir,
                canGoUp = currentView.canGoUp,
                onUp = {
                    when (val result = currentView.up()) {
                        is NavResult.Ok -> view = result.view
                        is NavResult.Error -> surfaceError(result.reason)
                    }
                },
            )

            val shortcuts = currentView.saveRootShortcuts()
            if (shortcuts.isNotEmpty()) {
                SaveRootShortcuts(
                    shortcuts = shortcuts,
                    onShortcut = { shortcut ->
                        navigateTo(
                            currentView = currentView,
                            targetPath = shortcut.path,
                            driveC = driveCPath,
                            onNavigate = { view = it },
                            onError = ::surfaceError,
                        )
                    },
                )
            }

            val entries = currentView.list()
            if (entries.isEmpty()) {
                EmptyDirectoryMessage(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(entries, key = { it.path.toString() }) { entry ->
                        DirectoryEntryRow(
                            entry = entry,
                            onClick = {
                                if (entry.isDirectory) {
                                    when (val result = currentView.into(entry.path)) {
                                        is NavResult.Ok -> view = result.view
                                        is NavResult.Error -> surfaceError(result.reason)
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Navigate the browser to an arbitrary [targetPath] under `drive_c` (used by the save-root
 * shortcuts, which may be several levels below the current view). Because the pure
 * [ContainerBrowser.BrowserView.into] only steps into an immediate child, this walks up from the
 * [currentView] to the `drive_c` root and then descends segment-by-segment to [targetPath],
 * surfacing an error and leaving the view unchanged if any step is unreadable (Requirement 3.5).
 */
private fun navigateTo(
    currentView: ContainerBrowser.BrowserView,
    targetPath: Path,
    driveC: Path,
    onNavigate: (ContainerBrowser.BrowserView) -> Unit,
    onError: (String) -> Unit,
) {
    // Walk up to the drive_c root so the descent below is valid from a known base.
    var rootView: ContainerBrowser.BrowserView = currentView
    while (rootView.canGoUp) {
        when (val up = rootView.up()) {
            is NavResult.Ok -> rootView = up.view
            is NavResult.Error -> {
                onError(up.reason)
                return
            }
        }
    }

    // Descend from drive_c down to the target, one immediate child at a time.
    var walkView = rootView
    var walkPath = rootView.currentDir
    for (segment in driveC.normalize().relativize(targetPath.normalize())) {
        val next = walkPath.resolve(segment)
        when (val result = walkView.into(next)) {
            is NavResult.Ok -> {
                walkView = result.view
                walkPath = next
            }
            is NavResult.Error -> {
                onError(result.reason)
                return
            }
        }
    }
    onNavigate(walkView)
}

/**
 * The breadcrumb from `drive_c` to the current directory plus the up control. The up control is
 * disabled at the `drive_c` root so navigation above it cannot be initiated (Requirement 3.7).
 */
@Composable
private fun Breadcrumb(
    driveC: Path,
    currentDir: Path,
    canGoUp: Boolean,
    onUp: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onUp,
            enabled = canGoUp,
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.container_browser_up),
            )
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val crumbs = breadcrumbSegments(driveC, currentDir)
            crumbs.forEachIndexed { index, label ->
                if (index > 0) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (index == crumbs.lastIndex) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** Compute the breadcrumb labels: "drive_c" then each segment down to [currentDir]. */
private fun breadcrumbSegments(driveC: Path, currentDir: Path): List<String> {
    val labels = mutableListOf("drive_c")
    if (currentDir.normalize() != driveC.normalize()) {
        val relative = driveC.normalize().relativize(currentDir.normalize())
        relative.forEach { labels.add(it.toString()) }
    }
    return labels
}

/**
 * The save-root shortcuts row (Requirement 3.3): the common Windows save roots that exist within
 * `drive_c`, shown prominently as quick-nav chips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SaveRootShortcuts(
    shortcuts: List<SaveRootShortcut>,
    onShortcut: (SaveRootShortcut) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = stringResource(R.string.container_browser_save_roots),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(6.dp))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(shortcuts, key = { it.path.toString() }) { shortcut ->
                AssistChip(
                    onClick = { onShortcut(shortcut) },
                    label = { Text(shortcut.label) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                )
            }
        }
    }
}

/** A single directory or file row. Files are shown greyed out and are not navigable. */
@Composable
private fun DirectoryEntryRow(
    entry: BrowserEntry,
    onClick: () -> Unit,
) {
    val contentColor = if (entry.isDirectory) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (entry.isDirectory) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (entry.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = entry.name,
            style = MaterialTheme.typography.bodyLarge,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (entry.isDirectory) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * The "Select this folder" action wired to confirm. Disabled with an explanation when the current
 * view is not confirmable (Requirement 3.9; Design "listable ≠ selectable").
 */
@Composable
private fun ConfirmBar(
    isConfirmable: Boolean,
    onConfirm: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            if (!isConfirmable) {
                Text(
                    text = stringResource(R.string.container_browser_not_selectable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Button(
                onClick = onConfirm,
                enabled = isConfirmable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.container_browser_select_folder))
            }
        }
    }
}

/** The error surface shown when the container filesystem is unavailable (Requirement 3.2). */
@Composable
private fun UnavailableSurface(
    reason: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.container_browser_unavailable),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyDirectoryMessage(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.container_browser_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun Preview_ContainerBrowserUnavailable() {
    PluviaTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            UnavailableSurface(
                reason = ContainerBrowser.CONTAINER_UNAVAILABLE,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun Preview_ConfirmBar() {
    PluviaTheme {
        Column {
            ConfirmBar(isConfirmable = true, onConfirm = {})
            Spacer(modifier = Modifier.height(16.dp))
            ConfirmBar(isConfirmable = false, onConfirm = {})
        }
    }
}
