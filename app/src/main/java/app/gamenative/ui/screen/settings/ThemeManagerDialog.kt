package app.gamenative.ui.screen.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.PrefManager
import app.gamenative.R
import app.gamenative.service.SteamService
import app.gamenative.theme.CommunityTheme
import app.gamenative.theme.InstalledTheme
import app.gamenative.theme.ShareBlocker
import app.gamenative.theme.StoreTheme
import app.gamenative.theme.ThemeManager
import app.gamenative.theme.ThemeOption
import app.gamenative.ui.theme.MenuColorScope
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.component.NoExtractOutlinedTextField
import app.gamenative.ui.component.QuickMenuTickSlider
import app.gamenative.ui.component.dialog.AccountSignInDialog
import app.gamenative.ui.component.topbar.BackButton
import app.gamenative.ui.util.NavigateSoundOnFocusGain
import app.gamenative.ui.util.TabSoundOnChange
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class ThemeTab { INSTALLED, COMMUNITY, STEAM_DECK, SOUNDS }

private const val STARTER_THEME_FILE_NAME = "gamenative-starter-theme.zip"

private val COLOUR_FIELDS = setOf(
    "background", "surface", "surfaceVariant", "primary", "onPrimary",
    "text", "textMuted", "outline", "focus", "bar",
    "card", "button", "onButton", "toggle", "menu", "onMenu",
)

private val EFFECT_FIELDS = setOf("accentGradient", "focusGlow")

private data class OptionGroup(val head: ThemeOption, val attached: List<ThemeOption>)

private fun groupOptions(options: List<ThemeOption>): List<OptionGroup> {
    val groups = mutableListOf<OptionGroup>()
    for (option in options) {
        val last = groups.lastOrNull()
        val head = last?.head
        if (last != null &&
            head is ThemeOption.Choice &&
            head.kind != ThemeOption.Choice.Kind.SLIDER &&
            option !is ThemeOption.Choice &&
            option.key.startsWith(head.key + "/")
        ) {
            groups[groups.lastIndex] = last.copy(attached = last.attached + option)
        } else {
            groups += OptionGroup(option, emptyList())
        }
    }
    return groups
}

