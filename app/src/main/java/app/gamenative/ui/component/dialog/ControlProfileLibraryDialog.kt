package app.gamenative.ui.component.dialog

import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.PluviaApp
import app.gamenative.R
import app.gamenative.inputcontrols.ControlProfilePreview
import app.gamenative.inputcontrols.ControlProfileSection
import app.gamenative.inputcontrols.ControlProfileService
import app.gamenative.ui.theme.PluviaBackground
import app.gamenative.ui.util.SnackbarManager
import com.winlator.container.Container
import com.winlator.inputcontrols.ControlElement
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.InputControlsManager
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.min

private data class SectionAction(
    val title: String,
    val available: Set<ControlProfileSection>,
    val selected: Set<ControlProfileSection> = available,
    val confirmLabel: String,
    val onConfirm: (Set<ControlProfileSection>) -> Unit,
)

private data class ProfilePreviewAction(
    val preview: ControlProfilePreview,
    val confirmLabel: String? = null,
    val onConfirm: (() -> Unit)? = null,
)

private data class ProfileLibraryEntry(
    val profile: ControlsProfile,
    val preview: ControlProfilePreview,
    val builtIn: Boolean,
)

private data class ProfileLibrarySnapshot(
    val entries: List<ProfileLibraryEntry>,
    val appliedSources: Map<ControlProfileSection, Int>,
)

