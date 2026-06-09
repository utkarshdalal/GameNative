package app.gamenative.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.utils.DeviceGameStatsService.DeviceGameStats

/**
 * The four device stats, in display order, paired with the icon that represents each.
 * Decoded for the user by [GameStatsKey] (shown in the library options panel).
 */
private data class StatEntry(val icon: ImageVector, val value: String)

@Composable
private fun statEntries(stats: DeviceGameStats): List<StatEntry> = listOf(
    StatEntry(Icons.Rounded.SportsEsports, stats.successfulRuns.toString()),
    StatEntry(Icons.Rounded.Star, stats.fiveStarReviews.toString()),
    StatEntry(Icons.Rounded.Speed, stats.medianFps.toString()),
    StatEntry(Icons.Rounded.Schedule, formatSessionLength(stats.medianSessionSec)),
)

/**
 * Compact horizontal row of device play stats shown under a card's title. Auto-scrolls (marquee)
 * when the values are too wide to fit the card. Renders nothing when [stats] is null.
 *
 * @param tint Color for icons and text.
 * @param onDark When true, text gets a subtle shadow for legibility over images.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameStatsRow(
    stats: DeviceGameStats?,
    tint: Color,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
) {
    if (stats == null) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .basicMarquee(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        statEntries(stats).forEach { entry ->
            StatItem(icon = entry.icon, value = entry.value, tint = tint, onDark = onDark)
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    value: String,
    tint: Color,
    onDark: Boolean,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.let {
                if (onDark) it.copy(shadow = Shadow(color = Color.Black, blurRadius = 2f)) else it
            },
            color = tint,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/**
 * Vertical key explaining each stat icon, with device-specific descriptions. Designed to sit at the
 * top of the library options panel; its rows match the style of [OptionListItem].
 */
@Composable
fun GameStatsKey(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        KeyRow(Icons.Rounded.SportsEsports, stringResource(R.string.stats_key_runs))
        KeyRow(Icons.Rounded.Star, stringResource(R.string.stats_key_reviews))
        KeyRow(Icons.Rounded.Speed, stringResource(R.string.stats_key_fps))
        KeyRow(Icons.Rounded.Schedule, stringResource(R.string.stats_key_session))
    }
}

@Composable
private fun KeyRow(
    icon: ImageVector,
    label: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Formats a median session length (seconds) into a compact string, e.g. 284 -> "4m", 7200 -> "2h". */
private fun formatSessionLength(seconds: Int): String {
    if (seconds <= 0) return "0m"
    val minutes = seconds / 60
    return when {
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h${minutes % 60}m"
    }
}
