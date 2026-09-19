package app.gamenative.ui.component.dialog

import android.graphics.Paint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
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

@OptIn(ExperimentalMaterial3Api::class)
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
    var profiles by remember { mutableStateOf(manager.getProfiles(false).toList()) }
    var appliedProfileId by remember {
        mutableStateOf(ControlProfileService.appliedLibraryProfileId(container, manager))
    }
    var previewAction by remember { mutableStateOf<ProfilePreviewAction?>(null) }
    var sectionAction by remember { mutableStateOf<SectionAction?>(null) }
    var createDialog by remember { mutableStateOf(false) }
    var renameProfile by remember { mutableStateOf<ControlsProfile?>(null) }
    var deleteProfile by remember { mutableStateOf<ControlsProfile?>(null) }
    var pendingExport by remember {
        mutableStateOf<Pair<ControlsProfile, Set<ControlProfileSection>>?>(null)
    }

    fun refresh() {
        profiles = manager.getProfiles(false).toList()
        appliedProfileId = ControlProfileService.appliedLibraryProfileId(container, manager)
    }

    fun failure(error: Throwable) {
        SnackbarManager.show(
            context.getString(R.string.control_profile_failed, error.message ?: error.javaClass.simpleName),
        )
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri != null && request != null) {
            runCatching {
                ControlProfileService.exportProfile(context, request.first, request.second, uri)
            }.onSuccess {
                SnackbarManager.show(context.getString(R.string.control_profile_exported))
            }.onFailure(::failure)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { ControlProfileService.importProfile(context, uri) }
                .onSuccess { imported ->
                    previewAction = ProfilePreviewAction(
                        preview = imported,
                        confirmLabel = context.getString(R.string.control_profile_import_confirm),
                        onConfirm = {
                            runCatching { ControlProfileService.installImported(manager, imported) }
                                .onSuccess {
                                    refresh()
                                    SnackbarManager.show(context.getString(R.string.control_profile_imported))
                                }
                                .onFailure(::failure)
                        },
                    )
                }
                .onFailure(::failure)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = PluviaBackground,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.control_profiles)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        }) {
                            Icon(Icons.Default.Download, contentDescription = stringResource(R.string.control_profile_import))
                        }
                        IconButton(onClick = { createDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.control_profile_create))
                        }
                    },
                )
            },
        ) { padding ->
            if (profiles.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.control_profiles_empty))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(profiles, key = { it.id }) { profile ->
                        val profilePreview = remember(profile.id, profiles) {
                            runCatching { ControlProfileService.preview(context, profile) }.getOrNull()
                        }
                        if (profilePreview != null) {
                            val builtIn = remember(profile.id, profiles) {
                                ControlProfileService.isBuiltIn(context, profile)
                            }
                            ControlProfileCard(
                                profile = profile,
                                preview = profilePreview,
                                applied = profile.id == appliedProfileId,
                                onPreview = { previewAction = ProfilePreviewAction(profilePreview) },
                                onApply = {
                                    sectionAction = SectionAction(
                                        title = context.getString(R.string.control_profile_apply),
                                        available = profilePreview.sections,
                                        confirmLabel = context.getString(R.string.control_profile_apply),
                                    ) { sections ->
                                        runCatching {
                                            ControlProfileService.applyProfile(
                                                context,
                                                container,
                                                manager,
                                                profile,
                                                sections,
                                            )
                                        }.onSuccess { applied ->
                                            if (applied != null) onProfileApplied(applied)
                                            refresh()
                                            SnackbarManager.show(context.getString(R.string.control_profile_applied_message))
                                        }.onFailure(::failure)
                                    }
                                },
                                onSaveCurrent = if (profile.id == appliedProfileId && !builtIn) ({
                                    sectionAction = SectionAction(
                                        title = context.getString(R.string.control_profile_update_current),
                                        available = ControlProfileSection.entries.toSet(),
                                        selected = profilePreview.sections,
                                        confirmLabel = context.getString(R.string.save),
                                    ) { sections ->
                                        runCatching {
                                            ControlProfileService.updateFromCurrent(
                                                context,
                                                container,
                                                manager,
                                                profile,
                                                sections,
                                            )
                                        }.onSuccess {
                                            refresh()
                                            SnackbarManager.show(context.getString(R.string.control_profile_saved))
                                        }.onFailure(::failure)
                                    }
                                }) else null,
                                onDuplicate = {
                                    runCatching { manager.duplicateProfile(profile) }
                                        .onSuccess {
                                            manager.reloadProfiles()
                                            refresh()
                                        }
                                        .onFailure(::failure)
                                },
                                onRename = if (builtIn) null else ({ renameProfile = profile }),
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
                                onDelete = if (builtIn) null else ({ deleteProfile = profile }),
                            )
                        }
                    }
                }
            }
        }
    }

    if (createDialog) {
        CreateControlProfileDialog(
            onDismiss = { createDialog = false },
            onCreateBlank = { name ->
                runCatching { ControlProfileService.createBlank(context, manager, name) }
                    .onSuccess {
                        createDialog = false
                        refresh()
                    }
                    .onFailure(::failure)
            },
            onCreateFromCurrent = { name, sections ->
                runCatching {
                    ControlProfileService.saveCurrentAsProfile(
                        context,
                        container,
                        manager,
                        name,
                        sections,
                    )
                }.onSuccess {
                    createDialog = false
                    refresh()
                    SnackbarManager.show(context.getString(R.string.control_profile_saved))
                }.onFailure(::failure)
            },
        )
    }

    val pendingRename: ControlsProfile? = renameProfile
    pendingRename?.let { profile: ControlsProfile ->
        ControlProfileNameDialog(
            title = stringResource(R.string.control_profile_rename),
            initialName = profile.name,
            onDismiss = { renameProfile = null },
            onConfirm = { name: String ->
                runCatching { ControlProfileService.rename(context, manager, profile, name) }
                    .onSuccess {
                        renameProfile = null
                        refresh()
                    }
                    .onFailure(::failure)
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
                    manager.removeProfile(profile)
                    deleteProfile = null
                    refresh()
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
private fun ControlProfileCard(
    profile: ControlsProfile,
    preview: ControlProfilePreview,
    applied: Boolean,
    onPreview: () -> Unit,
    onApply: () -> Unit,
    onSaveCurrent: (() -> Unit)?,
    onDuplicate: () -> Unit,
    onRename: (() -> Unit)?,
    onExport: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = if (applied) 5.dp else 1.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (applied) {
                        Text(
                            stringResource(R.string.control_profile_applied),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
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
            ProfileSectionChips(preview.sections)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onPreview) { Text(stringResource(R.string.control_profile_preview)) }
                Button(onClick = onApply) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.control_profile_apply))
                }
            }
        }
    }
}

