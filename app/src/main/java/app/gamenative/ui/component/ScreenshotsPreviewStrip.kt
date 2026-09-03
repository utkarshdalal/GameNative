package app.gamenative.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.gamenative.R
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

/** Max thumbnails shown inline; any beyond this are summed into a "+N" stack tile. */
private const val PREVIEW_MAX = 3

/**
 * Compact screenshots card for the game detail screen, built on [InfoCard]. Shows up to [PREVIEW_MAX]
 * thumbnails, then a "+N" tile for the rest. Renders nothing when the game has no screenshots.
 */
@Composable
fun ScreenshotsPreviewStrip(
    appId: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items by rememberScreenshots(appId)
    if (items.isEmpty()) return

    val preview = items.take(PREVIEW_MAX)
    val remaining = items.size - preview.size

    InfoCard(
        label = stringResource(R.string.screenshots_title),
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            preview.forEach { item ->
                ScreenshotTile {
                    CoilImage(
                        imageModel = { item.file },
                        imageOptions = ImageOptions(),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (remaining > 0) {
                ScreenshotTile {
                    // Stack tile: next screenshot dimmed with the remaining count on top.
                    CoilImage(
                        imageModel = { items[PREVIEW_MAX].file },
                        imageOptions = ImageOptions(),
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "+$remaining",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ScreenshotTile(
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .height(64.dp)
            .clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
