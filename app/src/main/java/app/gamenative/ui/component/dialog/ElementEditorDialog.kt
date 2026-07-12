package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileCopy
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
import app.gamenative.ui.util.SnackbarManager
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.component.settings.SettingsTextField
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsMenuLink
import com.alorma.compose.settings.ui.SettingsSwitch
import com.winlator.inputcontrols.ControlElement
import com.winlator.widget.InputControlsView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Normalise a [Binding]'s [toString] into the short label that appears on
 * on-screen control buttons. Keeping this in one place so the derivation in
 * [ElementEditorDialog] stays consistent with [ControlElement.getDisplayText].
 */
private fun bindingShortLabel(binding: com.winlator.inputcontrols.Binding?): String {
    if (binding == null) return ""
    return binding.toString()
        .replace("NUMPAD ", "NP")
        .replace("BUTTON ", "")
        .replace("SHOW KEYBOARD", "KEY")
}

/**
 * Compose-based element editor dialog matching the app's settings design pattern.
 *
 * Features:
 * - Full-width dialog at bottom of screen
 * - When adjusting size, dialog minimizes to show only slider controls
 * - Live preview of all changes on the actual control element
 * - Reset button returns size to 1.0x default
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ElementEditorDialog(
    element: ControlElement,
    view: InputControlsView,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    val context = LocalContext.current

    // Store original values for cancel/restore
    val originalScale by remember { mutableFloatStateOf(element.scale) }
    val originalText by remember { mutableStateOf(element.text) } // Keep null as null
    val originalType by remember { mutableStateOf(element.type) }
    val originalShape by remember { mutableStateOf(element.shape) }

    // Store original bindings for restore on cancel
    val originalBindings by remember {
        mutableStateOf(
            (0 until element.bindingCount).map { element.getBindingAt(it) }
        )
    }

    // Track if there are unsaved changes
    var hasUnsavedChanges by remember { mutableStateOf(false) }
    var showExitConfirmation by remember { mutableStateOf(false) }

    // Get the display text (either custom text or binding-based text)
    // Match the logic from ControlElement.getDisplayText()
    val initialDisplayText = remember(element) {
        val customText = element.text
        if (!customText.isNullOrEmpty()) {
            customText
        } else {
            // Show what's actually displayed (based on first binding)
            val binding = element.getBindingAt(0)
            if (binding != null && binding != com.winlator.inputcontrols.Binding.NONE) {
                var text = bindingShortLabel(binding)
                if (text.length > 7) {
                    // Abbreviate long binding names (e.g., "KEY A B" -> "KAB")
                    val parts = text.split(" ")
                    val sb = StringBuilder()
                    for (part in parts) {
                        if (part.isNotEmpty()) sb.append(part[0])
                    }
                    text = (if (binding.isMouse) "M" else "") + sb.toString()
                }
                text
            } else {
                ""
            }
        }
    }

    // Current editing values with live preview
    var currentScale by remember { mutableFloatStateOf(element.scale) }
    var currentText by remember { mutableStateOf(initialDisplayText) }
    var currentTextEdited by remember { mutableStateOf(false) }
    var showBindingsEditor by remember { mutableStateOf(false) }
    var bindingSlotToEdit by remember { mutableStateOf<Pair<Int, String>?>(null) }

    // Force recomposition of bindings section when bindings change
    var bindingsRefreshKey by remember { mutableIntStateOf(0) }

    // Track current dropdown selections
    var currentTypeIndex by remember { mutableIntStateOf(element.type.ordinal) }
    var currentShape by remember { mutableStateOf(element.shape) }
    // State for size adjustment mode
    var showSizeAdjuster by remember { mutableStateOf(false) }

    // Shooter mode settings state
    val movementTypeOptions = listOf("wasd", "arrow_keys", "gamepad_left_stick")
    val movementTypeLabels = listOf(
        stringResource(R.string.movement_wasd),
        stringResource(R.string.movement_arrow_keys),
        stringResource(R.string.movement_gamepad_left_stick)
    )
    val lookTypeOptions = listOf("mouse", "gamepad_right_stick")
    val lookTypeLabels = listOf(
        stringResource(R.string.look_type_mouse),
        stringResource(R.string.look_type_gamepad_right_stick)
    )
    var currentMovementTypeIndex by remember {
        mutableIntStateOf(movementTypeOptions.indexOf(element.shooterMovementType).coerceAtLeast(0))
    }
    var currentLookTypeIndex by remember {
        mutableIntStateOf(lookTypeOptions.indexOf(element.shooterLookType).coerceAtLeast(0))
    }
    var currentLookSensitivity by remember { mutableFloatStateOf(element.shooterLookSensitivity) }
    var currentJoystickSize by remember { mutableFloatStateOf(element.shooterJoystickSize) }
    // Store original shooter mode values for cancel/restore
    val originalMovementType by remember { mutableStateOf(element.shooterMovementType) }
    val originalLookType by remember { mutableStateOf(element.shooterLookType) }
    val originalLookSensitivity by remember { mutableFloatStateOf(element.shooterLookSensitivity) }
    val originalJoystickSize by remember { mutableFloatStateOf(element.shooterJoystickSize) }

    // Range button settings state
    val rangeTypes = listOf(
        ControlElement.Range.FROM_A_TO_Z,
        ControlElement.Range.FROM_0_TO_9,
        ControlElement.Range.FROM_F1_TO_F12
    )
    val rangeTypeLabels = listOf(
        stringResource(R.string.range_a_to_z),
        stringResource(R.string.range_1_to_0),
        stringResource(R.string.range_f1_to_f12)
    )
    var currentRangeTypeIndex by remember {
        mutableIntStateOf(rangeTypes.indexOf(element.range).coerceAtLeast(0))
    }
    var currentOrientation by remember {
        mutableIntStateOf(element.orientation.toInt())
    }
    var currentVisibleSegments by remember {
        mutableIntStateOf(element.bindingCount)
    }
    var currentScrollLocked by remember {
        mutableStateOf(element.isScrollLocked)
    }
    // Store original range button values for cancel/restore
    val originalRange by remember { mutableStateOf(element.range) }
    val originalOrientation by remember { mutableIntStateOf(element.orientation.toInt()) }
    val originalVisibleSegments by remember { mutableIntStateOf(element.bindingCount) }
    val originalScrollLocked by remember { mutableStateOf(element.isScrollLocked) }

    // Control appearance state
    var currentToggleSwitch by remember {
        mutableStateOf(element.isToggleSwitch)
    }
    val originalToggleSwitch by remember { mutableStateOf(element.isToggleSwitch) }
    var currentButtonColor by remember { mutableIntStateOf(element.buttonColor) }
    var currentButtonColorText by remember { mutableStateOf(rgbToHex(element.buttonColor)) }
    var currentButtonActiveColor by remember { mutableIntStateOf(element.buttonActiveColor) }
    var currentButtonActiveColorCustom by remember { mutableStateOf(element.hasCustomButtonActiveColor()) }
    var currentButtonActiveColorText by remember { mutableStateOf(rgbToHex(element.buttonActiveColor)) }
    var currentButtonOpacityInherited by remember { mutableStateOf(element.buttonOpacity < 0f) }
    var currentButtonOpacity by remember {
        mutableFloatStateOf(if (element.buttonOpacity >= 0f) element.buttonOpacity else view.overlayOpacity)
    }
    var currentButtonStrokeScale by remember { mutableFloatStateOf(element.buttonStrokeScale) }
    var currentShooterLookThrough by remember { mutableStateOf(element.isShooterLookThrough) }
    val originalControlAppearances by remember {
        mutableStateOf(
            view.profile?.elements
                ?.associateWith { ControlAppearance.capture(it) }
                .orEmpty()
        )
    }

    // Get types array for saving
    val types = remember { ControlElement.Type.values() }

    fun applyCurrentControlAppearance() {
        element.setButtonColor(currentButtonColor)
        element.setButtonActiveColor(currentButtonActiveColor, currentButtonActiveColorCustom)
        element.setButtonOpacity(
            if (currentButtonOpacityInherited) ControlElement.INHERIT_BUTTON_OPACITY else currentButtonOpacity
        )
        element.setButtonStrokeScale(currentButtonStrokeScale)
        element.setShooterLookThrough(currentShooterLookThrough)
    }

    fun currentAppearance() = ControlAppearance(
        scale = currentScale,
        color = currentButtonColor,
        activeColor = currentButtonActiveColor,
        activeColorCustom = currentButtonActiveColorCustom,
        opacity = if (currentButtonOpacityInherited) ControlElement.INHERIT_BUTTON_OPACITY else currentButtonOpacity,
        strokeScale = currentButtonStrokeScale,
        shooterLookThrough = currentShooterLookThrough
    )

    fun textForSave(): String? {
        if (!currentTextEdited) return originalText
        return currentText.ifEmpty { null }
    }

    fun saveChanges() {
        element.setScale(currentScale)
        element.setText(textForSave())

        val selectedType = types[currentTypeIndex]
        if (element.type != selectedType) {
            element.setTypeWithoutReset(selectedType)
        }
        element.setShape(currentShape)

        if (selectedType == ControlElement.Type.SHOOTER_MODE) {
            element.shooterMovementType = movementTypeOptions[currentMovementTypeIndex]
            element.shooterLookType = lookTypeOptions[currentLookTypeIndex]
            element.shooterLookSensitivity = currentLookSensitivity
            element.shooterJoystickSize = currentJoystickSize
        }

        if (selectedType == ControlElement.Type.RANGE_BUTTON) {
            element.setRange(rangeTypes[currentRangeTypeIndex])
            element.setOrientation(currentOrientation.toByte())
            element.setBindingCount(currentVisibleSegments)
            element.isScrollLocked = currentScrollLocked
        }

        if (selectedType == ControlElement.Type.BUTTON) {
            element.setToggleSwitch(currentToggleSwitch)
        }

        applyCurrentControlAppearance()
        view.profile?.save()
        view.invalidate()
        hasUnsavedChanges = false
    }

    // Apply changes to element for live preview
    LaunchedEffect(currentScale) {
        element.setScale(currentScale)
        view.invalidate()
    }

    LaunchedEffect(currentText, currentTextEdited) {
        if (currentTextEdited) {
            element.setText(currentText.ifEmpty { null })
            view.invalidate()
        }
    }

    LaunchedEffect(
        currentButtonColor,
        currentButtonActiveColor,
        currentButtonOpacity,
        currentButtonOpacityInherited,
        currentButtonStrokeScale,
        currentShooterLookThrough,
        currentTypeIndex
    ) {
        applyCurrentControlAppearance()
        view.invalidate()
    }

    LaunchedEffect(currentShape) {
        element.setShape(currentShape)
        view.invalidate()
    }

    Dialog(
        onDismissRequest = {
            if (hasUnsavedChanges) {
                showExitConfirmation = true
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        // Show either full settings dialog or minimized size adjuster
        if (showSizeAdjuster) {
            // Minimized size adjuster mode
            SizeAdjusterOverlay(
                element = element,
                view = view,
                currentScale = currentScale,
                onScaleChange = {
                    currentScale = it
                    hasUnsavedChanges = true
                },
                onConfirm = {
                    showSizeAdjuster = false
                },
                onCancel = {
                    currentScale = originalScale
                    element.setScale(originalScale)
                    view.invalidate()
                    showSizeAdjuster = false
                },
                onReset = {
                    currentScale = 1.0f
                    element.setScale(1.0f)
                    hasUnsavedChanges = true
                    view.invalidate()
                }
            )
        } else {
            // Full settings dialog
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.edit_element, element.type.name),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.element_position_size, element.x.toInt(), element.y.toInt(), currentScale),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (hasUnsavedChanges) {
                                showExitConfirmation = true
                            } else {
                                onDismiss()
                            }
                        }) {
                            Icon(Icons.Default.Close, null)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            saveChanges()
                            onSave()
                        }) {
                            Icon(Icons.Default.Save, null)
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                // Appearance Section
                SettingsGroup(title = { Text(stringResource(R.string.appearance)) }) {
                    // Text/Label - only for BUTTON type
                    if (element.type == ControlElement.Type.BUTTON) {
                        SettingsTextField(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.label_text)) },
                            subtitle = { Text(stringResource(R.string.label_text_subtitle)) },
                            value = currentText,
                            onValueChange = {
                                currentText = it
                                currentTextEdited = true
                                hasUnsavedChanges = true
                            },
                            action = {
                                // Reset button to restore original text
                                IconButton(onClick = {
                                    // Reset to initial display text (binding-based or original custom text)
                                    currentText = initialDisplayText
                                    currentTextEdited = false
                                    element.setText(originalText)
                                    hasUnsavedChanges = true
                                    view.invalidate()
                                }) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        )
                    }

                    // Element Type
                    val types = ControlElement.Type.values()
                    val typeNames = types.map {
                        if (it == ControlElement.Type.SHOOTER_MODE) "DYNAMIC JOYSTICKS"
                        else it.name.replace("_", " ")
                    }
                    SettingsListDropdown(
                        colors = settingsTileColors(),
                        title = { Text(stringResource(R.string.element_type)) },
                        subtitle = { Text(stringResource(R.string.element_type_subtitle)) },
                        value = currentTypeIndex,
                        items = typeNames,
                        onItemSelected = { index ->
                            val newType = types[index]
                            currentTypeIndex = index
                            currentShape = when (newType) {
                                ControlElement.Type.STICK,
                                ControlElement.Type.SHOOTER_MODE -> ControlElement.Shape.CIRCLE
                                ControlElement.Type.TRACKPAD,
                                ControlElement.Type.RANGE_BUTTON -> ControlElement.Shape.ROUND_RECT
                                else -> currentShape
                            }
                            element.setTypeWithoutReset(newType)

                            // Mark as having unsaved changes
                            hasUnsavedChanges = true

                            // Force UI refresh
                            bindingsRefreshKey++
                            view.invalidate()
                        }
                    )

                    // Element Shape (with restrictions)
                    // STICK, TRACKPAD, RANGE_BUTTON have fixed rendering shapes
                    // D_PAD uses custom cross-shaped path and doesn't respect shape
                    val selectedType = types[currentTypeIndex]
                    val availableShapes = when (selectedType) {
                        ControlElement.Type.STICK -> {
                            // Stick is always rendered as CIRCLE
                            listOf(ControlElement.Shape.CIRCLE)
                        }
                        ControlElement.Type.TRACKPAD,
                        ControlElement.Type.RANGE_BUTTON -> {
                            // Trackpad and Range Button are always rendered as ROUND_RECT
                            listOf(ControlElement.Shape.ROUND_RECT)
                        }
                        ControlElement.Type.D_PAD -> {
                            // D-Pad uses custom cross-shaped path, shape doesn't affect rendering
                            // Don't allow changing shape
                            listOf(currentShape)
                        }
                        ControlElement.Type.SHOOTER_MODE -> {
                            // Shooter Mode is always rendered as CIRCLE
                            listOf(ControlElement.Shape.CIRCLE)
                        }
                        ControlElement.Type.BUTTON -> {
                            // Buttons fully support all shapes
                            ControlElement.Shape.values().toList()
                        }
                    }

                    if (availableShapes.size > 1) {
                        val shapeNames = availableShapes.map { it.name.replace("_", " ") }
                        val currentShapeIndexInList = availableShapes.indexOf(currentShape).coerceAtLeast(0)
                        SettingsListDropdown(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.shape)) },
                            subtitle = { Text(stringResource(R.string.shape_subtitle)) },
                            value = currentShapeIndexInList,
                            items = shapeNames,
                            onItemSelected = { index ->
                                currentShape = availableShapes[index]
                                hasUnsavedChanges = true
                            }
                        )
                    } else if (availableShapes.size == 1 && selectedType != ControlElement.Type.D_PAD) {
                        // Show info for restricted types (but not D-PAD since it's obvious)
                        SettingsMenuLink(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.shape)) },
                            subtitle = { Text(stringResource(R.string.shape_restricted, selectedType.name, availableShapes[0].name.replace("_", " "))) },
                            enabled = false,
                            onClick = {}
                        )
                    }
                }

                // Bindings Section
                // Use key() with bindingsRefreshKey to force recomposition when bindings change
                key(bindingsRefreshKey) {
                    SettingsGroup(title = { Text(stringResource(R.string.bindings)) }) {
                        // Quick Presets for directional controls (D-Pad and Stick only)
                        if (element.type == ControlElement.Type.D_PAD || element.type == ControlElement.Type.STICK) {
                            VirtualControlPresets(
                                element = element,
                                view = view,
                                onPresetsApplied = {
                                    // Mark as having unsaved changes
                                    hasUnsavedChanges = true
                                    // Force UI refresh after presets are applied
                                    bindingsRefreshKey++
                                }
                            )
                        }

                        if (element.type == ControlElement.Type.RANGE_BUTTON) {
                            SettingsMenuLink(
                                colors = settingsTileColors(),
                                title = { Text(stringResource(R.string.bindings_auto_generated)) },
                                subtitle = { Text(stringResource(R.string.bindings_auto_generated_subtitle)) },
                                enabled = false,
                                onClick = {}
                            )
                        } else if (element.type == ControlElement.Type.SHOOTER_MODE) {
                            // Bindings auto-generated for these types
                            SettingsMenuLink(
                                colors = settingsTileColors(),
                                title = { Text(stringResource(R.string.bindings_auto_generated)) },
                                subtitle = { Text(stringResource(R.string.bindings_auto_generated_subtitle)) },
                                enabled = false,
                                onClick = {}
                            )
                        } else {
                            val bindingCount = when (element.type) {
                                ControlElement.Type.BUTTON -> 2
                                else -> 4
                            }

                            for (i in 0 until bindingCount) {
                                val binding = element.getBindingAt(i)
                                val bindingName = binding?.toString() ?: "NONE"

                                val slotLabel = when (element.type) {
                                    ControlElement.Type.BUTTON -> {
                                        stringResource(if (i == 0) R.string.primary_action else R.string.secondary_action)
                                    }
                                    ControlElement.Type.D_PAD,
                                    ControlElement.Type.STICK -> {
                                        val directionResId = when (i) {
                                            0 -> R.string.direction_up
                                            1 -> R.string.direction_right
                                            2 -> R.string.direction_down
                                            else -> R.string.direction_left
                                        }
                                        stringResource(directionResId)
                                    }
                                    ControlElement.Type.TRACKPAD -> {
                                        val directionResId = when (i) {
                                            0 -> R.string.direction_up
                                            1 -> R.string.direction_right
                                            2 -> R.string.direction_down
                                            else -> R.string.direction_left
                                        }
                                        stringResource(directionResId) + stringResource(R.string.mouse_suffix)
                                    }
                                    else -> stringResource(R.string.slot_number, i + 1)
                                }

                                SettingsMenuLink(
                                    colors = settingsTileColors(),
                                    title = { Text(slotLabel) },
                                    subtitle = { Text(bindingName) },
                                    onClick = {
                                        bindingSlotToEdit = Pair(i, slotLabel)
                                    }
                                )
                            }

                            // Helper text for buttons
                            if (element.type == ControlElement.Type.BUTTON) {
                                Text(
                                    text = stringResource(R.string.button_slots_help),
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }
                }

                // Range Button Settings Section (only for RANGE_BUTTON type)
                if (types[currentTypeIndex] == ControlElement.Type.RANGE_BUTTON) {
                    SettingsGroup(title = { Text(stringResource(R.string.range_button_settings)) }) {
                        // Key Range dropdown
                        SettingsListDropdown(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.range_type)) },
                            subtitle = { Text(stringResource(R.string.range_type_subtitle)) },
                            value = currentRangeTypeIndex,
                            items = rangeTypeLabels,
                            onItemSelected = { index ->
                                currentRangeTypeIndex = index
                                element.setRange(rangeTypes[index])
                                // Clamp visible segments to the new range's max
                                val newMax = rangeTypes[index].max.toInt()
                                if (currentVisibleSegments > newMax) {
                                    currentVisibleSegments = newMax
                                    element.setBindingCount(newMax)
                                }
                                hasUnsavedChanges = true
                                view.invalidate()
                            }
                        )

                        // Orientation dropdown
                        val orientationLabels = listOf(
                            stringResource(R.string.range_orientation_horizontal),
                            stringResource(R.string.range_orientation_vertical)
                        )
                        SettingsListDropdown(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.range_orientation)) },
                            subtitle = { Text(stringResource(R.string.range_orientation_subtitle)) },
                            value = currentOrientation,
                            items = orientationLabels,
                            onItemSelected = { index ->
                                currentOrientation = index
                                element.setOrientation(index.toByte())
                                hasUnsavedChanges = true
                                view.invalidate()
                            }
                        )

                        // Visible Segments slider
                        val maxSegments = rangeTypes[currentRangeTypeIndex].max.toInt()
                        SettingsMenuLink(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.range_visible_segments)) },
                            subtitle = { Text(stringResource(R.string.range_visible_segments_subtitle, currentVisibleSegments)) },
                            onClick = {}
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Slider(
                                value = currentVisibleSegments.toFloat(),
                                onValueChange = {
                                    val newCount = it.roundToInt().coerceIn(1, maxSegments)
                                    currentVisibleSegments = newCount
                                    element.setBindingCount(newCount)
                                    hasUnsavedChanges = true
                                    view.invalidate()
                                },
                                valueRange = 1f..maxSegments.toFloat(),
                                steps = (maxSegments - 2).coerceAtLeast(0),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "$currentVisibleSegments",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Lock Scrolling switch
                        SettingsSwitch(
                            colors = settingsTileColorsAlt(),
                            title = { Text(stringResource(R.string.range_lock_scrolling)) },
                            subtitle = { Text(stringResource(R.string.range_lock_scrolling_subtitle)) },
                            state = currentScrollLocked,
                            onCheckedChange = {
                                currentScrollLocked = it
                                element.isScrollLocked = it
                                hasUnsavedChanges = true
                            },
                        )
                    }
                }

                // Shooter Mode Settings Section (only for SHOOTER_MODE type)
                if (types[currentTypeIndex] == ControlElement.Type.SHOOTER_MODE) {
                    SettingsGroup(title = { Text(stringResource(R.string.shooter_mode_settings)) }) {
                        // Movement Type dropdown
                        SettingsListDropdown(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.movement_type)) },
                            subtitle = { Text(stringResource(R.string.movement_type_subtitle)) },
                            value = currentMovementTypeIndex,
                            items = movementTypeLabels,
                            onItemSelected = { index ->
                                currentMovementTypeIndex = index
                                hasUnsavedChanges = true
                            }
                        )

                        // Look Type dropdown
                        SettingsListDropdown(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.look_type)) },
                            subtitle = { Text(stringResource(R.string.look_type_subtitle)) },
                            value = currentLookTypeIndex,
                            items = lookTypeLabels,
                            onItemSelected = { index ->
                                currentLookTypeIndex = index
                                hasUnsavedChanges = true
                            }
                        )

                        // Look Sensitivity slider
                        SettingsMenuLink(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.look_sensitivity)) },
                            subtitle = { Text(stringResource(R.string.look_sensitivity_subtitle)) },
                            onClick = {}
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Slider(
                                value = currentLookSensitivity,
                                onValueChange = {
                                    currentLookSensitivity = it
                                    hasUnsavedChanges = true
                                },
                                valueRange = 0.1f..10.0f,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = String.format(Locale.US, "%.1fx", currentLookSensitivity),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Joystick Size slider
                        SettingsMenuLink(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.joystick_size)) },
                            subtitle = { Text(stringResource(R.string.joystick_size_subtitle)) },
                            onClick = {}
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Slider(
                                value = currentJoystickSize,
                                onValueChange = {
                                    currentJoystickSize = it
                                    hasUnsavedChanges = true
                                },
                                valueRange = 0.5f..3.0f,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = String.format(Locale.US, "%.1fx", currentJoystickSize),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Control Appearance Section
                SettingsGroup(title = { Text(stringResource(R.string.control_appearance)) }) {
                    ControlAppearancePreview(
                        type = types[currentTypeIndex],
                        text = currentText.ifEmpty { bindingShortLabel(element.getBindingAt(0)).ifEmpty { "A" } },
                        shape = currentShape,
                        orientation = currentOrientation,
                        segmentCount = currentVisibleSegments,
                        color = currentButtonColor,
                        activeColor = currentButtonActiveColor,
                        opacity = currentButtonOpacity,
                        strokeScale = currentButtonStrokeScale
                    )

                    ControlColorField(
                        title = stringResource(R.string.button_color),
                        subtitle = stringResource(R.string.button_color_subtitle),
                        value = currentButtonColorText,
                        selectedColor = currentButtonColor,
                        onValueChange = { value ->
                            currentButtonColorText = value.uppercase(Locale.US)
                            parseRgbHex(value)?.let {
                                currentButtonColor = it
                                hasUnsavedChanges = true
                            }
                        },
                        onPresetSelected = {
                            currentButtonColor = it
                            currentButtonColorText = rgbToHex(it)
                            hasUnsavedChanges = true
                        }
                    )

                    ControlColorField(
                        title = stringResource(R.string.button_active_color),
                        subtitle = stringResource(R.string.button_active_color_subtitle),
                        value = currentButtonActiveColorText,
                        selectedColor = currentButtonActiveColor,
                        onValueChange = { value ->
                            currentButtonActiveColorText = value.uppercase(Locale.US)
                            parseRgbHex(value)?.let {
                                currentButtonActiveColor = it
                                currentButtonActiveColorCustom = true
                                hasUnsavedChanges = true
                            }
                        },
                        onPresetSelected = {
                            currentButtonActiveColor = it
                            currentButtonActiveColorCustom = true
                            currentButtonActiveColorText = rgbToHex(it)
                            hasUnsavedChanges = true
                        }
                    )

                    SettingsMenuLink(
                        colors = settingsTileColors(),
                        title = { Text(stringResource(R.string.button_opacity)) },
                        subtitle = {
                            Text(
                                if (currentButtonOpacityInherited) {
                                    stringResource(R.string.button_opacity_inherited, (currentButtonOpacity * 100).roundToInt())
                                } else {
                                    stringResource(R.string.button_opacity_value, (currentButtonOpacity * 100).roundToInt())
                                }
                            )
                        },
                        onClick = {}
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Slider(
                            value = currentButtonOpacity,
                            onValueChange = {
                                currentButtonOpacity = it
                                currentButtonOpacityInherited = false
                                hasUnsavedChanges = true
                            },
                            valueRange = 0.1f..1.0f,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${(currentButtonOpacity * 100).roundToInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    SettingsMenuLink(
                        colors = settingsTileColors(),
                        title = { Text(stringResource(R.string.size)) },
                        subtitle = { Text(stringResource(R.string.size_subtitle, currentScale)) },
                        onClick = {
                            showSizeAdjuster = true
                        }
                    )

                    val strokeWidthItems = listOf(
                        stringResource(R.string.control_stroke_width_thin),
                        stringResource(R.string.control_stroke_width_default),
                        stringResource(R.string.control_stroke_width_thick),
                        stringResource(R.string.control_stroke_width_extra_thick)
                    )
                    val strokeWidthIndex = closestStrokeWidthIndex(currentButtonStrokeScale)
                    SettingsListDropdown(
                        colors = settingsTileColors(),
                        title = { Text(stringResource(R.string.control_stroke_width)) },
                        subtitle = { Text(stringResource(R.string.control_stroke_width_subtitle)) },
                        value = strokeWidthIndex,
                        items = strokeWidthItems,
                        onItemSelected = { index ->
                            currentButtonStrokeScale = controlStrokeWidthScales[index]
                            hasUnsavedChanges = true
                        }
                    )

                    if (types[currentTypeIndex] == ControlElement.Type.BUTTON) {
                        SettingsSwitch(
                            colors = settingsTileColorsAlt(),
                            title = { Text(stringResource(R.string.button_toggleable)) },
                            subtitle = { Text(stringResource(R.string.button_toggleable_subtitle)) },
                            state = currentToggleSwitch,
                            onCheckedChange = {
                                currentToggleSwitch = it
                                element.setToggleSwitch(it)
                                hasUnsavedChanges = true
                            },
                        )

                        SettingsSwitch(
                            colors = settingsTileColorsAlt(),
                            title = { Text(stringResource(R.string.button_shooter_look_through)) },
                            subtitle = { Text(stringResource(R.string.button_shooter_look_through_subtitle)) },
                            state = currentShooterLookThrough,
                            onCheckedChange = {
                                currentShooterLookThrough = it
                                hasUnsavedChanges = true
                            },
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                showCopyAppearanceDialog(context, element, view) { appearance ->
                                    currentScale = appearance.scale
                                    currentButtonColor = appearance.color
                                    currentButtonColorText = rgbToHex(appearance.color)
                                    currentButtonActiveColor = appearance.activeColor
                                    currentButtonActiveColorCustom = appearance.activeColorCustom
                                    currentButtonActiveColorText = rgbToHex(appearance.activeColor)
                                    currentButtonOpacityInherited = appearance.opacity < 0f
                                    currentButtonOpacity = if (appearance.opacity >= 0f) appearance.opacity else view.overlayOpacity
                                    currentButtonStrokeScale = appearance.strokeScale
                                    currentShooterLookThrough = appearance.shooterLookThrough
                                    hasUnsavedChanges = true
                                }
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                        ) {
                            Text(stringResource(R.string.copy), style = MaterialTheme.typography.labelSmall)
                        }

                        OutlinedButton(
                            onClick = {
                                val appearance = currentAppearance()
                                view.profile?.elements
                                    ?.forEach { appearance.applyTo(it) }
                                view.invalidate()
                                hasUnsavedChanges = true
                                SnackbarManager.show(context.getString(R.string.toast_applied_button_appearance))
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                        ) {
                            Text(stringResource(R.string.apply_all), style = MaterialTheme.typography.labelSmall)
                        }

                        OutlinedButton(
                            onClick = {
                                currentButtonColor = ControlElement.DEFAULT_BUTTON_COLOR
                                currentButtonColorText = rgbToHex(ControlElement.DEFAULT_BUTTON_COLOR)
                                currentButtonActiveColor = ControlElement.DEFAULT_BUTTON_ACTIVE_COLOR
                                currentButtonActiveColorCustom = false
                                currentButtonActiveColorText = rgbToHex(ControlElement.DEFAULT_BUTTON_ACTIVE_COLOR)
                                currentButtonOpacityInherited = true
                                currentButtonOpacity = view.overlayOpacity
                                currentScale = 1.0f
                                element.setScale(1.0f)
                                currentButtonStrokeScale = ControlElement.DEFAULT_BUTTON_STROKE_SCALE
                                currentShooterLookThrough = true
                                view.invalidate()
                                hasUnsavedChanges = true
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                        ) {
                            Text(stringResource(R.string.reset), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                // Properties Section
                SettingsGroup(title = { Text(stringResource(R.string.properties)) }) {
                    SettingsMenuLink(
                        colors = settingsTileColors(),
                        title = { Text(stringResource(R.string.position)) },
                        subtitle = { Text(stringResource(R.string.position_value, element.x, element.y)) },
                        enabled = false,
                        onClick = {}
                    )
                }
            }
        }
        }
    }

    // Show binding selector dialog
    bindingSlotToEdit?.let { (slotIndex, slotLabel) ->
        val currentBinding = element.getBindingAt(slotIndex)

        ControllerBindingDialog(
            buttonName = slotLabel,
            currentBinding = currentBinding,
            onDismiss = { bindingSlotToEdit = null },
            onBindingSelected = { binding ->

                // Update binding in memory only (not saved to disk yet)
                if (binding != null) {
                    element.setBindingAt(slotIndex, binding)
                } else {
                    element.setBindingAt(slotIndex, com.winlator.inputcontrols.Binding.NONE)
                }

                // If this is a button and slot 0 (primary), update label text to match new binding
                // (ControlElement.getDisplayText() returns custom text if set, otherwise binding text)
                if (element.type == ControlElement.Type.BUTTON && slotIndex == 0) {
                    // Check if custom text is empty or same as old binding text
                    val customText = element.text
                    if (customText.isNullOrEmpty() || customText == bindingShortLabel(currentBinding)) {
                        // Clear custom text so new binding text will show
                        element.setText(null)
                        currentTextEdited = false

                        // Update currentText state to show what will actually be displayed (new binding text)
                        val newBindingText = bindingShortLabel(binding)
                        currentText = if (newBindingText.length > 7) {
                            // Abbreviate long names to match getDisplayText() logic
                            val parts = newBindingText.split(" ")
                            val sb = StringBuilder()
                            for (part in parts) {
                                if (part.isNotEmpty()) sb.append(part[0])
                            }
                            (if (binding?.isMouse() == true) "M" else "") + sb.toString()
                        } else {
                            newBindingText
                        }
                    }
                }

                // Mark as having unsaved changes
                hasUnsavedChanges = true

                // Update canvas to show new binding immediately
                view.invalidate()

                // Close binding selector
                bindingSlotToEdit = null

                // Force UI refresh to show updated binding in list
                bindingsRefreshKey++
            }
        )
    }

    // Show exit confirmation dialog if there are unsaved changes
    if (showExitConfirmation) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text(stringResource(R.string.unsaved_changes)) },
            text = { Text(stringResource(R.string.unsaved_changes_message)) },
            confirmButton = {
                TextButton(onClick = {
                    saveChanges()
                    showExitConfirmation = false
                    onDismiss()
                }) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    // Discard changes and close
                    element.setScale(originalScale)
                    element.setTypeWithoutReset(originalType)
                    element.shape = originalShape
                    // Restore original range button properties before bindings; setBindingCount resets bindings.
                    if (originalType == ControlElement.Type.RANGE_BUTTON) {
                        element.setRange(originalRange)
                        element.setOrientation(originalOrientation.toByte())
                        element.setBindingCount(originalVisibleSegments)
                        element.isScrollLocked = originalScrollLocked
                    } else if (element.bindingCount != originalBindings.size) {
                        element.setBindingCount(originalBindings.size)
                    }
                    // Restore original bindings
                    originalBindings.forEachIndexed { index, binding ->
                        if (binding != null) {
                            element.setBindingAt(index, binding)
                        }
                    }
                    // Restore original shooter mode properties
                    element.shooterMovementType = originalMovementType
                    element.shooterLookType = originalLookType
                    element.shooterLookSensitivity = originalLookSensitivity
                    element.shooterJoystickSize = originalJoystickSize
                    // Restore original button properties
                    element.setToggleSwitch(originalToggleSwitch)
                    element.setText(originalText)
                    originalControlAppearances.forEach { (control, appearance) ->
                        appearance.applyTo(control)
                    }
                    view.invalidate()
                    showExitConfirmation = false
                    onDismiss()
                }) {
                    Text(stringResource(R.string.discard))
                }
            }
        )
    }
}

/**
 * Floating size adjuster overlay - appears on top of the controls view
 * with a slider and action buttons positioned away from the control element.
 * Automatically positions itself at top or bottom based on element location.
 */