@Composable
private fun ProfileSectionChips(sections: Set<ControlProfileSection>) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ControlProfileSection.entries.filter { it in sections }.forEach { section ->
            AssistChip(onClick = {}, label = { Text(sectionLabel(section)) })
        }
    }
}

@Composable
private fun CreateControlProfileDialog(
    onDismiss: () -> Unit,
    onCreateBlank: (String) -> Unit,
    onCreateFromCurrent: (String, Set<ControlProfileSection>) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var fromCurrent by remember { mutableStateOf(true) }
    var sections by remember { mutableStateOf(ControlProfileSection.entries.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.control_profile_create)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.control_profile_name)) },
                    singleLine = true,
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.control_profile_create_current)) },
                    leadingContent = { RadioButton(selected = fromCurrent, onClick = { fromCurrent = true }) },
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.control_profile_create_blank)) },
                    leadingContent = { RadioButton(selected = !fromCurrent, onClick = { fromCurrent = false }) },
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)),
                )
                if (fromCurrent) {
                    Text(stringResource(R.string.control_profile_sections), fontWeight = FontWeight.SemiBold)
                    ControlProfileSection.entries.forEach { section ->
                        SectionCheckbox(section, section in sections) { checked ->
                            sections = if (checked) sections + section else sections - section
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && (!fromCurrent || sections.isNotEmpty()),
                onClick = {
                    if (fromCurrent) onCreateFromCurrent(name.trim(), sections)
                    else onCreateBlank(name.trim())
                },
            ) { Text(stringResource(R.string.control_profile_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun ControlProfileNameDialog(
    title: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.control_profile_name)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name.trim()) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
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
            Column {
                Text(stringResource(R.string.control_profile_sections), fontWeight = FontWeight.SemiBold)
                action.available.forEach { section ->
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
        trailingContent = { Checkbox(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

@Composable
private fun ControlProfilePreviewDialog(
    action: ProfilePreviewAction,
    screenSize: String,
    onDismiss: () -> Unit,
    onConfirm: (() -> Unit)?,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 720.dp),
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
                    modifier = Modifier.weight(1f, fill = false),
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

@Composable
private fun ProfileSettingsSummary(preview: ControlProfilePreview) {
    val gyro = preview.json.optJSONObject("gyroSettings")
    val touch = preview.json.optJSONObject("touchscreenSettings")
    val shooter = preview.json.optJSONObject("shooterSettings")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (gyro != null) {
            Text(
                stringResource(
                    R.string.control_profile_gyro_summary,
                    gyro.optInt("mode"),
                    formatDecimal(gyro.optDouble("sensitivity", 1.0)),
                ),
            )
        }
        if (touch != null) {
            Text(
                stringResource(
                    R.string.control_profile_touchscreen_summary,
                    stringResource(if (touch.optBoolean("enabled")) R.string.enabled else R.string.disabled),
                ),
            )
        }
        if (shooter != null) {
            Text(
                stringResource(
                    R.string.control_profile_shooter_summary,
                    stringResource(if (shooter.optBoolean("enabled")) R.string.enabled else R.string.disabled),
                ),
            )
        }
    }
}

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
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = foreground
            textAlign = Paint.Align.CENTER
            textSize = min(size.width, size.height) * 0.035f
        }
        for (index in 0 until elements.length()) {
            val element = elements.optJSONObject(index) ?: continue
            val scale = element.optDouble("scale", 1.0).toFloat().coerceIn(0.25f, 4f)
            val base = size.width / 30f
            val type = element.optString("type", "BUTTON")
            val shape = element.optString("shape", "CIRCLE")
            val halfWidth = base * scale * when (type) {
                "D_PAD" -> 2.2f
                "STICK", "TRACKPAD" -> 1.9f
                "RANGE_BUTTON" -> 2.3f
                else -> if (shape == "RECT" || shape == "ROUND_RECT") 1.35f else 1f
            }
            val halfHeight = base * scale * when (type) {
                "D_PAD" -> 2.2f
                "STICK", "TRACKPAD" -> 1.9f
                else -> 1f
            }
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
    value.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "control-profile" }

private fun formatDecimal(value: Double): String = if (value % 1.0 == 0.0) {
    value.toInt().toString()
} else {
    "%.2f".format(value).trimEnd('0').trimEnd('.')
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
