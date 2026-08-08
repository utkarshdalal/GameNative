package app.gamenative.ui.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.enums.LibraryTab
import app.gamenative.ui.enums.LibraryTabPreference
import app.gamenative.ui.util.WindowWidthClass
import app.gamenative.ui.util.rememberWindowWidthClass
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTabsCustomizer(
    visible: Boolean,
    preferences: List<LibraryTabPreference>,
    tabCounts: Map<LibraryTab, Int>,
    onVisibilityChanged: (LibraryTab, Boolean) -> Unit,
    onMove: (LibraryTab, Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    val content: @Composable () -> Unit = {
        CustomizerContent(
            preferences = preferences,
            tabCounts = tabCounts,
            onVisibilityChanged = onVisibilityChanged,
            onMove = onMove,
            onReset = onReset,
            onDismiss = onDismiss,
        )
    }

    if (rememberWindowWidthClass() == WindowWidthClass.COMPACT) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            content()
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {},
            text = {
                Box(
                    modifier = Modifier
                        .widthIn(max = 620.dp)
                        .heightIn(max = 720.dp),
                ) {
                    content()
                }
            },
        )
    }
}

@Composable
private fun CustomizerContent(
    preferences: List<LibraryTabPreference>,
    tabCounts: Map<LibraryTab, Int>,
    onVisibilityChanged: (LibraryTab, Boolean) -> Unit,
    onMove: (LibraryTab, Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.library_customize_tabs),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.library_customize_tabs_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.library_tabs_live_preview),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            preferences.filter { it.isVisible }.forEachIndexed { index, preference ->
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (index == 0) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = stringResource(preference.tab.labelResId),
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(max = 470.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 1.dp,
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                preferences.forEachIndexed { index, preference ->
                    LibraryTabPreferenceRow(
                        preference = preference,
                        count = tabCounts[preference.tab] ?: 0,
                        canMoveUp = index > 1,
                        canMoveDown = index in 1..<preferences.lastIndex,
                        onVisibilityChanged = onVisibilityChanged,
                        onMove = onMove,
                    )
                    if (index < preferences.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.library_tabs_reset))
            }
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LibraryTabPreferenceRow(
    preference: LibraryTabPreference,
    count: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onVisibilityChanged: (LibraryTab, Boolean) -> Unit,
    onMove: (LibraryTab, Int) -> Unit,
) {
    val tab = preference.tab
    val isPinned = tab == LibraryTab.ALL
    val rowHeightPx = with(LocalDensity.current) { 56.dp.toPx() }
    var accumulatedDrag by remember(tab) { mutableFloatStateOf(0f) }
    val moveUpLabel = stringResource(R.string.library_tabs_move_up, stringResource(tab.labelResId))
    val moveDownLabel = stringResource(R.string.library_tabs_move_down, stringResource(tab.labelResId))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isPinned) {
                    Modifier
                } else {
                    Modifier
                        .semantics {
                            customActions = buildList {
                                if (canMoveUp) {
                                    add(
                                        CustomAccessibilityAction(moveUpLabel) {
                                            onMove(tab, -1)
                                            true
                                        },
                                    )
                                }
                                if (canMoveDown) {
                                    add(
                                        CustomAccessibilityAction(moveDownLabel) {
                                            onMove(tab, 1)
                                            true
                                        },
                                    )
                                }
                            }
                        }
                        .pointerInput(tab, canMoveUp, canMoveDown) {
                            detectDragGesturesAfterLongPress(
                                onDragEnd = { accumulatedDrag = 0f },
                                onDragCancel = { accumulatedDrag = 0f },
                            ) { change, dragAmount ->
                                change.consume()
                                accumulatedDrag += dragAmount.y
                                if (abs(accumulatedDrag) >= rowHeightPx) {
                                    val offset = if (accumulatedDrag > 0) 1 else -1
                                    if ((offset < 0 && canMoveUp) || (offset > 0 && canMoveDown)) {
                                        onMove(tab, offset)
                                    }
                                    accumulatedDrag = 0f
                                }
                            }
                        }
                },
            )
            .background(
                if (preference.isVisible) {
                    MaterialTheme.colorScheme.surfaceContainerLow
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLowest
                },
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isPinned) Icons.Default.PushPin else Icons.Default.DragHandle,
            contentDescription = if (isPinned) null else stringResource(R.string.library_tabs_drag_to_reorder),
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = stringResource(tab.labelResId),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (isPinned) {
                    stringResource(R.string.library_tabs_always_visible)
                } else {
                    stringResource(R.string.library_tabs_game_count, count)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isPinned) {
            Text(
                text = stringResource(R.string.library_tabs_pinned),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            IconButton(
                onClick = { onMove(tab, -1) },
                enabled = canMoveUp,
            ) {
                Icon(Icons.Default.ArrowUpward, contentDescription = moveUpLabel)
            }
            IconButton(
                onClick = { onMove(tab, 1) },
                enabled = canMoveDown,
            ) {
                Icon(Icons.Default.ArrowDownward, contentDescription = moveDownLabel)
            }
            Switch(
                checked = preference.isVisible,
                onCheckedChange = { onVisibilityChanged(tab, it) },
            )
        }
    }
}