private class StoreState {
    var query by mutableStateOf("")
    val items = mutableStateListOf<StoreTheme>()
    var page by mutableStateOf(0)
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var endReached by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    val installing = mutableStateListOf<String>()
    var targetFilter by mutableStateOf<String?>(null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeManagerDialog(
    open: Boolean,
    onDismiss: () -> Unit,
) {
    if (!open) return

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val themes by ThemeManager.themes.collectAsState()

    var tab by rememberSaveable { mutableStateOf(ThemeTab.INSTALLED) }
    TabSoundOnChange(tab)
    var busy by remember { mutableStateOf(false) }
    val expanded = remember { mutableStateListOf<String>() }
    var deleteTarget by remember { mutableStateOf<InstalledTheme?>(null) }
    var colorTarget by remember { mutableStateOf<Pair<String, ThemeOption.Color>?>(null) }
    var pendingImage by remember { mutableStateOf<Pair<String, String>?>(null) }
    val store = remember { StoreState() }
    val updating = remember { mutableStateListOf<String>() }
    val community = remember { CommunityState() }
    val signedInPref by PrefManager.gameNativeSignedIn
    val signedIn = signedInPref && ThemeManager.isSignedIn
    var shareTarget by remember { mutableStateOf<InstalledTheme?>(null) }
    var shareBlocked by remember { mutableStateOf<ShareBlocker?>(null) }
    var reportTarget by remember { mutableStateOf<CommunityTheme?>(null) }
    var communityDeleteTarget by remember { mutableStateOf<CommunityTheme?>(null) }
    var signInNeeded by remember { mutableStateOf(false) }
    var showSignIn by remember { mutableStateOf(false) }
    var afterSignIn by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun failureText(e: Throwable): String =
        ctx.getString(R.string.themes_failed_message, e.message ?: e.javaClass.simpleName)

    fun installedText(result: Result<InstalledTheme>): String =
        result.fold({ ctx.getString(R.string.themes_installed_message, it.name) }, { failureText(it) })

    fun runBusy(block: suspend () -> String) {
        scope.launch {
            busy = true
            val message = withContext(Dispatchers.IO) { block() }
            busy = false
            snackbarHostState.showSnackbar(message)
        }
    }

    fun runAction(block: suspend () -> Unit) {
        scope.launch { withContext(Dispatchers.IO) { block() } }
    }

    fun loadStore(reset: Boolean) {
        if (store.loading) return
        scope.launch {
            store.loading = true
            store.error = null
            val nextPage = if (reset) 1 else store.page + 1
            val query = store.query.trim()
            val result = withContext(Dispatchers.IO) { ThemeManager.storeList(query, nextPage) }
            result.onSuccess { list ->
                if (reset) store.items.clear()
                val fresh = list.filter { item -> store.items.none { it.id == item.id } }
                store.items.addAll(fresh)
                store.page = nextPage
                store.endReached = list.isEmpty()
                store.loaded = true
            }.onFailure {
                store.error = it.message ?: it.javaClass.simpleName
            }
            store.loading = false
        }
    }

    fun installFromStore(theme: StoreTheme) {
        if (theme.id in store.installing) return
        scope.launch {
            store.installing.add(theme.id)
            val result = withContext(Dispatchers.IO) { ThemeManager.installFromStore(theme) }
            store.installing.remove(theme.id)
            snackbarHostState.showSnackbar(installedText(result))
        }
    }

    fun updateTheme(id: String) {
        if (id in updating) return
        scope.launch {
            updating.add(id)
            val result = withContext(Dispatchers.IO) { ThemeManager.updateFromStore(id) }
            updating.remove(id)
            result.onFailure { snackbarHostState.showSnackbar(failureText(it)) }
        }
    }

    fun requireSignIn(action: () -> Unit) {
        if (signedIn) {
            action()
        } else {
            afterSignIn = action
            signInNeeded = true
        }
    }

    fun loadCommunity(reset: Boolean) {
        if (!reset && community.loading) return
        community.job?.cancel()
        community.job = scope.launch {
            community.loading = true
            community.error = null
            val nextPage = if (reset) 1 else community.page + 1
            val result = withContext(Dispatchers.IO) {
                ThemeManager.communityList(community.query.trim(), nextPage, community.sort, community.mineOnly)
            }
            result.onSuccess { page ->
                if (reset) community.items.clear()
                val fresh = page.themes.filter { item -> community.items.none { it.id == item.id } }
                community.items.addAll(fresh)
                community.page = nextPage
                community.hasMore = page.hasMore
                community.loaded = true
            }.onFailure {
                if (reset) community.items.clear()
                community.error = communityErrorText(ctx, it)
            }
            community.loading = false
        }
    }

    fun installFromCommunity(theme: CommunityTheme) {
        if (theme.id in community.installing) return
        scope.launch {
            community.installing.add(theme.id)
            val result = withContext(Dispatchers.IO) { ThemeManager.installFromCommunity(theme) }
            community.installing.remove(theme.id)
            snackbarHostState.showSnackbar(
                result.fold({ ctx.getString(R.string.themes_installed_message, it.name) }, { communityErrorText(ctx, it) }),
            )
        }
    }

    fun deleteFromCommunity(theme: CommunityTheme) {
        if (theme.id in community.deleting) return
        scope.launch {
            community.deleting.add(theme.id)
            val result = withContext(Dispatchers.IO) { ThemeManager.deleteCommunityTheme(theme.id) }
            community.deleting.remove(theme.id)
            result.onSuccess { community.items.removeAll { it.id == theme.id } }
            community.focusRow = theme.id
            snackbarHostState.showSnackbar(
                result.fold({ ctx.getString(R.string.themes_community_deleted) }, { communityErrorText(ctx, it) }),
            )
        }
    }

    fun startShare(theme: InstalledTheme) {
        val blocker = ThemeManager.shareBlocker(theme.id)
        if (blocker != null) {
            shareBlocked = blocker
        } else {
            requireSignIn { shareTarget = theme }
        }
    }

    LaunchedEffect(signedIn) {
        if (!signedIn && community.mineOnly) {
            community.mineOnly = false
            if (community.loaded) loadCommunity(reset = true)
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ThemeManager.checkForUpdates() }
    }

    val zipLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        SteamService.isImporting = false
        uri ?: return@rememberLauncherForActivityResult
        runBusy { installedText(ThemeManager.importZip(uri)) }
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        SteamService.isImporting = false
        uri ?: return@rememberLauncherForActivityResult
        runBusy { installedText(ThemeManager.importFolder(uri)) }
    }
    val starterLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        SteamService.isImporting = false
        uri ?: return@rememberLauncherForActivityResult
        runBusy {
            ThemeManager.exportStarterTheme(uri).fold(
                { ctx.getString(R.string.themes_starter_saved) },
                { failureText(it) },
            )
        }
    }
    val imageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        SteamService.isImporting = false
        val target = pendingImage
        pendingImage = null
        if (uri != null && target != null) {
            runAction { ThemeManager.setImage(target.first, target.second, uri) }
        }
    }

    LaunchedEffect(tab) {
        if (tab == ThemeTab.STEAM_DECK && !store.loaded) loadStore(reset = true)
        if (tab == ThemeTab.COMMUNITY && !community.loaded && !community.loading) loadCommunity(reset = true)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(text = stringResource(R.string.themes_title)) },
                    navigationIcon = { BackButton(onClick = onDismiss) },
                )
            },
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                val tabFocusRequester = remember { FocusRequester() }
                val isWindowFocused = LocalWindowInfo.current.isWindowFocused
                LaunchedEffect(isWindowFocused) {
                    if (isWindowFocused) {
                        delay(100)
                        runCatching { tabFocusRequester.requestFocus() }
                    }
                }

                TabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.fillMaxWidth()) {
                    ThemeTab.entries.forEach { entry ->
                        Tab(
                            selected = entry == tab,
                            onClick = { tab = entry },
                            modifier = if (entry == tab) Modifier.focusRequester(tabFocusRequester) else Modifier,
                            text = {
                                Text(
                                    text = stringResource(
                                        when (entry) {
                                            ThemeTab.INSTALLED -> R.string.themes_tab_installed
                                            ThemeTab.COMMUNITY -> R.string.themes_tab_community
                                            ThemeTab.STEAM_DECK -> R.string.themes_tab_steam_deck
                                            ThemeTab.SOUNDS -> R.string.themes_tab_sounds
                                        },
                                    ),
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }

                when (tab) {
                    ThemeTab.INSTALLED -> InstalledThemesTab(
                        themes = themes,
                        busy = busy,
                        expanded = expanded,
                        updating = updating,
                        onUpdate = { updateTheme(it) },
                        onImportZip = {
                            SteamService.isImporting = true
                            zipLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip", "application/octet-stream"))
                        },
                        onImportFolder = {
                            SteamService.isImporting = true
                            folderLauncher.launch(null)
                        },
                        onCreateStarter = {
                            SteamService.isImporting = true
                            starterLauncher.launch(STARTER_THEME_FILE_NAME)
                        },
                        onToggleExpanded = { id ->
                            if (id in expanded) expanded.remove(id) else expanded.add(id)
                        },
                        onAction = { runAction(it) },
                        onDelete = { deleteTarget = it },
                        onShare = { startShare(it) },
                        onEditColor = { id, option -> colorTarget = id to option },
                        onPickImage = { id, key ->
                            pendingImage = id to key
                            SteamService.isImporting = true
                            imageLauncher.launch(arrayOf("image/*"))
                        },
                    )

                    ThemeTab.COMMUNITY -> CommunityThemesTab(
                        state = community,
                        installedByCommunityId = themes.filter { it.communityInstalled }.mapNotNull { t -> t.communityId?.let { it to t } }.toMap(),
                        signedIn = signedIn,
                        onSearch = { loadCommunity(reset = true) },
                        onSortChange = { sort ->
                            if (sort != community.sort) {
                                community.sort = sort
                                loadCommunity(reset = true)
                            }
                        },
                        onMineToggle = {
                            requireSignIn {
                                community.mineOnly = !community.mineOnly
                                loadCommunity(reset = true)
                            }
                        },
                        onLoadMore = { loadCommunity(reset = false) },
                        onRetry = { loadCommunity(reset = community.items.isEmpty()) },
                        onInstall = { installFromCommunity(it) },
                        onReport = { theme -> requireSignIn { reportTarget = theme } },
                        onDelete = { communityDeleteTarget = it },
                    )

                    ThemeTab.STEAM_DECK -> BrowseThemesTab(
                        store = store,
                        installedByName = themes.associateBy { it.name.lowercase(Locale.ROOT) },
                        updating = updating,
                        onSearch = { loadStore(reset = true) },
                        onLoadMore = { loadStore(reset = false) },
                        onRetry = { loadStore(reset = store.items.isEmpty()) },
                        onInstall = { installFromStore(it) },
                        onUpdate = { updateTheme(it) },
                    )

                    ThemeTab.SOUNDS -> ThemeSoundsTab(onMessage = { scope.launch { snackbarHostState.showSnackbar(it) } })
                }
            }
        }

        deleteTarget?.let { target ->
            MenuColorScope {
            AlertDialog(
                onDismissRequest = { deleteTarget = null },
                containerColor = PluviaTheme.dialogContainerColor,
                title = { Text(stringResource(R.string.themes_delete_title)) },
                text = { Text(stringResource(R.string.themes_delete_message, target.name)) },
                confirmButton = {
                    TextButton(onClick = {
                        deleteTarget = null
                        expanded.remove(target.id)
                        runAction { ThemeManager.delete(target.id) }
                    }) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.cancel)) }
                },
            )
            }
        }

        shareTarget?.let { target ->
            ShareThemeDialog(
                theme = target,
                onDismiss = { shareTarget = null },
                onShared = { shared ->
                    shareTarget = null
                    val message = ctx.getString(
                        if (target.sharedFromHere) R.string.themes_share_updated else R.string.themes_share_done,
                        shared.name,
                    )
                    scope.launch { snackbarHostState.showSnackbar(message) }
                },
            )
        }

        shareBlocked?.let { blocker ->
            ShareBlockedDialog(blocker = blocker, onDismiss = { shareBlocked = null })
        }

        reportTarget?.let { target ->
            ReportThemeDialog(
                theme = target,
                onDismiss = {
                    reportTarget = null
                    community.focusRow = target.id
                },
                onSent = {
                    reportTarget = null
                    community.focusRow = target.id
                    val message = ctx.getString(R.string.themes_community_report_sent)
                    scope.launch { snackbarHostState.showSnackbar(message) }
                },
            )
        }

        communityDeleteTarget?.let { target ->
            DeleteCommunityThemeDialog(
                theme = target,
                onDismiss = {
                    communityDeleteTarget = null
                    community.focusRow = target.id
                },
                onConfirm = {
                    communityDeleteTarget = null
                    community.focusRow = target.id
                    deleteFromCommunity(target)
                },
            )
        }

        if (signInNeeded) {
            ThemeSignInNeededDialog(
                onDismiss = {
                    signInNeeded = false
                    afterSignIn = null
                },
                onSignIn = {
                    signInNeeded = false
                    showSignIn = true
                },
            )
        }

        AccountSignInDialog(
            visible = showSignIn,
            reason = stringResource(R.string.themes_sign_in_message),
            onSignedIn = {
                showSignIn = false
                val action = afterSignIn
                afterSignIn = null
                action?.invoke()
            },
            onDismiss = {
                showSignIn = false
                afterSignIn = null
            },
        )

        colorTarget?.let { (themeId, option) ->
            ColorPickerDialog(
                title = option.label,
                initial = option.argb,
                onDismiss = { colorTarget = null },
                onConfirm = { argb ->
                    colorTarget = null
                    runAction { ThemeManager.setColor(themeId, option.key, argb) }
                },
            )
        }
    }
}

