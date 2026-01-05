package app.gamenative.ui.component.dialog

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
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
import com.winlator.inputcontrols.Binding
import com.winlator.inputcontrols.ControlsProfile
import com.winlator.inputcontrols.ExternalControllerBinding

/**
 * Data classes for controller configuration
 */
private data class ButtonConfig(val label: String, val keyCode: Int)
private data class AnalogConfig(val label: String, val axis: Int, val sign: Int)

/**
 * Physical Controller Configuration with two-column categorized layout
 *
 * Shows controller bindings organized by categories (Buttons, Left Stick, Right Stick, D-Pad)
 * with a UI matching the ControllerBindingDialog design.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PhysicalControllerConfigSection(
    profile: ControlsProfile,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current

    // Ensure a wildcard controller exists for all physical controllers
    val controller = remember {
        var ctrl = profile.getController("*")
        if (ctrl == null) {
            Log.d("gncontrol", "=== Physical Controller Init: Creating wildcard controller for profile: ${profile.name} (ID: ${profile.id}) ===")
            ctrl = profile.addController("*")

            // Copy default bindings from the Physical Controller Default profile (ID 0)
            val manager = com.winlator.inputcontrols.InputControlsManager(context)
            val defaultProfile = manager.getProfile(0)
            if (defaultProfile != null) {
                Log.d("gncontrol", "Loading defaults from profile: ${defaultProfile.name} (ID: ${defaultProfile.id})")
                val defaultControllers = defaultProfile.getControllers()
                if (defaultControllers.isNotEmpty()) {
                    val defaultController = defaultControllers[0]
                    val bindingCount = defaultController.getControllerBindings().size
                    Log.d("gncontrol", "Copying $bindingCount default controller bindings from ${defaultProfile.name}")
                    for (binding in defaultController.getControllerBindings()) {
                        val newBinding = ExternalControllerBinding()
                        newBinding.setKeyCode(binding.getKeyCodeForAxis())
                        newBinding.setBinding(binding.getBinding())
                        ctrl.addControllerBinding(newBinding)
                    }

                    // Ensure Home/Guide/PS button is always set to OPEN_NAVIGATION_MENU
                    val homeButtonBinding = ExternalControllerBinding()
                    homeButtonBinding.setKeyCode(KeyEvent.KEYCODE_BUTTON_MODE)
                    homeButtonBinding.setBinding(com.winlator.inputcontrols.Binding.OPEN_NAVIGATION_MENU)
                    // Remove any existing home button binding first
                    val existingHomeBinding = ctrl.getControllerBindings().find {
                        it.getKeyCodeForAxis() == KeyEvent.KEYCODE_BUTTON_MODE
                    }
                    if (existingHomeBinding != null) {
                        ctrl.removeControllerBinding(existingHomeBinding)
                    }
                    ctrl.addControllerBinding(homeButtonBinding)
                    Log.d("gncontrol", "Set Home button (KEYCODE_BUTTON_MODE) to OPEN_NAVIGATION_MENU")
                } else {
                    Log.w("gncontrol", "No controllers found in default profile ${defaultProfile.name}")
                }

                // Copy on-screen elements from default profile if current profile has empty/NONE elements
                copyElementsIfNeeded(context, profile, defaultProfile)
            } else {
                Log.w("gncontrol", "Default profile 0 not found, wildcard controller will be empty")
            }

            profile.save()
        }
        ctrl
    }

    // Create a snapshot of original bindings for cancel behavior
    val originalBindings = remember {
        controller?.getControllerBindings()?.map {
            it.getKeyCodeForAxis() to it.getBinding()
        }?.toMap() ?: emptyMap()
    }

    // Working copy of bindings (memory only until Save is clicked)
    val workingBindings = remember { mutableStateMapOf<Int, com.winlator.inputcontrols.Binding?>() }

    // Initialize working copy with current bindings
    LaunchedEffect(controller) {
        controller?.getControllerBindings()?.forEach {
            workingBindings[it.getKeyCodeForAxis()] = it.getBinding()
        }
    }

    var selectedCategory by remember { mutableStateOf(0) } // 0 = Face, 1 = Shoulder, 2 = Menu, 3 = Thumbstick, 4 = Left Stick, 5 = Right Stick, 6 = D-Pad
    var showBindingDialog by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // Pre-compute all button configurations
    // Face buttons
    val faceButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_a), KeyEvent.KEYCODE_BUTTON_A),
            ButtonConfig(context.getString(R.string.button_b), KeyEvent.KEYCODE_BUTTON_B),
            ButtonConfig(context.getString(R.string.button_x), KeyEvent.KEYCODE_BUTTON_X),
            ButtonConfig(context.getString(R.string.button_y), KeyEvent.KEYCODE_BUTTON_Y)
        )
    }

    // Shoulder buttons
    val shoulderButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_l1), KeyEvent.KEYCODE_BUTTON_L1),
            ButtonConfig(context.getString(R.string.button_r1), KeyEvent.KEYCODE_BUTTON_R1),
            ButtonConfig(context.getString(R.string.button_l2), KeyEvent.KEYCODE_BUTTON_L2),
            ButtonConfig(context.getString(R.string.button_r2), KeyEvent.KEYCODE_BUTTON_R2)
        )
    }

    // Menu buttons
    val menuButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_start), KeyEvent.KEYCODE_BUTTON_START),
            ButtonConfig(context.getString(R.string.button_select), KeyEvent.KEYCODE_BUTTON_SELECT),
            ButtonConfig(context.getString(R.string.button_home), KeyEvent.KEYCODE_BUTTON_MODE)
        )
    }

    // Thumbstick buttons
    val thumbstickButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.button_l3), KeyEvent.KEYCODE_BUTTON_THUMBL),
            ButtonConfig(context.getString(R.string.button_r3), KeyEvent.KEYCODE_BUTTON_THUMBR)
        )
    }

    // D-Pad
    val dpadButtons = remember {
        listOf(
            ButtonConfig(context.getString(R.string.dpad_up), KeyEvent.KEYCODE_DPAD_UP),
            ButtonConfig(context.getString(R.string.dpad_down), KeyEvent.KEYCODE_DPAD_DOWN),
            ButtonConfig(context.getString(R.string.dpad_left), KeyEvent.KEYCODE_DPAD_LEFT),
            ButtonConfig(context.getString(R.string.dpad_right), KeyEvent.KEYCODE_DPAD_RIGHT)
        )
    }

    // Left analog stick
    val leftStickAxes = remember {
        listOf(
            AnalogConfig(context.getString(R.string.left_stick_up), MotionEvent.AXIS_Y, -1),
            AnalogConfig(context.getString(R.string.left_stick_down), MotionEvent.AXIS_Y, 1),
            AnalogConfig(context.getString(R.string.left_stick_left), MotionEvent.AXIS_X, -1),
            AnalogConfig(context.getString(R.string.left_stick_right), MotionEvent.AXIS_X, 1)
        )
    }

    // Right analog stick
    val rightStickAxes = remember {
        listOf(
            AnalogConfig(context.getString(R.string.right_stick_up), MotionEvent.AXIS_RZ, -1),
            AnalogConfig(context.getString(R.string.right_stick_down), MotionEvent.AXIS_RZ, 1),
            AnalogConfig(context.getString(R.string.right_stick_left), MotionEvent.AXIS_Z, -1),
            AnalogConfig(context.getString(R.string.right_stick_right), MotionEvent.AXIS_Z, 1)
        )
    }

    Dialog(
        onDismissRequest = {
            // Cancel: Restore original bindings
            controller?.let { ctrl ->
                val existingBindings = ctrl.getControllerBindings().toList()
                for (binding in existingBindings) {
                    ctrl.removeControllerBinding(binding)
                }
                for ((keyCode, binding) in originalBindings) {
                    val newBinding = ExternalControllerBinding()
                    newBinding.setKeyCode(keyCode)
                    newBinding.setBinding(binding)
                    ctrl.addControllerBinding(newBinding)
                }
            }
            onDismiss()
        },
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
                            text = stringResource(R.string.physical_controller_config),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            // Cancel: Restore original bindings
                            controller?.let { ctrl ->
                                val existingBindings = ctrl.getControllerBindings().toList()
                                for (binding in existingBindings) {
                                    ctrl.removeControllerBinding(binding)
                                }
                                for ((keyCode, binding) in originalBindings) {
                                    val newBinding = ExternalControllerBinding()
                                    newBinding.setKeyCode(keyCode)
                                    newBinding.setBinding(binding)
                                    ctrl.addControllerBinding(newBinding)
                                }
                            }
                            onDismiss()
                        }) {
                            Icon(Icons.Default.Close, null)
                        }
                    },
                    actions = {
                        // Reset button
                        IconButton(onClick = {
                            Log.d("gncontrol", "=== Reset: Resetting controller bindings ===")
                            workingBindings.clear()

                            val manager = com.winlator.inputcontrols.InputControlsManager(context)
                            val defaultProfile = manager.getProfile(0)
                            if (defaultProfile != null) {
                                val defaultControllers = defaultProfile.getControllers()
                                if (defaultControllers.isNotEmpty()) {
                                    val defaultController = defaultControllers[0]
                                    for (binding in defaultController.getControllerBindings()) {
                                        workingBindings[binding.getKeyCodeForAxis()] = binding.getBinding()
                                    }
                                }
                            }

                            // Ensure Home/Guide/PS button is always set to OPEN_NAVIGATION_MENU
                            workingBindings[KeyEvent.KEYCODE_BUTTON_MODE] = com.winlator.inputcontrols.Binding.OPEN_NAVIGATION_MENU
                            Log.d("gncontrol", "Set Home button (KEYCODE_BUTTON_MODE) to OPEN_NAVIGATION_MENU")

                            refreshKey++
                        }) {
                            Icon(Icons.Default.Refresh, null)
                        }

                        // Save button
                        IconButton(onClick = {
                            Log.d("gncontrol", "=== Save: Applying ${workingBindings.size} bindings ===")
                            controller?.let { ctrl ->
                                val existingBindings = ctrl.getControllerBindings().toList()
                                for (binding in existingBindings) {
                                    ctrl.removeControllerBinding(binding)
                                }

                                for ((keyCode, binding) in workingBindings) {
                                    if (binding != null) {
                                        val newBinding = ExternalControllerBinding()
                                        newBinding.setKeyCode(keyCode)
                                        newBinding.setBinding(binding)
                                        ctrl.addControllerBinding(newBinding)
                                    }
                                }

                                val manager = com.winlator.inputcontrols.InputControlsManager(context)
                                val defaultProfile = manager.getProfile(0)
                                if (defaultProfile != null) {
                                    copyElementsIfNeeded(context, profile, defaultProfile)
                                }

                                profile.save()
                                Log.d("gncontrol", "Saved profile ${profile.name}")
                            }
                            onSave()
                        }) {
                            Icon(Icons.Default.Save, null)
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
                // Two-column layout: Left = Categories, Right = Bindings list
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Left column: Category selection (scrollable)
                    Column(
                        modifier = Modifier
                            .weight(0.35f)
                            .fillMaxHeight()
                            .padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Scrollable categories
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // Face Buttons category
                        CategoryButton(
                            label = stringResource(R.string.face_buttons_category),
                            isSelected = selectedCategory == 0,
                            onClick = { selectedCategory = 0 }
                        )

                        // Shoulder Buttons category
                        CategoryButton(
                            label = stringResource(R.string.shoulder_buttons_category),
                            isSelected = selectedCategory == 1,
                            onClick = { selectedCategory = 1 }
                        )

                        // Menu Buttons category
                        CategoryButton(
                            label = stringResource(R.string.menu_buttons_category),
                            isSelected = selectedCategory == 2,
                            onClick = { selectedCategory = 2 }
                        )

                        // Thumbstick Buttons category
                        CategoryButton(
                            label = stringResource(R.string.thumbstick_buttons_category),
                            isSelected = selectedCategory == 3,
                            onClick = { selectedCategory = 3 }
                        )

                        // Left Stick category
                        CategoryButton(
                            label = stringResource(R.string.left_stick),
                            isSelected = selectedCategory == 4,
                            onClick = { selectedCategory = 4 }
                        )

                        // Right Stick category
                        CategoryButton(
                            label = stringResource(R.string.right_stick),
                            isSelected = selectedCategory == 5,
                            onClick = { selectedCategory = 5 }
                        )

                        // D-Pad category
                        CategoryButton(
                            label = stringResource(R.string.dpad_category),
                            isSelected = selectedCategory == 6,
                            onClick = { selectedCategory = 6 }
                        )
                        }
                    }

                    // Right column: Bindings list
                    key(refreshKey) {
                        Column(
                            modifier = Modifier
                                .weight(0.65f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            when (selectedCategory) {
                                0 -> {
                                    // Face Buttons
                                    faceButtons.forEach { buttonConfig ->
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
                                1 -> {
                                    // Shoulder Buttons
                                    shoulderButtons.forEach { buttonConfig ->
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
                                2 -> {
                                    // Menu Buttons
                                    menuButtons.forEach { buttonConfig ->
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
                                3 -> {
                                    // Thumbstick Buttons
                                    thumbstickButtons.forEach { buttonConfig ->
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
                                4 -> {
                                    // Left Stick - Quick Presets
                                    PhysicalControlPresets(
                                        presetType = PhysicalPresetTarget.LEFT_STICK,
                                        leftStickAxes = leftStickAxes,
                                        rightStickAxes = rightStickAxes,
                                        dpadButtons = dpadButtons,
                                        workingBindings = workingBindings,
                                        onPresetsApplied = { refreshKey++ }
                                    )

                                    // Left Stick bindings
                                    leftStickAxes.forEach { analogConfig ->
                                        val keyCode = ExternalControllerBinding.getKeyCodeForAxis(
                                            analogConfig.axis,
                                            analogConfig.sign.toByte()
                                        )
                                        ControllerBindingItem(
                                            label = analogConfig.label,
                                            keyCode = keyCode,
                                            workingBindings = workingBindings,
                                            onClick = {
                                                showBindingDialog = Pair(keyCode, analogConfig.label)
                                            }
                                        )
                                    }
                                }
                                5 -> {
                                    // Right Stick - Quick Presets
                                    PhysicalControlPresets(
                                        presetType = PhysicalPresetTarget.RIGHT_STICK,
                                        leftStickAxes = leftStickAxes,
                                        rightStickAxes = rightStickAxes,
                                        dpadButtons = dpadButtons,
                                        workingBindings = workingBindings,
                                        onPresetsApplied = { refreshKey++ }
                                    )

                                    // Right Stick bindings
                                    rightStickAxes.forEach { analogConfig ->
                                        val keyCode = ExternalControllerBinding.getKeyCodeForAxis(
                                            analogConfig.axis,
                                            analogConfig.sign.toByte()
                                        )
                                        ControllerBindingItem(
                                            label = analogConfig.label,
                                            keyCode = keyCode,
                                            workingBindings = workingBindings,
                                            onClick = {
                                                showBindingDialog = Pair(keyCode, analogConfig.label)
                                            }
                                        )
                                    }
                                }
                                6 -> {
                                    // D-Pad - Quick Presets
                                    PhysicalControlPresets(
                                        presetType = PhysicalPresetTarget.DPAD,
                                        leftStickAxes = leftStickAxes,
                                        rightStickAxes = rightStickAxes,
                                        dpadButtons = dpadButtons,
                                        workingBindings = workingBindings,
                                        onPresetsApplied = { refreshKey++ }
                                    )

                                    // D-Pad bindings
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
    }

    // Binding selector dialog
    showBindingDialog?.let { (keyCode, label) ->
        val currentBinding = workingBindings[keyCode]

        ControllerBindingDialog(
            buttonName = label,
            currentBinding = currentBinding,
            onDismiss = { showBindingDialog = null },
            onBindingSelected = { binding ->
                if (binding != null) {
                    workingBindings[keyCode] = binding
                    Log.d("gncontrol", "Updated binding for keyCode $keyCode to $binding")
                } else {
                    workingBindings.remove(keyCode)
                    Log.d("gncontrol", "Removed binding for keyCode $keyCode")
                }

                refreshKey++
                showBindingDialog = null
            }
        )
    }
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
    workingBindings: Map<Int, com.winlator.inputcontrols.Binding?>,
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

/**
 * Quick preset buttons for physical controller stick/dpad bindings
 */
