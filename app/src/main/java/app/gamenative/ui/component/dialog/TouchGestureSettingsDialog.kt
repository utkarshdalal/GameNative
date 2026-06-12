package app.gamenative.ui.component.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import app.gamenative.ui.component.NoExtractOutlinedTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.data.TouchGestureConfig
import app.gamenative.data.TouchGestureConfig.Companion.ACTION_LEFT_CLICK
import app.gamenative.data.TouchGestureConfig.Companion.ACTION_MIDDLE_CLICK
import app.gamenative.data.TouchGestureConfig.Companion.ACTION_RIGHT_CLICK
import app.gamenative.data.TouchGestureConfig.Companion.ACTION_SHOW_KEYBOARD
import app.gamenative.data.TouchGestureConfig.Companion.MOUSE_BEHAVIOR_CLICK
import app.gamenative.data.TouchGestureConfig.Companion.MOUSE_BEHAVIOR_HOLD
import app.gamenative.data.TouchGestureConfig.Companion.MOUSE_DRAG_MOVEMENT_DIRECT
import app.gamenative.data.TouchGestureConfig.Companion.MOUSE_DRAG_MOVEMENT_MODES
import app.gamenative.data.TouchGestureConfig.Companion.MOUSE_DRAG_MOVEMENT_RELATIVE
import app.gamenative.data.TouchGestureConfig.Companion.PAN_ACTIONS
import app.gamenative.data.TouchGestureConfig.Companion.PAN_ARROW_KEYS
import app.gamenative.data.TouchGestureConfig.Companion.PAN_LEFT_CLICK_DRAG
import app.gamenative.data.TouchGestureConfig.Companion.PAN_MIDDLE_MOUSE
import app.gamenative.data.TouchGestureConfig.Companion.PAN_RIGHT_CLICK_DRAG
import app.gamenative.data.TouchGestureConfig.Companion.PAN_WASD
import app.gamenative.data.TouchGestureConfig.Companion.ZOOM_ACTIONS
import app.gamenative.data.TouchGestureConfig.Companion.ZOOM_PAGE_UP_DOWN
import app.gamenative.data.TouchGestureConfig.Companion.ZOOM_PLUS_MINUS
import app.gamenative.data.TouchGestureConfig.Companion.ZOOM_SCROLL_WHEEL
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
import app.gamenative.ui.theme.PluviaBackground
import app.gamenative.ui.theme.PluviaBorder
import app.gamenative.ui.theme.PluviaSurface
import app.gamenative.ui.theme.PluviaSurfaceElevated
import com.alorma.compose.settings.ui.SettingsSwitch