internal fun fitPreviewHalfExtents(
    rawHalfWidth: Float,
    rawHalfHeight: Float,
    canvasWidth: Float,
    canvasHeight: Float,
): Pair<Float, Float> {
    if (canvasWidth <= 0f || canvasHeight <= 0f) return 0f to 0f
    val rawFit = minOf(
        1f,
        canvasWidth / (rawHalfWidth * 2f).coerceAtLeast(1f),
        canvasHeight / (rawHalfHeight * 2f).coerceAtLeast(1f),
    )
    val fit = if (rawFit < 1f) rawFit * 0.98f else 1f
    return rawHalfWidth * fit to rawHalfHeight * fit
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ControlProfileLibraryDialog(
    container: Container,
    onDismiss: () -> Unit,
    onProfileApplied: (ControlsProfile) -> Unit = {},
) {
    val context = LocalContext.current
    val manager = remember(container.id) {
        if (PluviaApp.inputControlsView != null) {
            PluviaApp.inputControlsManager ?: InputControlsManager(context)
        } else {
            InputControlsManager(context)
        }
    }
    val scope = rememberCoroutineScope()
    var entries by remember(container.id) { mutableStateOf<List<ProfileLibraryEntry>>(emptyList()) }
    var appliedSources by remember(container.id) {
        mutableStateOf<Map<ControlProfileSection, Int>>(emptyMap())
    }
    var loading by remember(container.id) { mutableStateOf(true) }
    var previewAction by remember { mutableStateOf<ProfilePreviewAction?>(null) }
    var sectionAction by remember { mutableStateOf<SectionAction?>(null) }
    var createDialog by remember { mutableStateOf(false) }
    var createName by remember { mutableStateOf("") }
    var createFromCurrent by remember { mutableStateOf(true) }
    var createSections by remember { mutableStateOf(ControlProfileSection.entries.toSet()) }
    var renameProfile by remember { mutableStateOf<ControlsProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<ControlsProfile?>(null) }
    var pendingExport by remember {
        mutableStateOf<Pair<ControlsProfile, Set<ControlProfileSection>>?>(null)
    }
    val libraryListState = rememberLazyListState()

    fun openCreate() {
        createName = ""
        createFromCurrent = true
        createSections = ControlProfileSection.entries.toSet()
        createDialog = true
    }

    fun failure(error: Throwable) {
        SnackbarManager.show(
            context.getString(R.string.control_profile_failed, error.message ?: error.javaClass.simpleName),
        )
    }

    suspend fun loadSnapshot(): ProfileLibrarySnapshot = withContext(Dispatchers.IO) {
        ControlProfileService.reconcileWorkingProfiles(context, manager)
        manager.reloadProfiles()
        val builtIns = ControlProfileService.builtInProfileKeys(context)
        ProfileLibrarySnapshot(
            entries = manager.getProfiles(false).mapNotNull { profile ->
                runCatching {
                    ProfileLibraryEntry(
                        profile = profile,
                        preview = ControlProfileService.preview(context, profile),
                        builtIn = profile.id to profile.name in builtIns,
                    )
                }.getOrNull()
            },
            appliedSources = ControlProfileService.appliedSectionSources(context, container, manager),
        )
    }

    fun refresh() {
        if (loading) return
        loading = true
        scope.launch {
            runCatching { loadSnapshot() }
                .onSuccess { snapshot ->
                    entries = snapshot.entries
                    appliedSources = snapshot.appliedSources
                }
                .onFailure(::failure)
            loading = false
        }
    }

    fun <T> runIo(operation: () -> T, onSuccess: (T) -> Unit = {}) {
        if (loading) return
        loading = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(operation) }
            loading = false
            result.onSuccess(onSuccess).onFailure(::failure)
        }
    }

    LaunchedEffect(container.id) {
        runCatching { loadSnapshot() }
            .onSuccess { snapshot ->
                entries = snapshot.entries
                appliedSources = snapshot.appliedSources
            }
            .onFailure(::failure)
        loading = false
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri != null && request != null) {
            runIo({
                ControlProfileService.exportProfile(context, request.first, request.second, uri)
            }) {
                SnackbarManager.show(context.getString(R.string.control_profile_exported))
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runIo({ ControlProfileService.importProfile(context, uri) }) { imported ->
                previewAction = ProfilePreviewAction(
                    preview = imported,
                    confirmLabel = context.getString(R.string.control_profile_import_confirm),
                    onConfirm = {
                        runIo({ ControlProfileService.installImported(manager, imported) }) {
                            refresh()
                            SnackbarManager.show(context.getString(R.string.control_profile_imported))
                        }
                    },
                )
            }
        }
    }

    BackHandler(enabled = createDialog) { createDialog = false }

    val orderedEntries = remember(entries, appliedSources) {
        entries.sortedWith(
            compareByDescending<ProfileLibraryEntry> { entry ->
                entry.preview.sections.any { appliedSources[it] == entry.profile.id }
            }.thenBy { it.profile.name.lowercase(Locale.getDefault()) },
        )
    }

    Dialog(
        onDismissRequest = { if (createDialog) createDialog = false else onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .displayCutoutPadding()
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility),
            containerColor = PluviaBackground,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            stringResource(
                                if (createDialog) R.string.control_profile_create else R.string.control_profiles,
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { if (createDialog) createDialog = false else onDismiss() }) {
                            Icon(
                                if (createDialog) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Close,
                                contentDescription = stringResource(
                                    if (createDialog) R.string.back else R.string.close,
                                ),
                            )
                        }
                    },
                    actions = {
                        if (!createDialog) {
                            IconButton(
                                enabled = !loading,
                                onClick = {
                                    importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                                },
                            ) {
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = stringResource(R.string.control_profile_import),
                                )
                            }
                            IconButton(enabled = !loading, onClick = ::openCreate) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource(R.string.control_profile_create),
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            if (createDialog) {
                CreateControlProfileScreen(
                    name = createName,
                    fromCurrent = createFromCurrent,
                    sections = createSections,
                    loading = loading,
                    onNameChange = { createName = it.take(InputControlsManager.MAX_PROFILE_NAME_LENGTH) },
                    onFromCurrentChange = { createFromCurrent = it },
                    onSectionChange = { section, checked ->
                        createSections = if (checked) createSections + section else createSections - section
                    },
                    onCreate = {
                        val name = createName.trim()
                        val selectedSections = if (createFromCurrent) {
                            createSections
                        } else {
                            setOf(ControlProfileSection.ON_SCREEN)
                        }
                        runIo({
                            val saved = if (createFromCurrent) {
                                ControlProfileService.saveCurrentAsProfile(
                                    context,
                                    container,
                                    manager,
                                    name,
                                    selectedSections,
                                )
                            } else {
                                ControlProfileService.createBlank(context, manager, name)
                            }
                            ControlProfileService.applyProfile(
                                context,
                                container,
                                manager,
                                saved,
                                selectedSections,
                            )
                        }) { applied ->
                            createDialog = false
                            onProfileApplied(applied)
                            refresh()
                            SnackbarManager.show(
                                context.getString(R.string.control_profile_created_applied),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize().padding(padding),
                )
            }
            else if (loading && entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    state = libraryListState,
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "library-overview") {
                        ControlProfileLibraryOverview(
                            profileCount = entries.size,
                            hasAppliedSections = appliedSources.isNotEmpty(),
                            onCreate = ::openCreate,
                        )
                    }
                    if (orderedEntries.isEmpty()) {
                        item(key = "empty-library") {
                            Text(
                                stringResource(R.string.control_profiles_empty),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    items(orderedEntries, key = { it.profile.id }) { entry ->
                        val profile = entry.profile
                        val profilePreview = entry.preview
                        val appliedSections = profilePreview.sections.filterTo(mutableSetOf()) {
                            appliedSources[it] == profile.id
                        }
                        ControlProfileCard(
                                profile = profile,
                                preview = profilePreview,
                                appliedSections = appliedSections,
                                onPreview = { previewAction = ProfilePreviewAction(profilePreview) },
                                onApply = {
                                    sectionAction = SectionAction(
                                        title = context.getString(R.string.control_profile_apply),
                                        available = profilePreview.sections,
                                        confirmLabel = context.getString(R.string.control_profile_apply),
                                    ) { sections ->
                                        runIo({
                                            ControlProfileService.applyProfile(
                                                context,
                                                container,
                                                manager,
                                                profile,
                                                sections,
                                            )
                                        }) { applied ->
                                            onProfileApplied(applied)
                                            refresh()
                                            SnackbarManager.show(context.getString(R.string.control_profile_applied_message))
                                        }
                                    }
                                },
                                onSaveCurrent = if (appliedSections.isNotEmpty() && !entry.builtIn) ({
                                    sectionAction = SectionAction(
                                        title = context.getString(R.string.control_profile_update_current),
                                        available = appliedSections,
                                        selected = appliedSections,
                                        confirmLabel = context.getString(R.string.save),
                                    ) { sections ->
                                        runIo({
                                            ControlProfileService.updateFromCurrent(
                                                context,
                                                container,
                                                manager,
                                                profile,
                                                sections,
                                            )
                                        }) {
                                            refresh()
                                            SnackbarManager.show(context.getString(R.string.control_profile_saved))
                                        }
                                    }
                                }) else null,
                                onDuplicate = {
                                    runIo({ manager.duplicateProfile(profile) }) { refresh() }
                                },
                                onRename = if (entry.builtIn) null else ({ renameProfile = profile }),
                                onExport = {
                                    sectionAction = SectionAction(
                                        title = context.getString(R.string.control_profile_export),
                                        available = profilePreview.sections,
                                        confirmLabel = context.getString(R.string.control_profile_export),
                                    ) { sections ->
                                        pendingExport = profile to sections
                                        exportLauncher.launch(safeFileName(profile.name) + ".icp")
                                    }
                                },
                                onDelete = if (entry.builtIn) null else ({ deleteProfile = profile }),
                            )
                    }
                }
            }
        }
    }

    val pendingRename: ControlsProfile? = renameProfile
    pendingRename?.let { profile: ControlsProfile ->
        ProfileNameDialog(
            title = stringResource(R.string.control_profile_rename),
            initialName = profile.name,
            labelResId = R.string.control_profile_name,
            maxLength = InputControlsManager.MAX_PROFILE_NAME_LENGTH,
            onDismiss = { renameProfile = null },
            onConfirm = { name: String ->
                runIo({ ControlProfileService.rename(context, manager, profile, name) }) {
                    renameProfile = null
                    refresh()
                }
            },
        )
    }

    deleteProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteProfile = null },
            title = { Text(stringResource(R.string.control_profile_delete_title)) },
            text = { Text(stringResource(R.string.control_profile_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    runIo({ ControlProfileService.deleteProfile(context, container, manager, profile) }) {
                        deleteProfile = null
                        refresh()
                    }
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteProfile = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    sectionAction?.let { action ->
        ProfileSectionDialog(
            action = action,
            onDismiss = { sectionAction = null },
            onConfirm = { sections ->
                sectionAction = null
                action.onConfirm(sections)
            },
        )
    }

    previewAction?.let { action ->
        ControlProfilePreviewDialog(
            action = action,
            screenSize = container.screenSize,
            onDismiss = { previewAction = null },
            onConfirm = action.onConfirm?.let { confirm ->
                {
                    previewAction = null
                    confirm()
                }
            },
        )
    }
}

@Composable
private fun ControlProfileLibraryOverview(
    profileCount: Int,
    hasAppliedSections: Boolean,
    onCreate: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            ) {
                Icon(
                    Icons.Default.SportsEsports,
                    contentDescription = null,
                    modifier = Modifier.padding(12.dp).size(28.dp),
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    stringResource(R.string.control_profile_library_count, profileCount),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(
                        if (hasAppliedSections) {
                            R.string.control_profile_library_applied_hint
                        } else {
                            R.string.control_profile_library_hint
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            FilledTonalButton(onClick = onCreate) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.control_profile_create))
            }
        }
    }
}

@Composable
private fun ControlProfileCard(
    profile: ControlsProfile,
    preview: ControlProfilePreview,
    appliedSections: Set<ControlProfileSection>,
    onPreview: () -> Unit,
    onApply: () -> Unit,
    onSaveCurrent: (() -> Unit)?,
    onDuplicate: () -> Unit,
    onRename: (() -> Unit)?,
    onExport: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val applied = appliedSections.isNotEmpty()
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onPreview),
        shape = RoundedCornerShape(18.dp),
        color = if (applied) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f)
        else MaterialTheme.colorScheme.surface,
        border = if (applied) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        tonalElevation = if (applied) 4.dp else 2.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (applied) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = if (applied) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                ) {
                    Icon(
                        Icons.Default.SportsEsports,
                        contentDescription = null,
                        modifier = Modifier.padding(9.dp).size(22.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (applied) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(
                                    R.string.control_profile_applied_sections,
                                    appliedSections.size,
                                    preview.sections.size,
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        if (onSaveCurrent != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.control_profile_save_current)) },
                                onClick = { menuExpanded = false; onSaveCurrent() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.control_profile_duplicate)) },
                            onClick = { menuExpanded = false; onDuplicate() },
                        )
                        if (onRename != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.control_profile_rename)) },
                                onClick = { menuExpanded = false; onRename() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.control_profile_export)) },
                            leadingIcon = { Icon(Icons.Default.Upload, contentDescription = null) },
                            onClick = { menuExpanded = false; onExport() },
                        )
                        if (onDelete != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete)) },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                onClick = { menuExpanded = false; onDelete() },
                            )
                        }
                    }
                }
            }
            ProfileSectionChips(preview.sections, appliedSections)
            val summaries = buildList {
                if (ControlProfileSection.ON_SCREEN in preview.sections) {
                    add(stringResource(R.string.control_profile_controls_count, preview.elementCount))
                }
                if (ControlProfileSection.PHYSICAL_CONTROLLER in preview.sections) {
                    add(stringResource(R.string.control_profile_bindings_count, preview.physicalBindingCount))
                }
                if (ControlProfileSection.RADIAL_MENU in preview.sections) {
                    add(stringResource(R.string.control_profile_radial_count, preview.radialSlotCount))
                }
            }
            if (summaries.isNotEmpty()) {
                Text(
                    summaries.joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(modifier = Modifier.weight(1f), onClick = onPreview) {
                    Text(stringResource(R.string.control_profile_preview))
                }
                Button(modifier = Modifier.weight(1f), onClick = onApply) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.control_profile_apply))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileSectionChips(
    sections: Set<ControlProfileSection>,
    appliedSections: Set<ControlProfileSection> = emptySet(),
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ControlProfileSection.entries.filter { it in sections }.forEach { section ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (section in appliedSections) {
                    MaterialTheme.colorScheme.primaryContainer
                }
                else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (section in appliedSections) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(text = sectionLabel(section), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun CreateControlProfileScreen(
    name: String,
    fromCurrent: Boolean,
    sections: Set<ControlProfileSection>,
    loading: Boolean,
    onNameChange: (String) -> Unit,
    onFromCurrentChange: (Boolean) -> Unit,
    onSectionChange: (ControlProfileSection, Boolean) -> Unit,
    onCreate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) focusManager.clearFocus()
    }
    Column(modifier = modifier) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "create-intro") {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        stringResource(R.string.control_profile_create_applies_hint),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item(key = "create-name") {
                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.control_profile_name)) },
                    singleLine = true,
                )
            }
            item(key = "create-source-heading") {
                Text(
                    stringResource(R.string.control_profile_start_with),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item(key = "create-current") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.control_profile_create_current)) },
                    supportingContent = { Text(stringResource(R.string.control_profile_create_current_hint)) },
                    leadingContent = { RadioButton(selected = fromCurrent, onClick = null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onFromCurrentChange(true) },
                )
            }
            item(key = "create-blank") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.control_profile_create_blank)) },
                    supportingContent = { Text(stringResource(R.string.control_profile_create_blank_hint)) },
                    leadingContent = { RadioButton(selected = !fromCurrent, onClick = null) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onFromCurrentChange(false) },
                )
            }
            if (fromCurrent) {
                item(key = "create-sections-heading") {
                    Text(stringResource(R.string.control_profile_sections), fontWeight = FontWeight.SemiBold)
                }
                items(ControlProfileSection.entries, key = { "create-section-${it.wireName}" }) { section ->
                    SectionCheckbox(section, section in sections) { checked ->
                        onSectionChange(section, checked)
                    }
                }
            }
        }
        Surface(tonalElevation = 4.dp) {
            Button(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                enabled = !loading && name.isNotBlank() && (!fromCurrent || sections.isNotEmpty()),
                onClick = onCreate,
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.control_profile_create_and_apply))
            }
        }
    }
}