@Composable
private fun PhysicalControlPresets(
    presetType: PhysicalPresetTarget,
    leftStickAxes: List<AnalogConfig>,
    rightStickAxes: List<AnalogConfig>,
    dpadButtons: List<ButtonConfig>,
    workingBindings: MutableMap<Int, Binding?>,
    onPresetsApplied: () -> Unit = {}
) {
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

            // Keyboard/Mouse presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.WASD,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_wasd), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.ARROW_KEYS,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_arrows), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.MOUSE_MOVE,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_mouse), style = MaterialTheme.typography.labelSmall)
                }
            }

            // Gamepad presets
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.DPAD,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_dpad), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.LEFT_STICK,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_left_stick), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyPhysicalPreset(
                            presetType,
                            PhysicalPresetBinding.RIGHT_STICK,
                            leftStickAxes,
                            rightStickAxes,
                            dpadButtons,
                            workingBindings
                        )
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_right_stick), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * Target for physical controller presets
 */
private enum class PhysicalPresetTarget {
    LEFT_STICK, RIGHT_STICK, DPAD
}

/**
 * Binding presets for physical controller inputs
 */
private enum class PhysicalPresetBinding {
    WASD, ARROW_KEYS, MOUSE_MOVE, DPAD, LEFT_STICK, RIGHT_STICK
}

