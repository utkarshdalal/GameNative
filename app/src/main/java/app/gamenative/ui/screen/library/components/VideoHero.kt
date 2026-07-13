package app.gamenative.ui.screen.library.components

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.skydoves.landscapist.ImageOptions
import com.skydoves.landscapist.coil.CoilImage

@Composable
internal fun VideoHero(
    videoUrl: String?,
    fallbackImageUrl: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val context = LocalContext.current
    val youTubeId = remember(videoUrl) { videoUrl?.let(::extractYouTubeId) }
    val thumbnail = youTubeId?.let { "https://img.youtube.com/vi/$it/hqdefault.jpg" } ?: fallbackImageUrl

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        CoilImage(
            imageModel = { thumbnail },
            imageOptions = ImageOptions(
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
            ),
            failure = {
                CoilImage(
                    imageModel = { fallbackImageUrl },
                    imageOptions = ImageOptions(contentScale = ContentScale.Crop),
                    modifier = Modifier.fillMaxSize(),
                )
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (videoUrl != null) {
            IconButton(
                onClick = {
                    val watchUrl = youTubeId?.let { "https://www.youtube.com/watch?v=$it" } ?: videoUrl
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, watchUrl.toUri())) }
                },
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = contentDescription,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }
}

private fun extractYouTubeId(url: String): String? {
    val patterns = listOf(
        Regex("""youtube(?:-nocookie)?\.com/embed/([\w-]{11})"""),
        Regex("""youtu\.be/([\w-]{11})"""),
        Regex("""[?&]v=([\w-]{11})"""),
    )
    for (pattern in patterns) {
        pattern.find(url)?.let { return it.groupValues[1] }
    }
    return null
}
