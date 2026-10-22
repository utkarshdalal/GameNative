package app.gamenative.ui.component

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A discrete, SteamOS-style slider row: a [title] and one continuous track with a stop per
 * [labels] entry, each label centred beneath its own stop, and the selected stop plus its
 * label emphasised.
 */
@Composable
internal fun QuickMenuTickSlider(
    title: String,
    labels: List<String>,
    selectedIndex: Int,
    accentColor: Color,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    if (labels.isEmpty()) return
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(14.dp)
    val inputBypass = LocalImmersiveInputBypass.current
    var isAdjustmentLocked by remember { mutableStateOf(false) }
    val lastIndex = labels.lastIndex
    val selected = selectedIndex.coerceIn(0, lastIndex)
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    val stopColor = MaterialTheme.colorScheme.outline
    val holeColor = MaterialTheme.colorScheme.surface

    // Mirrors QuickMenuAdjustmentRow: the row only reports (and only consumes LEFT/RIGHT) while A has
    // engaged it, in every mode — a focused row alone must not be steppable by the dpad.
    LaunchedEffect(isFocused, isAdjustmentLocked, selected, lastIndex) {
        val decrease: () -> Unit = { onSelect((selected - 1).coerceAtLeast(0)) }
        val increase: () -> Unit = { onSelect((selected + 1).coerceAtMost(lastIndex)) }
        inputBypass.reportAdjustment(
            interactionSource,
            if (isFocused && isAdjustmentLocked) decrease to increase else null,
        )
    }
    DisposableEffect(interactionSource) {
        onDispose { inputBypass.reportAdjustment(interactionSource, null) }
    }

    Column(
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(shape)
            .background(
                if (isFocused) {
                    Brush.horizontalGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.16f),
                            accentColor.copy(alpha = 0.08f),
                        ),
                    )
                } else {
                    Brush.horizontalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f),
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f),
                        ),
                    )
                },
            )
            .then(
                if (isFocused && !isAdjustmentLocked) {
                    Modifier.border(
                        width = 2.dp,
                        color = accentColor.copy(alpha = 0.7f),
                        shape = shape,
                    )
                } else {
                    Modifier
                }
            )
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester)
                } else {
                    Modifier
                }
            )
            .onFocusChanged { if (!it.isFocused) isAdjustmentLocked = false }
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { keyEvent ->
                val keyCode = keyEvent.nativeKeyEvent.keyCode
                if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN || !isFocused) {
                    false
                } else {
                    when {
                        // A engages editing (immersive: always on; flat: toggles).
                        keyCode == KeyEvent.KEYCODE_BUTTON_A && (!inputBypass.active || !isAdjustmentLocked) -> {
                            isAdjustmentLocked = if (inputBypass.active) true else !isAdjustmentLocked
                            true
                        }

                        isAdjustmentLocked &&
                            (keyCode == KeyEvent.KEYCODE_BUTTON_B ||
                                (inputBypass.active && keyCode == KeyEvent.KEYCODE_BACK)) -> {
                            isAdjustmentLocked = false
                            true
                        }

                        isAdjustmentLocked && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                            onSelect((selected - 1).coerceAtLeast(0))
                            true
                        }

                        isAdjustmentLocked && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            onSelect((selected + 1).coerceAtMost(lastIndex))
                            true
                        }

                        else -> false
                    }
                }
            }
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        // Mirrors QuickMenuSectionHeader so the title reads as a section label
        // but sits inside this row's focus background/border.
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(modifier = Modifier.height(6.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(labels.size) {
                    detectTapGestures { offset ->
                        val cell = size.width.toFloat() / labels.size
                        onSelect((offset.x / cell).toInt().coerceIn(0, lastIndex))
                    }
                }
                .pointerInput(labels.size) {
                    detectHorizontalDragGestures { change, _ ->
                        val cell = size.width.toFloat() / labels.size
                        onSelect((change.position.x / cell).toInt().coerceIn(0, lastIndex))
                    }
                },
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp),
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val count = labels.size
                        val cell = size.width / count
                        val centerY = size.height / 2f
                        val thickness = 4.dp.toPx()
                        val stopRadius = 3.5.dp.toPx()
                        val firstX = cell / 2f
                        val lastX = size.width - cell / 2f
                        val selectedX = cell * selected + cell / 2f

                        drawRoundRect(
                            color = trackColor,
                            topLeft = Offset(firstX, centerY - thickness / 2f),
                            size = Size(lastX - firstX, thickness),
                            cornerRadius = CornerRadius(thickness / 2f),
                        )
                        if (selectedX > firstX) {
                            drawRoundRect(
                                color = accentColor,
                                topLeft = Offset(firstX, centerY - thickness / 2f),
                                size = Size(selectedX - firstX, thickness),
                                cornerRadius = CornerRadius(thickness / 2f),
                            )
                        }
                        for (index in 0 until count) {
                            val stopX = cell * index + cell / 2f
                            if (index == selected) {
                                drawCircle(
                                    color = accentColor,
                                    radius = stopRadius * 2f,
                                    center = Offset(stopX, centerY),
                                )
                                drawCircle(
                                    color = holeColor,
                                    radius = stopRadius,
                                    center = Offset(stopX, centerY),
                                )
                            } else {
                                drawCircle(
                                    color = stopColor,
                                    radius = stopRadius,
                                    center = Offset(stopX, centerY),
                                )
                            }
                        }
                    }
                    // Mirrors QuickMenuAdjustmentRow's engaged hint.
                    if (isFocused && isAdjustmentLocked) {
                        Text(
                            text = "●",
                            modifier = Modifier.align(Alignment.TopEnd),
                            style = MaterialTheme.typography.labelSmall,
                            color = accentColor,
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    labels.forEachIndexed { index, label ->
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (index == selected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
