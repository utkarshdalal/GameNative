package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.data.CommunityCompatibilityVerdict
import app.gamenative.data.GameCompatibilityStatus
import app.gamenative.ui.theme.PluviaTheme

/**
 * Badge displaying game compatibility status.
 *
 * Can be displayed as:
 * - Icon-only (for grid views) - compact circular badge
 * - Icon + label (for list views) - pill-shaped badge with text
 *
 * @param status The compatibility status to display
 * @param modifier Modifier for the badge
 * @param showLabel Whether to show the text label (true for list view, false for grid)
 */
@Composable
fun CompatibilityBadge(
    status: GameCompatibilityStatus,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false,
) {
    val badgeStyle = getBadgeStyle(status)

    if (showLabel) {
        PillBadge(
            modifier = modifier,
            icon = badgeStyle.icon,
            backgroundColor = badgeStyle.backgroundColor,
            iconTint = badgeStyle.iconTint,
            label = badgeStyle.labelResId,
        )
    } else {
        IconBadge(
            modifier = modifier,
            icon = badgeStyle.icon,
            backgroundColor = badgeStyle.backgroundColor,
            iconTint = badgeStyle.iconTint,
            contentDescription = badgeStyle.labelResId,
        )
    }
}

/** Hardware-specific community verdict badge used by normal library cards. */
@Composable
fun CommunityCompatibilityBadge(
    verdict: CommunityCompatibilityVerdict,
    modifier: Modifier = Modifier,
    showLabel: Boolean = false,
    verdictLoaded: Boolean = true,
    loadFailed: Boolean = false,
    checking: Boolean = false,
) {
    val base = getCommunityBadgeStyle(verdict)
    val style = if (verdictLoaded) {
        base
    } else {
        base.copy(
            icon = Icons.Rounded.HelpOutline,
            labelResId = if (loadFailed || !checking) {
                R.string.community_compatibility_unavailable_short
            } else {
                R.string.community_compatibility_checking_short
            },
        )
    }
    if (showLabel) {
        PillBadge(
            modifier = modifier,
            icon = style.icon,
            backgroundColor = style.backgroundColor,
            iconTint = style.iconTint,
            label = style.labelResId,
        )
    } else {
        IconBadge(
            modifier = modifier,
            icon = style.icon,
            backgroundColor = style.backgroundColor,
            iconTint = style.iconTint,
            contentDescription = style.labelResId,
        )
    }
}

internal fun communityVerdictLabel(verdict: CommunityCompatibilityVerdict): Int = when (verdict) {
    CommunityCompatibilityVerdict.WORKS -> R.string.community_compatibility_works
    CommunityCompatibilityVerdict.SHOULD_WORK -> R.string.community_compatibility_should
    CommunityCompatibilityVerdict.MAY_WORK -> R.string.community_compatibility_may
    CommunityCompatibilityVerdict.MIXED -> R.string.community_compatibility_mixed
    CommunityCompatibilityVerdict.WONT_WORK -> R.string.community_compatibility_wont
    CommunityCompatibilityVerdict.UNKNOWN -> R.string.community_compatibility_unknown
}

/**
 * Style configuration for a compatibility badge.
 */
internal data class BadgeStyle(
    val icon: ImageVector,
    val backgroundColor: Color,
    val iconTint: Color,
    val labelResId: Int,
)

/**
 * Gets the badge style for a given compatibility status.
 */
@Composable
private fun getBadgeStyle(status: GameCompatibilityStatus): BadgeStyle {
    val colors = PluviaTheme.colors
    return when (status) {
        GameCompatibilityStatus.COMPATIBLE -> BadgeStyle(
            icon = Icons.Rounded.Verified,
            backgroundColor = colors.compatibilityGoodBackground.copy(alpha = 0.9f),
            iconTint = colors.compatibilityGood,
            labelResId = R.string.library_compatible,
        )

        GameCompatibilityStatus.GPU_COMPATIBLE -> BadgeStyle(
            icon = Icons.Rounded.Verified,
            backgroundColor = colors.compatibilityGoodBackground.copy(alpha = 0.9f),
            iconTint = colors.compatibilityGood,
            labelResId = R.string.library_compatible,
        )

        GameCompatibilityStatus.UNKNOWN -> BadgeStyle(
            icon = Icons.Rounded.QuestionMark,
            backgroundColor = colors.compatibilityUnknownBackground.copy(alpha = 0.8f),
            iconTint = colors.compatibilityUnknown,
            labelResId = R.string.library_compatibility_unknown,
        )

        GameCompatibilityStatus.NOT_COMPATIBLE -> BadgeStyle(
            icon = Icons.Rounded.Close,
            backgroundColor = colors.compatibilityBadBackground.copy(alpha = 0.9f),
            iconTint = colors.compatibilityBad,
            labelResId = R.string.library_not_compatible,
        )

        GameCompatibilityStatus.RECOMMENDED -> BadgeStyle(
            icon = Icons.Rounded.Star,
            backgroundColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
            iconTint = MaterialTheme.colorScheme.onPrimaryContainer,
            labelResId = R.string.recommended_badge,
        )
    }
}