@Composable
internal fun Modifier.focusOutline(shape: Shape = RoundedCornerShape(12.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    NavigateSoundOnFocusGain(focused)
    val color = MaterialTheme.colorScheme.primary
    return this
        .onFocusChanged { focused = it.hasFocus }
        .then(if (focused) Modifier.border(2.dp, color, shape) else Modifier)
}

/**
 * The action button of a store or community row. It stays one button while it works and once it is done,
 * so D-pad focus stays on the row instead of falling back to the top bar.
 */
@Composable
internal fun RowActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    doneLabel: String? = null,
) {
    val idle = !busy && doneLabel == null
    Button(
        onClick = { if (idle) onClick() },
        modifier = modifier.focusOutline(CircleShape),
        colors = if (idle) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
            )
        },
    ) {
        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            doneLabel != null -> {
                Icon(imageVector = Icons.Default.Done, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(doneLabel)
            }
            else -> Text(label)
        }
    }
}

/** Load more, kept as the same button while the next page loads so it keeps focus. */
@Composable
internal fun LoadMoreButton(loading: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = { if (!loading) onClick() },
        modifier = Modifier.focusOutline(CircleShape),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            Text(stringResource(R.string.themes_load_more))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InstalledThemesTab(
    themes: List<InstalledTheme>,
    busy: Boolean,
    expanded: List<String>,
    updating: List<String>,
    onUpdate: (String) -> Unit,
    onImportZip: () -> Unit,
    onImportFolder: () -> Unit,
    onCreateStarter: () -> Unit,
    onToggleExpanded: (String) -> Unit,
    onAction: (suspend () -> Unit) -> Unit,
    onDelete: (InstalledTheme) -> Unit,
    onShare: (InstalledTheme) -> Unit,
    onEditColor: (String, ThemeOption.Color) -> Unit,
    onPickImage: (String, String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onImportZip,
                    enabled = !busy,
                    modifier = Modifier.focusOutline(CircleShape),
                ) { Text(stringResource(R.string.themes_import_zip)) }
                Button(
                    onClick = onImportFolder,
                    enabled = !busy,
                    modifier = Modifier.focusOutline(CircleShape),
                ) { Text(stringResource(R.string.themes_import_folder)) }
                OutlinedButton(
                    onClick = onCreateStarter,
                    enabled = !busy,
                    modifier = Modifier.focusOutline(CircleShape),
                ) { Text(stringResource(R.string.themes_create_starter)) }
            }
            if (busy) {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(text = stringResource(R.string.working), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (themes.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.themes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        itemsIndexed(themes, key = { _, theme -> theme.id }) { index, theme ->
            InstalledThemeCard(
                theme = theme,
                isFirst = index == 0,
                isLast = index == themes.lastIndex,
                isExpanded = theme.id in expanded,
                isUpdating = theme.id in updating,
                onUpdate = { onUpdate(theme.id) },
                onToggleExpanded = { onToggleExpanded(theme.id) },
                onAction = onAction,
                onDelete = { onDelete(theme) },
                onShare = { onShare(theme) },
                onEditColor = { onEditColor(theme.id, it) },
                onPickImage = { key -> onPickImage(theme.id, key) },
            )
        }
    }
}

@Composable
private fun changesText(fields: List<String>): String {
    val words = buildList {
        if (fields.any { it in COLOUR_FIELDS }) add(R.string.themes_field_colours)
        if ("cornerRadius" in fields) add(R.string.themes_field_corners)
        if ("font" in fields) add(R.string.themes_field_font)
        if ("backgroundImage" in fields || "backgroundGradient" in fields) add(R.string.themes_field_background)
        if ("panelOpacity" in fields) add(R.string.themes_field_transparency)
        if ("blur" in fields) add(R.string.themes_field_blur)
        if (fields.any { it in EFFECT_FIELDS }) add(R.string.themes_field_effects)
        if ("layout" in fields) add(R.string.themes_field_layout)
    }.map { stringResource(it) }
    return stringResource(R.string.themes_changes, (words.ifEmpty { fields }).joinToString(", "))
}

@Composable
private fun InstalledThemeCard(
    theme: InstalledTheme,
    isFirst: Boolean,
    isLast: Boolean,
    isExpanded: Boolean,
    isUpdating: Boolean,
    onUpdate: () -> Unit,
    onToggleExpanded: () -> Unit,
    onAction: (suspend () -> Unit) -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    onEditColor: (ThemeOption.Color) -> Unit,
    onPickImage: (String) -> Unit,
) {
    val visibleOptions = theme.options.filter {
        when (it) {
            is ThemeOption.Choice -> true
            is ThemeOption.Color -> it.visible
            is ThemeOption.Image -> it.visible
        }
    }
    val optionGroups = remember(visibleOptions) { groupOptions(visibleOptions) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = theme.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val meta = if (theme.builtIn) {
                        stringResource(R.string.themes_custom_subtitle)
                    } else {
                        listOfNotNull(
                            theme.author?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.themes_by_author, it) },
                            theme.version?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                    }
                    if (meta.isNotEmpty()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (theme.appliedFields.isEmpty()) {
                        if (!theme.builtIn) {
                            Text(
                                text = stringResource(R.string.themes_layout_only),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        Text(
                            text = changesText(theme.appliedFields),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (theme.sharedFromHere) {
                        Text(
                            text = stringResource(R.string.themes_shared_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (theme.missingDependencies.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.themes_missing_dependencies,
                                theme.missingDependencies.joinToString(", "),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                val newVersion = theme.updateAvailable
                if (newVersion != null && !theme.builtIn) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        TextButton(
                            onClick = onUpdate,
                            modifier = Modifier.focusOutline(CircleShape),
                        ) {
                            Text(
                                text = stringResource(R.string.themes_update_to, newVersion),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 160.dp),
                            )
                        }
                    }
                }
                if (visibleOptions.isNotEmpty()) {
                    TextButton(
                        onClick = onToggleExpanded,
                        modifier = Modifier.focusOutline(CircleShape),
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                        )
                        Text(text = stringResource(R.string.themes_options))
                    }
                }
                IconButton(
                    onClick = { onAction { ThemeManager.move(theme.id, up = true) } },
                    enabled = !isFirst,
                    modifier = Modifier.focusOutline(CircleShape),
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.themes_move_up))
                }
                IconButton(
                    onClick = { onAction { ThemeManager.move(theme.id, up = false) } },
                    enabled = !isLast,
                    modifier = Modifier.focusOutline(CircleShape),
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.themes_move_down))
                }
                IconButton(
                    onClick = onShare,
                    modifier = Modifier.focusOutline(CircleShape),
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = stringResource(
                            if (theme.sharedFromHere) R.string.themes_share_update else R.string.themes_share,
                        ),
                    )
                }
                if (!theme.builtIn) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.focusOutline(CircleShape),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Switch(
                    checked = theme.enabled,
                    onCheckedChange = { checked -> onAction { ThemeManager.setEnabled(theme.id, checked) } },
                    modifier = Modifier.focusOutline(CircleShape),
                )
            }

            if (isExpanded && visibleOptions.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    optionGroups.forEach { group ->
                        ThemeOptionRow(
                            themeId = theme.id,
                            option = group.head,
                            attached = group.attached,
                            onAction = onAction,
                            onEditColor = onEditColor,
                            onPickImage = onPickImage,
                        )
                    }
                    TextButton(
                        onClick = { onAction { ThemeManager.resetOptions(theme.id) } },
                        modifier = Modifier.focusOutline(CircleShape),
                    ) { Text(stringResource(R.string.themes_reset_options)) }
                }
            }
        }
    }
}