/**
 * Apply a preset binding to physical controller inputs
 */
private fun applyPhysicalPreset(
    target: PhysicalPresetTarget,
    preset: PhysicalPresetBinding,
    leftStickAxes: List<AnalogConfig>,
    rightStickAxes: List<AnalogConfig>,
    dpadButtons: List<ButtonConfig>,
    workingBindings: MutableMap<Int, com.winlator.inputcontrols.Binding?>
) {
    // Define bindings for each preset (Up, Down, Left, Right order for sticks; Up, Down, Left, Right for dpad buttons)
    val bindings = when (preset) {
        PhysicalPresetBinding.WASD -> listOf(
            com.winlator.inputcontrols.Binding.KEY_W,
            com.winlator.inputcontrols.Binding.KEY_S,
            com.winlator.inputcontrols.Binding.KEY_A,
            com.winlator.inputcontrols.Binding.KEY_D
        )
        PhysicalPresetBinding.ARROW_KEYS -> listOf(
            com.winlator.inputcontrols.Binding.KEY_UP,
            com.winlator.inputcontrols.Binding.KEY_DOWN,
            com.winlator.inputcontrols.Binding.KEY_LEFT,
            com.winlator.inputcontrols.Binding.KEY_RIGHT
        )
        PhysicalPresetBinding.MOUSE_MOVE -> listOf(
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_UP,
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_DOWN,
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_LEFT,
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_RIGHT
        )
        PhysicalPresetBinding.DPAD -> listOf(
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_UP,
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_DOWN,
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_LEFT,
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_RIGHT
        )
        PhysicalPresetBinding.LEFT_STICK -> listOf(
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_UP,
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_DOWN,
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_LEFT,
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_RIGHT
        )
        PhysicalPresetBinding.RIGHT_STICK -> listOf(
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_UP,
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_DOWN,
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_LEFT,
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_RIGHT
        )
    }

    // Get keyCodes based on target
    val keyCodes = when (target) {
        PhysicalPresetTarget.LEFT_STICK -> {
            // Up, Down, Left, Right
            leftStickAxes.map { config ->
                ExternalControllerBinding.getKeyCodeForAxis(config.axis, config.sign.toByte())
            }
        }
        PhysicalPresetTarget.RIGHT_STICK -> {
            // Up, Down, Left, Right
            rightStickAxes.map { config ->
                ExternalControllerBinding.getKeyCodeForAxis(config.axis, config.sign.toByte())
            }
        }
        PhysicalPresetTarget.DPAD -> {
            // Up, Down, Left, Right
            dpadButtons.map { config ->
                config.keyCode
            }
        }
    }

    // Apply bindings
    keyCodes.forEachIndexed { index, keyCode ->
        if (keyCode != 0 && index < bindings.size) {
            workingBindings[keyCode] = bindings[index]
        }
    }
}

