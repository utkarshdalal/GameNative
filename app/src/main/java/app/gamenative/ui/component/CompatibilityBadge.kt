package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QuestionMark
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
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

/**
 * Style configuration for a compatibility badge.
 */
private data class BadgeStyle(
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
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = iconTint,
            fontWeight = FontWeight.Medium,
        )
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

