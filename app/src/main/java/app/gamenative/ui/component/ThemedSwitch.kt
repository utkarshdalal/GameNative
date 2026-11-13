package app.gamenative.ui.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.PluviaTheme
import app.gamenative.ui.theme.ThemedSwitchStyle
import kotlin.math.roundToInt

/** A switch in the theme's own look when it has one ([PluviaTheme.switchStyle]), else a Material [Switch] with [colors]. */
@Composable
@NonRestartableComposable
fun PluviaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    val style = if (PluviaTheme.isThemed) PluviaTheme.switchStyle else null
    if (style == null) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
            colors = colors,
            interactionSource = interactionSource,
        )
    } else {
        ThemedSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            style = style,
            modifier = modifier,
            enabled = enabled,
            interactionSource = interactionSource,
        )
    }
}

/**
 * A switch drawn with theme brushes: a pill track ([ThemedSwitchStyle.trackOn] / [ThemedSwitchStyle.trackOff], no
 * outline) and a round handle that slides between the ends. Same size, touch target and semantics as a Material switch.
 */
@Composable
fun ThemedSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    style: ThemedSwitchStyle,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val toggle = if (onCheckedChange != null) {
        Modifier
            .minimumInteractiveComponentSize()
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = source,
                indication = null,
            )
    } else {
        Modifier
    }
    val pressed by source.collectIsPressedAsState()
    ThemedSwitchTrack(
        checked = checked,
        style = style,
        modifier = modifier
            .then(toggle)
            .wrapContentSize(Alignment.Center)
            .then(if (enabled) Modifier else Modifier.alpha(DISABLED_ALPHA)),
        thumbSize = if (pressed) PRESSED_THUMB else THUMB,
        interactionSource = source,
    )
}

/**
 * The look of [ThemedSwitch] without input handling, for rows that toggle as a whole. The handle moves and changes
 * colour with [checked]; [interactionSource] adds the press / focus ripple around the handle.
 */
@Composable
fun ThemedSwitchTrack(
    checked: Boolean,
    style: ThemedSwitchStyle,
    modifier: Modifier = Modifier,
    width: Dp = TRACK_WIDTH,
    height: Dp = TRACK_HEIGHT,
    thumbSize: Dp = THUMB,
    interactionSource: InteractionSource? = null,
) {
    val progress by animateFloatAsState(if (checked) 1f else 0f, label = "switchProgress")
    val thumb by animateDpAsState(thumbSize, label = "switchThumb")
    Box(
        modifier = modifier
            .requiredSize(width, height)
            .drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(style.trackOff, cornerRadius = radius)
                if (progress > 0f) drawRoundRect(style.trackOn, cornerRadius = radius, alpha = progress)
                val r = thumb.toPx() / 2f
                val start = size.height / 2f
                val end = size.width - size.height / 2f
                drawCircle(
                    color = lerp(style.thumbOff, style.thumbOn, progress),
                    radius = r,
                    center = Offset(start + (end - start) * progress, size.height / 2f),
                )
            },
    ) {
        if (interactionSource != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset {
                        val travel = (width - height).toPx()
                        IntOffset(((height - RIPPLE_SIZE).toPx() / 2f + travel * progress).roundToInt(), 0)
                    }
                    .size(RIPPLE_SIZE)
                    .indication(interactionSource, ripple(bounded = false, radius = RIPPLE_SIZE / 2)),
            )
        }
    }
}

private val TRACK_WIDTH = 52.dp
private val TRACK_HEIGHT = 32.dp
private val THUMB = 24.dp
private val PRESSED_THUMB = 28.dp
private val RIPPLE_SIZE = 40.dp
private const val DISABLED_ALPHA = 0.38f