/**
 * Copies on-screen elements from source profile to destination profile if needed.
 */
private fun copyElementsIfNeeded(context: android.content.Context, destProfile: ControlsProfile, sourceProfile: ControlsProfile) {
    try {
        val destFile = ControlsProfile.getProfileFile(context, destProfile.id)
        val sourceFile = ControlsProfile.getProfileFile(context, sourceProfile.id)

        if (!sourceFile.isFile()) {
            Log.w("gncontrol", "copyElements: Source profile file not found")
            return
        }

        val sourceJson = org.json.JSONObject(com.winlator.core.FileUtils.readString(sourceFile))
        if (!sourceJson.has("elements")) {
            Log.w("gncontrol", "copyElements: Source profile has no elements")
            return
        }
        val sourceElements = sourceJson.getJSONArray("elements")

        var needsCopy = false
        if (!destFile.isFile()) {
            needsCopy = true
        } else {
            val destJson = org.json.JSONObject(com.winlator.core.FileUtils.readString(destFile))
            if (!destJson.has("elements") || destJson.getJSONArray("elements").length() == 0) {
                needsCopy = true
            } else {
                val destElements = destJson.getJSONArray("elements")
                var hasGamepadBindings = false
                for (i in 0 until destElements.length()) {
                    val element = destElements.getJSONObject(i)
                    if (element.has("bindings")) {
                        val bindings = element.getJSONArray("bindings")
                        for (j in 0 until bindings.length()) {
                            val binding = bindings.getString(j)
                            if (binding.startsWith("GAMEPAD_")) {
                                hasGamepadBindings = true
                                break
                            }
                        }
                    }
                    if (hasGamepadBindings) break
                }
                if (!hasGamepadBindings) {
                    needsCopy = true
                }
            }
        }

        if (needsCopy) {
            val destJson = if (destFile.isFile()) {
                org.json.JSONObject(com.winlator.core.FileUtils.readString(destFile))
            } else {
                org.json.JSONObject().apply {
                    put("id", destProfile.id)
                    put("name", destProfile.name)
                    put("cursorSpeed", destProfile.cursorSpeed)
                }
            }

            destJson.put("elements", sourceElements)
            com.winlator.core.FileUtils.writeString(destFile, destJson.toString())
            Log.d("gncontrol", "Copied ${sourceElements.length()} elements")
        }
    } catch (e: Exception) {
        Log.e("gncontrol", "copyElements: Failed", e)
    }
}