@Composable
private fun ProfileSectionDialog(
    action: SectionAction,
    onDismiss: () -> Unit,
    onConfirm: (Set<ControlProfileSection>) -> Unit,
) {
    var sections by remember(action) { mutableStateOf(action.selected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 480.dp)) {
                item {
                    Text(stringResource(R.string.control_profile_sections), fontWeight = FontWeight.SemiBold)
                }
                items(ControlProfileSection.entries.filter { it in action.available }) { section ->
                    SectionCheckbox(section, section in sections) { checked ->
                        sections = if (checked) sections + section else sections - section
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = sections.isNotEmpty(), onClick = { onConfirm(sections) }) {
                Text(action.confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun SectionCheckbox(
    section: ControlProfileSection,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(sectionLabel(section)) },
        trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlProfilePreviewDialog(
    action: ProfilePreviewAction,
    screenSize: String,
    onDismiss: () -> Unit,
    onConfirm: (() -> Unit)?,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .displayCutoutPadding()
                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight().widthIn(max = 900.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            action.preview.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                        }
                    }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (ControlProfileSection.ON_SCREEN in action.preview.sections) {
                            item { ControlLayoutPreview(action.preview.json, screenSize) }
                        }
                        item { ProfileSectionChips(action.preview.sections) }
                        if (ControlProfileSection.ON_SCREEN in action.preview.sections) {
                            item { Text(stringResource(R.string.control_profile_controls_count, action.preview.elementCount)) }
                        }
                        if (ControlProfileSection.PHYSICAL_CONTROLLER in action.preview.sections) {
                            item { Text(stringResource(R.string.control_profile_bindings_count, action.preview.physicalBindingCount)) }
                        }
                        if (ControlProfileSection.RADIAL_MENU in action.preview.sections) {
                            item { Text(stringResource(R.string.control_profile_radial_count, action.preview.radialSlotCount)) }
                        }
                        item { ProfileSettingsSummary(action.preview) }
                    }
                    HorizontalDivider()
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                        if (onConfirm != null && action.confirmLabel != null) {
                            FilledTonalButton(onClick = onConfirm) { Text(action.confirmLabel) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSettingsSummary(preview: ControlProfilePreview) {
    val enabled = stringResource(R.string.enabled)
    val disabled = stringResource(R.string.disabled)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        preview.json.optJSONObject("gyroSettings")?.let { gyroJson ->
            SettingsSummaryCard(
                title = sectionLabel(ControlProfileSection.GYRO),
                status = if (gyroJson.optInt("mode") == 0) disabled else enabled,
                rows = profileSettingRows(gyroJson, ControlProfileSection.GYRO),
            )
        }

        preview.json.optJSONObject("touchscreenSettings")?.let { touchJson ->
            SettingsSummaryCard(
                title = sectionLabel(ControlProfileSection.TOUCHSCREEN),
                status = enabledLabel(touchJson.optBoolean("enabled")),
                rows = profileSettingRows(
                    touchJson.optJSONObject("gestures") ?: JSONObject(),
                    ControlProfileSection.TOUCHSCREEN,
                ),
            )
        }

        preview.json.optJSONObject("shooterSettings")?.let { shooterJson ->
            SettingsSummaryCard(
                title = sectionLabel(ControlProfileSection.SHOOTER),
                status = enabledLabel(shooterJson.optBoolean("enabled")),
                rows = profileSettingRows(
                    shooterJson.optJSONObject("config") ?: JSONObject(),
                    ControlProfileSection.SHOOTER,
                ),
            )
        }
    }
}

@Composable
private fun SettingsSummaryCard(
    title: String,
    status: String,
    rows: List<Pair<String, String>>,
) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        status,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            rows.forEach { (label, value) ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 7.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        value,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun enabledLabel(enabled: Boolean): String =
    stringResource(if (enabled) R.string.enabled else R.string.disabled)

@Composable
private fun profileSettingRows(
    json: JSONObject,
    section: ControlProfileSection,
): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    val keys = json.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        rows += friendlySettingName(key) to profileSettingValue(section, key, json.opt(key))
    }
    return rows
}

@Composable
private fun profileSettingValue(
    section: ControlProfileSection,
    key: String,
    value: Any?,
): String = when {
    value is Boolean -> enabledLabel(value)
    value is Number -> when {
        section == ControlProfileSection.GYRO && key in setOf("mode", "lastTarget") -> when (value.toInt()) {
            0 -> stringResource(R.string.disabled)
            1 -> stringResource(R.string.left_stick)
            3 -> stringResource(R.string.mouse)
            else -> stringResource(R.string.right_stick)
        }
        section == ControlProfileSection.GYRO && key == "activationMode" -> stringResource(
            when (value.toInt()) {
                1 -> R.string.gyro_activation_hold
                2 -> R.string.gyro_activation_toggle
                3 -> R.string.gyro_activation_ratchet
                else -> R.string.gyro_activation_always
            },
        )
        else -> formatDecimal(value.toDouble())
    }
    value is String -> friendlySettingValue(value)
    else -> value?.toString().orEmpty()
}

private fun friendlySettingName(value: String): String = value
    .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
    .replace('_', ' ')
    .replaceFirstChar { it.titlecase(Locale.getDefault()) }

private fun friendlySettingValue(value: String): String = value
    .removePrefix("key_")
    .removePrefix("KEY_")
    .removePrefix("GAMEPAD_")
    .replace('_', ' ')
    .lowercase()
    .replaceFirstChar { it.titlecase(Locale.getDefault()) }

@Composable
private fun ControlLayoutPreview(json: JSONObject, screenSize: String) {
    val dimensions = screenSize.lowercase().split("x").mapNotNull { it.trim().toFloatOrNull() }
    val ratio = if (dimensions.size == 2 && dimensions[1] > 0f) {
        (dimensions[0] / dimensions[1]).coerceIn(0.6f, 2.5f)
    } else {
        16f / 9f
    }
    val elements = json.optJSONArray("elements")
    val foreground = MaterialTheme.colorScheme.onSurface.toArgbCompat()
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .heightIn(max = 320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF15181D)),
    ) {
        if (elements == null) return@Canvas
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground
            textAlign = Paint.Align.CENTER
            textSize = min(size.width, size.height) * 0.035f
        }
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val scale = element.optDouble("scale", 1.0).toFloat().coerceIn(0.1f, 5f)
            val base = size.width / 30f
            val type = element.optString("type", "BUTTON")
            val shape = element.optString("shape", "CIRCLE")
            val rawHalfWidth = base * scale * when (type) {
                "D_PAD" -> 2.2f
                "STICK", "TRACKPAD" -> 1.9f
                "RANGE_BUTTON" -> 2.3f
                else -> if (shape == "RECT" || shape == "ROUND_RECT") 1.35f else 1f
            }
            val rawHalfHeight = base * scale * when (type) {
                "D_PAD" -> 2.2f
                "STICK", "TRACKPAD" -> 1.9f
                else -> 1f
            }
            val (halfWidth, halfHeight) = fitPreviewHalfExtents(
                rawHalfWidth,
                rawHalfHeight,
                size.width,
                size.height,
            )
            val cx = (element.optDouble("x", 0.5).toFloat() * size.width)
                .coerceIn(halfWidth, size.width - halfWidth)
            val cy = (element.optDouble("y", 0.5).toFloat() * size.height)
                .coerceIn(halfHeight, size.height - halfHeight)
            val colorValue = ControlElement.parseRgbColor(
                element.opt("buttonColor"),
                ControlElement.DEFAULT_BUTTON_COLOR,
            )
            val color = Color(0xFF000000 or colorValue.toLong()).copy(alpha = 0.72f)
            when {
                type == "D_PAD" -> {
                    drawRoundRect(color, androidx.compose.ui.geometry.Offset(cx - halfWidth, cy - halfHeight / 2), androidx.compose.ui.geometry.Size(halfWidth * 2, halfHeight))
                    drawRoundRect(color, androidx.compose.ui.geometry.Offset(cx - halfWidth / 2, cy - halfHeight), androidx.compose.ui.geometry.Size(halfWidth, halfHeight * 2))
                }
                shape == "CIRCLE" || type == "STICK" || type == "SHOOTER_MODE" -> drawCircle(color, radius = min(halfWidth, halfHeight), center = androidx.compose.ui.geometry.Offset(cx, cy))
                else -> drawRoundRect(
                    color,
                    androidx.compose.ui.geometry.Offset(cx - halfWidth, cy - halfHeight),
                    androidx.compose.ui.geometry.Size(halfWidth * 2, halfHeight * 2),
                    androidx.compose.ui.geometry.CornerRadius(base * 0.3f),
                )
            }
            val label = element.optString("text").ifBlank {
                bindingLabel(element.optJSONArray("bindings")?.opt(0))
            }.take(8)
            if (label.isNotBlank()) {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawText(
                        label,
                        cx,
                        cy - (textPaint.descent() + textPaint.ascent()) / 2f,
                        textPaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun sectionLabel(section: ControlProfileSection): String = stringResource(
    when (section) {
        ControlProfileSection.ON_SCREEN -> R.string.control_profile_section_on_screen
        ControlProfileSection.PHYSICAL_CONTROLLER -> R.string.control_profile_section_physical
        ControlProfileSection.RADIAL_MENU -> R.string.control_profile_section_radial
        ControlProfileSection.GYRO -> R.string.control_profile_section_gyro
        ControlProfileSection.TOUCHSCREEN -> R.string.control_profile_section_touchscreen
        ControlProfileSection.SHOOTER -> R.string.control_profile_section_shooter
    },
)

private fun bindingLabel(value: Any?): String = when (value) {
    is String -> value.removePrefix("GAMEPAD_").removePrefix("KEY_").replace('_', ' ')
    is JSONObject -> value.optJSONArray("bindings")?.optString(0).orEmpty().replace('_', ' ')
    else -> ""
}

private fun safeFileName(value: String): String =
    value.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .trim('.', ' ')
        .take(InputControlsManager.MAX_PROFILE_NAME_LENGTH)
        .ifBlank { "control-profile" }

private fun formatDecimal(value: Double): String = DecimalFormat(
    "0.##",
    DecimalFormatSymbols.getInstance(Locale.getDefault()),
).format(value)

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