/**
 * Full-screen dialog for configuring per-game touch gesture settings.
 *
 * @param gestureConfig  The current [TouchGestureConfig] to display / edit.
 * @param onDismiss      Called when the user cancels (back button or X).
 * @param onSave         Called with the updated [TouchGestureConfig] when the user taps "Save".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TouchGestureSettingsDialog(
    gestureConfig: TouchGestureConfig,
    onDismiss: () -> Unit,
    onSave: (TouchGestureConfig) -> Unit,
) {
    var config by remember { mutableStateOf(gestureConfig) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = PluviaBackground,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.gesture_settings_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                        }
                    },
                    actions = {
                        IconButton(onClick = { onSave(config) }) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.save))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                // ═══════════════════════════════════════════════════════════
                // ── Single Finger ──────────────────────────────────────────
                // ═══════════════════════════════════════════════════════════
                Text(
                    text = stringResource(R.string.gesture_section_single_finger),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp),
                )

                // ── Tap (customisable action) ───────────────────────────
                GestureRow(
                    title = stringResource(R.string.gesture_tap),
                    subtitle = tapHoldActionLabel(config.tapAction),
                    enabled = config.tapEnabled,
                    onEnabledChange = { config = config.copy(tapEnabled = it) },
                ) {
                    TapHoldActionPicker(
                        currentAction = config.tapAction,
                        onActionSelected = { config = config.copy(tapAction = it) },
                    )
                }

                GestureRow(
                    title = stringResource(R.string.gesture_hold_mouse_button_while_touching),
                    subtitle = tapHoldActionLabel(config.holdMouseButtonWhileTouchingAction),
                    enabled = config.holdMouseButtonWhileTouchingEnabled,
                    onEnabledChange = {
                        config = config.copy(holdMouseButtonWhileTouchingEnabled = it)
                    },
                ) {
                    MouseButtonActionPicker(
                        currentAction = config.holdMouseButtonWhileTouchingAction,
                        onActionSelected = {
                            config = config.copy(holdMouseButtonWhileTouchingAction = it)
                        },
                    )
                }

                // ── Double-Tap (fixed action, customisable delay) ────────
                GestureRow(
                    title = stringResource(R.string.gesture_double_tap),
                    subtitle = stringResource(R.string.gesture_double_tap_subtitle),
                    enabled = config.doubleTapEnabled,
                    onEnabledChange = { config = config.copy(doubleTapEnabled = it) },
                ) {
                    DelayTextField(
                        label = stringResource(R.string.gesture_double_tap_delay),
                        value = config.doubleTapDelay,
                        onValueChange = { config = config.copy(doubleTapDelay = it) },
                    )
                }

                // ── Long Press (customisable action + delay) ─────────────
                GestureRow(
                    title = stringResource(R.string.gesture_long_press),
                    subtitle = holdGestureSubtitle(config.longPressAction, config.longPressMouseBehavior),
                    enabled = config.longPressEnabled,
                    onEnabledChange = { config = config.copy(longPressEnabled = it) },
                ) {
                    TapHoldActionPicker(
                        currentAction = config.longPressAction,
                        onActionSelected = { config = config.copy(longPressAction = it) },
                    )
                    MouseHoldBehaviorPicker(
                        action = config.longPressAction,
                        currentBehavior = config.longPressMouseBehavior,
                        onBehaviorSelected = { config = config.copy(longPressMouseBehavior = it) },
                    )
                    DelayTextField(
                        label = stringResource(R.string.gesture_long_press_delay),
                        value = config.longPressDelay,
                        onValueChange = { config = config.copy(longPressDelay = it) },
                    )
                }

                // ── Drag (customisable action) ──────────────────────────
                GestureRow(
                    title = stringResource(R.string.gesture_drag),
                    subtitle = panActionLabel(config.dragAction),
                    enabled = config.dragEnabled,
                    onEnabledChange = { config = config.copy(dragEnabled = it) },
                ) {
                    PanActionPicker(
                        currentAction = config.dragAction,
                        onActionSelected = { config = config.copy(dragAction = it) },
                    )
                }

                // ═══════════════════════════════════════════════════════════
                // ── Two Finger ─────────────────────────────────────────────
                // ═══════════════════════════════════════════════════════════
                Text(
                    text = stringResource(R.string.gesture_section_two_finger),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp),
                )

                // ── Two-Finger Tap (customisable action) ─────────────────
                GestureRow(
                    title = stringResource(R.string.gesture_two_finger_tap),
                    subtitle = tapHoldActionLabel(config.twoFingerTapAction),
                    enabled = config.twoFingerTapEnabled,
                    onEnabledChange = { config = config.copy(twoFingerTapEnabled = it) },
                ) {
                    TapHoldActionPicker(
                        currentAction = config.twoFingerTapAction,
                        onActionSelected = { config = config.copy(twoFingerTapAction = it) },
                    )
                }

                // ── Two-Finger Hold (customisable action + delay) ────────
                GestureRow(
                    title = stringResource(R.string.gesture_two_finger_hold),
                    subtitle = holdGestureSubtitle(config.twoFingerHoldAction, config.twoFingerHoldMouseBehavior),
                    enabled = config.twoFingerHoldEnabled,
                    onEnabledChange = { config = config.copy(twoFingerHoldEnabled = it) },
                ) {
                    TapHoldActionPicker(
                        currentAction = config.twoFingerHoldAction,
                        onActionSelected = { config = config.copy(twoFingerHoldAction = it) },
                    )
                    MouseHoldBehaviorPicker(
                        action = config.twoFingerHoldAction,
                        currentBehavior = config.twoFingerHoldMouseBehavior,
                        onBehaviorSelected = { config = config.copy(twoFingerHoldMouseBehavior = it) },
                    )
                    DelayTextField(
                        label = stringResource(R.string.gesture_two_finger_hold_delay),
                        value = config.twoFingerHoldDelay,
                        onValueChange = { config = config.copy(twoFingerHoldDelay = it) },
                    )
                }

                // ── Two-Finger Drag (customisable action) ────────────────
                GestureRow(
                    title = stringResource(R.string.gesture_two_finger_drag),
                    subtitle = panActionLabel(config.twoFingerDragAction),
                    enabled = config.twoFingerDragEnabled,
                    onEnabledChange = { config = config.copy(twoFingerDragEnabled = it) },
                ) {
                    PanActionPicker(
                        currentAction = config.twoFingerDragAction,
                        onActionSelected = { config = config.copy(twoFingerDragAction = it) },
                    )
                }

                // ── Pinch In/Out (customisable action) ───────────────────
                GestureRow(
                    title = stringResource(R.string.gesture_pinch),
                    subtitle = zoomActionLabel(config.pinchAction),
                    enabled = config.pinchEnabled,
                    onEnabledChange = { config = config.copy(pinchEnabled = it) },
                ) {
                    SettingsListDropdown(
                        colors = settingsTileColors(),
                        title = { Text(stringResource(R.string.gesture_action_label)) },
                        value = ZOOM_ACTIONS.indexOf(config.pinchAction).coerceAtLeast(0),
                        items = ZOOM_ACTIONS.map { zoomActionLabel(it) },
                        onItemSelected = { index ->
                            config = config.copy(pinchAction = ZOOM_ACTIONS[index])
                        },
                    )
                }

                // ═══════════════════════════════════════════════════════════
                // ── Three Finger ───────────────────────────────────────────
                // ═══════════════════════════════════════════════════════════
                Text(
                    text = stringResource(R.string.gesture_section_three_finger),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp),
                )

                // ── Three-Finger Tap (customisable action) ──────────────
                GestureRow(
                    title = stringResource(R.string.gesture_three_finger_tap),
                    subtitle = tapHoldActionLabel(config.threeFingerTapAction),
                    enabled = config.threeFingerTapEnabled,
                    onEnabledChange = { config = config.copy(threeFingerTapEnabled = it) },
                ) {
                    TapHoldActionPicker(
                        currentAction = config.threeFingerTapAction,
                        onActionSelected = { config = config.copy(threeFingerTapAction = it) },
                    )
                }

                // ── Three-Finger Hold (customisable action + delay) ─────
                GestureRow(
                    title = stringResource(R.string.gesture_three_finger_hold),
                    subtitle = holdGestureSubtitle(config.threeFingerHoldAction, config.threeFingerHoldMouseBehavior),
                    enabled = config.threeFingerHoldEnabled,
                    onEnabledChange = { config = config.copy(threeFingerHoldEnabled = it) },
                ) {
                    TapHoldActionPicker(
                        currentAction = config.threeFingerHoldAction,
                        onActionSelected = { config = config.copy(threeFingerHoldAction = it) },
                    )
                    MouseHoldBehaviorPicker(
                        action = config.threeFingerHoldAction,
                        currentBehavior = config.threeFingerHoldMouseBehavior,
                        onBehaviorSelected = { config = config.copy(threeFingerHoldMouseBehavior = it) },
                    )
                    DelayTextField(
                        label = stringResource(R.string.gesture_three_finger_hold_delay),
                        value = config.threeFingerHoldDelay,
                        onValueChange = { config = config.copy(threeFingerHoldDelay = it) },
                    )
                }

                // ── Three-Finger Drag (customisable action) ─────────────
                GestureRow(
                    title = stringResource(R.string.gesture_three_finger_drag),
                    subtitle = panActionLabel(config.threeFingerDragAction),
                    enabled = config.threeFingerDragEnabled,
                    onEnabledChange = { config = config.copy(threeFingerDragEnabled = it) },
                ) {
                    PanActionPicker(
                        currentAction = config.threeFingerDragAction,
                        onActionSelected = { config = config.copy(threeFingerDragAction = it) },
                    )
                }

                // ═══════════════════════════════════════════════════════════
                // ── Other ──────────────────────────────────────────────────
                // ═══════════════════════════════════════════════════════════
                Text(
                    text = stringResource(R.string.gesture_section_other),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp),
                )

                // ── Gesture Threshold ────────────────────────────────────
                DelayTextField(
                    label = stringResource(R.string.gesture_threshold),
                    value = config.gestureThreshold,
                    onValueChange = { config = config.copy(gestureThreshold = it) },
                )

                SettingsListDropdown(
                    colors = settingsTileColorsAlt(),
                    title = { Text(stringResource(R.string.gesture_mouse_drag_movement)) },
                    subtitle = { Text(stringResource(R.string.gesture_mouse_drag_movement_subtitle)) },
                    value = MOUSE_DRAG_MOVEMENT_MODES
                        .indexOf(config.mouseDragMovementMode)
                        .coerceAtLeast(0),
                    items = MOUSE_DRAG_MOVEMENT_MODES.map { mouseDragMovementModeLabel(it) },
                    onItemSelected = { index ->
                        config = config.copy(mouseDragMovementMode = MOUSE_DRAG_MOVEMENT_MODES[index])
                    },
                )

                // ── Show Click Highlight ─────────────────────────────────
                SettingsSwitch(
                    colors = settingsTileColorsAlt(),
                    title = { Text(stringResource(R.string.gesture_show_click_highlight)) },
                    subtitle = { Text(stringResource(R.string.gesture_show_click_highlight_subtitle)) },
                    state = config.showClickHighlight,
                    onCheckedChange = { config = config.copy(showClickHighlight = it) },
                )

                SettingsSwitch(
                    colors = settingsTileColorsAlt(),
                    title = { Text(stringResource(R.string.gesture_show_debug_overlay)) },
                    subtitle = { Text(stringResource(R.string.gesture_show_debug_overlay_subtitle)) },
                    state = config.showGestureDebugOverlay,
                    onCheckedChange = { config = config.copy(showGestureDebugOverlay = it) },
                )

                SettingsSwitch(
                    colors = settingsTileColorsAlt(),
                    title = { Text(stringResource(R.string.gesture_show_cursor_touchscreen_mode)) },
                    subtitle = { Text(stringResource(R.string.gesture_show_cursor_touchscreen_mode_subtitle)) },
                    state = config.showCursorInTouchscreenMode,
                    onCheckedChange = { config = config.copy(showCursorInTouchscreenMode = it) },
                )

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ── Helper composables / functions ───────────────────────────────────────

/**
 * One uniform row for a toggleable gesture: a [GestureBlock] containing a [SettingsSwitch]
 * with title + dynamic subtitle, plus an optional [GestureSubSettings] block that only renders
 * when [enabled] is true.
 *
 * @param title          The gesture's display name (e.g. "Tap").
 * @param subtitle       The current action / hint shown beneath the title; auto-dimmed when disabled.
 * @param enabled        Current on/off state.
 * @param onEnabledChange Callback when the user toggles the switch.
 * @param expandedContent Optional sub-settings shown when [enabled] is true (e.g. action picker, delay field).
 */
