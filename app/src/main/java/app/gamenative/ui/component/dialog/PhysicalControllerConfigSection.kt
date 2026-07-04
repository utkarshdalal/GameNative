package app.gamenative.ui.component.dialog

import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.ui.component.dialog.state.MessageDialogState
import app.gamenative.ui.util.SnackbarManager
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalController
import com.winlator.inputcontrols.ExternalControllerBinding
import com.winlator.inputcontrols.InputControlsManager
import timber.log.Timber

private data class ButtonConfig(val label: String, val keyCode: Int)
private data class AnalogConfig(val label: String, val axis: Int, val sign: Int)

private enum class BindingCategory(val labelRes: Int) {
    FACE(R.string.face_buttons_category),
    SHOULDER(R.string.shoulder_buttons_category),
    MENU(R.string.menu_buttons_category),
    THUMBSTICK(R.string.thumbstick_buttons_category),
    LEFT_STICK(R.string.left_stick),
    RIGHT_STICK(R.string.right_stick),
    DPAD(R.string.dpad_category),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhysicalControllerConfigSection(
    profile: ControlsProfile,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current
    val inputControlsManager = remember { InputControlsManager(context) }

    // Ensure a wildcard controller exists for all physical controllers
    var controller by remember { mutableStateOf<ExternalController?>(null) }
    var initFailed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        ControllerPresetManager.ensureWildcardController(context, profile)

        // copy on-screen elements from default profile if current profile has empty/NONE elements
        val defaultProfile = inputControlsManager.getProfile(0)
        if (defaultProfile != null) {
            copyElementsIfNeeded(context, profile, defaultProfile)
        }

        val ctrl = profile.getController("*")
        if (ctrl != null) {
            controller = ctrl
        } else {
            Timber.e("Failed to get wildcard controller after ensureWildcardController")
            initFailed = true
        }
    }

    if (initFailed) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    val ctrl = controller ?: return

    val initialBindings = remember(ctrl) {
        buildMap {
            ctrl.getControllerBindings().forEach {
                put(it.getKeyCodeForAxis(), it.getBinding())
            }
        }
    }
    // snapshot of saved bindings — updated on save to track dirty state
    val originalBindings = remember { mutableStateMapOf<Int, Binding>().apply { putAll(initialBindings) } }
    val workingBindings = remember { mutableStateMapOf<Int, Binding>().apply { putAll(initialBindings) } }

    // dirty tracking — compare working copy to snapshot taken at open
    val isDirty by remember {
        derivedStateOf {
            if (workingBindings.size != originalBindings.size) return@derivedStateOf true
            originalBindings.any { (k, v) -> workingBindings[k] != v }
        }
    }

    var selectedCategory by remember { mutableStateOf(BindingCategory.FACE) }
    var showBindingDialog by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var showLoadPresetDialog by remember { mutableStateOf(false) }
    var showSavePresetDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var dismissDialogState by remember { mutableStateOf(MessageDialogState(visible = false)) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            if (!ControllerPresetManager.exportBindingsToUri(context, uri, workingBindings)) {
                SnackbarManager.show(context.getString(R.string.export_bindings_failed))
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val imported = ControllerPresetManager.importBindingsFromUri(context, uri)
            if (imported != null) {
                workingBindings.clear()
                workingBindings.putAll(imported)
                ControllerPresetManager.ensureHomeBindingInMap(workingBindings)
            } else {
                SnackbarManager.show(context.getString(R.string.import_bindings_failed))
            }
        }
    }