@Composable
internal fun getCommunityBadgeStyle(verdict: CommunityCompatibilityVerdict, compact: Boolean = true): BadgeStyle {
    val colors = PluviaTheme.colors
    val lightDetailSurface = !compact && MaterialTheme.colorScheme.surfaceContainerHigh.luminance() > 0.5f
    return when (verdict) {
        CommunityCompatibilityVerdict.WORKS -> BadgeStyle(
            icon = Icons.Rounded.Verified,
            backgroundColor = if (lightDetailSurface) Color(0xFFE8F5E9) else Color(0xFF174527).copy(alpha = 0.94f),
            iconTint = if (lightDetailSurface) Color(0xFF256029) else Color(0xFF81C784),
            labelResId = communityVerdictLabel(verdict),
        )
        CommunityCompatibilityVerdict.SHOULD_WORK -> BadgeStyle(
            icon = Icons.Rounded.CheckCircle,
            backgroundColor = if (lightDetailSurface) Color(0xFFF1F8E9) else Color(0xFF29462B).copy(alpha = 0.9f),
            iconTint = if (lightDetailSurface) Color(0xFF44651C) else Color(0xFFC5E1A5),
            labelResId = communityVerdictLabel(verdict),
        )
        CommunityCompatibilityVerdict.MAY_WORK -> BadgeStyle(
            icon = Icons.Rounded.HelpOutline,
            backgroundColor = if (lightDetailSurface) Color(0xFFFFF8E1) else Color(0xFF584500).copy(alpha = 0.9f),
            iconTint = if (lightDetailSurface) Color(0xFF795C00) else Color(0xFFFFE082),
            labelResId = communityVerdictLabel(verdict),
        )
        CommunityCompatibilityVerdict.MIXED -> BadgeStyle(
            icon = Icons.Rounded.Warning,
            backgroundColor = if (lightDetailSurface) Color(0xFFFFF3E0) else Color(0xFF5A310C).copy(alpha = 0.9f),
            iconTint = if (lightDetailSurface) Color(0xFF8D4300) else Color(0xFFFFB74D),
            labelResId = communityVerdictLabel(verdict),
        )
        CommunityCompatibilityVerdict.WONT_WORK -> BadgeStyle(
            icon = Icons.Rounded.Close,
            backgroundColor = if (lightDetailSurface) Color(0xFFFFEBEE) else colors.compatibilityBadBackground.copy(alpha = 0.94f),
            iconTint = Color.White,
            labelResId = communityVerdictLabel(verdict),
        )
        CommunityCompatibilityVerdict.UNKNOWN -> BadgeStyle(
            icon = Icons.Rounded.QuestionMark,
            backgroundColor = if (lightDetailSurface) Color(0xFFEEEEEE) else Color(0xFF1B1B1B).copy(alpha = 0.9f),
            iconTint = if (lightDetailSurface) Color(0xFF616161) else colors.compatibilityUnknown,
            labelResId = communityVerdictLabel(verdict),
        )
    }.let { style ->
        if (compact) {
            style
        } else {
            style.copy(
                backgroundColor = style.backgroundColor.copy(
                    alpha = when (verdict) {
                        CommunityCompatibilityVerdict.SHOULD_WORK -> 0.38f
                        CommunityCompatibilityVerdict.UNKNOWN -> 0.4f
                        else -> 0.45f
                    },
                ),
                iconTint = if (verdict == CommunityCompatibilityVerdict.WONT_WORK) {
                    if (lightDetailSurface) Color(0xFFB71C1C) else Color(0xFFFF8A80)
                } else {
                    style.iconTint
                },
            )
        }
    }
}

/**
 * Pill-shaped badge with icon and label (for list views).
 */
@Composable
private fun PillBadge(
    modifier: Modifier,
    icon: ImageVector,
    backgroundColor: Color,
    iconTint: Color,
    label: Int,
) {
    val descriptionText = stringResource(label)
    Layout(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = descriptionText }
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor),
        content = {
            Icon(imageVector = icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(14.dp))
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelSmall,
                color = iconTint,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
            )
        },
    ) { measurables, constraints ->
        val horizontalPadding = 8.dp.roundToPx()
        val verticalPadding = 4.dp.roundToPx()
        val gap = 4.dp.roundToPx()
        // Measure once at the actual font scale. Narrow cards keep the icon and spoken label,
        // rather than clipping a verdict or adding per-card subcomposition/recomposition.
        val iconPlaceable = measurables[0].measure(Constraints())
        val textPlaceable = measurables[1].measure(Constraints())
        val showText = iconPlaceable.width + gap + textPlaceable.width + horizontalPadding * 2 <= constraints.maxWidth
        val contentWidth = iconPlaceable.width + if (showText) gap + textPlaceable.width else 0
        val contentHeight = maxOf(iconPlaceable.height, if (showText) textPlaceable.height else 0)
        val width = constraints.constrainWidth(contentWidth + horizontalPadding * 2)
        val height = constraints.constrainHeight(contentHeight + verticalPadding * 2)
        layout(width, height) {
            iconPlaceable.placeRelative(horizontalPadding, (height - iconPlaceable.height) / 2)
            if (showText) {
                textPlaceable.placeRelative(horizontalPadding + iconPlaceable.width + gap, (height - textPlaceable.height) / 2)
            }
        }
    }
}

/**
 * Circular icon-only badge (for grid views).
 */
@Composable
private fun IconBadge(
    modifier: Modifier,
    icon: ImageVector,
    backgroundColor: Color,
    iconTint: Color,
    contentDescription: Int,
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .shadow(4.dp, CircleShape)
            .clip(CircleShape)
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(contentDescription),
            tint = iconTint,
            modifier = Modifier.size(14.dp),
        )
    }
}
