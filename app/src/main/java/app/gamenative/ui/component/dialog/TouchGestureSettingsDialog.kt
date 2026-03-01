package app.gamenative.ui.component.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.gamenative.R
import app.gamenative.data.TouchGestureConfig
import app.gamenative.data.TouchGestureConfig.Companion.ACTION_LEFT_CLICK
import app.gamenative.data.TouchGestureConfig.Companion.ACTION_MIDDLE_CLICK
import app.gamenative.data.TouchGestureConfig.Companion.ACTION_RIGHT_CLICK
import app.gamenative.data.TouchGestureConfig.Companion.COMMON_MOUSE_ACTIONS
import app.gamenative.data.TouchGestureConfig.Companion.PAN_ACTIONS
import app.gamenative.data.TouchGestureConfig.Companion.PAN_ARROW_KEYS
import app.gamenative.data.TouchGestureConfig.Companion.PAN_MIDDLE_MOUSE
import app.gamenative.data.TouchGestureConfig.Companion.PAN_WASD
import app.gamenative.data.TouchGestureConfig.Companion.ZOOM_ACTIONS
import app.gamenative.data.TouchGestureConfig.Companion.ZOOM_PAGE_UP_DOWN
import app.gamenative.data.TouchGestureConfig.Companion.ZOOM_PLUS_MINUS
import app.gamenative.data.TouchGestureConfig.Companion.ZOOM_SCROLL_WHEEL
import app.gamenative.ui.component.settings.SettingsListDropdown
import app.gamenative.ui.theme.settingsTileColors
import app.gamenative.ui.theme.settingsTileColorsAlt
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
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.gesture_settings_title)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        TextButton(onClick = { onSave(config) }) {
                            Text(stringResource(android.R.string.ok))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp),
            ) {
                // ── 1. Tap (fixed: Left Click) ──────────────────────────
                OutlinedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    SettingsSwitch(
                        colors = settingsTileColorsAlt(),
                        title = { Text(stringResource(R.string.gesture_tap)) },
                        subtitle = { Text(stringResource(R.string.gesture_tap_subtitle)) },
                        state = config.tapEnabled,
                        onCheckedChange = { config = config.copy(tapEnabled = it) },
                    )
                }

                // ── 2. Drag (fixed: Drag Left Click) ────────────────────
                OutlinedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    SettingsSwitch(
                        colors = settingsTileColorsAlt(),
                        title = { Text(stringResource(R.string.gesture_drag)) },
                        subtitle = { Text(stringResource(R.string.gesture_drag_subtitle)) },
                        state = config.dragEnabled,
                        onCheckedChange = { config = config.copy(dragEnabled = it) },
                    )
                }

                // ── 3. Long Press (customisable action + delay) ──────────
                OutlinedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    SettingsSwitch(
                        colors = settingsTileColorsAlt(),
                        title = { Text(stringResource(R.string.gesture_long_press)) },
                        subtitle = { Text(mouseActionLabel(config.longPressAction)) },
                        state = config.longPressEnabled,
                        onCheckedChange = { config = config.copy(longPressEnabled = it) },
                    )
                    if (config.longPressEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsListDropdown(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.gesture_action_label)) },
                            value = COMMON_MOUSE_ACTIONS.indexOf(config.longPressAction).coerceAtLeast(0),
                            items = COMMON_MOUSE_ACTIONS.map { mouseActionLabel(it) },
                            onItemSelected = { index ->
                                config = config.copy(longPressAction = COMMON_MOUSE_ACTIONS[index])
                            },
                        )
                        DelayTextField(
                            label = stringResource(R.string.gesture_long_press_delay),
                            value = config.longPressDelay,
                            onValueChange = { config = config.copy(longPressDelay = it) },
                        )
                    }
                }

                // ── 4. Double-Tap (fixed action, customisable delay) ─────
                OutlinedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    SettingsSwitch(
                        colors = settingsTileColorsAlt(),
                        title = { Text(stringResource(R.string.gesture_double_tap)) },
                        subtitle = { Text(stringResource(R.string.gesture_double_tap_subtitle)) },
                        state = config.doubleTapEnabled,
                        onCheckedChange = { config = config.copy(doubleTapEnabled = it) },
                    )
                    if (config.doubleTapEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        DelayTextField(
                            label = stringResource(R.string.gesture_double_tap_delay),
                            value = config.doubleTapDelay,
                            onValueChange = { config = config.copy(doubleTapDelay = it) },
                        )
                    }
                }

                // ── 5. Two-Finger Drag (customisable action) ─────────────
                OutlinedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    SettingsSwitch(
                        colors = settingsTileColorsAlt(),
                        title = { Text(stringResource(R.string.gesture_two_finger_drag)) },
                        subtitle = { Text(panActionLabel(config.twoFingerDragAction)) },
                        state = config.twoFingerDragEnabled,
                        onCheckedChange = { config = config.copy(twoFingerDragEnabled = it) },
                    )
                    if (config.twoFingerDragEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsListDropdown(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.gesture_action_label)) },
                            value = PAN_ACTIONS.indexOf(config.twoFingerDragAction).coerceAtLeast(0),
                            items = PAN_ACTIONS.map { panActionLabel(it) },
                            onItemSelected = { index ->
                                config = config.copy(twoFingerDragAction = PAN_ACTIONS[index])
                            },
                        )
                    }
                }

                // ── 6. Pinch In/Out (customisable action) ────────────────
                OutlinedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    SettingsSwitch(
                        colors = settingsTileColorsAlt(),
                        title = { Text(stringResource(R.string.gesture_pinch)) },
                        subtitle = { Text(zoomActionLabel(config.pinchAction)) },
                        state = config.pinchEnabled,
                        onCheckedChange = { config = config.copy(pinchEnabled = it) },
                    )
                    if (config.pinchEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
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
                }

                // ── 7. Two-Finger Tap (customisable action) ──────────────
                OutlinedCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    SettingsSwitch(
                        colors = settingsTileColorsAlt(),
                        title = { Text(stringResource(R.string.gesture_two_finger_tap)) },
                        subtitle = { Text(mouseActionLabel(config.twoFingerTapAction)) },
                        state = config.twoFingerTapEnabled,
                        onCheckedChange = { config = config.copy(twoFingerTapEnabled = it) },
                    )
                    if (config.twoFingerTapEnabled) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsListDropdown(
                            colors = settingsTileColors(),
                            title = { Text(stringResource(R.string.gesture_action_label)) },
                            value = COMMON_MOUSE_ACTIONS.indexOf(config.twoFingerTapAction).coerceAtLeast(0),
                            items = COMMON_MOUSE_ACTIONS.map { mouseActionLabel(it) },
                            onItemSelected = { index ->
                                config = config.copy(twoFingerTapAction = COMMON_MOUSE_ACTIONS[index])
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

// ── Helper composables / functions ───────────────────────────────────────

@Composable
private fun DelayTextField(
    label: String,
    value: Int,
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }

    OutlinedTextField(
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
            .padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun mouseActionLabel(action: String): String = when (action) {
    ACTION_LEFT_CLICK -> stringResource(R.string.gesture_action_left_click)
    ACTION_RIGHT_CLICK -> stringResource(R.string.gesture_action_right_click)
    ACTION_MIDDLE_CLICK -> stringResource(R.string.gesture_action_middle_click)
    else -> action
}

@Composable
private fun panActionLabel(action: String): String = when (action) {
    PAN_MIDDLE_MOUSE -> stringResource(R.string.gesture_pan_middle_mouse)
    PAN_WASD -> stringResource(R.string.gesture_pan_wasd)
    PAN_ARROW_KEYS -> stringResource(R.string.gesture_pan_arrow_keys)
    else -> action
}

@Composable
private fun zoomActionLabel(action: String): String = when (action) {
    ZOOM_SCROLL_WHEEL -> stringResource(R.string.gesture_zoom_scroll_wheel)
    ZOOM_PLUS_MINUS -> stringResource(R.string.gesture_zoom_plus_minus)
    ZOOM_PAGE_UP_DOWN -> stringResource(R.string.gesture_zoom_page_up_down)
    else -> action
}
