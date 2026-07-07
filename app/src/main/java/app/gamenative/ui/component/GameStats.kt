package app.gamenative.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Timer
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
import androidx.compose.ui.unit.sp
import app.gamenative.R
import app.gamenative.ui.data.GameCardStats

/**
 * The card stats, in display order, paired with the icon that represents each. The reviews entry
 * shows device and GPU counts together as "device/GPU". Decoded by [GameStatsKey].
 */
private data class StatEntry(val icon: ImageVector, val value: String)

private fun Int?.orUnknown(): String = this?.toString() ?: "?"

private fun statEntries(stats: GameCardStats?): List<StatEntry> = listOf(
    StatEntry(Icons.Rounded.SportsEsports, (stats?.runsGpu ?: 0).toString()),
    StatEntry(Icons.Rounded.Star, "${stats?.reviewsGpu ?: 0}/${stats?.reviewsDevice ?: 0}"),
    StatEntry(Icons.Rounded.Speed, (stats?.fps).orUnknown()),
    StatEntry(Icons.Rounded.Timer, (stats?.sessionSec)?.let { formatSessionLength(it) } ?: "?"),
)

/**
 * Compact horizontal row of device play stats shown under a card's title. Auto-scrolls (marquee)
 * when [animate] is true and the values are too wide to fit the card. Renders nothing when [stats] is null.
 *
 * @param tint Color for icons and text.
 * @param onDark When true, text gets a subtle shadow for legibility over images.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GameStatsRow(
    stats: GameCardStats?,
    tint: Color,
    modifier: Modifier = Modifier,
    onDark: Boolean = false,
    animate: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (animate) Modifier.basicMarquee() else Modifier),
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
            modifier = Modifier.size(11.dp),
        )
        val textStyle = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp)
        Text(
            text = value,
            style = if (onDark) textStyle.copy(shadow = Shadow(color = Color.Black, blurRadius = 2f)) else textStyle,
            color = tint,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
        )
    }
}

/**
 * Vertical key explaining each stat icon, with device-specific descriptions. Designed to sit at the
 * top of the library options panel. Matches the white icon/text styling used on the game cards.
 */
@Composable
fun GameStatsKey(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        KeyRow(Icons.Rounded.SportsEsports, stringResource(R.string.stats_key_runs))
        KeyRow(Icons.Rounded.Star, stringResource(R.string.stats_key_reviews))
        KeyRow(Icons.Rounded.Speed, stringResource(R.string.stats_key_fps))
        KeyRow(Icons.Rounded.Timer, stringResource(R.string.stats_key_session))
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
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
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
