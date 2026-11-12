package app.gamenative.ui.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gamenative.R
import app.gamenative.ui.screen.support.supportButtonColors
import app.gamenative.ui.theme.PluviaTheme

/** A round badge with a soft radial glow in [accent] behind [icon]; heads paywalls and upsell cards. */
@Composable
fun HeroBadge(
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    iconSize: Dp = 28.dp,
    glowAlpha: Float = 0.2f,
    iconAlpha: Float = 1f,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        accent.copy(alpha = glowAlpha),
                        Color.Transparent,
                    ),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent.copy(alpha = iconAlpha),
            modifier = Modifier.size(iconSize),
        )
    }
}

/** The 36dp rounded tile with a tinted icon used by benefit rows and goal cards. */
@Composable
fun IconTile(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(36.dp)
            .clip(PluviaTheme.shape(10.dp))
            .background(tint.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** One benefit: a tinted icon tile beside a title and an optional muted body line. */
@Composable
fun BenefitRow(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    modifier: Modifier = Modifier,
    body: String? = null,
    compact: Boolean = false,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(icon = icon, tint = iconTint)
        Column {
            Text(
                text = title,
                style = if (compact) {
                    MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                } else {
                    MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.3.sp,
                    )
                },
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = PluviaTheme.colors.textMuted,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** A [BenefitRow] on its own elevated 20dp card, as the paywall tiers show it. */
@Composable
fun BenefitCard(
    title: String,
    body: String,
    icon: ImageVector,
    iconTint: Color,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = PluviaTheme.shape(20.dp),
        color = PluviaTheme.colors.surfaceElevated,
        tonalElevation = 0.dp,
    ) {
        BenefitRow(
            title = title,
            body = body,
            icon = icon,
            iconTint = iconTint,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
    }
}

/** A full-width 12dp dialog button with an optional subtitle and the d-pad focus ring; primary or outlined. */
@Composable
fun PremiumDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    subtitle: String? = null,
    colors: ButtonColors? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = PluviaTheme.shape(12.dp)
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = text, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
    val buttonModifier = modifier
        .fillMaxWidth()
        .focusRing(interaction, shape, width = 2.dp)
    if (primary) {
        val gradient = PluviaTheme.accentGradientBrush.takeIf { colors == null && enabled }
        Button(
            onClick = onClick,
            enabled = enabled,
            interactionSource = interaction,
            shape = shape,
            colors = colors ?: if (gradient != null) {
                ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                supportButtonColors()
            },
            modifier = if (gradient != null) buttonModifier.background(gradient, shape) else buttonModifier,
        ) {
            content()
        }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, interactionSource = interaction, shape = shape, modifier = buttonModifier, colors = PluviaTheme.outlinedButtonColors()) {
            content()
        }
    }
}

/** A small full-width text action under the main buttons, tinted [color], with the d-pad focus ring. */
@Composable
fun PremiumTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = PluviaTheme.colors.textMuted,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val shape = PluviaTheme.shape(12.dp)
    TextButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interaction,
        shape = shape,
        modifier = modifier
            .fillMaxWidth()
            .focusRing(interaction, shape, width = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) color else color.copy(alpha = 0.4f),
            textAlign = TextAlign.Center,
        )
    }
}

/** A pill with a colored dot and label, tinted [color] on a 15 % wash of it. */
@Composable
fun OutcomePill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    dot: Boolean = true,
) {
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

/**
 * The premium card surface: 20dp elevated, a 1dp border (the brand gradient unless [border] is given)
 * and a faint wash of [accent] across the top.
 */
@Composable
fun GradientBorderSurface(
    modifier: Modifier = Modifier,
    accent: Color = PluviaTheme.colors.accentCyan,
    border: Brush? = null,
    content: @Composable () -> Unit,
) {
    val shape = PluviaTheme.shape(20.dp)
    val washEnd = with(LocalDensity.current) { 120.dp.toPx() }
    Surface(
        modifier = modifier,
        shape = shape,
        color = PluviaTheme.colors.surfaceElevated,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, border ?: Brush.linearGradient(PluviaTheme.colors.brandGradient.map { it.copy(alpha = 0.6f) })),
    ) {
        Box {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(accent.copy(alpha = 0.10f), Color.Transparent),
                            startY = 0f,
                            endY = washEnd,
                        ),
                    ),
            )
            content()
        }
    }
}

/** One measured result: [label], the [before] value, the [after] value in [accent] and an optional [delta] in [deltaColor]. */
@Composable
fun MetricDeltaTile(
    label: String,
    after: String,
    accent: Color,
    modifier: Modifier = Modifier,
    before: String? = null,
    delta: String? = null,
    deltaColor: Color = PluviaTheme.colors.accentSuccess,
) {
    Column(
        modifier = modifier
            .clip(PluviaTheme.shape(14.dp))
            .background(PluviaTheme.colors.surfacePanel)
            .border(1.dp, PluviaTheme.colors.borderDefault.copy(alpha = 0.3f), PluviaTheme.shape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = PluviaTheme.colors.textMuted,
        )
        if (before != null) {
            Text(
                text = before,
                style = MaterialTheme.typography.labelSmall,
                color = PluviaTheme.colors.textMuted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Text(
            text = after,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = accent,
        )
        if (delta != null) {
            Text(
                text = delta,
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.3.sp),
                color = deltaColor,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** A round back button that grows and rings in cyan while focused. */
@Composable
fun FocusScaleBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "backButtonScale",
    )

    Box(
        modifier = modifier
            .scale(scale)
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (isFocused) {
                    PluviaTheme.colors.accentCyan.copy(alpha = 0.2f)
                } else {
                    PluviaTheme.colors.surfaceElevated
                },
            )
            .then(
                if (isFocused) {
                    Modifier.border(2.dp, PluviaTheme.colors.accentCyan.copy(alpha = 0.6f), CircleShape)
                } else {
                    Modifier.border(1.dp, PluviaTheme.colors.borderDefault.copy(alpha = 0.3f), CircleShape)
                },
            )
            .selectable(
                selected = isFocused,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = stringResource(R.string.back),
            tint = if (isFocused) PluviaTheme.colors.accentCyan else Color.White.copy(alpha = 0.8f),
            modifier = Modifier.size(24.dp),
        )
    }
}
