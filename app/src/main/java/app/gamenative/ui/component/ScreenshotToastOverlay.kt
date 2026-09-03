package app.gamenative.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.R
import app.gamenative.ui.util.ScreenshotNotification
import app.gamenative.ui.util.ScreenshotNotificationManager
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * In-game toast shown when a screenshot is captured: thumbnail + "Screenshot saved", auto-dismissing.
 * Mirrors [AchievementOverlay]; host it in the same top-level Box.
 */
@Composable
fun BoxScope.ScreenshotToastOverlay(onOpen: (ScreenshotNotification) -> Unit = {}) {
    var current by remember { mutableStateOf<ScreenshotNotification?>(null) }
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // collectLatest: a new screenshot replaces the current toast immediately.
        ScreenshotNotificationManager.notifications.collectLatest { notification ->
            current = notification
            visible = true
            delay(2500)
            visible = false
            delay(400)
            current = null
        }
    }

    AnimatedVisibility(
        visible = visible,
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(16.dp),
        enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
        exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(),
    ) {
        current?.let { notification ->
            ScreenshotToastContent(
                notification = notification,
                // Tapping the toast dismisses it and opens the just-captured shot in the viewer.
                onClick = {
                    visible = false
                    onOpen(notification)
                },
            )
        }
    }
}

@Composable
private fun ScreenshotToastContent(
    notification: ScreenshotNotification,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = 6.dp,
        tonalElevation = 2.dp,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoilImage(
                modifier = Modifier
                    .height(40.dp)
                    .width(71.dp) // 16:9 thumbnail
                    .clip(RoundedCornerShape(6.dp)),
                imageModel = { notification.file },
                imageOptions = ImageOptions(contentScale = ContentScale.Crop),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.screenshot_saved),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