@Composable
private fun ThemeOptionRow(
    themeId: String,
    option: ThemeOption,
    attached: List<ThemeOption> = emptyList(),
    onAction: (suspend () -> Unit) -> Unit,
    onEditColor: (ThemeOption.Color) -> Unit,
    onPickImage: (String) -> Unit,
) {
    val attachedDetail = attached.filterIsInstance<ThemeOption.Image>().firstOrNull()?.let { image ->
        image.path?.let { File(it).name } ?: stringResource(R.string.themes_no_image)
    }
    val attachedControls: @Composable () -> Unit = {
        attached.forEach { extra ->
            when (extra) {
                is ThemeOption.Color -> ColorOptionButton(option = extra, compact = true, onEditColor = onEditColor)
                is ThemeOption.Image -> ImageOptionButtons(themeId, extra, onAction, onPickImage)
                is ThemeOption.Choice -> Unit
            }
        }
    }
    when (option) {
        is ThemeOption.Choice -> when (option.kind) {
            ThemeOption.Choice.Kind.DROPDOWN -> ChoiceDropdown(
                option = option,
                detail = attachedDetail,
                extra = attachedControls,
            ) { value ->
                onAction { ThemeManager.setChoice(themeId, option.key, value) }
            }

            ThemeOption.Choice.Kind.CHECKBOX -> OptionLabelRow(label = option.label, detail = attachedDetail) {
                attachedControls()
                Switch(
                    checked = option.selected.equals("Yes", ignoreCase = true),
                    onCheckedChange = { checked ->
                        onAction { ThemeManager.setChoice(themeId, option.key, if (checked) "Yes" else "No") }
                    },
                    modifier = Modifier.focusOutline(CircleShape),
                )
            }

            ThemeOption.Choice.Kind.SLIDER -> {
                val index = option.values.indexOf(option.selected).coerceAtLeast(0)
                QuickMenuTickSlider(
                    title = "${option.label}: ${option.selected}",
                    labels = option.values,
                    selectedIndex = index,
                    accentColor = MaterialTheme.colorScheme.primary,
                    onSelect = { i ->
                        val value = option.values.getOrNull(i)
                        if (value != null && value != option.selected) {
                            onAction { ThemeManager.setChoice(themeId, option.key, value) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is ThemeOption.Color -> OptionLabelRow(label = option.label) {
            ColorOptionButton(option = option, compact = false, onEditColor = onEditColor)
        }

        is ThemeOption.Image -> OptionLabelRow(
            label = option.label,
            detail = option.path?.let { File(it).name } ?: stringResource(R.string.themes_no_image),
        ) {
            ImageOptionButtons(themeId, option, onAction, onPickImage)
        }
    }
}

@Composable
private fun ColorOptionButton(
    option: ThemeOption.Color,
    compact: Boolean,
    onEditColor: (ThemeOption.Color) -> Unit,
) {
    OutlinedButton(
        onClick = { onEditColor(option) },
        modifier = Modifier.focusOutline(CircleShape),
        contentPadding = if (compact) PaddingValues(horizontal = 12.dp) else ButtonDefaults.ContentPadding,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(Color(option.argb))
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
        if (!compact) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = colorToHex(option.argb), maxLines = 1)
        }
    }
}

@Composable
private fun ImageOptionButtons(
    themeId: String,
    option: ThemeOption.Image,
    onAction: (suspend () -> Unit) -> Unit,
    onPickImage: (String) -> Unit,
) {
    OutlinedButton(
        onClick = { onPickImage(option.key) },
        modifier = Modifier.focusOutline(CircleShape),
    ) {
        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = stringResource(R.string.themes_choose_image), maxLines = 1)
    }
    if (option.path != null) {
        IconButton(
            onClick = { onAction { ThemeManager.setImage(themeId, option.key, null) } },
            modifier = Modifier.focusOutline(CircleShape),
        ) {
            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.themes_clear_image))
        }
    }
}

@Composable
private fun OptionLabelRow(
    label: String,
    detail: String? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing()
    }
}