@Composable
private fun SizeAdjusterOverlay(
    element: ControlElement,
    view: InputControlsView,
    currentScale: Float,
    onScaleChange: (Float) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit
) {
    // Determine if element is in top or bottom half of screen
    // Coordinates are in actual screen pixels (not normalized)
    // Y=0 is at TOP, Y increases downward (standard Android Canvas)
    val elementY = element.y
    val screenHeight = view.height.toFloat()

    // Use 60% threshold: if Y < 60% of screen height, element is in top portion, show slider at bottom
    // Otherwise element is in bottom 40%, show slider at top
    val isElementInTopPortion = elementY < (screenHeight * 0.6f)

    // Full screen transparent overlay
    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        // Position slider opposite to element location - more compact and transparent
        Surface(
            modifier = Modifier
                .wrapContentHeight()
                .widthIn(max = 400.dp)
                .align(if (isElementInTopPortion) Alignment.BottomCenter else Alignment.TopCenter)
                .padding(16.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Title and current scale
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.adjust_size),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = String.format(Locale.US, "%.2fx", currentScale),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    )
                }

                // Slider - more compact
                Slider(
                    value = currentScale,
                    onValueChange = onScaleChange,
                    valueRange = 0.1f..5.0f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                )

                // All 4 buttons in one row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Copy Size button
                    OutlinedButton(
                        onClick = {
                            val context = view.context
                            showCopySizeDialog(context, element, view) { newScale ->
                                onScaleChange(newScale)
                            }
                        },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileCopy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(stringResource(R.string.copy), style = MaterialTheme.typography.labelSmall)
                    }

                    // Reset button
                    OutlinedButton(
                        onClick = onReset,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Text(stringResource(R.string.reset), style = MaterialTheme.typography.labelSmall)
                    }

                    // Cancel button
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelSmall)
                    }

                    // Confirm button
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(stringResource(R.string.done), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * Show dialog to copy size from another element
 */
private fun showCopySizeDialog(
    context: android.content.Context,
    currentElement: ControlElement,
    view: InputControlsView,
    onSizeCopied: (Float) -> Unit
) {
    val profile = view.profile ?: return
    val elements = profile.getElements()

    // Filter out the current element and create display list
    val otherElements = elements.filter { it != currentElement }

    if (otherElements.isEmpty()) {
        SnackbarManager.show(context.getString(R.string.toast_no_elements_to_copy))
        return
    }

    // Create display items showing element info with better formatting
    val elementNames = otherElements.map { element ->
        val typeStr = element.type.name.replace("_", " ")
        val scaleStr = String.format(Locale.US, "%.2fx", element.scale)

        // Get display text/binding
        val label = if (!element.text.isNullOrEmpty()) {
            element.text
        } else {
            val binding = element.getBindingAt(0)
            if (binding != null && binding != com.winlator.inputcontrols.Binding.NONE) {
                binding.toString().take(15)
            } else {
                context.getString(R.string.binding_none)
            }
        }

        // Format: "BUTTON • 1.50x • Space"
        "$typeStr • $scaleStr • $label"
    }.toTypedArray()

    android.app.AlertDialog.Builder(context)
        .setTitle(context.getString(R.string.copy_size_from_element))
        .setItems(elementNames) { _, which ->
            val selectedElement = otherElements[which]
            onSizeCopied(selectedElement.scale)
            SnackbarManager.show(context.getString(R.string.toast_copied_size, selectedElement.scale))
        }
        .setNegativeButton(context.getString(R.string.cancel), null)
        .show()
}

/**
 * Quick preset buttons for virtual control bindings
 */
@Composable
private fun VirtualControlPresets(
    element: ControlElement,
    view: InputControlsView,
    onPresetsApplied: () -> Unit = {}
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val context = LocalContext.current
            Text(
                text = stringResource(R.string.quick_presets),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            // Keyboard layouts
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        applyVirtualPreset(element, VirtualPresetType.WASD, view)
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_wasd), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyVirtualPreset(element, VirtualPresetType.ARROW_KEYS, view)
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_arrows), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyVirtualPreset(element, VirtualPresetType.MOUSE_MOVE, view)
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_mouse), style = MaterialTheme.typography.labelSmall)
                }
            }

            // Gamepad modes
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        applyVirtualPreset(element, VirtualPresetType.DPAD, view)
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_dpad), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyVirtualPreset(element, VirtualPresetType.LEFT_STICK, view)
                        onPresetsApplied()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
                ) {
                    Text(stringResource(R.string.preset_left_stick), style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(
                    onClick = {
                        applyVirtualPreset(element, VirtualPresetType.RIGHT_STICK, view)
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
 * Preset types for virtual control bindings
 */
private enum class VirtualPresetType {
    WASD, ARROW_KEYS, MOUSE_MOVE, DPAD, LEFT_STICK, RIGHT_STICK
}

/**
 * Apply a preset binding to a virtual control element
 */
private fun applyVirtualPreset(
    element: ControlElement,
    presetType: VirtualPresetType,
    view: InputControlsView
) {
    // Define bindings for each preset (Up, Right, Down, Left order)
    val bindings = when (presetType) {
        VirtualPresetType.WASD -> listOf(
            com.winlator.inputcontrols.Binding.KEY_W,
            com.winlator.inputcontrols.Binding.KEY_D,
            com.winlator.inputcontrols.Binding.KEY_S,
            com.winlator.inputcontrols.Binding.KEY_A
        )
        VirtualPresetType.ARROW_KEYS -> listOf(
            com.winlator.inputcontrols.Binding.KEY_UP,
            com.winlator.inputcontrols.Binding.KEY_RIGHT,
            com.winlator.inputcontrols.Binding.KEY_DOWN,
            com.winlator.inputcontrols.Binding.KEY_LEFT
        )
        VirtualPresetType.MOUSE_MOVE -> listOf(
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_UP,
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_RIGHT,
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_DOWN,
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_LEFT
        )
        VirtualPresetType.DPAD -> listOf(
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_UP,
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_RIGHT,
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_DOWN,
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_LEFT
        )
        VirtualPresetType.LEFT_STICK -> listOf(
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_UP,
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_RIGHT,
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_DOWN,
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_LEFT
        )
        VirtualPresetType.RIGHT_STICK -> listOf(
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_UP,
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_RIGHT,
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_DOWN,
            com.winlator.inputcontrols.Binding.GAMEPAD_RIGHT_THUMB_LEFT
        )
    }

    // Apply bindings to element in memory only (not saved to disk yet)
    bindings.forEachIndexed { index, binding ->
        element.setBindingAt(index, binding)
    }

    // Update canvas to show new bindings immediately
    view.invalidate()
}

/**
 * Get default bindings for an element type
 * Returns array of 4 bindings (Up, Right, Down, Left order for directional types)
 */
private fun getDefaultBindingsForType(type: ControlElement.Type): List<com.winlator.inputcontrols.Binding> {
    return when (type) {
        ControlElement.Type.D_PAD -> listOf(
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_UP,
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_RIGHT,
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_DOWN,
            com.winlator.inputcontrols.Binding.GAMEPAD_DPAD_LEFT
        )
        ControlElement.Type.STICK -> listOf(
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_UP,
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_RIGHT,
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_DOWN,
            com.winlator.inputcontrols.Binding.GAMEPAD_LEFT_THUMB_LEFT
        )
        ControlElement.Type.TRACKPAD -> listOf(
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_UP,
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_RIGHT,
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_DOWN,
            com.winlator.inputcontrols.Binding.MOUSE_MOVE_LEFT
        )
        // BUTTON and RANGE_BUTTON get no defaults
        else -> listOf(
            com.winlator.inputcontrols.Binding.NONE,
            com.winlator.inputcontrols.Binding.NONE,
            com.winlator.inputcontrols.Binding.NONE,
            com.winlator.inputcontrols.Binding.NONE
        )
    }
}