@Composable
private fun GestureRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    GestureBlock {
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            title = { Text(title) },
            subtitle = {
                Text(
                    text = subtitle,
                    color = if (!enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f) else Color.Unspecified,
                )
            },
            state = enabled,
            onCheckedChange = onEnabledChange,
        )
        if (enabled && expandedContent != null) {
            GestureSubSettings { expandedContent() }
        }
    }
}

@Composable
private fun GestureBlock(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = PluviaBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, PluviaBorder.copy(alpha = 0.55f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            content()
        }
    }
}

@Composable
private fun GestureSubSettings(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = (-6).dp)
            .padding(start = 12.dp, end = 10.dp, bottom = 4.dp),
    ) {
        content()
    }
}

@Composable
private fun DelayTextField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    NoExtractOutlinedTextField(
        value = text,
        onValueChange = { newText ->
            // Allow only digits
            val filtered = newText.filter { it.isDigit() }
            text = filtered
            filtered.toIntOrNull()?.let { onValueChange(it) }
        },
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 0.dp),
    )
}

@Composable
private fun MouseHoldBehaviorPicker(
    action: String,
    currentBehavior: String,
    onBehaviorSelected: (String) -> Unit,
) {
    if (!isMouseButtonAction(action)) return

    val behaviors = listOf(MOUSE_BEHAVIOR_CLICK, MOUSE_BEHAVIOR_HOLD)
    val selectedBehavior = if (currentBehavior == MOUSE_BEHAVIOR_CLICK) {
        MOUSE_BEHAVIOR_CLICK
    } else {
        MOUSE_BEHAVIOR_HOLD
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp),
        shape = RoundedCornerShape(10.dp),
        color = PluviaSurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, PluviaBorder.copy(alpha = 0.5f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.gesture_mouse_behavior_label),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                behaviors.forEachIndexed { index, behavior ->
                    SegmentedButton(
                        selected = behavior == selectedBehavior,
                        onClick = { onBehaviorSelected(behavior) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = behaviors.size,
                        ),
                        label = {
                            Text(
                                text = mouseBehaviorLabel(behavior),
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun tapHoldActionLabel(action: String): String = when (action) {
    ACTION_LEFT_CLICK -> stringResource(R.string.gesture_action_left_click)
    ACTION_RIGHT_CLICK -> stringResource(R.string.gesture_action_right_click)
    ACTION_MIDDLE_CLICK -> stringResource(R.string.gesture_action_middle_click)
    ACTION_SHOW_KEYBOARD -> stringResource(R.string.gesture_action_show_keyboard)
    else -> {
        if (action.startsWith("key_")) {
            action.removePrefix("key_").replace("_", " ")
        } else {
            action
        }
    }
}

@Composable
private fun holdGestureSubtitle(action: String, mouseBehavior: String): String {
    val actionLabel = tapHoldActionLabel(action)
    return if (isMouseButtonAction(action)) {
        "$actionLabel (${mouseBehaviorLabel(mouseBehavior)})"
    } else {
        actionLabel
    }
}

@Composable
private fun mouseBehaviorLabel(behavior: String): String = when (behavior) {
    MOUSE_BEHAVIOR_CLICK -> stringResource(R.string.gesture_mouse_behavior_click)
    else -> stringResource(R.string.gesture_mouse_behavior_hold)
}

private fun isMouseButtonAction(action: String): Boolean {
    return action == ACTION_LEFT_CLICK || action == ACTION_RIGHT_CLICK || action == ACTION_MIDDLE_CLICK
}

@Composable
private fun panActionLabel(action: String): String = when (action) {
    PAN_MIDDLE_MOUSE -> stringResource(R.string.gesture_pan_middle_mouse)
    PAN_WASD -> stringResource(R.string.gesture_pan_wasd)
    PAN_ARROW_KEYS -> stringResource(R.string.gesture_pan_arrow_keys)
    PAN_LEFT_CLICK_DRAG -> stringResource(R.string.gesture_pan_left_click_drag)
    PAN_RIGHT_CLICK_DRAG -> stringResource(R.string.gesture_pan_right_click_drag)
    else -> action
}

@Composable
private fun zoomActionLabel(action: String): String = when (action) {
    ZOOM_SCROLL_WHEEL -> stringResource(R.string.gesture_zoom_scroll_wheel)
    ZOOM_PLUS_MINUS -> stringResource(R.string.gesture_zoom_plus_minus)
    ZOOM_PAGE_UP_DOWN -> stringResource(R.string.gesture_zoom_page_up_down)
    else -> action
}

@Composable
private fun mouseDragMovementModeLabel(mode: String): String = when (mode) {
    MOUSE_DRAG_MOVEMENT_RELATIVE -> stringResource(R.string.gesture_mouse_drag_movement_relative)
    MOUSE_DRAG_MOVEMENT_DIRECT -> stringResource(R.string.gesture_mouse_drag_movement_direct)
    else -> mode
}

// ── Categorized action picker for tap/hold gestures ─────────────────────

private data class ActionCategory(val header: String, val actions: List<Pair<String, String>>)

@Composable
private fun buildActionCategories(): List<ActionCategory> {
    val special = ActionCategory(
        header = stringResource(R.string.gesture_header_special),
        actions = listOf(ACTION_SHOW_KEYBOARD to stringResource(R.string.gesture_action_show_keyboard))
    )
    val mouse = ActionCategory(
        header = stringResource(R.string.gesture_header_mouse),
        actions = listOf(
            ACTION_LEFT_CLICK to stringResource(R.string.gesture_action_left_click),
            ACTION_RIGHT_CLICK to stringResource(R.string.gesture_action_right_click),
            ACTION_MIDDLE_CLICK to stringResource(R.string.gesture_action_middle_click),
        )
    )
    val commonGame = ActionCategory(
        header = stringResource(R.string.gesture_header_common_game),
        actions = listOf(
            "key_ESC" to "ESC", "key_SPACE" to "SPACE", "key_E" to "E", "key_Q" to "Q",
            "key_F" to "F", "key_TAB" to "TAB", "key_ENTER" to "ENTER",
            "key_I" to "I", "key_M" to "M", "key_R" to "R",
        )
    )
    val letters = ActionCategory(
        header = stringResource(R.string.gesture_header_letters),
        actions = ('A'..'Z').map { "key_$it" to it.toString() }
    )
    val numbers = ActionCategory(
        header = stringResource(R.string.gesture_header_numbers),
        actions = (0..9).map { "key_$it" to it.toString() }
    )
    val functionKeys = ActionCategory(
        header = stringResource(R.string.gesture_header_function_keys),
        actions = (1..12).map { "key_F$it" to "F$it" }
    )
    return listOf(special, mouse, commonGame, letters, numbers, functionKeys)
}

@Composable
private fun TapHoldActionPicker(
    currentAction: String,
    onActionSelected: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val label = tapHoldActionLabel(currentAction)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clickable { showDialog = true },
        shape = RoundedCornerShape(10.dp),
        color = PluviaSurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, PluviaBorder.copy(alpha = 0.5f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.gesture_action_label))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = MaterialTheme.colorScheme.primary)
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDialog) {
        val categories = buildActionCategories()
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = PluviaBackground,
            title = { Text(stringResource(R.string.gesture_action_label)) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    categories.forEach { category ->
                        item {
                            Text(
                                text = category.header,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(category.actions) { (actionKey, actionLabel) ->
                            val isSelected = actionKey == currentAction
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onActionSelected(actionKey)
                                        showDialog = false
                                    },
                                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else PluviaSurface,
                            ) {
                                Text(
                                    text = actionLabel,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun MouseButtonActionPicker(
    currentAction: String,
    onActionSelected: (String) -> Unit,
) {
    val actions = listOf(ACTION_LEFT_CLICK, ACTION_RIGHT_CLICK, ACTION_MIDDLE_CLICK)
    SettingsListDropdown(
        colors = settingsTileColors(),
        title = { Text(stringResource(R.string.gesture_mouse_button_label)) },
        value = actions.indexOf(currentAction).coerceAtLeast(0),
        items = actions.map { tapHoldActionLabel(it) },
        onItemSelected = { index ->
            onActionSelected(actions[index])
        },
    )
}

@Composable
private fun PanActionPicker(
    currentAction: String,
    onActionSelected: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val label = panActionLabel(currentAction)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp)
            .clickable { showDialog = true },
        shape = RoundedCornerShape(10.dp),
        color = PluviaSurfaceElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, PluviaBorder.copy(alpha = 0.5f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stringResource(R.string.gesture_action_label))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, color = MaterialTheme.colorScheme.primary)
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            containerColor = PluviaBackground,
            title = { Text(stringResource(R.string.gesture_action_label)) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(PAN_ACTIONS) { action ->
                        val isSelected = action == currentAction
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onActionSelected(action)
                                    showDialog = false
                                },
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else PluviaSurface,
                        ) {
                            Text(
                                text = panActionLabel(action),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}
