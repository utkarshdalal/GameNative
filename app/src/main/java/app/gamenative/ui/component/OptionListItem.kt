package app.gamenative.ui.component

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.gamenative.ui.theme.PluviaTheme

private data class OptionItemStyle(
    val scale: Float,
    val backgroundColor: Color,
    val borderColor: Color,
    val borderWidth: androidx.compose.ui.unit.Dp,
    val contentColor: Color,
)

@Composable
private fun rememberOptionItemStyle(
    isFocused: Boolean,
    selected: Boolean,
    labelPrefix: String,
): OptionItemStyle {
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.02f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "${labelPrefix}Scale",
    )

    val backgroundColor by animateColorAsState(
        targetValue = when {
            isFocused && selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            isFocused -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            else -> Color.Transparent
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "${labelPrefix}BgColor",
    )

    val borderColor by animateColorAsState(
        targetValue = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "${labelPrefix}BorderColor",
    )

    val borderWidth by animateDpAsState(
        targetValue = if (isFocused) 2.dp else 0.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "${labelPrefix}BorderWidth",
    )

    val contentColor by animateColorAsState(
        targetValue = when {
            isFocused -> MaterialTheme.colorScheme.onSurface
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "${labelPrefix}ContentColor",
    )

    return OptionItemStyle(
        scale = scale,
        backgroundColor = backgroundColor,
        borderColor = borderColor,
        borderWidth = borderWidth,
        contentColor = contentColor,
    )
}

@Composable
fun OptionListItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    showCheckmark: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val style = rememberOptionItemStyle(
        isFocused = isFocused,
        selected = selected,
        labelPrefix = "item",
    )

    Box(
        modifier = modifier
            .scale(style.scale)
            .clip(RoundedCornerShape(12.dp))
            .background(style.backgroundColor)
            .then(
                if (isFocused) {
                    Modifier.border(style.borderWidth, style.borderColor, RoundedCornerShape(12.dp))
                } else Modifier
            )
            .focusRequester(focusRequester)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = style.contentColor,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(14.dp))
            }

            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = style.contentColor,
                modifier = Modifier.weight(1f)
            )

            if (showCheckmark && selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun OptionRadioItem(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val style = rememberOptionItemStyle(
        isFocused = isFocused,
        selected = selected,
        labelPrefix = "radio",
    )

    val radioIndicatorColor by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "radioIndicatorColor"
    )

    Box(
        modifier = modifier
            .scale(style.scale)
            .clip(RoundedCornerShape(12.dp))
            .background(style.backgroundColor)
            .then(
                if (isFocused) {
                    Modifier.border(style.borderWidth, style.borderColor, RoundedCornerShape(12.dp))
                } else Modifier
            )
            .focusRequester(focusRequester)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .border(2.dp, radioIndicatorColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(MaterialTheme.colorScheme.primary, CircleShape)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = style.contentColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = style.contentColor,
            )
        }
    }
}

@Composable
fun OptionSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
        letterSpacing = MaterialTheme.typography.labelMedium.letterSpacing * 1.5f,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun Preview_OptionListItem() {
    PluviaTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                OptionSectionHeader(text = "Sort By")
                OptionRadioItem(
                    text = "Installed First",
                    selected = true,
                    onClick = {},
                    icon = Icons.Default.Download
                )
                OptionRadioItem(
                    text = "Name (A-Z)",
                    selected = false,
                    onClick = {},
                    icon = Icons.Default.SortByAlpha
                )

                Spacer(modifier = Modifier.height(16.dp))

                OptionSectionHeader(text = "Filter By Type")
                OptionListItem(
                    text = "Games",
                    selected = true,
                    onClick = {},
                )
                OptionListItem(
                    text = "Applications",
                    selected = false,
                    onClick = {},
                )
                OptionListItem(
                    text = "Tools",
                    selected = true,
                    onClick = {},
                )
            }
        }
    }
}