    val faceButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_a), KeyEvent.KEYCODE_BUTTON_A),
            ButtonConfig(context.getString(R.string.button_b), KeyEvent.KEYCODE_BUTTON_B),
            ButtonConfig(context.getString(R.string.button_x), KeyEvent.KEYCODE_BUTTON_X),
            ButtonConfig(context.getString(R.string.button_y), KeyEvent.KEYCODE_BUTTON_Y)
        )
    }

    val shoulderButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_l1), KeyEvent.KEYCODE_BUTTON_L1),
            ButtonConfig(context.getString(R.string.button_r1), KeyEvent.KEYCODE_BUTTON_R1),
            ButtonConfig(context.getString(R.string.button_l2), KeyEvent.KEYCODE_BUTTON_L2),
            ButtonConfig(context.getString(R.string.button_r2), KeyEvent.KEYCODE_BUTTON_R2)
        )
    }

    val menuButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_start), KeyEvent.KEYCODE_BUTTON_START),
            ButtonConfig(context.getString(R.string.button_select), KeyEvent.KEYCODE_BUTTON_SELECT),
            ButtonConfig(context.getString(R.string.button_home), KeyEvent.KEYCODE_BUTTON_MODE)
        )
    }

    val thumbstickButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_l3), KeyEvent.KEYCODE_BUTTON_THUMBL),
            ButtonConfig(context.getString(R.string.button_r3), KeyEvent.KEYCODE_BUTTON_THUMBR)
        )
    }

    val dpadButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.dpad_up), KeyEvent.KEYCODE_DPAD_UP),
            ButtonConfig(context.getString(R.string.dpad_down), KeyEvent.KEYCODE_DPAD_DOWN),
            ButtonConfig(context.getString(R.string.dpad_left), KeyEvent.KEYCODE_DPAD_LEFT),
            ButtonConfig(context.getString(R.string.dpad_right), KeyEvent.KEYCODE_DPAD_RIGHT)
        )
    }
    val dpadKeyCodes = remember(dpadButtons) { dpadButtons.map { it.keyCode } }

    val leftStickAxes = remember {
        listOf(
            AnalogConfig(context.getString(R.string.left_stick_up), MotionEvent.AXIS_Y, -1),
            AnalogConfig(context.getString(R.string.left_stick_down), MotionEvent.AXIS_Y, 1),
            AnalogConfig(context.getString(R.string.left_stick_left), MotionEvent.AXIS_X, -1),
            AnalogConfig(context.getString(R.string.left_stick_right), MotionEvent.AXIS_X, 1)
        )
    }

    val rightStickAxes = remember {
        listOf(
            AnalogConfig(context.getString(R.string.right_stick_up), MotionEvent.AXIS_RZ, -1),
            AnalogConfig(context.getString(R.string.right_stick_down), MotionEvent.AXIS_RZ, 1),
            AnalogConfig(context.getString(R.string.right_stick_left), MotionEvent.AXIS_Z, -1),
            AnalogConfig(context.getString(R.string.right_stick_right), MotionEvent.AXIS_Z, 1)
        )
    }

    val applyBindings: (Map<Int, Binding>) -> Unit = remember(ctrl) {
        { bindings: Map<Int, Binding> ->
            ControllerPresetManager.replaceBindings(ctrl, bindings)
        }
    }

    // restores original bindings and closes
    val discardAndDismiss = {
        applyBindings(originalBindings)
        onDismiss()
    }

    val saveToContainer: () -> Unit = {
        Timber.d("=== Save: Applying ${workingBindings.size} bindings ===")
        applyBindings(workingBindings)
        profile.save()
        Timber.d("Saved profile ${profile.name}")

        // reset dirty state
        originalBindings.clear()
        originalBindings.putAll(workingBindings)
    }

    // prompt if dirty, otherwise dismiss directly
    val onDismissCheck: () -> Unit = {
        if (isDirty) {
            dismissDialogState = MessageDialogState(
                visible = true,
                title = context.getString(R.string.unsaved_changes),
                message = context.getString(R.string.unsaved_changes_message),
                confirmBtnText = context.getString(R.string.discard),
                dismissBtnText = context.getString(R.string.cancel),
            )
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismissCheck,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.physical_controller_config) + if (isDirty) "*" else "",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismissCheck) {
                            Icon(Icons.Default.Close, null)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showLoadPresetDialog = true }) {
                            Icon(Icons.Default.Bookmarks, contentDescription = stringResource(R.string.load_controller_preset))
                        }
                        IconButton(onClick = { showSavePresetDialog = true }) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = stringResource(R.string.save_controller_preset))
                        }
                        IconButton(onClick = {
                            saveToContainer()
                            onSave()
                        }) {
                            Icon(Icons.Default.Save, null)
                        }
                        Box {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.reset_to_defaults)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        workingBindings.clear()
                                        workingBindings.putAll(ControllerPresetManager.getDefaultBindings())
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.import_bindings)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        importLauncher.launch(arrayOf("application/json"))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_bindings)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        exportLauncher.launch("controller-bindings.json")
                                    },
                                )
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(0.35f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        BindingCategory.entries.forEach { category ->
                            CategoryButton(
                                label = stringResource(category.labelRes),
                                isSelected = selectedCategory == category,
                                onClick = { selectedCategory = category },
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(0.65f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        when (selectedCategory) {
                            BindingCategory.FACE,
                            BindingCategory.SHOULDER,
                            BindingCategory.MENU,
                            BindingCategory.THUMBSTICK -> {
                                val buttonList = when (selectedCategory) {
                                    BindingCategory.FACE -> faceButtons
                                    BindingCategory.SHOULDER -> shoulderButtons
                                    BindingCategory.MENU -> menuButtons
                                    BindingCategory.THUMBSTICK -> thumbstickButtons
                                    else -> error("unreachable")
                                }
                                buttonList.forEach { buttonConfig ->
                                    ControllerBindingItem(
                                        label = buttonConfig.label,
                                        keyCode = buttonConfig.keyCode,
                                        workingBindings = workingBindings,
                                        onClick = {
                                            showBindingDialog = Pair(buttonConfig.keyCode, buttonConfig.label)
                                        }
                                    )
                                }
                            }
                            BindingCategory.LEFT_STICK -> {
                                AnalogCategoryContent(
                                    axes = leftStickAxes,
                                    workingBindings = workingBindings,
                                    onShowBindingDialog = { showBindingDialog = it },
                                )
                            }
                            BindingCategory.RIGHT_STICK -> {
                                AnalogCategoryContent(
                                    axes = rightStickAxes,
                                    workingBindings = workingBindings,
                                    onShowBindingDialog = { showBindingDialog = it },
                                )
                            }
                            BindingCategory.DPAD -> {
                                PhysicalControlPresets(
                                    keyCodes = dpadKeyCodes,
                                    workingBindings = workingBindings,
                                )
                                dpadButtons.forEach { buttonConfig ->
                                    ControllerBindingItem(
                                        label = buttonConfig.label,
                                        keyCode = buttonConfig.keyCode,
                                        workingBindings = workingBindings,
                                        onClick = {
                                            showBindingDialog = Pair(buttonConfig.keyCode, buttonConfig.label)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    showBindingDialog?.let { (keyCode, label) ->
        val currentBinding = workingBindings[keyCode]

        ControllerBindingDialog(
            buttonName = label,
            currentBinding = currentBinding,
            onDismiss = { showBindingDialog = null },
            onBindingSelected = { binding ->
                if (binding != null) {
                    workingBindings[keyCode] = binding
                    Timber.d("Updated binding for keyCode $keyCode to $binding")
                } else {
                    workingBindings.remove(keyCode)
                    Timber.d("Removed binding for keyCode $keyCode")
                }
                showBindingDialog = null
            }
        )
    }

    if (showLoadPresetDialog) {
        LoadControllerPresetDialog(
            onDismiss = { showLoadPresetDialog = false },
            onPresetSelected = { presetBindings ->
                workingBindings.clear()
                workingBindings.putAll(presetBindings)
                ControllerPresetManager.ensureHomeBindingInMap(workingBindings)
            },
        )
    }

    if (showSavePresetDialog) {
        SaveControllerPresetDialog(
            onDismiss = { showSavePresetDialog = false },
            onSave = { name ->
                if (!ControllerPresetManager.saveUserPreset(context, name, workingBindings)) {
                    SnackbarManager.show(context.getString(R.string.save_preset_failed))
                }
            },
        )
    }

    MessageDialog(
        visible = dismissDialogState.visible,
        title = dismissDialogState.title,
        message = dismissDialogState.message,
        confirmBtnText = dismissDialogState.confirmBtnText,
        dismissBtnText = dismissDialogState.dismissBtnText,
        onDismissRequest = { dismissDialogState = MessageDialogState(visible = false) },
        onDismissClick = { dismissDialogState = MessageDialogState(visible = false) },
        onConfirmClick = discardAndDismiss,
    )
}

@Composable
private fun CategoryButton(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(10.dp)
        )
    }
}

@Composable
private fun ControllerBindingItem(
    label: String,
    keyCode: Int,
    workingBindings: Map<Int, Binding>,
    onClick: () -> Unit
) {
    val binding = workingBindings[keyCode]
    val bindingText = binding?.toString() ?: stringResource(R.string.not_set)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = bindingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AnalogCategoryContent(
    axes: List<AnalogConfig>,
    workingBindings: MutableMap<Int, Binding>,
    onShowBindingDialog: (Pair<Int, String>) -> Unit,
) {
    val keyCodes = remember(axes) {
        axes.map { ExternalControllerBinding.getKeyCodeForAxis(it.axis, it.sign.toByte()) }
    }

    PhysicalControlPresets(
        keyCodes = keyCodes,
        workingBindings = workingBindings,
    )

    axes.forEachIndexed { i, analogConfig ->
        ControllerBindingItem(
            label = analogConfig.label,
            keyCode = keyCodes[i],
            workingBindings = workingBindings,
            onClick = { onShowBindingDialog(Pair(keyCodes[i], analogConfig.label)) }
        )
    }
}

@Composable
private fun PhysicalControlPresets(
    keyCodes: List<Int>,
    workingBindings: MutableMap<Int, Binding>,
) {
    val applyPreset: (PhysicalPresetBinding) -> Unit = { preset ->
        applyPhysicalPreset(keyCodes, preset, workingBindings)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.quick_presets),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                PresetButton(Modifier.weight(1f), R.string.preset_wasd) { applyPreset(PhysicalPresetBinding.WASD) }
                PresetButton(Modifier.weight(1f), R.string.preset_arrows) { applyPreset(PhysicalPresetBinding.ARROW_KEYS) }
                PresetButton(Modifier.weight(1f), R.string.preset_mouse) { applyPreset(PhysicalPresetBinding.MOUSE_MOVE) }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                PresetButton(Modifier.weight(1f), R.string.preset_dpad) { applyPreset(PhysicalPresetBinding.DPAD) }
                PresetButton(Modifier.weight(1f), R.string.preset_left_stick) { applyPreset(PhysicalPresetBinding.LEFT_STICK) }
                PresetButton(Modifier.weight(1f), R.string.preset_right_stick) { applyPreset(PhysicalPresetBinding.RIGHT_STICK) }
            }
        }
    }
}

@Composable
private fun PresetButton(modifier: Modifier, labelRes: Int, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
    ) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.labelSmall)
    }
}

private enum class PhysicalPresetBinding {
    WASD, ARROW_KEYS, MOUSE_MOVE, DPAD, LEFT_STICK, RIGHT_STICK
}

// all presets follow Up, Down, Left, Right order
private fun applyPhysicalPreset(
    keyCodes: List<Int>,
    preset: PhysicalPresetBinding,
    workingBindings: MutableMap<Int, Binding>
) {
    val bindings = when (preset) {
        PhysicalPresetBinding.WASD -> listOf(Binding.KEY_W, Binding.KEY_S, Binding.KEY_A, Binding.KEY_D)
        PhysicalPresetBinding.ARROW_KEYS -> listOf(Binding.KEY_UP, Binding.KEY_DOWN, Binding.KEY_LEFT, Binding.KEY_RIGHT)
        PhysicalPresetBinding.MOUSE_MOVE -> listOf(Binding.MOUSE_MOVE_UP, Binding.MOUSE_MOVE_DOWN, Binding.MOUSE_MOVE_LEFT, Binding.MOUSE_MOVE_RIGHT)
        PhysicalPresetBinding.DPAD -> listOf(Binding.GAMEPAD_DPAD_UP, Binding.GAMEPAD_DPAD_DOWN, Binding.GAMEPAD_DPAD_LEFT, Binding.GAMEPAD_DPAD_RIGHT)
        PhysicalPresetBinding.LEFT_STICK -> listOf(Binding.GAMEPAD_LEFT_THUMB_UP, Binding.GAMEPAD_LEFT_THUMB_DOWN, Binding.GAMEPAD_LEFT_THUMB_LEFT, Binding.GAMEPAD_LEFT_THUMB_RIGHT)
        PhysicalPresetBinding.RIGHT_STICK -> listOf(Binding.GAMEPAD_RIGHT_THUMB_UP, Binding.GAMEPAD_RIGHT_THUMB_DOWN, Binding.GAMEPAD_RIGHT_THUMB_LEFT, Binding.GAMEPAD_RIGHT_THUMB_RIGHT)
    }
    keyCodes.forEachIndexed { i, keyCode ->
        if (keyCode != 0 && i < bindings.size) {
            workingBindings[keyCode] = bindings[i]
        }
    }
}

private fun copyElementsIfNeeded(context: android.content.Context, destProfile: ControlsProfile, sourceProfile: ControlsProfile) {
    try {
        val destFile = ControlsProfile.getProfileFile(context, destProfile.id)
        val sourceFile = ControlsProfile.getProfileFile(context, sourceProfile.id)

        if (!sourceFile.isFile()) {
            Timber.w("copyElements: Source profile file not found")
            return
        }

        val sourceJson = org.json.JSONObject(com.winlator.core.FileUtils.readString(sourceFile))
        if (!sourceJson.has("elements")) {
            Timber.w("copyElements: Source profile has no elements")
            return
        }
        val sourceElements = sourceJson.getJSONArray("elements")

        // read dest once, reuse for both check and write
        val destJson = if (destFile.isFile()) {
            org.json.JSONObject(com.winlator.core.FileUtils.readString(destFile))
        } else {
            null
        }

        val needsCopy = when {
            destJson == null -> true
            !destJson.has("elements") || destJson.getJSONArray("elements").length() == 0 -> true
            else -> {
                val destElements = destJson.getJSONArray("elements")
                var hasGamepadBindings = false
                for (i in 0 until destElements.length()) {
                    val element = destElements.getJSONObject(i)
                    if (element.has("bindings")) {
                        val bindings = element.getJSONArray("bindings")
                        for (j in 0 until bindings.length()) {
                            if (bindings.getString(j).startsWith("GAMEPAD_")) {
                                hasGamepadBindings = true
                                break
                            }
                        }
                    }
                    if (hasGamepadBindings) break
                }
                !hasGamepadBindings
            }
        }

        if (needsCopy) {
            val outJson = destJson ?: org.json.JSONObject().apply {
                put("id", destProfile.id)
                put("name", destProfile.name)
                put("cursorSpeed", destProfile.cursorSpeed)
            }

            outJson.put("elements", sourceElements)
            com.winlator.core.FileUtils.writeString(destFile, outJson.toString())
            Timber.d("Copied ${sourceElements.length()} elements")
        }
    } catch (e: Exception) {
        Timber.e(e, "copyElements: Failed")
    }
}