@Composable
private fun ChoiceDropdown(
    option: ThemeOption.Choice,
    detail: String? = null,
    extra: @Composable () -> Unit = {},
    onSelect: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    OptionLabelRow(label = option.label, detail = detail) {
        extra()
        Box {
            OutlinedButton(
                onClick = { menuOpen = true },
                modifier = Modifier.focusOutline(CircleShape),
            ) {
                Text(
                    text = option.selected,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                Icon(Icons.Default.ExpandMore, contentDescription = null)
            }
            MenuColorScope {
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
            ) {
                option.values.forEach { value ->
                    DropdownMenuItem(
                        text = { Text(value) },
                        onClick = {
                            menuOpen = false
                            if (value != option.selected) onSelect(value)
                        },
                        trailingIcon = if (value == option.selected) {
                            { Icon(Icons.Default.Done, contentDescription = null) }
                        } else {
                            null
                        },
                    )
                }
            }
            }
        }
    }
}

private fun colorToHex(argb: Int): String = String.format(Locale.ROOT, "#%08X", argb)

private fun parseHex(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    val full = when (digits.length) {
        6 -> "FF$digits"
        8 -> digits
        else -> return null
    }
    return full.toLongOrNull(16)?.toInt()
}

@Composable
private fun ColorPickerDialog(
    title: String,
    initial: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val initialHsv = remember(initial) { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember { mutableFloatStateOf(initialHsv[1]) }
    var brightness by remember { mutableFloatStateOf(initialHsv[2]) }
    var alpha by remember { mutableFloatStateOf(android.graphics.Color.alpha(initial) / 255f) }
    var hex by remember { mutableStateOf(colorToHex(initial)) }

    fun currentArgb(): Int =
        android.graphics.Color.HSVToColor((alpha * 255f).roundToInt(), floatArrayOf(hue, saturation, brightness))

    fun refreshHex() {
        hex = colorToHex(currentArgb())
    }

    val argb = currentArgb()

    MenuColorScope {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PluviaTheme.dialogContainerColor,
        title = { Text(text = title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(argb))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                )
                ColorSliderRow(stringResource(R.string.themes_hue), hue, 0f..360f) {
                    hue = it
                    refreshHex()
                }
                ColorSliderRow(stringResource(R.string.themes_saturation), saturation, 0f..1f) {
                    saturation = it
                    refreshHex()
                }
                ColorSliderRow(stringResource(R.string.themes_brightness), brightness, 0f..1f) {
                    brightness = it
                    refreshHex()
                }
                ColorSliderRow(stringResource(R.string.themes_opacity), alpha, 0f..1f) {
                    alpha = it
                    refreshHex()
                }
                NoExtractOutlinedTextField(
                    value = hex,
                    onValueChange = { text ->
                        hex = text
                        parseHex(text)?.let { parsed ->
                            val hsv = FloatArray(3)
                            android.graphics.Color.colorToHSV(parsed, hsv)
                            hue = hsv[0]
                            saturation = hsv[1]
                            brightness = hsv[2]
                            alpha = android.graphics.Color.alpha(parsed) / 255f
                        }
                    },
                    label = { Text(stringResource(R.string.themes_hex)) },
                    singleLine = true,
                    isError = parseHex(hex) == null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(argb) }) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
    }
}

@Composable
private fun ColorSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(88.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier
                .weight(1f)
                .focusOutline(RoundedCornerShape(8.dp))
                .onPreviewKeyEvent { event ->
                    // D-pad left and right step the slider, which does not take arrow keys on its own.
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = (range.endInclusive - range.start) / COLOR_SLIDER_STEPS
                    val next = when (event.key) {
                        Key.DirectionLeft -> value - step
                        Key.DirectionRight -> value + step
                        else -> return@onPreviewKeyEvent false
                    }.coerceIn(range.start, range.endInclusive)
                    if (next != value) onChange(next)
                    true
                },
        )
    }
}

private const val COLOR_SLIDER_STEPS = 50f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrowseThemesTab(
    store: StoreState,
    installedByName: Map<String, InstalledTheme>,
    updating: List<String>,
    onSearch: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onInstall: (StoreTheme) -> Unit,
    onUpdate: (String) -> Unit,
) {
    val targets = store.items.mapNotNull { item -> item.target?.takeIf { it.isNotBlank() } }.distinct()
    val activeFilter = store.targetFilter?.takeIf { it in targets }
    val visibleItems = if (activeFilter == null) store.items.toList() else store.items.filter { it.target == activeFilter }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NoExtractOutlinedTextField(
                value = store.query,
                onValueChange = { store.query = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.themes_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            )
            Button(
                onClick = onSearch,
                enabled = !store.loading,
                modifier = Modifier.focusOutline(CircleShape),
            ) { Text(stringResource(R.string.search)) }
        }

        if (targets.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "") {
                    FilterChip(
                        selected = activeFilter == null,
                        onClick = { store.targetFilter = null },
                        label = { Text(stringResource(R.string.themes_filter_all), maxLines = 1) },
                        modifier = Modifier.focusOutline(RoundedCornerShape(8.dp)),
                    )
                }
                items(targets, key = { "target:$it" }) { target ->
                    FilterChip(
                        selected = activeFilter == target,
                        onClick = { store.targetFilter = target },
                        label = { Text(target, maxLines = 1) },
                        modifier = Modifier.focusOutline(RoundedCornerShape(8.dp)),
                    )
                }
            }
        }

        when {
            store.items.isEmpty() && store.loading -> CenteredBox {
                CircularProgressIndicator()
            }

            store.items.isEmpty() && store.error != null -> CenteredBox {
                StoreErrorBlock(message = store.error.orEmpty(), onRetry = onRetry)
            }

            store.items.isEmpty() && store.loaded -> CenteredBox {
                Text(
                    text = stringResource(R.string.themes_store_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visibleItems, key = { it.id }) { theme ->
                    val installedTheme = installedByName[theme.name.lowercase(Locale.ROOT)]
                    StoreThemeRow(
                        theme = theme,
                        installedTheme = installedTheme,
                        installing = theme.id in store.installing,
                        updating = installedTheme != null && installedTheme.id in updating,
                        onInstall = { onInstall(theme) },
                        onUpdate = { installedTheme?.let { onUpdate(it.id) } },
                    )
                }
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            store.error != null && !store.loading -> StoreErrorBlock(message = store.error.orEmpty(), onRetry = onRetry)
                            store.loading || !store.endReached -> LoadMoreButton(loading = store.loading, onClick = onLoadMore)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CenteredBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun StoreErrorBlock(message: String, onRetry: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.themes_store_error, message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onRetry,
            modifier = Modifier.focusOutline(CircleShape),
        ) { Text(stringResource(R.string.themes_retry)) }
    }
}

@Composable
private fun StoreThemeRow(
    theme: StoreTheme,
    installedTheme: InstalledTheme?,
    installing: Boolean,
    updating: Boolean,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(112.dp)
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (theme.previewImageUrl != null) {
                    CoilImage(
                        modifier = Modifier.fillMaxSize(),
                        imageModel = { theme.previewImageUrl },
                        imageOptions = ImageOptions(
                            contentScale = ContentScale.Crop,
                            contentDescription = theme.name,
                        ),
                        loading = {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        },
                        failure = {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        previewPlaceholder = painterResource(R.drawable.ic_logo_color),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = theme.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                theme.author?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = stringResource(R.string.themes_by_author, it),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val meta = listOfNotNull(
                    theme.target?.takeIf { it.isNotBlank() },
                    pluralStringResource(R.plurals.themes_downloads, theme.downloads, theme.downloads),
                ).joinToString(" · ")
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val hasUpdate = installedTheme?.updateAvailable != null
            RowActionButton(
                label = stringResource(if (hasUpdate) R.string.themes_update else R.string.install),
                onClick = if (hasUpdate) onUpdate else onInstall,
                busy = installing || updating,
                doneLabel = if (installedTheme != null && !hasUpdate) stringResource(R.string.installed) else null,
            )
        }
    }
}
